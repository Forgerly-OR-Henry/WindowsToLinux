# 模型命令回执的有限输出与审阅绑定（222）

本阶段完成回执查询的本地修复：**5 个具体方法、7 个 JUnit 用例全部通过，显式跳过测试打包通过**。查询仅输出与原任务绑定的有限状态，并保留独立完整审批及新 grant。没有新增真实部署、模型 API、SSH、DPAPI、生产数据库或浏览器调用；不证明真实 Linux 权限、实服审批或业务验收通过。

221 原 Task `d3c363e4-730d-4f4f-b96d-89d96de26784` 仍 UNKNOWN、Goal BLOCKED，原回执未访问，旧结果和远端资源未改。当前仍仅 CSV Inspector、File Transfer 两个所选项目，七次 FULL，数量没有增加。

## 已证问题与修复

221 当轮原日志第 1015 行保留完整 646 字符独立拒因：审批认可状态查询的路径、权限及任务绑定检查，但认为受保护文件的内容与读取范围缺少可核验证据，不能排除凭据泄漏。原生提交只展示前 512 字符，完整理由已由该轮诊断核实。这是审批模型的判断，不证明文件含有凭据。

源码确认 `DeploymentModelCommandRunner.query` 复用 generic installer 的 `renderReceiptStatus`，通过路径、所有权和大小检查后直接 `cat` 文件。生产者通常写固定身份及 STARTED/EXIT，不能据此证明当前文件内容仍受限。

本次只改四个生产 owner：

- `DeploymentModelCommandIdentity` 新增专属 `receiptStatus`。保留 Bash/coreutils，通过原 task、operation、body SHA 和捕获布局生成期望头；只接受完整头加 `STARTED=1` 或规范 `EXIT=0..255` 及单个 LF。使用同一显式 FD、有界 `od` 十六进制读取、类型/root/0600/nlink1/1024 字节上限及前后 FD/路径元数据比较；匹配后重建已知 ASCII 输出。额外字节、重复内容、NUL、CRLF、缺 LF、错误身份、溢出或变化均不返回原文或其 hex。
- `DeploymentModelCommandRunner` 从真实原 DISPATCHED checkpoint 与完整规范请求交叉派生 `receiptStatusEvidence`，核 task/server/原 businessRevision/operation/body/捕获布局、目标、空 stdin、30 秒和 1024 输出上限；专属分支拒绝非规范 operation UUID，不迁移原账本。审批后、发送前和 SDK 返回后复核绑定与会话身份，变化不消费回执。
- `DeploymentCommandEvidence` 仅在该 status 动作提供 `modelCommandReceiptStatus`，含原身份、checkpoint revision/SHA 和有限输出合同；`remoteObserved=false`、`authority=NONE`。不把作者正文或 rationale 复制给状态查询，也不接收调用方自报的“可信”元数据。
- `DeploymentFixedCommandApproval` 使用真实 context/task 构造这些事实，在原生审阅后及宿主授权后再次核对。没有放宽原审批、权限或安全条件。

每次请求仍完整独立审阅并取得新 grant。事实描述当前匹配的实现与本地 DISPATCHED，不能证明文件现存、命令结束或应当批准。DENY、缺失、STARTED、失败或变化均保留未结状态，不重放作者正文、不自动释放 checkpoint。

Runner 同时被 ASSISTED、AGENT 及其恢复调用，因此这是有实际依据的窄共享机制修复；两模式编排保持独立。合法恢复可采用新审批配置，intent 的 businessRevision 仍绑定原 journal.modelRevision。generic installer status、全局审批 policy、STATIC 编排、数据库版本 1 和配置均未改。

## 实际验证与边界

原 dirty 七份源码字节和 3309 项清单先保存，再限定四生产和三个既有测试 owner。七文件 IDEA write/check 均退出 0；其余源码值映射未变。

唯一 `java-01` 由 exec57177 执行，已退出 0，源码前后冻结 0；Maven 显式限定 `-pl src/mod/deployment -am -Dtest=<以下具体方法>`，没有整类、模块全套或全项目验证。

| 具体方法 | JUnit 用例 | 主要证据 |
| --- | ---: | --- |
| RunnerTest `actualBashReceiptStatusProjectsOnlyCanonicalBoundBytes` | 2 | 默认/非默认布局；实际 Bash/od 过滤公开字节，合法状态和异常内容、身份、元数据、缺失反例 |
| RunnerTest `receiptStatusRequiresDispatchedCheckpointBeforeEveryFreshGrant` | 1 | DENY、审批后/SDK 后变化不发送或不结清；后续查询重新审批且不重放作者正文 |
| RunnerTest `recoveryReviewerPreservesOriginalCommandAndUnknownReceipt` | 1 | 原恢复方法直接回归，新 reviewer 不替换原业务身份 |
| CommandEvidenceTest `modelReceiptStatusFactsBindExactRequestAndOriginalCheckpoint` | 1 | 实际 checkpoint 与完整请求交叉核，错误 phase/身份/目标/limits/stdin/布局、非规范 UUID、重复或坏 JSON 均拒；固定异常不保输入片段/cause |
| AgentApprovalTest `boundReceiptStatusReachesNativeReviewBeforeItsNewGrant` | 2 | NativeAiFixture 的 APPROVE/DENY；完整 script 和绑定进入独立模型输入，新请求新审阅，拒绝无宿主 grant |

