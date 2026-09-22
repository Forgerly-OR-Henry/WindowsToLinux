# Task source is never writable by a model-generated command.
agent_source_operation() {
    local program
    program="$(
        cat <<'WTL_AGENT_SOURCE'
# @compat:agent-source@
WTL_AGENT_SOURCE
    )"
    /usr/bin/python3 -I -c "$program" "$@"
}
agent_candidate() {
    assert_task_candidate "$1" "$2" "$3"
    agent_candidate_root="$(candidate_root "$2")"
    assert_root_owned_directory "$agent_candidate_root"
    assert_root_owned_regular "$agent_candidate_root/.agent-task"
    assert_workspace_volume "$agent_candidate_root"
}
agent_open() {
    [ "$#" -eq 4 ] || reject agent-open-arguments
    agent_freeze_app="$1"
    agent_freeze_candidate="$2"
    agent_candidate "$1" "$2" "$3"
    require_digest "$4"
    [ ! -e "$agent_candidate_root/.agent-mode" ] || reject agent-already-open
    stop_candidate_build "$1" "$2"
    mount -o remount,rw,nodev,nosuid -- "$agent_candidate_root/mutable" || reject workspace-remount
    trap 'freeze_candidate_build "$agent_freeze_app" "$agent_freeze_candidate"' EXIT
    agent_source_operation open "$agent_candidate_root/mutable/input-source" "$4"
    install -d -o root -g root -m 700 -- "$agent_candidate_root/mutable/source"
    printf 'protected-source\n' >"$agent_candidate_root/.agent-mode"
    chmod 400 -- "$agent_candidate_root/.agent-mode"
    freeze_candidate_build "$1" "$2"
    trap - EXIT
}
agent_assert_revision() {
    require_digest "$1"
    assert_root_owned_regular "$agent_candidate_root/.agent-mode"
    local actual
    actual="$(agent_source_operation digest "$agent_candidate_root/mutable/input-source" "$1")"
    [ "$actual" = "DIGEST=$1" ] || reject agent-source-revision
}
agent_read() {
    [ "$#" -eq 7 ] || reject agent-read-arguments
    agent_candidate "$1" "$2" "$3"
    agent_assert_revision "$4"
    agent_source_operation read "$agent_candidate_root/mutable/input-source" "$4" "$5" "$6" "$7"
}
agent_patch() {
    [ "$#" -eq 5 ] || reject agent-patch-arguments
    agent_freeze_app="$1"
    agent_freeze_candidate="$2"
    agent_candidate "$1" "$2" "$3"
    require_digest "$5"
    exec 9>"$agent_candidate_root/.agent-lock"
    flock -x 9
    stop_candidate_build "$1" "$2"
    agent_assert_revision "$4"
    rm -f -- "$agent_candidate_root/.sealed" "$agent_candidate_root/.agent-success" "$agent_candidate_root/.agent-artifact"
    mount -o remount,rw,nodev,nosuid -- "$agent_candidate_root/mutable" || reject workspace-remount
    trap 'freeze_candidate_build "$agent_freeze_app" "$agent_freeze_candidate"' EXIT
    agent_source_operation patch "$agent_candidate_root/mutable/input-source" "$4"
    printf '%s\n' "$5" >>"$agent_candidate_root/.agent-patches"
    chmod 400 -- "$agent_candidate_root/.agent-patches"
    freeze_candidate_build "$1" "$2"
    trap - EXIT
}
agent_run() {
    [ "$#" -eq 7 ] || reject agent-run-arguments
    local app="$1" candidate="$2" task="$3" revision="$4" seconds="$5" output="$6" engine="$7" status=0
    agent_candidate "$app" "$candidate" "$task"
    exec 9>"$agent_candidate_root/.agent-lock"
    flock -x 9
    agent_assert_revision "$revision"
    case "$engine" in ordinary | docker | podman) ;; *) reject agent-engine ;; esac
    printf '%s\n' "$engine" >"$agent_candidate_root/.agent-engine"
    chmod 400 -- "$agent_candidate_root/.agent-engine"
    rm -f -- "$agent_candidate_root/.sealed" "$agent_candidate_root/.agent-success" "$agent_candidate_root/.agent-artifact"
    run_restricted_build "$app" "$candidate" "$seconds" 256 2048 "$output" "$engine" || status=$?
    agent_assert_revision "$revision"
    if [ "$status" -eq 0 ]; then
        printf '%s\n' "$revision" >"$agent_candidate_root/.agent-success"
        chmod 400 -- "$agent_candidate_root/.agent-success"
    fi
    return "$status"
}
agent_seal() {
    [ "$#" -eq 4 ] || reject agent-seal-arguments
    agent_candidate "$1" "$2" "$3"
    exec 9>"$agent_candidate_root/.agent-lock"
    flock -x 9
    stop_candidate_build "$1" "$2"
    agent_assert_revision "$4"
    assert_sealed_build "$1" "$2"
    assert_root_owned_regular "$agent_candidate_root/.agent-success"
    [ "$(cat -- "$agent_candidate_root/.agent-success")" = "$4" ] || reject agent-build-revision
    local digest
    assert_root_owned_regular "$agent_candidate_root/.agent-engine"
    case "$(cat -- "$agent_candidate_root/.agent-engine")" in
        ordinary) digest="$(agent_source_operation digest "$agent_candidate_root/mutable/source" "$4")" ;;
        docker | podman)
            assert_root_owned_regular "$agent_candidate_root/mutable/candidate-image.tar"
            digest="$(sha256sum -- "$agent_candidate_root/mutable/candidate-image.tar")"
            digest="DIGEST=${digest%% *}"
            ;;
        *) reject agent-engine ;;
    esac
    printf '%s\n' "$digest" >"$agent_candidate_root/.agent-artifact"
    chmod 400 -- "$agent_candidate_root/.agent-artifact"
    printf '%s\n' "$digest"
}
