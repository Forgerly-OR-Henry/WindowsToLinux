"""Operation-owned root package worker; no package subprocess exists outside its service."""
DEPENDENCY_DEADLINE_NS = None
DEPENDENCY_WORKER_ADMITTED = False
DEPENDENCY_STOP_SECONDS = 5
DEPENDENCY_RESERVE_SECONDS = 25


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


def dependency_control(args, timeout=3, allowed=(0,)):
    process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        cwd='/', env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C'})
    try:
        output, _ = process.communicate(timeout=min(timeout, dependency_remaining()))
        if process.returncode not in allowed or len(output) > 65536:
            dependency_reject('DEPENDENCY_MANAGER_UNCONFIRMED', True)
        return process.returncode, output.decode('utf-8', 'strict')
    finally:
        if process.poll() is None:
            process.kill()
        try:
            process.wait(timeout=1)
        finally:
            process.stdout.close()


def dependency_show(unit):
    names = ('LoadState', 'ActiveState', 'SubState', 'MainPID', 'ExecMainStatus', 'Result', 'InvocationID',
             'ControlGroup', 'Description', 'Transient', 'Type', 'User', 'Group', 'KillMode', 'SendSIGKILL',
             'RuntimeMaxUSec', 'TimeoutStopUSec', 'RemainAfterExit', 'Delegate')
    _, text = dependency_control([dependency_tool('systemctl'), 'show', '--no-pager',
        '--property=' + ','.join(names), unit], allowed=(0, 1, 4))
    result = {}
    for line in text.splitlines():
        key, sep, value = line.partition('=')
        if not sep or key in result or key not in names:
            dependency_reject('DEPENDENCY_UNIT_OBSERVATION', True)
        result[key] = value
    if set(result) != set(names):
        dependency_reject('DEPENDENCY_UNIT_OBSERVATION', True)
    return result


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


def dependency_unit_identity(manifest, facts, ready=None):
    expected = {'LoadState': 'loaded', 'Transient': 'yes', 'Description': manifest['description'],
        'ControlGroup': '/system.slice/' + manifest['unit'], 'Type': 'exec', 'User': 'root', 'Group': 'root',
        'KillMode': 'control-group', 'SendSIGKILL': 'yes', 'RemainAfterExit': 'yes', 'Delegate': 'no',
        'RuntimeMaxUSec': str(manifest['runtimeSeconds'] * 1000000), 'TimeoutStopUSec': '5000000'}
    # systemctl renders times in human units unless --value; accept only the exact canonical duration forms.
    for key in ('RuntimeMaxUSec', 'TimeoutStopUSec'):
        expected.pop(key)
    if any(facts.get(k) != v for k, v in expected.items()) or not re.fullmatch('[a-f0-9]{32}', facts.get('InvocationID', '')) or facts['InvocationID'] == '0' * 32:
        dependency_reject('DEPENDENCY_UNIT_IDENTITY', True)
    if dependency_duration(facts['RuntimeMaxUSec']) != manifest['runtimeSeconds'] or dependency_duration(facts['TimeoutStopUSec']) != DEPENDENCY_STOP_SECONDS:
        dependency_reject('DEPENDENCY_UNIT_BUDGET', True)
    if ready is not None and facts['InvocationID'] != ready['invocationId']:
        dependency_reject('DEPENDENCY_UNIT_IDENTITY', True)
    return facts['InvocationID']


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


