package com.lansync.app.data.server

import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.ConnectResponsePayload
import java.io.File

/**
 * HTTP API 契约接口（ARCHITECTURE.md §3.5）。
 *
 * 把「路由处理」与「服务器生命周期 / 传输实现」解耦：[KtorLanSyncServer] 是唯一实现，
 * 测试可用 ktor-server-test-host 直接 install [lanSyncModule] 而无需真实 Netty 端口。
 * 取代旧 KtorServer 的 5 个 setXxx 可变回调（消除 ARCHITECTURE P8）。
 */
interface LanSyncServer {
    /** 启动服务，返回实际端口（port=0 时由 OS 动态分配，SPEC.md §5.2）。 */
    suspend fun start(port: Int = 0): Int

    fun stop()

    fun isRunning(): Boolean

    fun getPort(): Int
}

/**
 * 路由处理器契约（由协调层实现，注入 [KtorLanSyncServer] / [lanSyncModule]）。
 *
 * Phase 1 骨架：appList provider 用 fake 数据，不接 AppScanner、不接 UI（见测试中的 Fake 实现）。
 */
interface ServerApiDelegate {
    /** GET /api/applist 的数据源（SPEC.md §3 路由 #2）。 */
    fun provideAppList(): List<AppInfo>

    /** 下载路由的打包产物提供者；返回 null 表示打包失败（SPEC.md §3 路由 #7/#8 → 500）。 */
    suspend fun pack(app: AppInfo): File?

    /** POST /api/disconnect 回调，key 为 identityKey 优先、否则 displayKey（SPEC.md §2.7）。 */
    fun onDisconnect(key: String)

    /** POST /api/refresh-applist 回调，参数为对端 displayKey（SPEC.md §2.8）。 */
    fun onRefreshAppList(displayKey: String)

    /** GET /api/deviceinfo 的设备名（SPEC.md §2.9）。 */
    fun deviceName(): String
}

/**
 * 配对协议状态存储契约（SPEC.md §7.3 接收方侧 RequestStatus 状态机的传输层投影）。
 *
 * 仅承载「配对请求-轮询-响应」的协议状态，供 connect/request、connect/status、connect/response
 * 三条路由使用。UI 弹窗流（incomingRequests）与反向连接接受策略（TT3 干净语义、配对历史）
 * 属于连接层职责（ConnectionCoordinator，Phase 4），不在此接口内。
 *
 * 现有 [com.lansync.app.data.connection.ConnectionManager] 的方法签名与此接口一致，
 * 后续阶段可由连接层适配/替换。
 */
interface PairingStore {
    /** 登记配对请求；重复 requestId 返回 false（→ 路由 409，SPEC.md §3.1 #4）。 */
    fun receiveRequest(payload: ConnectRequestPayload): Boolean

    /** 查询请求状态：PENDING/未知 → null（→ 路由回 status="pending"，SPEC.md §2.6）。 */
    fun getStatus(requestId: String): ConnectResponsePayload?

    /** 响应请求；不存在或已处理 → null（→ 路由 404，SPEC.md §3.1 #6）。 */
    fun respondToRequest(requestId: String, accepted: Boolean): ConnectResponsePayload?
}
