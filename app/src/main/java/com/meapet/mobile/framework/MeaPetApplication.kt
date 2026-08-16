package com.meapet.mobile.framework

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.meapet.mobile.BuildConfig
import com.umeng.commonsdk.UMConfigure

/**
 * 全局 Application 入口。
 *
 * ## 职责
 * - 初始化 [AppContainer]（唯一依赖容器）；
 * - 注册 [LifecycleManager]；
 * - 友盟统计 SDK 预初始化 + 合规正式初始化；
 * - 提供静态 [instance] 访问入口（谨慎使用，优先通过构造注入）。
 *
 * ## 友盟 SDK 初始化策略
 * - **预初始化**（[UMConfigure.preInit]）：每次冷启动都在 onCreate 主线程调用，
 *   不采集设备信息、不上报数据，满足工信部合规要求。
 * - **正式初始化**（[UMConfigure.init]）：仅在用户同意《隐私政策》后调用，
 *   SDK 才真正采集并上报。未同意时 App 其余功能不受影响。
 *
 * ## 配置
 * 在 `AndroidManifest.xml` 中声明：
 * ```xml
 * <application android:name=".framework.MeaPetApplication" ... />
 * ```
 */
class MeaPetApplication : Application() {

    /** 应用级依赖容器。所有子系统均通过此容器获取。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MeaPetApplication onCreate")

        // ── 友盟+ 预初始化（不采集、不上报，合规要求务必在 onCreate 调用） ──
        val appKey = BuildConfig.UMENG_APP_KEY
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        UMConfigure.setLogEnabled(isDebug)
        if (appKey.isNotBlank()) {
            UMConfigure.preInit(this, appKey, UMENG_CHANNEL)
        } else {
            Log.w(TAG, "友盟 AppKey 为空（local.properties 中未配置 umeng.appKey），跳过预初始化")
        }

        // 1) 创建依赖容器
        container = AppContainer(this)

        // 2) 注册生命周期回调
        registerComponentCallbacks(container.lifecycleManager)

        // 3) 异步预热（加载持久化的记忆数据，不阻塞主线程）
        container.warmUp()

        // 4) 打印启动信息
        Log.i(TAG, "App initialized: config=${container.config}")

        // ── 友盟+ 正式初始化（仅在用户已同意隐私协议时调用） ──
        if (appKey.isNotBlank() && PrivacyConsentManager.isAgreed(this)) {
            initUmengSdk()
        }
    }

    /**
     * 正式初始化友盟统计 SDK。
     *
     * 由 [PrivacyConsentManager] 控制：同意后调用此方法，SDK 开始采集上报；
     * 取消授权后不再调用（下次冷启动也不 init），SDK 停止上报。
     */
    fun initUmengSdk() {
        val appKey = BuildConfig.UMENG_APP_KEY
        if (appKey.isBlank()) return
        try {
            UMConfigure.init(
                this, appKey, UMENG_CHANNEL,
                UMConfigure.DEVICE_TYPE_PHONE, ""
            )
            Log.i(TAG, "友盟统计 SDK 正式初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "友盟 SDK 初始化失败: ${e.message}")
        }
    }

    override fun onTerminate() {
        Log.i(TAG, "MeaPetApplication onTerminate")
        super.onTerminate()
    }

    companion object {
        private const val TAG = "MeaPetApp"

        /** 友盟分发渠道名（由 local.properties 的 umeng.channel 注入，按分发来源命名）。 */
        private val UMENG_CHANNEL: String = BuildConfig.UMENG_CHANNEL

        /**
         * 便捷获取容器。
         *
         * 仅在无法通过构造注入的场景使用（如较旧的 Java 组件）。
         */
        fun from(application: Application): AppContainer =
            (application as MeaPetApplication).container
    }
}
