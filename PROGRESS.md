# LanSync 重构进度（PROGRESS · 仓库唯一事实源）

> **用途**：本文件是跨客户端会话的**唯一事实源**（single source of truth）。客户端会话随时可能更换，任何阶段推进都以本文件 + `docs/` 为准。
> **维护规则**：**每阶段结束必须更新本文件并 `git commit`**。
> **重构范式**：**契约不变，实现重写**。线上协议以 `docs/SPEC.md`（v1.0 冻结）为唯一基线；目标架构见 `docs/ARCHITECTURE.md`；测试与验收见 `docs/TEST-PLAN.md`。
> **阶段编号说明**：本文件用**执行阶段**编号（按组件推进），与 `.qoder/specs/LanSync_重构计划_task-436.md` 的原始阶段编号不同，映射见 §2。

---

## 1. 当前状态快照

- **当前阶段**：Phase 5 之后已交付两次增量（① UI Recreate T0–T6；② 本机应用缓存骨架接线，见 §10）+ **一次补短板批次**（分层静态门禁 / DL-7…DL-10 / `POST_NOTIFICATIONS` / 死码清理，见 §11）；下一步 **真机互操作验收**（`docs/TEST-PLAN.md` §6，Phase 6 前置，用户已暂缓）或 **Phase 6**（安全加固/协议版本化），待用户定
- **阶段状态**：✅ Phase 0/1/2/3/4/5 完成 + 两次分支增量交付 + 补短板批次（Phase 4/5 运行时、UI 视觉**全部待真机验证**）
- **分支状态**（2026-10-04 裁决，2026-10-05/06 两次执行）：✅ **`main` 是唯一开发线**，当前 HEAD `4228845`。两条工作分支均已以**纯 fast-forward** 合回并**保留作历史线、不再在其上开发**：`qoder/UI-Recreate`（UI Recreate + 缓存骨架 + 文档一致性）、`qoder/Quality-Gate`（§11 补短板批次）。新工作一律从 `main` 开分支。⚠️ **`origin` 尚未推送**（GitHub 经代理 `127.0.0.1` 不可达）：`origin/main` 仍停在重构前快照 `d1a71fc`、`origin/qoder/UI-Recreate` 在 `66a6291`、`qoder/Quality-Gate` **从未推送**；网络恢复后需 `git push origin main`（**推送须用户另行批准**）。本地分支**均未设 upstream**。
- **测试基线**：**147/147 全绿**（2026-10-05，15 个文件，0 失败/0 错误/0 跳过；`assembleDebug` 通过）。= 此前 139 + `LanSyncClientTest` 增 6（DL-7/8/9/10）+ `LayeringTest` 2，见 §4。
- **UI 配色**：**Teal 青绿**（`ui/theme/Color.kt`，自 UI Recreate `1b05ebb` 起）。⚠️ 本文件 §5/§9 的 Phase 5 记录写的是**靛蓝**——那是当时实现，**已过期**，现行以 §10.1 与代码为准。启动器图标底色也已同步改为 Teal `#00696B`（`res/values/colors.xml`）。
- **旧生产代码**：🗑️ **已删除**（AppRepository/KtorServer/AppListClient/ConnectionManager/旧 JmDNSDiscovery/旧 scanner/packer/update + 2 旧测试）。legacy-known-issue L1–L5 随之全部消除。
- **接线状态**：✅ **已接线**——`MainViewModel` → `LanSyncGraph.get()` → `LanSyncRepository` 门面 → 全部新构件；App 运行时走**全新栈**。`ForegroundSyncService` 承载 start/stop 生命周期。**运行时行为待真机验证**（FGS/mDNS/连接/下载，无设备无法自动化）。
- **文档集变更（2026-10-06，用户有意删除）**：`AGENT-HANDOVER.md`（52KB 交接快照，**从未入库**，故 git 不可恢复）与 `docs/compose/spec/` 下三份 UI Recreate 评审留档（`FEEDBACK.md`、`ui-recreate-final-review.md`、`ui-recreate-review-notes.md`，已入库、删除已提交）均已移除。**本文件中「交接文档 §N」一律指前者，其内容已不可查**；评审的结论性事实（2 个 critical 及修复）已固化在 §10.1、死符号清单已内联进 §10.4，不随文件删除而丢失。现存文档集：`docs/SPEC.md`、`docs/ARCHITECTURE.md`、`docs/TEST-PLAN.md`、`docs/compose/spec/{ui-recreate,local-apps-cache-skeleton}.md`、`REPORT.md`。

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
| **UI Recreate**<br>（Phase 5 后分支交付，**非执行阶段**） | Teal M3 设计系统换色 + edge-to-edge + 5 屏与覆盖层重写（T0–T6） | — | ✅ 完成（**代码级**） | `1b05ebb` + `80921ad`；`assembleDebug` 通过、139/139 基线不变；独立评审 2 critical + 若干 major **均已修**；**真机视觉验收未做**（§10.4） |
| **缓存骨架接线**<br>（Phase 5 后分支交付，**非执行阶段**） | 修「冷启动像被清空、从 0 全量重扫」——启动即展示本机应用列表 | — | ✅ 完成 | `9ed1fce` + `5c7b2ee`；`LanSyncRepository.loadLocalAppCache()` ← `MainViewModel.autoStart` 无条件先调；139/139 全绿（§10.2） |
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
- **Phase 5 末 / UI Recreate / 缓存骨架接线**：基线**不变，仍 139**（Compose UI 非 JVM 单测目标；两次分支交付未增删用例）。
- **当前 HEAD 复核（2026-10-04，`66a6291`）**：`testDebugUnitTest` → **139 用例，0 失败 / 0 错误 / 0 跳过**（实测汇总 `app/build/test-results/testDebugUnitTest/*.xml`，14 个文件），`BUILD SUCCESSFUL`。**这是首次在当前 HEAD 复跑验证**，此前「139/139」仅为文档记录、仓库内无运行证据。
- **补短板批次（2026-10-05）**：`testDebugUnitTest` → **147 用例，0 失败 / 0 错误 / 0 跳过**（**15 个文件**）= 139 + `LanSyncClientTest` 12→**18**（DL-7/8/9/10）+ 新增 `architecture/LayeringTest` **2**。`assembleDebug` ✅ 通过（`app-debug.apk` 62MB 产出）。⚠️ **DL-6 经查证不可覆盖**（OkHttp 4.12 的 `Response.body` 实际永不为 null），详见 §11.4 与 `docs/TEST-PLAN.md §4`。

