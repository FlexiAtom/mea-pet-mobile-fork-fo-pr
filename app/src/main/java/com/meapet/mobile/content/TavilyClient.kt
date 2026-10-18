package com.meapet.mobile.content

import android.util.Log
import com.meapet.mobile.client.HttpClientEngine
import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpRequest
import com.meapet.mobile.client.RequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tavily 网页检索客户端（人工裁决 2026-10-19：「应当为获取源选择失误，这个场景不应该是
 * Wikisource」——现代关键词的对口货源是网页检索，不是古籍库）。
 *
 * 形状承桌面 `sources.py::fetch_crawler_material`：**返回的是"可供 LLM 改写的资料片段"，
 * 不是可直接念的句子**——所以这一路没有命中校验闸（PC 爬虫线也没有）：网页检索按相关性
 * 返回，素材对不对由下游总结那发兜（提示词已把资料按"仅作素材"隔离）。
 * 取回的是第三方网页正文，可能夹着奇怪的句子，进气泡前必须过 [ClickPoetrySummarizer]。
 *
 * keyless 档（`X-Tavily-Access-Mode: keyless`）开箱可用：2026-10-19 开发机 IPv4 直连
 * 实测 200（中文维基条目正文）。移动端**不造 key 配置面**——桌面判语"填错 key 比不填
 * 更坏"，而裁决只要"换 Tavily 检索"，没要 key 面；哪天要接，鉴权形状照桌面补。
 */
class TavilyClient(
    private val engine: HttpClientEngine
) {

    /**
     * 一发检索。返回至多 [MAX_ITEMS] 条已归一的资料行（非空即成功）；非 2xx、解析失败、
     * 没有可用文本都返回空列表，理由经 [onMiss] 给（分档形状与检索线其余档同族）。
     */
    suspend fun search(query: String, onMiss: (String) -> Unit = {}): List<String> {
        val word = query.trim()
        if (word.isEmpty()) return emptyList()
        return try {
            val response = engine.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = URL,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        "X-Tavily-Access-Mode" to "keyless"
                    ),
                    body = RequestBody.Json(requestBody(word))
                )
            )
            if (response.statusCode !in 200..299) {
                onMiss("HTTP ${response.statusCode}")
                return emptyList()
            }
            val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val rows = parsed["results"]?.jsonArray
            if (rows == null) {
                onMiss("响应里没有 results")
                return emptyList()
            }
            val items = mutableListOf<String>()
            for (row in rows) {
                val obj = row.jsonObject
                val title = visible(obj["title"]?.jsonPrimitive?.contentOrNull)
                val content = visible(obj["content"]?.jsonPrimitive?.contentOrNull)
                val text = when {
                    title.isNotEmpty() && content.isNotEmpty() -> "$title｜$content"
                    else -> content.ifEmpty { title }
                }
                if (text.isNotEmpty()) {
                    items.add(if (text.length > ITEM_CHAR_LIMIT) text.take(ITEM_CHAR_LIMIT) + "…" else text)
                }
                if (items.size >= MAX_ITEMS) break
            }
            if (items.isEmpty()) onMiss("检索回来但没有可用文本")
            items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "tavily 取文失败：${e.message}")
            onMiss(failureOf(e))
            emptyList()
        }
    }

    private fun requestBody(word: String): String = buildJsonObject {
        put("query", word)
        put("max_results", MAX_ITEMS)
        put("search_depth", "basic")
    }.let { json.encodeToString(it) }

    companion object {
        private const val TAG = "TavilyClient"

        /** 端点常量：告知文案引用的地址必须取这里，不另抄第二份字符串（2026-10-19 开发机实测 200）。 */
        const val URL = "https://api.tavily.com/search"

        /** 桌面 `MAX_ITEMS` 同口径：一次点击用一条，多留两条是给总结那发"挑一个点"的余地。 */
        const val MAX_ITEMS = 3

        /** 桌面 `ITEM_CHAR_LIMIT` 同口径：单条截断长度——整页正文既进不了气泡，也没必要喂给总结。 */
        const val ITEM_CHAR_LIMIT = 240

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 异常 → 用户可见失败档（承 2026-10-18 分档裁决）：连接被拒/DNS 解析不出/读超时/
         * TLS 握手断——这一族都说明**链路到不了源站**（国内网络常态），归"网络不可达"；
         * Ktor CIO 会把它们裹进自己的异常里，所以沿 cause 链找类名。其余异常照裸类名给。
         */
        internal fun failureOf(e: Exception): String {
            var t: Throwable? = e
            while (t != null) {
                val name = t.javaClass.simpleName
                if (name.endsWith("UnknownHostException") || name.endsWith("ConnectException") ||
                    name.endsWith("TimeoutException") || name.endsWith("NoRouteToHostException") ||
                    name.endsWith("UnresolvedAddressException") ||
                    name.startsWith("SSLHandshake") || name.startsWith("SSLException") ||
                    name.startsWith("SSLProtocol") || name.startsWith("SSLCert")
                ) {
                    return "网络不可达（${e.javaClass.simpleName}）"
                }
                t = t.cause
            }
            return e.javaClass.simpleName
        }

        /** 用户可见归一：繁→简折叠 + 空白压平（网页素材与诗句共用这一档口径）。 */
        internal fun visible(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            return ScriptFolding.toSimplified(raw).trim().replace(Regex("\\s+"), " ")
        }
    }
}
