# FEEDBACK · UI Recreate（MIMO/UI-Recreate）

对照 `UI Design/TASKS.md` T6 与最终 Review（`ui-recreate-final-review.md`）。

## 交付清单（代码级）

| 项 | 结果 | 说明 |
|----|------|------|
| 无状态栏/手势条白条 | ✅ 代码级 | 无 `statusBarColor`/`navigationBarColor` 不透明赋值；`enableEdgeToEdge` + NavigationBar `containers.default` + `navigationBars`。真机三键/手势目视仍待设备。 |
| 5 Tab 语境标题正确 | ✅ | 设备 / 本地应用 / 远程应用 / 同步 / 文件；去掉页内大号「LanSync」。 |
| Teal M3 色角色 | ✅ | Primary Teal、Tertiary Amber、背景 Neutral99 / SurfaceDark、`dynamicColor=false`。 |
| 多选 Contextual + BatchBar | ✅ | 同步页 Contextual TopBar（关闭/已选 n/清除）+ `BatchBar` inverseSurface。 |
| 空态有 CTA | ✅（本轮补齐） | 设备「启动同步服务」、文件刷新、远程/同步空态增加「刷新」。 |
| 无 emoji 结构图标 | ✅ | 全部 Material Icons；icon-only 控件均有 `contentDescription`。 |
| 颜色不单独表意 | ✅ | StatusChip/徽章带文案；更新竖条旁有「可更新」文字。 |
| 业务回归 | ⏳ 待设备 | UI 层未改 ViewModel/仓库/网络契约；连接/拉取/安装/保存需真机回归。 |

## 已改设计决策 vs 实现偏差

### 已实现的有意决策
1. **Compose BOM 2023.10.01 不升**：material3 1.1 无 `surfaceContainer*`，用 `LanSyncContainerColors` + `LocalContainers` Compat 四档。
2. **Identity 连色带**：由 T1 延到 T4 的 `IdentityStrip`（primaryContainer 贴 statusBars + HeroStats + Switch）。
3. **配对 UI**：Dialog → `ModalBottomSheet`（顶圆角 28、返回可关、倒计时自动拒绝），与 COMPONENTS §10 一致。
4. **系统栏图标**：仅按 `background.luminance()` 设 `isAppearanceLight*`，不再写窗口色。

### 实现偏差（接受 / 待跟）
| 偏差 | 处置 |
|------|------|
| FilterChip / 行内按钮视觉高 36dp（设计如此） | 接受；补 `minimumInteractiveComponentSize()` 保证触控 ≥48。 |
| 真机白条、Dynamic Type 最大字号、暗色对比度实测 | 待设备侧；代码层已避免不透明系统栏色。 |
| MeshHint 尾部 Chip 恒显示「在线」 | 待打磨（onlineCount=0 时应改文案）。 |
| `LanSyncMetrics` 与 `LanSyncSpacing` 尺寸表部分重复 | 可后续合并，不影响运行时。 |

## 最终 Review 后已修复（本轮）

1. **[Critical] Device 页双 statusBars 留白** — IdentityStrip 已消费 statusBars 时，下方 TopAppBar `contentWindowInsets = WindowInsets(0)`。
2. **[Critical] `rememberSaveable(DeviceInfo)` 恢复崩溃** — 改为保存 `displayKey: String` 再解析设备。
3. **[Major] 安装 Snackbar 关闭后不再显示** — `isVisible`/`offsetX` keyed on `status`。
4. **[Major] 触控目标** — 拉取/安装/保存等 36dp 按钮与 FilterChip 加 `minimumInteractiveComponentSize()`。
5. **[Minor] 设备列表底部双 inset** — 去掉 LazyColumn 内重复 `navigationBars` padding（Scaffold 已含）。
6. **[Minor] 远程/同步空态 CTA** — 增加刷新动作；`EmptyStateCard` 残留 `24.dp` 改 token。

详见 `docs/compose/spec/ui-recreate-final-review.md`。
