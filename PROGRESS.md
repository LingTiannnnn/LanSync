# LanSync 重构进度（PROGRESS · 仓库唯一事实源）

> **用途**：本文件是跨客户端会话的**唯一事实源**（single source of truth）。客户端会话随时可能更换，任何阶段推进都以本文件 + `docs/` 为准。
> **维护规则**：**每阶段结束必须更新本文件并 `git commit`**。
> **重构范式**：**契约不变，实现重写**。线上协议以 `docs/SPEC.md`（v1.0 冻结）为唯一基线；目标架构见 `docs/ARCHITECTURE.md`；测试与验收见 `docs/TEST-PLAN.md`。
> **阶段编号说明**：本文件用**执行阶段**编号（按组件推进），与 `.qoder/specs/LanSync_重构计划_task-436.md` 的原始阶段编号不同，映射见 §2。

---

## 1. 当前状态快照

- **当前阶段**：**Phase 5 — UI 一次成型（设计系统 + 5 Tab + 全量覆盖层）** ✅ 完成；下一步 **Phase 6**（安全加固/协议版本化）或 **Phase 7**（工具链升级），待用户定
- **阶段状态**：✅ Phase 0/1/2/3/4/5 完成（Phase 4/5 运行时与 UI 视觉待真机验证）
- **测试基线**：**139/139 全绿**（Phase 5 末，与 Phase 4 同——UI 一次成型未新增 JVM 单测，Compose 非单测目标；`testDebugUnitTest` + `assembleDebug` 均通过，见 §4）。
- **旧生产代码**：🗑️ **已删除**（AppRepository/KtorServer/AppListClient/ConnectionManager/旧 JmDNSDiscovery/旧 scanner/packer/update + 2 旧测试）。legacy-known-issue L1–L5 随之全部消除。
- **接线状态**：✅ **已接线**——`MainViewModel` → `LanSyncGraph.get()` → `LanSyncRepository` 门面 → 全部新构件；App 运行时走**全新栈**。`ForegroundSyncService` 承载 start/stop 生命周期。**运行时行为待真机验证**（FGS/mDNS/连接/下载，无设备无法自动化）。

---

## 2. 阶段完成状态

| 执行阶段 | 主题 | 对应 task-436 | 状态 | 交付/验收 |
|---|---|---|---|---|
| **Phase 0** | 文档冻结（SPEC/ARCHITECTURE/TEST-PLAN） | 阶段 0 | ✅ 完成 | 三文档 v1.0；全量源码复核；T1–T6/TT1–TT4 决议 |
| **Phase 1** | 传输层骨架（DTO + Ktor 路由 + 客户端 + HashUtils 新语义） | 阶段 1（部分）+ 阶段 3 服务器契约 | ✅ 完成 | 95/95 全绿；`assembleDebug` 通过；构建卫生 4 项修复 |
| **Phase 2** | 发现与连接层（DeviceDiscovery/JmDNSDeviceDiscovery + ConnectionCoordinator + PairingHistoryStore；配对协议复用 Phase 1 InMemoryPairingStore） | 阶段 4（连接协作者）提前 | ✅ 完成 | 严格对齐 SPEC §6/§7；`DefaultConnectionCoordinatorTest` 21 例 + `InMemoryPairingStoreTest` 9 例；117/117 全绿；不接扫描/UI、未改旧码 |
| **Phase 3** | 扫描/打包/更新推荐（AppScanner + AppPacker + UpdateManager + LocalAppRepository + local_apps_cache.json） | 阶段 1/4 组件 | ✅ 完成 | 严格对齐 SPEC §5.3/§8/§3.2 + D2；`sync.UpdateManagerTest`(9)+`transfer.AppPackerTest`(6)+`localapps.LocalAppRepositoryTest`(5)+`ModelsTest`(+4)；141/141 全绿；不接 UI、未改旧码 |
| **Phase 4** | 前台服务 + 门面接线（UpdateCoordinator + DownloadInstallController + IconCache + LanSyncRepository 门面 + ForegroundSyncService + 切换 MainViewModel + 删旧码；手写 DI 组合根 LanSyncGraph） | 阶段 3+4+5 | ✅ 完成 | 门面 **158 行**（≤300 硬约束）；139/139 全绿 + assembleDebug；L1–L5 全消除；运行时待真机 |
| **Phase 5** | UI 一次成型（单一 Material3 设计系统 Color/Spacing/Theme/Typography + 底部 5 Tab + 全量覆盖层 + UiState 单一出口 + 字符串/颜色/间距零硬编码） | 阶段 5（UI） | ✅ 完成 | `assembleDebug` 通过；`testDebugUnitTest` 139/139 全绿（基线不变）；硬编码审计 0 违规；信息架构对齐 REPORT §2.12 |
| **Phase 6** | 安全加固与协议版本化（token / 剥离 sourcePaths / SHA-256） | 阶段 6 | ⏳ 待启动 | 互操作矩阵 |
| **Phase 7** | 收尾（工具链升级 / UI 拆分 / 文档对齐） | 阶段 2+7 | ⏳ 待启动 | — |

