package com.deepseek.pet.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * API Key 与偏好设置的落地存储。
 *
 * API Key 只存在 EncryptedSharedPreferences（AES256-GCM，密钥由 Android Keystore 托管），
 * 绝不硬编码进代码，也不进任何日志。
 */
object SecurePrefs {

    private const val FILE_NAME = "deepseek_pet_secure"
    private const val KEY_API_KEY = "deepseek_api_key"
    private const val KEY_THRESHOLD = "low_balance_threshold"

    /** 悬浮窗位置存在明文 prefs 里即可，不含敏感信息。 */
    private const val PLAIN_FILE = "deepseek_pet_ui"
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"

    const val DEFAULT_THRESHOLD = 5.0

    @Volatile
    private var encrypted: SharedPreferences? = null

    @Volatile
    private var plain: SharedPreferences? = null

    private fun secure(context: Context): SharedPreferences {
        return encrypted ?: synchronized(this) {
            encrypted ?: run {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { encrypted = it }
            }
        }
    }

    private fun ui(context: Context): SharedPreferences {
        return plain ?: synchronized(this) {
            plain ?: context.applicationContext
                .getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
                .also { plain = it }
        }
    }

    // ---- API Key ----

    fun apiKey(context: Context): String =
        runCatching { secure(context).getString(KEY_API_KEY, "").orEmpty() }
            .getOrDefault("")

    fun saveApiKey(context: Context, key: String) {
        secure(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clearApiKey(context: Context) {
        secure(context).edit().remove(KEY_API_KEY).apply()
    }

    fun hasApiKey(context: Context): Boolean = apiKey(context).isNotBlank()

    /** 只用于展示：sk-abcd****wxyz */
    fun maskedApiKey(context: Context): String {
        val key = apiKey(context)
        if (key.length <= 10) return if (key.isEmpty()) "" else "****"
        return key.take(6) + "****" + key.takeLast(4)
    }

    // ---- 预警阈值 ----

    fun threshold(context: Context): Double =
        runCatching { secure(context).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD.toFloat()).toDouble() }
            .getOrDefault(DEFAULT_THRESHOLD)

    fun saveThreshold(context: Context, value: Double) {
        secure(context).edit().putFloat(KEY_THRESHOLD, value.toFloat()).apply()
    }

    // ---- 悬浮窗位置 ----

    fun bubblePosition(context: Context): Pair<Int, Int> =
        ui(context).getInt(KEY_BUBBLE_X, 0) to ui(context).getInt(KEY_BUBBLE_Y, 320)

    fun saveBubblePosition(context: Context, x: Int, y: Int) {
        ui(context).edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
    }

    // ---- 人物大小 ----

    /** 桌面人物缩放比例的上下限（1f = 默认 110x150dp）。 */
    const val MIN_SCALE = 0.6f
    const val MAX_SCALE = 2.4f

    const val KEY_BUBBLE_SCALE = "bubble_scale"
    private const val UNSET_SCALE = -1f

    /**
     * 当前缩放比例。
     *
     * 用 StateFlow 暴露：主界面拖滑杆 → 存盘并更新这里 → 悬浮服务收到后
     * 立刻改窗口宽高并 `updateViewLayout`，人物大小即时生效。
     * 未初始化时是 [UNSET_SCALE]，首次 [bubbleScale] 读取后才变成真实值。
     */
    private val _scale = MutableStateFlow(UNSET_SCALE)
    val scale: StateFlow<Float> = _scale.asStateFlow()

    fun bubbleScale(context: Context): Float {
        val cur = _scale.value
        if (cur > 0f) return cur
        val v = ui(context).getFloat(KEY_BUBBLE_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)
        _scale.value = v
        return v
    }

    fun saveBubbleScale(context: Context, value: Float) {
        val v = value.coerceIn(MIN_SCALE, MAX_SCALE)
        ui(context).edit().putFloat(KEY_BUBBLE_SCALE, v).apply()
        _scale.value = v
    }
}
