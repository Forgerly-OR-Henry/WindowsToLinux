package gold.debug.windowstolinux.mod.deployment.service.core.config;

import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentConfigurationException;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;

/**
 * One type-checked non-secret value inside an immutable configuration snapshot.
 *
 *  <p>不可变配置快照中的一个经过类型检查的非秘密值。
 *
 * @param key the declared key / 声明的键
 * @param scope the consumption scope / 使用范围
 * @param value the typed value / 类型化值
 */
public record DeploymentConfigurationEntry(String key, DeploymentConfigurationScopeType scope,
                                           DeploymentConfigurationValue value) {
    /**
     * Validates and binds the inputs required by configuration entry.
     * <p>校验并绑定配置条目所需输入。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentConfigurationEntry {
        key = Objects.requireNonNull(key, "key").trim();
        if (!key.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw DeploymentConfigurationException.create("configuration.entry.key-invalid",
                "Configuration keys must use uppercase environment-style identifiers");
        }
        scope = Objects.requireNonNull(scope, "scope");
        value = Objects.requireNonNull(value, "value");
        if (key.endsWith("_PASSWORD") || key.endsWith("_SECRET") || key.endsWith("_TOKEN") || key.endsWith("_KEY")) {
            throw DeploymentConfigurationException.create("configuration.entry.secret-like-key",
                "Secret-like keys must use a DeploymentSecretReference");
        }
        if ("PORT".equals(key) && (!(value instanceof DeploymentConfigurationValue.Number number) || number.value() < 1
            || number.value() > 65535)) {
            throw DeploymentConfigurationException.create("configuration.entry.port-invalid",
                "PORT must be an integer between 1 and 65535");
        }
    }
}
