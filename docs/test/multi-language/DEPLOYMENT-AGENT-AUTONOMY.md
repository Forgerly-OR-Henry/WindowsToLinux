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

用量派生记录补充每个 provider 字段的已知与未知响应数量；该轮审批 reasoning 字段的十一项响应均为 UNKNOWN，已知子集求和为零不代表实际零消耗。诊断增量已本地提交为 `ba973cc9df2877eb614038dd2b0d9a002a97d5a6`，未推送。

task-board-03 尝试从 task-board-02 的产品加密配置复用凭据。该测试夹具打开旧 PlatformRuntime 读取配置，随后关闭时被未结任务保护拒绝，返回 `Unfinished platform tasks require reconciliation before shutdown`，未进入 SSH 或模型部署。该轮一项入口检查错误、零跳过；3257 项源码冻结零变化，provider 新增用量零，不存在缓存或审批比例。只读复核旧任务仍为 UNKNOWN、无结果、保留 715 条资源行，没有执行旧 Mod 恢复或清理。测试关闭错误不构成 Agent 自主审批失败，也不授权绕过未结任务保护。

task-board-04 改回本轮已经验证的本地截图安全输入路径：两 OCR 引擎一致后，只经临时子进程环境进入原产品加密保存入口，不打开旧 UNKNOWN 运行库。使用新的任务和应用身份独立部署，仅 task-board，监督者不向模型给步骤，源码保持冻结。模型自行结束重复回读，提交并通过计划评估，首次工具 readiness 审批自行 APPROVE、产品命令成功。后续工具准备审批重复请求可执行输入，但连续收到 `EVIDENCE_REQUEST_REJECTED / Invalid evidence member`，最终以 `reviewStage=RESPONSE_CHECK`、`reviewCode=TASK_TOOL_BUDGET_EXHAUSTED`、`reviewFailureType=IOException` 自行停止，任务 `19b4e5f8-a7b5-4b54-b4fe-cf57acbcf328` 返回 FAILED；正式应用列表为空，没有业务部署成功。

该错误文本在 request 读取分支对应 `part` 文本成员检查：原实现仅接受 script/stdin，native schema 却没有这两个枚举值或对应说明；长正文的 pointer 描述也没有标明应使用 evidence 工具读取。实际 part 原文没有打印，不能猜测它具体填了哪个字段。修复限定为 AGENT 原生只读取证工具的自描述参数、分页路由和安全错误反馈，不以放宽授权或单纯增加次数代替这个已证实的契约缺口。

工具契约增量仅改 `DeploymentAgentApproval` 与其定点测试：request 明确枚举 script/stdin，正文 descriptor 携带 evidence/pointer 路由，分页说明 UTF-16 单位及 value.next，拒绝反馈仅提供安全字段、错误码和当前契约。动态模拟模型依据实际 schema、反馈与游标自行纠正调用，读取原完整请求后独立提交判决。9 个不同定点场景通过、零跳过，覆盖非法 part、指针与游标、保护 stdin、快照变化及默认三字段；两文件格式检查通过。首轮七个夹具包裹断言失败及仅失败组重跑证据保留。共享工具上限 160、授权、判决及 UNKNOWN 保护未变，验证进程全部退出。这是本地工具验证，尚未证明新的真实部署成功。

task-board-04 一项入口检查失败、零跳过，3257 项源码冻结零变化，验证进程退出。58 次已捕获响应的实际 provider 输入 826495、输出 46017、已知合计 872512，其中审批 256516；输入缓存命中 542080、未命中 284415。仅已捕获部分的缓存命中率 65.59%、审批占比 29.40%；四项配置探测的调用数及用量仍 UNKNOWN，另有 26 项响应的 reasoning 字段 UNKNOWN。该失败轮不得算作最低交付达标或混入最终冻结八项目批次。

结束后以确切 task-board-04 库和原任务进行 H2 只读核对：平台任务已归档、资源行 0、正式 execution 行 0，Agent 账本含绑定原 action 的 PREPARE_NOT_SENT，effectsSettled=true；失败账本及调查证据保留。数据库 SHA256 前后一致，核对没有启动 Runtime、模型或 SSH。对 task-board-02 的另一份只读核对确认持久 execution_semantics_version=4、唯一 PREPARE_EXECUTING、没有 readiness/完成/NOT_SENT 事件；原任务仍 UNKNOWN、715 条资源行保留。这只确认现态与语义版本，尚未取得真实服务器结清证明或释放该任务资源。

