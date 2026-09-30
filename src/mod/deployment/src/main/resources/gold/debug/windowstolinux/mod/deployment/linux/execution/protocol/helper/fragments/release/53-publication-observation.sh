publication_original_status() {
    [ "$#" -eq 7 ] || reject publication-status-arguments
    publication_original_status_owned "$1" "$2" "$3" "$3" "$4" "$5" "$6" "$7" 0
}
publication_original_status_attempt() {
    [ "$#" -eq 8 ] || reject publication-status-attempt-arguments
    publication_original_status_owned "$@" 1
}
publication_original_status_owned() {
    [ "$#" -eq 9 ] || reject publication-status-owned-arguments
    local app="$1" manifest="$2" task="$3" operation="$4" candidate_id="$5" release="$6" backend="$7" engine="$8" attempt="$9"
    require_app "$app"
    require_digest "$manifest"
    require_snapshot_token "$task"
    require_snapshot_token "$operation"
    case "$attempt" in 0 | 1) ;; *) reject publication-attempt-flag ;; esac
    require_candidate "$app" "$candidate_id"
    if [ "$release" != none ]; then require_digest "$release"; fi
    case "$backend:$engine" in native:none | container:docker | container:podman) ;; *) reject publication-status-backend ;; esac
    local root current=none current_kind=none current_mode=none current_path current_digest
    root="$(app_root "$app")"
    if [ -e "$root" ] || [ -L "$root" ]; then
        assert_root_owned_directory "$root"
        if [ -e "$root/current" ] || [ -L "$root/current" ]; then
            [ -L "$root/current" ] || reject publication-current-not-link
            current_path="$(readlink -f -- "$root/current")" || reject publication-current-unreadable
            current_digest="${current_path##*/}"
            require_digest "$current_digest"
            [ "$current_path" = "$root/releases/$current_digest" ] || reject publication-current-path
            assert_root_owned_directory "$current_path"
            assert_root_owned_regular "$current_path/.windowstolinux-owner"
            [ "$(cat -- "$current_path/.windowstolinux-owner")" = "$manifest" ] || reject publication-current-owner
            current="$current_digest"
            if [ -e "$current_path/.windowstolinux-deployment-parameters" ] || [ -L "$current_path/.windowstolinux-deployment-parameters" ]; then
                assert_root_owned_regular "$current_path/.windowstolinux-deployment-parameters"
                current_application="$app"
                load_deployment_parameters "$current_path/.windowstolinux-deployment-parameters"
                current_kind=deployment
                current_mode="$application_mode"
            elif [ -e "$current_path/.windowstolinux-container-parameters" ] || [ -L "$current_path/.windowstolinux-container-parameters" ]; then
                assert_root_owned_regular "$current_path/.windowstolinux-container-parameters"
                load_container_parameters "$current_path/.windowstolinux-container-parameters"
                current_kind=container
                current_mode="$application_mode"
            else
                current_kind=ordinary
                current_mode=unknown
            fi
        fi
    fi
    local snapshot=none old=none old_kind=none old_running=none path original previous
    if [ -e "$snapshots_root" ] || [ -L "$snapshots_root" ]; then
        assert_root_owned_directory "$snapshots_root"
        path="$(snapshot_root "$app" "$operation")"
        if [ -e "$path" ] || [ -L "$path" ]; then
            assert_root_owned_directory "$path"
            assert_root_owned_regular "$path/.deployment-task"
            original="$(cat -- "$path/.deployment-task")"
            [ "$original" = "$task" ] || reject publication-snapshot-foreign
            if [ "$attempt" = 1 ]; then
                assert_root_owned_regular "$path/.deployment-operation"
                [ "$(cat -- "$path/.deployment-operation")" = "$operation" ] || reject publication-snapshot-operation
            fi
            snapshot="$operation"
            assert_root_owned_regular "$path/current-path"
            assert_root_owned_regular "$path/runtime"
            previous="$(cat -- "$path/current-path")"
            old="${previous##*/}"
            require_digest "$old"
            [ "$previous" = "$root/releases/$old" ] || reject publication-snapshot-path
            assert_root_owned_regular "$path/.deployment-old-owner"
            [ "$(cat -- "$path/.deployment-old-owner")" = "$manifest" ] || reject publication-snapshot-owner
            if [ -e "$previous" ] || [ -L "$previous" ]; then
                assert_root_owned_directory "$previous"
                assert_root_owned_regular "$previous/.windowstolinux-owner"
                [ "$(cat -- "$previous/.windowstolinux-owner")" = "$manifest" ] || reject publication-snapshot-owner
            fi
            old_running="$(cat -- "$path/runtime")"
            case "$old_running" in 0 | 1) old_kind=container ;; active) old_running=1 ;; inactive) old_running=0 ;; *) reject publication-snapshot-runtime ;; esac
            if [ "$old_kind" != container ]; then
                assert_root_owned_regular "$path/kind"
                old_kind="$(cat -- "$path/kind")"
                case "$old_kind" in deployment | ordinary) ;; *) reject publication-snapshot-kind ;; esac
            fi
        fi
    fi
    local marker=none lock="$base_root/application-locks/$app/maintenance"
    if [ -e "$lock" ] || [ -L "$lock" ]; then
        assert_root_owned_regular "$lock"
        marker="$(cat -- "$lock")"
        [ "$release" != none ] && [ "$marker" = "deployment-$release" ] || reject publication-maintenance-foreign
    fi
    local candidate=absent candidate_path build_active unit_state
    candidate_path="$(candidate_root "$candidate_id")"
    if [ -e "$candidate_path" ] || [ -L "$candidate_path" ]; then
        assert_candidate_for_deployer "$candidate_path"
        assert_root_owned_regular "$candidate_path/.agent-task"
        [ "$(cat -- "$candidate_path/.agent-task")" = "$task" ] || reject publication-candidate-foreign
        candidate=owned
    fi
    unit_state="$(systemctl show --value --property=ActiveState "$(build_unit_name "$candidate_id")")" || reject publication-build-query
    case "$unit_state" in active | activating | deactivating | reloading) build_active=yes ;; inactive | failed | '') build_active=no ;; *) reject publication-build-state ;; esac
    local unit_present=no container_present=not-applicable found load_state active_state retained_count=0 release_path release_name
    if [ -e "$(unit_path "$app")" ] || [ -L "$(unit_path "$app")" ]; then
        assert_root_owned_regular "$(unit_path "$app")"
        unit_present=yes
    fi
    if [ -e "$(podman_quadlet_path "$app")" ] || [ -L "$(podman_quadlet_path "$app")" ]; then
        assert_root_owned_regular "$(podman_quadlet_path "$app")"
        unit_present=yes
    fi
    load_state="$(systemctl show --value --property=LoadState "$(unit_name "$app")")" || reject publication-unit-load-query
    active_state="$(systemctl show --value --property=ActiveState "$(unit_name "$app")")" || reject publication-unit-active-query
    case "$load_state" in loaded | not-found | masked | error | bad-setting) ;; *) reject publication-unit-load-state ;; esac
    case "$active_state" in active | inactive | activating | deactivating | failed | reloading | maintenance) ;; *) reject publication-unit-active-state ;; esac
    if [ "$load_state:$active_state" != not-found:inactive ]; then unit_present=yes; fi
    if [ "$backend" = container ]; then
        command -v "$engine" >/dev/null || reject publication-container-engine-unavailable
        found="$("$engine" ps --all --filter "name=$(container_name "$app")" --format '{{.Names}}')" || reject publication-container-query
        container_present=no
        if printf '%s\n' "$found" | grep -Fxq -- "$(container_name "$app")"; then container_present=yes; fi
    fi
    if [ -e "$root/releases" ] || [ -L "$root/releases" ]; then
        assert_root_owned_directory "$root/releases"
        for release_path in "$root/releases"/*; do
            [ -e "$release_path" ] || [ -L "$release_path" ] || continue
            release_name="${release_path##*/}"
            require_digest "$release_name"
            assert_root_owned_directory "$release_path"
            assert_root_owned_regular "$release_path/.windowstolinux-owner"
            [ "$(cat -- "$release_path/.windowstolinux-owner")" = "$manifest" ] || reject publication-release-foreign
            retained_count=$((retained_count + 1))
            [ "$retained_count" -le 1000 ] || reject publication-release-count
        done
    fi
    printf 'PUBLICATION_STATUS=1\nCURRENT=%s\nCURRENT_KIND=%s\nCURRENT_MODE=%s\nSNAPSHOT=%s\nSNAPSHOT_OLD=%s\nSNAPSHOT_KIND=%s\nSNAPSHOT_RUNNING=%s\nMAINTENANCE=%s\nCANDIDATE=%s\nBUILD_ACTIVE=%s\nUNIT_PRESENT=%s\nCONTAINER_PRESENT=%s\nRETAINED_COUNT=%s\n' \
        "$current" "$current_kind" "$current_mode" "$snapshot" "$old" "$old_kind" "$old_running" "$marker" "$candidate" "$build_active" "$unit_present" "$container_present" "$retained_count"
}

