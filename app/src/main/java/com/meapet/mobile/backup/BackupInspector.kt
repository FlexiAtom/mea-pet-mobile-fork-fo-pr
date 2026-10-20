package com.meapet.mobile.backup

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** 校验通过的包内容：manifest + 按包内路径可取的条目头。 */
data class BackupContents(
    val manifest: BackupManifest,
    val createdAt: String,
    /** 类别 → (包内路径, 条目头)。一个类别只允许出现一次。 */
    val perCategory: Map<BackupCategory, Pair<String, FileHeader>>,
) {
    val categories: Set<BackupCategory> get() = perCategory.keys
}

/**
 * 读包 + 两级版本校验（`format` 与逐条目 `schema` 都是**不等即拒**，零迁移口径）。
 *
 * 口令在这里**不清零**——UI 侧一次输入要跨"校验→落地"两步用，清在它自己收尾。
 * 读侧用 `ZipFile`（不是 ZipInputStream）：SAF 的 Uri 流不可随机重读，所以先
 * 整份暂存到 cacheDir 再按中央目录读，这正是本案"cacheDir 暂存"那一步的用途。
 */
object BackupInspector {

    /**
     * 单条目**解压后**的上限。
     *
     * 暂存的 128 MiB 只卡住压缩体积：deflate 极限约 1000:1，manifest 的 `bytes` 与
     * zip 头里的 `uncompressedSize` 又都是包方自报（两者对账只保证它们互相一致）。
     * 所以闸必须按**实际读到的字节**下——否则一句 `readBytes()` 就把堆撑爆，
     * 而包是用户从任意位置选来的外部输入。
     */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    private const val COPY_BUFFER_BYTES = 8 * 1024

    private fun oversize(): Nothing = throw ManifestRejectException(
        "条目解压后超过 ${MAX_ENTRY_BYTES / 1024 / 1024} MiB 上限，拒绝"
    )

    /** 边读边计数，越界即抛（调用方的中间体清理由 [BackupImporter] 的 finally 负责）。 */
    private fun InputStream.readBounded(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) oversize()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun InputStream.copyBoundedTo(target: File) {
        target.outputStream().use { sink ->
            val buf = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val n = read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_ENTRY_BYTES) oversize()
                sink.write(buf, 0, n)
            }
        }
    }

    fun inspect(stageFile: File, password: CharArray): BackupContents {
        ZipFile(stageFile, password).use { zip ->
            val headers: List<FileHeader> = zip.fileHeaders
            val byPath = headers.associateBy { it.fileName }

            val manifestHeader = byPath[BackupExporter.MANIFEST_ENTRY]
                ?: throw ManifestRejectException("缺 manifest.json")
            val manifest = BackupFormat.decode(zip.readEntryText(manifestHeader))

            val perCategory = mutableMapOf<BackupCategory, Pair<String, FileHeader>>()
            headers.forEach { header ->
                if (header.fileName == BackupExporter.MANIFEST_ENTRY) return@forEach
                val path = header.fileName
                val category = BackupCategory.fromPath(path)
                    ?: throw ManifestRejectException("包内未知路径: $path")
                val entry = manifest.entries.firstOrNull { it.path == path }
                    ?: throw ManifestRejectException("manifest 未登记该条目: $path")
                if (!BackupFormat.entrySupported(entry)) {
                    throw ManifestRejectException(
                        "条目 ${entry.name} schema=${entry.schema} 不支持" +
                            "（支持值 ${BackupFormat.SUPPORTED_SCHEMA[entry.name]}）"
                    )
                }
                if (perCategory.containsKey(category)) {
                    throw ManifestRejectException("同类别重复条目: $path")
                }
                // bytes 只做合理性对账（不当校验和用，完整性由解密失败兜）
                if (entry.bytes != header.uncompressedSize) {
                    throw ManifestRejectException(
                        "条目 ${entry.name} 字节数不符: manifest=${entry.bytes} 实际=${header.uncompressedSize}"
                    )
                }
                perCategory[category] = path to header
            }
            return BackupContents(manifest, manifest.createdAt, perCategory.toMap())
        }
    }

    /** 选定类别 → 内容文本；未选的类别不解密。 */
    fun readText(contents: BackupContents, category: BackupCategory, stageFile: File, password: CharArray): String? {
        val pair = contents.perCategory[category] ?: return null
        return ZipFile(stageFile, password).use { zip -> zip.readEntryText(zip.getFileHeader(pair.first)) }
    }

    /** 选定类别 → 把条目解出到目标文件（壁纸这类二进制不走内存）。 */
    fun extractTo(
        contents: BackupContents,
        category: BackupCategory,
        stageFile: File,
        password: CharArray,
        target: File,
    ): Boolean {
        val pair = contents.perCategory[category] ?: return false
        ZipFile(stageFile, password).use { zip ->
            zip.getInputStream(zip.getFileHeader(pair.first)).use { input ->
                input.copyBoundedTo(target)
            }
        }
        return true
    }

    private fun ZipFile.readEntryText(header: FileHeader): String =
        getInputStream(header).use { it.readBounded().toString(Charsets.UTF_8) }
}
