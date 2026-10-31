package com.lansync.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.lansync.app.data.cache.IconCache
import com.lansync.app.ui.theme.LanSyncTheme

/** 位图按 2 倍目标尺寸请求（覆盖高密度屏），下限 96px。 */
private const val BITMAP_REQUEST_SCALE = 2f
private const val MIN_BITMAP_PX = 96

/** 占位图标相对图标框的比例。 */
private const val PLACEHOLDER_ICON_RATIO = 0.45f

/**
 * 应用图标 Composable（ui 层**薄壳**）。
 *
 * 获取链路：内存 LruCache → 磁盘 PNG → `PackageManager.getApplicationInfo` + `loadIcon`。
 * `loadIcon` 返回的是**系统当前应用的图标**（含桌面主题/图标包覆盖后的结果），
 * 因此同包在装了图标包的设备上会显示主题图标，而非 APK 内原始资源。
 *
 * 失败或未缓存时使用与成功态一致的圆角底 + 单色占位，避免圆形机器人与圆角矩形图标风格冲突。
 * 尺寸默认值也从 [LanSyncTheme.spacing] 取，不再直接引用编译期常量表。
 */
@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp? = null,
) {
    val sp = LanSyncTheme.spacing
    val side = size ?: sp.appIconXl
    val context = LocalContext.current
    val iconCache = remember { IconCache.getInstance(context) }
    val targetSize = (side.value * BITMAP_REQUEST_SCALE).toInt().coerceAtLeast(MIN_BITMAP_PX)

    var bitmap by remember(packageName) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(packageName) { mutableStateOf(false) }

    LaunchedEffect(packageName) {
        if (bitmap != null || loadFailed) return@LaunchedEffect
        val loaded = iconCache.get(packageName, targetSize)
        if (loaded != null) bitmap = loaded else loadFailed = true
    }

    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .size(side)
            .clip(shape)
            .background(
                if (bitmap != null) Color.Transparent else LanSyncTheme.containers.high,
            ),
        contentAlignment = Alignment.Center
    ) {
        val loadedBitmap = bitmap
        if (loadedBitmap != null) {
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(side * PLACEHOLDER_ICON_RATIO),
            )
        }
    }
}
