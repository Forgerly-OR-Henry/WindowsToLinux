package gold.debug.windowstolinux.mod.deployment.service.core.model.application;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;

/** Immutable component release and resource binding. / 不可变组件发布与资源绑定。
 * @param unitId exact original component unit identity / 精确原组件单元身份
 * @param releaseSha256 original component release digest / 原组件发布摘要
 * @param configurationRevision original immutable configuration revision / 原不可变配置修订
 * @param configurationSha256 original immutable configuration digest / 原不可变配置摘要
 * @param secrets complete original secret revision set / 完整原秘密修订集合 */
public record DeploymentReleaseBinding(String unitId, String releaseSha256, int configurationRevision,
                                       String configurationSha256,
                                       List<DeploymentSecretRef> secrets) {
    /** Validates and canonicalizes an unordered set without altering identities. / 校验并规范化无序集合，不改变身份。
     * @param unitId exact unit identity / 精确单元身份
     * @param releaseSha256 original component release digest / 原组件发布摘要
     * @param configurationRevision original configuration revision / 原配置修订
     * @param configurationSha256 original configuration digest / 原配置摘要
     * @param secrets original complete secret revision set / 原完整秘密修订集合 */
    public DeploymentReleaseBinding {
        if (unitId == null || !unitId.matches("[a-z0-9][a-z0-9-]{0,62}") || configurationRevision < 1)
            throw new IllegalArgumentException("Invalid release binding identity");
        for (var digest : List.of(releaseSha256, configurationSha256))
            if (!digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid release binding digest");
        secrets = List.copyOf(secrets).stream().sorted(Comparator.comparing(DeploymentSecretRef::identifier)
            .thenComparingLong(DeploymentSecretRef::revision)).toList();
        if (secrets.size() != new HashSet<>(secrets).size()
            || secrets.stream().anyMatch(s -> s.revision() > Integer.MAX_VALUE))
            throw new IllegalArgumentException("Invalid release secret set");
    }
}
