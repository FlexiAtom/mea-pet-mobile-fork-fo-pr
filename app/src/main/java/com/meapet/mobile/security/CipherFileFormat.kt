package com.meapet.mobile.security

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher

/**
 * 常驻密文的文件格：`MAGIC | [u32BE 段长][12B IV][密文+GCM tag] × n`。
 *
 * 自描述是硬要求——开关状态与磁盘形态各看文件本身就能判定，迁移才有
 * "逐文件幂等续传"的立足点；明文快照（两店都是整表 JSON 数组，首字符 `[`）
 * 与 MAGIC 不可能撞车，所以嗅探不需要任何旁路标记。
 */
object CipherFileFormat {

    const val MAGIC: String = "MPETENC1"
    private val MAGIC_BYTES = MAGIC.encodeToByteArray()
    private const val HEADER_BYTES = 8
    private const val MIN_CHUNK = AesGcmEngine.IV_BYTES + AesGcmEngine.TAG_BYTES

    /** 明文分块上限：GCM 单调用不越 2^32−256 字节的天花板，解侧也能流式。 */
    private const val CHUNK_BYTES = 64 * 1024

    fun isEncrypted(headBytes: ByteArray): Boolean {
        if (headBytes.size < MAGIC_BYTES.size) return false
        for (i in MAGIC_BYTES.indices) if (headBytes[i] != MAGIC_BYTES[i]) return false
        return true
    }

    /** 只读文件头做嗅探——损坏分流不该为一行 MAGIC 把整个文件吞进内存。 */
    fun isEncryptedFile(file: File): Boolean {
        if (!file.isFile) return false
        val head = ByteArray(HEADER_BYTES)
        return runCatching {
            file.inputStream().use { input ->
                var read = 0
                while (read < HEADER_BYTES) {
                    val n = input.read(head, read, HEADER_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                read == HEADER_BYTES && isEncrypted(head)
            }
        }.getOrDefault(false)
    }

    fun encode(plain: ByteArray, provider: DataKeyProvider): ByteArray {
        val out = ByteArrayOutputStream(plain.size + HEADER_BYTES + MIN_CHUNK + 16)
        writeEncoded(plain, provider, out)
        return out.toByteArray()
    }

    fun decode(data: ByteArray, provider: DataKeyProvider): ByteArray {
        if (!isEncrypted(data)) throw CorruptCiphertextException("密文头缺失（非 $MAGIC 格式）")
        return decryptBody(data, HEADER_BYTES, data.size.toLong(), provider)
    }

    fun encodeToFile(plain: ByteArray, provider: DataKeyProvider, target: File) {
        target.outputStream().use { out -> writeEncoded(plain, provider, out) }
    }

    /** 解整个文件到内存字节（导出解密与损坏分流用）。 */
    fun decodeFile(file: File, provider: DataKeyProvider): ByteArray {
        val length = file.length()
        if (length < HEADER_BYTES) throw CorruptCiphertextException("密文文件过短: $length 字节")
        return decryptBody(file.readBytes(), HEADER_BYTES, length, provider)
    }

    // ── 内部 ──────────────────────────────────────────

    /**
     * 每块都向 provider 取新加密器——AndroidKeyStore 的 GCM 密钥拒绝同一 Cipher
     * 实例第二次 doFinal（"Must use either different key or iv"，本机真机同款约束），
     * 复用实例在多块文件上必炸。
     */
    private fun writeEncoded(plain: ByteArray, provider: DataKeyProvider, out: OutputStream) {
        out.write(MAGIC_BYTES)
        var offset = 0
        do {
            val size = minOf(CHUNK_BYTES, plain.size - offset)
            val cipher = provider.encryptCipher()
            val chunk = cipher.doFinal(plain, offset, size)
            writeLengthPrefixed(out, cipher.iv, chunk)
            offset += size
        } while (offset < plain.size)
        // 空明文也至少一个块（chunk=纯 tag），解侧无需特判"零块=空文件"
    }

    private fun writeLengthPrefixed(out: OutputStream, iv: ByteArray, chunk: ByteArray) {
        require(iv.size == AesGcmEngine.IV_BYTES) { "GCM IV 长度异常: ${iv.size}" }
        val total = iv.size + chunk.size
        out.write(total ushr 24)
        out.write(total ushr 16)
        out.write(total ushr 8)
        out.write(total)
        out.write(iv)
        out.write(chunk)
    }

    private fun decryptBody(bytes: ByteArray, from: Int, fileLength: Long, provider: DataKeyProvider): ByteArray {
        var pos = from
        val out = ByteArrayOutputStream()
        while (pos < bytes.size) {
            if (bytes.size - pos < 4) throw CorruptCiphertextException("块长度字段被截断")
            val total = (bytes[pos].toInt() and 0xFF shl 24) or
                (bytes[pos + 1].toInt() and 0xFF shl 16) or
                (bytes[pos + 2].toInt() and 0xFF shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            if (total < MIN_CHUNK || total > bytes.size - pos - 4) {
                throw CorruptCiphertextException("块长度 $total 越界（剩余 ${bytes.size - pos - 4}）")
            }
            val iv = bytes.copyOfRange(pos + 4, pos + 4 + AesGcmEngine.IV_BYTES)
            val chunk = bytes.copyOfRange(pos + 4 + AesGcmEngine.IV_BYTES, pos + 4 + total)
            pos += 4 + total
            val plain = try {
                provider.decryptCipher(iv).doFinal(chunk)
            } catch (badTag: AEADBadTagException) {
                throw KeyUnavailableException("密文校验失败（密钥已丢失或被更换，数据仍在盘上，$fileLength 字节）", badTag)
            } catch (still: Exception) {
                // 密钥本来就不在场（取钥即败）归密钥档；否则按文件损坏档
                if (!provider.keyExists()) {
                    throw KeyUnavailableException("Keystore 密钥不在场，$fileLength 字节密文无法解开", still)
                }
                throw classify(still, fileLength)
            }
            out.write(plain)
        }
        return out.toByteArray()
    }

    /** 取钥/解码的其余异常分流：BadPadding 归密钥档，其余归文件损坏档。 */
    private fun classify(failure: Throwable, fileLength: Long): Exception = when {
        failure is BadPaddingException ->
            KeyUnavailableException("密文填充校验失败（密钥档），数据仍在盘上，$fileLength 字节", failure)
        failure is CorruptCiphertextException -> failure
        else -> CorruptCiphertextException("密文解密异常: ${failure.javaClass.simpleName}", failure)
    }
}
