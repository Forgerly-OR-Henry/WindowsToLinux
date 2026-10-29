"""Explicit reset of the disposable acceptance host; never a deployment fallback."""
import base64
import hashlib
import json
import os
import pathlib
import re
import shutil
import signal
import stat
import subprocess
import sys
import time

ROOTS = (
    '/usr/local/lib/windowstolinux', '/var/lib/windowstolinux',
    '/var/opt/windowstolinux', '/var/cache/windowstolinux',
    '/etc/opt/windowstolinux', '/etc/windowstolinux',
    '/opt/windowstolinux', '/run/windowstolinux',
)
UNIT_DIRS = ('/etc/systemd/system', '/run/systemd/system', '/usr/lib/systemd/system')
SUBID_FILES = ('/etc/subuid', '/etc/subgid')
UNIT_NAME = re.compile(r'(?:windowstolinux|wtl)[A-Za-z0-9_.@:-]*\.(?:service|socket|timer|target|path|scope|mount)\Z')
ACCOUNT_NAME = re.compile(r'wtl[rb]-[0-9a-f]{16}\Z')
PACKAGE_NAME = re.compile(r'[a-z0-9][a-z0-9+.-]*(?::[a-z0-9]+)?\Z')
TARGET_PACKAGES = frozenset('''build-essential pkg-config pkgconf pkgconf-bin
libssl-dev zlib1g-dev libffi-dev libbz2-dev libreadline-dev libsqlite3-dev
liblzma-dev libncurses-dev libncurses5-dev libncursesw5-dev libgdbm-dev
libonig-dev libcurl4-openssl-dev libxml2-dev libpq-dev libzip-dev libyaml-dev
libnode-dev libexpat1-dev libgdbm-compat-dev libltdl-dev libtool libtool-bin
gcc g++ cpp gfortran clang clang-tools lld llvm make cmake cmake-data ninja-build
meson autoconf automake autotools-dev bison flex dpkg-dev binutils
nodejs npm composer php php-cli php-common php-dev php-pear php-mbstring php-xml
php-curl php-zip php-sqlite3 php-pgsql php-mysql ruby ruby-dev ruby-bundler bundler
rubygems rustc cargo rustfmt clippy maven ant gradle default-jdk default-jdk-headless
default-jre default-jre-headless golang golang-go golang-src'''.split())
CORE_PACKAGES = frozenset('''openssh-server openssh-client openssh-sftp-server
python3 python3-minimal apt dpkg systemd systemd-sysv libc6 libc-bin libgcc-s1
libstdc++6 sudo ca-certificates coreutils bash dash util-linux mount findutils
passwd login procps iproute2 iputils-ping netbase netplan.io network-manager
systemd-resolved systemd-networkd cloud-init ubuntu-minimal ubuntu-standard
ubuntu-server ubuntu-server-minimal git git-man
base-files base-passwd debianutils hostname init init-system-helpers udev'''.split())
MAX_JSON = 512 * 1024
MAX_PLAN = 65536
MAX_ENTRIES = 1000000
PHASE = 'arguments'
DEADLINE = None


class ResetError(Exception):
    def __init__(self, code, **facts):
        self.code, self.facts = code, facts


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('utf-8')


def digest(value):
    return hashlib.sha256(value).hexdigest()


