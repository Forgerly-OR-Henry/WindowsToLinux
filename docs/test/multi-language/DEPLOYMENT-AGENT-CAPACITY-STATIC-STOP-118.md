# task-board 独立审批部署：容量与宿主入口阻塞（118）

2026-10-14 20:11:31—20:20:29 +08，只通过产品入口运行 `DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry` 的 task-board 项目一次，FULL AGENT/AUTOMATIC、remember=false。原报告 1 FAIL/0 ERROR/0 SKIP，530.437 秒，Maven exit 1、源码冻结 exit 0。任务 `dc683ad6-3e03-40b6-a993-59f8c939de78` 最后返回 FAILED/`DEPLOYMENT_AGENT_STOPPED`，模型主动声明 unable；没有应用发布、真实业务或浏览器验收。

运行 HEAD `768f40cab925fa9d96a5b3182d70290f01a45492`，源码 3299 条与 117 最终清单完全一致，deployment.jar SHA `9b53d5db4c4e78f4ae4df58c841310eef3b9b27056189dc47580ac7d3785db27`。公开请求端口 48700 交产品核查。部署与审批模型自行读取新来源、API 和前后端配置、识别组件、修改候选源码并提交计划；Root 未 SSH、提供具体部署步骤、热改源码、重放旧计划或按普通用量停止。

模型最初选择两组件 AUTO 工作区：backend 最低 512 MiB/优选 1 GiB，frontend 最低 256 MiB/优选 512 MiB。产品实际容量反馈 `WORKSPACE_HOST_INSUFFICIENT`：availableBytes=1320235008，requiredBytes=3221225472，包含 hostReserveBytes=1073741824 和 AGENT 的 hostCostMultiplier=2。模型随后自行下调到各 48 MiB，再尝试 AUTO 最低和优选均为零；平台按来源和配额下界保留每组件 128 MiB，所需总量下降到 1610612736，但最后可用 1320144896 字节仍不足。实际选择与调整证明这次 AGENT 没有固定使用 4 GiB；不代表另外两模式已完成真机动态选择验收。

模型自行提交一次 `host_command` 回收提案。原日志明确 `AI_LIVE_TOOL_REJECTION`：`DEPLOYMENT_COMMAND_STATIC_DENIED: NOT_SENT`，bodySha=`be4ae8b47a93b226016c05d58619b75dd96591c7ce6185e2026236cebd072b05`、envelopeSha=`7e085c06f1d1eddc83e0928f5c7f7ea4e8a37cd1acf8acfe0c0e433b22b7efb1`。该命令未发送，也没有进入新的独立审批调用。源码 `DeploymentAgentRemoteTools.hostCommand` 在调用命令 Runner 前执行现有固定命令 static gate；工具描述将此路限定为系统包前置安装。模型的有界 unable 正文声明拟回收 apt 缓存、日志和临时条目，未保留的完整命令正文不能从这段声明重建，也不能据声明判断全部删除对象安全。该轮阻塞不能直接归为审批模型拒绝或部署模型缺乏推断能力，后续须核对真实可用的宿主维护入口及其独立审批边界。

已知物理调用为部署 14 次、审批 10 次，均有 provider usage：部署 input=530471/output=63914/合 594385，审批 input=81295/output=5615/合 86910；合计 681295 Token，input=611766、output=69529、cache hit=394112、miss=217654。已知缓存比例 64.4220%，审批占已知总 Token 12.7566%；reasoning 属于 output 子集，不再次相加。四次原夹具必需能力探测的用量和 transport 总数仍 UNKNOWN，完整账单与费用 UNKNOWN。部署单次上限 32768、审批 8192，未见超限。以上只属于本次失败调试，不混入最终冻结八项目指标，不重复付费补证。

正常用户 CIM 正核 11 组本轮 PID/UTC creation ticks 全部退出，未终止其它进程。本轮精确 GUID 安全配置副本已删除，原配置 pair 字节不变，调查数据库删除零；旧 58/74/116 UNKNOWN 及证据保留。本轮 before/after 源清单、原 XML/TXT 和日志 SHA、实际已知用量及关闭证据均由父核验，不再运行其它测试或读取数据库。

证据目录：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/approved-full-business-live-118/task-board-approved-118-01`，包括 `sealed-evidence.json`、`parent-known-usage.json`、`parent-evidence-verification.json`、`processes-final.json`、`credential-cleanup.json` 和原失败报告。task-board 完整独立审批验收、其它七项目、全新服务器冻结八项目、非默认目录真实部署与整机重启仍未完成。后续先只读核对已证入口限制，待最小修复完成再启动新轮。