discard_original_snapshot() {
    [ "$#" -eq 3 ] || reject discard-snapshot-arguments
    discard_original_snapshot_owned "$1" "$2" "$3" "$3" 0
}
discard_original_snapshot_attempt() {
    [ "$#" -eq 4 ] || reject discard-snapshot-attempt-arguments
    discard_original_snapshot_owned "$@" 1
}
discard_original_snapshot_owned() {
    [ "$#" -eq 5 ] || reject discard-snapshot-owned-arguments
    local app="$1" manifest="$2" task="$3" operation="$4" attempt="$5" root path previous old marker
    require_app "$app"
    require_digest "$manifest"
    require_snapshot_token "$task"
    require_snapshot_token "$operation"
    case "$attempt" in 0 | 1) ;; *) reject discard-attempt-flag ;; esac
    application_maintenance "$app"
    root="$(app_root "$app")"
    path="$(snapshot_root "$app" "$operation")"
    assert_root_owned_directory "$root"
    assert_root_owned_directory "$path"
    assert_root_owned_regular "$path/.deployment-task"
    [ "$(cat -- "$path/.deployment-task")" = "$task" ] || reject discard-snapshot-foreign
    if [ "$attempt" = 1 ]; then
        assert_root_owned_regular "$path/.deployment-operation"
        [ "$(cat -- "$path/.deployment-operation")" = "$operation" ] || reject discard-snapshot-operation
    fi
    assert_root_owned_regular "$path/current-path"
    previous="$(cat -- "$path/current-path")"
    old="${previous##*/}"
    require_digest "$old"
    [ "$previous" = "$root/releases/$old" ] || reject discard-snapshot-path
    assert_root_owned_directory "$previous"
    assert_root_owned_regular "$previous/.windowstolinux-owner"
    [ "$(cat -- "$previous/.windowstolinux-owner")" = "$manifest" ] || reject discard-snapshot-owner
    [ -L "$root/current" ] || reject discard-current-missing
    [ "$(readlink -f -- "$root/current")" = "$previous" ] || reject discard-current-changed
    marker="$(application_lock_directory "$app")/maintenance"
    [ ! -e "$marker" ] && [ ! -L "$marker" ] || reject discard-maintenance-active
    if [ -e "$root/.migration-snapshot" ] || [ -L "$root/.migration-snapshot" ]; then
        assert_root_owned_regular "$root/.migration-snapshot"
        [ "$(cat -- "$root/.migration-snapshot")" = "$operation" ] || reject discard-migration-foreign
        rm -f -- "$root/.migration-snapshot"
    fi
    rm -rf --one-file-system -- "$path"
    printf 'SNAPSHOT_DISCARDED=1\n'
}
