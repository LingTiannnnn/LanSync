package com.lansync.app.data.sync

import com.lansync.app.data.AppNameKeyer
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo
import com.lansync.app.data.sortedByAppName

/**
 * 更新推荐与版本差异的**纯逻辑**（ARCH §3.2，冻结规则取自旧 `data.update.UpdateManager` + `AppRepository.calculateSyncDiffs`）。
 *
 * 与旧实现的关键差异：新架构中 `connectedDevices` 的 `appList` 已由 `ConnectionCoordinator` 维护
 * （首拉 + 120s 周期同步 + 刷新通知，SPEC §7.2），故本类**不再自行 fetch**（旧实现内部 `async fetchAppList`），
 * 改为对**已填充 appList 的设备列表**做纯比较——无 `suspend`、无网络/Context 依赖、完全可 JVM 单测。
 *
 * 规则（`findUpdates`/`deduplicateUpdates` 与旧实现逐条一致，SPEC/ARCH §3.2；差异语义经用户裁决收窄，见 ARCH §3.2；
 * 两个输出列表的展示顺序统一为 appName 拼音 A–Z 升序，排序口径见 [sortedByAppName]）：
 * - `findUpdates`：跳过系统应用（remote/local 任一为系统应用不计）；仅 `remote.versionCode > local.versionCode` 才 `canUpdate`。
 * - `deduplicateUpdates`：按 `packageName` 去重取最高 `versionCode`；平级取 `providerDevice.deviceName` 字母序更小者；输出按 appName 升序。
 * - `calculateSyncDiffs`：只收「远程落后」（双端都有且本地更高）；远程更高归可更新、同版本与单侧存在不构成差异，输出按 appName 升序。
 *
 * 排序键经 [appNameKey] 注入：生产组合根必须传 `DefaultAppNameKeyer`（ICU 拼音转写），JVM 单测传映射/恒等 Fake。
 */
class UpdateManager(private val appNameKey: AppNameKeyer) {

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
        return best.values.sortedByAppName(appNameKey, { it.remoteApp.appName }, { it.remoteApp.packageName })
    }

    /**
     * 产出「远程版本落后」列表（同步 Tab 版本差异档）：双端都安装、跳过系统应用、
     * 且本地 `versionCode` 更高——远程更高的情形归 [findUpdates]（可更新），不在此重复。
     * 按 appName 降序。
     */
    fun calculateSyncDiffs(localApps: List<AppInfo>, devices: List<DeviceInfo>): List<SyncDiff> {
        val diffs = mutableListOf<SyncDiff>()
        val localMap = localApps.associateBy { it.packageName }

        for (device in devices.filter { it.appList.isNotEmpty() }) {
            for (remoteApp in device.appList) {
                if (remoteApp.isSystemApp) continue
                val localApp = localMap[remoteApp.packageName] ?: continue
                if (localApp.isSystemApp) continue
                if (localApp.versionCode > remoteApp.versionCode) {
                    diffs.add(SyncDiff(remoteApp, localApp.versionName, remoteApp.versionName, device))
                }
            }
        }

        return diffs.sortedByAppName(appNameKey, { it.appInfo.appName }, { it.appInfo.packageName })
    }
}
