# Admission works from a checked root toward the immutable leaf.
ensure_staging_directory() {
    local path="$1" mode="$2"
    if [ ! -e "$path" ] && [ ! -L "$path" ]; then
        if ! mkdir -m "$mode" -- "$path"; then
            [ -e "$path" ] || [ -L "$path" ] || reject controlled-directory-create
        fi
    fi
    assert_root_owned_directory "$path"
}
assert_staged_payload_identity() {
    local target="$1" expected_digest="$2" expected_size="$3"
    assert_root_owned_regular "$target"
    [ "$(stat -c '%s' -- "$target")" = "$expected_size" ] || reject immutable-input-size
    [ "$(sha256sum -- "$target" | awk '{print $1}')" = "$expected_digest" ] || reject immutable-input-digest
}
receive_immutable_payload() {
    local target="$1" expected_digest="$2" expected_size="$3" parent tmp actual_size
    parent="${target%/*}"
    assert_root_owned_directory "$parent"
    if [ -e "$target" ] || [ -L "$target" ]; then
        assert_staged_payload_identity "$target" "$expected_digest" "$expected_size"
        dd bs=1 count="$expected_size" of=/dev/null status=none
        printf 'INPUT_PRESENT=1\n'
        return
    fi
    tmp="$(mktemp "$parent/.windowstolinux-input.XXXXXX")"
    trap 'rm -f -- "$tmp"' EXIT
    dd bs=1 count="$expected_size" of="$tmp" status=none
    actual_size="$(stat -c '%s' -- "$tmp")"
    [ "$actual_size" = "$expected_size" ] || reject input-size
    [ "$(sha256sum -- "$tmp" | awk '{print $1}')" = "$expected_digest" ] || reject input-digest
    chmod 400 -- "$tmp"
    if ln -T -- "$tmp" "$target"; then
        rm -f -- "$tmp"
        trap - EXIT
        printf 'INPUT_STAGED=1\n'
    else
        assert_staged_payload_identity "$target" "$expected_digest" "$expected_size"
        rm -f -- "$tmp"
        trap - EXIT
        printf 'INPUT_PRESENT=1\n'
    fi
}
stage_configuration() {
    [ "$#" -eq 5 ] || reject stage-config-arguments
    local app="$1" configuration_digest="$2" format="$3" payload_digest="$4" payload_size="$5" directory target
    require_app "$app"
    require_digest "$configuration_digest"
    require_digest "$payload_digest"
    require_payload_size "$payload_size"
    [ "$format" = systemd ] || [ "$format" = container ] || reject configuration-format
    initialise_controlled_roots
    directory="$(configuration_directory "$app" "$configuration_digest")"
    ensure_staging_directory "$configurations_root/$app" 755
    ensure_staging_directory "$directory" 700
    target="$(configuration_path "$app" "$configuration_digest" "$format")"
    receive_immutable_payload "$target" "$payload_digest" "$payload_size"
}
stage_secret() {
    [ "$#" -eq 5 ] || reject stage-secret-arguments
    local app="$1" identifier="$2" revision="$3" payload_digest="$4" payload_size="$5" directory target
    require_app "$app"
    require_secret_identifier "$identifier"
    require_revision "$revision"
    require_digest "$payload_digest"
    require_payload_size "$payload_size"
    [ "$payload_size" -gt 0 ] || reject secret-empty
    initialise_controlled_roots
    directory="$(secret_revision_directory "$app" "$identifier" "$revision")"
    ensure_staging_directory "$secrets_root/$app" 755
    ensure_staging_directory "$secrets_root/$app/secrets" 700
    ensure_staging_directory "$secrets_root/$app/secrets/$identifier" 700
    ensure_staging_directory "$directory" 700
    target="$(secret_revision_path "$app" "$identifier" "$revision")"
    receive_immutable_payload "$target" "$payload_digest" "$payload_size"
}
