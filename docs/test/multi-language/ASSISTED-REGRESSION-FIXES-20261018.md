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

服务器写入始终由产品执行；本轮监督侧只启动验收和本地诊断，没有手工部署、手工修改服务或手工补业务数据。凭据不进入本记录、补丁或 Git。

绑定证据的新增4个参数场景覆盖捕获的自定义根、真正子目录、相同路径、相似前缀的外部目录，以及 SYSTEM 根外路径。连同原有完整版本/来源、构建绑定、非法字面和非规范 Base64 的4项，`binding-evidence-unit-01` 共8项通过。未运行该大类其它用例或 Agent 场景。

`binding-review-01` 原字节真实模型 API-only 审查通过，Task `bb70eb96-6f56-43b3-a0b9-336ec6c34a1b`。输入4363、输出128，共4491 Token；cache384、miss3979，reasoning未报告，能力验证4项用量未知。没有连接/发送SSH，也没有将该许可复用于部署。此结果只验证该请求的一次独立审查，不等同于整项目成功或保证模型后续每次判断相同。

`binding-review-01` 首次启动被自动审批审查拒绝，担心图片凭据及元数据外发。核对代码后证明：SSH密码仅进入本地产品 `saveServerProfile` 受保护存储，随后清零；模型请求仅序列化审查 facts，不含该凭据。绑定与原已授权流程的真实请求摘要一致，使用原配置提供方，且此测试没有 SSH 连接/发送。带上述证据提交原命令复审后获准，未采用间接执行绕过拒绝。
