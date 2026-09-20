assert_container_ports() {
  local name="$1" actual spec protocol mapping target host address expected=
  actual="$("$container_engine" inspect --format '{{json .HostConfig.PortBindings}}' "$name")" || reject container-port-inspection
  for spec in "${container_ports[@]}"; do
    protocol="${spec##*/}"; mapping="${spec%/*}"; target="${mapping##*:}"; mapping="${mapping%:*}"
    host="${mapping##*:}"; address="${mapping%:*}"; address="${address#[}"; address="${address%]}"
    expected+="$protocol|$address|$host|$target"$'\n'
  done
  python3 -I - "$actual" "$expected" <<'WTL_PORT_BINDINGS'
import ipaddress,json,sys
actual=json.loads(sys.argv[1]) or {}
found=set()
for key, bindings in actual.items():
    port,protocol=key.split('/')
    for binding in bindings or []:
        found.add((protocol,str(ipaddress.ip_address(binding['HostIp'])),int(binding['HostPort']),int(port)))
expected={(p,str(ipaddress.ip_address(a)),int(h),int(t)) for p,a,h,t in (line.split('|') for line in sys.argv[2].splitlines())}
if found != expected: raise SystemExit(1)
WTL_PORT_BINDINGS
}
application_container_argv() {
  [ -z "${application_worker_ids[*]:-}" ] || reject container-workers-require-image-entrypoint
  local image="$1" entry="$2" metadata encoded; shift 2
  if [ -n "$entry" ]; then application_exec=("$(container_binding_destination "$image" "$entry")")
  else
    metadata="$("$container_engine" image inspect --format '{{json .Config}}' "$image")" || reject application-image-entry
    encoded="$(python3 -I -c 'import base64,json,sys; c=json.loads(sys.argv[1]); a=(c.get("Entrypoint") or [])+(c.get("Cmd") or []); assert 0<len(a)<=64 and all(isinstance(x,str) and len(x)<=4096 and not any(v in x for v in ("\0","\n","\r")) for x in a); print(base64.b64encode(("\0".join(a)+"\0").encode()).decode())' "$metadata")" || reject application-image-entry
    [ "${#encoded}" -le 87384 ] || reject application-image-entry
    mapfile -d '' -t application_exec < <(printf '%s' "$encoded" | base64 --decode)
  fi
  application_exec+=("$@")
}
application_container_options() {
  local image="$1" index=0 destination
  application_container_arguments=()
  if [ -n "$application_workdir" ]; then
    destination="$(container_binding_destination "$image" "$application_workdir")"
    application_container_arguments+=(--workdir "$destination")
  fi
  application_container_argv "$image" "$application_entry" "${application_args[@]}"
  application_container_entry="${application_exec[0]}"
  application_container_command=("${application_exec[@]:1}")
  application_container_arguments+=(--entrypoint "$application_container_entry")
  while [ "$index" -lt "${#application_input_sources[@]}" ]; do
    application_container_arguments+=(--mount "type=bind,source=${application_input_sources[$index]},destination=${application_input_targets[$index]},readonly")
    index=$((index+1))
  done
}

application_assert_exact_mounts() {
  local name="$1" actual; shift
  [ "$("$container_engine" inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$name")" = true ] || reject container-root-writable
  actual="$("$container_engine" inspect --format '{{json .Mounts}}' "$name")" || reject container-mount-inspection
  python3 -I - "$actual" "$@" <<'WTL_EXACT_MOUNTS'
import json,sys
expected=set()
for declaration in sys.argv[2:]:
    source,target,mode=declaration.split(':')
    expected.add((source,target,mode=='rw'))
found=set()
for mount in json.loads(sys.argv[1]):
    if mount['Type']=='tmpfs' and mount['Destination'] in ('/run','/tmp'): continue
    if mount['Type']!='bind': raise SystemExit(1)
    found.add((mount['Source'],mount['Destination'],mount['RW']))
if found!=expected: raise SystemExit(1)
WTL_EXACT_MOUNTS
}
application_container_candidate_probe() {
  local operation="$1" image entry status=0
  local -a args
  image="$(cat -- "$application_restore_root/candidate-image")"
  if [ "$operation" = verify ]; then entry="$application_verify_entry"; args=("${application_verify_args[@]}")
  else entry="$application_health_entry"; args=("${application_health_args[@]}"); fi
  application_container_argv "$image" "$entry" "${args[@]}"
  # The existing candidate already has private data, a non-root identity and a sealed read-only filesystem.
  # exec inherits those exact namespaces; it cannot mount the formal application's data.
  timeout --signal=TERM --kill-after=5 "$application_health_timeout" "$container_engine" exec "$application_health_container" "${application_exec[@]}" || status=$?
  if [ "$status" = 124 ] || [ "$status" = 137 ]; then
    "$container_engine" stop --time 5 "$application_health_container" >/dev/null || reject application-candidate-probe-cleanup
  fi
  return "$status"
}
