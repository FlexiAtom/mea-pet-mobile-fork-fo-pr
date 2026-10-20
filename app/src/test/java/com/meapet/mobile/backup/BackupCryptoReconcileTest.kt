package com.meapet.mobile.backup

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ConversationStore
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryRepository
import com.meapet.mobile.security.AtRestCrypto
import com.meapet.mobile.security.CipherFileFormat
import com.meapet.mobile.security.FakeDataKeyProvider
import com.meapet.mobile.security.KeyUnavailableException
import com.meapet.mobile.security.fakeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 导出线 × 常驻加密的对账（人工裁决 2026-10-20 09:48「解密导出，包维持现状」）：
 * 包内仍是明文 JSON，密文只存在于磁盘；解不开就报错，**不出包**。
 */
class BackupCryptoReconcileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val chatText = """[{"role":"user","content":"加密机里的一条"}]"""
    private val memoryText = """[{"id":"m1","type":"FACTUAL","content":"加密机里的一条记忆"}]"""

    private fun cipherFile(name: String, plain: String, provider: FakeDataKeyProvider): File =
        tmp.newFolder().let { folder ->
            File(folder, name).also { CipherFileFormat.encodeToFile(plain.encodeToByteArray(), provider, it) }
        }

    private fun plainFile(name: String, text: String): File =
        File(tmp.newFolder(), name).apply { writeText(text) }

    private fun sources(chat: File?, memory: File?, crypto: AtRestCrypto?) =
        BackupSources(chatFile = chat, memoryFile = memory, atRestCrypto = crypto)

    @Test
    fun encryptedDisk_exportCarriesPlaintextIntoPackage() {
        val provider = FakeDataKeyProvider()
        val chat = cipherFile("meapet_conversation.json", chatText, provider)
        val memory = cipherFile("meapet_memories.json", memoryText, provider)
        assertTrue("先确认盘上确实是密文", CipherFileFormat.isEncryptedFile(chat))

        val byPath = BackupCollector
            .collect(
                setOf(BackupCategory.CHAT, BackupCategory.MEMORY),
                sources(chat, memory, fakeCrypto(provider) { true })
            )
            .associateBy { it.zipPath }

        assertEquals(chatText, (byPath["files/chat"] as BackupItem.Text).content)
        assertEquals(memoryText, (byPath["files/memory"] as BackupItem.Text).content)
    }

    @Test
    fun plaintextDisk_isUnchangedByTheSeam() {
        val chat = plainFile("chat.json", chatText)
        val items = BackupCollector.collect(
            setOf(BackupCategory.CHAT),
            sources(chat, null, fakeCrypto(FakeDataKeyProvider()) { false })
        )
        assertEquals(chatText, (items.single() as BackupItem.Text).content)
    }

    @Test
    fun cipherFile_withoutCryptoSeam_isRejected_notPackedAsGarbage() {
        val chat = cipherFile("meapet_conversation.json", chatText, FakeDataKeyProvider())

        try {
            BackupCollector.collect(setOf(BackupCategory.CHAT), sources(chat, null, null))
            fail("密文无接线竟然出了条目")
        } catch (expected: KeyUnavailableException) {
            assertTrue(expected.message!!.contains("密文"))
        }
    }

    @Test
    fun cipherFile_withLostKey_isRejected() {
        val provider = FakeDataKeyProvider()
        val chat = cipherFile("meapet_conversation.json", chatText, provider)
        provider.exists = false

        try {
            BackupCollector.collect(setOf(BackupCategory.CHAT), sources(chat, null, fakeCrypto(provider) { true }))
            fail("密钥丢失竟然出了条目")
        } catch (expected: KeyUnavailableException) {
            assertTrue("理由必须说清解不开", expected.message!!.isNotBlank())
        }
    }

    /** 完整往返：加密机导出 → 加密机导入落盘仍是密文，且内容等值。 */
    @Test
    fun encryptedExport_thenImportIntoEncryptedDevice_roundTrips() = runTest {
        val srcProvider = FakeDataKeyProvider()
        val srcCrypto = fakeCrypto(srcProvider) { true }
        val srcStore = ConversationStore(tmp.newFolder(), backgroundScope, srcCrypto)
        val messages = listOf(
            ChatMessage(ChatRole.user, "来回一趟 🐱\n多行\"照旧\""),
            ChatMessage(ChatRole.assistant, "收到")
        )
        srcStore.persist(messages)
        val srcChat = requireNotNull(srcStore.backupFile()) { "源机会话未落盘" }
        assertTrue("源机磁盘是密文", CipherFileFormat.isEncryptedFile(srcChat))

        val exported = BackupCollector
            .collect(setOf(BackupCategory.CHAT), sources(srcChat, null, srcCrypto))
            .associateBy { it.zipPath }
        val payload = (exported["files/chat"] as BackupItem.Text).content
        assertFalse("包内不得带密文头", CipherFileFormat.isEncrypted(payload.encodeToByteArray()))

        val dstCrypto = fakeCrypto(FakeDataKeyProvider()) { true }
        val dstDir = tmp.newFolder()
        val dstStore = ConversationStore(dstDir, backgroundScope, dstCrypto)
        val parsed = requireNotNull(dstStore.parseSnapshot(payload)) { "导出内容在目标机解析不动" }
        dstStore.persist(parsed)

        val dstFile = requireNotNull(dstStore.backupFile()) { "目标机未落盘" }
        assertTrue("目标机按本机开关落密文", CipherFileFormat.isEncryptedFile(dstFile))
        val reloaded = ConversationStore(dstDir, backgroundScope, dstCrypto).load()
        assertEquals(messages.map { it.content }, reloaded.map { it.content })

        // 记忆一路同构：明文进、密文落盘、冷实例读回
        val plainDir = tmp.newFolder()
        MemoryRepository(plainDir).save(MemoryItem(id = "m1", content = "只在该机存在的一条"))
        val memPlain = MemoryRepository(plainDir).backupFile()!!.readText()
        val encDir = tmp.newFolder()
        val encRepo = MemoryRepository(encDir, crypto = dstCrypto)
        assertNotNull(encRepo.replaceWith(memPlain))
        assertTrue(CipherFileFormat.isEncryptedFile(encRepo.backupFile()!!))
        assertEquals(listOf("m1"), MemoryRepository(encDir, crypto = dstCrypto).getAll().map { it.id })
    }

    /** 值级 API Key 本来就不进包（G4 白名单），加密与否都不该漏出去。 */
    @Test
    fun apiKey_neverEntersSettingsEntry_whetherEncodedOrNot() {
        val prefs = mutablePreferencesOf().apply {
            this[stringPreferencesKey("model")] = "某模型"
            this[stringPreferencesKey("api_key")] = "k1:ZmFrZQ=="
        }
        val text = (BackupCollector.collect(setOf(BackupCategory.SETTINGS), BackupSources(settingsPrefs = prefs))
            .single() as BackupItem.Text).content
        assertFalse("密文值也不该漏进包", text.contains("k1:"))
        assertTrue(text.contains("某模型"))
    }
}
