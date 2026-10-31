# 规范清单（CONVENTIONS）

动手前按改动类型挑对应小节照做；冲突时以代码与 `docs/SPEC.md` 为准。

## 1. 架构 / Kotlin

- **MUST** 依赖单向向下；`data/**` 不得 `import com.lansync.app.ui.*`（`architecture/LayeringTest` 阻断）。
- **MUST** 门面（`LanSyncRepository`）保持纯委托 + 零业务逻辑；协作者下沉逻辑而不是往门面堆。
- **MUST** 超时/间隔取 `AppConfig`；**MUST NOT** 在构件里硬编码毫秒常量。
- **MUST** scope / dispatcher 构造注入（便于 `TestDispatcher`）；**MUST NOT** 类内自建 `CoroutineScope`。
- **MUST** 跨设备 DTO 只放 `data/model`；**MUST NOT** 新增 `DeviceInfo` 上线路径（SPEC §2.2 冻结）。
- **MUST** 包名解析 / 文件命名只用 `DownloadedFileName`，不在 ViewModel 再实现一份。
- **MUST NOT** 把 `e.message` / 堆栈回显进 HTTP 响应；异常细节只进 `FileLogger`。
- **MUST** 并发写法遵循 `docs/ARCHITECTURE.md` §6（状态单点收敛、Job 取消重建同协程内完成）。
- 分阶段迁移期若新旧构件并存：**MUST** 新构件放新包或改用不冲突的新名，避开冻结中的旧同名类；此期**不接 UI、不改旧码**。

## 2. 序列化 / 协议

- **MUST** 保持 `encodeDefaults=false` 语义；换序列化器必须保证"等于默认值的可选字段被省略"。
- **MUST** 本地缓存 JSON（`Json { ignoreUnknownKeys = true }`，`prettyPrint=false`）与线上 JSON **分开**。
- **MUST** 改动任何跨设备字节前：先读 `docs/SPEC.md` 对应章节 → 改后跑 `LanSyncRoutingTest` + `LanSyncClientTest` + `ModelsTest` → 偏离点登记进 `docs/DECISIONS.md`。
- **NEVER** 复用 `DeviceInfoResponse.version` 做兼容判断（已裁决死字段，见 `docs/DECISIONS.md` T2）。

## 3. UI / Compose

- **零硬编码三禁**（审计范围 `ui/` 非 theme 文件 + `MainActivity`，含 `ui/viewmodel`）：无内联中文字面量（走 `strings.xml`）、无 `Color(0x..)` 等色字面量（走 `Color.kt` + `colorScheme`/`containers`）、无裸 `\d+.dp`（走 `LanSyncTheme.spacing.*`）。由 `architecture/DesignSystemTest` 阻断；`@Preview` 同样只用令牌与资源，**中文字面量这条没有豁免文件**。
- **MUST NOT 把 `data` 层的诊断文本渲染到界面**：失败原因一律用机器可读类别（`ConnectionFailure.Kind` / `InstallFailure` / `LocalAppRepository.ScanFailure` / `SaveFailure`）在 `data` 层产出，UI 只在 `ui/string/FailureText.kt` 里做「类别 → 资源」映射；`e.message`、对端回复文本等原文只进 `FileLogger`。
- **禁止自造淡化**：组件内不得写 `color.copy(alpha = …)`。需要"淡底 + 深字"就取 M3 的 `*Container` / `on*Container` 配对或 `LanSyncTheme.containers` 四档。`outline` / `outlineVariant` 是 3:1 的非文本角色，**MUST NOT** 用作正文/小字号文字色（次要文字用 `onSurfaceVariant`）。唯一例外：`ui/theme/Color.kt` 里具名登记的 `LanSyncDim.overlay`。
- **令牌表唯一入口**：色值 `Color.kt`、尺寸 `Spacing.kt`、圆角 `Shape.kt`（`MaterialTheme.shapes`）、阴影 `Elevation.kt`、动效 `Motion.kt`、比例 `Typography.kt`、组装 `Theme.kt`。**MUST NOT** 再引入第二张尺寸表；`LanSyncMetrics` 已并入 `LanSyncSpacing`。阴影不得借用间距令牌，动效时长不得写死（`tween(300)` / `delay(600)` 都算违规；组合外取 `LanSyncTiming`）。
- **令牌纪律**：`Spacing.kt` / `Elevation.kt` 只登记**有调用点**的尺寸，`Typography.kt` 只登记被使用的样式；零引用即由门禁判红。圆角不设 spacing 副本，一律 `MaterialTheme.shapes.*`。
- **⛔ 两条 crash / 视觉级陷阱（评审必查）**：
  1. `rememberSaveable` 存的类型**必须可 Bundle 化**——存 `DeviceInfo` 这类领域对象会直接崩溃，`Set<String>` 同样不可。改存 `String`/`List<String>`/基本类型，或自备 `Saver`。
  2. **不要叠加 insets**——`enableEdgeToEdge` 后 TopBar 已吃 `statusBars`、`NavigationBar` 已吃 `navigationBars`，组件再 `.padding(statusBars...)` 会出现双份留白。edge-to-edge 与"不写不透明系统栏色赋值"必须成对。水平安全区（刘海/横屏）只在根容器取 `displayCutout` 的 start/end 两个分量（`asPaddingValues().calculateStartPadding/EndPadding(layoutDirection)`）；**不要**用 `windowInsetsPadding(WindowInsets.displayCutout)`，它四个方向都加，会与上面两者的垂直 insets 叠成双份。
