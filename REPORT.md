# LanSync 项目技术分析报告

## 一、项目概述

### 1.1 项目定位

LanSync 是一款**基于局域网的 P2P Android 应用更新同步工具**（项目根目录 `README.md`）。核心能力：

- 通过 JmDNS 自动发现同一局域网内运行 LanSync 的设备
- 扫描本机已安装应用，与远程设备应用列表做智能版本对比，识别可更新应用并**推荐最佳来源**（多设备同包名时取最高版本、同版本取设备名排序）
- 支持单 APK 直传与 **Split APK（.apks）打包**传输
- 下载后自动调用系统/第三方安装器安装（无需 Root、Shizuku 或 ADB 权限）
- 支持将下载的安装包保存到外部存储（SAF）
- 全程走局域网 HTTP，不依赖公网

### 1.2 技术栈全景

| 层级 | 技术 | 版本 |
|---|---|---|
| 语言 | Kotlin | 1.9.20 |
| UI | Jetpack Compose + Material3（动态取色） | BOM 2023.10.01 / Compose 编译器 1.5.5 |
| 服务端 | Ktor Server（Netty 引擎） | 2.3.5 |
| HTTP 客户端 | OkHttp | 4.12.0 |
| 设备发现 | JmDNS | 3.5.8 |
| 序列化 | kotlinx-serialization-json | 1.6.0 |
| 异步 | kotlinx-coroutines | 1.7.3 |
| 生命周期 | lifecycle-runtime-ktx / viewmodel-compose / activity-compose | 2.6.2 / 2.8.1 |
| 其他 | documentfile（SAF）、FileProvider | 1.9.0 / core-ktx 1.12.0 |
| 构建 | Gradle 8.13 / AGP 8.13.2 | — |
| 测试 | JUnit4 + MockK + coroutines-test + ktor-server-test-host | — |

**SDK 级别**：minSdk 29（Android 10）、targetSdk/compileSdk 34，Java/Kotlin/JVM target 17。

**模块结构**：单模块应用，仅 `:app`（`settings.gradle.kts`）。依赖仓库使用阿里云镜像加速，release 构建开启 minify + shrinkResources。

### 1.3 应用清单与权限

`AndroidManifest.xml` 声明了 11 项权限：`INTERNET`、Wi-Fi 状态相关、**定位权限（作为获取 SSID/多播收发的历史兼容）**、`CHANGE_WIFI_MULTICAST_STATE`（多播锁）、`NEARBY_WIFI_DEVICES`（neverForLocation）、**`QUERY_ALL_PACKAGES`**（扫描全部应用必选，属于高风险权限，Play 上架敏感）。配置了：

- `usesCleartextTraffic=true` + `network_security_config.xml`（允许明文 HTTP）
- FileProvider authority `${applicationId}.fileprovider`，映射缓存目录 `apks/`、`downloads/`（`file_paths.xml`）
- 唯一 Activity：`MainActivity`（LAUNCHER）

---

## 二、功能模块分析

### 2.0 模块总览与分层

```
┌────────────────────────────── UI 层（Compose） ─────────────────────────────┐
│  MainActivity / LanSyncApp  5 个 Tab（设备、本地应用、远程应用、同步、文件）│
│  + 覆盖层对话框（下载进度、安装状态、连接请求、保存状态、首次扫描）          │
└──────────────────────────────┬─────────────────────────────────┘
                               │ UiState (StateFlow)
┌────────────────────── ViewModel 层 ────────────────────────────┐
│  MainViewModel —— 汇总 Repository 多个 StateFlow → UiState       │
└──────────────────────────────┬─────────────────────────────────┘
                               │ 直接调用
┌────────────────────── 仓库/编排层 ──────────────────────────────┐
│  AppRepository（单例，1180 行，连接编排、心跳、更新推荐的总控）    │
├──────────┬──────────┬──────────┬──────────┬──────────┬─────────┤
│AppScanner│JmDNS     │KtorServer│AppList   │AppPacker │Update   │
│(扫描)    │Discovery  │(服务端)  │Client    │(打包)    │Manager  │
│          │(发现)     │          │(客户端)  │          │(比较)   │
├──────────┴──────────┴──────────┴──────────┴──────────┴─────────┤
│  ConnectionManager(配对) / ApkInstaller(安装) / AppIconDiskCache │
│  HashUtils / NetworkUtils / FileLogger / AppConfig              │
└─────────────────────────────────────────────────────────────────┘
```

