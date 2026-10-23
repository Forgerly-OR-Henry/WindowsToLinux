# Retained-release operations always use the snapshot of the original task.
# They never remove either the former or selected release tree.
managed_current_status() {
    [ "$#" -eq 3 ] || reject managed-current-status-arguments
    local app="$1" owner="$2" kind="$3" current
    require_app "$app"
    require_digest "$owner"
    current_application="$app"
    case "$kind" in
        deployment) assert_deployment_current_or_empty "$app" "$owner" ;;
        container) container_current_release "$app" "$owner" ;;
        *) reject managed-current-status-kind ;;
    esac
    [ "$previous_present" -eq 1 ] || reject managed-current-status-absent
    current="${previous_path##*/}"
    require_digest "$current"
    printf 'MANAGED_CURRENT=1\nKIND=%s\nRELEASE_SHA256=%s\n' "$kind" "$current"
}

managed_target_status() {
    [ "$#" -eq 3 ] || reject managed-target-status-arguments
    local app="$1" owner="$2" kind="$3" root current=-
    require_app "$app"; require_digest "$owner"
    current_application="$app"; root="$(app_root "$app")"
    if [ -e "$root" ] || [ -L "$root" ]; then assert_root_owned_directory "$root"; fi
    case "$kind" in
        deployment) assert_deployment_current_or_empty "$app" "$owner" ;;
        container)
            container_current_release "$app" "$owner"
            if [ "$previous_present" -eq 0 ]; then
                local engine
                for engine in docker podman; do
                    if command -v "$engine" >/dev/null 2>&1; then
                        ! "$engine" inspect "$(container_name "$app")" >/dev/null 2>&1 || reject managed-target-foreign-container
                    fi
                done
            fi ;;
        *) reject managed-target-status-kind ;;
    esac
    if [ "$previous_present" -eq 1 ]; then current="${previous_path##*/}"; require_digest "$current"; fi
    printf 'MANAGED_TARGET=1\nPRESENT=%s\nKIND=%s\nRELEASE_SHA256=%s\n' "$previous_present" "$kind" "$current"
}

snapshot_retained() {
    [ "$#" -eq 5 ] || reject snapshot-retained-arguments
    local app="$1" manifest="$2" task="$3" expected="$4" kind="$5"
    require_app "$app"; require_digest "$manifest"; require_snapshot_token "$task"; require_digest "$expected"
    application_maintenance "$app"
    case "$kind" in
        deployment) assert_deployment_current_or_empty "$app" "$manifest" ;;
        container) container_current_release "$app" "$manifest" ;;
        *) reject snapshot-retained-kind ;;
    esac
    [ "$previous_present" -eq 1 ] && [ "${previous_path##*/}" = "$expected" ] || reject snapshot-retained-current
    if [ "$kind" = container ]; then snapshot_container "$app" "$manifest" "$task"
    else snapshot_deployment "$app" "$manifest" "$task"; fi
}

retained_identity() {
    [ "$#" -eq 6 ] || reject retained-arguments
    retained_app="$1"
    retained_source="$2"
    retained_target="$3"
    retained_manifest="$4"
    retained_task="$5"
    retained_token="$6"
    require_app "$retained_app"
    require_digest "$retained_source"
    require_digest "$retained_target"
    require_digest "$retained_manifest"
    require_snapshot_token "$retained_task"
    require_snapshot_token "$retained_token"
    [ "$retained_task" = "$retained_token" ] || reject retained-task-token
    [ "$retained_source" != "$retained_target" ] || reject retained-same-release
    retained_root="$(app_root "$retained_app")"
    retained_releases="$retained_root/releases"
    retained_old="$retained_releases/$retained_source"
    retained_new="$retained_releases/$retained_target"
    retained_snapshot="$(snapshot_root "$retained_app" "$retained_token")"
    retained_marker="$(application_lock_directory "$retained_app")/maintenance"
    assert_root_owned_directory "$retained_root"
    assert_root_owned_directory "$retained_releases"
    for retained_path in "$retained_old" "$retained_new"; do
        assert_root_owned_directory "$retained_path"
        assert_root_owned_regular "$retained_path/.windowstolinux-owner"
        [ "$(cat -- "$retained_path/.windowstolinux-owner")" = "$retained_manifest" ] || reject retained-owner
    done
    assert_root_owned_directory "$retained_snapshot"
    for retained_file in .deployment-task .deployment-old-owner current-path runtime; do
        assert_root_owned_regular "$retained_snapshot/$retained_file"
    done
    [ "$(cat -- "$retained_snapshot/.deployment-task")" = "$retained_task" ] || reject retained-snapshot-task
    [ "$(cat -- "$retained_snapshot/.deployment-old-owner")" = "$retained_manifest" ] || reject retained-snapshot-owner
    [ "$(cat -- "$retained_snapshot/current-path")" = "$retained_old" ] || reject retained-snapshot-current
    [ -L "$retained_root/current" ] || reject retained-current-not-link
    retained_current="$(readlink -f -- "$retained_root/current")" || reject retained-current-unreadable
    case "$retained_current" in "$retained_old" | "$retained_new") ;; *) reject retained-current-drift ;; esac
    if [ -e "$retained_marker" ] || [ -L "$retained_marker" ]; then
        assert_root_owned_regular "$retained_marker"
        [ "$(cat -- "$retained_marker")" = "deployment-$retained_target" ] || reject retained-maintenance-foreign
    fi
    if [ -e "$retained_old/.windowstolinux-container-parameters" ]; then
        retained_kind=container
        assert_root_owned_regular "$retained_snapshot/container-parameters"
        assert_root_owned_regular "$retained_snapshot/autostart"
        assert_root_owned_regular "$retained_new/.windowstolinux-container-parameters"
        [ ! -e "$retained_new/.windowstolinux-deployment-parameters" ] || reject retained-kind-drift
    else
        retained_kind=deployment
        for retained_file in kind unit enabled deployment-parameters; do
            assert_root_owned_regular "$retained_snapshot/$retained_file"
        done
        [ "$(cat -- "$retained_snapshot/kind")" = deployment ] || reject retained-kind-drift
        assert_root_owned_regular "$retained_new/.windowstolinux-deployment-parameters"
        [ ! -e "$retained_new/.windowstolinux-container-parameters" ] || reject retained-kind-drift
        [ -e "$retained_root/.migration-snapshot" ] || reject retained-snapshot-marker
        assert_root_owned_regular "$retained_root/.migration-snapshot"
        [ "$(cat -- "$retained_root/.migration-snapshot")" = "$retained_token" ] || reject retained-snapshot-marker
    fi
}

