package gold.debug.windowstolinux.shared.model.managed;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;

import gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode;

import java.util.Objects;
import java.util.Optional;

/**
 * The last successfully deployed runtime contract for one managed application. It is mutable across successful deployments, unlike the application ownership identity itself.
 *
 * <p>某个受管应用最后一次成功部署的运行时契约。与应用资源归属身份本身不同，它会随成功部署而变化。
 *
 * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
 * @param userAccessUrl the {@code userAccessUrl} value / {@code userAccessUrl} 值
 */
public record ManagedApplicationRuntimeConfiguration(
        HealthCheck healthCheck,
        Optional<UserAccessUrl> userAccessUrl,
        RuntimeIdentityMode identityPolicy
) {
    /** Reads historical health-only runtime state without inventing an identity policy. / 读取历史健康运行状态，不推断缺失的身份策略。 */
    public ManagedApplicationRuntimeConfiguration(HealthCheck healthCheck, Optional<UserAccessUrl> userAccessUrl) {
        this(healthCheck, userAccessUrl, RuntimeIdentityMode.LEGACY_UNSPECIFIED);
    }

    /**
     * Creates a {@code ManagedApplicationRuntimeConfiguration} instance.
     *
     * <p>创建 {@code ManagedApplicationRuntimeConfiguration} 实例。
     *
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @param userAccessUrl the {@code userAccessUrl} value / {@code userAccessUrl} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ManagedApplicationRuntimeConfiguration {
        identityPolicy = Objects.requireNonNull(identityPolicy, "identityPolicy");
        healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        userAccessUrl = Objects.requireNonNull(userAccessUrl, "userAccessUrl");
        if (healthCheck instanceof HealthCheck.Http && userAccessUrl.isEmpty()) {
            throw new IllegalArgumentException("HTTP deployment requires an explicit user access URL");
        }
        if (healthCheck instanceof HealthCheck.Tcp && userAccessUrl.isPresent()) {
            throw new IllegalArgumentException("TCP deployment must not claim an HTTP user access URL");
        }
    }
}
