# LanSync 代码重构计划

## 一、现状分析

### 1.1 核心功能与技术栈

LanSync 是局域网 P2P Android 应用更新同步工具:JmDNS 设备发现 → 本机应用扫描(PackageManager + MD5 指纹)→ 请求/轮询式连接配对 → HTTP 明文传输(单 APK 直传 / Split APK 打包 .apks)→ FileProvider 委托安装 + SAF 保存。

技术栈:Kotlin 1.9.20 + Jetpack Compose(Material3 BOM 2023.10.01 / 编译器扩展 1.5.5)+ Ktor Server 2.3.5(Netty)+ OkHttp 4.12.0 + JmDNS 3.5.8,单模块 `:app`,AGP 8.13.2,minSdk 29 / targetSdk 34。

### 1.2 已核实的主要问题(代码实证)

| # | 问题 | 实证位置 |
|---|---|---|
| 1 | **AppRepository 上帝类**:1034 行,连接编排/心跳/双列表/端口迁移/更新推荐/下载安装/缓存全部混杂,暴露 **12 个 MutableStateFlow**,`ConcurrentHashMap` + Job 表 + 共享 scope 多把并发原语混用 | `data/repository/AppRepository.kt` L51-L64 |
| 2 | **无前台服务**:Ktor 服务端 + JmDNS + 心跳全部活在 Activity 生命周期内,退后台即失效 | `AppRepository.start()` |
| 3 | **明文 HTTP 无鉴权**:任何局域网设备可直接 GET `/api/applist` 枚举包名清单,且响应含 `sourcePaths` 本机绝对路径 | `KtorServer.kt`、`AppInfo` 模型 |
| 4 | **双套哈希语义**:列表中 `md5` = 原始 APK 文件拼接摘要,`X-MD5` = 打包产物(zip)摘要,客户端优先信 `X-MD5`、`expectedMd5` 仅兜底——隐式两套语义易踩坑;且 `HashUtils.digestFile` 对不可读文件**静默跳过**,全不可读时产出空摘要 `d41d8...` 而非失败 | `HashUtils.kt` L38-L53、`AppPacker.kt`、`AppListClient.kt` 校验逻辑 |
| 5 | **错误泄露内部异常**:Ktor 至少 5 处 `respondText("...${e.message}")` 回显异常细节 | `KtorServer.kt` L113/L172/L206 等 |
| 6 | **分层倒置**:data 层 `AppRepository` L22 直接 `import com.lansync.app.ui.components.preloadIcon` | 已确认 |
| 7 | **逻辑重复**:包名从文件名反推的 `extractPackageName` 在 `AppListClient.kt` L531 与 `MainViewModel.kt` L319 各实现一份 | 已确认 |
| 8 | **死依赖**:`gson:2.10.1`、`play-services-base:18.3.0` 全代码零引用(`build.gradle.kts` L95/L104);`/api/download` 两个路由大量重复 handler | 已确认 |
| 9 | **测试盲区**:仅 6 个 JVM 纯逻辑测试,AppRepository 编排、Ktor 路由、下载校验链路零覆盖;已引入 `ktor-server-test-host`/`MockK` 却未使用 | `app/src/test` |
| 10 | **工具链陈旧**:Kotlin 1.9.20 + 编译器扩展 1.5.5 组合老,Compose 编译器未迁移到独立 Gradle 插件;`android.enableJetifier=true` 已无必要;`configuration-cache` 与部分插件兼容性存疑 | 根/app `build.gradle.kts` |
| 11 | **陈旧缓存**:`local_apps_cache.json` 启动即用,应用卸载/更新后短暂展示脏数据,`installApp` "not found" 常源于此 | `MainViewModel.init` |

## 二、重构目标

