package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.linux.sshd.capability.ManagedHostCapabilityProbe;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilities;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Provides the {@code SshdCapabilityCollector} implementation.
 *
 * <p>提供 {@code SshdCapabilityCollector} 实现。
 */
public final class SshdCapabilityCollector {
    private static final int READ_ONLY_ATTEMPTS = 3;
    private static final Duration READ_ONLY_RETRY_DELAY = Duration.ofMillis(250);
    private final SshCommandExecutor commands;
    private final String hostFingerprint;

    /**
     * Creates a {@code SshdCapabilityCollector} instance.
     *
     * <p>创建 {@code SshdCapabilityCollector} 实例。
     *
     * @param commands the {@code commands} value / {@code commands} 值
     * @param hostFingerprint the {@code hostFingerprint} value / {@code hostFingerprint} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SshdCapabilityCollector(SshCommandExecutor commands, String hostFingerprint) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.hostFingerprint = Objects.requireNonNull(hostFingerprint, "hostFingerprint");
    }

    /**
     * Performs the {@code collect} operation.
     *
     * <p>执行 {@code collect} 操作。
     *
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public ServerCapabilities collect() throws LinuxOperationException {
        var result = collectReadOnly(ManagedHostCapabilityProbe.render(ManagedHelperBundle.PATH));
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.capabilityCollectionFailed",
                    "Failed to collect target capabilities: " + result.failureEvidence());
        }
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        return new ServerCapabilities(
                values.getOrDefault("OS", "unknown"),
                values.getOrDefault("ARCH", "unknown"),
                "1".equals(values.get("SYSTEMD")),
                "1".equals(values.get("JAVA21")),
                "1".equals(values.get("MAVEN")),
                "1".equals(values.get("TAR")),
                "1".equals(values.get("CURL")),
                "1".equals(values.get("SS")),
                "1".equals(values.get("LIMIT_TOOLS")),
                "1".equals(values.get("SUDO")),
                protocolVersion(values.get("HELPER_PROTOCOL")),
                SshCommandExecutor.parseLong(values.get("FREE")),
                "SSH host fingerprint verified: " + hostFingerprint
        );
    }

    private static int protocolVersion(String value) {
        return value != null && value.matches("[0-9]{1,3}") ? Integer.parseInt(value) : 0;
    }

    private SshCommandExecutor.CommandResult collectReadOnly(String script) throws LinuxOperationException {
        for (int attempt = 1; attempt <= READ_ONLY_ATTEMPTS; attempt++) {
            try {
                return commands.exec(script, Duration.ofSeconds(20), true);
            } catch (LinuxOperationException failure) {
                if (!SshCommandExecutor.isTransientTransportFailure(failure) || attempt == READ_ONLY_ATTEMPTS) {
                    throw failure;
                }
                waitForRetry();
            }
        }
        throw new IllegalStateException("read-only SSH retry loop completed without a result");
    }

    private static void waitForRetry() throws LinuxOperationException {
        try {
            Thread.sleep(READ_ONLY_RETRY_DELAY.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw LinuxOperationException.localized("linux.error.capabilityCollectionFailed",
                    "Read-only capability collection retry was interrupted", exception);
        }
    }
}
