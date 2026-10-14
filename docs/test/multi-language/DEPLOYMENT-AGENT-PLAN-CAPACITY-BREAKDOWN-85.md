# Agent plan 容量拒绝分解反馈（85）

原 plan 在真实容量观察成功后被 `WORKSPACE_HOST_INSUFFICIENT` 拒绝，模型原 call 只能获得 aggregate。Agent 现在把同 task/source/config/layout、容量 request/result SHA 绑定的安全分解交回该 call：原 Request、sourceFloor、EXPLICIT hostCost、reserve 与 aggregate。AUTO 不推测 selected 或依赖大小。原 Quota 算法、拒绝条件与保护不变。

仅原 Quota 确切拒绝且观察/输入身份一致时产生私有构造的 `PlanCapacityRefusal`。Session 复核当前绑定，再使用既有 observe/evidence 通路保留完整合法 JSON（既有 12000 字符上限，必要时明确 truncated 并保留身份、aggregate 和组件 IDs）。普通 IllegalArgumentException 仍走原 512 字反馈。DATA 是原 plan 事实，不构成 NOT_SENT、清理、审批或 PREPARE 证明；newInputsNotCreatedByRejectedPlan 只指此次拒绝的新 inputs。

唯一 selector：`DeploymentAgentWorkflowTest#planHostCapacityRefusalExposesBoundBreakdownForIndependentModelChoice`，固定 OPENAI_CHAT_COMPLETIONS。本地受控场景验证原 call 完整 DATA、拒绝 create/upload=0、下一合法新 plan 的 fresh capacity 独立审阅/精确授权/回执（2 reviews、2 executes），随后受控停止并关闭临时 Runtime。它不证明真实 API 模型自主部署、publication、正式 App 提交或原 UNKNOWN 恢复。若临时 UNKNOWN 需要显式 callback，其仅属于断言后的夹具物理收尾。

原轮全部保留：baseline-01 编译失败、0 cases；baseline-02 1 FAIL/34.435s（旧 failure 分支）、baseline-03 1 FAIL/36.117s（旧 publication 尾部 UNKNOWN），均不是目标；baseline-04 1 FAIL/11.4s，证实缺少 DATA；corrected-01 1 FAIL/10.039s，证实共享 512 字截断；corrected-02 1 PASS、0 FAIL/ERROR/SKIP，XML 11.043s（case 10.932s）。只重跑这个失败方法，未跑旧方法/整类/其他 Codec。

三个 owner 的 IDEA write/check 和 `-DskipTests package` 通过。before 3296 与 82 after-final 相同，after-final 仅 Session、RemoteTools、WorkflowTest 变化；共享层、STATIC/ASSISTED、Skill、审批、UNKNOWN/恢复门不变。原 83/80/74/58 的数据库与证据保留，paid/provider/真实 SSH/原 DB/真实 Runtime/恢复删除=0。

证据目录：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-plan-capacity-breakdown-85`。完整增量补丁：`patches/AGENT-PLAN-CAPACITY-BREAKDOWN-85.patch`，仅本次 3 sources + 本文；父负责隔离本地提交，不推送。
