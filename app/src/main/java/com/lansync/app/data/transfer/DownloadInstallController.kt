package com.lansync.app.data.transfer

import com.lansync.app.data.FileLogger
import com.lansync.app.data.installer.ApkInstaller
import com.lansync.app.data.model.InstallFailure
import com.lansync.app.data.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载/安装协调器（ARCH §3.4）——旧 `AppRepository` 中 `downloadApp`/`installApp`/文件管理职责的重写。
 *
 * 校验以 **`X-MD5`（传输产物）为唯一依据**（SPEC §8.3 D1，废弃 expectedMd5）——直接复用 [LanSyncClient]，
 * 其 `downloadApksFile` 已不含 expectedMd5 兜底。包名解析统一走 [DownloadedFileName]（消除 P7）。
 * `DownloadProgress`/`InstallStatus` 由旧 `AppRepository` 嵌套类迁移至此（门面/UI 引用点随之更新）。
 */
class DownloadInstallController(
    private val client: LanSyncClient,
    private val installer: ApkInstaller
) {

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _installStatus = MutableStateFlow<InstallStatus?>(null)
    val installStatus: StateFlow<InstallStatus?> = _installStatus.asStateFlow()

    /** 用户取消标志：`downloadApp` 每次开工前复位，取消后以清空进度 + Cancelled 结果收场。 */
    @Volatile
    private var cancelRequested = false

    /**
     * 取消当前下载（弹窗「取消」按钮）：中断底层 OkHttp 调用（阻塞读在 IO 线程上，
     * 协程取消打不断它，必须 `Call.cancel()`）。半成品文件由客户端失败分支删除。
     */
    fun cancelDownload() {
        cancelRequested = true
        client.cancelCurrentDownload()
    }

    suspend fun downloadApp(updateInfo: UpdateInfo): LanSyncClient.DownloadResult = withContext(Dispatchers.IO) {
        val remote = updateInfo.remoteApp
        val provider = updateInfo.providerDevice
        cancelRequested = false
        _downloadProgress.value = DownloadProgress(remote.packageName, 0, DownloadProgress.Status.DOWNLOADING)
        val result = client.downloadApksFile(
            ipAddress = provider.ipAddress,
            port = provider.port,
            packageName = remote.packageName,
            versionCode = remote.versionCode,
            onProgress = { p ->
                _downloadProgress.value = DownloadProgress(remote.packageName, p.coerceAtMost(99), DownloadProgress.Status.DOWNLOADING)
            }
        )
        if (cancelRequested) {
            // 用户主动取消：清掉进度让弹窗关闭，调用方按 Cancelled 语义处理，不走失败文案
            FileLogger.i(TAG, "download cancelled: ${remote.packageName}")
            _downloadProgress.value = null
            return@withContext LanSyncClient.DownloadResult.Error("Download cancelled")
        }
        _downloadProgress.value = when (result) {
            is LanSyncClient.DownloadResult.Success -> DownloadProgress(remote.packageName, 100, DownloadProgress.Status.COMPLETED)
            is LanSyncClient.DownloadResult.Error -> DownloadProgress(remote.packageName, 0, DownloadProgress.Status.FAILED)
        }
        result
    }

    fun installApp(updateInfo: UpdateInfo): ApkInstaller.InstallationResult {
        val pkg = updateInfo.remoteApp.packageName
        val file = client.getDownloadedFile(pkg, updateInfo.remoteApp.versionCode)
        return if (file != null) {
            installApp(updateInfo, file)
        } else {
            val detail = "no downloaded file for $pkg/${updateInfo.remoteApp.versionCode}"
            markInstallFailure(pkg, InstallFailure.FILE_MISSING, detail)
            ApkInstaller.InstallationResult.Error(InstallFailure.FILE_MISSING, detail)
        }
    }

    fun installApp(updateInfo: UpdateInfo, file: File): ApkInstaller.InstallationResult {
        _installStatus.value = InstallStatus.Installing(updateInfo.remoteApp.packageName)
        return installer.installApks(file).also { r ->
            when (r) {
                is ApkInstaller.InstallationResult.Success ->
                    _installStatus.value = InstallStatus.Success(updateInfo.remoteApp.packageName)
                is ApkInstaller.InstallationResult.Error ->
                    markInstallFailure(updateInfo.remoteApp.packageName, r.failure, r.message)
                is ApkInstaller.InstallationResult.Cancelled -> {} // 安装阶段不产生取消（取消只发生在下载）
            }
        }
    }

    suspend fun downloadAndInstallApp(updateInfo: UpdateInfo): ApkInstaller.InstallationResult =
        when (val dl = downloadApp(updateInfo)) {
            is LanSyncClient.DownloadResult.Success -> installApp(updateInfo, dl.file)
            is LanSyncClient.DownloadResult.Error -> {
                if (cancelRequested) {
                    ApkInstaller.InstallationResult.Cancelled
                } else {
                    markInstallFailure(updateInfo.remoteApp.packageName, InstallFailure.DOWNLOAD_FAILED, dl.message)
                    ApkInstaller.InstallationResult.Error(InstallFailure.DOWNLOAD_FAILED, dl.message)
                }
            }
        }

    fun installDownloadedFile(fileName: String, pkgName: String): ApkInstaller.InstallationResult {
        _installStatus.value = InstallStatus.Installing(pkgName)
        val file = client.getDownloadedFileFromName(fileName)
        return if (file != null) {
            installer.installApks(file).also { r ->
                when (r) {
                    is ApkInstaller.InstallationResult.Success ->
                        _installStatus.value = InstallStatus.Success(pkgName)
                    is ApkInstaller.InstallationResult.Error ->
                        markInstallFailure(pkgName, r.failure, r.message)
                    is ApkInstaller.InstallationResult.Cancelled -> {} // 安装阶段不产生取消（取消只发生在下载）
                }
            }
        } else {
            val detail = "no such downloaded file: $fileName"
            markInstallFailure(pkgName, InstallFailure.FILE_MISSING, detail)
            ApkInstaller.InstallationResult.Error(InstallFailure.FILE_MISSING, detail)
        }
    }

    /**
     * 失败状态的唯一落点：`[InstallStatus.Failed.detail]` 是诊断原文（英文、可能含异常信息），
     * UI 只读 `failure` 类别映射文案，原文在这里进 `FileLogger`（ARCH §7.2），否则它就随字段一起丢了。
     */
    private fun markInstallFailure(pkg: String, failure: InstallFailure, detail: String?) {
        FileLogger.w(TAG, "install failed for $pkg: $failure ${detail.orEmpty()}")
        _installStatus.value = InstallStatus.Failed(pkg, failure, detail)
    }

    fun getDownloadedFiles(): List<LanSyncClient.DownloadedFileInfo> = client.getDownloadedFiles()
    fun deleteDownloadedFiles(fileNames: List<String>): Int = client.deleteDownloadedFiles(fileNames)
    fun getDownloadedFile(packageName: String, versionCode: Long): File? = client.getDownloadedFile(packageName, versionCode)
    fun clearDownloadProgress() { _downloadProgress.value = null }
    fun clearInstallStatus() { _installStatus.value = null }

    data class DownloadProgress(val packageName: String, val progress: Int, val status: Status) {
        enum class Status { DOWNLOADING, VERIFYING, COMPLETED, FAILED }
    }

    sealed class InstallStatus {
        data class Installing(val packageName: String) : InstallStatus()
        data class Success(val packageName: String) : InstallStatus()

        /**
         * [failure] 是 UI 唯一可渲染的信息；[detail]（下载/安装的诊断原文）由 [markInstallFailure]
         * 写入 `FileLogger`，不上屏，否则英文诊断会漏进中文界面并绕过 strings.xml 唯一来源。
         */
        data class Failed(
            val packageName: String,
            val failure: InstallFailure,
            val detail: String? = null,
        ) : InstallStatus()
    }

    private companion object {
        const val TAG = "DownloadInstallController"
    }
}
