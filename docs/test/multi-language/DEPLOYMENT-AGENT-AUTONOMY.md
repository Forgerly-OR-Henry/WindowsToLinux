# Agent 自主审批与按需 Skill

2026-10-12。状态：自主审批与三项按需 Skill 的实现、本地定点验证、源码冻结及本地提交完成。task-board-01 的历史凭据认证失败保留；用户重新提供连接截图后的 task-board-02 已成功登录并进入真实模型自主循环，随后在审批取证阶段终止为 UNKNOWN，业务验收尚未完成。本文不将历史部署、本地通过或连接成功算作新业务验收。

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

本地提交为 `ffaedf65961f627fbe1527e2c13db4c3cec715a3`（Add autonomous Agent review and on-demand skills），17 个自有暂存路径核对后提交，未推送。精确补丁按原始字节保存，SHA256 为 `644dc248771e73c87143f546b5942e5591286ebc5e1ac43244734c8c5d651fb4`；提交中的补丁与工作副本字节相同，反向检查通过。补丁的空行上下文标记保留，其余暂存源码和文档的 Git 空白检查通过。

新的 `task-board-01` 只选中 `DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry`，项目限定 task-board。产品接受用户已确认的新主机指纹 `SHA256:ioa2rovqWA0tIF954jRvT2RUnbzjPEirj3BM7HTAySU`，随后返回 `SSH_AUTHENTICATION_FAILED`。一项真实入口检查失败，无跳过；源码冻结 3257 项，运行期间零变化。运行及验证进程已退出，失败日志、报告、状态和用量记录保留，没有手工服务器命令或其他项目部署。

task-board-01 没有模型请求或配置探测，新增 provider 用量为 0，不存在该轮缓存或审批比例。旧受保护凭据只经产品安全配置复用，旧任务未启动部署 Mod 或恢复，原旧信任记录保留。随后只读核对默认产品库的服务器元信息，发现目标端点已配置；数据库文件字节未变化，未读取凭据值。

用户重新提供连接截图后，两个本机 OCR 引擎对指定凭据字段识别一致。凭据只经临时子进程环境进入已有产品加密配置保存入口，未写入源码、命令参数、提示词、模型正文或日志。临时环境随本次进程结束清除。新独立 task-board-02 通过产品 SSH 登录并严格匹配已授权新指纹；监督者没有执行服务器命令、提供部署步骤或中途修改源码。

task-board-02 的部署模型自行读取来源并识别组件，经重复取证后自行提交计划，进入 PLANNED、清空待判断观察，并选择数据库取证与准备。随后的原生审批在读取来源阶段未提交最终判决即终止，宿主记录 `DEPLOYMENT_COMMAND_REVIEW_INVALID`、`validationStage=response`，任务 `eb65b964-bb37-4bcf-b6b7-d6682dbb8def` 保留为 UNKNOWN。该标签包装整个审批循环，不能据此认定模型 JSON 错误。另以正常用户 DPAPI 与 H2 只读连接核对该任务的 100 条关联日志，没有找到白名单底层调用栈；数据库 SHA256 未变化，没有模型或服务器调用。原 UNKNOWN、来源资源和失败证据保留，不重放原操作或清除未结记录。

该轮一项真实入口检查失败、零跳过，3257 项源码冻结零变化，验证进程退出。41 次已捕获响应的实际 provider 输入为 666883、输出为 37239，已知合计 704122；输入缓存命中 404736、未命中 262147，审批已知输入加输出 110854。仅该已捕获部分的缓存命中率 60.69%、审批占比 15.74%；另有四项配置能力探测的调用数及用量 UNKNOWN，不能补零或据此宣称整轮比例达标。这是未完成的开发验收轮，不能混入最终冻结八项目批次。

审批诊断增量仅修改五个直接相关文件，以各文件改动前的实际工作副本为前像，单独保存 [诊断精确补丁](patches/DEPLOYMENT-AGENT-APPROVAL-DIAGNOSTICS.patch)，原自主审批补丁不改写。实际执行边界记录绑定快照、上下文、响应检查、证据读取、历史保留或判决阶段；公开诊断只投影白名单阶段、工具、错误码与异常类型，不记录底层异常正文、命令输入或源码。共享审批仅增加相同安全异常投影，STATIC、ASSISTED 的原三字段协议及完整授权链保持，AGENT 的证据、UNKNOWN 和单次授权边界保持。

本增量只选中十个直接相关定点场景，最终均通过、零跳过：原生证据/历史/响应失败阶段，两个默认模式的完整事实与安全失败投影，恶意消息与伪 context，快照变化、判决纠正、默认三字段成功，以及取消/截断/预算停止。各轮编译和测试中间失败原样保留，失败后分别只重跑八项与两项，没有重跑此前全部 67 项或模块全套。五文件限定格式检查通过，独立只读复核未见 P1/P2。补丁 SHA256 为 `a857e7e6d4b14c36d53ad8b8ec63da7e3f31a9ef0f690000cebb6e78fd493b79`，反向检查通过；三个自有文件可独立暂存，另外两个混合迁移文件通过精确增量补丁交付。该诊断增量尚不能确定旧 task-board-02 的根因，需要新的独立产品运行验证。

用量派生记录补充每个 provider 字段的已知与未知响应数量；该轮审批 reasoning 字段的十一项响应均为 UNKNOWN，已知子集求和为零不代表实际零消耗。下一轮复用 task-board-02 已验证的产品加密凭据，只复制安全配置，不启动旧任务或复用原计划。

新 task-board 独立部署、真实浏览器业务、非默认目录的真实服务器部署与重启恢复、其余七项目及全新服务器的冻结八项目批次均未验证。缓存及审批比例必须按对应轮次实际 provider usage 计算，未知值不得补零；历史调试用量不能混入冻结八项目批次。

数据库 v1 与历史恢复清理的本地验证、正式记录及剩余实际旧库处理见 [DEPLOYMENT-RECOVERY-HISTORY.md](DEPLOYMENT-RECOVERY-HISTORY.md)。目录布局本地通过与仍未完成的真实非默认目录验收见 [DEPLOYMENT-DIRECTORY-CONFIG-531.md](DEPLOYMENT-DIRECTORY-CONFIG-531.md)。
