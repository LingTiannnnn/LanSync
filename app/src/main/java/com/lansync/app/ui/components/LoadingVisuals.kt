package com.lansync.app.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 「加载中」词汇的唯一入口（CONVENTIONS §3）。
 *
 * 此前 Material3 的 `CircularProgressIndicator` / `LinearProgressIndicator` 在 7 个文件里被直接调用 10 次，
 * 每处各自挑尺寸、线宽与颜色 —— 同一个"正在加载"在顶栏、卡片、按钮、横幅里视觉重量都不一样，
 * 而且改一处令牌无法全局生效。几何默认值一律来自 `LanSyncTheme.spacing`，
 * 只有在语境需要（例如用状态强调色 `accent`、用 `contentColor`）时才覆盖 `color`。
 */
@Composable
fun LanSyncSpinner(
    modifier: Modifier = Modifier,
    size: Dp = LanSyncTheme.spacing.iconXxl,
    strokeWidth: Dp = LanSyncTheme.spacing.strokeThick,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        strokeWidth = strokeWidth,
    )
}

/**
 * 线性进度条。[progress] 为 null 表示不确定进度（跑马灯），非 null 表示 0f–1f 的确定进度。
 *
 * 宽度**由调用方的 [modifier] 决定**（`fillMaxWidth()` / `fillMaxWidth(0.7f)` 都是有意的语境差异），
 * 本原语只统一高度、颜色与轨道色，避免把某处的宽度选择固化成全局规则。
 */
@Composable
fun LanSyncProgressBar(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    height: Dp = LanSyncTheme.spacing.progressBarHeight,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = LanSyncTheme.containers.highest,
) {
    val shaped = modifier.height(height)
    if (progress == null) {
        LinearProgressIndicator(
            modifier = shaped,
            color = color,
            trackColor = trackColor,
        )
    } else {
        LinearProgressIndicator(
            progress = progress,
            modifier = shaped,
            color = color,
            trackColor = trackColor,
        )
    }
}
