package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ConfigurationSnapshotRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.db.persistence.serialization.DeploymentRuntimePersistenceCodec;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.secret.crypto.BackupSecretCryptoService;
import gold.debug.windowstolinux.app.secret.crypto.BackupSecretException;
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
import gold.debug.windowstolinux.shared.backup.execution.collection.ManagedArtifactEvidence;
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

/** Creates one complete product backup from exact persisted review evidence and bounded remote protocols. / 从精确持久化审阅证据及有界远端协议创建完整产品备份。 */
public final class RemoteBackupCreationUseCase {
    private static final long MAXIMUM_REMOTE_ARTIFACT_BYTES = 4L * 1024 * 1024 * 1024;
    private final ManagedApplicationGraphRepository graphs;
    private final ManagedApplicationRepository applications;
    private final ConfigurationSnapshotRepository configurations;
    private final ApplicationSecretRepository secretMetadata;
    private final ManagedBackupInputUseCase inputAssessment;
    private final DeploymentLinuxGateway gateway;
    private final ServerUseCaseFacade servers;
    private final ServerOperationLockRegistry locks;
    private final WindowsBackupMaterialWorkspace materials;
    private final BackupArchiveCreationUseCase archives;
    private final ManagedArtifactValidator artifactValidator;
    private final BackupConfigurationCodec configurationCodec = new BackupConfigurationCodec();
    private final DeploymentRuntimePersistenceCodec runtimeCodec = new DeploymentRuntimePersistenceCodec();
    private final BackupSecretCryptoService backupSecrets = new BackupSecretCryptoService();
    private final SecureRandom random = new SecureRandom();

