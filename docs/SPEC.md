# LanSync 线上协议规范（Wire Protocol SPEC）

> **文档状态**：**v1.0 · 已冻结（FROZEN）** — Phase 0 交付物 · 字段级冻结（Field-Level Freeze）
> **冻结日期**：2026-09-08 · 冻结依据：当前源码树 + 全量 git 历史查证（见 §10 决议）
> **适用范围**：LanSync 设备间局域网通信的全部线上契约（JSON payload / HTTP API / mDNS 发现 / 文件传输 / 连接状态机）。
> **重构原则**：**契约不变，实现重写**。本文件描述的每一个字节级行为都是新实现必须复现的验收标准；任何偏离都必须在《互操作测试矩阵》中显式记录。
> **证据基线**：本规范全部字段与行为均直接取自当前源码（非 REPORT.md 转述）；历史相关结论经 `git log -p`/pickaxe 全量查证。
> **2026-09-08 全量复核**：本轮已逐条重读 `Models.kt`/`AppConfig.kt`/`KtorServer.kt`/`AppListClient.kt`/`ConnectionManager.kt`/`JmDNSDiscovery.kt`/`AppRepository.kt`/`AppPacker.kt`/`HashUtils.kt`/`AppScanner.kt`/`NetworkUtils.kt`/`ApkInstaller.kt`/`UpdateManager.kt` 核验 §1–§8，**全部协议字段与行为与源码一致，无 `[TODO:需从旧代码确认]` 遗留**（旧协议细节均可从工作树源码直接确认，无需用户另行提供源文件）。仅 §10 的 T1/TT1、TT3 依赖**用户事实/设计决策**（非源码可验证），已在 §10 显式标注。Phase 1 重写代码的契约保真核验见 **§11**。

---

## 0. 证据来源（已核对的旧源文件）

| 契约领域 | 权威源文件 |
|---|---|
| 数据模型 / DTO | `app/src/main/java/com/lansync/app/data/model/Models.kt` |
| HTTP 服务端路由 / 响应头 / 错误码 | `app/src/main/java/com/lansync/app/data/server/KtorServer.kt` |
| HTTP 客户端 / 下载 / 校验 / 文件命名 | `app/src/main/java/com/lansync/app/data/client/AppListClient.kt` |
| 配对请求状态机 / 超时常量 | `app/src/main/java/com/lansync/app/data/connection/ConnectionManager.kt` |
| mDNS 服务注册 / TXT / 保活 | `app/src/main/java/com/lansync/app/data/discovery/JmDNSDiscovery.kt` |
| 连接编排 / 心跳 / 端口迁移 / ConnectionState 迁移 | `app/src/main/java/com/lansync/app/data/repository/AppRepository.kt` |
| 打包产物命名 / zip 结构 | `app/src/main/java/com/lansync/app/data/packer/AppPacker.kt` |
| 哈希算法 | `app/src/main/java/com/lansync/app/data/HashUtils.kt` |
| 本机应用扫描 / AppInfo.md5 来源 | `app/src/main/java/com/lansync/app/data/scanner/AppScanner.kt` |
| 版本比较 / 去重 | `app/src/main/java/com/lansync/app/data/update/UpdateManager.kt` |
| 超时/间隔参数 | `app/src/main/java/com/lansync/app/data/AppConfig.kt` |
| IP 选取语义 | `app/src/main/java/com/lansync/app/data/NetworkUtils.kt` |
| 安装 MIME / FileProvider | `app/src/main/java/com/lansync/app/data/installer/ApkInstaller.kt` |

---

## 1. 全局约定

### 1.1 传输层
- **协议**：HTTP/1.1，**明文**（`usesCleartextTraffic=true` + `network_security_config.xml`）。无 TLS、无鉴权头、无 Cookie/Session。
- **URL 模板**：`http://{ipAddress}:{port}{path}`，`port` 为服务端动态分配端口（见 §5.2）。
- **字符编码**：JSON 体为 UTF-8；文本响应体为 UTF-8。

### 1.2 JSON 序列化配置（冻结）
服务端与客户端使用**完全一致**的 `kotlinx.serialization.json.Json` 配置：

```
Json {
    prettyPrint = true        // 输出带缩进/换行（不影响语义，但影响字节）
    isLenient = true          // 允许非严格 JSON（未加引号的 key/字面量）
    ignoreUnknownKeys = true  // 解码时忽略未知字段 → 向前兼容
    // encodeDefaults 未设置 → 取默认值 false（关键！见下）
}
```
证据：`KtorServer.start()` 的 `ContentNegotiation{ json(...) }`；`AppListClient.json`。

> **本地缓存例外**：`AppRepository` 读写 `local_apps_cache.json` 用的是 `Json { ignoreUnknownKeys = true }`（`prettyPrint=false`、`isLenient=false`）。此为**本机私有持久化格式，不属于线上协议**，但重写时若改变需保证旧缓存文件仍可解析。

#### 1.2.1 `encodeDefaults = false` 的字节级后果（**必须复现**）
kotlinx.serialization 默认 **不序列化等于默认值的可选字段**。因此线上 JSON 会**省略**取默认值的字段：

| 场景 | 构造 | 实际线上 JSON |
|---|---|---|
| `GET /api/ping` | `GenericStatusResponse(status="pong")` | `{"status":"pong"}` |
| `POST /api/disconnect` 成功 | `GenericStatusResponse()`（status 默认 "ok"） | `{}`（空对象，prettyPrint 下为 `{\n}`） |
| `POST /api/refresh-applist` 成功 | `GenericStatusResponse()` | `{}` |
| `GET /api/deviceinfo` | `DeviceInfoResponse(deviceName=X)`（version 默认 "1.0"） | `{"deviceName":"X"}` — **不含 version 字段**（注：`ad89d61` 之前的旧构建手写 JSON 曾发 `"version":"1.0"`，详见 §2.9） |
| connect/status 拒绝态 | `ConnectStatusResponse(status="rejected", accepted=false, ...)` | `accepted=false` 等于默认值 → **被省略** |

- **必填字段**（无默认值）：解码时缺失会抛 `SerializationException` → 触发对应路由的错误分支。
- **可选字段**（有默认值）：解码时可缺失，缺失即取默认值。
- **重写约束**：新实现的 DTO 序列化必须保持 `encodeDefaults=false` 语义；若改用其它序列化器，必须保证「等于默认值的可选字段被省略」，否则旧客户端虽因 `ignoreUnknownKeys` 不至于崩，但字节快照测试会失败。

### 1.3 枚举序列化
- Kotlin `enum` 默认按 **`name`**（大写下划线原文）序列化，无 `@SerialName` 覆盖。
- 涉及枚举：`ConnectionState`（DISCOVERED/CONNECTING/CONNECTED/ERROR/DISCONNECTED/RECONNECTING/CONNECTION_TIMEOUT）。
- 注意：`ConnectionState` 仅出现在 `DeviceInfo` 中，而 **`DeviceInfo` 当前不经过任何 HTTP 路由传输**（见 §2.2）。故枚举上线仅发生在本机缓存/内部状态，不属于跨设备字节契约。

---

## 2. 数据模型 JSON Schema（字段级冻结）

> 表格列含义：**类型** = Kotlin 类型；**可空** = 是否 `?`；**默认** = 构造默认值（决定 `encodeDefaults=false` 下是否省略）；**线上必填** = 解码时是否必须存在（无默认值即必填）。

### 2.1 `AppInfo` —— 网络传输的原子 payload（`@Serializable`）
唯一经由 `GET /api/applist` 与 `List<AppInfo>` 上线的核心模型。

