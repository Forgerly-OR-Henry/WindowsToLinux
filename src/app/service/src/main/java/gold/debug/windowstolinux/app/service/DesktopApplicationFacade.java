package gold.debug.windowstolinux.app.service;

import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDatabasePreparation;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;

import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.app.service.contract.DesktopRecoveryInteraction;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiUseCaseFacade;
import gold.debug.windowstolinux.app.service.deployment.DeploymentInspectionUseCase;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.config.DeploymentConfigurationUseCase;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome;
import gold.debug.windowstolinux.app.service.deployment.ReviewedDeploymentUseCase;
import gold.debug.windowstolinux.app.service.deployment.MultiComponentDeploymentUseCase;
import gold.debug.windowstolinux.app.service.deployment.MultiComponentLifecycleUseCase;
import gold.debug.windowstolinux.app.service.deployment.multi.ManagedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.execution.environment.EnvironmentSetupUseCase;
import gold.debug.windowstolinux.app.service.execution.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.execution.lifecycle.LifecycleUseCase;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot;
import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.BackupApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.DeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.MultiComponentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.service.source.SourcePreparationUseCase;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection;
import gold.debug.windowstolinux.app.service.backup.BackupUseCase;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputUseCase;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupSecrets;
import gold.debug.windowstolinux.app.service.backup.CreatedBackupArchive;
import gold.debug.windowstolinux.app.service.backup.RemoteBackupCreationUseCase;
import gold.debug.windowstolinux.app.service.backup.ManagedRestoreOutcome;
import gold.debug.windowstolinux.app.service.backup.ManagedRestoreUseCase;
import gold.debug.windowstolinux.app.service.backup.ManagedOfflineMigrationOutcome;
import gold.debug.windowstolinux.app.service.backup.ManagedOfflineMigrationUseCase;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourcePreparer;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.deploy.execution.environment.EnvironmentSetupService;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.LifecycleActionResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.deploy.execution.lifecycle.MultiComponentLifecycleService;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.ReviewedMultiComponentDeploymentService;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.ReviewedDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.Set;

/**
 * Stable desktop facade. Package-specific use cases own all implementation details.
 *
 *  <p>稳定的桌面门面。各包专属用例持有全部实现细节。
 */
