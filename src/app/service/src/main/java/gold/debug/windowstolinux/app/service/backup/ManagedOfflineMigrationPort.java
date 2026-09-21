package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.service.deployment.MultiComponentLifecycleUseCase;
import gold.debug.windowstolinux.app.service.deployment.multi.ManagedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.backup.contract.spi.OfflineMigrationPort;
import gold.debug.windowstolinux.shared.backup.contract.spi.OfflineMigrationRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Concrete two-server product operations behind the portable offline migration transaction. / 可移植离线迁移事务背后的具体双服务器产品操作。
 */
final class ManagedOfflineMigrationPort implements OfflineMigrationPort, AutoCloseable {
    /**
     * Managed application identifier.
     * <p>受管应用标识。
     */
    private final String applicationId;
    /**
     * Source identity or content read by the operation.
     * <p>操作读取的源身份或内容。
     */
    private final ServerProfile source;
    /**
     * Target server id.
     * <p>目标服务器标识。
     */
    private final String targetServerId;
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
     * Managed.
     * <p>受管。
     */
    private final ManagedMultiComponentApplication managed;
    /**
     * Initial.
     * <p>初始。
     */
    private final CreatedBackupArchive initial;
    /**
     * Final destination.
     * <p>最终目的地。
     */
    private final Path finalDestination;
    /**
     * Independent password for backup secret encryption or decryption.
     * <p>备份秘密加密或解密使用的独立密码。
     */
    private final char[] backupPassword;
    /**
     * Master-password buffer used to unlock protected credentials.
     * <p>用于解锁受保护凭据的主密码缓冲区。
     */
    private final char[] masterPassword;
    /**
     * Token or decision binding approval to the exact proposed action.
     * <p>将批准绑定到精确提议动作的令牌或决定。
     */
    private final Predicate<String> confirmation;
    /**
     * Final archive.
     * <p>最终归档。
     */
    private CreatedBackupArchive finalArchive;
    /**
     * Restore outcome.
     * <p>恢复结果。
     */
    private ManagedRestoreOutcome restoreOutcome;
    /**
     * The deterministic admission status.
     * <p>确定性准入状态。
     */
    private final String admission = "backup-" + java.util.UUID.randomUUID().toString().replace("-", "");