1. **可维护性**:消灭上帝类,AppRepository 降为组合门面,每个协作者 ≤300 行、单一职责、可独立测试
2. **正确性**:并发状态收敛到单一变更路径,消除心跳超时/端口迁移/连接去重的竞态窗口;哈希校验语义唯一化并有文档规范
3. **可用性**:引入前台服务使"服务端 + 发现 + 心跳"在退后台后仍存活(或明确降级为前台定位并写入文档)
4. **安全性**:配对后 token 校验、剥离跨设备 `sourcePaths`、错误响应不泄露内部异常
5. **质量底座**:Ktor 路由集成测试、连接状态机测试、下载校验测试全覆盖;工具链升级到 Kotlin 2.x
6. **约束**:全程保持与旧版本设备的局域网互操作能力(协议版本协商 + 兼容期)

## 三、实施阶段规划

### 阶段 0:基线与护栏(先行,约 1 周)

不改任何生产代码,建立"敢重构"的前提。

- 固化 `gradlew assembleDebug test` 绿色基线;用 `adb` 手动走通两条黄金链路(双机连接→更新推荐、下载→MD5 校验→安装),记录为《手工回归清单》
- 为现状写**特征化测试**(characterization tests):用 `ktor-server-test-host` 为现有 10 个路由补快照式行为测试(状态码/响应头/响应体形状);为 `ConnectionManager` 状态机、下载 MD5 校验分支补 MockK 测试
- 关键动作:把 Ktor 路由从 `KtorServer.kt` 的回调注入结构提取成可独立 install 的 `Routing.module()` 形式(仅结构搬运,不改行为)——这是后续所有路由测试的接缝

**交付物**:绿色测试基线、《手工回归清单》、路由集成测试套件(重构验收标准)

### 阶段 1:工程卫生与快赢项(约 1 周,低风险先行合并)

- 移除死依赖:`gson`、`play-services-base`;移除 `android.enableJetifier`
- 收敛 `extractPackageName`:`MainViewModel` L319 删除,统一调用 `AppListClient`(或下沉到 `data` 层独立 `DownloadedFileName` 工具);文件名解析失败返回 null 并显式处理,不再猜测
- 合并 `/api/download/{pkg}` 与 `/api/download/{pkg}/{vc}` 两个 handler 为同一参数化实现
- Ktor 错误响应统一:`respond(LanSyncErrorDto(code, message))`,`e.message` 只进 `FileLogger`;定义错误码枚举(APP_NOT_FOUND / NOT_EXTRACTABLE / PACK_FAILED / INVALID_REQUEST / CONFLICT / INTERNAL)
- 修复 `HashUtils.digestFile`:不可读文件不再静默跳过 → `md5(paths)` 返回 null 传播;同步修正 `HashUtilsTest`/`HashUtilsConsistencyTest` 中被"固化"的错误预期;`AppScanner` 对 null 指纹的包降级为 `isExtractable=false`(与现有语义对齐)
- 清理 `forceStartSync()`:与 `start()` 重复则删除该按钮或改为 `restart()`(停止+启动)
- 修复分层倒置:图标预加载下沉——`AppIconDiskCache` 增加 `preload(packageNames)` API,`AppRepository` 只依赖 data 层;`AppIcon.kt` 的 `preloadIcon` 变薄壳

**交付物**:依赖/告警清理提交、统一错误协议、哈希 null 语义、分层修正

### 阶段 2:工具链升级(约 1 周,独立分支验证)

- Kotlin 1.9.20 → 2.0.x:移除 `composeOptions.kotlinCompilerExtensionVersion`,引入 `org.jetbrains.kotlin.plugin.compose`
- Compose BOM 2023.10.01 → 2024.09.00+;lifecycle 2.6.2 → 2.8.x;Ktor 2.3.5 → 2.3.12(2.3.x 内升级,不与本阶段架构改动叠加)
- 验证 R8:minify + shrinkResources 下跑通 release 包安装器链路;补 `proguard-rules.pro` 对 kotlinx-serialization DTO 的 keep 规则
- 处理 `configuration-cache` 弃用项,删除重复的 `org.gradle.unsafe.configuration-cache`

**交付物**:升级后的绿色构建 + release 包真机冒烟记录

### 阶段 3:引入 DI 与模块骨架(约 1-2 周)