> **注**：工具链升级（Kotlin 2.x 等，task-436 阶段 2）**未纳入当前执行阶段序列**，按用户指示以组件重写优先；如需可在 Phase 7 合并处理。

---

## 3. 已裁决事项（决策登记册）

### 3.1 协议/查证类（Phase 0，详见 SPEC §10）
| 编号 | 事项 | 裁决 | 依据 |
|---|---|---|---|
| T1/TT1 | 旧版 APK 实物是否存在 | ✅ **存在**，后续功能测试阶段实机验证互操作矩阵 | 用户确认 2026-09-08 |
| T2 | `DeviceInfoResponse.version` | **死字段**，恒为默认 `"1.0"`，新代码禁止依赖；Phase 6 版本协商须用独立新字段 | `git log -S`/`git show ad89d61` 查证 |
| T3 | mDNS TXT 编码 | **不做字节级冻结**，仅应用层键值契约（`deviceName`/`instanceId` 两键，经 JmDNS `getPropertyString`） | 全仓查证无手写 TXT 解析 |
| T4 | `Build.MODEL` 编码 | 按 UTF-8 标准，沿用库默认，不增强 | 用户裁决 |
| T5 | 下载响应 Content-Type | 客户端**从不校验**；服务端 `.apk`→octet-stream、`.apks`→zip | 读 `AppListClient.performDownload` |
| T6 | 多网络 IP 分歧 | **仅承诺同一 Wi-Fi 两台设备**；两条 IP 取值路径原样保留，不增强 | 用户裁决 |
| TT3 | `autoAcceptKnown` 反向连接接受策略 | **有意设计但实现未完成**；改为干净语义（仅历史配对成功设备自动接受、陌生设备弹窗、显式拒绝即时 rejected）；两处旧缺陷经复核为真实代码行为 | 用户确认 2026-09-08；缺陷见 `AppRepository.handleIncomingRequest` L486–554 |

### 3.2 关键工程决策
| 编号 | 决策 | 内容 | 落地 |
|---|---|---|---|
| **D1** | MD5 语义统一 | 传输完整性**以 `X-MD5`（打包产物哈希）为唯一权威**；废弃 `expectedMd5` 双轨兜底；`AppInfo.md5` 降级为仅版本指纹 | ✅ Phase 1 `LanSyncClient`/`LanSyncRouting`（SPEC §8.3） |
| **D2** | AppScanner null 指纹 | **裁决②：接受现状**——`md5 ?: ""` 且 `isExtractable=true` 为合法态，不降级 | ✅ 写入 SPEC §8.4；重开条件：真机误伤正常应用 |
| **D3** | 错误协议 | 服务端统一 `LanSyncErrorDto(code,message)` + `LanSyncErrorCode` 枚举；`e.message` 只进 `FileLogger`；状态码保持 SPEC §3 | ✅ Phase 1 `LanSyncError`/`LanSyncRouting`（ARCH §7） |

### 3.3 legacy-known-issue（✅ 已于 Phase 4 全部消除：旧 AppRepository/KtorServer/AppListClient/ConnectionManager 等已删除，MainViewModel 切换到新门面）
| 编号 | 问题 | 位置 | 消除时机 |
|---|---|---|---|
| L1 | `forceStartSync()` 与 `start()` 重复 | `AppRepository.kt` L624–647 | Phase 4 门面切换、旧 `AppRepository` 整体删除 |
| L2 | 分层倒置 `import ui.components.preloadIcon` | `AppRepository.kt` L22/L901 | 同上 |
| L3 | 旧双轨 MD5（`downloadApp` 传 `expectedMd5`） | `AppRepository.kt` L1044 + 旧 `AppListClient` | Phase 4 接线切换到 `LanSyncClient`（D1） |
| L4 | 旧 `handleIncomingRequest` 的 TT3 两处缺陷 | `AppRepository.kt` L486–554 | Phase 4 接线切换到 `ConnectionCoordinator`（SPEC §7.7） |
| L5 | 旧 Ktor `e.message` 回显 | `KtorServer.kt` L113/172/206/248 | Phase 4 接线切换到 `LanSyncRouting`（D3） |

