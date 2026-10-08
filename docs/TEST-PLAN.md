# LanSync 测试计划（TEST-PLAN）

> **本文件角色**：测试主题文档——L1–L4 策略、DL/CS 用例矩阵、真机 golden path 逐项清单（勾选用）、门禁语义与当前落地清单。
> 代码与测试注释按章节号引用（`TEST-PLAN §2.3 / §4 / §5 / §6`），**不要重排 §N**。
> 本机运行命令与前提**只在** `docs/BUILD.md`；项目状态与基线headline在 `docs/STATUS.md`；协议字节契约在 `docs/SPEC.md`。本文件不复制这些内容。
> **既有测试依赖**（`app/build.gradle.kts` 已声明）：`junit:4.13.2`、`io.mockk:1.13.8`、`kotlinx-coroutines-test:1.7.3`、`io.ktor:ktor-server-test-host:2.3.5`。

---

## 1. 策略总览

| 层 | 范围 | 工具 | 门禁 |
|---|---|---|---|
| L1 单元（行为基线） | 配对状态机、去重、序列化、哈希、配置 | JUnit4 + MockK + coroutines-test | 全绿，迁移不删断言 |
| L2 路由集成（特征化） | 10 条 HTTP 路由的状态码/响应头/响应体形状 | ktor-server-test-host | 全绿，锁定 SPEC §3/§4/§5.1 |
| L3 链路集成 | 下载 + MD5 校验、连接状态机迁移 | MockK + coroutines-test（虚拟时间） | 全绿，锁定 SPEC §5/§7/§8 |
| L4 真机互操作 | 新旧 APK 跨设备 golden path | adb + 双真机 | 人工签核，见 §6 |

**三条原则**：
1. **迁移优先于新增**——旧测试原样搬运为基线，重写"零自由发挥"。
2. **特征化测试锁定现状**——先固化当前行为（含怪异点），行为改进必须在断言与本文档里**显式标注**并同步修订。
3. **虚拟时间**——所有超时（15/30/20/120/60s）用 `runTest` + `StandardTestDispatcher` + `advanceTimeBy` 驱动，**禁止真实 sleep**。

---

## 2. L1 单元基线

### 2.1 归属映射

| 被测行为 | 测试文件 |
|---|---|
| 接收方配对子状态机（PENDING/ACCEPTED/REJECTED/TIMEOUT） | `data/server/InMemoryPairingStoreTest` |
| 版本比较 / 去重 / 差异分类 | `data/sync/UpdateManagerTest` |
| DTO 序列化与省略行为 | `data/model/ModelsTest` |
| MD5 null 传播与一致性 | `data/HashUtilsTest`、`data/packer/HashUtilsConsistencyTest` |
| 超时/间隔默认参数 | `data/AppConfigTest` |

### 2.2 配对状态机必覆盖断言（锁定 SPEC §7.3）

`receiveRequest` 入 PENDING 并返回 true；同 `requestId` 重复 → false（对应路由 409）；`respondToRequest(true/false)` 返回 payload；不存在的 id → null（路由 404）；`getStatus` 在未应答时为 PENDING（路由回 `{"status":"pending"}`）、应答后为 accepted/rejected、**15s 超时后映射为 `status="rejected"` + `message="Timeout"`**（SPEC §2.6）；`respondToRequest` 后 timeoutJob 必须被取消；`incomingRequests` 只暴露 PENDING 且按 timestamp 倒序。

### 2.3 UpdateManager / Models / HashUtils 必覆盖断言

- **`UpdateManagerTest`**：`findUpdates` 只收 remote > local、跳过任一侧为系统应用、跳过本地缺失包；`deduplicateUpdates` 同包名取最高 `versionCode`、**平级取 `deviceName` 字母序更小者**（SPEC §3.2）、空输入返回空；`calculateSyncDiffs` 四种差异类型分类且跳过系统应用。
- **`ModelsTest` 的 `encodeDefaults=false` 字节快照**（SPEC §1.2.1，必测）：`DeviceInfoResponse(deviceName="X")` **不含** `version`；`GenericStatusResponse()` 序列化为 `{}`、`GenericStatusResponse(status="pong")` 为 `{"status":"pong"}`；`AppInfo(isSystemApp=false, isSplitApk=false)` **不含**这两字段（=true 时含）；`ConnectStatusResponse(status="rejected", accepted=false)` **不含** `accepted`。除单测外，路由测试同样保留这些断言。
- **`HashUtilsTest` 的 null 语义**（SPEC §8.2）：空列表 → null；任一路径不可读 → null；全部不可读 → null。**绝不返回空文件的摘要**（`d41d8cd9…` 幽灵指纹是缺陷，不得作为断言目标）。

