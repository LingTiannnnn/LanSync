package com.lansync.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 高度（阴影 / 色调）令牌。
 *
 * 此前组件把 `spacing.space4 / space6 / space8 / hairline` 当 elevation 用 ——
 * 间距表一改，卡片阴影和横幅高度感跟着变。阴影从此走 [LanSyncTheme.elevation]。
 *
 * - [card]：列表卡片贴地阴影（含选中态不变）。
 * - [banner]：短反馈横幅投影。
 * - [overlay]：模态遮罩内卡片与批量操作条的色调高度。
 */
@Immutable
data class LanSyncElevation(
    val card: Dp = 1.dp,
    val banner: Dp = 6.dp,
    val overlay: Dp = 8.dp,
)

val DefaultElevation = LanSyncElevation()

val LocalElevation = staticCompositionLocalOf { DefaultElevation }
