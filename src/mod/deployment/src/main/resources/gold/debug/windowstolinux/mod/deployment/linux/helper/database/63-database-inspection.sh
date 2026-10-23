database_password_path() {
    local app="$1" identifier="$2" revision="$3" path
    require_app "$app"
    require_secret_identifier "$identifier"
    require_revision "$revision"
    path="$(secret_revision_path "$app" "$identifier" "$revision")"
    assert_root_owned_regular "$path"
    [ -s "$path" ] || reject database-password-empty
    printf '%s' "$path"
}
database_pgpass() {
    local app="$1" host="$2" port="$3" database="$4" username="$5" identifier="$6" revision="$7"
    local password_path password escaped target slash
    password_path="$(database_password_path "$app" "$identifier" "$revision")"
    password="$(cat -- "$password_path")"
    case "$password" in *$'\n'*) reject database-password-newline ;; esac
    slash="$(printf '\134')"
    escaped="${password//${slash}/${slash}${slash}}"
    escaped="${escaped//:/${slash}:}"
    target="$(mktemp "$backups_root/.pgpass.XXXXXX")"
    printf '%s:%s:%s:%s:%s\n' "$host" "$port" "$database" "$username" "$escaped" >"$target"
    chmod 600 -- "$target"
    printf '%s' "$target"
}
database_mysql_defaults() {
    local app="$1" type="$2" identifier="$3" revision="$4" tls="$5" password_path password escaped target slash quote
    password_path="$(database_password_path "$app" "$identifier" "$revision")"
    password="$(cat -- "$password_path")"
    case "$password" in *$'\n'*) reject database-password-newline ;; esac
    slash="$(printf '\134')"
    quote='"'
    escaped="${password//${slash}/${slash}${slash}}"
    escaped="${escaped//${quote}/${slash}${quote}}"
    target="$(mktemp "$backups_root/.mysql.XXXXXX")"
    printf '[client]\npassword="%s"\n' "$escaped" >"$target"
    if [ "$tls" = 1 ]; then
        if [ "$type" = mariadb ]; then printf 'ssl=1\n' >>"$target"; else printf 'ssl-mode=REQUIRED\n' >>"$target"; fi
    fi
    chmod 600 -- "$target"
    printf '%s' "$target"
}
database_server_arguments() {
    [ "$#" -eq 8 ] || reject database-server-arguments
    database_type="$1"
    database_host="$2"
    database_port="$3"
    database_name="$4"
    database_username="$5"
    database_secret_identifier="$6"
    database_secret_revision="$7"
    database_tls="$8"
    require_database_type "$database_type"
    [ "$database_type" != sqlite ] || reject database-server-type
    require_database_host "$database_host"
    require_service_port "$database_port"
    require_database_name "$database_name"
    require_database_name "$database_username"
    require_secret_identifier "$database_secret_identifier"
    require_revision "$database_secret_revision"
    require_boolean "$database_tls"
}
database_inspect() {
    [ "$#" -ge 3 ] || reject database-inspect-arguments
    local app="$1" type="$2" engine=unavailable tool=unavailable available=0 compatible=0 online=0 transactional=0
    shift 2
    require_app "$app"
    require_database_type "$type"
    if [ "$type" = sqlite ]; then
        [ "$#" -eq 4 ] || reject database-sqlite-arguments
        database_sqlite_arguments "$app" "$@"
        local source=
        if [ -L "$(app_root "$app")/current" ]; then
            source="$(database_source_path "$app" "$@")"
        elif [ -e "$database_sqlite_target" ] || [ -L "$database_sqlite_target" ]; then reject database-unowned-target; fi
        if command -v sqlite3 >/dev/null 2>&1; then
            tool="$(sqlite3 --version | awk '{print $1}')"
            engine="$tool"
            available=1
            compatible=1
            online=1
            transactional=1
            if [ -n "$source" ]; then [ "$(sqlite3 -readonly -- "$source" 'PRAGMA integrity_check;' 2>/dev/null)" = ok ] || reject database-sqlite-integrity; fi
        fi
    else
        database_server_arguments "$type" "$@"
        if [ "$type" = postgresql ]; then
            if command -v psql >/dev/null 2>&1 && command -v pg_dump >/dev/null 2>&1; then
                local pgpass server_major tool_major
                pgpass="$(database_pgpass "$app" "$database_host" "$database_port" "$database_name" "$database_username" "$database_secret_identifier" "$database_secret_revision")"
                if ! engine="$(PGPASSFILE="$pgpass" psql --no-password --host="$database_host" --port="$database_port" --username="$database_username" --dbname="$database_name" -Atc 'SHOW server_version' 2>/dev/null)"; then
                    rm -f -- "$pgpass"
                    reject database-inspect-failed
                fi
                tool="$(pg_dump --version | awk '{print $NF}')"
                available=1
                transactional=1
                server_major="${engine%%.*}"
                tool_major="${tool%%.*}"
                [[ "$server_major" =~ ^[0-9]+$ && "$tool_major" =~ ^[0-9]+$ ]] || reject database-version
                if [ "$tool_major" -ge "$server_major" ]; then compatible=1; fi
                rm -f -- "$pgpass"
            fi
        else
            local client dump defaults non_transactional server_major tool_major
            if [ "$type" = mariadb ]; then
                client="$(command -v mariadb || true)"
                dump="$(command -v mariadb-dump || true)"
            else
                client="$(command -v mysql || true)"
                dump="$(command -v mysqldump || true)"
            fi
            if [ -n "$client" ] && [ -n "$dump" ]; then
                defaults="$(database_mysql_defaults "$app" "$type" "$database_secret_identifier" "$database_secret_revision" "$database_tls")"
                if ! engine="$(run_mysql_client "$client" "$defaults" --host="$database_host" --port="$database_port" --user="$database_username" --batch --skip-column-names -e 'SELECT VERSION()' 2>/dev/null)"; then
                    rm -f -- "$defaults"
                    reject database-inspect-failed
                fi
                tool="$($dump --version | awk '{print $5}' | tr -d ',')"
                available=1
                server_major="${engine%%.*}"
                tool_major="${tool%%.*}"
                if [[ "$server_major" =~ ^[0-9]+$ && "$tool_major" =~ ^[0-9]+$ ]] && [ "$server_major" = "$tool_major" ]; then compatible=1; fi
                if ! non_transactional="$(run_mysql_client "$client" "$defaults" --host="$database_host" --port="$database_port" --user="$database_username" --batch --skip-column-names --database="$database_name" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND engine IS NOT NULL AND engine NOT IN ('InnoDB','NDB')" 2>/dev/null)"; then
                    rm -f -- "$defaults"
                    reject database-engine-inspect-failed
                fi
                [ "$non_transactional" = 0 ] && transactional=1
                rm -f -- "$defaults"
                trap - RETURN
            fi
        fi
    fi
    printf 'TYPE=%s\nENGINE_VERSION=%s\nTOOL_VERSION=%s\nTOOL_AVAILABLE=%s\nENGINE_COMPATIBLE=%s\nONLINE_BACKUP_AVAILABLE=%s\nALL_TABLES_TRANSACTIONAL=%s\n' \
        "$type" "$engine" "$tool" "$available" "$compatible" "$online" "$transactional"
}
