package com.lansync.app.data.transfer

import com.lansync.app.data.installer.ApkInstaller
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

    suspend fun downloadApp(updateInfo: UpdateInfo): LanSyncClient.DownloadResult = withContext(Dispatchers.IO) {
        val remote = updateInfo.remoteApp
        val provider = updateInfo.providerDevice
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
        _downloadProgress.value = when (result) {
            is LanSyncClient.DownloadResult.Success -> DownloadProgress(remote.packageName, 100, DownloadProgress.Status.COMPLETED)
            is LanSyncClient.DownloadResult.Error -> DownloadProgress(remote.packageName, 0, DownloadProgress.Status.FAILED)
        }
        result
    }

    fun installApp(updateInfo: UpdateInfo): ApkInstaller.InstallationResult {
        val file = client.getDownloadedFile(updateInfo.remoteApp.packageName, updateInfo.remoteApp.versionCode)
        return if (file != null) {
            installApp(updateInfo, file)
        } else {
            _installStatus.value = InstallStatus.Failed(updateInfo.remoteApp.packageName, "Downloaded file not found")
            ApkInstaller.InstallationResult.Error("Downloaded file not found")
        }
    }

    fun installApp(updateInfo: UpdateInfo, file: File): ApkInstaller.InstallationResult {
        _installStatus.value = InstallStatus.Installing(updateInfo.remoteApp.packageName)
        return installer.installApks(file).also { r ->
            _installStatus.value = when (r) {
                is ApkInstaller.InstallationResult.Success -> InstallStatus.Success(updateInfo.remoteApp.packageName)
                is ApkInstaller.InstallationResult.Error -> InstallStatus.Failed(updateInfo.remoteApp.packageName, r.message)
            }
        }
    }

    suspend fun downloadAndInstallApp(updateInfo: UpdateInfo): ApkInstaller.InstallationResult =
        when (val dl = downloadApp(updateInfo)) {
            is LanSyncClient.DownloadResult.Success -> installApp(updateInfo, dl.file)
            is LanSyncClient.DownloadResult.Error -> {
                _installStatus.value = InstallStatus.Failed(updateInfo.remoteApp.packageName, dl.message)
                ApkInstaller.InstallationResult.Error(dl.message)
            }
        }

    fun installDownloadedFile(fileName: String, pkgName: String): ApkInstaller.InstallationResult {
        _installStatus.value = InstallStatus.Installing(pkgName)
        val file = client.getDownloadedFileFromName(fileName)
        return if (file != null) {
            installer.installApks(file).also { r ->
                _installStatus.value = when (r) {
                    is ApkInstaller.InstallationResult.Success -> InstallStatus.Success(pkgName)
                    is ApkInstaller.InstallationResult.Error -> InstallStatus.Failed(pkgName, r.message)
                }
            }
        } else {
            _installStatus.value = InstallStatus.Failed(pkgName, "文件不存在")
            ApkInstaller.InstallationResult.Error("文件不存在: $fileName")
        }
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
        data class Failed(val packageName: String, val message: String) : InstallStatus()
    }
}
