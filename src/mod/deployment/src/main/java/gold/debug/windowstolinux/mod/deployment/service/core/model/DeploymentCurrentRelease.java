package gold.debug.windowstolinux.mod.deployment.service.core.model;

import java.time.Instant;
import java.util.Objects;

/** Last confirmed release identity for one deployment unit. / 一个部署单元最后确认的发布身份。
 * @param unitId exact deployment unit / 精确部署单元
 * @param sha256 original release digest / 原发布摘要
 * @param publishedAt confirmed publication time / 已确认发布时间 */
public record DeploymentCurrentRelease(String unitId, String sha256, Instant publishedAt) {
    /** Validates the existing release identity format. / 校验既有发布身份格式。
     * @param unitId exact deployment unit / 精确部署单元
     * @param sha256 original release digest / 原发布摘要
     * @param publishedAt confirmed publication time / 已确认发布时间 */
    public DeploymentCurrentRelease {
        Objects.requireNonNull(unitId);
        Objects.requireNonNull(sha256);
        Objects.requireNonNull(publishedAt);
        if (!unitId.matches("[a-z0-9][a-z0-9-]{0,62}") || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid deployment release identity");
    }
}
