package com.lansync.app.data.connection

import android.util.Log
import com.lansync.app.data.FileLogger
import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.ConnectResponsePayload
import com.lansync.app.data.model.IncomingConnectRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConnectionManager(private val localDeviceName: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingRequests = ConcurrentHashMap<String, IncomingConnectRequest>()
    private val timeoutJobs = ConcurrentHashMap<String, Job>()

    private val _incomingRequests = MutableStateFlow<List<IncomingConnectRequest>>(emptyList())
    val incomingRequests: StateFlow<List<IncomingConnectRequest>> = _incomingRequests.asStateFlow()

    fun receiveRequest(payload: ConnectRequestPayload): Boolean {
        if (pendingRequests.containsKey(payload.requestId)) {
            FileLogger.w(TAG, "Duplicate request rejected: ${payload.requestId} from ${payload.requesterName}")
            return false
        }

        val request = IncomingConnectRequest(
            requestId = payload.requestId,
            requesterName = payload.requesterName,
            requesterIp = payload.requesterIp,
            requesterPort = payload.requesterPort,
            timestamp = payload.timestamp
        )

        pendingRequests[payload.requestId] = request
        refreshIncomingList()

        val timeoutJob = scope.launch {
            delay(REQUEST_TIMEOUT_MS)
            handleTimeout(payload.requestId)
        }
        timeoutJobs[payload.requestId] = timeoutJob

        Log.i(TAG, "Received connection request from ${payload.requesterName} (${payload.requesterIp}:${payload.requesterPort}), id=${payload.requestId}")
        FileLogger.i(TAG, "REQUEST_RECEIVED id=${payload.requestId} from=${payload.requesterName} ip=${payload.requesterIp}:${payload.requesterPort}")
        return true
    }

    fun respondToRequest(requestId: String, accepted: Boolean): ConnectResponsePayload? {
        val request = pendingRequests[requestId] ?: run {
            FileLogger.e(TAG, "RESPOND_FAIL: requestId=$requestId not found in pendingRequests (keys=${pendingRequests.keys})")
            return null
        }
        timeoutJobs[requestId]?.cancel()
        timeoutJobs.remove(requestId)

        val oldStatus = request.status
        request.status = if (accepted) {
            IncomingConnectRequest.RequestStatus.ACCEPTED
        } else {
            IncomingConnectRequest.RequestStatus.REJECTED
        }

        Log.i(TAG, "Request $requestId from ${request.requesterName}: $oldStatus -> ${request.status}")
        FileLogger.i(TAG, "REQUEST_RESPONDED id=$requestId from=${request.requesterName} old=$oldStatus new=${request.status} accepted=$accepted")

        refreshIncomingList()

        return ConnectResponsePayload(
            requestId = requestId,
            accepted = accepted,
            responderName = localDeviceName,
            message = if (accepted) "Connection accepted" else "Connection rejected"
        )
    }

    fun getStatus(requestId: String): ConnectResponsePayload? {
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

    fun getPendingRequest(requestId: String): IncomingConnectRequest? {
        return pendingRequests[requestId]
    }

    fun removeRequest(requestId: String) {
        timeoutJobs[requestId]?.cancel()
        timeoutJobs.remove(requestId)
        pendingRequests.remove(requestId)
        refreshIncomingList()
    }

    fun clearAll() {
        timeoutJobs.values.forEach { it.cancel() }
        timeoutJobs.clear()
        pendingRequests.clear()
        refreshIncomingList()
    }

    private fun handleTimeout(requestId: String) {
        val request = pendingRequests[requestId]
        if (request != null && request.status == IncomingConnectRequest.RequestStatus.PENDING) {
            request.status = IncomingConnectRequest.RequestStatus.TIMEOUT
            Log.i(TAG, "Request $requestId from ${request.requesterName}: auto-rejected (timeout)")
            refreshIncomingList()
        }
        timeoutJobs.remove(requestId)
    }

    private fun refreshIncomingList() {
        _incomingRequests.value = pendingRequests.values
            .filter { it.status == IncomingConnectRequest.RequestStatus.PENDING }
            .sortedByDescending { it.timestamp }
            .toList()
    }

    companion object {
        private const val TAG = "ConnectionManager"
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 500L
    }
}
