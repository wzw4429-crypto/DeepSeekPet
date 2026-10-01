package com.deepseek.pet.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DeepSeekException(message: String) : Exception(message)

/**
 * DeepSeek 官方余额接口：
 *   GET https://api.deepseek.com/user/balance
 *   Authorization: Bearer {API_KEY}
 *
 * 返回样例：
 * {
 *   "is_available": true,
 *   "balance_infos": [
 *     {"currency":"CNY","total_balance":"110.00",
 *      "granted_balance":"10.00","topped_up_balance":"100.00"}
 *   ]
 * }
 */
class DeepSeekApi(
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun fetchBalance(apiKey: String): BalanceInfo = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) throw DeepSeekException("尚未配置 API Key")

        val request = Request.Builder()
            .url(BALANCE_URL)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw DeepSeekException(describeHttpError(response.code, body))
                }
                parse(body)
            }
        } catch (e: DeepSeekException) {
            throw e
        } catch (e: IOException) {
            throw DeepSeekException("网络连接失败：${e.message ?: "请检查网络"}")
        } catch (e: Exception) {
            throw DeepSeekException("解析响应失败：${e.message ?: "未知错误"}")
        }
    }

    private fun parse(body: String): BalanceInfo {
        val json = JSONObject(body)
        val available = json.optBoolean("is_available", false)
        val array = json.optJSONArray("balance_infos")
            ?: throw DeepSeekException("接口未返回余额字段")

        if (array.length() == 0) throw DeepSeekException("账号下没有任何余额信息")

        var fallback: JSONObject? = null
        var cny: JSONObject? = null
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (fallback == null) fallback = item
            if (item.optString("currency").equals("CNY", ignoreCase = true)) {
                cny = item
                break
            }
        }

        val item = cny ?: fallback ?: throw DeepSeekException("接口未返回余额字段")

        return BalanceInfo(
            currency = item.optString("currency", "CNY"),
            totalBalance = item.optString("total_balance", "0").toDoubleOrNull() ?: 0.0,
            grantedBalance = item.optString("granted_balance", "0").toDoubleOrNull() ?: 0.0,
            toppedUpBalance = item.optString("topped_up_balance", "0").toDoubleOrNull() ?: 0.0,
            isAvailable = available
        )
    }

    private fun describeHttpError(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")

        val base = when (code) {
            400 -> "请求参数有误（400）"
            401 -> "API Key 无效或已失效（401），请到设置页重新填写"
            402 -> "账户余额不足（402）"
            403 -> "该 Key 无权访问余额接口（403）"
            429 -> "请求过于频繁（429），请稍后再试"
            in 500..599 -> "DeepSeek 服务端异常（$code），稍后重试"
            else -> "请求失败（HTTP $code）"
        }
        return if (detail.isBlank()) base else "$base：$detail"
    }

    companion object {
        const val BALANCE_URL = "https://api.deepseek.com/user/balance"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
