wrap_database_runtime() {
  for name in "${deployment_secret_names[@]}"; do
    if [[ "$name" =~ ^WINDOWSTOLINUX_SECRET_DB_[0-9A-F]{12}_ENV_[A-Z0-9_]+_FILE$ ]]; then
      command="/usr/bin/python3 /usr/local/lib/windowstolinux/runtime-db-env $command"
      break
    fi
  done
}

native_database() {
  [ "$#" -eq 1 ] || reject native-db-arguments
  case "$1" in inspect|install|start|resume|target|prepare|initialize) ;; *) reject native-db-operation ;; esac
  cd /
  /usr/bin/python3 -I - "$1" 3<&0 <<'WTL_NATIVE_DB_PY'
import base64, contextlib, fcntl, hashlib, json, os, pathlib, re, secrets, shlex, shutil, stat, subprocess, sys, tempfile

class Failure(Exception):
    def __init__(self, code="ACTION_FAILED"): self.code = code

def run(args, *, data=None, env=None, checked=True, timeout=60):
    try:
        result = subprocess.run(args, input=data, text=True, capture_output=True, timeout=timeout,
                                env=dict(os.environ, **(env or {})))
    except (OSError, subprocess.TimeoutExpired): raise Failure()
    if checked and result.returncode != 0: raise Failure()
    if len(result.stdout) > 1048576: raise Failure()
    return result

def safe_name(value):
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value): raise Failure()
    return value

def safe_app(value):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", value): raise Failure()
    return value

def version(value):
    found = re.search(r"(?:^|[^0-9])([0-9]{1,3}(?:\.[0-9]{1,3}){0,2})", value.split(":")[-1])
    if not found: raise Failure("VERSION_UNSUPPORTED")
    return found.group(1)

def system():
    values = {}
    for line in pathlib.Path('/etc/os-release').read_text().splitlines():
        if '=' in line:
            key, value = line.split('=', 1); values[key] = value.strip('"')
    distro, release = values.get('ID'), values.get('VERSION_ID')
    supported = {'ubuntu': {'22.04','24.04'}, 'debian': {'13'}, 'centos': {'9','10'},
                 'rocky': {'9.8','10.2'}, 'almalinux': {'9.8','10.2'}, 'ol': {'9.7','10.2'}}
    if release not in supported.get(distro, set()): raise Failure("VERSION_UNSUPPORTED")
    return 'apt' if distro in ('ubuntu','debian') else 'dnf'

PACKAGES = {'apt': {'POSTGRESQL':'postgresql', 'MYSQL':'mysql-server', 'MARIADB':'mariadb-server', 'REDIS':'redis-server'},
            'dnf': {'POSTGRESQL':'postgresql-server', 'MYSQL':'mysql-server', 'MARIADB':'mariadb-server', 'REDIS':'redis'}}
BINARY = {'POSTGRESQL':'postgres', 'MYSQL':'mysqld', 'MARIADB':'mariadbd', 'REDIS':'redis-server'}
UNITS = {'POSTGRESQL':['postgresql.service'], 'MYSQL':['mysql.service','mysqld.service'],
         'MARIADB':['mariadb.service'], 'REDIS':['redis-server.service','redis.service']}

def packages(manager):
    if manager == 'apt':
        output = run(['dpkg-query','-W','-f=${binary:Package}\t${Version}\t${db:Status-Status}\n'], timeout=30).stdout
        return {row[0].split(':')[0]: row[1] for line in output.splitlines()
                if len(row := line.split('\t')) == 3 and row[2] == 'installed'}
    output = run(['rpm','-qa','--qf','%{NAME}\t%{VERSION}-%{RELEASE}\n']).stdout
    return dict(line.split('\t',1) for line in output.splitlines() if '\t' in line)

def package_candidate(manager, engine):
    name = PACKAGES[manager][engine]
    if manager == 'apt':
        result = run(['apt-cache','policy',name], checked=False).stdout
        match = re.search(r'^\s*Candidate:\s*(\S+)', result, re.M)
        raw = match.group(1) if match else ''
        if not raw or raw == '(none)': return None
    else:
        result = run(['dnf','-q','repoquery','--latest-limit=1','--qf','%{version}-%{release}',name], checked=False, timeout=120)
        candidates = [line for line in result.stdout.splitlines() if re.fullmatch(r'[A-Za-z0-9.+_:~%-]+',line)]
        if result.returncode != 0 or len(candidates) != 1: return None
        raw = candidates[0]
    try: return {'package':name, 'packageVersion':raw, 'engineVersion':version(raw)}
    except Failure: return None

