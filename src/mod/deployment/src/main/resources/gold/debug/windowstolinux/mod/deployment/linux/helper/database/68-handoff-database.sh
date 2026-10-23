# Database handoff files are addressed by the original backup operation and
# reviewed binding. A lost reply is reconciled by status, never by exporting again.
handoff_database_identity() {
    [ "$#" -eq 7 ] || reject handoff-database-identity-arguments
    handoff_operation="$1"
    handoff_application="$2"
    handoff_component="$3"
    handoff_app="$4"
    handoff_release="$5"
    handoff_manifest="$6"
    handoff_binding="$7"
    require_backup_operation "$handoff_operation"
    require_app "$handoff_application"
    require_app "$handoff_component"
    require_app "$handoff_app"
    require_digest "$handoff_release"
    require_digest "$handoff_manifest"
    require_app "$handoff_binding"
    handoff_artifact="db-$(printf '%s\0' "$handoff_operation" "$handoff_application" \
        "$handoff_component" "$handoff_app" "$handoff_release" "$handoff_binding" | sha256sum | cut -c1-32)"
    require_artifact_id "$handoff_artifact"
    handoff_root="$(backup_operation_root "$handoff_operation")"
    handoff_marker="$handoff_root/.owner-$handoff_artifact"
    handoff_complete="$handoff_root/.complete-$handoff_artifact"
    handoff_file="$(database_artifact_path "$handoff_artifact")"
    handoff_owner="$(printf '%s\n' "$handoff_application" "$handoff_component" "$handoff_app" \
        "$handoff_release" "$handoff_manifest" "$handoff_binding" "$handoff_artifact")"
}

handoff_database_assert_marker() {
    assert_root_owned_directory "$handoff_root"
    assert_root_owned_regular "$handoff_root/.deployer"
    [ "$(cat -- "$handoff_root/.deployer")" = "$deployer" ] || reject handoff-database-deployer
    assert_root_owned_regular "$handoff_marker"
    [ "$(cat -- "$handoff_marker")" = "$handoff_request" ] || reject handoff-database-owner
}

handoff_database_export() {
    [ "$#" -ge 12 ] || reject handoff-database-export-arguments
    handoff_database_identity "${@:1:7}"
    shift 7
    local maximum="$1" mode="$2" writes="$3" exclusive="$4" type="$5" output size digest id temp
    shift 5
    require_backup_limit "$maximum"
    require_boolean "$writes"
    require_boolean "$exclusive"
    require_database_type "$type"
    handoff_request="$(printf '%s\n' "$handoff_owner" "$maximum" "$mode" "$writes" "$exclusive" "$type" "$@")"
    handoff_operation_lock "$handoff_operation" write
    [ ! -e "$handoff_marker" ] && [ ! -L "$handoff_marker" ] || reject handoff-database-attempted
    [ ! -e "$handoff_complete" ] && [ ! -L "$handoff_complete" ] || reject handoff-database-completed
    [ ! -e "$handoff_file" ] && [ ! -L "$handoff_file" ] || reject handoff-database-artifact-exists
    current_application="$handoff_app"
    if [ -e "$(app_root "$handoff_app")/current/.windowstolinux-container-parameters" ]; then
        container_current_release "$handoff_app" "$handoff_manifest"
    else assert_deployment_current_or_empty "$handoff_app" "$handoff_manifest"; fi
    [ "$previous_present" -eq 1 ] && [ "${previous_path##*/}" = "$handoff_release" ] \
        || reject handoff-database-release
    [ "$managed_data_application" = "$handoff_application" ] \
        && [ "$managed_data_component" = "$handoff_component" ] || reject handoff-database-component
    if [ "$type" = sqlite ]; then
        [ "$#" -eq 4 ] && [ "$1" = "$handoff_binding" ] || reject handoff-database-binding
    else
        [ "$#" -eq 7 ] || reject handoff-database-server-arguments
    fi
    prepare_backup_operation "$handoff_operation"
    printf '%s\n' "$handoff_request" >"$handoff_marker"
    chown root:root -- "$handoff_marker"
    chmod 400 -- "$handoff_marker"
    output="$(database_export --artifact-id "$handoff_artifact" "$handoff_app" "$mode" "$writes" \
        "$exclusive" "$type" "$@")" || reject handoff-database-export-failed
    id="$(printf '%s\n' "$output" | awk -F= '$1=="ARTIFACT_ID"{print $2}')"
    size="$(printf '%s\n' "$output" | awk -F= '$1=="BYTE_COUNT"{print $2}')"
    digest="$(printf '%s\n' "$output" | awk -F= '$1=="SHA256"{print $2}')"
    [ "$id" = "$handoff_artifact" ] || reject handoff-database-artifact-id
    require_artifact_size "$size"
    require_digest "$digest"
    [ "$size" -le "$maximum" ] || reject handoff-database-limit
    assert_root_owned_regular "$handoff_file"
    [ "$(stat -c '%s' -- "$handoff_file")" = "$size" ] \
        && [ "$(sha256sum -- "$handoff_file" | awk '{print $1}')" = "$digest" ] \
        || reject handoff-database-artifact-drift
    temp="$(mktemp "$handoff_root/.seal.XXXXXX")"
    printf '%s\n%s\n%s\n' "$handoff_request" "$size" "$digest" >"$temp"
    chown root:root -- "$temp"
    chmod 400 -- "$temp"
    ln -- "$temp" "$handoff_complete" || reject handoff-database-completion-collision
    rm -f -- "$temp"
    printf 'HANDOFF_DATABASE=1\nARTIFACT_ID=%s\nBYTE_COUNT=%s\nSHA256=%s\n' \
        "$id" "$size" "$digest"
    handoff_operation_unlock
}

