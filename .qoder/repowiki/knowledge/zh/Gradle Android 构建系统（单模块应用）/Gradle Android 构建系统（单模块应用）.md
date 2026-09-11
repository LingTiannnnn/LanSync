---
kind: build_system
name: Gradle Android 构建系统（单模块应用）
category: build_system
scope:
    - '**'
source_files:
    - settings.gradle.kts
    - build.gradle.kts
    - app/build.gradle.kts
    - gradle.properties
    - gradle/wrapper/
    - app/proguard-rules.pro
---

## 1. 使用的系统与工具
- 构建系统：Android Gradle Plugin (AGP) 8.13.2，基于 Kotlin DSL (`build.gradle.kts`)。
- Kotlin 版本：1.9.20，启用 `kotlin.plugin.serialization`。
- Compose 编译扩展：1.5.5，通过 `compose-bom:2023.10.01` 管理 Compose 依赖版本。
- JDK：编译与 JVM target 均为 Java 17；Gradle JVM args 配置 `-Xmx2048m`、UTF-8 编码及 TLS 协议限制。
- 仓库源：优先使用阿里云 Maven 镜像（gradle-plugin、public、google、central），再回退到官方 google() / mavenCentral()，并额外包含 jitpack.io。
- 工具链解析：通过 `org.gradle.toolchains.foojay-resolver-convention` 自动解析 Gradle 工具链。

## 2. 关键文件
- `settings.gradle.kts`：定义根项目名 `LanSync`，仅包含 `:app` 一个模块；集中配置 `pluginManagement` 与 `dependencyResolutionManagement`，并通过 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止子模块自行声明仓库。
- `build.gradle.kts`（根）：声明 AGP、Kotlin Android、Kotlin Serialization 插件及其版本，并以 `apply false` 方式在根聚合。
- `app/build.gradle.kts`：唯一业务模块的构建脚本，定义 namespace、compileSdk/targetSdk/minSdk、签名、构建类型、Compose 选项、打包排除规则及全部依赖。
- `gradle.properties`：全局 Gradle/Android 开关（AndroidX、Jetifier、非传递 RClass、配置缓存等）与 JVM 参数。
- `gradle/wrapper/` + `gradlew.bat`：Gradle Wrapper 分发。
- `app/proguard-rules.pro`：Release 混淆规则。

## 3. 架构与约定
- 单模块结构：根工程只包含 `:app` 一个 Android Application 模块，无 library 子模块，所有逻辑集中在 `com.lansync.app` 命名空间下。
- 版本集中管理：根 `build.gradle.kts` 用 plugins block 锁定 AGP 与 Kotlin 版本；Compose 依赖通过 BOM 统一版本；其他第三方库直接声明具体版本。
- 构建类型约定：
  - `debug`：启用 V1+V2 签名、可调试。
  - `release`：开启代码压缩 (`isMinifyEnabled = true`) 与资源裁剪 (`isShrinkResources = true`)，使用 ProGuard 优化，但正式签名密钥需手动配置（注释中明确提示）。
- 打包策略：`jniLibs.useLegacyPackaging = true` 以兼容旧版 so 包；`resources.excludes` 显式剔除 Netty/META-INF 冲突文件，避免 AAPT 报错。
- 测试集成：`testInstrumentationRunner` 指向 `androidx.test.runner.AndroidJUnitRunner`；单元测试依赖 JUnit 4、MockK、Coroutines Test、Ktor Test Host。
- 网络与安全：`network_security_config.xml` 用于允许明文 HTTP（局域网 Ktor Server），`file_paths.xml` 暴露共享存储路径供安装 APK 使用。

## 4. 约定与约束
- 仓库来源约束：`RepositoriesMode.FAIL_ON_PROJECT_REPOS` 强制所有依赖解析必须走 settings 中定义的仓库列表，子模块不得新增仓库。
- SDK 范围约束：`minSdk = 29`（Android 10），`targetSdk = compileSdk = 34`，JVM target 固定为 17，确保与 AGP 8.x 兼容。
- Compose 版本对齐：通过 `composeOptions.kotlinCompilerExtensionVersion = "1.5.5"` 与 Compose BOM 配合，避免编译器不匹配。
- Release 发布约束：`proguard-rules.pro` 必须存在且 release 构建会执行混淆；正式签名密钥未配置，构建脚本中以注释形式要求发布前手动填入 `signingConfigs.getByName("release")`。
- 配置缓存：启用 `org.gradle.configuration-cache=true` 与 `unsafe.configuration-cache`，提升增量构建速度。
- 无 CI/Docker/Makefile：仓库未发现 GitHub Actions、Jenkins、Dockerfile、Makefile 或自定义 shell 脚本，构建完全依赖本地 Gradle Wrapper。