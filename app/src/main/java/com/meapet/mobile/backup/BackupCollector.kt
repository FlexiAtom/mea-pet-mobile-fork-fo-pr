package com.meapet.mobile.backup

import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * 导出采集的四路源。由装配层（AppContainer/设置页）填，采集层不认识任何 Manager 类型——
 * 这样这一层能在 JVM 单测里跑（不依赖 Android）。缺位的源传 null，对应类别不进包。
 */
data class BackupSources(
    val settingsPrefs: Preferences? = null,
    val chatFile: File? = null,
    val memoryFile: File? = null,
    val wallpaperFile: File? = null,
)

/**
 * 选中的类别 → 待写出的条目列表。
 *
 * 口径：聊天/记忆**原文透传**（不重新序列化——重新编码会把当时字节换成当前模型
 * 能表达的形态，等于偷偷做迁移）；壁纸按**磁盘现名**入包（人工裁决 2026-10-20），
 * 现名先安全化再进 zipPath，写出层还会再兜一道越界检查。
 */
object BackupCollector {

    /** 磁盘现名安全化：只留路径最后一段，字符集收窄，长度截断，空名给兜底。 */
    fun sanitizeWallpaperName(diskName: String): String {
        val leaf = diskName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf.replace(Regex("""[^A-Za-z0-9._-]"""), "_").trimStart('.')
        return if (cleaned.isBlank()) "wallpaper.jpg" else cleaned.take(96)
    }

    fun collect(selected: Set<BackupCategory>, sources: BackupSources): List<BackupItem> {
        val items = mutableListOf<BackupItem>()
        if (BackupCategory.SETTINGS in selected) {
            val prefs = sources.settingsPrefs
            if (prefs != null) {
                items += BackupItem.Text(
                    BackupExporter.pathFor(BackupCategory.SETTINGS),
                    SettingsWhitelist.encodeFrom(prefs)
                )
            }
        }
        if (BackupCategory.CHAT in selected) {
            sources.chatFile?.backupOrNull()?.let {
                items += BackupItem.Text(BackupExporter.pathFor(BackupCategory.CHAT), it.readText())
            }
        }
        if (BackupCategory.MEMORY in selected) {
            sources.memoryFile?.backupOrNull()?.let {
                items += BackupItem.Text(BackupExporter.pathFor(BackupCategory.MEMORY), it.readText())
            }
        }
        if (BackupCategory.WALLPAPER in selected) {
            sources.wallpaperFile?.backupOrNull()?.let {
                items += BackupItem.FileSource(
                    BackupExporter.wallpaperEntryPath(sanitizeWallpaperName(it.name)), it
                )
            }
        }
        return items
    }

    /** 读侧统一口径：不可读（不存在/不是文件/零字节损坏）就当这一类没有内容。 */
    private fun File.backupOrNull(): File? = takeIf { it.isFile && it.length() > 0L }

    /**
     * 哪些类别**现在**有东西可导：只按存在性判，不读正文、不加密——
     * 给设置页那四行勾选做"本机这一类还没有东西"的提示用，判据与 [collect] 一致。
     */
    fun availableCategories(sources: BackupSources): Set<BackupCategory> {
        val available = mutableSetOf<BackupCategory>()
        if (sources.settingsPrefs != null) available += BackupCategory.SETTINGS
        if (sources.chatFile?.backupOrNull() != null) available += BackupCategory.CHAT
        if (sources.memoryFile?.backupOrNull() != null) available += BackupCategory.MEMORY
        if (sources.wallpaperFile?.backupOrNull() != null) available += BackupCategory.WALLPAPER
        return available
    }
}
