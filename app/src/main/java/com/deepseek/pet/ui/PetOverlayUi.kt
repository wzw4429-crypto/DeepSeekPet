package com.deepseek.pet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.pet.data.BalanceState
import com.deepseek.pet.model.Mood
import com.deepseek.pet.model.PetSprite
import com.deepseek.pet.ui.theme.HyperBlue
import com.deepseek.pet.ui.theme.HyperTextSecondary

/*
 * 悬浮窗视觉：沿用澎湃 OS 的「桌面小组件」语言 —— 半透明白底、24dp 大圆角、
 * 圆角色块包住头像、大字号余额、其余信息压成灰阶。
 * 手势由外层 View 处理，这里只负责画。
 */

/**
 * 桌面上的 DeepSeek娘 —— 全身立绘，不做圆形裁切（要的就是"只有人物"）。
 *
 * [mood] 只决定脚下的光晕颜色（余额状态），[sprite] 决定待机/点击哪张立绘。
 */
@Composable
fun PetBubble(
    mood: Mood,
    sprite: PetSprite,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp
) {
    val accent = Color(mood.accentColor)

    Box(
        modifier = modifier.height(height),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 脚下的状态光晕：余额变了颜色跟着变，替代表情切换
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(width = height * 0.78f, height = height * 0.24f)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.55f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        Image(
            painter = painterResource(sprite.res),
            contentDescription = "DeepSeek娘",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.BottomCenter)
        )

        if (mood == Mood.LOADING) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp),
                strokeWidth = 2.dp,
                color = accent
            )
        }
    }
}

/**
 * 点击悬浮球弹出的半透明余额卡片。
 * 三行数据：总余额 / 赠金余额 / 充值余额。
 */
@Composable
fun BalanceCard(
    state: BalanceState,
    mood: Mood,
    sprite: PetSprite,
    refreshing: Boolean,
    threshold: Double,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = Color(mood.accentColor)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color(0xF2FFFFFF),
        border = BorderStroke(1.dp, Color(0x14000000)),
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ---- 头部 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 全身立绘当头像：直接放，不套圆角底框（要的就是"只有人物"）
                Image(
                    painter = painterResource(sprite.res),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(58.dp)
                        .width(40.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DeepSeek娘",
                        color = Color(0xFF000000),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when (state) {
                            is BalanceState.Success -> subtitleFor(mood, threshold)
                            is BalanceState.Error -> "查询失败"
                            BalanceState.MissingApiKey -> "尚未配置 API Key"
                            BalanceState.Loading -> "查询中…"
                        },
                        color = HyperTextSecondary,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = HyperBlue,
                        modifier = Modifier.alpha(if (refreshing) 0.35f else 1f)
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "收起",
                        tint = HyperTextSecondary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0x14000000))
            Spacer(Modifier.height(14.dp))

            when (state) {
                is BalanceState.Success -> {
                    val symbol = currencySymbol(state.info.currency)
                    Text(
                        text = "${symbol}${money(state.info.totalBalance)}",
                        color = accent,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "总余额（${state.info.currency}）",
                        color = HyperTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    StatRow("赠金余额", "${symbol}${money(state.info.grantedBalance)}")
                    Spacer(Modifier.height(8.dp))
                    StatRow("充值余额", "${symbol}${money(state.info.toppedUpBalance)}")
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = relativeTime(state.updatedAt) +
                            if (!state.info.isAvailable) " · 账户当前不可用" else "",
                        color = HyperTextSecondary,
                        fontSize = 11.sp
                    )
                }

                is BalanceState.Error -> {
                    Text(
                        text = state.message,
                        color = Color(0xFFF5423B),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "点右上角刷新重试，或回到 App 检查 API Key",
                        color = HyperTextSecondary,
                        fontSize = 12.sp
                    )
                }

                BalanceState.MissingApiKey -> {
                    Text(
                        text = "还没有填写 DeepSeek API Key",
                        color = Color(0xFFFF8A00),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "打开 App 设置页填入 Key 后即可查询余额",
                        color = HyperTextSecondary,
                        fontSize = 12.sp
                    )
                }

                BalanceState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = HyperBlue
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "正在向 DeepSeek 查询…",
                            color = HyperTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = HyperTextSecondary, fontSize = 14.sp)
        Text(
            text = value,
            color = Color(0xFF000000),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun subtitleFor(mood: Mood, threshold: Double): String = when (mood) {
    Mood.HAPPY -> "余额充足"
    Mood.WORRIED -> "余额偏低（≤ ${money(threshold)}）"
    Mood.CRY -> "余额告急"
    Mood.LOADING -> "刷新中"
}
