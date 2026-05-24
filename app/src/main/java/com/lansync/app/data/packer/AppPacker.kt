package com.lansync.app.data.packer

import android.content.Context
import com.lansync.app.data.FileLogger
import com.lansync.app.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppPacker(private val context: Context) {

    private val apksDir: File by lazy {
        File(context.cacheDir, "apks").also { it.mkdirs() }
    }

    suspend fun packApp(appInfo: AppInfo): File? = withContext(Dispatchers.IO) {
        FileLogger.i("AppPacker", "packApp called: ${appInfo.packageName} v${appInfo.versionCode} isExtractable=${appInfo.isExtractable} paths=${appInfo.sourcePaths.size}")

        if (!appInfo.isExtractable || appInfo.sourcePaths.isEmpty()) {
            FileLogger.w("AppPacker", "packApp SKIPPED: ${appInfo.packageName} isExtractable=${appInfo.isExtractable} paths=${appInfo.sourcePaths.size}")
            return@withContext null
        }

        val packageName = appInfo.packageName.replace(".", "_")
        val versionCode = appInfo.versionCode
        val outputFile = File(apksDir, "${packageName}_${versionCode}.apks")

        try {
            createApksFile(appInfo.sourcePaths, outputFile)
            val size = outputFile.length()
            FileLogger.i("AppPacker", "packApp SUCCESS: ${appInfo.packageName} -> ${outputFile.name} (${size} bytes)")
            outputFile
        } catch (e: Exception) {
            FileLogger.e("AppPacker", "packApp FAILED: ${appInfo.packageName}: ${e.message}", e)
            outputFile.delete()
            null
        }
    }

    private fun createApksFile(sourcePaths: List<String>, outputFile: File) {
        val digest = MessageDigest.getInstance("MD5")

        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            sourcePaths.forEachIndexed { index, path ->
                try {
                    val sourceFile = File(path)
                    if (!sourceFile.canRead()) {
                        return@forEachIndexed
                    }

                    val entryName = if (index == 0) {
                        "base.apk"
                    } else {
                        "split_${index}.apk"
                    }

                    val zipEntry = ZipEntry(entryName)
                    zos.putNextEntry(zipEntry)

                    sourceFile.inputStream().use { fis ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                            zos.write(buffer, 0, bytesRead)
                        }
                    }

                    zos.closeEntry()
                } catch (e: SecurityException) {
                    // Skip files that cannot be read
                } catch (e: Exception) {
                    // Skip problematic files
                }
            }
        }
    }

    fun getMd5OfPackedFile(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    fun getPackedFile(packageName: String, versionCode: Long): File? {
        val sanitizedPackageName = packageName.replace(".", "_")
        val file = File(apksDir, "${sanitizedPackageName}_${versionCode}.apks")
        return if (file.exists()) file else null
    }

    fun cleanupOldPacks(maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        val cutoffTime = System.currentTimeMillis() - maxAgeMs
        apksDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }
    }

    fun getCacheSize(): Long {
        return apksDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun clearCache() {
        apksDir.listFiles()?.forEach { it.delete() }
    }
}
