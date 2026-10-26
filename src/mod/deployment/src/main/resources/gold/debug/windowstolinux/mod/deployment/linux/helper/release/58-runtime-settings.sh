runtime_settings_parameters() {
    local app="$1" expected="$2" owner="$3" payload="$4" configuration="$5" port="$6" old kind count
    require_app "$app"
    require_digest "$expected"
    require_digest "$owner"
    require_digest "$configuration"
    [ "$port" = 0 ] || require_service_port "$port"
    assert_deployment_current_or_empty "$app" "$owner"
    [ "$previous_present" = 1 ] && [ "${previous_path##*/}" = "$expected" ] || reject runtime-settings-current
    old="$previous_path/.windowstolinux-deployment-parameters"
    assert_root_owned_regular "$old"
    mapfile -t settings_parameters <"$old"
    render_deployment_unit "$app" "${settings_parameters[@]}" >/dev/null
    settings_before_command="$deployment_rendered_command"
    settings_old_ports=("${application_port_keys[@]}")
    kind="${toolchain_remaining_arguments[0]}"
    count="${#settings_parameters[@]}"
    settings_parameters[2]="$payload"
    settings_parameters[3]="$configuration"
    if [ "$port" != 0 ]; then
        case "$kind" in
            static) settings_parameters[count-2]="$port" ;;
            php | phpcli | ruby | rubycli) settings_parameters[count-1]="$port" ;;
        esac
    fi
    render_deployment_unit "$app" "${settings_parameters[@]}" >/dev/null
    [ "$runtime_identity_policy" = SYSTEMD_STATIC ] || reject runtime-settings-identity
    settings_after_command="$deployment_rendered_command"
    settings_recipe="$(printf '%s\n' "${settings_parameters[@]}" | sha256sum | awk '{print $1}')"
}

runtime_settings_preview() {
    [ "$#" -eq 6 ] || reject runtime-settings-preview-arguments
    runtime_settings_parameters "$@"
    printf 'BEFORE=%s\nAFTER=%s\nRECIPE=%s\n' \
        "$(printf '%s' "$settings_before_command" | base64 -w0)" \
        "$(printf '%s' "$settings_after_command" | base64 -w0)" "$settings_recipe"
}

runtime_settings_derive() {
    [ "$#" -eq 10 ] || reject runtime-settings-derive-arguments
    local app="$1" old="$2" new="$3" owner="$4" task="$5" payload="$6" configuration="$7" port="$8" recipe="$9" intent="${10}"
    require_digest "$new"
    require_digest "$recipe"
    require_digest "$intent"
    require_snapshot_token "$task"
    application_maintenance "$app"
    local marker="$(application_lock_directory "$app")/maintenance"
    [ ! -e "$marker" ] && [ ! -L "$marker" ] || reject application-maintenance-in-progress
    runtime_settings_parameters "$app" "$old" "$owner" "$payload" "$configuration" "$port"
    [ "$settings_recipe" = "$recipe" ] || reject runtime-settings-recipe-changed
    assert_deployment_inputs "$app" systemd
    application_argv "$application_runtime_kind" "$previous_path" "$application_entry" "${application_args[@]}"
    local key previous found transport selected flags occupied
    for key in "${application_port_keys[@]}"; do
        found=0
        for previous in "${settings_old_ports[@]}"; do [ "$key" != "$previous" ] || found=1; done
        [ "$found" = 0 ] || continue
        transport="${key%%:*}"
        selected="${key##*:}"
        if [ "$transport" = udp ]; then flags=-Hlun; else flags=-Hltn; fi
        occupied="$(ss "$flags")" || reject runtime-settings-port-observation
        ! printf '%s\n' "$occupied" | awk -v port="$selected" '$4 ~ ":" port "$" {found=1} END {exit !found}' || reject runtime-settings-port-occupied
    done
    local root="$(app_root "$app")" target temporary
    target="$root/releases/$new"
    temporary="$root/releases/.settings-$task"
    [ ! -e "$target" ] && [ ! -L "$target" ] && [ ! -e "$temporary" ] && [ ! -L "$temporary" ] || reject runtime-settings-target-exists
    install -d -o root -g root -m 755 -- "$temporary"
    cp -a --no-dereference -- "$previous_path/." "$temporary/"
    save_deployment_parameters "$temporary/.windowstolinux-deployment-parameters" "${settings_parameters[@]}"
    printf '%s\n' "$task" "$old" "$recipe" "$intent" >"$temporary/.windowstolinux-settings-origin"
    chown root:root -- "$temporary/.windowstolinux-settings-origin"
    chmod 444 -- "$temporary/.windowstolinux-settings-origin"
    mv -T -- "$temporary" "$target"
    printf 'DERIVED=1\n'
}

runtime_settings_status() {
    [ "$#" -eq 7 ] || reject runtime-settings-status-arguments
    local app="$1" old="$2" new="$3" owner="$4" task="$5" recipe="$6" intent="$7" root target
    require_app "$app"
    require_digest "$old"
    require_digest "$new"
    require_digest "$owner"
    require_snapshot_token "$task"
    require_digest "$recipe"
    require_digest "$intent"
    root="$(app_root "$app")"
    target="$root/releases/$new"
    if [ ! -e "$target" ] && [ ! -L "$target" ] \
        && [ ! -e "$root/releases/.settings-$task" ] && [ ! -L "$root/releases/.settings-$task" ]; then
        assert_deployment_current_or_empty "$app" "$owner"
        [ "$previous_present" = 1 ] && [ "${previous_path##*/}" = "$old" ] || reject runtime-settings-current
        local maintenance="$base_root/application-locks/$app/maintenance" snapshot="$(snapshot_root "$app" "$task")"
        [ ! -e "$maintenance" ] && [ ! -L "$maintenance" ] && [ ! -e "$snapshot" ] && [ ! -L "$snapshot" ] || reject runtime-settings-unresolved
        printf 'DERIVED=0\n'
        return
    fi
    assert_root_owned_directory "$target"
    assert_root_owned_regular "$target/.windowstolinux-owner"
    [ "$(cat -- "$target/.windowstolinux-owner")" = "$owner" ] || reject runtime-settings-owner
    assert_root_owned_regular "$target/.windowstolinux-settings-origin"
    [ "$(cat -- "$target/.windowstolinux-settings-origin")" = "$(printf '%s\n' "$task" "$old" "$recipe" "$intent")" ] || reject runtime-settings-origin
    assert_root_owned_regular "$target/.windowstolinux-deployment-parameters"
    [ "$(sha256sum -- "$target/.windowstolinux-deployment-parameters" | awk '{print $1}')" = "$recipe" ] || reject runtime-settings-recipe
    printf 'DERIVED=1\n'
}
