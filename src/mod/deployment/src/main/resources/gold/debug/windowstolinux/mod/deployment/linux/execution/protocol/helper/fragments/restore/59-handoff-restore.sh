# The caller's original task, reviewed target and candidate are immutable.
# An attempted step without a completion receipt is never dispatched again.
handoff_restore_identity() {
    [ "$#" -eq 4 ] || reject handoff-restore-identity-arguments
    handoff_restore_app="$1" handoff_restore_candidate="$2"
    handoff_restore_task="$3" handoff_restore_review="$4"
    require_app "$handoff_restore_app"
    require_candidate "$handoff_restore_app" "$handoff_restore_candidate"
    require_snapshot_token "$handoff_restore_task"
    require_digest "$handoff_restore_review"
    handoff_restore_root="$(candidate_root "$handoff_restore_candidate")"
    handoff_restore_owner="$(printf '%s\n' "$@")"
}

handoff_restore_lock() {
    local locks="$base_root/restore-operation-locks" lock
    if [ -e "$locks" ] || [ -L "$locks" ]; then assert_root_owned_directory "$locks"
    elif [ "$1" = create ]; then install -d -o root -g root -m 700 -- "$locks"
    else reject handoff-restore-lock-missing; fi
    assert_root_owned_directory "$locks"
    lock="$locks/$handoff_restore_candidate"
    if [ "$1" = create ] && [ ! -e "$lock" ] && [ ! -L "$lock" ]; then
        (umask 077; set -o noclobber; : >"$lock") 2>/dev/null || :
    fi
    assert_root_owned_regular "$lock"
    exec {handoff_restore_fd}<"$lock"
    flock -n "$handoff_restore_fd" || reject handoff-restore-writer-active
}

handoff_restore_preflight() {
    [ "$#" -eq 1 ] || reject handoff-restore-preflight-arguments
    local required="$1" available
    [[ "$required" =~ ^[0-9]{1,12}$ ]] && [ "$required" -ge 1 ] || reject handoff-restore-required-space
    assert_root_owned_directory /var/lib
    if [ -e "$base_root" ] || [ -L "$base_root" ]; then assert_root_owned_directory "$base_root"; fi
    available="$(df -PB1 /var/lib 2>/dev/null | awk 'NR==2 {print $4}')"
    [[ "$available" =~ ^[0-9]+$ ]] && [ "$available" -ge "$required" ] || reject handoff-restore-insufficient-space
    printf 'RESTORE_SPACE_VERIFIED=1\n'
}

handoff_restore_assert() {
    assert_candidate_for_deployer "$handoff_restore_root"
    assert_root_owned_regular "$handoff_restore_root/.handoff-owner"
    [ "$(cat -- "$handoff_restore_root/.handoff-owner")" = "$handoff_restore_owner" ] \
        || reject handoff-restore-owner
}

handoff_restore_create() {
    handoff_restore_identity "$@"
    initialise_controlled_roots
    handoff_restore_lock create
    [ ! -e "$handoff_restore_root" ] && [ ! -L "$handoff_restore_root" ] || reject handoff-restore-already-attempted
    create_restore_candidate "$handoff_restore_app" "$handoff_restore_candidate" >/dev/null
    printf '%s\n' "$handoff_restore_owner" >"$handoff_restore_root/.handoff-owner"
    chown root:root -- "$handoff_restore_root/.handoff-owner"
    chmod 400 -- "$handoff_restore_root/.handoff-owner"
    # The admitted transport writes only this staging directory. Preparation
    # seals it back to root ownership before extracting an archive.
    install -d -o "$deployer" -g "$(id -gn "$deployer")" -m 700 -- "$handoff_restore_root/mutable/restore"
    chmod 711 -- "$handoff_restore_root/mutable"
    printf 'RESTORE_CANDIDATE=%s\n' "$handoff_restore_root"
}

