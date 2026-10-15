# 186：task-board 独立审批与完整真实业务验收通过

2026-10-15 在重装后的已授权服务器上，通过产品 Agent 入口完成 task-board 部署、独立审批、前后端业务、真实浏览器操作、重启持久化及 STOP/DISABLE 收尾。定点方法 **1 PASS、0 FAIL、0 ERROR、0 SKIP，Maven exit0、source freeze0**，JUnit 耗时 4028.78 秒。此次是 task-board 的完整真实验收，不是全项目测试，也不是最终冻结版本的八项目验收。

## 执行边界与版本

- 唯一运行：`task-board-fresh-186-01`；开始 `2026-10-15T14:09:52.0482621+08:00`，结束 `2026-10-15T15:17:06.2408331+08:00`。原执行句柄 82696 已退出，exit0；没有并行部署其它项目。
- 定点入口：`DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry`，`projects=task-board`，FULL、AGENT、AUTOMATIC、remember=false。部署和审批模型自主选择工具、完整审阅具体动作及输入；作者没有通过 SSH 接管部署或提供逐步命令答案。
- 入口 HEAD：`af524f5ef093f8ac5fc96684ba3fbbe56c116694`。复用185已定点验证的最小审批接线修复及显式 skipTests 包，本轮没有中途修改产品源码、配置或构件。
- 当前实际工作树清单3299项，前后清单逐项一致，SHA256 均为 `cc3e0d51d4c228b670eaa1e9c0eb03a77970ff6b6c09d30fcbbf47feea81f444`。工作树仍含大量原迁移和其它任务改动，不能把干净 HEAD 当成该产品构建源。
- `deployment.jar`：`c2a31885f9ee9715f45b0c3b2667188453e2251bfe52455ca793cb237448a1ba`；`DeploymentAgentApproval.class`：`39c95c126a1060a8eb23a8328b6ad833a1fe0c191a36c046c96a835204c528da`，JAR 与 target 一致。
- host AI JAR：`a7d283d6bd8fa23ad415d9ef8729e7e6269ee60cf8845f65b87a478760119353`；两个 HTTP 类分别为 `5f2f45c8ddf0bc2afd09786e7c4bd8803e6978c7197dbab3146a0bf07480a43c`、`b3ac78b0599f05c36bf5e529292cdc78dd1d488cbadeaeb7776800ef9db6c603`；fixture 类为 `b300a41c48dda597a562b82d6c6bb047f4cffa7d59d6861b3eecfe489affd421`。39个 Runtime classpath 条目对应1041个实际文件随运行冻结；离线四类 no-init 来源检查和实际 Surefire classpath 检查保留，未冒称 FULL JVM 新增动态 codeSource 仪器验证。

目标 `45.192.105.213:22/root`；产品实际观测指纹与用户明确授权的新指纹 `SHA256:sCRc3ICCT9OMtsaGxjciDIQd0NMVO5/+pDe/fNMgRYc` 相符。当前 `https://api.deepseek.com/chat/completions`、`deepseek-flash` 及本轮 task-board 源码、部署指令、服务器观测和独立审批证据的出站许可已绑定。密码与 API 密钥没有作为模型正文输出；使用正常用户环境的受保护产品配置，临时加密凭据副本已清理。

## 产品与独立审批结果

SSH Task `00041c6a-b20d-4f6f-9113-f1c8e858d0ec` SUCCEEDED。新 Runtime 的四个必要能力检查均 SUCCEEDED：

| 能力 | Task |
|---|---|
| 部署 TEXT | `573574b6-1a32-40b4-85f9-b30fb0bce548` |
| 部署 TOOL | `11cde39e-1395-4430-b5b3-6bd6eadbc5a9` |
| 审批 TEXT | `b0e702f1-ddf6-49e0-8c6d-95c119a7b3c8` |
| 审批 TOOL | `c2867036-512a-44ef-8c14-b11e73471fe3` |

184的原具体阻塞在本轮得到真实验证：NODE 24 prepare 的101904字节 stdin 经独立审批调用 `call_00_K8XVIQkKyE4I3cQNzZNK2178` 获得 APPROVE，随后实际命令 SUCCEEDED、exit0。JAVA 21、PYTHON 3.12 的相应产品准备和绑定也推进完成。审批理由中的版本、配额描述不代替原 typed 回执的精确数值证明。

