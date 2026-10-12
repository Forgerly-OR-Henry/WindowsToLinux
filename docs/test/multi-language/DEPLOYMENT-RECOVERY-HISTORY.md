# 部署中断与恢复记录生命周期

本轮状态：数据库生命周期改造及直接相关本地验证已完成；按用户要求暂停 Agent 推进。数据库版本保持 **1**，没有版号迁移。此前目录配置、原 task-board 恢复和 file-transfer 结果仅作已有依据，不计为本轮验收。

## 数据边界

部署中及待恢复信息属于临时执行账本。副作用发出前仍须记录原任务、原操作和回执身份，以支持进程突然中断后的核对；这些记录不表示部署完成。

原恢复真实结清后，按原操作清除可删除的动作事件、Agent 状态和执行明细；再次发生中断，由新的操作记录实际状态。正式 App、组件、配置和发布记录只在实际验证可运行后的业务提交中保存。产品后续 STOP/DISABLE 不删除这些正式业务身份。

恢复结清与原部署成功分别判断：补偿结清可以保留原 FAILED/CANCELLED 结果，不创建已完成 App。未结清 UNKNOWN、失败、拒绝、回执和占用保留。必要结清凭证只保存有界结果和原身份，不保存命令正文、来源全文或恢复计划。

## 已核实的数据库依赖

改造前的 deployment_release.execution_id 通过外键及业务校验依赖 deployment_execution，直接删执行行会破坏发布历史。正式发布需先承接必要来源身份；临时账本删除后仍须验证发布、服务器和资源绑定，不能跳过校验。相同发布摘要可能被后续成功任务复用，原创建身份不能覆盖。

平台归档与 Mod 业务清理是两个事务。结清凭证必须支持“Mod 已清理、平台尚未结束”后的重启，并在执行原布局读取、SSH、模型或副作用之前识别已结清操作。提交确认丢失保持 UNKNOWN，通过原身份重读判断，不声称一定回滚。

## 本轮实现

用户明确要求数据库版本保持1，不做版号迁移。新结构统一使用 v1 的 SQL、校验与列清单；`DeploymentSchema`、`DeploymentSchemaIdentity` 和 manifest 均为1，没有迁移接线。本轮曾提出的 v2 源码、资源和构建产物已撤除，原文件及相关失败、中止轮次保留在调查证据中，不作为最终 v1 验收。正式 `deployment_release` 增加严格格式的 `origin_payload`，保存创建任务、执行、模式、服务器、审阅修订和发布身份，解除其对临时执行行的外键依赖。相同发布摘要被再次使用时保留原创建来源；临时行仍在时交叉验证来源，清理后继续验证正式发布、图及资源绑定。

新增 `deployment_operation_receipt`，每条载荷上限8192字节，只保存已结清的原身份、结果、正式发布关联和证据摘要。它不接收部署中或 UNKNOWN 草稿，也不承载命令、来源全文或可重放计划。Agent 结清时在一个事务中保存凭证，再按精确原任务删除事件、Agent 状态与执行行；STATIC/ASSISTED 不自动接入 Agent 清理流程，共享的正式来源校验仍适用于各模式。

Agent 恢复最早先查结清凭证；原资源仍占用时返回 UNKNOWN。历史维护入口使用平台新增的 `TaskService.findArchived` 只读归档接口，要求真实当前所属任务、精确插件和版本、原任务与资源行均已清除、归档未被裁剪且结果完整。该接口不创建旧上下文，也不赋予重放权限。平台证明缺失或不完整时保留明细。

历史维护产品服务入口为 `DeploymentAgentPageService.clearSettledHistory(UUID)`。它按原任务清理本地记录，不执行模型或服务器命令；平台归档的恢复 SUCCEEDED 不会覆盖原部署 FAILED/CANCELLED。新的部署、管理或中断操作继续使用新的操作身份。本轮没有扩展为同一个已结束任务反复续跑。