---

## 4. 测试基线

- **Phase 1 末基线**：`testDebugUnitTest` → **95 用例，0 失败 / 0 错误 / 0 跳过**（Gradle 8.13，离线，2026-09-08）。
  - `LanSyncRoutingTest` 33 · `LanSyncClientTest` 12 · `DownloadedFileNameTest` 10 · `ConnectionManagerTest`(旧) 10 · `ModelsTest` 9 · `InMemoryPairingStoreTest` 8 · `HashUtilsTest` 6 · `UpdateManagerTest` 3 · `AppConfigTest` 2 · `HashUtilsConsistencyTest` 2。
- **`assembleDebug`**：✅ BUILD SUCCESSFUL（含死依赖移除 + jetifier 移除后）。
- **Phase 2 末基线**：`testDebugUnitTest` → **117 用例，0 失败 / 0 错误 / 0 跳过**（= Phase 1 的 95 + `DefaultConnectionCoordinatorTest` 21 + `InMemoryPairingStoreTest` 增 1）。`assembleDebug` ✅ 通过。
- **Phase 3 末基线**：`testDebugUnitTest` → **141 用例，0 失败 / 0 错误 / 0 跳过**（= 117 + `sync.UpdateManagerTest` 9 + `transfer.AppPackerTest` 6 + `localapps.LocalAppRepositoryTest` 5 + `ModelsTest` 增 4）。`assembleDebug` ✅ 通过。
- **Phase 4 末基线**：`testDebugUnitTest` → **139 用例，0 失败 / 0 错误 / 0 跳过**（= 141 − 删旧 `ConnectionManagerTest` 10 − 删旧 `update.UpdateManagerTest` 3 + `UpdateCoordinatorTest` 4 + `DownloadInstallControllerTest` 7）。`assembleDebug` ✅ 通过（含 FGS Manifest/权限/资源合并）。**App 现运行全新栈**。

### 运行方式（本机实测，务必照此）
```powershell
# .\gradlew.bat 会因 GRADLE_USER_HOME=E:\... 下 wrapper dist 不完整而联网下载超时；
# 改用已完整的 wrapper-dist 二进制 + 已 populate 的默认缓存离线跑：
$env:GRADLE_USER_HOME="C:\Users\LingTian\.gradle"
& "C:\Users\LingTian\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" testDebugUnitTest --offline
```
> 详见 `docs/TEST-PLAN.md §7`。PowerShell 会把 JVM stderr 警告当 error 致 ExitCode 1，以 `BUILD SUCCESSFUL` 为准。

---

## 5. 决策日志（chronological）

