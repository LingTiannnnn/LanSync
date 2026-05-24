package com.lansync.app.ui.components

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Int = 48
) {
    val context = LocalContext.current
    val cachedSize = (size * 2).coerceAtLeast(96)

    val bitmap by produceState<Bitmap?>(initialValue = AppIconCache.get(packageName), key1 = packageName) {
        if (value != null) return@produceState
        value = AppIconCache.getOrLoad(context, packageName, cachedSize)
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

object AppIconCache {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun get(packageName: String): Bitmap? = cache[packageName]

    fun getOrLoad(context: Context, packageName: String, size: Int = 96): Bitmap? {
        cache[packageName]?.let { return it }

        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = appInfo.loadIcon(pm)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            drawable.draw(canvas)
            cache[packageName] = bitmap
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }
}