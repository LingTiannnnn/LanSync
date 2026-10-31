# LanSync UI 设计债审查

> 本文件是一次审查的输入清单，不是现状事实源：任一条目是否仍然成立，以代码与 `docs/` 主题文档为准。

审查方法：Design Debt Review（design-review 插件）工作流，逐文件人工扫描。
插件自带脚本 `audit-design-debt.mjs` 只匹配 hex / rgb() / Tailwind arbitrary / `style={{}}` / `px` 这类 CSS-JS 模式，对本仓库的 Kotlin + Compose 无效；仓库也没有 `DESIGN.md` 与 `design.qa.yaml`，因此没有机器裁决，全部结论来自代码直读 + 令牌引用计数。

覆盖范围（3785 行）：`app/src/main/java/com/lansync/app/ui/components/` 12 个文件、`ui/theme/` 5 个文件、`MainActivity.kt`。
判定的"应然"来自仓库自己的契约：`docs/CONVENTIONS.md` §3、`docs/ARCHITECTURE.md` §13.4、`ui/theme/` 五个令牌文件的注释声明。

---

## 摘要

| 指标 | 数值 |
|---|---|
| 非 theme 文件的硬编码色值 `Color(0x..)` | 0（这条铁律守得很干净） |
| 非 theme 文件的裸 `.dp` 字面量 | 0（仅 2 处在 `@Preview`，按约定豁免） |
| 组件内内联 `fontSize` / `letterSpacing` | 0 |
| 内联 `.copy(alpha = …)` 淡化 | 8 处 / 5 个不同值（0.15、0.3、0.45、0.5、0.7） |
| 内联 `fontWeight =` 覆盖字阶 | 13 处 |
| 取自间距令牌的 elevation / tonalElevation | 11 处 / elevation 令牌 0 个 |
| 动效时长、位移阈值字面量 | 9+ 处 / motion 令牌 0 个 |
| `LanSyncSpacing` 53 字段中零引用 | 14 个（26%） |
| `LanSyncMetrics` 15 字段中零引用 | 8 个（53%） |
| 两张令牌表重复条目 | 10 对同值 + 5 个 radius 概念重叠 |
| 触控高度写法冲突 | 4 处固定 `height(36dp)` vs 7 处 `heightIn(min = 48dp)` |
| 同一语义的重复组件 | 应用行 ×3、空态 ×4、多选工具条 ×3、反馈条 ×3、按钮 ×4 套 |
| 对比度实测不达标（亮色） | 状态 chip 3.3:1、`outline` 作正文 3.8:1（AA 要求 4.5:1） |
| 悬空文档引用 | 4 处注释指向仓库不存在的 `COMPONENTS.md` / `SCREENS` |

最高风险文件：`CommonComponents.kt`（原语与例外混装）、`AppListScreen.kt`（三套行模板的发源处）、`DeviceListScreen.kt`（对比度与状态色）、`DownloadProgressDialog.kt`（动效、px 阈值、自绘 Snackbar）、`ui/theme/Spacing.kt` 与 `ui/theme/Shape.kt`（令牌双源）。

---

## 阻断项（blocker）

### B1 · 底部反馈层可以互相覆盖

证据：`MainActivity.kt:495-505`（`installStatus` → `BottomCenter` Box）、`MainActivity.kt:530-539`（`operationMessage` → 另一个 `BottomCenter` Box）；两个字段在 `UiState` 中彼此独立（`MainViewModel.kt:40`、`MainViewModel.kt:47`）。

观察：两个 `Box(Modifier.fillMaxSize(), contentAlignment = BottomCenter)` 叠在同一个父 `Box` 里，中间没有共享的 `SnackbarHost`、也没有排布关系。`operationMessage` 有自动清空窗口（`MainViewModel.kt:166/179/239`），`installStatus` 由 10s 计时独立关闭（`DownloadProgressDialog.kt:147-156`），两者共存是正常时序，不是极端情况。

风险：安装反馈与操作提示同时出现时，后组合的一个压在前一个上面，用户丢失其中一条信息；这也是"同步/安装"主链路上唯一的状态说明。

建议：把覆盖层改为单一自顶向下的反馈栈（一个 `Column` 容器 + `FeedbackBanner` 队列），或引入 M3 `SnackbarHost` 并让 `UiState` 只暴露一个 `transientMessage` 队列字段。

预防：`LanSyncOverlays` 里对同一 `contentAlignment` 只允许一个宿主容器；`@Preview` 补一张"两条反馈同时存在"的组合图。

验证：真机触发 `停止服务`（写 `operationMessage`）同时拉起安装（写 `InstallStatus.Installing`），目视两条横幅是否叠字。