没有自动版号迁移、原地补造正式身份或结构校验失败后删库的通道。现有运行库尚未更新或清理；本轮最终测试使用新结构的隔离 v1 库，原373库与外部调查文件继续保留。旧结构即使同样标注版本1，也不能冒充新结构通过校验。

## 本轮只读库存核查

通过产品 DPAPI 安全 API 在内存取得数据库访问材料，使用只读 H2 连接，仅输出版本及计数，不读取或输出 API key、SSH 密码或配置内容。受限进程的 DPAPI 失败及正常用户权限重跑均留证。

- 已核查191个运行/历史测试库：87个匹配当前平台 DDL，104个不匹配。
- 默认运行库匹配当前 DDL，平台任务、Agent 状态、执行、正式 App 和发布均为0；当前配置无需删除。
- 原 task-board 373l1qz900 库的平台 DDL 匹配，平台任务0、Agent任务1、事件332、执行1、正式App1、发布1、单元2。平台结构匹配不等于其旧部署 Mod 结构符合本轮新 v1 契约；它需要保留业务的结构与归属核对，不能当作可丢弃的空旧库。
- 旧不匹配库中2个仍有未结平台任务。其他旧测试库也可能含历史日志与配置；新测试使用新隔离状态，不自动在启动失败时删库。

证据位于 `.ai-workspace/agent-live-evidence/agent-recovery-history-20261011/`。没有更新或删除这些已有运行/历史库；隔离测试库内的插入、故障注入和精确清理属于下述本地验证。现有旧结构与新 v1 结构不相符时仍严格拒绝，启动失败不会触发删库。

## 直接相关本地验证

最终58个不同场景均通过，无跳过；没有执行全模块或全项目测试。部署 Mod 的最终 v1 轮执行53项，首次51通过、2失败；仅修改故意未知发布格式用例的错误读取预期，复验该两参数均通过。原始十张正式业务表全列及原/候选执行记录保持不变，未知载荷仍保留，严格业务读取和结构验证继续拒绝。另5项平台归档接口用例此前分两次通过；平台实现未因 schema 回退改动，这5项沿用本轮已通过证据，没有重复执行或计入已撤除的迁移用例。

| 验证范围 | 不同场景数 | 最终结果 |
| --- | ---: | --- |
| Agent 清理、五处事务写入/提交故障、确认丢失、失败补偿、未结清保护、真实本地子进程崩溃与重启 | 15 | 通过 |
| STATIC/ASSISTED/AGENT 正式来源、部署中无正式 App | 3 | 通过 |
| v1 新建/校验、Mod 隔离连接和二次启动 | 5 | 通过 |
| 正式发布、执行提交回滚、重复发布及历史身份 | 24 | 通过 |
| 原非默认目录身份及 Agent 原布局、审阅、清理与健康依据代表场景 | 6 | 通过 |
| 平台归档精确所属、资源结清、重启、裁剪/截断与矛盾证明 | 5 | 通过 |

`final-local-checks.json` 以测试类及参数化用例名称去重，并保留每轮报告来源；不得把失败重跑次数相加为覆盖数。先前接口错误、恢复夹具错误及 v2 迁移夹具失败完整保留。v2 第三轮因用户要求版本1而停止，未作为构建成功。最终 v1 的 root `clean` 被既有 IDEA 格式缓存权限阻止，改为只清理部署 Mod 构建目录后重编译；另一次重跑参数被 PowerShell 拆分，未进入测试，修正参数后完成两项复验。这些启动失败也有独立日志。

当前31个自有代码、资源及测试增量中30个受支持文件的 IDEA 格式检查通过，列清单不在该格式工具范围。两个最终 v1 测试轮各自冻结1759个源码/构建配置文件，运行期间零变化；复验之间只修改上述失败测试。完整补丁反向检查通过。普通暂存差异检查通过；补丁正文另行检查，空白上下文行的单空格是 unified diff 语法，保留。正式补丁按仓库文本规则保存为LF，原字节前像另行保留。

