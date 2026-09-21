package gold.debug.windowstolinux.shared.backup.format;

import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;

import java.util.Objects;

/**
 * Exact non-secret component state required to activate and durably adopt a backup. / 激活并持久接管备份所需的精确无秘密组件状态。
 *
 * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
 * @param resourceBindings resource bindings / 资源绑定集合
 * @param runtimeConfiguration runtime configuration / 运行时配置
 */
public record BackupConfigurationDocument(
        ConfigurationSnapshot configuration,
        ManagedComponentResourceBindings resourceBindings,
        ManagedApplicationRuntimeConfiguration runtimeConfiguration
) {
    /**
     * Requires an explicit database review state rather than an unknown historical value. / 要求显式数据库审阅状态而非历史未知值。
     *
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param resourceBindings resource bindings / 资源绑定集合
     * @param runtimeConfiguration runtime configuration / 运行时配置
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupConfigurationDocument {
        configuration = Objects.requireNonNull(configuration, "configuration");
        resourceBindings = Objects.requireNonNull(resourceBindings, "resourceBindings");
        runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        if (resourceBindings.databaseBindings().isEmpty()) {
            throw new IllegalArgumentException("backup activation requires an explicit database review state");
        }
    }
}
