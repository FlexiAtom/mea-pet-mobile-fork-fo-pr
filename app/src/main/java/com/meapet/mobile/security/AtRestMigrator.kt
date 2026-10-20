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
 * 转换的文件重写回旧态，或让迁移读到的旧快照盖掉一次刚落的更新——既有数据不损坏，
 * 最新快照仍在内层管理器的内存里（若恰在下次落盘前被杀，丢的只是那一笔新写入）。
 * [isConsistent] 能查出形态不一致，UI 再触发一次即收敛。
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

    suspend fun enable(onStep: (String) -> Unit = {}): Int = run(MigrationDirection.ENABLE, onStep)

    suspend fun disable(onStep: (String) -> Unit = {}): Int = run(MigrationDirection.DISABLE, onStep)

    /**
     * 密钥不可解时的明示通道：**旧数据作废**（封形 A 的"故障格"处置，不做第二套密码栈、不假装能救）。
     * 删掉两路密文文件、清掉解不开的 API Key、关掉总开关、清进度标记。返回作废的条目数。
     *
     * 只有调用方拿到用户点名确认后才该走到这里——它不可逆。
     */
    suspend fun discardEncryptedData(): Int {
        var discarded = 0
        listOf(chatFile, memoryFile).forEach { file ->
            if (file.isFile && CipherFileFormat.isEncryptedFile(file)) {
                if (file.delete()) discarded++ else throw MigrationRejectedException("${file.name} 删不动，旧数据仍在盘上")
            }
        }
        if (ValueCipher.isEncoded(apiKeySettings.peekRawStored())) {
            apiKeySettings.rewriteStored { "" }
            discarded++
        }
        apiKeySettings.atRestEnabled = false
        runCatching { progressFile().delete() }
        return discarded
    }

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

    /** 盘上有没有任何密文形态（文件头或 `k1:` 值）——空操作判据用。 */
    private fun anythingCipherish(): Boolean =
        CipherFileFormat.isEncryptedFile(chatFile) ||
            CipherFileFormat.isEncryptedFile(memoryFile) ||
            ValueCipher.isEncoded(apiKeySettings.peekRawStored())

    // ── 内部 ──────────────────────────────────────────

    private suspend fun run(direction: MigrationDirection, onStep: (String) -> Unit): Int {
        val progress = readProgress()
        if (progress != null && progress.direction != direction) {
            throw MigrationRejectedException("存在未完成的反向转换（${progress.direction}），先按原方向续传")
        }
        // 同方向重跑是幂等的（逐文件各归一态），所以"开关已经是这个态"不是拒绝理由——
        // 不一致收敛（UI 的"再应用一次"）走的就是这条路。只有"关着且盘上没有密文"才是空操作。
        if (progress == null && direction == MigrationDirection.DISABLE &&
            !apiKeySettings.atRestEnabled && !anythingCipherish()
        ) {
            return 0
        }
        // 启用方向必须先有钥：真机初装时 Keystore 里根本没有这把钥（encryptCipher 会直接 require 失败），
        // 而 JVM 假 provider 自带来钥，把这一格盖了整整一轮。
        if (direction == MigrationDirection.ENABLE) {
            val provider = crypto.provider()
            if (!provider.keyExists() && anythingCipherish()) {
                throw MigrationRejectedException(
                    "密钥不在场但盘上已有密文；生成新钥会把旧数据变成解不开的孤儿，先走「重置加密（旧数据作废）」"
                )
            }
            try {
                provider.ensureKey()
            } catch (e: Exception) {
                throw MigrationRejectedException("Keystore 密钥生成失败：${e.message ?: e.javaClass.simpleName}")
            }
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
            onStep(step)
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

    /** 标记目录在真机上是"首次转换时才存在"的（cacheDir 下我们自己的子目录），所以这里必须自己造。 */
    private fun writeProgress(direction: MigrationDirection, done: Set<String>) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw MigrationRejectedException("进度标记目录建不出来（${dir.path}），转换不启动")
        }
        val body = """{"direction":"$direction","done":[${done.joinToString(",") { "\"$it\"" }}]}"""
        progressFile().writeText(body)
    }
}

data class MigrationProgress(val direction: MigrationDirection, val doneSteps: Set<String>)

/** 暴露底层密钥档的加密实现——值级迁移与编排要用同一把钥。 */
interface ProviderExposing {
    val provider: DataKeyProvider
}
