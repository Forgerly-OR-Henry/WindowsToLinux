package gold.debug.windowstolinux.app.service;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.service.ai.AiProfile;
import gold.debug.windowstolinux.app.service.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.SourcePreparation;
import gold.debug.windowstolinux.app.secret.store.Argon2AesSecretStore;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.LifecycleActionResult;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.analysis.ProjectAssessment;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.project.SourceProjectFacts;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.PhaseOneLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.PhaseOneRemoteSession;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.transfer.UploadReceipt;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildResult;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopApplicationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesPasswordOutsideProfileAndTrustsOnlyTheFirstConfirmedHostKey() throws Exception {
        ServerProfile profile = new ServerProfile(
                "server-one", "example.test", 22, "deployer", "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD
        );
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory);
             Argon2AesSecretStore secretStore = new Argon2AesSecretStore(database, "correct master password".toCharArray())) {
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), unusedGateway());
            service.saveServerProfile(profile, secretStore, "ssh-password".toCharArray());
            AiProfile aiProfile = new AiProfile(URI.create("https://example.test/v1/chat/completions"), "gpt-5",
                    "ai/default/api-key", CredentialStorageMode.MASTER_PASSWORD);
            service.saveAiProfile(aiProfile, CredentialStorageMode.MASTER_PASSWORD,
                    "correct master password".toCharArray(), "ai-key".toCharArray());

            assertEquals(profile, service.findServerProfile("server-one").orElseThrow());
            assertEquals(aiProfile, service.findAiProfile().orElseThrow());
            char[] password = service.loadPasswordCredential(profile, secretStore).copy();
            assertArrayEquals("ssh-password".toCharArray(), password);
            java.util.Arrays.fill(password, '\0');
            assertEquals(HostKeyDecision.ACCEPT_FIRST_USE,
                    service.hostKeyVerifier(profile, fingerprint -> fingerprint.equals("SHA256:first")).verify(profile.endpoint(), "SHA256:first"));
            assertEquals(HostKeyDecision.REJECT,
                    service.hostKeyVerifier(profile, fingerprint -> true).verify(profile.endpoint(), "SHA256:changed"));
            assertEquals("SHA256:first", database.findServer(profile.id()).orElseThrow().hostKeySha256(),
                    "a changed host key must not overwrite first-use trust");
        }
    }

    @Test
    void storesImmutableApplicationSecretsOnlyInThePlatformSecretStore() throws Exception {
        SecretReference reference = new SecretReference("database-password", 1);
        StoredApplicationSecretRevision revision = new StoredApplicationSecretRevision(reference,
                "application-secret/database-password/1", CredentialStorageMode.MASTER_PASSWORD, Instant.parse("2026-08-12T00:00:00Z"));
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory);
             Argon2AesSecretStore secretStore = new Argon2AesSecretStore(database, "correct master password".toCharArray())) {
            DesktopApplicationService service = new DesktopApplicationService(database, temporaryDirectory.resolve("work"), unusedGateway());
            service.savePhaseTwoSecretRevision(revision, secretStore, "database-password".toCharArray());

            assertEquals(revision, database.findApplicationSecretRevision(reference).orElseThrow());
            char[] stored = secretStore.read(revision.credentialKey()).orElseThrow();
            try {
                assertArrayEquals("database-password".toCharArray(), stored);
            } finally {
                java.util.Arrays.fill(stored, '\0');
            }
            assertThrows(java.sql.SQLException.class,
                    () -> service.savePhaseTwoSecretRevision(revision, secretStore, "replacement".toCharArray()));
        }
    }

    @Test
    void createsArchivesOnlyInsideTheFixedApplicationWorkDirectory() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Files.writeString(source.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>demo</artifactId><build><plugins><plugin>
                <artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>
                """);
        Files.writeString(source.resolve(".env"), "must-not-be-archived");
        Path work = temporaryDirectory.resolve("fixed-data/work");
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("data"))) {
            SourcePreparation preparation = new DesktopApplicationService(database, work, unusedGateway()).prepareSource(source);
            assertTrue(preparation.archive().orElseThrow().localArchive().startsWith(work.toAbsolutePath()));
            assertTrue(preparation.excludedEntries().contains(".env"));
        }
    }

    @Test
    void reusesStablePhaseOneOwnershipForTheSameApplicationAndServerIdentity() throws Exception {
        ServerIdentity savedServer = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ServerIdentity requestedServer = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ManagedApplication saved = ManagedApplication.forPhaseOne("demo", savedServer, "a".repeat(64));
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("data"))) {
            database.saveManagedApplication(saved);
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), unusedGateway());

            DeploymentRequest request = service.createDeploymentRequest(
                    supportedPreparation("demo"), requestedServer, healthCheck(), BuildLimits.defaultNonRoot(), false
            );

            assertEquals(saved, request.application());
            assertEquals(saved.ownershipManifestSha256(), request.application().ownershipManifestSha256());
            assertEquals(saved.systemdUnit(), request.application().systemdUnit());
            assertEquals(saved.releaseRoot(), request.application().releaseRoot());
        }
    }

    @Test
    void rejectsReclaimOfAnApplicationAlreadyBoundToAnotherServer() throws Exception {
        ServerIdentity savedServer = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ServerIdentity anotherServer = server("server-two", "192.0.2.11", "SHA256:BBBBBBBBBBBB");
        ManagedApplication saved = ManagedApplication.forPhaseOne("demo", savedServer, "a".repeat(64));
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("data"))) {
            database.saveManagedApplication(saved);
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), unusedGateway());

            LocalizedOperationException exception = assertThrows(LocalizedOperationException.class, () -> service.createDeploymentRequest(
                    supportedPreparation("demo"), anotherServer, healthCheck(), BuildLimits.defaultNonRoot(), false
            ));

            assertEquals("deployment.applicationServerConflict", exception.userMessage().key());
            assertEquals(saved, database.findManagedApplication("demo").orElseThrow());
        }
    }

    @Test
    void rejectsNonCanonicalPhaseOneIdentityWithoutOverwritingIt() throws Exception {
        ServerIdentity server = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ManagedApplication nonCanonical = new ManagedApplication(
                "demo", server, "windowstolinux-other.service", "/var/lib/windowstolinux/apps/demo", "a".repeat(64)
        );
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("data"))) {
            database.saveManagedApplication(nonCanonical);
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), unusedGateway());

            LocalizedOperationException exception = assertThrows(LocalizedOperationException.class, () -> service.createDeploymentRequest(
                    supportedPreparation("demo"), server, healthCheck(), BuildLimits.defaultNonRoot(), false
            ));

            assertEquals("deployment.applicationIdentityInvalid", exception.userMessage().key());
            assertEquals(nonCanonical, database.findManagedApplication("demo").orElseThrow());
        }
    }

    @Test
    void requiresAFreshRootApprovalBoundToTheReviewedSourceAndServer() throws Exception {
        ServerIdentity server = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        BuildLimits rootLimits = new BuildLimits(1200, 1024, 4096,
                4L * 1024 * 1024, 2L * 1024 * 1024 * 1024, true);
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("data"))) {
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), unusedGateway());

            assertThrows(IllegalArgumentException.class, () -> service.createDeploymentRequest(
                    supportedPreparation("demo"), server, healthCheck(), rootLimits, false
            ));

            DeploymentRequest approved = service.createDeploymentRequest(
                    supportedPreparation("demo"), server, healthCheck(), rootLimits, true
            );
            assertTrue(approved.approval().rootBuildAccepted());
            assertEquals(approved.archive().contentSha256(), approved.approval().sourceSha256());
            assertEquals(server.id(), approved.approval().serverId());
            assertEquals(approved.application().id(), approved.approval().applicationId());
        }
    }

    @Test
    void rejectsEnvironmentPreparationBeforeReadingCredentialsOrOpeningSshWhenConfirmationIsMissing() throws Exception {
        ServerProfile profile = new ServerProfile(
                "server-one", "example.test", 22, "deployer", "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD
        );
        char[] masterPassword = "correct master password".toCharArray();
        AtomicInteger connections = new AtomicInteger();
        PhaseOneLinuxGateway gateway = (endpoint, credential, verifier) -> {
            connections.incrementAndGet();
            throw new AssertionError("unconfirmed environment preparation must not connect");
        };
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("data"))) {
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), gateway);

            LocalizedOperationException failure = assertThrows(LocalizedOperationException.class, () ->
                    service.preparePhaseOneEnvironmentWithStoredPassword(
                            profile, CredentialStorageMode.MASTER_PASSWORD, masterPassword, fingerprint -> true, false
                    )
            );

            assertEquals("environment.confirmationRequired", failure.userMessage().key());
            assertEquals(0, connections.get());
        }
        for (char value : masterPassword) {
            assertEquals('\0', value);
        }
    }

    @Test
    void lifecycleAfterDesktopRestartUsesTheLastSuccessfulPersistedHealthCheck() throws Exception {
        Path data = temporaryDirectory.resolve("restart-data");
        ServerIdentity server = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ManagedApplication application = ManagedApplication.forPhaseOne("demo", server, "a".repeat(64));
        HealthCheck persistedHealth = new HealthCheck.Tcp(19092, 15, 2);
        ServerProfile profile = new ServerProfile("server-one", "192.0.2.10", 22, "deployer",
                "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD);

        try (DesktopDatabase database = DesktopDatabase.open(data)) {
            DesktopApplicationService firstDesktop = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("first-work"), unusedGateway());
            firstDesktop.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "restart-master".toCharArray(), "ssh-password".toCharArray());
            database.recordSuccessfulDeployment(application,
                    new ManagedApplicationRuntimeConfiguration(persistedHealth, Optional.empty()),
                    new CurrentRelease(application.id(), "b".repeat(64), Instant.parse("2026-08-10T00:00:00Z")));
        }

        RecordingGateway gateway = new RecordingGateway();
        try (DesktopDatabase reopened = DesktopDatabase.open(data)) {
            DesktopApplicationService restartedDesktop = new DesktopApplicationService(reopened,
                    temporaryDirectory.resolve("second-work"), gateway);
            LifecycleOutcome result = restartedDesktop.executePersistedLifecycleWithStoredPassword(
                    application.id(), LifecycleAction.RESTART, "restart-master".toCharArray());

            assertTrue(result.accepted(), result::toString);
            assertEquals(RuntimeState.RUNNING, result.observation().orElseThrow().runtimeState());
            assertEquals(AutostartState.ENABLED, result.observation().orElseThrow().autostartState());
            assertTrue(result.observation().orElseThrow().ownershipVerified());
            assertEquals(persistedHealth, gateway.healthCheck);
            assertEquals(1, gateway.connections.get());
        }
    }

    @Test
    void legacyApplicationWithoutPersistedRuntimeConfigurationDoesNotOpenSsh() throws Exception {
        ServerIdentity server = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ManagedApplication application = ManagedApplication.forPhaseOne("demo", server, "a".repeat(64));
        RecordingGateway gateway = new RecordingGateway();
        char[] masterPassword = "restart-master".toCharArray();
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("legacy-data"))) {
            database.saveManagedApplication(application);
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), gateway);

            LocalizedOperationException failure = assertThrows(LocalizedOperationException.class,
                    () -> service.executePersistedLifecycleWithStoredPassword(
                            application.id(), LifecycleAction.RESTART, masterPassword));

            assertEquals("applications.legacyRuntime", failure.userMessage().key());
            assertEquals(0, gateway.connections.get());
        }
        for (char value : masterPassword) {
            assertEquals('\0', value);
        }
    }

    private SourcePreparation supportedPreparation(String applicationId) {
        SourceProjectFacts facts = new SourceProjectFacts(
                temporaryDirectory.resolve(applicationId), applicationId, false, true,
                List.of(LocalizedMessage.of("analysis.observation.mavenDetected"))
        );
        SourceArchiveDescriptor archive = new SourceArchiveDescriptor(
                temporaryDirectory.resolve(applicationId + ".tar.gz"), "b".repeat(64), 0, 0
        );
        return new SourcePreparation(ProjectAssessment.supported(facts), Optional.of(archive), List.of());
    }

    private static ServerIdentity server(String id, String host, String fingerprint) {
        return new ServerIdentity(id, host, 22, fingerprint);
    }

    private static HealthCheck healthCheck() {
        return new HealthCheck.Tcp(8080, 60, 1);
    }

    private static PhaseOneLinuxGateway unusedGateway() {
        return (endpoint, credential, verifier) -> {
            throw new AssertionError("this test must not open SSH");
        };
    }

    private static final class RecordingGateway implements PhaseOneLinuxGateway {
        private final AtomicInteger connections = new AtomicInteger();
        private HealthCheck healthCheck;

        @Override
        public PhaseOneRemoteSession connect(SshEndpoint endpoint, SshCredential credential, HostKeyVerifier hostKeyVerifier) {
            connections.incrementAndGet();
            return new PhaseOneRemoteSession() {
                @Override
                public gold.debug.windowstolinux.shared.model.server.ServerCapabilities collectCapabilities() {
                    throw new AssertionError("not used by lifecycle control");
                }

                @Override
                public gold.debug.windowstolinux.shared.model.deployment.PhaseOneEnvironmentPreparationResult preparePhaseOneEnvironment(
                        gold.debug.windowstolinux.shared.model.deployment.PhaseOneEnvironmentPreparationApproval approval
                ) {
                    throw new AssertionError("not used by lifecycle control");
                }

                @Override
                public UploadReceipt uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace) {
                    throw new AssertionError("not used by lifecycle control");
                }

                @Override
                public RemoteBuildResult build(RemoteWorkspace workspace, BuildLimits limits) {
                    throw new AssertionError("not used by lifecycle control");
                }

                @Override
                public ReleaseSnapshot snapshot(ManagedApplication application) {
                    throw new AssertionError("not used by lifecycle control");
                }

                @Override
                public RemoteStepResult publish(ManagedApplication application, RemoteWorkspace workspace,
                                                RemoteBuildResult build, ReleaseSnapshot snapshot) {
                    throw new AssertionError("not used by lifecycle control");
                }

                @Override
                public HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck) {
                    throw new AssertionError("not used by lifecycle control");
                }

                @Override
                public RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application) {
                    throw new AssertionError("not used by lifecycle control");
                }

                @Override
                public RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot,
                                                 RemoteBuildResult build) {
                    throw new AssertionError("not used by lifecycle control");
                }

                @Override
                public LifecycleObservation observe(ManagedApplication application) {
                    return observation(application);
                }

                @Override
                public LifecycleObservation executeLifecycle(
                        ManagedApplication application,
                        LifecycleAction action,
                        HealthCheck requestedHealthCheck
                ) {
                    healthCheck = requestedHealthCheck;
                    return observation(application);
                }

                @Override
                public void close() {
                    // No resources are held by the recording session. / 记录会话不持有任何资源。
                }
            };
        }

        private static LifecycleObservation observation(ManagedApplication application) {
            return new LifecycleObservation(application, RuntimeState.RUNNING, AutostartState.ENABLED, true,
                    Instant.now(), "recording lifecycle session");
        }
    }
}
