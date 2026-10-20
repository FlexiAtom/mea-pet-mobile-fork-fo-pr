package com.meapet.mobile.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.meapet.mobile.BuildConfig
import com.meapet.mobile.client.KtorHttpClientEngine
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.content.ClickPoetryFetcher
import com.meapet.mobile.content.ClickPoetryLine
import com.meapet.mobile.content.ClickPoetrySummarizer
import com.meapet.mobile.backup.AppBackupApplier
import com.meapet.mobile.backup.BackupService
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ChatService
import com.meapet.mobile.chat.ConversationManager
import com.meapet.mobile.chat.ConversationStore
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.core.AppInfo
import com.meapet.mobile.core.LifecycleManager
import com.meapet.mobile.core.WallpaperStore
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryRepository
import com.meapet.mobile.memory.MemoryService
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.TtsManager
import com.meapet.mobile.tts.TtsSynthesizer
import com.meapet.mobile.tts.VitsOnnxEngine
import com.meapet.mobile.tts.audio.TtsAudioPlayer
import com.meapet.mobile.tts.g2p.ChineseG2p
import com.meapet.mobile.tts.g2p.G2pProcessor
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.update.UpdateChecker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * 应用依赖容器（手动 DI / 服务定位器）。
 *
 * ## 职责
 * - 创建并持有所有子系统实例；
 * - 保证初始化顺序（Settings → Client → Memory → Chat）；
 * - 提供 [reloadClient] 等方法在运行时切换配置（如 API Key 变更）。
 *
 * ## 使用方式
 * ```kotlin
 * // 在 Application.onCreate 中初始化
 * val container = AppContainer(context)
 *
 * // 在 Activity / ViewModel 中获取
 * val chatService = container.chatService
 * ```
 *
 * ## 低耦合设计
 * - 各子系统之间仅通过接口/服务类交互，不直接依赖具体实现；
 * - [ChatService] 依赖 [MemoryManager] 接口，而非 MemoryRepository 具体类；
 * - 容器是唯一知道"谁依赖谁"的地方。
 *
 * @param context Application Context（非 Activity，防止泄漏）
 * @param config 全局配置，不传则使用 [AppConfig.DEFAULT]
 */
