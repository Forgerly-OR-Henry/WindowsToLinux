# A root-owned controller launches project code only under a temporary identity.
build_unit_name() { printf 'windowstolinux-build-%s.service' "$1"; }
cargo_prefetch_unit_name() { printf 'windowstolinux-build-%s-cargo.service' "$1"; }
build_identity_name() { printf 'wtlb-%s' "$(printf %s "$1" | sha256sum | cut -c1-16)"; }
prepare_build_identity() {
    [ "$#" -eq 2 ] || { [ "$#" -eq 3 ] && [ "$3" = cargo-prefetch ]; } || reject build-identity-arguments
    require_app "$1"
    require_candidate "$1" "$2"
    local candidate="$(candidate_root "$2")" user uid gid script=.build-script started=.build-started
    if [ "${3:-}" = cargo-prefetch ]; then
        script=.build-prefetch
        started=.build-prefetch-started
    fi
    assert_workspace_volume "$candidate"
    user="$(build_identity_name "$2")"
    uid="$(id -u "$user")"
    gid="$(id -g "$user")"
    [ "$uid" -gt 0 ] && [ "$gid" -gt 0 ] || reject build-identity-root
    # A manager restart must not execute this invocation's author script twice.
    [ ! -e "$candidate/$started" ] && [ ! -L "$candidate/$started" ] || reject build-already-started
    (set -C; : > "$candidate/$started") || reject build-already-started
    chmod 400 -- "$candidate/$started"
    mount -o remount,rw,nodev,nosuid -- "$candidate/mutable" || reject workspace-remount
    chown -hR "$uid:$gid" -- "$candidate/mutable"
    chmod 700 -- "$candidate/mutable"
    if [ -e "$candidate/.agent-mode" ]; then
        assert_root_owned_regular "$candidate/.agent-mode"
        chown -hR root:root -- "$candidate/mutable/input-source"
        find -P "$candidate/mutable/input-source" -type d -exec chmod 555 -- {} +
        find -P "$candidate/mutable/input-source" -type f -exec chmod 444 -- {} +
    fi
    chown "$uid:$gid" -- "$candidate/$script"
    chmod 400 -- "$candidate/$script"
    prepare_build_mirror_material "$candidate" "$uid" "$gid" "$script"
}
stop_candidate_build() {
    local app="$1" candidate_id="$2"
    require_app "$app"
    require_candidate "$app" "$candidate_id"
    stop_build_unit "$(cargo_prefetch_unit_name "$candidate_id")"
    stop_build_unit "$(build_unit_name "$candidate_id")"
}
stop_build_unit() {
    local unit="$1" state
    state="$(systemctl show --value --property LoadState "$unit" 2>/dev/null || true)"
    [ "$state" != not-found ] && [ -n "$state" ] || return 0
    systemctl stop "$unit" || reject build-stop-failed
    state="$(systemctl show --value --property ActiveState "$unit" 2>/dev/null || true)"
    case "$state" in inactive | failed | '') ;; *) reject build-still-active ;; esac
    local group
    group="$(systemctl show --value --property ControlGroup "$unit" 2>/dev/null || true)"
    if [ -n "$group" ] && [ -d "/sys/fs/cgroup$group" ]; then
        [ -z "$(find "/sys/fs/cgroup$group" -name cgroup.procs -exec cat {} +)" ] || reject build-processes-remain
    fi
}
run_restricted_build() {
    [ "$#" -eq 9 ] || reject build-arguments
    local app="$1" candidate_id="$2" seconds="$3" processes="$4" memory="$5" output="$6" engine="$7" script_digest="$8" tools="$9"
    require_app "$app"
    require_candidate "$app" "$candidate_id"
    [[ "$seconds" =~ ^[0-9]{1,4}$ ]] && [ "$seconds" -ge 1 ] && [ "$seconds" -le 7200 ] || reject build-time
    [[ "$processes" =~ ^[0-9]{1,4}$ ]] && [ "$processes" -ge 8 ] && [ "$processes" -le 4096 ] || reject build-processes
    [[ "$memory" =~ ^[0-9]{3,6}$ ]] && [ "$memory" -ge 256 ] && [ "$memory" -le 262144 ] || reject build-memory
    [[ "$output" =~ ^[0-9]{4,9}$ ]] && [ "$output" -ge 4096 ] && [ "$output" -le 134217728 ] || reject build-output
    case "$engine" in ordinary | ordinary-offline | docker | podman) ;; *) reject build-engine ;; esac
    require_digest "$script_digest"
    if [ "$tools" != - ]; then
        [ "${#tools}" -ge 8 ] && [ "${#tools}" -le 87384 ] && [[ "$tools" =~ ^[A-Za-z0-9+/=]+$ ]] || reject build-toolchain-encoding
        local binding
        binding="$(printf '%s' "$tools" | base64 --decode | sha256sum | awk '{print $1}')" || reject build-toolchain-encoding
        parse_toolchain_binding tools-v1 "$binding" build
    fi
    local candidate="$(candidate_root "$candidate_id")" mutable unit user status=0
    local started=$SECONDS phase_header cargo_bytes=0 cargo_unit controller_lock
    mutable="$candidate/mutable"
    unit="$(build_unit_name "$candidate_id")"
    user="$(build_identity_name "$candidate_id")"
    assert_root_owned_regular "$helper_directory/build-entry"
    [ -z "$(find -P "$helper_directory/build-entry" -maxdepth 0 -perm /022 -print -quit)" ] || reject build-entry-writable
    [ "$(sha256sum -- "$helper_directory/build-entry" | awk '{print $1}')" = "$build_entry_sha256" ] || reject build-entry-identity
    assert_candidate_for_deployer "$candidate"
    assert_workspace_volume "$candidate"
    cargo_unit="$(cargo_prefetch_unit_name "$candidate_id")"
    if [ -e "$candidate/.build-controller-lock" ] || [ -L "$candidate/.build-controller-lock" ]; then
        assert_root_owned_regular "$candidate/.build-controller-lock"
    fi
    exec {controller_lock}>>"$candidate/.build-controller-lock"
    flock -n "$controller_lock" || reject build-already-running
    [ ! -e "$candidate/.sealed" ] || reject build-already-sealed
    [ "$(systemctl show --value --property LoadState "$unit" 2>/dev/null || true)" != loaded ] || reject build-already-running
    [ "$(systemctl show --value --property LoadState "$cargo_unit" 2>/dev/null || true)" != loaded ] || reject cargo-prefetch-running
    if [ -e "$candidate/.build-started" ] || [ -L "$candidate/.build-started" ]; then
        assert_root_owned_regular "$candidate/.build-started"
        rm -- "$candidate/.build-started"
    fi
    if [ -e "$candidate/.build-script" ] || [ -L "$candidate/.build-script" ]; then
        assert_root_owned_regular "$candidate/.build-script"
    fi
    head -c 1048577 >"$candidate/.build-script"
    [ "$(stat -c %s "$candidate/.build-script")" -le 1048576 ] || reject build-script-limit
    [ "$(sha256sum -- "$candidate/.build-script" | awk '{print $1}')" = "$script_digest" ] || reject build-script-identity
    chmod 400 -- "$candidate/.build-script"
    IFS= read -r phase_header <"$candidate/.build-script" || phase_header=
    if [[ "$phase_header" == '# WTL_CARGO_PREFETCH_BYTES='* ]]; then
        [ "$engine" = ordinary-offline ] && [ ! -e "$candidate/.agent-mode" ] || reject cargo-prefetch-engine
        assert_root_owned_regular "$candidate/.agent-task"
        cargo_bytes="${phase_header#*=}"
        [[ "$cargo_bytes" =~ ^[1-9][0-9]{0,5}$ ]] || reject cargo-prefetch-length
        local offset=$(("${#phase_header}" + 1)) length
        length="$(stat -c %s -- "$candidate/.build-script")"
        [ "$((offset + cargo_bytes))" -lt "$length" ] || reject cargo-prefetch-length
        assert_cargo_build_scratch "$mutable" "$unit" "$cargo_unit"
        if [ -e "$candidate/.build-prefetch-started" ] || [ -L "$candidate/.build-prefetch-started" ]; then
            assert_root_owned_regular "$candidate/.build-prefetch-started"
            rm -- "$candidate/.build-prefetch-started"
        fi
        stage_cargo_build_data "$candidate" "$offset" "$cargo_bytes"
    fi
    trap 'stop_candidate_build "$app" "$candidate_id"; freeze_candidate_build "$app" "$candidate_id"; cleanup_container_builder "$candidate"' EXIT
    trap 'exit 130' HUP INT TERM
    mount -o remount,rw,nodev,nosuid -- "$mutable" || reject workspace-remount
    # No project configuration is read while creating the execution identity.
    if [ "$engine" = ordinary ] || [ "$engine" = ordinary-offline ]; then
        ! getent passwd "$user" >/dev/null && ! getent group "$user" >/dev/null || reject build-identity-collision
    else
        rm -rf --one-file-system -- "$mutable/engine" "$mutable/home" "$mutable/tmp" "$mutable/run"
        prepare_container_builder "$candidate" "$user" "$engine"
    fi
    if [ "$cargo_bytes" -gt 0 ]; then
        reset_cargo_build_scratch "$mutable"
    fi
    install -d -o root -g root -m 700 -- "$mutable/home" "$mutable/tmp" "$mutable/run"
    local -a properties
    properties=(--property="User=$user" --property="Group=$user"
        --property="TimeoutStopSec=5s" --property=KillMode=control-group --property=SendSIGKILL=yes
        --property="MemoryMax=${memory}M" --property=MemorySwapMax=0 --property="TasksMax=$processes"
        --property=ProtectSystem=strict --property=ProtectHome=yes
        --property=ProtectProc=invisible --property=PrivateTmp=no --property=RemoveIPC=yes
        --property="ReadWritePaths=$mutable" --property="BindPaths=$mutable/tmp:/tmp $mutable/tmp:/var/tmp $mutable/tmp:/dev/shm"
        --property="ExecStopPost=+$helper_path build-freeze $app $candidate_id"
        --property="WorkingDirectory=$mutable" --property=UMask=0077)
    if [ -e "$candidate/.agent-mode" ]; then
        assert_root_owned_regular "$candidate/.agent-mode"
        properties+=(--property="ReadOnlyPaths=$mutable/input-source" --property="InaccessiblePaths=$mutable/source.tar.gz")
    fi
    local isolation_property
    while IFS= read -r isolation_property; do properties+=(--property="$isolation_property"); done \
        < <(render_systemd_manager_isolation "$applications_root" "$secrets_root" "$configurations_root" "$data_root")
    if [ "$engine" = ordinary ] || [ "$engine" = ordinary-offline ]; then
        properties+=(--property=DynamicUser=yes --property=NoNewPrivileges=yes --property=CapabilityBoundingSet= --property=ProcSubset=pid
            --property=RestrictNamespaces=yes --property=RestrictSUIDSGID=yes --property=ProtectControlGroups=yes
            --property=PrivateDevices=yes --property=ProtectKernelTunables=yes --property=ProtectKernelModules=yes --property=ProtectKernelLogs=yes)
        if [ "$cargo_bytes" -gt 0 ]; then
            local remaining=$((seconds - (SECONDS - started)))
            [ "$remaining" -gt 0 ] || reject cargo-prefetch-time
            # This unit starts only the installed data reader and pinned transport. The offline unit is separate.
            monitor_build_output "$candidate" "$output" systemd-run --quiet --wait --pipe --collect --unit="$cargo_unit" \
                "${properties[@]}" --property="RuntimeMaxSec=$remaining" \
                --property="ExecStartPre=+$helper_path build-identity $app $candidate_id cargo-prefetch" \
                /usr/bin/env -i PATH=/usr/bin:/bin HOME="$mutable/home" TMPDIR="$mutable/tmp" \
                /bin/bash "$helper_directory/build-entry" "$candidate" cargo-prefetch "$remaining" || status=$?
            stop_build_unit "$cargo_unit"
            if [ "$status" -ne 0 ]; then
                printf 'CARGO_PREFETCH_FAILED=1\n'
                exit "$status"
            fi
            freeze_candidate_build "$app" "$candidate_id"
            remaining=$((seconds - (SECONDS - started)))
            [ "$remaining" -gt 0 ] || reject cargo-prefetch-time
            seconds="$remaining"
            mount -o remount,rw,nodev,nosuid -- "$mutable" || reject workspace-remount
        fi
        if [ "$engine" = ordinary-offline ]; then
            properties+=("${offline_build_properties[@]}")
        fi
    else
        # Setuid mapping helpers must access hidden proc entries and the target namespace; the user has no effective capabilities.
        properties+=(--property=Delegate=yes --property=ProtectControlGroups=no --property=NoNewPrivileges=no
            --property="CapabilityBoundingSet=CAP_SETUID CAP_SETGID CAP_SYS_PTRACE CAP_DAC_OVERRIDE CAP_SYS_ADMIN" --property=DevicePolicy=closed
            --property="DeviceAllow=/dev/net/tun rw"
            --property="ReadOnlyPaths=/sys -/proc/sys -/proc/sysrq-trigger"
            --property="InaccessiblePaths=-/dev/kmsg -/dev/mem -/dev/kmem -/proc/kcore")
    fi
    properties+=(--property="RuntimeMaxSec=$seconds" --property="ExecStartPre=+$helper_path build-identity $app $candidate_id")
    monitor_build_output "$candidate" "$output" systemd-run --quiet --wait --pipe --collect --unit="$unit" "${properties[@]}" /usr/bin/env -i PATH=/usr/local/bin:/usr/bin:/bin HOME="$mutable/home" TMPDIR="$mutable/tmp" XDG_RUNTIME_DIR="$mutable/run" /bin/bash "$helper_directory/build-entry" "$candidate" "$engine" || status=$?
    trap - HUP INT TERM
    stop_candidate_build "$app" "$candidate_id"
    freeze_candidate_build "$app" "$candidate_id"
    cleanup_container_builder "$candidate"
    if [ "$status" -eq 0 ]; then
        printf 'sealed\n' >"$candidate/.sealed"
        chmod 400 -- "$candidate/.sealed"
    fi
    trap - EXIT
    return "$status"
}

