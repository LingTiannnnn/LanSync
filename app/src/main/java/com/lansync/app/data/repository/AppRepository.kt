package com.lansync.app.data.repository

import android.content.Context
import android.util.Log
import com.lansync.app.data.FileLogger
import com.lansync.app.data.client.AppListClient
import com.lansync.app.data.connection.ConnectionManager
import com.lansync.app.data.discovery.JmDNSDiscovery
import com.lansync.app.data.installer.ApkInstaller
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.IncomingConnectRequest
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo
import com.lansync.app.data.packer.AppPacker
import com.lansync.app.data.scanner.AppScanner
import com.lansync.app.data.server.KtorServer
import com.lansync.app.data.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AppRepository(context: Context) {

    private val appScanner = AppScanner(context)
    private val appPacker = AppPacker(context)
    private val ktorServer = KtorServer(context)
    private val jmdnsDiscovery = JmDNSDiscovery(context)
    private val appListClient = AppListClient(context)
    private val updateManager = UpdateManager(appListClient)
    private val apkInstaller = ApkInstaller(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _localApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val _rawDiscoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val _enrichedDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val _connectedDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val _availableUpdates = MutableStateFlow<List<UpdateInfo>>(emptyList())
    private val _syncDiffs = MutableStateFlow<List<SyncDiff>>(emptyList())
    private val _serverPort = MutableStateFlow(0)
    private val _isRunning = MutableStateFlow(false)
    private val _isScanningApps = MutableStateFlow(false)
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    private val _installStatus = MutableStateFlow<InstallStatus?>(null)
    private val _incomingRequests = MutableStateFlow<List<IncomingConnectRequest>>(emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val appCacheFile = File(context.filesDir, "local_apps_cache.json")

    val localApps: StateFlow<List<AppInfo>> = _localApps.asStateFlow()
    val discoveredDevices: StateFlow<List<DeviceInfo>> = _enrichedDevices.asStateFlow()
    val connectedDevices: StateFlow<List<DeviceInfo>> = _connectedDevices.asStateFlow()
    val enrichedDevices: StateFlow<List<DeviceInfo>> = _enrichedDevices.asStateFlow()
    val availableUpdates: StateFlow<List<UpdateInfo>> = _availableUpdates.asStateFlow()
    val syncDiffs: StateFlow<List<SyncDiff>> = _syncDiffs.asStateFlow()
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    val isScanningApps: StateFlow<Boolean> = _isScanningApps.asStateFlow()
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()
    val installStatus: StateFlow<InstallStatus?> = _installStatus.asStateFlow()
    val incomingRequests: StateFlow<List<IncomingConnectRequest>> = _incomingRequests.asStateFlow()

    private var connectionManagerJob: Job? = null
    private val heartbeatJobs = ConcurrentHashMap<String, Job>()

    private fun getLocalDisplayKey(): String {
        val port = _serverPort.value
        val ip = try {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
        return "$ip:$port"
    }

    fun handleRemoteDisconnect(remoteDisplayKey: String) {
        scope.launch {
            Log.i(TAG, "Remote disconnect notification from $remoteDisplayKey")
            stopHeartbeat(remoteDisplayKey)

            val connected = _connectedDevices.value.toMutableList()
            val idx = connected.indexOfFirst { it.displayKey == remoteDisplayKey }
            if (idx >= 0) {
                val disconnected = connected.removeAt(idx).copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    appList = emptyList()
                )
                _connectedDevices.value = connected
                addOrUpdateInEnriched(disconnected)
                Log.i(TAG, "Device $remoteDisplayKey marked as DISCONNECTED (remote initiated)")
            }

            val enriched = _enrichedDevices.value.toMutableList()
            val eidx = enriched.indexOfFirst { it.displayKey == remoteDisplayKey }
            if (eidx >= 0 && enriched[eidx].connectionState != ConnectionState.DISCONNECTED) {
                enriched[eidx] = enriched[eidx].copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    appList = emptyList()
                )
                _enrichedDevices.value = enriched
            }
        }
    }

    private fun startHeartbeat(device: DeviceInfo) {
        val key = device.displayKey
        heartbeatJobs[key]?.cancel()
        heartbeatJobs[key] = scope.launch {
            delay(HEARTBEAT_INTERVAL_MS)
            while (isActive) {
                try {
                    runHeartbeatCycle(device)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error for ${device.deviceName}: ${e.message}")
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun runHeartbeatCycle(device: DeviceInfo) {
        val key = device.displayKey
        var currentDevice = device

        for (retry in 0..HEARTBEAT_MAX_RETRIES) {
            try {
                val info = appListClient.fetchDeviceInfo(device.ipAddress, device.port)
                if (info != null) {
                    if (retry > 0) {
                        val recovered = currentDevice.copy(
                            connectionState = ConnectionState.CONNECTED,
                            lastSeenTimeMs = System.currentTimeMillis()
                        )
                        addOrUpdateInConnected(recovered)
                        addOrUpdateInEnriched(recovered)
                        Log.i(TAG, "Heartbeat recovered for ${device.deviceName} after $retry retries")
                        scope.launch { fetchAndEnrichDevice(device) }
                    } else {
                        val refreshed = currentDevice.copy(lastSeenTimeMs = System.currentTimeMillis())
                        addOrUpdateInConnected(refreshed)
                        addOrUpdateInEnriched(refreshed)
                    }
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat attempt ${retry + 1} failed for ${device.deviceName}: ${e.message}")
            }

            if (retry < HEARTBEAT_MAX_RETRIES) {
                val reconnecting = currentDevice.copy(
                    connectionState = ConnectionState.RECONNECTING,
                    connectionError = "正在尝试重新连接... (${retry + 1}/$HEARTBEAT_MAX_RETRIES)"
                )
                addOrUpdateInConnected(reconnecting)
                addOrUpdateInEnriched(reconnecting)
                currentDevice = reconnecting
                delay(HEARTBEAT_RETRY_DELAY_MS)
            }
        }

        val timedOut = currentDevice.copy(
            connectionState = ConnectionState.CONNECTION_TIMEOUT,
            connectionError = "连接超时"
        )
        removeFromConnected(timedOut)
        addOrUpdateInEnriched(timedOut)
        stopHeartbeat(key)
        Log.w(TAG, "Heartbeat timeout for ${device.deviceName} after $HEARTBEAT_MAX_RETRIES retries")
    }

    private fun stopHeartbeat(displayKey: String) {
        heartbeatJobs[displayKey]?.cancel()
        heartbeatJobs.remove(displayKey)
    }

    private fun stopAllHeartbeats() {
        heartbeatJobs.values.forEach { it.cancel() }
        heartbeatJobs.clear()
    }

    init {
        observeRawDevicesAndManageConnections()
        autoCheckUpdatesWhenDataReady()
    }

    private fun observeRawDevicesAndManageConnections() {
        scope.launch {
            var lastRefreshKeys = emptySet<String>()
            jmdnsDiscovery.discoveredDevices.collect { rawDevices ->
                _rawDiscoveredDevices.value = rawDevices

                val currentKeys = rawDevices.map { it.displayKey }.toSet()

                val newConnectedKeys = currentKeys - lastRefreshKeys
                val existingConnected = _connectedDevices.value.filter { d ->
                    d.displayKey in newConnectedKeys && d.connectionState == ConnectionState.CONNECTED
                }
                for (existing in existingConnected) {
                    launch { fetchAndEnrichDevice(existing) }
                }
                lastRefreshKeys = currentKeys

                val enriched = _enrichedDevices.value.toMutableList()
                enriched.removeAll { device ->
                    device.displayKey !in currentKeys &&
                    device.connectionState != ConnectionState.CONNECTED &&
                    device.connectionState != ConnectionState.RECONNECTING &&
                    device.connectionState != ConnectionState.CONNECTION_TIMEOUT
                }
                for (raw in rawDevices) {
                    val existingIdx = enriched.indexOfFirst { it.displayKey == raw.displayKey }
                    if (existingIdx >= 0) {
                        val existing = enriched[existingIdx]
                        if (existing.connectionState == ConnectionState.DISCOVERED ||
                            existing.connectionState == ConnectionState.DISCONNECTED) {
                            enriched[existingIdx] = raw.copy(
                                connectionState = existing.connectionState,
                                lastSeenTimeMs = System.currentTimeMillis()
                            )
                        } else {
                            enriched[existingIdx] = enriched[existingIdx].copy(
                                deviceName = raw.deviceName,
                                ipAddress = raw.ipAddress,
                                port = raw.port,
                                lastSeenTimeMs = System.currentTimeMillis()
                            )
                        }
                    } else {
                        enriched.add(raw.copy(connectionState = ConnectionState.DISCOVERED))
                    }
                }
                _enrichedDevices.value = enriched
            }
        }
    }

    private fun startObservingConnectionManager() {
        connectionManagerJob?.cancel()
        connectionManagerJob = scope.launch {
            ktorServer.connectionManager?.incomingRequests?.collect { requests ->
                _incomingRequests.value = requests
            }
        }
    }

    suspend fun connectDevice(device: DeviceInfo): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                FileLogger.i(TAG, "=== CONNECT START === target=${device.deviceName} (${device.displayKey})")
                Log.i(TAG, "Connecting to ${device.deviceName} (${device.displayKey})...")

                updateDeviceConnectionState(device, ConnectionState.CONNECTING)

                val localPort = _serverPort.value
                val localName = android.os.Build.MODEL

                FileLogger.i(TAG, "Sending POST /connect/request to ${device.ipAddress}:${device.port}, localPort=$localPort localName=$localName")
                Log.d(TAG, "Sending connect request to ${device.ipAddress}:${device.port}")
                val requestId = appListClient.sendConnectRequest(
                    targetIp = device.ipAddress,
                    targetPort = device.port,
                    localDeviceName = localName,
                    localPort = localPort
                )

                if (requestId == null) {
                    FileLogger.e(TAG, "=== CONNECT FAIL === sendConnectRequest returned null, target unresponsive")
                    Log.e(TAG, "Failed to send connect request - no response from target")
                    updateDeviceConnectionState(device, ConnectionState.ERROR, "无法发送连接请求，目标设备无响应")
                    return@withContext false
                }

                FileLogger.i(TAG, "Request sent, requestId=$requestId, starting poll...")
                Log.i(TAG, "Connect request sent, requestId=$requestId, polling for response...")

                val result = appListClient.pollConnectStatus(
                    targetIp = device.ipAddress,
                    targetPort = device.port,
                    requestId = requestId,
                    timeoutMs = ConnectionManager.CONNECT_TIMEOUT_MS
                )

                when (result) {
                    is AppListClient.ConnectResult.Accepted -> {
                        FileLogger.i(TAG, "=== CONNECT SUCCESS === accepted by=${result.responderName} id=$requestId")
                        Log.i(TAG, "Connection accepted by ${result.responderName}, marking as connected...")
                        val existingAppList = _enrichedDevices.value
                                    .find { it.displayKey == device.displayKey }
                                    ?.appList.orEmpty()

                                val connected = device.copy(
                                    connectionState = ConnectionState.CONNECTED,
                                    appList = if (existingAppList.isNotEmpty()) existingAppList else emptyList(),
                                    lastSeenTimeMs = System.currentTimeMillis(),
                                    connectionError = null
                                )
                        addOrUpdateInConnected(connected)
                        addOrUpdateInEnriched(connected)
                        startHeartbeat(connected)
                        Log.i(TAG, "Connected to ${device.deviceName}, fetching app list in background...")

                        scope.launch {
                            fetchAppListWithRetry(connected, device.ipAddress, device.port, isInitiator = true)
                        }
                        true
                    }
                    is AppListClient.ConnectResult.Rejected -> {
                        FileLogger.w(TAG, "=== CONNECT REJECTED === reason=${result.message}")
                        Log.w(TAG, "Connection rejected by target: ${result.message}")
                        updateDeviceConnectionState(device, ConnectionState.ERROR, "对方拒绝连接")
                        false
                    }
                    is AppListClient.ConnectResult.Timeout -> {
                        val alreadyConnected = _connectedDevices.value.any {
                            it.displayKey == device.displayKey && it.connectionState == ConnectionState.CONNECTED
                        }
                        if (alreadyConnected) {
                            FileLogger.i(TAG, "=== CONNECT TIMEOUT but already connected via reverse === device=${device.deviceName}")
                            true
                        } else {
                            FileLogger.e(TAG, "=== CONNECT TIMEOUT === ${result.message}")
                            Log.w(TAG, "Connection timeout: ${result.message}")
                            updateDeviceConnectionState(device, ConnectionState.ERROR, result.message)
                            false
                        }
                    }
                    is AppListClient.ConnectResult.Error -> {
                        FileLogger.e(TAG, "=== CONNECT ERROR === ${result.message}")
                        Log.e(TAG, "Connection error: ${result.message}")
                        updateDeviceConnectionState(device, ConnectionState.ERROR, result.message)
                        false
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "=== CONNECT EXCEPTION === ${e.javaClass.simpleName}: ${e.message}", e)
                Log.e(TAG, "Exception connecting to ${device.deviceName}", e)
                updateDeviceConnectionState(device, ConnectionState.ERROR, e.message ?: "连接异常")
                false
            }
        }
    }

    fun handleIncomingRequest(requestId: String, accepted: Boolean): Boolean {
        FileLogger.i(TAG, "=== HANDLE_INCOMING === requestId=$requestId accepted=$accepted")
        val manager = ktorServer.connectionManager ?: run {
            FileLogger.e(TAG, "HANDLE_INCOMING: connectionManager is NULL!")
            return false
        }
        val pendingReq = manager.getPendingRequest(requestId)
        if (pendingReq == null) {
            FileLogger.e(TAG, "HANDLE_INCOMING: pendingReq not found for $requestId")
        } else {
            FileLogger.i(TAG, "HANDLE_INCOMING: from=${pendingReq.requesterName} (${pendingReq.requesterIp}:${pendingReq.requesterPort})")
        }

        val response = manager.respondToRequest(requestId, accepted)
        FileLogger.i(TAG, "HANDLE_INCOMING: respondToRequest returned ${if (response != null) "OK" else "NULL"}")

        if (response != null && accepted && pendingReq != null) {
            FileLogger.i(TAG, "HANDLE_INCOMING: marking CONNECTED for ${pendingReq.requesterName}")
            val connectedDevice = DeviceInfo(
                ipAddress = pendingReq.requesterIp,
                deviceName = pendingReq.requesterName,
                port = pendingReq.requesterPort,
                appList = emptyList(),
                connectionState = ConnectionState.CONNECTED,
                lastSeenTimeMs = System.currentTimeMillis()
            )
            addOrUpdateInConnected(connectedDevice)
            addOrUpdateInEnriched(connectedDevice)
            startHeartbeat(connectedDevice)

            scope.launch {
                try {
                    fetchAppListWithRetry(connectedDevice, pendingReq.requesterIp, pendingReq.requesterPort, isInitiator = false)
                } finally {
                    manager.removeRequest(requestId)
                }
            }
        } else {
            manager.removeRequest(requestId)
        }

        return response != null
    }

    fun dismissIncomingRequest(requestId: String) {
        ktorServer.connectionManager?.removeRequest(requestId)
    }

    fun disconnectDevice(device: DeviceInfo) {
        scope.launch {
            stopHeartbeat(device.displayKey)

            try {
                val localKey = getLocalDisplayKey()
                appListClient.sendDisconnectNotification(device.ipAddress, device.port, localKey)
                Log.i(TAG, "Sent disconnect notification to ${device.deviceName}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send disconnect notification to ${device.deviceName}: ${e.message}")
            }

            val disconnected = device.copy(
                connectionState = ConnectionState.DISCONNECTED,
                appList = emptyList()
            )
            removeFromConnected(disconnected)

            val enriched = _enrichedDevices.value.toMutableList()
            val idx = enriched.indexOfFirst { it.displayKey == disconnected.displayKey }
            if (idx >= 0) {
                enriched[idx] = disconnected
                _enrichedDevices.value = enriched
            }
            Log.i(TAG, "Disconnected from ${device.deviceName}")
        }
    }

    fun forceStartSync() {
        if (_isRunning.value) return
        scope.launch {
            withContext(Dispatchers.IO) {
                if (_isRunning.value) return@withContext

                ktorServer.initConnectionManager(android.os.Build.MODEL)
                ktorServer.setAppListProvider { _localApps.value }
                ktorServer.setPacker { appPacker.packApp(it) }
                ktorServer.setDisconnectHandler { displayKey -> handleRemoteDisconnect(displayKey) }
                ktorServer.setRefreshAppListHandler { remoteDisplayKey -> handleRemoteRefreshAppList(remoteDisplayKey) }

                val port = ktorServer.start()
                _serverPort.value = port
                Log.i(TAG, "Server started on port $port (forced)")

                startObservingConnectionManager()

                jmdnsDiscovery.startDiscovery(port)
                _isRunning.value = true
                Log.i(TAG, "Discovery started on port $port (forced)")
            }
        }
    }

    private fun handleRemoteRefreshAppList(remoteDisplayKey: String) {
        scope.launch {
            val device = _connectedDevices.value.find { it.displayKey == remoteDisplayKey }
            if (device != null) {
                Log.i(TAG, "Remote refresh notification from $remoteDisplayKey, re-fetching app list")
                fetchAndEnrichDevice(device)
            }
        }
    }

    private fun updateDeviceConnectionState(device: DeviceInfo, state: ConnectionState, error: String? = null) {
        val updated = device.copy(connectionState = state, connectionError = error)

        val enriched = _enrichedDevices.value.toMutableList()
        val idx = enriched.indexOfFirst { it.displayKey == device.displayKey }
        if (idx >= 0) {
            enriched[idx] = updated
            _enrichedDevices.value = enriched
        }

        val connected = _connectedDevices.value.toMutableList()
        val cIdx = connected.indexOfFirst { it.displayKey == device.displayKey }
        if (cIdx >= 0) {
            if (state == ConnectionState.CONNECTED) {
                connected[cIdx] = updated
            } else {
                connected.removeAt(cIdx)
            }
            _connectedDevices.value = connected
        }
    }

    private fun addOrUpdateInConnected(device: DeviceInfo) {
        val list = _connectedDevices.value.toMutableList()
        val idx = list.indexOfFirst { it.displayKey == device.displayKey }
        if (idx >= 0) {
            list[idx] = device
        } else {
            list.add(device)
        }
        _connectedDevices.value = list
    }

    private fun removeFromConnected(device: DeviceInfo) {
        _connectedDevices.value = _connectedDevices.value.filter { it.displayKey != device.displayKey }
    }

    private fun addOrUpdateInEnriched(device: DeviceInfo) {
        val list = _enrichedDevices.value.toMutableList()
        val idx = list.indexOfFirst { it.displayKey == device.displayKey }
        if (idx >= 0) {
            list[idx] = device
        } else {
            list.add(device)
        }
        _enrichedDevices.value = list
    }

    private suspend fun fetchAndEnrichDevice(rawDevice: DeviceInfo) {
        try {
            val remoteApps = appListClient.fetchAppList(rawDevice.ipAddress, rawDevice.port)
            if (remoteApps != null) {
                val enriched = rawDevice.copy(appList = remoteApps)
                addOrUpdateInEnriched(enriched)
                addOrUpdateInConnected(enriched)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing app list from ${rawDevice.deviceName}: ${e.message}")
        }
    }

    private suspend fun fetchAppListWithRetry(
        baseDevice: DeviceInfo,
        ipAddress: String,
        port: Int,
        isInitiator: Boolean
    ) {
        val tag = if (isInitiator) "CONNECT_INITIATOR" else "HANDLE_INCOMING"
        val maxRetries = 5
        val retryDelayMs = 3000L

        for (attempt in 1..maxRetries) {
            try {
                FileLogger.i(TAG, "$tag: fetchAppList attempt #$attempt/$maxRetries -> $ipAddress:$port")
                val remoteApps = appListClient.fetchAppList(ipAddress, port)

                if (remoteApps != null && remoteApps.isNotEmpty()) {
                    val enriched = baseDevice.copy(appList = remoteApps)
                    addOrUpdateInConnected(enriched)
                    addOrUpdateInEnriched(enriched)
                    FileLogger.i(TAG, "$tag: fetchAppList SUCCESS on attempt #$attempt: ${remoteApps.size} apps")
                    Log.i(TAG, "Fetched ${remoteApps.size} apps from ${baseDevice.deviceName}")
                    return
                }

                val localSize = _localApps.value.size
                FileLogger.w(TAG, "$tag: fetchAppList attempt #$attempt returned empty/null (localApps=$localSize)")

                if (attempt < maxRetries) {
                    FileLogger.d(TAG, "$tag: waiting ${retryDelayMs}ms before retry...")
                    kotlinx.coroutines.delay(retryDelayMs)
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "$tag: fetchAppList attempt #$attempt exception", e)
                Log.e(TAG, "App list fetch error (attempt $attempt): ${e.message}", e)
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(retryDelayMs)
                }
            }
        }

        FileLogger.e(TAG, "$tag: fetchAppList FAILED after $maxRetries attempts, device=${baseDevice.deviceName} stays connected with empty list")
    }

    private fun autoCheckUpdatesWhenDataReady() {
        scope.launch {
            combine(_localApps, _connectedDevices) { localApps, devices ->
                localApps to devices
            }.collect { (localApps, devices) ->
                if (localApps.isNotEmpty() && devices.any { it.appList.isNotEmpty() }) {
                    recalculateUpdates(localApps, devices)
                    calculateSyncDiffs(localApps, devices)
                }
            }
        }
    }

    private suspend fun recalculateUpdates(
        localApps: List<AppInfo>,
        devices: List<DeviceInfo>
    ): List<UpdateInfo> {
        return withContext(Dispatchers.IO) {
            val devicesWithApps = devices.filter { it.appList.isNotEmpty() }
            if (localApps.isEmpty() || devicesWithApps.isEmpty()) {
                _availableUpdates.value = emptyList()
                return@withContext emptyList()
            }

            val allUpdates = updateManager.findUpdates(localApps, devicesWithApps)
            val deduplicated = updateManager.deduplicateUpdates(allUpdates)
            _availableUpdates.value = deduplicated
            Log.i(TAG, "Recalculated updates: ${deduplicated.size} available")
            deduplicated
        }
    }

    private fun calculateSyncDiffs(localApps: List<AppInfo>, devices: List<DeviceInfo>) {
        val diffs = mutableListOf<SyncDiff>()
        val localMap = localApps.associateBy { it.packageName }

        for (device in devices.filter { it.appList.isNotEmpty() }) {
            for (remoteApp in device.appList) {
                if (remoteApp.isSystemApp) continue

                val localApp = localMap[remoteApp.packageName] ?: continue

                if (localApp.isSystemApp) continue

                if (remoteApp.versionCode > localApp.versionCode) {
                    diffs.add(SyncDiff(
                        appInfo = remoteApp,
                        localVersion = localApp.versionName,
                        remoteVersion = remoteApp.versionName,
                        sourceDevice = device,
                        diffType = SyncDiff.DiffType.NEWER_ON_REMOTE
                    ))
                }
            }
        }

        _syncDiffs.value = diffs.sortedByDescending { it.appInfo.appName.lowercase() }
    }

    suspend fun scanLocalApps() {
        withContext(Dispatchers.IO) {
            _isScanningApps.value = true
            try {
                val cached = loadLocalAppsFromFile()
                if (cached.isNotEmpty()) {
                    _localApps.value = cached
                    Log.i(TAG, "Loaded ${cached.size} apps from cache")
                }

                val apps = appScanner.scanInstalledApps()
                _localApps.value = apps
                saveLocalAppsToFile(apps)
                Log.i(TAG, "Scanned ${apps.size} local apps, saved to cache")

                notifyConnectedDevicesToRefresh()
            } catch (e: Exception) {
                Log.e(TAG, "scanLocalApps failed", e)
            } finally {
                _isScanningApps.value = false
            }
        }
    }

    private suspend fun notifyConnectedDevicesToRefresh() {
        val localKey = getLocalDisplayKey()
        val connected = _connectedDevices.value
        for (device in connected) {
            try {
                appListClient.sendRefreshAppListNotification(device.ipAddress, device.port, localKey)
                Log.d(TAG, "Sent refresh notification to ${device.deviceName}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send refresh notification to ${device.deviceName}: ${e.message}")
            }
        }
    }

    private fun saveLocalAppsToFile(apps: List<AppInfo>) {
        try {
            val jsonStr = json.encodeToString(apps)
            appCacheFile.writeText(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save local apps cache", e)
        }
    }

    private fun loadLocalAppsFromFile(): List<AppInfo> {
        return try {
            if (appCacheFile.exists()) {
                val jsonStr = appCacheFile.readText()
                json.decodeFromString<List<AppInfo>>(jsonStr)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load local apps cache", e)
            appCacheFile.delete()
            emptyList()
        }
    }

    fun hasLocalAppCache(): Boolean {
        return appCacheFile.exists() && appCacheFile.length() > 0
    }

    fun refreshDeviceAppLists() {
        _rawDiscoveredDevices.value = emptyList()
        val serverPort = _serverPort.value
        if (serverPort > 0 && _isRunning.value) {
            jmdnsDiscovery.stopDiscovery()
            scope.launch { jmdnsDiscovery.startDiscovery(serverPort) }
        }
        val connected = _connectedDevices.value
        for (device in connected) {
            scope.launch { fetchAndEnrichDevice(device) }
        }
    }

    fun refreshConnectedDevice(device: DeviceInfo) {
        scope.launch { fetchAndEnrichDevice(device) }
    }

    suspend fun start() {
        withContext(Dispatchers.IO) {
            if (_isRunning.value) return@withContext

            ktorServer.initConnectionManager(android.os.Build.MODEL)
            ktorServer.setAppListProvider { _localApps.value }
            ktorServer.setPacker { appPacker.packApp(it) }
            ktorServer.setDisconnectHandler { displayKey -> handleRemoteDisconnect(displayKey) }
            ktorServer.setRefreshAppListHandler { remoteDisplayKey -> handleRemoteRefreshAppList(remoteDisplayKey) }

            val port = ktorServer.start()
            _serverPort.value = port
            Log.i(TAG, "Server started on port $port")

            startObservingConnectionManager()

            jmdnsDiscovery.startDiscovery(port)
            _isRunning.value = true
            Log.i(TAG, "Discovery started with server port $port")
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            try {
                connectionManagerJob?.cancel()
                connectionManagerJob = null
                stopAllHeartbeats()
                try {
                    ktorServer.connectionManager?.clearAll()
                } catch (e: Exception) {
                    Log.w(TAG, "Error clearing connection manager", e)
                }
                try {
                    jmdnsDiscovery.stopDiscovery()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping discovery", e)
                }
                try {
                    ktorServer.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping server", e)
                }
            } finally {
                _serverPort.value = 0
                _isRunning.value = false
                _rawDiscoveredDevices.value = emptyList()
                _enrichedDevices.value = emptyList()
                _connectedDevices.value = emptyList()
                _availableUpdates.value = emptyList()
                _syncDiffs.value = emptyList()
                _incomingRequests.value = emptyList()
                Log.i(TAG, "Server and discovery stopped")
            }
        }
    }

    suspend fun checkForUpdates(): List<UpdateInfo> {
        return recalculateUpdates(_localApps.value, _connectedDevices.value)
    }

    suspend fun fetchDeviceAppList(device: DeviceInfo): DeviceInfo? {
        return withContext(Dispatchers.IO) {
            updateManager.refreshDeviceAppList(device)?.also { enriched ->
                addOrUpdateInEnriched(enriched)
            }
        }
    }

    suspend fun downloadApp(updateInfo: UpdateInfo): AppListClient.DownloadResult {
        return withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "=== downloadApp START === pkg=${updateInfo.remoteApp.packageName} v${updateInfo.remoteApp.versionCode} from=${updateInfo.providerDevice.deviceName} (${updateInfo.providerDevice.ipAddress}:${updateInfo.providerDevice.port}) isExtractable=${updateInfo.remoteApp.isExtractable}")

            _downloadProgress.value = DownloadProgress(
                packageName = updateInfo.remoteApp.packageName,
                progress = 0,
                status = DownloadProgress.Status.DOWNLOADING
            )

            val result = appListClient.downloadApksFile(
                ipAddress = updateInfo.providerDevice.ipAddress,
                port = updateInfo.providerDevice.port,
                packageName = updateInfo.remoteApp.packageName,
                versionCode = updateInfo.remoteApp.versionCode,
                expectedMd5 = updateInfo.remoteApp.md5,
                onProgress = { percent ->
                    _downloadProgress.value = DownloadProgress(
                        packageName = updateInfo.remoteApp.packageName,
                        progress = percent.coerceAtMost(99),
                        status = DownloadProgress.Status.DOWNLOADING
                    )
                }
            )

            _downloadProgress.value = when (result) {
                is AppListClient.DownloadResult.Success -> {
                    FileLogger.i(TAG, "downloadApp SUCCESS: ${updateInfo.remoteApp.packageName} -> ${result.file.length()} bytes")
                    DownloadProgress(
                        packageName = updateInfo.remoteApp.packageName,
                        progress = 100,
                        status = DownloadProgress.Status.COMPLETED
                    )
                }
                is AppListClient.DownloadResult.Error -> {
                    FileLogger.e(TAG, "downloadApp FAILED: ${updateInfo.remoteApp.packageName} -> ${result.message}")
                    DownloadProgress(
                        packageName = updateInfo.remoteApp.packageName,
                        progress = 0,
                        status = DownloadProgress.Status.FAILED
                    )
                }
            }
            FileLogger.i(TAG, "=== downloadApp END === result=${result::class.simpleName}")
            result
        }
    }

    fun installApp(updateInfo: UpdateInfo): ApkInstaller.InstallationResult {
        FileLogger.i(TAG, "=== installApp START === pkg=${updateInfo.remoteApp.packageName} v${updateInfo.remoteApp.versionCode}")

        val file = appListClient.getDownloadedFile(
            updateInfo.remoteApp.packageName,
            updateInfo.remoteApp.versionCode
        )
        _installStatus.value = InstallStatus.Installing(updateInfo.remoteApp.packageName)

        return if (file != null) {
            FileLogger.d(TAG, "installApp: file found, calling installApks(${file.absolutePath}, size=${file.length()})")
            apkInstaller.installApks(file).also { result ->
                _installStatus.value = when (result) {
                    is ApkInstaller.InstallationResult.Success -> {
                        FileLogger.i(TAG, "installApp SUCCESS: ${updateInfo.remoteApp.packageName}")
                        InstallStatus.Success(updateInfo.remoteApp.packageName)
                    }
                    is ApkInstaller.InstallationResult.Error -> {
                        FileLogger.e(TAG, "installApp FAILED: ${updateInfo.remoteApp.packageName} -> ${result.message}")
                        InstallStatus.Failed(updateInfo.remoteApp.packageName, result.message)
                    }
                }
            }
        } else {
            FileLogger.e(TAG, "installApp: Downloaded file NOT FOUND for ${updateInfo.remoteApp.packageName} v${updateInfo.remoteApp.versionCode}. Was download successful?")
            _installStatus.value = InstallStatus.Failed(updateInfo.remoteApp.packageName, "Downloaded file not found")
            ApkInstaller.InstallationResult.Error("Downloaded file not found")
        }
    }

    fun installDownloadedFile(file: File): ApkInstaller.InstallationResult {
        _installStatus.value = InstallStatus.Installing(file.name)
        return apkInstaller.installApks(file).also { result ->
            _installStatus.value = when (result) {
                is ApkInstaller.InstallationResult.Success -> InstallStatus.Success(file.name)
                is ApkInstaller.InstallationResult.Error -> InstallStatus.Failed(file.name, result.message)
            }
        }
    }

    fun getDownloadedFile(packageName: String, versionCode: Long): File? =
        appListClient.getDownloadedFile(packageName, versionCode)

    fun canInstall(file: File): Boolean = apkInstaller.canHandleInstall(file)

    fun cleanupOldDownloads() = appListClient.cleanupOldDownloads()

    fun clearDownloads() = appListClient.clearDownloads()

    fun cleanupOldPacks() = appPacker.cleanupOldPacks()

    fun clearCache() {
        appPacker.clearCache()
        appListClient.clearDownloads()
    }

    fun clearDownloadProgress() { _downloadProgress.value = null }

    fun getDownloadedFiles(): List<AppListClient.DownloadedFileInfo> {
        return appListClient.getDownloadedFiles()
    }

    fun deleteDownloadedFiles(fileNames: List<String>): Int {
        return appListClient.deleteDownloadedFiles(fileNames)
    }

    fun installDownloadedFile(fileName: String, pkgName: String): ApkInstaller.InstallationResult {
        _installStatus.value = InstallStatus.Installing(pkgName)
        val file = appListClient.getDownloadedFileFromName(fileName)
        return if (file != null) {
            apkInstaller.installApks(file).also { result ->
                _installStatus.value = when (result) {
                    is ApkInstaller.InstallationResult.Success -> InstallStatus.Success(pkgName)
                    is ApkInstaller.InstallationResult.Error -> InstallStatus.Failed(pkgName, result.message)
                }
            }
        } else {
            _installStatus.value = InstallStatus.Failed(pkgName, "文件不存在")
            ApkInstaller.InstallationResult.Error("文件不存在: $fileName")
        }
    }

    fun clearInstallStatus() { _installStatus.value = null }

    data class DownloadProgress(
        val packageName: String,
        val progress: Int,
        val status: Status
    ) {
        enum class Status { DOWNLOADING, VERIFYING, COMPLETED, FAILED }
    }

    sealed class InstallStatus {
        data class Installing(val packageName: String) : InstallStatus()
        data class Success(val packageName: String) : InstallStatus()
        data class Failed(val packageName: String, val message: String) : InstallStatus()
    }

    companion object {
        private const val TAG = "AppRepository"
        const val HEARTBEAT_INTERVAL_MS = 60_000L
        const val HEARTBEAT_RETRY_DELAY_MS = 5_000L
        const val HEARTBEAT_MAX_RETRIES = 3

        @Volatile
        private var instance: AppRepository? = null

        fun getInstance(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
