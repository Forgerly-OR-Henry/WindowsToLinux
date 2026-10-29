"""Operation-owned dependency service with pre-transaction core-events ACK and strict v2 receipt."""
import ctypes
import errno
DEPENDENCY_DEADLINE_NS = None
DEPENDENCY_WORKER_ADMITTED = False
DEPENDENCY_STOP_SECONDS = 5
DEPENDENCY_RESERVE_SECONDS = 25
DEPENDENCY_OBSERVATIONS = []
DEPENDENCY_LAST_OBSERVATION = None
DEPENDENCY_OBSERVATION_SEQUENCE = 0


def dependency_remaining():
    if DEPENDENCY_DEADLINE_NS is None:
        dependency_reject('DEPENDENCY_DEADLINE_REQUIRED')
    value = (DEPENDENCY_DEADLINE_NS - time.monotonic_ns()) / 1000000000
    if value <= 0:
        dependency_reject('DEPENDENCY_TRANSACTION_TIMEOUT', True)
    return value


def dependency_frame(args):
    if len(args) != 5 or args[0] not in ('JAVA', 'NODE', 'PYTHON', 'DOTNET', 'KOTLIN', 'GO', 'RUST', 'PHP', 'RUBY', 'NATIVE_BUILD', 'HELPER_RUNTIME') \
            or not re.fullmatch('[a-f0-9]{32}', args[1]) or not re.fullmatch('[1-9][0-9]{0,3}', args[2]) \
            or not 45 <= int(args[2]) <= 7200 or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._:-]{0,127}', args[3]) \
            or not re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', args[4]):
        dependency_reject('DEPENDENCY_ARGUMENT_FRAME')
    return {'profile': args[0], 'operation': args[1], 'totalSeconds': int(args[2]),
            'server': args[3], 'task': args[4]}


def dependency_unit(operation):
    if not re.fullmatch('[a-f0-9]{32}', operation):
        dependency_reject('DEPENDENCY_OPERATION')
    return 'windowstolinux-dependency-' + operation + '.service'


