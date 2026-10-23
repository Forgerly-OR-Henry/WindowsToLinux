database_export() {
    local explicit_artifact_id=
    if [ "${1:-}" = --artifact-id ]; then
        [ "$#" -ge 8 ] || reject database-export-arguments
        explicit_artifact_id="$2"
        require_artifact_id "$explicit_artifact_id"
        [[ "$explicit_artifact_id" =~ ^db-[0-9a-f]{32}$ ]] || reject database-artifact-id
        shift 2
    fi
    [ "$#" -ge 6 ] || reject database-export-arguments
    local app="$1" mode="$2" writes_stopped="$3" exclusive="$4" type="$5" inspection engine tool transactional
    shift 5
    require_app "$app"
    require_boolean "$writes_stopped"
    require_boolean "$exclusive"
    require_database_type "$type"
    inspection="$(database_inspect "$app" "$type" "$@")"
    [ "$(printf '%s\n' "$inspection" | awk -F= '$1=="TOOL_AVAILABLE"{print $2}')" = 1 ] || reject database-tool-unavailable
    [ "$(printf '%s\n' "$inspection" | awk -F= '$1=="ENGINE_COMPATIBLE"{print $2}')" = 1 ] || reject database-version-incompatible
    engine="$(printf '%s\n' "$inspection" | awk -F= '$1=="ENGINE_VERSION"{print $2}')"
    tool="$(printf '%s\n' "$inspection" | awk -F= '$1=="TOOL_VERSION"{print $2}')"
    transactional="$(printf '%s\n' "$inspection" | awk -F= '$1=="ALL_TABLES_TRANSACTIONAL"{print $2}')"
    initialise_controlled_roots
    local artifact_id artifact tmp limited=0
    artifact_id="${explicit_artifact_id:-db-$(cat /proc/sys/kernel/random/uuid | tr -d '-')}"
    require_artifact_id "$artifact_id"
    artifact="$(database_artifact_path "$artifact_id")"
    [ ! -e "$artifact" ] && [ ! -L "$artifact" ] || reject database-artifact-collision
    tmp="$(mktemp "$backups_root/.database.XXXXXX")"
    trap 'rm -f -- "$tmp"' EXIT
    if [ "$type" = sqlite ]; then
        [ "$#" -eq 4 ] || reject database-sqlite-arguments
        local source
        source="$(database_source_path "$app" "$@")"
        if [ "$mode" = sqlite-online ]; then
            sqlite3 -readonly -- "$source" ".backup '$tmp'"
        elif [ "$mode" = sqlite-stopped ] && [ "$writes_stopped" = 1 ] && [ "$exclusive" = 1 ]; then
            ! systemctl is-active --quiet "$(unit_name "$app")" || reject database-writes-active
            sqlite3 -readonly -- "$source" ".backup '$tmp'"
        else reject database-consistency-mode; fi
        [ "$(sqlite3 -- "$tmp" 'PRAGMA integrity_check;' 2>/dev/null)" = ok ] || reject database-export-integrity
    else
        database_server_arguments "$type" "$@"
        if [ "$type" = postgresql ]; then
            [ "$mode" = postgresql-logical ] || reject database-consistency-mode
            local pgpass
            pgpass="$(database_pgpass "$app" "$database_host" "$database_port" "$database_name" "$database_username" "$database_secret_identifier" "$database_secret_revision")"
            if ! PGPASSFILE="$pgpass" pg_dump --no-password --format=custom --host="$database_host" --port="$database_port" --username="$database_username" --dbname="$database_name" --file="$tmp"; then
                rm -f -- "$pgpass"
                reject database-export-failed
            fi
            rm -f -- "$pgpass"
        else
            local dump defaults transaction_option
            if [ "$type" = mariadb ]; then dump="$(command -v mariadb-dump)"; else dump="$(command -v mysqldump)"; fi
            defaults="$(database_mysql_defaults "$app" "$type" "$database_secret_identifier" "$database_secret_revision" "$database_tls")"
            if [ "$mode" = mysql-transaction ] && [ "$transactional" = 1 ]; then
                transaction_option=--single-transaction
            elif [ "$mode" = mysql-stopped ] && [ "$writes_stopped" = 1 ] && [ "$exclusive" = 1 ]; then
                ! systemctl is-active --quiet "$(unit_name "$app")" || reject database-writes-active
                transaction_option=--lock-all-tables
                limited=1
            else
                rm -f -- "$defaults"
                reject database-consistency-mode
            fi
            if ! run_mysql_client "$dump" "$defaults" --host="$database_host" --port="$database_port" --user="$database_username" "$transaction_option" --routines --events --triggers "$database_name" >"$tmp"; then
                rm -f -- "$defaults"
                reject database-export-failed
            fi
            rm -f -- "$defaults"
        fi
    fi
    [ -s "$tmp" ] || reject database-export-empty
    install -o root -g root -m 400 -- "$tmp" "$artifact"
    rm -f -- "$tmp"
    trap - EXIT
    printf 'TYPE=%s\nENGINE_VERSION=%s\nTOOL_VERSION=%s\nCONSISTENCY_MODE=%s\nARTIFACT_ID=%s\nBYTE_COUNT=%s\nSHA256=%s\nLIMITED_NON_TRANSACTIONAL=%s\n' \
        "$type" "$engine" "$tool" "$mode" "$artifact_id" "$(stat -c '%s' -- "$artifact")" "$(sha256sum -- "$artifact" | awk '{print $1}')" "$limited"
}
database_stage_artifact() {
    [ "$#" -eq 3 ] || reject database-stage-arguments
    local id="$1" size="$2" digest="$3" target tmp
    require_artifact_id "$id"
    require_artifact_size "$size"
    require_digest "$digest"
    initialise_controlled_roots
    target="$(database_artifact_path "$id")"
    tmp="$(mktemp "$backups_root/.database-stage.XXXXXX")"
    trap 'rm -f -- "$tmp"' EXIT
    head -c "$((size + 1))" >"$tmp"
    [ "$(stat -c '%s' -- "$tmp")" = "$size" ] || reject database-artifact-size
    [ "$(sha256sum -- "$tmp" | awk '{print $1}')" = "$digest" ] || reject database-artifact-digest
    if [ -e "$target" ] || [ -L "$target" ]; then
        assert_root_owned_regular "$target"
        [ "$(sha256sum -- "$target" | awk '{print $1}')" = "$digest" ] || reject database-artifact-collision
    else install -o root -g root -m 400 -- "$tmp" "$target"; fi
    rm -f -- "$tmp"
    trap - EXIT
    printf 'STAGED=1\n'
}
database_read_artifact() {
    [ "$#" -eq 3 ] || reject database-read-arguments
    local id="$1" size="$2" digest="$3" target
    require_artifact_id "$id"
    require_artifact_size "$size"
    require_digest "$digest"
    target="$(database_artifact_path "$id")"
    assert_root_owned_regular "$target"
    [ "$(stat -c '%s' -- "$target")" = "$size" ] || reject database-artifact-size
    [ "$(sha256sum -- "$target" | awk '{print $1}')" = "$digest" ] || reject database-artifact-digest
    cat -- "$target"
}
database_discard_artifact() {
    [ "$#" -eq 2 ] || reject database-discard-arguments
    local id="$1" digest="$2" target
    require_artifact_id "$id"
    require_digest "$digest"
    target="$(database_artifact_path "$id")"
    if [ -e "$target" ] || [ -L "$target" ]; then
        assert_root_owned_regular "$target"
        [ "$(sha256sum -- "$target" | awk '{print $1}')" = "$digest" ] || reject database-artifact-digest
        rm -f -- "$target"
    fi
    printf 'DISCARDED=1\n'
}
database_restore_candidate() {
    [ "$#" -ge 6 ] || reject database-restore-arguments
    local app="$1" credential_app="$2" candidate="$3" artifact_id="$4" type="$5" artifact candidate_root_path token
    shift 5
    require_app "$app"
    require_app "$credential_app"
    require_candidate "$app" "$candidate"
    require_artifact_id "$artifact_id"
    require_database_type "$type"
    artifact="$(database_artifact_path "$artifact_id")"
    assert_root_owned_regular "$artifact"
    candidate_root_path="$(candidate_root "$candidate")"
    assert_candidate_for_deployer "$candidate_root_path"
    install -d -o root -g root -m 700 -- "$candidate_root_path/database"
    if [ "$type" = sqlite ]; then
        database_sqlite_arguments "$credential_app" "$@"
        local target="$candidate_root_path/database/$database_sqlite_binding/$database_sqlite_file"
        [ ! -e "$target" ] && [ ! -L "$target" ] || reject database-candidate-exists
        install -d -o root -g root -m 700 -- "${target%/*}"
        install -o root -g root -m 600 -- "$artifact" "$target"
        if [ "$(sqlite3 -- "$target" 'PRAGMA integrity_check;' 2>/dev/null)" != ok ]; then
            rm -f -- "$target"
            reject database-restore-integrity
        fi
        printf '%s\n' "$credential_app" "$database_sqlite_binding" "$database_sqlite_location_type" "$database_sqlite_location_path" "$database_sqlite_file" >"$candidate_root_path/database/sqlite-binding"
        chmod 400 -- "$candidate_root_path/database/sqlite-binding"
        token="$candidate"
    else
        database_server_arguments "$type" "$@"
        local suffix="${candidate##*-}" candidate_database="w2l_${suffix}" defaults client pgpass
        if [ "$type" = postgresql ]; then
            pgpass="$(database_pgpass "$credential_app" "$database_host" "$database_port" '*' "$database_username" "$database_secret_identifier" "$database_secret_revision")"
            if ! PGPASSFILE="$pgpass" createdb --no-password --host="$database_host" --port="$database_port" --username="$database_username" --owner="$database_username" "$candidate_database"; then
                rm -f -- "$pgpass"
                reject database-candidate-create-failed
            fi
            if ! PGPASSFILE="$pgpass" pg_restore --no-password --exit-on-error --host="$database_host" --port="$database_port" --username="$database_username" --dbname="$candidate_database" "$artifact"; then
                PGPASSFILE="$pgpass" dropdb --if-exists --force --no-password --host="$database_host" --port="$database_port" --username="$database_username" "$candidate_database" >/dev/null 2>&1 || true
                rm -f -- "$pgpass"
                reject database-restore-failed
            fi
            if [ "$(PGPASSFILE="$pgpass" psql --no-password --host="$database_host" --port="$database_port" --username="$database_username" --dbname="$candidate_database" -Atc 'SELECT 1' 2>/dev/null)" != 1 ]; then
                PGPASSFILE="$pgpass" dropdb --if-exists --force --no-password --host="$database_host" --port="$database_port" --username="$database_username" "$candidate_database" >/dev/null 2>&1 || true
                rm -f -- "$pgpass"
                reject database-restore-schema
            fi
            rm -f -- "$pgpass"
        else
            if [ "$type" = mariadb ]; then client="$(command -v mariadb)"; else client="$(command -v mysql)"; fi
            defaults="$(database_mysql_defaults "$credential_app" "$type" "$database_secret_identifier" "$database_secret_revision" "$database_tls")"
            if ! run_mysql_client "$client" "$defaults" --host="$database_host" --port="$database_port" --user="$database_username" -e "CREATE DATABASE \`$candidate_database\`"; then
                rm -f -- "$defaults"
                reject database-candidate-create-failed
            fi
            if ! run_mysql_client "$client" "$defaults" --host="$database_host" --port="$database_port" --user="$database_username" "$candidate_database" <"$artifact"; then
                run_mysql_client "$client" "$defaults" --host="$database_host" --port="$database_port" --user="$database_username" -e "DROP DATABASE IF EXISTS \`$candidate_database\`" >/dev/null 2>&1 || true
                rm -f -- "$defaults"
                reject database-restore-failed
            fi
            if ! run_mysql_client "$client" "$defaults" --host="$database_host" --port="$database_port" --user="$database_username" --database="$candidate_database" -e 'SHOW TABLES' >/dev/null; then
                run_mysql_client "$client" "$defaults" --host="$database_host" --port="$database_port" --user="$database_username" -e "DROP DATABASE IF EXISTS \`$candidate_database\`" >/dev/null 2>&1 || true
                rm -f -- "$defaults"
                reject database-restore-schema
            fi
            rm -f -- "$defaults"
        fi
        token="$candidate"
    fi
    printf 'CANDIDATE_ID=%s\nCONNECTION_TOKEN=%s\nINTEGRITY_VERIFIED=1\nSCHEMA_READABLE=1\n' "$candidate" "$token"
}
database_discard_candidate() {
    [ "$#" -ge 5 ] || reject database-candidate-discard-arguments
    local app="$1" credential_app="$2" candidate="$3" type="$4" candidate_root_path
    shift 4
    require_app "$app"
    require_app "$credential_app"
    require_candidate "$app" "$candidate"
    require_database_type "$type"
    candidate_root_path="$(candidate_root "$candidate")"
    assert_candidate_for_deployer "$candidate_root_path"
    if [ "$type" = sqlite ]; then
        database_sqlite_arguments "$credential_app" "$@"
        local target="$candidate_root_path/database/$database_sqlite_binding/$database_sqlite_file"
        if [ -e "$target" ] || [ -L "$target" ]; then
            assert_sqlite_file "$target"
            rm -f -- "$target" "$target-wal" "$target-shm" "$target-journal"
        fi
        rm -f -- "$candidate_root_path/database/sqlite-binding" "$candidate_root_path/database/sqlite-owner"
        rmdir -- "${target%/*}" 2>/dev/null || true
    else
        database_server_arguments "$type" "$@"
        local suffix="${candidate##*-}" candidate_database="w2l_${suffix}" credentials client
        if [ "$type" = postgresql ]; then
            credentials="$(database_pgpass "$credential_app" "$database_host" "$database_port" '*' "$database_username" "$database_secret_identifier" "$database_secret_revision")"
            if ! PGPASSFILE="$credentials" dropdb --if-exists --force --no-password --host="$database_host" --port="$database_port" --username="$database_username" "$candidate_database"; then
                rm -f -- "$credentials"
                reject database-candidate-discard-failed
            fi
        else
            if [ "$type" = mariadb ]; then client="$(command -v mariadb)"; else client="$(command -v mysql)"; fi
            credentials="$(database_mysql_defaults "$credential_app" "$type" "$database_secret_identifier" "$database_secret_revision" "$database_tls")"
            if ! run_mysql_client "$client" "$credentials" --host="$database_host" --port="$database_port" --user="$database_username" -e "DROP DATABASE IF EXISTS \`$candidate_database\`"; then
                rm -f -- "$credentials"
                reject database-candidate-discard-failed
            fi
        fi
        rm -f -- "$credentials"
    fi
    printf 'DISCARDED=1\n'
}
