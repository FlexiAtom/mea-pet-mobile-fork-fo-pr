package com.meapet.mobile.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.exception.ApiException
import com.meapet.mobile.client.model.ApiResponse
import com.meapet.mobile.framework.MeaPetApplication
import com.meapet.mobile.settings.SettingsKeys
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置界面 ViewModel。
 *
 * @property apiKey API Key
 * @property apiUrl API 根地址（通常以 `/v1` 结尾）
 * @property model 模型名
 * @property availableModels 从 API 拉取的模型 id 列表
 * @property isLoadingModels 是否正在拉取模型列表
 * @property modelsError 拉取失败时的错误信息
 */
data class SettingsUiState(
    val apiKey: String = "",
    val apiKeyMasked: String = "",
    val apiUrl: String = SettingsKeys.Defaults.API_URL,
    val model: String = SettingsKeys.Defaults.MODEL,
    val temperature: Double = SettingsKeys.Defaults.TEMPERATURE,
    val maxTokens: Int = SettingsKeys.Defaults.MAX_TOKENS,
    val systemPrompt: String = SettingsKeys.Defaults.SYSTEM_PROMPT,
    val enableMemory: Boolean = SettingsKeys.Defaults.ENABLE_MEMORY,
    val enableAutoSummary: Boolean = SettingsKeys.Defaults.ENABLE_AUTO_SUMMARY,
    val summaryInterval: Int = SettingsKeys.Defaults.SUMMARY_INTERVAL,
    val themeMode: String = SettingsKeys.Defaults.THEME_MODE,
    val enableDynamicColor: Boolean = SettingsKeys.Defaults.ENABLE_DYNAMIC_COLOR,
    val colorPreset: String = SettingsKeys.Defaults.COLOR_PRESET,
    val appVersion: String = "",
    val availableModels: List<String> = emptyList(),
    val isLoadingModels: Boolean = false,
    val modelsError: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = MeaPetApplication.from(application)
    private val settingsManager: SettingsManager = container.settingsManager

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** 从内存快照同步读取初始值，保证首帧就是已存设置（供 UI 本地编辑状态取初值）。 */
    private fun initialState(): SettingsUiState {
        val key = settingsManager.getApiKey()
        return SettingsUiState(
            apiKey = key,
            apiKeyMasked = maskApiKey(key),
            apiUrl = settingsManager.getApiUrl(),
            model = settingsManager.getModel(),
            temperature = settingsManager.getTemperature(),
            maxTokens = settingsManager.getMaxTokens(),
            systemPrompt = settingsManager.getSystemPrompt(),
            enableMemory = settingsManager.isMemoryEnabled(),
            enableAutoSummary = settingsManager.isAutoSummaryEnabled(),
            summaryInterval = settingsManager.getSummaryInterval()
        )
    }

    init {
        // 订阅所有设置流
        viewModelScope.launch {
            settingsManager.apiKeyFlow.collect { key ->
                _state.update {
                    it.copy(
                        apiKey = key,
                        apiKeyMasked = maskApiKey(key)
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsManager.apiUrlFlow.collect { url ->
                _state.update { it.copy(apiUrl = url) }
            }
        }
        viewModelScope.launch {
            settingsManager.modelFlow.collect { model ->
                _state.update { it.copy(model = model) }
            }
        }
        viewModelScope.launch {
            settingsManager.temperatureFlow.collect { temp ->
                _state.update { it.copy(temperature = temp) }
            }
        }
        viewModelScope.launch {
            settingsManager.maxTokensFlow.collect { tokens ->
                _state.update { it.copy(maxTokens = tokens) }
            }
        }
        viewModelScope.launch {
            settingsManager.systemPromptFlow.collect { prompt ->
                _state.update { it.copy(systemPrompt = prompt) }
            }
        }
        viewModelScope.launch {
            settingsManager.enableMemoryFlow.collect { enabled ->
                _state.update { it.copy(enableMemory = enabled) }
            }
        }
        viewModelScope.launch {
            settingsManager.enableAutoSummaryFlow.collect { enabled ->
                _state.update { it.copy(enableAutoSummary = enabled) }
            }
        }
        viewModelScope.launch {
            settingsManager.summaryIntervalFlow.collect { interval ->
                _state.update { it.copy(summaryInterval = interval) }
            }
        }
        viewModelScope.launch {
            settingsManager.themeModeFlow.collect { mode ->
                _state.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            settingsManager.enableDynamicColorFlow.collect { enabled ->
                _state.update { it.copy(enableDynamicColor = enabled) }
            }
        }
        viewModelScope.launch {
            settingsManager.colorPresetFlow.collect { preset ->
                _state.update { it.copy(colorPreset = preset) }
            }
        }

        // 从 PackageManager 读取版本号
        val version = try {
            application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
        _state.update { it.copy(appVersion = version) }
    }

    // ── 更新方法 ──────────────────────────────────────

    /** 保存 API Key（失焦/离开页面时调用）；值有变化才落盘并重建客户端。 */
    fun saveApiKey(key: String) {
        viewModelScope.launch {
            if (key == settingsManager.getApiKey()) return@launch
            settingsManager.setApiKey(key)
            container.reloadClient()
        }
    }

    /** 保存 API URL（失焦/离开页面时调用）；值有变化才落盘并重建客户端。 */
    fun saveApiUrl(url: String) {
        viewModelScope.launch {
            if (url == settingsManager.getApiUrl()) return@launch
            settingsManager.setApiUrl(url)
            container.reloadClient()
        }
    }

    /** 保存模型名（失焦/离开页面时调用）；值有变化才落盘。 */
    fun saveModel(model: String) {
        viewModelScope.launch {
            if (model == settingsManager.getModel()) return@launch
            settingsManager.setModel(model)
        }
    }

    /** 保存 System Prompt（失焦/离开页面时调用）；值有变化才落盘。 */
    fun saveSystemPrompt(prompt: String) {
        viewModelScope.launch {
            if (prompt == settingsManager.getSystemPrompt()) return@launch
            settingsManager.setSystemPrompt(prompt)
        }
    }

    fun updateTemperature(temp: Double) {
        viewModelScope.launch { settingsManager.setTemperature(temp) }
    }

    fun updateMaxTokens(tokens: Int) {
        viewModelScope.launch { settingsManager.setMaxTokens(tokens) }
    }

    fun updateEnableMemory(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setEnableMemory(enabled) }
    }

    fun updateEnableAutoSummary(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setEnableAutoSummary(enabled) }
    }

    fun updateSummaryInterval(interval: Int) {
        viewModelScope.launch { settingsManager.setSummaryInterval(interval) }
    }

    fun updateThemeMode(mode: String) {
        viewModelScope.launch { settingsManager.setThemeMode(mode) }
    }

    fun updateEnableDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setEnableDynamicColor(enabled) }
    }

    fun updateColorPreset(preset: String) {
        viewModelScope.launch { settingsManager.setColorPreset(preset) }
    }

    /**
     * 用当前表单里的 Key / URL 拉取模型列表。
     *
     * 会先把 Key、URL 落盘并重建客户端（与后续聊天共用同一配置），
     * 再用临时客户端请求 `/v1/models`，避免依赖 lazy 初始化时机。
     */
    fun fetchModels(apiKey: String, apiUrl: String) {
        // 本地模型（Ollama / LM Studio 等）不需要 API Key，允许留空直接拉取
        if (apiUrl.isBlank()) {
            _state.update { it.copy(modelsError = "请先填写 API 地址") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(isLoadingModels = true, modelsError = null)
            }
            // 先落盘当前表单值，保证后续聊天与拉取用同一配置
            if (apiKey != settingsManager.getApiKey()) {
                settingsManager.setApiKey(apiKey)
            }
            if (apiUrl != settingsManager.getApiUrl()) {
                settingsManager.setApiUrl(apiUrl)
            }
            container.reloadClient()

            val result = withContext(Dispatchers.IO) {
                val client = OpenAiCompatibleClient(apiKey = apiKey, baseUrl = apiUrl)
                try {
                    Result.success(ApiResponse.modelIds(client.listModels()))
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    client.close()
                }
            }

            result.fold(
                onSuccess = { ids ->
                    if (ids.isEmpty()) {
                        _state.update {
                            it.copy(
                                availableModels = emptyList(),
                                isLoadingModels = false,
                                modelsError = "接口未返回任何模型"
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                availableModels = ids,
                                isLoadingModels = false,
                                modelsError = null
                            )
                        }
                    }
                },
                onFailure = { e ->
                    val message = when (e) {
                        // ApiException.message 已是友好提示（401 → 提示填 Key）
                        is ApiException -> e.message
                        else -> e.message?.takeIf { it.isNotBlank() } ?: "获取模型列表失败"
                    }
                    _state.update {
                        it.copy(
                            isLoadingModels = false,
                            modelsError = message
                        )
                    }
                }
            )
        }
    }

    /** 从拉取结果中选中一个模型，写回本地设置。 */
    fun selectModel(model: String) {
        viewModelScope.launch {
            if (model == settingsManager.getModel()) return@launch
            settingsManager.setModel(model)
        }
    }

    fun dismissModelsError() {
        _state.update { it.copy(modelsError = null) }
    }

    // ── 工具 ──────────────────────────────────────────

    private fun maskApiKey(key: String): String {
        if (key.length <= 8) return "****"
        return "${key.take(4)}****${key.takeLast(4)}"
    }
}
