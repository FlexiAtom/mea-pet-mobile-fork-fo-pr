package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.meapet.mobile.content.HitokotoClient
import com.meapet.mobile.content.TavilyClient
import com.meapet.mobile.settings.parseClickPoetryKeywords
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

/**
 * 点击取诗（甲档：单源 hitokoto）：开关 + 启用那一刻的一次性告知弹窗。
 *
 * 告知只弹一次（启用时点），不是每次点击都弹——按点击弹会把它变成噪声，等于没有告知。
 * 弹窗里的端点地址直接取 [HitokotoClient.URL] 常量，UI 层不抄第二份字符串（防两处真值漂移）。
 */
@Composable
internal fun ClickPoetrySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showSearchPrivacyDialog by remember { mutableStateOf(false) }

    SectionTitle("点击取诗")

    SettingsSwitchRow(
        label = "单击悬浮桌宠取一句诗",
        description = "轻触悬浮窗桌宠 → 从一言(hitokoto.cn)诗词类目随机取一句，以气泡展示；" +
            "朗读遵循悬浮窗语音开关",
        checked = state.clickPoetryEnabled,
        darkTheme = darkTheme,
        onCheckedChange = { enabled ->
            if (enabled && !state.clickPoetryPrivacyAcked) {
                showPrivacyDialog = true
            } else {
                viewModel.updateClickPoetryEnabled(enabled)
            }
        }
    )

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("启用点击取诗") },
            text = {
                Text(
                    "开启后，轻触悬浮桌宠会向第三方服务发起一次随机取诗请求：\n" +
                        "· 会离开这台机器的内容：你的任何文字都不发送（该源不接受关键词，" +
                        "仅你的网络地址对请求可见）；\n" +
                        "· 请求地址：${HitokotoClient.URL}；\n" +
                        "· 失败回退：超时或取不到时不显示诗句，只在本地日志留一行记录。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPrivacyDialog = false
                    viewModel.acknowledgeClickPoetryPrivacy()
                    viewModel.updateClickPoetryEnabled(true)
                }) { Text("启用") }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 检索线（Tavily 网页检索）──
    SettingsSwitchRow(
        label = "检索线（按关键词检索网页）",
        description = "开启后单击从关键词表里随机抽一个词做 Tavily 网页检索，取回的资料片段交给对话模型" +
            "总结成不超过 60 字的一句进气泡——关键词不限于诗题，任意你感兴趣的词都可以；" +
            "任一环失败时交还分区语音并报错，不静默换随机线、也不直接念检索原文",
        checked = state.clickPoetrySearchEnabled,
        darkTheme = darkTheme,
        onCheckedChange = { enabled ->
            if (enabled && !state.clickPoetrySearchPrivacyAcked) {
                showSearchPrivacyDialog = true
            } else {
                viewModel.updateClickPoetrySearchEnabled(enabled)
            }
        }
    )

    var keywordsText by remember(state.clickPoetrySearchKeywordsRaw) {
        mutableStateOf(state.clickPoetrySearchKeywordsRaw)
    }
    OutlinedTextField(
        value = keywordsText,
        onValueChange = { keywordsText = it },
        label = { Text("检索关键词（逗号分隔）") },
        supportingText = {
            val parsed = parseClickPoetryKeywords(keywordsText)
            Text(
                if (parsed.isEmpty()) "关键词为空时这一路不参与——点了不会有检索发生"
                else "生效 ${parsed.size} 个，每次点击随机抽一个：${parsed.take(4).joinToString("、")}" +
                    if (parsed.size > 4) "…" else "",
                style = MaterialTheme.typography.bodySmall
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange {
                viewModel.updateClickPoetrySearchKeywords(keywordsText)
            },
        minLines = 1,
        maxLines = 3
    )

    if (showSearchPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showSearchPrivacyDialog = false },
            title = { Text("启用检索线") },
            text = {
                Text(
                    "开启后这一路会做两件事：\n" +
                        "· 每次点击从「检索关键词」表里随机抽一个词，作为检索请求发到第三方网页检索服务" +
                        "（不含你的其他文字与屏幕内容），请求地址：${TavilyClient.URL}；\n" +
                        "· 检索回来的第三方网页正文片段（已繁→简归一、单条截断）会发给你配置的 API 地址，" +
                        "让对话模型总结成不超过 60 字的一句再进气泡与语音——这些文字是第三方内容，" +
                        "只影响气泡与语音里说什么；\n" +
                        "· 失败回退：连不上/超时/检索没有可用文本/模型总结失败时，" +
                        "这一发交还分区语音并给出报错提示，不会静默念检索原文或别的。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSearchPrivacyDialog = false
                    viewModel.acknowledgeClickPoetrySearchPrivacy()
                    viewModel.updateClickPoetrySearchEnabled(true)
                }) { Text("启用") }
            },
            dismissButton = {
                TextButton(onClick = { showSearchPrivacyDialog = false }) { Text("取消") }
            }
        )
    }
}
