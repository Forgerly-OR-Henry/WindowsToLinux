# A completed artifact has an original-operation seal written only after the
# archive has been closed and verified. File existence alone proves nothing.
handoff_operation_lock() {
    [ "$#" -eq 2 ] || reject handoff-operation-lock-arguments
    local operation="$1" mode="$2" locks="$base_root/backup-operation-locks" lock
    require_backup_operation "$operation"
    case "$mode" in write | observe | close) ;; *) reject handoff-operation-lock-mode ;; esac
    lock="$locks/$operation"
    handoff_operation_fd=
    if [ "$mode" = write ] || [ "$mode" = close ]; then
        if [ -e "$base_root" ] || [ -L "$base_root" ]; then assert_root_owned_directory "$base_root"
        else assert_root_owned_directory /var/lib; install -d -o root -g root -m 755 -- "$base_root"; fi
        if [ -e "$locks" ] || [ -L "$locks" ]; then
            assert_root_owned_directory "$locks"
        else install -d -o root -g root -m 700 -- "$locks"; fi
        assert_root_owned_directory "$locks"
        if [ ! -e "$lock" ] && [ ! -L "$lock" ]; then
            (umask 077; set -o noclobber; : >"$lock") 2>/dev/null || :
        fi
    elif [ ! -e "$locks" ] && [ ! -L "$locks" ]; then
        [ ! -e "$(backup_operation_root "$operation")" ] \
            && [ ! -L "$(backup_operation_root "$operation")" ] || reject handoff-writer-evidence-missing
        return
    fi
    assert_root_owned_directory "$locks"
    if [ ! -e "$lock" ] && [ ! -L "$lock" ]; then
        [ ! -e "$(backup_operation_root "$operation")" ] \
            && [ ! -L "$(backup_operation_root "$operation")" ] || reject handoff-writer-evidence-missing
        return
    fi
    assert_root_owned_regular "$lock"
    # Keep the lock inode outside the removable operation tree. Observers open
    # an existing file only, and cannot report absence while a producer runs.
    exec {handoff_operation_fd}<"$lock"
    flock -n "$handoff_operation_fd" || reject handoff-operation-writer-active
    if [ "$mode" = write ] && { [ -e "$locks/.closed-$operation" ] || [ -L "$locks/.closed-$operation" ]; }; then
        assert_root_owned_regular "$locks/.closed-$operation"
        reject handoff-operation-closed
    fi
}

# Closing the original operation fences producers that have not acquired their
# writer lock yet. A running producer makes this command fail without a fence.
handoff_operation_close() {
    [ "$#" -eq 4 ] || reject handoff-operation-close-arguments
    local operation="$1" application="$2" task="$3" revision="$4" receipt owner
    require_backup_operation "$operation"; require_app "$application"
    require_snapshot_token "$task"; require_digest "$revision"
    owner="$(printf '%s\n' "$operation" "$application" "$task" "$revision" "$deployer")"
    handoff_operation_lock "$operation" close
    receipt="$base_root/backup-operation-locks/.closed-$operation"
    if [ -e "$receipt" ] || [ -L "$receipt" ]; then
        assert_root_owned_regular "$receipt"
        [ "$(cat -- "$receipt")" = "$owner" ] || reject handoff-operation-close-owner
    else
        (umask 077; set -o noclobber; printf '%s\n' "$owner" >"$receipt") || reject handoff-operation-close-collision
        chown root:root -- "$receipt"; chmod 400 -- "$receipt"
    fi
    handoff_operation_unlock
    printf 'HANDOFF_OPERATION_CLOSED=1\n'
}

handoff_operation_close_status() {
    [ "$#" -eq 4 ] || reject handoff-operation-close-status-arguments
    local operation="$1" application="$2" task="$3" revision="$4" receipt owner
    require_backup_operation "$operation"; require_app "$application"
    require_snapshot_token "$task"; require_digest "$revision"
    owner="$(printf '%s\n' "$operation" "$application" "$task" "$revision" "$deployer")"
    handoff_operation_lock "$operation" observe
    receipt="$base_root/backup-operation-locks/.closed-$operation"
    if [ ! -e "$receipt" ] && [ ! -L "$receipt" ]; then
        handoff_operation_unlock; printf 'HANDOFF_OPERATION_CLOSED=0\n'; return
    fi
    assert_root_owned_regular "$receipt"
    [ "$(cat -- "$receipt")" = "$owner" ] || reject handoff-operation-close-owner
    handoff_operation_unlock
    printf 'HANDOFF_OPERATION_CLOSED=1\n'
}

