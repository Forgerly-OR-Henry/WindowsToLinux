# Agent 发布提案身份证据：240 局部修复与验证

记录日期：2026-10-17。240 已完成本地定点验证和跳过测试的打包；没有进行新的实服部署、provider 调用或完整业务验收。

本修复让 Agent 原生 `deliver → PublishRunner.publish` 的固定发布请求，在同一个独立 APP 中获得原提案的身份映射。补丁为 [AGENT-PUBLICATION-EVIDENCE-240.patch](AGENT-PUBLICATION-EVIDENCE-240.patch)，SHA256：

```text
b107b8860ae791b7ff410e5b99e0939565a111ed4e85556b5207034283e4afd9
```

## 问题与修复范围

238 的实际 native 调用记录为日志 9912 的 `deliver`、9940 的 `publish`。最后独立 APP 在日志 11099 拒绝发布：固定 helper 命令形状合规，但可读上下文没有明确连接发布参数、封存候选与原提案的摘要角色。

这些摘要本来就描述不同对象。`publish-deployment/container` 的前四个业务参数依次是 application、candidate、release、预期 ownership manifest。release 按既有算法由 artifact、当前 source、configuration、runtime、resources 联合派生，无须等于 artifact 或 owner；当前发布 ActionIntent 使用所选 artifact 作为 `businessRevision` 也属于现有合法行为。不能因两者相等就判定 `SEALED_ARTIFACT` 错填了业务标识。

补丁精确包含以下五个路径，四个现有文件以 240 before 原字节为基准，新 Evidence 完整新增：

| 文件 | 变化 |
| --- | --- |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/contract/DeploymentAgentInputs.java` | 提取并共用原 `publicationReleaseIdentity` 算法，保留原 UTF-8 拼接及 SHA-256 语义。 |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/review/DeploymentAgentPublicationEvidence.java` | 新增本次发布作用域的只读身份投影与复核。 |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/tools/DeploymentAgentRemoteTools.java` | 在真实 `originalCommit.execute` 工作回调内，通过原 `context.withCurrentEvidence` 接入原 PublishRunner，并合并发送前复核。 |
| `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/agent/review/DeploymentAgentHealthEvidenceTest.java` | 增加发布前夹具及三个定点方法。 |
| `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/agent/tools/DeploymentAgentPublishRunnerTest.java` | 核实际 native APP 收到投影，且决定与宿主 grant 仍独立。 |

投影绑定原 task/server/profile/layout、规范 managed unit、candidate/workspace、SOURCE、CURRENT_SOURCE、seal 执行/结果、artifact/tools，以及提案 config/runtime/resources 和派生 release。覆盖的固定发布请求必须与原对象及规范 literal 参数一致，变化或缺失即拒绝；未覆盖 operation 返回空补充并清除 pending。所选绑定在 APP、host 授权及发送前重新核对。

复核只比较原身份和所选封存回执元组，不以整个事件流或 execution 更新时间相等作为条件。普通审批记账追加、同一图切换到下一组件仍合法。源码快照 manifest、受保护 tree、Prepared 初始 revision 分别保留各自含义，不强制同值。封存顺序依据生产 Session 的 CANDIDATE→CURRENT_SOURCE→SEAL_EXECUTING→SEAL_RESULT→SEALED_ARTIFACT，以及 deliver 随后追加的 SEALED_TOOLS。

owner 只表示原 proposal 中的预期值，不证明远端 owner 已存在或匹配。投影不提供 APP verdict、host authority、远端 CURRENT 或发布成功证明；不读取秘密正文，也不把当前请求中的秘密摘要称为已验证秘密。既有独立 APP、每次新 host grant、`rememberAuthorization=false` 保留。STATIC、ASSISTED、持久 schema、依赖及既有发布命令均不在本补丁的变更范围。

## 实际本地验证

辅助证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-evidence-240/`。两次实际执行及原始 XML/txt、stdout/stderr、冻结结果均保留，未用后一次结果覆盖首轮错误。

| 实际执行 | 范围 | JUnit | test / package / sourceFreeze |
| --- | --- | --- | --- |
| 首轮 session `66092`，进程退出 1 | 四个指定方法 | 25 cases：24P / 0F / 1E / 0Skip | `1 / 未执行 / 0` |
| attempt02 session `97474`，进程退出 0 | 仅失败的 negative 参数化方法组 | 20 cases：20P / 0F / 0E / 0Skip | `0 / 0（skipTests）/ 0` |

首轮唯一 E 是新增测试反例使用 `unrelated.service`，在 `DeploymentUnitIdentity` 的基础命名校验处先失败，尚未进入 Evidence。只修改 HealthEvidenceTest 一行，改取夹具 frontend 的合法 managed `systemdUnit`：backend 的 id、releaseRoot、owner 保持原值，由 Evidence 的规范 `forManaged` 比较拒绝异 unit。三份生产源码和 Runner 测试未变；已通过的方法没有重跑。

