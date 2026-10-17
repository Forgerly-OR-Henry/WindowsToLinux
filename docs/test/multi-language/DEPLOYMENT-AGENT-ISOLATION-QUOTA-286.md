# Agent 隔离规划的固定后续成本 286

FULL285 的计划从 backend/web 各 256 MiB 改为 256/384 MiB。后端候选已分配后，本地选择器未保留该组件未来 release/app 的 256 MiB，也未计额外 64 MiB 探测需求，导致接受的预算随后被严格隔离检查合法拒绝。完整实机、只读账本和失败 FULL 用量见 [285 记录](DEPLOYMENT-AGENT-ISOLATION-LIVE-285.md)。

仅修改 Agent RemoteTools 和直接相关 PrepareReviewTest，不改 STATIC/ASSISTED、共享 Quota/preserve、非隔离 Agent 或原生准入阈值。

- 仅 `isolationPolicy != null` 时，先计已 prepared 组件未来 release quota 和额外 requiredBytes，再把剩余内部规划额度交原双份候选选择器。该阶段是基于一个受控根容量的保守规划；跨文件系统最终判断仍由 fresh probe 负责。
- 新 Selection 的 availableBytes 保持本次原生观察值；已创建候选由 preserve 保持物理配额和历史观测。内部扣减值不冒充 native free，不把固定成本藏入 reserve。
- 拒绝反馈分别保留真实 available、完整 required、固定成本、候选双份成本及 reserve。无新 inputs 也检查固定后续成本，低容量夹零仅用于内部算术，不能放行；有界聚合使用 exact arithmetic。
- 原每动作独立 APP、新 grant、fresh READY、不可变候选配额及所有 ownership/UNKNOWN 边界保持。

## 实际定点

只运行 `DeploymentAgentPrepareReviewTest#isolatedReplanReservesPreparedReleaseAndProbeWithoutChangingNativeCapacity`，offline Maven 显式 `-pl src/mod/deployment -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false package`。

复用真实 Runtime/Task/RemoteTools 及原审批、exact grant 通道，远端和模型使用本地有界替身：首 native2233180160 B 选 256/256；backend 走 create/query/upload/verify/open 后，以 native1964691456 B 重规划仍选 web256；第二次新审批/grant 的完整隔离回执 required832 MiB、reserve1024 MiB 通过严格 parse/gate。另在同方法覆盖 explicit384 合理拒绝及完整数字、差1 B/等额边界、全部已 prepared 和 native1 B，原 physical quota 不变。它没有执行真实 SSH、工具链安装或完整 Session 模型规划。

唯一 test-01/session38103 实际 1P/0F/0E/0Skip、JUnit12.321s，package 退出0、Maven29.698s；UTC15:22:50.2104382Z—15:23:20.8034266Z。两源在 package 开始后未改变，deployment.jar4250091 B 实际在该窗口。没有全量，也未重复前轮已过且未改的回执合同、批量观测、总预算或取消测试。

核本轮 Java0、owner/绝对边界及无 reparse 后仅清 c286a/u286t；原始结果保留。辅助根 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-capacity-refusal-286/`，含两源 before、定点、只读审计及固定 reader。reader 实际 javac/read0、公有92事件匹配、DB pair属性不变；它没有运行 Runtime/TaskContext、恢复、模型或 SSH，自有三个临时目录已清。

工作树保留其它任务和迁移，仅交付两路径窄 patch，142增/13删、reverse check通过；只在本地提交阶段生成六项限定摘要（两源、JAR、patch、本说明、285 LIVE），不推送。没有用本修正回填 285 UNKNOWN 或改变任何旧结论。

下一步优先通过现有 `reconcilesOriginalTaskThroughProduct` 的 `originalTaskOnly=true` 入口处理本次285：保存原独立审批 route，不重写模型配置，不遍历其它任务，不重入未知模型会话。只有新产品归属/状态检查、独立 APP/新 grant 及清理后确认全成功才能结清。现有 fixture 仅强断言 ENDED；最终业务结果 FAILED、资源与 receipt 还须独立核对，不能把候选收尾算业务通过。

原194不恢复，旧238/280/281/282/283/284不恢复、不改判或重放；没有新 FULL 或第七保护集。File Transfer 完整 BUSINESS v2、真实浏览器及 STOP/DISABLE 后才进入 CSV，两个项目完成后暂停报告审批占比、输入加权 cache 及已知/未知；纯恢复与失败 FULL 分列。