retained_cleanup_snapshot() {
    [ -e "$retained_marker" ] || reject retained-maintenance-missing
    assert_root_owned_regular "$retained_marker"
    [ "$(cat -- "$retained_marker")" = "deployment-$retained_target" ] || reject retained-maintenance-foreign
    rm -f -- "$retained_marker"
    if [ "$retained_kind" = deployment ]; then rm -f -- "$retained_root/.migration-snapshot"; fi
    rm -rf --one-file-system -- "$retained_snapshot"
}

activate_retained() {
    require_app "${1:-}"
    application_maintenance "$1"
    retained_identity "$@"
    [ "$retained_current" = "$retained_old" ] || reject retained-current-drift
    [ ! -e "$retained_marker" ] && [ ! -L "$retained_marker" ] || reject retained-maintenance-active
    if [ "$retained_kind" = container ]; then
        container_current_release "$retained_app" "$retained_manifest"
        [ "$previous_path" = "$retained_old" ] || reject retained-current-drift
        retained_engine="$container_engine"
        load_container_parameters "$retained_new/.windowstolinux-container-parameters"
        [ "$container_engine" = "$retained_engine" ] || reject retained-engine-change
        [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ] || reject retained-identity-policy
    else
        assert_deployment_current_or_empty "$retained_app" "$retained_manifest"
        [ "$previous_path" = "$retained_old" ] || reject retained-current-drift
        load_deployment_parameters "$retained_new/.windowstolinux-deployment-parameters"
        [ "$runtime_identity_policy" = SYSTEMD_STATIC ] || reject retained-identity-policy
    fi
    printf '%s\n' "deployment-$retained_target" >"$retained_marker"
    chown root:root -- "$retained_marker"
    chmod 600 -- "$retained_marker"
    if [ "$retained_kind" = container ]; then
        stop_container_runtime "$retained_app"
        ln -sfnT -- "$retained_new" "$retained_root/current"
        start_container_release "$retained_app" "$retained_target" "$retained_manifest"
        if [ "$(cat -- "$retained_snapshot/autostart")" = no ]; then
            if [ "$container_engine" = docker ]; then
                docker update --restart no "$(container_name "$retained_app")" >/dev/null
            else set_podman_quadlet_autostart "$retained_app" 0; fi
        fi
        if [ "$(cat -- "$retained_snapshot/runtime")" = 0 ]; then stop_container_runtime "$retained_app"; fi
    else
        local unit tmp
        unit="$(unit_path "$retained_app")"
        tmp="$(mktemp /etc/systemd/system/.windowstolinux-retained.XXXXXX)"
        trap 'rm -f -- "$tmp"' EXIT
        render_deployment_unit "$retained_app" "${deployment_runtime_parameters[@]}" >"$tmp"
        stop_application_unit "$retained_app"
        prepare_managed_data_bindings "$retained_new/source"
        ln -sfnT -- "$retained_new" "$retained_root/current"
        install -o root -g root -m 644 -- "$tmp" "$unit"
        rm -f -- "$tmp"
        trap - EXIT
        systemctl daemon-reload
        if [ "$(cat -- "$retained_snapshot/enabled")" = enabled ] && [ "$application_mode" = DAEMON ]; then
            systemctl enable "$(unit_name "$retained_app")"
        else systemctl disable "$(unit_name "$retained_app")"; fi
        if [ "$(cat -- "$retained_snapshot/runtime")" = active ] && [ "$application_mode" = DAEMON ]; then
            systemctl start "$(unit_name "$retained_app")"
        else stop_application_unit "$retained_app"; fi
    fi
    printf 'SWITCHED=1\n'
}

