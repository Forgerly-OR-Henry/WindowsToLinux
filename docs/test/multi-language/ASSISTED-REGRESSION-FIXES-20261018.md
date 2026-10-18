# 2026-10-18 ASSISTED 回归修复记录与 Agent 调试交接

本记录由用户要求保留，供后续 Agent 调试追溯。当前仍在进行 ASSISTED 多语言真机验收，最终部署结果与 Token 汇总另见本轮验收报告；本记录不代表 Agent 已恢复测试或已经通过。

## 修改与边界

| 文件 | 本轮修改 | 影响范围与后续核对点 |
| --- | --- | --- |
| `ai/agent/DeploymentNativeSelection.java` | 原生读取工具补齐 offset/limit 上限；明确最多四调用；整批超限返回每个调用对应的错误回执，读取零文件；批量数量与剩余单工具预算协调，为结论保留机会；识别/校准提交补齐嵌套组件字段、类型和实际已读文件枚举 | 所在包含 agent 字样，但当前调用方是 ASSISTED 的 SourceComparison；不改变 Agent 自主循环或审批器。总识别 12 轮、校准 6 轮不增加。 |
| `service/standard/assisted/DeploymentSourceComparison.java` | 消费超限批次时计入原有轮次并反馈；每轮提供实际 readPaths，列表或搜索元数据不能冒充已读正文 | 仅辅助来源比较；原组件归属、未读证据、冻结来源和人工未决选择规则保持。 |
| `service/standard/assisted/DeploymentAssistedAnalysis.java` | 工具明确声明 action/sourceRevision/arguments，以及 LIST/READ/SEARCH/ADVISE 的子参数；解析前校验结构，不完整提交用原有纠正机制反馈 | 仅辅助技术建议；来源修订、真实证据、人工答案优先和确定性参数验证保持。12 次业务决策上限不增加。 |
| `ai/DeploymentAiJson.java` | 新增 askStructured 复用原 submit；非审批的多提交整批拒绝，仅在原有两次提交机会内重交，不执行其中动作；保持 AUTO 工具选择，并移除原审批的 named tool_choice | **共享代码，后续 Agent 须重点核对。** DeepSeek 思考模式不支持强制指定工具，但程序仍只接受合法的单次原生提交。普通多工具浏览不受影响。APPROVAL 多提交仍严格拒绝；不复用审批、不跳过独立模型或宿主授权。 |
| `ai/review/DeploymentFixedCommandApproval.java`、`service/feedback/DeploymentDiagnostics.java` | 独立审批拒绝时，在各模式保留公开理由的脱敏记录，最多4096字符，不记录原始模型正文、命令或凭据 | **共享代码，Agent 也会获得该诊断。** 仍抛出原拒绝，不增加发送、不产生授权、不缓存拒绝。 |
| `service/standard/shared/deploy/publish/graph/DeploymentStandardGraphPublishRunner.java` | 捕获内部异常并保守转 UNKNOWN 前，保存 DEPLOYMENT_OPERATION_FAILURE 的脱敏类型、原因和公开问题码 | 仅补诊断，不改变 UNKNOWN、对账、清理或发布逻辑；对 STATIC/ASSISTED 整图可见。 |
| `ai/review/DeploymentCommandEvidence.java` | 把已执行的规范 Base64、严格 UTF-8、绑定类型往返检查显式列为 literalValidation；按当前捕获布局给出目录的 SAME/DESCENDANT/OUTSIDE 关系，区分同前缀兄弟路径 | **共享证据，Agent 后续需核对。** 仅描述字面编码与词法路径关系，不声明安装、归属或权限，不接受无效绑定；SYSTEM 目录可以在管理根之外，仍须审查原 helper 的保护检查。 |
| 同一 `DeploymentCommandEvidence.java` 的 platformProbe | 仅对当前实现的 HOST/DOCKER/PODMAN 完整脚本和空stdin做机械匹配，给出SHA/字节数及字面环境/guard/argv说明。任何改动、追加命令、非空stdin、错误variant或其它程序都得不到此绑定 | **共享证据，Agent 后续需核对。** 没有改主机探测脚本；不证明实时程序可信、无副作用、归属或权限。容器探测仍可能初始化存储，必须完整独立审查。 |
| `linux/helper/DeploymentHelperReviewContract.java`、资源 `ai/review/command-review.md` | 根据固定 helper 源码补齐工具链 prepare 的官方元数据、重定向白名单、摘要校验、归档边界、管理根、SYSTEM 复用和源码包依赖安装效果；明确 helper 本体安装不属于已有安装调用信任 | **共享审查合同，Agent 后续须核对。** 未改 helper、摘要、动态输入或授权；未来实际URL/摘要须在受保护解析后取得，不能预先捏造。源码包可能在主机安装依赖并编译，不能误称项目沙箱内执行。 |
| `ai/review/DeploymentWorkflowAuthorization.java`、`DeploymentCurrentCommandEvidence.java`、`DeploymentFixedCommandApproval.java`、`service/standard/assisted/DeploymentAssistedScope.java`、`shared/deploy/publish/policy/DeploymentPublishPolicyContext.java` | 原先实际宿主流程作用域已打开，但审批材料只有策略名称，没有原目标。现在以实际宿主返回句柄的私有仅关闭包装提供当前任务对象/服务器绑定的原非秘密目标；冻结模型修订/策略也须匹配。普通与显式恢复绑定各自的新句柄；关闭即撤销事实，释放失败仍可重试原宿主释放 | **共享审批机制，Agent 后续须核对。** 不增加 App API，不建立全局授权表、不缓存许可、不持久化句柄。事实仅证明原流程交接，不证明目录归属、安全或当前命令权限；每条命令的独立审查和宿主授权保持。STATIC 不增加模型，Agent 编排未改。 |
| 资源 `ai/review/command-review.md` | 澄清匹配已声明服务器/路径的固定只读容量查询不要求取得既有系统目录的应用所有权，也不要求目录内容摘要等同业务修订 | **Agent 也会使用的共享审查说明。** 仍检查精确目标与所有实际效果，不豁免写入、目标不符或不可信可执行内容。先通过真实 API-only 审查验证，未发送SSH。 |
| `DeploymentStaticRegressionLiveAcceptanceTest.java`、`DeploymentAssistedRegressionLiveAcceptanceTest.java` | 辅助验收复用原完整 ASSISTED 流程，部署后经产品 START/inspect/usage 导出实际入口 | 共享的仅是管理信息交付；不合并 STATIC/ASSISTED/AGENT 的生产编排。网站留作用户验收，CLI 按需运行。 |
| `DeploymentSourceBatchRegressionTest.java`、`DeploymentAssistedAnalysisProtocolRegressionTest.java` | 覆盖整批错误回执、原预算、Schema 边界、已读证据枚举、缺字段/缺分页字段/多提交纠正 | 使用受控本地模型回包；不是 Agent 真机或真实提供方稳定性证据。 |

