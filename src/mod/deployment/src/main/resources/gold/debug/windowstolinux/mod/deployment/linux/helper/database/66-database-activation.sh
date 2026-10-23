database_activation_arguments() {
    [ "$#" -ge 6 ] || reject database-activation-arguments
    database_activation_app="$1"
    database_activation_credential_app="$2"
    database_activation_candidate="$3"
    database_activation_artifact_id="$4"
    database_activation_type="$5"
    shift 5
    require_app "$database_activation_app"
    require_app "$database_activation_credential_app"
    require_candidate "$database_activation_app" "$database_activation_candidate"
    require_artifact_id "$database_activation_artifact_id"
    require_database_type "$database_activation_type"
    database_activation_root="$(candidate_root "$database_activation_candidate")"
    assert_candidate_for_deployer "$database_activation_root"
    database_activation_artifact="$(database_artifact_path "$database_activation_artifact_id")"
    assert_root_owned_regular "$database_activation_artifact"
    database_activation_state="$database_activation_root/database/activation-state"
    database_activation_suffix="${database_activation_candidate##*-}"
    database_activation_database="w2l_${database_activation_suffix}"
    database_activation_rollback="w2l_prev_${database_activation_suffix}"
    if [ "$database_activation_type" = sqlite ]; then
        database_sqlite_arguments "$database_activation_credential_app" "$@"
        assert_root_owned_regular "$database_activation_root/database/sqlite-binding"
        [ "$(cat -- "$database_activation_root/database/sqlite-binding")" = "$(printf '%s\n' "$database_activation_credential_app" "$database_sqlite_binding" "$database_sqlite_location_type" "$database_sqlite_location_path" "$database_sqlite_file")" ] || reject database-candidate-binding
        database_activation_sqlite_candidate="$database_activation_root/database/$database_sqlite_binding/$database_sqlite_file"
        database_activation_sqlite_journal="$database_sqlite_directory/.restore-$database_activation_suffix"
    else
        database_server_arguments "$database_activation_type" "$@"
        if [ "$database_activation_type" = postgresql ]; then [ "${#database_name}" -le 63 ] || reject database-postgresql-name-too-long; fi
    fi
}
database_postgresql_exists() {
    local credentials="$1" name="$2"
    PGPASSFILE="$credentials" psql --no-password --host="$database_host" --port="$database_port" \
        --username="$database_username" --dbname=postgres -Atc "SELECT 1 FROM pg_database WHERE datname='$name'" 2>/dev/null | grep -qx 1
}
database_mysql_exists() {
    local client="$1" credentials="$2" name="$3"
    run_mysql_client "$client" "$credentials" --host="$database_host" --port="$database_port" \
        --user="$database_username" --batch --skip-column-names -e \
        "SELECT 1 FROM information_schema.schemata WHERE schema_name='$name'" 2>/dev/null | grep -qx 1
}
database_commit_postgresql() {
    local credentials previous=0 sql
    credentials="$(database_pgpass "$database_activation_credential_app" "$database_host" "$database_port" '*' "$database_username" "$database_secret_identifier" "$database_secret_revision")"
    database_postgresql_exists "$credentials" "$database_activation_database" || {
        rm -f -- "$credentials"
        reject database-candidate-missing
    }
    ! database_postgresql_exists "$credentials" "$database_activation_rollback" || {
        rm -f -- "$credentials"
        reject database-rollback-exists
    }
    if database_postgresql_exists "$credentials" "$database_name"; then previous=1; fi
    printf '%s\n%s\nprepared\n' "$database_activation_type" "$previous" >"$database_activation_state"
    chown root:root -- "$database_activation_state"
    chmod 400 -- "$database_activation_state"
    sql="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN ('$database_name','$database_activation_database') AND pid <> pg_backend_pid();"
    PGPASSFILE="$credentials" psql --no-password --host="$database_host" --port="$database_port" --username="$database_username" --dbname=postgres -v ON_ERROR_STOP=1 -c "$sql" >/dev/null || {
        rm -f -- "$credentials"
        reject database-commit-terminate
    }
    if [ "$previous" = 1 ]; then PGPASSFILE="$credentials" psql --no-password --host="$database_host" --port="$database_port" --username="$database_username" --dbname=postgres -v ON_ERROR_STOP=1 -c "ALTER DATABASE \"$database_name\" RENAME TO \"$database_activation_rollback\"" >/dev/null || {
        rm -f -- "$credentials"
        reject database-commit-rename-old
    }; fi
    PGPASSFILE="$credentials" psql --no-password --host="$database_host" --port="$database_port" --username="$database_username" --dbname=postgres -v ON_ERROR_STOP=1 -c "ALTER DATABASE \"$database_activation_database\" RENAME TO \"$database_name\"" >/dev/null || {
        rm -f -- "$credentials"
        reject database-commit-rename-new
    }
    database_postgresql_exists "$credentials" "$database_name" || {
        rm -f -- "$credentials"
        reject database-commit-verify
    }
    printf '%s\n%s\ncommitted\n' "$database_activation_type" "$previous" >"$database_activation_state"
    rm -f -- "$credentials"
}
database_commit_mysql() {
    local client dump credentials previous=0 rollback_dump="$backups_root/database-rollback-$database_activation_suffix.sql"
    if [ "$database_activation_type" = mariadb ]; then
        client="$(command -v mariadb)"
        dump="$(command -v mariadb-dump)"
    else
        client="$(command -v mysql)"
        dump="$(command -v mysqldump)"
    fi
    credentials="$(database_mysql_defaults "$database_activation_credential_app" "$database_activation_type" "$database_secret_identifier" "$database_secret_revision" "$database_tls")"
    database_mysql_exists "$client" "$credentials" "$database_activation_database" || {
        rm -f -- "$credentials"
        reject database-candidate-missing
    }
    [ ! -e "$rollback_dump" ] && [ ! -L "$rollback_dump" ] || {
        rm -f -- "$credentials"
        reject database-rollback-exists
    }
    if database_mysql_exists "$client" "$credentials" "$database_name"; then
        previous=1
        run_mysql_client "$dump" "$credentials" --host="$database_host" --port="$database_port" --user="$database_username" --lock-all-tables --routines --events --triggers "$database_name" >"$rollback_dump" || {
            rm -f -- "$credentials" "$rollback_dump"
            reject database-rollback-export
        }
        chown root:root -- "$rollback_dump"
        chmod 400 -- "$rollback_dump"
    fi
    printf '%s\n%s\nprepared\n' "$database_activation_type" "$previous" >"$database_activation_state"
    chown root:root -- "$database_activation_state"
    chmod 400 -- "$database_activation_state"
    run_mysql_client "$client" "$credentials" --host="$database_host" --port="$database_port" --user="$database_username" -e "DROP DATABASE IF EXISTS \`$database_name\`; CREATE DATABASE \`$database_name\`" || {
        rm -f -- "$credentials"
        reject database-commit-create
    }
    run_mysql_client "$client" "$credentials" --host="$database_host" --port="$database_port" --user="$database_username" "$database_name" <"$database_activation_artifact" || {
        rm -f -- "$credentials"
        reject database-commit-import
    }
    database_mysql_exists "$client" "$credentials" "$database_name" || {
        rm -f -- "$credentials"
        reject database-commit-verify
    }
    printf '%s\n%s\ncommitted\n' "$database_activation_type" "$previous" >"$database_activation_state"
    rm -f -- "$credentials"
}
sqlite_file_set_digest() {
    local target="$1" suffix
    for suffix in '' -wal -shm -journal; do
        if [ -e "$target$suffix" ]; then
            printf '%s:%s\n' "${suffix:-main}" "$(sha256sum -- "$target$suffix" | awk '{print $1}')"
        else printf '%s:-\n' "${suffix:-main}"; fi
    done | sha256sum | awk '{print $1}'
}
database_commit_sqlite() {
    local target="$database_sqlite_target" journal="$database_activation_sqlite_journal" previous=0 digest=none owner suffix
    current_application="$database_activation_credential_app"
    binding="$database_sqlite_binding"
    storage_kind=DATABASE
    location_type="$database_sqlite_location_type"
    location_path="$database_sqlite_location_path"
    assert_storage_parent "$database_sqlite_directory"
    record_storage_binding "$database_sqlite_directory"
    prepare_service_identity "$current_application"
    owner="$(service_identity_name "$current_application")"
    if [ -e "$database_activation_root/database/sqlite-owner" ]; then
        assert_root_owned_regular "$database_activation_root/database/sqlite-owner"
        owner="$(cat -- "$database_activation_root/database/sqlite-owner")"
        [[ "$owner" =~ ^[1-9][0-9]*:[1-9][0-9]*$ ]] || reject database-owner
    fi
    install -d -o root -g root -m 755 -- "${database_sqlite_directory%/*}"
    if [ ! -e "$database_sqlite_directory" ]; then install -d -o "${owner%%:*}" -g "${owner#*:}" -m 700 -- "$database_sqlite_directory"; fi
    [ ! -e "$journal" ] && [ ! -L "$journal" ] || reject database-rollback-exists
    assert_sqlite_file "$database_activation_sqlite_candidate"
    install -d -o root -g root -m 700 -- "$journal/previous" "$journal/failed"
    sqlite3 -readonly -- "$database_activation_sqlite_candidate" ".backup '$journal/next'" || reject database-commit-copy
    [ "$(sqlite3 -readonly -- "$journal/next" 'PRAGMA integrity_check;' 2>/dev/null)" = ok ] || reject database-commit-integrity
    if [ -e "$target" ] || [ -L "$target" ]; then
        assert_sqlite_file "$target"
        previous=1
        digest="$(sqlite_file_set_digest "$target")"
    fi
    printf 'sqlite\n%s\nprepared\n%s\n' "$previous" "$digest" >"$database_activation_state"
    chmod 400 -- "$database_activation_state"
    sync -f "$database_activation_state"
    sync -f "$journal/next"
    for suffix in '' -wal -shm -journal; do
        if [ -e "$target$suffix" ]; then mv -T -- "$target$suffix" "$journal/previous/value$suffix"; fi
    done
    sync -f "$database_sqlite_directory"
    chown "${owner%%:*}:${owner#*:}" -- "$journal/next"
    chmod 600 -- "$journal/next"
    mv -T -- "$journal/next" "$target"
    sync -f "$database_sqlite_directory"
    printf 'sqlite\n%s\ncommitted\n%s\n' "$previous" "$digest" >"$database_activation_state"
    sync -f "$database_activation_state"
}
database_recover_sqlite() {
    local previous="$1" target="$database_sqlite_target" journal="$database_activation_sqlite_journal" suffix old_digest="${activation[3]}"
    assert_storage_parent "$database_sqlite_directory"
    assert_root_owned_directory "$journal"
    if [ "$previous" = 1 ] && [ -e "$journal/previous/value" ]; then
        # Complete a partially interrupted move before checking the retained file set.
        for suffix in -wal -shm -journal; do
            if [ ! -e "$journal/previous/value$suffix" ] && [ -e "$target$suffix" ] && [ ! -e "$target" ]; then
                [ ! -L "$target$suffix" ] || reject database-recovery-companion
                mv -T -- "$target$suffix" "$journal/previous/value$suffix"
            fi
        done
        assert_sqlite_file "$journal/previous/value"
        [ "$(sqlite_file_set_digest "$journal/previous/value")" = "$old_digest" ] || reject database-rollback-digest
        if [ -e "$target" ] || [ -L "$target" ]; then assert_sqlite_file "$target"; fi
        for suffix in '' -wal -shm -journal; do
            if [ -e "$target$suffix" ]; then mv -T -- "$target$suffix" "$journal/failed/value$suffix"; fi
            if [ -e "$journal/previous/value$suffix" ]; then mv -T -- "$journal/previous/value$suffix" "$target$suffix"; fi
        done
    elif [ "$previous" = 1 ]; then
        assert_sqlite_file "$target"
        [ "$(sqlite_file_set_digest "$target")" = "$old_digest" ] || reject database-rollback-unprovable
    else
        if [ -e "$target" ] || [ -L "$target" ]; then assert_sqlite_file "$target"; fi
        for suffix in '' -wal -shm -journal; do if [ -e "$target$suffix" ]; then mv -T -- "$target$suffix" "$journal/failed/value$suffix"; fi; done
    fi
    sync -f "$database_sqlite_directory"
    if [ "$previous" = 1 ]; then
        [ "$(sqlite3 -readonly -- "$target" 'PRAGMA integrity_check;' 2>/dev/null)" = ok ] || reject database-recovery-integrity
    else [ ! -e "$target" ] && [ ! -L "$target" ] || reject database-recovery-absence; fi
    database_discard_candidate "$database_activation_app" "$database_activation_credential_app" "$database_activation_candidate" sqlite \
        "$database_sqlite_binding" "$database_sqlite_location_type" "$database_sqlite_location_path" "$database_sqlite_file" >/dev/null
}
database_commit_candidate() {
    database_activation_arguments "$@"
    assert_root_owned_regular "$(restore_activation_root "$database_activation_candidate")/.application-quiesced"
    [ ! -e "$database_activation_state" ] && [ ! -L "$database_activation_state" ] || reject database-activation-exists
    if [ "$database_activation_type" = sqlite ]; then
        database_commit_sqlite
    elif [ "$database_activation_type" = postgresql ]; then database_commit_postgresql; else database_commit_mysql; fi
    printf 'CANDIDATE_ID=%s\nCOMMITTED=1\nPREVIOUS_RETAINED=1\n' "$database_activation_candidate"
}
database_recover_postgresql() {
    local previous="$1" credentials sql
    credentials="$(database_pgpass "$database_activation_credential_app" "$database_host" "$database_port" '*' "$database_username" "$database_secret_identifier" "$database_secret_revision")"
    sql="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN ('$database_name','$database_activation_database','$database_activation_rollback') AND pid <> pg_backend_pid();"
    PGPASSFILE="$credentials" psql --no-password --host="$database_host" --port="$database_port" --username="$database_username" --dbname=postgres -v ON_ERROR_STOP=1 -c "$sql" >/dev/null || {
        rm -f -- "$credentials"
        reject database-recover-terminate
    }
    if [ "$previous" = 1 ] && ! database_postgresql_exists "$credentials" "$database_activation_rollback"; then
        database_postgresql_exists "$credentials" "$database_name" || {
            rm -f -- "$credentials"
            reject database-rollback-missing
        }
    else
        if database_postgresql_exists "$credentials" "$database_name"; then PGPASSFILE="$credentials" dropdb --force --no-password --host="$database_host" --port="$database_port" --username="$database_username" "$database_name" >/dev/null || {
            rm -f -- "$credentials"
            reject database-recover-drop
        }; fi
        if [ "$previous" = 1 ]; then
            PGPASSFILE="$credentials" psql --no-password --host="$database_host" --port="$database_port" --username="$database_username" --dbname=postgres -v ON_ERROR_STOP=1 -c "ALTER DATABASE \"$database_activation_rollback\" RENAME TO \"$database_name\"" >/dev/null || {
                rm -f -- "$credentials"
                reject database-recover-rename
            }
            database_postgresql_exists "$credentials" "$database_name" || {
                rm -f -- "$credentials"
                reject database-recover-verify
            }
        else ! database_postgresql_exists "$credentials" "$database_name" || {
            rm -f -- "$credentials"
            reject database-recover-absence
        }; fi
    fi
    if database_postgresql_exists "$credentials" "$database_activation_database"; then PGPASSFILE="$credentials" dropdb --force --no-password --host="$database_host" --port="$database_port" --username="$database_username" "$database_activation_database" >/dev/null || {
        rm -f -- "$credentials"
        reject database-candidate-discard-failed
    }; fi
    rm -f -- "$credentials"
}
database_recover_mysql() {
    local previous="$1" client credentials rollback_dump="$backups_root/database-rollback-$database_activation_suffix.sql"
    if [ "$database_activation_type" = mariadb ]; then client="$(command -v mariadb)"; else client="$(command -v mysql)"; fi
    credentials="$(database_mysql_defaults "$database_activation_credential_app" "$database_activation_type" "$database_secret_identifier" "$database_secret_revision" "$database_tls")"
    run_mysql_client "$client" "$credentials" --host="$database_host" --port="$database_port" --user="$database_username" -e "DROP DATABASE IF EXISTS \`$database_name\`" || {
        rm -f -- "$credentials"
        reject database-recover-drop
    }
    if [ "$previous" = 1 ]; then
        assert_root_owned_regular "$rollback_dump"
        run_mysql_client "$client" "$credentials" --host="$database_host" --port="$database_port" --user="$database_username" -e "CREATE DATABASE \`$database_name\`" || {
            rm -f -- "$credentials"
            reject database-recover-create
        }
        run_mysql_client "$client" "$credentials" --host="$database_host" --port="$database_port" --user="$database_username" "$database_name" <"$rollback_dump" || {
            rm -f -- "$credentials"
            reject database-recover-import
        }
        database_mysql_exists "$client" "$credentials" "$database_name" || {
            rm -f -- "$credentials"
            reject database-recover-verify
        }
    else ! database_mysql_exists "$client" "$credentials" "$database_name" || {
        rm -f -- "$credentials"
        reject database-recover-absence
    }; fi
    run_mysql_client "$client" "$credentials" --host="$database_host" --port="$database_port" --user="$database_username" -e "DROP DATABASE IF EXISTS \`$database_activation_database\`" || {
        rm -f -- "$credentials"
        reject database-candidate-discard-failed
    }
    rm -f -- "$credentials" "$rollback_dump"
}
database_recover_candidate() {
    local -a original=("$@")
    database_activation_arguments "$@"
    if [ ! -e "$database_activation_state" ] && [ ! -L "$database_activation_state" ]; then
        database_discard_candidate "${database_activation_app}" "${database_activation_credential_app}" "${database_activation_candidate}" "${database_activation_type}" "${original[@]:5}" >/dev/null
    else
        assert_root_owned_regular "$database_activation_state"
        mapfile -t activation <"$database_activation_state"
        [ "${activation[0]}" = "$database_activation_type" ] || reject database-recovery-type
        [ "${activation[1]}" = 0 ] || [ "${activation[1]}" = 1 ] || reject database-recovery-state
        if [ "$database_activation_type" = sqlite ]; then
            database_recover_sqlite "${activation[1]}"
        elif [ "$database_activation_type" = postgresql ]; then database_recover_postgresql "${activation[1]}"; else database_recover_mysql "${activation[1]}"; fi
        rm -f -- "$database_activation_state"
    fi
    printf 'CANDIDATE_ID=%s\nRECOVERED=1\nPREVIOUS_VERIFIED=1\nCANDIDATE_REMOVED=1\n' "$database_activation_candidate"
}