def unit_properties(name):
    result = run(['systemctl','show',name,'--property=Id,LoadState,ActiveState,MainPID,ExecStart,FragmentPath'], checked=False)
    return dict(line.split('=',1) for line in result.stdout.splitlines() if '=' in line)

def instance(engine, identifier, release, port, service, data, package, config=''):
    if not 1 <= int(port) <= 65535 or not re.fullmatch(r'[A-Za-z0-9_.@-]+\.service',service): raise Failure()
    path = pathlib.Path(data)
    if not path.is_absolute() or path.is_symlink(): raise Failure("STATE_CHANGED")
    identity = str(path.stat().st_ino) if path.exists() else 'absent'
    fingerprint = hashlib.sha256('\0'.join([engine, identifier, release, str(port), service, data, package, config, identity]).encode()).hexdigest()
    return {'engine':engine, 'id':identifier, 'version':release, 'port':str(port), 'service':service,
            'dataDirectory':data, 'fingerprint':fingerprint, 'running':str(unit_properties(service).get('ActiveState') == 'active').lower(),
            '_package':package, '_config':config}

def inspect(engine, include_candidate=True):
    manager = system(); installed = packages(manager); entries = []; conflicts = []
    names = [name for name in installed if name == PACKAGES[manager][engine]
             or engine == 'POSTGRESQL' and re.fullmatch(r'postgresql-[0-9]+',name)]
    # Server binaries with unrecognized packaging are evidence of an installation, never an absent DB.
    binary = shutil.which(BINARY[engine])
    if binary and not names: conflicts.append('unknown_source')
    if engine == 'MYSQL' and ('mariadb-server' in installed or shutil.which('mariadbd')):
        conflicts.append('mariadb_installed')
    if engine == 'MARIADB' and ('mysql-server' in installed or 'mysql-community-server' in installed):
        conflicts.append('mysql_installed')
    if engine == 'POSTGRESQL' and manager == 'apt' and shutil.which('pg_lsclusters'):
        for line in run(['pg_lsclusters','--no-header']).stdout.splitlines():
            columns = line.split()
            if len(columns) != 7: conflicts.append('postgresql_path'); continue
            release, name, port, state, owner, data, log = columns
            if not re.fullmatch(r'[A-Za-z0-9_-]+',name) or 'postgresql-'+release not in installed:
                conflicts.append('postgresql_source'); continue
            config = '/etc/postgresql/'+release+'/'+name+'/postgresql.conf'
            try: observed_version = version(installed['postgresql-'+release])
            except Failure: conflicts.append('version_unknown'); continue
            entries.append(instance(engine, release+'/'+name, observed_version, port,
                                    'postgresql@'+release+'-'+name+'.service', data, 'postgresql-'+release, config))
    elif names:
        seen = set()
        listed = run(['systemctl','list-unit-files','--no-legend','--no-pager'], checked=False).stdout
        listed += '\n'+run(['systemctl','list-units','--all','--no-legend','--no-pager','--plain'], checked=False).stdout
        family = {'POSTGRESQL':r'postgresql(?:@[^ ]+)?\.service', 'MYSQL':r'(?:mysql|mysqld)(?:@[^ ]+)?\.service',
                  'MARIADB':r'mariadb(?:@[^ ]+)?\.service', 'REDIS':r'redis(?:-server)?(?:@[^ ]+)?\.service'}[engine]
        units = set(UNITS[engine]) | {line.split()[0] for line in listed.splitlines() if line.split() and re.fullmatch(family,line.split()[0])}
        for unit in sorted(units):
            properties = unit_properties(unit); unit = properties.get('Id',unit)
            if properties.get('LoadState') != 'loaded' or unit in seen or '@.' in unit: continue
            seen.add(unit)
            package = names[0]
            try: release = version(installed[package])
            except Failure: conflicts.append('version_unknown'); continue
            port = {'POSTGRESQL':5432,'MYSQL':3306,'MARIADB':3306,'REDIS':6379}[engine]
            data = {'POSTGRESQL':'/var/lib/pgsql/data','MYSQL':'/var/lib/mysql','MARIADB':'/var/lib/mysql','REDIS':'/var/lib/redis'}[engine]
            config = ''
            if engine in ('MYSQL','MARIADB'):
                arguments = properties.get('ExecStart','')
                explicit = re.search(r'--defaults-file=(/[A-Za-z0-9_./@-]+)',arguments)
                if '@' in unit and not explicit:
                    conflicts.append('defaults_file|'+unit); continue
                options = ['--defaults-file='+explicit.group(1)] if explicit else []
                defaults = run(['my_print_defaults']+options+['mysqld'], checked=False).stdout if shutil.which('my_print_defaults') else ''
                socket = ''
                for item in defaults.splitlines():
                    if item.startswith('--port='): port = int(item[7:])
                    if item.startswith('--datadir='): data = item[10:]
                    if item.startswith('--socket='): socket = item[9:]
                config = hashlib.sha256(defaults.encode()).hexdigest()
            elif engine == 'REDIS':
                arguments = properties.get('ExecStart','')
                explicit = re.search(r'\s(/[A-Za-z0-9_./@-]+\.conf)(?:\s|;|$)',arguments)
                paths = [explicit.group(1)] if explicit else ['/etc/redis/redis.conf','/etc/redis.conf']
                if '@' in unit and not explicit:
                    conflicts.append('redis_instance|'+unit); continue
                paths = [path for path in paths if pathlib.Path(path).is_file()]
                if len(paths) != 1: conflicts.append('redis_config'); continue
                config = paths[0]
                for line in pathlib.Path(config).read_text().splitlines():
                    parts = shlex.split(line, comments=True)
                    if parts[:1] == ['port'] and len(parts) == 2: port = int(parts[1])
                    if parts[:1] == ['dir'] and len(parts) == 2: data = parts[1]
                    if parts[:1] in (['include'], ['cluster-enabled']) and parts[-1] not in ('no','0'):
                        conflicts.append('redis_cluster')
            else:
                if '@' in unit:
                    conflicts.append('postgresql_service|'+unit); continue
                config = data+'/postgresql.conf'
                if pathlib.Path(config).is_file():
                    text = pathlib.Path(config).read_text()
                    match = re.search(r'^\s*port\s*=\s*([0-9]+)',text,re.M)
                    if match: port = int(match.group(1))
            value = instance(engine, unit, release, port, unit, data, package, config)
            if engine in ('MYSQL','MARIADB'): value['_socket'] = socket
            entries.append(value)
    if names and not entries: conflicts.append('instance_unknown')
    if not names:
        data_root = pathlib.Path({'POSTGRESQL':'/var/lib/postgresql' if manager == 'apt' else '/var/lib/pgsql/data',
                                  'MYSQL':'/var/lib/mysql','MARIADB':'/var/lib/mysql','REDIS':'/var/lib/redis'}[engine])
        if data_root.exists() and any(data_root.iterdir()): conflicts.append('data_without_package')
    candidate = package_candidate(manager, engine) if include_candidate else None
    return {'engine':engine, 'instances':entries, 'candidate':candidate, 'conflicts':conflicts}