| 字段 | 类型 | 可空 | 默认 | 线上必填 | 说明 |
|---|---|---|---|---|---|
| `packageName` | String | 否 | 无 | **是** | 应用包名 |
| `appName` | String | 否 | 无 | **是** | 显示名（`loadLabel`） |
| `versionName` | String | 否 | 无 | **是** | 版本名；扫描不到时为 `"unknown"` |
| `versionCode` | Long | 否 | 无 | **是** | `longVersionCode`（JSON 数字，注意 JS 端精度，本项目全 Kotlin 无碍） |
| `sourcePaths` | List\<String\> | 否 | 无 | **是** | **本机 APK 绝对路径列表**；不可提取时为 `[]` |
| `md5` | String | 否 | 无 | **是** | **原始 APK 文件拼接摘要**（见 §8）；不可提取时为 `""` |
| `isExtractable` | Boolean | 否 | 无 | **是** | 是否可打包传输 |
| `fileSize` | Long | 否 | 无 | **是** | 所有 sourcePaths 文件字节数之和 |
| `isSystemApp` | Boolean | 否 | `false` | 否 | `FLAG_SYSTEM`；= false 时线上省略 |
| `isSplitApk` | Boolean | 否 | `false` | 否 | `sourcePaths.size > 1`；= false 时线上省略 |

> **安全注记（冻结现状）**：`sourcePaths` 与 `md5` 原样跨设备暴露。`sourcePaths` 是**提供方本机绝对路径**，接收方**绝不可**据此访问或信任；下载仅依据 `packageName`+`versionCode`+`X-MD5`。剥离 `sourcePaths` 属 Phase 6 变更，**不在本次冻结契约内**（见 §9 兼容性）。

### 2.2 `DeviceInfo` —— 内部设备模型（`@Serializable`，**当前不上线**）
| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `ipAddress` | String | 否 | 无 | 设备 IPv4 |
| `deviceName` | String | 否 | 无 | 设备名（`Build.MODEL`） |
| `port` | Int | 否 | 无 | 服务端口 |
| `instanceId` | String | 否 | `""` | 持久化 UUID（身份稳定标识） |
| `appList` | List\<AppInfo\> | 否 | `[]` | 该设备应用列表 |
| `connectionState` | ConnectionState | 否 | `DISCOVERED` | 见 §7 |
| `lastSeenTimeMs` | Long | 否 | `System.currentTimeMillis()` | 最近可见时间 |
| `connectionError` | String? | **是** | `null` | 用户可见错误串 |
| `displayKey`（计算属性） | String | — | — | `"$ipAddress:$port"`（**不序列化**，`get()` 派生） |
| `identityKey`（计算属性） | String | — | — | `instanceId` 非空取之，否则 `"$deviceName@$ipAddress"`（**不序列化**） |

> **冻结结论**：当前**没有任何 HTTP 路由**收发 `DeviceInfo`。`@Serializable` 注解为历史遗留/未来预留。跨设备传递设备信息的唯一载体是 §2.9 `DeviceInfoResponse`（仅 deviceName）与 mDNS TXT（§6）。新实现**不得**新增 `DeviceInfo` 上线路径而不走协议版本协商。

### 2.3 `ConnectRequestPayload` —— `POST /api/connect/request` 请求体（`@Serializable`）
| 字段 | 类型 | 可空 | 默认 | 线上必填 | 说明 |
|---|---|---|---|---|---|
| `requestId` | String | 否 | 无 | **是** | 发起方 `UUID.randomUUID()` |
| `requesterName` | String | 否 | 无 | **是** | 发起方设备名 |
| `requesterIp` | String | 否 | 无 | **是** | 发起方 IP（`NetworkUtils.getLocalIpAddress()`，见 §6.4） |
| `requesterPort` | Int | 否 | 无 | **是** | 发起方服务端口 |
| `requesterInstanceId` | String | 否 | `""` | 否 | 发起方持久化 UUID；空则线上省略 |
| `timestamp` | Long | 否 | 无 | **是** | `System.currentTimeMillis()` |

### 2.4 `ConnectResponseBody` —— `POST /api/connect/response/{requestId}` 请求体（`@Serializable`）
| 字段 | 类型 | 可空 | 默认 | 线上必填 | 说明 |
|---|---|---|---|---|---|
| `accepted` | Boolean | 否 | 无 | **是** | 接收方是否接受 |

线上 JSON：`{"accepted":true}` 或 `{"accepted":false}`。

### 2.5 `ConnectResponsePayload` —— `POST /api/connect/response/{requestId}` 响应体（`@Serializable`）
| 字段 | 类型 | 可空 | 默认 | 线上必填 | 说明 |
|---|---|---|---|---|---|
| `requestId` | String | 否 | 无 | **是** | 回显 |
| `accepted` | Boolean | 否 | 无 | **是** | 结果 |
| `responderName` | String? | **是** | `null` | 否 | 响应方设备名；null 省略 |
| `message` | String? | **是** | `null` | 否 | `"Connection accepted"` / `"Connection rejected"` / `"Accepted"` / `"Rejected"` / `"Timeout"` |

> 客户端 `sendConnectResponse` **只判 `response.isSuccessful`，不解析此响应体**。此体为契约完整性保留。

### 2.6 `ConnectStatusResponse` —— `GET /api/connect/status/{requestId}` 与 `POST /api/connect/request` 响应体（`@Serializable`）
| 字段 | 类型 | 可空 | 默认 | 线上必填 | 说明 |
|---|---|---|---|---|---|
| `status` | String | 否 | 无 | **是** | **状态字符串**：`"pending"` / `"accepted"` / `"rejected"`（客户端唯一依赖字段） |
| `requestId` | String? | **是** | `null` | 否 | null 省略 |
| `accepted` | Boolean | 否 | `false` | 否 | =false 时省略 |
| `responderName` | String? | **是** | `null` | 否 | accepted 时携带；null 省略 |
| `message` | String? | **是** | `null` | 否 | rejected 时携带 |

**`status` 取值来源（服务端 `KtorServer` + `ConnectionManager.getStatus`）**：
- 请求已登记但未响应（`RequestStatus.PENDING`）→ `getStatus` 返回 `null` → 路由回 `ConnectStatusResponse(status="pending")`。
- `requestId` 为空 / `connectionManager` 为 null / 路由异常 → 一律回 `status="pending"`（**失败安全**：让发起方继续轮询而非硬失败）。
- 已接受（`ACCEPTED`）→ `status="accepted"`, `accepted=true`, `responderName=本地名`, `message="Accepted"`。
- 已拒绝（`REJECTED`）→ `status="rejected"`, `accepted=false`(省略), `responderName=本地名`, `message="Rejected"`。
- 已超时（`TIMEOUT`，接收方 15s 自动置位）→ `getStatus` 映射为 `accepted=false, message="Timeout", responderName=null` → 路由 `status = if(accepted)"accepted" else "rejected"` → **上线为 `status="rejected"`, `message="Timeout"`**。

> **冻结要点**：接收方侧的「超时」对发起方表现为 `status="rejected"` + `message="Timeout"`，**不是**独立的 "timeout" status。发起方 `tryParseConnectStatus` 只识别 `pending/accepted/rejected` 三值，其余（含未知值）→ 继续轮询。

### 2.7 `DisconnectPayload` —— `POST /api/disconnect` 请求体（`@Serializable`）
| 字段 | 类型 | 可空 | 默认 | 线上必填 | 说明 |
|---|---|---|---|---|---|
| `displayKey` | String | 否 | 无 | **是** | 断开方自身 `"$ip:$port"` |
| `identityKey` | String | 否 | `""` | 否 | 断开方 `instanceId`；空则省略 |

> 服务端处理：`key = if(identityKey.isNotEmpty()) identityKey else displayKey`，据此匹配并断开。

### 2.8 `RefreshAppListPayload` —— `POST /api/refresh-applist` 请求体（`@Serializable`）
| 字段 | 类型 | 可空 | 默认 | 线上必填 | 说明 |
|---|---|---|---|---|---|
| `displayKey` | String | 否 | 无 | **是** | 通知方自身 `"$ip:$port"`；服务端据此重新拉取该设备列表 |

### 2.9 `DeviceInfoResponse` —— `GET /api/deviceinfo` 响应体（`@Serializable`）
| 字段 | 类型 | 可空 | 默认 | 线上必填 | 说明 |
|---|---|---|---|---|---|
| `deviceName` | String | 否 | 无 | **是** | 服务端设备名（`Build.MODEL`） |
| `version` | String | 否 | `"1.0"` | 否 | **死字段（vestigial）**：全 git 历史恒为字面常量 `"1.0"`，从未承载真实信息 |

