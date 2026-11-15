RUBY_RECIPE = 'ruby-dynamic-user-build-v1'
RUBY_DYNAMIC_MIN, RUBY_DYNAMIC_MAX = 61184, 65519
RUBY_BUILD_BYTES = 1024 * 1024 * 1024
RUBY_BUILD_ENTRIES = 40000
RUBY_SCRATCH_BYTES = 1536 * 1024 * 1024
RUBY_SCRATCH_INODES = 96000
RUBY_MEMORY_BYTES = 3 * 1024 * 1024 * 1024
SOURCE_PROBE_BYTES = 16 * 1024 * 1024
SOURCE_PROBE_INODES = 256
SOURCE_PROBE_MEMORY = 256 * 1024 * 1024
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
    126: ('resource-boundary', 'python3'),
    127: ('scratch-write', 'python3'),
    128: ('scratch-link', 'python3'),
    129: ('scratch-execute', 'sh'),
}
RUBY_CONFIGURE_FAILURES = {
    70: 'config-log-operation-not-permitted-reported-by-conftest',
    71: 'config-log-operation-not-permitted-reported-by-compiler',
    72: 'config-log-operation-not-permitted-reported-by-linker',
    73: 'config-log-operation-not-permitted-reported-by-make',
    74: 'config-log-operation-not-permitted-reported-by-configure-shell',
    75: 'config-log-operation-not-permitted-reported-by-config-status',
    89: 'output-operation-not-permitted-reported-by-configure-shell',
    104: 'start-permission-denied',
    105: 'start-path-or-interpreter-missing',
    106: 'start-executable-format',
    107: 'start-os-error',
    108: 'c-compiler-missing',
    109: 'c-compiler-cannot-link',
    110: 'compiled-program-cannot-run',
    111: 'configure-signalled',
    130: 'output-permission-denied',
    131: 'output-operation-not-permitted',
    132: 'output-read-only-filesystem',
    133: 'output-storage-exhausted',
    134: 'configure-nonzero',
    135: 'diagnostic-pipe-error',
    165: 'output-operation-not-permitted-conftest',
    166: 'output-operation-not-permitted-config-status',
    167: 'output-operation-not-permitted-compiler',
    168: 'output-operation-not-permitted-linker',
    169: 'output-operation-not-permitted-copy',
    170: 'output-operation-not-permitted-rename',
    171: 'output-permission-hints-ambiguous',
    193: 'output-operation-not-permitted-reported-by-ln',
    194: 'output-operation-not-permitted-reported-by-mkdir',
    195: 'output-operation-not-permitted-reported-by-rm',
    196: 'output-operation-not-permitted-reported-by-chmod',
    197: 'output-operation-not-permitted-reported-by-install',
    199: 'output-operation-not-permitted-reported-by-make',
}
SOURCE_BUILD_PROBE_STAGES = (('c-compile', 'cc'), ('static-archive', 'ar'), ('c-link', 'cc'), ('native-execute', 'probe'))
SOURCE_BUILD_PROBE_FAILURES = ('start-permission-denied', 'start-not-found', 'start-os-error', 'child-nonzero', 'child-signal')
RUBY_MANAGER_RESULTS = frozenset(('success', 'resources', 'protocol', 'timeout', 'exit-code', 'signal',
                                  'core-dump', 'watchdog', 'start-limit-hit', 'oom-kill', 'skip-condition'))
