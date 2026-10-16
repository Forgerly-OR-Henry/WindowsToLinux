# Agent 原生响应校验与上传确认原因保留（217）

本地诊断补强完成：**4 个具体方法、5 个 JUnit 案例通过**，七文件格式检查及显式跳测打包通过。本轮没有新增 provider、SSH、Runtime 或真实部署，不能据此宣称 File Transfer 业务恢复、缓存改善或审批占比下降。

## 已确认的问题

[216 真实验收](DEPLOYMENT-AGENT-APPROVAL-HOST-STATE-LIVE-216.md) 在候选归档上传后的独立确认审批中，收到 HTTP 200、976 字节响应，但宿主原生 codec 拒绝响应，返回 `FAILED/CONTRACT_VIOLATION/AI_RESPONSE_INVALID`。没有可用 AiResponse、正式判决、grant 或确认命令执行。

原诊断只有响应形状，不能指出实际失败的 native guard；`jsonFailureKind=NON_OBJECT` 是空 content 的补充投影，不能据此认定 tool arguments 不是对象。随后 Candidate.verify 又将原失败包装为 `UNKNOWN/DEPLOYMENT_UPLOAD_REMOTE_UNCONFIRMED` 并清空 context，公开结果丢失了原因。

216 完整 wire 和具体失败 guard 仍未知。本轮只修这两处已证诊断缺口；合成反例不是原响应重现，不改写旧 UNKNOWN/BLOCKED、不自动重放或追补旧任务证明。

## 最小改动与边界

仅改 4 个生产类及 3 个直接测试类，没有新增生产文件、依赖或公共 API。

- `NativeModelCodec` 在原有 guard 上产生私有 typed IOException，以显式稳定编号输出 `nativeValidationCode`。编号不取 enum ordinal、不重排或复用；未知 IOException 为 0，不读取异常 message 或 cause 链。
- `ChatCompletionsCodec` 只给既有拒绝条件赋码，不增加接受分支。合法 null/空 content 加工具调用继续成功，并保留完整原消息状态。
- `ModelHttpCall` 在原 `AI_RESPONSE_INVALID` 结果中追加一个固定码，保留原 FAILED、HTTP 观测和 usage。不输出 provider 正文、arguments、认证头或异常文本，不增加重试、纠错额度或超时。
- `DeploymentCandidateChannel` 仅在 verify 的 `!complete` 路径保留有限原因，仍返回 UNKNOWN、空 value。`causeOutcome/causeCode` 来自 typed 结果；`causeEvent` 只取 `AI_RESPONSE_INVALID/OTHER/ABSENT`；只有对应事件与 `CONTRACT_VIOLATION` 同时满足，才接受 canonical 十进制 0..15 为 `causeNativeValidationCode`，否则为 OTHER。其余 context 不复制。

固定码映射如下；consumer 只校验有限码域，不复制编号语义表，也不依赖 app-ai 的生产类型。

| 码 | 原有校验位置 |
| --- | --- |
| 0 | 其它未分类 IOException |
| 1 / 2 / 3 | 响应字节上限 / 严格 JSON / 根对象 |
| 4 / 5 | choices / message |
| 6 / 7 | tool_calls 数组 / function 类型 |
| 8 / 9 / 10 | id / name / arguments 字段 |
| 11 / 12 | AiToolCall 身份构造 / AiResponse 原生批次构造 |
| 13 / 14 / 15 | content / finish_reason / 显式对象输出格式 |

这些字段用于诊断，不能产生批准、grant、执行或资源结清证明。独立完整审批、修改动作重新审批和新 grant 保持；STATIC、ASSISTED、AGENT 编排、policy、控制器、配置、数据库均未改。共享 codec 的原 guard 与接受/拒绝行为保持。

## 实际验证

| 具体方法 | JUnit 案例 | 验证点 |
| --- | ---: | --- |
| `ModelProtocolCodecTest#chatNativeValidationCodesIdentifyOnlyOriginalFailingGuards` | 1 | 单案例内 23 个非法表场景，覆盖 0..15、重复/尾随 JSON、重复调用 ID、对象解析和形状拒绝；不猜异常正文 |
| `ModelProtocolCodecTest#chatNativeCallsAcceptNullOrEmptyContentWithoutJsonObjectCoercion` | 2 | null/空 content 的合法原生调用、usage、continuation 及下一请求原消息逐节点保留 |
| `ModelHttpCallTest#invalidNativeBodyRetainsFailedUsageAndFixedValidationCodeWithoutReplay` | 1 | 真实 loopback HTTP 200，FAILED/usage/固定码、一次发送、无可用 continuation、禁止重放、peer 退出和所属请求数组清零 |
| `DeploymentCandidateChannelTest#failedConfirmationReviewRetainsOnlySafeCauseAndNeverExecutes` | 1 | 单案例内 31 个有限码/非法码/无 Problem/外来事件等场景，原 FAILED 或不确定性不变，UNKNOWN 保有限原因、秘密标记不外传、零远端调用 |

