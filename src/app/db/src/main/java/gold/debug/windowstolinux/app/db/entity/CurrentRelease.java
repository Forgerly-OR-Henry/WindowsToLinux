package gold.debug.windowstolinux.app.db.entity;

import java.time.Instant;
import java.util.Objects;

/**
 * Last successfully published artifact version for one managed application.
 *
 * <p>某个受管应用最后成功发布的制品版本。
 *
 * @param applicationId the {@code applicationId} value / {@code applicationId} 值
 * @param artifactSha256 the {@code artifactSha256} value / {@code artifactSha256} 值
 * @param publishedAt the {@code publishedAt} value / {@code publishedAt} 值
 */
public record CurrentRelease(String applicationId, String artifactSha256, Instant publishedAt) {
    /**
     * Creates a {@code CurrentRelease} instance.
     *
     * <p>创建 {@code CurrentRelease} 实例。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param artifactSha256 the {@code artifactSha256} value / {@code artifactSha256} 值
     * @param publishedAt the {@code publishedAt} value / {@code publishedAt} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public CurrentRelease {
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        artifactSha256 = Objects.requireNonNull(artifactSha256, "artifactSha256");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}") || !artifactSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("current release identity is invalid");
        }
    }
}