### B2 · 状态色被直接当正文色用，亮色主题掉出 AA

证据：
- `DeviceListScreen.kt:369-374` → `StatusChip(containerColor = statusColor.copy(alpha = 0.15f), contentColor = statusColor)`，`statusColor` 取 `outline / error / secondary / tertiary`（`DeviceListScreen.kt:288-296`）。
- `AppListScreen.kt:250-253`、`RemoteAppListScreen.kt:328-332`、`SyncScreen.kt:327-338`、`InitialScanOverlay.kt:78-82` 用 `colorScheme.outline` 作 11–12sp 文本色。
- 文本样式是 `labelSmall`（11sp，`Typography.kt:80-86`）。

按 WCAG 2.1 相对亮度公式对仓库现有色值计算（亮色主题，`containers.low = #EAEEED`）：

| 场景 | 前景 | 有效背景 | 对比度 | AA(4.5:1) |
|---|---|---|---|---|
| DISCOVERED 状态 chip | `OutlineLight #6F7977` | 15% outline 淡化 | 3.34:1 | 不达标 |
| ERROR 状态 chip | `Red40 #BA1A1A` | 15% error 淡化 | 4.31:1 | 不达标 |
| CONNECTED 状态 chip | `TealSecondary40 #4A635F` | 15% secondary 淡化 | 4.53:1 | 勉强达标 |
| 文件大小（正文） | `OutlineLight #6F7977` | `containers.low` | 3.84:1 | 不达标 |
| 暗色主题同类 chip | `OutlineDark #8A9391` | `SurfaceContainerDark` | 5.13:1 | 达标 |

风险：`outline` / `outlineVariant` 在 M3 里是 3:1 的非文本角色，被降格成正文色后，亮色主题下"设备是否可连接""文件大小""是否全部同步"这些关键状态信息对小字号、低视力用户不可读；暗色反而没问题，问题只在默认亮色路径上，真机目视很容易漏。

建议：正文/小字号一律改 `onSurfaceVariant`（这是 M3 给次要文本的角色，仓库已经定义 `OnSurfaceVariantLight #3F4948`）；状态 chip 不要靠 `color.copy(alpha)` 造底，改为一组"状态 → (container, onContainer)"配对表（`CONNECTED → secondaryContainer/onSecondaryContainer`，`ERROR → errorContainer/onErrorContainer`，`DISCOVERED → surfaceVariant/onSurfaceVariant`，`CONNECTING → tertiaryContainer/onTertiaryContainer`），这些色对在 M3 生成器里已保证对比度。

验证：上述配对逐条过 Accessibility Scanner；`docs/ROADMAP.md` 的"暗色对比度实测"项扩成亮暗双主题。

---

## 严重项（major）

### M1 · 令牌表有两个来源，且四分之一是死字段

证据：`ui/theme/Spacing.kt:18-81` 与 `ui/theme/Shape.kt:22-37` 平行定义同值条目 10 对：

| Spacing.kt | Shape.kt(LanSyncMetrics) | 值 | 实际引用 |
|---|---|---|---|
| `minTouch` | `minTouchTarget` | 48dp | sp 0 次 / metrics 7 次 |
| `listItemMin` | `listItemMin` | 72dp | sp 0 / metrics 3 |
| `listItemTall` | `listItemTall` | 88dp | 0 / 0 |
| `deviceCardMin` | `deviceCardMin` | 84dp | 0 / 1 |
| `navIndicatorW/H` | `navIndicatorWidth/Height` | 64/32dp | 0 / 0 |
| `filterChipH` | `filterChipHeight` | 36dp | 0 / 0 |
| `searchH` | `searchBarHeight` | 48dp | 0 / 1 |
| `appIcon` | `appIcon` | 44dp | 0 / 0 |
| `deviceIcon` | `deviceIcon` | 48dp | 0 / 2 |

另外 `LanSyncSpacing` 的 `space20 / space28 / space40 / space56 / space64` 五个栅格位零引用；`LanSyncMetrics` 的 `progressBar / statusBarCompat / gestureBarH` 零引用。`docs/ROADMAP.md:46` 的死符号清单只登记了 `space20` 和 `searchH` 两个，登记面已经落后于实际。

同值不同入口还造成写法漂移：`DeviceListScreen.kt:311` 用 `sp.space48` 撑图标盒，`IdentityStrip.kt:52` 与 `IncomingConnectionDialog.kt:81` 用 `LanSyncMetrics.deviceIcon` 撑同一个 48dp；`AppIcon.kt:44` 的默认值直接读编译期常量 `DefaultSpacing.appIconXl`，绕过了 `LocalSpacing`，主题化链路在默认参数上断了一次。

