# File Transfer 控制器源入口修后真机复验（214）

本轮 **FAIL**，在审批协议阶段结束，未执行受审命令、发布或验证业务。已知三轮生成的缓存命中率 **75.8458%**、审批占比 **73.3339%**；这是很短的失败窗口，不能证明完整部署成本改善。[213 控制器完整源入口](DEPLOYMENT-AGENT-CONTROLLER-SOURCE-EVIDENCE-213.md) 尚未触达。

## 范围与实际输入

仍只推进用户选定的 CSV Inspector 与 File Transfer 两个不同项目。本轮是同一 File Transfer 的一次修后新 FULL；连同 208、209、211，累计四次 FULL，没有第三项目、旧 Task 重开或自动重放。

运行基线为本地提交 `a8ed4dcb61f15a05b7bc6a93a3a1f55571a1f17b`，index 为空；3309 项实际源码与 213 after-manifest 逐项一致，JAR 为 213 新包。File Transfer 28 文件、67,297 字节，未再改样例。准入实际重捕 39 项 classpath、1041 文件、19 个关键类、20 生产组/40 成员及 6 个资源；静态首匹配不是 JVM CodeSource 实测。

复用原 207 直接人类出站授权、原 211 必要样例修正来源桥，新增 213 平台证据绑定；平台 Java 不作为模型正文。实际模型仍为已授权 `https://api.deepseek.com/chat/completions` / `deepseek-flash`。原 176 受保护配置只通过正常用户环境创建本轮私有副本，密码和 API 密钥未输出。

运行 helper 保存原 211 八份来源及差分，3 个 PowerShell、6 个 Python AST 和 11 个新纯守门案例通过；这些是工具验证，不是产品业务通过。实际 capture、before-copy、before-run、source-before 均通过后，仅启动 `controller-source-214-01` 一次。请求端口 48931 未被冒称产品已证明可用。

## 原始终态

exec62272 已终态 exit1，不再轮询或重开。运行时间 2026-10-16 09:44:34.3432024—09:45:20.3287156（+08）；原 JUnit 为 1 FAIL、0 ERROR、0 SKIP，40.492 s，source-after freeze exit0。

Task/root/finalTask 为 `acf23f86-3ffc-4a49-a558-03a2c3546350`。实际 `completedGoal` API 返回 OperationResult FAILED，错误 `DEPLOYMENT_COMMAND_REVIEW_INVALID`，reviewStage 为 `RESPONSE_CHECK`、reviewCode 为 `NATIVE_BATCH_INCOMPLETE`。原日志没有 `AI_LIVE_ORIGINAL_PUBLIC_RESULT`，未额外读取持久任务或目标行，因此持久 task outcome 和 goal state **未知**，不将 API 返回等同于已确认的持久 FAILED/BLOCKED。

产品连接检查及四项必要能力探测完成后，仅有一次部署生成和两次审批生成。APP1 原生调用 `request` 与 `evidence` 读取当前脚本和 exactAction；APP2 finish=stop、非空正文、没有原生调用，随即协议失败。共有 **0 次正式原生判决提交、0 个已审动作、0 个人类问题**。正文中的 APPROVE 没有 grant 效力。

未到安装控制器 prepare/bind、发布、业务、真实浏览器或产品 STOP/DISABLE；不能据此验证 213 新源入口已被真实模型使用。没有本轮浏览器页面或 browser-result。

## 协议合同审计与后续边界

只读审计确认冻结源码存在具体冲突：`DeploymentAgentApproval` 的一次正文协议纠错要求 response.continuation 为空，而 `PlatformAiService` 对带 tools 的成功响应均保留完整原消息状态，包括 finish=stop、零工具调用。`AiContinuation` 是宿主签发并绑定任务、purpose、模型配置和 revision 的不透明句柄，没有“响应未完成”的合同语义。

在当前冻结产品实现被实际使用这一前提下，APP2 会携带该句柄并在这项守门处被拒绝；此前 APP1 的普通只读工具批次不会消耗纠错额度。原日志没有直接记录 APP2 响应 continuation 字段，APP2 请求里的 continuation 大小属于旧 APP1 状态，不能冒作响应证据。静态 classpath 解析亦不是运行时 CodeSource，故保留上述归因边界。

旧正文正例使用不带 continuation 的简化 AiResponse，负例反而把任意 continuation 视为应拒，未覆盖真实宿主合同。最小后续方向只涉及 Agent Approval 及直接测试：允许合法原句柄进入已有的一次非授权纠错，完整保留原 ASSISTANT 和句柄；仍须 stop、零 calls、非空正文、未中断、共用一次纠错额度，下一请求必须完整可见。不得解析正文发 grant、制造工具结果、剥离原状态或放宽正式 native verdict/新 grant。伪造或跨绑定句柄仍由正式宿主拒绝。本轮只完成审计，未实施或测试该修复，不改写本轮或历史 UNKNOWN。

## 用量

父按 generation ID、number、purpose 与五个 usage 字段独立比对原日志和 sealer，三行唯一、0 重复、0 binding conflict、0 输出预算违规；缓存三行均已知且一致。

| 角色 | 生成轮 | input | output | cached input | reasoning |
| --- | ---: | ---: | ---: | ---: | --- |
| 部署 | 1 | 9,291 | 128 | 8,832 | 33，已包含于 output |
| 审批 | 2 | 24,968 | 935 | 17,152 | 两轮未知 |
| 合计 | 3 | 34,259 | 1,063 | 25,984 | 不重复累加 |

缓存为 `25984/34259`，严格大于本阶段 60% 继续线；审批为 `25903/35322`，仅观察，不作为独立停止门。四项必要能力探测用量、完整物理 HTTP 数、整单费用仍未知，未为补证重复付费。与 211 的 115 轮及更后部署阶段不等价，不能认定趋势或 213 的因果收益。

## 收尾与保留

正常用户 CIM 核对 10 个自有 PID/创建时间全部退出，无强终止；本轮 marker TMP、短 UDS、fresh214 私有受保护凭据副本已清理，原 176 配置逐 SHA 不变。源码 3309 项、JAR、Git 在 FULL 期间及终态独核时均未变。

保留 Runtime `agent-live-file-transfer-gk5vsq61w`、App 引用 `wtl-agent-file-transfer-gk5vu6l0g`、失败/UNKNOWN、远端资源及调查证据；不将其写为正式可运行 App 或据名称清理。不代替模型部署，不提供命令答案，不自动重试。

辅助原证根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/controller-source-live-214/`。

| 原件 | SHA-256 |
| --- | --- |
| 原 JUnit XML | `af47e9b70a2298d5bf04af8dc03dfdca8cca2ad6eaca165f7b5397a23b584183` |
| 原 maven.log | `f4a7d67b011567f76a00519e2ea05619dd8c259f3d183a201fcb8ce2f385c304` |
| sealed-evidence.json | `60de3115270e2f53f205960ca6c38bcabec1b59c54e86746f4fc4dfcd5156cf9` |
| 协议只读审计报告 | `ca08c7f465f3c2c2cbd8ffa3a8637a6edb15690f61d97fabf8477a2a86bd20fc` |
| 当前 source manifest | `41cf3b2ef892125545124f2424280735d9f9c4914fd473de482fa28b7d20137a` |
| deployment.jar | `a5830441da7f2532d8174adf2bd0953cbc814d4f80c99ec3311215c62643fe7a` |

先离线修复已确认合同冲突并定点验证，再核对真实复验条件；不盲目重复付费部署。完整八项目、冻结全新服务器复验、非默认目录及整机重启等原 Goal 仍未完成。
