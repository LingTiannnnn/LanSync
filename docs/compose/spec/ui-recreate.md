---
feature: ui-recreate
status: delivered
updated: 2026-09-12
branch: MIMO/UI-Recreate
commits: 
---

# LanSync UI Recreate（Theme + Edge-to-Edge 优先）

## Report

**What was built**

- **T0 Theme/Token**：`Color.kt` 替换为交接包 Teal/Amber/青绿 Neutral 色板；亮色背景 `#F6FAF9`、暗色 `#0E1416`；`Theme.kt` 新 Light/Dark scheme，默认 `dynamicColor=false`，`LocalContainers` 提供 surfaceContainer 四档 Compat，并注入 `LanSyncShapes`；`Spacing.kt` 扩展圆角与列表/触控尺寸；新增 `Shape.kt`（`LanSyncShapes` + `LanSyncMetrics`）。已删除 `window.statusBarColor`/`navigationBarColor` 赋值，仅按 luminance 设置系统栏图标亮暗。
- **T1 Edge-to-edge 骨架**：`MainActivity.onCreate` 调用 `enableEdgeToEdge()`；`NavigationBar` 使用 `LanSyncTheme.containers.default` + `WindowInsets.navigationBars`。未改 ViewModel/网络/业务逻辑。
- **T2 导航与 TopBar**：TopBar 语境标题为「设备 / 本地应用 / 远程应用 / 同步 / 文件」，去掉页内大号「LanSync」；同步页 `selectedUpdates` 非空时切换 Contextual TopBar（Close 退出 / 已选 n / 清空，容器 `containers.high`）；5 项 NavigationBar 指示胶囊色 `primaryContainer`；`selectedTab` 使用 `rememberSaveable`。
- **T3 共享组件**：新增 `CommonComponents.kt`（StatusChip / LanSyncFilterChip / EmptyState / MeshHint / SummaryCard / BatchBar / SectionHeader / SearchBar / UpdateAccentBar），含双主题 Preview；DeviceCard 使用 `containers.low` + StatusChip；App/Remote/Update 列表行 `minHeight 72` + `containers.low/highest`；更新项左侧 tertiary 竖条；BatchBar 使用 inverseSurface 规格；设备列表接入 MeshHint；远程/同步空态与 Summary 走共享组件。
- **T4 五屏组装**：新增 `IdentityStrip`（primaryContainer 贴 statusBars + HeroStats + Switch）；设备页 MeshHint + 已连接/发现中分区 + 停止态 CTA「启动同步服务」；同步页可更新/版本差异双模式（FilterChip，兼容 M3 1.1）；文件页 SummaryCard + 空态 CTA；本地/远程沿用 T3 组件与 ViewModel 接线。
- **T5 Overlays**：配对改为 `ModalBottomSheet`（顶圆角 28，返回键可关，倒计时自动拒绝）；下载 Dialog + 进度保留；安装 Snackbar 自动消失/滑动关闭；保存 Dialog；首扫遮罩 scrim + 居中卡片；新增 `operationMessage` 底部短反馈。

**Verification**

- `./gradlew.bat :app:compileDebugKotlin --console=plain --no-configuration-cache` → **BUILD SUCCESSFUL**（约 8m；仅既有 warning）。
- 静态核对：组件层无品牌色字面量；无 `statusBarColor` 赋值；token 与 `UI Design/tokens/*` 一致。
- Review（独立子代理）：Spec / Correctness / Consistency 无 critical；建议 ship T0+T1。详见 `docs/compose/spec/ui-recreate-review-notes.md`。
- **Final review**（独立子代理）：Spec / Correctness / Consistency — 2 critical（Device 页双 statusBars、`rememberSaveable(DeviceInfo)`）、若干 major；已全部代码修复。详见 `ui-recreate-final-review.md`。
- `FEEDBACK.md` 已写入（交付清单 + 设计决策 vs 偏差）。
- 真机三键/手势白条、Dynamic Type、业务回归 **待设备侧**。

**Journey log**

1. BOM 2023.10.01 无 `surfaceContainer*` → 用 `LocalContainers` Compat，不升级 BOM。
2. `enableEdgeToEdge` 与去掉不透明系统栏色必须成对，否则仍会有白条。
3. TASKS.md T1「Identity 连色带」依赖 IdentityStrip，已明确延到 T2/T4。
4. Gradle 配置缓存 + 离线依赖不全导致超时；改 `--no-configuration-cache` 在线编译成功。