用户补充授权：任务若能安全接续则通过产品入口继续；无法接续则完成所属操作的补偿、释放可安全释放的临时磁盘占用，减少重装依赖。业务部署失败和执行结清 UNKNOWN 分别记录；共享工具、已发布应用、正式业务身份、配置与持久数据继续受保护。Token、上下文及其他预算可根据实际闭环需要自主调整并记录，不再为同一授权询问；审批单次输出仍不得超过部署普通单次上限。

预算增量将 AGENT 共享工具尝试额度提高至 512；主模型仍为 80 次 decision、每批最多 4 个，事实投影最多 320 次并受剩余轮数约束。11 个不同直接场景通过，其中真实本地工作流执行 320 次不同来源读取后在 80 轮停止；默认无作用域额度保持 80，不回填已用次数或跨任务保留调用身份。所选 baseline 工作流已通过：受控模型根据实际原生 schema、正文路由和游标读取完整审批事实，严格核对当前 Agent 安装请求、宿主授权、发布和清理证据。旧平面协议、旧 launcher、遗漏 currentCommandEvidence，以及 JSON LongNode/IntNode 夹具失败均保留；未放宽实际命令、身份或 canonical 摘要断言。预算范围内最后一次仅重跑该 baseline，未重复前十项。

恢复链核查另发现原生审批缺少外层主循环作用域时，显示的默认 80 次余额不会被 consume 扣减。修复使这类独立审批自行持有默认 80 次作用域，结束或失败时释放；已有共享 512 次作用域继续扣减，内层不创建新额度、不关闭或回填外层预算。4 个直接场景通过、零跳过，覆盖独立耗尽前停止且零授权、共享额度连续扣减、取消/截断及既有预算停止、回执变化拒绝；三文件格式通过。同期所选旧 baseline 夹具失败另行记录，不与这四项混算。该修复没有增加业务讨论轮数规则、修改 schema v1、授权或输出上限，也没有调用真实 API/SSH。

预算及独立审批增量共有八个自有文件，限定格式检查通过，保存于 [预算精确补丁](patches/DEPLOYMENT-AGENT-BUDGET.patch)。四个可独立索引增量与四个混合迁移增量分别核对；混合文件只交付精确增量，不整体暂存原基线。最终 baseline 所在 preparation-focused-01 联合轮有其他恢复夹具失败，不能称该联合轮全部通过；其 baseline 一项通过、零跳过，3262 项源码冻结零变化。没有新增 provider 或真实 SSH 调用。

本地提交 `efd3d14623e8ac2626ffd6a224243b098a950544` 包含六个自有暂存路径，未推送。预算补丁原始 SHA256 为 `770de36dd096792e58d76fe1f08cd4bf506d878a99b00f886acce29d1854f4b8`，反向检查通过；原自主审批及诊断补丁字节保持。

02 的产品窄恢复分支已实现并完成只读复核，未见明确 P1/P2：仅语义版本 4 的唯一首准备、完整原身份及无后续写入进展可进行固定系统元数据观察；候选路径、精确构建单元、挂载与 loop 全部确认为不存在后，事务追加 PREPARE_RECONCILED_ABSENT。它不伪造 NOT_SENT、不安装 helper、不执行项目、不删除同名对象；后续命令、数据库、秘密资源、能力回执和 FILE 释放确认仍须结清。精确幂等仅接受原前缀加唯一末回执，不能掩盖后续账本事件。

恢复定点最终去重 39 场景通过、零跳过：新增 H2/原生模型受控 27、原准备保护 3、本地 Bash 9。首轮缺少原布局的两项旧夹具失败、默认 Bash 路径的九项跳过、第二轮悬空链接早退出的管道错误均原样保留；只复验失败或直接受修复影响的范围，没有重复已过的 H2、工作流或身份场景。Bash 使用明确的 root/系统元数据替身，悬空链接为 Git Bash 模拟链接，并实际检查 -L、readlink 和目标不存在；不是 Windows 原生链接权限或真实 Linux 验收。三轮各冻结 3262 项，运行中零源码变化，验证进程退出。

