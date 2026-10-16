# File Transfer 原生校验诊断补强后真机复验（218）

本轮 **FAIL**：Task `1014e9de-1bb9-43ca-aa1b-ed9a3b633922` 原公开结果为 UNKNOWN/TASK_EFFECT_UNCERTAIN，原 Goal Observation 为 BLOCKED。最后部署模型生成遇到 HTTP 分块正文不完整，尚未发布业务、完成真实浏览器或产品 STOP/DISABLE。

148 次用量已知且一致的物理请求中，缓存命中率 **69.0412%**、审批占比 **44.1807%**；另有两次末尾物理请求用量未知。已知行缓存超过本阶段 60% 继续线，完整窗口用量、审批占比及费用仍未知，不能以已知行替代完整指标。

## 范围与准入

依据 [217 本地诊断补强](DEPLOYMENT-AGENT-NATIVE-VALIDATION-DIAGNOSTICS-217.md) 的实际源码、五项直接 JUnit 通过及显式跳测打包证据，只对同一 File Transfer 启动一个新的 FULL AGENT/AUTOMATIC 独立审批验收。连同 208、209、211、214、216，为两个已选不同项目中的累计第六次 FULL；没有第三项目或旧 Task 重开。

运行 HEAD `2e21f79f64e2ceb479f3da59ddf6291b65fda4bf`，index 为空。当前源码 3309 项、deployment/host AI 双 JAR、21 部署生产组/43 成员和 3 host AI 类族/5 成员重新逐项核对；原 216 的 69 件、217 的 220 件封存证据及原 176 受保护配置摘要不变。File Transfer 仍为 28 文件、67,297 字节；保原 207 人类出站授权及 211/213/215 来源桥，再核 215→217 仅七个 Java owner，平台 Java 不作为模型正文。

八份 helper 承接 216 并保原字节及精确差分，18 个新增纯守门案例、3 个 PowerShell/6 个 Python AST 检查通过；本轮未重跑此前通过的业务定点或重包。实际重捕 39 项 runtime classpath/1043 文件、24 个关键类、24 生产组及 6 资源，通过 offline、before-copy、before-run 和 source-before。静态首匹配不冒称 JVM CodeSource 实测，终态 sealer 另核原 XML 的真实 fixture classpath。

正常用户环境建立本轮精确 GUID 私有配置副本，秘密不输出。产品实际 SSH 检查成功，指纹同已授权重装服务器；DEP/APP 各 TEXT、TOOL_CALLING 四项新的必要能力检查均成功。已授权 endpoint/model 仍为 DeepSeek / `deepseek-flash`。48933 只是本轮请求端口，没有发布或实际端口可用性通过证据。

父准备阶段一次字段误名、一次多余 before-run 检查均在 Maven/新 Task/provider/SSH 前拒绝；原失败证据保留，前者只修父辅助字段，后者仅移动原检查回执保字节，均不是额外 FULL。终态独核辅助脚本另有一次 current/actual 变量误名，原脚本和 failure01 保留，修该变量后离线审计通过。没有修改产品来迎合辅助工具。

## 实际经过

唯一 run `native-validation-218-01`，exec84112 已终态 exit1。2026-10-16 10:51:48.8320417—11:21:18.0322660（+08），原 JUnit 为 1 FAIL、0 ERROR、0 SKIP，1763.984 秒；source-after freeze exit0。

模型自主调整两次空间不足计划后进入候选创建及上传。此次已越过 216 的上传确认阻点：原 SDK 确认 exit0/未截断，返回 owned、build inactive、24,765 字节及完整归档摘要；随后 agent-open 独立 APPROVE、取得新 grant 并 exit0，源修订为 `3d768462d83cee91b2e90bf1292f300d504775200fc8afad2e78a7722d6b0110`。重复上传进度不当作多次 dispatch。

