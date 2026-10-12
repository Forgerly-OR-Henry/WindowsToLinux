# task-board 修复后双 Agent 结果 28

2026-10-13 06:40:50+08，`task-board-dual-agent-28-01` 已结束：1 个定点测试失败，0 error/skip，原 XML 测试用例耗时 998.699 秒，平台保留 UNKNOWN。此次在[27 的最小反馈及按需 Skill 修复](DEPLOYMENT-AGENT-VERIFY-FEEDBACK-27.md)本地提交后，通过正常产品入口仅部署新 task-board，真实独立审批 MANUAL_REVIEW、remember=false。源码、配置和 Skill 冻结 3281 项，运行期间零变化；不重放原冻结计划，没有监督代理接手 SSH 或提供现场步骤。

原任务 `9228983d-f4d9-4c7f-b13d-e3663be122ca`，新 App `wtl-agent-task-board-c4dw3iekn`，DB 目录 `src/app/db/data/agent-live-task-board-c4dw1tacc`，计划 HTTP 端口 46600。主模型自主识别、调整计划、声明数据库、准备工具链及选择命令能力；远端有 Maven BUILD SUCCESS，不能将它当成整轮成功。最终无发布成功、finish、任务成功归档、真实业务或浏览器检查点。平台保留两项源码快照占用及 Business result requires explicit reconciliation。

最后的明确错误是日志第 2359 行审批 AiService 返回 UNKNOWN：`AI_TRANSPORT_UNKNOWN`、`HTTP_CHUNK_INCOMPLETE`、IOException > EOFException。这是提供方 HTTP 响应被截断，实际原因是提供方、链路还是客户端仍未定位，不能当作审批 DENY 或 SSH/SFTP 故障。此前捕获的 24 条正式 submit_result 全部 APPROVE；截断后没有新的 LANGUAGE 请求，主模型没有下一轮可处理的反馈。最后进度文本 Transferred 26283 仅记录传输进度，不能据此判断传输失败或把整个命令、来源上传及已完成准备改为 NOT_SENT。需要从原请求、grant/send 与回执确认具体待审调用的边界。

这一轮在新的阻塞处结束，未到 24 的最终 publication verify 问题。因此不能用它证明 27 已通过真实模型验收，也不能用它证明审批过严或主模型部署能力不足。当前纯部署闭环成功证据仍以[23](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-23.md)为准；24 的独立审批问题及正确拒绝另行保留，不将历史结果改为新成功。

| 本轮捕获 provider usage | 部署 | 审批 | 合计 |
| --- | ---: | ---: | ---: |
| 输入 token | 915,410 | 1,242,011 | 2,157,421 |
| 输出 token | 34,719 | 39,176 | 73,895 |
| 已知输入与输出 | 950,129 | 1,281,187 | 2,231,316 |
| 缓存命中输入 token | 703,360 | 861,056 | 1,564,416 |
| 缓存未命中输入 token | 212,050 | 380,955 | 593,005 |
| 捕获输入缓存命中率 | 76.84% | 69.33% | 72.51% |

捕获审批占已知输入与输出 57.42%。120 条监控请求尝试中，20 条 LANGUAGE、99 条 APPROVAL 有响应 usage，剩余 1 条审批请求的用量 UNKNOWN，不能当作零；请求尝试不等于完整提供方计费证明。四项配置探测的 provider usage/transport 次数也 UNKNOWN。部署 reasoning 24,134 是输出子集，99 条审批 reasoning 字段均 UNKNOWN。普通部署单次输出上限 32768、审批 8192。以上仅为失败开发轮的已捕获子集，不计入最终冻结新服务器八项目统计。

Runtime、Maven 和统一 exec 会话 55958 已退出。正常用户 CIM 根据捕获的 exact PID/CreationDate 核对，本轮自有验证进程为 0，未终止其他进程。临时受保护配置副本 28 已按精确工作区/GUID 目录清除，原配置源双 SHA 不变。此轮未创建浏览器业务标签，没有伪造 browser-result；未停止、禁用或删除本轮 UNKNOWN 应用、候选、正式记录、持久数据或原调查。DB 版本仍为 1。

原 log/XML、冻结前像/结果、一次保存的最终 usage、result.json、processes-final.json、credential-cleanup.json 及父核结果保留在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-dual-agent-28-01/` 和同名前缀文件。父核复用现有证据，独立解析 24 个 APPROVE/0 DENY、HTTP 截断与后续主请求缺失、各字段用量、原 XML、3281 当前字节及清理记录，没有重发模型或服务器请求。log SHA256 `baff6d7363dc097d8888ad11faa103f6db6fc9866c057ff46cc19398fc95a3ec`；XML SHA256 `99f150de571af8f5439e04476b862fb563dcabc657276bdda97083f031ee2454`。

下一项先只读核对审批传输失败的具体执行边界、现有反馈/重试及产品原任务恢复路径，避免另一轮完整付费重跑。未能证明结清的 UNKNOWN 和占用继续保留；实际恢复必须通过产品入口。真实前后端业务、浏览器、完整工具覆盖、成功恢复历史清理、非默认目录部署与重启、其余七项目及最终冻结八项目仍未完成。
