# 原恢复外层交付结清审计 25

2026-10-13，task-board 双 Agent 24 运行期间，仅只读核对原恢复 16 的账本、正式业务身份及生产调用链，没有修改源码、配置、Skill 或数据库，没有运行恢复、测试、Runtime、provider API 或 SSH。此记录是修复依据，不是成功恢复验收。

原任务 `dc79c361-80f1-467a-8d36-4935ff4c8355`，原执行 `1cf87400-2b94-4672-a6e2-28d1cdf6217c`。正式执行已经 FINISHED/SUCCEEDED，businessCommitted、cleanupComplete 均为 true；原 App、ACTIVE release 与业务身份仍保留。但 Agent 外层事件 155 是唯一未配对的 DELIVERY_EXECUTING，ordinal1、planRevision2，同动作 DELIVERY_RESULT 数量为 0。原平台仍 UNKNOWN，不能据正式业务提交直接清除历史明细。

实际原 action SHA256 为 `65bd44b4b4e88c020b9ef2d1835227ba218dc0c008fed3051288e94c68ad402d`。正式 publicationTemplate 的存储字节与 typed 编码摘要一致，为 `7442f2c8209acd246912847813b470722751c0a0756678efb42d9279160914e7`。两个组件的 candidate/unit 身份和 sealedArtifact/release 摘要分别保留；两类摘要不相等，不预设不同层次的摘要必须相等，也不凭名称前缀认定完整绑定成立。

源码调用链确认：Session 在 tools.deliver 之前记录 DELIVERY_EXECUTING，异常或 UNKNOWN 时没有正常 DELIVERY_RESULT；Recovery 完成实际发布、候选清理和 finishKnown 后，只记录 AI_RECOVERY_SETTLED 与 STATE。Workflow 随后调用 HistoryArchive，严格 effectsSettled 门仍看到原外层交付未配对。该门符合当前合同，不应降低；共享正式发布提交逻辑不需要为 Agent 外层缺口放行。

拟限定 Agent Recovery、ActionLog、HistoryArchive 三处，增加专用恢复结清证明，绑定唯一原事件、动作、计划修订及 exact 正式执行/模板/发布/候选/制品/配置/来源身份，并在原持锁事务中验证。不得补造旧 DELIVERY_RESULT、改为 NOT_SENT、只凭 STATE 放行或重写原冻结计划；多个无法唯一对应的尝试、外来引用、未结资源或回执必须保留。方案尚未实施，也未运行成功清理、事务回滚、幂等、重启或共享引用定点验证。

只读读取使用 exact 原 DB/task/formal/server/type 硬门、原文件锁、受保护配置 bootstrap，以及 H2 IFEXISTS/ACCESS_MODE_DATA=r；不初始化 Runtime 或 schema，不导出 plan/configuration/script/凭据正文。首次辅助关系预期不成立的 read-01 已保留；read-02 只移除读取器对不同层次摘要相等的假设，将差异如实投影，没有放宽生产保护。

原 DB 前后 SHA256 均为 `56376364577479F161D2076879CBDF07810A5B101CAC5C4DD1B4B8217B36E425`。锁已释放，辅助 javac/java 的 exact PID/CreationDate 核对均已退出，临时 classes 清除；3281 项源/配置/Skill 与 dual24 冻结基线逐字节一致。父核结果与两次读取证据保留于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-recovery-outer-settlement-audit-25/`，read-02 投影 SHA256 为 `09f7849b492613f134e25d3a015fc69af93d6ad256ca6f0485ef0aa908907483`。所有原 App、数据、UNKNOWN、未结资源和调查均保留。DB 版本保持 1。
