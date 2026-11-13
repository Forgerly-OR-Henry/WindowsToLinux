RUBY_RECIPE = 'ruby-dynamic-user-build-v1'
RUBY_DYNAMIC_MIN, RUBY_DYNAMIC_MAX = 61184, 65519
RUBY_BUILD_BYTES = 1024 * 1024 * 1024
RUBY_BUILD_ENTRIES = 40000
RUBY_OPENSSL_DIGEST = 'e24fcd69f6e0bac1e1c3cb8667d0ded5c1f6c59d010ecb5c857a771c9cb7565c'
# These worker-owned exit statuses are below systemd's execution setup status range.
RUBY_WORKER_FAILURES = {
    90: ('dynamic-identity', 'python3'),
    91: ('private-namespaces', 'python3'),
    92: ('privilege-restrictions', 'python3'),
    93: ('read-only-mounts', 'python3'),
    94: ('unix-socket-guard', 'python3'),
    95: ('staging-mount', 'python3'),
    96: ('archive-layout', 'python3'),
    97: ('source-extract', 'tar'),
    98: ('source-configure', 'configure'),
    99: ('source-build', 'make'),
    100: ('source-install', 'make'),
    101: ('legacy-gem-install', 'ruby'),
    102: ('prefix-verification', 'ruby'),
    103: ('runtime-verification', 'ruby'),
}
RUBY_CONFIGURE_FAILURES = {
    104: 'start-permission-denied',
    105: 'start-path-or-interpreter-missing',
    106: 'start-executable-format',
    107: 'start-os-error',
    108: 'c-compiler-missing',
    109: 'c-compiler-cannot-link',
    110: 'compiled-program-cannot-run',
    111: 'configure-signalled',
}
RUBY_MANAGER_RESULTS = frozenset(('success', 'resources', 'protocol', 'timeout', 'exit-code', 'signal',
                                  'core-dump', 'watchdog', 'start-limit-hit', 'oom-kill', 'skip-condition'))
RUBY_WORKER = r'''
import errno
import os
import pathlib
import socket
import subprocess
import sys
import tempfile

environment = {"PATH": "/usr/bin:/bin", "HOME": "/tmp", "TMPDIR": "/tmp", "LC_ALL": "C"}

def configure(release, prefix, options):
    # Classify failures inside the unprivileged unit. No configure or config.log text leaves it.
    with tempfile.TemporaryFile(dir="/tmp") as output:
        try:
            subprocess.run((str(release / "configure"), "--prefix=" + prefix, "--disable-install-doc", *options),
                           cwd=release, env=environment, stdin=subprocess.DEVNULL,
                           stdout=output, stderr=subprocess.STDOUT, check=True)
        except OSError as error:
            sys.exit({errno.EACCES: 104, errno.EPERM: 104, errno.ENOENT: 105, errno.ENOEXEC: 106}.get(error.errno, 107))
        except subprocess.CalledProcessError as error:
            if error.returncode < 0:
                sys.exit(111)
            output.seek(max(0, os.fstat(output.fileno()).st_size - 16384))
            messages = {
                b"configure: error: no acceptable C compiler found in $PATH": 108,
                b"configure: error: C compiler cannot create executables": 109,
                b"configure: error: cannot run C compiled programs.": 110,
            }
            for line in reversed(output.read(16384).splitlines()):
                if line in messages:
                    sys.exit(messages[line])
            raise

failure = 90
try:
    mode, network, mount, prefix, version, *arguments = sys.argv[1:]
    if not all(61184 <= identity <= 65519 for identity in (os.geteuid(), os.getegid())):
        sys.exit(failure)
    failure = 91
    if os.readlink("/proc/self/ns/net") == network or os.readlink("/proc/self/ns/mnt") == mount:
        sys.exit(failure)
    failure = 92
    with open("/proc/self/status") as status:
        properties = dict(line.split(":", 1) for line in status if ":" in line)
    if (properties["NoNewPrivs"].strip() != "1" or properties["Seccomp"].strip() != "2" or
            any(int(properties[key].strip(), 16) for key in ("CapEff", "CapPrm", "CapBnd"))):
        sys.exit(failure)
    failure = 93
    if any(not os.statvfs(path).f_flag & os.ST_RDONLY for path in ("/usr", "/etc", "/run")):
        sys.exit(failure)
    failure = 94
    try:
        local_socket = socket.socket(socket.AF_UNIX)
    except OSError:
        pass
    else:
        local_socket.close()
        sys.exit(failure)

    def command(*argv, cwd=None):
        subprocess.run(argv, cwd=cwd, env=environment, stdin=subprocess.DEVNULL, check=True)

    ruby = prefix + "/bin/ruby"
    if mode == "build":
        failure = 95
        device, inode, legacy = arguments
        mounted = os.stat(prefix)
        if (str(mounted.st_dev), str(mounted.st_ino)) != (device, inode):
            sys.exit(failure)
        source = pathlib.Path("/tmp/wtl-ruby-source")
        source.mkdir(mode=0o700)
        failure = 97
        command("/usr/bin/tar", "--extract", "--no-same-owner", "--no-same-permissions",
                "--file", "/tmp/wtl-ruby-release", "--directory", str(source))
        failure = 96
        release = source / ("ruby-" + version)
        if list(source.iterdir()) != [release] or release.is_symlink() or not release.is_dir():
            sys.exit(failure)
        options = ["--with-out-ext=openssl"] if legacy == "legacy" else []
        failure = 98
        configure(release, prefix, options)
        failure = 99
        command("/usr/bin/make", "-j2", cwd=release)
        failure = 100
        command("/usr/bin/make", "install", cwd=release)
        if legacy == "legacy":
            failure = 101
            command(ruby, prefix + "/bin/gem", "install", "--local", "/tmp/openssl-3.0.3.gem",
                    "--no-document", "--ignore-dependencies", cwd=release)
        failure = 102
        command(ruby, "-r", "rbconfig", "-e", "abort unless RbConfig::CONFIG.fetch('prefix') == ARGV.fetch(0)", prefix)

    failure = 103
    command(ruby, "-e", "abort unless RUBY_VERSION == ARGV.fetch(0); require 'openssl'; require 'zlib'; require 'yaml'", version)
except (OSError, KeyError, ValueError, subprocess.CalledProcessError):
    sys.exit(failure)
'''


