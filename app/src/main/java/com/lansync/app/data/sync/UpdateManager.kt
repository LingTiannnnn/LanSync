package com.lansync.app.data.sync

import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo

/**
 * 更新推荐与版本差异的**纯逻辑**（ARCH §3.2，冻结规则取自旧 `data.update.UpdateManager` + `AppRepository.calculateSyncDiffs`）。
 *
 * 与旧实现的关键差异：新架构中 `connectedDevices` 的 `appList` 已由 `ConnectionCoordinator` 维护
 * （首拉 + 120s 周期同步 + 刷新通知，SPEC §7.2），故本类**不再自行 fetch**（旧实现内部 `async fetchAppList`），
 * 改为对**已填充 appList 的设备列表**做纯比较——无 `suspend`、无网络/Context 依赖、完全可 JVM 单测。
 *
 * 冻结规则（与旧实现逐条一致，SPEC/ARCH §3.2）：
 * - `findUpdates`：跳过系统应用（remote/local 任一为系统应用不计）；仅 `remote.versionCode > local.versionCode` 才 `canUpdate`。
 * - `deduplicateUpdates`：按 `packageName` 去重取最高 `versionCode`；平级取 `providerDevice.deviceName` 字母序更小者。
 * - `calculateSyncDiffs`：四类型 NEWER_ON_REMOTE / ONLY_ON_REMOTE / SAME_VERSION / ONLY_ON_LOCAL，按 `appName` 降序。
 */
class UpdateManager {

    /** 对比本机列表与各设备（已含 appList），产出可更新推荐（未去重）。 */
    fun findUpdates(localApps: List<AppInfo>, devices: List<DeviceInfo>): List<UpdateInfo> {
        val localMap = localApps.associateBy { it.packageName }
        val result = mutableListOf<UpdateInfo>()
        for (device in devices) {
            for (remoteApp in device.appList) {
                if (remoteApp.isSystemApp) continue
                val localApp = localMap[remoteApp.packageName] ?: continue
                if (localApp.isSystemApp) continue
                if (remoteApp.versionCode > localApp.versionCode) {
                    result.add(UpdateInfo(localApp, remoteApp, device, canUpdate = true))
                }
            }
        }
        return result
    }

    /** 按包名去重「推荐最佳来源」：最高 versionCode，平级取 deviceName 字母序更小者。 */
    fun deduplicateUpdates(updates: List<UpdateInfo>): List<UpdateInfo> {
        val best = mutableMapOf<String, UpdateInfo>()
        for (update in updates) {
            val key = update.remoteApp.packageName
            val existing = best[key]
            if (existing == null) {
                best[key] = update
                continue
            }
            when {
                update.remoteApp.versionCode > existing.remoteApp.versionCode -> best[key] = update
                update.remoteApp.versionCode == existing.remoteApp.versionCode &&
                    update.providerDevice.deviceName < existing.providerDevice.deviceName -> best[key] = update
            }
        }
        return best.values.toList()
    }

    /** 产出四类版本差异（同步 Tab 用），按 appName 降序。 */
    fun calculateSyncDiffs(localApps: List<AppInfo>, devices: List<DeviceInfo>): List<SyncDiff> {
        val diffs = mutableListOf<SyncDiff>()
        val localMap = localApps.associateBy { it.packageName }
        val remotePackages = mutableSetOf<String>()

        for (device in devices.filter { it.appList.isNotEmpty() }) {
            for (remoteApp in device.appList) {
                if (remoteApp.isSystemApp) continue
                remotePackages.add(remoteApp.packageName)

                val localApp = localMap[remoteApp.packageName]
                if (localApp == null) {
                    diffs.add(
                        SyncDiff(remoteApp, null, remoteApp.versionName, device, SyncDiff.DiffType.ONLY_ON_REMOTE)
                    )
                    continue
                }
                if (localApp.isSystemApp) continue

                val diffType = when {
                    remoteApp.versionCode > localApp.versionCode -> SyncDiff.DiffType.NEWER_ON_REMOTE
                    remoteApp.versionCode == localApp.versionCode -> SyncDiff.DiffType.SAME_VERSION
                    else -> continue
                }
                diffs.add(
                    SyncDiff(remoteApp, localApp.versionName, remoteApp.versionName, device, diffType)
                )
            }
        }

        for (localApp in localApps) {
            if (localApp.isSystemApp) continue
            if (localApp.packageName !in remotePackages) {
                diffs.add(
                    SyncDiff(
                        localApp, localApp.versionName, "",
                        DeviceInfo(ipAddress = "", deviceName = "", port = 0),
                        SyncDiff.DiffType.ONLY_ON_LOCAL
                    )
                )
            }
        }

        return diffs.sortedByDescending { it.appInfo.appName.lowercase() }
    }
}