### 运行方式（本机实测 2026-10-04，务必照此）
```bash
cd "E:/S.H.I.T/LanSync"
GRADLE_USER_HOME="C:/Users/LingTian/.gradle" ./gradlew.bat testDebugUnitTest --offline --no-configuration-cache --console=plain
GRADLE_USER_HOME="C:/Users/LingTian/.gradle" ./gradlew.bat assembleDebug    --offline --no-configuration-cache --console=plain
```

三条前提，缺一即失败：

1. **`GRADLE_USER_HOME` 必须覆盖为 `C:\Users\LingTian\.gradle`**。机器级默认值 `E:\S.H.I.T\Gradle\GradleRepository` **缺全部测试依赖**（`junit:4.13.2`、`io.mockk:mockk:1.13.8`、`kotlinx-coroutines-test:1.7.3`、`ktor-server-test-host:2.3.5`），`--offline` 下 `compileDebugUnitTestKotlin` 必然失败。主源码编译不受影响，所以症状是「只有测试跑不起来」。
2. **`local.properties` 必须存在**，内容至少 `sdk.dir=E\:\\S.H.I.T\\Android SDK`。它被 `.gitignore` 忽略、且 `66a6291` 已把它从版本控制移除 → **新克隆/新机器上需手工重建**，否则报 `SDK location not found`（本机 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 均为空，无法兜底）。⚠️ **切分支也会把它删掉**（2026-10-05 实测踩到）：`main`(`d1a71fc`) 上该文件仍被**跟踪**，`git checkout main` 会用跟踪版**静默覆盖**磁盘上那份被忽略的文件（git 对 ignored 文件不告警），随后 FF 到含 `66a6291` 的提交时重放 `git rm` → 文件消失，而 `git status` 干净。恢复：`git show 66a6291^:local.properties > local.properties`。**切分支后先 `ls local.properties`。**
3. **`./gradlew.bat` 可直接用**。⚠️ 旧注记「`GRADLE_USER_HOME=E:\` 下 wrapper dist 不完整、会联网下载 `gradle-8.13-bin.zip` 超时」**已证伪**：两处 dist 均完整解开，`./gradlew.bat --version --offline` 正常输出 `Gradle 8.13 / Launcher JVM 17.0.20.1`，无联网。**不再需要绕道发行版自带的 `...\dists\gradle-8.13-bin\<hash>\gradle-8.13\bin\gradle.bat`**；仅当 C:\ 缓存也不可用时才回落到那条旧命令。

> **判定以输出里的 `BUILD SUCCESSFUL` 为准**：PowerShell 会把 JVM stderr 警告当 error 致 `ExitCode=1`；且**不要把输出管道给 `tail`**（管道会把 exit code 变成 `tail` 的 0，把 BUILD FAILED 掩盖成「成功」）。
> **环境**：JDK 17.0.20.1（`C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`）、Gradle 8.13、Android SDK `E:\S.H.I.T\Android SDK`（`buildToolsVersion = "35.0.0"` 已固定，避免联网下载）。工具链版本**三者绑死**：Kotlin 1.9.20 ↔ Compose 编译器 1.5.5 ↔ Compose BOM 2023.10.01（material3 1.1.x），要升必须整体升（Phase 7）。
> **机器级 init 脚本** `E:\S.H.I.T\Gradle\init.d\init.gradle` 只注入 `aliyun/public + mavenLocal + mavenCentral`（**无 `google()`**）；`settings.gradle.kts:17` 的 `PREFER_SETTINGS` 是为此而改，**不要改回去**。
> 详见 `docs/TEST-PLAN.md §7`。

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
- **2026-09-09 · Phase 5 完成（UI 一次成型）**：建立单一 Material3 设计系统——`ui/theme/Color.kt`（靛蓝品牌色板，唯一色值来源；⚠️ **后于 2026-09-12 被 UI Recreate 换成 Teal，见 §10.1**）、`Spacing.kt`（`LanSyncSpacing` 4dp 基栅格令牌 + `LocalSpacing` CompositionLocal + `LanSyncTheme.spacing` 访问器）、`Theme.kt`（完整 light/dark ColorScheme，**`dynamicColor` 默认关闭**以保跨设备一致设计系统）、`Typography.kt`（完整 M3 类型比例）；`strings.xml` 扩至全量 UI 文案。重写全部 10 个 UI 文件 + `MainActivity` + `MainViewModel` + `ForegroundSyncService`：**0 内联中文字面量 / 0 `Color` 字面量 / 0 裸 `.dp`**（grep 审计）；`UiState` 不可变 data class 单一出口。测试 **139/139 全绿**（基线不变）+ `assembleDebug` 通过。设计见 §9。
- **2026-09-11 · UI Recreate 交付（T0–T6，`1b05ebb` + `80921ad`）**：**配色从靛蓝整体换成 Teal 青绿**（交接包 `E:\S.H.I.T\UI Design\`）；`MainActivity` 改 `enableEdgeToEdge()` 并删除不透明系统栏色赋值；TopBar 语境标题 + 同步页 Contextual TopBar；新增 `CommonComponents.kt`/`IdentityStrip.kt`/`Shape.kt`；配对 UI 由 `Dialog` 改 `ModalBottomSheet`。因 material3 1.1.x 无 `surfaceContainer*`，自建 `LanSyncContainerColors` + `LocalContainers` 四档兼容层。独立评审查出 **2 个 critical（设备页双 `statusBars` 留白、`rememberSaveable(DeviceInfo)` 不可 Bundle 化致崩溃）+ 若干 major，均已修**；评审结论 "Ready for device-side T1/T6 visual acceptance"。基线不变 139/139 + `assembleDebug` 通过。**真机视觉验收未做**。设计见 §10.1。
- **2026-09-23 · 本机应用缓存骨架接线（`9ed1fce` + `5c7b2ee`）**：修「冷启动看起来应用列表被清空、从 0 全量重扫」。根因**不是持久化丢失**，而是 `LocalAppRepository.loadCacheSkeleton()` 早已实现且有单测、**但生产接线从未调用**（Phase 4 切换门面时漏接）。改法：门面加转发 `loadLocalAppCache()`，`MainViewModel.autoStart` 在 `hasLocalAppCache()` 分支**之前无条件**先调（顺带避免 TOCTOU）。基线不变 139/139。设计见 §10.2。
- **2026-09-25 · 构建卫生（`66a6291`）**：`.gitignore` 增补工具链/SDK 缓存目录（`.sdk-dl/`、`.qoder/`、`.trae/` 等），并**把 `local.properties` 从版本控制移除**（用的是 `git rm` 而非 `--cached`，文件同时从工作树删除）→ 新克隆需手工重建，见 §4 前提 2。
- **2026-10-04 · 基线复跑 + 文档一致性修正**：① 在当前 HEAD 首次实跑 `testDebugUnitTest` → **139/139 全绿**（此前仅为文档记录）；② 证伪「必须绕过 `gradlew.bat`」的旧注记，改为「wrapper 可直接用，但 `GRADLE_USER_HOME` 必须指向 C:\」（§4 / `docs/TEST-PLAN.md` §7 同步修正）；③ 补记 Phase 5 之后的两次分支交付（新增 §10），并修正 §5/§9 里已过期的「靛蓝色板」表述。
- **2026-10-04 · `main` 去向裁决（用户拍板）**：选择「本地 FF 合并 + 之后在 `main` 上开发」，否决「`main` 只作历史基线」。执行 `git checkout main && git merge --ff-only qoder/UI-Recreate` → `main` 快进 13 个提交至 `68bfa63`（`main` 原本就是 merge-base，故为纯 FF：无冲突、无 merge commit、不重写历史）。**理由**：`origin/HEAD → main`，GitHub 默认分支若停在 `d1a71fc`，任何人 clone 默认拿到重构前的上帝类代码；同时消除「下个 agent 切错分支」的风险。**边界**：`--ff-only` 保证只在能快进时才动；`qoder/UI-Recreate` 分支保留不删；**未推送**（当时 GitHub 经代理不可达），`origin/main` 保持 `d1a71fc` 不变。回退方式：`git reset --hard d1a71fc`（破坏性命令，需用户明示才跑）。
- **2026-10-04 · 真机验收暂缓**：用户明确**跳过交接文档 §8 第 2 步**（真机互操作验收）直接处理 `main` 去向。⚠️ 注意该步在计划中被定位为 **Phase 6 的前置**（协议版本协商与鉴权会碰互操作，无真机兜底不宜动），所以启动 Phase 6 前需重新评估这一缺口，见 §10.4。
- **2026-10-05 · 补短板批次（`qoder/Quality-Gate`）**：按交接文档 §8 第 4 步做完四项——① 分层静态门禁 `architecture/LayeringTest`（2 例，**零新依赖**；Konsist 不在本机离线缓存内故不用）；② `POST_NOTIFICATIONS` 运行时请求；③ 删 `LanSyncMotion` 整组死码；④ 补 DL-7/8/9/10（`LanSyncClientTest` 12→18）。**查证 DL-6 在 OkHttp 4.12 下不可覆盖**（`Response.body` 实际非空）→ 保留防御分支、不写断言。**一处行为改进（显式标注）**：空落盘分支补 `destination.delete()`，消除 0 字节残留被列进文件页。测试 **147/147 全绿** + `assembleDebug` 通过。过程中踩到 **`local.properties` 被 FF 合并删除**（已补记进 §4 前提 2）。详见 §11。

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

> ⚠️ **本节记录的是 Phase 5 交付当时（`681001f`）的设计。配色、系统栏写法、组件族已被 §10.1 的 UI Recreate 覆盖**——尤其**色板已由靛蓝改为 Teal**。设计系统的分层与硬约束（唯一色值来源 / `UiState` 单一出口 / 零硬编码 / `dynamicColor` 默认关闭）仍然成立。

**目标**：全部 UI 一次成型——单一 Material3 设计系统、底部 5 Tab（设备/本地/远程/同步/文件）+ 全量覆盖层，信息架构对齐 REPORT §2.12。**硬性要求**：① `UiState` 不可变 data class 单一出口（禁止把仓库多路 StateFlow 散装暴露给 Composable）；② 所有字符串/颜色/间距走统一 theme，禁止硬编码。

**设计系统（`ui/theme/`，唯一来源）**：
- `Color.kt`：~~靛蓝品牌色板（Primary=靛蓝 #3F51B5 对齐启动器）~~ ⚠️ **已过期，现行是 Teal 青绿色板**（Primary40 `#00696B`、亮色背景 `#F6FAF9`、暗色 `#0E1416`、Tertiary 保留琥珀语义、启动器底色同步为 `#00696B`）——见 §10.1 与代码。`internal` 常量，仅供 `Theme.kt` 组装 ColorScheme 的定位不变。
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

