---
kind: dependency_management
name: Android Gradle + Kotlin DSL 依赖管理（阿里云镜像、BOM 与集中仓库配置）
category: dependency_management
scope:
    - '**'
source_files:
    - settings.gradle.kts
    - build.gradle.kts
    - app/build.gradle.kts
    - gradle.properties
    - app/proguard-rules.pro
---

## 1. 使用的系统与工具

- **构建系统**：Android Gradle Plugin (AGP) 8.13.2，使用 Kotlin DSL (`build.gradle.kts` / `settings.gradle.kts`)。
- **插件声明**：根 `build.gradle.kts` 以 `apply false` 方式在顶层声明 AGP、Kotlin 编译器及 `kotlinx.serialization` 插件版本，子模块直接引用而不重复指定版本。
- **依赖声明位置**：所有第三方库集中在 `app/build.gradle.kts` 的 `dependencies {}` 块中，无多模块拆分。
- **仓库源**：通过 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 集中配置，禁止子模块自行添加仓库（`RepositoriesMode.FAIL_ON_PROJECT_REPOS`），确保全仓统一。
- **镜像策略**：优先使用阿里云 Maven 镜像（`maven.aliyun.com/repository/{public,google,central}`），再回退到官方 `google()`、`mavenCentral()`，并额外包含 `jitpack.io`。
- **Gradle 工具链**：通过 `org.gradle.toolchains.foojay-resolver-convention:0.10.0` 自动解析 JDK 工具链；JVM 目标锁定为 Java 17（`compileOptions` / `kotlinOptions.jvmTarget = "17"`）。
- **构建缓存**：启用 Gradle Configuration Cache（`org.gradle.configuration-cache=true`）。

## 2. 关键文件

| 文件 | 作用 |
|---|---|
| `settings.gradle.kts` | 集中声明插件仓库、依赖仓库、项目名与模块包含关系 |
| `build.gradle.kts`（根） | 集中声明 AGP、Kotlin、序列化插件的版本 |
| `app/build.gradle.kts` | 应用模块的 Android 配置、Compose BOM、全部三方依赖声明 |
| `gradle.properties` | JVM 参数、AndroidX、Jetifier、Configuration Cache 等全局开关 |
| `proguard-rules.pro` | Release 混淆规则（配合 `isMinifyEnabled = true`） |

## 3. 架构与约定

- **单一模块**：项目仅包含 `:app` 一个模块，所有依赖在该模块内声明，不存在跨模块共享依赖或 `libs.versions.toml` 版本目录。
- **Compose 版本对齐**：通过 `platform("androidx.compose:compose-bom:2023.10.01")` 引入 Compose BOM，后续 `ui`、`material3`、`ui-tooling-preview` 等依赖不写版本号，由 BOM 统一管理，避免 Compose 组件间版本冲突。
- **Ktor 版本一致性**：所有 Ktor 相关依赖（`ktor-server-core-jvm`、`ktor-server-netty-jvm`、`ktor-serialization-kotlinx-json`、`ktor-server-content-negotiation`、测试用的 `ktor-server-test-host`）均固定为 `2.3.5`，保持运行时一致。
- **协程与序列化版本对齐**：`kotlinx-coroutines-android:1.7.3` 与 `kotlinx-coroutines-test:1.7.3` 同版本；`kotlinx-serialization-json:1.6.0` 与 Kotlin 1.9.20 配套。
- **调试/测试依赖隔离**：`debugImplementation` 仅用于 UI Tooling 和测试 Manifest；`testImplementation` 限定 JUnit、MockK、协程测试、Ktor Test Host，不影响 release APK。
- **打包资源排除**：`packaging.resources.excludes` 显式剔除 Netty 的 `io.netty.versions.properties`、`META-INF/INDEX.LIST` 等，避免签名冲突。

## 4. 约定与约束

- **禁止子模块自定义仓库**：`dependencyResolutionManagement` 中设置 `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)`，任何在 `app/build.gradle.kts` 中添加 `repositories {}` 的行为都会导致构建失败。新增仓库必须修改 `settings.gradle.kts`。
- **插件版本集中管理**：根级 `plugins { id(...) version "..." apply false }` 是唯一的插件版本来源，子模块通过 `id("...")` 引用，不得在子模块再次声明版本。
- **Release 混淆强制开启**：`release` buildType 中 `isMinifyEnabled = true` 且 `isShrinkResources = true`，发布前需手动取消注释正式签名配置（见注释 `WARNING: 生产发布前需配置正式签名密钥！`）。
- **TLS 协议限制**：`gradle.properties` 强制使用 TLSv1.2/TLSv1.3 访问远程仓库，禁用旧版 TLS。
- **私有仓库/认证**：当前未配置任何私有 Maven 仓库或凭据；如需接入私有仓库，应在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 中追加 `maven { ... }` 并通过 `credentials` 配置认证。
- **无锁文件**：该项目不使用 Gradle 的 dependency lockfile（如 `gradle.lockfile`），也不存在 `go.mod`、`package.json` 等同构锁文件；依赖版本完全由 `.kts` 脚本中的字面量字符串决定，升级依赖需人工编辑对应行。
- **JITPACK 作为补充源**：除阿里云镜像与官方源外，额外引入 `jitpack.io`，用于拉取未在 Maven Central 发布的依赖（例如 fork 版本）。