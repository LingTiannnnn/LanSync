package com.lansync.app.data.connection

import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.IncomingConnectRequest
import kotlinx.coroutines.CompletableDeferred

/**
 * 连接协调事件（ARCH §3.1 / §6）——[ConnectionCoordinator] 的**唯一状态写入路径**。
 *
 * 所有事件经单一 [kotlinx.coroutines.channels.Channel] 投递、由单一 Actor 协程串行消费，
 * 杜绝旧实现「`ConcurrentHashMap` 裸读写后再 `StateFlow.value=`」的交错竞态（ARCH §6 铁律）。
 * 网络 I/O 在子协程执行，其**结果**再以事件回填，状态变更永远发生在 Actor 内。
 */
sealed interface ConnectionEvent {

    /** mDNS 裸发现列表更新（SPEC §7.5 端口迁移 / §7.6 陈旧清理的入口）。 */
    data class RawDevicesUpdated(val devices: List<DeviceInfo>) : ConnectionEvent

    /** 用户发起连接；[deferred] 回填 `connect()` 的布尔结果。 */
    data class ConnectRequested(
        val device: DeviceInfo,
        val deferred: CompletableDeferred<Boolean>
    ) : ConnectionEvent

    /** 连接网络流程（send + poll）完成，携带 [ConnectAttemptResult]。 */
    data class ConnectCompleted(
        val device: DeviceInfo,
        val result: ConnectAttemptResult,
        val deferred: CompletableDeferred<Boolean>
    ) : ConnectionEvent

    /** 心跳 ping 结果（SPEC §7.4 三档阈值 1/4 推进状态）。 */
    data class HeartbeatTick(val displayKey: String, val alive: Boolean) : ConnectionEvent

    /** 快速重连探测结果（SPEC §7.4 RECONNECTING→CONNECTED）。 */
    data class FastReconnectResult(val device: DeviceInfo, val alive: Boolean) : ConnectionEvent

    /** 应用列表拉取结果（首拉/周期同步/远端刷新，SPEC §7.2）。 */
    data class AppListFetched(val displayKey: String, val apps: List<AppInfo>) : ConnectionEvent

    /**
     * 收到对端配对请求（服务端 `receiveRequest` 登记后投递；SPEC §7.7 接受策略入口）。
     * 命中配对历史→自动接受；陌生设备→保持 PENDING 并暴露给 UI 弹窗。
     */
    data class IncomingRequestReceived(val request: IncomingConnectRequest) : ConnectionEvent

    /** 对端配对请求的裁决（用户点击或自动接受），SPEC §7.7。 */
    data class IncomingDecision(val requestId: String, val accepted: Boolean) : ConnectionEvent

    /** 远端主动断开（SPEC §7.4，按 displayKey/identityKey 匹配）。 */
    data class RemoteDisconnect(val key: String) : ConnectionEvent

    /** 本地主动断开（SPEC §7.4，appList 保留）。 */
    data class LocalDisconnect(val device: DeviceInfo) : ConnectionEvent
}

/**
 * `connect()` 网络流程结果（含传输级失败），一一映射到 SPEC §7.4 的各 ERROR/CONNECTED 分支。
 * 与协议级 [ConnectOutcome] 区分：本类型额外覆盖「发送失败」「异常」两条非轮询终态。
 */
sealed interface ConnectAttemptResult {
    data class Accepted(val responderName: String) : ConnectAttemptResult
    data class Rejected(val message: String) : ConnectAttemptResult
    data class TimedOut(val message: String) : ConnectAttemptResult
    data class SendFailed(val message: String) : ConnectAttemptResult
    data class Failed(val message: String) : ConnectAttemptResult
}