---

## 10. Phase 5 之后的两次分支交付（`qoder/UI-Recreate`）

> **本节补记长期滞后的内容**：Phase 5（`681001f`）之后分支上又交付了两次增量，此前**完全没写进本文件**——以致照本文件干活会把配色决策理解反（§9 写靛蓝、代码是 Teal）、把已交付的增量当成还没做。
> 提交序列：`81af6cf`（分支文件提交）→ `1b05ebb`（UI Recreate T0–T6）→ `80921ad`（UI 打磨与崩溃加固）→ `6d09076`（版本配对注记）→ `9ed1fce` + `5c7b2ee`（缓存骨架接线 + 特性文档）→ `66a6291`（构建卫生，**HEAD**）。
> 特性文档：`docs/compose/spec/ui-recreate.md`、`docs/compose/spec/local-apps-cache-skeleton.md`（均 `status: delivered`）。三份评审留档（`ui-recreate-review-notes.md` / `ui-recreate-final-review.md` / `FEEDBACK.md`）**已于 2026-10-06 由用户删除**，其结论性事实已固化在 §10.1。

### 10.1 交付① UI Recreate（T0–T6）

**设计源真相在仓库外**：`E:\S.H.I.T\UI Design\`（UI 设计交接包）。改 UI 前必读。

- **T0 配色换成 Teal**：`Color.kt` 整体替换为交接包色板——Primary 信任青绿（`TealPrimary10..95`，Primary40 `#00696B`）、Secondary 中性青灰、Tertiary 琥珀（保留「可更新」语义）、Error M3 标准红、Neutral 青绿微染；亮色背景 `Neutral99=#F6FAF9`、暗色 `SurfaceDark=#0E1416`（非纯黑）；新增 light/dark surface 容器各四档。启动器底色 `res/values/colors.xml` 同步改为 `#00696B`（**已不是靛蓝**）。⚠️ **本节是现行配色的唯一有效记录，§9 的「靛蓝」已过期**。
- **T0 Theme**：新 light/dark `ColorScheme`；`dynamicColor` **仍默认关闭**（Phase 5 决策不变）；因 `material3 1.1.x` **没有 `surfaceContainer*` 角色**，自建 `LanSyncContainerColors` + `LocalContainers` 四档兼容层（升级 BOM 后可考虑收敛）；注入 `LanSyncShapes`；`Spacing.kt` 扩圆角与列表/触控尺寸；新增 `Shape.kt`（`LanSyncShapes` + `LanSyncMetrics`）。
- **T1 Edge-to-edge**：`MainActivity.onCreate` 调 `enableEdgeToEdge()`；**删除** `window.statusBarColor`/`navigationBarColor` 不透明赋值，只按 luminance 设系统栏图标亮暗；`NavigationBar` 用 `LanSyncTheme.containers.default` + `WindowInsets.navigationBars`（消小白条）。未动 ViewModel/网络/业务逻辑。
- **T2 导航与 TopBar**：TopBar 改语境标题（设备/本地应用/远程应用/同步/文件），去掉页内大号「LanSync」；同步页 `selectedUpdates` 非空时切 Contextual TopBar（关闭 / 已选 n / 清空，容器 `containers.high`）+ `BatchBar`；导航指示胶囊色 `primaryContainer`；`selectedTab` 用 `rememberSaveable`。
- **T3 共享组件**：新增 `ui/components/CommonComponents.kt`（`StatusChip` / `LanSyncFilterChip` / `EmptyState` / `MeshHint` / `SummaryCard` / `BatchBar` / `SectionHeader` / `SearchBar` / `UpdateAccentBar`，含双主题 Preview）；列表行 `minHeight 72` + `containers.low/highest`；更新项左侧 tertiary 竖条。
- **T4 五屏组装**：新增 `ui/components/IdentityStrip.kt`（primaryContainer 贴 statusBars + HeroStats + Switch）；设备页 `MeshHint` + 已连接/发现中分区 + 停止态 CTA；同步页可更新/版本差异双模式（`FilterChip`，兼容 M3 1.1）；文件页 `SummaryCard` + 空态 CTA。
- **T5 Overlays**：**配对 UI 从 `Dialog` 改成 `ModalBottomSheet`**（顶圆角 28、返回键可关、倒计时自动拒绝）；下载 Dialog + 进度保留；安装反馈改 Snackbar（自动消失/滑动关闭）；首扫遮罩 scrim + 居中卡片；新增 `operationMessage` 底部短反馈。
- **独立评审**（本项目约定：大改动走独立子代理评审）：查出 **2 个 critical**——设备页双 `statusBars` 留白、`rememberSaveable(DeviceInfo)` 因 `DeviceInfo` 不可 Bundle 化而崩溃——**均已修复**；major（安装 Snackbar 关闭后不再显示、滤镜 Chip 视觉高 36dp 已补 `minimumInteractiveComponentSize()` 保触控 ≥48）也已修。评审最终结论：**"Ready for device-side T1/T6 visual acceptance."**

