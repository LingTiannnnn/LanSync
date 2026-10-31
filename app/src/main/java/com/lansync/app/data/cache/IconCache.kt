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
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用图标**三级缓存**（data 层，ARCH §3.3；修复 legacy-known-issue **L2 分层倒置**）。
 *
 * L1 内存 `LruCache`(**8 MB**，按字节计数) → L2 磁盘 PNG([AppIconDiskCache]) → L3 `PackageManager` 绘制
 * （`AdaptiveIconDrawable` 取 foreground）。提供 [preload] 供 **data 层**（`LocalAppRepository.ScanResult`
 * 的新增包名）调用——data 不再 `import ui.components.preloadIcon`（消除 L2）。ui 层 `AppIcon` 仅消费 [get]
 * 返回的 `Bitmap`（转 `ImageBitmap`）。绘制失败（典型：包名未安装）的包名记入**会话级负缓存**，
 * 滚动重试不再反复发起 PackageManager 调用；[preload] 开头清空负缓存，新装应用的图标立即可绘制。
 *
 * Android 图形耦合，**非 JVM 单测目标**（编译校验 + 真机验收）。
 */
class IconCache private constructor(
    private val context: Context,
    private val diskCache: AppIconDiskCache
) {

    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            value.allocationByteCount.coerceAtLeast(1)
    }

    /** 绘制失败包名的会话级负缓存：避免滚动时对未安装包反复发起 PackageManager binder 调用。 */
    private val failedPackages = ConcurrentHashMap.newKeySet<String>()

    /** 取图标：内存 → 负缓存 → 磁盘 → 绘制并回填两级缓存；失败返回 null。 */
    suspend fun get(packageName: String, targetSize: Int = DEFAULT_SIZE): Bitmap? {
        memoryCache.get(packageName)?.let { return it }
        if (packageName in failedPackages) return null
        return withContext(Dispatchers.IO) {
            diskCache.get(packageName, targetSize)?.also { memoryCache.put(packageName, it) }
                ?: drawAndCache(packageName, targetSize)
        }
    }

    /** 同步内存快路径：仅查 L1（主线程安全，不触发磁盘/绘制）。未命中返回 null，调用方走 [get] 异步链路。 */
    fun getSync(packageName: String): Bitmap? = memoryCache.get(packageName)

    /** 预加载一批包名图标（仅内存/磁盘均缺失者），供扫描后 data 层调用。开头清空负缓存：扫描代表本机应用集可能变化，历史失败全部允许重试。 */
    suspend fun preload(packageNames: List<String>, targetSize: Int = DEFAULT_SIZE) = withContext(Dispatchers.IO) {
        failedPackages.clear()
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
        failedPackages.remove(packageName)   // 成功后允许后续正常命中
        memoryCache.put(packageName, bitmap)
        diskCache.put(packageName, bitmap)
        bitmap
    } catch (e: Exception) {
        failedPackages.add(packageName)
        null
    }

    /** 移除单个包名图标（内存 + 磁盘，并解除负缓存登记，卸载→重装场景）。 */
    fun remove(packageName: String) {
        memoryCache.remove(packageName)
        diskCache.remove(packageName)
        failedPackages.remove(packageName)
    }

    /** 清理不在有效包集合内的图标（内存 + 磁盘），应用卸载/更新后调用。 */
    fun removeStale(validPackages: Set<String>) {
        for (key in memoryCache.snapshot().keys) {
            if (key !in validPackages) memoryCache.remove(key)
        }
        diskCache.removeStale(validPackages)
    }

    companion object {
        /** L1 内存上限（字节）。96px ARGB_8888 图标约 36KB，8MB ≈ 227 张，溢出走 LRU 淘汰 + 磁盘兜底。 */
        private const val MAX_MEMORY_CACHE_BYTES = 8 * 1024 * 1024
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
