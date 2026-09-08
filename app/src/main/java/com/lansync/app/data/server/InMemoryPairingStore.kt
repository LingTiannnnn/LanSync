package com.lansync.app.data.server

import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.ConnectResponsePayload
import com.lansync.app.data.model.IncomingConnectRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [PairingStore] 的内存实现，忠实复现 SPEC.md §7.3 接收方侧配对协议状态机。
 *
 * 这是旧 [com.lansync.app.data.connection.ConnectionManager] 协议状态部分的「实现重写」：
 * 更瘦（不含面向 UI 的 incomingRequests StateFlow，那属于连接层职责），构造注入 scope 以便
 * 虚拟时间测试。15s 自动超时（[REQUEST_TIMEOUT_MS]）取自 SPEC.md §7.2。
 *
 * 并发说明：本类无 StateFlow，pending 映射用 [ConcurrentHashMap] 即可；连接状态的单点收敛
 * （Actor/Mutex）是 ConnectionCoordinator 的职责（ARCHITECTURE.md §6），不在此。
 */
class InMemoryPairingStore(
    private val localDeviceName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PairingStore {

    private val pendingRequests = ConcurrentHashMap<String, IncomingConnectRequest>()
    private val timeoutJobs = ConcurrentHashMap<String, Job>()

    override fun receiveRequest(payload: ConnectRequestPayload): Boolean {
        if (pendingRequests.containsKey(payload.requestId)) return false

        pendingRequests[payload.requestId] = IncomingConnectRequest(
            requestId = payload.requestId,
            requesterName = payload.requesterName,
            requesterIp = payload.requesterIp,
            requesterPort = payload.requesterPort,
            requesterInstanceId = payload.requesterInstanceId,
            timestamp = payload.timestamp
        )

        timeoutJobs[payload.requestId] = scope.launch {
            delay(REQUEST_TIMEOUT_MS)
            handleTimeout(payload.requestId)
        }
        return true
    }

    override fun getStatus(requestId: String): ConnectResponsePayload? {
        val request = pendingRequests[requestId] ?: return null
        return when (request.status) {
            IncomingConnectRequest.RequestStatus.PENDING -> null
            IncomingConnectRequest.RequestStatus.ACCEPTED -> ConnectResponsePayload(
                requestId = requestId,
                accepted = true,
                responderName = localDeviceName,
                message = "Accepted"
            )
            IncomingConnectRequest.RequestStatus.REJECTED -> ConnectResponsePayload(
                requestId = requestId,
                accepted = false,
                responderName = localDeviceName,
                message = "Rejected"
            )
            IncomingConnectRequest.RequestStatus.TIMEOUT -> ConnectResponsePayload(
                requestId = requestId,
                accepted = false,
                message = "Timeout"
            )
        }
    }

    override fun respondToRequest(requestId: String, accepted: Boolean): ConnectResponsePayload? {
        val request = pendingRequests[requestId] ?: return null
        timeoutJobs[requestId]?.cancel()
        timeoutJobs.remove(requestId)

        val newStatus = if (accepted) {
            IncomingConnectRequest.RequestStatus.ACCEPTED
        } else {
            IncomingConnectRequest.RequestStatus.REJECTED
        }
        pendingRequests[requestId] = request.copy(status = newStatus)

        return ConnectResponsePayload(
            requestId = requestId,
            accepted = accepted,
            responderName = localDeviceName,
            message = if (accepted) "Connection accepted" else "Connection rejected"
        )
    }

    fun removeRequest(requestId: String) {
        timeoutJobs[requestId]?.cancel()
        timeoutJobs.remove(requestId)
        pendingRequests.remove(requestId)
    }

    fun clearAll() {
        timeoutJobs.values.forEach { it.cancel() }
        timeoutJobs.clear()
        pendingRequests.clear()
    }

    private fun handleTimeout(requestId: String) {
        val request = pendingRequests[requestId]
        if (request != null && request.status == IncomingConnectRequest.RequestStatus.PENDING) {
            pendingRequests[requestId] = request.copy(status = IncomingConnectRequest.RequestStatus.TIMEOUT)
        }
        timeoutJobs.remove(requestId)
    }

    companion object {
        /** SPEC.md §7.2：接收方登记请求后 15s 无响应自动置 TIMEOUT。 */
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
