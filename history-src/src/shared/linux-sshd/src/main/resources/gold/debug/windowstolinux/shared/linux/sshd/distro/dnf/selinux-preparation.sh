selinux_fail() {
    printf 'SELINUX_PREPARATION_FAILED=%s\n' "$1" >&2
    exit 64
}

selinux_regular() {
    [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = 0 ] || selinux_fail unsafe-file
    [ "$(stat -c %s "$1")" -le 16384 ] || selinux_fail oversized-file
    [ $((8#$(stat -c %a "$1") & 022)) -eq 0 ] || selinux_fail writable-file
}

selinux_directory() {
    [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = 0 ] || selinux_fail unsafe-directory
    [ $((8#$(stat -c %a "$1") & 022)) -eq 0 ] || selinux_fail writable-directory
}

selinux_write() {
    local destination="$1" value="$2"
    [ ! -L "$destination" ] || selinux_fail unsafe-state-link
    printf '%s\n' "$value" >"$destination.next"
    chmod 600 "$destination.next"
    mv -f -- "$destination.next" "$destination"
}

selinux_observe() {
    selinux_directory /etc
    selinux_directory /etc/selinux
    selinux_regular "$config"
    boot_id="$(cat /proc/sys/kernel/random/boot_id)"
    config_sha="$(sha256sum "$config" | cut -d ' ' -f1)"
    case "$(getenforce)" in
        Disabled) security=DISABLED ;;
        Permissive) security=PERMISSIVE ;;
        Enforcing) security=ENFORCING ;;
        *) selinux_fail unknown-security-state ;;
    esac
    checkpoint=UNPREPARED
    if [ -e /var/lib/windowstolinux ] || [ -L /var/lib/windowstolinux ]; then
        selinux_directory /var/lib/windowstolinux
    fi
    if [ -e /var/lib/windowstolinux/system-preparation ] || [ -L /var/lib/windowstolinux/system-preparation ]; then
        selinux_directory /var/lib/windowstolinux/system-preparation
    fi
    if [ -e "$state" ] || [ -L "$state" ]; then
        selinux_directory "$state"
        for item in config.before boot-id phase permissive-sha256; do selinux_regular "$state/$item"; done
        original_boot="$(cat "$state/boot-id")"
        case "$(cat "$state/phase")" in
            reboot)
                [ "$config_sha" = "$(cat "$state/permissive-sha256")" ] || selinux_fail configuration-changed
                if [ "$boot_id" = "$original_boot" ]; then
                    checkpoint=REBOOT_PENDING
                else
                    [ "$security" = PERMISSIVE ] || selinux_fail unexpected-post-reboot-security
                    if [ -e /.autorelabel ]; then checkpoint=REBOOT_PENDING; else checkpoint=READY_TO_ENFORCE; fi
                fi
                ;;
            enforcing)
                [ "$boot_id" != "$original_boot" ] || selinux_fail reboot-not-observed
                [ "$config_sha" = "$(cat "$state/permissive-sha256")" ] || selinux_fail configuration-changed
                if [ "$security" = ENFORCING ]; then
                    checkpoint=ENFORCEMENT_PENDING
                elif [ "$security" = PERMISSIVE ]; then
                    checkpoint=READY_TO_ENFORCE
                else selinux_fail unexpected-enforcement-security; fi
                ;;
            complete)
                [ "$security" = ENFORCING ] && grep -qx 'SELINUX=enforcing' "$config" || selinux_fail completed-security-changed
                checkpoint=COMPLETE
                ;;
            *) selinux_fail unknown-preparation-checkpoint ;;
        esac
    elif [ "$security" = ENFORCING ]; then
        checkpoint=COMPLETE
    fi
}

selinux_post_reboot() {
    [ ! -e /.autorelabel ] && [ ! -L /.autorelabel ] || selinux_fail relabel-incomplete
    [ "$(systemctl show selinux-autorelabel.service -p Result --value)" = success ] || selinux_fail relabel-service-failed
    local avc_status=0
    ausearch --input-logs -m AVC,USER_AVC -ts boot >"$state/avc-check.log" 2>&1 || avc_status=$?
    [ "$avc_status" -eq 1 ] && grep -q '<no matches>' "$state/avc-check.log" || selinux_fail unresolved-avc-or-audit-error
}

