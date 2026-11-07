package com.lansync.app.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.lansync.app.data.FileLogger
import com.lansync.app.data.NetworkUtils
import com.lansync.app.data.model.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.UUID
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * [DeviceDiscovery] 的 JmDNS 实现（SPEC §6）——T6 重开后的多接口形态。
 *
 * 线上契约严格不变（§6.1–6.3）：服务类型 `_lansync._tcp.local.`、实例名 `LanSync_{hostname}_{instanceId}`、
 * TXT 双键 `deviceName`/`instanceId`、60s 保活。实现层（§6.4）：
 * - 对每个候选网卡（[NetworkUtils.listLocalAddresses]，蜂窝/VPN 前缀已排除）各建一个 `JmDNS` 实例，
 *   注册同一逻辑服务；各实例在各自接口收发多播——热点主人因此在 AP 网段可被客户端发现；
 * - 跨接口观测经 [DiscoveryMerger] 按 instanceId 聚合为单条后发布（§6.5），协调层无感；
 * - 60s 保活循环每轮先重枚举候选接口（热点中途开关/网络切换自动跟随：新增建实例、消失关实例）；
 * - 逐实例失败隔离：单接口 JmDNS 异常只关该实例，不拖垮整体；零候选不报错（等循环跟进）。
 *
 * 说明：本类依赖 Android/JmDNS/多播，**非 JVM 单测目标**（纯逻辑已抽至 DiscoveryMerger；
 * 编译校验 + 真机互操作验收，见 TEST-PLAN §6）。
 */
class JmDNSDeviceDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val serviceType: String = "_lansync._tcp.local.",
    private val serviceName: String = "LanSync"
) : DeviceDiscovery {

    /** 一个已绑定的 JmDNS 实例：接口 tag（`ifaceName/addr`）→ 实例与其注册的 ServiceInfo。 */
    private data class BoundInstance(val tag: String, val jmdns: JmDNS, val serviceInfo: ServiceInfo)

    private val instanceId = loadOrGenerateInstanceId()
    private val merger = DiscoveryMerger(instanceId, ::isRemoteOnLocalSubnet)

    @Volatile
    private var instances: Map<String, BoundInstance> = emptyMap()
    private var refreshJob: Job? = null
    private var isRunningFlag = false
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile
    private var servicePort = 0

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    override val discoveredDevices: Flow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    override fun getInstanceId(): String = instanceId
    override fun isRunning(): Boolean = isRunningFlag

    private fun loadOrGenerateInstanceId(): String {
        val prefs = context.getSharedPreferences("lansync_device", Context.MODE_PRIVATE)
        prefs.getString("device_instance_id", null)?.let { return it }
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("device_instance_id", newId).apply()
        FileLogger.i(TAG, "Generated new persistent instanceId: ${newId.take(8)}...")
        return newId
    }

    override suspend fun startDiscovery(port: Int) {
        if (isRunningFlag) {
            FileLogger.w(TAG, "Discovery already running")
            return
        }
        isRunningFlag = true
        servicePort = port
        merger.reset()
        _discoveredDevices.value = emptyList()
        try {
            acquireMulticastLock()
            ensureInstances()   // 立即绑定首批候选；零候选不报错，保活循环会跟进
            refreshJob = scope.launch {
                while (isActive && isRunningFlag) {
                    refreshCycle()
                    delay(REFRESH_INTERVAL_MS)   // SPEC §6.5
                }
            }
            FileLogger.i(TAG, "Discovery started on ${instances.keys} port=$port, instanceId=${instanceId.take(8)}...")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start JmDNS discovery", e)
            stopDiscovery()
            throw e
        }
    }

    override fun stopDiscovery() {
        isRunningFlag = false
        refreshJob?.cancel()
        refreshJob = null
        instances.keys.toList().forEach { closeInstance(it, "stopped") }
        merger.reset()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        _discoveredDevices.value = emptyList()
        FileLogger.i(TAG, "Discovery stopped")
    }

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("LanSyncMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    /** 重建实例集与当前候选接口的差集：关闭消失接口的实例，为新增候选建实例（逐实例失败隔离）。 */
    private fun ensureInstances() {
        val wanted = try {
            NetworkUtils.listLocalAddresses().associateBy({ it.tag }, { it })
        } catch (e: Exception) {
            FileLogger.w(TAG, "interface enumeration failed", e)
            return
        }
        for (tag in instances.keys - wanted.keys) closeInstance(tag, "interface gone")
        val hostname = getDeviceName()
        for ((tag, la) in wanted) {
            if (instances.containsKey(tag)) continue
            var jmdns: JmDNS? = null
            try {
                jmdns = JmDNS.create(la.address, hostname)
                val info = newServiceInfo(hostname)
                jmdns.registerService(info)
                jmdns.addServiceListener(serviceType, listenerFor(tag))
                instances += tag to BoundInstance(tag, jmdns, info)
                FileLogger.i(TAG, "JmDNS bound: $tag (port=$servicePort)")
            } catch (e: Exception) {
                FileLogger.w(TAG, "JmDNS bind failed on $tag", e)
                jmdns?.let { runCatching { it.close() } }
            }
        }
    }

    private fun closeInstance(tag: String, reason: String) {
        val inst = instances[tag] ?: return
        instances = instances - tag
        try {
            inst.jmdns.unregisterService(inst.serviceInfo)
        } catch (e: Exception) {
            FileLogger.w(TAG, "unregister failed on $tag", e)
        }
        try {
            inst.jmdns.close()
        } catch (e: Exception) {
            FileLogger.w(TAG, "close failed on $tag", e)
        }
        merger.onIfaceClosed(tag)
        publish()
        FileLogger.i(TAG, "JmDNS closed: $tag ($reason)")
    }

    /** 一轮保活：跟随接口变化 + 逐实例 `list()` 全量对账 + 陈旧清理 + 发布（§6.5）。 */
    private fun refreshCycle() {
        ensureInstances()
        for (inst in instances.values.toList()) {
            try {
                val services = inst.jmdns.list(serviceType)
                val observed = HashSet<String>()
                for (si in services) {
                    val remoteInstanceId = si.getPropertyString("instanceId")
                    if (remoteInstanceId.isNullOrEmpty() || remoteInstanceId == instanceId) continue
                    val info = buildDeviceInfo(si, remoteInstanceId) ?: continue
                    observed.add(remoteInstanceId)
                    merger.onResolved(inst.tag, info)
                }
                merger.onRefreshCycleEnd(inst.tag, observed)
            } catch (e: Exception) {
                FileLogger.w(TAG, "refresh failed on ${inst.tag}", e)
            }
        }
        publish()
    }

    private fun newServiceInfo(hostname: String): ServiceInfo = ServiceInfo.create(
        serviceType,
        "${serviceName}_${hostname}_$instanceId",
        servicePort, 0, 0,
        mapOf("deviceName" to hostname, "instanceId" to instanceId)
    )

    private fun listenerFor(tag: String): ServiceListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            instances[tag]?.jmdns?.requestServiceInfo(event.type, event.name, true, 3000)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            val dns = instances[tag]?.jmdns ?: return
            val info = try {
                dns.getServiceInfo(event.type, event.name)
            } catch (e: Exception) {
                FileLogger.w(TAG, "resolve on remove failed on $tag", e)
                null
            } ?: return
            val remoteInstanceId = info.getPropertyString("instanceId")
            if (remoteInstanceId.isNullOrEmpty() || remoteInstanceId == instanceId) return
            merger.onRemoved(tag, remoteInstanceId)
            publish()
        }

        override fun serviceResolved(event: ServiceEvent) {
            val remoteInstanceId = event.info.getPropertyString("instanceId")
            if (remoteInstanceId.isNullOrEmpty() || remoteInstanceId == instanceId) return
            val deviceInfo = buildDeviceInfo(event.info, remoteInstanceId) ?: return
            merger.onResolved(tag, deviceInfo)
            publish()
        }
    }

    /** 由 ServiceInfo 构建观测记录；地址按 §6.2 口径选「同网段优先，否则首个 Inet4」。 */
    private fun buildDeviceInfo(si: ServiceInfo, remoteInstanceId: String): DeviceInfo? {
        val address = NetworkUtils.pickReachableRemote(si.inetAddresses.toList()) ?: return null
        val txtDeviceName = si.getPropertyString("deviceName")
        val deviceName = if (!txtDeviceName.isNullOrEmpty()) txtDeviceName
        else extractDeviceNameFromServiceName(si.name)
        return DeviceInfo(
            ipAddress = address.hostAddress ?: return null,
            deviceName = deviceName, port = si.port,
            instanceId = remoteInstanceId, appList = emptyList()
        )
    }

    private fun isRemoteOnLocalSubnet(remoteIp: String): Boolean =
        NetworkUtils.isSameSubnetWithAnyLocal(remoteIp)

    private fun publish() {
        val snapshot = merger.snapshot()
        if (snapshot != _discoveredDevices.value) _discoveredDevices.value = snapshot
    }

    private fun extractDeviceNameFromServiceName(name: String): String {
        val parts = name.split("_")
        return if (parts.size >= 2 && parts[0] == "LanSync") parts[1] else name
    }

    private fun getDeviceName(): String = try {
        android.os.Build.MODEL
    } catch (e: Exception) {
        "UnknownDevice"
    }

    private companion object {
        const val TAG = "JmDNSDeviceDiscovery"
        const val REFRESH_INTERVAL_MS = 60_000L   // SPEC §6.5
    }
}
