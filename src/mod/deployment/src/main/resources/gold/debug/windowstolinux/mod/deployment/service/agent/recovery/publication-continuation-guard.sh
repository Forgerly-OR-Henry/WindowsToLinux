# This unique entry is appended only to the separately reviewed continuation body.
application_assert_no_orphan() { [ ! -e "$1/job" ] && [ ! -L "$1/job" ] || reject JOB; }
continuation_check=ARGUMENTS
continuation_mutation=0
reject() { printf 'CONTINUATION_REFUSED=%s\n' "$continuation_check"; exit 74; }
continuation_regular() {
    assert_storage_parent "$1"
    assert_root_owned_regular "$1"
    [ "$(stat -c %h -- "$1")" = 1 ] || reject FILE
}
continuation_fingerprint() {
    continuation_regular "$1"
    [ "$(sha256sum -- "$1" | awk '{print $1}')" = "$2" ] || reject FINGERPRINT
}
continuation_build_idle() {
    local name="windowstolinux-build-$1.service" state group processes
    state="$(systemctl show --value --property ActiveState "$name")" || reject BUILD
    case "$state" in inactive | failed) ;; *) reject BUILD ;; esac
    [ "$(systemctl show --value --property MainPID "$name")" = 0 ] || reject BUILD
    group="$(systemctl show --value --property ControlGroup "$name")" || reject BUILD
    if [ -n "$group" ]; then
        [[ "$group" = /* && "$group" != *..* && "$group" != *//* ]] || reject BUILD
        if [ -e "/sys/fs/cgroup$group" ]; then
            [ -d "/sys/fs/cgroup$group" ] && [ ! -L "/sys/fs/cgroup$group" ] || reject BUILD
            processes="$(find -P "/sys/fs/cgroup$group" -name cgroup.procs -exec cat {} +)" || reject BUILD
            [ -z "$processes" ] || reject BUILD
        fi
    fi
}
continuation() {
    [ "$#" -eq 14 ] || reject ARGUMENTS
    local task="$1" action="$2" execution="$3" app="$4" owner="$5" candidate="$6" release="$7" plan="$8"
    local owner_hash="$9" task_hash="${10}" maintenance_hash="${11}" release_hash="${12}" parameters_hash="${13}" unit_hash="${14}"
    require_snapshot_token "$task"; require_snapshot_token "$execution"
    [[ "$action" =~ ^[a-f0-9]{32}$ ]] || reject ARGUMENTS
    require_app "$app"; require_candidate "$app" "$candidate"
    local digest
    for digest in "$owner" "$release" "$plan" "$owner_hash" "$task_hash" "$maintenance_hash" "$release_hash" "$parameters_hash" "$unit_hash"; do require_digest "$digest"; done
    continuation_check=ROOT_IDENTITY
    require_root; require_deployer
    local directory="$base_root/application-locks/$app" root="$(app_root "$app")" candidate_path="$(candidate_root "$candidate")" expected_unit="$(unit_path "$app")" path count=0 deadline=$((SECONDS + 30))
    continuation_check=LOCKS
    assert_storage_parent "$directory"; assert_root_owned_directory "$directory"
    [ "$(stat -c %a -- "$directory")" = 700 ] || reject LOCKS
    for path in "$directory/admission" "$directory/run"; do continuation_regular "$path"; [ "$(stat -c %a -- "$path")" = 600 ] || reject LOCKS; done
    # Existing-only O_RDONLY descriptors: no mkdir, O_CREAT, or truncation before guards.
    exec {application_admission_fd}<"$directory/admission"
    flock -w 30 -x "$application_admission_fd" || reject LOCKS
    exec {application_run_fd}<"$directory/run"
    flock -w "$((deadline > SECONDS ? deadline - SECONDS : 0))" -x "$application_run_fd" || reject LOCKS
    continuation_check=JOB
    application_assert_no_orphan "$directory"
    continuation_check=CANDIDATE
    assert_storage_parent "$candidate_path"; assert_candidate_for_deployer "$candidate_path"
    continuation_regular "$candidate_path/.agent-task"
    [ "$(cat -- "$candidate_path/.agent-task")" = "$task" ] || reject CANDIDATE
    continuation_check=FINGERPRINTS
    continuation_fingerprint "$candidate_path/.deployer" "$owner_hash"
    continuation_fingerprint "$candidate_path/.agent-task" "$task_hash"
    continuation_fingerprint "$directory/maintenance" "$maintenance_hash"
    continuation_fingerprint "$root/releases/$release/.windowstolinux-owner" "$release_hash"
    continuation_fingerprint "$root/releases/$release/.windowstolinux-deployment-parameters" "$parameters_hash"
    continuation_fingerprint "$expected_unit" "$unit_hash"
    continuation_check=BUILD
    continuation_build_idle "$candidate"
    continuation_check=SNAPSHOTS
    assert_storage_parent "$snapshots_root/$app-$task"
    shopt -s nullglob dotglob
    for path in "$snapshots_root/$app-"*; do reject SNAPSHOTS; done
    continuation_check=MAINTENANCE
    [ "$(cat -- "$directory/maintenance")" = "deployment-$release" ] || reject MAINTENANCE
    continuation_check=CURRENT
    assert_root_owned_directory "$root"; assert_root_owned_directory "$root/releases"
    for path in "$root/releases/"*; do
        [ "$path" = "$root/releases/$release" ] || reject CURRENT
        assert_root_owned_directory "$path"; count=$((count + 1))
    done
    [ "$count" = 1 ] && [ -L "$root/current" ] && [ "$(readlink -- "$root/current")" = "$root/releases/$release" ] || reject CURRENT
    continuation_check=NATIVE_CONTRACT
    # Execute the original three pure contracts with distinct bounded rejection categories.
    current_application="$app"
    continuation_check=FULL_PARAMETER_VALIDATION
    load_deployment_parameters "$root/releases/$release/.windowstolinux-deployment-parameters"
    [ "$application_mode" = DAEMON ] || reject NATIVE_CONTRACT
    continuation_check=MANAGED_DATA_LINKS
    assert_managed_data_links "$root/releases/$release/source"
    continuation_check=UNIT_RENDER_MATCH
    local expected
    expected="$(expected_deployment_unit_digest "$app" "${deployment_runtime_parameters[@]}")"
    [ "$(systemctl show --value --property FragmentPath "$(unit_name "$app")")" = "$expected_unit" ] || reject NATIVE_CONTRACT
    [ -z "$(systemctl show --value --property DropInPaths "$(unit_name "$app")")" ] || reject NATIVE_CONTRACT
    [ "$(sha256sum -- "$expected_unit" | awk '{print $1}')" = "$expected" ] || reject NATIVE_CONTRACT
    continuation_check=NATIVE_CONTRACT
    assert_deployment_current_or_empty "$app" "$owner"
    [ "$previous_present" = 1 ] && [ "$previous_path" = "$root/releases/$release" ] && [ "$application_mode" = DAEMON ] || reject NATIVE_CONTRACT
    [ ! -e "/etc/containers/systemd/windowstolinux-$app.container" ] && [ ! -L "/etc/containers/systemd/windowstolinux-$app.container" ] || reject NATIVE_CONTRACT
    # Recheck all bound bytes and direct ownership boundaries immediately before the one mutation.
    continuation_check=FINGERPRINTS
    continuation_fingerprint "$candidate_path/.deployer" "$owner_hash"
    continuation_fingerprint "$candidate_path/.agent-task" "$task_hash"
    continuation_fingerprint "$directory/maintenance" "$maintenance_hash"
    continuation_fingerprint "$root/releases/$release/.windowstolinux-owner" "$release_hash"
    continuation_fingerprint "$root/releases/$release/.windowstolinux-deployment-parameters" "$parameters_hash"
    continuation_fingerprint "$expected_unit" "$unit_hash"
    continuation_check=CURRENT
    [ "$(readlink -- "$root/current")" = "$root/releases/$release" ] || reject CURRENT
    [ "$(cat -- "$directory/maintenance")" = "deployment-$release" ] || reject CURRENT
    continuation_check=ROLLBACK
    printf 'CONTINUATION_MUTATION_STARTED=1\n'
    rollback_deployment_first "$app" "$release" "$owner"
    continuation_check=POSTCONDITIONS
    [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject POSTCONDITIONS
    [ ! -e "$expected_unit" ] && [ ! -L "$expected_unit" ] || reject POSTCONDITIONS
    [ ! -e "$root/releases/$release" ] && [ ! -L "$root/releases/$release" ] || reject POSTCONDITIONS
    continuation_fingerprint "$directory/maintenance" "$maintenance_hash"
    [ "$(cat -- "$directory/maintenance")" = "deployment-$release" ] || reject POSTCONDITIONS
    rm -- "$directory/maintenance"
    [ ! -e "$directory/maintenance" ] && [ ! -L "$directory/maintenance" ] || reject POSTCONDITIONS
    assert_candidate_for_deployer "$candidate_path"
    continuation_fingerprint "$candidate_path/.deployer" "$owner_hash"
    continuation_fingerprint "$candidate_path/.agent-task" "$task_hash"
    printf 'CONTINUATION_COMPLETED=1\n'
}
continuation "$@"
