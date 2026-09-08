package com.lansync.app.data.server

import com.lansync.app.data.model.ConnectRequestPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InMemoryPairingStore 测试（SPEC.md §7.3 接收方侧配对协议状态机）。
 * 用虚拟时间验证 15s 自动超时（SPEC.md §7.2）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryPairingStoreTest {

    private fun payload(id: String) = ConnectRequestPayload(
        requestId = id,
        requesterName = "Requester",
        requesterIp = "192.168.1.50",
        requesterPort = 9090,
        requesterInstanceId = "inst-$id",
        timestamp = System.currentTimeMillis()
    )

    @Test
    fun `receiveRequest returns true and status is pending (null)`() = runTest {
        val store = InMemoryPairingStore("TestDevice", backgroundScope)
        assertTrue(store.receiveRequest(payload("r1")))
        // PENDING → getStatus 返回 null（SPEC §2.6 → 路由回 "pending"）
        assertNull(store.getStatus("r1"))
    }

    @Test
    fun `duplicate request returns false`() = runTest {
        val store = InMemoryPairingStore("TestDevice", backgroundScope)
        assertTrue(store.receiveRequest(payload("r1")))
        assertFalse(store.receiveRequest(payload("r1")))
    }

    @Test
    fun `respond accepted reflected in getStatus`() = runTest {
        val store = InMemoryPairingStore("TestDevice", backgroundScope)
        store.receiveRequest(payload("r1"))
        val resp = store.respondToRequest("r1", accepted = true)
        assertNotNull(resp)
        assertTrue(resp!!.accepted)
        assertEquals("TestDevice", resp.responderName)
        val status = store.getStatus("r1")
        assertNotNull(status)
        assertTrue(status!!.accepted)
        assertEquals("Accepted", status.message)
    }

    @Test
    fun `respond rejected reflected in getStatus`() = runTest {
        val store = InMemoryPairingStore("TestDevice", backgroundScope)
        store.receiveRequest(payload("r1"))
        store.respondToRequest("r1", accepted = false)
        val status = store.getStatus("r1")
        assertNotNull(status)
        assertFalse(status!!.accepted)
        assertEquals("Rejected", status.message)
    }

    @Test
    fun `respond to nonexistent returns null`() = runTest {
        val store = InMemoryPairingStore("TestDevice", backgroundScope)
        assertNull(store.respondToRequest("nope", accepted = true))
    }

    @Test
    fun `timeout after 15s maps to rejected with Timeout message`() = runTest {
        val store = InMemoryPairingStore("TestDevice", backgroundScope)
        store.receiveRequest(payload("r1"))
        advanceTimeBy(InMemoryPairingStore.REQUEST_TIMEOUT_MS + 1)
        runCurrent()
        val status = store.getStatus("r1")
        assertNotNull(status)
        assertFalse(status!!.accepted)
        assertEquals("Timeout", status.message)
        // responderName 未设置（SPEC §2.6 TIMEOUT 分支）
        assertNull(status.responderName)
    }

    @Test
    fun `respond cancels pending timeout`() = runTest {
        val store = InMemoryPairingStore("TestDevice", backgroundScope)
        store.receiveRequest(payload("r1"))
        store.respondToRequest("r1", accepted = true)
        advanceTimeBy(InMemoryPairingStore.REQUEST_TIMEOUT_MS + 1)
        runCurrent()
        // 已接受，不应被超时覆盖
        assertEquals(true, store.getStatus("r1")?.accepted)
    }

    @Test
    fun `removeRequest clears state`() = runTest {
        val store = InMemoryPairingStore("TestDevice", backgroundScope)
        store.receiveRequest(payload("r1"))
        store.removeRequest("r1")
        assertNull(store.getStatus("r1"))
        assertNull(store.respondToRequest("r1", accepted = true))
    }

    // 迁移自旧 ConnectionManagerTest #9 `clearAll clears all pending requests`
    @Test
    fun `clearAll clears all pending requests`() = runTest {
        val store = InMemoryPairingStore("TestDevice", backgroundScope)
        store.receiveRequest(payload("r1"))
        store.receiveRequest(payload("r2"))
        store.clearAll()
        assertNull(store.getStatus("r1"))
        assertNull(store.getStatus("r2"))
        assertNull(store.respondToRequest("r1", accepted = true))
    }
}