生产源码路径前缀为 `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/`，测试路径前缀为 `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/`。

## 原始失败与已知原因

所有失败均保留在 `.ai-workspace/evidence/assisted-regression-20261018/`，每次使用独立运行数据与日志，不覆盖旧任务或重放旧计划。前六次 task-board 尚未发布应用，服务器环境准备由产品完成。

| 运行 | 现象与证据 | 判断边界 |
| --- | --- | --- |
| task-board-01 | 模型返回 5 个调用，原上限 4；read limit=200，而解析器上限100；随后 STATIC_REVIEW_INVALID | 批量上限未告知模型、Schema 分页上限与业务解析器不一致已证实。 |
| task-board-02 | 1+4+4+3 个读取调用耗尽原12次工具预算，未提交识别结论，DISCOVERY_UNAVAILABLE | 未增加总预算；补齐批量与剩余次数之间的约束和结论预留。 |
| task-board-03 | 最后返回 identify，随后 DISCOVERY_UNAVAILABLE | 当时没有底层异常栈；不可将未读证据等推断写成已证实根因。工具嵌套结构缺失是另行直接核对代码发现的合同缺口。 |
| task-board-04/05 | 已推进到组件图技术建议，随后 STATIC_REVIEW_INVALID | 当时未记录底层异常详情；工具外层/子参数合同不足已由代码和定点测试确认，不臆造具体模型参数。 |
| task-board-06 | 同一次请求返回两个 submit_result，流程取消并拒绝转人工继续 | 本地 JFR 证实 Analysis → AiJson.submit 抛出 `A complete native result submission is required`。过滤后的栈保存于 `task-board-06-source-exceptions.json`。 |
| task-board-07 | 本轮尝试指定 submit_result 后，提供方返回 HTTP400，模型用量未知 | 此次强制 LANGUAGE 工具设置由本轮引入，已撤回；同时移除原有审批相同强制设置。官方文档明确思考模式不支持 required/named tool_choice，程序以 AUTO 加严格业务校验继续。 |
| task-board-08 | 通过技术表单、多次独立审批，工具链准备的命令被模型拒绝，随后原任务 UNKNOWN | 未重放或删除原任务。产品 `task-board-reconcile-08-01` 对账通过，原任务 `523e363b-f6d0-4035-bcc3-006db78eb809` 最终 ENDED/FAILED，`DEPLOYMENT_GRAPH_PUBLISH_NOT_STARTED`，未发布应用。 |
| task-board-09 | APP 审批输出8192全部为 reasoning，finish=length，未形成完整审批；原流程 FAILED 并收尾，无应用 | 返回 `DEPLOYMENT_AI_OUTPUT_BUDGET_EXHAUSTED`；已知122787 Token（部署81003、审批41784），没有把截断当批准。 |
| task-board-10 | LOW/NONE，通过工具链准备并选择build，随后 UNKNOWN | 产品 `task-board-reconcile-10-01` 对账最终 ENDED/FAILED、未发布；底层异常当时未暴露，不臆断原因。已知194580 Token，恢复用量另计。 |
| task-board-11 | 只读容量查询被拒绝，随后 UNKNOWN | 日志与JFR证实公开拒绝理由为 /var/lib 与业务修订未提供应用归属/受控范围绑定。实际意图资源包含该服务器上的 deployment-workspace=/var/lib，脚本仅test/stat。产品原任务对账最终 ENDED/FAILED、未发布。 |
| task-board-12 | 容量查询通过，工具链 bind 被拒绝，随后 UNKNOWN | 模型认为 `/toolchains/versions/java-…` 不在 `/toolchains` 之下，并认为无法校验规范编码。实际原请求464字符/347字节，编码及绑定结构已由程序校验。产品对账最终 ENDED/FAILED、未发布。原请求绑定 SHA-256 为 `bdfc64144fd5e5ae319a2036967c97f37c001b1c49623010cb88e249f7a920d7`；根据产品实际 TOOLCHAIN 行与确定性需求编码重建后摘要完全相同，可用于 API-only 原字节复核，不代表服务器当前安装状态。 |
| task-board-13 | Java prepare 被拒绝，随后 UNKNOWN | 公开理由要求实际安装源、校验和、目录绑定和授权，认为 helper 守卫不足。产品对账最终 ENDED/FAILED、未发布；known109758 Token，恢复APP3次另计11815。原合同只有一句 prepare 效果概述，且“Installation is outside installed-call trust”可能造成 helper 本体安装与其内部工具链准备的歧义；扩充真实固定源码效果，不宣称已证实模型内部推理原因。 |

