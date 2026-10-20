package com.meapet.mobile.security

import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * 值级密文（API Key 一路，09:40 覆盖裁）：`k1:` + base64(iv | 密文+GCM tag)。
 *
 * 值自描述——读侧不查开关，解不开才报密钥档；明文值原样进出，
 * 迁移编排因此可以对同一个键反复调 encode 而不出双.wrap。
 * 诚实边界：DataStore 的 protobuf 文件本体仍明文，本案裁的是"密钥值不落明文"。
 */
object ValueCipher {
    private const val PREFIX = "k1:"

    fun isEncoded(value: String): Boolean = value.startsWith(PREFIX)

    /** 已带前缀原样返回（幂等）；开关键写侧与迁移共用。 */
    fun encode(plain: String, provider: DataKeyProvider): String {
        if (plain.isEmpty() || isEncoded(plain)) return plain
        val cipher = provider.encryptCipher()
        val body = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
        return PREFIX + Base64.getEncoder().encodeToString(body)
    }

    /** 无前缀=明文时代存量，原样返回；带前缀但解不开抛 [KeyUnavailableException]（不静默吞值）。 */
    fun decode(value: String, provider: DataKeyProvider): String {
        if (!isEncoded(value)) return value
        val body = runCatching { Base64.getDecoder().decode(value.removePrefix(PREFIX)) }
            .getOrElse { throw CorruptCiphertextException("值级密文 base64 解不动", it) }
        if (body.size < AesGcmEngine.IV_BYTES + AesGcmEngine.TAG_BYTES) {
            throw CorruptCiphertextException("值级密文过短: ${body.size} 字节")
        }
        val iv = body.copyOfRange(0, AesGcmEngine.IV_BYTES)
        val payload = body.copyOfRange(AesGcmEngine.IV_BYTES, body.size)
        val plain = try {
            provider.decryptCipher(iv).doFinal(payload)
        } catch (badTag: AEADBadTagException) {
            throw KeyUnavailableException("API Key 密文校验失败（密钥已丢失或被更换）", badTag)
        } catch (still: Exception) {
            if (!provider.keyExists()) throw KeyUnavailableException("Keystore 密钥不在场，API Key 无法解密", still)
            throw CorruptCiphertextException("值级密文解密异常: ${still.javaClass.simpleName}", still)
        }
        return plain.decodeToString()
    }
}
