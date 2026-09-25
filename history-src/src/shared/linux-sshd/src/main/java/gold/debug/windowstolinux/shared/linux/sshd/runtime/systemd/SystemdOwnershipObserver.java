package gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ManagedRuntimeProtocolExecutor;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

/**
 * Observes runtime state only after proving release and unit ownership. / 仅在证明发布与 unit 归属后观察运行状态。
 */
public final class SystemdOwnershipObserver {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Account name used by the reviewed connection.
     * <p>已审阅连接使用的账户名。
     */
    private final String username;

    /**
     * Creates an ownership observer. / 创建归属观察器。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SystemdOwnershipObserver(SshCommandExecutor commands, String username) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.username = Objects.requireNonNull(username, "username");
    }

    /**
     * Observes a legacy managed unit rendered from the stored application. / 观察由存储应用渲染的旧受管 unit。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
        return observe(application, SystemdUnitRenderer.render(username, application));
    }

    /**
     * Observes a unit against complete expected content. / 对照完整预期内容观察 unit。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param expectedUnitContent expected unit content / 预期单元内容
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
                if state=$(systemctl show --property=ActiveState,SubState,Result,ExecMainCode,ExecMainStatus,MainPID %s); then
                  printf 'QUERY_OK=1\n%%s\n' "$state"
                else printf 'QUERY_OK=0\n'; fi
                runtime=unknown
                enabled=$(systemctl is-enabled %s 2>/dev/null || true)
                printf 'OWNER=%%s\nCURRENT_PATH=%%s\nOWNER_FILE=%%s\nOWNER_VALUE=%%s\nFRAGMENT=%%s\nDROPINS=%%s\nUNIT_DIGEST=%%s\nRUNTIME=%%s\nENABLED=%%s\n' \
                  "$owner" "$current_path" "$owner_file" "$owner_value" "$fragment" "$dropins" "$unit_digest" "$runtime" "$enabled"
                """
                .formatted(gold.debug.windowstolinux.shared.linux.command.CommandText.quote(application.releaseRoot()),
                        gold.debug.windowstolinux.shared.linux.command.CommandText.quote(unitPath),
                        gold.debug.windowstolinux.shared.linux.command.CommandText
                                .quote(application.ownershipManifestSha256()),
                        gold.debug.windowstolinux.shared.linux.command.CommandText.quote(application.systemdUnit()),
                        gold.debug.windowstolinux.shared.linux.command.CommandText.quote(application.systemdUnit()),
                        gold.debug.windowstolinux.shared.linux.command.CommandText.quote(unitDigest),
                        gold.debug.windowstolinux.shared.linux.command.CommandText.quote(application.systemdUnit()),
                        gold.debug.windowstolinux.shared.linux.command.CommandText.quote(application.systemdUnit()));
        var result = commands.exec(
                "/bin/bash -lc " + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(script),
                Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            return ManagedRuntimeProtocolExecutor.nativeObservation(application, Map.of("QUERY_OK", "0"));
        }
        Map<String, String> values = gold.debug.windowstolinux.shared.linux.command.CommandText.lines(result.output());
        boolean ownership = "1".equals(values.get("OWNER"));
        LifecycleObservation observation = ManagedRuntimeProtocolExecutor.nativeObservation(application, values);
        String evidence = ownership
                ? observation.evidence()
                : "Managed identity is missing or externally modified (owner-file="
                        + values.getOrDefault("OWNER_FILE", "0") + ", current-path="
                        + values.getOrDefault("CURRENT_PATH", "0") + ", owner-value="
                        + values.getOrDefault("OWNER_VALUE", "0") + ", fragment=" + values.getOrDefault("FRAGMENT", "0")
                        + ", dropins=" + values.getOrDefault("DROPINS", "0") + ", unit-digest="
                        + values.getOrDefault("UNIT_DIGEST", "0") + ")";
        return new LifecycleObservation(application, observation.runtimeState(), observation.autostartState(),
                ownership, Instant.now(), evidence);
    }

    /**
     * Computes the SHA-256 content identity used for independent integrity checks.
     * <p>计算独立完整性检查使用的 SHA-256 内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return computed SHA-256 content digest / 已计算的 SHA-256 内容摘要
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }
}
