package com.lansync.app.data

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 本机网络地址工具（SPEC §6.4）。纯 JVM，零 Android 依赖（单测直接加载）。
 *
 * T6 重开后的口径（仅承诺热点场景，其余多网卡尽力而为）：
 * - mDNS 对每个候选网卡各绑一个 JmDNS 实例（见 `JmDNSDeviceDiscovery`）；
 * - 候选 = up + 非环回 + 支持多播 + 有非环回 Inet4，且接口名不命中排除表
 *   （蜂窝 `rmnet*`/`ccmni*` 与 VPN 隧道 `tun*`/`tap*` 不收发多播；未知前缀默认放行）；
 * - requesterIp / displayKey / 对端地址选择均取「与对端同网段优先，否则首个候选」。
 */
object NetworkUtils {

    /** 不参与 mDNS 收发的接口名前缀：蜂窝（rmnet*、ccmni*）与 VPN 隧道（tun*、tap*）。 */
    private val EXCLUDED_IFACE_PREFIXES = listOf("rmnet", "ccmni", "tun", "tap")

    /** 局域网类接口名前缀；仅影响候选排序（"首个候选"语义的确定性），不影响候选资格。 */
    private val PREFERRED_IFACE_PREFIXES = listOf("wlan", "ap", "swlan", "eth", "usb", "br")

    /** 本机一个候选地址：所在接口名、地址、IPv4 子网前缀长度。 */
    data class LocalAddress(val ifaceName: String, val address: InetAddress, val prefixLength: Short) {
        val tag: String get() = "$ifaceName/${address.hostAddress}"
    }

    /** 真实枚举本机全部候选地址。 */
    fun listLocalAddresses(): List<LocalAddress> =
        listLocalAddresses(NetworkInterface.getNetworkInterfaces().toList().filterNotNull())

    /**
     * 纯逻辑核心：对给定接口列表做候选过滤与排序（单测注入 NetworkInterface 用）。
     * 同优先级内保持传入顺序（stable sort）。
     */
    fun listLocalAddresses(interfaces: List<NetworkInterface?>): List<LocalAddress> {
        val candidates = mutableListOf<LocalAddress>()
        for (intf in interfaces) {
            if (intf == null || !isCandidateInterface(intf)) continue
            for (ia in intf.interfaceAddresses) {
                val addr = ia.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    candidates.add(LocalAddress(intf.name, addr, ia.networkPrefixLength))
                }
            }
        }
        return candidates.sortedBy { ifacePriority(it.ifaceName) }
    }

    private fun isCandidateInterface(intf: NetworkInterface): Boolean = try {
        intf.isUp && !intf.isLoopback && intf.supportsMulticast() &&
            EXCLUDED_IFACE_PREFIXES.none { intf.name.startsWith(it) }
    } catch (e: Exception) {
        false
    }

    private fun ifacePriority(name: String): Int =
        if (PREFERRED_IFACE_PREFIXES.any { name.startsWith(it) }) 0 else 1

    /** 首个候选地址的字符串；无候选返回空串（调用方按旧契约兜底）。 */
    fun getLocalIpAddress(): String = listLocalAddresses().firstOrNull()?.address?.hostAddress ?: ""

    /**
     * 为目标远端 [remoteIp] 选本机地址：同网段优先，无匹配回退首个候选（SPEC §6.4）。
     * 无任何候选返回 null。
     */
    fun pickLocalAddressFor(remoteIp: String?): InetAddress? = pickAddressFor(remoteIp, listLocalAddresses())?.address

    /** 纯逻辑核心：[pickLocalAddressFor] 的注入版（单测用）。 */
    fun pickAddressFor(remoteIp: String?, candidates: List<LocalAddress>): LocalAddress? {
        if (candidates.isEmpty()) return null
        val remote = remoteIp?.let { parseInet4(it) }
        if (remote != null) {
            candidates.firstOrNull { sameSubnet(remote, it) }?.let { return it }
        }
        return candidates.first()
    }

    /** 远端多地址里挑可达的：同网段优先，否则首个 Inet4（SPEC §6.2 对端地址口径）。仅接受非环回 Inet4。 */
    fun pickReachableRemote(remotes: List<InetAddress>): InetAddress? =
        pickReachableRemote(remotes, listLocalAddresses())

    /** 纯逻辑核心：[pickReachableRemote] 的注入版（单测用）。 */
    fun pickReachableRemote(remotes: List<InetAddress>, locals: List<LocalAddress>): InetAddress? {
        val v4s = remotes.filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }
        if (v4s.isEmpty()) return null
        return v4s.firstOrNull { r -> locals.any { sameSubnet(r, it) } } ?: v4s.first()
    }

    /** [remoteIp] 是否与本机任一候选同 IPv4 子网（DiscoveryMerger 注入用）。 */
    fun isSameSubnetWithAnyLocal(remoteIp: String): Boolean =
        isSameSubnetWithAnyLocal(remoteIp, listLocalAddresses())

    /** 纯逻辑核心：[isSameSubnetWithAnyLocal] 的注入版（单测用）。 */
    fun isSameSubnetWithAnyLocal(remoteIp: String, locals: List<LocalAddress>): Boolean {
        val remote = parseInet4(remoteIp) ?: return false
        return locals.any { sameSubnet(remote, it) }
    }

    /** 纯逻辑核心：远端 Inet4 与本机候选是否同子网（按前缀长度；前缀 0 或 /32 视为未知，不匹配）。 */
    fun sameSubnet(remote: Inet4Address, local: LocalAddress): Boolean {
        val prefix = local.prefixLength.toInt()
        if (prefix <= 0 || prefix >= 32) return false
        val r = remote.address
        val l = local.address.address
        for (i in 0 until prefix / 8) {
            if (r[i] != l[i]) return false
        }
        val restBits = prefix % 8
        if (restBits != 0) {
            val mask = (0xFF shl (8 - restBits)) and 0xFF
            if ((r[prefix / 8].toInt() and mask) != (l[prefix / 8].toInt() and mask)) return false
        }
        return true
    }

    private fun parseInet4(text: String): Inet4Address? =
        runCatching { InetAddress.getByName(text) }.getOrNull() as? Inet4Address
}