后续标准工具链 prepare 的实际 exit65 为 HOST_SPACE_REQUIRED：available 1,654,300,672 字节、required 9,663,676,416 字节、reserve 1,073,741,824 字节、stage VOLUME。当前 Agent controller 固定 INSTALL 8 GiB 加 1 GiB 余量；这不是模型工作区配额或 Go 的实测需求量。匹配注册安装才走 64 MiB PROBE，此次只证明当次 release 没有匹配记录，不推出主机完全无工具链。没有现成 volume 参数，未降低常量或安全门槛。

模型随后自主选 CLI 路线。首次全局安装方案被独立 DENY/NOT_SENT；改为固定版本、摘要校验和任务专属目录后重新审批。两个安装尝试分别因 Node 下载/校验顺序、删除当前目录后版本检查失败，原 root 收据均 EXIT=1，不能把外层 SDK exit0 当安装成功。后续隔离构建实际验证 Go 1.24.0/Node v24.22.0 可执行，但 Go/SQLite 模块解包在 104 MiB 候选卷上 ENOSPC。

模型经新审批创建任务专属宿主缓存后，第二次构建在 `test -w` 退出，SDK exit1/inputState=COMPLETED/channelClosed。当前 restricted-build 强制 ProtectSystem=strict，仅允许 mutable 及其 tmp 绑定可写，ordinary 使用 DynamicUser；候选外宿主缓存不会仅因 chown 或 1777 获得 mount namespace 写例外。远端实际 mountinfo 未读取，不能把失败仅归因 UID，也不能称作者载荷未发送。最后 chmod 动作另被独立 DENY，只有该动作明确 NOT_SENT。

原日志有 35 次正式原生审批提交：33 APPROVE、2 DENY。33 条 SDK 命令回执为 30 条外层 exit0、1 条 exit65、2 条 exit1；部分 host 动作的外层 exit0 仍有上述 inner EXIT=1。原 permission summary 为 reviewedActions=33、humanQuestions=0；主管没有替模型部署、提供命令/摘要答案或覆盖 DENY。

## 终态原因及实际分支

最后成功部署响应只读取既有 observation。下一逻辑 generation `07e50e22-ca85-4fb8-9028-f1a0479da1ea` 的两个物理 attempt 均 UNKNOWN/AI_TRANSPORT_UNKNOWN/HTTP_CHUNK_INCOMPLETE，分别 27,881/49,535 ms，五项 usage 全未知。最终 Problem 报 HTTP200、headers received、acceptedBodyBytes=0、IOException→EOFException；不能把这个聚合诊断回填为每个 attempt 的独立完整观测，也不能推出请求未发、provider 未生成或零费用。

这是既有纯生成正文恢复中的两次物理尝试，没有新 FULL 或部署动作重放。最终 AI UNKNOWN 经 PlatformAiService 的 AI_OPERATION_UNCERTAIN/reportUncertainEffect，由 TaskRuntime 保为 UNKNOWN/TASK_EFFECT_UNCERTAIN。直接终态不是 SSH UNKNOWN、最后审批拒绝、主动 unable、native codec 非法或预算耗尽；原 allowance 仍有剩余。CUSTOM model-command blocker 的持久 payload 未另读，不声称所有命令账本已结清。

本轮还确认 o44 拒因反馈丢失：原 reason 序列化为单行 JSON，在 observe/retainedOutput 前经过 Feedback.safe 的整行敏感词过滤，实际变为 `[credential content excluded]`。后续 observation 只能读脱敏版本。原 668 字 reason 仅留 512 字 excerpt，尾 156 字及准确触发词未知，不能恢复猜测。保密守门必须保留；下一步只宜离线审字段级安全投影，不赋予动作权限。

- 213：实际出现 controllerSource 全文导航的分页请求及 APP 对 108,038 字节当前源的确认，并在批准后执行到容量 exit65；完整 returned page bytes/wire 未留，不能冒称独立重算每页。
- 215：一次实际 stop 正文后继续，随后才原生 submit_result APPROVE、新 grant 及 SDK exit0；正文没有被当授权。
- 217：本轮没有 AI_RESPONSE_INVALID/nativeValidationCode 或 Candidate 失败 cause 投影的触发；末失败在正文传输阶段，不能反推 216 的未知 guard 或声称其 provider 根因已修。

