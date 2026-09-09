package com.lansync.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.lansync.app.data.cache.IconCache
import com.lansync.app.ui.theme.DefaultSpacing
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 应用图标 Composable（ui 层**薄壳**）。
 *
 * 三级缓存（内存 LruCache → 磁盘 PNG → PackageManager 绘制）与 `preload` 已下沉 data 层 [IconCache]
 * （修复 legacy-known-issue **L2 分层倒置**：data 不再 `import ui.components.preloadIcon`）。
 * 本组件仅消费 [IconCache.get] 返回的 [Bitmap] 并转 `ImageBitmap` 渲染；缺失时回退占位图标。
 *
 * 尺寸/圆角走设计令牌（[LanSyncTheme.spacing]），无硬编码 dp。
 */
@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = DefaultSpacing.appIconXl
) {
    val sp = LanSyncTheme.spacing
    val context = LocalContext.current
    val iconCache = remember { IconCache.getInstance(context) }
    val targetSize = (size.value * 2).toInt().coerceAtLeast(96)

    var bitmap by remember(packageName) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(packageName) { mutableStateOf(false) }

    LaunchedEffect(packageName) {
        if (bitmap != null || loadFailed) return@LaunchedEffect
        val loaded = iconCache.get(packageName, targetSize)
        if (loaded != null) bitmap = loaded else loadFailed = true
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(sp.radiusLg))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size - sp.space8)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size - sp.space16)
            )
        }
    }
}
