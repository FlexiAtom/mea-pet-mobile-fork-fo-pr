package com.meapet.mobile.chat

import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryStats

/**
 * 聊天 UI 状态。
 *
 * 不可变数据类，由 ChatViewModel 持有并通过 StateFlow 下发。
 * UI 层仅读取此状态驱动渲染，不直接修改。
 *
 * @property messages 消息列表
 * @property isLoading 是否正在等待 AI 响应
 * @property error 错误信息（非 null 时显示错误提示）
 * @property memoryContextInfo 当前记忆上下文摘要（如 "已加载 3 条记忆"）
 * @property inputText 输入框当前文本
 * @property updateNotice 启动静默检测发现的新版本提示（Snackbar 用）
 * @property aboutUpdateMessage 关于页手动检测结果文案
 * @property aboutReleaseUrl 关于页检测有新版本时的发布页 URL
 * @property isCheckingUpdate 关于页是否正在检测更新
 * @property memoryDialog 记忆查看对话框数据（null = 不显示）
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val memoryContextInfo: String? = null,
    val inputText: String = "",
    val updateNotice: UpdateNoticeUi? = null,
    val aboutUpdateMessage: String? = null,
    val aboutReleaseUrl: String? = null,
    val isCheckingUpdate: Boolean = false,
    val memoryDialog: MemoryDialogUi? = null
)

/** UI 层展示的更新提示。 */
data class UpdateNoticeUi(
    val message: String,
    val url: String
)

/** 记忆查看对话框数据。 */
data class MemoryDialogUi(
    val memories: List<MemoryItem> = emptyList(),
    val stats: MemoryStats = MemoryStats(),
    val isMemoryEnabled: Boolean = true
)

