package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input;


import gold.debug.windowstolinux.shared.linux.protocol.RemoteRuntimeConfiguration;
import java.nio.charset.StandardCharsets;

/** Renders immutable runtime configuration for systemd and container consumers. / 为 systemd 与容器使用方渲染不可变运行时配置。 */
public final class DeploymentConfigurationRenderer {
    private DeploymentConfigurationRenderer() { }

    static byte[] systemd(RemoteRuntimeConfiguration snapshot) {
        StringBuilder content = new StringBuilder();
        snapshot.entries().forEach((key, value) -> content.append(key).append("=\"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\"\n"));
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] container(RemoteRuntimeConfiguration snapshot) {
        StringBuilder content = new StringBuilder();
        snapshot.entries().forEach((key, value) -> content.append(key).append('=')
                .append(value).append('\n'));
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

}
