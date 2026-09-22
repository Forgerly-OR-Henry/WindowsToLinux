require_backup_operation() {
    [[ "$1" =~ ^backup-[0-9a-f]{32}$ ]] || reject backup-operation
}
require_backup_artifact() {
    [[ "$1" =~ ^artifact-[0-9a-f]{32}$ ]] || reject backup-artifact
}
backup_operation_root() { printf '%s/%s' "$backups_root" "$1"; }
prepare_backup_operation() {
    local operation="$1" root
    require_backup_operation "$operation"
    root="$(backup_operation_root "$operation")"
    install -d -o root -g root -m 700 -- "$backups_root"
    if [ ! -e "$root" ] && [ ! -L "$root" ]; then
        install -d -o root -g root -m 700 -- "$root"
        printf '%s\n' "$deployer" >"$root/.deployer"
        chown root:root -- "$root/.deployer"
        chmod 400 -- "$root/.deployer"
    fi
    assert_root_owned_directory "$root"
    assert_root_owned_regular "$root/.deployer"
    [ "$(cat -- "$root/.deployer")" = "$deployer" ] || reject backup-deployer
}
backup_artifact_identity() {
    printf '%s\0' "$@" | sha256sum | awk '{print "artifact-" substr($1,1,32)}'
}
require_backup_limit() {
    [[ "$1" =~ ^[1-9][0-9]{0,10}$ ]] && [ "$1" -le 68719476736 ] || reject backup-limit
}
assert_backup_tree() {
    local source="$1" allowed_link link relative found
    shift
    assert_storage_mount_boundary "$source"
    [ -d "$source" ] && [ ! -L "$source" ] || reject backup-source
    [ -z "$(find -P "$source" -xdev ! -type f ! -type d ! -type l -print -quit)" ] || reject backup-special-file
    [ -z "$(find -P "$source" -xdev -type f -links +1 -print -quit)" ] || reject backup-hardlink
    while IFS= read -r -d '' link; do
        relative="${link#"$source"/}"
        found=0
        for allowed_link in "$@"; do case "$relative" in "$allowed_link" | "$allowed_link"/*) found=1 ;; esac done
        [ "$found" -eq 1 ] || reject backup-link
    done < <(find -P "$source" -xdev -type l -print0)
}
create_pax_artifact() {
    local operation="$1" artifact="$2" source="$3" maximum="$4"
    shift 4
    local root target spec
    root="$(backup_operation_root "$operation")"
    target="$root/$artifact.pax"
    [ ! -e "$target" ] && [ ! -L "$target" ] || reject backup-artifact-exists
    assert_backup_tree "$source" "$@"
    local -a excludes=()
    for spec in "$@"; do excludes+=("--exclude=./$spec"); done
    tar --format=pax --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
        --pax-option=delete=atime,delete=ctime,delete=SCHILY.xattr.*,delete=LIBARCHIVE.xattr.* \
        "${excludes[@]}" -C "$source" -cf "$target" .
    chown root:root -- "$target"
    chmod 400 -- "$target"
    [ "$(stat -c '%s' -- "$target")" -le "$maximum" ] || {
        rm -f -- "$target"
        reject backup-limit
    }
}
backup_sqlite_exclusions() {
    local source="$1" release="$2" spec target access suffix relative
    backup_excluded_paths=()
    for spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$spec"
        [ "$storage_kind" = DATABASE ] || continue
        target="$(managed_storage_target)"
        access="$(managed_binding_access "$release/source")"
        if [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ]; then access="$(container_database_link "$container_storage_image")"; fi
        for relative in "$target" "$access"; do
            if [[ "$relative" = "$source/"* ]]; then
                relative="${relative#"$source"/}"
                for suffix in '' -wal -shm -journal; do backup_excluded_paths+=("$relative$suffix"); done
            fi
        done
    done
}
backup_create_artifact() {
    [ "$#" -eq 9 ] || reject backup-create-arguments
    local operation="$1" application="$2" component="$3" app="$4" release="$5" manifest="$6" kind="$7" resource="$8" maximum="$9"
    require_backup_operation "$operation"
    require_app "$application"
    require_app "$component"
    require_app "$app"
    require_digest "$release"
    require_digest "$manifest"
    require_backup_limit "$maximum"
    [[ "$resource" =~ ^[a-z0-9][a-z0-9-]{0,127}$ ]] || reject backup-resource
    case "$kind" in file | release | oci) ;; *) reject backup-kind ;; esac
    prepare_backup_operation "$operation"
    current_application="$app"
    local artifact root target source current_digest spec found image image_id
    artifact="$(backup_artifact_identity "$application" "$component" "$app" "$release" "$kind" "$resource")"
    require_backup_artifact "$artifact"
    root="$(backup_operation_root "$operation")"
    if [ -e "$(app_root "$app")/current/.windowstolinux-container-parameters" ]; then
        container_current_release "$app" "$manifest"
    else assert_deployment_current_or_empty "$app" "$manifest"; fi
    [ "$previous_present" -eq 1 ] || reject backup-current
    current_digest="${previous_path##*/}"
    [ "$current_digest" = "$release" ] || reject backup-release
    [ "$managed_data_application" = "$application" ] && [ "$managed_data_component" = "$component" ] || reject backup-data-owner
    if [ "$kind" = release ]; then
        [ "$resource" = release ] || reject backup-resource
        local -a links=()
        if [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ]; then links+=(.container-storage-view); fi
        if [ "$runtime_identity_policy" = SYSTEMD_STATIC ]; then
            for spec in "${managed_data_bindings[@]}"; do
                managed_binding_parts "$spec"
                if [[ "$logical" != /* ]]; then links+=("source/$logical"); fi
            done
        fi
        create_pax_artifact "$operation" "$artifact" "$previous_path" "$maximum" "${links[@]}"
        target="$root/$artifact.pax"
    elif [ "$kind" = file ]; then
        found=0
        for spec in "${managed_data_bindings[@]}"; do
            managed_binding_parts "$spec"
            if [ "$binding" = "$resource" ]; then
                [ "$storage_kind" != DATABASE ] || reject backup-database-requires-consistent-artifact
                source="$(managed_storage_target)"
                if [ "$storage_kind" = CONFIGURATION ]; then source="${source%/*}"; fi
                found=1
                break
            fi
        done
        [ "$found" = 1 ] || reject backup-binding
        backup_sqlite_exclusions "$source" "$previous_path"
        create_pax_artifact "$operation" "$artifact" "$source" "$maximum" "${backup_excluded_paths[@]}"
        target="$root/$artifact.pax"
    else
        [ "$resource" = image ] && [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ] || reject backup-resource
        image="$(container_image "$app" "$release")"
        image_id="$(cat -- "$previous_path/.windowstolinux-container-image-id")"
        [ "$("$container_engine" image inspect --format '{{.Id}}' "$image")" = "$image_id" ] || reject backup-image-identity
        target="$root/$artifact.oci"
        [ ! -e "$target" ] && [ ! -L "$target" ] || reject backup-artifact-exists
        if [ "$container_engine" = podman ]; then
            podman save --format oci-archive --output "$target" "$image"
        else /usr/bin/skopeo --override-arch amd64 copy "docker-daemon:$image" "oci-archive:$target:$image" >/dev/null; fi
        chown root:root -- "$target"
        chmod 400 -- "$target"
        [ "$(stat -c '%s' -- "$target")" -le "$maximum" ] || {
            rm -f -- "$target"
            reject backup-limit
        }
    fi
    printf 'ARTIFACT=%s\nKIND=%s\nSIZE=%s\nSHA256=%s\n' "$artifact" "$kind" \
        "$(stat -c '%s' -- "$target")" "$(sha256sum -- "$target" | awk '{print $1}')"
}
backup_read_artifact() {
    [ "$#" -eq 5 ] || reject backup-read-arguments
    local operation="$1" artifact="$2" kind="$3" expected_size="$4" expected_digest="$5" extension root target
    require_backup_operation "$operation"
    require_backup_artifact "$artifact"
    require_digest "$expected_digest"
    [[ "$expected_size" =~ ^[1-9][0-9]{0,10}$ ]] || reject backup-size
    case "$kind" in file | release | volume) extension=pax ;; oci) extension=oci ;; *) reject backup-kind ;; esac
    prepare_backup_operation "$operation"
    root="$(backup_operation_root "$operation")"
    target="$root/$artifact.$extension"
    assert_root_owned_regular "$target"
    [ "$(stat -c '%s' -- "$target")" = "$expected_size" ] \
        && [ "$(sha256sum -- "$target" | awk '{print $1}')" = "$expected_digest" ] || reject backup-artifact-identity
    cat -- "$target"
}
backup_discard_operation() {
    [ "$#" -eq 1 ] || reject backup-discard-arguments
    local operation="$1" root
    require_backup_operation "$operation"
    root="$(backup_operation_root "$operation")"
    if [ ! -e "$root" ] && [ ! -L "$root" ]; then
        printf 'DISCARDED=1\n'
        return
    fi
    prepare_backup_operation "$operation"
    rm -rf --one-file-system -- "$root"
    printf 'DISCARDED=1\n'
}
