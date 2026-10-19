package com.meapet.mobile.backup

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupCollectorTest {

    private val chatText = """[{"role":"user","content":"原文里的换行\n不该被改写"}]"""
    private val memoryText = """[{"id":"m1","type":"FACTUAL","content":"保持原样"}]"""

    private val dir: File = Files.createTempDirectory("collector_probe").toFile()

    private fun textFile(name: String, text: String): File =
        File(dir, name).apply { writeText(text) }

    private fun sources(chat: String? = chatText, memory: String? = memoryText) = BackupSources(
        settingsPrefs = mutablePreferencesOf().apply {
            this[stringPreferencesKey("model")] = "某模型"
            this[stringPreferencesKey("api_key")] = "sk-SECRET"
        },
        chatFile = chat?.let { textFile("meapet_conversation.json", it) },
        memoryFile = memory?.let { textFile("meapet_memories.json", it) },
        wallpaperFile = File(dir, "wallpaper_1700000000.jpg").apply {
            writeBytes(ByteArray(7) { 3 })
        },
    )

    @Test
    fun passthrough_keepsSourceBytesUnchanged() {
        val byPath = BackupCollector
            .collect(setOf(BackupCategory.CHAT, BackupCategory.MEMORY), sources())
            .associateBy { it.zipPath }
        assertEquals(chatText, (byPath["files/chat"] as BackupItem.Text).content)
        assertEquals(memoryText, (byPath["files/memory"] as BackupItem.Text).content)
    }

    @Test
    fun unselectedOrMissingSources_doNotEnterPackage() {
        assertEquals(
            listOf("files/settings"),
            BackupCollector.collect(setOf(BackupCategory.SETTINGS), sources()).map { it.zipPath }
        )
        assertEquals(
            listOf("files/wallpaper/wallpaper_1700000000.jpg"),
            BackupCollector
                .collect(setOf(BackupCategory.CHAT, BackupCategory.WALLPAPER), sources(chat = null))
                .map { it.zipPath }
        )
        assertTrue(
            BackupCollector.collect(
                BackupCategory.values().toSet(),
                BackupSources(settingsPrefs = null, wallpaperFile = null)
            ).isEmpty()
        )
    }

    @Test
    fun settingsEntry_isWhitelistFiltered() {
        val text = (BackupCollector.collect(setOf(BackupCategory.SETTINGS), sources()).first()
            as BackupItem.Text).content
        assertTrue(text.contains("某模型"))
        assertFalse("api_key 不应进设置条目", text.contains("sk-SECRET"))
    }

    @Test
    fun wallpaper_usesDiskNameUnderPrefix() {
        val item = BackupCollector.collect(setOf(BackupCategory.WALLPAPER), sources()).first()
        assertEquals("files/wallpaper/wallpaper_1700000000.jpg", item.zipPath)
        assertTrue(item is BackupItem.FileSource)
    }

    @Test
    fun sanitizeWallpaperName_narrowsToLeafAndCharset() {
        assertEquals("wallpaper_1.jpg", BackupCollector.sanitizeWallpaperName("wallpaper_1.jpg"))
        assertEquals("a_b_c.jpg", BackupCollector.sanitizeWallpaperName("a b\"c.jpg"))
        assertEquals("x.jpg", BackupCollector.sanitizeWallpaperName("/tmp/../../x.jpg"))
        assertEquals("wallpaper.jpg", BackupCollector.sanitizeWallpaperName("///"))
        val long = BackupCollector.sanitizeWallpaperName("n".repeat(300) + ".jpg")
        assertTrue(long.length <= 96 && !long.contains('/'))
    }

    @Test
    fun collectedPaths_areAllAcceptedByWriter() {
        val items = BackupCollector.collect(BackupCategory.values().toSet(), sources())
        assertEquals(4, items.size)
        val sink = File(dir, "out.zip")
        runBlocking {
            BackupExporter.writeBackup(sink.outputStream(), "pw".toCharArray(), items, "t")
        }
        assertTrue(sink.length() > 0)
    }

    @Test
    fun writer_rejectsIndependentDotDotSegment_butAcceptsDottedName() {
        // 段为 ".." 才是越界；名字里带连续点（..2.jpg）是合法现名，不该被误杀
        runBlocking {
            BackupExporter.writeBackup(
                File(dir, "dotted.zip").outputStream(), "pw".toCharArray(),
                listOf(BackupItem.Text("files/wallpaper/..2.jpg", "x")), "t"
            )
        }
        try {
            runBlocking {
                BackupExporter.writeBackup(
                    File(dir, "escape.zip").outputStream(), "pw".toCharArray(),
                    listOf(BackupItem.Text("files/../escape", "x")), "t"
                )
            }
            throw AssertionError("独立 .. 段竟然被放行")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("越界"))
        }
    }
}
