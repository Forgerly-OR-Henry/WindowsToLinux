application_field() {
  [ "$application_index" -lt "${#application_fields[@]}" ] || reject application-payload-truncated
  application_value="${application_fields[$application_index]}"; application_index=$((application_index + 1))
  [ "${#application_value}" -le 8192 ] && [[ "$application_value" != *$'\n'* && "$application_value" != *$'\r'* ]] || reject application-field
}
application_number() {
  application_field
  [[ "$application_value" =~ ^[0-9]{1,5}$ ]] && [ "$application_value" -ge "$1" ] && [ "$application_value" -le "$2" ] || reject application-number
}
application_relative() {
  [ -z "$1" ] && return
  [ "${#1}" -le 512 ] && [[ "$1" != /* ]] || reject application-relative-path
  python3 -I -c 'import sys; p=sys.argv[1]; assert all(c.isalnum() or c in "_./ -" for c in p) and all(s and s!=".." for s in p.split("/"))' "$1" || reject application-relative-path
}
application_absolute() {
  [ "${#1}" -le 512 ] && [[ "$1" = /* && "$1" != / ]] || reject application-absolute-path
  python3 -I -c 'import sys; p=sys.argv[1]; assert all(c.isalnum() or c in "_./ -" for c in p) and all(s and s not in ("..",".") for s in p[1:].split("/"))' "$1" || reject application-absolute-path
}
application_command_fields() {
  application_field; application_command_entry="$application_value"; application_relative "$application_command_entry"
  application_number 0 64; local count="$application_value"
  application_command_args=()
  while [ "$count" -gt 0 ]; do
    application_field; [ "${#application_value}" -le 4096 ] || reject application-argument-size
    application_command_args+=("$application_value"); count=$((count - 1))
  done
}
parse_application_payload() {
  application_payload="$1"
  [ "${#application_payload}" -le 87384 ] && [[ "$application_payload" =~ ^[A-Za-z0-9+/]*={0,2}$ ]] || reject application-payload
  mapfile -d '' -t application_fields < <(printf '%s' "$application_payload" | base64 --decode)
  [ "$(printf '%s\0' "${application_fields[@]}" | base64 -w0)" = "$application_payload" ] || reject application-payload-encoding
  application_index=0; application_field; local application_format="$application_value"
  case "$application_format" in application-v1|application-v2) ;; *) reject unsupported-application-format ;; esac
  application_field; application_mode="$application_value"
  case "$application_mode" in DAEMON|ON_DEMAND) ;; *) reject application-mode ;; esac
  application_field; [ "$application_value" = 1 ] || reject application-review-required
  application_field; application_workdir="$application_value"; application_relative "$application_workdir"
  application_field; application_builddir="$application_value"; application_relative "$application_builddir"
  application_command_fields; application_entry="$application_command_entry"; application_args=("${application_command_args[@]}")
  application_number 0 1; application_has_verification="$application_value"; application_verify_entry=; application_verify_args=()
  if [ "$application_has_verification" = 1 ]; then application_command_fields; application_verify_entry="$application_command_entry"; application_verify_args=("${application_command_args[@]}"); fi
  [ "$application_mode" != ON_DEMAND ] || [ "$application_has_verification" = 1 ] || reject application-verification-required
  application_field; application_expected_output="$application_value"
  application_number 0 1; application_has_client="$application_value"; application_client_entry=; application_client_args=()
  if [ "$application_has_client" = 1 ]; then application_command_fields; application_client_entry="$application_command_entry"; application_client_args=("${application_command_args[@]}"); fi
  application_number 0 32; local count="$application_value" id protocol address port target exposure url old
  application_endpoints=(); application_port_keys=(); application_endpoint_ids=()
  while [ "$count" -gt 0 ]; do
    application_field; id="$application_value"; require_app "$id"; application_endpoint_ids+=("$id")
    application_field; protocol="$application_value"; case "$protocol" in HTTP|HTTPS|TCP|UDP) ;; *) reject application-protocol ;; esac
    application_field; address="$application_value"; [[ "$address" =~ ^[0-9a-fA-F:.]{2,64}$ ]] || reject application-address
    python3 -c 'import ipaddress,sys; ipaddress.ip_address(sys.argv[1])' "$address" || reject application-address
    application_number 1 65535; port="$application_value"; application_number 1 65535; target="$application_value"
    application_field; exposure="$application_value"; case "$exposure" in INTERNAL|EXTERNAL) ;; *) reject application-exposure ;; esac
    application_field; url="$application_value"; [ "${#url}" -le 2048 ] || reject application-url
    local transport=tcp; [ "$protocol" != UDP ] || transport=udp
    for old in "${application_port_keys[@]}"; do [ "$old" != "$transport:$port" ] || reject application-port-conflict; done
    application_port_keys+=("$transport:$port"); application_endpoints+=("$id|$protocol|$address|$port|$target|$exposure")
    count=$((count - 1))
  done
  [ "$application_mode" != ON_DEMAND ] || [ "${#application_endpoints[@]}" = 0 ] || reject application-on-demand-endpoints
  application_number 0 32; count="$application_value"; application_input_ids=(); application_input_sources=(); application_input_targets=()
  while [ "$count" -gt 0 ]; do
    application_field; id="$application_value"; require_app "$id"; application_input_ids+=("$id")
    application_field; application_absolute "$application_value"; application_input_sources+=("$application_value")
    application_field; application_absolute "$application_value"; application_input_targets+=("$application_value")
    count=$((count - 1))
  done
  application_number 0 32; count="$application_value"; application_companion_ids=(); application_companion_sources=(); application_companion_artifacts=(); application_companion_environments=()
  while [ "$count" -gt 0 ]; do
    application_field; require_app "$application_value"; application_companion_ids+=("$application_value")
    application_field; [ -n "$application_value" ] || reject application-companion-source; application_relative "$application_value"; application_companion_sources+=("$application_value")
    application_field; [ "$application_value" = CMAKE_SERVICE ] || reject application-companion-type
    application_field; [ -n "$application_value" ] || reject application-companion-artifact; application_relative "$application_value"; application_companion_artifacts+=("$application_value")
    application_field; [[ "$application_value" =~ ^[A-Z][A-Z0-9_]{0,63}$ ]] || reject application-companion-environment; case "$application_value" in PATH|HOME|SHELL|ENV|BASH_ENV|TMPDIR|LD_*|PYTHON*|RUBY*|GEM_*|BUNDLE_*|JAVA*|NODE_*|WINDOWSTOLINUX_*) reject application-companion-environment ;; esac
    for old in "${application_companion_environments[@]}"; do [ "$old" != "$application_value" ] || reject application-companion-environment; done
    application_companion_environments+=("$application_value")
    count=$((count - 1))
  done
  application_worker_ids=(); application_worker_entries=(); application_worker_counts=(); application_worker_arguments=()
  if [ "$application_format" = application-v2 ]; then
    [ "$application_mode" = DAEMON ] || reject application-workers-require-daemon
    application_number 1 8; count="$application_value"
    while [ "$count" -gt 0 ]; do
      application_field; require_app "$application_value"; application_worker_ids+=("$application_value")
      application_command_fields; [ -n "$application_command_entry" ] || reject application-worker-entry
      application_worker_entries+=("$application_command_entry"); application_worker_counts+=("${#application_command_args[@]}")
      application_worker_arguments+=("${application_command_args[@]}"); count=$((count - 1))
    done
  fi
  local resources duplicate
  for resources in application_endpoint_ids application_input_ids application_input_targets application_companion_ids application_worker_ids; do
    local -n entries="$resources"
    duplicate="$(printf '%s\n' "${entries[@]}" | LC_ALL=C sort | uniq -d)"; [ -z "$duplicate" ] || reject application-duplicate-resource
  done
  parse_application_health
  [ "$application_index" = "${#application_fields[@]}" ] || reject application-payload-trailing
}
parse_application_health() {
  application_field; application_health_kind="$application_value"; application_number 1 300; application_health_timeout="$application_value"
  application_health_port=0; application_health_entry=; application_health_args=(); application_health_request=; application_health_response=
  case "$application_health_kind" in
    HTTP) application_field; application_health_url="$application_value"; application_number 200 399; application_health_status="$application_value" ;;
    TCP) application_number 1 65535; application_health_port="$application_value"; application_number 1 300; application_health_stability="$application_value" ;;
    PROCESS) application_number 1 "$application_health_timeout"; application_health_stability="$application_value" ;;
    COMMAND) application_command_fields; application_health_entry="$application_command_entry"; application_health_args=("${application_command_args[@]}"); application_field; application_health_expected="$application_value" ;;
    UDP)
      application_number 1 65535; application_health_port="$application_value"
      application_field; application_health_request="$application_value"; application_field; application_health_response="$application_value"
      application_number 0 1; application_health_custom="$application_value"
      if [ "$application_health_custom" = 1 ]; then
        [ -z "$application_health_request$application_health_response" ] || reject application-udp-mixed
        application_command_fields; application_health_entry="$application_command_entry"; application_health_args=("${application_command_args[@]}")
      else
        [[ "$application_health_request" =~ ^([0-9a-f]{2})+$ && "$application_health_response" =~ ^([0-9a-f]{2})+$ ]] || reject application-udp-probe
      fi ;;
    *) reject application-health-kind ;;
  esac
}

parse_application_health_payload() {
  local payload="$1"
  [ "${#payload}" -le 87384 ] && [[ "$payload" =~ ^[A-Za-z0-9+/]*={0,2}$ ]] || reject application-health-payload
  mapfile -d '' -t application_fields < <(printf '%s' "$payload" | base64 --decode)
  [ "$(printf '%s\0' "${application_fields[@]}" | base64 -w0)" = "$payload" ] || reject application-health-encoding
  application_index=0; application_field; [ "$application_value" = health-v1 ] || reject application-health-version
  parse_application_health
  [ "$application_index" = "${#application_fields[@]}" ] || reject application-health-trailing
  if [ "$application_mode" = ON_DEMAND ]; then
    [ "$application_health_kind" = COMMAND ] && [ "$application_health_entry" = "$application_verify_entry" ] \
      && [ "$(printf '%s\0' "${application_health_args[@]}" | base64 -w0)" = "$(printf '%s\0' "${application_verify_args[@]}" | base64 -w0)" ] \
      || reject application-installation-verification-mismatch
  fi
}
