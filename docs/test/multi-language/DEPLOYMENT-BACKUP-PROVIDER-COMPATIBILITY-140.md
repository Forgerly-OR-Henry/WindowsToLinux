# 140：备份审批请求被服务商拒绝

唯一 `executesSelectiveBackupCommandThroughProduct` 实际1FAIL、0ERROR、0SKIP，16.548秒；2026-10-15 02:21:58.5318336至02:22:20.5425729 +08，Maven1/sourceFreeze0。Task `7a1689e3-44c2-4535-b139-f161dea38a04` 为UNKNOWN，原记录和调查数据库保留。没有审批决定、grant或SDK命令回执，失败发生在它们之前；没有执行服务器作者命令，也没有导回数据。

实际配置为 `https://api.deepseek.com/chat/completions`、`deepseek-flash`、APP `DEFAULT`，请求强制命名工具 `submit_result`。唯一生成物理尝试被 HTTP400 / AI_HTTP_REJECTED拒绝，usage全部UNKNOWN；两项必要能力探测的usage/transport及完整账单也UNKNOWN。日志里的known-only计数0不能当实际消费0，reasoning不重复加。

当前[DeepSeek Chat API官方合同](https://api-docs.deepseek.com/api/create-chat-completion/)规定默认思考模式开启，并在思考模式下拒绝强制命名或required工具选择；`reasoning_effort=none`可以关闭思考模式。[思考模式说明](https://api-docs.deepseek.com/guides/thinking_mode/)也明确这些配置关系。实际请求与该合同相斥，与观测400相符；原服务商错误正文未保留，不宣称从正文证明了唯一原因。

最小修正是后续独立新备份轮显式 APP `NONE`，复用已有枚举和Chat编码，无须改生产代码、部署模型、审批输出合同或安全条件。后续仍需完整独立APP、新grant、原SDK回执；不重放140旧任务，不改写其UNKNOWN，不为补旧费用重复探测。

源码3299项、HEAD `00a3085fc3470b17374c82b778f019ec68d2d624`、JAR `891ef1b0cc2d0c7dca23353c299676e9fb24270e28ac878b2214e844eacc42bb`及输入全轮冻结。父核原log/XML/TXT SHA，正常CIM精确10组自有PID/创建时刻全退；唯一GUID加密配置副本和UDS已清，原配置SHA不变。原证位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/selective-backup-metadata-140/`，最终封存为 `sealed-failed-command.json`。必要备份仍未完成，不能暂停或提示可重装。
