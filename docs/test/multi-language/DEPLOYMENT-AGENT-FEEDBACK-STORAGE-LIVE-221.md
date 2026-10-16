# Agent 拒因反馈与构建容量说明后的真实复验（221）

本轮 File Transfer 完整独立审批验收为 **1 FAIL，原任务 UNKNOWN，目标 BLOCKED**。SSH 与四个必要模型能力探测通过；模型自主调整容量后仍无法准入，随后自主提出只读磁盘盘点。该命令完整获批并执行，产品包装层收到 SDK exit 0，但其内部回执查询被独立审批拒绝，作者正文退出码和受保护回执未结清。没有发布、前后端业务、真实浏览器或产品 STOP/DISABLE 通过结果。

没有自动重试。本阶段仍只选择 CSV Inspector、File Transfer 两个不同项目，累计七次 FULL：208、209、211、214、216、218、221 均未完成验收。本记录不改变原失败、UNKNOWN、资源或调查数据。

## 当前来源与准入

本轮未修改生产代码、配置、样例或审批策略，没有新增 Java 测试、格式化或打包。复用 219 的定点通过与 220 的显式跳测打包证据；它们不替代本次真实产品结果。

| 对象 | 本轮核对 |
| --- | --- |
| 启动前 HEAD | `fdd15f1d6bd3dac78ee70f9b6460e98348c56a37`，index 空，未推送 |
| 源码 | 3309 项，值映射与 220 after-manifest 全同 |
| 220 after-manifest SHA-256 | `a42f0668f810d70511bd96e7029ca4c432309d94f3a3ee748eabba6f853f5235` |
| 本轮 before/after JSON SHA-256 | 均 `1462d1b5387d4dd1f44a61630d4d63fcf11d831050d2d13b43759504d4390ea6`；JSON 序列化与 220 文件不同，3309 项值映射相同 |
| deployment.jar SHA-256 | `a66babeaa7a960409a156807dfe455fb20829765c9705a423bb2707e38dacfa9` |
| host AI JAR SHA-256 | `74ff9dfb37104c23e10baa409a18c2723fc1ab33848e5550af6a6e3ac5429863` |
| fixture class SHA-256 | `b300a41c48dda597a562b82d6c6bb047f4cffa7d59d6861b3eecfe489affd421` |
| 生产来源绑定 | 部署 24 组、46 成员、8 资源；host 3 组、5 成员；实际 classpath 39 项、1043 个文件 |
| 工具链 | Java 21.0.10、Node 24.16.0、Python 3.12.10、PowerShell 7.6.5、Maven 3.9.12 |
| 模型服务 | 已授权的 `https://api.deepseek.com/chat/completions`，`deepseek-flash` |

实际 classpath 解析、首个匹配项和文件摘要不是 JVM CodeSource 插桩。包与运行来源桥保留原 207 人类出站许可，精确连接 217→219 三个 owner 和 219→220 两个说明资源；File Transfer 28 文件、67,297 字节及远端执行资源未变。SSH 密码和 API 密钥未写入模型正文或本记录。

恢复使用原 176 受保护配置，在正常用户环境生成唯一私有 ACL 凭据副本。产品实际 SSH 成功，指纹为用户已授权的 `SHA256:sCRc3ICCT9OMtsaGxjciDIQd0NMVO5/+pDe/fNMgRYc`。DEP/APP 各 TEXT、TOOL_CALLING 的四个 fresh 探测均成功；其 usage 与物理调用次数仍未知。

正式目标为 `45.192.105.213:22/root`。历史 212 的 `192.168.201.1:60321` 来自 Windows 本机 VMware VMnet1，仅用于非 localhost 普通 HTTP 浏览器验证；该轮前后端在 Windows 上运行，服务已关闭，不能作为本轮远端业务证据。

## 唯一产品执行与阻塞

唯一入口为 `DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry`，项目 `file-transfer`，`FULL_AGENT_INDEPENDENT_APP`，请求端口 48934。请求端口不证明实际空闲、监听、对外发布或 CORS 正确。