风险：新人无法判断哪张表是权威，令牌表里 1/4 是死字段，实际取值靠"翻别人怎么写的"，这是漂移的源头，也正是 `docs/CONVENTIONS.md §3` 想防的事。

建议：
1. 两表合一（推荐保留 `LanSyncSpacing`，把 `LanSyncMetrics` 内容并入后删对象），组件侧统一走 `LanSyncTheme.spacing.*`。
2. 清掉零引用字段，而不是留着"以后可能用"。
3. `AppIcon` 默认参数改为 `size: Dp? = null` + 内部 `size ?: sp.appIconXl`，让默认值也过 CompositionLocal。
4. radius 概念只留一条路：`MaterialTheme.shapes.*` 或 `spacing.radiusXs…`，现在两套同值共存（`Shape.kt:13-19` vs `Spacing.kt:58-62`）。

预防：JVM 单测扫 `ui/` 全量，对 `LanSyncSpacing` / `LanSyncMetrics` 的每个字段做一次引用计数，零引用即失败。这是 `DocFactsTest` 已经在用的形态，成本很低。

### M2 · 设计系统没有 elevation 与 motion 两套令牌

证据：阴影/色调一律借间距令牌 —— `AppListScreen.kt:179/281`、`RemoteAppListScreen.kt:266`、`SyncScreen.kt:307` 用 `sp.hairline`；`DeviceListScreen.kt:305` 用 `sp.space4`；`CommonComponents.kt:296`（`tonalElevation`）、`MainActivity.kt:555`、`DownloadProgressDialog.kt:198/222/246` 用 `sp.space6`；`InitialScanOverlay.kt:34` 用 `sp.space8`。
动效完全没有令牌：`MainActivity.kt:265` `tween(700)`、`DownloadProgressDialog.kt:151/160/165/180/265` `tween(300)` / `fadeOut(tween(200))`、`DownloadProgressDialog.kt:41` `delay(5000L)`、`DownloadProgressDialog.kt:148` `delay(10_000L)`。

风险：间距表一改（比如 `space6` 调成 `space4`），卡片阴影和横幅高度感跟着变；动效时长散落 6 个文件、4 种取值，无法统一响应"减弱动效"，也让 `docs/ROADMAP.md:5` 里的 reduce-motion 验收没有落点。

建议：补两个文件 ——
- `ui/theme/Elevation.kt`：`raised(1dp) / card(2dp) / banner(6dp) / overlay(8dp)` 语义化四档，替换 11 处借用。
- `ui/theme/Motion.kt`：`durationFast/Standard/Slow`（如 120/250/400ms）、`easing`、`installHintAutoDismiss`、`feedbackAutoDismiss` 常量，并加一个 `@Composable fun reduceMotion(): Boolean`（读 `Settings.Global.ANIMATOR_DURATION_SCALE`），动画处统一 `if (reduceMotion()) 0 else motion.standard`。

### M3 · 三套"应用行"、四套空态、三套多选工具条、三套反馈条

证据（重复组件模式，`duplicate-component-pattern`）：

| 概念 | 实现处 | 差异 |
|---|---|---|
| 应用列表行 | `AppListScreen.kt:167-259`（AppItem）、`RemoteAppListScreen.kt:246-376`（RemoteAppItem）、`AppListScreen.kt:261-403`（UpdateItem） | 结构同：Card + heightIn(72) + containers.low/highest + AppIcon(appIconLg) + 名称行 + 版本/大小说明行 |
| 不可提取徽章 | `AppListScreen.kt:204-217`、`RemoteAppListScreen.kt:297-310`、`FileListScreen.kt:227-240` | 逐行复制，含 `errorContainer.copy(alpha = 0.5f)` |
| split 徽章 | `AppListScreen.kt:218-231`（`radiusXs`）、`FileListScreen.kt:227-240`（`radiusXs`）、`AppListScreen.kt:315-336`（`radiusSm` + 图标） | 同一语义两种圆角 |
| 空态 | `CommonComponents.kt:120-174`（EmptyState）、`AppListScreen.kt:405-415`（EmptyStateCard 兼容壳）、`AppListScreen.kt:74-100`（自绘 Icon+Text）、`SyncScreen.kt:126-148`（自绘"已全同步"Card） | 4 套视觉、3 种字号 |
| 多选工具条 | `CommonComponents.kt:329-382`（SectionHeader 内嵌全选按钮）、`FileListScreen.kt:45-96`（自绘 primaryContainer Surface + 3 个 TextButton）、`CommonComponents.kt:285-327`（BatchBar）+ `MainActivity.kt:167-194`（Contextual TopBar） | "全选/取消"在三处各写一遍 |
| 反馈条 | `MainActivity.kt:547-563`（inverseSurface + `shapes.medium`）、`DownloadProgressDialog.kt:136-278`（自绘 3 个 Surface + `radiusXs`）、`FileListScreen.kt:145-171`（AlertDialog） | 同一"短反馈"两种圆角两种色角色，且都没用 M3 `Snackbar` |