assert_cargo_build_scratch() {
    local mutable="$1" unit state mounts path
    # The caller holds the candidate controller lock. Start with empty scratch even on a compatible retry.
    for unit in "$2" "$3"; do
        state="$(systemctl show --value --property LoadState "$unit")" || reject cargo-scratch-unit-state
        [ "$state" = not-found ] || reject cargo-scratch-unit-state
    done
    assert_root_owned_directory "$mutable"
    mounts="$(findmnt -Rrn -M "$mutable" -o TARGET)" || reject cargo-scratch-mount
    [ "$mounts" = "$mutable" ] || reject cargo-scratch-mount
    for path in "$mutable/home" "$mutable/tmp" "$mutable/run"; do
        if [ -e "$path" ] || [ -L "$path" ]; then
            [ -d "$path" ] && [ ! -L "$path" ] || reject cargo-scratch-path
        fi
    done
}

reset_cargo_build_scratch() {
    local mutable="$1" path
    rm -rf --one-file-system --preserve-root=all -- "$mutable/home" "$mutable/tmp" "$mutable/run" || reject cargo-scratch-reset
    for path in "$mutable/home" "$mutable/tmp" "$mutable/run"; do
        [ ! -e "$path" ] && [ ! -L "$path" ] || reject cargo-scratch-reset
    done
}

