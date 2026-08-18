package com.meapet.mobile.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * MeaPet 主题。
 *
 * @param themeMode "dark" / "light" / "system"(null)
 * @param dynamicColor 是否使用 Material You 动态取色（Android 12+）
 * @param colorPreset 颜色预设 ID（dynamicColor=true 时无效）
 */
@Composable
fun MeaPetTheme(
    themeMode: String? = null,
    dynamicColor: Boolean = true,
    colorPreset: String = "default",
    content: @Composable () -> Unit
) {
    val darkTheme = isDarkTheme(themeMode)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val preset = findPreset(colorPreset)
            if (darkTheme) preset.dark else preset.light
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