- 引入 **Hilt**:`LanSyncApplication` 加 `@HiltApplication`;把 `AppRepository`/`ConnectionManager`/`JmDNSDiscovery`/`KtorServer`/`AppListClient` 等手写单例改为 `@Singleton @Inject` 构造注入,统一注入 `CoroutineScope`(qualified `@ApplicationScope`)与 `Dispatcher`(`@IoDispatcher`/`@DefaultDispatcher`)——为阶段 4 的单线程收敛和测试可替换性铺路
- `KtorServer` 的 5 个 `setXxx` 回调注入改为构造注入接口:`ServerApiDelegate`(把路由处理器与服务器生命周期解耦)
- ViewModel 改 `@HiltViewModel`,删除手动工厂
- 包结构预梳理(仍单 Gradle 模块,避免构建复杂度):`data` 下按领域建 `connection/`、`sync/`、`transfer/`、`localapps/` 子包空壳,后续代码迁入

**交付物**:DI 图稳定运行、无行为变化、回调注入清零

### 阶段 4:拆分 AppRepository(核心,约 2-3 周,绞杀者模式)

`AppRepository` 保留为**门面**(委托 + 组合暴露原 StateFlow,对 ViewModel 的公开 API 零变化),按序每次抽出一个协作者、独立 PR、每步过阶段 0 护栏:

1. **`LocalAppRepository`**:扫描触发、`_localApps`、缓存读写(`local_apps_cache.json`)、图标预加载调度;启动策略改为"缓存骨架展示 + 后台静默校验刷新"(解决中风险 8)
2. **`ConnectionCoordinator`**:`_rawDiscoveredDevices`/`_enrichedDevices`/`_connectedDevices` 三列表维护、`connectDevice`/断开编排、心跳循环、端口迁移、`connectingDevices`/`heartbeatFailCounts` 状态——**并发规范化在此步完成**:所有状态变更收敛到单线程 actor(专用 `Channel<ConnectionEvent>` + 循环协程)或 `Mutex`,消灭"先改 map 再更新 StateFlow"的交错;`ConnectionManager` 的 `ConcurrentHashMap` + `StateFlow.value` 无锁读改写同样收进该模型
3. **`UpdateCoordinator`**:`combine(localApps, connectedDevices)` 节流 → `UpdateManager.findUpdates/deduplicate` → `_availableUpdates`/`_syncDiffs`
4. **`DownloadInstallController`**:`downloadApp`/`installApp`/`_downloadProgress`/`_installStatus`
5. 门面剩余内容清空后,ViewModel 直连各协作者或保留瘦门面;同步用**不可变聚合状态**替代 12 路手动 copy:`LanSyncUiState`(数据类)+ 单一 `combine`/reduce

每步验收:路由测试 + 手工黄金链路 + 新增对应协作者的单元测试。

**交付物**:AppRepository ≤150 行门面;四个协作者各 ≤300 行且独立可测;状态转换单点化文档

### 阶段 5:前台服务与生命周期(约 1 周,含决策门)

- **决策门**(先行 1 页 ADR):方案 A「引入 Foreground Service」符合工具定位(后台保活同步);方案 B「明确前台使用」零成本但牺牲场景。默认取 A
- 方案 A 落地:`SyncForegroundService`(ForegroundServiceType=`specialUse` 或 `dataSync`,声明 `FOREGROUND_SERVICE` + API34 的 `FOREGROUND_SERVICE_SPECIAL_USE` 权限),通知栏常驻显示端口/已连接设备数;`repository.start()/stop()` 绑定服务生命周期而非 ViewModel;`POST_NOTIFICATIONS`(API 33+)运行时请求;`MainViewModel` 用 `LifecycleRepeatableOwner` 观察服务暴露的 StateFlow
- 处理进程被杀场景:服务 `START_STICKY` + 启动时从缓存恢复 instanceId(已有)
- **注意与阶段 1 的清理联动**:若引入 FGS,"强制启动同步"按钮语义改为"重启服务"

**交付物**:退后台 5 分钟链路存活实测记录、ADR 文档、通知交互设计

### 阶段 6:安全加固与协议版本化(约 2 周,依赖阶段 4 的 ConnectionCoordinator)

