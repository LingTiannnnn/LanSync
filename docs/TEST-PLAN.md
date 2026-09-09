# LanSync 测试计划（TEST-PLAN）

> **文档状态**：Phase 0 交付物 · 测试策略与验收清单（不含生产代码/测试代码，仅计划）
> **定位**：为「契约不变，实现重写」提供**可执行的行为基线与验收门槛**。协议字节契约以 `docs/SPEC.md` 为准，架构目标以 `docs/ARCHITECTURE.md` 为准。
> **既有测试依赖**（`app/build.gradle.kts` 已声明，可直接用）：`junit:4.13.2`、`io.mockk:1.13.8`、`kotlinx-coroutines-test:1.7.3`、`io.ktor:ktor-server-test-host:2.3.5`。

---

## 1. 策略总览

三层测试金字塔 + 一层真机验收：

| 层 | 范围 | 工具 | 门禁 |
|---|---|---|---|
| L1 单元（行为基线） | 配对状态机、去重、序列化、哈希、配置 | JUnit4 + MockK + coroutines-test | 全绿，迁移不删断言 |
| L2 路由集成（特征化） | 10 条 HTTP 路由的状态码/响应头/响应体形状 | ktor-server-test-host | 全绿，锁定 SPEC §3/§4/§5.1 |
| L3 链路集成 | 下载+MD5 校验、连接状态机迁移 | MockK + coroutines-test（虚拟时间） | 全绿，锁定 SPEC §5/§7/§8 |
| L4 真机互操作 | 新旧 APK 跨设备 golden path | adb + 双真机 | 人工签核，见 §6 |

**原则**：
1. **迁移优先于新增**——先把旧测试原样搬运为基线（§2），确保重写「零自由发挥」。
2. **特征化测试锁定现状**——路由/下载测试先固化**当前**行为（含怪异点），重写后必须仍通过；行为改进项（如 MD5 null 传播）须在测试中**显式标注变更**并同步修订断言。
3. **虚拟时间**——所有超时（15s/30s/20s/120s/60s）用 `StandardTestDispatcher` + `runTest` 的 `advanceTimeBy` 驱动，禁止真实 sleep。

---

## 2. 迁移旧测试作为行为基线（L1）

### 2.1 现有测试清单盘点（`app/src/test`）

> **⚠️ 计数已更新（2026-09-08 复核）**：原文「6 文件 29 用例」为 Phase 1 前快照。实测**旧测试 6 文件 32 用例**（`HashUtilsTest` 4→6、`ModelsTest` 实为 9 非 8）；Phase 1 另**新增 4 文件 63 用例**（`LanSyncRoutingTest` 33 / `LanSyncClientTest` 12 / `DownloadedFileNameTest` 10 / `InMemoryPairingStoreTest` 8）。合计 **10 文件 95 用例**。下表「迁移处置」中标 ✅ 者 Phase 1 已完成。

| 文件 | 用例数 | 覆盖 | 迁移处置 |
|---|---|---|---|
| `connection/ConnectionManagerTest.kt` | 10 | 配对请求状态机 | **原样迁移**为基线 |
| `update/UpdateManagerTest.kt` | 3 | `deduplicateUpdates` | **原样迁移** + 补 `findUpdates` 缺口 |
| `model/ModelsTest.kt` | 9 | DTO 序列化 roundtrip（ConnectResponseBody×4 / RefreshAppListPayload×2 / ConnectStatusResponse / AppInfo / DeviceInfo） | ✅ 已在基线；**但 §2.3 计划的 `encodeDefaults=false` 单测快照未加入 ModelsTest**（该覆盖改由 `LanSyncRoutingTest` 在路由层断言：deviceinfo 不含 version、disconnect 为 `{}`） |
| `HashUtilsTest.kt` | 6（原 4） | MD5 工具 | ✅ **§2.4 修订已完成**：缺陷预期例已改名 `md5 paths with unreadable files returns null` + `assertNull`，并新增「部分不可读→null」「空列表→null」两例 |
| `packer/HashUtilsConsistencyTest.kt` | 2 | MD5 一致性 | **原样迁移** |
| `AppConfigTest.kt` | 2 | 配置默认值 | **原样迁移**（锁定 SPEC §7.2 参数） |

