package com.meapet.mobile.chat

import android.util.Log
import com.meapet.mobile.security.AtRestCrypto
import com.meapet.mobile.security.CipherFileFormat
import com.meapet.mobile.security.KeyUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话历史持久化存储。
 *
 * ## 存储策略
 * 与 [com.meapet.mobile.memory.MemoryRepository] 一致：
 * - JSON 文件（`meapet_conversation.json`），临时文件 + rename 保证原子性；
 * - 损坏文件备份为 `.corrupt` 后丢弃，不阻塞后续使用；
 * - [persistAsync] 采用合并写（conflate）：连续多次提交只落盘最新快照，
 *   避免每条消息一次磁盘 IO。
 *
 * ## 常驻加密（双支路）
 * 磁盘形态**看文件自己**（[CipherFileFormat] 自描述），不看开关——开关只决定写侧。
 * 读侧三分：明文=现行为；密文解不开（密钥档）=**不改名不销毁**返回空并报密钥错误；
 * 密文解出但 JSON 坏=走 `.corrupt`。
 *
 * ## 低耦合
 * - 仅操作 [ChatMessage] 数据与文件，不依赖任何业务模块；
 * - 由 [ConversationManager] 在消息变更后调用。
 *
 * @param dir 存储目录（应用 filesDir；JVM 测试传临时目录）
 * @param scope 落盘用协程作用域（应用级；测试可传 TestScope）
 * @param crypto 常驻加密接缝（null=明文时代的全部行为，测试与未启用路径零变化）
 */
class ConversationStore(
    private val dir: File,
    scope: CoroutineScope,
    private val crypto: AtRestCrypto? = null,
) {
    companion object {
        private const val TAG = "ConversationStore"
        private const val FILE_NAME = "meapet_conversation.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private val file: File get() = File(dir, FILE_NAME)

    /**
     * 导出用：回源文件（不存在则 null）。原文透传、不重新序列化——
     * 本类的形态（List<ChatMessage>）一旦变化，旧包仍按当时字节导出的形状存在。
     * 不加锁是安全的：[persist] 走 tmp+rename 原子替换，读到的永远是完整快照。
     */
    fun backupFile(): File? = file.takeIf { it.isFile }

    /**
     * 导入用：按**本类自己的**解析口径判定包内文本是不是合法会话快照。
     * 返回 null = 解不动，调用方必须拒绝这一类，不能让它进到回灌那一步。
     * 回灌走 ConversationManager.replaceAll → 常规落盘（重新序列化），不直接写原始字节：
     * 内存与磁盘由同一条路径决定，才不会一边新一边旧。
     */
    fun parseSnapshot(text: String): List<ChatMessage>? = try {
        json.decodeFromString<List<ChatMessage>>(text)
    } catch (e: Exception) {
        Log.w(TAG, "Import payload not parseable as conversation: ${e.javaClass.simpleName}")
        null
    }

    /** 待落盘快照流。replay=1 + DROP_OLDEST = 天然 conflate，只写最新。 */
    private val pending = MutableSharedFlow<List<ChatMessage>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * 落盘 collector 协程。保留引用以便 [persistAsync] 检测其存活：
     * 传入 scope 一旦被取消，collector 结束，之后所有 emit 都无人消费——
     * 不检测的话数据静默丢失。
     */
    private val persistJob = scope.launch(Dispatchers.IO) {
        pending.collect { snapshot ->
            persist(snapshot)
        }
    }

    /**
     * 从磁盘加载会话历史。启动时调用一次。
     *
     * 文件损坏时备份为 `.corrupt` 并返回空列表。
     */
    suspend fun load(): List<ChatMessage> = mutex.withLock {
        if (!file.exists()) {
            Log.d(TAG, "No persisted conversation found")
            return emptyList()
        }
        val text = try {
            decodeTextLocked(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KeyUnavailableException) {
            // 密钥档：数据仍在盘上，改名 .corrupt 等于判死——只报错，返回空但不毁文件
            Log.e(TAG, "Conversation is encrypted but key is unavailable; file kept untouched", e)
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversation, backing up corrupted file", e)
            backupCorruptedFileLocked()
            return emptyList()
        }
        try {
            val messages = json.decodeFromString<List<ChatMessage>>(text)
            Log.i(TAG, "Loaded ${messages.size} conversation messages from disk")
            messages
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse conversation, backing up corrupted file", e)
            backupCorruptedFileLocked()
            emptyList()
        }
    }

    /** 提交当前会话快照，异步合并落盘（不阻塞调用方）。 */
    fun persistAsync(snapshot: List<ChatMessage>) {
        // scope 已取消时 collector 不在，emit 会静默丢弃——至少告警暴露问题
        if (!persistJob.isActive) {
            Log.w(TAG, "Persist collector inactive (scope cancelled?); dropping snapshot of ${snapshot.size} messages")
            return
        }
        pending.tryEmit(snapshot)
    }

    /** 同步落盘一份快照（persistAsync 的底层实现；测试可直接调用）。 */
    suspend fun persist(snapshot: List<ChatMessage>) {
        mutex.withLock { persistLocked(snapshot) }
    }

    // ── 内部 ──────────────────────────────────────────

    /** 必须在持有 [mutex] 时调用。 */
    private fun persistLocked(snapshot: List<ChatMessage>) {
        try {
            val text = json.encodeToString(snapshot)
            dir.mkdirs()
            val crypto = this.crypto
            if (crypto != null && crypto.enabled) {
                // 密文写自带原子替换；tmp 名分态，避免与明文路共用互踩
                val reason = crypto.tryWrite(file, "$FILE_NAME.enc.tmp", text.encodeToByteArray())
                if (reason != null) {
                    Log.e(TAG, "Failed to persist encrypted conversation: $reason")
                }
                return
            }
            // 先写临时文件再 rename，避免写入中途崩溃导致文件截断
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    Log.e(TAG, "Failed to replace conversation file")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist conversation", e)
        }
    }

    /** 读侧解码：明文直读；密文走文件格（**密钥丢失抛 [KeyUnavailableException]**）。 */
    private fun decodeTextLocked(target: File): String {
        val encrypted = CipherFileFormat.isEncryptedFile(target)
        val crypto = this.crypto
        if (encrypted && crypto == null) {
            // 接线缺席也要拒——按明文硬读会撞进 .corrupt 判死路
            throw KeyUnavailableException("会话文件是密文，但本机未接常驻解密")
        }
        if (encrypted) return requireNotNull(crypto).readPlain(target).decodeToString()
        return target.readText()
    }

    /** 将损坏的持久化文件挪到 `.corrupt` 备份，防止每次启动重复解析失败。 */
    private fun backupCorruptedFileLocked() {
        try {
            val backup = File(dir, "$FILE_NAME.corrupt")
            if (backup.exists()) backup.delete()
            if (!file.renameTo(backup)) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to back up corrupted conversation file", e)
        }
    }
}