SOURCE_WORKER = r'''
import errno
import os
import pathlib
import socket
import subprocess
import sys
import tempfile

environment = {"PATH": "/usr/bin:/bin", "HOME": "/tmp", "TMPDIR": "/tmp", "LC_ALL": "C"}

def require_isolated(network, mount):
    global failure
    failure = 90
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

def require_staging_mount(prefix, device, inode):
    mounted = os.stat(prefix)
    if (str(mounted.st_dev), str(mounted.st_ino)) != (device, inode):
        sys.exit(95)

def extract_release(name):
    source = pathlib.Path("/tmp/wtl-toolchain-source")
    source.mkdir(mode=0o700)
    command("/usr/bin/tar", "--extract", "--no-same-owner", "--no-same-permissions",
            "--file", "/tmp/wtl-toolchain-release", "--directory", str(source))
    release = source / name
    if list(source.iterdir()) != [release] or release.is_symlink() or not release.is_dir():
        sys.exit(96)
    return release

def require_ruby_resources(unit, device, inode, maximum_bytes, maximum_inodes, maximum_memory):
    group = "/system.slice/" + unit
    if pathlib.Path("/proc/self/cgroup").read_text(encoding="ascii").splitlines() != ["0::" + group]:
        sys.exit(126)
    control = pathlib.Path("/sys/fs/cgroup" + group)
    memory = (control / "memory.max").read_text(encoding="ascii").strip()
    swap = (control / "memory.swap.max").read_text(encoding="ascii").strip()
    if memory != maximum_memory or swap != "0":
        sys.exit(126)
    scratch = os.stat("/tmp")
    space = os.statvfs("/tmp")
    probe = device == "-"
    if probe and not probe_uses_private_tmpfs(scratch.st_dev):
        sys.exit(126)
    if ((not probe and (str(scratch.st_dev), str(scratch.st_ino)) != (device, inode))
            or not 0 < space.f_blocks * space.f_frsize <= int(maximum_bytes)
            or not 0 < space.f_files <= int(maximum_inodes)
            or (probe and not space.f_flag & os.ST_RDONLY)
            or any(not os.statvfs(path).f_flag & os.ST_RDONLY for path in ("/var/tmp", "/dev/shm"))):
        sys.exit(126)

def probe_uses_private_tmpfs(device):
    mount_device = str(os.major(device)) + ":" + str(os.minor(device))
    for line in pathlib.Path("/proc/self/mountinfo").read_text().splitlines():
        mount, _, filesystem = line.partition(" - ")
        fields = mount.split()
        if len(fields) >= 6 and fields[2:5] == [mount_device, "/", "/tmp"] and filesystem.startswith("tmpfs "):
            return True
    return False

def verify_build_scratch(release, prefix):
    global failure
    for directory in (release, prefix):
        failure = 127
        with tempfile.TemporaryDirectory(prefix=".wtl-check-", dir=directory) as temporary:
            executable = pathlib.Path(temporary) / "check"
            executable.write_bytes(b"#!/bin/sh\nexit 0\n")
            executable.chmod(0o700)
            failure = 128
            link = pathlib.Path(temporary) / "alias"
            os.link(executable, link)
            if not os.path.samestat(executable.stat(), link.stat()):
                sys.exit(failure)
            if directory == release:
                failure = 129
                command(str(link), cwd=directory)

def verify_native_build_chain(release):
    # All inputs, paths below scratch, and commands are constant. The unit's existing
    # resource and runtime bounds also apply to these short compiler and binutils calls.
    global failure
    failure = 127
    with tempfile.TemporaryDirectory(prefix=".wtl-native-check-", dir=release) as temporary:
        with (pathlib.Path(temporary) / "probe.c").open("xb") as source:
            source.write(b"int main(void) { return 0; }\n")
        commands = (("/usr/bin/cc", "-c", "probe.c", "-o", "probe.o"),
                    ("/usr/bin/ar", "crs", "probe.a", "probe.o"),
                    ("/usr/bin/cc", "probe.a", "-o", "probe"),
                    ("./probe",))
        for index, argv in enumerate(commands):
            start_error = 145 + 5 * index
            try:
                subprocess.run(argv, cwd=temporary, env=environment, stdin=subprocess.DEVNULL,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            except OSError as error:
                detail = {errno.EACCES: 0, errno.EPERM: 0, errno.ENOENT: 1}.get(error.errno, 2)
                sys.exit(start_error + detail)
            except subprocess.CalledProcessError as error:
                sys.exit(start_error + (4 if error.returncode < 0 else 3))

def permission_text(line):
    return line.lower().rstrip(b".").rsplit(b": ", 1)[-1]

def diagnostic_output_lines(stream):
    continued = False
    for raw in iter(lambda: stream.readline(4097), b""):
        complete = raw.endswith(b"\n")
        if not continued and complete and len(raw) <= 4096:
            yield raw.rstrip(b"\r\n")
        continued = not complete

def only_source_worker_in_cgroup(unit):
    with open("/sys/fs/cgroup/system.slice/" + unit + "/cgroup.procs", "rb") as processes:
        return processes.read(64) == (str(os.getpid()) + "\n").encode("ascii")
'''

