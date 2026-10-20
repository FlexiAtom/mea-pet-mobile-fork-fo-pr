package com.meapet.mobile.security

import android.annotation.SuppressLint
import android.util.Log

/** 门控环的三档读数：可信 / 不可信（已解锁或谎报）/ 报不出。 */
enum class TrustVerdict { TRUSTED, UNTRUSTED, UNREADABLE }

/** [verdict] 的返回：判档 + 给 UI 与日志看的原始读数（永不只报"不行"不说凭什么）。 */
data class TrustReport(val verdict: TrustVerdict, val detail: String)

/**
 * BL 锁状态探针——封形 A 的"决定给不给"环（人工裁决 00:14/00:35：已解 BL 与假死一律不提供，
 * 无"仍要开"；成因不可分，统一收口为"不可信→不给"）。
 *
 * 判据：三个引导 prop **只要报得出就都必须说"锁着"**；一个都读不到=报不出=不可信。
 *
 * 诚实边界（同档写进 UI 文案）：prop 是 ROM **自报**的。第二环（`KeyInfo` 安全档位 /
 * 密钥证明链）本机 01:24 实测应用侧不可得（反射 `NoSuchMethodException`、证明链 null），
 * 所以这一环只能证明"这台设备怎么说自己"，**我们无法替它背书**；锁定机器的谎报在应用侧证不出来。
 */
object BootloaderTrust {

    private const val TAG = "BootloaderTrust"

    /** 键 → 视为"锁着"的取值集合。 */
    private val LOCKED: List<Pair<String, Set<String>>> = listOf(
        "ro.boot.flash.locked" to setOf("1"),
        "ro.boot.verifiedbootstate" to setOf("green"),
        "ro.boot.vbmeta.device_state" to setOf("locked"),
    )

    /** 纯判档（JVM 单测直打这一格）：[read] 返回 null/空白=这台机器没这个键。 */
    fun verdict(read: (String) -> String?): TrustReport {
        val readings = LOCKED.mapNotNull { (key, allowed) ->
            val value = read(key)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Triple(key, value, value in allowed)
        }
        if (readings.isEmpty()) {
            return TrustReport(TrustVerdict.UNREADABLE, "引导状态键一个都读不到（ROM 未提供或应用无权读）")
        }
        val detail = readings.joinToString(" ") { (key, value, _) -> "$key=$value" }
        val verdict = if (readings.all { it.third }) TrustVerdict.TRUSTED else TrustVerdict.UNTRUSTED
        return TrustReport(verdict, detail)
    }

    /** 生产读数：反射 `android.os.SystemProperties`（公开 SDK 不暴露 prop 读；可用性由真机验收实证）。 */
    @SuppressLint("PrivateApi")
    fun readFromDevice(): TrustReport {
        val type = runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
        val getter = runCatching { type?.getMethod("get", String::class.java) }.getOrNull()
        if (getter == null) {
            Log.w(TAG, "android.os.SystemProperties#get 取不到，门控按报不出处置")
            return verdict { null }
        }
        return verdict { key -> runCatching { getter.invoke(null, key) as? String }.getOrNull() }
    }
}
