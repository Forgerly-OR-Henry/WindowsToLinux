package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Shared Reviewed-only setup for opt-in Maven Spring Boot acceptance tests. / 可选 Maven Spring Boot 验收测试共用的仅 Reviewed 设置。 */
final class ReviewedMavenAcceptanceFixture {
    private static final AtomicLong CONFIGURATION_REVISION = new AtomicLong(10_000);

    private ReviewedMavenAcceptanceFixture() {
    }

    static ReviewedSourcePreparation prepare(DesktopApplicationFacade service, Path source) throws Exception {
        return service.prepareReviewedSource(source, DeploymentProjectType.SPRING_BOOT);
    }

    static ReviewedDeploymentRequest request(
            DesktopApplicationFacade service,
            ReviewedSourcePreparation preparation,
            ServerIdentity server,
            HealthCheck health,
            Optional<UserAccessUrl> userAccessUrl,
            BuildLimitConfiguration limits,
            boolean rootBuild
    ) throws Exception {
        String applicationId = preparation.assessment().facts().orElseThrow().applicationId();
        long revision = CONFIGURATION_REVISION.incrementAndGet();
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create(applicationId,
                revision, "maven-acceptance-v1", Instant.now(), List.of(new ConfigurationEntry(
                "ACCEPTANCE_RUN_ID", ConfigurationScope.RUNTIME,
                new ConfigurationValue.Text("maven-acceptance-" + revision))));
        service.saveDeploymentConfigurationSnapshot(configuration);
        return service.createReviewedDeploymentRequest(preparation, server, configuration, List.of(),
                new DeploymentRuntimeSpecification.SpringBoot(health), userAccessUrl, limits, rootBuild, true, true);
    }
}