- **MUST** `dynamicColor` 保持默认关闭（除非用户明确要求 Material You）。
- **MUST** 触控目标 ≥48dp：交互控件写 `Modifier.heightIn(min = spacing.minTouchTarget)`，**MUST NOT** 用固定矮高度（`height(sp.buttonHeight)` 已废弃，`buttonHeight` 令牌随之删除）。列表行 `heightIn(min = spacing.listItemMin)`。
- **MUST** 共享原语优先，不在屏内复刻：行 = `LanSyncAppRow`（远程/可更新/已下载共用），徽标 = `LanSyncBadge(kind)`，空态/成功态 = `EmptyState`，短反馈 = `FeedbackBanner`（带 `liveRegion`，瞬时状态要能被 TalkBack 播报），批量条 = `BatchBar`，chip = `LanSyncFilterChip`（裸 `FilterChip` 只允许出现在 `CommonComponents.kt`），进行中按钮 = `InProgressButton`（不要 `onClick = {}` 的禁用按钮散落各处），加载态 = `LanSyncSpinner` / `LanSyncProgressBar`（裸 `CircularProgressIndicator` / `LinearProgressIndicator` 只允许出现在 `LoadingVisuals.kt`，尺寸与线宽由令牌给默认值，宽度留给调用方）。
- **MUST** 瞬时反馈只有一个底部宿主：多条反馈（安装状态 + 操作提示）放进同一个 `Column(BottomCenter)`，不得各挂一个 `Box(BottomCenter)` 互相压字。
- **MUST** 动效时长只从 `LanSyncTheme.motion` 取（tab `Crossfade`、刷新旋转、横幅退出与自动消失、倒计时 tick、划走阈值），减弱动效靠 `Settings.Global.ANIMATOR_DURATION_SCALE` 一处判定（`rememberMotionEnabled()`）。
- **MUST** 体积等数值格式化走 `ui/format/SizeFormat.kt`，IP:端口/版本对比等拼接文本走带占位符的 `strings.xml`（`addr_ip_port` / `version_pair` / `sync_provider_size`），不在组件里手拼字符串。等宽技术数值取 `LanSyncMonoText`。
- **MUST** 配对 UI 用 `ModalBottomSheet`（返回键可关、倒计时自动拒绝），不是 `Dialog`。
- **MUST** UI 只消费 `UiState` / 派生值 + 回调，**不接裸 Flow**；选择态（`selectedUpdates` 等）留在 ViewModel，不进领域层。

## 4. 测试写法

