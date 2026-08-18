package gold.debug.windowstolinux.shared.linux.sshd.distro.shell;

import gold.debug.windowstolinux.shared.linux.sshd.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;
import java.util.Objects;

/** Fixed shell fragments shared by typed preparation renderers. / 类型化准备渲染器共享的固定 Shell 片段。 */
public final class SetupShellSupport {
    /** Represents the {@code TIMEOUT} value. / 表示 {@code TIMEOUT} 值。 */
    public static final Duration TIMEOUT = Duration.ofMinutes(15);
    /** Represents the {@code SUDOERS_PATH} value. / 表示 {@code SUDOERS_PATH} 值。 */
    public static final String SUDOERS_PATH = "/etc/sudoers.d/windowstolinux-managed";

    private SetupShellSupport() {
    }

    /** Performs the {@code requireUsername} operation. / 执行 {@code requireUsername} 操作。 */
    public static String requireUsername(String username) {
        username = Objects.requireNonNull(username, "username").trim();
        if (!username.matches("[a-z_][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("username is not a supported Linux account name");
        }
        return username;
    }

    /** Performs the {@code renderSudoers} operation. / 执行 {@code renderSudoers} 操作。 */
    public static String renderSudoers(String username) {
        return "# Managed by WindowsToLinux managed deployment; only the constrained helper is granted.\n"
                + requireUsername(username) + " ALL=(root) NOPASSWD: " + ManagedHelperBundle.PATH + "\n";
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
        String sudoers = renderSudoers(username);
        String helper = ManagedHelperBundle.renderScript();
        return """
                tmp=$(/usr/bin/mktemp /tmp/windowstolinux-managed-sudoers.XXXXXX)
                helper_tmp=$(/usr/bin/mktemp /tmp/windowstolinux-managed-helper.XXXXXX)
                cleanup_files="$cleanup_files $tmp $helper_tmp"
                sudoers=%s
                helper=%s
                printf '%%s' "$sudoers" > "$tmp"
                printf '%%s' "$helper" > "$helper_tmp"
                if [ "$elevation" = root ]; then
                  /usr/sbin/visudo -cf "$tmp"
                  /usr/bin/install -d -o root -g root -m 755 %s
                  /usr/bin/install -o root -g root -m 755 "$helper_tmp" %s
                  /usr/bin/install -o root -g root -m 440 "$tmp" %s
                else
                  /usr/bin/sudo -n /usr/sbin/visudo -cf "$tmp"
                  /usr/bin/sudo -n /usr/bin/install -d -o root -g root -m 755 %s
                  /usr/bin/sudo -n /usr/bin/install -o root -g root -m 755 "$helper_tmp" %s
                  /usr/bin/sudo -n /usr/bin/install -o root -g root -m 440 "$tmp" %s
                fi
                helper_probe="$("/usr/bin/sudo" -n %s probe)"
                printf '%%s\\n' "$helper_probe" | /usr/bin/grep -qx 'HELPER=1'
                printf '%%s\\n' "$helper_probe" | /usr/bin/grep -qx 'PROTOCOL=%d'
                """.formatted(quote(sudoers), quote(helper), quote(ManagedHelperBundle.DIRECTORY),
                quote(ManagedHelperBundle.PATH), quote(SUDOERS_PATH), quote(ManagedHelperBundle.DIRECTORY),
                quote(ManagedHelperBundle.PATH), quote(SUDOERS_PATH), quote(ManagedHelperBundle.PATH),
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
                if [ "$elevation" = root ]; then
                  /usr/bin/install -d -o root -g root -m 755 %s
                  /usr/bin/install -o root -g root -m 755 "$java_runtime_tmp" %s
                else
                  /usr/bin/sudo -n /usr/bin/install -d -o root -g root -m 755 %s
                  /usr/bin/sudo -n /usr/bin/install -o root -g root -m 755 "$java_runtime_tmp" %s
                fi
                """.formatted(quote(ManagedHelperBundle.DIRECTORY), quote(ManagedHelperBundle.JAVA_RUNTIME_PATH),
                quote(ManagedHelperBundle.DIRECTORY), quote(ManagedHelperBundle.JAVA_RUNTIME_PATH));
    }

    /** Performs the {@code quote} operation. / 执行 {@code quote} 操作。 */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
