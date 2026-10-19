package com.meapet.mobile.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
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
    private val KEY_TTS_MAIN_ENABLED = booleanPreferencesKey(SettingsKeys.TTS_MAIN_ENABLED)
    private val KEY_TTS_OVERLAY_ENABLED = booleanPreferencesKey(SettingsKeys.TTS_OVERLAY_ENABLED)
    private val KEY_TTS_LENGTH_SCALE = doublePreferencesKey(SettingsKeys.TTS_LENGTH_SCALE)
    private val KEY_CHAT_BUBBLE_ALPHA = doublePreferencesKey(SettingsKeys.CHAT_BUBBLE_ALPHA)
    private val KEY_ENABLE_AUTO_UPDATE_CHECK = booleanPreferencesKey(SettingsKeys.ENABLE_AUTO_UPDATE_CHECK)
    private val KEY_ENABLE_CLICK_POETRY = booleanPreferencesKey(SettingsKeys.ENABLE_CLICK_POETRY)
    private val KEY_CLICK_POETRY_PRIVACY_ACKED = booleanPreferencesKey(SettingsKeys.CLICK_POETRY_PRIVACY_ACKED)
    private val KEY_ENABLE_CLICK_POETRY_SEARCH = booleanPreferencesKey(SettingsKeys.ENABLE_CLICK_POETRY_SEARCH)
    private val KEY_CLICK_POETRY_SEARCH_PRIVACY_ACKED = booleanPreferencesKey(SettingsKeys.CLICK_POETRY_SEARCH_PRIVACY_ACKED)
    private val KEY_CLICK_POETRY_SEARCH_KEYWORDS = stringPreferencesKey(SettingsKeys.CLICK_POETRY_SEARCH_KEYWORDS)
    private val KEY_CLICK_POETRY_SEARCH_PROBABILITY = intPreferencesKey(SettingsKeys.CLICK_POETRY_SEARCH_PROBABILITY)
    private val KEY_DEVELOPER_MODE_ENABLED = booleanPreferencesKey(SettingsKeys.DEVELOPER_MODE_ENABLED)
    private val KEY_CLICK_POETRY_PROBABILITY = intPreferencesKey(SettingsKeys.CLICK_POETRY_PROBABILITY)
    private val KEY_WALLPAPER_PATH = stringPreferencesKey(SettingsKeys.WALLPAPER_PATH)
    private val KEY_WALLPAPER_BLUR = doublePreferencesKey(SettingsKeys.WALLPAPER_BLUR)
    private val KEY_BACKUP_NOTICE_ACKED = booleanPreferencesKey(SettingsKeys.BACKUP_NOTICE_ACKED)
    private val KEY_PRIVACY_VERSION_SHOWN = stringPreferencesKey(SettingsKeys.PRIVACY_VERSION_SHOWN)

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

    /** 主界面语音开关流。 */
    val ttsMainEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_MAIN_ENABLED] ?: SettingsKeys.Defaults.TTS_MAIN_ENABLED
    }

    /** 悬浮窗语音开关流。 */
    val ttsOverlayEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_OVERLAY_ENABLED] ?: SettingsKeys.Defaults.TTS_OVERLAY_ENABLED
    }

    /** 语速流（length_scale，1.0 原速）。 */
    val ttsLengthScaleFlow: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_LENGTH_SCALE] ?: SettingsKeys.Defaults.TTS_LENGTH_SCALE
    }

    /** 主页聊天气泡透明度流（0.2~1.0，1.0 不透明）。 */
    val chatBubbleAlphaFlow: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_CHAT_BUBBLE_ALPHA] ?: SettingsKeys.Defaults.CHAT_BUBBLE_ALPHA
    }

    /** 启动自动检查更新开关流（默认开启）。 */
    val enableAutoUpdateCheckFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_AUTO_UPDATE_CHECK] ?: SettingsKeys.Defaults.ENABLE_AUTO_UPDATE_CHECK
    }

    /** 点击取诗开关流（默认关闭）。 */
    val enableClickPoetryFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_CLICK_POETRY] ?: SettingsKeys.Defaults.ENABLE_CLICK_POETRY
    }

    /** 点击取诗的启用隐私告知是否已确认流（默认未确认）。 */
    val clickPoetryPrivacyAckedFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_CLICK_POETRY_PRIVACY_ACKED] ?: SettingsKeys.Defaults.CLICK_POETRY_PRIVACY_ACKED
    }

    /** 检索线开关流（默认关闭）。 */
    val enableClickPoetrySearchFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_CLICK_POETRY_SEARCH] ?: SettingsKeys.Defaults.ENABLE_CLICK_POETRY_SEARCH
    }

    /** 检索线启用告知是否已确认流（默认未确认）。 */
    val clickPoetrySearchPrivacyAckedFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_CLICK_POETRY_SEARCH_PRIVACY_ACKED] ?: SettingsKeys.Defaults.CLICK_POETRY_SEARCH_PRIVACY_ACKED
    }

    /** 检索关键词原文流（逗号分隔字符串，UI 编辑用；解析见 [getClickPoetrySearchKeywords]）。 */
    val clickPoetrySearchKeywordsFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_CLICK_POETRY_SEARCH_KEYWORDS] ?: SettingsKeys.Defaults.CLICK_POETRY_SEARCH_KEYWORDS
    }

    /** 检索线独立概率流（0–100，默认 100）。 */
    val clickPoetrySearchProbabilityFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_CLICK_POETRY_SEARCH_PROBABILITY] ?: SettingsKeys.Defaults.CLICK_POETRY_SEARCH_PROBABILITY
    }

    /** 开发者选项解锁状态流（默认未解锁）。 */
    val developerModeEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DEVELOPER_MODE_ENABLED] ?: SettingsKeys.Defaults.DEVELOPER_MODE_ENABLED
    }

    /** 单击取诗概率流（0–100，默认 100）。 */
    val clickPoetryProbabilityFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_CLICK_POETRY_PROBABILITY] ?: SettingsKeys.Defaults.CLICK_POETRY_PROBABILITY
    }

    /** 主界面背景壁纸路径流（空串 = 默认纯色）。 */
    val wallpaperPathFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_WALLPAPER_PATH] ?: SettingsKeys.Defaults.WALLPAPER_PATH
    }

    /** 主界面背景壁纸模糊强度流（0~1，0 = 不模糊）。 */
    val wallpaperBlurFlow: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_WALLPAPER_BLUR] ?: SettingsKeys.Defaults.WALLPAPER_BLUR
    }

    /** 已看过/已处理的隐私政策版本号流（默认空串 = 未处理）。 */
    val privacyVersionShownFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PRIVACY_VERSION_SHOWN] ?: SettingsKeys.Defaults.PRIVACY_VERSION_SHOWN
    }

    // ── 同步 getter（非 Flow 场景使用，如 Client 构造）──
    // 读取内存快照，正常路径无磁盘 IO；快照未就绪时短暂 runBlocking 读一次兜底

    private fun currentPrefs(): Preferences =
        cachedPrefs ?: runBlocking { dataStore.data.first() }.also { cachedPrefs = it }

    /** 导出采集用：当前全量快照。取舍（哪些键能进包）在 SettingsWhitelist 侧做，这里不筛。 */
    fun exportSnapshot(): Preferences = currentPrefs()

    /**
     * 导入回灌用：在一个 DataStore 编辑事务里跑 [block]，返回**事务前**的全量快照。
     *
     * 把快照与写入放在同一次调用里，回滚才能拿到"被这次导入打扰之前"的真值
     * （包括"这个键原本根本不存在"这种状态）。同步快照照常刷新，UI 下帧即生效。
     */
    suspend fun editForImport(block: (MutablePreferences) -> Unit): Preferences {
        val before = currentPrefs()
        cachedPrefs = dataStore.edit { prefs -> block(prefs) }
        return before
    }

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

    /** 主界面语音开关。 */
    fun isTtsMainEnabled(): Boolean = currentPrefs()[KEY_TTS_MAIN_ENABLED] ?: SettingsKeys.Defaults.TTS_MAIN_ENABLED

    /** 悬浮窗语音开关。 */
    fun isTtsOverlayEnabled(): Boolean = currentPrefs()[KEY_TTS_OVERLAY_ENABLED] ?: SettingsKeys.Defaults.TTS_OVERLAY_ENABLED

    /** 语速（length_scale，1.0 原速）。 */
    fun getTtsLengthScale(): Double = currentPrefs()[KEY_TTS_LENGTH_SCALE] ?: SettingsKeys.Defaults.TTS_LENGTH_SCALE

    /** 主页聊天气泡透明度（0.2~1.0，1.0 不透明）。 */
    fun getChatBubbleAlpha(): Double = currentPrefs()[KEY_CHAT_BUBBLE_ALPHA] ?: SettingsKeys.Defaults.CHAT_BUBBLE_ALPHA

    /** 是否启用启动自动检查更新（默认开启）。 */
    fun isAutoUpdateCheckEnabled(): Boolean =
        currentPrefs()[KEY_ENABLE_AUTO_UPDATE_CHECK] ?: SettingsKeys.Defaults.ENABLE_AUTO_UPDATE_CHECK

    /** 是否启用点击取诗（默认关闭）。 */
    fun isClickPoetryEnabled(): Boolean =
        currentPrefs()[KEY_ENABLE_CLICK_POETRY] ?: SettingsKeys.Defaults.ENABLE_CLICK_POETRY

    /** 点击取诗的启用隐私告知是否已确认（默认未确认）。 */
    fun isClickPoetryPrivacyAcked(): Boolean =
        currentPrefs()[KEY_CLICK_POETRY_PRIVACY_ACKED] ?: SettingsKeys.Defaults.CLICK_POETRY_PRIVACY_ACKED

    /** 检索线开关（默认关闭）。 */
    fun isClickPoetrySearchEnabled(): Boolean =
        currentPrefs()[KEY_ENABLE_CLICK_POETRY_SEARCH] ?: SettingsKeys.Defaults.ENABLE_CLICK_POETRY_SEARCH

    /** 检索线启用告知是否已确认（默认未确认；未确认不许外发关键词，桌面双闸同形）。 */
    fun isClickPoetrySearchPrivacyAcked(): Boolean =
        currentPrefs()[KEY_CLICK_POETRY_SEARCH_PRIVACY_ACKED] ?: SettingsKeys.Defaults.CLICK_POETRY_SEARCH_PRIVACY_ACKED

    /** 检索关键词表：逗号/空白分隔解析、去空、按首次出现去重。 */
    fun getClickPoetrySearchKeywords(): List<String> =
        parseClickPoetryKeywords(getClickPoetrySearchKeywordsRaw())

    /** 检索关键词表原文（设置页编辑框用）。 */
    fun getClickPoetrySearchKeywordsRaw(): String =
        currentPrefs()[KEY_CLICK_POETRY_SEARCH_KEYWORDS] ?: SettingsKeys.Defaults.CLICK_POETRY_SEARCH_KEYWORDS

    /** 检索线独立概率（0–100，默认 100）：与随机线各掷各的签。 */
    fun getClickPoetrySearchProbability(): Int =
        currentPrefs()[KEY_CLICK_POETRY_SEARCH_PROBABILITY] ?: SettingsKeys.Defaults.CLICK_POETRY_SEARCH_PROBABILITY

    /** 开发者选项是否已解锁（默认未解锁）。 */
    fun isDeveloperModeEnabled(): Boolean =
        currentPrefs()[KEY_DEVELOPER_MODE_ENABLED] ?: SettingsKeys.Defaults.DEVELOPER_MODE_ENABLED

    /** 单击取诗概率（0–100，默认 100）。悬浮窗单击时同步读，按概率决定是否取诗。 */
    fun getClickPoetryProbability(): Int =
        currentPrefs()[KEY_CLICK_POETRY_PROBABILITY] ?: SettingsKeys.Defaults.CLICK_POETRY_PROBABILITY

    /** 主界面背景壁纸路径（空串 = 默认纯色）。 */
    fun getWallpaperPath(): String = currentPrefs()[KEY_WALLPAPER_PATH] ?: SettingsKeys.Defaults.WALLPAPER_PATH

    /** 主界面背景壁纸模糊强度（0~1，0 = 不模糊）。 */
    fun getWallpaperBlur(): Double = currentPrefs()[KEY_WALLPAPER_BLUR] ?: SettingsKeys.Defaults.WALLPAPER_BLUR

    /** 「导出包里有什么」的告知是否已确认过（默认未确认，首次导出前必弹一次）。 */
    fun isBackupNoticeAcked(): Boolean =
        currentPrefs()[KEY_BACKUP_NOTICE_ACKED] ?: SettingsKeys.Defaults.BACKUP_NOTICE_ACKED

    /** 已看过/已处理的隐私政策版本号（默认空串 = 未处理）。 */
    fun getPrivacyVersionShown(): String =
        currentPrefs()[KEY_PRIVACY_VERSION_SHOWN] ?: SettingsKeys.Defaults.PRIVACY_VERSION_SHOWN

    /** 是否首次启动（同步读取；默认 true）。 */
    fun isFirstLaunch(): Boolean = currentPrefs()[KEY_FIRST_LAUNCH] ?: true

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

    suspend fun setTtsMainEnabled(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_TTS_MAIN_ENABLED] = enabled }
    }

    suspend fun setTtsOverlayEnabled(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_TTS_OVERLAY_ENABLED] = enabled }
    }

    suspend fun setTtsLengthScale(scale: Double) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_TTS_LENGTH_SCALE] = scale }
    }

    suspend fun setChatBubbleAlpha(alpha: Double) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_CHAT_BUBBLE_ALPHA] = alpha }
    }

    suspend fun setEnableAutoUpdateCheck(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_AUTO_UPDATE_CHECK] = enabled }
    }

    /** 开关点击取诗（设置页每次拨动都落盘）。 */
    suspend fun setClickPoetryEnabled(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_CLICK_POETRY] = enabled }
    }

    /** 记录点击取诗的启用隐私告知已确认（只写 true，幂等）。 */
    suspend fun setClickPoetryPrivacyAcked() {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_CLICK_POETRY_PRIVACY_ACKED] = true }
    }

    /** 开关检索线（设置页每次拨动都落盘）。 */
    suspend fun setClickPoetrySearchEnabled(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_CLICK_POETRY_SEARCH] = enabled }
    }

    /** 记录检索线的启用告知已确认（只写 true，幂等）。 */
    suspend fun setClickPoetrySearchPrivacyAcked() {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_CLICK_POETRY_SEARCH_PRIVACY_ACKED] = true }
    }

    /** 写入检索关键词表（存原文，读时解析；空表不拦——生效判定在门控侧按"没词就不参与"处理）。 */
    suspend fun setClickPoetrySearchKeywords(raw: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_CLICK_POETRY_SEARCH_KEYWORDS] = raw }
    }

    /** 写入检索线概率，越界值钳到 [0, 100]（与随机线 setter 同形）。 */
    suspend fun setClickPoetrySearchProbability(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_CLICK_POETRY_SEARCH_PROBABILITY] = clamped }
    }

    /** 解锁开发者选项（只写 true，幂等；不提供回关入口是有意口径）。 */
    suspend fun setDeveloperModeEnabled() {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_DEVELOPER_MODE_ENABLED] = true }
    }

    /** 写入单击取诗概率，越界值钳到 [0, 100]。 */
    suspend fun setClickPoetryProbability(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_CLICK_POETRY_PROBABILITY] = clamped }
    }

    /** 记录已看过/已处理的隐私政策版本号。 */
    suspend fun setPrivacyVersionShown(version: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_PRIVACY_VERSION_SHOWN] = version }
    }

    suspend fun setWallpaperPath(path: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_WALLPAPER_PATH] = path }
    }

    suspend fun setWallpaperBlur(blur: Double) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_WALLPAPER_BLUR] = blur }
    }

    /** 记录导出告知已确认（只写 true，幂等）。 */
    suspend fun setBackupNoticeAcked() {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_BACKUP_NOTICE_ACKED] = true }
    }

    /** 清除所有设置（恢复出厂）。 */
    suspend fun clearAll() {
        cachedPrefs = dataStore.edit { it.clear() }
        Log.i(TAG, "All settings cleared")
    }
}

/**
 * 解析检索关键词原文：接受中英文逗号、顿号与换行分隔，去空白项并按首次出现去重。
 * 顶层纯函数便于单测；[SettingsManager.getClickPoetrySearchKeywords] 用同一口径。
 */
internal fun parseClickPoetryKeywords(raw: String): List<String> =
    raw.split(',', '，', '、', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
