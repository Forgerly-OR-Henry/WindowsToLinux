def discover_system(ecosystem, identity):
    # A shared dotnet root may select another SDK/runtime through global.json or framework roll-forward.
    if ecosystem == 'DOTNET':
        return None
    binaries = {
        'JAVA': ['java', 'javac', 'jar', 'javadoc'],
        'NODE': ['node', 'npm', 'npx'],
        'PYTHON': ['python3'],
        'DOTNET': ['dotnet'],
        'KOTLIN': ['kotlinc', 'kotlin'],
        'GO': ['go', 'gofmt'],
        'RUST': ['rustc', 'cargo', 'rustdoc'],
        'PHP': ['php'],
        'RUBY': ['ruby', 'gem', 'bundle'],
    }[ecosystem]
    launcher = shutil.which(binaries[0], path='/usr/local/bin:/usr/bin:/bin')
    if launcher is None:
        return None
    executable = pathlib.Path(launcher).resolve()
    home = executable.parent if ecosystem == 'DOTNET' else executable.parent.parent
    try:
        owned_directory(home)
        if not executable.is_file() or executable.stat().st_uid != 0 or executable.stat().st_mode & 0o022:
            return None
        files = []
        if ecosystem == 'PHP':
            php_protected_executable(executable)
            digest = hashlib.sha256(executable.read_bytes()).hexdigest()
            php_system_probe(executable, identity)
            php_protected_executable(executable)
            if hashlib.sha256(executable.read_bytes()).hexdigest() != digest:
                return None
            files.append({'name': 'php', 'path': str(executable), 'sha256': digest})
        else:
            probe(ecosystem, home, identity)
            for name in binaries:
                file = pathlib.Path(shutil.which(name, path=str(home / 'bin') + ':' + str(home) + ':/usr/bin') or '-')
                if not file.is_file():
                    if name == binaries[0] or ecosystem == 'JAVA' and name in ('javac', 'jar'):
                        return None
                    continue
                file = file.resolve()
                owned_directory(file.parent)
                if file.stat().st_uid != 0 or file.stat().st_mode & 0o022:
                    return None
                files.append({'name': name, 'path': str(file), 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
    except (PreparationFailure, OSError, subprocess.TimeoutExpired):
        return None
    digest = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    key = hashlib.sha256((ecosystem + '\n' + identity + '\nsystem\n' + digest).encode()).hexdigest()
    directory = ROOT / 'versions' / (ecosystem.lower() + '-' + key)
    owned_directory(directory)
    return {'ecosystem': ecosystem, 'version': identity, 'directory': str(directory), 'origin': 'SYSTEM',
            'source': 'system:' + str(executable), 'sha256': digest, 'system_files': files, '_home': str(home)}


def saved_installation_marker(directory):
    owned_directory(directory)
    if directory.is_symlink():
        fail('ownership', 'installation destination must not be a link')
    if not directory.exists():
        return None
    marker = directory / '.w2l-toolchain.json'
    if not directory.is_dir():
        fail('ownership', 'installation destination must be a directory')
    try:
        info = marker.lstat()
    except FileNotFoundError:
        fail('ownership', 'existing installation has no completion marker')
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        fail('ownership', 'installation marker must be a protected root-owned regular file')
    return marker


@contextlib.contextmanager
def create_installation_directory(directory, tracked):
    if tracked is not None:
        tracked.create_destination(directory)
        yield
        return
    directory.mkdir(parents=True)
    created = directory.lstat()
    try:
        yield
    except BaseException:
        try:
            current = directory.lstat()
        except FileNotFoundError:
            pass
        else:
            if os.path.samestat(created, current):
                shutil.rmtree(directory)
        raise


def materialize_system(discovered):
    record = dict(discovered)
    home = pathlib.Path(record.pop('_home'))
    directory = pathlib.Path(record['directory'])
    ecosystem = record['ecosystem']
    files = record['system_files']
    tracked = globals().get('TRACKED_OPERATION')
    marker = saved_installation_marker(directory)
    if marker is not None:
        if ecosystem == 'PHP':
            saved = tracked_json(tracked_regular(marker, 65536))
            if saved != record:
                fail('integrity', 'saved PHP system marker differs from the verified executable')
            record = saved
        else:
            record = json.loads(marker.read_text())
        verify_system(record)
        return record
    with create_installation_directory(directory, tracked):
        marker = directory / '.w2l-toolchain.json'
        (directory / 'bin').mkdir()
        for f in files:
            target = directory / f['name'] if ecosystem == 'DOTNET' else directory / 'bin' / f['name']
            target.symlink_to(f['path'])
        # Keep required runtime libraries reachable while exposing only this ecosystem's commands on PATH.
        for name in (
            'lib',
            'lib64',
            'share',
            'conf',
            'include',
            'jmods',
            'jre',
            'pkg',
            'src',
            'misc',
            'api',
            'VERSION',
        ):
            if (home / name).exists():
                (directory / name).symlink_to(home / name, target_is_directory=True)
        directory.chmod(0o755)
        (directory / 'bin').chmod(0o755)
        if ecosystem == 'PHP':
            verify_system(record)
        if tracked is None:
            marker.write_text(json.dumps(record, sort_keys=True))
            marker.chmod(0o444)
        else:
            tracked_atomic_json(marker, record, 0o444)
        return record


def reuse_system(ecosystem, identity):
    discovered = discover_system(ecosystem, identity)
    return None if discovered is None else materialize_system(discovered)


def verify_system(record):
    if record.get('origin') != 'SYSTEM':
        return
    if record.get('ecosystem') == 'PHP':
        php_system_launcher(record, pathlib.Path(record['directory']))
    for f in record['system_files']:
        file = pathlib.Path(f['path'])
        owned_directory(file.parent)
        if (
            file.is_symlink()
            or file.stat().st_uid != 0
            or file.stat().st_mode & 0o022
            or hashlib.sha256(file.read_bytes()).hexdigest() != f['sha256']
        ):
            fail('integrity', 'a pinned system tool has changed since preparation')


def installation_key(ecosystem, identity, expected):
    recipe = '\nruby-openssl-3.0.3' if ecosystem == 'RUBY' and version_key(identity)[:2] == (3, 0) else ''
    return hashlib.sha256((ecosystem + '\n' + identity + '\n' + expected + recipe).encode()).hexdigest()


def ruby_legacy_tls(destination, work):
    # Ruby 3.0's bundled OpenSSL 2.2 cannot use OpenSSL 3. Install this compatible default-gem replacement privately.
    version, digest = '3.0.3', 'e24fcd69f6e0bac1e1c3cb8667d0ded5c1f6c59d010ecb5c857a771c9cb7565c'
    gem = work / ('openssl-' + version + '.gem')
    url = source_url('RUBYGEMS', 'downloads/' + gem.name)
    download(url, gem, 'sha256', digest)
    environment = dict(os.environ, PATH=str(destination / 'bin') + ':/usr/bin:/bin')
    run(
        [
            str(destination / 'bin/ruby'),
            str(destination / 'bin/gem'),
            'install',
            '--local',
            str(gem),
            '--no-document',
            '--ignore-dependencies',
        ],
        work,
        environment,
    )
    return {'version': version, 'source': url, 'sha256': digest}


def seal_installation(destination, marker_pending=False):
    owned_directory(destination)
    marker = destination / '.w2l-toolchain.json'
    if marker.is_symlink():
        fail('ownership', 'toolchain completion marker must not be a link')
    for directory, dirs, files in os.walk(destination):
        for path in [pathlib.Path(directory)] + [pathlib.Path(directory) / f for f in files]:
            if not path.is_symlink():
                path.chmod(0o755 if path.is_dir() or path.stat().st_mode & 0o111 else 0o644)
    if not marker_pending:
        marker.chmod(0o444)


def managed_dotnet_file_digest(path, required_mode, archive_source=False):
    def protected(info):
        return (stat.S_ISREG(info.st_mode) and (archive_source or info.st_uid == 0) and info.st_nlink == 1
                and not info.st_mode & 0o7022 and (archive_source or info.st_mode & required_mode == required_mode)
                and 0 < info.st_size <= 16 * 1024 * 1024)

    before = path.lstat()
    if not protected(before):
        fail('ownership', 'managed .NET probe requires protected regular files')
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0)
    with os.fdopen(os.open(path, flags), 'rb') as source:
        opened = os.fstat(source.fileno())
        if not protected(opened) or not os.path.samestat(before, opened):
            fail('integrity', 'managed .NET probe file changed during open')
        payload = source.read(opened.st_size + 1)
        after = os.fstat(source.fileno())
    if len(payload) != opened.st_size or any(
        not protected(current) or not os.path.samestat(opened, current)
        or (current.st_size, current.st_mtime_ns, current.st_ctime_ns)
        != (opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns)
        for current in (after, path.lstat())
    ):
        fail('integrity', 'managed .NET probe file changed during verification')
    return hashlib.sha256(payload).hexdigest()


def managed_dotnet_probe_identity(directory, version, archive_source=False):
    # The native muxer can select libhostfxr from this tree before listing SDKs. The OS loader and
    # system libraries are host prerequisites. This identity does not measure the whole SDK tree.
    if not re.fullmatch(r'[0-9]{1,6}(?:\.[0-9]{1,6}){2}', version):
        fail('metadata', 'invalid managed .NET SDK release')
    if archive_source:
        # Tar may retain vendor UID/GID. Only the verified extraction below a root-private work directory
        # can use that metadata; every installed path is checked under the stricter rules below.
        owned_directory(directory.parent)
        if directory.parent.lstat().st_mode & 0o077:
            fail('ownership', 'managed .NET archive work directory is not private')

    def require_directory(path):
        if not stat.S_ISDIR(path.lstat().st_mode):
            fail('ownership', 'managed .NET probe requires real directories')
        if not archive_source:
            owned_directory(path)

    hostfxr = directory / 'host' / 'fxr'
    sdk = directory / 'sdk'
    for path in (directory, hostfxr.parent, hostfxr, sdk, sdk / version):
        require_directory(path)
    files = {'dotnet': managed_dotnet_file_digest(directory / 'dotnet', 0o005, archive_source)}
    for runtime in hostfxr.iterdir():
        if len(files) > 16 or not re.fullmatch(r'[0-9]{1,6}(?:\.[0-9]{1,6}){2}', runtime.name):
            fail('integrity', 'unexpected managed .NET hostfxr layout')
        require_directory(runtime)
        library = runtime / 'libhostfxr.so'
        files[library.relative_to(directory).as_posix()] = managed_dotnet_file_digest(library, 0o004, archive_source)
    if len(files) == 1:
        fail('integrity', 'managed .NET hostfxr is missing')
    return files


def load_managed_dotnet_marker(directory):
    marker = saved_installation_marker(directory)
    if marker is None or marker.lstat().st_mode & 0o222:
        fail('ownership', 'managed .NET requires a sealed completion marker')
    return tracked_json(tracked_regular(marker, 65536))


def validate_managed_dotnet_record(record, directory, version):
    if not isinstance(record, dict):
        fail('integrity', 'invalid managed .NET marker')
    fields = ('ecosystem', 'version', 'directory', 'origin', 'official_algorithm')
    expected = record.get('official_digest')
    if (tuple(record.get(k) for k in fields) != ('DOTNET', version, str(directory), 'MANAGED', 'sha512')
        or not tracked_digest(record.get('sha256')) or not isinstance(expected, str)
        or not re.fullmatch('[a-f0-9]{128}', expected)
        or directory != ROOT / 'versions' / ('dotnet-' + installation_key('DOTNET', version, expected))):
        fail('integrity', 'managed .NET marker differs from the official installation identity')


def verify_managed_dotnet_probe_files(record, directory, version):
    validate_managed_dotnet_record(record, directory, version)
    if 'dotnet_probe_files' not in record:
        fail('integrity', 'managed .NET must be prepared again from its official archive')
    if record['dotnet_probe_files'] != managed_dotnet_probe_identity(directory, version):
        fail('integrity', 'managed .NET probe files differ from the verified archive')


def probe_managed_dotnet(record, directory, version):
    verify_managed_dotnet_probe_files(record, directory, version)
    command = ['/usr/bin/setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', '--no-new-privs',
               '--inh-caps=-all', '--ambient-caps=-all', '--bounding-set=-all', '--',
               str(directory / 'dotnet'), '--list-sdks']
    code, output = managed_dotnet_probe_output(command, directory)
    if code or output.splitlines() != [version + ' [' + str(directory / 'sdk') + ']']:
        fail('probe', 'managed .NET did not report the selected exact SDK and directory')


def install(ecosystem, identity, url, algorithm, expected, layout):
    global MIRROR_POINT
    if ecosystem == 'PHP' or layout == 'php':
        fail('request', 'PHP source installation requires the isolated worker')
    MIRROR_POINT = toolchain_point(ecosystem)
    tracked = globals().get('TRACKED_OPERATION')
    if algorithm not in ('sha256', 'sha512') or not re.fullmatch(
        '[a-f0-9]{' + str(64 if algorithm == 'sha256' else 128) + '}', expected
    ):
        fail('metadata', 'invalid official artifact digest')
    dotnet = ecosystem == 'DOTNET'
    if dotnet and (algorithm != 'sha512' or layout != 'flat'):
        fail('metadata', 'managed .NET requires the verified official flat SDK archive')
    key = installation_key(ecosystem, identity, expected)
    destination = ROOT / 'versions' / (ecosystem.lower() + '-' + key)
    marker = saved_installation_marker(destination)
    previous_dotnet = None
    if marker is not None:
        if not dotnet:
            record = json.loads(marker.read_text())
            probe(ecosystem, destination, identity)
            seal_installation(destination)
            return record
        previous_dotnet = load_managed_dotnet_marker(destination)
        validate_managed_dotnet_record(previous_dotnet, destination, identity)
        if previous_dotnet.get('source') != url or previous_dotnet['official_digest'] != expected:
            fail('integrity', 'managed .NET marker differs from selected official metadata')
        if 'dotnet_probe_files' in previous_dotnet:
            probe_managed_dotnet(previous_dotnet, destination, identity)
            return previous_dotnet
    work_parent = ROOT / 'work'
    if tracked is None:
        work_parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    work_context = tempfile.TemporaryDirectory(prefix='prepare-', dir=work_parent) if tracked is None else tracked.work()
    with work_context as temporary:
        work = pathlib.Path(temporary)
        archive = work / 'release.archive'
        sha256 = download(url, archive, algorithm, expected)
        if tracked is not None:
            tracked.archive_downloaded(sha256)
        unpacked = work / 'unpacked'
        unpacked.mkdir()
        extract(archive, unpacked)
        source = unpacked if layout == 'flat' else next(iter(unpacked.iterdir()))
        if layout != 'flat' and (not source.is_dir() or len(list(unpacked.iterdir())) != 1):
            fail('archive', 'unexpected official archive layout')
        dotnet_probe_files = managed_dotnet_probe_identity(source, identity, archive_source=True) if dotnet else None
        if previous_dotnet is not None:
            if previous_dotnet['sha256'] != sha256:
                fail('integrity', 'managed .NET archived bytes differ from the existing installation identity')
            # Upgrade only the marker, grounding the expected digests in the verified archive.
            previous_dotnet['dotnet_probe_files'] = dotnet_probe_files
            probe_managed_dotnet(previous_dotnet, destination, identity)
            tracked_atomic_json(marker, previous_dotnet, 0o444)
            return previous_dotnet
        with create_installation_directory(destination, tracked):
            marker = destination / '.w2l-toolchain.json'
            dependencies = {}
            if layout in ('flat', 'archive'):
                shutil.copytree(source, destination, symlinks=True, dirs_exist_ok=True)
            else:
                if layout == 'rust':
                    run(
                        ['/bin/sh', str(source / 'install.sh'), '--prefix=' + str(destination), '--disable-ldconfig'],
                        source,
                    )
                else:
                    source_dependencies(layout)
                    options = {
                        'python': ['--with-ensurepip=install'],
                        'ruby': ['--disable-install-doc'],
                    }[layout]
                    if layout == 'ruby' and version_key(identity)[:2] == (3, 0):
                        options.append('--with-out-ext=openssl')
                    run([str(source / 'configure'), '--prefix=' + str(destination)] + options, source)
                    run(['make', '-j2'], source)
                    run(['make', 'install'], source)
                    if layout == 'ruby' and version_key(identity)[:2] == (3, 0):
                        dependencies['openssl'] = ruby_legacy_tls(destination, work)
            if not dotnet:
                probe(ecosystem, destination, identity)
            record = {
                'ecosystem': ecosystem,
                'version': identity,
                'directory': str(destination),
                'origin': 'MANAGED',
                'source': url,
                'sha256': sha256,
                'official_algorithm': algorithm,
                'official_digest': expected,
                'dependencies': dependencies,
            }
            if dotnet:
                record['dotnet_probe_files'] = dotnet_probe_files
                seal_installation(destination, marker_pending=True)
                probe_managed_dotnet(record, destination, identity)
                tracked_atomic_json(marker, record, 0o444)
            elif tracked is None:
                marker.write_text(json.dumps(record, sort_keys=True))
                seal_installation(destination)
            else:
                seal_installation(destination, marker_pending=True)
                tracked_atomic_json(marker, record, 0o444)
            return record


def probe(ecosystem, directory, expected):
    if ecosystem == 'PHP':
        fail('request', 'PHP verification requires the isolated worker')
    if ecosystem == 'DOTNET':
        return probe_managed_dotnet(load_managed_dotnet_marker(directory), directory, expected)
    commands = {
        'JAVA': ['bin/java', '-version'],
        'NODE': ['bin/node', '--version'],
        'PYTHON': ['bin/python3', '-I', '-S', '-B', '--version'],
        'KOTLIN': ['bin/kotlinc', '-version'],
        'GO': ['bin/go', 'version'],
        'RUST': ['bin/rustc', '--version'],
        'RUBY': ['bin/ruby', '-e', 'print RUBY_VERSION'],
    }
    command = list(commands[ecosystem])
    command[0] = str(directory / command[0])
    returncode, output = probe_output(command)
    tokens = re.findall(r'\d+(?:[._]\d+)*(?:u\d+)?(?:(?:\+|-b)\d+(?:\.\d+)*)?', output)
    if ecosystem == 'JAVA':
        tokens = [t[2:].replace('_', '.') if t.startswith('1.8') else t for t in tokens]
    if returncode or not any(
        version_key(t) == version_key(expected) and release_build(t) == release_build(expected) for t in tokens
    ):
        fail('probe', 'installed tool did not report the selected exact release')
    if ecosystem == 'JAVA':
        run([str(directory / 'bin/javac'), '-version'])
    if ecosystem == 'PYTHON':
        run([str(directory / 'bin/python3'), '-I', '-S', '-B', '-c', 'import ssl, sqlite3, bz2, lzma, venv, zlib'])
    if ecosystem == 'RUBY':
        run([str(directory / 'bin/ruby'), '-e', 'require "openssl"; require "zlib"; require "yaml"'])
