"""Original-operation toolchain receipts. Observation never selects or prepares another release."""
import contextlib
import stat
import uuid

TRACKED_OPERATION = None
TRACKED_PREPARE_LOCK = None
INSTALLATION_DIAGNOSTIC = None
DIAGNOSTIC_REQUEST_FIELDS = {'formatVersion', 'operationId', 'taskId', 'serverId', 'fingerprintSha256',
                             'planRevision', 'remoteLayout', 'arguments'}
TRACKED_REQUEST_FIELDS = {'formatVersion', 'operationId', 'taskId', 'executionId', 'unitId', 'recoveryAttempt',
                          'originalIntentSha256', 'serverId', 'fingerprintSha256', 'remoteLayout', 'kind', 'parameters'}
TRACKED_STATE_FIELDS = {'formatVersion', 'requestSha256', 'phase', 'executionStatus', 'failureCategory', 'selected',
                        'ownedDestination', 'systemDependencies', 'processes', 'bindingSha256'}
TRACKED_FAILURES = {'request', 'ownership', 'integrity', 'permission', 'unavailable', 'metadata', 'network',
                    'archive', 'installation', 'probe', 'platform', 'timeout', 'cancelled', 'operation_missing',
                    'lock_busy', 'interrupted'}


def tracked_pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            fail('request', 'duplicate tracked JSON field')
        result[key] = value
    return result


def tracked_json(raw):
    return json.loads(raw.decode('utf-8'), object_pairs_hook=tracked_pairs,
                      parse_constant=lambda value: fail('request', 'nonfinite tracked JSON value'))


def tracked_fields(value, expected):
    if not isinstance(value, dict) or set(value) != expected:
        fail('request', 'tracked JSON fields differ from the fixed protocol')


def tracked_digest(value):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None


def tracked_uuid(value):
    return isinstance(value, str) and str(uuid.UUID(value)) == value


def tracked_layout(encoded):
    if not isinstance(encoded, str) or len(encoded.encode('utf-8')) > 4096:
        fail('request', 'invalid tracked layout')
    value = tracked_json(encoded.encode('utf-8'))
    tracked_fields(value, {'formatVersion', 'remote'})
    names = ('helperDirectory', 'applicationsRoot', 'stateRoot', 'configurationRoot', 'dataRoot')
    tracked_fields(value['remote'], set(names))
    if type(value['formatVersion']) is not int or value['formatVersion'] != 1:
        fail('request', 'unsupported tracked layout')
    paths = value['remote']
    for path in paths.values():
        if (not isinstance(path, str) or not re.fullmatch(r'/[A-Za-z0-9._/-]{1,1023}', path)
                or any(part in ('', '.', '..') for part in path.split('/')[1:])):
            fail('request', 'invalid tracked layout root')
    if any(a == b or a.startswith(b + '/') or b.startswith(a + '/')
           for i, a in enumerate(paths.values()) for b in list(paths.values())[i + 1:]):
        fail('request', 'overlapping tracked layout roots')
    canonical = {'formatVersion': 1, 'remote': {name: paths[name] for name in names}}
    if encoded != json.dumps(canonical, separators=(',', ':'), ensure_ascii=False):
        fail('request', 'noncanonical tracked layout')
    if paths['helperDirectory'] + '/toolchains' != str(ROOT):
        fail('ownership', 'tracked request belongs to another helper layout')


