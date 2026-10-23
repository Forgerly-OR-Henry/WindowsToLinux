# Original activation recovery and verified retained-material cleanup.
handoff_restore_snapshot() {
    restore_snapshot_current "$@"
}

handoff_restore_quiesce_recovery() {
    local root="$(restore_component_root "$1" "$3")" app owner release kind
    assert_root_owned_regular "$root/identity"; assert_root_owned_regular "$root/kind"
    mapfile -t identity <"$root/identity"; app="${identity[0]}"; owner="${identity[1]}"; release="${identity[2]}"
    kind="$(cat -- "$root/kind")"
    application_maintenance "$app"
    [ "$(managed_current_status "$app" "$owner" "$kind")" = "$(printf 'MANAGED_CURRENT=1\nKIND=%s\nRELEASE_SHA256=%s' "$kind" "$release")" ] || reject handoff-restore-recovery-current
    restore_quiesce_recovery "$@"
}

handoff_restore_finish_component() {
    local candidate="$1" token="$2" component="$3" root app owner release kind snapshot spec target journal
    root="$(restore_component_root "$candidate" "$component")"
    assert_root_owned_regular "$root/identity"; assert_root_owned_regular "$root/previous"
    mapfile -t identity <"$root/identity"; app="${identity[0]}"; owner="${identity[1]}"; release="${identity[2]}"
    kind="$(cat -- "$root/kind")"
    [ "$(managed_current_status "$app" "$owner" "$kind")" = "$(printf 'MANAGED_CURRENT=1\nKIND=%s\nRELEASE_SHA256=%s' "$kind" "$release")" ] || reject handoff-restore-finish-current
    if [ "$kind" = deployment ]; then
        current_application="$app"; load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"; parse_deployment_inputs "${deployment_runtime_parameters[@]}"
    else current_application="$app"; load_container_parameters "$root/release/.windowstolinux-container-parameters"; fi
    restore_stop_candidate "$candidate" "$token" "$component" >/dev/null
    mapfile -t saved <"$root/previous"
    if [ "${saved[0]}" = 1 ]; then
        snapshot="$(snapshot_root "$app" "${saved[1]}")"
        assert_root_owned_directory "$snapshot"
        assert_root_owned_regular "$snapshot/.deployment-task"; assert_root_owned_regular "$snapshot/.deployment-old-owner"
        [ "$(cat -- "$snapshot/.deployment-task")" = "$handoff_restore_task" ] && [ "$(cat -- "$snapshot/.deployment-old-owner")" = "$owner" ] || reject handoff-restore-finish-snapshot-owner
    fi
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        [ "$storage_kind" = FILE ] || continue
        target="$(managed_storage_target)"; journal="${target%/*}/.windowstolinux-restore-$token-$binding"
        if [ -e "$journal" ] || [ -L "$journal" ]; then assert_root_owned_directory "$journal"; rm -rf --one-file-system -- "$journal"; fi
    done
    local migration="$(app_root "$app")/.migration-snapshot"
    if [ -e "$migration" ] || [ -L "$migration" ]; then
        assert_root_owned_regular "$migration"
        [ "$(cat -- "$migration")" = "$handoff_restore_task" ] || reject handoff-restore-finish-migration-owner
        rm -f -- "$migration"
    fi
    if [ "${saved[0]}" = 1 ]; then rm -rf --one-file-system -- "$snapshot"; fi
    (application_maintenance_control end "$app" "$owner" "restore-$token" "$release") >/dev/null
    printf 'RESTORE_FINISHED=1\n'
}

handoff_restore_cleanup() {
    handoff_restore_identity "$@"; handoff_restore_lock observe
    local receipt="$base_root/restore-operation-locks/.finished-$handoff_restore_candidate"
    if [ -e "$receipt" ] || [ -L "$receipt" ]; then
        assert_root_owned_regular "$receipt"; [ "$(cat -- "$receipt")" = "$handoff_restore_owner" ] || reject handoff-restore-cleanup-owner
        [ ! -e "$handoff_restore_root" ] && [ ! -L "$handoff_restore_root" ] || reject handoff-restore-cleanup-drift
        printf 'RESTORE_CLEANUP=1\n'; return
    fi
    handoff_restore_assert
    if [ -e "$handoff_restore_root/.done-finish" ]; then
        assert_root_owned_regular "$handoff_restore_root/.done-finish"
        [ "$(cat -- "$handoff_restore_root/.done-finish")" = 'RESTORE_FINISHED=1' ] || reject handoff-restore-finish-missing
    else
        assert_root_owned_regular "$handoff_restore_root/.done-abort"
        [ "$(cat -- "$handoff_restore_root/.done-abort")" = 'RESTORE_ABORTED=1' ] || reject handoff-restore-abort-missing
    fi
    rm -rf --one-file-system -- "$handoff_restore_root"
    printf '%s\n' "$handoff_restore_owner" >"$receipt"; chown root:root -- "$receipt"; chmod 400 -- "$receipt"
    printf 'RESTORE_CLEANUP=1\n'
}

