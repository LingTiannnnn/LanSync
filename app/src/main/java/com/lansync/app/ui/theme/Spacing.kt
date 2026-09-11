package com.lansync.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * LanSync 间距 / 尺寸设计令牌（设计系统唯一 dp 来源）。
 *
 * 组件内布局 `.dp` 字面量一律替换为本令牌字段，经 [LocalSpacing] 由
 * [LanSyncTheme] 注入，通过 `LanSyncTheme.spacing.xxx` 访问。
 * 关键列表/底栏尺寸见 [LanSyncMetrics]。
 *
 * 布局间距采用 4dp 基栅格（含 2dp 半步）。
 */
@Immutable
data class LanSyncSpacing(
    // ---- 布局间距（4dp 基栅格）----
    val none: Dp = 0.dp,
    val hairline: Dp = 1.dp,
    val space2: Dp = 2.dp,
    val space4: Dp = 4.dp,
    val space6: Dp = 6.dp,
    val space8: Dp = 8.dp,
    val space10: Dp = 10.dp,
    val space12: Dp = 12.dp,
    val space16: Dp = 16.dp,
    val space20: Dp = 20.dp,
    val space24: Dp = 24.dp,
    val space28: Dp = 28.dp,
    val space32: Dp = 32.dp,
    val space40: Dp = 40.dp,
    val space48: Dp = 48.dp,
    val space56: Dp = 56.dp,
    val space64: Dp = 64.dp,

    // ---- 图标尺寸 ----
    val iconXxs: Dp = 11.dp,
    val iconXs: Dp = 13.dp,
    val iconSm: Dp = 16.dp,
    val iconMd: Dp = 18.dp,
    val iconLg: Dp = 20.dp,
    val iconXl: Dp = 24.dp,
    val iconXxl: Dp = 28.dp,
    val iconFeature: Dp = 36.dp,
    val iconEmpty: Dp = 48.dp,
    val iconEmptyLg: Dp = 56.dp,
    val iconHero: Dp = 64.dp,

    // ---- 应用图标（AppIcon）----
    val appIconSm: Dp = 36.dp,
    val appIconMd: Dp = 40.dp,
    val appIconLg: Dp = 44.dp,
    val appIconXl: Dp = 48.dp,

    // ---- 圆角（对齐 LanSyncShapes）----
    val radiusXs: Dp = 8.dp,
    val radiusSm: Dp = 12.dp,
    val radiusMd: Dp = 16.dp,
    val radiusLg: Dp = 24.dp,
    val radiusXl: Dp = 28.dp,

    // ---- 描边 / 进度条 / 控件尺寸 ----
    val strokeThin: Dp = 2.dp,
    val strokeThick: Dp = 3.dp,
    val progressBarHeight: Dp = 6.dp,
    val buttonHeight: Dp = 36.dp,
    val topBarIndicator: Dp = 18.dp,
    val chipIcon: Dp = 16.dp,
    val minTouch: Dp = 48.dp,
    val listItemMin: Dp = 72.dp,
    val listItemTall: Dp = 88.dp,
    val deviceCardMin: Dp = 84.dp,
    val navIndicatorW: Dp = 64.dp,
    val navIndicatorH: Dp = 32.dp,
    val filterChipH: Dp = 36.dp,
    val searchH: Dp = 48.dp,
    val appIcon: Dp = 44.dp,
    val deviceIcon: Dp = 48.dp,
)

/** 默认令牌实例（编译期常量语义，运行期不可变）。 */
val DefaultSpacing = LanSyncSpacing()

/** 由 [LanSyncTheme] 提供的间距令牌 CompositionLocal。 */
val LocalSpacing = staticCompositionLocalOf { DefaultSpacing }
