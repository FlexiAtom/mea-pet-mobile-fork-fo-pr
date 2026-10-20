package com.meapet.mobile.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 生产密钥档：AndroidKeyStore 里一把 AES-256-GCM 密钥，硬件绑定、零口令（封形 A）。
 *
 * 密钥不可导出——整包拷走 filesDir 拿不到它；代价是"重打包/重置=旧密文解不开"，
 * 由"重置加密=旧数据作废"的明示路径与解密还原通路承接（父案 00:17/00:35 两裁）。
 * 生成只由使用方在**确认盘上没有旧密文**之后显式调 [ensureKey] 完成——重复生成会造出
 * 同一别名下的新钥、旧密文随之报废，所以读取路径永不生成，取不到钥就如实报不在场。
 */
class KeystoreDataKeyProvider(private val alias: String = DEFAULT_ALIAS) : DataKeyProvider {

    companion object {
        const val DEFAULT_ALIAS = "meapet_at_rest_aes256_gcm"
        private const val KEYSTORE = "AndroidKeyStore"
    }

    private fun secretKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        store.getKey(alias, null) as? SecretKey
    }.getOrNull()

    override fun keyExists(): Boolean = secretKey() != null

    /** 幂等确保密钥在场；返回是否"本次新造"（UI 迁移提示用）。 */
    override fun ensureKey(): Boolean {
        if (keyExists()) return false
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKey()
        return true
    }

    override fun encryptCipher(): Cipher =
        Cipher.getInstance(AesGcmEngine.TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, requireNotNull(secretKey()) { "Keystore 密钥不在场，拒绝加密写盘" })
        }

    override fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(AesGcmEngine.TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                requireNotNull(secretKey()) { "Keystore 密钥不在场，无法解密" },
                GCMParameterSpec(AesGcmEngine.TAG_BITS, iv)
            )
        }
}
