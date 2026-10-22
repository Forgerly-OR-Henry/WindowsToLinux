"""Original-operation evidence and finite cleanup; never a worker or installation replay."""
import contextlib
import io
import struct
import uuid

AGENT_TRACKED_CONTEXT = None
AGENT_TRACKED_FIELDS = ('formatVersion', 'taskId', 'serverId', 'fingerprintSha256', 'remoteLayout', 'phase',
    'operationId', 'version', 'sourceSha256', 'executedSourceSha256', 'invocationKind', 'originalArgv')
AGENT_TRACKED_VERBS = ('prepare-tracked', 'bind-tracked', 'observe-tracked', 'cleanup-tracked')
AGENT_TRACKED_RESOURCES = ('unit', 'cgroup', 'image', 'loop', 'mount', 'placeholder',
    'promotionTemporary', 'bindingTemporary', 'children')


def agent_tracked_check(condition, code='RESOURCE_UNCONFIRMED'):
    if not condition:
        raise PreparationFailure('unknown', code)


def agent_tracked_json(raw, maximum=131072):
    def pairs(items):
        result = {}
        for key, value in items:
            agent_tracked_check(key not in result, 'REQUEST_INVALID')
            result[key] = value
        return result
    agent_tracked_check(isinstance(raw, bytes) and 0 < len(raw) <= maximum, 'REQUEST_INVALID')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs,
        parse_constant=lambda _: agent_tracked_check(False, 'REQUEST_INVALID'))


def agent_tracked_encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def agent_tracked_layout(encoded):
    value = agent_tracked_json(encoded.encode(), 4096)
    names = ('helperDirectory', 'applicationsRoot', 'stateRoot', 'configurationRoot', 'dataRoot')
    agent_tracked_check(isinstance(value, dict) and list(value) == ['formatVersion', 'remote']
        and type(value['formatVersion']) is int and value['formatVersion'] == 1
        and isinstance(value['remote'], dict) and list(value['remote']) == list(names), 'REQUEST_INVALID')
    for path in value['remote'].values():
        agent_tracked_check(isinstance(path, str) and re.fullmatch('/[A-Za-z0-9._+/-]{1,2047}', path)
            and all(part not in ('', '.', '..') for part in path.split('/')[1:]), 'REQUEST_INVALID')
    agent_tracked_check(agent_tracked_encode(value).decode() == encoded
        and str(ROOT) == value['remote']['helperDirectory'] + '/toolchains', 'IDENTITY_MISMATCH')
    return hashlib.sha256(encoded.encode()).hexdigest()


def agent_tracked_request(args, raw):
    agent_tracked_check(len(args) == 4 and args[0] in AGENT_TRACKED_VERBS, 'REQUEST_INVALID')
    verb, task, operation, digest = args
    value = agent_tracked_json(raw)
    agent_tracked_check(isinstance(value, dict) and list(value) == list(AGENT_TRACKED_FIELDS)
        and type(value['formatVersion']) is int and value['formatVersion'] == 1
        and agent_tracked_encode(value) == raw and hashlib.sha256(raw).hexdigest() == digest, 'REQUEST_INVALID')
    for key in AGENT_TRACKED_FIELDS[1:-1]:
        agent_tracked_check(isinstance(value[key], str) and not any(ord(c) < 32 or ord(c) == 127 for c in value[key]), 'REQUEST_INVALID')
    agent_tracked_check(re.fullmatch('[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}', task)
        and value['taskId'] == task and re.fullmatch('[a-f0-9]{32}', operation)
        and value['operationId'] == operation and 0 < len(value['serverId'].encode()) <= 512
        and value['phase'] in ('PREPARE', 'BIND')
        and value['invocationKind'] in ('INSTALLED', 'EXISTING_INLINE')
        and (value['invocationKind'] != 'EXISTING_INLINE' or value['phase'] == 'PREPARE'), 'REQUEST_INVALID')
    for key in ('fingerprintSha256', 'version', 'sourceSha256', 'executedSourceSha256'):
        agent_tracked_check(agent_hash(value[key]), 'REQUEST_INVALID')
    layout_sha = agent_tracked_layout(value['remoteLayout'])
    argv = value['originalArgv']
    agent_tracked_check(isinstance(argv, list) and all(isinstance(item, str)
        and not any(ord(c) < 32 or ord(c) == 127 for c in item) for item in argv), 'REQUEST_INVALID')
    if value['phase'] == 'PREPARE':
        agent_tracked_check(len(argv) == 8 and argv[0] == 'prepare' and argv[6] == operation
            and argv[1] in AGENT_ECOSYSTEMS and argv[2] in SHIPPED_CATALOG.get(argv[1], [])
            and re.fullmatch('[1-9][0-9]{1,3}', argv[4]) and 30 <= int(argv[4]) <= 7200
            and agent_java_home(argv[5]), 'REQUEST_INVALID')
        resolution_decode(argv[7], argv[1], argv[2], argv[3], operation, EXPECTED_RESOLVER_SOURCE_SHA256)
    else:
        agent_tracked_check(len(argv) == 4 and argv[0] == 'bind' and argv[3] == operation
            and re.fullmatch('[1-9][0-9]{1,3}', argv[2]) and 30 <= int(argv[2]) <= 7200, 'REQUEST_INVALID')
        payload = base64.b64decode(argv[1], validate=True)
        agent_tracked_check(0 < len(payload) <= 65536 and base64.b64encode(payload).decode() == argv[1]
            and payload.endswith(b'\n') and all(32 <= b < 127 or b in (9, 10) for b in payload), 'REQUEST_INVALID')
    binding = globals().get('AGENT_TRACKED_STORE_BINDING')
    if binding is not None:
        agent_tracked_check(binding == dict(version=value['version'], sourceSha256=value['sourceSha256'], layoutSha256=layout_sha), 'IDENTITY_MISMATCH')
    if verb in ('prepare-tracked', 'bind-tracked'):
        agent_tracked_check(value['phase'] == ('PREPARE' if verb == 'prepare-tracked' else 'BIND')
            and isinstance(PROGRAM_SOURCE, bytes)
            and hashlib.sha256(PROGRAM_SOURCE).hexdigest() == value['executedSourceSha256']
            and (value['invocationKind'] == 'EXISTING_INLINE' and binding is None
                 or value['invocationKind'] == 'INSTALLED' and binding is not None
                 and value['sourceSha256'] == value['executedSourceSha256']), 'IDENTITY_MISMATCH')
    else:
        agent_tracked_check(binding is not None, 'IDENTITY_MISMATCH')
    return value


