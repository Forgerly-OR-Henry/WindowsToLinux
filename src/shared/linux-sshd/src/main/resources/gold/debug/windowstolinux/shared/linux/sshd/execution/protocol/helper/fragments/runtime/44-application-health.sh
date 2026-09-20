application_process_identity() {
  if [ "$application_backend" = native ]; then
    local pid state
    state="$(systemctl is-active "${application_health_unit:-$(unit_name "$current_application")}" 2>/dev/null)" || return 1
    [ "$state" = active ] || return 1
    pid="$(systemctl show --value --property MainPID "${application_health_unit:-$(unit_name "$current_application")}")"
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] && [ -r "/proc/$pid/stat" ] || return 1
    printf '%s:%s' "$pid" "$(systemctl show --value --property ExecMainStartTimestampMonotonic "${application_health_unit:-$(unit_name "$current_application")}")"
  else
    "$container_engine" inspect --format '{{if .State.Running}}{{.State.Pid}}:{{.State.StartedAt}}{{end}}' "${application_health_container:-$(container_name "$current_application")}" | grep -E '^[1-9][0-9]*:'
  fi
}
application_udp_owned() {
  local pid group line listener target="$application_health_port" transport=tcp flags=-ltnp
  if [ "$application_health_kind" = UDP ]; then transport=udp; flags=-lunp; fi
  if [ "$application_backend" = container ]; then
    assert_container_ports "${application_health_container:-$(container_name "$current_application")}"
    local endpoint id protocol address host exposure
    for endpoint in "${application_endpoints[@]}"; do
      IFS='|' read -r id protocol address host listener exposure <<< "$endpoint"
      if { [ "$protocol" = UDP ] && [ "$transport" = udp ] || [ "$protocol" != UDP ] && [ "$transport" = tcp ]; } && [ "$host" = "$application_health_port" ]; then target="$listener"; break; fi
    done
    [ "$host" = "$application_health_port" ] || return 1
    pid="$("$container_engine" inspect --format '{{.State.Pid}}' "${application_health_container:-$(container_name "$current_application")}")"
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
    nsenter --target "$pid" --net ss -H "${flags%p}" | awk -v port="$target" '$4 ~ ":" port "$" { found=1 } END { exit !found }'
    return
  fi
  group="$(systemctl show --value --property ControlGroup "${application_health_unit:-$(unit_name "$current_application")}")"
  [ -n "$group" ] || return 1
  while IFS= read -r line; do
    for pid in $(printf '%s' "$line" | grep -oE 'pid=[0-9]+' | cut -d= -f2); do
      if awk -F: -v group="$group" '$3 == group || index($3, group "/") == 1 { found=1 } END { exit !found }' "/proc/$pid/cgroup"; then return 0; fi
    done
  done < <(ss -H "$flags" | awk -v port="$target" '$4 ~ ":" port "$"')
  return 1
}
application_udp_response() {
  local endpoint id protocol address host target exposure probe_address=127.0.0.1
  for endpoint in "${application_endpoints[@]}"; do
    IFS='|' read -r id protocol address host target exposure <<< "$endpoint"
    if [ "$protocol" = UDP ] && [ "$host" = "$application_health_port" ]; then
      case "$address" in 0.0.0.0) probe_address=127.0.0.1 ;; ::) probe_address=::1 ;; *) probe_address="$address" ;; esac; break
    fi
  done
  python3 -I - "$application_health_port" "$application_health_request" "$application_health_response" "$probe_address" <<'WTL_UDP_PROBE'
import socket, sys
with socket.socket(socket.AF_INET6 if ':' in sys.argv[4] else socket.AF_INET, socket.SOCK_DGRAM) as client:
    client.settimeout(2)
    client.connect((sys.argv[4], int(sys.argv[1])))
    client.send(bytes.fromhex(sys.argv[2]))
    if client.recv(8193) != bytes.fromhex(sys.argv[3]):
        raise SystemExit(1)