---

## 3. L2 路由特征化测试

**前置接缝**（`docs/ARCHITECTURE.md` §3.5）：路由是可直接 `install` 的 `LanSyncRouting.lanSyncModule(delegate, pairingStore)`，测试用 `FakeDelegate`/`FakePairing` 注入，无需起真服务。

### 3.1 路由 × 状态码矩阵（锁定 SPEC §3）

| 路由 | 用例 |
|---|---|
| `GET /api/ping` | 200 + `{"status":"pong"}` + `application/json` |
| `GET /api/applist` | provider 有值 → 200 + `[AppInfo…]`；provider null → 200 + `[]` |
| `GET /api/deviceinfo` | 200 + `{"deviceName":"X"}`（**断言不含 version**） |
| `POST /api/connect/request` | 新请求 → 200 `{"status":"pending","requestId":…}`；重复 → **409** `"Duplicate request"`；坏 JSON → **400** |
| `GET /api/connect/status/{id}` | 恒 200。PENDING / 空 id / 异常 → `{"status":"pending"}`（**失败安全**）；ACCEPTED → `status="accepted"`+responderName；REJECTED → `status="rejected"`+message；TIMEOUT → `status="rejected"`+`message="Timeout"` |
| `POST /api/connect/response/{id}` | 正常 → 200 `ConnectResponsePayload`；空 id → **400**；不存在/已处理 → **404**；异常 → **500** |
| `GET /api/download/{pkg}` | 最高版本 → 200 + 文件流 + 三响应头；**先判**全不可提取 → **403**；再无匹配 → **404**；打包 null → **500** `"Failed to pack app"`；打包异常 → **500** |
| `GET /api/download/{pkg}/{vc}` | 精确匹配 → 200；**先判**无匹配 → **404**；再不可提取 → **403**；非法 vc（降级 0L）→ **404**；打包失败 → **500** |
| `POST /api/disconnect` | 正常 → 200 `{}`；坏 JSON → **400**；identityKey 优先于 displayKey 匹配 |
| `POST /api/refresh-applist` | 正常 → 200 `{}`；displayKey 空 → 不触发 handler 但仍 200；坏 JSON → **400** |

> **`503 Server not ready` 不属于新路由**：它来自旧服务端惰性创建的 null manager。新实现 `PairingStore` 构造注入恒非空，该态不存在，因此也没有对应用例（SPEC §3.2）。`LanSyncErrorCode.SERVER_NOT_READY` 仅用于识别旧端。

### 3.2 下载响应头断言（锁定 SPEC §5.1 / §4）

- `X-MD5` == `HashUtils.md5(产物文件)`（**非** `appInfo.md5`）；`X-File-Size` == `file.length()`；`Content-Disposition` == `attachment; filename="{产物名}"`。
- Content-Type：`.apk` → `application/octet-stream`；`.apks` → `application/zip`。
- **两条下载路由的错误码优先级相反**（#7 先 403 再 404；#8 先 404 再 403），分别断言。两条 handler **有意不合并**——只共用发送段 `sendPackedFile(...)`，判序各自保留；本表即回归护栏。

### 3.3 错误体断言（锁定 `docs/ARCHITECTURE.md` §7）

断言状态码不变、响应体不含 `e.message` 细节（用会抛异常的用例断言输出不含 `"boom"`）、`code` ∈ 枚举。另需覆盖"旧客户端只读 `isSuccessful` / `body.take(200)`"这一前提，证明错误体 text/plain → json 不影响旧端判定。

---

## 4. L3 下载 + MD5 校验链路（锁定 SPEC §5.6 / §8）

用 MockK 造真实 `okhttp3.Response`（含/不含 `X-MD5`）+ `TemporaryFolder`，覆盖 `performDownload` 全分支：