    /**
     * Validates and binds the inputs required by managed offline migration port.
     * <p>校验并绑定受管离线迁移端口所需输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param targetServerId target server id / 目标服务器标识
     * @param backups backups / 备份集合
     * @param restores restores / 恢复集合
     * @param lifecycle lifecycle / 生命周期
     * @param managed managed / 受管
     * @param initial initial / 初始
     * @param finalDestination final destination / 最终目的地
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    ManagedOfflineMigrationPort(
            String applicationId,
            ServerProfile source,
            String targetServerId,
            RemoteBackupCreationUseCase backups,
            ManagedRestoreUseCase restores,
            MultiComponentLifecycleUseCase lifecycle,
            ManagedMultiComponentApplication managed,
            CreatedBackupArchive initial,
            Path finalDestination,
            char[] backupPassword,
            char[] masterPassword,
            Predicate<String> confirmation
    ) {
        this.applicationId = Objects.requireNonNull(applicationId, "applicationId");
        this.source = Objects.requireNonNull(source, "source");
        this.targetServerId = Objects.requireNonNull(targetServerId, "targetServerId");
        this.backups = Objects.requireNonNull(backups, "backups");
        this.restores = Objects.requireNonNull(restores, "restores");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.managed = Objects.requireNonNull(managed, "managed");
        this.initial = Objects.requireNonNull(initial, "initial");
        this.finalDestination = Objects.requireNonNull(finalDestination, "finalDestination").toAbsolutePath().normalize();
        this.backupPassword = copy(backupPassword);
        this.masterPassword = copy(masterPassword);
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation");
    }

    /**
     * Verifies the target prerequisites before permitting migration changes.
     * <p>在允许迁移变更前验证目标前提条件。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved target preflight evidence / 构造或解析得到的目标预检证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public TargetPreflightEvidence preflightTarget(OfflineMigrationRequest request) throws BackupException {
        try {
            ManagedRestorePreflightOutcome evidence = restores.preflightUsingSavedProfile(initial.archive(),
                    targetServerId, copy(backupPassword), copy(masterPassword), confirmation);
            return new TargetPreflightEvidence(true, true, true, evidence.availableBytes(), evidence.evidence());
        } catch (Exception exception) {
            throw failure(BackupFailureType.MIGRATION_PREFLIGHT_FAILED,
                    "the target failed the read-only product restore preflight", exception);
        }
    }

    /**
     * Copies the initial reviewed source state before the final stop window.
     * <p>在最终停机窗口前复制初始已审阅源状态。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved sync evidence / 构造或解析得到的同步证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public SyncEvidence initialSync(OfflineMigrationRequest request) throws BackupException {
        try {
            return new SyncEvidence(Files.size(initial.archive()), initial.inspection().archiveSha256(), true, false,
                    List.of("complete initial archive was published and independently verified while source service state was restored"));
        } catch (IOException exception) {
            throw failure(BackupFailureType.MIGRATION_SYNC_FAILED,
                    "the complete initial archive could not be re-read", exception);
        }
    }

    /**
     * Stops source writes.
     * <p>停止源码写入集合。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved source quiesce evidence / 构造或解析得到的源码停写证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    @Override
    public SourceQuiesceEvidence stopSourceWrites(OfflineMigrationRequest request) throws BackupException {
        try {
            backups.taskAdmission(applicationId,admission,true,copy(masterPassword),confirmation);
            var targets=backups.daemonComponents(applicationId);
            var result = lifecycle.executeLifecycleWithStoredPassword(applicationId, targets.isEmpty()?componentIds():targets,
                    targets.isEmpty()?LifecycleAction.REFRESH_STATUS:LifecycleAction.STOP, source, source.credentialMode(), copy(masterPassword));
            if (!result.accepted() || !Set.of(ApplicationRuntimeState.STOPPED,ApplicationRuntimeState.INSTALLED).contains(result.runtimeState())) {
                throw new IllegalStateException("source components did not all enter the authoritative stopped state");
            }
            return new SourceQuiesceEvidence(true, true, admission,
                    List.of("every source component is authoritatively stopped in dependency-safe order"));
        } catch (Exception exception) {
            return new SourceQuiesceEvidence(false, false, admission,
                    List.of("source quiesce was not verified; recover daemon state and task admission before retrying"));
        }
    }

    /**
     * Copies the final stopped-writer source state used for target activation.
     * <p>复制供目标激活使用的最终停写源状态。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param initial initial / 初始
     * @param quiesced quiesced / 已停写
     * @return constructed or resolved sync evidence / 构造或解析得到的同步证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public SyncEvidence finalSync(OfflineMigrationRequest request, SyncEvidence initial,
                                  SourceQuiesceEvidence quiesced) throws BackupException {
        try {
            finalArchive = backups.createUsingSavedProfile(applicationId, finalDestination,
                    copy(backupPassword), copy(masterPassword), confirmation, admission);
            return new SyncEvidence(Files.size(finalArchive.archive()), finalArchive.inspection().archiveSha256(),
                    true, true, List.of(
                    "complete final archive was collected while every source component remained stopped",
                    "final archive was atomically published and independently verified"));
        } catch (Exception exception) {
            throw failure(BackupFailureType.MIGRATION_SYNC_FAILED,
                    "the stopped-write final archive could not be created", exception);
        }
    }

    /**
     * Restores and verify target.
     * <p>恢复与验证目标。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param finalSync final sync / 最终同步
     * @return constructed or resolved target candidate evidence / 构造或解析得到的目标候选证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    @Override
    public TargetCandidateEvidence restoreAndVerifyTarget(
            OfflineMigrationRequest request, SyncEvidence finalSync) throws BackupException {
        try {
            restoreOutcome = restores.restoreUsingSavedProfile(finalArchive.archive(), targetServerId,
                    copy(backupPassword), copy(masterPassword), confirmation);
            if (restoreOutcome.restore().status() != BackupRestoreStatus.SUCCEEDED
                    || restoreOutcome.controlState() == ManagedRestoreControlState.FAILED) {
                throw new IllegalStateException("target restore did not reach verified formal activation and safe local control state");
            }
            return new TargetCandidateEvidence(applicationId + "-" + finalSync.contentSha256().substring(0, 16),
                    true, true, true, List.of(
                    "target components and whole-application formal health passed after candidate commit",
                    "external traffic was not changed and the source deployment remains retained"));
        } catch (Exception exception) {
            throw failure(BackupFailureType.MIGRATION_TARGET_FAILED,
                    "the final archive could not be restored and verified on the target", exception);
        }
    }

    /**
     * Discards target candidate.
     * <p>清理目标候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     */
    @Override
    public RecoveryEvidence discardTargetCandidate(OfflineMigrationRequest request) {
        if (restoreOutcome == null) {
            return new RecoveryEvidence(true, true,
                    List.of("no target restore attempt was entered or its own transaction completed candidate cleanup"));
        }
        if (restoreOutcome.restore().status() == BackupRestoreStatus.FAILED_EXISTING_PRESERVED) {
            return new RecoveryEvidence(true, true,
                    List.of("target restore transaction verified the previous release and removed its candidate"));
        }
        return new RecoveryEvidence(false, false, List.of(
                "target state cannot be automatically discarded after formal activation or unverified recovery"));
    }

    /**
     * Recovers source identity or content read by the operation.
     * <p>恢复操作读取的源身份或内容。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param quiesced quiesced / 已停写
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public RecoveryEvidence recoverSource(OfflineMigrationRequest request, SourceQuiesceEvidence quiesced)
            throws BackupException {
        try {
            var targets=backups.daemonComponents(applicationId);
            var result = lifecycle.executeLifecycleWithStoredPassword(applicationId, targets.isEmpty()?componentIds():targets,
                    targets.isEmpty()?LifecycleAction.REFRESH_STATUS:LifecycleAction.START, source, source.credentialMode(), copy(masterPassword));
            boolean verified = result.accepted() && Set.of(ApplicationRuntimeState.RUNNING,ApplicationRuntimeState.INSTALLED).contains(result.runtimeState());
            if(verified)backups.taskAdmission(applicationId,admission,false,copy(masterPassword),confirmation);
            return new RecoveryEvidence(verified, verified,
                    List.of("source start recovery was executed and every component runtime was observed"));
        } catch (Exception exception) {
            throw failure(BackupFailureType.MIGRATION_RECOVERY_FAILED,
                    "the source could not be restarted and verified", exception);
        }
    }

    /**
     * Returns final archive.
     * <p>返回最终归档。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    Optional<Path> finalArchive() {
        return Optional.ofNullable(finalArchive).map(CreatedBackupArchive::archive);
    }

    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     */
    @Override public void close() { clear(backupPassword); clear(masterPassword); }

    /**
     * Returns affected component identifiers.
     * <p>返回受影响的组件标识符。
     *
     * @return affected component identifiers / 受影响的组件标识符
     */
    private Set<String> componentIds() {
        return managed.components().stream().map(value -> value.componentId()).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static BackupException failure(BackupFailureType type, String diagnostic, Exception exception) {
        return BackupException.create(type, diagnostic, exception);
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
}