def agent_tracked_stat(path):
    try:
        info = path.lstat()
    except FileNotFoundError:
        return None
    agent_tracked_check(not stat.S_ISLNK(info.st_mode), 'RESOURCE_CHANGED')
    return dict(identity=[info.st_dev, info.st_ino], uid=info.st_uid, gid=info.st_gid,
        mode=stat.S_IMODE(info.st_mode), links=info.st_nlink, size=info.st_size,
        directory=stat.S_ISDIR(info.st_mode), regular=stat.S_ISREG(info.st_mode))


def agent_tracked_owned(path, directory=False, identity=None):
    value = agent_tracked_stat(path)
    agent_tracked_check(value is not None and value['uid'] == 0 and value['gid'] == 0
        and not value['mode'] & 0o022 and value['directory' if directory else 'regular']
        and (directory or value['links'] == 1) and (identity is None or value['identity'] == identity), 'RESOURCE_CHANGED')
    return value


def agent_identity_file(path):
    return agent_tracked_owned(path)['identity']


def agent_tracked_sync(path):
    fd = os.open(str(path), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def agent_tracked_write(path, raw, mode=0o444):
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, mode)
    try:
        view = memoryview(raw)
        while view:
            amount = os.write(fd, view)
            agent_tracked_check(amount > 0)
            view = view[amount:]
        os.fchmod(fd, mode)
        os.fsync(fd)
    finally:
        os.close(fd)
    agent_tracked_sync(path.parent)


def agent_tracked_read(path, maximum=131072):
    before = agent_tracked_owned(path)
    agent_tracked_check(0 < before['size'] <= maximum)
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        info = os.fstat(fd)
        agent_tracked_check([info.st_dev, info.st_ino] == before['identity'])
        chunks = []
        while True:
            block = os.read(fd, min(65536, maximum + 1 - sum(map(len, chunks))))
            if not block:
                break
            chunks.append(block)
            agent_tracked_check(sum(map(len, chunks)) <= maximum)
        raw = b''.join(chunks)
        after = os.fstat(fd)
        agent_tracked_check(len(raw) == info.st_size and agent_same_file(info, after))
        return raw
    finally:
        os.close(fd)


def agent_tracked_replace(path, value):
    raw = agent_tracked_encode(value)
    agent_tracked_check(len(raw) <= 131072)
    temporary = path.parent / ('.creation-state-' + uuid.uuid4().hex)
    agent_tracked_write(temporary, raw)
    if path.exists() or path.is_symlink():
        agent_tracked_owned(path)
    os.replace(str(temporary), str(path))
    agent_tracked_sync(path.parent)


def agent_tracked_save():
    context = AGENT_TRACKED_CONTEXT
    agent_tracked_check(context is not None and not context.get('readOnly', False))
    context['state']['revision'] += 1
    agent_tracked_replace(context['base'] / 'creation-state.json', context['state'])


def agent_tracked_step(name, value=None, observed=False):
    context = AGENT_TRACKED_CONTEXT
    if context is None:
        return
    agent_tracked_check(not context.get('readOnly', False))
    previous = context['state']['steps'].get(name)
    if observed:
        agent_tracked_check(previous is not None)
    else:
        agent_tracked_check(previous is None, 'RESOURCE_CHANGED')
    context['state']['steps'][name] = dict(state='OBSERVED' if observed else 'INTENT', value=value or {})
    agent_tracked_save()


def agent_tracked_boot():
    value = pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    agent_tracked_check(str(uuid.UUID(value)) == value)
    return value


def agent_tracked_start(pid):
    try:
        raw = pathlib.Path('/proc/' + str(pid) + '/stat').read_text()
    except FileNotFoundError:
        return None
    fields = raw[raw.rfind(')') + 2:].split()
    agent_tracked_check(len(fields) >= 20 and fields[19].isdigit())
    return fields[19]


def agent_tracked_spawn():
    name = 'CHILD_SPAWN_' + str(len(AGENT_TRACKED_CONTEXT['state']['children']))
    agent_tracked_step(name, {'boot': agent_tracked_boot()})
    return name


def agent_tracked_group_members(group):
    members = []
    for path in pathlib.Path('/proc').iterdir():
        if not path.name.isdigit():
            continue
        try:
            raw = (path / 'stat').read_text()
        except FileNotFoundError:
            continue
        fields = raw[raw.rfind(')') + 2:].split()
        agent_tracked_check(len(fields) >= 20)
        if fields[2] == str(group):
            members.append(int(path.name))
    return members