public final class DesktopApplicationFacade implements AiApplicationFacade, DeploymentApplicationFacade,
        MultiComponentApplicationFacade, ServerApplicationFacade, ManagedApplicationFacade, BackupApplicationFacade,
        gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade, gold.debug.windowstolinux.app.service.contract.SshRecoveryApplicationFacade {
    /** Checks purpose configuration without opening network connections. / 不打开网络连接地检查用途配置。
     * @param mode deployment mode / 部署模式
     * @throws SQLException if configuration cannot be read / 配置无法读取时
     */
    @Override public void requireDeploymentModels(gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode mode) throws SQLException { try(var ignored=ai.openDeployment(mode)) { } }

    /**
     * Converts the current form using its existing automatic deployment use case. / 使用现有自动部署用例转换当前表单。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved automatic deployment request / 构造或解析得到的自动部署请求
     */
    @Override public gold.debug.windowstolinux.app.service.contract.definition.AutomaticDeploymentRequest createAutomaticDeploymentRequest(
            gold.debug.windowstolinux.app.service.contract.definition.DeploymentSourceInput source,
            ServerProfile server, gold.debug.windowstolinux.app.service.contract.definition.DeploymentFormInput input) {
        return gold.debug.windowstolinux.app.service.deployment.automatic.DeploymentFormUseCase.request(source, server, input);
    }

    /**
     * Automatic.
     * <p>自动。
     */
    private final gold.debug.windowstolinux.app.service.deployment.automatic.AutomaticDeploymentTaskService automatic;

    /**
     * Executes one automatic desktop operation through the reviewed service contracts. / 通过经审阅的服务契约执行一次桌面自动操作。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved automatic deployment outcome / 构造或解析得到的自动部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public gold.debug.windowstolinux.app.service.contract.definition.AutomaticDeploymentOutcome deployAutomatically(
            gold.debug.windowstolinux.app.service.contract.definition.AutomaticDeploymentRequest request, char[] master,
            gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction interaction,
            Predicate<String> fingerprint, java.util.function.Consumer<gold.debug.windowstolinux.shared.model.message.LocalizedMessage> progress) throws Exception {
        try { return automatic.deploy(request, master, interaction, fingerprint, progress); }
        catch (gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException failure) {
            throw gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.nativeDatabase(failure);
        } finally { java.util.Arrays.fill(master, '\0'); }
    }
    /**
     * Lists non-secret profiles for all desktop server selectors. / 为所有桌面服务器选择器列出非秘密配置。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    @Override public List<gold.debug.windowstolinux.app.service.server.ServerSummary> listServerSummaries() throws SQLException { return servers.summaries(); }
    /**
     * Lists saved connection profiles. / 列出已保存的连接资料。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    @Override public List<ServerProfile> listServerProfiles() throws SQLException { return servers.list(); }
    /**
     * Detects the source form through service parsing. / 通过服务解析识别源码输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved deployment source input / 构造或解析得到的部署源码输入
     */
    @Override public gold.debug.windowstolinux.app.service.contract.definition.DeploymentSourceInput identifyDeploymentSource(String value) {
        return gold.debug.windowstolinux.app.service.source.SourceSelectionService.identify(value);
    }
    /**
     * Source identity or content read by the operation.
     * <p>操作读取的源身份或内容。
     */
    private final SourcePreparationUseCase source;
    /**
     * Bound gold debug windowstolinux app db persistence repository desktop preference repository collaborator for recovery preferences.
     * <p>处理恢复偏好的golddebugwindowstolinux应用db持久化仓库Desktop偏好仓库协作对象。
     */
    private final gold.debug.windowstolinux.app.db.persistence.repository.DesktopPreferenceRepository recoveryPreferences;
    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;
    /**
     * Bound ai use case facade collaborator for the supplied ai use case facade.
     * <p>处理所提供的AI用例门面的AI用例门面协作对象。
     */
    private final AiUseCaseFacade ai;
    /**
     * Recovery.
     * <p>恢复。
     */
    private final gold.debug.windowstolinux.app.service.recovery.SshRecoveryUseCase recovery;
    /**
     * Deterministic source analysis and deployment planning use case.
     * <p>确定性源码分析和部署规划用例。
     */
    private final DeploymentInspectionUseCase deploymentInspection;
    /**
     * Deployment configuration.
     * <p>部署配置。
     */
    private final DeploymentConfigurationUseCase deploymentConfiguration;
    /**
     * Automatic databases.
     * <p>自动数据库集合。
     */
    private final gold.debug.windowstolinux.app.service.deployment.automatic.AutomaticDatabaseUseCase automaticDatabases;

    /**
     * Delegates database input completion to the automatic database preparation use case.
     * <p>将数据库输入补全委派给自动数据库准备用例。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param assessment the typed static assessment / 类型化静态评估
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved assessment / 构造或解析得到的评估
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment completeAutomaticDatabaseInputs(
            Path root, String applicationId, gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment,
            char[] master, gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction interaction) throws Exception {
        try { return automaticDatabases.completeInputs(root, applicationId, assessment, master, interaction); }
        catch (gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException failure) {
            throw gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.nativeDatabase(failure);
        }
    }

    /**
     * Prepares automatic databases.
     * <p>准备自动数据库集合。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param assessment the typed static assessment / 类型化静态评估
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved automatic database preparation / 构造或解析得到的自动数据库准备
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public gold.debug.windowstolinux.shared.deploy.contract.AutomaticDatabasePreparation prepareAutomaticDatabases(
            Path root, String applicationId, ServerProfile server,
            gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment, char[] master,
            gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction interaction,
            java.util.function.Predicate<String> fingerprint, java.util.function.Consumer<gold.debug.windowstolinux.shared.model.message.LocalizedMessage> progress) throws Exception {
        try { return automaticDatabases.prepare(root, applicationId, server, assessment, master, interaction, fingerprint, progress); }
        catch (gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException failure) {
            throw gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.nativeDatabase(failure);
        }
    }
    /**
     * Environment.
     * <p>环境。
     */
    private final EnvironmentSetupUseCase environment;
    /**
     * Reviewed deployment.
     * <p>已审阅部署。
     */
    private final ReviewedDeploymentUseCase reviewedDeployment;
    /**
     * Parses component form inputs through their owning use case. / 通过所属用例解析组件表单输入。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return component form inputs through their owning use case / 通过所属用例解析组件表单输入
     */
    @Override public gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest parseComponentAnalysis(
            gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput input) {
        return new gold.debug.windowstolinux.app.service.deployment.automatic.ComponentFormUseCase(input).analysisRequest();
    }

    /**
     * Parses review metadata without exposing service internals to UI. / 解析审阅元数据，不向 UI 暴露服务内部实现。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param managedApplicationId managed application id / 受管应用标识
     * @param containerRisk container risk / 容器风险
     * @param experimentalRisk experimental risk / 实验性风险
     * @return review metadata without exposing service internals to UI / 审阅元数据，不向 UI 暴露服务内部实现
     */
    @Override public MultiComponentReviewInput parseComponentReview(
            gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput input,
            String managedApplicationId, boolean containerRisk, boolean experimentalRisk) {
        return new gold.debug.windowstolinux.app.service.deployment.automatic.ComponentFormUseCase(input)
                .reviewInput(managedApplicationId, containerRisk, experimentalRisk);
    }

    /**
     * Multi component deployment.
     * <p>多组件部署。
     */
    private final MultiComponentDeploymentUseCase multiComponentDeployment;
    /**
     * Multi component lifecycle.
     * <p>多组件生命周期。
     */
    private final MultiComponentLifecycleUseCase multiComponentLifecycle;
    /**
     * Lifecycle.
     * <p>生命周期。
     */
    private final LifecycleUseCase lifecycle;
    /**
     * Application inventory.
     * <p>应用清单。
     */
    private final gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationInventoryUseCase applicationInventory;
    /**
     * External applications.
     * <p>外部应用集合。
     */
    private final gold.debug.windowstolinux.app.service.execution.lifecycle.ExternalApplicationUseCase externalApplications;
    /**
     * The local backup page state.
     * <p>本地备份页面状态。
     */
    private final BackupUseCase backup;
    /**
     * Backup inputs.
     * <p>备份输入集合。
     */
    private final ManagedBackupInputUseCase backupInputs;
    /**
     * Remote backup.
     * <p>远端备份。
     */
    private final RemoteBackupCreationUseCase remoteBackup;
    /**
     * Managed restore.
     * <p>受管恢复。
     */
    private final ManagedRestoreUseCase managedRestore;
    /**
     * Managed migration.
     * <p>受管迁移。
     */
    private final ManagedOfflineMigrationUseCase managedMigration;

    /**
     * Initializes desktop application facade through its shared constructor contract.
     * <p>通过共享构造契约初始化Desktop应用门面。
     *
     * @param persistence persistence / 持久化
     * @param workDirectory work directory / 工作目录
     * @param linuxGateway linux gateway / Linux网关
     */
    public DesktopApplicationFacade(DesktopPersistence persistence, Path workDirectory, DeploymentLinuxGateway linuxGateway) {
        this(persistence, workDirectory, workDirectory.toAbsolutePath().normalize().resolveSibling("backups"),
                linuxGateway);
    }

    /**
     * Creates the desktop facade with the sole run-mode-derived work and backup directories. / 使用唯一由运行模式派生的工作及备份目录创建桌面门面。
     *
     * @param persistence persistence / 持久化
     * @param workDirectory work directory / 工作目录
     * @param backupsDirectory backups directory / 备份集合目录
     * @param linuxGateway linux gateway / Linux网关
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopApplicationFacade(DesktopPersistence persistence, Path workDirectory, Path backupsDirectory,
                                    DeploymentLinuxGateway linuxGateway) {
        Objects.requireNonNull(persistence, "persistence");
        this.recoveryPreferences = persistence.preferences();
        Objects.requireNonNull(linuxGateway, "linuxGateway");
        ServerOperationLockRegistry locks = new ServerOperationLockRegistry();
        DesktopSecretStoreService secrets = new DesktopSecretStoreService(persistence.encryptedSecrets());
        this.servers = new ServerUseCaseFacade(persistence.servers(), secrets, linuxGateway);
        this.source = new SourcePreparationUseCase(new DeploymentAnalysisCoordinator(), new WindowsSourcePreparer(workDirectory));
        this.ai = new AiUseCaseFacade(persistence.aiProfiles(), secrets);
        this.automatic = new gold.debug.windowstolinux.app.service.deployment.automatic.AutomaticDeploymentTaskService(this, ai, source, locks, persistence.agentTasks(), new gold.debug.windowstolinux.app.service.deployment.automatic.AgentRemoteToolService(servers,linuxGateway));
        this.recovery = new gold.debug.windowstolinux.app.service.recovery.SshRecoveryUseCase(persistence, locks, servers, secrets, linuxGateway);
        this.deploymentInspection = new DeploymentInspectionUseCase();
        this.deploymentConfiguration = new DeploymentConfigurationUseCase(
                persistence.configurations(), persistence.applicationSecrets(), secrets);
        this.automaticDatabases = new gold.debug.windowstolinux.app.service.deployment.automatic.AutomaticDatabaseUseCase(
                servers, linuxGateway, persistence.applicationSecrets(), secrets, deploymentConfiguration, this);
        this.environment = new EnvironmentSetupUseCase(
                new EnvironmentSetupService(), linuxGateway, servers, locks);
        this.reviewedDeployment = new ReviewedDeploymentUseCase(persistence.managedApplications(),
                persistence.managedApplicationGraphs(),
                persistence.applicationSecrets(), new ReviewedDeploymentService(), linuxGateway, servers, locks);
        this.multiComponentDeployment = new MultiComponentDeploymentUseCase(persistence.managedApplications(),
                persistence.managedApplicationGraphs(),
                persistence.applicationSecrets(), new ReviewedMultiComponentDeploymentService(),
                linuxGateway, servers, locks);
        this.multiComponentLifecycle = new MultiComponentLifecycleUseCase(persistence.managedApplications(),
                persistence.managedApplicationGraphs(), new MultiComponentLifecycleService(), linuxGateway, servers, locks);
        this.lifecycle = new LifecycleUseCase(persistence.managedApplications(), linuxGateway, servers, locks);
        this.applicationInventory = new gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationInventoryUseCase(persistence.managedApplications(), persistence.externalApplications(), servers);
        this.externalApplications = new gold.debug.windowstolinux.app.service.execution.lifecycle.ExternalApplicationUseCase(persistence.externalApplications(), persistence.managedApplications(), servers, linuxGateway, locks);
        this.backup = new BackupUseCase(workDirectory);
        this.backupInputs = new ManagedBackupInputUseCase(persistence.managedApplicationGraphs(),
                persistence.managedApplications(), persistence.configurations(), persistence.applicationSecrets());
        this.remoteBackup = new RemoteBackupCreationUseCase(persistence.managedApplicationGraphs(),
                persistence.managedApplications(), persistence.configurations(), persistence.applicationSecrets(),
                backupInputs, linuxGateway, servers, locks, workDirectory);
        this.managedRestore = new ManagedRestoreUseCase(backup, linuxGateway, servers, locks,
                persistence.managedApplicationGraphs(), persistence.applicationSecrets());
        this.managedMigration = new ManagedOfflineMigrationUseCase(remoteBackup, managedRestore,
                multiComponentLifecycle, servers, workDirectory, backupsDirectory);
    }

    /**
     * Assesses exact persisted backup inputs without remote access. / 在不访问远端的情况下评估精确持久化备份输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return constructed or resolved managed backup input assessment / 构造或解析得到的受管备份输入评估
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    @Override
    public ManagedBackupInputAssessment assessManagedBackupInputs(String applicationId) throws SQLException {
        return backupInputs.assess(applicationId);
    }

    /**
     * Creates a complete remote backup and publishes it only after independent final-path validation. / 创建完整远端备份，并仅在最终路径独立复验后发布。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return a complete remote backup and publishes it only after independent final-path validation / 完整远端备份，并仅在最终路径独立复验后发布
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    @Override
    public CreatedBackupArchive createManagedBackup(
            String applicationId, Path destination, char[] backupPassword, char[] masterPassword,
            Predicate<String> firstUseConfirmation
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException,
            gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException {
        return remoteBackup.createUsingSavedProfile(applicationId, destination, backupPassword, masterPassword,
                firstUseConfirmation);
    }

    /**
     * Restores a complete archive through the candidate, formal activation and recovery transaction. / 通过候选、正式激活及恢复事务恢复完整归档。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param targetServerId target server id / 目标服务器标识
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return constructed or resolved managed restore outcome / 构造或解析得到的受管恢复结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    @Override
    public ManagedRestoreOutcome restoreManagedBackup(
            Path archive, String targetServerId, char[] backupPassword, char[] masterPassword,
            Predicate<String> firstUseConfirmation
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException,
            gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException {
        return managedRestore.restoreUsingSavedProfile(archive, targetServerId, backupPassword, masterPassword,
                firstUseConfirmation);
    }

    /**
     * Prepares a verified target while retaining the stopped source and leaving external traffic unchanged. / 准备已验证目标，同时保留停写源端且不改变外部流量。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param targetServerId target server id / 目标服务器标识
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param stopWindowApproved stop window approved / 停止窗口已批准
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return constructed or resolved managed offline migration outcome / 构造或解析得到的受管离线迁移结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    @Override
    public ManagedOfflineMigrationOutcome prepareManagedOfflineMigration(
            String applicationId, String targetServerId, char[] backupPassword, char[] masterPassword,
            boolean stopWindowApproved, Predicate<String> firstUseConfirmation
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException,
            gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException {
        return managedMigration.prepare(applicationId, targetServerId, backupPassword, masterPassword,
                stopWindowApproved, firstUseConfirmation);
    }

    /**
     * Validates one selected backup locally without extraction or remote access. / 在本地校验一个已选备份且不提取、不访问远端。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @return constructed or resolved backup archive inspection / 构造或解析得到的备份归档检查
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override
    public BackupArchiveInspection inspectBackup(Path archive) throws IOException {
        return backup.inspect(archive);
    }

    /**
     * Extracts one new local restore candidate without activating it. / 提取一个新的本地恢复候选且不激活。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @return one new local restore candidate without activating it / 一个新的本地恢复候选且不激活
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override
    public PreparedBackupCandidate prepareBackupCandidate(Path archive) throws IOException {
        return backup.prepare(archive);
    }

    /**
     * Deletes only one exact platform-owned local restore candidate. / 仅删除一个精确的平台持有本地恢复候选。
     *
     * @param candidate candidate / 候选
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override
    public void discardBackupCandidate(PreparedBackupCandidate candidate) throws IOException {
        backup.discard(candidate);
    }

    /**
     * Prepares a candidate and authenticates its manifest-bound encrypted revisions. / 准备候选并认证其清单绑定加密修订。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @return constructed or resolved prepared backup secrets / 构造或解析得到的已准备备份秘密集合
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    @Override
    public PreparedBackupSecrets prepareBackupCandidateWithSecrets(Path archive, char[] backupPassword)
            throws IOException, gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException {
        return backup.prepareWithSecrets(archive, backupPassword);
    }

    /**
     * Performs bounded deterministic analysis of the selected deployment source.
     *
     *  <p>对所选部署源码执行有界的确定性分析。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @return constructed or resolved deployment project assessment / 构造或解析得到的部署项目评估
     */
    public DeploymentProjectAssessment analyzeDeploymentSource(Path sourceDirectory, DeploymentProjectType projectType) {
        return deploymentInspection.analyze(sourceDirectory, projectType);
    }

    /**
     * Prepares a selected typed source and safe archive without invoking project code. / 在不调用项目代码的情况下准备选定类型的源码和安全归档。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @return constructed or resolved reviewed source preparation / 构造或解析得到的已审阅源码准备
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public ReviewedSourcePreparation prepareReviewedSource(Path sourceDirectory, DeploymentProjectType projectType) throws IOException {
        return source.prepare(sourceDirectory, projectType);
    }

    /**
     * Prepares an explicit component graph with one independent safe archive per component. / 使用每组件独立安全归档准备显式组件图。
     *
     * @param applicationRoot application root / 应用根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return constructed or resolved prepared multi component source / 构造或解析得到的已准备多组件源码
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public PreparedMultiComponentSource prepareReviewedMultiComponentSource(
            Path applicationRoot, String applicationId, List<ComponentAnalysisRequest> components) throws IOException {
        return source.prepareMultiComponent(applicationRoot, applicationId, components);
    }

    /**
     * Creates the complete secret-free review object for a whole-application transaction. / 创建整应用事务的完整无秘密审阅对象。
     *
     * @param prepared prepared / 已准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
     * @return the complete secret-free review object for a whole-application transaction / 整应用事务的完整无秘密审阅对象
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public ReviewedMultiComponentApplication createReviewedMultiComponentApplication(
            PreparedMultiComponentSource prepared, ServerIdentity server, List<MultiComponentReviewInput> inputs,
            ApplicationHealthGate applicationHealth) throws SQLException {
        return multiComponentDeployment.createReview(prepared, server, inputs, applicationHealth);
    }

    /**
     * Executes a reviewed whole-application transaction without stored application secrets. / 执行不含已存应用秘密的经审阅整应用事务。
     *
     * @param review review / 审阅
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public MultiComponentDeploymentResult deployReviewedMultiComponent(
            ReviewedMultiComponentApplication review, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator verifier) throws SQLException {
        return multiComponentDeployment.deploy(review, endpoint, credential, verifier);
    }

    /**
     * Executes a reviewed whole-application transaction with the selected saved credential. / 使用选定已保存凭据执行经审阅整应用事务。
     *
     * @param review review / 审阅
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public MultiComponentDeploymentResult deployReviewedMultiComponentWithStoredPassword(
            ReviewedMultiComponentApplication review, ServerProfile profile, CredentialStorageMode mode,
            char[] masterPassword, Predicate<String> confirmation) throws SecretStoreException, SQLException {
        return multiComponentDeployment.deployWithStoredPassword(review, profile, mode, masterPassword, confirmation);
    }

    /**
     * Loads a durable secret-free whole-application graph after a desktop restart. / 在桌面应用重启后加载持久且不含秘密的整应用图。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ManagedMultiComponentApplication> findManagedMultiComponentApplication(String applicationId)
            throws SQLException {
        return multiComponentLifecycle.findManagedApplication(applicationId);
    }

    /**
     * Executes a dependency-safe lifecycle action for a durably managed application graph. / 对持久受管应用图执行依赖安全生命周期动作。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param targetComponentIds target component ids / 目标组件标识集合
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return constructed or resolved multi component lifecycle result / 构造或解析得到的多组件生命周期结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public MultiComponentLifecycleResult executeManagedMultiComponentLifecycleWithStoredPassword(
            String applicationId, Set<String> targetComponentIds, LifecycleAction action,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return multiComponentLifecycle.executeLifecycleWithStoredPassword(applicationId, targetComponentIds, action,
                profile, mode, masterPassword);
    }

    /**
     * Prepares one explicitly selected credential-free Git source by pinning it to a detached commit before analysis.
     *
     *  <p>在分析前将一个显式选择且不含凭据的 Git 源固定为分离 Commit 后再准备。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @return constructed or resolved reviewed source preparation / 构造或解析得到的已审阅源码准备
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     */
    public ReviewedSourcePreparation prepareReviewedGitSource(GitSourceRequest request, DeploymentProjectType projectType)
            throws GitSnapshotException {
        return source.prepareGit(request, projectType);
    }

    /**
     * Renders a fully validated typed deployment plan without opening SSH, invoking a build, or reading a secret.
     *
     *  <p>渲染完整校验的部署计划，不打开 SSH、不调用构建，也不读取秘密。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved reviewed deployment plan / 构造或解析得到的已审阅部署计划
     */
    public ReviewedDeploymentPlan planDeployment(ReviewedDeploymentRequest request) {
        return deploymentInspection.plan(request);
    }

    /**
     * Executes one previously reviewed type-specific deployment through the configured bounded gateway.
     *
     *  <p>通过已配置的有界网关执行一个先前审阅的类型专属部署。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @return constructed or resolved deployment outcome / 构造或解析得到的部署结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public DeploymentOutcome deployReviewed(ReviewedDeploymentRequest request, SshEndpoint endpoint,
                                           SshCredential credential, HostKeyEvaluator verifier) throws SQLException {
        return reviewedDeployment.deploy(request, endpoint, credential, verifier);
    }

    /**
     * Creates a reviewed request bound to the desktop-managed application identity. / 创建绑定到桌面受管应用身份的经审阅请求。
     *
     * @param preparation preparation / 准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param rootBuildConfirmed root build confirmed / 根目录构建已确认
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     * @return a reviewed request bound to the desktop-managed application identity / 绑定到桌面受管应用身份的经审阅请求
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public ReviewedDeploymentRequest createReviewedDeploymentRequest(
            ReviewedSourcePreparation preparation, ServerIdentity server, ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences, Optional<List<ManagedDatabaseBinding>> databaseBindings,
            gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl, BuildLimitConfiguration limits, boolean rootBuildConfirmed,
            boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted
    ) throws SQLException {
        return reviewedDeployment.createRequest(preparation, server, configuration, secretReferences, databaseBindings,
                        runtime, userAccessUrl, limits,
                        rootBuildConfirmed, containerDaemonRiskAccepted, experimentalAdapterRiskAccepted);
    }

    /**
     * Creates a request whose database scope has not yet been reviewed. / 创建数据库范围尚未审阅的请求。
     *
     * @param preparation preparation / 准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param rootBuildConfirmed root build confirmed / 根目录构建已确认
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     * @return a request whose database scope has not yet been reviewed / 数据库范围尚未审阅的请求
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    @Override
    public ReviewedDeploymentRequest createReviewedDeploymentRequest(
            ReviewedSourcePreparation preparation, ServerIdentity server, ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences,
            gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl, BuildLimitConfiguration limits, boolean rootBuildConfirmed,
            boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted
    ) throws SQLException {
        return AutomaticDeploymentApplicationFacade.super.createReviewedDeploymentRequest(preparation, server, configuration,
                secretReferences, runtime, userAccessUrl, limits, rootBuildConfirmed,
                containerDaemonRiskAccepted, experimentalAdapterRiskAccepted);
    }

    /**
     * Creates a request that cannot enter an experimental adapter. / 创建不能进入试验适配器的请求。
     *
     * @param preparation preparation / 准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param rootBuildConfirmed root build confirmed / 根目录构建已确认
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @return a request that cannot enter an experimental adapter / 不能进入试验适配器的请求
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public ReviewedDeploymentRequest createReviewedDeploymentRequest(
            ReviewedSourcePreparation preparation, ServerIdentity server, ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences, gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl, BuildLimitConfiguration limits, boolean rootBuildConfirmed,
            boolean containerDaemonRiskAccepted
    ) throws SQLException {
        return createReviewedDeploymentRequest(preparation, server, configuration, secretReferences, runtime, userAccessUrl,
                limits, rootBuildConfirmed, containerDaemonRiskAccepted, false);
    }

    /**
     * Executes a reviewed request using the selected saved server credential. / 使用选定的已保存服务器凭据执行经审阅请求。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved deployment outcome / 构造或解析得到的部署结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public DeploymentOutcome deployReviewedWithStoredPassword(ReviewedDeploymentRequest request, ServerProfile profile,
                                                              CredentialStorageMode mode, char[] masterPassword,
                                                              Predicate<String> confirmation)
            throws SecretStoreException, SQLException {
        return reviewedDeployment.deployWithStoredPassword(request, profile, mode, masterPassword, confirmation);
    }

    /**
     * Stores data through {@code saveServerProfile}.
     *
     *  <p>通过 {@code saveServerProfile} 保存数据。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param store store / 存储
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public void saveServerProfile(ServerProfile profile, SecretStore store, char[] password)
            throws SQLException, SecretStoreException {
        servers.save(profile, store, password);
    }

    /**
     * Stores data through {@code saveServerProfile}.
     *
     *  <p>通过 {@code saveServerProfile} 保存数据。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public void saveServerProfile(ServerProfile profile, CredentialStorageMode mode,
                                  char[] masterPassword, char[] password)
            throws SQLException, SecretStoreException {
        servers.save(profile, mode, masterPassword, password);
    }

    /**
     * Returns the value produced by {@code findServerProfile}.
     *
     *  <p>返回 {@code findServerProfile} 生成的值。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ServerProfile> findServerProfile(String serverId) throws SQLException {
        return servers.find(serverId);
    }

    /**
     * Invokes exactly the provider assigned to the supplied minimal role context. / 精确调用分配给所提供最小角色上下文的提供者。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public Optional<AiRoleInvocationResult> invokeAiRole(AiRoleContext context, char[] masterPassword)
            throws SQLException, SecretStoreException {
        return ai.invokeRole(context, masterPassword);
    }

    /**
     * Stores an immutable non-secret typed deployment configuration snapshot.
     *
     *  <p>保存一个不可变的非秘密部署配置快照。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveDeploymentConfigurationSnapshot(ConfigurationSnapshot snapshot) throws SQLException {
        deploymentConfiguration.saveSnapshot(snapshot);
    }

    /**
     * Stores one immutable application-secret revision without exposing its plaintext after this call.
     *
     *  <p>保存一个不可变应用秘密修订；此调用后不再暴露其明文。
     *
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param store store / 存储
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public void saveDeploymentSecretRevision(StoredApplicationSecretRevision revision, SecretStore store, char[] value)
            throws SQLException, SecretStoreException {
        deploymentConfiguration.saveSecretRevision(revision, store, value);
    }

    /**
     * Saves a user-entered secret reference without accepting storage metadata from UI. / 保存用户输入的秘密引用，不接受 UI 提供的存储元数据。
     *
     * @param referenceInput reference input / 引用输入
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved secret reference / 构造或解析得到的秘密引用
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    @Override public SecretReference saveDeploymentSecretRevision(String referenceInput, CredentialStorageMode mode,
            char[] masterPassword, char[] value) throws SQLException, SecretStoreException {
        return deploymentConfiguration.saveSecretRevision(referenceInput, mode, masterPassword, value);
    }

    /**
     * Stores one immutable application-secret revision through the selected desktop secret store. / 通过选定的桌面秘密存储保存一个不可变应用秘密修订。
     *
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public void saveDeploymentSecretRevision(StoredApplicationSecretRevision revision, CredentialStorageMode mode,
                                             char[] masterPassword, char[] value)
            throws SQLException, SecretStoreException {
        deploymentConfiguration.saveSecretRevision(revision, mode, masterPassword, value);
    }

    /**
     * Verifies and binds immutable secret revisions to a release identity before it may be used for deployment or rollback.
     *
     *  <p>在可用于部署或回滚前，验证并将不可变秘密修订绑定到发布标识。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param references references / 引用集合
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public void bindDeploymentReleaseSecrets(String applicationId, String releaseIdentity, List<SecretReference> references,
                                            char[] masterPassword) throws SQLException, SecretStoreException {
        deploymentConfiguration.bindReleaseSecrets(applicationId, releaseIdentity, references, masterPassword);
    }

    /**
     * Validates the input through {@code verifyServer}.
     *
     *  <p>通过 {@code verifyServer} 验证输入。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public ServerCapabilityFacts verifyServer(ServerProfile profile, CredentialStorageMode mode,
                                           char[] masterPassword, Predicate<String> confirmation)
            throws SecretStoreException, SQLException, LinuxOperationException {
        return servers.verify(profile, mode, masterPassword, confirmation);
    }

    /**
     * Collects exact non-secret deployment capabilities without mutating the selected server. / 在不修改所选服务器的情况下采集精确且不含秘密的部署能力。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved linux capability facts / 构造或解析得到的Linux能力事实
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public LinuxCapabilityFacts inspectDeploymentCapabilitiesWithStoredPassword(
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation) throws SecretStoreException, LinuxOperationException {
        return servers.inspectDeploymentCapabilities(profile, mode, masterPassword, confirmation);
    }

    /**
     * Prepares environment with stored password.
     * <p>准备环境具有已存储密码。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @param installationConfirmed installation confirmed / 安装已确认
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public EnvironmentSetupResult prepareEnvironmentWithStoredPassword(
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation, boolean installationConfirmed)
            throws SecretStoreException, SQLException, LinuxOperationException {
        return environment.prepare(profile, mode, masterPassword, confirmation, installationConfirmed);
    }

    /**
     * Prepares the target with a separate, target-bound system-change confirmation. / 使用独立且绑定目标的系统变更确认准备目标机。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @param installationConfirmed installation confirmed / 安装已确认
     * @param systemConfirmation system confirmation / 系统确认
     * @return constructed or resolved environment setup result / 构造或解析得到的环境Setup结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public EnvironmentSetupResult prepareEnvironmentWithStoredPassword(
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation, boolean installationConfirmed,
            Predicate<gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan> systemConfirmation)
            throws SecretStoreException, SQLException, LinuxOperationException {
        return environment.prepare(profile, mode, masterPassword, confirmation, installationConfirmed, systemConfirmation);
    }

    /**
     * Returns the value produced by {@code findTrustedServer}.
     *
     *  <p>返回 {@code findTrustedServer} 生成的值。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ServerIdentity> findTrustedServer(String serverId) throws SQLException {
        return servers.findTrusted(serverId);
    }

    /**
     * Returns the values selected by {@code listManagedApplications}.
     *
     *  <p>返回 {@code listManagedApplications} 选出的值。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<ManagedApplication> listManagedApplications() throws SQLException {
        return lifecycle.list();
    }

    /**
     * Returns the values selected by {@code listManagedApplicationSummaries}.
     *
     *  <p>返回 {@code listManagedApplicationSummaries} 选出的值。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<ManagedApplicationSnapshot> listManagedApplicationSummaries() throws SQLException {
        return lifecycle.summaries();
    }

    /**
     * Executes persisted lifecycle with stored password.
     * <p>执行已持久化生命周期具有已存储密码。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public LifecycleOutcome executePersistedLifecycleWithStoredPassword(
            String applicationId, LifecycleAction action, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return lifecycle.executePersisted(applicationId, action, masterPassword);
    }

    /**
     * Executes persisted lifecycle result with stored password.
     * <p>执行已持久化生命周期结果具有已存储密码。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public LifecycleActionResult executePersistedLifecycleResultWithStoredPassword(
            String applicationId, LifecycleAction action, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return lifecycle.executePersistedResult(applicationId, action, masterPassword);
    }

    /**
     * Executes lifecycle with stored password.
     * <p>执行生命周期具有已存储密码。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public LifecycleOutcome executeLifecycleWithStoredPassword(
            ManagedApplication application, LifecycleAction action, HealthCheck healthCheck,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return lifecycle.execute(application, action, healthCheck, profile, mode, masterPassword);
    }

    /**
     * Executes lifecycle result with stored password.
     * <p>执行生命周期结果具有已存储密码。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public LifecycleActionResult executeLifecycleResultWithStoredPassword(
            ManagedApplication application, LifecycleAction action, HealthCheck healthCheck,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return lifecycle.executeResult(application, action, healthCheck, profile, mode, masterPassword);
    }

    /**
     * Obtains the server's host-key evaluator with the supplied trust confirmation callback.
     * <p>结合所提供信任确认回调取得服务器主机密钥求值器。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved host key evaluator / 构造或解析得到的主机键Evaluator
     */
    HostKeyEvaluator hostKeyVerifier(ServerProfile profile, Predicate<String> confirmation) {
        return servers.hostKeyVerifier(profile, confirmation);
    }

    /**
     * Loads password credential.
     * <p>加载密码凭据。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param store store / 存储
     * @return password credential / 密码凭据
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    SshCredential.Password loadPasswordCredential(ServerProfile profile, SecretStore store)
            throws SecretStoreException {
        return servers.loadPassword(profile, store);
    }

    /**
     * Executes the same single transaction with streaming progress. / 以流式进度执行相同的单组件事务。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved deployment outcome / 构造或解析得到的部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public DeploymentOutcome deployAutomaticallyReviewed(ReviewedDeploymentRequest request,
            ServerProfile profile, char[] master, Predicate<String> fingerprint,
            java.util.function.Consumer<gold.debug.windowstolinux.shared.model.message.LocalizedMessage> progress) throws Exception {
        return reviewedDeployment.deployWithStoredPassword(request, profile, profile.credentialMode(), master, fingerprint,
                event -> progress.accept(event.message()));
    }

    /**
     * Executes the same whole-application transaction with streaming progress. / 以流式进度执行相同的整应用事务。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public MultiComponentDeploymentResult deployAutomaticallyReviewed(ReviewedMultiComponentApplication request,
            ServerProfile profile, char[] master, Predicate<String> fingerprint,
            java.util.function.Consumer<gold.debug.windowstolinux.shared.model.message.LocalizedMessage> progress) throws Exception {
        return multiComponentDeployment.deployWithStoredPassword(request, profile, profile.credentialMode(), master, fingerprint,
                event -> progress.accept(event.message()));
    }
    /**
     * Lists both strict managed and external lifecycle registrations. / 列出严格受管和外部生命周期登记。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    @Override public java.util.List<gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationSummary> listApplications() throws SQLException {
        return applicationInventory.list();
    }
    /**
     * Saves only local presentation. / 仅保存本地显示设置。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param category category / 类别
     * @param accessUrl access url / 访问URL
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    @Override public void saveApplicationPresentation(String key, String name, String category, String accessUrl) throws SQLException {
        applicationInventory.savePresentation(key, name, category, accessUrl);
    }
    /**
     * Scans selected server metadata without mutation. / 扫描所选服务器元数据，不作修改。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved application scan / 构造或解析得到的应用扫描
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationScan scanApplications(String serverId, char[] master,
            java.util.function.Predicate<String> confirmation) throws Exception {
        return externalApplications.scan(serverId, master, confirmation);
    }
    /**
     * Adopts only a freshly rechecked selected identity. / 仅接管刚复核过的所选身份。
     *
     * @param scan scan / 扫描
     * @param candidate candidate / 候选
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return adopt application text / 接管应用文本
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public String adoptApplication(gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationScan scan,
            gold.debug.windowstolinux.shared.model.lifecycle.DiscoveredApplication candidate, char[] master,
            java.util.function.Predicate<String> confirmation) throws Exception {
        return externalApplications.adopt(scan, candidate, master, confirmation);
    }
    /**
     * Routes each application through its original ownership and capability contract. / 让每种应用经其原有归属及能力契约执行。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved application lifecycle result / 构造或解析得到的应用生命周期结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override public gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationLifecycleResult executeApplicationLifecycle(String key,
            LifecycleAction action, char[] master, java.util.function.Predicate<String> confirmation) throws Exception {
        if (key.startsWith("external:")) return externalApplications.execute(key, action, master, confirmation);
        try {
            if (!key.startsWith("managed:")) throw new IllegalArgumentException("invalid application key");
            var result = lifecycle.executePersisted(key.substring(8), action, master);
            return new gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationLifecycleResult(
                    result.observation().map(value -> value.runtimeState()).orElse(gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.UNKNOWN),
                    result.observation().map(value -> value.observedAt()).orElseGet(java.time.Instant::now), java.util.Optional.of(result));
        } finally { java.util.Arrays.fill(master, '\0'); }
    }

    /**
     * Returns ordered model cards. / 返回有序模型卡片。
     *
     * @return ordered model cards / 有序模型卡片
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    @Override public java.util.List<gold.debug.windowstolinux.app.service.ai.AiProviderSummary> listAiConfigurations() throws SQLException { return ai.configurations(); }
    /**
     * Saves global model order transactionally. / 通过事务保存全局模型顺序。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param key API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @param capability verified text or vision capability / 已验证文本或视觉能力
     */

    @Override public void saveAiConfiguration(AiProviderProfile profile, String name, char[] master, char[] key, gold.debug.windowstolinux.shared.model.ai.AiCapabilityType capability) throws SQLException, SecretStoreException { ai.saveConfiguration(profile,name,master,key,capability); }
    /**
     * Reorders ai providers.
     * <p>重新排序AI提供者集合。
     *
     * @param ids ids / 标识集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    @Override public void reorderAiProviders(java.util.List<String> ids) throws SQLException { ai.reorder(ids); }
    /**
     * Tests one model without changing its group or priority. / 测试单个模型，不改变分组和顺序。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @param capability separately tested text or vision capability / 分别测试的文本或视觉能力
     */
    @Override public void testAiCapability(String id, gold.debug.windowstolinux.shared.model.ai.AiCapabilityType capability, char[] master) throws Exception { ai.testCapability(id, capability, master); }
    /** Reads purpose members. / 读取用途成员。
     * @param purpose selected purpose / 所选用途
     * @return ordered members / 有序成员
     * @throws SQLException if reading fails / 读取失败时
     */
    @Override public java.util.List<gold.debug.windowstolinux.shared.model.ai.AiPurposeAssignment> listAiPurpose(gold.debug.windowstolinux.shared.model.ai.AiPurposeType purpose) throws SQLException { return ai.purpose(purpose); }
    /** Saves purpose members. / 保存用途成员。
     * @param purpose selected purpose / 所选用途
     * @param members ordered members / 有序成员
     * @throws SQLException if saving fails / 保存失败时
     */
    @Override public void saveAiPurpose(gold.debug.windowstolinux.shared.model.ai.AiPurposeType purpose, java.util.List<gold.debug.windowstolinux.shared.model.ai.AiPurposeAssignment> members) throws SQLException { ai.savePurpose(purpose,members); }
    /**
     * Starts APP browser rescue. / 启动 APP 浏览器救援。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @return constructed or resolved ssh recovery session / 构造或解析得到的SSH恢复会话
     */
    @Override public gold.debug.windowstolinux.app.service.contract.SshRecoverySession startSshRecovery(
            ServerProfile server, char[] master, Predicate<String> fingerprint) {
        return recovery.start(server, master, fingerprint);
    }
    /**
     * Closes all rescue resources. / 关闭全部救援资源。
     */
    @Override public void closeSshRecovery() { recovery.close(); }
    /**
     * Preserves the current source snapshot and server mutex during rescue. / 救援期间保留当前源码快照与服务器互斥。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param operation operation / 操作
     * @return true when preserves the current source snapshot and server mutex during rescue, false otherwise / 救援期间保留当前源码快照与服务器互斥时为 true，否则为 false
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public boolean recoverSshForOperation(ServerProfile server, char[] master, Predicate<String> fingerprint,
            AutomaticDeploymentInteraction interaction, String operation) throws Exception {
        if (!(interaction instanceof DesktopRecoveryInteraction desktop) || !desktop.offerSshRecovery(server)) {
            java.util.Arrays.fill(master, '\0'); return false;
        }
        return recovery.recoverInline(server, master, fingerprint, operation, session -> desktop.showSshRecovery(server, session));
    }
    /**
     * Performs a read-only server check with optional browser rescue. / 执行可选浏览器救援的只读服务器检查。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved server capability facts / 构造或解析得到的服务器能力事实
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public ServerCapabilityFacts verifyServerRecovering(ServerProfile profile, CredentialStorageMode mode,
            char[] master, Predicate<String> fingerprint, DesktopRecoveryInteraction interaction) throws Exception {
        return recovery.verify(profile, mode, master, fingerprint, interaction);
    }
    /**
     * Preserves completed installation evidence during manual environment recovery. / 人工环境救援期间保留已完成安装证据。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param confirmed confirmed / 已确认
     * @param system system / 系统
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved environment setup result / 构造或解析得到的环境Setup结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Override public EnvironmentSetupResult prepareEnvironmentRecovering(ServerProfile profile, CredentialStorageMode mode,
            char[] master, Predicate<String> fingerprint, boolean confirmed,
            Predicate<gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan> system,
            DesktopRecoveryInteraction interaction) throws Exception {
        return recovery.prepare(profile, mode, master, fingerprint, confirmed, system, interaction, environment);
    }
    /** Requests a safe task control transition. / 请求安全任务控制转换。
     * @param taskId active task identity / 活动任务身份
     * @param command requested transition / 请求转换
     */
    @Override public void controlDeployment(String taskId,gold.debug.windowstolinux.shared.model.agent.AgentTaskCommandAction command){automatic.control(taskId,command);}

    /** Reads process-local state and pause reason. / 读取进程内状态及暂停原因。
     * @param taskId active task identity / 活动任务身份
     * @return nonsecret state / 非秘密状态
     */
    @Override public java.util.Map<String,String> deploymentTaskState(String taskId){return automatic.state(taskId);}

    /** Reads recent deployment task history. / 读取最近部署任务历史。
     * @return nonsecret records / 非秘密记录
     * @throws java.sql.SQLException on database failure / 数据库失败时
     */
    @Override public java.util.List<java.util.Map<String,String>> deploymentTaskHistory() throws java.sql.SQLException{return automatic.history();}

    /** Reads a task audit trail. / 读取任务审计轨迹。
     * @param taskId task identity / 任务身份
     * @return bounded audit events / 有界审计事件
     * @throws java.sql.SQLException on database failure / 数据库失败时
     */
    @Override public java.util.List<java.util.Map<String,String>> deploymentTaskEvents(String taskId) throws java.sql.SQLException{return automatic.events(taskId);}

    /** Queries recorded task candidates without authorizing a retry. / 查询任务候选项，不授权重试。
     * @param taskId task identity / 任务身份
     * @param master scoped credential buffer / 限定凭据缓冲区
     * @return actual observations / 实际观测
     * @throws Exception when observation fails / 观测失败时
     */
    @Override public java.util.List<java.util.Map<String,String>> inspectDeploymentTask(String taskId,char[] master)throws Exception{return automatic.inspect(taskId,master);}
}