## [S1] Problem

现有 LanSync Compose UI 仍使用靛蓝主色，并在 `Theme.kt` 中以不透明 `statusBarColor` / `navigationBarColor` 强制系统栏背景，导致手势区/状态栏白条、与 M3 Scaffold 断层。设计交接包（`E:\S.H.I.T\UI Design`）要求在不动业务逻辑的前提下，将 UI 层对齐 Teal M3 设计系统与 edge-to-edge 骨架。

## [S2] Design

**来源**：`HANDOFF.md` / `TASKS.md` T0–T1 / `tokens/LanSyncColors.kt` / `LanSyncTheme.kt` / `LanSyncShape.kt`。

### T0 · Theme / Token
- Primary 由 Indigo → Teal 色板（`TealPrimary*` / `TealSecondary*`）；Tertiary 保留 Amber 语义但改用交接包色值；Error/Outline/Neutral 同步替换。
- Light background/surface = `#F6FAF9`（`Neutral99`）；Dark background/surface = `#0E1416`（`SurfaceDark`）。
- `dynamicColor` 默认 `false`。
- Compose BOM 2023.10.01（material3 1.1）无 `surfaceContainer*`：以 `LanSyncContainerColors` + `LocalContainers` 提供 low/default/high/highest 四档。
- `LanSyncTheme.spacing` 继续存在；扩展 shape 圆角与 `LanSyncMetrics` 关键尺寸（列表 72/88、底栏指示 64×32、触控 48 等）。
- `MaterialTheme(shapes = LanSyncShapes)` 注入 8/12/16/24/28 圆角。
- 禁止组件内硬编码品牌色；禁止 Theme 再写 `window.statusBarColor` / `navigationBarColor`。
- 只通过 `WindowCompat.getInsetsController` 按 luminance 设置系统栏图标亮暗。

### T1 · Edge-to-edge
- `MainActivity.onCreate` 调用 `enableEdgeToEdge()`。
- `NavigationBar(containerColor = LanSyncTheme.containers.default, windowInsets = WindowInsets.navigationBars)`，手势条与底栏同色、无小白条。
- Scaffold 使用 M3 默认 content insets；TopAppBar 吃 statusBars；列表内容不额外强制白色 status padding。
- 业务逻辑 / ViewModel / 网络层不改。
- **范围说明**：TASKS.md T1「首页 Identity 连色带」依赖 IdentityStrip（T2/T4），本切片仅完成 Activity/Scaffold 骨架。

### 契约
- 颜色唯一入口：`Color.kt` 色值 + `Theme.kt` ColorScheme + `LocalContainers`。
- 间距/尺寸入口：`LanSyncTheme.spacing` / `LanSyncMetrics`。
- 组件通过 `MaterialTheme.colorScheme` 与 `LanSyncTheme.containers` 取色。

## [S3] Out of Scope

- 不改网络、仓库、ViewModel 业务逻辑（除 UI 映射所需字段透传）。
- 不升级 Compose BOM / material3（使用 Compat containers）。
- 不修改 `E:\S.H.I.T\UI Design` 交接包本身（仅追加 FEEDBACK.md）。
- 真机目视验收（白条 / Dynamic Type / 业务回归）不在本分支自动化范围内。

## Tasks

- [x] T0: 替换 Color/Theme/Spacing/Shape token，注入 LocalContainers 与 LanSyncShapes — acceptance: 亮色背景 `#F6FAF9`、暗色 `#0E1416`、主色 Teal；无 statusBarColor 赋值 (covers: S2)
- [x] T1: MainActivity enableEdgeToEdge + NavigationBar 容器色与 navigationBars insets — acceptance: 编译通过；底栏使用 containers.default；无不透明系统栏色写法 (covers: S2; depends: T0)
- [x] T2: 5 Tab 语境标题与 Contextual TopBar — acceptance: 切换标题正确；去掉页内大标题 (covers: S2)
- [x] T3: 共享组件按 COMPONENTS.md — acceptance: 触控 ≥48dp；Material Icons (covers: S2)
- [x] T4: 五屏组装接 ViewModel — acceptance: 功能与旧版等价 (covers: S2)
- [x] T5: Overlays 与反馈 — acceptance: 无泄漏；返回可关 (covers: S2)
- [x] T6: 对照原型打磨 + FEEDBACK — acceptance: 交付清单通过（代码级；真机项待设备）(covers: S2)
