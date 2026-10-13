# task-board 当前版本纯主 Agent 结果 34

2026-10-13 08:40:22+08，`task-board-main-only-34-01` 通过产品入口完成当前版本纯主 Agent 部署诊断：1 个定点测试 PASS，0 failure/error/skip，891.687 秒。仅部署 task-board；审批模型暂时关闭。完整运行冻结 3282 个源码/Skill/样例条目，前后差异 0，运行中没有修改代码或 Skill，也没有人工 SSH 接手或逐步部署指导。

用户恢复后要求先核对方向。此前交接把下一真机轮排成原恢复 33 和双 Agent，已纠正：先完成进行中的[本地数据库收尾修复 32](DEPLOYMENT-AGENT-ORIGINAL-DELIVERY-SETTLEMENT-32.md)，再执行本轮纯部署。33 真机恢复未启动。历史[纯部署 23](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-23.md)只作历史证据，不当作本轮验收。

本轮原任务 `11269562-2a68-49b8-97c5-d2c92ad0e786`，隔离数据库 `agent-main-only-diagnostic-live-task-board-epa0562t4`，正式 App `wtl-agent-main-only-diagnostic-task-board-epa06i8dg`。产品服务器身份 `static-live`，实际浏览器入口 `http://45.192.105.213:46700`。主 Agent 自主读取源码及业务约定、识别组件和数据需求、提交计划、处理过时观察与参数拒绝、使用获授权命令构建两个真实制品、封存、按需加载发布 Skill、发布、验证和结束任务。监督者只读取日志与实际用量。

产品回执确认 backend/frontend 均健康，原平台任务自然 ENDED/SUCCEEDED，匹配 App/server/revision/ownership 的正式已完成任务使用记录存在，成功归档已核实。revision SHA256 `c77fbb0aa078a2117ac06d0a6d6877bb5dd57bde543576990686865f510032af`，ownership SHA256 `6baed3ce0dbf37da18e71f3d5f68d5efda9fa4d8cec9370e99b02074bec54df6`。

用户授权的隔离诊断在发送前阻断全部 APPROVAL 请求；Runtime 关闭后实际 approvalAttempts/providerCalls/tokens 均为 0。68 次产品授权和诊断判断仍绑定原 task/owner/server/configuration，不等于审批模型执行了 68 次审查。生产独立审批没有改成自动放行，本轮也不计为正式双 Agent 验收。

| 本轮实际捕获 provider usage | 数值 |
| --- | ---: |
| 物理生成请求/成功响应 | 31 / 31 |
| 输入 Token | 1,497,544 |
| 输出 Token | 56,400 |
| 已知输入与输出合计 | 1,553,944 |
| 缓存命中/未命中输入 | 1,025,280 / 472,264 |
| 捕获输入缓存命中率 | 68.46% |
| reasoning 输出子集 | 42,785 |
| 审批请求/调用/Token | 0 / 0 / 0 |

31 个物理生成 attempt 用量均已捕获，不与最终逻辑响应重复相加。普通单次输出上限 32768。两项 DEPLOYMENT 的 TEXT/TOOL_CALLING 配置能力探测，provider usage 与 transport 次数仍 UNKNOWN，不能记为 0。上述只属于本轮开发对照，不混入历史失败消耗或最终冻结新服务器八项目统计；审批关闭的 0% 不能作为正式审批成本达标证据。

通过普通产品管理入口仅停止本轮 App 并禁用自启，最终 STOPPED/DISABLED；App、正式发布身份、安装制品和持久数据库保留。Runtime 自然退出，正常用户 CIM 按捕获的 8 个 PID 与 UTC 创建时间确认自有验证进程 0，无 terminate。临时受保护配置副本已按精确工作区/GUID 目标删除，原配置源双文件 SHA 不变；原失败、UNKNOWN、旧 App/资源和调查证据保留。初次进程采样同时匹配父子启动器的辅助失败保留，随后按唯一顶层祖先修正，部署未中断。

证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/`：本轮 log、源码冻结前像/结果、独立 run 文件夹内 result、原 XML/TXT、进程和配置清理记录、父复核，以及 `task-board-main-only-34-01-usage.json`。父核没有再次请求模型或服务器。log SHA256 `d0f4243a74f484eb3e78ecf8682937fa3fd7c6d44a4df7d054b5d62c4427d42e`；原 XML SHA256 `e0ea1aaa2c1c8d645de29f5077aa71114aad647d788e1a6c8856a958b099a03f`。

本轮证明当前主部署 Agent 在这些条件下可以自主闭环。后续应优先核对审批侧的判断、通信与反馈；单轮纯部署成功不能直接证明审批一定过严，也不能自动定位[旧 28 的审批传输失败](DEPLOYMENT-AGENT-DUAL-RESULT-28.md)是否已由新机制修复。外部前后端业务/浏览器验收、真实恢复历史清理、非默认目录真机与重启、全部工具覆盖、其余七项目和最终冻结八项目仍未完成。本轮未恢复审批或部署其他项目；DB 版本仍为 1，本地记录不推送。
