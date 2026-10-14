# Agent 候选校验会话身份修复（88）

原候选通道的诊断包装会话生成 Verification，而 Agent Evidence 和 reviewed verify 消费者使用原始会话；正式契约要求对象恒等，因此 prepare 在校验审核/发送前被 ACCESS_DENIED / DEPLOYMENT_AGENT_VERIFICATION_EVIDENCE_CHANGED 拒绝。Agent RemoteTools 现在保存单一 candidateSession，初始构造和配置替换各一次 wrap，并让 producer、Evidence.matches、reviewed consumer 复用该对象。配置替换仍生成新身份并拒绝旧规格。

共享 Verification.matches 的 session/task/server/workspace/archive 恒等检查、source/config/layout/request/grant/UNKNOWN/effects/close/审批/Quota不变；其他 channel 继续使用原 session，诊断仍 best-effort 记录并原样重抛 API 异常。没有改 shared、STATIC/ASSISTED、Skill、权限或恢复。

唯一 selector：DeploymentAgentPrepareReviewTest#diagnosticCandidateSessionIdentityIsSharedByProducerEvidenceAndConsumer，非参数方法，复用 runVerification(APPROVE,false) 原临时 H2/native-review/受控 transport。baseline-01：1 FAIL / 9.748s，原 create/upload 断言通过，verifyGrants=0；旧计数断言没有保留 prepare cause，不称已捕获同码。仅新方法启用默认 false 的安全观测后，baseline-02：1 FAIL / 9.979s，原实际 ApiException 的 ProblemCode=ACCESS_DENIED、evidenceChanged=true、verifyGrants/verifySends/opens=0。corrected-01：1 PASS、0 FAIL/ERROR/SKIP，XML 10.844s（case 10.775s）；同一真实生产 prepare 达成1create/1upload、独立native review、1exactgrant/1verifySend/1open及PREPARE_RESULT/CURRENT_SOURCE。原两个失败轮和报告保留；没有执行旧4参数组、其他方法或整类。

仅 RemoteTools 与 PrepareReviewTest 两 owner，before/after source各3296，before等于85after-final，非owner0；HEAD035ee1bcbdd7c595e22109c62b49f0187c877c82/index原字节不变。IDEA两owner write/check与必要skipTests package通过。实际 RemoteTools7 / Session9 / Runner3 共19classes及15Agentresources源码/target/JAR parity；PID/ticks退出证据见辅助报告，不穷尽短child采样。

本地受控验证不是实际86恢复或真实API模型部署验收。原86/83/80/74/58 UNKNOWN、资源及调查DB全部保留，paid/provider/真实SSH/原DB/真实Runtime/恢复删除=0。sourceWriteDone/readDone=true；父负责增量隔离本地提交，不推送。

证据目录：.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-candidate-session-identity-88。增量补丁：patches/AGENT-CANDIDATE-SESSION-IDENTITY-88.patch，仅本次2source+本文，UTF-8/LF。
