def admitted(ecosystem, version):
    parts = version_key(version)
    count = 1 if ecosystem in ('JAVA', 'NODE', 'DOTNET') else 2
    branch = '.'.join(str(n) for n in parts[:count]) if parts else ''
    return branch in globals().get('SHIPPED_CATALOG', {}).get(ecosystem, [])


def bind(encoded, restoring=False):
    payload = base64.b64decode(encoded, validate=True)
    if len(payload) > 65536:
        fail('request', 'binding is too large')
    rows = payload.decode().splitlines()
    if not rows or not re.fullmatch(r'WTL-TOOLS-1\t[A-Za-z0-9._-]{1,96}', rows[0]) or len(rows) > 33:
        fail('request', 'unknown binding format')
    for row in rows[1:]:
        f = row.split('\t')
        if len(f) != 11 or f[8] not in ('MANAGED', 'SYSTEM'):
            fail('request', 'invalid prepared binding')
        if not restoring and not admitted(f[0], f[6]):
            fail('unavailable', 'prepared version is outside the shipped catalog')
        directory = pathlib.Path(f[7])
        if directory.parent != ROOT / 'versions':
            fail('ownership', 'binding directory is outside the managed root')
        owned_directory(directory)
        marker = directory / '.w2l-toolchain.json'
        if marker.is_symlink() or marker.stat().st_uid != 0 or marker.stat().st_mode & 0o022:
            fail('ownership', 'binding marker must be root-owned')
        record = json.loads(marker.read_text())
        verify_system(record)
        if [record[k] for k in ('ecosystem', 'version', 'directory', 'sha256')] != [f[0], f[6], f[7], f[10]]:
            fail('integrity', 'binding differs from the prepared installation')
        if record['source'] != base64.b64decode(f[9], validate=True).decode():
            fail('integrity', 'binding source differs from prepared evidence')
        probe(f[0], directory, f[6])
        if f[0] == 'JAVA':
            os.environ['JAVA_HOME'] = str(directory)
    identity = hashlib.sha256(payload).hexdigest()
    target = ROOT / 'bindings' / identity
    target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    owned_directory(target.parent)
    if target.exists():
        if target.is_symlink() or target.read_bytes() != payload:
            fail('integrity', 'immutable binding collision')
        print('TOOLCHAIN_BINDING=' + identity)
        return
    with target.open('wb') as output:
        output.write(payload)
    target.chmod(0o444)
    print('TOOLCHAIN_BINDING=' + identity)


def existing_installation(fields):
    directory = pathlib.Path(fields[7])
    if directory.parent != ROOT / 'versions' or not re.fullmatch('[a-z]+-[a-f0-9]{64}', directory.name):
        return None
    marker = directory / '.w2l-toolchain.json'
    if not marker.exists():
        return None
    owned_directory(directory)
    if marker.is_symlink() or marker.stat().st_uid != 0 or marker.stat().st_mode & 0o022:
        fail('ownership', 'saved installation marker must be root-owned')
    record = json.loads(marker.read_text())
    if [record[k] for k in ('ecosystem', 'version', 'directory', 'origin', 'sha256')] != [
        fields[0],
        fields[6],
        fields[7],
        fields[8],
        fields[10],
    ]:
        fail('integrity', 'saved installation differs from the immutable binding')
    if record['source'] != base64.b64decode(fields[9], validate=True).decode():
        fail('integrity', 'saved installation source differs from the immutable binding')
    try:
        verify_system(record)
    except (PreparationFailure, OSError):
        if record['origin'] == 'SYSTEM':
            return None
        raise
    probe(fields[0], directory, fields[6])
    if record['origin'] == 'MANAGED':
        seal_installation(directory)
    return record


def restore(encoded):
    payload = base64.b64decode(encoded, validate=True)
    if len(payload) > 65536:
        fail('request', 'binding is too large')
    rows = payload.decode().splitlines()
    if not rows or not re.fullmatch(r'WTL-TOOLS-1\t[A-Za-z0-9._-]{1,96}', rows[0]) or len(rows) > 33:
        fail('request', 'invalid portable binding')
    # Saved identities are independent of the currently shipped branch catalog. Still rediscover through official metadata.
    for row in rows[1:]:
        f = row.split('\t')
        if (
            len(f) != 11
            or not version_key(f[6])
            or f[8] not in ('MANAGED', 'SYSTEM')
            or not re.fullmatch('[a-f0-9]{64}', f[10])
        ):
            fail('request', 'invalid portable tool identity')
        count = 1 if f[0] in ('JAVA', 'NODE', 'DOTNET') else 2
        branch = '.'.join(str(n) for n in version_key(f[6])[:count])
        record = existing_installation(f)
        if record is None:
            v, url, algorithm, expected, layout = release(f[0], branch, f[6])
            key = installation_key(f[0], v, expected)
            if f[8] == 'MANAGED' and f[7] != str(ROOT / 'versions' / (f[0].lower() + '-' + key)):
                fail('integrity', 'portable path differs from the official artifact identity')
            record = install(f[0], v, url, algorithm, expected, layout)
        if f[8] == 'MANAGED' and record['sha256'] != f[10]:
            fail('integrity', 'restored official release differs from the saved digest')
        if f[0] == 'JAVA':
            os.environ['JAVA_HOME'] = record['directory']
        # A portable system binding is relocated to the exact official release on the destination host.
        f[7], f[8], f[9], f[10] = (
            record['directory'],
            record['origin'],
            base64.b64encode(record['source'].encode()).decode(),
            record['sha256'],
        )
        rows[rows.index(row)] = '\t'.join(f)
    bind(base64.b64encode(('\n'.join(rows) + '\n').encode()).decode(), restoring=True)


