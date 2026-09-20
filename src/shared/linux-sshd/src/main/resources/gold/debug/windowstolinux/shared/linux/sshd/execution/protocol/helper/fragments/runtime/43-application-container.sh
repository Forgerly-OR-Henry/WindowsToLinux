application_container_cleanup() {
  local status="$1" containers
  trap - EXIT HUP INT TERM
  if [ -n "${application_job_container:-}" ]; then
    "$container_engine" stop --time 15 "$application_job_container" >/dev/null 2>&1 || true
    "$container_engine" rm --force "$application_job_container" >/dev/null 2>&1 || true
    containers="$("$container_engine" ps --all --format '{{.Names}}')" || { printf 'MANAGED_HELPER_REJECT=application-container-cleanup-unverified\n' >&2; exit 64; }
    if printf '%s\n' "$containers" | grep -Fxq "$application_job_container"; then
      printf 'MANAGED_HELPER_REJECT=application-container-cleanup-unverified\n' >&2; exit 64
    fi
  fi
  if [ -n "${application_job_record:-}" ]; then rm -f -- "$application_job_record"; fi
  if [ -n "${application_validation_root:-}" ]; then
    case "$application_validation_root" in "$base_root/application-locks/$current_application/verify."*) rm -rf --one-file-system -- "$application_validation_root" ;; *) exit 64 ;; esac
  fi
  exit "$status"
}
run_application_container() {
  local app="$1" operation="$2" image runtime_user entry spec index=0 secret secret_name destination status=0 source mode
  shift 2
  image="$(container_image "$app" "${application_release##*/}")"; runtime_user="$(container_nonroot_user "$container_engine" "$image")"
  prepare_container_storage "$app" "$image" "$application_release" "$runtime_user"; container_storage_mounts "$image"
  local -a args command_args expected_mounts=()
  case "$operation" in
    run) entry="$application_entry"; command_args=("${application_args[@]}" "$@") ;;
    client) entry="$application_client_entry"; command_args=("${application_client_args[@]}" "$@") ;;
    verify) entry="$application_verify_entry"; command_args=("${application_verify_args[@]}") ;;
    probe) entry="$application_health_entry"; command_args=("${application_health_args[@]}") ;;
    *) reject application-operation ;;
  esac
  application_job_container="windowstolinux-job-$app-$(cat /proc/sys/kernel/random/uuid)"
  application_job_record="$(application_lock_directory "$app")/job"; printf '%s\n' "$container_engine" "$application_job_container" > "$application_job_record"
  trap 'application_container_cleanup $?' EXIT
  trap 'application_container_cleanup 129' HUP
  trap 'application_container_cleanup 130' INT
  trap 'application_container_cleanup 143' TERM
  args=("$container_engine" create --interactive --name "$application_job_container" --user "$runtime_user" --security-opt no-new-privileges --cap-drop ALL
    --read-only --tmpfs /tmp:rw,nosuid,nodev,mode=1777 --tmpfs /run:rw,nosuid,nodev,mode=755
    --memory 1g --pids-limit 256 --env-file "$(configuration_path "$app" "$deployment_configuration_digest" container)")
  if [ -n "$application_workdir" ]; then
    destination="$(container_binding_destination "$image" "$application_workdir")"; args+=(--workdir "$destination")
  fi
  application_container_argv "$image" "$entry" "${command_args[@]}"
  args+=(--entrypoint "${application_exec[0]}")
  command_args=("${application_exec[@]:1}")
  if [ "$operation" = verify ]; then
    application_validation_root="$(mktemp -d "$base_root/application-locks/$app/verify.XXXXXXXX")"; chmod 755 -- "$application_validation_root"
  fi
  for spec in "${container_bind_mounts[@]}"; do
    source="${spec%%:*}"; destination="${spec#*:}"; mode="${destination##*:}"; destination="${destination%:*}"
    if [ "$operation" = verify ] && [ "$mode" = rw ]; then
      local copy="$application_validation_root/binding-$index"
      install -d -m 700 -- "$copy"
      local binding_spec database_source=0
      for binding_spec in "${managed_data_bindings[@]}"; do
        managed_binding_parts "$binding_spec"
        if [ "$storage_kind" = DATABASE ] && [ "$(managed_data_binding_root "$binding")" = "$source" ]; then
          sqlite3 "$source/$database_file" ".backup '$copy/$database_file'" || reject application-verification-database
          database_source=1; break
        fi
      done
      if [ "$database_source" = 0 ]; then cp -a --no-preserve=ownership -- "$source/." "$copy/"; fi
      # The admission lock prevents a managed writer during this private validation copy.
      chown -R "$runtime_user" -- "$copy"; source="$copy"; index=$((index+1))
    fi
    args+=(--volume "$source:$destination:$mode,Z"); expected_mounts+=("$source:$destination:$mode")
  done
  index=0
  while [ "$index" -lt "${#application_input_sources[@]}" ]; do
    args+=(--mount "type=bind,source=${application_input_sources[$index]},destination=${application_input_targets[$index]},readonly")
    expected_mounts+=("${application_input_sources[$index]}:${application_input_targets[$index]}:ro")
    index=$((index+1))
  done
  index=0
  while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
    secret_name="${deployment_secret_names[$index]}"; destination="/run/secrets/$secret_name"
    secret="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
    secret="$(container_secret_delivery "$app" "${application_release##*/}" "$runtime_user" "$secret" "$secret_name")"
    args+=(--mount "type=bind,source=$secret,destination=$destination,readonly" --env "$secret_name=$destination"); expected_mounts+=("$secret:$destination:ro"); index=$((index+1))
  done
  args+=("$image" "${command_args[@]}"); "${args[@]}" >/dev/null || reject application-container-create
  # Inspect before executing untrusted image code; validation substitutes only owned private copies.
  application_assert_exact_mounts "$application_job_container" "${expected_mounts[@]}"
  if [ "$operation" = verify ] || [ "$operation" = probe ]; then
    timeout --signal=TERM --kill-after=15 "$application_health_timeout" "$container_engine" start --attach --interactive "$application_job_container" || status=$?
  else "$container_engine" start --attach --interactive "$application_job_container" || status=$?; fi
  [ "$status" -ne 0 ] || status="$("$container_engine" inspect --format '{{.State.ExitCode}}' "$application_job_container")"
  [[ "$status" =~ ^[0-9]{1,3}$ ]] && [ "$status" -le 255 ] || reject application-container-exit
  return "$status"
}
