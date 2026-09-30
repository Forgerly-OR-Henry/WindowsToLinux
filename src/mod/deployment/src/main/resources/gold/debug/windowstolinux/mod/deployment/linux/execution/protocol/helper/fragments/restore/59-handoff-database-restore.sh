# Independent database candidates under the original component handoff.
handoff_restore_database_create() {
    local app="$1" candidate="$2" member="$3" artifact="$4" digest="$5" root source target
    root="$(candidate_root "$candidate")"; source="$handoff_restore_root/mutable/restore/$member"
    assert_root_owned_regular "$source"
    [ "$(sha256sum -- "$source" | awk '{print $1}')" = "$digest" ] || reject handoff-restore-database-file-drift
    create_restore_candidate "$app" "$candidate" >/dev/null
    printf '%s\n' "$handoff_restore_owner" >"$root/.handoff-owner"
    chown root:root -- "$root/.handoff-owner"; chmod 400 -- "$root/.handoff-owner"
    target="$(database_artifact_path "$artifact")"
    [ ! -e "$target" ] && [ ! -L "$target" ] || reject handoff-restore-database-artifact-exists
    install -o root -g root -m 400 -- "$source" "$target"
    printf '%s\n%s\n' "$artifact" "$digest" >"$root/.handoff-artifact"
    chown root:root -- "$root/.handoff-artifact"; chmod 400 -- "$root/.handoff-artifact"
    printf 'DATABASE_CANDIDATE_CREATED=1\n'
}

handoff_restore_database_discard() {
    local app="$1" candidate="$3" artifact="$4" type="$5" root="$(candidate_root "$3")" target key
    assert_root_owned_regular "$root/.handoff-artifact"
    mapfile -t source <"$root/.handoff-artifact"
    [ "${source[0]}" = "$artifact" ] || reject handoff-restore-database-artifact-owner
    key="${handoff_restore_step_key#discard-}"
    if [ -e "$handoff_restore_root/.attempt-prepare-$key" ] || [ -L "$handoff_restore_root/.attempt-prepare-$key" ]; then
        assert_root_owned_regular "$handoff_restore_root/.attempt-prepare-$key"
        assert_root_owned_regular "$handoff_restore_root/.done-prepare-$key"
        [ "$(cat -- "$handoff_restore_root/.attempt-prepare-$key")" = "$(printf '%s\n' "$handoff_restore_owner" "prepare-$key" database-restore-candidate "$@")" ] || reject handoff-restore-database-discard-request
        database_discard_candidate "$app" "$2" "$candidate" "$type" "${@:6}" >/dev/null
    fi
    [ ! -e "$root/database/activation-state" ] && [ ! -L "$root/database/activation-state" ] || reject handoff-restore-database-activation-attempted
    if [ "$type" = sqlite ]; then
        database_sqlite_arguments "$app" "${@:6}"
        local journal="$database_sqlite_directory/.restore-${candidate##*-}"
        if [ -e "$journal" ] || [ -L "$journal" ]; then
            assert_root_owned_regular "$handoff_restore_root/.done-recover-$key"
            [ "$(cat -- "$handoff_restore_root/.done-recover-$key")" = "$(printf 'CANDIDATE_ID=%s\nRECOVERED=1\nPREVIOUS_VERIFIED=1\nCANDIDATE_REMOVED=1' "$candidate")" ] || reject handoff-restore-database-recovery-unconfirmed
            assert_root_owned_directory "$journal"; rm -rf --one-file-system -- "$journal"
        fi
    fi
    target="$(database_artifact_path "$artifact")"
    if [ -e "$target" ] || [ -L "$target" ]; then
        assert_root_owned_regular "$target"
        [ "$(sha256sum -- "$target" | awk '{print $1}')" = "${source[1]}" ] || reject handoff-restore-database-artifact-drift
        rm -f -- "$target"
    fi
    rm -rf --one-file-system -- "$root"
    printf 'DATABASE_DISCARDED=1\n'
}

handoff_restore_database_quiesced() {
    local candidate="$1" marker="$(restore_activation_root "$handoff_restore_candidate")/.application-quiesced" root
    assert_root_owned_regular "$marker"
    [ "$(cat -- "$marker")" = "${handoff_restore_task//-/}" ] || reject handoff-restore-database-not-quiesced
    root="$(restore_activation_root "$candidate")"
    install -d -o root -g root -m 700 -- "$root"
    install -o root -g root -m 400 -- "$marker" "$root/.application-quiesced"
}

# Completion follows the local atomic publication commit. The formal database
# is never dropped here; only this original operation's rollback and staging.
handoff_restore_database_finish() {
    database_activation_arguments "$@"
    assert_root_owned_regular "$database_activation_state"
    mapfile -t activation <"$database_activation_state"
    [ "${activation[0]}" = "$database_activation_type" ] && [ "${activation[2]}" = committed ] || reject handoff-restore-database-not-committed
    local credentials client rollback_dump
    if [ "$database_activation_type" = sqlite ]; then
        assert_sqlite_file "$database_sqlite_target"
        [ "$(sqlite3 -readonly -- "$database_sqlite_target" 'PRAGMA integrity_check;' 2>/dev/null)" = ok ] || reject handoff-restore-database-integrity
        assert_root_owned_directory "$database_activation_sqlite_journal"
        rm -rf --one-file-system -- "$database_activation_sqlite_journal"
    elif [ "$database_activation_type" = postgresql ]; then
        credentials="$(database_pgpass "$database_activation_credential_app" "$database_host" "$database_port" '*' "$database_username" "$database_secret_identifier" "$database_secret_revision")"
        database_postgresql_exists "$credentials" "$database_name" || { rm -f -- "$credentials"; reject handoff-restore-formal-database-missing; }
        PGPASSFILE="$credentials" dropdb --if-exists --force --no-password --host="$database_host" --port="$database_port" --username="$database_username" "$database_activation_rollback" || { rm -f -- "$credentials"; reject handoff-restore-database-cleanup; }
        rm -f -- "$credentials"
    else
        rollback_dump="$backups_root/database-rollback-$database_activation_suffix.sql"
        if [ -e "$rollback_dump" ] || [ -L "$rollback_dump" ]; then assert_root_owned_regular "$rollback_dump"; rm -f -- "$rollback_dump"; fi
    fi
    # MySQL retains its temporary import DB; discard addresses only that name.
    database_discard_candidate "$database_activation_app" "$database_activation_credential_app" "$database_activation_candidate" "$database_activation_type" "${@:6}" >/dev/null
    rm -f -- "$database_activation_state" "$database_activation_artifact"
    rm -rf --one-file-system -- "$database_activation_root"
    printf 'DATABASE_FINISHED=1\n'
}