def ruby_system_command(executable, *arguments):
    return ['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin', 'LC_ALL=C', '/usr/bin/' + executable, *arguments]


def ruby_unit_name(operation_id, role):
    return 'wtl-ruby-' + role + '-' + uuid.UUID(operation_id).hex + '.service'


def ruby_require_settled(unit):
    result = subprocess.run(
        ruby_system_command('systemctl', 'show', '--all', '--no-pager',
                            '--property=LoadState,ActiveState,MainPID,ControlPID,ControlGroup,Job', unit),
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10,
    )
    properties = dict(line.split('=', 1) for line in result.stdout.decode('ascii').splitlines() if '=' in line)
    group = '/system.slice/' + unit
    unloaded = properties.get('LoadState') == 'not-found' and properties.get('ActiveState') == 'inactive'
    stopped = (properties.get('LoadState') == 'loaded' and properties.get('ActiveState') in ('inactive', 'failed') and
               properties.get('MainPID') == '0' and properties.get('ControlPID') == '0')
    if (result.returncode or not (unloaded or stopped) or
            properties.get('MainPID', '0') != '0' or properties.get('ControlPID', '0') != '0' or
            properties.get('ControlGroup', '') not in ('', group) or properties.get('Job', '') not in ('', '0')):
        fail('interrupted', 'the original Ruby sandbox unit is not confirmed stopped')
    for hierarchy in ('/sys/fs/cgroup', '/sys/fs/cgroup/systemd'):
        try:
            with (pathlib.Path(hierarchy + group) / 'cgroup.procs').open() as processes:
                if processes.read(1):
                    fail('interrupted', 'the original Ruby sandbox still has processes')
        except FileNotFoundError:
            continue


def ruby_mount_path(path):
    if (not re.fullmatch(r'/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*', str(path)) or
            any(part in ('.', '..') for part in str(path).split('/'))):
        fail('ownership', 'Ruby sandbox requires an absolute literal mount path')
    return str(path)


