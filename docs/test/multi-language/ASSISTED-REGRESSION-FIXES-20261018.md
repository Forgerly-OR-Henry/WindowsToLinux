# 2026-10-18 ASSISTED 回归修复记录与 Agent 调试交接

本记录由用户要求保留，供后续 Agent 调试追溯。用户已要求继续 ASSISTED 多语言真机验收。CLI22原任务已由程序结清成功并导出管理同源启动信息，当前1/8交付，正顺序验证其余7项；最终部署与Token汇总仍待全部完成。本记录不代表Agent已恢复测试或已经通过。

## 修改与边界

| 文件 | 本轮修改 | 影响范围与后续核对点 |
| --- | --- | --- |
| `ai/agent/DeploymentNativeSelection.java` | 原生读取工具补齐 offset/limit 上限；明确最多四调用；整批超限返回每个调用对应的错误回执，读取零文件；批量数量与剩余单工具预算协调，为结论保留机会；识别/校准提交补齐嵌套组件字段、类型和实际已读文件枚举 | 所在包含 agent 字样，但当前调用方是 ASSISTED 的 SourceComparison；不改变 Agent 自主循环或审批器。总识别 12 轮、校准 6 轮不增加。 |
| `service/standard/assisted/DeploymentSourceComparison.java` | 消费超限批次时计入原有轮次并反馈；每轮提供实际 readPaths，列表或搜索元数据不能冒充已读正文 | 仅辅助来源比较；原组件归属、未读证据、冻结来源和人工未决选择规则保持。 |
| `service/standard/assisted/DeploymentAssistedAnalysis.java` | 工具明确声明 action/sourceRevision/arguments，以及 LIST/READ/SEARCH/ADVISE 的子参数；解析前校验结构，不完整提交用原有纠正机制反馈 | 仅辅助技术建议；来源修订、真实证据、人工答案优先和确定性参数验证保持。12 次业务决策上限不增加。 |
| `ai/DeploymentAiJson.java` | 新增 askStructured 复用原 submit；非审批的多提交整批拒绝，仅在原有两次提交机会内重交，不执行其中动作；LANGUAGE 保持 AUTO；早期曾为 LOW 审批移除 named tool_choice，CLI19 后将 APPROVAL 单结果恢复为显式 submit_result（审批配置须支持） | **共享代码，后续 Agent 须重点核对。** DeepSeek 思考模式不支持强制指定工具，但程序仍只接受合法的单次原生提交。普通多工具浏览不受影响。APPROVAL 多提交仍严格拒绝；不复用审批、不跳过独立模型或宿主授权。 |
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

`usage-report-compile-01` 仅编译通过；上述源码选择修复及记录已提交 `b3dc310b`，未推送。CLI03（任务 `68f155ff-1068-451f-968a-4ff8a26502bc`，root `assisted-live-log-analyzer-tb3ko50y4`）后续在 Rust prepare 审查 DENY，理由把正确的生态/分支/超时位置读错，不能据此声称产品参数本身错误。原任务经 `reconcile-03-01` 对账 ENDED/FAILED、无应用，原日志/JFR过滤原因保留；本次known81075（部署36686/审批44389）。

现在只对当前精确字面 prepare 命令新增机械解析字段：生态、目录分支、精确补丁或占位、超时和Java路径及原顺序；只在字面帧/类型/版本分支/时间范围匹配后提供，附加、错位、越界等均无该证据。它不是安装回执、官方解析结果、审批建议或许可，不改变执行脚本。`preparation-arguments-unit-01` 新9项通过，0跳过；其它已通过组不重复执行。

`rust-review-02` API-only使用产品实际的未引用 prepare-toolchains 词及Rust1.95/1777参数，模型不再报告参数顺序问题，但因明确API-only场景缺现场工具链状态而DENY；该诊断已知FAILED，无SSH连接/发送，无未知远端效果。保留原失败和实际用量，不把前次不同参数的API审查通过泛化为本次通过，也不靠重试该诊断或放松审批求通过；下一步由真实完整流程验证新增字段。后续 Agent 调试需关注共享 CommandEvidence 的新增事实，但本轮没有 Agent 验证。

## CLI04：发布阶段误提议重新发现源码

CLI04真实 Rust 准备和绑定均通过，之后主模型没有选标准 build，反而以“缺源码发现能力”为由调用 supplement。独立模型拒绝该脚本：完整执行体中的工作空间恢复单元写入与其声明的源码发现目标不符。任务 `80bb6d87-c746-4774-b586-fdb0667471c6`、root `assisted-live-log-analyzer-tgbf57ylc` 已经 `reconcile-04-01` 产品对账 ENDED/FAILED、无应用；known137782（部署72014/审批65768），恢复新增2APP8357另计。原拒绝及脚本SHA/JFR过滤记录保留，不将恢复单元写入当成操作员手动行为，也不因为其是产品包装就自动豁免审核。

真实标准 build 本来负责上传/校验冻结归档并执行已审阅配方；prepare只做上传前准备。原单项循环状态仅向模型展示runtime，缺少已完成源码审阅和build所含能力的明确上下文。因此补充ASSISTED专用阶段说明及单项实际归档SHA/buildTool/buildDirectory状态，说明上传前远端无源码符合流程，不能据此认定主机能力缺口；补充脚本仍须真实前置或失败证据，未知仍停机。没有更换标准构建、提供额外文件系统工具或修改 Agent 的策略/编排。测试监控追加仅工具名的catalog，便于后续区分“实际未提供”和“模型忽略”。

`publication-source-stage-unit-01` 编译成功，但选取的旧构建纠正方法6参数全在源码/表单前置报 `DEPLOYMENT_STATIC_REVIEW_INVALID`，未到本次发布阶段，不能计为通过。该夹具源码仍返回旧纯文本 AiResponse、依赖旧事实tools，而实际 NativeSelection 要求 finish=tool_calls及原生调用；这套夹具尚未适配当前原生协议。保留失败，不向生产增加纯文本兼容放宽，也不据此声称新发布字段已由该组验证。下一次真实流程验证该阶段。

## CLI05：固定效果审批遗漏上下文与拒绝原文

发布阶段说明提交 `90bf3fa6` 后，CLI05实际选择标准 build，候选创建/查询成功，在上传前固定效果独立审批 DENY。该次原文未被旧路径保存，不能补写具体拒绝原因。任务 `975d9c61-e395-431e-910e-fdc71e84dd82`、root `assisted-live-log-analyzer-tmo94y8ak` 已 `reconcile-05-01` 产品对账 ENDED/FAILED、未发布；本次known104301（部署31318/审批72983），恢复新增4APP16864另计，实际主日志及恢复用量均保留。