def relocate_venv(release_root, binding_id):
    # Only the internal restore verb calls this; no application code runs with root privileges.
    root = pathlib.Path(release_root)
    owned_directory(root)
    if not re.fullmatch('[a-f0-9]{64}', binding_id):
        fail('request', 'invalid relocation binding')
    binding = ROOT / 'bindings' / binding_id
    payload = binding.read_bytes()
    if len(payload) > 65536 or hashlib.sha256(payload).hexdigest() != binding_id:
        fail('integrity', 'relocation binding changed')
    for row in payload.decode().splitlines()[1:]:
        fields = row.split('\t')
        if fields[0] != 'PYTHON':
            continue
        venv = root / 'source' / '.venv'
        if not venv.exists():
            continue
        owned_directory(venv / 'bin')
        selected = pathlib.Path(fields[7]) / 'bin' / 'python3'
        owned_directory(selected.parent)
        config = venv / 'pyvenv.cfg'
        if config.is_symlink() or config.stat().st_size > 8192:
            fail('integrity', 'invalid portable virtual environment')
        lines = config.read_text().splitlines()
        lines = [line for line in lines if line.partition('=')[0].strip() not in ('home', 'executable', 'command')]
        lines += ['home = ' + str(selected.parent), 'executable = ' + str(selected)]
        config.write_text('\n'.join(lines) + '\n')
        for executable in (venv / 'bin').glob('python*'):
            if re.fullmatch(r'python(?:3(?:\.\d+)?)?', executable.name):
                if executable.is_symlink() or not executable.is_file():
                    fail('integrity', 'invalid virtual environment executable')
                shutil.copyfile(selected, executable)
                executable.chmod(0o755)


def prepare_shared_directories():
    for directory in (ROOT, ROOT / 'versions'):
        owned_directory(directory)
        directory.mkdir(mode=0o755, parents=True, exist_ok=True)
        directory.chmod(0o755)


def main(args):
    global DEADLINE
    import fcntl

    if os.geteuid() != 0:
        fail('permission', 'managed preparation requires the approved privileged environment boundary')
    prepare_shared_directories()
    DEADLINE = time.monotonic() + 7200
    with (ROOT / '.prepare.lock').open('a') as lock:
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                remaining()
                time.sleep(0.2)
        if len(args) == 2 and args[0] == 'verify' and re.fullmatch('[a-f0-9]{64}', args[1]):
            binding = ROOT / 'bindings' / args[1]
            owned_directory(binding.parent)
            if binding.is_symlink() or binding.stat().st_uid != 0 or binding.stat().st_mode & 0o022:
                fail('ownership', 'binding is not root-owned')
            payload = binding.read_bytes()
            if len(payload) > 65536 or hashlib.sha256(payload).hexdigest() != args[1]:
                fail('integrity', 'binding digest changed')
            for row in payload.decode().splitlines()[1:]:
                fields = row.split('\t')
                marker = pathlib.Path(fields[7]) / '.w2l-toolchain.json'
                owned_directory(marker.parent)
                verify_system(json.loads(marker.read_text()))
            return
        if len(args) == 2 and args[0] == 'bind':
            bind(args[1])
            return
        if len(args) == 2 and args[0] == 'restore':
            restore(args[1])
            return
        if len(args) == 3 and args[0] == 'relocate-venv':
            relocate_venv(args[1], args[2])
            return
        if (
            len(args) != 6
            or args[0] != 'prepare'
            or not re.fullmatch(r'\d{1,3}(?:\.\d{1,3})?', args[2])
            or not re.fullmatch(r'(?:-|[0-9][0-9.u+_-]{0,95})', args[3])
        ):
            fail('request', 'invalid preparation arguments')
        if not admitted(args[1], args[2]):
            fail('unavailable', 'branch is outside the shipped catalog')
        if args[5] != '-':
            java = pathlib.Path(args[5])
            if java.parent != ROOT / 'versions' or not re.fullmatch('java-[a-f0-9]{64}', java.name):
                fail('request', 'invalid managed Java selection')
            owned_directory(java)
            record = json.loads((java / '.w2l-toolchain.json').read_text())
            if record['ecosystem'] != 'JAVA':
                fail('request', 'managed Java selection has the wrong ecosystem')
            os.environ['JAVA_HOME'] = str(java)
        seconds = int(args[4])
        if not 30 <= seconds <= 7200:
            fail('request', 'invalid preparation timeout')
        DEADLINE = time.monotonic() + seconds
        identity, url, algorithm, digest, layout = release(args[1], args[2], '' if args[3] == '-' else args[3])
        record = reuse_system(args[1], identity)
        if record is None:
            record = install(args[1], identity, official_url(url), algorithm, digest, layout)
        print(
            'TOOLCHAIN='
            + '|'.join(record[k] for k in ('ecosystem', 'version', 'directory', 'origin', 'source', 'sha256'))
        )


if __name__ == '__main__':
    try:
        signal.signal(signal.SIGTERM, lambda *_: fail('cancelled', 'preparation cancelled'))
        signal.signal(signal.SIGHUP, lambda *_: fail('cancelled', 'preparation session ended'))
        main(sys.argv[1:])
    except PreparationFailure as error:
        print('TOOLCHAIN_FAILURE=' + error.category + ':' + error.detail)
        sys.exit(65)
    except subprocess.TimeoutExpired:
        print('TOOLCHAIN_FAILURE=timeout:preparation command exceeded deadline')
        sys.exit(124)
    except (OSError, ValueError, KeyError, StopIteration) as error:
        print('TOOLCHAIN_FAILURE=installation:' + type(error).__name__)
        sys.exit(70)
