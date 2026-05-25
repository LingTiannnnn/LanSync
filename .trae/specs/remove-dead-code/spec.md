# 清理死代码规范

## Why
代码库中存在已定义但从未调用的函数/方法/类，以及声明但未实现或不存在调用方的"死代码"。这些代码增加了维护负担、增加编译产物体积并降低代码可读性。

## What Changes

### A) 已定义但从未调用的代码（删除）
- 删除 `Result<T>` 密封类（`app/src/main/java/.../data/Result.kt`）——整个文件无任何生产代码引用，仅有自身的单元测试使用
- 删除 `UpdateManager.groupByDevice()` 方法——仅在测试中调用，生产代码中从未使用
- 删除 `AppRepository.enrichedDevices` 属性——与 `discoveredDevices` 完全重复
- 删除 `AppRepository.fetchDeviceAppList()` 方法——无任何调用方
- 删除 `AppRepository.installDownloadedFile(File)` 重载——无调用方，仅 `(fileName, pkgName)` 版本被使用
- 删除 `AppRepository` companion object 中的 4 个已废弃常量：`HEARTBEAT_PING_INTERVAL_MS`、`HEARTBEAT_SYNC_INTERVAL_MS`、`HEARTBEAT_PING_TOLERANCE`、`HEARTBEAT_PING_MAX_FAILURES`
- 删除 `MainViewModel.performInitialScan()` 方法——从未被 MainActivity 调用
- 删除 `MainViewModel.scanLocalApps()` 方法——从未被 MainActivity 调用
- 删除 `MainViewModel.checkForUpdates()` 方法——从未被 MainActivity 调用
- 删除 `MainViewModel.lastDownloadedUpdateInfo` 公开属性——从未被 UI 层读取
- 删除 `AppIconCache` 对象（`AppIcon.kt` 第 152-158 行）——与内存缓存 LruCache 重复，从未被使用
- 删除 `clearAppIconCache()` 函数（`AppIcon.kt` 第 148-150 行）——从未被调用

### B) 死代码——未实现/无用定义（删除）
- 删除 KtorServer 中 `/api/app/{packageName}/{versionCode}` 路由——AppListClient 从未调用此端点，而是使用 `/api/download/...`
- 删除 `AppListClient.ConnectResult.Error` 密封类变体——定义但从未在代码中构造实例
- 删除 `AppConfig` 中 `requestTimeoutMs`、`downloadBufferSize`、`sendBufferSize` 三个配置字段——它们虽被定义但从未被任何代码读取
- 删除 KtorServer.kt 中未使用的 `ZipEntry` 和 `ZipOutputStream` 导入

### C) 测试文件修正
- 重命名 `AppPackerTest` → `HashUtilsConsistencyTest`（该测试实际只测试 HashUtils，与 AppPacker 无关）
- 删除 `ResultTest.kt`（随 `Result<T>` 类的删除而移除）
- 删除 `UpdateManagerTest` 中测试 `groupByDevice` 的测试用例

## Impact
- Affected specs: 无
- Affected code:
  - `app/src/main/java/.../data/Result.kt`（删除）
  - `app/src/main/java/.../data/AppConfig.kt`
  - `app/src/main/java/.../data/repository/AppRepository.kt`
  - `app/src/main/java/.../data/update/UpdateManager.kt`
  - `app/src/main/java/.../data/server/KtorServer.kt`
  - `app/src/main/java/.../data/client/AppListClient.kt`
  - `app/src/main/java/.../ui/viewmodel/MainViewModel.kt`
  - `app/src/main/java/.../ui/components/AppIcon.kt`
  - `app/src/test/java/.../data/ResultTest.kt`（删除）
  - `app/src/test/java/.../data/packer/AppPackerTest.kt`（重命名及内容调整）
  - `app/src/test/java/.../data/update/UpdateManagerTest.kt`

## ADDED Requirements

### Requirement: 清理未使用的 Result 工具类
系统 SHALL 删除 `Result<T>` 密封类及其配套测试文件 `ResultTest.kt`。

#### Scenario: 删除未使用的 Result 类
- **WHEN** 代码库扫描完成
- **THEN** `Result.kt` 和 `ResultTest.kt` 文件不存在于项目中

### Requirement: 清理 UpdateManager 中的死方法
系统 SHALL 删除 `groupByDevice()` 方法并删除相关测试用例。

#### Scenario: 删除 groupByDevice
- **WHEN** 搜索 `groupByDevice` 的调用方
- **THEN** 生产代码中无调用方，测试中仅 UpdateManagerTest 存在一个调用，二者一并删除

### Requirement: 清理 AppRepository 死代码
系统 SHALL 删除 `enrichedDevices` 属性、`fetchDeviceAppList()` 方法、`installDownloadedFile(File)` 重载以及 4 个已废弃的 companion object 常量。

#### Scenario: 检查 enrichedDevices 的用途
- **WHEN** 分析 `enrichedDevices` 的引用
- **THEN** 它与 `discoveredDevices` 指向同一个 StateFlow，但 UI 层仅使用 `discoveredDevices`，因此 `enrichedDevices` 是多余的，应删除

### Requirement: 清理 MainViewModel 死方法
系统 SHALL 删除 `performInitialScan()`、`scanLocalApps()`、`checkForUpdates()` 三个未被 MainActivity UI 调用的公开方法，以及未被读取的 `lastDownloadedUpdateInfo` 属性。

### Requirement: 清理 AppIcon.kt 死代码
系统 SHALL 删除 `AppIconCache` 对象和 `clearAppIconCache()` 顶层函数。

### Requirement: 清理未实现/无用定义
系统 SHALL 删除：
- KtorServer 中 `/api/app/{packageName}/{versionCode}` 路由
- `AppListClient.ConnectResult.Error` 变体
- `AppConfig` 中 `requestTimeoutMs`、`downloadBufferSize`、`sendBufferSize`

#### Scenario: KtorServer 死路由删除
- **WHEN** 扫描 AppListClient 所有 HTTP 调用
- **THEN** 客户端使用 `/api/download/{pkg}` 和 `/api/download/{pkg}/{vc}`，从不呼叫 `/api/app/{pkg}/{vc}`，因此该路由为死路由

### Requirement: 修正测试文件
系统 SHALL 将 `AppPackerTest` 重命名为 `HashUtilsConsistencyTest`，保持内容不变（仅类名和文件名变更），以准确反映其测试范围。

## REMOVED Requirements

### Requirement: Result 工具类
**Reason**: 项目中从未在任何生产代码中使用 `Result<T>`，所有错误处理均使用各自模块专属的 sealed class（如 `ApkInstaller.InstallationResult`、`AppListClient.DownloadResult` 等）。
**Migration**: 无需迁移，无任何生产代码依赖此类。

### Requirement: groupByDevice 分组方法
**Reason**: 从未在生产代码中调用。
**Migration**: 无需迁移。

### Requirement: 已废弃常量
**Reason**: 已在 companion object 中标记 `@Deprecated`，且有 `ReplaceWith` 指向 `AppConfig`，但从未被任何代码引用。
**Migration**: 无需迁移，所有使用方已改用 AppConfig。

### Requirement: `/api/app/{pkg}/{vc}` 路由
**Reason**: 客户端（AppListClient）使用 `/api/download/{pkg}/{vc}` 下载应用，此路由无调用方。
**Migration**: 无需迁移。
