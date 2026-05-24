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
    val isConnecting: Boolean = false,
    val connectingDeviceName: String? = null,
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
    val lastDownloadedUpdateInfo: UpdateInfo? get() = _lastDownloadedUpdateInfo

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

    fun performInitialScan() {
        if (_uiState.value.needsInitialScan) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(needsInitialScan = true)
            repository.scanLocalApps()
            _uiState.value = _uiState.value.copy(needsInitialScan = false)
            repository.start()
        }
    }

    private fun observeRepositoryState() {
        viewModelScope.launch {
            @Suppress("UNCHECKED_CAST")
            val flows = listOf<kotlinx.coroutines.flow.Flow<Any?>>(
                repository.localApps as kotlinx.coroutines.flow.Flow<Any?>,
                repository.discoveredDevices as kotlinx.coroutines.flow.Flow<Any?>,
                repository.connectedDevices as kotlinx.coroutines.flow.Flow<Any?>,
                repository.availableUpdates as kotlinx.coroutines.flow.Flow<Any?>,
                repository.syncDiffs as kotlinx.coroutines.flow.Flow<Any?>,
                repository.isRunning as kotlinx.coroutines.flow.Flow<Any?>,
                repository.isScanningApps as kotlinx.coroutines.flow.Flow<Any?>,
                repository.serverPort as kotlinx.coroutines.flow.Flow<Any?>,
                repository.downloadProgress as kotlinx.coroutines.flow.Flow<Any?>,
                repository.installStatus as kotlinx.coroutines.flow.Flow<Any?>,
                repository.incomingRequests as kotlinx.coroutines.flow.Flow<Any?>,
            )
            combine(flows) { array ->
                val localApps = array[0] as List<com.lansync.app.data.model.AppInfo>
                val discoveredDevices = array[1] as List<DeviceInfo>
                val connectedDevices = array[2] as List<DeviceInfo>
                val availableUpdates = array[3] as List<UpdateInfo>
                val syncDiffs = array[4] as List<SyncDiff>
                val isRunning = array[5] as Boolean
                val isScanningApps = array[6] as Boolean
                val serverPort = array[7] as Int
                val downloadProgress = array[8] as AppRepository.DownloadProgress?
                val installStatus = array[9] as AppRepository.InstallStatus?
                val incomingRequests = array[10] as List<IncomingConnectRequest>

                _uiState.value.copy(
                    localApps = localApps,
                    discoveredDevices = discoveredDevices,
                    connectedDevices = connectedDevices,
                    availableUpdates = availableUpdates,
                    syncDiffs = syncDiffs,
                    isRunning = isRunning,
                    isScanningApps = isScanningApps,
                    serverPort = serverPort,
                    currentDownloadProgress = downloadProgress,
                    isDownloading = downloadProgress?.status == AppRepository.DownloadProgress.Status.DOWNLOADING,
                    installStatus = installStatus,
                    incomingRequests = incomingRequests
                )
            }.collect { newState ->
                _uiState.value = newState
            }
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

    fun scanLocalApps() {
        viewModelScope.launch {
            repository.scanLocalApps()
        }
    }

    fun forceStartSync() {
        repository.forceStartSync()
    }

    fun connectDevice(device: DeviceInfo) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnecting = true,
                connectingDeviceName = device.deviceName,
                connectionError = null,
                operationMessage = "正在连接 ${device.deviceName}..."
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
            } finally {
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    connectingDeviceName = null,
                    operationMessage = null
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

    fun checkForUpdates() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.checkForUpdates()
            _uiState.value = _uiState.value.copy(isLoading = false)
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

            updates.forEach { update ->
                Log.d(TAG, "Batch: downloading/installing ${update.remoteApp.packageName}")
                _lastDownloadedUpdateInfo = update
                repository.downloadAndInstallApp(update)
            }
            Log.i(TAG, "startBatchUpdate: completed")
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
            val pkgName = fileName.removeSuffix(".apks").replace("_", ".")
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

            targets.forEach { entry ->
                val updateInfo = UpdateInfo(
                    localApp = null,
                    remoteApp = entry.app,
                    providerDevice = entry.sourceDevice,
                    canUpdate = true
                )
                Log.d(TAG, "Pulling remote app: ${entry.app.packageName} from ${entry.sourceDevice.deviceName}")
                _lastDownloadedUpdateInfo = updateInfo
                repository.downloadAndInstallApp(updateInfo)
            }
            Log.i(TAG, "pullSelectedRemoteApps: completed")
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