def select(request):
    inventory = inspect(request['engine'], False)
    if inventory['conflicts']: raise Failure("STATE_CHANGED")
    found = [item for item in inventory['instances'] if item['id'] == request.get('instanceId')]
    if len(found) != 1 or found[0]['fingerprint'] != request.get('fingerprint'): raise Failure("STATE_CHANGED")
    return found[0]

def state_directory():
    directory = pathlib.Path('/var/lib/windowstolinux/db')
    for parent in list(reversed(directory.parents)) + [directory]:
        if parent.is_symlink(): raise Failure("STATE_CHANGED")
        if parent.exists() and (parent.stat().st_uid != 0 or parent.stat().st_mode & 0o022): raise Failure("STATE_CHANGED")
    directory.mkdir(mode=0o700, exist_ok=True)
    return directory

def write_state(path, value):
    if path.is_symlink(): raise Failure("STATE_CHANGED")
    handle, name = tempfile.mkstemp(prefix='.state-', dir=str(path.parent))
    try:
        with os.fdopen(handle,'w') as stream:
            json.dump(value,stream,sort_keys=True); stream.flush(); os.fsync(stream.fileno())
        os.replace(name,path)
    finally:
        if os.path.exists(name): os.unlink(name)

def read_state(path):
    if not path.exists(): return {}
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077: raise Failure("STATE_CHANGED")
    return json.loads(path.read_text())

def target_state_path(item, app, database):
    identity = hashlib.sha256((item['engine']+'\0'+item['id']+'\0'+database).encode()).hexdigest()
    return state_directory()/(safe_app(app)+'-'+identity+'.json')

def replacement_path(engine): return state_directory()/('replacement-'+engine.lower()+'.json')

