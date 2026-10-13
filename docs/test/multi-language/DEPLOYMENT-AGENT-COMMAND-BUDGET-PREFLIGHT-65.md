# Agent 命令预算拒绝与收尾定点验证 65

预算不足的候选命令必须在执行意图记录前拒绝，让模型从原调用反馈中自行调整。此前 `DeploymentAgentSession` 先记录 `COMMAND_EXECUTING`，底层 `ProjectChannel` 再因原目标剩余构建预算不足拒绝；命令未发送却被记录为 `UNKNOWN`。模型重新评估并缩短超时后可以完成下一命令，但悬空意图阻止 `CleanupEvidence` 收尾及正式 App 入库。

本次仅在 Session 准备候选后、失效工件与写执行意图前加入原目标剩余预算检查。底层发送前检查仍保留；不替换预算、不退款、不伪造 `NOT_SENT` 回执、不放宽结清门。STATIC、ASSISTED、审批、配置、数据库版本和 Skill 均未修改。任意下游命令异常的通用分类不是本次修复范围。

63 只读核定原 58 的事件 108/112 分别对应 1800/1600 秒，原调用收到预算拒绝反馈；同 action binding 不含超时，不能据此称完全相同输入重放。原 58 事件 178 的具体异常码仍未保留，不能把后续本地反例冒认成其全部原因，更不能称原 UNKNOWN 已恢复。62 精确只读审计的当前命令 checkpoint 行数为零，见 [62 审计记录](DEPLOYMENT-AGENT-CHECKPOINT-AUDIT-62.md)。

64 仅新增并执行 `DeploymentAgentWorkflowTest#goalBudgetPreflightRejectionAllowsIndependentTimeoutCorrectionAndNormalCommit`，固定 `OPENAI_CHAT_COMPLETIONS`。六次尝试保留五次实际 FAIL 和一次编译失败（零案例），包括夹具修正过程；没有整类测试。最终 baseline-06 在真实本地 TaskService、H2、RuntimeFixture、独立审阅及受控远端通道下，复现首次未发送却 UNKNOWN、模型修正后 4 个执行意图对 3 个结果、`CLEANUP_EVIDENCE/DEPLOYMENT_AGENT_CLEANUP_BINDING_CHANGED` 及正式 App/release 为零。它是本地确定性反例，未使用付费 provider、真实 SSH 或原调查数据库。

65 corrected-01 于 2026-10-14 00:41:09–00:42:12（UTC+8）只复跑这一方法，首轮 **1 PASS / 0 FAIL / 0 ERROR / 0 SKIP**，原 XML 耗时 45.714 秒。实际剩余 1798 秒拒绝 1800 秒请求，首次发送、审阅、执行意图与 `NOT_SENT` 均为零，修正时无 UNKNOWN。真实 Codec 保持原 tool_call_id 反馈，脚本模型重新评估 1600 秒请求，再进入完整独立审阅和实际受控通道；收尾前 3 个执行意图对应 3 个结果，原结清门通过，native publish/verify/finish 与正式本地提交完成，任务 ENDED，App/release 各一条。脚本模型的本地修正不是付费 API 模型的自主性实测。

两文件 IDEA 格式写入及检查、明确跳过测试的打包通过，源冻结变化为零。产物 `deployment.jar` SHA-256 为 `c34a2a12bc37c285967711da62992b73570c33d23c941f8c638dffbf17d0e158`；9 个 Session class 与 target/classes 一致，15 个 Agent 资源同时与源码和 target/classes 一致。父核对 3296 项完整前后原像，65 仅两个 owner（生产三行及测试空白），64→65 合并增量仅两 owner；两个补丁真实 Git 正反应用均字节一致。正常用户 CIM 正控制通过，9 个本次验证 PID/创建时间均已退出，无终止其他进程。

原迁移与其他任务改动保留。两个 source owner 已混有原改动，使用 [完整独立增量补丁](patches/AGENT-COMMAND-BUDGET-PREFLIGHT-65.patch) 承接本次代码和测试，不整文件暂存；本地验证针对当前迁移工作区，不称干净 HEAD 可独立构建。补丁 SHA-256 为 `69e921a83a2570d6757ab9964e0e11fbbd78e9cb59d0bb80c1db2547b2f984ef`。原始报告及失败轮次保留于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-budget-command-{baseline-64,preflight-65,parent-65}/`，核验没有重跑已通过方法。

下一阶段仍只 task-board：全程关闭审批模型，通过产品入口观察主 Agent 自主部署，再验证真实前后端业务、浏览器、重启持久化和产品停止/禁用。原 58 UNKNOWN、三项完整恢复失败、历史恢复真实清理、非默认目录真实部署及其余七项目保持待验；本记录不构成真实服务器通过或全量测试通过。
