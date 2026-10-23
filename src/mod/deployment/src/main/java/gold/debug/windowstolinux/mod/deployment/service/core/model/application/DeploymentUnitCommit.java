package gold.debug.windowstolinux.mod.deployment.service.core.model.application;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;

/** Exact data-store input for one verified component publication, never a remote command. / 一个已验证组件发布的精确存储输入，绝不是远端命令。
 * @param unit reviewed unit identity / 已审阅单元身份
 * @param runtime reviewed activation contract / 已审阅激活契约
 * @param release confirmed release identity / 已确认发布身份
 * @param configuration exact immutable configuration / 精确不可变配置
 * @param secrets exact secret revision set, including explicit empty / 精确秘密修订集合，包括显式空集合 */
public record DeploymentUnitCommit(DeploymentUnitIdentity unit, DeploymentUnitRuntimeConfig runtime,
                                   DeploymentCurrentRelease release, DeploymentConfigSnapshot configuration,
                                   List<DeploymentSecretRef> secrets) {
    /** Requires all records to belong to the same unit and copies the exact secret set. / 要求全部记录属于同一单元并复制精确秘密集合。
     * @param unit reviewed unit identity / 已审阅单元身份
     * @param runtime reviewed activation contract / 已审阅激活契约
     * @param release confirmed release identity / 已确认发布身份
     * @param configuration exact immutable configuration / 精确不可变配置
     * @param secrets exact secret revision set, including explicit empty / 精确秘密修订集合，包括显式空集合 */
    public DeploymentUnitCommit {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(runtime);
        Objects.requireNonNull(release);
        Objects.requireNonNull(configuration);
        secrets = List.copyOf(secrets);
        if (!unit.id().equals(release.unitId()) || !unit.id().equals(configuration.applicationId()))
            throw new IllegalArgumentException("Publish records must belong to their deployment unit");
        if (new HashSet<>(secrets).size() != secrets.size())
            throw new IllegalArgumentException("Publish secret references must be unique");
    }
}
