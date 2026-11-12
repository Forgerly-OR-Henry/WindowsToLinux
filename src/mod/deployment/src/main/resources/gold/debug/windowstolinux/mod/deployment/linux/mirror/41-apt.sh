wtl_prepare_system_credential() {
  local point="$1" provider="$2" folder="$3" descriptor signature decoded encoded authority root_ca prefix host
  local -a origins prefixes
  wtl_credential_authorization= wtl_credential_username= wtl_credential_password= wtl_credential_ca=
  wtl_source_credential "$point" "$provider" || return 65
  wtl_auth_options=(-o Dir::Etc::netrc=/dev/null -o Dir::Etc::netrcparts=/dev/null)
  wtl_apt_strict_options=(-o Acquire::http::Proxy=DIRECT -o Acquire::https::Proxy=DIRECT
    -o Acquire::http::AllowRedirect=false -o Acquire::https::AllowRedirect=false
    -o Acquire::https::Verify-Peer=true -o Acquire::https::Verify-Host=true
    -o APT::Get::AllowUnauthenticated=false -o Acquire::AllowInsecureRepositories=false
    -o Acquire::AllowWeakRepositories=false -o Acquire::AllowDowngradeToInsecureRepositories=false
    -o Acquire::Check-Valid-Until=true -o Debug::Acquire::http=false -o Debug::Acquire::https=false)
  wtl_source_routes "$point" "$provider" || return 65
  if [[ "$point" = APT_* ]]; then
    for prefix in "${prefixes[@]}"; do
      [[ "$prefix" = https://* || "$prefix" = http://* ]] || return 65
    done
  fi
  [ -n "$wtl_credential_file" ] || return 0
  [[ "$wtl_credential_scope" = https://* ]] || { printf 'MIRROR_REJECT=system-authentication-requires-https\n' >&2; return 65; }
  for prefix in "${prefixes[@]}"; do
    [[ "$prefix" = https://* ]] || { printf 'MIRROR_REJECT=system-authentication-requires-https\n' >&2; return 65; }
  done
  [ -f "$wtl_credential_file" ] && [ ! -L "$wtl_credential_file" ] || return 65
  [ "$(/usr/bin/stat -c %u -- "$wtl_credential_file")" = "$EUID" ] || return 65
  local mode="$(/usr/bin/stat -c %a -- "$wtl_credential_file")"
  [[ "$mode" =~ ^[0-7]{3,4}$ ]] && [ "$((8#$mode & 077))" = 0 ] || return 65
  exec {descriptor}<"$wtl_credential_file"
  IFS= read -r signature <&"$descriptor" || { exec {descriptor}<&-; return 65; }
  IFS= read -r wtl_credential_authorization <&"$descriptor" || { exec {descriptor}<&-; return 65; }
  [ "$signature" = WTL-MIRROR-1 ] || { exec {descriptor}<&-; return 65; }
  /usr/bin/cat <&"$descriptor" >"$folder/source-ca.pem" || { exec {descriptor}<&-; return 65; }
  exec {descriptor}<&-
  /usr/bin/chmod 600 -- "$folder/source-ca.pem" || return 65
  if [ -n "$wtl_credential_authorization" ]; then
    case "$wtl_credential_authorization" in 'Basic '*) ;; *) printf 'MIRROR_REJECT=system-authentication-must-be-basic\n' >&2; return 65;; esac
    encoded="${wtl_credential_authorization#Basic }"
    decoded="$(printf '%s' "$encoded" | /usr/bin/base64 --decode)" || return 65
    [ "$(printf '%s' "$decoded" | /usr/bin/base64 | /usr/bin/tr -d '\n')" = "$encoded" ] || return 65
    [[ "$decoded" = *:* && "$decoded" != *[[:space:]\"\'\\]* ]] || return 65
    wtl_credential_username="${decoded%%:*}" wtl_credential_password="${decoded#*:}"
    [ -n "$wtl_credential_username" ] || return 65
    printf 'machine %s login %s password %s\n' "$wtl_credential_scope" "$wtl_credential_username" "$wtl_credential_password" >"$folder/auth.conf"
    /usr/bin/chmod 600 -- "$folder/auth.conf" || return 65
    wtl_auth_options=(-o "Dir::Etc::netrc=$folder/auth.conf" -o Dir::Etc::netrcparts=/dev/null)
  fi
  if [ -s "$folder/source-ca.pem" ]; then
    wtl_credential_ca="$folder/trusted-ca.pem"
    : >"$wtl_credential_ca"
    for root_ca in /etc/ssl/certs/ca-certificates.crt /etc/pki/tls/certs/ca-bundle.crt; do
      if [ -f "$root_ca" ]; then /usr/bin/cat -- "$root_ca" >>"$wtl_credential_ca" || return 65; break; fi
    done
    /usr/bin/cat -- "$folder/source-ca.pem" >>"$wtl_credential_ca" || return 65
    /usr/bin/chmod 600 -- "$wtl_credential_ca" || return 65
    for prefix in "${prefixes[@]}"; do
      if [[ "$prefix" != "${wtl_credential_scope%/}" && "$prefix" != "${wtl_credential_scope%/}/"* ]]; then
        printf 'MIRROR_REJECT=apt-private-ca-cannot-extend-credential-path-scope\n' >&2; return 65
      fi
    done
    wtl_auth_options+=(-o "Acquire::https::CAInfo=$wtl_credential_ca")
  fi
}
wtl_package_log() {
  local file="$1" line secret
  while IFS= read -r line || [ -n "$line" ]; do
    for secret in "${wtl_credential_authorization:-}" "${wtl_credential_username:-}" "${wtl_credential_password:-}"; do
      if [ -n "$secret" ]; then line="${line//"$secret"/[redacted]}"; fi
    done
    printf '%s\n' "$line"
  done <"$file"
}
wtl_apt_source_security() {
  local line="${1,,}"
  if [[ "$line" =~ ^[[:space:]]*[\#] ]]; then return 0; fi
  if [[ "$line" =~ (trusted|allow-insecure|allow-weak|allow-downgrade-to-insecure)[=:][[:space:]]*(yes|true|1) \
    || "$line" =~ check-valid-until[=:][[:space:]]*(no|false|0) ]]; then
    printf 'MIRROR_REJECT=apt-source-disables-authentication\n' >&2; return 65
  fi
}
wtl_apt_canonical_uri() {
  local point="$1" provider="$2" url="$3" index best=-1 length=0
  local -a origins prefixes
  wtl_source_routes "$point" "$provider" || return 65
  for index in "${!prefixes[@]}"; do
    if [[ "$url" = "${prefixes[$index]}"* ]] && [ "${#prefixes[$index]}" -gt "$length" ]; then
      best="$index"; length="${#prefixes[$index]}"
    fi
  done
  [ "$best" -ge 0 ] || return 65
  printf '%s%s' "${origins[$best]}" "${url:$length}"
}
wtl_apt_selected_uri() {
  local point="$1" provider="$2" url="$3" index best=-1 length=0
  local -a origins prefixes
  wtl_source_routes "$point" "$provider" || return 65
  for index in "${!origins[@]}"; do
    if [[ "$url" = "${origins[$index]}"* ]] && [ "${#origins[$index]}" -gt "$length" ]; then
      best="$index"; length="${#origins[$index]}"
    fi
  done
  [ "$best" -ge 0 ] || return 65
  printf '%s%s' "${prefixes[$best]}" "${url:$length}"
}
wtl_apt_prepare_configuration() {
  local folder="$1"
  case "$folder" in *'"'*|*'\'*|*$'\n'*|*$'\r'*) return 65;; esac
  /usr/bin/mkdir -p "$folder/apt.conf.d" || return 65
  printf 'Dir::Etc::main "/dev/null";\nDir::Etc::parts "%s";\n' "$folder/apt.conf.d" >"$folder/apt.conf"
  printf 'Dir::Cache::pkgcache "%s";\nDir::Cache::srcpkgcache "%s";\n' "$folder/pkgcache.bin" "$folder/srcpkgcache.bin" >>"$folder/apt.conf"
  /usr/bin/chmod 600 "$folder/apt.conf" || return 65
  APT_CONFIG="$folder/apt.conf"
  export APT_CONFIG
}
wtl_apt_configure_command() {
  local folder="$1" cache="$2"
  package_command=(/usr/bin/apt-get -o Dir::Etc::sourcelist=/dev/null -o "Dir::Etc::sourceparts=$folder/repos"
    -o APT::Update::Error-Mode=any -o Acquire::Retries=0 -o "Acquire::http::Timeout=$wtl_mirror_connect_seconds" -o "Acquire::https::Timeout=$wtl_mirror_connect_seconds"
    -o "Dir::State::lists=$cache/lists" -o "Dir::Cache::archives=$cache/archives" -o APT::Sandbox::User=root
    "${wtl_auth_options[@]}" "${wtl_apt_strict_options[@]}")
}
wtl_apt_resolution_missing() {
  local log="$1" line found=0
  local missing="^E: (Unable to locate package [A-Za-z0-9+_.:-]+|Package '[A-Za-z0-9+_.:-]+' has no installation candidate|Version '[A-Za-z0-9.+:~_-]+' for '[A-Za-z0-9+_.:-]+' was not found|Release '[A-Za-z0-9.+:~_-]+' for '[A-Za-z0-9+_.:-]+' was not found)$"
  while IFS= read -r line || [ -n "$line" ]; do
    if [[ "$line" = E:* ]]; then
      [[ "$line" =~ $missing ]] || return 1
      found=1
    fi
  done <"$log"
  [ "$found" = 1 ]
}
wtl_apt_resolve_frozen() {
  local folder="$1" log="$2" status
  shift 2
  wtl_apt_unresolved=0
  if /usr/bin/env LC_ALL=C LANGUAGE=C "${package_command[@]}" -o Acquire::ForceHash=SHA256 --print-uris --download-only --yes "$@" >"$folder/objects" 2>"$log"; then return 0
  else status=$?; fi
  wtl_package_log "$log"
  if [ "$status" = 100 ] && wtl_apt_resolution_missing "$log"; then wtl_apt_unresolved=1; fi
  return "$status"
}
wtl_apt_configure_query() {
  query_command=(/usr/bin/env LC_ALL=C LANGUAGE=C /usr/bin/apt-cache "${package_command[@]:1}" policy "$wtl_apt_candidate_package")
}
wtl_apt_candidate() {
  [ "$#" = 2 ] && [ "$1" = policy ] && [[ "$2" =~ ^[a-z0-9][a-z0-9+.-]*(:[a-z0-9-]+)?$ ]] || return 65
  local wtl_apt_candidate_package="$2" wtl_apt_candidate_output=
  wtl_system_package apt-get update || return $?
  printf '%s\n' "$wtl_apt_candidate_output"
}
wtl_apt_configure_download() {
  [ -x /usr/lib/apt/apt-helper ] || { printf 'MIRROR_REJECT=apt-native-download-helper-missing\n' >&2; return 65; }
  helper_command=(/usr/lib/apt/apt-helper "${package_command[@]:1}" "${wtl_auth_options[@]}" "${wtl_apt_strict_options[@]}")
}
wtl_apt_install_frozen() {
  local point="$1" folder="$2" cache="$3" log="$4" metadata_provider="$5" uri name size digest extra canonical provider attempt status=65 index selected target
  shift 5
  local -a object_uris=() object_names=() object_sizes=() object_digests=() helper_command
  local -A seen=()
  [ -f "$folder/objects" ] || return 65
  while IFS=' ' read -r uri name size digest extra; do
    [[ "$uri" = \'* ]] || continue
    [[ "$uri" = \'*\' && "$name" =~ ^[A-Za-z0-9_%:+~.-]+[.]deb$ && "$size" =~ ^[0-9]+$ \
      && "$digest" =~ ^SHA256:[a-fA-F0-9]{64}$ && -z "$extra" ]] || { printf 'MIRROR_REJECT=apt-strong-frozen-object-required\n' >&2; return 65; }
    [ "$size" -le 2147483648 ] || return 65
    uri="${uri#\'}"; uri="${uri%\'}"
    canonical="$(wtl_apt_canonical_uri "$point" "$metadata_provider" "$uri")" || return 65
    [ -z "${seen[$name]:-}" ] || { printf 'MIRROR_REJECT=apt-duplicate-frozen-object\n' >&2; return 65; }
    seen[$name]=1
    object_uris+=("$canonical"); object_names+=("$name"); object_sizes+=("$size"); object_digests+=("$digest")
    [ "${#object_names[@]}" -le 2048 ] || return 65
  done <"$folder/objects"
  printf 'MIRROR_APT_FROZEN=%s\n' "$(/usr/bin/sha256sum "$folder/objects" | /usr/bin/awk '{print $1}')"
  for index in "${!object_names[@]}"; do
    target="$cache/archives/${object_names[$index]}"; status=65
    for provider in "${providers[@]}"; do
      selected="$(wtl_apt_selected_uri "$point" "$provider" "${object_uris[$index]}")" || continue
      wtl_prepare_system_credential "$point" "$provider" "$folder" || return 65
      wtl_apt_configure_download || return 65
      for ((attempt=1; attempt<=wtl_mirror_attempts; attempt++)); do
        /usr/bin/rm -f -- "$target" "$target.FAILED"
        printf 'MIRROR point=%s source=%s attempt=%s reason=frozen-object\n' "$point" "$provider" "$attempt" >&2
        if wtl_bounded_package_request "$cache" "$log" "${helper_command[@]}" download-file "$selected" "$target" "${object_digests[$index]}"; then
          if [ "$(/usr/bin/stat -c %s -- "$target")" = "${object_sizes[$index]}" ]; then status=0; break; fi
          printf 'MIRROR_REJECT=apt-frozen-size-mismatch\n' >>"$log"
        fi
        wtl_package_log "$log"
        printf 'MIRROR point=%s source=%s attempt=%s reason=frozen-object-transfer-failed\n' "$point" "$provider" "$attempt" >&2
      done
      [ "$status" != 0 ] || break
    done
    [ "$status" = 0 ] || return 65
    /usr/bin/rm -f -- "$target.FAILED"
  done
  # Never refresh metadata or re-resolve against another provider after freezing the objects.
  # Native APT rechecks the same signed metadata/hash and executes dpkg only at this final step.
  "${package_command[@]}" --no-download "$@"
}
