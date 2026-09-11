---
kind: logging_system
name: 基于 FileLogger 的本地文件日志系统
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/lansync/app/data/FileLogger.kt
    - app/src/main/java/com/lansync/app/LanSyncApplication.kt
    - app/src/main/java/com/lansync/app/data/client/AppListClient.kt
    - app/src/main/java/com/lansync/app/data/NetworkUtils.kt
    - app/src/main/java/com/lansync/app/data/HashUtils.kt
---

## 1. 使用的系统/方案

本项目没有引入第三方日志框架（如 Timber、Logback、SLF4J），而是实现了一个自研的轻量级文件日志组件 `FileLogger`（位于 `com.lansync.app.data` 包）。该组件通过 Android `Application.onCreate()` 初始化，将日志异步写入应用外部存储目录下的 `lansync_debug.log` 文件。

## 2. 核心文件与入口

- **日志实现**：`app/src/main/java/com/lansync/app/data/FileLogger.kt` — 单例对象，提供 `d/i/w/e/json` 五个级别接口。
- **应用初始化**：`app/src/main/java/com/lansync/app/LanSyncApplication.kt` — 在 `onCreate()` 中调用 `FileLogger.init(this)` 完成全局初始化。
- **主要使用者**：`data/client/AppListClient.kt`、`data/NetworkUtils.kt`、`data/HashUtils.kt` 等网络与工具模块，统一通过 `FileLogger.i/d/w/e(...)` 输出日志。

## 3. 架构与工作机制

- **异步写入**：使用 `kotlinx.coroutines` 的 `Channel<String>(UNLIMITED)` + `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 作为生产者-消费者队列；所有日志先入队，后台协程消费并落盘。
- **并发安全**：写文件时使用 `Mutex` 互斥，保证同一时刻只有一个线程写入磁盘。
- **日志轮转**：
  - 启动时执行 `rotateLogFiles()`：若当前日志存在且非空，将其重命名为 `lansync_debug.log.bak1`，并按 `MAX_BACKUP_COUNT=3` 依次后移 `.bak2`、`.bak3`，超出则删除最旧的备份。
  - 运行时按大小轮转：当 `lansync_debug.log` 超过 `MAX_LOG_SIZE_BYTES = 5MB` 时，将其重命名为 `lansync_debug.prev.log` 并新建空文件。
- **生命周期管理**：`shutdown()` 方法会清空 Channel 中剩余条目并关闭通道，用于应用退出前的收尾。
- **路径与清理**：日志目录取自 `context.getExternalFilesDir(null)`；提供 `getLogFilePath()` 和 `clearLog()` 辅助获取/清空日志。

## 4. 日志格式与约定

- **时间戳**：`yyyy-MM-dd HH:mm:ss.SSS`（`Locale.US`）。
- **行格式**：`[timestamp] [LEVEL] tag: message`，例如 `2024-01-01 12:00:00.000 [INFO ] AppListClient: === POLL START === ...`
- **级别**：`DEBUG`、`INFO`、`WARN`、`ERROR`，以及专用的 `JSON` 级别（用于结构化数据片段）。
- **异常处理**：`w/e` 方法会将 `Throwable` 的 `message` 和前 8 行堆栈追加到消息末尾，便于排查。
- **JSON 字段截断**：`json(tag, label, data)` 对字符串或任意对象的 toString 结果限制为前 500 字符，超长时追加 `...[truncated]`。
- **Tag 约定**：各模块定义 `private val TAG = ClassName::class.simpleName`，并通过常量传入 `FileLogger`，便于按模块过滤。

## 5. 约束与规则

- **必须通过 `FileLogger.init(context)` 初始化**：未初始化时所有 `enqueue` 直接返回，不会写入任何内容。初始化由 `LanSyncApplication.onCreate()` 统一完成。
- **日志仅写入外部存储**：目标目录为 `Context.getExternalFilesDir(null)`，不依赖 SD 卡权限以外的额外权限。
- **无动态级别开关**：代码中不存在根据 BuildType/Debuggable 切换日志级别的逻辑，所有级别均被记录。
- **无结构化 JSON 输出**：虽然提供了 `json()` 方法，但实际输出仍是带 `[JSON]` 标记的纯文本行，并非真正的 JSON 格式。
- **无远程上报/聚合**：日志仅落盘，未集成任何远端收集服务（如 Firebase Crashlytics、Sentry）。
- **无 Logcat 输出**：未调用 `android.util.Log`，因此无法通过 adb logcat 查看这些日志，只能通过拉取设备文件获取。

## 6. 使用模式总结

项目采用“自研单例 + 协程 Channel 异步落盘”的极简日志方案，适合 Android 应用在无第三方依赖的情况下持久化调试信息。所有业务模块统一通过 `FileLogger` 的静态 API 输出，避免了在每个类中维护独立的 Logger 实例，同时借助 Mutex 保证多协程并发写入的安全性。