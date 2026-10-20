package com.meapet.mobile.security

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** JVM 测试的假密钥档：软件 AES，接口与生产同模式；`exists=false` 模拟密钥丢失。 */
class FakeDataKeyProvider : DataKeyProvider {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var exists = true

    override fun keyExists(): Boolean = exists

    override fun encryptCipher(): Cipher =
        Cipher.getInstance(AesGcmEngine.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    override fun decryptCipher(iv: ByteArray): Cipher {
        check(exists) { "密钥不在场" }
        return Cipher.getInstance(AesGcmEngine.TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AesGcmEngine.TAG_BITS, iv))
        }
    }
}

/** 开关位可变的生产同款组装（providerBacked），store 测试注入这一份。 */
fun fakeCrypto(provider: FakeDataKeyProvider, enabled: () -> Boolean): AtRestCrypto =
    AtRestCrypto.providerBacked(enabled, provider)
