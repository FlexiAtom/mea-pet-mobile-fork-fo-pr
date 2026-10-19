package com.meapet.mobile.backup

import com.meapet.mobile.chat.ConversationManager
import com.meapet.mobile.chat.ConversationStore
import com.meapet.mobile.core.WallpaperStore
import com.meapet.mobile.memory.MemoryRepository
import com.meapet.mobile.settings.SettingsManager
import java.io.File

/**
 * 把导入条目落到真实存储的装配实现。依赖**具体对象注入**而非 AppContainer，
 * 这样 backup 包不认识 app 包，且单测可以只喂它需要的两三个依赖。
 *
 * 每类落地都自带撤销：
 * - 设置：editForImport 同一次调用里拿到"事务前快照"，撤销按快照逐键回写
 *   （原本不存在的键是**删掉**，不是写空值）。撤销后照样热刷一次。
 * - 聊天/记忆：先解得动才换内存与磁盘；解不动直接抛，现状一点不碰。
 *   撤销用替换前真正在用的那份内容，而不是"清空"。
 * - 壁纸：旧那张先整份存进 [stashDir] 再让新的一张进目录（installLocally 会把目录
 *   清成恒一张，等于换下来的旧图当场就没了，所以必须先留副本）。撤销是副本装回。
 */
class AppBackupApplier(
    private val settings: SettingsManager,
    private val conversationStore: ConversationStore,
    private val conversationManager: ConversationManager,
    private val memory: MemoryRepository,
    private val wallpaper: WallpaperStore,
    private val stashDir: File,
    /** 设置类热刷钩子（装配层传 AppContainer::reloadClient；导入不需要它时会误留旧 Base URL）。 */
    private val onSettingsChanged: () -> Unit = {},
) : BackupApplier {

    override suspend fun applySettings(json: String): BackupUndo? {
        val patch = SettingsWhitelist.decodeTo(json)
        if (patch.asMap().isEmpty()) return null
        val before = settings.editForImport { SettingsWhitelist.writeInto(it, patch) }
        onSettingsChanged()
        val touched = patch.asMap().keys.toList()
        return suspend {
            settings.editForImport { target ->
                touched.forEach { key -> SettingsWhitelist.writeOne(target, key, before[key]) }
            }
            onSettingsChanged()
        }
    }

    override suspend fun applyChat(text: String): BackupUndo? {
        val parsed = conversationStore.parseSnapshot(text)
            ?: throw BackupImportException("会话内容不可解析，拒绝回灌")
        val before = conversationManager.getMessages()
        conversationManager.replaceAll(parsed)
        return suspend { conversationManager.replaceAll(before) }
    }

    override suspend fun applyMemory(text: String): BackupUndo? {
        val before = memory.replaceWith(text)
            ?: throw BackupImportException("记忆内容不可解析，拒绝回灌")
        return suspend { memory.restoreAll(before) }
    }

    override suspend fun applyWallpaper(fileName: String, source: File): BackupUndo? {
        val oldFile = wallpaper.backupFileFor(settings.getWallpaperPath())
        val stash = oldFile?.let { prev ->
            File(stashDir, "wallpaper_prev_${prev.name}").also {
                prev.copyTo(it, overwrite = true)
            }
        }
        val newPath = wallpaper.installLocally(source, BackupCollector.sanitizeWallpaperName(fileName))
            ?: run {
                stash?.delete()
                throw BackupImportException("壁纸落地失败: $fileName")
            }
        settings.setWallpaperPath(newPath)
        val landed = File(newPath)
        return suspend {
            landed.delete()
            val restored = stash?.takeIf { it.isFile }?.let {
                wallpaper.installLocally(it, it.name.removePrefix("wallpaper_prev_"))
            }
            settings.setWallpaperPath(restored ?: "")
            stash?.delete()
        }
    }
}
