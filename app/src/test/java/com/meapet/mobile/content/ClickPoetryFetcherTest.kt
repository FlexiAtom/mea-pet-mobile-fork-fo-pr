package com.meapet.mobile.content

import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.test.FakeHttpClientEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
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

    // ── 检索线 fetchSearch ───────────────────────────

    private fun wikisourceResponse(body: String) =
        HttpResponse(200, body.toByteArray(), "application/json")

    private val hitRow = """{"query":{"search":[{"title":"靜夜思","snippet":"牀前看月光，疑是地上霜。"}]}}"""

    @Test
    fun `检索线 命中返回展示行且简体归一`() = runTest {
        val engine = FakeHttpClientEngine { wikisourceResponse(hitRow) }
        val outcome = ClickPoetryFetcher(engine).fetchSearch(listOf("静夜思"))
        assertTrue(outcome.ok)
        assertEquals("床前看月光，疑是地上霜。\n—— 静夜思", outcome.line)
        // 原样变体一发即中，不再打繁體改写那发
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `检索线 单词两变体全数不过校验时理由必非空`() = runTest {
        val noise = """{"query":{"search":[{"title":"四季歌","snippet":"只見床前明月光冬季到來雪茫茫"}]}}"""
        val engine = FakeHttpClientEngine { wikisourceResponse(noise) }
        val logs = mutableListOf<String>()
        val outcome = ClickPoetryFetcher(engine, logger = { logs.add(it) })
            .fetchSearch(listOf("床前明月光"))
        assertFalse(outcome.ok)
        // 随机抽的就是这唯一一词，两个变体（原样 + 繁體改写）各发一发
        assertEquals(2, engine.requests.size)
        assertNotNull(outcome.failureReason)
        assertTrue(outcome.failureReason!!.contains("未通过命中校验"))
        assertTrue(logs.last().contains("检索线取文失败"))
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
        fetcher.fetchSearch((1..6).map { "词$it" })
        val urls = engine.requests.map { it.url }
        // 只发被抽中的「词6」：原样 + 繁體（詞6）两变体封顶，其他词一请求不发
        assertEquals(2, urls.size)
        val picked = URLEncoder.encode("词6", "UTF-8")
        val traditional = URLEncoder.encode("詞6", "UTF-8")
        assertTrue(urls.all { it.contains(picked) || it.contains(traditional) })
        for (i in 1..5) {
            assertTrue(urls.none { it.contains(URLEncoder.encode("词$i", "UTF-8")) })
        }
    }

    @Test
    fun `检索线 空关键词不发任何请求`() = runTest {
        val engine = FakeHttpClientEngine { wikisourceResponse(hitRow) }
        val outcome = ClickPoetryFetcher(engine).fetchSearch(listOf("  ", ""))
        assertFalse(outcome.ok)
        assertEquals("未配置关键词", outcome.failureReason)
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `检索线 端到端预算用完即断 不再外发`() = runTest {
        var now = 0L
        val engine = FakeHttpClientEngine {
            now += 3_000
            HttpResponse(500, ByteArray(0), null)
        }
        val fetcher = ClickPoetryFetcher(
            engine = engine,
            perAttemptTimeoutMillis = 3_000,
            e2eBudgetMillis = 3_000,
            clock = { now }
        )
        // 预算 3000：第一发发出后归零，繁體变体前剩余 ≤0 → 断，不再外发
        val outcome = fetcher.fetchSearch(listOf("静夜思"))
        assertFalse(outcome.ok)
        assertEquals(1, engine.requests.size)
        assertTrue(outcome.failureReason!!.contains("端到端预算"))
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