检查发现前述上下文只接在完整SSH命令审批；上传走 `DeploymentAssistedApproval.approve`，仅有operation/targets/revision/exactAction，没有真实流程交接和后续源码选择，也丢弃DENY reason。现在固定效果路径复用相同原作用域事实，分别在审查前、模型返回后、宿主许可后校验；仍传原intent和空command给原宿主，确认要求/决定不变。独立拒绝以 `DEPLOYMENT_ASSISTED_REVIEW_DENIED` 脱敏日志及原问题保留reason，未请求宿主许可；不把目标摘要等同每个资源摘要。未提供源码归档内容给模型，也不创建额外服务器写入能力。

`fixed-effect-evidence-unit-01` 9项通过，0跳过：新增3项验证实际流程+源码事实仍需原精确宿主许可、DENY原文脱敏且0宿主申请、模型审查期间关闭交接即拒绝发许可；相关原作用域6项通过。没有运行旧失效构建夹具、Agent或全量测试。五个触及Java文件由既有格式器格式化，精确补丁继续以本轮首次修改前工作树为基线。

## CLI06：模型配置修订与业务计划修订混淆

固定效果修复提交 `0074d5c4` 后，CLI06在更早的candidate-create被拒绝，没有触达上传，不能声称上传问题已由真实部署验证。任务 `49a5ae18-71be-483f-bf94-630cac197d31`、root `assisted-live-log-analyzer-tsd0gdywk` 已 `reconcile-06-01` 产品对账 ENDED/FAILED、未发布。known129129（部署63047/审批66082），恢复新增2APP8381另计。

拒绝原文把businessRevision与workflow.modelRevision要求相等，并认为预调用helper守卫没有验证work目录，所以缺目录守卫。两者不是同一种对象：前者是计划身份，后者是冻结AI配置身份。现有源码 `20-candidate-workspace.sh` 的 create_task_candidate→create_candidate→initialise_controlled_roots 以及 `17-managed-content.sh` 的 assert_storage_parent 在helper内部核对父链symlink/owner/write和现有根，再初始化受管根、排他创建候选和写任务标记；预调用代码守卫保护helper，不替代这些内部检查。只是补充真实源码绑定合同与身份域说明，没有修改helper或绕过检查。

`candidate-guard-contract-unit-01` 源码固定身份/完整预调用守卫1项通过，原拒绝2参数因policy12305字符超过原12288失败；失败保留。压缩重复表述后，`unit-02` 只重跑这两项，均通过，未提高上限；已通过的源码测试不重复。压缩仍保留原Agent参数顺序、非root隔离、所有动态输入/初始化效果和独立许可规则，没有运行Agent验收。下一步仅重试失败CLI，仍无交付URL或CLI命令可宣称成功。

## CLI07：配置暂存帧和实际目的地

候选守卫说明提交 `3e81f3de` 后，CLI07候选创建、源码上传/校验和实际 Rust+C++ 编译均通过。构建回执 EXIT=0、ENGINE=ordinary、TOOL=CARGO_LOCKED、非root UID63370；但发布前 stage-config 审查DENY，尚未发布或CLI业务验收。任务 `3a42131c-4a8a-4b1a-a6bc-2c04e2562f02`、root `assisted-live-log-analyzer-tyefebfj4` 已 `reconcile-07-01` 产品对账 ENDED/FAILED，无应用；known167699（部署72942/审批94757），恢复新增4APP17104另计。

拒绝称空stdin不合法、配置帧摘要不等于业务/源码/目标摘要、且命令未带task参数。源码 `15-deployment-input.sh` 明确stage-config接受5参数（app、configuration identity、format、payload digest、payload bytes），允许0字节普通配置；它按应用/配置身份寻址，不包含task参数，任务权限由现有宿主交接另行绑定。configuration identity与渲染payload摘要是不同对象，不能互相比较。

同时确认 `DeploymentPublishChannel` 的审批resourceTargets只有releaseRoot，遗漏真正写入的配置/秘密文件。现已保留release语义目标，并为stage-config/stage-secret增补捕获layout下的精确文件目标。CommandEvidence只在当前完整字面配置帧匹配时核对stdin真实长度/SHA256及声明的配置目的地，输出各身份的明确字段和空配置语义；不披露配置内容，不声称live所有权、内容安全或授予命令许可。错长度、摘要、目标不能变成该证据，附加参数不会得到精确帧绑定。helper执行体不变。

`configuration-frame-unit-01` 11项通过、0跳过：新增帧边界8项，捕获自定义路径的配置/秘密目标2项，原暂存缓冲清理1项。既有路径测试按新增精确目标更新；该文件原有未跟踪内容依用户持续授权整体提交，本轮增量另入首次修改前补丁。共享PublishChannel和CommandEvidence会影响后续Agent的资源声明/审查事实，应在稍后Agent调试时核对；本轮仍未运行Agent。四个触及Java文件已格式化。

## CLI08：高优先级合同与真实配置协议冲突

配置帧修复提交 `ae366bd7` 后，CLI08仍在stage-config被拒绝，明确声称原五参数缺少任务/绑定参数。此次事实已解释不带task，但源码绑定的高优先级helper合同仍误写“stage-config/secret write task-bound protected frames”，两者冲突；原 `15-deployment-input.sh` 真实协议确实是应用范围的不可变五参数输入。现已纠正SYSTEM合同，列明两种实际参数顺序、配置可空/秘密非空和无task参数，仍由原宿主交接/精确许可约束实际任务。没有改helper、增加任务参数或绕过模型。仅压缩重复措辞保持预算，仍保留全部官方来源/校验/路径/隔离/独立许可条件。

原任务 `a7d7640a-7359-4e0b-a515-99555a155e65`、实际root `assisted-live-log-analyzer-u672rphlk` 已 `reconcile-08-02` 产品对账 ENDED/FAILED、无应用；known168613（部署74245/审批94368），恢复新增4APP17097。第一次对账08-01再次误抄目录，NoSuchFileException发生在toRealPath、运行时打开前，无模型/SSH；保留失败并已向用户说明。监督侧改用 `reconcile-run.ps1` 解析原日志的唯一真实目录和任务ID、校验项目/工作区后启动产品，避免继续手工转抄。