### 2.1 启动与初始化流程（`MainViewModel.kt`）

入口 `LanSyncApplication.onCreate` 仅初始化文件日志。`MainViewModel.init` 中：

1. **检查本地应用缓存** `hasLocalAppCache()`（`filesDir/local_apps_cache.json`）；不存在则显示 `InitialScanOverlay` 全屏遮罩 → 先 `scanLocalApps()` 再 `start()`
2. 有缓存则并行 `repository.start()` 与后台 `scanLocalApps()`

**关键点**：`AppRepository.start()`（`AppRepository.kt` L965-L988）是"全功能总开关"——启动 Ktor（随机端口）→ 注册回调 → 启动 JmDNS 发现 → 置 `isRunning`。**无前台服务，App 退后台或进程被杀后服务即停**。

### 2.2 本地应用扫描（`AppScanner.kt`）

- 通过 `PackageManager.getInstalledPackages` 枚举全部包（API 33+ 使用 `PackageInfoFlags`）
- 对每个包：读取 `sourceDir` + `splitSourceDirs` 组成 `sourcePaths` 并校验可读性；计算文件总大小；用 `HashUtils.kt` 对**所有 APK 文件内容做流式 MD5 合并摘要**作为版本指纹
- 判定 `isSystemApp`（FLAG_SYSTEM）、`isSplitApk`（sourcePaths>1）
- 读取失败/安全异常（如系统保护应用）→ 降级为 `isExtractable=false`、`md5=""`、`sourcePaths=[]`（该包仅展示不可传输）
- 结果写入 `filesDir/local_apps_cache.json` 作为启动缓存；随后对新增包做图标预加载、通知已连接设备刷新

### 2.3 设备发现（`JmDNSDiscovery.kt`）

- 获取 WiFi IP + 设备名（`Build.MODEL`），创建 JmDNS 实例并注册 `_lansync._tcp.local.` 服务，服务名 `LanSync_<hostname>_<instanceId>`，**TXT 记录携带 `deviceName` 与 `instanceId`**
- `instanceId` 为持久化 UUID（存 `SharedPreferences("lansync_device")`），用于**身份稳定识别**（即使 IP/端口变化也能识别同一设备）
- 监听 ServiceListener（added/removed/resolved）+ 60s 定时全量 `list()` 保活与陈旧设备清理
- 需持有 `MulticastLock` 才能收到多播包
- 自过滤：无 instanceId 的非 LanSync 服务、自身 instanceId 均忽略
- 输出 `discoveredDevices: Flow<List<DeviceInfo>>`（按 `ip:port` 去重的裸发现列表）

### 2.4 HTTP 服务端（`KtorServer.kt`）

Netty 引擎 + ContentNegotiation(kotlinx-json)。采用**回调注入**设计：`setAppListProvider`、`setPacker`、`setDisconnectHandler`、`setRefreshAppListHandler`、`setDeviceName`（同时创建 `ConnectionManager`）。**端口动态分配**（`port=0`），通过 `resolvedConnectors()` 取实际端口。路由详见第五章接口说明。

### 2.5 HTTP 客户端（`AppListClient.kt`）

- 两个 OkHttpClient：常规（15s 超时）+ 下载专用（connect 30s / read-write 120s / callTimeout 0 不限）
- 功能：发送连接请求、**轮询连接状态**（500ms 间隔、30s 超时，`ConnectResult{Accepted/Rejected/Timeout}`）、发连接响应、拉取应用列表/设备信息、ping、断开通知、刷新通知、**大文件下载 + 进度回调 + MD5 校验**
- 下载落盘目录 `cacheDir/downloads`，文件名 `pkg_下划线版本Code.apks`/`.apk`；校验逻辑：优先比对响应头 `X-MD5`（服务端实时计算），否则用调用方传入的 `expectedMd5`；不一致删除文件并报错
- 提供本地文件管理 API：列举下载文件、按名删除、按包名+版本查文件、包名从文件名逆向提取

### 2.6 连接配对（`ConnectionManager.kt`）

**配对采用"请求-轮询"模式**（非长连接）：

