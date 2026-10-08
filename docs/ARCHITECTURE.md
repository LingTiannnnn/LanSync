# LanSync 架构（ARCHITECTURE）

> **本文件角色**：架构主题文档——分层与依赖铁律、模块契约接口签名、并发规范、统一错误协议、前台服务设计、迁移映射、验收标准，以及**当前实现形态**（§13：代码地图、端到端主链路、DI 现状、UI 设计系统）。
> 生产代码与测试注释按本文件章节号引用（`ARCH §2/§3.1–§3.4/§6/§7/§8/§8.3`），**不要重排 §N**。
> §1–§11 是目标设计，§12 是逐条验收状态，§13 是现行落地形态；两者不一致时以 §13 与代码为准。
> **重构范式**：**契约不变，实现重写**。线上协议以 `docs/SPEC.md` 为唯一冻结基线；本文件定义承载该契约的内部架构。
> **核心目标**：消灭 1181 行上帝类 `AppRepository`（12 个 `MutableStateFlow` + 4 张 `ConcurrentHashMap` + 共享 scope 混用），按领域拆为可独立测试的协作者，状态变更单点收敛，引入 DI 与前台服务，统一错误协议。

---

## 1. 现状痛点（重构前的设计动因）

> 本节是重构前代码的实证快照，用来解释"为什么代码长这样"；§2–§8 的规则以 `(消除 Pn)` 形式引用这里的编号。重构前实现可在 git 基线 `d1a71fc` 查阅，现行形态见 §13。

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

> **本图是目标形态，构件名与现行实现的差异**（详表见 §13.1）：`AppListClient` → `data/transfer/LanSyncClient`；`JmDNSDiscovery` → `data/discovery/JmDNSDeviceDiscovery`；`ConnectionManager` → `data/connection/DefaultConnectionCoordinator`（配对协议半部复用 `data/server/InMemoryPairingStore`）；`AppRepository` 门面 → `data/repository/LanSyncRepository` + 组合根 `data/repository/LanSyncGraph`；`AppIconDiskCache` 之上另有 `data/cache/IconCache`；`di/` 包不存在（手写 DI）。
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
**启动策略**：「缓存骨架 + 后台校验刷新」——先用 `local_apps_cache.json` 立即填充 `localApps`（`loadCacheSkeleton()`，不触发扫描），随后后台 `scanInstalledApps()` 全量覆盖并 diff 出新增/移除包，仅对变化项预加载/清理图标。缓存读失败要自愈删除并回落首次扫描。
> **接线要求**：`MainViewModel.autoStart` 必须在 `hasLocalAppCache()` 分支判断**之前无条件**调用门面的 `loadLocalAppCache()`（顺带避免 TOCTOU）。"能力已实现且有单测"不等于"已接线"，改动启动链路时按此核对。
**边界**：图标预加载调 `AppIconDiskCache.preload(...)`（data 层），**不得** import ui（消除 P4）。

### 3.4 `DownloadInstallController`
**职责**：`downloadApp`/`installApp`/`downloadAndInstallApp`、`_downloadProgress`、`_installStatus`、下载文件管理（列举/删除/按名安装）。
**契约**：下载走 `AppListClient.downloadApksFile`，**校验以 `X-MD5`（传输产物）为唯一依据**（SPEC §8 决策 D1，废弃 expectedMd5 兜底）。安装走 `ApkInstaller`（FileProvider + ACTION_VIEW）。
**边界**：包名解析统一调用下沉后的单一 `DownloadedFileName` 工具（消除 P7），禁止 ViewModel 再实现一份。

### 3.5 `LanSyncServer`（HTTP API 契约接口，承接 P8）
**职责**：把「路由处理」与「服务器生命周期/传输实现」解耦。`KtorServer` 的 5 个 `setXxx` 可变回调 → 构造注入的**两个接口**：`ServerApiDelegate`（appList/pack/断开/刷新/设备名）+ `PairingStore`（配对协议状态）。

> **现行实现**（`data/server/ServerContracts.kt`）：下方接口签名与代码一致——`ServerApiDelegate` **不含** `connectionManager()`；配对状态抽象为**独立** `PairingStore` 接口（`InMemoryPairingStore` 实现），使 `lanSyncModule(delegate, pairingStore)` 可被 ktor-server-test-host 独立 install。**这个可独立安装的接缝是路由可测的前提，不可弱化。**

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

