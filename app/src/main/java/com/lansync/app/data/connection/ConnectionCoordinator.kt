package com.lansync.app.data.connection

import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.IncomingConnectRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * 连接协调器（ARCH §3.1）——承接旧 `AppRepository` 的连接编排职责。
 *
 * 职责：三设备列表（raw→enriched / connected）维护、`connect`/断开编排、心跳循环、
 * 端口迁移（SPEC §7.5）、陈旧清理（SPEC §7.6）、反向连接接受策略（SPEC §7.7 TT3）。
 *
 * 并发模型（ARCH §6 强制）：所有状态变更经 [submit] 收敛到**单一 Actor 协程**串行处理，
 * 禁止 `ConcurrentHashMap` 与 `StateFlow` 交错的裸读写。
 *
 * 边界：不做 appList 内容比较（交 UpdateCoordinator）、不做下载（交 DownloadInstallController）、
 * 不接扫描/UI。心跳阈值/超时参数全部取自 [com.lansync.app.data.AppConfig]（SPEC §7.2），禁止硬编码。
 */
interface ConnectionCoordinator {

    /** 全部发现态设备（含 DISCOVERED/CONNECTING/CONNECTED/RECONNECTING/… ）。 */
    val enrichedDevices: StateFlow<List<DeviceInfo>>

    /** 仅已连接设备（CONNECTED/RECONNECTING）。 */
    val connectedDevices: StateFlow<List<DeviceInfo>>

    /** 待用户确认的 PENDING 配对请求（按 timestamp 降序，驱动 UI 弹窗 + 倒计时）。 */
    val incomingRequests: StateFlow<List<IncomingConnectRequest>>

    /** 启动 Actor 消费循环（幂等）。 */
    fun start()

    /** 停止 Actor 与所有每设备心跳/同步任务。 */
    fun stop()

    /** 非阻塞投递事件（唯一状态写入路径）。 */
    fun submit(event: ConnectionEvent)

    /** 发起连接；挂起直至状态机给出结果（SPEC §7.4）。已连接直接 true，重复发起 false。 */
    suspend fun connect(device: DeviceInfo): Boolean

    /** 本地主动断开（异步投递 [ConnectionEvent.LocalDisconnect]）。 */
    fun disconnect(device: DeviceInfo)

    /** 对端配对请求裁决（SPEC §7.7）：接受→建连+记录历史；拒绝→即时 rejected。 */
    fun handleIncoming(requestId: String, accepted: Boolean)

    /** 忽略/滑走某待确认配对请求（仅移出 incomingRequests，不响应发起方；其 15s 后自然超时）。 */
    fun dismissIncoming(requestId: String)
}
