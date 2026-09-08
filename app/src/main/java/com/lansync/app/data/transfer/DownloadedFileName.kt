package com.lansync.app.data.transfer

import java.io.File

/**
 * 下载文件命名与包名解析的**单一权威实现**（SPEC.md §5.3 / §5.4）。
 *
 * 收敛旧代码中 `AppListClient.extractPackageName` 与 `MainViewModel.extractPackageNameFromFile`
 * 的重复实现（ARCHITECTURE.md P7）。纯函数、无 Android 依赖，可独立单测。
 */
object DownloadedFileName {

    /** 包名中的 `.` 替换为 `_`（SPEC.md §5.3）。 */
    fun underscore(packageName: String): String = packageName.replace(".", "_")

    /**
     * 服务端打包产物名（SPEC.md §5.3）：`{pkg下划线}_{versionCode}.apks`（split）或 `.apk`（单包）。
     */
    fun serverArtifactName(packageName: String, versionCode: Long, isSplitApk: Boolean): String {
        val ext = if (isSplitApk) "apks" else "apk"
        return "${underscore(packageName)}_$versionCode.$ext"
    }

    /**
     * 客户端下载兜底名（仅当响应缺 Content-Disposition 时使用，SPEC.md §5.3）。
     * 恒为 `.apks`；[versionCode] 为 null 时无版本段（对应 downloadLatestApksFile）。
     */
    fun defaultDownloadName(packageName: String, versionCode: Long?): String =
        if (versionCode != null) "${underscore(packageName)}_$versionCode.apks"
        else "${underscore(packageName)}.apks"

    /**
     * 从文件名反推包名（SPEC.md §5.4）。
     * 去扩展名 → 按 `_` 分段 → 取最后一个纯数字段(versionCode)之前的部分以 `.` 连接。
     */
    fun parsePackageName(fileName: String): String {
        val nameWithoutExt = when {
            fileName.endsWith(".apks") -> fileName.removeSuffix(".apks")
            fileName.endsWith(".apk") -> fileName.removeSuffix(".apk")
            else -> fileName
        }
        val parts = nameWithoutExt.split("_")
        val versionEnd = parts.indexOfLast { it.toLongOrNull() != null }
        return if (versionEnd <= 0) {
            nameWithoutExt.replace("_", ".")
        } else {
            parts.take(versionEnd).joinToString(".")
        }
    }

    /**
     * 按包名+版本正向精确匹配已下载文件（SPEC.md §5.4）：先 `.apks` 再 `.apk`，均无则 null。
     * 这是安装取文件的主路径，**不依赖** [parsePackageName] 的逆向猜测。
     */
    fun resolveDownloadedFile(downloadsDir: File, packageName: String, versionCode: Long): File? {
        val baseName = "${underscore(packageName)}_$versionCode"
        val apksFile = File(downloadsDir, "$baseName.apks")
        if (apksFile.exists()) return apksFile
        val apkFile = File(downloadsDir, "$baseName.apk")
        if (apkFile.exists()) return apkFile
        return null
    }
}