恢复增量包含十个自有文件、五个新增路径，限定格式检查通过。五个已有混合迁移文件只用 [准备恢复精确补丁](patches/DEPLOYMENT-AGENT-PREPARATION-RECOVERY.patch) 交付，原基线保留；补丁原始 SHA256 为 `1adc10f2be3c3a7171e931f14f353c5ac269afb5417b81ad83e2466f20da34d8`，反向检查通过。执行语义版本 4 只用于确认原动作边界，数据库仍为版本 1，没有版号迁移。

原 02 的真实产品恢复 `task-board-recovery02-01` 于 2026-10-12 03:59:08+08 结束：一项入口验收失败、零跳过，仍为 UNKNOWN，两个原 FILE 阻塞保留。产品独立审批已完成固定只读 `deployment.agent.preparation.absence.v1` 的审批并取得宿主授权，但没有提交严格 ABSENT 证明或结清摘要。运行期间 3262 项源码零变化，Maven 进程已退出。原冻结准备计划没有重放；没有创建正式 App，也没有删除未结资源。

恢复前后只读核对分别为 715 和 955 条平台资源行；新增资源来自本轮产品取证，不能将总行数当作活动阻塞数。原 Agent 仍存在，正式 execution、App、发布、单元及持久业务表均空，两个模型及一个服务器配置计数保持。只读调查时数据库字节未变。该轮还未验证成功事务结清或后续正常启动。

本轮 provider usage 按恢复前后日志 ID 去重，新增 9 个审批响应：已知 input 130486、output 1045、合计 131531 Token；cache hit 69248、miss 61238，已捕获输入命中率 53.07%，9 项 reasoning 用量未知，单次输出上限均为 8192。未请求新的配置探测。100 条日志窗口淘汰了一条旧 usage，不能用前后条数相减计费或将缺失记录补零；原 02 部署用量未混入。本轮只有恢复审批，不能以其审批占比评价最终部署批次。

实际阻塞尚未定位：此前恢复实现丢弃非严格 ABSENT 的命令结果，旧诊断不足以区分传输失败和 PATH/BUILD_UNIT/MOUNTS/LOOPS 等检查。AGENT 专属诊断已补齐，UNKNOWN 协议严格绑定完整原身份并限定阶段及原因枚举；产品只记录 outcome、problemCode、value、observedExit、truncated、unknownStage、unknownReason 七项安全 token，不保存 raw 输出、异常消息或原操作的新进展。完整 ABSENT 字节、准入条件、独立审批与单次授权保持。未根据 UNKNOWN 猜测服务器原因或放宽结清条件。

`observation-focused-01` 的 32 个不同直接场景通过、零失败/错误/跳过：实际 Git Bash 的 11 项阶段反例及成功路径、9 项完整协议反例、10 项传输/结果/恶意文本/截断/日志故障，以及一次真实本地事务结清和重复收尾。四文件限定格式通过；3262 项源码冻结零变化，全部本次验证进程已退出，没有 API 或 SSH。四份前像均匹配已提交基线，自有精确增量见 [DEPLOYMENT-AGENT-PREPARATION-OBSERVATION.patch](patches/DEPLOYMENT-AGENT-PREPARATION-OBSERVATION.patch)，SHA256 `aad14c776bba81875a943c159a9e6ef67966b1737d90cc2cd5f853edc0da8430`，反向及缓存应用检查通过。原恢复补丁保留；这不是全项目或真实服务器验收。下一轮须由产品入口取得实际结果后再决定修复。

新 task-board 独立部署、真实浏览器业务、非默认目录的真实服务器部署与重启恢复、其余七项目及全新服务器的冻结八项目批次均未验证。缓存及审批比例必须按对应轮次实际 provider usage 计算，未知值不得补零；历史调试用量不能混入冻结八项目批次。

数据库 v1 与历史恢复清理的本地验证、正式记录及剩余实际旧库处理见 [DEPLOYMENT-RECOVERY-HISTORY.md](DEPLOYMENT-RECOVERY-HISTORY.md)。目录布局本地通过与仍未完成的真实非默认目录验收见 [DEPLOYMENT-DIRECTORY-CONFIG-531.md](DEPLOYMENT-DIRECTORY-CONFIG-531.md)。
