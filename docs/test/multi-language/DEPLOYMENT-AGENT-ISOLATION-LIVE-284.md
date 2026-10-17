# File Transfer 284：隔离 READY，工具准备拒绝后控制器状态未确认

唯一 `file-transfer-isolated-284-01`，UTC 2026-10-17T14:23:35.4343356Z 至 14:38:45.6379355Z，Maven 实际退出 1；JUnit 1F/0E/0Skip，Goal BLOCKED 断言。第 14 次历史 FULL，两个当前项目仍无完整业务验收结果，CSV 未开始。实现基线本地提交 `7f647ab1efb6e702d8174c243ca1343329be74a7`。

| 对象 | 实际值 |
| --- | --- |
| Task | `eb71a6de-07cb-4574-b538-15df729a88d5` |
| Root | `src/app/db/data/agent-live-file-transfer-4kt0u36gc` |
| App | `wtl-agent-file-transfer-4kt0vqj3s` |
| 计划端口 | 49288 / 49289 |
| 已准备候选 | App 后缀 `backend-525f7bab44afa87d`、`web-525f7bab44afa87d` |

## 已实际通过的边界

五保护集合取得两次独立 APP、新 grant 和实际 READY 回执，均 94 行、17 paths/4 units/2 ports。首次 frame 19236 B，free 3307229184、required 2214592512、reserve 1073741824 B，available memory 3562930176 B、CPU 2。第二次 frame 19296 B，backend 已分配；free 2636087296、required 1543503872、reserve 1073741824 B。只是点时准入，未完成事后保护核对，不能宣称资源硬隔离或验收成功。

backend/web 均实际 create、归属 query、上传、verify、open。模型两次提出自称只读的 host_command，APP 根据包装器中的持久写入/服务安装分支拒绝；原动作 NOT_SENT 保留。模型随后通过其它能力继续观察。原生环境观察报告 go/node/npm 不可用，不能推断所有私有目录下均无工具。

Go resolver 得到 1.24.13；已有 controller READINESS 实际 READY。修正后的 existing-only prepare 经过独立 APP、新 grant 并实际执行，8167ms 后 FAILED/65，输出 `TOOLCHAIN_FAILURE=isolation:EXISTING_INSTALLATION_REQUIRED`，没有转入 INSTALL 分支。模型继续 web 准备，Node resolver 得到 24.22.0，随后任务 UNKNOWN，公开问题为 `DEPLOYMENT_AGENT_CONTROLLER_STATE_UNCONFIRMED`，不是新版隔离 probe 超时或 scope 漂移的证明。

静态确认现有 precreation refusal 只识别旧空 stdin/store loader 的 HOST_SPACE_REQUIRED 合同；当前 existingController 非空 stdin 的早期拒绝不能产生该 typed proof。State.finish 记录 FAILED，但没有 confirmed/notDispatched/refusal 依据就继续保留 checkpoint；后续动作见未确认状态而拒绝。285 将针对当前精确源码、完整参数及真实结果补充最窄 typed 合同；不能凭任意 FAILED/65 或单行文本关闭 checkpoint，不追认本轮历史 UNKNOWN。

## 账本及收尾

285 固定 reader 实际 javac/read 均 0：H2 READONLY、existing-only 空锁、公开 110 事件完整逐字匹配、pair 属性锁内及关闭后不变。平台与 Mod 均 UNKNOWN；4000 条资源中 3993 释放、7 未释放。正式 App/component/release/receipt 及 execution 均 0；CANDIDATE 2、CURRENT_SOURCE 2、ISOLATION_PREFLIGHT 2。两组 controller ACTION/RESULT 的事件名本身不表示成功。最新 PLAN 63 摘要 `58601f57b4ccf9df4bc02d8f8571affc7ce41e5f34d27be0cc66a78a99862a20`，backend/web workspace 仍为 640/384 MiB；candidate claim 与原生创建证据分别保留。

错误前无 stop。父仅在已观测 UNKNOWN 后写 automatic-close 标记，产品真实取消 queued postQuery `5defd0c4-776e-4729-bac2-9c0389f5d5df`，进程自然退出 1，无 kill。CIM 本轮 Java 0、terminated 0；核 owner/nonce/SID、边界及无 reparse 后清 f284a/u284a、加密 credential284 copy 和从未上传的浏览器输入。未打开浏览器，未生成浏览器通过证明。reader 三个自有编译/临时目录也已核验后清理。

保留本轮 Runtime、候选及原 238/280/281/282/283、queued post 和全部证据，source176 未由清理打开。原 194 不恢复；不远端清理、不补 ACK、不重放旧动作。下一步先完成协议修正及必要定点，不盲开新 FULL；如另开新 scope，必须保护本轮真实两候选并重新观察容量。

## 失败 FULL 已知用量

从封闭 stdout 离线统计，没有新增模型调用。

| 角色 | 物理尝试 | 输入 | 输出 | 缓存命中 | 缓存未命中 | 输入加输出 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DEP | 25 | 1099449 | 25133 | 902144 | 197305 | 1124582 |
| APP | 97 | 3063039 | 23127 | 2421376 | 641663 | 3086166 |
| 合计 | 122 | 4162488 | 48260 | 3323520 | 838968 | 4210748 |

APP 已知 IO 占比 `3086166 / 4210748 = 73.2926%`；输入加权 cache `3323520 / 4162488 = 79.8446%`。122 条 IO/cache 已知；DEP 25 条 reasoning 已包含 output，APP 97 条未提供。完整 provider、配置探测、HTTP/账单未知。这里只是失败 FULL，不替代两项目最终指标，不与纯恢复混算。

实际证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolation-probe-timeout-284/`，含 result/JUnit/stdout、两次 READY 原始 frame、usage284、live-cleanup 和 browser-not-run；固定 reader 在 `file-transfer-after-second-resolver-285/reader/`。File Transfer 完成 BUSINESS v2、真实浏览器和 STOP/DISABLE 后才开始 CSV，两项目完成后暂停汇报。