def command(arguments, timeout=120, allowed=(0,)):
    available = timeout if DEADLINE is None else min(timeout, int(DEADLINE - time.monotonic()))
    if available < 1:
        raise ResetError('RESET_DEADLINE')
    try:
        process = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True,
                                   env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C',
                                        'DEBIAN_FRONTEND': 'noninteractive', 'NEEDRESTART_MODE': 'l'})
        try:
            stdout, stderr = process.communicate(timeout=available)
        except subprocess.TimeoutExpired:
            # Only this newly created session/group is owned; existing apt lock holders are never touched.
            if os.name == 'posix':
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            else:
                process.terminate()
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                if os.name == 'posix':
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                else:
                    process.kill()
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    raise ResetError('OWNED_COMMAND_CLEANUP_UNCONFIRMED', command=pathlib.Path(arguments[0]).name)
            raise ResetError('COMMAND_TIMEOUT', command=pathlib.Path(arguments[0]).name)
        finally:
            process.stdout.close()
            process.stderr.close()
    except subprocess.TimeoutExpired:
        raise ResetError('COMMAND_TIMEOUT', command=pathlib.Path(arguments[0]).name)
    if process.returncode not in allowed:
        raise ResetError('COMMAND_FAILED', command=pathlib.Path(arguments[0]).name, exitCode=process.returncode)
    if len(stdout) > 2 * 1024 * 1024 or len(stderr) > 2 * 1024 * 1024:
        raise ResetError('COMMAND_OUTPUT_LIMIT', command=pathlib.Path(arguments[0]).name)
    return process.returncode, stdout.decode('utf-8', errors='strict')


def metadata(path):
    value = os.lstat(path)
    kind = ('directory' if stat.S_ISDIR(value.st_mode) else 'file' if stat.S_ISREG(value.st_mode)
            else 'symlink' if stat.S_ISLNK(value.st_mode) else 'special')
    return {'type': kind, 'device': value.st_dev, 'inode': value.st_ino,
            'mode': stat.S_IMODE(value.st_mode), 'uid': value.st_uid, 'gid': value.st_gid,
            'size': value.st_size, 'mtimeNs': value.st_mtime_ns}


def safe_ancestors(path):
    path = pathlib.Path(path)
    if not path.is_absolute() or '..' in path.parts or path == pathlib.Path('/'):
        raise ResetError('UNSAFE_PATH')
    for parent in reversed(path.parents):
        value = metadata(parent)
        if value['type'] != 'directory' or value['uid'] != 0 or value['mode'] & 0o022:
            raise ResetError('UNSAFE_ANCESTOR')


def mount_paths():
    values = set()
    for line in pathlib.Path('/proc/self/mountinfo').read_text().splitlines():
        fields = line.split()
        if len(fields) < 6:
            raise ResetError('MOUNT_METADATA_INVALID')
        values.add(re.sub(r'\\([0-7]{3})', lambda match: chr(int(match[1], 8)), fields[4]))
    return values


def require_no_mount(path, mounts):
    value = str(path)
    if any(item == value or item.startswith(value + '/') for item in mounts):
        raise ResetError('TARGET_CONTAINS_MOUNT', path=value)


def tree_metadata(path, mounts):
    """Hash names and lstat metadata only; never open a business/configuration file."""
    require_no_mount(path, mounts)
    result = hashlib.sha256()
    pending = [(pathlib.Path(path), '')]
    count, size = 0, 0
    while pending:
        current, relative = pending.pop()
        value = metadata(current)
        count += 1
        if count > MAX_ENTRIES or relative.count('/') > 128:
            raise ResetError('TREE_METADATA_LIMIT')
        result.update(canonical([relative, value]))
        if value['type'] == 'directory':
            with os.scandir(current) as entries:
                children = sorted((entry.name for entry in entries), reverse=True)
            for name in children:
                pending.append((current / name, relative + '/' + name))
        elif value['type'] == 'file':
            size += value['size']
        # Interior symlinks are metadata only and are unlinked, never traversed.
    return {'treeSha256': result.hexdigest(), 'entries': count, 'fileBytes': size}


def root_inventory(mounts):
    values = []
    for name in ROOTS:
        path = pathlib.Path(name)
        safe_ancestors(path)
        if not os.path.lexists(path):
            values.append({'path': name, 'exists': False})
            continue
        value = metadata(path)
        if value['type'] != 'directory' or value['uid'] != 0:
            raise ResetError('UNSAFE_TARGET_ROOT', path=name)
        values.append({'path': name, 'exists': True, **value, **tree_metadata(path, mounts)})
    return values


