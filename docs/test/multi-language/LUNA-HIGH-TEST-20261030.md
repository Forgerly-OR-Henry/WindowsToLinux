# Luna HIGH测试夹具适配与验证

2026-10-30，基于主工作区 `fc45594b` 的新模型／用途API完成测试接入。按用户要求，测试中的Luna语言、审批用途均为HIGH；部署Mod生产代码与界面不改，业务请求继续继承主App设置，不添加请求级推理覆写。

## 变更

- `LiveModelSettings`只从明确选中的私有配置区块读取模型、凭据与协议。GPT使用OPENAI适配器及Responses，不混入后面的DeepSeek区块；缺失或歧义配置明确拒绝，日志不输出凭据或私有URL。
- 模型配置使用新8参数构造器，旧单值为DEFAULT、用途defaults为空，避免旧6参数构造器的THIRD_PARTY和迁移初值覆盖用途偏好。保存后通过主App的 `saveReasoning` 和当前修订写入LANGUAGE、APPROVAL设置，保留VISION原值，再进行能力验证。
- Luna默认HIGH，旧测试参数若指定DEFAULT、LOW、MEDIUM或NONE则明确拒绝。其它模型保留显式参数入口。此强度策略仅在测试源中，不改变主App初始化策略或Mod配置。
- `DeploymentStaticLiveAcceptanceTest`增加单独启用的API-only方法，使用全新产品状态库，只验证真实模型能力；不启用staticLive/agentLive、不配置服务器或启动部署。旧r10f树及其未提交WIP原样保留，本次适配在当前主工作区完成。

## 实际验证

| 检查 | 选择器／范围 | 结果 |
| --- | --- | --- |
| 新配置与偏好夹具 | `LiveModelSettingsTest` | 首次19项：18通过、1 ERROR；原日志及XML保留 |
| 主App用途继承 | `PlatformModelSelectionTest#languageAndApprovalUseSeparateDefaultsAndRequestOverrideDoesNotPersist` | 1通过，检查实际线字段、独立用途、请求覆写不持久化 |
| Mod继承 | `DeploymentAiOutputConfigTest#recoveryIsCallerSelectedForMainAndApprovalWhileOrdinaryAdaptersStayDisabled` | 1通过，语言／审批的模型选择和推理覆写均为空 |
| 失败项修正复测 | `LiveModelSettingsTest#writesHighHostPurposePreferencesAndPreservesVisionAcrossProfileSave` | 仅复测该1项，通过；未重跑已通过组 |
| 真实产品SDK | `DeploymentStaticLiveAcceptanceTest#verifiesSelectedModelThroughProductApiOnly` | 1项通过；2配置各TEXT、TOOL_CALLING，共4能力任务SUCCEEDED；两配置LANGUAGE／APPROVAL均HIGH，OPENAI／Responses |
| 必要编译及格式 | Maven `-pl src/mod/deployment -am`，显式 `-Dtest`；IDEA命令行仅3份修改测试源 | 编译通过、3文件格式化完成；未运行全量测试，无UI／Computer Use |

首次ERROR是本次新增夹具的重复保存步骤仍持有平台分配新凭据引用之前的旧引用，被 `PlatformModelService.save` 拒绝，消息为“The credential reference changed; reload settings”。修正为重新读取当前引用后保存，单项复测通过。这是新夹具错误，没有撤销旧生产修复，也不把初次失败改成首过。正式八项目分母未受影响。

真实API-only在约21.38秒内通过，Maven整体约28.32秒。4是能力任务数，不是已审计的物理HTTP总数；provider用量与内部恢复次数未独立采集，不称零异常或完整账单，也没有直接请求DeepSeek。成功是模型能力验证，不是完整业务、交付、安装器或远端部署验收。

实际入口示例（Maven另指定本机已核验的 `-s/-gs` 设置文件）：

```powershell
mvn -pl src/mod/deployment -am '-Dtest=DeploymentStaticLiveAcceptanceTest#verifiesSelectedModelThroughProductApiOnly' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' '-Dmanaged.runtime.apiOnlyModelSmoke=true' '-Dmanaged.live.reasoningEffort=HIGH' '-Dmanaged.live.approvalReasoningEffort=HIGH' test
```

## 证据与后续边界

[本阶段汇总](H:/Program/WindowsToLinux/.ai-workspace/evidence/luna-high-app-preferences-20261030-01/validation-summary.json)绑定源码SHA及分阶段JUnit结果；`local-01`保留初次错误，`local-02`是明确标注的单项复测，`live-01`是唯一实际产品API-only尝试。[收尾](H:/Program/WindowsToLinux/.ai-workspace/evidence/luna-high-app-preferences-20261030-01/local-closeout.json)确认7个记录PID均已不存在，原生工具均终态、无信号发给其他进程、无本任务临时目录待清。[格式化临时文件收尾](H:/Program/WindowsToLinux/.ai-workspace/evidence/luna-high-app-preferences-20261030-01/formatter-temp-closeout.json)另确认本次formatter留下的临时目录与Netty DLL共2文件，逐祖先／子项nonreparse及精确边界校验后清理，两个目标均不存在，既有缓存不动。新的产品状态仅以加密形式保留供取证，不打开旧失败／UNKNOWN库或重放旧任务；未改私有配置、代理服务或用户论文。

原八项部署目标仍暂停、未达成，本次没有清场或开启新轮。后续恢复须整合隔离生产修复、新主App API和本次测试改动，重新核实际工具／凭据／产物并冻结，再经产品清场及独立复查开启全新八项；不能直接沿用旧R09冻结产物，也不能续跑其FTP或拼接已成功项。