合计 **7 PASS / 0 FAIL / 0 ERROR / 0 SKIP**。父从三个原 XML 独立解析方法及数量，核对六份报告摘要；内部表分支不冒充更多 JUnit 用例。219/220 已通过且未受影响的验证没有重跑。

Windows 的 Bash 测试确实执行新字节过滤，但 root、权限、inode 和保护祖先目录由受控 stat 替身表示；它不是 Linux 内核权限或符号链接竞态的实机证明。原生审批测试使用本地受控模型，没有 Linux capability，不能当远端 SDK 执行或实际服务商审批通过。

Bash 重定向 open 不提供内核级 NOFOLLOW/O_NONBLOCK；受信 root 若在检查后换成 FIFO，仍可能等原 30 秒超时并保 UNKNOWN。前后身份检查也不声称绝对排除受信 root 并发改写。核心保证是成功输出仅为已绑定的有限 ASCII 协议。`/proc/self/fd` 或既有 coreutils 不可用时失败关闭，无原文回退，也不安装新解释器。

唯一打包 exec39408 已退出 0：`-B -pl src/mod/deployment -am -DskipTests package`，14 处 skip、0 测试执行，源码前后冻结 0。父逐成员独核 26 部署组、49 成员、8 资源及 host 3 组、5 成员；24 旧部署组中 Evidence/FixedApproval 两组被当前版本替代，加入 Identity/Runner 两个此前未单列的既有族，其余 22 组保持。静态 source/target/JAR 绑定不等于 JVM CodeSource 插桩。

| 产物 | SHA-256 |
| --- | --- |
| 格式后 3309 项清单 | `6fe6c0b57de67b25b9292ec994607512e6a3bdde3dc2334ab5919b0bdd2e610b` |
| 新 deployment.jar | `e1d0504933ea55f8404f40a27321bc50ba4e9135d44079adb24cfddc52d2c92e` |
| host AI JAR，未变 | `74ff9dfb37104c23e10baa409a18c2723fc1ab33848e5550af6a6e3ac5429863` |
| package after-metadata | `5ef7efd62f8f9ad59855e56944114f402dd5d1e2d129c37f9a52f7058b059b03` |
| 七 owner 精确增量 patch | `c94991ff4f9be678ae6278dc790a09f3216038ca967a862c0eb45681f0f4dc11` |

## 工具、隔离与收尾

辅助根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/model-command-receipt-contract-222/`。入口为 `implementation/REPORT.md`、`parent-unit-verification222.json`、`parent-package-verification222.json` 和各阶段原报告/closure。

新工具的 21 个纯检查、6 项 PS AST、4 项 Python AST 通过。父在主体前发现 package capture 继承了“打包后 JAR 必须仍等于旧 JAR”的无条件断言；保留原 helper/preparation，只修一行阶段判断，新增 8 个实际 AST guard 和 1 项 Python AST 通过，未重复旧组。父首轮 format 绑定把待填的 `owners.json=null` 误认为已填，主体启动前失败；保留原 binder/失败记录，绑定精确七 path 并重算 SHA 后通过。两项都没有引发格式、产品或 Maven 重跑。

格式收尾独核 9 个观察到的自有进程身份，定点 179、打包 12，均按 PID+创建时间退出；未强杀。各自 marker TMP/短 UDS 目录已清，共享格式缓存保留。精确 patch 在自有副本实际 git apply 正反后逐字节匹配，并对当前源 reverse-check 通过；marker、边界和精确文件核对后副本已清，未替换当前源。

仅隔离提交本验收文档、七 owner patch 和该 patch 的单路径 `-text` 属性；不整体暂存混合源码、不推送。未创建凭据副本或 Runtime，也未触碰原 176 配置、221 UNKNOWN、远端资源或调查数据库。Sol XHigh 实现、Sol High 工具，没有使用 Astra。

本阶段没有 provider usage 或缓存/审批收益实测。221 的 cache66.9393%/APP34.3074%仍只是旧失败窗口。下一步先处理上轮容量不足与保留调查证据的产品回收路径，确认有实际依据后再决定新真实复验；不因本地通过而直接付费重跑。两个项目业务、最终冻结八项目、非默认目录和整机重启恢复等完整目标仍未完成。
