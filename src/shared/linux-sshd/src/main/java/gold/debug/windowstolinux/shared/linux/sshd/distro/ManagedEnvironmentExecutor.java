package gold.debug.windowstolinux.shared.linux.sshd.distro;

import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdPlatformCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.distro.ManagedPlatformInstaller;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;

/**
 * Selects only a fixed supported-distribution preparation script after a read-only host probe.
 *
 *  <p>在只读主机探测后仅选择固定的受支持发行版环境准备脚本。
 */
public final class ManagedEnvironmentExecutor
        implements
            gold.debug.windowstolinux.shared.linux.distro.LinuxEnvironmentPreparer {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Baseline capabilities.
     * <p>基线能力。
     */
    private final SshdCapabilityCollector baselineCapabilities;

    /**
     * Platform capabilities.
     * <p>平台能力。
     */
    private final SshdPlatformCapabilityCollector platformCapabilities;

    /**
     * Persisted server identifier.
     * <p>持久化服务器标识。
     */
    private final String serverId;

    /**
     * Account name used by the reviewed connection.
     * <p>已审阅连接使用的账户名。
     */
    private final String username;

    /**
     * Bound preparation resolver collaborator for preparations.
     * <p>处理准备集合的准备解析器协作对象。
     */
    private final gold.debug.windowstolinux.shared.linux.distro.EnvironmentPreparationPlan preparations;

    /**
     * Creates the managed environment executor. / 创建受管环境执行器。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param baselineCapabilities baseline capabilities / 基线能力
     * @param platformCapabilities platform capabilities / 平台能力
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param preparations preparations / 准备集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedEnvironmentExecutor(SshCommandExecutor commands, SshdCapabilityCollector baselineCapabilities,
            SshdPlatformCapabilityCollector platformCapabilities, String serverId, String username,
            gold.debug.windowstolinux.shared.linux.distro.EnvironmentPreparationPlan preparations) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.baselineCapabilities = Objects.requireNonNull(baselineCapabilities, "baselineCapabilities");
        this.platformCapabilities = Objects.requireNonNull(platformCapabilities, "platformCapabilities");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.username = Objects.requireNonNull(username, "username");
        this.preparations = preparations;
    }

    /**
     * Prepares one supported distribution after explicit confirmation. / 在明确确认后准备一个受支持的发行版。
     *
     * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
     * @return constructed or resolved environment setup result / 构造或解析得到的环境Setup结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public EnvironmentSetupResult prepareEnvironment(EnvironmentSetupApproval approval) throws LinuxOperationException {
        Objects.requireNonNull(approval, "approval").requireAcceptedFor(serverId);
        LinuxCapabilityFacts before = platformCapabilities.collectDeploymentCapabilities();
        String script = scriptFor(before);
        var prepared = commands.execScript(script, java.time.Duration.ofMinutes(15), true);
        if (!prepared.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "managed target environment preparation failed: " + prepared.failureEvidence());
        }
        prepareManagedPlatform(approval);
        ServerCapabilityFacts collected = baselineCapabilities.collect();
        if (!collected.supportsManagedDeployment(false, new HealthCheck.Tcp(1, 1, 1))) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_REQUIREMENTS_UNMET,
                    "Environment preparation completed, but the target is still missing the fixed managed deployment baseline: "
                            + collected.evidence());
        }
        String elevation = gold.debug.windowstolinux.shared.linux.command.CommandText.lines(prepared.output())
                .getOrDefault("PREPARED_AS", "unknown");
        return new EnvironmentSetupResult(collected,
                "managed target preparation installed the fixed distribution toolset, root-owned controlled helper, and "
                        + "restricted sudo policy with " + elevation + " privileges; selected " + before.distro() + " "
                        + before.version());
    }

    /**
     * Renders the preparation script selected by the observed platform and target username.
     * <p>根据已观测平台及目标用户名渲染准备脚本。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @return script for text / 脚本对应文本
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private String scriptFor(LinuxCapabilityFacts capabilities) throws LinuxOperationException {
        if (preparations == null)
            throw new IllegalStateException("standard environment strategy was not supplied");
        return preparations.render(capabilities, username);
    }

    /** Installs only common isolation and helper prerequisites. / 仅安装通用隔离及 helper 前提。
     * @param approval target authorization / 目标授权
     * @return actual platform evidence / 实际平台证据
     * @throws LinuxOperationException when platform installation fails / 平台安装失败时
     */
    public EnvironmentSetupResult prepareManagedPlatform(EnvironmentSetupApproval approval)
            throws LinuxOperationException {
        approval.requireAcceptedFor(serverId);
        var before = baselineCapabilities.collect();
        if (platformReady(before))
            return new EnvironmentSetupResult(before, "current managed helper protocol verified");
        var result = commands.execScript(ManagedPlatformInstaller.render(username), java.time.Duration.ofMinutes(15),
                true);
        if (!result.succeeded())
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "managed platform installation failed: " + result.failureEvidence());
        var after = baselineCapabilities.collect();
        if (!platformReady(after))
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_REQUIREMENTS_UNMET,
                    "managed helper protocol verification failed");
        return new EnvironmentSetupResult(after,
                "managed isolation and helper installed without project toolchain selection");
    }

    /** Checks transport and isolation prerequisites without any project language requirement. / 检查传输及隔离前提，不包含项目语言要求。
     * @param facts actual server observations / 实际服务器观察
     * @return whether the common platform is ready / 通用平台是否就绪
     */
    private static boolean platformReady(ServerCapabilityFacts facts) {
        return facts
                .managedHelperProtocolVersion() == gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol.VERSION
                && facts.systemdAvailable() && facts.tarAvailable() && facts.curlAvailable()
                && facts.socketInspectionAvailable() && facts.buildLimitToolsAvailable()
                && facts.nonInteractiveSudoAvailable();
    }
}
