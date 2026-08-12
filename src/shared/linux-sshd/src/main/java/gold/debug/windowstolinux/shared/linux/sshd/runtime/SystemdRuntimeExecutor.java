package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Provides the {@code SystemdRuntimeExecutor} implementation.
 *
 * <p>提供 {@code SystemdRuntimeExecutor} 实现。
 */
public final class SystemdRuntimeExecutor {
    private final SshCommandExecutor commands;
    private final ManagedReleaseProtocolExecutor protocol;
    private final String username;

    /**
     * Creates a {@code SystemdRuntimeExecutor} instance.
     *
     * <p>创建 {@code SystemdRuntimeExecutor} 实例。
     *
     * @param commands the {@code commands} value / {@code commands} 值
     * @param protocol the {@code protocol} value / {@code protocol} 值
     * @param username the {@code username} value / {@code username} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SystemdRuntimeExecutor(SshCommandExecutor commands, ManagedReleaseProtocolExecutor protocol, String username) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.username = Objects.requireNonNull(username, "username");
    }

    /**
     * Performs the {@code checkHealth} operation.
     *
     * <p>执行 {@code checkHealth} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck)
            throws LinuxOperationException {
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
                    SshCommandExecutor.quote(application.systemdUnit()),
                    SshCommandExecutor.quote(application.systemdUnit()));
        } else {
            throw LinuxOperationException.localized("linux.error.healthCheckUnsupported",
                    "Unsupported health-check type");
        }
        var result = commands.exec(script, Duration.ofSeconds(healthCheck.timeoutSeconds() + 15L), true);
        return new HealthCheckResult(result.succeeded()
                && "1".equals(SshCommandExecutor.lines(result.output()).get("HEALTHY")),
                result.succeeded() ? "Layered health check completed"
                        : "Health check failed or response ownership could not be proven; controlled diagnostic: "
                                + result.failureEvidence());
    }

    /**
     * Performs the {@code observe} operation.
     *
     * <p>执行 {@code observe} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
        String unitPath = "/etc/systemd/system/" + application.systemdUnit();
        String unitDigest = sha256(SystemdUnitRenderer.render(username, application));
        String script = """
                set -eu
                root=%s
                owner=0
                unit=%s
                owner_file=0
                owner_value=0
                current_path=0
                fragment=0
                dropins=0
                unit_digest=0
                if [ -L "$root/current" ]; then
                  current=$(readlink -f "$root/current")
                  case "$current" in "$root/releases"/*) current_path=1 ;; esac
                fi
                if [ "$current_path" -eq 1 ] && [ -f "$root/current/.windowstolinux-owner" ]; then
                  owner_file=1
                  if [ "$(cat "$root/current/.windowstolinux-owner" 2>/dev/null || true)" = %s ]; then
                    owner_value=1
                  fi
                fi
                if [ "$(systemctl show --value --property FragmentPath %s 2>/dev/null || true)" = "$unit" ]; then
                  fragment=1
                fi
                if [ -z "$(systemctl show --value --property DropInPaths %s 2>/dev/null || true)" ]; then
                  dropins=1
                fi
                if [ "$(sha256sum "$unit" 2>/dev/null | awk '{print $1}')" = %s ]; then
                  unit_digest=1
                fi
                if [ "$current_path" -eq 1 ] && [ "$owner_file" -eq 1 ] && [ "$owner_value" -eq 1 ] && [ "$fragment" -eq 1 ] \
                  && [ "$dropins" -eq 1 ] && [ "$unit_digest" -eq 1 ]; then owner=1; fi
                runtime=$(systemctl is-active %s 2>/dev/null || true)
                enabled=$(systemctl is-enabled %s 2>/dev/null || true)
                printf 'OWNER=%%s\nCURRENT_PATH=%%s\nOWNER_FILE=%%s\nOWNER_VALUE=%%s\nFRAGMENT=%%s\nDROPINS=%%s\nUNIT_DIGEST=%%s\nRUNTIME=%%s\nENABLED=%%s\n' \
                  "$owner" "$current_path" "$owner_file" "$owner_value" "$fragment" "$dropins" "$unit_digest" "$runtime" "$enabled"
                """.formatted(SshCommandExecutor.quote(application.releaseRoot()),
                SshCommandExecutor.quote(unitPath),
                SshCommandExecutor.quote(application.ownershipManifestSha256()),
                SshCommandExecutor.quote(application.systemdUnit()),
                SshCommandExecutor.quote(application.systemdUnit()), SshCommandExecutor.quote(unitDigest),
                SshCommandExecutor.quote(application.systemdUnit()),
                SshCommandExecutor.quote(application.systemdUnit()));
        var result = commands.exec("/bin/bash -lc " + SshCommandExecutor.quote(script),
                Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.runtimeObservationFailed",
                    "Failed to observe the actual managed application state");
        }
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        boolean ownership = "1".equals(values.get("OWNER"));
        String rawRuntime = values.getOrDefault("RUNTIME", "unknown");
        String rawAutostart = values.getOrDefault("ENABLED", "unknown");
        RuntimeState runtime = ownership ? runtimeState(rawRuntime) : RuntimeState.UNKNOWN;
        AutostartState autostart = ownership ? autostartState(rawAutostart) : AutostartState.UNKNOWN;
        String evidence = ownership ? "State verified through remote managed identity (runtime=" + rawRuntime
                + ", enabled=" + rawAutostart + ")"
                : "Managed identity is missing or externally modified (owner-file="
                + values.getOrDefault("OWNER_FILE", "0")
                + ", current-path=" + values.getOrDefault("CURRENT_PATH", "0")
                + ", owner-value=" + values.getOrDefault("OWNER_VALUE", "0")
                + ", fragment=" + values.getOrDefault("FRAGMENT", "0")
                + ", dropins=" + values.getOrDefault("DROPINS", "0")
                + ", unit-digest=" + values.getOrDefault("UNIT_DIGEST", "0") + ")";
        return new LifecycleObservation(application, runtime, autostart, ownership, Instant.now(), evidence);
    }

    /**
     * Performs the {@code executeLifecycle} operation.
     *
     * <p>执行 {@code executeLifecycle} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param action the {@code action} value / {@code action} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action,
                                                 HealthCheck healthCheck) throws LinuxOperationException {
        LifecycleObservation before = observe(application);
        if (!before.ownershipVerified()) {
            return before;
        }
        if (action == LifecycleAction.START && before.runtimeState() != RuntimeState.STOPPED) {
            throw LinuxOperationException.localized("linux.error.startRequiresStopped",
                    "Start is allowed only for a managed application confirmed as stopped");
        }
        String actionVerb = switch (action) {
            case START -> "start";
            case STOP -> "stop";
            case RESTART -> "restart";
            case ENABLE_AUTOSTART -> "enable";
            case DISABLE_AUTOSTART -> "disable";
            case REFRESH_STATUS -> null;
        };
        String command = actionVerb == null ? "true" : protocol.command(
                "lifecycle", application.id(), actionVerb, application.ownershipManifestSha256());
        var result = commands.exec(command, Duration.ofSeconds(60), false);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.lifecycleActionFailed",
                    "systemd lifecycle operation failed");
        }
        if ((action == LifecycleAction.START || action == LifecycleAction.RESTART)
                && !checkHealth(application, healthCheck).healthy()) {
            throw LinuxOperationException.localized("linux.error.postStartHealthFailed",
                    "Post-start health check failed");
        }
        LifecycleObservation after = action == LifecycleAction.STOP ? awaitStopped(application) : observe(application);
        if (action == LifecycleAction.STOP && after.runtimeState() != RuntimeState.STOPPED) {
            throw LinuxOperationException.localized("linux.error.stopUnverified",
                    "Stop operation could not be verified remotely: state=" + after.runtimeState()
                            + ", evidence=" + after.evidence());
        }
        if (action == LifecycleAction.STOP) {
            var noMainProcess = commands.exec("test \"$(systemctl show --value --property MainPID "
                            + SshCommandExecutor.quote(application.systemdUnit()) + ")\" = 0",
                    Duration.ofSeconds(10), false);
            if (!noMainProcess.succeeded()) {
                throw LinuxOperationException.localized("linux.error.mainProcessStillRunning",
                        "A systemd main process is still present after stop");
            }
        }
        if (action == LifecycleAction.ENABLE_AUTOSTART
                && after.autostartState() != AutostartState.ENABLED) {
            throw LinuxOperationException.localized("linux.error.enableAutostartUnverified",
                    "Autostart enablement could not be verified remotely");
        }
        if (action == LifecycleAction.DISABLE_AUTOSTART
                && after.autostartState() != AutostartState.DISABLED) {
            throw LinuxOperationException.localized("linux.error.disableAutostartUnverified",
                    "Autostart disablement could not be verified remotely");
        }
        return after;
    }

    private LifecycleObservation awaitStopped(ManagedApplication application) throws LinuxOperationException {
        LifecycleObservation observation = observe(application);
        for (int attempt = 0; observation.runtimeState() != RuntimeState.STOPPED && attempt < 20; attempt++) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw LinuxOperationException.localized("linux.error.stopWaitInterrupted",
                        "Interrupted while waiting to verify the stopped state", interrupted);
            }
            observation = observe(application);
        }
        return observation;
    }

    private static RuntimeState runtimeState(String value) {
        return "active".equals(value) ? RuntimeState.RUNNING
                : "inactive".equals(value) ? RuntimeState.STOPPED : RuntimeState.UNKNOWN;
    }

    private static AutostartState autostartState(String value) {
        return "enabled".equals(value) ? AutostartState.ENABLED
                : "disabled".equals(value) ? AutostartState.DISABLED : AutostartState.UNKNOWN;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }
}