def dependency_unit_binding(unit):
    match = re.fullmatch('windowstolinux-dependency-([a-f0-9]{32})\\.service', unit)
    if match is None:
        raise ResetError('DEPENDENCY_UNIT_IDENTITY')
    base = pathlib.Path('/var/lib/windowstolinux/dependency-operations') / match[1]
    def read(name, limit):
        path = base / name
        safe_ancestors(path)
        info = metadata(path)
        if info['type'] != 'file' or info['uid'] != 0 or info['gid'] != 0 or info['mode'] & 0o022 or info['size'] > limit:
            raise ResetError('DEPENDENCY_OWNERSHIP_FILE')
        data = path.read_bytes()
        if len(data) != info['size']:
            raise ResetError('DEPENDENCY_OWNERSHIP_CHANGED')
        return data
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ResetError('DEPENDENCY_OWNERSHIP_DUPLICATE')
            result[key] = value
        return result
    raw, ready_raw, worker = read('manifest.json', 16384), read('ready.json', 4096), read('worker.py', 1048576)
    manifest, ready = json.loads(raw, object_pairs_hook=unique), json.loads(ready_raw, object_pairs_hook=unique)
    source = digest(worker)
    if (not isinstance(manifest, dict) or not isinstance(ready, dict) or type(manifest.get('formatVersion')) is not int or manifest.get('formatVersion') != 1
        or manifest.get('operation') != match[1] or manifest.get('unit') != unit
        or manifest.get('workerSourceSha256') != source or ready.get('operation') != match[1]
        or ready.get('workerSourceSha256') != source or not re.fullmatch('[a-f0-9]{32}', ready.get('invocationId', ''))
        or ready['invocationId'] == '0' * 32 or not isinstance(manifest.get('task'), str)
        or not re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', manifest['task'])
        or not isinstance(manifest.get('server'), str) or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._:-]{0,127}', manifest['server'])
        or manifest.get('description') != 'WTL_DEPENDENCY_1:' + match[1] + ':' + source):
        raise ResetError('DEPENDENCY_UNIT_IDENTITY')
    _, output = command(['/usr/bin/systemctl', 'show', '--no-pager',
        '--property=LoadState,InvocationID,Description,Transient,ControlGroup', unit])
    facts = {}
    for line in output.splitlines():
        key, sep, value = line.partition('=')
        if not sep or key in facts:
            raise ResetError('DEPENDENCY_UNIT_OBSERVATION')
        facts[key] = value
    if (set(facts) != {'LoadState', 'InvocationID', 'Description', 'Transient', 'ControlGroup'}
        or facts['LoadState'] != 'loaded' or facts['InvocationID'] != ready['invocationId']
        or facts['Description'] != manifest['description'] or facts['Transient'] != 'yes'
        or facts['ControlGroup'] not in ('', '/system.slice/' + unit)):
        raise ResetError('DEPENDENCY_UNIT_IDENTITY')
    return {'operation': match[1], 'task': manifest['task'], 'server': manifest['server'],
        'manifestSha256': digest(raw), 'readySha256': digest(ready_raw), 'workerSourceSha256': source,
        'invocationId': ready['invocationId']}