> ⚠️ **现状与目标的偏差**：实际采用**手写组合根 `data/repository/LanSyncGraph`（单例 + 可空 late-bind 引用破 `server↔coordinator↔pairingStore` 构造环）**，理由是把 KSP/插件风险挡在协议重写之外。本节的 Hilt 选型与 §4.2 注入图**仍是有效目标**，落地属 **Phase 7**（`docs/ROADMAP.md` §3）。已落地的部分：`setXxx` 回调注入清零，全部改为 `ServerApiDelegate`/`PairingStore` 构造注入。现状详述见 §13.3。

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
// 现行实现：data/model/LanSyncError.kt 与此一致（含 REQUEST_NOT_FOUND）；
// LanSyncErrorDto(code, message) + of(code, message) 工厂，两字段无默认值故恒完整上线。

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

> 下表"新归属"即当前生产实现（§13.1 有完整代码地图）。绞杀者策略的收口方式是**新建 `LanSyncRepository` 瘦门面 + 删除旧类**，而不是把 `AppRepository` 一路降级保留。

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
>
> **与本图的差异**：门面与组合根位于 `data/repository/`（`LanSyncRepository`、`LanSyncGraph`）而非顶层；**`di/` 包不存在**（手写 DI，见 §4.1 注）；`discovery/` 是 `DeviceDiscovery` + `JmDNSDeviceDiscovery`；`sync/` 含 `UpdateCoordinator` + `UpdateManager`；`cache/` 同时有 `IconCache` 与 `AppIconDiskCache`；`connection/` 另有 `ConnectionTransport`/`LanSyncClientTransport`/`SharedPrefsPairingHistoryStore`；`installer/` 在 `data/installer/`。完整代码地图见 §13.1。

---

## 11. 工具链目标（未启动 · 对应执行阶段 **Phase 7**）

> ⚠️ 标题里的"Phase 2"是 `.qoder/specs/LanSync_重构计划_task-436.md` 的原始阶段编号；按 `docs/STATUS.md` 的执行阶段序列，本组升级属 **Phase 7**（范围见 `docs/ROADMAP.md` §3，尚未启动）：组件重写优先，工具链升级**不与协议/架构改动叠加**。下表"现状"是当前版本锁，与 `docs/BUILD.md` §3 一致。

| 项 | 目标 |
|---|---|
| Kotlin | 2.0.x（引入 `org.jetbrains.kotlin.plugin.compose`，移除 `composeOptions.kotlinCompilerExtensionVersion=1.5.5`） |
| Compose BOM | 2024.09.00+（升后可收敛 `LocalContainers` 兼容层） |
| lifecycle | 2.8.x |
| Ktor | 2.3.12（2.3.x 内升级） |
| DI | Hilt（§4 目标注入图） |
| R8 / release | 补 `proguard-rules.pro` 对 kotlinx-serialization DTO 的 keep 规则，`assembleRelease` + 真机冒烟验证安装器链路 |

当前版本锁数值只在 `docs/BUILD.md` §3 维护；升级顺序与边界见 `docs/ROADMAP.md` §3。

---

## 12. 架构验收标准（逐条状态）

1. 🟡 门面 ≤150 行**未达字面**：现行门面 `LanSyncRepository` **164 行**，硬约束 ≤300 由 `LayeringTest` 守护；四协作者均在 300 行内、可独立测试。差距记录在 `docs/ROADMAP.md` §4。
2. ✅ 状态变更单点收敛（`DefaultConnectionCoordinator` Actor），`ConcurrentHashMap` + StateFlow 裸混用清零（§6）。
3. 🟡 **形态不同**：`data/**` 零 `import ui.*` 由 `architecture/LayeringTest`（2 例，随 `testDebugUnitTest`）守护；本条字面要求是"**CI** 静态检查"，项目无 CI/CD 故字面未满足。已知绕过面：不写 import 而用全限定名。
4. 🟡 回调注入（`setXxx`）已清零 ✅；**Hilt 未引入**（现状手写 `LanSyncGraph`，见 §13.3）→ 本条只满足一半。
5. ✅ 服务端零 `e.message` 回显，统一 `LanSyncErrorDto` + 错误码枚举（§7）；`LanSyncRoutingTest` 断言不泄露异常细节。
6. ⏳ 退后台 5 分钟链路存活待真机实测（`docs/TEST-PLAN.md` §6）。
7. 🟡 SPEC §9 六条互操作红线已由特征化测试锁定并全绿；**真机新旧互操作未执行**。

---

## 13. 当前实现形态

> 本节是"现在长什么样"的权威描述，与 §1–§11 的目标设计冲突时以本节和代码为准。

### 13.1 分层与代码地图

