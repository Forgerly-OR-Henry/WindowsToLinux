package gold.debug.windowstolinux.app.db.entity;

import java.time.Instant;
import java.util.Objects;

/**
 * Last successfully published release identity for one managed application.
 *
 *  <p>某个受管应用最后成功发布的发布身份。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param releaseSha256 the release identity digest / 发布身份摘要
 * @param publishedAt published at / 已发布时刻
 */
public record CurrentRelease(String applicationId, String releaseSha256, Instant publishedAt) {
    /**
     * Validates and binds the inputs required by current release.
     * <p>校验并绑定当前发布所需输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param releaseSha256 the release identity digest / 发布身份摘要
     * @param publishedAt published at / 已发布时刻
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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
