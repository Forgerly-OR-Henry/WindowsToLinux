# File Transfer 审批句柄修复后真机复验（216）

本轮 **FAIL**：Task `108ce6cc-c1f3-426b-b0ba-37523b9e1411` 原公开结果为 UNKNOWN，产品目标为 BLOCKED。部署在上传后的远端确认阶段结束，尚未发布、验证业务、真实浏览器或产品 STOP/DISABLE。

已知 40 次生成的缓存命中率 **75.1422%**、审批占比 **44.9431%**。缓存满足本阶段严格大于 60% 的继续线；审批仅观察。这是失败窗口，不能据此认定完整部署成本改善。

## 范围与准入

按 [215 审批句柄合同修复](DEPLOYMENT-AGENT-APPROVAL-CONTINUATION-CONTRACT-215.md) 的实际改动与 12 项直接定点通过证据，仅对同一 File Transfer 启动一次新的 FULL AGENT/AUTOMATIC 独立审批验收。连同 208、209、211、214，为两个已选不同项目中的累计第五次 FULL；没有第三项目、旧 Task 重开或自动重放。

运行 HEAD `7c5045cfd9bb7b56ce0f5c2f8ec4f97dcf94d505`，index 为空，当前源码 3309 项、新 JAR 和 215 的 20 生产组/40 成员来源逐项重核。File Transfer 仍为 28 文件、67,297 字节。保留原 207 直接人类出站授权、211 样例修正及 213 平台证据桥，再验证 213→215 仅两个 Java owner 变化；平台 Java 不作为模型正文，没有合成人类答复。

复用 214 八份 helper 的原字节及精确差分，3 个 PowerShell、6 个自有 Python AST 和 15 个新增纯守门案例通过。实际重新捕获 39 项 classpath/1041 文件、19 个关键类、20 生产组/6 个资源，并通过 offline、before-copy、before-run 和 source-before。静态首匹配不是 JVM CodeSource 实测。

正常用户环境建立本轮精确 GUID 私有受保护配置副本，原 176 配置不变，秘密不输出。产品实际 SSH 检查成功，指纹同已授权的新服务器身份；DEP/APP 各 TEXT、TOOL_CALLING 四项新的必要能力检查均成功。实际 endpoint/model 仍为已授权 DeepSeek / `deepseek-flash`。请求端口 48932 未被冒称已证明可用。

## 实际经过与终态

唯一 run `approval-host-state-216-01`，exec93243 已终态 exit1，不再轮询或重开。2026-10-16 10:07:39.4314359—10:13:16.1031228（+08），原 JUnit 为 1 FAIL、0 ERROR、0 SKIP；source-after freeze exit0。

模型自主识别两组件并调整计划。一次容量拒绝观测为 available 2,129,063,936 字节、required 3,758,096,384 字节、主机预留 1,073,741,824 字节，拒绝没有创建新部署输入。模型随后自行降低工作区配额，进入候选创建、查询及上传。backend 候选创建的实际配额为 335,544,320 字节；该创建及随后查询获得独立原生 APPROVE、新 grant 和 SDK exit0，查询返回 owned/build inactive。没有为通过降低主机预留或来源下界。

共 9 次正式原生审阅提交，均 APPROVE，对应 9 个已审动作及 9 条成功、exit0、未截断的命令回执。上传进度多次到达 24,765 字节；重复进度不能证明多次 dispatch。按冻结产品路径，进入 verify 前上传成功 receipt 的路径、长度和 archive SHA 已匹配；它不等于独立远端内容确认，也不是作者载荷未发送。

随后新的上传确认命令进入独立审批，读取 request、contract、receipt_index 和 receipts。末次审批 generation `2ee1f073-c1ed-4e71-9b1d-cd9af124a598` 在 native codec 阶段返回 `FAILED/CONTRACT_VIOLATION/AI_RESPONSE_INVALID`，没有可用 AiResponse、正式原生判决、grant 或确认命令的 SDK 执行回执。

原安全诊断包含 HTTP 200、接收正文 976 字节、transport NONE、finishReason=tool_calls、空 content，且有 input/output usage。`jsonFailureKind=NON_OBJECT` 只是对空 content 的附加 JSON 对象投影，不能证明 native arguments 不是对象。原 wire、具体 native 字段类型及抛错 guard 未留，无法确定 provider 还是 codec 的具体字段错误；本轮没有为适配未知字段放宽校验。