def tracked_request(args, raw):
    if (len(args) != 3 or not tracked_uuid(args[1]) or not tracked_digest(args[2])
            or not raw or len(raw) > 131072 or hashlib.sha256(raw).hexdigest() != args[2]):
        fail('request', 'invalid tracked request identity')
    value = tracked_json(raw)
    tracked_fields(value, TRACKED_REQUEST_FIELDS)
    if (type(value['formatVersion']) is not int or value['formatVersion'] != 1
            or any(not tracked_uuid(value[k]) for k in ('operationId', 'taskId', 'executionId'))
            or value['operationId'] != args[1] or not tracked_digest(value['originalIntentSha256'])
            or not tracked_digest(value['fingerprintSha256']) or type(value['recoveryAttempt']) is not int
            or not 1 <= value['recoveryAttempt'] <= 2 or not isinstance(value['unitId'], str)
            or not re.fullmatch('[a-z0-9][a-z0-9-]{0,62}', value['unitId'])
            or not isinstance(value['serverId'], str) or not 1 <= len(value['serverId']) <= 512
            or any(ord(c) < 32 or ord(c) == 127 for c in value['serverId'])):
        fail('request', 'invalid tracked business identity')
    tracked_layout(value['remoteLayout'])
    parameters = value['parameters']
    if value['kind'] == 'PREPARE':
        tracked_fields(parameters, {'ecosystem', 'branch', 'exact', 'seconds', 'javaHome'})
        if (parameters['ecosystem'] not in ('JAVA', 'NODE', 'PYTHON', 'DOTNET', 'KOTLIN', 'GO', 'RUST', 'PHP', 'RUBY')
                or not isinstance(parameters['branch'], str)
                or not re.fullmatch(r'[0-9]{1,3}(?:\.[0-9]{1,3})?', parameters['branch'])
                or not isinstance(parameters['exact'], str)
                or not re.fullmatch(r'(?:-|[0-9][0-9.u+_-]{0,95})', parameters['exact'])
                or type(parameters['seconds']) is not int or not 30 <= parameters['seconds'] <= 7200
                or not isinstance(parameters['javaHome'], str) or len(parameters['javaHome']) > 2048):
            fail('request', 'invalid tracked preparation arguments')
        if parameters['javaHome'] != '-':
            directory = pathlib.Path(parameters['javaHome'])
            if directory.parent != ROOT / 'versions' or not re.fullmatch('java-[a-f0-9]{64}', directory.name):
                fail('request', 'invalid tracked Java selection')
    elif value['kind'] == 'BIND':
        tracked_fields(parameters, {'bindingBase64'})
        if not isinstance(parameters['bindingBase64'], str) or len(parameters['bindingBase64']) > 90000:
            fail('request', 'invalid tracked binding')
        payload = base64.b64decode(parameters['bindingBase64'], validate=True)
        if not payload or len(payload) > 65536 or base64.b64encode(payload).decode() != parameters['bindingBase64']:
            fail('request', 'noncanonical tracked binding')
        payload.decode('utf-8')
    else:
        fail('request', 'unknown tracked action')
    if args[0] in ('prepare-tracked', 'bind-tracked') and args[0].split('-')[0].upper() != value['kind']:
        fail('request', 'tracked verb differs from original request')
    return value


def tracked_regular(path, limit):
    owned_directory(path.parent)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or info.st_size > limit:
        fail('ownership', 'tracked evidence must be bounded root-owned regular data')
    return path.read_bytes()


