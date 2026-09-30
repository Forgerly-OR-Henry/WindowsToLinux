stop_container_runtime() {
    local app="$1" name found
    name="$(container_name "$app")"
    if [ "$container_engine" = podman ]; then
        if systemctl cat "windowstolinux-$app.service" >/dev/null 2>&1; then
            systemctl stop "windowstolinux-$app.service" || reject container-stop
            ! systemctl is-active --quiet "windowstolinux-$app.service" || reject container-still-active
        fi
    fi
    found="$("$container_engine" ps --all --filter "name=$name" --format '{{.Names}}')" || reject container-query
    if printf '%s\n' "$found" | grep -Fxq -- "$name"; then
        "$container_engine" stop "$name" >/dev/null || reject container-stop
        [ "$("$container_engine" inspect --format '{{.State.Running}}' "$name")" = false ] || reject container-still-running
    fi
}
rollback_container() {
    [ "$#" -eq 4 ] || reject rollback-container-arguments
    local app="$1" candidate_digest="$2" manifest="$3" token="$4"
    current_application="$app"
    require_app "$app"
    require_digest "$candidate_digest"
    require_digest "$manifest"
    require_snapshot_token "$token"
    local root releases candidate snapshot previous previous_digest previous_runtime
    root="$(app_root "$app")"
    releases="$root/releases"
    candidate="$releases/$candidate_digest"
    snapshot="$(snapshot_root "$app" "$token")"
    assert_root_owned_directory "$root"
    assert_root_owned_directory "$releases"
    assert_root_owned_directory "$snapshot"
    assert_root_owned_regular "$snapshot/current-path"
    assert_root_owned_regular "$snapshot/runtime"
    assert_root_owned_regular "$snapshot/container-parameters"
    assert_root_owned_regular "$snapshot/autostart"
    load_container_parameters "$snapshot/container-parameters"
    previous="$(cat -- "$snapshot/current-path")"
    previous_digest="${previous##*/}"
    require_digest "$previous_digest"
    [ "$previous" = "$releases/$previous_digest" ] || reject snapshot-current
    assert_root_owned_directory "$previous"
    assert_root_owned_regular "$previous/.windowstolinux-owner"
    [ "$(cat -- "$previous/.windowstolinux-owner")" = "$manifest" ] || reject previous-owner
    previous_runtime="$(cat -- "$snapshot/runtime")"
    [ "$previous_runtime" = 0 ] || [ "$previous_runtime" = 1 ] || reject snapshot-runtime
    stop_container_runtime "$app"
    ln -sfnT -- "$previous" "$root/current"
    start_container_release "$app" "$previous_digest" "$manifest"
    if [ "$application_mode" = DAEMON ] && [ "$(cat -- "$snapshot/autostart")" = no ]; then
        if [ "$container_engine" = docker ]; then "$container_engine" update --restart no "$(container_name "$app")" >/dev/null; else set_podman_quadlet_autostart "$app" 0; fi
    fi
    if [ "$previous_runtime" = 0 ]; then stop_container_runtime "$app"; fi
    if [ "$candidate" != "$previous" ] && { [ -e "$candidate" ] || [ -L "$candidate" ]; }; then
        assert_root_owned_directory "$candidate"
        assert_root_owned_regular "$candidate/.windowstolinux-owner"
        [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
        remove_container_secret_delivery "$app" "$candidate_digest"
        rm -rf --one-file-system -- "$candidate"
    fi
    rm -rf --one-file-system -- "$snapshot"
    printf 'ROLLED_BACK=1\n'
}
rollback_container_first() {
    [ "$#" -eq 3 ] || reject rollback-container-first-arguments
    local app="$1" candidate_digest="$2" manifest="$3"
    current_application="$app"
    require_app "$app"
    require_digest "$candidate_digest"
    require_digest "$manifest"
    local root releases candidate
    root="$(app_root "$app")"
    releases="$root/releases"
    candidate="$releases/$candidate_digest"
    if [ -e "$root" ] || [ -L "$root" ]; then assert_root_owned_directory "$root"; fi
    if [ -e "$releases" ] || [ -L "$releases" ]; then assert_root_owned_directory "$releases"; fi
    if [ ! -e "$candidate" ] && [ ! -L "$candidate" ]; then
        [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject rollback-current
        ! docker inspect "$(container_name "$app")" >/dev/null 2>&1 || reject current-container
        ! podman inspect "$(container_name "$app")" >/dev/null 2>&1 || reject current-container
        [ ! -e "$(podman_quadlet_path "$app")" ] \
            && [ ! -L "$(podman_quadlet_path "$app")" ] || reject current-container-unit
        rmdir -- "$releases" "$root" 2>/dev/null || true
        printf 'ROLLED_BACK=1\n'
        return
    fi
    assert_root_owned_directory "$candidate"
    assert_root_owned_regular "$candidate/.windowstolinux-container-parameters"
    load_container_parameters "$candidate/.windowstolinux-container-parameters"
    stop_container_runtime "$app"
    if [ "$container_engine" = docker ]; then "$container_engine" rm -f "$(container_name "$app")" >/dev/null 2>&1 || true; fi
    if [ "$container_engine" = podman ]; then
        podman_cni_clear "$app" "$manifest"
        rm -f -- "$(podman_quadlet_path "$app")"
        rmdir -- "$(podman_quadlet_path "$app").d" 2>/dev/null || true
        systemctl daemon-reload
    fi
    if [ -e "$root/current" ] || [ -L "$root/current" ]; then rm -f -- "$root/current"; fi
    if [ -e "$candidate" ] || [ -L "$candidate" ]; then
        assert_root_owned_directory "$candidate"
        assert_root_owned_regular "$candidate/.windowstolinux-owner"
        [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
        remove_container_secret_delivery "$app" "$candidate_digest"
        rm -rf --one-file-system -- "$candidate"
    fi
    printf 'ROLLED_BACK=1\n'
}
lifecycle_container() {
    [ "$#" -eq 4 ] || reject lifecycle-container-arguments
    local app="$1" action="$2" manifest="$3" expected="$4"
    require_app "$app"
    require_digest "$manifest"
    require_digest "$expected"
    application_maintenance "$app"
    container_current_release "$app" "$manifest"
    [ "$previous_present" -eq 1 ] || reject lifecycle-unmanaged
    [ "$previous_path" = "$(app_root "$app")/releases/$expected" ] || reject lifecycle-current-drift
    [ "$application_mode" = DAEMON ] || reject application-lifecycle-not-applicable
    application_backend=container
    application_release="$previous_path"
    application_assert_inputs "$app"
    case "$action" in
        start)
            if [ "$container_engine" = docker ]; then docker start "$(container_name "$app")" >/dev/null; else systemctl start "windowstolinux-$app.service"; fi
            ;;
        stop) stop_container_runtime "$app" ;;
        restart)
            if [ "$container_engine" = docker ]; then docker restart "$(container_name "$app")" >/dev/null; else systemctl restart "windowstolinux-$app.service"; fi
            ;;
        enable)
            if [ "$container_engine" = docker ]; then "$container_engine" update --restart unless-stopped "$(container_name "$app")" >/dev/null; else set_podman_quadlet_autostart "$app" 1; fi
            ;;
        disable)
            if [ "$container_engine" = docker ]; then "$container_engine" update --restart no "$(container_name "$app")" >/dev/null; else set_podman_quadlet_autostart "$app" 0; fi
            ;;
        *) reject lifecycle-action ;;
    esac
    printf 'LIFECYCLE=%s\n' "$action"
}
