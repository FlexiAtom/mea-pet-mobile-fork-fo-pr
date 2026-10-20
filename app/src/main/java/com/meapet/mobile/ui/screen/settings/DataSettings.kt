package com.meapet.mobile.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meapet.mobile.R
import com.meapet.mobile.backup.BackupCategory
import com.meapet.mobile.backup.BackupExporter
import com.meapet.mobile.backup.BackupService
import com.meapet.mobile.backup.SettingsWhitelist
import com.meapet.mobile.viewmodel.BackupUiState
import com.meapet.mobile.viewmodel.BackupViewModel

/** 短于这个长度只提示、不拦（人工口径：口令由用户定，弱口令的代价由用户承担）。 */
private const val WEAK_PASSWORD_CHARS = 8

/**
 * 「数据与备份」页：导出（选类别 + 定口令 + SAF 创建文档）与导入（选包 → 校验 → 勾选 → 强制确认）。
 *
 * 两节各做成可展开栏（人工裁决 2026-10-20：满屏信息读不动）——导出是主路径所以默认展开，
 * 导入默认收起，但**校验一出结果就自动展开**：收起着看不见解析清单等于没有反馈。
 * 回执两节共用一格、常驻在折叠块之外，所以任何一侧的回执都不会被折叠藏起来。
 *
 * 口令只活在本页的局部状态里，不进 ViewModel 的状态格、不落盘；两次输入用于防手滑，
 * 但**不提供任何找回途径**——忘了就是密文打不开，这句话在告知弹窗里写明。
 *
 * 导出侧首次点按会先看到一次「包里有什么」的告知（关掉即继续走系统保存面板，
 * 不额外拦一次路）；导入侧的确认框相反，它是**真闸**——覆盖在用数据必须点名确认。
 */
