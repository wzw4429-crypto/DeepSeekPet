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
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
import com.deepseek.pet.model.PetSprite
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
import kotlin.math.roundToInt

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

    /** 待机/点击两态立绘。**只要余额卡片开着就一直是点击形象。** */
    private val spriteState = mutableStateOf(PetSprite.IDLE)

    private val revertSprite = Runnable { spriteState.value = PetSprite.IDLE }

    /**
     * 轻点悬浮球：开 / 关余额卡片。
     *
     * 形象规则：卡片展开期间恒为点击形象（不计时回退），卡片关闭才回待机；
     * 只有"卡片没开成功"这种异常才用 [PetSprite.CLICK_REACT_MS] 兜底，
     * 免得卡在点击形象不动。
     */
    private fun toggleCard() {
        if (cardAttached) {
            removeCard()
            return
        }
        spriteState.value = PetSprite.CLICKED
        showCard()
        val view = if (::bubbleView.isInitialized) bubbleView else return
        view.removeCallbacks(revertSprite)
        if (!cardAttached) {
            view.postDelayed(revertSprite, PetSprite.CLICK_REACT_MS)
        }
    }

    private var threshold = SecurePrefs.DEFAULT_THRESHOLD

    /** 桌面人物缩放（应用内滑杆可调），同时驱动窗口尺寸与立绘绘制高度。 */
    private val scaleState = mutableStateOf(1f)

    /** 立绘窗口宽高（dp），随缩放变化。 */
    private fun petWidthDp(): Int =
        (PET_W_DP * scaleState.value).roundToInt().coerceAtLeast(40)

    private fun petHeightDp(): Int =
        (PET_H_DP * scaleState.value).roundToInt().coerceAtLeast(56)

    /**
     * 重新设置悬浮球窗口尺寸 —— 应用内调完滑杆后立刻生效。
     * 窗口尺寸变了要重新 clamp，否则放大后可能有一部分跑到屏幕外。
     */
    private fun applyBubbleSize() {
        if (!::bubbleView.isInitialized) return
        val w = dp(petWidthDp())
        val h = dp(petHeightDp())
        if (bubbleParams.width == w && bubbleParams.height == h) return
        bubbleParams.width = w
        bubbleParams.height = h
        applyBubbleParams()
        clampBubble()
    }

    // 拖拽用
    private var downRawX = 0f
    private var downRawY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false

    /**
     * 点击 / 拖动的分界线。
     *
     * 系统的 scaledTouchSlop 只有 8~24px，手指点一下屏幕时的抖动很容易超过它，
     * 于是 ACTION_UP 被判成"拖动"，卡片根本不展开 —— 表现就是"点一下有时没反应"。
     * 这里抬到至少 10dp：抖动算点击，真的想拖球肯定会超过。
     */
    private val touchSlop: Int by lazy {
        ViewConfiguration.get(this).scaledTouchSlop
            .coerceAtLeast((10 * resources.displayMetrics.density).toInt())
    }

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
        scaleState.value = SecurePrefs.bubbleScale(this)

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
        // 应用内拖动"人物大小"滑杆 → SecurePrefs 落盘并更新 StateFlow → 这里立刻重设窗口
        scope.launch {
            SecurePrefs.scale.collect { s ->
                if (s > 0f && s != scaleState.value) {
                    scaleState.value = s
                    applyBubbleSize()
                }
            }
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
        bubbleParams = WindowManager.LayoutParams(
            dp(petWidthDp()),
            dp(petHeightDp()),
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

        // 恒拦截：窗口里的 ComposeView 是子 View，会优先吃掉触摸事件，而它是否消费
        // 又随 Compose 内容（头像/加载圈）变化 —— 这就是"点击、滑动有时没反应"的来源。
        // 在这里统一拦下，所有事件都走 bubbleTouchListener。
        val container = object : FrameLayout(this@FloatingPetService) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
                ev.actionMasked != MotionEvent.ACTION_CANCEL
        }
        // owner 必须挂在**窗口根 View** 上：Compose 是从 window root 往上找
        // ViewTreeLifecycleOwner 的（找不到直接抛 IllegalStateException，进程崩溃）。
        attachOwners(container)
        val composeView = ComposeView(this).apply {
            attachOwners(this)
            setContent {
                DeepSeekPetTheme {
                    PetBubble(
                        mood = moodState.value,
                        sprite = spriteState.value,
                        // 随应用内"人物大小"同步放大/缩小，否则窗口变大人物不跟着变
                        height = (PET_H_DP * scaleState.value).dp
                    )
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

            MotionEvent.ACTION_UP -> {
                // 判定只看**本轮的累计位移**，不看 dragging 标志：
                // dragging 只要在 MOVE 里越过一次阈值就被置位，之后手指挪回原点
                // 也仍算拖动，点击就丢了 —— 表现为"点了没反应"。
                val moved = abs(event.rawX - downRawX) > touchSlop ||
                    abs(event.rawY - downRawY) > touchSlop

                if (moved) {
                    clampBubble()
                    SecurePrefs.saveBubblePosition(
                        this,
                        bubbleParams.x,
                        bubbleParams.y
                    )
                } else {
                    // 轻点：开 / 关余额卡片（卡片期间保持点击形象）
                    toggleCard()
                }
                dragging = false
                true
            }

            MotionEvent.ACTION_CANCEL -> {
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
        val w = dp(petWidthDp())
        val h = dp(petHeightDp())
        val maxX = (metrics.widthPixels - w).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - h).coerceAtLeast(0)
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
                        sprite = spriteState.value,
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

        // 关键：**先隐形挂载**。卡片初始 x=0,y=0（屏幕左上角），必须等首次布局
        // 拿到真实宽高、repositionCard() 定位之后才移动 —— 若直接显示，会先在
        // 左上角闪一帧再跳到正确位置，快速点击时肉眼非常明显。
        container.visibility = View.INVISIBLE
        addWindowView(cardView, cardParams, "card")

        cardView.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val v = cardView
                if (!cardAttached || v.width == 0 || v.height == 0) return
                if (v.viewTreeObserver.isAlive) {
                    v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
                repositionCard()
                v.visibility = View.VISIBLE
            }
        })

        // 兜底：布局回调万一没来，100ms 后也要显示，否则卡片永远出不来
        cardView.postDelayed({
            if (cardAttached && cardView.visibility != View.VISIBLE) {
                repositionCard()
                cardView.visibility = View.VISIBLE
            }
        }, FALLBACK_SHOW_MS)
    }

    private fun repositionCard() {
        if (!cardAttached || cardView.width == 0 || cardView.height == 0) return
        val metrics = resources.displayMetrics
        val margin = dp(12)
        val cardW = cardView.width
        val cardH = cardView.height

        var x = bubbleParams.x + dp(petWidthDp()) / 2 - cardW / 2
        x = x.coerceIn(margin, (metrics.widthPixels - cardW - margin).coerceAtLeast(margin))

        val bubbleCenterY = bubbleParams.y + dp(petHeightDp()) / 2
        var y = if (bubbleCenterY > metrics.heightPixels / 2) {
            bubbleParams.y - cardH - margin          // 球在下方 → 卡片往上弹
        } else {
            bubbleParams.y + dp(petHeightDp()) + margin   // 球在上方 → 卡片往下弹
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
        // 卡片一关就回待机形象
        if (::bubbleView.isInitialized) bubbleView.removeCallbacks(revertSprite)
        spriteState.value = PetSprite.IDLE
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

        // 全身立绘是竖构图（527x800 / 565x800），按高度定窗口；
        // 宽度取两张立绘中较宽的那张，避免愤怒表情被 ContentScale.Fit 压扁。
        private const val PET_W_DP = 110
        private const val PET_H_DP = 150
        private const val CARD_WIDTH_DP = 268

        /** 卡片隐形挂载后，布局回调万一没来，超过这个时间就强制显示。 */
        private const val FALLBACK_SHOW_MS = 100L

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
