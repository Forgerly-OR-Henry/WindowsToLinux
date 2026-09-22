expected_unit_digest() {
    local legacy_runtime_user="$deployer" unit="${2:-$(unit_path "$1")}"
    if [ -f "$unit" ]; then
        assert_root_owned_regular "$unit"
        legacy_runtime_user="$(sed -n 's/^User=//p' "$unit")"
        [[ "$legacy_runtime_user" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || reject legacy-runtime-user
    fi
    render_unit "$1" | sha256sum | awk '{print $1}'
}
initialise_controlled_roots() {
    local path
    for path in "$base_root/apps" "$base_root/configurations" "$base_root/data" "$base_root/secrets"; do
        [ ! -e "$path" ] && [ ! -L "$path" ] || reject unsupported-legacy-layout
    done
    for path in "$base_root" "$applications_root" "$work_root" "$snapshots_root" "$configurations_root" "$secrets_root" "$backups_root" "$data_root"; do
        assert_storage_parent "$path"
        if [ -e "$path" ] || [ -L "$path" ]; then assert_root_owned_directory "$path"; fi
    done
    install -d -o root -g root -m 755 -- "$base_root" "$applications_root" "$work_root" "$snapshots_root" "$configurations_root" "$secrets_root" "$backups_root" "$data_root"
    assert_root_owned_directory "$base_root"
    assert_root_owned_directory "$applications_root"
    assert_root_owned_directory "$work_root"
    assert_root_owned_directory "$snapshots_root"
    assert_root_owned_directory "$configurations_root"
    assert_root_owned_directory "$secrets_root"
    assert_root_owned_directory "$backups_root"
}
assert_application_root_or_absent() {
    local app="$1"
    local root
    root="$(app_root "$app")"
    if [ -e "$root" ] || [ -L "$root" ]; then
        assert_root_owned_directory "$root"
    fi
}
assert_current_or_empty() {
    local app="$1"
    local manifest="$2"
    local root unit releases current expected
    root="$(app_root "$app")"
    unit="$(unit_path "$app")"
    releases="$root/releases"
    expected="$(expected_unit_digest "$app")"
    previous_present=0
    previous_path=
    previous_running=0
    if [ -e "$root/current" ] || [ -L "$root/current" ]; then
        assert_root_owned_directory "$root"
        [ -L "$root/current" ] || reject current-not-link
        current="$(readlink -f -- "$root/current")"
        case "$current" in "$releases"/[0-9a-f]*) ;; *) reject current-path ;; esac
        local current_digest="${current##*/}"
        require_digest "$current_digest"
        [ "$current" = "$releases/$current_digest" ] || reject current-path
        assert_root_owned_directory "$current"
        assert_root_owned_regular "$current/.windowstolinux-owner"
        [ "$(cat -- "$current/.windowstolinux-owner")" = "$manifest" ] || reject current-owner
        [ "$(systemctl show --value --property FragmentPath "$(unit_name "$app")")" = "$unit" ] || reject unit-fragment
        [ -z "$(systemctl show --value --property DropInPaths "$(unit_name "$app")")" ] || reject unit-dropins
        assert_root_owned_regular "$unit"
        [ "$(sha256sum -- "$unit" | awk '{print $1}')" = "$expected" ] || reject unit-digest
        previous_present=1
        previous_path="$current"
        if systemctl is-active --quiet "$(unit_name "$app")"; then
            previous_running=1
        fi
    else
        [ ! -e "$unit" ] && [ ! -L "$unit" ] || reject unmanaged-unit
        ! systemctl cat "$(unit_name "$app")" >/dev/null 2>&1 || reject unmanaged-unit
        if [ -e "$root" ] || [ -L "$root" ]; then
            assert_root_owned_directory "$root"
            if [ -e "$releases" ] || [ -L "$releases" ]; then
                assert_root_owned_directory "$releases"
                [ -z "$(find -P "$releases" -mindepth 1 -maxdepth 1 -print -quit)" ] || reject unmanaged-release
            fi
        fi
    fi
}
create_candidate() {
    [ "$#" -eq 3 ] || reject candidate-arguments
    local app="$1"
    local candidate_id="$2"
    require_workspace_limit "$3"
    require_app "$app"
    require_candidate "$app" "$candidate_id"
    initialise_controlled_roots
    local candidate mutable
    candidate="$(candidate_root "$candidate_id")"
    mutable="$candidate/mutable"
    [ ! -e "$candidate" ] && [ ! -L "$candidate" ] || reject candidate-exists
    install -d -o root -g root -m 711 -- "$candidate"
    install -d -o "$deployer" -g "$deployer_group" -m 700 -- "$mutable"
    printf '%s\n' "$deployer" >"$candidate/.deployer"
    chown root:root -- "$candidate/.deployer"
    chmod 444 -- "$candidate/.deployer"
    create_workspace_volume "$candidate" "$3"
    assert_candidate_for_deployer "$candidate"
    printf 'CANDIDATE=%s\n' "$candidate"
}
cleanup_candidate() {
    [ "$#" -eq 2 ] || reject cleanup-arguments
    local app="$1"
    local candidate_id="$2"
    require_app "$app"
    require_candidate "$app" "$candidate_id"
    local candidate
    candidate="$(candidate_root "$candidate_id")"
    if [ -e "$candidate" ] || [ -L "$candidate" ]; then
        assert_candidate_for_deployer "$candidate"
        stop_candidate_build "$app" "$candidate_id"
        if mountpoint -q "$candidate/mutable"; then freeze_candidate_build "$app" "$candidate_id"; fi
        cleanup_container_builder "$candidate"
        cleanup_candidate_image "$app" "$candidate_id"
        cleanup_restore_candidates "$candidate"
        cleanup_workspace_volume "$candidate"
        rm -rf --one-file-system -- "$candidate"
    fi
    printf 'CANDIDATE_CLEANED=1\n'
}
create_snapshot() {
    [ "$#" -eq 2 ] || reject snapshot-arguments
    local app="$1"
    local manifest="$2"
    require_app "$app"
    require_digest "$manifest"
    initialise_controlled_roots
    assert_application_root_or_absent "$app"
    assert_current_or_empty "$app" "$manifest"
    if [ "$previous_present" -eq 0 ]; then
        printf 'PREVIOUS=0\n'
        return
    fi
    local token snapshot unit
    token="$(cat /proc/sys/kernel/random/uuid)"
    require_snapshot_token "$token"
    snapshot="$(snapshot_root "$app" "$token")"
    unit="$(unit_path "$app")"
    [ ! -e "$snapshot" ] && [ ! -L "$snapshot" ] || reject snapshot-exists
    install -d -o root -g root -m 700 -- "$snapshot"
    printf '%s\n' "$previous_path" >"$snapshot/current-path"
    install -o root -g root -m 600 -- "$unit" "$snapshot/unit"
    systemctl is-enabled "$(unit_name "$app")" >"$snapshot/enabled" 2>/dev/null || true
    if [ "$previous_running" -eq 1 ]; then
        printf 'active\n' >"$snapshot/runtime"
    else
        printf 'inactive\n' >"$snapshot/runtime"
    fi
    chown root:root -- "$snapshot/current-path" "$snapshot/enabled" "$snapshot/runtime"
    chmod 600 -- "$snapshot/current-path" "$snapshot/enabled" "$snapshot/runtime"
    printf 'SNAPSHOT_TOKEN=%s\n' "$token"
    printf 'PREVIOUS=1\n'
    printf 'PREVIOUS_RUNNING=%s\n' "$previous_running"
}

