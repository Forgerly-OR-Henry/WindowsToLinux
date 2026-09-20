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

/** Concrete two-server product operations behind the portable offline migration transaction. / 可移植离线迁移事务背后的具体双服务器产品操作。 */
final class ManagedOfflineMigrationPort implements OfflineMigrationPort, AutoCloseable {
    private final String applicationId;
    private final ServerProfile source;
    private final String targetServerId;
    private final RemoteBackupCreationUseCase backups;
    private final ManagedRestoreUseCase restores;
    private final MultiComponentLifecycleUseCase lifecycle;
    private final ManagedMultiComponentApplication managed;
    private final CreatedBackupArchive initial;
    private final Path finalDestination;
    private final char[] backupPassword;
    private final char[] masterPassword;
    private final Predicate<String> confirmation;
    private CreatedBackupArchive finalArchive;
    private ManagedRestoreOutcome restoreOutcome;
    private final String admission = "backup-" + java.util.UUID.randomUUID().toString().replace("-", "");

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

    Optional<Path> finalArchive() {
        return Optional.ofNullable(finalArchive).map(CreatedBackupArchive::archive);
    }

    @Override public void close() { clear(backupPassword); clear(masterPassword); }

    private Set<String> componentIds() {
        return managed.components().stream().map(value -> value.componentId()).collect(Collectors.toUnmodifiableSet());
    }

    private static BackupException failure(BackupFailureType type, String diagnostic, Exception exception) {
        return BackupException.create(type, diagnostic, exception);
    }

    private static char[] copy(char[] value) {
        return Arrays.copyOf(Objects.requireNonNull(value, "password"), value.length);
    }

    private static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }
}
