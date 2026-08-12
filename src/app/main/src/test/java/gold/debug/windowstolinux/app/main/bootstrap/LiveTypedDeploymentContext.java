package gold.debug.windowstolinux.app.main.bootstrap;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentStep;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.git.snapshot.GitSourceRequest;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owns one ephemeral desktop database while every remote action still crosses the production application service. / 持有单个临时桌面数据库，同时确保每个远端动作仍通过生产应用服务。 */
final class LiveTypedDeploymentContext implements AutoCloseable {
    private static final CredentialStorageMode MODE = CredentialStorageMode.MASTER_PASSWORD;
    private final DesktopPersistence persistence;
    private final char[] masterPassword;
    private final ServerProfile profile;
    private final ServerIdentity server;
    final DesktopApplicationService service;

    LiveTypedDeploymentContext(Path root) throws Exception {
        String host = requiredProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "root");
        assertTrue(!"root".equals(username) || Boolean.getBoolean("managed.root-build"),
                "root SSH requires managed.root-build=true");
        char[] sshPassword = requiredEnvironment("WINDOWSTOLINUX_TEST_SSH_PASSWORD").toCharArray();
        this.masterPassword = System.getenv().getOrDefault(
                "WINDOWSTOLINUX_TEST_MASTER_PASSWORD", "typed-live-acceptance-master").toCharArray();
        this.persistence = DesktopPersistence.open(root.resolve("desktop-data"));
        this.service = new DesktopApplicationService(persistence, root.resolve("work"), new SshdLinuxGateway());
        this.profile = new ServerProfile("typed-live", host, 22, username,
                "ssh/typed-live/password", MODE);
        try {
            service.saveServerProfile(profile, MODE, master(), sshPassword);
            service.verifyServer(profile, MODE, master(), fingerprint -> true);
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

    DeploymentResult deploy(ReviewedSourcePreparation preparation, long configurationRevision,
                            List<ConfigurationEntry> entries, List<SecretReference> secrets,
                            DeploymentRuntimeSpecification runtime, Optional<UserAccessUrl> userAccessUrl) throws Exception {
        String applicationId = preparation.assessment().facts().orElseThrow().applicationId();
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create(applicationId, configurationRevision,
                "acceptance-v1", Instant.now(), entries);
        service.saveDeploymentConfigurationSnapshot(configuration);
        ReviewedDeploymentRequest request = service.createReviewedDeploymentRequest(preparation, server, configuration,
                secrets, runtime, userAccessUrl, new BuildLimits(1800, 1024, 3072,
                        8L * 1024 * 1024, 4L * 1024 * 1024 * 1024, true), true,
                runtime instanceof DeploymentRuntimeSpecification.Container container
                        && container.engine() == DeploymentRuntimeSpecification.ContainerEngine.DOCKER);
        ReviewedDeploymentPlan plan = service.planDeployment(request);
        assertEquals(request, plan.request());
        assertTrue(plan.steps().containsAll(List.of(DeploymentStep.VERIFY_SOURCE_IDENTITY,
                DeploymentStep.VERIFY_CONFIGURATION_SNAPSHOT, DeploymentStep.VERIFY_SECRET_REVISIONS,
                DeploymentStep.CHECK_HEALTH,
                DeploymentStep.ROLLBACK_ON_FAILURE)));
        return service.deployReviewedWithStoredPassword(request, profile, MODE, master(), fingerprint -> true).result();
    }

    void saveSecret(SecretReference reference, String credentialKey, char[] value) throws Exception {
        service.saveDeploymentSecretRevision(new StoredApplicationSecretRevision(reference, credentialKey, MODE,
                Instant.now()), MODE, master(), value);
    }

    LifecycleObservation lifecycle(String applicationId, LifecycleAction action) throws Exception {
        var result = service.executePersistedLifecycleResultWithStoredPassword(applicationId, action, master());
        assertTrue(result.accepted(), () -> action + " was rejected: " + result);
        return result.observation().orElseThrow();
    }

    private char[] master() {
        return masterPassword.clone();
    }

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
