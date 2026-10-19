package com.meapet.mobile.backup

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsWhitelistTest {

    // MutablePreferences 的写入口是 operator set（javap 实证：无 put）
    private fun snapshot(): Preferences = mutablePreferencesOf().apply {
        this[stringPreferencesKey("api_url")] = "https://example.test/v1"
        this[stringPreferencesKey("model")] = "某模型"
        this[stringPreferencesKey("system_prompt")] = "包含\"引号\"与换行\n的长文本"
        this[stringPreferencesKey("theme_mode")] = "dark"
        this[stringPreferencesKey("color_preset")] = "ocean"
        this[stringPreferencesKey("click_poetry_search_keywords")] = "春风,明月"
        this[intPreferencesKey("max_tokens")] = 4096
        this[intPreferencesKey("summary_interval")] = 10
        this[doublePreferencesKey("temperature")] = 0.7
        this[doublePreferencesKey("chat_bubble_alpha")] = 0.85
        this[doublePreferencesKey("wallpaper_blur")] = 0.3
        this[doublePreferencesKey("tts_length_scale")] = 1.25
        this[booleanPreferencesKey("enable_memory")] = true
        this[booleanPreferencesKey("enable_auto_summary")] = false
        this[booleanPreferencesKey("enable_dynamic_color")] = true
        this[booleanPreferencesKey("enable_auto_update_check")] = false
        this[booleanPreferencesKey("enable_click_poetry")] = true
        this[booleanPreferencesKey("enable_click_poetry_search")] = false
        this[booleanPreferencesKey("tts_main_enabled")] = true
        this[booleanPreferencesKey("tts_overlay_enabled")] = false
        // 敏感/排除键——快照里存在也必须被挡在包外
        this[stringPreferencesKey("api_key")] = "sk-SECRET-勿入包"
        this[stringPreferencesKey("wallpaper_path")] = "/data/user/0/x/files/wallpaper/wallpaper_1.jpg"
        this[stringPreferencesKey("privacy_version_shown")] = "3"
        this[booleanPreferencesKey("developer_mode_enabled")] = true
        this[booleanPreferencesKey("click_poetry_privacy_acked")] = true
        this[booleanPreferencesKey("first_launch")] = false
        this[intPreferencesKey("exchange_count")] = 7
        this[intPreferencesKey("click_poetry_probability")] = 55
        this[intPreferencesKey("click_poetry_search_probability")] = 44
        this[stringPreferencesKey("umeng_privacy_agreed")] = "true"
    }

    @Test
    fun whitelist_isExactly20Keys() {
        assertEquals(SettingsWhitelist.KEY_COUNT, SettingsWhitelist.keys().size)
        assertEquals(
            SettingsWhitelist.KEY_COUNT.toLong(),
            SettingsWhitelist.keys().map { it.name }.distinct().size.toLong()
        )
    }

    @Test
    fun export_excludesSensitiveAndDevKeys() {
        val text = SettingsWhitelist.encodeFrom(snapshot())
        for (forbidden in listOf(
            "api_key", "wallpaper_path", "privacy_version_shown", "developer_mode_enabled",
            "click_poetry_privacy_acked", "first_launch", "exchange_count",
            "click_poetry_probability", "click_poetry_search_probability", "umeng_privacy_agreed",
        )) {
            assertFalse("排除键泄漏进包: $forbidden", text.contains(forbidden))
        }
        assertTrue(text.contains("https://example.test/v1"))
        assertTrue(text.contains("春风,明月"))
    }

    @Test
    fun roundtrip_preservesAllWhitelistedValues() {
        val text = SettingsWhitelist.encodeFrom(snapshot())
        val back = SettingsWhitelist.decodeTo(text)
        assertEquals("https://example.test/v1", back[stringPreferencesKey("api_url")])
        assertEquals("包含\"引号\"与换行\n的长文本", back[stringPreferencesKey("system_prompt")])
        assertEquals(4096, back[intPreferencesKey("max_tokens")])
        assertEquals(0.7, back[doublePreferencesKey("temperature")]!!, 0.0)
        assertEquals(0.85, back[doublePreferencesKey("chat_bubble_alpha")]!!, 0.0)
        assertEquals(true, back[booleanPreferencesKey("enable_click_poetry")])
        assertTrue("白名单键应全部回得", SettingsWhitelist.keys().all { back.contains(it) })
    }

    @Test
    fun decode_nonWhitelistedKey_rejects() {
        val text = """{"api_key":"sk-evil","model":"m"}"""
        try {
            SettingsWhitelist.decodeTo(text)
            fail("非白名单键竟然被放行")
        } catch (expected: ManifestRejectException) {
            assertTrue(expected.message!!.contains("api_key"))
        }
    }
}
