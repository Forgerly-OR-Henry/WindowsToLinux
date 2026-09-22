# Temporary container identities never receive SSH or sudo access.
prepare_container_builder() {
    local candidate="$1" user="$2" engine="$3" start end uid gid
    for tool in useradd usermod userdel newuidmap newgidmap setpriv; do command -v "$tool" >/dev/null || reject container-identity-tools; done
    if [ "$engine" = docker ]; then
        command -v rootlesskit >/dev/null && command -v dockerd >/dev/null && command -v slirp4netns >/dev/null || reject docker-rootless-tools
    else
        command -v podman >/dev/null || reject podman-rootless-tools
    fi
    ! getent passwd "$user" >/dev/null && ! getent group "$user" >/dev/null || reject container-identity-collision
    [ ! -e "$candidate/.builder" ] || reject container-identity-record
    exec 8>"$base_root/.subid-allocation.lock"
    flock -x 8
    printf '%s\n' "$user" >"$candidate/.builder-intent"
    chmod 400 -- "$candidate/.builder-intent"
    useradd --system --no-create-home --home-dir "$candidate/mutable/home" --shell /usr/sbin/nologin --user-group "$user" || reject container-identity-create
    uid="$(id -u "$user")"
    gid="$(id -g "$user")"
    printf '%s\n%s\n%s\n0\n0\n' "$user" "$uid" "$gid" >"$candidate/.builder"
    chmod 400 -- "$candidate/.builder"
    rm -f -- "$candidate/.builder-intent"
    start="$(
        /usr/bin/python3 -I - <<'WTL_SUBIDS'
import grp, pwd
from pathlib import Path
ranges = [(p.pw_uid, p.pw_uid) for p in pwd.getpwall()] + [(g.gr_gid, g.gr_gid) for g in grp.getgrall()]
for path in ('/etc/subuid', '/etc/subgid'):
    if Path(path).exists():
        for line in Path(path).read_text().splitlines():
            parts = line.split(':')
            if len(parts) == 3 and parts[1].isdigit() and parts[2].isdigit():
                ranges.append((int(parts[1]), int(parts[1]) + int(parts[2]) - 1))
start = 100000
for low, high in sorted(ranges):
    if start + 65535 < low:
        break
    if start <= high:
        start = high + 1
if start + 65535 > 2147483647:
    raise SystemExit('subordinate ID space exhausted')
print(start)
WTL_SUBIDS
    )" || reject container-id-range
    [[ "$start" =~ ^[0-9]{6,10}$ ]] || reject container-id-range
    end=$((start + 65535))
    printf '%s\n%s\n%s\n%s\n%s\n' "$user" "$uid" "$gid" "$start" "$end" >"$candidate/.builder"
    usermod --add-subuids "$start-$end" --add-subgids "$start-$end" "$user" || reject container-id-grant
    flock -u 8
    exec 8>&-
}
cleanup_container_builder() {
    local candidate="$1" user uid gid start end
    [ ! -e "$candidate/.builder-intent" ] || reject container-identity-creation-unconfirmed
    [ -e "$candidate/.builder" ] || return 0
    assert_root_owned_regular "$candidate/.builder"
    local -a identity
    mapfile -t identity <"$candidate/.builder"
    [ "${#identity[@]}" -eq 5 ] || reject container-identity-record
    user="${identity[0]}"
    uid="${identity[1]}"
    gid="${identity[2]}"
    start="${identity[3]}"
    end="${identity[4]}"
    [[ "$user" =~ ^wtlb-[0-9a-f]{16}$ ]] && [[ "$uid" =~ ^[0-9]+$ ]] && [ "$uid" -gt 0 ] || reject container-identity-record
    local account_present=0
    if getent passwd "$user" >/dev/null; then
        [ "$(id -u "$user")" = "$uid" ] && [ "$(id -g "$user")" = "$gid" ] || reject container-identity-changed
        account_present=1
    else
        ! grep -q "^$user:" /etc/subuid /etc/subgid || reject container-id-unowned-grant
    fi
    ! pgrep -u "$uid" >/dev/null || reject container-identity-processes
    exec 8>"$base_root/.subid-allocation.lock"
    flock -x 8
    if [ "$account_present" = 1 ] && [ "$start" != 0 ]; then
        [[ "$start" =~ ^[0-9]{6,10}$ ]] && [[ "$end" =~ ^[0-9]{6,10}$ ]] && [ "$((end - start))" -eq 65535 ] || reject container-identity-record
        usermod --del-subuids "$start-$end" --del-subgids "$start-$end" "$user" || reject container-id-revoke
    fi
    if [ "$account_present" = 1 ]; then userdel "$user" || reject container-identity-delete; fi
    ! getent passwd "$user" >/dev/null || reject container-identity-remains
    ! grep -q "^$user:" /etc/subuid /etc/subgid || reject container-id-grant-remains
    if getent group "$user" >/dev/null; then
        [ "$(getent group "$user" | cut -d: -f3)" = "$gid" ] || reject container-group-changed
        [ -z "$(getent group "$user" | cut -d: -f4)" ] || reject container-group-members
        groupdel "$user" || reject container-group-delete
    fi
    flock -u 8
    exec 8>&-
    rm -f -- "$candidate/.builder"
}