> **T2 决议（git 全历史查证，已冻结）**：
> - pickaxe `-G "DeviceInfoResponse\([^)]*version"` 与 `-G "version\s*=\s*BuildConfig|...versionName"` **全历史空结果** → `version` **从未**被显式赋真实值，恒为默认常量 `"1.0"`。
> - **线上形态在 `ad89d61` 处发生变迁**：该 commit **之前**（含 `f9cbb6e` 初始版）`/api/deviceinfo` 为手写 JSON `respondText("""{"deviceName":"$escapedName","version":"1.0"}""", Application.Json)` → **旧构建线上确实含 `"version":"1.0"`**；该 commit **之后**改用 `@Serializable` DTO，因 `encodeDefaults=false` → `version` **从线上消失**，现为 `{"deviceName":"X"}`。
> - 客户端 `fetchDeviceInfo` 解码为 `Map<String,String>` 且**仅判 `!= null`**（用于 `tryFastReconnect` 探活），**从不读取 `version`** → 新旧两种形态均被容忍，互操作无碍。
> - **冻结决策（依用户指示）**：`version` 视为**不可信死字段**，**新代码不得依赖它做任何兼容判断**。Phase 6 协议版本协商**必须新增独立字段**（如 mDNS TXT `proto` + deviceinfo 新增 `protocolVersion`），**禁止复用** `version`。新实现可保留该字段（默认 "1.0"、线上省略）以维持 DTO 形状，但不得赋予其语义。

### 2.10 `GenericStatusResponse` —— 通用响应体（`@Serializable`）
| 字段 | 类型 | 可空 | 默认 | 线上必填 | 说明 |
|---|---|---|---|---|---|
| `status` | String | 否 | `"ok"` | 否 | `/api/ping` 显式传 `"pong"`；`disconnect`/`refresh-applist` 用默认 `"ok"` → 线上省略 → `{}` |

### 2.11 `IncomingConnectRequest` —— **内部**配对请求模型（**非 `@Serializable`**）
接收方内存态，驱动 UI 弹窗；**从不上线**（上线的是 §2.6 `ConnectStatusResponse`）。
| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `requestId` | String | 无 | |
| `requesterName` / `requesterIp` | String | 无 | 来自 payload |
| `requesterPort` | Int | 无 | |
| `requesterInstanceId` | String | `""` | |
| `timestamp` | Long | 无 | |
| `status` | RequestStatus | `PENDING` | 枚举 `PENDING/ACCEPTED/REJECTED/TIMEOUT` |
| `identityKey`（计算属性） | String | — | `requesterInstanceId` 非空取之，否则 `"$requesterName@$requesterIp"` |

---

## 3. HTTP API 全表（冻结）

> **通用**：所有 JSON 响应 `Content-Type: application/json`（Ktor ContentNegotiation 自动）。文本错误响应经 `call.respondText(...)`，`Content-Type: text/plain; charset=UTF-8`。文件下载见 §5。

| # | 方法 | 路径 | 请求体 | 成功响应 | 错误响应 |
|---|---|---|---|---|---|
| 1 | GET | `/api/ping` | — | `200` `{"status":"pong"}` | 无（不抛错） |
| 2 | GET | `/api/applist` | — | `200` `[AppInfo,...]`（provider 为 null 时 `[]`） | 无（provider 缺失回空数组） |
| 3 | GET | `/api/deviceinfo` | — | `200` `{"deviceName":"X"}` | 无 |
| 4 | POST | `/api/connect/request` | `ConnectRequestPayload` | `200` `{"status":"pending","requestId":"..."}` | `409` 重复；`400` 解析失败；`503` 服务未就绪 |
| 5 | GET | `/api/connect/status/{requestId}` | — | `200` `ConnectStatusResponse`（见 §2.6） | 无（异常/缺失一律 `200 {"status":"pending"}`） |
| 6 | POST | `/api/connect/response/{requestId}` | `ConnectResponseBody` | `200` `ConnectResponsePayload` | `400` manager 缺失/requestId 空；`404` 请求不存在或已处理；`500` 异常 |
| 7 | GET | `/api/download/{packageName}` | — | `200` 文件流（最高 versionCode） | `403` 不可提取；`404` 无匹配；`500` 打包失败/异常 |
| 8 | GET | `/api/download/{packageName}/{versionCode}` | — | `200` 文件流（精确版本） | `404` 无匹配；`403` 不可提取；`500` 打包失败/异常 |
| 9 | POST | `/api/disconnect` | `DisconnectPayload` | `200` `{}` | `400` 解析失败 |
| 10 | POST | `/api/refresh-applist` | `RefreshAppListPayload` | `200` `{}` | `400` 解析失败 |

### 3.1 逐路由错误码语义（冻结）

**#4 `POST /api/connect/request`**
- `200`：`connectionManager.receiveRequest(payload)` 返回 true（新请求登记成功）。体 `{"status":"pending","requestId":<回显>}`。
- `409 Conflict`：`receiveRequest` 返回 false（`pendingRequests` 已含相同 `requestId`）。体 `text/plain` `"Duplicate request"`。
- `400 BadRequest`：`call.receive<ConnectRequestPayload>()` 抛异常（JSON 缺必填字段/格式错误）。体 `"Invalid request: ${e.message}"` ⚠️**回显异常细节**。
- `503 ServiceUnavailable`：`connectionManager == null`（`setDeviceName` 未调用）。体 `"Server not ready"`。

**#5 `GET /api/connect/status/{requestId}`**
- 恒 `200`。`requestId` 空 / manager null / `getStatus` 返回 null / 抛异常 → `{"status":"pending"}`（失败安全，见 §2.6）。

**#6 `POST /api/connect/response/{requestId}`**
- `200`：`respondToRequest` 返回非 null → `ConnectResponsePayload`。
- `400 BadRequest`：manager null 或 `requestId` 空 → `"Invalid request"`。
- `404 NotFound`：`respondToRequest` 返回 null（`pendingRequests` 无此 id，或已 removeRequest）→ `"Request not found or already handled"`。
- `500 InternalServerError`：`call.receive` 或处理抛异常 → `"Error: ${e.message}"` ⚠️**回显异常细节**。

**#7 `GET /api/download/{packageName}`（取最高版本）**
- 候选 = `appList` 中 `packageName` 匹配且 `isExtractable==true` 者；`app = maxByOrNull { versionCode }`。
- `403 Forbidden`：存在同包名但**全部** `isExtractable==false`（`nonExtractable && extractableApps.isEmpty()`）→ `"App is a system/protected app and cannot be extracted for transfer"`。
- `404 Not Found`：无任何匹配 `app`（`app == null`）→ `"App not found"`。
- `500 InternalServerError`：`packer` 返回 null / 文件不存在 → `"Failed to pack app"`；打包抛异常 → `"Error packing app: ${e.message}"` ⚠️。
- `200`：打包成功 → `sendZipFile`（见 §5）。

**#8 `GET /api/download/{packageName}/{versionCode}`（精确版本）**
- `versionCode` 解析：`call.parameters["versionCode"]?.toLongOrNull() ?: 0L`（**非法值降级为 0L**，随后因无匹配走 404）。
- `404 Not Found`：无 `packageName && versionCode` 精确匹配 → `"App not found"`。
- `403 Forbidden`：匹配到但 `!isExtractable` → 同 #7 文案。
- `500 InternalServerError`：同 #7（`"Failed to pack app"` / `"Error packing app: ${e.message}"`）。
- `200`：`sendZipFile`。

> **两路由差异（冻结）**：#7 先判「全部不可提取→403」再取最高版本；#8 先判「无匹配→404」再判「不可提取→403」。错误码优先级不同，重写时**必须逐路由保持**，否则互操作回归。合并 handler 属 Phase 1 重构，须以特征化测试锁定两路由各自的状态码矩阵后方可合并。

