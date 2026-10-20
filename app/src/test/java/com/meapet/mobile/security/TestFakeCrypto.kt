package com.meapet.mobile.security

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * JVM 测试的假密钥档：软件 AES，接口与生产同模式。
 *
 * `exists=false` 模拟"钥读不出来"；`key=null` 模拟真机初装（Keystore 里还没有这把钥）——
 * 后者只能靠 [ensureKey] 补出来，和 [KeystoreDataKeyProvider] 一样不会自愈。
 */
class FakeDataKeyProvider : DataKeyProvider {
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var exists = true

    override fun keyExists(): Boolean = exists && key != null

    override fun ensureKey(): Boolean {
        if (key != null) return false
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return true
    }

    /** 模拟真机初装：Keystore 里还没有这把钥（盘上也没有密文，与"丢了钥"不同）。 */
    fun freshInstall() { key = null }

    override fun encryptCipher(): Cipher {
        check(keyExists()) { "密钥不在场，拒绝加密写盘" }
        return Cipher.getInstance(AesGcmEngine.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher {
        check(keyExists()) { "密钥不在场" }
        return Cipher.getInstance(AesGcmEngine.TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AesGcmEngine.TAG_BITS, iv))
        }
    }
}

/** 开关位可变的生产同款组装（providerBacked），store 测试注入这一份。 */
fun fakeCrypto(provider: FakeDataKeyProvider, enabled: () -> Boolean): AtRestCrypto =
    AtRestCrypto.providerBacked(enabled, provider)
