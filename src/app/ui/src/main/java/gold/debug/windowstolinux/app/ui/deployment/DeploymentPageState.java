package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.app.service.source.SourcePreparation;

import java.util.Objects;

/**
 * Represents an immutable {@code DeploymentPageState} value.
 *
 * <p>表示不可变的 {@code DeploymentPageState} 值。
 *
 * @param healthMode the {@code healthMode} value / {@code healthMode} 值
 * @param healthEndpoint the {@code healthEndpoint} value / {@code healthEndpoint} 值
 * @param expectedHttpStatus the {@code expectedHttpStatus} value / {@code expectedHttpStatus} 值
 * @param healthTimeoutSeconds the {@code healthTimeoutSeconds} value / {@code healthTimeoutSeconds} 值
 * @param tcpStabilitySeconds the {@code tcpStabilitySeconds} value / {@code tcpStabilitySeconds} 值
 * @param userAccessUrl the {@code userAccessUrl} value / {@code userAccessUrl} 值
 * @param rootBuild the {@code rootBuild} value / {@code rootBuild} 值
 * @param output the {@code output} value / {@code output} 值
 * @param preparation the {@code preparation} value / {@code preparation} 值
 */
public record DeploymentPageState(
        String healthMode,
        String healthEndpoint,
        String expectedHttpStatus,
        String healthTimeoutSeconds,
        String tcpStabilitySeconds,
        String userAccessUrl,
        boolean rootBuild,
        String output,
        SourcePreparation preparation
) {
    /**
     * Creates a {@code DeploymentPageState} instance.
     *
     * <p>创建 {@code DeploymentPageState} 实例。
     *
     * @param healthMode the {@code healthMode} value / {@code healthMode} 值
     * @param healthEndpoint the {@code healthEndpoint} value / {@code healthEndpoint} 值
     * @param expectedHttpStatus the {@code expectedHttpStatus} value / {@code expectedHttpStatus} 值
     * @param healthTimeoutSeconds the {@code healthTimeoutSeconds} value / {@code healthTimeoutSeconds} 值
     * @param tcpStabilitySeconds the {@code tcpStabilitySeconds} value / {@code tcpStabilitySeconds} 值
     * @param userAccessUrl the {@code userAccessUrl} value / {@code userAccessUrl} 值
     * @param rootBuild the {@code rootBuild} value / {@code rootBuild} 值
     * @param output the {@code output} value / {@code output} 值
     * @param preparation the {@code preparation} value / {@code preparation} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DeploymentPageState {
        Objects.requireNonNull(healthMode, "healthMode");
        Objects.requireNonNull(healthEndpoint, "healthEndpoint");
        Objects.requireNonNull(expectedHttpStatus, "expectedHttpStatus");
        Objects.requireNonNull(healthTimeoutSeconds, "healthTimeoutSeconds");
        Objects.requireNonNull(tcpStabilitySeconds, "tcpStabilitySeconds");
        Objects.requireNonNull(userAccessUrl, "userAccessUrl");
        Objects.requireNonNull(output, "output");
    }
}