### 2.2 `ConnectionManagerTest`（10 例，配对状态机基线）
逐例迁移，锁定 SPEC §7.3：
1. `receiveRequest adds to pending and returns true`
2. `duplicate request rejected`（相同 requestId → false → 对应路由 409）
3. `respondToRequest accepted returns payload`（responderName="TestDevice"）
4. `respondToRequest rejected returns payload`
5. `respondToRequest non-existent returns null`（→ 路由 404）
6. `getStatus returns pending for unanswered request`（PENDING → null → 路由回 `{"status":"pending"}`）
7. `getStatus returns accepted after response`
8. `removeRequest clears pending`
9. `clearAll clears all pending requests`
10. `incomingRequests flow emits pending-only requests`（只暴露 PENDING）
- **新增缺口**：`handleTimeout` 15s 自动置 TIMEOUT（虚拟时间 `advanceTimeBy(15_000)`）；TIMEOUT 经 `getStatus` 映射为 `accepted=false, message="Timeout"`（SPEC §2.6）；`respondToRequest` 后 `timeoutJobs` 被取消（不再触发 timeout）。

### 2.3 `UpdateManagerTest`（3 例）+ `ModelsTest`（8 例）
- **UpdateManagerTest 迁移**：`deduplicateUpdates keeps highest versionCode` / `preserves unique packages` / `returns empty for empty input`。
  - **补缺口**：`findUpdates` 当前**零覆盖**（旧测试仅 mock `fetchAppList` 返回 emptyList 测去重）。新增：跳过系统应用（remote/local 任一为系统应用不计）、仅 `remote.versionCode > local.versionCode` 才 `canUpdate`、平级取 `deviceName` 字母序更小者（SPEC §3.2 冻结规则）。
- **ModelsTest 迁移**：`ConnectResponseBody`(±)、`RefreshAppListPayload`(±)、`ConnectStatusResponse` 解码、`AppInfo` roundtrip、`DeviceInfo` roundtrip。
  - **补关键缺口（SPEC §1.2.1）**：新增 `encodeDefaults=false` **字节快照**断言——
    - `DeviceInfoResponse(deviceName="X")` 序列化**不含** `"version"`。
    - `GenericStatusResponse()` 序列化为 `{}`；`GenericStatusResponse(status="pong")` 为 `{"status":"pong"}`。
    - `AppInfo(isSystemApp=false, isSplitApk=false)` 序列化**不含**这两字段；=true 时含。
    - `ConnectStatusResponse(status="rejected", accepted=false)` **不含** `accepted`。

### 2.4 `HashUtilsTest` 缺陷预期修订（**行为改进 — ✅ Phase 1 已完成**）
- 原第 4 例 `md5 paths with unreadable files handles gracefully` 断言「全不可读返回 32 字符」——曾**固化幽灵指纹缺陷** `d41d8cd98f00b204e9800998ecf8427e`（SPEC §8.2）。
- **已落地**（`HashUtils.kt` 改动 + `HashUtilsTest.kt` 4→6）：`md5(paths)` 改为 null 传播（空列表/任一不可读→null）；断言改名 `... returns null` + `assertNull`，并补「部分不可读→null」「空列表→null」两例。**[行为变更已在代码注释标注，非回归]**
- **✅ 裁决②（接受现状，2026-09-08）**：原计划「`AppScanner` 对 null 指纹降级 `isExtractable=false`」**不实施**。`AppScanner.kt` L73 保持 `md5 = HashUtils.md5(sourcePaths) ?: ""` 且 `isExtractable=true`（L83）——此为**合法态**，已写入 **SPEC §8.4（决策 D2）**。理由：`md5` 仅作版本指纹不参与传输校验（D1），且扫描前已 `canRead()` 预筛使 null 极罕见；降级反而误伤可传输应用。**重开条件**：真机测试若发现空 `md5` 的正常应用在去重/比较中被误判，再重开。
- `HashUtilsConsistencyTest` 2 例（含 `md5 nonexistent pack returns null`，已与 null 语义一致）+ `AppConfigTest` 2 例**原样在基线**。

