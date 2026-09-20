package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.app.secret.Argon2AesSecretStore;
import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifact;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactRequest;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteBackupCreationUseCaseTest {
    @TempDir
    Path temporary;

    @Test
    void createsCompleteArchiveAndRestoresTheOriginalRunningState() throws Exception {
        byte[] pax = pax();
        List<String> log = new ArrayList<>();
        RuntimeState[] state = {RuntimeState.RUNNING};
        DeploymentRemoteSession session = session(pax, log, state, false);
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> session;
        Path data = temporary.resolve("data");
        Path work = temporary.resolve("work");
        Path destination = temporary.resolve("complete.wtl-backup");
        char[] master = "correct master password".toCharArray();
        char[] backupPassword = "independent backup password".toCharArray();
        ServerProfile profile = new ServerProfile("server-one", "example.test", 22, "deployer",
                "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD);

        try (DesktopPersistence persistence = DesktopPersistence.open(data);
             Argon2AesSecretStore store = new Argon2AesSecretStore(
                     persistence.encryptedSecrets(), "correct master password".toCharArray())) {
            DesktopApplicationFacade facade = new DesktopApplicationFacade(persistence, work, gateway);
            facade.saveServerProfile(profile, store, "ssh-password".toCharArray());
            persistApplication(persistence);

            CreatedBackupArchive created = facade.createManagedBackup("demo", destination, backupPassword,
                    master, ignored -> false);

            assertEquals(destination.toAbsolutePath(), created.archive());
            assertTrue(Files.isRegularFile(destination));
            assertEquals(List.of("observe", "pause", "stop", "release", "file-data", "start", "health", "health", "resume",
                    "discard"), log);
            assertEquals(4, created.inspection().memberCount());
            assertEquals(RuntimeState.RUNNING, state[0]);
            BackupUseCase localBackups = new BackupUseCase(work);
            PreparedBackupActivation activation = localBackups.prepareForActivation(destination,
                    "independent backup password".toCharArray());
            try {
                RestoreArchiveModel model = RestoreArchiveModel.load(activation);
                assertEquals(resources(), model.configurations().get("demo").resourceBindings());
                assertEquals(runtimeConfiguration(), model.configurations().get("demo").runtimeConfiguration());
                assertTrue(model.database().isEmpty());
            } finally {
                activation.close();
                localBackups.discard(activation.localCandidate());
            }
            assertTrue(allCleared(master));
            assertTrue(allCleared(backupPassword));
        }
    }

    @Test
    void restoresTheOriginalRunningStateWhenCollectionFailsAfterStopping() throws Exception {
        byte[] pax = pax();
        List<String> log = new ArrayList<>();
        RuntimeState[] state = {RuntimeState.RUNNING};
        DeploymentRemoteSession session = session(pax, log, state, true);
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> session;
        Path data = temporary.resolve("failed-data");
        Path work = temporary.resolve("failed-work");
        Path destination = temporary.resolve("must-not-exist.wtl-backup");
        char[] master = "correct master password".toCharArray();
        char[] backupPassword = "independent backup password".toCharArray();
        ServerProfile profile = new ServerProfile("server-one", "example.test", 22, "deployer",
                "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD);

        try (DesktopPersistence persistence = DesktopPersistence.open(data);
             Argon2AesSecretStore store = new Argon2AesSecretStore(
                     persistence.encryptedSecrets(), "correct master password".toCharArray())) {
            DesktopApplicationFacade facade = new DesktopApplicationFacade(persistence, work, gateway);
            facade.saveServerProfile(profile, store, "ssh-password".toCharArray());
            persistApplication(persistence);

            assertThrows(LinuxOperationException.class, () -> facade.createManagedBackup("demo", destination,
                    backupPassword, master, ignored -> false));

            assertEquals(List.of("observe", "pause", "stop", "release", "start", "health", "health", "resume", "discard"), log);
            assertEquals(RuntimeState.RUNNING, state[0]);
            assertTrue(Files.notExists(destination));
            assertTrue(allCleared(master));
            assertTrue(allCleared(backupPassword));
        }
    }

    @Test
    void missingMigrationStopApprovalPerformsNoRemoteOperation() throws Exception {
        Path data = temporary.resolve("migration-rejected-data");
        Path work = temporary.resolve("migration-rejected-work");
        char[] master = "correct master password".toCharArray();
        char[] backupPassword = "independent backup password".toCharArray();
        ServerProfile source = new ServerProfile("server-one", "example.test", 22, "deployer",
                "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD);
        ServerProfile target = new ServerProfile("server-two", "target.example.test", 22, "deployer",
                "ssh/server-two/password", CredentialStorageMode.MASTER_PASSWORD);
        try (DesktopPersistence persistence = DesktopPersistence.open(data);
             Argon2AesSecretStore store = new Argon2AesSecretStore(
                     persistence.encryptedSecrets(), "correct master password".toCharArray())) {
            DesktopApplicationFacade facade = new DesktopApplicationFacade(persistence, work,
                    work.resolveSibling("backups"), (endpoint, credential, verifier) -> {
                throw new AssertionError("unapproved migration must not open SSH");
            });
            facade.saveServerProfile(source, store, "source-password".toCharArray());
            facade.saveServerProfile(target, store, "target-password".toCharArray());
            persistApplication(persistence);

            ManagedOfflineMigrationOutcome outcome = facade.prepareManagedOfflineMigration("demo", "server-two",
                    backupPassword, master, false, ignored -> false);

            assertEquals(gold.debug.windowstolinux.shared.backup.execution.migration.OfflineMigrationStatus.PRECONDITION_REJECTED,
                    outcome.migration().status());
            assertTrue(outcome.retainedFinalArchive().isEmpty());
            assertTrue(allCleared(master));
            assertTrue(allCleared(backupPassword));
        }
    }

    private static void persistApplication(DesktopPersistence persistence) throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "example.test", 22, "SHA256:fixture");
        ManagedApplication application = ManagedApplication.forManaged("demo", server, "a".repeat(64));
        HealthCheck.Tcp health = new HealthCheck.Tcp(18080, 10, 1);
        DeploymentRuntimeSpecification runtime = new DeploymentRuntimeSpecification.NodeService(22, health);
        ManagedApplicationRuntimeConfiguration runtimeConfiguration = runtimeConfiguration();
        ComponentDataPath data = dataPath();
        ManagedComponentResourceBindings resources = resources();
        ManagedApplicationGraph.Component component = new ManagedApplicationGraph.Component("demo", application,
                runtimeConfiguration, List.of(), Optional.of(runtime), Optional.of(List.of(data)), Optional.of(resources));
        CurrentRelease release = new CurrentRelease("demo", "b".repeat(64),
                Instant.parse("2026-08-22T00:00:00Z"));
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create("demo", 1, "v1",
                Instant.parse("2026-08-22T00:00:00Z"), List.of(new ConfigurationEntry("PORT",
                        ConfigurationScope.RUNTIME, new ConfigurationValue.Number(18080))));
        persistence.managedApplicationGraphs().recordSuccessfulApplication(
                new ManagedApplicationGraph("demo", "demo", Optional.of(health), List.of(component)),
                List.of(new SuccessfulManagedDeployment(application, runtimeConfiguration, release,
                        configuration, List.of())));
    }

    private static ManagedApplicationRuntimeConfiguration runtimeConfiguration() {
        return new ManagedApplicationRuntimeConfiguration(new HealthCheck.Tcp(18080, 10, 1), Optional.empty(),
                gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode.SYSTEMD_STATIC);
    }

    private static ComponentDataPath dataPath() {
        return new ComponentDataPath("uploads", ComponentDataPath.AccessMode.READ_WRITE, "uploads-v1", false);
    }

    private static ManagedComponentResourceBindings resources() {
        return new ManagedComponentResourceBindings(List.of(new ManagedFileBinding("data", dataPath())),
                Optional.of(List.of()));
    }

    private static DeploymentRemoteSession session(
            byte[] pax, List<String> log, RuntimeState[] state, boolean failFirstArtifact) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pax));
        return (DeploymentRemoteSession) Proxy.newProxyInstance(RemoteBackupCreationUseCaseTest.class.getClassLoader(),
                new Class<?>[]{DeploymentRemoteSession.class, gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort.class, gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "backupArtifacts", "databaseOperations" -> proxy;
                    case "beginMaintenance", "endMaintenance" -> {
                        assertTrue(((String) arguments[1]).matches("backup-[0-9a-f]{32}"));
                        log.add(method.getName().equals("beginMaintenance") ? "pause" : "resume"); yield null;
                    }
                    case "collectCapabilities" -> new ServerCapabilityFacts("Ubuntu 24.04", "x86_64", true,
                            true, true, true, true, true, true, true, ManagedHelperProtocolVersion.CURRENT,
                            16L * 1024 * 1024 * 1024, "fixture");
                    case "collectDeploymentCapabilities" -> linuxFacts();
                    case "observeDeployment" -> {
                        log.add("observe");
                        yield observation((ManagedApplication) arguments[0], state[0]);
                    }
                    case "executeDeploymentLifecycle" -> {
                        LifecycleAction action = (LifecycleAction) arguments[2];
                        if (action == LifecycleAction.STOP) { state[0] = RuntimeState.STOPPED; log.add("stop"); }
                        if (action == LifecycleAction.START) { state[0] = RuntimeState.RUNNING; log.add("start"); }
                        yield observation((ManagedApplication) arguments[0], state[0]);
                    }
                    case "createBackupArtifact" -> {
                        RemoteBackupArtifactRequest request = (RemoteBackupArtifactRequest) arguments[0];
                        log.add(request.kind() == gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactKind.RELEASE_TREE
                                ? "release" : "file-" + request.resourceId());
                        if (failFirstArtifact) throw LinuxOperationException.create(
                                LinuxOperationFailureType.BACKUP_ARTIFACT_CREATION_FAILED,
                                "fixture collection failure");
                        String suffix = request.kind().ordinal() == 1 ? "1" : "2";
                        yield new RemoteBackupArtifact(request.operationId(), "artifact-" + suffix.repeat(32),
                                request.kind(), pax.length, digest);
                    }
                    case "copyBackupArtifact" -> { ((java.io.OutputStream) arguments[1]).write(pax); yield null; }
                    case "checkDeploymentHealth" -> { log.add("health"); yield new HealthCheckResult(true, "healthy"); }
                    case "discardBackupOperation" -> {
                        log.add("discard"); yield new RemoteStepResult(true, false, "discarded");
                    }
                    case "close" -> null;
                    case "toString" -> "remote backup fixture";
                    default -> throw new AssertionError("unexpected operation: " + method.getName());
                });
    }

    private static LifecycleObservation observation(ManagedApplication application, RuntimeState state) {
        return new LifecycleObservation(application, state, AutostartState.ENABLED, true,
                Instant.parse("2026-08-22T00:00:00Z"), "fixture");
    }

    private static LinuxCapabilityFacts linuxFacts() {
        return new LinuxCapabilityFacts(LinuxDistroType.UBUNTU, "24.04", "x86_64", "apt", "amd64",
                true, false, false, false, Set.of(21), Set.of(22), true, true, Set.of("3.12"), true,
                Map.of(), Map.of(), false, false, CpuMicroarchitectureLevel.X86_64_V3, Set.of("sse4_2"),
                new LinuxSecurityPosture(LinuxSecurityModuleType.APPARMOR, LinuxSecurityState.ENABLED,
                        LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE), "fixture");
    }

    private static byte[] pax() throws Exception {
        byte[] payload = "managed-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes)) {
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry("payload.txt");
            entry.setSize(payload.length);
            entry.setModTime(0L);
            archive.putArchiveEntry(entry);
            archive.write(payload);
            archive.closeArchiveEntry();
            archive.finish();
        }
        return bytes.toByteArray();
    }

    private static boolean allCleared(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }
}