def unit_inventory(mounts):
    loaded, installed, files, states = set(), set(), {}, {}
    for option, names in (('list-units', loaded), ('list-unit-files', installed)):
        _, output = command(['/usr/bin/systemctl', option, '--all', '--no-legend', '--no-pager', '--plain'])
        for line in output.splitlines():
            fields = line.split()
            if fields and UNIT_NAME.fullmatch(fields[0]):
                names.add(fields[0])
                if option == 'list-units':
                    if len(fields) < 4 or any(not re.fullmatch('[a-z-]{1,32}', value) for value in fields[1:4]):
                        raise ResetError('UNIT_STATE_INVALID')
                    states[fields[0]] = {'loadState': fields[1], 'activeState': fields[2], 'subState': fields[3]}
    for directory in UNIT_DIRS:
        root = pathlib.Path(directory)
        if not root.exists():
            continue
        safe_ancestors(root / 'placeholder')
        with os.scandir(root) as entries:
            entries = sorted(entries, key=lambda entry: entry.name)
        for entry in entries:
            path = pathlib.Path(entry.path)
            if UNIT_NAME.fullmatch(entry.name) or entry.name.endswith('.d') and UNIT_NAME.fullmatch(entry.name[:-2]):
                name = entry.name[:-2] if entry.name.endswith('.d') else entry.name
                installed.add(name)
                value = metadata(path)
                if value['uid'] != 0 or value['type'] not in ('file', 'symlink', 'directory'):
                    raise ResetError('UNSAFE_UNIT_DEFINITION')
                if value['type'] == 'directory':
                    value.update(tree_metadata(path, mounts))
                files[str(path)] = {'path': str(path), **value}
            elif entry.name.endswith(('.wants', '.requires')) and entry.is_dir(follow_symlinks=False):
                with os.scandir(path) as links:
                    for link in links:
                        if not UNIT_NAME.fullmatch(link.name):
                            continue
                        value = metadata(link.path)
                        if value['type'] != 'symlink' or value['uid'] != 0:
                            raise ResetError('UNSAFE_UNIT_ENABLE_LINK')
                        resolved = str(pathlib.Path(link.path).resolve())
                        if not any(resolved == str(pathlib.Path(base) / link.name) for base in UNIT_DIRS):
                            raise ResetError('UNIT_LINK_ESCAPE')
                        installed.add(link.name)
                        files[link.path] = {'path': link.path, **value}
    names = sorted(loaded | installed)
    if len(names) > 2048 or len(files) > 8192:
        raise ResetError('UNIT_INVENTORY_LIMIT')
    for name in names:
        if name.startswith('windowstolinux-dependency-'):
            # Prefix only selects candidates. A foreign/colliding unit is never claimed or killed.
            states.setdefault(name, {})['dependencyOwnership'] = dependency_unit_binding(name)
    return names, sorted(installed), [files[key] for key in sorted(files)], dict(sorted(states.items()))


def target_package(name):
    base = name.split(':')[0]
    return base in TARGET_PACKAGES or bool(re.fullmatch(
        r'(?:gcc|g\+\+|cpp|gfortran|clang|clang-tools|lld|llvm)(?:-[0-9]{1,3})?(?:-[a-z0-9]+(?:-[a-z0-9]+)?-linux-gnu)?'
        r'|(?:libgcc|libstdc\+\+)-[0-9]{1,3}-dev|binutils(?:-[a-z0-9]+(?:-[a-z0-9]+)?-linux-gnu)?'
        r'|php[0-9]+\.[0-9]+(?:-[a-z0-9-]+)?|ruby[0-9]+\.[0-9]+(?:-dev)?'
        r'|openjdk-[0-9]+-(?:jdk|jre)(?:-headless)?|golang-[0-9]+\.[0-9]+(?:-go|-src)?'
        r'|(?:dotnet-sdk|dotnet-runtime|dotnet-hostfxr|dotnet-targeting-pack|dotnet-apphost-pack|aspnetcore-runtime|aspnetcore-targeting-pack)-[0-9]+\.[0-9]+'
        r'|dotnet-host|netstandard-targeting-pack-[0-9]+\.[0-9]+', base))


def core_package(name):
    base = name.split(':')[0]
    return base in CORE_PACKAGES or base.startswith(('openssh-', 'python3', 'libpython3', 'linux-image', 'linux-modules'))


def packages():
    _, output = command(['/usr/bin/dpkg-query', '-W', '-f=${binary:Package}\t${Version}\t${db:Status-Status}\t${Essential}\n'])
    values = []
    for line in output.splitlines():
        fields = line.split('\t')
        if len(fields) != 4 or not PACKAGE_NAME.fullmatch(fields[0]):
            raise ResetError('PACKAGE_METADATA_INVALID')
        if fields[2] == 'installed':
            values.append({'name': fields[0], 'version': fields[1], 'essential': fields[3] == 'yes'})
    if len(values) > 20000:
        raise ResetError('PACKAGE_INVENTORY_LIMIT')
    return sorted(values, key=lambda value: value['name'])


