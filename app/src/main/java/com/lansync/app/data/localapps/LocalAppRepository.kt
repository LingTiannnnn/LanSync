package com.lansync.app.data.localapps

import com.lansync.app.data.FileLogger
import com.lansync.app.data.model.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本机应用仓库（ARCH §3.3）——旧 `AppRepository` 中「扫描触发 + `_localApps` + `local_apps_cache.json` 读写」职责的重写。
 *
 * **启动策略**（ARCH §3.3 / REPORT 7.2#8）：先 [loadCacheSkeleton] 用缓存骨架填充（启动即展示），
 * 再由调用方（Phase 4 接线）后台 [scanAndRefresh] 静默校验刷新，避免陈旧缓存导致的短暂脏展示与「install not found」。
 *
 * **边界**（保持可 JVM 单测、不接 UI）：仅「扫描 + 缓存 + 状态」。
 * - **不做图标预加载**（旧 `AppRepository` 经 `ui.components.preloadIcon`，属 legacy-known-issue L2，Phase 4 处理）；
 *   改为 [ScanResult] 暴露新增/移除包名，由接线层决定如何预加载（下沉 data 层，不反向依赖 UI）。
 * - **不通知已连接设备刷新**（属连接层，Phase 4 接线）。
 *
 * 缓存 JSON 用 `Json { ignoreUnknownKeys = true }`（本机私有持久化格式，SPEC §1.2 注：非线上协议，
 * 与线上 DTO 的 prettyPrint/isLenient 配置不同）。
 */
class LocalAppRepository(
    private val scanner: InstalledAppScanner,
    private val cacheFile: File,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    private val _localApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val localApps: StateFlow<List<AppInfo>> = _localApps.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** 缓存文件是否存在且非空（决定启动是否走「骨架展示」路径）。 */
    fun hasCache(): Boolean = cacheFile.exists() && cacheFile.length() > 0

    /** 用缓存骨架填充 [localApps]（不触发扫描）；返回加载条数（0 表示无缓存或缓存损坏）。 */
    fun loadCacheSkeleton(): Int {
        val cached = readCache()
        if (cached.isNotEmpty()) {
            _localApps.value = cached
            FileLogger.i(TAG, "loadCacheSkeleton: ${cached.size} apps from cache")
        }
        return cached.size
    }

    /**
     * 扫描并刷新 [localApps] + 落盘缓存。返回 [ScanResult]（含与上一版的包名差异，供接线层做图标预加载/刷新通知）。
     * 扫描失败时保留现有 [localApps]，不清空。
     */
    suspend fun scanAndRefresh(): ScanResult {
        _isScanning.value = true
        return try {
            val previousPackages = _localApps.value.map { it.packageName }.toSet()
            val apps = scanner.scanInstalledApps()
            _localApps.value = apps
            writeCache(apps)
            val currentPackages = apps.map { it.packageName }.toSet()
            FileLogger.i(TAG, "scanAndRefresh: ${apps.size} apps (+${currentPackages.size - (currentPackages intersect previousPackages).size}/-${(previousPackages - currentPackages).size})")
            ScanResult(
                apps = apps,
                addedPackages = currentPackages - previousPackages,
                removedPackages = previousPackages - currentPackages
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "scanAndRefresh failed", e)
            ScanResult(_localApps.value, emptySet(), emptySet())
        } finally {
            _isScanning.value = false
        }
    }

    private fun readCache(): List<AppInfo> = try {
        if (cacheFile.exists()) json.decodeFromString(cacheFile.readText()) else emptyList()
    } catch (e: Exception) {
        FileLogger.e(TAG, "readCache failed, deleting corrupt cache", e)
        cacheFile.delete()
        emptyList()
    }

    private fun writeCache(apps: List<AppInfo>) {
        try {
            cacheFile.writeText(json.encodeToString(apps))
        } catch (e: Exception) {
            FileLogger.e(TAG, "writeCache failed", e)
        }
    }

    /** 一次扫描的结果 + 与上一版的包名差异（接线层据此做图标预加载/清理，避免本类依赖 UI）。 */
    data class ScanResult(
        val apps: List<AppInfo>,
        val addedPackages: Set<String>,
        val removedPackages: Set<String>
    )

    private companion object {
        const val TAG = "LocalAppRepository"
    }
}
