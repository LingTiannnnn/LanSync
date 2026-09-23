package com.lansync.app.data.repository

import com.lansync.app.data.FileLogger
import com.lansync.app.data.NetworkUtils
import com.lansync.app.data.cache.IconCache
import com.lansync.app.data.connection.ConnectionCoordinator
import com.lansync.app.data.connection.ConnectionEvent
import com.lansync.app.data.discovery.DeviceDiscovery
import com.lansync.app.data.installer.ApkInstaller
import com.lansync.app.data.localapps.LocalAppRepository
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.UpdateInfo
import com.lansync.app.data.server.LanSyncServer
import com.lansync.app.data.sync.UpdateCoordinator
import com.lansync.app.data.transfer.AppPacker
import com.lansync.app.data.transfer.DownloadInstallController
import com.lansync.app.data.transfer.LanSyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **组合门面**（ARCH §2/§9，替代旧 1181 行上帝类 `AppRepository`）——硬性约束 ≤300 行。
 *
 * 只做「组合 + 委托 + 生命周期编排」，不含业务逻辑：连接编排委托 [ConnectionCoordinator]、
 * 更新推荐委托 [UpdateCoordinator]、下载/安装委托 [DownloadInstallController]、本机应用委托 [LocalAppRepository]、
 * 打包委托 [AppPacker]、传输委托 [LanSyncClient]、发现委托 [DeviceDiscovery]、服务委托 [LanSyncServer]、图标委托 [IconCache]。
 *
 * 生命周期（ARCH §8）：[start]/[stop] 由 `ForegroundSyncService` 驱动（脱离 Activity）；本门面不持有 Android 组件。
 * 接线：[start] 内把 `discovery.discoveredDevices` → `ConnectionCoordinator`（`RawDevicesUpdated`）。
 * 图标预加载下沉 data 层（[IconCache]），消除旧 L2 分层倒置（data 不再 import ui）。
 */
class LanSyncRepository(
    private val server: LanSyncServer,
    private val discovery: DeviceDiscovery,
    private val coordinator: ConnectionCoordinator,
    private val localAppRepository: LocalAppRepository,
    private val updateCoordinator: UpdateCoordinator,
    private val downloadController: DownloadInstallController,
    private val client: LanSyncClient,
    private val packer: AppPacker,
    private val iconCache: IconCache,
    private val scope: CoroutineScope
) {

    private val _serverPort = MutableStateFlow(0)
    private val _isRunning = MutableStateFlow(false)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // ---- 直接转发各协作者的 StateFlow（零业务逻辑）----
    val localApps get() = localAppRepository.localApps
    val isScanningApps get() = localAppRepository.isScanning
    val discoveredDevices get() = coordinator.enrichedDevices
    val connectedDevices get() = coordinator.connectedDevices
    val incomingRequests get() = coordinator.incomingRequests
    val availableUpdates get() = updateCoordinator.availableUpdates
    val syncDiffs get() = updateCoordinator.syncDiffs
    val downloadProgress get() = downloadController.downloadProgress
    val installStatus get() = downloadController.installStatus

    private var discoveryJob: Job? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        if (_isRunning.value) return@withContext
        packer.clearCache()
        val port = server.start(0)
        _serverPort.value = port
        coordinator.start()
        updateCoordinator.start()
        discovery.startDiscovery(port)
        discoveryJob = scope.launch {
            discovery.discoveredDevices.collect { raw ->
                coordinator.submit(ConnectionEvent.RawDevicesUpdated(raw))
            }
        }
        _isRunning.value = true
        FileLogger.i(TAG, "started: server port=$port")
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            discoveryJob?.cancel(); discoveryJob = null
            updateCoordinator.stop()
            coordinator.stop()
            discovery.stopDiscovery()
            server.stop()
        } catch (e: Exception) {
            FileLogger.w(TAG, "stop error: ${e.message}")
        } finally {
            _serverPort.value = 0
            _isRunning.value = false
            FileLogger.i(TAG, "stopped")
        }
    }

    // ---- 本机应用（LocalAppRepository）----
    fun hasLocalAppCache(): Boolean = localAppRepository.hasCache()

    /** 启动骨架：用缓存填充 localApps，不触发扫描。返回加载条数（0 = 无缓存或损坏）。 */
    fun loadLocalAppCache(): Int = localAppRepository.loadCacheSkeleton()

    /** 扫描刷新 + 图标预加载/清理（data 层，消除 L2）+ 通知已连接设备刷新。 */
    suspend fun scanLocalApps() {
        val result = localAppRepository.scanAndRefresh()
        if (result.addedPackages.isNotEmpty()) {
            // 首次安装可能一次新增数百包，全量预加载易拖垮启动；列表可见时再按需 get()
            iconCache.preload(result.addedPackages.take(MAX_ICON_PRELOAD).toList())
        }
        result.removedPackages.forEach { iconCache.remove(it) }
        notifyConnectedDevicesToRefresh()
    }

    private suspend fun notifyConnectedDevicesToRefresh() {
        val localKey = localDisplayKey()
        for (d in connectedDevices.value) {
            runCatching { client.sendRefreshAppListNotification(d.ipAddress, d.port, localKey) }
        }
    }

    // ---- 连接（ConnectionCoordinator）----
    suspend fun connectDevice(device: DeviceInfo): Boolean = coordinator.connect(device)
    fun disconnectDevice(device: DeviceInfo) = coordinator.disconnect(device)
    fun handleIncomingRequest(requestId: String, accepted: Boolean) = coordinator.handleIncoming(requestId, accepted)
    fun dismissIncomingRequest(requestId: String) = coordinator.dismissIncoming(requestId)

    fun refreshDeviceAppLists() {
        connectedDevices.value.forEach { refreshConnectedDevice(it) }
    }

    fun refreshConnectedDevice(device: DeviceInfo) {
        scope.launch {
            val apps = client.fetchAppList(device.ipAddress, device.port)
            if (apps != null) coordinator.submit(ConnectionEvent.AppListFetched(device.displayKey, apps))
        }
    }

    // ---- 下载/安装（DownloadInstallController）----
    suspend fun downloadApp(updateInfo: UpdateInfo) = downloadController.downloadApp(updateInfo)
    suspend fun downloadAndInstallApp(updateInfo: UpdateInfo): ApkInstaller.InstallationResult =
        downloadController.downloadAndInstallApp(updateInfo)
    fun installApp(updateInfo: UpdateInfo) = downloadController.installApp(updateInfo)
    fun installDownloadedFile(fileName: String, pkgName: String) =
        downloadController.installDownloadedFile(fileName, pkgName)
    fun getDownloadedFiles() = downloadController.getDownloadedFiles()
    fun deleteDownloadedFiles(fileNames: List<String>) = downloadController.deleteDownloadedFiles(fileNames)
    fun clearDownloadProgress() = downloadController.clearDownloadProgress()
    fun clearInstallStatus() = downloadController.clearInstallStatus()

    private fun localDisplayKey(): String {
        val ip = NetworkUtils.getLocalIpAddress().ifEmpty { "0.0.0.0" }
        return "$ip:${_serverPort.value}"
    }

    private companion object {
        const val TAG = "LanSyncRepository"
        const val MAX_ICON_PRELOAD = 32
    }
}