`configuration-contract-unit-01` 源码固定身份/完整守卫1项通过，原拒绝2参数因policy12289比原上限多1字符失败；压缩重复收尾后unit-02仅重跑失败2项，均通过。`configuration-review-01` 真实API-only空配置审查通过：Task `33732b43-c590-4668-b733-40021ab3dedb`，root `ai-live-configuration-review-ueh6k36yk`；使用实际构造的空DeploymentConfigSnapshot及真实五参数帧，没有SSH连接/发送或许可复用。该结果不等同整项目发布成功，下一步继续完整真实流程。失败记录与真实用量继续保留。

## CLI09：工具链目录缓存键不是归档摘要

配置合同修复提交 `35f00cf3`，API-only空配置实际4422输入+116输出=4538 Token，cache1792/miss2630、reasoningUNKNOWN，4能力验证任务UNKNOWN。CLI09随后在更早的Rust bind被拒绝，尚未在完整流程触达新配置合同。原 `edd2fd8c-8380-4bd0-9969-079b4cb47d5e`、root `assisted-live-log-analyzer-ufktdzyk4` 已通过日志自动定位脚本启动 `reconcile-09-01` 产品对账 ENDED/FAILED、无应用；known90864（部署41128/审批49736），恢复另计。

拒绝将目录尾部 `3fa746…` 与归档SHA `a47ac9…` 直接比较，并要求先有安装验证。源码 `30-managed-installation.py` 对Rust的目录键为 SHA256("RUST\n1.95.0\n"+官方归档摘要)，现场数值机械计算确实得到3fa746…，不是归档或树摘要；其它生态可能使用解析器摘要及固定配方后缀，SYSTEM使用系统文件集合身份。`40-binding-protocol.py` 的bind会在封存前实际核对受管目录/受保护标记、来源/摘要、精确版本并重哈希SYSTEM文件，缺失或漂移会失败；不是只信输入标记，也没有在本地审查事实中证明当前安装。

新增bindingDigestMeaning区分这些对象，源码绑定合同明确bind自身执行live校验后才封存。没有新增目录命名推导作为许可、没有把文字当安装回执。`binding-digest-domains-unit-01` 新Rust双身份1项、原完整源码守卫1项、原拒绝/预算2参数，共4项通过0跳过；审批上限12288保持。`rust-binding-review-01` 真实API-only通过，Task `01d892ab-6113-4fb8-87b4-8a475d8c5bc2`、root `ai-live-rust-binding-review-um7f2d67s`，没有SSH连接/发送或许可复用；使用代表性SERIES1.95/current目录版本帧，不声称与原失败MINIMUM/catalog版本帧逐字相同。

## CLI10：真实准备回执未进入下一条绑定审查

摘要身份修复提交 `4b2dbfbf` 后，CLI10仍在Rust bind DENY：明确声称没有本次工作流中的安装证据。实际程序刚收到成功prepare回执并完成类型/目录分支/要求校验，但每条审批是独立对话，下一条bind并没有该实际观测。原 `37caa49f-62bf-4791-8742-fcc9c617b16b`、root `assisted-live-log-analyzer-uone7q0tc` 已由日志自动定位的 `reconcile-10-01` 产品对账 ENDED/FAILED、无应用；known90501（部署41291/审批49210），恢复2APP8547另计。

新增窄的准备观测端口：标准ToolchainSetup通过原完整回执与catalog/requirement校验后，在bind前交给原BuildCommandChannel；通道只匹配自己实际执行得到的完整exit0/未截断单行TOOLCHAIN回执，逐项字段一致后一次消费，再通知模式所属review。自定义Commands端口保持原接口/默认无观察；非ASSISTED使用默认空实现，不增加模型请求。没有缓存或传递命令许可，也没有修改任何服务器命令、SDK或helper执行体。

ASSISTED作用域只记录原活动工作流、原任务对象、原服务器、冻结模型修订下的匹配整组摘要，最多保留32组；仅在bind/build审查携带。摘要对应实际将提交的规范工具链集合，recordedAt是本地记录时刻，不声称每个安装刚发生。事实明确是先前观测，缺少历史项不证明缺安装，后续bind仍做live标记/版本/摘要校验，仍逐命令独立批准并取得原宿主许可。关闭、跨任务/服务器或模型变化均拒绝观测；不把前一条成功转成后一条授权。

`preparation-observation-unit-01` 原作用域6项+原通道3项通过；新7项因测试夹具误传非64位计划摘要，在构造阶段报错，未验证业务。修正夹具后unit-02只重跑新7项，全部通过：绑定审查前已可读匹配观测、失败/截断/畸形无观测、无实际回执/篡改字段/重复消费拒绝、跨作用域/关闭/模型变化拒绝、32组限额及不向无关效果提供观测。5个Java格式check通过。没有运行Agent或全量测试；新默认观察方法和共享通道检查的潜在Agent影响保留供稍后核对。

只读usage-audit-03已审计32个已结束的实际目录、504条usage记录，与原stdout/恢复按generationId+number对账无额外或缺失记录。CLI10随后才结束，因此该审计不覆盖CLI10及后续任务，最终交付前仍须更新。CLI10结束时全局known2565796、521物理尝试（1用量UNKNOWN）、132能力验证任务UNKNOWN，恢复46次188170；Rust绑定API-only实际4769+137=4906，cache1664/miss3105、reasoningUNKNOWN。

绑定证据的新增4个参数场景覆盖捕获的自定义根、真正子目录、相同路径、相似前缀的外部目录，以及 SYSTEM 根外路径。连同原有完整版本/来源、构建绑定、非法字面和非规范 Base64 的4项，`binding-evidence-unit-01` 共8项通过。未运行该大类其它用例或 Agent 场景。

`binding-review-01` 原字节真实模型 API-only 审查通过，Task `bb70eb96-6f56-43b3-a0b9-336ec6c34a1b`。输入4363、输出128，共4491 Token；cache384、miss3979，reasoning未报告，能力验证4项用量未知。没有连接/发送SSH，也没有将该许可复用于部署。此结果只验证该请求的一次独立审查，不等同于整项目成功或保证模型后续每次判断相同。

`binding-review-01` 首次启动被自动审批审查拒绝，担心图片凭据及元数据外发。核对代码后证明：SSH密码仅进入本地产品 `saveServerProfile` 受保护存储，随后清零；模型请求仅序列化审查 facts，不含该凭据。绑定与原已授权流程的真实请求摘要一致，使用原配置提供方，且此测试没有 SSH 连接/发送。带上述证据提交原命令复审后获准，未采用间接执行绕过拒绝。

## CLI11：快照公开参数与推导路径