def ruby_manager_terminal(command):
    results, exits = [], []
    for line in (command['stdout'] + '\n' + command['stderr']).splitlines():
        result = re.fullmatch(r'Finished with result: ([a-z-]{1,32})', line)
        if result and result[1] in RUBY_MANAGER_RESULTS:
            results.append(result[1])
        status = re.fullmatch(r'Main processes terminated with: code=(exited|killed|dumped)/status=([0-9]{1,3}|[A-Z0-9+_-]{1,16})', line)
        if status:
            code, value = status.groups()
            if code != 'exited' or value.isdecimal() and int(value) <= 255:
                exits.append((code, value))
    result = results[0] if len(results) == 1 else 'unobserved'
    code, value = exits[0] if len(exits) == 1 else ('unobserved', 'unobserved')
    return result, code, value


def ruby_diagnostic(command, role):
    result, code, value = ruby_manager_terminal(command)
    exit_code = command['actualExitCode']
    failure = RUBY_WORKER_FAILURES.get(exit_code)
    configure_failure = RUBY_CONFIGURE_FAILURES.get(exit_code) if role == 'build' else None
    if configure_failure is not None:
        failure = RUBY_WORKER_FAILURES[98]
    worker = (result == 'exit-code' and code == 'exited' and value == str(exit_code) and failure is not None
              and not (command['timedOut'] or command['cancelled']))
    stage = 'ruby-' + role + '-launcher'
    details = ['unit-role=' + role, 'manager-result=' + result, 'manager-main-code=' + code, 'manager-main-status=' + value]
    if worker:
        phase, child = failure
        stage = 'ruby-' + role + '-' + phase
        details.extend(('worker-stage=' + phase, 'worker-executable=' + child))
        if configure_failure is not None:
            details.append('configure-failure=' + configure_failure)
    return {**command, 'stage': stage, 'executable': 'systemd-run',
            'stdout': '\n'.join(details) + '\n', 'stderr': ''}


def ruby_run(command, role):
    # The synchronous shared runner captures the launcher's streams. The worker's streams stay separate.
    global record_installation_diagnostic
    recorder = record_installation_diagnostic

    def record_ruby_observation(observation):
        recorder(ruby_diagnostic(observation, role))

    record_installation_diagnostic = record_ruby_observation
    try:
        run(command)
    finally:
        record_installation_diagnostic = recorder


def ruby_unit(unit, directory, version, build_mounts=None):
    import grp
    import pwd

    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        fail('metadata', 'invalid exact Ruby release')
    directory = ruby_mount_path(directory)
    seconds = remaining() if build_mounts else min(60, remaining())
    user = 'wr' + unit.split('-')[-1][:28]
    for lookup in (pwd.getpwnam, grp.getgrnam):
        try:
            lookup(user)
        except KeyError:
            continue
        fail('ownership', 'Ruby sandbox identity is already allocated')
    properties = [
        'User=' + user, 'DynamicUser=yes', 'Type=exec',
        'ProtectSystem=strict', 'ProtectHome=tmpfs', 'PrivateTmp=yes', 'PrivateDevices=yes', 'PrivateNetwork=yes',
        'TemporaryFileSystem=/run:ro /var:ro', 'NoNewPrivileges=yes', 'CapabilityBoundingSet=',
        'AmbientCapabilities=', 'RestrictSUIDSGID=yes', 'RestrictNamespaces=yes', 'ProtectControlGroups=yes',
        'ProtectKernelTunables=yes', 'ProtectKernelModules=yes', 'ProtectKernelLogs=yes', 'ProtectClock=yes',
        'RestrictAddressFamilies=AF_INET AF_INET6 AF_NETLINK',
        'SystemCallFilter=~@mount @privileged @obsolete io_uring_setup io_uring_enter io_uring_register',
        'SystemCallErrorNumber=EPERM', 'SystemCallArchitectures=native',
        'UnsetEnvironment=LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT GLIBC_TUNABLES',
        'KillMode=control-group', 'TimeoutStartSec=15s', 'TimeoutStopSec=5s', 'RuntimeMaxSec=' + str(seconds) + 's',
        'TasksMax=128', 'LimitFSIZE=268435456', 'LimitCORE=0', 'UMask=0022',
        'StandardOutput=null', 'StandardError=null',
    ]
    worker = ['/usr/bin/python3', '-I', '-S', '-B', '-c', RUBY_WORKER]
    worker.extend(['build' if build_mounts else 'probe', os.readlink('/proc/self/ns/net'),
                   os.readlink('/proc/self/ns/mnt'), directory, version])
    if build_mounts:
        stage, archive, gem = build_mounts
        staged = stage.lstat()
        sources = [ruby_mount_path(archive) + ':/tmp/wtl-ruby-release']
        if gem is not None:
            sources.append(ruby_mount_path(gem) + ':/tmp/openssl-3.0.3.gem')
        properties.extend([
            'BindReadOnlyPaths=' + ' '.join(sources), 'BindPaths=' + ruby_mount_path(stage) + ':' + directory,
            'ReadWritePaths=' + directory,
        ])
        worker.extend([str(staged.st_dev), str(staged.st_ino), 'legacy' if gem is not None else 'standard'])
    elif directory.startswith(('/var/', '/home/', '/root/')):
        properties.append('BindReadOnlyPaths=' + directory)
    command = ruby_system_command('systemd-run', '--wait', '--collect', '--slice=system.slice', '--unit=' + unit)
    command.extend('--property=' + value for value in properties)
    try:
        ruby_run(command + ['--'] + worker, 'build' if build_mounts else 'probe')
    except (PreparationFailure, subprocess.TimeoutExpired, OSError):
        subprocess.run(
            ruby_system_command('systemctl', 'stop', '--no-pager', unit), stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15,
        )
        raise
    finally:
        ruby_require_settled(unit)


