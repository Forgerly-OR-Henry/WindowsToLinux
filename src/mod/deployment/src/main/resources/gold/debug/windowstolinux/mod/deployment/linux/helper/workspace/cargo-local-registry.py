# Populate a Cargo local registry without loading project code or Cargo configuration.
import stat
import tarfile


def cargo_lock_packages(data):
    # Cargo-generated lock versions 3 and 4 use this closed set of TOML statements.
    # Parse only that syntax so the worker also works on hosts with Python 3.9/3.10.
    packages = []
    fields = None
    dependencies = False
    lock_version = False
    for raw in data.decode('utf-8').split('\n'):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if dependencies:
            if line == ']':
                dependencies = False
            elif not re.fullmatch(r'"[^"\\]+"[,]?', line):
                raise MirrorFailure('unsupported Cargo.lock dependency syntax')
        elif line == '[[package]]' and lock_version:
            fields = {}
            packages.append(fields)
        elif fields is None and not lock_version and re.fullmatch(r'version\s*=\s*[34]', line):
            lock_version = True
        elif fields is not None and line in ('dependencies = [', 'dependencies = []'):
            if 'dependencies' in fields:
                raise MirrorFailure('duplicate Cargo.lock dependency field')
            fields['dependencies'] = True
            dependencies = line.endswith('[')
        else:
            match = re.fullmatch(r'(name|version|source|checksum)\s*=\s*"([^"\\]+)"', line)
            if fields is None or not match or match[1] in fields:
                raise MirrorFailure('unsupported Cargo.lock field')
            fields[match[1]] = match[2]
    if not packages or dependencies:
        raise MirrorFailure('incomplete Cargo.lock')
    locked = {}
    for package in packages:
        name, version = package.get('name', ''), package.get('version', '')
        if not re.fullmatch('[A-Za-z0-9_-]{1,64}', name) or not re.fullmatch(
                r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?', version):
            raise MirrorFailure('invalid Cargo.lock package identity')
        if len(version) > 128:
            raise MirrorFailure('Cargo.lock package version exceeds limit')
        source = package.get('source')
        if source is None and 'checksum' not in package:
            continue
        checksum = package.get('checksum', '')
        if source != 'registry+https://github.com/rust-lang/crates.io-index' or not re.fullmatch('[0-9a-f]{64}', checksum):
            raise MirrorFailure('Cargo.lock contains a dependency outside the locked crates.io route')
        versions = locked.setdefault(name, {})
        if version in versions:
            raise MirrorFailure('duplicate Cargo.lock package version')
        versions[version] = checksum
    return locked


def cargo_prefetch_lock(archive, expected, member):
    parts = pathlib.PurePosixPath(member)
    if parts.is_absolute() or '..' in parts.parts or parts.as_posix() != member or parts.name != 'Cargo.lock':
        raise MirrorFailure('invalid Cargo.lock archive path')
    if not re.fullmatch('[0-9a-f]{64}', expected):
        raise MirrorFailure('invalid Cargo source archive digest')
    with os.fdopen(os.open(archive, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as original:
        identity = os.fstat(original.fileno())
        if not stat.S_ISREG(identity.st_mode) or identity.st_nlink != 1:
            raise MirrorFailure('invalid Cargo source archive')
        digest = hashlib.sha256()
        for block in iter(lambda: original.read(1048576), b''):
            digest.update(block)
        if digest.hexdigest() != expected:
            raise MirrorFailure('Cargo source archive digest differs')
        original.seek(0)
        result = None
        with tarfile.open(fileobj=original, mode='r|gz') as source:
            for entry in source:
                if entry.name != member:
                    continue
                if result is not None or not entry.isfile() or not 0 < entry.size <= 4194304:
                    raise MirrorFailure('invalid or duplicate Cargo.lock archive member')
                with source.extractfile(entry) as stream:
                    result = stream.read(4194305)
        if result is None:
            raise MirrorFailure('Cargo.lock archive member is absent')
        return cargo_lock_packages(result)


def cargo_index_path(name):
    name = name.lower()
    size = len(name)
    if size < 3:
        return str(size) + '/' + name
    if size == 3:
        return '3/' + name[0] + '/' + name
    return name[:2] + '/' + name[2:4] + '/' + name


def cargo_index_entries(path, name, versions):
    selected = {}
    for line in path.read_bytes().split(b'\n'):
        if not line.strip():
            continue
        entry = json.loads(line)
        if not isinstance(entry, dict):
            raise ValueError('Cargo registry index entry is not an object')
        version = entry.get('vers')
        if isinstance(version, str) and version in versions:
            if entry.get('name') != name or entry.get('cksum') != versions[version] or version in selected:
                raise ValueError('Cargo registry index differs from the original lock')
            selected[version] = line
    if selected.keys() != versions.keys():
        raise ValueError('Cargo registry index lacks an original locked version')
    return b'\n'.join(selected.values()) + b'\n'


def mirror_cargo_prefetch(policy, archive, expected, member, destination):
    locked = cargo_prefetch_lock(archive, expected, member)
    target = pathlib.Path(destination)
    with tempfile.TemporaryDirectory(prefix='wtl-cargo-', dir=target.parent) as directory:
        staging = pathlib.Path(directory) / 'registry'
        (staging / 'index').mkdir(parents=True)
        for name, versions in sorted(locked.items()):
            relative = cargo_index_path(name)
            index = staging / 'index' / relative
            mirror_transfer(policy, 'RUST_INDEX', 'https://index.crates.io/' + relative, index, limit=33554432,
                            validator=lambda path, headers: cargo_index_entries(path, name, versions))
            index.write_bytes(cargo_index_entries(index, name, versions))
            for version, checksum in sorted(versions.items()):
                filename = name + '-' + version + '.crate'
                mirror_transfer(policy, 'RUST_CRATE', 'https://static.crates.io/crates/' + name + '/' + filename,
                                staging / filename, 'sha256', checksum)
        if os.path.lexists(target):
            raise MirrorFailure('Cargo local registry already exists')
        staging.rename(target)
