package gold.debug.windowstolinux.app.db.entity;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;

/**
 * One component state persisted after a verified deployment transaction. / 经验证部署事务后持久化的一个组件状态。
 *
 * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
 * @param runtimeConfiguration runtime configuration / 运行时配置
 * @param release release / 发布
 * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
 * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
 */
public record SuccessfulManagedDeployment(ManagedApplication application,
        ManagedApplicationRuntimeConfiguration runtimeConfiguration, CurrentRelease release,
        ConfigurationSnapshot configuration, List<SecretReference> secretReferences) {
    /**
     * Validates exact component identity, configuration, and secret binding. / 验证精确组件身份、配置与秘密绑定。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtimeConfiguration runtime configuration / 运行时配置
     * @param release release / 发布
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SuccessfulManagedDeployment {
        application = Objects.requireNonNull(application, "application");
        runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        release = Objects.requireNonNull(release, "release");
        configuration = Objects.requireNonNull(configuration, "configuration");
        secretReferences = List.copyOf(Objects.requireNonNull(secretReferences, "secretReferences"));
        if (!application.id().equals(release.applicationId())
                || !application.id().equals(configuration.applicationId())) {
            throw new IllegalArgumentException(
                    "successful release and configuration must belong to its managed application");
        }
        if (secretReferences.stream().distinct().count() != secretReferences.size()) {
            throw new IllegalArgumentException("successful deployment secret references must be unique");
        }
    }
}