def ruby_probe(directory, version):
    operation = globals().get('TRACKED_OPERATION')
    if operation is None:
        ruby_unit(ruby_unit_name(str(uuid.uuid4()), 'probe'), directory, version)
        return
    marker = operation.directory / 'ruby-probe'
    if marker.exists() or marker.is_symlink():
        fail('interrupted', 'an earlier Ruby probe still requires observation')
    identity = str(uuid.uuid4())
    unit = ruby_unit_name(identity, 'probe')
    tracked_atomic_bytes(marker, identity.encode('ascii'))
    try:
        ruby_unit(unit, directory, version)
    finally:
        ruby_require_settled(unit)
        marker.unlink()
        tracked_sync_directory(operation.directory)


def ruby_link_target(path, links):
    resolved, pending, hops = [], list(path), 0
    while pending:
        part = pending.pop(0)
        if part in ('', '.'):
            continue
        if part == '..':
            if not resolved:
                fail('archive', 'Ruby staging link leaves its installation')
            resolved.pop()
            continue
        link = links.get(tuple(resolved + [part]))
        if link is None:
            resolved.append(part)
        else:
            hops += 1
            if hops > 40:
                fail('archive', 'Ruby staging link is cyclic')
            pending = link.split('/') + pending
    return tuple(resolved)


