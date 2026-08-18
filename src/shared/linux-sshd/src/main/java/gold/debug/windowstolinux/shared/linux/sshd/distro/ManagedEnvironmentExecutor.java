package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.sshd.distro.registry.DistributionSetupRegistry;
import gold.debug.windowstolinux.shared.linux.sshd.distro.shell.SetupShellSupport;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdPlatformCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;

import java.util.Objects;

/**
 * Selects only a fixed supported-distribution preparation script after a read-only host probe.
 *
 * <p>在只读主机探测后仅选择固定的受支持发行版环境准备脚本。
 */
public final class ManagedEnvironmentExecutor {
    private final SshCommandExecutor commands;
    private final SshdCapabilityCollector baselineCapabilities;
    private final SshdPlatformCapabilityCollector platformCapabilities;
    private final String serverId;
    private final String username;
    private final DistributionSetupRegistry preparations;

    /** Creates the managed environment executor. / 创建受管环境执行器。 */
    public ManagedEnvironmentExecutor(SshCommandExecutor commands, SshdCapabilityCollector baselineCapabilities,
                                      SshdPlatformCapabilityCollector platformCapabilities, String serverId, String username) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.baselineCapabilities = Objects.requireNonNull(baselineCapabilities, "baselineCapabilities");
        this.platformCapabilities = Objects.requireNonNull(platformCapabilities, "platformCapabilities");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.username = Objects.requireNonNull(username, "username");
        this.preparations = DistributionSetupRegistry.defaults();
    }

    /** Prepares one supported distribution after explicit confirmation. / 在明确确认后准备一个受支持的发行版。 */
    public EnvironmentSetupResult prepare(EnvironmentSetupApproval approval) throws LinuxOperationException {
        Objects.requireNonNull(approval, "approval").requireAcceptedFor(serverId);
        LinuxCapabilities before = platformCapabilities.collectDeploymentCapabilities();
        String script = scriptFor(before);
        var prepared = commands.exec(script, SetupShellSupport.TIMEOUT, true);
        if (!prepared.succeeded()) {
            throw LinuxOperationException.localized("linux.error.environmentPreparationFailed",
                    "managed target environment preparation failed: " + prepared.failureEvidence());
        }
        ServerCapabilities collected = baselineCapabilities.collect();
        if (!collected.supportsManagedDeployment(false, new HealthCheck.Tcp(1, 1, 1))) {
            throw LinuxOperationException.localized("linux.error.environmentRequirementsUnmet",
                    "Environment preparation completed, but the target is still missing the fixed managed deployment baseline: "
                            + collected.evidence());
        }
        String elevation = SshCommandExecutor.lines(prepared.output()).getOrDefault("PREPARED_AS", "unknown");
        return new EnvironmentSetupResult(collected,
                "managed target preparation installed the fixed distribution toolset, root-owned controlled helper, and "
                        + "restricted sudo policy with " + elevation + " privileges; selected "
                        + before.distro() + " " + before.version());
    }

    private String scriptFor(LinuxCapabilities capabilities) throws LinuxOperationException {
        return preparations.render(capabilities, username);
    }
}
