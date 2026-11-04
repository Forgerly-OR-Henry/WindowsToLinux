# Source locations and substitutions below are rendered only from the frozen task JSON.
wtl_system_point() {
  local ID= ID_LIKE=
  . /etc/os-release
  case "$ID" in
    debian) printf APT_DEBIAN ;;
    ubuntu) printf APT_UBUNTU ;;
    fedora) printf DNF_FEDORA ;;
    rocky) printf DNF_ROCKY ;;
    almalinux) printf DNF_ALMA ;;
    ol) printf DNF_ORACLE ;;
    centos) printf DNF_CENTOS_STREAM ;;
    *) printf 'MIRROR_REJECT=unmapped-system-distribution\n' >&2; return 65 ;;
  esac
}
wtl_repository_copy() {
  local point="$1" provider="$2" input="$3" output="$4" line original replacement token
  local -a origins prefixes
  wtl_source_routes "$point" "$provider" || return 65
  : >"$output"
  local filtered="$output.wtl-input"
  awk 'BEGIN {RS=""; ORS="\n\n"} {
    lower=tolower($0)
    if (lower ~ /(^|\n)[ \t]*enabled:[ \t]*(no|false|0)([ \t]*\n|[ \t]*$)/) {
      count=split($0, lines, "\n"); for (i=1; i<=count; i++) print "# " lines[i]
    } else print $0
  }' "$input" >"$filtered"
  while IFS= read -r line || [ -n "$line" ]; do
    wtl_apt_source_security "$line" || return 65
    case "$line" in *mirrorlist=*|*metalink=*)
      # URL-producing metadata cannot silently reactivate an unselected repository.
      line="#$line" ;;
    esac
    for token in "${!origins[@]}"; do
      original="${origins[$token]}" replacement="${prefixes[$token]}"
      line="${line//"$original"/"$replacement"}"
    done
    if [[ ! "$line" =~ ^[[:space:]]*[\#] ]]; then
      # Every active HTTP(S) URL must belong to the selected endpoint after substitution.
      local words word matched
      read -r -a words <<<"$line"
      for word in "${words[@]}"; do
        word="${word#*=}"
        if [[ "$word" = http://* || "$word" = https://* ]]; then
          matched=0
          for replacement in "${prefixes[@]}"; do
            [[ "$word" = "$replacement"* ]] && matched=1
          done
          [ "$matched" = 1 ] || { printf 'MIRROR_REJECT=unmapped-system-repository\n' >&2; return 65; }
        fi
      done
    fi
    printf '%s\n' "$line" >>"$output"
  done <"$filtered"
  rm -f -- "$filtered"
}
wtl_dnf_repository_copy() {
  local point="$1" provider="$2" input="$3" output="$4" line repo= next_repo= enabled=1 emitted=0
  local -A repo_templates
  local -a section=()
  wtl_source_repo_templates "$point" "$provider" || return 65
  : >"$output"
  wtl_dnf_flush_section() {
    local item
    [ -n "$repo" ] || return 0
    printf '[%s]\n' "$repo" >>"$output"
    if [ "$enabled" = 0 ]; then printf 'enabled=0\n' >>"$output"; return 0; fi
    [ -n "${repo_templates[$repo]:-}" ] || { printf 'MIRROR_REJECT=dnf-repository-template-missing:%s\n' "$repo" >&2; return 65; }
    for item in "${section[@]}"; do
      if [[ "$item" =~ ^[[:space:]]*[\#\;] ]]; then printf '%s\n' "$item" >>"$output"
      elif [[ "$item" =~ ^[[:space:]]*(username|password|proxy|proxy_username|proxy_password|sslcacert|sslverify|sslclientcert|sslclientkey|gpgcheck|skip_if_unavailable)[[:space:]]*= ]]; then :
      elif [[ "$item" =~ ^[[:space:]]*(baseurl|mirrorlist|metalink)[[:space:]]*= ]]; then
        if [ "$emitted" = 0 ]; then printf 'baseurl=%s\n' "${repo_templates[$repo]}" >>"$output"; emitted=1; fi
      elif [[ "$item" =~ https?:// ]]; then
        # Remote GPG key acquisition is a separate source; use already installed trust roots.
        printf 'MIRROR_REJECT=dnf-remote-key-or-auxiliary-url\n' >&2; return 65
      else printf '%s\n' "$item" >>"$output"; fi
    done
    if [ "$emitted" = 0 ]; then printf 'baseurl=%s\n' "${repo_templates[$repo]}" >>"$output"; fi
    printf 'username=\npassword=\nproxy=\ngpgcheck=1\nsslverify=1\nskip_if_unavailable=0\n' >>"$output"
  }
  while IFS= read -r line || [ -n "$line" ]; do
    if [[ "$line" =~ ^[[:space:]]*\[([^]]+)\][[:space:]]*$ ]]; then
      next_repo="${BASH_REMATCH[1]}"
      wtl_dnf_flush_section || return 65
      repo="$next_repo"; enabled=1; emitted=0; section=()
    else
      section+=("$line")
      if [[ "$line" =~ ^[[:space:]]*enabled[[:space:]]*=[[:space:]]*0([[:space:]]|$) ]]; then enabled=0; fi
    fi
  done <"$input"
  wtl_dnf_flush_section
}
wtl_stop_package_tree() {
  local target="$1" expected="$2" status raw tail child parent
  local -a fields
  [ -r "/proc/$target/stat" ] || return 0
  IFS= read -r raw <"/proc/$target/stat" || return 0
  tail="${raw##*) }"; read -r -a fields <<<"$tail"
  [ -n "$expected" ] && [ "${fields[19]:-}" = "$expected" ] || return 0
  # Freeze this owned parent before traversing children, so its PIDs cannot be reaped/reused.
  kill -STOP "$target" 2>/dev/null || return 0
  for status in /proc/[0-9]*/stat; do
    [ -r "$status" ] || continue
    IFS= read -r raw <"$status" || continue
    tail="${raw##*) }"; read -r -a fields <<<"$tail"
    parent="${fields[1]:-}"
    if [ "$parent" = "$target" ]; then
      child="${status#/proc/}"; child="${child%/stat}"
      wtl_stop_package_tree "$child" "${fields[19]:-}"
    fi
  done
  kill -KILL "$target" 2>/dev/null || true
}
wtl_bounded_package_request() (
  local cache="$1" log="$2" pid result now own_start= raw tail last_size=0 size last_progress="$SECONDS" window_start="$SECONDS" window_bytes=0
  local -a fields
  shift 2
  "$@" >"$log" 2>&1 &
  pid=$!
  if [ -r "/proc/$pid/stat" ]; then
    IFS= read -r raw <"/proc/$pid/stat" || true
    tail="${raw##*) }"; read -r -a fields <<<"$tail"; own_start="${fields[19]:-}"
  fi
  trap 'wtl_stop_package_tree "$pid" "$own_start"; wait "$pid" 2>/dev/null || true' EXIT HUP INT TERM
  last_size="$(du -sb -- "$cache" | awk '{print $1}')"
  while kill -0 "$pid" 2>/dev/null; do
    sleep 1
    size="$(du -sb -- "$cache" | awk '{print $1}')"
    if [ "$size" -gt "$last_size" ]; then
      window_bytes=$((window_bytes + size - last_size)); last_progress="$SECONDS"
    fi
    last_size="$size"
    if [ -n "${WTL_MIRROR_BUDGET_SECONDS:-}" ] && [ -n "${WTL_MIRROR_STARTED_AT:-}" ]; then
      now="$(date +%s)"
      if [ "$((now - WTL_MIRROR_STARTED_AT))" -ge "$WTL_MIRROR_BUDGET_SECONDS" ]; then
        printf 'MIRROR_REJECT=task-deadline\n' >>"$log"; return 65
      fi
    fi
    if [ "$((SECONDS - last_progress))" -ge "$wtl_mirror_idle_seconds" ]; then printf 'MIRROR_REJECT=no-progress\n' >>"$log"; return 65; fi
    if [ "$((SECONDS - window_start))" -ge "$wtl_mirror_low_window_seconds" ]; then
      if [ "$window_bytes" -lt "$((wtl_mirror_low_rate * (SECONDS - window_start)))" ]; then
        printf 'MIRROR_REJECT=low-speed\n' >>"$log"; return 65
      fi
      window_start="$SECONDS"; window_bytes=0
    fi
  done
  if wait "$pid"; then result=0; else result=$?; fi
  trap - EXIT HUP INT TERM
  return "$result"
)
wtl_system_package() {
  local manager="$1" point provider attempt status=65 file log folder wtl_apt_unresolved=0
  local APT_CONFIG=
  shift
  point="$(wtl_system_point)" || return 65
  local -a providers
  local -a package_command query_command wtl_auth_options wtl_apt_strict_options
  local wtl_credential_file= wtl_credential_scope= wtl_credential_ref= wtl_credential_authorization= wtl_credential_username= wtl_credential_password= wtl_credential_ca=
  wtl_source_providers "$point" || return 65
  if [ "${#providers[@]}" -eq 0 ]; then
    printf '%s\n' "MIRROR_REJECT=unplanned-download-point:$point" >&2
    return 65
  fi
  folder="$(mktemp -d "${TMPDIR:-/tmp}/wtl-system-source.XXXXXXXX")" || return 65
  chmod 700 "$folder"
  log="$folder/request.log"
  local cache="${WTL_MIRROR_POLICY%/*}/system-cache"
  mkdir -p "$cache/lists/partial" "$cache/archives/partial" "$cache/dnf"
  for provider in "${providers[@]}"; do
    wtl_prepare_system_credential "$point" "$provider" "$folder" || { rm -rf -- "$folder"; return 65; }
    mkdir -p "$folder/repos"
    if [ "$manager" = apt-get ]; then
      for file in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
        [ -f "$file" ] || continue
        wtl_repository_copy "$point" "$provider" "$file" "$folder/repos/${file##*/}" || { rm -rf -- "$folder"; return 65; }
      done
    else
      for file in /etc/yum.repos.d/*.repo; do
        [ -f "$file" ] || continue
        wtl_dnf_repository_copy "$point" "$provider" "$file" "$folder/repos/${file##*/}" || { rm -rf -- "$folder"; return 65; }
      done
      if ! grep -Eq '^\s*baseurl\s*=\s*https?://' "$folder/repos/"*; then
        printf 'MIRROR_REJECT=dnf-explicit-baseurl-required\n' >&2
        rm -rf -- "$folder"; return 65
      fi
    fi
    for ((attempt=1; attempt<=wtl_mirror_attempts; attempt++)); do
      printf 'MIRROR point=%s source=%s attempt=%s reason=request\n' "$point" "$provider" "$attempt" >&2
      if [ "$manager" = apt-get ]; then
        wtl_apt_prepare_configuration "$folder" || { rm -rf -- "$folder"; return 65; }
        wtl_apt_configure_command "$folder" "$cache"
        if [[ " $* " = *' install '* ]]; then
          if wtl_bounded_package_request "$cache" "$log" "${package_command[@]}" update; then
            wtl_package_log "$log"
            if wtl_apt_resolve_frozen "$folder" "$log" "$@"; then :
            else
              status=$?
              if [ "$wtl_apt_unresolved" = 1 ]; then
                printf 'MIRROR point=%s source=%s attempt=%s reason=metadata-missing-candidate\n' "$point" "$provider" "$attempt" >&2
                break
              fi
              rm -rf -- "$folder"; return "$status"
            fi
            mkdir -p "$cache/active-sources"
            cp -- "$folder/repos/"* "$cache/active-sources/"
            if wtl_apt_install_frozen "$point" "$folder" "$cache" "$log" "$provider" "$@"; then status=0; else status=$?; fi
            rm -rf -- "$folder"; return "$status"
          else status=$?; fi
        elif wtl_bounded_package_request "$cache" "$log" "${package_command[@]}" "$@"; then status=0; else status=$?; fi
      else
        printf '[main]\nplugins=0\nusername=\npassword=\nproxy=\n' >"$folder/dnf.conf"
        package_command=(/usr/bin/env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u all_proxy
          /usr/bin/dnf --config="$folder/dnf.conf" --setopt="reposdir=$folder/repos" --setopt="cachedir=$cache/dnf" \
          --setopt=keepcache=true --setopt=retries=1 --setopt="timeout=$wtl_mirror_connect_seconds" --setopt="minrate=$wtl_mirror_low_rate" --setopt=sslverify=true)
        if [[ " $* " = *' install '* ]]; then
          if wtl_bounded_package_request "$cache" "$log" "${package_command[@]}" --downloadonly "$@"; then
            cat -- "$log"
            if "${package_command[@]}" --cacheonly "$@"; then status=0; else status=$?; fi
            rm -rf -- "$folder"; return "$status"
          else status=$?; fi
        elif wtl_bounded_package_request "$cache" "$log" "${package_command[@]}" "$@"; then status=0; else status=$?; fi
      fi
      wtl_package_log "$log"
      if [ "$status" = 0 ] && [ "$manager" = apt-get ] && [ -n "${wtl_apt_candidate_package:-}" ]; then
        wtl_apt_configure_query
        if wtl_bounded_package_request "$cache" "$folder/candidate" "${query_command[@]}"; then
          wtl_apt_candidate_output="$(cat -- "$folder/candidate")"
          local candidate
          candidate="$(awk '/^[[:space:]]*Candidate:/ { print $2; exit }' "$folder/candidate")"
          if [ -z "$candidate" ] || [ "$candidate" = '(none)' ]; then
            if [ -z "$candidate" ] && [ -s "$folder/candidate" ] && ! grep -Eq '^N: Unable to locate package [A-Za-z0-9+_.:-]+$' "$folder/candidate"; then
              rm -rf -- "$folder"; return 65
            fi
            status=65
            printf 'MIRROR point=%s source=%s attempt=%s reason=metadata-missing-candidate\n' "$point" "$provider" "$attempt" >&2
            break
          fi
        else status=$?; rm -rf -- "$folder"; return "$status"; fi
      fi
      if [ "$status" = 0 ]; then
        if [ "$manager" = apt-get ]; then mkdir -p "$cache/active-sources"; cp -- "$folder/repos/"* "$cache/active-sources/"; fi
        rm -rf -- "$folder"; return 0
      fi
      if [ "$manager" = apt-get ] && [[ " $* " = *' remove '* ]]; then rm -rf -- "$folder"; return "$status"; fi
      # Do not replay failed dpkg/rpm scripts or arbitrary installation errors.
      if ! grep -Eiq 'Failed to fetch|Could not resolve|Connection (failed|timed out|refused|reset)|Timeout|Curl error|Status code: (404|429|5[0-9][0-9])|Hash Sum mismatch|checksum.*(fail|mismatch)|MIRROR_REJECT=(no-progress|low-speed)' "$log"; then
        rm -rf -- "$folder"; return "$status"
      fi
      printf 'MIRROR point=%s source=%s attempt=%s reason=network-or-integrity\n' "$point" "$provider" "$attempt" >&2
      # Native DNF retries=1 already permits one retry (0 would mean infinity).
      [ "$manager" != dnf ] || break
    done
  done
  rm -rf -- "$folder"
  return "$status"
}
wtl_apt() { wtl_system_package apt-get "$@"; }
wtl_dnf() {
  local python= shebang point
  for python in "${WTL_MIRROR_SYSTEM_PYTHON:-}" /usr/bin/python3 /usr/libexec/platform-python; do
    if [ -n "$python" ] && [ -x "$python" ] && "$python" -I -c 'import sys; sys.exit(sys.version_info < (3, 9))' >/dev/null 2>&1; then
      "$python" -I "$WTL_MIRROR_RUNTIME" system-dnf "$@"; return $?
    fi
  done
  if [ -r /usr/bin/dnf ]; then
    IFS= read -r shebang </usr/bin/dnf || true
    if [[ "$shebang" =~ ^\#!(/[^[:space:]]*python[0-9.]*)([[:space:]]|$) ]]; then
      python="${BASH_REMATCH[1]}"
      if [ -x "$python" ] && "$python" -I -c 'import sys; sys.exit(sys.version_info < (3, 9))' >/dev/null 2>&1; then
        "$python" -I "$WTL_MIRROR_RUNTIME" system-dnf "$@"; return $?
      fi
    fi
  fi
  point="$(wtl_system_point)" || return 65
  if [ "$wtl_mirror_attempts" -lt 2 ] || ! wtl_dnf_native_allowed "$point"; then printf 'MIRROR_REJECT=dnf-redirect-boundary-needs-existing-python\n' >&2; return 65; fi
  printf 'MIRROR point=%s source=official reason=native-auto-official-no-python\n' "$point" >&2
  wtl_system_package dnf "$@"
}
