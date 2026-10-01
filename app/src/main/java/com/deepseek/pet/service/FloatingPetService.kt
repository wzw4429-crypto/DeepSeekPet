package com.deepseek.pet.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.deepseek.pet.data.BalanceRepository
import com.deepseek.pet.data.BalanceState
import com.deepseek.pet.data.SecurePrefs
import com.deepseek.pet.model.Mood
import com.deepseek.pet.ui.BalanceCard
import com.deepseek.pet.ui.PetBubble
import com.deepseek.pet.ui.money
import com.deepseek.pet.ui.theme.DeepSeekPetTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮宠物前台服务。
 *
 * 用 TYPE_APPLICATION_OVERLAY 在桌面上挂两个窗口：
 *   1) 圆形悬浮球（可拖动、可点击）
 *   2) 点击后展开的半透明余额卡片
 *
 * 前台服务 + START_STICKY 保证 App 退到后台后悬浮窗依然存活。
 */
class FloatingPetService :
    Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // ---- 供 Compose 使用的 Lifecycle / ViewModelStore / SavedState 三件套 ----
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var savedStateController: SavedStateRegistryController

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var cardView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var cardParams: WindowManager.LayoutParams

    private var cardAttached = false
    private var overlayVisible = true

    private val repository by lazy { BalanceRepository.get(applicationContext) }

    // Compose 直接读的 snapshot state
    private val moodState = mutableStateOf(Mood.LOADING)
    private val balanceState = mutableStateOf<BalanceState>(BalanceState.Loading)
    private val refreshingState = mutableStateOf(false)

    private var threshold = SecurePrefs.DEFAULT_THRESHOLD

    // 拖拽用
    private var downRawX = 0f
    private var downRawY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        lifecycleRegistry = LifecycleRegistry(this)
        savedStateController = SavedStateRegistryController.create(this)
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        threshold = SecurePrefs.threshold(this)

        startForegroundSafely()
        observeBalance()
        addBubble()

        repository.startAutoRefresh(BalanceRepository.DEFAULT_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 只要被 startForegroundService 拉起，就必须尽快进前台，否则系统会 ANR 崩溃
        startForegroundSafely()

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> repository.refresh()
            ACTION_TOGGLE -> toggleOverlay()
            else -> if (!hasOverlayPermission()) {
                // 权限被撤销（用户手动关掉）→ 无法常驻，自行退出
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bubbleView.isInitialized) {
            clampBubble()
            if (cardAttached) repositionCard()
        }
    }

    override fun onDestroy() {
        isRunning = false
        repository.stopAutoRefresh()
        scope.cancel()
        removeCard()
        if (::bubbleView.isInitialized) {
            runCatching { windowManager.removeView(bubbleView) }
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 前台服务 / 通知
    // ------------------------------------------------------------------

    private fun startForegroundSafely() {
        val notification = PetNotification.build(this, moodState.value, notificationText())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                PetNotification.ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ServiceCompat.startForeground(
                this,
                PetNotification.ID,
                notification,
                0
            )
        }
    }

    private fun notificationText(): String = when (val s = balanceState.value) {
        is BalanceState.Success ->
            "总余额 ¥${money(s.info.totalBalance)} · 每 10 分钟自动刷新"
        is BalanceState.Error -> s.message
        BalanceState.MissingApiKey -> "点击进入 App 填写 API Key"
        BalanceState.Loading -> "正在查询余额…"
    }

    private fun observeBalance() {
        scope.launch {
            repository.state.collect { state ->
                balanceState.value = state
                val mood = Mood.from(state, threshold)
                moodState.value = mood
                PetNotification.update(this@FloatingPetService, mood, notificationText())
            }
        }
        scope.launch {
            repository.refreshing.collect { refreshingState.value = it }
        }
    }

    // ------------------------------------------------------------------
    // 悬浮球
    // ------------------------------------------------------------------

    /**
     * 给窗口里的 View 注入 Lifecycle / ViewModelStore / SavedState 三件套。
     *
     * **必须加在 `windowManager.addView()` 的那个根 View 上**：ComposeView 的
     * `resolveParentCompositionContext()` 会一路走到 window root 才去取
     * ViewTreeLifecycleOwner，只挂在 ComposeView 自己身上是没用的，根上取不到
     * 就直接抛 IllegalStateException，整个进程崩掉、窗口随之消失。
     */
    private fun attachOwners(target: View) {
        target.setViewTreeLifecycleOwner(this@FloatingPetService)
        target.setViewTreeViewModelStoreOwner(this@FloatingPetService)
        target.setViewTreeSavedStateRegistryOwner(this@FloatingPetService)
    }

    private fun addBubble() {
        val size = dp(BUBBLE_SIZE_DP)
        bubbleParams = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (savedX, savedY) = SecurePrefs.bubblePosition(this@FloatingPetService)
            x = savedX
            y = savedY
        }

        val container = FrameLayout(this)
        // owner 必须挂在**窗口根 View** 上：Compose 是从 window root 往上找
        // ViewTreeLifecycleOwner 的（找不到直接抛 IllegalStateException，进程崩溃）。
        attachOwners(container)
        val composeView = ComposeView(this).apply {
            attachOwners(this)
            setContent {
                DeepSeekPetTheme {
                    PetBubble(mood = moodState.value)
                }
            }
        }
        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.setOnTouchListener(bubbleTouchListener)

        bubbleView = container
        addWindowView(bubbleView, bubbleParams, "bubble")
        bubbleView.post { clampBubble() }
    }

    /**
     * 加窗口。之前这里是 `runCatching {}`，异常被静默吞掉 —— 排查「桌面上什么都不显示」时
     * 一行线索都没有。现在必须落日志。
     */
    private fun addWindowView(view: View, params: WindowManager.LayoutParams, tag: String) {
        try {
            windowManager.addView(view, params)
            Log.i(TAG, "overlay $tag added, size=${params.width}x${params.height} pos=${params.x},${params.y}")
        } catch (t: Throwable) {
            Log.e(TAG, "overlay $tag addView failed", t)
        }
    }

    private val bubbleTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                originX = bubbleParams.x
                originY = bubbleParams.y
                dragging = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    // 一开始拖就收起卡片，免得挡手
                    removeCard()
                }
                if (dragging) {
                    bubbleParams.x = originX + dx.toInt()
                    bubbleParams.y = originY + dy.toInt()
                    applyBubbleParams()
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    clampBubble()
                    SecurePrefs.saveBubblePosition(
                        this,
                        bubbleParams.x,
                        bubbleParams.y
                    )
                } else {
                    // 轻点：展开 / 收起余额卡片
                    if (cardAttached) removeCard() else showCard()
                }
                dragging = false
                true
            }

            else -> false
        }
    }

    private fun applyBubbleParams() {
        runCatching { windowManager.updateViewLayout(bubbleView, bubbleParams) }
    }

    private fun clampBubble() {
        val metrics = resources.displayMetrics
        val size = dp(BUBBLE_SIZE_DP)
        val maxX = (metrics.widthPixels - size).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - size).coerceAtLeast(0)
        bubbleParams.x = bubbleParams.x.coerceIn(0, maxX)
        bubbleParams.y = bubbleParams.y.coerceIn(0, maxY)
        applyBubbleParams()
    }

    // ------------------------------------------------------------------
    // 余额卡片
    // ------------------------------------------------------------------

    private fun showCard() {
        if (cardAttached) return

        cardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val container = FrameLayout(this)
        attachOwners(container)
        val composeView = ComposeView(this).apply {
            attachOwners(this)
            setContent {
                DeepSeekPetTheme {
                    BalanceCard(
                        state = balanceState.value,
                        mood = moodState.value,
                        refreshing = refreshingState.value,
                        threshold = threshold,
                        onRefresh = { repository.refresh() },
                        onClose = { removeCard() }
                    )
                }
            }
        }
        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                dp(CARD_WIDTH_DP),
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 点卡片空白区域外不收，靠右上角的 × 或者再点一次悬浮球
        cardView = container
        cardAttached = true
        addWindowView(cardView, cardParams, "card")
        cardView.post { repositionCard() }
    }

    private fun repositionCard() {
        if (!cardAttached || cardView.width == 0 || cardView.height == 0) return
        val metrics = resources.displayMetrics
        val margin = dp(12)
        val cardW = cardView.width
        val cardH = cardView.height

        var x = bubbleParams.x + dp(BUBBLE_SIZE_DP) / 2 - cardW / 2
        x = x.coerceIn(margin, (metrics.widthPixels - cardW - margin).coerceAtLeast(margin))

        val bubbleCenterY = bubbleParams.y + dp(BUBBLE_SIZE_DP) / 2
        var y = if (bubbleCenterY > metrics.heightPixels / 2) {
            bubbleParams.y - cardH - margin          // 球在下方 → 卡片往上弹
        } else {
            bubbleParams.y + dp(BUBBLE_SIZE_DP) + margin  // 球在上方 → 卡片往下弹
        }
        y = y.coerceIn(margin, (metrics.heightPixels - cardH - margin).coerceAtLeast(margin))

        cardParams.x = x
        cardParams.y = y
        runCatching { windowManager.updateViewLayout(cardView, cardParams) }
    }

    private fun removeCard() {
        if (!cardAttached) return
        cardAttached = false
        runCatching { windowManager.removeView(cardView) }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun toggleOverlay() {
        if (overlayVisible) {
            removeCard()
            bubbleView.visibility = View.GONE
        } else {
            bubbleView.visibility = View.VISIBLE
        }
        overlayVisible = !overlayVisible
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "DeepSeekPet"

        const val ACTION_START = "com.deepseek.pet.action.START"
        const val ACTION_STOP = "com.deepseek.pet.action.STOP"
        const val ACTION_REFRESH = "com.deepseek.pet.action.REFRESH"
        const val ACTION_TOGGLE = "com.deepseek.pet.action.TOGGLE"

        private const val BUBBLE_SIZE_DP = 64
        private const val CARD_WIDTH_DP = 268

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingPetService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPetService::class.java))
        }

        fun refresh(context: Context) {
            val intent = Intent(context, FloatingPetService::class.java).setAction(ACTION_REFRESH)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
