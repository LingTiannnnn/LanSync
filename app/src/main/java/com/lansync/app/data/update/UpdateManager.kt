package com.lansync.app.data.update

import com.lansync.app.data.client.AppListClient
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class UpdateManager(private val appListClient: AppListClient) {

    suspend fun findUpdates(
        localApps: List<AppInfo>,
        discoveredDevices: List<DeviceInfo>
    ): List<UpdateInfo> {
        return withContext(Dispatchers.IO) {
            val updateInfos = mutableListOf<UpdateInfo>()
            
            val localAppMap = localApps.associateBy { it.packageName }
            
            val tasks = discoveredDevices.map { device ->
                async {
                    fetchAndCompare(device, localAppMap)
                }
            }
            
            tasks.awaitAll().forEach { updates ->
                updateInfos.addAll(updates)
            }
            
            updateInfos
        }
    }

    private suspend fun fetchAndCompare(
        device: DeviceInfo,
        localAppMap: Map<String, AppInfo>
    ): List<UpdateInfo> {
        val updates = mutableListOf<UpdateInfo>()
        
        val remoteApps = appListClient.fetchAppList(device.ipAddress, device.port)
        if (remoteApps.isNullOrEmpty()) {
            return updates
        }
        
        val deviceWithApps = device.copy(appList = remoteApps)
        
        remoteApps.forEach { remoteApp ->
            if (remoteApp.isSystemApp) return@forEach

            val localApp = localAppMap[remoteApp.packageName] ?: return@forEach

            if (localApp.isSystemApp) return@forEach

            if (remoteApp.versionCode > localApp.versionCode) {
                updates.add(
                    UpdateInfo(
                        localApp = localApp,
                        remoteApp = remoteApp,
                        providerDevice = deviceWithApps,
                        canUpdate = true
                    )
                )
            }
        }
        
        return updates
    }

    suspend fun refreshDeviceAppList(device: DeviceInfo): DeviceInfo? {
        return withContext(Dispatchers.IO) {
            val appList = appListClient.fetchAppList(device.ipAddress, device.port)
            appList?.let {
                device.copy(appList = it)
            }
        }
    }

    fun deduplicateUpdates(updates: List<UpdateInfo>): List<UpdateInfo> {
        val bestUpdates = mutableMapOf<String, UpdateInfo>()
        
        updates.forEach { update ->
            val key = update.remoteApp.packageName
            
            if (!bestUpdates.containsKey(key)) {
                bestUpdates[key] = update
            } else {
                val existing = bestUpdates[key]!!
                if (update.remoteApp.versionCode > existing.remoteApp.versionCode) {
                    bestUpdates[key] = update
                } else if (update.remoteApp.versionCode == existing.remoteApp.versionCode) {
                    if (update.providerDevice.deviceName < existing.providerDevice.deviceName) {
                        bestUpdates[key] = update
                    }
                }
            }
        }
        
        return bestUpdates.values.toList()
    }

    fun groupByDevice(updates: List<UpdateInfo>): Map<String, List<UpdateInfo>> {
        return updates.groupBy { it.providerDevice.displayKey }
    }
}