- **2026-09-08 · Phase 0**：完成三份文档全量源码复核；确认协议字段无 `[TODO]`（旧协议细节均可源码确认）；T1–T6/TT1–TT4 决议归档。
- **2026-09-08 · Phase 1 复核**：核验 Phase 1 新构件（server/transfer/error/HashUtils）**忠实复现契约**（含 D1）；发现新构件**未接线**、6 项卫生项未落地。
- **2026-09-08 · 用户裁决（本次）**：① `forceStartSync`/分层倒置 → **legacy-known-issue**（旧码冻结，Phase 4 删除消除，不做卫生修改）；② AppScanner null 指纹 → **裁决②接受现状**（写入 SPEC §8.4 / D2）；③ 下一步执行 **Phase 2**（发现+连接层，非接线）。
- **2026-09-08 · 构建修复**：`settings.gradle.kts` `PREFER_PROJECT`→`PREFER_SETTINGS`（修机器 init 脚本无 `google()` 致 androidx 404）；移除死依赖 `gson`/`play-services-base`；移除 `android.enableJetifier`；删除重复 `unsafe.configuration-cache`。修复后 95/95 仍全绿 + `assembleDebug` 通过。
- **2026-09-08 · 流程**：建立本 `PROGRESS.md` 作为跨会话唯一事实源；约定每阶段结束更新 + `git commit`。
- **2026-09-08 · Phase 2 完成**：新建 `DeviceDiscovery`/`JmDNSDeviceDiscovery`（SPEC §6，注入 scope）、`ConnectionEvent`/`ConnectionCoordinator`/`DefaultConnectionCoordinator`（Actor 单点收敛，SPEC §7.4–7.7）、`PairingHistoryStore`（TT3 §7.7 干净语义）、`ConnectionTransport`（传输抽象，手写 Fake 测试，不改 Phase 1 `LanSyncClient`）。迁移 `ConnectionManagerTest`：协议 9 例落 `InMemoryPairingStoreTest`、`incomingRequests` 流落 `DefaultConnectionCoordinatorTest`。测试 **117/117 全绿** + `assembleDebug` 通过。**未接线、未改旧生产代码**（旧 `JmDNSDiscovery`/`ConnectionManager`/`AppRepository` 冻结并存，Phase 4 删除）。设计决策见 §6。
- **2026-09-09 · Phase 3 完成**：新建 `localapps/{InstalledAppScanner,AppScanner,LocalAppRepository}`、`transfer/AppPacker`、`sync/UpdateManager`（均与冻结旧件同名的**新包**并存：localapps/transfer/sync vs 旧 scanner/packer/update）。关键改进：UpdateManager 改为**纯比较**（不再自行 fetch，因 connectedDevices.appList 已由 ConnectionCoordinator 维护）；AppPacker 输出目录构造注入 + **移除死代码 MD5 digest**（D1）；LocalAppRepository 采「缓存骨架 + 后台刷新」且**不接 UI**（图标预加载改为暴露 `ScanResult.added/removedPackages` 交接线层，避开 L2 分层倒置）；AppScanner 落实 D2。迁移 `UpdateManagerTest`（3→sync 新类 9 例，补 findUpdates/calculateSyncDiffs 旧零覆盖）、`ModelsTest` +4 encodeDefaults 字节快照。测试 **141/141 全绿** + `assembleDebug` 通过。**未接线、未改旧生产代码**。设计决策见 §7。
- **2026-09-09 · Phase 4 完成（接线 + 删旧）**：新建 `sync/UpdateCoordinator`（combine+节流，可控时钟）、`transfer/DownloadInstallController`（下载/安装/进度，DownloadProgress/InstallStatus 迁入）、`cache/IconCache`（data 层三级缓存，修 L2）、`repository/LanSyncRepository` 门面（**158 行 ≤300**，纯委托）、`repository/LanSyncGraph` 组合根（手写 DI，late-bind 破 server↔coordinator↔pairing 环）、`service/ForegroundSyncService`（specialUse FGS + 通知 + Manifest 权限）、适配器 `LanSyncClientTransport`/`NotifyingPairingStore`/`SharedPrefsPairingHistoryStore`。**切换** MainViewModel→LanSyncGraph、start/stop→FGS、类型迁移、AppIcon 薄壳化、删 forceStartSync+按钮（L1）。**删旧码** 8 主 + 2 测试。**L1–L5 全消除**。测试 **139/139 全绿** + assembleDebug 通过。**运行时（FGS/mDNS/连接/下载）待真机验证**。设计见 §8。
- **2026-09-09 · Phase 5 完成（UI 一次成型）**：建立单一 Material3 设计系统——`ui/theme/Color.kt`（靛蓝品牌色板，唯一色值来源）、`Spacing.kt`（`LanSyncSpacing` 4dp 基栅格令牌 + `LocalSpacing` CompositionLocal + `LanSyncTheme.spacing` 访问器）、`Theme.kt`（完整 light/dark ColorScheme，**`dynamicColor` 默认关闭**以保跨设备一致设计系统）、`Typography.kt`（完整 M3 类型比例）；`strings.xml` 扩至全量 UI 文案。重写全部 10 个 UI 文件 + `MainActivity` + `MainViewModel` + `ForegroundSyncService`：**0 内联中文字面量 / 0 `Color` 字面量 / 0 裸 `.dp`**（grep 审计）；`UiState` 不可变 data class 单一出口。测试 **139/139 全绿**（基线不变）+ `assembleDebug` 通过。设计见 §9。

---

## 6. Phase 2 设计与落地（进行中，结束时更新）

**目标**：新建发现层与连接层组件，行为严格对齐 SPEC §6（mDNS）/§7（连接状态机与超时），**不接扫描/UI、不改动旧生产代码**（旧 `JmDNSDiscovery`/`ConnectionManager`/`AppRepository` 冻结并存）。

