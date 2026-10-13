# Agent 配置消费与跨组件验证语义 47

[44 发布通过、45 业务 502](DEPLOYMENT-AGENT-MAIN-ONLY-BUSINESS-45.md)暴露了验证覆盖缺口：旧候选代理实测目标 18101，正式前后端配置均为 18201，但旧前端树重新封存；首页与独立后端接口健康没有覆盖正式同源业务链。46 原数据库只读投影通过完整身份和配置摘要检查，制品消费正文与 502 错误正文仍缺失。没有证据修改共享配置传递、代理代码或审批，也没有确认具体 502 根因。

本次仅改 Agent 的四个既有文件：计划 schema、固定协议、按需 `publication-verification` Skill、既有实际会话 Skill 测试。配置键与端点声明不自动重写制品消费者；模型应核对入口、字面参数、消费键及覆盖优先级。依赖表达实际消费关系，单独健康不证明独立；配置或目标变化后，旧候选证据不能证明当前业务消费链。Skill 引导模型选择覆盖实际业务入口的证据，缺失覆盖保留为未验证。

没有新增空依赖列表审批门、固定调用顺序、项目路径、端口或样例步骤。模型仍自主选择产品工具或命令排查；Skill 只按需加载，不能授予执行权限、替代正式回执或绕过审批。STATIC/ASSISTED、共享 helper、数据库版本和预算配置均未改。

实际限定 selector 为 `DeploymentAgentSkillSessionTest#actualSessionRejectsForeignSkillThenActivatesSelectedGuidanceWithoutRemoteEffectsOrSourceEvidence`。沿真实 `DeploymentAgentSession` 与 fake AiService 请求，核更新后的计划工具 schema、原消费者源码、不同旧/新目标及未观察正式链事实进入请求，随后按需 Skill 正文进入系统消息；外角色 Skill 和项目伪造 Skill 不获得权限。反例中的两个 200 和 `NOT_OBSERVED` 是明确的测试输入，不是执行了代理或证明真实链失败。

第一轮 1 failure：新增断言误从上下文顶层取 requirements；修为实际 `state.requirements` 后，第二轮 1 PASS、0 failure/error/skip，6.964 秒。两轮报告、修改时间、摘要与各自源码冻结原样保留，未运行全套测试。之后只收窄 dependencies 描述，没有改变已通过方法的语义或断言，未重复该测试。

官方 skill-creator `quick_validate.py` 和 Java 格式检查通过。初始仅 compile 因仓库的 test-support classifier 在 `process-test-classes` 才附着而依赖解析失败；改用现有 reactor `process-test-classes -DskipTests`，最终退出 0、源码冻结 0、执行测试 0，原失败日志保留。最终四 owner 精确补丁逐字节正反应用通过，SHA256 `fc748a9b2fee78957268247b6867c35e8114ee3537fd270c1bb65b0f7af8169e`。

本地验证使用隔离临时 fixture Runtime，没有原 live 数据库、真实部署 Runtime、provider、SSH 或浏览器调用。临时依赖精确清除，自有验证进程最终按 PID 与 UTC 创建时间核为 0，没有终止无关进程。3282 个源码/Skill/样例 before 等于 43 after，只有四 owner 变化，其他迁移改动保留。

父复核全部 3282 个 before/after-final 原字节、当前文件及原 XML 来源通过，非 owner 差异 0；在独立受控目录用 Git 正反应用精确补丁通过，目录已清除。父初版验证器误将最终 manifest 配合早期 raw 目录而失败，改为权威 after-final/raw 后通过，辅助失败记录保留，没有修改源码或重复业务测试。

源码增量见[原字节精确补丁](DEPLOYMENT-AGENT-RUNTIME-CONSUMER-VERIFICATION-47.patch)。证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-runtime-consumer-verification-47/`。本次只完成本地语义接入验证，不能称正式代理故障已修复。下一轮冻结 `after-final`，继续 task-board 纯主 Agent 自主部署，审批仍关闭；发布之后还需真实业务、重启和浏览器通过。正式双 Agent 与其余项目尚未推进。本地只提交独立自有文件及混合原文件的精确补丁，不推送。