- **协议版本协商(本阶段前置)**:JmDNS TXT 记录增加 `proto=2` 字段,`/api/deviceinfo` 增加 `protocolVersion`;双方按 min(版本) 启用新特性,旧版本(无 proto 字段)走现行为——**这是所有安全特性的兼容开关**
- **配对后 token 校验**:连接 accepted 时双方生成随机 `sharedSecret`(经已有的 request/response 通道带外交换,新字段),此后 `/api/applist`、`/api/download/*`、`/api/refresh-applist` 必须携带 `X-LanSync-Auth` 头(直接比对或 HMAC(challenge, ts) 防重放);未配对设备仅可访问 `/api/ping`、`/api/deviceinfo`、`/api/connect/*`。proto=1 旧客户端容忍期并行,UI 提示"对方版本过旧"
- **剥离 sourcePaths**:响应侧 `AppInfoDto` 过滤 `sourcePaths`/`md5` 明文列表接口;打包改用服务端本地 `AppInfo`(数据本就在服务端,零成本)
- **哈希语义统一(解决高风险 4)**:协议规定——列表内 `md5` 仅作**版本内容指纹**(去重/比较用,重命名为 `contentFingerprint` 并换 SHA-256,proto=2);传输完整性一律以响应头 `X-Transfer-Hash`(打包产物实时 SHA-256)为准;`AppListClient` 移除 `expectedMd5` 兜底分支;`AppPacker` 同步计算流式哈希改为 SHA-256
- **权限审查**:验证仅凭 `NEARBY_WIFI_DEVICES` 能否去掉定位权限(API 33+ 主路径);`QUERY_ALL_PACKAGES` 补 `<tools:purpose>` 说明并评估 `<queries>` 替代可行性(本地全量扫描场景基本无法避免,文档说明 Play 上架风险)
- HTTPS/TLS(自签 + 指纹钉定)**明确不做**,在 README 记录决策:token + 局域网边界已满足威胁模型,留作 proto=3 备选

**交付物**:安全设计文档(威胁模型 + 协议规范)、双版本互操作测试矩阵(新×新 / 新×旧 / 旧×旧)

### 阶段 7:收尾与可选增强(约 1 周 + 按需)

- `AppScanner` 结果缓存与 `local_apps_cache.json` 读写迁移 **DataStore**(键值场景足够;多设备 appList 历史若需查询再上 Room——默认不做 Room,保持零数据库简单性)
- UI 组件拆分:`AppListScreen`(483 行)/`RemoteAppListScreen`(455 行)提取公共列表脚手架(搜索栏 + 分类 Tab + 多选条)
- `FileLogger` 与 `android.util.Log` 二选一策略:debug 构建双写 logcat,release 仅文件
- 更新 README/REPORT 的协议与架构章节,与重构后代码对齐

**交付物**:重构总结报告、更新后的架构文档

### 里程碑视图

| 阶段 | 主题 | 周期 | 高风险动作 | 回滚粒度 |
|---|---|---|---|---|
| 0 | 测试护栏 | 1 周 | 无 | — |
| 1 | 卫生快赢 | 1 周 | 无 | 按提交 |
| 2 | 工具链 | 1 周 | 中(R8) | 整分支 |
| 3 | DI | 1-2 周 | 中 | 按 PR |
| 4 | Repository 拆分 | 2-3 周 | **高** | 每协作者一 PR |
| 5 | 前台服务 | 1 周 | 中 | 特性开关 |
| 6 | 安全 + 协议 | 2 周 | **高**(互操作) | 按 proto 协商 |
| 7 | 收尾 | 1 周 | 无 | — |

## 四、高风险项评估与应对(对应 REPORT 7.1)

### R1 上帝类拆分(AppRepository 1034 行 / 12 StateFlow)

