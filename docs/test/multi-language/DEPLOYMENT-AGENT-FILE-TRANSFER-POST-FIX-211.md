# File Transfer 修后 Agent 真机复验（211）

本轮 **FAIL**：Task `e00eafd9-80c2-47c6-b98a-7779497623fe` 为 UNKNOWN，目标为 BLOCKED，尚未发布或验证业务、浏览器及产品 STOP/DISABLE。已知生成窗口缓存命中率 **75.5013%**，审批占比 **71.1513%**。审批成本仍高，没有证据证明完整部署成本改善。

## 执行范围与输入

按用户“先串行试一到两个项目、缓存大于 60% 先推进、审批占比暂观察”继续同一个 File Transfer。原 CSV 208、File Transfer 209 已执行两次 FULL；本轮是两个已选项目中的唯一修后新 FULL，累计第三次，不增加第三个不同项目，也未重开旧 Task。

运行前已完成 [212 普通 HTTP 摘要修复](DEPLOYMENT-FILE-TRANSFER-HTTP-DIGEST-212.md)，本地提交 `be5ccce6d9e2e38ec154a2f722addbe32499a36b`。17 个摘要定点测试、实际 HTTP 上传/历史小文件原字节/64 MiB 暂停续传和错摘要拒绝通过；本地 64 MiB 浏览器下载仍缺回执，单独 HTTP 诊断不能替代浏览器通过。

准入重新核对当前源码 3309 项、File Transfer 28 文件/67,297 字节；平台 JAR 沿用 210，未重复已通过的 210 测试或重包。原 207 人类出站授权包含必要修正版本，本轮以精确三文件差分派生来源，保留原人类记录，不伪造新答复。实际模型仍为已授权 DeepSeek endpoint/model，凭据不进入模型正文或日志。

原 211 工具草稿 46 文件及 seal 全部保留。只调整三个来源守门，区分旧平台包的 3307 项来源与当前 3309 项样例来源。离线 capture 最初把 19 个关键类总数误写为部署类数，未产生输出即拒绝；只修为实际 16 部署类，加 2 HTTP 类和 1 fixture 共 19，原 helper 和错误证据保留。随后实际重捕 39 项 classpath/1041 文件、20 生产组/6 资源并通过 preflight；不将静态首匹配当成 JVM CodeSource 实测。

## 原始终态与阻塞

唯一 run `file-transfer-protocol-211-01`，exec28660 已 exit1，不再轮询或重开。时间为 2026-10-16 08:55:00.601—09:09:12.627（+08）；原 JUnit 1 FAIL、0 ERROR、0 SKIP，846.428 s，源码 after-freeze exit0。

26 次正式原生审阅提交中 25 APPROVE、1 DENY，对应 25 个已审动作。最后为合法 `submit_result` DENY，拒绝控制器 `prepare`，没有把正文当 grant，也未绕过拒绝执行该动作。日志记录理由总长 669 字符，但只保留 512 字符节选，不能当成完整理由。

节选明确指出：当前装载器脚本 8008 字符、exactAction 8164 字符、Base64 参数绑定 612 字符及空 stdin 已交付；装载器验证权限、回执、SHA 和长度后将执行 108,038 字节 controller 源码，而本次审批的应用源树不含该源码。摘要和安装回执不足以审阅其全部特权效果。当前源码只读审计也确认审批输入、当前 facts 和应用源树的可达边界；完整报告及最小修复方向保留在本轮辅助证据，不将待实现方案写为通过。

具体缺口在 Agent 的 `DeploymentAgentToolchainInputEvidence.selectInstalled`：严格匹配后只派生 description、typed input 和原 action，prepare/bind 的 sourcePointer 指向装载器 exactAction；controller 全文未进入 facts。现有 `Approval.compact` 与 `evidence` 已支持受控文本 descriptor 和分页，最小方向是在这个匹配分支提供同一 captured layout 的完整 rendered controller 及 SHA/字节数，复用现有分页。不能用裸 resource 替代完整 render，也不应把本次模型需要源码的判断改成每次调用必须机械重读全文。该方向未实现、未测试，不预先承诺批准或节省 Token。

