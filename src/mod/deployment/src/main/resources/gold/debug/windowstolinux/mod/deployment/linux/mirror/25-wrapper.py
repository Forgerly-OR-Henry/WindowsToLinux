"""Bootstrap the exact wrapper distribution without modifying a project wrapper or lock."""
import stat
import tarfile
import zipfile


def mirror_wrapper(policy, point, properties, directory):
    values = {}
    for line in pathlib.Path(properties).read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith(('#', '!')):
            continue
        key, equals, value = line.partition('=')
        if not equals or key in values:
            raise MirrorFailure('ambiguous wrapper properties')
        values[key.strip()] = value.strip().replace('\\:', ':').replace('\\=', '=')
    url = values.get('distributionUrl', '')
    expected = values.get('distributionSha256Sum', '')
    algorithm = 'sha256'
    if not expected and point == 'MAVEN_DISTRIBUTION':
        matches = []
        def validate_checksum(path, headers):
            matches[:] = re.findall(r'(?<![a-fA-F0-9])[a-fA-F0-9]{128}(?![a-fA-F0-9])', path.read_text(encoding='ascii'))
            if len(matches) != 1:
                raise ValueError('Maven wrapper distribution has no unique SHA512')
        mirror_bytes(policy, point, url + '.sha512', limit=4096, validator=validate_checksum)
        algorithm, expected = 'sha512', matches[0]
    if not expected:
        raise MirrorFailure('wrapper distribution digest is required')
    root = pathlib.Path(directory)
    root.mkdir(parents=True, exist_ok=False)
    archive = root / 'distribution.archive'
    mirror_transfer(policy, point, url, archive, algorithm, expected, 536870912)
    total, count = 0, 0

    def member(name, size):
        nonlocal total, count
        path = pathlib.PurePosixPath(name.rstrip('/'))
        if not name or path.is_absolute() or '\\' in name or any(p in ('', '.', '..') for p in path.parts):
            raise MirrorFailure('unsafe wrapper distribution path')
        total, count = total + size, count + 1
        if total > 2147483648 or count > 100000:
            raise MirrorFailure('wrapper distribution extraction limit')
        return root.joinpath(*path.parts)

    seen = set()
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as package:
            for item in package.infolist():
                output = member(item.filename, item.file_size)
                mode = item.external_attr >> 16
                if stat.S_ISLNK(mode) or output in seen:
                    raise MirrorFailure('wrapper distribution link or duplicate path')
                seen.add(output)
                if item.is_dir():
                    output.mkdir(parents=True, exist_ok=True)
                else:
                    output.parent.mkdir(parents=True, exist_ok=True)
                    with package.open(item) as source, output.open('xb') as target:
                        import shutil
                        shutil.copyfileobj(source, target, 65536)
                    output.chmod(0o755 if mode & 0o111 else 0o644)
    else:
        with tarfile.open(archive) as package:
            for item in package:
                output = member(item.name, item.size)
                if not item.isfile() and not item.isdir() or output in seen:
                    raise MirrorFailure('wrapper distribution link, special or duplicate path')
                seen.add(output)
                if item.isdir():
                    output.mkdir(parents=True, exist_ok=True)
                else:
                    output.parent.mkdir(parents=True, exist_ok=True)
                    with package.extractfile(item) as source, output.open('xb') as target:
                        import shutil
                        shutil.copyfileobj(source, target, 65536)
                    output.chmod(0o755 if item.mode & 0o111 else 0o644)
    archive.unlink()
    name = 'mvn' if point == 'MAVEN_DISTRIBUTION' else 'gradle'
    executables = list(root.glob('*/bin/' + name))
    if len(executables) != 1 or not os.access(executables[0], os.X_OK):
        raise MirrorFailure('wrapper executable is absent or ambiguous')
    print(str(executables[0]))
