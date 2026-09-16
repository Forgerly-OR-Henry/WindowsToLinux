def sql_query(item, sql, password, database=None, username=None, admin=True):
    if item['engine'] == 'POSTGRESQL':
        database = database or 'postgres'
        args = ['psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-p',item['port'],'-d',database]
        if admin and not password: args = ['runuser','-u','postgres','--']+args
        elif admin: args += ['-h','127.0.0.1','-U','postgres']
        else: args += ['-h','127.0.0.1','-U',safe_name(username)]
        result = run(args, data=sql, env={'PGPASSWORD':password,'PGCONNECT_TIMEOUT':'15'}, checked=False)
    else:
        client = 'mariadb' if item['engine'] == 'MARIADB' else 'mysql'
        username = 'root' if admin else safe_name(username)
        # Do not expose passwords in process arguments, diagnostics or SQL error messages.
        fd, defaults = tempfile.mkstemp(prefix='.mysql-',dir=state_directory())
        try:
            escaped = password.replace('\\','\\\\').replace('"','\\"')
            with os.fdopen(fd,'w') as stream: stream.write('[client]\npassword="'+escaped+'"\n')
            args = [client,'--defaults-extra-file='+defaults,'--batch','--skip-column-names','--binary-mode=1',
                    '--local-infile=0','--connect-timeout=15','--user='+username]
            if password or not admin: args += ['--protocol=TCP','--host=127.0.0.1','--port='+item['port']]
            else: args += ['--protocol=SOCKET'] + (['--socket='+item['_socket']] if item.get('_socket') else [])
            if database: args += ['--database='+safe_name(database)]
            result = run(args,data=sql,checked=False)
        finally: os.unlink(defaults)
    if result.returncode != 0: raise Failure('AUTH_REQUIRED' if admin else 'INITIALIZATION_FAILED')
    return result.stdout.strip()

def redis_query(item, args, password, username=None):
    command = ['redis-cli','--raw','-h','127.0.0.1','-p',item['port']]
    if username: command += ['--user',safe_name(username)]
    result = run(command+args,env={'REDISCLI_AUTH':password} if password else {},checked=False)
    if result.returncode != 0 or result.stdout.startswith(('NOAUTH','WRONGPASS','ERR','NOPERM')): raise Failure('AUTH_REQUIRED')
    return result.stdout.strip()

