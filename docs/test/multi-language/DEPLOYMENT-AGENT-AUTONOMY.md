# Agent 自主审批与按需 Skill

2026-10-12。状态：自主审批与三项按需 Skill 的实现、本地定点验证及源码冻结完成，尚未启动本阶段新的真实模型部署。本文不将历史部署、本地通过或授权后的连接意图算作新验收。

## 目标与职责

部署模型独立选择产品工具或获授权的命令能力，识别来源、诊断失败、处理审批反馈并完成实际发布。监督者观察运行；确认产品能力阻塞后，通过代码或 Skill 修正通用语义，再以独立运行验证。不在运行中给模型步骤、最终命令或配置答案。

审批模型独立读取当前完整命令、可执行输入、来源、目标、回执及相关产品契约，判断允许、拒绝、需要哪些证据或是否应结束无进展的讨论。建议和候选命令只作为数据，不能授予权限；主模型采用建议后，实际完整命令和 stdin 仍须重新审批。完整身份、静态边界、当前路由、单次授权和 UNKNOWN 防重放由产品强制执行。

STATIC、ASSISTED 保持原审批协议。AGENT 的原生命令审批可以自主调用只读取证工具；高层源码补丁判断和实际 SSH 可执行输入审批仍是各自独立的边界。审批单次输出上限不得超过普通部署单次上限。

## 正式 Skill 与生效方式

正式调用目录：`src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/service/agent/skill/`。资源随部署 Mod 版本发布，不写入个人 Skill 目录，不从部署来源注册可信技能，也不跨版本热改原冻结任务。

| Skill | 调用职责 | 适用情形 |
| --- | --- | --- |
| `approval-discussion/SKILL.md` | 审批 | 重复请求、未结束的证据交换，或真实新证据可能解决原拒绝 |
| `deployment-diagnosis/SKILL.md` | 部署 | 命令、构建、运行检查、审批失败，或观察与计划矛盾 |
| `publication-verification/SKILL.md` | 部署与审批 | 发布、实际健康、任务收尾及临时资源清理的证据判断 |

模型先发现名称和描述，按需要调用原生 `skill` 工具读取正文。选中资源进入当前循环后续请求的可信 SYSTEM 上下文，具有指导作用，没有执行权限。职责不匹配、伪造名称、外部路径或其他任务调用必须拒绝。正式发布及结清判据即使没有加载 Skill 也继续生效。

