package com.deepseek.pet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 视觉基线：小米澎湃 OS（HyperOS）。
 *
 * 取这套 token 的四个硬性特征：
 *   1. 页面底色是浅灰 #F2F3F5，卡片是纯白且**不带阴影**，靠留白和大圆角分层；
 *   2. 圆角普遍偏大：卡片 20dp、内层图标容器 14dp、按钮全圆角；
 *   3. 强调色是 HyperOS 蓝 #3482FF，只在关键数值/主按钮上出现，其余全灰阶；
 *   4. 图标一律放进圆角方形「色块容器」里（squircle + 8% 透明度同色底），
 *      这是澎湃 OS 设置页最显眼的识别点。
 */

// ---- 主色 ----
val HyperBlue = Color(0xFF3482FF)
val HyperBluePressed = Color(0xFF2668D9)
val HyperBlueContainer = Color(0x1F3482FF)

// ---- 状态色（三档表情）----
val MoodHappy = Color(0xFF05B36B)
val MoodWorried = Color(0xFFFF8A00)
val MoodCry = Color(0xFFF5423B)

// ---- 灰阶 ----
val HyperBg = Color(0xFFF2F3F5)
val HyperCard = Color(0xFFFFFFFF)
val HyperTextPrimary = Color(0xFF000000)
val HyperTextSecondary = Color(0xB3000000) // 70%
val HyperTextTertiary = Color(0x80000000)  // 50%
val HyperDivider = Color(0x14000000)
val HyperFill = Color(0xFFF7F8FA)

private val HyperColorScheme = lightColorScheme(
    primary = HyperBlue,
    onPrimary = Color.White,
    primaryContainer = HyperBlueContainer,
    onPrimaryContainer = HyperBlue,
    secondary = HyperBlue,
    onSecondary = Color.White,
    background = HyperBg,
    onBackground = HyperTextPrimary,
    surface = HyperCard,
    onSurface = HyperTextPrimary,
    surfaceVariant = HyperFill,
    onSurfaceVariant = HyperTextSecondary,
    outline = Color(0x1F000000),
    outlineVariant = HyperDivider,
    error = MoodCry,
    onError = Color.White
)

/** 澎湃 OS 的圆角阶梯：大卡片 20，内层 14，小控件 10，按钮走 pill。 */
private val HyperShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val HyperTypography = Typography().let { base ->
    base.copy(
        // 页面大标题：HyperOS 用大字号 + 紧字距压住页面
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 30.sp,
            letterSpacing = (-0.5).sp
        ),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp)
    )
}

/** 列表项 / 卡片里的圆角图标容器尺寸。 */
object HyperIconBox {
    val size = 36.dp
    val shape = RoundedCornerShape(11.dp)
}

@Composable
fun DeepSeekPetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HyperColorScheme,
        shapes = HyperShapes,
        typography = HyperTypography,
        content = content
    )
}