执行时间为 2026-10-16 12:22:56.4192215 至 12:29:27.5121872（+08）。runner exit 1、sourceFreezeExit 0；原 JUnit 1 tests / 1 failures / 0 errors / 0 skipped，382.87 秒。exec8497 已终态，不重开。

原 Task 为 `d3c363e4-730d-4f4f-b96d-89d96de26784`。模型自行修正两个工具参数问题，并独立调整容量，没有由父代理提供项目命令或接管 SSH。

两次实际容量结果均为 `OBSERVED / PLAN_CAPACITY / WORKSPACE_HOST_INSUFFICIENT`：

| 项目 | 第一次计划 | 模型自行修正后 |
| --- | ---: | ---: |
| 宿主可用字节 | 1,133,322,240 | 1,133,322,240 |
| 宿主保留字节 | 1,073,741,824 | 1,073,741,824 |
| backend、web 各最低需求 | 64 MiB | 0 |
| backend、web 各期望需求 | 256 MiB | 16 MiB |
| 总准入需求字节 | 1,879,048,192 | 1,610,612,736 |

两者使用当前实际源码归档 24,765 字节、解包 67,297 字节；AGENT 宿主成本乘数为 2。两个结果的 `newInputsNotCreatedByRejectedPlan=true` 只证明各被拒计划未创建其新输入，不证明整个任务没有副作用。没有降低保留空间、隔离边界或固定准入条件。

此后模型自主提出只读盘点，operation 为 `4a3214ed-0cb2-45f6-ae26-1da5330b0ded`，作者正文 SHA-256 为 `c2094a205002ea9f3d8708c553d4ddcd983fec8785f071eada3f9a7a94213dea`。本轮日志第 808 行保留完整 9430/9430 UTF-16 stdin 已审阅的原生 APPROVE 摘录，第 810 行 DISPATCHED，第 811 行 SDK `SUCCEEDED / exit 0 / truncated=false`。作者正文为只读盘点，但实际包装层还包含布局准入、可能创建未激活恢复锚点以及协议回执写入，不能把整个动作描述为无写入。

原 SDK 输出实际显示根分区约 29G、已用 28G、可用 1.1G、97%，并列出旧候选 loop 挂载。这是当前盘点证据；目录名、卷内空闲或较小使用率不证明资源可删、无人引用或可释放，不据此清理旧任务。

随后 `DeploymentModelCommandRunner.query` 对原 operation 发起新的独立审批：`deployment.model.command.status.v1`，30 秒、1024 字节输出上限、空 stdin。第 1013 行原生 DENY；第 1015 行当轮诊断保留 **完整 646 字符拒因**，与原生提交的前 512 字符一致且 `truncated=false`。完整拒因说明：审批模型虽确认路径所有权、权限、目标和任务绑定检查，仍认为缺少受保护回执的读取授权与内容安全证据，不能排除其中含凭据或令牌，故拒绝读取。

这是审批模型的判断，不是已证明文件含有凭据。之前仅凭原生提交摘录无法确定末尾 134 字符；此处完整原因来自本轮已有原始诊断，不是猜补或新访问远端文件。查询没有取得执行 grant，不绕过 DENY。

第 1016 行异常为 `ACCESS_DENIED / DEPLOYMENT_COMMAND_REVIEW_DENIED`；公开原结果 `UNKNOWN / DEPLOYMENT_AGENT_STOPPED`，Goal Observation 为 `BLOCKED`。`deployment-model-commands` checkpoint、两个源码快照与显式业务结清 blocker 保留。包装层 exit 0 不等于作者正文 exit 0，不能据此关闭 checkpoint、改写旧 UNKNOWN 或自动重放。

本轮共六个原生判决：五次 APPROVE、一次 DENY；五条 SDK 命令回执均 exit 0。最终阻塞不是 SSH 打开超时、provider 传输失败、主动 unable 或预算耗尽。219 反馈分支没有在这个内部查询 DENY 上形成已验证的模型恢复闭环；220 说明也未取得真实构建缓存写入证明。

