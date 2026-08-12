package gold.debug.windowstolinux.app.db.entity;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;

import java.util.List;
import java.util.Objects;

/** One component state persisted after a verified deployment transaction. / 经验证部署事务后持久化的一个组件状态。 */
public record SuccessfulManagedDeployment(
        ManagedApplication application,
        ManagedApplicationRuntimeConfiguration runtimeConfiguration,
        CurrentRelease release,
        List<SecretReference> secretReferences
) {
    /** Validates exact component identity and secret binding. / 验证精确组件身份与秘密绑定。 */
    public SuccessfulManagedDeployment {
        application = Objects.requireNonNull(application, "application");
        runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        release = Objects.requireNonNull(release, "release");
        secretReferences = List.copyOf(Objects.requireNonNull(secretReferences, "secretReferences"));
        if (!application.id().equals(release.applicationId())) {
            throw new IllegalArgumentException("successful release must belong to its managed application");
        }
        if (secretReferences.stream().distinct().count() != secretReferences.size()) {
            throw new IllegalArgumentException("successful deployment secret references must be unique");
        }
    }
}
