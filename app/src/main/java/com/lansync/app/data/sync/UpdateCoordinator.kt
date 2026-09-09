package com.lansync.app.data.sync

import com.lansync.app.data.AppConfig
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 更新推荐协调器（ARCH §3.2）——旧 `AppRepository` 中 `autoCheckUpdatesWhenDataReady`/`recalculateUpdates`/
 * `calculateSyncDiffs` 编排职责的重写。
 *
 * `combine(localApps, connectedDevices)` → 节流（`AppConfig.updateRecalculationThrottleMs`）→
 * [UpdateManager.findUpdates]/[UpdateManager.deduplicateUpdates] → `availableUpdates`；
 * [UpdateManager.calculateSyncDiffs] → `syncDiffs`。
 *
 * 并发（ARCH §6）：节流时间戳 `lastRecalcMs` 为**单一 collect 协程内的局部状态**，消除旧实现
 * 跨协程读写 `lastUpdateRecalculationMs`（非 volatile Long）的竞态。只读消费上游 StateFlow，产出不可变列表。
 */
class UpdateCoordinator(
    private val updateManager: UpdateManager,
    private val localApps: StateFlow<List<AppInfo>>,
    private val connectedDevices: StateFlow<List<DeviceInfo>>,
    private val config: AppConfig,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    private val _availableUpdates = MutableStateFlow<List<UpdateInfo>>(emptyList())
    val availableUpdates: StateFlow<List<UpdateInfo>> = _availableUpdates.asStateFlow()

    private val _syncDiffs = MutableStateFlow<List<SyncDiff>>(emptyList())
    val syncDiffs: StateFlow<List<SyncDiff>> = _syncDiffs.asStateFlow()

    private var job: Job? = null
    private var lastRecalcMs = 0L   // 仅在 collect 协程内读写

    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(localApps, connectedDevices) { l, d -> l to d }.collect { (l, devices) ->
                if (l.isNotEmpty() && devices.any { it.appList.isNotEmpty() }) {
                    val now = nowMillis()
                    if (now - lastRecalcMs < config.updateRecalculationThrottleMs) return@collect
                    lastRecalcMs = now
                    recalculate(l, devices)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 立即重算（绕过节流）；供刷新/测试触发。 */
    fun recalculateNow() {
        val l = localApps.value
        val d = connectedDevices.value
        lastRecalcMs = nowMillis()
        recalculate(l, d)
    }

    private fun recalculate(localApps: List<AppInfo>, devices: List<DeviceInfo>) {
        val withApps = devices.filter { it.appList.isNotEmpty() }
        _availableUpdates.value = if (localApps.isEmpty() || withApps.isEmpty()) {
            emptyList()
        } else {
            updateManager.deduplicateUpdates(updateManager.findUpdates(localApps, withApps))
        }
        _syncDiffs.value = updateManager.calculateSyncDiffs(localApps, devices)
    }
}