handoff_operation_unlock() {
    if [ -n "${handoff_operation_fd:-}" ]; then
        exec {handoff_operation_fd}<&-
        handoff_operation_fd=
    fi
}

handoff_backup_identity() {
    [ "$#" -eq 9 ] || reject handoff-backup-identity-arguments
    handoff_backup_operation="$1"
    handoff_backup_application="$2"
    handoff_backup_component="$3"
    handoff_backup_app="$4"
    handoff_backup_release="$5"
    handoff_backup_manifest="$6"
    handoff_backup_kind="$7"
    handoff_backup_resource="$8"
    handoff_backup_maximum="$9"
    require_backup_operation "$handoff_backup_operation"
    require_app "$handoff_backup_application"
    require_app "$handoff_backup_component"
    require_app "$handoff_backup_app"
    require_digest "$handoff_backup_release"
    require_digest "$handoff_backup_manifest"
    require_backup_limit "$handoff_backup_maximum"
    [[ "$handoff_backup_resource" =~ ^[a-z0-9][a-z0-9-]{0,127}$ ]] || reject backup-resource
    case "$handoff_backup_kind" in file | release) handoff_backup_extension=pax ;;
        oci) handoff_backup_extension=oci ;; *) reject backup-kind ;; esac
    handoff_backup_artifact="$(backup_artifact_identity "$handoff_backup_application" \
        "$handoff_backup_component" "$handoff_backup_app" "$handoff_backup_release" \
        "$handoff_backup_kind" "$handoff_backup_resource")"
    require_backup_artifact "$handoff_backup_artifact"
    handoff_backup_root="$(backup_operation_root "$handoff_backup_operation")"
    handoff_backup_target="$handoff_backup_root/$handoff_backup_artifact.$handoff_backup_extension"
    handoff_backup_seal_path="$handoff_backup_root/.complete-$handoff_backup_artifact"
    handoff_backup_request="$(printf '%s\n' "$handoff_backup_operation" "$handoff_backup_application" \
        "$handoff_backup_component" "$handoff_backup_app" "$handoff_backup_release" \
        "$handoff_backup_manifest" "$handoff_backup_kind" "$handoff_backup_resource" \
        "$handoff_backup_maximum" "$handoff_backup_artifact")"
}

handoff_backup_assert_root() {
    assert_root_owned_directory "$handoff_backup_root"
    assert_root_owned_regular "$handoff_backup_root/.deployer"
    [ "$(cat -- "$handoff_backup_root/.deployer")" = "$deployer" ] || reject backup-deployer
}

# Called once from backup_create_artifact, after final file checks and before
# its success receipt.
handoff_backup_seal() {
    [ "$#" -eq 10 ] || reject handoff-backup-seal-arguments
    local supplied_target="${10}" size digest temp
    handoff_backup_identity "${@:1:9}"
    [ "$supplied_target" = "$handoff_backup_target" ] || reject handoff-backup-target
    handoff_backup_assert_root
    [ ! -e "$handoff_backup_seal_path" ] && [ ! -L "$handoff_backup_seal_path" ] \
        || reject handoff-backup-already-sealed
    assert_root_owned_regular "$handoff_backup_target"
    size="$(stat -c '%s' -- "$handoff_backup_target")"
    [[ "$size" =~ ^[1-9][0-9]{0,10}$ ]] && [ "$size" -le "$handoff_backup_maximum" ] \
        || reject handoff-backup-size
    digest="$(sha256sum -- "$handoff_backup_target" | awk '{print $1}')"
    require_digest "$digest"
    temp="$(mktemp "$handoff_backup_root/.seal.XXXXXX")"
    printf '%s\n%s\n%s\n' "$handoff_backup_request" "$size" "$digest" >"$temp"
    chown root:root -- "$temp"
    chmod 400 -- "$temp"
    ln -- "$temp" "$handoff_backup_seal_path" || reject handoff-backup-seal-collision
    rm -f -- "$temp"
}

