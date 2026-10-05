set -euo pipefail
export LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077

fail() { printf 'ENVIRONMENT_FAILED=%s\n' "$1" >&2; exit 64; }
directory() {
  [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = 0 ] || fail unsafe-directory
  [ $((8#$(stat -c %a "$1") & 022)) -eq 0 ] || fail writable-directory
}
regular() {
  [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = 0 ] || fail unsafe-file
  [ "$(stat -c %s "$1")" -le 16384 ] || fail oversized-file
  [ $((8#$(stat -c %a "$1") & 022)) -eq 0 ] || fail writable-file
}
write() {
  local path="$1" value="$2" temporary
  [ ! -L "$path" ] || fail unsafe-state-link
  temporary="$(mktemp "$state/.write.XXXXXX")"
  printf '%s\n' "$value" >"$temporary"
  chmod 600 "$temporary"
  sync -f "$temporary"
  mv -fT -- "$temporary" "$path"
  sync -f "$state"
}
binding() {
  printf 'TASK=%s\nOPERATION=%s\nTARGET_SHA256=%s\nBODY_SHA256=%s\nPROFILE=%s\n' "$task" "$operation" "$target_sha" "$body_sha" "$profile"
}
existing() {
  for parent in /var /var/lib /var/lib/windowstolinux /var/lib/windowstolinux/environment-operations; do
    if [ -e "$parent" ] || [ -L "$parent" ]; then directory "$parent"; fi
  done
  if [ -e "$state" ] || [ -L "$state" ]; then
    directory "$state"; regular "$state/binding"
    [ "$(cat "$state/binding")" = "$(binding)" ] || fail original-operation-conflict
    return 0
  fi
  return 1
}
create() {
  if existing; then return; fi
  directory /var; directory /var/lib
  for parent in /var/lib/windowstolinux /var/lib/windowstolinux/environment-operations; do
    [ -d "$parent" ] || install -d -o root -g root -m 700 "$parent"
    directory "$parent"
  done
  mkdir -m 700 "$state"
  write "$state/binding" "$(binding)"
}
security_state() {
  if command -v getenforce >/dev/null 2>&1; then getenforce
  elif [ -r /sys/module/apparmor/parameters/enabled ]; then
    printf 'apparmor:'; tr -d '\n' </sys/module/apparmor/parameters/enabled
  else printf 'none'; fi
}
firewall_state() {
  local service rules
  for service in firewalld ufw; do
    if systemctl list-unit-files "$service.service" --no-legend 2>/dev/null | grep -q "$service.service"; then
      printf '%s:' "$service"; systemctl is-active "$service" 2>/dev/null || :; return
    fi
  done
  if command -v nft >/dev/null 2>&1; then
    if rules="$(nft list ruleset 2>/dev/null)"; then
      [ -z "$rules" ] && printf 'nftables:inactive' || printf 'nftables:active'
    else printf 'nftables:unknown'; fi
  else printf 'none'; fi
}
identity() {
  [ "$(id -u)" -eq 0 ] && [ "$(id -un)" = root ] || fail root-management-required
  [ -r /etc/os-release ] || fail os-release-unavailable
  . /etc/os-release
  [ "$(uname -m)" = x86_64 ] || fail unsupported-architecture
  profile="${ID:-}-${VERSION_ID:-}"
  case "$profile" in
    ubuntu-22.04|ubuntu-24.04|debian-13)
      [ "$(dpkg --print-architecture)" = amd64 ] || fail unsupported-package-architecture
      manager=apt; cpu=v1 ;;
    centos-9) manager=dnf; cpu=v2 ;;
    centos-10|almalinux-10.2|rocky-10.2|ol-10.2) manager=dnf; cpu=v3 ;;
    almalinux-9.8|rocky-9.8|ol-9.7) manager=dnf; cpu=v1 ;;
    *) fail unsupported-distribution ;;
  esac
  command -v systemctl >/dev/null || fail missing-systemctl
  if [ "$manager" = dnf ]; then [ "$(rpm --eval '%{_arch}')" = x86_64 ] || fail unsupported-rpm-architecture; fi
  if [ "$cpu" != v1 ]; then
    local loader=
    for candidate in /lib64/ld-linux-x86-64.so.2 /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2; do
      if [ -x "$candidate" ]; then loader="$candidate"; break; fi
    done
    [ -n "$loader" ] && "$loader" --help 2>/dev/null | grep -Eq "x86-64-$cpu.*supported" || fail unsupported-cpu
  fi
  [ -z "$expected_profile" ] || [ "$profile" = "$expected_profile" ] || fail original-profile-changed
  boot="$(cat /proc/sys/kernel/random/boot_id)"
  security="$(security_state)"; firewall="$(firewall_state)"
  config_sha=none; selinux_phase=NOT_APPLICABLE
  case "$profile" in centos-9|centos-10) selinux_observe ;; esac
  package_phase=ABSENT; package_request=none
  if existing && [ -e "$state/packages" ]; then
    regular "$state/packages"; regular "$state/packages-request"
    package_phase="$(cat "$state/packages")"; package_request="$(cat "$state/packages-request")"
    case "$package_phase" in STARTED|SUCCEEDED|FAILED) ;; *) fail invalid-package-receipt ;; esac
  fi
}
observe() {
  identity
  printf 'ENVIRONMENT_FACTS_BEGIN\n'
  binding
  printf 'BOOT=%s\nCONFIG_SHA256=%s\nSECURITY=%s\nFIREWALL=%s\nSELINUX_PHASE=%s\nPACKAGE_PHASE=%s\nPACKAGE_REQUEST=%s\n' "$boot" "$config_sha" "$security" "$firewall" "$selinux_phase" "$package_phase" "$package_request"
  for receipt in prepare enforce commit; do
    value=none
    if existing && [ -e "$state/$receipt-request" ]; then regular "$state/$receipt-request"; value="$(cat "$state/$receipt-request")"; fi
    printf '%s_REQUEST=%s\n' "${receipt^^}" "$value"
  done
}
approved_facts() {
  identity
  [ "$boot" = "$expected_boot" ] && [ "$config_sha" = "$expected_config" ] \
    && [ "$security" = "$expected_security" ] && [ "$selinux_phase" = "$expected_selinux" ] \
    && [ "$firewall" = "$expected_firewall" ] || fail approved-facts-changed
}
package_names() {
  if [ "$manager" = apt ]; then
    packages='openjdk-21-jdk-headless maven curl sudo tar gzip iproute2 coreutils e2fsprogs skopeo acl sqlite3 uidmap rootlesskit slirp4netns fuse-overlayfs util-linux findutils gawk nodejs npm python3 python3-venv python3-pip cmake ninja-build gcc g++ docker.io podman'
    if [ "$profile" = ubuntu-24.04 ]; then packages="$packages golang-go rustc cargo dotnet-sdk-8.0 php-cli composer ruby ruby-bundler"; fi
  else
    packages='java-21-openjdk-devel maven curl sudo tar gzip iproute coreutils e2fsprogs skopeo acl sqlite shadow-utils util-linux findutils gawk nodejs npm cmake ninja-build gcc gcc-c++ podman'
    case "$profile" in *-9|*-9.*) packages="$packages openssh openssh-server openssh-clients python3.11 python3.11-pip" ;; *) packages="$packages python3.12 python3.12-pip" ;; esac
  fi
}
capabilities() {
  local python java_runtime=
  case "$profile" in ubuntu-22.04) python=python3.10 ;; ubuntu-24.04) python=python3.12 ;; debian-13) python=python3.13 ;; *-9|*-9.*) python=python3.11 ;; *) python=python3.12 ;; esac
  for candidate in /usr/lib/jvm/java-21-openjdk*/bin/java /usr/lib/jvm/jre-21-openjdk*/bin/java; do
    if [ -x "$candidate" ] && "$candidate" -version 2>&1 | grep -Eq '(^|[^0-9])21[.]'; then java_runtime="$candidate"; break; fi
  done
  [ -n "$java_runtime" ] || fail java-21-missing
  for tool in javac jar mvn curl tar gzip sqlite3 ss setsid timeout du node npm cmake ninja cc c++ podman "$python"; do command -v "$tool" >/dev/null || fail missing-tool; done
  "$python" -m venv --help >/dev/null 2>&1 || fail python-venv-missing
  "$python" -m pip --version >/dev/null 2>&1 || fail python-pip-missing
  /usr/sbin/sshd -t || fail invalid-sshd-configuration
  podman info >/dev/null 2>&1 || fail podman-unavailable
  if [ "$manager" = apt ]; then docker info >/dev/null 2>&1 || fail docker-unavailable; fi
  if [ "$profile" = ubuntu-24.04 ]; then
    node --version | grep -Eq '^v18[.]'
    go version | grep -Eq '^go version go1[.]2[2-4]([.][0-9]+)? '
    rustc --version | grep -Eq '^rustc 1[.](7[5-9]|8[0-9]|9[0-9])([.][0-9]+)? '
    cargo --version >/dev/null
    dotnet --version | grep -Eq '^(8|9)[.]0([.][0-9]+)?$'
    php -r 'exit(PHP_MAJOR_VERSION === 8 && PHP_MINOR_VERSION >= 2 && PHP_MINOR_VERSION <= 4 ? 0 : 1);'
    composer --version >/dev/null
    ruby -e 'exit(RUBY_VERSION.match?(/^3[.][234]([.][0-9]+)?$/) ? 0 : 1)'
    bundle --version >/dev/null
  fi
}
packages_install() {
  approved_facts
  [ "$manager" != dnf ] || [ "$security" = Enforcing ] || fail enforcing-required
  create
  [ ! -e "$state/packages" ] && [ ! -L "$state/packages" ] || fail original-install-already-sent
  write "$state/packages-request" "$request_sha"
  write "$state/packages" STARTED
  trap 'status=$?; if [ "$status" -ne 0 ]; then write "$state/packages" FAILED; fi; exit "$status"' EXIT
  package_names
  if [ "$manager" = apt ]; then
    export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l
    /usr/bin/apt-get -o DPkg::Lock::Timeout=300 update
    /usr/bin/apt-get -o DPkg::Lock::Timeout=300 install -y --no-install-recommends $packages
  elif [ "${ID:-}" = centos ]; then /usr/bin/dnf --enablerepo=crb -y install $packages
  else /usr/bin/dnf -y install $packages; fi
  capabilities
  [ "$(security_state)" = "$expected_security" ] || fail security-changed
  case "$expected_firewall" in *:active) [ "$(firewall_state)" = "$expected_firewall" ] || fail firewall-changed ;; esac
  write "$state/packages" SUCCEEDED
  trap - EXIT
  observe
}
environment() {
  state="/var/lib/windowstolinux/environment-operations/$task-$operation"
  reboot_unit="wtl-selinux-reboot-$task-$operation"
  rollback_unit="wtl-selinux-rollback-$task-$operation"
  case "$stage" in
    probe) observe ;;
    verify) approved_facts; [ "$package_phase" = SUCCEEDED ] || fail packages-unconfirmed; capabilities; observe ;;
    packages) packages_install ;;
    prepare|enforce|commit) approved_facts; selinux_change; observe ;;
    *) fail invalid-stage ;;
  esac
}
