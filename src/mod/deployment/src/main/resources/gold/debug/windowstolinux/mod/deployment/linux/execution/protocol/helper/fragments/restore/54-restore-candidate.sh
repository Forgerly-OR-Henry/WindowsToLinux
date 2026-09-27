require_restore_token() { [[ "$1" =~ ^[0-9a-f]{32}$ ]] || reject restore-token; }
restore_activation_root() { printf '%s/mutable/restore-activation' "$(candidate_root "$1")"; }
restore_component_root() { printf '%s/%s' "$(restore_activation_root "$1")" "$2"; }
restore_unit_name() { printf 'windowstolinux-restore-%s-%s.service' "$1" "$2"; }
restore_container_name() { printf 'windowstolinux-restore-%s-%s' "$1" "$2"; }
require_restore_member() {
    [[ "$1" =~ ^[A-Za-z0-9._/-]{1,512}$ ]] && [[ "$1" != /* && "$1" != *..* && "$1" != *//* ]] \
        || reject restore-member
}
assert_restore_archive() {
    assert_root_owned_regular "$1"
    /usr/bin/python3 -I - "$1" <<'WTL_RESTORE_ARCHIVE'
import pathlib, sys, tarfile
seen=set(); size=0; count=0
with tarfile.open(sys.argv[1],'r:') as archive:
    for member in archive:
        name=member.name.removeprefix('./').rstrip('/')
        if name in ('','.'):
            if not member.isdir(): raise ValueError('invalid root member')
            continue
        parts=pathlib.PurePosixPath(name).parts
        if name.startswith('/') or '..' in parts or any(ord(c)<32 for c in name) or not (member.isfile() or member.isdir()):
            raise ValueError('invalid restore archive member')
        if name in seen: raise ValueError('duplicate restore archive member')
        seen.add(name); size+=member.size; count+=1
        if count>1000000 or size>68719476736: raise ValueError('restore archive exceeds policy')
WTL_RESTORE_ARCHIVE
}
restore_preflight() {
    [ "$#" -eq 2 ] || reject restore-preflight-arguments
    local app="$1" required="$2" available ports udp_ports
    require_app "$app"
    [[ "$required" =~ ^[0-9]{1,12}$ ]] || reject restore-required-bytes
    available="$(df -PB1 "$base_root" 2>/dev/null | awk 'NR==2 {print $4}')"
    [[ "$available" =~ ^[0-9]+$ ]] || reject restore-space
    ports="$(ss -H -ltn 2>/dev/null | awk '{value=$4; sub(/^.*:/,"",value); if(value~/^[0-9]+$/) print value}' | sort -nu | paste -sd, -)"
    udp_ports="$(ss -H -lun 2>/dev/null | awk '{value=$4; sub(/^.*:/,"",value); if(value~/^[0-9]+$/) print value}' | sort -nu | paste -sd, -)"
    printf 'OCCUPIED_UDP_PORTS=%s\n' "$udp_ports"
    printf 'MANAGED_ROOT_WRITABLE=1\nFOREIGN_CONFLICT=0\nAVAILABLE_BYTES=%s\nOCCUPIED_PORTS=%s\n' "$available" "$ports"
}
restore_prepare_component() {
    [ "$#" -ge 10 ] || reject restore-prepare-arguments
    local candidate="$1" token="$2" component="$3" app="$4" owner="$5" release="$6" release_member="$7"
    shift 7
    require_candidate "${candidate%-*}" "$candidate" 2>/dev/null || [[ "$candidate" =~ ^[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}$ ]] || reject candidate-id
    require_restore_token "$token"
    require_app "$component"
    require_app "$app"
    require_digest "$owner"
    require_digest "$release"
    require_restore_member "$release_member"
    require_count "$1"
    local persistent_count="$1"
    shift
    local -a persistent=()
    local member
    while [ "$persistent_count" -gt 0 ]; do
        [ "$#" -ge 1 ] || reject restore-persistent
        member="$1"
        shift
        require_restore_member "$member"
        persistent+=("$member")
        persistent_count=$((persistent_count - 1))
    done
    [ "$#" -ge 2 ] || reject restore-prepare-arguments
    local oci="$1"
    shift
    [ "$oci" = - ] || require_restore_member "$oci"
    require_count "$1"
    local port_count="$1"
    shift
    local -a ports=()
    local official selected protocol
    while [ "$port_count" -gt 0 ]; do
        [ "$#" -ge 3 ] || reject restore-ports
        protocol="$1"
        official="$2"
        selected="$3"
        shift 3
        case "$protocol" in tcp | udp) ;; *) reject restore-port-protocol ;; esac
        require_service_port "$official"
        require_service_port "$selected"
        [ "$selected" -ge 49152 ] && [ "$official" != "$selected" ] || reject restore-ports
        ports+=("$protocol:$official:$selected")
        port_count=$((port_count - 1))
    done
    [ "$#" -eq 0 ] || reject restore-prepare-arguments
    local candidate_path staged root archive release_root data_path base name
    candidate_path="$(candidate_root "$candidate")"
    assert_candidate_for_deployer "$candidate_path"
    staged="$candidate_path/mutable/restore"
    [ -d "$staged" ] && [ ! -L "$staged" ] || reject restore-staging
    [ "$(stat -c '%U' -- "$staged")" = "$deployer" ] || [ "$(stat -c '%u' -- "$staged")" = 0 ] || reject restore-staging-owner
    chown -R root:root -- "$staged"
    find "$staged" -type d -exec chmod 700 {} +
    find "$staged" -type f -exec chmod 400 {} +
    root="$(restore_component_root "$candidate" "$component")"
    [ ! -e "$root" ] && [ ! -L "$root" ] || reject restore-component-exists
    install -d -o root -g root -m 700 -- "$root/release" "$root/data" "$root/rollback"
    printf '%s\n' "$token" >"$root/activation-token"
    chmod 400 -- "$root/activation-token"
    archive="$staged/$release_member"
    assert_restore_archive "$archive"
    tar --extract --file "$archive" --directory "$root/release" --no-same-owner --no-same-permissions
    chown -R root:root -- "$root/release"
    assert_root_owned_regular "$root/release/.windowstolinux-owner"
    [ "$(cat -- "$root/release/.windowstolinux-owner")" = "$owner" ] || reject restore-owner
    if [ -e "$root/release/.windowstolinux-deployment-parameters" ]; then
        if [ -e "$root/release/.windowstolinux-toolchains" ]; then
            assert_root_owned_regular "$root/release/.windowstolinux-toolchains"
            [ "$(stat -c '%s' -- "$root/release/.windowstolinux-toolchains")" -le 65536 ] || reject restore-toolchain-size
            local previous_binding restored_binding toolchain_result
            previous_binding="$(sha256sum -- "$root/release/.windowstolinux-toolchains" | awk '{print $1}')"
            toolchain_result="$(prepare_official_toolchains restore "$(base64 -w0 -- "$root/release/.windowstolinux-toolchains")")" || reject restore-toolchain-preparation
            restored_binding="$(printf '%s\n' "$toolchain_result" | sed -n 's/^TOOLCHAIN_BINDING=//p')"
            require_digest "$restored_binding"
            if [ "$previous_binding" != "$restored_binding" ]; then
                awk -v old="$previous_binding" -v new="$restored_binding" 'previous == "tools-v1" && $0 == old {$0=new} {print;previous=$0}' \
                    "$root/release/.windowstolinux-deployment-parameters" >"$root/relocated-parameters"
                install -o root -g root -m 444 -- "$root/relocated-parameters" "$root/release/.windowstolinux-deployment-parameters"
                install -o root -g root -m 444 -- "/usr/local/lib/windowstolinux/toolchains/bindings/$restored_binding" "$root/release/.windowstolinux-toolchains"
                rm -f -- "$root/relocated-parameters"
            fi
            prepare_official_toolchains relocate-venv "$root/release" "$restored_binding" || reject restore-python-relocation
        fi
        current_application="$app"
        load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"
        parse_deployment_inputs "${deployment_runtime_parameters[@]}"
        assert_deployment_inputs "$app" systemd
        [ "$runtime_identity_policy" = SYSTEMD_STATIC ] || reject restore-runtime-identity-policy
        printf 'deployment\n' >"$root/kind"
    else
        current_application="$app"
        load_container_parameters "$root/release/.windowstolinux-container-parameters"
        [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ] || reject restore-runtime-identity-policy
        assert_deployment_inputs "$app" container
        printf 'container\n' >"$root/kind"
    fi
    for member in "${persistent[@]}"; do
        archive="$staged/$member"
        assert_restore_archive "$archive"
        base="${member##*/}"
        name="${base%.pax}"
        data_path="$root/data/$name"
        install -d -o root -g root -m 700 -- "$data_path"
        tar --extract --file "$archive" --directory "$data_path" --no-same-owner --no-same-permissions
        chown -R root:root -- "$data_path"
    done
    printf '%s\n' "$app" "$owner" "$release" "$oci" >"$root/identity"
    printf '%s\n' "${ports[@]}" >"$root/ports"
    chown root:root -- "$root/kind" "$root/identity" "$root/ports"
    chmod 400 -- "$root/kind" "$root/identity" "$root/ports"
    printf 'PREPARED=1\nCOMPONENT=%s\n' "$component"
}
restore_candidate_identity() { printf 'restore-%s' "$(printf '%s/%s' "$1" "$2" | sha256sum | cut -c1-24)"; }
restore_view_path() {
    local root="$1" app="$2" path="$3" installation data configuration
    installation="$(app_root "$app")"
    data="$data_root/$app"
    configuration="$configurations_root/$app"
    case "$path" in
        "$installation"/*) printf '%s/view/install/%s' "$root" "${path#"$installation"/}" ;;
        "$data"/*) printf '%s/view/data/%s' "$root" "${path#"$data"/}" ;;
        "$configuration"/*) printf '%s/view/config/%s' "$root" "${path#"$configuration"/}" ;;
        *) reject restore-view-boundary ;;
    esac
}
restore_binding_link() {
    local root="$1" app="$2" wanted="$logical" spec
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        if [ "$storage_kind" = FILE ] && [[ "$wanted" = "$logical/"* ]]; then
            printf '%s/data/%s/%s' "$root" "$binding" "${wanted#"$logical"/}"
            return
        fi
    done
    if [[ "$wanted" = /* ]]; then
        restore_view_path "$root" "$app" "$wanted"
    else printf '%s/release/source%s/%s' "$root" "${application_workdir:+/$application_workdir}" "$wanted"; fi
}
restore_prepare_native_view() {
    local candidate="$1" token="$2" component="$3" root app identity owner spec target source link placeholder directory
    root="$(restore_component_root "$candidate" "$component")"
    mapfile -t saved_identity <"$root/identity"
    app="${saved_identity[0]}"
    identity="$(restore_candidate_identity "$token" "$component")"
    prepare_service_identity "$identity"
    owner="$(service_identity_name "$identity")"
    install -d -o root -g root -m 755 -- "$root/view/install/current" "$root/view/data" "$root/view/config"
    printf '\n[Service]\nBindReadOnlyPaths=%s:%s\nBindReadOnlyPaths=%s:%s\nBindReadOnlyPaths=%s:%s\nBindReadOnlyPaths=%s:%s/current\n' \
        "$root/view/install" "$(app_root "$app")" "$root/view/data" "$data_root/$app" "$root/view/config" "$configurations_root/$app" \
        "$root/release" "$(app_root "$app")" >"$root/storage-unit"
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        target="$(managed_storage_target)"
        directory="$(managed_data_binding_root "$binding")"
        if [ "$storage_kind" = DATABASE ]; then
            assert_root_owned_regular "$(candidate_root "$candidate")/database/sqlite-binding"
            [ "$(cat -- "$(candidate_root "$candidate")/database/sqlite-binding")" = "$(printf '%s\n' "$app" "$binding" "$location_type" "$location_path" "$database_file")" ] || reject restore-database-binding
            source="$(candidate_root "$candidate")/database/$binding"
            assert_sqlite_file "$source/$database_file"
            chown -hR "$owner:$owner" -- "$source"
            chmod -R u+rwX,go-rwx -- "$source"
        elif [ "$storage_kind" = CONFIGURATION ]; then
            source="$root/data/$binding/value"
            assert_root_owned_regular "$source"
            [ "$(sha256sum -- "$source" | awk '{print $1}')" = "$configuration_sha256" ] || reject restore-configuration-digest
            chmod 444 -- "$source"
        else
            source="$root/data/$binding"
            (assert_storage_tree "$source")
            if [ "$mode" = ro ]; then
                chown -hR root:"$owner" -- "$source"
                chmod -R u=rX,g=rX,o= -- "$source"
            else
                chown -hR "$owner:$owner" -- "$source"
                chmod -R u+rwX,go-rwx -- "$source"
            fi
        fi
        if [ "$storage_kind" = CONFIGURATION ]; then
            placeholder="$(restore_view_path "$root" "$app" "$target")"
            install -d -o root -g root -m 755 -- "${placeholder%/*}"
            : >"$placeholder"
            printf 'BindReadOnlyPaths=%s:%s\n' "$source" "$target" >>"$root/storage-unit"
        else
            placeholder="$(restore_view_path "$root" "$app" "$directory")"
            install -d -o root -g root -m 755 -- "$placeholder"
            if [ "$mode" = ro ]; then
                printf 'BindReadOnlyPaths=%s:%s\n' "$source" "$directory"
            else printf 'BindPaths=%s:%s\n' "$source" "$directory"; fi >>"$root/storage-unit"
        fi
        link="$(restore_binding_link "$root" "$app")"
        if [ "$link" != "$(restore_view_path "$root" "$app" "$target")" ]; then
            [ "$(realpath -m -- "${link%/*}")" = "${link%/*}" ] || reject restore-access-link
            if [ ! -d "${link%/*}" ]; then install -d -o root -g root -m 755 -- "${link%/*}"; fi
            if [ -e "$link" ] || [ -L "$link" ]; then rm -rf --one-file-system -- "$link"; fi
            ln -sT -- "$target" "$link"
        fi
    done
    chmod 400 -- "$root/storage-unit"
}
restore_start_container_candidate() {
    local candidate="$1" token="$2" component="$3" root app owner release oci name image spec source destination mode index config secret secret_name secret_destination mount
    local -a args mappings expected_mounts=()
    root="$(restore_component_root "$candidate" "$component")"
    mapfile -t identity <"$root/identity"
    app="${identity[0]}"
    owner="${identity[1]}"
    release="${identity[2]}"
    oci="${identity[3]}"
    current_application="$app"
    load_container_parameters "$root/release/.windowstolinux-container-parameters"
    mapfile -t mappings <"$root/ports"
    if [ -z "${mappings[0]:-}" ]; then
        mappings=()
        if [ "${#application_endpoints[@]}" -gt 0 ]; then assert_root_owned_regular "$root/previous"; fi
    fi
    image="windowstolinux-restore-$token-$component:stage"
    name="$(restore_container_name "$token" "$component")"
    [ "$oci" != - ] || reject restore-image-required
    ! "$container_engine" image inspect "$image" >/dev/null 2>&1 || reject restore-image-collision
    ! "$container_engine" inspect "$name" >/dev/null 2>&1 || reject restore-container-collision
    printf '%s\n' "$image" >"$root/candidate-image"
    chmod 400 -- "$root/candidate-image"
    import_restore_image "$container_engine" "$(candidate_root "$candidate")/mutable/restore/$oci" "$image"
    local runtime_user
    runtime_user="$(container_nonroot_user "$container_engine" "$image")"
    local container_storage_view_root="$root/container-view" container_candidate_files="$root/data"
    local -a container_fresh_files=()
    local container_storage_image="$image"
    prepare_container_database_links "$image" "$root"
    container_storage_mounts "$image"
    config="$(configuration_path "$app" "$deployment_configuration_digest" container)"
    args=("$container_engine" create --name "$name" --restart no --user "$runtime_user" --security-opt no-new-privileges --cap-drop ALL
        --read-only --tmpfs /tmp:rw,nosuid,nodev,mode=1777 --tmpfs /run:rw,nosuid,nodev,mode=755 --label "io.windowstolinux.restore=$token" --env-file "$config")
    index=0
    for spec in "${container_ports[@]}"; do
        local protocol="${spec##*/}" mapping="${spec%/*}" host target selected candidate_mapping found=0
        target="${mapping##*:}"
        mapping="${mapping%:*}"
        host="${mapping##*:}"
        selected="$host"
        for candidate_mapping in "${mappings[@]}"; do
            if [[ "$candidate_mapping" = "$protocol:$host:"* ]]; then
                selected="${candidate_mapping##*:}"
                found=1
                break
            fi
        done
        [ "${#mappings[@]}" = 0 ] || [ "$found" = 1 ] || reject restore-port-mapping
        args+=(--publish "127.0.0.1:$selected:$target/$protocol")
        index=$((index + 1))
    done
    [ "${#mappings[@]}" = 0 ] || [ "$index" -eq "${#mappings[@]}" ] || reject restore-port-count
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        mount="$(container_binding_mount "$image")"
        destination="${mount#*:}"
        destination="${destination%:*}"
        if [ "$storage_kind" = DATABASE ]; then
            assert_root_owned_regular "$(candidate_root "$candidate")/database/sqlite-binding"
            [ "$(cat -- "$(candidate_root "$candidate")/database/sqlite-binding")" = "$(printf '%s\n' "$app" "$binding" "$location_type" "$location_path" "$database_file")" ] || reject restore-database-binding
            source="$(candidate_root "$candidate")/database/$binding"
            assert_sqlite_file "$source/$database_file"
            printf '%s\n' "$runtime_user" >"$(candidate_root "$candidate")/database/sqlite-owner"
            chmod 400 -- "$(candidate_root "$candidate")/database/sqlite-owner"
        elif [ "$storage_kind" = CONFIGURATION ]; then
            source="$root/data/$binding/value"
            assert_root_owned_regular "$source"
            [ "$(sha256sum -- "$source" | awk '{print $1}')" = "$configuration_sha256" ] || reject restore-configuration-digest
        else
            source="$root/data/$binding"
            (assert_storage_tree "$source")
        fi
        if [ "$mode" = ro ]; then
            chown -hR root:"${runtime_user#*:}" -- "$source"
            chmod -R u=rX,g=rX,o= -- "$source"
        else
            chown -hR "$runtime_user" -- "$source"
            chmod -R u+rwX,go-rwx -- "$source"
        fi
        args+=(--volume "$source:$destination:$mode,Z")
        expected_mounts+=("$source:$destination:$mode")
    done
    for mount in "${container_bind_mounts[@]}"; do
        source="${mount%%:*}"
        if [[ "$source" = "$container_storage_view_root/"* ]]; then
            args+=(--volume "$mount,Z")
            expected_mounts+=("$mount")
        fi
    done
    index=0
    while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
        secret_name="${deployment_secret_names[$index]}"
        secret_destination="/run/secrets/$secret_name"
        secret="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
        secret="$(container_secret_delivery "$app" "$release" "$runtime_user" "$secret" "$secret_name")"
        args+=(--mount "type=bind,source=$secret,destination=$secret_destination,readonly" --env "$secret_name=$secret_destination")
        expected_mounts+=("$secret:$secret_destination:ro")
        index=$((index + 1))
    done
    application_backend=container
    application_release="$root/release"
    # Candidate image identity is separate; permission checks use the explicit numeric image user.
    local container_input_user="$runtime_user"
    application_assert_inputs "$app"
    if [ "$application_mode" = ON_DEMAND ]; then
        application_entry="$application_verify_entry"
        application_args=("${application_verify_args[@]}")
    fi
    application_container_options "$image"
    args+=("${application_container_arguments[@]}" "$image" "${application_container_command[@]}")
    for index in "${!application_input_sources[@]}"; do expected_mounts+=("${application_input_sources[$index]}:${application_input_targets[$index]}:ro"); done
    "${args[@]}" >/dev/null
    application_assert_exact_mounts "$name" "${expected_mounts[@]}"
    "$container_engine" start "$name" >/dev/null
    printf 'STARTED=1\nCONTAINER=%s\n' "$name"
}
restore_start_candidate() {
    [ "$#" -eq 3 ] || reject restore-start-arguments
    local candidate="$1" token="$2" component="$3" root kind
    require_restore_token "$token"
    require_app "$component"
    root="$(restore_component_root "$candidate" "$component")"
    assert_root_owned_regular "$root/kind"
    kind="$(cat -- "$root/kind")"
    if [ "$kind" = deployment ]; then restore_start_deployment_candidate "$@"; else restore_start_container_candidate "$@"; fi
}
restore_stop_candidate() {
    [ "$#" -eq 3 ] || reject restore-stop-arguments
    local candidate="$1" token="$2" component="$3" root kind name state path image volume
    require_restore_token "$token"
    require_app "$component"
    root="$(restore_component_root "$candidate" "$component")"
    kind="$(cat -- "$root/kind")"
    if [ "$kind" = deployment ]; then
        name="$(restore_unit_name "$token" "$component")"
        state="$(systemctl show --value --property LoadState "$name" 2>/dev/null || true)"
        if [ "$state" = loaded ]; then systemctl stop "$name" || reject restore-stop-failed; fi
        state="$(systemctl show --value --property ActiveState "$name" 2>/dev/null || true)"
        case "$state" in inactive | failed | '') ;; *) reject restore-runtime-active ;; esac
        [ "$(systemctl show --value --property MainPID "$name" 2>/dev/null || printf 0)" = 0 ] || reject restore-candidate-process-remains
        local group processes
        group="$(systemctl show --value --property ControlGroup "$name" 2>/dev/null)" || reject restore-candidate-observation
        if [ -n "$group" ] && [ -d "/sys/fs/cgroup$group" ]; then
            processes="$(find "/sys/fs/cgroup$group" -name cgroup.procs -exec cat {} +)" || reject restore-candidate-observation
            [ -z "$processes" ] || reject restore-candidate-process-remains
        fi
        rm -f -- "/run/systemd/system/$name"
        systemctl daemon-reload

    else
        mapfile -t identity <"$root/identity"
        current_application="${identity[0]}"
        load_container_parameters "$root/release/.windowstolinux-container-parameters"
        name="$(restore_container_name "$token" "$component")"
        if "$container_engine" inspect "$name" >/dev/null 2>&1; then
            [ "$("$container_engine" inspect --format '{{ index .Config.Labels "io.windowstolinux.restore" }}' "$name")" = "$token" ] || reject restore-container-owner
            "$container_engine" rm -f "$name" >/dev/null || reject restore-container-stop
        fi
        image="windowstolinux-restore-$token-$component:stage"
        if [ -e "$root/candidate-image" ]; then
            assert_root_owned_regular "$root/candidate-image"
            [ "$(cat -- "$root/candidate-image")" = "$image" ] || reject restore-image-record
            if "$container_engine" image inspect "$image" >/dev/null 2>&1; then "$container_engine" image rm "$image" >/dev/null || reject restore-image-cleanup; fi
            rm -f -- "$root/candidate-image"
        fi
    fi
    printf 'STOPPED=1\n'
}
