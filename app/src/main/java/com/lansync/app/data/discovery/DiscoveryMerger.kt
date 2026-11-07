package com.lansync.app.data.discovery

import com.lansync.app.data.model.DeviceInfo

/**
 * 跨接口发现聚合器（SPEC §6.5）——多接口 mDNS 观测到「每远端一条」裸发现列表的纯逻辑合并层。
 *
 * 存在理由：协调层 `onRawDevicesUpdated` 的端口迁移分支以 `displayKey` 变化为信号，
 * 同一远端经多个 JmDNS 实例重复上报会触发乒乓迁移——必须在发现层按 `instanceId`
 * 聚合为单条后再发布（`DeviceDiscovery.discoveredDevices` 契约）。
 *
 * - 状态：`instanceId → (接口 tag → 该接口观测到的 DeviceInfo)`；
 * - 每远端发布一条：与「本机候选同网段」的观测优先，否则首个观测（[sameSubnet] 由实现层注入
 *   `NetworkUtils.isSameSubnetWithAnyLocal`）；
 * - 陈旧语义：某接口的观测仅当该接口的保活 `list()` 或关闭不再看到时才移除；
 *   全部接口都看不到才移除该远端；
 * - 纯 JVM，零 android/jmdns 依赖（隔离惯例同 IcuAppNameKeyer），单测直接实例化。
 *
 * 线程安全：JmDNS 监听线程与保活协程两路写入，全部经 [lock] 串行化；snapshot 返回不可变副本。
 */
class DiscoveryMerger(
    private val selfInstanceId: String,
    private val sameSubnet: (remoteIp: String) -> Boolean
) {

    private val lock = Any()

    /** instanceId → (接口 tag → 观测)。LinkedHashMap 保持插入序，snapshot 输出稳定。 */
    private val byInstance = LinkedHashMap<String, LinkedHashMap<String, DeviceInfo>>()

    /** 某接口解析到某远端（新增或覆盖同接口旧观测）。自身 instanceId 拒收（§6.2 自过滤）。 */
    fun onResolved(ifaceTag: String, info: DeviceInfo) {
        if (info.instanceId.isEmpty() || info.instanceId == selfInstanceId) return
        synchronized(lock) {
            byInstance.getOrPut(info.instanceId) { LinkedHashMap() }[ifaceTag] = info
        }
    }

    /** 某接口的某远端已注销（mDNS goodbye）。其他接口仍见则保留。 */
    fun onRemoved(ifaceTag: String, instanceId: String) {
        if (instanceId.isEmpty() || instanceId == selfInstanceId) return
        synchronized(lock) {
            byInstance[instanceId]?.remove(ifaceTag)?.let { pruneIfEmpty(instanceId) }
        }
    }

    /** 某接口一轮 60s 保活结束：该接口本轮未观测到的远端记录移除（§6.5 陈旧清理）。 */
    fun onRefreshCycleEnd(ifaceTag: String, observedInstanceIds: Set<String>) {
        synchronized(lock) {
            for ((id, obs) in byInstance) {
                if (id !in observedInstanceIds && obs.remove(ifaceTag) != null) pruneIfEmpty(id)
            }
        }
    }

    /** 某接口实例整体关闭（接口消失/停机）：其全部观测退场。 */
    fun onIfaceClosed(ifaceTag: String) {
        synchronized(lock) {
            for ((id, obs) in byInstance) {
                if (obs.remove(ifaceTag) != null) pruneIfEmpty(id)
            }
        }
    }

    /** 当前聚合快照：每远端一条，选择规则见类注释。 */
    fun snapshot(): List<DeviceInfo> = synchronized(lock) {
        byInstance.values.mapNotNull(::pickBest)
    }

    /** 清空（start/stop 时）。 */
    fun reset() = synchronized(lock) { byInstance.clear() }

    private fun pruneIfEmpty(instanceId: String) {
        if (byInstance[instanceId]?.isEmpty() == true) byInstance.remove(instanceId)
    }

    private fun pickBest(observations: LinkedHashMap<String, DeviceInfo>): DeviceInfo? {
        if (observations.isEmpty()) return null
        return observations.values.firstOrNull { sameSubnet(it.ipAddress) } ?: observations.values.first()
    }
}
