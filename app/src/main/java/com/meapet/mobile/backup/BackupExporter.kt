package com.meapet.mobile.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.OutputStream

/**
 * 导出源条目：文本（设置 JSON/聊天/记忆原文透传）或文件（壁纸二进制）。
 * `zipPath` = 包内实际路径；采集层负责壁纸"磁盘现名"的安全化，写出层拒收越界路径。
 */
sealed interface BackupItem {
    val zipPath: String

    data class Text(override val zipPath: String, val content: String) : BackupItem
    data class FileSource(override val zipPath: String, val file: File) : BackupItem
}

/**
 * 导出包写出流：逐条目 zip4j AES-256 流式写入 + manifest 收尾补写。
 *
 * 明文不落盘——文本条目在内存、文件条目直读源文件进加密流；目标 = 调用方给的
 * 任意 OutputStream（真机是 SAF CreateDocument 的 Uri 流，单测是临时文件）。
 * 未选类别不进包（manifest 只登记实际写出的条目）。
 *
 * API 实态（javap 2.11.5 逐格实证，勿凭 java.util.zip 直觉改）：
 * - `ZipFile` 构造器只有 String/File 两族，**没有 OutputStream 构造器**；
 *   往任意流写 zip 的载体是 `io.outputstream.ZipOutputStream(OutputStream, char[])`，
 *   逐条目 `putNextEntry(ZipParameters)` → `write(byte[])` → `closeEntry()`。
 * - `ZipOutputStream.close()` 会连带 close 传入的 sink（字节码：close →
 *   CountingOutputStream.close → OutputStream.close），故这里不额外关 sink。
 * - `ZipParameters` 是纯 Java bean：只有 setXxx/getXxx，**无 `isCompressed`**
 *   （那是 java.util.zip 的语义）；压缩与否只由 `setCompressionMethod` 决定。
 * - 口令统一在 ZipOutputStream 构造上给，条目侧只声明加密方式与强度。
 */
object BackupExporter {

    const val MANIFEST_ENTRY = "manifest.json"

    fun pathFor(category: BackupCategory): String = "files/${category.entryName}"

    fun wallpaperEntryPath(diskFileName: String): String = "files/wallpaper/$diskFileName"

    fun defaultFileName(utcCompact: String): String = "${utcCompact}_MeaPet-Android.zip"

    /**
     * 包内路径红线：必须在 files/ 下、非绝对、按**路径段**判越界。
     * 段判而不是 `contains("..")`：后者会误杀 `wallpaper..2.jpg` 这类合法现名，
     * 而真正的越界需要的是独立的 `.`/`..`/空段。
     */
    private fun requireSafePath(path: String) {
        val segments = path.split('/')
        require(
            path.startsWith("files/") && !path.startsWith("/") &&
                segments.none { it == ".." || it == "." || it.isBlank() }
        ) { "包内路径越界: $path" }
        require(BackupCategory.fromPath(path) != null) { "未登记的包内路径: $path" }
    }

    private fun aesParams(path: String, size: Long, store: Boolean) = ZipParameters().apply {
        setEncryptFiles(true)
        setEncryptionMethod(EncryptionMethod.AES)
        setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
        setFileNameInZip(path)
        setEntrySize(size)
        setCompressionMethod(if (store) CompressionMethod.STORE else CompressionMethod.DEFLATE)
        setOverrideExistingFilesInZip(true)
    }

    suspend fun writeBackup(
        sink: OutputStream,
        password: CharArray,
        items: List<BackupItem>,
        createdAt: String,
    ): Long = withContext(Dispatchers.IO) {
        val entries = mutableListOf<ManifestEntry>()
        var total = 0L
        net.lingala.zip4j.io.outputstream.ZipOutputStream(sink, password).use { zip ->
            items.forEach { item ->
                val path = item.zipPath
                requireSafePath(path)
                val textBytes = (item as? BackupItem.Text)?.content?.toByteArray(Charsets.UTF_8)
                val size = textBytes?.size?.toLong()
                    ?: (item as BackupItem.FileSource).file.length()
                zip.putNextEntry(aesParams(path, size, store = item is BackupItem.FileSource))
                if (textBytes != null) {
                    zip.write(textBytes)
                } else {
                    (item as BackupItem.FileSource).file.inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
                val category = BackupCategory.fromPath(path)!!
                entries += ManifestEntry(category.entryName, schema = 1, bytes = size, path = path)
                total += size
            }
            val manifest = BackupManifest(
                format = BackupFormat.SUPPORTED_FORMAT,
                createdAt = createdAt,
                entries = entries,
            )
            val manifestBytes = BackupFormat.encode(manifest).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(aesParams(MANIFEST_ENTRY, manifestBytes.size.toLong(), store = false))
            zip.write(manifestBytes)
            zip.closeEntry()
        }
        password.fill('\u0000')
        total
    }
}
