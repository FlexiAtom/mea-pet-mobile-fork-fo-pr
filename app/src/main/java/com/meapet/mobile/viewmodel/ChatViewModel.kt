package com.meapet.mobile.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meapet.mobile.chat.ChatEvent
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ChatService
import com.meapet.mobile.chat.ChatUiState
import com.meapet.mobile.chat.MemoryDialogUi
import com.meapet.mobile.chat.UpdateNoticeUi
import com.meapet.mobile.framework.MeaPetApplication
import com.meapet.mobile.live2d.Live2dManager
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryStats
import com.meapet.mobile.update.UpdateCheckResult
import com.meapet.mobile.update.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 聊天界面 ViewModel。
 *
 * ## 职责
 * - 持有 [ChatUiState] 并通过 StateFlow 暴露给 UI；
 * - 处理 [ChatEvent] 用户交互事件；
 * - 调用 [ChatService] 发送消息；
 * - 调用 [MemoryManager] 管理记忆。
 *
 * ## 生命周期
 * - 通过 AndroidViewModel 获取 Application Context；
 * - 从 [MeaPetApplication.container] 获取依赖。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = MeaPetApplication.from(application)
    private val chatService: ChatService = container.chatService
    private val memoryManager: MemoryManager? = container.memoryManager
    private val updateChecker: UpdateChecker = container.updateChecker

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 在途发送任务；清空会话时取消，避免回复回写到已清空的会话。 */
    private var sendJob: Job? = null

    /** 关于页手动检测任务。 */
    private var checkUpdateJob: Job? = null

    init {
        // 初始化时从 ConversationManager 加载已有消息
        val existingMessages = chatService.getHistory()
        if (existingMessages.isNotEmpty()) {
            _state.update { it.copy(messages = existingMessages) }
        }

        // 等启动预热（磁盘加载会话历史/记忆）完成后刷新消息列表：
        // ViewModel 往往先于异步加载完成而创建，此时 getHistory() 还是空的
        viewModelScope.launch {
            container.warmUpJob.join()
            val restored = chatService.getHistory()
            if (restored.isNotEmpty()) {
                _state.update { current ->
                    // 保留加载期间产生的新消息（按 id 去重，恢复的历史在前）
                    val currentIds = restored.map { it.id }.toSet()
                    val newDuringLoad = current.messages.filter { it.id !in currentIds }
                    current.copy(messages = restored + newDuringLoad)
                }
            }
        }

        // 监听 Live2D 触摸分区事件→添加系统消息气泡
        // 每条气泡独立生命周期，从产生开始倒计时 7 秒。
        // 新消息到达时按位置自动扣除旧气泡的剩余寿命：
        //   position 1-3（最新）→ 不扣
        //   position 4-5          → 扣 2 秒
        //   position 6+（最旧）→ 扣 4 秒（首次扣 2 秒 + 再次扣 2 秒）
        viewModelScope.launch {
            // msgId → (剩余毫秒, 移除Job, 是否已扣过2秒)
            val lifeMap = LinkedHashMap<String, Triple<Long, Job, Boolean>>()

            Live2dManager.tapMessageEvent.collect { text ->
                val newMsg = ChatMessage(role = ChatRole.system, content = text)
                _state.update { it.copy(messages = it.messages + newMsg) }

                // 为本条启动独立倒计时，初始 7 秒
                val job = scheduleRemove(lifeMap, newMsg.id, 7_000L)
                lifeMap[newMsg.id] = Triple(7_000L, job, false)

                // 重新排位：按 timestamp 降序（最新在前），重新分配剩余寿命
                val sysIds = _state.value.messages
                    .filter { it.role == ChatRole.system }
                    .sortedByDescending { it.timestamp }
                    .map { it.id }

                sysIds.forEachIndexed { index, id ->
                    val position = index + 1 // 1 = 最新
                    val entry = lifeMap[id] ?: return@forEachIndexed
                    val (currentLife, oldJob, alreadyReduced) = entry

                    var newLife = currentLife
                    if (position > 5 && alreadyReduced) {
                        // 第 6+ 条且之前扣过 2 秒：再扣 2 秒
                        newLife = (currentLife - 2_000L).coerceAtLeast(0)
                    } else if (position > 3 && !alreadyReduced) {
                        // 第 4-5 条：首次扣 2 秒
                        newLife = (currentLife - 2_000L).coerceAtLeast(0)
                    }

                    if (newLife != currentLife) {
                        oldJob.cancel()
                        val newJob = scheduleRemove(lifeMap, id, newLife)
                        lifeMap[id] = Triple(newLife, newJob, alreadyReduced || position > 3)
                    }
                }
            }
        }

        // 启动时静默检测一次：仅在有新版本时提示，失败不打扰
        viewModelScope.launch {
            when (val result = updateChecker.check()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _state.update {
                        it.copy(
                            updateNotice = UpdateNoticeUi(
                                message = "发现新版本 v${result.release.versionName}",
                                url = result.release.htmlUrl
                            )
                        )
                    }
                }
                is UpdateCheckResult.UpToDate,
                is UpdateCheckResult.Failed -> Unit
            }
        }
    }

    /**
     * 重新从会话历史加载消息列表。
     *
     * 悬浮窗期间对话发生在共享的 [ChatService]（同一 ConversationManager）里，
     * 本 ViewModel 的内存状态不会自动同步；后台切前台时调用一次，把最新历史合并
     * 进列表。合并按 id 去重，**保留当前界面里所有尚未落库的消息**（系统气泡、乐观
     * 消息等），绝不覆盖内存状态；发送中（isLoading）跳过，交由在途任务自行更新，
     * 避免重复。
     */
    fun reloadHistory() {
        if (_state.value.isLoading) return
        val history = chatService.getHistory()
        if (history.isEmpty()) return
        _state.update { current ->
            val historyIds = history.map { it.id }.toSet()
            val extra = current.messages.filterNot { it.id in historyIds }
            current.copy(messages = history + extra)
        }
    }

    /**
     * 处理 UI 事件。
     */
    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.SendMessage -> sendMessage(event.content)
            is ChatEvent.UpdateInput -> updateInput(event.text)
            is ChatEvent.ClearConversation -> clearConversation()
            is ChatEvent.ClearMemory -> clearMemory()
            is ChatEvent.ShowMemories -> showMemories()
            is ChatEvent.DismissMemories -> dismissMemories()
            is ChatEvent.DeleteMemory -> deleteMemory(event.id)
            is ChatEvent.RetryLastMessage -> retryLastMessage()
            is ChatEvent.DismissError -> dismissError()
            is ChatEvent.DismissMemoryInfo -> dismissMemoryInfo()
            is ChatEvent.DismissUpdateNotice -> dismissUpdateNotice()
            is ChatEvent.CheckForUpdate -> checkForUpdate()
            is ChatEvent.DismissAboutUpdateMessage -> dismissAboutUpdateMessage()
        }
    }

    // ── 事件处理 ──────────────────────────────────────

    private fun sendMessage(content: String) {
        if (content.isBlank()) return

        val userMessage = ChatMessage(role = ChatRole.user, content = content)
        _state.update {
            it.copy(
                messages = it.messages + userMessage,
                isLoading = true,
                error = null,
                inputText = ""
            )
        }

        sendJob = viewModelScope.launch {
            val result = chatService.sendMessage(content)
            result.fold(
                onSuccess = { (userMsg, assistantMsg) ->
                    _state.update { current ->
                        // 按 ViewModel 的 ID 移除乐观消息，再换上 ChatService 的正式消息
                        val updatedMessages = current.messages
                            .filterNot { it.id == userMessage.id }
                            .let { list -> list + listOf(userMsg, assistantMsg) }
                        current.copy(
                            messages = updatedMessages,
                            isLoading = false
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "发送失败，请检查网络和 API Key"
                        )
                    }
                }
            )
        }
    }

    private fun retryLastMessage() {
        val lastUserMsg = _state.value.messages.lastOrNull { it.isUser }
            ?: return

        _state.update {
            it.copy(
                isLoading = true,
                error = null
            )
        }

        sendJob = viewModelScope.launch {
            val result = chatService.retryLastMessage()
            result.fold(
                onSuccess = { (userMsg, assistantMsg) ->
                    _state.update { current ->
                        val updated = current.messages
                            .filterNot { msg ->
                                msg.id == lastUserMsg.id || (
                                    msg.isAssistant && current.messages.indexOf(msg) >
                                        current.messages.indexOfLast { it.id == lastUserMsg.id }
                                )
                            }
                        current.copy(
                            messages = updated + listOf(userMsg, assistantMsg),
                            isLoading = false
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "重试失败"
                        )
                    }
                }
            )
        }
    }

    private fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    private fun clearConversation() {
        // 取消在途发送，否则回复到达后会把消息回写进已清空的会话
        sendJob?.cancel()
        sendJob = null
        chatService.clearHistory()
        _state.update {
            ChatUiState(memoryContextInfo = "对话已清除")
        }
    }

    private fun clearMemory() {
        viewModelScope.launch {
            memoryManager?.clearAll()
            _state.update {
                it.copy(
                    memoryContextInfo = "记忆已全部清除",
                    // 对话框开着时同步清空列表
                    memoryDialog = it.memoryDialog?.copy(
                        memories = emptyList(),
                        stats = MemoryStats()
                    )
                )
            }
        }
    }

    /** 打开记忆查看对话框：拉取全部记忆与统计。 */
    private fun showMemories() {
        val mm = memoryManager ?: return
        viewModelScope.launch {
            val memories = mm.getAllMemories()
            val stats = mm.getStats()
            _state.update {
                it.copy(
                    memoryDialog = MemoryDialogUi(
                        memories = memories,
                        stats = stats,
                        isMemoryEnabled = mm.isMemoryEnabled()
                    )
                )
            }
        }
    }

    private fun dismissMemories() {
        _state.update { it.copy(memoryDialog = null) }
    }

    /** 删除单条记忆并刷新对话框列表。 */
    private fun deleteMemory(id: String) {
        val mm = memoryManager ?: return
        viewModelScope.launch {
            mm.delete(id)
            _state.update { current ->
                val dialog = current.memoryDialog ?: return@update current
                current.copy(
                    memoryDialog = dialog.copy(
                        memories = dialog.memories.filterNot { it.id == id },
                        stats = mm.getStats()
                    )
                )
            }
        }
    }

    private fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun dismissMemoryInfo() {
        _state.update { it.copy(memoryContextInfo = null) }
    }

    private fun dismissUpdateNotice() {
        _state.update { it.copy(updateNotice = null) }
    }

    private fun dismissAboutUpdateMessage() {
        _state.update { it.copy(aboutUpdateMessage = null, aboutReleaseUrl = null) }
    }

    /**
     * 调度系统气泡的延时移除，返回对应的 Job。
     * @param lifeMap 更新此 map 中的条目
     * @param msgId 消息 ID
     * @param delayMs 剩余毫秒数
     */
    private fun scheduleRemove(
        lifeMap: MutableMap<String, Triple<Long, Job, Boolean>>,
        msgId: String,
        delayMs: Long
    ): Job = viewModelScope.launch {
        if (delayMs <= 0) {
            removeBubble(msgId)
            lifeMap.remove(msgId)
            return@launch
        }
        kotlinx.coroutines.delay(delayMs)
        removeBubble(msgId)
        lifeMap.remove(msgId)
    }

    private fun removeBubble(msgId: String) {
        _state.update {
            it.copy(messages = it.messages.filterNot { m -> m.id == msgId })
        }
    }

    /** 关于页手动检测：始终反馈结果（有更新 / 已是最新 / 失败）。 */
    private fun checkForUpdate() {
        if (_state.value.isCheckingUpdate) return
        checkUpdateJob?.cancel()
        _state.update {
            it.copy(
                isCheckingUpdate = true,
                aboutUpdateMessage = null,
                aboutReleaseUrl = null
            )
        }
        checkUpdateJob = viewModelScope.launch {
            when (val result = updateChecker.check()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            aboutUpdateMessage = "发现新版本 v${result.release.versionName}",
                            aboutReleaseUrl = result.release.htmlUrl
                        )
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            aboutUpdateMessage = "当前已是最新版本（v${result.currentVersion}）",
                            aboutReleaseUrl = null
                        )
                    }
                }
                is UpdateCheckResult.Failed -> {
                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            aboutUpdateMessage = result.message,
                            aboutReleaseUrl = null
                        )
                    }
                }
            }
        }
    }
}
