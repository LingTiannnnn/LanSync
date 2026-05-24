package com.lansync.app.data.repository

import android.content.Context
import com.lansync.app.data.FileLogger
import com.lansync.app.data.NetworkUtils
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
    private val syncJobs = ConcurrentHashMap<String, Job>()
    private val heartbeatFailCounts = ConcurrentHashMap<String, Int>()
    private val connectingDevices = ConcurrentHashMap<String, Boolean>()
    private var lastUpdateRecalculationMs = 0L

    private fun getLocalDisplayKey(): String {
        val port = _serverPort.value
        val ip = NetworkUtils.getLocalIpAddress().ifEmpty { "0.0.0.0" }
        return "$ip:$port"
    }

    fun handleRemoteDisconnect(remoteDisplayKey: String) {
        scope.launch {
            FileLogger.i(TAG, "Remote disconnect notification from $remoteDisplayKey")
            handleDisconnectByKey { device ->
                device.displayKey == remoteDisplayKey || device.identityKey == remoteDisplayKey
            }
        }
    }

    private suspend fun handleDisconnectByKey(matchFn: (DeviceInfo) -> Boolean) {
        val connected = _connectedDevices.value.toMutableList()
        val matchingConnected = connected.filter(matchFn)
        if (matchingConnected.isEmpty()) {
            FileLogger.d(TAG, "handleDisconnect: no matching connected device found")
            return
        }

        for (device in matchingConnected) {
            stopHeartbeat(device.displayKey)
            stopSyncJob(device.displayKey)
            heartbeatFailCounts.remove(device.displayKey)

            connected.removeAll { it.displayKey == device.displayKey }
            FileLogger.i(TAG, "Device ${device.deviceName} (${device.displayKey}) marked as DISCONNECTED (remote initiated), appList preserved (${device.appList.size} apps)")
        }
        _connectedDevices.value = connected

        val enriched = _enrichedDevices.value.toMutableList()
        for (device in matchingConnected) {
            val eidx = enriched.indexOfFirst { it.displayKey == device.displayKey }
            if (eidx >= 0) {
                enriched[eidx] = enriched[eidx].copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    appList = enriched[eidx].appList
                )
            }
        }
        _enrichedDevices.value = enriched
    }

    private fun startHeartbeat(device: DeviceInfo) {
        val key = device.displayKey
        heartbeatJobs[key]?.cancel()
        heartbeatFailCounts.remove(key)

        heartbeatJobs[key] = scope.launch {
            delay(HEARTBEAT_PING_INTERVAL_MS)
            while (isActive) {
                try {
                    runHeartbeatPing(device)
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Heartbeat ping error for ${device.deviceName}: ${e.message}")
                }
                delay(HEARTBEAT_PING_INTERVAL_MS)
            }
        }

        startSyncSchedule(device)
    }

    private fun startSyncSchedule(device: DeviceInfo) {
        val key = device.displayKey
        syncJobs[key]?.cancel()
        syncJobs[key] = scope.launch {
            delay(HEARTBEAT_SYNC_INTERVAL_MS)
            while (isActive) {
                try {
                    val connected = _connectedDevices.value.find { it.displayKey == key }
                    if (connected != null && connected.connectionState == ConnectionState.CONNECTED) {
                        FileLogger.d(TAG, "Scheduled sync for ${device.deviceName}")
                        fetchAndEnrichDevice(connected)
                    }
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Sync error for ${device.deviceName}: ${e.message}")
                }
                delay(HEARTBEAT_SYNC_INTERVAL_MS)
            }
        }
    }

    private fun stopSyncJob(displayKey: String) {
        syncJobs[displayKey]?.cancel()
        syncJobs.remove(displayKey)
    }

    private suspend fun runHeartbeatPing(device: DeviceInfo) {
        val key = device.displayKey

        val isAlive = try {
            appListClient.pingDevice(device.ipAddress, device.port)
        } catch (e: Exception) {
            FileLogger.d(TAG, "Ping failed for ${device.deviceName}: ${e.message}")
            false
        }

        if (isAlive) {
            heartbeatFailCounts.remove(key)

            val current = _connectedDevices.value.find { it.displayKey == key }
            if (current != null) {
                val shouldUpdateState = current.connectionState != ConnectionState.CONNECTED
                val refreshed = current.copy(
                    connectionState = ConnectionState.CONNECTED,
                    lastSeenTimeMs = System.currentTimeMillis(),
                    connectionError = null
                )
                addOrUpdateInConnected(refreshed)
                addOrUpdateInEnriched(refreshed)
                if (shouldUpdateState) {
                    FileLogger.i(TAG, "Ping recovered for ${device.deviceName}, state restored to CONNECTED")
                }
            }
            return
        }

        val failCount = (heartbeatFailCounts.getOrDefault(key, 0) + 1)
        heartbeatFailCounts[key] = failCount

        val current = _connectedDevices.value.find { it.displayKey == key } ?: return

        when {
            failCount <= HEARTBEAT_PING_TOLERANCE -> {
                val unstable = current.copy(
                    connectionState = ConnectionState.RECONNECTING,
                    connectionError = "连接不稳定... (${failCount}/${HEARTBEAT_PING_MAX_FAILURES})",
                    lastSeenTimeMs = System.currentTimeMillis()
                )
                addOrUpdateInConnected(unstable)
                addOrUpdateInEnriched(unstable)
                FileLogger.d(TAG, "Ping unstable for ${device.deviceName}: $failCount/${HEARTBEAT_PING_MAX_FAILURES}")
            }
            failCount < HEARTBEAT_PING_MAX_FAILURES -> {
                val reconnecting = current.copy(
                    connectionState = ConnectionState.RECONNECTING,
                    connectionError = "正在尝试重新连接... (${failCount}/${HEARTBEAT_PING_MAX_FAILURES})",
                    lastSeenTimeMs = System.currentTimeMillis()
                )
                addOrUpdateInConnected(reconnecting)
                addOrUpdateInEnriched(reconnecting)
                FileLogger.i(TAG, "Ping reconnecting for ${device.deviceName}: $failCount/${HEARTBEAT_PING_MAX_FAILURES}")
                scope.launch { tryFastReconnect(current) }
            }
            else -> {
                val timedOut = current.copy(
                    connectionState = ConnectionState.CONNECTION_TIMEOUT,
                    connectionError = "连接超时，设备已离线"
                )
                removeFromConnected(timedOut)
                addOrUpdateInEnriched(timedOut)
                stopHeartbeat(key)
                stopSyncJob(key)
                heartbeatFailCounts.remove(key)
                FileLogger.w(TAG, "Heartbeat timeout for ${device.deviceName} after $failCount consecutive failures")
            }
        }
    }

    private suspend fun tryFastReconnect(device: DeviceInfo) {
        try {
            val info = appListClient.fetchDeviceInfo(device.ipAddress, device.port)
            if (info != null) {
                heartbeatFailCounts.remove(device.displayKey)
                val recovered = device.copy(
                    connectionState = ConnectionState.CONNECTED,
                    lastSeenTimeMs = System.currentTimeMillis(),
                    connectionError = null
                )
                addOrUpdateInConnected(recovered)
                addOrUpdateInEnriched(recovered)
                FileLogger.i(TAG, "Fast reconnect successful for ${device.deviceName}")
                scope.launch { fetchAndEnrichDevice(device) }
            }
        } catch (e: Exception) {
            FileLogger.d(TAG, "Fast reconnect failed for ${device.deviceName}: ${e.message}")
        }
    }

    private fun stopHeartbeat(displayKey: String) {
        heartbeatJobs[displayKey]?.cancel()
        heartbeatJobs.remove(displayKey)
    }

    private fun stopAllHeartbeats() {
        heartbeatJobs.values.forEach { it.cancel() }
        heartbeatJobs.clear()
        syncJobs.values.forEach { it.cancel() }
        syncJobs.clear()
        heartbeatFailCounts.clear()
    }

    init {
        observeRawDevicesAndManageConnections()
        autoCheckUpdatesWhenDataReady()
    }

    private fun observeRawDevicesAndManageConnections() {
        scope.launch {
            jmdnsDiscovery.discoveredDevices.collect { rawDevices ->
                _rawDiscoveredDevices.value = rawDevices

                val currentKeys = rawDevices.map { it.displayKey }.toSet()

                val enriched = _enrichedDevices.value.toMutableList()

                for (raw in rawDevices) {
                    val existingByIdentity = enriched.indexOfFirst {
                        it.identityKey.isNotEmpty() && it.identityKey == raw.identityKey
                    }
                    val existingByDisplayKey = enriched.indexOfFirst { it.displayKey == raw.displayKey }

                    if (existingByIdentity >= 0 && existingByIdentity != existingByDisplayKey) {
                        val oldDevice = enriched[existingByIdentity]
                        if (oldDevice.displayKey != raw.displayKey) {
                            FileLogger.i(TAG, "Port migration detected: ${oldDevice.deviceName} ${oldDevice.displayKey} -> ${raw.displayKey} (identityKey=${raw.identityKey})")
                            enriched[existingByIdentity] = raw.copy(
                                connectionState = oldDevice.connectionState,
                                appList = oldDevice.appList,
                                lastSeenTimeMs = System.currentTimeMillis()
                            )
                            val connected = _connectedDevices.value.toMutableList()
                            val cIdx = connected.indexOfFirst { it.displayKey == oldDevice.displayKey }
                            if (cIdx >= 0) {
                                val migratedConnected = raw.copy(
                                    connectionState = connected[cIdx].connectionState,
                                    appList = connected[cIdx].appList,
                                    lastSeenTimeMs = System.currentTimeMillis()
                                )
                                connected[cIdx] = migratedConnected
                                _connectedDevices.value = connected

                                if (migratedConnected.connectionState == ConnectionState.CONNECTED ||
                                    migratedConnected.connectionState == ConnectionState.RECONNECTING) {
                                    scope.launch {
                                        stopHeartbeat(oldDevice.displayKey)
                                        stopSyncJob(oldDevice.displayKey)
                                        heartbeatFailCounts.remove(oldDevice.displayKey)
                                        startHeartbeat(migratedConnected)
                                        FileLogger.i(TAG, "Port migration: restarting heartbeat for ${raw.deviceName} at new port ${raw.port}")
                                    }
                                }
                                FileLogger.i(TAG, "Port migration: updated connected device ${raw.deviceName} ${oldDevice.displayKey} -> ${raw.displayKey}")
                            }
                            continue
                        }
                    }

                    val existingIdx = if (existingByDisplayKey >= 0) existingByDisplayKey else existingByIdentity
                    if (existingIdx >= 0 && existingIdx < enriched.size) {
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
                                instanceId = raw.instanceId,
                                lastSeenTimeMs = System.currentTimeMillis()
                            )
                        }
                    } else {
                        enriched.add(raw.copy(connectionState = ConnectionState.DISCOVERED))
                    }
                }

                val staleKeys = enriched.filter { device ->
                    device.displayKey !in currentKeys &&
                    device.identityKey !in rawDevices.map { it.identityKey } &&
                    device.connectionState != ConnectionState.CONNECTED &&
                    device.connectionState != ConnectionState.RECONNECTING &&
                    device.connectionState != ConnectionState.CONNECTION_TIMEOUT
                }
                enriched.removeAll(staleKeys)

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
        val key = device.displayKey
        if (connectingDevices.putIfAbsent(key, true) != null) {
            FileLogger.w(TAG, "Already connecting to ${device.deviceName}, skipping duplicate request")
            return false
        }
        try {
            return withContext(Dispatchers.IO) {
                try {
                    FileLogger.i(TAG, "=== CONNECT START === target=${device.deviceName} (${device.displayKey})")

                    val existingConnected = _connectedDevices.value.find { it.displayKey == key }
                    if (existingConnected != null && existingConnected.connectionState == ConnectionState.CONNECTED) {
                        FileLogger.i(TAG, "Already connected to ${device.deviceName}")
                        return@withContext true
                    }

                    updateDeviceConnectionState(device, ConnectionState.CONNECTING)

                    val localPort = _serverPort.value
                    val localName = android.os.Build.MODEL

                    FileLogger.i(TAG, "Sending POST /connect/request to ${device.ipAddress}:${device.port}, localPort=$localPort localName=$localName")
                    val localInstanceId = jmdnsDiscovery.getInstanceId()
                    val requestId = appListClient.sendConnectRequest(
                        targetIp = device.ipAddress,
                        targetPort = device.port,
                        localDeviceName = localName,
                        localPort = localPort,
                        localInstanceId = localInstanceId
                    )

                    if (requestId == null) {
                        FileLogger.e(TAG, "=== CONNECT FAIL === sendConnectRequest returned null, target unresponsive")
                        updateDeviceConnectionState(device, ConnectionState.ERROR, "无法发送连接请求，目标设备无响应")
                        return@withContext false
                    }

                    FileLogger.i(TAG, "Request sent, requestId=$requestId, starting poll...")

                    val result = appListClient.pollConnectStatus(
                        targetIp = device.ipAddress,
                        targetPort = device.port,
                        requestId = requestId,
                        timeoutMs = ConnectionManager.CONNECT_TIMEOUT_MS
                    )

                    when (result) {
                        is AppListClient.ConnectResult.Accepted -> {
                            FileLogger.i(TAG, "=== CONNECT SUCCESS === accepted by=${result.responderName} id=$requestId")
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
                            FileLogger.i(TAG, "Connected to ${device.deviceName}, fetching app list in background...")

                            scope.launch {
                                fetchAppListWithRetry(connected, device.ipAddress, device.port, isInitiator = true)
                            }
                            true
                        }
                        is AppListClient.ConnectResult.Rejected -> {
                            FileLogger.w(TAG, "=== CONNECT REJECTED === reason=${result.message}")
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
                                updateDeviceConnectionState(device, ConnectionState.ERROR, result.message)
                                false
                            }
                        }
                        is AppListClient.ConnectResult.Error -> {
                            FileLogger.e(TAG, "=== CONNECT ERROR === ${result.message}")
                            updateDeviceConnectionState(device, ConnectionState.ERROR, result.message)
                            false
                        }
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "=== CONNECT EXCEPTION === ${e.javaClass.simpleName}: ${e.message}", e)
                    updateDeviceConnectionState(device, ConnectionState.ERROR, e.message ?: "连接异常")
                    false
                }
            }
        } finally {
            connectingDevices.remove(key)
        }
    }

    fun handleIncomingRequest(requestId: String, accepted: Boolean, autoAcceptKnown: Boolean = true): Boolean {
        FileLogger.i(TAG, "=== HANDLE_INCOMING === requestId=$requestId accepted=$accepted autoAcceptKnown=$autoAcceptKnown")
        val manager = ktorServer.connectionManager ?: run {
            FileLogger.e(TAG, "HANDLE_INCOMING: connectionManager is NULL!")
            return false
        }
        val pendingReq = manager.getPendingRequest(requestId)
        if (pendingReq == null) {
            FileLogger.e(TAG, "HANDLE_INCOMING: pendingReq not found for $requestId")
            return false
        }

        FileLogger.i(TAG, "HANDLE_INCOMING: from=${pendingReq.requesterName} (${pendingReq.requesterIp}:${pendingReq.requesterPort}) identityKey=${pendingReq.identityKey}")

        if (accepted || autoAcceptKnown) {
            val knownDevice = findKnownDevice(pendingReq.requesterIp, pendingReq.requesterName, pendingReq.requesterInstanceId)
            val shouldAutoAccept = accepted || knownDevice != null

            if (!accepted && knownDevice != null) {
                FileLogger.i(TAG, "HANDLE_INCOMING: auto-accepting, matched known device=${knownDevice.deviceName} (${knownDevice.displayKey})")
            }

            if (!shouldAutoAccept) {
                manager.removeRequest(requestId)
                return false
            }

            val response = manager.respondToRequest(requestId, accepted = true)
            FileLogger.i(TAG, "HANDLE_INCOMING: respondToRequest returned ${if (response != null) "OK" else "NULL"}")

            if (response != null) {
                val existingAppList = if (knownDevice != null) knownDevice.appList else emptyList()

                val connectedDevice = DeviceInfo(
                    ipAddress = pendingReq.requesterIp,
                    deviceName = pendingReq.requesterName,
                    port = pendingReq.requesterPort,
                    instanceId = pendingReq.requesterInstanceId,
                    appList = existingAppList,
                    connectionState = ConnectionState.CONNECTED,
                    lastSeenTimeMs = System.currentTimeMillis()
                )

                if (knownDevice != null && knownDevice.displayKey != connectedDevice.displayKey) {
                    FileLogger.i(TAG, "HANDLE_INCOMING: port migration for ${connectedDevice.deviceName} ${knownDevice.displayKey} -> ${connectedDevice.displayKey}")
                    migrateDeviceConnection(knownDevice, connectedDevice)
                } else {
                    addOrUpdateInConnected(connectedDevice)
                    addOrUpdateInEnriched(connectedDevice)
                }
                startHeartbeat(connectedDevice)

                scope.launch {
                    try {
                        fetchAppListWithRetry(connectedDevice, pendingReq.requesterIp, pendingReq.requesterPort, isInitiator = false)
                    } finally {
                        manager.removeRequest(requestId)
                    }
                }
                return true
            }
        } else {
            val response = manager.respondToRequest(requestId, accepted = false)
            FileLogger.i(TAG, "HANDLE_INCOMING: rejected, respondToRequest returned ${if (response != null) "OK" else "NULL"}")
        }

        manager.removeRequest(requestId)
        return false
    }

    private fun findKnownDevice(ip: String, name: String, instanceId: String): DeviceInfo? {
        if (instanceId.isNotEmpty()) {
            _connectedDevices.value.find { it.instanceId == instanceId }?.let { return it }
            _enrichedDevices.value.find { it.instanceId == instanceId }?.let { return it }
        }
        _connectedDevices.value.find { it.deviceName == name && it.ipAddress == ip }?.let { return it }
        _enrichedDevices.value.find { it.deviceName == name && it.ipAddress == ip }?.let { return it }
        return null
    }

    private fun migrateDeviceConnection(oldDevice: DeviceInfo, newDevice: DeviceInfo) {
        stopHeartbeat(oldDevice.displayKey)
        stopSyncJob(oldDevice.displayKey)
        heartbeatFailCounts.remove(oldDevice.displayKey)

        removeFromConnected(oldDevice)

        val enriched = _enrichedDevices.value.toMutableList()
        val idx = enriched.indexOfFirst { it.displayKey == oldDevice.displayKey }
        if (idx >= 0) {
            enriched[idx] = newDevice
        } else {
            enriched.add(newDevice)
        }
        _enrichedDevices.value = enriched

        addOrUpdateInConnected(newDevice)
        FileLogger.i(TAG, "Port migration complete: ${oldDevice.displayKey} -> ${newDevice.displayKey}, appList preserved (${newDevice.appList.size} apps)")
    }

    fun dismissIncomingRequest(requestId: String) {
        ktorServer.connectionManager?.removeRequest(requestId)
    }

    fun disconnectDevice(device: DeviceInfo) {
        scope.launch {
            stopHeartbeat(device.displayKey)
            stopSyncJob(device.displayKey)
            heartbeatFailCounts.remove(device.displayKey)

            try {
                val localKey = getLocalDisplayKey()
                val localIdentity = jmdnsDiscovery.getInstanceId()
                appListClient.sendDisconnectNotification(device.ipAddress, device.port, localKey, localIdentity)
                FileLogger.i(TAG, "Sent disconnect notification to ${device.deviceName}")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to send disconnect notification to ${device.deviceName}: ${e.message}")
            }

            val existingAppList = _connectedDevices.value
                .find { it.displayKey == device.displayKey }?.appList.orEmpty()

            val disconnected = device.copy(
                connectionState = ConnectionState.DISCONNECTED,
                appList = existingAppList
            )
            removeFromConnected(disconnected)

            val enriched = _enrichedDevices.value.toMutableList()
            val idx = enriched.indexOfFirst { it.displayKey == disconnected.displayKey }
            if (idx >= 0) {
                enriched[idx] = disconnected
                _enrichedDevices.value = enriched
            }
            FileLogger.i(TAG, "Disconnected from ${device.deviceName}, appList preserved (${existingAppList.size} apps)")
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
                FileLogger.i(TAG, "Server started on port $port (forced)")

                startObservingConnectionManager()

                jmdnsDiscovery.startDiscovery(port)
                _isRunning.value = true
                FileLogger.i(TAG, "Discovery started on port $port (forced)")
            }
        }
    }

    private fun handleRemoteRefreshAppList(remoteDisplayKey: String) {
        scope.launch {
            val device = _connectedDevices.value.find { it.displayKey == remoteDisplayKey }
            if (device != null) {
                FileLogger.i(TAG, "Remote refresh notification from $remoteDisplayKey, re-fetching app list")
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
            FileLogger.w(TAG, "Error refreshing app list from ${rawDevice.deviceName}: ${e.message}")
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
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateRecalculationMs < UPDATE_RECALCULATION_THROTTLE_MS) {
                        return@collect
                    }
                    lastUpdateRecalculationMs = now
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
            FileLogger.i(TAG, "Recalculated updates: ${deduplicated.size} available")
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
                    FileLogger.i(TAG, "Loaded ${cached.size} apps from cache")
                }

                val apps = appScanner.scanInstalledApps()
                _localApps.value = apps
                saveLocalAppsToFile(apps)
                FileLogger.i(TAG, "Scanned ${apps.size} local apps, saved to cache")

                notifyConnectedDevicesToRefresh()
            } catch (e: Exception) {
                FileLogger.e(TAG, "scanLocalApps failed", e)
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
                FileLogger.d(TAG, "Sent refresh notification to ${device.deviceName}")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to send refresh notification to ${device.deviceName}: ${e.message}")
            }
        }
    }

    private fun saveLocalAppsToFile(apps: List<AppInfo>) {
        try {
            val jsonStr = json.encodeToString(apps)
            appCacheFile.writeText(jsonStr)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to save local apps cache", e)
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
            FileLogger.e(TAG, "Failed to load local apps cache", e)
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

            appPacker.clearCache()
            FileLogger.i(TAG, "Cleared apks cache on startup")

            ktorServer.initConnectionManager(android.os.Build.MODEL)
            ktorServer.setAppListProvider { _localApps.value }
            ktorServer.setPacker { appPacker.packApp(it) }
            ktorServer.setDisconnectHandler { displayKey -> handleRemoteDisconnect(displayKey) }
            ktorServer.setRefreshAppListHandler { remoteDisplayKey -> handleRemoteRefreshAppList(remoteDisplayKey) }

            val port = ktorServer.start()
            _serverPort.value = port
            FileLogger.i(TAG, "Server started on port $port")

            startObservingConnectionManager()

            jmdnsDiscovery.startDiscovery(port)
            _isRunning.value = true
            FileLogger.i(TAG, "Discovery started with server port $port")
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
                    FileLogger.w(TAG, "Error clearing connection manager", e)
                }
                try {
                    jmdnsDiscovery.stopDiscovery()
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Error stopping discovery", e)
                }
                try {
                    ktorServer.stop()
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Error stopping server", e)
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
                FileLogger.i(TAG, "Server and discovery stopped")
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

        return if (file != null) {
            installApp(updateInfo, file)
        } else {
            FileLogger.e(TAG, "installApp: Downloaded file NOT FOUND for ${updateInfo.remoteApp.packageName} v${updateInfo.remoteApp.versionCode}. Was download successful?")
            _installStatus.value = InstallStatus.Failed(updateInfo.remoteApp.packageName, "Downloaded file not found")
            ApkInstaller.InstallationResult.Error("Downloaded file not found")
        }
    }

    fun installApp(updateInfo: UpdateInfo, file: File): ApkInstaller.InstallationResult {
        FileLogger.i(TAG, "=== installApp START === pkg=${updateInfo.remoteApp.packageName} v${updateInfo.remoteApp.versionCode} file=${file.name}")
        _installStatus.value = InstallStatus.Installing(updateInfo.remoteApp.packageName)
        FileLogger.d(TAG, "installApp: calling installApks(${file.absolutePath}, size=${file.length()})")

        return apkInstaller.installApks(file).also { result ->
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
    }

    suspend fun downloadAndInstallApp(updateInfo: UpdateInfo): ApkInstaller.InstallationResult {
        val downloadResult = downloadApp(updateInfo)
        return when (downloadResult) {
            is AppListClient.DownloadResult.Success -> installApp(updateInfo, downloadResult.file)
            is AppListClient.DownloadResult.Error -> {
                _installStatus.value = InstallStatus.Failed(updateInfo.remoteApp.packageName, downloadResult.message)
                ApkInstaller.InstallationResult.Error(downloadResult.message)
            }
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
        const val HEARTBEAT_PING_INTERVAL_MS = 20_000L
        const val HEARTBEAT_SYNC_INTERVAL_MS = 120_000L
        const val HEARTBEAT_PING_TOLERANCE = 1
        const val HEARTBEAT_PING_MAX_FAILURES = 4
        private const val UPDATE_RECALCULATION_THROTTLE_MS = 5_000L

        @Volatile
        private var instance: AppRepository? = null

        fun getInstance(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
