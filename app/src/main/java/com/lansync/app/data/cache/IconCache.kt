package com.lansync.app.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用图标**三级缓存**（data 层，ARCH §3.3；修复 legacy-known-issue **L2 分层倒置**）。
 *
 * L1 内存 `LruCache`(120 条，按字节计数) → L2 磁盘 PNG([AppIconDiskCache]) → L3 `PackageManager` 绘制
 * （`AdaptiveIconDrawable` 取 foreground）。提供 [preload] 供 **data 层**（`LocalAppRepository.ScanResult`
 * 的新增包名）调用——data 不再 `import ui.components.preloadIcon`（消除 L2）。ui 层 `AppIcon` 仅消费 [get]
 * 返回的 `Bitmap`（转 `ImageBitmap`）。
 *
 * Android 图形耦合，**非 JVM 单测目标**（编译校验 + 真机验收）。
 */
class IconCache private constructor(
    private val context: Context,
    private val diskCache: AppIconDiskCache
) {

    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    /** 取图标：内存 → 磁盘 → 绘制并回填两级缓存；失败返回 null。 */
    suspend fun get(packageName: String, targetSize: Int = DEFAULT_SIZE): Bitmap? {
        memoryCache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            diskCache.get(packageName, targetSize)?.also { memoryCache.put(packageName, it) }
                ?: drawAndCache(packageName, targetSize)
        }
    }

    /** 预加载一批包名图标（仅内存/磁盘均缺失者），供扫描后 data 层调用。 */
    suspend fun preload(packageNames: List<String>, targetSize: Int = DEFAULT_SIZE) = withContext(Dispatchers.IO) {
        for (pkg in packageNames) {
            if (memoryCache.get(pkg) != null) continue
            if (diskCache.exists(pkg)) continue
            drawAndCache(pkg, targetSize)
        }
    }

    private fun drawAndCache(packageName: String, targetSize: Int): Bitmap? = try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        var drawable = appInfo.loadIcon(pm)
        if (drawable is AdaptiveIconDrawable) drawable = drawable.foreground
        val bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawable.setBounds(0, 0, targetSize, targetSize)
        drawable.draw(canvas)
        memoryCache.put(packageName, bitmap)
        diskCache.put(packageName, bitmap)
        bitmap
    } catch (e: Exception) {
        null
    }

    /** 移除单个包名图标（内存 + 磁盘）。 */
    fun remove(packageName: String) {
        memoryCache.remove(packageName)
        diskCache.remove(packageName)
    }

    /** 清理不在有效包集合内的图标（内存 + 磁盘），应用卸载/更新后调用。 */
    fun removeStale(validPackages: Set<String>) {
        for (key in memoryCache.snapshot().keys) {
            if (key !in validPackages) memoryCache.remove(key)
        }
        diskCache.removeStale(validPackages)
    }

    companion object {
        private const val MAX_MEMORY_CACHE_ENTRIES = 120
        private const val DEFAULT_SIZE = 96

        @Volatile
        private var instance: IconCache? = null

        fun getInstance(context: Context): IconCache =
            instance ?: synchronized(this) {
                instance ?: IconCache(
                    context.applicationContext,
                    AppIconDiskCache.getInstance(context.applicationContext)
                ).also { instance = it }
            }
    }
}
