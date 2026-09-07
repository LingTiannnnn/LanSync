package com.lansync.app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.IncomingConnectRequest
import com.lansync.app.data.model.RemoteAppEntry
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo
import com.lansync.app.data.client.AppListClient
import com.lansync.app.data.installer.ApkInstaller
import com.lansync.app.data.repository.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class UiState(
    val localApps: List<com.lansync.app.data.model.AppInfo> = emptyList(),
    val discoveredDevices: List<DeviceInfo> = emptyList(),
    val connectedDevices: List<DeviceInfo> = emptyList(),
    val availableUpdates: List<UpdateInfo> = emptyList(),
    val syncDiffs: List<SyncDiff> = emptyList(),
    val selectedUpdates: Set<String> = emptySet(),
    val remoteAppSelections: Set<String> = emptySet(),
    val isRunning: Boolean = false,
    val isScanningApps: Boolean = false,
    val needsInitialScan: Boolean = false,
    val isDownloading: Boolean = false,
    val currentDownloadProgress: AppRepository.DownloadProgress? = null,
    val installStatus: AppRepository.InstallStatus? = null,
    val serverPort: Int = 0,
    val isLoading: Boolean = false,
    val connectionError: String? = null,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val isFetchingRemoteApps: Boolean = false,
    val operationMessage: String? = null,
    val incomingRequests: List<IncomingConnectRequest> = emptyList(),
    val downloadedFiles: List<AppListClient.DownloadedFileInfo> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val repository = AppRepository.getInstance(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var _lastDownloadedUpdateInfo: UpdateInfo? = null

    init {
        observeRepositoryState()
        autoStart()
    }

    private fun autoStart() {
        if (!repository.hasLocalAppCache()) {
            _uiState.value = _uiState.value.copy(needsInitialScan = true)
            viewModelScope.launch {
                repository.scanLocalApps()
                _uiState.value = _uiState.value.copy(needsInitialScan = false)
                repository.start()
            }
            return
        }

        viewModelScope.launch {
            repository.start()
        }
        viewModelScope.launch {
            repository.scanLocalApps()
        }
    }

    private fun observeRepositoryState() {
        // 使用类型安全的 combine，按功能分组避免超过 Kotlin 5 个 Flow 的重载限制
        viewModelScope.launch {
            combine(
                repository.localApps,
                repository.discoveredDevices,
                repository.connectedDevices,
                repository.availableUpdates,
                repository.syncDiffs
            ) { localApps, discoveredDevices, connectedDevices, availableUpdates, syncDiffs ->
                _uiState.value = _uiState.value.copy(
                    localApps = localApps,
                    discoveredDevices = discoveredDevices,
                    connectedDevices = connectedDevices,
                    availableUpdates = availableUpdates,
                    syncDiffs = syncDiffs
                )
            }.collect { }
        }

        viewModelScope.launch {
            repository.incomingRequests.collect { incomingRequests ->
                _uiState.value = _uiState.value.copy(incomingRequests = incomingRequests)
            }
        }

        viewModelScope.launch {
            combine(
                repository.isRunning,
                repository.isScanningApps,
                repository.serverPort,
                repository.downloadProgress,
                repository.installStatus
            ) { isRunning, isScanningApps, serverPort, downloadProgress, installStatus ->
                _uiState.value = _uiState.value.copy(
                    isRunning = isRunning,
                    isScanningApps = isScanningApps,
                    serverPort = serverPort,
                    currentDownloadProgress = downloadProgress,
                    isDownloading = downloadProgress?.status == AppRepository.DownloadProgress.Status.DOWNLOADING,
                    installStatus = installStatus
                )
            }.collect { }
        }
    }

    fun toggleRunning() {
        viewModelScope.launch {
            if (_uiState.value.isRunning) {
                _uiState.value = _uiState.value.copy(
                    isStopping = true,
                    operationMessage = "正在停止服务..."
                )
                try {
                    repository.stop()
                } finally {
                    _uiState.value = _uiState.value.copy(
                        isStopping = false,
                        operationMessage = null
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isStarting = true,
                    operationMessage = "正在启动服务..."
                )
                try {
                    repository.start()
                } finally {
                    _uiState.value = _uiState.value.copy(
                        isStarting = false,
                        operationMessage = null
                    )
                }
            }
        }
    }

    fun forceStartSync() {
        repository.forceStartSync()
    }

    fun connectDevice(device: DeviceInfo) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                connectionError = null
            )
            try {
                val success = repository.connectDevice(device)
                if (!success) {
                    _uiState.value = _uiState.value.copy(
                        connectionError = "连接 ${device.deviceName} 失败"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    connectionError = "连接异常: ${e.message}"
                )
            }
        }
    }

    fun dismissConnectionError() {
        _uiState.value = _uiState.value.copy(connectionError = null)
    }

    fun disconnectDevice(device: DeviceInfo) {
        repository.disconnectDevice(device)
    }

    fun acceptIncomingRequest(requestId: String) {
        repository.handleIncomingRequest(requestId, true)
    }

    fun rejectIncomingRequest(requestId: String) {
        repository.handleIncomingRequest(requestId, false)
    }

    fun dismissIncomingRequest(requestId: String) {
        repository.dismissIncomingRequest(requestId)
    }

    fun refreshDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isFetchingRemoteApps = true,
                operationMessage = "正在刷新..."
            )
            try {
                repository.refreshDeviceAppLists()
            } finally {
                _uiState.value = _uiState.value.copy(
                    isFetchingRemoteApps = false,
                    operationMessage = null
                )
            }
        }
    }

    fun refreshConnectedDevice(device: DeviceInfo) {
        viewModelScope.launch {
            repository.refreshConnectedDevice(device)
        }
    }

    fun toggleSelectedUpdate(packageName: String) {
        _uiState.value = _uiState.value.copy(
            selectedUpdates = if (_uiState.value.selectedUpdates.contains(packageName)) {
                _uiState.value.selectedUpdates - packageName
            } else {
                _uiState.value.selectedUpdates + packageName
            }
        )
    }

    fun selectAllUpdates() {
        _uiState.value = _uiState.value.copy(
            selectedUpdates = _uiState.value.availableUpdates.map { it.remoteApp.packageName }.toSet()
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedUpdates = emptySet())
    }

    fun startBatchUpdate() {
        viewModelScope.launch {
            val selected = _uiState.value.selectedUpdates
            val updates = _uiState.value.availableUpdates.filter {
                it.remoteApp.packageName in selected
            }
            Log.i(TAG, "startBatchUpdate: ${updates.size} apps selected")

            val errors = mutableListOf<String>()
            updates.forEach { update ->
                Log.d(TAG, "Batch: downloading/installing ${update.remoteApp.packageName}")
                _lastDownloadedUpdateInfo = update
                val result = repository.downloadAndInstallApp(update)
                if (result is ApkInstaller.InstallationResult.Error) {
                    Log.w(TAG, "Batch: FAILED for ${update.remoteApp.packageName}: ${result.message}")
                    errors.add("${update.remoteApp.appName}: ${result.message}")
                }
            }

            if (errors.isNotEmpty()) {
                val errorMsg = "批量更新完成，其中 ${errors.size}/${updates.size} 个失败:\n${errors.joinToString("\n")}"
                Log.w(TAG, errorMsg)
                _uiState.value = _uiState.value.copy(
                    operationMessage = errorMsg
                )
            } else {
                Log.i(TAG, "startBatchUpdate: all ${updates.size} apps completed successfully")
            }
        }
    }

    fun installSelectedUpdate(updateInfo: UpdateInfo) {
        viewModelScope.launch {
            Log.i(TAG, "installSelectedUpdate: pkg=${updateInfo.remoteApp.packageName} from=${updateInfo.providerDevice.deviceName}")
            _lastDownloadedUpdateInfo = updateInfo
            repository.downloadAndInstallApp(updateInfo)
        }
    }

    fun installLastDownloaded() {
        val info = _lastDownloadedUpdateInfo ?: return
        viewModelScope.launch {
            repository.installApp(info)
        }
    }

    fun loadDownloadedFiles() {
        viewModelScope.launch {
            val files = repository.getDownloadedFiles()
            _uiState.value = _uiState.value.copy(downloadedFiles = files)
        }
    }

    fun deleteDownloadedFiles(fileNames: List<String>) {
        viewModelScope.launch {
            val deleted = repository.deleteDownloadedFiles(fileNames)
            Log.i(TAG, "Deleted $deleted downloaded files")
            loadDownloadedFiles()
        }
    }

    fun installDownloadedFile(fileName: String) {
        viewModelScope.launch {
            // 使用与 AppListClient.extractPackageName 一致的包名提取逻辑
            val pkgName = extractPackageNameFromFile(fileName)
            val result = repository.installDownloadedFile(fileName, pkgName)
            Log.i(TAG, "installDownloadedFile: $fileName -> ${result::class.simpleName}")
        }
    }

    /**
     * 从下载文件名中提取包名
     * 文件名格式: com_example_app_123.apks -> com.example.app (去掉末尾versionCode)
     */
    private fun extractPackageNameFromFile(fileName: String): String {
        val nameWithoutExt = when {
            fileName.endsWith(".apks") -> fileName.removeSuffix(".apks")
            fileName.endsWith(".apk") -> fileName.removeSuffix(".apk")
            else -> fileName
        }
        val parts = nameWithoutExt.split("_")
        // 找到最后一个数字分段（即 versionCode），前面的部分即为包名
        val versionEnd = parts.indexOfLast { it.toLongOrNull() != null }
        return if (versionEnd <= 0) {
            nameWithoutExt.replace("_", ".")
        } else {
            parts.take(versionEnd).joinToString(".")
        }
    }

    fun clearInstallStatus() {
        repository.clearInstallStatus()
    }

    fun clearDownloadProgress() {
        repository.clearDownloadProgress()
    }

    fun toggleRemoteAppSelection(packageName: String) {
        _uiState.value = _uiState.value.copy(
            remoteAppSelections = if (_uiState.value.remoteAppSelections.contains(packageName)) {
                _uiState.value.remoteAppSelections - packageName
            } else {
                _uiState.value.remoteAppSelections + packageName
            }
        )
    }

    fun selectAllRemoteApps(entries: List<RemoteAppEntry>) {
        _uiState.value = _uiState.value.copy(
            remoteAppSelections = entries.map { it.app.packageName }.toSet()
        )
    }

    fun clearRemoteAppSelection() {
        _uiState.value = _uiState.value.copy(remoteAppSelections = emptySet())
    }

    fun pullSelectedRemoteApps(entries: List<RemoteAppEntry>) {
        viewModelScope.launch {
            val selected = _uiState.value.remoteAppSelections
            val targets = entries.filter { it.app.packageName in selected }
            Log.i(TAG, "pullSelectedRemoteApps: ${targets.size} apps selected")

            val errors = mutableListOf<String>()
            targets.forEach { entry ->
                val updateInfo = UpdateInfo(
                    localApp = null,
                    remoteApp = entry.app,
                    providerDevice = entry.sourceDevice,
                    canUpdate = true
                )
                Log.d(TAG, "Pulling remote app: ${entry.app.packageName} from ${entry.sourceDevice.deviceName}")
                _lastDownloadedUpdateInfo = updateInfo
                val result = repository.downloadAndInstallApp(updateInfo)
                if (result is ApkInstaller.InstallationResult.Error) {
                    Log.w(TAG, "Pull FAILED for ${entry.app.packageName}: ${result.message}")
                    errors.add("${entry.app.appName}: ${result.message}")
                }
            }

            if (errors.isNotEmpty()) {
                val errorMsg = "拉取完成，其中 ${errors.size}/${targets.size} 个失败:\n${errors.joinToString("\n")}"
                Log.w(TAG, errorMsg)
                _uiState.value = _uiState.value.copy(
                    operationMessage = errorMsg
                )
            } else {
                Log.i(TAG, "pullSelectedRemoteApps: all ${targets.size} apps completed successfully")
            }
            clearRemoteAppSelection()
        }
    }

    fun pullRemoteApp(entry: RemoteAppEntry) {
        viewModelScope.launch {
            val updateInfo = UpdateInfo(
                localApp = null,
                remoteApp = entry.app,
                providerDevice = entry.sourceDevice,
                canUpdate = true
            )
            Log.i(TAG, "pullRemoteApp: ${entry.app.packageName} from ${entry.sourceDevice.deviceName}")
            _lastDownloadedUpdateInfo = updateInfo
            repository.downloadAndInstallApp(updateInfo)
        }
    }
}