**#9 `POST /api/disconnect`** / **#10 `POST /api/refresh-applist`**
- `200`：解析成功（refresh 仅在 `displayKey` 非空时触发 handler）→ `{}`。
- `400 BadRequest`：解析异常 → `"Invalid request"`。

### 3.2 错误响应体统一现状（**待重构，非冻结目标**）
当前所有错误走 `respondText(纯文本)`，且 **#4/#6/#7/#8 至少 5 处直接拼接 `${e.message}`**（`KtorServer.kt` L113/L172/L206/L248 等）。
- **冻结现状**：客户端**不解析**错误体（下载失败仅取 `body.take(200)` 塞进 `DownloadResult.Error` 文案；连接类只看 `isSuccessful`）。故错误体文本可自由变更而不破坏互操作。
- **重写目标（ARCHITECTURE.md §7）**：统一 `LanSyncErrorDto(code, message)` + 错误码枚举，`e.message` 仅进 `FileLogger`。此为**内部改进**，因客户端不解析错误体，**不构成线上契约破坏**。

---

## 4. Content-Type 规则（冻结）

| 响应类别 | Content-Type | 来源 |
|---|---|---|
| 所有 JSON DTO / 数组 | `application/json` | Ktor ContentNegotiation |
| `respondText` 错误体 | `text/plain; charset=UTF-8` | Ktor 默认 |
| 下载 `.apk`（单包） | `application/octet-stream` | `sendZipFile`：`isSingleApk` 判定 |
| 下载 `.apks`（split zip） | `application/zip` | `sendZipFile` |

- `isSingleApk = file.name.endsWith(".apk") && !file.name.endsWith(".apks")`（因 `.apks` 也以 `.apk` 结尾，须显式排除）。
- 安装侧 MIME（`ApkInstaller.getMimeType`，与传输侧独立但需一致认知）：`.apks`→`application/zip`；`.apk`→`application/vnd.android.package-archive`；其它→`application/octet-stream`。

> **T5 决议（已读旧代码 Ktor 客户端/服务端配置，已冻结）**：客户端 `AppListClient.performDownload` **完全不校验响应 Content-Type**（只读 `X-MD5`/`Content-Disposition`/`X-File-Size`/body）。故「旧版实际兼容的写法」= 服务端 `sendZipFile` 现状：`.apk`→`application/octet-stream`、`.apks`→`application/zip`。**新版头部写法照此复现即可**，接收端不依赖 Content-Type，无互操作风险。

---

## 5. 文件下载契约

### 5.1 下载响应头（冻结）
`sendZipFile(call, file, storedMd5)` 设置：

| 响应头 | 值 | 说明 |
|---|---|---|
| `X-MD5` | `HashUtils.md5(file) ?: ""` | **对打包产物文件实时计算的 MD5**（见 §8）。计算失败为 `""` |
| `X-File-Size` | `file.length().toString()` | 产物字节数 |
| `Content-Disposition` | `attachment; filename="{file.name}"` | 文件名 = 打包产物名（见 §5.3），双引号包裹 |
| `Content-Type` | 见 §4 | |

> `sendZipFile` 的 `md5` 入参（= `app.md5`，原始拼接摘要）**仅用于日志对比**，**不写入任何响应头**。上线的 `X-MD5` 恒为产物实时哈希。这是 §8「以传输产物为准」决策的现状依据。

### 5.2 端口分配
- 服务端 `embeddedServer(Netty, port = 0)` → OS 动态分配；实际端口 = `resolvedConnectors().first().port`，写入 `_serverPort`。
- 该端口同时用于：mDNS 服务注册（§6）、`ConnectRequestPayload.requesterPort`、`displayKey`。

### 5.3 下载文件命名约定（冻结）
**服务端产物命名（`AppPacker.packApp`，输出 `cacheDir/apks/`）**：
- `packageName` 中的 `.` 全替换为 `_`：`val pkg = packageName.replace(".", "_")`。
- Split APK（`isSplitApk==true`）→ `{pkg}_{versionCode}.apks`。
- 单 APK（`isSplitApk==false`）→ `{pkg}_{versionCode}.apk`。
- 例：`com.example.app` v123 → `com_example_app_123.apks`（split）或 `com_example_app_123.apk`（单）。

**`.apks` 内部结构（`createApksFile`）**：标准 zip，条目名 `base.apk`（sourcePaths[0]）、`split_1.apk`、`split_2.apk`…（sourcePaths[index]，index≥1）。不可读条目被跳过（`canRead()` 检查）。

**客户端落盘命名（`AppListClient.performDownload`，输出 `cacheDir/downloads/`）**：
1. **优先**取 `Content-Disposition` 的 `filename`：`substringAfter("filename=\"").substringBeforeLast("\"")`。
2. 缺失时用 `defaultFileName`：
   - `downloadApksFile`（精确版本）→ `{packageName.replace(".","_")}_{versionCode}.apks`。
   - `downloadLatestApksFile`（最新版）→ `{packageName.replace(".","_")}.apks`（**无 versionCode 段**）。
> 因服务端**恒发** `Content-Disposition`，实际落盘名 = 服务端产物名（`.apk` 或 `.apks` 由 split 与否决定）。`defaultFileName` 恒为 `.apks` 仅是兜底。

### 5.4 包名解析规则（从文件名反推，冻结）
`AppListClient.extractPackageName(fileName)`（`MainViewModel.extractPackageNameFromFile` 为**重复实现**，逻辑一致）：
1. 去扩展名：`.apks` 优先，其次 `.apk`，否则原样。
2. `parts = nameWithoutExt.split("_")`。
3. `versionEnd = parts.indexOfLast { it.toLongOrNull() != null }`（最后一个纯数字段）。
4. `versionEnd <= 0` → 整名 `replace("_", ".")`；否则 `parts.take(versionEnd).joinToString(".")`。

> **已知脆弱性（冻结现状，非目标）**：包名自身含数字段（如 `com.app2.game`）或产物无版本段时会误判。`getDownloadedFile(pkg, vc)` 用正向构造 `{pkg.replace(".","_")}_{vc}.apks`/`.apk` 精确匹配，**不依赖反推**，是安装取文件的主路径；反推仅用于文件管理列表展示 `packageName`。收敛重复实现属 Phase 1。

### 5.5 客户端下载 HTTP 配置（冻结）
`downloadClient`（区别于常规 `client`）：`connectTimeout=30s`、`readTimeout=120s`、`writeTimeout=120s`、`callTimeout=0`（**不限**）、连接池 `maxIdle=2/keepAlive=1min`、`followRedirects=true`。
常规 `client`：`connect/read/write=15s`、`callTimeout=30s`、池 `maxIdle=5/keepAlive=5min`。

### 5.6 下载校验与落盘流程（冻结）
1. `!response.isSuccessful` → `DownloadResult.Error("HTTP error: {code}: {body.take(200)}")`。
2. `serverMd5 = header("X-MD5") ?: ""`。
3. body 为空 → `Error("Empty response body")`。
4. `contentLength = body.contentLength()>0 ? it : header("X-File-Size").toLongOrNull() ?: -1L`。
5. 流式写盘（buffer 65536），进度 `percent = totalBytesRead*100/contentLength` coerceIn(0,100)，仅在 `contentLength>0` 时回调。
6. 落盘后 `!exists() || length()==0` → `Error("Downloaded file is empty")`。
7. `actualMd5 = HashUtils.md5(destination) ?: ""`；`verifyMd5 = serverMd5.ifEmpty { expectedMd5 }`。
8. `actualMd5 != verifyMd5 && verifyMd5.isNotEmpty()` → **删除文件** + `Error("MD5 verification failed")`。
9. 否则 `Success(destination)`。
> `expectedMd5` 由调用方传入（`AppRepository.downloadApp` 传 `updateInfo.remoteApp.md5`，即 §8 的原始拼接摘要）。**§8 决策将废弃此兜底轨。**

---

## 6. JmDNS 服务发现规范（冻结）

