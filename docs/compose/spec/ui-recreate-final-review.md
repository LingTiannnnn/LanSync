# UI Recreate Final Review (MIMO/UI-Recreate)

Scope: theme + MainActivity + ui/components/* against spec, TASKS T0–T6, COMPONENTS/SCREENS.
Compile already verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL (not re-run).

## 1. Spec compliance (T0–T6)

| Task | Verdict | Evidence |
|------|---------|----------|
| T0 Theme/Token | PASS | `Color.kt` Teal/Amber/Neutral99 `#F6FAF9` / SurfaceDark `#0E1416`; `Theme.kt` Light/Dark schemes, `dynamicColor=false`, `LocalContainers` + `LanSyncShapes`; no `statusBarColor`/`navigationBarColor` assignment (luminance-only icon appearance). |
| T1 Edge-to-edge | PASS (code) | `MainActivity.onCreate` → `enableEdgeToEdge()`; `NavigationBar(containerColor = containers.default, windowInsets = navigationBars)`. Device 3-button/gesture visual still pending. |
| T2 Nav + TopBar | PASS | Titles 设备/本地应用/远程应用/同步/文件 (`tab_*` strings); contextual multi-select TopBar (Close / 已选 n / 清除选择, `containers.high`); no in-page 「LanSync」; `rememberSaveable` tab. |
| T3 Shared components | PASS w/ notes | `CommonComponents.kt` has StatusChip / FilterChip / EmptyState / MeshHint / SummaryCard / BatchBar / SectionHeader / SearchBar / UpdateAccentBar + dual-theme Previews; Material Icons only. Touch ≥48 **partially fails** (see Correctness C3). |
| T4 Five screens | PASS | Device/Local/Remote/Sync/File wired to existing `MainViewModel` API surface; no business-logic edits observed in UI layer. |
| T5 Overlays | PASS w/ notes | Pairing = `ModalBottomSheet` (back-dismissable, countdown → reject); Download Dialog; Install Snackbar (auto-dismiss + swipe); Save Dialog; InitialScanOverlay scrim; `operationMessage` feedback. Snackbar visibility has a reuse bug (C3). |
| T6 Polish | **INCOMPLETE** | Spec T6 unchecked; **no `FEEDBACK.md`**; no Dynamic Type / dark-contrast / reduce-motion evidence; device white-bar checklist still open. |

## 2. Correctness

### CRITICAL
1. **Double statusBars inset (Device tab)** — `IdentityStrip` applies `.windowInsetsPadding(WindowInsets.statusBars)` and the `TopAppBar` directly below (via `LanSyncTopBar`) also consumes default statusBars insets. On tab 0 the strip and bar stack → ~2× status-bar gap. Fix: strip consumes insets; set TopAppBar `contentWindowInsets = WindowInsets(0)` when IdentityStrip is present, or drop strip padding and let a single ancestor pad.
2. **`rememberSaveable` + non-Bundleable `DeviceInfo`** — `SyncScreen.kt:40` `rememberSaveable { mutableStateOf<DeviceInfo?>(…) }`. `DeviceInfo` is `@Serializable` (kotlinx) but not Parcelable; autoSaver cannot put it in a Bundle → `IllegalArgumentException` on config change / process-death restore when non-null. Fix: save `displayKey: String` and resolve against `connectedDevices`.

### MAJOR
3. **Touch targets & snackbar lifecycle**
   - Pull/Install row buttons use `Modifier.height(sp.buttonHeight)` = **36.dp** (`RemoteAppListScreen` ~358, `AppListScreen` UpdateItem ~390); `LanSyncMetrics.filterChipHeight` also 36 — conflicts with T3 “触控 ≥48dp” and `minTouchTarget`.
   - `InstallStatusSnackbar.isVisible` is `remember { mutableStateOf(true) }` **not keyed on `status`** — after first dismiss, later install statuses stay invisible until recomposition leaves/re-enters. Key on `status` (or reset when status changes).

### MINOR
4. `DeviceListScreen` LazyColumn `contentPadding` adds `WindowInsets.navigationBars` **on top of** Scaffold `paddingValues` (NavBar already includes those insets) → extra bottom blank. Not a white bar, but inconsistent with other tabs.
5. `MeshHint` StatusChip always reads “online” even when `onlineCount == 0`.

## 3. Consistency

- **PASS**: No brand `Color(0x…)` outside `Color.kt`; no emoji icons (all Material Icons); icon-only controls (`cd_close`, `cd_clear`, `cd_refresh`, nav labels) have `contentDescription`; decorative icons correctly `null`.
- **PASS**: Status meaning is never color-only (StatusChip text + icon; update badge has text; switch paired with status copy).
- **Notes**:
  - Radius dual source: `MaterialTheme.shapes.*` vs `RoundedCornerShape(sp.radius*)` — values match today (8/12/16/24/28) but can drift.
  - Residual dp literal: `AppListScreen` `EmptyStateCard` `24.dp` (should be `sp.space24`).
  - Alpha tints (0.15/0.3/0.45/0.5/0.7) scattered; acceptable semantic use, not brand-color hardcoding.
  - `LanSyncMetrics` and `LanSyncSpacing` duplicate list/nav/touch sizes (72/88/64×32/48).

## 4. T6 delivery checklist (code-level)

| Item | Result |
|------|--------|
| No white system bars | PASS (code: no opaque bar colors + e2e + nav container). Device visual open. |
| 5 tab contextual titles | PASS |
| Teal M3 roles | PASS |
| Contextual TopBar + BatchBar | PASS |
| Empty CTA | PASS (post-fix) — Devices/Files + Remote/Sync refresh CTA added. |
| No emoji structural icons | PASS |
| Color not sole meaning | PASS |
| Business regression | NOT RUN (no device; UI layer does not mutate ViewModel/repo contracts). |

## Conclusion

- **Spec compliance**: T0–T5 accepted with notes; T6 polish items **addressed in follow-up** (FEEDBACK.md written; criticals fixed). Device visual checklist still open.
- **Correctness**: 2 criticals + majors **fixed** (see FEEDBACK.md “最终 Review 后已修复”). Remaining: MeshHint online chip copy; device visual validation.
- **Consistency**: Clean token usage and a11y descriptions; small residual Metrics/Spacing duplication.

**Status after fixes**: Ready for device-side T1/T6 visual acceptance. Full report retained above for audit.