handoff_database_status() {
    [ "$#" -ge 12 ] || reject handoff-database-status-arguments
    handoff_database_identity "${@:1:7}"
    handoff_request="$(printf '%s\n' "$handoff_owner" "${@:8}")"
    handoff_operation_lock "$handoff_operation" observe
    if [ ! -e "$handoff_root" ] && [ ! -L "$handoff_root" ]; then
        [ ! -e "$handoff_file" ] && [ ! -L "$handoff_file" ] || reject handoff-database-orphan
        printf 'HANDOFF_DATABASE=1\nPRESENT=0\n'
        handoff_operation_unlock
        return
    fi
    assert_root_owned_directory "$handoff_root"
    assert_root_owned_regular "$handoff_root/.deployer"
    [ "$(cat -- "$handoff_root/.deployer")" = "$deployer" ] || reject handoff-database-deployer
    if [ ! -e "$handoff_marker" ] && [ ! -L "$handoff_marker" ]; then
        [ ! -e "$handoff_complete" ] && [ ! -L "$handoff_complete" ] \
            || reject handoff-database-seal-orphan
        [ ! -e "$handoff_file" ] && [ ! -L "$handoff_file" ] || reject handoff-database-orphan
        printf 'HANDOFF_DATABASE=1\nPRESENT=0\n'
        handoff_operation_unlock
        return
    fi
    handoff_database_assert_marker
    if [ ! -e "$handoff_file" ] && [ ! -L "$handoff_file" ]; then
        [ ! -e "$handoff_complete" ] && [ ! -L "$handoff_complete" ] \
            || reject handoff-database-sealed-file-missing
        printf 'HANDOFF_DATABASE=1\nPRESENT=0\n'
        handoff_operation_unlock
        return
    fi
    assert_root_owned_regular "$handoff_file"
    if [ ! -e "$handoff_complete" ] && [ ! -L "$handoff_complete" ]; then
        printf 'HANDOFF_DATABASE=1\nPRESENT=1\nCOMPLETE=0\n'
        handoff_operation_unlock
        return
    fi
    assert_root_owned_regular "$handoff_complete"
    local size digest expected
    size="$(stat -c '%s' -- "$handoff_file")"
    digest="$(sha256sum -- "$handoff_file" | awk '{print $1}')"
    expected="$(printf '%s\n%s\n%s\n' "$handoff_request" "$size" "$digest")"
    [ "$(cat -- "$handoff_complete")" = "$expected" ] || reject handoff-database-completion-drift
    printf 'HANDOFF_DATABASE=1\nPRESENT=1\nCOMPLETE=1\nARTIFACT_ID=%s\nBYTE_COUNT=%s\nSHA256=%s\n' \
        "$handoff_artifact" "$size" "$digest"
    handoff_operation_unlock
}

handoff_database_discard() {
    [ "$#" -ge 13 ] || reject handoff-database-discard-arguments
    handoff_database_identity "${@:1:7}"
    local expected="${!#}"
    local request_count=$(($# - 1))
    local -a request_args=("${@:1:request_count}")
    handoff_request="$(printf '%s\n' "$handoff_owner" "${request_args[@]:7}")"
    if [ "$expected" != incomplete ]; then require_digest "$expected"; fi
    handoff_operation_lock "$handoff_operation" observe
    handoff_database_assert_marker
    if [ "$expected" = incomplete ]; then
        [ ! -e "$handoff_complete" ] && [ ! -L "$handoff_complete" ] \
            || reject handoff-database-complete-requires-digest
        if [ -e "$handoff_file" ] || [ -L "$handoff_file" ]; then
            assert_root_owned_regular "$handoff_file"
            rm -f -- "$handoff_file"
        fi
        rm -f -- "$handoff_marker"
        printf 'HANDOFF_DATABASE_DISCARDED=1\n'
        handoff_operation_unlock
        return
    fi
    if [ -e "$handoff_file" ] || [ -L "$handoff_file" ]; then
        assert_root_owned_regular "$handoff_file"
        assert_root_owned_regular "$handoff_complete"
        [ "$(cat -- "$handoff_complete")" = \
            "$(printf '%s\n%s\n%s\n' "$handoff_request" "$(stat -c '%s' -- "$handoff_file")" "$expected")" ] \
            || reject handoff-database-completion-drift
        [ "$(sha256sum -- "$handoff_file" | awk '{print $1}')" = "$expected" ] \
            || reject handoff-database-artifact-drift
        rm -f -- "$handoff_file"
    fi
    if [ -e "$handoff_complete" ] || [ -L "$handoff_complete" ]; then
        assert_root_owned_regular "$handoff_complete"
        [ "$(tail -n 1 -- "$handoff_complete")" = "$expected" ] \
            || reject handoff-database-completion-drift
        rm -f -- "$handoff_complete"
    fi
    rm -f -- "$handoff_marker"
    printf 'HANDOFF_DATABASE_DISCARDED=1\n'
    handoff_operation_unlock
}
