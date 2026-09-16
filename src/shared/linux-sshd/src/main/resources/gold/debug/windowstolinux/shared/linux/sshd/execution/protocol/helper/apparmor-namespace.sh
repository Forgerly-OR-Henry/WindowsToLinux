    local profile=podman
    local -a namespace_properties=()
    [ "$1" != docker ] || profile=rootlesskit
    # Probe under the engine's existing policy instead of an unrelated unshare label.
    if [ -r /sys/kernel/security/apparmor/profiles ] \
      && grep -q "^$profile (" /sys/kernel/security/apparmor/profiles; then
      namespace_properties+=(--property="AppArmorProfile=$profile")
    fi
