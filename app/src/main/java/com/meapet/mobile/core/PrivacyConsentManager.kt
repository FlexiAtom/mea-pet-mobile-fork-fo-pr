package com.meapet.mobile.core

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.meapet.mobile.settings.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 隐私授权管理器。
 *
 * 管理用户是否已同意《隐私政策》中关于友盟统计 SDK 数据采集的授权。
 * - 首次启动：未同意，App 正常使用但不初始化友盟 SDK。
 * - 同意后：友盟 SDK 正式初始化，开始采集并上报数据。
 * - 取消授权后：停止上报（后续冷启动不再 init），App 其余功能不受影响。
 *
 * 使用 SharedPreferences 做同步读写，保证 [isAgreed] 可在 Application.onCreate 中同步判断。
 */
object PrivacyConsentManager {

    private const val PREFS_NAME = "privacy_consent"
    private const val KEY_AGREED = "umeng_privacy_agreed"
    private const val KEY_USER_CHOSEN = "privacy_user_chosen"

    private val KEY_DS_AGREED = booleanPreferencesKey("umeng_privacy_agreed")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户是否已同意隐私授权（同步读取，可在主线程调用）。 */
    fun isAgreed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AGREED, false)

    /** 用户是否已经做出过选择（同意或不同意都算），用于判断是否需要弹窗。 */
    fun hasUserChosen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USER_CHOSEN, false)

    /** 授权状态 Flow（响应式订阅）。 */
    fun agreedFlow(context: Context): Flow<Boolean> =
        context.appDataStore.data.map { it[KEY_DS_AGREED] ?: false }

    /** 标记用户已做出选择并记录授权状态。 */
    @Suppress("ApplySharedPref")
    fun setAgreed(context: Context, agreed: Boolean) {
        // 用同步 commit() 保证写盘完成后再返回：取消授权后会立刻 killProcess，
        // apply() 的异步落盘可能来不及，导致重启后仍读到旧的授权状态。
        prefs(context).edit()
            .putBoolean(KEY_AGREED, agreed)
            .putBoolean(KEY_USER_CHOSEN, true)
            .commit()
        // 同步写入 DataStore 供 Flow 订阅
        kotlinx.coroutines.runBlocking {
            context.appDataStore.edit { it[KEY_DS_AGREED] = agreed }
        }
    }
}
