package com.lansync.app.data.connection

import com.lansync.app.data.AppConfig
import com.lansync.app.data.FileLogger
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.IncomingConnectRequest
import com.lansync.app.data.server.PairingStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [ConnectionCoordinator] 默认实现（Actor 模型，ARCH §6）。
 *
 * 行为严格对齐 SPEC：§7.4 设备连接状态机、§7.5 端口迁移、§7.6 陈旧清理、§7.7（TT3）反向连接干净语义。
 * **全部可变状态仅在单一 Actor 协程内读写**（[handleEvent]）；网络 I/O 在子协程执行后以事件回填。
 * 超时/阈值全部取自 [AppConfig]（SPEC §7.2），无任何硬编码常量。
 *
 * 与旧 `AppRepository` 连接逻辑等价，但消除了 `ConcurrentHashMap` 与 `StateFlow` 交错的竞态窗口。
 */
class DefaultConnectionCoordinator(
    private val config: AppConfig,
    private val transport: ConnectionTransport,
    private val pairingStore: PairingStore,
    private val pairingHistory: PairingHistoryStore,
    private val localIdentityProvider: () -> LocalIdentity,
    private val scope: CoroutineScope
) : ConnectionCoordinator {

    private val events = Channel<ConnectionEvent>(Channel.UNLIMITED)
    private var actorJob: Job? = null

    // ---- 以下可变状态仅在 Actor 协程内访问（单点收敛，ARCH §6）----
    private val enriched = mutableListOf<DeviceInfo>()
    private val connected = mutableListOf<DeviceInfo>()
    private val pendingIncoming = LinkedHashMap<String, IncomingConnectRequest>()
    private val failCounts = mutableMapOf<String, Int>()
    private val connectingKeys = mutableSetOf<String>()
    private val heartbeatJobs = mutableMapOf<String, Job>()
    private val syncJobs = mutableMapOf<String, Job>()

    private val _enrichedDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val _connectedDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val _incomingRequests = MutableStateFlow<List<IncomingConnectRequest>>(emptyList())
    override val enrichedDevices: StateFlow<List<DeviceInfo>> = _enrichedDevices.asStateFlow()
    override val connectedDevices: StateFlow<List<DeviceInfo>> = _connectedDevices.asStateFlow()
    override val incomingRequests: StateFlow<List<IncomingConnectRequest>> = _incomingRequests.asStateFlow()

    override fun start() {
        if (actorJob != null) return
        actorJob = scope.launch {
            for (event in events) handleEvent(event)
        }
        FileLogger.i(TAG, "ConnectionCoordinator actor started")
    }

    override fun stop() {
        actorJob?.cancel()
        actorJob = null
        heartbeatJobs.values.forEach { it.cancel() }
        heartbeatJobs.clear()
        syncJobs.values.forEach { it.cancel() }
        syncJobs.clear()
        failCounts.clear()
        connectingKeys.clear()
        FileLogger.i(TAG, "ConnectionCoordinator stopped")
    }

    override fun submit(event: ConnectionEvent) {
        events.trySend(event) // UNLIMITED → 非阻塞、不丢事件
    }

    override suspend fun connect(device: DeviceInfo): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        submit(ConnectionEvent.ConnectRequested(device, deferred))
        return deferred.await()
    }

    override fun disconnect(device: DeviceInfo) = submit(ConnectionEvent.LocalDisconnect(device))

    override fun handleIncoming(requestId: String, accepted: Boolean) =
        submit(ConnectionEvent.IncomingDecision(requestId, accepted))

    override fun dismissIncoming(requestId: String) = submit(ConnectionEvent.DismissIncoming(requestId))

    // ==================== Actor：唯一状态写入点 ====================

    private fun handleEvent(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.RawDevicesUpdated -> onRawDevicesUpdated(event.devices)
            is ConnectionEvent.ConnectRequested -> onConnectRequested(event.device, event.deferred)
            is ConnectionEvent.ConnectCompleted -> onConnectCompleted(event.device, event.result, event.deferred)
            is ConnectionEvent.HeartbeatTick -> onHeartbeatTick(event.displayKey, event.alive)
            is ConnectionEvent.FastReconnectResult -> onFastReconnectResult(event.device, event.alive)
            is ConnectionEvent.AppListFetched -> onAppListFetched(event.displayKey, event.apps)
            is ConnectionEvent.IncomingRequestReceived -> onIncomingRequestReceived(event.request)
            is ConnectionEvent.IncomingDecision -> onIncomingDecision(event.requestId, event.accepted)
            is ConnectionEvent.DismissIncoming -> onDismissIncoming(event.requestId)
            is ConnectionEvent.RemoteDisconnect -> onRemoteDisconnect(event.key)
            is ConnectionEvent.LocalDisconnect -> onLocalDisconnect(event.device)
        }
    }

    /** SPEC §7.5 端口迁移 + §7.6 陈旧清理 + 新设备登记 DISCOVERED。 */
    private fun onRawDevicesUpdated(rawDevices: List<DeviceInfo>) {
        val currentKeys = rawDevices.map { it.displayKey }.toSet()
        for (raw in rawDevices) {
            val byIdentity = enriched.indexOfFirst { it.identityKey.isNotEmpty() && it.identityKey == raw.identityKey }
            val byDisplayKey = enriched.indexOfFirst { it.displayKey == raw.displayKey }

            // 端口迁移：同 instanceId 但 displayKey 变了
            if (byIdentity >= 0 && byIdentity != byDisplayKey && enriched[byIdentity].displayKey != raw.displayKey) {
                val old = enriched[byIdentity]
                FileLogger.i(TAG, "Port migration: ${old.deviceName} ${old.displayKey} -> ${raw.displayKey}")
                enriched[byIdentity] = raw.copy(
                    connectionState = old.connectionState,
                    appList = old.appList,
                    lastSeenTimeMs = now()
                )
                val cIdx = connected.indexOfFirst { it.displayKey == old.displayKey }
                if (cIdx >= 0) {
                    val migrated = raw.copy(
                        connectionState = connected[cIdx].connectionState,
                        appList = connected[cIdx].appList,
                        lastSeenTimeMs = now()
                    )
                    connected[cIdx] = migrated
                    if (migrated.connectionState == ConnectionState.CONNECTED ||
                        migrated.connectionState == ConnectionState.RECONNECTING
                    ) {
                        stopHeartbeat(old.displayKey)
                        stopSync(old.displayKey)
                        failCounts.remove(old.displayKey)
                        startHeartbeat(migrated)
                    }
                }
                continue
            }

            val idx = if (byDisplayKey >= 0) byDisplayKey else byIdentity
            if (idx >= 0 && idx < enriched.size) {
                val existing = enriched[idx]
                enriched[idx] = if (existing.connectionState == ConnectionState.DISCOVERED ||
                    existing.connectionState == ConnectionState.DISCONNECTED
                ) {
                    raw.copy(connectionState = existing.connectionState, lastSeenTimeMs = now())
                } else {
                    existing.copy(
                        deviceName = raw.deviceName, ipAddress = raw.ipAddress,
                        port = raw.port, instanceId = raw.instanceId, lastSeenTimeMs = now()
                    )
                }
            } else {
                enriched.add(raw.copy(connectionState = ConnectionState.DISCOVERED))
            }
        }

        // 陈旧清理（§7.6）：已连接类状态不因 mDNS 消失而清理
        val rawIdentityKeys = rawDevices.map { it.identityKey }.toSet()
        enriched.removeAll { d ->
            d.displayKey !in currentKeys && d.identityKey !in rawIdentityKeys &&
                d.connectionState != ConnectionState.CONNECTED &&
                d.connectionState != ConnectionState.RECONNECTING &&
                d.connectionState != ConnectionState.CONNECTION_TIMEOUT
        }
        publishDevices()
    }

    private fun onConnectRequested(device: DeviceInfo, deferred: CompletableDeferred<Boolean>) {
        val key = device.displayKey
        val existing = connected.find { it.displayKey == key }
        if (existing != null && existing.connectionState == ConnectionState.CONNECTED) {
            FileLogger.i(TAG, "connect: already CONNECTED to ${device.deviceName}")
            deferred.complete(true)
            return
        }
        if (!connectingKeys.add(key)) { // 去重锁（SPEC §7.4）
            FileLogger.w(TAG, "connect: already connecting to ${device.deviceName}, skip duplicate")
            deferred.complete(false)
            return
        }
        updateState(device, ConnectionState.CONNECTING, null)
        publishDevices()
        scope.launch {
            val result = try {
                val id = localIdentityProvider()
                val reqId = transport.sendConnectRequest(
                    device.ipAddress, device.port, id.deviceName, id.ipAddress, id.port, id.instanceId
                )
                if (reqId == null) {
                    ConnectAttemptResult.SendFailed("无法发送连接请求，目标设备无响应")
                } else {
                    when (val o = transport.pollConnectStatus(
                        device.ipAddress, device.port, reqId, config.connectTimeoutMs, config.pollIntervalMs
                    )) {
                        is ConnectOutcome.Accepted -> ConnectAttemptResult.Accepted(o.responderName)
                        is ConnectOutcome.Rejected -> ConnectAttemptResult.Rejected(o.message)
                        is ConnectOutcome.Timeout -> ConnectAttemptResult.TimedOut(o.message)
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "connect network error for ${device.deviceName}", e)
                ConnectAttemptResult.Failed(e.message ?: "连接异常")
            }
            submit(ConnectionEvent.ConnectCompleted(device, result, deferred))
        }
    }

    private fun onConnectCompleted(device: DeviceInfo, result: ConnectAttemptResult, deferred: CompletableDeferred<Boolean>) {
        connectingKeys.remove(device.displayKey)
        when (result) {
            is ConnectAttemptResult.Accepted -> {
                val existingApps = enriched.find { it.displayKey == device.displayKey }?.appList.orEmpty()
                val conn = device.copy(
                    connectionState = ConnectionState.CONNECTED, appList = existingApps,
                    lastSeenTimeMs = now(), connectionError = null
                )
                upsertConnected(conn)
                upsertEnriched(conn)
                startHeartbeat(conn)
                launchFetchAppList(conn.displayKey, device.ipAddress, device.port)
                publishDevices()
                deferred.complete(true)
            }
            is ConnectAttemptResult.Rejected -> {
                updateState(device, ConnectionState.ERROR, "对方拒绝连接"); publishDevices(); deferred.complete(false)
            }
            is ConnectAttemptResult.TimedOut -> {
                val reverseConnected = connected.any {
                    it.displayKey == device.displayKey && it.connectionState == ConnectionState.CONNECTED
                }
                if (reverseConnected) {
                    FileLogger.i(TAG, "connect timeout but reverse-connected, treat as success")
                    deferred.complete(true)
                } else {
                    updateState(device, ConnectionState.ERROR, result.message); publishDevices(); deferred.complete(false)
                }
            }
            is ConnectAttemptResult.SendFailed -> {
                updateState(device, ConnectionState.ERROR, result.message); publishDevices(); deferred.complete(false)
            }
            is ConnectAttemptResult.Failed -> {
                updateState(device, ConnectionState.ERROR, result.message); publishDevices(); deferred.complete(false)
            }
        }
    }

    /** SPEC §7.4 心跳三档：failCount ≤ tolerance(1) / < max(4) / ≥ max(4)。 */
    private fun onHeartbeatTick(key: String, alive: Boolean) {
        if (alive) {
            failCounts.remove(key)
            connected.find { it.displayKey == key }?.let { cur ->
                val refreshed = cur.copy(
                    connectionState = ConnectionState.CONNECTED,
                    lastSeenTimeMs = now(), connectionError = null
                )
                upsertConnected(refreshed); upsertEnriched(refreshed); publishDevices()
            }
            return
        }
        val fc = (failCounts[key] ?: 0) + 1
        failCounts[key] = fc
        val cur = connected.find { it.displayKey == key } ?: return
        val max = config.heartbeatPingMaxFailures
        when {
            fc <= config.heartbeatPingTolerance -> {
                val u = cur.copy(
                    connectionState = ConnectionState.RECONNECTING,
                    connectionError = "连接不稳定... ($fc/$max)",
                    lastSeenTimeMs = now()
                )
                upsertConnected(u); upsertEnriched(u)
            }
            fc < max -> {
                val u = cur.copy(
                    connectionState = ConnectionState.RECONNECTING,
                    connectionError = "正在尝试重新连接... ($fc/$max)",
                    lastSeenTimeMs = now()
                )
                upsertConnected(u); upsertEnriched(u)
                scope.launch {
                    val alive2 = try { transport.fetchDeviceInfo(cur.ipAddress, cur.port) != null } catch (e: Exception) { false }
                    submit(ConnectionEvent.FastReconnectResult(cur, alive2))
                }
            }
            else -> {
                val t = cur.copy(connectionState = ConnectionState.CONNECTION_TIMEOUT, connectionError = "连接超时，设备已离线")
                connected.removeAll { it.displayKey == key }
                upsertEnriched(t)
                stopHeartbeat(key); stopSync(key); failCounts.remove(key)
                FileLogger.w(TAG, "heartbeat timeout for ${cur.deviceName} after $fc failures")
            }
        }
        publishDevices()
    }

    private fun onFastReconnectResult(device: DeviceInfo, alive: Boolean) {
        if (!alive) return
        failCounts.remove(device.displayKey)
        val recovered = device.copy(
            connectionState = ConnectionState.CONNECTED, lastSeenTimeMs = now(), connectionError = null
        )
        upsertConnected(recovered); upsertEnriched(recovered)
        launchFetchAppList(recovered.displayKey, device.ipAddress, device.port)
        publishDevices()
        FileLogger.i(TAG, "fast reconnect success for ${device.deviceName}")
    }

    private fun onAppListFetched(key: String, apps: List<AppInfo>) {
        connected.indexOfFirst { it.displayKey == key }.takeIf { it >= 0 }?.let { connected[it] = connected[it].copy(appList = apps) }
        enriched.indexOfFirst { it.displayKey == key }.takeIf { it >= 0 }?.let { enriched[it] = enriched[it].copy(appList = apps) }
        publishDevices()
    }

    /** SPEC §7.7（TT3）：命中配对历史→自动接受；陌生→PENDING 弹窗，绝不自动放行。 */
    private fun onIncomingRequestReceived(request: IncomingConnectRequest) {
        if (request.requesterInstanceId.isNotEmpty() && pairingHistory.isPaired(request.requesterInstanceId)) {
            FileLogger.i(TAG, "incoming ${request.requesterName}: auto-accept (paired history)")
            respondIncoming(request, accepted = true)
            return
        }
        pendingIncoming[request.requestId] = request
        publishIncoming()
        FileLogger.i(TAG, "incoming ${request.requesterName}: stranger -> PENDING (await user)")
    }

    private fun onIncomingDecision(requestId: String, accepted: Boolean) {
        val request = pendingIncoming[requestId]
        if (request == null) {
            FileLogger.w(TAG, "IncomingDecision: no pending request $requestId")
            return
        }
        respondIncoming(request, accepted)
    }

    private fun onDismissIncoming(requestId: String) {
        if (pendingIncoming.remove(requestId) != null) publishIncoming()
    }

    private fun respondIncoming(request: IncomingConnectRequest, accepted: Boolean) {
        // 协议侧置位：使发起方轮询 getStatus 立即可见（TT3：拒绝即时 rejected，不再 30s 超时）
        pairingStore.respondToRequest(request.requestId, accepted)
        pendingIncoming.remove(request.requestId)
        publishIncoming()
        if (!accepted) {
            FileLogger.i(TAG, "incoming ${request.requesterName}: rejected (immediate)")
            return
        }
        if (request.requesterInstanceId.isNotEmpty()) pairingHistory.recordPaired(request.requesterInstanceId)

        val known = findKnownDevice(request)
        val device = DeviceInfo(
            ipAddress = request.requesterIp, deviceName = request.requesterName, port = request.requesterPort,
            instanceId = request.requesterInstanceId, appList = known?.appList.orEmpty(),
            connectionState = ConnectionState.CONNECTED, lastSeenTimeMs = now()
        )
        if (known != null && known.displayKey != device.displayKey) {
            migrate(known, device)
        } else {
            upsertConnected(device); upsertEnriched(device)
        }
        startHeartbeat(device)
        launchFetchAppList(device.displayKey, request.requesterIp, request.requesterPort)
        publishDevices()
        FileLogger.i(TAG, "incoming ${request.requesterName}: accepted -> CONNECTED")
    }

    private fun onRemoteDisconnect(key: String) {
        val matches = connected.filter { it.displayKey == key || it.identityKey == key }
        if (matches.isEmpty()) return
        for (d in matches) {
            stopHeartbeat(d.displayKey); stopSync(d.displayKey); failCounts.remove(d.displayKey)
            connected.removeAll { it.displayKey == d.displayKey }
            enriched.indexOfFirst { it.displayKey == d.displayKey }.takeIf { it >= 0 }?.let {
                enriched[it] = enriched[it].copy(connectionState = ConnectionState.DISCONNECTED) // appList 保留
            }
        }
        publishDevices()
        FileLogger.i(TAG, "remote disconnect: $key")
    }

    private fun onLocalDisconnect(device: DeviceInfo) {
        val key = device.displayKey
        stopHeartbeat(key); stopSync(key); failCounts.remove(key)
        val id = localIdentityProvider()
        scope.launch {
            try { transport.sendDisconnectNotification(device.ipAddress, device.port, id.displayKey, id.instanceId) }
            catch (e: Exception) { FileLogger.w(TAG, "sendDisconnectNotification failed: ${e.message}") }
        }
        val existingApps = connected.find { it.displayKey == key }?.appList.orEmpty()
        val disc = device.copy(connectionState = ConnectionState.DISCONNECTED, appList = existingApps)
        connected.removeAll { it.displayKey == key }
        enriched.indexOfFirst { it.displayKey == key }.takeIf { it >= 0 }?.let { enriched[it] = disc }
        publishDevices()
        FileLogger.i(TAG, "local disconnect: ${device.deviceName} (appList preserved)")
    }

    // ==================== 每设备任务（在 Actor 内启停） ====================

    private fun startHeartbeat(device: DeviceInfo) {
        val key = device.displayKey
        heartbeatJobs[key]?.cancel(); failCounts.remove(key)
        heartbeatJobs[key] = scope.launch {
            while (isActive) {
                delay(config.heartbeatPingIntervalMs)
                val alive = try { transport.pingDevice(device.ipAddress, device.port, config.pingTimeoutMs) }
                catch (e: Exception) { false }
                submit(ConnectionEvent.HeartbeatTick(key, alive))
            }
        }
        startSync(device)
    }

    private fun startSync(device: DeviceInfo) {
        val key = device.displayKey
        syncJobs[key]?.cancel()
        syncJobs[key] = scope.launch {
            while (isActive) {
                delay(config.heartbeatSyncIntervalMs)
                val apps = try { transport.fetchAppList(device.ipAddress, device.port) } catch (e: Exception) { null }
                if (apps != null) submit(ConnectionEvent.AppListFetched(key, apps))
            }
        }
    }

    private fun launchFetchAppList(key: String, ip: String, port: Int) {
        scope.launch {
            for (attempt in 1..config.fetchAppListMaxRetries) {
                val apps = try { transport.fetchAppList(ip, port) } catch (e: Exception) { null }
                if (!apps.isNullOrEmpty()) {
                    submit(ConnectionEvent.AppListFetched(key, apps))
                    return@launch
                }
                if (attempt < config.fetchAppListMaxRetries) delay(config.fetchAppListRetryDelayMs)
            }
            FileLogger.w(TAG, "fetchAppList failed after ${config.fetchAppListMaxRetries} attempts for $key")
        }
    }

    private fun stopHeartbeat(key: String) { heartbeatJobs[key]?.cancel(); heartbeatJobs.remove(key) }
    private fun stopSync(key: String) { syncJobs[key]?.cancel(); syncJobs.remove(key) }

    // ==================== 状态辅助（仅 Actor 内调用） ====================

    private fun upsertConnected(d: DeviceInfo) {
        val i = connected.indexOfFirst { it.displayKey == d.displayKey }; if (i >= 0) connected[i] = d else connected.add(d)
    }

    private fun upsertEnriched(d: DeviceInfo) {
        val i = enriched.indexOfFirst { it.displayKey == d.displayKey }; if (i >= 0) enriched[i] = d else enriched.add(d)
    }

    private fun updateState(device: DeviceInfo, state: ConnectionState, error: String?) {
        val updated = device.copy(connectionState = state, connectionError = error)
        enriched.indexOfFirst { it.displayKey == device.displayKey }.takeIf { it >= 0 }?.let { enriched[it] = updated }
        val c = connected.indexOfFirst { it.displayKey == device.displayKey }
        if (c >= 0) {
            if (state == ConnectionState.CONNECTED) connected[c] = updated else connected.removeAt(c)
        }
    }

    private fun findKnownDevice(req: IncomingConnectRequest): DeviceInfo? {
        if (req.requesterInstanceId.isNotEmpty()) {
            connected.find { it.instanceId == req.requesterInstanceId }?.let { return it }
            enriched.find { it.instanceId == req.requesterInstanceId }?.let { return it }
        }
        connected.find { it.deviceName == req.requesterName && it.ipAddress == req.requesterIp }?.let { return it }
        enriched.find { it.deviceName == req.requesterName && it.ipAddress == req.requesterIp }?.let { return it }
        return null
    }

    private fun migrate(old: DeviceInfo, new: DeviceInfo) {
        stopHeartbeat(old.displayKey); stopSync(old.displayKey); failCounts.remove(old.displayKey)
        connected.removeAll { it.displayKey == old.displayKey }
        val i = enriched.indexOfFirst { it.displayKey == old.displayKey }
        if (i >= 0) enriched[i] = new else enriched.add(new)
        upsertConnected(new)
        FileLogger.i(TAG, "migrate (incoming): ${old.displayKey} -> ${new.displayKey}, appList preserved")
    }

    private fun publishDevices() {
        _enrichedDevices.value = enriched.toList()
        _connectedDevices.value = connected.toList()
    }

    private fun publishIncoming() {
        _incomingRequests.value = pendingIncoming.values
            .filter { it.status == IncomingConnectRequest.RequestStatus.PENDING }
            .sortedByDescending { it.timestamp }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAG = "ConnectionCoordinator"
    }
}
