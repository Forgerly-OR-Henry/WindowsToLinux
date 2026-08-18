package gold.debug.windowstolinux.shared.deploy.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * A per-source, per-server, per-application user approval; it is never reusable.
 *
 * <p>按源码、服务器和应用分别绑定的用户批准，绝不可复用。
 *
 * @param applicationId the {@code applicationId} value / {@code applicationId} 值
 * @param sourceSha256 the {@code sourceSha256} value / {@code sourceSha256} 值
 * @param serverId the {@code serverId} value / {@code serverId} 值
 * @param rootBuildAccepted the {@code rootBuildAccepted} value / {@code rootBuildAccepted} 值
 * @param confirmedAt the {@code confirmedAt} value / {@code confirmedAt} 值
 */
public record DeploymentApproval(
        String applicationId,
        String sourceSha256,
        String serverId,
        boolean rootBuildAccepted,
        Instant confirmedAt
) {
    /**
     * Creates a {@code DeploymentApproval} instance.
     *
     * <p>创建 {@code DeploymentApproval} 实例。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param sourceSha256 the {@code sourceSha256} value / {@code sourceSha256} 值
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @param rootBuildAccepted the {@code rootBuildAccepted} value / {@code rootBuildAccepted} 值
     * @param confirmedAt the {@code confirmedAt} value / {@code confirmedAt} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DeploymentApproval {
        applicationId = identifier(applicationId, "applicationId");
        serverId = identifier(serverId, "serverId");
        sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        if (!sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceSha256 must be lowercase SHA-256");
        }
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
    }

    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