过程中曾有一次 APP 结果 `parametersParsed=false`，没有形成有效授权；部署模型自主重新准备动作并取得新审批。完整原始日志保留。最终76项具体动作受审，30次部署生成和417次审批生成。`requiredApprovals=0` 是 fixture 的人工／平台询问计数，不表示没有独立模型审批。没有用一次旧批准覆盖后续修正。

真实 Agent Task `9531d2c9-cba4-452e-9fa3-0d5774eeff5d` 最终 **SUCCEEDED**；产品经历 PREPARED、SEALED、发布、验证及完成，backend/frontend 均健康。正式 App 为 `wtl-agent-task-board-lqmo3j76c`，本轮业务 URL 为 `http://45.192.105.213:48900`。该 URL 仅是当轮实际路由，应用已停止，后续轮次必须重新识别来源、API、CORS、监听及端口。

正式 App revision：`bce44df64277abdb284ab9b200674aa020f81f4e23aba615ba1ffe879a4c571e`；ownership：`99fd531cdf56ca9bcb8b0642105242d9cf2b4e419d8326d547547b7ff6585d42`。持久绑定保留：

- FILE `backend:data`：`/var/opt/windowstolinux/apps/wtl-agent-task-board-lqmo3j76c-backend/files/data`。
- SQLITE DATABASE `backend:tasks`：`/var/opt/windowstolinux/apps/wtl-agent-task-board-lqmo3j76c-backend/databases/tasks/tasks.db`。

## 真实业务与浏览器

fixture 的真实 HTTP、前后端 API 路由及 CORS、数据写入、产品 STOP→START→RESTART 和持久化检查通过。原始标记为 `STATIC_LIVE_BUSINESS task-board real HTTP + cross-language flow + restart + persistence passed`。fixture 创建的任务4“静态真机持久化”经历上述生命周期检查。

作者随后通过两个真实浏览器页签操作同一实际应用（下称A、B）：

1. 在校园项目创建任务5“Agent186真实浏览器验收”，设置重要优先级、2030-10-15截止日期、agent186/browser标签，添加中文评论并开始办理。
2. 两页签均载入 version2。A保存描述至 version3；B提交旧 version2，实际显示“任务已被其他成员修改，请刷新后重试”，没有覆盖A的描述。刷新后加载A的 version3。
3. 实际执行提交验收、退回、再次提交和完成，最终 version7；历史显示对应操作及成员。整页重载后仍能查看完成状态、描述和评论。
4. 切换实验室项目，实际列表与成员隔离；返回校园项目后仍能查看任务5。

浏览器写操作均通过真实 UI，未以脚本 HTTP 调用替代。保留原始 JPEG、完整 AX、步骤证据和绑定 App/URL/各产物 SHA 的 `browser-result.json`；fixture 接受后记录 `AI_LIVE_BROWSER_PASS` 和 `AI_LIVE_PASS task-board languageCalls=30 approvalCalls=417 requiredApprovals=0 mode=agent`。

早期保存 AX 时拿到默认 diff“无变化”，证据生成器正确拒绝；随后只重新采集完整 AX，没有重复业务写入或付费部署。个别 UI locator／路由等待调整也如实保留。本轮两页签冲突测试不等于10并发测试，未验证样例 STANDARD 的万级任务、故障注入或整机重启恢复。

## 收尾与汇总误判

产品最终 STOP Task `fdd4c02f-93f0-4bac-9ce1-3c048f73d51f` SUCCEEDED。随后 snapshot Task `68576536-18cf-483e-9436-853d904d00c6` SUCCEEDED，确认精确 App **STOPPED、autostart=DISABLED**。原始日志有完整的 typed 回执及如下精确清理标记：

```text
STATIC_LIVE_CLEAN task-board DeploymentApplicationId[id=wtl-agent-task-board-lqmo3j76c, server=ServerId[id=static-live]] stopped/disabled
```

正常用户 CIM 正控制及 PID+创建时间核对确认本轮10组自有验证进程均退出；仅清理该轮有归属证明的临时目录、短 UDS 目录和新建加密凭据副本，176原配置不变。两个自有浏览器页签也已关闭。没有终止用户原有进程，未删除实际 Runtime、正式 App、持久数据或旧 UNKNOWN／失败调查证据。

