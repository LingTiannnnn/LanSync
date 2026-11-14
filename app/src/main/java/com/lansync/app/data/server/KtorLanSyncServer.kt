package com.lansync.app.data.server

import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer

/**
 * [LanSyncServer] 的 Ktor/CIO 实现（ARCHITECTURE.md §3.5）。
 *
 * 生命周期与端口分配复现 SPEC.md §5.2：`port=0` 由 OS 动态分配，
 * 实际端口经 `resolvedConnectors().first().port` 读取。路由逻辑全部在 [lanSyncModule]，
 * 本类只负责引擎启停，故路由可被 ktor-server-test-host 独立测试。
 *
 * 引擎用 CIO（纯 Kotlin/coroutines）而非 Netty：Netty 在 R8 下依赖反射
 * （ReflectiveChannelFactory / TypeParameterMatcher），曾多次在真机以闪退或连接 RST 失败；
 * CIO 无该反射面。依赖通过构造注入（[ServerApiDelegate] + [PairingStore]）。
 */
class KtorLanSyncServer(
    private val delegate: ServerApiDelegate,
    private val pairingStore: PairingStore
) : LanSyncServer {

    private var engine: CIOApplicationEngine? = null
    private var actualPort: Int = 0

    override suspend fun start(port: Int): Int {
        engine?.let { return actualPort }
        val newEngine = embeddedServer(CIO, port = port) {
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