本轮部署、审批产品 provider 调用为0，没有新增产品用量未知项；缓存命中率和审批占比没有新的可计算批次。原跨版本调试用量继续按历史证据保留。

## 提交隔离与保留范围

初始 main HEAD 为741ed6b1，索引为空，工作区已有大规模未提交迁移。仅暂存8个本轮新文件和10个可独立应用的索引增量；6个已跟踪上下文混合文件及7个既有未跟踪迁移文件的自有变化通过[完整补丁](patches/DEPLOYMENT-RECOVERY-HISTORY.patch)交付，不整体导入原迁移基线。补丁对应31个实际增量及逐文件 before/after SHA，见 `final-owned-manifest.json`；本记录及该补丁随本地提交保存，默认不推送。已验证的是当前实际工作区，不声称仅检出该提交就能构建正在迁移的全仓。

原字节、调查记录、失败报告和撤除的 v2 证据均保留。`before-manifest.json` 有一项明确例外：`ModTest.java` 的前像通过仅移除本轮已知新增表清单 token 重建，保留原有迁移改动；它不是启动时的原字节快照，不应作此声称。其他已保存前像均核验摘要。

## 后续推进与未验证范围

数据库阶段曾按用户要求完成改造、直接定点验证和本地提交后暂停。随后用户明确了自主部署/审批闭环、按需 Skill、失败补偿和预算自主调整，总目标已恢复推进；数据库继续保持 v1、无版号迁移。task-board 完整业务、非默认目录真实服务器及主机重启恢复、其余七项目、冻结版本全新服务器八项目批次与该轮缓存/审批统计仍尚未验收。

原373等已有库的真实历史清理未执行；新结构下通过本地原任务证明完成清理不等于原服务器操作已经重新验收。用户允许清理不兼容旧测试数据，数据库阶段采用新隔离库，未触发批量删除。原正式 App、发布、当前配置、持久数据与未结状态均保留。

2026-10-12 04:49:12+08，`task-board-recovery02-04` 通过真实产品入口完成原 `eb65b964-bb37-4bcf-b6b7-d6682dbb8def` 的失败补偿结清，并释放恢复模式、重新打开产品正常启动；不是 Linux 主机重启。严格固定只读探针证明候选、构建单元及相关内核资源不存在，原 UNKNOWN 转为 ENDED/FAILED、无阻塞，没有重放原冻结准备计划。只读审计确认平台任务已归档、资源行 0、原 Agent task 与 action event 已清除、formal execution 0；仅留下绑定原 operation 的 447 字节 AGENT/FAILED 摘要，无 App/发布身份。正式业务表保持原空状态，模型 2、服务器 1 保持，审计字节未变。原调查证据和其他未结记录保留。一次真实入口通过、零跳过、3262 源码零变、验证进程退出；这是失败部署恢复清理的实际证明，不能作为成功 App 发布或原373历史清理的证明。逐轮失败及实际 provider 用量见 [Agent 自主验收记录](DEPLOYMENT-AGENT-AUTONOMY.md)。

2026-10-12 05:38:58+08，`task-board-recovery06-01` 通过一个真实产品恢复入口场景、零跳过，原 `b1b0150b-3fd4-4e43-9fed-20de025fe0bd` 从 UNKNOWN 结清为 ENDED/FAILED、无阻塞。产品独立审批只读观察后释放恢复模式并重新打开正常启动，没有重放准备计划，也不是 Linux 主机重启。只读核对平台资源 389→0、原 Agent task/action event 清除、formal execution 0；仅保留原 operation 的 447 字节 AGENT/FAILED 摘要，无 App/发布身份。十张正式业务表仍 0、模型 2/服务器 1 保持、审计字节未变；3265 源码运行期间零变化、验证进程退出。其他未结记录与全部调查证据保留。这是另一份真实失败补偿证明，不能替代原373、有正式业务身份或共享引用的实际验收；本轮新增 provider 用量单独记于 Agent 自主验收记录。

