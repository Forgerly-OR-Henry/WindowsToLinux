# Agent 候选命令原 API 诊断：82 本地定点交付

80 第二次 plan 参数通过后因 `CapabilityOutcomeException / DEPLOYMENT_BUILD_DISPATCH_UNCONFIRMED` 停止。共享 build channel 将 execute 的原 ApiException 包装为 UNKNOWN 时未保留原 problem/cause，因此具体 API 原因仍未知。

本包只在 AgentRemoteTools 传给 CandidateChannel 的原 RemoteSession 上增加窄委托。execute 失败时 best-effort 保存原 task、exact request SHA256、固定 category、受限 exception class、ProblemCode、已知安全 event 或 SHA、message SHA，同时输出同一安全 progress metadata，然后原样再抛同一个异常。诊断失败不改变原异常，其他操作/close 原样委托。配置刷新重新绑定实际 revision；其它 channel、共享 BuildChannel、STATIC/ASSISTED、UNKNOWN/停止、审批与恢复门均未修改。

唯一新增方法 `DeploymentAgentWorkflowTest#planCapacityTransportFailurePersistsSafeOriginalProblemAndPreservesUnknown`，固定 OPENAI_CHAT_COMPLETIONS，通过真实本地 H2/Codec/固定审批与 controlled capacity execute 注入单一 IO_FAILURE。先验证原 Task/Agent UNKNOWN、capacity 一次、LANGUAGE 三次且停止、未准备/上传/发布、原 task/request 绑定与安全诊断；原 message/event 正文不入新诊断。

| 轮次 | 唯一方法实际结果 | 秒 |
| --- | --- | --- |
| baseline-01 | 0 FAIL / 1 ERROR；新夹具误等 UNKNOWN completion | 17.061 |
| baseline-02 | 1 FAIL / 0 ERROR；诊断缺失，expected 1 / actual 0 | 6.978 |
| corrected-01 | 0 FAIL / 1 ERROR；目标断言通过，Runtime 因 UNKNOWN 占用拒绝关闭 | 7.073 |
| corrected-02 | 1 PASS / 0 ERROR | 7.129 |

corrected-02 在全部真实 UNKNOWN/诊断断言与安全 snapshot 留存后，才按精确 UUID、JUnit 临时 root/data 守卫执行显式 TEST-ONLY 回调：仅关闭测试文件资源，并以 FAILED 结束此临时夹具，让 Runtime 实际关闭。该阶段不修改 Agent journal、不 SQL 改账、不重放远端动作，不能作为产品 UNKNOWN 自动恢复或真实业务结清证明。原失败轮全部保留，各轮 source freeze=0、compile failure=0；只复测该方法，没有旧测试/整类/其他 Codec。

仅两 Java owner IDEA write/check 通过；必要 reactor `-DskipTests` package 通过。完整 before/after-final 各3296文件，仅2 source owners变化；JAR SHA256 `8c8868aa41eccef3f5ce8399824e5c2d4b672f7b2f73531012f4c73c356d5945`，6 RemoteTools classes、3未变 Runner classes和15 Agent resources 字节一致。已知24精确 PID/ticks 退出、terminate=0，短子进程采样不穷尽。早期中间进程汇总只计launcher3的下计数记录保留，最终聚合已纠正。

原80/74数据库和 UNKNOWN 未读取、未恢复、未清理；没有 paid/provider/真实 SSH。仅补观测，不确定80原 API 根因，不声称真实部署已恢复。父处理本地隔离提交，子任务未 Git 写。基线 HEAD 为2afb22fb62af002acde3fc206c099506500b12c8。

辅助原证：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-capacity-api-diagnostic-82/`；完整自有增量位于同目录82-owner-source.patch及正式 `docs/test/multi-language/patches/AGENT-CAPACITY-API-DIAGNOSTIC-82.patch`。
