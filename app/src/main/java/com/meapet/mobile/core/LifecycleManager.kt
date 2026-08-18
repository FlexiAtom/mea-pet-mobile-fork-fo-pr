package com.meapet.mobile.core

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log

/**
 * 应用级生命周期管理器。
 */
class LifecycleManager(
    /** 注意：参数名不能叫 onTrimMemory，会和 override 方法冲突导致无限递归！ */
    private val trimMemoryCallback: (level: Int) -> Unit = {}
) : ComponentCallbacks2 {

    companion object {
        private const val TAG = "LifecycleManager"
    }

    override fun onTrimMemory(level: Int) {
        try {
            Log.d(TAG, "onTrimMemory level=$level")
            trimMemoryCallback(level)
        } catch (e: Exception) {
            Log.e(TAG, "onTrimMemory error: ${e.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        try {
            Log.w(TAG, "系统低内存通知")
            trimMemoryCallback(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        } catch (e: Exception) {
            Log.e(TAG, "onLowMemory error: ${e.message}")
        }
    }
}
