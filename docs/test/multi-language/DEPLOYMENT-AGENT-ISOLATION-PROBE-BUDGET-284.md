# Agent 隔离探测批量观测与有限总预算 284

283 在四保护集合的只读隔离探测中发生 30s SSH 超时，没有取得 READY。确认固定探测程序存在总预算缺口：32 次顺序 systemctl 查询各有独立 2s timeout，却共用外层 30s。不能从这一缺口推断本轮唯一卡点；原任务及超时证据保留，不重放。

本阶段仅修改 Agent 隔离 Probe、直接相关测试及实机 fixture 的第五保护集合，保留 Java 30s、96 行/24576 B、原 gate、APP 和新 exact grant 约束，不改变 STATIC/ASSISTED 原生编排。

- 每轮分别批量查询精确保护 unit 集合和新组件 unit 集合，成功路径共四批；前后轮重新观测，不跨轮缓存。Id 必须与请求闭集逐一匹配，缺失、重复、foreign、属性不完整或异常输出均拒绝；Id 不加入既有 metadata 摘要内容。
- 每轮只列举一次 work 目录，再以原应用及 unit 前缀筛选。仍保留 4096 个目录项、16 个候选及原路径/所有权/排序/截断边界；第二轮重新列举。
- Python 采用 20s 协作式总预算，子进程 timeout 为 `min(2s, remaining)`；有限阶段检查预算，耗尽输出完整 UNKNOWN/UNCONFIRMED，不能放行 READY。此预算不能中断阻塞的文件系统调用，不构成硬墙钟时限保证。
- 按 283 真实接受计划加入第五保护 Task/App/backend/web/端口，候选集合为空，不猜候选身份。平台 UNKNOWN 与 Mod FAILED 分别保留。五集合保守投影 94 行、19337 B，现有限度仍够用，未再次扩上限；这是投影，不是新远端事实。

批量输出契约核对了 systemd 官方源码：[systemctl-show.c](https://github.com/systemd/systemd/blob/main/src/systemctl/systemctl-show.c) 在 unit 记录间插入空行；[systemctl.c](https://github.com/systemd/systemd/blob/main/src/systemctl/systemctl.c) 对明确的 property 请求保留空值。目标服务器具体版本的输出仍须实测，未把当前上游源码当目标版本证明。

## 实际定点验证

仅 `DeploymentAgentIsolationTest` 以下四方法，offline Maven `-pl src/mod/deployment -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false package`：

1. `completeFiveRetainedNamespacesStayWithinBoundedReceipt`：94 行合法、97 行拒绝、实际超字节上限、缺项/foreign/无序/truncated 拒绝。
2. `fixedPythonProtectedProbeUsesSameFiniteMetadataBound`：执行固定 Python AST 中对应的五集合有限枚举。
3. `fixedPythonUnitBatchesRequireCompleteExactIdentityMaps`：完整属性、not-found 和空 UnitFileState、缺项/重复属性/重复 Id/foreign/nonzero/stderr。
4. `fixedPythonProbeKeepsFreshBatchesWithinAggregateBudget`：执行实际 try/emit 流程，健康四批/两次 work 列举，输出顺序与 argv 相反仍按 Id 对应，第二轮漂移 UNKNOWN，受控时钟模拟前期耗时后子调用 timeout 1.5s/0.5s，20s 耗尽输出完整 UNKNOWN，并由 Java 严格解析。

唯一 test-01 实际 4P/0F/0E/0Skip、JUnit 2.407s；Maven 19.429s、package 退出 0，UTC 14:19:48.5369302Z 至 14:20:08.8454193Z。三源在 package 开始后未改变，deployment.jar 4247180 B、实际落在该时间窗。没有全量测试、模型、SSH 或远端隔离证明，未重跑 283 已过且本阶段未受影响的接线/取消方法。

CIM 本轮 Java 0、terminated 0；核 owner、绝对边界和无 reparse 后仅清 c284a/u284t，保留原始证据。辅助目录为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolation-probe-timeout-284/`，含 before、受控 fixture、结果和尺寸投影。

## 交付与下一步

因工作区存在前序迁移等其它改动，交付相对于本阶段保存原字节的三路径窄 patch；reverse check 已通过。仅在本地提交时生成七项限定摘要（三源、JAR、patch、本说明、283 LIVE），不做逐次/全源/依赖 hash，不推送。

原 194 不恢复，原 238/280/281/282/283 及候选、queued postQuery 不改判、不清理、不重放。下一次唯一新 File Transfer 实机使用 49288/49289、五保护集合，实际准入必须来自新的接受计划和产品探测；每动作独立 APP 和新 grant。File Transfer 完成 BUSINESS v2、真实浏览器及 STOP/DISABLE 后才开始 CSV；两项目完成后暂停报告指标。
