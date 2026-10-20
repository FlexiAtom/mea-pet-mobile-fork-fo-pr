package com.meapet.mobile.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 门控判档（封形 A 的"决定给不给"环）——纯函数，读数由调用方注入。 */
class BootloaderTrustTest {

    /** 只列出这台机器"有"的键；没列出的键按读不到处理。 */
    private fun reading(vararg pairs: Pair<String, String?>): (String) -> String? =
        { key -> pairs.firstOrNull { it.first == key }?.second }

    private val flash = "ro.boot.flash.locked"
    private val verified = "ro.boot.verifiedbootstate"
    private val vbmeta = "ro.boot.vbmeta.device_state"

    @Test
    fun allLockedReadings_areTrusted() {
        val report = BootloaderTrust.verdict(reading(flash to "1", verified to "green", vbmeta to "locked"))
        assertEquals(TrustVerdict.TRUSTED, report.verdict)
        listOf("flash.locked=1", "verifiedbootstate=green", "device_state=locked").forEach {
            assertTrue("读数要原样给出去: $it", report.detail.contains(it))
        }
    }

    @Test
    fun unlockedAnyRing_isUntrusted_withTheOffendingValueVisible() {
        listOf(
            reading(flash to "0", verified to "green", vbmeta to "locked"),
            reading(flash to "1", verified to "orange", vbmeta to "locked"),
            reading(flash to "1", verified to "green", vbmeta to "unlocked"),
        ).forEach { read ->
            val report = BootloaderTrust.verdict(read)
            assertEquals(TrustVerdict.UNTRUSTED, report.verdict)
        }
        // 谎报只报得出"不是锁着档"，具体是哪一格说的必须可见
        val orange = BootloaderTrust.verdict(reading(verified to "orange"))
        assertTrue(orange.detail.contains("orange"))
    }

    @Test
    fun nothingReadable_isUnreadable_notTrustedByDefault() {
        val report = BootloaderTrust.verdict(reading(flash to null, verified to null, vbmeta to null))
        assertEquals(TrustVerdict.UNREADABLE, report.verdict)
        // 空白值等同读不到（部分 ROM 键在但空）
        assertEquals(TrustVerdict.UNREADABLE, BootloaderTrust.verdict(reading(flash to "  ")).verdict)
    }

    @Test
    fun partialButConsistentReadings_areTrusted() {
        // 有的机型没有 vbmeta 键：报得出的那几个都说锁着即算可信
        assertEquals(TrustVerdict.TRUSTED, BootloaderTrust.verdict(reading(flash to "1", verified to "green")).verdict)
        assertEquals(TrustVerdict.TRUSTED, BootloaderTrust.verdict(reading(vbmeta to "locked")).verdict)
    }
}