rollback_retained() {
    require_app "${1:-}"
    application_maintenance "$1"
    retained_identity "$@"
    [ -e "$retained_marker" ] || reject retained-maintenance-missing
    if [ "$retained_kind" = container ]; then
        load_container_parameters "$retained_snapshot/container-parameters"
        [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ] || reject retained-identity-policy
        stop_container_runtime "$retained_app"
        ln -sfnT -- "$retained_old" "$retained_root/current"
        start_container_release "$retained_app" "$retained_source" "$retained_manifest"
        if [ "$(cat -- "$retained_snapshot/autostart")" = no ]; then
            if [ "$container_engine" = docker ]; then
                docker update --restart no "$(container_name "$retained_app")" >/dev/null
            else set_podman_quadlet_autostart "$retained_app" 0; fi
        fi
        if [ "$(cat -- "$retained_snapshot/runtime")" = 0 ]; then stop_container_runtime "$retained_app"; fi
    else
        local unit expected
        unit="$(unit_path "$retained_app")"
        load_deployment_parameters "$retained_snapshot/deployment-parameters"
        expected="$(expected_deployment_unit_digest "$retained_app" "${deployment_runtime_parameters[@]}")"
        [ "$(sha256sum -- "$retained_snapshot/unit" | awk '{print $1}')" = "$expected" ] || reject retained-snapshot-unit
        stop_application_unit "$retained_app"
        prepare_managed_data_bindings "$retained_old/source"
        ln -sfnT -- "$retained_old" "$retained_root/current"
        install -o root -g root -m 644 -- "$retained_snapshot/unit" "$unit"
        systemctl daemon-reload
        if [ "$(cat -- "$retained_snapshot/enabled")" = enabled ]; then
            systemctl enable "$(unit_name "$retained_app")"
        else systemctl disable "$(unit_name "$retained_app")"; fi
        if [ "$(cat -- "$retained_snapshot/runtime")" = active ]; then
            systemctl start "$(unit_name "$retained_app")"
        else stop_application_unit "$retained_app"; fi
    fi
    retained_cleanup_snapshot
    printf 'ROLLED_BACK=1\n'
}

finish_retained() {
    require_app "${1:-}"
    application_maintenance "$1"
    retained_identity "$@"
    [ "$retained_current" = "$retained_new" ] || reject retained-current-drift
    if [ "$retained_kind" = container ]; then
        container_current_release "$retained_app" "$retained_manifest"
    else assert_deployment_current_or_empty "$retained_app" "$retained_manifest"; fi
    [ "$previous_path" = "$retained_new" ] || reject retained-current-drift
    retained_cleanup_snapshot
    printf 'FINISHED=1\n'
}

retained_status() {
    [ "$#" -eq 5 ] || reject retained-status-arguments
    local app="$1" source="$2" target="$3" manifest="$4" task="$5"
    require_app "$app"
    require_digest "$source"
    require_digest "$target"
    require_digest "$manifest"
    require_snapshot_token "$task"
    application_maintenance "$app"
    local root old new current snapshot marker snapshot_state=absent marker_state=absent current_state
    root="$(app_root "$app")"
    old="$root/releases/$source"
    new="$root/releases/$target"
    assert_root_owned_directory "$root"
    for current in "$old" "$new"; do
        assert_root_owned_directory "$current"
        assert_root_owned_regular "$current/.windowstolinux-owner"
        [ "$(cat -- "$current/.windowstolinux-owner")" = "$manifest" ] || reject retained-owner
    done
    [ -L "$root/current" ] || reject retained-current-not-link
    current="$(readlink -f -- "$root/current")" || reject retained-current-unreadable
    case "$current" in
        "$old") current_state=source ;;
        "$new") current_state=target ;;
        *) reject retained-current-drift ;;
    esac
    snapshot="$(snapshot_root "$app" "$task")"
    if [ -e "$snapshot" ] || [ -L "$snapshot" ]; then
        assert_root_owned_directory "$snapshot"
        for current in .deployment-task .deployment-old-owner current-path; do
            assert_root_owned_regular "$snapshot/$current"
        done
        [ "$(cat -- "$snapshot/.deployment-task")" = "$task" ] || reject retained-snapshot-task
        [ "$(cat -- "$snapshot/.deployment-old-owner")" = "$manifest" ] || reject retained-snapshot-owner
        [ "$(cat -- "$snapshot/current-path")" = "$old" ] || reject retained-snapshot-current
        snapshot_state=owned
    fi
    marker="$(application_lock_directory "$app")/maintenance"
    if [ -e "$marker" ] || [ -L "$marker" ]; then
        assert_root_owned_regular "$marker"
        [ "$(cat -- "$marker")" = "deployment-$target" ] || reject retained-maintenance-foreign
        marker_state=owned
    fi
    printf 'RETAINED_STATUS=1\nCURRENT=%s\nSNAPSHOT=%s\nMARKER=%s\n' \
        "$current_state" "$snapshot_state" "$marker_state"
}
