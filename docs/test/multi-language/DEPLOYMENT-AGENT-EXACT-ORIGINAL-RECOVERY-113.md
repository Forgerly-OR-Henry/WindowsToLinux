# 原58任务的单次产品恢复实测

2026-10-14，通过产品入口只恢复原Task `4e3c7265-9e4c-4152-87d0-e3009c1630cd`。唯一方法为 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，`originalTaskOnly=true`，`originalPreparationOnly=false`，`configureAgentRecoveryModel=false`。复用原安全配置及独立审批，未新建部署、探测模型、重放原计划、处理其他任务或App，也没有监控者直接SSH操作。

实际窗口18:30:03.0661619至18:30:33.3388684（UTC+8）。指定方法1项失败、0错误、0跳过，XML时间21.713秒，Maven退出1。产品返回原Task `UNKNOWN`、outcome为空、progress为 `AI_RECOVERY_COMMAND_RECEIPTS_SETTLED`，保留两个原源码快照引用及“Business result requires explicit reconciliation”阻塞。Runtime关闭另有 `Original Mod recovery still owns resources` 附属异常。命令回执推进不等于原业务结果确认；本轮尚未确定后续健康验证、审批或证据校验的具体停止原因。

不能把旧58的HEALTHY/FINISHED事件或本轮离线预检当作新健康验收。原App `wtl-agent-main-only-diagnostic-task-board-30lc1unw7` 当前物理状态、正式登记及完整结清均未证实。保留UNKNOWN、Agent明细、原数据及调查证据，没有用强制成功、整表删除或恢复旧数据库字节消除阻塞。本轮未重试付费恢复。

本次有界日志窗口中识别两次APPROVAL调用及对应两项物理attempt，均SUCCEEDED。已知input 13,811、output 726、合计14,537 Token；cached input 8,960、cache miss 4,851，已知输入缓存比例约64.88%。reasoning 282属于output子集，不重复相加。两次requested/effective输出上限均8,192，不超过普通部署上限32,768。排除42项历史usage；100条日志窗口不是完整账单证明，provider总调用和费用仍UNKNOWN。这是失败恢复调试用量，不计入最终冻结八项目综合指标，也不能以已观察APP调用解释完整部署审批占比。

运行前后source3297严格等于112最终manifest，生产JAR SHA256为 `66038d993f6d02f1ee17addbde3b1dd36bff81c8c1363b93398ca4f08f85953b`。冻结检查退出0；运行HEAD为 `e06d4548d1d66eec90091bfdf7723799dbe5fc79`，index为空。没有运行中源码、配置或Skill修改。原MDB的合法变化留存，db-access字节保持原值，不回写旧MDB。普通用户CIM正枚举362项并确认自身，13组本次PID/创建时间组合均已退出。

原证目录：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original58-product-recovery-113/task-board-original58-recovery-113-01/`。保留runner-used、freeze-used、source/data before/after、maven.log、原XML/TXT及SHA、result.json、usage.json、evidence-summary.json与parent-process-closure.json。单次实测失败与后续调查分开记录，不覆盖原轮。

下一步只读取本轮原任务的已持久化诊断定位停止原因，不为补齐证据重跑模型。task-board新完整独立审批部署仍未通过；其他七项目、最终全新服务器冻结八项目、非默认目录真实服务器部署及整机重启恢复仍未验收。历史102纯部署业务通过与105独立审批部署失败结论保持不变。
