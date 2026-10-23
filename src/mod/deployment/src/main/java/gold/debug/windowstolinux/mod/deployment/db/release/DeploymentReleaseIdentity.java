package gold.debug.windowstolinux.mod.deployment.db.release;

import java.util.*;

/** Immutable identity of one stored complete application release. / 单个完整应用发布的不可变存储身份。
 * @param id original release UUID / 原发布UUID
 * @param sha256 complete manifest digest / 完整清单摘要 */
public record DeploymentReleaseIdentity(UUID id, String sha256) {
    /** Validates the original identity without normalizing its digest. / 校验原身份，不规范化摘要。
     * @param id original release UUID / 原发布UUID
     * @param sha256 complete manifest digest / 完整清单摘要 */
    public DeploymentReleaseIdentity {
        Objects.requireNonNull(id);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid application release digest");
    }
}