```
UI(Compose: MainActivity · ui/components/* · ui/theme/*)
   ↑ StateFlow<UiState>          ↓ 用户操作（suspend fun / 回调上派）
ViewModel（MainViewModel：单 combine 归约 UiState，派发）
   ↑                              ↓
协调层（ConnectionCoordinator · UpdateCoordinator · DownloadInstallController · LocalAppRepository）
   ↑                              ↓
门面（data/repository/LanSyncRepository，纯委托 ≤300 行）← 组合根（data/repository/LanSyncGraph）
   ↑                              ↓
服务/传输（KtorLanSyncServer + lanSyncModule · LanSyncClient · JmDNSDeviceDiscovery · AppPacker · ApkInstaller · ForegroundSyncService）
基础设施（AppScanner · HashUtils · NetworkUtils · IconCache/AppIconDiskCache · FileLogger · AppConfig · SharedPreferences/JSON）
```

`app/src/main/java/com/lansync/app/`：

| 包 / 文件 | 职责 | JVM 可单测 |
|---|---|---|
| `data/model/Models.kt` | 全部 DTO + `ConnectionState`/`SyncDiff`/`UpdateInfo`/`RemoteAppEntry` | ✅ `ModelsTest`(13) |
| `data/model/LanSyncError.kt` | `LanSyncErrorCode` 枚举 + `LanSyncErrorDto(code,message)` | ✅ 随路由测 |
| `data/server/ServerContracts.kt` | `LanSyncServer` / `ServerApiDelegate` / `PairingStore` 契约接口 | — |
| `data/server/LanSyncRouting.kt` | `lanSyncModule` 10 路由 + `LanSyncJson` + `sendPackedFile` 三响应头 | ✅ `LanSyncRoutingTest`(33) |
| `data/server/KtorLanSyncServer.kt` | Netty 引擎、`port=0` 动态分配、`resolvedConnectors()` 取实际端口 | ➖ 编译校验 |
| `data/server/InMemoryPairingStore.kt` | 接收方配对子状态机（PENDING/ACCEPTED/REJECTED/TIMEOUT，15s） | ✅ (9) |
| `data/server/NotifyingPairingStore.kt` | 适配器：`receiveRequest` → coordinator `IncomingRequestReceived` | ➖ |
| `data/transfer/LanSyncClient.kt` | OkHttp 客户端：连接请求/轮询/响应/列表/设备信息/ping/disconnect/refresh + 下载与 `X-MD5` 校验 + 下载文件管理 | ✅ (18) |
| `data/transfer/DownloadedFileName.kt` | 产物命名 / 落盘名 / **包名反推的唯一实现** | ✅ (10) |
| `data/transfer/AppPacker.kt` | 单包字节副本 `.apk` / split zip `.apks`（`base.apk` + `split_N.apk`），输出目录构造注入 | ✅ (6) |
| `data/transfer/DownloadInstallController.kt` | 下载 / 安装 / 文件管理 + `downloadProgress`/`installStatus` StateFlow | ✅ (7) |
| `data/discovery/{DeviceDiscovery,JmDNSDeviceDiscovery}.kt` | SPEC §6：服务注册 + TXT 双键 + 60s 保活 + 陈旧清理 + MulticastLock；**scope 构造注入** | ❌ Android/JmDNS 耦合 |
| `data/connection/ConnectionEvent.kt` | 事件密封接口 + `ConnectAttemptResult` | — |
| `data/connection/{ConnectionCoordinator,DefaultConnectionCoordinator}.kt` | SPEC §7.4–7.7：**Actor 单点收敛**（单 `Channel` + 单消费协程持全部可变状态）；状态机/心跳/端口迁移/陈旧清理/去重/接受策略 | ✅ (21) 虚拟时间 |
| `data/connection/{PairingHistoryStore,SharedPrefsPairingHistoryStore}.kt` | 自动接受的**唯一判据**（历史配对 `instanceId`） | ✅ |
| `data/connection/ConnectionTransport.kt` | 传输抽象 + `ConnectOutcome`/`LocalIdentity` → coordinator 可用**手写 Fake** 测 | — |
| `data/connection/LanSyncClientTransport.kt` | 适配器：`LanSyncClient` → `ConnectionTransport` | ➖ |
| `data/localapps/{InstalledAppScanner,AppScanner}.kt` | PackageManager 扫描；D2：`md5 ?: ""` 且 `isExtractable=true` 为合法态 | ❌ 真机 |
| `data/localapps/LocalAppRepository.kt` | `local_apps_cache.json` 读写 + `localApps`/`isScanning` + 「缓存骨架 + 后台刷新」+ `ScanResult.added/removedPackages` | ✅ (5) |
| `data/sync/UpdateManager.kt` | **纯函数**：`findUpdates`/`deduplicateUpdates`/`calculateSyncDiffs`（不自行 fetch） | ✅ (9) |
| `data/sync/UpdateCoordinator.kt` | `combine(localApps, connectedDevices)` → 5s 节流 → `availableUpdates`/`syncDiffs`；`nowMillis` 可注入 | ✅ (4) |
| `data/cache/IconCache.kt` / `AppIconDiskCache.kt` | data 层三级图标缓存（内存 Lru → 磁盘 PNG → PackageManager 绘制）+ preload | ➖ |
| `data/repository/LanSyncRepository.kt` | **组合门面 164 行（硬约束 ≤300）**：11 路对外 StateFlow（自有 `serverPort`/`isRunning` 2 路 + 转发协作者 9 路）+ start/stop 生命周期编排 + 纯委托，零业务逻辑 | ➖ 编译 + 门禁 |
| `data/repository/LanSyncGraph.kt` | **手写 DI 组合根**（129 行）：可空 late-bind 引用破 `server ↔ coordinator ↔ pairingStore` 构造环 | ➖ |
| `service/ForegroundSyncService.kt` | `specialUse` FGS：承载 `repo.start/stop`、常驻通知（端口/连接数 + 停止/打开）、`START_STICKY` | ❌ 真机 |
| `ui/viewmodel/MainViewModel.kt` | `AndroidViewModel`：`uiState: StateFlow<UiState>` 单一出口；`autoStart` 先灌缓存骨架 | ❌ 无 Robolectric 栈 |
| `ui/theme/{Color,Theme,Spacing,Shape,Typography}.kt` | 设计系统（§13.4） | — |
| `ui/components/*`（12 文件） | 5 Tab + `CommonComponents`/`IdentityStrip` + 覆盖层 | ❌ 真机目视 |
| `data/{AppConfig,HashUtils,NetworkUtils,FileLogger}.kt` | 超时参数唯一来源 / MD5 工具（null 传播）/ IP 选取 / 文件日志 | ✅ AppConfig(2)/HashUtils(6)+一致性(2) |