def database_facts(item, database, username, admin_password):
    if item['engine'] == 'POSTGRESQL':
        exists = sql_query(item,"SELECT count(*) FROM pg_database WHERE datname='"+database+"';",admin_password) == '1'
        empty = not exists or sql_query(item,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r','p','v','m','S','f');",admin_password,database) == '0'
    elif item['engine'] == 'REDIS':
        exists = bool(redis_query(item,['ACL','GETUSER',username],admin_password))
        cursor = '0'; empty = True; scanned = 0
        while True:
            reply = redis_query(item,['SCAN',cursor,'MATCH',database+':*','COUNT','1000'],admin_password).splitlines()
            if not reply or not reply[0].isdigit(): raise Failure()
            cursor = reply[0]; scanned += 1000
            if len(reply) > 1: empty = False; break
            if cursor == '0': break
            if scanned >= 100000: raise Failure()
        exists = exists or not empty
    else:
        observed_port = sql_query(item,'SELECT @@port;',admin_password)
        if observed_port != item['port']: raise Failure('AUTH_REQUIRED')
        exists = sql_query(item,"SELECT count(*) FROM information_schema.schemata WHERE schema_name='"+database+"';",admin_password) == '1'
        empty = not exists or sql_query(item,"SELECT count(*) FROM information_schema.tables WHERE table_schema='"+database+"';",admin_password) == '0'
    return exists, empty

def inspect_target(request):
    item = select(request); app = safe_app(request['applicationId']); database = safe_name(request['database']); username = safe_name(request['username'])
    if item['running'] != 'true': raise Failure('STATE_CHANGED')
    exists, empty = database_facts(item,database,username,request.get('adminPassword',''))
    record = read_state(target_state_path(item,app,database))
    if record and (record.get('instanceId') != item['id'] or record.get('username') != username or record.get('database') != database): raise Failure('STATE_CHANGED')
    phase = record.get('phase','UNOWNED')
    if phase == 'CREATING': phase = 'STARTED'
    output = {'applicationId':app,'database':database,'username':username,'exists':str(exists).lower(),'empty':str(empty).lower(),
              'ownershipToken':record.get('ownershipToken',''),'initialization':phase,'initializedSourceSha256':record.get('sourceSha256','')}
    wire_instance(output,item,'instance.'); return output

def prepare_target(request):
    install_runtime_launcher()
    target = inspect_target(request); item = select(request); app = target['applicationId']; database = target['database']; username = target['username']
    password = request.get('applicationPassword',''); admin = request.get('adminPassword','')
    if not password or len(password) > 4096 or any(character in password for character in '\r\n\x00'): raise Failure()
    path = target_state_path(item,app,database); record = read_state(path)
    if target['exists'] == 'true':
        # Reuse never resets another account or relabels an existing database as new.
        try:
            if item['engine'] == 'REDIS': redis_query(item,['PING'],password,username)
            else: sql_query(item,'SELECT 1;',password,database,username,False)
        except Failure: raise Failure('AUTH_REQUIRED')
        return target
    if record: raise Failure('MANUAL_RESTORE_REQUIRED')
    if item['engine'] == 'POSTGRESQL':
        if sql_query(item,"SELECT count(*) FROM pg_roles WHERE rolname='"+username+"';",admin) != '0': raise Failure('STATE_CHANGED')
    elif item['engine'] != 'REDIS':
        if sql_query(item,"SELECT count(*) FROM mysql.user WHERE user='"+username+"';",admin) != '0': raise Failure('STATE_CHANGED')
    record = {'phase':'CREATING','instanceId':item['id'],'applicationId':app,'database':database,'username':username,
              'ownershipToken':secrets.token_hex(32),'secretIdentifier':request['secretIdentifier'],'secretRevision':request['secretRevision']}
    write_state(path,record)
    try:
        if item['engine'] == 'POSTGRESQL':
            escaped = password.replace("'","''")
            sql_query(item,'CREATE ROLE "'+username+'" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '+"'"+escaped+"';",admin)
            sql_query(item,'CREATE DATABASE "'+database+'" OWNER "'+username+'" TEMPLATE template0;',admin)
            sql_query(item,'REVOKE ALL ON DATABASE "'+database+'" FROM PUBLIC;',admin)
        elif item['engine'] == 'REDIS':
            rules = ['ACL','SETUSER',username,'reset','on','#'+hashlib.sha256(password.encode()).hexdigest(),
                     '~'+database+':*','&'+database+':*','+@read','+@write','+@transaction','+ping','+select','-@dangerous','-@admin','-@scripting']
            redis_query(item,rules,admin)
            acl_file = redis_query(item,['CONFIG','GET','aclfile'],admin).splitlines()
            redis_query(item,['ACL','SAVE'] if len(acl_file) > 1 and acl_file[1] else ['CONFIG','REWRITE'],admin)
        else:
            escaped = password.replace('\\','\\\\').replace("'","''")
            sql_query(item,'CREATE DATABASE `'+database+'`;',admin)
            for host in ('localhost','127.0.0.1'):
                account = "'"+username+"'@'"+host+"'"
                sql_query(item,'CREATE USER '+account+" IDENTIFIED BY '"+escaped+"';",admin)
                sql_query(item,'GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER ON `'+database+'`.* TO '+account+';',admin)
        exists, empty = database_facts(item,database,username,admin)
        if not exists or not empty: raise Failure('STATE_CHANGED')
        record['phase'] = 'EMPTY'; write_state(path,record)
        return inspect_target(request)
    except Exception:
        record['phase'] = 'FAILED'; write_state(path,record); raise

RUNTIME_LAUNCHER = '''import os, pathlib, re, sys
directory = pathlib.Path(os.environ['CREDENTIALS_DIRECTORY'])
for entry in directory.iterdir():
    match = re.fullmatch(r'WINDOWSTOLINUX_SECRET_DB_[0-9A-F]{12}_ENV_([A-Z][A-Z0-9_]{0,39})_FILE', entry.name)
    if not match: continue
    name = match.group(1)
    if entry.is_symlink() or not entry.is_file() or entry.stat().st_size > 8192: raise SystemExit(64)
    if name.endswith('_PASSWORD_FILE'):
        os.environ[name] = str(entry); continue
    if not name.endswith('_PASSWORD'): raise SystemExit(64)
    value = entry.read_text(encoding='utf-8')
    if any(character in value for character in ('\\x00','\\r','\\n')): raise SystemExit(64)
    os.environ[name] = value
if len(sys.argv) < 2 or not sys.argv[1].startswith('/'): raise SystemExit(64)
os.execv(sys.argv[1], sys.argv[1:])
'''

def install_runtime_launcher():
    target = pathlib.Path('/usr/local/lib/windowstolinux/runtime-db-env')
    if target.exists():
        info = target.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022: raise Failure('STATE_CHANGED')
        if target.read_text() == RUNTIME_LAUNCHER: return
        raise Failure('STATE_CHANGED')
    descriptor = os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o555)
    with os.fdopen(descriptor,'w') as stream: stream.write(RUNTIME_LAUNCHER)