def tracked_sync_directory(path):
    descriptor = os.open(str(path), os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def tracked_atomic_bytes(path, payload, mode=0o600):
    owned_directory(path.parent)
    if path.is_symlink():
        fail('ownership', 'tracked evidence cannot be a link')
    temporary = path.with_name('.' + path.name + '-' + uuid.uuid4().hex)
    try:
        with temporary.open('xb') as output:
            output.write(payload)
            output.flush()
            temporary.chmod(mode)
            os.fsync(output.fileno())
        os.replace(temporary, path)
        tracked_sync_directory(path.parent)
    finally:
        if temporary.exists():
            temporary.unlink()


def tracked_atomic_json(path, value, mode=0o600):
    tracked_atomic_bytes(path, json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8'), mode)


def installation_diagnostic_request(args, raw):
    if (len(args) != 3 or not tracked_uuid(args[1]) or not tracked_digest(args[2])
            or not raw or len(raw) > 16384 or hashlib.sha256(raw).hexdigest() != args[2]):
        fail('request', 'invalid installation diagnostic request identity')
    request = tracked_json(raw)
    tracked_fields(request, DIAGNOSTIC_REQUEST_FIELDS)
    if (type(request['formatVersion']) is not int or request['formatVersion'] != 1
            or request['operationId'] != args[1] or not tracked_uuid(request['taskId'])
            or not tracked_digest(request['fingerprintSha256']) or not tracked_digest(request['planRevision'])
            or not isinstance(request['serverId'], str) or not 1 <= len(request['serverId']) <= 512
            or any(ord(c) < 32 or ord(c) == 127 for c in request['serverId'])):
        fail('request', 'invalid installation diagnostic business identity')
    tracked_layout(request['remoteLayout'])
    arguments = request['arguments']
    if (not isinstance(arguments, list) or len(arguments) != 6 or arguments[0] != 'prepare'
            or any(not isinstance(value, str) for value in arguments)
            or arguments[1] not in ('JAVA', 'NODE', 'PYTHON', 'DOTNET', 'KOTLIN', 'GO', 'RUST', 'PHP', 'RUBY')
            or not re.fullmatch(r'[0-9]{1,3}(?:\.[0-9]{1,3})?', arguments[2])
            or not re.fullmatch(r'(?:-|[0-9][0-9.u+_-]{0,95})', arguments[3])
            or not re.fullmatch('[1-9][0-9]{1,3}', arguments[4]) or not 30 <= int(arguments[4]) <= 7200
            or not (arguments[5] == '-' or re.fullmatch(re.escape(str(ROOT / 'versions' / 'java-')) + '[a-f0-9]{64}', arguments[5]))):
        fail('request', 'invalid installation diagnostic preparation arguments')
    return request


def record_installation_diagnostic(command):
    context = INSTALLATION_DIAGNOSTIC
    if context is not None:
        directory, diagnostic = context
        diagnostic.update(command)
        tracked_atomic_json(directory / 'diagnostic.json', diagnostic)


def installation_diagnostic_main(args, raw=None):
    global INSTALLATION_DIAGNOSTIC
    if raw is None:
        with os.fdopen(os.dup(3), 'rb') as source:
            raw = source.read(16385)
    request = installation_diagnostic_request(args, raw)
    parent = ROOT / 'diagnostics'
    directory = parent / request['operationId']
    creating = args[0] == 'prepare-diagnosed'
    if creating:
        prepare_shared_directories()
        owned_directory(parent)
        parent.mkdir(mode=0o700, exist_ok=True)
        owned_directory(directory)
        directory.mkdir(mode=0o700)  # An original operation is never re-executed.
        tracked_atomic_bytes(directory / 'request.json', raw)
        diagnostic = {key: request[key] for key in ('formatVersion', 'operationId', 'taskId', 'serverId',
                                                   'fingerprintSha256', 'planRevision')}
        diagnostic.update(requestSha256=args[2], preparationStatus='UNKNOWN', failureCategory=None,
                          stage='preparation-start', executable='', actualExitCode=None, timedOut=False,
                          cancelled=False, stdout='', stderr='', stdoutTruncated=False, stderrTruncated=False)
        INSTALLATION_DIAGNOSTIC = (directory, diagnostic)
        tracked_atomic_json(directory / 'diagnostic.json', diagnostic)
        try:
            main(request['arguments'])
            diagnostic['preparationStatus'] = 'SUCCEEDED'
        except BaseException as error:
            diagnostic['preparationStatus'] = 'FAILED'
            diagnostic['failureCategory'] = (error.category if isinstance(error, PreparationFailure)
                                             else 'timeout' if isinstance(error, subprocess.TimeoutExpired) else 'installation')
            raise
        finally:
            try:
                tracked_atomic_json(directory / 'diagnostic.json', diagnostic)
            finally:
                INSTALLATION_DIAGNOSTIC = None
        return
    owned_directory(ROOT)
    owned_directory(parent)
    owned_directory(directory)
    if tracked_regular(directory / 'request.json', 16384) != raw:
        fail('integrity', 'installation diagnostic differs from the original operation request')
    payload = tracked_regular(directory / 'diagnostic.json', 262144)
    diagnostic = tracked_json(payload)
    expected = {key: request[key] for key in ('formatVersion', 'operationId', 'taskId', 'serverId',
                                             'fingerprintSha256', 'planRevision')}
    expected['requestSha256'] = args[2]
    if any(diagnostic.get(key) != value for key, value in expected.items()):
        fail('integrity', 'installation diagnostic identity differs from its original request')
    print(payload.decode('utf-8'))


def tracked_process_identity(pid):
    try:
        fields = pathlib.Path('/proc/' + str(pid) + '/stat').read_text().rsplit(')', 1)[1].split()
        return {'pid': pid, 'start': fields[19], 'boot': pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip()}
    except FileNotFoundError:
        return {'pid': pid, 'start': None, 'boot': pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip()}


class TrackedToolchainOperation:
    def __init__(self, request, raw, request_sha, directory, state):
        self.request, self.raw, self.sha, self.directory, self.state = request, raw, request_sha, directory, state

    def save(self):
        tracked_atomic_json(self.directory / 'state.json', self.state)

    def select(self, selected):
        directory = pathlib.Path(selected['directory'])
        owned_directory(directory)
        if directory.exists() and not (directory / '.w2l-toolchain.json').is_file():
            fail('ownership', 'an incomplete shared installation is not owned by this operation')
        self.state.update(phase='SELECTED', selected=selected, ownedDestination=not directory.exists())
        self.save()

    def create_destination(self, directory):
        if not self.state['ownedDestination'] or directory.exists() or str(directory) != self.state['selected']['directory']:
            fail('ownership', 'tracked installation destination is not exclusively owned')
        directory.mkdir()
        tracked_atomic_json(directory / '.w2l-operation-owner', {'operationId': self.request['operationId'], 'requestSha256': self.sha}, 0o444)
        self.state['phase'] = 'PREPARING'
        self.save()

    def archive_downloaded(self, digest):
        if not tracked_digest(digest):
            fail('integrity', 'invalid downloaded artifact digest')
        self.state['selected']['sha256'] = digest
        self.save()

    @contextlib.contextmanager
    def work(self):
        directory = self.directory / 'work'
        directory.mkdir(mode=0o700)
        tracked_sync_directory(self.directory)
        try:
            yield str(directory)
        finally:
            if directory.exists():
                shutil.rmtree(directory)
                tracked_sync_directory(self.directory)

    def dependencies_started(self):
        self.state['systemDependencies'] = True
        self.save()

    def process_started(self, pid):
        if len(self.state['processes']) >= 256:
            fail('installation', 'tracked subprocess evidence limit exceeded')
        self.state['processes'].append(dict(tracked_process_identity(pid), active=True))
        self.save()

    def process_finished(self, pid):
        for process in self.state['processes']:
            if process['pid'] == pid and process['active']:
                process['active'] = False
        self.save()


def tracked_open_operation(request, raw, request_sha, create):
    parent = ROOT / 'operations'
    directory = parent / request['operationId']
    owned_directory(parent)
    if create:
        parent.mkdir(mode=0o700, exist_ok=True)
    owned_directory(directory)
    if not directory.exists():
        if not create:
            return None, False
        directory.mkdir(mode=0o700)
        tracked_atomic_bytes(directory / 'request.json', raw, 0o444)
        state = {'formatVersion': 1, 'requestSha256': request_sha, 'phase': 'INTENT', 'executionStatus': 'UNKNOWN',
                 'failureCategory': None, 'selected': None, 'ownedDestination': False, 'systemDependencies': False,
                 'processes': [], 'bindingSha256': None}
        operation = TrackedToolchainOperation(request, raw, request_sha, directory, state)
        operation.save()
        tracked_sync_directory(parent)
        return operation, True
    if not directory.is_dir() or tracked_regular(directory / 'request.json', 131072) != raw:
        fail('integrity', 'tracked operation request collision')
    state = tracked_json(tracked_regular(directory / 'state.json', 131072))
    tracked_fields(state, TRACKED_STATE_FIELDS)
    if (state['formatVersion'] != 1 or state['requestSha256'] != request_sha
            or state['phase'] not in ('INTENT', 'SELECTED', 'PREPARING', 'BIND_PUBLISHING', 'COMPLETED', 'FAILED', 'CLEANED')
            or state['executionStatus'] not in ('UNKNOWN', 'SUCCEEDED', 'FAILED')
            or state['failureCategory'] is not None and state['failureCategory'] not in TRACKED_FAILURES
            or type(state['ownedDestination']) is not bool or type(state['systemDependencies']) is not bool
            or not isinstance(state['processes'], list) or len(state['processes']) > 256
            or state['bindingSha256'] is not None and not tracked_digest(state['bindingSha256'])):
        fail('integrity', 'invalid tracked operation state')
    for process in state['processes']:
        tracked_fields(process, {'pid', 'start', 'boot', 'active'})
        if (type(process['pid']) is not int or process['pid'] < 1 or type(process['active']) is not bool
                or not tracked_uuid(process['boot']) or process['start'] is not None
                and (not isinstance(process['start'], str) or not process['start'].isdigit())):
            fail('integrity', 'invalid tracked subprocess identity')
    selected = state['selected']
    if selected is not None:
        common = {'ecosystem', 'version', 'directory', 'origin', 'source', 'sha256'}
        if not isinstance(selected, dict) or selected.get('origin') not in ('SYSTEM', 'MANAGED'):
            fail('integrity', 'invalid tracked selection')
        tracked_fields(selected, common | ({'system_files', '_home'} if selected['origin'] == 'SYSTEM'
                                          else {'official_algorithm', 'official_digest', 'archive_layout'}))
        if (selected['ecosystem'] != request['parameters'].get('ecosystem')
                or not isinstance(selected['version'], str) or version_key(selected['version']) is None
                or any(not isinstance(selected[k], str) or not 1 <= len(selected[k]) <= 4096
                       for k in ('directory', 'source'))
                or selected['sha256'] is not None and not tracked_digest(selected['sha256'])):
            fail('integrity', 'invalid original selected release')
    return TrackedToolchainOperation(request, raw, request_sha, directory, state), False


def tracked_prepare(operation):
    global DEADLINE
    parameters = operation.request['parameters']
    ecosystem, branch, exact = (parameters[k] for k in ('ecosystem', 'branch', 'exact'))
    if not admitted(ecosystem, branch):
        fail('unavailable', 'branch is outside the shipped catalog')
    DEADLINE = time.monotonic() + parameters['seconds']
    if parameters['javaHome'] != '-':
        directory = pathlib.Path(parameters['javaHome'])
        record = tracked_json(tracked_regular(directory / '.w2l-toolchain.json', 65536))
        if record.get('ecosystem') != 'JAVA' or record.get('directory') != str(directory):
            fail('integrity', 'original Java selection changed')
        verify_system(record)
        os.environ['JAVA_HOME'] = str(directory)
    identity, url, algorithm, digest, archive_layout = release(ecosystem, branch, '' if exact == '-' else exact)
    url = official_url(url)
    discovered = discover_system(ecosystem, identity)
    if discovered is not None:
        operation.select(dict(discovered))
        materialize_system(discovered)
    else:
        directory = ROOT / 'versions' / (ecosystem.lower() + '-' + installation_key(ecosystem, identity, digest))
        operation.select({'ecosystem': ecosystem, 'version': identity, 'directory': str(directory), 'origin': 'MANAGED',
                          'source': url, 'sha256': None, 'official_algorithm': algorithm,
                          'official_digest': digest, 'archive_layout': archive_layout})
        install(ecosystem, identity, url, algorithm, digest, archive_layout)
    operation.state.update(phase='COMPLETED', executionStatus='SUCCEEDED')
    operation.save()


def tracked_publish_binding(target, payload):
    operation = TRACKED_OPERATION
    temporary = target.parent / ('.tracked-' + operation.request['operationId'])
    if temporary.exists() or temporary.is_symlink():
        fail('ownership', 'tracked binding temporary path already exists')
    with temporary.open('xb') as output:
        output.write(payload)
        output.flush()
        temporary.chmod(0o444)
        os.fsync(output.fileno())
    try:
        os.link(temporary, target)
    except FileExistsError:
        if tracked_regular(target, 65536) != payload:
            fail('integrity', 'immutable binding collision')
    tracked_sync_directory(target.parent)
    temporary.unlink()
    tracked_sync_directory(target.parent)


def tracked_bind(operation):
    payload = base64.b64decode(operation.request['parameters']['bindingBase64'], validate=True)
    operation.state.update(phase='BIND_PUBLISHING', bindingSha256=hashlib.sha256(payload).hexdigest())
    operation.save()
    bind(operation.request['parameters']['bindingBase64'], tracked=True)
    operation.state.update(phase='COMPLETED', executionStatus='SUCCEEDED')
    operation.save()


def tracked_validate_selection(operation):
    selected = operation.state['selected']
    if selected is None:
        return None
    directory = pathlib.Path(selected['directory'])
    if directory.parent != ROOT / 'versions' or not re.fullmatch('[a-z]+-[a-f0-9]{64}', directory.name):
        fail('ownership', 'tracked selection is outside the managed root')
    marker = directory / '.w2l-toolchain.json'
    if not marker.exists():
        return None
    record = tracked_json(tracked_regular(marker, 65536))
    fields = ('ecosystem', 'version', 'directory', 'origin', 'source', 'sha256')
    if any(record.get(k) != selected.get(k) for k in fields if k != 'sha256' or selected.get(k) is not None):
        fail('integrity', 'tracked marker differs from the original selection')
    if not tracked_digest(record.get('sha256')):
        fail('integrity', 'invalid original toolchain digest')
    if selected['origin'] == 'MANAGED' and any(record.get(k) != selected.get(k) for k in ('official_algorithm', 'official_digest')):
        fail('integrity', 'tracked marker differs from the selected official artifact')
    if marker.stat().st_mode & 0o222:
        fail('integrity', 'tracked completion marker is not sealed')
    verify_system(record)
    probe(record['ecosystem'], directory, record['version'])
    return {k: record[k] for k in fields}


def tracked_owned_residue(operation):
    work = operation.directory / 'work'
    if work.exists() or work.is_symlink():
        return True
    temporary = ROOT / 'bindings' / ('.tracked-' + operation.request['operationId'])
    if temporary.exists() or temporary.is_symlink():
        return True
    selected = operation.state['selected']
    return (selected is not None and operation.state['ownedDestination']
            and pathlib.Path(selected['directory']).exists()
            and not (pathlib.Path(selected['directory']) / '.w2l-toolchain.json').exists())


def tracked_observation(request, request_sha, operation, failure=None):
    state = operation.state if operation else {'phase': 'INTENT', 'executionStatus': 'UNKNOWN', 'failureCategory': 'operation_missing'}
    result = {'formatVersion': 1, 'operationId': request['operationId'], 'taskId': request['taskId'],
              'requestSha256': request_sha, 'originalIntentSha256': request['originalIntentSha256'], 'kind': request['kind'],
              'executionStatus': state['executionStatus'], 'effectState': 'UNSETTLED', 'phase': state['phase'],
              'selection': None, 'bindingSha256': None, 'failureCategory': failure or state['failureCategory'],
              'cleanupState': 'REQUIRED', 'retainedEffects': []}
    if operation is None or failure is not None:
        return result
    tracked_require_quiescent(operation)
    if state['systemDependencies']:
        result['retainedEffects'].append('SYSTEM_DEPENDENCIES')
    if request['kind'] == 'PREPARE':
        result['selection'] = tracked_validate_selection(operation)
        if result['selection'] is not None:
            result['effectState'] = 'PREPARED_VERIFIED'
            result['retainedEffects'].append('SHARED_INSTALLATION')
    elif state['bindingSha256'] is not None:
        payload = base64.b64decode(request['parameters']['bindingBase64'], validate=True)
        identity = hashlib.sha256(payload).hexdigest()
        if identity != state['bindingSha256']:
            fail('integrity', 'original binding identity changed')
        target = ROOT / 'bindings' / identity
        if target.exists():
            if tracked_regular(target, 65536) != payload or target.stat().st_mode & 0o222:
                fail('integrity', 'original binding is not immutable')
            validated_binding(request['parameters']['bindingBase64'], restoring=True)
            result['bindingSha256'] = identity
            result['effectState'] = 'BINDING_VERIFIED'
            result['retainedEffects'].append('IMMUTABLE_BINDING')
    residue = tracked_owned_residue(operation)
    if not residue:
        result['cleanupState'] = 'VERIFIED' if state['phase'] == 'CLEANED' else 'NOT_NEEDED'
        if result['effectState'] == 'UNSETTLED' and (state['executionStatus'] == 'FAILED' or state['phase'] == 'CLEANED'):
            result['effectState'] = 'SETTLED_RETAINED_EFFECTS' if result['retainedEffects'] else 'SETTLED_NO_EFFECTS'
    return result


def tracked_cleanup(operation):
    tracked_require_quiescent(operation)
    work = operation.directory / 'work'
    if work.exists() or work.is_symlink():
        owned_directory(work)
        if not work.is_dir() or work.is_symlink():
            fail('ownership', 'tracked work is not an owned directory')
        shutil.rmtree(work)
    temporary = ROOT / 'bindings' / ('.tracked-' + operation.request['operationId'])
    if temporary.exists() or temporary.is_symlink():
        if operation.request['kind'] != 'BIND':
            fail('ownership', 'preparation cannot own a binding temporary file')
        payload = base64.b64decode(operation.request['parameters']['bindingBase64'], validate=True)
        if tracked_regular(temporary, 65536) != payload:
            fail('ownership', 'binding residue differs from the original operation')
        temporary.unlink()
        tracked_sync_directory(temporary.parent)
    selected = operation.state['selected']
    if selected is not None and operation.state['ownedDestination']:
        directory = pathlib.Path(selected['directory'])
        if directory.parent != ROOT / 'versions' or not re.fullmatch('[a-z]+-[a-f0-9]{64}', directory.name):
            fail('ownership', 'cleanup destination is outside the managed root')
        if directory.exists() and not (directory / '.w2l-toolchain.json').exists():
            owned_directory(directory)
            owner = tracked_json(tracked_regular(directory / '.w2l-operation-owner', 1024))
            if owner != {'operationId': operation.request['operationId'], 'requestSha256': operation.sha}:
                fail('ownership', 'incomplete installation belongs to another operation')
            bindings = ROOT / 'bindings'
            entries = list(bindings.iterdir()) if bindings.exists() else []
            if len(entries) > 10000:
                fail('integrity', 'binding reference scan exceeds its fixed limit')
            for entry in entries:
                if re.fullmatch('[a-f0-9]{64}', entry.name) and ('\t' + str(directory) + '\t').encode() in tracked_regular(entry, 65536):
                    fail('ownership', 'incomplete installation is referenced by a retained binding')
            shutil.rmtree(directory)
            tracked_sync_directory(directory.parent)
    operation.state['phase'] = 'CLEANED'
    operation.save()
    tracked_sync_directory(operation.directory)


def tracked_require_quiescent(operation):
    active = [process for process in operation.state['processes'] if process['active']]
    if not active:
        return
    boot = pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    groups = {process['pid'] for process in active if process['boot'] == boot}
    if not groups:
        return
    for process in pathlib.Path('/proc').iterdir():
        if not process.name.isdigit():
            continue
        try:
            fields = (process / 'stat').read_text().rsplit(')', 1)[1].split()
            if int(fields[2]) in groups:
                fail('interrupted', 'an original tracked process group remains active')
        except FileNotFoundError:
            continue


def tracked_main(args, raw=None):
    global TRACKED_OPERATION, TRACKED_PREPARE_LOCK, DEADLINE
    import fcntl
    if raw is None:
        with os.fdopen(os.dup(3), 'rb') as source:
            raw = source.read(131073)
    request = tracked_request(args, raw)
    creating = args[0] in ('prepare-tracked', 'bind-tracked')
    cleaning = args[0] == 'cleanup-tracked'
    if creating:
        prepare_shared_directories()
    else:
        owned_directory(ROOT)
        if not ROOT.is_dir():
            print(json.dumps(tracked_observation(request, args[2], None), separators=(',', ':')))
            return
    path = ROOT / '.prepare.lock'
    if not creating:
        tracked_regular(path, 1024)
    descriptor = os.open(str(path), (os.O_RDWR | os.O_CREAT if creating else os.O_RDONLY)
                         | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            fail('ownership', 'tracked preparation lock is not root-owned')
        try:
            fcntl.flock(descriptor, (fcntl.LOCK_EX if creating or cleaning else fcntl.LOCK_SH) | fcntl.LOCK_NB)
        except BlockingIOError:
            print(json.dumps(tracked_observation(request, args[2], None, 'lock_busy'), separators=(',', ':')))
            return
        TRACKED_PREPARE_LOCK = descriptor
        DEADLINE = time.monotonic() + (request['parameters']['seconds'] if creating and request['kind'] == 'PREPARE' else 180)
        operation, fresh = tracked_open_operation(request, raw, args[2], creating)
        if fresh:
            TRACKED_OPERATION = operation
            try:
                if request['kind'] == 'PREPARE':
                    tracked_prepare(operation)
                else:
                    tracked_bind(operation)
            except (PreparationFailure, subprocess.TimeoutExpired, OSError, ValueError, KeyError, StopIteration) as error:
                category = error.category if isinstance(error, PreparationFailure) else 'timeout' if isinstance(error, subprocess.TimeoutExpired) else 'installation'
                operation.state.update(phase='FAILED', executionStatus='FAILED', failureCategory=category)
                operation.save()
            finally:
                TRACKED_OPERATION = None
        try:
            if cleaning and operation is not None:
                tracked_cleanup(operation)
            result = tracked_observation(request, args[2], operation)
        except (PreparationFailure, subprocess.TimeoutExpired, OSError, ValueError, KeyError, StopIteration) as error:
            category = error.category if isinstance(error, PreparationFailure) else 'timeout' if isinstance(error, subprocess.TimeoutExpired) else 'integrity'
            result = tracked_observation(request, args[2], operation, category)
        encoded = json.dumps(result, separators=(',', ':'), ensure_ascii=False)
        if len(encoded.encode('utf-8')) > 32768:
            fail('integrity', 'tracked receipt exceeds its fixed bound')
        print(encoded)
    finally:
        TRACKED_OPERATION = None
        TRACKED_PREPARE_LOCK = None
        os.close(descriptor)
