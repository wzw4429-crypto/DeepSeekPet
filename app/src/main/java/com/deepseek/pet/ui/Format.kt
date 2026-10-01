package com.deepseek.pet.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

fun money(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

fun currencySymbol(currency: String): String = when (currency.uppercase(Locale.ROOT)) {
    "CNY", "RMB" -> "¥"
    "USD" -> "$"
    else -> ""
}

fun timeOf(timestamp: Long): String =
    if (timestamp <= 0L) "--" else timeFormat.format(Date(timestamp))

fun dateTimeOf(timestamp: Long): String =
    if (timestamp <= 0L) "--" else dateTimeFormat.format(Date(timestamp))

fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return "尚未查询"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚更新"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前更新"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前更新"
        else -> "${dateTimeOf(timestamp)} 更新"
    }
}