RUBY_WORKER = SOURCE_WORKER + r'''
from contextlib import contextmanager
import io
import re
import stat
import time

RUBY_EPERM_REPORTERS = (
    (165, rb"^(?:(?:\./)?configure|(?:/(?:usr/)?bin/)?(?:ba|da)?sh): (?:line )?[0-9]+: (?:\.\./|\./)?conftest(?:\.exe)?: "),
    (166, rb"^(?:\./)?config\.status: "),
    (167, rb"^(?:[^: ]*/)?(?:gcc(?:-[0-9]+)?|cc|cc1|g\+\+|c\+\+): "),
    (168, rb"^(?:[^: ]*/)?(?:(?:[a-z0-9_]+-)*ld(?:\.bfd)?|collect2): "),
    (169, rb"^(?:[^: ]*/)?cp: "),
    (170, rb"^(?:[^: ]*/)?mv: "),
    (193, rb"^(?:/(?:usr/)?bin/)?ln: "),
    (194, rb"^(?:/(?:usr/)?bin/)?mkdir: "),
    (195, rb"^(?:/(?:usr/)?bin/)?rm: "),
    (196, rb"^(?:/(?:usr/)?bin/)?chmod: "),
    (197, rb"^(?:/(?:usr/)?bin/)?install: "),
    (199, rb"^(?:/(?:usr/)?bin/)?make(?:\[[0-9]+\])?: "),
    (89, rb"^(?:(?:\./)?configure|(?:/(?:usr/)?bin/)?(?:ba|da)?sh): "),
)

CONFIG_LOG_MAX_BYTES = 64 * 1024
CONFIG_LOG_REPORTERS = {165: 70, 167: 71, 168: 72, 199: 73, 89: 74, 166: 75}

def configure_eperm_reporter(line, configure_reporter):
    if line.startswith(configure_reporter):
        line = b"configure: " + line[len(configure_reporter):]
    return next((code for code, pattern in RUBY_EPERM_REPORTERS if re.match(pattern, line)), 131)

@contextmanager
def fresh_config_log_directory(release):
    descriptor = None
    fresh = None
    try:
        descriptor = os.open(release, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.stat("config.log", dir_fd=descriptor, follow_symlinks=False)
        except FileNotFoundError:
            fresh = descriptor
    except OSError:
        pass
    try:
        yield fresh
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass

def config_log_tail(directory):
    descriptor = os.open("config.log", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
    with os.fdopen(descriptor, "rb") as stream:
        before = os.fstat(descriptor)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
                or before.st_nlink != 1 or before.st_mode & 0o022):
            return b""
        offset = max(0, before.st_size - CONFIG_LOG_MAX_BYTES)
        stream.seek(offset)
        tail = stream.read(CONFIG_LOG_MAX_BYTES + 1)
        after = os.fstat(descriptor)
        current = os.stat("config.log", dir_fd=directory, follow_symlinks=False)
        identity = lambda info: (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_nlink,
                                 info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        if len(tail) != before.st_size - offset or identity(before) != identity(after) or identity(before) != identity(current):
            return b""
        return tail.partition(b"\n")[2] if offset else tail

def terminal_config_log_reporter(tail, child_exit, configure_reporter):
    expected_exit = b"configure: exit " + str(child_exit).encode("ascii")
    if not tail.endswith(b"\n") or tail.rsplit(b"\n", 2)[-2] != expected_exit:
        return 131
    if any(len(line) > 4096 for line in io.BytesIO(tail)):
        return 131
    reporters = set()
    checking = False
    terminal = False
    for line in diagnostic_output_lines(io.BytesIO(tail)):
        if re.match(rb"^configure:[0-9]{1,8}: checking ", line):
            reporters.clear()
            checking, terminal = True, False
        elif re.match(rb"^configure:[0-9]{1,8}: error: ", line):
            terminal, checking = checking, False
        elif checking and permission_text(line) == b"operation not permitted":
            reporters.add(configure_eperm_reporter(line, configure_reporter))
    if terminal and len(reporters) == 1:
        return CONFIG_LOG_REPORTERS.get(next(iter(reporters)), 131)
    return 131

def config_log_hint(directory, child_exit, configure_reporter, unit, unit_deadline, clock_namespace):
    # The bounded observation is optional and retains the original failure on unavailable evidence.
    try:
        if (directory is None or not clock_namespace or os.readlink("/proc/self/ns/time") != clock_namespace
                or int(unit_deadline) - time.monotonic_ns() <= 1_000_000_000 or not only_source_worker_in_cgroup(unit)):
            return 131
        hint = terminal_config_log_reporter(config_log_tail(directory), child_exit, configure_reporter)
        return hint if int(unit_deadline) - time.monotonic_ns() > 1_000_000_000 else 131
    except (OSError, ValueError):
        return 131

def configure_status(release, prefix, options, directory, unit, unit_deadline, clock_namespace):
    # Only a fixed worker status leaves the unprivileged unit.
    configure_path = str(release / "configure")
    configure_reporter = os.fsencode(configure_path) + b": "
    try:
        process = subprocess.Popen((configure_path, "--prefix=" + prefix, "--disable-install-doc", *options),
                                   cwd=release, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except OSError as error:
        return {errno.EACCES: 104, errno.EPERM: 104, errno.ENOENT: 105, errno.ENOEXEC: 106}.get(error.errno, 107)

    messages = {
        b"configure: error: no acceptable C compiler found in $PATH": 108,
        b"configure: error: C compiler cannot create executables": 109,
        b"configure: error: cannot run C compiled programs.": 110,
    }
    suffixes = {b"permission denied": 130, b"operation not permitted": 131, b"read-only file system": 132,
                b"no space left on device": 133, b"disk quota exceeded": 133}
    exact, hints, eperm_reporters = None, set(), set()
    try:
        with process:
            for line in diagnostic_output_lines(process.stdout):
                exact = messages.get(line, exact)
                hint = suffixes.get(permission_text(line))
                if hint:
                    hints.add(hint)
                if hint == 131:
                    eperm_reporters.add(configure_eperm_reporter(line, configure_reporter))
            code = process.wait()
    except OSError:
        return 135
    if code < 0:
        return 111
    if code:
        if exact:
            return exact
        if len(hints) > 1 or len(eperm_reporters) > 1:
            return 171
        hint = next(iter(eperm_reporters or hints), 134)
        if hint == 131:
            return config_log_hint(directory, code, configure_reporter, unit, unit_deadline, clock_namespace)
        return hint
    return 0

def configure(release, prefix, options, unit, unit_deadline, clock_namespace):
    with fresh_config_log_directory(release) as directory:
        status = configure_status(release, prefix, options, directory, unit, unit_deadline, clock_namespace)
    if status:
        sys.exit(status)

failure = 90
try:
    mode, network, mount, prefix, version, *arguments = sys.argv[1:]
    require_isolated(network, mount)
    ruby = prefix + "/bin/ruby"
    if mode == "build":
        failure = 95
        device, inode, legacy, *resource_limits, unit_deadline, clock_namespace = arguments
        require_staging_mount(prefix, device, inode)
        failure = 126
        require_ruby_resources(*resource_limits)
        failure = 97
        release = extract_release("ruby-" + version)
        verify_build_scratch(release, prefix)
        verify_native_build_chain(release)
        options = ["--with-out-ext=openssl"] if legacy == "legacy" else []
        failure = 98
        configure(release, prefix, options, resource_limits[0], unit_deadline, clock_namespace)
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

    else:
        failure = 126
        require_ruby_resources(*arguments)
    failure = 103
    command(ruby, "-e", "abort unless RUBY_VERSION == ARGV.fetch(0); require 'openssl'; require 'zlib'; require 'yaml'", version)
except (OSError, KeyError, ValueError, subprocess.CalledProcessError):
    sys.exit(failure)
'''


