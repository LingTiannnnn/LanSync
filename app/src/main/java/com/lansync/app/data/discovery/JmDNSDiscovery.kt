package com.lansync.app.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.lansync.app.data.model.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class JmDNSDiscovery(
    private val context: Context,
    private val serviceType: String = "_lansync._tcp.local.",
    private val serviceName: String = "LanSync"
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val TAG = "JmDNSDiscovery"
    private var jmdns: JmDNS? = null
    private var registeredService: ServiceInfo? = null
    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val discoveredDevices: Flow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    private data class DeviceKey(val ipAddress: String, val port: Int)
    private val deviceMap = mutableMapOf<DeviceKey, DeviceInfo>()
    private var isRunning = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private val instanceId = UUID.randomUUID().toString()

    fun isRunning(): Boolean = isRunning

    suspend fun startDiscovery(port: Int) {
        if (isRunning) {
            Log.w(TAG, "Discovery already running")
            return
        }

        isRunning = true
        deviceMap.clear()
        _discoveredDevices.value = emptyList()

        try {
            acquireMulticastLock()
            val localAddress = getLocalIpAddress()
            val hostname = getDeviceName()

            jmdns = JmDNS.create(localAddress, hostname)

            registerService(port, hostname)
            startListening()

            Log.i(TAG, "JmDNS discovery started on ${localAddress.hostAddress}:$port, instanceId=$instanceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start JmDNS discovery", e)
            stopDiscovery()
            throw e
        }
    }

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("LanSyncMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.i(TAG, "Multicast lock acquired")
    }

    fun stopDiscovery() {
        isRunning = false

        try {
            registeredService?.let { jmdns?.unregisterService(it) }
            jmdns?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error stopping JmDNS", e)
        }

        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null

        deviceMap.clear()
        _discoveredDevices.value = emptyList()
        Log.i(TAG, "JmDNS discovery stopped")
    }

    private fun registerService(port: Int, hostname: String) {
        val serviceInfo = ServiceInfo.create(
            serviceType,
            "${serviceName}_${hostname}_$instanceId",
            port,
            0,
            0,
            mapOf(
                "deviceName" to hostname,
                "instanceId" to instanceId
            )
        )
        jmdns?.registerService(serviceInfo)
        registeredService = serviceInfo
        Log.i(TAG, "Registered service: ${serviceInfo.name} on port $port")
    }

    private fun addOrUpdateDevice(info: ServiceInfo): Boolean {
        val remoteInstanceId = info.getPropertyString("instanceId")

        if (remoteInstanceId.isNullOrEmpty()) {
            Log.d(TAG, "Ignoring service without instanceId: ${info.name} (not a LanSync device)")
            return false
        }

        if (remoteInstanceId == instanceId) {
            Log.d(TAG, "Ignoring local device (same instanceId=$instanceId)")
            return false
        }

        val addresses = info.inetAddresses
        if (addresses.isEmpty()) {
            Log.w(TAG, "No addresses for service: ${info.name}")
            return false
        }

        val ipAddress = addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            ?: addresses[0].hostAddress ?: ""
        val port = info.port
        val key = DeviceKey(ipAddress, port)

        val txtDeviceName = info.getPropertyString("deviceName")
        val deviceName = when {
            !txtDeviceName.isNullOrEmpty() -> txtDeviceName
            else -> extractDeviceNameFromServiceName(info.name)
        }

        val newDevice = DeviceInfo(
            ipAddress = ipAddress,
            deviceName = deviceName,
            port = port,
            appList = emptyList()
        )

        val existing = deviceMap[key]
        if (existing == null || existing.deviceName != deviceName) {
            deviceMap[key] = newDevice
            _discoveredDevices.value = deviceMap.values.toList()
            Log.i(TAG, "Device discovered: $deviceName ($ipAddress:$port)")
            return true
        }
        return false
    }

    private fun removeDeviceByKey(key: DeviceKey) {
        if (deviceMap.remove(key) != null) {
            _discoveredDevices.value = deviceMap.values.toList()
            Log.i(TAG, "Device removed: $key")
        }
    }

    private fun extractDeviceNameFromServiceName(serviceName: String): String {
        val parts = serviceName.split("_")
        return if (parts.size >= 2 && parts[0] == "LanSync") {
            parts[1]
        } else {
            serviceName
        }
    }

    private fun startListening() {
        jmdns?.addServiceListener(serviceType, object : javax.jmdns.ServiceListener {
            override fun serviceAdded(event: javax.jmdns.ServiceEvent?) {
                event?.let {
                    Log.d(TAG, "Service added: ${it.name}, requesting info")
                    jmdns?.requestServiceInfo(it.type, it.name, true, 3000)
                }
            }

            override fun serviceRemoved(event: javax.jmdns.ServiceEvent?) {
                event?.let {
                    val info = jmdns?.getServiceInfo(it.type, it.name) ?: it.info
                    val remoteInstanceId = info.getPropertyString("instanceId")
                    if (remoteInstanceId.isNullOrEmpty() || remoteInstanceId == instanceId) return

                    val addresses = info.inetAddresses
                    val ipAddress = addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                        ?: addresses.firstOrNull()?.hostAddress ?: ""
                    val key = DeviceKey(ipAddress, info.port)
                    removeDeviceByKey(key)
                }
            }

            override fun serviceResolved(event: javax.jmdns.ServiceEvent?) {
                event?.info?.let { info ->
                    addOrUpdateDevice(info)
                }
            }
        })

        scope.launch {
            while (isActive && isRunning) {
                kotlinx.coroutines.delay(15000)
                jmdns?.let { dns ->
                    try {
                        Log.d(TAG, "Refreshing services...")
                        val services = dns.list(serviceType)
                        val currentKeys = mutableSetOf<DeviceKey>()
                        for (si in services) {
                            val remoteInstanceId = si.getPropertyString("instanceId")
                            if (remoteInstanceId.isNullOrEmpty()) continue
                            if (remoteInstanceId == instanceId) continue

                            val addresses = si.inetAddresses
                            if (addresses.isEmpty()) continue
                            val ipAddress = addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                                ?: addresses[0].hostAddress ?: ""
                            val key = DeviceKey(ipAddress, si.port)
                            currentKeys.add(key)
                            addOrUpdateDevice(si)
                        }

                        val staleKeys = deviceMap.keys - currentKeys
                        for (key in staleKeys) {
                            removeDeviceByKey(key)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error refreshing services", e)
                    }
                }
            }
        }
    }

    private fun getLocalIpAddress(): InetAddress {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt == 0) {
            Log.w(TAG, "WiFi ipAddress is 0, trying network interface")
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finding fallback IP", e)
            }
        }
        val ipAddress = Formatter.formatIpAddress(ipInt)
        Log.d(TAG, "Local IP Address: $ipAddress")
        return InetAddress.getByName(ipAddress)
    }

    private fun getDeviceName(): String = try {
        android.os.Build.MODEL
    } catch (e: Exception) {
        "UnknownDevice"
    }
}
