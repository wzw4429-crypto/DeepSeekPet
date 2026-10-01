package com.deepseek.pet.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** 一次余额采样（每 10 分钟轮询成功时落一条）。 */
data class BalanceSnapshot(val time: Long, val total: Double)

/** 消耗统计区间。 */
enum class UsageRange(val label: String) {
    TODAY("今日"),
    WEEK("近7天"),
    MONTH("本月"),
    CUSTOM("自选")
}

/**
 * 一个区间推算出的消耗。
 *
 * @param amount     区间内余额下降的累计值（元）
 * @param samples    区间内采样条数
 * @param hasRecharge 区间内出现过余额上升 —— 说明有充值，数字可能偏小
 */
data class UsageSummary(
    val from: Long,
    val to: Long,
    val amount: Double,
    val samples: Int,
    val hasRecharge: Boolean
)

/**
 * 余额快照流水 —— DeepSeek **没有**用量查询接口（官方只开放 get-user-balance），
 * 所以"今天/本周/本月花了多少"只能从余额的变化推算。
 *
 * 推算规则：对区间内相邻两次快照，只累加**下降**的部分；
 * 余额上升判为充值，不计入消耗（否则充值会被算成负消耗或抵掉真实消费）。
 *
 * 精度取决于采样间隔（当前 10 分钟），是**估算值**，与控制台账单会有出入。
 */
class BalanceHistory private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cache: List<BalanceSnapshot> = emptyList()

    private val _snapshots = MutableStateFlow<List<BalanceSnapshot>>(emptyList())
    val snapshots: StateFlow<List<BalanceSnapshot>> = _snapshots.asStateFlow()

    init {
        scope.launch {
            synchronized(lock) {
                ensureLoaded()
                _snapshots.value = cache
            }
        }
    }

    /** 余额刷新成功后调用；同值且间隔很短的重复采样会被丢掉。 */
    fun record(total: Double) {
        scope.launch {
            synchronized(lock) {
                ensureLoaded()
                val now = System.currentTimeMillis()
                val last = cache.lastOrNull()
                if (last != null &&
                    now - last.time < MIN_INTERVAL_MS &&
                    abs(last.total - total) < 0.005
                ) {
                    return@synchronized
                }
                val next = (cache + BalanceSnapshot(now, total))
                    .filter { it.time >= now - KEEP_MS }
                persist(next)
            }
        }
    }

    fun current(): List<BalanceSnapshot> = synchronized(lock) {
        ensureLoaded()
        cache
    }

    fun summarize(from: Long, to: Long): UsageSummary = summarize(current(), from, to)

    private fun persist(next: List<BalanceSnapshot>) {
        cache = next
        _snapshots.value = next
        runCatching { file.writeText(encode(next)) }
    }

    private fun ensureLoaded() {
        if (!loaded) {
            cache = runCatching { decode(file) }.getOrDefault(emptyList())
            loaded = true
        }
    }

    private fun encode(list: List<BalanceSnapshot>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("t", s.time).put("b", s.total))
        }
        return arr.toString()
    }

    private var loaded = false

    companion object {
        private const val FILE_NAME = "balance_history.json"
        private const val KEEP_MS = 120L * 24 * 60 * 60 * 1000   // 只留 120 天
        private const val MIN_INTERVAL_MS = 60_000L               // 1 分钟内的同值采样视为重复

        fun decode(file: File): List<BalanceSnapshot> {
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                BalanceSnapshot(o.optLong("t"), o.optDouble("b", 0.0))
            }.filter { it.time > 0 }.sortedBy { it.time }
        }

        /**
         * 区间消耗 = 相邻快照下降段之和。
         * 基线取区间**之前**最近的一次采样，这样区间内第一段消耗也不会漏掉。
         */
        fun summarize(list: List<BalanceSnapshot>, from: Long, to: Long): UsageSummary {
            if (list.isEmpty()) return UsageSummary(from, to, 0.0, 0, false)

            val inside = list.filter { it.time > from && it.time <= to }
            val baseline = list.lastOrNull { it.time <= from }
            if (baseline == null && inside.isEmpty()) {
                return UsageSummary(from, to, 0.0, 0, false)
            }

            val seq = listOfNotNull(baseline) + inside
            var amount = 0.0
            var recharge = false
            for (i in 1 until seq.size) {
                val delta = seq[i - 1].total - seq[i].total
                if (delta > 0) amount += delta else if (delta < 0) recharge = true
            }
            return UsageSummary(from, to, amount, inside.size, recharge)
        }

        /** 把 [UsageRange] 换成时间区间 [from, to]（毫秒，含端点）。 */
        fun bounds(
            range: UsageRange,
            customFrom: LocalDate?,
            customTo: LocalDate?
        ): Pair<Long, Long> {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val now = System.currentTimeMillis()
            return when (range) {
                UsageRange.TODAY ->
                    today.atStartOfDay(zone).toInstant().toEpochMilli() to now
                UsageRange.WEEK ->
                    today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli() to now
                UsageRange.MONTH ->
                    today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli() to now
                UsageRange.CUSTOM -> {
                    val f = customFrom ?: today.minusDays(6)
                    val t = customTo ?: today
                    val from = f.atStartOfDay(zone).toInstant().toEpochMilli()
                    val to = t.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    from to to.coerceAtLeast(from)
                }
            }
        }

        /** DatePicker 的 UTC 毫秒 -> 本机日期。 */
        fun dateFromPickerMillis(millis: Long): LocalDate =
            Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()

        @Volatile
        private var instance: BalanceHistory? = null

        fun get(context: Context): BalanceHistory {
            return instance ?: synchronized(this) {
                instance ?: BalanceHistory(context.applicationContext).also { instance = it }
            }
        }
    }
}
