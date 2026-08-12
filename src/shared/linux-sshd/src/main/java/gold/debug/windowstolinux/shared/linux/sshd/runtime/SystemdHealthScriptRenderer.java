package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.util.Objects;

/** Renders layered health scripts that bind a listening port to the managed systemd cgroup. / 渲染将监听端口绑定到受管 systemd 控制组的分层健康脚本。 */
final class SystemdHealthScriptRenderer {
    private SystemdHealthScriptRenderer() { }

    static String render(String systemdUnit, HealthCheck healthCheck) {
        Objects.requireNonNull(systemdUnit, "systemdUnit");
        Objects.requireNonNull(healthCheck, "healthCheck");
        String quotedUnit = SshCommandExecutor.quote(systemdUnit);
        if (healthCheck instanceof HealthCheck.Http http) {
            int port = http.endpoint().getPort() >= 0 ? http.endpoint().getPort()
                    : ("https".equalsIgnoreCase(http.endpoint().getScheme()) ? 443 : 80);
            return listenerOwnershipFunction() + "\n" + """
                    deadline=$((SECONDS + %d))
                    healthy=0
                    last_pid=0
                    last_status=unavailable
                    while [ "$SECONDS" -lt "$deadline" ]; do
                      pid=$(systemctl show --value --property MainPID %s)
                      case "$pid" in ''|*[!0-9]*) pid=0 ;; esac
                      last_pid=$pid
                      if [ "$pid" -gt 0 ]; then
                        status=$(curl --fail --silent --max-time 3 --output /dev/null --write-out '%%{http_code}' %s 2>/dev/null || true)
                        last_status=$status
                        if [ "$status" = %s ] && unit_owns_port %s %d; then
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
                    """.formatted(http.timeoutSeconds(), quotedUnit,
                    SshCommandExecutor.quote(http.endpoint().toASCIIString()),
                    SshCommandExecutor.quote(Integer.toString(http.expectedStatus())), quotedUnit, port, quotedUnit);
        }
        if (healthCheck instanceof HealthCheck.Tcp tcp) {
            return listenerOwnershipFunction() + "\n" + """
                    deadline=$((SECONDS + %d))
                    healthy=0
                    last_pid=0
                    while [ "$SECONDS" -lt "$deadline" ]; do
                      pid=$(systemctl show --value --property MainPID %s)
                      case "$pid" in ''|*[!0-9]*) pid=0 ;; esac
                      last_pid=$pid
                      if [ "$pid" -gt 0 ] \
                        && timeout 3 /bin/bash -c '</dev/tcp/127.0.0.1/%d' \
                        && unit_owns_port %s %d; then
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
                    """.formatted(tcp.timeoutSeconds(), quotedUnit, tcp.port(), quotedUnit, tcp.port(),
                    tcp.stabilitySeconds(), quotedUnit, quotedUnit);
        }
        throw new IllegalArgumentException("Unsupported health-check type");
    }

    private static String listenerOwnershipFunction() {
        return """
                set -euo pipefail
                unit_owns_port() {
                  local unit="$1" port="$2" cgroup listener_pid
                  cgroup=$(systemctl show --value --property ControlGroup "$unit")
                  [ -n "$cgroup" ] || return 1
                  while IFS= read -r listener_pid; do
                    case "$listener_pid" in ''|*[!0-9]*) continue ;; esac
                    [ -r "/proc/$listener_pid/cgroup" ] || continue
                    if cut -d: -f3- "/proc/$listener_pid/cgroup" | grep -Fxq -- "$cgroup"; then
                      return 0
                    fi
                  done < <(ss -H -ltnp \
                    | awk -v suffix=":$port" 'substr($4, length($4) - length(suffix) + 1) == suffix { print }' \
                    | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u || true)
                  return 1
                }
                """;
    }
}
