package com.deepseek.pet.data

/** DeepSeek /user/balance 接口返回的单条币种余额。 */
data class BalanceInfo(
    val currency: String,
    val totalBalance: Double,
    val grantedBalance: Double,
    val toppedUpBalance: Double,
    val isAvailable: Boolean
)

/** 余额查询的完整状态机，驱动悬浮窗表情切换。 */
sealed interface BalanceState {

    /** 首次查询中 / 手动刷新中且尚无数据。 */
    data object Loading : BalanceState

    data class Success(
        val info: BalanceInfo,
        val updatedAt: Long
    ) : BalanceState

    data class Error(
        val message: String,
        val updatedAt: Long = System.currentTimeMillis()
    ) : BalanceState

    /** 用户还没填 API Key —— 与查询失败同样是「哭」脸，但提示文案不同。 */
    data object MissingApiKey : BalanceState
}
