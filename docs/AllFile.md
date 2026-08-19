# WindowsToLinux 全文件架构索引

> 范围：仅记录 `src/` 下的非测试维护文件；测试、编译产物、忽略项和其他目录不纳入。
> 排序：同级项目按名称字母升序排列；模块的 `resources/` 目录固定置于最后。
> 粒度：模块、包和文件均有简体中文短注解；Java 文件仅说明与文件同名的顶级主类型。
> 路径：省略重复的 Maven 源码、资源与项目域名目录前缀，直接展示模块内职责包。

```text
src/  # 项目源码与模块根目录
├─ app/  # Windows 桌面应用模块分组
│  ├─ db/  # 桌面 SQLite 持久化模块
│  │  ├─ connection/  # SQLite 连接与事务基础包
│  │  │  └─ DesktopConnectionFactory.java  # 创建启用外键约束与有界忙等待的 SQLite 连接
│  │  ├─ DesktopPersistence.java  # 在版本化迁移的 SQLite schema 上组合聚焦桌面仓库
│  │  ├─ entity/  # 桌面持久化记录包
│  │  │  ├─ CurrentRelease.java  # 某个受管应用最后成功发布的发布身份
│  │  │  ├─ ManagedApplicationGraph.java  # 一个已成功部署应用的持久且不含秘密的拓扑
│  │  │  ├─ OpaqueSecret.java  # 仅供数据库保存的加密材料；本模块绝不解密它
│  │  │  ├─ StoredAiProfile.java  # 非秘密 OpenAI 兼容端点元数据；API 密钥保留在 app/secret 中
│  │  │  ├─ StoredAiProviderProfile.java  # 命名的非秘密 AI 提供者元数据；仅持久化其不透明的凭据存储键
│  │  │  ├─ StoredAiRoleAssignment.java  # 从一个固定协作角色到一个命名提供者的非秘密分配
│  │  │  ├─ StoredApplicationSecretRevision.java  # 用于定位普通配置和发布目录之外秘密的不可变元数据
│  │  │  ├─ StoredServerProfile.java  # 非秘密桌面连接元数据；密码或密钥保留在 app/secret 中
│  │  │  └─ SuccessfulManagedDeployment.java  # 经验证部署事务后持久化的一个组件状态
│  │  ├─ migration/  # 数据库版本迁移包
│  │  │  └─ DesktopSchemaMigrator.java  # 按版本顺序迁移并校验桌面 SQLite 数据库结构
│  │  ├─ pom.xml  # 配置 SQLite 数据访问模块的依赖和构建
│  │  └─ repository/  # 按领域职责拆分的仓库实现包
│  │     ├─ AiProfileRepository.java  # 保存不含凭据的 AI 提供者资料
│  │     ├─ ApplicationSecretRepository.java  # 保存不可变应用秘密元数据与发布绑定，绝不保存秘密值
│  │     ├─ ConfigurationSnapshotRepository.java  # 保存不可变的普通配置快照与条目
│  │     ├─ DesktopPreferenceRepository.java  # 保存小型非秘密桌面偏好
│  │     ├─ EncryptedSecretRepository.java  # 仅保存加密的不透明秘密载荷
│  │     ├─ ManagedApplicationGraphRepository.java  # 将持久整应用拓扑与成功组件版本原子保存
│  │     ├─ ManagedApplicationRepository.java  # 保存受管应用、运行契约、发布和生命周期观测
│  │     ├─ RepositoryTransactionExecutor.java  # 由聚焦仓库使用的共享事务原语
│  │     └─ ServerProfileRepository.java  # 保存服务器信任身份与不含凭据的连接资料
│  ├─ main/  # 桌面应用入口与模块装配模块
│  │  ├─ AppMain.java  # WindowsToLinux 桌面应用入口
│  │  ├─ pom.xml  # 配置桌面应用唯一入口与模块装配模块的依赖和构建
│  │  ├─ runtime/  # 本地运行布局与路径解析包
│  │  │  ├─ RunModeResolver.java  # 为受支持的开发、JAR 和 jpackage 布局解析应用主目录及其固定 data 目录
│  │  │  └─ RuntimePathResolver.java  # 定位并验证受支持的开发、JAR 与 jpackage 文件系统布局
│  │  └─ startup/  # 桌面启动与窗口装配包
│  │     ├─ DesktopMain.java  # 使用受管部署要求固定数据目录的生产桌面引导程序
│  │     └─ DesktopWindowController.java  # 持有一个桌面窗口，并在区域设置或主题变化时安全替换它
│  ├─ pom.xml  # 聚合 Windows 桌面应用各叶子模块
│  ├─ secret/  # 桌面秘密存储模块
│  │  ├─ Argon2AesSecretStore.java  # 使用加密包实现 Argon2id 与 AES-GCM 的数据库秘密存储
│  │  ├─ crypto/  # 密钥派生与认证加密包
│  │  │  └─ Argon2AesGcmCryptoService.java  # 使用 Argon2id 派生密钥并以 AES-GCM 加解密秘密载荷
│  │  ├─ pom.xml  # 配置桌面秘密存储与加密模块的依赖和构建
│  │  ├─ SecretStore.java  # 定义秘密写入、读取、删除与调用方清零责任的平台凭据边界
│  │  ├─ SecretStoreException.java  # 敏感存储失败被刻意设计为不附加明文输入
│  │  └─ WindowsCredentialManagerSecretStore.java  # 通过固定 P/Invoke 调用实现的 Windows Credential Manager 适配器
│  ├─ service/  # 桌面应用用例编排模块
│  │  ├─ ai/  # AI 配置、角色与分析用例包
│  │  │  ├─ AiAnalysisOutcome.java  # 适合界面使用的可选 AI 解释操作结果
│  │  │  ├─ AiProfile.java  # 为可选结构解释保存的非秘密 OpenAI 兼容元数据
│  │  │  ├─ AiProviderProfile.java  # 一个显式选择的命名 AI 提供者；请求绝不回退到另一个提供者
│  │  │  ├─ AiRoleAssignment.java  # 从一个固定 AI 角色到一个命名提供者的类型化非秘密映射
│  │  │  ├─ AiUseCaseFacade.java  # 编排 AI Provider、角色绑定、结构化分析与解释用例
│  │  │  └─ ReadOnlyDeploymentAgentFacade.java  # 完整的可选 Agent 工具表面：仅有有界静态分析和确定性计划渲染
│  │  ├─ config/  # 部署配置与秘密修订用例包
│  │  │  └─ DeploymentConfigurationUseCase.java  # 协调不可变的部署配置和平台秘密引用，且不返回秘密值
│  │  ├─ contract/  # 桌面页面依赖的五个窄应用门面包
│  │  │  ├─ AiApplicationFacade.java  # AI 页面所需的窄应用操作
│  │  │  ├─ DeploymentApplicationFacade.java  # 单组件部署所需的窄应用操作
│  │  │  ├─ ManagedApplicationFacade.java  # 受管应用页面所需的窄应用操作
│  │  │  ├─ MultiComponentApplicationFacade.java  # 整应用部署与生命周期所需的窄应用操作
│  │  │  └─ ServerApplicationFacade.java  # 服务器管理所需的窄应用操作
│  │  ├─ deployment/  # 部署流程与受管身份包
│  │  │  ├─ ManagedApplicationIdentityResolver.java  # 解析一个规范桌面受管身份，不包含部署专属行为
│  │  │  ├─ multi/  # 多组件审阅与应用拓扑契约包
│  │  │  │  ├─ ManagedMultiComponentApplication.java  # 桌面应用重启后使用的持久且不含秘密的应用拓扑
│  │  │  │  ├─ MultiComponentReviewInput.java  # 一个静态准入组件由用户审阅的可变输入
│  │  │  │  ├─ ReviewedComponentApplication.java  # 一个组件不含秘密的经审阅部署请求与稳定身份
│  │  │  │  └─ ReviewedMultiComponentApplication.java  # 一次整应用事务的完整无秘密审阅对象
│  │  │  ├─ MultiComponentDeploymentUseCase.java  # 负责经审阅整应用部署与生命周期的桌面产品边界
│  │  │  ├─ MultiComponentLifecycleUseCase.java  # 负责持久多组件应用恢复与生命周期操作
│  │  │  ├─ ReviewedDeploymentUseCase.java  # 将成功的经审阅部署持久化到与现有部署相同的受管应用清单中
│  │  │  └─ single/  # 单组件部署结果与交接契约包
│  │  │     ├─ DeploymentHandoff.java  # 仅在部署成功后可用的无秘密结构化后续步骤
│  │  │     └─ DeploymentOutcome.java  # 由桌面界面呈现的无秘密部署摘要
│  │  ├─ DesktopApplicationFacade.java  # 统一暴露桌面端服务器、源码、部署、生命周期和 AI 用例门面
│  │  ├─ environment/  # 目标主机环境准备用例包
│  │  │  └─ EnvironmentSetupUseCase.java  # 编排目标主机环境检查、审阅与受管准备流程
│  │  ├─ lifecycle/  # 受管应用生命周期用例包
│  │  │  ├─ LifecycleOutcome.java  # 单次桌面生命周期用例的无秘密结果
│  │  │  ├─ LifecycleUseCase.java  # 编排受管应用的实时观测、生命周期和自启操作
│  │  │  └─ ManagedApplicationSnapshot.java  # 已持久化的资源归属、成功部署契约和发布身份；并非远端运行时状态声明
│  │  ├─ lock/  # 同服务器操作互斥注册包
│  │  │  └─ ServerOperationLockRegistry.java  # 按服务器串行化互斥操作并支持后台任务取消
│  │  ├─ pom.xml  # 配置桌面业务流程编排模块的依赖和构建
│  │  ├─ server/  # 服务器资料、信任与能力用例包
│  │  │  ├─ DesktopSecretStoreService.java  # 按平台能力选择 Windows 凭据或数据库秘密存储
│  │  │  ├─ ServerProfile.java  # 已保存的非秘密桌面连接资料
│  │  │  └─ ServerUseCaseFacade.java  # 编排服务器资料、主机信任、连接验证与能力探测
│  │  └─ source/  # 本地与 Git 源码准备用例包
│  │     ├─ PreparedComponentSource.java  # 一个混合项目组件的经审阅不可变源码输入
│  │     ├─ PreparedMultiComponentSource.java  # 整个混合应用的静态评估及独立不可变归档
│  │     ├─ ReviewedSourcePreparation.java  # 在计划经审阅部署前展示的类型化源码分析和安全归档结果
│  │     └─ SourcePreparationUseCase.java  # 将本地目录或固定 Git 来源统一准备为经审阅源码
│  ├─ ui/  # Swing 桌面界面模块
│  │  ├─ ai/  # AI 配置与解释结果页面包
│  │  │  ├─ AiPage.java  # 持有可选 AI 表单、临时秘密、状态与解释流程
│  │  │  └─ AiPageState.java  # 保存桌面外观重建期间尚未提交的 AI 页面状态
│  │  ├─ component/  # 可复用桌面组件包
│  │  │  ├─ DesktopComponentFactory.java  # 创建桌面页面复用的按钮、表单和布局组件
│  │  │  └─ DesktopTaskExecutor.java  # 运行后台操作并将完成结果返回 Swing 事件线程
│  │  ├─ deployment/  # 部署审阅上下文与输入解析包
│  │  │  ├─ DeploymentConfigurationParser.java  # 解析桌面端有界构建/运行配置记法
│  │  │  ├─ DeploymentRuntimeParser.java  # 解析桌面端有界运行时、秘密引用与 Git 引用记法
│  │  │  ├─ multi/  # 多组件编辑、页面与结果呈现包
│  │  │  │  ├─ MultiComponentDraft.java  # 一个显式审阅组件不含秘密值的类型化表单状态
│  │  │  │  ├─ MultiComponentDraftController.java  # 持有多组件控件、草稿状态、选择与领域输入映射
│  │  │  │  ├─ MultiComponentFormState.java  # 桌面外观重建期间保留的未保存编辑值
│  │  │  │  ├─ MultiComponentHealthMode.java  # 多组件表单支持的类型化健康模式
│  │  │  │  ├─ MultiComponentPage.java  # 用于显式混合项目审阅、整应用部署和生命周期的桌面产品页面
│  │  │  │  ├─ MultiComponentPageState.java  # 完整且不含秘密的多组件页面状态
│  │  │  │  └─ MultiComponentResultPresenter.java  # 为桌面输出区格式化有界多组件审阅与结果证据
│  │  │  ├─ ReviewContext.java  # 可选 AI 页面使用的窄当前审阅视图
│  │  │  └─ single/  # 单组件表单、页面与分析呈现包
│  │  │     ├─ DeploymentAnalysisPresenter.java  # 格式化本地化语言、证据、冲突、缺失输入和拒绝摘要
│  │  │     ├─ DeploymentForm.java  # 持有部署控件、非秘密表单状态与领域输入映射
│  │  │     ├─ DeploymentPage.java  # 持有源码选择、审阅状态、部署表单与完整经审阅部署流程
│  │  │     └─ DeploymentPageState.java  # 保存单组件部署页尚未提交的运行、健康、配置和源码审阅状态
│  │  ├─ display/  # 主题、外观与区域设置包
│  │  │  ├─ DesktopDisplayConfiguration.java  # 在桌面设置页面选择的可持久化显示偏好
│  │  │  ├─ DesktopThemeService.java  # 受管部署 Swing 桌面客户端共用的 FlatLaf 设置
│  │  │  ├─ SystemThemeResolver.java  # 读取 Windows 应用外观偏好，但不写入系统注册表
│  │  │  ├─ ThemeMode.java  # 用户可选择的桌面外观偏好
│  │  │  └─ ThemePalette.java  # 单个生效浅色或深色外观使用的不可变应用颜色
│  │  ├─ i18n/  # 界面消息目录访问与格式化包
│  │  │  ├─ MessageCatalog.java  # 从区域设置资源包解析应用消息键和非递归命名占位符
│  │  │  └─ PageMessagePresenter.java  # 独立页面控制器共享的本地化与诊断格式化
│  │  ├─ managed/  # 受管应用清单与生命周期页面包
│  │  │  ├─ ManagedPage.java  # 持有受管应用清单、选择与生命周期流程
│  │  │  └─ ManagedPageState.java  # 保存受管应用页当前选择的应用标识与输出内容
│  │  ├─ pom.xml  # 配置 Swing 桌面界面模块的依赖、资源和构建
│  │  ├─ server/  # 服务器配置与能力验证页面包
│  │  │  ├─ ServerContext.java  # 部署与生命周期页面使用的窄服务器上下文
│  │  │  ├─ ServerPage.java  # 持有服务器表单、内存凭据状态与服务器流程
│  │  │  └─ ServerPageState.java  # 保存桌面外观重建期间尚未提交的服务器表单状态
│  │  ├─ setting/  # 桌面设置页面与状态包
│  │  │  ├─ SettingPage.java  # 持有外观控件与即时应用流程
│  │  │  └─ SettingPageState.java  # 设置立即生效，因此没有需要保留的未保存页面局部值
│  │  ├─ shell/  # 主窗口、导航与页面装配包
│  │  │  ├─ DesktopDisplayChangeHandler.java  # 保存选定外观，并在事件线程上替换活动桌面窗口
│  │  │  ├─ DesktopFrame.java  # 只负责导航、页面装配和窗口生命周期的窗口外壳
│  │  │  ├─ DesktopPageCoordinator.java  # 装配页面控制器、窄页面上下文、导航及整个窗口状态
│  │  │  ├─ DesktopViewState.java  # 在为外观热更新重建外壳时聚合各页面持有的状态
│  │  │  └─ PageNavigationController.java  # 在不向页面公开 Swing 组件的情况下导航外壳
│  │  └─ resources/  # 桌面界面生产资源目录
│  │     └─ i18n/  # 桌面本地化资源目录
│  │        └─ messages/  # 英文与简体中文消息目录
│  │           ├─ Messages.properties  # 提供桌面界面的英文基准消息目录
│  │           └─ Messages_zh_CN.properties  # 提供与英文消息键一致的简体中文界面映射
│  └─ windows/  # Windows 平台边界模块
│     ├─ pom.xml  # 配置 Windows 平台文件与工作区边界模块的依赖和构建
│     └─ workspace/  # 本地源码工作区与归档边界包
│        ├─ PreparedSourceArchive.java  # 保存已准备源码归档的描述信息与排除条目清单
│        └─ WindowsSourcePreparer.java  # 准备平台无关源码归档的 Windows 桌面入口
├─ shared/  # 平台无关共享模块分组
│  ├─ ai/  # AI 调用、协作与脱敏模块
│  │  ├─ AiAnalysisException.java  # 可选 AI 解释路径产生的安全、非秘密失败
│  │  ├─ AiStructuralAssessment.java  # 可选解释文本；它绝不改变确定性的项目支持判断或部署决策
│  │  ├─ client/  # Provider HTTP 客户端与传输契约包
│  │  │  ├─ OpenAiCompatibleRoleClient.java  # 仅调用一个已配置提供者并只保留已验证的不含凭据证据
│  │  │  └─ OpenAiCompatibleStructuralAnalysisClient.java  # 仅使用已脱敏的确定性事实调用 OpenAI 兼容端点
│  │  ├─ collaboration/  # 确定性优先的 AI 协调包
│  │  │  ├─ advice/  # 有界建议决策与输出包
│  │  │  │  ├─ AiAdviceDecision.java  # 绝不授权执行的有界建议决策
│  │  │  │  └─ RoleAdviceAssessment.java  # 来自一个固定角色的已验证有界建议输出
│  │  │  ├─ AiCollaborationDecision.java  # 保留确定性权威及全部调用记录的协调决策
│  │  │  ├─ AiDecisionCoordinator.java  # 协调可选模型建议且不允许其授予执行权限
│  │  │  ├─ CollaborationDisposition.java  # 最终协作处置；模型建议绝不创建执行许可
│  │  │  ├─ DeterministicDecision.java  # 在可选模型复核前提供的权威确定性决策
│  │  │  ├─ invocation/  # AI 调用状态、证据与结果包
│  │  │  │  ├─ AiInvocationEvidence.java  # 一次显式选择角色调用的不含凭据证据
│  │  │  │  ├─ AiInvocationStatus.java  # 对一个显式选择提供者调用的验证状态
│  │  │  │  └─ AiRoleInvocationResult.java  # 一个角色恰好一次提供者调用的结果
│  │  │  └─ role/  # 固定角色、绑定与脱敏上下文包
│  │  │     ├─ AiCollaborationRoleKind.java  # 具有独立提供者选择和上下文边界的固定 AI 协作角色类别
│  │  │     ├─ AiRoleBinding.java  # 从一个固定角色到唯一命名提供者和模型的非秘密绑定
│  │  │     ├─ AiRoleContext.java  # 一个协作角色可接受的封闭最小脱敏事实
│  │  │     ├─ DeploymentRiskRoleContext.java  # 不含 SSH 数据、源码内容、配置值或秘密的最小经审阅计划事实
│  │  │     ├─ ErrorExplanationRoleContext.java  # 在保留或传输前移除类似凭据文本的最小受控失败
│  │  │     └─ ProjectAnalysisRoleContext.java  # 不含源码路径、内容、配置值或秘密的最小项目事实
│  │  ├─ parser/  # 结构化 AI 响应解析包
│  │  │  ├─ ChatCompletionResponseParser.java  # 解析单个有界 Chat Completions 响应，不接受任意 JSON 结构
│  │  │  └─ RoleAdviceParser.java  # 严格解析固定建议 JSON 对象并拒绝额外字段
│  │  ├─ pom.xml  # 配置 AI Provider、角色协作、脱敏和解析模块的依赖与构建
│  │  ├─ prompt/  # 固定角色提示构建包
│  │  │  ├─ AiResponseLanguageType.java  # 可选 AI 生成解释所支持的语言类型
│  │  │  ├─ RolePrompt.java  # 构建响应必须匹配唯一 JSON 模式的固定角色请求
│  │  │  └─ StructuralAnalysisPrompt.java  # 根据已经脱敏的事实构建固定结构分析请求
│  │  ├─ provider/  # AI Provider 配置与协议包
│  │  │  └─ ProviderEndpointPolicy.java  # 在创建任何请求之前验证 Provider 端点和模型
│  │  ├─ redaction/  # 敏感信息识别与最小化脱敏包
│  │  │  └─ RedactedDeploymentProjectFacts.java  # 允许离开确定性分析边界的最小类型化部署事实
│  │  └─ transport/  # 角色聊天传输契约、HTTP 实现与结果包
│  │     ├─ HttpRoleChatTransport.java  # 精确所选 OpenAI 兼容端点的 JDK HTTP 传输
│  │     ├─ RoleChatResult.java  # 所选提供者传输返回的有界 HTTP 响应
│  │     └─ RoleChatTransport.java  # 单个已选提供者的传输；不提供发现或回退 API
│  ├─ analyze/  # 源码与部署条件静态分析模块
│  │  ├─ component/  # 多组件项目发现与依赖图分析包
│  │  │  ├─ ComponentAnalysisRequest.java  # 提供给确定性混合项目分析的用户审阅组件边界
│  │  │  ├─ ComponentGraphValidator.java  # 验证跨组件所有权、依赖、端口、制品及数据路径约束
│  │  │  └─ MixedProjectInspector.java  # 分析显式组件根，并在修改目标机前拒绝跨组件矛盾
│  │  ├─ core/  # 部署分析协调与语言汇总包
│  │  │  ├─ DeploymentAnalysisCoordinator.java  # 仅协调有界遍历、安全策略、类型分派与结果汇总
│  │  │  └─ ProjectLanguageInspector.java  # 合并独立语言检查器，不进行排序或选择项目类型
│  │  ├─ ecosystem/  # 按技术生态组织的分析包
│  │  │  ├─ dotnet/  # .NET 语言生态分析包
│  │  │  │  └─ dotnetsdk/  # .NET SDK 架构分析包
│  │  │  │     ├─ DotNetSdkDeploymentInspector.java  # 在不执行 dotnet 的情况下检查一个锁定的 .NET Web SDK 服务
│  │  │  │     └─ DotNetSdkFacts.java  # 服务分析使用的固定 .NET SDK 项目元数据
│  │  │  ├─ go/  # Go 语言生态分析包
│  │  │  │  └─ gomodule/  # Go Module 架构分析包
│  │  │  │     ├─ GoModuleDeploymentInspector.java  # 在不执行 Go 的情况下检查一个锁定的 Go Module 服务
│  │  │  │     └─ GoModuleFacts.java  # 服务分析使用的固定 Go Module 元数据
│  │  │  ├─ java/  # Java 语言与部署类型分析包
│  │  │  │  ├─ gradle/  # Gradle 构建事实分析包
│  │  │  │  │  ├─ GradleBuildFacts.java  # 固定的 Gradle 构建脚本与 Wrapper 事实
│  │  │  │  │  └─ GradleBuildInspector.java  # 读取一个固定 Gradle 构建脚本与 Wrapper 布局，但不调用 Gradle
│  │  │  │  ├─ jar/  # Java JAR 原生交付架构分析包
│  │  │  │  │  └─ JavaJarDeploymentInspector.java  # 在不加载归档的情况下生成 Java JAR 事实与清单依据运行时建议
│  │  │  │  ├─ JavaLanguageInspector.java  # 在不加载类的情况下检测 Java 源码与有界 JAR 清单事实
│  │  │  │  ├─ maven/  # Maven 构建事实分析包
│  │  │  │  │  ├─ MavenBuildFacts.java  # 框架及部署支持检查所需的根 Maven 事实
│  │  │  │  │  └─ MavenBuildInspector.java  # 读取根 POM 和 Maven 入口，但不调用 Maven 或其 Wrapper
│  │  │  │  └─ SpringBootDeploymentInspector.java  # 在不调用构建的前提下检查一个 Maven 或 Gradle Spring Boot 可执行 JAR 项目
│  │  │  ├─ kotlin/  # Kotlin 语言生态分析包
│  │  │  │  └─ gradle/  # Kotlin Gradle 架构分析包
│  │  │  │     ├─ KotlinGradleDeploymentInspector.java  # 在不执行 Gradle 的情况下检查一个锁定的 Kotlin Gradle 应用
│  │  │  │     └─ KotlinGradleFacts.java  # 服务分析使用的固定 Kotlin Gradle 应用元数据
│  │  │  ├─ node/  # Node.js 服务分析包
│  │  │  │  ├─ NodeBuildArchitectureFacts.java  # 选中的 Node 包管理器架构及其锁文件
│  │  │  │  ├─ NodeBuildFacts.java  # 来自 package.json 的固定包管理器、锁文件和脚本事实
│  │  │  │  ├─ NodeBuildInspector.java  # 读取 package.json、受支持锁文件和固定脚本名称，但不调用包管理器
│  │  │  │  ├─ NodeLanguageInspector.java  # 从路径与包元数据检测 Node.js、JavaScript 和 TypeScript 事实
│  │  │  │  ├─ NodeServiceDeploymentInspector.java  # 生成 Node 服务构建事实与精确版本运行时建议
│  │  │  │  ├─ npm/  # npm 架构分析包
│  │  │  │  │  └─ NpmBuildInspector.java  # 检查 npm package-lock 架构
│  │  │  │  ├─ pnpm/  # pnpm 架构分析包
│  │  │  │  │  └─ PnpmBuildInspector.java  # 检查 pnpm 锁文件架构
│  │  │  │  └─ yarn/  # Yarn 架构分析包
│  │  │  │     └─ YarnBuildInspector.java  # 检查 Yarn 锁文件架构
│  │  │  ├─ php/  # PHP 语言生态分析包
│  │  │  │  └─ composer/  # Composer 架构分析包
│  │  │  │     ├─ PhpComposerDeploymentInspector.java  # 在不执行 PHP 的情况下检查一个 Composer 锁定的 PHP 服务
│  │  │  │     └─ PhpComposerFacts.java  # PHP 服务分析使用的固定 Composer 元数据
│  │  │  ├─ python/  # Python 服务分析包
│  │  │  │  ├─ pip/  # pip 架构分析包
│  │  │  │  │  └─ PipBuildInspector.java  # 检查使用哈希锁定的 pip 架构
│  │  │  │  ├─ pipenv/  # Pipenv 架构分析包
│  │  │  │  │  └─ PipenvBuildInspector.java  # 检查 Pipenv 锁文件架构
│  │  │  │  ├─ poetry/  # Poetry 架构分析包
│  │  │  │  │  └─ PoetryBuildInspector.java  # 检查 Poetry 锁文件架构
│  │  │  │  ├─ PythonBuildFacts.java  # 固定的 Python 项目与锁文件事实
│  │  │  │  ├─ PythonBuildInspector.java  # 读取 pyproject.toml 与固定锁文件名称，但不调用 Python
│  │  │  │  ├─ PythonLanguageInspector.java  # 检测 Python 源码、精确版本元数据和唯一模块入口
│  │  │  │  ├─ PythonServiceDeploymentInspector.java  # 生成 Python 构建事实与精确版本/模块运行时建议
│  │  │  │  └─ uv/  # uv 架构分析包
│  │  │  │     └─ UvBuildInspector.java  # 检查 uv 锁文件架构
│  │  │  ├─ ruby/  # Ruby 语言生态分析包
│  │  │  │  └─ bundler/  # Bundler 架构分析包
│  │  │  │     ├─ RubyBundlerDeploymentInspector.java  # 在不执行 Ruby 的情况下检查一个 Bundler 锁定的 Rack 服务
│  │  │  │     └─ RubyBundlerFacts.java  # Ruby 服务分析使用的固定 Bundler 元数据
│  │  │  └─ rust/  # Rust 语言生态分析包
│  │  │     └─ cargo/  # Cargo 架构分析包
│  │  │        ├─ RustCargoDeploymentInspector.java  # 在不执行 Rust 工具的情况下检查一个锁定的 Cargo 服务
│  │  │        └─ RustCargoFacts.java  # Rust 服务分析使用的固定 Cargo 元数据
│  │  ├─ policy/  # 静态分析边界与拒绝规则包
│  │  │  └─ SourceMutationPolicy.java  # 拒绝可能自动修改受管数据库的源码声明
│  │  ├─ pom.xml  # 配置源码生态、构建和部署条件分析模块的依赖与构建
│  │  ├─ preview/  # 不可执行的语言识别预览包
│  │  │  ├─ PreviewInspector.java  # 生成无法进入归档准备或部署的静态识别结果
│  │  │  └─ PreviewLanguageMarkerCatalog.java  # 通过有界路径与固定元数据名称收集仍可用于识别的声明式语言标记，不求值其内容
│  │  ├─ registry/  # 部署类型检查器注册包
│  │  │  └─ DeploymentTypeInspectorRegistry.java  # 持有完整且已验证的源码类型检查器装配
│  │  ├─ service/  # 语言无关的服务事实组装包
│  │  │  ├─ ServiceInspectionAssembler.java  # 组装语言无关的服务事实与运行时建议
│  │  │  ├─ ServiceMetadataInspector.java  # 不包含语言特定规则的公共有界元数据操作
│  │  │  └─ ServiceProjectFacts.java  # 单个语言检查器返回的公共有界服务元数据
│  │  ├─ source/  # 受限源码树读取与元数据包
│  │  │  ├─ BoundedMetadataInspector.java  # 只在静态检查边界内读取固定项目元数据
│  │  │  ├─ BoundedSourceInspector.java  # 对源码树执行有界只读安全遍历
│  │  │  ├─ ProjectIdentityResolver.java  # 从经审阅元数据或选定根目录推导有界受管项目标识
│  │  │  └─ SourceInspectionFacts.java  # 在不执行任何项目可控内容的前提下采集的有界事实
│  │  ├─ spi/  # 部署类型分析扩展契约包
│  │  │  ├─ DeploymentTypeAssessment.java  # 由一个类型检查器返回的局部事实与运行时建议
│  │  │  └─ DeploymentTypeInspector.java  # 面向一个用户选定部署类型的单一有界检查器
│  │  └─ workload/  # 容器与静态站点工作负载分析包
│  │     ├─ ContainerDeploymentInspector.java  # 检测单一 Dockerfile 工作负载、声明端口与受管卷候选项
│  │     └─ StaticWebDeploymentInspector.java  # 区分纯静态内容与由锁文件支持的 Node 静态构建
│  ├─ backup/  # 共享备份职责预留模块
│  │  └─ pom.xml  # 保留共享备份职责边界的 POM-only 模块
│  ├─ config/  # 类型化配置定义与校验模块
│  │  ├─ definition/  # 配置项定义、类型与校验规则包
│  │  │  ├─ ConfigurationScope.java  # 非秘密配置值被使用的受限时点
│  │  │  └─ ConfigurationValue.java  # 类型化的非秘密配置值，不能携带 Shell 片段
│  │  ├─ pom.xml  # 配置类型化配置定义与校验模块的依赖和构建
│  │  ├─ revision/  # 不可变配置快照与修订包
│  │  │  ├─ ConfigurationEntry.java  # 不可变配置快照中的一个经过类型检查的非秘密值
│  │  │  ├─ ConfigurationSnapshot.java  # 一个已发布应用版本的不可变、以摘要寻址的普通配置
│  │  │  └─ DeploymentInputManifest.java  # 将一个发布绑定到不可变配置和精确秘密修订的非秘密清单
│  │  └─ secretref/  # 秘密引用与已解析修订包
│  │     ├─ ResolvedSecretRevision.java  # 仅用于已认证部署传输的短生命周期已解析秘密字节
│  │     ├─ SecretReference.java  # 一个指向不可变应用秘密修订的透明引用；它绝不包含秘密值
│  │     └─ SecretRevisionDigest.java  # 一个已解析秘密修订的公开完整性元数据；它绝不包含秘密值
│  ├─ deploy/  # 部署计划、事务与生命周期模块
│  │  ├─ adapter/  # 项目类型到部署执行器的适配包
│  │  │  ├─ ContainerAdapter.java  # 在策略验证后计划一个 Dockerfile 镜像和一个受管 Docker 或 Podman 容器
│  │  │  ├─ DeploymentPlanFactory.java  # 类型化部署适配器的内部共用顺序
│  │  │  ├─ ServiceDeploymentAdapter.java  # 计划一个由 Profile 选定的普通 systemd 服务，不接受命令字符串
│  │  │  ├─ ServiceDeploymentProfile.java  # 一个普通 systemd 服务项目类型的不可变 Profile
│  │  │  └─ StaticSiteAdapter.java  # 只在验证声明的生成输出目录后计划静态站点发布
│  │  ├─ contract/  # 部署、健康与生命周期公共契约包
│  │  │  ├─ ApplicationHealthGate.java  # 由一个经审阅组件端点承载的整体应用健康探测
│  │  │  ├─ DeploymentApproval.java  # 按源码、服务器和应用分别绑定的用户批准，绝不可复用
│  │  │  ├─ DeploymentPlanAction.java  # 部署短停机事务的可审计固定计划动作
│  │  │  ├─ MultiComponentDeploymentPlan.java  # 一个经审阅组件图的确定性构建与运行顺序
│  │  │  ├─ ReviewedDeploymentPlan.java  # 完全确定性的部署事务计划；不包含传输实现或原始命令
│  │  │  └─ ReviewedDeploymentRequest.java  # 经过完整审阅的部署输入，包含身份和类型化定义，绝不包含 Shell 命令
│  │  ├─ environment/  # 目标环境准备服务包
│  │  │  └─ EnvironmentSetupService.java  # 运行一个经过显式批准的环境准备操作
│  │  ├─ lifecycle/  # 多组件生命周期策略与执行包
│  │  │  ├─ ManagedComponentLifecycle.java  # 单个组件生命周期操作所需的不含秘密的受管身份与运行时
│  │  │  ├─ ManagedLifecycleService.java  # 仅在实时验证资源归属后应用生命周期动作
│  │  │  ├─ MultiComponentLifecyclePolicy.java  # 应用纯目标、依赖影响、最终状态与汇总规则
│  │  │  └─ MultiComponentLifecycleService.java  # 根据权威远端观测执行依赖安全的应用生命周期动作
│  │  ├─ plan/  # 经审阅部署计划构建包
│  │  │  ├─ MultiComponentDeploymentPlanner.java  # 仅在混合项目通过安全准入后生成确定性图顺序
│  │  │  ├─ ReviewedDeploymentPlanner.java  # 将经审阅的请求路由到唯一能够计划其选定单组件项目类型的适配器
│  │  │  └─ ReviewedReleaseIdentityResolver.java  # 从可改变构建或运行发布的每项输入推导不可变发布身份
│  │  ├─ pom.xml  # 配置部署计划、兼容性、事务和生命周期模块的依赖与构建
│  │  ├─ registry/  # 部署适配器注册与选择包
│  │  │  └─ DeploymentAdapterRegistry.java  # 持有完整且已验证的部署适配器装配
│  │  ├─ result/  # 部署与生命周期结果分组包
│  │  │  ├─ compatibility/  # 目标主机兼容性结果包
│  │  │  │  ├─ HostSupportDecision.java  # 带有有序审阅证据的保守主机支持决定
│  │  │  │  └─ HostSupportStatus.java  # 将实时主机证据与部署矩阵匹配后的保守状态
│  │  │  ├─ deployment/  # 单组件与多组件部署结果包
│  │  │  │  ├─ ComponentDeploymentResult.java  # 组件范围的部署证据与终态
│  │  │  │  ├─ ComponentTransactionState.java  # 应用事务中一个组件的终态
│  │  │  │  ├─ DeploymentEvent.java  # 适用于界面和持久化历史的简短、无秘密跟踪条目
│  │  │  │  ├─ DeploymentResult.java  # 部署终态结果，包括独立的回滚结果
│  │  │  │  └─ MultiComponentDeploymentResult.java  # 始终保留每个组件结果的应用级事务结果
│  │  │  └─ lifecycle/  # 组件与整应用生命周期结果包
│  │  │     ├─ ComponentLifecycleResult.java  # 一次应用生命周期请求中的组件实时结果
│  │  │     ├─ LifecycleActionResult.java  # 单资源生命周期操作结果
│  │  │     └─ MultiComponentLifecycleResult.java  # 保留每个组件的权威应用生命周期结果
│  │  ├─ spi/  # 部署适配器扩展契约包
│  │  │  └─ DeploymentAdapter.java  # 为一个受支持的部署单组件项目类型生成一个确定性计划
│  │  ├─ support/  # 目标支持判定协调包
│  │  │  ├─ distro/  # 发行版兼容策略包
│  │  │  │  ├─ AlmaLinuxSupportPolicy.java  # 包含明确 x86-64-v2 变体边界的 AlmaLinux 兼容性策略
│  │  │  │  ├─ CentosStreamSupportPolicy.java  # CentOS Stream 兼容性策略
│  │  │  │  ├─ DebianSupportPolicy.java  # Debian 稳定版兼容性策略
│  │  │  │  ├─ DistributionSupportEvaluator.java  # 将已分类发行版路由到恰好一个独立策略
│  │  │  │  ├─ DistributionSupportPolicy.java  # 单个发行版可独立审阅的兼容性边界
│  │  │  │  ├─ DistributionSupportRules.java  # 独立发行版策略机械共享的固定检查
│  │  │  │  ├─ OracleLinuxSupportPolicy.java  # Oracle Linux 滚动主版本兼容性策略
│  │  │  │  ├─ RockyLinuxSupportPolicy.java  # 固定到受维护小版本的 Rocky Linux 兼容性策略
│  │  │  │  └─ UbuntuSupportPolicy.java  # Ubuntu LTS 兼容性策略
│  │  │  ├─ HostSupportEvaluator.java  # 协调对已采集主机事实的公共、发行版与运行时支持检查
│  │  │  └─ runtime/  # 语言运行时能力检查包
│  │  │     ├─ RuntimeCapabilityDecision.java  # 表示一个已审阅运行时是否匹配采集到的主机能力
│  │  │     └─ RuntimeCapabilityEvaluator.java  # 将一个类型化运行时与已采集的主机工具能力进行匹配
│  │  └─ transaction/  # 部署事务、恢复与回滚协调包
│  │     ├─ MultiComponentRecoveryCoordinator.java  # 协调重连、回滚、候选清理与恢复状态
│  │     ├─ MultiComponentTransactionContext.java  # 持有一次经审阅事务中单个组件的可变状态
│  │     ├─ ReviewedComponentDeployment.java  # 一个应用组件的经审阅输入与稳定受管身份
│  │     ├─ ReviewedDeploymentService.java  # 通过与所有受管应用相同的有界事务语义执行一个经审阅、类型专属的部署
│  │     └─ ReviewedMultiComponentDeploymentService.java  # 通过单个已验证类型化会话执行一个整体应用事务
│  ├─ git/  # 受约束 Git 来源与快照模块
│  │  ├─ GitReference.java  # 用户选择的可变或不可变 Git 引用；分析前会将其解析为 Commit
│  │  ├─ GitRemote.java  # 解析后的 Git 远端；其位置绝不嵌入凭据
│  │  ├─ GitSnapshot.java  # 固定到一个 Commit 并配有确定性源码归档的检出结果
│  │  ├─ GitSnapshotException.java  # 安全的 Git 快照失败；其消息刻意不含远端或凭据材料
│  │  ├─ GitSourceRequest.java  # 只读 Git 分析快照的有界输入；凭据始终位于此值之外
│  │  ├─ pom.xml  # 配置受约束 Git 来源检查与快照模块的依赖和构建
│  │  └─ snapshot/  # 固定提交检出与安全归档包
│  │     ├─ ControlledGitWorkspaceValidator.java  # 仅验证并创建平台拥有的 Git 工作区边界
│  │     ├─ GitCommandExecutor.java  # 以禁用 Hook 和 LFS 物化的方式运行有界非交互 Git 命令
│  │     ├─ GitRepositoryFeaturePolicy.java  # 在存在显式有界物化策略前拒绝 Submodule、LFS 与符号链接条目
│  │     └─ GitSnapshotPreparer.java  # 协调克隆、固定检出、仓库策略校验和安全源码归档
│  ├─ linux/  # Linux 远程操作公共契约模块
│  │  ├─ build/  # 远端构建输入与结果契约包
│  │  │  └─ DeploymentBuildResult.java  # 在发布类型化运行时之前，有界部署源码构建的已验证结果
│  │  ├─ capability/  # 主机与工具链能力契约包
│  │  │  ├─ LinuxCapabilityCollector.java  # 类型化目标主机能力采集契约
│  │  │  └─ LinuxPlatformCapabilityCollector.java  # 用于部署发行版和容器矩阵的只读能力采集
│  │  ├─ connection/  # SSH 连接资料与网关契约包
│  │  │  ├─ DeploymentLinuxGateway.java  # 打开一个支持有界部署协议的已验证 SSH 会话
│  │  │  ├─ HostKeyDecision.java  # 由应用而非 SSH 适配器决定是否信任主机密钥
│  │  │  ├─ HostKeyEvaluator.java  # 在认证前调用，用于首次使用确认和阻止密钥变更
│  │  │  ├─ LinuxGateway.java  # 为完整部署或生命周期动作打开单个已验证 SSH 会话
│  │  │  ├─ SshCredential.java  # 由平台秘密模块提供的内存认证材料
│  │  │  └─ SshEndpoint.java  # 接受并持久化主机密钥之前的目标地址
│  │  ├─ distro/  # 发行版环境准备契约包
│  │  │  └─ LinuxEnvironmentPreparer.java  # 感知发行版的环境准备契约
│  │  ├─ error/  # 远端失败分类与诊断契约包
│  │  │  └─ LinuxOperationException.java  # 连接、指纹、协议或受控操作失败
│  │  ├─ pom.xml  # 配置 Linux 连接、命令和远程会话公共契约模块的依赖与构建
│  │  ├─ protocol/  # 受管 helper 协议契约包
│  │  │  ├─ ManagedHelperProtocol.java  # 由预检与 SSH 实现共享的稳定受管 helper 协议身份
│  │  │  ├─ ReleaseSnapshot.java  # 部署改变状态之前捕获的不透明远端回滚引用
│  │  │  └─ RemoteStepResult.java  # 具名固定 Linux 操作产生的已净化结果
│  │  ├─ runtime/  # 受管运行时观察与控制契约包
│  │  │  ├─ HealthCheckResult.java  # 完整受管部署健康检查策略的结果，而不只是进程检查
│  │  │  └─ LinuxRuntimeExecutor.java  # 受管运行时观测、健康检查和生命周期契约
│  │  ├─ session/  # 有界远端会话契约包
│  │  │  ├─ DeploymentRemoteSession.java  # 已验证受管部署会话的有界扩展，覆盖全部部署单组件项目类型
│  │  │  └─ LinuxRemoteSession.java  # 仅由有界、类型化 Linux 能力组成的单个已验证远程会话
│  │  └─ transfer/  # 源码与配置传输契约包
│  │     ├─ LinuxSourceTransport.java  # 受限源码传输与候选项清理契约
│  │     ├─ RemoteWorkspace.java  # 仅根据已验证应用 ID 和源码摘要推导的固定服务端路径
│  │     └─ SourceUploadResult.java  # 源码归档已到达固定候选工作区的确认结果
│  ├─ linux-sshd/  # Apache SSHD 与 Linux 运行适配模块
│  │  ├─ build/  # 目标主机构建协调包
│  │  │  ├─ DeploymentBuildExecutor.java  # 执行由实现渲染并受资源限制的目标机构建
│  │  │  ├─ ecosystem/  # 语言与构建架构的目标机构建实现包
│  │  │  │  ├─ BundlerBuildRenderer.java  # 渲染固定 Bundler 构建架构
│  │  │  │  ├─ CargoBuildRenderer.java  # 渲染固定 Cargo 构建架构
│  │  │  │  ├─ ComposerBuildRenderer.java  # 渲染固定 Composer 构建架构
│  │  │  │  ├─ DotNetSdkBuildRenderer.java  # 渲染固定 .NET SDK 构建架构
│  │  │  │  ├─ EcosystemBuildScript.java  # 共享单一架构生态渲染器使用的安全脚本机制
│  │  │  │  ├─ GoBuildRenderer.java  # 渲染固定 Go Module 构建架构
│  │  │  │  ├─ java/  # Java 多构建架构渲染包
│  │  │  │  │  ├─ JavaJarBuildRenderer.java  # 渲染经审阅的预构建 Java JAR 边界
│  │  │  │  │  └─ SpringBootBuildRenderer.java  # 渲染三个固定 Spring Boot 构建入口之一，并验证唯一受支持的可执行 JAR
│  │  │  │  ├─ KotlinGradleBuildRenderer.java  # 渲染固定 Kotlin Gradle 构建架构
│  │  │  │  ├─ node/  # Node.js 多包管理器构建渲染包
│  │  │  │  │  └─ NodeBuildRenderer.java  # 渲染固定锁文件的 Node 服务构建
│  │  │  │  └─ python/  # Python 多依赖架构构建渲染包
│  │  │  │     └─ PythonBuildRenderer.java  # 渲染固定锁文件的 Python 虚拟环境构建
│  │  │  ├─ registry/  # 构建渲染器装配与完整性检查包
│  │  │  │  └─ DeploymentBuildRendererRegistry.java  # 为每种支持的项目类型恰好注册一个构建渲染器
│  │  │  ├─ script/  # 安全构建脚本公共片段包
│  │  │  │  ├─ BuildConfigurationEnvironmentRenderer.java  # 将经审阅的非秘密构建值渲染为固定 Shell 导出
│  │  │  │  ├─ NodePackageBuildScript.java  # 渲染 Node 服务与构建型静态站点共享的包管理器部分
│  │  │  │  └─ SafeBuildScriptEnvelope.java  # 持有全部渲染器共享的归档校验、解压、限制、日志与构建工具证明
│  │  │  ├─ spi/  # 构建渲染器扩展契约包
│  │  │  │  └─ DeploymentBuildRenderer.java  # 通过固定且有界的目标机构建入口渲染一种经审阅项目类型
│  │  │  └─ workload/  # 容器与静态站点构建形态包
│  │  │     ├─ ContainerBuildRenderer.java  # 渲染固定 Dockerfile 容器镜像构建
│  │  │     └─ StaticSiteBuildRenderer.java  # 在不假设 Node 版本的情况下渲染纯静态或 Node 构建型站点产物
│  │  ├─ capability/  # 主机与生态能力只读采集包
│  │  │  ├─ ecosystem/  # 语言与构建工具链能力探测包
│  │  │  │  ├─ EcosystemCapabilityScriptRenderer.java  # 按生态能力配置生成固定检查脚本且保持证据顺序
│  │  │  │  └─ ManagedEcosystemCapabilityProbe.java  # 渲染只读语言与构建工具能力探测
│  │  │  ├─ ManagedHostCapabilityProbe.java  # 通过固定远端探测收集目标主机工具链与运行能力
│  │  │  ├─ ManagedPlatformCapabilityProbe.java  # 用于采集部署发行版、容器和 CPU 事实的固定只读 Shell 程序
│  │  │  ├─ SshdCapabilityCollector.java  # 使用 SSHD 会话只读收集发行版、CPU、安全和防火墙事实
│  │  │  └─ SshdPlatformCapabilityCollector.java  # 部署主机只读能力契约的 Apache SSHD 实现
│  │  ├─ command/  # 固定远端命令执行包
│  │  │  └─ SshCommandExecutor.java  # 仅通过已认证的 SSHD 会话执行由实现持有且预先渲染的命令
│  │  ├─ connection/  # Apache SSHD 网关与会话创建包
│  │  │  └─ SshdLinuxGateway.java  # 受管部署白名单远程契约的 Apache MINA SSHD 实现
│  │  ├─ distro/  # 发行版准备实现分组包
│  │  │  ├─ apt/  # APT 机械流程、包集合与 Debian 家族配置包
│  │  │  │  ├─ AptPackageSets.java  # 保存 APT 家族固定基础包与 Ubuntu 扩展包集合
│  │  │  │  ├─ AptSetupRenderer.java  # 独立 Ubuntu 与 Debian 适配器使用的固定 APT 准备机械流程
│  │  │  │  └─ DebianFamilySetupCatalog.java  # 持有 Debian 家族配置差异，同时共享 APT 机械流程
│  │  │  ├─ DistributionSetupRenderer.java  # 根据已采集事实渲染一个固定受支持发行版准备脚本
│  │  │  ├─ dnf/  # DNF 机械流程、包集合与企业 Linux 配置包
│  │  │  │  ├─ DnfPackageSets.java  # 按企业 Linux 主版本生成固定 DNF 包集合
│  │  │  │  ├─ DnfSetupRenderer.java  # 在不共享发行版身份规则的情况下复用的固定 DNF 准备机械流程
│  │  │  │  └─ EnterpriseLinuxSetupCatalog.java  # 持有企业 Linux 配置差异，同时共享 DNF 机械流程
│  │  │  ├─ ManagedEnvironmentExecutor.java  # 在只读主机探测后仅选择固定的受支持发行版环境准备脚本
│  │  │  ├─ profile/  # 不可变发行版与生态能力配置包
│  │  │  │  ├─ DistributionSetupProfile.java  # 单个发行版适配器持有的不可变、防注入事实
│  │  │  │  └─ EcosystemCapabilityProfile.java  # 保存发行版选择的生态能力配置
│  │  │  ├─ registry/  # 发行版准备目录与注册装配包
│  │  │  │  ├─ DistributionSetupCatalog.java  # 装配数据驱动的准备配置，但不持有包管理器机械流程
│  │  │  │  └─ DistributionSetupRegistry.java  # 持有完整且已验证的发行版准备实现装配
│  │  │  └─ script/  # 通用发行版准备脚本生成包
│  │  │     └─ SetupScriptRenderer.java  # 类型化准备渲染器共享的固定 Shell 片段
│  │  ├─ pom.xml  # 配置 Apache SSHD、受管 helper 和 Linux 运行适配模块的依赖与构建
│  │  ├─ protocol/  # 受管 helper 协议实现分组包
│  │  │  ├─ CandidateWorkspaceExecutor.java  # 仅通过固定 helper 动词控制候选工作区创建与清理
│  │  │  ├─ helper/  # helper 资源拼装与版本校验包
│  │  │  │  └─ ManagedHelperBundle.java  # 从固定职责片段拼装 root 持有的受管 helper 并拒绝协议漂移
│  │  │  ├─ input/  # 配置与秘密输入封存包
│  │  │  │  ├─ DeploymentConfigurationRenderer.java  # 为 systemd 与容器使用方渲染不可变运行时配置
│  │  │  │  ├─ DeploymentInputArguments.java  # 将非秘密输入清单转换为确定性辅助程序参数
│  │  │  │  └─ DeploymentInputProtocolExecutor.java  # 在发布前通过 root 所有的辅助程序流式传输并封存运行时输入
│  │  │  ├─ release/  # 候选发布、切换与回滚包
│  │  │  │  ├─ ContainerReleaseProtocolExecutor.java  # 应用彼此独立的 Docker 重启策略与 Podman Quadlet 发布协议
│  │  │  │  └─ DeploymentReleaseProtocolExecutor.java  # 使用 root 所有的辅助程序处理经审阅非容器发布的快照、发布和回滚
│  │  │  └─ runtime/  # 受管运行时控制协议包
│  │  │     ├─ ContainerRuntimeArguments.java  # 将受约束的容器运行模型转换为确定性的辅助程序参数
│  │  │     ├─ DeploymentRuntimeArguments.java  # 将一个已验证的非容器运行定义转换为辅助程序验证的标量参数
│  │  │     └─ ManagedRuntimeProtocolExecutor.java  # 仅通过固定 helper 动词控制受管运行时观察、生命周期与有界保留
│  │  ├─ runtime/  # 目标应用运行机制分组包
│  │  │  ├─ ContainerRuntimeExecutor.java  # 只观察和健康检查由受管发布根目录所有的命名容器
│  │  │  ├─ ManagedRuntimeExecutor.java  # 应用重启后从已封存的远端运行时标记恢复生命周期控制
│  │  │  ├─ ManagedRuntimeIdentity.java  # 在不持久化重复运行时规格的情况下识别已封存运行时类型
│  │  │  ├─ ManagedRuntimeKindProbe.java  # 通过固定 helper 协议读取 root 所有的当前发布标记
│  │  │  └─ systemd/  # systemd 单元、健康与生命周期包
│  │  │     ├─ SystemdHealthProbe.java  # 执行绑定到受管 systemd 进程的分层 HTTP 或 TCP 健康检查
│  │  │     ├─ SystemdHealthScriptRenderer.java  # 渲染将监听端口绑定到受管 systemd 控制组的分层健康脚本
│  │  │     ├─ SystemdLifecycleExecutor.java  # 仅在归属观察后执行生命周期变更并验证其后置条件
│  │  │     ├─ SystemdOwnershipObserver.java  # 仅在证明发布与 unit 归属后观察运行状态
│  │  │     └─ SystemdUnitRenderer.java  # 从类型化运行规格生成固定且受约束的 systemd 单元
│  │  ├─ session/  # SSHD 会话实现与安全关闭包
│  │  │  ├─ SshdLinuxRemoteSession.java  # 将各项类型化能力委派给其实现包的统一会话门面
│  │  │  └─ SshSessionLifecycleExecutor.java  # 关闭 Apache SSHD 会话资源且不掩盖首要操作结果
│  │  ├─ transfer/  # SFTP 源码归档传输包
│  │  │  ├─ LocalArchivePolicy.java  # 在传输前校验本地源码归档的路径、大小和普通文件属性
│  │  │  └─ SshdSourceTransport.java  # 通过 SFTP 将已校验源码归档传入受管候选工作区
│  │  └─ resources/  # 受管 helper 生产资源目录
│  │     ├─ protocol/  # helper 协议资源目录
│  │     │  └─ helper/  # root 持有 helper 资源目录
│  │     │     └─ fragments/  # 按职责拆分的 helper 脚本片段目录
│  │     │        ├─ 00-protocol-foundation.sh  # 定义受管 helper 协议的安全基线、路径和输入校验函数
│  │     │        ├─ 70-command-dispatch.sh  # 将 helper 协议命令分派到固定的受管操作
│  │     │        ├─ ecosystem/  # 语言生态构建与运行分派脚本片段目录
│  │     │        │  └─ 35-ecosystem-dispatch.sh  # 按受支持项目生态分派固定构建与运行准备流程
│  │     │        ├─ input/  # 部署输入脚本片段目录
│  │     │        │  └─ 15-deployment-input.sh  # 解析并校验类型化部署输入、配置和秘密引用
│  │     │        ├─ release/  # 发布与回滚脚本片段目录
│  │     │        │  ├─ 10-typed-release.sh  # 实现受约束的类型化候选发布与发布身份处理
│  │     │        │  ├─ 30-ordinary-release.sh  # 实现普通 systemd 应用的候选切换、快照和回滚流程
│  │     │        │  └─ 50-container-release.sh  # 实现容器应用的候选发布、Quadlet 配置和回滚流程
│  │     │        ├─ runtime/  # 类型化运行时脚本片段目录
│  │     │        │  └─ 40-typed-runtime.sh  # 实现类型化运行时环境、构建参数和产物校验
│  │     │        └─ workspace/  # 候选工作区脚本片段目录
│  │     │           └─ 20-candidate-workspace.sh  # 创建并校验受管候选工作区及其所有权边界
│  │     └─ runtime/  # 目标应用运行资源目录
│  │        ├─ container/  # 容器运行资源目录
│  │        │  └─ helper/  # Podman Quadlet helper 片段目录
│  │        │     └─ 55-podman-quadlet.sh  # 生成并管理 Podman Quadlet 容器运行单元
│  │        └─ systemd/  # systemd 运行资源目录
│  │           └─ helper/  # systemd 生命周期 helper 片段目录
│  │              └─ 60-lifecycle.sh  # 执行受管 systemd 应用的启动、停止、重启和自启操作
│  ├─ model/  # 跨模块领域模型与数据契约模块
│  │  ├─ analysis/  # 分析证据与运行建议模型包
│  │  │  ├─ AnalysisEvidence.java  # 单个确定性项目事实的紧凑、非秘密来源
│  │  │  ├─ DeploymentAdmissionStatus.java  # 项目进入部署计划前的确定性部署准入结果
│  │  │  ├─ EvidenceConfidenceLevel.java  # 单个确定性事实的置信等级，与任何 AI 建议相互独立
│  │  │  └─ RejectionReason.java  # 项目无法进入受管部署流程的确定性原因
│  │  ├─ archive/  # 源码归档描述模型包
│  │  │  └─ SourceArchiveDescriptor.java  # 本地准备的 tar.gz 源码归档，包含压缩大小和解压后大小边界
│  │  ├─ assessment/  # 项目评估、组件与问题模型包
│  │  │  ├─ ComponentIssue.java  # 一个组件范围的输入要求或硬安全拒绝
│  │  │  ├─ DeploymentProjectAssessment.java  # 将缺失用户决定与硬性安全拒绝分开的部署静态分析结果
│  │  │  └─ MultiComponentProjectAssessment.java  # 具有组件范围原因的确定性混合项目分析
│  │  ├─ capability/  # Linux 主机能力模型包
│  │  │  ├─ LinuxCapabilityFacts.java  # 仅用于决定能否提供部署计划的实时、非秘密主机事实
│  │  │  └─ ServerCapabilityFacts.java  # 创建任何受管部署候选项之前从目标主机采集的事实
│  │  ├─ deployment/  # 部署请求、状态与计划模型包
│  │  │  ├─ BuildLimitConfiguration.java  # 固定受管部署远程 Maven 构建入口经过审阅的明确限制
│  │  │  ├─ DeploymentStatus.java  # 受管部署发布事务的准确终态
│  │  │  ├─ DeploymentTraceEvent.java  # 稳定的部署跟踪事件代码；界面模块将这些代码映射为本地化标签
│  │  │  ├─ EnvironmentSetupApproval.java  # 在一个可信目标上安装固定受管部署 Ubuntu 工具集的单次明确确认
│  │  │  └─ EnvironmentSetupResult.java  # 固定工具集准备成功及其后重新采集的目标能力证据
│  │  ├─ health/  # HTTP 与 TCP 健康检查模型包
│  │  │  ├─ HealthCheck.java  # 受管部署仅有的两种健康检查策略
│  │  │  └─ UserAccessUrl.java  # 用户声明的已部署应用 HTTP(S) 访问 URL
│  │  ├─ language/  # 语言生态与源码语言事实模型包
│  │  │  ├─ LanguageEcosystemType.java  # 不含主要语言排序的确定性语言生态类型
│  │  │  ├─ LanguageFactKind.java  # 可用于预填审阅表单的稳定确定性语言事实类别
│  │  │  ├─ ProjectLanguageFacts.java  # 在不执行源码且不选择主要语言的情况下收集的确定性语言事实
│  │  │  └─ SourceLanguageType.java  # 由有界文件路径或元数据条目证实的源码语言类型
│  │  ├─ lifecycle/  # 生命周期动作与状态模型包
│  │  │  ├─ ApplicationAutostartState.java  # 一个应用全部组件的实时自启汇总状态
│  │  │  ├─ ApplicationRuntimeState.java  # 一个应用全部组件的实时运行汇总状态
│  │  │  ├─ AutostartState.java  # 受管单元经过验证的 systemd 启用状态
│  │  │  ├─ LifecycleAction.java  # 受支持的受管部署动作；不存在删除或任意服务动作
│  │  │  ├─ LifecycleObservation.java  # 为受管应用观测到的已验证远端状态
│  │  │  └─ RuntimeState.java  # 服务器实时观测结果，绝不是根据 SQLite 历史记录推断的值
│  │  ├─ managed/  # 受管应用与组件拓扑模型包
│  │  │  ├─ ManagedApplication.java  # 由 WindowsToLinux 拥有的应用不可变身份
│  │  │  └─ ManagedApplicationRuntimeConfiguration.java  # 某个受管应用最后一次成功部署的运行时契约
│  │  ├─ message/  # 结构化用户消息与诊断模型包
│  │  │  ├─ LocalizedFailure.java  # 用户可见摘要以及无秘密技术诊断
│  │  │  ├─ LocalizedMessage.java  # 不将领域代码与显示语言耦合的用户可见消息
│  │  │  └─ LocalizedOperationException.java  # 带有可本地化摘要的应用边界非受检失败
│  │  ├─ pom.xml  # 配置跨模块领域模型和公共数据契约模块的构建
│  │  ├─ project/  # 项目类型、运行规格与支持声明包
│  │  │  ├─ component/  # 多组件依赖、端口与数据路径模型包
│  │  │  │  ├─ ComponentDataPath.java  # 一个具有显式模式和访问契约的逻辑持久化数据路径
│  │  │  │  ├─ ComponentIsolationSpecification.java  # 一个组件显式请求的不安全执行能力
│  │  │  │  └─ DeploymentComponent.java  # 混合项目中一个组件的完整静态记录
│  │  │  ├─ DeploymentBuildToolType.java  # 固定的目标机构建工具类型，绝不是任意命令行
│  │  │  ├─ DeploymentProjectFacts.java  # 一个用户选定的部署项目类型的不可变确定性事实；未执行任何项目代码
│  │  │  ├─ DeploymentProjectType.java  # 部署计划器考虑的单组件项目类型
│  │  │  ├─ DeploymentRuntimeAssessment.java  # 在不运行项目内容的情况下推导出的、可审阅的源码依据运行时值
│  │  │  ├─ DeploymentRuntimeSpecification.java  # 恰好一个部署单组件项目类型的类型化运行定义
│  │  │  ├─ DeploymentSupportCatalog.java  # 提供每个界面共同使用的已检入支持声明
│  │  │  ├─ DeploymentSupportLevel.java  # 由证据支撑并由分析、计划和结果公开的支持等级
│  │  │  ├─ DeploymentSupportProfile.java  # 一个已分析路径的精确语言、框架、支持等级、验证矩阵与限制
│  │  │  ├─ SourceRevision.java  # 用于绑定分析、目标机构建和部署记录的不可变源码身份
│  │  │  └─ ValidatedDeploymentTarget.java  # 由真实产品入口验收证据支撑的一个精确目标组合
│  │  ├─ security/  # 凭据存储模式模型包
│  │  │  └─ CredentialStorageMode.java  # 用户选择的平台凭据存储机制，其本身绝不是秘密
│  │  └─ server/  # 服务器身份与发行版模型包
│  │     ├─ CpuMicroarchitectureLevel.java  # 目标运行时链接器确认的最高累积 x86-64 微架构级别
│  │     ├─ LinuxDistroType.java  # 部署为其保留独立证据和兼容性决策的发行版类型
│  │     ├─ ManagedHelperProtocolVersion.java  # 能力事实与远端执行共享的受管 helper 规范协议版本
│  │     ├─ security/  # 主机安全与防火墙状态模型包
│  │     │  ├─ LinuxFirewallKind.java  # 只读探测观测到的主机防火墙管理器
│  │     │  ├─ LinuxFirewallState.java  # 观测到的主机防火墙服务状态
│  │     │  ├─ LinuxSecurityModuleType.java  # 在不修改状态的情况下观测到的主机强制访问控制实现类型
│  │     │  ├─ LinuxSecurityPosture.java  # 作为一个连贯主机观测保留的只读安全与防火墙事实
│  │     │  └─ LinuxSecurityState.java  # 观测到的强制访问控制状态
│  │     └─ ServerIdentity.java  # 目标服务器及其可信 SSH 主机密钥的非秘密身份
│  ├─ pom.xml  # 聚合平台无关的共享叶子模块
│  └─ source/  # 源码清单、归档与安全校验模块
│     ├─ archive/  # 安全源码归档生成包
│     │  ├─ SafeSourceArchivePreparer.java  # 创建确定且经过边界检查的纯源码 tar.gz
│     │  └─ SourceArchive.java  # 可复现且经过边界检查的源码归档元数据
│     ├─ manifest/  # 确定性源码清单模型包
│     │  ├─ SourceEntry.java  # 规范化源码快照中的单个不可变普通文件成员
│     │  └─ SourceManifest.java  # 为归档准备的确定排序源码成员与排除项
│     ├─ pom.xml  # 配置源码归档、快照和安全校验模块的依赖与构建
│     ├─ snapshot/  # 规范 tar.gz 快照写入包
│     │  └─ SourceSnapshotAssembler.java  # 写入源码清单的规范 gzip 压缩 ustar 表示
│     └─ validation/  # 本地源码边界校验包
│        └─ SourceBoundaryValidator.java  # 验证本地源码边界并创建确定性安全文件清单
└─ web/  # Web 管理应用模块分组
   ├─ api/  # Web API 职责预留模块
   │  └─ pom.xml  # 保留 Web API 职责边界的 POM-only 模块
   ├─ auth/  # Web 身份认证职责预留模块
   │  └─ pom.xml  # 保留 Web 身份认证职责边界的 POM-only 模块
   ├─ db/  # Web 数据访问职责预留模块
   │  └─ pom.xml  # 保留 Web 数据访问职责边界的 POM-only 模块
   ├─ file/  # Web 文件管理职责预留模块
   │  └─ pom.xml  # 保留 Web 文件管理职责边界的 POM-only 模块
   ├─ frontend/  # Vue Web 前端模块
   │  ├─ index.html  # 提供 Vite Web 前端的 HTML 挂载入口
   │  ├─ package-lock.json  # 锁定 Web 前端 npm 依赖的精确版本和完整性信息
   │  ├─ package.json  # 声明 Web 前端脚本、运行依赖和开发依赖
   │  ├─ src/  # Vue 组件、入口与前端逻辑目录
   │  │  ├─ App.vue  # 提供当前 Web 前端可构建骨架的根组件
   │  │  ├─ env.d.ts  # 声明 Vite 前端环境的 TypeScript 类型引用
   │  │  ├─ main.ts  # 创建并挂载 Vue Web 前端应用
   │  │  ├─ readiness.ts  # 生成 Web 前端骨架的就绪状态消息
   │  │  └─ style.css  # 定义 Web 前端骨架页面的全局视觉样式
   │  ├─ tsconfig.app.json  # 配置浏览器端 TypeScript 源码编译规则
   │  ├─ tsconfig.json  # 聚合 Web 前端各 TypeScript 工程配置
   │  ├─ tsconfig.node.json  # 配置 Vite 与 Node 工具脚本的 TypeScript 规则
   │  └─ vite.config.ts  # 配置 Vue 插件、开发服务和 Vitest 测试环境
   ├─ main/  # Web 应用启动与装配预留模块
   │  └─ pom.xml  # 保留 Web 应用启动与装配职责边界的 POM-only 模块
   ├─ pom.xml  # 聚合 Web 管理端 Java 叶子模块
   ├─ secret/  # Web 秘密管理职责预留模块
   │  └─ pom.xml  # 保留 Web 秘密管理职责边界的 POM-only 模块
   ├─ service/  # Web 业务服务职责预留模块
   │  └─ pom.xml  # 保留 Web 业务服务职责边界的 POM-only 模块
   └─ task/  # Web 后台任务职责预留模块
      └─ pom.xml  # 保留 Web 后台任务职责边界的 POM-only 模块
```
