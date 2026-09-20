package gold.debug.windowstolinux.shared.linux.sshd.distro.generation.script;

import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;
import java.util.Objects;

/** Fixed shell fragments shared by typed preparation renderers. / 类型化准备渲染器共享的固定 Shell 片段。 */
public final class SetupScriptRenderer {
    /** Represents the {@code TIMEOUT} value. / 表示 {@code TIMEOUT} 值。 */
    public static final Duration TIMEOUT = Duration.ofMinutes(15);

    private SetupScriptRenderer() {
    }

    /** Performs the {@code requireUsername} operation. / 执行 {@code requireUsername} 操作。 */
    public static String requireUsername(String username) {
        username = Objects.requireNonNull(username, "username").trim();
        if (!username.matches("[a-z_][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("username is not a supported Linux account name");
        }
        return username;
    }

    /** Performs the {@code renderCpuCheck} operation. / 执行 {@code renderCpuCheck} 操作。 */
    public static String renderCpuCheck(CpuMicroarchitectureLevel required) {
        if (required == CpuMicroarchitectureLevel.X86_64_V1) {
            return ":";
        }
        String level = switch (required) {
            case X86_64_V2 -> "x86-64-v2";
            case X86_64_V3 -> "x86-64-v3";
            case X86_64_V4 -> "x86-64-v4";
            case UNKNOWN, X86_64_V1 -> throw new IllegalArgumentException("unsupported required CPU level");
        };
        return """
                loader=
                for candidate in /lib64/ld-linux-x86-64.so.2 /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2; do
                  if [ -x "$candidate" ]; then loader="$candidate"; break; fi
                done
                test -n "$loader"
                "$loader" --help 2>/dev/null | grep -Eq '%s.*supported'
        """.formatted(level);
    }

    /** Performs the {@code renderStageDiagnostics} operation. / 执行 {@code renderStageDiagnostics} 操作。 */
    public static String renderStageDiagnostics() {
        return """
                prepare_stage=preflight
                prepare_check=none
                cleanup_files=
                cleanup() {
                  for cleanup_file in $cleanup_files; do /usr/bin/rm -f -- "$cleanup_file"; done
                }
                trap 'status=$?; if [ "$status" -ne 0 ]; then printf "PREPARE_STAGE_FAILED=%s\\n" "$prepare_stage"; printf "PREPARE_CHECK_FAILED=%s\\n" "$prepare_check"; fi; cleanup; exit "$status"' EXIT
                """;
    }

    /** Performs the {@code renderSecurityObservationFunctions} operation. / 执行 {@code renderSecurityObservationFunctions} 操作。 */
    public static String renderSecurityObservationFunctions() {
        return """
                security_state() {
                  if command -v getenforce >/dev/null 2>&1; then getenforce
                  elif [ -r /sys/module/apparmor/parameters/enabled ]; then
                    printf 'apparmor:'; tr -d '\\n' < /sys/module/apparmor/parameters/enabled
                  else printf 'none'; fi
                }
                firewall_state() {
                  for service in firewalld ufw; do
                    if systemctl list-unit-files "$service.service" --no-legend 2>/dev/null | grep -q "$service.service"; then
                      printf '%s:' "$service"; systemctl is-active "$service" 2>/dev/null || :
                      return
                    fi
                  done
                  if command -v nft >/dev/null 2>&1; then
                    if nft_rules="$(nft list ruleset 2>/dev/null)"; then
                      if [ -n "$nft_rules" ]; then printf 'nftables:active'; else printf 'nftables:inactive'; fi
                    else
                      printf 'nftables:unknown'
                    fi
                    return
                  fi
                  printf 'none'
                }
                security_before="$(security_state)"
                firewall_before="$(firewall_state)"
                """;
    }

    /** Performs the {@code renderSecurityInvariant} operation. / 执行 {@code renderSecurityInvariant} 操作。 */
    public static String renderSecurityInvariant() {
        return """
                security_after="$(security_state)"
                firewall_after="$(firewall_state)"
                test "$security_after" = "$security_before"
                case "$firewall_before" in
                  *:active) test "$firewall_after" = "$firewall_before" ;;
                esac
                printf 'SECURITY_BEFORE=%s\\n' "$security_before"
                printf 'SECURITY_AFTER=%s\\n' "$security_after"
                printf 'FIREWALL_BEFORE=%s\\n' "$firewall_before"
                printf 'FIREWALL_AFTER=%s\\n' "$firewall_after"
                """;
    }

    /** Performs the {@code renderHelperInstallation} operation. / 执行 {@code renderHelperInstallation} 操作。 */
    public static String renderHelperInstallation(String username) {
        requireUsername(username);
        return """
                [ "$(id -u)" -eq 0 ] || { printf 'PREPARE_REJECT=root-management-required\\n'; exit 64; }
                helper_tmp=$(/usr/bin/mktemp /tmp/windowstolinux-managed-helper.XXXXXX)
                entry_tmp=$(/usr/bin/mktemp /tmp/windowstolinux-build-entry.XXXXXX)
                cleanup_files="$cleanup_files $helper_tmp $entry_tmp"
                printf '%%s' %s > "$helper_tmp"
                printf '%%s' %s > "$entry_tmp"
                /usr/bin/install -d -o root -g root -m 755 %s
                /usr/bin/install -o root -g root -m 755 "$helper_tmp" %s
                /usr/bin/install -o root -g root -m 755 "$entry_tmp" %s
                recovery_unit=/etc/systemd/system/windowstolinux-workspace-recovery.service
                [ ! -L "$recovery_unit" ] || exit 64
                recovery_tmp=$(/usr/bin/mktemp /tmp/windowstolinux-workspace-recovery.XXXXXX)
                cleanup_files="$cleanup_files $recovery_tmp"
                cat > "$recovery_tmp" <<'WTL_RECOVERY_UNIT'
                [Unit]
                Description=WindowsToLinux temporary workspace recovery
                After=local-fs.target
                Before=multi-user.target
                [Service]
                Type=oneshot
                ExecStart=/usr/local/lib/windowstolinux/managed-helper workspace-recover
                [Install]
                WantedBy=multi-user.target
                WTL_RECOVERY_UNIT
                if [ -e "$recovery_unit" ]; then
                  [ "$(stat -c '%%u:%%g' "$recovery_unit")" = 0:0 ] || exit 64
                  cmp -s "$recovery_unit" "$recovery_tmp" || { printf 'PREPARE_REJECT=recovery-unit-conflict\\n'; exit 64; }
                fi
                /usr/bin/install -o root -g root -m 644 "$recovery_tmp" "$recovery_unit"
                systemctl daemon-reload
                systemctl enable windowstolinux-workspace-recovery.service
                helper_probe="$(%s probe)"
                printf '%%s\\n' "$helper_probe" | /usr/bin/grep -qx 'HELPER=1'
                printf '%%s\\n' "$helper_probe" | /usr/bin/grep -qx 'PROTOCOL=%d'
                """.formatted(quote(ManagedHelperBundle.renderScript()), quote(ManagedHelperBundle.renderBuildEntry()),
                quote(ManagedHelperBundle.DIRECTORY), quote(ManagedHelperBundle.PATH),
                quote(ManagedHelperBundle.DIRECTORY + "/build-entry"), quote(ManagedHelperBundle.PATH),
                ManagedHelperBundle.PROTOCOL_VERSION);
    }

    /** Performs the {@code renderJava21RuntimeInstallation} operation. / 执行 {@code renderJava21RuntimeInstallation} 操作。 */
    public static String renderJava21RuntimeInstallation() {
        return """
                prepare_check=java-21-runtime
                java_runtime=
                for candidate in /usr/lib/jvm/java-21-openjdk*/bin/java /usr/lib/jvm/jre-21-openjdk*/bin/java; do
                  if [ -x "$candidate" ] && "$candidate" -version 2>&1 | /usr/bin/grep -Eq '(^|[^0-9])21[.]'; then
                    java_runtime="$candidate"
                    break
                  fi
                done
                test -n "$java_runtime"
                java_runtime_tmp=$(/usr/bin/mktemp /tmp/windowstolinux-managed-java.XXXXXX)
                cleanup_files="$cleanup_files $java_runtime_tmp"
                printf '%%s\\n' '#!/bin/sh' "exec $java_runtime \\"\\$@\\"" > "$java_runtime_tmp"
                  /usr/bin/install -d -o root -g root -m 755 %s
                  /usr/bin/install -o root -g root -m 755 "$java_runtime_tmp" %s
                """.formatted(quote(ManagedHelperBundle.DIRECTORY), quote(ManagedHelperBundle.JAVA_RUNTIME_PATH));
    }

    /** Performs the {@code quote} operation. / 执行 {@code quote} 操作。 */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
    /** Verifies common prepared tools independently of the package manager. / 独立于包管理器校验共同准备工具。 */
    public static String renderCommonChecks() {
        return """
                prepare_check=java-command
                test -x %s
                prepare_check=java-version
                java_version="$(%s -version 2>&1)"
                prepare_check=java-21
                printf 'PREPARE_JAVA_VERSION=%%s\\n' "$java_version"
                printf '%%s\\n' "$java_version" | /usr/bin/grep -Eq '(^|[^0-9])21[.]'
                prepare_check=maven-command
                command -v mvn >/dev/null 2>&1
                prepare_check=curl-command
                command -v curl >/dev/null 2>&1
                prepare_check=tar-command
                command -v tar >/dev/null 2>&1
                prepare_check=gzip-command
                command -v gzip >/dev/null 2>&1
                prepare_check=sqlite3-command
                command -v sqlite3 >/dev/null 2>&1
                prepare_check=ss-command
                command -v ss >/dev/null 2>&1
                prepare_check=setsid-command
                command -v setsid >/dev/null 2>&1
                prepare_check=timeout-command
                command -v timeout >/dev/null 2>&1
                prepare_check=du-command
                command -v du >/dev/null 2>&1
                """.formatted(SetupScriptRenderer.quote(
                gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.JAVA_RUNTIME_PATH),
                SetupScriptRenderer.quote(
                gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.JAVA_RUNTIME_PATH));
    }
}
