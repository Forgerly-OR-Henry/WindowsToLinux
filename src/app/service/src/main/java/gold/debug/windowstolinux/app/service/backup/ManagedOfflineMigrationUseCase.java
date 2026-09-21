package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException;
import gold.debug.windowstolinux.app.service.deployment.MultiComponentLifecycleUseCase;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.app.windows.workspace.WindowsBackupMaterialAttempt;
import gold.debug.windowstolinux.app.windows.workspace.WindowsBackupMaterialWorkspace;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.shared.backup.contract.spi.OfflineMigrationRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.OfflineMigrationPort;
import gold.debug.windowstolinux.shared.backup.execution.migration.OfflineMigrationCoordinator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Creates a complete initial backup, runs the offline transaction and retains the exact final backup. / 创建完整初始备份、执行离线事务并保留精确最终备份。
 */
public final class ManagedOfflineMigrationUseCase {
    /**
     * Backups.
     * <p>备份集合。
     */
    private final RemoteBackupCreationUseCase backups;
    /**
     * Restores.
     * <p>恢复集合。
     */
    private final ManagedRestoreUseCase restores;
    /**
     * Lifecycle.
     * <p>生命周期。
     */
    private final MultiComponentLifecycleUseCase lifecycle;
    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;
    /**
     * Platform-owned work area with enforced path boundaries.
     * <p>具有路径边界约束的平台工作区。
     */
    private final WindowsBackupMaterialWorkspace workspace;
    /**
     * Backups directory.
     * <p>备份集合目录。
     */
    private final Path backupsDirectory;

    /**
     * Creates the bounded product migration composition. / 创建受限的产品迁移组合。
     *
     * @param backups backups / 备份集合
     * @param restores restores / 恢复集合
     * @param lifecycle lifecycle / 生命周期
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param workDirectory work directory / 工作目录
     * @param backupsDirectory backups directory / 备份集合目录
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedOfflineMigrationUseCase(
            RemoteBackupCreationUseCase backups,
            ManagedRestoreUseCase restores,
            MultiComponentLifecycleUseCase lifecycle,
            ServerUseCaseFacade servers,
            Path workDirectory,
            Path backupsDirectory
    ) {
        this.backups = Objects.requireNonNull(backups, "backups");
        this.restores = Objects.requireNonNull(restores, "restores");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.workspace = new WindowsBackupMaterialWorkspace(workDirectory);
        this.backupsDirectory = Objects.requireNonNull(backupsDirectory, "backupsDirectory").toAbsolutePath().normalize();
    }

    /**
     * Prepares one migration up to verified target readiness and the explicit manual traffic switch boundary. / 将一次迁移准备到目标已验证就绪及显式人工切流边界。
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
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    public ManagedOfflineMigrationOutcome prepare(
            String applicationId,
            String targetServerId,
            char[] backupPassword,
            char[] masterPassword,
            boolean stopWindowApproved,
            Predicate<String> firstUseConfirmation
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException {
        WindowsBackupMaterialAttempt attempt = null;
        ManagedOfflineMigrationPort port = null;
        List<String> warnings = new ArrayList<>();
        try {
            var managed = lifecycle.findManagedApplication(applicationId).orElseThrow(() ->
                    ApplicationServiceException.create(ApplicationServiceFailureType.APPLICATION_NOT_MANAGED,
                            "the selected application has no durable whole-application lifecycle graph"));
            String sourceServerId = managed.components().getFirst().application().server().id();
            ServerProfile source = servers.find(sourceServerId).orElseThrow(() ->
                    ApplicationServiceException.create(ApplicationServiceFailureType.SERVER_PROFILE_MISSING,
                            "the source server profile is unavailable"));
            if (servers.find(targetServerId).isEmpty()) {
                throw ApplicationServiceException.create(ApplicationServiceFailureType.SERVER_PROFILE_MISSING,
                        "the target server profile is unavailable");
            }
            String migrationId = "migration-" + UUID.randomUUID().toString().replace("-", "");
            if (!stopWindowApproved) {
                OfflineMigrationRequest rejected = new OfflineMigrationRequest(migrationId, applicationId,
                        sourceServerId, targetServerId, 1, false);
                return new ManagedOfflineMigrationOutcome(
                        new OfflineMigrationCoordinator(RejectedMigrationState.INSTANCE).prepare(rejected),
                        java.util.Optional.empty(), List.of());
            }
            attempt = workspace.createAttempt();
            Path initialPath = workspace.member(attempt, "initial.wtlbackup");
            CreatedBackupArchive initial = backups.createUsingSavedProfile(applicationId, initialPath,
                    copy(backupPassword), copy(masterPassword), firstUseConfirmation);
            Files.createDirectories(backupsDirectory);
            Path finalPath = backupsDirectory.resolve(applicationId + "-" + migrationId + ".wtlbackup");
            OfflineMigrationRequest request = new OfflineMigrationRequest(migrationId, applicationId,
                    sourceServerId, targetServerId, Files.size(initial.archive()), stopWindowApproved);
            port = new ManagedOfflineMigrationPort(applicationId, source, targetServerId, backups, restores,
                    lifecycle, managed, initial, finalPath, backupPassword, masterPassword, firstUseConfirmation);
            var result = new OfflineMigrationCoordinator(port).prepare(request);
            var retainedFinalArchive = port.finalArchive();
            port.close(); port = null;
            try { workspace.discard(attempt); }
            catch (WindowsWorkspaceException exception) {
                warnings.add("backup.warning.initialArchiveCleanup");
            }
            attempt = null;
            return new ManagedOfflineMigrationOutcome(result, retainedFinalArchive, warnings);
        } catch (SQLException | SecretStoreException | LinuxOperationException | IOException
                 | BackupSecretException | RuntimeException exception) {
            if (port != null) port.close();
            if (attempt != null) {
                try { workspace.discard(attempt); }
                catch (WindowsWorkspaceException cleanup) { exception.addSuppressed(cleanup); }
            }
            throw exception;
        } finally {
            clear(backupPassword); clear(masterPassword);
        }
    }

    /**
     * Copies char.
     * <p>复制char。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static char[] copy(char[] value) {
        return Arrays.copyOf(Objects.requireNonNull(value, "password"), value.length);
    }

    /**
     * Clears retained credential material after its scoped use.
     * <p>在限定作用域使用结束后清空保留的凭据素材。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }

    /**
     * Must remain unreachable because the coordinator rejects missing approval before calling its port. / 协调器必须在调用端口前拒绝缺失批准，因此此端口不可到达。
     */
    private enum RejectedMigrationState implements OfflineMigrationPort {
        /**
         * INSTANCE classification within rejected migration state.
         * <p>已拒绝迁移状态中的实例分类。
         */
        INSTANCE;