stage_cargo_build_data() {
    local candidate="$1" offset="$2" bytes="$3" source="$1/.build-script" prefetch="$1/.build-prefetch" remainder="$1/.build-offline" path
    # Root writes only private candidate files. The payload's first phase is never a shell program.
    for path in "$prefetch" "$remainder"; do
        if [ -e "$path" ] || [ -L "$path" ]; then
            assert_root_owned_regular "$path"
        fi
    done
    /usr/bin/dd if="$source" of="$prefetch" iflag=skip_bytes,count_bytes skip="$offset" count="$bytes" status=none || reject cargo-prefetch-split
    [ "$(stat -c %s -- "$prefetch")" -eq "$bytes" ] || reject cargo-prefetch-split
    /usr/bin/tail -c "+$((offset + bytes + 1))" -- "$source" >"$remainder" || reject cargo-prefetch-split
    chmod 400 -- "$prefetch" "$remainder" || reject cargo-prefetch-split
    mv -fT -- "$remainder" "$source" || reject cargo-prefetch-split
}

freeze_candidate_build() {
    [ "$#" -eq 2 ] || reject build-freeze-arguments
    require_app "$1"
    require_candidate "$1" "$2"
    local candidate="$(candidate_root "$2")"
    assert_candidate_for_deployer "$candidate"
    # ExecStopPost runs after cgroup termination and before DynamicUser release.
    if [[ ",$(findmnt -rn -M "$candidate/mutable" -o OPTIONS)," = *,ro,* ]]; then
        assert_root_owned_directory "$candidate/mutable"
        return 0
    fi
    chown root:root -- "$candidate/mutable"
    chmod 700 -- "$candidate/mutable"
    cleanup_build_mirror_material "$candidate"
    if [ -f "$candidate/.build-prefetch" ]; then
        chown root:root -- "$candidate/.build-prefetch"
        chmod 400 -- "$candidate/.build-prefetch"
    fi
    if [ -f "$candidate/.build-script" ]; then
        chown root:root -- "$candidate/.build-script"
        chmod 400 -- "$candidate/.build-script"
    fi
    if [ ! -e "$candidate/.agent-mode" ] && [ -f "$candidate/mutable/source.tar.gz" ] \
        && [ ! -L "$candidate/mutable/source.tar.gz" ] && [ "$(stat -c '%h' -- "$candidate/mutable/source.tar.gz")" = 1 ]; then
        chown root:root -- "$candidate/mutable/source.tar.gz"
        chmod 400 -- "$candidate/mutable/source.tar.gz"
    fi
    assert_workspace_volume "$candidate"
    mount -o remount,ro,nodev,nosuid -- "$candidate/mutable" || reject workspace-freeze-failed
}
assert_sealed_build() {
    local app="$1" candidate_id="$2" candidate="$(candidate_root "$2")"
    stop_candidate_build "$app" "$candidate_id"
    assert_root_owned_regular "$candidate/.sealed"
    [ "$(cat -- "$candidate/.sealed")" = sealed ] || reject build-not-sealed
    assert_workspace_volume "$candidate"
    [[ ",$(findmnt -rn -M "$candidate/mutable" -o OPTIONS)," = *,ro,* ]] || reject workspace-not-frozen
    [ ! -e "$candidate/.builder" ] || reject build-identity-not-released
}