1. 发起方 POST `/api/connect/request`（携带 requestId/名称/IP/端口/instanceId/时间戳）
2. 接收方 `receiveRequest` 存入 `ConcurrentHashMap`，15s 无响应自动置 TIMEOUT，并通过 `incomingRequests: StateFlow` 驱动 UI 弹窗（`IncomingConnectionDialog.kt` 带倒计时自动拒绝）
3. 接收方响应后，发起方 GET 轮询 `/api/connect/status/{requestId}` 直到 accepted/rejected

`AppRepository.kt`（L386-L484）的 `connectDevice()` 负责编排，带 `connectingDevices` 去重锁；`handleIncomingRequest()` 处理反向连接（**已知设备自动接受**，发现过/连接过的设备免确认）。

### 2.7 心跳与连接状态机

连接建立后，Repository 对每台设备启动两个循环协程：

- **心跳 ping**（20s）：GET `/api/ping`；失败计数分三档推进状态：`RECONNECTING(不稳定)` → `RECONNECTING(尝试重连, 触发快速重连)` → `CONNECTION_TIMEOUT`（从已连接列表中移除）。恢复后状态回 `CONNECTED`
- **周期同步**（120s）：重新拉取对方 appList 并刷新
- `_enrichedDevices` / `_connectedDevices` 双列表：前者展示全部发现状态，后者只含已连接设备；**端口迁移**（同一 instanceId 换了 ip:port）时保留状态与 appList 无缝切换

### 2.8 APK 打包（`AppPacker.kt`）

- 单 APK：直接复制 `sourceDir` 为 `.apk`
- Split APK：将 `sourcePaths` 压缩为一个 **`.apks`（实为 zip，内部 base.apk + split_N.apk）**，压缩时同步计算 MD5 供响应头使用
- 输出目录 `cacheDir/apks`，启动时 `clearCache()` 清空

### 2.9 版本比较与更新推荐（`UpdateManager.kt`）

- `findUpdates`：并发（async/awaitAll）拉取各设备 appList，与本机对比，规则：**跳过系统应用**、仅 `remoteApp.versionCode > localApp.versionCode` 才算可更新
- `deduplicateUpdates`：按包名去重，取最高 versionCode；平级时取 `deviceName` 字母序更小者（"推荐最佳来源"）
- `calculateSyncDiffs` 另产出 `SyncDiff`：`NEWER_ON_REMOTE / ONLY_ON_REMOTE / SAME_VERSION / ONLY_ON_LOCAL` 四类差异
- 触发：`combine(localApps, connectedDevices)` 自动监听 + 5s 节流

### 2.10 下载→安装→保存链路

- 下载：`downloadApp()` 更新 `DownloadProgress`（0→99%，完成置 100/FAILED），UI 弹进度对话框（`DownloadProgressDialog.kt`）
- 安装：`installApp()` 先从下载目录取文件，然后 `ApkInstaller.kt` 用 **FileProvider + ACTION_VIEW Intent** 委托系统/第三方安装器（.apks 用 `application/zip` MIME）；`InstallStatus{Installing/Success/Failed}` 以 Snackbar 显示
- 保存：SAF `OpenDocumentTree` 选择目录 → `SaveStatusDialog.kt` 的 `copyFileToSafDirectory()` 流式复制，重名自动加时间戳，含 ENOSPC 专项错误提示

### 2.11 图标缓存（`AppIcon.kt` + `AppIconDiskCache.kt`）

- 三级：内存 LruCache(120 条，按字节计数) → 磁盘 PNG（`filesDir/app_icons`）→ PackageManager 绘制（AdaptiveIcon 取 foreground）
- App 列表变更时清理失效条目、预加载新包图标

### 2.12 UI 层

`MainActivity.kt` 底部 5 Tab：

1. **设备**：状态卡（运行开关/端口/"强制启动同步"）+ 设备卡片列表（按连接状态着色、连接/断开操作）
2. **本地应用**：搜索 + 分类 Tab（全部/用户/系统）
3. **远程应用**：多设备聚合、按包名去重取最高版本、复选框多选"拉取安装"
4. **同步**：设备 chips + 搜索 + "可更新"（可全选一键更新）与"版本差异"分组
5. **文件**：下载文件管理（多选、安装、保存到存储、删除）

