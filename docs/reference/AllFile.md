# WindowsToLinux 全文件架构索引

> 状态：按当前根 Maven 活动模块、独立 ModDev 区及实际文件生成，包含 1.3 已落地的生产文件；整体实施与本机验收状态见开发文档，源码索引不代表真实远端验收。
> 范围：根与活动模块、独立 ModDev 区的 Maven 描述、Mod 描述、非测试 Java 源及运行资源；测试、编译产物、退出活动图的旧 shared/web 与冻结 history-src 不纳入。
> 排序：Maven 描述置顶，其余同级按名称排序；模块的 resources 目录置后。
> 粒度：Java 文件说明与文件同名的顶级主类型；路径省略重复的 Maven 源码、资源与项目域名前缀。实际模块、包结构与维护规则见 [File.md](File.md)。
> 边界：1.1 的备份 Mod 与正式发行打包仍暂缓；部署 helper 保留的底层备份协议资源不等于备份 Mod 已迁移。

配置填写的唯一集中说明见 [Config.md](Config.md)。

```text
WindowsToLinux/
├─ pom.xml  # Maven 模块与依赖声明
└─ src/  # 当前活动 Maven 模块与源码
   ├─ app/  # 平台实现与桌面宿主的模块分组
   │  ├─ pom.xml  # Maven 模块与依赖声明
   │  ├─ ai/  # 模型、图像与音频的协议和消息处理
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ http/  # 共用 HTTP 地址校验与有界响应读取
   │  │  │  ├─ HttpUrlChecker.java  # 模型和音频调用共用的精确 HTTP 端点校验
   │  │  │  └─ LimitedHttpBodySubscriber.java  # 在分配完整正文前拒绝超大响应
   │  │  ├─ model/  # 模型用途、能力状态与通用消息编解码
   │  │  │  ├─ AiCapabilityCheck.java  # 单项能力的安全证据，不含提供方载荷
   │  │  │  ├─ AiCapabilityStatus.java  # 独立能力验证结果
   │  │  │  ├─ AiCapabilityType.java  # 分别验证的模型输入能力
   │  │  │  ├─ AiPurposeAssignment.java  # 一个有序用途成员，位置由所属列表确定
   │  │  │  ├─ AiPurposeType.java  # 与清单展示顺序无关的独立调用用途
   │  │  │  ├─ ModelCapabilityProbe.java  # 不含部署业务事实的共用能力挑战及答案核验
   │  │  │  ├─ ModelHttpCall.java  # 持有一个不重放的 HTTP 模型请求及其实际客户端关闭
   │  │  │  └─ ModelMessageCodec.java  # 编码通用模型消息并校验提供者信封，不包含业务策略
   │  │  ├─ vision/  # 图像校验、消息编码与图像能力验证样本
   │  │  │  ├─ VisionCapabilityProbe.java  # 生成带平台持有预期答案的全新视觉挑战
   │  │  │  └─ VisionMessageCodec.java  # 在共用模型消息协议内校验和编码图像附件
   │  │  └─ voice/  # 音频 HTTP 转写、实时会话与转写结果组装
   │  │     ├─ VoiceHttpCall.java  # 一个真实 multipart 转写请求，未知状态后不重放
   │  │     ├─ VoiceProtocol.java  # 不含业务路由的有界 OpenAI 转写协议值
   │  │     ├─ VoiceSocketSession.java  # 具有有序提交、有界队列及明确终态清理的 OpenAI 转写会话
   │  │     └─ VoiceTranscriptBuilder.java  # 由所属会话锁保护的提交身份及有界转写组装
   │  ├─ config/  # 独立 YAML 加载与不可变启动配置
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ AppConfig.java  # 不可变启动配置及分组纯值
   │  │  ├─ ConfigLoader.java  # classpath 资源读取、严格四区 YAML 合并及字段校验
   │  │  └─ resources/  # 随 Config JAR 发布的资源
   │  │     └─ config.yaml  # 通用与三模式启动配置，填写见 docs/reference/Config.md
   │  ├─ db/  # 平台 H2 数据存取与存储能力装配
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ PlatformStorage.java  # 在受保护 H2 引擎上装配平台所属配置仓库
   │  │  ├─ ai/  # 模型配置、用途选择与能力验证记录
   │  │  │  ├─ AiModelStore.java  # 在一行中保存模型连接、能力及路由状态
   │  │  │  ├─ AiPurposeStore.java  # 在模型行维护独立用途成员及顺序
   │  │  │  ├─ StoredAiModelInfo.java  # 同一模型绑定修订的分协议能力
   │  │  │  └─ StoredAiModelProfile.java  # 同一模型的可选协议地址及不透明凭据引用
   │  │  ├─ connect/  # H2 连接、事务和数据库生命周期
   │  │  │  ├─ ManagedJdbcConnection.java  # 追踪 JDBC 所有权，不暴露物理驱动句柄
   │  │  │  ├─ PlatformDatabase.java  # 持有唯一嵌入式 H2 引擎及受保护引导材料
   │  │  │  ├─ StoreConnections.java  # 在嵌套平台仓库事务间共享一个所属连接
   │  │  │  └─ StoreTransactionRunner.java  # 由聚焦仓库使用的共享事务原语
   │  │  ├─ error/  # 数据库失败的结构化原因映射
   │  │  │  ├─ DesktopStorageException.java  # 结构化桌面持久化失败
   │  │  │  └─ DesktopStorageFailureType.java  # 桌面持久化失败分类
   │  │  ├─ file/  # 文件操作、传输、链接与恢复依据的存取
   │  │  │  └─ FileStore.java  # 所有者/任务限定的持久文件操作及链接关联
   │  │  ├─ init/  # 平台数据库初始化、保护与重开校验
   │  │  │  ├─ DatabaseBootstrap.java  # 持有受保护引导字节，不将缺失或损坏状态转换为新数据库
   │  │  │  ├─ DatabaseProtection.java  # 宿主注入的当前用户保护能力，独立于数据库实现
   │  │  │  ├─ PlatformDatabaseInitializer.java  # 借用引擎连接初始化当前平台结构与启动状态
   │  │  │  └─ PlatformSchemaList.java  # 以同一固定引擎正式DDL核验APP结构
   │  │  ├─ log/  # 日志记录、保留与应急日志编码
   │  │  │  ├─ EmergencyLogs.java  # 具有已核验暂存及同目录原子发布的有界类别文件
   │  │  │  ├─ LogRecordCodec.java  # 与应急存储共用的有界脱敏记录格式
   │  │  │  └─ LogStore.java  # 持有登记标准日志表及原子写入和保留
   │  │  ├─ mod/  # Mod 安装、版本、重启和 Schema 核对记录
   │  │  │  ├─ ModActivationLedger.java  # 记录一次实际启用，不改变用户开关或版本选择
   │  │  │  ├─ ModDeletionLedger.java  # 持久化精确安装版本或整 Mod 删除，核验清理后才注销
   │  │  │  ├─ ModProgramMaintenance.java  # 安装程序替换与删除共用的已停止所有者准入
   │  │  │  ├─ ModRecoveryLedger.java  # 在任务生命期之外保留原恢复会话
   │  │  │  ├─ ModReinstallationLedger.java  # 持有同版本替换检查点，不修改 Mod 数据或用户选择
   │  │  │  ├─ ModRestartLedger.java  # 根据原持久事实应用重启意图、首次数据准备和明确重试
   │  │  │  └─ ModSchemaReconciliation.java  # 核对原未知数据，不运行迁移或改变用户选择
   │  │  ├─ schema/  # Schema 命名空间、受限访问与结构检查
   │  │  │  ├─ SchemaAccessScope.java  # 持有准备阶段连接及可选的只读验证凭据
   │  │  │  ├─ SchemaChecker.java  # 使用与作者验证相同的受限读取身份复核持久表约束
   │  │  │  ├─ SchemaCoordinates.java  # 将精确逻辑身份编码为有界的 H2 数据结构及所有者名称
   │  │  │  ├─ SchemaProvisioner.java  # 创建明确登记的 Mod 数据结构，不接管已有数据库对象
   │  │  │  └─ SchemaSnapshot.java  # 对 H2 原生结构及行计算指纹，不创建导出文件或日志
   │  │  ├─ security/  # 主密码设置、秘密载荷与秘密引用登记
   │  │  │  ├─ SecretRefStore.java  # 持久化 Mod 所属凭据关联，不暴露受保护值
   │  │  │  ├─ SecurityStore.java  # 持久化不透明安全记录及原子凭据归属变更
   │  │  │  ├─ StoredSecret.java  # 不透明凭据字节，使用后清理本地副本，包括明文格式
   │  │  │  └─ StoredSecurityProfile.java  # 持久密码验证信息和包装密钥，不含明文数据密钥
   │  │  ├─ server/  # 服务器配置、可信身份与业务引用存取
   │  │  │  ├─ ServerProfileStore.java  # 保存连接元数据和绑定端点的认证信任
   │  │  │  ├─ ServerRefStore.java  # 保存代次所属服务器引用，不检查私有业务记录
   │  │  │  ├─ StoredServerIdentity.java  # 目标服务器及其可信 SSH 主机密钥的非秘密身份
   │  │  │  ├─ StoredServerObservation.java  # 最近一次明确连接检查，不含凭据
   │  │  │  └─ StoredServerProfile.java  # 非秘密桌面连接元数据；密码或密钥保留在 app/secret 中
   │  │  ├─ setting/  # 平台界面、安装及 Mod 导航偏好存取
   │  │  │  ├─ DesktopPreferenceStore.java  # 保存小型非秘密桌面偏好
   │  │  │  ├─ ModNavigationPreferences.java  # 在用户选择及整 Mod 清理间共用严格导航持久化
   │  │  │  └─ NavigationPreferenceStore.java  # 复用桌面偏好的入口事务排序与隐藏保序
   │  │  ├─ sshrecover/  # SSH 救援会话和原任务恢复依据
   │  │  │  ├─ BrowserRecoveryArchival.java  # 仅在原中央任务事务内归档救援状态
   │  │  │  └─ BrowserRecoveryStore.java  # 仅保存状态码及命令摘要，不保存终端内容
   │  │  ├─ task/  # 任务账本、资源依赖、归档及清理状态
   │  │  │  ├─ DependencyRecovery.java  # 一次同步依赖调用的精确提供方及保留数据代次
   │  │  │  ├─ FileMaterialTransition.java  # 在原事务内检查精确 FILE 保管转移
   │  │  │  ├─ ProcessRecovery.java  # 独立于业务结果的精确进程创建检查点
   │  │  │  ├─ ResourceRecovery.java  # 与显式占用原子保存的有界宿主恢复事实
   │  │  │  ├─ TaskAuthorCheckpoint.java  # 存于一个平台 CUSTOM 占用、且不暴露宿主恢复协议的精确作者依据
   │  │  │  ├─ TaskLedger.java  # 独立于业务 SQL 持久化原子准入及实际资源释放
   │  │  │  ├─ TaskRecoveryGraph.java  # 任务准入前捕获的精确代码及数据身份
   │  │  │  ├─ TaskResource.java  # 由平台适配器确定归属的内部资源申请
   │  │  │  ├─ TaskResourceObservation.java  # 对任务收尾所用相同显式资源判据进行只读观察
   │  │  │  ├─ WorkflowRecoveryStore.java  # 仅释放前次启动宿主流程占用，不授予权限
   │  │  │  └─ metadata/  # 原任务元数据的严格取消意图编解码
   │  │  │     └─ TaskCancellationDocument.java  # 既有宿主任务元数据内严格的正交取消意图
   │  │  └─ resources/  # 随附运行资源
   │  │     └─ schema/  # Schema 准备与管理
   │  │        └─ platform-v1.sql  # 当前模块的数据库结构与权限脚本
   │  ├─ file/  # 平台文件引擎、传输计划与本机后端
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ FileContentPublish.java  # 负责已描述的精确文件职责
   │  │  ├─ FileCopyPlan.java  # 副作用前捕获精确复制位置、冲突选择及登记链接依赖
   │  │  ├─ FileCopyRun.java  # 负责已描述的精确文件职责
   │  │  ├─ FileEngine.java  # 有界文件操作、不跟随遍历、精确计划及持久副作用依据
   │  │  ├─ FileMaintenanceRun.java  # 仅对移动目标仍可证明的未完成原链接重新授权
   │  │  ├─ LocalFileBackend.java  # 当前进程账户文件访问，不附加全局根限制
   │  │  ├─ model/  # 文件后端、操作日志、链接维护及回收回执的内部合同
   │  │  │  ├─ FileActionLog.java  # 持久副作用前依据及有界所有者链接登记
   │  │  │  ├─ FileBackend.java  # 主机原语，审批、链接及目录规则归统一引擎
   │  │  │  ├─ FileLinkMaintenance.java  # 文件副作用前保留的精确原链接及移动目标依据
   │  │  │  └─ FileRecycleReceipt.java  # 标识实际可恢复对象及可选独立恢复元数据
   │  │  └─ source/  # 已选来源目录、工作目录与冻结来源
   │  │     ├─ FrozenSourceWork.java  # 持有一个任务本地快照及可核验清理标记
   │  │     ├─ SelectedDirectory.java  # 仅读取一个已确认目录中明确命名的普通文件
   │  │     ├─ SourceMaterialManifest.java  # 在作者载荷外持久保存有界规范来源事实，不复制原始树
   │  │     └─ SourceWorkDirectory.java  # 持有任务本地文件容器及其持久物理身份
   │  ├─ linux/  # 平台 SSH、SFTP 与实际远程执行能力
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ sftp/  # 复用受管 SSH 会话的远端文件协议与传输
   │  │  │  ├─ SftpDestination.java  # 选择并发布目标，在暂存期间完整保留旧文件
   │  │  │  ├─ SftpFileBackend.java  # 原认证受管 SSH 通道上的文件原语
   │  │  │  ├─ SftpProgress.java  # 仅计算已完成文件字节，从无进展计时中排除人工等待
   │  │  │  ├─ SftpTransfer.java  # 暂存一个已审核普通文件，仅在内容验证后发布
   │  │  │  ├─ TransferActionLog.java  # 首次暂存文件变更前必需的宿主日志
   │  │  │  ├─ TransferConflictResolver.java  # 宿主持有的明确同名决定，独立于操作授权
   │  │  │  ├─ TransferPlan.java  # 暂存一次原传输前持久化的不可变观测
   │  │  │  └─ TransferProgressListener.java  # 不暴露物理工作线程的宿主所属进度发布
   │  │  └─ ssh/  # SSH 会话、命令执行、信任与救援
   │  │     ├─ connect/  # SSH 连接、主机信任与认证
   │  │     │  ├─ ClientIoLifecycle.java  # 独立于 SSHD 逻辑关闭凭据持有真实客户端 I/O 群组
   │  │     │  ├─ SshCommandExecution.java  # 持有一次已发送 SSH 通道、有界输出及输入发送线程的实际生命周期
   │  │     │  ├─ SshCommandFrame.java  # 将脚本字节与命令的原始二进制输入分开传送
   │  │     │  ├─ SshConnector.java  # 持有平台 SSH 客户端，不引入部署代码或环境凭据
   │  │     │  ├─ SshEndpoint.java  # 精确的平台服务器身份及认证端点
   │  │     │  └─ SshOutputCapture.java  # 持续排空两条 SSH 流，同时只保留共享限额内的前缀
   │  │     └─ recover/  # SSH 救援状态、会话与原任务恢复编排
   │  │        ├─ BrowserRecoverySession.java  # 带可撤销精确动作确认的串行 Linux 救援状态机
   │  │        ├─ BrowserRecoverySnapshot.java  # 临时界面状态，命令内容不得记录日志或持久化
   │  │        ├─ RecoveryHost.java  # 向 SSH 救援注入既有平台授权及存储
   │  │        ├─ RecoveryProbe.java  # 以固定有界回显复核已认证 SSH 连接
   │  │        ├─ RecoverySessionState.java  # 工作线程持有状态，快照不含原始终端证据
   │  │        ├─ SshRecovery.java  # 在原中央任务内持有完整 SSH 救援、恢复核对及清理
   │  │        ├─ SshRecoverySession.java  # 受控交互会话，不暴露传输或持久化内部对象
   │  │        ├─ ai/  # 救援专用模型调用、提示及响应协议
   │  │        │  ├─ PlatformRecoveryModels.java  # 使用原平台任务及已配置模型预算提供救援建议
   │  │        │  ├─ RecoveryModels.java  # 向救援控制器提供临时观测及建议结果
   │  │        │  └─ RecoveryProtocol.java  # 持有严格救援提示及结果解析，不执行任何建议动作
   │  │        ├─ browser/  # 救援浏览器、终端会话与所属进程控制
   │  │        │  ├─ BrowserLaunchObserver.java  # 将浏览器创建绑定到宿主持有的持久资源事实
   │  │        │  ├─ BrowserProcess.java  # 标识一个实际浏览器进程，不授予向其发信号的权限
   │  │        │  ├─ BrowserRecoveryException.java  # 报告浏览器阶段失败，不复制页面、命令或终端内容
   │  │        │  ├─ BrowserTerminalSession.java  # 浏览器能力限定在用户选定的单一终端
   │  │        │  ├─ PlaywrightDriver.java  # 保留原 Playwright 连接及原生驱动，直到证实退出
   │  │        │  ├─ PlaywrightTerminalSession.java  # 在单一串行事件线程上持有全部 Playwright 对象
   │  │        │  └─ TaskBrowserSession.java  # 原浏览器物理关闭及持久凭据释放前保留任务占用
   │  │        └─ model/  # 救援目标、动作、探测与终端观测的共享值
   │  │           ├─ RecoveryAction.java  # 单条建议命令，由服务负责批准
   │  │           ├─ RecoveryProbeResult.java  # 保留原始失败实例的类型化 SSH 准入结果
   │  │           ├─ RecoveryTarget.java  # 标识已准入救援端点，不含凭据或业务配置
   │  │           ├─ TerminalObservation.java  # 临时终端证据，图片不得进入记录
   │  │           └─ TerminalTarget.java  # 不透明终端选择，地址不含凭据、查询和片段
   │  ├─ main/  # 平台入口、能力装配与运行生命周期
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ AppMain.java  # 桌面应用入口
   │  │  ├─ error/  # 平台失败的安全证据和实时日志绑定
   │  │  │  ├─ DesktopFailureReportStore.java  # 将有界宿主失败送入既有数据库或启动应急队列
   │  │  │  ├─ DesktopStartupException.java  # 阻止桌面安全启动的结构化失败
   │  │  │  ├─ DesktopSystemFailureType.java  # 持有的桌面进程失败类型
   │  │  │  └─ DesktopUncaughtFailureBoundary.java  # 桌面未捕获失败的最终进程边界
   │  │  ├─ mod/  # Mod 宿主上下文、生命周期与 Schema 检查装配
   │  │  │  ├─ ModSchemaChecker.java  # 持有一次明确只读检查，仅重试其原清理
   │  │  │  ├─ ModSchemaSetup.java  # 持有隔离初始化或迁移，只重试清理而不重放作者代码
   │  │  │  ├─ PlatformModContext.java  # 持有一次实际 Mod 启用的完整能力及暂存扩展
   │  │  │  ├─ PlatformModFlow.java  # 装配一次启动的已选 Mod 图并持有其逆序关闭
   │  │  │  ├─ PlatformModHost.java  # 向 Mod 装配提供已有平台能力，不依赖启动实现
   │  │  │  └─ PlatformModRecovery.java  # 在普通启动装配前持有隔离的原发布恢复组
   │  │  ├─ runtime/  # 开发与安装运行模式、数据根和运行路径解析
   │  │  │  ├─ RunModeResolver.java  # 模式按 DB 模块、jpackage 模式按 EXE 解析固定 data 目录
   │  │  │  └─ RuntimePathResolver.java  # 定位并验证受支持的开发、JAR 与 jpackage 文件系统布局
   │  │  ├─ start/  # 启动、数据库保护、主窗口与平台能力总装
   │  │  │  ├─ ApplicationLauncher.java  # 使用固定路径、当前用户保护及 H2 的生产平台引导
   │  │  │  ├─ PlatformBuildIdentity.java  # 读取编译后的启动身份并落实阻塞生命周期边界
   │  │  │  ├─ PlatformRecoveryHost.java  # 将平台授权及数据库凭据接入 Linux 所属 SSH 救援
   │  │  │  └─ PlatformRuntime.java  # 为一次桌面启动持有平台存储、任务执行及安全会话
   │  │  └─ resources/  # 随附运行资源
   │  │     └─ build.properties  # 当前模块的固定配置或语言资源
   │  ├─ mod/  # Mod 配置、发现、安装、加载与运行基础
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ config/  # Mod 描述读取、版本约束与配置值
   │  │  │  ├─ ManifestReader.java  # 严格读取有界 UTF-8 作者清单，不加载作者类
   │  │  │  └─ ModManifest.java  # 已解析的作者元数据，独立于安装意图及运行状态
   │  │  ├─ dev/  # 开发源码发现、Maven 输入与开发装载
   │  │  │  ├─ DevelopmentInfo.java  # 在 Mod/ModDev 同区边界中使用显式输入摘要核验 CLASS 描述
   │  │  │  ├─ DevelopmentModFinder.java  # 发现当前已编译 Mod 声明，不执行任何作者代码
   │  │  │  ├─ MavenDevelopmentInputs.java  # 编译前捕获 Maven 已解析模型，不重新解析依赖声明
   │  │  │  └─ ModPackageTool.java  # 执行构建所属 CLASS 描述生成，不加载作者代码
   │  │  ├─ install/  # Mod 包读取、检查、打包与安装素材处理
   │  │  │  ├─ ModPackageReader.java  # 读取并暂存原包，不加载作者类或改变已安装状态
   │  │  │  └─ ModZip.java  # 约束 ZIP 元数据及嵌套视图，不展开内嵌归档
   │  │  ├─ load/  # 类加载隔离、入口和公开导出的检查
   │  │  │  ├─ ModClassLoader.java  # 共享声明的公开类型，同时将作者代码与宿主实现隔离
   │  │  │  ├─ ModClassShape.java  # 不定义类、不访问方法体地读取声明元数据
   │  │  │  ├─ ModCodeChecker.java  # 原包与编译来源共用的有界入口及导出静态准入
   │  │  │  └─ ModEntryFactory.java  # 仅在启用期间检查公开形状与来源后构造入口
   │  │  ├─ process/  # Mod 所属辅助进程的固定通信协议
   │  │  │  ├─ ProcessChannel.java  # 持有一个真实子进程的有界协议传输；宿主负责准入和持久占用
   │  │  │  └─ ProcessProtocol.java  # 独立于任务准入的严格第一版辅助进程帧
   │  │  ├─ run/  # Mod 依赖计划、运行会话、启停与资源归属
   │  │  │  ├─ ModDependencyPlan.java  # 在加载代码前检查已选且启用的版本，不改变用户意图
   │  │  │  └─ ModRuntimeSession.java  # 启用一个已准备的 Mod，并保留部分停止归属直至实际清理
   │  │  ├─ service/  # 跨 Mod 服务值的受控复制与传递
   │  │  │  └─ ServiceValueCopier.java  # 复制有界服务值，不让活动提供方资源跨越调用
   │  │  ├─ version/  # Mod 版本解析和兼容范围判断
   │  │  │  ├─ SemanticVersion.java  # 比较规范 SemVer 发布版本，避免整数溢出且不接收构建元数据
   │  │  │  └─ VersionRange.java  # 判断精确版本或单一区间，并显式限制预发布准入
   │  │  └─ resources/  # 随附运行资源
   │  │     └─ META-INF/  # Java标准元数据
   │  │        └─ maven/  # maven 语言或工具链构建职责
   │  │           └─ plugin.xml  # 当前模块的随附运行资源
   │  ├─ secret/  # 平台秘密存取、主密码保护与秘密生命周期
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ PasswordKeyCrypto.java  # 第一版主密码包装及凭据认证加密
   │  │  ├─ SecretStore.java  # 平台凭据边界
   │  │  ├─ SecretStoreException.java  # 报告凭据失败，不复制秘密输入或提供方消息
   │  │  ├─ SecretStoreFailureType.java  # 使用平台公开词汇表达凭据失败
   │  │  └─ SecretVault.java  # 使用仅内存引用计数密钥句柄的统一数据库凭据库
   │  ├─ service/  # 将公开能力绑定到原任务、授权与资源的服务
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ ai/  # 模型配置访问、用途路由与受管 AI 调用
   │  │  │  ├─ AiModelInfo.java  # 同一模型绑定修订的分协议能力
   │  │  │  ├─ AiModelListAccess.java  # 平台非秘密模型清单及明确用途选择
   │  │  │  ├─ AiModelProfile.java  # 同一模型及明确配置的分协议端点
   │  │  │  ├─ PlatformAiService.java  # 经冻结用途选择及原任务凭据路由通用模型调用
   │  │  │  └─ PlatformModelService.java  # 持有已验证模型配置及明确用途选择
   │  │  ├─ db/  # 任务所属 Mod 的受限数据库访问
   │  │  │  └─ TaskModDatabase.java  # 将所属业务 JDBC 直接连接到原始任务的资源账本
   │  │  ├─ error/  # 平台服务的结构化失败与安全诊断
   │  │  │  ├─ ApplicationServiceException.java  # 桌面应用服务边界的非受检结构化失败
   │  │  │  └─ ApplicationServiceFailureType.java  # 桌面应用服务边界抛出的稳定失败
   │  │  ├─ file/  # 文件选择、受管执行、传输与恢复服务
   │  │  │  ├─ FileDirectoryPicker.java  # 原任务目录选择的宿主回调契约
   │  │  │  ├─ FileOperationRecovery.java  # 核对原物理依据，仅修复已证明的链接元数据
   │  │  │  ├─ FileTransfers.java  # 将单个预先计划的普通文件接入既有持久传输状态机
   │  │  │  ├─ PlatformFileAccess.java  # 将用户所选文件及所属快照绑定到原 Mod 任务
   │  │  │  ├─ PlatformFileFlow.java  # 实际审批、持久化及受管传输接通的任务授权通用文件服务
   │  │  │  ├─ PlatformFileMaterials.java  # 转移精确原工作目录接管并在访问前持久保存消费方持有
   │  │  │  ├─ PlatformFileService.java  # 通过职责接口发布原文件所有者
   │  │  │  ├─ PlatformMaterialCoordinator.java  # 在同引擎文件启用间共享实际原捕获归属
   │  │  │  ├─ PlatformMaterialGrant.java  # 附着于消费方自己的选择对象的仅宿主白名单
   │  │  │  ├─ PlatformMaterialHeader.java  # 严格原素材头部，文件系统位置从不属于作者事实
   │  │  │  ├─ PlatformMaterialStore.java  # 读取精确保留素材资源，不虚构缺失接管
   │  │  │  ├─ PlatformWorkspaceTransfer.java  # 已消费原句柄的可信转移坐标，绝不是作者提供的位置元数据
   │  │  │  └─ check/  # 文件审批交互、删除审阅与后端准入
   │  │  │     ├─ FileBackendAccess.java  # 平台专有的任务绑定本机或既有受管 SSH 文件通道访问
   │  │  │     ├─ FileDeletionReview.java  # 签发授权前独立校验实际删除事实及 AI 决定
   │  │  │     ├─ FileInteraction.java  # 保持调用方审批和任务等待的平台交互
   │  │  │     └─ PlatformFileInteraction.java  # 将文件决定、可选说明及等待接入真实宿主授权
   │  │  ├─ linux/  # 任务所属远程会话与命令执行服务
   │  │  │  └─ PlatformLinuxService.java  # 将已保存服务器及已审核副作用接入真实任务所属 SSH 资源
   │  │  ├─ log/  # 所属日志访问、维护与应急记录回填
   │  │  │  └─ PlatformLogService.java  # 独立于业务结果协调真实 SQL 及应急诊断
   │  │  ├─ mod/  # Mod 管理入口、服务公开与任务所属能力
   │  │  │  ├─ ModHostLease.java  # 将实际任务准入门及暂存宿主扩展绑定到一次 Mod 启用
   │  │  │  ├─ PlatformModCatalog.java  # 登记已核验 CLASS 构建，仅在请求的启动中初始化新身份选择
   │  │  │  ├─ PlatformModIntents.java  # 审阅并原子取得重启意图、首次数据准备或明确重试
   │  │  │  ├─ PlatformModPrograms.java  # 为共用生命周期解析精确登记代码，不混用来源模式
   │  │  │  ├─ PlatformModServices.java  # 具有启用发布及逐调用任务归属的共用实际服务登记器
   │  │  │  ├─ install/  # Mod 安装、删除、重装及所属资源清理
   │  │  │  │  ├─ ModInstallationCleanup.java  # 仅审阅和移除明确接纳安装的有界文件集合
   │  │  │  │  ├─ ModOwnedCleanup.java  # 不跟随链接地审阅整所有者安装程序及明确归属辅助文件
   │  │  │  │  ├─ ModPackageInfo.java  # 已验证包的不可变展示信息，不携带安装权限
   │  │  │  │  ├─ PlatformModDeletion.java  # 经原持久及物理范围删除已审阅安装版本或完整所有者
   │  │  │  │  ├─ PlatformModDeletionFileEvidence.java  # 核验原删除目标，不删除文件，不持有数据库资源
   │  │  │  │  ├─ PlatformModInstallation.java  # 经持久阶段安装已审阅本地原包，不启用或执行作者代码
   │  │  │  │  └─ PlatformModReinstallation.java  # 修复原安装发布，保留数据、选择及可恢复旧程序
   │  │  │  └─ store/  # Mod 商店查询、获取及验证的适配
   │  │  │     ├─ HttpModStoreGateway.java  # 实际同源 HTTP 传输，具备完整 body 期限且不重定向
   │  │  │     ├─ ModStoreBatch.java  # 执行一份固定计划，保留实际凭据及原不确定性
   │  │  │     ├─ ModStoreBatchResult.java  # 取消或不确定后仍保留的实际逐包事实
   │  │  │     ├─ ModStoreBody.java  # 仅在 EOF 及文件关闭后完成的有界流式 body
   │  │  │     ├─ ModStoreCatalog.java  # 完整远端目录及独立核验安装观察
   │  │  │     ├─ ModStoreFacts.java  # 共用商店事实，与作者执行及安装权限分开
   │  │  │     ├─ ModStoreGateway.java  # 商店查询、发布、下载、进度及验证接口
   │  │  │     ├─ ModStoreInstalled.java  # 读取实际登记安装程序，不启动作者或发现源码
   │  │  │     ├─ ModStorePlan.java  # 一份修订绑定的完整获取计划
   │  │  │     ├─ ModStoreProgress.java  # 原批次的实际阶段及字节观察
   │  │  │     ├─ ModStoreProtocol.java  # 严格版本一目录协议，作者解析仍归 ManifestReader
   │  │  │     ├─ ModStoreResolver.java  # 每分支从固定主项重建的有限全局搜索
   │  │  │     ├─ ModStoreSelection.java  # 明确不可变用户选择，可选关系不会静默消失
   │  │  │     ├─ ModStoreService.java  # 确认下载与校验后复用已有安装器
   │  │  │     └─ UnavailableModStoreGateway.java  # 无真实 HTTP 的服务明确报告不可用的商店入口
   │  │  ├─ process/  # 任务所属辅助进程的受管访问
   │  │  │  └─ PlatformProcessService.java  # 具有持久创建事实及已证明物理释放的任务绑定真实辅助进程
   │  │  ├─ secret/  # 秘密标识、业务引用与原任务授权绑定
   │  │  │  └─ PlatformSecretService.java  # 经已接纳任务提供所有者绑定的受保护字节及代次所属引用
   │  │  ├─ security/  # 解锁、主密码、加密设置与安全会话
   │  │  │  ├─ DesktopPrivacyState.java  # 独立于平台授权的原生窗口隐私确认
   │  │  │  ├─ DesktopSecurityService.java  # 持有会话授权、已接纳任务句柄及独占安全变更
   │  │  │  ├─ DesktopSecurityState.java  # 不含秘密的桌面授权状态
   │  │  │  ├─ DesktopTaskAuthorization.java  # 持有跨工作线程的已授权桌面操作
   │  │  │  └─ SecurityAccess.java  # 安全边界只暴露状态和密码操作，不暴露密钥
   │  │  ├─ server/  # 服务器配置编辑、选择、标识与业务引用
   │  │  │  ├─ PlatformServerAccess.java  # 提供任务已准入服务器观测及精确代次持久引用
   │  │  │  ├─ PlatformServerService.java  # 管理平台服务器设置及任务所属认证检查，不依赖部署代码
   │  │  │  ├─ ServerProfile.java  # 已保存的非秘密桌面连接资料
   │  │  │  ├─ ServerProfileEditor.java  # 保存平台连接设置，不调用部署或远端准备
   │  │  │  └─ ServerSummary.java  # 服务器卡片及带时间的连接证据，不含凭据
   │  │  ├─ setting/  # 文件、传输与导航偏好的读写服务
   │  │  │  ├─ FileSettingsService.java  # 在既有桌面偏好中保存可选删除说明
   │  │  │  ├─ NavigationOrderService.java  # 经安全边界读取及保存入口顺序
   │  │  │  ├─ StartupPageLocation.java  # 独立于偏好读写服务的非秘密启动页面位置值
   │  │  │  ├─ StartupPageService.java  # 在桌面偏好中读写正常关闭时的非秘密页面身份
   │  │  │  ├─ TransferConfig.java  # 每个逻辑网络操作只捕获一次的非秘密平台阈值
   │  │  │  └─ TransferSettingsService.java  # 保存独立平台传输偏好，不保留任务授权或检查点
   │  │  └─ task/  # 任务准入、授权、执行、取消与资源收尾
   │  │     ├─ HostTaskRecovery.java  # 核验已知宿主协议消失的进程内资源
   │  │     ├─ PlatformAuthorizationService.java  # 持有持久权限偏好及宿主启动流程的临时作用域
   │  │     ├─ PlatformInteractionService.java  # 持有调用方绑定的人工审核及单次授权，不持久化秘密输入
   │  │     ├─ PlatformTaskAdmission.java  # 根据当前启动的权威平台登记解析任务归属
   │  │     ├─ PlatformTaskManagement.java  # 将明确桌面操作路由到原任务及宿主救援确认边界
   │  │     ├─ PlatformTaskRuntime.java  # 在持久化准入后运行绑定调用方的作者工作，并保留未完成归属
   │  │     ├─ PlatformWorkflowAdmission.java  # 读取宿主委托所需的完整原根服务器准入
   │  │     ├─ TaskCapabilityScope.java  # 跟踪同步平台能力操作，直至物理和持久释放完成
   │  │     └─ TaskManagement.java  # 宿主任务清单及明确原任务操作
   │  ├─ ui/  # 平台 Swing 界面与公共控件
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ ai/  # 模型清单、用途选择与能力状态展示
   │  │  │  ├─ AiModelListPane.java  # 内联编辑用途时保持原模型清单可见
   │  │  │  ├─ AiModelListState.java  # 外观变化时保留的非秘密内联编辑状态
   │  │  │  ├─ PlatformModelDialog.java  # 保存明确模型配置并单独观察能力验证
   │  │  │  └─ PlatformModelsPane.java  # 常驻模型清单及明确的中断探测恢复
   │  │  ├─ control/  # 主题、布局、卡片与通用 Swing 控件
   │  │  │  ├─ DesktopCardList.java  # 卡片跟随鼠标及相邻行平滑让位，保留原排序回调
   │  │  │  ├─ DesktopIcons.java  # 跟随主题的内置 SVG 图标
   │  │  │  ├─ DesktopPartFactory.java  # 按所选桌面主题及共享视觉约定创建 Swing 控件
   │  │  │  ├─ DesktopThemeBindings.java  # 更新已有主题控件时保留明确的配色角色
   │  │  │  ├─ RoundedCard.java  # 共用的可缩放圆角表面
   │  │  │  └─ WrappingFlowLayout.java  # 按可用宽度测量换行操作区
   │  │  ├─ debug/  # 受管 CLASS 模式的界面调试预览
   │  │  │  ├─ UiDebugDialog.java  # 不持有生产写入、SSH或模型能力的仅CLASS固定数据目录
   │  │  │  └─ UiDebugPreviewHost.java  # 在宿主持有的对话框中挂载隔离作者预览
   │  │  ├─ display/  # 语言、主题和显示设置
   │  │  │  ├─ DesktopComboBoxUi.java  # 固定各下拉框显示后的宽度，保留 FlatLaf 输入及展开列表绘制
   │  │  │  ├─ DesktopDisplayConfig.java  # 在桌面设置页面选择的可持久化显示偏好
   │  │  │  ├─ DesktopMotion.java  # 共用实际时间运动时钟及系统动画偏好
   │  │  │  ├─ DesktopSwitchIcon.java  # 普通复选组件的圆形滑块及鼠标键盘状态反馈
   │  │  │  ├─ DesktopThemeService.java  # 受管部署 Swing 桌面客户端共用的 FlatLaf 设置
   │  │  │  ├─ SystemThemeResolver.java  # 读取 Windows 应用外观偏好，但不写入系统注册表
   │  │  │  ├─ ThemeMode.java  # 用户可选择的桌面外观偏好
   │  │  │  └─ ThemePalette.java  # 单个生效浅色或深色外观使用的不可变应用颜色
   │  │  ├─ error/  # 结构化失败的本地化显示
   │  │  │  ├─ DesktopFailurePresenter.java  # 将任务失败转换为安全的本地化 UI 文本和报告引用
   │  │  │  ├─ FailureReportRecord.java  # 本地诊断尽力写入的结果
   │  │  │  ├─ FailureReportStore.java  # 面向 UI 的安全本地诊断报告窄边界
   │  │  │  └─ PageMessagePresenter.java  # 独立页面控制器共享的本地化与诊断格式化
   │  │  ├─ language/  # 界面文案目录与语言选择
   │  │  │  ├─ LocaleBinding.java  # 显式绑定展示文本的语言，不解释用户输入
   │  │  │  ├─ LocalizedMessage.java  # 不将领域代码与显示语言耦合的用户可见消息
   │  │  │  └─ MessageCatalog.java  # 从区域设置资源包解析应用消息键和非递归命名占位符
   │  │  ├─ mod/  # Mod 管理、页面挂载和页面生命周期
   │  │  │  ├─ ExtensionPagePane.java  # 挂载保留的 Mod 根卡片及按所有者分组的设置
   │  │  │  ├─ ModNavigationPane.java  # 每个已发布根自动显示一次，不构造作者视图
   │  │  │  ├─ ModOriginalsPane.java  # 管理明确发现的原包及其原中断安装
   │  │  │  ├─ ModPageRegistry.java  # 持有暂存页面扩展及限定在 EDT 的作者视图生命周期
   │  │  │  ├─ ModStoreConfirmation.java  # 持有明确接纳且可撤销的完整当前计划审阅
   │  │  │  ├─ ModStoreFeedback.java  # 独立于传输阶段展示原逐包回执
   │  │  │  ├─ ModStorePane.java  # 商店搜索、详情、安装、进度和手动重试
   │  │  │  ├─ ModStorePlanModel.java  # 展示精确已解析及未解析所有者，不执行依赖解析
   │  │  │  ├─ ModStoreSelectionPane.java  # 编辑用户固定版本及可选边，同时展示原服务计划
   │  │  │  └─ PlatformModsPane.java  # 经原宿主管理 Mod 已存选择及明确数据维护
   │  │  ├─ server/  # 服务器配置、选择、编辑与 SSH 救援界面
   │  │  │  ├─ PlatformServersPane.java  # 带显式任务恢复的常驻配置及仅认证 SSH 页面
   │  │  │  ├─ ServerProfileDialog.java  # 独立服务器配置编辑窗口，连接检查是单独动作
   │  │  │  └─ SshRecoveryDialog.java  # 非模态人工登录交接及精确命令批准
   │  │  ├─ setting/  # 平台文件等设置界面
   │  │  │  ├─ FileSettingsPane.java  # 仅编辑可选说明偏好，保留持久确认及隐私门禁
   │  │  │  ├─ LogSettingsPane.java  # 仅宿主的诊断清单、有界记录视图及明确历史清理
   │  │  │  ├─ NavigationOrderPane.java  # 外观内的平台和固定 Mod 入口排序
   │  │  │  ├─ PlatformAppearancePane.java  # 持久化明确的外观选择，不重建运行器或业务页面
   │  │  │  ├─ SecuritySettingsPane.java  # 重新验证密码后修改全局安全设置
   │  │  │  └─ TransferSettingsPane.java  # 使用持久化已确认值的常驻平台阈值编辑器
   │  │  ├─ task/  # 通用任务启动、授权、后台执行和恢复界面
   │  │  │  ├─ DesktopTaskHandle.java  # 通过中断其工作线程取消单个任务，完成回调等待资源清理
   │  │  │  ├─ DesktopTaskRunner.java  # 运行后台操作并将完成结果返回 Swing 事件线程
   │  │  │  ├─ TaskInteractionPresenter.java  # 在平台隐私窗口所属对话框内呈现完整任务审核
   │  │  │  ├─ TaskRecoveryPane.java  # 保留任务检查及明确原任务取消或核对
   │  │  │  └─ WorkflowLaunchButton.java  # 捕获实际选定流程及目标作用域的宿主启动控件
   │  │  ├─ window/  # 主窗口、导航、标题和设置分类
   │  │  │  ├─ DesktopBrandLabel.java  # 固定两行项目名称及宽度驱动淡入淡出
   │  │  │  ├─ DesktopHomePane.java  # 内置 16:9 封面等比首页
   │  │  │  ├─ DesktopLockController.java  # 保留导航的桌面锁定及从属窗口生命周期
   │  │  │  ├─ DesktopLockGate.java  # 透明内容输入门禁
   │  │  │  ├─ DesktopLockStatus.java  # 左下角状态与反向操作
   │  │  │  ├─ DesktopUnlockDialog.java  # 紧凑主密码解锁弹窗
   │  │  │  ├─ PlatformFrame.java  # 承载安全控件及独立所属 Mod 视图的平台桌面
   │  │  │  └─ PlatformNavigationShell.java  # 围绕已有页面实例的常驻桌面导航
   │  │  └─ resources/  # 随附运行资源
   │  │     ├─ control/  # 通用界面控件
   │  │     │  ├─ brand/  # 独立的项目图标及封面资源副本
   │  │     │  │  ├─ windowstolinux-cover-4k.png  # 首页 16:9 项目封面
   │  │     │  │  └─ windowstolinux-icon-2k.png  # 左上角项目标识
   │  │     │  └─ icons/  # 按功能分类的界面图标
   │  │     │     ├─ LICENSE.txt  # 原图标许可
   │  │     │     ├─ action/  # 通用操作
   │  │     │     │  ├─ arrow-right.svg  # 当前模块的随附运行资源
   │  │     │     │  ├─ grip-vertical.svg  # 当前模块的随附运行资源
   │  │     │     │  ├─ plus.svg  # 当前模块的随附运行资源
   │  │     │     │  ├─ refresh-cw.svg  # 当前模块的随附运行资源
   │  │     │     │  ├─ rocket.svg  # 当前模块的随附运行资源
   │  │     │     │  └─ search.svg  # 当前模块的随附运行资源
   │  │     │     ├─ ai/  # AI 配置
   │  │     │     │  └─ bot.svg  # 当前模块的随附运行资源
   │  │     │     ├─ file/  # 文件与来源
   │  │     │     │  ├─ folder-open.svg  # 当前模块的随附运行资源
   │  │     │     │  └─ git-branch.svg  # 当前模块的随附运行资源
   │  │     │     ├─ lock/  # 锁定与解锁
   │  │     │     │  ├─ locked.svg  # 当前模块的随附运行资源
   │  │     │     │  └─ unlocked.svg  # 当前模块的随附运行资源
   │  │     │     ├─ mod/  # Mod 管理
   │  │     │     │  └─ archive.svg  # 当前模块的随附运行资源
   │  │     │     ├─ navigation/  # 导航与展开折叠
   │  │     │     │  ├─ chevron-down.svg  # 当前模块的随附运行资源
   │  │     │     │  ├─ chevron-right.svg  # 当前模块的随附运行资源
   │  │     │     │  ├─ panel-left-close.svg  # 当前模块的随附运行资源
   │  │     │     │  ├─ panel-left-open.svg  # 当前模块的随附运行资源
   │  │     │     │  ├─ panels-top-left.svg  # 当前模块的随附运行资源
   │  │     │     │  └─ settings.svg  # 当前模块的随附运行资源
   │  │     │     ├─ security/  # 授权策略
   │  │     │     │  ├─ hand.svg  # 当前模块的随附运行资源
   │  │     │     │  ├─ shield-alert.svg  # 当前模块的随附运行资源
   │  │     │     │  └─ shield.svg  # 当前模块的随附运行资源
   │  │     │     └─ server/  # 服务器
   │  │     │        └─ server.svg  # 当前模块的随附运行资源
   │  │     └─ language/  # 语言资源与切换
   │  │        └─ messages/  # 双语消息
   │  │           ├─ Messages.properties  # 提供桌面界面的英文基准消息目录
   │  │           └─ Messages_zh_CN.properties  # 提供与英文消息键一致的简体中文界面映射
   │  └─ windows/  # Windows 原生文件、安全、窗口与安装维护
   │     ├─ pom.xml  # Maven 模块与依赖声明
   │     ├─ file/  # Windows 文件身份、链接、回收和发布
   │     │  ├─ WindowsFileIdentity.java  # 不跟随末级重解析点，读取卷及原生文件身份
   │     │  ├─ WindowsFilePublish.java  # 按明确原生重命名规则发布已验证文件或整个目录
   │     │  ├─ WindowsLinks.java  # 不跟随地识别 Windows 符号链接及目录联接点
   │     │  ├─ WindowsRecycle.java  # 使用原生回收回执，绝不回退永久删除
   │     │  ├─ WindowsRecycleInformation.java  # 校验有界 Windows 回收元数据，不根据缺失推断可恢复性
   │     │  ├─ WindowsRecycleNative.java  # 在一个平台线程 COM 单元内完整持有原生 Shell 对象
   │     │  ├─ WindowsRecycleReceipt.java  # 已观测 Windows 回收对象的内部证明
   │     │  └─ WindowsRecycleSink.java  # 接收实际 PostDeleteItem 结果，并保留完整 COM 回调表
   │     ├─ handoff/  # 更新与卸载共用的认证交接合同
   │     │  ├─ DesktopHandoffEnvelopeCodec.java  # 独立传输桌面交接共用的认证二进制信封
   │     │  ├─ DesktopHandoffException.java  # 桌面持有的认证交接结构化受检失败
   │     │  └─ DesktopHandoffFailureType.java  # 桌面交接边界持有的失败类型
   │     ├─ security/  # 当前用户与 Windows 原生数据保护
   │     │  └─ CurrentUserProtection.java  # 使用 Windows 当前用户 DPAPI 保护引导材料
   │     ├─ uninstall/  # Windows 卸载审阅、准备、交接与执行
   │     │  ├─ DesktopUninstallFlow.java  # 在已验证本地应用边界内执行显式选择的卸载
   │     │  ├─ DesktopUninstallHandoff.java  # 交给外部执行器的显式卸载决定与停收证据
   │     │  ├─ DesktopUninstallHandoffCodec.java  # 卸载交接跨进程边界使用的严格认证传输编解码器
   │     │  ├─ DesktopUninstallPort.java  # 不含源码、备份或远端操作的主进程与外部执行器分阶段卸载接缝
   │     │  ├─ DesktopUninstallRequest.java  # 单次卸载请求的精确 jpackage、数据及凭据边界
   │     │  ├─ DesktopUninstallResult.java  # 带精确失败残留项目的桌面卸载结果
   │     │  ├─ DesktopUninstallSetupResult.java  # 校验决定并停止主进程任务的结果
   │     │  └─ model/  # 卸载决策、状态、事件及结构化失败合同
   │     │     ├─ DesktopUninstallDecisionType.java  # 桌面本地数据及凭据的显式用户选择
   │     │     ├─ DesktopUninstallEvent.java  # 单个有界桌面卸载事件
   │     │     ├─ DesktopUninstallException.java  # 桌面卸载边界的结构化受检失败
   │     │     ├─ DesktopUninstallFailureType.java  # 桌面卸载边界持有的失败类型
   │     │     ├─ DesktopUninstallSetupStatus.java  # 启动外部执行器前的主进程卸载状态
   │     │     ├─ DesktopUninstallState.java  # 有序桌面卸载证据状态
   │     │     └─ DesktopUninstallStatus.java  # 精确桌面卸载终态
   │     ├─ update/  # Windows 更新准备、交接与执行
   │     │  ├─ DesktopUpdateFlow.java  # 编排程序、H2 数据库与 db-access.bin 的一致更新事务
   │     │  ├─ DesktopUpdateHandoff.java  # 交给独立更新器的不可变主进程证据
   │     │  ├─ DesktopUpdateHandoffCodec.java  # 更新交接跨进程边界使用的严格认证传输编解码器
   │     │  ├─ DesktopUpdatePort.java  # 单次桌面更新的主进程与独立执行器分阶段接缝
   │     │  ├─ DesktopUpdateResult.java  # 带程序及数据库成对恢复证据的更新终态
   │     │  ├─ DesktopUpdateSetupResult.java  # 仅由主进程执行的更新准备阶段结果
   │     │  ├─ check/  # 发布清单、信任策略与更新包验证
   │     │  │  ├─ DesktopUpdateManifest.java  # 单个已暂存桌面包的签名不可变元数据
   │     │  │  ├─ DesktopUpdateTrustPolicy.java  # 固定发布信任根及本地更新策略
   │     │  │  ├─ DesktopUpdateVerification.java  # 完整的软件包完整性、信任、版本及架构证据
   │     │  │  └─ DesktopUpdateVerifier.java  # 使用单个固定 Ed25519 发布信任根验证已暂存软件包
   │     │  └─ model/  # 更新状态、事件、版本及结构化失败合同
   │     │     ├─ DesktopArchitectureType.java  # 受支持的签名桌面包架构
   │     │     ├─ DesktopReleaseVersion.java  # 严格三段式桌面发布版本
   │     │     ├─ DesktopUpdateEvent.java  # 单个有界桌面更新证据事件
   │     │     ├─ DesktopUpdateException.java  # 桌面更新验证及替换的结构化受检失败
   │     │     ├─ DesktopUpdateFailureType.java  # 桌面更新边界持有的失败类型
   │     │     ├─ DesktopUpdateSetupStatus.java  # 启动独立更新器前的主进程终态
   │     │     ├─ DesktopUpdateState.java  # 单次桌面更新事务的有序证据状态
   │     │     └─ DesktopUpdateStatus.java  # 精确桌面更新终态
   │     ├─ window/  # Windows 窗口状态与进程显示集成
   │     │  └─ WindowPrivacy.java  # 仅隐藏当前应用所属子进程的原生窗口
   │     └─ resources/  # 随附运行资源
   │        └─ window/  # 窗口装配与生命周期
   │           └─ window-privacy.ps1  # 当前模块的随附运行资源
   ├─ appapi/  # 供 Mod 使用的平台公开 Java 合同
   │  ├─ pom.xml  # Maven 模块与依赖声明
   │  ├─ ai/  # 共享模型调用与配置修订查询入口
   │  │  ├─ AiService.java  # 绑定调用方并校验任务授权及预算的模型能力
   │  │  ├─ model/  # 通用模型用途、消息、请求、响应与用量
   │  │  │  ├─ AiMessage.java  # 包含独立图像附件列表的有序模型消息
   │  │  │  ├─ AiPurpose.java  # 平台调用选用的配置模型组
   │  │  │  ├─ AiRequest.java  # 具有明确时间及输出预算的模型调用
   │  │  │  ├─ AiResponse.java  # 不暴露提供方实现类型的模型响应及用量
   │  │  │  ├─ AiRole.java  # 有序模型对话中的消息角色
   │  │  │  └─ AiUsage.java  # 提供方可选报告的非负令牌用量
   │  │  ├─ vision/  # 视觉请求使用的不可变图像输入
   │  │  │  └─ AiImage.java  # 视觉请求使用的不可变图像字节
   │  │  └─ voice/  # 音频输入、转写结果与实时会话合同
   │  │     ├─ VoiceEvent.java  # 一个转写观测；增量追加，最终文字替换本片段所有增量
   │  │     ├─ VoiceListener.java  # 在传输线程串行投递的有界转写回调，应及时返回，不得等待会话完成
   │  │     ├─ VoiceOptions.java  # 实时输入为单声道小端 PCM16、24 kHz，最多 30 分钟
   │  │     ├─ VoiceRequest.java  # 完整 WAV 输入：小端 PCM16、单声道、24 kHz，最多 25 MB
   │  │     ├─ VoiceResponse.java  # 已确认最终文字及有序片段；实时失败结果可以保留已确认片段
   │  │     └─ VoiceSession.java  # 原任务实时会话；不切换模型或重放未知音频
   │  ├─ db/  # 任务限定的日常受管数据库访问入口
   │  │  ├─ ModDatabase.java  # 绑定任务并访问当前所有者数据代次的连接能力
   │  │  └─ schema/  # Schema 身份、初始化、校验与迁移合同
   │  │     ├─ CheckResult.java  # 作者对数据结构作出接受或拒绝判断的证据
   │  │     ├─ ModSchemaLifecycle.java  # 业务启动前由作者实现的初始化和只读验证
   │  │     ├─ SchemaAccess.java  # 初始化与迁移期间绑定操作的连接能力
   │  │     ├─ SchemaIdentity.java  # 与唯一代次绑定的数据所有者及版本
   │  │     └─ SchemaMigration.java  # 从受保护来源到独立目标的作者迁移逻辑
   │  ├─ file/  # 任务限定的通用文件能力入口
   │  │  ├─ FileService.java  # 文件查询、操作、来源、快照与工作区的组合入口
   │  │  ├─ action/  # 文件审批意图、操作策略、记录与结果
   │  │  │  ├─ DeleteEffect.java  # 删除效果不得静默升级
   │  │  │  ├─ FileActionService.java  # 任务授权文件副作用与原操作恢复
   │  │  │  ├─ FileActions.java  # 普通及前置授权文件操作的精确平台意图
   │  │  │  ├─ FileChoice.java  # 分开的目录/文件决定，未指定层级仍需交互
   │  │  │  ├─ FileOperation.java  # 原持久操作依据，不恢复旧授权
   │  │  │  └─ FileReport.java  # 包含部分进度和不确定性的逐项真实结果
   │  │  ├─ material/  # 跨 Mod 的受管素材引用、持有及原操作核对
   │  │  │  ├─ FileMaterialHold.java  # 消费方本地持久读占用，不是可转交的目录句柄
   │  │  │  ├─ FileMaterialService.java  # 原任务接管及消费方访问，句柄不得跨 Mod 服务边界
   │  │  │  ├─ MaterialInspection.java  # 只读原接管事实，无引用从不授权消费
   │  │  │  ├─ MaterialPublication.java  # 生产方预选原身份及直接交平台的普通路径
   │  │  │  ├─ MaterialRef.java  # 原任务素材的小型不可变身份，持有值本身不授予访问权
   │  │  │  └─ MaterialState.java  # 持久原素材生命周期，缺失不授予接管或访问
   │  │  ├─ model/  # 带主机身份的文件位置与对象观测
   │  │  │  ├─ FileInfo.java  # 实际对象及解析链，观测不授予权限
   │  │  │  └─ FilePath.java  # 带明确主机身份的绝对位置
   │  │  ├─ query/  # 任务绑定的文件观测与有界读取
   │  │  │  └─ FileQueryService.java  # 绑定任务的文件观测
   │  │  ├─ snapshot/  # 不可变来源快照的请求与资源持有合同
   │  │  │  ├─ FileSnapshot.java  # 实际关闭后释放的受管不可变来源目录
   │  │  │  ├─ FileSnapshotService.java  # 原任务不可变快照创建
   │  │  │  └─ SnapshotRequest.java  # 明确的相对文件集合及总复制容量预算
   │  │  ├─ source/  # 已授权来源的选择、列举与有界读取
   │  │  │  ├─ FileContent.java  # 从已授权来源取得的独立字节及摘要
   │  │  │  ├─ FileEntry.java  # 不跟随链接、不套用业务排除规则观察到的一个直接子项
   │  │  │  ├─ FileReadRequest.java  # 限制在已选来源根目录内的有界读取
   │  │  │  ├─ FileSelection.java  # 用户批准的不透明来源选择
   │  │  │  └─ FileSourceService.java  # 经授权的来源选取与有界读取
   │  │  └─ work/  # 可写暂存工作区及实际关闭合同
   │  │     ├─ FileWorkspace.java  # 保留至实际清理的任务所属可写暂存文件
   │  │     └─ FileWorkspaceService.java  # 原任务可写暂存与保留依据
   │  ├─ linux/  # 任务授权的远程连接与未完成传输查询入口
   │  │  ├─ LinuxService.java  # 绑定任务授权及主机信任校验的 SSH 能力
   │  │  ├─ action/  # 远程命令、传输与恢复的精确审批意图
   │  │  │  └─ RemoteActions.java  # 生成作者及平台传输共用的精确审核描述
   │  │  ├─ command/  # 精确远程命令请求与已观察执行结果
   │  │  │  ├─ CommandRequest.java  # 带独立输入及输出上限的精确远端脚本
   │  │  │  └─ CommandResult.java  # 不推断未观察退出状态的远端输出
   │  │  ├─ session/  # 受管 SSH 会话及已认证主机观测
   │  │  │  ├─ RemoteFacts.java  # 已观察的远端平台与认证主机指纹
   │  │  │  ├─ RemoteSession.java  # 产生副作用时需要已审核授权的受管 SSH 会话
   │  │  │  └─ RemoteTarget.java  # 不含秘密的固定连接预期，复制它不授予任务权限
   │  │  └─ transfer/  # 传输请求、进度、冲突、停滞与恢复依据
   │  │     ├─ TransferCheckpoint.java  # 持久原传输描述；恢复前由宿主重新加载其事实
   │  │     ├─ TransferConflict.java  # 解决冲突前展示的精确文件端点及实测大小
   │  │     ├─ TransferConflictChoice.java  # 同名文件冲突的明确用户选择
   │  │     ├─ TransferProgress.java  # 当前传输或核验阶段的已观察字节数
   │  │     ├─ TransferRequest.java  # 具有明确远端路径及容量限制的文件传输
   │  │     ├─ TransferResult.java  # 已观察的传输大小及内容摘要
   │  │     ├─ TransferStall.java  # 原文件操作在配置时长内无有效字节进展后已停止
   │  │     └─ TransferStallChoice.java  # 原物理传输停止后的明确选择
   │  ├─ mod/  # Mod 获取宿主能力及宿主调用启停的双向入口
   │  │  ├─ ModContext.java  # 绑定真实启动插件身份的平台能力集合
   │  │  ├─ ModEntrypoint.java  # 作者生命周期入口及部分启动失败的清理责任
   │  │  ├─ lifecycle/  # 启动模式、停止上下文与停止原因
   │  │  │  ├─ ModLaunchMode.java  # 正常启动或对未完成工作的隔离核对模式
   │  │  │  ├─ StopContext.java  # 后台幂等停止所使用的原因上下文
   │  │  │  └─ StopReason.java  # 不代表资源已经释放的明确生命周期停止原因
   │  │  ├─ model/  # Mod 稳定身份
   │  │  │  └─ ModIdentity.java  # 独立于显示名和包文件名的稳定插件身份
   │  │  └─ service/  # 跨 Mod 服务的注册、发现与受管调用
   │  │     ├─ AvailabilityState.java  # 宿主对一个声明提供方的观察，调用仍须实时准入
   │  │     ├─ ModServices.java  # 使用共享公开类型身份的显式依赖服务
   │  │     ├─ ServiceAvailability.java  # 不加载类或保留资源的小型可用性观察
   │  │     ├─ ServiceCall.java  # 不得泄漏提供方资源的同步跨插件调用
   │  │     ├─ ServiceHandle.java  # 每次调用时获取占用的服务发现句柄
   │  │     └─ ServiceRegistration.java  # 仅在实际释放后撤销的服务注册
   │  ├─ secret/  # 秘密创建、业务引用与授权短期读取入口
   │  │  ├─ SecretService.java  # 仅经授权临时回调提供所属秘密的服务
   │  │  ├─ access/  # 短期秘密字节访问回调
   │  │  │  └─ SecretConsumer.java  # 输入在使用后清零的短期秘密回调
   │  │  ├─ model/  # 绑定精确用途的不透明秘密标识
   │  │  │  └─ SecretId.java  # 绑定授权用途的不透明秘密引用
   │  │  └─ ref/  # 保护共享凭据的持久业务引用
   │  │     └─ SecretRef.java  # 保护共享凭据的持久业务引用
   │  ├─ server/  # 服务器查询与业务引用保护入口
   │  │  ├─ ServerService.java  # 绑定任务的服务器查询及持久引用管理
   │  │  ├─ model/  # 服务器标识与不含秘密的配置观测
   │  │  │  ├─ ServerId.java  # 平台管理服务器的稳定引用
   │  │  │  └─ ServerInfo.java  # 不含秘密的服务器配置与已观察信任信息
   │  │  └─ ref/  # 服务器专属业务引用
   │  │     └─ ServerRef.java  # 保护共享服务器的业务归属预留
   │  ├─ task/  # 任务准入、查询与恢复的服务入口
   │  │  ├─ TaskService.java  # 绑定调用方的任务准入、观察与核对服务
   │  │  ├─ action/  # 精确动作意图、审批许可、权限模式与输入交互
   │  │  │  ├─ ActionIntent.java  # 绑定任务资源的精确待审核副作用与修订
   │  │  │  ├─ ActionSemantics.java  # 绑定精确副作用的、经业务校验的操作含义及实际目标
   │  │  │  ├─ ActionTarget.java  # 准入服务器上已解析的业务目标，不是本地化标签或按钮名称
   │  │  │  ├─ ApprovalGrant.java  # 授权效力由宿主核实的不透明审批标识
   │  │  │  ├─ AuthorizationMode.java  # 为一个用户启动流程捕获的平台权限策略，独立于 AI 审批
   │  │  │  ├─ InteractionService.java  # 绑定已接纳任务的宿主审核及输入交互
   │  │  │  └─ WorkflowGrant.java  # 仅覆盖原任务回调的宿主签发委托
   │  │  ├─ log/  # 所属日志登记、事件及实际写入结果
   │  │  │  ├─ LogCategory.java  # 包含可选作者日志表的所属诊断类别
   │  │  │  ├─ LogEvent.java  # 不得包含凭据材料的诊断事件
   │  │  │  ├─ LogLevel.java  # 独立于业务操作状态的诊断级别
   │  │  │  ├─ LogReadResult.java  # 一个原所属目标的有界脱敏诊断
   │  │  │  ├─ LogService.java  # 明确返回存储结果的所属结构化诊断服务
   │  │  │  ├─ LogStorage.java  # 不猜测持久化成功的实际诊断存储结果
   │  │  │  ├─ LogTarget.java  # 当前日志所有者的不透明已登记目的地
   │  │  │  └─ LogWriteResult.java  # 已观察的诊断持久化与截断结果
   │  │  ├─ model/  # 任务准入请求、进度状态与不可变快照
   │  │  │  ├─ TaskRequest.java  # 包含完整初始服务器资源集合的任务准入请求
   │  │  │  ├─ TaskSnapshot.java  # 终态结果保持一致的不可变任务状态
   │  │  │  └─ TaskState.java  # 区分取消请求与业务结果的任务进度状态
   │  │  ├─ process/  # 受管进程启动、协议消息与实际退出合同
   │  │  │  ├─ ManagedProcess.java  # 直到实际退出前持续保留占用的协议进程
   │  │  │  ├─ ProcessMessage.java  # 传输前需要校验 JSON 对象的协议消息
   │  │  │  ├─ ProcessService.java  # 不暴露平台凭据的任务所属进程启动
   │  │  │  └─ ProcessSpec.java  # 字面进程参数及有界握手配置
   │  │  ├─ query/  # 共用查询分页结果与继续游标
   │  │  │  └─ PageSlice.java  # 独立查询结果集合及可选的后续游标
   │  │  ├─ recover/  # 原任务核对回调、恢复注册与有界检查点
   │  │  │  ├─ RecoveryRegistration.java  # 允许人工核对保留任务的恢复注册
   │  │  │  ├─ TaskCheckpoint.java  # 保留至平台任务归档的原任务有界作者依据
   │  │  │  └─ TaskRecovery.java  # 复用原资源且不重放未知副作用的结果核对
   │  │  ├─ ref/  # 业务提交与核对共用的资源引用状态
   │  │  │  ├─ ResourceRefResult.java  # 业务提交已经核实或仍然未知的结果
   │  │  │  └─ ResourceRefState.java  # 持续保护未知引用的预留状态
   │  │  ├─ result/  # 业务结果、失败原因与公开异常合同
   │  │  │  ├─ ApiException.java  # 报告未接纳的请求或失败的本地能力访问
   │  │  │  ├─ OperationResult.java  # 失败或不确定时具有明确证据的业务结果
   │  │  │  ├─ Problem.java  # 不暴露实现异常的结构化失败或不确定原因
   │  │  │  ├─ ProblemCode.java  # 公开能力边界使用的稳定失败类别
   │  │  │  └─ TaskResult.java  # 独立于尚待完成资源清理的业务结果
   │  │  └─ run/  # 任务执行上下文、取消、结果观察与资源持有
   │  │     ├─ CancelToken.java  # 不强制中断执行的协作取消请求
   │  │     ├─ ResourceLease.java  # 仅在实际清理后释放的受追踪资源占用
   │  │     ├─ TaskContext.java  # 已接纳任务的身份及协作资源登记
   │  │     ├─ TaskHandle.java  # 仅在必要清理完成后结束的可观察任务结果
   │  │     └─ TaskWork.java  # 观察取消并保留未完成资源的作者任务逻辑
   │  ├─ ui/  # 页面登记、打开与环境查询入口
   │  │  ├─ UiService.java  # 不暴露宿主实现类型的所属页面挂载服务
   │  │  └─ page/  # 页面创建、登记句柄、描述、环境更新与关闭合同
   │  │     ├─ ModPage.java  # 在事件分发线程创建和释放的作者 Swing 视图
   │  │     ├─ PageFactory.java  # 页面重新打开时创建新视图的惰性工厂
   │  │     ├─ PageRegistration.java  # 关闭时释放已打开视图的所属页面注册
   │  │     ├─ PageSpec.java  # 唯一根工作区身份或主导航之外的设置/预览扩展
   │  │     ├─ PlatformPageKind.java  # 不含私有控件或配置权限的固定平台目的地
   │  │     └─ UiEnvironment.java  # 当前宿主语言、主题及锁定状态
   │  └─ resources/  # 随公开 API 发布的版本资源
   │     └─ build.properties  # 由当前构建生成 API 版本
   ├─ mod/  # 官方 Mod 的 Maven 模块分组
   │  ├─ pom.xml  # Maven 模块与依赖声明
   │  ├─ deployment/  # 官方部署 Mod：分析、部署与应用管理
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ Mod.java  # 持有官方部署初始化及绑定平台的生命周期
   │  │  ├─ ai/  # 部署 AI 提示、提案解析与可执行动作约束
   │  │  │  ├─ DeploymentAgentPatch.java  # 仅用于任务所属源码、与修订绑定的结构化补丁
   │  │  │  ├─ DeploymentAgentPlan.java  # 自主模型提出、由证据支持的中立应用图
   │  │  │  ├─ DeploymentAgentPlanProtocol.java  # 将严格自主图转换为既有类型化部署值
   │  │  │  ├─ DeploymentAgentProposal.java  # 一项严格串行的自主工具请求
   │  │  │  ├─ DeploymentAgentProtocol.java  # 不具执行权限的严格自主动作模式
   │  │  │  ├─ DeploymentAiJson.java  # 通过真实平台 AI 能力发送有界的部署专用 JSON 请求
   │  │  │  ├─ DeploymentAuthorizationPolicy.java  # 将独立审核风险与已抓取人工授权重复策略分开
   │  │  │  ├─ DeploymentCommandEvidence.java  # 增补完整可执行输入，受保护数据载荷继续保持不透明
   │  │  │  ├─ DeploymentCommandReviewScope.java  # 增补原任务命令审核，原样借用所有真实所有者能力
   │  │  │  ├─ DeploymentCommandStaticGate.java  # 在模型审核或候选准备前拒绝明确的灾难性目标
   │  │  │  ├─ DeploymentFixedCommandApproval.java  # 获取精确原宿主许可前审核实际固定命令
   │  │  │  ├─ DeploymentLowRiskRequiredException.java  # 要求不同且独立审核的低风险动作的已知未发送拒绝
   │  │  │  └─ DeploymentWorkflowAuthorization.java  # 仅按已抓取部署策略开启真实宿主所属原流程作用域
   │  │  ├─ api/  # 向其他 Mod 公开的应用与部署管理合同
   │  │  │  ├─ ApplicationAutostartState.java  # 实际自动启动观察，包括混合组件事实
   │  │  │  ├─ ApplicationRunState.java  # 已验证的应用运行状态或明确未知
   │  │  │  ├─ DeploymentActivation.java  # 已验证目标激活与清理结果
   │  │  │  ├─ DeploymentApi.java  # 公开部署快照、生命周期与备份交接服务
   │  │  │  ├─ DeploymentApplicationId.java  # 稳定部署应用与精确服务器身份
   │  │  │  ├─ DeploymentApplicationSnapshot.java  # 应用事实副本；只有检查会刷新远端状态
   │  │  │  ├─ DeploymentApplicationUsage.java  # 仅从精确完整活动发布复制的无秘密用法
   │  │  │  ├─ DeploymentArtifact.java  # 一次采集所属的已验证远端产物
   │  │  │  ├─ DeploymentBinding.java  # 不含部署私有模型类型的已审阅数据绑定
   │  │  │  ├─ DeploymentCollection.java  # 采集进度与精确结果产物
   │  │  │  ├─ DeploymentCollectionRequest.java  # 待审阅的精确应用修订与所选绑定集合
   │  │  │  ├─ DeploymentHandoff.java  # 公开的持久交接进度
   │  │  │  ├─ DeploymentHandoffState.java  # 持久采集或恢复交接阶段
   │  │  │  ├─ DeploymentLifecycleAction.java  # 明确的受管生命周期动作
   │  │  │  ├─ DeploymentLifecycleResult.java  # 已验证的生命周期前后状态
   │  │  │  ├─ DeploymentRestoreRequest.java  # 经验证备份归档生成的已审阅目标候选
   │  │  │  ├─ RestoreFile.java  # 备份已验证并用于目标准备的本地文件
   │  │  │  └─ VersionedRuntime.java  # 可供归档转交的不透明版本化运行字节
   │  │  ├─ db/  # 部署私有 Schema、业务记录与发布恢复依据
   │  │  │  ├─ DeploymentAssistedBuildIntent.java  # 任一固定环境恢复效果前的持久类型化恢复同意
   │  │  │  ├─ DeploymentAssistedBuildReview.java  # 一次实际已知失败审阅，绝非重放发布权限
   │  │  │  ├─ DeploymentAssistedBuildReviewCodec.java  # 持久化完整非秘密构建审阅，检查点仅持有有界指针
   │  │  │  ├─ DeploymentAttemptTemplateCodec.java  # 使用原完整固定配置留存后续不可变提案
   │  │  │  ├─ DeploymentConfigStore.java  # 保存不可变的普通配置快照、条目及精确发布绑定
   │  │  │  ├─ DeploymentExecutionCodec.java  # 执行身份、预留证据及正式结果的严格有界JSON
   │  │  │  ├─ DeploymentExecutionIdentity.java  # 原审阅操作的不可变身份
   │  │  │  ├─ DeploymentExecutionRecord.java  # 只读持久执行证据，包括不解释的未来JSON版本
   │  │  │  ├─ DeploymentExecutionStore.java  # 副作用前保存原执行证据，并将发布与正式结果同事务提交
   │  │  │  ├─ DeploymentGraphStore.java  # 在既有所属单元上保存完整拓扑，不查询平台私有表
   │  │  │  ├─ DeploymentHealthCodec.java  # 两端及备份共用的完整严格健康载荷
   │  │  │  ├─ DeploymentJsonDocument.java  # 版本化业务证据共用的严格JSON原语
   │  │  │  ├─ DeploymentPayloadChecker.java  # 通过持久化所用版本化编解码器读取原有界业务载荷
   │  │  │  ├─ DeploymentPayloadReader.java  # 读取有界持久文档，不将无界BLOB加载入内存
   │  │  │  ├─ DeploymentPreferences.java  # 在此Mod所属偏好表保存实际部署页面选择
   │  │  │  ├─ DeploymentProjectRecord.java  # 已保存项目身份及完整已观察来源修订
   │  │  │  ├─ DeploymentProjectStore.java  # 经原任务及受管H2代次保存完整项目来源修订
   │  │  │  ├─ DeploymentPublishAttemptCodec.java  # 一个原执行至多三个不可变操作的明确有界请求格式
   │  │  │  ├─ DeploymentPublishAttemptIntent.java  # 独立发布操作的任一固定环境效果前完整人工同意
   │  │  │  ├─ DeploymentPublishAttemptProof.java  # 留存一次完整恢复的发布操作，不结束原正式任务
   │  │  │  ├─ DeploymentPublishAttemptReview.java  # 核实恢复后的新操作完整类型化同意，绝非任务重放
   │  │  │  ├─ DeploymentPublishAttemptStore.java  # 经同一原行锁及已预留正式执行推进有界操作同意
   │  │  │  ├─ DeploymentPublishProposal.java  # 与服务结果封套独立的已持久非秘密业务输入
   │  │  │  ├─ DeploymentPublishStore.java  # 原子记录已验证组件发布及其完整应用图
   │  │  │  ├─ DeploymentPublishTemplateCodec.java  # 任一远端发布前编码完整非秘密原业务模板
   │  │  │  ├─ DeploymentRefAssociations.java  # 读取一个原平台关联的权威保留业务行
   │  │  │  ├─ DeploymentRefIntent.java  # 平台引用预留前保存的非秘密事实
   │  │  │  ├─ DeploymentRefKey.java  # 绑定精确保留部署业务行的版本化键
   │  │  │  ├─ DeploymentReleaseChecker.java  # 不可变发布历史与正式业务结果间的仅SELECT核验
   │  │  │  ├─ DeploymentReleaseCodec.java  # 使用既有二进制运行格式的版本化完整发布清单
   │  │  │  ├─ DeploymentReleaseIdentity.java  # 单个完整应用发布的不可变存储身份
   │  │  │  ├─ DeploymentReleaseRecord.java  # 不可变历史文档与当前记录的发布状态
   │  │  │  ├─ DeploymentReleaseSnapshot.java  # 完整不可变运行图及保留配置和秘密绑定身份
   │  │  │  ├─ DeploymentReleaseStore.java  # 在成功发布的同一事务中保留原发布历史
   │  │  │  ├─ DeploymentResourceCodec.java  # 经审阅无秘密受管资源绑定的严格版本化存储编解码器
   │  │  │  ├─ DeploymentReviewedPlanDigest.java  # 稳定的执行前审阅身份，区别于最终发布清单身份
   │  │  │  ├─ DeploymentRuntimeCodec.java  # 单个已审阅非秘密运行时定义的严格版本化存储编解码器
   │  │  │  ├─ DeploymentSchema.java  # 通过公开受管 JDBC 初始化并读取固定官方部署结构
   │  │  │  ├─ DeploymentSecretStore.java  # 持久化不可变秘密元数据和精确发布关联，不读取明文
   │  │  │  ├─ DeploymentStoragePathCodec.java  # 经审阅非秘密组件数据路径的严格版本化存储编解码器
   │  │  │  ├─ DeploymentStorageTransaction.java  # 提交所属业务事务，不将不确定的确认误记为已失败
   │  │  │  ├─ DeploymentUnitCommit.java  # 一个已验证组件发布的精确存储输入，绝不是远端命令
   │  │  │  ├─ DeploymentUnitRuntimeCodec.java  # 不推测服务对外范围的精确激活与清单状态
   │  │  │  └─ DeploymentWorkloadCodec.java  # 持久化与发布身份共用的有界执行载荷
   │  │  ├─ file/  # 部署素材筛选、封存、归档与来源核对
   │  │  │  ├─ DeploymentSourceArchive.java  # 带实际压缩字节摘要的冻结部署归档
   │  │  │  ├─ DeploymentSourceArchiver.java  # 仅使用平台所属暂存及快照构建确定性归档
   │  │  │  ├─ DeploymentSourceBrowser.java  # 供辅助与自主部署共用的所属冻结源码只读分页访问
   │  │  │  ├─ DeploymentSourceMaterial.java  # 供后续分析及部署步骤共用的纯源码冻结素材
   │  │  │  ├─ DeploymentSourcePolicy.java  # 从原源码归档规则迁入的部署专用排除策略
   │  │  │  ├─ DeploymentSourceSetup.java  # 通过公开能力筛选部署文件，并在分析前冻结
   │  │  │  ├─ DeploymentSourceTar.java  # 从冻结素材写入原确定性纯源码 ustar/gzip 格式
   │  │  │  └─ git/  # 通过平台能力准备和冻结 Git 来源
   │  │  │     ├─ DeploymentGitAccess.java  # 仅在 Git 来源操作内构造的可选类型适配器
   │  │  │     ├─ DeploymentGitMaterial.java  # 不可变解析 Git 身份及分别所属的分析素材和归档
   │  │  │     ├─ DeploymentGitMessages.java  # 一次 Git 准备请求的精确业务消息及退出凭据
   │  │  │     ├─ DeploymentGitRef.java  # 用户选择的可变或不可变 Git 引用；分析前会将其解析为 Commit
   │  │  │     ├─ DeploymentGitRemote.java  # 解析后的 Git 远端；其位置绝不嵌入凭据
   │  │  │     ├─ DeploymentGitRequest.java  # 只读 Git 分析快照的有界输入；凭据始终位于此值之外
   │  │  │     └─ DeploymentGitSetup.java  # 经原平台进程及来源归属准备固定 Git 素材
   │  │  ├─ linux/  # 通过平台远程会话执行固定部署协议
   │  │  │  ├─ DeploymentBuildBudget.java  # 仅将实际远端命令执行计入同一已审阅构建预算；人工审核期间暂停
   │  │  │  ├─ DeploymentBuildCommandChannel.java  # 仅通过借用且经宿主审核的会话发送固定 helper 构建动作
   │  │  │  ├─ DeploymentBuildOutputProtocol.java  # 将不超过已审阅构建输出限制的流转换成固定短回执
   │  │  │  ├─ DeploymentCandidateChannel.java  # 发送固定上传前探测、任务所属候选创建及一次精确归档传输
   │  │  │  ├─ DeploymentCommandReview.java  # 模式所属审核在真实宿主许可前接收完整不可变请求
   │  │  │  ├─ DeploymentCommandReviews.java  # 按所属模式路由固定命令，不替换宿主权限
   │  │  │  ├─ DeploymentContainerArguments.java  # 将受约束的容器运行模型转换为确定性的辅助程序参数
   │  │  │  ├─ DeploymentEcosystemCapabilityProbe.java  # 渲染只读语言与构建工具能力探测
   │  │  │  ├─ DeploymentHelperInstallerScript.java  # 共用 helper 及隔离安装，不选择项目语言
   │  │  │  ├─ DeploymentManagedHelper.java  # 通过借用的宿主授权远端会话执行固定 helper 安装与探测
   │  │  │  ├─ DeploymentManagedHelperBundle.java  # 从固定职责片段拼装 root 持有的受管 helper 并拒绝协议漂移
   │  │  │  ├─ DeploymentNativeDatabaseResults.java  # 严格解码固定原生数据库 helper 返回的非秘密事实
   │  │  │  ├─ DeploymentNativeDatabaseWire.java  # 有界原生数据库 helper 帧及精确审核远端传输
   │  │  │  ├─ DeploymentOriginalPublishStatus.java  # 一项原发布任务的精确只读 helper 观察
   │  │  │  ├─ DeploymentPlatformCapabilityParser.java  # 解析原固定主机探测，不虚构缺失能力
   │  │  │  ├─ DeploymentPlatformCapabilityProbe.java  # 用于采集部署发行版、容器和 CPU 事实的固定只读 Shell 程序
   │  │  │  ├─ DeploymentPublishArguments.java  # 从已审阅非秘密发布事实投影的固定helper标量
   │  │  │  ├─ DeploymentPublishChannel.java  # 经原已审阅会话发送固定受管发布动作
   │  │  │  ├─ DeploymentPublishEvidenceChannel.java  # 读取并清理真实根任务及独立有界尝试的证据
   │  │  │  ├─ DeploymentPublishReceipt.java  # 分类完整有界发布回执，不改变原结果
   │  │  │  ├─ DeploymentRemoteReleaseSnapshot.java  # 原 helper 所属回滚快照，区别于本机业务发布行
   │  │  │  ├─ DeploymentRemoteRuntimeObservation.java  # 远端发布或回滚后的精确所属运行时观测
   │  │  │  ├─ DeploymentRuntimeArguments.java  # 将一个已验证的非容器运行定义转换为辅助程序验证的标量参数
   │  │  │  ├─ DeploymentSendGuard.java  # 在远端发送紧前核对调用方持有的绑定
   │  │  │  └─ DeploymentWorkloadArguments.java  # 以 Base64 封装的有界 UTF-8 字段
   │  │  ├─ mod.json  # CLASS 模式的实际 Mod 描述
   │  │  ├─ secret/  # 部署秘密修订、引用与发布绑定
   │  │  │  ├─ DeploymentSecretProvisionActionLog.java  # 一项新建凭据与不可变业务修订的有界原任务依据
   │  │  │  ├─ DeploymentSecretProvisionFlow.java  # 在原任务下创建一次凭据及不可变修订，此后仅核对持久依据
   │  │  │  └─ SecretValueSource.java  # 仅在已接纳原任务内提供明文；具体提交契约决定数组所有权
   │  │  ├─ service/  # 部署分析、审阅、执行与管理流程
   │  │  │  ├─ agent/  # 自主模式的提案、工具调用与执行门禁
   │  │  │  │  ├─ DeploymentAgentActionLog.java  # 独立于标准部署的持久自主任务状态与动作依据
   │  │  │  │  ├─ DeploymentAgentControl.java  # 仅在动作已结清边界与一项所属工作线程协作
   │  │  │  │  ├─ DeploymentAgentDeliveryPort.java  # 供独立自主引擎使用的窄真实远端工具与共用受管发布能力
   │  │  │  │  ├─ DeploymentAgentEvidenceLimit.java  # 保留每项既有正式恢复证明，同时限制新增观察
   │  │  │  │  ├─ DeploymentAgentHistory.java  # 读取既有正式自主依据并明确仅检查其原候选
   │  │  │  │  ├─ DeploymentAgentModels.java  # 将部署、审批及发送绑定到一份明确采用的模型修订
   │  │  │  │  ├─ DeploymentAgentPageService.java  # 提供自主专用页面查询与原任务接纳
   │  │  │  │  ├─ DeploymentAgentProjectChannel.java  # 仅经任务所属已审核 SSH 会话发送旧受保护 agent helper 动作
   │  │  │  │  ├─ DeploymentAgentQuestionScope.java  # 仅解析明确的任务所属问题范围，复合Shell动作保留精确绑定
   │  │  │  │  ├─ DeploymentAgentRecovery.java  # 仅核对自主任务留存的原提案与远端影响
   │  │  │  │  ├─ DeploymentAgentRemoteTools.java  # 将原认证任务适配至受保护自主工具与共用发布
   │  │  │  │  ├─ DeploymentAgentReview.java  # 具精确策略及人工升级的独立动作审核者
   │  │  │  │  ├─ DeploymentAgentSession.java  # 独立于标准分析的串行证据驱动部署循环
   │  │  │  │  └─ DeploymentAgentWorkflow.java  # 持有一项独立自主任务并仅核对其原持久效果
   │  │  │  ├─ core/  # 各部署模式共用的流程和纯值
   │  │  │  │  ├─ DeploymentGitSourceActionLog.java  # 在宿主所属作者检查点上原子推进的有界原任务 Git 依据
   │  │  │  │  ├─ DeploymentGitSourceFlow.java  # 执行一次已审阅 Git 项目任务，并仅核对其原持久依据
   │  │  │  │  ├─ DeploymentProjectSaveActionLog.java  # 写入前记录一次完整本地项目 CAS，不保留来源授权
   │  │  │  │  ├─ DeploymentProjectSourceRecorder.java  # 准备本机授权来源，并保存实际位置与冻结摘要；Git使用原记账Flow
   │  │  │  │  ├─ DeploymentPublish.java  # 可供一次本机原子发布的作者已核实结果
   │  │  │  │  ├─ DeploymentPublishPlan.java  # 平台预留前已具备的不可变审阅发布事实
   │  │  │  │  ├─ DeploymentRefFlow.java  # 提交真实发布任务，并在不重放副作用的条件下核对原引用
   │  │  │  │  ├─ DeploymentRemoteRecovery.java  # 由Mod装配的窄原任务发布恢复边界
   │  │  │  │  ├─ DeploymentSourceSelectionService.java  # 仅在原选择器授权关闭后返回本地位置元数据
   │  │  │  │  ├─ config/  # 部署配置值、作用范围与不可变配置快照
   │  │  │  │  │  ├─ DeploymentConfigEntry.java  # 不可变配置快照中的一个经过类型检查的非秘密值
   │  │  │  │  │  ├─ DeploymentConfigInput.java  # 解析桌面端有界构建/运行配置记法
   │  │  │  │  │  ├─ DeploymentConfigScopeType.java  # 非秘密配置值被使用的受限时点
   │  │  │  │  │  ├─ DeploymentConfigSnapshot.java  # 一个已发布应用版本的不可变、以摘要寻址的普通配置
   │  │  │  │  │  └─ DeploymentConfigValue.java  # 类型化的非秘密配置值，不能携带 Shell 片段
   │  │  │  │  ├─ delivery/  # 各模式共用的部署交付与固定内容
   │  │  │  │  │  ├─ DeploymentGraphPublisher.java  # 以真实健康和已知失败回滚执行一项中立受管图发布
   │  │  │  │  │  ├─ DeploymentManagedUnitResolver.java  # 为两种部署模式解析规范归属，不使用来源或计划散列
   │  │  │  │  │  └─ DeploymentOriginalPublishCommit.java  # 在已接纳原任务内以精确引用保护提交经核实图
   │  │  │  │  ├─ execution/  # 原任务来源采集、管理操作与恢复流程
   │  │  │  │  │  ├─ DeploymentApiProvider.java  # 部署所属同步API，每次调用均留在已认证根任务下
   │  │  │  │  │  ├─ DeploymentApplicationHosts.java  # 仅为本地用法投影读取绑定调用方的非秘密已配置主机
   │  │  │  │  │  ├─ DeploymentApplicationList.java  # 合并已存正式发布与接管运行记录，不观察远端服务器
   │  │  │  │  │  ├─ DeploymentApplicationPresentation.java  # 仅编辑本地展示信息，保留已审阅运行与发布身份
   │  │  │  │  │  ├─ DeploymentApplicationReader.java  # 在一份所属快照中读取完整已发布应用，排除草稿及未确认发布
   │  │  │  │  │  ├─ DeploymentApplicationRunner.java  # 仅通过已验证helper观测管理部署所属的已发布应用
   │  │  │  │  │  ├─ DeploymentApplicationsService.java  # 按精确原服务器范围接纳应用页面操作
   │  │  │  │  │  ├─ DeploymentArchivedRuntimeCodec.java  # 归档完整规范发布及非秘密凭据元数据
   │  │  │  │  │  ├─ DeploymentCollectionEvidence.java  # 原采集身份、当前状态及封印回执校验
   │  │  │  │  │  ├─ DeploymentCollectionFlow.java  # 以留存维护依据编排完整原任务来源采集
   │  │  │  │  │  ├─ DeploymentCollectionPlan.java  # 从同一保留发布构造完整固定helper输入，绝不从归档猜测
   │  │  │  │  │  ├─ DeploymentCollectionWork.java  # 同一借用采集会话及原持久交接下的阶段
   │  │  │  │  │  ├─ DeploymentExternalApplications.java  # 扫描并接管精确外部服务，不冒充部署归属或重写其文件
   │  │  │  │  │  ├─ DeploymentHandoffChannel.java  # 仅在借用会话上发送固定的部署所属采集与恢复helper动作
   │  │  │  │  │  ├─ DeploymentHandoffLedger.java  # 在部署自身Schema中保存原调用归属、审阅与恢复事实
   │  │  │  │  │  ├─ DeploymentManagementActionLog.java  # 在远端副作用前记录原管理任务，并保留未知阶段
   │  │  │  │  │  ├─ DeploymentRestoreCleanup.java  # 已核实的原数据库及组件材料清理
   │  │  │  │  │  ├─ DeploymentRestoreEvidence.java  # 精确审阅目标、运行健康及恢复结果依据
   │  │  │  │  │  ├─ DeploymentRestoreFlow.java  # 原任务完整恢复，使用不可变步骤回执且不重放未知效果
   │  │  │  │  │  ├─ DeploymentRestorePlan.java  # 完整原归档输入，不查历史来源服务器
   │  │  │  │  │  ├─ DeploymentRestoreRecovery.java  # 原激活补偿与已核实提交后恢复
   │  │  │  │  │  ├─ DeploymentRestoreSetup.java  # 完整配置与秘密权限及原候选准备
   │  │  │  │  │  ├─ DeploymentRestoreState.java  # 单项已认证任务的原恢复账本游标
   │  │  │  │  │  ├─ DeploymentRestoreSteps.java  # 原恢复命令身份及持久步骤回执
   │  │  │  │  │  ├─ DeploymentRestoreTransfers.java  # 在提供方自身权限下明确清理原上传暂存
   │  │  │  │  │  └─ DeploymentRetainedReleases.java  # 以原任务依据切换完整保留应用发布
   │  │  │  │  └─ model/  # 跨部署模式共用的纯数据类型
   │  │  │  │     ├─ DeploymentAccessUrl.java  # 用户声明的已部署应用 HTTP(S) 访问 URL
   │  │  │  │     ├─ DeploymentApplicationGraph.java  # 完整审阅的应用拓扑，不含秘密或平台实现类型
   │  │  │  │     ├─ DeploymentBuildEnvironment.java  # 一次固定远端构建的已审阅非秘密值
   │  │  │  │     ├─ DeploymentBuildLimits.java  # 全部固定受管部署构建入口经过审阅的明确限制
   │  │  │  │     ├─ DeploymentCommand.java  # 经审阅程序入口与参数，不是 Shell 文本
   │  │  │  │     ├─ DeploymentCompanion.java  # 随所属应用原子交付的仅构建单元
   │  │  │  │     ├─ DeploymentConfigException.java  # 以公开且不含秘密的失败说明拒绝确定性部署输入
   │  │  │  │     ├─ DeploymentCurrentRelease.java  # 一个部署单元最后确认的发布身份
   │  │  │  │     ├─ DeploymentDatabaseBinding.java  # 绑定到一个经审阅组件的不可变数据库身份
   │  │  │  │     ├─ DeploymentDatabaseConnection.java  # 经审阅的非秘密数据库连接；密码保持为精确秘密引用
   │  │  │  │     ├─ DeploymentDatabaseEngineType.java  # 不包含备份专属证据的受管数据库族
   │  │  │  │     ├─ DeploymentEndpoint.java  # 传输、发布和应用对外范围是独立事实
   │  │  │  │     ├─ DeploymentFileBinding.java  # 一个经审阅逻辑持久化文件树的稳定受管身份
   │  │  │  │     ├─ DeploymentHealthCheck.java  # 经审阅的网络、进程与隔离安装验证策略
   │  │  │  │     ├─ DeploymentIdentityMode.java  # 不包含临时 UID 的持久执行策略
   │  │  │  │     ├─ DeploymentInputField.java  # 外部输入只读且不进入应用内容备份
   │  │  │  │     ├─ DeploymentProjectKind.java  # 部署计划器考虑的单组件项目类型
   │  │  │  │     ├─ DeploymentProjectSource.java  # 完整已观察来源事实；已存位置绝不授予读取权
   │  │  │  │     ├─ DeploymentRemoteWorkspace.java  # 仅根据已验证应用 ID 和压缩归档字节摘要推导的固定服务端路径
   │  │  │  │     ├─ DeploymentResourceBindings.java  # 精确审阅的文件绑定及可选数据库绑定审阅状态
   │  │  │  │     ├─ DeploymentRuntimeDefinition.java  # 恰好一个部署单组件项目类型的类型化运行定义
   │  │  │  │     ├─ DeploymentSecretRef.java  # 一个指向不可变应用秘密修订的透明引用；它绝不包含秘密值
   │  │  │  │     ├─ DeploymentSecretRevision.java  # 不可变秘密元数据，不透明凭据身份绝不包含其值
   │  │  │  │     ├─ DeploymentSecretSelection.java  # 不含明文的可选修订及其已记录用途
   │  │  │  │     ├─ DeploymentServerIdentity.java  # 目标服务器及其可信 SSH 主机密钥的非秘密身份
   │  │  │  │     ├─ DeploymentSqliteRequirement.java  # 嵌入式 SQLite 的文件位置与应用接入声明
   │  │  │  │     ├─ DeploymentStorageLocation.java  # 已审阅存储选择，未确定位置绝不变成默认位置
   │  │  │  │     ├─ DeploymentStoragePath.java  # 一个具有显式模式和访问契约的逻辑持久化数据路径
   │  │  │  │     ├─ DeploymentToolchainType.java  # 与支持清单无关的生态版本身份和分支精度
   │  │  │  │     ├─ DeploymentToolchainVersion.java  # 数字发布身份，解析不查询构建支持目录
   │  │  │  │     ├─ DeploymentUnitIdentity.java  # 由 WindowsToLinux 拥有的应用不可变身份
   │  │  │  │     ├─ DeploymentUnitRuntimeConfig.java  # 某个受管应用最后一次成功部署的运行时契约
   │  │  │  │     ├─ DeploymentWorker.java  # 共享主服务运行时、身份与存储的前台工作进程
   │  │  │  │     ├─ DeploymentWorkload.java  # 共享的经审阅执行、对外服务与交付契约
   │  │  │  │     ├─ analysis/  # 静态分析事实与来源信息
   │  │  │  │     │  ├─ AnalysisEvidence.java  # 单个确定性项目事实的紧凑、非秘密来源
   │  │  │  │     │  ├─ DeploymentAdmissionStatus.java  # 项目进入部署计划前的确定性部署准入结果
   │  │  │  │     │  ├─ EvidenceConfidenceLevel.java  # 单个确定性事实的置信度，与任何 AI 建议相互独立
   │  │  │  │     │  └─ RejectionReason.java  # 项目无法进入受管部署流程的确定性原因
   │  │  │  │     ├─ assessment/  # 项目报告、问题与分析结论
   │  │  │  │     │  ├─ DeploymentProjectReport.java  # 将缺失用户决定与硬性安全拒绝分开的部署静态分析结果
   │  │  │  │     │  ├─ MultiPartProjectReport.java  # 具有组件范围原因的确定性混合项目分析
   │  │  │  │     │  └─ PartIssue.java  # 一个组件范围的输入要求或硬安全拒绝
   │  │  │  │     ├─ capability/  # 主机能力、支持等级与匹配结果
   │  │  │  │     │  ├─ DeploymentEcosystemToolType.java  # 可用观测版本约束部署的固定语言或构建工具能力
   │  │  │  │     │  ├─ DeploymentHostCapabilityStatus.java  # 将实时主机证据与部署矩阵匹配后的保守结果
   │  │  │  │     │  └─ DeploymentLinuxCapabilityFacts.java  # 仅用于决定能否提供部署计划的实时、非秘密主机事实
   │  │  │  │     ├─ ecosystem/  # 语言、构建和运行生态的类型信息
   │  │  │  │     │  └─ db/  # 原生数据库实例、软件包与状态事实
   │  │  │  │     │     ├─ DatabaseRequirement.java  # 从组件提取或由用户补齐的非秘密数据库声明
   │  │  │  │     │     ├─ DatabaseSchemaReview.java  # 与源码绑定的验证结果，来自已验证初始化或单独获批的现有模式操作
   │  │  │  │     │     ├─ DatabaseVersionRequirement.java  # 显式数据库版本约束，较新版本兼容最低版本要求
   │  │  │  │     │     └─ DeploymentNativeDatabaseFacts.java  # 从固定 helper 观察到的不含秘密的原生数据库库存与目标事实
   │  │  │  │     ├─ language/  # 源码语言类别与观察事实
   │  │  │  │     │  ├─ LanguageEcosystemType.java  # 不含主要语言排序的确定性语言生态观测
   │  │  │  │     │  ├─ LanguageFactKind.java  # 可用于预填审阅表单的稳定确定性语言相关值
   │  │  │  │     │  ├─ ProjectLanguageFacts.java  # 在不执行源码且不选择主要语言的情况下收集的确定性语言事实
   │  │  │  │     │  └─ SourceLanguageType.java  # 由有界文件路径或元数据条目证实的源码语言
   │  │  │  │     ├─ message/  # 部署文案与结构化消息
   │  │  │  │     │  └─ DeploymentLocalizedMessage.java  # 不将领域代码与显示语言耦合的用户可见消息
   │  │  │  │     ├─ project/  # 项目分类、声明与部署单元信息
   │  │  │  │     │  ├─ DeploymentArchitectureType.java  # 一个精确且可部署的项目类型与构建工具架构组合
   │  │  │  │     │  ├─ DeploymentBuildToolType.java  # 固定的目标机构建工具入口，绝不是任意命令行
   │  │  │  │     │  ├─ DeploymentProjectFacts.java  # 一个用户选定的部署项目类型的不可变确定性事实；未执行任何项目代码
   │  │  │  │     │  ├─ DeploymentRuntimeReport.java  # 在不运行项目内容的情况下推导出的、可审阅的源码依据运行时值
   │  │  │  │     │  ├─ DeploymentSupportCatalog.java  # 为精确项目类型与架构组合提供证据支撑的支持声明
   │  │  │  │     │  ├─ DeploymentSupportLevel.java  # 由证据支撑并由分析、计划和结果公开的支持等级
   │  │  │  │     │  ├─ DeploymentSupportProfile.java  # 一个已分析路径的精确语言、架构、框架、支持等级、验证矩阵、证据与限制
   │  │  │  │     │  ├─ ValidatedDeploymentTarget.java  # 由真实产品入口验收证据支撑的一个精确目标组合
   │  │  │  │     │  └─ component/  # 多组件的声明、依赖、资源和隔离要求
   │  │  │  │     │     ├─ DeploymentPart.java  # 混合项目中一个组件的完整静态记录
   │  │  │  │     │     └─ PartIsolationSpec.java  # 一个组件显式请求的不安全执行能力
   │  │  │  │     ├─ server/  # 目标服务器与主机能力数据
   │  │  │  │     │  ├─ DeploymentCpuMicroarchitectureLevel.java  # 目标运行时链接器确认的最高累积 x86-64 微架构级别
   │  │  │  │     │  ├─ LinuxDistroType.java  # 部署为其保留独立证据和兼容性决策的发行版系列
   │  │  │  │     │  └─ security/  # 服务器安全机制与受限能力信息
   │  │  │  │     │     ├─ DeploymentLinuxFirewallKind.java  # 只读探测观测到的主机防火墙管理器
   │  │  │  │     │     ├─ DeploymentLinuxFirewallState.java  # 观测到的主机防火墙服务状态
   │  │  │  │     │     ├─ DeploymentLinuxSecurityModuleType.java  # 在不修改状态的情况下观测到的主机强制访问控制实现
   │  │  │  │     │     ├─ DeploymentLinuxSecurityPosture.java  # 作为一个连贯主机观测保留的只读安全与防火墙事实
   │  │  │  │     │     └─ DeploymentLinuxSecurityState.java  # 观测到的强制访问控制状态
   │  │  │  │     └─ toolchain/  # 工具链版本、绑定与准备数据
   │  │  │  │        ├─ DeploymentResolvedToolchains.java  # 发布级不可变工具身份，不随目录修订重新选择
   │  │  │  │        ├─ DeploymentToolchainBindingCodec.java  # 无配置解析依赖的确定性版本化发布绑定
   │  │  │  │        ├─ DeploymentToolchainCatalog.java  # 唯一可注入构建分支目录，补丁由官方发布发现提供
   │  │  │  │        ├─ DeploymentToolchainSelectionPolicy.java  # 准备与有界构建重试共用的纯选择规则
   │  │  │  │        └─ ToolchainRequirement.java  # 即使没有构建候选也保留源码声明
   │  │  │  ├─ environment/  # 原端点环境准备、独立审阅及中断核对
   │  │  │  │  ├─ DeploymentEnvironmentCommandReview.java  # 模块运行资源
   │  │  │  │  ├─ DeploymentEnvironmentHelper.java  # 使用原环境操作保存 helper 回执，包括不创建另一检查点的恢复
   │  │  │  │  ├─ DeploymentEnvironmentJournal.java  # 严格原任务 CAS 依据，恢复不能重新创建已发送副作用
   │  │  │  │  ├─ DeploymentEnvironmentObservation.java  # 精确有界的原主机观测及回执
   │  │  │  │  ├─ DeploymentEnvironmentPendingException.java  # 停止后续发送并保留精确原操作依据
   │  │  │  │  ├─ DeploymentEnvironmentProfiles.java  # 原实现自有软件包配置，调用者不能选择任意软件包
   │  │  │  │  ├─ DeploymentEnvironmentRunner.java  # 推进一项原环境操作，后续认证前关闭每个物理会话
   │  │  │  │  ├─ DeploymentEnvironmentScripts.java  # 固定完整审核资源，不含调用方提供的 Shell 或软件包
   │  │  │  │  ├─ DeploymentEnvironmentState.java  # 由宿主检查点保留的小型非秘密原操作依据
   │  │  │  │  └─ DeploymentEnvironmentWorkflow.java  # 原服务器独占环境业务任务及明确恢复
   │  │  │  ├─ feedback/  # 原任务阶段与有界脱敏日志读取
   │  │  │  │  └─ DeploymentDiagnostics.java  # 使用既有所属诊断类别记录实际任务阶段及结果
   │  │  │  └─ standard/  # 确定性分析、辅助建议与标准部署流程
   │  │  │     ├─ DeploymentComponentSelection.java  # 校验审阅图，并从明确输入派生根，不要求属于发现全集
   │  │  │     ├─ DeploymentDatabasePlanActionLog.java  # 每项计划数据库子操作的有界原任务索引
   │  │  │     ├─ DeploymentDatabaseStages.java  # 将整份源码数据库校验与原任务原生效果分开
   │  │  │     ├─ DeploymentNativeDatabaseActionLog.java  # 一项串行原生数据库操作的原任务非秘密身份
   │  │  │     ├─ DeploymentNativeDatabaseChannel.java  # 发送一次原生数据库原操作，随后仅读取其精确 helper 回执
   │  │  │     ├─ DeploymentNativeDatabaseSetup.java  # 在原发布任务及精确 helper 回执下准备真实原生数据库目标
   │  │  │     ├─ DeploymentProtocolBootstrap.java  # 在可恢复的原发布任务下只安装一次缺失的受管 helper
   │  │  │     ├─ DeploymentProtocolBootstrapActionLog.java  # 共用 helper 首次安装的原任务身份与效果阶段
   │  │  │     ├─ DeploymentStaticAssistance.java  # 在确定性流程内组织来源建议、人工审阅及辅助发布身份
   │  │  │     ├─ DeploymentStaticPageService.java  # 绑定页面只读服务器查询及原发布任务，但不持有 Swing 视图
   │  │  │     ├─ DeploymentStaticProjectSave.java  # 在原任务中仅保存一次已审阅发布来源
   │  │  │     ├─ DeploymentStaticRuntimeDraft.java  # 在来源建议之后应用明确非秘密运行预填，保留组件资源范围
   │  │  │     ├─ DeploymentStaticSetupActionLog.java  # 有界原静态准备身份；原生数据库效果另须原操作回执核对
   │  │  │     ├─ DeploymentStaticSourceService.java  # 执行来源预选及完整项目保存，不跨任务分享活动授权
   │  │  │     ├─ DeploymentStaticSourceSetup.java  # 冻结图来源归档，仅释放原已确认准备资源
   │  │  │     ├─ DeploymentStaticTaskRecovery.java  # 本机清理前核对原发布前素材及原生数据库回执；正式执行仍由原恢复处理
   │  │  │     ├─ DeploymentStaticUnitResolver.java  # 解析原受管所有者，首次发布则生成尚未持久化的随机所有者
   │  │  │     ├─ DeploymentStaticWorkflow.java  # 协调一项本地或 Git 静态审阅与发布原任务，不另造来源授权
   │  │  │     ├─ analyze/  # 不执行构建的源码和项目分析
   │  │  │     │  ├─ DeploymentStandardAnalyzer.java  # 借用一份仍受管的不可变素材进行确定性标准分析
   │  │  │     │  ├─ component/  # 多组件发现、归属与依赖检查
   │  │  │     │  │  ├─ DiscoveredProjectPart.java  # 声明可部署根目录及可能类型，保留歧义供输入解析处理
   │  │  │     │  │  ├─ MixedProjectChecker.java  # 分析显式组件根，并在修改目标机前拒绝跨组件矛盾
   │  │  │     │  │  ├─ PartAnalysisRequest.java  # 提供给确定性混合项目分析的用户审阅组件边界
   │  │  │     │  │  ├─ PartGraphChecker.java  # 验证跨组件所有权、依赖、端口、制品及数据路径约束
   │  │  │     │  │  └─ ProjectPartFinder.java  # 有界静态发现，目录和声明仅作为证据，不作为可执行指令
   │  │  │     │  ├─ contract/  # 静态分析的输入与规则合同
   │  │  │     │  │  ├─ policy/  # 来源与组件的分析准入规则
   │  │  │     │  │  │  └─ SourceMutationPolicy.java  # 拒绝可能自动修改受管数据库的源码声明
   │  │  │     │  │  └─ spi/  # 按项目类型扩展静态检查器的合同
   │  │  │     │  │     ├─ DeploymentTypeChecker.java  # 面向一个用户选定部署类型的单一有界检查器
   │  │  │     │  │     └─ DeploymentTypeReport.java  # 由一个类型检查器返回的局部事实与运行时建议
   │  │  │     │  ├─ core/  # 跨语言分析顺序、规则应用与结果汇总
   │  │  │     │  │  ├─ DeploymentAnalysisFlow.java  # 仅协调有界遍历、安全策略、类型分派与结果汇总
   │  │  │     │  │  └─ ProjectLanguageChecker.java  # 合并独立语言检查器，不进行排序或选择项目类型
   │  │  │     │  ├─ ecosystem/  # 源码语言识别及其构建架构分析
   │  │  │     │  │  ├─ c/  # C/C++ 语言识别与构建架构分派
   │  │  │     │  │  │  ├─ CLanguageChecker.java  # 独立于 CMake 识别 C 与 C++ 语言标记
   │  │  │     │  │  │  └─ cmake/  # 静态检查 CMake preset 与目标，不执行构建
   │  │  │     │  │  │     ├─ CmakeDeploymentChecker.java  # 在不执行 CMake 的情况下检查固定 preset 的单可执行服务
   │  │  │     │  │  │     └─ CmakeFacts.java  # 固定的 CMake preset、目标、源码语言与拒绝事实
   │  │  │     │  │  ├─ db/  # 读取数据库声明与服务端点，不保留凭据
   │  │  │     │  │  │  └─ DatabaseProjectChecker.java  # 读取显式数据库声明和常规 Spring 端点，不保留凭据
   │  │  │     │  │  ├─ dotnet/  # .NET/C# 语言识别与构建架构分派
   │  │  │     │  │  │  ├─ DotNetLanguageChecker.java  # 独立于构建架构识别 .NET/C# 语言标记
   │  │  │     │  │  │  └─ dotnetsdk/  # 静态检查 .NET Web SDK，不执行构建
   │  │  │     │  │  │     ├─ DotNetSdkDeploymentChecker.java  # 在不执行 dotnet 的情况下检查一个锁定的 .NET Web SDK 服务
   │  │  │     │  │  │     └─ DotNetSdkFacts.java  # 服务分析使用的固定 .NET SDK 项目元数据
   │  │  │     │  │  ├─ go/  # Go 语言识别与构建架构分派
   │  │  │     │  │  │  ├─ GoLanguageChecker.java  # 独立于构建架构识别 Go 语言标记
   │  │  │     │  │  │  └─ gomodule/  # 静态检查 Go Module，不执行构建
   │  │  │     │  │  │     ├─ GoModuleDeploymentChecker.java  # 在不执行 Go 的情况下检查一个锁定的 Go Module 服务
   │  │  │     │  │  │     └─ GoModuleFacts.java  # 服务分析使用的固定 Go Module 元数据
   │  │  │     │  │  ├─ java/  # Java 语言识别与构建架构分派
   │  │  │     │  │  │  ├─ JavaLanguageChecker.java  # 识别 Java 源码与 JAR 生态标记，不打开归档
   │  │  │     │  │  │  ├─ SpringBootDeploymentChecker.java  # 在不调用构建的前提下检查一个 Maven 或 Gradle Spring Boot 可执行 JAR 项目
   │  │  │     │  │  │  ├─ gradle/  # 静态检查 Gradle 构建脚本与 Wrapper，不执行构建
   │  │  │     │  │  │  │  ├─ GradleBuildChecker.java  # 读取一个固定 Gradle 构建脚本与 Wrapper 布局，但不调用 Gradle
   │  │  │     │  │  │  │  └─ GradleBuildFacts.java  # 固定的 Gradle 构建脚本与 Wrapper 事实
   │  │  │     │  │  │  ├─ jar/  # 静态检查 既有 JAR 与清单，不执行构建
   │  │  │     │  │  │  │  ├─ JavaJarDeploymentChecker.java  # 在不加载归档的情况下生成 Java JAR 事实与清单依据运行时建议
   │  │  │     │  │  │  │  └─ JavaJarManifestChecker.java  # 读取有界 JAR 运行元数据，不加载类
   │  │  │     │  │  │  ├─ jdk/  # 静态检查 JDK 源码入口，不执行构建
   │  │  │     │  │  │  │  ├─ JavaJdkDeploymentChecker.java  # 使用显式 JDK 构建元数据检查无依赖 Java 源码
   │  │  │     │  │  │  │  └─ JavaJdkFacts.java  # 固定的无依赖 Java 源码架构事实
   │  │  │     │  │  │  └─ maven/  # 静态检查 Maven POM 与构建入口，不执行构建
   │  │  │     │  │  │     ├─ MavenBuildChecker.java  # 读取根 POM 和 Maven 入口，但不调用 Maven 或其 Wrapper
   │  │  │     │  │  │     └─ MavenBuildFacts.java  # 框架及部署支持检查所需的根 Maven 事实
   │  │  │     │  │  ├─ kotlin/  # Kotlin 语言识别与构建架构分派
   │  │  │     │  │  │  ├─ KotlinLanguageChecker.java  # 独立于构建架构识别 Kotlin 语言标记
   │  │  │     │  │  │  ├─ KotlinServiceDeploymentChecker.java  # 恰好选择一个经审阅的 Kotlin 构建架构
   │  │  │     │  │  │  ├─ gradle/  # 静态检查 Gradle 构建脚本与 Wrapper，不执行构建
   │  │  │     │  │  │  │  ├─ KotlinGradleDeploymentChecker.java  # 在不执行 Gradle 的情况下检查一个锁定的 Kotlin Gradle 应用
   │  │  │     │  │  │  │  └─ KotlinGradleFacts.java  # 服务分析使用的固定 Kotlin Gradle 应用元数据
   │  │  │     │  │  │  └─ kotlinc/  # 静态检查 Kotlin 编译器声明，不执行构建
   │  │  │     │  │  │     ├─ KotlinCompilerDeploymentChecker.java  # 使用显式编译器元数据检查无依赖 Kotlin/JVM 源码
   │  │  │     │  │  │     └─ KotlinCompilerFacts.java  # 固定的无依赖 Kotlin 编译器架构事实
   │  │  │     │  │  ├─ node/  # Node.js/JavaScript/TypeScript 语言识别与构建架构分派
   │  │  │     │  │  │  ├─ NodeBuildArchitectureFacts.java  # 选中的 Node 包管理器架构及其锁文件
   │  │  │     │  │  │  ├─ NodeBuildChecker.java  # 读取 package.json、受支持锁文件和固定脚本名称，但不调用包管理器
   │  │  │     │  │  │  ├─ NodeBuildFacts.java  # 来自 package.json 的固定包管理器、锁文件和脚本事实
   │  │  │     │  │  │  ├─ NodeLanguageChecker.java  # 从路径与包元数据检测 Node.js、JavaScript 和 TypeScript 事实
   │  │  │     │  │  │  ├─ NodeServiceDeploymentChecker.java  # 生成 Node 服务构建事实与精确版本运行时建议
   │  │  │     │  │  │  ├─ npm/  # 静态检查 npm 锁文件，不执行构建
   │  │  │     │  │  │  │  └─ NpmBuildChecker.java  # 检查 npm package-lock 架构
   │  │  │     │  │  │  ├─ pnpm/  # 静态检查 pnpm 锁文件，不执行构建
   │  │  │     │  │  │  │  └─ PnpmBuildChecker.java  # 检查 pnpm 锁文件架构
   │  │  │     │  │  │  └─ yarn/  # 静态检查 Yarn 锁文件，不执行构建
   │  │  │     │  │  │     └─ YarnBuildChecker.java  # 检查 Yarn 锁文件架构
   │  │  │     │  │  ├─ php/  # PHP 语言识别与构建架构分派
   │  │  │     │  │  │  ├─ PhpLanguageChecker.java  # 独立于构建架构识别 PHP 语言标记
   │  │  │     │  │  │  ├─ PhpServiceDeploymentChecker.java  # 恰好选择一个经审阅的 PHP 架构
   │  │  │     │  │  │  ├─ composer/  # 静态检查 Composer 锁文件，不执行构建
   │  │  │     │  │  │  │  ├─ PhpComposerDeploymentChecker.java  # 在不执行 PHP 的情况下检查一个 Composer 锁定的 PHP 服务
   │  │  │     │  │  │  │  └─ PhpComposerFacts.java  # 服务分析使用的固定 Composer 元数据
   │  │  │     │  │  │  └─ phpcli/  # 静态检查 PHP CLI 入口，不执行构建
   │  │  │     │  │  │     ├─ PhpCliDeploymentChecker.java  # 在不执行 PHP 的情况下检查一个无依赖 PHP CLI 服务
   │  │  │     │  │  │     └─ PhpCliFacts.java  # 固定的无依赖 PHP CLI 架构事实
   │  │  │     │  │  ├─ python/  # Python 语言识别与构建架构分派
   │  │  │     │  │  │  ├─ PythonBuildArchitectureFacts.java  # 选中的 Python 依赖架构及其锁文件
   │  │  │     │  │  │  ├─ PythonBuildChecker.java  # 读取 pyproject.toml 与固定锁文件名称，但不调用 Python
   │  │  │     │  │  │  ├─ PythonBuildFacts.java  # 固定的 Python 项目与锁文件事实
   │  │  │     │  │  │  ├─ PythonLanguageChecker.java  # 检测 Python 源码、精确版本元数据和唯一模块入口
   │  │  │     │  │  │  ├─ PythonServiceDeploymentChecker.java  # 生成 Python 构建事实与精确版本/模块运行时建议
   │  │  │     │  │  │  ├─ pip/  # 静态检查 pip 依赖锁定，不执行构建
   │  │  │     │  │  │  │  └─ PipBuildChecker.java  # 检查使用哈希锁定的 pip 架构
   │  │  │     │  │  │  ├─ pipenv/  # 静态检查 Pipenv 锁文件，不执行构建
   │  │  │     │  │  │  │  └─ PipenvBuildChecker.java  # 检查 Pipenv 锁文件架构
   │  │  │     │  │  │  ├─ poetry/  # 静态检查 Poetry 锁文件，不执行构建
   │  │  │     │  │  │  │  └─ PoetryBuildChecker.java  # 检查 Poetry 锁文件架构
   │  │  │     │  │  │  └─ uv/  # 静态检查 uv 锁文件，不执行构建
   │  │  │     │  │  │     └─ UvBuildChecker.java  # 检查 uv 锁文件架构
   │  │  │     │  │  ├─ ruby/  # Ruby 语言识别与构建架构分派
   │  │  │     │  │  │  ├─ RubyLanguageChecker.java  # 独立于构建架构识别 Ruby 语言标记
   │  │  │     │  │  │  ├─ RubyServiceDeploymentChecker.java  # 恰好选择一个经审阅的 Ruby 架构
   │  │  │     │  │  │  ├─ bundler/  # 静态检查 Bundler 锁文件，不执行构建
   │  │  │     │  │  │  │  ├─ RubyBundlerDeploymentChecker.java  # 在不执行 Ruby 的情况下检查一个 Bundler 锁定的 Rack 服务
   │  │  │     │  │  │  │  └─ RubyBundlerFacts.java  # 服务分析使用的固定 Bundler 元数据
   │  │  │     │  │  │  └─ rubycli/  # 静态检查 Ruby CLI 入口，不执行构建
   │  │  │     │  │  │     ├─ RubyCliDeploymentChecker.java  # 在不执行 Ruby 的情况下检查一个无依赖 Ruby CLI 服务
   │  │  │     │  │  │     └─ RubyCliFacts.java  # 固定的无依赖 Ruby CLI 架构事实
   │  │  │     │  │  └─ rust/  # Rust 语言识别与构建架构分派
   │  │  │     │  │     ├─ RustLanguageChecker.java  # 独立于构建架构识别 Rust 语言标记
   │  │  │     │  │     └─ cargo/  # 静态检查 Cargo 锁文件与构建声明，不执行构建
   │  │  │     │  │        ├─ RustCargoDeploymentChecker.java  # 在不执行 Rust 工具的情况下检查一个锁定的 Cargo 服务
   │  │  │     │  │        └─ RustCargoFacts.java  # 服务分析使用的固定 Cargo 元数据
   │  │  │     │  ├─ extension/  # 静态分析实现的装配与选择
   │  │  │     │  │  └─ registry/  # 按项目类型注册和选择检查实现
   │  │  │     │  │     └─ DeploymentTypeCheckerRegistry.java  # 持有完整且已验证的源码类型检查器装配
   │  │  │     │  ├─ preview/  # 不能直接进入部署的只读识别预览
   │  │  │     │  │  ├─ PreviewChecker.java  # 生成无法进入归档准备或部署的静态识别结果
   │  │  │     │  │  └─ PreviewLanguageMarkerCatalog.java  # 仅收集尚无独立生态检查器的语言标记，不求值文件内容
   │  │  │     │  ├─ service/  # 语言无关的服务元数据与报告组装
   │  │  │     │  │  ├─ ServiceMetadataChecker.java  # 不包含语言特定规则的公共有界元数据操作
   │  │  │     │  │  ├─ ServiceProjectFacts.java  # 单个语言检查器返回的公共有界服务元数据
   │  │  │     │  │  └─ ServiceReportBuilder.java  # 组装语言无关的服务事实与运行时建议
   │  │  │     │  ├─ source/  # 源码树安全遍历与有界元数据读取
   │  │  │     │  │  ├─ ApplicationBundleChecker.java  # 在组件发现前读取构建归属，不执行声明
   │  │  │     │  │  ├─ BoundedMetadataChecker.java  # 只在静态检查边界内读取固定项目元数据
   │  │  │     │  │  ├─ BoundedSourceChecker.java  # 对源码树执行有界只读安全遍历
   │  │  │     │  │  ├─ ProjectIdentityResolver.java  # 从经审阅元数据或选定根目录推导有界受管项目标识
   │  │  │     │  │  ├─ SourceCheckFacts.java  # 在不执行任何项目可控内容的前提下采集的有界事实
   │  │  │     │  │  └─ SourceLanguageEvidence.java  # 按调用方提供的语言规则收集首个路径证据
   │  │  │     │  ├─ toolchain/  # 读取显式工具链声明，不执行构建
   │  │  │     │  │  └─ ToolchainSpecChecker.java  # 不执行构建文件，在发布准入前收集有界版本声明
   │  │  │     │  └─ workload/  # 容器和静态站点的确定性工作负载识别
   │  │  │     │     ├─ ContainerDeploymentChecker.java  # 检测单一 Dockerfile 工作负载、声明端口与受管卷候选项
   │  │  │     │     └─ StaticWebDeploymentChecker.java  # 区分纯静态内容与由锁文件支持的 Node 静态构建
   │  │  │     └─ deploy/  # 标准部署的输入、计划、构建与发布
   │  │  │        ├─ DeploymentStandardPlanner.java  # 对原仍受管源码规划全部确定性单组件及混合部署
   │  │  │        ├─ DeploymentStaticReviewAnswers.java  # 一个已分析组件的冻结非秘密答案；确认记录由真实审阅者提供
   │  │  │        ├─ DeploymentStaticReviewBuilder.java  # 按原任务、冻结来源与选定服务器补全确定性审阅输入
   │  │  │        ├─ assistance/  # 辅助模式建议、审阅与有界执行
   │  │  │        │  ├─ DeploymentAssistedAdvisor.java  # 仅在确定性步骤出现已证实失败后提供有界建议
   │  │  │        │  ├─ DeploymentAssistedAnalysis.java  # 不持有执行端口、用于技术缺项的有界只读源码分析
   │  │  │        │  ├─ DeploymentAssistedApproval.java  # 仅对已登记固定部署效果应用所选独立模型策略，保留精确宿主授权
   │  │  │        │  ├─ DeploymentAssistedBuildCorrection.java  # 物理确认构建失败后仅重开四项类型化技术字段
   │  │  │        │  ├─ DeploymentAssistedScope.java  # 冻结一项原辅助任务及语言调用预算，不替换平台权限
   │  │  │        │  └─ DeploymentAssistedSession.java  # 每次真实 SSH 发送前立即复核辅助绑定
   │  │  │        ├─ build/  # 类型化构建描述与构建脚本生成
   │  │  │        │  ├─ BuildMatchPolicy.java  # 根据构建输出判定有界工具链重试
   │  │  │        │  ├─ DeploymentStandardBuildRunner.java  # 经精确 helper 工具绑定与受限构建执行一个已审阅来源候选
   │  │  │        │  ├─ DeploymentStandardCandidateRunner.java  # 串接一份原冻结归档、上传前工具选择、任务所属传输及受限构建
   │  │  │        │  ├─ DeploymentToolchainSetup.java  # 每次至多准备一个已审阅目录分支，并封存精确安装绑定
   │  │  │        │  ├─ DeploymentVerifiedBuildResult.java  # 已确认的有界构建身份，区别于最终发布
   │  │  │        │  ├─ contract/  # 构建输入与生成内容的合同
   │  │  │        │  │  └─ spi/  # 按构建架构扩展文本生成实现的合同
   │  │  │        │  │     └─ DeploymentBuildWriter.java  # 通过固定且有界的目标机构建入口渲染一种经审阅项目类型
   │  │  │        │  ├─ ecosystem/  # 按语言和工具生成构建脚本
   │  │  │        │  │  ├─ CargoBuildWriter.java  # 渲染固定 Cargo 构建架构
   │  │  │        │  │  ├─ CmakeBuildWriter.java  # 渲染固定单目标 CMake 试验架构
   │  │  │        │  │  ├─ DotNetSdkBuildWriter.java  # 渲染固定 .NET SDK 构建架构
   │  │  │        │  │  ├─ GoBuildWriter.java  # 渲染固定 Go Module 构建架构
   │  │  │        │  │  ├─ java/  # 生成 Java 的受控构建脚本
   │  │  │        │  │  │  ├─ GradleBuildWriter.java  # 渲染经审阅的 Spring Boot Gradle 架构
   │  │  │        │  │  │  ├─ JavaJarBuildWriter.java  # 渲染经审阅的预构建 Java JAR 边界
   │  │  │        │  │  │  ├─ JdkBuildWriter.java  # 渲染无依赖 JDK 源码架构
   │  │  │        │  │  │  ├─ MavenBuildWriter.java  # 渲染经审阅的 Spring Boot Maven 与 Maven Wrapper 入口
   │  │  │        │  │  │  └─ SpringBootArtifactBuildScript.java  # 仅共享不变的 Spring Boot 可执行 JAR 验证
   │  │  │        │  │  ├─ kotlin/  # 生成 Kotlin 的受控构建脚本
   │  │  │        │  │  │  ├─ KotlinCompilerBuildWriter.java  # 使用原生编译器渲染无依赖 Kotlin/JVM 源码
   │  │  │        │  │  │  └─ KotlinGradleBuildWriter.java  # 渲染固定 Kotlin Gradle 扩展架构
   │  │  │        │  │  ├─ node/  # 生成 Node.js/JavaScript/TypeScript 的受控构建脚本
   │  │  │        │  │  │  ├─ NodeArchitectureBuildWriter.java  # 仅在具名架构 Renderer 间共享不变的 Node 服务外壳
   │  │  │        │  │  │  ├─ NpmBuildWriter.java  # 渲染原生 npm Node 架构
   │  │  │        │  │  │  ├─ PnpmBuildWriter.java  # 渲染 pnpm Node 架构
   │  │  │        │  │  │  └─ YarnBuildWriter.java  # 渲染 Yarn Node 架构
   │  │  │        │  │  ├─ php/  # 生成 PHP 的受控构建脚本
   │  │  │        │  │  │  ├─ ComposerBuildWriter.java  # 渲染固定 Composer 扩展架构
   │  │  │        │  │  │  └─ PhpCliBuildWriter.java  # 通过 PHP CLI 语法验证渲染无依赖 PHP 源码
   │  │  │        │  │  ├─ python/  # 生成 Python 的受控构建脚本
   │  │  │        │  │  │  ├─ PipBuildWriter.java  # 渲染原生完整哈希 pip 架构
   │  │  │        │  │  │  ├─ PipenvBuildWriter.java  # 渲染 Pipenv 锁定架构
   │  │  │        │  │  │  ├─ PoetryBuildWriter.java  # 渲染 Poetry 锁定架构
   │  │  │        │  │  │  ├─ PythonArchitectureBuildWriter.java  # 在具名 Python 架构 Renderer 间共享不变的隔离 venv 外壳
   │  │  │        │  │  │  └─ UvBuildWriter.java  # 渲染 uv 锁定架构
   │  │  │        │  │  └─ ruby/  # 生成 Ruby 的受控构建脚本
   │  │  │        │  │     ├─ BundlerBuildWriter.java  # 渲染固定 Bundler 扩展架构
   │  │  │        │  │     └─ RubyCliBuildWriter.java  # 通过 Ruby CLI 语法验证渲染无依赖 Ruby 源码
   │  │  │        │  ├─ extension/  # 构建文本生成实现的装配与选择
   │  │  │        │  │  └─ registry/  # 注册并选择构建脚本生成实现
   │  │  │        │  │     └─ DeploymentBuildWriterRegistry.java  # 为每种可部署项目架构恰好注册一个具名 Renderer
   │  │  │        │  ├─ generation/  # 共用构建脚本与固定协议内容
   │  │  │        │  │  └─ script/  # 构建脚本片段与受控外壳
   │  │  │        │  │     ├─ CompanionBuildScript.java  # 将配套制品保留在与所属组件相同的有界候选及封存发布中
   │  │  │        │  │     ├─ DeploymentBuildEnvironmentWriter.java  # 将已审阅非秘密构建值渲染为固定 Shell 导出
   │  │  │        │  │     ├─ NodeDependencyToolSetup.java  # 准备项目固定包管理器，不改声明或锁文件
   │  │  │        │  │     ├─ NodePackageBuildScript.java  # 渲染 Node 服务与构建型静态站点共享的包管理器部分
   │  │  │        │  │     └─ SafeBuildScriptEnvelope.java  # 持有全部渲染器共享的归档校验、解压、限制、日志与构建工具证明
   │  │  │        │  └─ workload/  # 容器和静态站点的构建内容生成
   │  │  │        │     ├─ ContainerBuildWriter.java  # 渲染固定 Dockerfile 容器镜像构建
   │  │  │        │     └─ StaticSiteBuildWriter.java  # 在不假设 Node 版本的情况下渲染纯静态或 Node 构建型站点产物
   │  │  │        ├─ contract/  # 部署计划、输入与执行的扩展合同
   │  │  │        │  ├─ DeploymentPlanAction.java  # 部署短停机部署事务的可审计固定阶段
   │  │  │        │  ├─ DeploymentReviewConsent.java  # 用户对精确来源和目标的审阅记录；远端副作用仍需宿主授权
   │  │  │        │  ├─ MultiPartDeploymentPlan.java  # 一个经审阅组件图的确定性构建与运行顺序
   │  │  │        │  ├─ ReviewedDeploymentPlan.java  # 完全确定性的部署事务计划；不包含传输实现或原始命令
   │  │  │        │  ├─ ReviewedDeploymentRequest.java  # 使用两种独立来源摘要及原平台句柄的完整已审阅标准候选
   │  │  │        │  └─ spi/  # 受控部署实现的可替换合同
   │  │  │        │     └─ DeploymentAdapter.java  # 为一个受支持的部署单组件项目类型生成一个确定性计划
   │  │  │        ├─ database/  # 原生数据库选择、审阅与准备协议
   │  │  │        │  ├─ DeploymentDatabaseSourceInputs.java  # 补全源码派生的数据库声明，不连接服务器也不授予结构批准
   │  │  │        │  ├─ DeploymentNativeDatabaseReview.java  # 精确原生数据库效果与短期密码的原视图决定
   │  │  │        │  ├─ DeploymentNativeSqlSource.java  # 首个远端数据库效果前校验每份冻结的原生 SQL 来源
   │  │  │        │  ├─ DeploymentPreparedDatabase.java  # 交给审阅与发布的真实源码绑定数据库准备
   │  │  │        │  └─ DeploymentSqliteSetup.java  # 准备源码绑定的 SQLite 声明；物理初始化留在正式发布阶段
   │  │  │        ├─ extension/  # 部署能力的适配、注册与选择
   │  │  │        │  ├─ adapter/  # 将部署类型接入既有受控能力
   │  │  │        │  │  ├─ ContainerAdapter.java  # 在策略验证后计划一个 Dockerfile 镜像和一个受管 Docker 或 Podman 容器
   │  │  │        │  │  ├─ DeploymentPlanFactory.java  # 类型化部署适配器的内部共用顺序
   │  │  │        │  │  ├─ ServiceDeploymentAdapter.java  # 计划一个由 Profile 选定的普通 systemd 服务，不接受命令字符串
   │  │  │        │  │  ├─ ServiceDeploymentProfile.java  # 一个普通 systemd 服务项目类型的不可变 Profile
   │  │  │        │  │  └─ StaticSiteAdapter.java  # 只在验证声明的生成输出目录后计划静态站点发布
   │  │  │        │  └─ registry/  # 按部署类型注册和选择适配实现
   │  │  │        │     └─ DeploymentAdapterRegistry.java  # 持有完整且已验证的部署适配器装配
   │  │  │        ├─ input/  # 部署声明、普通配置与秘密输入的解析和校验
   │  │  │        │  ├─ ApplicationSpec.java  # 两端共用的有界应用声明与补填值
   │  │  │        │  ├─ DeploymentDatabaseReviewModeType.java  # 区分尚未审阅、已审阅且为空及一种已审阅数据库族
   │  │  │        │  ├─ DeploymentReviewField.java  # 向审阅人请求的一项不含秘密的运行输入
   │  │  │        │  ├─ DeploymentReviewFieldException.java  # 标识实际无效输入，不在界面诊断暴露输入值
   │  │  │        │  ├─ DeploymentStaticInputParser.java  # 解析桌面端有界运行时、数据库、秘密引用与 Git 引用记法
   │  │  │        │  ├─ DeploymentStaticRuntimeResolver.java  # 解析有源码依据的运行时默认值，并校验与手动部署相同的领域对象
   │  │  │        │  └─ DeploymentStoragePlan.java  # 解析已审阅存储并拒绝原有重叠和保留路径
   │  │  │        ├─ plan/  # 已审阅计划、主机绑定与计划身份
   │  │  │        │  ├─ DeploymentReviewedReleaseIdentity.java  # 从可改变构建或运行发布的每项输入推导不可变发布身份
   │  │  │        │  ├─ MultiPartDeploymentPlanner.java  # 仅在混合项目通过安全准入后生成确定性图顺序
   │  │  │        │  ├─ ReviewedDeploymentPlanner.java  # 将经审阅的请求路由到唯一能够计划其选定单组件项目类型的适配器
   │  │  │        │  └─ ReviewedPlanIdentity.java  # 使用不变共用分界写入器的稳定执行前审阅身份
   │  │  │        ├─ publication/  # 发布事务、原效果核对、收尾与恢复
   │  │  │        │  ├─ DeploymentAssistedPublishAttempts.java  # 仅在各原效果及会话关闭后开启一次独立有界发布操作
   │  │  │        │  ├─ DeploymentAssistedPublishBuild.java  # 仅在一个原候选、正式执行及有界重试预算内执行已确认构建纠正
   │  │  │        │  ├─ DeploymentAssistedReviewRecovery.java  # 重启后仅确认留存正式账本到检查点的构建审阅指针，绝不安装或构建
   │  │  │        │  ├─ DeploymentGraphPublishActionLog.java  # 各索引组件账本保留精确远端依据期间占用同一原图
   │  │  │        │  ├─ DeploymentMultiPartPublishRecovery.java  # 显式核对一份原整图，不重构建、重发布或伪造来源权限
   │  │  │        │  ├─ DeploymentMultiPartPublishRunner.java  # 在一个原可恢复引用任务和借用会话下运行完整已审阅组件图
   │  │  │        │  ├─ DeploymentMultiPartPublishTransaction.java  # 将已构建的已审阅图作为单个原事务发布
   │  │  │        │  ├─ DeploymentPublishAttemptPointers.java  # 指向完整持久发布同意的严格小指针，绝非复制配置或新任务状态
   │  │  │        │  ├─ DeploymentPublishAttemptRecovery.java  # 仅确认正式已消费发布指针，不恢复中断作者
   │  │  │        │  ├─ DeploymentPublishAuthorSession.java  # 跨明确审阅操作一次拥有一个物理作者会话
   │  │  │        │  ├─ DeploymentPublishCleanupActionLog.java  # 保留原远端候选和清理依据，直至所有实际清理均已确认
   │  │  │        │  ├─ DeploymentPublishCleanupAttempt.java  # 持久化一项原清理尝试，同时区分宿主拒绝与发送未确认
   │  │  │        │  ├─ DeploymentPublishOperationBinding.java  # 要求实际后续候选匹配真实正式已消费当前操作
   │  │  │        │  ├─ DeploymentPublishPolicyContext.java  # 在准备、每次连接及明确恢复间共用一份原发布策略
   │  │  │        │  ├─ DeploymentPublishPolicyFacts.java  # 保留原发布策略及认证 wire，不读取后续偏好
   │  │  │        │  ├─ DeploymentPublishRecoveryFacts.java  # 已校验原非秘密事实，绝不由路径或当前远端状态推断
   │  │  │        │  ├─ DeploymentPublishRestorationProof.java  # 另一独立操作可审阅前读取每个已恢复原单元
   │  │  │        │  ├─ DeploymentPublishStaging.java  # 封存单组件已审阅非秘密配置及瞬时秘密值
   │  │  │        │  ├─ DeploymentStandardPublishRecovery.java  # 仅使用留存事实及只读helper观测核对一项原远端发布
   │  │  │        │  ├─ DeploymentStandardPublishRunner.java  # 在同一原可恢复引用任务内构建并发布一个已审阅候选
   │  │  │        │  └─ DeploymentStandardPublishTransaction.java  # 执行一个已知单组件发布，且仅以原快照回滚已知失败
   │  │  │        ├─ support/  # 根据主机事实判断部署支持程度
   │  │  │        │  ├─ DeploymentHostMatchDecision.java  # 带有有序审阅证据的保守主机支持状态
   │  │  │        │  ├─ DeploymentHostMatcher.java  # 协调对已采集主机事实的公共、发行版与运行时支持检查
   │  │  │        │  ├─ distro/  # 按 Linux 发行版应用支持规则
   │  │  │        │  │  ├─ DeploymentAlmaLinuxMatchPolicy.java  # 包含明确 x86-64-v2 变体边界的 AlmaLinux 兼容性策略
   │  │  │        │  │  ├─ DeploymentCentosStreamMatchPolicy.java  # 兼容性策略
   │  │  │        │  │  ├─ DeploymentDebianMatchPolicy.java  # 稳定版兼容性策略
   │  │  │        │  │  ├─ DeploymentDistributionMatchPolicy.java  # 单个发行版可独立审阅的兼容性边界
   │  │  │        │  │  ├─ DeploymentDistributionMatchRules.java  # 独立发行版策略机械共享的固定检查
   │  │  │        │  │  ├─ DeploymentDistributionMatcher.java  # 将已分类发行版路由到恰好一个独立策略
   │  │  │        │  │  ├─ DeploymentOracleLinuxMatchPolicy.java  # 滚动主版本兼容性策略
   │  │  │        │  │  ├─ DeploymentRockyLinuxMatchPolicy.java  # 固定到受维护小版本的 Rocky Linux 兼容性策略
   │  │  │        │  │  └─ DeploymentUbuntuMatchPolicy.java  # 兼容性策略
   │  │  │        │  └─ runtime/  # 匹配运行时需求与已采集主机能力
   │  │  │        │     ├─ DeploymentRuntimeCapabilityDecision.java  # 表示一个已审阅运行时是否匹配采集到的主机能力
   │  │  │        │     └─ DeploymentRuntimeCapabilityMatcher.java  # 将一个类型化运行时与已采集的主机工具能力进行匹配
   │  │  │        └─ toolchain/  # 已审阅工具链的准备与精确绑定
   │  │  │           ├─ DeploymentProjectToolchainRequirements.java  # 审阅输入与原始声明的公共工具链需求投影
   │  │  │           └─ ToolchainBuildEnvironment.java  # 明确可执行路径与选择版本，保留项目语言目标
   │  │  ├─ ui/  # 部署审阅、自主模式、任务与应用管理界面
   │  │  │  ├─ DeploymentAgentEvidencePanel.java  # 展示原依据及明确安全边界控件，不持有任务能力
   │  │  │  ├─ DeploymentAgentGitForm.java  # 持有实际不含凭据的固定Git输入控件
   │  │  │  ├─ DeploymentAgentPage.java  # 用于本地或固定 Git 任务的自主专属所属页面
   │  │  │  ├─ DeploymentAgentSourceControls.java  # 在任务准入前抓取一个自主本地位置或不含凭据的Git远端
   │  │  │  ├─ DeploymentApplicationsPage.java  # 展示已保存应用并接纳精确所属管理动作
   │  │  │  ├─ DeploymentGitSourceForm.java  # 持有仅 Git 可见输入并构建一项不含凭据的经审阅请求
   │  │  │  ├─ DeploymentLocalSourceControls.java  # 共用本地元数据选择，保留每个原短任务至宿主完成
   │  │  │  ├─ DeploymentPreferenceSelection.java  # 持久化明确视图编辑，不写初始化或语言变化
   │  │  │  ├─ DeploymentRuntimePreview.java  # 以固定值复用普通运行控件，不持有任务、数据库、SSH或模型能力
   │  │  │  ├─ DeploymentSecretRefField.java  # 将既有精确业务修订添加到高级无秘密引用字段
   │  │  │  ├─ DeploymentSourceControls.java  # 持有所选来源元数据及完整项目草稿，不持有活动文件授权
   │  │  │  ├─ DeploymentStaticPage.java  # 绑定所属者的静态部署页面；确认后视图关闭，任务回调仍留在原任务
   │  │  │  ├─ DeploymentStaticPageReviewer.java  # 将当前所属视图的一次决定转换为类型化静态审阅值
   │  │  │  ├─ DeploymentStaticPasswordInput.java  # 不保留迟到 EDT 字符地获取一次性所属 UTF-8 密码
   │  │  │  ├─ DeploymentStaticSecretControls.java  # 管理单页发布前秘密创建及不含明文的修订选项
   │  │  │  ├─ DeploymentTargetControls.java  # 保持明确目标身份，仅导航到固定公开宿主目标
   │  │  │  ├─ DeploymentWorkspace.java  # 在唯一已发布根内持有按需创建的部署视图
   │  │  │  ├─ component/  # 手工多组件编辑及原输入保留
   │  │  │  │  └─ DeploymentComponentEditor.java  # 源码分析前编辑实际图，保留全部明确组件边界
   │  │  │  ├─ delivery/  # 已核实公开端点与固定命令的桌面动作
   │  │  │  │  └─ DeploymentDeliveryPanel.java  # 复用已核实且带组件身份的交付，不解码不透明运行数据
   │  │  │  ├─ environment/  # 显式目标环境准备及原任务反馈
   │  │  │  │  └─ DeploymentEnvironmentPage.java  # 经真实可恢复环境流程准备明确选定的原服务器
   │  │  │  ├─ feedback/  # 原句柄持续观察与成功后精确交付查询
   │  │  │  │  ├─ DeploymentTaskDelivery.java  # 仅在原宿主完成后读取精确交付
   │  │  │  │  └─ DeploymentTaskFeedback.java  # 持续观察一个原句柄，不提交轮询任务
   │  │  │  ├─ runtime/  # 运行预填、原字段校验与键盘帮助
   │  │  │  │  ├─ DeploymentReviewInputs.java  # 创建带可访问字段帮助及有界多行声明的真实编辑输入
   │  │  │  │  └─ DeploymentRuntimeDraftForm.java  # 保留明确非秘密启动前输入，来源建议仅补未编辑值
   │  │  │  └─ style/  # 复用主题值的所属表单与高级窗口排版
   │  │  │     └─ DeploymentPageStyle.java  # 仅使用 Swing 主题值的 Mod 本地展示，不访问宿主实现
   │  │  └─ resources/  # 随附运行资源
   │  │     ├─ db/  # 数据库实现
   │  │     │  ├─ schema-v1.sql  # 当前模块的数据库结构与权限脚本
   │  │     │  └─ validation-v1.sql  # 当前模块的数据库结构与权限脚本
   │  │     ├─ linux/  # Linux 与受管远程能力
   │  │     │  ├─ distro/  # 发行版识别
   │  │     │  │  └─ dnf/  # DNF软件源
   │  │     │  │     └─ centos-source-repositories.py  # CentOS 9/10 源码依赖事务 CRB 选择
   │  │     │  ├─ execution/  # 执行协议
   │  │     │  │  └─ protocol/  # 固定执行协议
   │  │     │  │     └─ helper/  # 固定helper组装
   │  │     │  │        └─ fragments/  # 固定helper分片
   │  │     │  │           ├─ 00-protocol-foundation.sh  # 定义受管 helper 协议的安全基线、路径和输入校验函数
   │  │     │  │           ├─ 70-command-dispatch.sh  # 将 helper 协议命令分派到固定的受管操作
   │  │     │  │           ├─ backup/  # 备份协议
   │  │     │  │           │  ├─ 67-managed-backup.sh  # 实现固定 PAX TAR/OCI 取材、制品读取和精确操作清理
   │  │     │  │           │  └─ 69-handoff-backup-status.sh  # 固定备份与只读状态协议片段
   │  │     │  │           ├─ database/  # 原生数据库协议
   │  │     │  │           │  ├─ 10-native-instances.sh  # 检测已有实例并执行系统包安装、替换和恢复等待
   │  │     │  │           │  ├─ 20-native-targets.sh  # 创建限定权限的应用库、受保护启动凭据和一次性 SQL 初始化
   │  │     │  │           │  ├─ 21-native-operations.sh  # 固定数据库操作、身份核对或备份协议片段
   │  │     │  │           │  ├─ 63-database-inspection.sh  # 固定数据库操作、身份核对或备份协议片段
   │  │     │  │           │  ├─ 64-database-client.sh  # SQL 客户端、数据库参数与 SQLite 物理绑定核验
   │  │     │  │           │  ├─ 65-database-backup.sh  # 实现数据库预检、导出、制品流转、清理和候选恢复
   │  │     │  │           │  ├─ 66-database-activation.sh  # 激活数据库候选并保留可验证的回滚边界
   │  │     │  │           │  └─ 68-handoff-database.sh  # 固定数据库操作、身份核对或备份协议片段
   │  │     │  │           ├─ ecosystem/  # 语言工具生态
   │  │     │  │           │  └─ 35-ecosystem-dispatch.sh  # 按受支持项目生态分派固定构建与运行准备流程
   │  │     │  │           ├─ input/  # 输入校验
   │  │     │  │           │  ├─ 15-deployment-input.sh  # 解析并校验类型化部署输入、配置和秘密引用
   │  │     │  │           │  ├─ 16-application-input.sh  # 独立解析和验证 APP 运行协议
   │  │     │  │           │  └─ 17-managed-content.sh  # 解析、准备并验证普通文件的固定远端受管目录绑定
   │  │     │  │           ├─ release/  # 发布身份
   │  │     │  │           │  ├─ 10-typed-release.sh  # 实现受约束的类型化候选发布与发布身份处理
   │  │     │  │           │  ├─ 12-container-image-input.sh  # rootless 镜像交接与容器数据权限
   │  │     │  │           │  ├─ 18-container-storage.sh  # 宿主绑定、镜像初始化、只读视图与挂载归属检查
   │  │     │  │           │  ├─ 50-container-release.sh  # 实现容器应用的发布、镜像与命名卷归属流程
   │  │     │  │           │  ├─ 52-container-recovery.sh  # 实现容器快照、回滚和生命周期恢复流程
   │  │     │  │           │  ├─ 53-publication-observation.sh  # 固定发布、快照或留存协议片段
   │  │     │  │           │  ├─ 55-publication-snapshot.sh  # 固定发布、快照或留存协议片段
   │  │     │  │           │  └─ 58-retained-activation.sh  # 固定发布、快照或留存协议片段
   │  │     │  │           ├─ restore/  # 已保存事实恢复
   │  │     │  │           │  ├─ 54-restore-candidate.sh  # 准备受管恢复候选及隔离检查输入
   │  │     │  │           │  ├─ 56-restore-commit.sh  # 提交恢复候选并执行失败回滚
   │  │     │  │           │  ├─ 56-restore-recovery.sh  # 固定恢复与收尾协议片段
   │  │     │  │           │  ├─ 57-application-restore.sh  # 候选 APP 验证与候选端口映射
   │  │     │  │           │  ├─ 59-handoff-database-restore.sh  # 固定恢复与收尾协议片段
   │  │     │  │           │  ├─ 59-handoff-restore-cleanup.sh  # 固定恢复与收尾协议片段
   │  │     │  │           │  └─ 59-handoff-restore.sh  # 固定恢复与收尾协议片段
   │  │     │  │           ├─ runtime/  # 运行时观察
   │  │     │  │           │  ├─ 40-typed-runtime.sh  # 实现类型化运行时环境、构建参数和产物校验
   │  │     │  │           │  ├─ 41-application-admission.sh  # 固定运行观察与应用准入协议片段
   │  │     │  │           │  ├─ 41-application-runtime.sh  # 任务登记、维护准入和输入校验
   │  │     │  │           │  ├─ 42-application-job.sh  # 原生非 root 前台任务及隔离验证
   │  │     │  │           │  ├─ 43-application-container.sh  # 一次性任务容器和清理
   │  │     │  │           │  ├─ 44-application-health.sh  # 稳定进程、受控自检与 TCP/HTTP/UDP 协议验证
   │  │     │  │           │  └─ 45-application-container-contract.sh  # 镜像固定入口、挂载及端口实际映射核对
   │  │     │  │           └─ workspace/  # 受管工作区
   │  │     │  │              ├─ 20-candidate-workspace.sh  # 创建并校验受管候选工作区及其所有权边界
   │  │     │  │              ├─ 21-workspace-volume.sh  # 固定 ext4 容量及挂载设备回收
   │  │     │  │              ├─ 22-restricted-build.sh  # 临时构建、控制组限制与只读封存
   │  │     │  │              ├─ 23-container-builder.sh  # 临时 rootless 账号和 UID/GID 映射
   │  │     │  │              ├─ 24-build-entry.sh  # 独立安装的非特权构建与 rootless 后端入口
   │  │     │  │              ├─ 25-workspace-recovery.sh  # 开机回收受管候选，隔离不明残留
   │  │     │  │              ├─ 26-build-output.sh  # root 控制器累计输出预算和有界日志
   │  │     │  │              ├─ 27-agent-workspace.sh  # 模块资源与配置
   │  │     │  │              └─ apparmor-namespace.sh  # 工作区使用实际引擎 AppArmor profile 探测命名空间
   │  │     │  ├─ runtime/  # 运行时观察
   │  │     │  │  ├─ container/  # 容器工作负载
   │  │     │  │  │  └─ helper/  # 固定helper组装
   │  │     │  │  │     └─ 55-podman-quadlet.sh  # 生成并管理 Podman Quadlet 容器运行单元
   │  │     │  │  └─ systemd/  # Systemd运行控制
   │  │     │  │     └─ helper/  # 固定helper组装
   │  │     │  │        ├─ 60-lifecycle.sh  # 核验当前原生/容器运行合同并执行受管发布保留
   │  │     │  │        ├─ 61-service-identity.sh  # 固定服务账号、声明写入权限与停止确认
   │  │     │  │        ├─ selinux-command-entry.sh  # SELinux 标准服务启动入口
   │  │     │  │        └─ systemd-manager-isolation.sh  # systemd 管理接口隔离
   │  │     │  ├─ toolchain/  # 工具链准备与绑定
   │  │     │  │  ├─ 10-release-metadata.py  # 解析官方发布元数据、版本和可信摘要
   │  │     │  │  ├─ 20-installation-boundaries.py  # 校验归档、系统依赖、归属和有界子进程
   │  │     │  │  ├─ 30-managed-installation.py  # 隔离安装、系统复用及完成标记验证
   │  │     │  │  └─ 40-binding-protocol.py  # 密封精确绑定、验证和跨机恢复定位
   │  │     │  └─ workspace/  # 受管工作区
   │  │     │     └─ source-operations.py  # 模块资源与配置
   │  │     ├─ service/  # 平台业务服务
   │  │     │  ├─ core/  # 公共业务事实
   │  │     │  │  └─ execution/  # 执行协议
   │  │     │  │     └─ external-applications.py  # 按已认证会话执行固定外部应用扫描与身份复核
   │  │     │  └─ environment/  # 固定工具链与原操作安全准备协议
   │  │     │     ├─ environment.sh  # 模块运行资源
   │  │     │     └─ selinux.sh  # 模块运行资源
   │  │     └─ ui/  # Swing 界面
   │  │        ├─ delivery/  # 公开交付动作与状态的多语言文字
   │  │        │  ├─ delivery.properties  # 模块运行资源
   │  │        │  ├─ delivery_en.properties  # 模块运行资源
   │  │        │  └─ delivery_zh.properties  # 模块运行资源
   │  │        ├─ deployment-agent-page_en.properties  # 当前模块的固定配置或语言资源
   │  │        ├─ deployment-agent-page_zh.properties  # 当前模块的固定配置或语言资源
   │  │        ├─ deployment-applications-page.properties  # 当前模块的固定配置或语言资源
   │  │        ├─ deployment-applications-page_en.properties  # 当前模块的固定配置或语言资源
   │  │        ├─ deployment-applications-page_zh.properties  # 当前模块的固定配置或语言资源
   │  │        ├─ deployment-page.properties  # 当前模块的固定配置或语言资源
   │  │        ├─ deployment-page_zh.properties  # 当前模块的固定配置或语言资源
   │  │        ├─ environment/  # 环境选择、审批与状态的多语言文字
   │  │        │  ├─ environment-page.properties  # 模块运行资源
   │  │        │  ├─ environment-page_en.properties  # 模块运行资源
   │  │        │  └─ environment-page_zh.properties  # 模块运行资源
   │  │        └─ feedback/  # 原任务动态反馈的多语言文字
   │  │           ├─ task-feedback.properties  # 模块运行资源
   │  │           ├─ task-feedback_en.properties  # 模块运行资源
   │  │           └─ task-feedback_zh.properties  # 模块运行资源
   │  ├─ files/  # 独立可选文件管理 Mod，无业务 Schema
   │  │  ├─ pom.xml  # Maven 模块与依赖声明
   │  │  ├─ Mod.java  # 可选文件工作区及原文件操作核对生命周期
   │  │  ├─ file/  # 文件观测与工作区意图值
   │  │  │  └─ model/  # 目录、剪贴与实际结果的不可变值
   │  │  │     ├─ FilesClipboard.java  # 工作区内部意图及观测；准备不修改文件
   │  │  │     ├─ FilesDirectory.java  # 一个目录的完整有界观测，不含可复用授权
   │  │  │     ├─ FilesObservation.java  # 独立于任务最终清理的已返回回调事实
   │  │  │     └─ FilesOutcome.java  # 实际报告及原任务日志，包含成功链接更新
   │  │  ├─ mod.json  # 作者身份、依赖与公开入口描述
   │  │  ├─ service/  # 原任务文件动作、部分结果与安全核对
   │  │  │  ├─ FilesExecution.java  # 在不确定任务可结束之前保留实际部分结果
   │  │  │  ├─ FilesMutation.java  # 仅在真实回调执行既有File及Link操作
   │  │  │  ├─ FilesObservations.java  # 有界启用所属事实；不淘汰未结束任务来暗示成功
   │  │  │  ├─ FilesPlan.java  # 固定非秘密批次依据，防止部分工作变成整批成功
   │  │  │  ├─ FilesProblems.java  # 受检诊断及明确汇总结果
   │  │  │  ├─ FilesRecovery.java  # 核对真实原任务，不重复文件操作
   │  │  │  └─ FilesService.java  # 原任务文件管理及启用所属实际观测
   │  │  ├─ ui/  # 按需双栏、共用动作与实际任务反馈
   │  │  │  ├─ FilesActions.java  # 共享工具栏、菜单及实际键盘动作
   │  │  │  ├─ FilesCommands.java  # 将共享视图动作转换为原平台服务任务
   │  │  │  ├─ FilesDialogs.java  # 持有未提交的模态输入，以供锁定及关闭撤销
   │  │  │  ├─ FilesFeedback.java  # 显示原任务状态、逐项报告及持久链接步骤
   │  │  │  ├─ FilesMessages.java  # 不重建控件地解析原工作区语言
   │  │  │  ├─ FilesPane.java  # 持有一个独立定位及多选的栏
   │  │  │  ├─ FilesTableModel.java  # 显示两列并保留实际不跟随链接观测
   │  │  │  ├─ FilesTasks.java  # 观察实际任务，不永久等待 UNKNOWN 完成
   │  │  │  ├─ FilesTransferHandler.java  # 仅接受一个根内的跨栏移动载荷
   │  │  │  └─ FilesWorkspace.java  # 持有一个按需文件工作区及对称可选第二栏
   │  │  └─ resources/  # 所属能力分组
   │  │     └─ ui/  # 文件操作与任务状态的多语言文字
   │  │        ├─ files.properties  # 模块运行资源
   │  │        └─ files_zh_CN.properties  # 模块运行资源
   │  └─ git/  # 独立 Git Mod：受管仓库取材与固定提交素材服务
   │     ├─ pom.xml  # Maven 模块与依赖声明
   │     ├─ Mod.java  # 无页面或业务数据库的无状态 Git 来源服务生命周期
   │     ├─ api/  # 公开 Git 请求、结果与源码服务合同
   │     │  ├─ GitReferenceKind.java  # 解析为单个不可变提交的明确来源引用
   │     │  ├─ GitSourceRequest.java  # 不含凭据的原任务 Git 来源意图
   │     │  ├─ GitSourceResult.java  # 小型不可变固定来源结果；读取要求平台准入
   │     │  └─ GitSourceService.java  # 调用方原受管任务内的只读 Git 来源服务
   │     ├─ file/  # 受管 Git worker 与原进程凭据
   │     │  ├─ GitCheckout.java  # 抓取一个精确引用、验证树特性并检出不可变提交
   │     │  ├─ GitCommands.java  # 在 worker 内运行固定 Git 参数并等待原进程、已观察后代及管道退出
   │     │  ├─ GitLaunch.java  # 仅为 worker 解析本 Mod 代码、私有 JSON 库及公开 API
   │     │  ├─ GitMessages.java  # 一次 Git 准备请求的精确业务消息及退出凭据
   │     │  ├─ GitPreparation.java  # 经原平台进程及来源归属准备固定 Git 素材
   │     │  ├─ GitProtocol.java  # 符合公开进程契约的作者侧第一版 Git worker 帧
   │     │  ├─ GitRef.java  # 在分析前解析为固定提交的用户引用
   │     │  ├─ GitRemote.java  # 不在位置中嵌入凭据的严格 Git 来源
   │     │  ├─ GitRequest.java  # 只读 Git 取材的有界请求
   │     │  └─ GitWorker.java  # 仅使用公开标准流进程协议的单操作 Git worker
   │     ├─ mod.json  # 作者身份、依赖与公开入口描述
   │     └─ service/  # 原 Git 操作检查点及素材交接
   │        ├─ GitSourceJournal.java  # 提供方所属原任务检查点；任何阶段均不授权重放
   │        └─ GitSourceProvider.java  # 只发布原受控 Git 工作并核对其留存依据
   └─ moddev/  # 独立开发区，不进入默认根聚合或运行发现
      └─ pom.xml  # 独立开发聚合入口
```

验收辅助资源 `app/main/src/test/resources/architecture/publication-resource-baseline.properties` 保存 081 已提交 helper 的 37 项完整路径与原始 SHA256；固定包的 58 项资源继续按实际集合、组装摘要及功能合同核对。该测试资源不计入上方生产文件集合。