第10轮起，验收程序仍配置同一 `deepseek-flash`，部署模型 LOW，独立审批模型 NONE，审批请求上限仍8192；各配置仍经产品能力验证。前九轮为 LOW/LOW。这里调整的是本轮产品保存的模型配置，没有修改 App 的默认配置或扩大输出额度。后续结果必须注明此区别，不能声称 LOW/LOW 已完成八项目验收；Agent 使用 LOW/8192 时仍可能遇到审批耗尽，本轮没有验证其恢复行为。

`capacity-review-01` 真实模型容量审查通过，Task `140d70cb-df4c-44f9-b279-cf51c1408ce1`；本测试没有连接或发送SSH。该次审批输入3111、输出110，共3221 Token；缓存命中384、未命中2727，推理用量未报告。该审核没有被复用来执行远端命令。能力验证4项的提供方用量未知。额外用量纳入 DIAGNOSTIC 分组，不混入八项目正式验收指标。

第八次 JFR 还显示日志编码层拒绝 UNKNOWN 诊断，但日志服务已捕获该异常；它不是 UNKNOWN 业务结果的直接原因，不应据此修改任务结论。此处诊断丢失问题作为已知边界保留，本轮没有修改 App 日志合同。原独立审批的详细拒绝理由没有留存，因此新增公开拒绝诊断后继续验证，不能臆断拒绝原因，也不能把拒绝改成批准。

