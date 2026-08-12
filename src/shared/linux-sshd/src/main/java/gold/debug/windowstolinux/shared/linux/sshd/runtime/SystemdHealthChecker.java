package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.time.Duration;
import java.util.Objects;

/** Executes layered HTTP or TCP health checks tied to the managed systemd process. / 执行绑定到受管 systemd 进程的分层 HTTP 或 TCP 健康检查。 */
public final class SystemdHealthChecker {
    private final SshCommandExecutor commands;

    /** Creates a checker for one authenticated session. / 为一个已认证会话创建检查器。 */
    public SystemdHealthChecker(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** Checks service health and response ownership. / 检查服务健康与响应归属。 */
    public HealthCheckResult check(ManagedApplication application, HealthCheck healthCheck) throws LinuxOperationException {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(healthCheck, "healthCheck");
        String script;
        try {
            script = SystemdHealthScriptRenderer.render(application.systemdUnit(), healthCheck);
        } catch (IllegalArgumentException unsupported) {
            throw LinuxOperationException.localized("linux.error.healthCheckUnsupported", "Unsupported health-check type");
        }
        var result = commands.exec(script, Duration.ofSeconds(healthCheck.timeoutSeconds() + 15L), true);
        return new HealthCheckResult(result.succeeded()
                && "1".equals(SshCommandExecutor.lines(result.output()).get("HEALTHY")),
                result.succeeded() ? "Layered health check completed"
                        : "Health check failed or response ownership could not be proven; controlled diagnostic: "
                        + result.failureEvidence());
    }
}
