def reuse_system(ecosystem, identity):
    # A shared dotnet root may select another SDK/runtime through global.json or framework roll-forward.
    if ecosystem == 'DOTNET':
        return None
    binaries = {'JAVA': ['java', 'javac', 'jar', 'javadoc'], 'NODE': ['node', 'npm', 'npx'],
                'PYTHON': ['python3'], 'DOTNET': ['dotnet'], 'KOTLIN': ['kotlinc', 'kotlin'],
                'GO': ['go', 'gofmt'], 'RUST': ['rustc', 'cargo', 'rustdoc'],
                'PHP': ['php'], 'RUBY': ['ruby', 'gem', 'bundle']}[ecosystem]
    launcher = shutil.which(binaries[0], path='/usr/local/bin:/usr/bin:/bin')
    if launcher is None:
        return None
    executable = pathlib.Path(launcher).resolve()
    home = executable.parent if ecosystem == 'DOTNET' else executable.parent.parent
    try:
        owned_directory(home)
        if not executable.is_file() or executable.stat().st_uid != 0 or executable.stat().st_mode & 0o022:
            return None
        probe(ecosystem, home, identity)
        files = []
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
    marker = directory / '.w2l-toolchain.json'
    if marker.is_file():
        record = json.loads(marker.read_text())
        verify_system(record)
        return record
    if directory.exists():
        shutil.rmtree(directory)
    try:
        directory.mkdir()
        (directory / 'bin').mkdir()
        for f in files:
            target = directory / f['name'] if ecosystem == 'DOTNET' else directory / 'bin' / f['name']
            target.symlink_to(f['path'])
        # Keep required runtime libraries reachable while exposing only this ecosystem's commands on PATH.
        for name in ('lib', 'lib64', 'share', 'conf', 'include', 'jmods', 'jre', 'pkg', 'src', 'misc', 'api', 'VERSION'):
            if (home / name).exists():
                (directory / name).symlink_to(home / name, target_is_directory=True)
        record = {'ecosystem': ecosystem, 'version': identity, 'directory': str(directory), 'origin': 'SYSTEM',
                  'source': 'system:' + str(executable), 'sha256': digest, 'system_files': files}
        marker.write_text(json.dumps(record, sort_keys=True))
        directory.chmod(0o755)
        (directory / 'bin').chmod(0o755)
        marker.chmod(0o444)
        return record
    except BaseException:
        shutil.rmtree(directory)
        raise


def verify_system(record):
    if record.get('origin') != 'SYSTEM':
        return
    for f in record['system_files']:
        file = pathlib.Path(f['path'])
        owned_directory(file.parent)
        if file.is_symlink() or file.stat().st_uid != 0 or file.stat().st_mode & 0o022 or hashlib.sha256(file.read_bytes()).hexdigest() != f['sha256']:
            fail('integrity', 'a pinned system tool has changed since preparation')


def installation_key(ecosystem, identity, expected):
    recipe = '\nruby-openssl-3.0.3' if ecosystem == 'RUBY' and version_key(identity)[:2] == (3, 0) else ''
    return hashlib.sha256((ecosystem + '\n' + identity + '\n' + expected + recipe).encode()).hexdigest()


def ruby_legacy_tls(destination, work):
    # Ruby 3.0's bundled OpenSSL 2.2 cannot use OpenSSL 3. Install this compatible default-gem replacement privately.
    version, digest = '3.0.3', 'e24fcd69f6e0bac1e1c3cb8667d0ded5c1f6c59d010ecb5c857a771c9cb7565c'
    gem = work / ('openssl-' + version + '.gem')
    url = 'https://rubygems.org/downloads/' + gem.name
    download(url, gem, 'sha256', digest)
    environment = dict(os.environ, PATH=str(destination / 'bin') + ':/usr/bin:/bin')
    run([str(destination / 'bin/ruby'), str(destination / 'bin/gem'), 'install', '--local', str(gem),
         '--no-document', '--ignore-dependencies'], work, environment)
    return {'version': version, 'source': url, 'sha256': digest}


def seal_installation(destination):
    owned_directory(destination)
    marker = destination / '.w2l-toolchain.json'
    if marker.is_symlink():
        fail('ownership', 'toolchain completion marker must not be a link')
    for directory, dirs, files in os.walk(destination):
        for path in [pathlib.Path(directory)] + [pathlib.Path(directory) / f for f in files]:
            if not path.is_symlink():
                path.chmod(0o755 if path.is_dir() or path.stat().st_mode & 0o111 else 0o644)
    marker.chmod(0o444)


