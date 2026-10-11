# Agent 自主审批与按需 Skill

2026-10-12。状态：自主审批与按需 Skill 已实现并完成分阶段本地定点验证。原 task-board-02 与 task-board-06 已通过产品恢复结清为 ENDED/FAILED。最新 09 在候选创建、上传后因 agent-open 独立拒绝而结束为 UNKNOWN，平台保留 2337 条资源记录，其中 5 条未释放，正式 App 仍为空。该轮暴露的完整执行正文投递审批门已修复并通过 58 个不同本地定点场景，真实模型复验尚未启动；不能回填此前安装批准为完整独立审批通过。原 09 部分准备的窄补偿正在实施。业务自主闭环仍待实际证明，本文不将历史部署、本地通过或连接成功算作新业务验收。

## 目标与职责

部署模型独立选择产品工具或获授权的命令能力，识别来源、诊断失败、处理审批反馈并完成实际发布。监督者观察运行；确认产品能力阻塞后，通过代码或 Skill 修正通用语义，再以独立运行验证。不在运行中给模型步骤、最终命令或配置答案。

审批模型独立读取当前完整命令、可执行输入、来源、目标、回执及相关产品契约，判断允许、拒绝、需要哪些证据或是否应结束无进展的讨论。建议和候选命令只作为数据，不能授予权限；主模型采用建议后，实际完整命令和 stdin 仍须重新审批。完整身份、静态边界、当前路由、单次授权和 UNKNOWN 防重放由产品强制执行。

STATIC、ASSISTED 保持原审批协议。AGENT 的原生命令审批可以自主调用只读取证工具；高层源码补丁判断和实际 SSH 可执行输入审批仍是各自独立的边界。审批单次输出上限不得超过普通部署单次上限。

## 正式 Skill 与生效方式

正式调用目录：`src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/service/agent/skill/`。资源随部署 Mod 版本发布，不写入个人 Skill 目录，不从部署来源注册可信技能，也不跨版本热改原冻结任务。

| Skill | 调用职责 | 适用情形 |
| --- | --- | --- |
| `approval-discussion/SKILL.md` | 审批 | 重复请求、未结束的证据交换，或真实新证据可能解决原拒绝 |
| `approval-context/SKILL.md` | 审批 | 长请求审阅、旧工具批次被裁剪，或需要保存模型自身判断、未决问题和页游标 |
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

以上六个自有路径已本地提交 `dc27945d8fe3784034db86ff832c28a94f1e2d3a`，未推送。随后 `task-board-recovery02-02` 于 2026-10-12 04:16:40+08 结束，一项失败、零跳过，3262 项源码零变化，验证进程已退出。独立审批与命令执行已完成，安全诊断为 SUCCEEDED、exit 0、value PRESENT、truncated false、problemCode NONE，但结果既非严格 ABSENT，也未解析出完整 UNKNOWN 阶段。原任务仍 UNKNOWN，两个 FILE 阻塞保留。只读核对原 Agent 仍存在、无结清摘要或正式业务记录，平台资源总行数为 1052，配置计数保持；未删除未结数据。

本轮新增 2 个审批 provider 响应：input 13727、output 1587、合计 15314，cache hit 7936、miss 5791，捕获输入命中率 57.81%，2 项 reasoning 未知，输出上限均为 8192，没有新的配置探测。前后 usage 窗口均为 38 条但淘汰 2 条旧记录，按日志 ID 去重保留新轮用量，没有混入旧轮。stdout 长度、stderr 是否存在及协议失配位置仍未知，不能将命令 exit 0 视为资源结清；先做本地封装及观察缺口调查，避免盲目重复付费。

独立只读复核确认一处诊断盲点：固定系统工具失败可以同时输出完整绑定的 UNKNOWN stdout 和非空 stderr，旧 UNKNOWN 解析却要求 stderr 为空；本地 Bash 夹具也合并了两路输出。这不是第二轮远端原因的确认。已仅放开纯诊断 UNKNOWN 的 stderr 条件，仍要求 exit 0、未截断和完整绑定协议；ABSENT 方法原文未改，错误输出非空始终不能结清。新增双流字符长度、stdout 分类及固定失配枚举，共 11 项安全 token，未保存收到的字段值、正文或异常消息，也未改变原 journal 和正式业务记录。

`observation-shape-focused-01` 于 2026-10-12 04:35:35+08 通过 54 个不同直接场景，零失败/错误/跳过：本地 Bash 语法及 11 项受控阶段、3 项退出/截断、23 项协议形状反例、2 项实际 `SshCommandFrame` 与原 launcher 的双流验证、13 项 JDBC 安全诊断及一次严格成功结清幂等。四文件限定格式通过；3262 项源码零变化，本次进程已退出，无 API/SSH。辅助 runner 最初读取旧 selectors 结构被拒，未进入 Maven，错误记录保留后按实际新结构修正。此轮验证覆盖本地双流与恢复保护，不证明服务器工具或业务成功。

