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

/** Creates a complete initial backup, runs the offline transaction and retains the exact final backup. / 创建完整初始备份、执行离线事务并保留精确最终备份。 */
public final class ManagedOfflineMigrationUseCase {
    private final RemoteBackupCreationUseCase backups;
    private final ManagedRestoreUseCase restores;
    private final MultiComponentLifecycleUseCase lifecycle;
    private final ServerUseCaseFacade servers;
    private final WindowsBackupMaterialWorkspace workspace;
    private final Path backupsDirectory;

    /** Creates the bounded product migration composition. / 创建受限的产品迁移组合。 */
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

    /** Prepares one migration up to verified target readiness and the explicit manual traffic switch boundary. / 将一次迁移准备到目标已验证就绪及显式人工切流边界。 */
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

    private static char[] copy(char[] value) {
        return Arrays.copyOf(Objects.requireNonNull(value, "password"), value.length);
    }

    private static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }

    /** Must remain unreachable because the coordinator rejects missing approval before calling its port. / 协调器必须在调用端口前拒绝缺失批准，因此此端口不可到达。 */
    private enum RejectedMigrationState implements OfflineMigrationPort {
        INSTANCE;

        @Override public TargetPreflightEvidence preflightTarget(OfflineMigrationRequest request) { throw unreachable(); }
        @Override public SyncEvidence initialSync(OfflineMigrationRequest request) { throw unreachable(); }
        @Override public SourceQuiesceEvidence stopSourceWrites(OfflineMigrationRequest request) { throw unreachable(); }
        @Override public SyncEvidence finalSync(OfflineMigrationRequest request, SyncEvidence initial,
                                                 SourceQuiesceEvidence quiesced) { throw unreachable(); }
        @Override public TargetCandidateEvidence restoreAndVerifyTarget(
                OfflineMigrationRequest request, SyncEvidence finalSync) { throw unreachable(); }
        @Override public RecoveryEvidence discardTargetCandidate(OfflineMigrationRequest request) { throw unreachable(); }
        @Override public RecoveryEvidence recoverSource(
                OfflineMigrationRequest request, SourceQuiesceEvidence quiesced) { throw unreachable(); }

        private static AssertionError unreachable() {
            return new AssertionError("a rejected migration must not invoke its platform port");
        }
    }
}
