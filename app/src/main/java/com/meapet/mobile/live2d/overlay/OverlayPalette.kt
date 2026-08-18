package com.meapet.mobile.live2d.overlay

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import com.meapet.mobile.core.isDarkTheme
import com.meapet.mobile.framework.MeaPetApplication
import com.meapet.mobile.settings.SettingsManager

/**
 * 悬浮窗配色板。
 *
 * 悬浮窗是纯 View 界面（非 Compose），无法直接读取
 * [com.meapet.mobile.ui.theme.MeaPetTheme] 的 ColorScheme。这里读取用户在设置里选的
 * 主题（颜色预设 / 动态取色 / 明暗模式），按 ui/theme/Color.kt 相同的 seed 派生规则
 * 还原菜单、气泡、输入栏用到的几个颜色，保证悬浮窗与主界面观感一致。
 *
 * 动态取色（Material You，Android 12+）在无窗口 token 的 Service 里拿不到系统动态配色，
 * 退而读取壁纸主色（[WallpaperManager.getWallpaperColors]）近似；拿不到则回退所选预设。
 */
object OverlayPalette {

    /** 各预设 seed 主色，与 ui/theme/Color.kt 的 S_* 常量一一对应。 */
    private val PRESET_SEEDS: Map<String, Int> = mapOf(
        "default" to 0xFF6650A4.toInt(),
        "ocean" to 0xFF006492.toInt(),
        "forest" to 0xFF2E7D32.toInt(),
        "sunset" to 0xFFB85C00.toInt(),
        "rose" to 0xFFB54E6A.toInt(),
        "mono" to 0xFF4A4A4A.toInt(),
        "sky" to 0xFF5B8FA8.toInt(),
        "sage" to 0xFF6B9E7A.toInt(),
        "lilac" to 0xFF9A7FA8.toInt(),
        "terracotta" to 0xFFA87A6A.toInt(),
        "amber" to 0xFFB59A5A.toInt(),
        "steel" to 0xFF6A7A8A.toInt(),
    )

    /** 依据用户主题设置取一套悬浮窗颜色。 */
    fun resolve(context: Context): OverlayColors {
        val app = context.applicationContext
        val settings = (app as? MeaPetApplication)?.container?.settingsManager
            ?: SettingsManager(app)
        val themeMode = settings.getThemeMode()
        val presetId = settings.getColorPreset()
        val dynamicOn = settings.isDynamicColorEnabled()

        // 动态取色（Android 12+）：用壁纸主色近似 Material You 动态配色
        val seed = if (dynamicOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wallpaperSeed(context) ?: (PRESET_SEEDS[presetId] ?: PRESET_SEEDS.getValue("default"))
        } else {
            PRESET_SEEDS[presetId] ?: PRESET_SEEDS.getValue("default")
        }

        val dark = isDarkTheme(context, themeMode)
        return if (dark) darkColors(seed) else lightColors(seed)
    }

    /** 壁纸主色（依次取 primary → secondary → tertiary）。仅 API S+ 调用（动态取色分支已检查）。 */
    @SuppressLint("NewApi", "UNNECESSARY_SAFE_CALL")
    private fun wallpaperSeed(context: Context): Int? {
        val wc = try {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        } catch (_: Exception) {
            null
        } ?: return null
        return listOf(
            wc.primaryColor?.toArgb(),
            wc.secondaryColor?.toArgb(),
            wc.tertiaryColor?.toArgb(),
        ).firstNotNullOfOrNull { it }
    }

    /** 浅色套（seed 未提亮，对齐 ui/theme/Color.kt 的 lightScheme）。 */
    private fun lightColors(seed: Int): OverlayColors = OverlayColors(
        primary = seed,
        onPrimary = darken(seed, 0.6f),
        surfaceVariant = lighten(seed, 0.92f),
        onSurfaceVariant = darken(seed, 0.3f),
        onSurface = darken(seed, 0.5f),
    )

    /** 深色套（seed 提亮 50% 后派生，对齐 darkScheme）。 */
    private fun darkColors(seed: Int): OverlayColors {
        val sp = lighten(seed, 0.5f)
        return OverlayColors(
            primary = sp,
            onPrimary = darken(sp, 0.85f),
            surfaceVariant = darken(sp, 0.75f),
            onSurfaceVariant = lighten(sp, 0.4f),
            onSurface = lighten(sp, 0.55f),
        )
    }

    /** 与 Color.kt lighten 一致：c + (1-c)*f。 */
    private fun lighten(c: Int, f: Float): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return Color.rgb(
            (r + (255 - r) * f).toInt(),
            (g + (255 - g) * f).toInt(),
            (b + (255 - b) * f).toInt(),
        )
    }

    /** 与 Color.kt darken 一致：c*(1-f)。 */
    private fun darken(c: Int, f: Float): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return Color.rgb(
            (r * (1 - f)).toInt(),
            (g * (1 - f)).toInt(),
            (b * (1 - f)).toInt(),
        )
    }
}

/**
 * 一套悬浮窗颜色。字段语义对齐 Material3 ColorScheme 的对应色（light / dark）。
 *
 * @param primary 主色：发送按钮、加载指示。
 * @param onPrimary 主色上的文字 / 图标色。
 * @param surfaceVariant 表面变体色：气泡、输入栏、菜单卡片底色。
 * @param onSurfaceVariant 表面变体上的文字色（气泡正文、占位符）。
 * @param onSurface 普通表面文字色（菜单项）。
 */
data class OverlayColors(
    val primary: Int,
    val onPrimary: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val onSurface: Int,
)