def ruby_publish(stage, destination):
    # The unit is quiescent. No worker-controlled file is opened through a symlink or as a device.
    links, entries = {}, set()
    total = 0
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW

    def copy(source, target, parts):
        if len(parts) > 24:
            fail('archive', 'Ruby staging tree is too deep')
        with os.scandir(source) as listing:
            for entry in listing:
                copy_entry(source, target, parts, entry.name)
        target.chmod(0o755)

    def copy_entry(source, target, parts, name):
        nonlocal total
        path = parts + (name,)
        if len(entries) >= RUBY_BUILD_ENTRIES or name.startswith('.w2l-') or '\\' in name or len('/'.join(path)) > 4096:
            fail('archive', 'invalid Ruby staging path or entry limit')
        entries.add(path)
        info = os.stat(name, dir_fd=source, follow_symlinks=False)
        if (not RUBY_DYNAMIC_MIN <= info.st_uid <= RUBY_DYNAMIC_MAX or
                info.st_mode & (stat.S_ISUID | stat.S_ISGID | stat.S_ISVTX)):
            fail('ownership', 'invalid Ruby staging ownership or mode')
        output = target / name
        if stat.S_ISDIR(info.st_mode):
            descriptor = os.open(name, directory_flags, dir_fd=source)
            try:
                if not os.path.samestat(info, os.fstat(descriptor)):
                    fail('integrity', 'Ruby staging directory changed')
                output.mkdir(mode=0o755)
                copy(descriptor, output, path)
            finally:
                os.close(descriptor)
        elif stat.S_ISREG(info.st_mode):
            total += info.st_size
            if total > RUBY_BUILD_BYTES or info.st_nlink != 1:
                fail('archive', 'Ruby staging file exceeds its bound or has another link')
            descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=source)
            with os.fdopen(descriptor, 'rb') as data:
                current = os.fstat(data.fileno())
                if not os.path.samestat(info, current) or info.st_size != current.st_size or current.st_nlink != 1:
                    fail('integrity', 'Ruby staging file changed')
                with output.open('xb') as published:
                    remaining_bytes = info.st_size
                    while remaining_bytes:
                        payload = data.read(min(1024 * 1024, remaining_bytes))
                        if not payload:
                            fail('integrity', 'Ruby staging file shrank')
                        published.write(payload)
                        remaining_bytes -= len(payload)
                    if data.read(1):
                        fail('integrity', 'Ruby staging file grew')
                    os.fchmod(published.fileno(), 0o755 if info.st_mode & 0o111 else 0o644)
                    published.flush()
                    os.fsync(published.fileno())
        elif stat.S_ISLNK(info.st_mode):
            link = os.readlink(name, dir_fd=source)
            if not link or link.startswith('/') or '\\' in link or len(link) > 4096:
                fail('archive', 'invalid Ruby staging link')
            links[path] = link
        else:
            fail('archive', 'invalid Ruby staging object type')

    descriptor = os.open(stage, directory_flags)
    try:
        copy(descriptor, destination, ())
    finally:
        os.close(descriptor)
    for path, link in links.items():
        if ruby_link_target(path, links) not in entries:
            fail('archive', 'Ruby staging link has no installed target')
        destination.joinpath(*path).symlink_to(link)
    for directory, _, _ in os.walk(destination, topdown=False, followlinks=False):
        tracked_sync_directory(pathlib.Path(directory))
    ruby = destination / 'bin/ruby'
    if ruby.is_symlink() or not ruby.is_file() or not ruby.stat().st_mode & 0o111:
        fail('probe', 'Ruby staging did not produce its executable')