2026-10-12 10:08:55+08，`task-board-recovery09-03` 通过一个真实产品恢复入口场景、零失败/错误/跳过。原 `191bab33-4c43-400a-816d-f4ba78f4b785` 由 UNKNOWN 结清为 ENDED/FAILED、无阻塞；PRE 新完整独立审批后严格证明原资源 PRESENT，产品补偿后 POST 新独立审批严格证明相关候选、构建及 mount/loop ABSENT。原 frozen prepare 未重放，未手工删除服务器目录。平台资源 2888→0、原 Agent task/action event 清除、formal execution 0，仅保留原 operation 的 447 字节 AGENT/FAILED 摘要，无 App/发布身份。十张正式业务表仍 0、模型 2/服务器 1 保持，审计字节相同；3272 源码运行期间零变化，验证进程退出。产品释放恢复模式并重新打开正常启动，未进行 Linux 主机重启。这轮实际覆盖存在原候选的失败补偿清理；原373、共享引用及已有正式业务身份的真实处理仍未执行，其他未结状态及所有调查证据保留。逐轮失败、最小 Agent 探针修复及本轮独立 provider 用量见 [Agent 自主验收记录](DEPLOYMENT-AGENT-AUTONOMY.md)。

2026-10-12 11:12:11+08，`task-board-recovery11-01` 通过原 `d0031662-a830-4b7c-84f2-75da23bd6e53` 的真实产品恢复入口，零失败/错误/跳过、84.533秒。新独立审批后的PRE严格PRESENT，产品补偿后新独立POST严格ABSENT，原UNKNOWN结清为ENDED/FAILED，原平台资源归零、Agent task/action event清除，仅447字节原operation绑定AGENT/FAILED摘要，无App/发布身份或formal execution。正式十表0、模型2/服务器1保持；源码3272项运行期间零变化、验证进程退出，原冻结prepare未重放、没有手工服务器删除。产品释放恢复并重新打开正常启动，未进行Linux主机重启；原失败调查、其他未结状态和原373正式业务保留。这是原候选失败补偿及历史明细清理的另一轮实际证明，不能替代成功App、共享引用或非默认目录真机验收。15项新provider审批响应单独计量，原11部署消耗排除，见Agent自主验收记录。

2026-10-12 12:08:35+08，`task-board-recovery12-01` 通过原 `2a48b931-089b-4fd2-8da7-bbfe26161a7c` 的完整候选恢复入口，一项场景零失败/错误/跳过、76.398秒。原部署因主模型8192有效输出全部推理而截断，已存在PREPARE_RESULT/CURRENT_SOURCE和数据库回执，尚无正式App；没有重放原计划。产品经独立native审批结清原UNKNOWN为ENDED/FAILED，释放恢复并重开正常启动，未进行Linux重启。只读核对平台资源1460→0、Agent task/action event清除、formal execution0，仅原operation的447字节AGENT/FAILED摘要，无App/发布身份；十正式表0、模型2/服务器1保持，主DB审计字节相同。3272源码运行期间零变化、验证进程退出，原失败调查、原373正式业务及其他未结保持。这是已完整准备候选的真实失败补偿，与此前partial准备恢复分支区分。

恢复数字以实际产品前后诊断recordId差集计算：前48/后59、新14/淘汰3，14全为恢复时段APPROVAL/native_tools；不能以总条数不变或净增11判断新增调用。五个测试宿主许可标签不代表STATIC业务，实际源码仍经native模型审批与真实许可，没有发现raw降级；未保存逐请求verdict/grant对应，不补造该证明。14响应122859已知Token、命中62.77%、reasoning未知，有效上限8192，无新配置探测；失败SQL基线与调查保留，详见Agent自主验收记录。普通部署预算的通用配置修复已完成本地定点验证，APP不超过普通额度；按用户要求本地提交交接后暂停，第13轮及更高额度真机闭环尚未启动。数据库仍保持v1，不做版号迁移。

