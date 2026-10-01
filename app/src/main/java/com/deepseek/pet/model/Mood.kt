package com.deepseek.pet.model

import com.deepseek.pet.data.BalanceState
import com.deepseek.pet.data.SecurePrefs

/**
 * DeepSeek娘 的**余额状态**，只负责配色和文案。
 *
 * 立绘（待机/点击）由 [PetSprite] 单独管 —— 两者是正交的：
 * 立绘决定"她长什么样"，Mood 决定"光晕和文案用什么颜色"。
 *
 * 余额规则：
 *   余额充足  total >  阈值(默认 5 元)  -> HAPPY
 *   余额偏低  0 < total <= 阈值        -> WORRIED
 *   余额为零 / 查询失败 / 未配置 Key   -> CRY
 */
enum class Mood {
    LOADING,
    HAPPY,
    WORRIED,
    CRY;

    val title: String
        get() = when (this) {
            LOADING -> "正在查询余额…"
            HAPPY -> "余额充足，今天也要好好写代码呀～"
            WORRIED -> "余额有点紧张了，记得充值哦"
            CRY -> "余额告急！DeepSeek娘要饿哭了"
        }

    /** 悬浮球外圈光晕颜色（ARGB）。 */
    val accentColor: Long
        get() = when (this) {
            LOADING -> 0xFF9AA6C4
            HAPPY -> 0xFF38D39F
            WORRIED -> 0xFFFFB020
            CRY -> 0xFFFF5D6C
        }

    companion object {

        fun from(state: BalanceState, threshold: Double = SecurePrefs.DEFAULT_THRESHOLD): Mood =
            when (state) {
                is BalanceState.Loading -> LOADING
                is BalanceState.MissingApiKey -> CRY
                is BalanceState.Error -> CRY
                is BalanceState.Success -> fromBalance(state.info.totalBalance, threshold)
            }

        fun fromBalance(total: Double, threshold: Double = SecurePrefs.DEFAULT_THRESHOLD): Mood =
            when {
                total <= 0.0 -> CRY
                total <= threshold -> WORRIED
                else -> HAPPY
            }
    }
}
