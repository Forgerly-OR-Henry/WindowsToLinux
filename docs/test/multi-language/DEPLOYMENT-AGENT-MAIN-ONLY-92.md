# task-board 纯主 Agent 真实验收 92：模型自主停止

唯一产品入口轮 `task-board-main-only-92-01` 于 2026-10-14 12:21:52.4051216 至 12:26:57.8748893（UTC+8）自然结束。`DeploymentStaticLiveAcceptanceTest#deploysWebsiteThroughMainAgentDiagnostic` 原 XML 为 1 FAIL / 0 ERROR / 0 SKIP、297.309 秒，Maven 退出 1，公开失败码为 `DEPLOYMENT_AGENT_STOPPED`。本轮未通过验收。

运行基于本地提交 `953cf99a913e3aa99bbee85d0291d38032191f6a`，JAR SHA-256 为 `d9cbe1a50ecf316592896522679f2f54769bf167dccb9ec1de53d3765292d647`。3296 项源码开始与结束均匹配 91 封口原像，源码和 Skill 未中途修改。91 的变化仅为验收测试输入桥，相关 19 个生产 class 和 15 个 Agent 资源与 88 字节相同。产品实际 SSH 指纹与原授权一致；Root 仅监控，没有接管 SSH 或提供部署步骤。

实际 12 次 LANGUAGE 响应。模型自主 inspect、source_index、read 16 次、identify、plan 2 次、Skill、host_command 3 次，最终调用 unable。阶段只有 INVESTIGATING、IDENTIFIED，未进入 PLANNED、PREPARED、发布、业务或浏览器验证。本轮没有 input，91 输入桥未在真实模型路径中被调用，其直接定点通过不能替代真实输入验收。

本轮数据库根为 `agent-main-only-diagnostic-live-task-board-6t2jectdg`，请求 App 为 `wtl-agent-main-only-diagnostic-task-board-6t2jfymbc`，公开目标端口为 48000。调查数据库与原 89、86、83、80、74、58 等未结清记录及证据保留；没有恢复、重放、强制改写任务状态或删除调查数据库。是否具备可清理的恢复明细仍须按原操作身份、资源和回执核对，不由本轮失败码推定。

后续 93 只读核查确认，两个 plan 参数校验通过，均收到绑定原任务 `515e4a2f-7ffb-43ad-ab1f-0d2a27877674` 的 `WORKSPACE_HOST_INSUFFICIENT DATA` 拒绝。完整需求、组件分配与 DATA 内 required/available 值未保留在日志的 512 字摘要中；不能由这两份计划推定所有合法方案都不可行。正式容量 stdout 为 1228009472 字节，主机命令后新观察为 1228001280 字节，下降 8192 字节。

三次 host_command 中，两条主执行及正式回执查询均 SUCCEEDED、exit 0、未截断；回执绑定同一原任务与 operation `4214bd86-0a84-4ee3-bce9-7bc047db3b22`、`33889982-208b-42f3-b042-bf57d18f9b7c`。另一条在 assessment 参数校验时被拒绝，未派发。exit 0 不证明资源清理或全部效果结清。原日志没有 TaskSnapshot、GoalSnapshot 或资源 owner/released/lease 字段，任务实际状态和资源结清仍未知，未连接数据库求证。

unable 的实际 reason 原参数未留。生产链先以 HANDOFF 观察它，再抛固定 `Deployment model cannot continue`；正式 journal 只保存 tool 和 digest，不能把固定异常消息当成模型实际理由。93 三份封口文件、42 个原日志行哈希与必要源码已独立核对。下一最小修复限于验收程序的容量白名单和有界、遮蔽后的模型停止说明，后者须标为模型自述；不改生产 Quota 或替模型选择计划、释放磁盘，也不付费重取原 reason。

Runtime 关闭后的审批 attempts、providerCalls、Token 均为 0。12 次部署 provider usage 全部字段已知：input 438348、output 41288、合计 **479636 Token**；cache hit 254848、miss 183500，输入缓存命中率 **58.1383%**，缓存字段一致。reasoning 34582 是 output 子集。两次能力探针 usage 与 transport 次数未知，另列，不按零计。本轮为独立失败调试轮，不混入最终冻结八项目批次，也不构成正式审批验收。

初始和实际运行时分别记录 7 个 PID/创建时间，去重后 7 个已知身份经正常用户 CIM 核对全部退出，未人工终止；短采样不声称穷尽所有短进程。仅本轮 GUID 受保护配置副本已精确删除，原 clean 配置双 SHA 不变，调查数据库删除零。

原日志 SHA-256 为 `963bf098419cba42a45a0a1a8f3e2caa81239cd5c3760a809446cbb2791876ab`，XML 为 `fa7c52057bc18549491324f03b73c367aa6bf5c1c5219e25e68f183552b820c8`，TXT 为 `0352680eb877d147ed0fff0b625fd2dae505830a73532310de21517714ec96b5`。原报告、时间、冻结源码、用量和进程收尾已核；该核对未新增模型调用、SSH、数据库读取或业务测试。

task-board 完整业务、浏览器、重启和产品停用仍未通过。真实历史恢复与明细清理、非默认目录真机部署及重启、渐进审批、其余七项目和最终八项目验收尚未完成。后续先核实本轮自主停止原因，不重复付费取证，不把历史结果当成新轮通过。