GPT-6.1-Sol 负责实现与定点修正，GPT-6-Astra 仅参与正式来源、窄结清凭证及完整候选恢复审批边界的只读判断；其 v2 建议已按用户最新要求撤除。

本轮 Maven、测试子进程与格式验证进程均已退出；用户原有 IDEA 进程保留。

2026-10-12第13轮新部署的原task `bb9ce013-263d-45d3-aded-8cc3ee54357d` 因平台native状态保存上限终止，仍为UNKNOWN。两份完整候选及databases结果不能结清prepare_tools的CAPABILITY_EXECUTING191：此前resolve已有真实执行结果，最后审批中断仅证明最后请求未获授权，不能证明整个capability未发送。原effectsSettled=false、4439资源及全部Agent明细保留，无正式App、无结清摘要。现有恢复缺少该capability内原toolchain操作及可信回执/status的整体结清依据；本阶段没有调用恢复或清理，也没有补造RESULT/NOT_SENT/COMPENSATED。两次有界只读诊断的原3个数据库文件SHA均未变化，锁已释放。第13轮实际用量、终因和暂停范围见[Agent自主验收记录](DEPLOYMENT-AGENT-AUTONOMY.md)，不作为恢复成功证据。数据库保持v1，原373正式业务及其他未结记录继续保留。

2026-10-12第14轮新task `783198e1-12c0-492c-9c0a-374b5bec130e` 仍UNKNOWN、2239资源、effectsSettled=false、正式十表与formal execution均0。已有工具链prepare成功回执，但后续bind审批拒绝，外层CAP66/action `b21f955379741e67c48318d3bc5acfb2f865767b5669d044e2787bbdca6f9f39` 无RESULT；不能将最后未获授权扩张为整次能力NOT_SENT。只读主库SHA256 `48a117c5b93836506aaf99d34867c6acc9347964b0bb5e2208e8bf7ade0d0515` 前后相同、锁释放，没有运行恢复或清理。原13日志中找回的NODE resolver身份不足以证明CAP191全部子操作及依赖已经结清，原13继续保留4439资源和账本。

第15轮仅主Agent诊断task `f5b542be-650e-4fea-bf69-ec4e17b58c2d` 于18:38:41+08结束UNKNOWN、734资源、effectsSettled=false，正式十表与formal execution均0、无operation结果摘要。第一prepare_tools CAP59→60已有FAILED回执，模型自行尝试后第二PREPARE70/action `83aa302b3693b1db9b831c8303a1d5027378395c75f7edef13e87eca0a835a54` 在候选容量异常后无RESULT；第一轮回执不能结清第二操作。只读主库SHA256 `e50085b90b5c8f3bd8ffd339fa1417425147791c81d555239e852385d4ff4df5` 前后相同，模型1/服务器1保持，锁释放。此轮暂停审批模型只是独立诊断条件，没有取得原操作真实恢复成功、全部资源/回执结清或业务身份承接证明，不删除任何原13/14/15明细、候选和共享引用。

遵照用户完成本轮后暂停的要求，不继续恢复、清理或付费重试；调查文件、UNKNOWN及失败/拒绝证据保留，已结清旧任务不重放，原373正式App/发布及持久数据未操作。数据库仍v1，没有版号迁移。旧373真实历史清理、非默认目录真机部署与Linux重启恢复仍待验证；本轮失败或只读审计不替代该验收。主Agent对照细节见[诊断15记录](DEPLOYMENT-AGENT-MAIN-ONLY-DIAGNOSTIC-15.md)，验证进程已退出，原用户IDE进程保留。

