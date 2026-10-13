# Agent 原生发布失败后的自主继续路线提示 73

原生发布失败并确认回滚后，产品实际提供 `continue_deployment`，但状态里的 `nextStep.routes` 没有列出它；`finish_failure` 的路线提示也没有说明它只结束当前尝试。70 的真实模型选择失败收尾，没有创建后继；该轮健康失败的具体原因仍未知，不能把路线遗漏认定为全部失败的原因。

本轮只修改 Agent 专用 `DeploymentAgentPublishRunner` 的状态投影：按原工具注册使用的 currentWorker 条件呈现可选的 `continue_deployment`，明确新后继仍须由工具校验原来源、资源、剩余目标预算及未变化的绑定；`finish_failure` 明确只结束本次失败尝试。模型自行选择路线。实际执行、审批、准入、Goal 终态、UNKNOWN、清理和 Skill 均未修改，STATIC、ASSISTED 无源码变更。

唯一验证方法为 `DeploymentAgentWorkflowTest#nativeFailedPublicationNextStepOffersGoalContinuationAndAttemptOnlyFinish`，固定 `OPENAI_CHAT_COMPLETIONS`。它复用真实本地 Goal、JDBC、产品 TaskService、正式 Codec 与原生失败回滚/结清路径，在实际 AiRequest 中核对 offered 工具及 state。受控模型仍选择失败收尾，之后才断言提示；没有强制继续或创建新后继。此为本地受控接线验证，不是付费 API 模型或真实服务器验收。

- baseline-01：生产未改，1 FAIL / 0 ERROR / 0 SKIP，39.428 秒。实际 offered continue 校验通过，正确期待的可选路线缺失断言失败。
- corrected-01：类引用编译失败，0 案例；原日志和源码冻结结果保留。
- corrected-02：只补正同一生产文件引用并重跑同一方法，1 PASS / 0 FAIL / 0 ERROR / 0 SKIP，37.913 秒。原 XML SHA-256 为 `2d405fb48d284b80f59dac6b5a5664ba3a1a11ac6d96f84ba0d5ba67cc372ea3`。

没有重跑旧方法、整类或小规模全量。两处 Java 文件的 IDEA 格式 write/check 通过，必要 `skipTests` 打包通过；JAR SHA-256 为 `329ebd36c038ae170b3200076c62c035f018c148c2c207734d221e6998b53e79`。实际两次案例执行共 1 FAIL、1 PASS，另一次编译失败不计为案例。生产改动 1 个文件，测试改动 1 个文件；付费 provider、真实 SSH、原调查数据库读取和恢复操作均为 0。

父独立核验全部源码原像、原 XML/TXT、JAR 的 3 个相关 class 和 15 个 Agent 资源字节通过；完整增量在隔离 Git 目录实际正反应用后精确重构。正常用户 CIM 核对 11 个已知 PID/创建时间均已退出；短子进程未完整采样，不声称全捕获。临时补丁验证目录已精确清理。生产路径尚属原迁移未跟踪文件，测试文件混有其他已有改动，因此不整文件暂存；本轮完整代码及测试增量由 [AGENT-NATIVE-FAILURE-ROUTES-73.patch](patches/AGENT-NATIVE-FAILURE-ROUTES-73.patch) 承接，SHA-256 为 `d3a3a8fc85043d1724c3b9c8f125bacbbc7809fab313f7935a26998517ab46b5`。验证针对当前工作区，不声称纯 HEAD 可独立构建。

证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-native-failure-routes-73/`。下一轮仍只验收 task-board 纯主 Agent 的完整业务、浏览器、重启持久化与产品收尾，审批模型关闭。历史 UNKNOWN 与失败证据保留，完整真实恢复、非默认目录真机验收及最终八项目综合用量标准仍未完成。