def restricted_sql(sql):
    if not sql.strip() or len(sql.encode()) > 2097152 or '\x00' in sql: raise Failure('INITIALIZATION_FAILED')
    # Client meta-commands, privileged statements and executable SQL routines are outside this initializer.
    if re.search(r'^\s*\\',sql,re.M): raise Failure('INITIALIZATION_FAILED')
    stripped = re.sub(r"'(?:''|[^'])*'", "''", sql)
    if '\\' in stripped or '/*!' in stripped: raise Failure('INITIALIZATION_FAILED')
    stripped = re.sub(r'/\*.*?\*/|--[^\n]*|#[^\n]*',' ',stripped,flags=re.S)
    if re.search(r'\b(?:COPY|LOAD|OUTFILE|DUMPFILE|GRANT|REVOKE|USE|CALL|DO|EXECUTE|PREPARE|FUNCTION|PROCEDURE|EXTENSION|EVENT|TRIGGER|TABLESPACE|GLOBAL|PROGRAM|FOREIGN|SERVER)\b',stripped,re.I): raise Failure('INITIALIZATION_FAILED')
    for statement in stripped.split(';'):
        if statement.strip() and not re.match(r'^\s*(?:CREATE\s+(?:TABLE|(?:UNIQUE\s+)?INDEX|VIEW|SEQUENCE)|ALTER\s+TABLE|DROP\s+(?:TABLE|INDEX|VIEW|SEQUENCE)|INSERT\s+INTO|UPDATE\s|DELETE\s+FROM|COMMENT\s+ON)\b',statement,re.I): raise Failure('INITIALIZATION_FAILED')
    return sql