建议（合并方向，不改行为）：
- 一个 `LanSyncListItem`：`leading`（AppIcon/设备图标）、`title`、`supporting`（版本/大小/来源）、`badges`（列表）、`trailing`（按钮槽）、`selected`。三个行组件变它的调用点。
- 一个 `Badge(kind)`：`kind ∈ {NotExtractable, Split, Updatable, Online}`，圆角与色对固定，替换所有手搓 `Surface + padding(space4, hairline)`。
- 空态只留 `EmptyState`，加 `variant = Info / Success`（Success 承接"应用版本已同步"），删掉 `EmptyStateCard` 壳（仅 `SyncScreen.kt:206` 一处调用）。
- 反馈只留 `FeedbackBanner(tonal = Primary/Tertiary/Error/Inverse)`，加 `Modifier.semantics { liveRegion = politeness }` 让 TalkBack 能播报。

验证：合并后逐 tab 对照截图（亮/暗 × 3 种字号）。

### M4 · 触控高度有两种互相矛盾的写法

证据：`AppListScreen.kt:387`、`RemoteAppListScreen.kt:360`、`FileListScreen.kt:274/286` 用 `Modifier.height(sp.buttonHeight)`（固定 36dp）；而 `CommonComponents.kt:111/168/276/317/372`、`IncomingConnectionDialog.kt:130/138` 用 `Modifier.heightIn(min = LanSyncMetrics.minTouchTarget)`（48dp 下限）。`docs/CONVENTIONS.md:31` 写的是 MUST ≥48dp。
`SyncScreen.kt:282-295`（ConnectedDeviceChips）直接用裸 `FilterChip`，而同一文件 `SyncScreen.kt:254/260` 用的是带 48dp 下限的 `LanSyncFilterChip` —— 共享原语在自己的文件里被绕过，chip 视觉高度掉回默认约 32dp，选中色也丢了集中定义（`CommonComponents.kt:112-116`）。

风险：列表行右侧主操作按钮（安装 / 拉取 / 保存）是最高频的点击点，却恰好是全系统唯一被压到 36dp 的按钮；同一屏内两种 chip 并存，选中态观感不一致。

建议：
- 删掉 `LanSyncSpacing.buttonHeight`（36dp）作为 `height()` 用法，改为 `Modifier.heightIn(min = spacing.minTouch)`；若确实想要矮的视觉按钮，就在共享按钮组件里把"视觉高度 36 + 触摸扩展 48"包起来（`Modifier.minimumInteractiveComponentSize()`），不要让调用点自己决定。
- `ConnectedDeviceChips` 换 `LanSyncFilterChip`；`FilterChip` 在 `ui/components/` 里只允许出现在 `CommonComponents.kt`。
- 列表行整行应可点：`AppItem`（`AppListScreen.kt:170-181`）是 Card 但没有 `onClick`，`UpdateItem`/`RemoteAppItem` 的勾选只能点 48dp 的 Checkbox，整行没有 `selectable` —— 建议 `Card(onClick)` / `Modifier.selectable` + `semantics { contentDescription = appName }`。

说明：36dp 是否真的让触摸区小于 48dp 取决于 M3 1.1（compose-bom 2023.10.01）`Button` 内部是否做最小触摸尺寸扩展，仓库里没有 Material3 源码可查，这一点未经实机验证；但"同一系统内两种相反的写法"本身已经成立。

预防：JVM 单测断言 `ui/components/**` 中 `Modifier.height(` 后跟 `sp.buttonHeight` 出现次数为 0，`FilterChip(` 只出现在 `CommonComponents.kt`。

### M5 · 选中态容器色有两套语义

证据：`AppListScreen.kt:273-280`、`RemoteAppListScreen.kt:258-265` 选中用 `containers.highest`；`FileListScreen.kt:188-190` 选中用 `colorScheme.primaryContainer`；`SyncScreen.kt:129` 又用 `colorScheme.surfaceVariant` 作卡片底（其余卡片用 `containers.low`）。`InitialScanOverlay.kt:32` 用 `surface`，`DownloadProgressDialog.kt:90`、`SaveStatusDialog.kt:144` 用 `surfaceVariant` 作进度条轨道。

