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
import java.io.IOException
import java.util.UUID
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * [DeviceDiscovery] 的 JmDNS 实现（SPEC §6）——旧 `JmDNSDiscovery` 的「实现重写」。
 *
 * 与旧实现的唯一结构差异（ARCH §6 铁律 1）：**CoroutineScope 由构造注入**，不再类内自建
 * `CoroutineScope(SupervisorJob()+Dispatchers.IO)`，以便测试替换调度器 / 统一生命周期治理。
 * 线上行为严格复现 SPEC §6：
 * - 服务类型 `_lansync._tcp.local.`、实例名 `LanSync_{hostname}_{instanceId}`、weight/priority=0（§6.1）
 * - TXT 恰两键 `deviceName`/`instanceId`，经 JmDNS `getPropertyString` 读写（§6.2，无手写字节解析）
 * - instanceId 持久化 `SharedPreferences("lansync_device")/"device_instance_id"`（§6.3）
 * - 绑定 IP = `NetworkUtils.getLocalIpAddressViaWifi`（§6.4）
 * - 60s 全量 `list()` 保活 + 陈旧清理；ServiceListener added/removed/resolved；MulticastLock（§6.5）
 * - 自过滤：无 instanceId 的非 LanSync 服务、自身 instanceId 均忽略（§6.2）
 *
 * 说明：本类依赖 Android/JmDNS/多播，**非 JVM 单测目标**（编译校验 + 真机互操作验收，见 TEST-PLAN §6）。
 */
class JmDNSDeviceDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val serviceType: String = "_lansync._tcp.local.",
    private val serviceName: String = "LanSync"
) : DeviceDiscovery {

    private var jmdns: JmDNS? = null
    private var registeredService: ServiceInfo? = null
    private var refreshJob: Job? = null
    private var isRunningFlag = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    override val discoveredDevices: Flow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    private data class DeviceKey(val ipAddress: String, val port: Int)
    private val deviceMap = mutableMapOf<DeviceKey, DeviceInfo>()

    private val instanceId = loadOrGenerateInstanceId()
    override fun getInstanceId(): String = instanceId

    private fun loadOrGenerateInstanceId(): String {
        val prefs = context.getSharedPreferences("lansync_device", Context.MODE_PRIVATE)
        prefs.getString("device_instance_id", null)?.let { return it }
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("device_instance_id", newId).apply()
        FileLogger.i(TAG, "Generated new persistent instanceId: ${newId.take(8)}...")
        return newId
    }

    override fun isRunning(): Boolean = isRunningFlag

    override suspend fun startDiscovery(port: Int) {
        if (isRunningFlag) {
            FileLogger.w(TAG, "Discovery already running")
            return
        }
        isRunningFlag = true
        deviceMap.clear()
        _discoveredDevices.value = emptyList()
        try {
            acquireMulticastLock()
            val localAddress = NetworkUtils.getLocalIpAddressViaWifi(context)
            val hostname = getDeviceName()
            jmdns = JmDNS.create(localAddress, hostname)
            registerService(port, hostname)
            startListening()
            FileLogger.i(TAG, "Discovery started on ${localAddress.hostAddress}:$port, instanceId=${instanceId.take(8)}...")
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
        try {
            registeredService?.let { jmdns?.unregisterService(it) }
            jmdns?.close()
        } catch (e: IOException) {
            FileLogger.e(TAG, "Error stopping JmDNS", e)
        }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        deviceMap.clear()
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

    private fun registerService(port: Int, hostname: String) {
        val serviceInfo = ServiceInfo.create(
            serviceType,
            "${serviceName}_${hostname}_$instanceId",
            port, 0, 0,
            mapOf("deviceName" to hostname, "instanceId" to instanceId)
        )
        jmdns?.registerService(serviceInfo)
        registeredService = serviceInfo
        FileLogger.i(TAG, "Registered service: ${serviceInfo.name} on port $port")
    }

    private fun addOrUpdateDevice(info: ServiceInfo): Boolean {
        val remoteInstanceId = info.getPropertyString("instanceId")
        if (remoteInstanceId.isNullOrEmpty()) return false          // 非 LanSync 设备
        if (remoteInstanceId == instanceId) return false             // 自身

        val addresses = info.inetAddresses
        if (addresses.isEmpty()) return false
        val ipAddress = addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            ?: addresses[0].hostAddress ?: ""
        val port = info.port
        val key = DeviceKey(ipAddress, port)

        val txtDeviceName = info.getPropertyString("deviceName")
        val deviceName = if (!txtDeviceName.isNullOrEmpty()) txtDeviceName
        else extractDeviceNameFromServiceName(info.name)

        val newDevice = DeviceInfo(
            ipAddress = ipAddress, deviceName = deviceName, port = port,
            instanceId = remoteInstanceId, appList = emptyList()
        )
        val existing = deviceMap[key]
        if (existing == null || existing.deviceName != deviceName) {
            deviceMap[key] = newDevice
            _discoveredDevices.value = deviceMap.values.toList()
            FileLogger.i(TAG, "Device discovered: $deviceName ($ipAddress:$port)")
            return true
        }
        return false
    }

    private fun removeDeviceByKey(key: DeviceKey) {
        if (deviceMap.remove(key) != null) {
            _discoveredDevices.value = deviceMap.values.toList()
            FileLogger.i(TAG, "Device removed: $key")
        }
    }

    private fun extractDeviceNameFromServiceName(name: String): String {
        val parts = name.split("_")
        return if (parts.size >= 2 && parts[0] == "LanSync") parts[1] else name
    }

    private fun startListening() {
        jmdns?.addServiceListener(serviceType, object : javax.jmdns.ServiceListener {
            override fun serviceAdded(event: javax.jmdns.ServiceEvent?) {
                event?.let { jmdns?.requestServiceInfo(it.type, it.name, true, 3000) }
            }

            override fun serviceRemoved(event: javax.jmdns.ServiceEvent?) {
                event?.let {
                    val info = jmdns?.getServiceInfo(it.type, it.name) ?: it.info
                    val remoteInstanceId = info.getPropertyString("instanceId")
                    if (remoteInstanceId.isNullOrEmpty() || remoteInstanceId == instanceId) return
                    val addresses = info.inetAddresses
                    val ipAddress = addresses.firstOrNull { a -> a is java.net.Inet4Address }?.hostAddress
                        ?: addresses.firstOrNull()?.hostAddress ?: ""
                    removeDeviceByKey(DeviceKey(ipAddress, info.port))
                }
            }

            override fun serviceResolved(event: javax.jmdns.ServiceEvent?) {
                event?.info?.let { info -> addOrUpdateDevice(info) }
            }
        })

        refreshJob = scope.launch {
            while (isActive && isRunningFlag) {
                delay(REFRESH_INTERVAL_MS)
                jmdns?.let { dns ->
                    try {
                        val services = dns.list(serviceType)
                        val currentKeys = mutableSetOf<DeviceKey>()
                        for (si in services) {
                            val remoteInstanceId = si.getPropertyString("instanceId")
                            if (remoteInstanceId.isNullOrEmpty() || remoteInstanceId == instanceId) continue
                            val addresses = si.inetAddresses
                            if (addresses.isEmpty()) continue
                            val ipAddress = addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                                ?: addresses[0].hostAddress ?: ""
                            currentKeys.add(DeviceKey(ipAddress, si.port))
                            addOrUpdateDevice(si)
                        }
                        (deviceMap.keys - currentKeys).forEach { removeDeviceByKey(it) }
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Error refreshing services", e)
                    }
                }
            }
        }
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
