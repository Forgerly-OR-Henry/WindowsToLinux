container_binding_destination() {
    local image="$1" working path="${2:-${logical:-}}"
    if [[ "$path" != /* ]]; then
        working="$("$container_engine" image inspect --format '{{.Config.WorkingDir}}' "$image")"
        [ -n "$working" ] || working=/
        if [ "$#" = 1 ] && [ -n "${application_workdir:-}" ]; then working="${working%/}/$application_workdir"; fi
        path="${working%/}/$path"
    fi
    require_storage_path "$path"
    [[ "$path" = /* ]] || reject container-storage-access
    case "$path" in /proc | /proc/* | /sys | /sys/* | /dev | /dev/* | /run/secrets | /run/secrets/*) reject container-storage-reserved ;; esac
    printf '%s' "$path"
}
container_binding_mount() {
    local image="$1" destination source
    destination="$(container_binding_destination "$image")"
    source="$(managed_storage_target)"
    if [ "$storage_kind" = DATABASE ]; then
        [ "${destination##*/}" = "$database_file" ] || reject container-database-file-name
        source="${source%/*}"
        destination="/run/windowstolinux/databases/$binding"
    fi
    printf '%s:%s:%s' "$source" "$destination" "$mode"
}
container_view_root() {
    printf '%s' "${container_storage_view_root:-$(app_root "$current_application")/releases/${1##*:}/.container-storage-view}"
}
container_database_link() (
    local image="$1" spec wanted id="$binding" parent
    wanted="$(container_binding_destination "$image")"
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        [ "$storage_kind" = FILE ] || continue
        parent="$(container_binding_destination "$image")"
        if [[ "$wanted" = "$parent/"* ]]; then
            local source="$(managed_data_binding_root "$binding")"
            if [ -n "${container_candidate_files:-}" ]; then source="$container_candidate_files/$binding"; fi
            printf '%s/%s' "$source" "${wanted#"$parent"/}"
            return
        fi
    done
    local view_id="path-$(printf %s "${wanted%/*}" | sha256sum | cut -c1-16)"
    printf '%s/%s/%s' "$(container_view_root "$image")" "$view_id" "${wanted##*/}"
)
container_storage_mounts() {
    local image="$1" spec mount previous destination other link view parent
    container_storage_image="$image"
    container_bind_mounts=()
    local resolved saved prior_kind
    local -a resolved_access=()
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        resolved="$(container_binding_destination "$image")"
        for saved in "${resolved_access[@]}"; do
            prior_kind="${saved%%:*}"
            other="${saved#*:}"
            [ "$resolved" != "$other" ] || reject container-storage-access-overlap
            if [ "$storage_kind" != DATABASE ] && [ "$prior_kind" != DATABASE ]; then
                case "$resolved/" in "$other/"*) reject container-storage-access-overlap ;; esac
                case "$other/" in "$resolved/"*) reject container-storage-access-overlap ;; esac
            fi
        done
        resolved_access+=("$storage_kind:$resolved")
    done
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        mount="$(container_binding_mount "$image")"
        destination="${mount#*:}"
        destination="${destination%:*}"
        for previous in "${container_bind_mounts[@]}"; do
            other="${previous#*:}"
            other="${other%:*}"
            [ "$destination" != "$other" ] || reject container-storage-overlap
        done
        container_bind_mounts+=("$mount")
        if [ "$storage_kind" = DATABASE ]; then
            link="$(container_database_link "$image")"
            view="$(container_view_root "$image")"
            if [[ "$link" = "$view/"* ]]; then
                parent="$(container_binding_destination "$image")"
                parent="${parent%/*}"
                [ -n "$parent" ] && [ "$parent" != / ] || reject container-database-root-access
                local view_mount="${link%/*}:$parent:ro" present=0
                for previous in "${container_bind_mounts[@]}"; do [ "$previous" != "$view_mount" ] || present=1; done
                if [ "$present" = 0 ]; then container_bind_mounts+=("$view_mount"); fi
            fi
        fi
    done
}
extract_container_storage_seed() {
    /usr/bin/python3 -I - "$@" <<'WTL_STORAGE_SEED'
import os, pathlib, shutil, sys, tarfile
archive, destination, output, kind, filename = sys.argv[1:]
prefix = destination.strip('/')
root = pathlib.Path(output)
total = count = 0
seen = set()
links = []
with tarfile.open(archive, 'r:') as source:
    for member in source:
        name = member.name.removeprefix('./').rstrip('/')
        if not (name == prefix or name.startswith(prefix + '/')):
            continue
        relative = name[len(prefix):].lstrip('/')
        if not relative:
            if not member.isdir(): raise ValueError('image storage path is not a directory')
            continue
        parts = pathlib.PurePosixPath(relative).parts
        if any(part in ('..', '.') for part in parts) or relative.startswith('/'):
            raise ValueError('image seed path escaped')
        if member.islnk() or not (member.isfile() or member.isdir() or kind == 'VIEW' and member.issym()):
            raise ValueError('image seed contains links or special files')
        if kind == 'DATABASE' and relative not in (filename,filename+'-wal',filename+'-shm',filename+'-journal'):
            continue
        if relative in seen: raise ValueError('duplicate image seed member')
        seen.add(relative); count += 1; total += member.size
        if count > 100000 or total > 2147483648: raise ValueError('image seed exceeds policy')
        target = root.joinpath(*parts)
        if member.issym():
            if len(member.linkname) > 512 or any(ord(c)<32 for c in member.linkname): raise ValueError('invalid image link')
            links.append((target,member.linkname)); continue
        target.parent.mkdir(parents=True, exist_ok=True)
        if member.isdir(): target.mkdir(exist_ok=True)
        else:
            with source.extractfile(member) as data, target.open('xb') as output_file:
                shutil.copyfileobj(data, output_file, 65536)
            os.chmod(target, 0o700 if member.mode & 0o111 else 0o600)
# Install links only after all normal members, never write through image-provided links.
for target, link in links:
    target.parent.mkdir(parents=True, exist_ok=True)
    os.symlink(link,target)
WTL_STORAGE_SEED
}
container_storage_needs_image_seed() {
    [ "$seed_file" = - ] || return 1
    local target="$1"
    case "$storage_kind" in
        DATABASE) target="$target/$database_file" ;;
        FILE) ;;
        *) return 1 ;;
    esac
    [ ! -e "$target" ] && [ ! -L "$target" ]
}
prepare_container_storage() (
    local app="$1" image="$2" release="$3" user="$4" spec directory destination temporary cid= source index=0 mount
    current_application="$app"
    container_storage_mounts "$image"
    temporary="$(mktemp -d "$work_root/.container-storage.XXXXXX")"
    trap 'if [ -n "$cid" ]; then "$container_engine" rm -v -f "$cid" >/dev/null || exit 1; fi; rm -rf --one-file-system -- "$temporary"' EXIT
    local -a prepared=() container_fresh_files=()
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        directory="$(managed_data_binding_root "$binding")"
        if [ ! -e "$directory" ] && [ "$storage_kind" = FILE ]; then container_fresh_files+=("$directory"); fi
        if container_storage_needs_image_seed "$directory"; then
            if [ -z "$cid" ]; then
                cid="$("$container_engine" create --network none --entrypoint /nonexistent "$image")"
                [[ "$cid" =~ ^[a-f0-9]{12,64}$ ]] || reject container-seed-container
                (
                    ulimit -f 8388608
                    "$container_engine" export --output "$temporary/image.tar" "$cid"
                ) || reject container-seed-export
            fi
            destination="$(container_binding_destination "$image")"
            if [ "$storage_kind" = DATABASE ]; then destination="${destination%/*}"; fi
            source="$release/source/.w2l/image-seeds/$binding"
            install -d -o root -g root -m 755 -- "$source"
            extract_container_storage_seed "$temporary/image.tar" "$destination" "$source" "$storage_kind" "$database_file" || reject container-seed-invalid
            if [ "$storage_kind" = DATABASE ]; then
                if [ -e "$source/$database_file" ]; then seed_file=".w2l/image-seeds/$binding/$database_file"; fi
            else seed_file=".w2l/image-seeds/$binding"; fi
        fi
        prepared+=("$binding:$logical:$mode:$storage_kind:$location_type:$location_path:$database_file:$seed_file:$initialization_files:$configuration_sha256")
    done
    managed_data_bindings=("${prepared[@]}")
    prepare_managed_data_bindings "$release/source"
    prepare_container_database_links "$image" "$temporary"
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        directory="$(managed_data_binding_root "$binding")"
        if [ "$storage_kind" != CONFIGURATION ]; then
            (assert_storage_tree "$directory")
            if [ "$mode" = ro ]; then
                chown -hR root:"${user#*:}" -- "$directory"
                chmod -R u=rX,g=rX,o= -- "$directory"
            else
                chown -hR "$user" -- "$directory"
                chmod -R u+rwX,go-rwx -- "$directory"
            fi
        fi
    done
)
assert_container_bind_mounts() {
    local image="$1" name="$2" mount source destination mode found
    container_storage_mounts "$image"
    local spec link
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        [ "$storage_kind" = DATABASE ] || continue
        link="$(container_database_link "$image")"
        [ -L "$link" ] && [ "$(readlink -- "$link")" = "/run/windowstolinux/databases/$binding/$database_file" ] || reject container-database-alias-changed
    done
    [ "$("$container_engine" inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$name")" = true ] || reject container-root-writable
    local actual type row allowed index expected_secret
    actual="$("$container_engine" inspect --format '{{range .Mounts}}{{printf "%s:%s:%s:%t\n" .Type .Source .Destination .RW}}{{end}}' "$name")"
    while IFS=: read -r type source destination mode; do
        [ -n "$type" ] || continue
        if [ "$type" = tmpfs ] && { [ "$destination" = /tmp ] || [ "$destination" = /run ]; }; then continue; fi
        [ "$type" = bind ] || reject container-undeclared-volume
        allowed=0
        for mount in "${container_bind_mounts[@]}"; do
            row="${mount%:*}"
            if [ "${mount##*:}" = ro ]; then row="$row:false"; else row="$row:true"; fi
            [ "$source:$destination:$mode" != "$row" ] || allowed=1
        done
        index=0
        while [ "$index" -lt "${#deployment_secret_names[@]}" ]; do
            expected_secret="$configurations_root/$current_application/.container-credentials/${image##*:}/${deployment_secret_names[$index]}"
            [ "$source:$destination:$mode" != "$expected_secret:/run/secrets/${deployment_secret_names[$index]}:false" ] || allowed=1
            index=$((index + 1))
        done
        index=0
        while [ "$index" -lt "${#application_input_sources[@]}" ]; do
            [ "$source:$destination:$mode" != "${application_input_sources[$index]}:${application_input_targets[$index]}:false" ] || allowed=1
            index=$((index + 1))
        done
        [ "$allowed" = 1 ] || reject container-undeclared-mount
    done <<<"$actual"
    index=0
    while [ "$index" -lt "${#application_input_sources[@]}" ]; do
        printf '%s\n' "$actual" | grep -Fxq "bind:${application_input_sources[$index]}:${application_input_targets[$index]}:false" || reject container-external-input-mount
        index=$((index + 1))
    done
    for mount in "${container_bind_mounts[@]}"; do
        source="${mount%%:*}"
        destination="${mount#*:}"
        mode="${destination##*:}"
        destination="${destination%:*}"
        found="$("$container_engine" inspect --format '{{range .Mounts}}{{printf "%s:%s:%t\n" .Source .Destination .RW}}{{end}}' "$name")"
        if [ "$mode" = ro ]; then
            printf '%s\n' "$found" | grep -Fxq "$source:$destination:false" || reject container-storage-mount
        else printf '%s\n' "$found" | grep -Fxq "$source:$destination:true" || reject container-storage-mount; fi
    done
}

