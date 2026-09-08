# LanSync 目标架构（ARCHITECTURE）

> **文档状态**：Phase 0 交付物 · 目标架构设计（不含生产代码，仅契约与设计）
> **重构范式**：**契约不变，实现重写**。线上协议以 `docs/SPEC.md` 为唯一冻结基线；本文件定义承载该契约的**目标内部架构**。
> **核心目标**：消灭 1181 行上帝类 `AppRepository`（12 个 `MutableStateFlow` + 4 张 `ConcurrentHashMap` + 共享 scope 混用），按领域拆为可独立测试的协作者，状态变更单点收敛，引入 DI 与前台服务，统一错误协议。

---

## 1. 现状痛点（代码实证）

| # | 痛点 | 实证 |
|---|---|---|
| P1 | **上帝类**：连接编排/心跳/双列表/端口迁移/更新推荐/下载/安装/缓存/扫描全在 `AppRepository`（1181 行） | `AppRepository.kt` 全文 |
| P2 | **12 路 MutableStateFlow 手动 copy**：`_localApps/_rawDiscoveredDevices/_enrichedDevices/_connectedDevices/_availableUpdates/_syncDiffs/_serverPort/_isRunning/_isScanningApps/_downloadProgress/_installStatus/_incomingRequests` | `AppRepository.kt` L53–L64 |
| P3 | **并发原语混用**：`heartbeatJobs/syncJobs/heartbeatFailCounts/connectingDevices` 四张 `ConcurrentHashMap` + `lastUpdateRecalculationMs`（非 volatile Long）与 StateFlow 交错读写，存在竞态窗口 | `AppRepository.kt` L82–L86 |
| P4 | **分层倒置**：data 层 `import com.lansync.app.ui.components.preloadIcon` | `AppRepository.kt` L22 |
| P5 | **无前台服务**：Ktor + JmDNS + 心跳全活在 ViewModel/Activity 生命周期内 | `AppRepository.start()`；Manifest 无 `<service>` |
| P6 | **错误泄露**：Ktor 5+ 处 `respondText("...${e.message}")` | `KtorServer.kt` L113/172/206/248 |
| P7 | **逻辑重复**：`extractPackageName` 两份 | `AppListClient.kt` L531 + `MainViewModel.kt` L323 |
| P8 | **回调注入耦合**：`KtorServer` 5 个 `setXxx` 可变回调 + 延迟创建 `ConnectionManager` | `KtorServer.kt` L44–L63 |

---

## 2. 目标分层与依赖方向

