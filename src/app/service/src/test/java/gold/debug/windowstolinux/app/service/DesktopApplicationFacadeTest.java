package gold.debug.windowstolinux.app.service;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.service.ai.AiProfile;
import gold.debug.windowstolinux.app.service.ai.AiAnalysisOutcome;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.execution.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.secret.Argon2AesSecretStore;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.LifecycleActionResult;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopApplicationFacadeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesPasswordOutsideProfileAndTrustsOnlyTheFirstConfirmedHostKey() throws Exception {
        ServerProfile profile = new ServerProfile(
                "server-one", "example.test", 22, "deployer", "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD
        );
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory);
             Argon2AesSecretStore secretStore = new Argon2AesSecretStore(database.encryptedSecrets(), "correct master password".toCharArray())) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
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
            assertEquals("SHA256:first", database.servers().findServer(profile.id()).orElseThrow().hostKeySha256(),
                    "a changed host key must not overwrite first-use trust");
        }
    }

    @Test
    void storesImmutableApplicationSecretsOnlyInThePlatformSecretStore() throws Exception {
        SecretReference reference = new SecretReference("database-password", 1);
        StoredApplicationSecretRevision revision = new StoredApplicationSecretRevision(reference,
                "application-secret/database-password/1", CredentialStorageMode.MASTER_PASSWORD, Instant.parse("2026-08-12T00:00:00Z"));
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory);
             Argon2AesSecretStore secretStore = new Argon2AesSecretStore(database.encryptedSecrets(), "correct master password".toCharArray())) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(database, temporaryDirectory.resolve("work"), unusedGateway());
            service.saveDeploymentSecretRevision(revision, secretStore, "database-password".toCharArray());

            assertEquals(revision, database.applicationSecrets().findRevision(reference).orElseThrow());
            char[] stored = secretStore.read(revision.credentialKey()).orElseThrow();
            try {
                assertArrayEquals("database-password".toCharArray(), stored);
            } finally {
                java.util.Arrays.fill(stored, '\0');
            }
            assertThrows(java.sql.SQLException.class,
                    () -> service.saveDeploymentSecretRevision(revision, secretStore, "replacement".toCharArray()));
        }
    }

    @Test
    void keepsNamedAiProvidersExplicitAndDoesNotUseTheLegacyDefaultAsFallback() throws Exception {
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory)) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(database, temporaryDirectory.resolve("work"), unusedGateway());
            AiProviderProfile provider = new AiProviderProfile("analysis", URI.create("https://analysis.example.test/v1/chat/completions"),
                    "gpt-5", "ai/analysis/api-key", CredentialStorageMode.MASTER_PASSWORD);
            service.saveAiProviderProfile(provider, "correct master password".toCharArray(), "analysis-key".toCharArray());

            assertEquals(java.util.List.of(provider), service.listAiProviderProfiles());
            AiAnalysisOutcome unavailable = service.requestAiExplanationFromProvider(
                    new ReviewedSourcePreparation(DeploymentProjectAssessment.rejected(java.util.List.of(
                            new gold.debug.windowstolinux.shared.model.analysis.RejectionReason("TEST_REJECTED",
                                    gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of("test.rejected"), "test"))),
                            Optional.empty(), Optional.empty(), java.util.List.of()),
                    "missing", "correct master password".toCharArray(), "en");
            assertEquals("ai.status.providerMissing", unavailable.status().key());
        }
    }

    @Test
    void exposesOnlyReadOnlyTypedToolsToTheOptionalDeploymentAgent() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("typed-deployment-source"));
        Files.writeString(source.resolve("package.json"), """
                {"name":"demo","scripts":{"build":"vite","start":"node server.js"}}
                """);
        Files.writeString(source.resolve("package-lock.json"), """
                {"name":"demo","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"demo","version":"1.0.0"}}}
                """);
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory)) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(database, temporaryDirectory.resolve("work"), unusedGateway());
            assertEquals(gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus.READY_FOR_PLANNING,
                    service.analyzeDeploymentSource(source, gold.debug.windowstolinux.shared.model.project.DeploymentProjectType.NODE_SERVICE)
                            .admission());
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
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("data"))) {
            ReviewedSourcePreparation preparation = new DesktopApplicationFacade(database, work, unusedGateway())
                    .prepareReviewedSource(source, DeploymentProjectType.SPRING_BOOT);
            assertTrue(preparation.archive().orElseThrow().localArchive().startsWith(work.toAbsolutePath()));
            assertTrue(preparation.excludedEntries().contains(".env"));
        }
    }

    @Test
    void reusesStableManagedOwnershipForTheSameApplicationAndServerIdentity() throws Exception {
        ServerIdentity savedServer = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ServerIdentity requestedServer = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ManagedApplication saved = ManagedApplication.forManaged("demo", savedServer, "a".repeat(64));
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("data"))) {
            database.managedApplications().save(saved);
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), unusedGateway());

            ReviewedDeploymentRequest request = createRequest(service, supportedPreparation("demo"), requestedServer,
                    BuildLimitConfiguration.defaultNonRoot(), false);

            assertEquals("demo", request.facts().applicationId());
            assertEquals(saved, database.managedApplications().find("demo").orElseThrow());
        }
    }

    @Test
    void rejectsReclaimOfAnApplicationAlreadyBoundToAnotherServer() throws Exception {
        ServerIdentity savedServer = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ServerIdentity anotherServer = server("server-two", "192.0.2.11", "SHA256:BBBBBBBBBBBB");
        ManagedApplication saved = ManagedApplication.forManaged("demo", savedServer, "a".repeat(64));
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("data"))) {
            database.managedApplications().save(saved);
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), unusedGateway());

            LocalizedOperationException exception = assertThrows(LocalizedOperationException.class, () ->
                    createRequest(service, supportedPreparation("demo"), anotherServer,
                            BuildLimitConfiguration.defaultNonRoot(), false));

            assertEquals("deployment.applicationServerConflict", exception.userMessage().key());
            assertEquals(saved, database.managedApplications().find("demo").orElseThrow());
        }
    }

    @Test
    void rejectsNonCanonicalManagedIdentityWithoutOverwritingIt() throws Exception {
        ServerIdentity server = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ManagedApplication nonCanonical = new ManagedApplication(
                "demo", server, "windowstolinux-other.service", "/var/lib/windowstolinux/apps/demo", "a".repeat(64)
        );
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("data"))) {
            database.managedApplications().save(nonCanonical);
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), unusedGateway());

            LocalizedOperationException exception = assertThrows(LocalizedOperationException.class, () ->
                    createRequest(service, supportedPreparation("demo"), server,
                            BuildLimitConfiguration.defaultNonRoot(), false));

            assertEquals("deployment.applicationIdentityInvalid", exception.userMessage().key());
            assertEquals(nonCanonical, database.managedApplications().find("demo").orElseThrow());
        }
    }

    @Test
    void requiresAFreshRootApprovalBoundToTheReviewedSourceAndServer() throws Exception {
        ServerIdentity server = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        BuildLimitConfiguration rootLimits = new BuildLimitConfiguration(1200, 1024, 4096,
                4L * 1024 * 1024, 2L * 1024 * 1024 * 1024, true);
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), unusedGateway());

            assertThrows(IllegalArgumentException.class, () ->
                    createRequest(service, supportedPreparation("demo"), server, rootLimits, false));

            ReviewedDeploymentRequest approved = createRequest(service, supportedPreparation("demo"), server,
                    rootLimits, true);
            assertTrue(approved.approval().rootBuildAccepted());
            assertEquals(approved.archive().contentSha256(), approved.approval().sourceSha256());
            assertEquals(server.id(), approved.approval().serverId());
            assertEquals(approved.facts().applicationId(), approved.approval().applicationId());
        }
    }

    @Test
    void rejectsEnvironmentPreparationBeforeReadingCredentialsOrOpeningSshWhenConfirmationIsMissing() throws Exception {
        ServerProfile profile = new ServerProfile(
                "server-one", "example.test", 22, "deployer", "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD
        );
        char[] masterPassword = "correct master password".toCharArray();
        AtomicInteger connections = new AtomicInteger();
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> {
            connections.incrementAndGet();
            throw new AssertionError("unconfirmed environment preparation must not connect");
        };
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), gateway);

            LocalizedOperationException failure = assertThrows(LocalizedOperationException.class, () ->
                    service.prepareEnvironmentWithStoredPassword(
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
    void inspectsExactDeploymentCapabilitiesWithoutMutationAndClearsTheMasterPassword() throws Exception {
        ServerProfile profile = new ServerProfile("server-one", "example.test", 22, "deployer",
                "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD);
        LinuxCapabilityFacts expected = new LinuxCapabilityFacts(LinuxDistroType.UBUNTU, "24.04", "x86_64", "apt",
                "amd64", true, false, false, false, Set.of(21), Set.of(22), true, true,
                Set.of("3.12"), true, Map.of(), Map.of(), false, false, CpuMicroarchitectureLevel.X86_64_V3,
                Set.of("sse4_2"), new LinuxSecurityPosture(LinuxSecurityModuleType.APPARMOR,
                LinuxSecurityState.ENABLED, LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE), "bounded fixture");
        AtomicInteger connections = new AtomicInteger();
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> {
            connections.incrementAndGet();
            assertEquals(HostKeyDecision.ACCEPT_FIRST_USE,
                    verifier.verify(endpoint, "SHA256:deployment-capabilities"));
            if (credential instanceof SshCredential.Password password) password.clear();
            return (DeploymentRemoteSession) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{DeploymentRemoteSession.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "collectDeploymentCapabilities" -> expected;
                        case "close" -> null;
                        case "toString" -> "bounded capability session";
                        default -> throw new AssertionError("read-only inspection used unexpected operation: "
                                + method.getName());
                    });
        };
        char[] masterPassword = "capability-master".toCharArray();
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("capability-data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), gateway);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "capability-master".toCharArray(), "ssh-password".toCharArray());

            assertEquals(expected, service.inspectDeploymentCapabilitiesWithStoredPassword(
                    profile, CredentialStorageMode.MASTER_PASSWORD, masterPassword, fingerprint -> true));
            assertEquals(1, connections.get());
        }
        for (char value : masterPassword) assertEquals('\0', value);
    }

    @Test
    void lifecycleAfterDesktopRestartUsesTheLastSuccessfulPersistedHealthCheck() throws Exception {
        Path data = temporaryDirectory.resolve("restart-data");
        ServerIdentity server = server("server-one", "192.0.2.10", "SHA256:AAAAAAAAAAAA");
        ManagedApplication application = ManagedApplication.forManaged("demo", server, "a".repeat(64));
        HealthCheck persistedHealth = new HealthCheck.Tcp(19092, 15, 2);
        ServerProfile profile = new ServerProfile("server-one", "192.0.2.10", 22, "deployer",
                "ssh/server-one/password", CredentialStorageMode.MASTER_PASSWORD);

        try (DesktopPersistence database = DesktopPersistence.open(data)) {
            DesktopApplicationFacade firstDesktop = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("first-work"), unusedGateway());
            firstDesktop.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "restart-master".toCharArray(), "ssh-password".toCharArray());
            database.managedApplications().recordSuccessfulDeployment(application,
                    new ManagedApplicationRuntimeConfiguration(persistedHealth, Optional.empty()),
                    new CurrentRelease(application.id(), "b".repeat(64), Instant.parse("2026-08-10T00:00:00Z")));
        }

        RecordingGateway gateway = new RecordingGateway();
        try (DesktopPersistence reopened = DesktopPersistence.open(data)) {
            DesktopApplicationFacade restartedDesktop = new DesktopApplicationFacade(reopened,
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
        ManagedApplication application = ManagedApplication.forManaged("demo", server, "a".repeat(64));
        RecordingGateway gateway = new RecordingGateway();
        char[] masterPassword = "restart-master".toCharArray();
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("legacy-data"))) {
            database.managedApplications().save(application);
            DesktopApplicationFacade service = new DesktopApplicationFacade(
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

    private ReviewedSourcePreparation supportedPreparation(String applicationId) {
        DeploymentProjectFacts facts = new DeploymentProjectFacts(
                temporaryDirectory.resolve(applicationId), applicationId, DeploymentProjectType.SPRING_BOOT,
                DeploymentBuildToolType.MAVEN, List.of(), List.of(), List.of());
        SourceArchiveDescriptor archive = new SourceArchiveDescriptor(
                temporaryDirectory.resolve(applicationId + ".tar.gz"), "b".repeat(64), 0, 0
        );
        return new ReviewedSourcePreparation(DeploymentProjectAssessment.ready(facts), Optional.of(archive),
                Optional.of(new SourceRevision(archive.contentSha256(), Optional.empty(), Map.of())), List.of());
    }

    private ReviewedDeploymentRequest createRequest(DesktopApplicationFacade service,
                                                     ReviewedSourcePreparation preparation, ServerIdentity server,
                                                     BuildLimitConfiguration limits, boolean rootBuildConfirmed) throws Exception {
        return service.createReviewedDeploymentRequest(preparation, server,
                ConfigurationSnapshot.create("demo", 1, "v1", Instant.now(), List.of(
                        new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                                new ConfigurationValue.Number(8080)))), List.of(),
                new DeploymentRuntimeSpecification.SpringBoot(healthCheck()), Optional.empty(), limits,
                rootBuildConfirmed, true, true);
    }

    private static ServerIdentity server(String id, String host, String fingerprint) {
        return new ServerIdentity(id, host, 22, fingerprint);
    }

    private static HealthCheck healthCheck() {
        return new HealthCheck.Tcp(8080, 60, 1);
    }

    private static DeploymentLinuxGateway unusedGateway() {
        return (endpoint, credential, verifier) -> {
            throw new AssertionError("this test must not open SSH");
        };
    }

    private static final class RecordingGateway implements DeploymentLinuxGateway {
        private final AtomicInteger connections = new AtomicInteger();
        private HealthCheck healthCheck;

        /** Performs the {@code connect} operation. / 执行 {@code connect} 操作。 */
        @Override
        public DeploymentRemoteSession connect(SshEndpoint endpoint, SshCredential credential,
                                               HostKeyEvaluator hostKeyVerifier) {
            connections.incrementAndGet();
            return (DeploymentRemoteSession) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{DeploymentRemoteSession.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "observe" -> observation((ManagedApplication) arguments[0]);
                        case "executeLifecycle" -> {
                            healthCheck = (HealthCheck) arguments[2];
                            yield observation((ManagedApplication) arguments[0]);
                        }
                        case "close" -> null;
                        case "toString" -> "recording lifecycle session";
                        default -> throw new AssertionError("not used by lifecycle control: " + method.getName());
                    });
        }

        private static LifecycleObservation observation(ManagedApplication application) {
            return new LifecycleObservation(application, RuntimeState.RUNNING, AutostartState.ENABLED, true,
                    Instant.now(), "recording lifecycle session");
        }
    }
}
