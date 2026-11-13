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
    if [[ "$point" = APT_* && -n "${wtl_apt_root:-}" ]]; then
      /usr/bin/chgrp -- "$wtl_apt_gid" "$wtl_credential_ca" && /usr/bin/chmod 640 -- "$wtl_credential_ca" || return 65
      wtl_apt_as_downloader /usr/bin/test -r "$wtl_credential_ca" || return 65
    else
      /usr/bin/chmod 600 -- "$wtl_credential_ca" || return 65
    fi
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
wtl_apt_repository_uris() {
  local point="$1" provider="$2" values="$3" uri selected
  local IFS=$' \t'
  local -a uris rewritten=()
  read -r -a uris <<<"$values"
  for uri in "${uris[@]}"; do
    if [[ "$uri" != http://* && "$uri" != https://* ]]; then
      printf 'MIRROR_REJECT=apt-source-uri-requires-http-or-https\n' >&2; return 65
    fi
    if [[ "$uri" = *[[:cntrl:]#?\"\'\\]* || "$uri" = */../* || "$uri" = */.. ]]; then
      printf 'MIRROR_REJECT=unmapped-system-repository\n' >&2; return 65
    fi
    if ! selected="$(wtl_apt_selected_uri "$point" "$provider" "$uri")"; then
      wtl_apt_canonical_uri "$point" "$provider" "$uri" >/dev/null || {
        printf 'MIRROR_REJECT=unmapped-system-repository\n' >&2; return 65;
      }
      selected="$uri"
    fi
    rewritten+=("$selected")
  done
  printf '%s' "${rewritten[*]}"
}
wtl_apt_canonical_uri() {
  local point="$1" provider="$2" url="$3" index prefix best=-1 length=0
  local -a origins prefixes
  wtl_source_routes "$point" "$provider" || return 65
  for index in "${!prefixes[@]}"; do
    prefix="${prefixes[$index]}"
    if [[ "$url" = "$prefix" || ( "$prefix" = */ && "$url" = "$prefix"* ) ]] && [ "${#prefix}" -gt "$length" ]; then
      best="$index"; length="${#prefix}"
    fi
  done
  [ "$best" -ge 0 ] || return 65
  printf '%s%s' "${origins[$best]}" "${url:$length}"
}
wtl_apt_selected_uri() {
  local point="$1" provider="$2" url="$3" index origin best=-1 length=0
  local -a origins prefixes
  wtl_source_routes "$point" "$provider" || return 65
  for index in "${!origins[@]}"; do
    origin="${origins[$index]}"
    if [[ "$url" = "$origin" || ( "$origin" = */ && "$url" = "$origin"* ) ]] && [ "${#origin}" -gt "$length" ]; then
      best="$index"; length="${#origin}"
    fi
  done
  [ "$best" -ge 0 ] || return 65
  printf '%s%s' "${prefixes[$best]}" "${url:$length}"
}
wtl_apt_sandbox_identity() {
  local entry
  entry="$(/usr/bin/getent passwd _apt)" || return 65
  [[ "$entry" =~ ^_apt:[^:]*:([1-9][0-9]{0,9}):([1-9][0-9]{0,9}):[^:]*:[^:]*:[^:]*$ ]] || return 65
  wtl_apt_uid="${BASH_REMATCH[1]}" wtl_apt_gid="${BASH_REMATCH[2]}"
  [ "$wtl_apt_uid" -lt 4294967295 ] && [ "$wtl_apt_gid" -lt 4294967295 ] || return 65
  # The fixed sticky temporary parent allows a fresh sandbox path beside the root-only policy.
  wtl_apt_trusted_path / && [ ! -L /tmp ] && [ -d /tmp ] && [ -k /tmp ] \
    && [ "$(/usr/bin/stat -c %u -- /tmp)" = 0 ]
}
wtl_apt_as_downloader() {
  /usr/bin/setpriv --reuid="$wtl_apt_uid" --regid="$wtl_apt_gid" --clear-groups --no-new-privs -- "$@"
}
wtl_apt_prepare_sandbox() {
  local folder="$1" cache="$2"
  /usr/bin/mkdir -p -- "$folder" "$cache/lists/partial" "$cache/lists/auxfiles" \
    "$cache/archives/partial" "$cache/download" "$cache/tmp" || return 65
  /usr/bin/chgrp -- "$wtl_apt_gid" "$wtl_apt_root" "$folder" "$cache" "$cache/lists" "$cache/archives" || return 65
  /usr/bin/chmod 710 -- "$wtl_apt_root" "$folder" "$cache" "$cache/archives" || return 65
  # APT may reuse a signed index from lists/ while verifying and decompressing metadata.
  /usr/bin/chmod 750 -- "$cache/lists" || return 65
  /usr/bin/chown -- "$wtl_apt_uid:$wtl_apt_gid" "$cache/lists/partial" "$cache/lists/auxfiles" \
    "$cache/archives/partial" "$cache/download" "$cache/tmp" || return 65
  wtl_apt_as_downloader /bin/bash --noprofile --norc -pc '
    [ "$EUID" = "$1" ] && [ "$(/usr/bin/id -g)" = "$2" ] || exit 65
    shift 2
    for directory; do
      probe="$(/usr/bin/mktemp -- "$directory/.wtl-access.XXXXXXXX")" || exit 65
      /usr/bin/rm -f -- "$probe" || exit 65
    done
  ' wtl-apt-access "$wtl_apt_uid" "$wtl_apt_gid" "$cache/lists/partial" "$cache/lists/auxfiles" \
    "$cache/archives/partial" "$cache/download" "$cache/tmp" || return 65
}
wtl_apt_update() {
  local cache="$1"
  if [ -n "${wtl_apt_root:-}" ]; then
    # Signature verification can need temporary files after APT lowers its identity.
    # Keep this directory out of the environment used by dpkg and package scripts.
    TMPDIR="$cache/tmp" TMP="$cache/tmp" TEMP="$cache/tmp" wtl_bounded_package_request "$@"
  else
    wtl_bounded_package_request "$@"
  fi
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
  wtl_apt_options=(-o Dir::Etc::sourcelist=/dev/null -o "Dir::Etc::sourceparts=$folder/repos"
    -o APT::Update::Error-Mode=any -o Acquire::Retries=0 -o "Acquire::http::Timeout=$wtl_mirror_connect_seconds" -o "Acquire::https::Timeout=$wtl_mirror_connect_seconds"
    -o "Dir::State::lists=$cache/lists" -o "Dir::Cache::archives=$cache/archives"
    -o APT::Sandbox::User=_apt -o APT::Sandbox::Verify=true
    # Older APT can clear the global sandbox setting if a destination is inaccessible.
    # HTTP methods apply their own Binary config before dropping privileges.
    -o Binary::http::APT::Sandbox::User=_apt -o Binary::https::APT::Sandbox::User=_apt
    "${wtl_apt_strict_options[@]}")
  package_command=(/usr/bin/apt-get "${wtl_apt_options[@]}" "${wtl_auth_options[@]}")
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
  local wtl_apt_candidate_package="$2"
  wtl_apt update
}
wtl_apt_trusted_path() {
  local path="$1" identity
  [ ! -L "$path" ] || return 65
  identity="$(/usr/bin/stat -c '%u:%a' -- "$path" 2>/dev/null)" || return 65
  [[ "$identity" =~ ^0:([0-7]{1,4})$ ]] || return 65
  (( (8#${BASH_REMATCH[1]} & 022) == 0 ))
}
wtl_apt_configure_download() {
  local helper=/usr/lib/apt/apt-helper parent
  for parent in / /usr /usr/lib /usr/lib/apt; do
    if ! wtl_apt_trusted_path "$parent" || [ ! -d "$parent" ]; then
      printf 'MIRROR_REJECT=apt-native-download-helper-untrusted\n' >&2; return 65
    fi
  done
  if [ ! -e "$helper" ] && [ ! -L "$helper" ]; then
    printf 'MIRROR_REJECT=apt-native-download-helper-missing\n' >&2; return 65
  fi
  if ! wtl_apt_trusted_path "$helper" || [ ! -f "$helper" ] || [ ! -x "$helper" ] || [ -u "$helper" ] || [ -g "$helper" ]; then
    printf 'MIRROR_REJECT=apt-native-download-helper-untrusted\n' >&2; return 65
  fi
  helper_command=("$helper" "${wtl_apt_options[@]}" "${wtl_auth_options[@]}")
}
wtl_apt_promote_archive() {
  local staged="$1" target="$2" size="$3" expected="$4" checksum
  [ ! -L "$staged" ] && [ -f "$staged" ] || return 65
  if [ -n "${wtl_apt_root:-}" ]; then
    # Read with the downloader's rights and limit the copy. The root-owned archive
    # is verified independently of the copy or any later change to staged bytes.
    wtl_apt_as_downloader /usr/bin/dd if="$staged" iflag=nofollow,nonblock,count_bytes count="$((size + 1))" status=none >"$target" || return 65
  else
    /usr/bin/cp -- "$staged" "$target" || return 65
  fi
  [ "$(/usr/bin/stat -c %s -- "$target")" = "$size" ] || return 65
  checksum="$(/usr/bin/sha256sum -- "$target")" || return 65
  [ "${checksum%% *}" = "${expected#SHA256:}" ]
}
wtl_apt_install_frozen() {
  local point="$1" folder="$2" cache="$3" log="$4" metadata_provider="$5" uri name size digest extra canonical provider attempt status=65 index selected target staged
  shift 5
  local -a object_uris=() object_names=() object_sizes=() object_digests=() helper_command
  local -A seen=()
  [ -f "$folder/objects" ] || return 65
  while IFS=' ' read -r uri name size digest extra; do
    [[ "$uri" = \'* ]] || continue
    [[ "$uri" = \'*\' && "$name" =~ ^[A-Za-z0-9_%:+~.-]+[.]deb$ && "$size" =~ ^[0-9]+$ \
      && "$digest" =~ ^SHA256:[a-fA-F0-9]{64}$ && -z "$extra" ]] || { printf 'MIRROR_REJECT=apt-strong-frozen-object-required\n' >&2; return 65; }
    [[ "$size" =~ ^(0|[1-9][0-9]{0,9})$ ]] && [ "$size" -le 2147483648 ] || return 65
    uri="${uri#\'}"; uri="${uri%\'}"
    canonical="$(wtl_apt_canonical_uri "$point" "$metadata_provider" "$uri")" || return 65
    [ -z "${seen[$name]:-}" ] || { printf 'MIRROR_REJECT=apt-duplicate-frozen-object\n' >&2; return 65; }
    seen[$name]=1
    digest="${digest#SHA256:}"
    object_uris+=("$canonical"); object_names+=("$name"); object_sizes+=("$size"); object_digests+=("SHA256:${digest,,}")
    [ "${#object_names[@]}" -le 2048 ] || return 65
  done <"$folder/objects"
  /usr/bin/mkdir -p -- "$cache/download" || return 65
  printf 'MIRROR_APT_FROZEN=%s\n' "$(/usr/bin/sha256sum "$folder/objects" | /usr/bin/awk '{print $1}')"
  for index in "${!object_names[@]}"; do
    target="$cache/archives/${object_names[$index]}"; staged="$cache/download/${object_names[$index]}"; status=65
    for provider in "${providers[@]}"; do
      selected="$(wtl_apt_selected_uri "$point" "$provider" "${object_uris[$index]}")" || continue
      wtl_prepare_system_credential "$point" "$provider" "$folder" || return 65
      wtl_apt_configure_download || return 65
      for ((attempt=1; attempt<=wtl_mirror_attempts; attempt++)); do
        /usr/bin/rm -f -- "$target" "$staged" "$staged.FAILED" || return 65
        printf 'MIRROR point=%s source=%s attempt=%s reason=frozen-object\n' "$point" "$provider" "$attempt" >&2
        if wtl_bounded_package_request "$cache" "$log" "${helper_command[@]}" download-file "$selected" "$staged" "${object_digests[$index]}"; then
          if wtl_apt_promote_archive "$staged" "$target" "${object_sizes[$index]}" "${object_digests[$index]}"; then status=0; break; fi
          printf 'MIRROR_REJECT=apt-frozen-archive-mismatch\n' >>"$log"
        fi
        wtl_package_log "$log"
        printf 'MIRROR point=%s source=%s attempt=%s reason=frozen-object-transfer-failed\n' "$point" "$provider" "$attempt" >&2
      done
      [ "$status" != 0 ] || break
    done
    [ "$status" = 0 ] || return 65
    /usr/bin/rm -f -- "$staged" "$staged.FAILED"
  done
  # Never refresh metadata or re-resolve against another provider after freezing the objects.
  # Native APT rechecks the same signed metadata/hash and executes dpkg only at this final step.
  "${package_command[@]}" --no-download "$@"
}