风险：同一个"这一项被选中了"在三个 tab 里分别是"更深的灰"和"青绿底"，用户会以为是两种不同的机制。更关键的是 `docs/BUILD.md` §3 已经写明 material3 1.1.x 没有 `surfaceContainer*` 角色，所以仓库专门自建了 `LanSyncContainerColors` + `LocalContainers` 四档 —— 组件里再手写 `surfaceVariant` 等于绕开唯一可用的容器阶梯，等于在同一系统里并存两条容器链路，升 BOM 收敛兼容层时（`docs/ROADMAP.md:22`）这两拨引用得分开处理。

建议：现在就统一到 `LanSyncTheme.containers`（selected = `highest`，卡片底 = `low`，进度条轨道 = `highest`，遮罩内卡片 = `default`），把 `surfaceVariant` 在组件里的使用清零，只留在 `Theme.kt:52/84` 的装配处，并在收敛兼容层时一并替换。

### M6 · 刷新进行中的状态在 UI 上消失

证据：`RemoteAppListScreen.kt:232-242` → `actionLabel = if (isRefreshing) status_refreshing_short else cd_refresh`，同时 `onAction = if (isRefreshing) null else onRefresh`。而 `SummaryCard`（`CommonComponents.kt:273-280`）只有在 `actionLabel != null && onAction != null` 时才渲染按钮。

观察：`isRefreshing = true` 时传入的"刷新中…"文案随 `onAction = null` 一起被丢掉，那个位置直接空出来；同时 `stringResource(R.string.cd_refresh)` 把一条 contentDescription 语义的串（`strings.xml:41` `<string name="cd_refresh">刷新</string>`）当可见按钮标签复用，`cd_` 前缀的串开始出现在正文里。

风险：远程 tab 唯一的"正在拉取列表"反馈不可见，用户点刷新后以为没反应；文案资源角色开始互相渗漏，之后想改无障碍描述会连带改掉可见文案。

建议：`SummaryCard` 增加 `busy: Boolean` 参数（按钮位换成 18dp `CircularProgressIndicator` + 保持宽度稳定，避免布局跳动），并把可见按钮文案与无障碍描述拆成两条串（新增 `action_refresh`）。

### M7 · 面向用户的中文串在 data 层拼接，绕过了零硬编码门禁

证据：`docs/CONVENTIONS.md:26` 的审计范围是 `ui/` 非 theme + `MainActivity`，而 UI 实际渲染的文案有一部分来自 `data/`：
- `data/transfer/LanSyncClient.kt:130` `"连接超时（${timeoutMs / 1000}秒内未收到响应）"` → 进 `DeviceCard` 的 `statusText`（`DeviceListScreen.kt:280-283`）。
- `data/connection/DefaultConnectionCoordinator.kt:235/278/286/296` `"对方拒绝连接"`、`"连接不稳定... ($fc/$max)"`、`"正在尝试重新连接... ($fc/$max)"`、`"连接超时，设备已离线"` → 进 `connectionError` 卡片（`DeviceListScreen.kt:65-98`）。
- `data/transfer/DownloadInstallController.kt:90-91` `"文件不存在"` → 安装失败横幅。
- `ui/components/AppListScreen.kt:341-350` 在组件里直接拼 `"${local} → ${remote}"`，而仓库已经有 `strings.xml:119` `version_arrow = " → "` 并在 `SyncScreen.kt:335` 正确使用。

风险：这条链路既不能本地化，也无法统一措辞与省略号/标点风格，还让 `docs/CONVENTIONS.md §3` 的门禁变成"看起来在守、实际守不住"。`"连接不稳定... ($fc/$max)"` 里的 `...` 与仓库其余 `…` 混用（`strings.xml:19` `扫描中…`）。

建议：领域层只出错误码/枚举（`docs/ARCHITECTURE.md §7` 已经有 `LanSyncError` 与错误码协议），文案映射放在 ViewModel/一个 `ui/string/ErrorText.kt` 里用 `stringResource` 完成；组件内拼接一律走带占位符的串资源（`%1$s → %2$s`）。

预防：门禁扩到"data 层不得出现含汉字的字符串字面量"（白名单：日志 TAG、Technical 格式如 `FileListScreen.kt:24` 的 `TIME_PATTERN`）。

### M8 · 列表槽宽与数字格式跨 tab 不一致