def initialize(request):
    item = select(request); app = safe_app(request['applicationId']); database = safe_name(request['database']); username = safe_name(request['username'])
    path = target_state_path(item,app,database); record = read_state(path); sql = restricted_sql(request.get('sql',''))
    digest = hashlib.sha256(sql.encode()).hexdigest(); source = request.get('sourceSha256','')
    if not re.fullmatch(r'[0-9a-f]{64}',source): raise Failure()
    if item['engine'] == 'REDIS': raise Failure('INITIALIZATION_FAILED')
    if record.get('phase') in ('STARTED','FAILED','CREATING'): raise Failure('MANUAL_RESTORE_REQUIRED')
    if record.get('phase') == 'COMPLETE' and record.get('sqlSha256') == digest:
        sql_query(item,'SELECT 1;',request.get('applicationPassword',''),database,username,False)
        output = {'applicationId':app,'database':database,'username':username,'exists':'true','empty':'false',
                  'ownershipToken':record.get('ownershipToken',''),'initialization':'COMPLETE','initializedSourceSha256':record.get('sourceSha256','')}
        wire_instance(output,item,'instance.'); return output
    approved_existing = request.get('existingApproved') == 'true'
    if not approved_existing and (not record or record.get('ownershipToken') != request.get('ownershipToken') or record.get('phase') != 'EMPTY'):
        raise Failure('STATE_CHANGED')
    password = request.get('applicationPassword','')
    if item['engine'] == 'POSTGRESQL':
        privileges = sql_query(item,"SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication FROM pg_roles WHERE rolname=current_user;",password,database,username,False)
        count = sql_query(item,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r','p','v','m','S','f');",password,database,username,False)
        if privileges != 'f' or not approved_existing and count != '0': raise Failure('STATE_CHANGED')
        sql = 'BEGIN;\n'+sql+'\nCOMMIT;'
    else:
        grants = sql_query(item,'SHOW GRANTS;',password,database,username,False)
        for grant in grants.splitlines():
            if not re.search(r'\bGRANT USAGE ON \*\.\*',grant,re.I) and (' ON `'+database+'`.* TO ') not in grant:
                raise Failure('STATE_CHANGED')
        count = sql_query(item,"SELECT count(*) FROM information_schema.tables WHERE table_schema='"+database+"';",password,database,username,False)
        if not approved_existing and count != '0': raise Failure('STATE_CHANGED')
    record.update({'instanceId':item['id'],'applicationId':app,'database':database,'username':username})
    record.update({'phase':'STARTED','sourceSha256':source,'sqlSha256':digest,'existingSchemaChangeApproved':approved_existing})
    write_state(path,record)
    try:
        sql_query(item,sql,password,database,username,False)
        record['phase'] = 'COMPLETE'; write_state(path,record)
    except Exception:
        record['phase'] = 'FAILED'; write_state(path,record); raise Failure('INITIALIZATION_FAILED')
    # The post-initialization result uses the restricted application's verified connection.
    output = {'applicationId':app,'database':database,'username':username,'exists':'true','empty':'false',
              'ownershipToken':record.get('ownershipToken',''),'initialization':'COMPLETE','initializedSourceSha256':source}
    wire_instance(output,item,'instance.'); return output

def resume(request):
    item = select(request); path = replacement_path(item['engine']); record = read_state(path)
    if record.get('phase') != 'WAITING_FOR_RESTORE' or item['running'] != 'true': raise Failure('MANUAL_RESTORE_REQUIRED')
    required = record['target']['engineVersion'].split('.')
    if item['version'].split('.')[:len(required)] != required: raise Failure('STATE_CHANGED')
    if item['engine'] == 'REDIS': redis_query(item,['PING'],request.get('adminPassword',''))
    else: sql_query(item,'SELECT 1;',request.get('adminPassword',''))
    record['phase'] = 'USER_RESTORE_CONFIRMED'; record['verifiedInstance'] = item; write_state(path,record)
    return item

def dispatch(operation, request):
    if request.get('engine') not in PACKAGES['apt']: raise Failure()
    if operation == 'inspect': return wire_inventory(inspect(request['engine']))
    if operation == 'install': return wire_inventory(install(request))
    if operation in ('start','resume'):
        output = {}; wire_instance(output,start(request) if operation == 'start' else resume(request),'instance.'); return output
    if operation == 'target': return inspect_target(request)
    if operation == 'prepare': return prepare_target(request)
    if operation == 'initialize': return initialize(request)
    raise Failure()

def main():
    output = {}
    try:
        with os.fdopen(3,'rb',closefd=False) as stream: payload = stream.read(3 * 1024 * 1024 + 1)
        if len(payload) > 3 * 1024 * 1024: raise Failure()
        request = {}
        for line in payload.decode('ascii').splitlines():
            key, value = line.split('=',1)
            if key in request or not re.fullmatch(r'[a-zA-Z0-9.]{1,64}',key): raise Failure()
            request[key] = base64.b64decode(value,validate=True).decode('utf-8')
        directory = state_directory()
        with open(directory/'operation.lock','a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
            output = dispatch(sys.argv[1],request)
        output['status'] = 'OK'
    except Failure as failure: output = {'status':failure.code}
    except Exception: output = {'status':'ACTION_FAILED'}
    for key,value in output.items(): print(key+'='+base64.b64encode(str(value).encode()).decode('ascii'))

if __name__ == '__main__': main()
WTL_NATIVE_DB_PY
}