def agent_tracked_child(process, ended=False, spawn=None):
    context = AGENT_TRACKED_CONTEXT
    if context is None or context.get('readOnly', False):
        return
    children = context['state']['children']
    if ended:
        for child in children:
            if child['pid'] == process.pid and not child['ended']:
                child['ended'] = True
        agent_tracked_save()
        return
    start = agent_tracked_start(process.pid)
    agent_tracked_check(start is not None or process.poll() is not None, 'ACTIVE_CHILD')
    agent_tracked_check(len(children) < 2048)
    children.append(dict(pid=process.pid, group=process.pid, start=start, boot=agent_tracked_boot(), ended=process.poll() is not None))
    agent_tracked_check(spawn is not None)
    agent_tracked_step(spawn, {'boot': agent_tracked_boot(), 'pid': process.pid}, True)
    agent_tracked_save()


def agent_tracked_open(value, raw):
    base = agent_operation(value['operationId'])
    agent_tracked_check(not base.exists() and not base.is_symlink(), 'IDENTITY_MISMATCH')
    for path in (ROOT, ROOT / 'versions', ROOT / 'bindings', ROOT / 'work'):
        owned_directory(path)
        path.mkdir(mode=0o700 if path.name == 'work' else 0o755, parents=True, exist_ok=True)
    base.mkdir(mode=0o755)
    agent_tracked_write(base / 'request.json', raw)
    agent_tracked_write(base / 'controller.py', PROGRAM_SOURCE)
    agent_tracked_write(base / 'identity.json', agent_encode(dict(operation=value['operationId'], uid=0, gid=0,
        programSha256=value['executedSourceSha256'])))
    agent_tracked_write(base / 'ready.json', b'')
    creation = dict(formatVersion=1, operationId=value['operationId'], requestSha256=hashlib.sha256(raw).hexdigest(),
        executedSourceSha256=value['executedSourceSha256'], baseIdentity=agent_tracked_owned(base, True)['identity'],
        controllerIdentity=agent_tracked_owned(base / 'controller.py')['identity'],
        readyIdentity=agent_tracked_owned(base / 'ready.json')['identity'], boot=agent_tracked_boot())
    agent_tracked_write(base / 'creation.json', agent_tracked_encode(creation))
    binding = hashlib.sha256(base64.b64decode(value['originalArgv'][1], validate=True)).hexdigest() if value['phase'] == 'BIND' else None
    state = dict(formatVersion=1, operationId=value['operationId'], requestSha256=creation['requestSha256'], revision=0,
        executionStatus='UNKNOWN', steps={}, children=[], selection=None, bindingSha256=binding,
        cleanupStarted=False, cleanupVerified=False)
    agent_tracked_replace(base / 'creation-state.json', state)
    return base, creation, state


def agent_tracked_load(value, raw):
    base = agent_operation(value['operationId'])
    if not base.exists():
        raise PreparationFailure('unknown', 'OPERATION_MISSING')
    if not (base / 'creation.json').exists():
        raise PreparationFailure('unknown', 'LEGACY_PROOF_MISSING')
    creation_raw = agent_tracked_read(base / 'creation.json')
    creation = agent_tracked_json(creation_raw)
    agent_tracked_check(isinstance(creation, dict) and set(creation) == {'formatVersion', 'operationId', 'requestSha256',
        'executedSourceSha256', 'baseIdentity', 'controllerIdentity', 'readyIdentity', 'boot'}
        and creation['formatVersion'] == 1 and creation['operationId'] == value['operationId']
        and creation['requestSha256'] == hashlib.sha256(raw).hexdigest()
        and creation['executedSourceSha256'] == value['executedSourceSha256']
        and agent_tracked_read(base / 'request.json') == raw, 'IDENTITY_MISMATCH')
    agent_tracked_owned(base, True, creation['baseIdentity'])
    agent_tracked_owned(base / 'controller.py', identity=creation['controllerIdentity'])
    agent_tracked_check(hashlib.sha256(agent_tracked_read(base / 'controller.py', AGENT_MAX_PROGRAM)).hexdigest()
        == value['executedSourceSha256'], 'IDENTITY_MISMATCH')
    agent_tracked_owned(base / 'ready.json', identity=creation['readyIdentity'])
    state = agent_tracked_json(agent_tracked_read(base / 'creation-state.json'))
    fields = {'formatVersion', 'operationId', 'requestSha256', 'revision', 'executionStatus', 'steps', 'children',
        'selection', 'bindingSha256', 'cleanupStarted', 'cleanupVerified'}
    agent_tracked_check(isinstance(state, dict) and set(state) == fields and state['formatVersion'] == 1
        and state['operationId'] == value['operationId'] and state['requestSha256'] == creation['requestSha256']
        and type(state['revision']) is int and state['revision'] >= 0
        and state['executionStatus'] in ('UNKNOWN', 'SUCCEEDED', 'FAILED') and isinstance(state['steps'], dict)
        and isinstance(state['children'], list) and type(state['cleanupStarted']) is bool
        and type(state['cleanupVerified']) is bool, 'IDENTITY_MISMATCH')
    return base, creation, state