- **MUST** 优先迁移既有断言；删断言需给出"该行为已被哪个新用例覆盖"的理由并登记。
- **MUST** 用虚拟时间驱动超时；状态机测试用**直接投递事件**，只有周期性触发用 `advanceTimeBy`。
- **SHOULD** 用 `ConnectionTransport` 抽象 + 手写 Fake 测 coordinator，**不 MockK 代理 final 类**、不改动已绿的 `LanSyncClient`。
- **MUST** 文件相关用 `TemporaryFolder`；产物目录走构造注入以便测试。
- **MUST NOT** 为不可覆盖的分支写假断言（DL-6 是判例）；改行为要同步改 `docs/TEST-PLAN.md` 对应行。
- **MUST** 判定绿灯只看 `BUILD SUCCESSFUL` + `app/build/test-results/testDebugUnitTest/*.xml`，不被 stderr 警告或管道 exit code 误导；输出里 `:app:testDebugUnitTest UP-TO-DATE` 等于**没跑**，此时不能声称门禁通过（门禁输入登记见 `app/build.gradle.kts` 末尾与 `docs/BUILD.md` §1 前提 4）。
- **MUST** 新增文本扫描类门禁（`LayeringTest` / `DocFactsTest` / `DesignSystemTest`）时先确认它当前会判红还是判绿：用同样的扫描跑一遍源码，别把没验证过的断言写进门禁。**测试依赖缺失导致 `testDebugUnitTest` 跑不动时，门禁的存在不等于门禁在生效**，须在 `docs/STATUS.md` §2 写清。
- **MUST** 给每条文本扫描规则配负控：被禁写法的「规则名 + 正则 + 最小坏样本」只注册一次（`DesignSystemTest.ban()` 形态），规则用例与自检引用同一个对象，匹配入口只接受注册项；再加反向检查——注册了却没有规则调用它、或绕过注册内联 `Regex`，一律判红。
  理由：正控只能证明当前源码干净，正则写错、或违规写法换了个取值入口（`val scheme = MaterialTheme.colorScheme` 之后的 `scheme.surfaceVariant`）都会静默空过；自检若另抄一份正则，可能自检全绿而规则早已失效。豁免规则同样要有负控（被豁免的原语文件里必须真的存在该裸用点）。
  Kotlin 侧写法约束：三引号原始串末尾紧跟一个引号（`""""`）会把那个 `"` 收进内容，模式因此多出一个匹配不上的尾字符 —— 正则只在注册处写一份可同时消除这个陷阱与副本漂移。

## 5. Git / 流程

- 分支从 `main` 开；合回 `main` 用 `--ff-only`；历史分支保留不删。
- 提交信息沿用仓库风格：`feat|fix|refactor|chore|docs: 中文一句话`。
- **NEVER** 未与用户确认就执行破坏性命令（`reset --hard`、`push --force`、删分支、`--no-verify`）；**未经用户要求不得 commit**。
- 共享工作树环境**禁用裸 `git stash` / `git stash pop`**；确需暂存用带唯一 tag 的 `git stash push -m "tag"` + `git stash apply`。
- 每个特性/批次交付前跑独立评审（`lansync-review` 子代理，按 Spec 符合性 / 正确性 / 一致性三段输出分级发现）；critical 必须修，结论性事实回写文档。检索/定位代码用 `lansync-explore` 子代理。

## 6. 文档维护

- 不新建与上表并列的"事实源"文档。单特性如需设计稿，放 `docs/compose/spec/<feature>.md`（frontmatter + Problem / Design / Out of Scope + Tasks），交付后把**结论性事实**回写对应主题文档，设计稿本身不留状态。
- 内容归属唯一：状态 → `docs/STATUS.md`，构建运行 → `docs/BUILD.md`，架构与代码地图 → `docs/ARCHITECTURE.md`，字节契约 → `docs/SPEC.md`，测试 → `docs/TEST-PLAN.md`，规范 → `docs/CONVENTIONS.md`，裁决 → `docs/DECISIONS.md`，方向与债务 → `docs/ROADMAP.md`，项目速览 → `docs/OVERVIEW.md`。**不要跨文件复制同一条事实**。
- 三份锚点文档（SPEC / ARCHITECTURE / TEST-PLAN）**保留章节号**（生产代码与测试注释按 `SPEC §N` / `ARCH §N` / `TEST-PLAN §N` 引用）：只替换内容，不重排 §N、不删文件。
- 写进文档的每个数字（例数、行数、文件数、权限数、提交号）都要先对代码实测。**同一个数字只写在一处权威位置**，别处引用文件与章节号、不复述数字（复述即第二事实源）。已被 `architecture/DocFactsTest` 机械校验的类别清单见 `docs/ROADMAP.md` §4；新增一类可实测数字时，**MUST** 同批把它加进门禁与那份清单，否则它仍属人工核对范围。
- 文档只写"现在该怎么做、方向是什么"，不留"某年某月做了什么"、"某文件已删除后如何如何"式考古注释；历史过程查 git。
- 改了文档结构就同步 `.qoder/agents/lansync-explore.md`、`.qoder/agents/lansync-review.md` 与项目记忆 `lansync-doc-map.md`（三处都在版本控制之外，不会随 `git diff` 暴露）。