### 10.2 交付② 本机应用缓存骨架接线

- **修的 bug**：每次冷启动看起来「应用列表被清空、从 0 全量重扫」。
- **根因不是持久化丢失**：`LocalAppRepository.loadCacheSkeleton()`（Phase 3 就实现、`LocalAppRepositoryTest` 含骨架与损坏自愈用例）**从未被生产接线调用**——Phase 4 切换门面时漏接。
- **改法**：`LanSyncRepository` 加转发 `fun loadLocalAppCache(): Int = localAppRepository.loadCacheSkeleton()`（门面 158 → **164 行**，仍 ≤300 硬约束）；`MainViewModel.autoStart` 在 `hasLocalAppCache()` 分支判断**之前无条件**先调它（同时避免 TOCTOU）→ 有缓存则启动即有列表，后台再静默全量校验刷新；无缓存/损坏（读失败自愈删除）才走 `needsInitialScan` 全屏首扫。
- **边界**：`LocalAppRepository` / 扫描器 / UI **均未改**；不为两行接线新建 Android 测试栈（接线由既有单测 + 独立评审覆盖）。
- **已接受的成本**：无缓存时 `loadCacheSkeleton` 仍会读一次 JSON；有效但为 `[]` 的空列表缓存仍会跳过首扫遮罩（`hasCache` 既有语义，未在本次扩大范围）。

