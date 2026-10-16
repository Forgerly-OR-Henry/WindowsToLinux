# Agent 原候选集合恢复（224）

本阶段实现原任务多次准备的恢复：**9 个具体方法、34 个有效 JUnit 用例通过，显式跳过测试打包通过**。两次失败后的修正均只涉及一个测试夹具，四个生产文件从首轮编译后未再变化。尚未调用真实产品 Recovery、SSH、模型服务或新部署，原任务仍 UNKNOWN，远端候选和资源保留。

## 原问题与范围

223 对原 Task `a67e3b89-d04e-49a7-8b5e-20ed93803d53` 的只读完整账本核查，确认 backend 的 PREPARE/RESULT/CANDIDATE 和 SEAL 已配对，后续 frontend 的 PREPARE95 未结。已有两个 PLAN、同一个 App 和各自的配额。旧单准备 Compensation/Absence 捕获器均不适用于该形状；不能仅放宽数量检查后只清最后一个候选，遗漏前一个。

本次限定四个生产 owner 和两个既有测试 owner。新增 `DeploymentAgentMultiPreparationCompensation`，修改 Agent `Recovery`、`ActionLog` 和 `ProjectChannel`；不改旧 Absence/Compensation 证明格式、PreparationBinding/Probe、HistoryArchive、helper 脚本、独立审批政策、STATIC/ASSISTED 编排或数据库版本 1。

## 恢复行为

- 捕获完整有序原事件前缀、固定 source/profile/target/layout/policy、同一 App，以及每次准备实际先行 PLAN 对应的 WORKSPACE_QUOTA。完整结果、候选和 CANDIDATE_QUOTA 必须一致，只接受已有完成候选加唯一最后未结准备。其它影响必须已配对；身份、配额、正式业务引用或后续进展不明时拒绝。
- 在第一次清理前，逐件完成独立审批的当前归属观察。已完成候选使用原 task 查询，未结候选使用现有严格 partial 探针；清理和后续 absence 查询各自独立审阅完整请求并取得新 grant。不会重放 prepare、build 或部署。
- 原 Task 的专属 TaskService checkpoint 记录每个候选及精确请求摘要。在审批、绑定核验后持久记录 DISPATCHED，再紧邻 SDK 核验。该状态表示可能发生发送；即使持久记录后被守门阻止、实际零 SDK，也不补成 NOT_SENT。
- SDK 结果未知后，重入只重新观察，仍 present 就保持未结，不自动重发 cleanup。严格 absence 可记 RECONCILED_ABSENT，不伪造原退出回执。所有候选都取得严格 absence 后，才在原 task 行锁、完整前缀 CAS、正式引用及实际 checkpoint 核验下提交新的 `PREPARE_SET_COMPENSATED`，之后释放 checkpoint。
- 新证明只结清其唯一 pending prepare，并从后续工作区恢复中排除全部且仅限该集合。证明提交、ACK、checkpoint 释放和最终 STATE 间隙保持幂等；已有 durable proof 的重入不再查询或清理候选，也不重复记 settled 事件。归档仍须所有资源、回执及正式引用满足原规则。

集合最多 16 件，完整前缀最多 9996 事件；这不是保证任意 16 件都可接受。实际 canonical proof 还须同时满足 8192 字符/UTF-8 字节，checkpoint 须满足 12000，最坏形式超界就在本分支任何远端动作前拒绝，不裁剪证明。合法恢复审批路由元数据可更新，但不会替换原业务身份或借旧 grant。

## 实际定点验证

Maven 显式使用 `-pl src/mod/deployment -am -Dtest=<具体方法>`；没有整类、模块全套或全项目验证。

| 方法（CompensationTest，另行标注除外） | 有效用例 |
| --- | ---: |
| `multiplePreparationsBindEveryPlanQuotaAndOriginalCandidate` | 2 |
| `multiPreparationRejectsAmbiguousOrForeignHistory` | 12 |
| `multiPreparationCleansAllCandidatesBeforeDurableProofAndArchivesFailed` | 2 |
| `multiPreparationDispatchAndReentryNeverRepeatUnknownCleanup` | 9 |
| `multiPreparationProofCommitAndFinalStateFailuresRemainIdempotent` | 5 |
| `uploadedOriginalClosesOnlyItsTemporaryEffectsAndArchivesFailed` | 1 |
| `confirmedProofCannotConcealLaterIdentityOrPublication` | 1 |
| `committedSettlementSurvivesFinalStateCommitFailureWithoutAnotherDeleteOrEvent` | 1 |
| RecoveryTest `verifiedMultiPreparationProofExcludesExactlyItsWholeCandidateSet` | 1 |

原报告保留在 `multiprepare-recovery-224/tools/validation/`：java-01 实际 34 例，20P/14F；只修端点夹具后，java-03 只复验四个失败方法 17 例，12P/5F；修正会话夹具后，java-04 只复验两个失败方法 14 例，全 P。合计 65 次实际执行，34 个有效独立用例全部通过；不称 65 个不同用例。java-02 在工具清单路径门前拒绝，未启动 Maven、未执行用例。

首个夹具把 `REMOTE_TARGET` 误填为 server+SSH 指纹的 targetDigest，正式产品该字段为 physicalTarget 的端点摘要。生产 guard 正确拦截；修正种子并增加两种身份不混淆断言。第二个夹具按确定的 reviewBinding 跨独立审批沿用分页 cursor，而新审批有新 history/coverage；修正为按实际新 history 从头阅读，并在原两个方法验证重新分页、实际 TOOL 交付及新 grant。原五个失败报告不含实际返回 Problem，具体历史终末码没有追补。