        /**
         * Verifies the target prerequisites before permitting migration changes.
         * <p>在允许迁移变更前验证目标前提条件。
         *
         * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
         * @return constructed or resolved target preflight evidence / 构造或解析得到的目标预检证据
         */
        @Override public TargetPreflightEvidence preflightTarget(OfflineMigrationRequest request) { throw unreachable(); }
        /**
         * Copies the initial reviewed source state before the final stop window.
         * <p>在最终停机窗口前复制初始已审阅源状态。
         *
         * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
         * @return constructed or resolved sync evidence / 构造或解析得到的同步证据
         */
        @Override public SyncEvidence initialSync(OfflineMigrationRequest request) { throw unreachable(); }
        /**
         * Stops source writes.
         * <p>停止源码写入集合。
         *
         * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
         * @return constructed or resolved source quiesce evidence / 构造或解析得到的源码停写证据
         */
        @Override public SourceQuiesceEvidence stopSourceWrites(OfflineMigrationRequest request) { throw unreachable(); }
        /**
         * Copies the final stopped-writer source state used for target activation.
         * <p>复制供目标激活使用的最终停写源状态。
         *
         * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
         * @param initial initial / 初始
         * @param quiesced quiesced / 已停写
         * @return constructed or resolved sync evidence / 构造或解析得到的同步证据
         */
        @Override public SyncEvidence finalSync(OfflineMigrationRequest request, SyncEvidence initial,
                                                 SourceQuiesceEvidence quiesced) { throw unreachable(); }
        /**
         * Restores and verify target.
         * <p>恢复与验证目标。
         *
         * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
         * @param finalSync final sync / 最终同步
         * @return constructed or resolved target candidate evidence / 构造或解析得到的目标候选证据
         */
        @Override public TargetCandidateEvidence restoreAndVerifyTarget(
                OfflineMigrationRequest request, SyncEvidence finalSync) { throw unreachable(); }
        /**
         * Discards target candidate.
         * <p>清理目标候选。
         *
         * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
         * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
         */
        @Override public RecoveryEvidence discardTargetCandidate(OfflineMigrationRequest request) { throw unreachable(); }
        /**
         * Recovers source identity or content read by the operation.
         * <p>恢复操作读取的源身份或内容。
         *
         * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
         * @param quiesced quiesced / 已停写
         * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
         */
        @Override public RecoveryEvidence recoverSource(
                OfflineMigrationRequest request, SourceQuiesceEvidence quiesced) { throw unreachable(); }

        /**
         * Builds assertion error from the supplied unreachable inputs.
         * <p>根据所提供不可达输入构建Assertion错误。
         *
         * @return assertion error from the supplied unreachable inputs / 根据所提供不可达输入构建Assertion错误
         */
        private static AssertionError unreachable() {
            return new AssertionError("a rejected migration must not invoke its platform port");
        }
    }
}
