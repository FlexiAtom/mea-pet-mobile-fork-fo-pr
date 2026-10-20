package com.meapet.mobile.security

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 内存假档（设置路），迁移顺序与开关语义全靠它显形。 */
private class FakeApiKeySettings : ApiKeySettings {
    var stored = "sk-plain-123"
    override fun peekRawStored(): String = stored
    override suspend fun rewriteStored(transform: (String) -> String) { stored = transform(stored) }
    override var atRestEnabled: Boolean = false
}

/**
 * 双向重加密编排与幂等续传（实现案 §2 反证表第 3 格的 JVM 层；
 * 真机"杀进程续传"仍挂在该格，装机复验时一并走）。
 */
class AtRestMigratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun enable_encryptsAllThreePaths_andFlipsToggleLast() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        val settings = FakeApiKeySettings()
        val chat = File(dir, "chat.json").apply { writeText("[{\"a\":1}]") }
        val memory = File(dir, "mem.json").apply { writeText("[]") }
        val crypto = AtRestCrypto.providerBacked({ settings.atRestEnabled }, provider)
        val migrator = AtRestMigrator(File(dir, "cache").apply { mkdirs() }, chat, memory, settings, crypto)

        assertEquals(3, migrator.enable())
        assertTrue("聊天必须落密文", CipherFileFormat.isEncryptedFile(chat))
        assertTrue("记忆必须落密文", CipherFileFormat.isEncryptedFile(memory))
        assertTrue("API Key 必须带前缀", ValueCipher.isEncoded(settings.stored))
        assertTrue("开关最后必须为开", settings.atRestEnabled)
        assertEquals("[]", String(CipherFileFormat.decodeFile(memory, provider)))
        assertNull("完成后不留进度", migrator.pendingProgress())
        assertTrue("存量与开关一致", migrator.isConsistent())
    }

    @Test
    fun disable_restoresPlaintext_everywhere() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        val settings = FakeApiKeySettings()
        val chat = File(dir, "chat.json")
        val memory = File(dir, "mem.json")
        val cache = File(dir, "cache").apply { mkdirs() }
        val crypto = AtRestCrypto.providerBacked({ settings.atRestEnabled }, provider)
        val migrator = AtRestMigrator(cache, chat, memory, settings, crypto)
        chat.writeText("[{\"a\":1}]")
        memory.writeText("[]")
        migrator.enable()

        assertEquals(3, migrator.disable())
        assertFalse(chat.let { CipherFileFormat.isEncryptedFile(it) })
        assertFalse(CipherFileFormat.isEncryptedFile(memory))
        assertFalse(ValueCipher.isEncoded(settings.stored))
        assertFalse(settings.atRestEnabled)
        assertEquals("[{\"a\":1}]", chat.readText())
        assertEquals("sk-plain-123", settings.stored)
        assertTrue(migrator.isConsistent())
    }

    @Test
    fun interruptedEnable_resumesOnlyMissingSteps() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        val settings = FakeApiKeySettings()
        val chat = File(dir, "chat.json").apply { writeText("[1]") }
        val memory = File(dir, "mem.json").apply { writeText("[2]") }
        val cache = File(dir, "cache").apply { mkdirs() }
        val crypto = AtRestCrypto.providerBacked({ true }, provider)
        val migrator = AtRestMigrator(cache, chat, memory, settings, crypto)

        // 模拟"设置+记忆已做完、聊天没做就被杀"
        File(cache, "at_rest_migration.json").writeText("""{"direction":"ENABLE","done":["settings","memory"]}""")
        memory.run {
            val plain = readBytes()
            crypto.tryWrite(this, "mem.enc.tmp", plain)
        }
        settings.stored = ValueCipher.encode("sk-x", provider)

        assertEquals(3, migrator.enable())
        assertTrue("续传必须把缺的聊天补上", CipherFileFormat.isEncryptedFile(chat))
        assertNull(migrator.pendingProgress())
        assertTrue(settings.atRestEnabled)
    }

    @Test
    fun reverseDirectionWhilePending_isRejected_notSilentlyMixed() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        val settings = FakeApiKeySettings()
        val cache = File(dir, "cache").apply { mkdirs() }
        val crypto = AtRestCrypto.providerBacked({ settings.atRestEnabled }, provider)
        val migrator = AtRestMigrator(cache, File(dir, "chat.json"), File(dir, "mem.json"), settings, crypto)
        File(cache, "at_rest_migration.json").writeText("""{"direction":"ENABLE","done":["settings"]}""")

        try {
            migrator.disable()
            fail("带着 ENABLE 进度竟然能反向跑")
        } catch (expected: MigrationRejectedException) {
            assertNotNull(expected.message)
        }
        assertNotNull(migrator.pendingProgress())
    }

    @Test
    fun disableWithLostKey_abortsAndKeepsProgress() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        val settings = FakeApiKeySettings()
        val chat = File(dir, "chat.json")
        val memory = File(dir, "mem.json").apply { writeText("[3]") }
        val cache = File(dir, "cache").apply { mkdirs() }
        val crypto = AtRestCrypto.providerBacked({ settings.atRestEnabled }, provider)
        val migrator = AtRestMigrator(cache, chat, memory, settings, crypto)
        migrator.enable()
        provider.exists = false // 密钥丢了

        try {
            migrator.disable()
            fail("密钥丢失竟然还原成功")
        } catch (expected: MigrationRejectedException) {
            assertTrue("理由必须点出密钥档", expected.message!!.contains("密钥"))
        }
        assertTrue("密文必须原样留盘", CipherFileFormat.isEncryptedFile(memory))
        assertNotNull("进度标记要留着，修好原因可续传", migrator.pendingProgress())
    }

    @Test
    fun disable_whenNeverEnabled_isNoOp() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        val settings = FakeApiKeySettings()
        val crypto = AtRestCrypto.providerBacked({ settings.atRestEnabled }, provider)
        val migrator = AtRestMigrator(
            File(dir, "cache").apply { mkdirs() }, File(dir, "chat.json"), File(dir, "mem.json"), settings, crypto
        )
        assertEquals(0, migrator.disable())
        assertFalse(settings.atRestEnabled)
    }

    /** 收敛路径：开关与存量不一致时，同方向重跑补齐——不能拿"已是这个态"当拒绝理由。 */
    @Test
    fun rerunEnable_afterAWriteWentBackToPlaintext_completesMissingPath() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        val settings = FakeApiKeySettings()
        val chat = File(dir, "chat.json").apply { writeText("[1]") }
        val memory = File(dir, "mem.json").apply { writeText("[2]") }
        val crypto = AtRestCrypto.providerBacked({ settings.atRestEnabled }, provider)
        val migrator = AtRestMigrator(File(dir, "cache").apply { mkdirs() }, chat, memory, settings, crypto)
        migrator.enable()

        chat.writeText("[被另一路写回明文的会话]") // 已知边界：转换间隙的旧语义写入

        assertFalse(migrator.isConsistent())
        assertEquals(3, migrator.enable())
        assertTrue("补齐后回到一致", migrator.isConsistent())
        assertEquals("[被另一路写回明文的会话]", String(CipherFileFormat.decodeFile(chat, provider)))
    }

    /** 关着但盘上仍有密文（还原被杀后标记已清掉的极端格）：disable 不能当空操作跳过。 */
    @Test
    fun disable_whenSwitchOffButCiphertextRemains_stillRestores() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        val settings = FakeApiKeySettings()
        val chat = File(dir, "chat.json").apply { writeText("[1]") }
        val crypto = AtRestCrypto.providerBacked({ settings.atRestEnabled }, provider)
        val migrator = AtRestMigrator(File(dir, "cache").apply { mkdirs() }, chat, File(dir, "mem.json"), settings, crypto)
        migrator.enable()
        settings.atRestEnabled = false // 开关掉了，密文还在

        assertEquals(3, migrator.disable())
        assertEquals("[1]", chat.readText())
    }

    /** 密钥档不可解时的明示通路：旧数据作废——删两路密文、清值级密文、关开关。 */
    @Test
    fun discard_deletesCiphertextAndClearsKey_andTurnsToggleOff() = runTest {
        val dir = tmp.newFolder()
        val provider = FakeDataKeyProvider()
        val settings = FakeApiKeySettings()
        val chat = File(dir, "chat.json").apply { writeText("[1]") }
        val memory = File(dir, "mem.json").apply { writeText("[2]") }
        val cache = File(dir, "cache").apply { mkdirs() }
        val crypto = AtRestCrypto.providerBacked({ settings.atRestEnabled }, provider)
        val migrator = AtRestMigrator(cache, chat, memory, settings, crypto)
        migrator.enable()

        // 模拟密钥丢失
        provider.exists = false
        File(cache, "at_rest_migration.json").writeText("""{"direction":"DISABLE","done":["chat"]}""")
        val plaintextBackup = File(dir, "chat_plain_kept.json").apply { writeBytes(chat.readBytes()) }

        assertEquals(3, migrator.discardEncryptedData())
        assertFalse("聊天密文作废", chat.exists())
        assertFalse("记忆密文作废", memory.exists())
        assertEquals("", settings.stored)
        assertFalse(settings.atRestEnabled)
        assertNull(migrator.pendingProgress())
        assertTrue("作废不碰非密文文件", plaintextBackup.exists())
        assertTrue(migrator.isConsistent())
    }
}
