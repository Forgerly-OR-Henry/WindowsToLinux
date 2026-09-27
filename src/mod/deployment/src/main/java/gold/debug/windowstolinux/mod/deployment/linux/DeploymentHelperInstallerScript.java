package gold.debug.windowstolinux.mod.deployment.linux;

/** Shared helper and isolation installation; no project language selection. / 共用 helper 及隔离安装，不选择项目语言。 */
public final class DeploymentHelperInstallerScript {
    /** Prevents construction. / 禁止实例化。 */
    private DeploymentHelperInstallerScript() {
    }

    /** Renders the bounded managed platform installation. / 渲染有界受管平台安装。
     * @return bounded implementation-owned script / 有界实现自有脚本
     */
    public static String render() {
        return "set -euo pipefail\nexport LC_ALL=C\n" + renderStageDiagnostics() + renderSecurityObservationFunctions()
                + """
                        [ "$(id -u)" -eq 0 ] || exit 64
                        command -v systemctl >/dev/null
                        prepare_stage=platform-packages
                        if command -v apt-get >/dev/null; then
                          export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l
                          apt-get -o DPkg::Lock::Timeout=300 update
                          apt-get -o DPkg::Lock::Timeout=300 install -y --no-install-recommends python3 curl tar gzip coreutils util-linux findutils gawk e2fsprogs acl sudo iproute2
                        elif command -v dnf >/dev/null; then
                          dnf install -y python3 curl tar gzip coreutils util-linux findutils gawk e2fsprogs acl sudo iproute
                        else printf 'PREPARE_REJECT=platform-package-manager\\n'; exit 64; fi
                        prepare_stage=helper-installation
                        """
                + renderHelperInstallation() + renderSecurityInvariant() + "printf 'HELPER_INSTALL=1\\nPROTOCOL="
                + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\\nSHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\\n'\n";
    }

    /**
     * Renders stage diagnostics as text without executing the rendered command.
     * <p>渲染阶段诊断为文本，不执行所渲染命令。
     *
     * @return render stage diagnostics text / 渲染阶段诊断文本
     */
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

    /**
     * Renders security observation functions as text without executing the rendered command.
     * <p>渲染安全观测函数集合为文本，不执行所渲染命令。
     *
     * @return render security observation functions text / 渲染安全观测函数集合文本
     */
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

    /**
     * Renders security invariant as text without executing the rendered command.
     * <p>渲染安全不变量为文本，不执行所渲染命令。
     *
     * @return render security invariant text / 渲染安全不变量文本
     */
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

    /**
     * Renders helper installation as text without executing the rendered command.
     * <p>渲染helper安装为文本，不执行所渲染命令。
     *
     * @return render helper installation text / 渲染helper安装文本
     */
    public static String renderHelperInstallation() {
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
                printf '%%s  %%s\n' %s %s | /usr/bin/sha256sum --check --status
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
                """
                .formatted(quote(DeploymentManagedHelperBundle.renderScript()),
                        quote(DeploymentManagedHelperBundle.renderBuildEntry()),
                        quote(DeploymentManagedHelperBundle.DIRECTORY), quote(DeploymentManagedHelperBundle.PATH),
                        quote(DeploymentManagedHelperBundle.DIRECTORY + "/build-entry"),
                        quote(DeploymentManagedHelperBundle.EXPECTED_SHA256), quote(DeploymentManagedHelperBundle.PATH),
                        quote(DeploymentManagedHelperBundle.PATH), DeploymentManagedHelperBundle.PROTOCOL_VERSION);
    }

    /**
     * Quotes a literal argument for the fixed command-rendering boundary.
     * <p>为固定命令渲染边界引用字面参数。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return quote text / 引用文本
     */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
