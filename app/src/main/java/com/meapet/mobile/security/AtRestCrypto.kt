package com.meapet.mobile.security

import java.io.File

/**
 * 存储层用到的常驻加密接缝。生产实现装配 Keystore provider 与总开关；
 * JVM 测试注入软件 AES 的假档——双支路与分流判据在测试里走真码。
 */
interface AtRestCrypto {
    /** 写侧：当前是否产出密文（总开关）。 */
    val enabled: Boolean

    fun encryptCipher(): javax.crypto.Cipher

    fun decryptCipher(iv: ByteArray): javax.crypto.Cipher

    /** 写侧失败的可读原因（磁盘错、Keystore 异常）；null=写成功。 */
    fun tryWrite(target: File, tmpName: String, plain: ByteArray): String?

    /** 读侧：嗅探→解码。**密钥丢失抛 [KeyUnavailableException]**——调用方绝不可按损坏处置。 */
    fun readPlain(file: File): ByteArray

    /** 嗅探档（迁移编排与导出解密用）。 */
    fun isCipherFile(file: File): Boolean = CipherFileFormat.isEncryptedFile(file)

    companion object {
        fun providerBacked(enabled: () -> Boolean, provider: DataKeyProvider): AtRestCrypto =
            object : AtRestCrypto {
                override val enabled: Boolean get() = enabled()

                override fun encryptCipher() = provider.encryptCipher()

                override fun decryptCipher(iv: ByteArray) = provider.decryptCipher(iv)

                override fun tryWrite(target: File, tmpName: String, plain: ByteArray): String? {
                    return try {
                        val tmp = File(target.parentFile, tmpName)
                        CipherFileFormat.encodeToFile(plain, provider, tmp)
                        if (!tmp.renameTo(target)) {
                            target.delete()
                            if (!tmp.renameTo(target)) return "原子替换失败"
                        }
                        null
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                }

                override fun readPlain(file: File): ByteArray = CipherFileFormat.decodeFile(file, provider)
            }
    }
}
