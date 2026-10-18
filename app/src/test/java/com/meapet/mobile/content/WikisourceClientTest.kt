package com.meapet.mobile.content

import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.test.FakeHttpClientEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wikisource 客户端单测。fixture 取 2026-10-18 开发机实发响应的形状
 * （`query.search[]` + `title`/`snippet` + `<span class="searchmatch">` 高亮标签）。
 */
class WikisourceClientTest {

    private fun response(status: Int, body: String) =
        HttpResponse(status, body.encodeToByteArray(), "application/json")

    private val hitJson = """
        {"batchcomplete":"","query":{"searchinfo":{"totalhits":71717},"search":[
          {"ns":0,"title":"靜夜思","pageid":221,
           "snippet":"<span class=\"searchmatch\">静</span>夜思，床前看月光，疑是地上霜。"},
          {"ns":0,"title":"靜夜思 (劉基)","pageid":768288,"snippet":"靜夜思，一思百轉。"}
        ]}}
    """.trimIndent()

    @Test
    fun `命中条目返回折叠简体的正文与条目名归属`() = runTest {
        val engine = FakeHttpClientEngine { response(200, hitJson) }
        val misses = mutableListOf<String>()
        val hit = WikisourceClient(engine).search("静夜思", onMiss = { misses += it })
        assertEquals("静夜思，床前看月光，疑是地上霜。", hit?.text)
        assertEquals("静夜思", hit?.attribution)
        assertTrue(misses.isEmpty())
    }

    @Test
    fun `请求 URL 带编码后的检索词与参数组`() = runTest {
        val engine = FakeHttpClientEngine { response(200, hitJson) }
        WikisourceClient(engine).search("床前明月光")
        val url = engine.requests.single().url
        assertTrue(url.startsWith("https://zh.wikisource.org/w/api.php?"))
        assertTrue(url.contains("srsearch=%E5%BA%8A%E5%89%8D%E6%98%8E%E6%9C%88%E5%85%89"))
        assertTrue(url.contains("srnamespace=0") && url.contains("srlimit=3") && url.contains("format=json"))
    }

    @Test
    fun `全部条目未过命中校验时返回 null 且逐条留理由`() = runTest {
        val engine = FakeHttpClientEngine {
            response(200, """{"query":{"search":[{"title":"四季歌","snippet":"只見床前明月光冬季到來雪茫茫"}]}}""")
        }
        val misses = mutableListOf<String>()
        val hit = WikisourceClient(engine).search("床前明月光", onMiss = { misses += it })
        assertNull(hit)
        assertEquals(1, misses.size)
        assertTrue(misses[0].contains("未通过命中校验"))
    }

    @Test
    fun `非 2xx 返回 null 并记状态码理由`() = runTest {
        val engine = FakeHttpClientEngine { response(503, "") }
        val misses = mutableListOf<String>()
        assertNull(WikisourceClient(engine).search("静夜思", onMiss = { misses += it }))
        assertEquals("HTTP 503", misses.single())
    }

    @Test
    fun `响应不是可解析 JSON 时不抛异常`() = runTest {
        val engine = FakeHttpClientEngine { response(200, "<html>not json</html>") }
        val misses = mutableListOf<String>()
        assertNull(WikisourceClient(engine).search("静夜思", onMiss = { misses += it }))
        assertEquals(1, misses.size)
    }

    @Test
    fun `超长 snippet 截断到上限`() = runTest {
        val long = "床".repeat(300)
        val engine = FakeHttpClientEngine {
            response(200, """{"query":{"search":[{"title":"静夜思","snippet":"$long"}]}}""")
        }
        val hit = WikisourceClient(engine).search("静夜思")
        assertEquals(SearchHit.MAX_BODY_CHARS + 1, hit?.text?.length)
        assertTrue(hit!!.text.endsWith("…"))
    }

    @Test
    fun `snippet 为空时退回条目名作正文`() = runTest {
        val engine = FakeHttpClientEngine {
            response(200, """{"query":{"search":[{"title":"靜夜思","snippet":""}]}}""")
        }
        val hit = WikisourceClient(engine).search("静夜思")
        assertEquals("静夜思", hit?.text)
    }

    @Test
    fun `检索零结果留查无词条理由`() = runTest {
        val engine = FakeHttpClientEngine { response(200, """{"query":{"search":[]}}""") }
        val misses = mutableListOf<String>()
        assertNull(WikisourceClient(engine).search("Opus5.5", onMiss = { misses += it }))
        assertEquals(1, misses.size)
        assertTrue(misses[0].contains("查无词条"))
    }

    @Test
    fun `网络层异常归网络不可达档（含 Ktor 包裹的 cause 链）`() {
        // 裸异常直接命中
        assertTrue(
            WikisourceClient.failureOf(java.net.ConnectException("failed to connect"))
                .startsWith("网络不可达")
        )
        // Ktor CIO 把 SocketTimeoutException 裹在自己的异常里：沿 cause 链找到才算分档成功
        val wrapped = IllegalStateException(
            "Fail to connect", java.net.SocketTimeoutException("connect timed out")
        )
        assertTrue(WikisourceClient.failureOf(wrapped).startsWith("网络不可达"))
        // 真机日志实证（2026-10-19 总结链）：NIO 系的解析失败也要归网络不可达，不裸报类名
        assertTrue(
            WikisourceClient.failureOf(java.nio.channels.UnresolvedAddressException())
                .startsWith("网络不可达")
        )
        // 非网络异常不吞进这一档，照裸类名给（日志侧另有全文）
        assertEquals("IllegalArgumentException", WikisourceClient.failureOf(IllegalArgumentException("x")))
    }
}
