package gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.time.Duration;
import java.util.Objects;

/** Executes layered HTTP or TCP health checks tied to the managed systemd process. / 执行绑定到受管 systemd 进程的分层 HTTP 或 TCP 健康检查。 */
public final class SystemdHealthProbe {
    private final SshCommandExecutor commands;

    /** Creates a checker for one authenticated session. / 为一个已认证会话创建检查器。 */
    public SystemdHealthProbe(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** Checks service health and response ownership. / 检查服务健康与响应归属。 */
    public HealthCheckResult check(ManagedApplication application, HealthCheck healthCheck) throws LinuxOperationException {
        Objects.requireNonNull(application, "application");
        return gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ApplicationHealthProbe.check(commands, application, healthCheck);
    }

    /** Checks one deterministic restore-candidate unit with the same listener ownership proof. / 使用相同监听归属证明检查一个确定性恢复候选单元。 */
    public HealthCheckResult checkUnit(String systemdUnit, HealthCheck healthCheck) throws LinuxOperationException {
        systemdUnit = Objects.requireNonNull(systemdUnit, "systemdUnit");
        if (!systemdUnit.matches("windowstolinux-(?:restore-[0-9a-f]{32}-)?[a-z0-9][a-z0-9-]{0,62}\\.service")) {
            throw LinuxOperationException.create(LinuxOperationFailureType.HEALTH_CHECK_UNSUPPORTED,
                    "restore health unit identity is invalid");
        }
        Objects.requireNonNull(healthCheck, "healthCheck");
        String script;
        try {
            script = SystemdHealthScriptRenderer.render(systemdUnit, healthCheck);
        } catch (IllegalArgumentException unsupported) {
            throw LinuxOperationException.create(LinuxOperationFailureType.HEALTH_CHECK_UNSUPPORTED, "Unsupported health-check type");
        }
        var result = commands.exec(script, Duration.ofSeconds(healthCheck.timeoutSeconds() + 15L), true);
        return new HealthCheckResult(result.succeeded()
                && "1".equals(SshCommandExecutor.lines(result.output()).get("HEALTHY")),
                result.succeeded() ? "Layered health check completed"
                        : "Health check failed or response ownership could not be proven; controlled diagnostic: "
                        + result.failureEvidence());
    }
}