覆盖层：下载进度、安装状态 Snackbar、保存状态、**传入连接请求弹窗**（倒计时自动拒绝）、首次扫描全屏遮罩。主题见 `Theme.kt`：Material3 动态取色（API 31+），fallback 紫粉色系。

### 2.13 日志（`FileLogger.kt`）

异步 Channel + 协程写入 `getExternalFilesDir/lansync_debug.log`，5MB 轮转 + 3 份备份，含级别/异常堆栈/JSON 便捷方法。**不写 logcat**。

---

## 三、数据模型设计（`Models.kt`）

| 模型 | 类型 | 关键字段 | 备注 |
|---|---|---|---|
| `AppInfo` | @Serializable | packageName, appName, versionName, versionCode(Long), sourcePaths, md5, isExtractable, fileSize, isSystemApp, isSplitApk | 网络传输的原子 payload |
| `DeviceInfo` | @Serializable | ipAddress, deviceName, port, instanceId, appList, connectionState, lastSeenTimeMs, connectionError | `displayKey=ip:port`，`identityKey=instanceId 优先，否则 name@ip` |
| `ConnectionState` | enum | DISCOVERED/CONNECTING/CONNECTED/ERROR/DISCONNECTED/RECONNECTING/CONNECTION_TIMEOUT | 驱动 UI 状态 |
| `UpdateInfo` | 普通 | localApp?, remoteApp, providerDevice, canUpdate | 可更新推荐 |
| `RemoteAppEntry` | 普通 | app, sourceDevice | 远程列表去重后的条目 |
| `SyncDiff` | 普通 | appInfo, localVersion?, remoteVersion, sourceDevice, diffType(NEWER_ON_REMOTE/ONLY_ON_REMOTE/SAME_VERSION/ONLY_ON_LOCAL) | 同步差异 |
| `ConnectRequestPayload` | @Serializable | requestId, requesterName/Ip/Port, requesterInstanceId, timestamp | POST 体 |
| `ConnectResponsePayload` | @Serializable | requestId, accepted, responderName, message | 响应体 |
| `IncomingConnectRequest` | 普通 | 请求派生 + status(PENDING/ACCEPTED/REJECTED/TIMEOUT) | 弹窗状态 |
| `DisconnectPayload`/`ConnectStatusResponse`/`DeviceInfoResponse`/`GenericStatusResponse`/`ConnectResponseBody`/`RefreshAppListPayload` | @Serializable | 各接口 payload | |

**序列化要点**：JSON 均 `ignoreUnknownKeys=true`/`isLenient=true`，保证版本演进兼容；`sourcePaths` 是**本机绝对路径，绝不能视为安全数据跨设备信任**（下载时用的是版本+md5，不直接用路径）。

### 本地持久化/缓存清单

| 位置 | 内容 | 生命周期 |
|---|---|---|
| `SharedPreferences("lansync_device")` | `device_instance_id`（UUID） | 永久 |
| `filesDir/local_apps_cache.json` | 最近一次应用的 AppInfo 列表 | 每次扫描覆盖 |
| `filesDir/app_icons/*.png` | 图标磁盘缓存 | 随应用卸载清理 |
| `cacheDir/apks/*` | 打包产物 | 启动即清空 |
| `cacheDir/downloads/*` | 已下载 .apk/.apks | 用户手动删除 |
| `getExternalFilesDir/lansync_debug.log(.bakN)` | 文件日志 | 5MB/3 备份轮转 |

**重置即"卸载"**——没有任何跨进程持续状态或数据库（无 Room/DataStore）。

---

## 四、HTTP 接口说明（Ktor，局域网明文 HTTP/1.1）

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|---|---|---|---|---|
| GET | `/api/ping` | — | `GenericStatusResponse{status:"pong"}` | 心跳探活 |
| GET | `/api/applist` | — | `[AppInfo]` | 当前本地应用列表（实时取 `_localApps`） |
| GET | `/api/deviceinfo` | — | `DeviceInfoResponse{deviceName, version}` | 设备信息 |
| POST | `/api/connect/request` | `ConnectRequestPayload` | `ConnectStatusResponse{status:"pending"}` / 409 重复 | 发起配对；成功后服务端登记请求并弹窗 |
| GET | `/api/connect/status/{requestId}` | — | `ConnectStatusResponse{status:pending\|accepted\|rejected, ...}` | 发起方轮询 |
| POST | `/api/connect/response/{requestId}` | `ConnectResponseBody{accepted}` | `ConnectResponsePayload` / 404 | 接收方回复 |
| GET | `/api/download/{packageName}` | — | 文件流 | 取该包最高版本下载；不可提取→403；打包失败→500；无→404 |
| GET | `/api/download/{packageName}/{versionCode}` | — | 文件流 | 精确版本下载（同上错误体系） |
| POST | `/api/disconnect` | `DisconnectPayload` | `GenericStatusResponse` | 主动断开通知 |
| POST | `/api/refresh-applist` | `RefreshAppListPayload{displayKey}` | `GenericStatusResponse` | 通知对方重新拉取本机列表 |