23 和 31 是单方法内的表行数，不冒充额外 JUnit 案例。response 数组原 finally 清零源码未变，夹具不能直接观察其缓冲；本轮只直接验证所属 request 数组清零，不宣称所有 JDK 拷贝销毁。

首次 `java-01` 在 Maven 启动前因证据工具读取旧元数据的缺失 `sourceSha256` 键而失败：primaryExit=null、实际测试 0。实际 215 的二十组中有九个 Java 组没有该字段；只修两份 capture，严格按已绑定的本轮 before-manifest 核源码摘要，有旧键仍须相等，全部二十组验证保留。针对该 guard 的十个纯例和两份 AST 通过；旧工具及 java-01 原件保留。修后 `java-02` 唯一实际测试运行为 5 PASS、0 FAIL、0 ERROR、0 SKIP，没有重跑旧 210/212/213/215 方法或整类。

IDEA 2026.2.3 七文件 write/check 均 exit0。唯一打包命令 `-B -pl src/app/ai,src/mod/deployment -am -DskipTests package` 成功，14 处 skip、0 测试执行。父独核部署原二十组未变加 Candidate 一组，共 21 组/43 成员；宿主 NativeModelCodec、ChatCompletionsCodec、ModelHttpCall 三族/5 成员均与实际 target/JAR 相符。静态产物核对不充 JVM CodeSource 或真实模型证据。

正常用户 CIM 最终核对 45 组自有 PID/创建时间均退出，未强杀；本轮 TMP、短 UDS 和唯一补丁校验副本已清。原 216 的 69 份封存文件逐 SHA 未变；FAILED/UNKNOWN、Runtime、远端候选资源和调查数据保留。本轮沿用 Sol 子代理，未调用 Astra。

## 来源与交付

输入 HEAD `5b2f7bf940ce0582d2688ef06976b078f0e51c34`、index 空，源码 3309 项与 215 after-manifest 全等。输入 deployment.jar 为 `814ac73e3975627f8dfcd18302356daf8525d0b3e88127d8f75244cef7529750`。

| 最终证据 | SHA-256 |
| --- | --- |
| 3309 项 after-manifest | `2154a2c62030c74d2fff77a58db02f3e392bd079aabb78925a9280c794e47c7a` |
| deployment.jar | `ef11526aea39eb95b6098d96a5a999f45c721785f0810d28b44a0849449c9d90` |
| host AI JAR | `74ff9dfb37104c23e10baa409a18c2723fc1ab33848e5550af6a6e3ac5429863` |
| package/after-metadata.json | `b526b122afd1f5ef8d15b829fcb32b03a462c5c6b769b438a4d78f7676f7b46d` |
| 七 owner 精确增量 | `64df2d9798a9eacad17f70cec9220bf86829c21e7ce7737fb2672b5b0fb3fc04` |

本地交付仅本记录、[原 dirty 字节上的七文件精确补丁](patches/AGENT-NATIVE-VALIDATION-DIAGNOSTICS-217.patch) 及该补丁单路径 `-text` 属性；真实 git apply 正反向逐字节一致，实际源码 reverse-check 通过，不整体暂存混合源码。其余迁移和用户改动保留，不宣称干净 HEAD 独立包含当前完整产品。辅助证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/native-validation-diagnostics-217/`。

仍是 CSV Inspector、File Transfer 两个不同项目，累计五次 FULL，本轮新增 0 次。216 失败窗口 cache 75.1422%、审批 44.9431% 仅复用原证，没有新用量趋势或完整费用结论；cache 严格大于 60% 可推进、审批仅观察的本阶段规则保持。下一真实验收须重新核源码、两 JAR、实际 classpath、配置和连接，不能仅因本地诊断通过而宣称原失败已恢复。完整业务/真实浏览器/产品 STOP-DISABLE、八项目冻结新服务器、非默认目录和整机重启等原 Goal 仍未完成；默认只本地提交、不推送。
