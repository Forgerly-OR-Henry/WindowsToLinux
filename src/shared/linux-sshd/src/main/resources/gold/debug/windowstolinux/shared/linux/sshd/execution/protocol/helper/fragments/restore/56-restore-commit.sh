restore_snapshot_current() {
    [ "$#" -eq 3 ] || reject restore-snapshot-arguments
    local candidate="$1" token="$2" component="$3" root kind output app owner release previous snapshot_token
    root="$(restore_component_root "$candidate" "$component")"
    kind="$(cat -- "$root/kind")"
    mapfile -t identity <"$root/identity"
    app="${identity[0]}"
    owner="${identity[1]}"
    release="${identity[2]}"
    (application_maintenance_control begin "$app" "$owner" "restore-$token") >/dev/null
    if [ -e "$root/previous" ]; then
        assert_root_owned_regular "$root/previous"
        printf 'SNAPSHOT=1\nPREVIOUS=%s\n' "$(head -n1 -- "$root/previous")"
        return
    fi
    if [ "$kind" = deployment ]; then
        output="$(snapshot_deployment "$app" "$owner")"
    else
        current_application="$app"
        load_container_parameters "$root/release/.windowstolinux-container-parameters"
        output="$(snapshot_container "$app" "$owner")"
    fi
    previous="$(printf '%s\n' "$output" | awk -F= '$1=="PREVIOUS"{print $2}')"
    [ "$previous" = 0 ] || [ "$previous" = 1 ] || reject restore-snapshot-evidence
    snapshot_token="$(printf '%s\n' "$output" | awk -F= '$1=="SNAPSHOT_TOKEN"{print $2}')"
    if [ "$previous" = 1 ]; then require_snapshot_token "$snapshot_token"; else snapshot_token=-; fi
    printf '%s\n%s\n' "$previous" "$snapshot_token" >"$root/previous"
    chown root:root -- "$root/previous"
    chmod 400 -- "$root/previous"
    if [ "$previous" = 1 ]; then
        if [ "$kind" = deployment ]; then
            stop_application_unit "$app"
        else
            current_application="$app"
            container_current_release "$app" "$owner"
            stop_container_runtime "$app"
        fi
    fi
    printf 'SNAPSHOT=1\nPREVIOUS=%s\n' "$previous"
}
restore_data_snapshot_root() {
    local root="$1" app="$2"
    mapfile -t previous <"$root/previous"
    if [ "${previous[0]}" = 1 ]; then
        printf '%s/restore-data' "$(snapshot_root "$app" "${previous[1]}")"
    else printf '%s/new-data' "$root/rollback"; fi
}
restore_mark_quiesced() {
    [ "$#" -ge 4 ] || reject restore-quiesced-arguments
    local candidate="$1" token="$2" count="$3" component root kind app previous marker
    shift 3
    require_restore_token "$token"
    require_count "$count"
    [ "$#" -eq "$count" ] || reject restore-quiesced-count
    for component in "$@"; do
        require_app "$component"
        root="$(restore_component_root "$candidate" "$component")"
        assert_root_owned_regular "$root/previous"
        kind="$(cat -- "$root/kind")"
        mapfile -t identity <"$root/identity"
        app="${identity[0]}"
        previous="$(head -n 1 -- "$root/previous")"
        [ "$previous" = 0 ] || [ "$previous" = 1 ] || reject restore-previous
        if [ "$previous" = 1 ]; then
            if [ "$kind" = deployment ]; then
                ! systemctl is-active --quiet "$(unit_name "$app")" || reject restore-writes-active
            else
                current_application="$app"
                load_container_parameters "$root/release/.windowstolinux-container-parameters"
                ! "$container_engine" inspect --format '{{.State.Running}}' "$(container_name "$app")" 2>/dev/null | grep -qx true || reject restore-writes-active
            fi
        fi
    done
    marker="$(restore_activation_root "$candidate")/.application-quiesced"
    printf '%s\n' "$token" >"$marker"
    chown root:root -- "$marker"
    chmod 400 -- "$marker"
    printf 'QUIESCED=1\n'
}
restore_remove_candidate_database_links() {
    local root="$1" app="$2" spec link target
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        [ "$storage_kind" = DATABASE ] || continue
        link="$(restore_binding_link "$root" "$app")"
        target="$(managed_storage_target)"
        if [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ]; then
            local container_candidate_files="$root/data" container_storage_view_root="$root/container-view"
            link="$(container_database_link "$(container_image "$app" "$release")")"
            target="/run/windowstolinux/databases/$binding/$database_file"
        fi
        if [ -L "$link" ]; then
            [ "$(readlink -- "$link")" = "$target" ] || reject restore-data-link-changed
            rm -f -- "$link"
        fi
    done
}
restore_install_managed_files() {
    local root="$1" app="$2" spec target directory source rollback status journal token owner mode
    current_application="$app"
    token="$(cat -- "$root/activation-token")"
    require_restore_token "$token"
    prepare_service_identity "$app"
    owner="$(service_identity_name "$app")"
    if [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ]; then owner="$(container_nonroot_user "$container_engine" "$(container_image "$app" "$release")")"; fi
    restore_remove_candidate_database_links "$root" "$app"
    rollback="$(restore_data_snapshot_root "$root" "$app")"
    install -d -o root -g root -m 700 -- "$rollback"
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        [ "$storage_kind" != DATABASE ] || continue
        target="$(managed_storage_target)"
        directory="$(managed_data_binding_root "$binding")"
        assert_storage_parent "$directory"
        record_storage_binding "$directory"
        if [ "$storage_kind" = CONFIGURATION ]; then
            source="$root/data/$binding/value"
            assert_root_owned_regular "$source"
            [ "$(sha256sum -- "$source" | awk '{print $1}')" = "$configuration_sha256" ] || reject restore-configuration-digest
            if [ -e "$target" ] || [ -L "$target" ]; then
                assert_root_owned_regular "$target"
                [ "$(sha256sum -- "$target" | awk '{print $1}')" = "$configuration_sha256" ] || reject restore-configuration-conflict
            else
                install -d -o root -g root -m 755 -- "${target%/*}"
                install -o root -g root -m 444 -- "$source" "$target"
            fi
            continue
        fi
        source="$root/data/$binding"
        (assert_storage_tree "$source")
        install -d -o root -g root -m 755 -- "${target%/*}"
        journal="${target%/*}/.windowstolinux-restore-$token-$binding"
        [ ! -e "$journal" ] && [ ! -L "$journal" ] || reject restore-data-journal-exists
        install -d -o root -g root -m 700 -- "$journal"
        cp -a --no-preserve=ownership -- "$source" "$journal/next"
        if [ "$mode" = ro ]; then
            chown -hR root:"${owner#*:}" -- "$journal/next"
            chmod -R u=rX,g=rX,o= -- "$journal/next"
        else
            chown -hR "${owner%%:*}:${owner#*:}" -- "$journal/next"
            chmod -R u+rwX,go-rwx -- "$journal/next"
        fi
        status="$rollback/$binding.present"
        if [ -e "$target" ] || [ -L "$target" ]; then
            (assert_storage_tree "$target")
            printf '1\n' >"$status"
        else printf '0\n' >"$status"; fi
        chmod 400 -- "$status"
        sync -f "$status"
        sync -f "$journal/next"
        if [ -e "$target" ]; then mv -T -- "$target" "$journal/previous"; fi
        sync -f "$journal"
        mv -T -- "$journal/next" "$target"
        sync -f "${target%/*}"
    done
}
restore_install_release_tree() {
    local root="$1" app="$2" owner="$3" release="$4" target app_root_path releases
    app_root_path="$(app_root "$app")"
    releases="$app_root_path/releases"
    target="$releases/$release"
    install -d -o root -g root -m 755 -- "$app_root_path" "$releases"
    if [ -e "$target" ] || [ -L "$target" ]; then
        assert_root_owned_directory "$target"
        assert_root_owned_regular "$target/.windowstolinux-owner"
        [ "$(cat -- "$target/.windowstolinux-owner")" = "$owner" ] || reject restore-release-collision
    else copy_sealed_source "$root/release" "$target"; fi
    ln -sfnT -- "$target" "$app_root_path/current"
}
restore_start_formal() {
    [ "$#" -eq 3 ] || reject restore-formal-arguments
    local candidate="$1" token="$2" component="$3" root kind app owner release oci unit tmp image
    root="$(restore_component_root "$candidate" "$component")"
    kind="$(cat -- "$root/kind")"
    mapfile -t identity <"$root/identity"
    assert_root_owned_regular "$(restore_activation_root "$candidate")/.application-quiesced"
    [ "$(cat -- "$(restore_activation_root "$candidate")/.application-quiesced")" = "$token" ] || reject restore-quiesced-token
    app="${identity[0]}"
    owner="${identity[1]}"
    release="${identity[2]}"
    oci="${identity[3]}"
    [ -f "$root/previous" ] || reject restore-snapshot-missing
    if [ "$kind" = deployment ]; then
        current_application="$app"
        load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"
        restore_install_managed_files "$root" "$app"
        restore_install_release_tree "$root" "$app" "$owner" "$release"
        prepare_managed_data_bindings "$(app_root "$app")/releases/$release/source"
        unit="$(unit_path "$app")"
        tmp="$(mktemp /etc/systemd/system/.windowstolinux-restore.XXXXXX)"
        render_deployment_unit "$app" "${deployment_runtime_parameters[@]}" >"$tmp"
        install -o root -g root -m 644 -- "$tmp" "$unit"
        rm -f -- "$tmp"
        systemctl daemon-reload
        if [ "$application_mode" = DAEMON ]; then systemctl start "$(unit_name "$app")"; else systemctl disable "$(unit_name "$app")"; fi
    else
        current_application="$app"
        load_container_parameters "$root/release/.windowstolinux-container-parameters"
        [ "$oci" != - ] || reject restore-image-required
        import_restore_image "$container_engine" "$(candidate_root "$candidate")/mutable/restore/$oci" "$(container_image "$app" "$release")"
        image="$(container_image "$app" "$release")"
        "$container_engine" image inspect "$image" >/dev/null
        restore_install_managed_files "$root" "$app"
        "$container_engine" image inspect --format '{{.Id}}' "$image" >"$root/release/.windowstolinux-container-image-id"
        chown root:root -- "$root/release/.windowstolinux-container-image-id"
        chmod 444 -- "$root/release/.windowstolinux-container-image-id"
        restore_install_release_tree "$root" "$app" "$owner" "$release"
        start_container_release "$app" "$release" "$owner"
    fi
    printf 'FORMAL_STARTED=1\nAPP=%s\n' "$app"
}
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
cleanup_restore_candidates() {
    local candidate="$1" root component token
    [ -d "$candidate/mutable/restore-activation" ] || return 0
    for root in "$candidate/mutable/restore-activation"/*; do
        [ -d "$root" ] && [ ! -L "$root" ] || continue
        component="${root##*/}"
        require_app "$component"
        if [ -e "$root/kind" ] && [ -e "$root/activation-token" ]; then
            assert_root_owned_regular "$root/activation-token"
            token="$(cat -- "$root/activation-token")"
            restore_stop_candidate "${candidate##*/}" "$token" "$component"
        fi
    done
}
