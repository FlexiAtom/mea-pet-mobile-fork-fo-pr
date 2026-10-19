package com.meapet.mobile.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.backup.BackupCategory
import com.meapet.mobile.backup.BackupCollector
import com.meapet.mobile.backup.BackupContents
import com.meapet.mobile.backup.BackupExporter
import com.meapet.mobile.backup.BackupImportException
import com.meapet.mobile.backup.BackupImporter
import com.meapet.mobile.backup.BackupInspector
import com.meapet.mobile.backup.BackupService
import com.meapet.mobile.backup.BackupSources
import com.meapet.mobile.backup.ManifestRejectException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 已选包的摘要行：类别、字节、包内路径（UI 只认这个，不认 zip4j 的条目头）。 */
data class StagedEntryRow(val category: BackupCategory, val bytes: Long, val path: String)

data class BackupUiState(
    /** 导出默认全勾——来这一页的前提就是"我要备份"。 */
    val exportSelection: Set<BackupCategory> = BackupCategory.values().toSet(),
    /** 导入默认全不勾——覆盖在用数据这件事必须逐项点名。 */
    val importSelection: Set<BackupCategory> = emptySet(),
    val busy: Boolean = false,
    val stagedName: String? = null,
    val stagedCreatedAt: String? = null,
    val stagedRows: List<StagedEntryRow> = emptyList(),
    /** 结果回执：成功与失败同一格，失败带原因但不承诺"数据一定找回"。 */
    val notice: String? = null,
)

/**
 * 「数据与备份」页 ViewModel。
 *
 * 口令的寿命限于一次调用：UI 传字符串进来，这里就地转 CharArray，不写进任何状态格、
 * 不进日志、不落盘。导出侧由写出层用完清零；导入侧用完即随作用域结束交给 GC。
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val container = MeaPetApplication.from(application)
    private val service: BackupService = container.backupService

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** 解析结果只在内存持有（暂存文件 + 校验后的内容）；导入成功或换文件即清。 */
    private var staged: Pair<File, BackupContents>? = null

    init {
        // 进页清扫：上次崩溃/被杀留下的暂存料
        service.sweepStaleStaging()
    }

    fun toggleExport(category: BackupCategory, on: Boolean) =
        _state.update { it.copy(exportSelection = it.exportSelection.with(category, on)) }

    fun toggleImport(category: BackupCategory, on: Boolean) =
        _state.update { it.copy(importSelection = it.importSelection.with(category, on)) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** 选完文件 + 输入口令 → 暂存并校验。不合格就地报错，不进入"可导入"态。 */
    fun inspectPackage(uri: Uri, password: String) {
        if (password.isEmpty()) {
            _state.update { it.copy(notice = "请先输入口令") }
            return
        }
        if (_state.value.busy) return
        clearStaged(notice = null)
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val pwd = password.toCharArray()
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val file = service.stageForRead(uri)
                    file to BackupInspector.inspect(file, pwd)
                }
            }
            outcome.onSuccess { (file, contents) ->
                staged = file to contents
                _state.update {
                    it.copy(
                        busy = false,
                        stagedName = service.displayName(uri) ?: file.name,
                        stagedCreatedAt = contents.createdAt,
                        stagedRows = contents.perCategory
                            .map { (category, pair) ->
                                StagedEntryRow(category, pair.second.uncompressedSize, pair.first)
                            }
                            .sortedBy { row -> row.category.ordinal },
                        importSelection = emptySet(),
                        notice = "已解析出 ${contents.perCategory.size} 类内容，勾选要导入的类别。",
                    )
                }
            }.onFailure { t ->
                runCatching { staged?.first?.delete() }
                staged = null
                _state.update {
                    it.copy(
                        busy = false,
                        stagedName = null,
                        stagedCreatedAt = null,
                        stagedRows = emptyList(),
                        notice = t.readableMessage(),
                    )
                }
            }
        }
    }

    fun clearStaged(notice: String? = "已清除所选文件") {
        staged?.let { (file, _) -> runCatching { file.delete() } }
        staged = null
        _state.update {
            it.copy(
                stagedName = null,
                stagedCreatedAt = null,
                stagedRows = emptyList(),
                importSelection = emptySet(),
                notice = notice,
            )
        }
    }

    /** 执行导入（调用方必须先过强制确认弹窗；导哪些类别由勾选集决定）。 */
    fun runImport(password: String) {
        val file = staged?.first ?: run {
            _state.update { it.copy(notice = "请先选择导出包") }
            return
        }
        val selected = _state.value.importSelection
        if (selected.isEmpty()) {
            _state.update { it.copy(notice = "没有勾选任何类别") }
            return
        }
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val pwd = password.toCharArray()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    BackupImporter(container.newBackupApplier(service.stagingDir))
                        .import(file, pwd, selected)
                }
            }
            result.onSuccess { summary ->
                // 暂存料（导入包体 + 换下来的旧图副本）只服务于这一次操作，成功即扫干净
                service.sweepStaleStaging()
                staged = null
                _state.update {
                    it.copy(
                        busy = false,
                        stagedName = null,
                        stagedCreatedAt = null,
                        stagedRows = emptyList(),
                        importSelection = emptySet(),
                        notice = "已导入 ${summary.applied.size} 类内容（共 ${summary.bytes} 字节），" +
                            "包内导出时间为 ${summary.createdAt}。",
                    )
                }
            }.onFailure { t ->
                // 失败时保留暂存与解析结果：回滚已经做完，改对口令可以直接重试
                _state.update {
                    it.copy(
                        busy = false,
                        notice = (if (t is BackupImportException) "" else "导入失败：") + t.readableMessage(),
                    )
                }
            }
        }
    }

    /** 执行导出：目标由 SAF「创建文档」给出，口令由 UI 收集并已二次确认。 */
    fun runExport(target: Uri, password: String) {
        if (_state.value.busy) return
        val selected = _state.value.exportSelection
        if (selected.isEmpty()) {
            _state.update { it.copy(notice = "没有勾选任何类别") }
            return
        }
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val settings = container.settingsManager
                    val sources = BackupSources(
                        settingsPrefs = settings.exportSnapshot(),
                        chatFile = container.conversationStore.backupFile(),
                        memoryFile = container.memoryRepository.backupFile(),
                        wallpaperFile = container.wallpaperStore.backupFileFor(settings.getWallpaperPath()),
                    )
                    val items = BackupCollector.collect(selected, sources)
                    if (items.isEmpty()) error("选中的类别在这台机器上都没有内容")
                    val pwd = password.toCharArray()
                    val bytes = service.openWriteSink(target).use { sink ->
                        BackupExporter.writeBackup(
                            sink = sink,
                            password = pwd,
                            items = items,
                            createdAt = BackupService.utcCompactNow(),
                        )
                    }
                    items.size to bytes
                }
            }
            result.onSuccess { (count, bytes) ->
                _state.update {
                    it.copy(busy = false, notice = "已导出 $count 项内容（$bytes 字节）")
                }
            }.onFailure { t ->
                _state.update { it.copy(busy = false, notice = t.readableMessage()) }
            }
        }
    }

    private fun Throwable.readableMessage(): String = when (this) {
        is ManifestRejectException, is BackupImportException -> message ?: "包校验未通过"
        else -> message ?: javaClass.simpleName
    }
}

private fun Set<BackupCategory>.with(category: BackupCategory, on: Boolean): Set<BackupCategory> =
    if (on) this + category else this - category
