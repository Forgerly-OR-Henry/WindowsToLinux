package gold.debug.windowstolinux.mod.deployment.linux.helper;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate;

/** Shared helper and isolation installation; no project language selection. / 共用 helper 及隔离安装，不选择项目语言。 */
public final class DeploymentHelperInstallerScript {
    /** Root-owned original installer receipt directory. / root 持有的原安装回执目录。 */
    public static final String RECEIPT_DIRECTORY = "/var/lib/windowstolinux/helper-install-operations";

    /** Fixed apt prerequisites used by the installer. / 安装器使用的固定 apt 依赖。 */
    public static final String APT_PACKAGES = "python3 curl tar gzip coreutils util-linux findutils gawk e2fsprogs acl sudo iproute2 sqlite3";

    /** Fixed dnf prerequisites used by the installer. / 安装器使用的固定 dnf 依赖。 */
    public static final String DNF_PACKAGES = "python3 curl tar gzip coreutils util-linux findutils gawk e2fsprogs acl sudo iproute sqlite";

    /** Fixed runtime prerequisites for files-only installation and its readiness observation. / 仅文件安装及其就绪观测的固定运行依赖。 */
    public static final java.util.List<String> RUNTIME_COMMANDS = java.util.List.of("bash", "env", "python3", "curl",
        "tar", "gzip", "stat", "readlink", "realpath", "sha256sum", "find", "awk", "grep", "sed", "install",
        "mktemp", "chmod", "chown", "rm", "mv", "cp", "cat", "head", "tail", "base64", "id", "sync", "timeout",
        "df", "du", "flock", "setpriv", "fallocate", "losetup", "mkfs.ext4", "mount", "umount", "findmnt",
        "systemctl", "systemd-run", "setfacl", "getfacl", "sqlite3", "ln", "tr", "mkdir");

    /** Prevents construction. / 禁止实例化。 */
    private DeploymentHelperInstallerScript() {
    }

    /** Renders the bounded managed platform installation. / 渲染有界受管平台安装。
     * @return bounded implementation-owned script / 有界实现自有脚本
     */
    public static String render() {
        return render(DeploymentRemoteLayout.original());
    }

    public static String render(DeploymentRemoteLayout layout) {
        return render("", "", layout);
    }

    /** Installs missing fixed files or prerequisites without registering recovery. / 补齐固定文件或依赖，不注册恢复服务。
     * @return complete independently reviewed installer / 完整独立审阅安装脚本 */
    public static String renderFiles() {
        return renderFiles(DeploymentRemoteLayout.original());
    }

    public static String renderFiles(DeploymentRemoteLayout layout) {
        return renderFiles("""
              if command -v apt-get >/dev/null; then
                export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l
                apt-get -o DPkg::Lock::Timeout=300 update
                apt-get -o DPkg::Lock::Timeout=300 install -y --no-install-recommends %s
              elif command -v dnf >/dev/null; then
                dnf install -y %s
              else printf 'PREPARE_REJECT=platform-package-manager\\n'; exit 64; fi
            """.formatted(APT_PACKAGES, DNF_PACKAGES), layout);
    }

    /** Reuses fixed file installation with an implementation-owned prerequisite stage, never model input.
     * @param prerequisitesScript complete fixed prerequisite statements with a final newline
     * @return original installer envelope with the supplied narrow prerequisite stage */
    public static String renderFiles(String prerequisitesScript) {
        return renderFiles(prerequisitesScript, DeploymentRemoteLayout.original());
    }

