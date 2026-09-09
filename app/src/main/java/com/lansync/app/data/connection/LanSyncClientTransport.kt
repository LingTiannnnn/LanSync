package com.lansync.app.data.connection

import com.lansync.app.data.transfer.LanSyncClient

/**
 * [ConnectionTransport] 的 [LanSyncClient] 适配（Phase 4 接线）。
 *
 * 把 `LanSyncClient.ConnectResult` 映射为连接层的 [ConnectOutcome]，其余方法直接转发。
 * 使 `DefaultConnectionCoordinator` 复用 Phase 1 已绿的 `LanSyncClient`，而无需其反向依赖连接层类型。
 */
class LanSyncClientTransport(private val client: LanSyncClient) : ConnectionTransport {

    override suspend fun sendConnectRequest(
        targetIp: String, targetPort: Int, requesterName: String,
        requesterIp: String, requesterPort: Int, requesterInstanceId: String
    ): String? = client.sendConnectRequest(targetIp, targetPort, requesterName, requesterIp, requesterPort, requesterInstanceId)

    override suspend fun pollConnectStatus(
        targetIp: String, targetPort: Int, requestId: String, timeoutMs: Long, pollIntervalMs: Long
    ): ConnectOutcome = when (val r = client.pollConnectStatus(targetIp, targetPort, requestId, timeoutMs, pollIntervalMs)) {
        is LanSyncClient.ConnectResult.Accepted -> ConnectOutcome.Accepted(r.responderName)
        is LanSyncClient.ConnectResult.Rejected -> ConnectOutcome.Rejected(r.message)
        is LanSyncClient.ConnectResult.Timeout -> ConnectOutcome.Timeout(r.message)
    }

    override suspend fun pingDevice(ipAddress: String, port: Int, timeoutMs: Long): Boolean =
        client.pingDevice(ipAddress, port, timeoutMs)

    override suspend fun fetchAppList(ipAddress: String, port: Int) = client.fetchAppList(ipAddress, port)

    override suspend fun fetchDeviceInfo(ipAddress: String, port: Int) = client.fetchDeviceInfo(ipAddress, port)

    override suspend fun sendDisconnectNotification(
        targetIp: String, targetPort: Int, localDisplayKey: String, localIdentityKey: String
    ): Boolean = client.sendDisconnectNotification(targetIp, targetPort, localDisplayKey, localIdentityKey)
}
