package com.meapet.mobile.content

import com.meapet.mobile.client.HttpResponse
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