def agent_tracked_loop(device):
    import fcntl
    agent_tracked_check(re.fullmatch('/dev/loop[0-9]+', device) is not None)
    fd = os.open(device, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        info = os.fstat(fd)
        agent_tracked_check(stat.S_ISBLK(info.st_mode) and info.st_uid == 0, 'RESOURCE_CHANGED')
        try:
            data = fcntl.ioctl(fd, 0x4C05, bytes(232))  # Linux LOOP_GET_STATUS64, x86-64 controller boundary.
        except OSError as error:
            if error.errno == errno.ENXIO:
                return None
            raise
        backing, inode, _, offset, limit = struct.unpack_from('=QQQQQ', data)
        return dict(device=device, rdev=info.st_rdev, backingIdentity=[backing, inode], offset=offset, sizeLimit=limit)
    finally:
        os.close(fd)


def agent_tracked_loop_candidate(device, size_limit):
    agent_tracked_check(re.fullmatch('/dev/loop[0-9]+', device) is not None)
    info = os.stat(device, follow_symlinks=False)
    agent_tracked_check(stat.S_ISBLK(info.st_mode) and info.st_uid == 0
        and agent_tracked_loop(device) is None, 'RESOURCE_CHANGED')
    return dict(device=device, rdev=info.st_rdev, sizeLimit=size_limit)


def agent_tracked_capture_mount(path):
    mounts = [item for item in agent_tracked_mounts() if item['target'] == str(path)]
    loop = agent_tracked_step_value(AGENT_TRACKED_CONTEXT['state'], 'LOOP_ATTACH')
    agent_tracked_check(len(mounts) == 1 and mounts[0]['rdev'] == loop['rdev']
        and mounts[0]['filesystem'] == 'ext4' and mounts[0]['root'] == '/'
        and {'nodev', 'nosuid'} <= set(mounts[0]['options']), 'RESOURCE_CHANGED')
    return dict(mountId=mounts[0]['id'], identity=agent_tracked_owned(path, True)['identity'])


def agent_tracked_mounts():
    def unescape(text):
        return re.sub(r'\\([0-7]{3})', lambda found: chr(int(found[1], 8)), text)
    result = []
    for line in pathlib.Path('/proc/self/mountinfo').read_text().splitlines():
        left, right = line.split(' - ', 1)
        fields, tail = left.split(), right.split()
        agent_tracked_check(len(fields) >= 6 and len(tail) == 3)
        major, minor = fields[2].split(':')
        result.append(dict(id=int(fields[0]), rdev=os.makedev(int(major), int(minor)), root=unescape(fields[3]),
            target=unescape(fields[4]), options=fields[5].split(','), filesystem=tail[0], source=unescape(tail[1])))
    return result


def agent_tracked_unit(state):
    context = AGENT_TRACKED_CONTEXT
    operation, base = context['recipe']['operationId'], context['base']
    keys = ('Id', 'LoadState', 'ActiveState', 'InvocationID', 'ControlGroup', 'User', 'Group', 'DynamicUser', 'UID', 'GID', 'MainPID')
    code, output = agent_os([agent_tool('systemctl'), 'show', '--all'] + ['--property=' + key for key in keys]
        + [agent_unit(operation)], allow_failure=True, timeout=15)
    values = {}
    for line in output.decode().splitlines():
        key, separator, item = line.partition('=')
        agent_tracked_check(separator and key in keys and key not in values)
        values[key] = item
    agent_tracked_check(values.get('Id') == agent_unit(operation))
    if values.get('LoadState') == 'not-found' and values.get('ActiveState') == 'inactive':
        return 'ABSENT', None
    agent_tracked_check(not code and set(values) == set(keys) and values['LoadState'] == 'loaded')
    ready_info = agent_tracked_owned(base / 'ready.json', identity=context['creation']['readyIdentity'])
    agent_tracked_check(ready_info['size'] > 0)
    ready = agent_tracked_json(agent_tracked_read(base / 'ready.json', 1024))
    agent_tracked_check(set(ready) == {'operation', 'programSha256', 'uid', 'gid', 'invocationId'}
        and ready['operation'] == operation and ready['programSha256'] == context['recipe']['executedSourceSha256']
        and type(ready['uid']) is int and type(ready['gid']) is int and ready['uid'] > 0 and ready['gid'] > 0
        and re.fullmatch('[a-f0-9]{32}', ready['invocationId'])
        and values['InvocationID'] == ready['invocationId'] and values['DynamicUser'] == 'yes'
        and values['User'] == agent_user(operation) and values['Group'] == agent_user(operation)
        and values['ControlGroup'] in ('', '/system.slice/' + agent_unit(operation)), 'RESOURCE_CHANGED')
    active = values['ActiveState'] in ('active', 'activating', 'deactivating')
    agent_tracked_check(active or values['ActiveState'] in ('inactive', 'failed'))
    if active:
        agent_tracked_check(values['UID'] == str(ready['uid']) and values['GID'] == str(ready['gid'])
            and values['ControlGroup'] == '/system.slice/' + agent_unit(operation), 'RESOURCE_CHANGED')
    return ('OWNED_PRESENT' if active else 'QUIESCENT'), ready


def agent_tracked_group(state, unit):
    path = pathlib.Path('/sys/fs/cgroup/system.slice') / agent_unit(AGENT_TRACKED_CONTEXT['recipe']['operationId'])
    if not path.exists():
        return 'ABSENT', None
    identity = agent_tracked_owned(path, True)['identity']
    original = state['steps'].get('UNIT_READY', {}).get('value', {}).get('groupIdentity')
    agent_tracked_check(original is None or identity == original, 'RESOURCE_CHANGED')
    populated = False
    for directory, names, files in os.walk(path, followlinks=False):
        for name in names:
            agent_tracked_owned(pathlib.Path(directory) / name, True)
        if 'cgroup.procs' in files:
            members = (pathlib.Path(directory) / 'cgroup.procs').read_text().split()
            agent_tracked_check(all(re.fullmatch('[1-9][0-9]*', pid) for pid in members))
            populated = populated or bool(members)
    agent_tracked_check(agent_tracked_owned(path, True)['identity'] == identity, 'RESOURCE_CHANGED')
    agent_tracked_check(not populated or unit is not None)
    return ('OWNED_PRESENT' if populated else 'QUIESCENT'), identity


def agent_tracked_step_value(state, name):
    record = state['steps'].get(name)
    if record is None:
        return None
    agent_tracked_check(isinstance(record, dict) and set(record) == {'state', 'value'}
        and record['state'] in ('INTENT', 'OBSERVED') and isinstance(record['value'], dict))
    return record['value']


def agent_tracked_observe():
    context = AGENT_TRACKED_CONTEXT
    state, base, recipe = context['state'], context['base'], context['recipe']
    resources = dict.fromkeys(AGENT_TRACKED_RESOURCES, 'ABSENT')
    facts, retained = {}, []
    resources['unit'], facts['unit'] = agent_tracked_unit(state)
    resources['cgroup'], facts['cgroup'] = agent_tracked_group(state, facts['unit'])
    for child in state['children']:
        agent_tracked_check(set(child) == {'pid', 'group', 'start', 'boot', 'ended'} and type(child['pid']) is int
            and child['pid'] > 0 and child['group'] == child['pid'] and type(child['ended']) is bool)
        if child['boot'] == agent_tracked_boot():
            agent_tracked_check(not agent_tracked_group_members(child['group']), 'ACTIVE_CHILD')
    for name, step in state['steps'].items():
        if name.startswith('CHILD_SPAWN_'):
            agent_tracked_check(step.get('state') == 'OBSERVED', 'ACTIVE_CHILD')
    resources['children'] = 'QUIESCENT'
    image = agent_tracked_stat(base / 'volume.ext4')
    image_step = agent_tracked_step_value(state, 'IMAGE_CREATE')
    if image is not None:
        agent_tracked_check(image_step is not None and image['regular'] and image['uid'] == 0
            and image['gid'] == 0 and image['links'] == 1 and not image['mode'] & 0o077, 'RESOURCE_CHANGED')
        original = image_step.get('identity')
        agent_tracked_check(image['identity'] == original if original is not None else image['size'] == 0, 'RESOURCE_CHANGED')
        resources['image'], facts['image'] = 'OWNED_PRESENT', image
    loop_step = agent_tracked_step_value(state, 'LOOP_ATTACH')
    device = loop_step.get('device') if loop_step else None
    loop = agent_tracked_loop(device) if device else None
    if loop is not None:
        agent_tracked_check(image is not None and loop['backingIdentity'] == image['identity']
            and loop['offset'] == 0 and loop['sizeLimit'] == loop_step['sizeLimit']
            and loop['rdev'] == loop_step['rdev'], 'RESOURCE_CHANGED')
        resources['loop'], facts['loop'] = 'OWNED_PRESENT', loop
    mounts = agent_tracked_mounts()
    targets = [item for item in mounts if item['target'] == str(base / 'mutable')]
    related = [item for item in mounts if device and (item['source'] == device
        or loop_step and item['rdev'] == loop_step['rdev'])]
    agent_tracked_check(len(targets) <= 1 and len(related) == len(targets), 'RESOURCE_CHANGED')
    if targets:
        mount = targets[0]
        expected = agent_tracked_step_value(state, 'MOUNT')
        agent_tracked_check(expected is not None and loop is not None and mount['rdev'] == loop['rdev']
            and mount['root'] == '/' and mount['filesystem'] == 'ext4'
            and {'nodev', 'nosuid'} <= set(mount['options']), 'RESOURCE_CHANGED')
        identity = agent_tracked_owned(base / 'mutable', True)['identity']
        agent_tracked_check(('mountId' not in expected or expected['mountId'] == mount['id'])
            and ('identity' not in expected or expected['identity'] == identity), 'RESOURCE_CHANGED')
        with (base / 'volume.ext4').open('rb') as source:
            source.seek(1128)
            filesystem_uuid = source.read(16).hex()
        agent_tracked_check(filesystem_uuid == recipe['operationId'], 'RESOURCE_CHANGED')
        resources['mount'], facts['mount'] = 'OWNED_PRESENT', dict(mount, identity=identity)
    for key, step_name, path in (('promotionTemporary', 'PROMOTION_CREATE', ROOT / 'versions' / ('.agent-promote-' + recipe['operationId'])),
            ('bindingTemporary', 'BIND_TEMP', base / 'binding.tmp')):
        actual = agent_tracked_stat(path)
        expected = agent_tracked_step_value(state, step_name)
        if actual is not None:
            agent_tracked_check(expected is not None and actual['uid'] == 0 and actual['gid'] == 0, 'RESOURCE_CHANGED')
            if 'identity' in expected:
                agent_tracked_check(actual['identity'] == expected['identity'], 'RESOURCE_CHANGED')
            elif key == 'promotionTemporary':
                agent_tracked_check(actual['directory'] and not list(path.iterdir())
                    and agent_tracked_owned(path.parent, True)['identity'] == expected.get('parentIdentity'), 'RESOURCE_CHANGED')
            else:
                agent_tracked_check(actual['regular'] and actual['links'] == 1
                    and path.read_bytes() == base64.b64decode(recipe['originalArgv'][1], validate=True), 'RESOURCE_CHANGED')
            resources[key], facts[key] = 'OWNED_PRESENT', actual
    placeholder = agent_tracked_step_value(state, 'PLACEHOLDER')
    if placeholder:
        path = pathlib.Path(placeholder['path'])
        agent_tracked_check(path.parent == ROOT / 'versions' and re.fullmatch('[a-z]+-[a-f0-9]{64}', path.name), 'RESOURCE_CHANGED')
        actual = agent_tracked_stat(path)
        if actual is not None:
            publish = agent_tracked_step_value(state, 'PROMOTION_PUBLISH')
            if publish and actual['identity'] == publish['identity']:
                record = agent_record(path)
                agent_tracked_check(hashlib.sha256(agent_regular(path / '.w2l-toolchain.json')).hexdigest() == publish['markerSha256']
                    and record['agent_program_sha256'] == recipe['executedSourceSha256'], 'RESOURCE_CHANGED')
                retained.append('SHARED_INSTALLATION')
                resources['placeholder'] = 'RETAINED_SHARED'
            else:
                agent_tracked_check(actual['directory'] and actual['uid'] == 0 and actual['gid'] == 0
                    and not actual['mode'] & 0o022 and not list(path.iterdir())
                    and (actual['identity'] == placeholder['identity'] if 'identity' in placeholder
                        else agent_tracked_owned(path.parent, True)['identity'] == placeholder.get('parentIdentity')), 'RESOURCE_CHANGED')
                resources['placeholder'], facts['placeholder'] = 'OWNED_PRESENT', actual
    if state['selection'] is not None:
        selection = state['selection']
        record = agent_record(pathlib.Path(selection['directory']))
        agent_tracked_check({key: record[key] for key in selection} == selection, 'RESOURCE_CHANGED')
        retained.append('SHARED_INSTALLATION')
    if recipe['phase'] == 'BIND':
        target = ROOT / 'bindings' / state['bindingSha256']
        actual = agent_tracked_stat(target)
        if actual is not None:
            payload = base64.b64decode(recipe['originalArgv'][1], validate=True)
            agent_tracked_check(actual['regular'] and actual['uid'] == 0 and actual['gid'] == 0
                and not actual['mode'] & 0o022 and target.read_bytes() == payload, 'RESOURCE_CHANGED')
            temporary = facts.get('bindingTemporary')
            agent_tracked_check(actual['links'] == 1 or actual['links'] == 2 and temporary is not None
                and actual['identity'] == temporary['identity'], 'RESOURCE_CHANGED')
            retained.append('IMMUTABLE_BINDING')
        agent_tracked_check(state['executionStatus'] != 'SUCCEEDED' or actual is not None, 'RESOURCE_CHANGED')
    elif state['executionStatus'] == 'SUCCEEDED':
        agent_tracked_check(state['selection'] is not None and 'SHARED_INSTALLATION' in retained, 'RESOURCE_CHANGED')
    return resources, facts, sorted(set(retained))


def agent_tracked_settled(resources):
    return all(resources[key] in ('ABSENT', 'QUIESCENT') for key in ('unit', 'cgroup', 'children')) \
        and all(resources[key] == 'ABSENT' for key in ('image', 'loop', 'mount', 'promotionTemporary', 'bindingTemporary')) \
        and resources['placeholder'] in ('ABSENT', 'RETAINED_SHARED')


def agent_tracked_remove_tree(path, identity):
    agent_tracked_owned(path, True, identity)
    fd = os.open(str(path), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    def remove(directory):
        for name in os.listdir(directory):
            info = os.stat(name, dir_fd=directory, follow_symlinks=False)
            if stat.S_ISDIR(info.st_mode):
                child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=directory)
                try:
                    agent_tracked_check(os.fstat(child).st_dev == os.fstat(fd).st_dev, 'RESOURCE_CHANGED')
                    remove(child)
                finally:
                    os.close(child)
                os.rmdir(name, dir_fd=directory)
            else:
                agent_tracked_check(stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode), 'RESOURCE_CHANGED')
                os.unlink(name, dir_fd=directory)
    try:
        agent_tracked_check([os.fstat(fd).st_dev, os.fstat(fd).st_ino] == identity, 'RESOURCE_CHANGED')
        remove(fd)
        agent_tracked_owned(path, True, identity)
        path.rmdir()
    finally:
        os.close(fd)
    agent_tracked_sync(path.parent)


