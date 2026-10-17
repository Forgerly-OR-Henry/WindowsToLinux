# Agent 隔离实机中断记录（281）

2026-10-17 用户要求暂停以更新 Codex。本轮已停止，File Transfer 尚未完成 BUSINESS v2、真实浏览器和 STOP/DISABLE，CSV 未启动。原目标仍为 File Transfer → CSV 严格串行，两者完成后再暂停汇报最终指标；本次提前暂停不算验收完成。

## 本轮事实

- 唯一运行：`file-transfer-isolated-281-01`，UTC `10:32:10.3449599Z` 至 `10:38:59.9409094Z`，Maven 实际退出 1。
- Root：`src/app/db/data/agent-live-file-transfer-v0wjv3nds`；Task：`c236a665-56e5-44b3-8acb-bfe167d97b97`；App：`wtl-agent-file-transfer-v0wjwq48s`。公开端口 49282，内部端口 49283。
- 实机隔离 probe 经独立 APP 和新 grant 返回完整 `READY`：paths 17、units 4、ports 2、protected metadata 40 行；可用磁盘 5263634432 B，需求 3288334336 B，预留 1073741824 B；可用内存 3577393152 B，CPU 2。该次准入不代表后续动作或最终业务成功。
- 后端候选创建命令经独立 APP 和新 grant 实际 `SUCCEEDED/0`，回执路径为 `/var/lib/windowstolinux/work/wtl-agent-file-transfer-v0wjwq48s-backend-525f7bab44afa87d`，已保留。
- 停止期间观测到 `ApiException: Agent isolation is not confirmed for this exact action [ACCESS_DENIED/DEPLOYMENT_AGENT_ISOLATION_UNCONFIRMED]`，Task 日志为 `UNKNOWN`。本次未继续定位原因，不能将其单归因为用户取消，也不能改判成功或未派发。

## 暂停与进程收尾

先在本轮目录创建 `agent.stop`，产品实际记录 `AGENT_LIVE_MONITOR_CANCEL`、`CANCEL_REQUESTED`、`UNKNOWN`。随后验收脚本停在 `verifyRetainedIsolation/completed` 的事后只读核对等待；停止标记只针对 Agent，未让该等待退出。

保存平台及虚拟线程快照后，核实主线程等待点、快照中未见远端操作栈，并按 PID、出生时间、父进程、当前用户和本轮 f281a 路径校验，仅结束自有测试 fork PID 35776。父 Maven 随后实际退出 1。没有取得完整 JUnit XML；Surefire 的 `Tests run: 0` 是中断结果，不能计为通过或完整失败用例。事后保护集复核未完成，不能宣称旧资源事后不变或新任务已结清。

退出后 CIM 匹配本轮 Java 为 0。仅在 owner、nonce、SID、绝对目录边界及无 reparse 核验后，删除 f281a、u281a 和本轮加密凭据副本；原凭据源未由清理打开。保留新 UNKNOWN 运行库、远端候选、原 238/280 运行库、全部日志、线程快照及 Surefire dumpstream。未执行远端清理、恢复或旧动作重放，未恢复原 194。

## 本轮已知用量

仅从本次完整保留的 stdout 物理生成记录离线统计，没有为统计发起模型调用：

| 角色 | 物理尝试 | 输入 | 输出 | 输入缓存命中 | 输入缓存未命中 | 输入加输出 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DEP | 8 | 183407 | 7019 | 124928 | 58479 | 190426 |
| APP | 16 | 251094 | 4040 | 194816 | 56278 | 255134 |
| 合计 | 24 | 434501 | 11059 | 319744 | 114757 | 445560 |

APP 已知 IO 占比为 `255134 / 445560 = 57.2614%`；按输入 token 加权的缓存命中率为 `319744 / 434501 = 73.5888%`。24 条观测记录的输入、输出和缓存字段均已知；DEP reasoning 8 条已知，APP reasoning 16 条未提供，reasoning 已包含于输出，不重复相加。完整 provider 调用清单、配置探测用量、HTTP/账单仍未知。这是中断 FULL 窗口，不能替代两个项目完成后的最终指标，也不与纯恢复窗口混算。

## 续接入口

实现及两个新增定点测试已提交 `9fffe83bafce5d3579c2e408824a2e0c46c4153c`；2P/0F/0E/0Skip，package 退出 0，未执行全量。暂停阶段没有再改产品代码或重跑测试。当前累计 11 次历史 FULL 调用，当前两项目窗口中完整验收仍为 0。

更新 Codex 后先读 `CURRENT.md` 顶部及本记录，再只读核对 281 的真实账本、候选归属和资源状态，定位新闸门错误及后核对等待。不得直接重放本轮，不得凭日志补齐缺失结果；任何后续远端动作继续独立 APP 和全新 grant。原 238/280 保留 UNKNOWN，原 194 不恢复；File Transfer 未完成前不启动 CSV。若另开隔离验收，保护集合必须包含本轮实际残留，不能沿用仅含 238/280 的旧前提。

实际证据目录：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolation-feedback-281/`，重点为 `pause281.json`、`usage281.json`、`live-cleanup.json` 和 `file-transfer-isolated-281-01/` 下的 result、stdout、停止标记、线程快照及终止归属核验记录。原始运行库尚未进行事后只读核对，正式资源数量未知，不填零。本记录单独提交，不重写已经提交的 281 源码摘要。
