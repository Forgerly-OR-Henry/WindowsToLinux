managed_binding_parts() {
    IFS=: read -r binding logical mode storage_kind location_type location_path database_file seed_file initialization_files configuration_sha256 <<<"$1"
}
require_storage_path() {
    [[ "$1" =~ ^/?[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$ ]] || reject managed-storage-path
    [ "${#1}" -le 512 ] && [ "$1" != - ] || reject managed-storage-path
    case "/$1/" in */../* | */./*) reject managed-storage-traversal ;; esac
}
managed_storage_directory() {
    local app="$1" kind="$2" id="$3" choice="$4" requested="$5" root category first
    require_app "$app"
    require_app "$id"
    root="$(app_root "$app")"
    case "$kind" in FILE) category=files ;; CONFIGURATION) category=configuration ;; DATABASE) category=databases ;; *) reject managed-storage-kind ;; esac
    case "$choice" in
        DEFAULT)
            [ "$requested" = - ] || reject managed-storage-default-path
            if [ "$kind" = CONFIGURATION ]; then
                printf '%s/%s/files/%s' "$configurations_root" "$app" "$id"
            else printf '%s/%s/%s/%s' "$data_root" "$app" "$category" "$id"; fi
            ;;
        CUSTOM)
            require_storage_path "$requested"
            if [[ "$requested" = /* ]]; then
                case "$requested" in "$root"/*) ;; *) reject managed-storage-outside-application ;; esac
                first="${requested#"$root"/}"
                first="${first%%/*}"
                case "$first" in releases | current | persistent | .*) reject managed-storage-reserved ;; esac
                if [ "$kind" = CONFIGURATION ]; then printf '%s/persistent/configuration/%s' "$root" "$id"; else printf '%s' "$requested"; fi
            else printf '%s/persistent/%s/%s' "$root" "$category" "$id"; fi
            ;;
        *) reject managed-storage-unreviewed ;;
    esac
}
assert_storage_parent() {
    local path="$1" ancestor
    [ "$(realpath -m -- "$path")" = "$path" ] || reject managed-storage-link-boundary
    ancestor="${path%/*}"
    while [ "$ancestor" != / ]; do
        if [ -e "$ancestor" ] || [ -L "$ancestor" ]; then
            [ -d "$ancestor" ] && [ ! -L "$ancestor" ] || reject managed-storage-parent
            [ "$(stat -c %u -- "$ancestor")" = 0 ] || reject managed-storage-parent-owner
            [ -z "$(find -P "$ancestor" -maxdepth 0 -perm /022 -print -quit)" ] || reject managed-storage-parent-writable
        fi
        ancestor="${ancestor%/*}"
        [ -n "$ancestor" ] || ancestor=/
    done
}
parse_managed_data_bindings() {
    [ "$#" -ge 3 ] || reject managed-data-arguments
    managed_data_application="$1"
    managed_data_component="$2"
    shift 2
    require_app "$managed_data_application"
    require_app "$managed_data_component"
    require_app "$current_application"
    require_count "$1"
    local remaining="$1" existing target other saved item
    shift
    managed_data_bindings=()
    while [ "$remaining" -gt 0 ]; do
        [ "$#" -ge 10 ] || reject managed-data-bindings
        binding="$1"
        logical="$2"
        mode="$3"
        storage_kind="$4"
        location_type="$5"
        location_path="$6"
        database_file="$7"
        seed_file="$8"
        initialization_files="$9"
        configuration_sha256="${10}"
        shift 10
        require_app "$binding"
        require_storage_path "$logical"
        [ "$mode" = ro ] || [ "$mode" = rw ] || reject managed-data-mode
        if [ "$seed_file" != - ]; then
            require_storage_path "$seed_file"
            [[ "$seed_file" != /* ]] || reject managed-storage-seed
        fi
        if [ "$storage_kind" = CONFIGURATION ]; then
            [ "$mode" = ro ] && [ "$seed_file" != - ] || reject managed-configuration-input
            require_digest "$configuration_sha256"
        else [ "$configuration_sha256" = - ] || reject managed-configuration-revision; fi
        if [ "$storage_kind" = DATABASE ]; then
            [[ "$database_file" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] && [ "$mode" = rw ] || reject managed-database-file
            if [ "$initialization_files" != - ]; then
                local -a sql_files
                IFS=, read -r -a sql_files <<<"$initialization_files"
                [ "${#sql_files[@]}" -le 32 ] || reject managed-database-initialization
                for item in "${sql_files[@]}"; do
                    require_storage_path "$item"
                    [[ "$item" != /* && "$item" = *.sql ]] || reject managed-database-initialization
                done
            fi
        else [ "$database_file" = - ] && [ "$initialization_files" = - ] || reject managed-database-file; fi
        saved="$binding:$logical:$mode:$storage_kind:$location_type:$location_path:$database_file:$seed_file:$initialization_files:$configuration_sha256"
        target="$(managed_storage_directory "$current_application" "$storage_kind" "$binding" "$location_type" "$location_path")"
        assert_storage_parent "$target"
        if [[ "$logical" = /* ]] && [ "$runtime_identity_policy" != CONTAINER_NON_ROOT ]; then
            case "$logical" in "$(app_root "$current_application")"/*) managed_storage_directory "$current_application" "$storage_kind" "$binding" CUSTOM "$logical" >/dev/null ;;
            *)
                local expected="$target"
                if [ "$storage_kind" = DATABASE ]; then expected="$target/$database_file"; fi
                if [ "$storage_kind" = CONFIGURATION ]; then expected="$target/revisions/$configuration_sha256/value"; fi
                [ "$location_type" = DEFAULT ] && [ "$logical" = "$expected" ] || reject managed-storage-access-boundary
                ;;
            esac
        fi
        for existing in "${managed_data_bindings[@]}"; do
            managed_binding_parts "$existing"
            other="$(managed_storage_directory "$current_application" "$storage_kind" "$binding" "$location_type" "$location_path")"
            case "$target/" in "$other/"*) reject managed-data-overlap ;; esac
            case "$other/" in "$target/"*) reject managed-data-overlap ;; esac
            local previous_logical="$logical" previous_kind="$storage_kind"
            managed_binding_parts "$saved"
            [ "${existing%%:*}" != "$binding" ] && [ "$previous_logical" != "$logical" ] || reject managed-data-duplicate
            if [ "$previous_kind" != DATABASE ] && [ "$storage_kind" != DATABASE ]; then
                case "$logical/" in "$previous_logical/"*) reject managed-data-access-overlap ;; esac
                case "$previous_logical/" in "$logical/"*) reject managed-data-access-overlap ;; esac
            fi
        done
        managed_data_bindings+=("$saved")
        remaining=$((remaining - 1))
    done
    local -a directories=() databases=()
    for item in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$item"
        if [ "$storage_kind" = DATABASE ]; then databases+=("$item"); else directories+=("$item"); fi
    done
    managed_data_bindings=("${directories[@]}" "${databases[@]}")
    managed_data_remaining_arguments=("$@")
}
managed_data_binding_root() {
    local requested="$1" spec
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        if [ "$binding" = "$requested" ]; then
            managed_storage_directory "$current_application" "$storage_kind" "$binding" "$location_type" "$location_path"
            return
        fi
    done
    reject managed-data-binding-missing
}
managed_storage_target() {
    local target
    target="$(managed_data_binding_root "$binding")"
    case "$storage_kind" in DATABASE) printf '%s/%s' "$target" "$database_file" ;;
    CONFIGURATION) printf '%s/revisions/%s/value' "$target" "$configuration_sha256" ;; *) printf '%s' "$target" ;; esac
}
managed_binding_access() {
    local source="$1" wanted="$logical" spec parent
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        if [ "$storage_kind" = FILE ] && [[ "$wanted" = "$logical/"* ]]; then
            parent="$(managed_data_binding_root "$binding")"
            printf '%s/%s' "$parent" "${wanted#"$logical"/}"
            return
        fi
    done
    if [[ "$wanted" = /* ]]; then printf '%s' "$wanted"; else printf '%s/%s' "$source${application_workdir:+/$application_workdir}" "$wanted"; fi
}
assert_storage_mount_boundary() {
    local root="$1" mount mounts
    mounts="$(findmnt -rn -o TARGET)" || reject managed-storage-mount-inspection
    while IFS= read -r mount; do
        case "$mount" in "$root"/*) reject managed-storage-nested-mount ;; esac
    done <<<"$mounts"
}
assert_storage_tree() {
    local target="$1" original="${2:-$1}"
    assert_storage_mount_boundary "$target"
    [ ! -L "$target" ] && [ -d "$target" ] || reject managed-data-directory
    [ -z "$(find -P "$target" -xdev ! -type d ! -type f ! -type l -print -quit)" ] || reject managed-data-special
    [ -z "$(find -P "$target" -xdev -type f -links +1 -print -quit)" ] || reject managed-data-hardlink
    local link spec expected allowed access
    while IFS= read -r -d '' link; do
        allowed=0
        for spec in "${managed_data_bindings[@]}"; do
            managed_binding_parts "$spec"
            [ "$storage_kind" = DATABASE ] || continue
            expected="$(managed_storage_target)"
            access="$(managed_binding_access "${storage_source_root:-/nonexistent}")"
            if [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ]; then
                expected="/run/windowstolinux/databases/$binding/$database_file"
                if [ -n "${container_storage_image:-}" ]; then access="$(container_database_link "$container_storage_image")"; fi
            fi
            if [ "$original/${link#"$target"/}" = "$access" ] && [ "$(readlink -- "$link")" = "$expected" ]; then
                allowed=1
                break
            fi
        done
        [ "$allowed" = 1 ] || reject managed-data-link-content
    done < <(find -P "$target" -xdev -type l -print0)
}
assert_managed_data_links() {
    local source_root="$1" spec target link directory
    storage_source_root="$source_root"
    [ -d "$source_root" ] && [ ! -L "$source_root" ] || reject managed-data-source
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        directory="$(managed_data_binding_root "$binding")"
        assert_storage_parent "$directory"
        target="$(managed_storage_target)"
        link="$(managed_binding_access "$source_root")"
        [ ! -L "$target" ] || reject managed-data-target-link
        if [ "$storage_kind" = FILE ]; then
            (assert_storage_tree "$target")
        else [ -f "$target" ] && [ "$(stat -c %h -- "$target")" = 1 ] || reject managed-data-file; fi
        if [ "$runtime_identity_policy" != CONTAINER_NON_ROOT ] && [ "$link" != "$target" ]; then
            [ -L "$link" ] && [ "$(readlink -- "$link")" = "$target" ] || reject managed-data-link-target
        fi
        if [ "$storage_kind" = CONFIGURATION ]; then
            assert_root_owned_regular "$target"
            [ "$(sha256sum -- "$target" | awk '{print $1}')" = "$configuration_sha256" ] || reject managed-configuration-digest
        fi
    done
}
record_storage_binding() {
    local target="$1" record="$base_root/storage/$current_application/$binding" expected
    expected="$storage_kind:$location_type:$location_path"
    assert_storage_parent "$record"
    if [ -e "$record" ] || [ -L "$record" ]; then
        assert_root_owned_regular "$record"
        [ "$(cat -- "$record")" = "$expected" ] || reject managed-storage-location-changed
    else
        [ ! -e "$target" ] && [ ! -L "$target" ] || reject managed-storage-unowned-target
        install -d -o root -g root -m 700 -- "${record%/*}"
        printf '%s\n' "$expected" >"$record"
        chmod 400 -- "$record"
    fi
}
link_managed_storage() {
    local source_root="$1" target="$2" link="$3" parent="${3%/*}" existing
    [ "$link" != "$target" ] || return 0
    [ "$(realpath -m -- "$parent")" = "$parent" ] || reject managed-data-alias-parent
    if [[ "$link" = "$source_root/"* ]]; then
        if [ -e "$link" ] || [ -L "$link" ]; then
            if [ -L "$link" ]; then
                existing="$(readlink -- "$link")"
                [ "$existing" = "$target" ] || reject managed-data-alias-changed
            else rm -rf --one-file-system -- "$link"; fi
        fi
    elif [ -e "$link" ] || [ -L "$link" ]; then
        [ -L "$link" ] || reject managed-data-alias-unowned
        existing="$(readlink -- "$link")"
        if [ "$storage_kind" = CONFIGURATION ]; then
            case "$existing" in "$(managed_data_binding_root "$binding")/revisions/"*/value) ;; *) reject managed-data-alias-changed ;; esac
        else [ "$existing" = "$target" ] || reject managed-data-alias-changed; fi
    fi
    if [ ! -d "$parent" ]; then install -d -o root -g root -m 755 -- "$parent"; fi
    ln -sfnT -- "$target" "$link"
}
prepare_managed_data_bindings() {
    local source_root="$1" spec target directory link parent owner group seed
    storage_source_root="$source_root"
    prepare_service_identity "$current_application"
    owner="$(service_identity_name "$current_application")"
    group="$owner"
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        directory="$(managed_data_binding_root "$binding")"
        target="$(managed_storage_target)"
        link="$(managed_binding_access "$source_root")"
        assert_storage_parent "$directory"
        record_storage_binding "$directory"
        install -d -o root -g root -m 755 -- "${directory%/*}"
        if [ "$storage_kind" = CONFIGURATION ]; then
            if [ ! -e "$target" ] && [ ! -L "$target" ]; then
                seed="$source_root/$seed_file"
                assert_root_owned_regular "$seed"
                [ "$(sha256sum -- "$seed" | awk '{print $1}')" = "$configuration_sha256" ] || reject managed-configuration-seed
                install -d -o root -g root -m 755 -- "${target%/*}"
                install -o root -g root -m 444 -- "$seed" "$target"
            fi
        else
            if [ ! -e "$directory" ]; then
                if [ "$storage_kind" = FILE ] && [ "$seed_file" != - ]; then
                    seed="$source_root/$seed_file"
                    [ "$(realpath -m -- "$seed")" = "$seed" ] || reject managed-storage-seed-link
                    (assert_storage_tree "$seed")
                    initialize_managed_file_tree "$seed" "$directory" "$owner"
                else install -d -o "$owner" -g "$group" -m 700 -- "$directory"; fi
            fi
            (assert_storage_tree "$directory")
            chown -hR "$owner:$group" -- "$directory"
            if [ "$mode" = ro ]; then
                chown -hR root:"$group" -- "$directory"
                chmod -R u=rX,g=rX,o= -- "$directory"
            else chmod -R u+rwX,go-rwx -- "$directory"; fi
            if [ "$storage_kind" = DATABASE ]; then initialize_managed_sqlite "$source_root" "$directory" "$target" "$owner"; fi
        fi
        if [ "$runtime_identity_policy" != CONTAINER_NON_ROOT ]; then link_managed_storage "$source_root" "$target" "$link"; fi
    done
    assert_managed_data_links "$source_root"
}
initialize_managed_file_tree() (
    local seed="$1" target="$2" owner="$3" temporary
    temporary="$(mktemp -d "${target%/*}/.initialize-files.XXXXXX")"
    trap 'rm -rf --one-file-system -- "$temporary"' EXIT
    cp -a --no-preserve=ownership -- "$seed/." "$temporary/" || reject managed-storage-seed-copy
    chown -hR "$owner:$owner" -- "$temporary"
    chmod -R u+rwX,go-rwx -- "$temporary"
    [ ! -e "$target" ] && [ ! -L "$target" ] || reject managed-storage-seed-race
    mv -T -- "$temporary" "$target"
)
initialize_managed_sqlite() (
    local source_root="$1" directory="$2" target="$3" owner="$4" temporary input file seed
    command -v sqlite3 >/dev/null 2>&1 || reject database-tool-unavailable
    [ ! -L "$target" ] || reject database-source-link
    if [ -e "$target" ]; then
        [ -f "$target" ] && [ "$(stat -c %h -- "$target")" = 1 ] || reject database-source-file
        [ "$(sqlite3 -- "$target" 'PRAGMA integrity_check;' 2>/dev/null)" = ok ] || reject database-sqlite-integrity
        return
    fi
    temporary="$(mktemp -d "$directory/.initialize.XXXXXX")"
    trap 'rm -rf --one-file-system -- "$temporary"' EXIT
    chown "$owner:$owner" -- "$temporary"
    if [ "$seed_file" != - ]; then
        seed="$source_root/$seed_file"
        assert_root_owned_regular "$seed"
        [ "$(realpath -m -- "$seed")" = "$seed" ] && [ "$(stat -c %h -- "$seed")" = 1 ] || reject database-seed-link
        local suffix
        for suffix in -wal -shm -journal; do
            if [ -e "$seed$suffix" ] || [ -L "$seed$suffix" ]; then
                assert_root_owned_regular "$seed$suffix"
                [ "$(stat -c %h -- "$seed$suffix")" = 1 ] || reject database-seed-link
            fi
        done
        sqlite3 -readonly -- "$seed" ".backup '$temporary/value'" || {
            rm -rf --one-file-system -- "$temporary"
            reject database-seed-invalid
        }
        chown "$owner:$owner" -- "$temporary/value"
        chmod 600 -- "$temporary/value"
    fi
    input="$temporary/input.sql"
    printf '.bail on\n' >"$input"
    if [ "$initialization_files" != - ]; then
        local -a sql_files
        IFS=, read -r -a sql_files <<<"$initialization_files"
        for file in "${sql_files[@]}"; do
            seed="$source_root/$file"
            assert_root_owned_regular "$seed"
            [ "$(realpath -m -- "$seed")" = "$seed" ] && [ "$(stat -c %s -- "$seed")" -le 2097152 ] || reject database-initialization-source
            ! grep -Eq '^[[:space:]]*\.' "$seed" || reject database-initialization-cli-command
            cat -- "$seed" >>"$input"
            printf '\n' >>"$input"
        done
    fi
    printf 'PRAGMA integrity_check;\n' >>"$input"
    chown root:"$owner" -- "$input"
    chmod 440 -- "$input"
    if ! systemd-run --quiet --wait --pipe --collect --property="User=$owner" --property="Group=$owner" \
        --property=ProtectSystem=strict --property="ReadWritePaths=$temporary" --property=ProtectHome=yes \
        --property=PrivateTmp=yes --property=PrivateNetwork=yes --property=NoNewPrivileges=yes --property=CapabilityBoundingSet= \
        --property=RuntimeMaxSec=60s --property=MemoryMax=512M --property=TasksMax=64 \
        /usr/bin/sqlite3 "$temporary/value" <"$input" >/dev/null; then
        rm -rf --one-file-system -- "$temporary"
        reject database-initialization-failed
    fi
    [ "$(sqlite3 -- "$temporary/value" 'PRAGMA integrity_check;' 2>/dev/null)" = ok ] || {
        rm -rf --one-file-system -- "$temporary"
        reject database-sqlite-integrity
    }
    sqlite3 -- "$temporary/value" 'PRAGMA wal_checkpoint(TRUNCATE); PRAGMA journal_mode=DELETE;' >/dev/null || reject database-initialization-checkpoint
    [ ! -e "$target" ] && [ ! -L "$target" ] || reject database-initialization-race
    chown "$owner:$owner" -- "$temporary/value"
    chmod 600 -- "$temporary/value"
    mv -T -- "$temporary/value" "$target"
    rm -rf --one-file-system -- "$temporary"
)
