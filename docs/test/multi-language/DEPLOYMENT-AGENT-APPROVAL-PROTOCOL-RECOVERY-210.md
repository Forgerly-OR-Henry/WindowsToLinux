# 210：Agent审批正文响应的一次非授权协议纠错

2026-10-16，本阶段修复[209 File Transfer](DEPLOYMENT-AGENT-SERIAL-PROJECTS-208-209.md)暴露的审批协议阻塞。**25个相关定点用例通过，显式跳过测试的打包成功；尚未通过真实模型或服务器复验。** 没有新增付费部署，没有重放209或改写原失败、DENY、UNKNOWN及资源记录；不把本地检查称为审批成本或缓存收益。

## 实际问题与修改

209最后一个APP generation成功返回 `finishReason=stop`，正文JSON写APPROVE，但没有调用native `submit_result`。旧实现直接在RESPONSE_CHECK以 `NATIVE_BATCH_INCOMPLETE` 结束，该响应没有取得grant。这条安全拒绝有效；模型为何改变输出形式、完整wire输入是否影响它，仍未知。

现有审批已经给不完整的native verdict schema一次纠错机会。本次仅在Agent独立审批包内，将**同一个一次额度**扩展到下列完整正文响应：`stop`、零toolCalls、非空白text、无continuation，且当前线程未中断。其它不完整或不一致的响应不进入此路径。

- 原正文按原ASSISTANT消息完整保留，不解析其中的APPROVE/DENY，不伪造tool result，不签发或继承grant。
- 下一请求的动态USER提供 `NATIVE_SUBMISSION_REQUIRED`、当前reviewBinding、`authorizationIssued=false` 和剩余纠错0。固定SYSTEM、完整fixed USER及可用只读工具保持；模型自行读取证据，再用独立的native `submit_result` 单独提交判决，不强制批准或指定部署命令。
- 纠错与旧schema分支共用一次额度。正文再错、正文后schema错、schema后正文错均停止；不是每种错误各送一次，也不重置工具预算。
- 下一实际模型请求必须仍含完整的原ASSISTANT；历史预算无法保留或已裁剪则停止，不截断后继续。pending只守下一请求，模型成功响应及绑定复查后清除。
- 取消、证据及来源绑定、完整可执行输入覆盖、调用身份、mixed verdict、独立新grant等原守门保持。中断不清除线程标志；length、stop带calls、tool_calls为空、无效/重复ID、空白正文或continuation保持拒绝。

本次生产修改只在 `service/agent/review/DeploymentAgentApproval.java`，测试只在对应 `DeploymentAgentApprovalTest.java`。共享NativeHistory、AiJson、平台codec、审批policy、STATIC/ASSISTED编排、配置、数据库及资源语义未改。没有扩大“把正文当结构化判决”的兼容路径，也没有自动推翻208的结构化DENY。插件自有reviewSource分支保持原行为。

## 实际验证

独立Sol XHigh做只读边界和测试可达性审查，父实现并核对关键结果；Sol High只准备离线格式/验证/打包工具。本阶段未调用Astra。准备工具PS/Python AST及7个选择器正反例通过，正常执行时另保留真实源、进程与原报告证据。

| 范围 | 实际结果 |
|---|---|
| IDEA 2026.2.3，仅两个Java文件write/check | 均exit0，owner外源码变化0 |
| `DeploymentAgentApprovalTest`的10个具体方法 | 25PASS/0FAIL/0ERROR/0SKIP，一次运行，无失败重跑 |
| Maven `-B -pl src/mod/deployment -am -DskipTests package` | 成功，16.083秒；14处skip日志，没有执行测试 |
| 源码及产物 | 3307源项冻结；20生产组/40成员source-target-JAR核对，19组及policy保持 |
| 正常用户本地收尾 | 格式9、Java15、打包13组已观测PID+creation UTC ticks退出；自有TMP/短UDS及补丁核对scratch清除 |

精确方法选择与每方法用例数在辅助 `implementation/planned-selectors.json`，原XML与实际方法数逐项相符。7个新增方法14case覆盖正文和正式判决相反、共享额度、输入缺口、取消/receipt/interrupt、历史超限/裁剪、空白/continuation和有限工具预算；3个直接相关旧方法11case覆盖原schema纠错、无效批次/身份及安全阶段诊断。旧RESPONSE_CHECK失败夹具改为连续两次正文并期待两次请求，仍断言无grant，其它oversized边界未改变。不是整类或全量测试，没有重复205已通过验证。

父格式准入脚本第一次假定通用seal文件名而读取失败，runner随后在尚未授权守门处拒绝；没有创建format阶段/TMP或启动formatter。保留该本地错误，只改父绑定文件名后执行一次实际格式；没有放宽守门。原格式共享cache保留。所有临时验证进程已退出，没有真实provider、SSH、DPAPI、产品Runtime或浏览器调用。

## 当前输入、增量与证据

运行HEAD为 `a5e64a3e1d869269862eeac55a20b270dd6ae310`。输入JAR `f040260c14433052440b9fc92903f216e652966f5a99cc2df0e2c4367b50430e`；新JAR **`60b3d55cd8ff6f756c3d69655cdd22fd3c1e5275aa3b7a2d42e610ad10e4961b`**。最终3307项源码manifest SHA **`df735515e523243e68181bd12d7efde87f00433751021f8a13ba9ae3242d5f12`**，只有两个owner不同于205/209原基线。policy仍为 `f116b4ed48492f3800d95cc12419010e852b302793091a4ac613bf327cedb250`。

工作树仍保留原迁移及其它任务改动，部分真实源码不在HEAD。本地提交沿用精确增量交付：[两个原dirty文件之上的源码补丁](patches/AGENT-APPROVAL-PROTOCOL-RECOVERY-210.patch)，另加本记录和该补丁单路径的`-text`属性，不整体暂存混合源码。父实际 `git apply` 正反向重建两份文件并逐字节比对，当前实际源码reverse-check也通过；验证用scratch已删除。此补丁要求对应原dirty基线，不能据此声称干净HEAD独立包含当前完整产品。

辅助根 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/approval-protocol-recovery-210/`：原源码before/after、独立审查、来源及工具delta、准备AST、原XML/日志、manifest、class family/产物metadata、PID与cleanup及父核对均保留，119个原件封存。原208/209封存文件逐SHA重核未变。

| 关键证据 | SHA256 |
|---|---|
| 原25case XML | 828a729623e60d872977de9e1fda418e30ec744b4e9ff4bcef7f24380af7ea10 |
| package/after-metadata.json | fe426573b2a27a2e6e62aaaed24641e37d2606a71b69c8595599971620d2ac22 |
| implementation/parent-final-verification.json | ff5ac101f7b44839b81d9b0d9a115ba7e761c560995bb9d8e5394b229ff01fd4 |
| 精确源码增量patch | 95aacf444ed922f26eb3c6dc1788db1c65017745e7870ccc66dda6f07961e81d |
| sealed-local210.json | aad48ec48f0a66cabea0b6b977928d90f44d939d8c5bee8301fc569e0257ab25 |

这是实际协议阻塞的本地修复，不是第四轮付费优化或新项目验收。两项目采样仍为208/209原失败结果；本地25PASS不保证模型下一次会按协议提交，不证明CSV矛盾拒因已解决，也不证明审批占比或缓存改善。后续完整业务、真实浏览器、产品STOP/DISABLE、原八项目目标、非默认目录及重启恢复仍待实际验证。默认只本地提交，不推送。
