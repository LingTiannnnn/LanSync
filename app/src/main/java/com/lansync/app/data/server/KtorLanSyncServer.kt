package com.lansync.app.data.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine

/**
 * [LanSyncServer] 的 Ktor/Netty 实现（ARCHITECTURE.md §3.5）。
 *
 * 生命周期与端口分配复现 SPEC.md §5.2：`port=0` 由 OS 动态分配，
 * 实际端口经 `resolvedConnectors().first().port` 读取。路由逻辑全部在 [lanSyncModule]，
 * 本类只负责引擎启停，故路由可被 ktor-server-test-host 独立测试。
 *
 * 依赖通过构造注入（[ServerApiDelegate] + [PairingStore]），取代旧 KtorServer 的 setXxx 回调。
 */
class KtorLanSyncServer(
    private val delegate: ServerApiDelegate,
    private val pairingStore: PairingStore
) : LanSyncServer {

    private var engine: NettyApplicationEngine? = null
    private var actualPort: Int = 0

    override suspend fun start(port: Int): Int {
        engine?.let { return actualPort }
        val newEngine = embeddedServer(Netty, port = port) {
            lanSyncModule(delegate, pairingStore)
        }
        newEngine.start(wait = false)
        actualPort = newEngine.resolvedConnectors().first().port
        engine = newEngine
        return actualPort
    }

    override fun stop() {
        engine?.stop(1000, 5000)
        engine = null
        actualPort = 0
    }

    override fun isRunning(): Boolean = engine != null

    override fun getPort(): Int = actualPort
}