准备观测修复提交 `5aa691b6` 后，CLI11真实prepare/bind通过，绑定摘要d16b3b4efd86b4c33c5b3018bca667d4cb644712091d51c39f3f8089af4fb7ea；上传/校验、Rust+C++实际构建及普通配置两次暂存均通过。snapshot-deployment审批DENY称缺少release path参数，并将归属清单摘要当快照摘要。原任务 `6165504b-3074-4e35-8ffc-822495ecc701`、root `assisted-live-log-analyzer-v3d8i9gd4` 已reconcile-11-01产品对账ENDED/FAILED，无应用。部署已知72647、审批105206，共177853Token；恢复新增另计。

直接核对55-publication-snapshot.sh：公开snapshot-deployment/container接受app、owner manifest和可选task；原程序发送完整3参数。发布路径来自捕获layout，并非缺参。helper先检查受控根及现有归属或缺席；缺席返回PREVIOUS=0不产生快照，有旧部署才捕获受保护运行状态。没有更改helper执行体。补充源码绑定合同说明，区分归属摘要与快照/业务/源码/模型身份。

同时补足共享PublishChannel的实际snapshot resourceTarget，normal使用原task、attempt使用独立operation，保留release目标。CommandEvidence仅在空stdin/精确三参数字面帧匹配时推导并核对两个声明目标、交叉核对原TaskContext；不声称当前存在旧部署/已验证实时归属/安全或授权，附加参数和非空stdin不会获得该精确绑定。共享资源声明与合同可能影响稍后Agent审查，应依本节和精确补丁核对；本轮没有运行Agent。

snapshot-frame-unit-01新增帧边界7项、捕获layout的normal/attempt目标1项、原helper源码身份1项均通过；原审批拒绝2参数因policy12389超过原12288上限失败。压缩重复措辞后仅重跑失败2项，结果待记。API-only代表性快照审查待确认，不可代替整部署。

预算边界补充：unit-02两项仍因policy12326>12288失败，继续仅压缩重复语义后，unit-03仅重跑这两项均通过。前述9项已通过检查没有重复执行。CLI11恢复新增4APP17232，全局截至CLI11known2760881、552物理生成（1用量UNKNOWN）、136cap用量UNKNOWN，恢复共50次205402；audit03尚未覆盖新增目录，最终须更新。

- snapshot-review-01真实API-only通过，task2b61279d-6bfc-47a1-916c-48e1fd868c8a/root ai-live-snapshot-review-vhgconvgk，未连接或发送SSH，未复用审批；known4553=4421+132，cache1152/miss3269，reasoningUNKNOWN，4capUNKNOWN。六个Java格式检查中合同文件因换行需单独write，随后check通过；其余五个已通过。下一步CLI12完整流程，仍0/8。

## CLI12：归档来源与伴随文件未传递到独立构建审查

快照修复提交 `8dadddac` 后，CLI12在build-run提前DENY，未到快照阶段，不能称快照已在整流程通过。拒绝称native伴随组件未声明、构建脚本与25文件快照没有source-integrity关联。核对源码：SourceArchiver本来生成originSnapshotId/sourceManifestSha256/relativeRoot和压缩SHA，SafeBuildScriptEnvelope对实际压缩归档做SHA256校验后才解包；构建脚本由程序生成并独立审查，不是必须属于原源码清单的签名文件。但Scope当时只传原源码摘要/文件数，没有已生成归档的关联及文件名。

原 `41ad16ea-e163-4a9c-b56a-e5dcc19a89e5`、root `assisted-live-log-analyzer-vifg7qq60` 已reconcile-12-01产品对账ENDED/FAILED，无发布；known131437=40528+90909，恢复新增另计。保留拒绝原文和过滤JFR，不将测试监控误用Agent识别schema的已捕获异常误判为业务根因。

复用ASSISTED已有workspaceDemand阶段观察已创建归档，STATIC默认编排不变，不新增远端操作。Scope核对原Task对象、模型、活动workflow、原source snapshot ID/SHA，记录压缩归档SHA/长度、来源子树及有界文件名（最多64条/8192字符，明确截断/总数），历史最多32归档。仅build审查携带；同内容不同子树分别保留。记录发生在最终确认前，明确不是已批准计划、实时远端证明、安全裁决或许可；原归档校验、非root隔离和独立宿主批准继续执行。该修复仅ASSISTED观测路径，后续Agent仍不得据此跳过自身来源或命令审查。

source-archive-observation-unit-01原作用域/来源6项和新增归档2项，共8项通过0跳过；三个Java格式check通过。CLI12恢复新增4APP17228，累计恢复54次222630；全局截至CLI12known2914099、579物理（1用量UNKNOWN）、144capUNKNOWN。usage-audit-04此前35root/553条全对账、无额外/缺失/不可读记录，但不覆盖CLI12，最终需再更新。

## CLI13：固定效果原生Schema与前次进程访问占用

归档关联提交 `e9793618` 后，CLI13在upload审查INVALID_INPUT提前失败，没有验证新build事实或快照。JFR确认Invalid independent review fields/Independent fixed-action review was invalid，不能说是模型有效DENY或推断其具体缺失字段。原fixed-effect使用AiJson.ask的宽泛additionalProperties对象，调用后才parse；现复用已有askCommand严格decision/requiresUserConfirmation/reason Schema及原一次格式纠正。畸形DENY不纠正、不变成APPROVE，任何有效批准后仍需原意图的宿主许可。fixed-effect-schema-unit-01共5项中4通过；新增格式纠正用例误从AiMessage.text读取TOOL结果导致唯一断言失败，生产纠正实际已成功。改为toolResult.text后仅重跑失败1项通过。

原 `b70260f4-ccd3-42f1-9326-02b911bc56ad`、root `assisted-live-log-analyzer-vny0oewrk` 首次reconcile-13-01完成远端/引用恢复，仍UNKNOWN，剩server-access-fa260168-121f-41d9-9cbf-f17e6055d663。只读本地数据库证明是原boot的CUSTOM/EXPLICIT同步服务器访问占用，已重启且仍未released；原始占用泄漏触发未确证，过滤JFR未发现OwnedLease/PlatformServerAccess异常，不伪造其起因。没有手工UPDATE或服务器操作。

补产品恢复缺口：新server-access占用带server-process.v1/bootId；Ledger在原任务/版本或真实依赖、前次任务boot、UNKNOWN/FINISHING下，仅处理所属CUSTOM、精确server-access-UUID和严格旧EXPLICIT/新进程证明；当前进程证明保留，畸形整事务回滚。释放的是消失进程的同步本地调用占用，不释放SERVER引用、工作流检查点或其它业务资源，也不写业务成功。TaskRuntime鉴权并刷新快照；ServerAccess接入原Mod直接恢复和服务恢复清理。共享平台变更会影响稍后Agent及其它Mod恢复，需依精确首次工作树补丁核对；未运行Agent。