### 10.3 工程硬约束复核（两次交付后仍成立）

`UiState` 单一出口 ✅；UI 零硬编码（中文走 `strings.xml`、色值走 `Color.kt`、间距走 `LanSyncTheme.spacing.*`）✅ 但**仍靠人工 grep 审计**；门面 ≤300 行（当前 **164**）✅；`data/**` 不 import `ui.*` ✅。
**2026-10-05 起后两条已有自动门禁**：`architecture/LayeringTest` 断言「`data/**` 不 import `ui.*`」+「门面 ≤300 行」，随 `testDebugUnitTest` 跑，ARCH §8.3/§12.3 由「❌ 没做」转为「✅ 已落地（非 CI 形态）」。UI 零硬编码**未自动化**——`@Preview` 函数里合法使用裸 `.dp`，机械断言会大量误报，需先约定豁免规则。

### 10.4 未完成 / 待真机（别把「已交付」当「已验收」）

- **UI Recreate 的真机视觉验收全未做**：三键/手势导航白条目视、Dynamic Type 最大字号、暗色对比度实测、reduce-motion、横屏、业务回归（连接/拉取/安装/保存）。T6 任务项全勾，但那是**代码级**通过。
- **已知残留**：`MeshHint` 尾部 `StatusChip` 恒显「在线」（`onlineCount=0` 时文案不对，`CommonComponents.kt`）；`LanSyncMetrics`（`Shape.kt`）与 `LanSyncSpacing`（`Spacing.kt`）尺寸表**部分重复**，有漂移风险，建议合并成一个访问器。
- ~~**死代码**：`ui/theme/Theme.kt` 的 `LanSyncMotion` / `DefaultMotion` / `LocalMotion` / `LanSyncTheme.motion` 整组无引用~~ → ✅ **已于 2026-10-05 删除**（见 §11.3）。
- **其余死符号未清**（2026-09-25 逐个 grep 验证「只有定义、全仓无引用」；原记录在已删除的交接文档 §6.5，现内联于此以免失传，**清理前需重新 grep 复核**）：
  - *单点死符号*：`DeviceListScreen.StatusCard`、`IncomingConnectionDialog.IncomingConnectionDialog`（兼容壳，`MainActivity` 只调 `IncomingConnectionSheet`）、`AppIconDiskCache.{batchPut,clear,size,totalBytes}`、`IconCache.removeStale`、`LanSyncClient.{sendConnectResponse,clearDownloads}`、`LanSyncRepository.downloadApp`（UI 走 `downloadAndInstallApp`）、`FileLogger.{getLogFilePath,clearLog,shutdown}`、`LanSyncErrorCode.SERVER_NOT_READY`、`InMemoryPairingStore.{removeRequest,clearAll}`、`UiState.isLoading`、`Models.DeviceInfoResponse.version`（= 已裁决的死字段 **T2**，禁止复用）
  - *仅测试引用*：`InMemoryPairingHistoryStore`、`UpdateCoordinator.recalculateNow`、`LanSyncClient.downloadLatestApksFile`
  - *未使用的 token*：`Spacing.kt` 的 `space20/28/40/56/64`、`minTouch`、`listItemTall`、`searchH`、`appIcon`；`Shape.kt` 的 `LanSyncMetrics.{listItemTall,navIndicatorWidth,navIndicatorHeight,filterChipHeight,appIcon,progressBar,statusBarCompat,gestureBarH}`
