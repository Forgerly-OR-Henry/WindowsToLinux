package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.sshd.distro.generation.script.SetupScriptRenderer;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdPlatformCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;

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
    private final PreparationResolver preparations;

    /** Creates the managed environment executor. / 创建受管环境执行器。 */
    public ManagedEnvironmentExecutor(SshCommandExecutor commands, SshdCapabilityCollector baselineCapabilities,
                                      SshdPlatformCapabilityCollector platformCapabilities, String serverId,
                                      String username, PreparationResolver preparations) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.baselineCapabilities = Objects.requireNonNull(baselineCapabilities, "baselineCapabilities");
        this.platformCapabilities = Objects.requireNonNull(platformCapabilities, "platformCapabilities");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.username = Objects.requireNonNull(username, "username");
        this.preparations = Objects.requireNonNull(preparations, "preparations");
    }

    /** Prepares one supported distribution after explicit confirmation. / 在明确确认后准备一个受支持的发行版。 */
    public EnvironmentSetupResult prepare(EnvironmentSetupApproval approval) throws LinuxOperationException {
        Objects.requireNonNull(approval, "approval").requireAcceptedFor(serverId);
        LinuxCapabilityFacts before = platformCapabilities.collectDeploymentCapabilities();
        String script = scriptFor(before);
        var prepared = commands.exec(script, SetupScriptRenderer.TIMEOUT, true);
        if (!prepared.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "managed target environment preparation failed: " + prepared.failureEvidence());
        }
        ServerCapabilityFacts collected = baselineCapabilities.collect();
        if (!collected.supportsManagedDeployment(false, new HealthCheck.Tcp(1, 1, 1))) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_REQUIREMENTS_UNMET,
                    "Environment preparation completed, but the target is still missing the fixed managed deployment baseline: "
                            + collected.evidence());
        }
        String elevation = SshCommandExecutor.lines(prepared.output()).getOrDefault("PREPARED_AS", "unknown");
        return new EnvironmentSetupResult(collected,
                "managed target preparation installed the fixed distribution toolset, root-owned controlled helper, and "
                        + "restricted sudo policy with " + elevation + " privileges; selected "
                        + before.distro() + " " + before.version());
    }

    private String scriptFor(LinuxCapabilityFacts capabilities) throws LinuxOperationException {
        return preparations.render(capabilities, username);
    }

    /** Resolves the fixed preparation script selected for collected distribution facts. / 解析采集到的发行版事实所选择的固定准备脚本。 */
    @FunctionalInterface
    public interface PreparationResolver {
        /** Renders the selected preparation script. / 渲染所选择的准备脚本。 */
        String render(LinuxCapabilityFacts capabilities, String username) throws LinuxOperationException;
    }
}