handoff_restore_abort_component() {
    local candidate="$1" token="$2" component="$3" root app owner kind migration spec target journal marker
    root="$(restore_component_root "$candidate" "$component")"
    assert_root_owned_regular "$root/identity"; assert_root_owned_regular "$root/previous"
    mapfile -t identity <"$root/identity"; app="${identity[0]}"; owner="${identity[1]}"
    kind="$(cat -- "$root/kind")"
    assert_root_owned_regular "$handoff_restore_root/.done-recover"
    mapfile -t saved <"$root/previous"
    application_maintenance "$app"
    if [ "$kind" = deployment ]; then assert_deployment_current_or_empty "$app" "$owner"
    else current_application="$app"; container_current_release "$app" "$owner"; fi
    if [ "${saved[0]}" = 0 ]; then
        [ "$previous_present" = 0 ] || reject handoff-restore-abort-current
        [ "$(cat -- "$handoff_restore_root/.done-recover")" = "$(printf 'ROLLED_BACK=1\nRECOVERED=1\nPREVIOUS=0\nAPP=%s' "$app")" ] || reject handoff-restore-abort-recovery
    else
        [ "$previous_present" = 1 ] || reject handoff-restore-abort-current
        [ "$previous_path" = "$(app_root "$app")/releases/${saved[2]}" ] \
            && [ "$previous_running" = "${saved[3]}" ] || reject handoff-restore-abort-old-state
        # The original recover receipt was sealed only after the original snapshot rollback.
        [ "$(cat -- "$handoff_restore_root/.done-recover")" = "$(printf 'ROLLED_BACK=1\nRECOVERED=1\nPREVIOUS=1\nAPP=%s' "$app")" ] || reject handoff-restore-abort-recovery
    fi
    if [ "$kind" = deployment ]; then
        current_application="$app"; load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"; parse_deployment_inputs "${deployment_runtime_parameters[@]}"
    else current_application="$app"; load_container_parameters "$root/release/.windowstolinux-container-parameters"; fi
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"; [ "$storage_kind" = FILE ] || continue
        target="$(managed_storage_target)"; journal="${target%/*}/.windowstolinux-restore-$token-$binding"
        if [ -e "$journal" ] || [ -L "$journal" ]; then assert_root_owned_directory "$journal"; rm -rf --one-file-system -- "$journal"; fi
    done
    migration="$(app_root "$app")/.migration-snapshot"
    if [ -e "$migration" ] || [ -L "$migration" ]; then
        assert_root_owned_regular "$migration"; [ "$(cat -- "$migration")" = "$handoff_restore_task" ] || reject handoff-restore-abort-migration-owner
        rm -f -- "$migration"
    fi
    marker="$(application_lock_directory "$app")/maintenance"
    if [ -e "$marker" ] || [ -L "$marker" ]; then
        assert_root_owned_regular "$marker"; [ "$(cat -- "$marker")" = "restore-$token" ] || reject handoff-restore-abort-marker-owner
        rm -f -- "$marker"
    fi
    printf 'RESTORE_ABORTED=1\n'
}

handoff_restore_cleanup_status() {
    handoff_restore_identity "$@"; handoff_restore_lock observe
    local receipt="$base_root/restore-operation-locks/.finished-$handoff_restore_candidate"
    if [ -e "$receipt" ] || [ -L "$receipt" ]; then
        assert_root_owned_regular "$receipt"; [ "$(cat -- "$receipt")" = "$handoff_restore_owner" ] || reject handoff-restore-cleanup-owner
        [ ! -e "$handoff_restore_root" ] && [ ! -L "$handoff_restore_root" ] || reject handoff-restore-cleanup-drift
        printf 'RESTORE_CLEANUP=1\n'; return
    fi
    handoff_restore_assert
    printf 'RESTORE_CLEANUP=0\n'
}
