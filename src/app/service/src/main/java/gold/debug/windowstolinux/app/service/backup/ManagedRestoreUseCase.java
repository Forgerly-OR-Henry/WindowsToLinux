package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.shared.backup.restore.RestoreTargetEvaluator;

import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.extension.adapter.LinuxDatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.extension.adapter.LinuxRestoreCandidateAdapter;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreCoordinator;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestorePlan;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestorePreflight;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreResult;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreStatus;
import gold.debug.windowstolinux.shared.backup.restore.RestoreMaterialKind;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.DeploymentInputMapper;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.ManagedRestoreDeploymentPort;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Product restore composition over local validation, SSH, deploy, database, recovery and persistence. / 组合本地校验、SSH、部署、数据库、恢复及持久化的产品恢复用例。
 */
public final class ManagedRestoreUseCase {
    /**
     * Backups.
     * <p>备份集合。
     */
    private final BackupUseCase backups;
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
     * Recorder.
     * <p>记录器。
     */
    private final RestoredApplicationRecorder recorder;
    /**
     * Secret registrar.
     * <p>秘密Registrar。
     */
    private final RestoredSecretRegistrar secretRegistrar;
    /**
     * Target evaluator.
     * <p>目标Evaluator。
     */
    private final RestoreTargetEvaluator targetEvaluator = new RestoreTargetEvaluator();

