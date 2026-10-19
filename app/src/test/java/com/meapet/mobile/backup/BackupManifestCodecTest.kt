package com.meapet.mobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupManifestCodecTest {

    @Test
    fun roundtrip_keepsTwoLevelVersionsAndBytes() {
        val manifest = BackupManifest(
            format = BackupManifestCodec.SUPPORTED_FORMAT,
            createdAt = "2026-10-20T023000Z",
            entries = listOf(
                ManifestEntry("chat", schema = 1, bytes = 1_048_576),
                ManifestEntry("settings", schema = 1, bytes = 2_048),
            ),
        )
        val back = BackupManifestCodec.decode(BackupManifestCodec.encode(manifest))
        assertEquals(manifest, back)
        assertTrue(back.entries.all { BackupManifestCodec.entrySupported(it) })
        assertEquals(1_048_576L, back.entries[0].bytes)
    }

    @Test
    fun encodedText_hasNoSha256Field() {
        val text = BackupManifestCodec.encode(
            BackupManifest(1, "t", listOf(ManifestEntry("memory", 1, 5)))
        )
        assertFalse("sha256 已剪除，编码结果不应带该字段", text.contains("sha256"))
    }

    @Test
    fun formatMismatch_rejectsWholePackage() {
        val text = BackupManifestCodec.encode(BackupManifest(2, "t", emptyList()))
        try {
            BackupManifestCodec.decode(text)
            fail("format=2 竟然被放行")
        } catch (expected: ManifestRejectException) {
            assertTrue(expected.message!!.contains("format 不支持: 2"))
        }
    }

    @Test
    fun entrySchemaTooNew_orUnknownName_rejectsThatEntryOnly() {
        assertFalse(BackupManifestCodec.entrySupported(ManifestEntry("chat", schema = 2, bytes = 1)))
        assertFalse(BackupManifestCodec.entrySupported(ManifestEntry("secrets", schema = 1, bytes = 1)))
        assertEquals(null, BackupManifestCodec.entryCategory("secrets"))
        assertEquals(BackupCategory.WALLPAPER, BackupManifestCodec.entryCategory("wallpaper"))
    }
}
