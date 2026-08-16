package com.meapet.mobile.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 应用设置管理器。
 *
 * 基于 DataStore 的键值持久化，所有操作通过 Flow 暴露，
 * UI 层可响应式订阅变更。
 *
 * 同步 getter 读取的是构造时在 IO 协程里预热、并持续跟随 DataStore 变更的
 * 内存快照，正常路径不会产生磁盘 IO；仅在进程启动后的极短窗口内快照尚未
 * 就绪时，才退化为一次 runBlocking 读盘兜底。
 *
 * 该模块**不依赖**任何其他模块，可独立测试。
 *
 * @param context Application Context
 */
class SettingsManager(context: Context) {

    private val dataStore = context.appDataStore

    /** 设置项内存快照（跟随 DataStore 变更；null = 尚未完成首次加载）。 */
    @Volatile
    private var cachedPrefs: Preferences? = null

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 构造即预热：首次加载后持续订阅，保证快照与磁盘一致
        cacheScope.launch {
            dataStore.data.collect { prefs ->
                cachedPrefs = prefs
            }
        }
    }

    companion object {
        private const val TAG = "SettingsManager"
    }

    // ── Keys ──────────────────────────────────────────

    private val KEY_API_KEY = stringPreferencesKey(SettingsKeys.API_KEY)
    private val KEY_API_URL = stringPreferencesKey(SettingsKeys.API_URL)
    private val KEY_MODEL = stringPreferencesKey(SettingsKeys.MODEL)
    private val KEY_TEMPERATURE = doublePreferencesKey(SettingsKeys.TEMPERATURE)
    private val KEY_MAX_TOKENS = intPreferencesKey(SettingsKeys.MAX_TOKENS)
    private val KEY_ENABLE_MEMORY = booleanPreferencesKey(SettingsKeys.ENABLE_MEMORY)
    private val KEY_ENABLE_AUTO_SUMMARY = booleanPreferencesKey(SettingsKeys.ENABLE_AUTO_SUMMARY)
    private val KEY_SUMMARY_INTERVAL = intPreferencesKey(SettingsKeys.SUMMARY_INTERVAL)
    private val KEY_EXCHANGE_COUNT = intPreferencesKey(SettingsKeys.EXCHANGE_COUNT)
    private val KEY_SYSTEM_PROMPT = stringPreferencesKey(SettingsKeys.SYSTEM_PROMPT)
    private val KEY_THEME_MODE = stringPreferencesKey(SettingsKeys.THEME_MODE)
    private val KEY_ENABLE_DYNAMIC_COLOR = booleanPreferencesKey(SettingsKeys.ENABLE_DYNAMIC_COLOR)
    private val KEY_COLOR_PRESET = stringPreferencesKey(SettingsKeys.COLOR_PRESET)
    private val KEY_FIRST_LAUNCH = booleanPreferencesKey(SettingsKeys.FIRST_LAUNCH)

    // ── Flows (响应式订阅) ────────────────────────────

    /** API Key 流。 */
    val apiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_API_KEY] ?: ""
    }

    /** API URL 流。 */
    val apiUrlFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_API_URL] ?: SettingsKeys.Defaults.API_URL
    }

    /** 模型流。 */
    val modelFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_MODEL] ?: SettingsKeys.Defaults.MODEL
    }

    /** Temperature 流。 */
    val temperatureFlow: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_TEMPERATURE] ?: SettingsKeys.Defaults.TEMPERATURE
    }

    /** Max tokens 流。 */
    val maxTokensFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_MAX_TOKENS] ?: SettingsKeys.Defaults.MAX_TOKENS
    }

    /** 记忆开关流。 */
    val enableMemoryFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_MEMORY] ?: SettingsKeys.Defaults.ENABLE_MEMORY
    }

    /** 自动摘要开关流。 */
    val enableAutoSummaryFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_AUTO_SUMMARY] ?: SettingsKeys.Defaults.ENABLE_AUTO_SUMMARY
    }

    /** 摘要轮次流（每隔多少轮对话触发一次短期记忆摘要）。 */
    val summaryIntervalFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SUMMARY_INTERVAL] ?: SettingsKeys.Defaults.SUMMARY_INTERVAL
    }

    /** System prompt 流。 */
    val systemPromptFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SYSTEM_PROMPT] ?: SettingsKeys.Defaults.SYSTEM_PROMPT
    }

    /** 主题模式流。 */
    val themeModeFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: SettingsKeys.Defaults.THEME_MODE
    }

    /** 动态颜色开关流。 */
    val enableDynamicColorFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_DYNAMIC_COLOR] ?: SettingsKeys.Defaults.ENABLE_DYNAMIC_COLOR
    }

    /** 颜色预设流。 */
    val colorPresetFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_COLOR_PRESET] ?: SettingsKeys.Defaults.COLOR_PRESET
    }

    /** 首次启动标记流。 */
    val isFirstLaunchFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FIRST_LAUNCH] ?: true
    }

    // ── 同步 getter（非 Flow 场景使用，如 Client 构造）──
    // 读取内存快照，正常路径无磁盘 IO；快照未就绪时短暂 runBlocking 读一次兜底

    private fun currentPrefs(): Preferences =
        cachedPrefs ?: runBlocking { dataStore.data.first() }.also { cachedPrefs = it }

    fun getApiKey(): String = currentPrefs()[KEY_API_KEY] ?: ""
    fun getApiUrl(): String = currentPrefs()[KEY_API_URL] ?: SettingsKeys.Defaults.API_URL
    fun getModel(): String = currentPrefs()[KEY_MODEL] ?: SettingsKeys.Defaults.MODEL
    fun getTemperature(): Double = currentPrefs()[KEY_TEMPERATURE] ?: SettingsKeys.Defaults.TEMPERATURE
    fun getMaxTokens(): Int = currentPrefs()[KEY_MAX_TOKENS] ?: SettingsKeys.Defaults.MAX_TOKENS
    fun getSystemPrompt(): String = currentPrefs()[KEY_SYSTEM_PROMPT] ?: SettingsKeys.Defaults.SYSTEM_PROMPT
    fun isMemoryEnabled(): Boolean = currentPrefs()[KEY_ENABLE_MEMORY] ?: SettingsKeys.Defaults.ENABLE_MEMORY
    fun isAutoSummaryEnabled(): Boolean = currentPrefs()[KEY_ENABLE_AUTO_SUMMARY] ?: SettingsKeys.Defaults.ENABLE_AUTO_SUMMARY
    fun getSummaryInterval(): Int = currentPrefs()[KEY_SUMMARY_INTERVAL] ?: SettingsKeys.Defaults.SUMMARY_INTERVAL

    /** 主题模式（"system" | "light" | "dark"）。 */
    fun getThemeMode(): String = currentPrefs()[KEY_THEME_MODE] ?: SettingsKeys.Defaults.THEME_MODE

    /** 是否启用动态取色（Material You）。 */
    fun isDynamicColorEnabled(): Boolean = currentPrefs()[KEY_ENABLE_DYNAMIC_COLOR] ?: SettingsKeys.Defaults.ENABLE_DYNAMIC_COLOR

    /** 颜色预设 ID（"default" | "ocean" | ...）。 */
    fun getColorPreset(): String = currentPrefs()[KEY_COLOR_PRESET] ?: SettingsKeys.Defaults.COLOR_PRESET

    /** 距上次摘要已进行的对话轮数（跨进程存活，见 [SettingsKeys.EXCHANGE_COUNT]）。 */
    fun getExchangeCount(): Int = currentPrefs()[KEY_EXCHANGE_COUNT] ?: 0

    // ── 写入方法 ──────────────────────────────────────
    // edit 返回写入后的最新快照，随手更新缓存，保证同步 getter 读己之写

    suspend fun setApiKey(key: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_API_KEY] = key }
        Log.d(TAG, "API Key updated")
    }

    suspend fun setApiUrl(url: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_API_URL] = url }
        Log.d(TAG, "API URL updated: $url")
    }

    suspend fun setModel(model: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_MODEL] = model }
    }

    suspend fun setTemperature(temp: Double) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_TEMPERATURE] = temp }
    }

    suspend fun setMaxTokens(tokens: Int) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_MAX_TOKENS] = tokens }
    }

    suspend fun setEnableMemory(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_MEMORY] = enabled }
    }

    suspend fun setEnableAutoSummary(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_AUTO_SUMMARY] = enabled }
    }

    suspend fun setSummaryInterval(interval: Int) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_SUMMARY_INTERVAL] = interval }
    }

    suspend fun setExchangeCount(count: Int) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_EXCHANGE_COUNT] = count }
    }

    suspend fun setSystemPrompt(prompt: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_SYSTEM_PROMPT] = prompt }
    }

    suspend fun setThemeMode(mode: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode }
    }

    suspend fun setEnableDynamicColor(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setColorPreset(preset: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_COLOR_PRESET] = preset }
    }

    suspend fun markFirstLaunchDone() {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_FIRST_LAUNCH] = false }
    }

    /** 清除所有设置（恢复出厂）。 */
    suspend fun clearAll() {
        cachedPrefs = dataStore.edit { it.clear() }
        Log.i(TAG, "All settings cleared")
    }
}
