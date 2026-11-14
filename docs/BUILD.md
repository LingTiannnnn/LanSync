# 本机编译与测试（BUILD）

```bash
cd "E:/S.H.I.T/LanSync"
./gradlew.bat assembleDebug     --offline --no-configuration-cache --console=plain
./gradlew.bat testDebugUnitTest              --no-configuration-cache --console=plain   # 不能带 --offline
./gradlew.bat assembleRelease   --offline --no-configuration-cache --console=plain -x lintVitalRelease -x lintVitalReportRelease -x lintVitalAnalyzeRelease   # lint/签名/体积口径见 §4
```

三条命令都用**机器级默认 `GRADLE_USER_HOME`**（不要加覆盖）。实测通过：`assembleDebug` 离线可编译；`testDebugUnitTest` 去掉 `--offline` 后依赖联网解析、全绿；`assembleRelease` 口径见 §4（lint / 签名 / 体积的唯一来源）。

## 1. 四条前提（缺一即失败）

1. **测试依赖不在任何离线缓存里**：`GRADLE_USER_HOME` 覆盖为 `C:\Users\LingTian\.gradle` 后只剩 `wrapper/`（无 `caches/`），连 Gradle 8.13 发行包都要联网下载；机器级仓库 `E:\S.H.I.T\Gradle\GradleRepository\caches\modules-2\files-2.1` 有 androidx / compose / ktor-server 但**没有** `junit:junit`、`io.mockk`、`kotlinx-coroutines-test`、`ktor-server-test-host`。所以 **`testDebugUnitTest` 必须去掉 `--offline`**（首次解析走网络，之后落进机器级缓存即可离线）；`assembleDebug` 主源码编译不受影响，可继续 `--offline`。
2. **`local.properties` 必须存在**，至少 `sdk.dir=E\:\\S.H.I.T\\Android SDK`。它被 `.gitignore` 忽略且不在版本控制内，本机 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 为空无法兜底。⚠️ **切分支会把它删掉**：老提交上该文件仍被跟踪，`git checkout` 会用跟踪版静默覆盖被忽略的本地文件（git 对 ignored 文件不告警），随后快进到移除它的提交时文件消失而 `git status` 干净。
   - 切分支后先 `ls local.properties`；恢复：`git show 66a6291^:local.properties > local.properties`。
