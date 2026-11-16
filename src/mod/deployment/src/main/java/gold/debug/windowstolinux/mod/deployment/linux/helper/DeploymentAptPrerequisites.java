package gold.debug.windowstolinux.mod.deployment.linux.helper;

import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;

/** Fixed read-only Linux observation shared by native bootstrap and the package-free installer. */
final class DeploymentAptPrerequisites {
    private DeploymentAptPrerequisites() {
    }

    static String program(DownloadPoint point) {
        String os = switch (point) {
            case APT_UBUNTU -> "ubuntu";
            case APT_DEBIAN -> "debian";
            default -> throw new IllegalArgumentException("An observed APT repository point is required");
        };
        return DeploymentHelperInstallerScript.renderRuntimePathGuards() + """
            apt_prerequisite_file() {
              local mode size
              [ ! -L "$1" ] && [ -f "$1" ] &&
                [ "$(/usr/bin/stat -c '%u:%g' -- "$1")" = 0:0 ] || return 1
              mode=$(/usr/bin/stat -c '%a' -- "$1") || return 1
              size=$(/usr/bin/stat -c '%s' -- "$1") || return 1
              [[ "$mode" =~ ^[0-7]{3,4}$ && "$size" =~ ^[0-9]{1,9}$ ]] &&
                (( (8#$mode & 022) == 0 && size <= $2 ))
            }
            apt_prerequisite_os() {
              local release=/etc/os-release directory line observed=
              for directory in / /etc /usr /usr/lib; do files_trusted_directory "$directory" || return 1; done
              if [ -L "$release" ]; then
                [ "$(/usr/bin/stat -c '%u:%g' -- "$release")" = 0:0 ] || return 1
                case "$(/usr/bin/readlink -- "$release")" in
                  ../usr/lib/os-release | /usr/lib/os-release) release=/usr/lib/os-release ;;
                  *) return 1 ;;
                esac
              fi
              apt_prerequisite_file "$release" 16384 || return 1
              while IFS= read -r line || [ -n "$line" ]; do
                case "$line" in
                  ID=*)
                    [ -z "$observed" ] || return 1
                    case "$line" in
                      'ID=@APT_OS@' | 'ID="@APT_OS@"') observed=@APT_OS@ ;;
                      *) return 1 ;;
                    esac ;;
                esac
              done < "$release"
              [ "$observed" = @APT_OS@ ]
            }
            apt_prerequisites_observe() {
              local directory binary before after architecture report status=0 line name arch state error version path
              local absent=0 unlisted=0
              local -A expected=() seen=()
              local -a packages=(@APT_PACKAGES@)
              [ "$EUID" -eq 0 ] && apt_prerequisite_os || return 64
              for directory in / /var /var/lib /var/lib/dpkg; do files_trusted_directory "$directory" || return 64; done
              apt_prerequisite_file /var/lib/dpkg/status 268435456 || return 64
              for binary in /usr/bin/env /usr/bin/timeout /usr/bin/dpkg /usr/bin/dpkg-query; do
                files_trusted_runtime_path "$binary" || return 64
              done
              before=$(/usr/bin/stat -c '%d:%i:%s:%y:%z' -- /var/lib/dpkg/status) || return 64
              architecture=$(/usr/bin/env -i LC_ALL=C /usr/bin/timeout 15s /usr/bin/dpkg --print-architecture 2>/dev/null) || return 64
              [[ "$architecture" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || return 64
              report=$(/usr/bin/env -i LC_ALL=C /usr/bin/timeout 15s /usr/bin/dpkg-query -W \\
                -f='${Package}\\t${Architecture}\\t${db:Status-Status}\\t${db:Status-Eflag}\\t${Version}\\n' \\
                -- "${packages[@]}" 2>&1) || status=$?
              [ "$status" -le 1 ] && [ "${#report}" -le 8192 ] || return 64
              for name in "${packages[@]}"; do expected[$name]=1; done
              while IFS= read -r line; do
                if [[ "$line" =~ ^dpkg-query:\\ no\\ packages\\ found\\ matching\\ ([a-z0-9.+-]+)$ ]]; then
                  name=${BASH_REMATCH[1]}
                  absent=1
                  unlisted=1
                else
                  [[ "$line" =~ ^[a-z0-9.+-]+$'\\t'[a-z0-9-]+$'\\t'[a-z-]+$'\\t'[a-z-]+$'\\t'[a-zA-Z0-9.:+~_-]*$ ]] || return 64
                  IFS=$'\\t' read -r name arch state error version <<< "$line"
                  [ "$arch" = "$architecture" ] || [ "$arch" = all ] || return 64
                  [ "$error" = ok ] || return 64
                  case "$state" in
                    installed) [ -n "$version" ] || return 64 ;;
                    config-files | not-installed) absent=1 ;;
                    *) return 64 ;;
                  esac
                fi
                [ -n "${expected[$name]:-}" ] && [ -z "${seen[$name]:-}" ] || return 64
                seen[$name]=1
              done <<< "$report"
              [ "${#seen[@]}" -eq "${#packages[@]}" ] && [ "$status" -eq "$unlisted" ] || return 64
              if [ "$absent" -eq 0 ]; then
                for binary in @RUNTIME_COMMANDS@; do
                  path=$(type -P -- "$binary") && files_trusted_runtime_path "$path" || return 64
                done
              fi
              apt_prerequisite_file /var/lib/dpkg/status 268435456 || return 64
              after=$(/usr/bin/stat -c '%d:%i:%s:%y:%z' -- /var/lib/dpkg/status) || return 64
              [ "$before" = "$after" ] || return 64
              if [ "$absent" -eq 0 ]; then printf 'APT_PREREQUISITES=READY\\n'; else printf 'APT_PREREQUISITES=MISSING\\n'; fi
            }
            """.replace("@APT_OS@", os).replace("@APT_PACKAGES@", DeploymentHelperInstallerScript.APT_PACKAGES)
            .replace("@RUNTIME_COMMANDS@", String.join(" ", DeploymentHelperInstallerScript.RUNTIME_COMMANDS));
    }
}
