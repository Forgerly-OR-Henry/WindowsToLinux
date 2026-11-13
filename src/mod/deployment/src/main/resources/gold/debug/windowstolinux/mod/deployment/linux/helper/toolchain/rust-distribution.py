RUST_DISTRIBUTION_RECIPE = 'rust-component-manifest-v3'
RUST_DISTRIBUTION_COMPONENTS = ('rustc', 'rust-std-x86_64-unknown-linux-gnu', 'cargo')
RUST_TRANSFER_REASON_CODES = frozenset((
    'task-deadline', 'connect-timeout', 'no-progress', 'low-speed', 'truncated-response', 'response-size-limit',
    'digest-mismatch', 'invalid-protocol-metadata', 'source-previously-exhausted', 'incompatible-route',
))
RUST_TRANSFER_REASON_ALIASES = {
    'task deadline exceeded': 'task-deadline',
    'toolchain preparation exceeded its time budget': 'task-deadline',
    'task deadline while waiting for an in-flight object': 'task-deadline',
    'DNS returned no usable address': 'dns-no-address',
    'destination/redirect outside selected source allowlist': 'redirect-outside-allowlist',
    'URL has no exact compatible route for selected source': 'incompatible-route',
    'URLError': 'url-error',
    'TimeoutError': 'socket-timeout',
    'IncompleteRead': 'truncated-response',
    'RemoteDisconnected': 'remote-disconnected',
    'ValueError': 'invalid-response',
}


def rust_transfer_reasons(detail):
    # The shared mirror runtime reports at most six attempts. Transfer error text is never attached.
    prefix = 'selected sources exhausted: '
    if detail.startswith(prefix):
        failures = detail[len(prefix):].split(',', 6)
    elif detail.endswith((': official source request failed', ': official artifact download failed')):
        failures = [detail.partition(':')[0]]
    else:
        failures = [detail]
    return [reason if reason in RUST_TRANSFER_REASON_CODES or re.fullmatch(r'http-[1-5][0-9]{2}', reason)
            else RUST_TRANSFER_REASON_ALIASES.get(reason, 'unclassified') for reason in failures[:6]]


@contextlib.contextmanager
def rust_transfer_diagnostic(stage, point):
    try:
        yield
    except PreparationFailure as error:
        if error.category in ('network', 'timeout'):
            reasons = rust_transfer_reasons(error.detail)
            record_installation_diagnostic({
                'stage': stage, 'executable': '', 'actualExitCode': None, 'timedOut': 'task-deadline' in reasons,
                'cancelled': False, 'stdout': '', 'stderr': 'mirror-point=' + point + '\ntransfer-reasons='
                + ','.join(reasons) + '\n', 'stdoutTruncated': False, 'stderrTruncated': False,
            })
        raise


_previous_rust_distribution_release = release


def release_with_rust_diagnostic(ecosystem, branch, exact):
    if ecosystem != 'RUST':
        return _previous_rust_distribution_release(ecosystem, branch, exact)
    # An exact release bypasses tag enumeration and only fetches the distribution checksum.
    stage = 'rust-release-checksum' if exact else 'rust-release-metadata'
    with rust_transfer_diagnostic(stage, 'RUST_METADATA'):
        return _previous_rust_distribution_release(ecosystem, branch, exact)


def rust_read_manifest(path, maximum):
    if not stat.S_ISREG(path.lstat().st_mode):
        fail('archive', 'Rust distribution manifest must be a regular file')
    with path.open('rb') as stream:
        raw = stream.read(maximum + 1)
    if not raw or len(raw) > maximum or not raw.endswith(b'\n') or any(
            byte != 10 and not 32 <= byte <= 126 for byte in raw):
        fail('archive', 'Rust distribution manifest has invalid framing')
    return raw[:-1].decode('ascii').split('\n')


def rust_component_path(value):
    parts = value.split('/')
    if (len(value) > 4096 or len(parts) > 48 or parts[0] not in ('bin', 'lib', 'libexec', 'share', 'etc')
            or any(not re.fullmatch(r'[A-Za-z0-9_.+@-]{1,255}', part) or part in ('.', '..')
                   for part in parts)):
        fail('archive', 'Rust component path is outside the managed distribution')
    return tuple(parts)


def rust_component_plan(source):
    if rust_read_manifest(source / 'rust-installer-version', 16) != ['3']:
        fail('archive', 'unsupported Rust distribution manifest version')
    components = rust_read_manifest(source / 'components', 8192)
    if (len(components) != len(set(components))
            or any(not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,127}', name) for name in components)
            or not set(RUST_DISTRIBUTION_COMPONENTS).issubset(components)):
        fail('archive', 'Rust distribution is missing its required components')
    entries, directories = {}, set()
    for component in RUST_DISTRIBUTION_COMPONENTS:
        root = source / component
        if not stat.S_ISDIR(root.lstat().st_mode):
            fail('archive', 'Rust component is not a directory')
        manifest = rust_read_manifest(root / 'manifest.in', 4 * 1024 * 1024)
        for entry in manifest:
            kind, separator, name = entry.partition(':')
            if not separator or kind not in ('file', 'dir'):
                fail('archive', 'invalid Rust component directive')
            parts = rust_component_path(name)
            path = root
            for part in parts[:-1]:
                path /= part
                if not stat.S_ISDIR(path.lstat().st_mode):
                    fail('archive', 'Rust component parent is not a directory')
            rust_component_copy(path / parts[-1], parts, kind, entries, directories)
    return entries, directories