- ~~**`POST_NOTIFICATIONS` 运行时请求仍未写**~~ → ✅ **已于 2026-10-05 补上**（见 §11.2）；FGS `specialUse` 的 Play 上架说明**仍需补**。
- **测试缺口**：~~DL-6/7/8/9/10~~ → DL-7/8/9/10 **已于 2026-10-05 补齐**，**DL-6 经查证不可覆盖**（§11.4）。仍无单测的：`AppScanner`/`JmDNSDeviceDiscovery`/`IconCache`/`ForegroundSyncService`/`LanSyncRepository`/`LanSyncGraph`/全部 Compose UI（Android 耦合或纯委托，靠编译 + 真机验收）。
- **小的称谓不一致（未修）**：`docs/compose/spec/ui-recreate.md` frontmatter 写 `branch: MIMO/UI-Recreate`，实际分支是 `qoder/UI-Recreate`；`local-apps-cache-skeleton.md` 的 `commits: 3c7dade..cf55139` 与本分支实际哈希（`9ed1fce`/`5c7b2ee`）也对不上。
- **真机互操作验收（`docs/TEST-PLAN.md` §6）从未执行，且用户已于 2026-10-04 明确暂缓**。⚠️ 该验收在计划中是 **Phase 6 的前置**——协议版本协商与鉴权会碰互操作红线，没有真机兜底不宜动。启动 Phase 6 前须重新评估这一缺口。
- **`origin` 未同步**：`main` 已本地快进到 `68bfa63`，但 `origin/main` 仍在 `d1a71fc`（GitHub 经代理不可达）。网络恢复后需推送，**推送须用户明示批准**。
- **Phase 6 / Phase 7 未启动**。`main` 去向**已裁决**（唯一开发线，见 §1 与 §5）。

