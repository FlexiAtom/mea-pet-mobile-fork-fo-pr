package com.meapet.mobile.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 导出包 `manifest.json` 的两级整数版本协议（设计裁定见父案 app-data-export）。
 *
 * 零迁移口径：`format` 与逐条目 `schema` 都是"等于支持值才放行"——过旧、过新、
 * 未知类名一律拒，不做任何兼容读。曾候选的逐条目 sha256 已剪除（zip 口令本身
 * 完整性校验 + 解密失败即拒已覆盖该威胁面），仅留 `bytes` 供 UI 展示与合理性检查。
 */
@Serializable
data class ManifestEntry(
    val name: String,
    val schema: Int,
    val bytes: Long,
)

@Serializable
data class BackupManifest(
    val format: Int,
    val createdAt: String,
    val entries: List<ManifestEntry>,
)

class ManifestRejectException(message: String) : IllegalArgumentException(message)

object BackupManifestCodec {

    const val SUPPORTED_FORMAT = 1

    val SUPPORTED_SCHEMA: Map<String, Int> = mapOf(
        "settings" to 1,
        "memory" to 1,
        "chat" to 1,
        "wallpaper" to 1,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(manifest: BackupManifest): String = json.encodeToString(BackupManifest.serializer(), manifest)

    /** 解析并校验 `format`；条目级不在此判（未知/过新条目由调用方按 [entrySupported] 逐类拒绝）。 */
    fun decode(text: String): BackupManifest {
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), text)
        } catch (t: Throwable) {
            throw ManifestRejectException("manifest.json 无法解析: ${t.javaClass.simpleName}")
        }
        if (manifest.format != SUPPORTED_FORMAT) {
            throw ManifestRejectException("format 不支持: ${manifest.format}（零迁移，不等即拒）")
        }
        return manifest
    }

    fun entrySupported(entry: ManifestEntry): Boolean =
        SUPPORTED_SCHEMA[entry.name] == entry.schema

    fun entryCategory(name: String): BackupCategory? = BackupCategory.fromEntryName(name)
}

enum class BackupCategory(val entryName: String) {
    SETTINGS("settings"),
    MEMORY("memory"),
    CHAT("chat"),
    WALLPAPER("wallpaper"),
    ;

    companion object {
        fun fromEntryName(name: String): BackupCategory? =
            values().firstOrNull { it.entryName == name }
    }
}