def rust_component_copy(source, parts, kind, entries, directories):
    pending = [(source, parts, kind)]
    while pending:
        path, target, expected = pending.pop()
        mode = path.lstat().st_mode
        parents = {target[:index] for index in range(1, len(target))}
        if expected == 'dir':
            if not stat.S_ISDIR(mode) or target in entries:
                fail('archive', 'invalid Rust component directory')
            directories.add(target)
            with os.scandir(path) as children:
                for child in children:
                    child_target = rust_component_path('/'.join(target + (child.name,)))
                    pending.append((path / child.name, child_target, 'dir' if child.is_dir(follow_symlinks=False) else 'file'))
                    if len(pending) > 100000:
                        fail('archive', 'Rust component entry limit exceeded')
        else:
            if not stat.S_ISREG(mode) or target in directories or target in entries:
                fail('archive', 'invalid or duplicate Rust component file')
            entries[target] = (path, 0o755 if mode & 0o111 else 0o644)
        if parents.intersection(entries):
            fail('archive', 'Rust component file has a conflicting parent')
        directories.update(parents)
        if len(entries) + len(directories) > 100000:
            fail('archive', 'Rust component entry limit exceeded')


def install_rust_distribution(identity, url, algorithm, expected, layout):
    global MIRROR_POINT
    if layout != 'rust' or algorithm != 'sha256' or not re.fullmatch('[a-f0-9]{64}', expected):
        fail('metadata', 'invalid official Rust distribution metadata')
    MIRROR_POINT = toolchain_point('RUST')
    destination = ROOT / 'versions' / ('rust-' + installation_key('RUST', identity, expected))
    marker = saved_installation_marker(destination)
    if marker is not None:
        record = json.loads(tracked_regular(marker, 65536))
        if (not isinstance(record, dict) or any(record.get(key) != value for key, value in (
                ('ecosystem', 'RUST'), ('version', identity), ('origin', 'MANAGED'), ('directory', str(destination)),
                ('source', url), ('sha256', expected), ('official_algorithm', algorithm),
                ('official_digest', expected)))
                or record.get('recipe', RUST_DISTRIBUTION_RECIPE) != RUST_DISTRIBUTION_RECIPE):
            fail('integrity', 'existing Rust installation does not match its source recipe')
        probe('RUST', destination, identity)
        return record
    tracked = globals().get('TRACKED_OPERATION')
    work_parent = ROOT / 'work'
    if tracked is None:
        owned_directory(work_parent)
        work_parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    work_context = tempfile.TemporaryDirectory(prefix='prepare-', dir=work_parent) if tracked is None else tracked.work()
    with work_context as temporary:
        work = pathlib.Path(temporary)
        archive = work / 'release.archive'
        with rust_transfer_diagnostic('rust-archive-download', 'RUST_TOOLCHAIN'):
            sha256 = download(url, archive, algorithm, expected)
        if tracked is not None:
            tracked.archive_downloaded(sha256)
        unpacked = work / 'unpacked'
        unpacked.mkdir(mode=0o700)
        extract(archive, unpacked)
        children = list(unpacked.iterdir())
        if len(children) != 1 or not stat.S_ISDIR(children[0].lstat().st_mode):
            fail('archive', 'invalid Rust distribution root')
        entries, directories = rust_component_plan(children[0])
        for binary in ('rustc', 'cargo', 'rustdoc'):
            row = entries.get(('bin', binary))
            if row is None or row[1] != 0o755:
                fail('archive', 'Rust distribution is missing a compiler or cargo executable')
        with create_installation_directory(destination, tracked):
            for parts in sorted(directories):
                directory = destination.joinpath(*parts)
                directory.mkdir()
                directory.chmod(0o755)
            for parts, (source, mode) in entries.items():
                target = destination.joinpath(*parts)
                with source.open('rb') as incoming, target.open('xb') as output:
                    shutil.copyfileobj(incoming, output, 1024 * 1024)
                target.chmod(mode)
            destination.chmod(0o755)
            probe('RUST', destination, identity)
            record = {'ecosystem': 'RUST', 'version': identity, 'directory': str(destination), 'origin': 'MANAGED',
                      'source': url, 'sha256': sha256, 'official_algorithm': algorithm, 'official_digest': expected,
                      'dependencies': {}, 'recipe': RUST_DISTRIBUTION_RECIPE}
            tracked_atomic_json(destination / '.w2l-toolchain.json', record, 0o444)
            return record


_previous_rust_distribution_install = install


def install_with_rust_distribution(ecosystem, identity, url, algorithm, expected, layout):
    if ecosystem == 'RUST':
        return install_rust_distribution(identity, url, algorithm, expected, layout)
    return _previous_rust_distribution_install(ecosystem, identity, url, algorithm, expected, layout)


def probe_managed_rust(directory, expected):
    version_pattern = r'rustc ([0-9]+\.[0-9]+\.[0-9]+)(?: [^\r\n]+)?\s*'
    output_status, output = probe_output([
        '/usr/bin/env', '-i', 'PATH=/usr/bin:/bin', 'HOME=/nonexistent', 'LC_ALL=C',
        '/usr/bin/setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', '--no-new-privs',
        str(directory / 'bin/rustc'), '--version',
    ])
    match = re.fullmatch(version_pattern, output)
    if output_status or not match or match.group(1) != expected:
        fail('probe', 'managed Rust did not report the selected exact release')


_previous_rust_distribution_probe = probe


def probe_with_rust_distribution(ecosystem, directory, expected):
    if ecosystem == 'RUST' and directory.parent == ROOT / 'versions':
        return probe_managed_rust(directory, expected)
    return _previous_rust_distribution_probe(ecosystem, directory, expected)


install = install_with_rust_distribution
probe = probe_with_rust_distribution
release = release_with_rust_diagnostic
