---
kind: error_handling
name: LanSync Android 应用错误处理体系：基于 sealed result、状态枚举与集中式日志的错误传播
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/lansync/app/data/client/AppListClient.kt
    - app/src/main/java/com/lansync/app/data/installer/ApkInstaller.kt
    - app/src/main/java/com/lansync/app/data/model/Models.kt
    - app/src/main/java/com/lansync/app/ui/viewmodel/MainViewModel.kt
    - app/src/main/java/com/lansync/app/data/repository/AppRepository.kt
    - app/src/main/java/com/lansync/app/data/FileLogger.kt
    - app/src/main/java/com/lansync/app/data/HashUtils.kt
    - app/src/main/java/com/lansync/app/data/NetworkUtils.kt
    - app/src/main/java/com/lansync/app/data/cache/AppIconDiskCache.kt
---

## 1. 整体方法
本仓库是一个基于 Kotlin/Android 的 P2P 局域网同步应用，没有引入统一的异常类型框架或全局错误中间件。错误处理采用“分层 + 结果对象 + 集中式文件日志”的组合方式：
- 底层 I/O（网络、文件、哈希）通过 `try/catch` 捕获并返回 `null` / 布尔值等安全返回值。
- 业务层（下载、安装、连接）使用 **sealed class** 表达多种失败原因（如 `DownloadResult.Error`、`InstallationResult.Error`、`ConnectResult.Timeout/Rejected/Accepted`）。
- 所有可观测错误统一通过 `FileLogger` 写入 `lansync_debug.log`，供用户导出排查。
- UI 层通过 `UiState.connectionError`、`installStatus`、`operationMessage` 等字段向 Compose 界面呈现错误信息。

## 2. 关键文件与位置
- `app/src/main/java/com/lansync/app/data/client/AppListClient.kt`：定义 `ConnectResult`（Accepted/Rejected/Timeout）和 `DownloadResult`（Success/Error），是网络侧错误的主要载体。
- `app/src/main/java/com/lansync/app/data/installer/ApkInstaller.kt`：定义 `InstallationResult`（Success / Error(message)），封装 APK 安装过程中的系统级失败。
- `app/src/main/java/com/lansync/app/data/model/Models.kt`：定义 `ConnectionState` 枚举（DISCOVERED/CONNECTING/CONNECTED/ERROR/DISCONNECTED/RECONNECTING/CONNECTION_TIMEOUT），以及 `DeviceInfo.connectionError: String?`，用于在设备维度承载错误消息。
- `app/src/main/java/com/lansync/app/ui/viewmodel/MainViewModel.kt`：将底层错误转换为 `UiState.connectionError`、`operationMessage` 等 UI 状态；批量更新时收集失败条目并以汇总消息展示。
- `app/src/main/java/com/lansync/app/data/repository/AppRepository.kt`：核心编排层，负责心跳重试、连接超时判定、端口迁移、远程刷新等流程中的错误分类与状态推进。
- `app/src/main/java/com/lansync/app/data/FileLogger.kt`：单例日志器，提供 `d/i/w/e/json` 接口，异步写入带时间戳的日志文件，支持轮转与备份。
- `app/src/main/java/com/lansync/app/data/HashUtils.kt`、`NetworkUtils.kt`、`cache/AppIconDiskCache.kt`：工具类统一用 `try/catch` 吞掉异常并返回空值或默认值，保证上层调用稳定。

## 3. 架构与约定
### 3.1 结果对象优先于抛出异常
- `AppListClient.performDownload` 返回 `DownloadResult.Success(file)` 或 `DownloadResult.Error(message)`，其中错误分支会记录 HTTP 状态码、响应体前 200 字符、MD5 校验失败等信息到 `FileLogger.e`。
- `ApkInstaller.installApks` 返回 `InstallationResult.Success` 或 `InstallationResult.Error(message)`，覆盖文件不存在、无包管理器、启动 Intent 抛异常等场景。
- `AppListClient.pollConnectStatus` 内部循环 poll，最终返回 `ConnectResult.Timeout`、`Rejected` 或 `Accepted`，而不是抛出异常；UI 层根据该结果设置 `connectionError`。

