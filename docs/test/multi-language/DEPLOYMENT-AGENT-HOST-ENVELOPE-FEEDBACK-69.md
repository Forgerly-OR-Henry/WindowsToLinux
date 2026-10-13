# Agent 宿主命令完整封套拒绝反馈：67–69

已修复 Agent 宿主前置命令的一条发送前反馈路径。`DeploymentAgentRemoteTools.hostCommand` 使用本次真实 operation、原目录、原脚本及产品说明生成完整可执行封套，按真实 Evidence 所用 fixed gate 预检；只有这一门函数直接产生的 STATIC_INVALID/DENIED 转为原调用的有界拒绝反馈。反馈保留 task、body/envelope SHA 与 NOT_SENT，不带脚本或秘密，不生成 dispatcher 回执。模型自行决定新的输入，随后仍须完整独立审阅和平台授权。

共享 `DeploymentModelCommandRunner.executeBound` 只接收同一操作身份并重新核对原目录摘要与 canonical 封套摘要，消费同一封套。无效绑定、目录/封套漂移和任意 phase 的重复 operation 在 INTENT 前终止；它们不进入 Agent 的可纠正反馈。原 checkpoint 归属、16 条/7200 秒、DISPATCHED 未结清、路由/取消/精确授权/发送/回执及 envelope 清零机制保留。Agent 规则留在其独立包，共享 Runner 没有 Agent 类型判断或审批耦合。

普通 execute 的 raw gate、limits 后随机 UUID 位置和未启用绑定时的正文，经源码投影核对等价。AgentPublishRunner 与 ASSISTED 仍调用普通入口；STATIC 未使用此 Runner。本轮没有新增其他模式真实验收，也未独立执行无效绑定等负向身份案例，不将源码核读表述为这些场景实测通过。数据库版本、配置、Skill、静态策略、共享审批和 UNKNOWN/cleanup 边界未修改。

67 对原 66 只读核定：末次 host 调用已有 PROPOSED、无对应 DISPATCHED，未反馈原 call，也没有下一模型请求。共享审批包装已丢原 SecurityException 来源，原日志未保留完整输入，所以 **66 确切静态条款仍 UNKNOWN**，未恢复。不能仅凭 ApiException 错误码宽捕获，不能用候选自由脚本 gate 替代宿主完整封套 fixed gate。真实失败及用量见 [66 记录](DEPLOYMENT-AGENT-MAIN-ONLY-66.md)。

68 只新增并运行 `DeploymentAgentWorkflowTest#hostEnvelopeStaticDenialReturnsBoundFeedbackBeforeIndependentLegalCommandAndCommit`，固定 OPENAI_CHAT_COMPLETIONS，一次本地基线 **1 FAIL / 0 ERROR / 0 SKIP**，XML 6.612 秒。实际临时 H2 TaskService 与原 RuntimeFixture 中，受控原脚本通过 raw fixed gate，同一真实 operation 的完整产品封套被 fixed gate 拒绝；checkpoint 留 INTENT、发送/独立 host 审阅/回执/App/release 全为零，原调用反馈为零。正确期待反馈一次而失败，属于目标反例，不是 mock 同码异常或夹具阻塞。准备阶段误将全部 index entries 当成 staged changes 的辅助 guard 失败已保留，没有源码或 Maven 执行。

69 corrected-01 只复跑同一方法/Codec，测试字节未改，首轮 **1 PASS / 0 FAIL / 0 ERROR / 0 SKIP**，XML 48.299 秒，源冻结变化零。原调用反馈一次、被拒输入发送和 checkpoint entries 均零；新合法 host 输入独立审阅/精确发送/原回执各一次，最终任务 ENDED/SUCCEEDED，native publish/verify/finish 与正式本地 App/release 各一条。14 次 LANGUAGE 请求来自脚本模型，本地验证不等于付费 API 模型自主部署通过。原观察保留 1091 次重复 checkpoint 读取投影；有界摘要只有同一合法操作的四个 distinct 状态，不能把读取次数当作命令或 phase 转移次数。

必要三 Java 路径 IDEA write/check 和明确 skipTests package 通过，未跑整类、模块或旧已通过方法。JAR SHA-256 为 `856953499723e0e8a42823b97d689b7c313a5631315f2488b665ccafc9f8b767`，7 个本次相关 class、15 个 Agent 资源与实际 classes/source 字节一致，65 的 9 个 Session class 保持原像。3296 项完整 before/after/current 核对通过，69 仅两个生产 owner、测试字节增量零；69 delta 及 68→69 合并补丁真实 Git 正反应用均一致。

9 个已知 PID/UTC 创建时间均已退出，相关 Java/格式签名为零；格式与打包短子进程采样较晚，明确不声称全部短 PID 都捕获。父正常用户 CIM 正控制及已知精确身份核对通过，未终止其他进程。原 DB/真实 SSH/付费 provider/恢复删除均零。父初次辅助分类误假设迁移路径已在 HEAD，保留失败后按“HEAD 无该路径”校准，未重新运行业务测试。

两个生产路径属于原未提交迁移，测试文件亦混有已有任务改动，均不整文件暂存；本次独立代码/测试增量由 [合并补丁](patches/AGENT-HOST-ENVELOPE-FEEDBACK-69.patch) 承接，SHA-256 `add62ddbca840341b1a02b1c1b6aee3b49eeb3f7b2032ab6e06362e0bfaa99c8`。验证针对迁移工作区，不称干净 HEAD 可独立构建。原证位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-host-denial-feedback-{preflight-67,baseline-68,fix-69,parent-68,parent-69}/`。下一步仍仅 task-board 的真实纯部署及完整业务/浏览器/重启/产品收尾；原 58 UNKNOWN、旧恢复失败与其他未验项继续保留。
