package com.lansync.app.data

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface

/**
 * NetworkUtils 候选网卡过滤与同网段选址（SPEC §6.4，T6 重开后口径）。
 *
 * 纯逻辑核心经 `List<NetworkInterface>` 注入测（MockK 打桩）；真实枚举依赖运行环境，不入测。
 */
class NetworkUtilsTest {

    // ---- 桩件 ----

    private fun iface(
        ifName: String,
        up: Boolean = true,
        loopback: Boolean = false,
        multicast: Boolean = true,
        addrs: List<Pair<String, Short>> = emptyList()
    ): NetworkInterface = mockk {
        every { name } returns ifName
        every { isUp } returns up
        every { isLoopback } returns loopback
        every { supportsMulticast() } returns multicast
        every { interfaceAddresses } returns addrs.map { (ip, prefix) ->
            mockk<InterfaceAddress> {
                every { address } returns InetAddress.getByName(ip)
                every { networkPrefixLength } returns prefix
            }
        }
    }

    private fun addr(text: String): InetAddress = InetAddress.getByName(text)

    // ---- 候选过滤（SPEC §6.4 排除表 fail-open）----

    @Test
    fun `cellular and vpn interfaces excluded by name prefix`() {
        val ifs = listOf(
            iface("rmnet_data0", addrs = listOf("10.11.12.13" to 30.toShort())),
            iface("ccmni0", addrs = listOf("10.0.0.2" to 30.toShort())),
            iface("tun0", addrs = listOf("10.66.0.1" to 24.toShort())),
            iface("tap0", addrs = listOf("10.77.0.1" to 24.toShort()))
        )
        assertTrue(NetworkUtils.listLocalAddresses(ifs).isEmpty())
    }

    @Test
    fun `down loopback and non multicast interfaces excluded`() {
        val ifs = listOf(
            iface("wlan0", up = false, addrs = listOf("192.168.1.2" to 24.toShort())),
            iface("lo", loopback = true, addrs = listOf("127.0.0.1" to 8.toShort())),
            iface("eth9", multicast = false, addrs = listOf("192.168.1.3" to 24.toShort()))
        )
        assertTrue(NetworkUtils.listLocalAddresses(ifs).isEmpty())
    }

    @Test
    fun `unknown interface names fail open as candidates`() {
        val ifs = listOf(
            iface("somevendor0", addrs = listOf("192.168.50.2" to 24.toShort())),
            iface("br-lan", addrs = listOf("192.168.50.1" to 24.toShort()))
        )
        assertEquals(2, NetworkUtils.listLocalAddresses(ifs).size)
    }

    @Test
    fun `only non loopback inet4 collected with prefix preserved`() {
        val ifs = listOf(
            iface(
                "wlan0",
                addrs = listOf(
                    "fe80::1" to 64.toShort(),       // IPv6 不收
                    "127.0.0.1" to 8.toShort(),      // 环回 Inet4 不收
                    "192.168.43.1" to 24.toShort()
                )
            )
        )
        val result = NetworkUtils.listLocalAddresses(ifs)
        assertEquals(1, result.size)
        assertEquals("wlan0", result[0].ifaceName)
        assertEquals("192.168.43.1", result[0].address.hostAddress)
        assertEquals(24.toShort(), result[0].prefixLength)
    }

    @Test
    fun `lan class interfaces sorted ahead and stable within class`() {
        val ifs = listOf(
            iface("dummy0", addrs = listOf("172.16.0.1" to 16.toShort())),
            iface("wlan0", addrs = listOf("192.168.1.2" to 24.toShort())),
            iface("ap0", addrs = listOf("192.168.43.1" to 24.toShort()))
        )
        val names = NetworkUtils.listLocalAddresses(ifs).map { it.ifaceName }
        assertEquals(listOf("wlan0", "ap0", "dummy0"), names)
    }

    // ---- 同网段判定 ----