class AppContainer(
    private val context: Context,
    val config: AppConfig = AppConfig.fromBuildConfig(
        ttsModelBaseUrl = BuildConfig.TTS_MODEL_BASE_URL,
        appVersion = BuildConfig.VERSION_NAME
    )
) {
    companion object {
        private const val TAG = "AppContainer"

        /** 点击取诗单发请求的连接/整体/socket 超时（毫秒），短档，独立于 LLM 的 300s。 */
        private const val CLICK_POETRY_REQUEST_TIMEOUT_MS = 8_000L

        /** 检索线报错提示里失败理由的截断长度（理由全文进日志，气泡只给短的一截）。 */
        private const val ERROR_REASON_MAX_CHARS = 60
    }
    /**
     * 常驻加密密钥档（封形 A）。首次触碰才生成/读取——出厂默认关，
     * 没开过加密的机器不经历任何 Keystore 动作。
     */
    private val atRestDataKeyProvider: com.meapet.mobile.security.KeystoreDataKeyProvider by lazy {
        com.meapet.mobile.security.KeystoreDataKeyProvider()
    }

    /** 设置管理器（值级 API Key 编解码在此接线；开关位仍归 DataStore 自己）。 */
    val settingsManager: SettingsManager by lazy {
        SettingsManager(context, atRestDataKeyProvider)
    }

    /** 文件级常驻加密接缝：开关关（出厂态）时行为与明文时代逐字节一致。 */
    private val atRestCrypto: com.meapet.mobile.security.AtRestCrypto by lazy {
        com.meapet.mobile.security.AtRestCrypto.providerBacked(
            enabled = { settingsManager.isAtRestEncryptionEnabled() },
            provider = atRestDataKeyProvider,
        )
    }

    /**
     * 点击取诗（hitokoto）：独立 KtorHttpClientEngine 实例，超时压到短档，
     * 不与服务 LLM 长回复的 apiClient 共用（同一 client 无法同时服务 5 分钟 LLM 与 8 秒取文）。
     */
    val clickPoetryFetcher: ClickPoetryFetcher by lazy {
        ClickPoetryFetcher(
            engine = KtorHttpClientEngine(
                connectTimeoutMillis = CLICK_POETRY_REQUEST_TIMEOUT_MS,
                requestTimeoutMillis = CLICK_POETRY_REQUEST_TIMEOUT_MS,
                socketTimeoutMillis = CLICK_POETRY_REQUEST_TIMEOUT_MS
            ),
            logger = { Log.w("ClickPoetry", it) }
        )
    }

    /**
     * 检索线命中后的一次性 LLM 总结（人工裁决 2026-10-18：「像 PC 一样过下 LLM
     * 总结成不超过 60 的一句」）。复用 [apiClient]（300s 长档引擎）——总结本就是
     * 一次对话请求，与 3s 短档的取文 client 各走各的；超时由 Summarizer 自封。
     */
    val clickPoetrySummarizer: ClickPoetrySummarizer by lazy {
        ClickPoetrySummarizer(
            clientProvider = { apiClient },
            modelProvider = { settingsManager.getModel() },
            logger = { Log.w("ClickPoetrySummary", it) }
        )
    }

    /** OpenAI 兼容 HTTP 客户端。可通过 [reloadClient] 在运行时重建。 */
    val apiClient: OpenAiCompatibleClient
        get() = _apiClient ?: synchronized(clientLock) {
            _apiClient ?: createClient().also { _apiClient = it }
        }

    /** 会话历史持久化存储。 */
    val conversationStore: ConversationStore by lazy {
        ConversationStore(context.filesDir, applicationScope, atRestCrypto)
    }

    /** 会话历史管理器。 */
    val conversationManager: ConversationManager by lazy {
        ConversationManager(
            maxSize = config.maxHistoryMessages,
            trimBatch = config.historyTrimBatch,
            store = conversationStore
        )
    }

    /** 记忆存储器。 */
    val memoryRepository: MemoryRepository by lazy {
        MemoryRepository(context.filesDir, maxItems = config.maxMemoryItems, crypto = atRestCrypto)
    }

    /** 记忆业务服务。 */
    val memoryService: MemoryService by lazy {
        MemoryService(
            repository = memoryRepository,
            summarizationClient = { apiClient },
            settingsManager = settingsManager,
            config = config
        )
    }

    /** 记忆管理器（高层面 orchestrator）。 */
    val memoryManager: MemoryManager by lazy {
        MemoryManager(
            service = memoryService,
            repository = memoryRepository,
            settingsManager = settingsManager,
            config = config
        )
    }

    /** 聊天服务（核心业务逻辑）。 */
    val chatService: ChatService by lazy {
        ChatService(
            clientProvider = { apiClient },
            conversationManager = conversationManager,
            memoryManager = memoryManager,
            settingsManager = settingsManager,
            postProcessScope = applicationScope,
            config = config
        )
    }

    /** 应用级协程作用域（用于非 UI 的后台任务）。 */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    /** 版本更新检测（独立 HTTP 引擎，不与用户 API Key 绑定）。 */
    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(currentVersionProvider = { readAppVersion() })
    }

    /** 主界面背景壁纸导入与清理（相册 URI → filesDir）。 */
    val wallpaperStore: WallpaperStore by lazy {
        WallpaperStore(context)
    }

    /** 数据导出/导入的 SAF 暂存层（口令不过这里）。 */
    val backupService: BackupService by lazy {
        BackupService(context)
    }

    /**
     * 造一个把导入条目落到真实存储的执行体。
     *
     * 不是 lazy 单例：暂存格由调用方（BackupService）按次给出，装的是那一次导入的
     * 旧图副本。设置类落地后热刷一次 [reloadClient]——否则换完包还拿着旧 Base URL。
     */
    fun newBackupApplier(stashDir: File): AppBackupApplier = AppBackupApplier(
        settings = settingsManager,
        conversationStore = conversationStore,
        conversationManager = conversationManager,
        memory = memoryRepository,
        wallpaper = wallpaperStore,
        stashDir = stashDir,
        onSettingsChanged = { reloadClient() }
    )

    /** 生命周期管理器。 */
    val lifecycleManager: LifecycleManager by lazy {
        LifecycleManager(
            trimMemoryCallback = { level ->
                // 只做日志，不在这里调 suspend 函数（系统回调可能在任何线程触发，
                // 且此时各 lazy 组件可能尚未初始化，容易导致崩溃）
                if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                    Log.i(TAG, "系统内存紧张（level=$level），由各组件自行在下次操作时清理")
                }
            }
        )
    }

    // ── TTS 语音 ──────────────────────────────────────

    /** TTS 模型/词典下载与状态管理。 */
    val ttsModelManager: TtsModelManager by lazy {
        TtsModelManager(context)
    }

    /** VITS 四模块 ONNX 推理引擎（懒加载 73MB 权重）。 */
    private val vitsEngine: VitsOnnxEngine by lazy {
        VitsOnnxEngine(ttsModelManager)
    }

    /** G2P 编排：中文走拼音映射。 */
    private val g2pProcessor: G2pProcessor by lazy {
        G2pProcessor(chinese = ChineseG2p(context))
    }

    /** TTS 门面：开关判断、整段合成播放。 */
    val ttsManager: TtsManager by lazy {
        TtsManager(
            settingsManager = settingsManager,
            modelManager = ttsModelManager,
            synthesizer = TtsSynthesizer(vitsEngine, g2pProcessor),
            player = TtsAudioPlayer(),
            scope = applicationScope
        ).also { wireVoiceMutex() }
    }

    /**
     * 接线触摸语音 ↔ TTS 互斥（经 Live2dDelegate 的进程级 hook，避免包间反向依赖）：
     * - 触摸语音触发时若 TTS 在播，先停 TTS；
     * - TTS 开始播放时停掉未完触摸语音。
     */
    private fun wireVoiceMutex() {
        // 触摸语音触发时若 TTS 在播，先停 TTS
        com.meapet.mobile.live2d.Live2dDelegate.ttsPlayingChecker = { ttsManager.isPlaying.value }
        com.meapet.mobile.live2d.Live2dDelegate.ttsStopper = { ttsManager.stop() }
        // TTS 开始播放时停掉未完的触摸语音
        TtsManager.onPlaybackStart = {
            com.meapet.mobile.live2d.Live2dDelegate.getInstance().stopTouchVoices()
        }
    }

    // ── 点击取诗 ──────────────────────────────────────

    /**
     * 检索线是否**生效**（桌面 `participating_sources` 双闸同形）：开关 + 已确认告知 +
     * 关键词表非空。手改存储把 enabled 拧成 true 而没点过告知不算生效——不许外发关键词。
     */
    private fun isClickPoetrySearchLive(): Boolean =
        settingsManager.isClickPoetrySearchEnabled() &&
            settingsManager.isClickPoetrySearchPrivacyAcked() &&
            settingsManager.getClickPoetrySearchKeywords().isNotEmpty()

    /**
     * 单击取诗门控：两线各自掷签、检索优先（人工裁决 2026-10-18，修复共享概率把
     * 随机线实际命中率挤成 0 的问题）——检索线生效且有独立概率（开发者选项，
     * 0=从不，100=每次）则先掷它；不中再掷随机线（原有概率）。两签皆不中=NONE。
     * 同步读内存快照，任意线程可调；悬浮窗与主界面共用这一道闸。
     *
     * 注意口径：检索线**掷签不中**不等于"检索失败"——那只是本发没选中检索线，
     * 随机线仍可命中；而检索线被选中后取文失败不回退随机线，交还分区语音并报错。
     */
    fun clickPoetryGate(): ClickPoetryLine {
        if (isClickPoetrySearchLive() && ClickPoetryFetcher.rollHits(
                settingsManager.getClickPoetrySearchProbability(),
                kotlin.random.Random.nextInt(100)
            )
        ) {
            return ClickPoetryLine.SEARCH
        }
        if (settingsManager.isClickPoetryEnabled() && ClickPoetryFetcher.rollHits(
                settingsManager.getClickPoetryProbability(),
                kotlin.random.Random.nextInt(100)
            )
        ) {
            return ClickPoetryLine.RANDOM
        }
        return ClickPoetryLine.NONE
    }

    /**
     * 接线主界面单击取诗（对齐悬浮窗/桌面语义）：点一下桌宠，命中门控即异步出网取诗，
     * 诗句经 [Live2dManager.emitTapMessage] 走既有 tap 消息通路（聊天页系统气泡），
     * 并按主界面语音开关朗读。命中时本次轻触不再触发分区语音。
     *
     * 失败档统一（人工裁决 2026-10-18）：检索线出网/超时/检索没有可用文本，或命中后
     * **模型总结未成功**——都交还分区语音（[Live2dDelegate.triggerZoneFeedback]）
     * 并气泡报错，不静默念检索原文。线程口径见该方法注释。
     * MainActivity.onCreate 调用；重复调用幂等（只是覆盖 hook）。
     */
    fun wireMainScreenClickPoetry() {
        com.meapet.mobile.live2d.Live2dDelegate.clickPoetryTapHandler = { x, y ->
            when (clickPoetryGate()) {
                ClickPoetryLine.NONE -> false
                ClickPoetryLine.RANDOM -> {
                    applicationScope.launch {
                        val line = clickPoetryFetcher.fetch() ?: return@launch
                        com.meapet.mobile.live2d.Live2dManager.emitTapMessage(line)
                        ttsManager.speak(
                            ChatMessage(role = ChatRole.assistant, content = line),
                            TtsManager.Source.MAIN
                        )
                    }
                    true
                }
                ClickPoetryLine.SEARCH -> {
                    val keywords = settingsManager.getClickPoetrySearchKeywords()
                    applicationScope.launch {
                        val search = clickPoetryFetcher.fetchCrawler(keywords)
                        // 命中≠结束：还要过一次性 LLM 总结成 ≤60 字那一句（人工裁决 2026-10-18）
                        val outcome = if (search.ok) {
                            clickPoetrySummarizer.summarize(checkNotNull(search.line))
                        } else {
                            search
                        }
                        val line = outcome.line
                        if (line != null) {
                            com.meapet.mobile.live2d.Live2dManager.emitTapMessage(line)
                            ttsManager.speak(
                                ChatMessage(role = ChatRole.assistant, content = line),
                                TtsManager.Source.MAIN
                            )
                        } else {
                            com.meapet.mobile.live2d.Live2dDelegate.getInstance()
                                .triggerZoneFeedback(x, y)
                            com.meapet.mobile.live2d.Live2dManager.emitTapMessage(
                                "检索线失败：${outcome.failureReason?.take(ERROR_REASON_MAX_CHARS).orEmpty()}"
                            )
                        }
                    }
                    true
                }
            }
        }
    }

    // ── 启动预热 ──────────────────────────────────────

    /**
     * 启动预热任务。[warmUp] 调用后完成记忆 + 会话历史的磁盘加载；
     * ViewModel 可 `join()` 等待加载完成后刷新 UI。
     * 未调用 warmUp 时为已完成的空 Job（各仓库有惰性加载兜底，不会丢数据）。
     */
    @Volatile
    var warmUpJob: Job = Job().apply { complete() }
        private set

    /** 启动时异步加载持久化的记忆与会话历史（不阻塞主线程）。 */
    fun warmUp() {
        warmUpJob = applicationScope.launch(Dispatchers.IO) {
            listOf(
                launch { memoryRepository.loadFromDisk() },
                launch { conversationManager.restore(conversationStore.load()) },
                // Live2D 模型文件预读到内存缓存：模型加载在 GL 线程同步执行，
                // asset 读取是最大耗时（阻塞主线程 onPause 数秒）。预热后 GL 线程
                // 只做解析 + GL 上传，启动/切前后台不再长时间卡顿。
                launch {
                    runCatching {
                        com.meapet.mobile.live2d.Live2dManager.getInstance()
                            .prewarmModel(context)
                    }.onFailure { Log.w(TAG, "Live2D 模型预热失败", it) }
                }
            ).joinAll()
        }
    }

    /** 从 PackageManager 读取当前 versionName（统一实现见 [AppInfo]）。 */
    fun readAppVersion(): String = AppInfo.readVersion(context)

    // ── 运行时热替换 ──────────────────────────────────

    /**
     * 在 API Key 或 Base URL 变更后重建 HTTP 客户端。
     *
     * 旧客户端引擎会被关闭；[ChatService]、[MemoryService] 通过 provider
     * 在下次请求时拿到新的 [apiClient]，无需重建服务实例。
     */
    fun reloadClient() {
        val old: OpenAiCompatibleClient?
        synchronized(clientLock) {
            old = _apiClient
            _apiClient = null
        }
        old?.close()
        Log.i(TAG, "API client invalidated, will be rebuilt on next request")
    }

    /**
     * 清除所有记忆数据。
     */
    suspend fun clearAllMemories() {
        memoryRepository.clear()
    }

    /**
     * 复位所有状态（清除对话 + 记忆）。
     */
    suspend fun resetAll() {
        conversationManager.clear()
        memoryRepository.clear()
    }

    // ── 内部可变缓存（用于 reload） ──

    private val clientLock = Any()

    @Volatile
    private var _apiClient: OpenAiCompatibleClient? = null

    private fun createClient(): OpenAiCompatibleClient {
        val apiKey = settingsManager.getApiKey()
        val baseUrl = settingsManager.getApiUrl()
        return OpenAiCompatibleClient(
            apiKey = apiKey,
            baseUrl = baseUrl,
            engine = KtorHttpClientEngine()
        )
    }
}
