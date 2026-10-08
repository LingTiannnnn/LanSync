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

- **零硬编码三禁**（审计范围 `ui/` 非 theme 文件 + `MainActivity`）：无内联中文字面量（走 `strings.xml`）、无 `Color(0x..)` 等色字面量（走 `Color.kt` + `colorScheme`/`containers`）、无裸 `\d+.dp`（走 `LanSyncTheme.spacing.*`）。`@Preview` 里的 `.dp` 暂不纳入审计。
- **⛔ 两条 crash / 视觉级陷阱（评审必查）**：
  1. `rememberSaveable` 存的类型**必须可 Bundle 化**——存 `DeviceInfo` 这类领域对象会直接崩溃。改存 `String`/基本类型，或自备 `Saver`。
  2. **不要叠加 insets**——`enableEdgeToEdge` 后 Scaffold/TopBar 已吃 `statusBars`，组件再 `.padding(statusBars...)` 会出现双份顶部留白。edge-to-edge 与"不写不透明系统栏色赋值"必须成对。
- **MUST** `dynamicColor` 保持默认关闭（除非用户明确要求 Material You）。
- **MUST** 触控目标 ≥48dp（`minimumInteractiveComponentSize()`）；列表行 `minHeight 72`。
- **MUST** 配对 UI 用 `ModalBottomSheet`（返回键可关、倒计时自动拒绝），不是 `Dialog`。
- **MUST** UI 只消费 `UiState` / 派生值 + 回调，**不接裸 Flow**；选择态（`selectedUpdates` 等）留在 ViewModel，不进领域层。
- **MUST** 视觉规格以 `ui/theme/` 五个文件为唯一来源（色值 `Color.kt`、令牌 `Spacing.kt`/`Shape.kt`、比例 `Typography.kt`、组装 `Theme.kt`）。

## 4. 测试写法

- **MUST** 优先迁移既有断言；删断言需给出"该行为已被哪个新用例覆盖"的理由并登记。
- **MUST** 用虚拟时间驱动超时；状态机测试用**直接投递事件**，只有周期性触发用 `advanceTimeBy`。
- **SHOULD** 用 `ConnectionTransport` 抽象 + 手写 Fake 测 coordinator，**不 MockK 代理 final 类**、不改动已绿的 `LanSyncClient`。
- **MUST** 文件相关用 `TemporaryFolder`；产物目录走构造注入以便测试。
- **MUST NOT** 为不可覆盖的分支写假断言（DL-6 是判例）；改行为要同步改 `docs/TEST-PLAN.md` 对应行。
- **MUST** 判定绿灯只看 `BUILD SUCCESSFUL` + `app/build/test-results/testDebugUnitTest/*.xml`，不被 stderr 警告或管道 exit code 误导。

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
- 写进文档的每个数字（例数、行数、文件数、权限数、提交号）都要先对代码实测。
- 文档只写"现在该怎么做、方向是什么"，不留"某年某月做了什么"、"某文件已删除后如何如何"式考古注释；历史过程查 git。
- 改了文档结构就同步 `.qoder/agents/lansync-explore.md`、`.qoder/agents/lansync-review.md` 与项目记忆 `lansync-doc-map.md`（三处都在版本控制之外，不会随 `git diff` 暴露）。