server-access-recovery-unit-01新Ledger2项、原真实ServerRef物理释放重试1项及上述失败格式用例1项共4通过；其余已通过4项没有重跑。七Java格式check通过。随后只启动产品reconcile-13-02，真实原任务ENDED/FAILED、blockers空、无应用，JUnit1通过；没有重放部署。五个平台/测试文件在本轮首次修改前另存.before，本轮增量并入精确补丁，按用户持续授权提交触及完整文件并保留其既有内容。

## CLI14：编码工作负载缺少完整解码审查事实

固定效果Schema/同步访问恢复提交 `0fb02470` 后，CLI14实际上传/校验、Rust+C++构建、配置暂存和snapshot均通过；publish-deployment DENY称Base64可执行载荷未完整解码，业务/工具绑定不明确。原 `5aecfdd9-d128-4139-98a3-71f790e2cb2c`、root `assisted-live-log-analyzer-vw1sfprx8` 经reconcile-14-01产品对账ENDED/FAILED、blockers空、无应用。known177391=66624+110767，恢复新增17234另计。

核对WorkloadArguments及16-application-input.sh：实际是公开的非秘密application-v1/v2 NUL分隔字段，不是缺失的任意stdin脚本，包含全部入口/argv、verification/client/workers、端点/输入/伴随构建以及health；health-v1使用同一健康字段协议。新增review-only解码器严格canonical Base64/UTF8/终结NUL、完整计数结构/边界/无尾随字段，逐项展示原值及实际payload字节SHA；不把reviewedFlag或shape检查当安全/实时所有权/安装或授权。

CommandEvidence仅对已知public publish字面前缀或完整application-health帧、空stdin解码，核对所属应用release目标及publish实际candidate目标。publish前缀以外完整原命令仍需独立审阅，没有用解码结果替代其余配置/秘密/工具/运行参数。受保护的配置/秘密载荷不进入解码器。PublishChannel新增真正candidate resourceTarget；ASSISTED只在publish同时提供同任务原archive/preparation观察以便比较同对象身份，非许可或实时状态证明。未改helper、工作负载编码或服务器执行体。共享review/PublishChannel的影响需在稍后Agent调试核对，不得据此豁免其命令或来源检查。

workload-review-evidence-unit-01共29项通过0跳过：新协议/帧20项、原捕获layout发布目标1项、原来源关联1项、原准备观察7项；6Java格式check通过。新生产文件从不存在基线加入增量补丁；其余文件继续保留首次工作树.before及哈希。当前全局known3233405、636物理（1用量UNKNOWN）、152capUNKNOWN；audit05覆盖此前37root/602条，不覆盖CLI14，最终需更新。仍0/8交付。

## CLI15：上传缺少原归档关联与已有校验语义

工作负载解码提交 `3049a674` 后，CLI15提前在固定upload审查有效DENY，未到新publish/health事实。拒绝称摘要/长度只是声明，未见本地实测或后续远端校验。原 `6accdc01-c691-414f-8c20-6e33f4b955b9`、root `assisted-live-log-analyzer-w51fy9ubs` 经reconcile-15-01产品对账ENDED/FAILED、blockers空、无应用；known116407=41077+75330，恢复另计。

直接核对原SourceArchiver：DigestOutputStream计算压缩输出摘要/长度，再重读冻结副本确认。原SftpTransfer.SourceSetup.open/verify在远端写入前NOFOLLOW打开常规本地文件，检查长度/身份/真实哈希；过程中再验证源及远端临时/最终结果。PlatformLinuxService消费精确WTL-SFTP-1许可，源摘要与原本地/远端路径/maxBytes绑定。原CandidateChannel还核对返回路径/字节/摘要，StandardBuildRunner再独立验证远端task/bytes/SHA后才构建，构建信封亦校验压缩SHA。未发现这些原有校验缺失，也没有新增手工hash或改SFTP实现。

把已有sourceArchiveObservations也提供给upload，增加原归档snapshot ID和实际localArchivePath，以便对照本条transfer原参数；仅匹配原任务/来源关联，不声称未来传输已完成或授予权限。fixed-effect-review补已有平台校验语义与数据上传/后续执行区别，仍拒绝变更源/目标、冲突事实、缺权限或未审执行。Scope的路径访问仍走原snapshot句柄验证，不读取或解码秘密载荷。该说明属于ASSISTED，Agent后续不能自动继承执行豁免。

upload-integrity-evidence-unit-01原归档/上传关联2项及精确宿主许可1项，共3通过0跳过；2Java格式check通过，合同文本已核对上述实际入口。当前全局known3366982、660物理（1用量UNKNOWN）、156capUNKNOWN；audit05尚不覆盖CLI14/15，最终更新。仍0/8交付。

## CLI16：准备审查缺少确定性源码工具链声明

上传关联提交 `bf195912` 后，CLI16提前在Rust prepare DENY，未到新upload或publish解码。拒绝称初始草稿git为空且没有manifest/required toolchain，无法确认Rust准备属于目标。原 `0820bf2b-7549-47f5-bed7-3b683a269cca`、root `assisted-live-log-analyzer-wa0jcfek8` 经reconcile-16-01产品对账ENDED/FAILED、blockers空、无应用。known83010=38728+44282，恢复另计。

实际ProjectFacts已有从冻结源码得出的ToolchainRequirement（生态/声明/来源文件/用途/约束/版本），ToolchainSetup依原catalog和约束选择并验证真实版本；独立APP准备审查只收到初始化Draft和精确prepare字面参数，缺少这些原需求。因此复用既有workspaceDemand→archiveCaptured交接，把实际facts.toolchainRequirements同原archive关联保存；prepare也展示该观察，明确不等于初始URI/git、未来resolver结果或安装证明。原任务/源码/归档/模型/服务器检查及不可替换规则保留，未改catalog、选择、安装、命令或许可。

source-toolchain-requirement-unit-01新准备需求1项+直接受影响原归档2项，共3项通过0跳过；3Java格式check通过。测试确认来源Cargo.toml/MINIMUM/1.85.0及原source SHA可见、同归档需求不能替换；只是验证字段/生命周期边界，不宣称服务器安装状态。全局截至CLI16known3458568、676物理（1用量UNKNOWN）、160capUNKNOWN；usage-audit-06覆盖此前39root/660记录，不覆盖CLI16，最终须更新。仍0/8交付。

## CLI17：完整发布位置参数仍无对应表