selinux_preparation() {
    set -euo pipefail
    export LC_ALL=C
    export PATH=/usr/sbin:/usr/bin:/sbin:/bin
    umask 077
    local operation="${1:?}" config=/etc/selinux/config
    local state=/var/lib/windowstolinux/system-preparation/selinux
    local boot_id config_sha security checkpoint original_boot
    [ "$(id -u)" -eq 0 ] || selinux_fail root-management-required
    . /etc/os-release
    if [ "${ID:-}" != centos ] || { [ "${VERSION_ID:-}" != 9 ] && [ "${VERSION_ID:-}" != 10 ]; }; then
        if [ "$operation" = inspect ]; then
            printf 'APPLICABLE=false\n'
            return
        fi
        selinux_fail unsupported-distribution
    fi
    [ "$(uname -m)" = x86_64 ] || selinux_fail unsupported-architecture
    selinux_observe
    if [ "$operation" = inspect ]; then
        printf 'APPLICABLE=true\nBOOT_ID=%s\nCONFIG_SHA256=%s\nSECURITY_STATE=%s\nPREPARATION_STATE=%s\n' "$boot_id" "$config_sha" "$security" "$checkpoint"
        return
    fi
    [ "$boot_id" = "${2:?}" ] && [ "$config_sha" = "${3:?}" ] && [ "$checkpoint" = "${4:?}" ] && [ "$security" = "${5:?}" ] || selinux_fail approved-facts-changed
    case "$operation" in prepare | enforce | commit) ;; *) selinux_fail invalid-operation ;; esac
    for command in flock fixfiles setenforce systemd-run systemctl ausearch; do
        command -v "$command" >/dev/null || selinux_fail missing-system-utility
    done
    rpm -q selinux-policy-targeted policycoreutils libselinux-utils >/dev/null || selinux_fail missing-selinux-package
    if [ "$operation" = prepare ]; then
        case "$checkpoint" in UNPREPARED | REBOOT_PENDING) ;; *) selinux_fail invalid-prepare-checkpoint ;; esac
        if [ "$checkpoint" = UNPREPARED ]; then
            [ "$(grep -c '^SELINUX=' "$config")" -eq 1 ] && grep -Eq '^SELINUX=(disabled|permissive)$' "$config" || selinux_fail unsupported-selinux-configuration
            grep -qx 'SELINUXTYPE=targeted' "$config" || selinux_fail unsupported-selinux-policy
            ! grep -Eq '(^|[[:space:]])selinux=0([[:space:]]|$)' /proc/cmdline || selinux_fail selinux-disabled-by-kernel
            [ "$(systemctl show windowstolinux-selinux-reboot.timer -p LoadState --value)" = not-found ] || selinux_fail reboot-unit-collision
            [ "$(systemctl show windowstolinux-selinux-rollback.timer -p LoadState --value)" = not-found ] || selinux_fail rollback-unit-collision
            selinux_directory /var
            selinux_directory /var/lib
            [ -d /var/lib/windowstolinux ] || install -d -m 755 /var/lib/windowstolinux
            [ -d /var/lib/windowstolinux/system-preparation ] || install -d -m 700 /var/lib/windowstolinux/system-preparation
            mkdir -m 700 "$state"
            cp -p -- "$config" "$state/config.before"
            chmod 600 "$state/config.before"
            selinux_write "$state/boot-id" "$boot_id"
            sed 's/^SELINUX=.*/SELINUX=permissive/' "$config" >"$state/config.permissive"
            selinux_write "$state/permissive-sha256" "$(sha256sum "$state/config.permissive" | cut -d ' ' -f1)"
            cat "$state/config.permissive" >"$config"
            selinux_write "$state/phase" reboot
        fi
        exec 9>"$state/operation.lock"
        flock -n 9 || selinux_fail preparation-busy
        fixfiles -F onboot
        [ -f /.autorelabel ] && [ ! -L /.autorelabel ] || selinux_fail relabel-not-scheduled
        if ! systemctl is-active --quiet windowstolinux-selinux-reboot.timer; then
            systemd-run --unit=windowstolinux-selinux-reboot --collect --timer-property=RemainAfterElapse=no --description='WindowsToLinux approved SELinux reboot' --on-active=5s /usr/bin/systemctl reboot
        fi
        printf 'SELINUX_REBOOT_SCHEDULED=%s\n' "$boot_id"
        return
    fi
    selinux_directory "$state"
    exec 9>"$state/operation.lock"
    flock -n 9 || selinux_fail preparation-busy
    if [ "$operation" = enforce ]; then
        [ "$checkpoint" = READY_TO_ENFORCE ] || selinux_fail invalid-enforce-checkpoint
        selinux_post_reboot
        if systemctl is-active --quiet windowstolinux-selinux-rollback.timer; then
            selinux_fail prior-enforcement-check-pending
        fi
        systemd-run --unit=windowstolinux-selinux-rollback --collect --timer-property=RemainAfterElapse=no --description='WindowsToLinux SELinux login safety rollback' --on-active=180s /usr/sbin/setenforce 0
        selinux_write "$state/phase" enforcing
        setenforce 1
        [ "$(getenforce)" = Enforcing ] || selinux_fail enforcement-not-observed
        printf 'SELINUX_ENFORCEMENT_PENDING=1\n'
    else
        [ "$checkpoint" = ENFORCEMENT_PENDING ] || selinux_fail invalid-commit-checkpoint
        systemctl is-active --quiet windowstolinux-selinux-rollback.timer || selinux_fail rollback-timer-missing
        sed 's/^SELINUX=.*/SELINUX=enforcing/' "$config" >"$state/config.enforcing"
        cat "$state/config.enforcing" >"$config"
        if ! systemctl stop windowstolinux-selinux-rollback.timer || [ "$(getenforce)" != Enforcing ]; then
            cat "$state/config.permissive" >"$config"
            selinux_fail enforcement-commit-failed
        fi
        [ "$(systemctl show windowstolinux-selinux-rollback.timer -p ActiveState --value)" = inactive ] || selinux_fail rollback-timer-still-active
        selinux_write "$state/phase" complete
        printf 'SELINUX_PREPARATION_COMPLETE=1\n'
    fi
}
