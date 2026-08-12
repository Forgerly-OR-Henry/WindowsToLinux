package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses the bounded desktop build/runtime configuration notation. / 解析桌面端有界构建/运行配置记法。 */
final class DeploymentConfigurationParser {
    private DeploymentConfigurationParser() { }

    /** Parses semicolon-separated entries, defaulting unprefixed keys to runtime scope. / 解析分号分隔项，并将无前缀键默认为运行范围。 */
    static List<ConfigurationEntry> parse(String input) {
        List<ConfigurationEntry> entries = new ArrayList<>();
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
            ConfigurationScope scope = ConfigurationScope.RUNTIME;
            int scopeSeparator = declaredKey.indexOf(':');
            if (scopeSeparator >= 0) {
                String declaredScope = declaredKey.substring(0, scopeSeparator).trim().toUpperCase(Locale.ROOT);
                if (!"BUILD".equals(declaredScope) && !"RUNTIME".equals(declaredScope)) {
                    throw new IllegalArgumentException("configuration scope must be build or runtime");
                }
                scope = ConfigurationScope.valueOf(declaredScope);
                declaredKey = declaredKey.substring(scopeSeparator + 1).trim();
            }
            entries.add(new ConfigurationEntry(declaredKey, scope,
                    configurationValue(value.substring(separator + 1).trim())));
        }
        return List.copyOf(entries);
    }

    private static ConfigurationValue configurationValue(String value) {
        if (value.matches("-?[0-9]+")) {
            return new ConfigurationValue.Number(Long.parseLong(value));
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return new ConfigurationValue.Flag(Boolean.parseBoolean(value));
        }
        return new ConfigurationValue.Text(value);
    }
}
