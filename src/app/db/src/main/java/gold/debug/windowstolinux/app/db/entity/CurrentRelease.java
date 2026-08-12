package gold.debug.windowstolinux.app.db.entity;

import java.time.Instant;
import java.util.Objects;

/**
 * Last successfully published release identity for one managed application.
 *
 * <p>某个受管应用最后成功发布的发布身份。
 *
 * @param applicationId the {@code applicationId} value / {@code applicationId} 值
 * @param releaseSha256 the release identity digest / 发布身份摘要
 * @param publishedAt the {@code publishedAt} value / {@code publishedAt} 值
 */
public record CurrentRelease(String applicationId, String releaseSha256, Instant publishedAt) {
    /**
     * Creates a {@code CurrentRelease} instance.
     *
     * <p>创建 {@code CurrentRelease} 实例。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param releaseSha256 the release identity digest / 发布身份摘要
     * @param publishedAt the {@code publishedAt} value / {@code publishedAt} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public CurrentRelease {
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        releaseSha256 = Objects.requireNonNull(releaseSha256, "releaseSha256");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}") || !releaseSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("current release identity is invalid");
        }
    }
}
