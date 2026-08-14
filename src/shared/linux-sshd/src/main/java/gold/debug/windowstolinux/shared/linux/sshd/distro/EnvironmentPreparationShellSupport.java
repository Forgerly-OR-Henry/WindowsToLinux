package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;
import java.util.Objects;

/** Fixed shell fragments shared by typed preparation renderers. / 类型化准备渲染器共享的固定 Shell 片段。 */
final class EnvironmentPreparationShellSupport {
    static final Duration TIMEOUT = Duration.ofMinutes(15);
    static final String SUDOERS_PATH = "/etc/sudoers.d/windowstolinux-managed";

    private EnvironmentPreparationShellSupport() {
    }

    static String requireUsername(String username) {
        username = Objects.requireNonNull(username, "username").trim();
        if (!username.matches("[a-z_][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("username is not a supported Linux account name");
        }
        return username;
    }

    static String renderSudoers(String username) {
        return "# Managed by WindowsToLinux managed deployment; only the constrained helper is granted.\n"
                + requireUsername(username) + " ALL=(root) NOPASSWD: " + ManagedHelperBundle.PATH + "\n";
    }

    static String renderCpuCheck(CpuMicroarchitectureLevel required) {
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

    static String renderStageDiagnostics() {
        return """
                prepare_stage=preflight
                trap 'status=$?; if [ "$status" -ne 0 ]; then printf "PREPARE_STAGE_FAILED=%s\\n" "$prepare_stage"; fi' EXIT
                """;
    }

    static String renderSecurityObservationFunctions() {
        return """
                security_state() {
                  if command -v getenforce >/dev/null 2>&1; then getenforce
                  elif [ -r /sys/module/apparmor/parameters/enabled ]; then
                    printf 'apparmor:'; tr -d '\\n' < /sys/module/apparmor/parameters/enabled
                  else printf 'none'; fi
                }
                firewall_state() {
                  for service in firewalld ufw nftables; do
                    if systemctl list-unit-files "$service.service" --no-legend 2>/dev/null | grep -q "$service.service"; then
                      printf '%s:' "$service"; systemctl is-active "$service" 2>/dev/null || :
                      return
                    fi
                  done
                  printf 'none'
                }
                security_before="$(security_state)"
                firewall_before="$(firewall_state)"
                """;
    }

    static String renderSecurityInvariant() {
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

    static String renderHelperInstallation(String username) {
        String sudoers = renderSudoers(username);
        String helper = ManagedHelperBundle.renderScript();
        return """
                tmp=$(/usr/bin/mktemp /tmp/windowstolinux-managed-sudoers.XXXXXX)
                helper_tmp=$(/usr/bin/mktemp /tmp/windowstolinux-managed-helper.XXXXXX)
                trap '/usr/bin/rm -f -- "$tmp" "$helper_tmp"' EXIT
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

    static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
