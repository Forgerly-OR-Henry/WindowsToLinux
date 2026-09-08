package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.crypto.BackupSecretException;
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

/** Product restore composition over local validation, SSH, deploy, database, recovery and persistence. / 组合本地校验、SSH、部署、数据库、恢复及持久化的产品恢复用例。 */
public final class ManagedRestoreUseCase {
    private final BackupUseCase backups;
    private final DeploymentLinuxGateway gateway;
    private final ServerUseCaseFacade servers;
    private final ServerOperationLockRegistry locks;
    private final RestoredApplicationRecorder recorder;
    private final RestoredSecretRegistrar secretRegistrar;
    private final RestoreTargetEvaluator targetEvaluator = new RestoreTargetEvaluator();

    /** Creates the production restore composition without transport-specific dependencies. / 创建不含传输实现依赖的生产恢复组合。 */
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

    /** Restores one exact archive to a selected saved target profile. / 将一个精确归档恢复到选定的已保存目标资料。 */
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

    /** Performs the exact read-only target preflight without staging configuration, secrets, files or databases. / 执行精确只读目标前置检查且不暂存配置、秘密、文件或数据库。 */
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
                    var activationEvidence = session.inspectRestoreActivation(
                            model.activation().validation().manifest().applicationId(),
                            model.activation().validation().verifiedBytes());
                    boolean existingOwned = recorder.existingOwnedTarget(model, profile.id());
                    var target = targetEvaluator.evaluate(model, profile.id(), session.collectCapabilities(),
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
                DatabaseOperationPort databaseOperations = new LinuxDatabaseOperationPort(session);
                var activationEvidence = session.inspectRestoreActivation(
                        model.activation().validation().manifest().applicationId(),
                        model.activation().validation().verifiedBytes());
                boolean existingOwned = recorder.existingOwnedTarget(model, profile.id());
                var target = targetEvaluator.evaluate(model, profile.id(), session.collectCapabilities(),
                        session.collectDeploymentCapabilities(), activationEvidence, existingOwned);
                BackupRestorePlan plan = plan(model, target);
                new BackupRestorePreflight().verify(plan);
                Map<String, DeploymentInputManifest> inputs = stageInputs(model, targetIdentity, session);
                boolean artifactStaged = stageDatabase(model, databaseOperations);
                BackupRestoreResult result;
                try {
                    var candidates = new LinuxRestoreCandidateAdapter(session,
                            new ManagedRestoreDeploymentPort(session), inputs);
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

    private static BackupRestorePlan plan(RestoreArchiveModel model,
                                          gold.debug.windowstolinux.shared.backup.restore.RestoreTargetProfile target) {
        return new BackupRestorePlan(model.activation().validation(),
                model.activation().restoreCandidate(), model.activation().restoreCandidate().root().getParent(),
                model.activation().localCandidate().candidateRoot().getFileName().toString(),
                RestoreMaterialKind.BINARY_RELEASE, target,
                model.database().map(RestoreArchiveModel.DatabaseMaterial::request));
    }

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
            inputs.put(component.componentId(), session.stageDeploymentInputs(
                    application, document.configuration(), secrets));
        }
        return Map.copyOf(inputs);
    }

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

    private static char[] copy(char[] value) { return value == null ? null : value.clone(); }
    private static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }
    private record RestoreCompletion(BackupRestoreResult restore, ManagedRestoreControlState controlState,
                                     Optional<FailureDescriptor> localFailure) { }
}
