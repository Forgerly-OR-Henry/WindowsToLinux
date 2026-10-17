# Agent existing-only 工具准备的独立拒绝反馈 288

287在existing-only工具准备的独立APP返回DENY后，BuildTools没有将该实际审核异常转换为Session既有AlternativeRequired反馈，导致主模型无法继续选择不同方案，留下pending capability。原287仍UNKNOWN，完整原始拒因、实机和失败FULL用量见 [287记录](DEPLOYMENT-AGENT-ISOLATION-LIVE-287.md)。本修正只处理未来真实审核边界，不回填历史。

## 改动范围

仅四个直接相关源文件：Agent BuildTools、ToolchainCommands、PrepareReviewTest，以及Live验收夹具的安全诊断投影。

- 仅reuseExistingOnly路径使用私有review adapter；必须是原DeploymentCommandReview、同一Task对象/任务拒因、有效reviewBinding、原server、精确prepare operation、完整existingController输入及实际RemoteActions intent匹配。只转换原ACCESS_DENIED/DEPLOYMENT_COMMAND_REVIEW_DENIED；其它失败与不确定性照原逻辑。拒绝不能产生grant或发送，也不允许重放相同动作。
- 转换前复用现有诊断入口，保留有界完整脱敏原拒因，避免软反馈绕过Workflow后只余摘要。Session沿既有CAPABILITY_NOT_SENT(ALTERNATIVE_REQUIRED)合同收束该调用，再由模型选择不同动作；resolver/readiness及既有controller账本继续保留，不把整个历史称为零副作用。
- 若controller NOT_SENT持久化失败，抛UNKNOWN/DEPLOYMENT_AGENT_CONTROLLER_STATE_UNCONFIRMED，保留原持久化异常cause及原拒绝，禁止继续下一模型调用。
- Live安全诊断仅接受286生产者已输出的六个预算字段，要求字段齐全、非负整数、固定成本/候选双份成本/总required/planning余量精确算术一致，budgetMeaning精确常量；溢出、缺字段、额外脚本或不安全正文仍UNKNOWN。修复的是观测兼容性，不更改容量判断。

未改STATIC/ASSISTED原生编排、共享HostChannel/BuildCommandChannel、Session、数据库表、公开API/UI、独立APP、新grant或隔离阈值。没有为旧287补RESULT/proof、恢复或清理。

## 验证状态

仅三个精确方法，无全量、真实provider或SSH。新Session夹具首次两例2F：真实审批把较长exactAction转为evidence分页描述，新增fixture错误地按文本读取，未到DENY；保留失败并按真实分页修夹具。容量诊断方法实际1P0F0E0skip/0.133s，test02/package0，UTC15:58:09.4229560Z—15:58:27.9702599Z；不重复该已过组。Session两失败方法修正分页夹具后，test03实际2P/0F/0E/0Skip、28.41s，package0；UTC16:01:00.1355158Z—16:01:46.4521330Z，Maven45.423s。最终三个不同方法均通过；物理5例=3P2F，首次runner语法错误发生在Maven启动前，单列不混JUnit。正向实际验证DENY无grant/send、controller NOT_SENT released、原CAPABILITY_NOT_SENT配对、完整拒因末尾标记、模型下一不同read独立APP/新grant；反例验证真实checkpoint持久化失败后UNKNOWN、不继续main、不伪结清。全部直接源码在最终package开始后未改，JAR4253949B位于实际package窗口。

辅助证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-toolchain-denial-288/`，含四源before、原始定点输出和独立只读判断。CIM确认本轮Java0/terminated0，经owner、绝对边界和no-reparse后清两个自有临时目录，UNKNOWN库和原始证据均保留。源码迁移等既有改动保留；本次只交付四路径窄patch（281增/5删，reverse check通过）及8项限定摘要，仅本地提交，不推送。

后续保留287及旧六组UNKNOWN，有限扩大完整保护frame，由模型根据fresh真实容量重新规划新scope；不为腾空间新增部分清理合同，不降构建需求/工具版本/准入。File Transfer→CSV严格串行，两个完整BUSINESS v2、真实浏览器、STOP/DISABLE后暂停报告审批占比及输入加权缓存；当前均未完成。
