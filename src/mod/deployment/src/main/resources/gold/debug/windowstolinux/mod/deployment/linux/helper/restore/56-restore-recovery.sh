restore_quiesce_recovery() {
    [ "$#" -eq 3 ] || reject restore-recovery-quiesce-arguments
    local candidate="$1" token="$2" component="$3" root kind app owner
    restore_stop_candidate "$candidate" "$token" "$component" >/dev/null || reject restore-candidate-stop-failed
    root="$(restore_component_root "$candidate" "$component")"
    [ -e "$root" ] || {
        printf 'QUIESCED=1\n'
        return
    }
    kind="$(cat -- "$root/kind")"
    mapfile -t identity <"$root/identity"
    app="${identity[0]}"
    owner="${identity[1]}"
    if [ -f "$root/previous" ]; then
        if [ "$kind" = deployment ]; then
            stop_application_unit "$app"
        else
            current_application="$app"
            load_container_parameters "$root/release/.windowstolinux-container-parameters"
            if [ -L "$(app_root "$app")/current" ]; then
                container_current_release "$app" "$owner"
                stop_container_runtime "$app"
            fi
        fi
    fi
    printf 'QUIESCED=1\n'
}
restore_restore_managed_data() {
    local root="$1" app="$2" kind="$3" rollback spec target status journal token
    rollback="$(restore_data_snapshot_root "$root" "$app")"
    [ -d "$rollback" ] || return 0
    token="$(cat -- "$root/activation-token")"
    require_restore_token "$token"
    current_application="$app"
    if [ "$kind" = deployment ]; then
        load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"
    else load_container_parameters "$root/release/.windowstolinux-container-parameters"; fi
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        [ "$storage_kind" = FILE ] || continue
        [ -e "$rollback/$binding.present" ] || continue
        assert_root_owned_regular "$rollback/$binding.present"
        status="$(cat -- "$rollback/$binding.present")"
        target="$(managed_storage_target)"
        assert_storage_parent "$target"
        journal="${target%/*}/.windowstolinux-restore-$token-$binding"
        assert_root_owned_directory "$journal"
        if [ "$status" = 1 ] && [ -d "$journal/previous" ]; then
            (assert_storage_tree "$journal/previous" "$target")
            if [ -e "$target" ] || [ -L "$target" ]; then
                (assert_storage_tree "$target")
                mv -T -- "$target" "$journal/failed"
            fi
            mv -T -- "$journal/previous" "$target"
        elif [ "$status" = 1 ]; then
            # If the old directory was not moved, next must still be present; otherwise the state is ambiguous.
            [ -d "$journal/next" ] && [ -d "$target" ] || reject restore-data-recovery-unprovable
            (assert_storage_tree "$target")
        elif [ "$status" = 0 ]; then
            if [ -e "$target" ] || [ -L "$target" ]; then
                (assert_storage_tree "$target")
                mv -T -- "$target" "$journal/failed"
            fi
        else reject restore-data-recovery-state; fi
        sync -f "${target%/*}"
    done
}
restore_recover_component() {
    [ "$#" -eq 3 ] || reject restore-recover-arguments
    local candidate="$1" token="$2" component="$3" root kind app owner release previous snapshot_token
    root="$(restore_component_root "$candidate" "$component")"
    [ -e "$root" ] || {
        printf 'RECOVERED=1\nPREVIOUS=0\n'
        return
    }
    kind="$(cat -- "$root/kind")"
    mapfile -t identity <"$root/identity"
    app="${identity[0]}"
    owner="${identity[1]}"
    release="${identity[2]}"
    restore_stop_candidate "$candidate" "$token" "$component" >/dev/null || reject restore-candidate-stop-failed
    if [ -f "$root/previous" ]; then
        mapfile -t saved <"$root/previous"
        previous="${saved[0]}"
        snapshot_token="${saved[1]}"
        restore_restore_managed_data "$root" "$app" "$kind"
        if [ "$kind" = deployment ]; then
            if [ "$previous" = 1 ]; then rollback_deployment "$app" "$release" "$owner" "$snapshot_token"; else rollback_deployment_first "$app" "$release" "$owner"; fi
        else if [ "$previous" = 1 ]; then rollback_container "$app" "$release" "$owner" "$snapshot_token"; else rollback_container_first "$app" "$release" "$owner"; fi; fi
    fi
    printf 'RECOVERED=1\nPREVIOUS=%s\nAPP=%s\n' "${previous:-0}" "$app"
}
