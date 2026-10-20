package com.meapet.mobile.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** API Key 值级密文（k1: 自描述）的编解码与分流。 */
class ValueCipherTest {

    @Test
    fun roundtrip_andPlaintextPassesThroughBothWays() {
        val provider = FakeDataKeyProvider()
        val secret = "sk-测-1234567890abcdef"
        val encoded = ValueCipher.encode(secret, provider)
        assertTrue("编码后必须带前缀", ValueCipher.isEncoded(encoded))
        assertNotEquals(secret, encoded)
        assertEquals(secret, ValueCipher.decode(encoded, provider))
        // 明文存量 decode 原样进出（迁移可反复调）
        assertEquals(secret, ValueCipher.decode(secret, provider))
        assertEquals("plain-value", ValueCipher.decode("plain-value", provider))
        assertEquals("", ValueCipher.encode("", provider))
    }

    @Test
    fun encodeTwice_doesNotDoubleWrap() {
        val provider = FakeDataKeyProvider()
        val once = ValueCipher.encode("sk-abc", provider)
        assertEquals(once, ValueCipher.encode(once, provider))
    }

    @Test
    fun wrongKey_reportsKeyUnavailable() {
        val provider = FakeDataKeyProvider()
        val encoded = ValueCipher.encode("sk-abc", provider)
        provider.exists = false
        try {
            ValueCipher.decode(encoded, provider)
            fail("密钥丢失竟然解开了")
        } catch (expected: KeyUnavailableException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun garbageBody_reportsCorruptNotSilentEmpty() {
        val provider = FakeDataKeyProvider()
        try {
            ValueCipher.decode("k1:@@@不是base64@@@", provider)
            fail("坏 base64 竟然解开了")
        } catch (expected: CorruptCiphertextException) {
            assertTrue(expected.message!!.isNotBlank())
        }
        try {
            ValueCipher.decode("k1:aGVsbG8=", provider) // "hello"，短于 iv+tag
            fail("过短密文竟然解开了")
        } catch (expected: CorruptCiphertextException) {
            assertTrue(expected.message!!.isNotBlank())
        }
        assertFalse(ValueCipher.isEncoded(""))
    }
}
