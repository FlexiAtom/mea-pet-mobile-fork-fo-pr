package com.meapet.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SystemBubblePolicy] 气泡寿命调度测试。
 *
 * 覆盖规则：
 * - 位置 1-3（最新）不扣寿命；
 * - 位置 4 及以后被挤旧时扣 2 秒，**累计最多扣 [SystemBubblePolicy.MAX_REDUCE_COUNT]
 *   （2 次，共 4 秒）**，寿命封底 [SystemBubblePolicy.MIN_LIFE_MS]（3 秒）；
 * - 已扣满上限后即使持续停留在旧位也不再下降。
 */
@Suppress("NonAsciiCharacters")
class SystemBubblePolicyTest {

    @Test
    fun `位置 1 到 3 不扣寿命且不增加计数`() {
        for (position in 1..3) {
            val (life, count) = SystemBubblePolicy.computeNextLife(
                SystemBubblePolicy.BASE_LIFE_MS, reduceCount = 0, position = position
            )
            assertEquals("position=$position 不应扣寿命", SystemBubblePolicy.BASE_LIFE_MS, life)
            assertEquals("position=$position 不应增加计数", 0, count)
        }
    }

    @Test
    fun `位置 4 首次扣 2 秒`() {
        val (life, count) = SystemBubblePolicy.computeNextLife(
            SystemBubblePolicy.BASE_LIFE_MS, reduceCount = 0, position = 4
        )
        assertEquals(5_000L, life)
        assertEquals(1, count)
    }

    @Test
    fun `位置 6 首次同样扣 2 秒`() {
        val (life, count) = SystemBubblePolicy.computeNextLife(
            SystemBubblePolicy.BASE_LIFE_MS, reduceCount = 0, position = 6
        )
        assertEquals(5_000L, life)
        assertEquals(1, count)
    }

    @Test
    fun `位置 6 第二次扣 2 秒`() {
        val (life, count) = SystemBubblePolicy.computeNextLife(
            currentLifeMs = 5_000L, reduceCount = 1, position = 6
        )
        assertEquals(3_000L, life)
        assertEquals(2, count)
    }

    @Test
    fun `已达扣减上限后不再扣`() {
        val (life, count) = SystemBubblePolicy.computeNextLife(
            currentLifeMs = 3_000L, reduceCount = 2, position = 6
        )
        assertEquals(3_000L, life)
        assertEquals(2, count)
    }

    @Test
    fun `位置 4 已扣 1 次会继续扣到上限`() {
        val (life, count) = SystemBubblePolicy.computeNextLife(
            currentLifeMs = 5_000L, reduceCount = 1, position = 4
        )
        assertEquals(3_000L, life)
        assertEquals(2, count)
    }

    @Test
    fun `位置退回 1 到 3 即使有配额也不扣`() {
        val (life, count) = SystemBubblePolicy.computeNextLife(
            currentLifeMs = 5_000L, reduceCount = 1, position = 2
        )
        assertEquals(5_000L, life)
        assertEquals(1, count)
    }

    @Test
    fun `寿命低于下限时钳制到下限`() {
        // 防御：即使传入异常小值也不低于 MIN_LIFE_MS
        val (life, count) = SystemBubblePolicy.computeNextLife(
            currentLifeMs = 1_000L, reduceCount = 1, position = 6
        )
        assertEquals(SystemBubblePolicy.MIN_LIFE_MS, life)
        assertEquals(2, count)
    }

    @Test
    fun `连续挤压 6 位封底在 3 秒不再下降`() {
        var life = SystemBubblePolicy.BASE_LIFE_MS
        var count = 0
        val steps = mutableListOf<Long>()
        repeat(5) {
            val (next, nextCount) = SystemBubblePolicy.computeNextLife(life, count, position = 6)
            steps += next
            life = next
            count = nextCount
        }
        assertEquals(listOf(5_000L, 3_000L, 3_000L, 3_000L, 3_000L), steps)
    }
}
