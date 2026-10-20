package com.meapet.mobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupFormatTest {

    @Test
    fun roundtrip_keepsTwoLevelVersionsBytesAndPaths() {
        val manifest = BackupManifest(
            format = BackupFormat.SUPPORTED_FORMAT,
            createdAt = "2026-10-20T023000Z",
            entries = listOf(
                ManifestEntry("chat", schema = 1, bytes = 1_048_576, path = "files/chat"),
                ManifestEntry("settings", schema = 1, bytes = 2_048, path = "files/settings"),
                ManifestEntry("wallpaper", schema = 1, bytes = 640, path = "files/wallpaper/wallpaper_123.jpg"),
            ),
        )
        val back = BackupFormat.decode(BackupFormat.encode(manifest))
        assertEquals(manifest, back)
        assertTrue(back.entries.all { BackupFormat.entrySupported(it) })
        assertEquals(1_048_576L, back.entries[0].bytes)
        assertEquals("files/wallpaper/wallpaper_123.jpg", back.entries[2].path)
    }

    @Test
    fun encodedText_hasNoSha256Field() {
        val text = BackupFormat.encode(
            BackupManifest(1, "t", listOf(ManifestEntry("memory", 1, 5, "files/memory")))
        )
        assertFalse("sha256 已剪除，编码结果不应带该字段", text.contains("sha256"))
    }

    @Test
    fun formatMismatch_rejectsWholePackage() {
        val text = BackupFormat.encode(BackupManifest(2, "t", emptyList()))
        try {
            BackupFormat.decode(text)
            fail("format=2 竟然被放行")
        } catch (expected: ManifestRejectException) {
            assertTrue(expected.message!!.contains("format 不支持: 2"))
        }
    }

    @Test
    fun entrySchemaTooNew_orUnknownName_rejectsThatEntryOnly() {
        assertFalse(BackupFormat.entrySupported(ManifestEntry("chat", schema = 2, bytes = 1, path = "files/chat")))
        assertFalse(BackupFormat.entrySupported(ManifestEntry("secrets", schema = 1, bytes = 1, path = "files/secrets")))
        assertEquals(null, BackupCategory.fromEntryName("secrets"))
        assertEquals(BackupCategory.WALLPAPER, BackupCategory.fromEntryName("wallpaper"))
    }

    @Test
    fun fromPath_mapsFixedAndPrefixedPaths() {
        assertEquals(BackupCategory.CHAT, BackupCategory.fromPath("files/chat"))
        assertEquals(BackupCategory.SETTINGS, BackupCategory.fromPath("files/settings"))
        assertEquals(BackupCategory.WALLPAPER, BackupCategory.fromPath("files/wallpaper/任意名.png"))
        assertEquals(null, BackupCategory.fromPath("files/unknown"))
        assertEquals(null, BackupCategory.fromPath("manifest.json"))
    }
}