**命名约束**：旧 `JmDNSDiscovery`、`ConnectionManager` 已占用 `data/discovery`、`data/connection` 包内同名，故新构件用**不冲突的新名**并存（旧码 Phase 4 删除）。

**已落地构件**（全部编译通过、测试全绿）：
- `data/discovery/DeviceDiscovery.kt`（接口）+ `JmDNSDeviceDiscovery.kt`（SPEC §6 实现；**scope 构造注入**，修正旧类自建 scope 的 ARCH §6 违规；Android/JmDNS 耦合，编译校验 + 真机验收，非 JVM 单测目标）
- `data/connection/ConnectionEvent.kt`（事件密封接口 + `ConnectAttemptResult`）
- `data/connection/ConnectionCoordinator.kt`（接口，ARCH §3.1）+ `DefaultConnectionCoordinator.kt`（**Actor 单点收敛**：单一 `Channel<ConnectionEvent>` + 单消费协程持有全部可变状态，杜绝旧 `ConcurrentHashMap`+`StateFlow` 交错竞态；严格复现 SPEC §7.4 状态机 / §7.5 端口迁移 / §7.6 陈旧清理 / §7.7 TT3 干净语义；超时阈值全取自 `AppConfig`）
- `data/connection/PairingHistoryStore.kt`（接口 + `InMemoryPairingHistoryStore`；TT3 自动接受唯一判据；Phase 4 换 SharedPreferences 持久化实现）
- `data/connection/ConnectionTransport.kt`（传输抽象 + `ConnectOutcome` + `LocalIdentity`；使 coordinator 可用**手写 Fake** 虚拟时间测试，**不改动 Phase 1 已绿的 `LanSyncClient`**，Phase 4 由 `LanSyncClient` 适配实现）
- 配对协议：复用 Phase 1 `InMemoryPairingStore`（= 新架构 ConnectionManager 协议半部）

**测试**（`DefaultConnectionCoordinatorTest` 21 例 + `InMemoryPairingStoreTest` +1）：
- CS-1..CS-16 覆盖 SPEC §7.4–7.6 全部迁移（发现/连接成功/发送失败/拒绝/超时/心跳三档 1-4/快速重连/本地断开/远端断开/端口迁移/陈旧清理/去重）；CS-6（超时但已反向连接）折叠进 CS-16（已连接不重发）。
- CS-17a–d 覆盖 SPEC §7.7 TT3 干净语义（配对历史命中自动接受 / 陌生设备 PENDING 不自动放行 / 显式拒绝即时 rejected / 显式接受写入历史）。
- 迁移旧 `ConnectionManagerTest` #10（incomingRequests 仅 PENDING、timestamp 降序）→ coordinator 测试；#9（clearAll）→ `InMemoryPairingStoreTest`。
- 周期心跳「20s 触发一次 ping」用 `advanceTimeBy` 虚拟时间验证。

**关键设计决策**：
1. **命名避让**：旧 `JmDNSDiscovery`/`ConnectionManager` 冻结且占用同名，新构件用 `JmDNSDeviceDiscovery`/`ConnectionCoordinator`（协议半部复用 `InMemoryPairingStore`），Phase 4 删旧时无冲突。
2. **传输抽象 `ConnectionTransport`**：coordinator 依赖接口而非具体 `LanSyncClient`，测试用手写 Fake（避免 MockK 代理 final 类、且不触碰 Phase 1 绿代码）。
3. **测试策略**：状态迁移用**直接投递事件**驱动（确定性，规避周期循环时序脆弱）；仅「20s 触发 ping」用虚拟时间。
4. **TT3 落地**：`DefaultConnectionCoordinator` 实现 SPEC §7.7 干净语义（`PairingHistoryStore` 判据），修复旧两处缺陷；旧 `handleIncomingRequest` 仍冻结（L4）。
5. **updateState 忠实旧语义**：仅更新**已在 enriched** 的设备（需先发现），与旧 `AppRepository.updateDeviceConnectionState` 一致；测试按真实「发现→连接」流程驱动。

**未做（按边界）**：不接扫描/UI；未接线进 `AppRepository`（Phase 4）；`JmDNSDeviceDiscovery` 无 JVM 单测（Android 耦合，真机验收）。

---

## 7. Phase 3 设计与落地（扫描/打包/更新推荐）

**目标**：新建扫描/打包/更新推荐/本机应用仓库组件，严格对齐 SPEC §5.3（打包命名/结构）、§8（哈希语义 + D2）、§3.2（更新规则）；**不接 UI、不改旧生产代码**（旧 `AppScanner`/`AppPacker`/`UpdateManager` 冻结并存，Phase 4 删除）。

