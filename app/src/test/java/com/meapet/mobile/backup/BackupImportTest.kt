package com.meapet.mobile.backup

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class BackupImportTest {

    private val pw = "口令1".toCharArray()
    private val dir: File = Files.createTempDirectory("import_probe").toFile()
    private val chatText = """[{"role":"user","content":"你好"}]"""

    private fun makePackage(selected: Set<BackupCategory> = BackupCategory.values().toSet()): File {
        val wallpaper = File(dir, "wallpaper_42.jpg").apply { writeBytes(ByteArray(11) { 9 }) }
        val items = BackupCollector.collect(
            selected,
            BackupSources(
                settingsPrefs = mutablePreferencesOf().apply {
                    this[stringPreferencesKey("model")] = "导入回来的模型"
                    this[stringPreferencesKey("api_key")] = "sk-SECRET"
                },
                chatFile = File(dir, "meapet_conversation.json").apply { writeText(chatText) },
                memoryFile = File(dir, "meapet_memories.json").apply { writeText("[]") },
                wallpaperFile = wallpaper,
            )
        )
        val zip = File(dir, "pkg_${System.nanoTime()}.zip")
        FileOutputStream(zip).use { sink ->
            runBlocking { BackupExporter.writeBackup(sink, "口令1".toCharArray(), items, "2026-10-20T040000Z") }
        }
        return zip
    }

    private class FakeApplier : BackupApplier {
        val received = mutableMapOf<BackupCategory, String>()
        val landed = mutableListOf<BackupCategory>()
        val rolledBack = mutableListOf<BackupCategory>()
        var wallpaperName: String? = null
        var wallpaperBytes: ByteArray? = null
        var failOn: BackupCategory? = null

        override suspend fun applySettings(json: String): BackupUndo? = touch(BackupCategory.SETTINGS, json)
        override suspend fun applyChat(text: String): BackupUndo? = touch(BackupCategory.CHAT, text)
        override suspend fun applyMemory(text: String): BackupUndo? = touch(BackupCategory.MEMORY, text)

        override suspend fun applyWallpaper(fileName: String, source: File): BackupUndo? {
            if (failOn == BackupCategory.WALLPAPER) throw IllegalStateException("boom-wallpaper")
            wallpaperName = fileName
            wallpaperBytes = source.readBytes()
            landed += BackupCategory.WALLPAPER
            return { rolledBack += BackupCategory.WALLPAPER }
        }

        private fun touch(category: BackupCategory, text: String): BackupUndo? {
            if (failOn == category) throw IllegalStateException("boom-$category")
            received[category] = text
            landed += category
            return { rolledBack += category }
        }
    }

    @Test
    fun roundTrip_exportThenImport_returnsWhatWentIn() {
        val zip = makePackage()
        val applier = FakeApplier()
        val summary = runBlocking { BackupImporter(applier).import(zip, "口令1".toCharArray(), BackupCategory.values().toSet()) }
        assertEquals(BackupCategory.values().toSet(), summary.applied)
        assertEquals(BackupCategory.values().toSet(), applier.landed.toSet())
        assertEquals("2026-10-20T040000Z", summary.createdAt)
        assertEquals(chatText, applier.received[BackupCategory.CHAT])
        assertEquals("[]", applier.received[BackupCategory.MEMORY])
        assertTrue(applier.received.getValue(BackupCategory.SETTINGS).contains("导入回来的模型"))
        assertFalse("api_key 不该绕道回来", applier.received.getValue(BackupCategory.SETTINGS).contains("sk-SECRET"))
        assertEquals("wallpaper_42.jpg", applier.wallpaperName)
        assertEquals(11, applier.wallpaperBytes!!.size)
        // 解出的中间体不留在暂存目录
        assertNull(File(zip.parentFile, "wallpaper_in_wallpaper_42.jpg").takeIf { it.exists() })
    }

    @Test
    fun selection_limitsWhatReachesApplier() {
        val zip = makePackage()
        val applier = FakeApplier()
        val summary = runBlocking {
            BackupImporter(applier).import(zip, "口令1".toCharArray(), setOf(BackupCategory.CHAT))
        }
        assertEquals(setOf(BackupCategory.CHAT), summary.applied)
        assertEquals(setOf(BackupCategory.CHAT), applier.received.keys)
        assertNull(applier.wallpaperName)
    }

    @Test
    fun wrongPassword_failsBeforeAnyApply() {
        val zip = makePackage()
        val applier = FakeApplier()
        try {
            runBlocking { BackupImporter(applier).import(zip, "错口令".toCharArray(), BackupCategory.values().toSet()) }
            fail("错口令竟然走完导入")
        } catch (expected: Exception) {
            assertTrue("错口令应在校验阶段就挂", applier.received.isEmpty())
        }
    }

    @Test
    fun midWayFailure_rollsBackAlreadyAppliedCategories() {
        val zip = makePackage()
        val applier = FakeApplier().apply { failOn = BackupCategory.CHAT }
        try {
            runBlocking { BackupImporter(applier).import(zip, "口令1".toCharArray(), BackupCategory.values().toSet()) }
            fail("注入的失败点竟然没抛")
        } catch (expected: BackupImportException) {
            assertTrue(expected.message!!.contains("回滚"))
            // 回灌顺序 settings→memory→chat；CHAT 抛错前只有前两类落了地，撤销必须逆序各来一次
            assertEquals(listOf(BackupCategory.SETTINGS, BackupCategory.MEMORY), applier.landed)
            assertEquals(listOf(BackupCategory.MEMORY, BackupCategory.SETTINGS), applier.rolledBack)
            assertFalse(applier.received.containsKey(BackupCategory.CHAT))
        }
    }

    @Test
    fun tamperedPackage_rejectedByFormatOrSchema() {
        // 手工造三种坏包：未知路径条目 / schema 过新 / manifest 缺该条目
        listOf(
            Triple("files/unknown", 1, true),
            Triple("files/chat", 2, true),
            Triple("files/chat", 1, false),
        ).forEach { (path, schema, registerInManifest) ->
            val zip = File(dir, "bad_${path.replace('/', '_')}_${schema}_${registerInManifest}.zip")
            val manifest = BackupManifest(
                format = BackupFormat.SUPPORTED_FORMAT,
                createdAt = "t",
                entries = if (registerInManifest) listOf(ManifestEntry("chat", schema, 2, path)) else emptyList(),
            )
            FileOutputStream(zip).use { sink ->
                ZipOutputStream(sink, "口令1".toCharArray()).use { zipOut ->
                    zipOut.putNextEntry(entryParams(path, 2))
                    zipOut.write("hi".toByteArray())
                    zipOut.closeEntry()
                    val mb = BackupFormat.encode(manifest).toByteArray(Charsets.UTF_8)
                    zipOut.putNextEntry(entryParams(BackupExporter.MANIFEST_ENTRY, mb.size.toLong()))
                    zipOut.write(mb)
                    zipOut.closeEntry()
                }
            }
            try {
                BackupInspector.inspect(zip, "口令1".toCharArray())
                fail("坏包竟然通过校验: $path schema=$schema registered=$registerInManifest")
            } catch (expected: ManifestRejectException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    private fun entryParams(path: String, size: Long) = ZipParameters().apply {
        setEncryptFiles(true)
        setEncryptionMethod(EncryptionMethod.AES)
        setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
        setFileNameInZip(path)
        setEntrySize(size)
        setCompressionMethod(CompressionMethod.DEFLATE)
    }

    @Test
    fun manifestOnlyPackage_isValidButAppliesNothing() {
        val zip = File(dir, "empty.zip")
        FileOutputStream(zip).use { sink ->
            runBlocking { BackupExporter.writeBackup(sink, "口令1".toCharArray(), emptyList(), "2026-10-20T041000Z") }
        }
        val contents = BackupInspector.inspect(zip, "口令1".toCharArray())
        assertEquals(emptySet<BackupCategory>(), contents.categories)
        val applier = FakeApplier()
        val summary = runBlocking {
            BackupImporter(applier).import(zip, "口令1".toCharArray(), BackupCategory.values().toSet())
        }
        assertTrue(summary.applied.isEmpty())
    }

    /**
     * 解压上限：128 MiB 的暂存闸只卡住**压缩后**的体积，deflate 对全零正文能压到千倍级，
     * 几百 KB 的包就够解出 GB 级；manifest 的 bytes 与 zip 头里的 uncompressedSize
     * 都是包方自报（对账只保证两者互相一致，不保证真实）。所以闸必须按实际读到的字节下。
     */
    @Test
    fun read_rejectsEntryExpandingPastTheCap() {
        val total = 65L * 1024 * 1024
        val zip = File(dir, "expands_past_cap.zip")
        val zeros = ByteArray(1024 * 1024)
        FileOutputStream(zip).use { sink ->
            ZipOutputStream(sink, "口令1".toCharArray()).use { zipOut ->
                zipOut.putNextEntry(entryParams("files/chat", total))
                repeat((total / zeros.size).toInt()) { zipOut.write(zeros) }
                zipOut.closeEntry()
                val mb = BackupFormat.encode(
                    BackupManifest(
                        format = BackupFormat.SUPPORTED_FORMAT,
                        createdAt = "t",
                        entries = listOf(ManifestEntry("chat", 1, total, "files/chat")),
                    )
                ).toByteArray(Charsets.UTF_8)
                zipOut.putNextEntry(entryParams(BackupExporter.MANIFEST_ENTRY, mb.size.toLong()))
                zipOut.write(mb)
                zipOut.closeEntry()
            }
        }
        // 校验阶段只比头与 manifest（两者自洽），放行是预期的；闸落在读正文那一步
        val contents = BackupInspector.inspect(zip, "口令1".toCharArray())
        try {
            BackupInspector.readText(contents, BackupCategory.CHAT, zip, "口令1".toCharArray())
            fail("65 MiB 的正文竟然被整读进内存")
        } catch (expected: ManifestRejectException) {
            assertTrue(expected.message!!.contains("上限"))
        }
    }
}
