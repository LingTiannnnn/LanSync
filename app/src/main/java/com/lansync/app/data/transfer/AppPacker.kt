package com.lansync.app.data.transfer

import com.lansync.app.data.FileLogger
import com.lansync.app.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * APK 打包（SPEC §5.3 / §8.1）——旧 `data.packer.AppPacker` 的「实现重写」。
 *
 * 差异（均为契约内改进，不改变线上产物字节形状）：
 * - **输出目录构造注入**（`apksDir: File`）而非 `Context.cacheDir`，使打包逻辑可 JVM 单测（`TemporaryFolder`）。
 * - **移除死代码**：旧 `createApksFile` 内计算了一个 MD5 digest 但从不返回/使用（SPEC §8.1 已确认为死代码）；
 *   `X-MD5` 一律由服务端 `sendPackedFile` 对产物实时计算（D1），打包器不再涉及任何哈希。
 * - 命名统一走 [DownloadedFileName.serverArtifactName]（消除 P7 重复实现，SPEC §5.3）。
 *
 * 产物结构（SPEC §5.3，与旧实现一致）：单包 = `sourcePaths[0]` 字节副本 `.apk`；
 * split = zip（`base.apk` + `split_N.apk`）`.apks`，不可读条目跳过（`canRead()` + 逐条目容错）。
 */
class AppPacker(private val apksDir: File) {

    init {
        apksDir.mkdirs()
    }

    /** 打包产物；不可提取 / 无源路径 / 异常 → null（异常时删除半成品）。 */
    suspend fun packApp(appInfo: AppInfo): File? = withContext(Dispatchers.IO) {
        if (!appInfo.isExtractable || appInfo.sourcePaths.isEmpty()) {
            FileLogger.w(TAG, "packApp SKIPPED: ${appInfo.packageName} extractable=${appInfo.isExtractable} paths=${appInfo.sourcePaths.size}")
            return@withContext null
        }
        val outFile = File(
            apksDir,
            DownloadedFileName.serverArtifactName(appInfo.packageName, appInfo.versionCode, appInfo.isSplitApk)
        )
        try {
            if (appInfo.isSplitApk) createApksFile(appInfo.sourcePaths, outFile)
            else copySingleApk(appInfo.sourcePaths.first(), outFile)
            FileLogger.i(TAG, "packApp OK: ${appInfo.packageName} -> ${outFile.name} (${outFile.length()} bytes)")
            outFile
        } catch (e: Exception) {
            FileLogger.e(TAG, "packApp FAILED: ${appInfo.packageName}: ${e.message}", e)
            outFile.delete()
            null
        }
    }

    private fun copySingleApk(sourcePath: String, outputFile: File) {
        File(sourcePath).inputStream().use { input ->
            FileOutputStream(outputFile).use { output -> input.copyTo(output, 8192) }
        }
    }

    private fun createApksFile(sourcePaths: List<String>, outputFile: File) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            sourcePaths.forEachIndexed { index, path ->
                try {
                    val sourceFile = File(path)
                    if (!sourceFile.canRead()) return@forEachIndexed
                    val entryName = if (index == 0) "base.apk" else "split_${index}.apk"
                    zos.putNextEntry(ZipEntry(entryName))
                    sourceFile.inputStream().use { fis -> fis.copyTo(zos, 8192) }
                    zos.closeEntry()
                } catch (e: Exception) {
                    // 逐条目容错（与旧实现一致）：单条目失败不阻断整包
                    FileLogger.w(TAG, "createApksFile: error on $path: ${e.message}")
                }
            }
        }
    }

    /** 清空打包缓存（启动时调用，SPEC §5.3）。 */
    fun clearCache() {
        apksDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val TAG = "AppPacker"
    }
}