---

## 3. 新增 ktor-server-test-host 路由测试（L2，特征化）—— **✅ Phase 1 已落地**

> **前置接缝**（ARCHITECTURE §3.5）：路由已提取为可独立 `install` 的 `LanSyncRouting.lanSyncModule(delegate, pairingStore)`；测试见 `app/src/test/.../server/LanSyncRoutingTest.kt`（**33 用例**），用 `FakeDelegate`/`FakePairing` 注入，覆盖全 10 路由状态码矩阵、§4 Content-Type、§5.1 三响应头、§1.2.1 省略行为、§8.3 D1（`X-MD5 == HashUtils.md5(产物)` 而非列表 md5）、§7 不回显 `e.message`（断言不含 "boom"）。
> **⚠️ 503 修正**：下表 `POST /api/connect/request` 的「manager null → 503」仅适用**旧 `KtorServer`** 特征化；**新 `lanSyncModule` 已移除 503**（`PairingStore` 构造注入恒非空，SPEC §11.2），故新路由测试**无 503 用例**。

### 3.1 路由 × 状态码矩阵（锁定 SPEC §3）
对每条路由断言 **method + path + 请求体 → 状态码 + Content-Type + 响应体形状**：

| 路由 | 用例 |
|---|---|
| `GET /api/ping` | 200 + `{"status":"pong"}` + `application/json` |
| `GET /api/applist` | provider 有值 → 200 + `[AppInfo…]`；provider null → 200 + `[]` |
| `GET /api/deviceinfo` | 200 + `{"deviceName":"X"}`（**断言不含 version**） |
| `POST /api/connect/request` | 新请求 → 200 `{"status":"pending","requestId":…}`；重复 → **409** `"Duplicate request"`；manager null → **503** `"Server not ready"`；坏 JSON → **400** |
| `GET /api/connect/status/{id}` | PENDING/空 id/manager null/异常 → 200 `{"status":"pending"}`；ACCEPTED → 200 `status="accepted"`+responderName；REJECTED → 200 `status="rejected"`+message；TIMEOUT → 200 `status="rejected"`+`message="Timeout"` |
| `POST /api/connect/response/{id}` | 正常 → 200 `ConnectResponsePayload`；manager null/空 id → **400**；不存在/已处理 → **404**；异常 → **500** |
| `GET /api/download/{pkg}` | 最高版本 → 200 + 文件流 + 三响应头；全不可提取 → **403**；无匹配 → **404**；打包 null → **500** `"Failed to pack app"`；打包异常 → **500** |
| `GET /api/download/{pkg}/{vc}` | 精确匹配 → 200；无匹配 → **404**；不可提取 → **403**；非法 vc（→0L）→ **404**；打包失败 → **500** |
| `POST /api/disconnect` | 正常 → 200 `{}`；坏 JSON → **400**；identityKey 优先于 displayKey 匹配 |
| `POST /api/refresh-applist` | 正常 → 200 `{}`；displayKey 空 → 不触发 handler 但仍 200；坏 JSON → **400** |

### 3.2 下载响应头断言（锁定 SPEC §5.1 / §4）
- `X-MD5` == `HashUtils.md5(产物文件)`（**非** `app.md5`）；`X-File-Size` == `file.length()`；`Content-Disposition` == `attachment; filename="{产物名}"`。
- Content-Type：`.apk` → `application/octet-stream`；`.apks` → `application/zip`。
- **两路由错误码优先级差异**分别断言（SPEC §3.1 #7 vs #8），为 Phase 1「合并 handler」提供回归护栏——合并后两路由状态码矩阵必须逐格不变。

