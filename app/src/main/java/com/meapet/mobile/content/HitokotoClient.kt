package com.meapet.mobile.content

import android.util.Log
import com.meapet.mobile.client.HttpClientEngine
import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 点一下桌宠取回的"一句文本"。
 *
 * @property text 句子本体（诗词类目 c=i 时为一联诗）
 * @property from 出处篇名，可能为空
 * @property fromWho 作者，可能为空
 */
data class Poem(
    val text: String,
    val from: String?,
    val fromWho: String?
) {
    /**
     * 组装为展示文本：正文加省略号截断，出处/作者另起一行署名。
     *
     * [MAX_TEXT_CHARS] 是移动端气泡口径（非桌面侧的 240），命中超长素材时截断加省略号。
     */
    fun toDisplayLine(): String {
        val body = if (text.length > MAX_TEXT_CHARS) text.take(MAX_TEXT_CHARS) + "…" else text
        val attribution = buildString {
            val who = fromWho?.takeIf { it.isNotBlank() }
            val src = from?.takeIf { it.isNotBlank() }
            if (who != null) append(who)
            if (src != null) append("《").append(src).append("》")
        }
        return if (attribution.isEmpty()) body else "$body\n—— $attribution"
    }

    companion object {
        const val MAX_TEXT_CHARS = 120
    }
}

/**
 * hitokoto（一言）诗词源客户端：免 key、随机一句、**不接受关键词**（不外发用户任何文字）。
 *
 * 只负责"发一发 + 判成败 + 解析"，不含超时/重试预算（那是 [ClickPoetryFetcher] 的职责）。
 * 用 [engine] 抽象，单元测试注入 FakeHttpClientEngine，无需真实网络。
 */
class HitokotoClient(
    private val engine: HttpClientEngine
) {

    /**
     * 取一句诗。任何非"可用命中"（非 2xx、空句、解析失败）都返回 null，不抛异常。
     */
    suspend fun fetch(): Poem? {
        return try {
            val response = engine.execute(
                HttpRequest(
                    method = HttpMethod.GET,
                    url = URL,
                    headers = mapOf("Accept" to "application/json")
                )
            )
            // 桌面侧判语：HTTP 状态码不足以判成败，还要看体是否给出可用句
            if (response.statusCode !in 200..299) {
                Log.w(TAG, "hitokoto 非 2xx：${response.statusCode}")
                return null
            }
            val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val text = obj["hitokoto"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) {
                Log.w(TAG, "hitokoto 返回空句")
                return null
            }
            Poem(
                text = text,
                from = obj["from"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
                fromWho = obj["from_who"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "hitokoto 取文失败：${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "HitokotoClient"

        /** 诗词类目 `c=i`，`encode=json` 直接返回结构化字段。 */
        const val URL = "https://v1.hitokoto.cn/?c=i&encode=json"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
