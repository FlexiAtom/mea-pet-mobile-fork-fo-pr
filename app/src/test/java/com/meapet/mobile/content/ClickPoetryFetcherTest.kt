package com.meapet.mobile.content

import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClickPoetryFetcherTest {

    private fun okPoem() = HttpResponse(
        200,
        """{"hitokoto":"明月出天山，苍茫云海间。","from":"关山月","from_who":"李白"}""".toByteArray(),
        "application/json"
    )

    private fun emptyPoem() = HttpResponse(200, """{"hitokoto":""}""".toByteArray(), "application/json")

    @Test
    fun `端到端预算耗尽时留一行日志而非静默截断`() = runTest {
        var now = 0L
        val engine = FakeHttpClientEngine { emptyPoem().also { now += 3_000 } }
        val logs = mutableListOf<String>()
        val fetcher = ClickPoetryFetcher(
            engine = engine,
            perAttemptTimeoutMillis = 3_000,
            e2eBudgetMillis = 8_000,
            maxAttempts = 5,
            clock = { now },
            logger = { logs.add(it) }
        )
        // 预算 8000：第 1/2/3 发各扣 3000（3000→6000→9000 后剩余为负），第 4 发前放弃
        assertNull(fetcher.fetch())
        assertEquals(3, engine.requests.size)
        assertTrue(logs.last().contains("端到端预算"))
    }

    @Test
    fun `第一发失败第二发命中`() = runTest {
        var calls = 0
        val engine = FakeHttpClientEngine {
            calls++
            if (calls == 1) HttpResponse(500, ByteArray(0), null) else okPoem()
        }
        val line = ClickPoetryFetcher(engine).fetch()
        assertEquals("明月出天山，苍茫云海间。\n—— 李白《关山月》", line)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `单发超每档上限即让位下一发（虚拟时间）`() = runTest {
        var calls = 0
        val engine = FakeHttpClientEngine {
            calls++
            if (calls == 1) {
                delay(10_000) // 慢滴/挂死形状：靠每档超时封顶
                emptyPoem()
            } else {
                okPoem()
            }
        }
        val fetcher = ClickPoetryFetcher(engine, perAttemptTimeoutMillis = 1_000, e2eBudgetMillis = 30_000)
        assertNotNull(fetcher.fetch())
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `全失败时恰好打满 maxAttempts 后返回 null`() = runTest {
        val engine = FakeHttpClientEngine { HttpResponse(503, ByteArray(0), null) }
        val logs = mutableListOf<String>()
        val fetcher = ClickPoetryFetcher(engine, maxAttempts = 2, logger = { logs.add(it) })
        assertNull(fetcher.fetch())
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `概率门控：roll 严格小于概率才命中`() {
        assertFalse(ClickPoetryFetcher.rollHits(0, 0)) // 0 = 从不
        assertTrue(ClickPoetryFetcher.rollHits(100, 99)) // 100 = 每次
        assertTrue(ClickPoetryFetcher.rollHits(50, 49))
        assertFalse(ClickPoetryFetcher.rollHits(50, 50))
    }

    // ── 检索线 fetchCrawler（Tavily）─────────────────

    private fun tavilyResponse(body: String) = HttpResponse(200, body.toByteArray(), "application/json")

    @Test
    fun `检索线 命中返回素材编号块且简体归一`() = runTest {
        val engine = FakeHttpClientEngine {
            tavilyResponse(
                """{"results":[{"title":"靜夜思","content":"牀前看月光，疑是地上霜。"},""" +
                    """{"title":"李白","content":"唐代诗人，字太白。"}]}"""
            )
        }
        val outcome = ClickPoetryFetcher(engine).fetchCrawler(listOf("静夜思"))
        assertTrue(outcome.ok)
        assertEquals(
            "1. 静夜思｜床前看月光，疑是地上霜。\n2. 李白｜唐代诗人，字太白。",
            outcome.line
        )
        // 素材块只交总结那发，不直接进气泡；一发即回，不再打变体
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `检索线 随机抽一个词 其余词不发`() = runTest {
        val engine = FakeHttpClientEngine { HttpResponse(500, ByteArray(0), null) }
        val fetcher = ClickPoetryFetcher(
            engine = engine,
            e2eBudgetMillis = 100_000,
            perAttemptTimeoutMillis = 30_000,
            wordPicker = { words -> words.last() }
        )
        fetcher.fetchCrawler((1..6).map { "词$it" })
        // 爬虫线没有变体重试：只发被抽中的「词6」一发
        val req = engine.requests.single()
        val body = (req.body as RequestBody.Json).content
        assertTrue(body.contains("\"query\":\"词6\""))
        for (i in 1..5) {
            assertFalse(body.contains("词$i"))
        }
    }

    @Test
    fun `检索线 空关键词不发任何请求`() = runTest {
        val engine = FakeHttpClientEngine { tavilyResponse("""{"results":[]}""") }
        val outcome = ClickPoetryFetcher(engine).fetchCrawler(listOf("  ", ""))
        assertFalse(outcome.ok)
        assertEquals("未配置关键词", outcome.failureReason)
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `检索线 非 2xx 档名进理由`() = runTest {
        val logs = mutableListOf<String>()
        val engine = FakeHttpClientEngine { HttpResponse(503, ByteArray(0), null) }
        val outcome = ClickPoetryFetcher(engine, logger = { logs.add(it) }).fetchCrawler(listOf("静夜思"))
        assertFalse(outcome.ok)
        assertEquals("HTTP 503", outcome.failureReason)
        assertTrue(logs.last().contains("检索线取文失败"))
    }

    @Test
    fun `检索线 单发超时档名前置（虚拟时间）`() = runTest {
        val engine = FakeHttpClientEngine {
            delay(10_000) // 慢滴/挂死形状：靠每档超时封顶
            tavilyResponse("""{"results":[{"content":"太晚了"}]}""")
        }
        val outcome = ClickPoetryFetcher(engine, perAttemptTimeoutMillis = 1_000).fetchCrawler(listOf("静夜思"))
        assertFalse(outcome.ok)
        assertTrue(outcome.failureReason!!.startsWith("超时：「静夜思」1000ms 内未完成"))
    }

    @Test
    fun `检索线 端到端预算用完即断 不再外发`() = runTest {
        val engine = FakeHttpClientEngine { HttpResponse(500, ByteArray(0), null) }
        val fetcher = ClickPoetryFetcher(
            engine = engine,
            e2eBudgetMillis = 0,
            clock = { 0L }
        )
        val outcome = fetcher.fetchCrawler(listOf("静夜思"))
        assertFalse(outcome.ok)
        assertTrue(outcome.failureReason!!.contains("端到端预算"))
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `文本处理：署名格式与超长截断`() {
        val longText = "长".repeat(Poem.MAX_TEXT_CHARS + 10)
        val display = Poem(longText, "关山月", "李白").toDisplayLine()
        assertTrue(display.startsWith("长".repeat(Poem.MAX_TEXT_CHARS) + "…"))
        assertTrue(display.endsWith("—— 李白《关山月》"))

        assertEquals("寂寂人语绝。", Poem("寂寂人语绝。", null, null).toDisplayLine())
        assertEquals("大漠沙如雪。\n—— 李贺", Poem("大漠沙如雪。", null, "李贺").toDisplayLine())
        assertEquals("折梅逢驿使。\n—— 《赠范晔诗》", Poem("折梅逢驿使。", "赠范晔诗", null).toDisplayLine())
    }
}