def account_inventory():
    import pwd
    import grp
    accounts, groups = [], []
    for value in pwd.getpwall():
        if not ACCOUNT_NAME.fullmatch(value.pw_name):
            continue
        if value.pw_uid == 0 or value.pw_gid == 0 or value.pw_shell not in ('/usr/sbin/nologin', '/sbin/nologin'):
            raise ResetError('UNSAFE_ENVIRONMENT_ACCOUNT')
        valid_home = value.pw_dir == '/nonexistent' if value.pw_name.startswith('wtlr-') else bool(re.fullmatch(
            r'/var/lib/windowstolinux/work/[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}/mutable/home', value.pw_dir))
        if not valid_home:
            raise ResetError('ENVIRONMENT_ACCOUNT_HOME_DIFFERS')
        accounts.append({'name': value.pw_name, 'uid': value.pw_uid, 'gid': value.pw_gid, 'home': value.pw_dir})
    for value in grp.getgrall():
        if ACCOUNT_NAME.fullmatch(value.gr_name):
            if value.gr_gid == 0 or value.gr_mem:
                raise ResetError('ENVIRONMENT_GROUP_OCCUPIED')
            groups.append({'name': value.gr_name, 'gid': value.gr_gid})
        elif any(member in {item['name'] for item in accounts} for member in value.gr_mem):
            raise ResetError('ENVIRONMENT_ACCOUNT_OTHER_GROUP')
    return sorted(accounts, key=lambda value: value['name']), sorted(groups, key=lambda value: value['name'])


def executable(path):
    return {'path': path, 'realpath': os.path.realpath(path),
            'available': os.path.isfile(path) and os.access(path, os.X_OK)}


def subordinate_inventory():
    values = []
    for name in SUBID_FILES:
        path = pathlib.Path(name)
        if not os.path.lexists(path):
            continue
        safe_ancestors(path)
        observed = metadata(path)
        if observed['type'] != 'file' or observed['uid'] != 0 or observed['size'] > 1024 * 1024:
            raise ResetError('UNSAFE_SUBORDINATE_ID_FILE')
        for line in path.read_text(encoding='utf-8').splitlines():
            fields = line.split(':')
            if not ACCOUNT_NAME.fullmatch(fields[0]):
                continue
            if len(fields) != 3 or not fields[1].isdigit() or not fields[2].isdigit():
                raise ResetError('SUBORDINATE_ID_INVALID')
            start, count = int(fields[1]), int(fields[2])
            if start < 1 or count < 1 or start + count > 4294967296:
                raise ResetError('SUBORDINATE_ID_INVALID')
            values.append({'file': name, 'name': fields[0], 'start': start, 'count': count})
    return sorted(values, key=lambda value: (value['file'], value['name'], value['start']))


def cache_inventory():
    root = pathlib.Path('/var/cache/apt/archives')
    result = {'debFiles': 0, 'partialFiles': 0, 'bytes': 0}
    for directory, key in ((root, 'debFiles'), (root / 'partial', 'partialFiles')):
        if not os.path.lexists(directory):
            continue
        safe_ancestors(directory)
        if metadata(directory)['type'] != 'directory':
            raise ResetError('UNSAFE_APT_CACHE')
        with os.scandir(directory) as entries:
            for entry in entries:
                if key == 'debFiles' and not entry.name.endswith('.deb'):
                    continue
                value = metadata(entry.path)
                if value['type'] not in ('file', 'symlink'):
                    raise ResetError('UNEXPECTED_APT_CACHE_ENTRY')
                result[key] += 1
                result['bytes'] += value['size']
    return result