| 用例 | 场景 | 期望 |
|---|---|---|
| DL-1 | 200 + `X-MD5` 匹配产物 | `Success(file)`，文件保留 |
| DL-2 | 200 + `X-MD5` **不匹配** | `Error("MD5 verification failed")` + **文件被删除** |
| DL-3 | 200 + **无** `X-MD5` | 删文件 + `Error("Missing X-MD5 header")`（D1 目标态，**无 `expectedMd5` 兜底**） |
| DL-5 | 非 2xx | `Error("HTTP error: {code}: {body.take(200)}")` |
| DL-6 | body 为 null | ⚠️ **不可覆盖，别写断言**（见下方判例） |
| DL-7 | 落盘后 `length == 0` | `Error("Downloaded file is empty")` + **断言无 0 字节残留**（`getDownloadedFiles()` 为空）。此分支必须删文件，与 DL-2/DL-3 一致，否则垃圾项会被列进文件页 |
| DL-8 | 单 APK 端到端 | `AppPacker` 产物即字节副本 → `X-MD5 == AppInfo.md5 == 落盘哈希`（SPEC §8.1：单包场景两轨重合） |
| DL-9 | Split 端到端 | zip 产物哈希 **≠** 源拼接摘要 → 以 `X-MD5` 校验**必通过**；改用列表 md5 **必失败并删文件**（D1 必要性的端到端证据） |
| DL-10 | 进度回调 | ① `contentLength > 0` 时 percent 单调不减且末次为 100；② `contentLength == -1` 且无 `X-File-Size` 时**完全不回调**但下载成功；③ 服务端少报长度时裸算会得 131/196/200，断言全部被 `coerceIn` 夹在 0..100 |
| DL-11 | 落盘命名 | 优先 `Content-Disposition.filename`；缺失时 `downloadApksFile` → `{pkg_}_{vc}.apks`、`downloadLatestApksFile` → `{pkg_}.apks`（**无 versionCode 段**） |
| DL-12 | 包名反推 | `DownloadedFileName` 常规 / 含数字段 / 无版本段边界（SPEC §5.4），单一实现 |

> **DL-6 判例（为何不写断言）**：OkHttp 4.12 的 `Response.body` 实际永不为 null（`javap` 确认）。强行 `Response.Builder().body(null)` 编得过（Kotlin 侧签名仍收可空），但运行时 `Response.close()` 对 null body 抛 NPE，被 `performDownload` 外层 catch 归一成 `Error("Download failed")`，永远命不中 `Error("Empty response body")`。处置：该分支作为网络边界防御**保留、不删、不写断言**——写断言等于把 NPE 兜底产物钉成契约。服务端"200 但无内容"的真实形态是 0 长度 body，由 DL-7 覆盖。

**哈希一致性专项**：`AppPacker.createApksFile` 产物 → `HashUtils.md5(产物)` 与路由 `sendPackedFile` 发出的 `X-MD5` 必然相等；D1 不等式（zip 产物哈希 ≠ 源拼接摘要）由 `AppPackerTest` 的 `split artifact hash differs from concatenated source hash (D1 rationale)` 钉住。

---

## 5. L3 连接状态机（锁定 SPEC §7.4–7.7）

**驱动方式**：状态迁移用**直接投递事件**（确定性，规避周期循环的时序脆弱）；只有"周期心跳按 20s 触发"用虚拟时间 `advanceTimeBy`。前提顺序必须照真实流程：**先 discovery 再 connect**（`updateState` 只对已在 enriched 的设备生效）。

