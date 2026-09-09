package com.lansync.app.data.localapps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.lansync.app.data.FileLogger
import com.lansync.app.data.HashUtils
import com.lansync.app.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本机已安装应用扫描（SPEC §2.2 / §8.1 / §8.4）——旧 `data.scanner.AppScanner` 的「实现重写」。
 *
 * **决策 D2（SPEC §8.4）**：`md5 = HashUtils.md5(sourcePaths) ?: ""` 且 `isExtractable` 保持 `true`——
 * `AppInfo(md5="", isExtractable=true)` 为**合法态**（HashUtils null 传播后不降级）。因加入 `sourcePaths`
 * 前已 `canRead()` 预筛，null 仅在扫描中途文件变不可读的竞态下出现，正常应用几乎不触发。
 *
 * Android/`PackageManager` 耦合，**非 JVM 单测目标**（编译校验 + 真机验收）；[LocalAppRepository] 经
 * [InstalledAppScanner] 抽象消费，可用 Fake 单测缓存/状态逻辑。
 */
class AppScanner(private val context: Context) : InstalledAppScanner {

    override suspend fun scanInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        }
        FileLogger.i(TAG, "getInstalledPackages returned ${packages.size} packages")
        val results = packages.mapNotNull { extractAppInfo(it, pm) }
        FileLogger.i(TAG, "Scan complete: ${results.size} apps (user=${results.count { !it.isSystemApp }}, system=${results.count { it.isSystemApp }})")
        results
    }

    private fun extractAppInfo(packageInfo: PackageInfo, pm: PackageManager): AppInfo? {
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val sourcePaths = mutableListOf<String>()
        return try {
            val sourceDir = applicationInfo.sourceDir
            if (sourceDir != null && File(sourceDir).canRead()) {
                sourcePaths.add(sourceDir)
            } else {
                return nonExtractable(applicationInfo, packageInfo, pm)
            }
            applicationInfo.splitSourceDirs?.forEach { split ->
                if (split != null && File(split).canRead()) sourcePaths.add(split)
            }

            val md5 = HashUtils.md5(sourcePaths) ?: ""   // D2：null → ""，isExtractable 仍为 true
            AppInfo(
                packageName = packageInfo.packageName,
                appName = applicationInfo.loadLabel(pm).toString(),
                versionName = packageInfo.versionName ?: "unknown",
                versionCode = packageInfo.longVersionCode,
                sourcePaths = sourcePaths,
                md5 = md5,
                isExtractable = true,
                fileSize = sourcePaths.sumOf { path -> runCatching { File(path).length() }.getOrDefault(0L) },
                isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isSplitApk = sourcePaths.size > 1
            )
        } catch (e: SecurityException) {
            nonExtractable(applicationInfo, packageInfo, pm)
        } catch (e: Exception) {
            nonExtractable(applicationInfo, packageInfo, pm)
        }
    }

    private fun nonExtractable(applicationInfo: ApplicationInfo, packageInfo: PackageInfo, pm: PackageManager): AppInfo {
        val appName = try {
            applicationInfo.loadLabel(pm).toString()
        } catch (e: Exception) {
            packageInfo.packageName
        }
        return AppInfo(
            packageName = packageInfo.packageName,
            appName = appName,
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = packageInfo.longVersionCode,
            sourcePaths = emptyList(),
            md5 = "",
            isExtractable = false,
            fileSize = 0L,
            isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        )
    }

    private companion object {
        const val TAG = "AppScanner"
    }
}