WTL_UDP_PROBE
}
application_health() {
  [ "$#" = 3 ] || reject application-health-arguments
  local application_validation=1
  application_admit_run "$1"; application_load_current "$1" "$2"; application_assert_inputs "$1"
  parse_application_health_payload "$3"
  application_health_loaded "$1"
}
application_health_loaded() {
  local initial current deadline=$((SECONDS + application_health_timeout)) stable="$SECONDS" output expected
  if [ "$application_mode" = ON_DEMAND ] || [ "$application_health_kind" = COMMAND ]; then
    if [ "$application_mode" != ON_DEMAND ]; then
      application_verify_entry="$application_health_entry"; application_verify_args=("${application_health_args[@]}")
      expected="$application_health_expected"
    else expected="$application_health_expected"; fi
    # Subshell owns its cleanup traps. Output stays bounded; SIGPIPE fails validation on overflow.
    if [ "$application_backend" = native ]; then
      output="$(run_application_native "$1" verify | head -c 65537)" || reject application-verification-failed
    elif [ -n "${application_restore_root:-}" ]; then output="$(application_container_candidate_probe verify | head -c 65537)" || reject application-verification-failed
    else output="$(run_application_container "$1" verify | head -c 65537)" || reject application-verification-failed; fi
    [ "${#output}" -le 65536 ] && [[ "$output" = *"$expected"* ]] || reject application-verification-result
    [ "$application_mode" != ON_DEMAND ] || [[ "$output" = *"$application_expected_output"* ]] || reject application-verification-result
    printf 'HEALTHY=1\n'; return
  fi
  if [ "$application_health_kind" = HTTP ]; then
    application_health_port="$(python3 -I -c 'import sys,urllib.parse; u=urllib.parse.urlsplit(sys.argv[1]); assert u.hostname in ("localhost","127.0.0.1","::1") and u.scheme in ("http","https"); print(u.port or (443 if u.scheme=="https" else 80))' "$application_health_url")" || reject application-health-url
  fi
  initial="$(application_process_identity)" || initial=
  while [ "$SECONDS" -lt "$deadline" ]; do
    current="$(application_process_identity)" || current=
    if [ -z "$current" ] || [ "$current" != "$initial" ]; then initial="$current"; stable="$SECONDS"; fi
    case "$application_health_kind" in
      PROCESS) if [ -n "$current" ] && [ "$((SECONDS - stable))" -ge "$application_health_stability" ]; then printf 'HEALTHY=1\n'; return; fi ;;
      TCP)
        if [ -n "$current" ] && application_udp_owned && application_tcp_response; then
          if [ "$((SECONDS - stable))" -ge "$application_health_stability" ]; then printf 'HEALTHY=1\n'; return; fi
        else stable="$SECONDS"; fi ;;
      HTTP)
        if [ -n "$current" ] && application_udp_owned && [ "$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 2 --noproxy '*' "$application_health_url")" = "$application_health_status" ]; then
          [ "$(application_process_identity)" = "$current" ] || reject application-process-changed
          printf 'HEALTHY=1\n'; return
        fi ;;
      UDP)
        if [ -n "$current" ] && application_udp_owned; then
          if [ "$application_health_custom" = 1 ]; then
            if [ "$application_backend" = native ]; then (run_application_native "$1" probe) >/dev/null || { sleep 1; continue; }
            elif [ -n "${application_restore_root:-}" ]; then (application_container_candidate_probe probe) >/dev/null || { sleep 1; continue; }
            else (run_application_container "$1" probe) >/dev/null || { sleep 1; continue; }; fi
          else application_udp_response || { sleep 1; continue; }; fi
          [ "$(application_process_identity)" = "$current" ] || reject application-process-changed
          printf 'HEALTHY=1\n'; return
        fi ;;
      *) reject application-health-kind ;;
    esac
    sleep 1
  done
  reject application-health-timeout
}

application_tcp_response() {
  local endpoint id protocol address host target exposure probe_address=127.0.0.1
  for endpoint in "${application_endpoints[@]}"; do
    IFS='|' read -r id protocol address host target exposure <<< "$endpoint"
    if [ "$protocol" != UDP ] && [ "$host" = "$application_health_port" ]; then
      case "$address" in 0.0.0.0) probe_address=127.0.0.1 ;; ::) probe_address=::1 ;; *) probe_address="$address" ;; esac; break
    fi
  done
  python3 -I - "$probe_address" "$application_health_port" <<'WTL_TCP_PROBE'
import socket,sys
with socket.create_connection((sys.argv[1],int(sys.argv[2])),timeout=2): pass
WTL_TCP_PROBE
}
