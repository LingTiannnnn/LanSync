import {
  ReportShell,
  ReportSection,
  ImprovementList,
  ImprovementDisclosure,
  Fluency,
  Tag,
  MetricsGrid,
  Callout,
  Text,
  Stack,
  Row,
  Code,
} from "qoder/canvas";

const dimensions = [
  { id: "task-understanding", label: "任务理解", score: 72, summary: "文档完整（SPEC/ARCH/TEST-PLAN/PROGRESS），决策记录清晰，但缺少 AGENTS.md 统一导航入口。" },
  { id: "controlled-execution", label: "受控执行", score: 48, summary: "构建命令已文档化但需手动非标准设置（GRADLE_USER_HOME 覆盖），无自动化验证或 doctor 命令。" },
  { id: "change-validation", label: "变更验证", score: 68, summary: "测试覆盖扎实（139 例，L1-L3 分层），但缺少 CI 门禁与静态分析护栏。" },
  { id: "reliable-delivery", label: "可靠交付", score: 42, summary: "测试计划完整但运行时行为仅 JVM 测试验证，未真机执行；无 CI/CD 流水线。" },
  { id: "learning-capture", label: "学习捕获", score: 38, summary: "决策与进度通过 PROGRESS.md 跨会话记录，但未形成可复用程序或 Skill。" },
];

const findings = [
  {
    id: "missing-agent-navigation",
    severity: "Medium",
    title: "项目缺少 Agent 导航入口，新 Agent 需从多份文档自行推断上下文",
    cause: "项目无 AGENTS.md 或等效指令文件。Agent 需自行发现并阅读 PROGRESS.md（215 行）、SPEC.md（616 行）、ARCHITECTURE.md（380 行）、TEST-PLAN.md（280 行）才能理解阶段状态、协议契约与架构目标。PROGRESS.md 明确声明「唯一事实源」但仅人类可读。",
    expectedOutputs: [
      "Agent 可在 30 秒内定位当前阶段、构建命令、关键约束",
      "减少首次任务探索轮次",
    ],
    action: "创建根目录 AGENTS.md：项目概述、当前状态（指向 PROGRESS.md §1）、关键约束（测试基线 139/139、门面 ≤300 行、data 层禁 import ui.*）、非标准构建命令、文档地图。",
    artifact: "AGENTS.md",
  },
  {
    id: "non-automated-build-environment",
    severity: "Medium",
    title: "构建环境需手动非标准设置，无自动化验证或 doctor 命令",
    cause: "标准 .\\gradlew.bat 因 GRADLE_USER_HOME 下 wrapper dist 不完整而超时，需手动覆盖环境变量并使用完整路径的 gradle.bat + --offline。此设置仅文档化于 PROGRESS.md §4 与 TEST-PLAN §7，无自动化验证脚本。",
    expectedOutputs: [
      "Agent 可通过单一命令完成环境验证与构建",
      "消除手动复制粘贴非标准路径",
    ],
    action: "创建项目根目录 build.ps1：检测 GRADLE_USER_HOME、验证 wrapper dist 完整性、封装默认离线构建命令、doctor 模式输出环境状态（JDK / Android SDK / Gradle wrapper）。",
    artifact: "build.ps1 / setup.ps1",
  },
  {
    id: "no-static-analysis-gates",
    severity: "Low",
    title: "架构计划的分层检查与静态分析护栏未落地",
    cause: "ARCHITECTURE.md §8.3 计划 CI 静态检查断言 data/** 不 import com.lansync.app.ui.*，§12 验收标准第 3 条要求 CI 静态检查通过。当前仅靠 Phase 5 的 grep 人工审计，无自动化门禁。",
    expectedOutputs: [
      "分层铁律自动校验，反向依赖引入即失败",
      "架构验收标准 §12 可自动判定",
    ],
    action: "落地 ARCH §8.3：添加 Konsist 依赖（或自定义 lint 规则），编写测试断言 data/** 不 import ui.*，集成到 testDebugUnitTest 门禁。",
    artifact: "Konsist 测试或自定义 lint 规则",
  },
  {
    id: "runtime-behavior-unverified",
    severity: "Low",
    title: "运行时行为（FGS/mDNS/连接/下载）仅 JVM 测试验证，未真机执行",
    cause: "PROGRESS.md §1 明确「运行时行为待真机验证」；TEST-PLAN §6 定义互操作矩阵与 Golden Path 清单但状态为「待真机执行」。FGS 生命周期、mDNS 发现、连接状态机迁移、下载校验、安装链路仅通过 JVM 单元测试与 assembleDebug 编译验证。",
    expectedOutputs: [
      "运行时行为真机验证完成",
      "互操作矩阵四组合全绿，证明重构未破坏线上契约",
    ],
    action: "执行 TEST-PLAN §6 真机互操作验收：双真机 + 同一 WiFi，按 Golden Path 逐格执行，完成新×新/新×旧/旧×新/旧×旧四组合矩阵，结果回填 PROGRESS.md。",
    artifact: "真机测试报告（更新至 PROGRESS.md）",
  },
  {
    id: "no-reusable-procedure-capture",
    severity: "Low",
    title: "多阶段重构程序与决策模式未固化为可复用程序",
    cause: "项目已完成 Phase 0-5 共 6 阶段重构，形成清晰模式：文档冻结 → 分阶段实现（命名避让、传输抽象、手写 DI）→ 每阶段过护栏 → 更新 PROGRESS.md → git commit。但此程序仅隐式存在于记忆与文档中，未固化为可复用 Skill。",
    expectedOutputs: [
      "多阶段重构程序可复用",
      "决策登记册格式标准化",
    ],
    action: "提取多阶段重构程序为 Skill（.qoder/skills/phased-refactor/SKILL.md）：文档冻结与决策登记模板、命名避让/传输抽象/手写 Fake 测试模式、每阶段门禁清单。",
    artifact: "Skill 或检查清单",
  },
];

