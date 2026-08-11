package gold.debug.windowstolinux.shared.config.revision;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;

import java.util.Objects;

/**
 * One type-checked non-secret value inside an immutable configuration snapshot.
 *
 * <p>不可变配置快照中的一个经过类型检查的非秘密值。
 *
 * @param key the declared key / 声明的键
 * @param scope the consumption scope / 使用范围
 * @param value the typed value / 类型化值
 */
public record ConfigurationEntry(String key, ConfigurationScope scope, ConfigurationValue value) {
    /**
     * Creates a {@code ConfigurationEntry} instance.
     *
     * <p>创建 {@code ConfigurationEntry} 实例。
     */
    public ConfigurationEntry {
        key = Objects.requireNonNull(key, "key").trim();
        if (!key.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("configuration keys must be uppercase environment-style identifiers");
        }
        scope = Objects.requireNonNull(scope, "scope");
        value = Objects.requireNonNull(value, "value");
        if (key.endsWith("_PASSWORD") || key.endsWith("_SECRET") || key.endsWith("_TOKEN") || key.endsWith("_KEY")) {
            throw new IllegalArgumentException("secret-like keys must use SecretReference instead of normal configuration");
        }
        if ("PORT".equals(key) && (!(value instanceof ConfigurationValue.Number number)
                || number.value() < 1 || number.value() > 65535)) {
            throw new IllegalArgumentException("PORT must be an integer between 1 and 65535");
        }
    }
}