封装参考了实际读取的 [Codex Build skills](https://learn.chatgpt.com/docs/build-skills)，以及本机 Skill Creator、模型路由 Skill 的发现、按需读取和职责约束。仅借鉴 Skill 组织与渐进加载，不复制其他产品的授权模型或固定部署顺序。

## 讨论与证据

审批模型依据问题复杂度、缺失证据和真实进展决定继续讨论或结束，不新增固定业务讨论轮数。已有共享任务预算、取消、输出和超时限制继续生效。

DENY 的 `discussionStatus` 可为 OPEN 或 CLOSED，省略时为 OPEN。OPEN 表示仍有具体有用的证据交换；CLOSED 表示相同真实事实下继续请求已无意义。只有 CLOSED 的准确同事实拒绝可以复用，真实来源修订、实测观察或已完成执行回执发生变化后必须允许新的独立判断。请求编号、执行意图、拒绝日志和改写理由不算新事实。

完整审批快照与讨论进展分别绑定。审批过程中身份、来源、回执或路由变化，原判断不能产生授权。判决格式错误可由原生工具反馈给模型自行纠正；纠正过程不授予权限。未完成输出、取消、错误和预算耗尽都不能默认放行。

独立只读复核发现并修复一项讨论重复问题：PLAN 的解释、IDENTIFY 的自由文本和 HANDOFF 的理由不能作为新实测事实。真实 H2 journal→forTask→Scope→ModelCommandRunner 的定点场景证明，同一结构图改写这三类说辞仍仅调用一次受控审批；实际计划入口、READ 或 COMMAND_RESULT 改变后分别重新审。该场景全程拒绝，零发送、零宿主授权。

主循环只对原生审批明确 OPEN 且确认 NOT_SENT 的反馈放开通用参数错误重复计数。原 effectsSettled、路由与 UNKNOWN 检查继续执行；普通参数错误、CLOSED 及旧协议拒绝保留原无进展停止规则。

## 本阶段证据与当前限制

证据目录为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/`，保存源码前像、编译与每轮定点报告、连接失败、源码冻结及实际用量记录。初始 HEAD 为 `6d562fdfcd3408b564c14e658a48fc917f1b4951`，原迁移工作区保留。35 个自有源码、资源与测试增量包含 11 个新文件、4 个可独立暂存增量和 20 个混合文件增量；混合文件通过 [精确补丁](patches/DEPLOYMENT-AGENT-AUTONOMY.patch) 交付，不能据此声称仅检出本阶段提交即可构建迁移中的全仓。

当前去重 67 个定点场景通过，无跳过。按实际类和场景选择 Maven `-Dtest=...`，失败后仅重跑受影响组；最终一次为上述进展分类的单个 JDBC 场景。全部场景及其最新成功报告见 `child-validation-summary.json`，不是模块或全项目测试。

| 定点类 | 不同成功场景 |
| --- | ---: |
| DeploymentAgentApprovalTest | 17 |
| DeploymentAgentCleanupEvidenceTest | 2 |
| DeploymentAgentProtocolRequestTest | 9 |
| DeploymentAgentPublishRunnerTest | 11 |
| DeploymentAgentRuntimeValidationSessionTest | 1 |
| DeploymentAgentSkillSessionTest | 3 |
| DeploymentLiveMonitoringRequestTest | 2 |
| DeploymentModelCommandRunnerTest | 9 |
| DeploymentRequiredReviewSubmissionTest | 13 |

覆盖原生取证顺序自主选择、源码伪 Skill 与职责隔离、当前 SYSTEM 加载、格式自纠、OPEN 连续反馈、CLOSED 复用与真实新证据、快照变化、默认三字段、真实产品发布/健康/结清路由，以及 UNKNOWN 保护。模型与 SSH 接口为受控替身；本地 Bash 用受控 layout/root 前提验证真实 Bash 回执，不能作为真实 Linux root、SSH 或业务验收证据。监控检查证明截断响应交还产品处理，不由监督者直接取消。

29 个不同自有 Java 文件的限定格式检查通过。22 个实现者既有文件的前像 SHA256 全部吻合，11 个新增路径无前像重叠，另两父级夹具的前像同样保留；精确补丁反向检查通过。实现者验证进程均退出。

首次 50 项的 47 通过/3 失败、原生工具标识格式错误、旧运行拒绝回调预期及其他中间失败全部保留，不与最终成功相加。官方 Skill Creator 的 `quick_validate.py` 实际调用因缺少 PyYAML 未完成，隔离临时安装网络失败并停止；未修改全局或生产依赖。Java 固定加载器的严格元信息校验与实际原生调用行为已验证，不能表述为官方结构脚本通过。

本阶段连接核查没有调用付费部署或审批模型。旧受保护凭据只经产品安全配置复用；旧任务未启动部署 Mod 或恢复。真实主机身份变化已由用户确认并授权新指纹，原旧信任记录保留；新指纹下的实际登录尚未验证。

新 task-board 独立部署、真实浏览器业务、非默认目录的真实服务器部署与重启恢复、其余七项目及全新服务器的冻结八项目批次均未验证。缓存及审批比例必须按对应轮次实际 provider usage 计算，未知值不得补零；历史调试用量不能混入冻结八项目批次。

数据库 v1 与历史恢复清理的本地验证、正式记录及剩余实际旧库处理见 [DEPLOYMENT-RECOVERY-HISTORY.md](DEPLOYMENT-RECOVERY-HISTORY.md)。目录布局本地通过与仍未完成的真实非默认目录验收见 [DEPLOYMENT-DIRECTORY-CONFIG-531.md](DEPLOYMENT-DIRECTORY-CONFIG-531.md)。
