package com.meapet.mobile.content

import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilyClientTest {

    private fun ok(body: String) = HttpResponse(200, body.toByteArray(), "application/json")

    @Test
    fun `请求形状：POST keyless 且 body 带 query`() = runTest {
        val engine = FakeHttpClientEngine { ok("""{"results":[{"title":"李白","content":"唐代诗人。"}]}""") }
        val items = TavilyClient(engine).search("李白")
        assertEquals(1, items.size)
        val req = engine.requests.single()
        assertEquals(HttpMethod.POST, req.method)
        assertEquals(TavilyClient.URL, req.url)
        assertEquals("keyless", req.headers["X-Tavily-Access-Mode"])
        val body = (req.body as RequestBody.Json).content
        assertTrue(body.contains("\"query\":\"李白\""))
        assertTrue(body.contains("\"max_results\":${TavilyClient.MAX_ITEMS}"))
        assertTrue(body.contains("\"search_depth\":\"basic\""))
    }

    @Test
    fun `素材行：title 拼 content 且繁→简归一、空白压平`() = runTest {
        val engine = FakeHttpClientEngine {
            ok("""{"results":[{"title":"靜夜思","content":"牀前看月光，\n疑是地上霜。"}]}""")
        }
        val items = TavilyClient(engine).search("静夜思")
        assertEquals(listOf("静夜思｜床前看月光， 疑是地上霜。"), items)
    }

    @Test
    fun `无标题时只给正文，标题正文皆空的行被丢掉`() = runTest {
        val engine = FakeHttpClientEngine {
            ok("""{"results":[{"content":"只有正文。"},{"title":"  ","content":"\t"}]}""")
        }
        assertEquals(listOf("只有正文。"), TavilyClient(engine).search("x"))
    }

    @Test
    fun `单条按 240 截断加省略号，至多 3 条`() = runTest {
        val long = "长".repeat(TavilyClient.ITEM_CHAR_LIMIT + 10)
        val rows = (1..5).joinToString(",") { """{"title":"条$it","content":"${if (it == 1) long else "内容$it"}"}""" }
        val engine = FakeHttpClientEngine { ok("""{"results":[$rows]}""") }
        val items = TavilyClient(engine).search("测试")
        assertEquals(TavilyClient.MAX_ITEMS, items.size)
        assertEquals(TavilyClient.ITEM_CHAR_LIMIT + 1, items[0].length)
        assertTrue(items[0].startsWith("条1｜"))
        assertTrue(items[0].endsWith("…"))
    }

    @Test
    fun `非 2xx 报档名 HTTP N`() = runTest {
        val misses = mutableListOf<String>()
        val engine = FakeHttpClientEngine { HttpResponse(403, ByteArray(0), null) }
        assertTrue(TavilyClient(engine).search("静夜思", onMiss = { misses.add(it) }).isEmpty())
        assertEquals(listOf("HTTP 403"), misses)
    }

    @Test
    fun `响应里没有 results 时如实报`() = runTest {
        val misses = mutableListOf<String>()
        val engine = FakeHttpClientEngine { ok("""{"results":[],"answer":"hi"}""") }
        val items = TavilyClient(engine).search("静夜思", onMiss = { misses.add(it) })
        assertTrue(items.isEmpty())
        // results 为空数组：解析得到行但无可用文本
        assertEquals(listOf("检索回来但没有可用文本"), misses)

        val misses2 = mutableListOf<String>()
        val engine2 = FakeHttpClientEngine { ok("""{"unexpected":true}""") }
        TavilyClient(engine2).search("静夜思", onMiss = { misses2.add(it) })
        assertEquals(listOf("响应里没有 results"), misses2)
    }

    @Test
    fun `空查询不发请求`() = runTest {
        val engine = FakeHttpClientEngine { ok("""{"results":[]}""") }
        TavilyClient(engine).search("   ")
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `失败分档：连接被拒与 DNS 解析不出归网络不可达`() {
        assertTrue(
            TavilyClient.failureOf(java.net.ConnectException("failed to connect")).startsWith("网络不可达")
        )
        // Ktor CIO 把 DNS 失败裹进自己异常：沿 cause 链也要认（真机日志实证过的漏网成员）
        val wrapped = RuntimeException("request failed", java.nio.channels.UnresolvedAddressException())
        assertTrue(TavilyClient.failureOf(wrapped).startsWith("网络不可达"))
        assertEquals("网络不可达（UnresolvedAddressException）", TavilyClient.failureOf(java.nio.channels.UnresolvedAddressException()))
        assertEquals("IllegalArgumentException", TavilyClient.failureOf(IllegalArgumentException("x")))
    }

    @Test
    fun `visible：null 与纯空白都归空串`() {
        assertEquals("", TavilyClient.visible(null))
        assertEquals("", TavilyClient.visible("  \t\n "))
        assertEquals("静夜思 李白", TavilyClient.visible(" 靜夜思 李白 "))
    }
}
