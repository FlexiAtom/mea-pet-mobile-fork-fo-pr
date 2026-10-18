package com.meapet.mobile.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命中校验单测。测试向量取自 2026-10-18 开发机对 Wikisource 的实发取证
 * （提案 click-feedback-keyword-search-line §2 反证表）——两条被证伪的桌面判语
 * 各自留下一个必须永久拦住的反例形状。
 */
class HitVerifierTest {

    @Test
    fun `简体查询对繁體条目名 折叠后应命中`() {
        // 旧闸（恒等归一）在这一格误拦过真古籍《靜夜思》
        assertTrue(HitVerifier.isHit("静夜思", "靜夜思"))
        assertTrue(HitVerifier.isHit("观沧海", "觀滄海"))
    }

    @Test
    fun `名句被别篇原样引用 不应命中`() {
        // 实测反例：srsearch=床前明月光 首条《四季歌》，歌词现文含「只見床前明月光」
        // 旧闸在 snippet 上做 ≥3 字连续片段判定，三窗口全中被放行；新闸只看 title
        val snippet = "只見床前明月光冬季到來雪茫茫"
        // 旧闸就是在这里连出 3 字片段才放行的：折叠后的 snippet 确实原样含查询
        assertTrue(HitVerifier.compact(snippet).contains(HitVerifier.compact("床前明月光")))
        // 新闸只看 title，snippet 不参与判定
        assertFalse(HitVerifier.isHit("床前明月光", "四季歌"))
    }

    @Test
    fun `判决书噪声 即使标题含查询也不应命中`() {
        assertFalse(
            HitVerifier.isHit("静夜思", "静夜思奶茶店侵害作品信息网络传播权纠纷一审民事判决书")
        )
    }

    @Test
    fun `消歧页标题形状不应命中`() {
        assertFalse(HitVerifier.isHit("客中作", "客中作 (消歧页)"))
    }

    @Test
    fun `作者查询命中含该作者的条目`() {
        assertTrue(HitVerifier.isHit("李白", "贈李白"))
    }

    @Test
    fun `空查询与空标题都不命中`() {
        assertFalse(HitVerifier.isHit("", "靜夜思"))
        assertFalse(HitVerifier.isHit("   ", "靜夜思"))
        assertFalse(HitVerifier.isHit("静夜思", ""))
    }

    @Test
    fun `compact 去过标点空白并折简体`() {
        assertEquals("静夜思李白", HitVerifier.compact("《靜夜思》—— 李白"))
        assertEquals("abc123", HitVerifier.compact(" a.b!c 1-2-3 "))
    }

    @Test
    fun `查询变体 原样在前繁體改写在后 相同则去重`() {
        assertEquals(listOf("静夜思", "靜夜思"), HitVerifier.queryVariants("静夜思"))
        // 繁简无差异的单字只剩一条
        assertEquals(listOf("李"), HitVerifier.queryVariants(" 李 "))
        assertEquals(emptyList<String>(), HitVerifier.queryVariants("   "))
    }

    @Test
    fun `拉丁数字关键词：标点空格剥掉且大小写不敏感`() {
        // 保留集含 ASCII 字母数字，剥掉 . 与空格，字母统一小写
        assertEquals("opus55", HitVerifier.compact("Opus 5.5"))
        assertTrue(HitVerifier.isHit("Opus5.5", "Opus 5.5"))
        assertTrue(HitVerifier.isHit("opus 5.5", "Opus5.5"))
        // 数字不同即不命中：折叠不做模糊匹配
        assertFalse(HitVerifier.isHit("Opus5.5", "Opus 5.6"))
    }
}