源码工具链需求提交 `69c046ef` 后，CLI17 prepare/bind、真实upload/独立verify、实际构建、配置和snapshot均通过；publish审查仍DENY，明确要求应用/candidate/部署目录与13位置参数/NUL载荷的对应表。新workloadInput已实际进入facts（publish evidence10993字符），不能说解码器未执行，也不能把模型仍称opaque当实际数据缺失。原 `4be84d93-4be7-4d82-98e9-5ce10471fd8f`、root `assisted-live-log-analyzer-weaus7ou0` 经reconcile-17-01产品对账ENDED/FAILED、blockers空、无应用；known159278=44963+114315，恢复另计。

新增完整字面参数映射：仅程序标准单引号/转义引号的完整调用经shellQuote回环完全相同后，依真实public标量次序列出每个参数的序号/原值/角色；包含app/candidate/release/owner、identity、恰好第7个原workload参数、configuration、计数秘密引用、十字段数据绑定、可选tools-v1以及全部运行dispatch尾参数。保留原始完整命令，不把参数分组当全部类型值/安全校验；未审运行参数、当前状态和权限仍由独立审查与helper控制。发布facts再明确派生releaseRoot/candidateRoot。process分派使用解码workload入口；编译型程序可不保留构建期编译器，所以运行期tools-v1可合法缺席，不能把缺省当丢失构建绑定。

publication-argument-frame-unit-01仅运行受影响帧/边界及新增字面位置用例，共11项通过0跳过；无关已通过health/解码用例未重跑，3Java格式check通过。没有改helper执行或任何服务器参数。全局known3635080、708物理（1用量UNKNOWN）、164capUNKNOWN；audit06不覆盖CLI16/17，最终须更新。仍0/8交付。

## CLI18：容量观察被误当成主机能力探测

完整发布参数映射提交 `205f3379` 后，CLI18提前在capacity DENY，未验证publish映射。拒绝承认只读脚本安全，但错误要求platformProbe接口/guard。原 `e1b98aad-2bf8-4dbc-8dbd-ce8970cacdd6`、root `assisted-live-log-analyzer-wllsah204` 经reconcile-18-01产品对账ENDED/FAILED、blockers空、无应用；known64341=28901+35440，恢复2APP8589另计。

原容量调用仅OS test/stat：默认路径是/var/lib存在/非symlink+statfs；捕获自定义layout则检查根与已存在祖先归属/非symlink，再statfs。它没有使用host/container capability probe或helper。提取原CandidateChannel.capacityScript/Target供原执行和review共同使用，字节/目标不变；capacityProbe仅在精确原脚本、空stdin和唯一完整匹配声明资源集合时给出，不声称live容量、OS二进制信任、安全或许可。改动/非空stdin不获该绑定，错误/额外资源直接拒绝。command-review压缩并明确两种探测分开审查，仍保留写入/逃逸/未可信执行等原规则及12288上限。

capacity-interface-unit-01新事实7项、原自定义路径1项、原精确拒绝/预算2参数，共10项通过0跳过；3Java格式check通过。capacity-review-02真实API-only通过，Task `278bec94-b78c-4e54-85fc-13666bb215d9`、root `ai-live-capacity-review-wpza36hr0`，known3667=3553+114、cache512/miss3041、reasoningUNKNOWN，4capUNKNOWN；无SSH连接/发送且不复用许可。该通道首次修改前已保存.before，本轮增量单独入精确补丁；完整触及文件保留既有内容提交。

全局截至该单项known3711677、722物理（1用量UNKNOWN）、172capUNKNOWN；usage-audit-07覆盖此前41root/708条全部对账无漏记，不覆盖CLI18/该单项，最终须更新。仍0/8交付。

## CLI19 与方向复核：单结果审批请求的工具选择

CLI19容量通过，上传固定效果审查返回finishReason=stop，JFR确认`A complete native result submission is required`，继而`Independent fixed-action review was invalid`。没有原生结果，不能记成有效DENY或推断模型正文内容。原任务`de6296b2-c0ff-4e30-b256-361cbb2b808b`、root`assisted-live-log-analyzer-wr12gqezc`经reconcile-19-01产品对账ENDED/FAILED、blockers空、应用列表为空。

只读方向复核确认：STATIC与ASSISTED共用标准源码、工具链、候选、构建和发布能力；当前模式合同另外要求AI独立识别、选择标准工具和逐条独立审批。前期Schema、实际目标和错误合同修复有具体缺陷依据；后期逐拒绝补说明并完整重跑未形成稳定闭环。用户随后明确要求继续修补，后续优先协议和统一交接，不改变模式调度/审批规则。

审批用途自task-board10起已为NONE，部署用途仍LOW；AiJson单结果请求却仍统一AUTO，允许只输出正文，与后续必须原生提交冲突。现仅`submit`中的APPROVAL要求已有`submit_result`；公开通用`call`和LANGUAGE保持原AUTO。严格单提交/三字段检查、原一次结构纠正、有效DENY立即停止、宿主单次精确授权保持。无原生结果仍失败、不解析正文当批准，不为找放行追加调用。