提供方限制依据：[DeepSeek Chat Completions 的 tool_choice 说明](https://api-docs.deepseek.com/api/create-chat-completion/)。本轮保持 LOW 思考，没有通过关闭思考掩盖兼容性问题。`DeploymentStructuredSubmissionRegressionTest` 验证审批多提交仍拒绝，以及非审批多提交不会扩大原两次尝试额度。

所有已产生的提供方已知用量都计入排障消耗，能力验证未知用量单独记录。不能只保留成功用量或把未知当零。原始 JFR 属于临时诊断文件，交付前清理，保留过滤后的相关异常栈。

## 定点验证与未验证部分

- SourceComparison 22 项、历史保留相关1项与新增批量3项分阶段验证；后续只重跑受影响失败项，不以分批方式执行全仓测试。
- Analysis 10 项、新协议3项与 AiJson 单提交相关1项通过（`assisted-analysis-unit-03.log`）。
- AUTO 兼容性、审批多提交拒绝、LANGUAGE 两次纠正上限及协议相关6项通过（`structured-submission-unit-01.log`）。
- 拒绝原因脱敏诊断3项通过（`denial-diagnostics-unit-01.log`）；拒绝后不发送、不缓存授权、不同方案必须重新审批的2项参数用例以普通 Windows 用户执行通过（`denial-policy-unit-02.log`）。首次沙箱执行因 H2 只读无法打开数据库而未进入业务验证，失败证据保留，未为此修改数据库策略。
- 整图原始原因记录及拒绝诊断2项通过（`graph-diagnostics-unit-01.log`）；真实容量审查1项通过（`capacity-review-01.log/xml`）。
- `DeploymentAiJsonTest.extendedApprovalKeepsFullCommandEvidenceButNeverExceedsDeploymentOutputCeiling` 失败：断言期望10分钟，当前审批请求配置为2分钟。修复前快照的请求路由同样优先使用 approvalTimeoutSeconds；本轮未改变该策略。此项失败保留，不能将该测试类表述为全通过。
- 未运行 Agent 真机部署或全仓/全模块测试。后续恢复 Agent 时，应核对共享 AiJson 的 AUTO 工具选择与单提交错误回执，再依据用户当时授权启动 Agent；不得自动恢复历史 UNKNOWN 任务。

## 精确变更与恢复依据

本仓库开始时存在大量既有未提交重构。用户先允许指定未跟踪文件整体提交，随后明确“这里修改过程中修改的文件都保持提交”、以后不再询问；据此保留本轮触及文件的完整当前内容，其它未触及改动保留。AiJson、Diagnostics 等共享文件已有重构内容也不应误认为均由本轮引入；本轮实际增量以以下首次修改前快照的补丁为准。

为区分本轮增量，额外保留 [精确修复补丁](ASSISTED-REGRESSION-20261018-fixes.patch)。其基线是本轮首次改动相应文件之前的工作树，而非 Git HEAD；不要对已修复工作树重复应用。原始 `.before` 快照和修复前后 SHA-256 位于本轮 evidence 的 `source-code-fixes.json`，可用于后续比对或精确恢复。恢复必须保留之后产生的用户改动，不应整文件回滚或撤销无关重构。

首个本地修复提交：`d4cc018d9537e2790fffe441df9aa8f5c5d82850`，19个触及文件，未推送；该提交明确记载真机整体验收仍未通过，不能当作完成证明。后续新增修复继续补充本记录、基线补丁及本地提交。

## 单条准备审查与流程目标证据

`preparation-review-01` 实际提供方约2.7秒返回 DENY，公开理由为未提供安装效果的流程目标授权证据。夹具直接等待 completion，未观察作者抛出后的 UNKNOWN，因而显示5分钟超时。该隔离任务为 `a381a7b7-a6bf-46eb-917f-ab9053c91a43`，状态目录 `ai-live-preparation-review-rap49ou9w`；类内没有调用 SSH 能力，保留 UNKNOWN 审计记录，不将其描述为未确定远端安装。

产品日志/账本只读检查恢复了实际 provider 请求：输入4211、输出140，共4351 Token，cache640/miss3571，reasoning未报告；4项能力验证未知。此请求按 generationId/number 去重计入 DIAGNOSTIC。首次只读入口使用 Runtime 关闭时因原未完结任务被拒，随后改为直接产品数据库/日志/账本只读检查并通过；未删除或改写原任务。夹具已改为记录失败原因并返回 FAILED，因其不含任何 SSH dispatch，不让单纯审查失败再造成无意义的5分钟等待。

新增 `DeploymentWorkflowEvidenceRegressionTest` 4项通过：真实交接句柄的原目标、伪造句柄/同UUID替代任务对象/跨服务器资源拒绝、关闭后证据失效、释放失败仍重试同宿主句柄、辅助冻结策略不一致拒绝。原 `automaticHighRefusalNeverSendsOrCachesDenialAndDifferentLowProposalNeedsAnotherAiReview` 2参数场景最初因说明文字16749字符超过原12288上限失败；未提高上限，压缩重复表述后 `workflow-evidence-unit-04` 两项通过。原句柄/精确命令许可和独立模型审查没有被测试替身取代。

压缩涉及共享审查文字：保留原 Agent stdin 字节比较、原参数顺序、可信源码哈希、非root隔离、全部可达效果和独立许可规则。它是满足原有请求大小上限的表述收敛，不是 Agent 真机验证或已证明的提供方费用优化。相关 Agent 语义仍需后续按授权调试。

`preparation-review-02` 真实 API-only 审查通过，Task `189c96c6-aa7f-44f3-8ac1-915f6199df64`，状态目录 `ai-live-preparation-review-rry7urfs0`。同一模型/LOW部署+NONE审批配置，输入3795、输出122，共3917 Token；cache128/miss3667，reasoning未报告，另4项能力验证未知。未连接/发送SSH，也未复用许可发命令。原真实部署13已对账结束后，第14次完整项目运行才开始；此处不声称第14次或八项目已通过。

## 第14轮及首个CLI的后续证据

第14轮已通过 JDK 准备/绑定，随后主机能力探测被独立模型拒绝。原任务 `934338e4-8f9f-41db-9ff3-0229e698dfb3` 已由产品对账 ENDED/FAILED、`DEPLOYMENT_GRAPH_PUBLISH_NOT_STARTED`，未发布；已知116174 Token（部署61716/审批54458），恢复新增3次APP共12586另计。

拒绝理由称包管理器插件、动态任意Python以及heredoc/实际字节不可核对。直接检查当前源码与原定点用例可确认：现有脚本已清空继承环境、固定根工作目录、校验可选程序和父链，Composer传入全部禁插件/脚本/缓存参数，Python使用-I/-S/-B及venv --help。不能将模型的说法记为已证实漏洞，也不靠声明“安全”绕过审查。[Composer官方参数](https://getcomposer.org/doc/03-cli.md)、[Python官方参数](https://docs.python.org/3.13/using/cmdline.html)支持对应选项用途；这不等同于证明任意已安装程序可信。本轮只补充精确请求字节和字面参数证据，探测执行体保持原样。

`platform-probe-evidence-unit-01` 新绑定5项、原隔离/归属/Python无启动钩子3项、原拒绝2参数，共10项通过，0跳过；使用本地Git Bash/Python及loopback夹具，无真实服务器写入或外部模型。`host-probe-review-01` 实际模型审查通过：Task `9a606510-7b74-465c-8399-43cbe58627b1`，输入9047/输出111=9158 Token、cache128/miss8919、reasoning未报告，4能力验证未知，没有SSH连接/发送。

为先验证完整构建至交付路径，队列改为三个CLI后五个网站。首个 `log-analyzer-01` 在 Rust 准备被拒绝，原 `a18e8f7c-3ef1-4ccd-9bfd-ea83d424d253` 已产品对账 ENDED/FAILED、未发布；known71029（部署29281/审批41748），恢复2APP共8394另计。拒绝把1776秒误述为范围问题后又自行纠正，最终称源码 Draft 未表达工具链安装目标；还称安装为rustup，而固定源码实际使用带官方摘要验证的独立发行归档 install.sh，不能混淆。

因此原流程交接现在明确表述：构建/发布所选应用，包含已审阅源码所需工具链/依赖/已声明数据库准备、必要受控安装、工作空间、隔离构建和健康发布；仍受原计划目标及既有资源决策策略约束，并完整保留原选择。不是新增服务器操作或扩大到无关安装；STATIC不打开该AI交接，单命令审批及宿主许可保持。此语义通过真实产品目标格式器进入API-only Rust审查；案例选择源码声明的1.85分支、1776秒，不声称与原失败命令字节相同或已确定实际安装版本。

`rust-review-01` 实际模型审查通过，Task `12ed0d0d-10a1-403a-9b71-7c4cd70dca2d`，状态 `ai-live-rust-preparation-review-s7euj0bs8`；没有SSH连接/发送或许可复用。随后只重跑失败CLI，已经通过的定点组不再重跑，Agent编排和真机测试仍暂停。

服务器写入始终由产品执行；本轮监督侧只启动验收和本地诊断，没有手工部署、手工修改服务或手工补业务数据。凭据不进入本记录、补丁或 Git。

## CLI02：真实源码选择未进入独立审查

`log-analyzer-02` 已由程序成功安装 MANAGED Rust 1.95.0，随后在 `toolchain.bind` 独立审查被拒绝，尚未发布。任务 `6e0ae933-3554-4cd7-84bf-cd766d2b8edb`、状态目录 `assisted-live-log-analyzer-sa2nwu72g` 已由 `log-analyzer-reconcile-02-02` 产品对账为 ENDED/FAILED、应用为空；本次已知75757 Token（部署28590/审批47167），恢复新增2次APP共8409另计。首次对账误写本地目录，在 toRealPath 阶段失败，未打开运行时或连接服务器，失败日志保留。

等待期间只读虚拟线程快照确认任务在 SSH 工具链准备；监督侧一次只读SSH诊断查看进程名称、归档大小/时间和工具链锁，未查看进程参数/环境，也未发写入或信号。快照看到306184192字节归档及持有准备锁的Python进程；单次快照不能证明持续增长。随后程序真实安装回执才证明 Rust 完成，安装约24分钟。诊断固定脚本摘要 `3f708adfefe28911b69bf6c63adce2947f8b5182d7299e2b7350093c3ba2511f`，主机指纹验证通过，日志保留。

拒绝理由称冻结 Draft 的 URI/git 为空、缺少实际构建输入，并把当前 bind 当作下载准备。核对真实流程可见：宿主交接先冻结草稿，后续才由平台选目录并抓取源码快照；空初始URI是合法路径，原审查事实却没有后续实际选择。因此在现有 Session 添加默认无操作 sourceCaptured 扩展，真实抓取后由 ASSISTED Scope 绑定原任务、原服务器和实际快照ID/SHA/文件数；逐命令事实包含该选择。拒绝跨任务、跨服务器、不同模型路由、替换快照或关闭交接后的使用。它不是源码可信声明、安装安全判断或命令许可，不缓存/复用批准，不改变 STATIC 或 Agent 的编排。

精确 bind 解析成功后额外记录 `toolchainSubcommand=bind`，不把构建绑定误标为准备。`source-selection-evidence-unit-01` 10项通过（作用域6、绑定4），0跳过；没有执行 Agent 或全量测试。新增只读用量审计入口直接读取22个本轮运行目录的340条产品usage记录，未调用模型/SSH；与stdout及恢复按 generationId+number 核对后暂无额外请求，1次物理生成用量、88项能力验证任务用量仍未知。首次审计因诊断日志路径残留CR失败，清理路径解析后审计02完成，未改产品记录。

验收父夹具原有“审批Token必须小于等于部署Token”是旧优化门槛，不属于本次用户的业务验收要求。改为如实打印双方累计量；真实模型调用、独立批准、发布、业务及生命周期断言均保留，不篡改或截断用量。此文件此前未跟踪，依用户持续授权提交完整被修改文件，精确两行增量另保存在本轮基线补丁，不能把整个旧夹具视为本轮新写内容。编译结果见本轮 evidence；下一次真实运行仍需完成完整业务验证。

绑定证据的新增4个参数场景覆盖捕获的自定义根、真正子目录、相同路径、相似前缀的外部目录，以及 SYSTEM 根外路径。连同原有完整版本/来源、构建绑定、非法字面和非规范 Base64 的4项，`binding-evidence-unit-01` 共8项通过。未运行该大类其它用例或 Agent 场景。

`binding-review-01` 原字节真实模型 API-only 审查通过，Task `bb70eb96-6f56-43b3-a0b9-336ec6c34a1b`。输入4363、输出128，共4491 Token；cache384、miss3979，reasoning未报告，能力验证4项用量未知。没有连接/发送SSH，也没有将该许可复用于部署。此结果只验证该请求的一次独立审查，不等同于整项目成功或保证模型后续每次判断相同。

`binding-review-01` 首次启动被自动审批审查拒绝，担心图片凭据及元数据外发。核对代码后证明：SSH密码仅进入本地产品 `saveServerProfile` 受保护存储，随后清零；模型请求仅序列化审查 facts，不含该凭据。绑定与原已授权流程的真实请求摘要一致，使用原配置提供方，且此测试没有 SSH 连接/发送。带上述证据提交原命令复审后获准，未采用间接执行绕过拒绝。
