---
feature: local-apps-cache-skeleton
status: delivered
updated: 2026-09-11
branch: qoder/UI-Recreate
commits: 3c7dade..cf55139
---

# 本机应用缓存骨架接线（启动即展示，后台静默刷新）

## Report

**What was built** — 启动路径补上缓存骨架接线：`LanSyncRepository` 增加 `loadLocalAppCache()` 转发到已有的 `LocalAppRepository.loadCacheSkeleton()`；`MainViewModel.autoStart` 在分支判断前无条件调用它，使存在有效 `local_apps_cache.json` 时 `localApps` 在后台全量校验刷新完成前就已非空。无缓存或损坏缓存（读失败自愈删除）仍走 `needsInitialScan` 全屏首扫。`LocalAppRepository` / 扫描器 / UI 未改。

**Verification** — `testDebugUnitTest --offline --rerun-tasks`：PASS，139 tests / 0 failures（`LocalAppRepositoryTest` 5/5，含骨架与损坏自愈）。`assembleDebug --offline`：PASS。独立 Review：T1–T3 均满足，无 critical；非 critical（有效 `[]` 空列表缓存仍跳过遮罩、主程一次 JSON 读）为既有 `hasCache` 语义/已接受成本，未在本次扩大范围。

**Journey log** — 1) 根因不是持久化丢失，而是 `loadCacheSkeleton` 只写在仓库层与单测、未进生产接线。2) 有缓存分支原设计即「骨架 + 后台刷新」，重构 Phase 4 切换时漏了骨架调用。3) 无条件先 load 再 `hasCache` 分支，避免 TOCTOU，并让损坏缓存自然落入首扫。4) 不为两行接线新建 Android 测试栈；接线由 `LocalAppRepositoryTest` + review 覆盖。5) Review 指出 spec T1 笔误「基加」已改为「增加」；空 JSON 数组缓存语义若需与「零应用」对齐，属后续 hardening。

## [S1] Problem

用户每次冷启动 LanSync，本机应用列表看起来被清空并从 0 全量重扫（全屏/局部「正在扫描」）。

根因（已复核）：

- `LocalAppRepository.loadCacheSkeleton()` 已实现且有单测，**生产接线从未调用**（全仓仅定义 + 测试）。
- `MainViewModel.autoStart()` 在 `hasLocalAppCache() == true` 时直接后台 `scanLocalApps()`，扫描完成前 `localApps` 保持 `emptyList()`。
- `MainActivity.LocalTabContent` 在 `isScanningApps && localApps.isEmpty()` 时渲染全屏扫描态，放大「结果已丢失」的观感。
- 缓存文件 `filesDir/local_apps_cache.json` 多半仍在；旧 `AppRepository` 扫描前会先灌入缓存，重构后这条路径漏接了。

设计意图（ARCH §3.3 / LocalAppRepository KDoc / PROGRESS）：**先骨架填充，再后台校验刷新**。实现缺的是接线，不是能力。

## [S2] Design

**目标启动策略（契约不变）：**

1. 进程启动 / ViewModel 初始化时，**先**尝试用缓存骨架填充 `localApps`（`loadCacheSkeleton()`，不触发扫描）。
2. 再后台 `scanLocalApps()` → `scanAndRefresh()` 校验刷新并落盘。
3. 仅当**无有效缓存**（文件不存在、空、或解析失败被自愈删除）时，才走 `needsInitialScan` 全屏遮罩 + 首扫。

**改动面（最小接线）：**

| 层 | 变更 |
|---|---|
| `LocalAppRepository` | **不改**（`loadCacheSkeleton` / `hasCache` / `scanAndRefresh` 契约已正确） |
| `LanSyncRepository` | 新增转发 `fun loadLocalAppCache(): Int = localAppRepository.loadCacheSkeleton()`（与既有 `hasLocalAppCache()` 对称） |
| `MainViewModel.autoStart` | 在分支判断前**无条件**先 `repository.loadLocalAppCache()`；随后用 `hasLocalAppCache()` 决定遮罩 vs 静默后台刷新 |

