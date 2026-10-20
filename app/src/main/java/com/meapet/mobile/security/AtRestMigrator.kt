package com.meapet.mobile.security

import java.io.File

/** API Key 一路的迁移接缝（生产实现=SettingsManager，测试注入内存假档）。 */
interface ApiKeySettings {
    fun peekRawStored(): String

    suspend fun rewriteStored(transform: (String) -> String)

    var atRestEnabled: Boolean
}

enum class MigrationDirection { ENABLE, DISABLE }

/** 迁移被拒（密钥档、写盘失败）——已完成步进在标记里，修好原因可原地续传。 */
class MigrationRejectedException(reason: String) : Exception(reason)

/**
 * 总开关的双向重加密编排（封形 A + 00:17 裁的硬必需通路）。
 *
 * 顺序：**启用** 设置→记忆→聊天、三路都落密文后开关才翻；**还原** 开关最先关、
 * 再 聊天→记忆→设置（设置步把 `k1:` 值解回明文）。两个方向都把"开关与存量不一致"
 * 的窗口放在文件自描述能安全容忍的一侧——读侧从不查开关，中途被杀就是"半转换态"：
 * 各文件各归一态、标记文件记进度、重跑幂等续传，无回滚亦无损。
 *
 * 已知边界（如实留档）：转换协程跑的间隙里，另一路写路径可能按旧开关语义把刚
 * 转换的文件重写回旧态——后果是"开关开、存量明文"这类不一致，不毁数据；
 * [isConsistent] 能查出来，UI 再触发一次即收敛。
 */
class AtRestMigrator(
    private val dir: File,
    private val chatFile: File,
    private val memoryFile: File,
    private val apiKeySettings: ApiKeySettings,
    private val crypto: AtRestCrypto,
) {

    companion object {
        private const val PROGRESS_FILE = "at_rest_migration.json"
        const val STEP_SETTINGS = "settings"
        const val STEP_MEMORY = "memory"
        const val STEP_CHAT = "chat"
        private val STEPS_ENABLE = listOf(STEP_SETTINGS, STEP_MEMORY, STEP_CHAT)
        private val STEPS_DISABLE = listOf(STEP_CHAT, STEP_MEMORY, STEP_SETTINGS)
    }

    /** 遗留进度（崩溃/被杀后 UI 用来提示"上次转换没走完"）。 */
    fun pendingProgress(): MigrationProgress? = readProgress()

    suspend fun enable(): Int = run(MigrationDirection.ENABLE)

    suspend fun disable(): Int = run(MigrationDirection.DISABLE)

    /** 开关与存量是否一致（不一致=转换被打断过，UI 提示"再应用一次"）。 */
    fun isConsistent(): Boolean {
        if (pendingProgress() != null) return false
        val enabled = apiKeySettings.atRestEnabled
        return matchesFile(chatFile, enabled) &&
            matchesFile(memoryFile, enabled) &&
            matchesValue(apiKeySettings.peekRawStored(), enabled)
    }

    /** 不存在的文件不参与判据（没数据就没不一致可言）。 */
    private fun matchesFile(file: File, enabled: Boolean): Boolean =
        !file.isFile || CipherFileFormat.isEncryptedFile(file) == enabled

    /** 未配置（空串）同理不参与——encode 对空串本来就不产密文。 */
    private fun matchesValue(stored: String, enabled: Boolean): Boolean =
        stored.isEmpty() || ValueCipher.isEncoded(stored) == enabled

    // ── 内部 ──────────────────────────────────────────

    private suspend fun run(direction: MigrationDirection): Int {
        val progress = readProgress()
        if (progress != null && progress.direction != direction) {
            throw MigrationRejectedException("存在未完成的反向转换（${progress.direction}），先按原方向续传")
        }
        if (progress == null && direction == MigrationDirection.ENABLE && apiKeySettings.atRestEnabled) {
            throw MigrationRejectedException("常驻加密已是开启态")
        }
        if (progress == null && direction == MigrationDirection.DISABLE && !apiKeySettings.atRestEnabled) {
            return 0 // 没开过就没有可还原的东西（空操作，不是错误）
        }
        var done = progress?.doneSteps ?: emptySet()
        val steps = if (direction == MigrationDirection.ENABLE) STEPS_ENABLE else STEPS_DISABLE
        if (direction == MigrationDirection.DISABLE) {
            apiKeySettings.atRestEnabled = false // 开关先关：还原期间的新写入直接落明文
        }
        writeProgress(direction, done)

        for (step in steps.filterNot { it in done }) {
            runStep(direction, step)
            done += step
            writeProgress(direction, done)
        }
        if (direction == MigrationDirection.ENABLE) {
            // 三路都落密文后才翻；续传时设置步已在 done 集里，这里照样补得上终态
            apiKeySettings.atRestEnabled = true
        }
        runCatching { progressFile().delete() }
        return steps.size
    }

    private suspend fun runStep(direction: MigrationDirection, step: String) {
        val toCipher = direction == MigrationDirection.ENABLE
        val provider = crypto.provider()
        when (step) {
            STEP_SETTINGS -> {
                val stored = apiKeySettings.peekRawStored()
                if (toCipher && !ValueCipher.isEncoded(stored)) {
                    apiKeySettings.rewriteStored { ValueCipher.encode(it, provider) }
                } else if (!toCipher && ValueCipher.isEncoded(stored)) {
                    apiKeySettings.rewriteStored { ValueCipher.decode(it, provider) }
                }
            }
            STEP_MEMORY -> migrateFile(memoryFile, toCipher, "${memoryFile.name}.enc.tmp")
            STEP_CHAT -> migrateFile(chatFile, toCipher, "${chatFile.name}.enc.tmp")
        }
    }

    private fun migrateFile(target: File, toCipher: Boolean, tmpName: String) {
        if (!target.isFile) return
        val encrypted = CipherFileFormat.isEncryptedFile(target)
        if (toCipher && !encrypted) {
            val reason = crypto.tryWrite(target, tmpName, target.readBytes())
            if (reason != null) throw MigrationRejectedException("${target.name} 加密失败: $reason")
        } else if (!toCipher && encrypted) {
            val plain = try {
                crypto.readPlain(target)
            } catch (e: KeyUnavailableException) {
                throw MigrationRejectedException("${target.name} 无法解密（密钥档），还原中止: ${e.message}")
            }
            val tmp = File(target.parentFile, tmpName)
            tmp.writeBytes(plain)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw MigrationRejectedException("${target.name} 原子替换失败")
            }
        }
    }

    private fun AtRestCrypto.provider(): DataKeyProvider =
        (this as ProviderExposing).provider

    private fun progressFile() = File(dir, PROGRESS_FILE)

    private fun readProgress(): MigrationProgress? = runCatching {
        val text = progressFile().readText()
        val direction = MigrationDirection.valueOf(
            Regex("\"direction\":\"(ENABLE|DISABLE)\"").find(text)?.groupValues?.get(1) ?: return null
        )
        val done = Regex("\"(settings|memory|chat)\"").findAll(text).map { it.value.trim('"') }.toSet()
        MigrationProgress(direction, done)
    }.getOrNull()

    private fun writeProgress(direction: MigrationDirection, done: Set<String>) {
        val body = """{"direction":"$direction","done":[${done.joinToString(",") { "\"$it\"" }}]}"""
        progressFile().writeText(body)
    }
}

data class MigrationProgress(val direction: MigrationDirection, val doneSteps: Set<String>)

/** 暴露底层密钥档的加密实现——值级迁移与编排要用同一把钥。 */
interface ProviderExposing {
    val provider: DataKeyProvider
}