## 用量与收尾

父逐 generation ID/number/purpose/五项 usage 与 sealer 独核 150 个物理 attempt、149 个逻辑 generation；148 个成功响应，0 重复、0 binding conflict、0 输出预算违规。

| 角色 | 物理 attempt | 已知 input | 已知 output | 已知 cached input | usage 未知 |
| --- | ---: | ---: | ---: | ---: | --- |
| 部署 | 37 | 1,998,894 | 103,407 | 1,247,104 | 末尾 2 次 |
| 审批 | 113 | 1,599,653 | 64,311 | 1,237,376 | input/output/cache 均已知；reasoning 113 次未知 |
| 合计 | 150 | 3,598,547 | 167,718 | 2,484,480 | 2 次 |

已知 input+output 为 3,766,265；缓存按 `2484480/3598547` 计算，148 行一致、2 行未知。审批已知 1,663,964，占已知 input+output 的 44.1807%；这不是完整窗口审批占比。部署 reasoning 80,927 已含于 output，不重复累计。四项必要 probe 用量、完整物理 HTTP/整单费用仍未知，不重复付费补证。

216 已知行缓存 75.1422%、审批 44.9431%；218 已知行缓存 69.0412%、审批 44.1807%。阶段长度、动作、暖机及未知覆盖不同，均未完成业务，不能认定完整部署成本改善或持续下降，更不是八项目最终指标。

正常用户 CIM 核对 10 个自有 PID/创建时间全部退出，无强终止；marker TMP、短 UDS、fresh218 私有配置副本已清，原 176 pair 摘要不变。本轮没有 browser-ready/result 或浏览器页面。Runtime `agent-live-file-transfer-hzn8a7puk`、App 引用 `wtl-agent-file-transfer-hzn8boo6c`、UNKNOWN、远端候选、任务工具链/缓存及调查证据均保留，不按名称删除或强制结清。

源码、双 JAR 和运行 helper 全程冻结，父终态再核 3309 项及 before/after/217 一致。辅助证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/native-validation-live-218/`。

| 原件 | SHA-256 |
| --- | --- |
| 原 JUnit XML | `825ebb9c8d39dfaf2b3353935f634300e409a28f476fa3452c56ef4df5496363` |
| 原 maven.log | `f172b1066b3c5f1f19cdf37e6f9008e3e781e7cdd5cc1237ac99019609b74fc1` |
| sealed-evidence.json | `15493c8ce8860db50071774548bcb97c787b00277bf1d86f7ca9b3a3c373dca8` |
| parent-terminal-audit218.json | `8930aec927e20a5c84d5acc94aa59504070f5ff78102830821f74a9a4fd9000b` |
| 容量来源只读报告 | `ffba9fd76a31a3f7821ad141cf5b50acc0ca1c7c59793e9b359297c4489689c3` |
| 终态因果只读报告 | `857c6a02601052f957d351f0618609a5751cde10f5777dc1516d42a931bed6bf` |
| 当前源码 manifest | `2154a2c62030c74d2fff77a58db02f3e392bd079aabb78925a9280c794e47c7a` |
| deployment.jar | `ef11526aea39eb95b6098d96a5a999f45c721785f0810d28b44a0849449c9d90` |
| host AI JAR | `74ff9dfb37104c23e10baa409a18c2723fc1ab33848e5550af6a6e3ac5429863` |

下一阶段先离线处理安全拒因呈现和隔离可写空间事实的可见性；本轮没有实现这些后续修复，也不自动新开付费复验、放宽隔离/审批、增加纠错/超时或改写旧 UNKNOWN。完整业务、八项目冻结新服复验、非默认目录和整机重启恢复等原 Goal 仍未完成。