def agent_tracked_cleanup(resources, facts):
    context = AGENT_TRACKED_CONTEXT
    state, base = context['state'], context['base']
    agent_tracked_check(not state['cleanupStarted'], 'INTERRUPTED')
    state['cleanupStarted'] = True
    agent_tracked_save()
    if resources['unit'] == 'OWNED_PRESENT':
        current, ready = agent_tracked_unit(state)
        agent_tracked_check(current == 'OWNED_PRESENT' and ready == facts['unit'], 'RESOURCE_CHANGED')
        agent_tracked_step('UNIT_STOP', {'invocationId': ready['invocationId']})
        agent_os([agent_tool('systemctl'), 'stop', agent_unit(context['recipe']['operationId'])], timeout=30)
        agent_tracked_step('UNIT_STOP', {'invocationId': ready['invocationId']}, True)
    current, unit = agent_tracked_unit(state)
    group, identity = agent_tracked_group(state, unit)
    agent_tracked_check(current in ('ABSENT', 'QUIESCENT') and group in ('ABSENT', 'QUIESCENT'))
    agent_tracked_check(identity is None or facts.get('cgroup') is None or identity == facts['cgroup'], 'RESOURCE_CHANGED')
    if resources['mount'] == 'OWNED_PRESENT':
        again, actual, _ = agent_tracked_observe()
        agent_tracked_check(actual.get('mount') == facts['mount'], 'RESOURCE_CHANGED')
        agent_tracked_step('UNMOUNT', facts['mount'])
        agent_os([agent_tool('umount'), '--', str(base / 'mutable')], timeout=30)
        agent_tracked_check(not any(item['target'] == str(base / 'mutable') for item in agent_tracked_mounts()))
        agent_tracked_step('UNMOUNT', facts['mount'], True)
    if resources['loop'] == 'OWNED_PRESENT':
        loop = facts['loop']
        agent_tracked_check(agent_tracked_loop(loop['device']) == loop
            and not any(item['rdev'] == loop['rdev'] for item in agent_tracked_mounts()), 'RESOURCE_CHANGED')
        agent_tracked_step('LOOP_DETACH', loop)
        agent_os([agent_tool('losetup'), '--detach', loop['device']], timeout=30)
        agent_tracked_check(agent_tracked_loop(loop['device']) is None)
        agent_tracked_step('LOOP_DETACH', loop, True)
    if resources['image'] == 'OWNED_PRESENT':
        image = agent_tracked_owned(base / 'volume.ext4', identity=facts['image']['identity'])
        # Query every live loop, not just the candidate: no hidden reference may outlive the image.
        _, output = agent_os([agent_tool('losetup'), '--list', '--noheadings', '--raw', '--output', 'NAME'], timeout=30)
        for device in output.decode().split():
            attached = agent_tracked_loop(device)
            agent_tracked_check(attached is None or attached['backingIdentity'] != image['identity'])
        agent_tracked_step('IMAGE_REMOVE', image)
        (base / 'volume.ext4').unlink()
        agent_tracked_sync(base)
        agent_tracked_step('IMAGE_REMOVE', image, True)
    if resources['promotionTemporary'] == 'OWNED_PRESENT':
        path = ROOT / 'versions' / ('.agent-promote-' + context['recipe']['operationId'])
        agent_tracked_step('PROMOTION_REMOVE', facts['promotionTemporary'])
        agent_tracked_remove_tree(path, facts['promotionTemporary']['identity'])
        agent_tracked_step('PROMOTION_REMOVE', facts['promotionTemporary'], True)
    if resources['bindingTemporary'] == 'OWNED_PRESENT':
        path = base / 'binding.tmp'
        actual = agent_tracked_stat(path)
        agent_tracked_check(actual is not None and actual['identity'] == facts['bindingTemporary']['identity'], 'RESOURCE_CHANGED')
        agent_tracked_step('BIND_TEMP_REMOVE', actual)
        path.unlink()
        agent_tracked_sync(base)
        agent_tracked_step('BIND_TEMP_REMOVE', actual, True)
    if resources['placeholder'] == 'OWNED_PRESENT':
        placeholder = agent_tracked_step_value(state, 'PLACEHOLDER')
        path = pathlib.Path(placeholder['path'])
        agent_tracked_owned(path, True, facts['placeholder']['identity'])
        agent_tracked_check(not list(path.iterdir()), 'RESOURCE_CHANGED')
        agent_tracked_step('PLACEHOLDER_REMOVE', placeholder)
        path.rmdir()
        agent_tracked_sync(path.parent)
        agent_tracked_step('PLACEHOLDER_REMOVE', placeholder, True)
    resources, facts, retained = agent_tracked_observe()
    agent_tracked_check(agent_tracked_settled(resources))
    state['cleanupVerified'] = True
    agent_tracked_save()
    return resources, facts, retained