def inventory():
    global PHASE
    PHASE = 'inventory-host'
    machine = pathlib.Path('/etc/machine-id').read_bytes().strip()
    if not re.fullmatch(b'[0-9a-f]{32}', machine):
        raise ResetError('MACHINE_ID_INVALID')
    mounts = mount_paths()
    PHASE = 'inventory-roots'
    roots = root_inventory(mounts)
    PHASE = 'inventory-units'
    units, installed_units, unit_files, unit_states = unit_inventory(mounts)
    PHASE = 'inventory-packages'
    installed = packages()
    PHASE = 'inventory-accounts'
    accounts, groups = account_inventory()
    core = {'python3': executable('/usr/bin/python3'), 'sshClient': executable('/usr/bin/ssh'),
            'sshDaemon': executable('/usr/sbin/sshd'),
            'requiredPackages': [value for value in installed if core_package(value['name']) or value['essential']],
            'preserved': 'OS Python, SSH client/daemon, server meta-packages, Git utilities, package/service managers, core libraries and networking remain installed.'}
    return {'formatVersion': 1, 'hostSha256': digest(machine), 'roots': roots, 'units': units,
            'installedUnits': installed_units, 'unitFiles': unit_files, 'unitStates': unit_states, 'accounts': accounts, 'groups': groups,
            'installedPackages': installed, 'installedTargets': [value for value in installed if target_package(value['name'])],
            'core': core, 'aptCache': cache_inventory(), 'subordinateIds': subordinate_inventory()}


def parse_simulation(output, installed, requested):
    known = {value['name']: value for value in installed}
    removals = set()
    for line in output.splitlines():
        fields = line.split()
        if not fields:
            continue
        if fields[0] in ('Inst', 'Conf'):
            raise ResetError('APT_UNEXPECTED_INSTALL')
        if fields[0] not in ('Remv', 'Purg'):
            continue
        if len(fields) < 2 or not PACKAGE_NAME.fullmatch(fields[1]):
            raise ResetError('APT_SIMULATION_INVALID')
        name = fields[1]
        matches = [value for key, value in known.items() if key == name or key.split(':')[0] == name]
        if not matches or any(value['essential'] for value in matches) or core_package(name):
            raise ResetError('APT_CORE_REMOVAL_REFUSED', package=name)
        if not target_package(name):
            raise ResetError('APT_UNREVIEWED_REMOVAL', package=name)
        if len(matches) != 1:
            raise ResetError('APT_AMBIGUOUS_ARCHITECTURE')
        removals.add(matches[0]['name'])
    if not set(requested).issubset(removals):
        raise ResetError('APT_REQUEST_NOT_SIMULATED')
    return sorted(removals)


def plan():
    global PHASE
    observed = inventory()
    if not all(observed['core'][key]['available'] for key in ('python3', 'sshClient', 'sshDaemon')):
        raise ResetError('CORE_EXECUTABLE_UNAVAILABLE')
    requested = [value['name'] for value in observed['installedTargets']]
    if any(core_package(value['name']) or value['essential'] for value in observed['installedTargets']):
        raise ResetError('CORE_TARGET_CONFLICT')
    if any(value['name'] not in {account['name'] for account in observed['accounts']} for value in observed['subordinateIds']):
        raise ResetError('SUBORDINATE_ID_OWNER_ABSENT')
    PHASE = 'apt-simulation'
    simulated = []
    if requested:
        _, output = command(['/usr/bin/apt-get', '-s', 'purge', '--'] + requested, timeout=180)
        simulated = parse_simulation(output, observed['installedPackages'], requested)
    result = {key: observed[key] for key in ('formatVersion', 'hostSha256', 'roots', 'units', 'installedUnits',
                                             'unitFiles', 'unitStates', 'accounts', 'groups', 'subordinateIds', 'installedTargets', 'core')}
    result['packageInventorySha256'] = digest(canonical(observed['installedPackages']))
    result['purgePackages'] = simulated
    if len(canonical(result)) > MAX_PLAN:
        raise ResetError('PLAN_LIMIT')
    return result


