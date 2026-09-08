package com.lansync.app.data.connection

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 配对历史存储（SPEC §7.7 / TT3 干净语义）。
 *
 * 记录「历史配对成功过」的 `instanceId` 集合，作为反向连接**自动接受的唯一判据**——
 * 取代旧实现「当前 discovered/connected 列表内即视为已知」的错误语义（旧缺陷见 SPEC §7.7）。
 * 本机私有持久化，**不上线**（不属于跨设备字节契约）。
 *
 * Phase 2 提供内存实现 [InMemoryPairingHistoryStore] 供虚拟时间测试；
 * Phase 4 接线时替换为 SharedPreferences 持久化实现（键 `paired_instance_ids`）。
 */
interface PairingHistoryStore {
    /** 该 instanceId 是否曾配对成功（空 instanceId 恒 false）。 */
    fun isPaired(instanceId: String): Boolean

    /** 记录一次成功配对（空 instanceId 忽略）。 */
    fun recordPaired(instanceId: String)
}

/** [PairingHistoryStore] 的内存实现（进程级；测试与非持久化场景用）。 */
class InMemoryPairingHistoryStore : PairingHistoryStore {

    private val paired: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override fun isPaired(instanceId: String): Boolean =
        instanceId.isNotEmpty() && paired.contains(instanceId)

    override fun recordPaired(instanceId: String) {
        if (instanceId.isNotEmpty()) paired.add(instanceId)
    }
}