证据：`AppListScreen.kt:104`、`RemoteAppListScreen.kt:196`、`DeviceListScreen.kt:116-121` 用 `contentPadding(horizontal = space16)`；`FileListScreen.kt:121-124` 的 `LazyColumn` 没有水平 `contentPadding`，改由每个 item 自己 `Card(...).padding(horizontal = sp.space12)`（`FileListScreen.kt:183-186`）。
数字格式：`AppListScreen.kt:248`、`RemoteAppListScreen.kt:327`、`AppListScreen.kt:371-373` 用整数 MB（`fileSize / (1024*1024)` → `size_mb`）；`FileListScreen.kt:298-305` 用 `formatFileSize` 三档（B/KB/MB 带小数 → `size_mb_decimal`）。
文件行主标题用包名（`FileListScreen.kt:219-226`），其余三个列表主标题用应用名。

风险：12dp 与 16dp 两种左边距在切 tab 时会看到卡片对齐跳变；同一个"体积"概念有两种精度（12 MB vs 12.35 MB）；文件 tab 把技术标识当主标题，用户要先认包名。

建议：抽 `LanSyncListScaffold`（统一 gutter + spacing + 底部 inset），体积格式化收成一个 `formatSize(bytes)` 供四处调用，文件行主标题改为应用名（`packageName` 降为 supporting，取不到时才回落包名）。

---

## 次要项（minor）

1. **`typography-drift` · 13 处内联 `fontWeight`。** 其中 `AppListScreen.kt:199`、`RemoteAppListScreen.kt:292`、`SyncScreen.kt:321` 对 `titleSmall` 覆盖 `Medium`，而 `Typography.kt:38-44` 的 `titleSmall` 本身就是 `Medium`（无效但误导）；`DeviceListScreen.kt:341`、`SyncScreen.kt:76` 把 `titleMedium` 从 `SemiBold` 抬到 `Bold`，属于绕过字阶。建议：字阶里补 `*Bold` 语义样式或 `listItemTitle`/`sectionTitle`，组件只引用样式名。
2. **`magic-number` · 用 px 做手势阈值。** `DownloadProgressDialog.kt:167` `abs(offsetX.value) > 200f`、`:187` `coerceIn(-500f, 500f)`、`:151` `animateTo(1000f)` —— `offset { IntOffset }` 与 `dragAmount` 是 px，阈值在不同 density 上表现不同（mdpi 与 3.5x 屏的"划走"手感差 3 倍多）。建议 `with(LocalDensity.current) { 200.dp.toPx() }`，并把"划走距离"做成 `motion` 常量。
3. **`non-token-radius` · 同语义不同圆角。** split 徽章在 `AppListScreen.kt:317` 用 `radiusSm`(12)、在 `AppListScreen.kt:222`/`RemoteAppListScreen.kt:301`/`FileListScreen.kt:231` 用 `radiusXs`(8)；反馈条用 `shapes.medium`(16) 与 `radiusXs`(8) 各一套；`FileListScreen.kt:187` 显式 `RoundedCornerShape(sp.radiusMd)` 与 Card 默认 `shapes.medium` 同值但换了入口。建议：圆角只从 `MaterialTheme.shapes` 取，删掉 `LanSyncSpacing.radiusXs…Xl` 五个重复入口。
4. **`info` · 图标尺寸低于可读下限。** `LanSyncSpacing.iconXxs = 11dp`、`iconXs = 13dp` 用于 `UpdateItem` 的 `NewReleases`（`AppListScreen.kt:327`）、来源设备图标（`AppListScreen.kt:360`、`RemoteAppListScreen.kt:344`）。Material 建议图标 ≥16dp，11dp 细节糊掉。建议：信息性图标下限设 14dp，装饰性可继续小；或统一 `iconSm = 16dp`。
5. **`info` · `sp.none` 被当"显式无内边距"使用。** `IncomingConnectionDialog.kt:80-83` 的 `.padding(sp.none)` 是无操作；`InitialScanOverlay.kt:72` `fillMaxWidth(0.7f)` 的 0.7 是未登记比例。建议删除无操作调用，比例进 `Spacing` 或写注释说明依据。
6. **`undocumented-exception` · Monospace 无入口。** `CommonComponents.kt:227`（mesh 统计）、`IdentityStrip.kt:87`（端口行）、`IncomingConnectionDialog.kt:104`（IP:端口）三处内联 `FontFamily.Monospace`，`Typography.kt` 里没有等宽样式。建议补 `LanSyncTypography.monoSmall/monoBody`，让"技术数值用等宽"成为规范而非散落手法。
7. **`mixed-library-style` · 用了 BOM 升级后会告警的 API。** `compose-bom 2023.10.01`（`app/build.gradle.kts:87`）下 `LinearProgressIndicator(progress = Float)`（`DownloadProgressDialog.kt:86`）与 `surfaceVariant`（3 处）在 M3 1.2 起被弃用/语义变更。建议提前改成 `progress = { fraction }` lambda 与容器角色，避免 Phase 7 升级时和设计债一起爆。
8. **悬空引用与失效注释。** `CommonComponents.kt:33/429`、`IdentityStrip.kt:22`、`IncomingConnectionDialog.kt:22` 指向仓库不存在的 `COMPONENTS.md` / `SCREENS`（`docs/` 只有 9 个主题文档）。`DeviceListScreen.kt:115` 说"Scaffold paddingValues 已含 NavigationBar"，但 `MainActivity.kt:70-71` 明确改成了 Column 三段、没有 Scaffold；`IdentityStrip.kt:22-23` 说"贴 statusBars"同样与 `MainActivity` 的实现不一致。`docs/ARCHITECTURE.md:451` 提到的 `CategoryTabs`/`UpdateItem` 签名稳定，但 `CategoryTabs` 是 `internal`（`AppListScreen.kt:120`）。建议：注释只引 `docs/SPEC.md` / `docs/ARCHITECTURE.md` 的真实章节号，或者删掉指针。
9. **`@Preview` 覆盖极低。** 12 个组件文件里只有 `CommonComponents.kt:440-478` 有 3 组预览。建议至少给三个行组件、`EmptyState`、`FeedbackBanner`、`IdentityStrip`、`IncomingConnectionSheet` 补 light/dark + `fontScale = 1.4` 预览，作为升 BOM 时的目视基线。

