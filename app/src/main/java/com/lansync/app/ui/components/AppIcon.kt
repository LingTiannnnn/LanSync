package com.lansync.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.LruCache
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
import androidx.compose.ui.unit.dp
import com.lansync.app.data.cache.AppIconDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_MEMORY_CACHE_ENTRIES = 120

private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_CACHE_ENTRIES) {
    override fun sizeOf(key: String, value: Bitmap): Int = 1
}

@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Int = 48
) {
    val context = LocalContext.current
    val diskCache = remember { AppIconDiskCache.getInstance(context) }
    val targetSize = (size * 2).coerceAtLeast(96)

    val cached = remember(packageName) { memoryCache.get(packageName) }
    var bitmap by remember(packageName) { mutableStateOf(cached) }
    var loadFailed by remember(packageName) { mutableStateOf(false) }

    LaunchedEffect(packageName) {
        if (bitmap != null || loadFailed) return@LaunchedEffect

        val loaded = withContext(Dispatchers.IO) {
            loadIcon(context, diskCache, packageName, targetSize)
        }

        if (loaded != null) {
            memoryCache.put(packageName, loaded)
            bitmap = loaded
        } else {
            loadFailed = true
        }
    }

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size((size - 8).dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size - 16).dp)
            )
        }
    }
}

private suspend fun loadIcon(
    context: Context,
    diskCache: AppIconDiskCache,
    packageName: String,
    targetSize: Int
): Bitmap? = withContext(Dispatchers.IO) {
    val fromDisk = diskCache.get(packageName, targetSize)
    if (fromDisk != null) return@withContext fromDisk

    try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        var drawable = appInfo.loadIcon(pm)

        if (drawable is AdaptiveIconDrawable) {
            drawable = drawable.foreground
        }

        val bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        drawable.setBounds(0, 0, targetSize, targetSize)
        drawable.draw(canvas)

        diskCache.put(packageName, bitmap)
        bitmap
    } catch (_: Exception) {
        null
    }
}

fun preloadIcon(context: Context, packageName: String, targetSize: Int = 96) {
    if (memoryCache.get(packageName) != null) return
    val diskCache = AppIconDiskCache.getInstance(context)
    if (diskCache.exists(packageName)) return

    try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        var drawable = appInfo.loadIcon(pm)

        if (drawable is AdaptiveIconDrawable) {
            drawable = drawable.foreground
        }

        val bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        drawable.setBounds(0, 0, targetSize, targetSize)
        drawable.draw(canvas)

        memoryCache.put(packageName, bitmap)
        diskCache.put(packageName, bitmap)
    } catch (_: Exception) {
    }
}

fun clearAppIconCache() {
    memoryCache.evictAll()
}

object AppIconCache {
    fun get(packageName: String): Bitmap? = memoryCache.get(packageName)
    fun put(packageName: String, bitmap: Bitmap) = memoryCache.put(packageName, bitmap)
    fun clear() {
        memoryCache.evictAll()
    }
}