# 纠错拒因留存278：单例通过，未重试远端动作

277的独立APP真实DENY后，Correction只保存理由SHA，含正文的异常被吞；原TaskOnly恢复也不经过普通live监控，无法从已存证据恢复理由正文。278修复未来同类拒因记录，不补造277历史，不改变其DENY/NOT_SENT或已消耗的一次性键。

## 最小修复

`DeploymentAgentPublicationRecovery.reviewed`仅在correction分支、durable.reviewed成功且真实决策为DENY后，调用既有`DeploymentDiagnostics.agentReviewDenied`。传入同任务、COMMAND、exactAction的SHA及实际理由；该API要求64hex，不能传完整命令描述。既有通道脱敏凭据赋值行、移除控制字符、将理由限制为512字符，再添加固定action前缀；不写命令正文。

没有修改审批决策、grant、REQUEST/REVIEW帧、once键、执行程序或sourceVersion、数据库表、公开API或UI。其它审批分支不新增重复记录。Diagnostics仍是best-effort、bounded last100，理由可能截断，不能声称完整永久审计或恢复277原理由。

测试把原参数化流程中的deny拆成专用单例，其余4个参数仍执行同一private body，原90秒及UNKNOWN、无纠错派发、不可重放、后续新目标/独立cleanup断言保留。新增公开Diagnostics读取核同task/action、唯一记录、有界脱敏、无控制字符和命令正文；第二次未达标协调不新增纠错记录或纠错派发。

## 实际验证

唯一selector：`DeploymentAgentHistoryArchiveTest#correctionDenialRetainsRedactedReasonWithoutGrantOrReplay`，offline Maven、`-pl src/mod/deployment -am`、`-Dsurefire.failIfNoSpecifiedTests=false`，Phase package。没有执行其它4个已通过流程、整个模块测试或真实模型/服务器调用。

| 证据 | 实际结果 |
| --- | --- |
| test-01 | 退出1，1F/0E/0skip；拒因留存、脱敏及无纠错派发断言已走过，随后新增总grant/send断言错误地期望2，实际4 |
| 修正 | 第二次完整两组件状态观测合法新增2个独立grant/send，总数应为4；纠错仍0次派发。仅修正测试计数，不改生产逻辑 |
| test-02 | 退出0，1P/0F/0E/0skip，JUnit 53.335秒，Maven约71秒，package成功 |

首轮失败原样保留，不改判通过。最终JAR为4158244字节。只在提交时统一计算两源码、JAR、patch及本说明5项摘要，不做全源/CP清查或逐步封存。

本轮Maven和测试Java均已退出；CIM核本轮专有Java为0，未终止进程。精确路径、owner及no-reparse核验后删除自有`c278a`、`u278a`，见`278/cleanup.json`。没有打开原238数据库、Runtime或SSH，没有新增APP调用，也没有重试277。

## 后续边界

用户已允许自主尝试其他或多种方案，最终汇报所用方案及结果。只读审查发现，原238没有正式App，因此现成组件管理入口不适用；低层通用helper生命周期可能额外清理遗留job/container，不能冒充stop-only；换helper或拆分同一删除效果也不能解释277未知拒因。

后续准备明确人工确认的有限复议方案：只适用于完整DENY＋确定NOT_SENT且从未grant/dispatch/result；保留原历史和Program，人工输入只准许再提交一次独立APP，不是执行grant。真正针对523–525的复议必须取得当前精确问题的真人明确答复，不能把“尝试其他方案”自动记成这一答复。APP仍可拒绝，通过后仍须新精确grant；已派发UNKNOWN不适用。成功回执也须后续新整图及独立cleanup后原生结清。不新增无界诊断或程序代际，不恢复194/234，不自动恢复221，CSV后置。

当前仅本地修复验证通过，原238仍UNKNOWN，两个完整BUSINESS验收未完成。原工作区迁移保持，只将本轮两文件增量patch、说明和摘要提交本地，不纳入整批迁移或个人AGENTS，不推送。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-correction-denial-evidence-278`。Sol完成最小修复；Astra仅判断独立的恢复替代及人工复议授权边界，没有执行程序或远端动作。