def ruby_system_command(executable, *arguments):
    return ['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin', 'LC_ALL=C', '/usr/bin/' + executable, *arguments]


def ruby_unit_name(operation_id, role):
    return source_unit_name(operation_id, role, 'ruby')


def source_unit_name(operation_id, role, ecosystem):
    return 'wtl-' + ecosystem + '-' + role + '-' + uuid.UUID(operation_id).hex + '.service'


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


def ruby_scratch_mount(path, unit, check_limits=True):
    name = ruby_mount_path(path)
    owned_directory(path.parent)
    if path.is_symlink():
        fail('ownership', 'Ruby scratch directory is a symbolic link')
    matches = []
    with open('/proc/self/mountinfo', encoding='ascii', errors='surrogateescape') as stream:
        for row in stream:
            mount, filesystem = row.rstrip('\n').split(' - ', 1)
            fields = mount.split()
            target = fields[4]
            if target.startswith(name + '/'):
                fail('ownership', 'unexpected nested Ruby scratch mount')
            if target == name:
                matches.append((fields, filesystem.split()))
    if not matches:
        return False
    if len(matches) != 1:
        fail('ownership', 'multiple Ruby scratch mounts')
    mount, filesystem = matches[0]
    volume = path.stat()
    space = os.statvfs(path)
    if (mount[3] != '/' or not {'rw', 'nosuid', 'nodev'} <= set(mount[5].split(','))
            or filesystem[:2] != ['tmpfs', unit]
            or volume.st_uid != 0 or not stat.S_ISDIR(volume.st_mode)):
        fail('ownership', 'Ruby scratch mount identity differs')
    if check_limits and (not 0 < space.f_blocks * space.f_frsize <= RUBY_SCRATCH_BYTES
                         or not 0 < space.f_files <= RUBY_SCRATCH_INODES):
        fail('installation', 'Ruby scratch mount bounds differ')
    return True


