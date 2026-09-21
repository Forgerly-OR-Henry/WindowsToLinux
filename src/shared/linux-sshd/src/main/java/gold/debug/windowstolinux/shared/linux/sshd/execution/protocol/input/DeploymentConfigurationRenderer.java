package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input;


import gold.debug.windowstolinux.shared.linux.protocol.RemoteRuntimeConfiguration;
import java.nio.charset.StandardCharsets;

/**
 * Renders immutable runtime configuration for systemd and container consumers. / 为 systemd 与容器使用方渲染不可变运行时配置。
 */
public final class DeploymentConfigurationRenderer {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentConfigurationRenderer() { }

    /**
     * Encodes environment entries as quoted systemd environment-file lines with backslash and quote escaping.
     * <p>将环境项编码为带引号的 systemd 环境文件行，并转义反斜杠及引号。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @return environment entries as quoted systemd environment-file lines with backslash and quote escaping / 将环境项编码为带引号的 systemd 环境文件行，并转义反斜杠及引号
     */
    static byte[] systemd(RemoteRuntimeConfiguration snapshot) {
        StringBuilder content = new StringBuilder();
        snapshot.entries().forEach((key, value) -> content.append(key).append("=\"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\"\n"));
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes reviewed environment entries as UTF-8 container environment-file lines.
     * <p>将已审阅环境项编码为 UTF-8 容器环境文件行。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @return reviewed environment entries as UTF-8 container environment-file lines / 将已审阅环境项编码为 UTF-8 容器环境文件行
     */
    static byte[] container(RemoteRuntimeConfiguration snapshot) {
        StringBuilder content = new StringBuilder();
        snapshot.entries().forEach((key, value) -> content.append(key).append('=')
                .append(value).append('\n'));
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

}
