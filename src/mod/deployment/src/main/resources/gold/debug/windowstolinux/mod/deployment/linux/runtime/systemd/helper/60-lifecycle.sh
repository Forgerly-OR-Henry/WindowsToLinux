retain_releases() {
    [ "$#" -eq 2 ] || reject retain-arguments
    local app="$1"
    local manifest="$2"
    require_app "$app"
    require_digest "$manifest"
    local root releases current current_digest
    root="$(app_root "$app")"
    releases="$root/releases"
    assert_root_owned_directory "$root"
    assert_root_owned_directory "$releases"
    [ -L "$root/current" ] || reject current-not-link
    current="$(readlink -f -- "$root/current")"
    current_digest="${current##*/}"
    require_digest "$current_digest"
    [ "$current" = "$releases/$current_digest" ] || reject current-path
    mapfile -t releases_by_age < <(find -P "$releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %f\n' | LC_ALL=C sort -nr | awk '{print $2}')
    retained_noncurrent=0
    for release_digest in "${releases_by_age[@]}"; do
        require_digest "$release_digest"
        release="$releases/$release_digest"
        assert_root_owned_directory "$release"
        assert_root_owned_regular "$release/.windowstolinux-owner"
        [ "$(cat -- "$release/.windowstolinux-owner")" = "$manifest" ] || reject release-owner
        if [ "$release" = "$current" ]; then
            continue
        elif [ "$retained_noncurrent" -lt 2 ]; then
            retained_noncurrent=$((retained_noncurrent + 1))
        else
            remove_container_secret_delivery "$app" "$release_digest"
            rm -rf --one-file-system -- "$release"
        fi
    done
    printf 'RETAINED=%s\n' "$((retained_noncurrent + 1))"
}

inspect_managed_runtime() {
    [ "$#" -eq 2 ] || reject inspect-runtime-arguments
    local app="$1" manifest="$2" root releases current current_digest
    require_app "$app"
    require_digest "$manifest"
    root="$(app_root "$app")"
    releases="$root/releases"
    assert_root_owned_directory "$root"
    assert_root_owned_directory "$releases"
    [ -L "$root/current" ] || reject lifecycle-unmanaged
    current="$(readlink -f -- "$root/current")"
    current_digest="${current##*/}"
    require_digest "$current_digest"
    [ "$current" = "$releases/$current_digest" ] || reject current-path
    assert_root_owned_directory "$current"
    assert_root_owned_regular "$current/.windowstolinux-owner"
    [ "$(cat -- "$current/.windowstolinux-owner")" = "$manifest" ] || reject current-owner
    if [ -e "$current/.windowstolinux-deployment-parameters" ] || [ -L "$current/.windowstolinux-deployment-parameters" ]; then
        [ ! -e "$current/.windowstolinux-container-parameters" ] && [ ! -L "$current/.windowstolinux-container-parameters" ] \
            || reject runtime-marker-conflict
        assert_deployment_current_or_empty "$app" "$manifest"
        [ "$previous_present" -eq 1 ] || reject lifecycle-unmanaged
        printf 'KIND=deployment\nMODE=%s\n' "$application_mode"
    elif [ -e "$current/.windowstolinux-container-parameters" ] || [ -L "$current/.windowstolinux-container-parameters" ]; then
        [ ! -e "$current/.windowstolinux-deployment-parameters" ] && [ ! -L "$current/.windowstolinux-deployment-parameters" ] \
            || reject runtime-marker-conflict
        container_current_release "$app" "$manifest"
        [ "$previous_present" -eq 1 ] || reject lifecycle-unmanaged
        printf 'KIND=container\nENGINE=%s\nMODE=%s\n' "$container_engine" "$application_mode"
    else
        reject unsupported-runtime-format
    fi
}