3. **`./gradlew.bat` 可直接用**（`--version --offline` 输出 Gradle 8.13 / Launcher JVM 17.0.20.1，不联网）。仅当 `C:\` 缓存整体不可用时才回落用 Gradle 发行版自带的 `bin/gradle.bat`。
4. **判定以输出里的 `BUILD SUCCESSFUL` 为准**，不要信 exit code：PowerShell 会把 JVM stderr 警告当 error 致 `ExitCode=1`；**不要把输出管道给 `tail`**——管道会用 `tail` 的 0 把 `BUILD FAILED` 掩盖成"成功"。
   - `BUILD SUCCESSFUL` 还可能来自**根本没跑的测试**：`:app:testDebugUnitTest UP-TO-DATE` 表示本轮没有执行门禁。三道文本门禁读的是 `docs/**`、`.git` 引用与 main 源码文本，都不在 Test 任务的默认输入里，所以 `app/build.gradle.kts` 末尾用 `inputs.files(...)` 把它们登记成输入 —— 改文档后任务会重跑，没改动时才是 UP-TO-DATE。复核绿灯看 `app/build/test-results/testDebugUnitTest/*.xml` 的用例数与文件时间戳，不只看构建输出。

## 2. 仓库与依赖约束

- 机器级 init 脚本 `E:\S.H.I.T\Gradle\init.d\init.gradle` 只注入 `aliyun/public + mavenLocal + mavenCentral`（**无 `google()`**）；`settings.gradle.kts:17` 的 `repositoriesMode=PREFER_SETTINGS` 正是为此而设，**不要改回 `PREFER_PROJECT`**（会 androidx 404）。
- 新增测试依赖前先确认它在 `C:\Users\LingTian\.gradle` 离线缓存里——**不在就写不了**（分层门禁因此用零依赖单测而非 Konsist）。
- PowerShell 下命令分隔用 `;` 而非 `&&`。

## 3. 技术栈与版本锁

| 层 | 技术 / 版本 |
|---|---|
| 语言 / JVM | Kotlin **1.9.20**，`jvmTarget = 17`，JDK 17.0.20.1（`C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`） |
| UI | Jetpack Compose + Material3，BOM **2023.10.01**（material3 1.1.x），Compose 编译器扩展 **1.5.5**；ui/ui-graphics/foundation/runtime/animation 显式 **1.6.8**（修复大列表触摸命中测试 hitTest 的框架级卡顿，pointer 管线，2026-10-31 真机插桩定位） |
| 服务端 | Ktor Server 2.3.5（**CIO 引擎**，纯 Kotlin；netty 已移除，见 §4）+ ContentNegotiation(kotlinx-json) |
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

- **R8 蒸馏（2026-11-15 二次裁决，v1.3）**：`isMinifyEnabled = true`、`isShrinkResources = true`，默认规则用**非 optimize 版**（`proguard-android.txt`，即 `-dontoptimize`）→ 单 dex 产物。R8 × netty 的历史事故：v1.2 闪退 = `ReflectiveChannelFactory` 反射目标的无参构造器被裁（Channel 实现 `-keep` 修复）；v1.3 连接 RST = `MessageToMessageEncoder` 构造函数经 `TypeParameterMatcher` 反射读**类泛型签名**（要求 `getTypeParameters()` 返回 `<I>`），而 R8 即使 `-keep` 该类 + `-keepattributes Signature` 也不把类型参数签名写进 dex（实测：dex 中类级 Signature 缺失，ProGuard 层面的 keep 无法修复）。**结论：服务端引擎整体换 CIO（纯 Kotlin/coroutines，无反射面）**，`proguard-rules.pro`「Ktor & Netty」段的 netty keep 规则保留作文档与回滚依据。**改 keep 规则或引擎后必须真机全链路冒烟再发版**。App 启动日志带安装指纹与关键类探针（`build x.y (code), apk installed/updated at ...` + `class probe: ...`），排查装机/类可见性问题看日志首屏。**SLF4J 自建绑定**（`org/slf4j/impl/StaticLoggerBinder.kt`，proguard 有 `-keep`）把库日志转发进 `lansync_debug.log`——此前 slf4j 无绑定静默降级为 NOP，库内异常（如 netty 的 `exceptionCaught`）全部丢失；v1.3 的 `unknown type parameter 'I'` 正是靠它抓到的。
- ⛔ **禁止剔除任何 netty 模块**：ktor-server-netty 的 netty 栈是经 `netty-codec-http2` / `netty-transport-native-epoll` 这两个**声明**传递进来的，剔除它们会让整个 netty 脱离类路径；而 **D8/R8 对「超类缺失」的类会静默从 dex 丢弃（构建不报错）**，产物表现为 ktor 引擎类还在、其 netty 超类与 handler 全无 → 启动即 `NoClassDefFoundError`。教训：v1.3 排查中曾误判为「多 dex 产物损坏 / 增量缓存污染」，实际是依赖剔除越权。**任何依赖变更后验证两件事**：`releaseRuntimeClasspath` 依赖树里 `io.netty` 计数 > 0、`dexdump` 抽查关键类定义存在。
- lint 链的 `com.android.tools.lint:lint-gradle:31.13.2` 不在本机离线缓存——离线跑 `assembleRelease` 需如顶部命令带三个 `-x lintVital*`（只跳 lint，不影响打包），或首跑去掉 `--offline` 联网解析一次。
- 产物 `app-release.apk` 挂 **debug 签名**（2026-11-08 裁决：正式 keystore 延后，`build.gradle.kts` 已临时接 `signingConfigs.getByName("debug")`）——可直接安装，与手机上已装 debug 包同签名、可覆盖升级；换正式 keystore 后需卸载重装。
- **体积治理（2026-11-15，与 R8 蒸馏叠加）**：45.4 MB → **4.7 MB**。① 图标改 `material-icons-core`（49 个经典图标），core 未收录的 26 个由 `ui/icons/LanSyncIcons.kt` 本地提供（path 取自 Material 官方 24px filled，挂 `Icons.Filled` 扩展属性与库同命名空间）——icons-extended 约 7000 个图标类 ≈30MB dex；② `packaging.resources.excludes` 剔除 `org/fusesource/**`（jansi 桌面原生库）；③ 服务端引擎 Netty → **CIO**（纯 Kotlin，dex 少 ~1.6MB 且无反射面，见下条）；④ `releaseRuntimeClasspath` 级依赖剔除只允许 **jansi + kotlin-reflect**（字节码级零引用验证；MockK 依赖 kotlin-reflect，剔除不能外溢到测试类路径）。
- ⚠️ **依赖图/打包配置变更后必须 `clean` 再出 release**：AGP 增量 dex 缓存在 R8 开关切换 + 依赖剔除的连续变更间被污染，曾产出「引用在、定义丢」的残缺 dex（v1.3 首次装机即 `NoClassDefFoundError: NettyApplicationCallHandler`，该类被引擎直接构造却无定义）。**单测门禁跑在 JVM 类路径上，覆盖不到 dex 层**——绿灯不等于 dex 完整。交付前的完整校验：`dexdump` 提取 dex 定义类集合，与 releaseRuntimeClasspath 各 jar/aar 的类集合比对（按依赖树的解决版本取坐标），「被引用但未定义」必须为零。
- 体积参考（2026-11 实测）：release（无 R8 + 图标/依赖治理，v1.3）≈ 20.4 MB；历史数字族：R8+optimize 5.4 / R8+`-dontoptimize` 7.8 / 未治理无 R8 45.4 / debug ≈ 65 / v1.1 误发 debug 100.52 / R8+keep-all 26.1。
- 测试结果的权威来源是 `app/build/test-results/testDebugUnitTest/*.xml` 的 failures / errors / skipped 计数；门禁语义见 `docs/TEST-PLAN.md` §7。
