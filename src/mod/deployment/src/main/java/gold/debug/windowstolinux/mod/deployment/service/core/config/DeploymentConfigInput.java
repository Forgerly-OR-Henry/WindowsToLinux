package gold.debug.windowstolinux.mod.deployment.service.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;

/**
 * Parses the bounded desktop build/runtime configuration notation. / 解析桌面端有界构建/运行配置记法。
 */
public final class DeploymentConfigInput {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentConfigInput() {
    }

    /**
     * Parses semicolon-separated entries, defaulting unprefixed keys to runtime scope. / 解析分号分隔项，并将无前缀键默认为运行范围。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return semicolon-separated entries, defaulting unprefixed keys to runtime scope / 分号分隔项，并将无前缀键默认为运行范围
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static List<DeploymentConfigEntry> parse(String input) {
        List<DeploymentConfigEntry> entries = new ArrayList<>();
        for (String item : input.split(";")) {
            String value = item.trim();
            if (value.isBlank()) {
                continue;
            }
            int separator = value.indexOf('=');
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException("configuration entry must contain a key and value");
            }
            String declaredKey = value.substring(0, separator).trim();
            DeploymentConfigScopeType scope = DeploymentConfigScopeType.RUNTIME;
            int scopeSeparator = declaredKey.indexOf(':');
            if (scopeSeparator >= 0) {
                String declaredScope = declaredKey.substring(0, scopeSeparator).trim().toUpperCase(Locale.ROOT);
                if (!"BUILD".equals(declaredScope) && !"RUNTIME".equals(declaredScope)) {
                    throw new IllegalArgumentException("configuration scope must be build or runtime");
                }
                scope = DeploymentConfigScopeType.valueOf(declaredScope);
                declaredKey = declaredKey.substring(scopeSeparator + 1).trim();
            }
            entries.add(new DeploymentConfigEntry(declaredKey, scope,
                configurationValue(value.substring(separator + 1).trim())));
        }
        return List.copyOf(entries);
    }

    /**
     * Builds configuration value from the supplied configuration value inputs.
     * <p>根据所提供配置内容输入构建配置内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return configuration value from the supplied configuration value inputs / 根据所提供配置内容输入构建配置内容
     */
    private static DeploymentConfigValue configurationValue(String value) {
        if (value.matches("-?[0-9]+")) {
            return new DeploymentConfigValue.Number(Long.parseLong(value));
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return new DeploymentConfigValue.Flag(Boolean.parseBoolean(value));
        }
        return new DeploymentConfigValue.Text(value);
    }

    /**
     * Parses exact public secret references for configuration and deployment. / 为配置和部署解析精确公开秘密引用。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return exact public secret references for configuration and deployment / 为配置和部署解析精确公开秘密引用
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static List<DeploymentSecretRef> secrets(String input) {
        List<DeploymentSecretRef> references = new ArrayList<>();
        for (String item : input.split(";")) {
            String value = item.trim();
            if (value.isEmpty())
                continue;
            int separator = value.lastIndexOf(':');
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException("secret reference must contain an identifier and revision");
            }
            references.add(new DeploymentSecretRef(value.substring(0, separator).trim(),
                Long.parseLong(value.substring(separator + 1).trim())));
        }
        return List.copyOf(references);
    }

}