def decode_plan(encoded):
    if len(encoded) > (MAX_PLAN + 2) // 3 * 4:
        raise ResetError('PLAN_LIMIT')
    try:
        raw = base64.b64decode(encoded, validate=True)
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError('duplicate')
                result[key] = value
            return result
        value = json.loads(raw.decode('utf-8'), object_pairs_hook=unique)
        if not isinstance(value, dict) or len(raw) > MAX_PLAN:
            raise ValueError('object')
        return value
    except (ValueError, UnicodeError):
        raise ResetError('PLAN_ENCODING_INVALID')


def remove_root(record):
    path = pathlib.Path(record['path'])
    if str(path) not in ROOTS or not record['exists']:
        raise ResetError('UNPLANNED_ROOT')
    safe_ancestors(path)
    require_no_mount(path, mount_paths())
    current = metadata(path)
    if any(current[key] != record[key] for key in ('device', 'inode', 'uid', 'type')) or current['type'] != 'directory':
        raise ResetError('TARGET_IDENTITY_CHANGED')
    if not shutil.rmtree.avoids_symlink_attacks:
        raise ResetError('FD_SAFE_DELETE_UNAVAILABLE')
    shutil.rmtree(path)


def apply(selected):
    global PHASE
    PHASE = 'apply-admission'
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise ResetError('ROOT_REQUIRED')
    current = plan()
    if canonical(current) != canonical(selected):
        raise ResetError('PLAN_DRIFT_OR_TAMPERING')
    summary = {'stoppedUnits': 0, 'removedUnitFiles': 0, 'removedRoots': 0,
               'removedAccounts': 0, 'purgedPackages': 0, 'aptDownloadCacheCleaned': False}
    PHASE = 'stop-units'
    for unit in current['units']:
        if unit.startswith('windowstolinux-dependency-') and dependency_unit_binding(unit) != current['unitStates'][unit]['dependencyOwnership']:
            raise ResetError('DEPENDENCY_UNIT_DRIFT')
        command(['/usr/bin/systemctl', 'stop', '--', unit], timeout=180)
        summary['stoppedUnits'] += 1
    PHASE = 'reset-exact-failed-units'
    _, output = command(['/usr/bin/systemctl', 'list-units', '--all', '--no-legend', '--no-pager', '--plain'])
    for line in output.splitlines():
        fields = line.split()
        if len(fields) >= 4 and fields[0] in current['units'] and fields[2] == 'failed':
            command(['/usr/bin/systemctl', 'reset-failed', '--', fields[0]])
    PHASE = 'disable-units'
    for unit in current['installedUnits']:
        command(['/usr/bin/systemctl', 'disable', '--no-reload', '--', unit])
    PHASE = 'remove-unit-files'
    for record in sorted(current['unitFiles'], key=lambda value: len(value['path']), reverse=True):
        path = pathlib.Path(record['path'])
        if not os.path.lexists(path):
            continue  # An exact planned enable link can have been removed by systemctl disable.
        safe_ancestors(path)
        value = metadata(path)
        if any(value[key] != record[key] for key in ('device', 'inode', 'type', 'uid')):
            raise ResetError('UNIT_DEFINITION_CHANGED')
        if value['type'] == 'directory':
            require_no_mount(path, mount_paths())
            if not shutil.rmtree.avoids_symlink_attacks:
                raise ResetError('FD_SAFE_DELETE_UNAVAILABLE')
            shutil.rmtree(path)
        else:
            path.unlink()
        summary['removedUnitFiles'] += 1
    command(['/usr/bin/systemctl', 'daemon-reload'])
    PHASE = 'remove-environment-accounts'
    for account in current['accounts']:
        code, _ = command(['/usr/bin/pgrep', '-u', str(account['uid'])], allowed=(0, 1))
        if code == 0:
            raise ResetError('ENVIRONMENT_ACCOUNT_ACTIVE')
        for allocation in current['subordinateIds']:
            if allocation['name'] == account['name']:
                option = '--del-subuids' if allocation['file'] == '/etc/subuid' else '--del-subgids'
                span = str(allocation['start']) + '-' + str(allocation['start'] + allocation['count'] - 1)
                command(['/usr/sbin/usermod', option, span, '--', account['name']])
        command(['/usr/sbin/userdel', '--', account['name']])
        summary['removedAccounts'] += 1
    import grp
    for group in current['groups']:
        try:
            observed = grp.getgrnam(group['name'])
        except KeyError:
            continue
        if observed.gr_gid != group['gid'] or observed.gr_mem:
            raise ResetError('ENVIRONMENT_GROUP_CHANGED')
        command(['/usr/sbin/groupdel', '--', group['name']])
    PHASE = 'purge-development-packages'
    if current['purgePackages']:
        lock_wait = 1200 if DEADLINE is None else min(1200, int(DEADLINE - time.monotonic()))
        if lock_wait < 1:
            raise ResetError('RESET_DEADLINE')
        command(['/usr/bin/apt-get', '-o', 'DPkg::Lock::Timeout=' + str(lock_wait), 'purge', '-y', '--']
                + current['purgePackages'], timeout=1200)
        summary['purgedPackages'] = len(current['purgePackages'])
    PHASE = 'clean-download-cache'
    command(['/usr/bin/apt-get', 'clean'])
    summary['aptDownloadCacheCleaned'] = True
    PHASE = 'remove-managed-roots'
    for record in current['roots']:
        if record['exists']:
            remove_root(record)
            summary['removedRoots'] += 1
    PHASE = 'post-inventory'
    after = inventory()
    for _ in range(2):
        if not after['units']:
            break
        time.sleep(1)  # Give systemd bounded time to unload definitions removed by this exact reset.
        after = inventory()
    PHASE = 'post-verify'
    if (any(value['exists'] for value in after['roots']) or after['units'] or after['unitFiles']
            or after['accounts'] or after['groups'] or after['subordinateIds'] or after['installedTargets']
            or after['aptCache']['debFiles'] or after['aptCache']['partialFiles']):
        raise ResetError('POST_RESET_RESIDUE')
    if not all(after['core'][key]['available'] for key in ('python3', 'sshClient', 'sshDaemon')):
        raise ResetError('POST_RESET_CORE_UNAVAILABLE')
    before_core = current['core']['requiredPackages']
    if after['core']['requiredPackages'] != before_core:
        raise ResetError('POST_RESET_CORE_PACKAGES_CHANGED')
    PHASE = 'apply-complete'
    return {'summary': summary, 'postInventory': after}


