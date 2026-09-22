parse_container_parameters() {
    [ "$#" -ge 3 ] || reject container-arguments
    container_engine="$1"
    shift
    case "$container_engine" in docker | podman) ;; *) reject container-engine ;; esac
    require_count "$1"
    local port_count="$1"
    shift
    container_ports=()
    while [ "$port_count" -gt 0 ]; do
        [ "$#" -ge 4 ] || reject container-ports
        case "$1" in tcp | udp) ;; *) reject container-protocol ;; esac
        python3 -c 'import ipaddress,sys; ipaddress.ip_address(sys.argv[1])' "$2" || reject container-address
        [[ "$3" =~ ^[0-9]{1,5}$ && "$4" =~ ^[0-9]{1,5}$ ]] && [ "$3" -ge 1 ] && [ "$3" -le 65535 ] && [ "$4" -ge 1 ] && [ "$4" -le 65535 ] || reject container-port
        local address="$2"
        [[ "$address" != *:* ]] || address="[$address]"
        container_ports+=("$address:$3:$4/$1")
        shift 4
        port_count=$((port_count - 1))
    done
    local endpoint id protocol address host target exposure expected found=0
    [ "${#container_ports[@]}" = "${#application_endpoints[@]}" ] || reject container-endpoint-count
    for endpoint in "${application_endpoints[@]}"; do
        IFS='|' read -r id protocol address host target exposure <<<"$endpoint"
        if [ "$protocol" = UDP ]; then protocol=udp; else protocol=tcp; fi
        [[ "$address" != *:* ]] || address="[$address]"
        expected="$address:$host:$target/$protocol"
        found=0
        for spec in "${container_ports[@]}"; do [ "$spec" != "$expected" ] || found=$((found + 1)); done
        [ "$found" = 1 ] || reject container-endpoint-mismatch
    done
    require_count "$1"
    local volume_count="$1"
    shift
    container_volumes=()
    while [ "$volume_count" -gt 0 ]; do
        [ "$#" -ge 3 ] || reject container-volumes
        [[ "$1" =~ ^windowstolinux-[a-z0-9][a-z0-9-]{0,62}$ ]] || reject container-volume
        [[ "$2" =~ ^/[A-Za-z0-9._/-]{1,255}$ ]] && [ "$2" != / ] && [[ "$2" != *..* && "$2" != *//* ]] || reject container-volume-path
        [ "$3" = 0 ] || [ "$3" = 1 ] || reject container-volume-mode
        container_volumes+=("$1:$2:$3")
        shift 3
        volume_count=$((volume_count - 1))
    done
    [ "$#" -eq 0 ] || reject container-arguments
    local volume spec id destination readonly found
    for volume in "${container_volumes[@]}"; do
        id="${volume%%:*}"
        id="${id#windowstolinux-}"
        destination="${volume#*:}"
        readonly="${destination##*:}"
        destination="${destination%:*}"
        found=0
        for spec in "${managed_data_bindings[@]}"; do
            managed_binding_parts "$spec"
            [ "$binding" = "$id" ] || continue
            [ "$storage_kind" = FILE ] && [ "$logical" = "$destination" ] || reject container-storage-declaration
            if [ "$readonly" = 1 ]; then
                [ "$mode" = ro ] || reject container-storage-declaration
            else [ "$mode" = rw ] || reject container-storage-declaration; fi
            found=1
        done
        [ "$found" = 1 ] || reject container-storage-declaration
    done
}
save_container_parameters() {
    local target="$1" spec source destination mode
    {
        if [ "$runtime_identity_policy" != LEGACY_UNSPECIFIED ]; then printf 'identity-v2\n%s\n%s\n' "$runtime_identity_policy" "$application_payload"; fi
        printf '%s\n' "$deployment_configuration_digest"
        printf '%s\n' "${#deployment_secret_identifiers[@]}"
        local index=0
        while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
            printf '%s\n' "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}" "${deployment_secret_digests[$index]}"
            index=$((index + 1))
        done
        printf '%s\n' "$managed_data_application" "$managed_data_component" "${#managed_data_bindings[@]}"
        for spec in "${managed_data_bindings[@]}"; do
            managed_binding_parts "$spec"
            printf '%s\n' "$binding" "$logical" "$mode" "$storage_kind" "$location_type" "$location_path" "$database_file" "$seed_file" "$initialization_files" "$configuration_sha256"
        done
        printf '%s\n' "$container_engine"
        printf '%s\n' "${#container_ports[@]}"
        for spec in "${container_ports[@]}"; do
            local protocol="${spec##*/}" mapping="${spec%/*}" target="${spec%/*}" host address
            target="${target##*:}"
            mapping="${mapping%:*}"
            host="${mapping##*:}"
            address="${mapping%:*}"
            address="${address#[}"
            address="${address%]}"
            printf '%s\n' "$protocol" "$address" "$host" "$target"
        done
        printf '%s\n' "${#container_volumes[@]}"
        for spec in "${container_volumes[@]}"; do
            source="${spec%%:*}"
            destination="${spec#*:}"
            destination="${destination%:*}"
            mode="${spec##*:}"
            printf '%s\n' "$source" "$destination" "$mode"
        done
    } >"$target"
    chown root:root -- "$target"
    chmod 444 -- "$target"
}
load_container_parameters() {
    local source="$1"
    local -a saved
    assert_root_owned_regular "$source"
    mapfile -t saved <"$source"
    parse_deployment_inputs "${saved[@]}"
    parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
    parse_container_parameters "${managed_data_remaining_arguments[@]}"
}
container_name() { printf 'windowstolinux-%s' "$1"; }
container_image() { printf 'windowstolinux-%s:%s' "$1" "$2"; }
container_current_release() {
    local app="$1" manifest="$2"
    current_application="$app"
    local root releases current current_digest found runtime_image
    root="$(app_root "$app")"
    releases="$root/releases"
    previous_present=0
    previous_path=
    previous_running=0
    if [ -e "$root/current" ] || [ -L "$root/current" ]; then
        [ -L "$root/current" ] || reject current-not-link
        current="$(readlink -f -- "$root/current")"
        case "$current" in "$releases"/[0-9a-f]*) ;; *) reject current-path ;; esac
        current_digest="${current##*/}"
        require_digest "$current_digest"
        assert_root_owned_directory "$current"
        assert_root_owned_regular "$current/.windowstolinux-owner"
        [ "$(cat -- "$current/.windowstolinux-owner")" = "$manifest" ] || reject current-owner
        assert_root_owned_regular "$current/.windowstolinux-container-engine"
        assert_root_owned_regular "$current/.windowstolinux-container-parameters"
        assert_root_owned_regular "$current/.windowstolinux-container-image-id"
        load_container_parameters "$current/.windowstolinux-container-parameters"
        assert_deployment_inputs "$app" container
        [ "$(cat -- "$current/.windowstolinux-container-engine")" = "$container_engine" ] || reject container-engine-change
        [ "$("$container_engine" image inspect --format '{{.Id}}' "$(container_image "$app" "$current_digest")")" \
            = "$(cat -- "$current/.windowstolinux-container-image-id")" ] || reject container-image-identity
        found="$("$container_engine" ps --all --filter "name=$(container_name "$app")" --format '{{.Names}}')" || reject container-query
        if printf '%s\n' "$found" | grep -Fxq -- "$(container_name "$app")"; then
            runtime_image="$("$container_engine" inspect --format '{{.Image}}' "$(container_name "$app")")" || reject container-query
            [ "$runtime_image" = "$(cat -- "$current/.windowstolinux-container-image-id")" ] || reject current-container-identity
            assert_container_ports "$(container_name "$app")"
            assert_container_bind_mounts "$(container_image "$app" "$current_digest")" "$(container_name "$app")"
        fi
        previous_present=1
        previous_path="$current"
        if "$container_engine" inspect --format '{{.State.Running}}' "$(container_name "$app")" 2>/dev/null | grep -qx true; then previous_running=1; fi
    fi
}
start_container_release() {
    local app="$1" release_digest="$2" manifest="$3"
    local image name volume spec source destination mode config index secret secret_name secret_destination
    local -a args identity_options=()
    local runtime_user=
    image="$(container_image "$app" "$release_digest")"
    name="$(container_name "$app")"
    if [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ]; then
        runtime_user="$(container_nonroot_user "$container_engine" "$image")"
        identity_options=(--user "$runtime_user" --security-opt no-new-privileges --cap-drop ALL --read-only --tmpfs /tmp:rw,nosuid,nodev,mode=1777 --tmpfs /run:rw,nosuid,nodev,mode=755)
    else reject container-identity-policy; fi
    assert_deployment_inputs "$app" container
    stop_container_runtime "$app"
    prepare_container_storage "$app" "$image" "$(app_root "$app")/releases/$release_digest" "$runtime_user"
    container_storage_mounts "$image"
    application_backend=container
    application_release="$(app_root "$app")/releases/$release_digest"
    application_assert_inputs "$app"
    application_container_options "$image"
    if [ "$application_mode" = ON_DEMAND ]; then
        if "$container_engine" inspect "$name" >/dev/null 2>&1; then "$container_engine" rm "$name" >/dev/null || reject application-container-cleanup; fi
        if [ "$container_engine" = podman ]; then
            local quadlet="$(podman_quadlet_path "$app")"
            set_podman_quadlet_autostart "$app" 0
            if [ -e "$quadlet" ]; then
                assert_root_owned_regular "$quadlet"
                rm -f -- "$quadlet"
            fi
            systemctl daemon-reload
        fi
        return
    fi
    config="$(configuration_path "$app" "$deployment_configuration_digest" container)"
    if [ "$container_engine" = docker ]; then
        "$container_engine" rm -f -- "$name" >/dev/null 2>&1 || true
        args=("$container_engine" create --name "$name" --restart unless-stopped "${identity_options[@]}" "${application_container_arguments[@]}" --env-file "$config")
        for spec in "${container_ports[@]}"; do args+=(--publish "$spec"); done
        for spec in "${container_bind_mounts[@]}"; do args+=(--volume "$spec,Z"); done
        index=0
        while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
            secret_name="${deployment_secret_names[$index]}"
            secret_destination="/run/secrets/$secret_name"
            secret="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
            if [ -n "$runtime_user" ]; then secret="$(container_secret_delivery "$app" "$release_digest" "$runtime_user" "$secret" "$secret_name")"; fi
            args+=(--mount "type=bind,source=$secret,destination=$secret_destination,readonly" --env "$secret_name=$secret_destination")
            index=$((index + 1))
        done
        args+=("$image" "${application_container_command[@]}")
        "${args[@]}" >/dev/null
        assert_container_bind_mounts "$image" "$name"
        assert_container_ports "$name"
        "$container_engine" start "$name" >/dev/null
    else
        local quadlet tmp unit
        quadlet="$(podman_quadlet_path "$app")"
        unit="windowstolinux-$app.service"
        install -d -o root -g root -m 755 -- /etc/containers/systemd
        tmp="$(mktemp /etc/containers/systemd/.windowstolinux-managed.XXXXXX)"
        trap 'rm -f -- "$tmp"' EXIT
        {
            printf '[Container]\nImage=%s\nContainerName=%s\n' "$image" "$name"
            printf 'EnvironmentFile=%s\n' "$config"
            if [ -n "$application_workdir" ]; then printf 'WorkingDir=%s\n' "$(application_systemd_escape "$(container_binding_destination "$image" "$application_workdir")")"; fi
            printf 'Entrypoint=%s\n' "$(application_systemd_escape "$application_container_entry")"
            if [ "${#application_container_command[@]}" -gt 0 ]; then
                printf 'Exec='
                for spec in "${application_container_command[@]}"; do
                    application_systemd_escape "$spec"
                    printf ' '
                done
                printf '\n'
            fi
            index=0
            while [ "$index" -lt "${#application_input_sources[@]}" ]; do
                printf 'Volume=%s\n' "$(application_systemd_escape "${application_input_sources[$index]}:${application_input_targets[$index]}:ro")"
                index=$((index + 1))
            done
            if [ -n "$runtime_user" ]; then printf 'User=%s\nNoNewPrivileges=true\nDropCapability=all\n' "$runtime_user"; fi
            for spec in "${container_ports[@]}"; do printf 'PublishPort=%s\n' "$spec"; done
            printf 'ReadOnly=true\nTmpfs=/tmp:rw,nosuid,nodev,mode=1777\nTmpfs=/run:rw,nosuid,nodev,mode=755\n'
            for spec in "${container_bind_mounts[@]}"; do printf 'Volume=%s,Z\n' "$spec"; done
            index=0
            while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
                secret_name="${deployment_secret_names[$index]}"
                secret_destination="/run/secrets/$secret_name"
                secret="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
                if [ -n "$runtime_user" ]; then secret="$(container_secret_delivery "$app" "$release_digest" "$runtime_user" "$secret" "$secret_name")"; fi
                printf 'Volume=%s:%s:ro\nEnvironment=%s=%s\n' "$secret" "$secret_destination" "$secret_name" "$secret_destination"
                index=$((index + 1))
            done
            printf '\n[Service]\nExecStartPost=/usr/local/lib/windowstolinux/managed-helper podman-cni-forward %s %s\n' "$app" "$manifest"
            printf 'ExecStopPost=/usr/local/lib/windowstolinux/managed-helper podman-cni-clear %s %s\n' "$app" "$manifest"
        } >"$tmp"
        install -o root -g root -m 644 -- "$tmp" "$quadlet"
        rm -f -- "$tmp"
        trap - EXIT
        set_podman_quadlet_autostart "$app" 1
        systemctl restart "$unit"
    fi
    assert_container_bind_mounts "$image" "$name"
    assert_container_ports "$name"
}
publish_container() {
    [ "$#" -ge 7 ] || reject publish-container-arguments
    local app="$1" candidate_id="$2" release_digest="$3" manifest="$4"
    current_application="$app"
    shift 4
    require_app "$app"
    require_candidate "$app" "$candidate_id"
    require_digest "$release_digest"
    require_digest "$manifest"
    initialise_controlled_roots
    local root releases release candidate source candidate_image image
    root="$(app_root "$app")"
    releases="$root/releases"
    release="$releases/$release_digest"
    candidate="$(candidate_root "$candidate_id")"
    container_current_release "$app" "$manifest"
    parse_deployment_inputs "$@"
    parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
    parse_container_parameters "${managed_data_remaining_arguments[@]}"
    [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ] || reject container-identity-policy
    if [ "$previous_present" -eq 0 ]; then
        local existing
        existing="$("$container_engine" ps --all --filter "name=$(container_name "$app")" --format '{{.Names}}')" || reject container-query
        ! printf '%s\n' "$existing" | grep -Fxq -- "$(container_name "$app")" || reject unmanaged-container
    fi
    if [ "$previous_present" = 1 ] && [ "$previous_path" = "$release" ]; then
        [ "$(printf '%s\n' "$@" | sha256sum | awk '{print $1}')" = "$(sha256sum -- "$release/.windowstolinux-container-parameters" | awk '{print $1}')" ] || reject release-parameters-changed
        if [ "$application_mode" = DAEMON ] && [ "$previous_running" = 0 ]; then start_container_release "$app" "$release_digest" "$manifest"; fi
        printf 'PUBLISHED=1\n'
        return
    fi
    [ ! -e "$release" ] && [ ! -L "$release" ] || reject release-exists
    assert_candidate_for_deployer "$candidate"
    source="$candidate/mutable/source"
    [ -d "$source" ] && [ ! -L "$source" ] || reject source-directory
    [ -z "$(find -P "$source" -xdev -type l -print -quit)" ] || reject source-symlink
    [ -z "$(find -P "$source" -xdev ! -type f ! -type d -print -quit)" ] || reject source-special-file
    [ -z "$(find -P "$source" -xdev -type f -links +1 -print -quit)" ] || reject source-hardlink
    candidate_image="$(candidate_image_name "$container_engine" "$candidate_id")"
    image="$(container_image "$app" "$release_digest")"
    import_candidate_image "$app" "$candidate_id" "$container_engine"
    "$container_engine" image inspect "$candidate_image" >/dev/null
    [ "$("$container_engine" image inspect --format '{{ index .Config.Labels "io.windowstolinux.application" }}' "$candidate_image")" = "$app" ] \
        && [ "$("$container_engine" image inspect --format '{{ index .Config.Labels "io.windowstolinux.candidate" }}' "$candidate_image")" = "$candidate_id" ] \
        || reject container-image-owner
    "$container_engine" tag "$candidate_image" "$image"
    install -d -o root -g root -m 755 -- "$root" "$releases" "$release"
    copy_sealed_source "$source" "$release/source"
    printf '%s\n' "$manifest" >"$release/.windowstolinux-owner"
    printf '%s\n' "$container_engine" >"$release/.windowstolinux-container-engine"
    "$container_engine" image inspect --format '{{.Id}}' "$image" >"$release/.windowstolinux-container-image-id"
    chown root:root -- "$release/.windowstolinux-owner" "$release/.windowstolinux-container-engine" \
        "$release/.windowstolinux-container-image-id"
    chmod 444 -- "$release/.windowstolinux-owner" "$release/.windowstolinux-container-engine" \
        "$release/.windowstolinux-container-image-id"
    save_container_parameters "$release/.windowstolinux-container-parameters"
    ln -sfnT -- "$release" "$root/current"
    start_container_release "$app" "$release_digest" "$manifest"
    printf 'PUBLISHED=1\n'
}
