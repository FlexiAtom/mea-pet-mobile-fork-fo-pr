package com.meapet.mobile.ui.screen.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.meapet.mobile.content.HitokotoClient
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
}
