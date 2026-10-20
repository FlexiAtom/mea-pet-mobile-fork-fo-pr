package com.meapet.mobile.backup

import java.io.File

/** 一次落地的撤销动作（null = 该类原本无内容，撤销即"清成没有"由实现侧自己决定）。 */
typealias BackupUndo = suspend () -> Unit

/**
 * 落地动作由装配层实现——导入核心只认这个接口，不认 SettingsManager/各 Store，
 * 这样"校验→回灌→失败整体回滚"的编排在 JVM 单测里能真跑（假 applier 注入失败点）。
 *
 * 返回非 null 即表示已改动现状、需要在失败时被撤销。
 */
interface BackupApplier {
    suspend fun applySettings(json: String): BackupUndo?
    suspend fun applyChat(text: String): BackupUndo?
    suspend fun applyMemory(text: String): BackupUndo?

    /** 壁纸按包内记录的现名落地；实现侧决定最终路径与生效方式。 */
    suspend fun applyWallpaper(fileName: String, source: File): BackupUndo?
}

/** 导入结果：实际回灌的类别与字节数（UI 文案用）。 */
data class BackupImportSummary(
    val applied: Set<BackupCategory>,
    val bytes: Long,
    val createdAt: String,
)

class BackupImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 导入编排：**先全量校验、后逐类回灌、任一步抛就按 applied 逆序整体回滚**。
 *
 * 校验阶段不碰现状（inspect 只读包），所以"包本身不合格"根本走不到回滚那一步；
 * 回滚只覆盖回灌中途失败（磁盘满、权限、第三方文件坏）。回滚动作自己也失败时
 * 不再继续回滚其余类别——那需要人介入，异常链里把两边都带上。
 */
class BackupImporter(private val applier: BackupApplier) {

    /** 固定顺序：设置最先（它决定后续热刷读到什么），壁纸最后（唯一动文件的）。 */
    private val order = listOf(
        BackupCategory.SETTINGS,
        BackupCategory.MEMORY,
        BackupCategory.CHAT,
        BackupCategory.WALLPAPER,
    )

    suspend fun import(
        stageFile: File,
        password: CharArray,
        selected: Set<BackupCategory>,
    ): BackupImportSummary {
        val contents = BackupInspector.inspect(stageFile, password)
        val plan = order.filter { it in selected && contents.perCategory.containsKey(it) }
        val undos = mutableListOf<Pair<BackupCategory, BackupUndo>>()
        val tempFiles = mutableListOf<File>()
        var bytes = 0L
        try {
            plan.forEach { category ->
                val (path, header) = contents.perCategory.getValue(category)
                val undo = when (category) {
                    BackupCategory.SETTINGS ->
                        applier.applySettings(BackupInspector.readText(contents, category, stageFile, password)!!)
                    BackupCategory.CHAT ->
                        applier.applyChat(BackupInspector.readText(contents, category, stageFile, password)!!)
                    BackupCategory.MEMORY ->
                        applier.applyMemory(BackupInspector.readText(contents, category, stageFile, password)!!)
                    BackupCategory.WALLPAPER -> {
                        val name = path.substringAfterLast('/')
                        val out = File(stageFile.parentFile, "wallpaper_in_$name")
                        // 先登记再解：解到一半失败（超限、写盘错）也要被 finally 收走
                        tempFiles += out
                        if (!BackupInspector.extractTo(contents, category, stageFile, password, out)) {
                            throw BackupImportException("壁纸条目解出失败: $path")
                        }
                        applier.applyWallpaper(name, out)
                    }
                }
                if (undo != null) undos += category to undo
                bytes += header.uncompressedSize
            }
        } catch (t: Throwable) {
            val rollbackError = runRollback(undos)
            throw BackupImportException(
                "导入失败，已尝试回滚 ${undos.size} 个类别",
                if (rollbackError != null) t.apply { addSuppressed(rollbackError) } else t
            )
        } finally {
            // 解出来的壁纸中间体只服务于这一次落地（成功=实现侧已搬走，失败=已回滚）
            tempFiles.forEach { runCatching { it.delete() } }
        }
        return BackupImportSummary(plan.toSet(), bytes, contents.createdAt)
    }

    private suspend fun runRollback(undos: List<Pair<BackupCategory, BackupUndo>>): Throwable? {
        var first: Throwable? = null
        undos.asReversed().forEach { (_, undo) ->
            runCatching { undo() }.onFailure { if (first == null) first = it }
        }
        return first
    }
}