const coverageRows = [
  { surface: "Rules", count: 0, scope: "Project" },
  { surface: "Skills", count: 0, scope: "Project" },
  { surface: "Memory", count: 5, scope: "User" },
  { surface: "MCP", count: 0, scope: "Project" },
  { surface: "Plugins", count: 1, scope: "User" },
];

function severityTone(severity: string): "warning" | "info" {
  return severity === "Medium" ? "warning" : "info";
}

export default function BetterHarnessReport() {
  return (
    <ReportShell width="reading">
      <ReportSection
        id="overview"
        title="LanSync Harness 分析报告"
        description="项目文档与测试基础扎实，但缺少 Agent 导航入口、自动化构建验证与运行时验证，限制了 Agent 独立工作能力。"
        meta={<>5 findings · 支持轨道 Bootstrap (0 → 1)</>}
      >
        <MetricsGrid
          variant="header"
          columns={5}
          items={[
            { label: "任务理解", value: "72" },
            { label: "受控执行", value: "48", tone: "warning" },
            { label: "变更验证", value: "68" },
            { label: "可靠交付", value: "42", tone: "warning" },
            { label: "学习捕获", value: "38", tone: "warning" },
          ]}
        />
      </ReportSection>

      <ReportSection
        id="dimensions"
        title="五维度评估"
        description="Agent Work Loop 五维度能力评分"
      >
        <Fluency
          projectName="LanSync"
          columns={dimensions.map((d) => ({
            id: d.id,
            title: d.label,
            subtitle: d.summary,
            score: d.score,
          }))}
          height={220}
        />
        <Stack gap={8}>
          {dimensions.map((d) => (
            <Text key={d.id} tone="secondary" size="small">
              {d.label}（{d.score}）：{d.summary}
            </Text>
          ))}
        </Stack>
      </ReportSection>

      <ReportSection
        id="findings"
        title="发现的问题"
        description="按优先级排序的改进机会"
      >
        <ImprovementList divided>
          {findings.map((f) => (
            <ImprovementDisclosure
              key={f.id}
              severity={f.severity}
              severityTone={severityTone(f.severity)}
              title={f.title}
              cause={f.cause}
              expectedOutputs={f.expectedOutputs}
              detailsSummary={`预期产物：${f.artifact}`}
              labels={{ cause: "问题原因", expectedOutput: "预期效果", details: "详情" }}
            >
              <Callout tone="info" title="修复建议">
                <Stack gap={4}>
                  <Text size="small">{f.action}</Text>
                  <Code>{f.artifact}</Code>
                </Stack>
              </Callout>
            </ImprovementDisclosure>
          ))}
        </ImprovementList>
      </ReportSection>

      <ReportSection
        id="coverage"
        title="资产覆盖图"
        description="项目配置的 Agent 资产清单（计数仅路由检查，不构成发现）"
      >
        <Stack gap={8}>
          {coverageRows.map((row) => (
            <Row key={row.surface} gap={12} align="center">
              <Text style={{ width: 80 }}>{row.surface}</Text>
              <Tag tone="neutral">{row.scope}</Tag>
              <Text tone="secondary">{row.count}</Text>
            </Row>
          ))}
        </Stack>
      </ReportSection>

      <ReportSection
        id="context"
        title="项目上下文"
      >
        <Stack gap={8}>
          <Text>当前阶段：Phase 5 完成（UI 一次成型）；测试基线 139/139 全绿。</Text>
          <Text>下一步：Phase 6（安全加固/协议版本化）或 Phase 7（工具链升级），待用户确认。</Text>
          <Text>关键约束：门面 ≤300 行 · data 层禁 import ui.* · SPEC v1.0 冻结协议契约。</Text>
          <Text>优先行动：创建 AGENTS.md + 自动化构建脚本（build.ps1），即可显著提升 Agent 独立工作能力。</Text>
        </Stack>
      </ReportSection>

      <Callout tone="info" title="支持轨道：Bootstrap (0 → 1)">
        <Text size="small">
          项目处于初始引导阶段，需要建立基础的 Agent 导航与环境验证机制。保留的发现均为已观测的当下后果，
          修复验证命令均来自项目内已有文档（PROGRESS.md §4 / TEST-PLAN §6-§7）。
        </Text>
      </Callout>
    </ReportShell>
  );
}