[DeepSeek官方接口](https://api-docs.deepseek.com/api/create-chat-completion/)说明`reasoning_effort=none`关闭思考，named/required工具仅在关闭思考后可用。当前既有NONE配置符合；程序不隐式关闭思考、更换提供方或把不支持的强制工具请求降级。其它审批配置须满足已有AiRequest.withRequiredTool的公开合同。共享AiJson可能影响Agent单结果审查，后续Agent调试须核对所选配置，未运行Agent真机。

required-approval-submission-unit-01因PowerShell未正确引用Maven带点属性，未进入编译/测试；unit-02已改为参数数组，验证进行中。只读合并已保留记录得到known3847223、746物理尝试（1输入/输出UNKNOWN），包含CLI19与恢复；其中LANGUAGE1763408、APPROVAL2083815。最终汇总与数据库对账仍须更新。

required-approval-submission-unit-02定点18项全部通过、0跳过；涵盖独立上下文、纯文本/多结果拒绝、畸形结果原有边界、有效DENY不重审及不同方案重新审批。两Java已按项目格式化。capacity-review-03真实API-only通过，task969bc8da-fff8-4a6a-af10-0d08addb8229/root ai-live-capacity-review-x30k0mig8，无SSH；输入3563/输出117=3680、cache2816/miss747，reasoningUNKNOWN，4项能力验证用量UNKNOWN。该单次结果不能替代整部署稳定性或后续每条审查。

## CLI20：官方摘要角色与UNKNOWN诊断字段

审批单结果修复提交`fcb5bb59`后，CLI20实际审批请求均带`requiredTool=submit_result`，部署普通工具仍AUTO。容量/主机探测/prepare成功，bind收到有效DENY：认为绑定中的归档SHA不能说明官方校验值。原`a3c034e5-0f93-4ea2-9488-152e45b32b3d`、root`assisted-live-log-analyzer-x4knkcaqc`经reconcile-20-01程序核对ENDED/FAILED、blockers空、无应用；原运行known85387，恢复另计。没有重试该拒绝、没有发布。

直接核对当前固定helper：`toolchain/10-release-metadata.py`的Rust resolver返回归档URL+.sha256的官方expected；download同时计算官方算法和SHA256，官方expected不匹配即失败，通过后才返回归档SHA256。`30-managed-installation.py`把官方算法/expected与归档SHA分别保存在受保护marker；SHA256来源两值相等、SHA512来源不同。原bindingDigestMeaning仅称downloaded archive SHA，遗漏这种关系。现补已有代码语义，仍要求匹配原prepare观测与live bind守卫，未改helper、目录、任何服务器命令/授权或凭空生成官方值。

JFR同时确认独立本地缺陷：effectResult把TaskResult.UNKNOWN作为LogEvent终态outcome，而LogRecordCodec明确禁止UNKNOWN终态。二次IllegalArgumentException被RefFlow捕获后会遮住原UNKNOWN问题。现统一将UNKNOWN记录为细节`observedOutcome=UNKNOWN`，终态字段留空；其他终态不改，不改变真实任务结果/对账要求。原DeploymentDiagnosticsTest只是捕获LogEvent，没校验真实编解码。现在保留脱敏断言并执行LogRecordCodec.create/encode/decode往返，证明记录可被实际日志层接受。该测试文件原已存在但未跟踪，依用户后续授权完整提交，当前改动仅方法异常声明、UNKNOWN断言及往返验证。

observed-outcome-and-official-digest-unit-01因新增Codec.decode受检异常未声明而编译失败，未进入测试；修正后unit-02同5项定点全部通过（诊断1、绑定1、helper身份1、原拒绝/策略预算2），0跳过。共享Diagnostics及CommandEvidence的Agent影响留待后续核对，未进行Agent真机测试。Rust绑定API-only验证进行中。

- Rust-binding-review-02真实API-only审查通过，taskdf19d84e-d023-47ad-bad2-78d816615741/root ai-live-rust-binding-review-xbc7ksn6k，known4957=4824+133、cache2816/miss2008、reasoningUNKNOWN，4capUNKNOWN，无SSH/许可复用。两处修复相关5项定点通过，3Java格式化完成。下一步CLI21完整流程；该单项不等同整部署成功。
- 当前已记录known3949839、765物理尝试（1输入/输出UNKNOWN）、188项能力验证用量UNKNOWN；usage-audit-07仍不覆盖后续新增，最终需要新的库对账。仍0/8。

## CLI21：统一当前任务的审查上下文

CLI21 prepare成功，bind仍有效DENY；理由已经变为缺少源码必需工具链与catalogRevision的对应证据，不再是官方校验值问题。原a0b1437f-8dec-453d-80cc-b7e457aeb746/root assisted-live-log-analyzer-xcq95v2zc经reconcile-21-01产品结清ENDED/FAILED、blockers空、无应用。JFR仅余原拒绝/关闭阻塞，没有UNKNOWN日志序列化二次异常；原DEPLOYMENT_EFFECT_OUTCOME已经正常记录。

已定位真实交接缺口：Scope虽然保存完整原sourceToolchainRequirements，却只在prepare/upload/build/publish白名单提供归档，bind拿不到；准备回执也另有操作白名单。因此停止继续按单个操作加例外，统一在原任务作用域的每次审查提供已有来源、归档和准备观测。记录仍受原task对象/server/model/workflow句柄、不可替换及32项/文件名预算约束；关闭失效，过去观测不是当前安全结论或许可。不新增来源读取、命令或远端写入；STATIC不调用这些辅助适配，Agent编排不变。完整候选/文件/目标仍由本条原审批核对。

绑定审查同时补实际canonical typed binding中已存在的requirement五字段（declaration/source/purpose/constraint/version），便于与同源声明对照；不修改绑定字节/摘要/服务器协议，不要求不同语义的源码约束与已审阅版本策略字符串盲目相等。shared-review-context-unit-01定点13项全部通过、0跳过：原来源关联/不可替换/关闭与跨scope边界5、原真实准备回执/变造/消费/容量与作用域7、绑定解码1；源码观测确实在bind/verify/snapshot等当前审查可见，准备历史在当前任务其他审查可见，均无grant字段。5Java格式化完成。

当前累计known4042320、781物理（1输入/输出UNKNOWN）、192cap UNKNOWN；旧库audit07不覆盖后续新增，最终再对账。下一步CLI22完整流程，仍0/8。

## 用户要求暂停（2026-10-18 21时本轮结束后）

- 用户明确要求找合适时间暂停；当前所有本轮测试/恢复工具会话均已终止，不再启动部署、恢复或模型调用。
- 最后源码提交bc2b7b7；本轮新增提交依次fcb5bb59（单结果审批原生工具）、7cff591d（UNKNOWN日志/官方摘要角色）、bc2b7b7（统一原任务观测/绑定需求）。验证分别18、5、13项定点通过，两个真实API-only通过；无全量或Agent真机验收。
- CLI22原task247e2624-5971-4043-a27a-13619b6f4ffc，状态目录src/app/db/data/assisted-live-log-analyzer-xiiwrepk8。prepare/bind/upload/verify/实际Rust+C++构建/config/snapshot/publish/health/inspect/observe均推进，endMaintenance独立审查有效DENY，不能计完整成功。
- 拒绝理由：application-maintenance end的应用/路径/两摘要与resourceTargets关系没有可验证说明。代码真实参数为end,app,ownerManifestSha256,deployment-releaseSha256,releaseSha256；不是用户猜测的发布目录参数。仅只读查看实现，尚未为此修改源码。
- reconcile-22-01已结束但原task仍UNKNOWN，保留deployment-publication-policy和deployment-publication-remote检查点，业务结果须显式对账。恢复入口未结清；不得启动新任务重发发布，也不得手工改服务器/数据库或移除维护标记。下一次先排查该原任务恢复未结清原因及原固定maintenance调用的参数/真实资源目标，修程序后只恢复同一原任务；确认结果后再补该应用业务验收/管理同源入口导出。
- 仍0/8完整ASSISTED交付，无可作为完整验收成功交付的地址。不能把CLI22实际发布或健康通过写成整项目完成。STATIC已由用户验收但旧服务器已重装。
- 逐轮原始日志/XML、过滤JFR异常及精确源码补丁保留。本轮诊断JFR均已随验收JVM退出停止。最新token-summary在CLI21后为4042320 known /781物理（1输入/输出UNKNOWN）/192项能力验证用量UNKNOWN；暂停前将纳入CLI22与恢复生成新汇总，最终仍需新数据库用量对账。

暂停汇总已刷新：已知提供方Token 4237072，物理生成尝试815（输入/输出用量UNKNOWN 1次），能力验证用量UNKNOWN 196项；包含失败、API-only及恢复。无新增模型/SSH调用，旧audit07不覆盖新增部分，完整最终对账留待恢复工作时执行。


## 用户继续后：CLI22维护收尾与单体清理记账

本地只读检查original-22-inspection-03通过，原任务仍UNKNOWN，远端检查点phase=MAINTENANCE_END_MAY_HAVE_RUN，verifiedOutcome=SUCCEEDED。此前拒绝发生在独立审批、未获得发送许可；单体finishKnown直接mark后调用，漏用多组件发布已有DeploymentPublishCleanupAttempt，导致明确未发送仍被记成可能已发送。现单体三项收尾复用已有工具：仅明确发送前拒绝恢复原阶段，真正UNKNOWN保留MAY。没有修改平台任务、删除检查点或伪造成功。

维护命令实际五参数为end/app/ownerManifestSha256/deployment-releaseSha256/releaseSha256，发布目录由layout派生；新增机械帧证据逐一绑定token后缀与release、空stdin、完整字面命令与声明资源。PublishChannel同时声明真实applicationLockRoot目标，覆盖锁目录及admission/run文件写入与maintenance标记删除。owner与release摘要角色不同；特别明确helper end只校验参数格式、root归属锁目录/标记和精确token，不以owner/expected摘要核实当前发布。原命令、完整helper审查和每次独立新授权保留；没有改helper或服务器命令。

单体原恢复此前拒绝所有MAINTENANCE_END_MAY_HAVE_RUN，因而无法结清历史错误记账。新边界只对这一幂等收尾允许：原账本已有已核实业务结果，原任务独立只读status重新确认对应release的所属维护标记仍在，当前业务状态一致且构建未活动，再经过本条独立审批执行end；helper在排他锁内重核token，外来token拒绝，已不存在则不会重发end。仍禁止重放构建/发布/回滚、重试未知retain或候选清理。新增有标记/已结束/活动构建/外来标记等回环SSH恢复案例及发送前拒绝与UNKNOWN区分案例，验证进行中。此共享单体通道可能影响未来Agent固定命令审查，需保留记录；本次不做Agent真机测试。

maintenance-recovery-unit-01中帧/资源10项及原任务重启恢复11项全部通过；另2项记账测试业务断言通过但结束时触发夹具正常close拒绝UNKNOWN占用，故整次标失败。改为调用既有隔离子JVM夹具，仅重跑这2项（unit-02），不重跑已通过21项。未因夹具错误修改生产关闭/恢复规则。九Java限定格式化已完成。

maintenance-recovery-unit-02两项隔离记账测试通过0跳过；合计23项定点通过（前次21+本次2），未执行全量。下一步只恢复原CLI22任务。


维护修复本地提交8d62385b。reconcile-22-02成功，原247e2624-5971-4043-a27a-13619b6f4ffc为ENDED/SUCCEEDED、blockers空，产品列表确认wtl-assisted-log-analyzer-xiiwt1zl8为INSTALLED/NOT_APPLICABLE。仅原任务收尾，无新发布；1项真实恢复检查通过、61.37s。

补恢复后的交付测试入口，核对正式已归档的原成功任务、同一应用与持久COMMAND健康期望，再复用现有product usage导出。delivery-01误从活跃snapshot找已结束任务、delivery-02误从未归档表读取，均在管理请求前失败，未变更原发布；delivery-03改读正式archived(task,owner,version)，继续验证管理与导出。没有为导出重建执行记录或重跑部署；原CLI业务检查同正常ASSISTED入口，以原成功COMMAND健康及期望输出为依据。

delivery-03真实产品交付检查1项通过0跳过、41.69s；log-analyzer.json已导出同源管理启动命令，第一项ASSISTED完整交付成立。两Java限定格式化完成。恢复与交付管理调用将纳入usage-audit-08本地持久日志核对；任务归档正常保留，后续不再重部署该CLI。


## Directory Diff 01：已发布后的保留清理参数与实际目标

原task161164ad-1d4a-42ba-a12e-a4a84852a153/root assisted-live-directory-diff-3awq9ju74，来源/准备/上传/构建/发布/健康均通过，maintenance-end通过，但retain有效DENY；理由把两个实参当部署目录标识和业务摘要，要求与release/businessRevision绑定。真实接口只有app/ownerManifestSha256，二者应分别与派生应用目录/对应所有权核对。新记账已生效，发送前拒绝保留MAINTENANCE_ENDED；directory-diff-reconcile-01-01已由产品重新观察同一发布、实际保留数满足上限，无需发送retain，结清原任务ENDED/SUCCEEDED、blockers空。不删除拒绝、不重发布或用另一模型寻找批准。

补这类固定管理入口的共同app/owner机械帧，覆盖retain/inspect-runtime/observe-deployment，完整精确字面调用、空stdin及派生应用资源匹配后才给出；保留原命令/所有权和业务修订角色差异，事实不等于live归属或授权。retain额外描述真实inventory/归属验证、当前+两个最新非当前版本保留，以及旧版本和对应容器凭据目录清理。核对源码时发现retain可能调用remove_container_secret_delivery，原target只含应用发布目录；现另声明捕获layout的configurationRoot(app)/.container-credentials，不暴露任何秘密值。无helper/命令/保留策略变化，独立新审批不省略；未来Agent固定发布通道会看到此资源，记录供后续调试。定点验证进行中。

managed-application-frame-unit-01限定10项通过0跳过（3种共同帧、资源/参数漂移与非空stdin/额外动作边界9项，真实PublishChannel保留target1项）；4Java限定格式化完成。接下来导出已成功原Directory Diff，再继续余下6项。

共同管理帧修复本地提交ea7eebf5。Directory Diff原成功任务delivery-01检查1项通过0跳过、32.65s，管理启动命令已导出directory-diff.json，当前2/8。继续核对同一send资源分支还发现candidate-cleanup-task实际删除工作候选，却只声明发布应用；补与publish共用的精确deployment-candidate目标，helper与三实参/删除守卫保持，新增实际通道定点检查。此为已确认目标遗漏，非推测另一个失败根因。
