"""Read-only release selection and the same canonical descriptor checks before installation."""

RELEASE_FIELDS = frozenset(('format', 'operation', 'ecosystem', 'branch', 'requestedExact',
    'version', 'url', 'algorithm', 'digest', 'layout', 'platform', 'resolverSourceSha256'))


def resolution_reject(detail):
    fail('metadata', detail)


def resolution_encode(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def resolution_pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            resolution_reject('RELEASE_DESCRIPTOR_DUPLICATE')
        value[key] = item
    return value


def resolution_validate(value, ecosystem, branch, exact, operation, resolver_sha256):
    if not isinstance(value, dict) or set(value) != RELEASE_FIELDS or any(not isinstance(item, str) for item in value.values()):
        resolution_reject('RELEASE_DESCRIPTOR_SCHEMA')
    if value['format'] != 'WTL_AGENT_RELEASE_1' or value['operation'] != operation or value['ecosystem'] != ecosystem or value['branch'] != branch or value['requestedExact'] != exact:
        resolution_reject('RELEASE_DESCRIPTOR_BINDING')
    if not re.fullmatch('[a-f0-9]{32}', operation) or value['platform'] != 'linux-x86_64' or value['resolverSourceSha256'] != resolver_sha256 or not re.fullmatch('[a-f0-9]{64}', resolver_sha256):
        resolution_reject('RELEASE_DESCRIPTOR_SOURCE')
    if len(value['version']) > 96 or version_key(value['version']) is None or choose([value['version']], branch, '' if exact == '-' else exact) != value['version']:
        resolution_reject('RELEASE_DESCRIPTOR_VERSION')
    if len(value['url']) > 2048 or not re.fullmatch('[!-~]+', value['url']):
        resolution_reject('RELEASE_DESCRIPTOR_URL')
    official_url(value['url'])
    parsed = urllib.parse.urlsplit(value['url'])
    if parsed.fragment:
        resolution_reject('RELEASE_DESCRIPTOR_URL')
    if value['algorithm'] not in ('sha256', 'sha512') or not re.fullmatch('[a-f0-9]{' + ('64' if value['algorithm'] == 'sha256' else '128') + '}', value['digest']):
        resolution_reject('RELEASE_DESCRIPTOR_DIGEST')
    layouts = {'JAVA': 'archive', 'NODE': 'archive', 'GO': 'archive', 'DOTNET': 'flat',
        'KOTLIN': 'archive', 'RUST': 'rust', 'PYTHON': 'python', 'PHP': 'php', 'RUBY': 'ruby'}
    if layouts.get(ecosystem) != value['layout']:
        resolution_reject('RELEASE_DESCRIPTOR_LAYOUT')
    return value


def resolution_decode(encoded, ecosystem, branch, exact, operation, resolver_sha256):
    if not isinstance(encoded, str) or len(encoded) > 5464:
        resolution_reject('RELEASE_DESCRIPTOR_LIMIT')
    try:
        data = base64.b64decode(encoded, validate=True)
        if len(data) > 4096 or base64.b64encode(data).decode('ascii') != encoded:
            resolution_reject('RELEASE_DESCRIPTOR_ENCODING')
        value = json.loads(data.decode('ascii'), object_pairs_hook=resolution_pairs)
        if resolution_encode(value) != data:
            resolution_reject('RELEASE_DESCRIPTOR_CANONICAL')
        return resolution_validate(value, ecosystem, branch, exact, operation, resolver_sha256)
    except (ValueError, UnicodeError):
        resolution_reject('RELEASE_DESCRIPTOR_ENCODING')


def resolution_main(args):
    if len(args) != 6 or args[0] != 'resolve':
        resolution_reject('RELEASE_RESOLVE_ARGUMENTS')
    _, ecosystem, branch, exact, seconds, operation = args
    if branch not in SHIPPED_CATALOG.get(ecosystem, []) or not re.fullmatch('[a-f0-9]{32}', operation) or not re.fullmatch('[1-9][0-9]{1,2}', seconds) or not 30 <= int(seconds) <= 180:
        resolution_reject('RELEASE_RESOLVE_ARGUMENTS')
    if exact != '-' and (version_key(exact) is None or len(exact) > 96):
        resolution_reject('RELEASE_RESOLVE_ARGUMENTS')
    if os.uname().sysname != 'Linux' or os.uname().machine not in ('x86_64', 'amd64'):
        resolution_reject('RELEASE_PLATFORM_UNSUPPORTED')
    # This entrypoint never creates ROOT, locks, volumes or installation directories.
    signal.signal(signal.SIGALRM, lambda *_: fail('timeout', 'RELEASE_RESOLVE_TIMEOUT'))
    signal.alarm(int(seconds))
    try:
        version, url, algorithm, digest, layout = release(ecosystem, branch, '' if exact == '-' else exact)
        source_sha = hashlib.sha256(PROGRAM_SOURCE).hexdigest()
        value = {'format': 'WTL_AGENT_RELEASE_1', 'operation': operation, 'ecosystem': ecosystem,
            'branch': branch, 'requestedExact': exact, 'version': version, 'url': url,
            'algorithm': algorithm, 'digest': digest, 'layout': layout, 'platform': 'linux-x86_64',
            'resolverSourceSha256': source_sha}
        resolution_validate(value, ecosystem, branch, exact, operation, source_sha)
        data = resolution_encode(value)
        if len(data) > 4096:
            resolution_reject('RELEASE_DESCRIPTOR_LIMIT')
        print('TOOLCHAIN_RELEASE=' + base64.b64encode(data).decode('ascii'))
    finally:
        signal.alarm(0)


def resolution_cli():
    try:
        resolution_main(sys.argv[1:])
    except PreparationFailure as error:
        category = error.category if error.category in ('metadata', 'network', 'timeout', 'unavailable') else 'metadata'
        detail = error.detail if re.fullmatch('[A-Z_]{1,96}', error.detail) else 'RELEASE_RESOLVE_REJECTED'
        print('TOOLCHAIN_FAILURE=' + category + ':' + detail)
        sys.exit(65)
    except (OSError, ValueError, KeyError, StopIteration):
        print('TOOLCHAIN_FAILURE=metadata:RELEASE_RESOLVE_REJECTED')
        sys.exit(65)