原 FAILED 经 FixedCommandApproval 和 BuildCommandChannel 返回，后者在 execute 前结束。CandidateChannel.verify 将所有非完整确认结果统一包装为 UNKNOWN，并创建空 context，丢掉了原 AI event/安全诊断；原部署用量日志仍保留该诊断。最终原公开事件为 `DEPLOYMENT_UPLOAD_REMOTE_UNCONFIRMED`，不是 APP DENY、最终容量不足、主动 unable、预算耗尽或确认命令已执行失败。原目标 Observation 为 BLOCKED，TaskSnapshot 为 UNKNOWN，并保留两个 FILE 与 business blocker。

所有可见成功 AiResponse 都是 tool_calls；最后失败尚未返回 AiResponse，未进入 215 的 stop+opaque 一次正文纠错。因此本轮没有实际验证该新分支。也没有 installed controller prepare/bind 或 controllerSource 专用指针读取回执，不能将通用 managed-helper readiness 当成 213 控制器完整源入口已触达。

## 用量与趋势边界

父逐 generation ID、number、purpose 和五个 usage 字段独立比对原日志与 sealer，40 行唯一、0 重复、0 binding conflict、0 输出预算违规。成功响应只有 39 个（DEP12、APP27），末次 FAILED 仍有真实 usage，必须包含在 40 次生成统计中。

| 角色 | 生成次数 | input | output | cached input | reasoning |
| --- | ---: | ---: | ---: | ---: | --- |
| 部署 | 12 | 394,337 | 20,108 | 276,096 | 13,782，已包含于 output |
| 审批 | 28 | 329,283 | 9,030 | 267,648 | 28 次未知 |
| 合计 | 40 | 723,620 | 29,138 | 543,744 | 不重复累加 |

已知 input+output 为 752,758；缓存为 `543744/723620`，40 行已知且一致；审批为 `338313/752758`。四项必要能力探测用量、完整物理 HTTP 数、整单费用仍未知，没有填零或重复付费补证。

| File Transfer 窗口 | 缓存 | 审批占比 | 停止阶段 |
| --- | ---: | ---: | --- |
| 211 | 75.5013% | 71.1513% | prepare 的正式 DENY |
| 214 | 75.8458% | 73.3339% | 很短的审批协议失败窗口 |
| 216 | 75.1422% | 44.9431% | 上传确认审批的 AI_RESPONSE_INVALID |

216 审批数值较前两轮低，但执行阶段、动作数量、模型选择和暖机不同，不能认定持续下降或因果收益；各轮均未完成业务。当前按用户最新要求保缓存继续线、审批仅观察，不把原八项目最终标准改写为本轮通过。

## 收尾、原件与下一动作

正常用户 CIM 核对 10 个自有 PID/创建时间全部退出，无强终止；本轮 marker TMP、短 UDS 和 fresh216 私有受保护配置副本已清理，原 176 pair SHA 不变。没有本轮浏览器页面。保留 Runtime `agent-live-file-transfer-h1u5cdsdg`、App 引用 `wtl-agent-file-transfer-h1u5dsans`、UNKNOWN、远端候选、上传及占用资源和调查证据，不据名称删除或强制结清。

源码、JAR、Git 和七个运行 helper 在 FULL 期间冻结。父终态独核当前 3309 项与 before/after/215 清单一致，JAR 未变。原 214 的 67 件和 215 的 169 件封存证据仍保留；未重跑 215/213/212 定点或重包。

辅助证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/approval-host-state-live-216/`。

| 原件 | SHA-256 |
| --- | --- |
| 原 JUnit XML | `f6e72d65203bc15f8175a7d60bb4dbb78f7c3e7c8355e0c7804f81926652855d` |
| 原 maven.log | `78ad45cf4102edd8f4a3552a5a83243babb4e7042e4a74db9e39e2f8beeed7aa` |
| sealed-evidence.json | `fb50a5699dbc62f7239cc0fb8295dd8871cf47c77c54f89bd0bc21b90df71434` |
| parent-terminal-audit216.json | `845e538774a3a1ed63ee522a7b6fe99ba16f1eaa9cc6b9b20b7a6b489cd6e20d` |
| 协议与上传确认只读报告 | `427a412d033f783d001de297863014a6819e459e67932029ca4b2f8ed91139b0` |
| 当前 source manifest | `08e45512a71c87927a9824a8a396837d779ece4fb11bfe0a6c780c009b137503` |
| deployment.jar | `814ac73e3975627f8dfcd18302356daf8525d0b3e88127d8f75244cef7529750` |

下一步先离线补有限、固定且不含正文/arguments 的 native 校验诊断，以及 UNKNOWN 包装中原安全 cause 的保留；准确字段未明前不做兼容绕过、不增加重试或纠错额度、不放宽超时、不盲目新增 FULL。此项尚未实现或测试，不能用旧日志补造 wire、改写旧 UNKNOWN 或否认已上传的效果。完整业务、八项目冻结全新服务器复验、非默认目录及整机重启等原 Goal 仍未完成。
