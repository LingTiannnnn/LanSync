package com.lansync.app.data.connection

import com.lansync.app.data.AppConfig
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.IncomingConnectRequest
import com.lansync.app.data.server.InMemoryPairingStore
import com.lansync.app.data.server.PairingStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DefaultConnectionCoordinator] 状态机测试（TEST-PLAN §5，锁定 SPEC §7.4–7.7）。
 *
 * 策略：状态迁移用**直接投递 [ConnectionEvent]** 驱动（确定性、无周期循环时序脆弱性）；
 * 周期心跳的「20s 触发一次 ping」单独用虚拟时间 `advanceTimeBy` 验证一次。
 * 传输层用**手写 Fake**（[FakeTransport]），不接网络、不接扫描/UI。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultConnectionCoordinatorTest {

    private fun TestScope.newCoordinator(
        transport: FakeTransport = FakeTransport(),
        history: PairingHistoryStore = InMemoryPairingHistoryStore(),
        pairing: PairingStore = InMemoryPairingStore("Local", backgroundScope)
    ): DefaultConnectionCoordinator {
        val c = DefaultConnectionCoordinator(
            config = AppConfig.DEFAULT,
            transport = transport,
            pairingStore = pairing,
            pairingHistory = history,
            localIdentityProvider = { LocalIdentity("Local", "10.0.0.1", 8080, "inst-local") },
            scope = backgroundScope
        )
        c.start()
        runCurrent() // 让 Actor 消费循环进入 channel receive 挂起
        return c
    }

    /** 处理有限事件链（不推进虚拟时间，避免触发周期心跳）。 */
    private fun TestScope.settle() = repeat(5) { runCurrent() }

    private fun connectedDevice(c: ConnectionCoordinator, key: String): DeviceInfo? =
        c.connectedDevices.value.find { it.displayKey == key }

    private fun enrichedDevice(c: ConnectionCoordinator, key: String): DeviceInfo? =
        c.enrichedDevices.value.find { it.displayKey == key }

    // ---------- CS-1 发现 ----------
    @Test
    fun `CS-1 raw devices become DISCOVERED`() = runTest {
        val c = newCoordinator()
        c.submit(ConnectionEvent.RawDevicesUpdated(listOf(device("192.168.1.5", 9000, "Pixel", "inst-r"))))
        settle()
        assertEquals(1, c.enrichedDevices.value.size)
        assertEquals(ConnectionState.DISCOVERED, c.enrichedDevices.value[0].connectionState)
        assertTrue(c.connectedDevices.value.isEmpty())
    }

    // ---------- CS-2 连接成功 ----------
    @Test
    fun `CS-2 connect accepted becomes CONNECTED`() = runTest {
        val t = FakeTransport()
        val c = newCoordinator(transport = t)
        val d = device("192.168.1.5", 9000, "Pixel", "inst-r")
        c.submit(ConnectionEvent.RawDevicesUpdated(listOf(d))); settle() // 先发现（真实流程）
        assertTrue(c.connect(d))
        settle() // 让首拉 appList 的异步事件回填
        assertEquals(1, t.sendCalls)
        val conn = connectedDevice(c, "192.168.1.5:9000")
        assertEquals(ConnectionState.CONNECTED, conn?.connectionState)
        // 首拉 appList 生效
        assertTrue((conn?.appList ?: emptyList()).isNotEmpty())
    }

    // ---------- CS-3 发送失败 ----------
    @Test
    fun `CS-3 sendConnectRequest null becomes ERROR`() = runTest {
        val t = FakeTransport().apply { sendResult = null }
        val c = newCoordinator(transport = t)
        val d = device("192.168.1.5", 9000, "Pixel", "inst-r")
        c.submit(ConnectionEvent.RawDevicesUpdated(listOf(d))); settle()
        assertFalse(c.connect(d))
        val e = enrichedDevice(c, "192.168.1.5:9000")
        assertEquals(ConnectionState.ERROR, e?.connectionState)
        assertEquals("无法发送连接请求，目标设备无响应", e?.connectionError)
    }

    // ---------- CS-4 被拒 ----------
    @Test
    fun `CS-4 poll rejected becomes ERROR`() = runTest {
        val t = FakeTransport().apply { pollOutcome = ConnectOutcome.Rejected("no") }
        val c = newCoordinator(transport = t)
        val d = device("192.168.1.5", 9000, "Pixel", "inst-r")
        c.submit(ConnectionEvent.RawDevicesUpdated(listOf(d))); settle()
        assertFalse(c.connect(d))
        val e = enrichedDevice(c, "192.168.1.5:9000")
        assertEquals(ConnectionState.ERROR, e?.connectionState)
        assertEquals("对方拒绝连接", e?.connectionError)
    }

    // ---------- CS-5 轮询超时 ----------
    @Test
    fun `CS-5 poll timeout becomes ERROR`() = runTest {
        val t = FakeTransport().apply { pollOutcome = ConnectOutcome.Timeout("连接超时（30秒内未收到响应）") }
        val c = newCoordinator(transport = t)
        val d = device("192.168.1.5", 9000, "Pixel", "inst-r")
        c.submit(ConnectionEvent.RawDevicesUpdated(listOf(d))); settle()
        assertFalse(c.connect(d))
        val e = enrichedDevice(c, "192.168.1.5:9000")
        assertEquals(ConnectionState.ERROR, e?.connectionState)
        assertEquals("连接超时（30秒内未收到响应）", e?.connectionError)
    }

    // ---------- CS-7 心跳恢复清计数 ----------
    @Test
    fun `CS-7 heartbeat alive resets fail count`() = runTest {
        val t = FakeTransport()
        val c = newCoordinator(transport = t)
        val key = "192.168.1.5:9000"
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        // 先失败一次进入 RECONNECTING(1/4)
        c.submit(ConnectionEvent.HeartbeatTick(key, alive = false)); settle()
        assertEquals(ConnectionState.RECONNECTING, connectedDevice(c, key)?.connectionState)
        // 恢复
        c.submit(ConnectionEvent.HeartbeatTick(key, alive = true)); settle()
        assertEquals(ConnectionState.CONNECTED, connectedDevice(c, key)?.connectionState)
        assertNull(connectedDevice(c, key)?.connectionError)
        // 再失败一次应重新从 1/4 计数（证明计数已清）
        c.submit(ConnectionEvent.HeartbeatTick(key, alive = false)); settle()
        assertEquals("连接不稳定... (1/4)", connectedDevice(c, key)?.connectionError)
    }

    // ---------- CS-8 首档不稳定 ----------
    @Test
    fun `CS-8 first ping failure is RECONNECTING unstable`() = runTest {
        val t = FakeTransport().apply { pingAlive = false; deviceInfoResult = null }
        val c = newCoordinator(transport = t)
        val key = "192.168.1.5:9000"
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        c.submit(ConnectionEvent.HeartbeatTick(key, alive = false)); settle()
        assertEquals(ConnectionState.RECONNECTING, connectedDevice(c, key)?.connectionState)
        assertEquals("连接不稳定... (1/4)", connectedDevice(c, key)?.connectionError)
    }

    // ---------- CS-9 中档重连 ----------
    @Test
    fun `CS-9 second ping failure is RECONNECTING reconnecting`() = runTest {
        val t = FakeTransport().apply { pingAlive = false; deviceInfoResult = null }
        val c = newCoordinator(transport = t)
        val key = "192.168.1.5:9000"
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        c.submit(ConnectionEvent.HeartbeatTick(key, alive = false)); settle()
        c.submit(ConnectionEvent.HeartbeatTick(key, alive = false)); settle()
        assertEquals(ConnectionState.RECONNECTING, connectedDevice(c, key)?.connectionState)
        assertEquals("正在尝试重新连接... (2/4)", connectedDevice(c, key)?.connectionError)
    }

    // ---------- CS-10 超时离线 ----------
    @Test
    fun `CS-10 fourth ping failure is CONNECTION_TIMEOUT and removed from connected`() = runTest {
        val t = FakeTransport().apply { pingAlive = false; deviceInfoResult = null }
        val c = newCoordinator(transport = t)
        val key = "192.168.1.5:9000"
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        repeat(4) { c.submit(ConnectionEvent.HeartbeatTick(key, alive = false)); settle() }
        assertEquals(ConnectionState.CONNECTION_TIMEOUT, enrichedDevice(c, key)?.connectionState)
        assertEquals("连接超时，设备已离线", enrichedDevice(c, key)?.connectionError)
        assertNull(connectedDevice(c, key)) // 从已连接列表移除
    }

    // ---------- CS-11 快速重连成功 ----------
    @Test
    fun `CS-11 fast reconnect success restores CONNECTED`() = runTest {
        val t = FakeTransport().apply { pingAlive = false; deviceInfoResult = null }
        val c = newCoordinator(transport = t)
        val key = "192.168.1.5:9000"
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        c.submit(ConnectionEvent.HeartbeatTick(key, alive = false)); settle() // RECONNECTING(1/4)
        val cur = connectedDevice(c, key)!!
        c.submit(ConnectionEvent.FastReconnectResult(cur, alive = true)); settle()
        assertEquals(ConnectionState.CONNECTED, connectedDevice(c, key)?.connectionState)
    }

    // ---------- CS-12 本地断开 ----------
    @Test
    fun `CS-12 local disconnect preserves appList and notifies`() = runTest {
        val t = FakeTransport()
        val c = newCoordinator(transport = t)
        val key = "192.168.1.5:9000"
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        settle() // 让首拉 appList 回填
        val conn = connectedDevice(c, key)!!
        assertTrue(conn.appList.isNotEmpty())
        c.disconnect(conn); settle()
        assertEquals(ConnectionState.DISCONNECTED, enrichedDevice(c, key)?.connectionState)
        assertTrue((enrichedDevice(c, key)?.appList ?: emptyList()).isNotEmpty()) // appList 保留
        assertNull(connectedDevice(c, key))
        assertEquals(1, t.disconnectCalls)
    }

    // ---------- CS-13 远端断开 ----------
    @Test
    fun `CS-13 remote disconnect by key preserves appList`() = runTest {
        val c = newCoordinator()
        val key = "192.168.1.5:9000"
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        c.submit(ConnectionEvent.RemoteDisconnect(key)); settle()
        assertEquals(ConnectionState.DISCONNECTED, enrichedDevice(c, key)?.connectionState)
        assertTrue((enrichedDevice(c, key)?.appList ?: emptyList()).isNotEmpty())
        assertNull(connectedDevice(c, key))
    }

    // ---------- CS-14 端口迁移 ----------
    @Test
    fun `CS-14 port migration preserves state and appList`() = runTest {
        val c = newCoordinator()
        c.submit(ConnectionEvent.RawDevicesUpdated(listOf(device("192.168.1.5", 9000, "Pixel", "inst-r")))); settle()
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        // 同 instanceId，端口 9000 -> 9999
        c.submit(ConnectionEvent.RawDevicesUpdated(listOf(device("192.168.1.5", 9999, "Pixel", "inst-r")))); settle()
        val migrated = enrichedDevice(c, "192.168.1.5:9999")
        assertEquals(ConnectionState.CONNECTED, migrated?.connectionState)
        assertTrue((migrated?.appList ?: emptyList()).isNotEmpty())
        assertNull(enrichedDevice(c, "192.168.1.5:9000")) // 旧 key 消失
        assertTrue(connectedDevice(c, "192.168.1.5:9999") != null)
    }

    // ---------- CS-15 陈旧清理 ----------
    @Test
    fun `CS-15 stale cleanup removes DISCOVERED but keeps CONNECTED`() = runTest {
        val c = newCoordinator()
        val disc = device("192.168.1.9", 9000, "Gone", "inst-gone")
        c.submit(ConnectionEvent.RawDevicesUpdated(listOf(disc, device("192.168.1.5", 9000, "Pixel", "inst-r")))); settle()
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        // raw 全部消失
        c.submit(ConnectionEvent.RawDevicesUpdated(emptyList())); settle()
        assertNull(enrichedDevice(c, "192.168.1.9:9000"))       // DISCOVERED 被清理
        assertTrue(connectedDevice(c, "192.168.1.5:9000") != null) // CONNECTED 不被清理
    }

    // ---------- CS-16 去重：已连接不重复发起 ----------
    @Test
    fun `CS-16 connect on already-connected returns true without resending`() = runTest {
        val t = FakeTransport()
        val c = newCoordinator(transport = t)
        val d = device("192.168.1.5", 9000, "Pixel", "inst-r")
        assertTrue(c.connect(d))
        assertEquals(1, t.sendCalls)
        assertTrue(c.connect(d)) // 已连接
        assertEquals(1, t.sendCalls) // 未重复发送
    }

    // ---------- CS-17a 配对历史命中自动接受 ----------
    @Test
    fun `CS-17a incoming from paired history auto-accepts`() = runTest {
        val history = InMemoryPairingHistoryStore().apply { recordPaired("inst-r") }
        val pairing = InMemoryPairingStore("Local", backgroundScope)
        val c = newCoordinator(history = history, pairing = pairing)
        pairing.receiveRequest(ConnectRequestPayload("r1", "Pixel", "192.168.1.5", 9000, "inst-r", 1L))
        c.submit(ConnectionEvent.IncomingRequestReceived(incoming("r1", "192.168.1.5", 9000, "Pixel", "inst-r", 1L))); settle()
        assertTrue(connectedDevice(c, "192.168.1.5:9000") != null)
        assertTrue(c.incomingRequests.value.isEmpty())       // 未弹窗
        assertEquals(true, pairing.getStatus("r1")?.accepted) // 发起方轮询可见 accepted
    }

    // ---------- CS-17b 陌生设备保持 PENDING，不自动接受 ----------
    @Test
    fun `CS-17b incoming stranger stays PENDING and not auto-accepted`() = runTest {
        val pairing = InMemoryPairingStore("Local", backgroundScope)
        val c = newCoordinator(pairing = pairing)
        pairing.receiveRequest(ConnectRequestPayload("r1", "Stranger", "192.168.1.7", 9000, "inst-s", 1L))
        c.submit(ConnectionEvent.IncomingRequestReceived(incoming("r1", "192.168.1.7", 9000, "Stranger", "inst-s", 1L))); settle()
        assertEquals(1, c.incomingRequests.value.size)         // 弹窗
        assertTrue(c.connectedDevices.value.isEmpty())          // 绝不自动放行（修复旧漏洞）
        assertNull(pairing.getStatus("r1"))                     // 仍 PENDING
    }

    // ---------- CS-17c 陌生设备显式拒绝即时 rejected ----------
    @Test
    fun `CS-17c stranger explicit reject is immediate rejected`() = runTest {
        val pairing = InMemoryPairingStore("Local", backgroundScope)
        val c = newCoordinator(pairing = pairing)
        pairing.receiveRequest(ConnectRequestPayload("r1", "Stranger", "192.168.1.7", 9000, "inst-s", 1L))
        c.submit(ConnectionEvent.IncomingRequestReceived(incoming("r1", "192.168.1.7", 9000, "Stranger", "inst-s", 1L))); settle()
        c.handleIncoming("r1", accepted = false); settle()
        val status = pairing.getStatus("r1")
        assertEquals(false, status?.accepted)
        assertEquals("Rejected", status?.message)  // 即时 rejected（非 30s 超时）
        assertTrue(c.incomingRequests.value.isEmpty())
        assertTrue(c.connectedDevices.value.isEmpty())
    }

    // ---------- CS-17d 显式接受记录配对历史 ----------
    @Test
    fun `CS-17d stranger explicit accept connects and records history`() = runTest {
        val history = InMemoryPairingHistoryStore()
        val pairing = InMemoryPairingStore("Local", backgroundScope)
        val c = newCoordinator(history = history, pairing = pairing)
        pairing.receiveRequest(ConnectRequestPayload("r1", "Stranger", "192.168.1.7", 9000, "inst-s", 1L))
        c.submit(ConnectionEvent.IncomingRequestReceived(incoming("r1", "192.168.1.7", 9000, "Stranger", "inst-s", 1L))); settle()
        c.handleIncoming("r1", accepted = true); settle()
        assertTrue(connectedDevice(c, "192.168.1.7:9000") != null)
        assertEquals(true, pairing.getStatus("r1")?.accepted)
        assertTrue(history.isPaired("inst-s"))   // 写入配对历史
    }

    // ---------- 迁移旧 ConnectionManagerTest #10：incomingRequests 仅 PENDING、按 timestamp 降序 ----------
    @Test
    fun `incomingRequests emits pending-only sorted by timestamp desc`() = runTest {
        val c = newCoordinator()
        c.submit(ConnectionEvent.IncomingRequestReceived(incoming("r1", "192.168.1.7", 9000, "A", "inst-a", 100L))); settle()
        c.submit(ConnectionEvent.IncomingRequestReceived(incoming("r2", "192.168.1.8", 9000, "B", "inst-b", 200L))); settle()
        val list = c.incomingRequests.value
        assertEquals(2, list.size)
        assertEquals("r2", list[0].requestId) // timestamp 降序
        assertEquals("r1", list[1].requestId)
    }

    // ---------- 周期心跳：20s 触发一次 ping（虚拟时间） ----------
    @Test
    fun `periodic heartbeat pings after interval`() = runTest {
        val t = FakeTransport()
        val c = newCoordinator(transport = t)
        c.connect(device("192.168.1.5", 9000, "Pixel", "inst-r"))
        assertEquals(0, t.pingCalls)
        advanceTimeBy(AppConfig.DEFAULT.heartbeatPingIntervalMs) // 20s
        runCurrent()
        assertTrue("expected at least one heartbeat ping", t.pingCalls >= 1)
    }
}

