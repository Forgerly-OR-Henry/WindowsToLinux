# Agent 原生发布失败的模型确认与收尾

54-A 已完成直接本地验证及父独立复核。选择性本地交付范围及提交证据见本包 `commit-delivery.json`。当前真实 task-board 验收仍是 [48 的 FAIL/UNKNOWN](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-48.md)，审批 API Agent 关闭；本文件不能作为新部署、原48恢复或自主后继成功的证据。

## 已确认问题与修复边界

原发布工具可以把 native FAILED 和整图 RESTORED 回执交给模型，但只提供成功 finish 与 UNKNOWN unable，缺少让模型接受可证明失败并合法结束执行的入口。加入 Agent 专属 `finish_failure`：模型在下一回合自主选择，仍须评估当前全部待评估证据，并核对原 task、execution、server、target、model route、layout、source/archive、proposal、candidate 和当次发布/回滚事件。

模型接受失败独立记录为 `AI_PUBLICATION_FAILED_ACCEPTED`。它不满足成功 `AI_PUBLICATION_FINISHED` 的条件。原 `DeploymentOriginalPublishCommit` 继续记录真实 FAILED 并处理原业务引用；Session 写入 `DELIVERY_RESULT=FAILED` 后，RemoteTools 才按现有资源结算及 `CleanupEvidence(false)` 完成候选清理。Workflow 关闭来源与连接、调用原 `finishKnown`，平台保存真实 FAILED 归档。产品依赖次序用于正确收尾；模型仍自主决定工具和诊断方式。

当次整图回滚缺失或不明、发布后新增 stage/custom command、身份或冻结证据变化、待评估证据未覆盖、资源/当前状态/删除后的 absence/引用/来源结清无法证实时，保留 UNKNOWN 及调查记录。不得据命令 EXIT 或自然语言宣称 App 成功。已发布应用、正式发布身份、配置和持久数据继续受原所有权与引用规则保护。

发布前已知 ENDED 的命令行回执可以保留到收尾，核对发布边界的精确冻结快照及合法释放状态。首次失败资格不支持旧 INTENT 分支；这是该入口的支持范围，不能把已确认未派发描述为未知，也不能因此提前阻止原独立成功发布路径。原拒绝记录不重开。

有 native 数据库或密钥资源索引时，缺少其原 checkpoint 不构成已合法 close 的证明。新失败入口检查必要根索引和原严格归属，再沿现有严格资源 reconcile；未知保持未知。SQLite 的 `SqliteSetup` 路径不产生该 native 实例索引，不能因索引缺省被泛化拒绝。

## 验证与交付状态

本地验证使用受控 AiService、隔离临时 Runtime/H2 和替身远端会话，检查实际工具循环、正式执行、资源、引用、来源及平台归档；不等于真实模型或服务器验收。13 个明确筛选轮次保留 103 次实际执行：12 failure、24 error、0 skip；修正后 52 个不同当前案例最新全部通过。被替换的 2 个旧夹具案例另列，原失败没有删除，也没有重复无新疑点的通过组。CIM 无权限的前置启动未运行 Maven，不能计作业务失败。

| 直接范围 | 当前案例 | 验证内容 |
| --- | ---: | --- |
| FailurePublicationTest | 21 | 下一受控模型回合选择失败收尾；原 ENDED CLI；旧/外来/未知回滚、后续 stage/command、未结影响、评估和预算拒绝；INTENT 成功发布可达性；资源根缺失及归属拒绝 |
| FailedPublicationTest | 10 | DELIVERY_RESULT 与冻结身份、正式结果和引用校验；数据库/密钥查询未知时保留 |
| WorkflowTest | 6 | 原成功路径、完整 FAILED 归档、4 个 UNKNOWN 清理反例 |
| CleanupEvidenceTest | 13 | 原当前状态/absence 门及禁止失败借用 unfinished success 例外 |
| SkillSessionTest、SkillResourcesTest | 各 1 | 实际按需资源加载、更新 JAR 随包资源与类结构 |

完整失败场景实际断言 Task ENDED/FAILED、completion FAILED、正式执行 FINISHED/FAILED、businessCommitted=false、cleanupComplete=true、App=0、未释放平台资源=0；原引用结算、remote close=1、material/archive 路径不存在、原 FAILED 归档可查。UNKNOWN 场景保留正式 EXECUTING/FAILED/cleanupComplete=false、原 source 和未结资源；部分已知删除不写整体 CLEANED，Mod 停止仍按原规则报告 MOD_STOP_BLOCKED。

12 个 Java 文件格式 write/check、2 个既有 Skill 官方校验、显式 skipTests 打包通过。新 JAR 摘要为 `f51a334f28d84c694f82e39a5b11ac3a646f0eb4fbcb2f5eb7f41b1f9747624b`；父独立核对 8 个生产顶层 class 和 4 个资源的当前/JAR 字节。3282 个 before 条目等于51最终原像，after为3285，仅16个约定文件变化；父核完整 raw/current、真实 Git 正反、新文件创建/逆向不存在均通过。正常用户 CIM 原15组精确 PID/UTC ticks 及父重核均为0，7个临时依赖路径不存在；无终止既有进程。

父还独立验证191项留存文件的摘要/长度、原XML/TXT摘要与时间窗口、每轮源冻结及最新案例集合。辅助 Git 首轮因 extended patch pathname 被拒绝，修正参数后正反通过；Skill 前置缺 PyYAML/临时目录权限错误和全部原测试失败均保留。临时固定 PyYAML 验证后已清除。实际外部 provider、SSH、浏览器及原48数据库读取均为0；本包没有新的真实部署用量。

本包只修改 Agent 的发布工具、typed failure、Session/Workflow、必要现有资源/Skill 和直接测试。不创建 child、ticket、source capsule 或 goal observation，不改 schema-v1、当前配置、STATIC/ASSISTED 或 [51 的共享回执修复](DEPLOYMENT-MODEL-COMMAND-TRUNCATED-RECEIPT-51.md)。原迁移改动保留。

后续55-B在线后继还需一起完成合法来源、累积预算、唯一 admission、新候选身份，以及产品 API/UI/验收对最终真实 child 的观察；见 [52 设计](DEPLOYMENT-AGENT-FAILURE-CONTINUATION-DESIGN-52.md)。完成并通过直接本地闭环后，才继续 task-board 的纯主真实部署、业务和产品收尾。

原始证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-known-failure-settlement-54/`。[精确自有补丁](DEPLOYMENT-AGENT-FAILED-PUBLICATION-54.patch) 摘要为 `d1c9aaa1c908856f73f82db53b048875d9ad38aa9589954fb7575aa0d12babf9`。本地交付范围为7个可独立文件、本文和完整精确补丁；9个混合迁移文件不整体暂存，原迁移及其他任务保持。默认不推送；未证明完整干净 checkout 可构建、真实后继或真机通过。