2026-10-12动态工作区16本地定点验证补充：新WORKSPACE_QUOTA/CANDIDATE_QUOTA绑定原task/server/source/config/layout/plan/Q/reserve，实际恢复重建只认原额度，缺失或错配拒绝，数据库仍v1无版号迁移。新PREPARE_CAPACITY_REJECTED窄事件要求helper/host/capacity真实确认、候选create未发送及原attempt完整绑定，只结清当前候选容量拒绝，不伪造准备结果或历史恢复成功。取消、UNKNOWN、缺值、截断、篡改、重复证明或其他未结效果保留安全停止。68个业务逻辑case最新PASS是本地受控证明，没有运行原13/14/15 Runtime、恢复或清理，旧UNKNOWN及共享资源不回填或删除；原373正式业务、持久数据和调查文件未操作。非默认目录真机/Linux重启及旧373成功历史清理仍待实际验收，详见[动态工作区16记录](DEPLOYMENT-DYNAMIC-WORKSPACE-QUOTA-16.md)。


## 2026-10-13 原主 Agent 对照16与收尾修复17

真实 task-board-main-only-16-03 的主模型自主完成发布、verify 和 finish 请求；工具链与命令错误由主模型自行排查，监督代理未操作服务器或提供步骤答案。产品入口最终 FAIL/UNKNOWN，3279 源码运行期间零变化；原 task dc79c361-80f1-467a-8d36-4935ff4c8355 的正式 execution 已保存，但业务未提交，正式应用/发布等十表仍为空，不能作为部署成功。

33 个 LANGUAGE 捕获响应的实际合计1679174 Token，输入1611665、输出67509、cache hit850176/miss761489，已知输入命中率52.75%；两个配置探测 usage/transport 未知。审批业务为0属于显式 main-only 诊断，不能计正式审批比例；本轮排除最终冻结八项目。前两次16入口的安全配置副本问题发生在 SSH/模型配置之前，原失败日志保留；本轮两个临时凭据副本已安全清理，原调查库和旧未结资源保留。

只读原账本确认两个宿主命令已 ENDED/exit0，但检查点在 FINISHED 后的 ERROR 之后7毫秒才释放。严格 cleanup binding 要求此前已释放；定点直接再现旧顺序失败。修复17只在 verified finish 创建 cleanupEvidence 前调用既有 strict releaseKnown，保留 finally 与 DISPATCHED/外来/损坏拒绝。7 个不同直接逻辑场景与两个 Java 格式检查通过，父核源码范围、XML及补丁正反向字节；不是全模块或真机恢复验收。

详见[动态配额与真实16记录](DEPLOYMENT-DYNAMIC-WORKSPACE-QUOTA-16.md)及[收尾修复17](DEPLOYMENT-AGENT-COMMAND-FINISH-17.md)。下一步从原产品 reconcile 入口重新验证同一发布、独立审批与严格清理；尚未恢复，不回填旧 UNKNOWN，不重放原部署或删除历史业务身份。


## 2026-10-13 原16恢复及业务入口18

验收夹具支持原 main-only 库从既有产品 reconcile 进入，启用原已验证的独立审批路由；不重新配置模型或探测能力，不反向授权原诊断部署。恢复业务按原成功归档/完整 App 身份/已审阅 publicUrl 绑定，依据真实 runtime-config API 地址进行写入与受管重启持久化验证，再等待绑定 application/URL 的真实浏览器证据，最后产品停止并禁用自启。去掉外部 port+1 的冲突硬要求；STATIC/ASSISTED 显式端口路径保持。

3 个实际 loopback HTTP 参数最新通过，两个 Java 格式检查通过；首次相邻端口夹具失败保留，修正后只重跑同一直接方法。3279 文件仅两个测试 owner 改变，父核 XML/前后像/补丁正反向字节，未运行真实恢复。额外只读原16账本显示两组件 AUTO workspace/candidate 都为1207959552字节（1.125 GiB），重规划保持同 Q；原 DB SHA 不变，两个本轮临时凭据副本已清理，原 UNKNOWN/资源/业务和调查保留。

详见[恢复业务入口18](DEPLOYMENT-AGENT-RECOVERED-BUSINESS-18.md)。原16仍待实际产品恢复、独立审批与严格结清；本地通过不能替代真实成功或完整双 Agent 验收。


## 2026-10-13 原16真实恢复与网络切换暂停

