package gold.debug.windowstolinux.shared.linux.sshd.capability;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.capability.ManagedHostCapabilityProbe;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;

/**
 * Collects typed Linux capabilities through the authenticated SSH command executor.
 * <p>通过已认证的 SSH 命令执行器采集类型化 Linux 能力。
 */
public final class SshdCapabilityCollector {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Host fingerprint.
     * <p>主机指纹。
     */
    private final String hostFingerprint;

    /**
     * Validates and binds the inputs required by sshd capability collector.
     * <p>校验并绑定Sshd能力Collector所需输入。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param hostFingerprint host fingerprint / 主机指纹
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SshdCapabilityCollector(SshCommandExecutor commands, String hostFingerprint) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.hostFingerprint = Objects.requireNonNull(hostFingerprint, "hostFingerprint");
    }

    /**
     * Collects server capability facts.
     * <p>采集服务器能力事实。
     *
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public ServerCapabilityFacts collect() throws LinuxOperationException {
        var result = CapabilityReadExecutor.collect(commands, LinuxOperationFailureType.CAPABILITY_COLLECTION_FAILED,
                ManagedHostCapabilityProbe.render(ManagedHelperBundle.PATH));
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.CAPABILITY_COLLECTION_FAILED,
                    "Failed to collect target capabilities: " + result.failureEvidence());
        }
        Map<String, String> values = gold.debug.windowstolinux.shared.linux.command.CommandText.lines(result.output());
        return new ServerCapabilityFacts(values.getOrDefault("OS", "unknown"), values.getOrDefault("ARCH", "unknown"),
                "1".equals(values.get("SYSTEMD")), "1".equals(values.get("JAVA21")), "1".equals(values.get("MAVEN")),
                "1".equals(values.get("TAR")), "1".equals(values.get("CURL")), "1".equals(values.get("SS")),
                "1".equals(values.get("LIMIT_TOOLS")), "1".equals(values.get("SUDO")),
                protocolVersion(values.get("HELPER_PROTOCOL")),
                gold.debug.windowstolinux.shared.linux.command.CommandText.parseLong(values.get("FREE")),
                "SSH host fingerprint verified: " + hostFingerprint);
    }

    /**
     * Parses a one-to-three-digit helper protocol version, using zero for missing or malformed input.
     * <p>解析一至三位数字的 helper 协议版本；缺失或格式无效输入使用零。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return a one-to-three-digit helper protocol version, using zero for missing or malformed input / 一至三位数字的 helper 协议版本；缺失或格式无效输入使用零
     */
    private static int protocolVersion(String value) {
        return value != null && value.matches("[0-9]{1,3}") ? Integer.parseInt(value) : 0;
    }

}
