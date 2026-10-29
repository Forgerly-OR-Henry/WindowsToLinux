# AI 厂商适配、用途推理偏好与模型选择 API 验证

日期：2026-10-30。环境：Windows、JDK 21、Maven。只执行列明的定点测试及相关模块编译，没有运行全项目或模块全套测试；没有使用真实厂商账号发起付费调用。

## 交付行为

- `app.ai.provider` 保存合同、类型、注册器及发送前不兼容异常；`provider.adapter` 保存六家实现、第三方通用实现及静态规则。主启动装配注入注册器。合同包不引用实现包。
- 厂商与协议独立配置；确切型号和协议的已知控制带官方来源及核对日期。未知型号和第三方不从名称推测，默认省略参数。旧通用模型设置按原协议语义保留。
- 数据库按模型配置及 LANGUAGE、APPROVAL、VISION 保存偏好。Config 缺省 MEDIUM、二选模式 STANDARD；缺失项才初始化，旧单值优先作为迁移初值，已有用途值不覆盖。语音请求不发送推理控制。
- 全部模型查询包含未验证状态，不暴露端点、凭据或凭据引用。推荐查询按用途顺序返回；显式配置 ID 无须属于推荐列表，仍检查能力、授权、预算和修订。指定调用不自动换模型。
- 推荐文字／视觉维持现有明确 HTTP 拒绝后备边界。文件语音仅对明确 429 或 5xx 拒绝后备；实时语音仅在会话打开前、连接失败或握手被拒且资源已关闭时后备。取消、UNKNOWN 和已绑定原生会话不跨模型重放。
- 请求未覆写时继承用途偏好，显式 DEFAULT 省略控制；覆写不持久化。不兼容控制或工具组合明确失败，不删参数或降档。响应的 DEFAULT 不代表已知内部实际计算量。请求复制、生成重试、修订和续接保持原选择。
- 主 APP 编辑器增加厂商、移除单一强度；详情页显示已收录控制及三个用途的独立设置，后台保存、修订冲突反馈及恢复已确认值。本地化重建保留草稿且不写入数据库。
- 部署 Mod 仅委托适配新 API；其语言和审批请求不主动选择配置、不覆写推理，没有新增调节控件。

## 静态规则来源

代码中的核对日期固定为 2026-10-30，运行时不查询或刷新。控制集合以各适配类的精确型号列表为准，不对后缀或相似型号扩展。