@contextlib.contextmanager
def prevent_package_start(manager, units):
    policy = pathlib.Path('/usr/sbin/policy-rc.d'); created = False; masked = []
    try:
        if manager == 'apt':
            if policy.exists() or policy.is_symlink(): raise Failure("STATE_CHANGED")
            fd = os.open(policy, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o755)
            with os.fdopen(fd,'w') as stream: stream.write('#!/bin/sh\nexit 101\n')
            created = True
        for unit in units:
            if unit_properties(unit).get('LoadState') == 'masked': continue
            run(['systemctl','mask','--runtime',unit]); masked.append(unit)
        yield
    finally:
        for unit in masked: run(['systemctl','unmask','--runtime',unit],checked=False)
        if created and policy.is_file() and not policy.is_symlink() and policy.read_text() == '#!/bin/sh\nexit 101\n': policy.unlink()

def install(request):
    engine = request['engine']; before = inspect(engine); candidate = before['candidate']; manager = system()
    if before['conflicts'] or not candidate or any(request.get(key) != value for key,value in candidate.items()): raise Failure("STATE_CHANGED")
    replacing = bool(before['instances'])
    if replacing:
        previous = select(request)
        if len(before['instances']) != 1 or request.get('replacementApproved') != 'true': raise Failure("STATE_CHANGED")
        safe_app(request['approvedServer'])
        if not re.fullmatch(r'[0-9a-f-]{36}',request.get('operation','')): raise Failure()
        write_state(replacement_path(engine), {'phase':'WAITING_FOR_RESTORE','previous':previous,
                                             'target':candidate,'operation':request['operation']})
        run(['systemctl','stop',previous['service']])
    units = UNITS[engine] + ([previous['service']] if replacing else [])
    with prevent_package_start(manager, units):
        if replacing:
            # Remove software only. Never purge or delete a database data directory.
            package = previous['_package']
            if manager == 'apt':
                simulation = run(['apt-get','-s','remove',package]).stdout
                removed = re.findall(r'^Remv\s+(\S+)',simulation,re.M)
                if any(name not in (package,'postgresql') for name in removed): raise Failure("STATE_CHANGED")
                run(['apt-get','-y','remove',package],env={'DEBIAN_FRONTEND':'noninteractive'},timeout=1800)
            else:
                # DNF --noautoremove preserves unrelated leaf packages; dependent removals are refused in preview.
                preview = run(['dnf','-q','repoquery','--installed','--whatrequires',package],checked=False).stdout.strip()
                if preview: raise Failure("STATE_CHANGED")
                run(['dnf','-y','--noautoremove','remove',package],timeout=1800)
        if manager == 'apt':
            run(['apt-get','-y','--no-remove','install',candidate['package']+'='+candidate['packageVersion']],
                env={'DEBIAN_FRONTEND':'noninteractive'},timeout=1800)
        else:
            run(['dnf','-y','install',candidate['package']+'-'+candidate['packageVersion']],timeout=1800)
    return inspect(engine)

def start(request):
    item = select(request)
    if read_state(replacement_path(item['engine'])).get('phase') == 'WAITING_FOR_RESTORE': raise Failure("MANUAL_RESTORE_REQUIRED")
    if item['engine'] == 'POSTGRESQL' and system() == 'dnf' and not pathlib.Path(item['dataDirectory']+'/PG_VERSION').exists():
        data = pathlib.Path(item['dataDirectory'])
        if data.exists() and any(data.iterdir()): raise Failure("STATE_CHANGED")
        run(['postgresql-setup','--initdb'],timeout=120)
    run(['systemctl','start',item['service']],timeout=120)
    refreshed = inspect(item['engine'],False)
    values = [value for value in refreshed['instances'] if value['id'] == item['id']]
    if len(values) != 1 or values[0]['running'] != 'true': raise Failure()
    return values[0]

def wire_instance(output, item, prefix):
    for key,value in item.items():
        if not key.startswith('_'): output[prefix+key] = str(value)

def wire_inventory(inventory):
    output = {'engine':inventory['engine'],'instanceCount':str(len(inventory['instances'])), 'conflictCount':str(len(inventory['conflicts']))}
    for index,item in enumerate(inventory['instances']): wire_instance(output,item,'instance.'+str(index)+'.')
    for index,conflict in enumerate(inventory['conflicts']): output['conflict.'+str(index)] = conflict
    if inventory['candidate']: output.update(inventory['candidate'])
    return output
