application_lock_directory() {
    require_app "$1"
    local directory="$base_root/application-locks/$1"
    assert_storage_parent "$directory"
    install -d -o root -g root -m 700 -- "$directory"
    assert_root_owned_directory "$directory"
    printf '%s' "$directory"
}
application_maintenance() {
    local directory deadline=$((SECONDS + 30))
    directory="$(application_lock_directory "$1")"
    exec {application_admission_fd}>"$directory/admission"
    flock -w 30 -x "$application_admission_fd" || reject application-busy
    exec {application_run_fd}>"$directory/run"
    flock -w "$((deadline > SECONDS ? deadline - SECONDS : 0))" -x "$application_run_fd" || reject application-busy
    application_assert_no_orphan "$directory"
}
application_admit_run() {
    local directory
    directory="$(application_lock_directory "$1")"
    exec {application_admission_fd}>"$directory/admission"
    flock -n -x "$application_admission_fd" || reject application-maintenance-in-progress
    [ "${application_validation:-0}" = 1 ] || [ ! -e "$directory/maintenance" ] || reject application-maintenance-in-progress
    exec {application_run_fd}>"$directory/run"
    flock -n -x "$application_run_fd" || reject application-busy
    application_assert_no_orphan "$directory"
    flock -u "$application_admission_fd"
}
