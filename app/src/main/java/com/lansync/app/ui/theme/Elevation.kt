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
 * - [flat]：列表行卡与设备卡的平面高度。卡片空间感不靠阴影——滚动期每行阴影的
 *   graphicsLayer 在触摸命中测试路径上有实测成本，改由 `spacing.hairline` 描边 +
 *   `outlineVariant` 承担；选中态靠 highest 底色区分。
 * - [banner]：短反馈横幅投影。
 * - [overlay]：模态遮罩内卡片与批量操作条的色调高度。
 */
@Immutable
data class LanSyncElevation(
    val flat: Dp = 0.dp,
    val banner: Dp = 6.dp,
    val overlay: Dp = 8.dp,
)

val DefaultElevation = LanSyncElevation()

val LocalElevation = staticCompositionLocalOf { DefaultElevation }