handoff_restore_file() {
    [ "$#" -eq 7 ] || reject handoff-restore-file-arguments
    handoff_restore_identity "${@:1:4}"
    local member="$5" size="$6" digest="$7" path
    require_restore_member "$member"
    [[ "$member" != */* ]] || reject handoff-restore-member-directory
    [[ "$size" =~ ^[0-9]{1,11}$ ]] && [ "$size" -ge 1 ] && [ "$size" -le 68719476736 ] || reject handoff-restore-file-size
    require_digest "$digest"
    handoff_restore_lock observe
    handoff_restore_assert
    path="$handoff_restore_root/mutable/restore/$member"
    [ -f "$path" ] && [ ! -L "$path" ] && [ "$(realpath -e -- "$path")" = "$path" ] \
        || reject handoff-restore-file
    [ "$(stat -c '%U' -- "$path")" = "$deployer" ] || [ "$(stat -c '%u' -- "$path")" = 0 ] \
        || reject handoff-restore-file-owner
    [ "$(stat -c '%s' -- "$path")" = "$size" ] \
        && [ "$(sha256sum -- "$path" | awk '{print $1}')" = "$digest" ] || reject handoff-restore-file-drift
    printf 'RESTORE_FILE_VERIFIED=1\n'
}

handoff_restore_step_identity() {
    [ "$#" -ge 6 ] || reject handoff-restore-step-arguments
    handoff_restore_identity "${@:1:4}"
    handoff_restore_step_key="$5" handoff_restore_verb="$6"
    require_app "$handoff_restore_step_key"
    shift 6
    handoff_restore_arguments=("$@")
    handoff_restore_request="$(printf '%s\n' "$handoff_restore_owner" "$handoff_restore_step_key" "$handoff_restore_verb" "$@")"
    handoff_restore_attempt="$handoff_restore_root/.attempt-$handoff_restore_step_key"
    handoff_restore_done="$handoff_restore_root/.done-$handoff_restore_step_key"
    case "$handoff_restore_verb" in
        restore-prepare | restore-start-candidate | restore-stop-candidate | restore-snapshot | restore-start-formal | restore-recover | restore-quiesce-recovery)
            [ "$#" -ge 3 ] && [ "$1" = "$handoff_restore_candidate" ] \
                && [ "$2" = "${handoff_restore_task//-/}" ] || reject handoff-restore-step-scope ;;
        restore-mark-quiesced)
            [ "$#" -ge 4 ] && [ "$1" = "$handoff_restore_candidate" ] \
                && [ "$2" = "${handoff_restore_task//-/}" ] || reject handoff-restore-step-scope ;;
        restore-db-create)
            [ "$#" -eq 5 ] || reject handoff-restore-database-create-arguments
            require_app "$1"; require_candidate "$1" "$2"
            require_restore_member "$3"; require_artifact_id "$4"; require_digest "$5" ;;
        database-restore-candidate | database-commit-candidate | database-recover-candidate | database-finish-candidate)
            [ "$#" -ge 6 ] || reject handoff-restore-database-arguments
            require_app "$1"; [ "$1" = "$2" ] || reject handoff-restore-database-credential-owner
            require_candidate "$1" "$3"; require_artifact_id "$4" ;;
        database-discard-original)
            [ "$#" -ge 6 ] || reject handoff-restore-database-arguments
            require_app "$1"; [ "$1" = "$2" ] || reject handoff-restore-database-credential-owner
            require_candidate "$1" "$3"; require_artifact_id "$4" ;;
        restore-finish | restore-abort)
            [ "$#" -eq 3 ] && [ "$1" = "$handoff_restore_candidate" ] && [ "$2" = "${handoff_restore_task//-/}" ] \
                || reject handoff-restore-finish-scope ;;
        *) reject handoff-restore-step-verb ;;
    esac
}

handoff_restore_step() {
    handoff_restore_step_identity "$@"
    handoff_restore_lock observe
    handoff_restore_assert
    [ ! -e "$handoff_restore_attempt" ] && [ ! -L "$handoff_restore_attempt" ] || reject handoff-restore-step-attempted
    [ ! -e "$handoff_restore_done" ] && [ ! -L "$handoff_restore_done" ] || reject handoff-restore-step-orphan
    case "$handoff_restore_verb" in database-*)
        assert_root_owned_regular "$(candidate_root "${handoff_restore_arguments[2]}")/.handoff-owner"
        [ "$(cat -- "$(candidate_root "${handoff_restore_arguments[2]}")/.handoff-owner")" = "$handoff_restore_owner" ] || reject handoff-restore-database-owner ;;
    esac
    printf '%s\n' "$handoff_restore_request" >"$handoff_restore_attempt"
    chown root:root -- "$handoff_restore_attempt"
    chmod 400 -- "$handoff_restore_attempt"
    local output
    case "$handoff_restore_verb" in
        restore-prepare) output="$(restore_prepare_component "${handoff_restore_arguments[@]}")" ;;
        restore-start-candidate) output="$(restore_start_candidate "${handoff_restore_arguments[@]}")" ;;
        restore-stop-candidate) output="$(restore_stop_candidate "${handoff_restore_arguments[@]}")" ;;
        restore-snapshot) output="$(handoff_restore_snapshot "${handoff_restore_arguments[@]}")" ;;
        restore-mark-quiesced) output="$(restore_mark_quiesced "${handoff_restore_arguments[@]}")" ;;
        restore-start-formal) output="$(restore_start_formal "${handoff_restore_arguments[@]}")" ;;
        restore-recover) output="$(restore_recover_component "${handoff_restore_arguments[@]}")" ;;
        restore-quiesce-recovery) output="$(handoff_restore_quiesce_recovery "${handoff_restore_arguments[@]}")" ;;
        restore-db-create) output="$(handoff_restore_database_create "${handoff_restore_arguments[@]}")" ;;
        database-restore-candidate) output="$(database_restore_candidate "${handoff_restore_arguments[@]}")" ;;
        database-commit-candidate)
            handoff_restore_database_quiesced "${handoff_restore_arguments[2]}"
            output="$(database_commit_candidate "${handoff_restore_arguments[@]}")" ;;
        database-recover-candidate) output="$(database_recover_candidate "${handoff_restore_arguments[@]}")" ;;
        database-finish-candidate) output="$(handoff_restore_database_finish "${handoff_restore_arguments[@]}")" ;;
        database-discard-original) output="$(handoff_restore_database_discard "${handoff_restore_arguments[@]}")" ;;
        restore-finish) output="$(handoff_restore_finish_component "${handoff_restore_arguments[@]}")" ;;
        restore-abort) output="$(handoff_restore_abort_component "${handoff_restore_arguments[@]}")" ;;
    esac
    printf '%s\n' "$output" >"$handoff_restore_done"
    chown root:root -- "$handoff_restore_done"
    chmod 400 -- "$handoff_restore_done"
    printf 'RESTORE_STEP=DONE\n%s\n' "$output"
}

handoff_restore_step_status() {
    handoff_restore_step_identity "$@"
    handoff_restore_lock observe
    handoff_restore_assert
    if [ ! -e "$handoff_restore_attempt" ] && [ ! -L "$handoff_restore_attempt" ]; then
        [ ! -e "$handoff_restore_done" ] && [ ! -L "$handoff_restore_done" ] || reject handoff-restore-step-orphan
        printf 'RESTORE_STEP=ABSENT\n'; return
    fi
    assert_root_owned_regular "$handoff_restore_attempt"
    [ "$(cat -- "$handoff_restore_attempt")" = "$handoff_restore_request" ] || reject handoff-restore-step-drift
    if [ ! -e "$handoff_restore_done" ] && [ ! -L "$handoff_restore_done" ]; then
        printf 'RESTORE_STEP=UNKNOWN\n'; return
    fi
    assert_root_owned_regular "$handoff_restore_done"
    printf 'RESTORE_STEP=DONE\n'
    cat -- "$handoff_restore_done"
}

handoff_restore_discard() {
    handoff_restore_identity "$@"
    handoff_restore_lock observe
    local receipt="$base_root/restore-operation-locks/.discarded-$handoff_restore_candidate"
    if [ -e "$receipt" ] || [ -L "$receipt" ]; then
        assert_root_owned_regular "$receipt"
        [ "$(cat -- "$receipt")" = "$handoff_restore_owner" ] || reject handoff-restore-discard-owner
        [ ! -e "$handoff_restore_root" ] && [ ! -L "$handoff_restore_root" ] || reject handoff-restore-discard-drift
        printf 'RESTORE_DISCARDED=1\n'; return
    fi
    handoff_restore_assert
    [ -z "$(find -P "$handoff_restore_root" -maxdepth 1 \( -name '.attempt-formal' -o -name '.attempt-formal-*' \) -print -quit)" ] \
        || reject handoff-restore-activation-attempted
    [ -z "$(find -P "$handoff_restore_root" -maxdepth 1 \( -name '.attempt-snapshot' -o -name '.attempt-snapshot-*' \) -print -quit)" ] \
        || reject handoff-restore-source-state-changed
    [ -z "$(find -P "$handoff_restore_root" -maxdepth 1 -name '.attempt-start-*' -print -quit)" ] \
        || reject handoff-restore-candidate-runtime-attempted
    rm -rf --one-file-system -- "$handoff_restore_root"
    (umask 077; set -o noclobber; printf '%s\n' "$handoff_restore_owner" >"$receipt") || reject handoff-restore-discard-receipt
    chown root:root -- "$receipt"; chmod 400 -- "$receipt"
    printf 'RESTORE_DISCARDED=1\n'
}

handoff_restore_discard_status() {
    handoff_restore_identity "$@"
    handoff_restore_lock observe
    local receipt="$base_root/restore-operation-locks/.discarded-$handoff_restore_candidate"
    if [ -e "$receipt" ] || [ -L "$receipt" ]; then
        assert_root_owned_regular "$receipt"
        [ "$(cat -- "$receipt")" = "$handoff_restore_owner" ] || reject handoff-restore-discard-owner
        [ ! -e "$handoff_restore_root" ] && [ ! -L "$handoff_restore_root" ] || reject handoff-restore-discard-drift
        printf 'RESTORE_DISCARDED=1\n'; return
    fi
    handoff_restore_assert
    printf 'RESTORE_DISCARDED=0\n'
}

