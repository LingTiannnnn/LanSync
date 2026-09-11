package com.lansync.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 形状与关键尺寸（交接包 tokens/LanSyncShape.kt）。
 * 组件内布局间距走 [LanSyncSpacing]；全局形状经 [MaterialTheme.shapes]。
 */

val LanSyncShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 关键控件/列表尺寸 token（禁止在组件内散落字面量时优先走此表）。 */
object LanSyncMetrics {
    val listItemMin: Dp = 72.dp
    val listItemTall: Dp = 88.dp
    val deviceCardMin: Dp = 84.dp
    val navIndicatorWidth: Dp = 64.dp
    val navIndicatorHeight: Dp = 32.dp
    val filterChipHeight: Dp = 36.dp
    val searchBarHeight: Dp = 48.dp
    val appIcon: Dp = 44.dp
    val deviceIcon: Dp = 48.dp
    val minTouchTarget: Dp = 48.dp
    val progressBar: Dp = 6.dp
    val updateAccentBar: Dp = 4.dp
    val statusBarCompat: Dp = 32.dp
    val gestureBarH: Dp = 22.dp
}
