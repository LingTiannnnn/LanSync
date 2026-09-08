package com.lansync.app.data.discovery

import com.lansync.app.data.model.DeviceInfo
import kotlinx.coroutines.flow.Flow

/**
 * 设备发现抽象（SPEC §6）。
 *
 * 把「mDNS/多播实现细节」与「连接层消费」解耦：[com.lansync.app.data.connection.ConnectionCoordinator]
 * 只消费 [discoveredDevices] 裸发现流（Phase 4 接线时由协调层 collect 后投递
 * `ConnectionEvent.RawDevicesUpdated`），不依赖具体 JmDNS/Android。
 *
 * 契约（SPEC §6）：服务类型 `_lansync._tcp.local.`、实例名 `LanSync_{host}_{instanceId}`、
 * TXT 双键 `deviceName`/`instanceId`、60s 保活、按 `ip:port` 去重、自过滤。
 */
interface DeviceDiscovery {

    /** 裸发现列表（`appList=[]`、`connectionState=DISCOVERED`，按 `ip:port` 去重）。 */
    val discoveredDevices: Flow<List<DeviceInfo>>

    /** 本机持久化 instanceId（身份稳定标识，SPEC §6.3）。 */
    fun getInstanceId(): String

    /** 是否正在发现。 */
    fun isRunning(): Boolean

    /** 启动发现并注册本机服务（[port] 为服务端动态分配端口，SPEC §5.2）。 */
    suspend fun startDiscovery(port: Int)

    /** 停止发现、注销服务、释放多播锁。 */
    fun stopDiscovery()
}
