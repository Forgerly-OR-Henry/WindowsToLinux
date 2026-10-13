# Agent 失败后继续的限定设计判断 52

本文件只记录只读设计判断，尚未实现或验收。GPT-6-Astra High 定向检查 16 个冻结输入及[50 的事实](DEPLOYMENT-AGENT-PUBLICATION-UNKNOWN-AUDIT-50.md)，源 SHA 留存在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-failure-continuation-judgment-52/`；未修改源码、运行测试、读 live DB 或调用外部能力。

建议先解决在线、已确认失败后的自主继续，再单独处理原 48 的中断恢复。现有 schema 1 对 `deployment_execution.platform_task_id` 有 UNIQUE 约束，OriginalPublishCommit 也拒绝同任务第二次 begin；ASSISTED 的 AttemptStore 不可直接借给 Agent。简单让 Session 再次 deliver 会违反真实身份和持久规则。

最小首版考虑由模型自主评估并结束本次失败，再由产品串行创建独立后继部署任务：新 task/execution/proposal/operation/approval，原 task 与冻结提案保持 FAILED。首版只接纳 native FAILED、当次完整 rollback、没有后来已发送的 custom command、没有其他未知效果、全部原资源已合法结清的分支。失败收尾未知则不创建后继，不能删除持久数据来满足条件。

继续意图和唯一 admission key 必须先持久化；重复调用、并发或崩溃不得创建多个后继。保留 root/predecessor、非秘密要求、原目标/profile/fingerprint/layout、可校验的可读源快照、模型及审批配置身份、已经消耗的预算、原失败/拒绝/未知事实和回执引用。新任务不复用旧审批或 READ assessment，不重置预算、不重开 Closed DENY，也不能绕过旧任务未释放的占用。源快照资源的合法保留与转交必须先完成，不依赖已删临时目录或擅自重读变化后的用户目录。

产品把可用的新操作与事实交给模型；不生成修复脚本、端口或固定业务步骤。观察和交付需真实标识原 FAILED 与后继结果，最终 App 只由真实成功的正式业务记录承接。产品内后继任务不等于另建 Codex chat，也不部署其他项目。

若必须保持同一 task UUID，需另建 Agent 独立的持久 attempt/当前 attempt CAS 协议，连同恢复、原执行证明和 command checkpoint 绑定一起实现。这不是本轮窄补丁，也不改数据库版本或借用 ASSISTED 编排。

原 48 不满足首版在线资格：native rollback 后又有 stage/custom command，原正文缺失、输出截断且最终评估缺失。精确 EXIT receipt 只证明命令结束，不能证明所有效果、只读分类或发布成功。应先经产品核原 receipt，再进行受审的原范围观察与真实模型判断；没有足够证据继续 UNKNOWN，不能 Root 填评估、强制 FAILED 或重放猜测脚本。

实现前需定点覆盖资格反例、清理未知、资源占用、重复派发、源身份、预算累加、拒绝与审批边界，以及原失败记录不误删。中断后的模型重建还需要持久上下文和唯一 worker 租约，不能把当前只保存摘要的 checkpoint 当作完整会话恢复。后续实现范围与真实 task-board 验收另记。
