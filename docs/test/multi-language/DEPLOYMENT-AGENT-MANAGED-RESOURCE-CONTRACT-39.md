# Agent 资源契约与按需发布验证 Skill 修复 39

本轮修复[真实业务验证 36](DEPLOYMENT-AGENT-MAIN-ONLY-BUSINESS-36.md)发现的部署侧问题：主 Agent 将普通 JSON 文件声明为持久目录资源，候选阶段可用的文件在正式发布挂载后被替换。只补充 Agent 自己的工具参数说明、固定契约和现有按需发布验证 Skill，没有修改共享存储语义、旧任务计划、现场应用或生产审批。真实模型是否能据此自主完成完整业务，仍待新的 task-board 纯部署轮。

工具 `resources.path/type/seedFile` 现在明确：`FILE` 暴露持久目录树，可选目录种子只在首次初始化复制，空种子创建空目录，已有数据在后续发布保留；`CONFIGURATION` 暴露不可变只读普通文件，需要与原 READ 摘要匹配的普通文件种子。逻辑路径上的候选内容不是隐式种子。模型应根据实际消费者选择存储，普通封存制品文件不因存在而自动需要持久绑定。

固定契约与 `publication-verification` Skill 补充正式制品复制后的资源挂载行为，以及候选检查和正式配置消费的证据边界。验证应绑定当前正式 release、资源身份和原业务要求。未提供 task-board 路径、地址、固定命令或工具步骤，部署模型继续自主选择工具或命令；审批能力和执行授权保持独立。

现有 Skill 会话测试核实际提供给 fake 模型的完整工具定义和固定说明，按需选用 Skill 后核下一请求精确追加完整正文，并保留外来角色 Skill 拒绝、无远端能力调用和查询归属检查。必要 JAR 验包实际发现旧测试硬编码资源数量 23，而当前维护资源为 26；仅修测试按真实源码清单检查 JAR 逐字节，以及允许合法产品 `service/agent/skill/<id>/SKILL.md` 路径，没有改生产 catalog 或包装逻辑。

| 实际定点轮次 | 结果 |
| --- | --- |
| focused-01，明确 5 方法 | 2 PASS / 2 FAIL / 1 SKIP；旧 fake 缺查询处理，JAR 路径未提供 |
| focused-02，只重跑 2 失败方法 | 2 FAIL；新增断言误认初始阶段已有 plan 工具 |
| focused-03，只重跑同 2 方法 | 2 PASS；测试通过真实 READ/identify 进入正确阶段 |
| package-01，`-pl src/mod/deployment -am -DskipTests package` | PASS，23.462 秒；跳过全部测试，包含测试编译 |
| packaged-01，只检查实际当前 JAR | 1 FAIL；旧资源数量断言不符 |
| packaged-02，只重跑该失败方法 | 1 PASS；26 份维护资源逐字节与当前协议位置检查通过 |

共 11 次原执行，5 PASS / 5 FAIL / 1 SKIP；5 个不同方法最新均 PASS，原失败和跳过保留。两个原 PlanProtocol 方法证明完整 native 字段往返和不可变配置契约；两个原 SkillSession 方法证明实际上下文加载、工具目录及拒绝边界；一个原 SkillResources 方法证明包装。没有运行全模块或全项目测试。当前 JAR SHA256 为 `f3eb5dc2f723be602b807af8cb9adc0f839ebb0ed50ba336972fe754cf613b5b`，打包后仅修改测试，不重复构建未变的生产制品。

三个 Java owner 最终格式检查通过，官方 `skill-creator/scripts/quick_validate.py` 通过。初始 sandbox 格式器 bind 权限失败、两个 Python 缺 PyYAML、一次临时依赖安装失败和两处测试换行格式差异均保留。正常用户限定检查及本任务临时依赖解决后，临时安装路径已精确清除，无全局依赖变更。官方 validator 只证明 Skill 结构合法，不代替真实模型效果验证。

另附 35 的[业务测试模型阻断增量](DEPLOYMENT-AGENT-BUSINESS-MODEL-FREE-35.patch)：已有业务入口 `aiEnabled=false`，现有代理在模型传输前硬拒，外层 `finally` 在 Runtime 关闭后核调用计数为 0。最终版本已编译、单文件格式检查和原字节正反补丁核验通过；36 实际运行的是较早版本，异常后未执行最终断言，历史结果不倒算通过。最终版本的真实业务执行仍待新的 App。

源码保护和交付：39 的完整 3282 项 before 与 34 冻结只差父任务 35 的既有测试修改；最终 after-final 只差 5 个约定 owner，nonowner 为 0。父独立核所有原像、当前字节、清单及原报告 SHA/修改时间，Git 在独立 scratch 正反应用全部 owner 补丁逐字节通过，scratch 已删除。35 最终字节保持等于 39 的 before 边界，单 owner 与非 owner 范围也独立核实。

39 [精确原字节增量](DEPLOYMENT-AGENT-MANAGED-RESOURCE-CONTRACT-39.patch) SHA256 为 `60c1ff6997172fa0f898cec607b91a7a705a1a0acc9532ce15b634552256f563`；35 最终 patch SHA256 为 `c13d3e975c882c4d8d084c9399e3954266986719f6ac9befdf82a93dc1b4c4fd`。原工作区已有迁移：现有 Skill 和 SkillSessionTest 的 before 等于 HEAD，可直接提交；另三项及 35 的混合原迁移文件只交付本次精确 patch，不能整体暂存其他改动。没有证明干净 checkout 包含全部迁移后可构建。

正常用户 CIM 核四个已捕获 launcher 的 PID 与 UTC 创建时间，自有活动进程 0；另核准确 Maven selectors 的 Java/cmd 和本任务标记残留 0，无 terminate。验证使用隔离本地 fixture Runtime/H2，真实 Runtime、原 DB、provider、SSH 和浏览器调用均为 0。原 App、发布、数据和 UNKNOWN 不变，数据库版本仍为 1。本轮未使用 GPT-6-Astra。

证据保留在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-managed-resource-contract-39/` 和 `agent-business-model-free-35/`，以 39 的 `after-final/`、`closure.json`、父核和 35 的 `final-version/` 为最终交付依据。此前四 owner 的 after、初版 35 及全部失败证据继续保留。限定本地提交后只启动全新 task-board 纯主 Agent 任务，审批仍关闭，冻结源码/Skill并全程监控；其真实业务、重启及浏览器结果单独记录。
