# 独立审批原生提交格式反馈（2026-10-29）

R07 的 CSV 首次尝试在 `prepare` 审批收到完整的 `finish=stop` 文本，未收到原生 `submit_result`。生成调用成功不代表审批有效；原实现拒绝该响应，并保留原任务 UNKNOWN。该证据没有证明网络故障，也没有证明原有拒绝保护存在缺陷。本分支增加一次有界格式反馈，不改写原尝试、不恢复原 UNKNOWN 库，也不以文本 APPROVE 作为许可。

## 行为与旧保护

- 仅 APPROVAL 的第一次提交在完整 `stop` 且无原生调用时获得一次固定提示。提示属于当前请求，在保留会话每次重建消息之后、当前完整事实之前加入；下一命令不保留该提示或未提交的模型文本。
- 缺调用和原有坏原生形状纠正共用原两次提交预算，没有第三次。无 call-id 时不构造 tool_result。第二次仍无有效结果则拒绝。
- 完整合法的原生 DENY 仍一次结束；LANGUAGE、其它 finish、相互矛盾的 stop/原生调用、输出截断、传输 UNKNOWN 不借此增加调用。
- 保留 AUTO 及 LOW 编码、完整原生 schema、当前精确意图、独立审批、新鲜主机 grant、路由和取消边界、UNKNOWN 分类及所有业务负载、期限。2026-10-19 的 `a4a36717` 已因实际 LOW/强制具名工具 HTTP 400 改为 AUTO，本改动不反向恢复强制 tool_choice。
- 影响范围是共享结构化 APPROVAL 提交入口，可能影响多个项目；不改变部署或清理生命周期、SSH/FTP 实现、文件大小、分片或超时。

## 独立计数

产品在纠正前写入任务绑定的 `DEPLOYMENT_AI_SUBMISSION_CORRECTION` WARN 事件：固定 reason `MISSING_NATIVE_CALL`、提交序号 1→2、最大 2、唯一诊断 operationId，不设置终态 outcome。事件不含模型正文、凭据或原生参数。

既有 live 监控透传日志写入后，仅对完整匹配该固定事件、且返回 DATABASE/无 problem/未截断的结果导出 `AI_LIVE_SUBMISSION_CORRECTION task=... correction=... purpose=APPROVAL reason=MISSING_NATIVE_CALL rejectedSubmission=1 nextSubmission=2 maximumSubmissions=2`。返回 NOT_SAVED、EMERGENCY_FILE、problem、截断或无结果则仅导出固定字段的 `AI_LIVE_SUBMISSION_CORRECTION_COVERAGE_GAP`，不冒称正常数据库保存。原两次模型请求、物理 generation 及用量仍按原路径分别记录。该 UUID 是格式纠正诊断身份，不是 SDK 同 generation 的 number>1 恢复，也不是整项目重试。未来验收账本应分别统计 marker 原始数与唯一 correction 数（同 UUID 重复写入会有重复行）；旧 R07 parser 未增加这一口径，不能拿旧 parser 缺字段或缺 stdout 推断零纠正。若日志抛异常或不可用，原调用判定不变，但诊断完整性应记未知。

## 本地验证与限制

证据保存在隔离树 `.ai-workspace/evidence/native-approval-feedback-20261029/`，完整参数在各 launch 回执中。Maven 3.9.12/JDK21 使用同一公开 Central settings 的 `-s` 和 `-gs`，显式 `-Dtest` 和 `-Dsurefire.failIfNoSpecifiedTests=false`，live flags 为 false。

1. `red-01`：生产实现未改时，23 用例中 8 个新增场景按预期失败，实际退出 1。
2. `green-01`：58 个直接相关用例执行，3 failure/2 error，实际退出 1。新 RequiredReviewSubmission 23 个及 Metrics 5 个通过；不能称该次整体通过。
3. `baseline-existing-01`：仅对上述失败的 5 个方法临时使用原生产实现；try/finally 恢复自有 patch 并核 SHA 一致。旧 stop 单响应方法通过，属于本次应调整的行为断言；另外 4 项原实现同样失败。其中一项把已于 `37039deb` 改为 65536 的随包审批预算仍硬编码为 8192，本分支改为核对实际随包配置，配置本身未改。
4. `green-02`：仅重跑改动组，共 31 用例，0 failure/error/skip，实际退出 0，BUILD SUCCESS。覆盖独立/保留会话纠正、后续命令隔离、两次混合预算、DENY、零未审批 grant/dispatch、UNKNOWN、AUTO，以及真实 monitored 包装器的安全 marker 和两次调用可见性。
5. 随后审查发现原监控测试的日志 double 返回 null，正常返回并不等于保存成功；因此补入上述保存状态门禁与显式 LogWriteResult 正、反例。`green-03` 仅重跑 Monitoring 受影响组：7 用例，0 failure/error/skip，实际退出 0，BUILD SUCCESS。原审查阻断及其修正分别留存，不重写早期回执。

三个原有工具链源码证据用例 `toolchainCurrentSourceClaimsAndChangedRequestsRejectBeforeApproval`、`toolchainCompleteEvidenceRetainsTheOriginalEncodedRequestLimit`、`toolchainPreparationSuppliesOneCurrentSourceAfterHistoryAndPreservesFinalDenial` 在原实现同样失败，拒绝点在未改的 `DeploymentAgentToolchainPreparationEvidence.source`。本分支未修改其生产保护或夹具，不宣称已解决或整个 Conversation 类通过；其原因和对真实环境的适用范围仍需另行调查。

本次没有执行全套测试，没有真实模型、SSH、HTTP 业务、业务数据库或 UI；受控响应不能证明真实提供方一定按提示改为原生调用。原 R07 的 source/policy/原始回执保持不变，CSV 的原 UNKNOWN 和整轮未达标结论不变。