def main(arguments):
    global PHASE, DEADLINE
    mode = arguments[0] if arguments else 'invalid'
    DEADLINE = time.monotonic() + 1740
    try:
        if arguments == ['inventory']:
            result = {'inventory': inventory()}
            PHASE = 'inventory-complete'
        elif arguments == ['plan']:
            value = plan()
            result = {'plan': value, 'planSha256': digest(canonical(value))}
            PHASE = 'plan-complete'
        elif len(arguments) == 2 and mode == 'apply':
            selected = decode_plan(arguments[1])
            result = {'planSha256': digest(canonical(selected)), **apply(selected)}
        else:
            raise ResetError('ARGUMENTS_INVALID')
        output = {'mode': mode, 'ok': True, 'phase': PHASE, **result}
        if len(canonical(output)) > MAX_JSON:
            raise ResetError('OUTPUT_LIMIT')
        print(canonical(output).decode('ascii'))
        return 0
    except ResetError as error:
        print(canonical({'mode': mode, 'ok': False, 'phase': PHASE, 'error': error.code, **error.facts}).decode('ascii'))
        return 65
    except Exception as error:
        print(canonical({'mode': mode, 'ok': False, 'phase': PHASE, 'error': 'UNCONFIRMED_LOCAL_FAILURE',
                         'exceptionType': type(error).__name__}).decode('ascii'))
        return 70


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
