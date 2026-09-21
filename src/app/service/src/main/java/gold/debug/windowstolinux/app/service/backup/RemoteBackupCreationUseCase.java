package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ConfigurationSnapshotRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.shared.config.persistence.serialization.DeploymentRuntimePersistenceCodec;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretCryptoService;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.app.windows.workspace.WindowsBackupMaterialAttempt;
import gold.debug.windowstolinux.app.windows.workspace.WindowsBackupMaterialWorkspace;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupAdapter;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseConnectionProfile;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchivePolicy;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.contract.validation.ManagedArtifactValidator;
import gold.debug.windowstolinux.shared.backup.contract.validation.ManagedArtifactEvidence;
import gold.debug.windowstolinux.shared.backup.extension.adapter.LinuxDatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.format.BackupArchiveContent;
import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationCodec;
import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationDocument;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponent;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponentRuntime;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;
import gold.debug.windowstolinux.shared.backup.manifest.BackupHealthCheck;
import gold.debug.windowstolinux.shared.backup.manifest.BackupIdentity;
import gold.debug.windowstolinux.shared.backup.manifest.BackupInventory;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.backup.manifest.BackupRuntime;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlanner;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifact;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactKind;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactRequest;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Creates one complete product backup from exact persisted review evidence and bounded remote protocols. / 从精确持久化审阅证据及有界远端协议创建完整产品备份。
 */
