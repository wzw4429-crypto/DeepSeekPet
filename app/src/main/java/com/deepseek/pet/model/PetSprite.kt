package com.deepseek.pet.model

import androidx.annotation.DrawableRes
import com.deepseek.pet.R

/**
 * 悬浮球上显示哪张立绘（两态，与余额状态正交）：
 *
 *   待机 IDLE   → 愤怒（叉腰）—— 平时杵在桌面上的样子
 *   点击 CLICKED → 托腮笑 —— 点一下、以及余额卡片展开期间一直保持
 *
 * 规则：**只要余额卡片还开着，就始终是点击形象**；卡片关闭才切回待机。
 * [CLICK_REACT_MS] 只作为兜底 —— 万一卡片没开成功，1.6 秒后自己回待机，
 * 不至于卡在点击形象不动。
 *
 * 余额状态本身由 [Mood] 的配色（脚底光晕 / 卡片强调色 / 通知文案）表达。
 */
enum class PetSprite(@DrawableRes val res: Int) {
    /** 待机：愤怒叉腰 */
    IDLE(R.drawable.ic_pet_idle),

    /** 点击：托腮笑（卡片展开期间保持） */
    CLICKED(R.drawable.ic_pet_click);

    companion object {
        /** 卡片没开成功时，点击形象展示多久后自动切回待机。 */
        const val CLICK_REACT_MS = 1600L
    }
}
