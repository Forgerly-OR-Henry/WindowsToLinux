package gold.debug.windowstolinux.app.main.bootstrap;

import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
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
final class ReviewedMavenAcceptanceSupport {
    private static final AtomicLong CONFIGURATION_REVISION = new AtomicLong(10_000);

    private ReviewedMavenAcceptanceSupport() {
    }

    static ReviewedSourcePreparation prepare(DesktopApplicationService service, Path source) throws Exception {
        return service.prepareReviewedSource(source, DeploymentProjectType.SPRING_BOOT);
    }

    static ReviewedDeploymentRequest request(
            DesktopApplicationService service,
            ReviewedSourcePreparation preparation,
            ServerIdentity server,
            HealthCheck health,
            Optional<UserAccessUrl> userAccessUrl,
            BuildLimits limits,
            boolean rootBuild
    ) throws Exception {
        String applicationId = preparation.assessment().facts().orElseThrow().applicationId();
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create(applicationId,
                CONFIGURATION_REVISION.incrementAndGet(), "maven-acceptance-v1", Instant.now(), List.of());
        service.saveDeploymentConfigurationSnapshot(configuration);
        return service.createReviewedDeploymentRequest(preparation, server, configuration, List.of(),
                new DeploymentRuntimeSpecification.SpringBoot(health), userAccessUrl, limits, rootBuild, true, true);
    }
}
