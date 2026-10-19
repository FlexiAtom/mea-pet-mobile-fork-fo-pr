package com.meapet.mobile.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * SAF 与导出/导入核心之间的薄服务层：暂存、取名、清扫、上限。
 *
 * 口令不在这里出现——它由 UI 收集、按次传给 exporter/importer，本层只管字节搬运。
 */
class BackupService(context: Context) {

    private val resolver = context.contentResolver

    /** 暂存目录：导入包体、解出的壁纸中间体、以及换图时旧图的临时副本都落在这里。 */
    val stagingDir: File = File(context.cacheDir, "backup").apply { mkdirs() }

    companion object {
        /** 暂存包上限：壁纸重编码后单张只有几百 KB，128 MiB 已经非常宽松，
         *  装的是"用户手选了一个超大文件"这一格——那是外部输入，必须设边界。 */
        const val MAX_PACKAGE_BYTES = 128L * 1024 * 1024

        private val UTC_COMPACT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)

        /** 导出默认名的时间戳部分（人工裁决的命名口径：<UTC紧凑>_MeaPet-Android.zip）。 */
        fun utcCompactNow(): String = UTC_COMPACT.format(Instant.now())

        fun isPlausiblePackageName(name: String): Boolean = name.lowercase().endsWith(".zip")
    }

    /** 把 SAF 选来的 zip 整份暂存到 cacheDir，返回本地文件（可随机重读，供中央目录解析）。 */
    fun stageForRead(uri: Uri): File {
        val size = querySize(uri)
        if (size != null && size > MAX_PACKAGE_BYTES) {
            throw ManifestRejectException(
                "待导入文件过大: ${size / 1024 / 1024} MiB（上限 ${MAX_PACKAGE_BYTES / 1024 / 1024} MiB）"
            )
        }
        val target = File(stagingDir, "in_${System.currentTimeMillis()}.zip")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开所选文件" }
            target.outputStream().use { input.copyTo(it) }
        }
        if (target.length() > MAX_PACKAGE_BYTES) {
            target.delete()
            throw ManifestRejectException("待导入文件超过上限（读取后才确认）")
        }
        return target
    }

    /** 打开 SAF「创建文档」给出的目标流（导出写这里，明文不落盘）。 */
    fun openWriteSink(uri: Uri) = resolver.openOutputStream(uri)
        ?: throw ManifestRejectException("无法写入所选位置")

    /**
     * 进页清扫：删掉暂存目录里剩下的东西。
     *
     * 导入的包体（含解密前的密文与解出的壁纸中间体）只在一次操作里有用；
     * 崩溃/被杀留下的残料下次进这一页就清掉。异常吞掉——清扫失败不该挡住页面打开，
     * cacheDir 本身也会被系统在空间不足时回收。
     */
    fun sweepStaleStaging() {
        runCatching { stagingDir.listFiles()?.forEach { it.delete() } }
    }

    /** 当前暂存占用（字节），UI 可用来提示"上次操作留了东西"。 */
    fun stagedBytes(): Long = stagingDir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * SAF Uri 的显示名。各家 provider 的 `lastPathSegment` 形态不一（有的是
     * `content/document/1234`），所以只认 DISPLAY_NAME，取不到就返回 null 由调用方兜。
     */
    fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()
}
