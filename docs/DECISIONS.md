# 已裁决登记册（DECISIONS）

**勿重开**已裁决事项，除非命中该条写明的重开条件。字节级契约的查证过程在 `docs/SPEC.md` §6 / §9 / §10。

## 1. 协议与查证类

| 编号 | 事项 | 裁决 |
|---|---|---|
| T1 / TT1 | 旧版 APK 实物 | **存在**（用户事实，代码无从验证）；互操作验证归真机验收阶段 |
| T2 | `DeviceInfoResponse.version` | **死字段**：全 git 历史恒为默认 `"1.0"`，DTO + `encodeDefaults=false` 后从线上消失。新代码**禁止依赖它做任何兼容判断、禁止复用**；版本协商必须新增独立字段 |
| T3 | mDNS TXT 编码 | **不做字节级冻结**，只锁应用层键值契约（`deviceName` / `instanceId`，经 JmDNS `getPropertyString`）。已查证无手写 TXT 解析 |
| T4 | `Build.MODEL` 编码 | 按 **UTF-8** 标准，沿用库默认，不做转义增强 |
| T5 | 下载 Content-Type | 客户端**从不校验**；服务端 `.apk`→octet-stream、`.apks`→zip，照此复现 |
| T6 | 多网络 IP 分歧 | **仅承诺同一 Wi-Fi 两台设备**；两条本机 IP 取值路径原样保留，**禁止**引入网络选择 / 路由绑定等增强逻辑 |
| TT2 | 旧测试是否曾在 CI 跑通 | 未明确 → 以本机 `testDebugUnitTest` 实跑为绿色基线起点 |
| TT3 | 反向连接自动接受策略 | 采纳**干净语义**（`docs/SPEC.md` §7.7）：只有持久化配对历史里的 `instanceId` 自动接受，陌生设备保持 PENDING 弹窗，显式拒绝即时 `rejected` |
| TT4 | 历史手工测试脚本 | 未明确 → 以 `docs/TEST-PLAN.md` §6 golden path 清单为准 |

## 2. 哈希与错误协议

| 编号 | 内容 |
|---|---|
| **D1** MD5 语义统一 | `X-MD5`（**打包产物**实时哈希）是传输完整性**唯一权威**；废弃 `expectedMd5` 双轨兜底；`AppInfo.md5`（列表拼接摘要）降级为仅版本指纹 / 去重，不参与校验，也绝不写入响应头。头名与算法不变（改 SHA-256 / 改名属 Phase 6，须版本协商）。契约细节见 `docs/SPEC.md` §8，端到端证据见 `docs/TEST-PLAN.md` §4（DL-8 / DL-9） |
| **D2** AppScanner null 指纹 | 接受现状：`md5 = HashUtils.md5(paths) ?: ""` 且 `isExtractable = true` **是合法态**（可传输但指纹因竞态未算出），不降级；`UpdateManager` 只比 `versionCode` 故不受影响。**重开条件**：真机发现空 `md5` 的正常应用在去重 / 比较中被误判 |
| **D3** 错误协议 | 统一 `LanSyncErrorDto(code, message)` + `LanSyncErrorCode` 枚举；`e.message` 只进 `FileLogger`；HTTP 状态码逐条保持 `docs/SPEC.md` §3.1。错误体从 `text/plain` 改 `application/json` 是已授权的内部改进（客户端从不解析错误体，不破坏互操作） |
| 哈希工具 null 传播 | `HashUtils.md5(paths)` 对空列表或任一不可读路径返回 **null**，不返回空文件的摘要（`d41d8cd9…` 幽灵指纹）。曾固化该缺陷的测试预期同步修订 |
| 空落盘也删文件 | `performDownload` 的 0 字节分支必须 `destination.delete()`，与"缺 X-MD5" / "MD5 不匹配"两分支一致（由 DL-7 锁定） |

## 3. 工程与设计

| 事项 | 裁决要点 |
|---|---|
| 手写 DI | 不引入 Hilt：`LanSyncGraph` 单例组合根 + 可空 late-bind 破 `server ↔ coordinator ↔ pairingStore` 构造环。避免把 KSP/插件风险叠加到协议重写期；Hilt 属 Phase 7 |
| 单 `:app` 模块 | 多模块构建复杂度是二次风险；除非拆分后编译反馈明显变慢才评估 `:core` / `:network` |
| 契约接口 + 可独立安装的 `lanSyncModule` | `lanSyncModule(delegate, pairingStore)` 可被 `ktor-server-test-host` 单独 install——这是全链路可测的关键接缝，不可弱化 |
| `dynamicColor` 关闭 | 跨设备一致的单一设计系统优先于 Material You（可一行开启） |
| material3 1.1.x 无 `surfaceContainer*` | 自建 `LanSyncContainerColors` + `LocalContainers` 四档兼容层，**不为此升 BOM** |
| 配色 Teal | Primary40 `#00696B`，启动器底色同步；Tertiary 保留琥珀承载"可更新"语义 |
| 缓存骨架接线 | `MainViewModel.autoStart` 在 `hasLocalAppCache()` 分支判断**之前无条件**先 `loadLocalAppCache()`（顺带避免 TOCTOU）；不为两行接线新建 Android 测试栈。**已接受的成本**：无缓存时仍读一次 JSON；内容有效但为 `[]` 的缓存仍跳过首扫遮罩 |
| DL-6 不写断言 | 该分支不可覆盖 → 保留防御分支、不钉成契约（论证只在 `docs/TEST-PLAN.md` §4 维护） |
| `POST_NOTIFICATIONS` 请求挂在 Activity 创建点 | FGS 由 `MainViewModel.init → autoStart()` 自启，没有"用户点启动同步"的时机可挂；用户拒绝即容忍（不重试、不阻断，仅通知不可见）。真机行为待验证 |
| 门禁用零依赖单测 | 不引入 Konsist（离线缓存无该库、代理不可靠）；分层门禁写成跑在 `testDebugUnitTest` 里的 JVM 单测，比外挂 lint 更难绕过 |
| 不做 `build.ps1` 封装 | 命令与前提已完整写在 `docs/BUILD.md`，脚本化收益小 |
| `main` 是唯一开发线 | 默认分支必须已是重构后代码，避免任何人 clone 拿到重构前的上帝类（否决"`main` 只作历史基线"） |
| 历史分支保留 | 快进合回后保留作历史线，不再在其上开发；远端是否同步 / 删除待用户定 |
| 真机验收暂缓 | 用户明确跳过该步；⚠️ 它是 Phase 6 前置，启动 Phase 6 前须重新评估缺口 |
| 工具链升级独立 | Kotlin 2.x / Compose BOM / Ktor / AGP 升级走单独分支，**不与协议或架构改动叠加**，留 Phase 7 |
| 类名与间接层不做"清理式"改名 | 命名的历史理由与抽象层的作用只在 `docs/ARCHITECTURE.md` §13.5 维护。结论：不要"顺手改名"或删除 `ConnectionTransport` / `LanSyncClientTransport`——改名会打断按章节号引用的注释锚点与测试名，无收益 |