// ---------- Fakes & builders（顶层私有，供嵌套 Fake 复用） ----------

private fun testApp(pkg: String, vc: Long) = AppInfo(
    packageName = pkg, appName = pkg, versionName = "1.0", versionCode = vc,
    sourcePaths = emptyList(), md5 = "m", isExtractable = true, fileSize = 1L
)

private fun device(ip: String, port: Int, name: String, instanceId: String) =
    DeviceInfo(ipAddress = ip, deviceName = name, port = port, instanceId = instanceId)

private fun incoming(id: String, ip: String, port: Int, name: String, instanceId: String, ts: Long) =
    IncomingConnectRequest(
        requestId = id, requesterName = name, requesterIp = ip,
        requesterPort = port, requesterInstanceId = instanceId, timestamp = ts
    )

private class FakeTransport : ConnectionTransport {
    var sendResult: String? = "req-1"
    var sendCalls = 0
    var pollOutcome: ConnectOutcome = ConnectOutcome.Accepted("Remote")
    var pingAlive = true
    var pingCalls = 0
    var appListResult: List<AppInfo>? = listOf(testApp("com.a", 1))
    var deviceInfoResult: Map<String, String>? = mapOf("deviceName" to "Remote")
    var disconnectCalls = 0

    override suspend fun sendConnectRequest(
        targetIp: String, targetPort: Int, requesterName: String,
        requesterIp: String, requesterPort: Int, requesterInstanceId: String
    ): String? { sendCalls++; return sendResult }

    override suspend fun pollConnectStatus(
        targetIp: String, targetPort: Int, requestId: String, timeoutMs: Long, pollIntervalMs: Long
    ): ConnectOutcome = pollOutcome

    override suspend fun pingDevice(ipAddress: String, port: Int, timeoutMs: Long): Boolean {
        pingCalls++; return pingAlive
    }

    override suspend fun fetchAppList(ipAddress: String, port: Int): List<AppInfo>? = appListResult

    override suspend fun fetchDeviceInfo(ipAddress: String, port: Int): Map<String, String>? = deviceInfoResult

    override suspend fun sendDisconnectNotification(
        targetIp: String, targetPort: Int, localDisplayKey: String, localIdentityKey: String
    ): Boolean { disconnectCalls++; return true }
}