### 3.3 错误体改进的兼容断言（锁定 ARCHITECTURE §7）
- 重写为 `LanSyncErrorDto(code,message)` 后：断言**状态码不变**、响应体**不再含 `e.message` 细节**、`code` ∈ 枚举。
- 兼容性：新增测试模拟「旧客户端只读 `isSuccessful` / `body.take(200)`」，证明错误体从 text/plain 改 json **不影响旧客户端判定**。

---

## 4. 下载 + MD5 校验链路测试（L3，锁定 SPEC §5.6 / §8）—— **✅ Phase 1 部分落地**

> **已落地**：`app/src/test/.../transfer/LanSyncClientTest.kt`（**12 用例**，MockK 造真实 `okhttp3.Response`）覆盖 DL-1/DL-2/**DL-3 目标态**/DL-5/DL-11 + `parseConnectStatus`(§2.6) + `fetchAppList`；`DownloadedFileNameTest.kt`（**10 用例**）覆盖 DL-12 命名/解析/正向匹配。
> **⚠️ DL-3 现状 vs 目标**：新 `LanSyncClient` **已实现目标态**（缺 X-MD5→删文件+`Error("Missing X-MD5 header")`，无 expectedMd5 兜底）；但**旧 `AppListClient` 仍保留兜底轨**（未接线，SPEC §11.3）。DL-4（split 用 expectedMd5 必失败）在旧客户端才复现，新客户端已无此路径。
> **尚未覆盖（待补）**：DL-6（空 body）、DL-7（落盘 length==0）、DL-8/DL-9（单/split 端到端 X-MD5 对比）、DL-10（进度回调单调性）。

用 MockK 造 `Response`（含/不含 `X-MD5` 头）+ 临时文件，覆盖 `performDownload` 全分支：

| 用例 | 场景 | 期望 |
|---|---|---|
| DL-1 | 200 + `X-MD5` 匹配产物 | `Success(file)`，文件保留 |
| DL-2 | 200 + `X-MD5` **不匹配** | `Error("MD5 verification failed")` + **文件被删除** |
| DL-3 | 200 + **无** `X-MD5` + `expectedMd5` 匹配 | 现状：`Success`（兜底轨）；**目标（D1）**：无头即 `Error`，删除兜底分支后此例断言修订 |
| DL-4 | 200 + 无 `X-MD5` + `expectedMd5` 不匹配 | 现状：`Error`（这正是 split 场景「偶然校验失败」鬼故事根源，SPEC §8.1） |
| DL-5 | 非 2xx | `Error("HTTP error: {code}: {body.take(200)}")` |
| DL-6 | 空 body | `Error("Empty response body")` |
| DL-7 | 落盘后 length==0 | `Error("Downloaded file is empty")` |
| DL-8 | 单 APK 端到端 | `X-MD5 == AppInfo.md5`（字节副本，SPEC §8.1）→ 校验通过 |
| DL-9 | Split APK 端到端 | `X-MD5 != AppInfo.md5`（zip≠拼接，SPEC §8.1）→ **以 X-MD5 校验通过**；用 expectedMd5 必失败（证明 D1 必要性） |
| DL-10 | 进度回调 | `contentLength>0` 时 percent 单调 0→100 coerceIn；`contentLength<=0` 时不回调 |
| DL-11 | 文件命名 | 落盘名取 `Content-Disposition.filename`；缺失时 `downloadApksFile` → `{pkg_}_{vc}.apks`、`downloadLatestApksFile` → `{pkg_}.apks`（SPEC §5.3） |
| DL-12 | 包名反推 | `extractPackageName` 常规/含数字段/无版本段边界（SPEC §5.4），收敛后**单一实现**测试（消除 P7 双份） |

> **哈希一致性专项**：新增 `AppPacker.createApksFile` 产物 → `HashUtils.md5(产物)` 与 `sendZipFile` 发出的 `X-MD5` **必然相等**的断言（证明「以传输产物为准」自洽）；并断言 `createApksFile` 内部那个**死代码 digest** 不影响结果（ARCHITECTURE/SPEC §8.1）。

---

## 5. 连接状态机测试（L3，锁定 SPEC §7）—— **✅ Phase 2 已落地**

