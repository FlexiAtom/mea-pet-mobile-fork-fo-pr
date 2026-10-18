package com.meapet.mobile.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 关键词表解析：设置页存原文、生效侧读解析结果，两边共用这一份口径。 */
class SearchKeywordsParseTest {

    @Test
    fun `多种分隔符混用 去空去重`() {
        assertEquals(
            listOf("静夜思", "观沧海", "李白"),
            parseClickPoetryKeywords("静夜思, 观沧海、李白，静夜思\n , ")
        )
    }

    @Test
    fun `全空白解析为空表`() {
        assertEquals(emptyList<String>(), parseClickPoetryKeywords(" , 、 \n "))
    }

    @Test
    fun `默认预置表可解析且非空`() {
        val defaults = parseClickPoetryKeywords(SettingsKeys.Defaults.CLICK_POETRY_SEARCH_KEYWORDS)
        assertEquals(6, defaults.size)
        assertEquals("静夜思", defaults.first())
    }
}