public final class RemoteBackupCreationUseCase {
    /**
     * MAXIMUM REMOTE ARTIFACT BYTES.
     * <p>最大远端制品字节。
     */
    private static final long MAXIMUM_REMOTE_ARTIFACT_BYTES = 4L * 1024 * 1024 * 1024;
    /**
     * Bound managed application graph repository collaborator for graphs.
     * <p>处理图集合的受管应用图仓库协作对象。
     */
    private final ManagedApplicationGraphRepository graphs;
    /**
     * Bound managed application repository collaborator for applications.
     * <p>处理应用集合的受管应用仓库协作对象。
     */
    private final ManagedApplicationRepository applications;
    /**
     * Bound configuration snapshot repository collaborator for configurations.
     * <p>处理配置集合的配置快照仓库协作对象。
     */
    private final ConfigurationSnapshotRepository configurations;
    /**
     * Bound application secret repository collaborator for secret metadata.
     * <p>处理秘密元数据的应用秘密仓库协作对象。
     */
    private final ApplicationSecretRepository secretMetadata;
    /**
     * Input assessment.
     * <p>输入评估。
     */
    private final ManagedBackupInputUseCase inputAssessment;
    /**
     * Factory for authenticated Linux sessions.
     * <p>已认证 Linux 会话的工厂。
     */
    private final DeploymentLinuxGateway gateway;
    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;
    /**
     * Shared operation locks indexed by target identity.
     * <p>按目标身份索引的共享操作锁。
     */
    private final ServerOperationLockRegistry locks;
    /**
     * Materials.
     * <p>素材集合。
     */
    private final WindowsBackupMaterialWorkspace materials;
    /**
     * Archives.
     * <p>归档集合。
     */
    private final BackupArchiveCreationUseCase archives;
    /**
     * Bound backup configuration codec collaborator for configuration codec.
     * <p>处理配置编解码器的备份配置编解码器协作对象。
     */
    private final BackupConfigurationCodec configurationCodec = new BackupConfigurationCodec();
    /**
     * Bound deployment runtime persistence codec collaborator for runtime codec.
     * <p>处理运行时编解码器的部署运行时持久化编解码器协作对象。
     */
    private final DeploymentRuntimePersistenceCodec runtimeCodec = new DeploymentRuntimePersistenceCodec();
    /**
     * Bound backup secret crypto service collaborator for backup secrets.
     * <p>处理备份秘密集合的备份秘密加密服务协作对象。
     */
    private final BackupSecretCryptoService backupSecrets = new BackupSecretCryptoService();
    /**
     * Random.
     * <p>随机。
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * Creates the complete backup orchestrator under the sole run-mode-derived work directory. / 在唯一由运行模式派生的工作目录下创建完整备份编排器。
     *
     * @param graphs graphs / 图集合
     * @param applications applications / 应用集合
     * @param configurations configurations / 配置集合
     * @param secretMetadata secret metadata / 秘密元数据
     * @param inputAssessment input assessment / 输入评估
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     * @param workDirectory work directory / 工作目录
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteBackupCreationUseCase(
            ManagedApplicationGraphRepository graphs,
            ManagedApplicationRepository applications,
            ConfigurationSnapshotRepository configurations,
            ApplicationSecretRepository secretMetadata,
            ManagedBackupInputUseCase inputAssessment,
            DeploymentLinuxGateway gateway,
            ServerUseCaseFacade servers,
            ServerOperationLockRegistry locks,
            Path workDirectory
    ) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.applications = Objects.requireNonNull(applications, "applications");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.secretMetadata = Objects.requireNonNull(secretMetadata, "secretMetadata");
        this.inputAssessment = Objects.requireNonNull(inputAssessment, "inputAssessment");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.materials = new WindowsBackupMaterialWorkspace(workDirectory);
        this.archives = new BackupArchiveCreationUseCase();
    }

    /**
     * Creates, validates, and atomically publishes one complete backup while restoring the original run state. / 创建、校验并原子发布完整备份，同时恢复原运行状态。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return constructed or resolved created backup archive / 构造或解析得到的已创建备份归档
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    public CreatedBackupArchive createUsingSavedProfile(
            String applicationId,
            Path destination,
            char[] backupPassword,
            char[] masterPassword,
            Predicate<String> firstUseConfirmation
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException {
        return createUsingSavedProfile(applicationId,destination,backupPassword,masterPassword,firstUseConfirmation,null);
    }
    /**
     * Creates using saved profile.
     * <p>创建使用已保存配置资料。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @param heldMaintenance held maintenance / 已持有维护
     * @return using saved profile / 使用已保存配置资料
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    public CreatedBackupArchive createUsingSavedProfile(String applicationId, Path destination, char[] backupPassword,
            char[] masterPassword, Predicate<String> firstUseConfirmation, String heldMaintenance)
            throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException {
        try {
            Optional<ManagedApplicationGraph> graph = graphs.find(applicationId);
            if (graph.isEmpty()) {
                throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                        "the selected application has no persisted whole-application graph");
            }
            String serverId = graph.orElseThrow().components().getFirst().application().server().id();
            ServerProfile profile = servers.find(serverId).orElseThrow(() ->
                    ApplicationServiceException.create(ApplicationServiceFailureType.SERVER_PROFILE_MISSING,
                            "the managed application's saved server profile is unavailable"));
            return create(applicationId, destination, backupPassword, profile, profile.credentialMode(),
                    masterPassword, firstUseConfirmation, heldMaintenance);
        } finally {
            clear(backupPassword); clear(masterPassword);
        }
    }

    /**
     * Creates, validates, and atomically publishes one complete backup with an explicit saved profile. / 使用显式已保存资料创建、校验并原子发布完整备份。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return constructed or resolved created backup archive / 构造或解析得到的已创建备份归档
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    public CreatedBackupArchive create(
            String applicationId,
            Path destination,
            char[] backupPassword,
            ServerProfile profile,
            CredentialStorageMode mode,
            char[] masterPassword,
            Predicate<String> firstUseConfirmation
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException {
        return create(applicationId,destination,backupPassword,profile,mode,masterPassword,firstUseConfirmation,null);
    }
    /**
     * Creates created backup archive.
     * <p>创建已创建备份归档。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @param heldMaintenance held maintenance / 已持有维护
     * @return created backup archive / 已创建备份归档
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    private CreatedBackupArchive create(String applicationId, Path destination, char[] backupPassword,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword, Predicate<String> firstUseConfirmation,
            String heldMaintenance) throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException {
        try {
            ManagedBackupInputAssessment assessment = inputAssessment.assess(applicationId);
            if (!assessment.persistedInputsComplete()) {
                throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                        "complete remote backup creation requires every exact persisted review input");
            }
            BackupContext context = loadContext(assessment.applicationId());
            validateProfile(context, profile, mode);
            validateDatabaseScope(context);
            ReentrantLock lock = locks.forServer(profile.id());
            lock.lock();
            try {
                return createLocked(context, destination, backupPassword, profile, mode, masterPassword,
                        firstUseConfirmation, heldMaintenance);
            } finally {
                lock.unlock();
            }
        } finally {
            clear(backupPassword);
            clear(masterPassword);
        }
    }

    /**
     * Pauses or resumes managed task admission for an enclosing offline migration. / 离线迁移的任务准入窗口。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param token token / 令牌
     * @param pause pause / 暂停
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public void taskAdmission(String applicationId, String token, boolean pause, char[] masterPassword,
            Predicate<String> confirmation) throws SQLException, SecretStoreException, LinuxOperationException {
        try {
            var graph=graphs.find(applicationId).orElseThrow();
            var profile=servers.find(graph.components().getFirst().application().server().id()).orElseThrow();
            try (SecretStore store=servers.secrets().open(profile.credentialMode(),masterPassword)) {
                var credential=servers.loadPassword(profile,store);
                try (DeploymentRemoteSession session=gateway.connect(profile.endpoint(),credential,servers.hostKeyVerifier(profile,confirmation))) {
                    var paused=new ArrayList<gold.debug.windowstolinux.shared.model.managed.ManagedApplication>();
                    try {
                        for(var component:graph.components()) {
                            if(pause)session.backupArtifacts().beginMaintenance(component.application(),token);
                            else session.backupArtifacts().endMaintenance(component.application(),token);
                            paused.add(component.application());
                        }
                    } catch(LinuxOperationException failure) {
                        if(pause)for(var app:paused.reversed())try { session.backupArtifacts().endMaintenance(app,token); }
                        catch(LinuxOperationException recovery) { failure.addSuppressed(recovery); }
                        throw failure;
                    }
                } finally { credential.clear(); }
            }
        } finally { clear(masterPassword); }
    }
    /**
     * Returns only reviewed daemon component IDs. / 只返回经审阅的长期进程组件。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return only reviewed daemon component IDs / 只返回经审阅的长期进程组件
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Set<String> daemonComponents(String applicationId) throws SQLException {
        return graphs.find(applicationId).orElseThrow().components().stream()
                .filter(component -> component.runtimeConfiguration().workload().supportsLifecycle())
                .map(ManagedApplicationGraph.Component::componentId).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Collects and packages an admitted managed backup under the server lock, owning local work and credential cleanup.
     * <p>在服务器锁内采集并打包已准入受管备份，负责本地工作区及凭据清理。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @param heldMaintenance held maintenance / 已持有维护
     * @return constructed or resolved created backup archive / 构造或解析得到的已创建备份归档
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private CreatedBackupArchive createLocked(
            BackupContext context,
            Path destination,
            char[] backupPassword,
            ServerProfile profile,
            CredentialStorageMode mode,
            char[] masterPassword,
        Predicate<String> firstUseConfirmation, String heldMaintenance
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException {
        WindowsBackupMaterialAttempt attempt = materials.createAttempt();
        CreatedBackupArchive created;
        try {
            HostKeyEvaluator verifier = servers.hostKeyVerifier(profile,
                    Objects.requireNonNull(firstUseConfirmation, "firstUseConfirmation"));
            try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
                SshCredential.Password credential = servers.loadPassword(profile, store);
                try (DeploymentRemoteSession session = gateway.connect(profile.endpoint(), credential, verifier)) {
                    credential.clear();
                    CollectedRemoteBackup remote = collectRemote(context, attempt, session, heldMaintenance);
                    List<Material> all = new ArrayList<>(remote.materials());
                    addLocalDefinitions(context, attempt, all);
                    addEncryptedSecrets(context, attempt, all, backupPassword, masterPassword);
                    BackupManifest manifest = manifest(context, remote, all);
                    created = archives.create(manifest, contents(all), destination);
                } finally {
                    credential.clear();
                }
            }
        } catch (SQLException | SecretStoreException | LinuxOperationException | IOException
                 | BackupSecretException | RuntimeException exception) {
            try {
                materials.discard(attempt);
            } catch (WindowsWorkspaceException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        materials.discard(attempt);
        return created;
    }

    /**
     * Adapts persisted desktop component and application-health contracts to the shared collection service while retaining desktop ownership of local material and cancellation state.
     * <p>将持久化桌面组件及整应用健康契约适配到共享采集服务，并由桌面保留本地素材及取消状态的所有权。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param attempt attempt / 尝试
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param heldMaintenance held maintenance / 已持有维护
     * @return constructed or resolved collected remote backup / 构造或解析得到的已采集远端备份
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private CollectedRemoteBackup collectRemote(
            BackupContext context, WindowsBackupMaterialAttempt attempt, DeploymentRemoteSession session, String heldMaintenance)
            throws LinuxOperationException, IOException {
        var request = new gold.debug.windowstolinux.shared.backup.contract.definition.BackupCollectionRequest(
                context.plan(), context.plan().startOrder().stream().map(id -> {
                    var component = context.components().get(id);
                    return new gold.debug.windowstolinux.shared.backup.contract.definition.BackupCollectionRequest.Component(
                            id, component.graph().application(), component.runtime(), component.release().releaseSha256(),
                            component.graph().reviewedResourceBindings().orElseThrow());
                }).toList(), heldMaintenance == null ? operationId() : heldMaintenance, heldMaintenance == null,
                new gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate(context.graph().healthComponentId(),
                        context.graph().applicationHealthCheck().orElseThrow()), Set.copyOf(context.plan().startOrder()),
                database(context).map(selected -> new gold.debug.windowstolinux.shared.backup.contract.definition.BackupCollectionRequest.Database(
                        selected.component().graph().componentId(), selected.binding().databaseId(), selected.profile())),
                MAXIMUM_REMOTE_ARTIFACT_BYTES);
        try {
        var result = new gold.debug.windowstolinux.shared.backup.execution.collection.BackupCollectionService(BackupArchivePolicy.defaults())
                .collect(request, session, new gold.debug.windowstolinux.shared.backup.contract.spi.BackupCollectionMaterialPort() {
                    /**
                     * Resolves a canonical archive-member path within the owning storage boundary.
                     * <p>在所属存储边界内解析规范归档成员路径。
                     *
                     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
                     * @return a canonical archive-member path within the owning storage boundary / 在所属存储边界内解析规范归档成员路径
                     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
                     */
                    @Override public Path member(String name) throws IOException { return materials.member(attempt, name); }
                    /**
                     * Opens output stream.
                     * <p>打开输出流。
                     *
                     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
                     * @return constructed or resolved output stream / 构造或解析得到的输出流
                     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
                     */
                    @Override public OutputStream open(Path path) throws IOException {
                        return Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    }
                }, new gold.debug.windowstolinux.shared.backup.contract.spi.BackupCollectionInteraction() {
                    /**
                     * Checks cancelled.
                     * <p>检查已取消。
                     *
                     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
                     */
                    @Override public void checkCancelled() throws InterruptedException {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Backup cancelled");
                    }
                    /**
                     * Accepts the callback without side effects because this adapter needs no additional action.
                     * <p>接受回调且不产生副作用，因为当前适配器无需额外动作。
                     *
                     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
                     */
                    @Override public void collecting(String id) { }
                });
        return new CollectedRemoteBackup(result.materials().entrySet().stream()
                .map(entry -> new Material(entry.getKey(), entry.getValue())).toList(), result.database(), result.runtime());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            var interrupted = new java.io.InterruptedIOException("Backup cancelled after necessary recovery");
            interrupted.initCause(cancelled); throw interrupted;
        }
    }

    /**
     * Adds local definitions.
     * <p>添加本地定义集合。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param attempt attempt / 尝试
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private void addLocalDefinitions(
            BackupContext context, WindowsBackupMaterialAttempt attempt, List<Material> target) throws IOException {
        for (String componentId : context.plan().startOrder()) {
            ComponentContext component = context.components().get(componentId);
            target.add(write(attempt, "config/" + componentId + ".bin", BackupMemberKind.CONFIGURATION,
                    configurationCodec.writeActivation(new BackupConfigurationDocument(component.configuration(),
                            component.graph().reviewedResourceBindings().orElseThrow(),
                            component.graph().runtimeConfiguration()))));
            target.add(write(attempt, "runtime/" + componentId + ".bin", BackupMemberKind.RUNTIME,
                    runtimeCodec.write(component.runtime())));
        }
    }

    /**
     * Resolves exact referenced secret revisions, encrypts them with the backup password and adds the authenticated envelope member.
     * <p>解析精确引用秘密修订，使用备份密码加密，并添加认证信封成员。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param attempt attempt / 尝试
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private void addEncryptedSecrets(
            BackupContext context,
            WindowsBackupMaterialAttempt attempt,
            List<Material> target,
            char[] backupPassword,
            char[] masterPassword
    ) throws SQLException, SecretStoreException, BackupSecretException, IOException {
        List<SecretReference> references = context.components().values().stream()
                .flatMap(component -> component.secretReferences().stream()).distinct()
                .sorted(Comparator.comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision))
                .toList();
        if (references.isEmpty()) return;
        List<ResolvedSecretRevision> resolved = new ArrayList<>();
        byte[] encrypted = null;
        try {
            for (SecretReference reference : references) {
                var metadata = secretMetadata.findRevision(reference).orElseThrow(() ->
                        SecretStoreException.create(SecretStoreFailureType.APPLICATION_REFERENCE_MISSING,
                                "Application secret revision metadata is missing"));
                try (SecretStore store = servers.secrets().open(metadata.credentialMode(), masterPassword)) {
                    char[] value = store.read(metadata.credentialKey()).orElseThrow(() ->
                            SecretStoreException.create(SecretStoreFailureType.APPLICATION_REFERENCE_MISSING,
                                    "Application secret revision is unavailable from its selected platform store"));
                    try {
                        resolved.add(new ResolvedSecretRevision(reference, value));
                    } finally {
                        clear(value);
                    }
                }
            }
            encrypted = backupSecrets.encryptRevisions(backupPassword, resolved);
            target.add(write(attempt, "secrets.enc", BackupMemberKind.ENCRYPTED_SECRETS, encrypted));
        } finally {
            resolved.forEach(ResolvedSecretRevision::close);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    /**
     * Assembles the backup manifest from sorted local members, reviewed inventory and collected runtime evidence.
     * <p>根据排序后的本地成员、已审阅清单及采集运行证据组装备份清单。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param remote the credential-free remote / 不含凭据的远端
     * @param materials materials / 素材集合
     * @return constructed or resolved backup manifest / 构造或解析得到的备份清单
     */
    private BackupManifest manifest(
            BackupContext context, CollectedRemoteBackup remote, List<Material> materials) {
        Map<String, BackupMember> members = new LinkedHashMap<>();
        materials.stream().map(Material::member).sorted(Comparator.comparing(BackupMember::path))
                .forEach(member -> members.put(member.path(), member));
        List<BackupComponent> components = new ArrayList<>();
        for (String componentId : context.plan().startOrder()) {
            ComponentContext component = context.components().get(componentId);
            components.add(new BackupComponent(componentId, component.graph().application().id(),
                    component.graph().application().ownershipManifestSha256(),
                    "releases/" + componentId + ".pax", "config/" + componentId + ".bin",
                    "runtime/" + componentId + ".bin", component.graph().dependencies(),
                    BackupComponentRuntime.from(component.runtime()), component.release().releaseSha256(),
                    component.secretReferences()));
        }
        List<SecretReference> secrets = components.stream().flatMap(component ->
                        component.secretReferences().orElseThrow().stream()).distinct()
                .sorted(Comparator.comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision))
                .toList();
        List<String> files = members.values().stream().filter(member -> member.kind() == BackupMemberKind.PERSISTENT_CONTENT
                && member.path().contains("/files/")).map(BackupMember::path).toList();
        List<String> volumes = members.values().stream().filter(member -> member.kind() == BackupMemberKind.PERSISTENT_CONTENT
                && member.path().contains("/volumes/")).map(BackupMember::path).toList();
        BackupIdentity identity = new BackupIdentity(context.graph().applicationId(),
                context.graph().components().getFirst().application().server().id(),
                "/opt/windowstolinux/apps/" + context.graph().applicationId(),
                BackupInventory.computeReleaseSetSha256(components));
        BackupInventory inventory = new BackupInventory(
                components.stream().map(BackupComponent::releaseManifestPath).toList(),
                components.stream().map(BackupComponent::configurationSnapshotPath).toList(), secrets,
                files, volumes, remote.database(), identity,
                components.stream().map(BackupComponent::serviceDefinitionPath).toList(), components,
                context.graph().healthComponentId(),
                BackupHealthCheck.from(context.graph().applicationHealthCheck().orElseThrow()), remote.runtime(),
                List.of("restore requires managed helper protocol 5",
                        "official ports require a final post-commit health verification"));
        return BackupManifest.create(Instant.now(), context.graph().applicationId(), inventory,
                List.copyOf(members.values()));
    }

    /**
     * Loads the persisted successful application graph and requires complete backup resource and release evidence.
     * <p>加载持久化成功应用图，并要求完整的备份资源及发布证据。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return the persisted successful application graph and requires complete backup resource and release evidence / 持久化成功应用图，并要求完整的备份资源及发布证据
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private BackupContext loadContext(String applicationId) throws SQLException {
        ManagedApplicationGraph graph = graphs.find(applicationId).orElseThrow(() ->
                ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                        "the persisted whole-application graph disappeared"));
        if (graph.applicationHealthCheck().isEmpty()) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                    "the independently reviewed whole-application health check is unavailable");
        }
        Map<String, String> namespaces = new LinkedHashMap<>();
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        LinkedHashMap<String, ComponentContext> components = new LinkedHashMap<>();
        for (ManagedApplicationGraph.Component component : graph.components()) {
            CurrentRelease release = applications.findRelease(component.application().id()).orElseThrow(() ->
                    ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                            "one persisted current release disappeared"));
            var configuration = configurations.findRelease(component.application().id(), release.releaseSha256())
                    .orElseThrow(() -> ApplicationServiceException.create(
                            ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                            "one exact release configuration disappeared"));
            List<SecretReference> secrets = secretMetadata.findRelease(component.application().id(),
                    release.releaseSha256()).orElseThrow(() -> ApplicationServiceException.create(
                    ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                    "one exact release secret binding disappeared"));
            DeploymentRuntimeSpecification runtime = component.reviewedRuntime().orElseThrow();
            namespaces.put(component.componentId(), component.application().id());
            dependencies.put(component.componentId(), component.dependencies());
            components.put(component.componentId(), new ComponentContext(component, release,
                    configuration, List.copyOf(secrets), runtime));
        }
        MultiComponentDeploymentPlan plan = new MultiComponentDeploymentPlanner().restore(
                graph.applicationId(), namespaces, dependencies);
        return new BackupContext(graph, plan, Map.copyOf(components));
    }

    /**
     * Validates connection or provider settings supplied to the operation.
     * <p>校验提供给操作的连接或提供者设置。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static void validateProfile(BackupContext context, ServerProfile profile, CredentialStorageMode mode) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(mode, "mode");
        if (profile.credentialMode() != mode || context.components().values().stream().anyMatch(component ->
                !component.graph().application().server().id().equals(profile.id())
                        || !component.graph().application().server().host().equals(profile.host())
                        || component.graph().application().server().sshPort() != profile.sshPort())) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.LIFECYCLE_CONTEXT_MISMATCH,
                    "managed backup graph, server endpoint, and credential mode must match");
        }
    }

    /**
     * Validates database scope.
     * <p>校验数据库作用域。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     */
    private static void validateDatabaseScope(BackupContext context) {
        List<DatabaseContext> databases = databases(context);
        if (databases.size() > 1) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                    "schema v5 supports exactly zero or one reviewed database artifact per application");
        }
        if (!databases.isEmpty()) {
            DatabaseContext database = databases.getFirst();
            if (database.binding().connection() instanceof ManagedDatabaseConnection.Server server
                    && !database.component().secretReferences().contains(server.passwordReference())) {
                throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                        "the reviewed database password revision is not bound to the component release");
            }
        }
    }

    /**
     * Enumerates the reviewed database bindings together with their owning components.
     * <p>枚举已审阅数据库绑定及其所属组件。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private static List<DatabaseContext> databases(BackupContext context) {
        List<DatabaseContext> databases = new ArrayList<>();
        context.components().values().forEach(component -> component.graph().reviewedResourceBindings().orElseThrow()
                .databaseBindings().orElseThrow().forEach(binding -> databases.add(
                        new DatabaseContext(component, binding, BackupDatabaseProfileMapper.profile(binding)))));
        return List.copyOf(databases);
    }

    /**
     * Returns the first reviewed database binding when one exists.
     * <p>存在已审阅数据库绑定时返回第一个绑定。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<DatabaseContext> database(BackupContext context) {
        List<DatabaseContext> databases = databases(context);
        return databases.isEmpty() ? Optional.empty() : Optional.of(databases.getFirst());
    }



    /**
     * Writes material.
     * <p>写入素材。
     *
     * @param attempt attempt / 尝试
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @return constructed or resolved material / 构造或解析得到的素材
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private Material write(
            WindowsBackupMaterialAttempt attempt, String path, BackupMemberKind kind, byte[] bytes) throws IOException {
        try {
            Path target = materials.member(attempt, path);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Evidence evidence = evidence(target);
            return new Material(new BackupMember(path, evidence.size(), evidence.sha256(), kind), target);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Creates path-sorted archive entries whose suppliers open each local material on demand.
     * <p>创建按路径排序的归档条目，其提供函数按需打开各本地素材。
     *
     * @param materials materials / 素材集合
     * @return path-sorted archive entries whose suppliers open each local material on demand / 按路径排序的归档条目，其提供函数按需打开各本地素材
     */
    private static List<BackupArchiveContent> contents(List<Material> materials) {
        return materials.stream().sorted(Comparator.comparing(value -> value.member().path()))
                .map(material -> new BackupArchiveContent(material.member(), () -> Files.newInputStream(material.path())))
                .toList();
    }

    /**
     * Reads the complete local member to measure its byte count and SHA-256 digest.
     * <p>读取完整本地成员以测量字节数及 SHA-256 摘要。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return the complete local member to measure its byte count and SHA-256 digest / 完整本地成员以测量字节数及 SHA-256 摘要
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static Evidence evidence(Path path) throws IOException {
        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                size = Math.addExact(size, read);
                digest.update(buffer, 0, read);
            }
        } catch (ArithmeticException exception) {
            throw new IOException("backup material size overflow", exception);
        }
        return new Evidence(size, HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Returns identifier shared by the remote operation and its maintenance markers.
     * <p>返回远端操作及其维护标记共享的标识。
     *
     * @return identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
     */
    private String operationId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return "backup-" + HexFormat.of().formatHex(bytes);
    }

    /**
     * Creates a SHA-256 accumulator for independent content evidence.
     * <p>创建用于独立内容证据的 SHA-256 累加器。
     *
     * @return new SHA-256 digest accumulator / 新的 SHA-256 摘要累加器
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * Clears retained credential material after its scoped use.
     * <p>在限定作用域使用结束后清空保留的凭据素材。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    /**
     * Carries the reviewed application graph, dependency plan and exact component inputs.
     * <p>携带已审阅应用图、依赖计划及精确组件输入。
     *
     * @param graph graph / 图
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     */
    private record BackupContext(
            ManagedApplicationGraph graph,
            MultiComponentDeploymentPlan plan,
            Map<String, ComponentContext> components
    ) { }

    /**
     * Carries a component's persisted release, configuration, secrets and runtime contract.
     * <p>携带组件持久化的发布、配置、秘密引用及运行契约。
     *
     * @param graph graph / 图
     * @param release release / 发布
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     */
    private record ComponentContext(
            ManagedApplicationGraph.Component graph,
            CurrentRelease release,
            gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences,
            DeploymentRuntimeSpecification runtime
    ) { }

    /**
     * Associates one admitted database binding and connection profile with its owning component.
     * <p>将一个已准入数据库绑定和连接配置与其所属组件关联。
     *
     * @param component component / 组件
     * @param binding binding / 绑定
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     */
    private record DatabaseContext(
            ComponentContext component,
            ManagedDatabaseBinding binding,
            DatabaseConnectionProfile profile
    ) { }

    /**
     * Pairs a validated archive member with its private local material path.
     * <p>将已验证归档成员与其私有本地素材路径配对。
     *
     * @param member member / 成员
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     */
    private record Material(BackupMember member, Path path) { }
    /**
     * Records independently measured byte count and SHA-256 identity.
     * <p>记录独立测量的字节数及 SHA-256 身份。
     *
     * @param size size / 大小
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     */
    private record Evidence(long size, String sha256) { }
    /**
     * Contains collected members and the database and runtime facts used by the backup manifest.
     * <p>包含采集成员及备份清单使用的数据库和运行事实。
     *
     * @param materials materials / 素材集合
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     */
    private record CollectedRemoteBackup(
            List<Material> materials,
            BackupDatabase database,
            BackupRuntime runtime
    ) { }
}