- **风险本质**:隐式时序耦合——心跳循环、端口迁移、连接锁之间依赖执行顺序,拆错即引入难复现的竞态回归
- **应对**:① 阶段 0 特征化测试先行,拆分零"自由发挥";② 绞杀者模式:门面 API 全程不变,ViewModel 无感知,每次只搬一个职责;③ 迁移顺序先"无并发"的(LocalAppRepository/UpdateCoordinator/DownloadInstallController),最后集中处理并发的 ConnectionCoordinator,把竞态治理压缩到单一 PR 评审;④ 并发规范:事件通道 + 单协程收敛全部状态写,禁止 `ConcurrentHashMap` 与 StateFlow 混合同步;⑤ 每个 PR 跑双机黄金链路
- **失败回滚**:门面模式下任何协作者可单独退回委托旧路径,git revert 粒度小

### R2 无前台服务 / 进程保活

- **风险本质**:Android 12+ FGS 启动限制(需前台时序触发)、API 34 强制 `foregroundServiceType` 声明、Doze/厂商省电杀进程、电池优化投诉与新权限(`POST_NOTIFICATIONS`)打扰
- **应对**:① ADR 决策门,不默认上 FGS;② 类型选 `specialUse`(附 Play 说明)或 `dataSync`,启动时机固定在用户点击"启用同步"的前台时刻规避限制;③ 断连策略明确:进程真被杀则通知消失、重进 App 自动恢复(instanceId 持久化已支持身份无缝重连,IP/端口变化由端口迁移逻辑吸收);④ 低端 OEM 场景在 README 标注"必要时加入电池白名单",提供跳转设置入口;⑤ 特性开关可一键回退到"前台模式"

### R3 明文 HTTP + 无鉴权

- **风险本质**:局域网信任模型被破坏(访客网络/公共 WiFi 下包名清单泄露、流量消耗);加鉴权会破坏与旧版本互操作
- **应对**:① 兼容性由 **proto 字段协商**兜底:新客户端对旧设备降级为匿名模式并在设备卡片上标注,永不硬失败;② token 走已配对的 connect request/response 通道带外交换,避免引入第二套握手;③ 威胁边界写文档:LanSync 定位为**可信局域网工具**,token 防的是同网段非配对设备,不防已配对恶意设备与网段外攻击;HTTPS 列入 proto=3 备选,当前用 auth 头 + 明文满足需求且省掉证书钉定 UX 复杂度;④ `sourcePaths` 剥离(隐私)先于 token(鉴权)落地,前者零互操作风险可提前到阶段 4
- **回归防护**:互操作测试矩阵(新×新/新×旧/旧×旧)是验收门槛

### R4 双套 MD5 哈希语义

- **风险本质**:列表 `md5`(原始文件拼接)与 `X-MD5`(zip 产物)必然不等,当前靠"客户端优先信 X-MD5"的隐式约定掩盖;一旦服务端漏发头,`expectedMd5` 兜底校验**注定失败**,产生"偶然下载校验错误"的鬼故事;MD5 抗碰撞性也已不足
- **应对**:① 立即止血(阶段 1):`digestFile` null 传播,消灭 `d41d8...` 幽灵指纹——陈旧测试同步修正;② 协议定形(阶段 6):语义一分为二——`contentFingerprint`(SHA-256 流式,版本比较/去重,仅元数据)与 `X-Transfer-Hash`(SHA-256,传输完整性,唯一校验依据);客户端删除 expectedMd5 兜底分支,缺头即报错而非猜测;③ 服务端保证打包与哈希单次流式完成(现 `AppPacker` 已如此),响应头缺失视为服务端 bug 进日志;④ 协议规范文档明确"任何校验失败删除文件"的现有行为为契约;⑤ 用阶段 0 的路由测试锁定 `X-Transfer-Hash` 必发
- **兼容**:proto=1 旧客户端仍收 `X-MD5`(zip 的 MD5),双头并发一个版本周期后移除

## 五、假设与边界

- 单人/小团队维护,无并行大重构窗口,故全程渐进式、每阶段可独立发布
- 无历史版本包袱(应用未大规模分发),但双端互升级是真实场景,协议协商不可省
- 保持单 Gradle 模块(功能拆分先包级、不上 `:core`/`:network` 多模块),避免构建复杂度成为二次风险;若阶段 4 后编译反馈明显变慢再评估拆分
- Room/DataStore、TLS、多设备并发传输优化均为可选项,默认不做,不阻塞主线