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
        FileLogger.i("AppPacker", "packApp called: ${appInfo.packageName} v${appInfo.versionCode} isExtractable=${appInfo.isExtractable} isSplitApk=${appInfo.isSplitApk} paths=${appInfo.sourcePaths.size}")

        if (!appInfo.isExtractable || appInfo.sourcePaths.isEmpty()) {
            FileLogger.w("AppPacker", "packApp SKIPPED: ${appInfo.packageName} isExtractable=${appInfo.isExtractable} paths=${appInfo.sourcePaths.size}")
            return@withContext null
        }

        val packageName = appInfo.packageName.replace(".", "_")
        val versionCode = appInfo.versionCode

        if (appInfo.isSplitApk) {
            val outputFile = File(apksDir, "${packageName}_${versionCode}.apks")
            try {
                createApksFile(appInfo.sourcePaths, outputFile)
                val size = outputFile.length()
                FileLogger.i("AppPacker", "packApp SUCCESS (split): ${appInfo.packageName} -> ${outputFile.name} (${size} bytes)")
                outputFile
            } catch (e: Exception) {
                FileLogger.e("AppPacker", "packApp FAILED: ${appInfo.packageName}: ${e.message}", e)
                outputFile.delete()
                null
            }
        } else {
            val outputFile = File(apksDir, "${packageName}_${versionCode}.apk")
            try {
                copySingleApk(appInfo.sourcePaths.first(), outputFile)
                val size = outputFile.length()
                FileLogger.i("AppPacker", "packApp SUCCESS (single): ${appInfo.packageName} -> ${outputFile.name} (${size} bytes)")
                outputFile
            } catch (e: Exception) {
                FileLogger.e("AppPacker", "packApp FAILED: ${appInfo.packageName}: ${e.message}", e)
                outputFile.delete()
                null
            }
        }
    }

    private fun copySingleApk(sourcePath: String, outputFile: File) {
        val sourceFile = File(sourcePath)
        sourceFile.inputStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
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
                } catch (e: Exception) {
                }
            }
        }
    }

    fun clearCache() {
        apksDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
