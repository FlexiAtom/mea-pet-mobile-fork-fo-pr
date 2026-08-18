package com.meapet.mobile.core

import android.content.Context
import com.meapet.mobile.BuildConfig

/**
 * 应用独特性标识信息的统一访问入口。
 *
 * 开发者名、仓库地址、交流群链接、友盟隐私政策链接等均通过
 * [BuildConfig] 在构建时注入（来源为 local.properties），
 * 源码中不硬编码，便于开源分叉时替换为自己的信息。
 *
 * 注入缺失时回退到 MeaPet 原作者的默认值，保证功能可用。
 */
object AppInfo {

    private const val DEFAULT_VERSION = "1.0.0"

    /** 开发者名称。 */
    val devName: String = BuildConfig.DEV_NAME.ifBlank { "llz121517" }

    /** GitHub 仓库主页地址。 */
    val gitRepoUrl: String = BuildConfig.GIT_REPO_URL.ifBlank {
        "https://github.com/llz121517/mea-pet-mobile"
    }

    /** 交流 QQ 群地址。 */
    val qqGroupUrl: String = BuildConfig.QQ_GROUP_URL.ifBlank {
        "https://qm.qq.com/q/pD9vpN6zKg"
    }

    /** 友盟+ 隐私权政策链接。 */
    val umengPolicyUrl: String = BuildConfig.UMENG_POLICY_URL.ifBlank {
        "https://www.umeng.com/page/policy"
    }

    /** 从 PackageManager 读取当前 versionName（失败回退默认）。 */
    fun readVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: DEFAULT_VERSION
    } catch (_: Exception) {
        DEFAULT_VERSION
    }
}
