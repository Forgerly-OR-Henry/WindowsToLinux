# File Transfer 隔离实机验收 282：保留 UNKNOWN

2026-10-17 唯一调用 `file-transfer-isolated-282-01`，UTC 13:30:31.9740813Z 至 13:39:23.1436808Z。Maven 实际退出 1；JUnit 1F/0E/0Skip、525.750s，失败为 Goal BLOCKED 断言。当前累计第 12 次历史 FULL；File Transfer 尚未完成 BUSINESS v2、真实浏览器及 STOP/DISABLE，CSV 未开始。

## 本次身份与实际观测

| 对象 | 实际值 |
| --- | --- |
| Task | `d8de6c5a-2b8c-42bd-8628-4bba4f88216b` |
| Root | `src/app/db/data/agent-live-file-transfer-3g6o3aly8` |
| App | `wtl-agent-file-transfer-3g6o4vqic` |
| 新端口 | 49284 / 49285 |
| backend 候选 | `wtl-agent-file-transfer-3g6o4vqic-backend-525f7bab44afa87d` |

逐动作独立 APP 和新 grant 后，隔离 probe 实际 READY：17 paths、4 units、2 ports，原 238/280/281 保护 metadata 59 行；free 4314185728 B、required 2617245696 B、reserve 1073741824 B，available memory 3573985280 B、CPU 2。backend 候选 create、归属 query、24765 B 上传、verify、open 均成功；Go resolver 得到 1.24.13。已有 fixed controller 的 READINESS 实际 READY，随后工具准备报 `DEPLOYMENT_AGENT_ISOLATION_UNCONFIRMED`。

错误前没有 stop 标记。父代理仅在已观测 UNKNOWN 后写入 `AUTOMATIC_CLOSE_AFTER_ALREADY_OBSERVED_UNKNOWN`，用于停止排队事后核对，并未暂停整个目标。查询任务的真实 TaskRequest 不支持取消，监视器调用 `requestCancel` 被拒绝，验收进程自然退出 1，没有终止进程。

## 只读账本与原因边界

后续固定只读 reader 实际 javac/read 均退出 0：existing-only 空锁、H2 READONLY，公开 journal 66 个事件逐字匹配，源 pair 属性在锁内及关闭后均不变。未打开 Runtime、读取凭据行、调用模型或 SSH，未做数据库 hash。

主任务平台与 Mod 账本仍 UNKNOWN；1532 条资源记录中 1527 已释放、5 未释放。正式 App、component、release、execution、receipt 均为 0。journal 有 CANDIDATE_QUOTA/CANDIDATE，且原生 stdout 有候选实际创建证据。controller ACTION/RESULT 事件名本身不能证明执行成功或未派发。排队 postQuery `7f30e58e-2ae9-4659-8d88-4200a0c21393` 仍 QUEUED，SQL resourceTotal 为 0；不能据此推断 handler 或远端动作不存在。

静态调用链确认 Agent 存在一处接线缺陷：RemoteTools 构造 BuildTools 时遗漏隔离开关，默认 `reuseExistingOnly=false`；controller READY 后选择 `controller.prepare.v1`，被既有隔离门禁明确拒绝，发生在 scope 重算前。该缺陷与本轮实机失败位置吻合；原始异常未保存底层 cause，因此不声称唯一历史原因已经恢复。283 仅补传已有的 `isolationPolicy != null`，保留禁止集合、默认非隔离行为和 APP/grant 约束。原 281 的唯一 cause 仍未知。

## 本地收尾与保留

CIM 匹配本轮 Java 为 0，terminated 为 0。仅在 owner、nonce、SID、绝对边界及无 reparse 检查后清理自有 f282a/u282a 和加密 credential282 副本；原 source176 未由清理打开。主 UNKNOWN、新候选、queued postQuery、原 238/280/281 及全部日志和运行库保留。没有远端清理、恢复、改判、补 ACK 或旧动作重放，不恢复原 194。

下一次新隔离验收必须把本轮残留作为第四保护集合，先完成直接相关接线与真实排队取消的本地定点验证并提交，再取得实际新隔离 proof。不能复用本轮 grant。File Transfer 完成前不开始 CSV；两个项目都完成后按用户要求暂停并报告最终指标。

## 失败 FULL 已知用量

从封闭 stdout 窗口离线统计，没有为统计增加模型调用。

| 角色 | 物理尝试 | 输入 | 输出 | 输入缓存命中 | 输入缓存未命中 | 输入加输出 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DEP | 13 | 438920 | 27331 | 296576 | 142344 | 466251 |
| APP | 35 | 796920 | 9009 | 648064 | 148856 | 805929 |
| 合计 | 48 | 1235840 | 36340 | 944640 | 291200 | 1272180 |

APP 已知 IO 占比 `805929 / 1272180 = 63.3502%`；输入加权缓存命中率 `944640 / 1235840 = 76.4371%`。48 条记录 IO/cache 已知；DEP 13 条 reasoning 已知且包含于输出，APP 35 条 reasoning 未提供。完整 provider 清单、配置探测、HTTP/账单未知。这里只是失败 FULL，不能替代两项目最终指标，也不与纯恢复窗口混算。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolation-lifecycle-282/`，含 live-preflight、live-cleanup、usage282 及该 FULL 的原始输出/result/JUnit；后续固定 reader 在 `file-transfer-isolation-controller-wiring-283/reader/`。实现基线为本地提交 `182fb22e3100f082daad54450bdcc666ef2564b8`，本记录不改写既有源码摘要。