**命名/包**（ARCH §10 目标包结构：新件放**新包**，与冻结旧件同名但不同包，无冲突）：
| 组件 | 新位置 | 旧（冻结） |
|---|---|---|
| AppScanner | `data/localapps/AppScanner`（+ `InstalledAppScanner` 接口） | `data/scanner/AppScanner` |
| AppPacker | `data/transfer/AppPacker` | `data/packer/AppPacker` |
| UpdateManager | `data/sync/UpdateManager` | `data/update/UpdateManager` |
| LocalAppRepository | `data/localapps/LocalAppRepository` | （旧职责散在 `AppRepository`） |

**已落地构件**（编译通过、测试全绿）：
- `localapps/InstalledAppScanner`（接口）+ `AppScanner`（Android/PackageManager 实现，落实 **D2**：`md5 ?: ""` 且 `isExtractable=true`；非 JVM 单测目标）
- `localapps/LocalAppRepository`：`local_apps_cache.json` 读写（`Json{ignoreUnknownKeys}` 私有格式，SPEC §1.2 注）+ `localApps`/`isScanning` StateFlow + 「缓存骨架 `loadCacheSkeleton` + 后台刷新 `scanAndRefresh`」启动策略（ARCH §3.3）；`ScanResult` 暴露新增/移除包名交接线层做图标预加载（**不 import UI**，避开 L2）
- `transfer/AppPacker`：输出目录**构造注入**（可 `TemporaryFolder` 单测）+ **移除旧死代码 MD5 digest**（SPEC §8.1，D1）+ 命名走 `DownloadedFileName.serverArtifactName`（收敛 P7）；单包字节副本 `.apk` / split zip `base.apk`+`split_N.apk` `.apks`（SPEC §5.3）
- `sync/UpdateManager`：**纯逻辑**（`findUpdates`/`deduplicateUpdates`/`calculateSyncDiffs`），无 suspend/无网络/无 Context——因 `connectedDevices.appList` 已由 `ConnectionCoordinator` 维护，不再自行 fetch（旧实现内部 async fetch）

**测试**（新增 20 例 + 扩展 4 例）：
- `sync/UpdateManagerTest`(9)：迁移旧 3 例 dedup + 平级 deviceName 去重 + findUpdates（跳过系统应用/仅 remote>local/跳过本地缺失）+ calculateSyncDiffs 四类型 + 系统应用跳过
- `transfer/AppPackerTest`(6)：单包命名+字节一致、split zip 结构(base/split_1)、不可提取/空路径→null、**D1 哈希一致性**（split 产物哈希 ≠ 原始拼接摘要 ≠ 列表 md5）、clearCache
- `localapps/LocalAppRepositoryTest`(5)：hasCache、scanAndRefresh 写缓存+状态+added、loadCacheSkeleton 不触发扫描、跨次 added/removed 差异、损坏缓存自愈（删除+空）
- `ModelsTest` +4：encodeDefaults=false 字节快照（DeviceInfoResponse 省略 version、GenericStatusResponse()={}、AppInfo 省略 false 布尔默认、ConnectStatusResponse rejected 省略 accepted）

**关键设计决策**：
1. **同名不同包**：严格遵循 ARCH §10；旧件冻结，Phase 4 删除后新件成为唯一实现。
2. **UpdateManager 去 fetch 化**：新架构下设备 appList 由连接层维护，更新推荐回归纯函数——可完全单测、消除旧实现的并发 fetch 复杂度。
3. **LocalAppRepository 不接 UI**：图标预加载/刷新通知是 L2/连接层职责，本层只暴露 `ScanResult` 差异，接线层（Phase 4）消费——从源头避免重蹈 L2 分层倒置。
4. **AppPacker 去死代码**：删除旧 `createApksFile` 中从不使用的 MD5 digest（SPEC §8.1），哈希统一由服务端 `sendPackedFile` 对产物计算（D1）。

**未做（按边界）**：不接 UI；未接线进 `AppRepository`（Phase 4）；`UpdateCoordinator`（combine+节流编排）与 `DownloadInstallController` 归入 Phase 4；`AppScanner` 无 JVM 单测（Android 耦合，真机验收）。

---

## 8. Phase 4 设计与落地（前台服务 + 门面接线 + 删旧）