    public static String renderFiles(String prerequisitesScript, DeploymentRemoteLayout layout) {
        java.util.Objects.requireNonNull(prerequisitesScript);
        if (prerequisitesScript.isBlank() || !prerequisitesScript.endsWith("\n"))
            throw new IllegalArgumentException("Incomplete helper prerequisite stage");
        return "set -euo pipefail\nexport LC_ALL=C\nPATH=/usr/sbin:/usr/bin:/sbin:/bin\nexport PATH\nunset BASH_ENV ENV\n"
            + renderStageDiagnostics() + DeploymentRemoteLayoutAdmission.initialize(layout) + fixedLayout("""
            [ "$(id -u)" -eq 0 ] || exit 64
            files_trusted_directory() {
              [ ! -L "$1" ] && [ -d "$1" ] || return 1
              [ "$(/usr/bin/stat -c '%u:%g' -- "$1")" = '0:0' ] || return 1
              local mode
              mode=$(/usr/bin/stat -c '%a' -- "$1") || return 1
              (( (8#$mode & 022) == 0 ))
            }
            files_trusted_runtime_path() {
              local executable="$1" resolved=/ component target owner mode hops=0 steps=0
              local -a pending parts
              [[ "$executable" = /* ]] || return 1
              files_trusted_directory / || return 1
              IFS=/ read -r -a pending <<< "$executable"
              while [ "${#pending[@]}" -gt 0 ]; do
                steps=$((steps + 1)); [ "$steps" -le 512 ] || return 1
                component="${pending[0]}"; pending=("${pending[@]:1}")
                case "$component" in '' | .) continue ;; ..) resolved="${resolved%/*}"; [ -n "$resolved" ] || resolved=/; continue ;; esac
                executable="${resolved%/}/$component"
                if [ -L "$executable" ]; then
                  hops=$((hops + 1)); [ "$hops" -le 40 ] || return 1
                  owner=$(/usr/bin/stat -c '%u:%g' -- "$executable") || return 1
                  [ "$owner" = 0:0 ] || return 1
                  target=$(/usr/bin/readlink -- "$executable") || return 1
                  [[ -n "$target" && "$target" != *$'\\n'* ]] || return 1
                  [[ "$target" != /* ]] || resolved=/
                  IFS=/ read -r -a parts <<< "$target"
                  pending=("${parts[@]}" "${pending[@]}")
                  continue
                fi
                if [ "${#pending[@]}" -gt 0 ]; then
                  files_trusted_directory "$executable" || return 1
                else
                  [ -f "$executable" ] && [ -x "$executable" ] || return 1
                  owner=$(/usr/bin/stat -c '%u:%g' -- "$executable") || return 1
                  mode=$(/usr/bin/stat -c '%a' -- "$executable") || return 1
                  [ "$owner" = 0:0 ] && [[ "$mode" =~ ^[0-7]{3,4}$ ]] || return 1
                  (( (8#$mode & 022) == 0 )) || return 1
                fi
                resolved="$executable"
              done
              [ -f "$resolved" ] && [ -x "$resolved" ]
            }
            files_check_one() {
              [ -e "$1" ] || [ -L "$1" ] || { files_missing=$((files_missing + 1)); return 0; }
              [ ! -L "$1" ] && [ -f "$1" ] && [ -x "$1" ] || return 1
              [ "$(/usr/bin/stat -c '%u:%g:%a:%h' -- "$1")" = '0:0:755:1' ] || return 1
              printf '%s  %s\n' "$2" "$1" | /usr/bin/sha256sum --check --status
            }
            files_check() {
              files_missing=0
              for parent in @HELPER_EXISTING_PARENTS@; do
                [ ! -e "$parent" ] && [ ! -L "$parent" ] && continue
                files_trusted_directory "$parent" || { printf 'PREPARE_REJECT=installer-parent-ownership\\n'; exit 64; }
              done
              if [ -e @wtl:helper-directory@ ] || [ -L @wtl:helper-directory@ ]; then
                files_trusted_directory @wtl:helper-directory@ || { printf 'PREPARE_REJECT=helper-directory-ownership\\n'; exit 64; }
              fi
            """, layout) + "  files_check_one " + quote(layout.helperPath()) + " "
            + quote(DeploymentManagedHelperBundle.helperSha256(layout))
            + " || { printf 'PREPARE_REJECT=helper-file-conflict\\n'; exit 64; }\n"
            + "  files_check_one " + quote(layout.buildEntry()) + " "
            + quote(DeploymentManagedHelperBundle.buildEntrySha256(layout))
            + " || { printf 'PREPARE_REJECT=build-entry-conflict\\n'; exit 64; }\n}\n"
            + "files_check_runtime() {\n  runtime_missing=0\n  for runtime_command in "
            + String.join(" ", RUNTIME_COMMANDS) + "; do\n" + """
                runtime_path=$(type -P -- "$runtime_command") || { runtime_missing=1; continue; }
                files_trusted_runtime_path "$runtime_path" || { printf 'PREPARE_REJECT=runtime-command-ownership\\n'; exit 64; }
              done
            }
            prepare_stage=files-readiness
            files_check
            files_check_runtime
            if [ "$files_missing" -eq 0 ] && [ "$runtime_missing" -eq 0 ]; then
              printf 'PREPARE_REJECT=files-install-not-required\\n'; exit 64
            fi
            """ + renderSecurityObservationFunctions() + renderInstallationLock(layout) + """
            prepare_stage=platform-packages
            if [ "$runtime_missing" -ne 0 ]; then
            """ + prerequisitesScript + fixedLayout("""
            fi
            prepare_stage=helper-files-installation
            files_check
            files_check_runtime
            [ "$runtime_missing" -eq 0 ] || { printf 'PREPARE_REJECT=runtime-command-missing\\n'; exit 64; }
            if [ ! -e @wtl:helper-directory@ ]; then
              /usr/bin/install -d -o root -g root -m 755 -- @wtl:helper-directory@
            fi
            files_check
            if [ ! -e %s ]; then
              helper_tmp=$(/usr/bin/mktemp @wtl:helper-directory@/.managed-helper.XXXXXX)
              cleanup_files="$cleanup_files $helper_tmp"
              printf '%%s' %s > "$helper_tmp"
              /usr/bin/chown root:root -- "$helper_tmp"
              /usr/bin/chmod 755 -- "$helper_tmp"
              files_check_one "$helper_tmp" %s || exit 64
              /usr/bin/ln -T -- "$helper_tmp" %s
              /usr/bin/rm -- "$helper_tmp"
            fi
            if [ ! -e %s ]; then
              entry_tmp=$(/usr/bin/mktemp @wtl:helper-directory@/.build-entry.XXXXXX)
              cleanup_files="$cleanup_files $entry_tmp"
              printf '%%s' %s > "$entry_tmp"
              /usr/bin/chown root:root -- "$entry_tmp"
              /usr/bin/chmod 755 -- "$entry_tmp"
              files_check_one "$entry_tmp" %s || exit 64
              /usr/bin/ln -T -- "$entry_tmp" %s
              /usr/bin/rm -- "$entry_tmp"
            fi
            files_check
            [ "$files_missing" -eq 0 ] || exit 64
            helper_probe="$(%s probe)"
            printf '%%s\\n' "$helper_probe" | /usr/bin/grep -qx 'HELPER=1'
            printf '%%s\\n' "$helper_probe" | /usr/bin/grep -qx 'PROTOCOL=%d'
            """, layout).formatted(quote(layout.helperPath()),
            quote(DeploymentManagedHelperBundle.renderScript(layout)), quote(DeploymentManagedHelperBundle.helperSha256(layout)),
            quote(layout.helperPath()), quote(layout.buildEntry()),
            quote(DeploymentManagedHelperBundle.renderBuildEntry(layout)), quote(DeploymentManagedHelperBundle.buildEntrySha256(layout)),
            quote(layout.buildEntry()), quote(layout.helperPath()),
            DeploymentManagedHelperBundle.PROTOCOL_VERSION)
            + renderSecurityInvariant() + "printf 'HELPER_INSTALL=1\\nPROTOCOL="
            + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\\nSHA256="
            + DeploymentManagedHelperBundle.helperSha256(layout) + "\\n'\n";
    }

