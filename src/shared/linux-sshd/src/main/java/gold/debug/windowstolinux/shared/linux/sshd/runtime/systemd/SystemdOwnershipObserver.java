package gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
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

/** Observes runtime state only after proving release and unit ownership. / 仅在证明发布与 unit 归属后观察运行状态。 */
public final class SystemdOwnershipObserver {
    private final SshCommandExecutor commands;
    private final String username;

    /** Creates an ownership observer. / 创建归属观察器。 */
    public SystemdOwnershipObserver(SshCommandExecutor commands, String username) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.username = Objects.requireNonNull(username, "username");
    }

    /** Observes a legacy managed unit rendered from the stored application. / 观察由存储应用渲染的旧受管 unit。 */
    public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
        return observe(application, SystemdUnitRenderer.render(username, application));
    }

    /** Observes a unit against complete expected content. / 对照完整预期内容观察 unit。 */
    public LifecycleObservation observe(ManagedApplication application, String expectedUnitContent)
            throws LinuxOperationException {
        application = Objects.requireNonNull(application, "application");
        expectedUnitContent = Objects.requireNonNull(expectedUnitContent, "expectedUnitContent");
        String unitPath = "/etc/systemd/system/" + application.systemdUnit();
        String unitDigest = sha256(expectedUnitContent);
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
                """.formatted(SshCommandExecutor.quote(application.releaseRoot()), SshCommandExecutor.quote(unitPath),
                SshCommandExecutor.quote(application.ownershipManifestSha256()),
                SshCommandExecutor.quote(application.systemdUnit()), SshCommandExecutor.quote(application.systemdUnit()),
                SshCommandExecutor.quote(unitDigest), SshCommandExecutor.quote(application.systemdUnit()),
                SshCommandExecutor.quote(application.systemdUnit()));
        var result = commands.exec("/bin/bash -lc " + SshCommandExecutor.quote(script), Duration.ofSeconds(20), true);
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
                + values.getOrDefault("OWNER_FILE", "0") + ", current-path=" + values.getOrDefault("CURRENT_PATH", "0")
                + ", owner-value=" + values.getOrDefault("OWNER_VALUE", "0")
                + ", fragment=" + values.getOrDefault("FRAGMENT", "0")
                + ", dropins=" + values.getOrDefault("DROPINS", "0")
                + ", unit-digest=" + values.getOrDefault("UNIT_DIGEST", "0") + ")";
        return new LifecycleObservation(application, runtime, autostart, ownership, Instant.now(), evidence);
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }
}