## 本轮已知用量

父审计从原始 `DEPLOYMENT_AI_USAGE` 逐 generation ID、attempt number、purpose 独立核对全部 29 个记录到的主流程物理生成，与 sealer 逐字段一致；无重复、绑定冲突或核心 cache 字段未知/不一致。四个必要探测与完整账单仍未知，不补零、不付费重取。

| 范围 | 物理生成 | input | output | cached input | cache miss |
| --- | ---: | ---: | ---: | ---: | ---: |
| 部署模型 | 12 | 374,312 | 30,962 | 221,696 | 152,616 |
| 审批模型 | 17 | 203,549 | 8,102 | 165,120 | 38,429 |
| 合计 | 29 | 577,861 | 39,064 | 386,816 | 191,045 |

已知一致行的 input 加权缓存命中率为 **66.9393%**（386816/577861），高于当前阶段 60% 门槛；审批占已知 input+output 的 **34.3074%**（211651/616925），只作观察。部署 reasoning 23,745 已包含在 output 内，不重复相加；审批 reasoning 的 17 行未知。普通/审批单次输出上限为 32768/8192，实际记录均在界内，普通累计 token 停止已禁用。

本窗口因容量和审批终止，不能据较 218 已知行的 cache69.0412%、APP44.1807% 宣称完整部署成本、趋势或因果收益；两轮状态、行为数量和未知 usage 不同。这也不是最终八项目指标，最终验收标准没有调整。

## 父独核、收尾与下一步

正常用户 CIM 以 PID+创建时间核对 10 个自有进程均退出，没有强杀。唯一 221 凭据副本、精确 marker 绑定的临时目录及短 UDS 目录已清；原 176 配置对摘要未变。Runtime `agent-live-file-transfer-jxghjt930`、原 Task、远端资源及调查证据保留；本轮没有浏览器页或成功的产品 STOP/DISABLE。未终止用户原有进程。

父终态工具第一次错误地要求本轮 JSON 序列化摘要等于 220 清单文件摘要，在写终态审计前失败。保留原工具与失败记录后，只修该断言为“各自字节摘要保留，3309 项值映射严格相等”，父审计通过；没有重跑产品、构建或 Java 测试。218/219/220 原封存 81/272/69 个文件重新逐 SHA 核对不变。

辅助证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/build-storage-live-221/`。主要入口为 `parent-terminal-audit221.json`、`build-storage-221-01/sealed-evidence.json`、原 `maven.log`、JUnit XML，以及 process/temporary/credential 收尾记录。原日志 SHA-256 为 `871b8c8d6cf5764dbf2ca059feba5875e2948d8ee037f12a1afea078dc0ce4e1`；XML 为 `b57679005f8ac4484fbca491997565c829234d8dd4d4fc05de5a75481812445f`。

下一阶段先离线核查原回执的生产格式、读取与审阅证据：当前 envelope 只写固定身份和状态，查询却直接 `cat` 受保护文件。需要判断如何让实际输出和可审证据严格受协议约束；尚未实现或测试，不以可信标签代替内容校验、读取授权或独立审批。任何后续具体动作仍完整复审并取得新 grant，不重放原命令、不改旧 UNKNOWN、不直接开始下一次 FULL。

并行只读能力审计还确认：既有原任务恢复可能清理候选，但完整结清后会归档原 task/event；当前没有明确的“仅释放指定旧资源、保留原 UNKNOWN 及完整调查账本”的独立入口。工具链内部失败清理也不等于旧 pending 卷的独立维护入口。未调用恢复、核查或任意 host_command 来补替这些缺口。

本阶段仅新增本验收文档到本地 Git，不推送；其他混合源码与迁移保留。完整目标仍未完成：两个项目业务、其余项目串行推广、冻结后全新服务器八项目、非默认目录与整机重启恢复均待验。使用 Sol 子代理完成工具及有界只读审计，没有使用 Astra。