原 `sealed-evidence.json` 的 `junitPassed`、独立审批、权限、源码冻结及物理收尾均为真，预算违规为0；但辅助正则 `STATIC_LIVE_CLEAN task-board \S+ stopped/disabled` 不能接受合法 typed ApplicationId 中的空格，因此误投影 `productStoppedDisabled=false`，连带 `fullBusinessAndRealBrowserPassed=false`。这不是产品没有 STOP/DISABLE。

原 seal、helper、日志、XML 及任务状态均原样保留。188追加只读核验，使用绑定 project/App/server 的精确字符串判断，并检查最终真实 snapshot、JUnit、业务/browser产物 SHA、3299项实际源码、JAR、进程及临时凭据收尾；**10个正反例通过，追加结论 PASS**。该追加核验没有新产品／provider／SSH 执行，没有改写旧 UNKNOWN，也没有从日志追补未发送证明。

## 已知用量与未知项

| 已观测平台生成记录 | 部署 | 独立审批 | 合计 |
|---|---:|---:|---:|
| 次数 | 30 | 417 | 447 |
| input | 1,360,885 | 6,637,597 | 7,998,482 |
| output | 37,520 | 146,735 | 184,255 |
| input+output | 1,398,405 | 6,784,332 | 8,182,737 |
| cached input | 1,027,840 | 4,462,208 | 5,490,048 |

已知 input 加权缓存命中 **68.6386%**，独立审批占已知 input+output 的 **82.9103%**。部署 reasoning 25,498 已包含在 output 内，不能重复相加；审批 reasoning 未提供，是 UNKNOWN，不填0。部署普通单次上限32768、审批8192，已观测违规0。

447是平台 generation attempt 的已观测去重记录，不是完整物理 HTTP 请求数。四个能力探测用量、完整物理次数与完整账单仍未知，不重复付费补证。本轮是 task-board 集成成功的调试验收；缓存达到最低60%，审批占比未达到最终八项目≤20%的要求，不能据此宣布最终指标达标。

187固定日志截面只读调查没有证实“同一101904输入在同一审查中两路各完整读取”的缺陷；当前 coverage 已有 OR 别名与范围记录。少量重叠请求存在，可考虑明确展示等价路线及确认投递进度，但尚未实施，也没有证据保证该候选能把审批占比降至20%。核心部署优先，此调查不构成普通用量下的停跑门槛。

## 证据定位与后续

原始运行目录：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/fresh-full-business-live-186/task-board-fresh-186-01/`。追加核验位于同级 `acceptance-seal-projection-188/`，只读效率报告位于 `agent-approval-efficiency-187/REPORT.md`。

| 产物 | SHA256 |
|---|---|
| 原 maven.log | `4ade083abfb5735bef442240530e375d117cb77bdd9dd5ff5fd9d860a30b1866` |
| 原 JUnit XML | `19fb3dc6446e1f5ff8e5f841b8bd414bf6ef5d296bc02abf47e1617141a3db07` |
| 原 seal（含汇总误判，保留） | `7cbbd522934ee759d2f54cfd029aaf5ab91bb1ca9859f3dd93531178316847c5` |
| 188追加核验 parent-acceptance188.json | `c795756022cfe072dd99691a66e54ee1bc7508000936a3da6d8c6f56ea473e15` |
| 188核验脚本 audit186.py | `c71326162641d921ff70526836e4925abbe39dc79ddfa58c56768c60e2f91717` |
| 实际浏览器 browser-final.jpg | `07e8de86e55b54a368c8d6aac010a0faf7e6c7045cde4ee4243cf373d6ac30f2` |
| browser-result.json | `dbb6339aeb1f17d67754c9aac5884a6082d02d6b3a9228f7ec9dfff89535e3b5` |
| 187只读报告 | `09370acc820b0d861a2cbc2e522802969fdccd7955c7e6ec5aa40d4be2ddc2ba` |

实际 Runtime `src/app/db/data/agent-live-task-board-lqmo1ymlo` 及正式 App 数据保留。117／119等已有效定点证据继续复用；184、120及其它未结 UNKNOWN／失败证据不因本轮成功改判或整表删除，数据库保持版本1。

下一阶段按原目标串行推进其余七项目；其源码与样例的第三方出站授权单独待答，离线准备不等于已获授权启动。之后仍需冻结版本，在全新服务器完成八项目无中途代码修改验收，并验证非默认目录和整机重启恢复。本记录不宣称上述阶段已完成。本轮185接线难点曾由 Astra High 作具体判断；186执行、浏览器和188证据核验不设置 Astra 最终审批门。
