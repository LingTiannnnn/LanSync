package com.lansync.app.data.discovery

import com.lansync.app.data.model.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DiscoveryMerger 跨接口聚合语义（SPEC §6.5）：每远端 instanceId 一条、同网段观测优先、
 * 部分接口消失保留 / 全部消失清理、自身过滤、接口关闭退场。
 */
class DiscoveryMergerTest {

    private val subnet43: (String) -> Boolean = { it.startsWith("192.168.43.") }

    private fun merger(
        sameSubnet: (String) -> Boolean = subnet43,
        selfInstanceId: String = "self"
    ) = DiscoveryMerger(selfInstanceId, sameSubnet)

    private fun info(ip: String, instanceId: String, name: String = "B") =
        DeviceInfo(ipAddress = ip, deviceName = name, port = 8080, instanceId = instanceId)

    @Test
    fun `aggregates same instance across ifaces into one entry preferring local subnet`() {
        val m = merger()
        m.onResolved("wlan0/192.168.1.5", info("192.168.1.2", "id-b"))
        m.onResolved("ap0/192.168.43.1", info("192.168.43.2", "id-b"))
        val snap = m.snapshot()
        assertEquals(1, snap.size)
        // 同网段观测优先（AP 网段可达），而非首个观测
        assertEquals("192.168.43.2", snap[0].ipAddress)
        assertEquals("id-b", snap[0].instanceId)
    }

    @Test
    fun `falls back to first observation when none on local subnet`() {
        val m = merger(sameSubnet = { false })
        m.onResolved("wlan0/192.168.1.5", info("192.168.1.2", "id-b"))
        m.onResolved("ap0/192.168.43.1", info("192.168.43.2", "id-b"))
        assertEquals("192.168.1.2", m.snapshot()[0].ipAddress)
    }

    @Test
    fun `keeps entry while any iface still observes it`() {
        val m = merger()
        m.onResolved("wlan0/192.168.1.5", info("192.168.1.2", "id-b"))
        m.onResolved("ap0/192.168.43.1", info("192.168.43.2", "id-b"))
        m.onRefreshCycleEnd("wlan0/192.168.1.5", emptySet())
        assertEquals(1, m.snapshot().size)
    }

    @Test
    fun `removes entry only when no iface observes it anymore`() {
        val m = merger()
        m.onResolved("wlan0/192.168.1.5", info("192.168.1.2", "id-b"))
        m.onResolved("ap0/192.168.43.1", info("192.168.43.2", "id-b"))
        m.onRefreshCycleEnd("wlan0/192.168.1.5", emptySet())
        m.onRefreshCycleEnd("ap0/192.168.43.1", emptySet())
        assertEquals(0, m.snapshot().size)
    }

    @Test
    fun `per iface removal keeps entry until last iface drops it`() {
        val m = merger()
        m.onResolved("wlan0/192.168.1.5", info("192.168.1.2", "id-b"))
        m.onResolved("ap0/192.168.43.1", info("192.168.43.2", "id-b"))
        m.onRemoved("ap0/192.168.43.1", "id-b")
        assertEquals(listOf("192.168.1.2"), m.snapshot().map { it.ipAddress })
        m.onRemoved("wlan0/192.168.1.5", "id-b")
        assertEquals(0, m.snapshot().size)
    }

    @Test
    fun `filters own instanceId and blank instanceId`() {
        val m = merger(selfInstanceId = "self")
        m.onResolved("wlan0/192.168.1.5", info("192.168.1.2", "self"))
        m.onResolved("wlan0/192.168.1.5", DeviceInfo("192.168.1.9", "X", 8080, ""))
        assertEquals(0, m.snapshot().size)
    }

    @Test
    fun `closing an iface drops only its observations`() {
        val m = merger()
        m.onResolved("wlan0/192.168.1.5", info("192.168.1.2", "id-b"))
        m.onResolved("ap0/192.168.43.1", info("192.168.43.2", "id-b"))
        m.onIfaceClosed("ap0/192.168.43.1")
        assertEquals(listOf("192.168.1.2"), m.snapshot().map { it.ipAddress })
        m.onIfaceClosed("wlan0/192.168.1.5")
        assertEquals(0, m.snapshot().size)
    }

    @Test
    fun `distinct instances stay separate and snapshot order is insertion stable`() {
        val m = merger()
        m.onResolved("ap0/192.168.43.1", info("192.168.43.2", "id-b", name = "B"))
        m.onResolved("ap0/192.168.43.1", info("192.168.43.3", "id-c", name = "C"))
        m.onResolved("wlan0/192.168.1.5", info("192.168.1.2", "id-b"))
        val snap = m.snapshot()
        assertEquals(listOf("id-b", "id-c"), snap.map { it.instanceId })
        assertEquals(listOf("192.168.43.2", "192.168.43.3"), snap.map { it.ipAddress })
    }

    @Test
    fun `reset clears everything`() {
        val m = merger()
        m.onResolved("ap0/192.168.43.1", info("192.168.43.2", "id-b"))
        m.reset()
        assertEquals(0, m.snapshot().size)
    }
}