> **现状**：`DefaultConnectionCoordinator`（Phase 2）已实现，`DefaultConnectionCoordinatorTest.kt`（**21 用例**）用虚拟时间 + 直接投递事件覆盖下表 CS-1…CS-17（CS-6 折叠进 CS-16）。接收方配对协议状态机 `InMemoryPairingStoreTest.kt`（**9 用例**，含迁移的 clearAll）验证 15s 超时/getStatus 映射/respond 取消 timeout/removeRequest；`incomingRequests` 流（旧 `ConnectionManagerTest` #10）已迁至 coordinator 测试。旧 `ConnectionManagerTest` 10 例仍冻结在基线（旧码 Phase 4 删除）。

针对 `ConnectionCoordinator`（重写后）用虚拟时间覆盖：

| 用例 | 场景 | 期望迁移 |
|---|---|---|
| CS-1 | mDNS 新设备 | → DISCOVERED |
| CS-2 | `connectDevice` + poll Accepted | DISCOVERED→CONNECTING→CONNECTED；startHeartbeat；fetchAppListWithRetry(initiator) |
| CS-3 | `sendConnectRequest`→null | →ERROR「无法发送连接请求，目标设备无响应」 |
| CS-4 | poll Rejected | →ERROR「对方拒绝连接」 |
| CS-5 | poll Timeout（未被反向连接） | →ERROR「连接超时（30秒内未收到响应）」；`advanceTimeBy(30_000)` 驱动 |
| CS-6 | poll Timeout 但已反向连接 | 视为成功 true（SPEC §7.4） |
| CS-7 | 心跳 ping 成功 | 清 failCount；CONNECTED；lastSeen 刷新；connectionError=null |
| CS-8 | ping 失败 failCount=1 | →RECONNECTING「连接不稳定... (1/4)」 |
| CS-9 | ping 失败 1<failCount<4 | →RECONNECTING「正在尝试重新连接...」+ tryFastReconnect |
| CS-10 | failCount≥4 | →CONNECTION_TIMEOUT「连接超时，设备已离线」；removeFromConnected；停心跳/同步 |
| CS-11 | tryFastReconnect fetchDeviceInfo≠null | →CONNECTED + fetchAndEnrich |
| CS-12 | 本地 disconnect | →DISCONNECTED；**appList 保留**；sendDisconnectNotification |
| CS-13 | 远端 disconnect（displayKey/identityKey 匹配） | →DISCONNECTED；appList 保留 |
| CS-14 | 端口迁移（同 instanceId 换 ip:port） | 保留 connectionState+appList；CONNECTED/RECONNECTING 时停旧起新心跳（SPEC §7.5） |
| CS-15 | 陈旧清理 | 非 CONNECTED/RECONNECTING/CONNECTION_TIMEOUT 且不在 raw → 移除；连接态**不被清理**（SPEC §7.6） |
| CS-16 | 去重锁 | `connectDevice` 并发同 key → 仅一次生效（`connectingDevices.putIfAbsent`） |
| CS-17 | `handleIncomingRequest` 干净语义（TT3/SPEC §7.7 已冻结） | 分四例断言：**CS-17a** 配对历史命中设备来请求（无交互）→ 自动接受 `accepted`；**CS-17b** 陌生设备来请求 → 保持 PENDING/弹窗，**不自动接受**（修复旧漏洞）；**CS-17c** 陌生设备显式拒绝 → **即时 `rejected`**（非 30s 超时）；**CS-17d** 显式接受 → `accepted` + 写入 `PairingHistoryStore` |

> **并发收敛专项**（ARCHITECTURE §6）：新增压力测试——并发投递 `RawDevicesUpdated` + `HeartbeatTick` + `ConnectRequested` 事件，断言最终状态**确定且无交错损坏**（Actor/Mutex 生效）；旧实现的竞态窗口在此类测试下应暴露，重写后消除。

---

## 6. 真机互操作验收（L4，golden path 清单）

> **前提**：需至少两台 Android 真机（minSdk 29），同一局域网 WiFi。「新版本」= 重写后 APK；「旧版本」= 当前源码树构建的 APK（versionCode 1）。