最终四方法覆盖为 **25P**：首轮另外三个方法的 5P，加上 attempt02 的 20P。两轮 raw 报告合计 **45 cases，44P / 0F / 原 1E / 0Skip**；这不是一轮 45P，也不是全量测试通过。

| 指定方法 | 最终通过 case 数 | 证据来源 |
| --- | ---: | --- |
| `DeploymentAgentHealthEvidenceTest#publicationIdentityBindsDistinctDigestsWithoutClaimingObservedOwner` | 1 | 首轮；方法内覆盖不同摘要、自定义布局、双组件切换和普通记账追加。 |
| `DeploymentAgentHealthEvidenceTest#publicationIdentityRejectsMissingOrChangedOriginalBindings` | 20 | attempt02；包括缺失/变化身份、命令参数、目标、配置与非规范 managed unit。 |
| `DeploymentAgentHealthEvidenceTest#publicationBindingsAreRecheckedAfterAppHostAndBeforeSend` | 3 | 首轮；APP、host、发送前三个边界。 |
| `DeploymentAgentPublishRunnerTest#actualBuiltInPublishReceivesIdentityEvidenceAndStillNeedsIndependentDecision` | 1 | 首轮；方法内分别控制 DENY/APPROVE，核无 publish-send 或独立新 grant。 |

测试中的 APP、host、transport 为本地替身。它们验证生产接线和授权边界，不证明真实模型会批准、远端已发布或应用业务可用。

最终源码为 3311 项，`after-manifest240.json` SHA256：

```text
42c4bd533b0facf478b9d0b6a4b0bb8a3a70d9f3f105703a6728dfd744af8e2d
```

本轮 before 基线为 HEAD `fd254a3fb831adf9eba24fe50433e0fb52021683`、3310 项源码及 manifest `5d53bf56e2c9a024ae33a9a2fbee18d1eb3efe44e823db921f518e97d2238f97`。最终打包及父代理唯一 metadata capture session `3631` 均实际结束 0。实际产物与闭包记录如下：

| 项目 | 实际结果 |
| --- | --- |
| `deployment.jar` | 3,772,021 bytes；SHA256 `725ca7a24962b2da193868136c5efe850da933dd19e397b1621ee7c28c9924c7` |
| package metadata | `metadata-tools/after-metadata240.json`；SHA256 `833404e8f623885abc9ff0800f3dba9b32f6320838cb49c70a5ff6e7a8d80829` |
| classpath metadata | `metadata-tools/classpath240.json`；SHA256 `03deb6bb3b2d2cc0f651dc0ba67fe4f5d1a4f49b9730218cf67c00f9068c951f` |
| 本地闭包 | deployment 33 groups、AI 3、platform 4 families、complete host 5；CP 54 entries / 3213 files。 |
| 原验收 fixture class | 字节核同；SHA256 `b300a41c48dda597a562b82d6c6bb047f4cffa7d59d6861b3eecfe489affd421`。 |
| host JAR | 五份全部字节不变。 |
| 241 consumer canonical compatibility | 本地实际检查为 true；不是恢复执行或实服验收通过。 |

上述结果见 `metadata-tools/parent-package-verification240.json`、`parent-metadata-crosscheck240.json`。打包与 CP 文件核对不等于证明后续运行进程已经加载这些字节。

两轮正常、非管理员 CIM 收尾分别核了 13 / 20 个精确进程记录，各清理两个自有目录。末次 `parent-final-process-verification240.json` 对含两轮 closer 的 35 个精确 PID/CreationDate 对核得 `exactAlive=0`，四个自有目录均不存在，positive control 成立，未执行终止操作。未停止用户已有或无关进程。

正式补丁生成时，将五条 diff 路径与 frozen before/current source 哈希逐项比较，并在内存应用全部 hunks，重建结果与最终五源码逐字节一致；仅一份新增文件。未调用 Git 应用命令，未再次执行 Maven、Java、DB 或模型调用。

## 保留状态与后续边界

240 不增加 FULL 或完整业务通过计数：当前仍为 **9 FULL / 0 完整业务通过**。原 238 Task `072bc074-4bce-4c14-b425-f2ff965c7d40` 仍为 UNKNOWN，保留运行态，待 241 的必要产品协调；本文不授权恢复或重跑。

194、234 已结清，不再恢复；221 不自动恢复；CSV Inspector 仍排队，File Transfer 与 CSV 严格逐个。两个验证完成即暂停，分别报告各自 FULL 的 APP 占比与 cache，再等待真人决定，不自动扩展至第三个或八项目。

本阶段曾使用 Astra 对管理能力扩展的具体适用性作独立判断；单组件内部管理扩展仍待真人范围选择。该建议未混入 240 代码、补丁或本轮本地验证，也不构成后续操作授权。
