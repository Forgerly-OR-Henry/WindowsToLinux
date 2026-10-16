# Agent 审批原消息句柄与协议纠错合同修复（215）

本地修复及 **4 个具体方法、12 个案例通过**，显式跳测打包成功。本轮没有新的 provider、SSH、Runtime 或真实部署；不能据此宣称 File Transfer 业务通过、缓存改善或审批占比下降。

## 问题与原因

[214 真机复验](DEPLOYMENT-AGENT-CONTROLLER-SOURCE-LIVE-214.md) 在 APP2 返回 stop、非空正文、零原生调用后，以 `RESPONSE_CHECK/NATIVE_BATCH_INCOMPLETE` 结束，没有进入已有的一次非授权协议纠错，也没有产生 grant。

冻结源码存在明确的合同冲突：`PlatformAiService` 为带 tools 的所有成功响应保留完整原消息状态，包括 stop/零调用，并返回 `AiContinuation`；它是不透明的宿主状态句柄，绑定任务、purpose、模型配置及 revision，没有“响应未完成”的含义。Agent Approval 却要求该句柄为空才允许正文协议纠错。

214 原日志没有直接记录 APP2 响应 continuation 字段，也没有完整 wire 或 JVM CodeSource 实测。因此合同冲突确定，具体运行归因仍保留“当前冻结产品实现被实际使用”的边界，不从旧日志补造对象证明，不改写旧任务结果。

旧正例使用不带句柄的简化 AiResponse，旧负例反而把任意句柄视作应拒，未覆盖真实宿主合同。

## 最小改动

仅改 `DeploymentAgentApproval.java` 和对应 `DeploymentAgentApprovalTest.java`。生产代码只删除 `response.continuation().isPresent()` 这一项拒绝条件，共 39 字节；完整原 ASSISTANT 与原句柄仍交给现有 history 保留，没有剥离、重建或输出 provider 私有状态。

原条件继续要求 stop、零原生调用、非空正文、未中断，并与判决 schema 纠错共用一次额度。正文不解析为判决、不能产生 grant，不制造工具结果；后续仍需独立完整输入审阅、合法原生 `submit_result` 和精确动作的新 grant。原固定 SYSTEM/完整 fixed USER、coverage、绑定核对、上下文可见性和工具预算保持。伪造、跨任务/purpose/模型/revision 或错误长度的句柄仍由正式宿主验证。

未改共享 history、宿主 AI 合同、policy、控制器源入口、配置、数据库或其它模式。此修复不强制模型使用某个工具或接受某个动作，也不自动重放 214、209 或旧 UNKNOWN。

## 实际验证

| 方法 | 案例数 | 验证点 |
| --- | ---: | --- |
| `plainTextVerdictGetsOneProtocolCorrectionAndOnlyOppositeNativeVerdictCanAuthorize` | 4 | 无句柄/真实 codec 状态；正文 APPROVE 后 native DENY 零 grant，正文 DENY 后仅 native APPROVE 授权；完整固定输入、原 ASSISTANT/句柄和反馈保留，零假工具结果 |
| `protocolAndVerdictSchemaFailuresShareOneCorrectionBudget` | 3 | TEXT_TEXT、TEXT_SCHEMA、SCHEMA_TEXT 共享一次额度，原状态实际编码进下一请求；第二次错误即止，零 grant |
| `completePlainTextResponseMustFitAndRemainVisibleBeforeCorrection` | 4 | 正文及 opaque payload 的 retain/context 两类预算边界；保留失败或原响应被裁剪均不能发起下一 call |
| `blankResponseCannotEnterPlainTextCorrection` | 1 | 带真实 codec 状态的空白响应仍直接拒绝、零 grant |

状态正例用实际 Chat Completions codec 解码完整原消息，按真实 UTF-8 大小保留 provider state 并附宿主式公开句柄，再用同一 codec 编码下一请求，逐节点核对完整原 message。它覆盖实际数据流，但不是付费模型或正式宿主任务绑定集成测试；未修改或重跑共享宿主的既有绑定测试。

实际 Maven 只选择上述四方法，`java-01` 一次 12 PASS、0 FAIL、0 ERROR、0 SKIP，原 XML SHA `5ceabf074e698cd139018c5476738b1219cab78da50a177dc424396f776e0a9a`。未运行整类、旧 210 的 25 案例、213 的 5 案例或 212 的 17 案例。

IDEA 2026.2.3 两文件 write/check 均 exit0。首次格式工具在调用 formatter 前因复用的“三文件”计数拒绝，源码前后相同；只修工具计数与输出轮次，独立 `format-02` 成功，原 helper、baseline、首轮输出及收尾证据保留，不把首次拒绝当成 formatter 或产品失败。工具 AST 与选择器纯例、修正后计数正反例均有原件。

唯一打包命令为 `-B -pl src/mod/deployment -am -DskipTests package`，成功、15.983 秒，14 处 skip、0 测试执行。父独立核对 3309 项源码、20 生产组/40 成员的 source-target-JAR；本轮当前组仅 Approval，19 未变组保留 213 InputEvidence 及 policy。静态产物核对不是实际 JVM CodeSource 证明。

正常用户 CIM 最终确认已观察到的 **43 组自有 PID/创建时间全部退出**，无强终止；自有 TMP、短 UDS 和唯一补丁校验 scratch 均已清除。没有新凭据副本、浏览器页或远端资源操作；214 的 67 份封存原件逐 SHA 未变。

## 来源、增量与剩余事项

本轮输入 HEAD 为 `4d723566b4fd84d4fa6e49ae5e4bfaff09681242`，index 原为空；输入源码 3309 项与 213 一致，仅两个 owner 改变。输入 JAR 为 `a5830441da7f2532d8174adf2bd0953cbc814d4f80c99ec3311215c62643fe7a`。

| 最终证据 | SHA-256 |
| --- | --- |
| 当前 3309 项 after-manifest | `08e45512a71c87927a9824a8a396837d779ece4fb11bfe0a6c780c009b137503` |
| 新 deployment.jar | `814ac73e3975627f8dfcd18302356daf8525d0b3e88127d8f75244cef7529750` |
| package/after-metadata.json | `c96ce5a03746b98edfd9af7c71bda123ee5c7d47b00978335a7633ca83a7baff` |
| 两 owner 精确增量 | `e16336ac265cafda594e02eb6aee789d8459c26cf78694818cb2a293a70d08f3` |
| 未变 policy | `f116b4ed48492f3800d95cc12419010e852b302793091a4ac613bf327cedb250` |

工作树保留原迁移及其它任务改动；本地交付仅本记录、[两份原 dirty 源码上的精确补丁](patches/AGENT-APPROVAL-CONTINUATION-CONTRACT-215.patch) 和该补丁单路径的 `-text` 属性。真实 `git apply` 正反向 scratch 逐字节一致，当前实际源码 reverse-check 通过；不整体暂存混合源码，不声称干净 HEAD 独立包含当前完整产品。辅助证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/continuation-contract-215/`。

仍仅 CSV Inspector 与 File Transfer 两个不同项目、累计四次 FULL；本轮没有新增 FULL。214 原 API 返回 FAILED、持久 task/goal 状态未查，211 UNKNOWN/BLOCKED、原 Runtime/资源及调查证据全保。真实模型协议恢复、213 完整源入口、File Transfer 完整业务/浏览器/产品 STOP-DISABLE 尚待真实回执；完整八项目、冻结全新服务器复验、非默认目录及整机重启等原 Goal 仍未完成。默认只本地提交、不推送。
