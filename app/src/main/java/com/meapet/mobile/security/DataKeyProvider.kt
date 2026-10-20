package com.meapet.mobile.security

import javax.crypto.Cipher

/**
 * 常驻加密的密钥档位抽象。
 *
 * 生产实现走 AndroidKeyStore（密钥材料不出 TEE）；JVM 测试用同模式的软件实现注入——
 * 两态只在这一层分叉，文件格与判定逻辑测试里全部走真码。
 */
interface DataKeyProvider {
    fun encryptCipher(): Cipher

    fun decryptCipher(iv: ByteArray): Cipher

    /** 密钥是否在场（已生成且可读）。不可用 ≠ 数据损坏，调用方据此分流报错。 */
    fun keyExists(): Boolean

    /**
     * 幂等确保密钥在场（缺失时生成，返回是否本次新造）。
     *
     * 只有**启用方向**该调它，且调用方必须先确认盘上没有旧密文——否则新钥会把旧数据
     * 变成永远解不开的孤儿。还原/读取方向一律按"不在场"报错。
     */
    fun ensureKey(): Boolean
}

/** 密文自带 MAGIC 但解不开（密钥丢失/被换/ROM 重置）。数据仍在盘上，禁止按损坏处置。 */
class KeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 密文文件本身不完整或不合格（截断、长度字段坏）。与密钥档分开报，处置路径不同。 */
class CorruptCiphertextException(message: String, cause: Throwable? = null) : Exception(message, cause)
