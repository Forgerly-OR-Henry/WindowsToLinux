# 204：第2轮完整产品复验未通过

2026-10-16 00:10:28—00:16:02 +08，唯一`task-board-cached-probe-204-01`自然结束：原XML **1 FAIL／0 ERROR／0 SKIP，327.961秒**；Maven exit1、源码冻结exit0。第2轮计为未达标，累计2/3，第3轮尚未启动。不得重开本轮或改名重试。

原Task `bb282f24-9487-40d2-ad7b-277c7e13699f`的原始TaskSnapshot为UNKNOWN，保留两份FILE及业务结清blocker；原XML中的Goal observation为BLOCKED。未完成前后端业务、真实浏览器或最终产品STOP／DISABLE，不把进度日志UNKNOWN代替Goal状态，也不把Goal直接当底层Task结果。

## 本轮实际情况

通过产品完整Agent入口、AUTOMATIC独立审批、remember=false，主模型LOW／审批NONE、32768／8192，保留四必要fresh能力检查、90分钟业务等待及15分钟真实浏览器检查点。目标使用已授权重装后SSH指纹，当前DeepSeek有效地址和模型与178已有许可一致；凭据仅由受保护产品配置交产品使用，不作为模型正文发送。Root没有SSH、提供部署命令或重放旧任务。

首个计划收到WORKSPACE_HOST_INSUFFICIENT：可用5,442,539,520字节，小于5,905,580,032字节需求；当时两组件各选最低1 GiB／优选2 GiB，主机1 GiB余量保持，拒绝没有创建该计划的新inputs。模型继续自主处理并进入后续上传，不将此首个拒绝记为最终失败。

最后独立DENY发生在固定helper的`agent-open`，与[197](DEPLOYMENT-AGENT-CONTROLLER-LIVE-197.md)的归档观察不同；本轮此前verify命令有SUCCEEDED回执。审批读取了完整当前输入，并另取合同、业务文件、源码和回执。此时尚未核完agent-open的实际守门和所要求证据的先后关系，不能把DENY宣称为误拒，也不直接修改政策要求其批准。没有证据表明[203](DEPLOYMENT-AGENT-CACHED-PROBE-CAPACITY-203.md)的64 MiB探测已经在本轮真实完成。

保留Runtime `src/app/db/data/agent-live-task-board-4ckx594sk`、App引用`wtl-agent-task-board-4ckx744zg`及候选／上传／UNKNOWN原证；引用不证明正式App已发布。201仍FAILED_UNSETTLED，旧资源未释放，不从本轮日志补结旧任务。

## 已知用量

按平台generation id:number去重，共40次＝部署10＋审批30。

| 角色 | Input | Output | Cached input | Cache miss |
|---|---:|---:|---:|---:|
| 部署 | 273731 | 13408 | 184576 | 89155 |
| 审批 | 354099 | 12243 | 285184 | 68915 |
| 合计 | 627830 | 25651 | 469760 | 158070 |

已知input＋output为653,481，审批366,342，占56.0601%；input加权缓存74.8228%。40行cache字段一致，未知cache行0；部署reasoning8,856已包含output，审批30行reasoning未知，不填0或重复相加。四必要能力探测、完整物理HTTP与账单仍UNKNOWN，没有重复付费补证。

这是失败窗口，未达到完整业务和审批≤20%要求，不能与186完整82.9103%或199失败截面作等覆盖因果比较，更不能当冻结八项目指标。缓存≥60%本身不能抵消本轮业务和审批未达标。

## 冻结、收尾与原件

输入HEAD `cd08f684be243c9948630ced5bae625d0641bc1a`，实际3307源码与203最终manifest `b077894166df30b4d2a17081ac97b43b6a0c118ff81815002faa81c92e56f6b0`相同；JAR SHA `690d52e5c0d2030aa8acada3708aabe4cf860fc2e8814dc6d015153b368127b3`前后不变。新捕获39项CP／1041文件、19关键类／19生产组／5控制器资源，原XML实际类路径另核；这是记录路径和冻结字节解析，不冒称JVM Codesource插桩。

唯一exec67194已终态exit1。正常用户CIM确认10组自有PID／UTC创建时间全部退出；精确marker TMP／短UDS及fresh204-GUID私有凭据副本已清，原176受保护pair逐SHA不变。原调查Runtime、正式App、旧UNKNOWN和远端资源删除0。本轮没有打开浏览器。

原件根`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-cached-probe-live-204/`：

- `task-board-cached-probe-204-01/sealed-evidence.json`：`7dbaa55c5efa9ce453a2c70c12d319baa1fb717c872947a2ce2ae808094a0fc9`。
- 父核`parent-failure-closure204.json`：`89e9880dd37ef5d52a1dc016952ace97b0561a95ed396aba534949384ad74f88`。
- 64份原件`sealed-final204.json`：`1c45f1e584d014572a048585c837219131811f6c5b045d6ffab4358ea4f42fb6`。

本地提交仅此失败记录，不推送。下一步第3轮先只读核本次具体拒因和主要审批成本，再选择最小实现及必要方法；不降低完整输入、独立审阅、新grant或UNKNOWN条件，不给模型答案。最低缓存≥60%／审批≤20%、理想≥80%／<10%保持；第3轮仍未达到最低线即按用户明确条件暂停。完整原Goal及其余七项目、冻结新机八项目、非默认目录和重启恢复均未完成。