task-board-recovery16-01 从原任务产品 reconcile 执行真实独立审批；2026-10-13 01:46:40+08结束，产品入口1FAIL/0error-skip、360.2秒，3279源码/Skill运行冻结零变化。没有重放build/publish/主模型决策或在运行中修改源码。主only原部署仍是诊断，不反向计作完整双Agent部署成功。

本轮重新核实原发布健康并完成真实业务提交、候选清理及finishKnown：原formal execution 1cf87400-2b94-4672-a6e2-28d1cdf6217c已FINISHED/SUCCEEDED，businessCommitted=true、cleanupComplete=true，结果DEPLOYMENT_PUBLICATION_COMMITTED；账本新增AI_PUBLICATION_CLEANED/AI_RECOVERY_SETTLED/STATE SUCCEEDED。正式App1/组件2/发布1，原正式配置与身份保留。原model-command checkpoint仍是两个ENDED/exit0且已释放；不是把旧UNKNOWN改写成原部署成功。

平台task dc79c361-80f1-467a-8d36-4935ff4c8355仍UNKNOWN，仅Business result requires explicit reconciliation阻塞；Agent/执行/平台资源明细仍保留，operation结果摘要0、资源记录总数5569。原155 DELIVERY_EXECUTING没有配对DELIVERY_RESULT，effectsSettled=false；HistoryArchive的严格结清门因此阻断归档，产品报告TASK_RECOVERY_FAILED。不能据已有业务提交和候选清理直接删历史或宣称完整恢复成功。恢复后的浏览器/业务验收及产品停止禁用尚未启动，仍未证明前后端完整业务或独立双Agent部署。

只读post审计两次主DB SHA前后均56376364577479F161D2076879CBDF07810A5B101CAC5C4DD1B4B8217B36E425，锁释放，未初始化Runtime/API/SSH。此前未恢复成功的原13/14/15、原373正式业务及调查均保持。

provider用量按实际record-ID差集：before33/after72，新52、旧13被有界日志轮换，不能用净增39当新调用数。52捕获响应全部APPROVAL，input666816/output38997/合计705813，cache hit282880/miss383936、已知输入命中率42.42%；reasoning23161是输出子集。实际单次上限8192，低于原部署普通32768；没有新配置探测，未捕获transport尝试仍未知。本轮恢复单列，不重复计原33或混最终冻结八项目。

按用户“找合适时机暂停以切换网络”，在本轮结束、结果/用量保存和进程核对后暂停，不启动browser/business16或后续部署。01:48:45正常用户CIM自有临时验证进程0，用户既有IDE未终止；部署App与持久业务身份保留。下次用户明确恢复后，先核网络/工具链/源冻/原任务实际状态，只修成功恢复的原外层delivery结清记录这一实际阻塞，严格保留未结内层、外来记录及旧UNKNOWN，不通用忽略effectsSettled或伪造旧DELIVERY_RESULT/NOT_SENT；从产品入口结清并验证历史清除，再完成真实浏览器与业务/生命周期验收。完整八项目、非默认目录真机和Linux重启、最终冻结新服务器及缓存/审批比例仍待验收。


## 2026-10-13 恢复推进：先纯主Agent部署对照19

用户最新明确先task-board、暂停审批模型、先测部署；原16恢复轮有52次APPROVAL，不能作为纯主Agent成功证据。已纠正优先级，保留原恢复问题和原应用，先运行新任务独立端口46200。本轮只编译新增的部署阶段判定入口（0测试、0真实API/SSH），1Java格式检查通过，3279项只改唯一测试owner。成功必须有主任务自然结束、正式App完整身份/健康组件/publicUrl和平台成功归档，Runtime关闭后APP尝试/调用/token均0。业务QA留到之后，管理失败单独归因；不绕过生产完整审批或手工接管部署。

详见[纯部署对照19](DEPLOYMENT-AGENT-MAIN-ONLY-DEPLOYMENT-PROBE-19.md)。真实新轮尚未启动，旧UNKNOWN、未结清明细和调查继续保留；未宣称完整前后端、双Agent、非默认目录真机/重启或最终八项目通过。


