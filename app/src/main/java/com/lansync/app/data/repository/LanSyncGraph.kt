package com.lansync.app.data.repository

import android.content.Context
import android.os.Build
import com.lansync.app.data.AppConfig
import com.lansync.app.data.NetworkUtils
import com.lansync.app.data.cache.IconCache
import com.lansync.app.data.connection.ConnectionCoordinator
import com.lansync.app.data.connection.ConnectionEvent
import com.lansync.app.data.connection.DefaultConnectionCoordinator
import com.lansync.app.data.connection.LanSyncClientTransport
import com.lansync.app.data.connection.LocalIdentity
import com.lansync.app.data.connection.SharedPrefsPairingHistoryStore
import com.lansync.app.data.discovery.JmDNSDeviceDiscovery
import com.lansync.app.data.installer.ApkInstaller
import com.lansync.app.data.localapps.AppScanner
import com.lansync.app.data.localapps.LocalAppRepository
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.server.InMemoryPairingStore
import com.lansync.app.data.server.KtorLanSyncServer
import com.lansync.app.data.server.LanSyncServer
import com.lansync.app.data.server.NotifyingPairingStore
import com.lansync.app.data.server.ServerApiDelegate
import com.lansync.app.data.sync.UpdateCoordinator
import com.lansync.app.data.sync.UpdateManager
import com.lansync.app.data.transfer.AppPacker
import com.lansync.app.data.transfer.DownloadInstallController
import com.lansync.app.data.transfer.LanSyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 组合根 / 服务定位器（Phase 4，手写 DI；Hilt 留待后续）。构建并持有单例 [LanSyncRepository] 对象图，
 * `MainViewModel` 与 `ForegroundSyncService` 共享同一实例。
 *
 * 打破 `server ↔ coordinator ↔ pairingStore` 构造环：用可空 late-bind 引用（`coordinatorRef`/`serverRef`），
 * 在 lambda 真正执行时（运行期）才解引用，构造期不触发。
 */
object LanSyncGraph {

    @Volatile
    private var instance: LanSyncRepository? = null

    fun get(context: Context): LanSyncRepository =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(appContext: Context): LanSyncRepository {
        val config = AppConfig.DEFAULT
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val localAppRepository = LocalAppRepository(
            scanner = AppScanner(appContext),
            cacheFile = File(appContext.filesDir, "local_apps_cache.json")
        )
        val iconCache = IconCache.getInstance(appContext)
        val packer = AppPacker(File(appContext.cacheDir, "apks"))
        val client = LanSyncClient(File(appContext.cacheDir, "downloads"))
        val transport = LanSyncClientTransport(client)
        val pairingHistory = SharedPrefsPairingHistoryStore(appContext)
        val basePairing = InMemoryPairingStore(Build.MODEL, scope)
        val discovery = JmDNSDeviceDiscovery(appContext, scope)

        // late-bind 打破环
        var coordinatorRef: ConnectionCoordinator? = null
        var serverRef: LanSyncServer? = null

        val notifyingPairing = NotifyingPairingStore(basePairing) { req ->
            coordinatorRef?.submit(ConnectionEvent.IncomingRequestReceived(req))
        }

        val coordinator = DefaultConnectionCoordinator(
            config = config,
            transport = transport,
            pairingStore = notifyingPairing,
            pairingHistory = pairingHistory,
            localIdentityProvider = {
                LocalIdentity(Build.MODEL, NetworkUtils.getLocalIpAddress(), serverRef?.getPort() ?: 0, discovery.getInstanceId())
            },
            scope = scope
        )
        coordinatorRef = coordinator

        val delegate = object : ServerApiDelegate {
            override fun provideAppList(): List<AppInfo> = localAppRepository.localApps.value
            override suspend fun pack(app: AppInfo): File? = packer.packApp(app)
            override fun onDisconnect(key: String) {
                coordinator.submit(ConnectionEvent.RemoteDisconnect(key))
            }
            override fun onRefreshAppList(displayKey: String) {
                val device = coordinator.connectedDevices.value.find { it.displayKey == displayKey } ?: return
                scope.launch {
                    val apps = client.fetchAppList(device.ipAddress, device.port)
                    if (apps != null) coordinator.submit(ConnectionEvent.AppListFetched(displayKey, apps))
                }
            }
            override fun deviceName(): String = Build.MODEL
        }

        val server = KtorLanSyncServer(delegate, notifyingPairing)
        serverRef = server

        val updateCoordinator = UpdateCoordinator(
            updateManager = UpdateManager(),
            localApps = localAppRepository.localApps,
            connectedDevices = coordinator.connectedDevices,
            config = config,
            scope = scope
        )
        val downloadController = DownloadInstallController(client, ApkInstaller(appContext))

        return LanSyncRepository(
            server = server,
            discovery = discovery,
            coordinator = coordinator,
            localAppRepository = localAppRepository,
            updateCoordinator = updateCoordinator,
            downloadController = downloadController,
            client = client,
            packer = packer,
            iconCache = iconCache,
            scope = scope
        )
    }
}
