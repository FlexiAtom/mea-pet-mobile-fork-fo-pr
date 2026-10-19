package com.meapet.mobile.backup

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random

class BackupExporterTest {

    private fun readAll(input: java.io.InputStream): ByteArray {
        val bo = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        while (input.read(buf).also { n = it } > 0) bo.write(buf, 0, n)
        input.close()
        return bo.toByteArray()
    }

    @Test
    fun fullChain_writeThenDecryptRead_allEntriesMatch() {
        val pw = "口令p@ss-测试".toCharArray()
        val wallpaper = File.createTempFile("wallpaper_probe", ".jpg").apply {
            writeBytes(ByteArray(4096).also { Random(3L).nextBytes(it) })
        }
        val chatJson = "[{\"role\":\"user\",\"content\":\"你好\"}]"
        val settingsJson = SettingsWhitelist.encodeFrom(
            mutablePreferencesOf().apply {
                this[stringPreferencesKey("model")] = "deepseek"
            }
        )
        val zip = File.createTempFile("backup_probe", ".zip")
        try {
            val total = FileOutputStream(zip).use { sink ->
                runBlocking {
                    BackupExporter.writeBackup(
                        sink = sink,
                        password = pw,
                        items = listOf(
                            BackupItem.Text(BackupExporter.pathFor(BackupCategory.SETTINGS), settingsJson),
                            BackupItem.Text(BackupExporter.pathFor(BackupCategory.CHAT), chatJson),
                            BackupItem.Text(BackupExporter.pathFor(BackupCategory.MEMORY), "[]"),
                            BackupItem.FileSource(
                                BackupExporter.wallpaperEntryPath(wallpaper.name),
                                wallpaper
                            ),
                        ),
                        createdAt = "2026-10-20T030000Z",
                    )
                }
            }
            assertTrue(total > 0)
            ZipFile(zip, "口令p@ss-测试".toCharArray()).use { zf ->
                val headers = zf.fileHeaders
                val byPath = headers.associateBy { it.fileName }
                assertTrue(byPath.keys.containsAll(
                    listOf("manifest.json", "files/settings", "files/chat", "files/memory",
                        "files/wallpaper/${wallpaper.name}")
                ))
                headers.forEach { assertTrue("每条都应加密: ${it.fileName}", it.isEncrypted) }

                val manifest = BackupFormat.decode(
                    String(readAll(zf.getInputStream(byPath["manifest.json"]!!)), Charsets.UTF_8)
                )
                assertEquals(4, manifest.entries.size)
                manifest.entries.forEach { entry ->
                    assertTrue("manifest 与实际条目不符: ${entry.path}", byPath.containsKey(entry.path))
                    assertEquals(entry.bytes, byPath[entry.path]!!.uncompressedSize)
                    assertTrue(BackupFormat.entrySupported(entry))
                }
                val wallpaperEntry = manifest.entries.first { it.name == "wallpaper" }
                assertEquals(
                    "壁纸按磁盘现名入包且字节等值",
                    true,
                    wallpaperEntry.path == "files/wallpaper/${wallpaper.name}" &&
                        readAll(zf.getInputStream(byPath[wallpaperEntry.path]!!)).contentEquals(wallpaper.readBytes())
                )
                assertEquals(
                    chatJson,
                    String(readAll(zf.getInputStream(byPath["files/chat"]!!)), Charsets.UTF_8)
                )
            }
            // 错口令快速失败
            try {
                ZipFile(zip, "wrong".toCharArray()).use { zf ->
                    readAll(zf.getInputStream(zf.fileHeaders.first { it.fileName == "files/chat" }))
                }
                fail("错口令竟然读出数据")
            } catch (expected: net.lingala.zip4j.exception.ZipException) {
                // 预期
            }
        } finally {
            zip.delete()
            wallpaper.delete()
        }
    }

    @Test
    fun pathTraversal_rejected() {
        val zip = File.createTempFile("backup_probe", ".zip")
        try {
            FileOutputStream(zip).use { sink ->
                try {
                    runBlocking {
                        BackupExporter.writeBackup(
                            sink = sink,
                            password = "x".toCharArray(),
                            items = listOf(BackupItem.Text("../../escape", "boom")),
                            createdAt = "t",
                        )
                    }
                    fail("越界路径竟然被放行")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message!!.contains("越界"))
                }
            }
        } finally {
            zip.delete()
        }
    }

    @Test
    fun defaultFileName_followsSealedConvention() {
        assertEquals(
            "2026-10-20T030000Z_MeaPet-Android.zip",
            BackupExporter.defaultFileName("2026-10-20T030000Z")
        )
    }
}