---

## 债务与方向（debt / info）

1. **状态词汇不统一（loading / empty / success / error）。** 载入表现有三种：全屏 scrim 遮罩（`InitialScanOverlay.kt:23-26`，`scrim.copy(alpha = 0.45f)`，且不可取消）、居中 spinner + 文案（`MainActivity.kt:375-389`）、顶栏小 spinner（`MainActivity.kt:203-215`）。遮罩没有超时与取消出口，扫描卡住时用户被困。建议：统一一个 `BlockingProgress(title, body, cancelable)`，遮罩透明度进令牌（M3 scrim 是 0.32），并给首扫一个"跳过/重试"。
2. **禁用按钮写成 `onClick = {}`。** `DeviceCard`（`DeviceListScreen.kt:396-403`）与安装中态（`DownloadProgressDialog.kt:113-121`）各写了一个 `OutlinedButton(onClick = {}, enabled = false)` + 内联 spinner。建议：共享 `InProgressButton(label)`，同时保证 spinner 换 label 时按钮宽度不跳。
3. **`installClicked` 用 5 秒计时猜测安装结果**（`DownloadProgressDialog.kt:37-44`）。安装是外部 Intent 行为，UI 里回不回来不知道。建议：完成态由 `InstallStatus` 驱动（`MainViewModel.kt:140-148` 已经在流式消费 `installStatus`），不要在组件里放计时业务态。
4. **无障碍播报缺失。** 全仓 `ui/` 没有 `semantics(`/`liveRegion`（仅 M3 组件自带）。自绘的两条横幅 + 倒计时 chip（`IncomingConnectionDialog.kt:107-120`，每秒 `remainingSeconds` 变化）都不播报；`SectionHeader` 的计数 chip 只念数字（`CommonComponents.kt:362-366` `text = count.toString()`），TalkBack 只读"3"。建议：横幅加 `liveRegion`；计数 chip 用 `semantics { contentDescription = "$title $count 项" }`；Checkbox 加行名描述。
5. **横向 insets 与 IME 未处理。** 三段 Column 布局（`MainActivity.kt:99-142`）没有 `displayCutout`/`landscape` 水平 inset，横屏刘海机上侧栏与底栏文本可能被裁；`SearchBar`（`CommonComponents.kt:385-427`）无 `imePadding()`，键盘弹起时列表末尾内容与底栏关系由运气决定。建议：底栏与 TopBar 加 `WindowInsets.safeDrawing` 水平分量，内容容器加 `imePadding()`。`docs/ROADMAP.md:5` 已把横屏列为真机项，这里是它的具体落点。
6. **Tab 切换无过渡。** `LanSyncTabContent`（`MainActivity.kt:332-367`）直接 `when` 切换，无 `AnimatedContent`/`Crossfade`，切 tab 是硬跳。属于设计系统一致性（其余都有动效）与"减弱动效"开关的一部分。
7. **同一文案跨语境复用。** `SyncScreen.kt:231-237`（`EmptySyncState`）复用远程 tab 的 `remote_empty_title` / `remote_empty_hint`，同步页空态说的是"尚未连接任何设备"的远程话术；`device_online_badge`（"在线"）在 `CommonComponents.kt:230-234`（primary 实心）与 `DeviceListScreen.kt:359-365`（secondaryContainer 淡底）是同文不同色。建议：文案按屏拆分，同语义徽章走同一个 `Badge(kind)`（见 M3）。

