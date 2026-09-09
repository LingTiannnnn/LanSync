package com.lansync.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 亮色 ColorScheme —— 靛蓝品牌色（[Color.kt] 为唯一色值来源）。
 * Material3 1.1.x 色角色集（无 surfaceContainer* 角色）。
 */
private val LightColorScheme = lightColorScheme(
    primary = Indigo40,
    onPrimary = PureWhite,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    inversePrimary = Indigo80,
    secondary = Teal40,
    onSecondary = PureWhite,
    secondaryContainer = Teal90,
    onSecondaryContainer = Teal10,
    tertiary = Amber40,
    onTertiary = PureWhite,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Neutral90,
    onSurfaceVariant = Neutral30,
    surfaceTint = Indigo40,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    error = Red40,
    onError = PureWhite,
    errorContainer = Red90,
    onErrorContainer = Red10,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    scrim = ScrimColor,
)

/**
 * 暗色 ColorScheme —— 靛蓝品牌色暗色投影。
 */
private val DarkColorScheme = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    inversePrimary = Indigo40,
    secondary = Teal80,
    onSecondary = Teal20,
    secondaryContainer = Teal30,
    onSecondaryContainer = Teal90,
    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceTint = Indigo80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    scrim = ScrimColor,
)

/**
 * LanSync 统一主题入口（Phase 5「单一 Material3 设计系统」）。
 *
 * - **颜色**：默认使用固定品牌 [LightColorScheme]/[DarkColorScheme]（[dynamicColor] 默认关闭，
 *   以保证跨设备一致的单一设计系统；如需 Material You 动态取色可显式开启）。
 * - **间距**：经 [LocalSpacing] 注入 [LanSyncSpacing] 令牌，通过 [LanSyncTheme.spacing] 访问。
 * - **字体**：统一 [Typography]。
 */
@Composable
fun LanSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val isLightBackground = colorScheme.background.luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isLightBackground
                isAppearanceLightNavigationBars = isLightBackground
            }
        }
    }

    CompositionLocalProvider(LocalSpacing provides DefaultSpacing) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * 设计系统访问器（对齐 Material3 [MaterialTheme] 对象惯例）：
 * 提供 [spacing] 令牌；颜色/字体直接经 [MaterialTheme.colorScheme] / [MaterialTheme.typography]。
 */
object LanSyncTheme {
    val spacing: LanSyncSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSpacing.current
}
