# 172：下载审批的来源证据与保护执行时序

171 的独立 APP 拒绝了配置包下载，未产生新 grant、SDK download 或 cipher。该次 UNKNOWN 与原证保留。检查发现，既有 KEY_RECEIPT necessity 只提供路径、SHA 和理由，没有向 APP 呈现167成功归档链；保护字段也只有枚举描述。同时，`DeploymentAiJson.ask(APPROVAL)` 无条件拼入 installed helper 专属契约，而本动作是 SDK transfer。这些是已确认的送审缺口，不能据此确定模型内部拒绝原因。

本次仅修改 `DeploymentStaticLiveAcceptanceTest.java`。KEY_RECEIPT 配置归档要求绑定六份原件，机械核验完整 producer plan、原审批 facts/binding、APP APPROVE、新 grant、SDK SUCCEEDED/exit0/nontruncated/空 stderr、Task ENDED/SUCCEEDED，以及310条冻结文件映射、内部 manifest 和 bundle SHA。实际 SFTP 对象及逐字节测得的 archive SHA 仍须匹配，业务数据库仍走原 BUSINESS_DATA 一致性门。

原 author facts、48k batch 限额、原始 identity、typed bytes/modified 和 UNKNOWN 覆盖字段继续保留。只有显式绑定完整来源和保护能力引用的新路径，才展开下载专属审批副本，限制为512KiB；完整包不裁字段，超限在 provider 前拒绝。原件在 APP 前、APP 后、grant 前和 dispatch 前重读核 SHA。下载审批改用专属 `.call(APPROVAL)`，保留事实规范化、单一原生 `submit_result`、严格 parse、8192输出与零 generation recovery，不改变共享 AI 或三种部署模式。

保护契约区分当前目标已观测的 DACL、历史能力实证，以及授权执行后才产生的 cipher/反解 SHA/自有明文删除回执。136 六个保护相关方法与当前逐字一致；136 到当前3299项源码清单仅 fixture 不同，生产 `CurrentUserProtection` 和所有 POM 依赖声明均未变。原136公开 native 回执、163原 SDK/DPAPI回执作为历史能力证据，不冒充未来目标已保护。实现只清零本函数持有的四个数组，不声称 SDK/JNA/DPAPI 内部拷贝已安全擦除；UNKNOWN/未确认 `.part` 保留策略不变。

## 本地验证

- 唯一 selector：`DeploymentStaticLiveAcceptanceTest#protectedBackupBatchesBindExactTransfersAndRetainUnconfirmedPlaintext`，显式 `managed.runtime.protectedBackupLocal=true`。最终 protected-03 为 **1 PASS、0 SKIP，6.389s**，Maven0/sourceFreeze0。覆盖公开310条链的正反例、原字段和完整来源保留、hash/身份/审批/UNKNOWN/截断/正文边界、超预算拒绝、严格 native 捕获、DENY零dispatch及原保护失败留存。
- protected-01 在 testCompile 因新增夹具 ObjectNode 静态类型错误失败，没有执行测试；protected-02 在断言完成后因旧收尾逻辑遇新增非空临时子目录报错。改正夹具类型及自有目录位置后只重跑该失败方法，原两轮日志/报告保留，均不作为生产回归基线。
- 对真实167/160/171非秘密原 metadata 和136/163原能力回执执行 source-only 展开：六原件、310条映射均通过，完整输入 **392096 UTF-8字节**；只复用171历史观察，没有新 SFTP 或 fresh target 观察。
- soleowner format write/check 与显式 `-pl src/mod/deployment -am -DskipTests package` 均0；最终3299项仅 owner 改变，格式后测试/source-only/package字节一致。JAR中1048个类、113份资源与当前编译/源码逐字节匹配。normal CIM核70个自有 PID+CreationTicks 全退，stage tmp及已知JUnit临时目录已清。

## 待单独授权的公开 probe

未来入口为 `DeploymentStaticLiveAcceptanceTest#reviewsPublicBackupProtectionPlansWithoutExecution`，严格 `managed.runtime.backupReviewProbeLive=true`；staticLive、agentLive、backupLive、backupCommandLive 全为false，并指定全新 `managed.backup.run`/`managed.backup.evidence`。入口只复用产品 Task 和 APP-only 配置，两项原能力验证后分别记录两个 generation 的完整请求、原 verdict 与用量，全部记录后才评价；没有 saveServer、session、grant 或 SDK transfer 路径。

两例使用同一 `backupBatchReview` 入口及安全标准，均明确 `PUBLIC_NONEXECUTABLE_HYPOTHETICAL_REVIEW`，单个25B公开文件，只差一个额外 ACL 主体；不给模型预期答案、不伪造当前真实 DACL/source 观察。source-only fake native 捕获的 USER 为1885/1895B，相同 SYSTEM3570B，请求记录6555/6567B。它只验证条件保护计划和 ACL判断，不验证真实配置包来源；若正例因假设性或缺 fresh 证据被拒，应记为 **INCONCLUSIVE**，不能当兼容性失败或放行真实下载。实际 probe 尚未执行，不计为 APP通过。

本轮 provider、SSH、DPAPI、真实 Runtime、原调查库和实际下载调用均为0；仅原本的纯本地 fake-protection 平台作用域夹具运行。没有全量测试，没有新部署，没有旧 UNKNOWN 恢复。必要保全尚未完成，`backupComplete=false`，不可发出重装或 paused 信号。

证据目录：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-download-evidence-phases-172/`，关键索引为 `agent-final-verification.json`、`actual-source-only-result.json`、`protection-applicability-manifest-diff.json`、`public-probe-source-only/summary.json`、各轮原 log/XML/TXT 和 normal `processes-final.json`/`tmp-cleanup.json`。

精确增量：[BACKUP-DOWNLOAD-EVIDENCE-PHASES-172.patch](patches/BACKUP-DOWNLOAD-EVIDENCE-PHASES-172.patch)，只包含170最终原 bytes 到172最终 fixture 的差分。父另核原日志、报告、3299项源码及真实 Git apply 正反原字节，验证用临时库已按精确路径清除。源码与JAR冻结，仅本说明及精确补丁隔离本地提交，不暂存混合 fixture，不推送。