**目标**：把 Phase 1–3 的并行新构件**接线**成可运行的全新栈，用**组合门面**替代旧上帝类，引入**前台服务**承载生命周期，并**删除旧冻结代码**（消除 L1–L5）。

**门面（硬约束 ≤300 行）**：`repository/LanSyncRepository.kt` = **158 行**，纯「组合 + 委托 + 生命周期编排」，零业务逻辑：转发 11 个 StateFlow；start/stop（server.start→port→discovery→coordinator→updateCoordinator + discovery 流→RawDevicesUpdated）；委托连接/下载安装/扫描/刷新。

**组合根**：`repository/LanSyncGraph.kt`（手写 DI 单例）构建对象图；用**可空 late-bind 引用**打破 `server↔coordinator↔pairingStore` 构造环（notifyingPairing→coordinator、localIdentityProvider→server.getPort 均运行期解引用）。

**协作者**：
- `sync/UpdateCoordinator`：combine(localApps, connectedDevices)→节流→availableUpdates/syncDiffs；节流时间戳为单 collect 协程局部状态（消除旧 lastUpdateRecalculationMs 竞态）；`nowMillis` 可注入（可控时钟测试）
- `transfer/DownloadInstallController`：downloadApp/installApp/downloadAndInstallApp/文件管理 + downloadProgress/installStatus（D1，复用 LanSyncClient）
- `cache/IconCache`：data 层三级缓存（内存 Lru→磁盘 PNG→PackageManager 绘制）+ preload；**修 L2**（ui/AppIcon 薄壳化，data 不再 import ui）
- `service/ForegroundSyncService`：specialUse FGS，承载 repo.start/stop；常驻通知（端口/连接数 + 停止/打开）；START_STICKY；Manifest 加 service + FOREGROUND_SERVICE(_SPECIAL_USE) + POST_NOTIFICATIONS
- 适配器：`LanSyncClientTransport`（LanSyncClient→ConnectionTransport，映射 ConnectResult→ConnectOutcome）、`NotifyingPairingStore`（receiveRequest→coordinator.IncomingRequestReceived，不改 Phase 1）、`SharedPrefsPairingHistoryStore`（TT3 持久化配对历史）

**切换（UI）**：MainViewModel `AppRepository.getInstance`→`LanSyncGraph.get`；start/stop→`ForegroundSyncService.start/stop`；类型迁移（DownloadProgress/InstallStatus→DownloadInstallController、DownloadedFileInfo→LanSyncClient）；删 forceStartSync + DeviceListScreen 按钮（L1）；AppIcon 改用 IconCache；IncomingConnectionDialog 的 REQUEST_TIMEOUT_MS→InMemoryPairingStore；MainViewModel.extractPackageNameFromFile→DownloadedFileName.parsePackageName（P7 收敛）。

**删除旧码**（8 主 + 2 测试）：AppRepository、KtorServer、AppListClient、ConnectionManager、旧 JmDNSDiscovery、旧 scanner/packer/update 的 AppScanner/AppPacker/UpdateManager；ConnectionManagerTest、update.UpdateManagerTest。**L1–L5 全部消除**。

**测试**（139/139 全绿）：新增 `UpdateCoordinatorTest`(4)、`DownloadInstallControllerTest`(7)；删旧 13；门面/FGS/IconCache/AppScanner/JmDNSDeviceDiscovery 属 Android 耦合或纯委托，编译校验 + assembleDebug（运行时真机验收）。

**关键决策**：① 门面 ≤300 靠 UpdateCoordinator/DownloadInstallController 下沉 + 纯委托达成（158 行）；② 手写 DI（LanSyncGraph 单例）而非 Hilt，避免本阶段引入 KSP/插件风险（Hilt 留待后续，ARCH §4）；③ FGS 承载生命周期，ViewModel 只触发服务 + 观察 StateFlow（ARCH §8）；④ late-bind 破环（构造期不解引用）。

**风险与待办（真机）**：FGS 在 API34 的 specialUse 需 Play 说明；POST_NOTIFICATIONS 运行时请求尚未加（API33+ 通知可见性）；退后台存活/mDNS/连接/下载/安装 golden path 须真机验证（TEST-PLAN §6）。

---

## 9. Phase 5 设计与落地（UI 一次成型）

**目标**：全部 UI 一次成型——单一 Material3 设计系统、底部 5 Tab（设备/本地/远程/同步/文件）+ 全量覆盖层，信息架构对齐 REPORT §2.12。**硬性要求**：① `UiState` 不可变 data class 单一出口（禁止把仓库多路 StateFlow 散装暴露给 Composable）；② 所有字符串/颜色/间距走统一 theme，禁止硬编码。