@Composable
internal fun DataSection(viewModel: BackupViewModel) {
    val state by viewModel.state.collectAsState()

    var exportPwd by remember { mutableStateOf("") }
    var exportPwdAgain by remember { mutableStateOf("") }
    var importPwd by remember { mutableStateOf("") }
    var showContentNotice by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    /** 告知弹窗关掉的那一下才开系统保存面板（首次按导出不直接跳走）。 */
    var exportAfterNotice by remember { mutableStateOf(false) }
    var exportExpanded by remember { mutableStateOf(true) }
    var importExpanded by remember { mutableStateOf(false) }

    val exportFileName = BackupExporter.defaultFileName(BackupService.utcCompactNow())

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument()
    ) { uri ->
        if (uri != null) viewModel.runExport(uri, exportPwd) else viewModel.report("已取消，没有写出任何文件")
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) viewModel.report("已取消，没有选择导出包")
        else viewModel.inspectPackage(uri, importPwd)
    }

    LaunchedEffect(state.stagedName) {
        if (state.stagedName != null) importExpanded = true
    }

    // ── 导出 ────────────────────────────────────────
    CollapsibleSection(
        title = "导出",
        summary = exportSummary(state),
        expanded = exportExpanded,
        onToggle = { exportExpanded = !exportExpanded }
    ) {
        Text(
            "勾出来的内容写进一个 AES-256 加密的 zip，交给你在系统面板里选的位置；全程不经过网络。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Column(Modifier.fillMaxWidth()) {
            BackupCategory.values().forEach { category ->
                CategoryRow(
                    label = category.label(),
                    detail = if (category in state.available) "有内容" else "本机这一类还没有东西",
                    checked = category in state.exportSelection,
                    enabled = !state.busy,
                    onCheckedChange = { on -> viewModel.toggleExport(category, on) }
                )
            }
        }
        OutlinedTextField(
            value = exportPwd,
            onValueChange = { exportPwd = it },
            label = { Text("导出口令") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            supportingText = {
                Text(
                    when {
                        exportPwd.isEmpty() -> "口令是你唯一的密钥，忘了没有任何找回途径"
                        exportPwd.length < WEAK_PASSWORD_CHARS -> "少于 $WEAK_PASSWORD_CHARS 位更容易被反复尝试（不拦你）"
                        else -> "长度 ${exportPwd.length} 位"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = exportPwdAgain,
            onValueChange = { exportPwdAgain = it },
            label = { Text("再输一次口令") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            isError = exportPwdAgain.isNotEmpty() && exportPwdAgain != exportPwd,
            supportingText = {
                if (exportPwdAgain.isNotEmpty() && exportPwdAgain != exportPwd) {
                    Text("两次不一致，这样写出来的包会打不开", style = MaterialTheme.typography.bodySmall)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = !state.busy,
            onClick = {
                when {
                    exportPwd.isEmpty() ->
                        viewModel.report("先设一个口令——没口令的包等于没保护")
                    exportPwd != exportPwdAgain ->
                        viewModel.report("两次输入的口令不一致，先核对一下")
                    state.exportSelection.isEmpty() ->
                        viewModel.report("一个类别都没勾，包里不会有任何东西")
                    !state.backupNoticeAcked -> {
                        viewModel.acknowledgeBackupNotice()
                        exportAfterNotice = true
                        showContentNotice = true
                    }
                    else -> exportLauncher.launch(exportFileName)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(if (state.busy) "正在处理…" else "选择保存位置并写出")
        }
    }

    // ── 导入 ────────────────────────────────────────
    CollapsibleSection(
        title = "导入",
        summary = importSummary(state),
        expanded = importExpanded,
        onToggle = { importExpanded = !importExpanded }
    ) {
        OutlinedTextField(
            value = importPwd,
            onValueChange = { importPwd = it },
            label = { Text("导出包口令") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            supportingText = {
                Text("先填口令再选包：口令不对的包在解析阶段就会被拒，不碰现有数据", style = MaterialTheme.typography.bodySmall)
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = !state.busy,
            onClick = {
                // zip 的 MIME 各家 provider 报法不一，octet-stream 是通用兜底；
                // 真选错了文件也不要紧——解析这一步按 manifest 与路径段拒。
                importLauncher.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(if (state.stagedName == null) "选择导出包并校验" else "换一个导出包")
        }

        if (state.stagedName != null) {
            Text(
                "导出时间：" + (state.stagedCreatedAt ?: "包内未记录"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            state.stagedRows.forEach { row ->
                CategoryRow(
                    label = row.category.label(),
                    detail = "包内 ${formatBytes(row.bytes)}",
                    checked = row.category in state.importSelection,
                    enabled = !state.busy,
                    onCheckedChange = { on -> viewModel.toggleImport(row.category, on) }
                )
            }
            OutlinedButton(
                enabled = !state.busy && state.importSelection.isNotEmpty(),
                onClick = { showImportConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("导入勾选的 ${state.importSelection.size} 类（覆盖当前）")
            }
            OutlinedButton(
                enabled = !state.busy,
                onClick = { viewModel.clearStaged() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text("放弃这个包")
            }
        }
    }

    // ── 回执（两节共用，不随折叠隐藏）────────────────
    state.notice?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clickable { viewModel.dismissNotice() }
        )
    }

    if (showContentNotice) {
        InfoNoticeDialog(
            title = "导出包里有什么",
            text = "· 进包的：你勾选的类别。设置类只带 ${SettingsWhitelist.KEY_COUNT} 项偏好" +
                "（API 地址、模型名、温度、主题、语音开关等）；\n" +
                "· 不进包的：API Key、各类隐私授权与同意标记、开发者选项——" +
                "换机后 Key 要重新填一次，这是有意的；\n" +
                "· 对话与记忆按当时磁盘上的原文入包，不做任何格式转换；" +
                "壁纸按当前生效的那张原图入包，保留原文件名；\n" +
                "· 保护方式是 AES-256，口令就是你唯一的密钥：这个应用不提供任何找回途径，" +
                "口令写错或忘记，包就永久打不开；\n" +
                "· 文件写到你选的位置，全程不经过网络。"
        ) {
            showContentNotice = false
            if (exportAfterNotice) {
                exportAfterNotice = false
                exportLauncher.launch(exportFileName)
            }
        }
    }

    if (showImportConfirm) {
        val names = BackupCategory.values()
            .filter { it in state.importSelection }
            .joinToString("、") { it.label() }
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("确认导入并覆盖") },
            text = {
                Text(
                    "接下来会用包里的内容覆盖这台机器上的：$names。\n" +
                        "过程任一步失败会尝试整体回滚，但回滚不保证一定成功；" +
                        "如果现在的对话与记忆很重要，建议先做一次导出再导入。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    viewModel.runImport(importPwd)
                }) {
                    Text("确认覆盖导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text("先不导入")
                }
            }
        )
    }
}

private fun exportSummary(state: BackupUiState): String {
    val selected = state.exportSelection.size
    val withContent = state.exportSelection.count { it in state.available }
    return if (selected == 0) "没勾任何类别" else "已勾 $selected 类，其中有内容 $withContent 类"
}

private fun importSummary(state: BackupUiState): String = state.stagedName
    ?.let { "已解析 $it（${state.stagedRows.size} 类）" }
    ?: "未选择导出包"

private fun BackupCategory.label(): String = when (this) {
    BackupCategory.SETTINGS -> "设置"
    BackupCategory.MEMORY -> "记忆"
    BackupCategory.CHAT -> "对话"
    BackupCategory.WALLPAPER -> "壁纸"
}

/** 可展开栏的标题行：标题 + 当前状态摘要 + 展开箭头（收起时也读得懂这一节现在是什么情形）。 */
@Composable
private fun CollapsibleSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT)
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = if (expanded) "收起$title" else "展开$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_FAINT_TEXT),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 90f else 0f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/**
 * 类别勾选行：不用 Switch——这是"多选"而不是"开/关"，
 * 且导出页的四行里有一行本机没有东西，Switch 灰着读起来像坏掉的设置项。
 */
@Composable
private fun CategoryRow(
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT)
            )
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / 1024 / 1024} MiB"
    bytes >= 1024 -> "${bytes / 1024} KiB"
    else -> "$bytes B"
}
