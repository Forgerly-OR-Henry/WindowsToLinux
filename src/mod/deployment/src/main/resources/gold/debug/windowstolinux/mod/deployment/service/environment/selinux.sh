selinux_observe() {
  local config=/etc/selinux/config original_boot phase
  directory /etc; directory /etc/selinux; regular "$config"
  config_sha="$(sha256sum "$config" | cut -d ' ' -f1)"
  case "$security" in Disabled|Permissive|Enforcing) ;; *) fail unknown-selinux-state ;; esac
  # Historical global preparation has no original task/op proof; never adopt it.
  [ ! -e /var/lib/windowstolinux/system-preparation/selinux ] && [ ! -L /var/lib/windowstolinux/system-preparation/selinux ] || fail foreign-preparation-state
  for unit in windowstolinux-selinux-reboot.timer windowstolinux-selinux-rollback.timer; do
    [ "$(systemctl show "$unit" -p LoadState --value)" = not-found ] || fail foreign-security-timer
  done
  if [ -e /var/lib/windowstolinux/environment-operations ] || [ -L /var/lib/windowstolinux/environment-operations ]; then
    directory /var/lib/windowstolinux; directory /var/lib/windowstolinux/environment-operations
    for other in /var/lib/windowstolinux/environment-operations/*/selinux-phase; do
      [ -e "$other" ] || [ -L "$other" ] || continue
      [ "$other" = "$state/selinux-phase" ] && continue
      directory "${other%/*}"; regular "$other"
      [ "$(cat "$other")" = complete ] || fail foreign-security-operation
    done
  fi
  selinux_phase=UNPREPARED
  if existing && [ -e "$state/selinux-phase" ]; then
    for item in config.before boot-id selinux-phase permissive-sha256 prepare-request; do regular "$state/$item"; done
    original_boot="$(cat "$state/boot-id")"; phase="$(cat "$state/selinux-phase")"
    case "$phase" in
      reboot)
        [ "$config_sha" = "$(cat "$state/permissive-sha256")" ] || fail configuration-changed
        if [ "$boot" = "$original_boot" ]; then selinux_phase=REBOOT_PENDING
        else
          [ "$security" = Permissive ] || fail unexpected-post-reboot-security
          if [ -e /.autorelabel ] || [ -L /.autorelabel ]; then selinux_phase=REBOOT_PENDING
          else selinux_phase=READY_TO_ENFORCE; fi
        fi ;;
      enforcing)
        regular "$state/enforce-request"
        [ "$boot" != "$original_boot" ] || fail reboot-not-observed
        [ "$config_sha" = "$(cat "$state/permissive-sha256")" ] || fail configuration-changed
        [ "$security" = Enforcing ] || fail enforcement-reverted
        systemctl is-active --quiet "$rollback_unit.timer" || fail safety-timer-missing
        selinux_phase=ENFORCEMENT_PENDING ;;
      complete)
        regular "$state/commit-request"; regular "$state/enforcing-sha256"
        [ "$security" = Enforcing ] && [ "$config_sha" = "$(cat "$state/enforcing-sha256")" ] \
          && grep -qx 'SELINUX=enforcing' "$config" || fail completed-security-changed
        [ "$(systemctl show "$rollback_unit.timer" -p ActiveState --value)" = inactive ] || fail rollback-timer-still-active
        selinux_phase=COMPLETE ;;
      *) fail unknown-preparation-state ;;
    esac
  elif [ "$security" = Enforcing ]; then selinux_phase=COMPLETE
  fi
}
selinux_post_reboot() {
  [ ! -e /.autorelabel ] && [ ! -L /.autorelabel ] || fail relabel-incomplete
  [ "$(systemctl show selinux-autorelabel.service -p Result --value)" = success ] || fail relabel-service-failed
  local status=0 output
  output="$(ausearch --input-logs -m AVC,USER_AVC -ts boot 2>&1)" || status=$?
  [ "$status" -eq 1 ] && printf '%s' "$output" | grep -q '<no matches>' || fail unresolved-avc-or-audit-error
}
selinux_change() {
  case "$profile" in centos-9|centos-10) ;; *) fail unsupported-selinux-preparation ;; esac
  for utility in flock fixfiles setenforce systemd-run systemctl ausearch; do command -v "$utility" >/dev/null || fail missing-system-utility; done
  rpm -q selinux-policy-targeted policycoreutils libselinux-utils >/dev/null || fail missing-selinux-package
  local config=/etc/selinux/config
  if [ "$stage" = prepare ]; then
    [ "$selinux_phase" = UNPREPARED ] || fail invalid-prepare-state
    [ "$(grep -c '^SELINUX=' "$config")" -eq 1 ] && grep -Eq '^SELINUX=(disabled|permissive)$' "$config" || fail unsupported-selinux-configuration
    grep -qx 'SELINUXTYPE=targeted' "$config" || fail unsupported-selinux-policy
    ! grep -Eq '(^|[[:space:]])selinux=0([[:space:]]|$)' /proc/cmdline || fail selinux-disabled-by-kernel
    for unit in "$reboot_unit" "$rollback_unit"; do
      [ "$(systemctl show "$unit.timer" -p LoadState --value)" = not-found ] || fail timer-collision
    done
    create
    [ ! -e "$state/selinux-phase" ] && [ ! -L "$state/selinux-phase" ] || fail original-prepare-already-sent
    cp -p -- "$config" "$state/config.before"; chmod 600 "$state/config.before"; sync -f "$state/config.before"
    write "$state/boot-id" "$boot"
    write "$state/prepare-request" "$request_sha"
    sed 's/^SELINUX=.*/SELINUX=permissive/' "$config" >"$state/config.permissive"
    chmod 600 "$state/config.permissive"
    write "$state/permissive-sha256" "$(sha256sum "$state/config.permissive" | cut -d ' ' -f1)"
    write "$state/selinux-phase" reboot
    cat "$state/config.permissive" >"$config"; sync -f "$config"
    fixfiles -F onboot
    [ -f /.autorelabel ] && [ ! -L /.autorelabel ] || fail relabel-not-scheduled
    systemd-run --unit="$reboot_unit" --collect --timer-property=RemainAfterElapse=no --description='WindowsToLinux approved original SELinux reboot' --on-active=5s /usr/bin/systemctl reboot
    systemctl is-active --quiet "$reboot_unit.timer" || fail reboot-timer-missing
    return
  fi
  directory "$state"
  [ ! -L "$state/operation.lock" ] || fail unsafe-lock
  exec 9>"$state/operation.lock"; flock -n 9 || fail preparation-busy
  if [ "$stage" = enforce ]; then
    [ "$selinux_phase" = READY_TO_ENFORCE ] || fail invalid-enforce-state
    [ ! -e "$state/enforce-request" ] && [ ! -L "$state/enforce-request" ] || fail original-enforce-already-sent
    selinux_post_reboot
    [ "$(systemctl show "$rollback_unit.timer" -p LoadState --value)" = not-found ] || fail prior-enforcement-timer
    write "$state/enforce-request" "$request_sha"
    systemd-run --unit="$rollback_unit" --collect --timer-property=RemainAfterElapse=no --description='WindowsToLinux original SELinux login safety rollback' --on-active=180s /usr/sbin/setenforce 0
    systemctl is-active --quiet "$rollback_unit.timer" || fail safety-timer-missing
    write "$state/selinux-phase" enforcing
    setenforce 1
    [ "$(getenforce)" = Enforcing ] || fail enforcement-not-observed
  else
    [ "$selinux_phase" = ENFORCEMENT_PENDING ] || fail invalid-commit-state
    [ ! -e "$state/commit-request" ] && [ ! -L "$state/commit-request" ] || fail original-commit-already-sent
    systemctl is-active --quiet "$rollback_unit.timer" || fail safety-timer-missing
    write "$state/commit-request" "$request_sha"
    sed 's/^SELINUX=.*/SELINUX=enforcing/' "$config" >"$state/config.enforcing"
    chmod 600 "$state/config.enforcing"
    write "$state/enforcing-sha256" "$(sha256sum "$state/config.enforcing" | cut -d ' ' -f1)"
    cat "$state/config.enforcing" >"$config"; sync -f "$config"
    if ! systemctl stop "$rollback_unit.timer" || [ "$(getenforce)" != Enforcing ]; then
      cat "$state/config.permissive" >"$config"; sync -f "$config"; fail enforcement-commit-failed
    fi
    [ "$(systemctl show "$rollback_unit.timer" -p ActiveState --value)" = inactive ] || fail rollback-timer-still-active
    write "$state/selinux-phase" complete
  fi
}