**设计系统（`ui/theme/`，唯一来源）**：
- `Color.kt`：靛蓝品牌色板（Primary=靛蓝 #3F51B5 对齐启动器、Secondary=青绿、Tertiary=琥珀、Error=M3 红 + 中性色），`internal` 常量，仅供 `Theme.kt` 组装 ColorScheme。
- `Theme.kt`：完整 light/dark `ColorScheme`（M3 1.1.x 角色集，无 surfaceContainer*）；**`dynamicColor` 默认 `false`**（关键决策：保跨设备一致的「单一设计系统」，非 Material You 随壁纸变色；如需一行可开）；`object LanSyncTheme` 访问器（对齐 M3 `MaterialTheme` 惯例）经 `LocalSpacing` 暴露 `spacing`。
- `Spacing.kt`：`LanSyncSpacing` 令牌（4dp 基栅格 space2..space64 + icon*/appIcon*/radius*/stroke*/控件尺寸），`staticCompositionLocalOf` 注入。
- `Typography.kt`：补全 M3 类型比例（headlineSmall/title*/body*/label*），组件禁止内联 fontSize/letterSpacing。

**字符串（`res/values/strings.xml`，唯一来源）**：从 1 条扩至 ~150 条，覆盖 5 Tab + 5 覆盖层 + 通知 + VM 用户消息 + content description；含参数文案用位置化占位符 `%1$d/%1$s`。组件经 `stringResource(id, args)`、VM/Service/SAF 助手经 `getString(id, args)` 引用。

**改造范围**：重写 `MainActivity` + 10 个 `ui/components/*`（DeviceListScreen/AppListScreen/RemoteAppListScreen/SyncScreen/FileListScreen/DownloadProgressDialog/IncomingConnectionDialog/InitialScanOverlay/SaveStatusDialog/AppIcon）+ `MainViewModel`（消息改 `getString`）+ `ForegroundSyncService`（通知文案改 `getString`）。**签名稳定**：共享子组件（SearchBar/CategoryTabs/SectionHeader/UpdateItem/EmptyStateCard）签名不变，仅内部换 token；`AppIcon` 尺寸 `Int`→`Dp`（唯一签名变更）。

**硬性要求核验**：
1. **UiState 单一出口** ✅：`MainViewModel` 仅暴露 `val uiState: StateFlow<UiState>`（不可变 data class），仓库 11 路 StateFlow 经 `combine` 归约；Composable 只接 `UiState`/派生值 + 回调，不接裸 Flow。
2. **零硬编码** ✅（grep 审计，范围 `ui/` 非 theme 文件 + `MainActivity`）：内联中文字面量 **0**、`Color` 字面量（`Color(0x..)`/`Color.Green`/`Color.Gray`/`Color.Transparent`）**0**、裸 `\d+.dp` **0**（全部 `LanSyncTheme.spacing.*`）。清除的旧违规：DeviceListScreen 的 `Color.Green/Gray/0xFF2E7D32/0xFFF57F17/0xFF4CAF50`、AppListScreen 的 `Color.Transparent`、Theme 的紫粉模板色。

**关键决策**：① `dynamicColor` 默认关闭——「单一设计系统」优先于 Material You 个性化（可一行开启）；② 间距用 CompositionLocal 令牌（`LanSyncTheme.spacing`）而非散落 dimens.xml；③ 连接状态色全走 colorScheme 角色（CONNECTED=secondary、CONNECTING/RECONNECTING=tertiary、ERROR/TIMEOUT=error、DISCOVERED=outline、DISCONNECTED=onSurfaceVariant），删除所有裸色；④ Compose UI 非 JVM 单测目标，门禁为 `assembleDebug` 编译 + grep 硬编码审计 + 139 基线不变（视觉/交互真机验收）。

**遗留（非阻塞）**：① REPORT §2.12 提及的「强制启动同步」按钮已随 Phase 4 删除（= L1 legacy-known-issue，已裁决）；② 3 处 pre-existing 未用参数警告（`FileTabContent.saveTargetFileName`/`StatusCard.isScanningApps`/`SyncScreen.onRefreshDevice`）沿用旧签名未清；③ UI 视觉/暗色/大字号/横屏适配 + POST_NOTIFICATIONS 运行时弹窗须真机验证。