def dependency_manifest(path):
    path = pathlib.Path(path)
    root = pathlib.Path(DEPENDENCY_OPERATIONS_ROOT)
    if path.name != 'manifest.json' or path.parent.parent != root or not re.fullmatch('[a-f0-9]{32}', path.parent.name):
        dependency_reject('DEPENDENCY_MANIFEST_PATH')
    value = dependency_json(dependency_read(path, 16384))
    keys = {'formatVersion', 'profile', 'operation', 'server', 'task', 'totalSeconds', 'runtimeSeconds',
            'deadlineMonotonicNs', 'workerDeadlineMonotonicNs', 'unit', 'description', 'workerSourceSha256'}
    if not isinstance(value, dict) or set(value) != keys or type(value['formatVersion']) is not int or value['formatVersion'] != 1 or value['operation'] != path.parent.name:
        dependency_reject('DEPENDENCY_MANIFEST', True)
    frame = dependency_frame([value['profile'], value['operation'], str(value['totalSeconds']), value['server'], value['task']])
    if any(type(value[k]) is not int or value[k] < 1 for k in ('totalSeconds', 'runtimeSeconds', 'deadlineMonotonicNs', 'workerDeadlineMonotonicNs')) \
            or value['runtimeSeconds'] > frame['totalSeconds'] - 30 or value['deadlineMonotonicNs'] - value['workerDeadlineMonotonicNs'] != 30000000000 \
            or value['unit'] != dependency_unit(value['operation']) \
            or not re.fullmatch('[a-f0-9]{64}', value['workerSourceSha256']) \
            or value['description'] != 'WTL_DEPENDENCY_1:' + value['operation'] + ':' + value['workerSourceSha256'] \
            or hashlib.sha256(dependency_read(path.parent / 'worker.py')).hexdigest() != value['workerSourceSha256']:
        dependency_reject('DEPENDENCY_MANIFEST', True)
    return value


def dependency_worker(path):
    global DEPENDENCY_DEADLINE_NS, DEPENDENCY_WORKER_ADMITTED
    manifest = dependency_manifest(path)
    if pathlib.Path(globals().get('__file__', '/missing')) != pathlib.Path(path).parent / 'worker.py':
        dependency_reject('DEPENDENCY_WORKER_SOURCE', True)
    DEPENDENCY_DEADLINE_NS = manifest['workerDeadlineMonotonicNs']
    invocation = dependency_unit_identity(manifest, dependency_show(manifest['unit']))
    group_identity, members = dependency_group(manifest)
    if os.geteuid() != 0 or os.environ.get('INVOCATION_ID') != invocation or str(os.getpid()) not in members \
            or dependency_show(manifest['unit'])['MainPID'] != str(os.getpid()):
        dependency_reject('DEPENDENCY_WORKER_NOT_IN_SERVICE', True)
    ready = {'operation': manifest['operation'], 'workerSourceSha256': manifest['workerSourceSha256'],
             'invocationId': invocation, 'groupIdentity': group_identity}
    dependency_write(pathlib.Path(path).parent / 'ready.json', dependency_encode(ready))
    DEPENDENCY_WORKER_ADMITTED = True
    import contextlib
    import io
    with contextlib.redirect_stdout(io.StringIO()) as captured:
        dependency_transaction([manifest['profile'], manifest['operation']])
    line = captured.getvalue()
    if len(line.encode('utf-8')) > 16384 or not line.startswith('TOOLCHAIN_DEPENDENCIES=') or line.count('\n') != 1 or not line.endswith('\n'):
        dependency_reject('DEPENDENCY_RESULT_SCHEMA', True)
    result = dependency_json(line[len('TOOLCHAIN_DEPENDENCIES='):-1].encode('ascii'))
    dependency_write(pathlib.Path(path).parent / 'result.json', dependency_encode(result))


