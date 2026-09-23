package com.lansync.app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.app.R
import com.lansync.app.data.FileLogger
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.IncomingConnectRequest
import com.lansync.app.data.model.RemoteAppEntry
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo
import com.lansync.app.data.installer.ApkInstaller
import com.lansync.app.data.repository.LanSyncGraph
import com.lansync.app.data.transfer.DownloadInstallController
import com.lansync.app.data.transfer.DownloadedFileName
import com.lansync.app.data.transfer.LanSyncClient
import com.lansync.app.service.ForegroundSyncService
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
    val currentDownloadProgress: DownloadInstallController.DownloadProgress? = null,
    val installStatus: DownloadInstallController.InstallStatus? = null,
    val serverPort: Int = 0,
    val isLoading: Boolean = false,
    val connectionError: String? = null,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val isFetchingRemoteApps: Boolean = false,
    val operationMessage: String? = null,
    val incomingRequests: List<IncomingConnectRequest> = emptyList(),
    val downloadedFiles: List<LanSyncClient.DownloadedFileInfo> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val repository = LanSyncGraph.get(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var _lastDownloadedUpdateInfo: UpdateInfo? = null

    /** UI 文案统一走字符串资源（Phase 5：禁止硬编码字符串）。 */
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    init {
        observeRepositoryState()
        autoStart()
    }

    private fun autoStart() {
        // 先骨架填充（ARCH §3.3）：有缓存则启动即展示，再后台校验刷新；损坏/缺失返回 0 后走首扫
        repository.loadLocalAppCache()
        if (!repository.hasLocalAppCache()) {
            _uiState.value = _uiState.value.copy(needsInitialScan = true)
            viewModelScope.launch {
                try {
                    FileLogger.i(TAG, "initial scan start")
                    repository.scanLocalApps()
                    FileLogger.i(TAG, "initial scan done")
                    _uiState.value = _uiState.value.copy(needsInitialScan = false)
                    ForegroundSyncService.start(getApplication())
                } catch (t: Throwable) {
                    FileLogger.e(TAG, "autoStart initial path failed", t)
                    _uiState.value = _uiState.value.copy(needsInitialScan = false)
                }
            }
            return
        }

        try {
            ForegroundSyncService.start(getApplication())
        } catch (t: Throwable) {
            FileLogger.e(TAG, "ForegroundSyncService.start failed", t)
        }
        viewModelScope.launch {
            try {
                repository.scanLocalApps()
            } catch (t: Throwable) {
                FileLogger.e(TAG, "background rescan failed", t)
            }
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
                    isDownloading = downloadProgress?.status == DownloadInstallController.DownloadProgress.Status.DOWNLOADING,
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
                    operationMessage = str(R.string.op_stopping_service)
                )
                try {
                    ForegroundSyncService.stop(getApplication())
                } finally {
                    _uiState.value = _uiState.value.copy(
                        isStopping = false,
                        operationMessage = null
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isStarting = true,
                    operationMessage = str(R.string.op_starting_service)
                )
                try {
                    ForegroundSyncService.start(getApplication())
                } finally {
                    _uiState.value = _uiState.value.copy(
                        isStarting = false,
                        operationMessage = null
                    )
                }
            }
        }
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
                        connectionError = str(R.string.error_connect_failed, device.deviceName)
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    connectionError = str(R.string.error_connect_exception, e.message ?: "")
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
                operationMessage = str(R.string.op_refreshing)
            )
            try {
                repository.refreshDeviceAppLists()
                // 短暂保持进行中状态，让顶栏刷新动画可感知
                kotlinx.coroutines.delay(600)
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
                    errors.add(str(R.string.batch_error_line, update.remoteApp.appName, result.message))
                }
            }

            if (errors.isNotEmpty()) {
                val errorMsg = str(
                    R.string.batch_update_partial_failure,
                    errors.size, updates.size, errors.joinToString("\n")
                )
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
            // 包名解析统一走 DownloadedFileName（消除 P7 重复实现）
            val pkgName = DownloadedFileName.parsePackageName(fileName)
            val result = repository.installDownloadedFile(fileName, pkgName)
            Log.i(TAG, "installDownloadedFile: $fileName -> ${result::class.simpleName}")
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
                    errors.add(str(R.string.batch_error_line, entry.app.appName, result.message))
                }
            }

            if (errors.isNotEmpty()) {
                val errorMsg = str(
                    R.string.pull_partial_failure,
                    errors.size, targets.size, errors.joinToString("\n")
                )
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
