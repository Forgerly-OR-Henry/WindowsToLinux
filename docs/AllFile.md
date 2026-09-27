# WindowsToLinux 全文件架构索引

> 状态：1.1 改造进行中；下方目录树保留旧模块基线，文末补充已实际新增的 1.1 生产节点。完整模块树将在 S3 切换时合并；目标目录见 [File.md](File.md#phase-1-1-target)。旧 Web 预留不要求继续实现。
> 范围：仅记录 `src/` 下的非测试维护文件；省略 Maven 配置文件，测试、编译产物、忽略项和其他目录不纳入。
> 排序：同级项目按名称字母升序排列；模块的 `resources/` 目录固定置于最后。
> 粒度：模块、包和文件均有简体中文短注解；Java 文件仅说明与文件同名的顶级主类型。
> 路径：省略重复的 Maven 源码、资源与项目域名目录前缀，直接展示模块内职责包。

```text
src/  # 项目源码与模块根目录
├─ app/  # Windows 桌面应用模块分组
│  ├─ db/  # 桌面 SQLite v21、事务及应用仓库
│  │  ├─ DesktopPersistence.java  # 在已校验的当前 SQLite schema 上组合聚焦桌面仓库
│  │  ├─ entity/  # 持久化记录
│  │  │  ├─ CurrentRelease.java  # 某个受管应用最后成功发布的发布身份
│  │  │  ├─ ManagedApplicationGraph.java  # 一个已成功部署应用的持久拓扑、运行时及已审阅数据路径
│  │  │  ├─ StoredAiProviderConfiguration.java  # 模型显示顺序、配置修订及独立文字和视觉能力记录
│  │  │  ├─ StoredAiProviderProfile.java  # 命名的非秘密 AI 提供者元数据；仅持久化其不透明的凭据存储键
│  │  │  ├─ StoredApplicationPresentation.java  # 应用名称分类和入口覆盖
│  │  │  ├─ StoredApplicationSecretRevision.java  # 用于定位普通配置和发布目录之外秘密的不可变元数据
│  │  │  ├─ StoredExternalApplication.java  # 独立外部应用登记与端点身份
│  │  │  ├─ StoredServerObservation.java  # 带时间的服务器连接检查证据
│  │  │  ├─ StoredCredential.java  # 数据库凭据载荷与随机数
│  │  │  ├─ StoredSecurityProfile.java  # 密码验证及已包装数据密钥
│  │  │  ├─ StoredServerProfile.java  # 非秘密桌面连接元数据；密码或密钥保留在 app/secret 中
│  │  │  └─ SuccessfulManagedDeployment.java  # 经验证部署事务后持久化的一个组件状态
│  │  ├─ execution/  # 执行流程
│  │  │  └─ schema/  # 全新结构初始化与严格校验
│  │  │     └─ DesktopSchemaInitializer.java  # 仅创建空库或校验当前结构
│  │  ├─ failure/  # 跨模块最小结构化失败契约
│  │  │  ├─ DesktopPersistenceException.java  # 数据目录、完整性、锁定、磁盘及事务回滚的结构化停止失败
│  │  │  └─ DesktopPersistenceFailureType.java  # 桌面 SQLite 失败码、阶段与恢复边界
│  │  └─ persistence/  # 数据编解码职责组
│  │     ├─ connection/  # Apache SSHD 网关与会话创建包
│  │     │  └─ DesktopConnectionFactory.java  # 创建启用外键约束与有界忙等待的 SQLite 连接
│  │     ├─ repository/  # 作用域仓库
│  │     │  ├─ AgentTaskRepository.java  # 仅存储有界审计摘要及摘要值，不保存原始提示词或凭据
│  │     │  ├─ AiProfileRepository.java  # 保存不含凭据的 AI 提供者资料
│  │     │  ├─ AiPurposeRepository.java  # 独立于模型清单顺序保存用途成员
│  │     │  ├─ ApplicationSecretRepository.java  # 保存并按发布读取不可变秘密元数据绑定，绝不保存秘密值
│  │     │  ├─ BrowserRecoveryRepository.java  # 保存救援阶段、动作摘要和审计事件
│  │     │  ├─ ConfigurationSnapshotRepository.java  # 保存不可变的普通配置快照、条目及精确发布绑定
│  │     │  ├─ DesktopPreferenceRepository.java  # 保存小型非秘密桌面偏好
│  │     │  ├─ ExternalApplicationRepository.java  # 外部登记去重事务及显示设置
│  │     │  ├─ ManagedApplicationGraphRepository.java  # 将持久整应用拓扑与成功组件版本原子保存
│  │     │  ├─ ManagedApplicationRepository.java  # 保存受管应用、运行契约、发布和生命周期观测
│  │     │  ├─ RepositoryTransactionExecutor.java  # 由聚焦仓库使用的共享事务原语
│  │     │  └─ ServerProfileRepository.java  # 保存服务器信任身份与不含凭据的连接资料
│  │     └─ serialization/  # 持久化与接口数据序列化
│  │        ├─ ComponentPathPersistenceCodec.java  # 编解码成功部署时已审阅且不含秘密的数据路径清单
│  │        └─ ManagedResourcePersistenceCodec.java  # 编解码稳定文件绑定及未知或显式审阅的非秘密数据库绑定
│  ├─ main/  # 桌面应用入口与依赖装配
│  │  ├─ AppMain.java  # WindowsToLinux 桌面应用入口
│  │  ├─ diagnostic/  # 结构化失败安全展示包
│  │  │  ├─ DesktopFailureReportStore.java  # 原子写入、截断、脱敏并轮转固定 data/error-logs 报告
│  │  │  ├─ DesktopStartupException.java  # 启动布局、数据库及 UI 装配失败的结构化异常
│  │  │  ├─ DesktopSystemFailureType.java  # 桌面系统级启动、运行、资源和关闭失败目录
│  │  │  └─ DesktopUncaughtFailureBoundary.java  # 捕获线程边界失败并对 JVM 致命错误尽力记录后退出
│  │  ├─ runtime/  # 代码来源解析与运行边界
│  │  │  ├─ DesktopStorageLayout.java  # 从唯一运行模式 data 根派生数据库、受管应用、工作、备份和诊断路径
│  │  │  ├─ RunModeResolver.java  # 分离程序与数据锚点，CLASS 验证工作区，JAR 数据按 DB 模块，APP 按 EXE
│  │  │  └─ RuntimePathResolver.java  # 定位并验证受支持的开发、JAR 与 jpackage 文件系统布局
│  │  └─ startup/  # 应用装配与启动清理
│  │     ├─ DesktopMain.java  # 默认 H2 平台、安全及 Mod 页面外壳的生产桌面引导程序
│  │     └─ DesktopWindowController.java  # 持有一个桌面窗口，并在区域设置或主题变化时安全替换它
│  ├─ secret/  # 主密码保护的统一数据库凭据存储
│  │  ├─ crypto/  # 认证加密与备份秘密格式
│  │  ├─ SecretStore.java  # 定义秘密写入、读取、删除与调用方清零责任的平台凭据边界
│  │  ├─ SecretStoreException.java  # 凭据失败的公开 Problem；安全诊断与内部 cause 分离
│  │  ├─ SecretStoreFailureType.java  # 保留凭据事件码并映射公开 ProblemCode 类别
│  ├─ service/  # App 模式分流、凭据与持久化及共享能力编排
│  │  ├─ ai/  # AI 模型功能
│  │  │  ├─ AiConfigurationUseCase.java  # 桌面模型测试、保存与顺序编排
│  │  │  ├─ AiProviderChain.java  # 按优先级验证 AI 建议并处理取消
│  │  │  ├─ AiUseCaseFacade.java  # 编排 AI Provider、角色绑定、结构化分析与解释用例
│  │  │  ├─ AssistedDeploymentModelAdapter.java  # 独立辅助分析与登记恢复的模型适配
│  │  │  ├─ DeploymentAgentModelAdapter.java  # 凭据适配器，部署与审批不共享会话状态
│  │  │  ├─ DeploymentAiScope.java  # 工作线程持有的不可变路由作用域，嵌套辅助不能绕过静态模式
│  │  │  ├─ DeploymentReviewModelAdapter.java  # 辅助与自主流程共用的独立命令审核模型传输
│  │  │  └─ RecoveryAiUseCase.java  # 按配置快照分组回退并分离观察和诊断
│  │  ├─ backup/  # 备份恢复迁移
│  │  │  ├─ BackupArchiveCreationUseCase.java  # 编排临时写入、双重完整校验与无覆盖原子发布
│  │  │  ├─ BackupArchiveInspection.java  # 完整校验后可供界面展示的安全备份摘要
│  │  │  ├─ BackupDatabaseProfileMapper.java  # 备份服务内复用经审阅数据库连接映射
│  │  │  ├─ BackupUseCase.java  # 重新校验并准备本地候选、精确激活输入或按签发身份删除候选
│  │  │  ├─ CreatedBackupArchive.java  # 绑定最终路径与发布后独立复验结果
│  │  │  ├─ ManagedBackupInputAssessment.java  # 精确报告持久化备份输入完整性及结构化缺失原因
│  │  │  ├─ ManagedBackupInputUseCase.java  # 在不访问远端时核对整应用图、发布、运行时、路径、配置和秘密绑定
│  │  │  ├─ ManagedOfflineMigrationOutcome.java  # 保留初始/最终归档、源端状态、目标恢复及人工切流边界的迁移结果
│  │  │  ├─ ManagedOfflineMigrationPort.java  # 将双服务器迁移状态机映射到受管备份、源端停止和目标恢复操作
│  │  │  ├─ ManagedOfflineMigrationUseCase.java  # 编排初始备份、明确停写、最终同步、目标恢复和源端失败恢复
│  │  │  ├─ ManagedRestoreControlState.java  # 远端提交后本地秘密与整应用图接管的可审计状态
│  │  │  ├─ ManagedRestoreOutcome.java  # 组合目标预检、远端恢复终态和本地接管结果
│  │  │  ├─ ManagedRestorePreflightOutcome.java  # 任何目标写入前形成的只读兼容性、空间、端口与冲突结论
│  │  │  ├─ ManagedRestoreUseCase.java  # 编排归档激活准备、目标预检、暂存、候选健康、提交、恢复和本地接管
│  │  │  ├─ PreparedBackupActivation.java  # 绑定严格归档模型、候选和短生命周期秘密修订的激活输入
│  │  │  ├─ PreparedBackupCandidate.java  # 将候选证据绑定到已校验归档，并只为工作区签发实例保留删除授权
│  │  │  ├─ PreparedBackupSecrets.java  # 将本地候选与完整已认证短生命周期秘密修订绑定
│  │  │  ├─ RemoteBackupCreationUseCase.java  # 编排受管短停写取材、运行状态恢复、秘密加密和完整归档原子发布
│  │  │  ├─ RestoreArchiveModel.java  # 严格关联 schema v4 清单、配置文档和每个成员身份及摘要
│  │  │  ├─ RestoredApplicationRecorder.java  # 仅在远端正式健康后记录目标整应用图并报告接管失败
│  │  │  └─ RestoredSecretRegistrar.java  # 仅在远端正式健康后登记精确秘密修订并保持秘密清零边界
│  │  ├─ config/  # 配置模型与框架配置
│  │  │  └─ DeploymentConfigurationUseCase.java  # 协调不可变的部署配置和平台秘密引用，且不返回秘密值
│  │  ├─ contract/  # 平台无关交互与数据契约
│  │  │  ├─ AiApplicationFacade.java  # AI 页面所需的窄应用操作
│  │  │  ├─ AutomaticDeploymentApplicationFacade.java  # 一键部署、实时进度和 DB 准备的窄应用边界
│  │  │  ├─ BackupApplicationFacade.java  # 备份页面所需的备份、校验、恢复、离线迁移和候选管理窄操作
│  │  │  ├─ definition/  # 配置项定义、类型与校验规则包
│  │  │  │  ├─ AutomaticDeploymentOutcome.java  # 汇总真实部署终态与成功访问入口
│  │  │  │  ├─ AutomaticDeploymentRequest.java  # 冻结一次部署的来源、服务器与高级参数
│  │  │  │  ├─ ComponentFormInput.java  # 可跨页面保留且不含秘密值的组件表单数据
│  │  │  │  ├─ ComponentHealthMode.java  # 组件 HTTP 或 TCP 健康选择
│  │  │  │  ├─ DeploymentFormInput.java  # 单组件表单的类型化非秘密输入
│  │  │  │  ├─ DeploymentSourceInput.java  # 待服务解析的本地源码选择或 Git 引用控件值
│  │  │  │  ├─ MultiComponentReviewInput.java  # 一个静态准入组件由用户审阅的可变输入
│  │  │  │  └─ RecoverySnapshot.java  # 不含原始观察的临时救援界面快照
│  │  │  ├─ DeploymentApplicationFacade.java  # 单组件部署所需的窄应用操作
│  │  │  ├─ DesktopRecoveryInteraction.java  # 原任务安全续跑时的桌面救援交接
│  │  │  ├─ ManagedApplicationFacade.java  # 受管应用页面所需的窄应用操作
│  │  │  ├─ MultiComponentApplicationFacade.java  # 整应用部署与生命周期所需的窄应用操作
│  │  │  ├─ ServerApplicationFacade.java  # 服务器管理所需的窄应用操作
│  │  │  ├─ SshRecoveryApplicationFacade.java  # APP 网页终端救援应用入口
│  │  │  └─ SshRecoverySession.java  # 救援状态、绑定、确认及接管契约
│  │  ├─ deployment/  # 部署输入与任务提交
│  │  │  ├─ automatic/  # 单/多组件自动部署入口、表单与任务控制包
│  │  │  │  ├─ AssistedDeploymentInteraction.java  # 将既有具体风险决定接入相同的强制双审批
│  │  │  │  ├─ AssistedManagedToolCatalog.java  # 仅登记当前已准备应用范围内先前受管的服务
│  │  │  │  ├─ DeploymentCandidateToolService.java  # 仅为精确任务绑定候选操作解析凭据
│  │  │  │  ├─ AutomaticActionDescription.java  # 生成有界可审阅操作影响，排除配置值及秘密
│  │  │  │  ├─ AssistedDeploymentBoundary.java  # 审批标准步骤，将辅助恢复限制于登记工具
│  │  │  │  ├─ AutomaticDatabaseUseCase.java  # 前置补齐 DB 输入，再编排实例复用、专用账号及按库初始化
│  │  │  │  ├─ AutomaticDeploymentTaskService.java  # 仅 App 使用的模式装配、冻结模型作用域及持久化任务生命周期
│  │  │  │  ├─ AutomaticDeploymentUseCase.java  # 组合源码快照、单多组件识别与统一发布事务
│  │  │  │  ├─ AutomaticInputCompletion.java  # 先校验 AI 建议，再通过同一表单补齐剩余字段
│  │  │  │  ├─ ComponentFormUseCase.java  # 在服务内解析组件分析、健康及部署审阅输入
│  │  │  │  ├─ DeploymentDiagnosticEvidence.java  # 为固定辅助节点及串行 Agent 提供实际失败阶段证据
│  │  │  │  └─ DeploymentFormUseCase.java  # 在服务内解析单组件源码引用和自动部署表单
│  │  │  ├─ AutonomousDeploymentUseCase.java  # 持有的自主部署装配及原子清单记录
│  │  │  ├─ DeploymentInspectionUseCase.java  # 有界的确定性源码分析和部署计划生成
│  │  │  ├─ ManagedApplicationIdentityResolver.java  # 解析一个规范桌面受管身份，不包含部署专属行为
│  │  │  ├─ multi/  # 多组件编辑、页面与结果呈现包
│  │  │  │  ├─ ManagedMultiComponentApplication.java  # 桌面应用重启后使用的持久且不含秘密的应用拓扑
│  │  │  │  ├─ ReviewedComponentApplication.java  # 一个组件不含秘密的经审阅部署请求与稳定身份
│  │  │  │  └─ ReviewedMultiComponentApplication.java  # 一次整应用事务的完整无秘密审阅对象
│  │  │  ├─ MultiComponentDeploymentUseCase.java  # 负责经审阅整应用部署与生命周期的桌面产品边界
│  │  │  ├─ MultiComponentLifecycleUseCase.java  # 负责持久多组件应用恢复与生命周期操作
│  │  │  ├─ ReviewedDeploymentUseCase.java  # 将成功的经审阅部署持久化到与现有部署相同的受管应用清单中
│  │  │  └─ single/  # 单组件部署结果与交接契约包
│  │  │     ├─ DeploymentHandoff.java  # 仅在部署成功后可用的无秘密结构化后续步骤
│  │  │     └─ DeploymentOutcome.java  # 由桌面界面呈现的无秘密部署摘要
│  │  ├─ DesktopApplicationFacade.java  # 统一暴露桌面端服务器、源码、部署、生命周期、备份和 AI 用例门面
│  │  ├─ execution/  # 执行流程
│  │  │  ├─ environment/  # 目标环境准备服务包
│  │  │  │  └─ EnvironmentSetupUseCase.java  # 编排目标主机环境检查、审阅与受管准备流程
│  │  │  └─ lifecycle/  # 生命周期与接管
│  │  │     ├─ ApplicationInventoryUseCase.java  # 统一原生与外部清单及组合筛选字段
│  │  │     ├─ ApplicationLifecycleResult.java  # 保留受管诊断的统一操作结果
│  │  │     ├─ ApplicationScan.java  # 候选与既有归属映射及接管能力
│  │  │     ├─ ApplicationSummary.java  # 明确区分部署接管和观测时间的统一摘要
│  │  │     ├─ ExternalApplicationUseCase.java  # 扫描接管和身份复核的外部生命周期
│  │  │     ├─ LifecycleOutcome.java  # 单次桌面生命周期用例的无秘密结果
│  │  │     ├─ LifecycleUseCase.java  # 编排受管应用的实时观测、生命周期和自启操作
│  │  │     └─ ManagedApplicationSnapshot.java  # 已持久化的资源归属、成功部署契约和发布身份；并非远端运行时状态声明
│  │  ├─ failure/  # 跨模块最小结构化失败契约
│  │  │  ├─ ApplicationServiceException.java  # 受控桌面用例失败的结构化异常
│  │  │  └─ ApplicationServiceFailureType.java  # 用例拒绝与本地观测警告的失败目录
│  │  ├─ lock/  # 同服务器操作互斥注册包
│  │  │  └─ ServerOperationLockRegistry.java  # 按服务器串行化互斥操作并支持后台任务取消
│  │  ├─ recovery/  # 网页控制台 SSH 救援职责包
│  │  │  ├─ RecoveryProbeAdapter.java  # 将剩余旧 SSH 失败转换为 Linux 核心的 Problem 探测结果
│  │  │  ├─ RecoverySshProbe.java  # 验证 SSH 身份、认证和只读命令执行
│  │  │  └─ SshRecoveryUseCase.java  # 装配浏览器救援、服务器互斥及原任务衔接
│  │  ├─ server/  # 服务器配置与观测
│  │  │  ├─ DesktopSecretStoreService.java  # 为已接纳操作提供限定数据库凭据访问
│  │  │  ├─ ServerSummary.java  # 服务器卡片与名称地址查询摘要
│  │  │  └─ ServerUseCaseFacade.java  # 编排服务器资料、主机信任、连接验证与能力探测
│  │  └─ source/  # 源码输入与静态快照
│  │     ├─ PreparedComponentSource.java  # 一个混合项目组件的经审阅不可变源码输入
│  │     ├─ PreparedMultiComponentSource.java  # 整个混合应用的静态评估及独立不可变归档
│  │     ├─ ReviewedSourcePreparation.java  # 在计划经审阅部署前展示的类型化源码分析和安全归档结果
│  │     ├─ SourcePreparationUseCase.java  # 将本地目录或固定 Git 来源统一准备为经审阅源码
│  │     └─ SourceSelectionService.java  # 既有 Git 地址及本地目录边界内的源码输入识别
│  ├─ ui/  # Swing 桌面界面模块
│  │  ├─ ai/  # AI 模型功能
│  │  │  ├─ AiInventoryState.java  # 外观变化时保留的非秘密内联编辑状态
│  │  │  ├─ AiModelInventoryPane.java  # 内联编辑用途时保持原模型清单可见
│  │  │  ├─ AiPage.java  # 持有可选 AI 表单、临时秘密、状态与解释流程
│  │  │  ├─ AiPageState.java  # 保存桌面外观重建期间尚未提交的 AI 页面状态
│  │  │  ├─ AiProviderDialog.java  # 桌面 AI 编辑与连接测试弹窗
│  │  │  └─ AiProviderDragTransfer.java  # 桌面 AI 模型拖动排序
│  │  ├─ backup/  # 备份恢复迁移
│  │  │  ├─ BackupPage.java  # 创建或校验归档、认证备份密码，并在明确目标及停写批准后执行恢复或迁移
│  │  │  ├─ BackupPageState.java  # 保留外观重建期间的任务、输入和候选状态，并在关闭时清除密码副本
│  │  │  └─ BackupSelectionPane.java  # 查询已保存应用与服务器并保留精确选择状态
│  │  ├─ component/  # 多组件依赖、端口与数据路径模型包
│  │  │  ├─ AdvancedOptionsPane.java  # 提供可伸缩侧栏、嵌套输入修改提示、操作冻结与问号帮助
│  │  │  ├─ AdvancedWindowHost.java  # 保持工作区尺寸的窗口检查面板宿主
│  │  │  ├─ DesktopComponentFactory.java  # 创建桌面页面复用的按钮、表单和布局组件
│  │  │  ├─ DesktopIcons.java  # 随主题着色的内置 SVG 图标
│  │  │  ├─ DesktopTaskExecutor.java  # 后台运行操作、回送 Swing 结果并记录活动任务以保护外观重建
│  │  │  ├─ DesktopTaskHandle.java  # 桌面后台任务完成和取消句柄
│  │  │  ├─ RoundedCard.java  # 共享圆角卡片绘制
│  │  │  ├─ SystemPreparationDialog.java  # 默认拒绝的系统配置与服务器重启确认框
│  │  │  └─ ToggleSwitch.java  # 保留原生二值状态、键盘及无障碍行为的后置滑块开关
│  │  ├─ deployment/  # 部署输入与任务提交
│  │  │  ├─ automatic/  # 单/多组件自动部署入口、表单与任务控制包
│  │  │  │  ├─ DeploymentForm.java  # 持有部署控件、非秘密表单状态与领域输入映射
│  │  │  │  ├─ DeploymentInputDialog.java  # 保留缺项表单并展开脱敏 AI 问答和风险确认
│  │  │  │  ├─ DeploymentModeSelector.java  # 当前模式入口、弹出三档滑块及带图标的审批策略
│  │  │  │  ├─ DeploymentPage.java  # 持有源码选择、审阅状态、部署表单与完整经审阅部署流程
│  │  │  │  ├─ DeploymentPageState.java  # 保存自动部署页尚未提交的运行、健康、配置和源码审阅状态
│  │  │  │  ├─ DeploymentSourceCard.java  # 单项源码选择、后台识别及卡片内展示，清除后可重新拖入或粘贴
│  │  │  │  └─ DeploymentTaskControls.java  # 显式暂停、恢复、取消控件及持久化非秘密任务历史
│  │  │  ├─ multi/  # 多组件编辑、页面与结果呈现包
│  │  │  │  ├─ MultiComponentDraftController.java  # 持有多组件控件、草稿及选择，通过门面提交表单契约
│  │  │  │  ├─ MultiComponentFormState.java  # 桌面外观重建期间保留的未保存编辑值
│  │  │  │  ├─ MultiComponentPage.java  # 用于显式混合项目审阅、整应用部署和生命周期的桌面产品页面
│  │  │  │  ├─ MultiComponentPageState.java  # 完整且不含秘密的多组件页面状态
│  │  │  │  └─ MultiComponentResultPresenter.java  # 为桌面输出区格式化有界多组件审阅与结果证据
│  │  │  └─ ReviewContext.java  # 可选 AI 页面使用的窄当前审阅视图
│  │  ├─ diagnostic/  # 结构化失败安全展示包
│  │  │  ├─ DesktopFailurePresenter.java  # 展示错误码、操作标识、安全摘要、恢复结果和报告位置
│  │  │  ├─ FailureReportRecord.java  # 可呈现的本地诊断报告引用
│  │  │  └─ FailureReportStore.java  # UI 与 main 诊断写入实现之间的窄契约
│  │  ├─ display/  # 主题、外观与区域设置包
│  │  │  ├─ DesktopComboBoxUi.java  # 统一固定下拉框入口宽度，保留展开列表完整内容
│  │  │  ├─ DesktopDisplayConfiguration.java  # 在桌面设置页面选择的可持久化显示偏好
│  │  │  ├─ DesktopThemeService.java  # 受管部署 Swing 桌面客户端共用的 FlatLaf 设置
│  │  │  ├─ SystemThemeResolver.java  # 读取 Windows 应用外观偏好，但不写入系统注册表
│  │  │  ├─ ThemeMode.java  # 用户可选择的桌面外观偏好
│  │  │  └─ ThemePalette.java  # 单个生效浅色或深色外观使用的不可变应用颜色
│  │  ├─ i18n/  # 桌面本地化资源目录
│  │  │  ├─ MessageCatalog.java  # 从区域设置资源包解析应用消息键和非递归命名占位符
│  │  │  └─ PageMessagePresenter.java  # 独立页面控制器共享的本地化与诊断格式化
│  │  ├─ managed/  # 受管应用与组件拓扑模型包
│  │  │  ├─ ApplicationPresentationDialog.java  # 应用分类名称和入口的独立编辑窗口
│  │  │  ├─ ApplicationScanDialog.java  # 独立扫描与明确选择接管窗口
│  │  │  ├─ ManagedPage.java  # 持有受管应用选择、已保存运行检查详情和生命周期流程
│  │  │  └─ ManagedPageState.java  # 保存受管应用页当前选择的应用标识与输出内容
│  │  ├─ recovery/  # 网页控制台 SSH 救援职责包
│  │  │  ├─ RecoveryInteractionPresenter.java  # 服务器检查及环境准备的救援交接
│  │  │  └─ SshRecoveryDialog.java  # 人工登录交接、终端绑定及命令确认
│  │  ├─ server/  # 服务器配置与观测
│  │  │  ├─ ServerContext.java  # 部署与生命周期页面使用的窄服务器上下文
│  │  │  ├─ ServerInventoryPane.java  # 名称与地址搜索及服务器纵向卡片列表
│  │  │  ├─ ServerPage.java  # 持有服务器表单、内存凭据状态与服务器流程
│  │  │  ├─ ServerPageState.java  # 保存桌面外观重建期间尚未提交的服务器表单状态
│  │  │  ├─ ServerProfileDialog.java  # 独立服务器添加编辑及保存检查窗口
│  │  │  ├─ ServerSelectionPane.java  # 单台目标选择与清除、摘要卡片及独立可搜索选择窗口
│  │  │  └─ ServerTrustPrompt.java  # 后台连接期间的事件线程主机指纹确认
│  │  ├─ setting/  # 桌面设置页面与状态包
│  │  │  ├─ SettingPage.java  # 持有外观控件与即时应用流程
│  │  │  ├─ SettingPageState.java  # 设置立即生效，因此没有需要保留的未保存页面局部值
│  │  │  ├─ UiDebugDialog.java  # Class 模式主页面导航与运行过程弹窗预览目录
│  │  │  └─ UiPreviewService.java  # 固定示例数据及默认拒绝真实操作的隔离预览服务
│  │  ├─ shell/  # 主窗口、导航与页面装配包
│  │  │  ├─ DesktopDisplayChangeHandler.java  # 保存选定外观，并在事件线程上替换活动桌面窗口
│  │  │  ├─ DesktopFrame.java  # 只负责导航、页面装配和窗口生命周期的窗口外壳
│  │  │  ├─ DesktopPageCoordinator.java  # 装配页面控制器、窄页面上下文、导航及整个窗口状态
│  │  │  ├─ DesktopViewState.java  # 在为外观热更新重建外壳时聚合各页面持有的状态
│  │  │  └─ PageNavigationController.java  # 在不向页面公开 Swing 组件的情况下导航外壳
│  │  └─ resources/  # 生产资源
│  │     ├─ component/  # 多组件依赖、端口与数据路径模型包
│  │     │  └─ icons/  # 界面 SVG 图标及 Lucide 上游许可
│  │     │     ├─ archive.svg  # 导航及操作图标
│  │     │     ├─ arrow-right.svg  # 源码到目标服务器的部署方向图标
│  │     │     ├─ bot.svg  # 导航及操作图标
│  │     │     ├─ chevron-down.svg  # 模式入口的展开箭头
│  │     │     ├─ chevron-right.svg  # 导航及操作图标
│  │     │     ├─ folder-open.svg  # 导航及操作图标
│  │     │     ├─ git-branch.svg  # 导航及操作图标
│  │     │     ├─ grip-vertical.svg  # 导航及操作图标
│  │     │     ├─ hand.svg  # 人工审核的手掌图标
│  │     │     ├─ LICENSE.txt  # 上游完整许可声明
│  │     │     ├─ panel-left-close.svg  # 导航及操作图标
│  │     │     ├─ panel-left-open.svg  # 导航及操作图标
│  │     │     ├─ panels-top-left.svg  # 导航及操作图标
│  │     │     ├─ plus.svg  # 导航及操作图标
│  │     │     ├─ refresh-cw.svg  # 导航及操作图标
│  │     │     ├─ rocket.svg  # 导航及操作图标
│  │     │     ├─ search.svg  # 导航及操作图标
│  │     │     ├─ server.svg  # 导航及操作图标
│  │     │     ├─ settings.svg  # 导航及操作图标
│  │     │     ├─ shield-alert.svg  # 完全控制的警示盾牌图标
│  │     │     └─ shield.svg  # 自动审批的盾牌图标
│  │     └─ i18n/  # 桌面本地化资源目录
│  │        └─ messages/  # 英文与简体中文消息目录
│  │           ├─ Messages.properties  # 提供桌面界面的英文基准消息目录
│  │           └─ Messages_zh_CN.properties  # 提供与英文消息键一致的简体中文界面映射
│  └─ windows/  # Windows 平台边界模块
│     ├─ uninstall/  # 无默认选择的本地受管卸载安全边界
│     │  ├─ DesktopUninstallCoordinator.java  # 显式保留/删除分支、受管预检和精确残留编排
│     │  ├─ DesktopUninstallDecisionType.java  # 保留或删除本地数据及凭据的显式决定
│     │  ├─ DesktopUninstallEvent.java  # 单个有界卸载证据事件
│     │  ├─ DesktopUninstallException.java  # 卸载边界结构化受检失败
│     │  ├─ DesktopUninstallFailureType.java  # 决定、边界、任务和残留失败目录
│     │  ├─ DesktopUninstallHandoff.java  # 交给外部执行器的显式决定和主进程停收证据
│     │  ├─ DesktopUninstallHandoffCodec.java  # 认证并重建跨进程卸载交接的严格版本化载荷
│     │  ├─ DesktopUninstallPort.java  # 分离主进程停收与外部执行器删除能力的窄端口
│     │  ├─ DesktopUninstallPreparationResult.java  # 外部交接就绪、缺少决定或前置拒绝结果
│     │  ├─ DesktopUninstallPreparationStatus.java  # 主进程卸载准备阶段的精确终态
│     │  ├─ DesktopUninstallRequest.java  # 固定 jpackage、data 子目录和专用凭据命名空间请求
│     │  ├─ DesktopUninstallResult.java  # 区分失败残留与用户有意保留项目的卸载终态
│     │  ├─ DesktopUninstallState.java  # 决定、停任务、边界和各删除步骤状态
│     │  └─ DesktopUninstallStatus.java  # 需选择、前置拒绝、两类成功及带残留终态
│     ├─ update/  # 桌面签名更新及程序/SQLite 成对回滚边界
│     │  ├─ DesktopArchitectureType.java  # x86-64 与 ARM64 签名包架构
│     │  ├─ DesktopReleaseVersion.java  # 严格三段式桌面版本及比较
│     │  ├─ DesktopUpdateCoordinator.java  # 停任务、成对备份、独立交接、迁移、健康和回滚事务
│     │  ├─ DesktopUpdateEvent.java  # 单个有界更新证据事件
│     │  ├─ DesktopUpdateException.java  # 更新验证与事务结构化受检失败
│     │  ├─ DesktopUpdateFailureType.java  # 清单、签名、版本、架构、软件包、事务和回滚失败目录
│     │  ├─ DesktopUpdateHandoff.java  # 交给独立更新器的停收、成对备份和已验证更新证据
│     │  ├─ DesktopUpdateHandoffCodec.java  # 认证并重建跨进程更新交接的严格版本化载荷
│     │  ├─ DesktopUpdateManifest.java  # Ed25519 签名载荷、摘要、有效期、架构及紧急回退标记
│     │  ├─ DesktopUpdatePort.java  # 分离主进程准备与独立更新器替换的成对更新窄端口
│     │  ├─ DesktopUpdatePreparationResult.java  # 主进程交接就绪或结构化前置拒绝结果
│     │  ├─ DesktopUpdatePreparationStatus.java  # 独立更新器启动前的主进程准备终态
│     │  ├─ DesktopUpdateResult.java  # 更新成功、前置拒绝、安全回滚和人工恢复终态
│     │  ├─ DesktopUpdateState.java  # 停任务、备份、交接、替换、迁移、健康及回滚状态
│     │  ├─ DesktopUpdateStatus.java  # 更新精确终态
│     │  ├─ DesktopUpdateTrustPolicy.java  # 固定 Ed25519 公钥、撤销集合及版本批准策略
│     │  ├─ DesktopUpdateVerification.java  # 完整签名、版本、架构和包身份验证证据
│     │  └─ DesktopUpdateVerifier.java  # 流式摘要及固定发布信任验证器
│     └─ workspace/  # 受管远端源码与补丁边界
│        ├─ DesktopHandoffEnvelopeCodec.java  # 以用途隔离和 HMAC-SHA256 认证维护交接二进制信封
│        ├─ PreparedSourceArchive.java  # 保存已准备源码归档的描述信息与排除条目清单
│        ├─ WindowsBackupArchiveAttempt.java  # 绑定同目录临时归档、最终目标和不可伪造文件身份
│        ├─ WindowsBackupArchiveWorkspace.java  # 无覆盖原子发布并只清理本次精确持有的归档文件
│        ├─ WindowsBackupMaterialAttempt.java  # 绑定本次完整备份取材工作区身份和精确成员根
│        ├─ WindowsBackupMaterialWorkspace.java  # 在固定工作根创建成员并只精确清理本次完整备份取材目录
│        ├─ WindowsRestoreAttempt.java  # 不可伪造地绑定恢复尝试父目录身份、摘要候选根和候选标识
│        ├─ WindowsRestoreWorkspace.java  # 只创建或按创建时目录身份精确清理平台工作区下的恢复尝试
│        ├─ WindowsSourcePreparer.java  # 准备平台无关源码归档的 Windows 桌面入口
│        ├─ WindowsWorkspaceException.java  # Windows 工作区预检失败的结构化异常
│        └─ WindowsWorkspaceFailureType.java  # Windows 路径、权限、容量与链接失败目录
├─ shared/  # 平台无关共享模块分组
│  ├─ agent/  # 自主 Agent 决策与执行闭环
│  │  ├─ approval/  # 独立审批及实际命令审核边界
│  │  │  └─ SourcePatchApproval.java  # 独立于通用命令权限的专用源码授权
│  │  ├─ execution/  # 执行流程
│  │  │  ├─ AutonomousDeploymentSession.java  # 不依赖标准分析的串行证据驱动部署循环
│  │  │  └─ protocol/  # helper 协议资源目录
│  │  │     ├─ AgentDeliveryInputs.java  # 使用既有公共存储契约的严格非秘密部署输入
│  │  │     ├─ AgentProposal.java  # 一个严格且串行执行的自主工具请求
│  │  │     ├─ AutonomousAiProtocolClient.java  # 严格自主部署模式及业务校验
│  │  │     └─ AutonomousModelPort.java  # 用途隔离的串行部署推理
│  │  ├─ tool/  # 职责包
│  │  │  └─ AgentDeliveryPort.java  # 由应用持有凭据及受管发布，引擎不访问界面或数据库
│  │  └─ resources/  # 生产资源
│  │     └─ protocol/  # helper 协议资源目录
│  │        └─ autonomous.md  # 模块资源与配置
│  ├─ ai/  # AI 模型功能
│  │  ├─ AiAnalysisException.java  # 可选 AI 解释路径产生的安全、非秘密失败
│  │  ├─ AiAnalysisFailureType.java  # 可选 AI 失败码；不参与授权、回滚或确定性分类
│  │  ├─ AiStructuralAssessment.java  # 可选解释文本；它绝不改变确定性的项目支持判断或部署决策
│  │  ├─ client/  # Provider HTTP 客户端与传输契约包
│  │  │  ├─ OpenAiCompatibleRoleClient.java  # 仅调用一个已配置提供者并只保留已验证的不含凭据证据
│  │  │  ├─ OpenAiCompatibleStructuralAnalysisClient.java  # 仅使用已脱敏的确定性事实调用 OpenAI 兼容端点
│  │  │  └─ StructuredAiClient.java  # 不包含部署策略的有界结构化提供者传输
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
│  │  │  │  ├─ AiProviderAttempt.java  # 单次提供商尝试的结构化证据
│  │  │  │  └─ AiRoleInvocationResult.java  # 一个角色恰好一次提供者调用的结果
│  │  │  └─ role/  # 固定角色、绑定与脱敏上下文包
│  │  │     ├─ AiCollaborationRoleKind.java  # 具有独立提供者选择和上下文边界的固定 AI 协作角色类别
│  │  │     ├─ AiRoleBinding.java  # 从一个固定角色到唯一命名提供者和模型的非秘密绑定
│  │  │     ├─ AiRoleContext.java  # 一个协作角色可接受的封闭最小脱敏事实
│  │  │     ├─ DeploymentInputRoleContext.java  # 仅向 AI 提交脱敏问题与有界候选
│  │  │     ├─ DeploymentRiskRoleContext.java  # 不含 SSH 数据、源码内容、配置值或秘密的最小经审阅计划事实
│  │  │     ├─ ErrorExplanationRoleContext.java  # 在保留或传输前移除类似凭据文本的最小受控失败
│  │  │     └─ ProjectAnalysisRoleContext.java  # 不含源码路径、内容、配置值或秘密的最小项目事实
│  │  ├─ execution/  # 执行流程
│  │  │  └─ protocol/  # helper 协议资源目录
│  │  │     └─ StrictJson.java  # 严格 JSON 结构检查
│  │  ├─ generation/  # 发行版内容生成功能组
│  │  │  └─ prompt/  # 固定角色提示构建包
│  │  │     ├─ AiResponseLanguageType.java  # 可选 AI 生成解释所支持的语言类型
│  │  │     ├─ RolePrompt.java  # 构建响应必须匹配唯一 JSON 模式的固定角色请求
│  │  │     └─ StructuralAnalysisPrompt.java  # 根据已经脱敏的事实构建固定结构分析请求
│  │  ├─ parser/  # 结构化 AI 响应解析包
│  │  │  ├─ ChatCompletionResponseParser.java  # 解析单个有界 Chat Completions 响应，不接受任意 JSON 结构
│  │  │  └─ RoleAdviceParser.java  # 严格解析固定建议 JSON 对象并拒绝额外字段
│  │  ├─ provider/  # AI Provider 配置与协议包
│  │  │  └─ ProviderEndpointPolicy.java  # 在创建任何请求之前验证 Provider 端点和模型
│  │  ├─ recovery/  # 网页控制台 SSH 救援职责包
│  │  │  └─ RecoveryModelClient.java  # OpenAI 兼容图片观察与独立命令建议客户端
│  │  ├─ redaction/  # 敏感信息识别与最小化脱敏包
│  │  │  ├─ AgentEvidenceText.java  # 仅为明确选取的诊断片段提供有界脱敏，绝不处理完整源码或配置
│  │  │  └─ RedactedDeploymentProjectFacts.java  # 允许离开确定性分析边界的最小类型化部署事实
│  │  └─ transport/  # 角色聊天传输契约、HTTP 实现与结果包
│  │     ├─ HttpRoleChatTransport.java  # 精确所选 OpenAI 兼容端点的 JDK HTTP 传输
│  │     ├─ RoleChatResult.java  # 所选提供者传输返回的有界 HTTP 响应
│  │     └─ RoleChatTransport.java  # 单个已选提供者的传输；不提供发现或回退 API
│  ├─ backup/  # 备份恢复迁移
│  │  ├─ contract/  # 平台无关交互与数据契约
│  │  │  ├─ definition/  # 配置项定义、类型与校验规则包
│  │  │  │  ├─ BackupCollectionRequest.java  # 停写采集的组件、操作归属和健康检查契约
│  │  │  │  └─ BackupCollectionResult.java  # 已验证备份成员、运行证据和原始组件状态
│  │  │  ├─ spi/  # 构建渲染器扩展契约包
│  │  │  │  ├─ BackupCollectionInteraction.java  # 采集进度与取消交互端口
│  │  │  │  ├─ BackupCollectionMaterialPort.java  # 平台私有素材目录与输出配额端口
│  │  │  │  ├─ DatabaseBackupAdapter.java  # 按数据库类型执行预检、导出和候选恢复策略
│  │  │  │  ├─ DatabaseBackupArtifact.java  # 已导出数据库制品的大小、摘要和一致性证据
│  │  │  │  ├─ DatabaseBackupRequest.java  # 数据库备份身份、连接、运行状态和写入排他声明
│  │  │  │  ├─ DatabaseCommitEvidence.java  # 已激活数据库候选与可保留的回滚点证据
│  │  │  │  ├─ DatabaseCompatibilityEvidence.java  # 服务端、工具和表引擎兼容性证据
│  │  │  │  ├─ DatabaseConnectionProfile.java  # SQLite 路径或服务器数据库的不透明连接资料
│  │  │  │  ├─ DatabaseContractRules.java  # 数据库标识、版本和安全文本公共约束
│  │  │  │  ├─ DatabaseOperationPort.java  # 检查、导出、流式制品和候选恢复的模块内策略端口
│  │  │  │  ├─ DatabaseRecoveryEvidence.java  # 数据库回滚或未提交候选清理证据
│  │  │  │  ├─ DatabaseRestoreEvidence.java  # 候选数据库恢复及只读验证结果
│  │  │  │  ├─ DatabaseRestoreRequest.java  # 绑定备份制品与受管候选标识的恢复请求
│  │  │  │  ├─ OfflineMigrationPort.java  # 目标预检、双阶段同步、停写、目标验证和双端恢复窄端口
│  │  │  │  ├─ OfflineMigrationRequest.java  # 在最终归档尚未产生前绑定源/目标、预计大小和明确停写批准的迁移请求
│  │  │  │  ├─ RestoreCandidatePort.java  # 文件暂存、两级健康、提交和失败恢复的平台窄端口
│  │  │  │  └─ RestoreCandidateRequest.java  # 不反向依赖恢复编排包的已验证候选平台请求
│  │  │  └─ validation/  # 请求边界与非秘密输入校验
│  │  │     ├─ ArchivePathRules.java  # 拒绝绝对路径、穿越、空段和 Windows 不安全尾部
│  │  │     ├─ BackupArchivePolicy.java  # 成员数、大小、路径、清单和压缩率显式边界
│  │  │     ├─ BackupArchiveValidation.java  # 完整校验后生成的归档指纹与证据
│  │  │     ├─ BackupArchiveValidator.java  # 不提取地校验 ZIP 结构、摘要和可选签名
│  │  │     ├─ BackupException.java  # 备份模块结构化受检失败
│  │  │     ├─ BackupFailureType.java  # 备份、恢复与清理稳定失败定义
│  │  │     ├─ BackupManifestValidator.java  # 对解码清单应用资源策略
│  │  │     ├─ BackupProvenanceStatus.java  # 未签名、未验证与已验证来源状态
│  │  │     ├─ BackupSignatureTrust.java  # 显式受信备份签名公钥解析契约
│  │  │     ├─ ManagedArtifactEvidence.java  # 受管制品格式、大小、摘要和成员数量验证证据
│  │  │     ├─ ManagedArtifactFormatType.java  # PAX TAR 与 OCI Archive 的封闭取材格式
│  │  │     └─ ManagedArtifactValidator.java  # 不提取地严格验证受管 PAX TAR 与 OCI Archive 取材制品
│  │  ├─ crypto/  # 认证加密与备份秘密格式
│  │  │  ├─ BackupSecretCryptoService.java  # 使用调用级独立备份密码生成或认证 secrets.enc
│  │  │  ├─ BackupSecretDocument.java  # 持有完整已解码秘密修订并支持整体清零
│  │  │  ├─ BackupSecretDocumentCodec.java  # 不产生秘密字符串的严格规范二进制载荷编解码器
│  │  │  ├─ BackupSecretException.java  # 不携带密码或明文的备份秘密结构化失败
│  │  │  └─ BackupSecretFailureType.java  # 备份密码、加密、统一认证及认证后载荷失败定义
│  │  ├─ execution/  # 执行流程
│  │  │  ├─ collection/  # 受管远端取材格式与独立验证证据
│  │  │  │  └─ BackupCollectionService.java  # 桌面与 Web 共用的准入、停机、采集、恢复和清理编排
│  │  │  └─ migration/  # 版本化迁移
│  │  │     ├─ OfflineMigrationCoordinator.java  # 目标预检、初始/最终同步、停写、验证和失败恢复状态机
│  │  │     ├─ OfflineMigrationEvent.java  # 单个有界无秘密迁移证据事件
│  │  │     ├─ OfflineMigrationResult.java  # 保留源端且禁止声称自动切流的迁移终态
│  │  │     ├─ OfflineMigrationState.java  # 迁移准备及双端恢复的有序证据状态
│  │  │     └─ OfflineMigrationStatus.java  # 等待人工切流、前置拒绝、安全恢复和人工恢复终态
│  │  ├─ extension/  # 发行版实现装配功能组
│  │  │  ├─ adapter/  # 项目类型到部署执行器的适配包
│  │  │  │  ├─ DatabaseAdapterEvidence.java  # 适配器共用的严格证据一致性检查
│  │  │  │  ├─ LinuxDatabaseOperationPort.java  # 将 Linux 公共远程数据库契约适配为备份模块策略类型
│  │  │  │  ├─ LinuxRestoreCandidateAdapter.java  # 将备份候选单向映射到 deploy 激活与 Linux 文件暂存窄端口
│  │  │  │  ├─ MysqlDatabaseAdapter.java  # 按全事务表或停写排他条件选择一致性导出
│  │  │  │  ├─ PostgresqlDatabaseAdapter.java  # 仅允许服务与工具兼容的逻辑导出
│  │  │  │  └─ SqliteDatabaseAdapter.java  # 优先在线备份并拒绝活跃写入直接复制
│  │  │  └─ registry/  # 发行版准备目录与注册装配包
│  │  │     └─ DatabaseAdapterRegistry.java  # 对支持类型闭合并拒绝未知数据库
│  │  ├─ format/  # 版本化归档流式写入
│  │  │  ├─ BackupArchiveContent.java  # 清单成员与全新输入流的绑定
│  │  │  ├─ BackupArchiveStream.java  # 可受检打开的成员输入流窄契约
│  │  │  ├─ BackupArchiveWriter.java  # 写入时核验每个成员大小与 SHA-256
│  │  │  ├─ BackupConfigurationCodec.java  # 严格编解码历史配置或完整非秘密激活文档并拒绝截断、尾随及非规范载荷
│  │  │  ├─ BackupConfigurationDocument.java  # 恢复所需的配置、资源绑定、运行时、健康门和用户 URL 完整非秘密文档
│  │  │  ├─ BackupSecretEnvelope.java  # Argon2id 参数、随机盐/nonce 与 AES-GCM 密文信封
│  │  │  └─ BackupSecretEnvelopeCodec.java  # secrets.enc 严格确定性编解码
│  │  ├─ manifest/  # 确定性源码清单模型包
│  │  │  ├─ BackupComponent.java  # 组件身份、依赖、定义成员引用和类型化运行时
│  │  │  ├─ BackupComponentRuntime.java  # 全部十四种已审阅运行时的封闭可移植 schema
│  │  │  ├─ BackupConsistencyMode.java  # 数据库一致性证据方式
│  │  │  ├─ BackupDatabase.java  # 数据库类型、版本、工具和一致性限制
│  │  │  ├─ BackupDatabaseType.java  # SQLite、PostgreSQL、MySQL/MariaDB 等数据库族
│  │  │  ├─ BackupHealthCheck.java  # 不混用字段的 HTTP 或 TCP 健康探针可移植值
│  │  │  ├─ BackupHealthCheckType.java  # HTTP 与 TCP 健康策略判别类型
│  │  │  ├─ BackupIdentity.java  # 受管应用、服务器、根目录和发布身份
│  │  │  ├─ BackupInventory.java  # 恢复所需的完整结构化数据清单
│  │  │  ├─ BackupManagedVolume.java  # 受管容器卷的可移植副本
│  │  │  ├─ BackupManifest.java  # 当前版本根清单与成员唯一性约束
│  │  │  ├─ BackupManifestCodec.java  # 严格确定性的 manifest.json 编解码
│  │  │  ├─ BackupManifestRules.java  # 清单内部有界文本和路径规则
│  │  │  ├─ BackupManifestSigner.java  # 对规范未签名载荷添加 Ed25519 来源签名
│  │  │  ├─ BackupMember.java  # 精确路径、大小、摘要和类别
│  │  │  ├─ BackupMemberKind.java  # 发布、配置、数据、数据库、运行时和加密秘密类别
│  │  │  ├─ BackupProvenance.java  # 与完整性分离的可选来源签名元数据
│  │  │  └─ BackupRuntime.java  # 源发行版、运行时版本、架构和能力证据
│  │  └─ restore/  # 受管恢复候选与激活片段
│  │     ├─ BackupArchiveExtractor.java  # 指纹重绑定、精确提取和失败清理
│  │     ├─ BackupRestoreCandidate.java  # 尚未激活的完整候选证据
│  │     ├─ BackupRestoreCoordinator.java  # 文件、数据库、两级健康、提交及失败恢复状态机
│  │     ├─ BackupRestorePlan.java  # 绑定归档、候选、目标和数据库制品的不可变计划
│  │     ├─ BackupRestorePreflight.java  # 在修改前拒绝路径、空间、平台、运行时和数据库不兼容
│  │     ├─ BackupRestoreResult.java  # 绑定操作标识、终态、事件、数据库和失败证据
│  │     ├─ BackupRestoreStatus.java  # 成功、保留现有版本失败和人工恢复终态
│  │     ├─ RestoreCandidateEvent.java  # 单个有界候选恢复状态证据
│  │     ├─ RestoreCandidateState.java  # 前置、文件、数据库、健康、提交和恢复状态
│  │     ├─ RestoreMaterialKind.java  # 源码重建与二进制发布兼容策略
│  │     ├─ RestoreTargetEvaluator.java  # 以实时目标事实保守判定架构、运行时、数据库和候选端口策略
│  │     └─ RestoreTargetProfile.java  # 修改前已验证的目标平台、资源和数据库事实
│  ├─ config/  # 配置模型与框架配置
│  │  ├─ ConfigurationException.java  # 配置定义与输入拒绝的结构化异常
│  │  ├─ ConfigurationFailureType.java  # 配置失败码、阶段与用户纠正动作
│  │  ├─ contract/  # 平台无关交互与数据契约
│  │  │  └─ definition/  # 配置项定义、类型与校验规则包
│  │  │     ├─ ConfigurationScope.java  # 非秘密配置值被使用的受限时点
│  │  │     └─ ConfigurationValue.java  # 类型化的非秘密配置值，不能携带 Shell 片段
│  │  ├─ input/  # 部署输入脚本片段目录
│  │  │  └─ DeploymentConfigurationParser.java  # 解析服务层非秘密构建与运行配置及精确公开秘密引用
│  │  ├─ persistence/  # 数据编解码职责组
│  │  │  └─ serialization/  # 持久化与接口数据序列化
│  │  │     ├─ ApplicationRuntimeConfigurationCodec.java  # 受管运行配置的版本化编码
│  │  │     ├─ ApplicationWorkloadCodec.java  # APP 运行声明的规范化二进制编码
│  │  │     ├─ DeploymentRuntimePersistenceCodec.java  # 编解码成功部署时已审阅且不含秘密的运行时定义
│  │  │     └─ HealthCheckCodec.java  # HTTP、TCP、UDP、进程与命令验证编码
│  │  ├─ resource/  # 经审阅的非秘密受管资源绑定包
│  │  │  ├─ ManagedComponentResourceBindings.java  # 组合稳定文件绑定及未知或显式审阅的数据库范围
│  │  │  ├─ ManagedDatabaseBinding.java  # 将稳定数据库标识绑定到一个非秘密连接
│  │  │  ├─ ManagedDatabaseConnection.java  # SQLite 文件或服务器数据库端点及精确密码秘密引用
│  │  │  ├─ ManagedDatabaseEngineType.java  # SQLite、PostgreSQL、MySQL、MariaDB 与 Redis 数据库类型
│  │  │  ├─ ManagedFileBinding.java  # 将稳定本地受管标识绑定到已审阅逻辑数据路径
│  │  │  └─ ManagedStoragePlan.java  # 统一计算、校验和展示配置、文件及 SQLite 物理路径
│  │  ├─ revision/  # 不可变配置快照与修订包
│  │  │  ├─ ConfigurationEntry.java  # 不可变配置快照中的一个经过类型检查的非秘密值
│  │  │  ├─ ConfigurationSnapshot.java  # 一个已发布应用版本的不可变、以摘要寻址的普通配置
│  │  │  └─ DeploymentInputManifest.java  # 将一个发布绑定到不可变配置和精确秘密修订的非秘密清单
│  │  └─ secretref/  # 秘密引用与已解析修订包
│  │     ├─ ResolvedSecretRevision.java  # 仅用于已认证部署传输的短生命周期已解析秘密字节
│  │     ├─ SecretReference.java  # 一个指向不可变应用秘密修订的透明引用；它绝不包含秘密值
│  │     └─ SecretRevisionDigest.java  # 一个已解析秘密修订的公开完整性元数据；它绝不包含秘密值
│  ├─ deploy/  # 模式无关的审批、受管发布及生命周期
│  │  ├─ approval/  # 独立审批及实际命令审核边界
│  │  │  ├─ DeploymentApprovalGate.java  # 执行前再次求值的默认拒绝策略
│  │  │  ├─ CommandApprovalAudit.java  # 对标准辅助及自主部署应用一致的命令语义
│  │  │  ├─ DeploymentApprovalClient.java  # 独立命令及操作审批协议
│  │  │  └─ DeploymentReviewPort.java  # 模块资源与配置
│  │  ├─ contract/  # 平台无关交互与数据契约
│  │  │  ├─ ApplicationHealthGate.java  # 由一个经审阅组件端点承载的整体应用健康探测
│  │  │  ├─ AutomaticDeploymentInteraction.java  # 定义分组缺项、秘密输入与明确风险授权交互
│  │  │  ├─ MultiComponentDeploymentPlan.java  # 一个经审阅组件图的确定性构建与运行顺序
│  │  │  ├─ result/  # 部署与生命周期结果分组包
│  │  │  │  ├─ deployment/  # 部署输入与任务提交
│  │  │  │  │  ├─ ComponentDeploymentResult.java  # 组件范围的部署证据与终态
│  │  │  │  │  ├─ ComponentTransactionState.java  # 应用事务中一个组件的终态
│  │  │  │  │  ├─ DeploymentEvent.java  # 适用于界面和持久化历史的简短、无秘密跟踪条目
│  │  │  │  │  ├─ DeploymentResult.java  # 部署终态结果，包括独立的回滚结果
│  │  │  │  │  └─ MultiComponentDeploymentResult.java  # 始终保留每个组件结果的应用级事务结果
│  │  │  │  └─ lifecycle/  # 生命周期与接管
│  │  │  │     ├─ ComponentLifecycleResult.java  # 一次应用生命周期请求中的组件实时结果
│  │  │  │     ├─ LifecycleActionResult.java  # 单资源生命周期操作结果
│  │  │  │     └─ MultiComponentLifecycleResult.java  # 保留每个组件的权威应用生命周期结果
│  │  │  └─ spi/  # 构建渲染器扩展契约包
│  │  │     ├─ CandidatePortBinding.java  # 绑定组件的正式端口与候选检查端口
│  │  │     ├─ CandidatePortMode.java  # 区分候选端口隔离方式
│  │  │     ├─ CandidatePortPlan.java  # 保存整应用候选端口映射和占用计划
│  │  │     ├─ DatabaseCredentialPort.java  # 平台无关数据库秘密修订访问契约
│  │  │     ├─ RestoreDeploymentComponent.java  # 准备受管激活的依赖有序类型化恢复组件
│  │  │     ├─ RestoreDeploymentPort.java  # 候选激活、两级健康、提交及回滚的 deploy 所有窄端口
│  │  │     └─ RestoreDeploymentRequest.java  # 绑定摘要候选、组件图和整应用健康门的恢复激活请求
│  │  ├─ delivery/  # 职责包
│  │  │  └─ ManagedDelivery.java  # 显式组件及依赖边
│  │  ├─ error/  # 脱敏错误定义
│  │  │  ├─ DeploymentExecutionFailureType.java  # 部署执行失败码、阶段与恢复动作
│  │  │  └─ DeploymentSwitchException.java  # 发布切换失败的结构化异常
│  │  ├─ execution/  # 执行流程
│  │  │  ├─ environment/  # 目标环境准备服务包
│  │  │  │  ├─ EnvironmentSetupService.java  # 运行一个经过显式批准的环境准备操作
│  │  │  │  └─ SelinuxPreparationService.java  # 编排独立批准的 SELinux 准备、重启重连及强制模式验证
│  │  │  ├─ lifecycle/  # 生命周期与接管
│  │  │  │  ├─ ManagedComponentLifecycle.java  # 单个组件生命周期操作所需的不含秘密的受管身份与运行时
│  │  │  │  ├─ ManagedLifecycleService.java  # 仅在实时验证资源归属后应用生命周期动作
│  │  │  │  ├─ MultiComponentLifecyclePolicy.java  # 应用纯目标、依赖影响、最终状态与汇总规则
│  │  │  │  └─ MultiComponentLifecycleService.java  # 根据权威远端观测执行依赖安全的应用生命周期动作
│  │  │  └─ transaction/  # 部署事务、恢复与回滚协调包
│  │  │     ├─ DeploymentInputMapper.java  # 统一投影构建、运行、秘密摘要和受管文件绑定，并清理秘密副本
│  │  │     ├─ ManagedRestoreDeploymentPort.java  # 将受管恢复衔接到既有部署事务边界
│  │  │     └─ RestoreCandidatePortPlanner.java  # 规划恢复候选的临时端口并拒绝冲突
│  │  ├─ publication/  # 模式无关的受管发布、健康及回滚
│  │  │  ├─ ManagedPublicationTransaction.java  # 模式中立的发布、整应用健康及可验证逆序回滚
│  │  │  └─ PreparedPublication.java  # 显式资源绑定
│  │  ├─ task/  # 持久化任务调度与恢复
│  │  │  └─ AgentTaskControl.java  # 在事务边界协作控制串行任务
│  │  └─ resources/  # 生产资源
│  │     └─ approval/  # 独立审批及实际命令审核边界
│  │        └─ approval.md  # 审批 Agent 版本化内置流程
│  ├─ git/  # 受约束 Git 来源与快照模块
│  │  ├─ GitReference.java  # 用户选择的可变或不可变 Git 引用；分析前会将其解析为 Commit
│  │  ├─ GitRemote.java  # 解析后的 Git 远端；其位置绝不嵌入凭据
│  │  ├─ GitSnapshot.java  # 固定到一个 Commit 并配有确定性源码归档的检出结果
│  │  ├─ GitSnapshotException.java  # 安全的 Git 快照失败；其消息刻意不含远端或凭据材料
│  │  ├─ GitSnapshotFailureType.java  # Git 工具、引用、网络、超时与清理失败目录
│  │  ├─ GitSourceRequest.java  # 只读 Git 分析快照的有界输入；凭据始终位于此值之外
│  │  └─ snapshot/  # 冻结源码身份与摘要
│  │     ├─ ControlledGitWorkspaceValidator.java  # 仅验证并创建平台拥有的 Git 工作区边界
│  │     ├─ GitCommandExecutor.java  # 以禁用 Hook 和 LFS 物化的方式运行有界非交互 Git 命令
│  │     ├─ GitRepositoryFeaturePolicy.java  # 在存在显式有界物化策略前拒绝 Submodule、LFS 与符号链接条目
│  │     └─ GitSnapshotPreparer.java  # 协调克隆、固定检出、仓库策略校验和安全源码归档
│  ├─ linux-sshd/  # Apache SSHD 与 Linux 运行适配模块
│  │  ├─ backup/  # 备份恢复迁移
│  │  │  ├─ execution/  # 执行流程
│  │  │  │  └─ protocol/  # helper 协议资源目录
│  │  │  │     ├─ DatabaseProtocolParser.java  # 严格解析有界兼容性、数据库制品和恢复证据
│  │  │  │     └─ ManagedBackupProtocolParser.java  # 严格解析受管文件、卷和镜像制品证据
│  │  │  ├─ generation/  # 发行版内容生成功能组
│  │  │  │  └─ script/  # 通用发行版准备脚本生成包
│  │  │  │     ├─ DatabaseCommandRenderer.java  # 只渲染固定 helper 数据库动词和校验参数
│  │  │  │     └─ ManagedBackupCommandRenderer.java  # 只渲染固定受管取材、读取和清理动词
│  │  │  ├─ SshdBackupArtifactPort.java  # 经 SSHD 流式复制并校验普通文件、卷和 OCI 制品的具体端口
│  │  │  └─ SshdDatabaseOperationPort.java  # 通过 SSHD 流式传输并校验数据库制品的具体端口
│  │  ├─ capability/  # Linux 主机能力模型包
│  │  │  ├─ CapabilityReadExecutor.java  # 能力包内复用只读探测的有界重试
│  │  │  ├─ ecosystem/  # 技术生态专属模型包
│  │  │  │  └─ ManagedEcosystemCapabilityProbe.java  # 渲染只读语言与构建工具能力探测
│  │  │  ├─ ManagedHostCapabilityProbe.java  # 通过固定远端探测收集目标主机工具链与运行能力
│  │  │  ├─ ManagedPlatformCapabilityProbe.java  # 用于采集部署发行版、容器和 CPU 事实的固定只读 Shell 程序
│  │  │  ├─ SshdCapabilityCollector.java  # 使用 SSHD 会话只读收集发行版、CPU、安全和防火墙事实
│  │  │  └─ SshdPlatformCapabilityCollector.java  # 部署主机只读能力契约的 Apache SSHD 实现
│  │  ├─ command/  # 固定远端命令执行包
│  │  │  ├─ CommandOutputCapture.java  # SSH 标准输出和错误输出共享预算
│  │  │  └─ SshCommandExecutor.java  # 执行预先渲染的固定命令，并为受控大制品提供不进入诊断缓冲区的流式通道
│  │  ├─ connection/  # Apache SSHD 网关与会话创建包
│  │  │  └─ SshdLinuxGateway.java  # 受管部署白名单远程契约的 Apache MINA SSHD 实现
│  │  ├─ distro/  # 发行版系统准备资源
│  │  │  ├─ dnf/  # DNF 发行版固定系统准备
│  │  │  │  └─ SelinuxPreparationExecutor.java  # 执行固定且绑定批准事实的 SELinux 准备脚本
│  │  │  ├─ ManagedEnvironmentExecutor.java  # 在只读主机探测后仅选择固定的受支持发行版环境准备脚本
│  │  │  └─ ManagedPlatformInstaller.java  # 共用 helper 及隔离安装，不选择项目语言
│  │  ├─ execution/  # 执行流程
│  │  │  ├─ protocol/  # helper 协议资源目录
│  │  │  │  ├─ CandidateWorkspaceExecutor.java  # 通过固定 helper 动词创建、查询和清理任务绑定候选工作区
│  │  │  │  ├─ database/  # 原生数据库管理与一致性操作片段目录
│  │  │  │  │  └─ SshdNativeDatabasePort.java  # 通过固定 helper 动词和敏感标准输入管理原生 DB
│  │  │  │  ├─ helper/  # systemd 生命周期 helper 片段目录
│  │  │  │  │  └─ ManagedHelperBundle.java  # 统一展开固定职责资源并拼装 root 持有的受管 helper，拒绝协议漂移
│  │  │  │  ├─ input/  # 部署输入脚本片段目录
│  │  │  │  │  ├─ DeploymentConfigurationRenderer.java  # 为 systemd 与容器使用方渲染不可变运行时配置
│  │  │  │  │  ├─ DeploymentInputArguments.java  # 将非秘密输入清单转换为确定性辅助程序参数
│  │  │  │  │  ├─ DeploymentInputProtocolExecutor.java  # 在发布前通过 root 所有的辅助程序流式传输并封存运行时输入
│  │  │  │  │  └─ ManagedContentArguments.java  # 将已审阅普通文件绑定渲染到固定远端受管数据根
│  │  │  │  ├─ release/  # 发布身份与 0.6 期发布预留
│  │  │  │  │  ├─ ContainerReleaseProtocolExecutor.java  # 应用彼此独立的 Docker 重启策略与 Podman Quadlet 发布协议
│  │  │  │  │  └─ DeploymentReleaseProtocolExecutor.java  # 使用 root 所有的辅助程序处理经审阅非容器发布的快照、发布和回滚
│  │  │  │  ├─ restore/  # 受管恢复候选与激活片段
│  │  │  │  │  └─ SshdRestoreActivationPort.java  # 通过固定 SSHD 协议执行恢复候选激活
│  │  │  │  └─ runtime/  # 代码来源解析与运行边界
│  │  │  │     ├─ ApplicationHealthProbe.java  # 调用当前封存发布的受控验证入口
│  │  │  │     ├─ ApplicationWorkloadArguments.java  # 运行声明和独立健康验证到 helper 的有界参数协议
│  │  │  │     ├─ ContainerRuntimeArguments.java  # 将受约束的容器运行模型转换为确定性的辅助程序参数
│  │  │  │     ├─ DeploymentRuntimeArguments.java  # 将一个已验证的非容器运行定义转换为辅助程序验证的标量参数
│  │  │  │     └─ ManagedRuntimeProtocolExecutor.java  # 固定 helper 运行时操作、完整原生状态解码及停止诊断保留
│  │  │  └─ transfer/  # SFTP 源码归档与恢复候选传输包
│  │  │     ├─ LocalArchivePolicy.java  # 在传输前校验本地源码归档的路径、大小和普通文件属性
│  │  │     ├─ SshdRestoreTransport.java  # 上传精确恢复成员并经 SFTP 独立回读完整性
│  │  │     └─ SshdSourceTransport.java  # 通过 SFTP 将已校验源码归档传入受管候选工作区
│  │  ├─ runtime/  # 代码来源解析与运行边界
│  │  │  ├─ ContainerRuntimeExecutor.java  # 只观察和健康检查由受管发布根目录所有的命名容器
│  │  │  ├─ ManagedRuntimeExecutor.java  # 应用重启后从已封存的远端运行时标记恢复生命周期控制
│  │  │  ├─ ManagedRuntimeIdentity.java  # 在不持久化重复运行时规格的情况下识别已封存运行时类型
│  │  │  ├─ ManagedRuntimeKindProbe.java  # 通过固定 helper 协议读取 root 所有的当前发布标记
│  │  │  ├─ SshdExternalApplicationPort.java  # 固定程序的扫描协议及身份复核
│  │  │  └─ systemd/  # systemd 运行资源目录
│  │  │     ├─ SystemdHealthProbe.java  # 执行绑定到受管 systemd 进程的分层 HTTP 或 TCP 健康检查
│  │  │     ├─ SystemdHealthScriptRenderer.java  # 渲染将监听端口绑定到受管 systemd 控制组的分层健康脚本
│  │  │     ├─ SystemdLifecycleExecutor.java  # 仅在归属观察后执行生命周期变更并验证其后置条件
│  │  │     ├─ SystemdOwnershipObserver.java  # 仅在证明发布与 unit 归属后观察运行状态
│  │  │     └─ SystemdUnitRenderer.java  # 从类型化运行规格生成固定且受约束的 systemd 单元
│  │  ├─ session/  # 0.6 期会话预留
│  │  │  ├─ SshdLinuxRemoteSession.java  # 将各项类型化能力委派给其实现包的统一会话门面
│  │  │  └─ SshSessionLifecycleExecutor.java  # 关闭 Apache SSHD 会话资源且不掩盖首要操作结果
│  │  ├─ workspace/  # 受管远端源码与补丁边界
│  │  │  └─ SshdRemoteProjectPort.java  # 受保护源码及沙箱脚本的机械 helper 适配器
│  │  └─ resources/  # 生产资源
│  │     ├─ distro/  # 发行版系统准备资源
│  │     │  └─ dnf/  # DNF 发行版固定系统准备
│  │     │     ├─ centos-source-repositories.py  # CentOS 9/10 源码依赖事务 CRB 选择
│  │     │     └─ selinux-preparation.sh  # 备份配置、修复标签、重启与验证强制模式的受控步骤
│  │     ├─ execution/  # 执行流程
│  │     │  └─ protocol/  # helper 协议资源目录
│  │     │     └─ helper/  # systemd 生命周期 helper 片段目录
│  │     │        └─ fragments/  # 按职责拆分的 helper 脚本片段目录
│  │     │           ├─ 00-protocol-foundation.sh  # 定义受管 helper 协议的安全基线、路径和输入校验函数
│  │     │           ├─ 70-command-dispatch.sh  # 将 helper 协议命令分派到固定的受管操作
│  │     │           ├─ backup/  # 备份恢复迁移
│  │     │           │  └─ 67-managed-backup.sh  # 实现固定 PAX TAR/OCI 取材、制品读取和精确操作清理
│  │     │           ├─ database/  # 原生数据库管理与一致性操作片段目录
│  │     │           │  ├─ 10-native-instances.sh  # 检测已有实例并执行系统包安装、替换和恢复等待
│  │     │           │  ├─ 20-native-targets.sh  # 创建限定权限的应用库、受保护启动凭据和一次性 SQL 初始化
│  │     │           │  ├─ 64-database-client.sh  # SQL 客户端、数据库参数与 SQLite 物理绑定核验
│  │     │           │  ├─ 65-database-backup.sh  # 实现数据库预检、导出、制品流转、清理和候选恢复
│  │     │           │  └─ 66-database-activation.sh  # 激活数据库候选并保留可验证的回滚边界
│  │     │           ├─ ecosystem/  # 技术生态专属模型包
│  │     │           │  └─ 35-ecosystem-dispatch.sh  # 按受支持项目生态分派固定构建与运行准备流程
│  │     │           ├─ input/  # 部署输入脚本片段目录
│  │     │           │  ├─ 15-deployment-input.sh  # 解析并校验类型化部署输入、配置和秘密引用
│  │     │           │  ├─ 16-application-input.sh  # 独立解析和验证 APP 运行协议
│  │     │           │  └─ 17-managed-content.sh  # 解析、准备并验证普通文件的固定远端受管目录绑定
│  │     │           ├─ release/  # 发布身份与 0.6 期发布预留
│  │     │           │  ├─ 10-typed-release.sh  # 实现受约束的类型化候选发布与发布身份处理
│  │     │           │  ├─ 12-container-image-input.sh  # rootless 镜像交接与容器数据权限
│  │     │           │  ├─ 18-container-storage.sh  # 宿主绑定、镜像初始化、只读视图与挂载归属检查
│  │     │           │  ├─ 50-container-release.sh  # 实现容器应用的发布、镜像与命名卷归属流程
│  │     │           │  └─ 52-container-recovery.sh  # 实现容器快照、回滚和生命周期恢复流程
│  │     │           ├─ restore/  # 受管恢复候选与激活片段
│  │     │           │  ├─ 54-restore-candidate.sh  # 准备受管恢复候选及隔离检查输入
│  │     │           │  ├─ 56-restore-commit.sh  # 提交恢复候选并执行失败回滚
│  │     │           │  └─ 57-application-restore.sh  # 候选 APP 验证与候选端口映射
│  │     │           ├─ runtime/  # 代码来源解析与运行边界
│  │     │           │  ├─ 40-typed-runtime.sh  # 实现类型化运行时环境、构建参数和产物校验
│  │     │           │  ├─ 41-application-runtime.sh  # 任务登记、维护准入和输入校验
│  │     │           │  ├─ 42-application-job.sh  # 原生非 root 前台任务及隔离验证
│  │     │           │  ├─ 43-application-container.sh  # 一次性任务容器和清理
│  │     │           │  ├─ 44-application-health.sh  # 稳定进程、受控自检与 TCP/HTTP/UDP 协议验证
│  │     │           │  └─ 45-application-container-contract.sh  # 镜像固定入口、挂载及端口实际映射核对
│  │     │           └─ workspace/  # 受管远端源码与补丁边界
│  │     │              ├─ 20-candidate-workspace.sh  # 创建并校验受管候选工作区及其所有权边界
│  │     │              ├─ 21-workspace-volume.sh  # 固定 ext4 容量及挂载设备回收
│  │     │              ├─ 22-restricted-build.sh  # 临时构建、控制组限制与只读封存
│  │     │              ├─ 23-container-builder.sh  # 临时 rootless 账号和 UID/GID 映射
│  │     │              ├─ 24-build-entry.sh  # 独立安装的非特权构建与 rootless 后端入口
│  │     │              ├─ 25-workspace-recovery.sh  # 开机回收受管候选，隔离不明残留
│  │     │              ├─ 26-build-output.sh  # root 控制器累计输出预算和有界日志
│  │     │              ├─ 27-agent-workspace.sh  # 模块资源与配置
│  │     │              └─ apparmor-namespace.sh  # 工作区使用实际引擎 AppArmor profile 探测命名空间
│  │     ├─ runtime/  # 代码来源解析与运行边界
│  │     │  ├─ container/  # 容器运行资源目录
│  │     │  │  └─ helper/  # systemd 生命周期 helper 片段目录
│  │     │  │     └─ 55-podman-quadlet.sh  # 生成并管理 Podman Quadlet 容器运行单元
│  │     │  ├─ external-applications.py  # 按已认证会话执行固定外部应用扫描与身份复核
│  │     │  └─ systemd/  # systemd 运行资源目录
│  │     │     └─ helper/  # systemd 生命周期 helper 片段目录
│  │     │        ├─ 60-lifecycle.sh  # 执行受管 systemd 应用的启动、停止、重启和自启操作
│  │     │        ├─ 61-service-identity.sh  # 固定服务账号、声明写入权限与停止确认
│  │     │        ├─ selinux-command-entry.sh  # SELinux 标准服务启动入口
│  │     │        └─ systemd-manager-isolation.sh  # systemd 管理接口隔离
│  │     ├─ toolchain/  # 工具链声明、分支目录及受管准备职责包
│  │     │  ├─ 10-release-metadata.py  # 解析官方发布元数据、版本和可信摘要
│  │     │  ├─ 20-installation-boundaries.py  # 校验归档、系统依赖、归属和有界子进程
│  │     │  ├─ 30-managed-installation.py  # 隔离安装、系统复用及完成标记验证
│  │     │  └─ 40-binding-protocol.py  # 密封精确绑定、验证和跨机恢复定位
│  │     └─ workspace/  # 受管远端源码与补丁边界
│  │        └─ source-operations.py  # 模块资源与配置
│  ├─ linux/  # Linux 远程操作公共契约模块
│  │  ├─ build/  # 目标主机构建协调包
│  │  │  ├─ DeploymentBuildResult.java  # 在发布类型化运行时之前，有界部署源码构建的已验证结果
│  │  │  ├─ RemoteBuildEnvironment.java  # 按应用限定且仅含构建值的远程环境输入
│  │  │  ├─ RemoteBuildPort.java  # 由装配入口注入的可选标准构建能力
│  │  │  └─ ToolchainPreparationResult.java  # 已准备的精确身份及独立采集的系统能力事实
│  │  ├─ capability/  # Linux 主机能力模型包
│  │  │  ├─ LinuxCapabilityCollector.java  # 类型化目标主机能力采集契约
│  │  │  └─ LinuxPlatformCapabilityCollector.java  # 用于部署发行版和容器矩阵的只读能力采集
│  │  ├─ command/  # 固定远端命令执行包
│  │  │  ├─ CommandExecutionScope.java  # 位于实际 SSH 派发处的工作线程强制边界，覆盖固定工具
│  │  │  ├─ CommandText.java  # 字面参数与有界协议解析
│  │  │  ├─ RemoteCommandAudit.java  # 活跃任务的强制审核及持久化派发钩子
│  │  │  ├─ RemoteCommandExecutor.java  # 与传输实现无关的有界命令执行
│  │  │  ├─ RemoteCommandRequest.java  # 源码及证据修订
│  │  │  └─ RemoteCommandResult.java  # 退出状态
│  │  ├─ connection/  # Apache SSHD 网关与会话创建包
│  │  │  ├─ DeploymentLinuxGateway.java  # 打开一个支持有界部署协议的已验证 SSH 会话
│  │  │  ├─ HostKeyDecision.java  # 由应用而非 SSH 适配器决定是否信任主机密钥
│  │  │  ├─ HostKeyEvaluator.java  # 在认证前调用，用于首次使用确认和阻止密钥变更
│  │  │  ├─ HostKeyObservation.java  # 同一 SSH 公钥的标准与历史编码指纹
│  │  │  ├─ LinuxGateway.java  # 为完整部署或生命周期动作打开单个已验证 SSH 会话
│  │  │  ├─ SshCredential.java  # 由平台秘密模块提供的内存认证材料
│  │  │  └─ SshEndpoint.java  # 接受并持久化主机密钥之前的目标地址
│  │  ├─ distro/  # 发行版系统准备资源
│  │  │  ├─ EnvironmentPreparationPlan.java  # 模块资源与配置
│  │  │  ├─ LinuxEnvironmentPreparer.java  # 感知发行版的环境准备契约
│  │  │  └─ SelinuxEnvironmentPreparer.java  # 绑定当前服务器事实的固定 SELinux 准备窄端口
│  │  ├─ ecosystem/  # 技术生态专属模型包
│  │  │  └─ db/  # Spring 数据源事务与 MyBatis-Plus 持久化
│  │  │     └─ NativeDatabasePort.java  # 定义实例检查、安装、复用和初始化的类型化契约
│  │  ├─ error/  # 脱敏错误定义
│  │  │  ├─ LinuxOperationException.java  # 连接、指纹、协议或受控操作失败
│  │  │  ├─ LinuxOperationFailureType.java  # SSH、数据库协议、远端状态及中断失败目录
│  │  │  ├─ NativeDatabaseException.java  # 原生 DB 非受检异常与结构化失败描述
│  │  │  └─ NativeDatabaseFailureType.java  # 原生 DB 六类失败、固定错误码与恢复建议
│  │  ├─ protocol/  # helper 协议资源目录
│  │  │  ├─ backup/  # 备份恢复迁移
│  │  │  │  ├─ ManagedContentPublication.java  # 随发布绑定的普通文件受管根及容器命名卷归属清单
│  │  │  │  ├─ RemoteBackupArtifact.java  # helper 创建且绑定操作身份的远端制品证据
│  │  │  │  ├─ RemoteBackupArtifactKind.java  # 发布树、文件树、命名卷和 OCI 镜像封闭类型
│  │  │  │  ├─ RemoteBackupArtifactPort.java  # 固定创建、流式复制和精确丢弃受管制品的窄端口
│  │  │  │  ├─ RemoteBackupArtifactRequest.java  # 绑定应用、组件、发布、资源及操作身份的取材请求
│  │  │  │  └─ RemoteManagedFileBinding.java  # 远程发布使用的稳定文件身份与逻辑数据路径
│  │  │  ├─ database/  # 原生数据库管理与一致性操作片段目录
│  │  │  │  └─ RemoteDatabasePort.java  # 不引用备份格式或 SSHD 类型的数据库检查、导出、流转和候选恢复端口
│  │  │  ├─ ManagedHelperProtocol.java  # 由预检与 SSH 实现共享的稳定受管 helper 协议身份
│  │  │  ├─ ReleaseSnapshot.java  # 部署改变状态之前捕获的不透明远端回滚引用
│  │  │  ├─ RemoteDeploymentInputs.java  # 配置摘要及精确秘密身份、修订、长度和摘要的非秘密绑定
│  │  │  ├─ RemoteRuntimeConfiguration.java  # 保留原配置摘要且仅含运行值的远程输入
│  │  │  ├─ RemoteSecretPayload.java  # 有界可清零的传输秘密副本，关闭后禁止复用
│  │  │  ├─ RemoteStepResult.java  # 具名固定 Linux 操作产生的已净化结果
│  │  │  └─ restore/  # 受管恢复候选与激活片段
│  │  │     ├─ RemoteRestoreActivationComponent.java  # 描述远端恢复组件的受管身份与激活输入
│  │  │     ├─ RemoteRestoreActivationMode.java  # 区分远端恢复激活操作模式
│  │  │     ├─ RemoteRestoreActivationPort.java  # 提供远端候选恢复激活窄端口
│  │  │     ├─ RemoteRestoreActivationRequest.java  # 绑定恢复激活的应用、组件和候选端口
│  │  │     ├─ RemoteRestoreFilePort.java  # 暂存或丢弃摘要派生隔离文件候选的窄端口
│  │  │     ├─ RemoteRestoreMember.java  # 精确常规成员路径、大小和 SHA-256
│  │  │     ├─ RemoteRestorePortBinding.java  # 描述恢复阶段正式端口与候选端口绑定
│  │  │     ├─ RemoteRestoreStagingEvidence.java  # 隔离、完整性及未触碰当前发布的暂存证据
│  │  │     └─ RemoteRestoreStagingRequest.java  # 绑定本地候选、摘要、字节及精确成员清单的请求
│  │  ├─ runtime/  # 代码来源解析与运行边界
│  │  │  ├─ ExternalApplicationPort.java  # 既有应用发现与生命周期窄端口
│  │  │  ├─ HealthCheckResult.java  # 完整受管部署健康检查策略的结果，而不只是进程检查
│  │  │  └─ LinuxRuntimeExecutor.java  # 受管运行时观测、健康检查和生命周期契约
│  │  ├─ session/  # 0.6 期会话预留
│  │  │  ├─ DeploymentRemoteSession.java  # 组合类型化部署与远程数据库能力的已验证受管会话
│  │  │  └─ LinuxRemoteSession.java  # 仅由有界、类型化 Linux 能力组成的单个已验证远程会话
│  │  ├─ transfer/  # SFTP 源码归档与恢复候选传输包
│  │  │  ├─ DeploymentRemoteTaskScope.java  # 将既有事务候选项关联至调用任务，不暴露执行器
│  │  │  ├─ LinuxSourceTransport.java  # 受限源码传输与候选项清理契约
│  │  │  ├─ RemoteTaskCandidate.java  # 精确任务与候选身份，不接受自由远端路径
│  │  │  ├─ RemoteWorkspace.java  # 仅根据已验证应用 ID 和源码摘要推导的固定服务端路径
│  │  │  └─ SourceUploadResult.java  # 源码归档已到达固定候选工作区的确认结果
│  │  └─ workspace/  # 受管远端源码与补丁边界
│  │     ├─ RemoteProjectPort.java  # 不含构建策略的任务绑定远端源码及隔离输出操作
│  │     └─ RemoteSourcePatch.java  # 排序且不重叠的行编辑
│  ├─ model/  # 任务状态模型
│  │  ├─ agent/  # 任务动作、审批与状态的公共数据契约
│  │  │  ├─ AgentAction.java  # 由可信执行代码绑定的不可变非秘密审核信封
│  │  │  ├─ AgentDecision.java  # 引用精确候选动作的一次部署决策
│  │  │  ├─ AgentDecisionType.java  # 部署 Agent 的受限决策
│  │  │  ├─ AgentObservation.java  # 实际执行器结果，未知结果绝不重放
│  │  │  ├─ AgentReview.java  # 绑定一个精确动作的独立审批
│  │  │  ├─ AgentReviewDecision.java  # 独立审批结果，仅 ALLOW 允许执行
│  │  │  ├─ AgentRiskLevel.java  # 按严重程度排序的本地及审批风险
│  │  │  ├─ AgentTaskCommandAction.java  # 显式进程内任务控制
│  │  │  ├─ AgentTaskState.java  # 不自动恢复写操作的持久化任务状态
│  │  │  └─ AgentToolType.java  # 封闭的类型化部署能力目录
│  │  ├─ ai/  # AI 模型功能
│  │  │  ├─ AiCapabilityType.java  # 分别验证的模型输入能力
│  │  │  ├─ AiModelGroupType.java  # 旧常规及视觉分组的迁移身份，新 App 路由使用独立用途
│  │  │  ├─ AiPurposeAssignment.java  # 一个有序用途成员，位置由所属列表确定
│  │  │  └─ AiPurposeType.java  # 与清单展示顺序无关的独立调用用途
│  │  ├─ analysis/  # 分析证据与运行建议模型包
│  │  │  ├─ AnalysisEvidence.java  # 单个确定性项目事实的紧凑、非秘密来源
│  │  │  ├─ DeploymentAdmissionStatus.java  # 项目进入部署计划前的确定性部署准入结果
│  │  │  ├─ EvidenceConfidenceLevel.java  # 单个确定性事实的置信等级，与任何 AI 建议相互独立
│  │  │  └─ RejectionReason.java  # 项目无法进入受管部署流程的确定性原因
│  │  ├─ archive/  # 安全源码归档生成包
│  │  │  └─ SourceArchiveDescriptor.java  # 本地准备的 tar.gz 源码归档，包含压缩大小和解压后大小边界
│  │  ├─ assessment/  # 项目评估、组件与问题模型包
│  │  │  ├─ ComponentIssue.java  # 一个组件范围的输入要求或硬安全拒绝
│  │  │  ├─ DeploymentProjectAssessment.java  # 将缺失用户决定与硬性安全拒绝分开的部署静态分析结果
│  │  │  └─ MultiComponentProjectAssessment.java  # 具有组件范围原因的确定性混合项目分析
│  │  ├─ capability/  # Linux 主机能力模型包
│  │  │  ├─ EcosystemToolType.java  # 具有精确观测版本的固定语言与构建工具能力类型
│  │  │  ├─ LinuxCapabilityFacts.java  # 用于部署计划的实时主机、运行时与生态工具版本事实
│  │  │  └─ ServerCapabilityFacts.java  # 创建任何受管部署候选项之前从目标主机采集的事实
│  │  ├─ deployment/  # 部署输入与任务提交
│  │  │  ├─ AgentApprovalMode.java  # 强制本地及 AI 审批后的人工确认策略
│  │  │  ├─ AssistedDeploymentAdvice.java  # 关联证据的建议，值仍须确定性验证
│  │  │  ├─ BuildLimitConfiguration.java  # 固定受管部署远程 Maven 构建入口经过审阅的明确限制
│  │  │  ├─ DatabaseReviewMode.java  # 数据库未审阅、明确无数据库或明确引擎的选择
│  │  │  ├─ DeploymentApprovalException.java  # 环境批准与部署审批边界的结构化异常
│  │  │  ├─ DeploymentApprovalFailureType.java  # 审批缺失、失效或不匹配的失败目录
│  │  │  ├─ DeploymentAutomationMode.java  # 控制一次桌面部署中的 AI 参与程度
│  │  │  ├─ DeploymentInputField.java  # 描述有界非秘密缺项、帮助文字和候选
│  │  │  ├─ DeploymentStatus.java  # 受管部署发布事务的准确终态
│  │  │  ├─ DeploymentTraceEvent.java  # 稳定的部署跟踪事件代码；界面模块将这些代码映射为本地化标签
│  │  │  ├─ EnvironmentSetupApproval.java  # 在一个可信目标上安装固定受管部署 Ubuntu 工具集的单次明确确认
│  │  │  ├─ EnvironmentSetupResult.java  # 固定工具集准备成功及其后重新采集的目标能力证据
│  │  │  └─ ReleaseSetDigest.java  # 对依赖有序组件发布身份计算固定域与长度分隔的集合摘要
│  │  ├─ ecosystem/  # 技术生态专属模型包
│  │  │  └─ db/  # Spring 数据源事务与 MyBatis-Plus 持久化
│  │  │     ├─ DatabaseEngineType.java  # 区分 SQL、文档和其他 DB 类型及默认端口
│  │  │     ├─ DatabaseRequirement.java  # 校验非秘密数据库、账号、版本与初始化声明
│  │  │     ├─ DatabaseSchemaReview.java  # 将初始化授权绑定到同一源码检查摘要
│  │  │     ├─ other/  # 其他数据库资源边界
│  │  │     │  └─ RedisKeyNamespace.java  # 限定应用 Redis 键命名空间
│  │  │     ├─ sql/  # 关系型数据库版本约束
│  │  │     │  └─ DatabaseVersionRequirement.java  # 校验 SQL 数据库的显式版本约束
│  │  │     └─ SqliteFileRequirement.java  # SQLite 文件、路径接入、种子与宿主选择
│  │  ├─ failure/  # 跨模块最小结构化失败契约
│  │  │  ├─ FailureCarrier.java  # 仅暴露一个结构化失败描述
│  │  │  ├─ FailureDefinition.java  # 稳定错误码、阶段、消息、严重性与默认恢复动作
│  │  │  ├─ FailureDescriptor.java  # 单次失败、操作标识、安全诊断及实际恢复结果
│  │  │  ├─ FailureRecoveryAction.java  # 保守恢复动作
│  │  │  ├─ FailureRecoveryDisposition.java  # 实际恢复处置结果
│  │  │  ├─ FailureSeverityLevel.java  # 警告、错误与致命级别
│  │  │  └─ OperationIdentity.java  # UUID 形式的无敏感操作标识
│  │  ├─ health/  # HTTP 与 TCP 健康检查模型包
│  │  │  ├─ HealthCheck.java  # 受管部署仅有的两种健康检查策略
│  │  │  └─ UserAccessUrl.java  # 用户声明的已部署应用 HTTP(S) 访问 URL
│  │  ├─ language/  # 语言生态与源码语言事实模型包
│  │  │  ├─ LanguageEcosystemType.java  # 不含主要语言排序的确定性语言生态类型
│  │  │  ├─ LanguageFactKind.java  # 可用于预填审阅表单的稳定确定性语言事实类别
│  │  │  ├─ ProjectLanguageFacts.java  # 在不执行源码且不选择主要语言的情况下收集的确定性语言事实
│  │  │  └─ SourceLanguageType.java  # 由有界文件路径或元数据条目证实的源码语言类型
│  │  ├─ lifecycle/  # 生命周期与接管
│  │  │  ├─ ApplicationAutostartState.java  # 一个应用全部组件的实时自启汇总状态
│  │  │  ├─ ApplicationRuntimeState.java  # 一个应用全部组件的实时运行汇总状态
│  │  │  ├─ AutostartState.java  # 受管单元经过验证的 systemd 启用状态
│  │  │  ├─ DiscoveredApplication.java  # 有界候选状态及可用操作
│  │  │  ├─ ExternalApplicationKind.java  # systemd 与 Docker 既有运行时类型
│  │  │  ├─ ExternalApplicationScan.java  # 扫描候选和部分失败
│  │  │  ├─ ExternalApplicationTarget.java  # 精确运行时身份和扫描指纹
│  │  │  ├─ ExternalScanIssueType.java  # 扫描不完整原因
│  │  │  ├─ LifecycleAction.java  # 受支持的受管部署动作；不存在删除或任意服务动作
│  │  │  ├─ LifecycleObservation.java  # 为受管应用观测到的已验证远端状态
│  │  │  └─ RuntimeState.java  # 服务器实时观测结果，绝不是根据 SQLite 历史记录推断的值
│  │  ├─ managed/  # 受管应用与组件拓扑模型包
│  │  │  ├─ ApplicationUsage.java  # 两端共享的使用命令、端点和操作能力
│  │  │  ├─ ManagedApplication.java  # 由 WindowsToLinux 拥有的应用不可变身份
│  │  │  ├─ ManagedApplicationRuntimeConfiguration.java  # 某个受管应用最后一次成功部署的运行时契约
│  │  │  └─ ManagedStorageLocation.java  # FHS 默认、自定义及未确定位置的精确应用边界
│  │  ├─ message/  # 结构化用户消息模型包
│  │  │  └─ LocalizedMessage.java  # 不将领域代码与显示语言耦合的用户可见消息
│  │  ├─ project/  # 项目类型、运行规格与支持声明包
│  │  │  ├─ application/  # APP 运行声明与配套资源契约
│  │  │  │  ├─ ApplicationCommand.java  # 固定入口和字面参数数组
│  │  │  │  ├─ ApplicationCompanion.java  # 配套 C/C++ 产物及所属发布的环境绑定
│  │  │  │  ├─ ApplicationEndpoint.java  # 经审阅的协议、作用范围与宿主及容器端口
│  │  │  │  ├─ ApplicationInput.java  # 外部只读输入的路径契约
│  │  │  │  ├─ ApplicationWorker.java  # 共享常驻主服务运行身份与存储的前台工作进程
│  │  │  │  └─ ApplicationWorkload.java  # 分类、执行方式、验证、工作目录与资源声明
│  │  │  ├─ component/  # 多组件依赖、端口与数据路径模型包
│  │  │  │  ├─ ComponentDataPath.java  # 一个具有显式模式和访问契约的逻辑持久化数据路径
│  │  │  │  ├─ ComponentIsolationSpecification.java  # 一个组件显式请求的不安全执行能力
│  │  │  │  └─ DeploymentComponent.java  # 混合项目中一个组件的完整静态记录
│  │  │  ├─ DeploymentArchitectureType.java  # 一个精确项目类型与构建工具组合的稳定架构身份
│  │  │  ├─ DeploymentBuildToolType.java  # 固定的目标机构建工具类型，绝不是任意命令行
│  │  │  ├─ DeploymentProjectFacts.java  # 一个用户选定项目的精确架构与不可变确定性事实；未执行任何项目代码
│  │  │  ├─ DeploymentProjectType.java  # 部署计划器考虑的单组件项目类型
│  │  │  ├─ DeploymentRuntimeAssessment.java  # 在不运行项目内容的情况下推导出的、可审阅的源码依据运行时值
│  │  │  ├─ DeploymentRuntimeSpecification.java  # 恰好一个部署单组件项目类型的类型化运行定义
│  │  │  ├─ DeploymentSupportCatalog.java  # 提供每个界面共同使用的已检入支持声明
│  │  │  ├─ DeploymentSupportLevel.java  # 由证据支撑并由分析、计划和结果公开的支持等级
│  │  │  ├─ DeploymentSupportProfile.java  # 一个已分析路径的精确语言、框架、支持等级、验证矩阵与限制
│  │  │  ├─ RuntimeIdentityMode.java  # 历史、systemd 动态与容器非 root 身份策略
│  │  │  ├─ SourceRevision.java  # 用于绑定分析、目标机构建和部署记录的不可变源码身份
│  │  │  └─ ValidatedDeploymentTarget.java  # 由真实产品入口验收证据支撑的一个精确目标组合
│  │  ├─ security/  # 主机安全与防火墙状态模型包
│  │  │  └─ CredentialStorageMode.java  # 用户选择的平台凭据存储机制，其本身绝不是秘密
│  │  ├─ server/  # 服务器配置与观测
│  │  │  ├─ CpuMicroarchitectureLevel.java  # 目标运行时链接器确认的最高累积 x86-64 微架构级别
│  │  │  ├─ LinuxDistroType.java  # 部署为其保留独立证据和兼容性决策的发行版类型
│  │  │  ├─ ManagedHelperProtocolVersion.java  # 能力事实与远端执行共享的受管 helper 规范协议版本
│  │  │  ├─ security/  # 主机安全与防火墙状态模型包
│  │  │  │  ├─ LinuxFirewallKind.java  # 只读探测观测到的主机防火墙管理器
│  │  │  │  ├─ LinuxFirewallState.java  # 观测到的主机防火墙服务状态
│  │  │  │  ├─ LinuxSecurityModuleType.java  # 在不修改状态的情况下观测到的主机强制访问控制实现类型
│  │  │  │  ├─ LinuxSecurityPosture.java  # 作为一个连贯主机观测保留的只读安全与防火墙事实
│  │  │  │  ├─ LinuxSecurityState.java  # 观测到的强制访问控制状态
│  │  │  │  ├─ SelinuxPreparationPlan.java  # 将系统批准绑定到服务器、启动标识及配置摘要
│  │  │  │  └─ SelinuxPreparationState.java  # 经批准系统准备的可恢复观测检查点
│  │  │  └─ ServerIdentity.java  # 目标服务器及其可信 SSH 主机密钥的非秘密身份
│  │  └─ toolchain/  # 工具链声明、分支目录及受管准备职责包
│  │     ├─ ResolvedToolchainSet.java  # 发布级不可变工具身份，不随目录修订重新选择
│  │     ├─ ToolchainBindingCodec.java  # 无配置解析依赖的确定性版本化发布绑定
│  │     ├─ ToolchainEcosystemType.java  # 与支持清单无关的生态版本身份和分支精度
│  │     ├─ ToolchainRequirement.java  # 即使没有构建候选也保留源码声明
│  │     ├─ ToolchainSelectionPolicy.java  # 准备与有界构建重试共用的纯选择规则
│  │     ├─ ToolchainSupportCatalog.java  # 唯一可注入构建分支目录，补丁由官方发布发现提供
│  │     └─ ToolchainVersion.java  # 数字发布身份，解析不查询构建支持目录
│  ├─ source/  # 源码快照、受控浏览、搜索与归档
│  │  ├─ archive/  # 安全源码归档生成包
│  │  │  ├─ SafeSourceArchivePreparer.java  # 创建确定且经过边界检查的纯源码 tar.gz
│  │  │  ├─ SourceArchive.java  # 可复现且经过边界检查的源码归档元数据
│  │  │  ├─ SourceArchiveException.java  # 源码遍历、归档和清理失败的结构化异常
│  │  │  └─ SourceArchiveFailureType.java  # 源码边界、权限、容量、中断与清理失败目录
│  │  ├─ browse/  # 受控源码分页、搜索和读取
│  │  │  ├─ SourceBrowser.java  # 对冻结安全源码清单提供只读分页访问
│  │  │  └─ SourceReadPort.java  # 无执行与写入能力的冻结源码只读接口
│  │  ├─ contract/  # 平台无关交互与数据契约
│  │  │  └─ validation/  # 请求边界与非秘密输入校验
│  │  │     └─ SourceBoundaryValidator.java  # 验证本地源码边界并创建确定性安全文件清单
│  │  ├─ manifest/  # 确定性源码清单模型包
│  │  │  ├─ SourceEntry.java  # 规范化源码快照中的单个不可变普通文件成员
│  │  │  └─ SourceManifest.java  # 为归档准备的确定排序源码成员与排除项
│  │  └─ snapshot/  # 冻结源码身份与摘要
│  │     ├─ SourceDirectorySnapshot.java  # 在分析前冻结所有组件共用的私有源码目录
│  │     ├─ SourceSnapshotAssembler.java  # 写入源码清单的规范 gzip 压缩 ustar 表示
│  │     └─ SourceSnapshotIdentity.java  # 重新验证私有快照成员，不泄露源码内容
│  └─ standard/  # 标准部署聚合工程
│     ├─ analyze/  # 标准静态分析模块
│     │  ├─ component/  # 多组件依赖、端口与数据路径模型包
│     │  │  ├─ ComponentAnalysisRequest.java  # 提供给确定性混合项目分析的用户审阅组件边界
│     │  │  ├─ ComponentGraphValidator.java  # 验证跨组件所有权、依赖、端口、制品及数据路径约束
│     │  │  ├─ DiscoveredProjectComponent.java  # 保存静态发现的组件根目录和候选类型
│     │  │  ├─ MixedProjectInspector.java  # 分析显式组件根，并在修改目标机前拒绝跨组件矛盾
│     │  │  └─ ProjectComponentDiscovery.java  # 发现源码组件并排除构建、依赖和测试目录
│     │  ├─ contract/  # 平台无关交互与数据契约
│     │  │  ├─ policy/  # 静态分析边界与拒绝规则包
│     │  │  │  └─ SourceMutationPolicy.java  # 拒绝可能自动修改受管数据库的源码声明
│     │  │  └─ spi/  # 构建渲染器扩展契约包
│     │  │     ├─ DeploymentTypeAssessment.java  # 由一个类型检查器返回的局部事实与运行时建议
│     │  │     └─ DeploymentTypeInspector.java  # 面向一个用户选定部署类型的单一有界检查器
│     │  ├─ core/  # 部署分析协调与语言汇总包
│     │  │  ├─ DeploymentAnalysisCoordinator.java  # 仅协调有界遍历、安全策略、类型分派与结果汇总
│     │  │  └─ ProjectLanguageInspector.java  # 合并独立语言检查器，不进行排序或选择项目类型
│     │  ├─ ecosystem/  # 技术生态专属模型包
│     │  │  ├─ c/  # C 与 C++ 语言生态分析包
│     │  │  │  ├─ CLanguageInspector.java  # 独立于 CMake 识别 C/C++ 源码与头文件标记
│     │  │  │  └─ cmake/  # CMake 架构分析包
│     │  │  │     ├─ CmakeDeploymentInspector.java  # 静态检查单目标且禁止下载依赖与自定义安装的 CMake 服务
│     │  │  │     └─ CmakeFacts.java  # 保存固定 preset、唯一目标与精确 C/C++ 语言集合
│     │  │  ├─ db/  # 桌面 SQLite v21、事务及应用仓库
│     │  │  │  └─ DatabaseProjectInspector.java  # 读取显式 DB 清单与 JDBC、Redis 声明
│     │  │  ├─ dotnet/  # .NET 语言生态分析包
│     │  │  │  ├─ DotNetLanguageInspector.java  # 独立于构建架构识别 .NET/C# 语言标记
│     │  │  │  └─ dotnetsdk/  # .NET SDK 架构分析包
│     │  │  │     ├─ DotNetSdkDeploymentInspector.java  # 在不执行 dotnet 的情况下检查一个锁定的 .NET Web SDK 服务
│     │  │  │     └─ DotNetSdkFacts.java  # 服务分析使用的固定 .NET SDK 项目元数据
│     │  │  ├─ go/  # Go 语言生态分析包
│     │  │  │  ├─ GoLanguageInspector.java  # 独立于构建架构识别 Go 语言标记
│     │  │  │  └─ gomodule/  # Go Module 架构分析包
│     │  │  │     ├─ GoModuleDeploymentInspector.java  # 在不执行 Go 的情况下检查一个锁定的 Go Module 服务
│     │  │  │     └─ GoModuleFacts.java  # 服务分析使用的固定 Go Module 元数据
│     │  │  ├─ java/  # Java 多构建架构渲染包
│     │  │  │  ├─ gradle/  # Kotlin Gradle 架构分析包
│     │  │  │  │  ├─ GradleBuildFacts.java  # 固定的 Gradle 构建脚本与 Wrapper 事实
│     │  │  │  │  └─ GradleBuildInspector.java  # 读取一个固定 Gradle 构建脚本与 Wrapper 布局，但不调用 Gradle
│     │  │  │  ├─ jar/  # Java JAR 原生交付架构分析包
│     │  │  │  │  ├─ JavaJarDeploymentInspector.java  # 生成 Java JAR 交付事实与清单支持的运行建议
│     │  │  │  │  └─ JavaJarManifestInspector.java  # 读取有界 JAR 清单入口类与版本，不加载类
│     │  │  │  ├─ JavaLanguageInspector.java  # 识别 Java 源码与 JAR 生态标记，不打开归档
│     │  │  │  ├─ jdk/  # JDK 纯 Java 源码构建架构分析包
│     │  │  │  │  ├─ JavaJdkDeploymentInspector.java  # 检查 JDK 源码根、内部类型导入与唯一主类
│     │  │  │  │  └─ JavaJdkFacts.java  # 保存纯 Java 构建的源码根、主类与固定版本事实
│     │  │  │  ├─ maven/  # Maven 构建事实分析包
│     │  │  │  │  ├─ MavenBuildFacts.java  # 框架及部署支持检查所需的根 Maven 事实
│     │  │  │  │  └─ MavenBuildInspector.java  # 读取根 POM 和 Maven 入口，但不调用 Maven 或其 Wrapper
│     │  │  │  └─ SpringBootDeploymentInspector.java  # 在不调用构建的前提下检查一个 Maven 或 Gradle Spring Boot 可执行 JAR 项目
│     │  │  ├─ kotlin/  # Kotlin 多构建架构渲染包
│     │  │  │  ├─ gradle/  # Kotlin Gradle 架构分析包
│     │  │  │  │  ├─ KotlinGradleDeploymentInspector.java  # 在不执行 Gradle 的情况下检查一个锁定的 Kotlin Gradle 应用
│     │  │  │  │  └─ KotlinGradleFacts.java  # 服务分析使用的固定 Kotlin Gradle 应用元数据
│     │  │  │  ├─ kotlinc/  # Kotlin 编译器原生架构分析包
│     │  │  │  │  ├─ KotlinCompilerDeploymentInspector.java  # 检查 kotlinc 源码、内部声明导入与唯一主入口
│     │  │  │  │  └─ KotlinCompilerFacts.java  # 保存精确编译器版本、源码根、主类与制品名
│     │  │  │  ├─ KotlinLanguageInspector.java  # 独立于构建架构识别 Kotlin 语言标记
│     │  │  │  └─ KotlinServiceDeploymentInspector.java  # 在 Gradle 与原生编译器架构之间进行显式选择
│     │  │  ├─ node/  # Node.js 多包管理器构建渲染包
│     │  │  │  ├─ NodeBuildArchitectureFacts.java  # 选中的 Node 包管理器架构及其锁文件
│     │  │  │  ├─ NodeBuildFacts.java  # 来自 package.json 的固定包管理器、锁文件和脚本事实
│     │  │  │  ├─ NodeBuildInspector.java  # 读取 package.json、受支持锁文件和固定脚本名称，但不调用包管理器
│     │  │  │  ├─ NodeLanguageInspector.java  # 从路径与包元数据检测 Node.js、JavaScript 和 TypeScript 事实
│     │  │  │  ├─ NodeServiceDeploymentInspector.java  # 生成 Node 服务构建事实与精确版本运行时建议
│     │  │  │  ├─ npm/  # npm 架构分析包
│     │  │  │  │  └─ NpmBuildInspector.java  # 检查 npm package-lock 架构
│     │  │  │  ├─ pnpm/  # pnpm 架构分析包
│     │  │  │  │  └─ PnpmBuildInspector.java  # 检查 pnpm 锁文件架构
│     │  │  │  └─ yarn/  # Yarn 架构分析包
│     │  │  │     └─ YarnBuildInspector.java  # 检查 Yarn 锁文件架构
│     │  │  ├─ php/  # PHP 多构建架构渲染包
│     │  │  │  ├─ composer/  # Composer 架构分析包
│     │  │  │  │  ├─ PhpComposerDeploymentInspector.java  # 在不执行 PHP 的情况下检查一个 Composer 锁定的 PHP 服务
│     │  │  │  │  └─ PhpComposerFacts.java  # PHP 服务分析使用的固定 Composer 元数据
│     │  │  │  ├─ phpcli/  # PHP CLI 原生架构分析包
│     │  │  │  │  ├─ PhpCliDeploymentInspector.java  # 检查零依赖 PHP 文档根、入口与精确版本
│     │  │  │  │  └─ PhpCliFacts.java  # 保存 PHP CLI 文档根、路由入口与版本事实
│     │  │  │  ├─ PhpLanguageInspector.java  # 独立于构建架构识别 PHP 语言标记
│     │  │  │  └─ PhpServiceDeploymentInspector.java  # 在 Composer 与 PHP CLI 架构之间进行显式选择
│     │  │  ├─ python/  # Python 多依赖架构构建渲染包
│     │  │  │  ├─ pip/  # pip 架构分析包
│     │  │  │  │  └─ PipBuildInspector.java  # 检查 pip 哈希锁定清单并合并多行续行
│     │  │  │  ├─ pipenv/  # Pipenv 架构分析包
│     │  │  │  │  └─ PipenvBuildInspector.java  # 检查 Pipenv 锁文件架构
│     │  │  │  ├─ poetry/  # Poetry 架构分析包
│     │  │  │  │  └─ PoetryBuildInspector.java  # 检查 Poetry 锁文件架构
│     │  │  │  ├─ PythonBuildArchitectureFacts.java  # 保存选中的 Python 依赖架构及其唯一锁文件
│     │  │  │  ├─ PythonBuildFacts.java  # 固定的 Python 项目、精确依赖架构与锁文件事实
│     │  │  │  ├─ PythonBuildInspector.java  # 读取 pyproject.toml 与固定锁文件名称，但不调用 Python
│     │  │  │  ├─ PythonLanguageInspector.java  # 检测 Python 源码、精确版本元数据和唯一模块入口
│     │  │  │  ├─ PythonServiceDeploymentInspector.java  # 生成 Python 构建事实与精确版本/模块运行时建议
│     │  │  │  └─ uv/  # uv 架构分析包
│     │  │  │     └─ UvBuildInspector.java  # 检查 uv 锁文件架构
│     │  │  ├─ ruby/  # Ruby 多构建架构渲染包
│     │  │  │  ├─ bundler/  # Bundler 架构分析包
│     │  │  │  │  ├─ RubyBundlerDeploymentInspector.java  # 在不执行 Ruby 的情况下检查一个 Bundler 锁定的 Rack 服务
│     │  │  │  │  └─ RubyBundlerFacts.java  # Ruby 服务分析使用的固定 Bundler 元数据
│     │  │  │  ├─ rubycli/  # Ruby CLI 原生架构分析包
│     │  │  │  │  ├─ RubyCliDeploymentInspector.java  # 检查零依赖 Ruby 服务入口与精确版本
│     │  │  │  │  └─ RubyCliFacts.java  # 保存 Ruby CLI 服务入口与版本事实
│     │  │  │  ├─ RubyLanguageInspector.java  # 独立于构建架构识别 Ruby 语言标记
│     │  │  │  └─ RubyServiceDeploymentInspector.java  # 在 Bundler 与 Ruby CLI 架构之间进行显式选择
│     │  │  └─ rust/  # Rust 语言生态分析包
│     │  │     ├─ cargo/  # Cargo 架构分析包
│     │  │     │  ├─ RustCargoDeploymentInspector.java  # 在不执行 Rust 工具的情况下检查一个锁定的 Cargo 服务
│     │  │     │  └─ RustCargoFacts.java  # Rust 服务分析使用的固定 Cargo 元数据
│     │  │     └─ RustLanguageInspector.java  # 独立于构建架构识别 Rust 语言标记
│     │  ├─ extension/  # 发行版实现装配功能组
│     │  │  └─ registry/  # 发行版准备目录与注册装配包
│     │  │     └─ DeploymentTypeInspectorRegistry.java  # 持有完整且已验证的源码类型检查器装配
│     │  ├─ preview/  # 不可执行的语言识别预览包
│     │  │  ├─ PreviewInspector.java  # 生成无法进入归档准备或部署的静态识别结果
│     │  │  └─ PreviewLanguageMarkerCatalog.java  # 仅收集尚无独立生态检查器的长尾语言标记
│     │  ├─ service/  # App 模式分流、凭据与持久化及共享能力编排
│     │  │  ├─ ServiceInspectionAssembler.java  # 组装语言无关的服务事实与运行时建议
│     │  │  ├─ ServiceMetadataInspector.java  # 不包含语言特定规则的公共有界元数据操作
│     │  │  └─ ServiceProjectFacts.java  # 单个语言检查器返回的公共有界服务元数据
│     │  ├─ source/  # 源码快照、受控浏览、搜索与归档
│     │  │  ├─ ApplicationBundleInspector.java  # 解析主构建目录和随主程序发布的配套构建单元
│     │  │  ├─ BoundedMetadataInspector.java  # 只在静态检查边界内读取固定项目元数据
│     │  │  ├─ BoundedSourceInspector.java  # 对源码树执行有界只读安全遍历
│     │  │  ├─ ProjectIdentityResolver.java  # 从经审阅元数据或选定根目录推导有界受管项目标识
│     │  │  ├─ SourceInspectionFacts.java  # 在不执行任何项目可控内容的前提下采集的有界事实
│     │  │  └─ SourceLanguageEvidence.java  # 按语言检查器提供的规则收集首个路径证据，不包含具体语言规则
│     │  ├─ toolchain/  # 工具链声明、分支目录及受管准备职责包
│     │  │  └─ ToolchainDeclarationInspector.java  # 不执行构建文件，在发布准入前收集有界版本声明
│     │  └─ workload/  # 容器与静态站点构建形态包
│     │     ├─ ContainerDeploymentInspector.java  # 检测单一 Dockerfile 工作负载、声明端口与受管卷候选项
│     │     └─ StaticWebDeploymentInspector.java  # 区分纯静态内容与由锁文件支持的 Node 静态构建
│     └─ deploy/  # 标准计划、构建、工具链、环境策略与 AI 辅助
│        ├─ assistance/  # 独立只读分析、节点建议及有界恢复
│        │  ├─ AssistedActionModelPort.java  # 用途适配器提供的独立决策及审批上下文
│        │  ├─ AssistedActionSession.java  # 标准动作审批及登记恢复决策会话
│        │  ├─ AssistedAdvicePort.java  # 模块资源与配置
│        │  ├─ AssistedAnalysisDecision.java  # 只读分析动作及结构化建议协议
│        │  ├─ AssistedAnalysisModelPort.java  # 纯源码分析模型窄接口
│        │  ├─ AssistedAnalysisSession.java  # 冻结证据验证、分页读取和有界分析循环
│        │  ├─ AssistedDecisionBudget.java  # 分析与恢复共用的任务模型决策预算
│        │  ├─ AssistedDeploymentAdvisor.java  # 基于证据的分析及节点建议，不持有命令权限
│        │  ├─ AssistedExecutionPort.java  # 已登记动作的窄执行接口
│        │  ├─ AssistedParameterPolicy.java  # 受支持技术参数及候选形状准入
│        │  └─ execution/  # 执行流程
│        │     └─ protocol/  # 辅助模型的严格 JSON 协议
│        │        └─ AssistedAiProtocolClient.java  # 源码分析、节点建议及登记恢复的严格协议
│        ├─ build/  # 目标主机构建协调包
│        │  ├─ BuildCompatibilityPolicy.java  # 仅根据明确兼容性构建诊断判定工具链重试
│        │  ├─ contract/  # 平台无关交互与数据契约
│        │  │  └─ spi/  # 构建渲染器扩展契约包
│        │  │     └─ DeploymentBuildRenderer.java  # 通过固定且有界的目标机构建入口渲染一种经审阅项目类型
│        │  ├─ DeploymentBuildExecutor.java  # 执行由实现渲染并受资源限制的目标机构建
│        │  ├─ ecosystem/  # 技术生态专属模型包
│        │  │  ├─ CargoBuildRenderer.java  # 渲染固定 Cargo 构建架构
│        │  │  ├─ CmakeBuildRenderer.java  # 渲染固定单目标 CMake configure、build 与制品验证
│        │  │  ├─ DotNetSdkBuildRenderer.java  # 渲染固定 .NET SDK 构建架构
│        │  │  ├─ GoBuildRenderer.java  # 渲染固定 Go Module 构建架构
│        │  │  ├─ java/  # Java 多构建架构渲染包
│        │  │  │  ├─ GradleBuildRenderer.java  # 渲染固定 Gradle Wrapper Spring Boot 构建入口
│        │  │  │  ├─ JavaJarBuildRenderer.java  # 渲染经审阅的预构建 Java JAR 边界
│        │  │  │  ├─ JdkBuildRenderer.java  # 使用受控 javac 21 与 jar 构建纯 Java 可执行 JAR
│        │  │  │  ├─ MavenBuildRenderer.java  # 渲染固定系统 Maven Spring Boot 构建入口
│        │  │  │  └─ SpringBootArtifactBuildScript.java  # 为 Maven 与 Gradle 复用唯一 Spring Boot 制品校验脚本
│        │  │  ├─ kotlin/  # Kotlin 多构建架构渲染包
│        │  │  │  ├─ KotlinCompilerBuildRenderer.java  # 使用精确 kotlinc 与 Java 21 构建 Kotlin 可执行 JAR
│        │  │  │  └─ KotlinGradleBuildRenderer.java  # 渲染固定 Kotlin Gradle Wrapper 构建架构
│        │  │  ├─ node/  # Node.js 多包管理器构建渲染包
│        │  │  │  ├─ NodeArchitectureBuildRenderer.java  # 为三个具名 Node Renderer 复用受控服务构建外壳
│        │  │  │  ├─ NpmBuildRenderer.java  # 渲染 npm immutable 安装与固定 build 脚本
│        │  │  │  ├─ PnpmBuildRenderer.java  # 渲染 pnpm frozen-lockfile 安装与固定 build 脚本
│        │  │  │  └─ YarnBuildRenderer.java  # 渲染 Yarn 4 immutable 安装与固定 build 脚本
│        │  │  ├─ php/  # PHP 多构建架构渲染包
│        │  │  │  ├─ ComposerBuildRenderer.java  # 渲染固定 Composer 锁定安装与源码校验
│        │  │  │  └─ PhpCliBuildRenderer.java  # 渲染零依赖 PHP CLI 源码校验与制品准备
│        │  │  ├─ python/  # Python 多依赖架构构建渲染包
│        │  │  │  ├─ PipBuildRenderer.java  # 渲染哈希锁定的 pip 虚拟环境构建
│        │  │  │  ├─ PipenvBuildRenderer.java  # 渲染 Pipenv lock 校验与同步
│        │  │  │  ├─ PoetryBuildRenderer.java  # 渲染 Poetry lock 校验与主依赖同步
│        │  │  │  ├─ PythonArchitectureBuildRenderer.java  # 为四个具名 Python Renderer 复用隔离虚拟环境构建外壳
│        │  │  │  └─ UvBuildRenderer.java  # 渲染校验清单与锁一致的 uv locked 环境同步
│        │  │  └─ ruby/  # Ruby 多构建架构渲染包
│        │  │     ├─ BundlerBuildRenderer.java  # 渲染固定 Bundler 锁定安装与 Rack 制品
│        │  │     └─ RubyCliBuildRenderer.java  # 渲染零依赖 Ruby CLI 源码校验与制品准备
│        │  ├─ extension/  # 发行版实现装配功能组
│        │  │  └─ registry/  # 发行版准备目录与注册装配包
│        │  │     └─ DeploymentBuildRendererRegistry.java  # 为每种支持的项目类型恰好注册一个构建渲染器
│        │  ├─ generation/  # 发行版内容生成功能组
│        │  │  └─ script/  # 通用发行版准备脚本生成包
│        │  │     ├─ BuildConfigurationEnvironmentRenderer.java  # 将经审阅的非秘密构建值渲染为固定 Shell 导出
│        │  │     ├─ CompanionBuildScript.java  # 同一受限候选内的 CMake 配套构建
│        │  │     ├─ NodeDependencyToolPreparation.java  # 准备项目固定包管理器，不改声明或锁文件
│        │  │     ├─ NodePackageBuildScript.java  # 渲染 Node 服务与构建型静态站点共享的包管理器部分
│        │  │     └─ SafeBuildScriptEnvelope.java  # 持有全部渲染器共享的归档校验、解压、限制、日志与构建工具证明
│        │  └─ workload/  # 容器与静态站点构建形态包
│        │     ├─ ContainerBuildRenderer.java  # 渲染固定 Dockerfile 容器镜像构建
│        │     └─ StaticSiteBuildRenderer.java  # 在不假设 Node 版本的情况下渲染纯静态或 Node 构建型站点产物
│        ├─ contract/  # 平台无关交互与数据契约
│        │  ├─ AutomaticDatabasePreparation.java  # 保存经核验的非秘密 DB 配置、秘密引用和初始化证据
│        │  ├─ DeploymentApproval.java  # 按源码、服务器和应用分别绑定的用户批准，绝不可复用
│        │  ├─ DeploymentPlanAction.java  # 部署短停机事务的可审计固定计划动作
│        │  ├─ result/  # 部署与生命周期结果分组包
│        │  │  └─ compatibility/  # 目标主机兼容性结果包
│        │  │     ├─ HostSupportDecision.java  # 带有有序审阅证据的保守主机支持决定
│        │  │     └─ HostSupportStatus.java  # 将实时主机证据与部署矩阵匹配后的保守状态
│        │  ├─ ReviewedDeploymentPlan.java  # 完全确定性的部署事务计划；不包含传输实现或原始命令
│        │  ├─ ReviewedDeploymentRequest.java  # 经过完整审阅的部署输入，包含身份和类型化定义，绝不包含 Shell 命令
│        │  └─ spi/  # 构建渲染器扩展契约包
│        │     └─ DeploymentAdapter.java  # 为一个受支持的部署单组件项目类型生成一个确定性计划
│        ├─ distro/  # 发行版系统准备资源
│        │  ├─ apt/  # APT 公共流程与直接平铺的发行版准备实现
│        │  │  ├─ AptPackageSets.java  # 保存 APT 家族共有软件包集合
│        │  │  ├─ AptSetupRenderer.java  # 独立 Ubuntu 与 Debian 适配器使用的固定 APT 准备机械流程
│        │  │  ├─ DebianSetupRenderer.java  # 持有 Debian 专属的环境准备差异
│        │  │  └─ UbuntuSetupRenderer.java  # 持有 Ubuntu 专属包集合、版本和额外准备选择
│        │  ├─ contract/  # 平台无关交互与数据契约
│        │  │  └─ profile/  # 不可变发行版与生态能力配置包
│        │  │     ├─ DistributionSetupProfile.java  # 单个发行版适配器持有的不可变、防注入事实
│        │  │     └─ EcosystemCapabilityProfile.java  # 保存发行版选择的生态能力配置
│        │  ├─ DistributionSetupRenderer.java  # 根据已采集事实渲染一个固定受支持发行版准备脚本
│        │  ├─ dnf/  # DNF 发行版固定系统准备
│        │  │  ├─ AlmaLinuxSetupRenderer.java  # 持有 AlmaLinux 专属的环境准备差异
│        │  │  ├─ CentosStreamSetupRenderer.java  # 持有 CentOS Stream 专属的环境准备差异
│        │  │  ├─ DnfPackageSets.java  # 按企业 Linux 主版本生成固定 DNF 包集合
│        │  │  ├─ DnfSetupRenderer.java  # 在不共享发行版身份规则的情况下复用的固定 DNF 准备机械流程
│        │  │  ├─ OracleLinuxSetupRenderer.java  # 持有 Oracle Linux 专属的环境准备差异
│        │  │  └─ RockyLinuxSetupRenderer.java  # 持有 Rocky Linux 专属的环境准备差异
│        │  ├─ extension/  # 发行版实现装配功能组
│        │  │  └─ registry/  # 发行版准备目录与注册装配包
│        │  │     ├─ DistributionSetupCatalog.java  # 仅装配六种已实现的具名发行版渲染器
│        │  │     └─ DistributionSetupRegistry.java  # 持有完整且已验证的发行版准备实现装配
│        │  └─ generation/  # 发行版内容生成功能组
│        │     └─ script/  # 通用发行版准备脚本生成包
│        │        ├─ EcosystemCapabilityScriptRenderer.java  # 按生态能力配置生成固定检查脚本且保持证据顺序
│        │        └─ SetupScriptRenderer.java  # 类型化准备渲染器共享的固定 Shell 片段
│        ├─ execution/  # 执行流程
│        │  ├─ environment/  # 目标环境准备服务包
│        │  │  ├─ DatabaseInstanceResolver.java  # 先检查已有实例，处理兼容版本和精确替换授权
│        │  │  └─ NativeDatabasePreparationService.java  # App/Web 共用的原生数据库安装、复用与替换规则
│        │  └─ transaction/  # 部署事务、恢复与回滚协调包
│        │     ├─ DeploymentEventJournal.java  # 将事务真实事件追加到记录并通知进度观察者
│        │     ├─ MultiComponentRecoveryCoordinator.java  # 协调重连、回滚、候选清理与恢复状态
│        │     ├─ MultiComponentTransactionContext.java  # 持有一次经审阅事务中单个组件的可变状态
│        │     ├─ ReviewedComponentDeployment.java  # 一个应用组件的经审阅输入与稳定受管身份
│        │     ├─ ReviewedDeploymentService.java  # 通过与所有受管应用相同的有界事务语义执行一个经审阅、类型专属的部署
│        │     └─ ReviewedMultiComponentDeploymentService.java  # 通过单个已验证类型化会话执行一个整体应用事务
│        ├─ extension/  # 发行版实现装配功能组
│        │  ├─ adapter/  # 项目类型到部署执行器的适配包
│        │  │  ├─ ContainerAdapter.java  # 在策略验证后计划一个 Dockerfile 镜像和一个受管 Docker 或 Podman 容器
│        │  │  ├─ DeploymentPlanFactory.java  # 类型化部署适配器的内部共用顺序
│        │  │  ├─ ServiceDeploymentAdapter.java  # 计划一个由 Profile 选定的普通 systemd 服务，不接受命令字符串
│        │  │  ├─ ServiceDeploymentProfile.java  # 一个普通 systemd 服务项目类型的不可变 Profile
│        │  │  └─ StaticSiteAdapter.java  # 只在验证声明的生成输出目录后计划静态站点发布
│        │  └─ registry/  # 发行版准备目录与注册装配包
│        │     └─ DeploymentAdapterRegistry.java  # 持有完整且已验证的部署适配器装配
│        ├─ input/  # 部署输入脚本片段目录
│        │  ├─ ApplicationDeclaration.java  # 声明文件、桌面和 Web 补填的统一解析
│        │  ├─ AutomaticRuntimeResolver.java  # 从源码补齐运行参数，并为无效高级输入提供可继续的纠错字段
│        │  ├─ DeploymentInputAnswers.java  # 不访问模型地验证分组人工回答
│        │  ├─ DeploymentRuntimeParser.java  # 解析服务层有界运行时、DB 绑定及 Git 引用
│        │  └─ ManagedStoragePreparation.java  # 桌面与 Web 共用存储声明、种子摘要及环境变量交付
│        ├─ plan/  # 经审阅部署计划构建包
│        │  ├─ MultiComponentDeploymentPlanner.java  # 仅在混合项目通过安全准入后生成确定性图顺序
│        │  ├─ ReviewedDeploymentPlanner.java  # 将经审阅的请求路由到唯一能够计划其选定单组件项目类型的适配器
│        │  └─ ReviewedReleaseIdentityResolver.java  # 从可改变构建或运行发布的每项输入推导不可变发布身份
│        ├─ support/  # 目标支持判定协调包
│        │  ├─ distro/  # 发行版系统准备资源
│        │  │  ├─ AlmaLinuxSupportPolicy.java  # 包含明确 x86-64-v2 变体边界的 AlmaLinux 兼容性策略
│        │  │  ├─ CentosStreamSupportPolicy.java  # CentOS Stream 兼容性策略
│        │  │  ├─ DebianSupportPolicy.java  # Debian 稳定版兼容性策略
│        │  │  ├─ DistributionSupportEvaluator.java  # 将已分类发行版路由到恰好一个独立策略
│        │  │  ├─ DistributionSupportPolicy.java  # 单个发行版可独立审阅的兼容性边界
│        │  │  ├─ DistributionSupportRules.java  # 独立发行版策略机械共享的固定检查
│        │  │  ├─ OracleLinuxSupportPolicy.java  # Oracle Linux 滚动主版本兼容性策略
│        │  │  ├─ RockyLinuxSupportPolicy.java  # 固定到受维护小版本的 Rocky Linux 兼容性策略
│        │  │  └─ UbuntuSupportPolicy.java  # Ubuntu LTS 兼容性策略
│        │  ├─ HostSupportEvaluator.java  # 协调对已采集主机事实的公共、发行版与运行时支持检查
│        │  └─ runtime/  # 代码来源解析与运行边界
│        │     ├─ RuntimeCapabilityDecision.java  # 表示一个已审阅运行时是否匹配采集到的主机能力
│        │     └─ RuntimeCapabilityEvaluator.java  # 将一个类型化运行时与已采集的主机工具能力进行匹配
│        ├─ toolchain/  # 工具链声明、分支目录及受管准备职责包
│        │  ├─ ManagedToolchainPreparer.java  # 通过产品自有、有界远程适配器准备官方发布
│        │  ├─ ProjectToolchainRequirements.java  # 审阅输入与原始声明的公共工具链需求投影
│        │  └─ ToolchainBuildEnvironment.java  # 明确可执行路径与选择版本，保留项目语言目标
│        └─ resources/  # 生产资源
│           └─ assistance/  # 独立只读分析、节点建议及有界恢复
│              ├─ analysis.md  # 独立只读分析及真实证据引用协议
│              ├─ assisted.md  # 部署前检查及已知失败解释协议
│              └─ deployment.md  # 部署 Agent 版本化内置流程
└─ web/  # 0.5 期 Spring Boot 服务台与 0.6 期结构预留
   ├─ api/  # Spring MVC 接口、Filter、SSE 与脱敏错误
   │  ├─ account/  # 0.6 期账号预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ audit/  # 0.6 期审计预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ config/  # 配置模型与框架配置
   │  │  └─ WebHttpPolicy.java  # HTTP 容量、请求大小和 SSE 时限
   │  ├─ controller/  # 按业务职责划分的 Spring MVC Controller
   │  │  ├─ WebAiController.java  # Spring MVC 业务请求与响应入口
   │  │  ├─ WebApplicationController.java  # Spring MVC 业务请求与响应入口
   │  │  ├─ WebBackupController.java  # Spring MVC 业务请求与响应入口
   │  │  ├─ WebErrorController.java  # Servlet 错误转发的统一响应
   │  │  ├─ WebHealthController.java  # Spring MVC 业务请求与响应入口
   │  │  ├─ WebPreferenceController.java  # Spring MVC 业务请求与响应入口
   │  │  ├─ WebSecretController.java  # Spring MVC 业务请求与响应入口
   │  │  ├─ WebServerController.java  # Spring MVC 业务请求与响应入口
   │  │  ├─ WebSourceController.java  # Spring MVC 业务请求与响应入口
   │  │  └─ WebTaskController.java  # Spring MVC 业务请求与响应入口
   │  ├─ error/  # 脱敏错误定义
   │  │  ├─ WebErrorResponse.java  # 稳定脱敏错误与关联标识
   │  │  └─ WebExceptionAdvice.java  # 框架和业务异常的脱敏响应
   │  ├─ filter/  # 请求来源、容量限制与安全响应头
   │  │  ├─ BoundedWebRequest.java  # 已知及分块 JSON 请求体大小限制
   │  │  └─ WebRequestFilter.java  # 回环同源检查、并发配额与安全响应头
   │  ├─ release/  # 发布身份与 0.6 期发布预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ workspace/  # 受管远端源码与补丁边界
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  └─ resources/  # 生产资源
   │     └─ openapi.json  # 0.5 期请求、任务与事件接口契约
   ├─ auth/  # Web 身份认证职责预留模块
   │  ├─ authorization/  # 0.6 期授权预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ csrf/  # 0.6 期 CSRF 预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ identity/  # 0.6 期身份预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ initialization/  # 0.6 期初始化预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ login/  # 0.6 期登录预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ ratelimit/  # 0.6 期速率限制预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  └─ session/  # 0.6 期会话预留
   │     └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   ├─ db/  # Spring 数据源事务与 MyBatis-Plus 持久化
   │  ├─ config/  # 配置模型与框架配置
   │  │  └─ WebPersistenceConfiguration.java  # 事务、实体及 Mapper/XML 扫描配置
   │  ├─ entity/  # 持久化记录
   │  │  ├─ ResourceScope.java  # 工作区与用户上下文
   │  │  ├─ ResourceType.java  # 封闭的业务资源类型
   │  │  ├─ StoredResource.java  # 带归属和乐观版本的资源
   │  │  ├─ StoredTask.java  # 持久化任务视图
   │  │  ├─ StoredTaskEvent.java  # 单调序号的任务事件
   │  │  ├─ WebAiProfileEntity.java  # 0.5 期业务表持久化实体
   │  │  ├─ WebApplicationEntity.java  # 0.5 期业务表持久化实体
   │  │  ├─ WebBackupEntity.java  # 0.5 期业务表持久化实体
   │  │  ├─ WebPreferenceEntity.java  # 0.5 期业务表持久化实体
   │  │  ├─ WebResourceEntity.java  # 资源的作用域、版本与公共持久化字段
   │  │  ├─ WebSecretEntity.java  # 0.5 期业务表持久化实体
   │  │  ├─ WebServerEntity.java  # 0.5 期业务表持久化实体
   │  │  ├─ WebSourceEntity.java  # 0.5 期业务表持久化实体
   │  │  ├─ WebTaskDecisionEntity.java  # 0.5 期业务表持久化实体
   │  │  ├─ WebTaskEntity.java  # 0.5 期业务表持久化实体
   │  │  └─ WebTaskTargetEntity.java  # 0.5 期业务表持久化实体
   │  ├─ execution/  # 执行流程
   │  │  └─ migration/  # 版本化迁移
   │  │     └─ WebSchemaMigrator.java  # 事务性 Web schema 迁移及完整性检查
   │  ├─ persistence/  # 数据编解码职责组
   │  │  ├─ mapper/  # 显式作用域 SQL 与 MyBatis-Plus Mapper
   │  │  │  ├─ ResourceRevisionMapper.java  # 资源版本条件更新的 Mapper 契约
   │  │  │  ├─ WebAiProfileMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebApplicationMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebBackupMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebConfigurationMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebPreferenceMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebScopeMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebSecretMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebServerMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebSourceMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebTaskDecisionMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebTaskJournalMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  ├─ WebTaskMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  │  └─ WebTaskTargetMapper.java  # 工作区完整键约束下的业务数据访问
   │  │  └─ repository/  # 作用域仓库
   │  │     ├─ ScopeAccess.java  # 工作区有效成员访问检查
   │  │     ├─ WebResourceRepository.java  # 按工作区查询和乐观版本更新资源
   │  │     ├─ WebSecretRepository.java  # 作用域内不透明加密材料持久化
   │  │     └─ WebTaskRepository.java  # 任务状态、事件、步骤及用户决定持久化
   │  ├─ runtime/  # 代码来源解析与运行边界
   │  │  └─ WebStorageLocation.java  # 锚定 DB 模块的 CLASS/JAR 数据根与固定文件布局
   │  ├─ WebPersistence.java  # 独立 Web SQLite 与内部作用域初始化
   │  └─ resources/  # 生产资源
   │     ├─ mapper/  # 显式作用域 SQL 与 MyBatis-Plus Mapper
   │     │  ├─ WebAiProfileMapper.xml  # 作用域及版本条件明确的更新 SQL
   │     │  ├─ WebApplicationMapper.xml  # 作用域及版本条件明确的更新 SQL
   │     │  ├─ WebBackupMapper.xml  # 作用域及版本条件明确的更新 SQL
   │     │  ├─ WebServerMapper.xml  # 作用域及版本条件明确的更新 SQL
   │     │  ├─ WebSourceMapper.xml  # 作用域及版本条件明确的更新 SQL
   │     │  └─ WebTaskMapper.xml  # 作用域及版本条件明确的更新 SQL
   │     ├─ schema/  # 版本化数据库定义
   │     │  └─ 001-web.sql  # 26 张业务与 0.6 期预留表、复合外键和索引
   │     └─ sql-error-codes.xml  # SQLite 约束与锁错误的 Spring 分类
   ├─ file/  # 流式上传、配额与受管工作区
   │  ├─ quota/  # 上传和存储配额
   │  │  └─ UploadQuota.java  # 内部上传大小、总量和成员限制
   │  ├─ upload/  # 流式上传与归档边界
   │  │  └─ SourceArchiveUpload.java  # 有界 ZIP/TAR 解包与成员安全检查
   │  └─ workspace/  # 受管远端源码与补丁边界
   │     ├─ WebWorkspace.java  # 归属标记、路径边界、配额及精确清理
   │     └─ WorkspaceAddress.java  # 受管资源目录的作用域地址
   ├─ frontend/  # Vue、TypeScript 与 Vite 前端工程
   │  ├─ index.html  # 前端逻辑与构建配置
   │  ├─ package-lock.json  # 前端逻辑与构建配置
   │  ├─ package.json  # 前端逻辑与构建配置
   │  ├─ playwright.boot.config.ts  # 前端逻辑与构建配置
   │  ├─ playwright.config.ts  # 前端逻辑与构建配置
   │  ├─ scripts/  # 前端功能与组件目录
   │  │  └─ playwright-local.mjs  # 前端逻辑与构建配置
   │  ├─ src/  # 前端功能与组件目录
   │  │  ├─ App.vue  # Vue 界面组件
   │  │  ├─ env.d.ts  # 前端逻辑与构建配置
   │  │  ├─ features/  # 前端功能与组件目录
   │  │  │  ├─ account/  # 前端功能与组件目录
   │  │  │  │  └─ README.md  # 前端逻辑与构建配置
   │  │  │  ├─ ai/  # 前端功能与组件目录
   │  │  │  │  └─ AiPage.vue  # Vue 界面组件
   │  │  │  ├─ application/  # 前端功能与组件目录
   │  │  │  │  └─ ApplicationsPage.vue  # Vue 界面组件
   │  │  │  ├─ backup/  # 前端功能与组件目录
   │  │  │  │  └─ BackupPage.vue  # Vue 界面组件
   │  │  │  ├─ deployment/  # 前端功能与组件目录
   │  │  │  │  └─ DeploymentPage.vue  # Vue 界面组件
   │  │  │  ├─ release/  # 前端功能与组件目录
   │  │  │  │  └─ README.md  # 前端逻辑与构建配置
   │  │  │  ├─ server/  # 前端功能与组件目录
   │  │  │  │  └─ ServerPage.vue  # Vue 界面组件
   │  │  │  ├─ task/  # 前端功能与组件目录
   │  │  │  │  └─ TasksPage.vue  # Vue 界面组件
   │  │  │  ├─ website/  # 前端功能与组件目录
   │  │  │  │  └─ README.md  # 前端逻辑与构建配置
   │  │  │  └─ workspace/  # 前端功能与组件目录
   │  │  │     └─ README.md  # 前端逻辑与构建配置
   │  │  ├─ main.ts  # 前端逻辑与构建配置
   │  │  ├─ shared/  # 前端功能与组件目录
   │  │  │  ├─ api/  # 前端功能与组件目录
   │  │  │  │  └─ client.ts  # 前端逻辑与构建配置
   │  │  │  ├─ i18n/  # 前端功能与组件目录
   │  │  │  │  ├─ execution.ts  # 前端逻辑与构建配置
   │  │  │  │  ├─ messages.ts  # 前端逻辑与构建配置
   │  │  │  │  └─ workbench.ts  # 前端逻辑与构建配置
   │  │  │  └─ ui/  # 前端功能与组件目录
   │  │  │     └─ AppIcon.vue  # Vue 界面组件
   │  │  └─ style.css  # 前端逻辑与构建配置
   │  ├─ tsconfig.app.json  # 前端逻辑与构建配置
   │  ├─ tsconfig.json  # 前端逻辑与构建配置
   │  ├─ tsconfig.node.json  # 前端逻辑与构建配置
   │  └─ vite.config.ts  # 前端逻辑与构建配置
   ├─ main/  # Spring Boot 入口及配置装配
   │  ├─ config/  # 配置模型与框架配置
   │  │  └─ WebRuntimeProperties.java  # 严格绑定 YAML 配置与运行策略
   │  ├─ runtime/  # 代码来源解析与运行边界
   │  │  └─ WebInstanceLease.java  # 独立数据根归属和单实例锁
   │  ├─ startup/  # 应用装配与启动清理
   │  │  ├─ WebServiceConfiguration.java  # 业务依赖注入、内部主体与启动恢复
   │  │  └─ WebStorageConfiguration.java  # 目录锁、密钥和 SQLite 连接池生命周期
   │  ├─ WebMain.java  # 标准 Spring Boot 无参入口
   │  └─ resources/  # 生产资源
   │     └─ application.yml  # 唯一运行配置与默认值
   ├─ secret/  # 独立主密钥与凭据加解密
   │  ├─ credential/  # 作用域凭据访问
   │  │  └─ WebCredentialStore.java  # 作用域与用途绑定的加密秘密及不可变修订
   │  ├─ crypto/  # 认证加密与备份秘密格式
   │  │  └─ WebSecretCipher.java  # AES-GCM 作用域认证加密
   │  ├─ masterkey/  # 测试主密钥生命周期
   │  │  ├─ WebMasterKey.java  # 内存主密钥与关闭清零
   │  │  └─ WebMasterKeyStore.java  # 当前用户权限、持久密钥标识与生成复用
   │  └─ password/  # 0.6 期密码学边界预留
   │     └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   ├─ service/  # Web 业务服务与共享规则组合
   │  ├─ account/  # 0.6 期账号预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ ai/  # AI 模型功能
   │  │  └─ WebAiService.java  # 固定模型测试、排序及有界候选建议
   │  ├─ audit/  # 0.6 期审计预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ backup/  # 备份恢复迁移
   │  │  ├─ WebBackupCollection.java  # 停止写入后收集精确材料并生成共享归档
   │  │  ├─ WebBackupRestore.java  # 共享候选恢复与成功图登记
   │  │  ├─ WebBackupService.java  # 备份上传下载、创建、恢复和迁移入口
   │  │  ├─ WebOfflineMigration.java  # 双目标离线迁移与失败源端恢复
   │  │  └─ WebRestoreMaterial.java  # 校验归档、运行身份和精确秘密修订
   │  ├─ config/  # 配置模型与框架配置
   │  │  └─ WebApplicationSecrets.java  # 应用环境秘密修订和交互秘密引用
   │  ├─ contract/  # 平台无关交互与数据契约
   │  │  ├─ ManagedWebComponent.java  # 携带精确发布身份和恢复输入的受管组件
   │  │  ├─ ManagedWebGraph.java  # 依赖有序的持久化应用拓扑
   │  │  ├─ OperationCompletionState.java  # 业务操作最终状态
   │  │  ├─ PreparedWebOperation.java  # 已解析的任务输入、目标锁与执行器
   │  │  ├─ TaskInteraction.java  # 进度、取消与持久化用户决定
   │  │  ├─ validation/  # 请求边界与非秘密输入校验
   │  │  │  └─ WebRequestValidator.java  # 字段白名单、文本及非秘密请求校验
   │  │  └─ WebRequestContext.java  # 服务端提供的用户和空间上下文
   │  ├─ deployment/  # 部署输入与任务提交
   │  │  ├─ WebDatabaseService.java  # 共享原生数据库准备与 Web 凭据交互
   │  │  ├─ WebDeploymentInputs.java  # 确定性分析、AI 辅助与用户补全
   │  │  └─ WebDeploymentService.java  # 复用共享单组件与多组件自动部署
   │  ├─ execution/  # 执行流程
   │  │  └─ lifecycle/  # 生命周期与接管
   │  │     └─ WebApplicationInventory.java  # 应用图、卡片、生命周期与外部应用接管
   │  ├─ interaction/  # 任务确认和交互适配
   │  │  └─ WebTaskInteractionService.java  # 任务确认、输入补全和进度交互适配
   │  ├─ persistence/  # 数据编解码职责组
   │  │  └─ serialization/  # 持久化与接口数据序列化
   │  │     └─ WebJsonCodec.java  # 严格 JSON 编解码和对象转换
   │  ├─ release/  # 发布身份与 0.6 期发布预留
   │  │  └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   │  ├─ server/  # 服务器配置与观测
   │  │  └─ WebServerService.java  # 服务器资料、固定主机指纹与新鲜观测
   │  ├─ source/  # 源码快照、受控浏览、搜索与归档
   │  │  └─ WebSourceService.java  # 上传及 Git 快照冻结、复验和静态分析
   │  ├─ WebApplicationService.java  # Web 用例入口及排队目标固定
   │  └─ workspace/  # 受管远端源码与补丁边界
   │     └─ package-info.java  # 0.6 期职责说明，仅预留，不提供可调用实现
   └─ task/  # 持久化任务调度与恢复
      ├─ model/  # 任务状态模型
      │  ├─ TaskState.java  # 持久化任务状态集合
      │  └─ WebTaskPolicy.java  # 任务并发、事件上限和决定及关闭时限
      └─ scheduler/  # 任务并发与交互调度
         └─ WebTaskScheduler.java  # 持久化任务排队、互斥、取消、决定与重启重新验证
```

## APP 安全改造新增节点

- `app/db/resources/schema/desktop.sql`：v21 的 19 张表及约束。
- `app/secret/CredentialVault.java`、`app/secret/crypto/PasswordKeyCrypto.java`：统一凭据库、密码验证、密钥包装及引用计数句柄。
- `app/service/contract/security`：`SecurityApplicationFacade`、`DesktopTaskAuthorization`、`DesktopSecurityState`，不向 UI 暴露密钥。
- `app/service/security/DesktopSecurityService.java`：会话、锁定、任务接纳与安全变更互斥。
- `app/service/contract/definition/DeploymentPreferences.java`：持久化部署模式与审批选择。
- `app/ui/shell/DesktopHeader.java`：独立页头布局，控制窗口外壳职责大小。
- `app/ui/shell/DesktopLockController.java`：主窗口及从属窗口的锁定层与键盘门禁。
- `app/ui/setting/SecuritySettingsPane.java`：主密码与全局凭据加密设置。

APP 数据库不再维护迁移链或旧结构读取分支；Web 节点保持原实现，后续同步。

- `app/windows/window/WindowPrivacy.java` 与 `window-privacy.ps1`：仅隐藏/恢复归属经过校验的 APP 隔离 Chromium 原生窗口。

## 1.1 已落地新增节点

以下节点均已存在于活动源码；只说明文件职责，不代表完整模块切换或全部 API 宿主接线已经完成。

- `appapi/ai/AiImage.java`：视觉请求使用的不可变图像字节。
- `appapi/ai/AiMessage.java`：包含独立图像附件列表的有序模型消息。
- `appapi/ai/AiPurpose.java`：平台调用选用的配置模型组。
- `appapi/ai/AiRequest.java`：具有明确时间及输出预算的模型调用。
- `appapi/ai/AiResponse.java`：不暴露提供方实现类型的模型响应及用量。
- `appapi/ai/AiRole.java`：有序模型对话中的消息角色。
- `appapi/ai/AiService.java`：绑定调用方并校验任务授权及预算的模型能力。
- `appapi/ai/AiUsage.java`：提供方可选报告的非负令牌用量。
- `appapi/db/ModDatabase.java`：绑定任务并访问当前所有者数据代次的连接能力。
- `appapi/db/ModSchemaLifecycle.java`：业务启动前由作者实现的初始化和只读验证。
- `appapi/db/SchemaAccess.java`：初始化与迁移期间绑定操作的连接能力。
- `appapi/db/SchemaIdentity.java`：与唯一代次绑定的数据所有者及版本。
- `appapi/db/SchemaMigration.java`：从受保护来源到独立目标的作者迁移逻辑。
- `appapi/db/ValidationReport.java`：作者对数据结构作出接受或拒绝判断的证据。
- `appapi/linux/CommandRequest.java`：带独立输入及输出上限的精确远端脚本。
- `appapi/linux/CommandResult.java`：不推断未观察退出状态的远端输出。
- `appapi/linux/LinuxService.java`：绑定任务授权及主机信任校验的 SSH 能力。
- `appapi/linux/RemoteFacts.java`：已观察的远端平台与认证主机指纹。
- `appapi/linux/RemoteActions.java`：作者与宿主共用的精确命令、输入摘要及文件传输审核描述。
- `appapi/linux/RemoteSession.java`：产生副作用时需要已审核授权的受管 SSH 会话。
- `appapi/linux/TransferRequest.java`：具有明确远端路径及容量限制的文件传输。
- `appapi/linux/TransferResult.java`：已观察的传输大小及内容摘要。
- `appapi/linux/TransferCheckpoint.java`：原任务的持久传输描述，供精确续传或丢弃审核；执行前由宿主重读原事实。
- `appapi/linux/TransferProgress.java`：原传输当前物理阶段及已完成字节，进度比例仅针对已知的本阶段范围。
- `appapi/linux/TransferStall.java`：实际尝试停止后的原路径、方向、最后进度及停滞阈值。
- `appapi/linux/TransferStallChoice.java`：明确重试或终止，终止不推断未确认文件已回滚。
- `appapi/mod/ModContext.java`：绑定真实启动插件身份的平台能力集合。
- `appapi/mod/ModEntrypoint.java`：作者生命周期入口及部分启动失败的清理责任。
- `appapi/mod/ModIdentity.java`：独立于显示名和包文件名的稳定插件身份。
- `appapi/mod/ModLaunchMode.java`：正常启动或对未完成工作的隔离核对模式。
- `appapi/mod/ModServices.java`：使用共享公开类型身份的显式依赖服务。
- `appapi/mod/ServiceCall.java`：不得泄漏提供方资源的同步跨插件调用。
- `appapi/mod/ServiceHandle.java`：每次调用时获取占用的服务发现句柄。
- `appapi/mod/ServiceRegistration.java`：仅在实际释放后撤销的服务注册。
- `appapi/mod/StopContext.java`：后台幂等停止所使用的原因上下文。
- `appapi/mod/StopReason.java`：不代表资源已经释放的明确生命周期停止原因。
- `appapi/secret/SecretConsumer.java`：输入在使用后清零的短期秘密回调。
- `appapi/secret/SecretRef.java`：绑定授权用途的不透明秘密引用。
- `appapi/secret/SecretReference.java`：保护共享凭据的持久业务引用。
- `appapi/secret/SecretService.java`：仅经授权临时回调提供所属秘密的服务。
- `appapi/server/ReferenceResolution.java`：业务提交已经核实或仍然未知的结果。
- `appapi/server/ReferenceState.java`：持续保护未知引用的预留状态。
- `appapi/server/ServerInfo.java`：不含秘密的服务器配置与已观察信任信息。
- `appapi/server/ServerRef.java`：平台管理服务器的稳定引用。
- `appapi/server/ServerReference.java`：保护共享服务器的业务归属预留。
- `appapi/server/ServerService.java`：绑定任务的服务器查询及持久引用管理。
- `appapi/source/SnapshotRequest.java`：明确的相对文件集合及总复制容量预算。
- `appapi/source/SourceFile.java`：从已授权来源取得的独立字节及摘要。
- `appapi/source/SourceReadRequest.java`：限制在已选来源根目录内的有界读取。
- `appapi/source/SourceSelection.java`：用户批准的不透明来源选择。
- `appapi/source/SourceService.java`：用户选定来源的有界读取及受管不可变快照。
- `appapi/source/SourceSnapshot.java`：实际关闭后释放的受管不可变来源目录。
- `appapi/task/ActionIntent.java`：绑定任务资源的精确待审核副作用与修订。
- `appapi/task/ApiException.java`：报告未接纳的请求或失败的本地能力访问。
- `appapi/task/ApprovalGrant.java`：授权效力由宿主核实的不透明审批标识。
- `appapi/task/CancellationToken.java`：不强制中断执行的协作取消请求。
- `appapi/task/InteractionService.java`：绑定已接纳任务的宿主审核及输入交互。
- `appapi/task/LogCategory.java`：包含可选作者日志表的所属诊断类别。
- `appapi/task/LogEvent.java`：不得包含凭据材料的诊断事件。
- `appapi/task/LogLevel.java`：独立于业务操作状态的诊断级别。
- `appapi/task/LogService.java`：明确返回存储结果的所属结构化诊断服务。
- `appapi/task/LogStorage.java`：不猜测持久化成功的实际诊断存储结果。
- `appapi/task/LogTarget.java`：当前日志所有者的不透明已登记目的地。
- `appapi/task/LogWriteResult.java`：已观察的诊断持久化与截断结果。
- `appapi/task/ManagedProcess.java`：直到实际退出前持续保留占用的协议进程。
- `appapi/task/OperationResult.java`：失败或不确定时具有明确证据的业务结果。
- `appapi/task/Outcome.java`：独立于尚待完成资源清理的业务结果。
- `appapi/task/PageSlice.java`：独立查询结果集合及可选的后续游标。
- `appapi/task/Problem.java`：不暴露实现异常的结构化失败或不确定原因。
- `appapi/task/ProblemCode.java`：公开能力边界使用的稳定失败类别。
- `appapi/task/ProcessMessage.java`：传输前需要校验 JSON 对象的协议消息。
- `appapi/task/ProcessService.java`：不暴露平台凭据的任务所属进程启动。
- `appapi/task/ProcessSpec.java`：字面进程参数及有界握手配置。
- `appapi/task/RecoveryRegistration.java`：允许人工核对保留任务的恢复注册。
- `appapi/task/ResourceLease.java`：仅在实际清理后释放的受追踪资源占用。
- `appapi/task/TaskContext.java`：已接纳任务的身份及协作资源登记。
- `appapi/task/TaskHandle.java`：仅在必要清理完成后结束的可观察任务结果。
- `appapi/task/TaskRecovery.java`：复用原资源且不重放未知副作用的结果核对。
- `appapi/task/TaskRequest.java`：包含完整初始服务器资源集合的任务准入请求。
- `appapi/task/TaskService.java`：绑定调用方的任务准入、观察与核对服务。
- `appapi/task/TaskSnapshot.java`：终态结果保持一致的不可变任务状态。
- `appapi/task/TaskState.java`：区分取消请求与业务结果的任务进度状态。
- `appapi/task/TaskWork.java`：观察取消并保留未完成资源的作者任务逻辑。
- `appapi/ui/ModPage.java`：在事件分发线程创建和释放的作者 Swing 视图。
- `appapi/ui/PageFactory.java`：页面重新打开时创建新视图的惰性工厂。
- `appapi/ui/PageRegistration.java`：关闭时释放已打开视图的所属页面注册。
- `appapi/ui/PageSpec.java`：不会自动固定主导航的本地页面定义。
- `appapi/ui/UiEnvironment.java`：当前宿主语言、主题及锁定状态。
- `appapi/ui/UiService.java`：不暴露宿主实现类型的所属页面挂载服务。
- `app/db/persistence/connection/PlatformDatabase.java`：持有唯一嵌入式 H2 引擎及受保护引导材料。
- `appapi/resources/gold/debug/windowstolinux/appapi/build.properties`：由 Maven 展开实际公开 API 版本，供宿主管理的兼容校验读取。
- `app/db/PlatformPersistence.java`：在受保护 H2 引擎上装配平台所属配置仓库。
- `app/db/bootstrap/DatabaseBootstrap.java`：持有受保护引导字节，不将缺失或损坏状态转换为新数据库。
- `app/db/bootstrap/DatabaseProtection.java`：宿主注入的当前用户保护能力，独立于数据库实现。
- `app/db/mod/ModActivationLedger.java`：按原启动及发布版本记录实际启用、Schema 使用和最终关闭，保护用户意图及未结束资源。
- `app/db/mod/ModRecoveryLedger.java`：在既有 RECONCILE 操作中保留原恢复图和旧运行凭据；实际会话关闭后持久 VERIFIED，原摘要与旧状态确认同事务完成，保留未应用重启意图。
- `app/db/mod/ModSchemaReconciliation.java`：核验原 UNKNOWN 数据身份、取得明确只读核对并原子归档已证明结果，不改变用户选择。
- `app/db/mod/ModRestartLedger.java`：核验前次启动意图与明确失败重试，登记原准备/新代次并按实际状态原子归档，保留重启停用确认。
- `app/db/mod/ModDeletionLedger.java`：共用单版本与整 Mod 删除账本；捕获版本、Schema、所属诊断、导航及非共享自有凭据，核验实际文件缺失后按原阶段清理并归档；整 Mod 文件服务与默认管理入口均已接。
- `app/db/mod/ModReinstallationLedger.java`：保存同版本程序修复的原范围、有序替换及明确恢复检查点，原子提交版本状态/原包摘要和固定终态；物理文件服务与默认重装/恢复入口均已接入。
- `app/db/mod/ModProgramMaintenance.java`：删除与重装共用的已确认停用、任务/资源排空及启用依赖检查。
- `app/db/mod/ModNavigationPreferences.java`：共用严格导航偏好读写，移除精确所有者条目时保留其余条目顺序，损坏内容不覆盖。
- `app/db/persistence/connection/ManagedJdbcConnection.java`：追踪 JDBC 所有权，不暴露物理驱动句柄。
- `app/db/persistence/connection/RepositoryConnections.java`：在嵌套平台仓库事务间共享一个所属连接。
- `app/db/persistence/repository/ServerReferenceRepository.java`：按任务列出服务器观测，并事务化保存精确 Mod/Schema 代次的业务引用。
- `app/db/persistence/repository/CredentialReferenceRepository.java`：签发 Mod/用途所属凭据身份，并事务化保护精确数据代次的业务引用。
- `app/db/schema/SchemaAccessScope.java`：持有准备阶段连接及可选的只读验证凭据。
- `app/db/schema/SchemaCoordinates.java`：将精确逻辑身份编码为有界的 H2 数据结构及所有者名称。
- `app/db/schema/SchemaProvisioner.java`：创建明确登记的数据结构，或按独立重试记录重置从未可用的失败目标；核验实际所有权及会话。
- `app/db/schema/SchemaValidation.java`：以只读身份复核实际表和 Domain 约束，生成结构验证凭据。
- `app/db/schema/SchemaSnapshot.java`：按固定 H2 引擎对来源实际结构及数据求摘要，核对迁移前后保全。
- `app/db/task/BrowserRecoveryArchival.java`：核对原宿主任务/服务器，保存固定救援摘要，并在中央任务事务内清理所属救援事件与状态；拒绝迟到写入。
- `app/db/task/TaskLedger.java`：独立于业务 SQL 持久化原子准入及实际资源释放；固定任务摘要与执行清理同事务，原归档凭据支持仅元数据重试及跨启动收尾。
- `app/db/task/TaskRecoveryGraph.java`：保留任务准入时原所有者及全部传递提供方的版本、完整代码摘要与可选 Schema 代次；严格校验持久字段，不从当前单选补造原图。
- `app/db/task/ResourceRecovery.java`：显式资源的有界恢复事实封套，严格拒绝损坏或未知格式。
- `app/db/task/TaskResource.java`：由平台适配器确定归属的内部资源申请。
- `app/db/resources/gold/debug/windowstolinux/app/db/schema/platform-v1.sql`：H2 平台首版表与约束。
- `app/linux/ssh/SshConnector.java`：明确凭据与指纹授权下的独立 SSH 连接、认证、取消及实际资源释放。
- `app/linux/ssh/SshCommandExecution.java`：一次 SSH 命令发送、有界等待、退出证据及未知结果处理，保留尚未释放的通道和发送线程。
- `app/linux/ssh/SshCommandFrame.java`：固定 Bash 引导与长度帧，在标准输入上传送脚本及原始二进制输入。
- `app/linux/ssh/SshOutputCapture.java`：合并限制标准输出和错误输出的保留容量，超限后继续排空。
- `app/linux/ssh/SftpTransfer.java`：普通文件的临时传输、摘要与容量核对、校验后发布及回读，保留不确定结果及未释放资源。
- `app/linux/ssh/SshEndpoint.java`：平台保存的服务器标识及精确连接端点。
- `app/main/startup/PlatformRuntime.java`：为一次桌面启动持有平台存储、中央任务执行器及安全会话，并核对实际关闭。
- `app/main/mod/ModSchemaInspection.java`：持有原版本只读验证器、来源核验和资源收尾，核对不重放迁移或启动业务。
- `app/main/mod/ModSchemaPreparation.java`：共用首次准备与明确重试的隔离初始化/多步迁移、来源核验、目标验证及原资源收尾。
- `app/main/mod/PlatformModRecovery.java`：在普通启动前核验原代码及数据图、合并共享提供方并装配隔离恢复会话；保留失败资源，整组任务结束后逆序释放，当前启动不热切换业务。
- `app/main/mod/PlatformModCoordinator.java`：按实际 CLASS 构建装配依赖图，协调明确数据核对/重试，保留原资源及归档义务并逆序停止。
- `app/main/resources/gold/debug/windowstolinux/app/main/build.properties`：由 Maven 展开实际应用版本，供宿主任务及恢复核验。
- `app/mod/development/DevelopmentDescriptor.java`：生成并核对 CLASS 编译来源、明示构建输入摘要及输出标记，不重新解析 Maven 依赖。
- `app/mod/development/MavenDevelopmentInputs.java`：在 Maven 原构建会话中撤销旧描述，捕获真实解析的依赖、从模块和构建配置输入。
- `app/mod/development/DevelopmentModDiscovery.java`：在明示工作区发现开发 Mod，保留逐来源错误并拒绝重复身份，不执行作者入口。
- `app/mod/installation/ModPackageTool.java`：独立 Java 构建命令，当前执行 CLASS describe，复用正式开发描述校验并输出有界输入的结构化结果。
- `app/mod/installation/ModPackageReader.java`：核验原包布局、实际外层字节、两类摘要及嵌套 JAR 类归属；审阅后重验再展开到新暂存目录，并可在原包不存在时按保留证据核验完整展开树；支持按登记摘要核验安装目录并返回有序代码来源，本读取器不登记安装。
- `app/mod/installation/ModZip.java`：在分配条目前检查 ZIP/ZIP64 中央目录，核对原路径、本地头与有界嵌套视图，拒绝链接及冲突。
- `app/mod/resources/META-INF/maven/plugin.xml`：登记 development-inputs 构建目标及原 Maven 会话注入，不注册业务 Mod。
- `app/mod/lifecycle/ModRuntimeSession.java`：启用一个已准备的 Mod，先记录启动再调用作者；原会话保留至物理清理与最终持久确认完成。
- `app/mod/lifecycle/ModDependencyPlan.java`：校验启用选择的依赖图、兼容性和导出冲突，生成依赖优先顺序，并绑定原提供方加载器。
- `app/ui/mod/ModOriginalsPane.java`：JAR/APP 原包搜索/刷新、明确安装及原包删除、共用留存设置与原中断核对；锁定撤销确认，读取失败封闭修改，保留安装/清理的分别结果。
- `app/service/mod/ModInstallationCleanup.java`：为未登记安装撤销及已安装版本删除审阅有界的精确剩余文件，校验原目录身份与持久清理范围，继续删除原子集并拒绝额外或变化内容。
- `app/service/mod/ModOwnedCleanup.java`：整 Mod 登记程序、专属数据根及原类别应急文件的有界审阅、原身份验证和剩余子集清理；共享父目录只作身份依据。
- `app/service/mod/PlatformModDeletion.java`：审阅并持久保存单版本或整 Mod 的精确原生文件范围；串联程序、Schema、专属及应急文件、引用与登记删除，按明确勾选清理清单匹配原包，保留中断原范围并提供终态凭据。
- `app/service/mod/PlatformModReinstallation.java`：按原包及目录身份修复同版本程序，保留旧副本至新登记完成，支持明确恢复、原范围核对及独立原包留存结果，不修改数据或启用选择。
- `app/service/mod/PlatformModInstallation.java`：绑定原包审阅及留存选择；按原 INSTALL 阶段暂存、不覆盖发布、登记、清理和归档，以 Windows 原身份及逐文件摘要恢复；支持明确撤销未登记安装及原 CANCELLING 清理/归档；提供含无效包的只读扫描、独立待处理清单及原包删除审阅，不自动启用或接管替代目录。
- `app/service/mod/PlatformModIntents.java`：读取选择、运行和数据管理快照；审阅并原子保存开关、版本、连带禁用和来源，或取得明确数据重试；撤销原组同事务归档；保存用户页面固定偏好；已注销所有者的原 DELETE_MOD 仍进入管理观察。
- `app/service/mod/PlatformModCatalog.java`：将已验证 CLASS 构建原子登记为可选择版本，保留用户意图，以实际输出摘要防止待执行依据被重新编译替换。
- `app/service/mod/PlatformModPrograms.java`：为共用生命周期解析当前 CLASS 构建或安装根中精确 READY 登记，加载前复核原清单、来源、固定路径、完整内容和代码准入；不扫描接管未登记程序。
- `app/mod/loader/ModClassLoader.java`：共享声明的公开类型，同时将作者代码与宿主实现隔离；原包及 CLASS/JAR 共用 JAR 隐式 Class-Path 拒绝规则。
- `app/mod/loader/ModClassShape.java`：用 ASM 读取类声明、泛型、嵌套可见性及注解引用；核对成员/属性边界，不读取方法体或初始化作者类。
- `app/mod/loader/ModCodeInspection.java`：有界索引原包与 CLASS 来源，证明入口继承关系及导出签名归属，处理多版本覆盖和安装/启用的依赖可用性差异。
- `app/mod/loader/ModEntryFactory.java`：仅在启用期间检查公开形状与来源后构造入口。
- `app/mod/manifest/ManifestReader.java`：严格读取有界 UTF-8 作者清单，不加载作者类。
- `app/mod/manifest/ModManifest.java`：已解析的作者元数据，独立于安装意图及运行状态。
- `app/mod/version/SemanticVersion.java`：比较规范 SemVer 发布版本，避免整数溢出且不接收构建元数据。
- `app/mod/version/VersionRange.java`：判断精确版本或单一区间，并显式限制预发布准入。
- `app/service/database/TaskModDatabase.java`：将所属业务 JDBC 直接连接到原始任务的资源账本。
- `app/service/mod/ModHostLease.java`：将实际任务准入门及暂存宿主扩展绑定到一次 Mod 启用。
- `app/service/task/PlatformTaskRuntime.java`：在持久化准入后使用保留凭据运行绑定调用方的作者工作，核对资源及固定摘要归档；已知归档失败保留 FINISHING，原元数据重试不重放作者，完成句柄保留终态。
- `app/service/task/PlatformTaskAdmission.java`：在锁定的平台事务内核验本次启动的 Mod 版本、完整传递依赖、代码摘要、就绪 Schema 代次及完整服务器集合，并捕获原恢复图。
- `app/service/contract/spi/TaskManagement.java`：平台内部的保留任务清单及原任务操作契约，区分不可核对、缺原恢复、原恢复、元数据归档及救援确认。
- `app/service/task/PlatformTaskManagement.java`：后台读取真实控制可用性、请求原句柄协作取消，精确宿主救援经原救援服务确认；仅待归档不调用业务恢复。
- `app/ui/recovery/TaskRecoveryPane.java`：常驻任务页展示原身份、进度及资源阻塞，明确取消/核对/补归档并跳转现有日志页；锁定或关闭清空观察、使旧确认及迟到结果失效。
- `app/service/task/TaskCapabilityScope.java`：跟踪服务器及秘密适配器的真实操作寿命、Schema 占用及失败释放重试。
- `app/service/secret/PlatformSecretService.java`：在原任务凭据作用域提供所属秘密字节及持久引用，回调结束清零临时明文。
- `app/service/contract/spi/ServerProfileEditor.java`：只保存服务器配置及凭据的窄接口，不附带部署或连接检查。
- `app/service/server/PlatformServerService.java`：平台服务器配置、受引用保护的删除及原身份 SSH 认证检查/中断收尾。
- `app/service/server/PlatformServerAccess.java`：面向 Mod 的任务绑定服务器查询、持久引用预留/核对及未完成释放重试。
- `app/service/task/PlatformInteractionService.java`：持有调用方绑定的单次审批、秘密输入及实际提示窗口的任务占用。
- `app/service/linux/PlatformLinuxService.java`：连接真实服务器、凭据及 SSH/SFTP 资源，核验精确授权并报告不可覆盖的未知结果。
- `app/ui/shell/TaskInteractionPresenter.java`：在主窗口隐私边界内显示完整任务审核和输入，取消后实际关闭对话框。
- `app/ui/shell/ExtensionPagePane.java`：挂载已打开的 Mod 视图及按所有者分组的设置，明确打开时聚焦实际外层页面；固定偏好由导航管理。
- `app/ui/mod/ModPageRegistry.java`：持有暂存页面扩展及限定在 EDT 的作者视图生命周期；宿主打开复用原所有者作用域，发布变更只通知元数据，挂载界面由 shell 注入。
- `app/ui/shell/PlatformFrame.java`：承载平台安全、常驻管理和独立所属 Mod 视图的桌面。
- `app/ui/mod/PlatformModsPane.java`：以原快照审阅选择、确认连带停用及来源、撤销整组请求，并明确核对/重试数据；选择已发布页面以打开或固定，锁定使旧确认失效；提供单版本/整 Mod 文件范围确认及原删除核对，登记已移除的待清理项仍可继续。
- `app/ui/mod/ModNavigationPane.java`：显示用户固定偏好与当前发布页面的交集；异步持久化、跨启动恢复、锁定隐藏与原作用域打开，不自动创建作者视图；整 Mod 清理后重读偏好并撤销旧在途观察。
- `app/ui/server/PlatformServersPane.java`：常驻服务器列表、配置与普通 SSH 认证检查，支持协作取消和显式中断恢复。
- `app/windows/security/CurrentUserDataProtection.java`：使用 Windows 当前用户 DPAPI 保护引导材料。

- `app/ai/protocol/ModelMessageCodec.java`：通用文字/图片模型请求编码、容量检查及严格响应信封与用量校验。
- `app/ai/transport/LimitedHttpBodySubscriber.java`：在接收过程中限制完整正文容量，超限取消上游。
- `app/ai/transport/ModelHttpCall.java`：持有单次模型 HTTP 请求，处理总时限、取消、未知结果及实际客户端关闭。
- `app/service/ai/PlatformAiService.java`：按已验证用途调用模型，绑定原任务凭据及真实连接占用，保留未完成释放。

- `app/ai/protocol/ModelCapabilityProbe.java`：与业务无关的随机文字/图片挑战及精确答案核验。
- `app/service/ai/PlatformModelService.java`：平台模型验证后保存、明确用途配置及原任务中断核对。
- `app/service/contract/spi/AiInventoryAccess.java`：供平台清单控件使用的非秘密配置和用途窄接口。
- `app/service/contract/profile/ServerProfile.java`：已保存的非秘密桌面连接资料及纯记录转换。
- `app/service/contract/profile/AiProviderProfile.java`：模型连接配置、不透明凭据键及纯记录转换。
- `app/service/contract/result/AiProviderSummary.java`：非秘密模型清单摘要及独立能力验证证据。
- `app/service/task/HostTaskRecovery.java`：核对已知宿主协议的前次启动资源；浏览器只承认原所有者持久化的关闭检查点，任一资源未确认则整次不释放。
- `app/ui/ai/PlatformModelDialog.java`：含明确输出上限的模型表单，观察实际任务并清空输入凭据。
- `app/ui/ai/PlatformModelsPane.java`：常驻模型清单和用途编辑器，支持取消及明确中断核对。

- `app/source/boundary/SelectedDirectory.java`：校验用户目录内的精确相对文件，按身份、内容和容量进行读取与复制。
- `app/source/snapshot/FrozenSourceWork.java`：生成明确文件集的只读任务快照，核验清单及原生归属后清理。
- `app/service/source/PlatformSourceAccess.java`：把目录确认、读取和快照绑定原 Mod 任务，保留实际 FILE 占用并支持原身份恢复。
- `app/windows/filesystem/WindowsFileIdentity.java`：使用既有 JNA 读取 Windows 卷号和 128 位文件 ID，并关闭实际原生句柄。

- `appapi/linux/TransferConflict.java`：同名冲突的精确端点、方向与实测大小。
- `appapi/linux/TransferConflictChoice.java`：覆盖、保留两份与终止的明确用户选择。
- `app/linux/ssh/SftpDestination.java`：在暂存期间保留旧目标，生成不冲突名称并核对用户选择后发布。
- `app/linux/ssh/TransferConflictResolver.java`：由宿主提供的实际同名冲突决定接口。
- `app/linux/ssh/TransferJournal.java`：创建暂存文件前原子登记持久恢复事实和文件占用的宿主接口。
- `app/linux/ssh/SftpProgress.java`：仅以真实文件字节刷新计时，排除人工等待，区分传输与校验阶段。
- `app/linux/ssh/TransferProgressListener.java`：由实际传输监控线程发布阶段与字节观测的宿主回调。
- `app/linux/ssh/TransferPlan.java`：原传输的源/目标观测、选定端点和暂存名称，不含未核验续传偏移。
- `app/windows/filesystem/WindowsFilePublication.java`：不预删旧文件的 Windows 原生同目录文件发布；完整目录另以同卷不复制、不覆盖的原生重命名发布。

- `app/service/linux/TransferSettings.java`：独立连接及有效字节停滞阈值，每个逻辑操作使用不可变设置。
- `app/service/linux/TransferSettingsService.java`：当前用户门禁下后台读取并原子保存平台传输偏好，不保存任务授权或检查点。
- `app/ui/setting/TransferSettingsPane.java`：常驻连接/传输阈值设置页，持久化确认后生效，失败恢复上次确认显示。

- `appapi/task/AuthorizationMode.java`：独立平台一次/通用/完全授权枚举。
- `appapi/task/ActionTarget.java`：操作所属的精确服务器 ID、业务种类及已解析目标身份；仅依赖 JDK，不反向引用 server 能力包。
- `appapi/task/ActionSemantics.java`：业务校验后的操作含义和不可变完整目标集合。
- `app/service/task/PlatformAuthorizationService.java`：独立模式偏好、宿主流程准入、原任务临时语义授权及结束失效。
- `app/ui/component/WorkflowLaunchButton.java`：从实际用户操作捕获表单目标并提交中央流程任务，防止重复启动。
- `app/ui/setting/AuthorizationSettingsPane.java`：常驻三档平台授权偏好选择、保存确认与失败显示回滚。

- `app/db/log/LogRecordCodec.java`：SQL 与应急文件共用的有界脱敏记录、原身份时间及严格 UTF-8 封套校验。
- `app/db/log/LogRepository.java`：验证固定标准表与实际 Schema 代次，按类别原子写入、去重及保留。
- `app/db/log/EmergencyLogs.java`：固定根下每类应急文件及原子替换，保留损坏和中断替换证据。
- `app/service/log/PlatformLogService.java`：启用所属公开日志绑定、共享类别协调、回填及仅宿主日志管理。
- `app/ui/setting/LogSettingsPane.java`：默认数据库管理页，异步查看、清理、实际计数和本次 DEBUG 开关。

- `app/mod/process/ProcessProtocol.java`：严格 UTF-8 JSONL 封套、关联身份、容量和结果契约校验。
- `app/mod/process/ProcessChannel.java`：实际子进程的有界双向管道、握手、请求、取消、停止及独立 stderr 消费。
- `app/db/task/ProcessRecovery.java`：STARTING/STARTED 的精确启动代次、进程 UUID、PID 和系统启动时间，以及保留原事实的 `browser.closed.v1` 明确关闭协议。
- `app/service/process/PlatformProcessService.java`：创建前占用、握手前身份检查点、原任务未知保护及无重放进程核对。

- `app/mod/service/ServiceSnapshot.java`：跨 Mod 同步调用的有界值复制，拒绝活动句柄、闭包及异步对象。
- `app/db/task/DependencyRecovery.java`：逐调用提供方原版本、可选 Schema 代次及严格 service.v1 恢复事实。
- `app/service/mod/PlatformModServices.java`：真实加载器身份的服务登记、启动发布、依赖发现和原任务同步调用。
- `app/main/mod/PlatformModContext.java`：同次真实 Mod 启用的完整能力装配、暂存发布、部分失败回收和实际上下文关闭归属。
- `app/main/mod/PlatformModHost.java`：Mod 装配所需的宿主能力与原会话访问端口；由 startup 的实际运行时实现，Mod 装配不反向引用启动实现。

- `app/linux/recovery/`：迁入 PlaywrightTerminalSession、BrowserTerminalSession、TerminalTarget、TerminalObservation 和 BrowserRecoveryException；具体浏览器救援不再放在 Windows 或 shared/model。
- `app/linux/recovery/BrowserRecoverySession.java`、`RecoverySessionState.java`：串行救援状态机、预算、精确动作批准和原任务协作取消。
- `app/linux/recovery/RecoveryProbeResult.java`、`RecoverySnapshot.java`、`RecoveryTarget.java`：保留原 `Problem` 的探测分类、临时核心快照和无凭据端点；不依赖 service/db/shared。
- `app/service/recovery/RecoveryProbeAdapter.java`：仅供剩余旧 SSH 入口使用的错误转换，保留原事件和诊断身份；旧浏览器失败枚举已移除。
- `app/service/contract/definition/RecoveryTerminal.java`：服务层终端选择快照，避免救援 UI 直接依赖 Linux 浏览器类型。

- `app/ai/recovery/RecoveryAction.java` 与 `RecoveryProtocol.java`：平台救援的有界建议、影响分类、固定提示及严格结果解析，旧网络客户端复用同一协议。
- `app/linux/recovery/RecoveryModels.java`：救援控制器的临时观测/建议端口，结果不自动授权命令。
- `app/service/recovery/PlatformRecoveryModels.java`：原任务的真实平台模型适配，沿用用途、凭据、预算及 HTTP 占用，未知结果不继续请求。
- `app/linux/recovery/BrowserProcess.java` 与 `BrowserLaunchObserver.java`：原浏览器 PID/系统启动时间及实际创建前、终端可用前的宿主持久登记边界。
- `app/linux/recovery/PlaywrightDriver.java`：固定依赖版本的原驱动对象适配，分别核验连接关闭和原 `Process` 实际退出，失败保留原对象；不按进程名称猜测归属。
- `app/service/recovery/TaskBrowserSession.java`：原中央任务的浏览器归属；创建前登记 `browser.v1`，真实清理完成后经 `TaskLedger.confirmBrowserClosed` 提交关闭检查点，再释放 H2 占用。默认救援由 PlatformRecoveryService 接入；跨启动只能核对已确认关闭的记录，不推定未知资源已退出。

- `app/service/recovery/PlatformRecoveryService.java`：默认服务器页独立救援的原任务、保存配置、模型/浏览器/固定 SSH 复核总装、同服务器会话重开及显式原任务核对。

- `mod/deployment/Mod.java`：官方部署生命周期入口，绑定当前数据身份和独立日志类别；部署业务迁移仍在进行。
- `mod/deployment/db/DeploymentSchema.java`：独立于业务启动的固定数据生命周期，初始化文档规定的 18 张表，只读核验列清单、类型化配置内容摘要及发布绑定。
- `mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql`：官方部署 Schema 1 的固定 H2 DDL，含业务表及标准日志。
- `mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/validation-v1.sql`：只读列清单，用于发现缺表、缺列及多余表列。
- `mod/deployment/db/DeploymentConfigurationRepository.java`：在原任务受管 H2 连接中保存不可变配置及精确发布绑定，读取时复核类型和摘要。
- `mod/deployment/db/DeploymentDataTransaction.java`：所属业务事务的提交/回滚边界，无法确认时保留 UNKNOWN。
- `mod/deployment/service/core/config/DeploymentConfigurationSnapshot.java`：保留原规范 SHA-256 算法的不可变配置修订。
- `mod/deployment/service/core/config/DeploymentConfigurationEntry.java`：带消费范围的非秘密配置条目，拒绝秘密键及无效端口。
- `mod/deployment/service/core/config/DeploymentConfigurationValue.java`：TEXT、NUMBER、FLAG 对应的类型化值及规范摘要文本。
- `mod/deployment/service/core/config/DeploymentConfigurationScopeType.java`：BUILD/RUNTIME 两类配置消费范围。
- `mod/deployment/service/core/config/DeploymentConfigurationInput.java`：分号配置文本和精确秘密修订引用的确定性解析。
- `mod/deployment/service/core/model/DeploymentConfigurationException.java`：保留原配置事件码并提供公开 Problem 的非秘密输入失败。
- `mod/deployment/service/core/model/DeploymentSecretReference.java`：只持有业务秘密标识及不可变修订号，不持有秘密内容。
- `mod/deployment/db/DeploymentDataPathCodec.java`：组件审阅路径的版本1有界编码，拒绝重复和非规范路径。
- `mod/deployment/db/DeploymentResourceCodec.java`：文件与数据库绑定版本2编码，保留未知与明确空审阅以及秘密修订引用。
- `mod/deployment/db/DeploymentUnitRuntimeCodec.java`：部署单元激活信息版本1/2，保存健康、访问URL、身份策略和工作负载。
- `mod/deployment/db/DeploymentWorkloadCodec.java`：运行与激活载荷共用的字面参数、端点、输入、配套单元和工作进程编码。
- `mod/deployment/db/DeploymentRuntimeCodec.java`：运行定义版本5/6/7的持久化编码，健康契约由原部署单元单独提供。
- `mod/deployment/db/DeploymentHealthCodec.java`：独立健康检查载荷，保留HTTP/TCP/进程/命令/UDP的判别与字段。
- `mod/deployment/service/core/model/DeploymentResourceBindings.java`：文件绑定和可选数据库审阅清单。
- `mod/deployment/service/core/model/DeploymentDatabaseBinding.java`：逻辑数据库身份与精确连接绑定。
- `mod/deployment/service/core/model/DeploymentDatabaseConnection.java`：数据库服务器或SQLite文件的已审阅连接事实，仅含秘密引用。
- `mod/deployment/service/core/model/DeploymentDatabaseEngineType.java`：受支持数据库引擎判别值。
- `mod/deployment/service/core/model/DeploymentFileBinding.java`：业务文件位置及权限绑定。
- `mod/deployment/service/core/model/DeploymentSqliteRequirement.java`：部署应用使用的SQLite文件、种子及初始化声明。
- `mod/deployment/service/core/model/DeploymentHealthCheck.java`：受限健康探测及成功条件的纯值模型。
- `mod/deployment/service/core/model/DeploymentAccessUrl.java`：用户访问地址的显式声明，不从健康检查猜测服务暴露。
- `mod/deployment/service/core/model/DeploymentUnitIdentity.java`：应用部署单元、精确平台 ServerRef、systemd及发布目录的归属事实，不复制服务器端点或信任观测。
- `mod/deployment/service/core/model/DeploymentUnitRuntimeConfiguration.java`：部署单元的健康、访问、身份与工作负载组合。
- `mod/deployment/service/core/model/DeploymentStorageLocation.java`：默认FHS、自定义或未确认的应用存储位置及边界校验。
- `mod/deployment/service/core/model/DeploymentProjectKind.java`：运行类别的稳定业务判别值。
- `mod/deployment/service/core/model/DeploymentRuntimeDefinition.java`：15类部署运行定义及已审阅工作负载、身份策略，不承担平台执行。
- `mod/deployment/service/core/model/DeploymentIdentityMode.java`：保留受管进程动态或静态身份策略。
- `mod/deployment/service/core/model/DeploymentCommand.java`：已审阅程序入口及字面argv，不解释为Shell脚本。
- `mod/deployment/service/core/model/DeploymentCompanion.java`：工作负载配套进程及用途。
- `mod/deployment/service/core/model/DeploymentEndpoint.java`：传输协议、绑定地址、端口及内外暴露范围。
- `mod/deployment/service/core/model/DeploymentInputField.java`：明确只读输入及部署路径。
- `mod/deployment/service/core/model/DeploymentWorker.java`：受管工作进程身份与命令。
- `mod/deployment/service/core/model/DeploymentWorkload.java`：常驻/按需任务、端点、验证、输入及工作进程的审阅事实。
- `mod/deployment/service/core/model/DeploymentDataPath.java`：组件数据路径、读写范围、版本和保留要求。
- `mod/deployment/service/core/model/DeploymentServerIdentity.java`：部署时服务器端点和主机指纹快照，不是连接授权。
- `mod/deployment/service/core/model/DeploymentToolchainType.java`：运行版本规则对应的工具链类别。
- `mod/deployment/service/core/model/DeploymentToolchainVersion.java`：版本数值及预览状态解析与同类别比较。
- `mod/deployment/db/DeploymentPayloadValidation.java`：通过SELECT-only和有界LOB流核验原运行、健康、路径及资源载荷，不修复或重写原数据。
- `mod/deployment/db/DeploymentPayloadReader.java`：统一有界 BLOB 流读取，保留 SQL NULL 并拒绝超过既有格式上限的载荷。
- `mod/deployment/service/core/model/DeploymentApplicationGraph.java`：完整单服务器应用拓扑，校验组件唯一、依赖无环、健康归属及资源路径精确覆盖。
- `mod/deployment/db/DeploymentGraphRepository.java`：在既有匹配部署单元上原子替换完整图，只返回 graph_ready=true 的一致快照，不查询平台私有表。
