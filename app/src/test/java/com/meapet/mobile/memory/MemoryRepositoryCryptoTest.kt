package com.meapet.mobile.memory

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

/** 记忆店的双支路与 `.corrupt` 三分（口径同 ConversationStoreCryptoTest）。 */
class MemoryRepositoryCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun mem(content: String) = MemoryItem(id = "m_" + System.nanoTime(), content = content)

    private fun memFile(dir: File) = File(dir, "meapet_memories.json")

    @Test
    fun enabledWritesCiphertext_andReadsBackWithSwitchOff() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        var on = true
        val item = mem("密文往返")
        MemoryRepository(dir, crypto = fakeCrypto(provider) { on }).save(item)
        assertTrue("开关开着必须落密文", CipherFileFormat.isEncryptedFile(memFile(dir)))

        on = false
        val reloaded = MemoryRepository(dir, crypto = fakeCrypto(provider) { on }).getAll()
        assertEquals(listOf(item), reloaded)
    }

    @Test
    fun keyLostKeepsFileUntouched_noCorruptRename() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        MemoryRepository(dir, crypto = fakeCrypto(provider) { true }).save(mem("唯一的数据"))
        provider.exists = false

        val repo = MemoryRepository(dir, crypto = fakeCrypto(provider) { true })
        assertTrue("密钥丢失只能读到空", repo.getAll().isEmpty())
        assertTrue("密文必须在盘上原样留着", memFile(dir).exists())
        assertFalse("密钥档绝不可落 .corrupt 判死路", File(dir, "meapet_memories.json.corrupt").exists())
    }

    @Test
    fun decryptedButBadJson_stillGoesThroughCorruptPath() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        CipherFileFormat.encodeToFile("]]] 不是 JSON".encodeToByteArray(), provider, memFile(dir))

        val repo = MemoryRepository(dir, crypto = fakeCrypto(provider) { false })
        assertTrue(repo.getAll().isEmpty())
        assertTrue("解出明文但 JSON 坏=真损坏，照旧走 .corrupt", File(dir, "meapet_memories.json.corrupt").exists())
        assertFalse(memFile(dir).exists())
    }
}
