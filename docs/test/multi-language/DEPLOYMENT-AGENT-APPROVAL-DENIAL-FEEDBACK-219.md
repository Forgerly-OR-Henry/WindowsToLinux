# Agent 审批拒因结构化反馈修复（219）

本阶段完成离线修复：选定的 **4 个具体方法、38 个不同 JUnit 用例均有通过结果**，显式跳过测试打包成功。没有新增付费部署或 provider、SSH、DPAPI、产品 Runtime 调用；真实业务恢复、缓存命中率和审批占比改善尚未验证。

## 问题与最小改动

[218 真实验收](DEPLOYMENT-AGENT-NATIVE-VALIDATION-LIVE-218.md) 中，一条独立审批拒绝反馈经过 `DeploymentAgentFeedback.safe` 后，整行 JSON 被替换为 `[credential content excluded]`。原过滤器按整行匹配敏感词与冒号或等号，JSON 中任一叶文本触发过滤便会连同其它非秘密理由和建议一起消失。原判决 reason 长 668 字符，但保留日志只提供 512 字符 excerpt；本修复不恢复或猜测缺失的 156 字符。

仅改一个生产类 `DeploymentAgentFeedback`，以及两个直接测试类。Session、审批器、授权策略、共享 AI/HTTP、控制器、隔离权限、配置、数据库和三模式编排均未改。

新路径只接受实际工具标签 `ALTERNATIVE_REQUIRED` 与 Session 既有固定前缀。严格解析产品的五字段 JSON，要求 `outcome=DENY`、`dispatch=NOT_SENT`、`authority=NONE; every replacement needs fresh exact independent review`；拒绝重复键、尾随 JSON、额外字段、类型错误和越界输入。

reason 与三个建议数组中的字符串叶分别应用既有 `DeploymentAgentReview.denialReason` 脱敏，再重建原 JSON 形状。建议数组仍是 JSON 编码的字符串，单数组最多 4 项、单叶最多 512 个 UTF-16 单元；整个 JSON 输入和输出各不超过 6000 个 UTF-16 单元。UUID、可选 64 位小写十六进制绑定及 OPEN/CLOSED 状态均验证格式。这些格式检查不构成身份认证或权限。

非法封装仅抛出固定 `IllegalArgumentException("Invalid alternative refusal feedback")`，不带解析器正文或 cause。其它原始输出仍走原整行过滤；原 `OBSERVATION` 与数据库、秘密引用元数据分支保持。固定 DENY/NOT_SENT 不会产生 grant；每个替代动作仍须完整独立审批并取得新 grant，旧 UNKNOWN 与调查证据不改写。

## 实际验证与失败保留

| 具体方法 | 不同 JUnit 用例 | 验证范围 |
| --- | ---: | --- |
| `DeploymentAgentFeedbackTest#alternativeRefusalPreservesNonsecretDataAndRedactsOnlyCredentialLeaves` | 1 | 真实拒绝封装生成、逐叶脱敏、非秘密兄弟字段、幂等、其它工具标签仍按原过滤 |
| `DeploymentAgentFeedbackTest#alternativeRefusalRejectsMalformedOrForgedEnvelopes` | 33 | 伪造 outcome/dispatch/authority、重复/尾随/未知/缺失字段、类型、绑定、数组、NUL 和预算拒绝；固定错误不泄露输入 |
| `DeploymentAgentFeedbackTest#preservesOnlyExactProductReferenceMetadataAndRedactsUntrustedCredentials` | 1 | 既有秘密引用投影和原始输出脱敏回归 |
| `DeploymentAgentReviewStopTest#inspectRefusalReturnsNativeFeedbackButRequiresFreshReviewAndSettledEffects` | 3 | Session 消费拒因；替代动作取得新审批/grant；CLOSED 不重复讨论；未结执行阻止推进 |

java-01 实际执行 38 项：Feedback 35 PASS，ReviewStop 3 FAIL。失败均在进入新反馈投影前，旧夹具指纹 `SHA256:boundary-test` 不符合当前身份格式。仅将该假指纹改为 `SHA256:` 加 43 个 `b`，不放宽产品指纹校验或改变真实服务器指纹。

java-02 只重跑失败方法的 3 项，仍全部失败：旧 fake provider 从最后一条动态 USER 消息读取完整审批事实，而当前合同已把稳定事实放在第一条 USER 消息。最后一条仅含引用标记、绑定和动态预算。夹具随后按既有合同校验该标记、任务、当前绑定、目标与完整输入正文，保留内联或分页正文的长度与摘要检查；不修改生产请求或审批合同。实际命中的内联/分页分支未单独计量，不据此宣称两条分支都执行过。