create_restore_candidate() {
    [ "$#" -eq 2 ] || reject restore-candidate-arguments
    require_app "$1"
    require_candidate "$1" "$2"
    initialise_controlled_roots
    local candidate="$(candidate_root "$2")"
    [ ! -e "$candidate" ] && [ ! -L "$candidate" ] || reject candidate-exists
    install -d -o root -g root -m 711 -- "$candidate"
    install -d -o root -g root -m 700 -- "$candidate/mutable"
    printf '%s\n' "$deployer" >"$candidate/.deployer"
    printf 'restore\n' >"$candidate/.purpose"
    chmod 400 -- "$candidate/.deployer" "$candidate/.purpose"
    printf 'CANDIDATE=%s\n' "$candidate"
}

# Task-specific commands never adopt an existing unbound candidate.
require_agent_task() { [[ "$1" =~ ^[a-zA-Z0-9-]{1,80}$ ]] || reject task-identity; }
create_task_candidate() {
    [ "$#" -eq 4 ] || reject task-candidate-arguments
    require_agent_task "$4"
    create_candidate "$1" "$2" "$3"
    local candidate
    candidate="$(candidate_root "$2")"
    printf '%s\n' "$4" >"$candidate/.agent-task"
    chown root:root -- "$candidate/.agent-task"
    chmod 444 -- "$candidate/.agent-task"
}
assert_task_candidate() {
    require_app "$1"
    require_candidate "$1" "$2"
    require_agent_task "$3"
    local candidate
    candidate="$(candidate_root "$2")"
    assert_root_owned_directory "$work_root"
    if [ -e "$candidate" ] || [ -L "$candidate" ]; then
        assert_candidate_for_deployer "$candidate"
        assert_root_owned_regular "$candidate/.agent-task"
        [ "$(cat -- "$candidate/.agent-task")" = "$3" ] || reject foreign-task-candidate
    fi
}
query_task_candidate() {
    [ "$#" -eq 3 ] || reject task-query-arguments
    assert_task_candidate "$@"
    local candidate unit state
    candidate="$(candidate_root "$2")"
    unit="$(build_unit_name "$2")"
    state="$(systemctl show --value --property=ActiveState "$unit")" || reject task-build-query
    case "$state" in active | activating | deactivating | reloading) state=yes ;; inactive | failed | '') state=no ;; *) reject task-build-state ;; esac
    if [ -e "$candidate" ]; then printf 'CANDIDATE_STATE=owned\n'; else printf 'CANDIDATE_STATE=absent\n'; fi
    printf 'BUILD_ACTIVE=%s\n' "$state"
}
cleanup_task_candidate() {
    [ "$#" -eq 3 ] || reject task-cleanup-arguments
    assert_task_candidate "$@"
    cleanup_candidate "$1" "$2"
}
