package com.lansync.app.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.lansync.app.R

/**
 * 文件体积的唯一显示格式（B / KB / MB 一档小数）。
 *
 * 此前本地与远程列表用整数除法截断（`fileSize / 1024 / 1024` → "12 MB"），
 * 文件页用 [R.string.size_mb_decimal] 带小数，同一个"体积"概念在相邻两个 tab 精度不同。
 * 现在四个列表统一走这里。
 */
@Composable
@ReadOnlyComposable
fun formattedFileSize(bytes: Long): String = when {
    bytes < 1024 -> stringResource(R.string.size_bytes, bytes)
    bytes < 1024 * 1024 -> stringResource(R.string.size_kb, bytes / 1024.0)
    else -> stringResource(R.string.size_mb_decimal, bytes / (1024.0 * 1024.0))
}
