require_root
[ "$#" -ge 1 ] || reject verb
verb="$1"
shift
case "$verb" in
  podman-cni-forward|podman-cni-clear) ;;
  *) require_deployer ;;
esac
case "$verb" in
  probe)
    [ "$#" -eq 0 ] || reject probe-arguments
    printf 'HELPER=1\nPROTOCOL=%s\n' "$helper_protocol"
    ;;
  candidate-create) create_candidate "$@" ;;
  candidate-cleanup) cleanup_candidate "$@" ;;
  stage-config) stage_configuration "$@" ;;
  stage-secret) stage_secret "$@" ;;
  database-inspect) database_inspect "$@" ;;
  database-export) database_export "$@" ;;
  database-stage-artifact) database_stage_artifact "$@" ;;
  database-read-artifact) database_read_artifact "$@" ;;
  database-discard-artifact) database_discard_artifact "$@" ;;
  database-restore-candidate) database_restore_candidate "$@" ;;
  database-discard-candidate) database_discard_candidate "$@" ;;
  backup-create) backup_create_artifact "$@" ;;
  backup-read) backup_read_artifact "$@" ;;
  backup-discard) backup_discard_operation "$@" ;;
  restore-preflight) restore_preflight "$@" ;;
  restore-prepare) restore_prepare_component "$@" ;;
  restore-start-candidate) restore_start_candidate "$@" ;;
  restore-stop-candidate) restore_stop_candidate "$@" ;;
  restore-snapshot) restore_snapshot_current "$@" ;;
  restore-start-formal) restore_start_formal "$@" ;;
  restore-recover) restore_recover_component "$@" ;;
  snapshot-deployment) snapshot_deployment "$@" ;;
  publish-deployment) publish_deployment "$@" ;;
  retain) retain_releases "$@" ;;
  rollback-deployment) rollback_deployment "$@" ;;
  rollback-deployment-first) rollback_deployment_first "$@" ;;
  lifecycle) lifecycle "$@" ;;
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
