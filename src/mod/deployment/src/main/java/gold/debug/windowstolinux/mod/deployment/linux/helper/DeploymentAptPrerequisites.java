package gold.debug.windowstolinux.mod.deployment.linux.helper;

import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;

/** Fixed read-only Linux observation shared by native bootstrap and the package-free installer. */
final class DeploymentAptPrerequisites {
    private DeploymentAptPrerequisites() {
    }

    static String program(DownloadPoint point) {
        return program(point, true);
    }

    static String observationProgram(DownloadPoint point) {
        return metadataGuard() + "[ \"$EUID\" -eq 0 ] && apt_prerequisite_metadata || exit 64\n"
            + program(point, false) + "apt_prerequisites_observe\n";
    }

    /** Renders the previous bytes for matching an existing installation receipt. */
    static String previouslySentProgram(DownloadPoint point) {
        return program(point, false);
    }

    private static String metadataGuard() {
        return """
            apt_prerequisite_metadata() {
              local path
              # Perl is part of the Debian/Ubuntu base system and supplies independent filesystem metadata.
              for path in / /usr /usr/bin; do
                [ ! -L "$path" ] && [ -O "$path" ] && [ -d "$path" ] && [ -x "$path" ] || return 1
              done
              [ ! -L /usr/bin/perl ] && [ -O /usr/bin/perl ] && [ -f /usr/bin/perl ] && [ -x /usr/bin/perl ] || return 1
              LC_ALL=C /usr/bin/perl -T -f <<'APT_PREREQUISITE_METADATA'
            sub root_metadata {
                my ($path) = @_;
                my @metadata = lstat($path);
                return unless @metadata && $metadata[4] == 0 && $metadata[5] == 0;
                return $metadata[2];
            }
            sub protected_type {
                my ($mode, $type) = @_;
                return defined($mode) && ($mode & 0170000) == $type
                    && ($mode & 022) == 0 && ($mode & 0111) != 0;
            }
            sub trusted_executable {
                my ($path) = @_;
                my @pending = split m{/}, $path, -1;
                my @resolved;
                my ($hops, $steps) = (0, 0);
                while (@pending) {
                    return if ++$steps > 512;
                    my $component = shift @pending;
                    next if $component eq '' || $component eq '.';
                    if ($component eq '..') {
                        pop @resolved;
                        next;
                    }
                    my $entry = '/' . join('/', @resolved, $component);
                    my $mode = root_metadata($entry);
                    return unless defined($mode);
                    if (($mode & 0170000) == 0120000) {
                        # Linux ignores symlink permission bits. Check ownership above and check the target below.
                        return if ++$hops > 40;
                        my $target = readlink($entry);
                        return unless defined($target) && length($target) && $target !~ /\\n/;
                        @resolved = () if substr($target, 0, 1) eq '/';
                        unshift @pending, split m{/}, $target, -1;
                    } elsif (@pending) {
                        return unless protected_type($mode, 0040000);
                        push @resolved, $component;
                    } else {
                        return protected_type($mode, 0100000);
                    }
                }
                return;
            }
            for my $directory ('/', '/usr', '/usr/bin') {
                exit 1 unless protected_type(root_metadata($directory), 0040000);
            }
            exit 1 unless protected_type(root_metadata('/usr/bin/perl'), 0100000);
            for my $tool ('/usr/bin/stat', '/usr/bin/readlink') {
                exit 1 unless trusted_executable($tool);
            }
            exit 0;
            APT_PREREQUISITE_METADATA
            }
            """;
    }

    private static String program(DownloadPoint point, boolean guardMetadata) {
        String os = switch (point) {
            case APT_UBUNTU -> "ubuntu";
            case APT_DEBIAN -> "debian";
            default -> throw new IllegalArgumentException("An observed APT repository point is required");
        };
        return DeploymentHelperInstallerScript.renderRuntimePathGuards() + (guardMetadata ? metadataGuard() : "") + """
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
              [ "$EUID" -eq 0 ] && @METADATA_CHECK@apt_prerequisite_os || return 64
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
            .replace("@METADATA_CHECK@", guardMetadata ? "apt_prerequisite_metadata && " : "")
            .replace("@RUNTIME_COMMANDS@", String.join(" ", DeploymentHelperInstallerScript.RUNTIME_COMMANDS));
    }
}