| 用例 | 场景 | 期望迁移 |
|---|---|---|
| CS-1 | mDNS 新设备 | → DISCOVERED |
| CS-2 | `connectDevice` + poll Accepted | DISCOVERED→CONNECTING→CONNECTED；startHeartbeat；fetchAppListWithRetry(initiator) |
| CS-3 | `sendConnectRequest` → null | → ERROR「无法发送连接请求，目标设备无响应」 |
| CS-4 | poll Rejected | → ERROR「对方拒绝连接」 |
| CS-5 | poll Timeout（未被反向连接） | → ERROR「连接超时（30秒内未收到响应）」 |
| CS-6 | poll Timeout 但已反向连接 | 视为成功 true（折叠进 CS-16） |
| CS-7 | 心跳 ping 成功 | 清 failCount；CONNECTED；lastSeen 刷新；connectionError = null |
| CS-8 | ping 失败 failCount = 1 | → RECONNECTING「连接不稳定… (1/4)」 |
| CS-9 | ping 失败 1 < failCount < 4 | → RECONNECTING「正在尝试重新连接…」+ tryFastReconnect |
| CS-10 | failCount ≥ 4 | → CONNECTION_TIMEOUT「连接超时，设备已离线」；移出 connected；停心跳/同步 |
| CS-11 | tryFastReconnect 且 `fetchDeviceInfo != null` | → CONNECTED + fetchAndEnrich |
| CS-12 | 本地 disconnect | → DISCONNECTED；**appList 保留**；发 disconnect 通知 |
| CS-13 | 远端 disconnect（displayKey/identityKey 匹配） | → DISCONNECTED；appList 保留 |
| CS-14 | 端口迁移（同 `instanceId` 换 ip:port） | 保留 connectionState + appList；原 CONNECTED/RECONNECTING 时停旧起新心跳（SPEC §7.5） |
| CS-15 | 陈旧清理 | 非 CONNECTED/RECONNECTING/CONNECTION_TIMEOUT 且不在 raw → 移除；**连接态不因 mDNS 消失被清理**（SPEC §7.6） |
| CS-16 | 去重锁 + 已连接重复 connect | `putIfAbsent` 下并发同 key 仅一次生效；已 CONNECTED 直接返回 true 且不再发请求 |
| CS-17a | 配对历史命中设备来请求（无交互） | 自动接受 → `accepted` |
| CS-17b | 陌生设备来请求 | 保持 PENDING / 弹窗，**绝不自动放行** |
| CS-17c | 陌生设备显式拒绝 | **即时 `rejected`**（不是让发起方干等 30s） |
| CS-17d | 显式接受 | `accepted` + CONNECTED + 心跳 + 拉列表 + **写入 `PairingHistoryStore`** |

> **并发收敛专项 —— ⏳ 未写**：并发投递 `RawDevicesUpdated` + `HeartbeatTick` + `ConnectRequested`、断言最终状态确定且无交错损坏的压力测试尚未落地。现有用例走单事件串行投递，确定性高但碰不到竞态窗口；Actor 单协程收敛目前靠**构造保证**而非测试证明（登记在 `docs/ROADMAP.md` §4）。

---

## 6. L4 真机互操作验收（golden path 清单）

**前提**：两台 Android 真机（minSdk 29）同一局域网 WiFi。「新版本」= 当前工作树构建的 APK；「旧版本」= 已分发的旧 APK（需重新构建时，重构前代码在 git `d1a71fc`）。**本矩阵从未执行，是唯一阻塞 Phase 6 的验收缺口。**

### 6.1 互操作矩阵

| 组合 | 发现 | 配对 | 传输 | 安装 | 备注 |
|---|---|---|---|---|---|
| 新 × 新 | ☐ | ☐ | ☐ | ☐ | 主路径 |
| 新（发起）× 旧（接收） | ☐ | ☐ | ☐ | ☐ | 新实现必须能被旧端发现/配对/拉取 |
| 旧（发起）× 新（接收） | ☐ | ☐ | ☐ | ☐ | 新端必须复现旧协议字节（SPEC §9） |
| 旧 × 旧 | ☐ | ☐ | ☐ | ☐ | 回归基线 |

已知新旧差异点仅 `/api/deviceinfo` 的 `version`（旧构建线上含 `"version":"1.0"`、新构建省略，SPEC §2.9）；客户端从不读取，故容忍。其余路由/头/命名/状态机词表一致。

### 6.2 Golden Path 逐步清单（每组合执行一遍）

**① 发现（mDNS）**
- [ ] 双机装 APK → 均启用同步 → 设备 Tab 各自出现对方（deviceName = 对方 `Build.MODEL`）。
- [ ] 抓包/日志确认 `_lansync._tcp.local.`、实例名 `LanSync_{host}_{instanceId}`、TXT 双键 `deviceName`/`instanceId`（SPEC §6.1/§6.2）。
- [ ] 自过滤：本机不出现在自己的列表（instanceId 相同被忽略）。
- [ ] 60s 保活：静置 >60s 设备仍在列（`REFRESH_INTERVAL_MS`）。