    /** Renders one installer with an original operation receipt written before and after its effects. / 渲染一项在副作用前后写入原操作回执的安装程序。
     * @param taskId original task UUID / 原任务 UUID
     * @param operationId original installer UUID / 原安装 UUID
     * @param serverDigest exact server identity digest / 精确服务器身份摘要
     * @param bodyDigest digest of the unchanged installer body / 未修改安装主体的摘要
     * @return bounded operation-specific Bash script / 有界的操作专属 Bash 脚本 */
    public static String renderTracked(java.util.UUID taskId, java.util.UUID operationId, String serverDigest,
                                       String bodyDigest) {
        return renderTracked(taskId, operationId, serverDigest, bodyDigest, DeploymentRemoteLayout.original());
    }

    public static String renderTracked(java.util.UUID taskId, java.util.UUID operationId, String serverDigest,
                                       String bodyDigest, DeploymentRemoteLayout layout) {
        java.util.Objects.requireNonNull(taskId);
        java.util.Objects.requireNonNull(operationId);
        if (serverDigest == null || !serverDigest.matches("[0-9a-f]{64}") || bodyDigest == null
            || !bodyDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid installer operation digest");
        String started = receipt(taskId, operationId, serverDigest, bodyDigest, "STARTED", layout);
        String succeeded = receipt(taskId, operationId, serverDigest, bodyDigest, "SUCCEEDED", layout);
        String directory = quote(layout.helperInstallOperationsRoot());
        String receipt = quote(layout.helperInstallOperationsRoot() + "/" + operationId + ".receipt");
        String begin = fixedLayout("""
            prepare_stage=installer-receipt
            bootstrap_directory=%s
            bootstrap_receipt=%s
            bootstrap_trusted_directory() {
              [ ! -L "$1" ] && [ -d "$1" ] || return 1
              [ "$(/usr/bin/stat -c '%%u:%%g' "$1")" = '0:0' ] || return 1
              bootstrap_mode=$(/usr/bin/stat -c '%%a' "$1")
              (( (8#$bootstrap_mode & 022) == 0 ))
            }
            for bootstrap_parent in @STATE_EXISTING_PARENTS@; do
              [ ! -e "$bootstrap_parent" ] && [ ! -L "$bootstrap_parent" ] && continue
              bootstrap_trusted_directory "$bootstrap_parent" || exit 64
            done
            if [ -e @wtl:state-root@ ] || [ -L @wtl:state-root@ ]; then
              bootstrap_trusted_directory @wtl:state-root@ || exit 64
            else
              /usr/bin/install -d -o root -g root -m 700 @wtl:state-root@
            fi
            bootstrap_trusted_directory @wtl:state-root@ || exit 64
            if [ -e "$bootstrap_directory" ] || [ -L "$bootstrap_directory" ]; then
              bootstrap_trusted_directory "$bootstrap_directory" || exit 64
            else
              /usr/bin/install -d -o root -g root -m 700 "$bootstrap_directory"
            fi
            [ "$(/usr/bin/stat -c '%%u:%%g:%%a' "$bootstrap_directory")" = '0:0:700' ] || exit 64
            umask 077
            ( set -C; printf '%%s' %s > "$bootstrap_receipt" ) || exit 64
            /usr/bin/sync -f "$bootstrap_receipt"
            /usr/bin/sync -f "$bootstrap_directory"
            """, layout).formatted(directory, receipt, quote(started));
        String end = """
            prepare_stage=installer-receipt-terminal
            bootstrap_terminal=$(/usr/bin/mktemp "$bootstrap_directory/.%s.XXXXXX")
            cleanup_files="$cleanup_files $bootstrap_terminal"
            printf '%%s' %s > "$bootstrap_terminal"
            /usr/bin/sync -f "$bootstrap_terminal"
            /usr/bin/mv -fT -- "$bootstrap_terminal" "$bootstrap_receipt"
            /usr/bin/sync -f "$bootstrap_directory"
            """.formatted(operationId, quote(succeeded));
        return render(begin, end, layout);
    }

    /** Resolves only the exact current fixed installer and its original receipt, before review or dispatch. */
    public static java.util.UUID trackedOperation(String executable, java.util.UUID expectedTask,
            DeploymentRemoteLayout layout) {
        java.util.Objects.requireNonNull(expectedTask);
        var receipt = java.util.regex.Pattern.compile("FORMAT=1\\nTASK=([0-9a-f-]{36})\\nOPERATION=([0-9a-f-]{36})\\nSERVER_SHA256=([0-9a-f]{64})\\nBODY_SHA256=([0-9a-f]{64})\\nHELPER_SHA256=([0-9a-f]{64})\\nPHASE=STARTED\\n").matcher(executable);
        if (!receipt.find() || !expectedTask.toString().equals(receipt.group(1)))
            throw new IllegalArgumentException("Tracked installer requires its original task receipt");
        java.util.UUID operation = java.util.UUID.fromString(receipt.group(2));
        String bodyDigest;
        try {
            bodyDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(render(layout).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        if (!bodyDigest.equals(receipt.group(4)) || !DeploymentManagedHelperBundle.helperSha256(layout).equals(receipt.group(5))
            || !renderTracked(expectedTask, operation, receipt.group(3), bodyDigest, layout).equals(executable))
            throw new IllegalArgumentException("Tracked installer differs from its exact body, helper or receipt envelope");
        return operation;
    }

    /** Builds one exact non-secret original installer receipt. / 构造精确的非秘密原安装回执。
     * @param taskId original task UUID / 原任务 UUID
     * @param operationId original installer UUID / 原安装 UUID
     * @param serverDigest exact server identity digest / 精确服务器身份摘要
     * @param bodyDigest unchanged installer body digest / 未修改安装主体摘要
     * @param phase original receipt phase / 原回执阶段
     * @return exact bounded receipt bytes as text / 精确有界回执文本 */
    public static String receipt(java.util.UUID taskId, java.util.UUID operationId, String serverDigest,
                                 String bodyDigest, String phase) {
        return receipt(taskId, operationId, serverDigest, bodyDigest, phase, DeploymentRemoteLayout.original());
    }

    public static String receipt(java.util.UUID taskId, java.util.UUID operationId, String serverDigest,
                                 String bodyDigest, String phase, DeploymentRemoteLayout layout) {
        if (taskId == null || operationId == null || serverDigest == null || !serverDigest.matches("[0-9a-f]{64}")
            || bodyDigest == null || !bodyDigest.matches("[0-9a-f]{64}")
            || !java.util.Set.of("STARTED", "SUCCEEDED").contains(phase))
            throw new IllegalArgumentException("Invalid installer receipt identity");
        return "FORMAT=1\nTASK=" + taskId + "\nOPERATION=" + operationId + "\nSERVER_SHA256=" + serverDigest
            + "\nBODY_SHA256=" + bodyDigest + "\nHELPER_SHA256=" + DeploymentManagedHelperBundle.helperSha256(layout)
            + "\nPHASE=" + phase + "\n";
    }

    /** Renders a read-only receipt query requiring only pre-existing Bash and core utilities. / 渲染仅依赖原有 Bash 和基础工具的只读回执查询。
     * @param operationId original installer UUID / 原安装 UUID
     * @return fixed bounded read-only Bash script / 固定有界只读 Bash 脚本 */
    public static String renderStatus(java.util.UUID operationId) {
        return renderStatus(operationId, DeploymentRemoteLayout.original());
    }

    public static String renderStatus(java.util.UUID operationId, DeploymentRemoteLayout layout) {
        return renderReceiptStatus(operationId, layout, layout.helperInstallOperationsRoot());
    }

    public static String renderReceiptStatus(java.util.UUID operationId, DeploymentRemoteLayout layout, String directory) {
        java.util.Objects.requireNonNull(operationId);
        if (!java.util.Set.of(layout.helperInstallOperationsRoot(), layout.modelCommandOperationsRoot()).contains(directory))
            throw new IllegalArgumentException("Unknown owned receipt directory");
        return fixedLayout("""
            set -euo pipefail
            export LC_ALL=C
            bootstrap_directory=%s
            bootstrap_receipt=%s
            for bootstrap_parent in @STATE_ALL_PARENTS@ "$bootstrap_directory"; do
              [ ! -L "$bootstrap_parent" ] && [ -d "$bootstrap_parent" ] || exit 3
              [ "$(/usr/bin/stat -c '%%u:%%g' "$bootstrap_parent")" = '0:0' ] || exit 3
              bootstrap_mode=$(/usr/bin/stat -c '%%a' "$bootstrap_parent")
              (( (8#$bootstrap_mode & 022) == 0 )) || exit 3
            done
            [ "$(/usr/bin/stat -c '%%u:%%g:%%a' "$bootstrap_directory")" = '0:0:700' ] || exit 3
            [ ! -L "$bootstrap_receipt" ] && [ -f "$bootstrap_receipt" ] || exit 3
            [ "$(/usr/bin/stat -c '%%u:%%g:%%a' "$bootstrap_receipt")" = '0:0:600' ] || exit 3
            [ "$(/usr/bin/stat -c '%%s' "$bootstrap_receipt")" -le 1024 ] || exit 3
            /usr/bin/cat -- "$bootstrap_receipt"
            """, layout).formatted(quote(directory), quote(directory + "/" + operationId + ".receipt"));
    }

    /** Combines unchanged installer stages with optional original-operation receipt steps. / 将未改变的安装阶段与可选原操作回执步骤组合。
     * @param begin operation receipt before effects / 副作用前操作回执
     * @param end terminal receipt before response / 回包前终态回执
     * @return complete installer script / 完整安装脚本 */
    private static String render(String begin, String end, DeploymentRemoteLayout layout) {
        return "set -euo pipefail\nexport LC_ALL=C\n" + renderStageDiagnostics() + renderSecurityObservationFunctions()
            + """
            [ "$(id -u)" -eq 0 ] || exit 64
            command -v systemctl >/dev/null
            """ + DeploymentRemoteLayoutAdmission.initialize(layout) + begin + renderInstallationLock(layout)
            + """
            prepare_stage=platform-packages
            if command -v apt-get >/dev/null; then
              export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l
              apt-get -o DPkg::Lock::Timeout=300 update
              apt-get -o DPkg::Lock::Timeout=300 install -y --no-install-recommends %s
            elif command -v dnf >/dev/null; then
              dnf install -y %s
            else printf 'PREPARE_REJECT=platform-package-manager\\n'; exit 64; fi
            prepare_stage=helper-installation
            """
            .formatted(APT_PACKAGES, DNF_PACKAGES) + renderHelperInstallation(layout) + renderSecurityInvariant() + end + "printf 'HELPER_INSTALL=1\\nPROTOCOL="
            + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\\nSHA256="
            + DeploymentManagedHelperBundle.helperSha256(layout) + "\\n'\n";
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
            installer_lock=
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
        return renderHelperInstallation(DeploymentRemoteLayout.original());
    }

    public static String renderHelperInstallation(DeploymentRemoteLayout layout) {
        return DeploymentRemoteLayoutAdmission.initialize(layout) + renderInstallationLock(layout) + fixedLayout("""
            [ "$(id -u)" -eq 0 ] || { printf 'PREPARE_REJECT=root-management-required\\n'; exit 64; }
            installer_trusted_path() {
              [ ! -L "$1" ] || return 1
              if [ "$2" = directory ]; then [ -d "$1" ] || return 1
              else [ -f "$1" ] || return 1; fi
              [ "$(/usr/bin/stat -c '%%u:%%g' "$1")" = '0:0' ] || return 1
              installer_mode=$(/usr/bin/stat -c '%%a' "$1")
              (( (8#$installer_mode & 022) == 0 ))
            }
            for installer_parent in @HELPER_EXISTING_PARENTS@ /etc /etc/systemd /etc/systemd/system; do
              [ ! -e "$installer_parent" ] && [ ! -L "$installer_parent" ] && continue
              installer_trusted_path "$installer_parent" directory || { printf 'PREPARE_REJECT=installer-parent-ownership\\n'; exit 64; }
            done
            installer_directory=@wtl:helper-directory@
            if [ -e "$installer_directory" ] || [ -L "$installer_directory" ]; then
              installer_trusted_path "$installer_directory" directory || { printf 'PREPARE_REJECT=helper-directory-ownership\\n'; exit 64; }
            else
              /usr/bin/install -d -o root -g root -m 755 -- "$installer_directory"
            fi
            installer_trusted_path "$installer_directory" directory || exit 64
            installer_trusted_path "$installer_directory" directory || exit 64
            for installer_file in @wtl:helper-directory@/managed-helper @wtl:helper-directory@/build-entry /etc/systemd/system/windowstolinux-workspace-recovery.service; do
              if [ -e "$installer_file" ] || [ -L "$installer_file" ]; then
                installer_trusted_path "$installer_file" file || { printf 'PREPARE_REJECT=installer-file-ownership\\n'; exit 64; }
              fi
            done
            helper_tmp=$(/usr/bin/mktemp "$installer_directory/.managed-helper.XXXXXX")
            entry_tmp=$(/usr/bin/mktemp "$installer_directory/.build-entry.XXXXXX")
            cleanup_files="$cleanup_files $helper_tmp $entry_tmp"
            printf '%%s' %s > "$helper_tmp"
            printf '%%s' %s > "$entry_tmp"
            /usr/bin/chown root:root -- "$helper_tmp" "$entry_tmp"
            /usr/bin/chmod 755 -- "$helper_tmp" "$entry_tmp"
            installer_verify_executable() {
              [ ! -L "$1" ] && [ -f "$1" ] || return 1
              [ "$(/usr/bin/stat -c '%%u:%%g:%%a:%%h' -- "$1")" = '0:0:755:1' ] || return 1
              printf '%%s  %%s\n' "$2" "$1" | /usr/bin/sha256sum --check --status
            }
            installer_verify_executable "$helper_tmp" %s || { printf 'PREPARE_REJECT=helper-payload-digest\\n'; exit 64; }
            installer_verify_executable "$entry_tmp" %s || { printf 'PREPARE_REJECT=build-entry-payload-digest\\n'; exit 64; }
            /usr/bin/sync -f "$helper_tmp"
            /usr/bin/sync -f "$entry_tmp"
            /usr/bin/mv -fT -- "$helper_tmp" %s
            /usr/bin/mv -fT -- "$entry_tmp" %s
            /usr/bin/sync -f "$installer_directory"
            installer_verify_executable %s %s || { printf 'PREPARE_REJECT=helper-installed-digest\\n'; exit 64; }
            installer_verify_executable %s %s || { printf 'PREPARE_REJECT=build-entry-installed-digest\\n'; exit 64; }
            systemctl daemon-reload
            systemctl enable windowstolinux-workspace-recovery.service
            helper_probe="$(%s probe)"
            printf '%%s\\n' "$helper_probe" | /usr/bin/grep -qx 'HELPER=1'
            printf '%%s\\n' "$helper_probe" | /usr/bin/grep -qx 'PROTOCOL=%d'
            """, layout)
            .formatted(quote(DeploymentManagedHelperBundle.renderScript(layout)),
                quote(DeploymentManagedHelperBundle.renderBuildEntry(layout)),
                quote(DeploymentManagedHelperBundle.helperSha256(layout)),
                quote(DeploymentManagedHelperBundle.buildEntrySha256(layout)),
                quote(layout.helperPath()), quote(layout.buildEntry()),
                quote(layout.helperPath()), quote(DeploymentManagedHelperBundle.helperSha256(layout)),
                quote(layout.buildEntry()), quote(DeploymentManagedHelperBundle.buildEntrySha256(layout)),
                quote(layout.helperPath()), DeploymentManagedHelperBundle.PROTOCOL_VERSION);
    }

    private static String renderInstallationLock(DeploymentRemoteLayout layout) {
        return fixedLayout("""
            prepare_stage=installer-lock
            [ "$(id -u)" -eq 0 ] || exit 64
            installer_lock_directory=@wtl:helper-directory@
            installer_lock_trusted_directory() {
              [ ! -L "$1" ] && [ -d "$1" ] || return 1
              [ "$(/usr/bin/stat -c '%u:%g' -- "$1")" = '0:0' ] || return 1
              installer_lock_mode=$(/usr/bin/stat -c '%a' -- "$1") || return 1
              (( (8#$installer_lock_mode & 022) == 0 ))
            }
            for installer_lock_parent in @HELPER_EXISTING_PARENTS@; do
              [ ! -e "$installer_lock_parent" ] && [ ! -L "$installer_lock_parent" ] && continue
              installer_lock_trusted_directory "$installer_lock_parent" || { printf 'PREPARE_REJECT=installer-lock-parent-ownership\\n'; exit 64; }
            done
            if [ ! -e "$installer_lock_directory" ] && [ ! -L "$installer_lock_directory" ]; then
              /usr/bin/install -d -o root -g root -m 755 -- "$installer_lock_directory"
            fi
            installer_lock_trusted_directory "$installer_lock_directory" || { printf 'PREPARE_REJECT=installer-lock-directory-ownership\\n'; exit 64; }
            if [ -z "${installer_lock:-}" ]; then
              command -v /usr/bin/flock >/dev/null || { printf 'PREPARE_REJECT=installer-lock-runtime-missing\\n'; exit 64; }
              exec {installer_lock}< "$installer_lock_directory"
              /usr/bin/flock -x "$installer_lock"
            else
              [[ "$installer_lock" =~ ^[0-9]+$ ]] && [ "/dev/fd/$installer_lock" -ef "$installer_lock_directory" ] || exit 64
            fi
            installer_lock_trusted_directory "$installer_lock_directory" || exit 64
            """, layout);
    }

    private static String fixedLayout(String template, DeploymentRemoteLayout layout) {
        String helperParents = DeploymentRemoteLayoutAdmission.existingParents(layout.paths().helperDirectory());
        String stateParents = DeploymentRemoteLayoutAdmission.existingParents(layout.paths().stateRoot());
        String stateAll = String.join(" ", DeploymentRemoteLayoutAdmission.ancestors(layout.paths().stateRoot()).stream()
            .map(DeploymentHelperInstallerScript::quote).toList());
        return DeploymentRemoteLayoutTemplate.render(template.replace("@HELPER_EXISTING_PARENTS@", helperParents)
            .replace("@STATE_EXISTING_PARENTS@", stateParents).replace("@STATE_ALL_PARENTS@", stateAll), layout);
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
