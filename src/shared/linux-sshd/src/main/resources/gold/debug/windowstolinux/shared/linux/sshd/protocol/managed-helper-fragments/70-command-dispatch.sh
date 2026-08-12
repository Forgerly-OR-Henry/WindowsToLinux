require_root
require_deployer
[ "$#" -ge 1 ] || reject verb
verb="$1"
shift
case "$verb" in
  probe)
    [ "$#" -eq 0 ] || reject probe-arguments
    printf 'HELPER=1\n'
    ;;
  candidate-create) create_candidate "$@" ;;
  candidate-cleanup) cleanup_candidate "$@" ;;
  stage-config) stage_configuration "$@" ;;
  stage-secret) stage_secret "$@" ;;
  snapshot) create_snapshot "$@" ;;
  snapshot-deployment) snapshot_deployment "$@" ;;
  publish) publish_release "$@" ;;
  publish-deployment) publish_deployment "$@" ;;
  retain) retain_releases "$@" ;;
  rollback-previous) rollback_previous "$@" ;;
  rollback-first) rollback_first "$@" ;;
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
  *) reject verb ;;
esac