java-03 再只运行该失败方法，实际 3 PASS。此前 Feedback 两文件与编译 target 字节未变，复用 java-01 的 35 PASS，没有重复运行。总计 44 次物理用例执行，6 次失败原件保留；最终 38 个不同用例通过，并非一次 38 PASS 或整类、整模块通过。Session 用本地 fake provider/SSH 验证真实接线，不是远端模型或服务器验收。

两个 Feedback 文件的 IDEA 格式 write/check 通过。ReviewStop 仅作上述夹具适配，按原风格审阅并经定点编译及三项行为验证，未对旧测试类整体格式化。

唯一打包命令 `-B -pl src/mod/deployment -am -DskipTests package` 成功；日志 14 处 skip、0 测试执行。父独核部署 22 组/44 成员（含 6 资源）、宿主 AI 3 族/5 成员的当前源码、target 与 JAR 一致；审批 policy 和验收 fixture 均未变。该核对不充 JVM CodeSource 或真实 provider 证据。

验证工具沿用既有工具并仅适配本轮 owner、来源与失败方法。父绑定和审计工具曾发生元数据错误，均保留原 helper 和失败记录；最终审计曾误将单测后的旧 JAR 字段与打包后的新 JAR 整对象比较，改为比较不变 source/target，再独立核新包，没有因此重跑测试。工具准备记录中的“尚未执行”描述其交接时点，实际结果以各轮 `result.json` 与原 XML 为准。

## 来源、产物与收尾

输入 HEAD `85ebda6de9dfd12093e30f8fa67589fe0bb4f42f`、index 空；当前 3309 项源码仅三个 owner 相对本轮 before 改变。输入 deployment.jar 为 `ef11526aea39eb95b6098d96a5a999f45c721785f0810d28b44a0849449c9d90`。

| 最终对象 | SHA-256 |
| --- | --- |
| `implementation/final-manifest-03.json` | `35309cffa7ce35b86cfdde318c5b2b9b63434353b843902eb488aa55b471b25c` |
| deployment.jar | `c026c0d0085a0e0667c3adca68be05085b896b7127bb4d3c9959f88b9babf127` |
| host AI JAR（未变） | `74ff9dfb37104c23e10baa409a18c2723fc1ab33848e5550af6a6e3ac5429863` |
| `package/after-metadata.json` | `b03a61fe2737dde0b5d4f9cc861345a6d89bf7d9c70649b43c7832f4392cebdc` |
| 最终三个 owner 精确补丁 | `83e1793136aa2c74b20bc716eb360a4afa24aea6cbd7f1d51d88eb7a65a0048e` |
| java-01 Feedback XML（35 PASS） | `7c0f4e89afd6ac5e4587e8d11bb8f6854690910580e9ef34674182e83fea4b64` |
| java-03 ReviewStop XML（3 PASS） | `0c32fa920ce06c0ef8980bdf7c70506287431ac58dac4557a975649befe13f0c` |

交付仅本记录、[原 dirty 字节上的三个 owner 精确补丁](patches/AGENT-APPROVAL-DENIAL-FEEDBACK-219.patch) 及该补丁单路径 `-text` 属性。补丁在自有副本真实 git apply 正反向逐字节一致，当前实际源码 reverse-check 通过；校验副本已清，不整体暂存混合源码或覆盖原迁移，不宣称干净 HEAD 已包含完整产品。

format 正常关闭及最终复核分别核对 10/11 组自有 PID/创建时间；java-01/02/03 分别 15/16/15 组、package 12 组均退出，TMP、短 UDS 和补丁副本清理完成，无强杀或原始数据删除。旧 216/217/218 的 69/220/81 份封存文件逐 SHA 未变，原 176 受保护数据对未变。原失败、UNKNOWN、Runtime、远端资源及调查证据保留。

辅助证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/approval-denial-feedback-219/`，`implementation/parent-final-verification.json` 汇总原结果与独核来源；初步补丁和两轮失败结果保留，最终补丁以 `final-increment-03.patch` 为准。本阶段使用 Sol 子代理，未调用 Astra；默认只本地提交，不推送。

仍是 CSV Inspector、File Transfer 两个不同项目、累计六次 FULL，本轮新增 0 次。218 已知一致 usage 行 cache 69.0412%、审批 44.1807% 仅复用原证，末两次物理请求 usage 及完整费用未知，不算新的优化收益。缓存大于 60% 可推进、审批仅观察是当前阶段规则，原八项目最终标准没有改写。

下一步先离线核查实际容量与构建缓存能力事实，避免把固定冷安装预留当作实际工具链所需量；不盲目重开付费 FULL，不代替模型部署或放宽隔离。完整业务、真实浏览器、产品 STOP/DISABLE、八项目冻结新服、非默认目录和整机重启等验收仍未完成。
