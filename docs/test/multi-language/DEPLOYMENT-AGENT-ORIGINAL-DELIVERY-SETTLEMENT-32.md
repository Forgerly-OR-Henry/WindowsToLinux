# Agent 原交付恢复结清 32

2026-10-13，本地实现与直接验证完成：仅三个 Agent 生产文件和两个现有测试文件改变，39 个不同定点场景最新全部通过，五个 Java 文件格式检查通过。没有访问原调查数据库、调用模型 API/SSH 或执行服务器恢复。DB 版本保持 1，未新增迁移；真实恢复与历史明细删除尚未验收。

原问题见[外层恢复审计 25](DEPLOYMENT-AGENT-RECOVERY-OUTER-AUDIT-25.md)：正式发布、业务写入和候选清理已经成功，Agent 外层仍有原 `DELIVER_EXECUTING`，缺少确定结束证据。原恢复不能把它改为未发送，也不能补造旧 `DELIVERY_RESULT`。本次通过原追加账本与正式业务身份建立专用、受限的恢复结清证明。

`DeploymentAgentRecovery` 仅在原 `finishKnown` 成功后重新读取正式记录并验证。原唯一 pending 事件的 ID、action、计划修订和 task/execution/server/App，须与原 source/configuration/layout、候选、完整组件及有效密封链匹配。检查 delivery 前密封没有失效，之后没有新的构建修改或交付；合法发布与恢复的健康、结束和清理回执仍按其实际职责验证。WORKSPACE_QUOTA 与 CANDIDATE_QUOTA 分别遵守原普通 JSON 字段契约，不给历史记录补字段或版本。

组件 release 使用 `reviewedRuntime()` 重算；正式 canonical template 经 ReleaseSnapshot 和 ReleaseCodec 重算整应用 release，再与 formal result、关联业务记录核对。整体源码、组件源码、密封 artifact 和 release 摘要分别验证，不要求不同含义的摘要相等。`GRAPH_PROPOSAL` 不成为必需工具步骤，避免以非 canonical 的旧 toString 顺序猜测原模型输入。这是原可信账本的因果绑定，不能表述为重新验证旧 opaque action 原始输入哈希。

`DeploymentAgentHistoryArchive` 在现有 task/event/formal 行锁事务内写入 `DELIVERY_RECOVERY_SETTLED`，重新读取事件，沿严格 effects、inspection、业务关联门生成有界 receipt，再按依赖精确删除该 task 的事件、task 行和 exact execution。证明、摘要和删除共用同一 JDBC 事务。共享 release 的 creating origin 可以来自其他合法操作，外来 working rows、正式 App/release/configuration 和持久业务数据保留。

资源必须在归档开启自身 JDBC 前严格确认结清；事务内查询会把归档自己持有的 CONNECTION lease 判为未释放，直接测试确认了这一阻塞。没有忽略任何资源类型。平台串行 reconcile、原行锁与不明提交恢复约束仍生效；资源观察和 Mod 事务不是分布式原子事务。提交确认或连接关闭不明继续保留 UNKNOWN，以持久 receipt 核实，不假定整体已回滚或重新派发。

| 定点轮次 | 实际结果 |
| --- | --- |
| 01 | 编译失败，0 case；checked exception 边界修正 |
| 02 | 1 PASS、35 ERROR；沙箱临时 H2 只读，原 XML 保留 |
| 03 | 35 FAIL；正常用户执行暴露自持 JDBC 与 quota 解码问题，自然结束 |
| 04 | 1 FAIL；普通 quota 被误按有版本业务文档解码，按实际契约修正 |
| 05 | 1 ERROR；成功 producer 已执行，夹具最后在 platform 库误查 Mod 表，修正断言连接 |
| 06 | 39 PASS、0 FAIL/ERROR/SKIP；仅失败及直接受影响的方法选择器 |

合计 112 次执行为 40 PASS、36 FAIL、36 ERROR、0 SKIP；39 个不同逻辑场景最新全部通过。覆盖成功后明细清除、proof/receipt/六处写失败整体回滚、重复收尾幂等、关闭重开只读取 receipt、不再次派发、共享及业务身份保留、未结状态/资源/坏绑定不删除，并覆盖提交确认丢失、整应用 release 和密封失效反例。成功 producer 通过反射执行生产私有收尾方法与实际本地 H2 事务，不等于完整远端 reconcile 验收。没有全模块或全仓库测试。

父独立核对了完整 before/after 3282 项原字节、新鲜 inventory、五个 owner 和非 owner 0、六轮原 Running/报告来源，以及补丁 Git 正反应用逐字节一致。原失败、辅助权限/线程采样失败和调查证据保留。已捕获的 21 个自有验证进程按 PID 与 UTC 规范化 CreationDate 检查为 0，没有 terminate；补丁验证临时目录已精确清除。

增量补丁为 [DEPLOYMENT-AGENT-ORIGINAL-DELIVERY-SETTLEMENT-32.patch](DEPLOYMENT-AGENT-ORIGINAL-DELIVERY-SETTLEMENT-32.patch)，SHA256 `17a14de1c2fe47b30436b3380c81d9873cc23fd600bbf321a8f7c7409c6ea35a`。after manifest SHA256 `b40f389e8a1da831a94b919f61d654fb372509a2d989eb2ad1e2ef8bd4dbd794`。完整原像、日志/XML、格式、进程、事务证据及父复核保留于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-original-delivery-settlement-32/`。

本地交付仅直接提交 before 与 HEAD 一致的 HistoryArchive 及其测试；Recovery、ActionLog 和 EffectEvidenceTest 中的原迁移/其他未提交改动保留，本次精确增量通过配套补丁交付，不整文件暂存。不能据此声称独立干净 checkout 已可构建。默认不推送。

用户恢复后的最新顺序优先：下一真机轮仅 task-board 纯主部署 Agent，审批模型暂时关闭，监督者只监控并在实际阻塞后修代码或 Skill。原恢复 33 与双 Agent 验证后置。历史[纯部署结果 23](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-23.md)保留，不作为当前版本新验收；完整前后端业务/浏览器、真实恢复清理、非默认目录真机与重启、工具覆盖及最终八项目冻结轮均未完成。