---

## 11. 补短板批次（2026-10-05，分支 `qoder/Quality-Gate`）

> 对应交接文档 §8 第 4 步（该文档已由用户删除，见 §1）。四项全部落地，`testDebugUnitTest` **147/147 全绿** + `assembleDebug` 通过（15 文件）。
> **未做**同批次的第 5 项（`build.ps1`/`AGENTS.md` 固化构建命令）：命令与三条前提已完整写进 §4，再脚本化收益不大；且 `AGENTS.md` 会被自动注入，写错代价高于收益。

### 11.1 分层静态门禁（ARCH §8.3 / §12.3）

新增 `app/src/test/java/com/lansync/app/architecture/LayeringTest.kt`（**2 例**）：

- `data layer must not import ui layer`：遍历 `src/main/java/com/lansync/app/data/**/*.kt`，逐行断言不以 `import com.lansync.app.ui.` 开头；违规时输出 `文件:行号: 原文` 清单。**显式兜底「扫到 0 个文件即失败」**——否则路径写错时门禁会静默空过，比没有门禁更危险。
- `repository facade stays within 300 lines`：断言 `LanSyncRepository.kt` ≤300 行（当前 164）。

**为什么不用 Konsist**：该库**不在本机两个 Gradle 缓存内**（`C:\Users\LingTian\.gradle` 与 `E:\S.H.I.T\Gradle\GradleRepository` 都查过），引入需联网解析依赖，而当前代理不可达。改用零新依赖的 JVM 单测，**直接跑在 `testDebugUnitTest` 门禁里**——跑测试就必然跑到它，比外挂 lint 更难被绕过。
**已知代价**：不是 CI 形态（项目本就无 CI/CD）；靠源码文本匹配而非 AST，理论上可被「不写 import、直接用全限定名 `com.lansync.app.ui.X()`」绕过。现阶段够用，若要更严可后续换 AST。

### 11.2 `POST_NOTIFICATIONS` 运行时请求

