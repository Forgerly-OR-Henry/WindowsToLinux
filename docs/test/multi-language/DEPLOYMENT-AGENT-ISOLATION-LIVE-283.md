# File Transfer 283：只读隔离探测超时

唯一 `file-transfer-isolated-283-01` 实机调用 UTC 2026-10-17T14:05:28.9957884Z 至 14:09:01.7915407Z，Maven 退出 1；JUnit 1F/0E/0Skip、207.5s，失败为 Goal BLOCKED 断言。第 13 次历史 FULL；本轮仍无完整 File Transfer BUSINESS v2/浏览器/STOP-DISABLE 结果，CSV 未开始。实现基线本地提交 `730cb8208c57c76b74603bdbf069d3c94d45c490`。

| 对象 | 实际值 |
| --- | --- |
| Task | `af2f6e5b-08c1-4977-beae-533e7fa679f9` |
| Root | `src/app/db/data/agent-live-file-transfer-46xyvhwgw` |
| App | `wtl-agent-file-transfer-46xyx53kc` |
| 计划端口 | 49286 / 49287 |

产品先按实际 3307339776 B 可用空间拒绝 AUTO 预算，再由模型重新规划；接受计划的 backend workspace 为 671088640 B、web 为 402653184 B，计划摘要 `b62ab9d8f8977e69488a943dd7de98f19520d62f29330adf36c4097e9798f579`。共享 helper 只读 READINESS 成功。四旧保护集合的隔离探测经过独立 APP、完整脚本/stdin 审查及新 grant 后，SDK 返回 UNKNOWN/SSH_COMMAND_TIMEOUT；channel 已开、input 已完成，30001ms 时仍 WAITING_FOR_EXIT，未观测 stdout 或 exit。未获得 READY，未进入候选准备；没有停止标记或进程终止，不重放这个动作。

## 账本与原因边界

284 固定 reader 的 javac/read 实际均为 0，H2 READONLY、existing-only 空锁、公有 47 事件完整逐字匹配，锁内及总关闭后 pair 属性不变。平台任务 UNKNOWN，Mod journal FAILED，必须分别表述；522 条资源中 519 释放、3 未释放。正式 App/component/release/receipt 及 execution 全 0，candidate claim 为 0。journal 的 effectsSettled=true 仅是该事件集求值，不能结清平台 SSH 不确定性，也不能从零索引推断远端不存在效果。reader 没有 Runtime、TaskContext、凭据行、模型或 SSH；源数据库和依赖路径未 hash。

静态审查确认探测总预算不自洽：四保护集合每轮 12 个 unit 顺序查询，前后两轮 24 次，加新组件前后 8 次，共 32 次 systemctl；每次各有 2s timeout，而 Java 总 timeout 固定 30s。282 的 59 行成功场景为 26 次。32 次的名义等待可以超过 30s，但单次 timeout 会提前退出，因此不能断言该场景必然超时，也不能确认本轮具体卡在 systemctl。stdout 仅在全程结束时输出一次，故无输出无法定位阶段；未发现解析、24KiB cap 或应用层 pipe 未排空造成此次等待超时的证据。

284 采用按轮次精确批量 unit 查询、每轮一次有界 work 列举和短于外层 SSH 的内部有限总预算；前后观测仍重新取得，缺项、重复、foreign、漂移或预算耗尽不得放行。阻塞文件系统调用及传输不确定性仍不能宣称被硬时限消除。实际实现与定点结果另记，不在这里预报新实机通过。

## 收尾与保留

CIM 本轮 Java 0、terminated 0；核 owner/nonce/SID、绝对边界和无 reparse 后，仅清理 f283a/u283a 及本轮加密 credential283 副本，source176 未由清理打开。固定 reader 的三个自有编译/临时输出目录也已核验后清除。新运行库、原 238/280/281/282、候选、queued postQuery 和全部原始证据保留。原 194 不恢复；无远端清理、强制结清、补 ACK 或旧动作重放。

下一次新验收将本轮接受计划的两 units、端口和身份纳入第五保护集合；没有 candidate claim 就不编造候选身份，零记录仍不当远端无效果证明。File Transfer 完成前不启动 CSV，两项目完整验收后暂停汇报。

## 失败 FULL 已知用量

封闭 stdout 窗口离线统计 21 次物理生成；没有额外模型调用。

| 角色 | 物理尝试 | 输入 | 输出 | 缓存命中 | 缓存未命中 | 输入加输出 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DEP | 12 | 301570 | 9909 | 219648 | 81922 | 311479 |
| APP | 9 | 132682 | 1788 | 99200 | 33482 | 134470 |
| 合计 | 21 | 434252 | 11697 | 318848 | 115404 | 445949 |

APP 已知 IO 占比 `134470 / 445949 = 30.1537%`；输入加权 cache `318848 / 434252 = 73.4246%`。21 条 IO/cache 已知；DEP 12 条 reasoning 已知且已包含于 output，APP 9 条未提供。完整 provider 清单、配置探测、HTTP/账单未知。仅失败 FULL，不替代两项目最终指标，不混纯恢复。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolation-controller-wiring-283/`，含 result/JUnit/stdout、live-cleanup 和 usage283；固定 reader 位于 `file-transfer-isolation-probe-timeout-284/reader/`。