    /**
     * Creates the production restore composition without transport-specific dependencies. / 创建不含传输实现依赖的生产恢复组合。
     *
     * @param backups backups / 备份集合
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     * @param graphs graphs / 图集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedRestoreUseCase(
            BackupUseCase backups,
            DeploymentLinuxGateway gateway,
            ServerUseCaseFacade servers,
            ServerOperationLockRegistry locks,
            ManagedApplicationGraphRepository graphs,
            ApplicationSecretRepository secrets
    ) {
        this.backups = java.util.Objects.requireNonNull(backups, "backups");
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        this.servers = java.util.Objects.requireNonNull(servers, "servers");
        this.locks = java.util.Objects.requireNonNull(locks, "locks");
        this.recorder = new RestoredApplicationRecorder(graphs);
        this.secretRegistrar = new RestoredSecretRegistrar(secrets, servers.secrets());
    }

    /**
     * Restores one exact archive to a selected saved target profile. / 将一个精确归档恢复到选定的已保存目标资料。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param targetServerId target server id / 目标服务器标识
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return constructed or resolved managed restore outcome / 构造或解析得到的受管恢复结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedRestoreOutcome restoreUsingSavedProfile(
            Path archive,
            String targetServerId,
            char[] backupPassword,
            char[] masterPassword,
            Predicate<String> firstUseConfirmation
    ) throws IOException, BackupSecretException, SQLException, SecretStoreException, LinuxOperationException {
        PreparedBackupActivation activation = null;
        List<String> warnings = new ArrayList<>();
        try {
            ServerProfile profile = servers.find(targetServerId).orElseThrow(() ->
                    ApplicationServiceException.create(ApplicationServiceFailureType.SERVER_PROFILE_MISSING,
                            "the selected restore target profile is unavailable"));
            activation = backups.prepareForActivation(archive, backupPassword);
            RestoreArchiveModel model = RestoreArchiveModel.load(activation);
            ReentrantLock lock = locks.forServer(profile.id()); lock.lock();
            RestoreCompletion completion;
            try {
                completion = restoreLocked(model, profile, masterPassword,
                        java.util.Objects.requireNonNull(firstUseConfirmation, "firstUseConfirmation"), warnings);
            } finally {
                lock.unlock();
            }
            activation.close();
            try { backups.discard(activation.localCandidate()); }
            catch (IOException exception) { warnings.add("backup.warning.localCandidateCleanup"); }
            return new ManagedRestoreOutcome(profile.id(), completion.restore(), completion.controlState(),
                    completion.localFailure(), warnings);
        } catch (IOException | BackupSecretException | SQLException | SecretStoreException
                 | LinuxOperationException | RuntimeException exception) {
            if (activation != null) {
                activation.close();
                try { backups.discard(activation.localCandidate()); }
                catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            }
            throw exception;
        } finally {
            clear(backupPassword); clear(masterPassword);
        }
    }

    /**
     * Performs the exact read-only target preflight without staging configuration, secrets, files or databases. / 执行精确只读目标前置检查且不暂存配置、秘密、文件或数据库。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param targetServerId target server id / 目标服务器标识
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return constructed or resolved managed restore preflight outcome / 构造或解析得到的受管恢复预检结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedRestorePreflightOutcome preflightUsingSavedProfile(
            Path archive,
            String targetServerId,
            char[] backupPassword,
            char[] masterPassword,
            Predicate<String> firstUseConfirmation
    ) throws IOException, BackupSecretException, SQLException, SecretStoreException, LinuxOperationException {
        PreparedBackupActivation activation = null;
        try {
            ServerProfile profile = servers.find(targetServerId).orElseThrow(() ->
                    ApplicationServiceException.create(ApplicationServiceFailureType.SERVER_PROFILE_MISSING,
                            "the selected restore target profile is unavailable"));
            activation = backups.prepareForActivation(archive, backupPassword);
            RestoreArchiveModel model = RestoreArchiveModel.load(activation);
            ReentrantLock lock = locks.forServer(profile.id()); lock.lock();
            try (SecretStore store = servers.secrets().open(profile.credentialMode(), masterPassword)) {
                SshCredential.Password credential = servers.loadPassword(profile, store);
                try (DeploymentRemoteSession session = gateway.connect(profile.endpoint(), credential,
                        servers.hostKeyVerifier(profile,
                                java.util.Objects.requireNonNull(firstUseConfirmation, "firstUseConfirmation")))) {
                    credential.clear();
                    var activationEvidence = session.restoreActivation().inspectRestoreActivation(
                            model.activation().validation().manifest().applicationId(),
                            model.activation().validation().verifiedBytes());
                    boolean existingOwned = recorder.existingOwnedTarget(model, profile.id());
                    var target = targetEvaluator.evaluate(model.activation().validation().manifest(), model.database().isPresent(), profile.id(), session.collectCapabilities(),
                            session.collectDeploymentCapabilities(), activationEvidence, existingOwned);
                    BackupRestorePlan plan = plan(model, target);
                    new BackupRestorePreflight().verify(plan);
                    List<String> evidence = new ArrayList<>(target.evidence());
                    evidence.add("no configuration, secret, release, file or database restore input was staged");
                    return new ManagedRestorePreflightOutcome(profile.id(),
                            model.activation().validation().manifest().applicationId(),
                            model.activation().validation().archiveSha256(), target.availableBytes(), evidence);
                } finally {
                    credential.clear();
                }
            } finally {
                lock.unlock();
            }
        } finally {
            if (activation != null) {
                activation.close();
                backups.discard(activation.localCandidate());
            }
            clear(backupPassword); clear(masterPassword);
        }
    }

    /**
     * Performs preflight and isolated restore under the selected server lock, then records verified restored state.
     * <p>在所选服务器锁内执行预检及隔离恢复，随后记录已验证恢复状态。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @param warnings warnings / 警告集合
     * @return constructed or resolved restore completion / 构造或解析得到的恢复完成
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private RestoreCompletion restoreLocked(
            RestoreArchiveModel model,
            ServerProfile profile,
            char[] masterPassword,
            Predicate<String> firstUseConfirmation,
            List<String> warnings
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException {
        try (SecretStore store = servers.secrets().open(profile.credentialMode(), masterPassword)) {
            SshCredential.Password credential = servers.loadPassword(profile, store);
            try (DeploymentRemoteSession session = gateway.connect(profile.endpoint(), credential,
                    servers.hostKeyVerifier(profile, firstUseConfirmation))) {
                credential.clear();
                ServerIdentity targetIdentity = servers.findTrusted(profile.id()).orElseThrow(() ->
                        ApplicationServiceException.create(ApplicationServiceFailureType.SERVER_PROFILE_MISSING,
                                "the accepted target host key identity was not persisted"));
                DatabaseOperationPort databaseOperations = new LinuxDatabaseOperationPort(session.databaseOperations());
                var activationEvidence = session.restoreActivation().inspectRestoreActivation(
                        model.activation().validation().manifest().applicationId(),
                        model.activation().validation().verifiedBytes());
                boolean existingOwned = recorder.existingOwnedTarget(model, profile.id());
                var target = targetEvaluator.evaluate(model.activation().validation().manifest(), model.database().isPresent(), profile.id(), session.collectCapabilities(),
                        session.collectDeploymentCapabilities(), activationEvidence, existingOwned);
                BackupRestorePlan plan = plan(model, target);
                new BackupRestorePreflight().verify(plan);
                Map<String, DeploymentInputManifest> inputs = stageInputs(model, targetIdentity, session);
                boolean artifactStaged = stageDatabase(model, databaseOperations);
                BackupRestoreResult result;
                try {
                    var candidates = new LinuxRestoreCandidateAdapter(session,
                            new ManagedRestoreDeploymentPort(session.restoreActivation()), inputs);
                    result = new BackupRestoreCoordinator(new BackupRestorePreflight(), candidates,
                            DatabaseAdapterRegistry.defaults(databaseOperations)).restore(plan);
                } finally {
                    if (artifactStaged) {
                        try { databaseOperations.discardArtifact(model.database().orElseThrow().artifact()); }
                        catch (IOException exception) { warnings.add("backup.warning.remoteDatabaseCleanup"); }
                    }
                }
                return adopt(model, profile, targetIdentity, masterPassword, result);
            } finally {
                credential.clear();
            }
        }
    }

    /**
     * Builds backup restore plan from the supplied plan inputs.
     * <p>根据所提供计划输入构建备份恢复计划。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @return backup restore plan from the supplied plan inputs / 根据所提供计划输入构建备份恢复计划
     */
    private static BackupRestorePlan plan(RestoreArchiveModel model,
                                          gold.debug.windowstolinux.shared.backup.restore.RestoreTargetProfile target) {
        return new BackupRestorePlan(model.activation().validation(),
                model.activation().restoreCandidate(), model.activation().restoreCandidate().root().getParent(),
                model.activation().localCandidate().candidateRoot().getFileName().toString(),
                RestoreMaterialKind.BINARY_RELEASE, target,
                model.database().map(RestoreArchiveModel.DatabaseMaterial::request));
    }

