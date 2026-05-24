package com.lansync.app.data.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.lansync.app.data.FileLogger
import com.lansync.app.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class AppScanner(private val context: Context) {

    companion object {
        private const val TAG = "AppScanner"
    }

    suspend fun scanInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        }

        FileLogger.i(TAG, "getInstalledPackages returned ${packages.size} total packages")

        val results = packages.mapNotNull { packageInfo ->
            extractAppInfo(packageInfo, packageManager)
        }

        val userCount = results.count { !it.isSystemApp }
        val systemCount = results.count { it.isSystemApp }
        FileLogger.i(TAG, "Scan complete: ${results.size} apps (user=$userCount, system=$systemCount)")

        if (results.size < packages.size) {
            FileLogger.w(TAG, "${packages.size - results.size} packages were filtered out during extraction")
        }

        results
    }

    private fun extractAppInfo(packageInfo: PackageInfo, packageManager: PackageManager): AppInfo? {
        val applicationInfo = packageInfo.applicationInfo ?: return null

        val sourcePaths = mutableListOf<String>()

        try {
            val sourceDir = applicationInfo.sourceDir
            if (sourceDir != null && File(sourceDir).canRead()) {
                sourcePaths.add(sourceDir)
            } else {
                return createNonExtractableAppInfo(applicationInfo, packageInfo, packageManager)
            }

            applicationInfo.splitSourceDirs?.forEach { splitPath ->
                if (splitPath != null && File(splitPath).canRead()) {
                    sourcePaths.add(splitPath)
                }
            }

            val appName = applicationInfo.loadLabel(packageManager).toString()
            val versionName = packageInfo.versionName ?: "unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            val fileSize = calculateTotalFileSize(sourcePaths)
            val md5 = calculateCombinedMd5(sourcePaths)
            val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            return AppInfo(
                packageName = packageInfo.packageName,
                appName = appName,
                versionName = versionName,
                versionCode = versionCode,
                sourcePaths = sourcePaths,
                md5 = md5,
                isExtractable = true,
                fileSize = fileSize,
                isSystemApp = isSystemApp
            )
        } catch (e: SecurityException) {
            return createNonExtractableAppInfo(applicationInfo, packageInfo, packageManager)
        } catch (e: Exception) {
            return createNonExtractableAppInfo(applicationInfo, packageInfo, packageManager)
        }
    }

    private fun createNonExtractableAppInfo(
        applicationInfo: ApplicationInfo,
        packageInfo: PackageInfo,
        packageManager: PackageManager
    ): AppInfo {
        val appName = try {
            applicationInfo.loadLabel(packageManager).toString()
        } catch (e: Exception) {
            packageInfo.packageName
        }

        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        return AppInfo(
            packageName = packageInfo.packageName,
            appName = appName,
            versionName = versionName,
            versionCode = versionCode,
            sourcePaths = emptyList(),
            md5 = "",
            isExtractable = false,
            fileSize = 0L,
            isSystemApp = isSystemApp
        )
    }

    private fun calculateTotalFileSize(paths: List<String>): Long {
        return paths.sumOf { path ->
            try {
                File(path).length()
            } catch (e: Exception) {
                0L
            }
        }
    }

    private fun calculateCombinedMd5(paths: List<String>): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")

            paths.forEach { path ->
                try {
                    val file = File(path)
                    if (file.canRead()) {
                        file.inputStream().use { fis ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                digest.update(buffer, 0, bytesRead)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip files that cannot be read
                }
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
