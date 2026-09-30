# Root task and distinct operation snapshots share the original runtime-specific capture. / 根任务与独立操作快照复用原运行类型捕获。
snapshot_deployment() {
    [ "$#" -eq 2 ] || [ "$#" -eq 3 ] || reject snapshot-deployment-arguments
    snapshot_deployment_owned "$1" "$2" "${3:-}" "${3:-}" 0
}
snapshot_container() {
    [ "$#" -eq 2 ] || [ "$#" -eq 3 ] || reject snapshot-container-arguments
    snapshot_container_owned "$1" "$2" "${3:-}" "${3:-}" 0
}
snapshot_deployment_owned() {
    [ "$#" -eq 5 ] || reject snapshot-deployment-owned-arguments
    local app="$1" manifest="$2" task="$3" operation="$4" attempt="$5"
    require_app "$app"
    require_digest "$manifest"
    if [ -n "$task" ]; then require_snapshot_token "$task"; fi
    if [ -n "$operation" ]; then require_snapshot_token "$operation"; fi
    case "$attempt" in 0 | 1) ;; *) reject snapshot-attempt-flag ;; esac
    initialise_controlled_roots
    assert_application_root_or_absent "$app"
    assert_deployment_or_ordinary_current_or_empty "$app" "$manifest"
    if [ "$previous_present" -eq 0 ]; then
        printf 'PREVIOUS=0\n'
        return
    fi
    local token snapshot unit
    if [ -n "$operation" ]; then token="$operation"; else token="$(cat /proc/sys/kernel/random/uuid)"; fi
    require_snapshot_token "$token"
    snapshot="$(snapshot_root "$app" "$token")"
    unit="$(unit_path "$app")"
    [ ! -e "$snapshot" ] && [ ! -L "$snapshot" ] || reject snapshot-exists
    install -d -o root -g root -m 700 -- "$snapshot"
    printf '%s\n' "$previous_path" >"$snapshot/current-path"
    printf '%s\n' "$previous_kind" >"$snapshot/kind"
    install -o root -g root -m 600 -- "$unit" "$snapshot/unit"
    if [ "$previous_kind" = deployment ]; then
        install -o root -g root -m 600 -- "$previous_path/.windowstolinux-deployment-parameters" "$snapshot/deployment-parameters"
    fi
    systemctl is-enabled "$(unit_name "$app")" >"$snapshot/enabled" 2>/dev/null || true
    if [ "$previous_running" -eq 1 ]; then printf 'active\n' >"$snapshot/runtime"; else printf 'inactive\n' >"$snapshot/runtime"; fi
    chown root:root -- "$snapshot/current-path" "$snapshot/kind" "$snapshot/enabled" "$snapshot/runtime"
    chmod 600 -- "$snapshot/current-path" "$snapshot/kind" "$snapshot/enabled" "$snapshot/runtime"
    if [ -n "$task" ]; then
        install -o root -g root -m 600 -- "$previous_path/.windowstolinux-owner" "$snapshot/.deployment-old-owner"
        printf '%s\n' "$task" >"$snapshot/.deployment-task"
        chown root:root -- "$snapshot/.deployment-task"
        chmod 600 -- "$snapshot/.deployment-task"
        if [ "$attempt" = 1 ]; then
            printf '%s\n' "$operation" >"$snapshot/.deployment-operation"
            chown root:root -- "$snapshot/.deployment-operation"
            chmod 600 -- "$snapshot/.deployment-operation"
        fi
    fi
    if [ "$previous_kind" = deployment ]; then
        chown root:root -- "$snapshot/deployment-parameters"
        chmod 600 -- "$snapshot/deployment-parameters"
    fi
    printf '%s\n' "$token" >"$(app_root "$app")/.migration-snapshot"
    chmod 400 -- "$(app_root "$app")/.migration-snapshot"
    printf 'SNAPSHOT_TOKEN=%s\n' "$token"
    printf 'PREVIOUS=1\n'
    printf 'PREVIOUS_RUNNING=%s\n' "$previous_running"
    printf 'PREVIOUS_DIGEST=%s\n' "${previous_path##*/}"
    printf 'PREVIOUS_KIND=%s\n' "$previous_kind"
}
snapshot_container_owned() {
    [ "$#" -eq 5 ] || reject snapshot-container-owned-arguments
    local app="$1" manifest="$2" task="$3" operation="$4" attempt="$5"
    current_application="$app"
    require_app "$app"
    require_digest "$manifest"
    if [ -n "$task" ]; then require_snapshot_token "$task"; fi
    if [ -n "$operation" ]; then require_snapshot_token "$operation"; fi
    case "$attempt" in 0 | 1) ;; *) reject snapshot-attempt-flag ;; esac
    initialise_controlled_roots
    container_current_release "$app" "$manifest"
    if [ "$previous_present" -eq 0 ]; then
        printf 'PREVIOUS=0\n'
        return
    fi
    local token snapshot autostart
    if [ -n "$operation" ]; then token="$operation"; else token="$(cat /proc/sys/kernel/random/uuid)"; fi
    require_snapshot_token "$token"
    snapshot="$(snapshot_root "$app" "$token")"
    if [ "$attempt" = 1 ]; then [ ! -e "$snapshot" ] && [ ! -L "$snapshot" ] || reject snapshot-exists; fi
    install -d -o root -g root -m 700 -- "$snapshot"
    printf '%s\n' "$previous_path" >"$snapshot/current-path"
    printf '%s\n' "$previous_running" >"$snapshot/runtime"
    install -o root -g root -m 600 -- "$previous_path/.windowstolinux-container-parameters" "$snapshot/container-parameters"
    if [ "$container_engine" = docker ]; then
        autostart="$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$(container_name "$app")" 2>/dev/null || true)"
    else
        if podman_quadlet_autostart_enabled "$app"; then autostart=enabled; else autostart=no; fi
    fi
    printf '%s\n' "$autostart" >"$snapshot/autostart"
    chown root:root -- "$snapshot/current-path" "$snapshot/runtime" "$snapshot/container-parameters" "$snapshot/autostart"
    chmod 600 -- "$snapshot/current-path" "$snapshot/runtime" "$snapshot/container-parameters" "$snapshot/autostart"
    if [ -n "$task" ]; then
        install -o root -g root -m 600 -- "$previous_path/.windowstolinux-owner" "$snapshot/.deployment-old-owner"
        printf '%s\n' "$task" >"$snapshot/.deployment-task"
        chown root:root -- "$snapshot/.deployment-task"
        chmod 600 -- "$snapshot/.deployment-task"
        if [ "$attempt" = 1 ]; then
            printf '%s\n' "$operation" >"$snapshot/.deployment-operation"
            chown root:root -- "$snapshot/.deployment-operation"
            chmod 600 -- "$snapshot/.deployment-operation"
        fi
    fi
    printf 'SNAPSHOT_TOKEN=%s\nPREVIOUS=1\nPREVIOUS_RUNNING=%s\nPREVIOUS_DIGEST=%s\nPREVIOUS_KIND=container\n' "$token" "$previous_running" "${previous_path##*/}"
}

