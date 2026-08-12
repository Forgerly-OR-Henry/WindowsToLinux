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
        String servicePid = "systemctl show --value --property MainPID "
                + SshCommandExecutor.quote(application.systemdUnit());
        String script;
        if (healthCheck instanceof HealthCheck.Http http) {
            String endpointText = http.endpoint().toASCIIString();
            int port = http.endpoint().getPort() >= 0 ? http.endpoint().getPort()
                    : ("https".equalsIgnoreCase(http.endpoint().getScheme()) ? 443 : 80);
            script = """
                    set -euo pipefail
                    deadline=$((SECONDS + %d))
                    healthy=0
                    last_pid=0
                    last_status=unavailable
                    while [ "$SECONDS" -lt "$deadline" ]; do
                      pid=$(%s)
                      case "$pid" in ''|*[!0-9]*) pid=0 ;; esac
                      last_pid=$pid
                      if [ "$pid" -gt 0 ]; then
                        status=$(curl --fail --silent --max-time 3 --output /dev/null --write-out '%%{http_code}' %s 2>/dev/null || true)
                        last_status=$status
                        if [ "$status" = %s ] && ss -ltnp | grep -F "pid=$pid" | grep -F ":%d" >/dev/null; then
                          healthy=1
                          break
                        fi
                      fi
                      sleep 1
                    done
                    if [ "$healthy" -eq 1 ]; then
                      printf 'HEALTHY=1\n'
                    else
                      printf 'LAST_PID=%%s\n' "$last_pid"
                      printf 'LAST_HTTP_STATUS=%%s\n' "$last_status"
                      printf 'SYSTEMD_STATE='; systemctl is-active %s 2>/dev/null || true
                      exit 1
                    fi
                    """.formatted(http.timeoutSeconds(), servicePid, SshCommandExecutor.quote(endpointText),
                    SshCommandExecutor.quote(Integer.toString(http.expectedStatus())), port,
                    SshCommandExecutor.quote(application.systemdUnit()));
        } else if (healthCheck instanceof HealthCheck.Tcp tcp) {
            script = """
                    set -euo pipefail
                    deadline=$((SECONDS + %d))
                    healthy=0
                    last_pid=0
                    while [ "$SECONDS" -lt "$deadline" ]; do
                      pid=$(%s)
                      case "$pid" in ''|*[!0-9]*) pid=0 ;; esac
                      last_pid=$pid
                      if [ "$pid" -gt 0 ] \
                        && timeout 3 /bin/bash -c '</dev/tcp/127.0.0.1/%d' \
                        && ss -ltnp | grep -F "pid=$pid" >/dev/null; then
                        sleep %d
                        if systemctl is-active --quiet %s; then
                          healthy=1
                          break
                        fi
                      fi
                      sleep 1
                    done
                    if [ "$healthy" -eq 1 ]; then
                      printf 'HEALTHY=1\n'
                    else
                      printf 'LAST_PID=%%s\n' "$last_pid"
                      printf 'SYSTEMD_STATE='; systemctl is-active %s 2>/dev/null || true
                      exit 1
                    fi
                    """.formatted(tcp.timeoutSeconds(), servicePid, tcp.port(), tcp.stabilitySeconds(),
                    SshCommandExecutor.quote(application.systemdUnit()), SshCommandExecutor.quote(application.systemdUnit()));
        } else {
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
