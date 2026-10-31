package com.lansync.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * LanSync 尺寸令牌（设计系统**唯一**尺寸来源）。
 *
 * 组件内布局 `.dp` 字面量一律替换为本令牌字段，经 [LocalSpacing] 由
 * [LanSyncTheme] 注入，通过 `LanSyncTheme.spacing.xxx` 访问。
 * 圆角只走 `MaterialTheme.shapes`（[LanSyncShapes]），阴影只走 [LanSyncTheme.elevation]。
 *
 * 字段纪律：**只登记有调用点的尺寸**。新增前先确认它是可复用的语义尺寸；
 * 零引用字段由 `architecture/DesignSystemTest` 判红，避免令牌表再次长出死值。
 *
 * 布局间距采用 4dp 基栅格（含 2dp 半步与 1dp 描边级 hairline）。
 */
@Immutable
data class LanSyncSpacing(
    // ---- 布局间距 ----
    val space2: Dp = 2.dp,
    val space4: Dp = 4.dp,
    val space6: Dp = 6.dp,
    val space8: Dp = 8.dp,
    val space10: Dp = 10.dp,
    val space12: Dp = 12.dp,
    val space16: Dp = 16.dp,
    val space24: Dp = 24.dp,
    val space32: Dp = 32.dp,

    // ---- 图标尺寸（下限 14dp，信息性图标不得小于 iconSm）----
    val iconXs: Dp = 14.dp,
    val iconSm: Dp = 16.dp,
    val iconMd: Dp = 18.dp,
    val iconLg: Dp = 20.dp,
    val iconXl: Dp = 24.dp,
    val iconXxl: Dp = 28.dp,
    val iconFeature: Dp = 36.dp,
    val iconEmpty: Dp = 48.dp,
    val iconEmptyLg: Dp = 56.dp,
    val iconHero: Dp = 64.dp,

    // ---- 应用 / 设备图标 ----
    val appIconSm: Dp = 36.dp,
    val appIconMd: Dp = 40.dp,
    val appIconLg: Dp = 44.dp,
    val appIconXl: Dp = 48.dp,
    val deviceIcon: Dp = 48.dp,

    // ---- 描边 / 进度 / 强调条 ----
    val strokeThin: Dp = 2.dp,
    val strokeThick: Dp = 3.dp,
    val progressBarHeight: Dp = 6.dp,
    val accentBar: Dp = 4.dp,

    // ---- 控件与列表尺寸 ----
    val minTouchTarget: Dp = 48.dp,
    val listItemMin: Dp = 72.dp,
    val deviceCardMin: Dp = 84.dp,
    val topBarIndicator: Dp = 18.dp,
    val chipIcon: Dp = 16.dp,
)

/** 默认令牌实例（编译期常量语义，运行期不可变）。 */
val DefaultSpacing = LanSyncSpacing()

/** 由 [LanSyncTheme] 注入的间距令牌 CompositionLocal。 */
val LocalSpacing = staticCompositionLocalOf { DefaultSpacing }