---

## 分批整改建议（按"可见影响 × 扩散度 × 可修性"排序）

**第 1 批 · 修用户能直接看错/看不到的（1 个改动批次内可完成）**
- B1 反馈层单宿主；B2 状态色改 M3 容器配对 + 正文一律 `onSurfaceVariant`
- M4 按钮/chip 触摸下限统一，`ConnectedDeviceChips` 回 `LanSyncFilterChip`
- M6 刷新中状态可见化；M8 文件 tab 主标题改应用名 + gutter 统一
- 门禁：`Modifier.height(sp.buttonHeight)` 计数为 0 的 JVM 单测

**第 2 批 · 收令牌，防止漂移继续长**
- M1 `LanSyncSpacing` / `LanSyncMetrics` 合并 + 死字段清理 + radius 单入口
- M2 新增 `Elevation.kt` / `Motion.kt`，替换 11 处借用与 9 处动效字面量；接 reduce-motion
- M5 组件内 `surfaceVariant` 清零；次要项 6 的 Monospace 样式；次要项 7 的弃用 API 预改
- 门禁：令牌引用计数 + 禁 `.copy(alpha =` 的 JVM 单测（豁免 `Theme.kt`/`Color.kt`，并明确 `@Preview` 块豁免规则，回应 `docs/ROADMAP.md:29` 登记的"审计未自动化"）

**第 3 批 · 收组件，删重复**
- M3 `LanSyncListItem` / `Badge` / `EmptyState(variant)` / `FeedbackBanner` / `SelectionToolbar` 五件套落地，删 `EmptyStateCard`、`StatusCard`（`DeviceListScreen.kt:186`，已无调用点）、`IncomingConnectionDialog` 壳（`IncomingConnectionDialog.kt:151`，`MainActivity.kt:520` 只用 `IncomingConnectionSheet`）
- M7 文案下沉到资源/错误码映射，门禁扩到 data 层
- 债务 1–4：统一载入词汇、`InProgressButton`、去 `installClicked` 计时、补 `liveRegion` 语义
- `UI 大文件拆分`（`docs/ROADMAP.md:22`）随这一步做：`AppListScreen.kt`、`DeviceListScreen.kt`、`CommonComponents.kt` 按"原语 / 行组件 / 屏"三层分目录

---

## 可接受例外（建议登记而不是清除）

- `ui/theme/Color.kt`、`ui/theme/Typography.kt` 内的字面量是令牌定义本身，不是债。
- `AppIcon.kt:49` 的 `(size.value * 2).coerceAtLeast(96)` 与 `:82` 的 `size * 0.45f`：位图请求尺寸与占位比例属几何启发式，可保留，但应写成有名字的常量（`iconRenderScale = 0.45f`、`minBitmapRequestPx = 96`），并说明是按 px 而非 dp。
- `InitialScanOverlay.kt:25` 的 `scrim.copy(alpha = 0.45f)`：模态遮罩加深是有意的（首扫不可操作），可保留，但需进 motion/elevation 令牌表并注明与 M3 默认 0.32 的偏差理由。
- `FileListScreen.kt:24` 的 `TIME_PATTERN = "MM-dd HH:mm"`：技术格式，注释已说明不入 `strings.xml`，属正确例外。
- `@Preview` 内的裸 `.dp`（`CommonComponents.kt:452/464`）与中文串（`:448-450/475`）：预览脚手架，按 `docs/CONVENTIONS.md:26` 豁免；自动化门禁必须实现同样的块级豁免，否则会大量误报。

---

## 本次审查未能覆盖的部分

- 未编译、未跑单测、未真机目视，所有结论来自源码静态判读；因此"3.3:1 / 3.8:1"是按 WCAG 公式对令牌色值的计算结果，仍需在真机（含系统字体缩放、暗色、深色图标叠底）复核。
- 未验证 M3 1.1 的 `Button` 是否内部扩展触摸区，故 M4 的"36dp 是否真的违反 48dp"缺少运行时证据。
- 未检查 `res/values` 下的主题/启动器色值与 `Color.kt` 的一致性（`docs/ARCHITECTURE.md:445` 说启动器底色同为 `#00696B`，未对 `res` 做交叉核对）。
- 未审查动效在具体设备上的时长感受，也未验证字体缩放 200% 下的截断（`CategoryTabs`/`SectionHeader` 已有 `maxLines = 1` + Ellipsis，实际是否会截掉关键数字未知）。
