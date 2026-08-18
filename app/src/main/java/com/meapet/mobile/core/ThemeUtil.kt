package com.meapet.mobile.core

import android.content.Context
import android.content.res.Configuration

/**
 * 主题解析纯逻辑（非 Compose 场景，含悬浮窗 Service / 工具类）。
 *
 * 放在 core（基础设施层）而非 ui.theme，避免 live2d.overlay 等平台层
 * 反向依赖 UI 层（分层约束）；Compose 场景的薄封装见
 * [com.meapet.mobile.ui.theme.isDarkTheme]。
 */

/** 解析主题模式是否深色；"system"（或空）时使用传入的系统深色值。 */
fun resolveDarkTheme(themeMode: String?, systemDark: Boolean): Boolean = when (themeMode) {
    "dark" -> true
    "light" -> false
    else -> systemDark
}

/** Android 系统当前是否夜间模式（基于 context 配置）。 */
fun isSystemNight(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/** 基于 context 解析主题模式是否深色（Service / 工具类使用）。 */
fun isDarkTheme(context: Context, themeMode: String?): Boolean =
    resolveDarkTheme(themeMode, isSystemNight(context))
