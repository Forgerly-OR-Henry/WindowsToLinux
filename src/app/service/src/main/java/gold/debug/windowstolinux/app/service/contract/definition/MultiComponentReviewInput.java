package gold.debug.windowstolinux.app.service.contract.definition;

import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * User-reviewed mutable inputs for one statically admitted component. / 一个静态准入组件由用户审阅的可变输入。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
 * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
 * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
 * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
 * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
 * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
 * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
 */
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
    /**
     * Validates the bounded component review. / 验证有界组件审阅。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Creates an input whose database scope has not yet been reviewed. / 创建数据库范围尚未审阅的输入。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     */
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
