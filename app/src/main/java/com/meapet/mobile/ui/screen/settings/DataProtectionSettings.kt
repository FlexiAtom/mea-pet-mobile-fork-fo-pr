package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meapet.mobile.security.TrustVerdict
import com.meapet.mobile.viewmodel.AtRestSecurityViewModel
import com.meapet.mobile.viewmodel.AtRestUiState

/**
 * 「本机数据常驻加密」节（设置 → 数据与备份）。
 *
 * 三件必须说清的事都写进文案，不藏进弹窗：
 * - **范围**：只有聊天、记忆、API Key 三路（人工裁决 2026-10-20 09:40）；壁纸、设置页其余键、
 *   DataStore 的 protobuf 文件本体、以及导出包内的内容都不在此列（导出件里是明文 JSON + zip4j 信封）。
 * - **门控是设备自报**：引导锁状态由本机 ROM 提供，应用侧拿不到硬件签发的证明，
 *   所以这一环只证"这台机器怎么说自己"，**我们无法替它背书**。
 * - **解不开就是解不开**：密钥丢失不做静默降级，只给"重置=旧数据作废"这一条明示通路。
 */
@Composable
internal fun AtRestSecuritySection(viewModel: AtRestSecurityViewModel, darkTheme: Boolean) {
    val state by viewModel.state.collectAsState()
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val trusted = state.trust?.verdict == TrustVerdict.TRUSTED

    Column(Modifier.fillMaxWidth()) {
        SectionTitle("本机数据常驻加密")
        Body(
            "开启后，聊天、记忆与 API Key 在本机磁盘上以硬件绑定的密钥落密文（密钥不出芯片，不额外记口令）。" +
                "范围之外：壁纸、其余设置项、导出包内的内容——导出件仍是明文 JSON 加 zip 口令。"
        )

        SettingsSwitchRow(
            label = "常驻加密",
            description = when {
                state.busy -> "转换中，请稍候"
                !trusted -> "本机不可信或未报出引导状态，不提供此功能"
                state.enabled -> "已开启：新写入与存量都落密文"
                else -> "关闭（出厂默认）：磁盘按明文存"
            },
            checked = state.enabled,
            darkTheme = darkTheme,
            enabled = trusted && !state.busy,
            onCheckedChange = { on -> viewModel.setEnabled(on) }
        )

        // 门控读数常驻：置灰了却看不见凭什么置灰，等于让用户猜
        state.trust?.let { trust ->
            if (!trusted) {
                Body(
                    when (trust.verdict) {
                        TrustVerdict.UNTRUSTED ->
                            "检测到本机引导状态不是锁定档（${trust.detail}）。已解锁的机器由机主自行管控，" +
                                "常驻加密不再必要，这里不提供，也没有\"仍要开\"。"
                        else ->
                            "这台机器读不出引导状态（${trust.detail}），报不出就按不可信处置——不提供常驻加密。"
                    },
                    alpha = ALPHA_MUTED_TEXT
                )
            } else {
                Body("可信判定取自本机自报的引导锁状态（${trust.detail}）。设备自报的读数我们无法背书。", alpha = ALPHA_FAINT_TEXT)
            }
        }

        if (state.busy) {
            LinearProgressLike(state.doneSteps.toFloat() / state.totalSteps)
            Body(
                buildString {
                    append("转换中 ${state.doneSteps}/${state.totalSteps}")
                    state.lastStep?.let { append(" · 刚完成：$it") }
                },
                alpha = ALPHA_MUTED_TEXT
            )
        }

        state.leftover?.let { leftover ->
            Body(
                "上次${directionLabel(leftover.direction)}没走完（已完成 ${leftover.doneSteps.size}/${AtRestUiState.TOTAL_STEPS} 步）。" +
                    "半路的状态不毁数据——每份文件自己说它是密文还是明文；接着跑完即可。",
                alpha = ALPHA_MUTED_TEXT
            )
            OutlinedButton(onClick = { viewModel.resumeLeftover() }, enabled = !state.busy) {
                Text("继续完成转换")
            }
        }

        if (state.inconsistent && state.leftover == null) {
            Body("开关与磁盘上的存量不一致（转换期间被别的写入打断过）。再应用一次即收敛。", alpha = ALPHA_MUTED_TEXT)
            OutlinedButton(onClick = { viewModel.reconcile() }, enabled = !state.busy) {
                Text("再应用一次")
            }
        }

        if (state.enabled) {
            Body(
                "计划解锁 Bootloader 或刷机会连带旋转密钥，届时本机数据将再也解不开。要走那一步，先在这里关掉加密并等还原跑完。",
                alpha = ALPHA_MUTED_TEXT
            )
        }

        if (state.keyMissing) {
            Body(
                "磁盘上有密文，但本机的密钥已不在场（换签名/刷机/清除数据都会这样）。" +
                    "应用侧救不回来——唯一通路是作废这些旧数据、从干净状态重新开始。",
                alpha = ALPHA_MUTED_TEXT
            )
            OutlinedButton(onClick = { showDiscardConfirm = true }, enabled = !state.busy) {
                Text("重置加密（旧数据作废）")
            }
        }

        state.notice?.let {
            Body(it, alpha = ALPHA_MUTED_TEXT)
            TextButton(onClick = { viewModel.dismissNotice() }) { Text("知道了") }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("确认作废解不开的旧数据？") },
            text = {
                Text(
                    "这会删掉本机上的聊天与记忆密文文件、清掉解不开的 API Key，并关闭常驻加密。" +
                        "删掉的内容无法找回。要继续请先在系统面板做一次导出——但注意：解密导出同样依赖这把钥，" +
                        "密钥已丢失的机器导不出这份内容。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    viewModel.discardEncryptedData()
                }) { Text("确认作废") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("先不动") }
            }
        )
    }
}

private fun directionLabel(direction: com.meapet.mobile.security.MigrationDirection): String =
    if (direction == com.meapet.mobile.security.MigrationDirection.ENABLE) "开启（明文→密文）" else "还原（密文→明文）"

@Composable
private fun Body(text: String, alpha: Float = 1f) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