prepare_container_database_links() (
    local image="$1" temporary="$2" cid="${3:-}" spec link destination expected view source
    trap 'if [ -n "$cid" ]; then "$container_engine" rm -v -f "$cid" >/dev/null || exit 1; fi' EXIT
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        [ "$storage_kind" = DATABASE ] || continue
        link="$(container_database_link "$image")"
        expected="/run/windowstolinux/databases/$binding/$database_file"
        view="$(container_view_root "$image")"
        if [ -L "$link" ]; then
            [ "$(readlink -- "$link")" = "$expected" ] || reject container-database-alias
            continue
        fi
        if [[ "$link" = "$view/"* ]] && [ ! -d "${link%/*}" ]; then
            if [ ! -f "$temporary/image.tar" ]; then
                cid="$("$container_engine" create --network none --entrypoint /nonexistent "$image")"
                [[ "$cid" =~ ^[a-f0-9]{12,64}$ ]] || reject container-seed-container
                (
                    ulimit -f 8388608
                    "$container_engine" export --output "$temporary/image.tar" "$cid"
                ) || reject container-seed-export
            fi
            destination="$(container_binding_destination "$image")"
            destination="${destination%/*}"
            install -d -o root -g root -m 755 -- "${link%/*}"
            extract_container_storage_seed "$temporary/image.tar" "$destination" "${link%/*}" VIEW - || reject container-view-invalid
            chown -hR root:root -- "${link%/*}"
            chmod -R u=rX,go=rX -- "${link%/*}"
        fi
        # Only the exact declared SQLite file is replaced; its source was explicitly seeded above.
        if [ -e "$link" ] && [[ "$link" != "$view/"* ]]; then
            local fresh allowed=0
            for fresh in "${container_fresh_files[@]}"; do [[ "$link" = "$fresh/"* ]] && allowed=1; done
            [ "$allowed" = 1 ] || reject container-database-existing-unbound-file
        fi
        if [ -e "$link" ]; then
            [ -f "$link" ] && [ ! -L "$link" ] && [ "$(stat -c %h -- "$link")" = 1 ] || reject container-database-alias
            rm -f -- "$link"
        fi
        [ "$(realpath -m -- "${link%/*}")" = "${link%/*}" ] || reject container-database-parent
        if [ ! -d "${link%/*}" ]; then install -d -o root -g root -m 755 -- "${link%/*}"; fi
        ln -s -- "$expected" "$link"
    done
)