    /** Creates the complete backup orchestrator under the sole run-mode-derived work directory. / 在唯一由运行模式派生的工作目录下创建完整备份编排器。 */
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
        this.artifactValidator = new ManagedArtifactValidator(BackupArchivePolicy.defaults());
    }

    /** Creates, validates, and atomically publishes one complete backup while restoring the original run state. / 创建、校验并原子发布完整备份，同时恢复原运行状态。 */
    public CreatedBackupArchive createUsingSavedProfile(
            String applicationId,
            Path destination,
            char[] backupPassword,
            char[] masterPassword,
            Predicate<String> firstUseConfirmation
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException {
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
                    masterPassword, firstUseConfirmation);
        } finally {
            clear(backupPassword); clear(masterPassword);
        }
    }

    /** Creates, validates, and atomically publishes one complete backup with an explicit saved profile. / 使用显式已保存资料创建、校验并原子发布完整备份。 */
    public CreatedBackupArchive create(
            String applicationId,
            Path destination,
            char[] backupPassword,
            ServerProfile profile,
            CredentialStorageMode mode,
            char[] masterPassword,
            Predicate<String> firstUseConfirmation
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException {
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
                        firstUseConfirmation);
            } finally {
                lock.unlock();
            }
        } finally {
            clear(backupPassword);
            clear(masterPassword);
        }
    }

    private CreatedBackupArchive createLocked(
            BackupContext context,
            Path destination,
            char[] backupPassword,
            ServerProfile profile,
            CredentialStorageMode mode,
            char[] masterPassword,
        Predicate<String> firstUseConfirmation
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
                    CollectedRemoteBackup remote = collectRemote(context, attempt, session);
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

    private CollectedRemoteBackup collectRemote(
            BackupContext context, WindowsBackupMaterialAttempt attempt, DeploymentRemoteSession session)
            throws LinuxOperationException, IOException {
        ServerCapabilityFacts serverFacts = session.collectCapabilities();
        LinuxCapabilityFacts linuxFacts = session.collectDeploymentCapabilities();
        if (serverFacts.managedHelperProtocolVersion() != ManagedHelperProtocolVersion.CURRENT
                || !serverFacts.tarAvailable() || !serverFacts.nonInteractiveSudoAvailable()) {
            throw BackupException.create(BackupFailureType.DATABASE_PREFLIGHT_FAILED,
                    "the target does not expose the exact managed helper v5 backup capability");
        }
        Map<String, LifecycleObservation> observations = observe(context, session);
        List<String> originallyRunning = context.plan().startOrder().stream()
                .filter(componentId -> observations.get(componentId).runtimeState() == RuntimeState.RUNNING).toList();
        String operationId = operationId();
        List<Material> collected = new ArrayList<>();
        DatabaseBackupArtifact databaseArtifact = null;
        LinuxDatabaseOperationPort databaseOperations = new LinuxDatabaseOperationPort(session.databaseOperations());
        Exception failure = null;
        try {
            for (String componentId : context.plan().stopOrder()) {
                if (!originallyRunning.contains(componentId)) continue;
                ComponentContext component = context.components().get(componentId);
                LifecycleObservation stoppedObservation = session.executeDeploymentLifecycle(
                        component.graph().application(), component.runtime(), LifecycleAction.STOP);
                if (!stoppedObservation.ownershipVerified()
                        || stoppedObservation.runtimeState() != RuntimeState.STOPPED) {
                    throw BackupException.create(BackupFailureType.DATABASE_BACKUP_FAILED,
                            "one managed component did not enter the verified stopped state");
                }
            }
            for (String componentId : context.plan().startOrder()) {
                ComponentContext component = context.components().get(componentId);
                collected.add(downloadManagedArtifact(context, component, attempt, session, operationId,
                        RemoteBackupArtifactKind.RELEASE_TREE, "release",
                        "releases/" + componentId + ".pax", BackupMemberKind.RELEASE));
                for (var binding : component.graph().reviewedResourceBindings().orElseThrow().fileBindings()) {
                    collected.add(downloadManagedArtifact(context, component, attempt, session, operationId,
                            RemoteBackupArtifactKind.FILE_TREE, binding.bindingId(),
                            "data/" + componentId + "/files/" + binding.bindingId() + ".pax",
                            BackupMemberKind.PERSISTENT_CONTENT));
                }
                if (component.runtime() instanceof DeploymentRuntimeSpecification.Container container) {
                    for (var volume : container.volumes()) {
                        collected.add(downloadManagedArtifact(context, component, attempt, session, operationId,
                                RemoteBackupArtifactKind.VOLUME, volume.name(),
                                "data/" + componentId + "/volumes/" + volume.name() + ".pax",
                                BackupMemberKind.PERSISTENT_CONTENT));
                    }
                    {
                        collected.add(downloadManagedArtifact(context, component, attempt, session, operationId,
                                RemoteBackupArtifactKind.OCI_IMAGE, "image",
                                "runtime/" + componentId + ".oci", BackupMemberKind.RUNTIME));
                    }
                }
            }
            Optional<DatabaseContext> database = database(context);
            if (database.isPresent()) {
                DatabaseContext selected = database.orElseThrow();
                DatabaseAdapterRegistry registry = DatabaseAdapterRegistry.defaults(databaseOperations);
                DatabaseBackupAdapter adapter = registry.require(selected.profile().type());
                databaseArtifact = adapter.backup(new DatabaseBackupRequest(
                        selected.component().graph().application().id(), selected.profile(), true, true));
                Path target = materials.member(attempt, "database/" + selected.binding().databaseId() + ".dump");
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    databaseOperations.copyArtifact(databaseArtifact, output);
                }
                Evidence evidence = evidence(target);
                if (evidence.size() != databaseArtifact.byteCount()
                        || !evidence.sha256().equals(databaseArtifact.sha256())) {
                    throw BackupException.create(BackupFailureType.DATABASE_EVIDENCE_INVALID,
                            "the independently downloaded database artifact differs from remote evidence");
                }
                collected.add(new Material(new BackupMember(
                        "database/" + selected.binding().databaseId() + ".dump", evidence.size(), evidence.sha256(),
                        BackupMemberKind.DATABASE), target));
            }
        } catch (Exception exception) {
            failure = exception;
        }
        try {
            restartAndVerify(context, session, originallyRunning);
        } catch (Exception recoveryFailure) {
            if (failure != null) recoveryFailure.addSuppressed(failure);
            throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_RECOVERY_FAILED,
                    "the original running components could not be restored and verified after backup collection",
                    recoveryFailure);
        }
        try {
            if (databaseArtifact != null) databaseOperations.discardArtifact(databaseArtifact);
            RemoteStepResult cleanup = session.backupArtifacts().discardBackupOperation(operationId);
            if (!cleanup.succeeded()) {
                throw BackupException.create(BackupFailureType.CLEANUP_FAILED,
                        "the managed backup operation could not be discarded after collection");
            }
        } catch (Exception cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        }
        if (failure != null) rethrowCollection(failure);
        return new CollectedRemoteBackup(List.copyOf(collected), databaseArtifact == null
                ? BackupDatabase.none() : databaseArtifact.database(), runtime(linuxFacts, serverFacts, context));
    }

    private Map<String, LifecycleObservation> observe(BackupContext context, DeploymentRemoteSession session)
            throws LinuxOperationException, BackupException {
        LinkedHashMap<String, LifecycleObservation> observations = new LinkedHashMap<>();
        for (String componentId : context.plan().startOrder()) {
            ComponentContext component = context.components().get(componentId);
            LifecycleObservation observation = session.observeDeployment(component.graph().application(),
                    component.runtime());
            if (!observation.ownershipVerified()
                    || observation.runtimeState() != RuntimeState.RUNNING
                    && observation.runtimeState() != RuntimeState.STOPPED) {
                throw BackupException.create(BackupFailureType.DATABASE_PREFLIGHT_FAILED,
                        "every component requires one authoritative running or stopped observation before backup");
            }
            observations.put(componentId, observation);
        }
        return Map.copyOf(observations);
    }

    private void restartAndVerify(
            BackupContext context, DeploymentRemoteSession session, List<String> originallyRunning)
            throws LinuxOperationException {
        for (String componentId : context.plan().startOrder()) {
            if (!originallyRunning.contains(componentId)) continue;
            ComponentContext component = context.components().get(componentId);
            LifecycleObservation started = session.executeDeploymentLifecycle(
                    component.graph().application(), component.runtime(), LifecycleAction.START);
            if (!started.ownershipVerified() || started.runtimeState() != RuntimeState.RUNNING
                    || !session.checkDeploymentHealth(component.graph().application(), component.runtime(),
                    component.runtime().healthCheck()).healthy()) {
                throw LinuxOperationException.create(
                        gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.POST_START_HEALTH_FAILED,
                        "a previously running component did not recover after the backup stop window");
            }
        }
        if (!originallyRunning.isEmpty()) {
            ComponentContext owner = context.components().get(context.graph().healthComponentId());
            if (!session.checkDeploymentHealth(owner.graph().application(), owner.runtime(),
                    context.graph().applicationHealthCheck().orElseThrow()).healthy()) {
                throw LinuxOperationException.create(
                        gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.POST_START_HEALTH_FAILED,
                        "the whole-application health gate did not recover after backup collection");
            }
        }
    }

    private Material downloadManagedArtifact(
            BackupContext context,
            ComponentContext component,
            WindowsBackupMaterialAttempt attempt,
            DeploymentRemoteSession session,
            String operationId,
            RemoteBackupArtifactKind kind,
            String resourceId,
            String memberPath,
            BackupMemberKind memberKind
    ) throws LinuxOperationException, IOException {
        RemoteBackupArtifact remote = session.backupArtifacts().createBackupArtifact(new RemoteBackupArtifactRequest(operationId,
                context.graph().applicationId(), component.graph().componentId(), component.graph().application(),
                component.release().releaseSha256(), kind, resourceId, MAXIMUM_REMOTE_ARTIFACT_BYTES));
        Path local = materials.member(attempt, memberPath);
        try (OutputStream output = Files.newOutputStream(local, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            session.backupArtifacts().copyBackupArtifact(remote, output);
        }
        ManagedArtifactEvidence evidence = kind == RemoteBackupArtifactKind.OCI_IMAGE
                ? artifactValidator.validateOci(local) : artifactValidator.validatePax(local);
        if (evidence.byteCount() != remote.byteCount() || !evidence.sha256().equals(remote.sha256())) {
            throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                    "local managed artifact validation differs from helper evidence");
        }
        return new Material(new BackupMember(memberPath, evidence.byteCount(), evidence.sha256(), memberKind), local);
    }

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
                "/var/lib/windowstolinux/apps/" + context.graph().applicationId(),
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

    private static void validateDatabaseScope(BackupContext context) {
        List<DatabaseContext> databases = databases(context);
        if (databases.size() > 1) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                    "schema v4 supports exactly zero or one reviewed database artifact per application");
        }
        if (!databases.isEmpty() && databases.getFirst().binding().connection() instanceof ManagedDatabaseConnection.Sqlite) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                    "SQLite backup requires an explicitly reviewed managed application-relative physical mapping");
        }
        if (!databases.isEmpty()) {
            DatabaseContext database = databases.getFirst();
            ManagedDatabaseConnection.Server server = (ManagedDatabaseConnection.Server) database.binding().connection();
            if (!database.component().secretReferences().contains(server.passwordReference())) {
                throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE,
                        "the reviewed database password revision is not bound to the component release");
            }
        }
    }

    private static List<DatabaseContext> databases(BackupContext context) {
        List<DatabaseContext> databases = new ArrayList<>();
        context.components().values().forEach(component -> component.graph().reviewedResourceBindings().orElseThrow()
                .databaseBindings().orElseThrow().forEach(binding -> databases.add(
                        new DatabaseContext(component, binding, BackupDatabaseProfileMapper.profile(binding.connection())))));
        return List.copyOf(databases);
    }

    private static Optional<DatabaseContext> database(BackupContext context) {
        List<DatabaseContext> databases = databases(context);
        return databases.isEmpty() ? Optional.empty() : Optional.of(databases.getFirst());
    }



    private BackupRuntime runtime(
            LinuxCapabilityFacts linux, ServerCapabilityFacts server, BackupContext context) {
        Set<String> capabilities = new LinkedHashSet<>();
        if (linux.systemdAvailable()) capabilities.add("systemd");
        if (linux.dockerOperational()) capabilities.add("docker");
        if (linux.podmanOperational()) capabilities.add("podman");
        capabilities.add("managed-helper-v" + server.managedHelperProtocolVersion());
        boolean container = context.components().values().stream()
                .anyMatch(component -> component.runtime() instanceof DeploymentRuntimeSpecification.Container);
        boolean ordinary = context.components().values().stream()
                .anyMatch(component -> !(component.runtime() instanceof DeploymentRuntimeSpecification.Container));
        String runtimeKind = container && ordinary ? "mixed" : container ? "container" : "systemd";
        return new BackupRuntime(linux.distro().name().toLowerCase(java.util.Locale.ROOT), linux.version(),
                runtimeKind, "managed-helper-" + server.managedHelperProtocolVersion(), linux.architecture(),
                capabilities.stream().sorted().toList());
    }

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

    private static List<BackupArchiveContent> contents(List<Material> materials) {
        return materials.stream().sorted(Comparator.comparing(value -> value.member().path()))
                .map(material -> new BackupArchiveContent(material.member(), () -> Files.newInputStream(material.path())))
                .toList();
    }

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

    private String operationId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return "backup-" + HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void rethrowCollection(Exception failure) throws LinuxOperationException, IOException {
        if (failure instanceof LinuxOperationException linux) throw linux;
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new IOException("managed remote backup collection failed", failure);
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    private record BackupContext(
            ManagedApplicationGraph graph,
            MultiComponentDeploymentPlan plan,
            Map<String, ComponentContext> components
    ) { }

    private record ComponentContext(
            ManagedApplicationGraph.Component graph,
            CurrentRelease release,
            gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences,
            DeploymentRuntimeSpecification runtime
    ) { }

    private record DatabaseContext(
            ComponentContext component,
            ManagedDatabaseBinding binding,
            DatabaseConnectionProfile profile
    ) { }

    private record Material(BackupMember member, Path path) { }
    private record Evidence(long size, String sha256) { }
    private record CollectedRemoteBackup(
            List<Material> materials,
            BackupDatabase database,
            BackupRuntime runtime
    ) { }
}
