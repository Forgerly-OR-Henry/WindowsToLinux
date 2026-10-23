require_root
[ "$#" -ge 1 ] || reject verb
verb="$1"
shift
case "$verb" in
    podman-cni-forward | podman-cni-clear) ;;
    *) require_deployer ;;
esac
case "$verb" in
    publish-deployment | publish-container | rollback-deployment | rollback-deployment-first | rollback-container | rollback-container-first | retain)
        application_maintenance "${1:-}"
        application_operation_token=
        case "$verb" in
            publish-*)
                require_digest "$3"
                application_operation_token="deployment-$3"
                ;;
            rollback-*)
                require_digest "$2"
                application_operation_token="deployment-$2"
                ;;
            retain) application_operation_token="deployment-$(basename -- "$(readlink -f -- "$(app_root "$1")/current")")" ;;
        esac
        application_marker="$(application_lock_directory "$1")/maintenance"
        if [ -e "$application_marker" ]; then
            assert_root_owned_regular "$application_marker"
            [ "$(cat -- "$application_marker")" = "$application_operation_token" ] || reject application-maintenance-in-progress
        elif [[ "$verb" = publish-* ]]; then
            printf '%s\n' "$application_operation_token" >"$application_marker"
            chmod 600 -- "$application_marker"
        fi
        ;;
esac
case "$verb" in
    application-maintenance) application_maintenance_control "$@" ;;
    application-health) application_health "$@" ;;
    app-run) application_job "$1" "$2" run "${@:3}" ;;
    app-client) application_job "$1" "$2" client "${@:3}" ;;
    probe)
        [ "$#" -eq 0 ] || reject probe-arguments
        printf 'HELPER=1\nPROTOCOL=%s\n' "$helper_protocol"
        ;;
    workspace-recover) recover_workspaces "$@" ;;
    candidate-create) create_candidate "$@" ;;
    candidate-create-task) create_task_candidate "$@" ;;
    candidate-query-task) query_task_candidate "$@" ;;
    candidate-cleanup-task) cleanup_task_candidate "$@" ;;
    candidate-restore-create) create_restore_candidate "$@" ;;
    agent-open) agent_open "$@" ;;
    agent-read) agent_read "$@" ;;
    agent-patch) agent_patch "$@" ;;
    agent-run) agent_run "$@" ;;
    agent-seal) agent_seal "$@" ;;
    build-preflight) preflight_build "$@" ;;
    build-run) run_restricted_build "$@" ;;
    build-freeze) freeze_candidate_build "$@" ;;
    build-identity) prepare_build_identity "$@" ;;
    build-stop) stop_candidate_build "$@" ;;
    candidate-cleanup) cleanup_candidate "$@" ;;
    stage-config) stage_configuration "$@" ;;
    stage-secret) stage_secret "$@" ;;
    native-db) native_database "$@" ;;
    database-inspect) database_inspect "$@" ;;
    database-export) database_export "$@" ;;
    database-stage-artifact) database_stage_artifact "$@" ;;
    database-read-artifact) database_read_artifact "$@" ;;
    database-discard-artifact) database_discard_artifact "$@" ;;
    database-restore-candidate) database_restore_candidate "$@" ;;
    database-commit-candidate) database_commit_candidate "$@" ;;
    database-recover-candidate) database_recover_candidate "$@" ;;
    database-discard-candidate) database_discard_candidate "$@" ;;
    handoff-database-export) handoff_database_export "$@" ;;
    handoff-database-status) handoff_database_status "$@" ;;
    handoff-database-discard) handoff_database_discard "$@" ;;
    backup-create) backup_create_artifact "$@" ;;
    backup-read) backup_read_artifact "$@" ;;
    backup-discard) backup_discard_operation "$@" ;;
    handoff-backup-status) handoff_backup_status "$@" ;;
    handoff-maintenance-status) handoff_maintenance_status "$@" ;;
    handoff-operation-close) handoff_operation_close "$@" ;;
    handoff-operation-close-status) handoff_operation_close_status "$@" ;;
    handoff-restore-preflight) handoff_restore_preflight "$@" ;;
    handoff-restore-create) handoff_restore_create "$@" ;;
    handoff-restore-file) handoff_restore_file "$@" ;;
    handoff-restore-step) handoff_restore_step "$@" ;;
    handoff-restore-step-status) handoff_restore_step_status "$@" ;;
    handoff-restore-discard) handoff_restore_discard "$@" ;;
    handoff-restore-discard-status) handoff_restore_discard_status "$@" ;;
    handoff-restore-cleanup) handoff_restore_cleanup "$@" ;;
    handoff-restore-cleanup-status) handoff_restore_cleanup_status "$@" ;;
    restore-preflight) restore_preflight "$@" ;;
    restore-prepare) restore_prepare_component "$@" ;;
    restore-application-health) restore_application_health "$@" ;;
    restore-start-candidate) restore_start_candidate "$@" ;;
    restore-stop-candidate) restore_stop_candidate "$@" ;;
    restore-mark-quiesced) restore_mark_quiesced "$@" ;;
    restore-quiesce-recovery) restore_quiesce_recovery "$@" ;;
    restore-snapshot) restore_snapshot_current "$@" ;;
    restore-start-formal) restore_start_formal "$@" ;;
    restore-recover) restore_recover_component "$@" ;;
    activate-retained) activate_retained "$@" ;;
    rollback-retained) rollback_retained "$@" ;;
    finish-retained) finish_retained "$@" ;;
    retained-status) retained_status "$@" ;;
    managed-current-status) managed_current_status "$@" ;;
    managed-target-status) managed_target_status "$@" ;;
    snapshot-retained) snapshot_retained "$@" ;;
    snapshot-deployment) snapshot_deployment "$@" ;;
    snapshot-deployment-attempt) snapshot_deployment_attempt "$@" ;;
    snapshot-container-attempt) snapshot_container_attempt "$@" ;;
    publication-original-status) publication_original_status "$@" ;;
    publication-original-status-attempt) publication_original_status_attempt "$@" ;;
    snapshot-discard-task) discard_original_snapshot "$@" ;;
    snapshot-discard-attempt) discard_original_snapshot_attempt "$@" ;;
    publish-deployment) publish_deployment "$@" ;;
    prepare-toolchains)
        case "${1:-}" in prepare | bind | verify | prepare-tracked | bind-tracked | observe-tracked | cleanup-tracked | prepare-diagnosed | diagnose-only) prepare_official_toolchains "$@" ;; *) reject toolchain-verb ;; esac
        ;;
    retain) retain_releases "$@" ;;
    rollback-deployment) rollback_deployment "$@" ;;
    rollback-deployment-first) rollback_deployment_first "$@" ;;
    inspect-runtime) inspect_managed_runtime "$@" ;;
    lifecycle-deployment) lifecycle_deployment "$@" ;;
    observe-deployment) observe_deployment "$@" ;;
    snapshot-container) snapshot_container "$@" ;;
    publish-container) publish_container "$@" ;;
    rollback-container) rollback_container "$@" ;;
    rollback-container-first) rollback_container_first "$@" ;;
    lifecycle-container) lifecycle_container "$@" ;;
    podman-cni-forward) podman_cni_forward "$@" ;;
    podman-cni-clear) podman_cni_clear "$@" ;;
    *) reject verb ;;
esac
