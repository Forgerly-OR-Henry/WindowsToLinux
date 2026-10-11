"""Agent-only root controller. The prefix supplies catalog, metadata and pure archive functions.

Official asset authentication does not confer execution trust. Only worker mode executes vendor
content; root modes create the bounded volume, validate fixed identities and promote frozen data.
"""
import collections
import errno
import posixpath
import selectors
import stat

AGENT_VOLUME_BYTES = 8 * 1024 * 1024 * 1024
AGENT_HOST_RESERVE_BYTES = 1024 * 1024 * 1024
AGENT_SYSTEM_COMMANDS = frozenset(('fallocate', 'findmnt', 'losetup', 'mkfs.ext4', 'mke2fs',
    'mount', 'systemctl', 'systemd-run', 'umount', 'UNCLASSIFIED'))
AGENT_MAX_MEMBERS = 200000
AGENT_MAX_PROGRAM = 1024 * 1024
AGENT_ALIAS = pathlib.Path('/run/wtl-agent-toolchain')
AGENT_ECOSYSTEMS = ('JAVA', 'NODE', 'PYTHON', 'DOTNET', 'KOTLIN', 'GO', 'RUST', 'PHP', 'RUBY')
AGENT_BINARIES = {
    'JAVA': ('java', 'javac', 'jar', 'javadoc'), 'NODE': ('node', 'npm', 'npx'),
    'PYTHON': ('python3',), 'DOTNET': ('dotnet',), 'KOTLIN': ('kotlinc', 'kotlin'),
    'GO': ('go', 'gofmt'), 'RUST': ('rustc', 'cargo', 'rustdoc'), 'PHP': ('php',),
    'RUBY': ('ruby', 'gem', 'bundle'),
}
AGENT_BOOTSTRAP = (
    "import sys; sys.dont_write_bytecode=True; p=sys.argv.pop(1); code=open(p,'rb').read(); "
    "exec(compile(code,p,'exec'),{'__name__':'__main__','__file__':p,'PROGRAM_SOURCE':code})"
)
DEADLINE = None
AGENT_DIAGNOSTIC_FUNCTIONS = frozenset(('agent_main', 'agent_prepare', 'agent_create', 'agent_prepare_selected',
    'agent_execute', 'agent_bind', 'agent_setup', 'agent_worker', 'agent_freeze', 'agent_release_source',
    'agent_probe_records', 'agent_promote', 'agent_detach', 'agent_cleanup_before_start',
    'agent_cleanup_placeholder', 'agent_os', 'agent_tool', 'agent_regular', 'agent_write',
    'agent_record', 'agent_identity', 'agent_scan', 'agent_copy_tree', 'agent_require_space', 'entry'))


def remaining():
    value = int(DEADLINE - time.monotonic()) if DEADLINE is not None else 7200
    if value < 1:
        fail('timeout', 'DEADLINE_EXHAUSTED')
    return value


def agent_reject(detail, category='isolation'):
    fail(category, detail)


def agent_json(data):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                agent_reject('DUPLICATE_JSON_FIELD')
            value[key] = item
        return value
    if len(data) > 65536:
        agent_reject('JSON_LIMIT')
    try:
        return json.loads(data.decode('utf-8'), object_pairs_hook=unique,
                          parse_constant=lambda _: agent_reject('JSON_CONSTANT'))
    except (ValueError, UnicodeError):
        agent_reject('INVALID_JSON')


def agent_operation(value):
    if not isinstance(value, str) or not re.fullmatch('[a-f0-9]{32}', value):
        agent_reject('OPERATION_ID')
    return ROOT / 'work' / ('agent-' + value)


def agent_hash(value):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None


