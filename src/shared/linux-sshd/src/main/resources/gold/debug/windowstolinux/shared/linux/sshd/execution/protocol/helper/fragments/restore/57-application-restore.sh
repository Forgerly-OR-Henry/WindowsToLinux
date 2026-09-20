restore_start_deployment_candidate() {
  local candidate="$1" token="$2" component="$3" root app port unit tmp
  root="$(restore_component_root "$candidate" "$component")"; assert_root_owned_directory "$root"
  mapfile -t identity < "$root/identity"; app="${identity[0]}"; current_application="$app"
  load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"
  [ "$runtime_identity_policy" = SYSTEMD_STATIC ] || reject restore-runtime-identity-policy
  restore_prepare_native_view "$candidate" "$token" "$component"
  deployment_root_override="$(app_root "$app")"; runtime_identity_override="$(restore_candidate_identity "$token" "$component")"
  application_backend=native; application_assert_inputs "$app"
  port="$(awk -F: 'NR==1 {print $3}' "$root/ports")"
  if [ -n "$port" ]; then require_service_port "$port"; deployment_service_port_override="$port"; deployment_bind_address_override=127.0.0.1
  elif [ "${#application_endpoints[@]}" -gt 0 ]; then assert_root_owned_regular "$root/previous"; fi
  unit="$(restore_unit_name "$token" "$component")"; tmp="$(mktemp /run/systemd/system/.windowstolinux-restore.XXXXXX)"
  render_deployment_unit "$app" "${deployment_runtime_parameters[@]}" > "$tmp"
  cat -- "$root/storage-unit" >> "$tmp"
  if [ "$application_mode" = ON_DEMAND ]; then
    application_entry="$application_verify_entry"; application_args=("${application_verify_args[@]}")
    command="$application_primary_command"; render_application_command "$application_runtime_kind" "$(app_root "$app")"; wrap_database_runtime
    printf '\n[Service]\nType=oneshot\nRestart=no\nRemainAfterExit=no\nExecStart=\nExecStart=%s\nTimeoutStartSec=%s\nStandardOutput=file:%s/verification-output\nLimitFSIZE=65536\n' "$command" "$application_health_timeout" "$root" >> "$tmp"
  fi
  find -P "$root/release" -type d -exec chmod 755 {} +
  find -P "$root/release" -type f -exec chmod a+r {} +
  install -o root -g root -m 600 -- "$tmp" "/run/systemd/system/$unit"; rm -f -- "$tmp"
  unset deployment_root_override deployment_service_port_override deployment_bind_address_override runtime_identity_override
  systemctl daemon-reload; systemctl start "$unit"; printf 'STARTED=1\nUNIT=%s\n' "$unit"
}

restore_application_health() {
  [ "$#" = 4 ] || reject restore-application-health-arguments
  local candidate="$1" token="$2" component="$3" root app kind status output endpoint mapping protocol host target selected address id exposure
  require_restore_token "$token"; require_app "$component"
  root="$(restore_component_root "$candidate" "$component")"; assert_root_owned_directory "$root"
  mapfile -t identity < "$root/identity"; app="${identity[0]}"; current_application="$app"
  local application_validation=1 application_restore_root="$root"
  application_admit_run "$app"
  kind="$(cat -- "$root/kind")"; application_release="$root/release"
  if [ "$kind" = deployment ]; then
    load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"; application_backend=native
    application_health_unit="$(restore_unit_name "$token" "$component")"
    local runtime_identity_override="$(restore_candidate_identity "$token" "$component")"
  else
    load_container_parameters "$root/release/.windowstolinux-container-parameters"; application_backend=container
    application_health_container="$(restore_container_name "$token" "$component")"
  fi
  parse_application_health_payload "$4"
  if [ "$application_mode" = ON_DEMAND ]; then
    if [ "$kind" = deployment ]; then
      [ "$(systemctl show --value --property ExecMainStatus "$application_health_unit")" = 0 ] || reject restore-verification-failed
      [ "$(systemctl show --value --property Result "$application_health_unit")" = success ] || reject restore-verification-failed
      [ "$(systemctl show --value --property MainPID "$application_health_unit")" = 0 ] || reject restore-verification-running
      assert_root_owned_regular "$root/verification-output"
      [ "$(stat -c %s -- "$root/verification-output")" -le 65536 ] || reject restore-verification-output
      output="$(cat -- "$root/verification-output")"
    else
      status="$(timeout --signal=TERM --kill-after=5 "$application_health_timeout" "$container_engine" wait "$application_health_container")" || reject restore-verification-timeout
      [ "$status" = 0 ] || reject restore-verification-failed
      output="$("$container_engine" logs "$application_health_container" | head -c 65537)" || reject restore-verification-output
      [ "${#output}" -le 65536 ] || reject restore-verification-output
    fi
    [[ "$output" = *"$application_expected_output"* && "$output" = *"$application_health_expected"* ]] || reject restore-verification-result
    printf 'HEALTHY=1\n'; return
  fi
  if [ "$application_health_kind" = UDP ]; then
    mapfile -t mappings < "$root/ports"
    for mapping in "${mappings[@]}"; do
      if [[ "$mapping" = "udp:$application_health_port:"* ]]; then application_health_port="${mapping##*:}"; break; fi
    done
  fi
  if [ "$kind" != deployment ]; then
    local spec protocol host target selected address id exposure index
    local -a candidate_ports=()
    mapfile -t mappings < "$root/ports"
    for spec in "${container_ports[@]}"; do
      protocol="${spec##*/}"; mapping="${spec%/*}"; target="${mapping##*:}"; mapping="${mapping%:*}"; host="${mapping##*:}"; selected="$host"
      for mapping in "${mappings[@]}"; do [[ "$mapping" != "$protocol:$host:"* ]] || selected="${mapping##*:}"; done
      candidate_ports+=("127.0.0.1:$selected:$target/$protocol")
    done
    container_ports=("${candidate_ports[@]}")
  fi
    local -a candidate_endpoints=()
    mapfile -t mappings < "$root/ports"
    for endpoint in "${application_endpoints[@]}"; do
      IFS='|' read -r id protocol address host target exposure <<< "$endpoint"; selected="$host"
      local transport=tcp; [ "$protocol" != UDP ] || transport=udp
      for mapping in "${mappings[@]}"; do [[ "$mapping" != "$transport:$host:"* ]] || selected="${mapping##*:}"; done
      if [ "$kind" != deployment ] || [ "$selected" != "$host" ]; then address=127.0.0.1; fi
      candidate_endpoints+=("$id|$protocol|$address|$selected|$target|$exposure")
    done
    application_endpoints=("${candidate_endpoints[@]}")
  application_health_loaded "$app"
}
