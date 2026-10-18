package com.meapet.mobile.content

import android.util.Log
import com.meapet.mobile.client.HttpClientEngine
import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * 一条通过命中校验的 Wikisource 检索结果。
 *
 * @property text 展示正文（snippet 优先，已剥高亮标签、折回简体、按 [MAX_BODY_CHARS] 截断）
 * @property attribution 条目名——归属诚实：进气泡的那一截文字就是从这一条目来的
 */
data class SearchHit(
    val text: String,
    val attribution: String
) {
    fun toDisplayLine(): String = if (attribution.isBlank()) text else "$text\n—— $attribution"

    companion object {
        /** 桌面 `sources.py` wikisource 档同为 120；移动端与 [Poem.MAX_TEXT_CHARS] 统一口径。 */
        const val MAX_BODY_CHARS = 120
    }
}

/**
 * Wikisource（中医古籍文库）检索客户端：**把关键词作为检索词发过去**（出境提醒见告知文案）。
 *
 * 只负责"发一发 + 判成败 + 解析 + 命中校验过滤"，不含超时/预算/逐词轮转
 * （那是 [ClickPoetryFetcher] 的职责）。端点形状来自 2026-10-18 开发机实发取证
 * （提案 §2）：`query.search[]` 每项 `title`/`snippet`，snippet 带
 * `<span class="searchmatch">` 高亮标签，条目名繁體。
 *
 * 命中校验不过的条目逐条经 [onMiss] 记理由（桌面 misses 形状），绝不把没验过的
 * 条目念出来——"查得到"不等于"查对了"。
 */
class WikisourceClient(
    private val engine: HttpClientEngine
) {

    /**
     * 一发检索。命中返回 [SearchHit]；非 2xx、解析失败、全数未过校验都返回 null。
     *
     * @param onMiss 未过命中校验的条目理由出口（含 HTTP/解析失败），供调用侧聚合
     */
    suspend fun search(query: String, onMiss: (String) -> Unit = {}): SearchHit? {
        val variant = query.trim()
        if (variant.isEmpty()) return null
        return try {
            val response = engine.execute(
                HttpRequest(
                    method = HttpMethod.GET,
                    url = apiUrl(variant),
                    headers = mapOf("Accept" to "application/json")
                )
            )
            if (response.statusCode !in 200..299) {
                onMiss("HTTP ${response.statusCode}")
                return null
            }
            val rows = json.parseToJsonElement(response.bodyAsText())
                .jsonObject["query"]?.jsonObject?.get("search")?.jsonArray
                ?: return null.also { onMiss("响应里没有 query.search") }
            if (rows.isEmpty()) {
                onMiss("查无词条：「$variant」")
                return null
            }
            for (element in rows) {
                val row = element.jsonObject
                val title = row["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isEmpty()) continue
                if (!HitVerifier.isHit(variant, title)) {
                    onMiss("未通过命中校验：「$variant」→「${title.take(20)}」")
                    continue
                }
                val snippet = cleanSnippet(row["snippet"]?.jsonPrimitive?.contentOrNull)
                val body = snippet.ifEmpty { toVisible(title) }
                return SearchHit(text = truncate(body), attribution = titleVisible(title))
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "wikisource 取文失败：${e.message}")
            onMiss(failureOf(e))
            null
        }
    }

    private fun truncate(body: String): String =
        if (body.length > SearchHit.MAX_BODY_CHARS) body.take(SearchHit.MAX_BODY_CHARS) + "…" else body

    companion object {
        private const val TAG = "WikisourceClient"

        /**
         * 异常 → 用户可见失败档（人工裁决 2026-10-18：文案分档，不裸报异常名）。
         *
         * 连接被拒/DNS 解析不出/读超时/TLS 握手断——这一族都说明**链路到不了
         * zh.wikisource.org**（国内网络常态），归"网络不可达"；Ktor CIO 会把它们
         * 裹进自己的异常里，所以沿 cause 链找类名。其余异常照裸类名给，日志侧另有全文。
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

        /** 端点常量：告知文案引用的地址必须取这里，不另抄第二份字符串。 */
        const val API_BASE = "https://zh.wikisource.org/w/api.php"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * srsearch 一发 URL。`srnamespace=0` 只查正文命名空间（与桌面同参数组），
         * `srlimit=3` 与桌面 MAX_ITEMS 对齐——一次点击用一条，多留两条是余地不是搬整页。
         */
        fun apiUrl(query: String): String =
            "$API_BASE?action=query&list=search&srsearch=${URLEncoder.encode(query, "UTF-8")}" +
                "&srnamespace=0&srlimit=3&format=json"

        /** 剥 MediaWiki 高亮标签并折回简体、压空白（用户可见那一档已由桌面裁为仅简体）。 */
        fun cleanSnippet(raw: String?): String = toVisible(
            raw?.replace("<span class=\"searchmatch\">", "")?.replace("</span>", "")
        )

        /** 条目名可见形：剥标签族操作后简体折叠 + 空白压平。 */
        fun titleVisible(title: String): String = toVisible(title)

        private fun toVisible(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            val folded = ScriptFolding.toSimplified(raw)
            return folded.whitespaceCollapse()
        }

        private fun String.whitespaceCollapse(): String = trim().replace(Regex("\\s+"), " ")
    }
}
