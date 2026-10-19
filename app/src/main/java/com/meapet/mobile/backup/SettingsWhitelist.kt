package com.meapet.mobile.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * 设置类导出的**字段级白名单**（G4 封版，人工裁决 2026-10-20 02:45「开发者选项不计入导出」）。
 *
 * 只列纯用户配置 20 键；以下归属**永不入包**（清单=敏感边界的实际载体，改动须人再点名）：
 * - 敏感：`api_key`、两条取诗隐私确认态、`privacy_version_shown`、旁路键 `umeng_privacy_agreed`
 * - 设备绑定/运行态：`developer_mode_enabled`、`first_launch`、`exchange_count`（计数器）、`wallpaper_path`（壁纸类自行重定位）
 * - 开发者选项：`click_poetry_probability`、`click_poetry_search_probability`
 */
object SettingsWhitelist {

    private val STRING_KEYS = listOf(
        "api_url", "model", "system_prompt", "theme_mode", "color_preset",
        "click_poetry_search_keywords",
    ).map(::stringPreferencesKey)

    private val INT_KEYS = listOf(
        "max_tokens", "summary_interval",
    ).map(::intPreferencesKey)

    private val DOUBLE_KEYS = listOf(
        "temperature", "chat_bubble_alpha", "wallpaper_blur", "tts_length_scale",
    ).map(::doublePreferencesKey)

    private val BOOL_KEYS = listOf(
        "enable_memory", "enable_auto_summary", "enable_dynamic_color",
        "enable_auto_update_check", "enable_click_poetry", "enable_click_poetry_search",
        "tts_main_enabled", "tts_overlay_enabled",
    ).map(::booleanPreferencesKey)

    private sealed interface Kind {
        data object Str : Kind
        data object Int : Kind
        data object Dbl : Kind
        data object Bool : Kind
    }

    private val TYPED: List<Pair<Preferences.Key<*>, Kind>> =
        STRING_KEYS.map { it to Kind.Str } + INT_KEYS.map { it to Kind.Int } +
            DOUBLE_KEYS.map { it to Kind.Dbl } + BOOL_KEYS.map { it to Kind.Bool }

    private val ALL: List<Preferences.Key<*>> = TYPED.map { it.first }

    /** 防漂移哨兵：封版清单=20 键。键集变动必须同步改这个数（即再过一次 G4）。 */
    const val KEY_COUNT = 20

    private val json = Json { prettyPrint = false }

    fun keys(): List<Preferences.Key<*>> = ALL

    /**
     * 快照 → JsonObject（缺键不落，导入侧按各自默认处理）。
     * JsonPrimitive 的公开入口是**顶层工厂**（String/Number/Boolean 等重载，
     * javap 1.8.0 实证），类的直接构造是 protected——所以必须按 Kind 显式分派，
     * 不能 `JsonPrimitive(anyValue)`。
     */
    fun encodeFrom(prefs: Preferences): String {
        val properties = mutableMapOf<String, JsonPrimitive>()
        for ((key, kind) in TYPED) {
            val value = prefs[key] ?: continue
            properties[key.name] = when (kind) {
                Kind.Str -> JsonPrimitive(value as String)
                Kind.Int -> JsonPrimitive(value as Int)
                Kind.Dbl -> JsonPrimitive(value as Double)
                Kind.Bool -> JsonPrimitive(value as Boolean)
            }
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(properties))
    }

    /** JsonObject 文本 → Preferences 补丁（只认白名单；类型不符即整包拒绝，导入是受信自产件不做宽容）。 */
    @Suppress("UNCHECKED_CAST")
    fun decodeTo(text: String): Preferences {
        val obj = json.decodeFromString(JsonObject.serializer(), text)
        val out = emptyPreferences().toMutablePreferences()
        for ((name, element) in obj) {
            val typed = TYPED.firstOrNull { it.first.name == name }
                ?: throw ManifestRejectException("设置包含非白名单键: $name")
            val primitive = element.jsonPrimitive
            val key = typed.first as Preferences.Key<Any>
            val value: Any = when (typed.second) {
                Kind.Str -> primitive.content
                Kind.Int -> primitive.int
                Kind.Dbl -> primitive.double
                Kind.Bool -> primitive.boolean
            }
            out[key] = value
        }
        return out.toPreferences()
    }

    /**
     * 写回用：把一对 键→值 落到 MutablePreferences；value 为 null 表示"这个键本来不存在"，
     * 回滚时就该把它删掉（不是写个空值进去）。
     *
     * `MutablePreferences.set` 是 `<T> set(Key<T>, T)`，通配符键调不动，只能按封版清单
     * 的类型做一次 unchecked cast——类型不符的值在 decodeTo 里已经被 `primitive.int`
     * 一类的读取器挡掉了，这里不再重复校验。
     */
    @Suppress("UNCHECKED_CAST")
    fun writeOne(target: MutablePreferences, key: Preferences.Key<*>, value: Any?) {
        if (value == null) target -= key else target[key as Preferences.Key<Any>] = value
    }

    /** 写回用：整份补丁逐键落。 */
    fun writeInto(target: MutablePreferences, source: Preferences) {
        source.asMap().forEach { (key, value) -> writeOne(target, key, value) }
    }
}