### 3.2 连接状态机驱动错误语义
`ConnectionState` 枚举明确区分了“连接中”“已连接”“拒绝”“重连中”“超时”“断开”等状态。`AppRepository.runHeartbeatPing` 根据连续失败次数逐步推进：
- 失败次数 ≤ `heartbeatPingTolerance` → 标记为 `RECONNECTING`，附带“连接不稳定…”提示。
- 失败次数 < `heartbeatPingMaxFailures` → 继续尝试快速重连。
- 超过阈值 → 置为 `CONNECTION_TIMEOUT`，移除心跳/同步任务，并从已连接列表剔除。
这些状态同时写入 `DeviceInfo.connectionState` 与 `connectionError`，由 ViewModel 暴露给 UI。

### 3.3 重试与降级
- `fetchAppListWithRetry` 最多重试 5 次，每次间隔 3s，记录每次尝试的日志，最终失败时保留设备连接但 appList 为空。
- `pollConnectStatus` 自带超时控制（默认 `ConnectionManager.CONNECT_TIMEOUT_MS`），超时后返回 `Timeout`。
- 工具类（`HashUtils`、`NetworkUtils`、`AppIconDiskCache`）对不可恢复的 I/O 异常直接返回 `null`/空字符串，避免污染上层调用链。

### 3.4 集中式日志作为“错误输出通道”
`FileLogger` 是唯一的结构化日志出口：
- 所有网络异常、HTTP 非成功、MD5 不匹配、安装失败、缓存读写失败都通过 `FileLogger.e/w/i/d` 记录。
- 日志文件位于外部存储 `context.getExternalFilesDir(null)/lansync_debug.log`，最大 5MB，自动轮转为 `.bak1..bak3` 及 `.prev.log`。
- 写入在独立协程作用域 + `Mutex` 保护下进行，消费端 catch 全部异常以保证日志通道本身不会崩溃。
- 提供 `getLogFilePath()` 以便 UI 分享日志。

### 3.5 UI 层错误呈现
`MainViewModel.UiState` 包含：
- `connectionError: String?`：连接失败时的用户可见错误。
- `operationMessage: String?`：批量操作汇总错误（如“批量更新完成，其中 X/Y 个失败”）。
- `installStatus: AppRepository.InstallStatus?`：安装进度/结果。
- `isDownloading`、`currentDownloadProgress`：下载阶段的状态。
UI 组件读取这些字段显示 Toast/Dialog/文本，而不是依赖异常堆栈。

## 4. 约定与约束
- **禁止向上抛出未包装异常**：工具类（HashUtils、NetworkUtils、DiskCache）一律 catch 并返回空值；业务方法返回 sealed result 或 null/boolean。
- **网络错误必须记录 FileLogger**：`AppListClient` 中每个 OkHttp 调用都在 try/catch 中调用 `FileLogger.e/w`，并返回安全值。
- **连接生命周期错误走状态机**：心跳失败通过 `heartbeatFailCounts` 计数，按阈值切换 `RECONNECTING`/`CONNECTION_TIMEOUT`，而非简单抛错。
- **用户可见错误以字符串形式传递**：`DeviceInfo.connectionError`、`DownloadResult.Error.message`、`InstallationResult.Error.message` 都是人类可读的中文/英文混合字符串，由 UI 直接展示。
- **日志文件是唯一持久化错误上下文**：没有集中错误上报服务，调试依赖 `lansync_debug.log` 及其轮转备份。
- **批量操作聚合错误**：`startBatchUpdate`、`pullSelectedRemoteApps` 收集每条子任务的失败消息，最终以一条汇总 `operationMessage` 反馈给用户。

总体而言，该仓库的错误处理风格偏实用主义：没有自定义异常层次，而是以 sealed result、状态枚举和集中式文件日志构成“可观察、可重试、可回退”的错误传播路径。