### 6.1 互操作测试矩阵（验收门槛，对应 ARCHITECTURE 重构红线）
| 组合 | 发现 | 配对 | 传输 | 安装 | 备注 |
|---|---|---|---|---|---|
| 新 × 新 | ☐ | ☐ | ☐ | ☐ | 主路径 |
| 新（发起）× 旧（接收） | ☐ | ☐ | ☐ | ☐ | **兼容红线**：新实现必须能被旧端发现/配对/拉取 |
| 旧（发起）× 新（接收） | ☐ | ☐ | ☐ | ☐ | **兼容红线**：新端必须复现旧协议字节（SPEC §9） |
| 旧 × 旧 | ☐ | ☐ | ☐ | ☐ | 回归基线（证明未破坏现状） |

> **已知新旧差异点（验收时确认不破坏 golden path）**：`/api/deviceinfo` 旧构建线上含 `"version":"1.0"`、新构建省略（SPEC §2.9）；客户端从不读取该字段，故容忍。其余路由/头/命名/状态机词表一致。

### 6.2 Golden Path 逐步清单（每个组合执行一遍）
**① 发现（mDNS）**
- [ ] 双机装 APK → 均「启用同步」→ 设备 Tab 各自出现对方（deviceName = 对方 `Build.MODEL`）。
- [ ] 抓包/日志确认服务类型 `_lansync._tcp.local.`、实例名 `LanSync_{host}_{instanceId}`、TXT 双键 `deviceName`/`instanceId`（SPEC §6.1/§6.2）。
- [ ] 自过滤：本机不出现在自己列表（instanceId 相同被忽略）。
- [ ] 60s 保活：静置 >60s 设备仍在列（`REFRESH_INTERVAL_MS`）。

**② 配对（请求-轮询）**
- [ ] A 点连接 B → B 弹 `IncomingConnectionDialog`（15s 倒计时）。
- [ ] B 接受 → A 轮询 status 收到 `accepted` → 双方进入 CONNECTED。
- [ ] B 15s 不响应 → 请求自动 TIMEOUT → A 侧表现为 rejected/"Timeout" 或 30s 超时（SPEC §2.6/§7.2）。
- [ ] 反向连接（TT3 干净语义，SPEC §7.7）：① 陌生设备首次连 → A **弹窗**确认（不自动放行）；② A 接受后该设备入配对历史；③ 该设备再次连 → **自动接受**；④ A 对陌生设备点拒绝 → 对端**即时**收到 rejected（非 30s 超时）。
- [ ] 端口迁移：连接后关闭再重开 B 的服务（换端口）→ A 无缝保留 CONNECTED + appList（SPEC §7.5）。

**③ 传输（下载 + 校验）**
- [ ] 同步 Tab 出现「可更新」项（remote.versionCode > local）。
- [ ] 单 APK 应用下载：进度 0→99→100，MD5 校验通过（`X-MD5`）。
- [ ] **Split APK 应用下载**：`.apks` 落盘，`X-MD5`（zip 产物）校验通过（**关键**：证明 D1「以传输产物为准」，旧 expectedMd5 轨在此必失败——SPEC §8/DL-9）。
- [ ] 系统/受保护应用 → 403，UI 提示不可提取。
- [ ] 校验失败路径：人为篡改 → 文件被删除 + `Error("MD5 verification failed")`。

**④ 安装**
- [ ] 下载完成 → 一键安装 → FileProvider URI + ACTION_VIEW 拉起系统/第三方安装器。
- [ ] `.apks` MIME = `application/zip`、`.apk` MIME = `application/vnd.android.package-archive`（SPEC §4）。
- [ ] 文件 Tab：列举/多选/保存到 SAF/删除；包名反推显示正确（SPEC §5.4）。

