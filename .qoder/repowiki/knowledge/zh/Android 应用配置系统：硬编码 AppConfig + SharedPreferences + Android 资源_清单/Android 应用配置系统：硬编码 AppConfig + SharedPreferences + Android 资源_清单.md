---
kind: configuration_system
name: Android 应用配置系统：硬编码 AppConfig + SharedPreferences + Android 资源/清单
category: configuration_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/lansync/app/data/AppConfig.kt
    - app/src/main/java/com/lansync/app/data/discovery/JmDNSDiscovery.kt
    - app/build.gradle.kts
    - gradle.properties
    - app/src/main/AndroidManifest.xml
    - app/src/main/res/xml/network_security_config.xml
    - app/src/main/res/xml/file_paths.xml
    - app/src/main/res/values/strings.xml
    - app/src/main/java/com/lansync/app/LanSyncApplication.kt
---

## 1. 使用的系统与方式

该仓库没有引入外部配置框架（如 Hilt Config、Kotlinx Config、Spring Boot 等），而是采用 Android 原生方式组合实现配置管理：
- **运行时参数**通过 Kotlin data class `AppConfig` 集中声明，所有字段带默认值。
- **持久化用户/设备级数据**通过 Android `SharedPreferences` 存储（设备实例 ID）。
- **构建期与平台级配置**通过 Gradle (`build.gradle.kts`、`gradle.properties`)、`AndroidManifest.xml` 及 `res/values`、`res/xml` 资源文件声明。
- 无 `.env`、`.yaml`、`.toml` 或自定义配置文件；无环境变量读取逻辑。

## 2. 关键文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/lansync/app/data/AppConfig.kt` | 运行时可调参数的唯一集中定义，含 `DEFAULT` 单例 |
| `app/src/main/java/com/lansync/app/data/discovery/JmDNSDiscovery.kt` | 使用 `SharedPreferences("lansync_device")` 持久化 `device_instance_id` |
| `app/build.gradle.kts` | 应用 ID、SDK 版本、签名、ProGuard、Compose/Ktor 依赖等构建配置 |
| `gradle.properties` | Gradle JVM 参数、AndroidX、Kotlin 代码风格、配置缓存开关 |
| `app/src/main/AndroidManifest.xml` | 权限、`usesCleartextTraffic`、`networkSecurityConfig`、`FileProvider` 等清单配置 |
| `app/src/main/res/xml/network_security_config.xml` | 明文 HTTP 允许策略（开发用途） |
| `app/src/main/res/xml/file_paths.xml` | FileProvider 暴露的 cache 目录映射 |
| `app/src/main/res/values/strings.xml` | 仅包含 `app_name` 字符串资源 |
| `app/src/main/java/com/lansync/app/LanSyncApplication.kt` | Application 入口，仅初始化 `FileLogger`，未做全局配置加载 |

## 3. 架构与约定

### 3.1 运行时配置：`AppConfig` data class
- 位置：`com.lansync.app.data.AppConfig`。
- 所有字段均有 Kotlin 默认值，构成“内置默认配置”：心跳间隔（20s）、同步间隔（120s）、连接超时（30s）、轮询间隔（500ms）、重试次数（5）、重试延迟（3s）等。
- 通过 `AppConfig.DEFAULT` 作为模块构造函数的默认参数注入。例如 `AppRepository(context, config = AppConfig.DEFAULT)`，测试中可传入自定义 `AppConfig` 覆盖部分字段。
- 当前没有任何从 `SharedPreferences`、`BuildConfig`、资源文件或网络拉取配置到 `AppConfig` 的逻辑——它是纯内存常量集合。

### 3.2 持久化配置：SharedPreferences
- 仅有一处使用：`JmDNSDiscovery` 在启动时通过 `context.getSharedPreferences("lansync_device", Context.MODE_PRIVATE)` 读写 `device_instance_id`。
- 若不存在则生成 UUID 并写回，用于跨进程/重启识别同一设备。
- 键名硬编码为字符串字面量，无集中配置类。

### 3.3 构建期配置：Gradle + Manifest + Resources
- `build.gradle.kts` 中 `defaultConfig` 固定 `applicationId`、`minSdk=29`、`targetSdk=34`、`versionCode=1`、`versionName="1.0"`。
- `signingConfigs.debug` 启用 V1/V2 签名；`release` 开启混淆和资源压缩，但注释提示需手动配置正式签名密钥。
- `gradle.properties` 设置 `android.useAndroidX=true`、`kotlin.code.style=official`、`org.gradle.configuration-cache=true` 等构建行为。
- `AndroidManifest.xml` 声明网络相关权限（INTERNET、ACCESS_WIFI_STATE、CHANGE_WIFI_MULTICAST_STATE、NEARBY_WIFI_DEVICES 等）、`usesCleartextTraffic="true"` 以及 `networkSecurityConfig`。
- `res/xml/network_security_config.xml` 允许 cleartext 流量，仅信任系统证书。
- `res/xml/file_paths.xml` 将 `cache/apks` 和 `cache/downloads` 暴露给 `FileProvider`。
- 字符串资源仅 `app_name`，其余 UI 文案以 Compose 字符串字面量硬编码。

### 3.4 初始化流程
- `LanSyncApplication.onCreate()` 仅调用 `FileLogger.init(this)`，不加载任何配置源。
- 各组件在构造时直接消费 `AppConfig.DEFAULT` 或自行读取 `SharedPreferences`。

## 4. 约定与约束

- **运行时参数必须通过 `AppConfig` 字段表达**：所有可调阈值（心跳、超时、重试、节流）集中在一个 data class 中，便于测试替换。
- **设备标识持久化统一走 `SharedPreferences("lansync_device")`**：目前仅 `device_instance_id` 一项，键名硬编码。
- **无动态配置热更新**：应用启动后 `AppConfig` 不可变，无法在不重启的情况下调整行为。
- **构建产物差异由 Gradle buildTypes 控制**：debug/release 区分调试开关与混淆策略，但未使用 flavor dimension。
- **网络明文限制由 `network_security_config.xml` 全局放开**：这是针对局域网 P2P 场景的明确设计选择（允许 cleartext）。
- **资源最小化**：`strings.xml` 仅保留 `app_name`，其他文案未抽离为资源，属于当前阶段的简化做法。

## 5. 评估

该配置系统非常轻量：没有外部配置库，没有配置文件格式，没有环境变量或远程配置中心。它把“配置”拆成三块——内存常量（`AppConfig`）、本地持久化（`SharedPreferences`）、平台声明式配置（Gradle/Manifest/Resources）。这种模式适合小型 Android 应用，但在需要多环境切换、运行时热更新或敏感信息隔离时会显得不足。