def install(ecosystem, identity, url, algorithm, expected, layout):
    if algorithm not in ('sha256', 'sha512') or not re.fullmatch('[a-f0-9]{' + str(64 if algorithm == 'sha256' else 128) + '}', expected):
        fail('metadata', 'invalid official artifact digest')
    key = installation_key(ecosystem, identity, expected)
    destination = ROOT / 'versions' / (ecosystem.lower() + '-' + key)
    owned_directory(destination)
    marker = destination / '.w2l-toolchain.json'
    if marker.is_file():
        record = json.loads(marker.read_text())
        probe(ecosystem, destination, identity)
        seal_installation(destination)
        return record
    if destination.exists():
        # A directory without its completion marker cannot have been admitted to any immutable release binding.
        shutil.rmtree(destination)
    work_parent = ROOT / 'work'
    work_parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='prepare-', dir=work_parent) as temporary:
        work = pathlib.Path(temporary)
        archive = work / 'release.archive'
        sha256 = download(url, archive, algorithm, expected)
        unpacked = work / 'unpacked'
        unpacked.mkdir()
        extract(archive, unpacked)
        source = unpacked if layout == 'flat' else next(iter(unpacked.iterdir()))
        if layout != 'flat' and (not source.is_dir() or len(list(unpacked.iterdir())) != 1):
            fail('archive', 'unexpected official archive layout')
        try:
            dependencies = {}
            if layout in ('flat', 'archive'):
                shutil.copytree(source, destination, symlinks=True)
            else:
                destination.mkdir(parents=True)
                if layout == 'rust':
                    run(['/bin/sh', str(source / 'install.sh'), '--prefix=' + str(destination), '--disable-ldconfig'], source)
                else:
                    source_dependencies(layout)
                    options = {'python': ['--with-ensurepip=install'],
                               'php': ['--disable-all', '--enable-cli', '--enable-mbstring', '--with-openssl', '--with-zlib', '--enable-phar', '--enable-tokenizer', '--enable-session', '--enable-filter', '--with-curl', '--enable-pdo', '--with-pdo-pgsql', '--with-pdo-mysql=mysqlnd', '--with-mysqli=mysqlnd', '--with-pgsql', '--with-sqlite3', '--with-pdo-sqlite'],
                               'ruby': ['--disable-install-doc']}[layout]
                    if layout == 'ruby' and version_key(identity)[:2] == (3, 0):
                        options.append('--with-out-ext=openssl')
                    run([str(source / 'configure'), '--prefix=' + str(destination)] + options, source)
                    run(['make', '-j2'], source)
                    run(['make', 'install'], source)
                    if layout == 'ruby' and version_key(identity)[:2] == (3, 0):
                        dependencies['openssl'] = ruby_legacy_tls(destination, work)
            probe(ecosystem, destination, identity)
            record = {'ecosystem': ecosystem, 'version': identity, 'directory': str(destination),
                      'origin': 'MANAGED', 'source': url, 'sha256': sha256,
                      'official_algorithm': algorithm, 'official_digest': expected, 'dependencies': dependencies}
            marker.write_text(json.dumps(record, sort_keys=True))
            seal_installation(destination)
            return record
        except BaseException:
            if destination.exists():
                shutil.rmtree(destination)
            raise


def probe(ecosystem, directory, expected):
    commands = {'JAVA': ['bin/java', '-version'], 'NODE': ['bin/node', '--version'],
                'PYTHON': ['bin/python3', '--version'], 'DOTNET': ['dotnet', '--list-sdks'],
                'KOTLIN': ['bin/kotlinc', '-version'], 'GO': ['bin/go', 'version'],
                'RUST': ['bin/rustc', '--version'], 'PHP': ['bin/php', '-r', 'echo PHP_VERSION;'],
                'RUBY': ['bin/ruby', '-e', 'print RUBY_VERSION']}
    command = list(commands[ecosystem])
    command[0] = str(directory / command[0])
    returncode, output = probe_output(command)
    tokens = re.findall(r'\d+(?:[._]\d+)*(?:u\d+)?(?:(?:\+|-b)\d+(?:\.\d+)*)?', output)
    if ecosystem == 'JAVA':
        tokens = [t[2:].replace('_', '.') if t.startswith('1.8') else t for t in tokens]
    if returncode or not any(version_key(t) == version_key(expected)
                             and release_build(t) == release_build(expected) for t in tokens):
        fail('probe', 'installed tool did not report the selected exact release')
    if ecosystem == 'JAVA':
        run([str(directory / 'bin/javac'), '-version'])
    if ecosystem == 'PYTHON':
        run([str(directory / 'bin/python3'), '-c', 'import ssl, sqlite3, bz2, lzma, venv, zlib'])
    if ecosystem == 'RUBY':
        run([str(directory / 'bin/ruby'), '-e', 'require "openssl"; require "zlib"; require "yaml"'])

