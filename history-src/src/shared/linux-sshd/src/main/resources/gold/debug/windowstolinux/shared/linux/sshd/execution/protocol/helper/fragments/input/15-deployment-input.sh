configuration_directory() {
    printf '%s/%s/%s' "$configurations_root" "$1" "$2"
}
configuration_path() {
    printf '%s/%s.env' "$(configuration_directory "$1" "$2")" "$3"
}
secret_revision_directory() {
    printf '%s/%s/secrets/%s/%s' "$secrets_root" "$1" "$2" "$3"
}
secret_revision_path() {
    printf '%s/value' "$(secret_revision_directory "$1" "$2" "$3")"
}
require_payload_size() {
    [[ "$1" =~ ^[0-9]{1,6}$ ]] || reject payload-size
    [ "$1" -le 65536 ] || reject payload-size
}
receive_immutable_payload() {
    local target="$1" expected_digest="$2" expected_size="$3" parent tmp actual_size
    parent="${target%/*}"
    install -d -o root -g root -m 700 -- "$parent"
    assert_root_owned_directory "$parent"
    if [ -e "$target" ] || [ -L "$target" ]; then
        assert_root_owned_regular "$target"
        [ "$(stat -c '%s' -- "$target")" = "$expected_size" ] || reject immutable-input-size
        [ "$(sha256sum -- "$target" | awk '{print $1}')" = "$expected_digest" ] || reject immutable-input-digest
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
    install -o root -g root -m 400 -- "$tmp" "$target"
    rm -f -- "$tmp"
    trap - EXIT
    printf 'INPUT_STAGED=1\n'
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
    install -d -o root -g root -m 755 -- "$configurations_root/$app"
    install -d -o root -g root -m 700 -- "$directory"
    assert_root_owned_directory "$configurations_root/$app"
    assert_root_owned_directory "$directory"
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
    install -d -o root -g root -m 755 -- "$secrets_root/$app"
    install -d -o root -g root -m 700 -- "$secrets_root/$app/secrets" "$secrets_root/$app/secrets/$identifier" "$directory"
    assert_root_owned_directory "$secrets_root/$app"
    assert_root_owned_directory "$secrets_root/$app/secrets/$identifier"
    assert_root_owned_directory "$directory"
    target="$(secret_revision_path "$app" "$identifier" "$revision")"
    receive_immutable_payload "$target" "$payload_digest" "$payload_size"
}
normalize_secret_environment_name() {
    printf 'WINDOWSTOLINUX_SECRET_%s_FILE' "$(printf '%s' "$1" | tr '[:lower:].-' '[:upper:]__')"
}
parse_deployment_inputs() {
    [ "$#" -ge 2 ] || reject deployment-input-arguments
    [ "$1" = identity-v2 ] || reject unsupported-runtime-format
    [ "$1" = identity-v2 ] || reject unsupported-runtime-format
    if [ "$1" = identity-v2 ]; then
        [ "$#" -ge 4 ] || reject runtime-identity-arguments
        runtime_identity_policy="$2"
        parse_application_payload "$3"
        shift 3
        case "$runtime_identity_policy" in SYSTEMD_STATIC | CONTAINER_NON_ROOT) ;; *) reject runtime-identity-policy ;; esac
    fi
    deployment_configuration_digest="$1"
    shift
    require_digest "$deployment_configuration_digest"
    require_count "$1"
    local secret_count="$1"
    shift
    deployment_secret_identifiers=()
    deployment_secret_revisions=()
    deployment_secret_digests=()
    deployment_secret_names=()
    local identifier revision digest name existing
    while [ "$secret_count" -gt 0 ]; do
        [ "$#" -ge 3 ] || reject deployment-secret-arguments
        identifier="$1"
        revision="$2"
        digest="$3"
        shift 3
        require_secret_identifier "$identifier"
        require_revision "$revision"
        require_digest "$digest"
        name="$(normalize_secret_environment_name "$identifier")"
        for existing in "${deployment_secret_names[@]}"; do [ "$existing" != "$name" ] || reject secret-name-collision; done
        deployment_secret_identifiers+=("$identifier")
        deployment_secret_revisions+=("$revision")
        deployment_secret_digests+=("$digest")
        deployment_secret_names+=("$name")
        secret_count=$((secret_count - 1))
    done
    deployment_remaining_arguments=("$@")
}
assert_deployment_inputs() {
    local app="$1" format="$2" config index secret
    config="$(configuration_path "$app" "$deployment_configuration_digest" "$format")"
    assert_root_owned_regular "$config"
    index=0
    while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
        secret="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
        assert_root_owned_regular "$secret"
        [ "$(sha256sum -- "$secret" | awk '{print $1}')" = "${deployment_secret_digests[$index]}" ] || reject secret-digest
        index=$((index + 1))
    done
}
