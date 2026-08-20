package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;

/** Renders immutable runtime configuration for systemd and container consumers. / 为 systemd 与容器使用方渲染不可变运行时配置。 */
public final class DeploymentConfigurationRenderer {
    private DeploymentConfigurationRenderer() { }

    static byte[] systemd(ConfigurationSnapshot snapshot) {
        StringBuilder content = new StringBuilder();
        runtimeEntries(snapshot).forEach(entry -> content.append(entry.key()).append("=\"")
                .append(entry.value().canonicalValue().replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\"\n"));
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] container(ConfigurationSnapshot snapshot) {
        StringBuilder content = new StringBuilder();
        runtimeEntries(snapshot).forEach(entry -> content.append(entry.key()).append('=')
                .append(entry.value().canonicalValue()).append('\n'));
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static java.util.List<ConfigurationEntry> runtimeEntries(ConfigurationSnapshot snapshot) {
        return Objects.requireNonNull(snapshot, "snapshot").entries().stream()
                .filter(entry -> entry.scope() == ConfigurationScope.RUNTIME)
                .sorted(Comparator.comparing(ConfigurationEntry::key)).toList();
    }
}