def dependency_supervise(args):
    global DEPENDENCY_DEADLINE_NS
    frame = dependency_frame(args)
    if os.geteuid() != 0:
        dependency_reject('DEPENDENCY_REQUIRES_ROOT')
    started = time.monotonic_ns()
    DEPENDENCY_DEADLINE_NS = started + frame['totalSeconds'] * 1000000000
    worker_deadline = started + (frame['totalSeconds'] - 30) * 1000000000
    if not pathlib.Path('/sys/fs/cgroup/cgroup.controllers').is_file():
        dependency_reject('DEPENDENCY_CGROUP_V2_REQUIRED')
    # All operations before worker admission are fixed manager/filesystem checks, never package commands.
    unit = dependency_unit(frame['operation'])
    if dependency_show(unit)['LoadState'] != 'not-found':
        dependency_reject('DEPENDENCY_UNIT_COLLISION')
    root = dependency_directory(DEPENDENCY_OPERATIONS_ROOT, create=True)
    base = root / frame['operation']
    base.mkdir(mode=0o700)  # Exclusive claim. Existing/old operations are never adopted.
    worker = DEPENDENCY_WORKER_SOURCE.encode('utf-8')
    manifest = {'formatVersion': 1, **frame, 'unit': unit, 'workerSourceSha256': hashlib.sha256(worker).hexdigest(),
        'deadlineMonotonicNs': DEPENDENCY_DEADLINE_NS, 'workerDeadlineMonotonicNs': worker_deadline,
        'runtimeSeconds': 0}
    manifest['description'] = 'WTL_DEPENDENCY_1:' + frame['operation'] + ':' + manifest['workerSourceSha256']
    dependency_write(base / 'worker.py', worker)
    manifest['runtimeSeconds'] = (worker_deadline - time.monotonic_ns()) // 1000000000
    if manifest['runtimeSeconds'] < 1:
        dependency_reject('DEPENDENCY_BUDGET_BEFORE_SERVICE')
    dependency_write(base / 'manifest.json', dependency_encode(manifest))
    if time.monotonic_ns() + (manifest['runtimeSeconds'] + 30) * 1000000000 > DEPENDENCY_DEADLINE_NS:
        dependency_reject('DEPENDENCY_BUDGET_BEFORE_SERVICE')  # Durable file preparation cannot widen the reviewed total.
    properties = ['Type=exec', 'User=root', 'Group=root', 'RemainAfterExit=yes', 'KillMode=control-group',
        'SendSIGKILL=yes', 'TimeoutStopSec=5s', 'TimeoutStartSec=5s', 'RuntimeMaxSec=' + str(manifest['runtimeSeconds']) + 's',
        'UMask=0077', 'Delegate=no', 'Slice=system.slice', 'WorkingDirectory=/', 'Description=' + manifest['description']]
    launch = [dependency_tool('systemd-run'), '--quiet', '--wait', '--pipe', '--unit=' + unit]
    launch += ['--property=' + prop for prop in properties]
    launch += ['--setenv=PATH=/usr/sbin:/usr/bin:/sbin:/bin', '--setenv=LC_ALL=C',
               '--setenv=DEBIAN_FRONTEND=noninteractive', '--setenv=NEEDRESTART_MODE=l',
               dependency_tool('python3'), '-I', '-S', str(base / 'worker.py'), '--dependency-worker', str(base / 'manifest.json')]
    process = subprocess.Popen(launch, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd='/',
        env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C'})
    ready, completion, stopped, captured = None, None, False, bytearray()
    try:
        # --wait with RemainAfterExit must NOT be waited before observing/empty-checking/stopping the service.
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while completion is None:
                dependency_remaining()
                for key, _ in selector.select(0.05):
                    data = os.read(key.fileobj.fileno(), 8192)
                    if data:
                        captured.extend(data)
                        if len(captured) > 65536:
                            dependency_reject('DEPENDENCY_TRANSACTION_OUTPUT_LIMIT', True)
                    else:
                        selector.unregister(key.fileobj)
                if ready is None and (base / 'ready.json').exists():
                    ready = dependency_json(dependency_read(base / 'ready.json', 4096))
                    if not isinstance(ready, dict) or set(ready) != {'operation', 'workerSourceSha256', 'invocationId', 'groupIdentity'} or ready['operation'] != frame['operation'] or ready['workerSourceSha256'] != manifest['workerSourceSha256'] \
                            or not isinstance(ready['groupIdentity'], list) or len(ready['groupIdentity']) != 2 or any(type(v) is not int or v < 1 for v in ready['groupIdentity']):
                        dependency_reject('DEPENDENCY_READY_IDENTITY', True)
                if (base / 'result.json').exists():
                    facts = dependency_show(unit)
                    dependency_unit_identity(manifest, facts, ready)
                    if ready is None or facts['ActiveState'] != 'active' or facts['SubState'] != 'exited' or facts['MainPID'] != '0':
                        if process.poll() is not None:
                            dependency_reject('DEPENDENCY_COMPLETION_UNCONFIRMED', True)
                        continue
                    if facts['ExecMainStatus'] != '0' or facts['Result'] != 'success':
                        dependency_reject('DEPENDENCY_COMPLETION_UNCONFIRMED', True)
                    identity, _ = dependency_group(manifest, empty=True)
                    if identity != ready['groupIdentity']:
                        dependency_reject('DEPENDENCY_CGROUP_IDENTITY', True)
                    completion = {'invocationId': ready['invocationId'], 'groupIdentity': identity}
                elif process.poll() is not None:
                    dependency_reject('DEPENDENCY_WORKER_UNCONFIRMED', True)
        dependency_control([dependency_tool('systemctl'), 'stop', unit], timeout=10)
        stopped = True
        tail, _ = process.communicate(timeout=min(5, dependency_remaining()))
        if process.returncode != 0 or len(captured) + len(tail) > 65536:
            dependency_reject('DEPENDENCY_LAUNCHER_UNCONFIRMED', True)
        after = dependency_show(unit)
        if after['LoadState'] != 'not-found':
            dependency_unit_identity(manifest, after, ready)
            if after['ActiveState'] not in ('inactive', 'failed') or after['MainPID'] != '0':
                dependency_reject('DEPENDENCY_STOP_UNCONFIRMED', True)
        # not-found is accepted only after the independent, loaded completion+real empty-cgroup proof above.
        if (pathlib.Path('/sys/fs/cgroup/system.slice') / unit).exists():
            identity, _ = dependency_group(manifest, empty=True)
            if identity != completion['groupIdentity']:
                dependency_reject('DEPENDENCY_CGROUP_IDENTITY', True)
        result = dependency_json(dependency_read(base / 'result.json', 16384))
        if not isinstance(result, dict) or set(result) != {'operation', 'ecosystem', 'manager', 'packages'} or result['operation'] != frame['operation'] or result['ecosystem'] != frame['profile']:
            dependency_reject('DEPENDENCY_RESULT_IDENTITY', True)
        result['lifecycle'] = {'formatVersion': 1, 'unit': unit, 'invocationId': completion['invocationId'],
            'workerSourceSha256': manifest['workerSourceSha256'], 'totalSeconds': frame['totalSeconds'],
            'runtimeSeconds': manifest['runtimeSeconds'], 'stopSeconds': 5, 'reserveSeconds': 25,
            'loadedCompletionObserved': True, 'recursiveCgroupEmptyObserved': True, 'stopCompleted': True,
            'server': frame['server'], 'task': frame['task']}
        dependency_write(base / 'receipt.json', dependency_encode(result))
        print('TOOLCHAIN_DEPENDENCIES=' + dependency_encode(result).decode('ascii'))
    finally:
        if ready is not None and not stopped:
            try:
                dependency_unit_identity(manifest, dependency_show(unit), ready)
                dependency_control([dependency_tool('systemctl'), 'stop', unit], timeout=10)
            except (DependencyFailure, OSError, ValueError, subprocess.TimeoutExpired):
                pass  # Failure remains UNKNOWN; independent RuntimeMaxSec is not an observed stop receipt.
        if process.poll() is None:
            process.kill()  # Only the systemd-run client, never an unrelated package process.
        try:
            process.wait(timeout=1)
        finally:
            process.stdout.close()
