rollback_deployment() {
    [ "$#" -eq 4 ] || reject rollback-deployment-arguments
    local app="$1" candidate_digest="$2" manifest="$3" token="$4"
    require_app "$app"
    require_digest "$candidate_digest"
    require_digest "$manifest"
    require_snapshot_token "$token"
    local root releases candidate snapshot unit expected previous previous_digest previous_runtime previous_kind
    root="$(app_root "$app")"
    releases="$root/releases"
    candidate="$releases/$candidate_digest"
    snapshot="$(snapshot_root "$app" "$token")"
    unit="$(unit_path "$app")"
    assert_root_owned_directory "$root"
    assert_root_owned_directory "$releases"
    assert_root_owned_directory "$snapshot"
    for file in current-path kind unit runtime enabled; do assert_root_owned_regular "$snapshot/$file"; done
    previous_kind="$(cat -- "$snapshot/kind")"
    [ "$previous_kind" = deployment ] || reject snapshot-kind
    previous="$(cat -- "$snapshot/current-path")"
    previous_digest="${previous##*/}"
    require_digest "$previous_digest"
    [ "$previous" = "$releases/$previous_digest" ] || reject snapshot-current
    previous_runtime="$(cat -- "$snapshot/runtime")"
    [ "$previous_runtime" = active ] || [ "$previous_runtime" = inactive ] || reject snapshot-runtime
    assert_root_owned_directory "$previous"
    assert_root_owned_regular "$previous/.windowstolinux-owner"
    [ "$(cat -- "$previous/.windowstolinux-owner")" = "$manifest" ] || reject previous-owner
    assert_root_owned_regular "$snapshot/deployment-parameters"
    current_application="$app"
    load_deployment_parameters "$snapshot/deployment-parameters"
    expected="$(expected_deployment_unit_digest "$app" "${deployment_runtime_parameters[@]}")"
    [ "$(sha256sum -- "$snapshot/unit" | awk '{print $1}')" = "$expected" ] || reject snapshot-unit
    if [ -e "$candidate" ] || [ -L "$candidate" ]; then
        assert_root_owned_directory "$candidate"
        assert_root_owned_regular "$candidate/.windowstolinux-owner"
        [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
        assert_root_owned_regular "$candidate/.windowstolinux-deployment-parameters"
    fi
    stop_application_unit "$app"
    prepare_managed_data_bindings "$previous/source"
    ln -sfnT -- "$previous" "$root/current"
    install -o root -g root -m 644 -- "$snapshot/unit" "$unit"
    systemctl daemon-reload
    if [ "$(cat -- "$snapshot/enabled")" = enabled ]; then systemctl enable "$(unit_name "$app")"; else systemctl disable "$(unit_name "$app")"; fi
    if [ "$previous_runtime" = active ]; then systemctl start "$(unit_name "$app")"; else stop_application_unit "$app"; fi
    if [ "$candidate" != "$previous" ] && { [ -e "$candidate" ] || [ -L "$candidate" ]; }; then rm -rf --one-file-system -- "$candidate"; fi
    rm -rf --one-file-system -- "$snapshot"
    printf 'ROLLED_BACK=1\n'
}
rollback_deployment_first() {
    [ "$#" -eq 3 ] || reject rollback-deployment-first-arguments
    local app="$1" candidate_digest="$2" manifest="$3"
    require_app "$app"
    require_digest "$candidate_digest"
    require_digest "$manifest"
    local root releases candidate unit expected
    root="$(app_root "$app")"
    releases="$root/releases"
    candidate="$releases/$candidate_digest"
    unit="$(unit_path "$app")"
    assert_root_owned_directory "$root"
    assert_root_owned_directory "$releases"
    if [ ! -e "$candidate" ] && [ ! -L "$candidate" ]; then
        [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject rollback-current
        [ ! -e "$unit" ] && [ ! -L "$unit" ] || reject current-unit
        rmdir -- "$releases" "$root" 2>/dev/null || true
        printf 'ROLLED_BACK=1\n'
        return
    fi
    if [ ! -e "$candidate/.windowstolinux-owner" ] && [ ! -L "$candidate/.windowstolinux-owner" ]; then
        [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject rollback-current
        [ ! -e "$unit" ] && [ ! -L "$unit" ] || reject current-unit
        assert_root_owned_directory "$candidate"
        rm -rf --one-file-system -- "$candidate"
        rmdir -- "$releases" "$root" 2>/dev/null || true
        printf 'ROLLED_BACK=1\n'
        return
    fi
    assert_root_owned_directory "$candidate"
    assert_root_owned_regular "$candidate/.windowstolinux-owner"
    [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
    current_application="$app"
    load_deployment_parameters "$candidate/.windowstolinux-deployment-parameters"
    expected="$(expected_deployment_unit_digest "$app" "${deployment_runtime_parameters[@]}")"
    if [ -e "$unit" ] || [ -L "$unit" ]; then
        assert_root_owned_regular "$unit"
        [ "$(sha256sum -- "$unit" | awk '{print $1}')" = "$expected" ] || reject current-unit
        stop_application_unit "$app"
        rm -f -- "$unit"
    fi
    if [ -e "$root/current" ] || [ -L "$root/current" ]; then rm -f -- "$root/current"; fi
    rm -rf --one-file-system -- "$candidate"
    systemctl daemon-reload
    printf 'ROLLED_BACK=1\n'
}
lifecycle_deployment() {
    [ "$#" -eq 4 ] || reject lifecycle-deployment-arguments
    local app="$1" action="$2" manifest="$3" expected="$4"
    require_app "$app"
    require_digest "$manifest"
    require_digest "$expected"
    application_maintenance "$app"
    case "$action" in start | stop | restart | enable | disable) ;; *) reject lifecycle-action ;; esac
    assert_deployment_current_or_empty "$app" "$manifest"
    [ "$previous_present" -eq 1 ] || reject lifecycle-unmanaged
    [ "$previous_path" = "$(app_root "$app")/releases/$expected" ] || reject lifecycle-current-drift
    [ "$application_mode" = DAEMON ] || reject application-lifecycle-not-applicable
    if [ "$action" = stop ]; then
        stop_requested_application_unit "$app"
    else systemctl "$action" "$(unit_name "$app")"; fi
    printf 'LIFECYCLE=%s\n' "$action"
}
observe_deployment() {
    [ "$#" -eq 2 ] || reject observe-deployment-arguments
    local app="$1" manifest="$2" running enabled
    require_app "$app"
    require_digest "$manifest"
    assert_deployment_current_or_empty "$app" "$manifest"
    [ "$previous_present" -eq 1 ] || reject lifecycle-unmanaged
    if [ "$application_mode" = ON_DEMAND ]; then
        printf 'OWNER=1\nINSTALLED=1\nRUNNING=0\nENABLED=not-applicable\nQUERY_OK=1\nActiveState=inactive\nMainPID=0\n'
        return
    fi
    if systemctl is-active --quiet "$(unit_name "$app")"; then running=1; else running=0; fi
    enabled="$(systemctl is-enabled "$(unit_name "$app")" 2>/dev/null || true)"
    printf 'OWNER=1\nRUNNING=%s\nENABLED=%s\n' "$running" "$enabled"
    observe_application_unit "$(unit_name "$app")"
}