### 6.1 服务类型与实例名
- **服务类型**：`_lansync._tcp.local.`（构造默认值 `serviceType`）。
- **实例名（service name）**：`LanSync_{hostname}_{instanceId}`
  - `serviceName` 常量 = `"LanSync"`；`hostname = getDeviceName() = Build.MODEL`（异常时 `"UnknownDevice"`）；`instanceId` 见 §6.3。
  - 注册：`ServiceInfo.create(serviceType, name, port, weight=0, priority=0, props)`。

### 6.2 TXT 记录
- **props（TXT）**：`{ "deviceName" -> hostname, "instanceId" -> instanceId }`（恰两个键）。
- 解析端 `addOrUpdateDevice`：
  - `instanceId = info.getPropertyString("instanceId")`；**空/null → 忽略**（非 LanSync 设备）。
  - `instanceId == 本机 instanceId` → **忽略自身**。
  - `deviceName`：优先 TXT `deviceName`；空则从实例名 `extractDeviceNameFromServiceName`（`split("_")`，`parts[0]=="LanSync"` 取 `parts[1]`，否则整名）。
  - IP：`inetAddresses` 中**首个 Inet4Address**，无则 `addresses[0]`。

> **T3 决议（已冻结）**：**不做字节级 DNS-SD 冻结**，只需**应用层键值契约一致**：TXT 恰含 `deviceName`/`instanceId` 两键，经 JmDNS 库 `getPropertyString(key)` 读写。
> **无升级项**：已全仓查证（`grep getPropertyString|getPropertyBytes|getPropertyNames|txtRecord`）——TXT 值**全部经 JmDNS 3.5.8 库 API 读取，不存在手写 TXT 字节解析逻辑**，故不触发用户设定的「发现手写 TXT 解析则升级」条件。（`extractDeviceNameFromServiceName` 解析的是**服务实例名**而非 TXT，且仅在 TXT `deviceName` 缺失时兜底；本项目恒发 TXT `deviceName`，该路径实际不触发。）
> **T4 决议（已冻结）**：`deviceName`（= `Build.MODEL`）按 **UTF-8 标准**编码写入 TXT / 实例名，与库默认一致；旧代码运行中未见明显问题，新版沿用 UTF-8 标准，不做特殊转义增强。

### 6.3 `instanceId`（身份稳定标识）
- 持久化于 `SharedPreferences("lansync_device")`，键 `"device_instance_id"`，值 `UUID.randomUUID().toString()`（首次生成后永久复用）。
- 用途：IP/端口变化后仍识别同一设备（端口迁移依据，见 §7.4）；自过滤；`DeviceInfo.identityKey`。

### 6.4 本机绑定 IP 语义
- mDNS 绑定地址：`NetworkUtils.getLocalIpAddressViaWifi(context)` = WiFi `connectionInfo.ipAddress`；为 0 时回退首个非环回 Inet4；再回退 `127.0.0.1`。
- `ConnectRequestPayload.requesterIp` / `displayKey` 的 IP：`NetworkUtils.getLocalIpAddress()` = **首个非环回 Inet4 网卡地址**（不特指 WiFi）。

> **T6 决议（已冻结）**：**目标场景 = 同一 Wi-Fi 局域网内两台设备**。多网卡/热点/VPN **不承诺支持**，行为**对齐旧版、不做增强**。上述两条取值路径（WiFi 绑定 vs 首个 Inet4）**原样保留**，即使在多网络下可能分歧也不修正——单 Wi-Fi 场景两者一致，满足目标场景。新实现**不得**引入网络选择/路由绑定等增强逻辑。

### 6.5 保活与陈旧清理
- **60s 周期**（`REFRESH_INTERVAL_MS = 60_000L`）：`jmdns.list(serviceType)` 全量拉取 → 重建 `currentKeys` → `addOrUpdateDevice` → 移除 `deviceMap.keys - currentKeys` 的陈旧项。
- 事件驱动：`ServiceListener` 的 `serviceAdded`（→ `requestServiceInfo(type,name,true,3000)`）、`serviceRemoved`（按 instanceId 过滤后移除）、`serviceResolved`（→ `addOrUpdateDevice`）。
- **多播锁**：`MulticastLock("LanSyncMulticastLock")`，`setReferenceCounted(true)`，start 时 `acquire()`，stop 时 `release()`。无锁收不到多播。
- 输出：`discoveredDevices: Flow<List<DeviceInfo>>`（`_discoveredDevices` StateFlow，按 `DeviceKey(ip,port)` 去重的**裸发现列表**，`appList=[]`、`connectionState=DISCOVERED`）。

---

## 7. 连接状态机（冻结）

### 7.1 `ConnectionState` 枚举
`DISCOVERED` / `CONNECTING` / `CONNECTED` / `ERROR` / `DISCONNECTED` / `RECONNECTING` / `CONNECTION_TIMEOUT`。

### 7.2 超时/间隔参数（`AppConfig.DEFAULT` + `ConnectionManager` 常量）
| 参数 | 值 | 来源 | 语义 |
|---|---|---|---|
| 配对请求自动超时 | **15s** | `ConnectionManager.REQUEST_TIMEOUT_MS` | 接收方登记请求后 15s 无响应 → 置 `TIMEOUT`（对发起方表现为 rejected/"Timeout"） |
| 发起方轮询总超时 | **30s** | `ConnectionManager.CONNECT_TIMEOUT_MS`（= `AppConfig.connectTimeoutMs`） | 轮询 status 上限；超时 → `ConnectResult.Timeout("连接超时（30秒内未收到响应）")` |
| 轮询间隔 | **500ms** | `ConnectionManager.POLL_INTERVAL_MS`（= `AppConfig.pollIntervalMs`） | |
| 心跳 ping 间隔 | **20s** | `AppConfig.heartbeatPingIntervalMs` | 连接后每 20s `GET /api/ping` |
| 周期同步间隔 | **120s** | `AppConfig.heartbeatSyncIntervalMs` | 每 120s 重拉对端 appList |
| ping 单次超时 | **3s** | `AppConfig.pingTimeoutMs` / `pingDevice(timeoutMs=3000)` | |
| 心跳容忍失败次数 | **1** | `AppConfig.heartbeatPingTolerance` | failCount ≤ 1 → RECONNECTING(不稳定) |
| 心跳最大失败次数 | **4** | `AppConfig.heartbeatPingMaxFailures` | failCount ≥ 4 → CONNECTION_TIMEOUT |
| appList 拉取重试 | **5 次 / 间隔 3s** | `AppConfig.fetchAppListMaxRetries` / `fetchAppListRetryDelayMs`（`fetchAppListWithRetry` 内亦硬编码 5/3000） | |
| 更新重算节流 | **5s** | `AppConfig.updateRecalculationThrottleMs` | `combine(localApps, connectedDevices)` 触发 |
| mDNS 保活 | **60s** | `JmDNSDiscovery.REFRESH_INTERVAL_MS` | |

### 7.3 配对子状态机（`IncomingConnectRequest.RequestStatus`，接收方侧）
```
        receiveRequest(登记, 启动15s定时)
                 │
                 ▼
             PENDING ──── respondToRequest(accepted=true) ──▶ ACCEPTED
                 │                                               │
                 │──── respondToRequest(accepted=false) ─▶ REJECTED
                 │                                               │
                 └──── 15s 到期(handleTimeout) ─────────▶ TIMEOUT │
                                                                 ▼
                            removeRequest / clearAll ──▶ 从 pendingRequests 移除
```
- `incomingRequests` StateFlow **只暴露 PENDING** 项，按 `timestamp` 降序（驱动 UI 弹窗 + 倒计时）。
- ACCEPTED/REJECTED/TIMEOUT 项仍留在 `pendingRequests`（供 `getStatus` 查询）直到 `removeRequest`。

