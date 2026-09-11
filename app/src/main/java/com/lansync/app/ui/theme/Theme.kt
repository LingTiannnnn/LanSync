package com.lansync.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * LanSync 统一主题入口（Material 3 · Teal）。
 *
 * - **颜色**：固定品牌 scheme（[dynamicColor] 默认关闭）；容器色经 [LocalContainers]。
 * - **系统栏**：Activity 必须先 `enableEdgeToEdge()`；此处**禁止**再写不透明
 *   `statusBarColor` / `navigationBarColor`，只按 luminance 设置图标亮暗。
 * - **间距**：经 [LocalSpacing] 注入 [LanSyncSpacing]。
 */

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary40,
    onPrimary = PureWhite,
    primaryContainer = TealPrimary90,
    onPrimaryContainer = TealPrimary10,
    inversePrimary = TealPrimary80,
    secondary = TealSecondary40,
    onSecondary = PureWhite,
    secondaryContainer = TealSecondary90,
    onSecondaryContainer = TealSecondary10,
    tertiary = Amber40,
    onTertiary = PureWhite,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceTint = TealPrimary40,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    error = Red40,
    onError = PureWhite,
    errorContainer = Red90,
    onErrorContainer = Red10,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    scrim = ScrimColor,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimary80,
    onPrimary = TealPrimary20,
    primaryContainer = TealPrimary30,
    onPrimaryContainer = TealPrimary90,
    inversePrimary = TealPrimary40,
    secondary = TealSecondary80,
    onSecondary = TealSecondary20,
    secondaryContainer = TealSecondary30,
    onSecondaryContainer = TealSecondary90,
    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    background = SurfaceDark,
    onBackground = Neutral90,
    surface = SurfaceDark,
    onSurface = Neutral90,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceTint = TealPrimary80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    scrim = ScrimColor,
)

/** 容器色扩展：补 M3 1.1 缺失的 surfaceContainer 角色。 */
@Immutable
data class LanSyncContainerColors(
    val low: Color,
    val default: Color,
    val high: Color,
    val highest: Color,
)

object LanSyncContainers {
    val Light = LanSyncContainerColors(
        low = SurfaceLowLight,
        default = SurfaceContainerLight,
        high = SurfaceContainerHighLight,
        highest = SurfaceContainerHighestLight,
    )
    val Dark = LanSyncContainerColors(
        low = SurfaceLowDark,
        default = SurfaceContainerDark,
        high = SurfaceContainerHighDark,
        highest = SurfaceContainerHighestDark,
    )
}

val LocalContainers = staticCompositionLocalOf { LanSyncContainers.Light }

/** 动效 token（静态默认，避免每次组合新建实例）。 */
@Immutable
data class LanSyncMotion(
    val shortMillis: Int = 150,
    val mediumMillis: Int = 250,
    val longMillis: Int = 350,
    val pressScale: Float = 0.96f,
)

private val DefaultMotion = LanSyncMotion()

val LocalMotion = staticCompositionLocalOf { DefaultMotion }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 设计系统访问器：间距 / 容器色 / 动效。
 * 颜色与字体仍经 [MaterialTheme.colorScheme] / [MaterialTheme.typography]。
 */
object LanSyncTheme {
    val spacing: LanSyncSpacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current

    val containers: LanSyncContainerColors
        @Composable @ReadOnlyComposable get() = LocalContainers.current

    val motion: LanSyncMotion
        @Composable @ReadOnlyComposable get() = LocalMotion.current
}

@Composable
fun LanSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val containers = if (darkTheme) LanSyncContainers.Dark else LanSyncContainers.Light

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 某些 ContextWrapper 链上不一定直接是 Activity，强转会闪退
            val activity = view.context.findActivity()
            if (activity != null) {
                val lightBars = !darkTheme && colorScheme.background.luminance() > 0.5f
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalSpacing provides DefaultSpacing,
        LocalContainers provides containers,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = LanSyncShapes,
            content = content,
        )
    }
}