**② 配对**
- [ ] A 点连接 B → B 弹 `IncomingConnectionSheet`（`ModalBottomSheet`，15s 倒计时自动拒绝，返回键可关）。
- [ ] B 接受 → A 轮询收到 `accepted` → 双方 CONNECTED。
- [ ] B 15s 不响应 → 自动 TIMEOUT → A 侧表现为 `rejected` / "Timeout"（SPEC §2.6/§7.2）。
- [ ] 自动接受语义（SPEC §7.7）：陌生设备首次连 → 弹窗确认（不自动放行）；接受后入配对历史；该设备再次连 → 自动接受；对陌生设备点拒绝 → 对端**即时**收到 rejected。
- [ ] 端口迁移：连接后重启 B 的服务（换端口）→ A 无缝保留 CONNECTED + appList（SPEC §7.5）。

**③ 传输**
- [ ] 同步 Tab 出现可更新项（remote.versionCode > local）。
- [ ] 单 APK 下载：进度 0→100，`X-MD5` 校验通过。
- [ ] **Split APK 下载**：`.apks` 落盘并以 `X-MD5`（zip 产物哈希）校验通过——D1 的关键真机证据。
- [ ] 系统/受保护应用 → 403，UI 提示不可提取。
- [ ] 人为篡改产物 → 文件被删除 + `MD5 verification failed`。

**④ 安装**
- [ ] 一键安装 → FileProvider URI + `ACTION_VIEW` 拉起系统/第三方安装器。
- [ ] MIME：`.apks` = `application/zip`、`.apk` = `application/vnd.android.package-archive`（SPEC §4）。
- [ ] 文件 Tab：列举 / 多选 / 保存到 SAF / 删除；包名反推显示正确（SPEC §5.4）。

**⑤ 生命周期（`ForegroundSyncService`，`docs/ARCHITECTURE.md` §8）**
- [ ] 退后台 5 分钟：服务端 + 发现 + 心跳存活（对方仍可见本机、ping 不断）。
- [ ] 通知栏常驻显示端口/已连接数；「停止同步」按钮生效；API 33+ 拒绝 `POST_NOTIFICATIONS` 后服务仍能跑。
- [ ] `adb shell am kill` 后 `START_STICKY` 恢复，`instanceId` 不变，对端身份无缝识别。
- [ ] 低端 OEM 省电场景：必要时加电池白名单（README 已标注）。

### 6.3 产物要求

每组合每步记录 `lansync_debug.log` 导出 + 关键截图 + 抓包（mDNS/HTTP）。失败项回填 `docs/SPEC.md` 决议记录或 `docs/ARCHITECTURE.md`。

---

## 7. 门禁

| 门禁 | 阻断条件 |
|---|---|
| `testDebugUnitTest` | 任一 L1/L2/L3 用例失败；或 `LayeringTest` / `DocFactsTest` 违规 |
| `assembleDebug` | 编译 / 资源合并失败 |
| `architecture/LayeringTest` | ① `data/**` 出现 `import com.lansync.app.ui.*`（输出 `文件:行号: 原文`）；② `LanSyncRepository.kt` > 300 行。**显式兜底"扫到 0 个文件即失败"**，否则路径写错时门禁静默空过 |
| `architecture/DocFactsTest` | 文档字面量与仓库实测不符：`docs/BUILD.md` §3 版本锁表与 SDK / `jvmTarget` 行、`docs/STATUS.md` §2 与 §10 的测试基线、§10 逐文件例数、`.qoder/agents` 镜像数字、远端同步表述与 `.git` 引用矛盾。**自带检测器自检例**，匹配式失效即失败，不会静默空过 |
| `assembleRelease` + 真机冒烟 | 安装器链路失败（Phase 7 范畴） |
| §6 人工签核 | 任一红线组合失败 |

命令与运行前提见 `docs/BUILD.md`（唯一来源，本文件不复制）。项目**没有 CI/CD**，上表就是本地约定门槛；分层门禁为何用零依赖单测而非 Konsist，见 `docs/DECISIONS.md`。已知代价：靠源码文本匹配而非 AST，可被"不写 import、直接用全限定名"绕过。

---

## 8. 测试相关决议

