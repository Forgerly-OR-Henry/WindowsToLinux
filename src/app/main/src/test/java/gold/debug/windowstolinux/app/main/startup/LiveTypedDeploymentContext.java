package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.service.backup.CreatedBackupArchive;
import gold.debug.windowstolinux.app.service.backup.ManagedOfflineMigrationOutcome;
import gold.debug.windowstolinux.app.service.backup.ManagedRestoreOutcome;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.DeploymentPlanAction;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportLevel;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owns one ephemeral desktop database while every remote action still crosses the production application service. / 持有单个临时桌面数据库，同时确保每个远端动作仍通过生产应用服务。 */
final class LiveTypedDeploymentContext implements AutoCloseable {
    private static final CredentialStorageMode MODE = CredentialStorageMode.MASTER_PASSWORD;
    private final DesktopPersistence persistence;
    private final char[] masterPassword;
    private final ServerProfile profile;
    private final ServerIdentity server;
    final DesktopApplicationFacade service;

    LiveTypedDeploymentContext(Path root) throws Exception {
        String host = requiredProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "root");
        assertEquals("root", username, "managed deployment requires root management");
        assertTrue(!Boolean.getBoolean("managed.root-build"), "root builds were removed in helper v7");
        char[] sshPassword = requiredEnvironment("WINDOWSTOLINUX_TEST_SSH_PASSWORD").toCharArray();
        this.masterPassword = System.getenv().getOrDefault(
                "WINDOWSTOLINUX_TEST_MASTER_PASSWORD", "typed-live-acceptance-master").toCharArray();
        this.persistence = DesktopPersistence.open(root.resolve("desktop-data"));
        this.service = new DesktopApplicationFacade(persistence, root.resolve("work"), new SshdLinuxGateway());
        int port = Integer.getInteger("managed.ssh.port", 22);
        this.profile = new ServerProfile("typed-live", host, port, username,
                "ssh/typed-live/password", MODE);
        try {
            withMaster(master -> {
                service.saveServerProfile(profile, MODE, master, sshPassword);
                return null;
            });
            withMaster(master -> {
                service.verifyServer(profile, MODE, master, fingerprint -> true);
                return null;
            });
            this.server = service.findTrustedServer(profile.id()).orElseThrow();
        } finally {
            Arrays.fill(sshPassword, '\0');
        }
    }

    ReviewedSourcePreparation prepare(Path source, DeploymentProjectType type) throws Exception {
        ReviewedSourcePreparation preparation = service.prepareReviewedSource(source, type);
        assertTrue(preparation.archive().isPresent(), () -> "source was not planning-ready: " + preparation.assessment());
        return preparation;
    }

    ReviewedSourcePreparation prepare(GitSourceRequest source, DeploymentProjectType type) throws Exception {
        ReviewedSourcePreparation preparation = service.prepareReviewedGitSource(source, type);
        assertTrue(preparation.archive().isPresent(), () -> "Git source was not planning-ready: " + preparation.assessment());
        return preparation;
    }

    PreparedMultiComponentSource prepareMulti(Path source, String applicationId,
                                               List<ComponentAnalysisRequest> components) throws Exception {
        PreparedMultiComponentSource preparation = service.prepareReviewedMultiComponentSource(
                source, applicationId, components);
        assertEquals(components.size(), preparation.components().size(),
                () -> "component graph was not planning-ready: " + preparation.assessment());
        return preparation;
    }

    ReviewedMultiComponentApplication reviewMulti(PreparedMultiComponentSource preparation,
                                                   List<MultiComponentReviewInput> inputs,
                                                   ApplicationHealthGate applicationHealth) throws Exception {
        return service.createReviewedMultiComponentApplication(preparation, server, inputs, applicationHealth);
    }

    MultiComponentDeploymentResult deployMulti(ReviewedMultiComponentApplication review,
                                                List<MultiComponentReviewInput> inputs) throws Exception {
        for (MultiComponentReviewInput input : inputs) {
            service.saveDeploymentConfigurationSnapshot(input.configuration());
        }
        return withMaster(master -> service.deployReviewedMultiComponentWithStoredPassword(review, profile, MODE,
                master, fingerprint -> true));
    }

    MultiComponentLifecycleResult lifecycleMulti(String applicationId, Set<String> componentIds,
                                                 LifecycleAction action) throws Exception {
        return withMaster(master -> service.executeManagedMultiComponentLifecycleWithStoredPassword(applicationId,
                componentIds, action, profile, MODE, master));
    }

    LinuxCapabilityFacts inspectDeploymentCapabilities() throws Exception {
        return withMaster(master -> service.inspectDeploymentCapabilitiesWithStoredPassword(profile, MODE, master,
                fingerprint -> true));
    }

    ServerIdentity trustedServer() {
        return server;
    }

    EnvironmentSetupResult prepareEnvironment() throws Exception {
        return withMaster(master -> service.prepareEnvironmentWithStoredPassword(profile, MODE, master,
                fingerprint -> true, true));
    }

    EnvironmentSetupResult prepareEnvironmentWithSystemConfirmation(
            java.util.function.Predicate<gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan> confirmation)
            throws Exception {
        return withMaster(master -> service.prepareEnvironmentWithStoredPassword(profile, MODE, master,
                fingerprint -> true, true, confirmation));
    }

    ServerProfile registerTargetServer() throws Exception {
        String host = requiredProperty("managed.target.ssh.host");
        String username = System.getProperty("managed.target.ssh.user", "root");
        assertEquals("root", username, "managed deployment requires root management");
        assertTrue(!Boolean.getBoolean("managed.target.root-build"), "root builds were removed in helper v7");
        int port = Integer.getInteger("managed.target.ssh.port", 22);
        char[] sshPassword = requiredEnvironment("WINDOWSTOLINUX_TEST_TARGET_SSH_PASSWORD").toCharArray();
        ServerProfile target = new ServerProfile("typed-live-target", host, port, username,
                "ssh/typed-live-target/password", MODE);
        try {
            withMaster(master -> {
                service.saveServerProfile(target, MODE, master, sshPassword);
                return null;
            });
            withMaster(master -> {
                service.verifyServer(target, MODE, master, fingerprint -> true);
                return null;
            });
            assertTrue(service.findTrustedServer(target.id()).isPresent(),
                    "target server trust must be persisted before managed backup acceptance");
            return target;
        } finally {
            Arrays.fill(sshPassword, '\0');
        }
    }

    CreatedBackupArchive createManagedBackup(String applicationId, Path destination, char[] backupPassword)
            throws Exception {
        char[] copiedBackupPassword = backupPassword.clone();
        try {
            return withMaster(master -> service.createManagedBackup(applicationId, destination,
                    copiedBackupPassword, master, fingerprint -> true));
        } finally {
            Arrays.fill(copiedBackupPassword, '\0');
        }
    }

    ManagedRestoreOutcome restoreManagedBackup(
            Path archive, ServerProfile target, char[] backupPassword) throws Exception {
        char[] copiedBackupPassword = backupPassword.clone();
        try {
            return withMaster(master -> service.restoreManagedBackup(archive, target.id(),
                    copiedBackupPassword, master, fingerprint -> true));
        } finally {
            Arrays.fill(copiedBackupPassword, '\0');
        }
    }

    ManagedOfflineMigrationOutcome prepareManagedOfflineMigration(
            String applicationId, ServerProfile target, char[] backupPassword) throws Exception {
        char[] copiedBackupPassword = backupPassword.clone();
        try {
            return withMaster(master -> service.prepareManagedOfflineMigration(applicationId, target.id(),
                    copiedBackupPassword, master, true, fingerprint -> true));
        } finally {
            Arrays.fill(copiedBackupPassword, '\0');
        }
    }

    DeploymentResult deploy(ReviewedSourcePreparation preparation, long configurationRevision,
                            List<ConfigurationEntry> entries, List<SecretReference> secrets,
                            DeploymentRuntimeSpecification runtime, Optional<UserAccessUrl> userAccessUrl) throws Exception {
        String applicationId = preparation.assessment().facts().orElseThrow().applicationId();
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create(applicationId, configurationRevision,
                "acceptance-v1", Instant.now(), entries);
        service.saveDeploymentConfigurationSnapshot(configuration);
        boolean ecosystemService = runtime instanceof DeploymentRuntimeSpecification.GoService
                || runtime instanceof DeploymentRuntimeSpecification.RustService
                || runtime instanceof DeploymentRuntimeSpecification.DotNetService
                || runtime instanceof DeploymentRuntimeSpecification.KotlinService
                || runtime instanceof DeploymentRuntimeSpecification.PhpService
                || runtime instanceof DeploymentRuntimeSpecification.RubyService;
        int reviewedAddressSpaceMiB = runtime instanceof DeploymentRuntimeSpecification.DotNetService
                || runtime instanceof DeploymentRuntimeSpecification.KotlinService
                ? 8192 : 3072;
        int reviewedTimeoutSeconds = ecosystemService ? 600 : 1800;
        ReviewedDeploymentRequest request = service.createReviewedDeploymentRequest(preparation, server, configuration,
                secrets, Optional.of(List.of()), runtime, userAccessUrl,
                new BuildLimitConfiguration(reviewedTimeoutSeconds, 1024, reviewedAddressSpaceMiB,
                        8L * 1024 * 1024, 4L * 1024 * 1024 * 1024, false), false,
                runtime instanceof DeploymentRuntimeSpecification.Container container
                        && container.engine() == DeploymentRuntimeSpecification.ContainerEngineType.DOCKER,
                preparation.assessment().facts().orElseThrow().support().level()
                        == DeploymentSupportLevel.EXPERIMENTAL_ADAPTER);
        ReviewedDeploymentPlan plan = service.planDeployment(request);
        assertEquals(request, plan.request());
        assertTrue(plan.steps().containsAll(List.of(DeploymentPlanAction.VERIFY_SOURCE_IDENTITY,
                DeploymentPlanAction.VERIFY_CONFIGURATION_SNAPSHOT, DeploymentPlanAction.VERIFY_SECRET_REVISIONS,
                DeploymentPlanAction.CHECK_HEALTH,
                DeploymentPlanAction.ROLLBACK_ON_FAILURE)));
        DeploymentResult result = withMaster(master -> service.deployReviewedWithStoredPassword(request, profile, MODE, master,
                fingerprint -> true).result());
        System.out.printf("LIVE_DEPLOYMENT application=%s type=%s revision=%d status=%s release=%s%n",
                applicationId, runtime.projectType(), configurationRevision, result.status(),
                result.publishedReleaseSha256().orElse("none"));
        result.events().stream().filter(event -> !event.succeeded()).forEach(event ->
                System.out.printf("LIVE_FAILURE application=%s step=%s evidence=%s%n",
                        applicationId, event.step().code(), event.evidence()));
        if (result.status() == gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus.SUCCEEDED) {
            assertTrue(result.nonFatalFailures().isEmpty(), () -> result.nonFatalFailures().toString());
            assertRuntimeIdentity(applicationId, runtime);
            assertTrue(service.listManagedApplications().stream().anyMatch(app -> app.id().equals(applicationId)),
                    "successful deployment must remain available for product lifecycle operations");
        }
        return result;
    }

    void assertRuntimeIdentity(String applicationId, DeploymentRuntimeSpecification runtime) throws Exception {
        assertTrue(applicationId.matches("[a-z0-9][a-z0-9-]{0,62}"));
        String command;
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            String engine = container.engine().name().toLowerCase(java.util.Locale.ROOT);
            command = "pid=$(" + engine + " inspect --format '{{.State.Pid}}' windowstolinux-" + applicationId + ")\n";
        } else {
            String unit = "windowstolinux-" + applicationId + ".service";
            command = "test \"$(systemctl show --value --property DynamicUser " + unit + ")\" = yes\n"
                    + "user=$(systemctl show --value --property User " + unit + ")\n"
                    + "! grep -q \"^$user:\" /etc/passwd\n"
                    + "pid=$(systemctl show --value --property MainPID " + unit + ")\n";
        }
        command = "set -eu\n" + command + "test \"$pid\" -gt 0\n"
                + "uid=$(awk '/^Uid:/ {print $2}' /proc/$pid/status)\ntest \"$uid\" -gt 0\n"
                + "printf 'RUNTIME_UID=%s\\n' \"$uid\"\n"
                + "if [ -e /sys/fs/selinux/enforce ]; then printf 'RUNTIME_SELINUX='; tr -d '\\000' < /proc/$pid/attr/current; printf '\\n'; fi\n"
                + "test -z \"$(find /var/lib/windowstolinux/work -maxdepth 1 -name '" + applicationId + "-*' -print -quit)\"\n";
        try (var client = org.apache.sshd.client.SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, remote, key) -> server.hostKeySha256().equals(
                    org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(org.apache.sshd.common.digest.BuiltinDigests.sha256, key)));
            client.start();
            try (var session = client.connect(profile.username(), profile.host(), profile.sshPort())
                    .verify(java.time.Duration.ofSeconds(20)).getSession()) {
                session.addPasswordIdentity(requiredEnvironment("WINDOWSTOLINUX_TEST_SSH_PASSWORD"));
                session.auth().verify(java.time.Duration.ofSeconds(20));
                var result = new gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor(session)
                        .execProtocol(command, java.time.Duration.ofSeconds(20), true);
                assertTrue(result.succeeded(), result::failureEvidence);
                System.out.println("LIVE_IDENTITY " + applicationId + " " + result.output().strip());
            }
        }
    }

    void saveSecret(SecretReference reference, String credentialKey, char[] value) throws Exception {
        withMaster(master -> {
            service.saveDeploymentSecretRevision(new StoredApplicationSecretRevision(reference, credentialKey, MODE,
                    Instant.now()), MODE, master, value);
            return null;
        });
    }

    LifecycleObservation lifecycle(String applicationId, LifecycleAction action) throws Exception {
        var result = withMaster(master -> service.executePersistedLifecycleResultWithStoredPassword(applicationId,
                action, master));
        assertTrue(result.accepted(), () -> action + " was rejected: " + result);
        return result.observation().orElseThrow();
    }

    /** Stops only applications registered in this test database through the product. / 通过产品停止仅登记在本测试数据库中的应用。 */
    void stopTestApplications() throws Exception {
        AssertionError failures = new AssertionError("test application cleanup failed");
        for (var application : service.listManagedApplications()) {
            try {
                var disabled = lifecycle(application.id(), LifecycleAction.DISABLE_AUTOSTART);
                assertTrue(disabled.ownershipVerified(), disabled::toString);
                assertEquals(gold.debug.windowstolinux.shared.model.lifecycle.AutostartState.DISABLED,
                        disabled.autostartState(), disabled::toString);
                var stopped = lifecycle(application.id(), LifecycleAction.STOP);
                assertTrue(stopped.ownershipVerified(), stopped::toString);
                assertEquals(gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.STOPPED,
                        stopped.runtimeState(), stopped::toString);
                System.out.printf("LIVE_STOPPED application=%s autostart=%s%n",
                        application.id(), stopped.autostartState());
            } catch (Exception | AssertionError failure) {
                failures.addSuppressed(failure);
            }
        }
        if (failures.getSuppressed().length > 0) throw failures;
    }

    private char[] master() {
        return masterPassword.clone();
    }

    private <T> T withMaster(MasterOperation<T> operation) throws Exception {
        char[] copiedMasterPassword = master();
        try {
            return operation.apply(copiedMasterPassword);
        } finally {
            Arrays.fill(copiedMasterPassword, '\0');
        }
    }

    @FunctionalInterface
    private interface MasterOperation<T> {
        /** Performs the {@code apply} operation. / 执行 {@code apply} 操作。 */
        T apply(char[] copiedMasterPassword) throws Exception;
    }

    /** Closes this resource. / 关闭此资源。 */
    @Override
    public void close() {
        Arrays.fill(masterPassword, '\0');
        persistence.close();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }
}