**文件下载响应头约定**：`X-MD5`（服务端对打包产物实时计算）、`X-File-Size`、`Content-Disposition: attachment; filename="..."`；Content-Type 按扩展名：`.apk`→octet-stream，`.apks`→application/zip。

**校验策略**：客户端优先用 `X-MD5` 校验，否则用调用方传的 `expectedMd5`（= 应用列表里的 md5）。

**安全现状（必须正视）**：全程无鉴权、无加密、纯明文 HTTP；错误响应直接回显 `e.message`；任何局域网设备都可调 `/api/applist`。这对"仅局域网信任环境"够用，但重构需评估风险边界。

---

## 五、核心数据流（时序）

**连接链路**：

```
A:B 发现(多播) → 用户点连接 → A发connect/request → B弹窗(15s倒计时)
→ B接受(post response) → A轮询status/...直到accepted → A加入_connectedDevices
→ 双向startHeartbeat(20s ping) + 首拉appList(5次重试) → 双方定期同步appList(120s)
```

**更新推荐链路**：

```
本地扫描 → _localApps + 缓存落盘
发现+连接 → _connectedDevices(含appList)
combine(localApps, connectedDevices) → 节流5s → UpdateManager.findUpdates(并发拉取)
→ deduplicate → _availableUpdates → UiState.availableUpdates → 同步Tab渲染
```

**下载安装链路**：

```
UI点击安装 → downloadApp → GET /download/{pkg}/{vc} → 服务端打包(apks/zip) → 流式传输(进度回调)
→ 本地写cacheDir/downloads → MD5校验 → DownloadProgress(COMPLETED)
→ (可选一键安装) installApp → FileProvider URI → ACTION_VIEW系统安装器
```

---

## 六、测试现状

均为 **JVM 单元测试**（`app/src/test`），无 instrumented/UI 测试：

- `ConnectionManagerTest`：配对状态机（10 用例）
- `UpdateManagerTest`：去重逻辑（3 用例）
- `ModelsTest`：序列化 roundtrip（8 用例）
- `AppConfigTest` / `HashUtilsTest` / `HashUtilsConsistencyTest`：配置与 MD5 工具

覆盖纯逻辑为主，**对 AppRepository 编排、Ktor 路由、下载校验流程没有自动化覆盖**（即使已引入 ktor-server-test-host 与 MockK 依赖也未充分利用）。

---

## 七、潜在重构风险与建议

### 7.1 高风险项（重构需优先设计）

1. **AppRepository 是"上帝类"**（约 1180 行）：连接编排、心跳、双列表维护、端口迁移、更新推荐、下载、安装、缓存、扫描全部于此。状态通过 12 个 `MutableStateFlow` 暴露，杂糅并发原语（ConcurrentHashMap + 协程 Job 表 + 多个 scope）。**建议**：按领域拆为 `ConnectionCoordinator`、`UpdateCoordinator`、`LocalAppRepository`、`DownloadInstallController`，Repository 降为组合门面。
2. **无前台服务/无进程保活**：服务端 + 心跳全在 Activity 生命周期内，退后台即失效。测试/实际使用依赖用户停留在前台。**建议**：引入 Foreground Service（通知常驻），或明确"前台使用"定位并在文档标注。
3. **净明文 HTTP + 无鉴权**：局域网内任何设备可枚举应用列表（含包名清单）、可拉流量。**建议**：增加设备配对后的 shared-secret/token 校验，或至少 HTTPS（自签证书+证书指纹校对）。
4. **MD5 校验系统的脆弱性**：MD5 非抗碰撞，且服务端 `sendZipFile` 重新计算的实际 MD5 与列表中的 `md5` 可能不一致（打包后的 zip 与原始拆分文件内容序不同——注意 `AppPacker.createApksFile` 与 `HashUtils.md5(paths)` 的字节序一致性问题，但 zip 头/条目元数据必然改变原始拼接哈希），客户端实际以 `X-MD5`（打包产物的哈希）为准，`expectedMd5` 仅作兜底——**这是隐式的两套哈希语义，易踩坑**。建议统一规范：要么校验原始内容哈希，要么全部以传输物哈希为准，并明确在文档中说明。