完整登记册见 `docs/DECISIONS.md`；此处只保留影响测试可执行性的四条。

| # | 事项 | 决议与测试侧后果 |
|---|---|---|
| TT1 | 旧版 APK 实物存在（用户事实） | §6.1 四组合全部可执行；⚠️ 至今仍**从未真机执行** |
| TT2 | 项目从来没有 CI | 绿色基线以本地 `testDebugUnitTest` 为准 |
| TT3 | 自动接受策略 | 采纳干净语义（SPEC §7.7）→ CS-17a–d；配对历史为 `PairingHistoryStore` 本机私有、不上线 |
| TT4 | 无历史手工测试脚本 | §6.2 golden path 清单是**唯一**手工验收脚本 |

---

## 9. 职责边界

本文件只管**测试设计与验收清单**（§1–§8）与**当前落地映射**（§10）。基线 headline 与阶段状态在 `docs/STATUS.md`，运行命令在 `docs/BUILD.md`，裁决在 `docs/DECISIONS.md`，缺口与待办在 `docs/ROADMAP.md`——同一条事实不在多处维护。

---

## 10. 当前落地清单

16 个测试文件 / 154 例（用例数以仓库实测为准）。

| 测试文件 | 例数 | 锁定 |
|---|---|---|
| `data/server/LanSyncRoutingTest` | 33 | §3.1 状态码矩阵、§3.2 三响应头与 Content-Type、§3.3 不回显 `e.message`、SPEC §1.2.1 省略、D1 |
| `data/transfer/LanSyncClientTest` | 18 | DL-1/2/3/5/7/8/9/10/11 + `parseConnectStatus` + `fetchAppList` |
| `data/connection/DefaultConnectionCoordinatorTest` | 21 | CS-1…CS-17（含 CS-17a–d）、`incomingRequests`、周期心跳 |
| `data/model/ModelsTest` | 13 | DTO roundtrip + `encodeDefaults=false` 字节快照 |
| `data/server/InMemoryPairingStoreTest` | 9 | §2.2 配对状态机、15s 超时、getStatus 映射、respond 取消 timeout、clearAll |
| `data/sync/UpdateManagerTest` | 9 | §2.3 `findUpdates` / `deduplicateUpdates` / `calculateSyncDiffs` |
| `data/transfer/DownloadedFileNameTest` | 10 | DL-12 命名 / 反推边界 / 正向匹配 |
| `data/transfer/DownloadInstallControllerTest` | 7 | 下载与安装进度状态流 |
| `architecture/DocFactsTest` | 7 | CONVENTIONS §6 文档事实门禁：BUILD §3 版本锁表与 SDK/`jvmTarget` 行、STATUS §2 与 §10 测试基线、§10 逐文件例数、`.qoder/agents` 镜像数、远端同步表述对照 `.git` 引用，外加检测器自检 |
| `data/transfer/AppPackerTest` | 6 | 单包字节一致与命名、split zip 结构、不可提取/空路径 → null、D1 不等式、`clearCache` |
| `data/HashUtilsTest` | 6 | §2.4 null 传播三态 |
| `data/localapps/LocalAppRepositoryTest` | 5 | hasCache、`scanAndRefresh` 写缓存+状态+added、`loadCacheSkeleton` 不触发扫描、跨次 added/removed、损坏缓存自愈 |
| `data/sync/UpdateCoordinatorTest` | 4 | combine / 节流 / 立即重算（注入 `nowMillis`） |
| `architecture/LayeringTest` | 2 | §7 分层门禁 |
| `data/AppConfigTest` | 2 | SPEC §7.2 全部默认参数 |
| `data/packer/HashUtilsConsistencyTest` | 2 | MD5 一致性（含 nonexistent → null） |

**无 JVM 单测（靠编译 + `assembleDebug` + 真机验收）**：`AppScanner`、`JmDNSDeviceDiscovery`、`IconCache`/`AppIconDiskCache`、`ForegroundSyncService`、`LanSyncRepository`、`LanSyncGraph`、`KtorLanSyncServer`、全部 Compose UI 与 `MainViewModel`。项目无 Robolectric / 无 UI 测试 / 无覆盖率工具；为 Android 耦合类新建测试栈属扩大范围，须先与用户确认。
