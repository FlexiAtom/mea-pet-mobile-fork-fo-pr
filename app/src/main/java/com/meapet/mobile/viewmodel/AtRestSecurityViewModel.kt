package com.meapet.mobile.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.security.AtRestMigrator
import com.meapet.mobile.security.BootloaderTrust
import com.meapet.mobile.security.CipherFileFormat
import com.meapet.mobile.security.MigrationDirection
import com.meapet.mobile.security.MigrationProgress
import com.meapet.mobile.security.MigrationRejectedException
import com.meapet.mobile.security.TrustReport
import com.meapet.mobile.security.TrustVerdict
import com.meapet.mobile.security.ValueCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 常驻加密的页面状态：门控读数 + 开关 + 转换进度 + 异常格。 */
data class AtRestUiState(
    val trust: TrustReport? = null,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    /** 本次转换已完成的步数（跑完即归位，不给"卡在 3/3"的假进度）。 */
    val doneSteps: Int = 0,
    val totalSteps: Int = TOTAL_STEPS,
    val lastStep: String? = null,
    /** 上次被杀留下的未完成转换（UI 给"继续"，不猜它想开还是想关）。 */
    val leftover: MigrationProgress? = null,
    /** 开关与存量不一致（转换被打断过、或另一路写回了旧态）。 */
    val inconsistent: Boolean = false,
    /** 磁盘上有密文，但本机 Keystore 里那把钥不在场——只有这一格通向"重置=旧数据作废"。 */
    val keyMissing: Boolean = false,
    val notice: String? = null,
) {
    companion object {
        const val TOTAL_STEPS = 3
    }
}

/**
 * 「本机数据常驻加密」节（设置 → 数据与备份）。
 *
 * 只做编排与呈现，不含密码学：门控读数取自 [BootloaderTrust]（设备自报，本 VM 不替它背书，
 * 文案同口径），转换动作全部交给 [AtRestMigrator]。密钥档失败与"删不动"都如实报给用户，
 * 不静默重试、不降级成明文硬读。
 */
class AtRestSecurityViewModel(application: Application) : AndroidViewModel(application) {

    private val container = MeaPetApplication.from(application)
    private val migrator: AtRestMigrator = container.atRestMigrator

    private val _state = MutableStateFlow(AtRestUiState())
    val state: StateFlow<AtRestUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 进页/动作后重读一遍真值（门控、开关、进度、一致性、密钥在场）。 */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val trust = BootloaderTrust.readFromDevice().also {
                    Log.i(TAG, "门控读数 ${it.verdict} · ${it.detail}")
                }
                val enabled = container.settingsManager.isAtRestEncryptionEnabled()
                AtRestSnapshot(
                    trust = trust,
                    enabled = enabled,
                    leftover = migrator.pendingProgress(),
                    inconsistent = !migrator.isConsistent(),
                    keyMissing = cipherishButKeyGone(),
                )
            }
            _state.update {
                it.copy(
                    trust = snapshot.trust,
                    enabled = snapshot.enabled,
                    leftover = snapshot.leftover,
                    inconsistent = snapshot.inconsistent,
                    keyMissing = snapshot.keyMissing,
                    busy = false,
                    doneSteps = 0,
                    lastStep = null,
                )
            }
        }
    }

    /** 开关动作：门控不放行就根本不该被点（UI 已置灰），这里再兜一道不静默。 */
    fun setEnabled(on: Boolean) {
        val current = _state.value
        if (current.busy) return
        if (current.trust?.verdict != TrustVerdict.TRUSTED) {
            _state.update { it.copy(notice = "本机引导状态不可信或报不出，常驻加密不提供（详见上方读数）") }
            return
        }
        if (on == current.enabled) return
        launchMigration(if (on) MigrationDirection.ENABLE else MigrationDirection.DISABLE)
    }

    /** 续传上次没走完的转换（方向按标记文件，不由 UI 猜）。 */
    fun resumeLeftover() {
        val direction = _state.value.leftover?.direction ?: return
        if (_state.value.busy) return
        launchMigration(direction)
    }

    /**
     * 开关与存量不一致时的收敛：按**开关现状**再跑一次同方向。
     * 逐文件幂等，已经到位的那些不动，缺的那些补上——所以这不是"重来"，是"补齐"。
     */
    fun reconcile() {
        if (_state.value.busy) return
        launchMigration(if (_state.value.enabled) MigrationDirection.ENABLE else MigrationDirection.DISABLE)
    }

    /** 密钥不可解时的作废通道——**不可逆**，UI 必须先拿到用户点名确认。 */
    fun discardEncryptedData() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { migrator.discardEncryptedData() } }
            outcome.onSuccess { count ->
                _state.update { it.copy(busy = false, notice = if (count == 0) "没有可作废的密文，开关已关" else "已作废 $count 项解不开的旧数据，开关关闭") }
                refresh()
            }.onFailure { error -> _state.update { it.copy(busy = false, notice = error.readable()) } }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun launchMigration(direction: MigrationDirection) {
        _state.update { it.copy(busy = true, notice = null, doneSteps = 0, lastStep = null, totalSteps = AtRestUiState.TOTAL_STEPS) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val onStep: (String) -> Unit = { step ->
                        _state.update { it.copy(doneSteps = it.doneSteps + 1, lastStep = stepLabel(step)) }
                    }
                    if (direction == MigrationDirection.ENABLE) migrator.enable(onStep) else migrator.disable(onStep)
                }
            }
            outcome.onSuccess {
                _state.update { it.copy(notice = if (direction == MigrationDirection.ENABLE) "已开启：聊天、记忆与 API Key 落密文（壁纸与导出包不在范围内）" else "已还原：本机数据回到明文") }
            }.onFailure { throwable ->
                _state.update { it.copy(notice = throwable.readable()) }
            }
            refresh()
        }
    }

    /** 磁盘上有密文（文件或 `k1:` 值）而 Keystore 那把钥不在场。 */
    private fun cipherishButKeyGone(): Boolean {
        if (container.atRestDataKeyProvider.keyExists()) return false
        val files = listOfNotNull(
            container.conversationStore.backupFile(),
            container.memoryRepository.backupFile(),
        )
        return files.any { CipherFileFormat.isEncryptedFile(it) } ||
            ValueCipher.isEncoded(container.settingsManager.peekApiKeyRawStored())
    }

    private fun Throwable.readable(): String = when (this) {
        is MigrationRejectedException -> "转换中止：${message}"
        else -> message ?: javaClass.simpleName
    }

    private companion object {
        const val TAG = "AtRestSecurity"

        /** 步名 → 给用户看的一格字。设置步说的是 API Key，不是整张设置表——别夸大范围。 */
        fun stepLabel(step: String): String = when (step) {
            AtRestMigrator.STEP_SETTINGS -> "API Key"
            AtRestMigrator.STEP_MEMORY -> "记忆"
            AtRestMigrator.STEP_CHAT -> "聊天"
            else -> step
        }
    }
}

private data class AtRestSnapshot(
    val trust: TrustReport,
    val enabled: Boolean,
    val leftover: MigrationProgress?,
    val inconsistent: Boolean,
    val keyMissing: Boolean,
)