```
┌───────────────────────────── UI 层 (Compose) ─────────────────────────────┐
│ MainActivity · ui/components/* · ui/theme/*                                │
│ MainViewModel (@HiltViewModel)  ── 订阅聚合 UiState，派发 Intent            │
└───────────────▲───────────────────────────────────────────┬───────────────┘
                │ StateFlow<LanSyncUiState>                  │ 用户操作 (suspend fun)
┌───────────────┴──────────────── 领域协调层 ────────────────▼───────────────┐
│  ConnectionCoordinator   UpdateCoordinator   DownloadInstallController      │
│  LocalAppRepository      (各持单一 scope，状态单点收敛)                      │
├────────────────────────────── 门面 (可选瘦壳) ─────────────────────────────┤
│  AppRepository → 降为组合门面 / 或由 ViewModel 直连各 Coordinator           │
├─────────────────────────────── 服务/传输层 ────────────────────────────────┤
│  LanSyncServer (契约接口) ← KtorLanSyncServer (实现)                        │
│  AppListClient · JmDNSDiscovery · ConnectionManager · AppPacker · ApkInstaller│
│  ForegroundSyncService (承载 start/stop 生命周期)                           │
├─────────────────────────────── 基础设施层 ─────────────────────────────────┤
│  AppScanner · HashUtils · NetworkUtils · AppIconDiskCache · FileLogger      │
│  AppConfig · 持久化 (SharedPreferences / local_apps_cache)                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 分层铁律
1. **依赖单向向下**：UI → ViewModel → 协调层 → 服务/传输层 → 基础设施层。**禁止任何反向引用**。
2. **data 层禁止 `import ui.*`**（消除 P4）：图标预加载下沉为 `AppIconDiskCache.preload(packageNames)`；`ui.components.preloadIcon` 变薄壳转调 data 层。CI 加静态检查（见 §8.3）。
3. **协议 DTO 边界**：所有跨设备字节契约集中在 `data/model`（见 SPEC.md §2），协调层与 UI 只消费领域模型，Ktor/OkHttp 实现细节封在传输层。
4. **契约接口隔离实现**：`LanSyncServer` 定义 HTTP API 契约接口，`KtorLanSyncServer` 为唯一实现；测试可用 `ktor-server-test-host` 或 fake 替换。

---

## 3. 模块拆分（职责 + 契约接口 + 边界）

> 以下为**目标设计契约**（Phase 1+ 落地）。接口用 Kotlin 示意，仅为设计文档，非生产代码。

### 3.1 `ConnectionCoordinator`（承接 P1/P3 核心并发治理）
**职责**：三设备列表（raw/enriched/connected）维护、`connectDevice`/断开编排、心跳循环、端口迁移、配对请求处理。
**并发模型（强制）**：所有状态写**收敛到单一调度协程**——专用 `Channel<ConnectionEvent>` + 单循环协程消费（Actor 模型），**禁止**在 `ConcurrentHashMap` 上裸读写后再 `StateFlow.value=` 的交错模式。

```kotlin
// 事件源（唯一写入路径）
sealed interface ConnectionEvent {
    data class RawDevicesUpdated(val devices: List<DeviceInfo>) : ConnectionEvent
    data class ConnectRequested(val device: DeviceInfo) : ConnectionEvent
    data class PollResult(val device: DeviceInfo, val result: ConnectResult) : ConnectionEvent
    data class HeartbeatTick(val displayKey: String, val alive: Boolean) : ConnectionEvent
    data class IncomingRequest(val request: IncomingConnectRequest, val accept: Boolean) : ConnectionEvent
    data class RemoteDisconnect(val key: String) : ConnectionEvent
    data class LocalDisconnect(val device: DeviceInfo) : ConnectionEvent
}

