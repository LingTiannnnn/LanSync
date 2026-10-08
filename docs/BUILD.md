# 本机编译与测试（BUILD）

```bash
cd "E:/S.H.I.T/LanSync"
GRADLE_USER_HOME="C:/Users/LingTian/.gradle" ./gradlew.bat testDebugUnitTest --offline --no-configuration-cache --console=plain
GRADLE_USER_HOME="C:/Users/LingTian/.gradle" ./gradlew.bat assembleDebug    --offline --no-configuration-cache --console=plain
```

## 1. 四条前提（缺一即失败）

1. **`GRADLE_USER_HOME` 必须覆盖为 `C:\Users\LingTian\.gradle`**。机器级默认 `E:\S.H.I.T\Gradle\GradleRepository` 缺全部测试依赖（junit / mockk / coroutines-test / ktor-server-test-host），`--offline` 下 `compileDebugUnitTestKotlin` 必失败；主源码编译不受影响，症状是"只有测试跑不起来"。
2. **`local.properties` 必须存在**，至少 `sdk.dir=E\:\\S.H.I.T\\Android SDK`。它被 `.gitignore` 忽略且不在版本控制内，本机 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 为空无法兜底。⚠️ **切分支会把它删掉**：老提交上该文件仍被跟踪，`git checkout` 会用跟踪版静默覆盖被忽略的本地文件（git 对 ignored 文件不告警），随后快进到移除它的提交时文件消失而 `git status` 干净。
   - 切分支后先 `ls local.properties`；恢复：`git show 66a6291^:local.properties > local.properties`。
3. **`./gradlew.bat` 可直接用**（`--version --offline` 输出 Gradle 8.13 / Launcher JVM 17.0.20.1，不联网）。仅当 `C:\` 缓存整体不可用时才回落用 Gradle 发行版自带的 `bin/gradle.bat`。
4. **判定以输出里的 `BUILD SUCCESSFUL` 为准**，不要信 exit code：PowerShell 会把 JVM stderr 警告当 error 致 `ExitCode=1`；**不要把输出管道给 `tail`**——管道会用 `tail` 的 0 把 `BUILD FAILED` 掩盖成"成功"。

## 2. 仓库与依赖约束

- 机器级 init 脚本 `E:\S.H.I.T\Gradle\init.d\init.gradle` 只注入 `aliyun/public + mavenLocal + mavenCentral`（**无 `google()`**）；`settings.gradle.kts:17` 的 `repositoriesMode=PREFER_SETTINGS` 正是为此而设，**不要改回 `PREFER_PROJECT`**（会 androidx 404）。
- 新增测试依赖前先确认它在 `C:\Users\LingTian\.gradle` 离线缓存里——**不在就写不了**（分层门禁因此用零依赖单测而非 Konsist）。
- PowerShell 下命令分隔用 `;` 而非 `&&`。

## 3. 技术栈与版本锁

| 层 | 技术 / 版本 |
|---|---|
| 语言 / JVM | Kotlin **1.9.20**，`jvmTarget = 17`，JDK 17.0.20.1（`C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`） |
| UI | Jetpack Compose + Material3，BOM **2023.10.01**（material3 1.1.x），Compose 编译器扩展 **1.5.5** |
| 服务端 | Ktor Server 2.3.5（Netty）+ ContentNegotiation(kotlinx-json) |
| 客户端 | OkHttp **4.12.0** |
| 发现 | JmDNS 3.5.8 |
| 序列化 | kotlinx-serialization-json 1.6.0 |
| 异步 | kotlinx-coroutines 1.7.3 |
| 其他 | documentfile 1.0.1（SAF）、core-ktx 1.12.0（FileProvider）、lifecycle 2.6.2、activity-compose 1.8.1 |
| 构建 | Gradle **8.13** / AGP 8.13.2 / `buildToolsVersion = "35.0.0"`（固定，避免联网下载） |
| 测试 | JUnit4 4.13.2 + MockK 1.13.8 + coroutines-test 1.7.3 + ktor-server-test-host 2.3.5 |
| SDK | `minSdk 29` / `targetSdk 34` / `compileSdk 34` |

> ⛔ **版本三者绑死**：Kotlin 1.9.20 ↔ Compose 编译器 1.5.5 ↔ Compose BOM 2023.10.01。升任一必须整体升，走独立分支，**不与协议/架构改动叠加**（`docs/ROADMAP.md`）。
> ⚠️ material3 1.1.x **没有** `surfaceContainer*` 角色 → 项目自建 `LanSyncContainerColors` + `LocalContainers` 四档兼容层；升 BOM 后方可收敛。

## 4. 其他命令与判定

- Release / R8 链路验证：同样带 `GRADLE_USER_HOME` 前缀跑 `./gradlew.bat assembleRelease`，再装真机冒烟（属 Phase 7，见 `docs/ROADMAP.md` §3）。
- 测试结果的权威来源是 `app/build/test-results/testDebugUnitTest/*.xml` 的 failures / errors / skipped 计数；门禁语义见 `docs/TEST-PLAN.md` §7。
