# 方向与债务（ROADMAP）

## 1. 阻塞级：真机互操作验收（Phase 6 前置）

逐项清单在 `docs/TEST-PLAN.md` §6。需两台 Android 真机（minSdk 29）同一 Wi-Fi，跑满 新×新 / 新×旧 / 旧×新 / 旧×旧 四组合：FGS 退后台存活与通知可见性、mDNS 发现与自过滤、配对（含 TT3 四场景）、心跳与端口迁移、下载与 split 校验、安装器拉起、SAF 保存、`specialUse` 在 API 34 的实际行为、API 33+ 首次权限弹窗时机；UI 侧三键/手势白条、暗色对比度实测、最大字号、reduce-motion、横屏、业务回归。

## 2. Phase 6：安全加固与协议版本化

**前置 = 第 1 节验收完成。**

- token 鉴权（当前明文 HTTP 无鉴权，任何局域网设备可枚举 appList）。
- **剥离 `sourcePaths`**：本机绝对路径当前原样跨设备暴露，接收方绝不可信任该路径。
- SHA-256 / 下载响应头名迁移。
- **协议版本协商必须新增独立字段**（如 TXT `proto` + deviceinfo `protocolVersion`）：当前无版本字段，`DeviceInfoResponse.version` 是已裁决死字段，**禁止复用**（`docs/DECISIONS.md` T2）。
- 兼容期内旧客户端必须无感工作：靠 `ignoreUnknownKeys` + 恒发 `X-MD5` + 不解析错误体。
- 权限审查：定位权限能否移除、`NEARBY_WIFI_DEVICES` 是否改用。
- `DeviceInfo` 目前没有任何 HTTP 路由（SPEC §2.2 冻结），新增其上线路径必须走版本协商。

## 3. Phase 7：工具链与收尾

- 工具链升级目标逐项列在 `docs/ARCHITECTURE.md` §11（Kotlin / Compose BOM / lifecycle / Ktor / Hilt / R8 keep 规则）；当前版本锁见 `docs/BUILD.md` §3，**版本三者绑死必须整体升**。
- UI 大文件拆分；升 BOM 后收敛 `LocalContainers` 兼容层。
- 独立分支推进，**不与协议或架构改动叠加**（`docs/DECISIONS.md`）。

## 4. 非阻塞债务

- `specialUse` FGS 的 Play 上架说明未补；`QUERY_ALL_PACKAGES` 的上架敏感性未写说明。
- 并发压力测试专项未写：并发投递 `RawDevicesUpdated` + `HeartbeatTick` + `ConnectRequested` 断言最终状态确定。Actor 单协程收敛目前靠**构造保证**而非测试证明。
- UI 零硬编码审计未自动化（`@Preview` 里的裸 `.dp` 属合法使用，机械断言会大量误报，需先约定豁免规则）。
- 分层门禁非 CI 形态，且靠源码文本匹配，可被"不写 import、直接用全限定名 `com.lansync.app.ui.X()`"绕过；要更严可换 AST。
- 文档事实门禁（`architecture/DocFactsTest`）只覆盖已枚举的字面量类别：`BUILD.md` §3 版本锁表与 SDK / `jvmTarget` 行、测试基线数字、`.qoder/agents` 镜像数字、远端同步表述。本机 JDK 构建号属机器级事实，列入白名单不与构建脚本对照；`.qoder/agents` 在版本控制外，仅在文件存在时对照；散文式表述仍靠人工核对。
- `ARCHITECTURE.md` §12 验收标准未达项：门面 ≤150 行（现 164，≤300 硬约束已达）、"CI 静态检查"以单测形态替代、Hilt 未引入、退后台存活待真机、六条红线真机互操作未验。
- 缓存策略：每次启动仍后台全量重扫（有意保留）。
- 包名反推（从落盘文件名反推包名）对含数字段的包名 / 无版本段的文件名天然脆弱，属冻结现状；安装链路走正向构造匹配不依赖它，仅文件页展示受影响。

## 5. 已知 UI 残留

- `CommonComponents.kt` 的 `MeshHint`：`onlineCount = 0` 时尾部 `StatusChip` 仍恒显"在线"（文案错误）。
- `LanSyncMetrics`（`Shape.kt`）与 `LanSyncSpacing`（`Spacing.kt`）尺寸表部分重复（`listItemTall`、`appIcon`、`navIndicator*` 两处各一份）→ 漂移风险，建议合并成单一访问器。
- 3 处未用参数警告沿用旧签名：`FileTabContent.saveTargetFileName`、`StatusCard.isScanningApps`、`SyncScreen.onRefreshDevice`。

## 6. 死符号候选清单

口径：**在 `app/src` 下 `grep -rn` 全仓命中 1 次 = 只有定义 = 死**；命中 >1 的条目清理前必须逐个重新 grep 定性（可能是定义 + 内部使用，或仅测试引用）。

- **仅定义**：`DeviceListScreen.StatusCard`、`IncomingConnectionDialog.IncomingConnectionDialog`（兼容壳，`MainActivity` 只调 `IncomingConnectionSheet`）、`AppIconDiskCache.batchPut`、`LanSyncClient.clearDownloads`、`FileLogger.{getLogFilePath,clearLog,shutdown}`、`LanSyncErrorCode.SERVER_NOT_READY`、`UiState.isLoading`、`LanSyncSpacing.{space20,searchH}`。
- **命中数 >1，待定性**：`IconCache.removeStale`、`LanSyncClient.sendConnectResponse`、`LanSyncRepository.downloadApp`（UI 实际走 `downloadAndInstallApp`）、`InMemoryPairingStore.{removeRequest,clearAll}`、`DeviceInfoResponse.version`（= 已裁决死字段 T2，禁止复用）、`InMemoryPairingHistoryStore`、`UpdateCoordinator.recalculateNow`、`LanSyncClient.downloadLatestApksFile`、`LanSyncSpacing.{space28,space40,space56,space64,minTouch,listItemTall}`、`LanSyncMetrics.{listItemTall,navIndicator*,filterChipHeight,appIcon,progressBar,statusBarCompat,gestureBarH}`。
- 复核命令：`for s in StatusCard batchPut removeStale; do printf "%-32s %s\n" "$s" "$(grep -rn -- "$s" . | wc -l)"; done`

## 7. 未评估的可选方向

Room 持久化"已知设备 + 最近 appList"（当前无跨进程数据库，重启即失）。仅在成为实际痛点后再评估。