    /**
     * Builds restore completion from the supplied adopt inputs.
     * <p>根据所提供接管输入构建恢复完成。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param targetIdentity target identity / 目标身份
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @return restore completion from the supplied adopt inputs / 根据所提供接管输入构建恢复完成
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private RestoreCompletion adopt(
            RestoreArchiveModel model, ServerProfile profile, ServerIdentity targetIdentity,
            char[] masterPassword, BackupRestoreResult result) throws SQLException {
        if (result.status() != BackupRestoreStatus.SUCCEEDED) {
            return new RestoreCompletion(result, ManagedRestoreControlState.FAILED, Optional.empty());
        }
        if (recorder.sourceGraphRetained(model, profile.id())) {
            return new RestoreCompletion(result, ManagedRestoreControlState.DEFERRED_SOURCE_RETAINED,
                    Optional.empty());
        }
        try {
            secretRegistrar.register(model.activation().validation().manifest().applicationId(),
                    model.activation().secrets(), profile.credentialMode(), copy(masterPassword),
                    Instant.parse(model.activation().validation().manifest().createdAtUtc()));
            recorder.record(model, targetIdentity);
            return new RestoreCompletion(result, ManagedRestoreControlState.UPDATED, Optional.empty());
        } catch (SQLException | SecretStoreException | RuntimeException exception) {
            FailureDescriptor failure = FailureDescriptor.create(ApplicationServiceFailureType.RESTORE_RECORD_SAVE_FAILED,
                    result.operationIdentity(), "remote restore succeeded but exact local control-plane adoption failed");
            return new RestoreCompletion(result, ManagedRestoreControlState.FAILED, Optional.of(failure));
        }
    }

    /**
     * Stages reviewed non-secret deployment input fields.
     * <p>暂存已审阅的非秘密部署输入字段。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static Map<String, DeploymentInputManifest> stageInputs(
            RestoreArchiveModel model, ServerIdentity target, DeploymentRemoteSession session)
            throws LinuxOperationException {
        LinkedHashMap<String, DeploymentInputManifest> inputs = new LinkedHashMap<>();
        for (var component : model.activation().validation().manifest().inventory().components()) {
            var document = model.configurations().get(component.componentId());
            List<SecretReference> expected = component.secretReferences().orElseThrow();
            List<ResolvedSecretRevision> secrets = model.activation().secrets().stream()
                    .filter(revision -> expected.contains(revision.reference())).toList();
            if (secrets.size() != expected.size()) throw new IllegalStateException("component secret revisions differ");
            ManagedApplication application = ManagedApplication.forManaged(component.managedApplicationId(), target,
                    component.ownershipManifestSha256());
            inputs.put(component.componentId(), DeploymentInputMapper.stage(session,
                    application, document.configuration(), secrets));
        }
        return Map.copyOf(inputs);
    }

    /**
     * Stages reviewed database identity or database operation boundary.
     * <p>暂存已审阅数据库身份或数据库操作边界。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param operations operations / 操作集合
     * @return true when stages reviewed database identity or database operation boundary, false otherwise / 暂存已审阅数据库身份或数据库操作边界时为 true，否则为 false
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static boolean stageDatabase(RestoreArchiveModel model, DatabaseOperationPort operations)
            throws IOException {
        if (model.database().isEmpty()) return false;
        var database = model.database().orElseThrow();
        if (!database.localArtifact().startsWith(model.activation().restoreCandidate().root())
                || !Files.isRegularFile(database.localArtifact(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.size(database.localArtifact()) != database.artifact().byteCount()) {
            throw BackupException.create(gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType.INTEGRITY_FAILED,
                    "the extracted database artifact changed before target staging");
        }
        try (InputStream input = Files.newInputStream(database.localArtifact())) {
            operations.stageArtifact(database.artifact(), input);
        }
        return true;
    }

    /**
     * Copies char.
     * <p>复制char。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    private static char[] copy(char[] value) { return value == null ? null : value.clone(); }
    /**
     * Clears retained credential material after its scoped use.
     * <p>在限定作用域使用结束后清空保留的凭据素材。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }
    /**
     * Combines the restore execution result with its desktop recording outcome.
     * <p>组合恢复执行结果及其桌面记录结果。
     *
     * @param restore restore / 恢复
     * @param controlState control state / 控件状态
     * @param localFailure local failure / 本地失败
     */
    private record RestoreCompletion(BackupRestoreResult restore, ManagedRestoreControlState controlState,
                                     Optional<FailureDescriptor> localFailure) { }
}