**⑤ 生命周期（若引入 FGS，ARCHITECTURE §8）**
- [ ] 退后台 5 分钟：服务端 + 发现 + 心跳存活（对方仍可见本机、ping 不断）。
- [ ] 通知栏常驻显示端口/已连接数；「停止同步」按钮生效。
- [ ] 进程被杀（`adb shell am kill`）→ 重进 App 自动恢复，instanceId 不变，对端身份无缝识别。

### 6.3 手工回归清单产物
- 每组合每步记录：`lansync_debug.log` 导出 + 关键截图 + 抓包（mDNS/HTTP）。
- 失败项回填 SPEC §10 决议记录或 ARCHITECTURE 缺陷表。

---

## 7. CI 门禁与执行

| 门禁 | 命令 | 阻断条件 |
|---|---|---|
| 基线绿 | `./gradlew test`（PowerShell 用 `.\gradlew.bat test`） | 任一 L1/L2/L3 用例失败 |
| 构建绿 | `.\gradlew.bat assembleDebug` | 编译失败 |
| 分层检查 | Konsist/自定义 lint | `data/**` 出现 `import com.lansync.app.ui.*` |
| Release/R8 | `.\gradlew.bat assembleRelease` + 真机冒烟 | 安装器链路失败（Phase 2） |
| 互操作 | §6 矩阵人工签核 | 任一红线组合失败 |

> Windows/PowerShell 注意：命令分隔用 `;` 而非 `&&`；SDK 组件现状与镜像配置见项目记忆（阿里云 google 镜像，AGP 解析依赖）。
>
> **本机实测运行方式（2026-09-08，已跑通 95/95）**：`.\gradlew.bat` 因 `GRADLE_USER_HOME=E:\S.H.I.T\Gradle\GradleRepository` 下 wrapper dist 不完整，会联网下载 `gradle-8.13-bin.zip`（services.gradle.org 超时）。改用**已完整的 wrapper-dist 二进制 + 已 populate 的默认缓存**离线跑通：
> ```powershell
> $env:GRADLE_USER_HOME="C:\Users\LingTian\.gradle"
> & "C:\Users\LingTian\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" testDebugUnitTest --offline
> ```
> 机器级 init 脚本 `E:\S.H.I.T\Gradle\init.d\init.gradle` 仅注入 `aliyun/public + mavenLocal + mavenCentral`（**无 `google()`**），旧 `PREFER_PROJECT` 下覆盖 settings 镜像致 androidx 404；已将 `settings.gradle.kts` 改 `PREFER_SETTINGS` 修复（本轮验证仍全绿）。

---

## 8. TODO 决议（测试相关）

> **✅ 可验证性标注（2026-09-08 复核 + 用户确认）**：TT1（旧 APK 实物存在）与 TT3（`autoAcceptKnown` 定性为「有意设计但实现未完成」）属**用户事实/设计决策**，源码无法证实；用户已于 2026-09-08 确认两项均成立（旧 APK 存在、后续实机验证；TT3 干净语义为采纳目标，落地属 Phase 4）。TT3 涉及的**两处缺陷本身经复核为真实代码行为**（`AppRepository.handleIncomingRequest` L486–L554，见 SPEC §7.7）；TT2/TT4「未明确」项以本机实跑与 §6.2 清单为准。

| # | 原待确认项 | 用户决议 | 落地 |
|---|---|---|---|
| TT1 | 旧版 APK 实物是否存在 | **存在**，用户装入真机 | §6.1 矩阵四组合全部可执行（含新×旧/旧×旧） |
| TT2 | 旧测试是否曾 CI 跑通 | 未明确 | 以本机 `.\gradlew.bat test` 实跑结果为绿色基线起点（Phase 0 护栏） |
| TT3 | autoAcceptKnown 是 bug 还是设计 | **有意设计但实现未完成** → 改干净语义（仅历史配对成功设备自动接受、陌生设备弹窗、显式拒绝即时 rejected、修复陌生设备被自动放行漏洞） | SPEC §7.7 冻结；CS-17a–d 验证；ARCHITECTURE 新增 `PairingHistoryStore` |
| TT4 | 是否有历史手工测试脚本 | 未明确 | 以 §6.2 golden path 清单为准，Phase 1 起积累 |

