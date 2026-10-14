# 142：审批通过后缺少平台审核作用域

唯一备份命令方法实际1FAIL、0ERROR、0SKIP，17.821秒，Maven1/sourceFreeze0。Task `9c5e2fba-8a4d-410e-901a-26dc0d315bdd` 保持UNKNOWN。新轮APP显式NONE后，DeepSeek生成成功，独立模型对完整只读脚本给出APPROVE，`requiresUserConfirmation=false`；随后宿主在创建grant前以 `ACCESS_DENIED / REVIEWED_SCOPE_REQUIRED` 拒绝。没有grant或SDK命令回执，没有执行服务器作者命令或导回数据。

根因位于新增备份验收工具：它直接调用 `approveReviewedAction`，没有先通过正式 `openReviewedWorkflow` 建立当前真实Task的审核作用域。源码还表明逐动作须携带完整资源语义；命令与下载调用都需遵守同一合同。修复应只调整该fixture的两个调用入口，复用AUTOMATIC、remember=false、recovery=false的原平台API，完整APP、新grant和SDK消费不变；不能放宽平台门禁或将普通scope接纳当独立审批。

本轮唯一生成物理尝试已知input10265/output103/合计10368，cached input0、miss10265；reasoning usage未给出，不重复加入output。两项必要probe usage/transport及完整账单UNKNOWN。这是备份工具调试用量，缓存0%不构成中断核心工作的理由，也不进入最终八项目指标。

源码3299项、HEAD `00a3085fc3470b17374c82b778f019ec68d2d624`、JAR `891ef1b0cc2d0c7dca23353c299676e9fb24270e28ac878b2214e844eacc42bb`及输入全轮冻结。父已核原log/XML/TXT SHA、审批和平台异常原件；正常CIM核10组自有PID/创建时刻全退，唯一GUID加密配置副本和UDS已精确清理，原配置SHA不变，调查数据库及UNKNOWN保留。原证在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/selective-backup-metadata-142/`，封存为 `sealed-failed-command.json`。

143负责最小调用修复；144只准备新查询草稿，待相关定点测试、源码隔离提交及最终基线封定后执行，不能复用本轮APP或grant。必要业务数据仍未导回，不能暂停或提示可重装。
