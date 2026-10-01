package com.deepseek.pet.model

import androidx.annotation.DrawableRes
import com.deepseek.pet.R

/**
 * 悬浮球上显示哪张立绘。
 *
 * 只有两态（需求已从"按余额换三档表情"改为"待机/点击"两态）：
 *   待机 → 托腮笑；点击 → 愤怒（点一下会炸毛）。
 *
 * 余额状态仍然通过 [Mood] 的主题色（光晕 / 卡片强调色 / 通知文案）表达。
 */
enum class PetSprite(@DrawableRes val res: Int) {
    /** 待机：托腮笑 */
    IDLE(R.drawable.ic_face_idle),

    /** 点击：愤怒 */
    CLICKED(R.drawable.ic_face_angry);

    companion object {
        /** 愤怒表情展示多久后自动切回待机。 */
        const val CLICK_REACT_MS = 1600L
    }
}
