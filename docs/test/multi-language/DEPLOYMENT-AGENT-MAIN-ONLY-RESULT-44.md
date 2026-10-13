# task-board 纯主 Agent 部署 44：发布通过，完整业务仍失败

2026-10-13 10:58:23+08，`task-board-main-only-44-01` 自然结束，1 个定点测试 PASS，0 failure/error/skip，1224.969 秒。只部署 task-board，审批模型在发送前被诊断代理拒绝；Runtime 关闭后的实际 APPROVAL attempts/providerCalls/tokens 均为 0。75 次产品诊断授权决策不属于审批模型调用。生产独立审批实现保持原状。

启动源码逐 SHA 等于[43 的 after](DEPLOYMENT-AGENT-QUERY-RECOVERY-HTTP-COMMENT-43.md)，3282 个源码、Skill、样例条目冻结差异 0。模型自主读源、识别、规划、构建、排查、封存、按需使用 Skill、发布、verify 和 finish；监督者没有运行中修改代码/Skill、接手 SSH 或补充部署步骤。实际排障包括工具链准备空间不足后改用已有工具，以及查明旧 loopback 18101 被其他进程占用后调整新计划。原失败观察和被拒绝的过期观察保留。

原 task `e5850b2b-d68a-406a-8200-c702bf141d66`，数据库 `agent-main-only-diagnostic-live-task-board-hip6g6owg`，正式 App `wtl-agent-main-only-diagnostic-task-board-hip6hmm44`，Web `http://45.192.105.213:46900`。平台任务 ENDED/SUCCEEDED、原成功归档、正式完成业务身份一致，revision SHA256 `3ba7a4fea82dbd7668ebdc8fc5f6197610e47bcdb446d739d3a98ee8b14abd5f`，ownership SHA256 `65b6d3b6d96f9b74055eae41b530e922f71a02e20167d7167724e50d75b623df`。

| 本轮实际捕获 provider 用量 | 数值 |
| --- | ---: |
| 物理生成 attempt / 已捕获响应 | 44 / 44 |
| 输入 Token | 2,421,538 |
| 输出 Token | 116,315 |
| 已知输入与输出合计 | 2,537,853 |
| 缓存命中 / 未命中输入 | 1,295,872 / 1,125,666 |
| 捕获输入缓存命中率 | 53.51% |
| reasoning 输出子集 | 95,017 |
| 审批请求 / 调用 / Token | 0 / 0 / 0 |

按物理 attempt 统计，不重复累加逻辑响应。两项 DEPLOYMENT 配置能力 probe 的 provider usage 和 transport 次数仍 UNKNOWN，不能记作 0。本轮是开发诊断，低于最终缓存最低标准的结果保留，不混入最终冻结八项目批次；关闭审批的 0% 也不能证明正式审批成本达标。

普通产品入口仅停止并禁用本轮 App，正式发布、制品和持久数据保留；7 个已捕获 PID 与 UTC 创建时间对应的自有验证进程最终为 0，无 terminate。自建受保护配置 GUID 副本精确删除，原配置双 SHA 不变。父离线复核原报告、日志、冻结、用量、进程和凭据清理，没有重复付费取证。log SHA256 `acf7a6bfb1f7197377d4badde2904fa5517c758598c922932f0c8d9d8157b063`，XML SHA256 `ea1c30607e75afba1d7248315d6d40e8b184030c7bc889d05a36436e9576c0d6`。

[45 同 App 业务轮](DEPLOYMENT-AGENT-MAIN-ONLY-BUSINESS-45.md)首个正式同源 API 请求返回 502，完整业务未通过。46 对原数据库的严格只读投影确认，前端 `API_TARGET` 与后端 `PORT` 均为 18201；正式前端和整应用 health 只访问首页，后端 health 独立访问 `/api/projects`。先前候选代理实际使用 18101，前端未变树重新封存，现有证据不能证明它消费了正式目标。具体 502 原因缺制品正文和错误响应证据，不能归因为审批、CORS 或共享配置传递故障。

证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-main-only-44-01*`、`main-only-comment-live-44/`、`agent-same-origin-proxy-binding-audit-46/`。当前继续只测主 Agent；真实业务、审批闭环、历史清理真实恢复、非默认目录真机及重启、其余七项目、最终冻结八项目均未完成，DB 版本仍为 1。