def agent_regular(path, maximum=65536):
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or info.st_nlink != 1 or info.st_size > maximum:
            agent_reject('PROTECTED_FILE')
        chunks, size = [], 0
        while size <= maximum:
            chunk = os.read(fd, min(65536, maximum + 1 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
        value = b''.join(chunks)
        if len(value) != info.st_size or not agent_same_file(os.fstat(fd), info):
            agent_reject('PROTECTED_FILE_CHANGED')
        return value
    finally:
        os.close(fd)


def agent_same_file(left, right):
    return all(getattr(left, key) == getattr(right, key) for key in
               ('st_dev', 'st_ino', 'st_mode', 'st_uid', 'st_gid', 'st_nlink', 'st_size', 'st_mtime_ns', 'st_ctime_ns'))


def agent_file_sha(path):
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or info.st_nlink < 1 or info.st_size > MAX_ARCHIVE:
            agent_reject('PROTECTED_EXECUTABLE')
        digest, size = hashlib.sha256(), 0
        while True:
            value = os.read(fd, 1024 * 1024)
            if not value:
                break
            size += len(value)
            if size > info.st_size:
                agent_reject('PROTECTED_EXECUTABLE_CHANGED')
            digest.update(value)
        if size != info.st_size or not agent_same_file(os.fstat(fd), info):
            agent_reject('PROTECTED_EXECUTABLE_CHANGED')
        return digest.hexdigest()
    finally:
        os.close(fd)


def agent_write(path, value, mode=0o444):
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        with os.fdopen(fd, 'wb', closefd=False) as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.fchmod(fd, mode)
    finally:
        os.close(fd)


def agent_encode(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def agent_tool(name):
    for prefix in ('/usr/bin', '/usr/sbin', '/bin', '/sbin'):
        path = pathlib.Path(prefix) / name
        if path.is_file():
            path = path.resolve()
            owned_directory(path.parent)
            info = path.stat()
            if info.st_uid == 0 and not info.st_mode & 0o022 and os.access(str(path), os.X_OK):
                return str(path)
    agent_reject('REQUIRED_SYSTEM_TOOL')


def agent_os(command, allow_failure=False, timeout=None):
    """Root executes only product-selected OS administration tools, never a vendor path."""
    process = subprocess.Popen(command, cwd='/', stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C'},
                               start_new_session=True)
    output = bytearray()
    deadline = time.monotonic() + (timeout if timeout is not None else remaining())
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                if time.monotonic() >= deadline:
                    agent_reject('SYSTEM_COMMAND_TIMEOUT', 'unknown')
                for key, _ in selector.select(min(0.2, max(0.01, deadline - time.monotonic()))):
                    block = os.read(key.fileobj.fileno(), 8192)
                    if not block:
                        selector.unregister(key.fileobj)
                    else:
                        output.extend(block)
                        if len(output) > 65536:
                            agent_reject('SYSTEM_COMMAND_OUTPUT_LIMIT', 'unknown')
            code = process.wait(timeout=max(0.01, deadline - time.monotonic()))
        if code and not allow_failure:
            name = pathlib.PurePosixPath(command[0]).name
            error = PreparationFailure('isolation', 'SYSTEM_COMMAND_FAILED')
            error.command_diagnostic = {'command': name if name in AGENT_SYSTEM_COMMANDS else 'UNCLASSIFIED',
                'exitCode': code, 'outputBytes': len(output), 'outputSha256': hashlib.sha256(output).hexdigest()}
            raise error
        return code, bytes(output)
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
        process.wait()
        process.stdout.close()


def agent_unit(operation):
    agent_operation(operation)
    return 'windowstolinux-toolchain-' + operation + '.service'


def agent_user(operation):
    return 'wtlt-' + hashlib.sha256(operation.encode()).hexdigest()[:16]


def agent_identity(path):
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or path.is_symlink() or info.st_uid != 0 or info.st_mode & 0o022:
        agent_reject('PROTECTED_DIRECTORY')
    return [info.st_dev, info.st_ino]


def agent_manifest(operation, alias=False):
    base = AGENT_ALIAS if alias else agent_operation(operation)
    owned_directory(base)
    manifest = agent_json(agent_regular(base / 'manifest.json'))
    fields = {'format', 'operation', 'programSha256', 'seconds', 'deadline', 'action', 'request',
              'directories', 'loop', 'imageIdentity', 'mountIdentity'}
    if not isinstance(manifest, dict) or set(manifest) != fields or manifest['format'] != 'WTL_AGENT_TOOLCHAIN_1':
        agent_reject('MANIFEST_SCHEMA')
    if manifest['operation'] != operation or not agent_hash(manifest['programSha256']):
        agent_reject('MANIFEST_BINDING')
    source = globals().get('PROGRAM_SOURCE')
    if not isinstance(source, bytes) or not 1 <= len(source) <= AGENT_MAX_PROGRAM or hashlib.sha256(source).hexdigest() != manifest['programSha256']:
        agent_reject('PROGRAM_BINDING')
    if agent_regular(base / 'controller.py', AGENT_MAX_PROGRAM) != source:
        agent_reject('CONTROLLER_BINDING')
    if type(manifest['seconds']) is not int or not 30 <= manifest['seconds'] <= 7200 or type(manifest['deadline']) is not int:
        agent_reject('MANIFEST_DEADLINE')
    if manifest['action'] not in ('INSTALL', 'PROBE'):
        agent_reject('MANIFEST_ACTION')
    if not isinstance(manifest['loop'], str) or not re.fullmatch('/dev/loop[0-9]+', manifest['loop']):
        agent_reject('MANIFEST_LOOP')
    for key in ('imageIdentity', 'mountIdentity'):
        if not isinstance(manifest[key], list) or len(manifest[key]) != 2 or any(type(v) is not int or v < 1 for v in manifest[key]):
            agent_reject('MANIFEST_INODE')
    directories = manifest['directories']
    if not isinstance(directories, dict) or set(directories) != {'out', 'work', 'home', 'tmp'}:
        agent_reject('MANIFEST_DIRECTORIES')
    for value in directories.values():
        if not isinstance(value, list) or len(value) != 2 or any(type(v) is not int or v < 1 for v in value):
            agent_reject('MANIFEST_DIRECTORY_INODE')
    request = manifest['request']
    if manifest['action'] == 'INSTALL':
        expected = {'ecosystem', 'version', 'destination', 'layout', 'sourceName', 'javaHome', 'legacyGem'}
        if not isinstance(request, dict) or set(request) != expected:
            agent_reject('INSTALL_SCHEMA')
        agent_selection(request['ecosystem'], request['version'], request['destination'])
        if request['layout'] not in ('flat', 'archive', 'rust', 'python', 'php', 'ruby'):
            agent_reject('INSTALL_LAYOUT')
        if request['sourceName'] != 'release' or not agent_java_home(request['javaHome']):
            agent_reject('INSTALL_SOURCE')
        if type(request['legacyGem']) is not bool or request['legacyGem'] != (request['ecosystem'] == 'RUBY' and version_key(request['version'])[:2] == (3, 0)):
            agent_reject('INSTALL_GEM')
    else:
        if not isinstance(request, dict) or set(request) != {'probes', 'javaHome'} or not agent_java_home(request['javaHome']):
            agent_reject('PROBE_SCHEMA')
        probes = request['probes']
        if not isinstance(probes, list) or not 1 <= len(probes) <= 32:
            agent_reject('PROBE_COUNT')
        for probe in probes:
            if not isinstance(probe, dict) or set(probe) != {'ecosystem', 'version', 'directory'}:
                agent_reject('PROBE_RECORD')
            agent_selection(probe['ecosystem'], probe['version'], probe['directory'], managed=False)
    return manifest


def agent_java_home(value):
    return value == '-' or isinstance(value, str) and re.fullmatch(re.escape(str(ROOT)) + '/versions/java-[a-f0-9]{64}', value) is not None


def agent_selection(ecosystem, version, directory, managed=True):
    if not isinstance(ecosystem, str) or ecosystem not in AGENT_ECOSYSTEMS or not isinstance(version, str) or not re.fullmatch('[0-9][A-Za-z0-9._+-]{0,95}', version) or not version_key(version):
        agent_reject('SELECTION')
    if not isinstance(directory, str) or not re.fullmatch('/[A-Za-z0-9._+/-]{1,2047}', directory) or '..' in directory.split('/'):
        agent_reject('SELECTION_DIRECTORY')
    if managed and (pathlib.Path(directory).parent != ROOT / 'versions' or not re.fullmatch(ecosystem.lower() + '-[a-f0-9]{64}', pathlib.Path(directory).name)):
        agent_reject('MANAGED_DIRECTORY')


def agent_volume(manifest, require_ro=False):
    base = agent_operation(manifest['operation'])
    owned_directory(base)
    image = base / 'volume.ext4'
    info = image.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_nlink != 1 or info.st_size != AGENT_VOLUME_BYTES:
        agent_reject('VOLUME_IMAGE')
    if [info.st_dev, info.st_ino] != manifest['imageIdentity']:
        agent_reject('VOLUME_IMAGE_CHANGED')
    _, output = agent_os([agent_tool('losetup'), '--noheadings', '--raw', '--output', 'BACK-FILE', manifest['loop']])
    if output.strip().decode() != str(image):
        agent_reject('VOLUME_LOOP_OWNER')
    _, output = agent_os([agent_tool('findmnt'), '-rn', '-M', str(base / 'mutable'), '-o', 'SOURCE,FSTYPE,OPTIONS'])
    parts = output.decode().strip().split()
    if len(parts) != 3 or parts[:2] != [manifest['loop'], 'ext4'] or not {'nodev', 'nosuid'} <= set(parts[2].split(',')):
        agent_reject('VOLUME_MOUNT')
    if require_ro and 'ro' not in parts[2].split(','):
        agent_reject('VOLUME_NOT_FROZEN', 'unknown')
    if agent_identity(base / 'mutable') != manifest['mountIdentity']:
        agent_reject('VOLUME_MOUNT_CHANGED')


def agent_setup(operation):
    import pwd
    if os.geteuid() != 0:
        agent_reject('SETUP_REQUIRES_ROOT')
    manifest = agent_manifest(operation)
    agent_volume(manifest)
    user = pwd.getpwnam(agent_user(operation))
    if user.pw_uid <= 0 or user.pw_gid <= 0:
        agent_reject('DYNAMIC_IDENTITY')
    base = agent_operation(operation)
    for name, identity in manifest['directories'].items():
        fd = os.open(str(base / 'mutable' / name), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            info = os.fstat(fd)
            if [info.st_dev, info.st_ino] != identity or info.st_uid != 0 or info.st_mode & 0o022 or os.listdir(fd):
                agent_reject('SETUP_REQUIRES_ORIGINAL_EMPTY_DIRECTORY')
            os.fchown(fd, user.pw_uid, user.pw_gid)
            os.fchmod(fd, 0o700)
        finally:
            os.close(fd)
    path = base / 'identity.json'
    initial = agent_json(agent_regular(path))
    if initial != {'operation': operation, 'uid': 0, 'gid': 0, 'programSha256': manifest['programSha256']}:
        agent_reject('IDENTITY_ALREADY_ASSIGNED')
    value = agent_encode({'operation': operation, 'uid': user.pw_uid, 'gid': user.pw_gid, 'programSha256': manifest['programSha256']})
    fd = os.open(str(path), os.O_WRONLY | os.O_NOFOLLOW)
    try:
        os.ftruncate(fd, 0)
        os.write(fd, value)
        os.fsync(fd)
    finally:
        os.close(fd)


def agent_freeze(operation):
    if os.geteuid() != 0:
        agent_reject('FREEZE_REQUIRES_ROOT')
    manifest = agent_manifest(operation)
    agent_volume(manifest)
    base = agent_operation(operation)
    identity = agent_json(agent_regular(base / 'identity.json'))
    if set(identity) != {'operation', 'uid', 'gid', 'programSha256'} or identity['operation'] != operation or identity['programSha256'] != manifest['programSha256'] or type(identity['uid']) is not int or identity['uid'] <= 0 or type(identity['gid']) is not int or identity['gid'] <= 0:
        agent_reject('FREEZE_IDENTITY')
    for name, original in manifest['directories'].items():
        fd = os.open(str(base / 'mutable' / name), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            info = os.fstat(fd)
            if [info.st_dev, info.st_ino] != original or info.st_uid not in (0, identity['uid']):
                agent_reject('FREEZE_DIRECTORY_CHANGED')
            os.fchown(fd, 0, 0)
            os.fchmod(fd, 0o700)
        finally:
            os.close(fd)
    agent_os([agent_tool('mount'), '-o', 'remount,ro,nodev,nosuid', '--', str(base / 'mutable')], timeout=30)
    agent_volume(manifest, require_ro=True)
    agent_write(base / 'frozen.json', agent_encode({'operation': operation, 'programSha256': manifest['programSha256'], 'mountIdentity': manifest['mountIdentity']}))


def agent_sandbox_properties(manifest):
    operation = manifest['operation']
    base, alias = agent_operation(operation), AGENT_ALIAS
    binds = [str(base / 'mutable' / name) + ':' + str(alias / name) for name in ('out', 'work', 'home', 'tmp')]
    binds += [str(base / 'mutable/tmp') + ':' + name for name in ('/tmp', '/var/tmp', '/dev/shm')]
    if manifest['action'] == 'INSTALL':
        binds.append(str(base / 'mutable/out') + ':' + manifest['request']['destination'])
    readonly = [str(base / name) + ':' + str(alias / name) for name in ('controller.py', 'manifest.json', 'identity.json')]
    readonly.append(str(base / 'mutable/input') + ':' + str(alias / 'input'))
    properties = [
        'User=' + agent_user(operation), 'Group=' + agent_user(operation), 'DynamicUser=yes',
        'NoNewPrivileges=yes', 'CapabilityBoundingSet=', 'AmbientCapabilities=', 'ProtectSystem=strict',
        'ProtectHome=yes', 'PrivateNetwork=yes', 'RestrictNamespaces=yes', 'RestrictSUIDSGID=yes',
        'ProtectControlGroups=yes', 'PrivateDevices=yes', 'ProtectKernelTunables=yes',
        'ProtectKernelModules=yes', 'ProtectKernelLogs=yes', 'ProtectProc=invisible', 'ProcSubset=pid',
        'Delegate=no', 'KillMode=control-group', 'SendSIGKILL=yes', 'TimeoutStopSec=10s',
        'RuntimeMaxSec=' + str(min(manifest['seconds'], remaining())), 'MemoryMax=2147483648',
        'MemorySwapMax=0', 'TasksMax=256', 'UMask=0077', 'RemoveIPC=yes', 'Slice=system.slice',
        'TemporaryFileSystem=/run:ro', 'BindReadOnlyPaths=' + ' '.join(readonly),
        'BindPaths=' + ' '.join(binds),
        'ReadWritePaths=' + ' '.join(str(base / 'mutable' / name) for name in ('out', 'work', 'home', 'tmp')),
        'InaccessiblePaths=-/run/dbus -/run/docker.sock -/run/podman -/run/user -/opt/windowstolinux/apps -/etc/opt/windowstolinux/apps -/var/opt/windowstolinux/apps',
        'WorkingDirectory=/',
    ]
    # Only the fixed controller callbacks use +; the vendor worker is the ordinary unprivileged ExecStart.
    for kind in ('setup', 'freeze'):
        command = '/usr/bin/python3 -I -S -c ' + json.dumps(AGENT_BOOTSTRAP) + ' ' + str(base / 'controller.py') + ' ' + kind + ' ' + operation
        properties.append(('ExecStartPre=+' if kind == 'setup' else 'ExecStopPost=+') + command)
    return properties


def agent_stopped(manifest):
    operation = manifest['operation']
    code, output = agent_os([agent_tool('systemctl'), 'show', '--property=LoadState', '--property=ActiveState',
                            '--property=ControlGroup', agent_unit(operation)], allow_failure=True, timeout=30)
    values = {}
    for line in output.decode().splitlines():
        key, separator, value = line.partition('=')
        if not separator or key in values:
            agent_reject('UNIT_STATE_UNCONFIRMED', 'unknown')
        values[key] = value
    expected_group = '/system.slice/' + agent_unit(operation)
    if set(values) != {'LoadState', 'ActiveState', 'ControlGroup'} or values['LoadState'] not in ('loaded', 'not-found') or values['ActiveState'] not in ('inactive', 'failed') or values['ControlGroup'] not in ('', expected_group) or code and values['LoadState'] != 'not-found':
        agent_reject('UNIT_NOT_STOPPED', 'unknown')
    group = pathlib.Path('/sys/fs/cgroup/system.slice') / agent_unit(operation)
    if group.exists():
        for path in group.rglob('cgroup.procs'):
            if path.read_text().strip():
                agent_reject('CGROUP_NOT_EMPTY', 'unknown')
    frozen = agent_json(agent_regular(agent_operation(operation) / 'frozen.json'))
    if frozen != {'operation': operation, 'programSha256': manifest['programSha256'], 'mountIdentity': manifest['mountIdentity']}:
        agent_reject('FREEZE_RECEIPT', 'unknown')
    agent_volume(manifest, require_ro=True)


def agent_execute(manifest):
    global DEADLINE
    DEADLINE = manifest['deadline']
    args = [agent_tool('systemd-run'), '--quiet', '--wait', '--pipe', '--collect', '--unit=' + agent_unit(manifest['operation'])]
    args += ['--property=' + value for value in agent_sandbox_properties(manifest)]
    args += ['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin', 'HOME=' + str(AGENT_ALIAS / 'home'),
             'TMPDIR=' + str(AGENT_ALIAS / 'tmp'), 'XDG_CACHE_HOME=' + str(AGENT_ALIAS / 'home/.cache'),
             '/usr/bin/python3', '-I', '-S', '-c', AGENT_BOOTSTRAP, str(AGENT_ALIAS / 'controller.py'), 'worker', manifest['operation']]
    code, output = 1, b''
    agent_write(agent_operation(manifest['operation']) / 'start-intent.json', agent_encode({
        'operation': manifest['operation'], 'programSha256': manifest['programSha256']}))
    try:
        code, output = agent_os(args, allow_failure=True)
    finally:
        agent_os([agent_tool('systemctl'), 'stop', agent_unit(manifest['operation'])], allow_failure=True, timeout=30)
        agent_stopped(manifest)
    if code or output != ('WORKER_OK=' + manifest['programSha256'] + '\n').encode():
        agent_reject('NONROOT_WORKER_FAILED', 'installation')


def agent_vendor(command, environment, cwd=None, capture=False):
    if os.geteuid() == 0:
        agent_reject('ROOT_VENDOR_EXECUTION_FORBIDDEN')
    with tempfile.TemporaryFile(dir=str(AGENT_ALIAS / 'tmp')) as output:
        process = subprocess.Popen(command, cwd=cwd, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=output if capture else subprocess.DEVNULL,
                                   stderr=subprocess.STDOUT if capture else subprocess.DEVNULL, start_new_session=True)
        try:
            code = process.wait(timeout=min(remaining(), 60) if capture else remaining())
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
        if code:
            agent_reject('VENDOR_COMMAND_FAILED', 'installation')
        if capture:
            output.seek(0)
            value = output.read(8193)
            if len(value) > 8192:
                agent_reject('VENDOR_PROBE_OUTPUT_LIMIT')
            return value.decode('utf-8', errors='replace')
    return ''


def agent_environment(directory, java_home):
    value = {'PATH': str(directory / 'bin') + ':' + str(directory) + ':/usr/bin:/bin', 'LC_ALL': 'C',
             'HOME': str(AGENT_ALIAS / 'home'), 'TMPDIR': str(AGENT_ALIAS / 'tmp')}
    if java_home != '-':
        value['JAVA_HOME'] = java_home
    return value


def agent_probe(ecosystem, directory, expected, java_home):
    commands = {
        'JAVA': ['bin/java', '-version'], 'NODE': ['bin/node', '--version'], 'PYTHON': ['bin/python3', '--version'],
        'DOTNET': ['dotnet', '--list-sdks'], 'KOTLIN': ['bin/kotlinc', '-version'], 'GO': ['bin/go', 'version'],
        'RUST': ['bin/rustc', '--version'], 'PHP': ['bin/php', '-r', 'echo PHP_VERSION;'],
        'RUBY': ['bin/ruby', '-e', 'print RUBY_VERSION'],
    }
    directory = pathlib.Path(directory)
    environment = agent_environment(directory, java_home)
    command = list(commands[ecosystem])
    command[0] = str(directory / command[0])
    output = agent_vendor(command, environment, capture=True)
    tokens = re.findall(r'\d+(?:[._]\d+)*(?:u\d+)?(?:(?:\+|-b)\d+(?:\.\d+)*)?', output)
    if ecosystem == 'JAVA':
        tokens = [t[2:].replace('_', '.') if t.startswith('1.8') else t for t in tokens]
    if not any(version_key(t) == version_key(expected) and release_build(t) == release_build(expected) for t in tokens):
        agent_reject('EXACT_PROBE_MISMATCH', 'probe')
    additional = {
        'JAVA': ['bin/javac', '-version'], 'PYTHON': ['bin/python3', '-c', 'import ssl, sqlite3, bz2, lzma, venv, zlib'],
        'PHP': ['bin/php', '-r', 'foreach (["curl","iconv","mbstring","pdo","pdo_sqlite","zip"] as $e) { if (!extension_loaded($e)) { exit(1); } }'],
        'RUBY': ['bin/ruby', '-e', 'require "openssl"; require "zlib"; require "yaml"'],
    }
    if ecosystem in additional:
        command = list(additional[ecosystem])
        command[0] = str(directory / command[0])
        agent_vendor(command, environment)


def agent_worker(operation):
    global DEADLINE
    if os.geteuid() == 0:
        agent_reject('WORKER_REQUIRES_NONROOT')
    manifest = agent_manifest(operation, alias=True)
    DEADLINE = manifest['deadline']
    identity = agent_json(agent_regular(AGENT_ALIAS / 'identity.json'))
    if identity != {'operation': operation, 'uid': os.getuid(), 'gid': os.getgid(), 'programSha256': manifest['programSha256']} or os.getgid() == 0:
        agent_reject('WORKER_IDENTITY')
    status = pathlib.Path('/proc/self/status').read_text()
    if not re.search(r'^CapEff:\s+0+$', status, re.M) or not re.search(r'^NoNewPrivs:\s+1$', status, re.M):
        agent_reject('WORKER_PRIVILEGES')
    for name in ('out', 'work', 'home', 'tmp'):
        info = (AGENT_ALIAS / name).lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or [info.st_dev, info.st_ino] != manifest['directories'][name]:
            agent_reject('WORKER_DIRECTORY')
    os.chdir(str(AGENT_ALIAS / 'work'))
    request = manifest['request']
    if manifest['action'] == 'PROBE':
        for selected in request['probes']:
            agent_probe(selected['ecosystem'], selected['directory'], selected['version'], request['javaHome'])
    else:
        destination = pathlib.Path(request['destination'])
        if [destination.stat().st_dev, destination.stat().st_ino] != manifest['directories']['out']:
            agent_reject('FINAL_PREFIX_NOT_BOUND')
        source = AGENT_ALIAS / 'work/source'
        shutil.copytree(AGENT_ALIAS / 'input/release', source, symlinks=True)
        for directory, dirs, files in os.walk(source, followlinks=False):
            os.chmod(directory, 0o700)
            for name in files:
                path = pathlib.Path(directory) / name
                if not path.is_symlink():
                    path.chmod(0o700 if path.stat().st_mode & 0o111 else 0o600)
        environment = agent_environment(destination, request['javaHome'])
        layout = request['layout']
        if layout in ('flat', 'archive'):
            shutil.copytree(source, destination, dirs_exist_ok=True, symlinks=True)
        elif layout == 'rust':
            agent_vendor(['/bin/sh', str(source / 'install.sh'), '--prefix=' + str(destination), '--disable-ldconfig'], environment, source)
        else:
            options = {
                'python': ['--with-ensurepip=install'],
                'php': ['--disable-all', '--enable-cli', '--enable-mbstring', '--with-iconv', '--with-openssl', '--with-zlib',
                        '--with-zip', '--enable-phar', '--enable-tokenizer', '--enable-session', '--enable-filter', '--with-curl',
                        '--enable-pdo', '--with-pdo-pgsql', '--with-pdo-mysql=mysqlnd', '--with-mysqli=mysqlnd',
                        '--with-pgsql', '--with-sqlite3', '--with-pdo-sqlite'],
                'ruby': ['--disable-install-doc'],
            }[layout]
            if request['legacyGem']:
                options += ['--with-out-ext=openssl']
            agent_vendor([str(source / 'configure'), '--prefix=' + str(destination)] + options, environment, source)
            agent_vendor(['/usr/bin/make', '-j2'], environment, source)
            agent_vendor(['/usr/bin/make', 'install'], environment, source)
            if request['legacyGem']:
                agent_vendor([str(destination / 'bin/ruby'), str(destination / 'bin/gem'), 'install', '--local',
                              str(AGENT_ALIAS / 'input/openssl-3.0.3.gem'), '--no-document', '--ignore-dependencies'], environment, source)
        agent_probe(request['ecosystem'], str(destination), request['version'], request['javaHome'])
    print('WORKER_OK=' + manifest['programSha256'])


def agent_installation_key(ecosystem, version, digest):
    suffix = '\nruby-openssl-3.0.3' if ecosystem == 'RUBY' and version_key(version)[:2] == (3, 0) else ''
    if ecosystem == 'PHP':
        suffix = '\nphp-cli-iconv-zip'
    return hashlib.sha256((ecosystem + '\n' + version + '\n' + digest + suffix).encode()).hexdigest()


def agent_require_space(path, operation, stage, entries=None):
    owned_directory(path)
    filesystem = os.statvfs(str(path))
    block = filesystem.f_frsize
    available = filesystem.f_bavail * block
    if type(block) is not int or not 1 <= block <= 1024 * 1024 or type(available) is not int or not 0 <= available <= 2 ** 63 - 1:
        agent_reject('FILESYSTEM_SPACE_UNCONFIRMED', 'unknown')
    if stage == 'VOLUME' and entries is None:
        requested = AGENT_VOLUME_BYTES
    elif stage == 'PROMOTION' and isinstance(entries, dict):
        requested = sum(((info.st_size + block - 1) // block) * block for info, _ in entries.values() if stat.S_ISREG(info.st_mode))
        requested += len(entries) * block
    else:
        agent_reject('FILESYSTEM_SPACE_UNCONFIRMED', 'unknown')
    required = requested + AGENT_HOST_RESERVE_BYTES
    if available < required:
        error = PreparationFailure('isolation', 'HOST_SPACE_REQUIRED')
        error.diagnostic = {'operation': operation, 'phase': 'HOST_SPACE_REQUIRED', 'stage': stage,
            'availableBytes': available, 'requiredBytes': required, 'reserveBytes': AGENT_HOST_RESERVE_BYTES}
        raise error


def agent_create(operation, seconds):
    import pwd
    if os.geteuid() != 0:
        agent_reject('CONTROLLER_REQUIRES_ROOT')
    base = agent_operation(operation)
    for path in (ROOT, ROOT / 'versions', ROOT / 'bindings', ROOT / 'work'):
        owned_directory(path)
        path.mkdir(mode=0o755 if path.name != 'work' else 0o700, parents=True, exist_ok=True)
    if base.exists() or base.is_symlink():
        agent_reject('OPERATION_ALREADY_EXISTS', 'unknown')
    _, state = agent_os([agent_tool('systemctl'), 'show', '--value', '--property=LoadState', agent_unit(operation)], allow_failure=True)
    if state.strip() != b'not-found':
        agent_reject('UNIT_ALREADY_EXISTS', 'unknown')
    try:
        pwd.getpwnam(agent_user(operation))
    except KeyError:
        pass
    else:
        agent_reject('IDENTITY_COLLISION')
    source = globals().get('PROGRAM_SOURCE')
    if not isinstance(source, bytes) or not 1 <= len(source) <= AGENT_MAX_PROGRAM:
        agent_reject('COMPLETE_PROGRAM_REQUIRED')
    controllers = pathlib.Path('/sys/fs/cgroup/cgroup.controllers').read_text().split()
    if not {'memory', 'pids'} <= set(controllers):
        agent_reject('CGROUP_V2_REQUIRED')
    agent_require_space(base.parent, operation, 'VOLUME')
    base.mkdir(mode=0o755)
    agent_write(base / 'controller.py', source)
    agent_write(base / 'identity.json', agent_encode({'operation': operation, 'uid': 0, 'gid': 0, 'programSha256': hashlib.sha256(source).hexdigest()}))
    image = base / 'volume.ext4'
    agent_write(image, b'', 0o600)
    agent_os([agent_tool('fallocate'), '-l', str(AGENT_VOLUME_BYTES), '--', str(image)])
    agent_os([agent_tool('mkfs.ext4'), '-q', '-F', '-m', '0', '-E', 'nodiscard,lazy_itable_init=0,lazy_journal_init=0', str(image)])
    _, output = agent_os([agent_tool('losetup'), '--find', '--show', '--nooverlap', '--sizelimit', str(AGENT_VOLUME_BYTES), str(image)])
    device = output.decode().strip()
    if not re.fullmatch('/dev/loop[0-9]+', device):
        agent_reject('LOOP_IDENTITY', 'unknown')
    (base / 'mutable').mkdir(mode=0o755)
    agent_os([agent_tool('mount'), '-t', 'ext4', '-o', 'nodev,nosuid', device, str(base / 'mutable')])
    for name in ('input', 'out', 'work', 'home', 'tmp'):
        (base / 'mutable' / name).mkdir(mode=0o755)
    info = image.lstat()
    return {'format': 'WTL_AGENT_TOOLCHAIN_1', 'operation': operation, 'programSha256': hashlib.sha256(source).hexdigest(),
            'seconds': seconds, 'deadline': int(DEADLINE), 'action': '', 'request': {},
            'directories': {name: agent_identity(base / 'mutable' / name) for name in ('out', 'work', 'home', 'tmp')},
            'loop': device, 'imageIdentity': [info.st_dev, info.st_ino], 'mountIdentity': agent_identity(base / 'mutable')}


def agent_validate_entry(info, root_device, capabilities=b''):
    if info.st_dev != root_device or info.st_mode & 0o6000 or capabilities:
        agent_reject('UNSAFE_OUTPUT_METADATA')
    if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode)):
        agent_reject('SPECIAL_OUTPUT')


def agent_resolve_link(name, target, links, names):
    if not target or target.startswith('/') or '\\' in target or '\0' in target:
        agent_reject('EXTERNAL_OUTPUT_LINK')
    queue = (posixpath.dirname(name) + '/' + target).split('/')
    parts, hops = [], 0
    while queue:
        part = queue.pop(0)
        if part in ('', '.'):
            continue
        if part == '..':
            if not parts:
                agent_reject('EXTERNAL_OUTPUT_LINK')
            parts.pop()
            continue
        key = '/'.join(parts + [part])
        if key in links:
            hops += 1
            value = links[key]
            if hops > 40 or value.startswith('/') or '\\' in value:
                agent_reject('OUTPUT_LINK_CHAIN')
            queue = value.split('/') + queue
        else:
            parts.append(part)
    value = '/'.join(parts)
    if value not in names and value != '':
        agent_reject('DANGLING_OUTPUT_LINK')
    return value


def agent_scan(fd, prefix='', root_device=None, result=None):
    result = {} if result is None else result
    root_device = os.fstat(fd).st_dev if root_device is None else root_device
    for name in sorted(os.listdir(fd)):
        path = prefix + name
        info = os.stat(name, dir_fd=fd, follow_symlinks=False)
        capabilities = b''
        if stat.S_ISREG(info.st_mode):
            child = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=fd)
            try:
                if not agent_same_file(os.fstat(child), info):
                    agent_reject('OUTPUT_CHANGED')
                try:
                    capabilities = os.getxattr(child, 'security.capability')
                except OSError as error:
                    if error.errno not in (errno.ENODATA, getattr(errno, 'ENOATTR', errno.ENODATA)):
                        raise
            finally:
                os.close(child)
        agent_validate_entry(info, root_device, capabilities)
        result[path] = (info, os.readlink(name, dir_fd=fd) if stat.S_ISLNK(info.st_mode) else None)
        if len(result) > AGENT_MAX_MEMBERS:
            agent_reject('OUTPUT_MEMBER_LIMIT')
        if stat.S_ISDIR(info.st_mode):
            child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            try:
                agent_scan(child, path + '/', root_device, result)
            finally:
                os.close(child)
    return result


def agent_validate_tree(entries):
    if not entries:
        agent_reject('EMPTY_OUTPUT')
    links = {name: target for name, (_, target) in entries.items() if target is not None}
    counts = collections.Counter((info.st_dev, info.st_ino) for info, _ in entries.values() if stat.S_ISREG(info.st_mode))
    total = 0
    for name, (info, target) in entries.items():
        if name == '.w2l-toolchain.json' or name.startswith('.w2l-toolchain.json/'):
            agent_reject('VENDOR_COMPLETION_MARKER')
        if stat.S_ISREG(info.st_mode):
            if info.st_nlink != counts[(info.st_dev, info.st_ino)]:
                agent_reject('EXTERNAL_OUTPUT_HARDLINK')
            total += info.st_size
            if info.st_size > MAX_ARCHIVE or total > AGENT_VOLUME_BYTES:
                agent_reject('OUTPUT_BYTE_LIMIT')
        if target is not None:
            agent_resolve_link(name, target, links, set(entries))


def agent_copy_tree(source_fd, target_fd, entries, prefix='', digest=None):
    digest = hashlib.sha256() if digest is None else digest
    for name in sorted(os.listdir(source_fd)):
        path = prefix + name
        info, link = entries[path]
        if not agent_same_file(os.stat(name, dir_fd=source_fd, follow_symlinks=False), info):
            agent_reject('OUTPUT_CHANGED')
        digest.update(agent_encode([path, stat.S_IFMT(info.st_mode), bool(info.st_mode & 0o111), info.st_size, link]))
        if stat.S_ISDIR(info.st_mode):
            os.mkdir(name, 0o755, dir_fd=target_fd)
            source = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=source_fd)
            target = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=target_fd)
            try:
                agent_copy_tree(source, target, entries, path + '/', digest)
                os.fchmod(target, 0o755)
            finally:
                os.close(source)
                os.close(target)
        elif stat.S_ISLNK(info.st_mode):
            os.symlink(link, name, dir_fd=target_fd)
        else:
            source = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=source_fd)
            target = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=target_fd)
            try:
                if not agent_same_file(os.fstat(source), info):
                    agent_reject('OUTPUT_CHANGED')
                size = 0
                while True:
                    value = os.read(source, 1024 * 1024)
                    if not value:
                        break
                    size += len(value)
                    if size > info.st_size:
                        agent_reject('OUTPUT_CHANGED')
                    digest.update(value)
                    with os.fdopen(os.dup(target), 'wb') as stream:
                        stream.write(value)
                if size != info.st_size or not agent_same_file(os.fstat(source), info):
                    agent_reject('OUTPUT_CHANGED')
                os.fchmod(target, 0o755 if info.st_mode & 0o111 else 0o644)
                os.fsync(target)
            finally:
                os.close(source)
                os.close(target)
    return digest.hexdigest()


