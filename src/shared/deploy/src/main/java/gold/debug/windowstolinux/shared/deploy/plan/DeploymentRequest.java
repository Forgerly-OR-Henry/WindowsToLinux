package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.project.SourceProjectFacts;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;

import java.util.Objects;
import java.util.Optional;

/**
 * Fully reviewed input for one deployment transaction.
 *
 * <p>单次部署事务经过完整审阅的输入。
 *
 * @param application the {@code application} value / {@code application} 值
 * @param source the {@code source} value / {@code source} 值
 * @param archive the {@code archive} value / {@code archive} 值
 * @param runtimeConfiguration the {@code runtimeConfiguration} value / {@code runtimeConfiguration} 值
 * @param buildLimits the {@code buildLimits} value / {@code buildLimits} 值
 * @param approval the {@code approval} value / {@code approval} 值
 */
public record DeploymentRequest(
        ManagedApplication application,
        SourceProjectFacts source,
        SourceArchiveDescriptor archive,
        ManagedApplicationRuntimeConfiguration runtimeConfiguration,
        BuildLimits buildLimits,
        DeploymentApproval approval
) {
    /**
     * Creates a {@code DeploymentRequest} instance.
     *
     * <p>创建 {@code DeploymentRequest} 实例。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param source the {@code source} value / {@code source} 值
     * @param archive the {@code archive} value / {@code archive} 值
     * @param runtimeConfiguration the {@code runtimeConfiguration} value / {@code runtimeConfiguration} 值
     * @param buildLimits the {@code buildLimits} value / {@code buildLimits} 值
     * @param approval the {@code approval} value / {@code approval} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DeploymentRequest {
        application = Objects.requireNonNull(application, "application");
        source = Objects.requireNonNull(source, "source");
        archive = Objects.requireNonNull(archive, "archive");
        runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        buildLimits = Objects.requireNonNull(buildLimits, "buildLimits");
        approval = Objects.requireNonNull(approval, "approval");
        if (!source.applicationName().equals(application.id())) {
            throw new IllegalArgumentException("application identity must match reviewed source");
        }
        if (!approval.applicationId().equals(application.id())
                || !approval.serverId().equals(application.server().id())
                || !approval.sourceSha256().equals(archive.contentSha256())) {
            throw new IllegalArgumentException("approval must match the exact application, source archive and server");
        }
        if (buildLimits.runAsRoot() != approval.rootBuildAccepted()) {
            throw new IllegalArgumentException("root-build approval must exactly match the requested build mode");
        }
    }

    /**
     * Convenience constructor for TCP-only callers that have no HTTP access URL.
     *
     * <p>供没有 HTTP 访问 URL 的纯 TCP 调用方使用的便捷构造器。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param source the {@code source} value / {@code source} 值
     * @param archive the {@code archive} value / {@code archive} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @param buildLimits the {@code buildLimits} value / {@code buildLimits} 值
     * @param approval the {@code approval} value / {@code approval} 值
     */
    public DeploymentRequest(
            ManagedApplication application,
            SourceProjectFacts source,
            SourceArchiveDescriptor archive,
            HealthCheck healthCheck,
            BuildLimits buildLimits,
            DeploymentApproval approval
    ) {
        this(application, source, archive,
                new ManagedApplicationRuntimeConfiguration(healthCheck, Optional.empty()), buildLimits, approval);
    }

    /**
     * Performs the {@code healthCheck} operation.
     *
     * <p>执行 {@code healthCheck} 操作。
     *
     * @return the operation result / 操作结果
     */
    public HealthCheck healthCheck() {
        return runtimeConfiguration.healthCheck();
    }

    /**
     * Performs the {@code userAccessUrl} operation.
     *
     * <p>执行 {@code userAccessUrl} 操作。
     *
     * @return the optional operation result / 可选操作结果
     */
    public Optional<UserAccessUrl> userAccessUrl() {
        return runtimeConfiguration.userAccessUrl();
    }
}