def agent_tracked_receipt(verb, value, raw, proof='VERIFIED', resources=None, retained=None, failure=None):
    context = AGENT_TRACKED_CONTEXT
    state = context['state'] if context is not None and context.get('state') is not None else None
    resources = resources or dict.fromkeys(AGENT_TRACKED_RESOURCES, 'UNKNOWN')
    retained = retained or []
    settled = proof == 'VERIFIED' and agent_tracked_settled(resources)
    base = context['base'] if state is not None else None
    binding = hashlib.sha256(base64.b64decode(value['originalArgv'][1], validate=True)).hexdigest() if value['phase'] == 'BIND' else None
    result = dict(formatVersion=1, verb=verb, taskId=value['taskId'], serverId=value['serverId'],
        fingerprintSha256=value['fingerprintSha256'], layoutSha256=hashlib.sha256(value['remoteLayout'].encode()).hexdigest(),
        phase=value['phase'], operationId=value['operationId'], version=value['version'], sourceSha256=value['sourceSha256'],
        executedSourceSha256=value['executedSourceSha256'], invocationKind=value['invocationKind'],
        requestSha256=hashlib.sha256(raw).hexdigest(), proofState=proof,
        executionStatus=state['executionStatus'] if state is not None else 'UNKNOWN',
        effectState=('SETTLED_RETAINED_EFFECTS' if retained else 'SETTLED_NO_EFFECTS') if settled else 'UNSETTLED',
        cleanupState=('VERIFIED' if state and state['cleanupVerified'] else 'NOT_NEEDED') if settled else 'REQUIRED',
        selection=state['selection'] if state is not None else None, bindingSha256=binding,
        resources=resources, retainedEffects=retained, failureCode=failure,
        creationSha256=hashlib.sha256(agent_tracked_read(base / 'creation.json')).hexdigest() if base else None,
        stateSha256=hashlib.sha256(agent_tracked_read(base / 'creation-state.json')).hexdigest() if base else None)
    line = 'AGENT_TOOLCHAIN_TRACKED=' + agent_tracked_encode(result).decode() + '\n'
    agent_tracked_check(len(line.encode()) <= 32768)
    print(line, end='')


