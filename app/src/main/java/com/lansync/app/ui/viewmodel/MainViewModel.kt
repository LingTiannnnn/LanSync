package com.lansync.app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.app.R
import com.lansync.app.data.FileLogger
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
import com.lansync.app.ui.theme.LanSyncTiming
import com.lansync.app.ui.string.installFailureText
import kotlinx.coroutines.delay
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
    val initialScanDismissed: Boolean = false,
    val installStatus: DownloadInstallController.InstallStatus? = null,
    val serverPort: Int = 0,
    val isLoading: Boolean = false,
    val connectionNotice: String? = null,
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

    /** 下载进度独立 flow：tick 频繁，不并入巨石 UiState（避免每次进度更新重发全部状态）。 */
    val downloadProgress: StateFlow<DownloadInstallController.DownloadProgress?> = repository.downloadProgress

    private var _lastDownloadedUpdateInfo: UpdateInfo? = null

    /** 上一次 connectedDevices 的键集；用于检测「任一设备断开」并清空两页勾选。 */
    private var lastConnectedKeys: Set<String> = emptySet()

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
                    ensureServiceStarted()
                } catch (t: Throwable) {
                    FileLogger.e(TAG, "autoStart initial path failed", t)
                    _uiState.value = _uiState.value.copy(needsInitialScan = false)
                }
            }
            return
        }

        ensureServiceStarted()
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
                // 断连即重置勾选：被断开设备的条目已不可见，残留勾选只会留下顶栏开关与批量条的僵尸状态
                val connectedKeys = connectedDevices.map { it.displayKey }.toSet()
                val disconnected = lastConnectedKeys.any { it !in connectedKeys }
                lastConnectedKeys = connectedKeys
                _uiState.value = _uiState.value.copy(
                    localApps = localApps,
                    discoveredDevices = discoveredDevices,
                    connectedDevices = connectedDevices,
                    availableUpdates = availableUpdates,
                    syncDiffs = syncDiffs,
                    remoteAppSelections = if (disconnected) emptySet() else _uiState.value.remoteAppSelections,
                    selectedUpdates = if (disconnected) emptySet() else _uiState.value.selectedUpdates,
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
                repository.installStatus
            ) { isRunning, isScanningApps, serverPort, installStatus ->
                _uiState.value = _uiState.value.copy(
                    isRunning = isRunning,
                    isScanningApps = isScanningApps,
                    serverPort = serverPort,
                    installStatus = installStatus
                )
            }.collect { }
        }
    }

    /** 服务启动失败只记录，不打断 UI 操作（FGS 权限/系统在个别 ROM 上会拒）。 */
    private fun ensureServiceStarted() {
        try {
            ForegroundSyncService.start(getApplication())
        } catch (t: Throwable) {
            FileLogger.e(TAG, "ForegroundSyncService.start failed", t)
        }
    }

    /** 首扫卡住时让用户离开遮罩；扫描在后台继续，缓存骨架照常展示。 */
    fun skipInitialScan() {
        _uiState.value = _uiState.value.copy(initialScanDismissed = true)
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
                connectionNotice = null
            )
            try {
                val success = repository.connectDevice(device)
                if (!success) {
                    _uiState.value = _uiState.value.copy(
                        connectionNotice = str(R.string.error_connect_failed, device.deviceName)
                    )
                }
            } catch (e: Exception) {
                FileLogger.e("MainViewModel", "connectDevice failed for ${device.deviceName}", e)
                _uiState.value = _uiState.value.copy(
                    connectionNotice = str(R.string.error_connect_failed, device.deviceName)
                )
            }
        }
    }

    fun dismissConnectionError() {
        _uiState.value = _uiState.value.copy(connectionNotice = null)
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
                delay(LanSyncTiming.OPERATION_FEEDBACK_MS)
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

    /** 顶栏「全选」：勾选当前设备的全部可更新应用。 */
    fun selectUpdates(packages: Collection<String>) {
        _uiState.value = _uiState.value.copy(selectedUpdates = packages.toSet())
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
            for (update in updates) {
                Log.d(TAG, "Batch: downloading/installing ${update.remoteApp.packageName}")
                _lastDownloadedUpdateInfo = update
                val result = repository.downloadAndInstallApp(update)
                if (result is ApkInstaller.InstallationResult.Cancelled) {
                    // 用户取消：终止剩余批次，保留已选清单供重试
                    Log.i(TAG, "Batch: cancelled by user at ${update.remoteApp.packageName}")
                    return@launch
                }
                if (result is ApkInstaller.InstallationResult.Error) {
                    Log.w(TAG, "Batch: FAILED for ${update.remoteApp.packageName}: ${result.message}")
                    errors.add(
                        str(
                            R.string.batch_error_line,
                            update.remoteApp.appName,
                            installFailureText(getApplication(), result.failure),
                        )
                    )
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

    /** 用户取消当前下载（进度弹窗「取消」按钮）：中断下载并关闭弹窗，不按失败处理。 */
    fun cancelDownload() {
        repository.cancelDownload()
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

    /** 顶栏「全选」：勾选当前可见列表（当前分类 tab 下的全部应用）。 */
    fun selectRemoteApps(packages: Collection<String>) {
        _uiState.value = _uiState.value.copy(remoteAppSelections = packages.toSet())
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
            for (entry in targets) {
                val updateInfo = UpdateInfo(
                    localApp = null,
                    remoteApp = entry.app,
                    providerDevice = entry.sourceDevice,
                    canUpdate = true
                )
                Log.d(TAG, "Pulling remote app: ${entry.app.packageName} from ${entry.sourceDevice.deviceName}")
                _lastDownloadedUpdateInfo = updateInfo
                val result = repository.downloadAndInstallApp(updateInfo)
                if (result is ApkInstaller.InstallationResult.Cancelled) {
                    // 用户取消：终止剩余拉取，保留已选清单供重试
                    Log.i(TAG, "Pull cancelled by user at ${entry.app.packageName}")
                    return@launch
                }
                if (result is ApkInstaller.InstallationResult.Error) {
                    Log.w(TAG, "Pull FAILED for ${entry.app.packageName}: ${result.message}")
                    errors.add(
                        str(
                            R.string.batch_error_line,
                            entry.app.appName,
                            installFailureText(getApplication(), result.failure),
                        )
                    )
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