**`autoStart` 目标伪码：**

```
init → observeRepositoryState() → autoStart()
autoStart:
  loaded = repository.loadLocalAppCache()   // 骨架；无缓存/损坏 → 0 且 localApps 仍空
  if (!repository.hasLocalAppCache()):
    needsInitialScan = true
    scanLocalApps()  // 遮罩下首扫 + 落盘
    needsInitialScan = false
    ForegroundSyncService.start()
  else:
    ForegroundSyncService.start()
    后台 scanLocalApps()  // 此时 localApps 已有骨架，UI 不再空列表
```

**为何无条件 `loadLocalAppCache()` 而不是只在有缓存分支调用：**

- 与 `hasCache()` 读同一文件，幂等、成本可忽略（读一次 JSON）。
- 损坏缓存会在 `readCache()` 自愈删除并返回 0，随后自然落入首扫路径，行为正确。
- 避免「先 hasCache 再 load」的 TOCTOU 与重复分支。

**UI 层：** `MainActivity` **不改**。骨架就位后 `localApps` 非空，`isScanningApps && isEmpty()` 全屏态只在真正无数据首扫时出现，符合预期。

**错误行为：**

- `loadLocalAppCache()` 读失败 → 返回 0，不抛；`scanAndRefresh` 失败 → 保留当前 `localApps`（已有行为）。
- 不引入新的持久化格式；继续 `Json { ignoreUnknownKeys = true }`。

**测试边界：**

- 仓库级回归已有：`LocalAppRepositoryTest.loadCacheSkeleton populates from cache without scanning`（覆盖骨架灌入、不触发扫描、损坏自愈）。
- 本修复的缺口是**接线**。`LanSyncRepository.loadLocalAppCache()` 为纯转发；`MainViewModel` 为 `AndroidViewModel`，无现成 Robolectric/单测基建，**不为接线单独搭 Android 测试栈**（避免超范围）。
- 未新增委托测试：`LanSyncRepository` 协作者过重（含 Android `IconCache`），纯转发无独立可测行为；以 `LocalAppRepositoryTest` + 代码评审/真机启动验收为验收面。
- 验收命令：`testDebugUnitTest`（基线 139）+ `assembleDebug`。

## [S3] Out of Scope

- 不改 `LocalAppRepository` 扫描/缓存 JSON 格式与 `AppScanner`。
- 不改 UI 视觉、Tab 结构、`InitialScanOverlay` 文案与布局。
- 不改「每次启动后台全量校验刷新」策略（REPORT 7.2#8 有意保留，只修「启动即展示」）。
- 不处理图标预加载、连接/同步、前台服务行为。
- 不清理 `.sdk-dl` 误提交大文件、不重构 `MainViewModel`。
- 不引入 Hilt/新 DI 框架，不为 ViewModel 增加测试基建。

## Tasks

- [x] T1: `LanSyncRepository` 增加 `loadLocalAppCache()` 转发到 `LocalAppRepository.loadCacheSkeleton()` — acceptance: 门面可暴露缓存骨架加载并返回条数，与 `hasLocalAppCache()` API 对称 (covers: S2)
- [x] T2: `MainViewModel.autoStart` 在分支前调用骨架加载，使有缓存时启动即有列表 — acceptance: 存在有效 `local_apps_cache.json` 时，后台全量扫描完成前 `localApps` 已非空；无缓存时仍走 `needsInitialScan` 遮罩首扫 (covers: S2; depends: T1)
- [x] T3: 回归与构建验证 — acceptance: `testDebugUnitTest` 全绿（含既有 `LocalAppRepositoryTest` 骨架用例）且 `assembleDebug` 通过；若新增委托测试则一并绿 (covers: S2; depends: T2)
