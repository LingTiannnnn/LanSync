package com.lansync.app.data.server

import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.ConnectResponsePayload
import com.lansync.app.data.model.IncomingConnectRequest

/**
 * [PairingStore] 装饰器（Phase 4 接线）：`receiveRequest` 成功登记后回调 [onNewRequest]，
 * 把新到达的配对请求转达连接层（→ `ConnectionCoordinator.submit(IncomingRequestReceived)`），
 * 驱动 SPEC §7.7 接受策略与 UI 弹窗流。
 *
 * **不改动 Phase 1 的 `InMemoryPairingStore` / `lanSyncModule`**（它们只依赖 [PairingStore] 接口），
 * 仅在组合根用本装饰器包一层，即可把「服务端收到请求」与「连接层处理请求」解耦对接。
 */
class NotifyingPairingStore(
    private val delegate: PairingStore,
    private val onNewRequest: (IncomingConnectRequest) -> Unit
) : PairingStore {

    override fun receiveRequest(payload: ConnectRequestPayload): Boolean {
        val ok = delegate.receiveRequest(payload)
        if (ok) {
            onNewRequest(
                IncomingConnectRequest(
                    requestId = payload.requestId,
                    requesterName = payload.requesterName,
                    requesterIp = payload.requesterIp,
                    requesterPort = payload.requesterPort,
                    requesterInstanceId = payload.requesterInstanceId,
                    timestamp = payload.timestamp
                )
            )
        }
        return ok
    }

    override fun getStatus(requestId: String): ConnectResponsePayload? = delegate.getStatus(requestId)

    override fun respondToRequest(requestId: String, accepted: Boolean): ConnectResponsePayload? =
        delegate.respondToRequest(requestId, accepted)
}
