# Definitions above are unchanged native code. Only this entry is executable.
[ "$#" -eq 15 ] || exit 70
contract_fd="${15}"
[[ "$contract_fd" =~ ^[0-9]{1,6}$ ]] && [ "$contract_fd" -ge 3 ] || exit 70
contract_root_pid="$BASHPID"
contract_phase=ARGUMENTS
set -Eeuo pipefail
shopt -s inherit_errexit
contract_emit() { builtin printf 'WTL_CONTRACT_CONTROL_1\t%s\t%s\t%s\t%s\n' "$1" "$contract_phase" "$2" "$BASHPID" >&"$contract_fd"; }
contract_exit() { if [ "$BASHPID" = "$contract_root_pid" ]; then contract_emit EXIT "$1"; fi; }
trap 'contract_code=$?; contract_emit ERROR "$contract_code"' ERR
trap 'contract_code=$?; contract_exit "$contract_code"' EXIT
# Native rejection arguments can contain data: none is copied to the control channel.
reject() {
    case "${1:-}" in
        invalid-file | fingerprint | build | action | locks | job | task | snapshot | release | current | owner | container | maintenance | daemon | unit | fragment | dropins | drift | not-root | root-management-required | deployer | application-id | candidate-id | digest | snapshot-token | controlled-path-permissions | controlled-path-writable | controlled-directory | controlled-directory-symlink | controlled-directory-owner | controlled-file | controlled-file-symlink | controlled-file-owner | candidate-deployer | runtime-parameters | runtime-arguments | runtime-kind | runtime-argument | runtime-argument-count | relative-path | java-main | safe-name | service-port | deployment-input-arguments | unsupported-runtime-format | runtime-toolchain-arguments | runtime-version | runtime-toolchain-missing | toolchain-arguments | toolchain-binding-size | toolchain-binding-digest | toolchain-binding-format | toolchain-binding-fields | toolchain-origin | toolchain-directory | toolchain-version | toolchain-ecosystem | toolchain-binding-empty | toolchain-binding-changed | managed-data-source | managed-data-target-link | managed-data-file | managed-data-link-target | managed-configuration-digest | managed-data-link-content | runtime-identity-policy | runtime-bind-address) contract_emit REJECT 64 ;;
        *) contract_emit ERROR 64 ;;
    esac
    exit 64
}
contract_next() { contract_phase="$1"; contract_emit PHASE 0; }
contract_pass() { [ "$BASHPID" = "$contract_root_pid" ] || exit 70; contract_emit PASS 0; }
contract_emit BEGIN 0
contract_existing_regular() {
    assert_storage_parent "$1"
    assert_root_owned_regular "$1"
    [ "$(stat -c %h -- "$1")" = 1 ] || reject invalid-file
}
contract_fingerprint() {
    contract_existing_regular "$1"
    [ "$(sha256sum -- "$1" | awk '{print $1}')" = "$2" ] || reject fingerprint
}
contract_build_idle() {
    local unit="windowstolinux-build-$1.service" state group processes
    state="$(systemctl show --value --property ActiveState "$unit")" || reject build
    case "$state" in inactive | failed) ;; *) reject build ;; esac
    [ "$(systemctl show --value --property MainPID "$unit")" = 0 ] || reject build
    group="$(systemctl show --value --property ControlGroup "$unit")" || reject build
    if [ -n "$group" ]; then
        [[ "$group" = /* && "$group" != *..* && "$group" != *//* ]] || reject build
        if [ -e "/sys/fs/cgroup$group" ]; then
            [ -d "/sys/fs/cgroup$group" ] && [ ! -L "/sys/fs/cgroup$group" ] || reject build
            processes="$(find -P "/sys/fs/cgroup$group" -name cgroup.procs -exec cat {} +)" || reject build
            [ -z "$processes" ] || reject build
        fi
    fi
}
task="$1" action="$2" execution="$3" app="$4" owner="$5" candidate="$6" release="$7" plan="$8"
owner_hash="$9" task_hash="${10}" maintenance_hash="${11}" release_hash="${12}" parameters_hash="${13}" unit_hash="${14}"
require_snapshot_token "$task"
require_snapshot_token "$execution"
[[ "$action" =~ ^[a-f0-9]{32}$ ]] || reject action
require_app "$app"
require_candidate "$app" "$candidate"
for contract_digest in "$owner" "$release" "$plan" "$owner_hash" "$task_hash" "$maintenance_hash" "$release_hash" "$parameters_hash" "$unit_hash"; do require_digest "$contract_digest"; done
contract_next ROOT_IDENTITY
require_root
require_deployer
directory="$base_root/application-locks/$app"
root="$(app_root "$app")"
candidate_path="$(candidate_root "$candidate")"
expected_unit="$(unit_path "$app")"
contract_next LOCKS
assert_storage_parent "$directory"
assert_root_owned_directory "$directory"
[ "$(stat -c %a -- "$directory")" = 700 ] || reject locks
for contract_file in admission run; do
    contract_existing_regular "$directory/$contract_file"
    [ "$(stat -c %a -- "$directory/$contract_file")" = 600 ] || reject locks
done
# Existing-only descriptors, never the creating/truncating maintenance entry.
exec {application_admission_fd}<"$directory/admission"
flock -w 10 -x "$application_admission_fd" || reject locks
exec {application_run_fd}<"$directory/run"
flock -w 10 -x "$application_run_fd" || reject locks
contract_lock_identity() {
    local name="$1" fd="$2" descriptor_identity path_identity
    contract_existing_regular "$directory/$name"
    [ "$(stat -c %a -- "$directory/$name")" = 600 ] || reject locks
    descriptor_identity="$(stat -Lc '%d:%i:%u:%g:%a:%h' -- "/proc/self/fd/$fd")"
    path_identity="$(stat -c '%d:%i:%u:%g:%a:%h' -- "$directory/$name")"
    [ "$descriptor_identity" = "$path_identity" ] || reject drift
}
contract_lock_identity admission "$application_admission_fd"
contract_lock_identity run "$application_run_fd"
[ ! -e "$directory/job" ] && [ ! -L "$directory/job" ] || reject job
contract_next CANDIDATE
assert_storage_parent "$candidate_path"
assert_candidate_for_deployer "$candidate_path"
contract_existing_regular "$candidate_path/.agent-task"
[ "$(cat -- "$candidate_path/.agent-task")" = "$task" ] || reject task
contract_next FINGERPRINTS
contract_fingerprint "$candidate_path/.deployer" "$owner_hash"
contract_fingerprint "$candidate_path/.agent-task" "$task_hash"
contract_fingerprint "$directory/maintenance" "$maintenance_hash"
contract_fingerprint "$root/releases/$release/.windowstolinux-owner" "$release_hash"
contract_fingerprint "$root/releases/$release/.windowstolinux-deployment-parameters" "$parameters_hash"
contract_fingerprint "$expected_unit" "$unit_hash"
contract_next BUILD
contract_build_idle "$candidate"
contract_next SNAPSHOTS
assert_storage_parent "$snapshots_root/$app-$task"
shopt -s nullglob dotglob
for contract_file in "$snapshots_root/$app-"*; do reject snapshot; done
contract_next CURRENT
assert_root_owned_directory "$root"
assert_root_owned_directory "$root/releases"
contract_count=0
for contract_file in "$root/releases/"*; do
    [ "$contract_file" = "$root/releases/$release" ] || reject release
    assert_root_owned_directory "$contract_file"
    contract_count=$((contract_count + 1))
done
[ "$contract_count" = 1 ] && [ -L "$root/current" ] && [ "$(readlink -- "$root/current")" = "$root/releases/$release" ] || reject current
[ "$(cat -- "$root/releases/$release/.windowstolinux-owner")" = "$owner" ] || reject owner
[ ! -e "/etc/containers/systemd/windowstolinux-$app.container" ] && [ ! -L "/etc/containers/systemd/windowstolinux-$app.container" ] || reject container
contract_next MAINTENANCE
[ "$(cat -- "$directory/maintenance")" = "deployment-$release" ] || reject maintenance
current_application="$app"
# Direct calls preserve errexit, including the native redirect, pipeline and substitution.
contract_next FULL_PARAMETER_VALIDATION
load_deployment_parameters "$root/releases/$release/.windowstolinux-deployment-parameters"
[ "$application_mode" = DAEMON ] || reject daemon
contract_pass
contract_next MANAGED_DATA_LINKS
assert_managed_data_links "$root/releases/$release/source"
contract_pass
contract_next UNIT_RENDER_MATCH
contract_expected="$(expected_deployment_unit_digest "$app" "${deployment_runtime_parameters[@]}")"
require_digest "$contract_expected"
[ "$(sha256sum -- "$expected_unit" | awk '{print $1}')" = "$contract_expected" ] || reject unit
[ "$(systemctl show --value --property FragmentPath "$(unit_name "$app")")" = "$expected_unit" ] || reject fragment
contract_dropins="$(systemctl show --value --property DropInPaths "$(unit_name "$app")")"
[ -z "$contract_dropins" ] || reject dropins
contract_pass
contract_next STABILITY
contract_lock_identity admission "$application_admission_fd"
contract_lock_identity run "$application_run_fd"
contract_fingerprint "$candidate_path/.deployer" "$owner_hash"
contract_fingerprint "$candidate_path/.agent-task" "$task_hash"
contract_fingerprint "$directory/maintenance" "$maintenance_hash"
contract_fingerprint "$root/releases/$release/.windowstolinux-owner" "$release_hash"
contract_fingerprint "$root/releases/$release/.windowstolinux-deployment-parameters" "$parameters_hash"
contract_fingerprint "$expected_unit" "$unit_hash"
[ -L "$root/current" ] && [ "$(readlink -- "$root/current")" = "$root/releases/$release" ] || reject drift
[ "$(cat -- "$directory/maintenance")" = "deployment-$release" ] || reject drift
contract_next COMPLETE
