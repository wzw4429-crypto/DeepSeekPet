package com.deepseek.pet.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 余额的唯一数据源：Activity 与前台 Service 共用同一份 StateFlow，
 * 谁刷新都行，两边同时更新。
 */
class BalanceRepository private constructor(private val appContext: Context) {

    private val api = DeepSeekApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<BalanceState>(BalanceState.Loading)
    val state: StateFlow<BalanceState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var inFlight: Job? = null

    /** 手动/自动刷新共用入口，重复调用会被合并。 */
    fun refresh() {
        if (inFlight?.isActive == true) return

        val key = SecurePrefs.apiKey(appContext)
        if (key.isBlank()) {
            _state.value = BalanceState.MissingApiKey
            return
        }

        inFlight = scope.launch {
            _refreshing.value = true
            if (_state.value !is BalanceState.Success) {
                _state.value = BalanceState.Loading
            }
            try {
                val info = api.fetchBalance(key)
                _state.value = BalanceState.Success(info, System.currentTimeMillis())
            } catch (e: DeepSeekException) {
                _state.value = BalanceState.Error(e.message ?: "查询失败")
            } catch (e: Exception) {
                _state.value = BalanceState.Error(e.message ?: "查询失败")
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * 每 [intervalMs] 自动刷新一次（需求：10 分钟）。
     * 首次调用会立即拉一次，随后按间隔循环。
     */
    fun startAutoRefresh(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob = scope.launch {
            refresh()
            while (isActive) {
                delay(intervalMs)
                refresh()
                // refresh() 是异步派发的，稍等它落地，避免空转
                delay(300)
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 10 * 60 * 1000L

        @Volatile
        private var instance: BalanceRepository? = null

        fun get(context: Context): BalanceRepository {
            return instance ?: synchronized(this) {
                instance ?: BalanceRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
