package com.meapet.mobile.security

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 文件格嗅探与损坏分流（实现案 §2 反证表第 1 格）。
 *
 * 密钥档用软件 AES 假提供器——与生产同一 transformation/参数，只有密钥来源不同，
 * 所以编解码、长度字段、分流判据这些**真逻辑**全部在测试里走真码。
 */
class CipherFileFormatTest {

    @Test
    fun roundtrip_emptyPlain_singleChunk() {
        val provider = FakeDataKeyProvider()
        val encoded = CipherFileFormat.encode(ByteArray(0), provider)
        assertTrue(CipherFileFormat.isEncrypted(encoded))
        assertEquals(0, CipherFileFormat.decode(encoded, provider).size)
    }

    @Test
    fun roundtrip_multiChunk_largerThan64KiB() {
        val provider = FakeDataKeyProvider()
        val plain = "记".repeat(64 * 1024).encodeToByteArray() // 跨多个分块的 UTF-8 长文
        val encoded = CipherFileFormat.encode(plain, provider)
        assertTrue(CipherFileFormat.isEncrypted(encoded))
        assertArrayEqualsCompat(plain, CipherFileFormat.decode(encoded, provider))
    }

    @Test
    fun sniff_plainTruncatedMagic_andFullMagic_distinguishable() {
        val provider = FakeDataKeyProvider()
        assertFalse(CipherFileFormat.isEncrypted("[]".encodeToByteArray()))
        // 只差一个字节的 MAGIC 前缀不能被当成密文头
        assertFalse(CipherFileFormat.isEncrypted((CipherFileFormat.MAGIC.dropLast(1)).encodeToByteArray()))
        val encrypted = CipherFileFormat.encode("[]".encodeToByteArray(), provider)
        assertTrue(CipherFileFormat.isEncrypted(encrypted))
        assertFalse(CipherFileFormat.isEncrypted(encrypted.copyOfRange(0, 7)))
    }

    @Test
    fun sniffFile_realFiles_encryptedPlainGhost() {
        val provider = FakeDataKeyProvider()
        val dir = File(System.getProperty("java.io.tmpdir"), "cfft_${System.nanoTime()}").apply { mkdirs() }
        try {
            val plain = File(dir, "plain.json").apply { writeText("[{\"a\":1}]") }
            val enc = File(dir, "enc.json")
            val payload = "x".repeat(70 * 1024).encodeToByteArray()
            CipherFileFormat.encodeToFile(payload, provider, enc)
            assertTrue(CipherFileFormat.isEncryptedFile(enc))
            assertFalse(CipherFileFormat.isEncryptedFile(plain))
            assertFalse("不存在的文件不能报密文", CipherFileFormat.isEncryptedFile(File(dir, "ghost")))
            assertArrayEqualsCompat(payload, CipherFileFormat.decodeFile(enc, provider))
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test
    fun wrongKey_reportsKeyUnavailable_notCorruptCiphertext() {
        val provider = FakeDataKeyProvider()
        val encoded = CipherFileFormat.encode("secret".encodeToByteArray(), provider)
        val dir = File(System.getProperty("java.io.tmpdir"), "cfft2_${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = File(dir, "chat.json")
            file.writeBytes(encoded)
            provider.exists = false
            try {
                CipherFileFormat.decodeFile(file, provider)
                fail("密钥丢失竟然解开了")
            } catch (expected: Exception) {
                assertTrue(
                    "密钥档必须报 KeyUnavailable（毁数据判据走不到它），实报 ${expected.javaClass.simpleName}",
                    expected is KeyUnavailableException
                )
                assertTrue("文件必须还在盘上（不许被判损坏吃掉）", file.exists())
            }
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test
    fun truncatedOrBadLengthField_reportsCorruptCiphertext() {
        val provider = FakeDataKeyProvider()
        val good = CipherFileFormat.encode("payload".encodeToByteArray(), provider)
        try {
            CipherFileFormat.decode(good.copyOfRange(0, good.size - 3), provider)
            fail("截断竟然解开了")
        } catch (expected: CorruptCiphertextException) {
            assertTrue(expected.message!!.isNotBlank())
        }
        // MAGIC 在、长度字段宣称超出剩余字节
        val lying = (CipherFileFormat.MAGIC + "!!!!").encodeToByteArray() + byteArrayOf(0x7F, 0, 0, 0)
        try {
            CipherFileFormat.decode(lying, provider)
            fail("长度字段越界竟然解开了")
        } catch (expected: CorruptCiphertextException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    private fun assertArrayEqualsCompat(expected: ByteArray, actual: ByteArray) {
        assertEquals("字节数不等", expected.size, actual.size)
        assertTrue("内容不等", expected.contentEquals(actual))
    }
}
