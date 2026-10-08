# LanSync · agent 入口

局域网 P2P Android 应用更新同步工具（Kotlin + Compose + Ktor/OkHttp + JmDNS）。重构范式：**契约不变，实现重写**。

本文件只做导航。深度内容按需读 `docs/` 对应文件，不要凭记忆复述，也不要在这里堆细节。

## 权威层级

1. **代码与实测输出** —— 一切"现在是什么样"的问题。
2. `docs/` 主题文档（下表，每类事实只有一处权威来源）。
3. `.qoder/repowiki/**`、`.qoder/specs/**` —— 自动生成物，**不是事实源**。

## 文档地图

| 文件 | 只管这件事 | 何时读 |
|---|---|---|
| `docs/OVERVIEW.md` | 项目能力、权限与 Manifest、本地持久化与缓存清单 | 需要知道"声明了什么" |
| `docs/BUILD.md` | 本机编译/测试命令与四条前提、仓库镜像约束、技术栈版本锁 | **跑构建或单测前必读** |
| `docs/STATUS.md` | 阶段进度、测试基线、分支与远端、验收缺口 | 判断"做到哪一步了" |
| `docs/SPEC.md` | 线上协议**字节级冻结契约**（v1.0）：DTO 逐字段、10 路由与错误码、下载头与命名、mDNS/TXT、连接状态机、超时参数、六条红线（§9） | 任何跨设备字节改动前**必读** |
| `docs/ARCHITECTURE.md` | 分层与依赖铁律、模块契约接口、并发规范、错误协议、FGS 设计；**§13 = 当前实现形态**（13.1 代码地图 / 13.2 主链路 / 13.3 DI / 13.4 UI 设计系统 / 13.5 与目标差异） | 改分层、加构件、接线，或想理解"为什么这样设计" |
| `docs/TEST-PLAN.md` | L1–L4 策略、必覆盖断言、DL/CS 用例矩阵、真机 golden path 清单、门禁语义、§10 当前落地清单 | 写测试前、跑真机验收时逐格勾 |
| `docs/CONVENTIONS.md` | 规范清单：架构 / 序列化 / UI / 测试 / Git / 文档维护，MUST 与 MUST NOT | 动手前挑对应小节 |
| `docs/DECISIONS.md` | 已裁决登记册（T / TT / D 系列 + 工程决策）与各自的重开条件 | 想推翻某个既有做法前先查 |
| `docs/ROADMAP.md` | 真机验收缺口、Phase 6 / Phase 7 范围、非阻塞债务、死符号清单 | 决定工作方向 |
| `README.md` | 面向用户的功能说明 | 对外表述 |

## 按任务定位

| 任务 | 读哪里 |
|---|---|
| 跑构建 / 单测 | `docs/BUILD.md` |
| 改协议字段、路由、响应头、文件命名 | `docs/SPEC.md` 对应章节 → 改后跑 `LanSyncRoutingTest` + `LanSyncClientTest` + `ModelsTest` |
| 改分层、加构件、依赖注入 | `docs/ARCHITECTURE.md` §2 / §6 / §13 + `docs/CONVENTIONS.md` §1 |
| 改 UI / 视觉 | `docs/ARCHITECTURE.md` §13.4 + `docs/CONVENTIONS.md` §3（含两条 crash 级陷阱） |
| 写测试 | `docs/TEST-PLAN.md` §1 / §2 / §10 + `docs/CONVENTIONS.md` §4 |
| 追一条跨层链路（谁调谁） | `docs/ARCHITECTURE.md` §13.2 + §13.1 |
| 判断"做到哪一步了"、下一步做什么 | `docs/STATUS.md` + `docs/ROADMAP.md` |

## 三条不可违背的约束

- **六条互操作红线**：违反任一条即无法与旧版 APK 互通。清单与逐项条款只在 `docs/SPEC.md` §9 维护，改任何跨设备字节前先读它。
- **依赖单向向下**：`data/**` 不得 import `com.lansync.app.ui.*`（`architecture/LayeringTest` 阻断）。
- **未经用户确认不做破坏性 git 操作、不 commit**；构建是否成功只以输出里的 `BUILD SUCCESSFUL` 判定。

## 维护约定

交付批次结束时更新对应主题文档并随代码一起提交。内容归属唯一、不跨文件复制、不写"某日做了什么"式历史注释。细则见 `docs/CONVENTIONS.md` §6。