### 7.4 设备连接状态机（`ConnectionState`，发起/接收编排侧）
```
   [mDNS 发现]
       │  addOrUpdate: 新设备
       ▼
   DISCOVERED ──── connectDevice(): putIfAbsent 去重锁 ───▶ CONNECTING
       ▲                                                        │
       │                                    sendConnectRequest  │
       │                                       返回 null ───────┼──▶ ERROR("无法发送连接请求，目标设备无响应")
       │                                                        │
       │                                   pollConnectStatus(30s)│
       │                    ┌──────────────┬───────────────────┼───────────────┐
       │                    ▼              ▼                    ▼               ▼
       │               Accepted        Rejected             Timeout         异常(catch)
       │                    │              │            (未被反向连接)          │
       │                    │              ▼                    ▼               ▼
       │                    │        ERROR("对方拒绝连接")  ERROR("连接超时…")  ERROR(e.message?:"连接异常")
       │                    ▼
       │              CONNECTED ── addOrUpdateInConnected/Enriched + startHeartbeat + fetchAppListWithRetry
       │                    │
       │      ┌─────────────┼──────────────────────────┐
       │      ▼             ▼                            ▼
       │  ping 成功      ping 失败 failCount++        disconnectDevice()/远端断开
       │  (清除计数)         │                            │
       │      │    ┌─────────┼──────────┐                 ▼
       │      │    ▼         ▼          ▼            DISCONNECTED(appList 保留)
       │      │ failCount≤1  <4        ≥4                 │
       │      │    │         │          │                  │
       │      │    ▼         ▼          ▼                  │
       │   RECONNECTING  RECONNECTING  CONNECTION_TIMEOUT  │
       │   ("连接不稳定…")("正在尝试重新连接…" ("连接超时，设备已离线")│
       │      │        +tryFastReconnect)  │ removeFromConnected│
       │      │         │                  │ stopHeartbeat/Sync │
       │      └────┬────┘                  └───────────────────┘
       │           ▼
       └───── 恢复到 CONNECTED（ping 成功 / fastReconnect fetchDeviceInfo!=null）
```

**迁移条件明细（冻结）**：
| 当前态 | 事件 | 目标态 | 附带动作 |
|---|---|---|---|
| — | mDNS 新增设备 | DISCOVERED | 加入 `_enrichedDevices` |
| DISCOVERED/DISCONNECTED | `connectDevice()` | CONNECTING | `connectingDevices.putIfAbsent` 去重；已 CONNECTED 直接返回 true |
| CONNECTING | `sendConnectRequest`→null | ERROR | 文案「无法发送连接请求，目标设备无响应」 |
| CONNECTING | poll=Accepted | CONNECTED | 加入 connected+enriched；`startHeartbeat`；后台 `fetchAppListWithRetry(isInitiator=true)` |
| CONNECTING | poll=Rejected | ERROR | 文案「对方拒绝连接」 |
| CONNECTING | poll=Timeout | ERROR | 若已被反向连接（connected 中存在且 CONNECTED）→ 视为成功 true；否则 ERROR「连接超时（30秒内未收到响应）」 |
| CONNECTING | 抛异常 | ERROR | 文案 `e.message ?: "连接异常"` |
| CONNECTED | ping 成功 | CONNECTED | 清 `heartbeatFailCounts`；刷新 `lastSeenTimeMs`；`connectionError=null` |
| CONNECTED | ping 失败 failCount≤1 | RECONNECTING | 「连接不稳定... (n/4)」 |
| CONNECTED/RECONNECTING | ping 失败 1<failCount<4 | RECONNECTING | 「正在尝试重新连接... (n/4)」；`launch{ tryFastReconnect }` |
| RECONNECTING | failCount≥4 | CONNECTION_TIMEOUT | 「连接超时，设备已离线」；`removeFromConnected`；stopHeartbeat/Sync；清计数 |
| RECONNECTING | `tryFastReconnect` fetchDeviceInfo≠null | CONNECTED | 清计数；`fetchAndEnrichDevice` |
| CONNECTED | 本地 `disconnectDevice()` | DISCONNECTED | stopHeartbeat/Sync；`sendDisconnectNotification`；**appList 保留**；移出 connected |
| CONNECTED | 远端 `POST /api/disconnect` | DISCONNECTED | 按 displayKey/identityKey 匹配；stopHeartbeat/Sync；**appList 保留**；移出 connected |

### 7.5 端口迁移（同 `instanceId` 换 `ip:port`）
- 触发：`observeRawDevicesAndManageConnections` 中 `existingByIdentity >= 0 && existingByIdentity != existingByDisplayKey && oldDevice.displayKey != raw.displayKey`。
- 动作：以 `raw` 为新基，**保留** `connectionState` + `appList`，刷新 `lastSeenTimeMs`；同步迁移 `_connectedDevices`；若迁移前为 CONNECTED/RECONNECTING → 停旧 key 心跳/同步/计数 + 对新 key `startHeartbeat`。
- `handleIncomingRequest` 侧亦有 `migrateDeviceConnection`（knownDevice.displayKey ≠ 新 displayKey 时）。

### 7.6 陈旧设备清理（enriched 列表）
`_enrichedDevices` 中满足**全部**条件者被移除：`displayKey ∉ currentKeys` 且 `identityKey ∉ rawDevices.identityKeys` 且 `connectionState ∉ {CONNECTED, RECONNECTING, CONNECTION_TIMEOUT}`。
> 即：已连接类状态**不因 mDNS 消失而被清理**（交给心跳超时处理）。

### 7.7 反向连接接受策略（`handleIncomingRequest`）—— **TT3 决议：干净语义（已冻结为 v1.0 目标）**

> **背景**：旧实现 `autoAcceptKnown=true` 存在两个缺陷：① 对已知设备点「拒绝」反而**自动接受**；② 对陌生设备点「拒绝」走 `removeRequest` **不置 REJECTED**，发起方**收不到即时拒绝、只能等 30s 超时**。用户确认此为「有意设计但实现未完成」，要求按下述**干净语义**重写并修复漏洞。

**冻结的目标语义**：
1. **自动接受仅面向「历史配对成功过」的设备**：以**持久化配对历史**（新增本地存储，记录成功配对过的 `instanceId` 集合）为唯一判据；**非**旧的「当前 discovered/connected 列表内即视为已知」。
2. **陌生设备一律弹窗确认**：不在配对历史中的设备，收到请求 → 保持 `PENDING` → 驱动 `IncomingConnectionDialog`（15s 倒计时），**绝不自动放行**。
3. **修复旧漏洞**：任何「陌生设备被自动接受」的路径必须消除。
4. **显式拒绝须即时回 REJECTED**：用户点「拒绝」→ `respondToRequest(accepted=false)` 置 `REJECTED` → 发起方**立即**收到 `status="rejected"`（而非 `removeRequest` 导致 30s 超时）。
5. **显式接受**：用户点「接受」→ `respondToRequest(accepted=true)` → 建 CONNECTED + startHeartbeat + fetchAppListWithRetry(isInitiator=false) + **将该 `instanceId` 写入配对历史**。

**目标处理表**：
| 触发 | 配对历史命中 | 动作 | 发起方可见结果 |
|---|---|---|---|
| 收到请求（无用户交互） | 是 | 自动 `respondToRequest(true)` | `accepted` |
| 收到请求（无用户交互） | 否 | 保持 PENDING，弹窗 | `pending`（直至用户操作或 15s→rejected/"Timeout"） |
| 用户点接受 | — | `respondToRequest(true)` + 写入配对历史 | `accepted` |
| 用户点拒绝 | — | `respondToRequest(false)` | **`rejected`（即时）** |
| 15s 无响应 | — | `handleTimeout` → TIMEOUT | `rejected` + `message="Timeout"`（§2.6） |

> **契约边界说明**：以上为**内部接受策略**变更，**线上状态词表（pending/accepted/rejected）与路由/payload 形状不变**（§2.6/§3），故**不破坏字节级互操作**；唯一可观测差异是「陌生设备被拒时发起方更快收到 rejected」——仍在既有词表内，旧客户端 `tryParseConnectStatus` 正常处理。配对历史为**本机私有持久化**，不上线。
> **落地依赖**：需在 ARCHITECTURE.md 的 `ConnectionCoordinator` 增加「配对历史存储」协作者；TEST-PLAN CS-17 断言改为验证本表（含「陌生设备拒绝→即时 rejected」「陌生设备不自动接受」两条修复用例）。