def agent_tracked_atomic_binding(target, payload):
    context = AGENT_TRACKED_CONTEXT
    temporary = context['base'] / 'binding.tmp'
    agent_tracked_step('BIND_TEMP', {'path': 'binding.tmp'})
    agent_tracked_write(temporary, payload)
    identity = agent_tracked_owned(temporary)['identity']
    agent_tracked_step('BIND_TEMP', {'path': 'binding.tmp', 'identity': identity}, True)
    agent_tracked_step('BIND_PUBLISH', {'identity': identity, 'bindingSha256': hashlib.sha256(payload).hexdigest()})
    try:
        os.link(str(temporary), str(target), follow_symlinks=False)
    except FileExistsError:
        agent_tracked_check(agent_regular(target) == payload, 'RESOURCE_CHANGED')
    agent_tracked_sync(target.parent)
    agent_tracked_step('BIND_PUBLISH', {'identity': identity, 'bindingSha256': hashlib.sha256(payload).hexdigest()}, True)
    temporary.unlink()
    agent_tracked_sync(temporary.parent)


def agent_tracked_main(args):
    import fcntl
    global AGENT_TRACKED_CONTEXT, DEADLINE
    raw = globals().get('AGENT_TRACKED_REQUEST_RAW')
    value = agent_tracked_request(args, raw)
    agent_tracked_check(os.geteuid() == 0, 'REQUEST_INVALID')
    verb = args[0]
    DEADLINE = time.monotonic() + (int(value['originalArgv'][4 if value['phase'] == 'PREPARE' else 2])
        if verb in ('prepare-tracked', 'bind-tracked') else 120)
    owned_directory(ROOT)
    ROOT.mkdir(mode=0o755, parents=True, exist_ok=True)
    fd = os.open(str(ROOT / '.prepare.lock'), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
    context = dict(recipe=value, raw=raw, fd=fd, state=None, base=agent_operation(value['operationId']), readOnly=False)
    AGENT_TRACKED_CONTEXT = context
    try:
        agent_tracked_owned(ROOT / '.prepare.lock')
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            agent_tracked_receipt(verb, value, raw, 'MISSING', failure='LOCK_BUSY')
            return
        try:
            if verb in ('prepare-tracked', 'bind-tracked'):
                base, creation, state = agent_tracked_open(value, raw)
            else:
                base, creation, state = agent_tracked_load(value, raw)
            context.update(base=base, creation=creation, state=state)
        except (PreparationFailure, OSError, ValueError, KeyError) as error:
            code = getattr(error, 'detail', 'RESOURCE_UNCONFIRMED')
            proof = 'MISSING' if code in ('OPERATION_MISSING', 'LEGACY_PROOF_MISSING') else 'CONFLICT'
            agent_tracked_receipt(verb, value, raw, proof, failure=code if code in ('OPERATION_MISSING', 'LEGACY_PROOF_MISSING') else 'IDENTITY_MISMATCH')
            return
        failure = None
        if verb in ('prepare-tracked', 'bind-tracked'):
            captured = io.StringIO()
            try:
                with contextlib.redirect_stdout(captured):
                    (agent_prepare if value['phase'] == 'PREPARE' else agent_bind)(value['originalArgv'][1:])
                output = captured.getvalue()
                if value['phase'] == 'PREPARE':
                    agent_tracked_check(output.startswith('TOOLCHAIN=') and output.endswith('\n') and output.count('\n') == 1)
                    fields = output[len('TOOLCHAIN='):-1].split('|')
                    agent_tracked_check(len(fields) == 6)
                    state['selection'] = dict(zip(('ecosystem', 'version', 'directory', 'origin', 'source', 'sha256'), fields))
                else:
                    agent_tracked_check(output == 'TOOLCHAIN_BINDING=' + state['bindingSha256'] + '\n')
                state['executionStatus'] = 'SUCCEEDED'
            except (PreparationFailure, OSError, ValueError, KeyError, StopIteration, subprocess.TimeoutExpired):
                state['executionStatus'] = 'FAILED'
                failure = 'EXECUTION_FAILED'
            agent_tracked_save()
        try:
            context['readOnly'] = True
            resources, facts, retained = agent_tracked_observe()
            if verb == 'cleanup-tracked':
                context['readOnly'] = False
                resources, facts, retained = agent_tracked_cleanup(resources, facts)
        except (PreparationFailure, OSError, ValueError, KeyError, StopIteration, subprocess.TimeoutExpired) as error:
            code = getattr(error, 'detail', '')
            failure = code if code in ('ACTIVE_CHILD', 'RESOURCE_CHANGED', 'INTERRUPTED') else ('CLEANUP_FAILED' if verb == 'cleanup-tracked' else 'RESOURCE_UNCONFIRMED')
            resources, retained = None, None
        agent_tracked_receipt(verb, value, raw, resources=resources, retained=retained, failure=failure)
    finally:
        AGENT_TRACKED_CONTEXT = None
        os.close(fd)