本轮曾有一次 1211 ms 的非零 SSH 回执，通道已打开，输入 COMPLETED，输出和退出均已观察；不是旧 120 的通道打开超时分类。最后阻塞为上述正式 DENY；原公开结果仍为 `DEPLOYMENT_AGENT_STOPPED`/UNKNOWN、目标 BLOCKED，保留两个 FILE 和业务对账 blocker，不从日志改写其历史状态。

## 用量与对比边界

父逐 generation ID/number/purpose 及五个 usage 字段独立核对原日志与 sealer：115 唯一生成轮，0 重复、0 binding conflict，输出预算违规 0。四项必要能力探测已执行，用量与完整物理 HTTP 数、整单费用仍未知；未为补齐计量重复付费。

| 角色 | 生成轮 | input | output | cached input | reasoning |
| --- | ---: | ---: | ---: | ---: | --- |
| 部署 | 17 | 687,323 | 22,876 | 538,112 | 16,870，已包含在 output |
| 审批 | 98 | 1,711,405 | 40,206 | 1,272,960 | 98 轮未知 |
| 合计 | 115 | 2,398,728 | 63,082 | 1,811,072 | 不重复累加 |

已知 input+output 为 2,461,810；缓存为 `1811072/2398728`，115 行均已知且一致；审批为 `1751611/2461810`。这是失败调试窗口，不是最终八项目指标。

| 样本 | 结果 | 缓存命中率 | 审批占比 |
| --- | --- | ---: | ---: |
| CSV 208 | UNKNOWN/BLOCKED，未完成业务 | 78.7416% | 44.5785% |
| File Transfer 209 | FAILED，审批协议无效，短失败窗口 | 71.9452% | 22.6939% |
| File Transfer 211 | UNKNOWN/BLOCKED，prepare 正式拒绝 | 75.5013% | 71.1513% |

缓存达本阶段大于 60% 的继续线；审批没有下降。不同阶段、动作数量及服务器暖机不同，表中数值不能建立因果收益。一次具体执行输入约 12 万字符的分段审阅已观察到，但安装、readiness、后续调用的完整成本归因仍需精确回执，不用总数倒推。

## 收尾与证据

正常用户 CIM 核对 10 个自有 PID/创建时间均退出，无强终止；marker TMP、短 UDS 和 fresh211 私有受保护凭据副本已清理，原 176 来源逐 SHA 不变。保留 Runtime `agent-live-file-transfer-fi7tfb4ho`、App 引用 `wtl-agent-file-transfer-fi7th3n3k`、UNKNOWN、远端候选/占用和原始回执；未将其当正式可运行 App 或清理授权。未到本轮浏览器节点，没有创建浏览器结果或额外浏览器页。

源码前后及当前 3309 项一致，JAR 未变；本轮源、JAR、helper 和 Git 在实际 FULL 期间冻结。没有代部署服务器、提供模型命令答案、自动补 grant 或自动重试。

辅助原证根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-protocol-live-211/`。

| 原件 | SHA-256 |
| --- | --- |
| 原 JUnit XML | `9d697f756b78c9372f630799261fccf7dada7d1e248a5e24fb504ad07f01531f` |
| 原 maven.log | `554d1c923db42fcc937029b2279021bc2768b4c3663339c014064b5e71bd0aa6` |
| sealed-evidence.json | `e17a54ff15c81d1b842c9c13e7c6d9b282f34f0c3a90871954162100e3686e44` |
| parent-terminal-audit211.json | `83cab849bf8604f4e459152e59afd2cd955c2f8d429a4a96ffd3f66d48ccc521` |
| parent-source-gap-audit.md | `f2a8fcae312d04fe50aed622bc7ad4252ec763b9c314577fe267d21ea0fa5509` |
| 当前 source manifest | `b78a5293be17589faa20addb2259716adab21d65d5848be3e318a20dc26503a0` |
| deployment.jar | `60b3d55cd8ff6f756c3d69655cdd22fd3c1e5275aa3b7a2d42e610ad10e4961b` |

后续修复当前动作对其实际执行代码的审阅可见性，保留严格来源/布局/SHA 绑定、独立完整动作审阅及每次新 grant。该修复尚未实现或测试，不通过讨论绕审批，也不再盲目追加付费部署。完整八项目、非默认目录、整机重启等原 Goal 仍未完成。