def ruby_source_install(identity, url, algorithm, expected, layout):
    global MIRROR_POINT
    if (layout != 'ruby' or algorithm != 'sha256' or not re.fullmatch('[a-f0-9]{64}', expected) or
            not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', identity)):
        fail('metadata', 'invalid official Ruby source metadata')
    MIRROR_POINT = toolchain_point('RUBY')
    destination = ROOT / 'versions' / ('ruby-' + installation_key('RUBY', identity, expected))
    marker = saved_installation_marker(destination)
    if marker is not None:
        record = json.loads(tracked_regular(marker, 65536))
        if (not isinstance(record, dict) or any(record.get(key) != value for key, value in (
                ('ecosystem', 'RUBY'), ('origin', 'MANAGED'), ('recipe', RUBY_RECIPE), ('version', identity),
                ('directory', str(destination)), ('source', url), ('sha256', expected),
                ('official_algorithm', algorithm), ('official_digest', expected)))):
            fail('integrity', 'existing Ruby installation does not match its source recipe')
        ruby_probe(destination, identity)
        return record

    tracked = globals().get('TRACKED_OPERATION')
    operation_id = tracked.request['operationId'] if tracked is not None else str(uuid.uuid4())
    unit = ruby_unit_name(operation_id, 'build')
    if tracked is not None:
        work = tracked.directory / 'work'
        work.mkdir(mode=0o700)
        tracked_sync_directory(tracked.directory)
    else:
        work_parent = ROOT / 'work'
        owned_directory(work_parent)
        work_parent.mkdir(mode=0o700, exist_ok=True)
        work = pathlib.Path(tempfile.mkdtemp(prefix='ruby-', dir=work_parent))
    sandbox_started, sandbox_settled = False, False
    try:
        archive = work / 'release.archive'
        sha256 = download(url, archive, algorithm, expected)
        archive.chmod(0o444)
        if tracked is not None:
            tracked.archive_downloaded(sha256)
        gem, dependencies = None, {}
        if version_key(identity)[:2] == (3, 0):
            gem = work / 'openssl-3.0.3.gem'
            gem_url = source_url('RUBYGEMS', 'downloads/' + gem.name)
            download(gem_url, gem, 'sha256', RUBY_OPENSSL_DIGEST)
            gem.chmod(0o444)
            dependencies['openssl'] = {'version': '3.0.3', 'source': gem_url, 'sha256': RUBY_OPENSSL_DIGEST}
        source_dependencies('ruby')
        stage = work / 'stage'
        stage.mkdir()
        # Only the unit can reach this write mount. Every host ancestor to the work directory is protected.
        stage.chmod(0o777)
        with create_installation_directory(destination, tracked):
            sandbox_started = True
            ruby_unit(unit, destination, identity, (stage, archive, gem))
            sandbox_settled = True
            ruby_publish(stage, destination)
            ruby_probe(destination, identity)
            record = {
                'ecosystem': 'RUBY', 'version': identity, 'directory': str(destination), 'origin': 'MANAGED',
                'source': url, 'sha256': sha256, 'official_algorithm': algorithm, 'official_digest': expected,
                'dependencies': dependencies, 'recipe': RUBY_RECIPE,
            }
            tracked_atomic_json(destination / '.w2l-toolchain.json', record, 0o444)
            return record
    finally:
        if sandbox_started and not sandbox_settled:
            ruby_require_settled(unit)
        shutil.rmtree(work)
        tracked_sync_directory(work.parent)


_previous_installation_key = installation_key
_previous_install = install
_previous_probe = probe
_previous_tracked_require_quiescent = tracked_require_quiescent
_previous_tracked_owned_residue = tracked_owned_residue
_previous_tracked_cleanup = tracked_cleanup


def install_key_with_ruby_recipe(ecosystem, identity, expected):
    if ecosystem == 'RUBY':
        return hashlib.sha256(('RUBY\n' + identity + '\n' + expected + '\n' + RUBY_RECIPE).encode()).hexdigest()
    return _previous_installation_key(ecosystem, identity, expected)


def install_with_ruby_isolation(ecosystem, identity, url, algorithm, expected, layout):
    if ecosystem == 'RUBY':
        return ruby_source_install(identity, url, algorithm, expected, layout)
    return _previous_install(ecosystem, identity, url, algorithm, expected, layout)


def probe_with_ruby_isolation(ecosystem, directory, expected):
    if ecosystem == 'RUBY':
        return ruby_probe(directory, expected)
    return _previous_probe(ecosystem, directory, expected)


def tracked_require_quiescent_with_ruby(operation):
    _previous_tracked_require_quiescent(operation)
    if (operation.request['kind'] == 'PREPARE' and operation.request['parameters']['ecosystem'] == 'RUBY' and
            (operation.directory / 'work' / 'stage').is_dir()):
        ruby_require_settled(ruby_unit_name(operation.request['operationId'], 'build'))
    marker = operation.directory / 'ruby-probe'
    if marker.exists() or marker.is_symlink():
        identity = tracked_regular(marker, 36).decode('ascii')
        if not tracked_uuid(identity):
            fail('integrity', 'invalid tracked Ruby probe identity')
        ruby_require_settled(ruby_unit_name(identity, 'probe'))


def tracked_owned_residue_with_ruby(operation):
    marker = operation.directory / 'ruby-probe'
    return marker.exists() or marker.is_symlink() or _previous_tracked_owned_residue(operation)


def tracked_cleanup_with_ruby(operation):
    _previous_tracked_cleanup(operation)
    marker = operation.directory / 'ruby-probe'
    if marker.exists():
        marker.unlink()
        tracked_sync_directory(operation.directory)


installation_key = install_key_with_ruby_recipe
install = install_with_ruby_isolation
probe = probe_with_ruby_isolation
tracked_require_quiescent = tracked_require_quiescent_with_ruby
tracked_owned_residue = tracked_owned_residue_with_ruby
tracked_cleanup = tracked_cleanup_with_ruby