assert_attempt_previous_current() {
    [ "$#" -eq 3 ] || reject attempt-current-arguments
    local app="$1" manifest="$2" expected="$3" root current
    require_app "$app"
    require_digest "$manifest"
    assert_application_root_or_absent "$app"
    root="$(app_root "$app")"
    if [ "$expected" = empty ]; then
        [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject attempt-current-changed
        return
    fi
    require_digest "$expected"
    [ -L "$root/current" ] || reject attempt-current-changed
    current="$(readlink -f -- "$root/current")" || reject attempt-current-unreadable
    [ "$current" = "$root/releases/$expected" ] || reject attempt-current-changed
    assert_root_owned_directory "$current"
    assert_root_owned_regular "$current/.windowstolinux-owner"
    [ "$(cat -- "$current/.windowstolinux-owner")" = "$manifest" ] || reject attempt-current-owner
}
snapshot_publication_attempt() {
    [ "$#" -eq 6 ] || reject snapshot-attempt-arguments
    local kind="$1" app="$2" manifest="$3" task="$4" operation="$5" expected="$6"
    require_app "$app"
    require_digest "$manifest"
    require_snapshot_token "$task"
    require_snapshot_token "$operation"
    case "$kind" in deployment | container) ;; *) reject snapshot-attempt-kind ;; esac
    initialise_controlled_roots
    application_maintenance "$app"
    assert_attempt_previous_current "$app" "$manifest" "$expected"
    if [ "$kind" = deployment ]; then snapshot_deployment_owned "$app" "$manifest" "$task" "$operation" 1
    else snapshot_container_owned "$app" "$manifest" "$task" "$operation" 1; fi
}
snapshot_deployment_attempt() {
    [ "$#" -eq 5 ] || reject snapshot-attempt-arguments
    snapshot_publication_attempt deployment "$@"
}
snapshot_container_attempt() {
    [ "$#" -eq 5 ] || reject snapshot-attempt-arguments
    snapshot_publication_attempt container "$@"
}