---

## 8. 哈希 / MD5 语义（**关键决策**）

### 8.1 现状：隐式双轨
| 名称 | 计算方式 | 用途 | 上线位置 |
|---|---|---|---|
| `AppInfo.md5` | `HashUtils.md5(sourcePaths)` = 对**原始各 APK 文件字节顺序拼接**做 MD5（`AppScanner` 写入） | 版本内容指纹；下载兜底校验 `expectedMd5` | `GET /api/applist` 的 `md5` 字段 |
| `X-MD5` | `HashUtils.md5(packedFile)` = 对**打包产物文件**（单包=字节副本；split=zip）做 MD5（`KtorServer.sendZipFile` 实时算） | 传输完整性校验（**客户端优先**） | 下载响应头 |

- **单 APK**：产物是 `sourcePaths[0]` 的字节副本 → `X-MD5 == AppInfo.md5`（当 sourcePaths 仅 1 项）。
- **Split APK**：产物是 zip（含 zip 头/条目元数据/压缩）→ `X-MD5 ≠ AppInfo.md5`（**必然不等**）。
- 客户端 `verifyMd5 = serverMd5.ifEmpty { expectedMd5 }`：**优先 X-MD5**，缺失才用 expectedMd5。
- **`AppPacker.createApksFile` 内部另算了一个 MD5 digest，但为死代码——从未返回/使用**。真实 `X-MD5` 只来自 `sendZipFile` 对产物文件的独立计算。

### 8.2 已知幽灵指纹缺陷
`HashUtils.md5(paths)` 对**全部不可读**文件返回**空摘要 MD5** `d41d8cd98f00b204e9800998ecf8427e`（非 null），因 `digestFile` 静默跳过不可读文件。此缺陷被 `HashUtilsTest.md5 paths with unreadable files handles gracefully`（断言返回 32 字符）**固化为预期**。

### 8.3 冻结决策（本次重构采纳）
> **决策 D1：MD5 语义统一为「以实际传输产物哈希为准」。**
- `X-MD5` = **打包产物文件的 MD5**（`HashUtils.md5(packedFile)`），是传输完整性校验的**唯一权威**。
- **废弃 `expectedMd5` 双轨兜底**：客户端不得再用 `AppInfo.md5`（原始拼接摘要）作为下载校验依据。
- `AppInfo.md5` 的语义**降级**为「仅用于版本内容指纹/去重比较」，**不参与传输校验**。
- **兼容约束**：本次冻结**保持 `X-MD5` 响应头名与 MD5 算法不变**（改 SHA-256 / 改名 `X-Transfer-Hash` 属 Phase 6，须经协议版本协商，见 §9）。
- **重写验收**：① 单包与 split 下载均以 `X-MD5` 校验通过；② 缺失 `X-MD5` 时应报错而非静默用 expectedMd5（此为**行为改进**，因旧客户端优先信 X-MD5、服务端恒发 X-MD5，故不破坏互操作）；③ 幽灵指纹缺陷修正（null 传播）属 Phase 1，须同步修正被固化的测试预期。

### 8.4 AppScanner 对 null 指纹的行为规范（**决策 D2 / 裁决②**，2026-09-08）

> **决策 D2（用户裁决②：接受现状）**：`HashUtils.md5(paths)` 改为 null 传播后（§8.2/§8.3，Phase 1 已落地），`AppScanner` 对 null 指纹的处理**保持现状**——即 `md5 = HashUtils.md5(sourcePaths) ?: ""` 且 `isExtractable` **仍为 `true`**，**不**降级为 `isExtractable=false`。

- **合法态定义**：`AppInfo(md5 = "", isExtractable = true)` 为**合法**状态，表示「该包可提取传输，但内容指纹因扫描期竞态未能计算」。此态**不阻断**下载/打包（传输完整性一律以 `X-MD5` 产物哈希为准，§8.3 D1，与列表 `md5` 无关）。
- **触发条件（罕见）**：`AppScanner` 在加入 `sourcePaths` 前已对每个路径做 `canRead()` 预筛（`AppScanner.kt` L56/L63），故 `HashUtils.md5` 仅在「预筛通过但读取时文件变不可读」的竞态下返回 null；正常应用几乎不触发。
- **理由**：① `md5` 已降级为「仅版本内容指纹/去重比较」，不参与传输校验（D1），故空指纹不影响下载正确性；② 降级为 `isExtractable=false` 反而会**误伤**（把可正常传输的应用标记为不可提取）；③ 保持现状零风险、零行为变更。
- **对去重/比较的影响**：`UpdateManager` 仅用 `versionCode` 比较（§3.2 规则），**不依赖 `md5`**；故空 `md5` 不影响更新推荐。
- **重开条件**：若真机测试发现「`md5=""` 的正常应用在去重/版本比较中被误判」，再重开此决策（届时改为 null 指纹降级或补算）。

---

## 9. 兼容性与冻结声明（v1.0）

- **本 SPEC 冻结的是「当前源码树所实现的单一版本协议」**（versionName 1.0 / versionCode 1，无协议版本协商字段）。
- **T1 决议（旧 APK 互操作）**：用户确认**已分发旧版 APK 存在**并将装入真机，按 TEST-PLAN §6.1 互操作矩阵（新×新 / 新×旧 / 旧×新 / 旧×旧）执行验收。**已知新旧差异点**：① `/api/deviceinfo` 旧构建含 `"version":"1.0"`、新构建省略（§2.9，客户端不读，容忍）；② 其余路由/头/命名/状态机词表一致。验收须逐格确认这些差异不破坏 golden path。
- **无 proto 协商**：当前 TXT 无 `proto` 键、`/api/deviceinfo` 无有效 `protocolVersion`（`version` 为死字段，禁止复用，§2.9）。任何新增字段/头/路由（token 鉴权、`X-Transfer-Hash`、剥离 sourcePaths）**必须**走 Phase 6 的版本协商，且**旧客户端在兼容期内必须能无感继续工作**（靠 `ignoreUnknownKeys` + 恒发 X-MD5 + 不解析错误体）。
- **重写不变量（互操作红线）**：
  1. 10 条路由的方法/路径/成功响应体形状/错误状态码不变（§3）。
  2. `encodeDefaults=false` 的省略行为不变（§1.2.1）。
  3. 下载三响应头 `X-MD5`/`X-File-Size`/`Content-Disposition` 与 Content-Type 规则不变（§4/§5.1）。
  4. 文件命名 `{pkg下划线}_{versionCode}.apk/.apks` 与 `.apks` zip 结构不变（§5.3）。
  5. mDNS 服务类型 `_lansync._tcp.local.`、实例名 `LanSync_{host}_{instanceId}`、TXT 双键（应用层键值契约）、60s 保活不变（§6）。
  6. 超时参数 15s/30s/500ms/20s/120s/3s 与心跳三档阈值 1/4 不变（§7.2）。
- **有意行为变更（非红线，须显式记录）**：
  - **TT3**：反向连接接受策略改为「仅历史配对成功设备自动接受、陌生设备弹窗、显式拒绝即时回 rejected」（§7.7）。**线上词表不变**，仅内部策略与拒绝时延改善。
  - **D1**：MD5 校验以 `X-MD5`（传输产物）为唯一权威，废弃 `expectedMd5` 兜底（§8.3）。**头名与算法不变**。

---

## 10. TODO 决议记录（全部已冻结）

