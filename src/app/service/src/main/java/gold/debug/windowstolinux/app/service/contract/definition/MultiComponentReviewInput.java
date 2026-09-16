package gold.debug.windowstolinux.app.service.contract.definition;

import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** User-reviewed mutable inputs for one statically admitted component. / 一个静态准入组件由用户审阅的可变输入。 */
public record MultiComponentReviewInput(
        String componentId,
        ConfigurationSnapshot configuration,
        List<SecretReference> secretReferences,
        Optional<List<ManagedDatabaseBinding>> databaseBindings,
        Optional<UserAccessUrl> userAccessUrl,
        BuildLimitConfiguration limits,
        boolean containerDaemonRiskAccepted,
        boolean experimentalAdapterRiskAccepted
) {
    /** Validates the bounded component review. / 验证有界组件审阅。 */
    public MultiComponentReviewInput {
        componentId = Objects.requireNonNull(componentId, "componentId").trim();
        if (!componentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("componentId must be a bounded managed identifier");
        }
        configuration = Objects.requireNonNull(configuration, "configuration");
        secretReferences = List.copyOf(Objects.requireNonNull(secretReferences, "secretReferences"));
        databaseBindings = Objects.requireNonNull(databaseBindings, "databaseBindings")
                .map(values -> List.copyOf(values.stream()
                        .map(value -> Objects.requireNonNull(value, "database binding"))
                        .sorted(java.util.Comparator.comparing(ManagedDatabaseBinding::databaseId)).toList()));
        userAccessUrl = Objects.requireNonNull(userAccessUrl, "userAccessUrl");
        limits = Objects.requireNonNull(limits, "limits");
    }

    /** Creates an input whose database scope has not yet been reviewed. / 创建数据库范围尚未审阅的输入。 */
    public MultiComponentReviewInput(
            String componentId,
            ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences,
            Optional<UserAccessUrl> userAccessUrl,
            BuildLimitConfiguration limits,
            boolean containerDaemonRiskAccepted,
            boolean experimentalAdapterRiskAccepted
    ) {
        this(componentId, configuration, secretReferences, Optional.empty(), userAccessUrl, limits,
                containerDaemonRiskAccepted, experimentalAdapterRiskAccepted);
    }
}