---

## 9. 交付物清单与状态（2026-09-08 复核更新）

- [x] `docs/SPEC.md` —— 线上协议字段级冻结 **v1.0**（协议字段无遗留 TODO；T1/TT1、TT3 依赖用户事实/决策，已在 §10 显式标注待确认；新增 §11 Phase 1 保真核验）
- [x] `docs/ARCHITECTURE.md` —— 目标架构（已修 §3.5 接口与实现对齐、§7.1 补 REQUEST_NOT_FOUND；新增 §13 Phase 1 落地现状）
- [x] `docs/TEST-PLAN.md` —— 本文件（已更新计数、标记 Phase 1 完成项、纠正 AppScanner/503/DL-3；新增 §10 测试落地现状）

## 10. 测试落地现状（Phase 1 已完成部分）

| 计划项 | 测试文件（用例数） | 状态 |
|---|---|---|
| §2 迁移旧测试为基线 | ConnectionManagerTest(10)/UpdateManagerTest(3)/ModelsTest(9)/HashUtilsConsistencyTest(2)/AppConfigTest(2) | ✅ 在基线 |
| §2.4 HashUtils null 传播修订 | HashUtilsTest(6，原 4) | ✅ 已修订 |
| §2.3 补 findUpdates 缺口 | sync/UpdateManagerTest(9) | ✅ Phase 3 已补（findUpdates + calculateSyncDiffs + dedup 平级） |
| §2.3 补 ModelsTest encodeDefaults 单测快照 | ModelsTest(13) | ✅ Phase 3 已落地（+4 字节快照；路由层亦保留断言） |
| §3 路由特征化测试 | LanSyncRoutingTest(33) | ✅ 已落地 |
| §4 下载+MD5 链路 | LanSyncClientTest(12) + DownloadedFileNameTest(10) | ✅ 部分（DL-6/7/8/9/10 待补） |
| §5 配对状态机（接收方） | InMemoryPairingStoreTest(9) | ✅ 已落地（含迁移 clearAll） |
| §5 连接状态机 CS-1…CS-17（ConnectionCoordinator） | DefaultConnectionCoordinatorTest(21) | ✅ Phase 2 已落地 |
| §6 真机互操作矩阵 | — | ⏳ 待接线后执行（T1 旧 APK 实物已确认存在，用户将于后续功能测试阶段实机验证） |

> **合计（Phase 3 末）**：**14 文件 141 例**（= Phase 2 的 117 + `sync.UpdateManagerTest` 9 + `transfer.AppPackerTest` 6 + `localapps.LocalAppRepositoryTest` 5 + `ModelsTest` 增 4），0 失败/0 错误/0 跳过（均 JVM 单元/集成，无 instrumented）。权威阶段状态见仓库根 `PROGRESS.md`。
> **✅ 绿色基线已跑通（2026-09-08）**：`testDebugUnitTest`（Gradle 8.13，离线，`GRADLE_USER_HOME=C:\Users\LingTian\.gradle`）实跑 **95/95 全绿，0 失败 0 错误 0 跳过**（`LanSyncRoutingTest` 33 / `LanSyncClientTest` 12 / `DownloadedFileNameTest` 10 / `ConnectionManagerTest` 10 / `ModelsTest` 9 / `InMemoryPairingStoreTest` 8 / `HashUtilsTest` 6 / `UpdateManagerTest` 3 / `AppConfigTest` 2 / `HashUtilsConsistencyTest` 2）；应用构建卫生修复（移除死依赖/jetifier、`PREFER_SETTINGS`）后**复跑仍 95/95 全绿**。运行方式见 §7 本机实测注。

> **停止点**：Phase 3（扫描/打包/更新推荐）已跑通 **141/141 全绿** + `assembleDebug` 通过。权威阶段状态/裁决/决策日志见仓库根 **`PROGRESS.md`**（每阶段结束更新并 git commit）。下一步 Phase 4（协调层收尾 + 接线）待用户确认。

---

*（TEST-PLAN.md 结束。）*