### 13.2 三条端到端主链路

参数取值见 `docs/SPEC.md` §7.2，契约条款见 SPEC 对应章节。

```
① 连接   mDNS 多播发现 → 用户点连接 → POST /api/connect/request
         → 接收方登记 InMemoryPairingStore（15s 倒计时，UI = IncomingConnectionSheet）
         → 接收方接受 → POST /api/connect/response
         → 发起方 500ms 轮询 GET /api/connect/status（总上限 30s）→ Accepted → 入 connectedDevices
         → 双向 20s GET /api/ping + 首拉 GET /api/applist（5 次 / 3s 重试），此后 120s 周期重拉
         → IP/端口变化按 instanceId 做端口迁移（停旧起新，同一调度协程内）
② 更新   AppScanner 扫 PackageManager → LocalAppRepository（启动灌骨架 + 后台全量校验刷新）
         → combine(localApps, connectedDevices) → 5s 节流
         → UpdateManager.findUpdates / deduplicateUpdates / calculateSyncDiffs（纯函数，不 fetch）
         → UpdateCoordinator → UiState.availableUpdates / syncDiffs → 同步 Tab
③ 下载安装 UI/VM → 门面 downloadAndInstallApp → DownloadInstallController
        → GET /api/download/{pkg}/{vc} → 服务端 AppPacker 产 cacheDir/apks（启动 clearCache()）
        → 流式响应带 X-MD5 / X-File-Size / Content-Disposition
        → 客户端写 cacheDir/downloads/{pkg}_{vc}.{apk|apks}，以 X-MD5 校验（失败分支的删盘语义见 SPEC §5.6）
        → DownloadProgress → ApkInstaller：FileProvider URI + ACTION_VIEW
          （.apk → package-archive；.apks → zip）
        → 保存到公共目录另走 SAF（SaveStatusDialog + CreateDocument），与安装链路独立
```

两个易错点：`UpdateManager` **不自己拉对端列表**，数据只来自 `ConnectionCoordinator` 维护的 `connectedDevices.appList`；安装一律走 `downloadAndInstallApp`（门面上的 `downloadApp` 转发无生产调用者，见 `docs/ROADMAP.md` §6）。反向连接与自动接受语义见 SPEC §7.7。

### 13.3 DI 现状与目标的分歧

- **现状**：手写组合根 `LanSyncGraph` 单例 + 可空 late-bind 破构造环；`setXxx` 回调注入已清零（改 `ServerApiDelegate` / `PairingStore` 构造注入）。
- **目标**：§4 的 Hilt 选型仍未落地（属 Phase 7）。⚠️ 别把 §4 当现状读，也别在 §12 第 4 条上误认为 DI 框架已就位。