def agent_promote(manifest, destination, original, record):
    agent_stopped(manifest)
    if agent_identity(destination) != original or list(destination.iterdir()):
        agent_reject('PROMOTION_TARGET_CHANGED', 'unknown')
    temporary = ROOT / 'versions' / ('.agent-promote-' + manifest['operation'])
    if temporary.exists() or temporary.is_symlink():
        agent_reject('PROMOTION_ALREADY_EXISTS', 'unknown')
    temporary.mkdir(mode=0o700)
    source_fd = os.open(str(agent_operation(manifest['operation']) / 'mutable/out'), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    target_fd = os.open(str(temporary), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        entries = agent_scan(source_fd)
        agent_validate_tree(entries)
        agent_require_space(temporary, manifest['operation'], 'PROMOTION', entries)
        record['tree_sha256'] = agent_copy_tree(source_fd, target_fd, entries)
        record['agent_program_sha256'] = manifest['programSha256']
        # Only the root controller creates this marker after stopped/frozen output has been copied.
        agent_write(temporary / '.w2l-toolchain.json', agent_encode(record))
        os.fchmod(target_fd, 0o755)
        os.fsync(target_fd)
    finally:
        os.close(source_fd)
        os.close(target_fd)
    # A complete marker remains hidden under the operation staging name until the volume is detached.
    agent_detach(manifest)
    if agent_identity(destination) != original or list(destination.iterdir()):
        agent_reject('PROMOTION_TARGET_CHANGED', 'unknown')
    os.rename(str(temporary), str(destination))
    fd = os.open(str(ROOT / 'versions'), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def agent_record(directory):
    owned_directory(directory)
    record = agent_json(agent_regular(directory / '.w2l-toolchain.json'))
    required = {'ecosystem', 'version', 'directory', 'origin', 'source', 'sha256'}
    allowed = required | {'system_files', 'official_algorithm', 'official_digest', 'dependencies', 'tree_sha256', 'agent_program_sha256'}
    if not isinstance(record, dict) or not required <= set(record) <= allowed or record['directory'] != str(directory) or record['origin'] not in ('MANAGED', 'SYSTEM') or not isinstance(record['source'], str) or not re.fullmatch('[!-~]{1,2048}', record['source']) or '|' in record['source']:
        agent_reject('INSTALLATION_RECORD')
    agent_selection(record['ecosystem'], record['version'], record['directory'])
    if not agent_hash(record['sha256']) or any(not agent_hash(record[key]) for key in ('tree_sha256', 'agent_program_sha256') if key in record):
        agent_reject('INSTALLATION_DIGEST')
    if record['origin'] == 'SYSTEM':
        files = record.get('system_files')
        if not isinstance(record['source'], str) or not record['source'].startswith('system:/') or not isinstance(files, list) or not 1 <= len(files) <= 8:
            agent_reject('SYSTEM_FILE_RECORD')
        names = set()
        for item in files:
            if not isinstance(item, dict) or set(item) != {'name', 'path', 'sha256'} or item['name'] not in AGENT_BINARIES[record['ecosystem']] or item['name'] in names or not isinstance(item['path'], str) or not item['path'].startswith('/') or not agent_hash(item['sha256']):
                agent_reject('SYSTEM_FILE_RECORD')
            names.add(item['name'])
            path = pathlib.Path(item['path'])
            owned_directory(path.parent)
            if path.is_symlink() or agent_file_sha(path) != item['sha256']:
                agent_reject('SYSTEM_FILE_CHANGED')
        primary = next((item['path'] for item in files if item['name'] == AGENT_BINARIES[record['ecosystem']][0]), None)
        if record['source'] != 'system:' + str(primary):
            agent_reject('SYSTEM_SOURCE_RECORD')
    else:
        if not isinstance(record.get('source'), str) or record.get('official_algorithm') not in ('sha256', 'sha512') or not isinstance(record.get('official_digest'), str) or not re.fullmatch('[a-f0-9]{' + ('64' if record['official_algorithm'] == 'sha256' else '128') + '}', record['official_digest']):
            agent_reject('OFFICIAL_DIGEST_RECORD')
        official_url(record['source'])
    dependencies = record.get('dependencies', {})
    if not isinstance(dependencies, dict) or set(dependencies) - {'openssl'}:
        agent_reject('INSTALLATION_DEPENDENCIES')
    if dependencies and dependencies != {'openssl': {'version': '3.0.3',
            'source': 'https://rubygems.org/downloads/openssl-3.0.3.gem',
            'sha256': 'e24fcd69f6e0bac1e1c3cb8667d0ded5c1f6c59d010ecb5c857a771c9cb7565c'}}:
        agent_reject('INSTALLATION_DEPENDENCIES')
    if record['origin'] == 'MANAGED' and bool(dependencies) != (record['ecosystem'] == 'RUBY' and version_key(record['version'])[:2] == (3, 0)):
        agent_reject('INSTALLATION_DEPENDENCIES')
    return record


def agent_publish_manifest(manifest):
    agent_write(agent_operation(manifest['operation']) / 'manifest.json', agent_encode(manifest))
    return agent_manifest(manifest['operation'])


def agent_probe_records(manifest, records, java_home):
    manifest['action'] = 'PROBE'
    manifest['request'] = {'probes': [{key: record[key] for key in ('ecosystem', 'version', 'directory')} for record in records], 'javaHome': java_home}
    agent_execute(agent_publish_manifest(manifest))


def agent_release_source(manifest, url, algorithm, expected, layout):
    input_root = agent_operation(manifest['operation']) / 'mutable/input'
    archive = input_root / 'release.archive'
    sha256 = download(official_url(url), archive, algorithm, expected)
    unpacked = input_root / 'unpacked'
    unpacked.mkdir()
    extract(archive, unpacked)
    source = unpacked if layout == 'flat' else next(iter(unpacked.iterdir()))
    if layout != 'flat' and (not source.is_dir() or source.is_symlink() or len(list(unpacked.iterdir())) != 1):
        agent_reject('OFFICIAL_ARCHIVE_LAYOUT')
    # The root safe-extractor validates link chains; no downstream code is executed here.
    os.rename(str(source), str(input_root / 'release'))
    for directory, dirs, files in os.walk(input_root / 'release', followlinks=False):
        os.chown(directory, 0, 0)
        os.chmod(directory, 0o555)
        for name in files:
            path = pathlib.Path(directory) / name
            os.chown(path, 0, 0, follow_symlinks=False)
            if not path.is_symlink():
                path.chmod(0o555 if path.stat().st_mode & 0o111 else 0o444)
    return sha256


def agent_system(ecosystem, version):
    if ecosystem == 'DOTNET':
        return None
    launcher = shutil.which(AGENT_BINARIES[ecosystem][0], path='/usr/local/bin:/usr/bin:/bin')
    if launcher is None:
        return None
    executable = pathlib.Path(launcher).resolve()
    home = executable.parent.parent
    owned_directory(home)
    files = []
    for name in AGENT_BINARIES[ecosystem]:
        selected = shutil.which(name, path=str(home / 'bin') + ':' + str(home) + ':/usr/bin')
        if selected is None:
            if name == AGENT_BINARIES[ecosystem][0] or ecosystem == 'JAVA' and name in ('javac', 'jar'):
                return None
            continue
        path = pathlib.Path(selected).resolve()
        owned_directory(path.parent)
        files.append({'name': name, 'path': str(path), 'sha256': agent_file_sha(path)})
    digest = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    key = hashlib.sha256((ecosystem + '\n' + version + '\nsystem\n' + digest).encode()).hexdigest()
    directory = ROOT / 'versions' / (ecosystem.lower() + '-' + key)
    if not (directory / '.w2l-toolchain.json').exists():
        return None
    record = agent_record(directory)
    if record['ecosystem'] != ecosystem or record['version'] != version or record['origin'] != 'SYSTEM' or record['sha256'] != digest or record.get('system_files') != files:
        agent_reject('SYSTEM_RECORD_MISMATCH')
    return record


def agent_detach(manifest):
    agent_stopped(manifest)
    base = agent_operation(manifest['operation'])
    agent_os([agent_tool('umount'), '--', str(base / 'mutable')], timeout=30)
    _, output = agent_os([agent_tool('findmnt'), '-rn', '-S', manifest['loop']], allow_failure=True, timeout=30)
    if output.strip():
        agent_reject('VOLUME_STILL_MOUNTED', 'unknown')
    agent_os([agent_tool('losetup'), '--detach', manifest['loop']], timeout=30)
    _, output = agent_os([agent_tool('losetup'), '-j', str(base / 'volume.ext4')], timeout=30)
    if output.strip():
        agent_reject('VOLUME_STILL_ATTACHED', 'unknown')
    # Retain the protected controller, manifest and receipts; remove only this confirmed detached image.
    (base / 'volume.ext4').unlink()


def agent_cleanup_before_start(manifest):
    base = agent_operation(manifest['operation'])
    if (base / 'start-intent.json').exists() or (base / 'start-intent.json').is_symlink():
        return False
    if agent_json(agent_regular(base / 'identity.json')) != {
            'operation': manifest['operation'], 'uid': 0, 'gid': 0,
            'programSha256': manifest['programSha256']}:
        agent_reject('PRESTART_IDENTITY_CHANGED', 'unknown')
    _, state = agent_os([agent_tool('systemctl'), 'show', '--value', '--property=LoadState',
                         agent_unit(manifest['operation'])], allow_failure=True, timeout=30)
    if state.strip() != b'not-found':
        agent_reject('PRESTART_UNIT_UNCONFIRMED', 'unknown')
    agent_volume(manifest)
    for name, identity in manifest['directories'].items():
        path = base / 'mutable' / name
        if agent_identity(path) != identity or list(path.iterdir()):
            agent_reject('PRESTART_DIRECTORY_CHANGED', 'unknown')
    agent_os([agent_tool('mount'), '-o', 'remount,ro,nodev,nosuid', '--', str(base / 'mutable')], timeout=30)
    agent_volume(manifest, require_ro=True)
    agent_os([agent_tool('umount'), '--', str(base / 'mutable')], timeout=30)
    _, mounts = agent_os([agent_tool('findmnt'), '-rn', '-S', manifest['loop']], allow_failure=True, timeout=30)
    if mounts.strip():
        agent_reject('PRESTART_VOLUME_STILL_MOUNTED', 'unknown')
    agent_os([agent_tool('losetup'), '--detach', manifest['loop']], timeout=30)
    _, attached = agent_os([agent_tool('losetup'), '-j', str(base / 'volume.ext4')], timeout=30)
    if attached.strip():
        agent_reject('PRESTART_VOLUME_STILL_ATTACHED', 'unknown')
    (base / 'volume.ext4').unlink()
    agent_write(base / 'prestart-cleaned.json', agent_encode({
        'operation': manifest['operation'], 'programSha256': manifest['programSha256']}))
    return True


def agent_cleanup_placeholder(manifest):
    path = agent_operation(manifest['operation']) / 'placeholder.json'
    if not path.exists() and not path.is_symlink():
        return
    record = agent_json(agent_regular(path))
    if not isinstance(record, dict) or set(record) != {'operation', 'ecosystem', 'version', 'directory', 'identity'} or record['operation'] != manifest['operation']:
        agent_reject('PLACEHOLDER_BINDING', 'unknown')
    agent_selection(record['ecosystem'], record['version'], record['directory'])
    destination = pathlib.Path(record['directory'])
    if agent_identity(destination) != record['identity'] or list(destination.iterdir()):
        agent_reject('PLACEHOLDER_CHANGED', 'unknown')
    destination.rmdir()


def agent_prepare(args):
    ecosystem, branch, exact, seconds, java_home, operation, encoded_release = args
    seconds = int(seconds)
    if ecosystem not in AGENT_ECOSYSTEMS or not re.fullmatch(r'\d{1,3}(?:\.\d{1,3})?', branch) or branch not in SHIPPED_CATALOG.get(ecosystem, []) or not 30 <= seconds <= 7200 or not agent_java_home(java_home):
        agent_reject('PREPARE_ARGUMENTS')
    if exact != '-' and (not re.fullmatch('[0-9][A-Za-z0-9._+-]{0,95}', exact) or not version_key(exact)):
        agent_reject('EXACT_VERSION')
    if java_home != '-' and agent_record(pathlib.Path(java_home))['ecosystem'] != 'JAVA':
        agent_reject('JAVA_HOME_RECORD')
    selected = resolution_decode(encoded_release, ecosystem, branch, exact, operation, EXPECTED_RESOLVER_SOURCE_SHA256)
    version, url, algorithm, expected, layout = (selected[key] for key in ('version', 'url', 'algorithm', 'digest', 'layout'))
    manifest = agent_create(operation, seconds)
    try:
        agent_prepare_selected(manifest, ecosystem, version, url, algorithm, expected, layout, java_home)
    except (PreparationFailure, OSError, ValueError) as error:
        if agent_cleanup_before_start(manifest):
            agent_cleanup_placeholder(manifest)
        elif isinstance(error, PreparationFailure) and error.category not in ('unknown', 'timeout'):
            # A failed ordinary worker is disposable only after the same stop/freeze/loop proof.
            agent_detach(manifest)
            agent_cleanup_placeholder(manifest)
        raise


def agent_prepare_selected(manifest, ecosystem, version, url, algorithm, expected, layout, java_home):
    operation = manifest['operation']
    detached = False
    system = agent_system(ecosystem, version)
    if system is not None:
        record = system
        directory = pathlib.Path(record['directory'])
        agent_probe_records(manifest, [record], java_home)
    else:
        key = agent_installation_key(ecosystem, version, expected)
        directory = ROOT / 'versions' / (ecosystem.lower() + '-' + key)
        if directory.exists() or directory.is_symlink():
            record = agent_record(directory)
            if record['ecosystem'] != ecosystem or record['version'] != version or record['origin'] != 'MANAGED' or record['source'] != url or record.get('official_algorithm') != algorithm or record.get('official_digest') != expected or record['directory'] != str(directory) or (algorithm == 'sha256' and record['sha256'] != expected):
                agent_reject('EXISTING_INSTALLATION_MISMATCH')
            agent_probe_records(manifest, [record], java_home)
        else:
            sha256 = agent_release_source(manifest, url, algorithm, expected, layout)
            legacy = ecosystem == 'RUBY' and version_key(version)[:2] == (3, 0)
            dependencies = {}
            if legacy:
                gem_digest = 'e24fcd69f6e0bac1e1c3cb8667d0ded5c1f6c59d010ecb5c857a771c9cb7565c'
                gem_url = 'https://rubygems.org/downloads/openssl-3.0.3.gem'
                download(gem_url, agent_operation(operation) / 'mutable/input/openssl-3.0.3.gem', 'sha256', gem_digest)
                dependencies['openssl'] = {'version': '3.0.3', 'source': gem_url, 'sha256': gem_digest}
            directory.mkdir(mode=0o755)
            original = agent_identity(directory)
            agent_write(agent_operation(operation) / 'placeholder.json', agent_encode({
                'operation': operation, 'ecosystem': ecosystem, 'version': version,
                'directory': str(directory), 'identity': original}))
            manifest['action'] = 'INSTALL'
            manifest['request'] = {'ecosystem': ecosystem, 'version': version, 'destination': str(directory), 'layout': layout,
                                   'sourceName': 'release', 'javaHome': java_home, 'legacyGem': legacy}
            agent_execute(agent_publish_manifest(manifest))
            record = {'ecosystem': ecosystem, 'version': version, 'directory': str(directory), 'origin': 'MANAGED', 'source': url,
                      'sha256': sha256, 'official_algorithm': algorithm, 'official_digest': expected, 'dependencies': dependencies}
            agent_promote(manifest, directory, original, record)
            detached = True
    if not detached:
        agent_detach(manifest)
    print('TOOLCHAIN=' + '|'.join(record[key] for key in ('ecosystem', 'version', 'directory', 'origin', 'source', 'sha256')))


def agent_bind(args):
    encoded, seconds, operation = args
    seconds = int(seconds)
    if not 30 <= seconds <= 7200:
        agent_reject('BIND_TIMEOUT')
    payload = base64.b64decode(encoded, validate=True)
    if len(payload) > 65536 or base64.b64encode(payload).decode() != encoded or any(byte < 32 and byte not in (9, 10) or byte > 126 for byte in payload):
        agent_reject('CANONICAL_BINDING')
    rows = payload.decode().splitlines()
    if not payload.endswith(b'\n') or not 2 <= len(rows) <= 33 or not re.fullmatch(r'WTL-TOOLS-1\t[A-Za-z0-9._-]{1,96}', rows[0]):
        agent_reject('BINDING_FORMAT')
    records, java_home = [], '-'
    for row in rows[1:]:
        values = row.split('\t')
        if len(values) != 11 or values[8] not in ('MANAGED', 'SYSTEM') or not agent_hash(values[10]):
            agent_reject('BINDING_FIELDS')
        agent_selection(values[0], values[6], values[7])
        branch_count = 1 if values[0] in ('JAVA', 'NODE', 'DOTNET') else 2
        branch = '.'.join(str(v) for v in version_key(values[6])[:branch_count])
        if branch not in SHIPPED_CATALOG.get(values[0], []):
            agent_reject('BINDING_CATALOG')
        record = agent_record(pathlib.Path(values[7]))
        if [record[key] for key in ('ecosystem', 'version', 'directory', 'origin', 'source', 'sha256')] != [
                values[0], values[6], values[7], values[8], base64.b64decode(values[9], validate=True).decode(), values[10]]:
            agent_reject('BINDING_RECORD_MISMATCH')
        if record['ecosystem'] == 'JAVA':
            java_home = record['directory']
        records.append(record)
    manifest = agent_create(operation, seconds)
    agent_probe_records(manifest, records, java_home)
    identity = hashlib.sha256(payload).hexdigest()
    target = ROOT / 'bindings' / identity
    agent_detach(manifest)
    if target.exists() or target.is_symlink():
        if agent_regular(target) != payload:
            agent_reject('BINDING_COLLISION')
    else:
        agent_write(target, payload)
    print('TOOLCHAIN_BINDING=' + identity)


def agent_main(args):
    import fcntl
    global DEADLINE
    if len(args) == 2 and args[0] in ('setup', 'freeze', 'worker'):
        {'setup': agent_setup, 'freeze': agent_freeze, 'worker': agent_worker}[args[0]](args[1])
        return
    if os.geteuid() != 0:
        agent_reject('CONTROLLER_REQUIRES_ROOT')
    if len(args) == 8 and args[0] == 'prepare':
        seconds = int(args[4])
        resolution_decode(args[7], args[1], args[2], args[3], args[6], EXPECTED_RESOLVER_SOURCE_SHA256)
    elif len(args) == 4 and args[0] == 'bind':
        seconds = int(args[2])
    else:
        agent_reject('ARGUMENT_FRAME')
    if not 30 <= seconds <= 7200:
        agent_reject('ARGUMENT_TIMEOUT')
    DEADLINE = time.monotonic() + seconds
    os.umask(0o022)
    owned_directory(ROOT)
    ROOT.mkdir(mode=0o755, parents=True, exist_ok=True)
    lock = ROOT / '.prepare.lock'
    fd = os.open(str(lock), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or info.st_nlink != 1:
            agent_reject('PREPARATION_LOCK')
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                remaining()
                time.sleep(0.2)
        if args[0] == 'prepare':
            agent_prepare(args[1:])
        else:
            agent_bind(args[1:])
    finally:
        os.close(fd)


def agent_diagnostic_operation(args):
    if len(args) == 8 and args[0] == 'prepare':
        operation = args[6]
    elif len(args) == 4 and args[0] == 'bind':
        operation = args[3]
    elif len(args) == 2 and args[0] in ('setup', 'freeze', 'worker'):
        operation = args[1]
    else:
        return None
    return operation if isinstance(operation, str) and re.fullmatch('[a-f0-9]{32}', operation) else None


def agent_exception_diagnostic(error, args):
    operation = agent_diagnostic_operation(args)
    if operation is None:
        return None
    families = ((OSError, 'OSError'), (ValueError, 'ValueError'), (KeyError, 'KeyError'),
                (StopIteration, 'StopIteration'), (subprocess.TimeoutExpired, 'TimeoutExpired'))
    family = next((name for kind, name in families if isinstance(error, kind)), None)
    if family is None:
        return None
    function, line = 'entry', 0
    frame = error.__traceback__
    while frame is not None:
        name = frame.tb_frame.f_code.co_name
        if frame.tb_frame.f_globals is globals() and name in AGENT_DIAGNOSTIC_FUNCTIONS and 1 <= frame.tb_lineno <= 16384:
            function, line = name, frame.tb_lineno
        frame = frame.tb_next
    number = error.errno if isinstance(error, OSError) and type(error.errno) is int and 1 <= error.errno <= 4095 else None
    return 'TOOLCHAIN_DIAGNOSTIC=' + json.dumps({'operation': operation, 'phase': 'AGENT_TOOLCHAIN_STATE_UNCONFIRMED',
        'function': function, 'exception': family, 'errno': number, 'line': line}, sort_keys=True, separators=(',', ':'))


def agent_space_diagnostic(error, args):
    if not (len(args) == 8 and args[0] == 'prepare' or len(args) == 4 and args[0] == 'bind'):
        return None
    operation = agent_diagnostic_operation(args)
    value = getattr(error, 'diagnostic', None)
    if operation is None or not isinstance(value, dict):
        return None
    if set(value) != {'operation', 'phase', 'stage', 'availableBytes', 'requiredBytes', 'reserveBytes'}:
        return None
    if value['operation'] != operation or value['phase'] != 'HOST_SPACE_REQUIRED' or value['stage'] not in ('VOLUME', 'PROMOTION'):
        return None
    if any(type(value[name]) is not int for name in ('availableBytes', 'requiredBytes', 'reserveBytes')):
        return None
    if value['reserveBytes'] != AGENT_HOST_RESERVE_BYTES or not 0 <= value['availableBytes'] < value['requiredBytes'] <= 1024 ** 4:
        return None
    if value['stage'] == 'VOLUME' and value['requiredBytes'] != AGENT_VOLUME_BYTES + AGENT_HOST_RESERVE_BYTES or value['requiredBytes'] < AGENT_HOST_RESERVE_BYTES:
        return None
    return 'TOOLCHAIN_DIAGNOSTIC=' + json.dumps(value, sort_keys=True, separators=(',', ':'))


def agent_command_diagnostic(error, args):
    operation = agent_diagnostic_operation(args)
    value = getattr(error, 'command_diagnostic', None)
    if operation is None or not isinstance(value, dict) or set(value) != {'command', 'exitCode', 'outputBytes', 'outputSha256'}:
        return None
    if not isinstance(value['command'], str) or value['command'] not in AGENT_SYSTEM_COMMANDS:
        return None
    if type(value['exitCode']) is not int or not -255 <= value['exitCode'] <= 255 or value['exitCode'] == 0:
        return None
    if type(value['outputBytes']) is not int or not 0 <= value['outputBytes'] <= 65536:
        return None
    if not isinstance(value['outputSha256'], str) or not re.fullmatch('[a-f0-9]{64}', value['outputSha256']):
        return None
    return 'TOOLCHAIN_DIAGNOSTIC=' + json.dumps({'operation': operation, 'phase': 'SYSTEM_COMMAND_FAILED', **value}, sort_keys=True, separators=(',', ':'))


def agent_cli(args):
    try:
        agent_main(args)
        return 0
    except PreparationFailure as error:
        category = error.category if error.category in ('isolation', 'integrity', 'ownership', 'metadata', 'network', 'archive', 'installation', 'probe', 'timeout', 'unknown') else 'isolation'
        detail = error.detail if re.fullmatch('[A-Z_]{1,96}', error.detail) else 'AGENT_TOOLCHAIN_REJECTED'
        print('TOOLCHAIN_FAILURE=' + category + ':' + detail)
        if detail == 'HOST_SPACE_REQUIRED':
            diagnostic = agent_space_diagnostic(error, args)
            if diagnostic is not None:
                print(diagnostic)
        elif detail == 'SYSTEM_COMMAND_FAILED':
            diagnostic = agent_command_diagnostic(error, args)
            if diagnostic is not None:
                print(diagnostic)
        return 70 if category == 'unknown' else 65
    except (OSError, ValueError, KeyError, StopIteration, subprocess.TimeoutExpired) as error:
        print('TOOLCHAIN_FAILURE=unknown:AGENT_TOOLCHAIN_STATE_UNCONFIRMED')
        diagnostic = agent_exception_diagnostic(error, args)
        if diagnostic is not None:
            print(diagnostic)
        return 70


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: agent_reject('CANCELLED', 'unknown'))
    signal.signal(signal.SIGHUP, lambda *_: agent_reject('SESSION_ENDED', 'unknown'))
    sys.exit(agent_cli(sys.argv[1:]))