interface ConnectionCoordinator {
    val enrichedDevices: StateFlow<List<DeviceInfo>>   // 全部发现态
    val connectedDevices: StateFlow<List<DeviceInfo>>  // 仅已连接
    val incomingRequests: StateFlow<List<IncomingConnectRequest>>
    fun submit(event: ConnectionEvent)                 // 非阻塞投递，单协程串行消费
    suspend fun connect(device: DeviceInfo): Boolean
    fun disconnect(device: DeviceInfo)
    fun handleIncoming(requestId: String, accepted: Boolean)
}
```
**边界**：不碰 appList 内容比较（交 UpdateCoordinator）、不碰下载（交 DownloadInstallController）。心跳失败阈值/超时参数**全部取自 `AppConfig`**（禁止硬编码），保持 SPEC.md §7.2 数值不变。
**反向连接接受策略（TT3 已冻结，见 SPEC §7.7）**：新增 `PairingHistoryStore`（本机私有持久化，记录成功配对过的 `instanceId` 集合）作为 `ConnectionCoordinator` 协作者。`IncomingRequest` 事件处理**必须**实现干净语义：① 仅「配对历史命中」的设备自动接受；② 陌生设备一律保持 PENDING → 弹窗，**绝不自动放行**（修复旧漏洞）；③ 用户显式拒绝 → `respondToRequest(false)` 即时回 REJECTED（不再 `removeRequest` 致发起方 30s 超时）；④ 用户显式接受 → 写入配对历史。旧 `autoAcceptKnown` 双缺陷行为**作废**。

### 3.2 `UpdateCoordinator`
**职责**：`combine(localApps, connectedDevices)` → 5s 节流 → `UpdateManager.findUpdates/deduplicateUpdates` → `_availableUpdates`；`calculateSyncDiffs` → `_syncDiffs`。
**边界**：只读消费 `LocalAppRepository.localApps` 与 `ConnectionCoordinator.connectedDevices`，产出不可变列表；节流时间戳用**单一调度协程内的局部状态**替代共享可变 `lastUpdateRecalculationMs`（消除 P3 竞态）。
**冻结规则**（SPEC 未涉但属行为契约，取自 `UpdateManager`/`calculateSyncDiffs`）：跳过系统应用；仅 `remoteApp.versionCode > localApp.versionCode` 可更新；去重取最高 versionCode，平级取 `deviceName` 字母序更小者；`SyncDiff` 四类型 NEWER_ON_REMOTE/ONLY_ON_REMOTE/SAME_VERSION/ONLY_ON_LOCAL。

### 3.3 `LocalAppRepository`
**职责**：`AppScanner` 触发、`_localApps`、`local_apps_cache.json` 读写、图标预加载调度、通知已连接设备刷新。
**启动策略改进（承接 REPORT 7.2#8）**：改为「缓存骨架展示 + 后台静默校验刷新」——启动即用缓存填充 `localApps`，随后后台 `scanInstalledApps()` 覆盖并 diff 出新增/移除包，仅对变化项预加载/清理图标。
**边界**：图标预加载调 `AppIconDiskCache.preload(...)`（data 层），**不得** import ui（消除 P4）。

### 3.4 `DownloadInstallController`
**职责**：`downloadApp`/`installApp`/`downloadAndInstallApp`、`_downloadProgress`、`_installStatus`、下载文件管理（列举/删除/按名安装）。
**契约**：下载走 `AppListClient.downloadApksFile`，**校验以 `X-MD5`（传输产物）为唯一依据**（SPEC §8 决策 D1，废弃 expectedMd5 兜底）。安装走 `ApkInstaller`（FileProvider + ACTION_VIEW）。
**边界**：包名解析统一调用下沉后的单一 `DownloadedFileName` 工具（消除 P7），禁止 ViewModel 再实现一份。

### 3.5 `LanSyncServer`（HTTP API 契约接口，承接 P8）
**职责**：把「路由处理」与「服务器生命周期/传输实现」解耦。`KtorServer` 的 5 个 `setXxx` 可变回调 → 构造注入的**两个接口**：`ServerApiDelegate`（appList/pack/断开/刷新/设备名）+ `PairingStore`（配对协议状态）。

> **✅ Phase 1 已落地**（`data/server/ServerContracts.kt`）：下方接口签名与实现一致——`ServerApiDelegate` **不含** `connectionManager()`；配对状态抽象为**独立** `PairingStore` 接口（`InMemoryPairingStore` 实现），使 `lanSyncModule(delegate, pairingStore)` 可被 ktor-server-test-host 独立 install。

```kotlin
interface LanSyncServer {
    suspend fun start(port: Int = 0): Int      // 返回实际端口（port=0 动态分配）
    fun stop()
    fun isRunning(): Boolean
    fun getPort(): Int
}

// 路由处理器契约（由协调层实现，注入 KtorLanSyncServer / lanSyncModule）
interface ServerApiDelegate {
    fun provideAppList(): List<AppInfo>
    suspend fun pack(app: AppInfo): File?
    fun onDisconnect(key: String)
    fun onRefreshAppList(displayKey: String)
    fun deviceName(): String
}

