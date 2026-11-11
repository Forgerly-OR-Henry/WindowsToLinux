# Legacy boot units can observe candidates without settling another task's state.
recover_workspaces() {
    [ "$#" -eq 0 ] || reject workspace-recovery-arguments
    [ -e "$work_root" ] || [ -L "$work_root" ] || return 0
    assert_storage_parent "$work_root"
    assert_root_owned_directory "$work_root"
    local candidate id failed=0
    for candidate in "$work_root"/*; do
        [ -e "$candidate" ] || [ -L "$candidate" ] || continue
        id="${candidate##*/}"
        if ! [[ "$id" =~ ^[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}$ ]] \
            || [ -L "$candidate" ] || [ ! -d "$candidate" ]; then
            printf 'WORKSPACE_RECOVERY_UNEXPLAINED=1\n' >&2
            failed=1
            continue
        fi
        if (assert_candidate_for_deployer "$candidate"); then
            printf 'WORKSPACE_RETAINED=%s\n' "$id"
        else
            printf 'WORKSPACE_QUARANTINED=%s\n' "$id" >&2
            failed=1
        fi
    done
    return "$failed"
}
