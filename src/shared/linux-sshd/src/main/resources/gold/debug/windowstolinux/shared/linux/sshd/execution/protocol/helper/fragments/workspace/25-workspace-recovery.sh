# Boot recovery inspects only helper-owned candidate records.
recover_workspaces() {
    [ "$#" -eq 0 ] || reject workspace-recovery-arguments
    initialise_controlled_roots
    local candidate id app failed=0
    for candidate in "$work_root"/*; do
        [ -e "$candidate" ] || [ -L "$candidate" ] || continue
        id="${candidate##*/}"
        app="${id%-*}"
        if ! [[ "$id" =~ ^[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}$ ]] \
            || [ -L "$candidate" ] || [ ! -d "$candidate" ]; then
            printf 'WORKSPACE_RECOVERY_UNEXPLAINED=1\n' >&2
            failed=1
            continue
        fi
        if (
            assert_candidate_for_deployer "$candidate"
            cleanup_candidate "$app" "$id"
        ); then
            printf 'WORKSPACE_RECOVERED=%s\n' "$id"
        else
            # Retain the root-owned record and volume; never pretend UID release proves cleanup.
            printf 'WORKSPACE_QUARANTINED=%s\n' "$id" >&2
            failed=1
        fi
    done
    return "$failed"
}