四份前像匹配 `dc27945d` 基线，新自有精确增量见 [DEPLOYMENT-AGENT-PREPARATION-OBSERVATION-SHAPE.patch](patches/DEPLOYMENT-AGENT-PREPARATION-OBSERVATION-SHAPE.patch)，SHA256 `a45601e867743f0ad3f0110820e0caf1cbef8f1d392b284ef11cba07a9e22f77`，反向及缓存应用检查通过。原观察、恢复及其他迁移补丁保留；六个自有路径已提交 `7619662a671154697fec57c2537d84a4f7d817b5`，未推送。

`task-board-recovery02-03` 于 2026-10-12 04:38:30+08 结束，一项失败、零跳过，3262 项源码零变化，验证进程退出。实际新诊断为 SUCCEEDED、exit 0、value PRESENT、truncated false、problem NONE，stdout 471 字符、stderr 69 字符、stdoutShape EXACT_UNKNOWN、stdoutMismatch NONE、unknownStage MOUNTS、unknownReason MOUNT_QUERY_UNCONFIRMED。这次完整绑定的 UNKNOWN 明确定位到挂载查询；未记录原始错误正文，未读取服务器具体 util-linux 版本。原任务仍 UNKNOWN、两个 FILE 阻塞保留，无结清摘要；只读原库资源总行数 1168、Agent 存在，正式业务计数仍 0，模型 2、服务器 1 保持，数据库字节未变化。

前后日志 ID 去重新增 3 项审批响应：input 26499、output 1646、合计 28145，cache hit 15616、miss 10883，捕获输入命中率 58.93%，3 项 reasoning 未知，输出上限均为 8192，无新配置探测。usage 窗口 38→37 且淘汰 4 项旧记录；不能通过窗口计数差求本轮用量，也不将原 task-board-02 或此前恢复轮混入本轮。这是失败的恢复观察轮，不是最终部署批次。