### 7.2 中风险项

5. **并发一致性隐患**：`ConnectionManager.incomingRequests` 的 `StateFlow.value` 在 `ConcurrentHashMap` 上的读写未加锁；Repository 中 `connectingDevices`/`heartbeatFailCounts` 等 map 与 StateFlow 的既有序列存在竞态窗口（如心跳超时与端口迁移并发）。**建议**：收敛到单一调度协程或用 `Mutex`/`atomicfu` 规范化状态转换。
6. **`sourcePaths` 明文跨设备传输**：`AppInfo` 含本机文件路径，`.applist` 接口原样暴露给所有发现方（包含 base apk 绝对路径）。**建议**：响应侧剥离 sourcePaths，仅保留元数据。
7. **命名/格式反推脆弱**：包名从文件名 `com_example_app_123.apks` 反推（`AppListClient.extractPackageName` 与 ViewModel 各实现一份，**逻辑重复**）；下载文件名强约定 `pkg_vc`。重名文件、含数字段的包名可能误判。**建议**：文件名附加真实包名元数据（如 `pkg=<包名>` query/header）或统一提取函数收敛复用。
8. **缓存数据可信度**：`local_apps_cache.json` 启动即用，若用户卸载/更新应用会导致陈旧数据短暂展示与错误"缺失"判断（`installApp` 报 not found 的根因常在此）。**建议**：启动缓存仅做骨架展示，后台校验刷新。

### 7.3 低风险项 / 清理项

9. **死依赖**：`com.google.code.gson` 与 `com.google.android.gms:play-services-base` 在代码中**无任何使用点**（已在搜索中核实），建议移除。
10. **无效能力与降级路径**：`forceStartSync()`（"强制启动同步"按钮）与正常启动逻辑重复；Android 12+ 的 `NEARBY_WIFI_DEVICES` 已声明但未见运行时请求逻辑（属于兼容声明）；定位权限更像历史遗留。
11. **路由重复**：`/api/download` 两个版本的 handler 有大量重复段，重构时可用同一处理函数参数化 packageName+versionCode 收敛。
12. **UI-Data 耦合倒置**：data 层 `AppRepository` 直接 import `ui.components.preloadIcon`，破坏分层依赖方向。**建议**：将图标预加载下沉到 data/icon 层或改为图标缓存直接提供方法。
13. **错误响应泄露内部异常**：Ktor 多处 `call.respondText("...e.message")` 将异常细节回传，建议统一错误枚举 + 日志留痕。
14. **MD5 fallback 语义**：`HashUtils.md5(listOf("/nonexistent/.."))` 对所有文件不可读时返回**空摘要的 MD5（d41d8...）**而非失败，会静默产生错误指纹，测试已将其"固化"为预期行为。建议改为 null 传播。

### 7.4 架构层建议（对"从零开始重构"）

- **引入 DI**（Hilt/Koin）管理服务实例与生命周期，替代手写单例与构造注入回调
- **网络层抽象**：统一定义 `LanSyncServer`（HTTP API）契约与 DTO，把 Ktor/Netty 实现与传输细节隔离
- **状态管理**：StateFlow 已可用，但建议用不可变 UiState + Reducer 模式替代 12 路手动 copy
- **离线数据**：用 Room 持久化"已知设备 + 最近 appList"以支持断网后的稳定性与快速恢复
- **测试策略**：为路由、下载/校验、状态机补服务端与单元测试；合理利用既有的 ktor-server-test-host 与 MockK 依赖
- **Gradle**：AGP 8.13.2 + Kotlin 1.9.20 + Compose 1.5.5 组合较旧，重构时可整体升级（Kotlin 2.x + Compose Compiler Plugin + BOM 2024+），同时解决 R8 规则与 `configuration-cache` 兼容性