`MainActivity` 增 `notificationPermissionLauncher`（`registerForActivityResult(RequestPermission())`，字段初始化即注册）+ `requestNotificationPermission()`：仅当 `Build.VERSION.SDK_INT >= TIRAMISU` 且尚未授权时请求（`targetSdk=34` / `minSdk=29`）。权限本身 Phase 4 已在 Manifest 声明，缺的只是运行时请求 → **此前 API 33+ 上 FGS 常驻通知可能整条不可见**。

**为什么挂在 Activity 创建点**：FGS 是在 `MainViewModel.init → autoStart()` 里**自启**的，没有「用户点启动同步」的时机可挂；Android 惯例的「按功能触发时再请求」在本项目结构下不成立。
**拒绝即容忍**：不重试、不阻断，服务照常运行、仅通知不可见（代码内有注释说明这一取舍）。⚠️ **真机行为未验证**（首次弹窗时机、拒绝后的通知可见性、与 FGS 启动的先后）。

### 11.3 `LanSyncMotion` 死码清理

删除 `ui/theme/Theme.kt` 的 `LanSyncMotion` data class、`DefaultMotion`、`LocalMotion`、`LanSyncTheme.motion` 访问器，并把 `LanSyncTheme` 的 KDoc 从「间距 / 容器色 / 动效」改为「间距 / 容器色」。删前逐个 grep 复核：全仓（含测试）**零引用**，且 `CompositionLocalProvider` 从未 provide 过 `LocalMotion`。`@Immutable` 与 `staticCompositionLocalOf` 两个 import 仍被 `LanSyncContainerColors` / `LocalContainers` 使用，**保留**。
§10.4 提到的其余单点死符号（`StatusCard`、`AppIconDiskCache.batchPut` 等）**本批次未清**——它们分散且部分是刻意保留的兼容壳，需逐个判断，不宜与门禁批次混在一起。

### 11.4 DL-7/8/9/10 补齐 + DL-6「不可覆盖」的查证

`LanSyncClientTest` **12 → 18** 例：

- **DL-7**：0 长度落盘 → `Error("Downloaded file is empty")`，并断言**无残留**（`getDownloadedFiles()` 为空）。用例故意带上「空内容的正确 X-MD5」——若空文件检查缺失，本例会误判为 Success，因此它真的锁住了这条分支。
- **DL-8**：`AppPacker` 打单包 → 产物即源字节副本 → 以产物哈希作 `X-MD5` 下发 → 下载成功，且**产物哈希 == 列表 md5 == 落盘哈希**（SPEC §8.1：单包场景两条轨重合）。
- **DL-9**：`AppPacker` 打 split → 断言 **zip 产物哈希 != 源拼接摘要**；以 `X-MD5`（产物哈希）校验**通过**；同一产物改用列表 md5（= 旧 `expectedMd5` 兜底轨）校验**必失败并删文件** → 端到端复现 DL-4 的「偶然校验失败」鬼故事，证明 D1 必要性。
- **DL-10**：三例——① 长度已知（200KB / 64KB 缓冲）时 percent **单调不减**且末次为 100；② `contentLength()==-1` 且无 `X-File-Size` 头时**完全不回调**，但下载仍成功；③ 服务端少报长度（声明 100000、实发 200000）时裸算会得到 131/196/200，断言全部被 `coerceIn` 夹在 0..100。

**⚠️ DL-6（空 body）经查证不可覆盖**：`javap` 确认 OkHttp 4.12 的 `Response.body()` 返回**非空** `ResponseBody`。强行 `Response.Builder().body(null)` 虽然编得过（Kotlin 侧签名仍收可空参，这也是 `response.body?.byteStream()` 不产生「多余安全调用」警告的原因），但运行时 `Response.close()` 会对 null body 抛 NPE，被 `performDownload` 外层 catch 归一成 `Error("Download failed")`，**永远命不中 `Error("Empty response body")`**。
处置：**保留该分支**（网络边界防御，且编译器视角类型确实可空），**不为它写断言**——否则等于把 NPE 兜底产物钉成契约。服务端「200 但无内容」的真实形态是 **0 长度 body**，已由 DL-7 覆盖。结论同步写进 `docs/TEST-PLAN.md §4`（DL-6 行 + 注记）。

**行为改进（按 §10 约定显式标注）**：`performDownload` 的空落盘分支原先**不删文件**，与「缺 X-MD5」「MD5 不匹配」两个失败分支不一致；遗留的 0 字节 `.apk` 会被 `getDownloadedFiles()` 当成正常条目列进文件页，用户看到一个装不了的垃圾项。已改为同样 `destination.delete()`，并由 DL-7 断言锁定。