## 2026-10-13 02:48+08 纯主Agent真实19-01结果

task-board-main-only-19-01已结束，实际1测试/1FAIL/0error-skip，源码与Skill冻结3279项无变化。全程38捕获响应全部LANGUAGE，70诊断domain判断；Runtime关闭后APP尝试/调用/token均0。主Agent自主识别两组件、规划、处理工具链空间不足与ownership失败、读取Skill、检查环境、改用获授权命令、修正127构建失败、封存、deliver、选择产品publish、verify、finish；监督代理未写运行中源码/Skill，未现场提示工具步骤或接手SSH。

原任务1a5f7268-6fed-4e41-ba1d-c659954ee1fd自然返回Published healthy=[backend,frontend]，平台自然ENDED/SUCCEEDED且PLATFORM_TASK_SUCCEEDED归档；新正式App wtl-agent-main-only-diagnostic-task-board-779tb0t9o保存。验收失败原因明确为正式入口契约：frontend Endpoint web的protocol=tcp、value=tcp://45.192.105.213:46200、browser为空，backend endpoints为空，未通过原授权http://45.192.105.213:46200网页入口断言。不能将此轮直接说为完整纯部署通过，也不能将它说为主模型卡死、审批阻断或单纯断言误判；HTTP服务/网页入口与TCP监听的契约需要继续定点调查。

测试finally通过普通产品管理入口仅将本轮新App停止并禁用自启，实际STATIC_LIVE_CLEAN已出；Runtime关闭后APP0，正常CIM按本轮捕获PID及CreationDate匹配确认0进程。受保护凭据副本19已按精确workspace/GUID目录校验清除、原凭据源双文件SHA保持，原16应用46100、其他业务/UNKNOWN及调查DB保留。没有业务写入、真实浏览器验收或Linux重启，不重复付费获取已存在部署回执。

已知provider usage：input2073765/output78693/合计2152458，cache hit1394048/miss679717，捕获输入命中率67.22%；reasoning62537是输出子集。38请求均有响应usage；DEPLOYMENT两项配置探测的provider用量/transport次数仍UNKNOWN，不将它们计为0。审批捕获占比0%，此轮只是main-only开发对照，不能混入最终冻结八项目或计作完整双Agent达标轮。

自有入口交付本地提交43a26151，未推送；真实19证据及XML/usage/父核结果保留。下一阶段先只读查HTTP入口工具/Skill和Agent判断的具体契约缺口，再按证据最小修复并定点验证，继续保持main-only优先。原16成功恢复历史清理、完整task-board业务/浏览器/工具与双Agent验收、非默认目录真机与重启、其他七项目和最终冻结八项目仍待完成。


## 2026-10-13 Agent网页入口契约修复21

19 TCP/browser空的根因已独立核实：原生端口工具不能表达HTTP/HTTPS，parser强制TCP、encode丢字段，网络约束只核公网端口；不是模型主动选错协议，不靠投影或Skill猜HTTP。21仅Agent独立protocol四Java及两个直接Tests，显式protocol/accessUrl保真往返并匹配原publicUrl，CLI无网页要求保留。shared core、独立生产审批、live/MainOnlyFixture、Config/Skill、旧typed存储与原冻结plan/DBv1不改。

48个最新定点逻辑case PASS，包含旧typed TCP字节往返；首轮Network夹具缺workspace的失败保留，仅补夹具并重跑失败33项，已过Protocol15不重跑。6Javaformat、3279源保护/补丁正反向、CIM0均通过；0真实API/SSH/Runtime/原DB。详见[HTTP契约修复21](DEPLOYMENT-AGENT-HTTP-PLAN-CONTRACT-21.md)。下一轮继续纯主Agent/task-board/APP0，新独立入口46300；此时未执行新真机部署，不回填19失败为成功或宣称完整业务、双Agent、旧恢复、重启或八项目已通过。
