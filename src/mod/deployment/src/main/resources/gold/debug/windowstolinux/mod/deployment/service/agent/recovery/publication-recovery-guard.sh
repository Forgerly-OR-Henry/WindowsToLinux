# Fixed recovery-only override: never inspect, stop or remove an orphan job.
application_assert_no_orphan() {
    [ ! -e "$1/job" ] && [ ! -L "$1/job" ] || reject recovery-job-present
}
recovery_existing_regular() {
    assert_storage_parent "$1"
    assert_root_owned_regular "$1"
    [ "$(stat -c %h -- "$1")" = 1 ] || reject recovery-hardlink
}
recovery_lock_inputs() {
    local directory="$base_root/application-locks/$1" file
    assert_storage_parent "$directory"
    assert_root_owned_directory "$directory"
    [ "$(stat -c %a -- "$directory")" = 700 ] || reject recovery-lock-mode
    for file in admission run; do
        recovery_existing_regular "$directory/$file"
        [ "$(stat -c %a -- "$directory/$file")" = 600 ] || reject recovery-lock-mode
    done
}
recovery_build_idle() {
    local unit="windowstolinux-build-$1.service" state group processes
    state="$(systemctl show --value --property ActiveState "$unit")" || reject recovery-build-query
    case "$state" in inactive | failed) ;; *) reject recovery-build-active ;; esac
    [ "$(systemctl show --value --property MainPID "$unit")" = 0 ] || reject recovery-build-process
    group="$(systemctl show --value --property ControlGroup "$unit")" || reject recovery-build-query
    if [ -n "$group" ]; then
        [[ "$group" = /* && "$group" != *..* && "$group" != *//* ]] || reject recovery-build-cgroup
        if [ -e "/sys/fs/cgroup$group" ]; then
            [ -d "/sys/fs/cgroup$group" ] && [ ! -L "/sys/fs/cgroup$group" ] || reject recovery-build-cgroup
            processes="$(find -P "/sys/fs/cgroup$group" -name cgroup.procs -exec cat {} +)" || reject recovery-build-query
            [ -z "$processes" ] || reject recovery-build-process
        fi
    fi
}
recovery_first_rollback() {
    [ "$#" -eq 8 ] || reject recovery-arguments
    local task="$1" action="$2" execution="$3" app="$4" owner="$5" candidate="$6" release="$7" plan="$8"
    require_snapshot_token "$task"
    require_snapshot_token "$execution"
    [[ "$action" =~ ^[a-f0-9]{32}$ ]] || reject recovery-action
    require_app "$app"
    require_digest "$owner"
    require_digest "$release"
    require_digest "$plan"
    require_candidate "$app" "$candidate"
    require_root
    require_deployer
    recovery_lock_inputs "$app"
    application_maintenance "$app"
    # All guards and the old rollback run under the same two held descriptors.
    local root="$(app_root "$app")" lock="$base_root/application-locks/$app/maintenance" path found=0 dropins
    local candidate_path="$(candidate_root "$candidate")" expected_unit="$(unit_path "$app")"
    assert_storage_parent "$root"
    assert_storage_parent "$candidate_path"
    assert_candidate_for_deployer "$candidate_path"
    recovery_existing_regular "$candidate_path/.agent-task"
    [ "$(cat -- "$candidate_path/.agent-task")" = "$task" ] || reject recovery-candidate-task
    recovery_build_idle "$candidate"
    assert_storage_parent "$snapshots_root/$app-$task"
    shopt -s nullglob dotglob
    for path in "$snapshots_root/$app-"*; do reject recovery-snapshot-present; done
    recovery_existing_regular "$lock"
    [ "$(cat -- "$lock")" = "deployment-$release" ] || reject recovery-maintenance
    assert_root_owned_directory "$root"
    assert_root_owned_directory "$root/releases"
    for path in "$root/releases/"*; do
        [ "$path" = "$root/releases/$release" ] || reject recovery-extra-release
        assert_root_owned_directory "$path"
        found=$((found + 1))
    done
    [ "$found" -eq 1 ] || reject recovery-release-missing
    [ -L "$root/current" ] && [ "$(readlink -- "$root/current")" = "$root/releases/$release" ] || reject recovery-current-drift
    recovery_existing_regular "$root/releases/$release/.windowstolinux-owner"
    recovery_existing_regular "$root/releases/$release/.windowstolinux-deployment-parameters"
    assert_deployment_current_or_empty "$app" "$owner"
    [ "$previous_present" = 1 ] && [ "$previous_path" = "$root/releases/$release" ] || reject recovery-current-drift
    [ "$application_mode" = DAEMON ] || reject recovery-not-native-daemon
    # Native render parses only native runtime kinds; a container's parameters cannot pass it.
    [ ! -e "/etc/containers/systemd/windowstolinux-$app.container" ] && [ ! -L "/etc/containers/systemd/windowstolinux-$app.container" ] || reject recovery-container-unit
    recovery_existing_regular "$expected_unit"
    dropins="$(systemctl show --value --property DropInPaths "$(unit_name "$app")")" || reject recovery-unit-query
    [ -z "$dropins" ] || reject recovery-unit-dropins
    # Recheck the most direct ownership/current boundaries immediately before mutation.
    [ "$(readlink -- "$root/current")" = "$root/releases/$release" ] || reject recovery-current-drift
    [ "$(cat -- "$lock")" = "deployment-$release" ] || reject recovery-maintenance
    rollback_deployment_first "$app" "$release" "$owner"
    [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject recovery-current-remains
    [ ! -e "$expected_unit" ] && [ ! -L "$expected_unit" ] || reject recovery-unit-remains
    [ ! -e "$root/releases/$release" ] && [ ! -L "$root/releases/$release" ] || reject recovery-release-remains
    recovery_existing_regular "$lock"
    [ "$(cat -- "$lock")" = "deployment-$release" ] || reject recovery-maintenance
    rm -- "$lock"
    [ ! -e "$lock" ] && [ ! -L "$lock" ] || reject recovery-maintenance-remains
}
[ "$#" -eq 9 ] && [ "$1" = guarded-first-rollback ] || reject recovery-entry
shift
recovery_first_rollback "$@"