| 厂商 | 原生映射及来源 |
| --- | --- |
| OpenAI | Chat 的 `reasoning_effort`、Responses 的 `reasoning.effort`；[推理指南](https://developers.openai.com/api/docs/guides/reasoning)、[工具与协议限制](https://developers.openai.com/api/docs/guides/deployment-checklist)、[GPT-5.1](https://developers.openai.com/api/docs/models/gpt-5.1)、[GPT-5.2](https://developers.openai.com/api/docs/models/gpt-5.2)、[GPT-5.4](https://developers.openai.com/api/docs/models/gpt-5.4) |
| Anthropic | Messages 的 `output_config.effort` 与 `thinking.type`；[effort](https://platform.claude.com/docs/en/build-with-claude/effort)、[thinking](https://platform.claude.com/docs/en/build-with-claude/thinking) |
| Gemini | OpenAI 兼容协议的 `reasoning_effort`；[官方兼容接口](https://ai.google.dev/gemini-api/docs/openai) |
| Grok | Chat／Responses 的 effort；[官方推理指南](https://docs.x.ai/developers/model-capabilities/text/reasoning) |
| DeepSeek | `thinking` 开关及三种协议的 effort；[官方思考模式与映射](https://api-docs.deepseek.com/guides/thinking_mode/)、[工具选择限制](https://api-docs.deepseek.com/api/create-chat-completion/) |
| Qwen | OpenAI 兼容请求的 `enable_thinking`；[官方深度思考说明](https://help.aliyun.com/zh/model-studio/deep-thinking) |

Claude 4.6/4.7/4.8 的已收录 adaptive 型号选择档位时显式开启 adaptive thinking。Opus 4.5 只映射官方输出 effort，不分配旧式 thinking Token 预算。本版没有自定义 Token 预算输入。

## 通过的定点验证

所有 Maven 测试命令都使用 `-Dtest=...` 和 `-Dsurefire.failIfNoSpecifiedTests=false`，模块范围为 `-pl src/app/main -am` 或与 `src/mod/deployment` 的相关依赖。

| 定点范围 | 验证内容及结果 |
| --- | --- |
| `ModelProviderRegistryTest` | 六家线参数、确切型号集合、未知项、中等映射、标准／思考区别、Claude adaptive、工具不兼容拒绝、适配后最终字节预算：4 项通过 |
| `AiReasoningStoreTest` | 实际缺失用途键的旧值迁移、已有值优先、幂等、独立用途、过期修订拒绝、重启保持、Config 只初始化一次及证据失效：2 项通过 |
| `AiProtocolStoreTest`、`AiPriorityStoreTest` | 协议、旧强度、能力证据、用途顺序及重启相关回归：8 项通过 |
| `ConfigLoaderTest`、`AiModelSelectionContractTest` | 配置严格加载及文档键覆盖；请求复制保留模型与显式 DEFAULT：10 项通过 |
| `PlatformModelSelectionTest` | 全部／推荐查询、未验证限制、推荐外指定文字与视觉、指定失败不后备、推荐后备、用途独立及覆写不持久化：4 个测试方法分别定点通过 |
| `VoiceModelSelectionTest` | 文件／实时指定调用、指定失败不后备、文件推荐后备、实时连接前后备：4 项通过 |
| `VoiceServiceTest#fileRejectionDoesNotSilentlyRotateAndSavedVoiceRoutesSurviveRestart` | 原输入拒绝不后备、语音路由重启保持：通过 |
| `PlatformAiServiceTest#actualWireUsesSelectedModelBudgetRolesAndReportedUsageAndSurvivesDatabaseRestart` | 真实本机 HTTP、预算、角色、用量和数据库重启：通过 |
| `PlatformAiServiceTest#generationRecoveryHandlesIncompleteChunksOnlyAfterIndependentClientAndLeaseRelease` | 真实残缺 chunk 后新连接生成，显式模型及 HIGH 参数字节保持，旧客户端和占用先释放：通过 |
| `NativeModelContinuationsTest#continuationKeepsTheOriginalReasoningOverrideAndModelBinding` | 续接保持原模型与覆写，改变推理控制明确拒绝：通过 |
| `PlatformNativeModelTest#nativeRoundTripUsesSelectedProtocolEvenWhenUrlLooksLikeChat+nativeNoneRoundTripUsesExplicitProtocolSetting` | 三种原生协议往返及显式标准控制：9 项通过 |
| `PlatformModelServiceTest#textAndVisionVerificationAlwaysUseIndependentBudgetWithoutLegacyLimit`、`PlatformReasoningNoneTest` | 独立验证预算及不兼容强度不静默改默认：3 项通过 |
| `PlatformReasoningWindowTest` | 真实 Swing 详情显示、用途独立保存、保持视觉值、本地化保留草稿且不写入、过期保存反馈及恢复：通过，截图检查完成 |
| `DeploymentAiOutputConfigTest#recoveryIsCallerSelectedForMainAndApprovalWhileOrdinaryAdaptersStayDisabled` | 部署语言／审批请求的模型选择和推理覆写均为空，继承主 APP：通过 |

数据库重启用例在沙箱外运行：独立 H2 诊断确认沙箱内 `Files.isWritable` 返回 false 导致数据库重开只读，同一诊断在正常用户权限下可重启写入。未修改生产数据库行为来绕过该环境差异。

相关主 APP、公共 API 和部署 Mod 源码／测试编译及 Mod 描述生成已执行。IDEA 仅格式化本次修改的 54 个 Java 和本地化文件，最终格式检查通过。以 HEAD 为基线对本次 41 个生产 Java 文件运行既有 Javadoc 检查器，新增文档缺项为 0；既有全仓文档缺项未在本任务清理。

## 未通过与未验证范围

- `DeploymentApprovalConversationTest` 整个测试类本轮 20 项中 17 项通过、1 项断言失败、2 项异常。失败方法为 `toolchainPreparationSuppliesOneCurrentSourceAfterHistoryAndPreservesFinalDenial`、`toolchainCurrentSourceClaimsAndChangedRequestsRejectBeforeApproval`、`toolchainCompleteEvidenceRetainsTheOriginalEncodedRequestLimit`。堆栈定位到未修改的 `DeploymentAgentToolchainPreparationEvidence.source` 对源码标记的校验，在模型发送前拒绝。本任务没有修改工具链实现或其证据规则，也未将这 3 项记作通过。
- 仓库级 `productionPackageDependenciesAreAcyclic`、`allEnumAndRestrictedTypeNamesFollowTheStrictSemanticStandard`、`productionClassesStayWithinAstComplexityLimits` 未通过，分别报告部署 config/build 包循环、既有类型命名及多处大类／长方法限制。本次没有扩大到仓库级结构重构。`productionNamesUseStableResponsibilitiesAndMatchFileDocumentation`、`packageTreeIsFlatBoundedAndFreeOfObsoletePaths`、`publicApiPackagesFollowTheDocumentedDependencyDirection`、`documentedPublicApiDependenciesMatchAttributedGraph` 通过；新增厂商合同包对 adapter 包的反向引用定向检查通过。
- 没有运行全量测试，没有真实厂商凭据／网络端到端验收，没有验证厂商后来发布或未收录的型号。官方能力表是本版静态快照。
- 用户论文改动保留，未纳入本次提交；暂停的部署验收未恢复。测试窗口、HTTP／WebSocket 夹具及格式化进程随测试或命令退出。
