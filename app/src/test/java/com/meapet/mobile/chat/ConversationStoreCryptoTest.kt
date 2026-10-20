package com.meapet.mobile.chat

import com.meapet.mobile.security.CipherFileFormat
import com.meapet.mobile.security.FakeDataKeyProvider
import com.meapet.mobile.security.fakeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 双支路与 `.corrupt` 三分判据的 store 侧验证（实现案 §2 反证表第 2 格）。
 *
 * 判据核心：**密文永远走不到"改名判死"那一步**——密钥丢失/接线缺席只报错，
 * 文件原样留在盘上；真损坏（解出明文但 JSON 坏）才落 `.corrupt`。
 */
class ConversationStoreCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun msg(content: String) = ChatMessage(role = ChatRole.user, content = content)

    /** ChatMessage 默认自带新 id/时间戳，整对象相等不是这里的判据——比内容。 */
    private fun assertContents(expected: List<ChatMessage>, actual: List<ChatMessage>) {
        assertEquals(expected.size, actual.size)
        assertEquals(expected.map { it.content }, actual.map { it.content })
    }

    private fun convFile(dir: File) = File(dir, "meapet_conversation.json")

    @Test
    fun enabledWritesCiphertext_andReadsBackWithSwitchOff() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        var on = true
        ConversationStore(dir, backgroundScope, fakeCrypto(provider) { on })
            .persist(listOf(msg("密文往返")))
        assertTrue("开关开着必须落密文", CipherFileFormat.isEncryptedFile(convFile(dir)))

        on = false
        val reloaded = ConversationStore(dir, backgroundScope, fakeCrypto(provider) { on }).load()
        assertContents(listOf(msg("密文往返")), reloaded)
    }

    @Test
    fun keyLostKeepsFileUntouched_noCorruptRename() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        ConversationStore(dir, backgroundScope, fakeCrypto(provider) { true })
            .persist(listOf(msg("唯一的数据")))
        provider.exists = false

        val loaded = ConversationStore(dir, backgroundScope, fakeCrypto(provider) { true }).load()
        assertTrue("密钥丢失只能返回空", loaded.isEmpty())
        assertTrue("密文必须在盘上原样留着", convFile(dir).exists())
        assertFalse("密钥档绝不可落 .corrupt 判死路", File(dir, "meapet_conversation.json.corrupt").exists())
    }

    @Test
    fun ciphertextWithoutWiring_isNotJudgedCorrupt() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        ConversationStore(dir, backgroundScope, fakeCrypto(provider) { true })
            .persist(listOf(msg("无接线也要留着")))

        val loaded = ConversationStore(dir, backgroundScope, null).load()
        assertTrue(loaded.isEmpty())
        assertTrue("未接常驻解密的读侧不许毁密文", convFile(dir).exists())
        assertFalse(File(dir, "meapet_conversation.json.corrupt").exists())
    }

    @Test
    fun decryptedButBadJson_stillGoesThroughCorruptPath() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        // 用文件格直接造一个"能解开、内容坏"的密文
        CipherFileFormat.encodeToFile("]]] 不是 JSON".encodeToByteArray(), provider, convFile(dir))

        val loaded = ConversationStore(dir, backgroundScope, fakeCrypto(provider) { false }).load()
        assertTrue(loaded.isEmpty())
        assertTrue("解出明文但 JSON 坏=真损坏，照旧走 .corrupt", File(dir, "meapet_conversation.json.corrupt").exists())
        assertFalse(convFile(dir).exists())
    }

    @Test
    fun plaintextEraBehavior_unchangedWhenCryptoNull() = runTest {
        val dir = tmp.newFolder()
        ConversationStore(dir, backgroundScope, null).persist(listOf(msg("明文如旧")))
        assertFalse(CipherFileFormat.isEncryptedFile(convFile(dir)))
        assertContents(listOf(msg("明文如旧")), ConversationStore(dir, backgroundScope, null).load())
    }
}