### 13.4 UI 设计系统与约定

- **配色 Teal 青绿**，唯一色值来源 `ui/theme/Color.kt`（`internal` 常量仅供 `Theme.kt` 组装 ColorScheme）：Primary40 `#00696B`、亮色背景 `#F6FAF9`、暗色 `#0E1416`（非纯黑）、Secondary 中性青灰、**Tertiary 琥珀承载"可更新"语义**、Error 为 M3 标准红。启动器底色同为 `#00696B`。
- **`dynamicColor` 默认 `false`**：跨设备一致的单一设计系统优先于 Material You。
- **令牌入口**：`LanSyncTheme.spacing`（`LanSyncSpacing` 4dp 基栅格 + icon/appIcon/radius/控件尺寸，经 `LocalSpacing` 注入）、`LanSyncTheme.containers`（`LocalContainers` 四档 surfaceContainer 兼容层）、`LanSyncShapes`（8/12/16/24/28）、`LanSyncMetrics`、`Typography.kt`（完整 M3 比例，组件禁止内联 `fontSize`/`letterSpacing`）。
- **连接状态色全走 colorScheme 角色**：CONNECTED=secondary、CONNECTING/RECONNECTING=tertiary、ERROR/TIMEOUT=error、DISCOVERED=outline、DISCONNECTED=onSurfaceVariant。
- **文案唯一来源** `res/values/strings.xml`（约 150 条，参数用 `%1$d`/`%1$s`）；组件经 `stringResource(id, args)`，VM/Service/SAF 助手经 `getString(id, args)`。
- **edge-to-edge**：`MainActivity.onCreate` 调 `enableEdgeToEdge()`；`NavigationBar` 用 `containers.default` + `WindowInsets.navigationBars`；只按 luminance 设系统栏图标亮暗。
- **`AppIcon(packageName, modifier, size: Dp = DefaultSpacing.appIconXl)`**——尺寸是 `Dp` 不是 `Int`，传值走 `LanSyncTheme.spacing.appIcon*`；其余共享子组件（`SearchBar`/`CategoryTabs`/`SectionHeader`/`UpdateItem`/`EmptyStateCard`）签名稳定，只换内部 token。
- **结构**：底部 5 Tab（设备 / 本地应用 / 远程应用 / 同步 / 文件）+ 语境 TopBar（同步页 `selectedUpdates` 非空时切 Contextual TopBar + `BatchBar`）；共享组件在 `CommonComponents.kt`（`StatusChip`/`LanSyncFilterChip`/`EmptyState`/`MeshHint`/`SummaryCard`/`BatchBar`/`SectionHeader`/`SearchBar`/`UpdateAccentBar`）+ `IdentityStrip.kt`；覆盖层为配对 `ModalBottomSheet`、下载进度 Dialog、安装与操作反馈 Snackbar、首扫遮罩。
- **门禁形态**：Compose UI 不是 JVM 单测目标 → `assembleDebug` 编译 + 零硬编码审计 + 基线不变 + 真机目视。

### 13.5 与目标设计的其他差异

- §8.2 设想的"用户前台点击启用同步时才 `startForegroundService`"未采用：服务由 `MainViewModel.init → autoStart()` 自启，因此 `POST_NOTIFICATIONS` 运行时请求挂在 Activity 创建点（裁决见 `docs/DECISIONS.md`）。
- §8.3 设想的 Konsist/自定义 lint 分层检查改为零依赖 JVM 单测 `architecture/LayeringTest`（离线缓存无该库）。
- §6 的调度器限定注解（`@IoDispatcher` 等）随 Hilt 一起属于 Phase 7；现阶段用构造注入 scope 达成同样的可测性。
- 类名与间接层的来历：`JmDNSDeviceDiscovery`/`ConnectionCoordinator` 是新构件与冻结旧同名类并存期的避让命名，`AppScanner`/`AppPacker`/`UpdateManager` 落在新包；并存期结束后**名字保留**（改名会打断按章节号引用的注释锚点）。`ConnectionTransport`/`LanSyncClientTransport` 是为可测性而生的抽象层，不是冗余间接层。

---

*（架构文档结束。导航：`AGENTS.md`；协议契约：`docs/SPEC.md`；测试：`docs/TEST-PLAN.md`；规范：`docs/CONVENTIONS.md`；裁决：`docs/DECISIONS.md`；方向与债务：`docs/ROADMAP.md`。）*
