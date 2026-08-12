package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;

import java.util.Objects;

/**
 * Provides the {@code UbuntuEnvironmentExecutor} implementation.
 *
 * <p>提供 {@code UbuntuEnvironmentExecutor} 实现。
 */
public final class UbuntuEnvironmentExecutor {
    private final SshCommandExecutor commands;
    private final SshdCapabilityCollector capabilities;
    private final String serverId;
    private final String username;

    /**
     * Creates a {@code UbuntuEnvironmentExecutor} instance.
     *
     * <p>创建 {@code UbuntuEnvironmentExecutor} 实例。
     *
     * @param commands the {@code commands} value / {@code commands} 值
     * @param capabilities the {@code capabilities} value / {@code capabilities} 值
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @param username the {@code username} value / {@code username} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public UbuntuEnvironmentExecutor(SshCommandExecutor commands, SshdCapabilityCollector capabilities,
                                     String serverId, String username) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.username = Objects.requireNonNull(username, "username");
    }

    /**
     * Performs the {@code prepare} operation.
     *
     * <p>执行 {@code prepare} 操作。
     *
     * @param approval the {@code approval} value / {@code approval} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public EnvironmentPreparationResult prepare(EnvironmentPreparationApproval approval)
            throws LinuxOperationException {
        Objects.requireNonNull(approval, "approval").requireAcceptedFor(serverId);
        var prepared = commands.exec(UbuntuEnvironmentPreparation.renderScript(username),
                UbuntuEnvironmentPreparation.TIMEOUT, true);
        if (!prepared.succeeded()) {
            throw LinuxOperationException.localized("linux.error.environmentPreparationFailed",
                    "managed service target environment preparation failed: " + prepared.failureEvidence());
        }
        ServerCapabilities collected = capabilities.collect();
        if (!collected.supportsManagedDeployment(false, new HealthCheck.Tcp(1, 1, 1))) {
            throw LinuxOperationException.localized("linux.error.environmentRequirementsUnmet",
                    "Environment preparation completed, but the target still does not satisfy Ubuntu 24.04 "
                            + "deployment requirements: " + collected.evidence());
        }
        String elevation = SshCommandExecutor.lines(prepared.output()).getOrDefault("PREPARED_AS", "unknown");
        return new EnvironmentPreparationResult(collected,
                "managed service preparation installed the fixed Ubuntu toolset, root-owned controlled helper, and "
                        + "restricted sudo policy with " + elevation + " privileges");
    }
}
