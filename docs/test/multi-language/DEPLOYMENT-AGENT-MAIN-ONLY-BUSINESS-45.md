# task-board 业务轮 45：正式同源 API 返回 502

2026-10-13 11:06:47+08，`task-board-business45-01` 自然失败退出：1 个定点测试，1 failure、0 error/skip，原 XML 时长 342.769 秒。复用[44 的新正式 App](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-44.md)，不重新部署、不调用模型、不执行新配置能力 probe；Runtime 关闭后实际模型 attempts 为 0。3282 个源码、Skill、样例条目冻结差异 0，逐 SHA 与 44 和 43 after 一致。

原 task `e5850b2b-d68a-406a-8200-c702bf141d66`，App `wtl-agent-main-only-diagnostic-task-board-hip6hmm44`，数据库 `agent-main-only-diagnostic-live-task-board-hip6g6owg`。原归档和正式完成身份、产品 START/ENABLE、运行状态检查、页面和 runtime-config 读取通过；配置解析出的 API 与 Web 同源。首个 GET `http://45.192.105.213:46900/api/tasks?projectId=1` 返回 502，2xx 断言失败，位置为原 `browserApi:2266`、`business:2134`、业务测试 `:1340`。

尚未进入 POST、STOP/START/RESTART 持久化序列或 browser READY，没有创建本轮浏览器页或成功 checkpoint。错误消息只保存状态和 URI，未保存响应正文或必要 headers；不能由 502 判定具体代理异常。没有新的 UNKNOWN。产品 finally 真实将该 App STOPPED/DISABLED，Runtime/Maven 退出，7 个已捕获 PID 与 UTC 创建时间对应的自有进程最终为 0，无 terminate。正式 App、原发布、配置、持久数据和其他历史记录保留。

46 严格只读原数据库，实际 canonical codec 验证整 release/revision/ownership 和两份配置摘要；READY、schema 1、原 MDB 前后字节 SHA 相同。两次辅助读取器失败保留，第三次投影通过，无真实 Runtime/SSH/provider 调用。正式前端 runtime configuration 为 `API_TARGET=http://127.0.0.1:18201`、`PORT=46900`，后端为 `HOST=127.0.0.1`、`PORT=18201`。这排除了正式记录漏配目标或仍声明旧端口。

确定的覆盖缺口是：候选代理实测 18101 后，前端旧树被重新封存；正式前端与整应用 health 仅首页，后端单独健康检查 `/api/projects`。这些通过结果不证明当前同源代理链联通。共享代码有 runtime configuration 到 systemd EnvironmentFile 的传递路径，未发现其确证缺陷；远端实际环境、`run-web.sh`/`server.mjs` 消费正文仍未读取。因此下一修复限定 Agent 契约和按需 Skill 的配置消费、变更后跨组件证据语义，不据猜测修改共享 helper 或热改原 App。

父按原报告摘要及修改时间、明确 selector、原身份、完整冻结和进程记录完成离线复核，没有重跑付费部署。log SHA256 `f3c601838ea739e943cd94684d7af005e0bac7edce5080c11488be8e8c3a0d16`，XML SHA256 `95f0378e22cf84e33c95ef39212effcb8633589da910a0426a388c30e791f95d`。证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-business45-01*`、`main-only-business-live-45/`、`agent-same-origin-proxy-binding-audit-46/`。44 发布通过与 45 业务失败分别保留；完整纯主部署尚未通过，审批继续关闭，不能判断审批是唯一原因。