    private val local24 = NetworkUtils.LocalAddress("ap0", addr("192.168.43.1"), 24)

    @Test
    fun `same subnet judged by prefix length`() {
        assertTrue(NetworkUtils.sameSubnet(addr("192.168.43.77") as java.net.Inet4Address, local24))
        assertFalse(NetworkUtils.sameSubnet(addr("192.168.44.77") as java.net.Inet4Address, local24))
        val local16 = NetworkUtils.LocalAddress("wlan0", addr("192.168.0.1"), 16)
        assertTrue(NetworkUtils.sameSubnet(addr("192.168.99.9") as java.net.Inet4Address, local16))
        assertFalse(NetworkUtils.sameSubnet(addr("192.169.0.9") as java.net.Inet4Address, local16))
    }

    @Test
    fun `unknown prefix 0 and host route 32 never match`() {
        val p0 = NetworkUtils.LocalAddress("wlan0", addr("192.168.43.1"), 0)
        val p32 = NetworkUtils.LocalAddress("wlan0", addr("192.168.43.1"), 32)
        assertFalse(NetworkUtils.sameSubnet(addr("192.168.43.1") as java.net.Inet4Address, p0))
        assertFalse(NetworkUtils.sameSubnet(addr("192.168.43.1") as java.net.Inet4Address, p32))
    }

    // ---- 选址（requesterIp / displayKey，SPEC §6.4）----

    @Test
    fun `pick address for prefers same subnet then falls back to first`() {
        val candidates = listOf(
            NetworkUtils.LocalAddress("wlan0", addr("10.0.0.5"), 8),
            NetworkUtils.LocalAddress("ap0", addr("192.168.43.1"), 24)
        )
        assertEquals("192.168.43.1", NetworkUtils.pickAddressFor("192.168.43.77", candidates)?.address?.hostAddress)
        assertEquals("10.0.0.5", NetworkUtils.pickAddressFor("10.9.9.9", candidates)?.address?.hostAddress)
        // 非 IP 字符串 / null：回退首个候选
        assertEquals("10.0.0.5", NetworkUtils.pickAddressFor("not-an-ip", candidates)?.address?.hostAddress)
        assertEquals("10.0.0.5", NetworkUtils.pickAddressFor(null, candidates)?.address?.hostAddress)
        assertNull(NetworkUtils.pickAddressFor("192.168.43.77", emptyList()))
    }

    // ---- 对端地址选择（§6.2：同网段优先，否则首个 Inet4）----

    @Test
    fun `pick reachable remote filters to non loopback inet4 and prefers same subnet`() {
        val locals = listOf(NetworkUtils.LocalAddress("ap0", addr("192.168.43.1"), 24))
        val remotes = listOf(addr("fe80::1"), addr("127.0.0.1"), addr("192.168.43.9"), addr("10.0.0.9"))
        assertEquals("192.168.43.9", NetworkUtils.pickReachableRemote(remotes, locals)?.hostAddress)
    }

    @Test
    fun `pick reachable remote falls back to first inet4 when nothing on local subnet`() {
        val locals = listOf(NetworkUtils.LocalAddress("ap0", addr("192.168.43.1"), 24))
        assertEquals("10.0.0.9", NetworkUtils.pickReachableRemote(listOf(addr("10.0.0.9")), locals)?.hostAddress)
        assertNull(NetworkUtils.pickReachableRemote(listOf(addr("fe80::1"), addr("127.0.0.1")), locals))
    }

    @Test
    fun `is same subnet with any local parses remote and matches prefix`() {
        val locals = listOf(NetworkUtils.LocalAddress("ap0", addr("192.168.43.1"), 24))
        assertTrue(NetworkUtils.isSameSubnetWithAnyLocal("192.168.43.77", locals))
        assertFalse(NetworkUtils.isSameSubnetWithAnyLocal("192.168.44.77", locals))
        assertFalse(NetworkUtils.isSameSubnetWithAnyLocal("not-an-ip", locals))
    }
}
