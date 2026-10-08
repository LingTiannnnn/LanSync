# 当前状态（STATUS）

## 1. 阶段进度

| 阶段 | 主题 | 状态 | 验收 |
|---|---|---|---|
| Phase 0 | 文档冻结（SPEC / ARCHITECTURE / TEST-PLAN，字段级 v1.0） | ✅ | T1–T6 / TT1–TT4 决议归档（`docs/DECISIONS.md`） |
| Phase 1 | 传输层骨架（DTO + `lanSyncModule` + `LanSyncClient` + `HashUtils` null 传播） | ✅ | 95 例；构建卫生 4 项 |
| Phase 2 | 发现与连接层（`DeviceDiscovery`/`JmDNSDeviceDiscovery` + `ConnectionCoordinator` + `PairingHistoryStore` + `ConnectionTransport`） | ✅ | 117 例；对齐 SPEC §6/§7 |
| Phase 3 | 扫描/打包/更新推荐（`localapps/*` + `transfer/AppPacker` + `sync/UpdateManager` + `LocalAppRepository`） | ✅ | 141 例；对齐 SPEC §5.3/§8/§3.2 |
| Phase 4 | 接线 + 前台服务 + 删旧码（`LanSyncRepository` 门面 + `LanSyncGraph` + `ForegroundSyncService` + `IconCache` + `UpdateCoordinator` + `DownloadInstallController`） | ✅ | 139 例 + assembleDebug |
| Phase 5 | UI 一次成型（单一 Material3 设计系统 + 底部 5 Tab + 全量覆盖层 + `UiState` 单一出口 + 零硬编码） | ✅ | 139 例；硬编码审计 0 违规 |
| 增量① | UI Recreate（Teal 配色、edge-to-edge、组件族重写、配对改 `ModalBottomSheet`） | ✅ 代码级 | 独立评审 2 critical 已修；**真机视觉验收未做** |
| 增量② | 本机应用缓存骨架接线 | ✅ | 139 例 |
| 增量③ | 补短板批次（分层门禁 / DL-7…DL-10 / `POST_NOTIFICATIONS` 运行时请求 / 死码清理） | ✅ | **147 例** + assembleDebug |
| Phase 6 | 安全加固与协议版本化 | ⏳ 待启动 | **前置：真机互操作验收** |
| Phase 7 | 工具链升级 / UI 拆分 | ⏳ 待启动 | 独立分支，不叠加协议改动 |

范围细节见 `docs/ROADMAP.md`。

## 2. 基线

- **147/147 全绿**：15 个测试文件，0 失败 / 0 错误 / 0 跳过；`assembleDebug` 通过（`app-debug.apk` 约 62MB）。文件 × 例数 × 锁定契约的映射见 `docs/TEST-PLAN.md` §10。
- **DL-6（空 body）经查证不可覆盖**，裁决为"保留防御分支、不写断言"（`docs/TEST-PLAN.md` §4）。

## 3. 分支与远端

- **`main` 是唯一开发线**；新工作一律从 `main` 开分支，合回用 `--ff-only`。
- `origin/main` 已与 `main` 同步，GitHub 默认分支即重构后代码，clone 默认拿到新栈。
- `qoder/UI-Recreate`、`qoder/Quality-Gate` 已纯快进合回 `main`，保留作历史线、不再在其上开发；二者未与远端同步，内容已全在 `main`，是否推/删待用户定。
- 重构前代码（`AppRepository`/`KtorServer`/`AppListClient`/`ConnectionManager`/旧 `JmDNSDiscovery`/旧 `scanner`/`packer`/`update` 及其测试）只存在于 git 历史，基线快照 `d1a71fc`。

## 4. 验收缺口

App 运行时走全新栈，**但 Phase 4/5 的运行时行为与 UI 视觉效果全部待真机验证**：FGS 存活、mDNS 发现、配对/心跳/端口迁移、下载校验、安装拉起、暗色对比度、大字号、三键/手势导航白条、横屏、reduce-motion。这些没有设备就无法自动化；**真机互操作矩阵（新×新 / 新×旧 / 旧×新 / 旧×旧）从未执行**，用户已明确暂缓。

⚠️ 该验收是 Phase 6 的前置：协议版本协商与鉴权会碰互操作红线，无真机兜底不宜动。别把"已交付"当"已验收"。

**下一步候选（待用户定）**：① 真机互操作验收（`docs/TEST-PLAN.md` §6）→ 之后才适合启动 Phase 6；② Phase 6；③ Phase 7。
