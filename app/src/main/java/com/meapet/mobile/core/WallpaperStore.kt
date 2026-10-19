package com.meapet.mobile.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 主界面背景壁纸的导入与清理（纯 IO，不涉及 GL）。
 *
 * 相册 PhotoPicker 返回的是 `content://` URI，其读取权限是进程级**临时**授权
 * （跨进程重启后可能失效），因此选图回调里必须**立即**把图片复制进
 * `filesDir/wallpaper/`，设置只存该文件的绝对路径。
 *
 * 导入时做两件事：
 * - **EXIF 旋转归一化**——相册照片常带 orientation tag，这里读出来后用
 *   [Matrix.postRotate] 转正，再重编码落盘（去掉元数据，GL 端无需再处理方向）；
 * - **下采样到 maxDim ≤ 2048**——控制磁盘体积与后续 GL 线程解码量，防 OOM。
 *
 * 落盘用 JPEG（照片转 PNG 会膨胀到数 MB，JPEG 约几百 KB）；`BitmapFactory`
 * 按**内容**识别格式而非扩展名，扩展名用 `.jpg` 即可。
 */
class WallpaperStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "wallpaper").apply { mkdirs() }

    /**
     * 导出用：把设置里的壁纸路径解析成**本目录内**的现有文件，否则 null。
     *
     * 目录外一律不导——`wallpaper_path` 是设备本地路径且不入白名单，若指到别处
     * （手工改过、或旧版本残留），把任意文件打进导出包不是本案要的行为。
     * 这里不碰 [dir]（它的 getter 带 mkdirs 副作用，读侧不该建目录）。
     */
    fun backupFileFor(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val root = runCatching { File(context.filesDir, "wallpaper").canonicalPath }.getOrNull()
            ?: return null
        return if (target.parentFile?.canonicalPath == root && target.isFile) target else null
    }

    /** 导入后的最大边长（px），限制解码内存与 GL 上传量。 */
    private val maxDimension = 2048

    /**
     * 把相册图片导入为本地壁纸文件，返回绝对路径；失败返回 null。
     *
     * 成功后删除目录内其余旧文件（只保留最新一张）。
     */
    suspend fun importFromContentUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        if (resolver == null) return@withContext null

        // 1) 只读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        // 2) 读 EXIF 旋转方向（android.media.ExifInterface 支持 InputStream，API 24+，minSdk 26 满足）
        val orientation = resolver.openInputStream(uri)?.use {
            runCatching {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        // 3) 采样到 maxDim ≤ 2048
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return@withContext null

        // 4) 应用 EXIF 旋转 → 重编码落盘
        val rotated = rotate(decoded, orientation)
        if (rotated !== decoded) decoded.recycle()
        val out = File(dir, "wallpaper_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(out).use { rotated.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (_: Exception) {
            out.delete()
            rotated.recycle()
            return@withContext null
        }
        rotated.recycle()

        // 5) 清理旧文件（只保留当前这张）
        dir.listFiles()?.filter { it != out && it.isFile }?.forEach { it.delete() }

        out.absolutePath
    }

    /** 恢复默认：清空壁纸目录。 */
    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 导入用：把**已在本地**的文件搬进壁纸目录并保留给定文件名，返回绝对路径。
     *
     * 不走 [importFromContentUri] 的重编码管道：包里那张是本应用自己产出的 JPEG，
     * 再解码压缩一遍只会让两次往返各掉一次质量；导入要的是字节等值。
     * 保留现名是人工裁决 2026-10-20（多条壁纸条目按 `<name>` 各自的磁盘名入/出包）。
     * "目录恒一张"在这里继续成立：新文件站稳后才删其余（含被换下来的旧那张）。
     */
    suspend fun installLocally(source: File, diskName: String): String? = withContext(Dispatchers.IO) {
        // 只取最后一段兜底防越界；字符集安全化由调用侧（BackupCollector.sanitizeWallpaperName）做
        val leaf = diskName.substringAfterLast('/').substringAfterLast('\\')
        val out = File(dir, leaf)
        if (out.absolutePath == source.absolutePath) return@withContext out.absolutePath
        runCatching { source.copyTo(out, overwrite = true) }.getOrNull() ?: return@withContext null
        dir.listFiles()?.filter { it.absolutePath != out.absolutePath && it.isFile }?.forEach { it.delete() }
        out.absolutePath
    }

    private fun rotate(src: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0f  // 镜像翻转场景罕见，仅旋转处理
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 0f
            ExifInterface.ORIENTATION_TRANSPOSE -> 90f
            ExifInterface.ORIENTATION_TRANSVERSE -> 270f
            else -> return src
        }
        if (degrees == 0f) return src
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
