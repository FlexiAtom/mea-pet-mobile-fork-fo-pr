package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  开发者选项：实验性调节项（关于页连点 5 下版本号解锁）
// ═══════════════════════════════════════════════════

/**
 * 开发者选项页：目前只有"单击取诗概率"一格。
 *
 * 概率是取诗开关之上的第二道闸：开关关 = 整条链不跑；开关开且概率 < 100 =
 * 每次单击按概率掷骰决定是否真的出网取诗。默认 100，行为与无此格时完全一致。
 */
@Composable
internal fun DeveloperSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SectionTitle("点击取诗概率")

    Text(
        "开关开启后，每次单击仍按此概率（0–100）决定是否实际取诗；0 = 从不，100 = 每次。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    // 本地编辑文本，失焦时解析落盘（与提供商页输入框同形状）
    var text by remember(state.clickPoetryProbability) {
        mutableStateOf(state.clickPoetryProbability.toString())
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            // 只放行数字，最多 3 位
            if (input.all { it.isDigit() } && input.length <= 3) text = input
        },
        label = { Text("概率（%）") },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange {
                val parsed = text.toIntOrNull()
                if (parsed != null) {
                    val clamped = parsed.coerceIn(0, 100)
                    viewModel.updateClickPoetryProbability(clamped)
                    text = clamped.toString()
                } else {
                    text = state.clickPoetryProbability.toString()
                }
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