# Never creates an operation directory or supplies a missing completion marker.
handoff_backup_status() {
    [ "$#" -eq 9 ] || reject handoff-backup-status-arguments
    local size digest expected
    handoff_backup_identity "$@"
    handoff_operation_lock "$handoff_backup_operation" observe
    if [ ! -e "$handoff_backup_root" ] && [ ! -L "$handoff_backup_root" ]; then
        printf 'HANDOFF_BACKUP=1\nPRESENT=0\n'
        handoff_operation_unlock
        return
    fi
    handoff_backup_assert_root
    if [ ! -e "$handoff_backup_target" ] && [ ! -L "$handoff_backup_target" ]; then
        [ ! -e "$handoff_backup_seal_path" ] && [ ! -L "$handoff_backup_seal_path" ] \
            || reject handoff-backup-sealed-file-missing
        printf 'HANDOFF_BACKUP=1\nPRESENT=0\n'
        handoff_operation_unlock
        return
    fi
    assert_root_owned_regular "$handoff_backup_target"
    if [ ! -e "$handoff_backup_seal_path" ] && [ ! -L "$handoff_backup_seal_path" ]; then
        printf 'HANDOFF_BACKUP=1\nPRESENT=1\nCOMPLETE=0\n'
        handoff_operation_unlock
        return
    fi
    assert_root_owned_regular "$handoff_backup_seal_path"
    size="$(stat -c '%s' -- "$handoff_backup_target")"
    digest="$(sha256sum -- "$handoff_backup_target" | awk '{print $1}')"
    expected="$(printf '%s\n%s\n%s\n' "$handoff_backup_request" "$size" "$digest")"
    [ "$(cat -- "$handoff_backup_seal_path")" = "$expected" ] \
        || reject handoff-backup-completion-drift
    [ "$size" -le "$handoff_backup_maximum" ] || reject handoff-backup-size
    printf 'HANDOFF_BACKUP=1\nPRESENT=1\nCOMPLETE=1\nARTIFACT=%s\nKIND=%s\nSIZE=%s\nSHA256=%s\n' \
        "$handoff_backup_artifact" "$handoff_backup_kind" "$size" "$digest"
    handoff_operation_unlock
}

# Observes only the original backup marker; it does not create a lock directory
# or renew maintenance after a lost acknowledgement.
handoff_maintenance_status() {
    [ "$#" -eq 3 ] || reject handoff-maintenance-status-arguments
    local app="$1" manifest="$2" token="$3" root current locks marker
    require_app "$app"
    require_digest "$manifest"
    require_backup_operation "$token"
    root="$(app_root "$app")"
    assert_root_owned_directory "$root"
    [ -L "$root/current" ] || reject handoff-maintenance-current
    current="$(readlink -f -- "$root/current")" || reject handoff-maintenance-current
    case "$current" in "$root"/releases/[0-9a-f]*) ;; *) reject handoff-maintenance-current ;; esac
    assert_root_owned_directory "$current"
    assert_root_owned_regular "$current/.windowstolinux-owner"
    [ "$(cat -- "$current/.windowstolinux-owner")" = "$manifest" ] \
        || reject handoff-maintenance-owner
    locks="$base_root/application-locks/$app"
    if [ ! -e "$locks" ] && [ ! -L "$locks" ]; then
        printf 'HANDOFF_MAINTENANCE=1\nPRESENT=0\n'
        return
    fi
    assert_root_owned_directory "$locks"
    marker="$locks/maintenance"
    if [ ! -e "$marker" ] && [ ! -L "$marker" ]; then
        printf 'HANDOFF_MAINTENANCE=1\nPRESENT=0\n'
        return
    fi
    assert_root_owned_regular "$marker"
    [ "$(cat -- "$marker")" = "$token" ] || reject handoff-maintenance-foreign
    printf 'HANDOFF_MAINTENANCE=1\nPRESENT=1\n'
}