> 原 6 项 `[TODO:需从旧代码确认]` + 测试计划 TT1/TT3 已由用户逐条答复，并经源码/git 查证落实。以下为**决议存档**，对应细节已落回各章节。
>
> **可验证性分级（2026-09-08 复核）**：
> - **源码/git 可验证（已独立复核通过）**：T2（`git log -S DeviceInfoResponse` + `git show ad89d61` 证实 `version` 恒为默认常量、ad89d61 处由手写 JSON 改 DTO）、T3（全仓仅 `getPropertyString`，无手写 TXT 解析）、T5（`AppListClient.performDownload` 不校验 Content-Type）、T6（`NetworkUtils` 两条 IP 取值路径）。
> - **⚠️ 依赖用户事实/设计决策（源码无法证实，按用户指示显式标注）**：
>   - **T1/TT1**：「已分发旧版 APK 实物存在并将装入真机」属**用户事实**，代码无从验证。**[✅ 用户已确认 2026-09-08：旧版 APK 实物存在，将在后续功能测试阶段做实机互操作验证（§9 矩阵新×旧/旧×旧 可执行）]**。
>   - **TT3**：`handleIncomingRequest(autoAcceptKnown=true)` 的**两处缺陷经复核确为真实代码行为**（拒绝已知设备反被自动接受；拒绝陌生设备走 `removeRequest` 致发起方 30s 超时，见 §7.7 与 `AppRepository.kt` L486–L554）；而「此为*有意设计但实现未完成*」的定性与「改用持久化配对历史干净语义」的决策属用户判断。**[✅ 用户已确认 2026-09-08：TT3 状态仍为「有意设计但实现未完成」，干净语义为采纳目标；落地属 Phase 4 `ConnectionCoordinator`，本阶段（传输骨架）不实现]**。

| # | 原待确认项 | 用户决议 | 查证/落地 | 冻结位置 |
|---|---|---|---|---|
| T1 / TT1 | 是否存在旧版 APK 及其协议差异 | 旧 APK 存在，用户装入真机，按互操作矩阵验收 | git 查证发现 `ad89d61` 前后 `/api/deviceinfo` 线上形态差异（version 字段） | §9 / §2.9 / TEST-PLAN §6.1 |
| T2 | `version` 是否曾赋真实值 | 用户不掌握历史，令以 `git log -p` 查证为准；不可确认则视为不可信、新代码不得依赖 | pickaxe 全历史证明**从未赋真实值**，恒为常量 "1.0" | §2.9（死字段，禁止复用于兼容判断） |
| T3 | TXT 字节级编码 | **不做字节级冻结**，应用层键值一致即可；若有手写 TXT 解析则升级 | 全仓查证：**无手写 TXT 解析**（仅 `getPropertyString`），**无需升级** | §6.2 |
| T4 | `Build.MODEL` 含下划线的解析表现 | **按 UTF-8 标准实现**，旧代码运行无明显问题 | 沿用库默认 UTF-8，不增强 | §6.2 |
| T5 | 旧接收端 Content-Type 期望 | 读旧代码 Ktor 配置确认，新版头部以旧版实际兼容写法为准 | 查证：客户端**从不校验** Content-Type；服务端 `.apk`→octet-stream/`.apks`→zip | §4 |
| T6 | 多网络 IP 分歧 | **仅承诺同一 Wi-Fi 两台设备**；多网卡/热点/VPN 不支持，对齐旧版不增强 | 保留两条 IP 取值路径原样，不引入网络选择增强 | §6.4 |
| TT3 | `autoAcceptKnown` 是 bug 还是设计 | **有意设计但实现未完成**；改为干净语义：仅「历史配对成功过」自动接受，陌生设备弹窗，修复陌生设备被自动放行漏洞 | 重新定义接受策略 + 新增持久化配对历史 + 即时 rejected | §7.7 |

**冻结声明**：以上决议构成 SPEC **v1.0** 的一部分。协议字段层面**无遗留 `[TODO]`**（均可源码确认）；仅 T1/TT1、TT3 依赖用户事实/决策，已在上表显式标注待确认。后续若 Phase 6 引入协议版本协商，须以 §2.9 的独立新字段承载，不得复用 `version`。

---

## 11. Phase 1 实现保真核验（2026-09-08 复核）

> 应用户要求，对「已完成的 Phase 1 重写代码」是否忠实复现本冻结契约做逐条核验。核验对象为工作树中**尚未提交**的新实现：`data/server/{ServerContracts,KtorLanSyncServer,LanSyncRouting,InMemoryPairingStore}.kt`、`data/transfer/{LanSyncClient,DownloadedFileName}.kt`、`data/model/LanSyncError.kt`、`data/HashUtils.kt`（改动）。

### 11.1 契约复现结论：**忠实**（含 2 处已声明的有意分歧）

| 契约条目 | 新实现位置 | 结论 |
|---|---|---|
| §1.2 JSON 配置（prettyPrint/isLenient/ignoreUnknownKeys，encodeDefaults=false） | `LanSyncRouting.LanSyncJson` | ✅ 一致 |
| §3 十路由方法/路径/成功体形状/错误码矩阵 | `lanSyncModule` | ✅ 一致 |
| §3.1 #7 vs #8 错误码优先级差异 | `lanSyncModule` 两 download 块 | ✅ 各自保持（#7 先 403 后 404；#8 先 404 后 403） |
| §4 Content-Type（.apk octet-stream / .apks zip） | `sendPackedFile` `isSingleApk` | ✅ 一致 |
| §5.1 X-MD5/X-File-Size/Content-Disposition | `sendPackedFile` | ✅ 一致，X-MD5=产物实时哈希（D1） |
| §5.3/§5.4 命名与包名解析 | `DownloadedFileName` | ✅ 一致（单一实现，收敛 P7） |
| §5.5/§5.6 客户端下载配置与校验流程 | `LanSyncClient` | ✅ 一致，且实现 D1（见 §11.2/§11.3） |
| §7.3 接收方配对状态机 + 15s 超时 | `InMemoryPairingStore` | ✅ 一致（`getStatus` 映射逐字复现，含 TIMEOUT→`accepted=false,message="Timeout",responderName=null`） |
| §8.3 决策 D1（X-MD5 唯一权威、废弃 expectedMd5） | `LanSyncClient.performDownload` | ✅ 已实现：缺 X-MD5→删文件+`Error("Missing X-MD5 header")`，移除 `expectedMd5` 兜底分支 |
| §8.2 幽灵指纹 null 传播 | `HashUtils.md5(paths)` | ✅ 已实现（空列表/任一不可读→null，消灭 `d41d8…`） |

### 11.2 有意分歧（均在 SPEC/ARCHITECTURE 授权范围内，不破坏 §9 互操作红线）

1. **错误响应体 `text/plain` → `application/json`（`LanSyncErrorDto`）**：状态码逐一保持；客户端从不解析错误体（§3.2），故不破坏互操作。`LanSyncRoutingTest` 断言错误体不再含 `e.message`。
2. **503「Server not ready」分支被移除**：旧 `KtorServer` 因 `connectionManager` 惰性创建存在 503 竞态；新实现经构造注入 `PairingStore` 恒非空，该分支不复存在（§3.1 #4 的 503 仅为旧服务端产物；新客户端对旧服务端 503 仍按失败容忍）。

### 11.3 尚未落地（Phase 1 未完成项，非契约破坏）

- **新实现未接线**：`AppRepository` 仍使用旧 `KtorServer`/`AppListClient`/`ConnectionManager`；新 `KtorLanSyncServer`/`LanSyncClient`/`InMemoryPairingStore`/`lanSyncModule` 为**并行构件**，尚未替换线上路径。故当前 App 实际仍走**旧双轨 MD5**（`downloadApp` 传 `expectedMd5`，见 `AppRepository.kt` L1044）；D1 语义仅存在于未接线的新客户端。
- **§7.7（TT3）干净语义未实现**：旧 `handleIncomingRequest` 两处缺陷仍在（线上行为）；目标「持久化配对历史 + 陌生设备弹窗 + 即时 rejected」属 Phase 4 `ConnectionCoordinator`，尚未落地。
- 落地现状详表见 **ARCHITECTURE.md §13**、测试现状见 **TEST-PLAN.md §10**。

---

*（SPEC.md 结束。ARCHITECTURE.md 见目标架构，TEST-PLAN.md 见测试计划。）*
