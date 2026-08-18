package com.meapet.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.meapet.mobile.core.resolveDarkTheme

/**
 * Compose 场景主题判断。
 *
 * 纯逻辑（[resolveDarkTheme] / 系统夜间检测 / context 版 `isDarkTheme`）位于
 * [com.meapet.mobile.core]，供非 Compose 场景（悬浮窗 Service 等）复用，
 * 避免 live2d 等平台层反向依赖 UI 层（分层约束）。
 */
@Composable
fun isDarkTheme(themeMode: String?): Boolean =
    resolveDarkTheme(themeMode, isSystemInDarkTheme())
