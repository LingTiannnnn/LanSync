package com.lansync.app.data.connection

import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.IncomingConnectRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {

    private fun createManager() = ConnectionManager("TestDevice")

    private fun createPayload(
        requestId: String = "test-request-001",
        requesterName: String = "RequesterDevice",
        requesterIp: String = "192.168.1.50",
        requesterPort: Int = 9090
    ) = ConnectRequestPayload(
        requestId = requestId,
        requesterName = requesterName,
        requesterIp = requesterIp,
        requesterPort = requesterPort,
        timestamp = System.currentTimeMillis()
    )

    @Test
    fun `receiveRequest adds to pending and returns true`() = runTest {
        val manager = createManager()
        val payload = createPayload()

        val result = manager.receiveRequest(payload)

        assertTrue(result)
        assertEquals(1, manager.incomingRequests.value.size)
    }

    @Test
    fun `duplicate request rejected`() = runTest {
        val manager = createManager()
        val payload = createPayload()

        manager.receiveRequest(payload)
        val result = manager.receiveRequest(payload)

        assertFalse(result)
    }

    @Test
    fun `respondToRequest accepted returns payload`() = runTest {
        val manager = createManager()
        val payload = createPayload()
        manager.receiveRequest(payload)

        val response = manager.respondToRequest(payload.requestId, accepted = true)

        assertNotNull(response)
        assertTrue(response!!.accepted)
        assertEquals("TestDevice", response.responderName)
    }

    @Test
    fun `respondToRequest rejected returns payload`() = runTest {
        val manager = createManager()
        val payload = createPayload()
        manager.receiveRequest(payload)

        val response = manager.respondToRequest(payload.requestId, accepted = false)

        assertNotNull(response)
        assertFalse(response!!.accepted)
    }

    @Test
    fun `respondToRequest non-existent returns null`() = runTest {
        val manager = createManager()

        val response = manager.respondToRequest("nonexistent", accepted = true)

        assertNull(response)
    }

    @Test
    fun `getStatus returns pending for unanswered request`() = runTest {
        val manager = createManager()
        val payload = createPayload()
        manager.receiveRequest(payload)

        val status = manager.getStatus(payload.requestId)

        assertNull(status)
    }

    @Test
    fun `getStatus returns accepted after response`() = runTest {
        val manager = createManager()
        val payload = createPayload()
        manager.receiveRequest(payload)
        manager.respondToRequest(payload.requestId, accepted = true)

        val status = manager.getStatus(payload.requestId)

        assertNotNull(status)
        assertTrue(status!!.accepted)
    }

    @Test
    fun `removeRequest clears pending`() = runTest {
        val manager = createManager()
        val payload = createPayload()
        manager.receiveRequest(payload)

        manager.removeRequest(payload.requestId)

        assertEquals(0, manager.incomingRequests.value.size)
        assertNull(manager.getPendingRequest(payload.requestId))
    }

    @Test
    fun `clearAll clears all pending requests`() = runTest {
        val manager = createManager()
        manager.receiveRequest(createPayload("req-1"))
        manager.receiveRequest(createPayload("req-2"))
        val initialCount = manager.incomingRequests.value.size
        assertTrue(initialCount >= 1)

        manager.clearAll()

        assertEquals(0, manager.incomingRequests.value.size)
    }

    @Test
    fun `incomingRequests flow emits pending-only requests`() = runTest {
        val manager = createManager()
        val payload = createPayload("req-1")
        manager.receiveRequest(payload)

        val requests = manager.incomingRequests.value
        assertEquals(1, requests.size)
        assertEquals(IncomingConnectRequest.RequestStatus.PENDING, requests[0].status)
    }
}