def dependency_encode(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode('ascii')


def dependency_json(data):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                dependency_reject('DEPENDENCY_DUPLICATE_FIELD', True)
            result[key] = value
        return result
    return json.loads(data.decode('ascii'), object_pairs_hook=pairs)


def dependency_write(path, data):
    # Readers must never observe a final-name half-file. Reuse the tracked binding's no-replace publish.
    temporary = pathlib.Path(str(path) + '.new')
    fd = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o400)
    try:
        os.fchown(fd, 0, 0)
        if os.write(fd, data) != len(data):
            dependency_reject('DEPENDENCY_SHORT_WRITE', True)
        os.fsync(fd)
    finally:
        os.close(fd)
    try:
        os.link(str(temporary), str(path), follow_symlinks=False)
    finally:
        temporary.unlink()
    directory = os.open(str(pathlib.Path(path).parent), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def dependency_directory(path, create=False):
    path = pathlib.Path(path)
    for parent in reversed((path, *path.parents)):
        if create and not parent.exists() and not parent.is_symlink():
            parent.mkdir(mode=0o700)
        resolved = dependency_protected(parent)
        if parent.is_symlink() or resolved != parent or not parent.is_dir():
            dependency_reject('DEPENDENCY_DIRECTORY')
    return path


def dependency_read(path, limit=1048576):
    original = pathlib.Path(path)
    path = dependency_protected(original)
    if original.is_symlink() or original != path:
        dependency_reject('DEPENDENCY_FILE_ALIAS', True)
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            dependency_reject('DEPENDENCY_FILE', True)
        data = os.read(fd, limit + 1)
        after = os.fstat(fd)
        if len(data) != info.st_size or (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            dependency_reject('DEPENDENCY_FILE_CHANGED', True)
        return data
    finally:
        os.close(fd)


def dependency_group(manifest, empty=False):
    root = pathlib.Path('/sys/fs/cgroup/system.slice') / manifest['unit']
    # Missing is never evidence of emptiness. The recursive proof needs actual cgroup.procs.
    if not root.is_dir() or root.is_symlink():
        dependency_reject('DEPENDENCY_CGROUP_UNAVAILABLE', True)
    identity = [root.stat().st_dev, root.stat().st_ino]
    seen, members = 0, []
    for directory, dirs, files in os.walk(root, followlinks=False):
        if len(dirs) > 1024 or any((pathlib.Path(directory) / name).is_symlink() for name in dirs) or 'cgroup.procs' not in files:
            dependency_reject('DEPENDENCY_CGROUP_UNAVAILABLE', True)
        seen += 1
        if seen > 4096:
            dependency_reject('DEPENDENCY_CGROUP_LIMIT', True)
        value = (pathlib.Path(directory) / 'cgroup.procs').read_text(encoding='ascii').split()
        if any(not re.fullmatch('[1-9][0-9]{0,9}', pid) for pid in value):
            dependency_reject('DEPENDENCY_CGROUP_INVALID', True)
        members.extend(value)
    if not seen or empty and members:
        dependency_reject('DEPENDENCY_CGROUP_NOT_EMPTY', True)
    return identity, members


def dependency_duration(value):
    if not isinstance(value, str):
        dependency_reject('DEPENDENCY_UNIT_BUDGET', True)
    # systemctl show uses canonical systemd time spans, e.g. 1min 30s, not an arbitrary float.
    parts = value.split(' ')
    units, total, previous = {'h': 3600, 'min': 60, 's': 1}, 0, 3601
    for part in parts:
        m = re.fullmatch('(0|[1-9][0-9]*)(h|min|s)', part)
        if m is None or units[m[2]] >= previous:
            dependency_reject('DEPENDENCY_UNIT_BUDGET', True)
        previous = units[m[2]]
        total += int(m[1]) * previous
    return total


def dependency_control(args, timeout=3, allowed=(0,)):
    process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        cwd='/', env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C'})
    try:
        output, _ = process.communicate(timeout=min(timeout, dependency_remaining()))
        if (allowed is not None and process.returncode not in allowed) or len(output) > 65536:
            dependency_reject('DEPENDENCY_MANAGER_UNCONFIRMED', True)
        return process.returncode, output.decode('utf-8', 'strict')
    finally:
        if process.poll() is None:
            process.kill()  # Only this unreaped manager client, never an application PID.
        try:
            process.wait(timeout=1)
        finally:
            process.stdout.close()


def dependency_show(unit, phase='UNIT_STATUS'):
    global DEPENDENCY_LAST_OBSERVATION, DEPENDENCY_OBSERVATION_SEQUENCE
    names = ('LoadState', 'ActiveState', 'SubState', 'MainPID', 'ExecMainStatus', 'Result', 'InvocationID',
             'ControlGroup', 'Description', 'Transient', 'Type', 'User', 'Group', 'KillMode', 'SendSIGKILL',
             'RuntimeMaxUSec', 'TimeoutStopUSec', 'RemainAfterExit', 'Delegate', 'ExitType', 'ActiveEnterTimestampMonotonic')
    DEPENDENCY_LAST_OBSERVATION = {'phase': phase, 'unit': unit, 'monotonicNs': time.monotonic_ns(),
                                   'nativeExit': None, 'fields': {}, 'callReturned': False}
    code, text = dependency_control([dependency_tool('systemctl'), 'show', '--no-pager',
        '--property=' + ','.join(names), '--', unit], allowed=None)
    facts, duplicates, unexpected = {}, [], []
    for line in text.splitlines():
        key, sep, value = line.partition('=')
        if sep and key in names:
            if key in facts:
                duplicates.append(key)
            else:
                facts[key] = value
        else:
            unexpected.append(key if re.fullmatch('[A-Za-z][A-Za-z0-9]{0,63}', key) else '[UNPARSEABLE_FIELD]')
    DEPENDENCY_OBSERVATION_SEQUENCE += 1
    DEPENDENCY_LAST_OBSERVATION = {'sequence': DEPENDENCY_OBSERVATION_SEQUENCE, 'phase': phase, 'unit': unit,
        'monotonicNs': time.monotonic_ns(), 'nativeExit': code, 'fields': dict(facts),
        'missingFields': sorted(set(names) - set(facts)), 'duplicateFields': duplicates,
        'unexpectedFieldNames': unexpected, 'sameCallNotRetrospectiveQuery': True}
    DEPENDENCY_OBSERVATIONS.append(DEPENDENCY_LAST_OBSERVATION)
    del DEPENDENCY_OBSERVATIONS[:-16]
    if code not in (0, 1, 4) or duplicates or unexpected or set(facts) != set(names):
        dependency_reject('DEPENDENCY_UNIT_OBSERVATION', True)
    return dict(facts)


def dependency_static_identity(manifest, facts):
    expected = {'LoadState': 'loaded', 'Transient': 'yes', 'Description': manifest['description'],
        'Type': 'exec', 'ExitType': 'cgroup', 'User': 'root', 'Group': 'root', 'KillMode': 'control-group',
        'SendSIGKILL': 'yes', 'RemainAfterExit': 'yes', 'Delegate': 'no'}
    if any(facts.get(k) != v for k, v in expected.items()) or facts.get('ControlGroup') not in ('', '/system.slice/' + manifest['unit']):
        dependency_reject('DEPENDENCY_UNIT_IDENTITY', True)
    if dependency_duration(facts.get('RuntimeMaxUSec')) != manifest['runtimeSeconds'] or dependency_duration(facts.get('TimeoutStopUSec')) != DEPENDENCY_STOP_SECONDS:
        dependency_reject('DEPENDENCY_UNIT_BUDGET', True)


def dependency_unit_identity(manifest, facts, ready=None, running=False):
    dependency_static_identity(manifest, facts)
    value = facts.get('InvocationID', '')
    if not re.fullmatch('[a-f0-9]{32}', value) or value == '0' * 32 or ready is not None and value != ready['invocationId']:
        dependency_reject('DEPENDENCY_UNIT_IDENTITY', True)
    if running and (facts['ControlGroup'] != '/system.slice/' + manifest['unit'] or not re.fullmatch('[1-9][0-9]*', facts.get('MainPID', ''))):
        dependency_reject('DEPENDENCY_WORKER_NOT_IN_SERVICE', True)
    return value


def dependency_startup_decision(manifest, facts, ownership, observed_ns, ready_at_call):
    if manifest['originalUnitAbsent'] is not True or observed_ns >= manifest['deadlineMonotonicNs']:
        dependency_reject('DEPENDENCY_STARTUP_DEADLINE', True)
    if facts['LoadState'] == 'not-found':
        if ownership is not None or ready_at_call:
            dependency_reject('DEPENDENCY_UNIT_IDENTITY', True)
        return None
    dependency_static_identity(manifest, facts)
    if ownership is None and not ready_at_call and facts.get('InvocationID') in ('', '0' * 32):
        return None
    return dependency_unit_identity(manifest, facts, ownership)


def dependency_manifest(path):
    path = pathlib.Path(path)
    if path.name != 'manifest.json' or path.parent.parent != pathlib.Path(DEPENDENCY_OPERATIONS_ROOT) or not re.fullmatch('[a-f0-9]{32}', path.parent.name):
        dependency_reject('DEPENDENCY_MANIFEST_PATH')
    m = dependency_json(dependency_read(path, 16384))
    keys = {'formatVersion', 'profile', 'operation', 'server', 'task', 'totalSeconds', 'runtimeSeconds',
            'deadlineMonotonicNs', 'workerDeadlineMonotonicNs', 'unit', 'description', 'workerSourceSha256', 'exitType', 'originalUnitAbsent'}
    if not isinstance(m, dict) or set(m) != keys or type(m['formatVersion']) is not int or m['formatVersion'] != 2:
        dependency_reject('DEPENDENCY_MANIFEST', True)
    frame = dependency_frame([m['profile'], m['operation'], str(m['totalSeconds']), m['server'], m['task']])
    if any(type(m[k]) is not int or m[k] < 1 for k in ('totalSeconds', 'runtimeSeconds', 'deadlineMonotonicNs', 'workerDeadlineMonotonicNs')) \
            or m['operation'] != path.parent.name or m['runtimeSeconds'] > frame['totalSeconds'] - 30 \
            or m['deadlineMonotonicNs'] - m['workerDeadlineMonotonicNs'] != 30000000000 \
            or m['unit'] != dependency_unit(m['operation']) or m['exitType'] != 'cgroup' or m['originalUnitAbsent'] is not True \
            or not isinstance(m['workerSourceSha256'], str) or not re.fullmatch('[a-f0-9]{64}', m['workerSourceSha256']) \
            or m['description'] != 'WTL_DEPENDENCY_2:' + m['operation'] + ':' + m['workerSourceSha256'] \
            or hashlib.sha256(dependency_read(path.parent / 'worker.py')).hexdigest() != m['workerSourceSha256']:
        dependency_reject('DEPENDENCY_MANIFEST', True)
    return m


def dependency_ownership_record(m, invocation):
    return {'formatVersion': 2, 'operation': m['operation'], 'profile': m['profile'], 'unit': m['unit'],
        'description': m['description'], 'workerSourceSha256': m['workerSourceSha256'], 'server': m['server'], 'task': m['task'],
        'totalSeconds': m['totalSeconds'], 'runtimeSeconds': m['runtimeSeconds'], 'stopSeconds': 5, 'reserveSeconds': 25,
        'exitType': 'cgroup', 'invocationId': invocation}


def dependency_validate_ownership(m, value, invocation=None):
    if not isinstance(value, dict) or not isinstance(value.get('invocationId'), str) or not re.fullmatch('[a-f0-9]{32}', value['invocationId']) \
            or value['invocationId'] == '0' * 32 or invocation is not None and value['invocationId'] != invocation:
        dependency_reject('DEPENDENCY_CLEANUP_OWNERSHIP', True)
    expected = dependency_ownership_record(m, value['invocationId'])
    if value != expected or set(value) != set(expected) or any(type(value[k]) is not int for k in ('formatVersion', 'totalSeconds', 'runtimeSeconds', 'stopSeconds', 'reserveSeconds')):
        dependency_reject('DEPENDENCY_CLEANUP_OWNERSHIP', True)
    return value


def dependency_retain_ownership(m, base, ownership, invocation):
    expected = dependency_ownership_record(m, invocation)
    path = base / 'cleanup-ownership.json'
    if path.exists():
        saved = dependency_validate_ownership(m, dependency_json(dependency_read(path)), invocation)
        if saved != expected:
            dependency_reject('DEPENDENCY_CLEANUP_OWNERSHIP', True)
    else:
        if ownership is not None:
            dependency_reject('DEPENDENCY_CLEANUP_OWNERSHIP', True)
        dependency_write(path, dependency_encode(expected))
    return expected


def dependency_observe_startup(m, base, ownership, startup, observations):
    ready_at_call = (base / 'ready.json').exists()
    try:
        facts = dependency_show(m['unit'], phase='READY_STRICT' if ready_at_call else 'SOURCE_BEFORE_READY')
        invocation = dependency_startup_decision(m, facts, ownership, DEPENDENCY_LAST_OBSERVATION['monotonicNs'], ready_at_call)
    except BaseException:
        observations['rejectionObservation'] = dependency_json(dependency_encode(DEPENDENCY_LAST_OBSERVATION))
        raise
    finally:
        startup.append(dependency_json(dependency_encode(DEPENDENCY_LAST_OBSERVATION)))
        del startup[:-16]
    startup[-1].update(decision='PENDING' if invocation is None else 'BOUND', originalDeadlineMonotonicNs=m['deadlineMonotonicNs'])
    if invocation is not None:
        ownership = dependency_retain_ownership(m, base, ownership, invocation)
    return ownership, facts


def dependency_identity_pair(value):
    return isinstance(value, list) and len(value) == 2 and all(type(v) is int and v > 0 for v in value)


def dependency_ready_record(m, invocation, group_identity, main_pid):
    return {'formatVersion': 2, 'operation': m['operation'], 'profile': m['profile'], 'server': m['server'], 'task': m['task'],
        'unit': m['unit'], 'workerSourceSha256': m['workerSourceSha256'], 'invocationId': invocation,
        'totalSeconds': m['totalSeconds'], 'runtimeSeconds': m['runtimeSeconds'], 'groupIdentity': group_identity,
        'mainPid': main_pid, 'workerDeadlineMonotonicNs': m['workerDeadlineMonotonicNs']}


def dependency_validate_ready(m, value, invocation):
    if not isinstance(value, dict) or not dependency_identity_pair(value.get('groupIdentity')) or type(value.get('mainPid')) is not int or value['mainPid'] < 1:
        dependency_reject('DEPENDENCY_READY_IDENTITY', True)
    expected = dependency_ready_record(m, invocation, value['groupIdentity'], value['mainPid'])
    if value != expected or set(value) != set(expected) or any(type(value[k]) is not int for k in ('formatVersion', 'totalSeconds', 'runtimeSeconds', 'workerDeadlineMonotonicNs')):
        dependency_reject('DEPENDENCY_READY_IDENTITY', True)
    return value


def dependency_fstype(fd):
    buffer = ctypes.create_string_buffer(256)
    function = ctypes.CDLL(None, use_errno=True).fstatfs
    function.argtypes, function.restype = (ctypes.c_int, ctypes.c_void_p), ctypes.c_int
    if function(fd, buffer) != 0:
        dependency_reject('DEPENDENCY_CGROUP_FILESYSTEM', True)
    return ctypes.c_ulong.from_buffer(buffer).value


def dependency_events_read(fd):
    try:
        data = os.pread(fd, 4097, 0)  # Positive offset-zero read, no separate lseek or cached file position.
    except OSError as error:
        return {'kind': 'ERRNO', 'errno': error.errno, 'method': 'PREAD_OFFSET_ZERO'}
    if not data or len(data) > 4096 or not data.endswith(b'\n'):
        dependency_reject('DEPENDENCY_CORE_EVENTS_READ', True)
    values = {}
    for line in data.decode('ascii', 'strict').splitlines():
        pair = line.split()
        if len(pair) != 2 or pair[0] not in ('populated', 'frozen') or pair[0] in values or pair[1] not in ('0', '1'):
            dependency_reject('DEPENDENCY_CORE_EVENTS_READ', True)
        values[pair[0]] = int(pair[1])
    if set(values) != {'populated', 'frozen'}:
        dependency_reject('DEPENDENCY_CORE_EVENTS_READ', True)
    return {'kind': 'VALUES', **values, 'method': 'PREAD_OFFSET_ZERO'}


def dependency_open_core_events(m, ready):
    facts = dependency_show(m['unit'], phase='CORE_EVENTS_ADMISSION')
    dependency_unit_identity(m, facts, ready, running=True)
    identity, members = dependency_group(m)
    if identity != ready['groupIdentity'] or facts['MainPID'] != str(ready['mainPid']) or str(ready['mainPid']) not in members:
        dependency_reject('DEPENDENCY_CGROUP_IDENTITY', True)
    root = pathlib.Path('/sys/fs/cgroup/system.slice') / m['unit']
    dependency_directory(root)
    directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    events_fd = None
    try:
        info = os.fstat(directory_fd)
        if [info.st_dev, info.st_ino] != identity or dependency_fstype(directory_fd) != 1667723888:
            dependency_reject('DEPENDENCY_CGROUP_FILESYSTEM', True)
        events_fd = os.open('cgroup.events', os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=directory_fd)
        info = os.fstat(events_fd)
        if not stat.S_ISREG(info.st_mode) or dependency_fstype(events_fd) != 1667723888:
            dependency_reject('DEPENDENCY_CGROUP_FILESYSTEM', True)
        first = dependency_events_read(events_fd)
        if first.get('kind') != 'VALUES' or first.get('populated') != 1:
            dependency_reject('DEPENDENCY_CORE_EVENTS_INITIAL', True)
        anchor = {'groupIdentity': identity, 'eventsIdentity': [info.st_dev, info.st_ino],
                  'coreEventsFilesystemMagic': 1667723888, 'initialPopulated': 1, 'coreEventsBound': True}
        return directory_fd, events_fd, anchor
    except BaseException:
        if events_fd is not None:
            os.close(events_fd)
        os.close(directory_fd)
        raise


def dependency_ack_record(m, ready, anchor, ownership_sha):
    return {'formatVersion': 2, 'operation': m['operation'], 'profile': m['profile'], 'server': m['server'], 'task': m['task'],
        'unit': m['unit'], 'workerSourceSha256': m['workerSourceSha256'], 'invocationId': ready['invocationId'],
        'totalSeconds': m['totalSeconds'], 'runtimeSeconds': m['runtimeSeconds'], 'groupIdentity': ready['groupIdentity'],
        'eventsIdentity': anchor['eventsIdentity'], 'coreEventsFilesystemMagic': 1667723888, 'initialPopulated': 1,
        'freshReadMethod': 'PREAD_OFFSET_ZERO', 'cleanupOwnershipSha256': ownership_sha}


def dependency_validate_ack(m, ready, value, ownership_sha):
    if not isinstance(value, dict) or not dependency_identity_pair(value.get('eventsIdentity')) or not dependency_identity_pair(value.get('groupIdentity')):
        dependency_reject('DEPENDENCY_ACK_IDENTITY', True)
    expected = dependency_ack_record(m, ready, {'eventsIdentity': value['eventsIdentity']}, ownership_sha)
    if value != expected or set(value) != set(expected) or any(type(value[k]) is not int for k in
            ('formatVersion', 'totalSeconds', 'runtimeSeconds', 'coreEventsFilesystemMagic', 'initialPopulated')):
        dependency_reject('DEPENDENCY_ACK_IDENTITY', True)
    event = pathlib.Path('/sys/fs/cgroup/system.slice') / m['unit'] / 'cgroup.events'
    info = event.lstat()
    if not stat.S_ISREG(info.st_mode) or event.is_symlink() or [info.st_dev, info.st_ino] != value['eventsIdentity']:
        dependency_reject('DEPENDENCY_ACK_CORE_IDENTITY', True)
    return value


def dependency_worker(path):
    global DEPENDENCY_DEADLINE_NS, DEPENDENCY_WORKER_ADMITTED
    m = dependency_manifest(path)
    base = pathlib.Path(path).parent
    if pathlib.Path(globals().get('__file__', '/missing')) != base / 'worker.py':
        dependency_reject('DEPENDENCY_WORKER_SOURCE', True)
    DEPENDENCY_DEADLINE_NS = m['workerDeadlineMonotonicNs']
    facts = dependency_show(m['unit'], phase='WORKER_BEFORE_READY')
    invocation = dependency_unit_identity(m, facts, running=True)
    group_identity, members = dependency_group(m)
    if os.geteuid() != 0 or os.environ.get('INVOCATION_ID') != invocation or str(os.getpid()) not in members or facts['MainPID'] != str(os.getpid()):
        dependency_reject('DEPENDENCY_WORKER_NOT_IN_SERVICE', True)
    ready = dependency_ready_record(m, invocation, group_identity, os.getpid())
    dependency_write(base / 'ready.json', dependency_encode(ready))
    while not (base / 'ack.json').exists():
        dependency_remaining()
        time.sleep(min(0.05, dependency_remaining()))
    ownership_bytes = dependency_read(base / 'cleanup-ownership.json')
    dependency_validate_ownership(m, dependency_json(ownership_bytes), invocation)
    ownership_sha = hashlib.sha256(ownership_bytes).hexdigest()
    ack_bytes = dependency_read(base / 'ack.json')
    dependency_validate_ack(m, ready, dependency_json(ack_bytes), ownership_sha)
    dependency_unit_identity(m, dependency_show(m['unit'], phase='WORKER_AFTER_ACK'), ready, running=True)
    dependency_remaining()
    DEPENDENCY_WORKER_ADMITTED = True
    import contextlib
    import io
    with contextlib.redirect_stdout(io.StringIO()) as captured:
        dependency_transaction([m['profile'], m['operation']])
    line = captured.getvalue()
    if len(line.encode('utf-8')) > 16384 or not line.startswith('TOOLCHAIN_DEPENDENCIES=') or line.count('\n') != 1 or not line.endswith('\n'):
        dependency_reject('DEPENDENCY_RESULT_SCHEMA', True)
    result = dependency_json(line[len('TOOLCHAIN_DEPENDENCIES='):-1].encode('ascii'))
    envelope = {'formatVersion': 2, 'operation': m['operation'], 'workerSourceSha256': m['workerSourceSha256'],
        'invocationId': invocation, 'ackSha256': hashlib.sha256(ack_bytes).hexdigest(),
        'cleanupOwnershipSha256': ownership_sha, 'result': result}
    dependency_write(base / 'result.json', dependency_encode(envelope))


def dependency_result(m, envelope, ready, ack_sha, ownership_sha):
    keys = {'formatVersion', 'operation', 'workerSourceSha256', 'invocationId', 'ackSha256', 'cleanupOwnershipSha256', 'result'}
    if not isinstance(envelope, dict) or set(envelope) != keys or type(envelope['formatVersion']) is not int or envelope['formatVersion'] != 2 \
            or envelope['operation'] != m['operation'] or envelope['workerSourceSha256'] != m['workerSourceSha256'] \
            or envelope['invocationId'] != ready['invocationId'] or envelope['ackSha256'] != ack_sha or envelope['cleanupOwnershipSha256'] != ownership_sha:
        dependency_reject('DEPENDENCY_RESULT_IDENTITY', True)
    result = envelope['result']
    if not isinstance(result, dict) or set(result) != {'operation', 'ecosystem', 'manager', 'packages'} or result['operation'] != m['operation'] or result['ecosystem'] != m['profile']:
        dependency_reject('DEPENDENCY_RESULT_IDENTITY', True)
    return result


def dependency_completion_proof(anchor, observation):
    if anchor is None or anchor.get('coreEventsBound') is not True or anchor.get('coreEventsFilesystemMagic') != 1667723888 \
            or type(anchor.get('initialPopulated')) is not int or anchor['initialPopulated'] != 1 \
            or not dependency_identity_pair(anchor.get('groupIdentity')) or not dependency_identity_pair(anchor.get('eventsIdentity')) \
            or observation.get('method') != 'PREAD_OFFSET_ZERO':
        dependency_reject('DEPENDENCY_CORE_EVENTS_UNBOUND', True)
    if observation.get('kind') == 'VALUES' and type(observation.get('populated')) is int and observation['populated'] == 0:
        return {'proofKind': 'KERNEL_POPULATED_ZERO', 'finalPopulated': 0, 'readErrno': 0}
    if observation.get('kind') == 'ERRNO' and type(observation.get('errno')) is int and observation['errno'] == errno.ENODEV:
        return {'proofKind': 'KERNEL_CORE_EVENTS_RETIRED', 'finalPopulated': None, 'readErrno': 19}
    dependency_reject('DEPENDENCY_CORE_EVENTS_COMPLETION', True)


def dependency_stop_owned(m, base, ownership, collect=False):
    facts = dependency_show(m['unit'], phase='CLEANUP_OWNERSHIP')
    record = {'beforeStopFacts': facts, 'stopAttempted': False}
    if facts['LoadState'] == 'not-found':
        record['unitNotFound'] = True
        return record
    invocation = dependency_unit_identity(m, facts, ownership)
    ownership = dependency_retain_ownership(m, base, ownership, invocation)
    dependency_unit_identity(m, dependency_show(m['unit'], phase='REBOUND_BEFORE_STOP'), ownership)
    record['stopAttempted'] = True
    code, _ = dependency_control([dependency_tool('systemctl'), 'stop', '--', m['unit']], timeout=10)
    record['stopNativeExit'] = code
    after = dependency_show(m['unit'], phase='AFTER_STOP')
    if after['LoadState'] != 'not-found':
        dependency_unit_identity(m, after, ownership)
        if after['MainPID'] != '0' or after['ActiveState'] not in ('inactive', 'failed'):
            dependency_reject('DEPENDENCY_STOP_UNCONFIRMED', True)
        if collect:
            record['preResetOriginalFacts'] = dict(after)
            rebound = dependency_show(m['unit'], phase='REBOUND_BEFORE_RESET')
            dependency_unit_identity(m, rebound, ownership)
            if rebound['MainPID'] != '0' or rebound['ActiveState'] not in ('inactive', 'failed'):
                dependency_reject('DEPENDENCY_RESET_UNCONFIRMED', True)
            code, _ = dependency_control([dependency_tool('systemctl'), 'reset-failed', '--', m['unit']])
            record['resetFailedNativeExit'] = code
            for _ in range(16):
                after = dependency_show(m['unit'], phase='AFTER_RESET_COLLECTION')
                if after['LoadState'] == 'not-found':
                    break
                dependency_static_identity(m, after)
                if after['InvocationID'] not in ('', '0' * 32, invocation) or after['MainPID'] != '0' or after['ActiveState'] not in ('inactive', 'failed'):
                    dependency_reject('DEPENDENCY_RESET_UNCONFIRMED', True)
                time.sleep(min(0.1, dependency_remaining()))
            if after['LoadState'] != 'not-found':
                dependency_reject('DEPENDENCY_COLLECTION_UNCONFIRMED', True)
    record.update(finalFacts=after, unitNotFound=after['LoadState'] == 'not-found', stopCompleted=True)
    return record


def dependency_supervise(args):
    global DEPENDENCY_DEADLINE_NS
    frame = dependency_frame(args)
    if os.geteuid() != 0:
        dependency_reject('DEPENDENCY_REQUIRES_ROOT')
    started = time.monotonic_ns()
    DEPENDENCY_DEADLINE_NS = started + frame['totalSeconds'] * 1000000000
    worker_deadline = DEPENDENCY_DEADLINE_NS - 30000000000
    if not pathlib.Path('/sys/fs/cgroup/cgroup.controllers').is_file():
        dependency_reject('DEPENDENCY_CGROUP_V2_REQUIRED')
    unit = dependency_unit(frame['operation'])
    if dependency_show(unit, phase='ORIGINAL_UNIT_ABSENT')['LoadState'] != 'not-found':
        dependency_reject('DEPENDENCY_UNIT_COLLISION')
    root = dependency_directory(DEPENDENCY_OPERATIONS_ROOT, create=True)
    base = root / frame['operation']
    base.mkdir(mode=0o700)
    worker = DEPENDENCY_WORKER_SOURCE.encode('utf-8')
    m = {'formatVersion': 2, **frame, 'unit': unit, 'workerSourceSha256': hashlib.sha256(worker).hexdigest(),
         'deadlineMonotonicNs': DEPENDENCY_DEADLINE_NS, 'workerDeadlineMonotonicNs': worker_deadline,
         'runtimeSeconds': 0, 'exitType': 'cgroup', 'originalUnitAbsent': True}
    m['description'] = 'WTL_DEPENDENCY_2:' + frame['operation'] + ':' + m['workerSourceSha256']
    dependency_write(base / 'worker.py', worker)
    m['runtimeSeconds'] = (worker_deadline - time.monotonic_ns()) // 1000000000
    if m['runtimeSeconds'] < 1:
        dependency_reject('DEPENDENCY_BUDGET_BEFORE_SERVICE')
    dependency_write(base / 'manifest.json', dependency_encode(m))
    if time.monotonic_ns() + (m['runtimeSeconds'] + 30) * 1000000000 > DEPENDENCY_DEADLINE_NS:
        dependency_reject('DEPENDENCY_BUDGET_BEFORE_SERVICE')
    properties = ['Type=exec', 'ExitType=cgroup', 'User=root', 'Group=root', 'RemainAfterExit=yes', 'KillMode=control-group',
        'SendSIGKILL=yes', 'TimeoutStopSec=5s', 'TimeoutStartSec=5s', 'RuntimeMaxSec=' + str(m['runtimeSeconds']) + 's',
        'UMask=0077', 'Delegate=no', 'Slice=system.slice', 'WorkingDirectory=/', 'Description=' + m['description']]
    launch = [dependency_tool('systemd-run'), '--quiet', '--wait', '--pipe', '--unit=' + unit]
    launch += ['--property=' + prop for prop in properties]
    launch += ['--setenv=PATH=/usr/sbin:/usr/bin:/sbin:/bin', '--setenv=LC_ALL=C', '--setenv=DEBIAN_FRONTEND=noninteractive',
               '--setenv=NEEDRESTART_MODE=l', dependency_tool('python3'), '-I', '-S', str(base / 'worker.py'), '--dependency-worker', str(base / 'manifest.json')]
    process, ownership, ready, directory_fd, events_fd, anchor = None, None, None, None, None, None
    stopped, captured, startup = False, bytearray(), []
    observations = {'formatVersion': 2, 'operation': m['operation'], 'startup': startup}
    try:
        process = subprocess.Popen(launch, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            cwd='/', env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C'})
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                dependency_remaining()
                for key, _ in selector.select(0.05):
                    data = os.read(key.fileobj.fileno(), 8192)
                    if data:
                        captured.extend(data)
                        if len(captured) > 65536:
                            dependency_reject('DEPENDENCY_TRANSACTION_OUTPUT_LIMIT', True)
                    else:
                        selector.unregister(key.fileobj)
                ownership, facts = dependency_observe_startup(m, base, ownership, startup, observations)
                if ownership is not None and ready is None and (base / 'ready.json').exists():
                    ready = dependency_validate_ready(m, dependency_json(dependency_read(base / 'ready.json')), ownership['invocationId'])
                    directory_fd, events_fd, anchor = dependency_open_core_events(m, ready)
                    ownership_sha = hashlib.sha256(dependency_read(base / 'cleanup-ownership.json')).hexdigest()
                    ack = dependency_ack_record(m, ready, anchor, ownership_sha)
                    dependency_unit_identity(m, dependency_show(unit, phase='PRE_ACK_RUNNING_IDENTITY'), ready, running=True)
                    dependency_write(base / 'ack.json', dependency_encode(ack))
                    ack_sha = hashlib.sha256(dependency_encode(ack)).hexdigest()
                if (base / 'result.json').exists():
                    if ready is None or anchor is None:
                        dependency_reject('DEPENDENCY_ACK_REQUIRED', True)
                    result = dependency_result(m, dependency_json(dependency_read(base / 'result.json', 32768)), ready, ack_sha, ownership_sha)
                    dependency_unit_identity(m, facts, ownership)
                    if facts['ActiveState'] != 'active' or facts['SubState'] != 'exited' or facts['MainPID'] != '0':
                        if process.poll() is not None or facts['ActiveState'] in ('failed', 'inactive'):
                            dependency_reject('DEPENDENCY_COMPLETION_UNCONFIRMED', True)
                        continue
                    if facts['ExecMainStatus'] != '0' or facts['Result'] != 'success':
                        dependency_reject('DEPENDENCY_COMPLETION_UNCONFIRMED', True)
                    proof = dependency_completion_proof(anchor, dependency_events_read(events_fd))
                    observations['loadedCompletionFacts'] = dict(facts)
                    break
                if process.poll() is not None:
                    dependency_reject('DEPENDENCY_WORKER_UNCONFIRMED', True)
        observations['normalStop'] = dependency_stop_owned(m, base, ownership)
        if observations['normalStop'].get('stopAttempted') is not True or observations['normalStop'].get('stopCompleted') is not True:
            dependency_reject('DEPENDENCY_STOP_UNCONFIRMED', True)
        stopped = True
        tail, _ = process.communicate(timeout=min(5, dependency_remaining()))
        if process.returncode != 0 or len(captured) + len(tail) > 65536:
            dependency_reject('DEPENDENCY_LAUNCHER_UNCONFIRMED', True)
        result['lifecycle'] = {'formatVersion': 2, 'unit': unit, 'invocationId': ready['invocationId'],
            'workerSourceSha256': m['workerSourceSha256'], 'totalSeconds': m['totalSeconds'], 'runtimeSeconds': m['runtimeSeconds'],
            'stopSeconds': 5, 'reserveSeconds': 25, 'exitType': 'cgroup', 'server': m['server'], 'task': m['task'],
            'loadedCompletionObserved': True, 'ackVerified': True, **proof, **anchor, 'freshReadMethod': 'PREAD_OFFSET_ZERO',
            'stopCompleted': True, 'launcherWaitCompleted': True, 'launcherExitCode': 0,
            'ackSha256': ack_sha, 'cleanupOwnershipSha256': ownership_sha}
    except BaseException:
        observations.setdefault('lastObservationAtFailure', dependency_json(dependency_encode(DEPENDENCY_LAST_OBSERVATION)))
        raise
    finally:
        primary_failure = sys.exc_info()[0] is not None
        final_failure = None
        if not stopped:
            try:
                observations['failureCleanup'] = dependency_stop_owned(m, base, ownership, collect=True)
            except BaseException as error:
                observations['cleanupFailureType'] = type(error).__name__
                observations['lastCleanupObservation'] = dependency_json(dependency_encode(DEPENDENCY_LAST_OBSERVATION))
        try:
            if process is not None:
                try:
                    if process.poll() is None:
                        process.kill()
                    observations['clientFinalExit'] = process.wait(timeout=1)
                    observations['clientFinalWaitCompleted'] = True
                except BaseException as error:
                    observations['clientFinalWaitCompleted'] = False
                    observations['clientFinalWaitFailureType'] = type(error).__name__
                    if not primary_failure:
                        final_failure = error
        finally:
            resources = [('stdout', process.stdout if process is not None else None), ('events', events_fd), ('directory', directory_fd)]
            for name, resource in resources:
                if resource is not None:
                    try:
                        resource.close() if name == 'stdout' else os.close(resource)
                    except BaseException as error:
                        observations.setdefault('closeFailures', []).append({'resource': name, 'type': type(error).__name__})
                        if not primary_failure and final_failure is None:
                            final_failure = error
        observations['recent'] = DEPENDENCY_OBSERVATIONS
        try:
            dependency_write(base / 'observations.json', dependency_encode(observations))
        except (OSError, ValueError, DependencyFailure):
            pass  # Diagnostics cannot replace the original UNKNOWN or generate a success receipt.
        if final_failure is not None:
            raise final_failure
    dependency_write(base / 'receipt.json', dependency_encode(result))
    print('TOOLCHAIN_DEPENDENCIES=' + dependency_encode(result).decode('ascii'))
