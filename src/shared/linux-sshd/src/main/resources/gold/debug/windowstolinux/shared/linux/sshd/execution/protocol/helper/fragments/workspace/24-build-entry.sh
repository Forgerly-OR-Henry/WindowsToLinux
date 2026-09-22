#!/usr/bin/env bash
# Runs as the temporary identity; this file is installed root-owned.
set -euo pipefail
[ "$(id -u)" -gt 0 ] || exit 64
[ "$(awk '/^CapEff:/ {print $2}' /proc/self/status)" = 0000000000000000 ] || exit 64
[ "$#" -eq 2 ] || exit 64
candidate="$1"
engine="$2"
[[ "$candidate" =~ ^/var/lib/windowstolinux/work/[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}$ ]] || exit 64
mutable="$candidate/mutable"
[ -f "$candidate/.build-script" ] && [ ! -L "$candidate/.build-script" ] || exit 64
[ "$(stat -c %d /tmp)" = "$(stat -c %d "$mutable")" ] || exit 64
[ "$(stat -c %d /var/tmp)" = "$(stat -c %d "$mutable")" ] || exit 64
export HOME="$mutable/home" TMPDIR="$mutable/tmp" XDG_RUNTIME_DIR="$mutable/run"
export XDG_CACHE_HOME="$HOME/.cache" XDG_CONFIG_HOME="$HOME/.config" XDG_DATA_HOME="$HOME/.local/share"
if [ -e "$candidate/.agent-mode" ]; then
    export WTL_SOURCE="$mutable/input-source" WTL_OUTPUT="$mutable/source"
    [ ! -w "$WTL_SOURCE" ] || exit 64
    cd -- "$WTL_OUTPUT"
fi
printf 'BUILD_UID=%s\nBUILD_ENGINE=%s\n' "$(id -u)" "$engine"
if [ "$engine" = ordinary ]; then exec /usr/bin/setpriv --no-new-privs /bin/bash "$candidate/.build-script"; fi
[ "$(awk '/^NoNewPrivs:/ {print $2}' /proc/self/status)" = 0 ] || exit 64
# /tmp is already bound to this candidate's volume; short paths fit engine Unix socket limits.
export XDG_RUNTIME_DIR=/tmp/wtl-engine
mkdir -m 700 -- "$XDG_RUNTIME_DIR"
socket="$XDG_RUNTIME_DIR/engine.sock"
daemon=
cleanup_engine() {
    if [ -n "$daemon" ]; then
        kill "$daemon" 2>/dev/null || true
        wait "$daemon" 2>/dev/null || true
    fi
}
trap cleanup_engine EXIT HUP INT TERM
case "$engine" in
    docker)
        parent_user_namespace="$(readlink /proc/self/ns/user)"
        /usr/bin/rootlesskit --state-dir="$XDG_RUNTIME_DIR/rootlesskit" --net=slirp4netns --disable-host-loopback \
            --copy-up=/etc --copy-up=/run /bin/bash -ceu '
        [ "$(id -u)" = 0 ] && [ "$(readlink /proc/self/ns/user)" != "$1" ] || exit 64
        [ "$(stat -f -c %T /run)" = tmpfs ] || exit 64
        shift
        export PATH=/usr/sbin:/usr/bin:/sbin:/bin
        for path in /run/docker /run/containerd /run/xtables.lock; do
          if [ -L "$path" ]; then rm -- "$path"; else [ ! -e "$path" ] || exit 64; fi
        done
        mkdir -m 700 -- "$XDG_RUNTIME_DIR/docker-run" "$XDG_RUNTIME_DIR/containerd-run"
        touch -- "$XDG_RUNTIME_DIR/xtables.lock"
        ln -s -- "$XDG_RUNTIME_DIR/docker-run" /run/docker
        ln -s -- "$XDG_RUNTIME_DIR/containerd-run" /run/containerd
        ln -s -- "$XDG_RUNTIME_DIR/xtables.lock" /run/xtables.lock
        exec /usr/bin/dockerd "$@"
      ' wtl-docker-child "$parent_user_namespace" --rootless --storage-driver=vfs --host="unix://$socket" \
            --data-root="$mutable/engine" --exec-root="$XDG_RUNTIME_DIR/docker" --pidfile="$XDG_RUNTIME_DIR/docker.pid" \
            >"$mutable/engine.log" 2>&1 &
        daemon=$!
        ;;
    podman)
        /usr/bin/podman --root "$mutable/engine" --runroot "$XDG_RUNTIME_DIR/podman" --storage-driver=vfs system service --time=0 "unix://$socket" >"$mutable/engine.log" 2>&1 &
        daemon=$!
        ;;
    *) exit 64 ;;
esac
wait_engine_ready() {
    local attempt
    for attempt in {1..20}; do
        kill -0 "$daemon" 2>/dev/null || break
        if [ -S "$socket" ]; then
            if [ "$engine" = docker ]; then
                if /usr/bin/timeout --kill-after=1s 1s /usr/bin/docker --host="unix://$socket" version >/dev/null 2>&1; then return 0; fi
            else
                if /usr/bin/timeout --kill-after=1s 1s /usr/bin/podman --remote --url="unix://$socket" version >/dev/null 2>&1; then return 0; fi
            fi
        fi
        sleep 0.1
    done
    printf 'BUILD_REJECT=container-engine-not-ready\n'
    tail -c 4096 "$mutable/engine.log"
    return 65
}
wait_engine_ready
export WTL_BUILD_SOCKET="$socket"
/usr/bin/setpriv --no-new-privs /bin/bash "$candidate/.build-script"
# The exported image survives the temporary daemon; the root controller imports it.