// 配对协议状态存储（与 ServerApiDelegate 分离；SPEC §7.3 接收方状态机的传输层投影）
interface PairingStore {
    fun receiveRequest(payload: ConnectRequestPayload): Boolean   // 重复 requestId→false（路由 409）
    fun getStatus(requestId: String): ConnectResponsePayload?     // PENDING/未知→null（路由回 pending）
    fun respondToRequest(requestId: String, accepted: Boolean): ConnectResponsePayload? // 不存在/已处理→null（路由 404）
}
```
**边界**：`KtorLanSyncServer` 负责 ContentNegotiation/路由/响应头/错误映射；**路由逻辑严格复现 SPEC §3**（10 路由 + 各自错误码矩阵 + §5.1 三响应头 + §4 Content-Type）。`Routing.module()` 提取为可独立 `install` 形式（Phase 0 护栏接缝，见 TEST-PLAN §3）。

### 3.6 `ForegroundSyncService`（承接 P5，详见 §6）
**职责**：承载 `LanSyncServer.start/stop` + `JmDNSDiscovery` + `ConnectionCoordinator` 心跳的进程级生命周期，脱离 Activity。

### 3.7 UI 层
- `MainViewModel` 改 `@HiltViewModel`，删除手动工厂；订阅**单一聚合** `LanSyncUiState`（见 §5），派发用户操作到对应 Coordinator。
- Compose 屏幕保持 `ui/components/*` 每屏一 Composable + 回调上派（沿用现有约定）。
- 覆盖层对话框（下载进度/安装状态/连接请求/保存状态/首次扫描）行为不变。

---

## 4. 依赖注入方案（选型）

### 4.1 选型结论：**Hilt**

| 维度 | Hilt（选定） | Koin |
|---|---|---|
| 类型安全 | 编译期校验依赖图，缺失/循环**编译失败** | 运行时解析，错误延迟到崩溃 |
| 与 ViewModel 集成 | `@HiltViewModel` 官方一等公民，`viewModel()` 直接注入 | 需 `KoinComponent`/`getViewModel` 额外桥接 |
| 作用域管理 | `@Singleton`/`@ViewModelScoped`/`@ActivityRetainedScoped` 与 Android 生命周期对齐 | 需手写 scope 生命周期 |
| 学习/样板成本 | 注解处理器（KSP）+ 编译期开销 | 纯 DSL，零编译期开销，上手快 |
| 本项目适配 | 手写单例 `AppRepository.getInstance` + 4 张 map + 多 scope，**正需要编译期确定性与生命周期作用域** | 可行但把「运行时才发现接线错误」的风险引入正在治理竞态的重构 |

**理由**：本项目重构的**首要目标是正确性与可维护性**（消灭隐式时序耦合、状态单点收敛）。Hilt 的编译期依赖图校验能在重构拆分类时**立刻暴露接线错误**（如 Coordinator 依赖成环、Dispatcher 未限定），把风险从运行时前移到编译期，与「每步过护栏、绞杀者模式」的策略契合。Koin 的运行时解析在此场景反而是负债。项目已用 KSP/注解处理器生态（kotlinx-serialization 插件），引入 Hilt 增量成本低。

### 4.2 注入图（目标）
```kotlin
@HiltAndroidApp class LanSyncApplication : Application()   // FileLogger 初始化保留

@Module @InstallIn(SingletonComponent::class)
object LanSyncModule {
    @Provides @Singleton fun appConfig(): AppConfig = AppConfig.DEFAULT
    @Provides @Singleton @ApplicationScope fun appScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Provides @IoDispatcher fun io(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun default(): CoroutineDispatcher = Dispatchers.Default
    @Provides @Singleton fun server(delegate: ServerApiDelegate): LanSyncServer =
        KtorLanSyncServer(delegate)
    // ConnectionManager / JmDNSDiscovery / AppListClient / AppPacker /
    // ApkInstaller / AppScanner / AppIconDiskCache → @Singleton @Inject 构造注入
    // 四个 Coordinator → @Singleton @Inject，注入 @ApplicationScope + @IoDispatcher
}
```
**约定**：所有 `CoroutineScope`/`CoroutineDispatcher` 必须**限定注入**（`@ApplicationScope`/`@IoDispatcher`/`@DefaultDispatcher`），杜绝各类自建 `CoroutineScope(SupervisorJob()+Dispatchers.IO)`（现状每个类各建一个 scope，无法在测试中替换调度器）。`ServerApiDelegate` 由协调层聚合实现后注入 `KtorLanSyncServer`，替换 5 个 `setXxx` 回调（消除 P8）。

---

## 5. 状态管理：聚合 UiState（承接 P2）

**问题**：现状 ViewModel 用 3 组 `combine`（每组 5 flow）+ 12 路手动 `copy` 拼 `UiState`，字段散落、易漏更新。
**目标**：协调层各自暴露领域 StateFlow，ViewModel 用**单一 `combine` 归约**为不可变聚合 `LanSyncUiState`（Reducer 风格）。

```kotlin
data class LanSyncUiState(
    val localApps: List<AppInfo> = emptyList(),
    val enrichedDevices: List<DeviceInfo> = emptyList(),
    val connectedDevices: List<DeviceInfo> = emptyList(),
    val availableUpdates: List<UpdateInfo> = emptyList(),
    val syncDiffs: List<SyncDiff> = emptyList(),
    val incomingRequests: List<IncomingConnectRequest> = emptyList(),
    val downloadProgress: DownloadProgress? = null,
    val installStatus: InstallStatus? = null,
    val serverPort: Int = 0,
    val isRunning: Boolean = false,
    val isScanningApps: Boolean = false,
    // 纯 UI 选择态（selectedUpdates/remoteAppSelections/needsInitialScan 等）留在 ViewModel
    val userMessage: String? = null
)
```
**约束**：`combine` 超过 5 个 flow 时用嵌套 `combine` 或 `data class` 分组（现状即因 Kotlin 5-flow 重载限制而分 3 组）；聚合后 ViewModel 只做**一次** `copy`。UI 选择态（多选集合等）不进领域层。

---

## 6. 并发规范（强制）

1. **每个 Coordinator 单一 scope**：注入 `@ApplicationScope`（或 Coordinator 专属 scope），**禁止**类内自建 `CoroutineScope(SupervisorJob()+Dispatchers.IO)`（现状 `ConnectionManager`/`JmDNSDiscovery`/`AppRepository` 各建一个，测试无法注入 `TestDispatcher`）。
2. **状态收敛单点化**：
   - `ConnectionCoordinator` 用 **`Channel<ConnectionEvent>` + 单循环协程**（Actor）串行消费所有状态变更；或退一步用 **`Mutex`** 保护「读 map → 改 StateFlow」的复合操作。
   - **禁止** `ConcurrentHashMap` 上裸读写后再 `StateFlow.value = ...` 的交错模式（现状 P3 竞态根因：如 `runHeartbeatPing` 先 `heartbeatFailCounts[key]=failCount` 再 `addOrUpdateInConnected`，与端口迁移的 `scope.launch{}` 并发时存在窗口）。
3. **每设备 Job 生命周期**：心跳/同步 Job 仍按 `displayKey` 管理，但**取消与重建必须在同一调度协程内**完成（端口迁移「停旧起新」不得跨协程交错）。
4. **调度器注入**：IO/Default 调度器全部限定注入，单元测试用 `StandardTestDispatcher` + `runTest` 控制虚拟时间（测心跳 20s/超时 15s/30s 无需真实等待）。
5. **共享可变标量**：`lastUpdateRecalculationMs` 之类移入单协程局部状态或用 `AtomicLong`，禁止无同步的跨协程读写。

---

## 7. 统一错误协议（承接 P6）

### 7.1 错误码枚举（服务端）
```kotlin
enum class LanSyncErrorCode {
    APP_NOT_FOUND,      // 404 下载无匹配应用
    REQUEST_NOT_FOUND,  // 404 配对请求不存在或已处理（connect/response）
    NOT_EXTRACTABLE,    // 403 系统/受保护应用
    PACK_FAILED,        // 500 打包失败
    INVALID_REQUEST,    // 400 解析失败
    CONFLICT,           // 409 重复请求
    SERVER_NOT_READY,   // 503 旧服务端惰性初始化产物；新服务端不再发出（保留供识别旧端）
    INTERNAL            // 500 兜底
}
// ✅ Phase 1 已落地：data/model/LanSyncError.kt 与此一致（含 REQUEST_NOT_FOUND）；
//    LanSyncErrorDto(code, message) + of(code, message) 工厂，两字段无默认值故恒完整上线。

@Serializable
data class LanSyncErrorDto(val code: String, val message: String)  // message 为用户可读文案，非异常细节
```
### 7.2 规则
- **禁止 `e.message` 回显给客户端**：`respondText("...${e.message}")` 全部替换为 `call.respond(status, LanSyncErrorDto(code, 通用文案))`；`e.message`/堆栈**只进 `FileLogger`**。
- **状态码保持 SPEC §3 不变**（403/404/409/400/500/503 语义一一对应），仅**错误体从纯文本改结构化 JSON**。
- **互操作安全性**：旧客户端**不解析**错误体（下载仅取 `body.take(200)` 塞文案、连接类只看 `isSuccessful`），故错误体从 `text/plain` 改 `application/json` **不破坏互操作**（SPEC §3.2）。
- **业务错误仍用 sealed result**：`ConnectResult`(Accepted/Rejected/Timeout)、`DownloadResult`(Success/Error)、`InstallationResult`(Success/Error) 保留，但 `Error.message` 收敛为**枚举 + 用户文案**，不含异常细节。

---

## 8. Foreground Service 生命周期与通知设计（承接 P5）

### 8.1 决策（ADR 摘要）
- **方案 A（采纳）**：引入 `ForegroundSyncService`，符合「后台保活同步」工具定位。
- **方案 B（否决）**：明确「仅前台使用」——零成本但退后台即失效，牺牲核心场景。
- **回退**：特性开关 `sync_foreground_enabled`，可一键退回前台模式。

### 8.2 服务设计
```kotlin
@AndroidEntryPoint
class ForegroundSyncService : Service() {
    // onStartCommand: START_STICKY；startForeground(NOTIF_ID, buildNotification())
    // 绑定：LanSyncServer.start() + JmDNSDiscovery.startDiscovery(port) + ConnectionCoordinator 心跳
    // onDestroy：有序 stop（心跳→discovery→server），释放 MulticastLock
}
```
- **foregroundServiceType**：`specialUse`（附 Play 上架说明）或 `dataSync`；API 34 强制声明。
- **新增权限**：`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`（或 `FOREGROUND_SERVICE_DATA_SYNC`）+ `POST_NOTIFICATIONS`（API 33+ 运行时请求）。
- **启动时机**：固定在用户前台点击「启用同步」时 `startForegroundService`，规避 Android 12+ 后台启动 FGS 限制。
- **生命周期归属迁移**：`server.start/stop` 从 ViewModel 迁到 Service；ViewModel 通过绑定/`StateFlow` 观察服务状态。
- **进程被杀恢复**：`START_STICKY` + `instanceId` 持久化（已支持身份无缝重连）+ IP/端口变化由端口迁移逻辑（SPEC §7.5）吸收。
- **通知内容**：常驻显示「运行中 · 端口 {port} · 已连接 {n} 台」；操作按钮「停止同步」「打开」。低端 OEM 省电场景在 README 标注「必要时加电池白名单」。

### 8.3 Manifest 变更（目标，非本次生成）
- 新增 `<service android:name=".ForegroundSyncService" android:foregroundServiceType="specialUse" android:exported="false">` + `<property>` 说明。
- 现有 9 项权限保留；`NEARBY_WIFI_DEVICES`(neverForLocation) 已声明，评估是否可去定位权限（Phase 6 权限审查）。
- **CI 分层检查**：加静态规则（如 Konsist/自定义 lint）断言 `data/**` 不 import `com.lansync.app.ui.*`（守护 §2.1 铁律 2）。

---

## 9. 旧→新 迁移映射（绞杀者模式）

| 旧（`AppRepository` 成员） | 新归属 | 备注 |
|---|---|---|
| `_localApps` + 扫描 + 缓存 + 图标预加载 | `LocalAppRepository` | 图标预加载下沉 data 层 |
| `_rawDiscoveredDevices`/`_enrichedDevices`/`_connectedDevices` + `connectDevice`/心跳/端口迁移/`handleIncomingRequest` + 4 张 map | `ConnectionCoordinator`（+ `PairingHistoryStore`） | 并发单点收敛（Actor/Mutex）；接受策略改干净语义（TT3/SPEC §7.7） |
| `_availableUpdates`/`_syncDiffs` + `combine` 节流 + `UpdateManager` 调用 | `UpdateCoordinator` | 节流状态入单协程 |
| `_downloadProgress`/`_installStatus` + `downloadApp`/`installApp` + 文件管理 | `DownloadInstallController` | 校验以 X-MD5 为准 |
| `start()`/`stop()`/`forceStartSync()` 生命周期 | `ForegroundSyncService` | `forceStartSync` 语义改「重启服务」或删除（Phase 1） |
| `KtorServer` 5×`setXxx` 回调 | `ServerApiDelegate` 构造注入 | 消除 P8 |
| 12 路 StateFlow + 3 组 combine | 聚合 `LanSyncUiState` + 单 combine | 消除 P2 |
| `AppRepository.getInstance` 手写单例 | Hilt `@Singleton @Inject` | 消除隐式全局态 |

**迁移顺序**（先无并发、后集中治并发，每步过 SPEC 护栏 + 手工黄金链路）：
`LocalAppRepository` → `UpdateCoordinator` → `DownloadInstallController` → **`ConnectionCoordinator`（并发治理压缩到单一 PR 评审）** → 门面清空 → FGS。
`AppRepository` 全程保留为**门面**（委托 + 组合暴露原 StateFlow），对 ViewModel 公开 API 零变化，任何协作者可单独 `git revert` 退回委托旧路径。

---

## 10. 目标包结构（单 Gradle 模块，包级拆分）

```
com.lansync.app/
├── data/
│   ├── connection/   ConnectionCoordinator, ConnectionManager, ConnectionEvent, PairingHistoryStore
│   ├── sync/         UpdateCoordinator, UpdateManager
│   ├── transfer/     DownloadInstallController, AppListClient, AppPacker, ApkInstaller
│   ├── localapps/    LocalAppRepository, AppScanner
│   ├── server/       LanSyncServer(接口), KtorLanSyncServer, ServerApiDelegate, Routing.module()
│   ├── discovery/    JmDNSDiscovery
│   ├── model/        DTO (SPEC §2) + LanSyncErrorDto + LanSyncErrorCode
│   ├── cache/        AppIconDiskCache(+preload)
│   ├── AppConfig, HashUtils, NetworkUtils, FileLogger, DownloadedFileName(收敛包名解析)
├── service/          ForegroundSyncService
├── di/               LanSyncModule, 限定注解(@ApplicationScope/@IoDispatcher/@DefaultDispatcher)
└── ui/               components/, theme/, viewmodel/(MainViewModel @HiltViewModel)
```
> 保持单 `:app` 模块（避免多模块构建复杂度成为二次风险）；若拆分后编译反馈明显变慢再评估 `:core`/`:network`。

---

## 11. 工具链目标（Phase 2，与架构改动解耦）

| 项 | 现状 | 目标 |
|---|---|---|
| Kotlin | 1.9.20 | 2.0.x（引入 `org.jetbrains.kotlin.plugin.compose`，移除 `composeOptions.kotlinCompilerExtensionVersion=1.5.5`） |
| Compose BOM | 2023.10.01 | 2024.09.00+ |
| lifecycle | 2.6.2 | 2.8.x |
| Ktor | 2.3.5 | 2.3.12（2.3.x 内升级，不叠加架构改动） |
| DI | 无 | Hilt（§4） |
| 死依赖 | `gson:2.10.1`、`play-services-base:18.3.0`（零引用） | **移除**；移除 `android.enableJetifier` |
| DI/序列化 keep | — | 补 `proguard-rules.pro` 对 kotlinx-serialization DTO 的 keep 规则，验证 R8 release 链路 |

> 工具链升级独立分支验证，**不与协议/架构改动叠加**，避免风险耦合。

---

## 12. 架构验收标准

1. `AppRepository` ≤150 行门面；四个 Coordinator 各 ≤300 行、单一职责、可独立测试。
2. 全部状态变更单点收敛，`ConcurrentHashMap` + StateFlow 裸混用清零（§6）。
3. `data/**` 零 `import ui.*`（CI 静态检查通过）。
4. 回调注入（`setXxx`）清零，全量构造注入 + Hilt（§4）。
5. 服务端零 `e.message` 回显，统一 `LanSyncErrorDto` + 错误码枚举（§7）。
6. 退后台 5 分钟链路存活实测通过（§8）。
7. **SPEC.md §9 六条互操作红线全部由特征化测试锁定并通过**（见 TEST-PLAN.md）。

---

## 13. Phase 1 落地现状与保真审查（2026-09-08 复核）

> 应用户要求核验「已完成的 Phase 1」是否忠实复现目标架构与 SPEC 契约。结论：**新构件契约保真度高，但尚未接线，且若干 Phase 1 卫生项未落地**。

### 13.1 已忠实落地（新构件 + 测试，均契约保真）
| 目标（本文档章节） | 实现 | 状态 |
|---|---|---|
| §3.5 LanSyncServer/ServerApiDelegate/PairingStore 契约接口 | `ServerContracts.kt` | ✅ |
| §3.5 Routing.module() 可独立 install（测试接缝） | `LanSyncRouting.lanSyncModule(delegate, pairingStore)` | ✅ + 33 路由测试 |
| §3.5 KtorLanSyncServer 引擎/端口 | `KtorLanSyncServer.kt`（port=0 动态分配） | ✅ |
| §7 统一错误枚举 + DTO，禁 e.message 回显 | `LanSyncError.kt` + 路由全用 `LanSyncErrorDto.of(...)` | ✅（测试断言不泄露 "boom"） |
| §7.3 配对状态机重写（更瘦、可虚拟时间测试） | `InMemoryPairingStore.kt` | ✅ + 8 测试 |
| §3.4/P7 下载 + D1 校验 + 命名解析收敛 | `transfer/LanSyncClient.kt` + `transfer/DownloadedFileName.kt` | ✅ + 12 + 10 测试 |
| §8.2/REPORT 7.3#14 HashUtils null 传播 | `HashUtils.md5(paths)` 改动 | ✅ + 测试 4→6 |

### 13.2 尚未接线（关键集成缺口）
- `AppRepository`（仍 1181 行上帝类）**未改动**：仍注入旧 `KtorServer`/`AppListClient`/`ConnectionManager`/`UpdateManager(旧 client)`。新构件为**并行**存在，App 运行时仍走旧路径（旧双轨 MD5、旧 `handleIncomingRequest` 缺陷、旧 `e.message` 回显、旧 12 StateFlow）。
- 即：§3（模块拆分）、§4（Hilt DI）、§5（聚合 UiState）、§6（并发单点收敛）、§8（前台服务）均属 **Phase 3+**，Phase 1 未触及，符合计划（Phase 1 仅「工程卫生与快赢项」+ 新构件预置）。

### 13.3 Phase 1 工程卫生项落地状态（2026-09-08 更新）
| 计划项（task-436 阶段1） | 状态 | 位置 |
|---|---|---|
| 移除死依赖 `gson`、`play-services-base` | ✅ 已移除（grep 确认 `app/src` 零引用） | `app/build.gradle.kts` |
| 移除 `android.enableJetifier` | ✅ 已移除（全依赖均 AndroidX 原生/纯 JVM） | `gradle.properties` |
| 删除重复 `org.gradle.unsafe.configuration-cache` | ✅ 已删（保留 `org.gradle.configuration-cache`） | `gradle.properties` |
| **仓库解析修复**：`PREFER_PROJECT`→`PREFER_SETTINGS` | ✅ 已修（机器 init 脚本无 `google()` 曾致 androidx 404） | `settings.gradle.kts` |
| 清理 `forceStartSync()`（与 start 重复） | 🧊 **legacy-known-issue**（旧实现；互操作基线建立前旧代码冻结，不做任何卫生修改；Phase 4 切换门面时随旧 `AppRepository` 整体删除而消除） | `AppRepository.kt` L624–647 |
| 修复分层倒置（`import ui.components.preloadIcon`） | 🧊 **legacy-known-issue**（同上；Phase 4 旧 `AppRepository` 删除时自然消除，不单独修） | `AppRepository.kt` L22/L901 |
| AppScanner 对 null 指纹降级 isExtractable=false | ✅ **裁决②：接受现状**（`md5 ?: ""` 且 `isExtractable=true` 为合法态，已写入 SPEC §8.4 作为新 AppScanner 行为规范；若真机测试发现误伤正常应用再重开此决策） | `AppScanner.kt` L73/L83 |

> **结论**：Phase 1 的**新构件重写忠实复现了 SPEC/ARCHITECTURE 契约**（含 D1、统一错误协议、命名收敛、配对状态机），**95 例测试全绿**（见 TEST-PLAN §10）。本轮已补齐 4 项构建卫生/仓库修复；`forceStartSync`、分层倒置经裁决定为 **legacy-known-issue**（旧代码冻结，Phase 4 随门面切换整体删除，不做单独卫生修改）；AppScanner null 指纹经裁决 **② 接受现状**（写入 SPEC §8.4）。接线（整合进 AppRepository）属 Phase 4。

---

*（ARCHITECTURE.md 结束。协议契约见 SPEC.md，测试与验收见 TEST-PLAN.md。）*
