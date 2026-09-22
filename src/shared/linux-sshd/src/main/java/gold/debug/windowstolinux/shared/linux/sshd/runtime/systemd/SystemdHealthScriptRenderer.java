package gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd;

import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;

/**
 * Renders layered health scripts that bind a listening port to the managed systemd cgroup. / 渲染将监听端口绑定到受管 systemd 控制组的分层健康脚本。
 */
public final class SystemdHealthScriptRenderer {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SystemdHealthScriptRenderer() {
    }

    /**
     * Renders the fixed systemd ownership and health verification script for the reviewed service and check.
     * <p>为已审阅服务及检查渲染固定 systemd 归属及健康验证脚本。
     *
     * @param systemdUnit systemd unit / systemd单元
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static String render(String systemdUnit, HealthCheck healthCheck) {
        Objects.requireNonNull(systemdUnit, "systemdUnit");
        Objects.requireNonNull(healthCheck, "healthCheck");
        String quotedUnit = gold.debug.windowstolinux.shared.linux.command.CommandText.quote(systemdUnit);
        if (healthCheck instanceof HealthCheck.Http http) {
            int port = http.endpoint().getPort() >= 0
                    ? http.endpoint().getPort()
                    : ("https".equalsIgnoreCase(http.endpoint().getScheme()) ? 443 : 80);
            return listenerOwnershipFunction() + "\n"
                    + """
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
                              printf 'SYSTEMD_RESULT='; systemctl show --value --property Result %s 2>/dev/null || true
                              printf 'SYSTEMD_EXEC_MAIN_CODE='; systemctl show --value --property ExecMainCode %s 2>/dev/null || true
                              printf 'SYSTEMD_EXEC_MAIN_STATUS='; systemctl show --value --property ExecMainStatus %s 2>/dev/null || true
                              exit 1
                            fi
                            """
                            .formatted(http.timeoutSeconds(), quotedUnit,
                                    gold.debug.windowstolinux.shared.linux.command.CommandText
                                            .quote(http.endpoint().toASCIIString()),
                                    gold.debug.windowstolinux.shared.linux.command.CommandText
                                            .quote(Integer.toString(http.expectedStatus())),
                                    quotedUnit, port, quotedUnit, quotedUnit, quotedUnit, quotedUnit);
        }
        if (healthCheck instanceof HealthCheck.Tcp tcp) {
            return listenerOwnershipFunction() + "\n"
                    + """
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
                              printf 'SYSTEMD_RESULT='; systemctl show --value --property Result %s 2>/dev/null || true
                              printf 'SYSTEMD_EXEC_MAIN_CODE='; systemctl show --value --property ExecMainCode %s 2>/dev/null || true
                              printf 'SYSTEMD_EXEC_MAIN_STATUS='; systemctl show --value --property ExecMainStatus %s 2>/dev/null || true
                              exit 1
                            fi
                            """
                            .formatted(tcp.timeoutSeconds(), quotedUnit, tcp.port(), quotedUnit, tcp.port(),
                                    tcp.stabilitySeconds(), quotedUnit, quotedUnit, quotedUnit, quotedUnit, quotedUnit);
        }
        throw new IllegalArgumentException("Unsupported health-check type");
    }

    /**
     * Renders fixed listener ownership function protocol text from the reviewed inputs.
     * <p>根据已审阅输入渲染固定监听器归属函数协议文本。
     *
     * @return listener ownership function text / 监听器归属函数文本
     */
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