def ruby_create_scratch(path, unit):
    ruby_mount_path(path)
    path.mkdir(mode=0o700)
    options = f'rw,nosuid,nodev,mode=1777,size={RUBY_SCRATCH_BYTES},nr_inodes={RUBY_SCRATCH_INODES}'
    run(ruby_system_command('mount', '--types', 'tmpfs', '--options', options, '--', unit, str(path)))
    if not ruby_scratch_mount(path, unit):
        fail('installation', 'Ruby scratch mount could not be verified')


def ruby_remove_scratch(path, unit):
    if not ruby_scratch_mount(path, unit, check_limits=False):
        return
    try:
        subprocess.run(ruby_system_command('umount', '--', str(path)), stdin=subprocess.DEVNULL,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        fail('interrupted', 'the original Ruby scratch mount could not be released')
    if ruby_scratch_mount(path, unit, check_limits=False):
        fail('interrupted', 'the original Ruby scratch mount remains attached')


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


def ruby_diagnostic(command, role, ecosystem='ruby'):
    result, code, value = ruby_manager_terminal(command)
    exit_code = command['actualExitCode']
    failure = RUBY_WORKER_FAILURES.get(exit_code)
    configure_failure = RUBY_CONFIGURE_FAILURES.get(exit_code) if ecosystem == 'ruby' and role == 'build' else None
    make_failure = (globals().get('PYTHON_MAKE_FAILURES', {}).get(exit_code)
                    if ecosystem == 'python' and role == 'build' else None)
    build_probe_failure = None
    if role == 'build' and exit_code is not None and 145 <= exit_code <= 164:
        index, detail = divmod(exit_code - 145, len(SOURCE_BUILD_PROBE_FAILURES))
        phase, child = SOURCE_BUILD_PROBE_STAGES[index]
        failure = 'native-check-' + phase, child
        build_probe_failure = SOURCE_BUILD_PROBE_FAILURES[detail]
    if configure_failure is not None:
        failure = RUBY_WORKER_FAILURES[98]
    if make_failure is not None:
        failure = RUBY_WORKER_FAILURES[99]
    worker = (result == 'exit-code' and code == 'exited' and value == str(exit_code) and failure is not None
              and not (command['timedOut'] or command['cancelled']))
    stage = ecosystem + '-' + role + '-launcher'
    details = ['unit-role=' + role, 'manager-result=' + result, 'manager-main-code=' + code, 'manager-main-status=' + value]
    if worker:
        phase, child = failure
        if ecosystem == 'python' and exit_code in (102, 103):
            child = 'python3'
        stage = ecosystem + '-' + role + '-' + phase
        details.extend(('worker-stage=' + phase, 'worker-executable=' + child))
        if configure_failure is not None:
            details.append('configure-failure=' + configure_failure)
        if make_failure is not None:
            details.append('make-failure=' + make_failure)
        if build_probe_failure is not None:
            details.append('native-check-failure=' + build_probe_failure)
    return {**command, 'stage': stage, 'executable': 'systemd-run',
            'stdout': '\n'.join(details) + '\n', 'stderr': ''}


def ruby_run(command, role, ecosystem='ruby'):
    # The synchronous shared runner captures the launcher's streams. The worker's streams stay separate.
    global record_installation_diagnostic
    recorder = record_installation_diagnostic

    def record_ruby_observation(observation):
        recorder(ruby_diagnostic(observation, role, ecosystem))

    record_installation_diagnostic = record_ruby_observation
    try:
        run(command)
    finally:
        record_installation_diagnostic = recorder


def ruby_unit(unit, directory, version, build_mounts=None):
    source_unit(unit, directory, version, 'ruby', RUBY_WORKER, build_mounts)


def source_unit(unit, directory, version, ecosystem, worker_source, build_mounts):
    import grp
    import pwd

    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        fail('metadata', 'invalid exact Ruby release')
    directory = ruby_mount_path(directory)
    launch_start = time.monotonic_ns()
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
        'ProtectSystem=strict', 'ProtectHome=tmpfs', 'PrivateDevices=yes', 'PrivateNetwork=yes',
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
    worker = ['/usr/bin/python3', '-I', '-S', '-B', '-c', worker_source]
    worker.extend(['build' if build_mounts else 'probe', os.readlink('/proc/self/ns/net'),
                   os.readlink('/proc/self/ns/mnt'), directory, version])
    if build_mounts:
        properties.append('PrivateTmp=yes')
        stage, archive, gem = build_mounts
        staged = stage.lstat()
        sources = [ruby_mount_path(archive) + ':/tmp/wtl-toolchain-release']
        if gem is not None:
            sources.append(ruby_mount_path(gem) + ':/tmp/openssl-3.0.3.gem')
        mounts = [ruby_mount_path(stage) + ':' + directory]
        writable = [directory]
        worker.extend([str(staged.st_dev), str(staged.st_ino), 'legacy' if gem is not None else 'standard'])
        scratch = stage.parent
        info = scratch.lstat()
        readonly = ruby_mount_path(scratch / 'readonly')
        sources.extend([readonly + ':/var/tmp', readonly + ':/dev/shm'])
        mounts.append(ruby_mount_path(scratch) + ':/tmp')
        writable.append('/tmp')
        properties.extend(['MemoryMax=' + str(RUBY_MEMORY_BYTES), 'MemorySwapMax=0'])
        worker.extend([unit, str(info.st_dev), str(info.st_ino), str(RUBY_SCRATCH_BYTES),
                       str(RUBY_SCRATCH_INODES), str(RUBY_MEMORY_BYTES)])
        try:
            clock_namespace = os.readlink('/proc/self/ns/time')
        except OSError:
            clock_namespace = ''
        worker.extend([str(launch_start + seconds * 1_000_000_000), clock_namespace])
        properties.extend(['BindReadOnlyPaths=' + ' '.join(sources), 'BindPaths=' + ' '.join(mounts),
                           'ReadWritePaths=' + ' '.join(writable)])
    else:
        # Explicit tmpfs mounts provide private storage without competing with PrivateTmp mounts.
        properties.extend(['PrivateTmp=no', 'MemoryMax=' + str(SOURCE_PROBE_MEMORY), 'MemorySwapMax=0',
                           'TemporaryFileSystem=' + ' '.join(path + ':ro,nosuid,nodev,noexec,size='
                           + str(SOURCE_PROBE_BYTES) + ',nr_inodes=' + str(SOURCE_PROBE_INODES)
                           for path in ('/tmp', '/var/tmp', '/dev/shm'))])
        if directory.startswith(('/var/', '/home/', '/root/')):
            properties.append('BindReadOnlyPaths=' + directory)
        worker.extend([unit, '-', '-', str(SOURCE_PROBE_BYTES), str(SOURCE_PROBE_INODES), str(SOURCE_PROBE_MEMORY)])
    command = ruby_system_command('systemd-run', '--wait', '--collect', '--slice=system.slice', '--unit=' + unit)
    command.extend('--property=' + value for value in properties)
    try:
        ruby_run(command + ['--'] + worker, 'build' if build_mounts else 'probe', ecosystem)
    except (PreparationFailure, subprocess.TimeoutExpired, OSError):
        subprocess.run(
            ruby_system_command('systemctl', 'stop', '--no-pager', unit), stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15,
        )
        raise
    finally:
        ruby_require_settled(unit)


def ruby_probe(directory, version):
    source_probe(directory, version, 'ruby', ruby_unit)


def source_probe(directory, version, ecosystem, run_unit):
    operation = globals().get('TRACKED_OPERATION')
    if operation is None:
        run_unit(source_unit_name(str(uuid.uuid4()), 'probe', ecosystem), directory, version)
        return
    marker = operation.directory / (ecosystem + '-probe')
    if marker.exists() or marker.is_symlink():
        fail('interrupted', 'an earlier Ruby probe still requires observation')
    identity = str(uuid.uuid4())
    unit = source_unit_name(identity, 'probe', ecosystem)
    tracked_atomic_bytes(marker, identity.encode('ascii'))
    try:
        run_unit(unit, directory, version)
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


def ruby_publish(stage, destination, executable='ruby'):
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
    launcher = ('bin', executable)
    if executable == 'ruby' and launcher in links:
        fail('probe', 'Ruby staging did not produce its regular executable')
    target = destination.joinpath(*ruby_link_target(launcher, links))
    if not target.is_file() or not target.stat().st_mode & 0o111:
        fail('probe', 'source build staging did not produce its executable')


def ruby_source_install(identity, url, algorithm, expected, layout):
    return source_install('RUBY', RUBY_RECIPE, identity, url, algorithm, expected, layout,
                          ruby_unit, ruby_publish, ruby_probe)


def source_install(ecosystem, recipe, identity, url, algorithm, expected, layout, run_unit, publish, probe_release):
    global MIRROR_POINT
    name = ecosystem.lower()
    if (layout != name or algorithm != 'sha256' or not re.fullmatch('[a-f0-9]{64}', expected) or
            not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', identity)):
        fail('metadata', 'invalid official source release metadata')
    MIRROR_POINT = toolchain_point(ecosystem)
    destination = ROOT / 'versions' / (name + '-' + installation_key(ecosystem, identity, expected))
    marker = saved_installation_marker(destination)
    if marker is not None:
        record = json.loads(tracked_regular(marker, 65536))
        if (not isinstance(record, dict) or any(record.get(key) != value for key, value in (
                ('ecosystem', ecosystem), ('origin', 'MANAGED'), ('version', identity),
                ('directory', str(destination)), ('source', url), ('sha256', expected),
                ('official_algorithm', algorithm), ('official_digest', expected)))
                or record.get('recipe', recipe if ecosystem == 'PYTHON' else None) != recipe):
            fail('integrity', 'existing installation does not match its source recipe')
        probe_release(destination, identity)
        return record

    tracked = globals().get('TRACKED_OPERATION')
    operation_id = tracked.request['operationId'] if tracked is not None else str(uuid.uuid4())
    unit = source_unit_name(operation_id, 'build', name)
    if tracked is not None:
        work = tracked.directory / 'work'
        work.mkdir(mode=0o700)
        tracked_sync_directory(tracked.directory)
    else:
        work_parent = ROOT / 'work'
        owned_directory(work_parent)
        work_parent.mkdir(mode=0o700, exist_ok=True)
        work = pathlib.Path(tempfile.mkdtemp(prefix=name + '-', dir=work_parent))
    sandbox_started, sandbox_settled = False, False
    try:
        archive = work / 'release.archive'
        sha256 = download(url, archive, algorithm, expected)
        archive.chmod(0o444)
        if tracked is not None:
            tracked.archive_downloaded(sha256)
        gem, dependencies = None, {}
        if ecosystem == 'RUBY' and version_key(identity)[:2] == (3, 0):
            gem = work / 'openssl-3.0.3.gem'
            gem_url = source_url('RUBYGEMS', 'downloads/' + gem.name)
            download(gem_url, gem, 'sha256', RUBY_OPENSSL_DIGEST)
            gem.chmod(0o444)
            dependencies['openssl'] = {'version': '3.0.3', 'source': gem_url, 'sha256': RUBY_OPENSSL_DIGEST}
        source_dependencies(layout)
        scratch = work / 'scratch'
        ruby_create_scratch(scratch, unit)
        (scratch / 'readonly').mkdir(mode=0o555)
        stage = scratch / 'stage'
        stage.mkdir()
        # Only the unit can reach this write mount. Every host ancestor to the work directory is protected.
        stage.chmod(0o777)
        with create_installation_directory(destination, tracked):
            sandbox_started = True
            run_unit(unit, destination, identity, (stage, archive, gem))
            sandbox_settled = True
            publish(stage, destination)
            probe_release(destination, identity)
            record = {
                'ecosystem': ecosystem, 'version': identity, 'directory': str(destination), 'origin': 'MANAGED',
                'source': url, 'sha256': sha256, 'official_algorithm': algorithm, 'official_digest': expected,
                'dependencies': dependencies, 'recipe': recipe,
            }
            tracked_atomic_json(destination / '.w2l-toolchain.json', record, 0o444)
            return record
    finally:
        if sandbox_started and not sandbox_settled:
            ruby_require_settled(unit)
        ruby_remove_scratch(work / 'scratch', unit)
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
    if operation.request['kind'] == 'PREPARE':
        name = operation.request['parameters']['ecosystem'].lower()
        work = operation.directory / 'work'
        if name in ('ruby', 'python') and ((work / 'stage').is_dir() or (work / 'scratch').is_dir()):
            ruby_require_settled(source_unit_name(operation.request['operationId'], 'build', name))
    for name, marker in source_probe_markers(operation):
        if marker.exists() or marker.is_symlink():
            identity = tracked_regular(marker, 36).decode('ascii')
            if not tracked_uuid(identity):
                fail('integrity', 'invalid tracked source toolchain probe identity')
            ruby_require_settled(source_unit_name(identity, 'probe', name))


def source_probe_markers(operation):
    return [(name, operation.directory / (name + '-probe')) for name in ('ruby', 'python')]


def tracked_owned_residue_with_ruby(operation):
    return (any(marker.exists() or marker.is_symlink() for _, marker in source_probe_markers(operation))
            or _previous_tracked_owned_residue(operation))


def tracked_cleanup_with_ruby(operation):
    tracked_require_quiescent(operation)
    if operation.request['kind'] == 'PREPARE':
        name = operation.request['parameters']['ecosystem'].lower()
        work = operation.directory / 'work'
        if name in ('ruby', 'python') and (work.exists() or work.is_symlink()):
            owned_directory(work)
            ruby_remove_scratch(work / 'scratch', source_unit_name(operation.request['operationId'], 'build', name))
    _previous_tracked_cleanup(operation)
    for _, marker in source_probe_markers(operation):
        if marker.exists():
            marker.unlink()
            tracked_sync_directory(operation.directory)


installation_key = install_key_with_ruby_recipe
install = install_with_ruby_isolation
probe = probe_with_ruby_isolation
tracked_require_quiescent = tracked_require_quiescent_with_ruby
tracked_owned_residue = tracked_owned_residue_with_ruby
tracked_cleanup = tracked_cleanup_with_ruby
