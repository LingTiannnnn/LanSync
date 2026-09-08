package com.lansync.app.data.connection

import com.lansync.app.data.model.AppInfo

/**
 * 连接层对「传输能力」的抽象（Phase 2）。
 *
 * [DefaultConnectionCoordinator] 依赖本接口而非具体 `LanSyncClient`，以便用**手写 Fake** 做虚拟时间测试
 * （不引入 MockK 对 final 类的字节码代理，也**不改动 Phase 1 已绿的 `LanSyncClient`**）。
 * 方法签名与 `LanSyncClient` 对齐，Phase 4 接线时由 `LanSyncClient` 适配实现本接口即可。
 *
 * 契约依据 SPEC.md：§2.3 配对请求、§7.2 轮询/心跳超时、§3 #2/#3 查询、§2.7 断开通知。
 */
interface ConnectionTransport {

    /** 发起配对请求（SPEC §2.3）；成功返回生成的 requestId，非 2xx / 无响应 / 异常返回 null。 */
    suspend fun sendConnectRequest(
        targetIp: String,
        targetPort: Int,
        requesterName: String,
        requesterIp: String,
        requesterPort: Int,
        requesterInstanceId: String
    ): String?

    /**
     * 轮询配对状态（SPEC §7.2）。`timeoutMs` / `pollIntervalMs` 由调用方从 [com.lansync.app.data.AppConfig]
     * 显式传入（禁止在传输层硬编码），返回终态 [ConnectOutcome]。
     */
    suspend fun pollConnectStatus(
        targetIp: String,
        targetPort: Int,
        requestId: String,
        timeoutMs: Long,
        pollIntervalMs: Long
    ): ConnectOutcome

    /** 心跳探活（SPEC §7.2 `pingTimeoutMs`）；成功 true，超时/异常 false。 */
    suspend fun pingDevice(ipAddress: String, port: Int, timeoutMs: Long): Boolean

    /** 拉取对端应用列表（SPEC §3 路由 #2）；失败/空返回 null。 */
    suspend fun fetchAppList(ipAddress: String, port: Int): List<AppInfo>?

    /** 探活/快速重连用（SPEC §3 路由 #3）；不可达返回 null。仅判空，不读死字段 version（SPEC §2.9）。 */
    suspend fun fetchDeviceInfo(ipAddress: String, port: Int): Map<String, String>?

    /** 主动断开通知（SPEC §2.7）。 */
    suspend fun sendDisconnectNotification(
        targetIp: String,
        targetPort: Int,
        localDisplayKey: String,
        localIdentityKey: String
    ): Boolean
}

/**
 * 配对轮询终态（对应 SPEC §2.6 状态词表：pending 由轮询内部继续，不出现在此）。
 * 独立于 `LanSyncClient.ConnectResult` 定义，使连接层不反向依赖 transfer 层。
 */
sealed interface ConnectOutcome {
    data class Accepted(val responderName: String) : ConnectOutcome
    data class Rejected(val message: String) : ConnectOutcome
    data class Timeout(val message: String) : ConnectOutcome
}

/**
 * 本机身份（发起配对/断开通知时携带）。服务端口在启动后动态确定（SPEC §5.2），
 * 故由 coordinator 通过 `() -> LocalIdentity` provider 惰性获取。
 */
data class LocalIdentity(
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val instanceId: String
) {
    /** `"$ipAddress:$port"`（SPEC §2.7 displayKey 语义）。 */
    val displayKey: String get() = "$ipAddress:$port"
}