覆盖默认/合法自定义布局、各 plan-quota、晚进展/外来引用/超界拒绝、全部候选清理和 pending absent、拒绝审批、checkpoint 写入失败、审批后/持久记录后/SDK 后绑定变化、UNKNOWN 不重发、活动/不完整观察拒绝，以及 proof INSERT/commit/ACK/release/最终 STATE 故障。测试使用真实本地 H2 Mod 事务和受控 NativeAiFixture；平台 TaskService checkpoint 是受控 CAS 代理，不能当作真实平台持久化、进程重启、Linux 权限或原194实服恢复证明。正例主要为语义 4，语义 5 沿用既有机械校验，未获得本轮真实验证。

## 构件、工具与交付边界

唯一打包 exec60389 已 exit0，源码前后冻结均 0，命令 `-B -pl src/mod/deployment -am -DskipTests package`。父独立逐成员核对 29 部署组：21 Java 族、57 class 成员、8 资源；另 host AI 3 族、5 成员一致。属于静态 source/target/JAR 一致性，不是 JVM CodeSource 插桩。

| 产物 | SHA-256 |
| --- | --- |
| 最终 3310 项源码清单 | `1fe2cc46e30e29a4a157de95e5f56d1c76bafffed63b5a16cd5fc224cd4767a5` |
| deployment.jar | `34ecae6786405da2912bb92695b0377aa4a2a172ff06110ba7e878e75b640325` |
| host AI JAR，未变 | `74ff9dfb37104c23e10baa409a18c2723fc1ab33848e5550af6a6e3ac5429863` |
| package after-metadata | `f9ed876852c373fc8a505637e263bd7448dfdefccd0b63a8cbcee0ef68af99b2` |
| 六 owner 精确增量 patch | `6bb567ddfc748fcab5e3bfe1ab57726a3dbebb35ecb335f2d960df00b7450146` |

辅助根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/multiprepare-recovery-224/`。保留初始 dirty 字节、新类原不存在的证据、格式前后清单、两次夹具修正及全部原报告。工具适配支持唯一新类及重试的实际 prior target，修正入口分隔符和版本清单路径；原工具字节与失败均保留。版本清单必须在 implementation 的规范无 reparse 路径下，逐 SHA 和完整源集合/owner 校验保持。

六文件首次格式 write/check 通过；两次夹具修正仅处理该测试文件。首轮、Maven 各轮及包的观察进程按 PID+创建时间退出，marker TMP/短 UDS 精确清理，共享格式缓存保留。单文件格式工具同步等待 formatter 子进程，另核观察到的 launcher/Python，未将它们冒称完整 Java CIM 采样。精确 patch 在自有副本实际 git apply 正反逐字节一致，对当前源 reverse-check 通过；副本核对边界和 marker 后已清。

只读资格工具先在保守 appapi 类型闭包绑定时触界，未启动 Java 或读库；改用已打包 App API JAR 单文件，而非提高递归上限。App API JAR 为 177392 字节，158 个 class 与对应 target、148 个源文件与最终清单一致。保留原8个依赖条目，加 mod 定向族/资源和 App API JAR，共10条目、319个文件绑定。公开合成的22事件/2候选路径实际运行通过，不含原194数据、数据库、DPAPI或网络。

原任务只读入口在启动前因构件元数据的 Windows `\\?\` 路径形式被规范路径门拒绝；原件保留，只在只读 SHA 函数中规范该前缀，保留无 reparse 和完整 SHA 检查。首个函数抽取检查漏载 Assert-Path，产生非终止错误；随后同时加载两函数、以终止错误模式检查通过，未因此重跑数据库。唯一实际 reader 已 exit0、stderr为空：正常用户 DPAPI 只在内存使用，原 pair 全程 Read/FileShare.Read 拒写句柄，既有产品空锁、裸 H2 IFEXISTS/ACCESS_MODE_DATA=r、generation/schema1 守门；没有产品初始化、迁移或网络。

实际读取原97事件，新 production capture 返回 present，集合恰2件，pending=95。backend 绑定 PREPARE44/PLAN29/QUOTA30，frontend 绑定 PREPARE95/PLAN57/QUOTA59；原布局摘要 `d332b2fb…`，完整事件摘要 `a4462b79…`。这消除了223未解码实际配额的本地资格疑点，不证明当前远端归属、依赖、absence或可删除性；也未查询原3454资源的新状态。原数据库59060224字节和引导358字节前后SHA与223基线相同。reader launcher/Java及closer已退出，标记TMP已清，0UDS、0凭据副本。

任何真实 Recovery 前仍须私有备份必要原 pair，并通过正式产品入口取得当前归属、依赖和 absence 证据。此次没有补写旧 UNKNOWN、执行清理、重放旧动作或启动付费部署。

仅隔离提交本文档、六 owner 精确 patch 和该 patch 单路径 `-text` 属性，不整体暂存混合源码，不推送。Sol XHigh 实现、Sol High 工具，没有使用 Astra。旧 UNKNOWN、失败和调查证据未改，不重试197/201或旧58/74/116；仍为 CSV Inspector、File Transfer 两项目累计七次 FULL。没有本轮 provider usage 或缓存/审批收益实测，最终八项目、非默认目录实服和整机重启恢复等完整目标仍未完成。