产品固定探针同时传入 `findmnt --list --raw`。在 [util-linux v2.37.4 官方源码](https://raw.githubusercontent.com/util-linux/util-linux/v2.37.4/misc-utils/findmnt.c) 和 [当前官方源码](https://raw.githubusercontent.com/util-linux/util-linux/master/misc-utils/findmnt.c) 中，两参数属于同一互斥输出组，`--raw` 自身关闭树形输出；此代码缺陷与实际 MOUNT_QUERY_UNCONFIRMED 阶段吻合。修复只移除 `--list`，保留 kernel、noheadings、raw、TARGET 与全部原挂载/回环/路径重查、授权和严格 ABSENT 门。没有向运行中的模型提供步骤或命令。

`findmnt-focused-01` 于 2026-10-12 04:46:51+08 通过 14 个不同直接场景，零失败/错误/跳过：Bash 语法、12 项受控工具/文件系统观察及一次 JDBC 严格结清幂等。夹具明确模拟官方参数互斥契约；重新注入旧冲突参数必须形成 MOUNTS/MOUNT_QUERY_UNCONFIRMED、不能结清。两文件格式通过，3262 项源码零变化，进程退出，无模型/SSH；不冒充真实 util-linux 或服务器验收。两份前像匹配 `7619662a`，新自有增量见 [DEPLOYMENT-AGENT-PREPARATION-FINDMNT.patch](patches/DEPLOYMENT-AGENT-PREPARATION-FINDMNT.patch)，SHA256 `1fb8cc4d4aadb88145271b64fb2a8aef9cbb1893dad3027661c51a245ba9a73e`，反向检查通过，所有旧补丁及原迁移改动保留。

findmnt 修复的四个自有路径已本地提交 `886ee037163b1d4fa0560509feb5e9f8fb4194ed`，未推送。`task-board-recovery02-04` 于 2026-10-12 04:49:12+08 通过一个真实产品入口场景，零失败/错误/跳过，3262 项源码零变化，进程退出。产品独立审批固定只读探针后取得严格 ABSENT，原任务由 UNKNOWN 结清为 ENDED/FAILED、无阻塞，随后释放恢复模式并重新打开产品正常启动通过；没有重放原准备计划，也没有 Linux 主机重启。

恢复后只读核对：原平台任务已归档、资源行 1168→0；原 Agent task 不存在、action event 0、formal execution 0。仅保留绑定原 operation `eb65b964-bb37-4bcf-b6b7-d6682dbb8def` 的 AGENT/FAILED 回执，摘要 447 字节，无 App/发布身份。十张正式业务表原本均为空且保持为空，模型 2、服务器 1 保持，审计未改变数据库字节。候选路径实际 ABSENT，因此没有通过手工删除释放磁盘；实际不存在的准备效果已证明并结清。此轮证明失败部署的补偿恢复和产品重开，不等于成功发布，也不证明旧 373 的正式应用或共享引用已重新验收。

本轮新增 2 个审批 provider 响应：input 13708、output 1097、合计 14805，hit 7936、miss 5772，捕获输入命中率 57.89%，2 项 reasoning 未知、输出上限 8192，无新配置探测。usage 窗口 37→38 并淘汰 1 项旧记录，按 ID 差集计算；不并入原部署、其他恢复或最终八项目批次。原失败日志与所有调查文件保留，尚未结清的其他旧记录不删除。

`task-board-05` 于 2026-10-12 04:59:05+08 结束为 FAILED，一项入口失败、零跳过，3262 源码零变化、进程已退出。独立新任务 `ea1e9353-52b1-4781-9d19-ad2fc9b947a6` 经安全截图配置成功连接，部署模型自行识别、提交计划并选择数据库准备。审批模型自行放行主机基线及 readiness，随后对安装请求 DENY：指出分段输入第 0 段中依赖事务 Python 约 16000–29600 未完整审阅。本轮没有预算耗尽；正式 App 列表为空。只读库确认平台任务已归档、资源 0，但 Agent/失败动作账本保留，无结清摘要或正式 execution；原 action 的 PREPARE_NOT_SENT 使 effectsSettled=true。十正式业务表保持 0、模型 2、服务器 1，数据库字节未变。辅助只读模板前两次仍绑定 02 身份而被拒，失败证据保留、字节未变；修正准确 05 身份后才完成核对。

94 个已捕获 provider 响应：input 1457133、output 30717、合计 1487850，hit 664832、miss 792301；捕获输入命中率 45.63%，审批 77 个响应共 1180170（捕获总量 79.32%），部署 17 个响应共 307680。reasoning 已知 8428、另 77 项未知；四项配置能力探测仍 UNKNOWN。209 个原生调用包含被拒调用。低命中及审批占比未用于中断，实际终止来自独立拒绝；这是失败开发轮，不并入最终八项目。

专项只读核对确认：日志中的正式 `submit_result` 只有两次 APPROVE 和最后首次 DENY，模型所称“此前三次拒绝”没有成立；其正文明确把历史工具批次 66/67/68 当作既有判决。`DeploymentNativeHistory` 对每个工具批次写入 `Previous decision N`，且在 42000 字符/65536 字节边界裁剪旧批次；审批每轮没有累计返回范围或模型审阅笔记。分段器校验完整还原的 UTF-8 字节、长度、SHA256，分页工具返回原始 facts 的 UTF-16 切片及 next/total/hasMore，尚未找到正文缺失或偏移算法错误。现日志不记录审批页游标，无法证明每个被称未审阅区段实际是否已返回；不能据此越过拒绝。

修复将历史标签改为中性的工具交互批次，明确批次号不是审批判决。单次审批内的 `reviewMemory` 保存成功返回的原请求/证据范围、正文 SHA256 与 UTF-16 长度，合并相邻或重叠范围但保留间隙；失败、空页和保护输入不计为已返回。范围、路由和字节均有上限，超限明确标记记录不完整，不拒绝继续取证。新增原生 `save_review_notes` 允许模型自主替换或清空有界笔记，严格绑定当前 review；错误绑定或溢出整体拒绝。笔记作为 USER DATA 回投，不是授权、正式记录或新事实，不跨审批请求复用，也不能重开 CLOSED。`approval-context` 是仅审批可选的可信产品 Skill，不规定业务阅读步骤；返回范围不等于模型已理解或完成安全审阅。监控只记录白名单页路由及游标，不输出正文或笔记。

`approval-context-focused-01` 于 2026-10-12 05:15:09+08 通过 25 个不同直接场景，零失败/错误/跳过，十个 Java 文件的限定格式检查通过。覆盖裁剪后范围及笔记保留、间隙/原文身份/容量边界、笔记原子拒绝与清空、跨会话隔离、保护输入、职责及伪 Skill、CLOSED/新事实、完整提交和授权、默认/共享预算、快照变化、原生历史及安全监控。3265 项源码运行期间零变化，验证进程全部退出，没有 API 或 SSH。独立只读复核的十二个文件 SHA256 前后一致，未见已证实的 P1/P2；本地受控模型通过不证明真实模型会选用笔记，也不证明成本下降。

十二个自有增量见 [审批上下文精确补丁](patches/DEPLOYMENT-AGENT-APPROVAL-CONTEXT.patch)，原始 SHA256 为 `c9d3ef59a7e131bd5691aab8ac350e7c9a35e62ee32deb0d0cd175361fdae391`，全部前像核对、反向检查及可独立增量的缓存应用检查通过。九个路径可独立暂存，另外三个已有混合迁移文件仅交付精确增量，不整体暂存原基线；所有旧补丁保留。新独立 task-board 完整部署、真实浏览器业务、非默认目录的真实服务器部署与重启恢复、其余七项目及全新服务器的冻结八项目批次均未验证。缓存及审批比例必须按对应轮次实际 provider usage 计算，未知值不得补零；历史调试用量不能混入冻结八项目批次。

审批上下文修复的十一项自有路径已本地提交 `ddd281304f2597919c4ab2fd6923f61db4c8ec76`，未推送；补丁入库 48979 字节，与工作副本原始 SHA256 一致，索引为空后才启动新任务。

`task-board-06` 于 2026-10-12 05:25:21+08 结束为 UNKNOWN，一项入口失败、零跳过，3265 项源码零变化，验证进程退出、临时凭据环境清除。新任务 `b1b0150b-3fd4-4e43-9fed-20de025fe0bd`、库 `agent-live-task-board-3oe9pway8g`、App 意图 `wtl-agent-task-board-3oe9pxuwfk`。部署模型自行读源码、提交计划并纠正数据库 assessment 参数，审批先放行宿主检查；数据库 readiness 审阅读完 script/stdin 后，两次源码分页上限拒绝已自行修正，随后 source_list 抛出 SecurityException，产品记录 EVIDENCE_READ/REVIEW_EXCEPTION，未返回可纠正工具反馈而终止整轮。没有耗尽预算，也没有进入长安装或正式发布。日志未保存该原始 subject，尚不能确定异常属于输入路径拒绝还是冻结来源边界变化；不得笼统吞掉安全异常。

只读 H2 核对原平台及 Agent 都仍 UNKNOWN，平台资源总行 389、原语义版本 4、唯一 PREPARE_EXECUTING，没有 readiness/完成/NOT_SENT 或结清回执，effectsSettled=false。正式 execution 及十张业务表均 0，模型 2、服务器 1 保持，审计前后数据库字节一致；未删除未结资源。原日志、报告、源码冻结、用量和只读审计保留，待产品入口完成真实恢复后再结清。

21 项已捕获 provider 响应：input 319557、output 14987、合计 334544，hit 214272、miss 105285；捕获输入命中率 67.05%。审批 8 项共 77098，占捕获总量 23.05%；部署 13 项共 257446。reasoning 已知 7881，另 8 项审批 UNKNOWN；四项配置探测的用量及传输调用数 UNKNOWN。39 次原生调用包含被拒调用。这是开发失败轮，比例没有作为中断原因，不混入最终冻结八项目；本轮未完成真实长请求上下文闭环或业务验收。

源码工具边界修复仅涉及三个文件。共享 `DeploymentSourceBrowser.relative` 保留唯一原路径准入规则，只把明确输入拒绝标记为固定消息的 `InputPathRejectedException`；审批仅在 source_list/source_read 原调用边界将该专用型转换为固定字段及契约反馈。通用 SecurityException、冻结成员/目录/内容变化仍终止，不捕获或替读；来源身份、分页、不存在成员及搜索参数反馈也不回显模型输入。STATIC/ASSISTED 所依赖的底层准入与完整性规则保持。原工具 schema 的空根目录说明另与默认 minLength=1 冲突，现仅 source_list.subject 设 minLength=0，source_read/search 仍为 1；模型可从实际契约自行选根目录。

源码反馈首轮 35 用例通过，零失败/错误/跳过，包括 20 次 list/read 路径纠错、原完整命令授权、来源权限、内容与目录变化闭合、取消/预算/快照和五个共享 Browser 反例。父复核发现根目录 schema 冲突后，只重跑受影响的两个方法、16 个参数化用例，全部通过；模型替身从反馈中实际 schema 的 minLength 选择空根，并用现有严格 validator 验证整组参数，read/search 空输入继续被拒。按场景去重仍为 35，不能相加为 51。三文件格式 write/check 均通过，后续两文件格式亦通过。第一轮没有紧邻 Maven 开始的完整冻结快照，只证明与 3265 原基线相比仅三项归属变更；后续 16 用例紧邻前后 3265 项零变化，格式 check 区间也零变化。全部验证进程退出，没有 API、Runtime 或服务器调用。

三份原始前像及全部中间证据保留，精确增量见 [源码反馈补丁](patches/DEPLOYMENT-AGENT-SOURCE-FEEDBACK.patch)，SHA256 `29babbea647da3bc94c4c2d09abdf9616f625298dc67c76adce61312d0f73b12`，前像、反向及缓存应用检查通过。两项可独立暂存；共享 Browser 已有迁移基线只交付精确增量，不整体暂存。06 的真实 subject 仍未知，不能把本地路径反例当作远端根因确认；原 UNKNOWN 须由产品实际恢复结清。

源码反馈四项自有路径已本地提交 `0f0adfc5ec42fb5edd8e8541b94c71804a1d8299`，未推送、索引为空后启动原任务恢复。`task-board-recovery06-01` 于 2026-10-12 05:38:58+08 通过一个真实产品入口场景，零失败/错误/跳过、3265 项源码零变化、验证进程退出。产品独立审批只读观察后结清原 06 为 ENDED/FAILED、无阻塞，释放恢复模式并重新打开正常产品；没有重放原准备计划，没有 Linux 主机重启。

只读核对原平台任务已归档、资源 389→0、原 Agent task/action event 清除、formal execution 0；仅保留同一 operation 的 AGENT/FAILED 摘要 447 字节，无 App/发布身份。十张正式业务表仍 0，模型 2、服务器 1 保持，审计字节未变。调查文件和其他未结记录保留；这是失败补偿结清与产品重开的证据，不是业务部署成功或旧373清理证明。

按前后日志 ID 差集新增三项审批响应：input 26148、output 1516、合计 27664，hit 12544、miss 13604，捕获输入命中率 47.97%，三项 reasoning UNKNOWN，输出上限 8192，无新配置探测。usage 窗口 21→24、没有旧基线淘汰；原 06 部署及其他轮次未混入。后续须使用全新 task-board 任务独立验证，不向运行模型提供步骤。

原 06 恢复的两份正式记录本地提交 `d40af821ad92d97506bf8ceed54f686e51cec6d5`，未推送。随后新独立 `task-board-07` 于 2026-10-12 05:49:14+08 结束为 FAILED，一项入口失败、零跳过、3265 项源码零变化，验证进程退出、临时凭据环境清除。新任务 `26646660-2018-4150-bea6-79da5d7cb19b`、库 `agent-live-task-board-3oscvh8e7g`、App 意图 `wtl-agent-task-board-3oscviqy0o`。模型自行识别来源、提交计划与数据库判断；审批已独立放行宿主和 readiness，首次自主调用 save_review_notes，并继续读取安装长输入。最终响应包含五个完整 evidence 调用，而产品把 size>4 与真正不完整响应合并为 NATIVE_BATCH_INCOMPLETE，未向模型返回可纠正反馈，终止审阅。这是已确认技术批次边界缺口，没有预算耗尽；尚未安装或发布。

只读平台已归档、资源 0；Agent FAILED 账本保留，语义版本 4，readiness 一次、PREPARE_DISPATCH_NOT_SENT 一次，原 PREPARE_EXECUTING 与 PREPARE_NOT_SENT 绑定同一 action `d9f60ae2db6e38cf17f7a497196d6731fb06add7b5f8e768501e61c98d71bd43`，effectsSettled=true。没有结清摘要或 formal execution，十张正式业务表仍 0、模型 2/服务器 1 保持，审计字节未变。没有手工删除失败账本或占用。原入口报告、用量、冻结及只读证据保留。

106 项已捕获 provider 响应：input 1678032、output 32914、合计 1710946，hit 761344、miss 916688，捕获输入命中率 45.37%；审批 85 项共 1306977，占捕获总量 76.39%，部署 21 项共 403969。reasoning 已知 11498，另 85 项审批 UNKNOWN；四项配置探测用量及传输调用数 UNKNOWN。原生调用含拒绝，notes 一次不等于已经完成真实长请求独立审批或业务闭环。比例没有用于中断，这是失败开发轮，不能并入最终冻结八项目。

完整超额批次现整批返回绑定原 ID、原顺序的固定安全反馈，实际技术上限 4 向模型自描述；该批次全部尝试扣减预算，读取、笔记、Skill 和 verdict 均不部分执行。模型可自行按契约重新组织调用。真正不完整、截断、坏或重用 ID、绑定变化及无法完整保留或向下次请求展示反馈继续闭合，原授权门与 STATIC/ASSISTED 默认路径保持；技术批次容量不是新的业务讨论轮数。

`approval-batch-feedback-focused-01/02` 最终覆盖 25 个不同直接场景，全部通过，无未结失败或跳过。首轮 25 项中 19 通过、6 断言失败；五项正确闭合但夹具误预期不在现有白名单的新诊断码，一项 length 正确返回 FAILED 而夹具误预期异常。保留原诊断白名单及 capability 分类，仅修夹具并复验两个失败方法的 12 个场景，不能把两轮相加。验证涵盖模型从超额反馈自主拆批后只授权原完整命令、混合 verdict/笔记/Skill/读取原子拒绝、独立与共享预算、取消/额度不足、原生身份/截断、字符/UTF-8/continuation/大量调用/可见性边界以及 CLOSED/快照/原始绑定。两轮紧邻 Maven 前后 3265 项冻结均零变化，两文件限定格式及差异检查通过，所有验证进程退出，没有 API 或服务器调用。

两份原始前像匹配当前 HEAD，精确增量见 [批次反馈补丁](patches/DEPLOYMENT-AGENT-BATCH-FEEDBACK.patch)，原始 SHA256 为 `152ab64817784a1a2c0378f720cf97b516b89b844ce424d8faa19ced1a0f8cf5`；前像、反向与缓存应用检查通过。首轮失败报告、全部中间证据、旧补丁和迁移改动保留。下一独立 task-board 产品部署尚未验证，本地受控模型通过不能表述为真实闭环成功。

批次反馈修复四项自有路径已本地提交 `31b9255cec403aa2b24380cc5c4c17822e259e4b`，未推送、提交后索引为空。`task-board-08` 于 2026-10-12 06:11:28+08 结束为 FAILED，一项入口失败、零跳过、3265 项源码零变化，验证进程退出、临时凭据环境清除。新任务 `52a974db-6dd7-4bb3-a689-050d1420a9fd`、库 `agent-live-task-board-3pa4slvwis`、App 意图 `wtl-agent-task-board-3pa4snee0o`。主模型自行识别、提交 plan 并选择数据库准备；只读复核发现本段重复 observation 与完整历史批次裁剪存在合理解释，实际已进入 PLANNED，未发现已证实的死循环，因此没有修改主流程。审批独立放行宿主/readiness，长安装审阅自主取证并保存两次笔记后，正式 DENY、discussionStatus=OPEN：段 0 与 506667 字符 managedHelper 的剩余可达效果尚未审完。没有预算耗尽，没有安装 dispatch 或正式发布；本轮未触发超额批次反馈，不能声称该修复已有真实超额调用复验。

准备阶段的实际未发送拒绝被包装为 `DeploymentAgentPrepareNotSentException`，Session 写未发送证明后还原原 ACCESS_DENIED，主模型没有收到 native `ALTERNATIVE_REQUIRED` 或后续讨论机会。已有原生自主拒绝反馈机制在命令等路径使用，目前正核对该准备路径与请求身份；不能通过新 operation 绕过 CLOSED 拒绝，也不能以不足证据替代完整独立审批。模型本次拒绝理由本身不是已证实错误，不能因拒绝直接放行。

只读库确认平台已归档、资源 0；Agent FAILED、语义版本 4、readiness 一次、PREPARE_DISPATCH_NOT_SENT 一次，原准备 action `3dada8396c80723b70453efd82e3b38248d0184d7da947ec817b54b4e9017895` 与 PREPARE_NOT_SENT 对应，effectsSettled=true。没有结清摘要或 formal execution，十张正式业务表仍 0，模型 2、服务器 1 保持，审计字节未变。失败账本、原报告及调查证据保留，没有手工删除记录或服务器文件。

63 项已捕获 provider 响应：input 1001358、output 42170、合计 1043528，hit 558848、miss 442510，捕获输入命中率 55.81%；审批 38 项共 534289，占捕获总量 51.20%，部署 25 项共 509239。reasoning 已知 24256，另 38 项审批 UNKNOWN；四项配置探测用量及传输调用数 UNKNOWN。原生调用含拒绝；本轮比例未作为中断理由，不并入最终冻结八项目。本地现有浏览器验收依赖准备的默认 npm 缓存 EPERM 失败及工作区缓存重试通过、隔离 Chromium runtime 安装通过均已保留；并未访问正式应用页面，没有浏览器业务验收通过证据。

准备阶段现只在原安装审批捕获的同一失败对象、原任务/组件/来源匹配、严格未发送证明已经落账，且原 API 为绑定本任务的 native DENY 时，才把拒绝作为 `ALTERNATIVE_REQUIRED` 数据交还主模型。只有这条确认未发送的拒绝释放组件准备标记。INVALID、UNKNOWN、CANCELLED、发送 guard、执行和 postprobe 仍沿原错误路径；不能靠同名错误获得未发送证明。

安装 bootstrap nonce 属于原 RemoteTools 的不可变身份，每次调用仍创建新的 mutable attempt，重新生成并完整审查当前实际请求。没有保存旧业务参数或 grant 供重放。主模型可选调用 EXCLUSIVE `review_response{reviewBinding,message}`，向原审批实例对应请求的最新 DENY/OPEN 保存一条有界、可替换或清空的回复。未知、外任务、已被替代及 CLOSED 绑定拒绝；超过 3000 UTF-16 字符单元或 6000 UTF-8 字节的替换整体拒绝。保存回复不触发审批或服务器动作，下一当前提案仍完整独立重审。

回复仅以 USER `MODEL_AUTHORED_DATA`、authority NONE 进入后续 discussion；回复及 ACK 不改变 meaningful facts 或 progressRevision，同事实 CLOSED 不被重开。产品 Skill 明确区分审批模型已经能够读取但尚未审完的页面，与确实不可获得的证据；主模型没有审批专用取证工具，不能编造 helper 正文。模型自主选择取证、回应、调整当前提案或结束，不规定读序、讨论轮数或必然放行。

`agent-prepare-review-01` 最终去重 25 个直接场景全部通过，无未结失败或跳过：审批 9、协议/请求呈现 2、新 H2 集成及原拒绝身份 3、原安装边界 11。受控 H2 场景验证 OPEN 回应后当前提案重新审批、完整 APPROVE 前零安装发送，以及 CLOSED 不因回复、相同计划、重复 readiness 或 nonce 重开。它们使用本地受控模型响应与传输，不是实际 API 模型部署成功。11 个生产/Skill 文件独立只读复核未见具体 P1/P2，读取前后摘要一致。

全部中间失败保留：01 参数启动失败，02 测试编译缺导入；03 的 24 项为 11 通过/13 失败；04 的 15 项为 8 通过/7 失败；05 测试目标清单方法缺布局参数而编译失败；06 仅剩余 7 项中 5 通过/2 失败；07 仅最后 2 项全部通过。夹具依次校准保存服务器前提、native 六字段和 currentRequest、完整安装目标、任务体实际异常与授权代理；未弱化生产审批或证明。旧边界按原逻辑身份去重，不将拆分后的参数序号和重跑次数相加。每轮紧邻前后冻结 3266 项、零变化，12 个自有 Java 文件最终格式检查通过；未运行模块/全项目测试，没有新增 API 或 SSH 用量。

15 文件自有增量见 [准备审批回应精确补丁](patches/DEPLOYMENT-AGENT-PREPARE-REVIEW.patch)，原始 SHA256 `05628cd72f000ece9e1451cb3efd348377c03eb67d1634f2eddfda1186f1828c`。全部前像与保留的 08 源码冻结匹配，反向及缓存应用检查通过；7 项可独立暂存、8 项混合原迁移仅交付自有补丁，旧补丁与证据不改写。原 08 FAILED 账本继续保留；下一新任务须独立证明实际模型闭环与业务成功。

准备审批回应的九项自有路径已本地提交 `daa231c77dedbbc7e07d11db25370ec345b46fc2`，未推送、索引为空后才启动 09。

`task-board-09` 于 2026-10-12 07:00:08+08 结束为 UNKNOWN，一项入口失败、零跳过、3266 项源码零变化，验证进程已退出、临时凭据环境清除。任务 `191bab33-4c43-400a-816d-f4ba78f4b785`、库 `agent-live-task-board-3q8om5idi8`、App 意图 `wtl-agent-task-board-3q8om79rww`。主模型自行识别、提交计划及数据库判断；审批自选 Skill、保存笔记并给出安装批准，产品实际安装 helper、创建候选、上传 26283 字节。之后 agent-open 被独立 DENY，理由涉及原 SOURCE 事件的归档摘要、快照来源摘要及业务版本绑定。摘要是否因实际角色不同而被误判仍待核对，不能强改原身份或据此要求放行。此时已有实际准备效果，不能使用此前仅适用于确认未发送拒绝的反馈路径。

只读审计确认平台原任务及 Agent 为 UNKNOWN、语义版本 4、资源记录 2337；只有原 action `81921ee8aef135d428d133826bebdcb4f31a62c2dedf132e86b62b886167f8ac` 的 PREPARE_EXECUTING，没有 CANDIDATE、PREPARE_RESULT、NOT_SENT 或结清回执，effectsSettled=false。正式 execution 及十张业务表均 0，模型 2、服务器 1 保持，审计前后数据库字节一致。第一次辅助审计因 PowerShell 5.1 的字符串 Split 重载在启动前拒绝，未打开数据库；修正辅助脚本的精确标记计数后，由 PowerShell 7 完成只读核对。UNKNOWN、资源及调查文件全部保留，候选必须由产品原任务恢复处理，不能套用 02/06 的不存在结论或手工删盘。

独立只读复核确认一个具体 P1：安装审批窗口为原日志 873–1108 行，没有安装 stdin 或完整 completeExecutableInput 的读取请求，managedHelper 506667 字符只请求了 `[0,8000)`；初始长正文仅为 descriptor。安装器写出 helper 后立即调用 probe，installation 也明确不属于 installed-call trust，不能以契约摘要免除正文审阅。日志 1111 行真实授权、1113 行执行成功，说明缺口已经进入产品执行路径。请求元数据不证明成功返回或模型理解，但结合实际正文投递入口可以排除其余安装 helper 已完整投递；故该 APPROVE 不满足现行完整独立审批要求。这不表明 helper 恶意或服务器受损。六个核心审批、契约和 observer 文件与该轮冻结摘要吻合，复核没有代码、API 或 SSH 操作。后续修复仅把真实正文投递作为 APPROVE 的必要条件，安全判断仍由独立审批模型完成。

补充只读源码与原库核对证实摘要对象刻意不同：SOURCE.action 是实际压缩归档 SHA，SOURCE.detail 是平台快照清单 SHA，成功 agent-open 才产生第三种保护树 revision；request.businessRevision 来自模型路由配置摘要，与 BUSINESS.md 的数字版本没有等值关系。原 SOURCE 事件 4、PLAN_APPLICATION 事件 98 和 PREPARE_EXECUTING 事件 118 各唯一、先后次序符合原绑定；SHA256(task:component:snapshot) 与原 action 完全匹配，产品推导的候选也与实际创建、上传回执相同。该验证没有证明当前远端仍存在或可删除，仅补上安全原身份实证。实际保留 2337 行中仅 5 行未释放：两个原 source-snapshot、MOD、SCHEMA、SERVER；两个快照均只有原 task 一个引用，已释放 CONNECTION 1149 行和 CUSTOM 1181 行不能当作当前占用。第一次辅助投影把合法 static-live 资料标识误当 UUID 而失败，原库字节保持；仅校准辅助标识后第二次只读投影通过，失败文件保留，没有 Runtime、API、SSH 或数据库修改。

106 项已捕获 provider 响应：input 1553404、output 51844、合计 1605248，hit 942464、miss 610940，捕获输入命中率 60.67%。审批 81 项共 1081362，占捕获总量 67.36%；部署 25 项共 523886。reasoning 已知 22429，另 81 项审批 UNKNOWN；四项配置探测用量及传输调用数 UNKNOWN。原生调用包含被拒调用，没有预算耗尽。用量、原报告、源码冻结和只读审计保留；这是开发失败轮，不混入最终冻结八项目，也没有浏览器业务成功证据。

完整正文投递门仅在 AGENT 原生 APPROVE 前生效。每次 review 从实际 request 构造独立正文身份，经既有 InstallerEvidence.verify 无损验证的结构化 literals/sources 与原 stdin 为等价路线；相同原文的 textual evidence/request 分页共享范围。短原文确实内联时计入，descriptor 不计。成功工具结果只有真实进入成功模型请求后才累计；错误、裁掉的尚未投递页、模型笔记或摘要不能填补缺口。范围容量不足只可能保留缺页，不能制造完整覆盖。保护输入继续保持 opaque，不解码给模型；非空 stdin 缺分类或原文重建矛盾闭合。

缺口返回同 reviewBinding 的 EXECUTABLE_INPUT_NOT_DELIVERED、真实范围和等价证据路线，零授权；模型可自行选择继续取证或 DENY，不自动读页、不规定步骤或讨论轮数。完整投递只是必要条件，不证明当前理解、安全结论或权限。最后仍要独立 APPROVE，并通过原来源、请求、回执、路由及取消检查；CLOSED 及默认三字段审批边界保持。审批基础策略说明这一门槛，按需 Skill 的发现和调用机制不改。

`approval-complete-input-01` 于 2026-10-12 07:35:28+08 完成，按测试类/参数化场景去重 58 项全部通过，零失败/错误/跳过。覆盖长安装只读前缀/单 helper 不够、真实完整分页后独立授权、混合等价路线、短内联、间隙/容量/错误页/笔记/历史裁剪、opaque、来源和回执变化、默认/共享预算，以及真实本地 H2 Session 的 OPEN 绑定回应与 CLOSED、原安装 guard/execute/probe/错误身份边界。六个 Java 限定格式检查通过，五轮测试及终态冻结各 3268 项、零变化；未运行模块或全项目测试、真实 API、Runtime 或 SSH。

中间记录全部保留：01 因新测试 lambda 的 checked exception 编译失败，零用例执行；02 为 36/37 通过，修复同原文等价路由的范围合并后仅复验失败及直接受影响组，03 为 23/23；04 为 20/21，OPEN 夹具把安装前零 grant 断言误用到安装成功后的 probe 分页，只校准该断言窗口，05 仅此场景 1/1 通过。不能把重跑次数相加。默认 CIM 拒绝保留为未知，实际正常用户只读核对 queryStatus=OK、相关进程 0，用户原有 IDEA 保留。

七项自有增量见 [完整正文投递精确补丁](patches/DEPLOYMENT-AGENT-APPROVAL-COMPLETE-INPUT.patch)，SHA256 `f41fdfe1303257c9821277c8566170f64539184dd4573b44cb258113ee3adbbd`，已有 raw 前像均匹配 09 冻结，两项新文件原不存在；反向及缓存应用检查通过。六项可独立暂存，一个原安装边界测试的混合迁移只交付精确自有补丁，旧证据及补丁保持。09 的 UNKNOWN 和未释放资源仍保留；修复没有替代原候选的实际恢复。

数据库 v1 与历史恢复清理的本地验证、正式记录及剩余实际旧库处理见 [DEPLOYMENT-RECOVERY-HISTORY.md](DEPLOYMENT-RECOVERY-HISTORY.md)。目录布局本地通过与仍未完成的真实非默认目录验收见 [DEPLOYMENT-DIRECTORY-CONFIG-531.md](DEPLOYMENT-DIRECTORY-CONFIG-531.md)。
