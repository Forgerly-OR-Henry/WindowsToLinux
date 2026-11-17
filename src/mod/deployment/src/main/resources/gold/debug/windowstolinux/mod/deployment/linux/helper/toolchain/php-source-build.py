PHP_SOURCE_RECIPE = 'php-dynamic-user-cli-v1-gnu-make-' + GNU_MAKE_SOURCE_VERSION
PHP_REQUIRED_EXTENSIONS = ('curl', 'iconv', 'mbstring', 'pdo', 'pdo_sqlite', 'zip')

PHP_SOURCE_WORKER = gnu_make_worker(r'''
import zipfile

MAX_ARCHIVE = 2 * 1024 * 1024 * 1024
PHP_STAGE = pathlib.Path("/tmp/stage")
PHP_DESTDIR = pathlib.Path("/tmp/wtl-php-destdir")

def fail(category, detail):
    sys.exit(failure)
'''+ PHP_ARCHIVE_BOUNDARIES + r'''

def php_extract(version):
    source = pathlib.Path("/tmp/wtl-toolchain-source")
    source.mkdir(mode=0o700)
    release = source / ("php-" + version)
    extract("/tmp/wtl-toolchain-release", source)
    if list(source.iterdir()) != [release] or release.is_symlink() or not release.is_dir():
        sys.exit(96)
    configure = release / "configure"
    if not stat.S_ISREG(configure.lstat().st_mode) or not configure.stat().st_mode & 0o111:
        sys.exit(96)
    return release

def php_stage_output(prefix):
    # The outer worker bind already associates /tmp/stage and the final prefix with the same private stage.
    # Propagate only the precisely nested INSTALL_ROOT output. Neither destination path is a host write.
    parts = pathlib.PurePosixPath(prefix)
    if not parts.is_absolute() or parts.parts[0] != "/" or len(parts.parts) < 3:
        sys.exit(95)
    install = PHP_DESTDIR
    for name in parts.parts[1:]:
        if name in (".", "..") or not stat.S_ISDIR(install.lstat().st_mode):
            sys.exit(95)
        child = install / name
        if list(install.iterdir()) != [child]:
            sys.exit(95)
        install = child
    if (not stat.S_ISDIR(install.lstat().st_mode) or not stat.S_ISDIR(PHP_STAGE.lstat().st_mode)
            or list(PHP_STAGE.iterdir())):
        sys.exit(95)
    executable = install / "bin" / "php"
    if (not stat.S_ISDIR(executable.parent.lstat().st_mode)
            or not stat.S_ISREG(executable.lstat().st_mode) or not executable.stat().st_mode & 0o111):
        sys.exit(95)
    for entry in install.iterdir():
        entry.rename(PHP_STAGE / entry.name)

def php_probe(executable, version, system):
    script = (
        'if (PHP_SAPI !== "cli") exit(1); '
        'if (PHP_VERSION !== $argv[1] && ($argv[2] !== "SYSTEM" || '
        '!preg_match("/\\A".preg_quote($argv[1], "/")."-[0-9][0-9A-Za-z+._~:-]*\\z/D", PHP_VERSION))) exit(1); '
        'foreach (array_slice($argv, 3) as $extension) if (!extension_loaded($extension)) exit(1);'
    )
    config = () if system else ("-n",)
    command(executable, *config, "-r", script, "--", version,
            "SYSTEM" if system else "MANAGED", *PHP_REQUIRED_EXTENSIONS)

failure = 90
try:
    mode, network, mount, prefix, version, *arguments = sys.argv[1:]
    require_isolated(network, mount)
    if mode == "build":
        failure = 95
        device, inode, flavor, *resource_limits, _unit_deadline, _clock_namespace = arguments
        if flavor != "standard":
            sys.exit(failure)
        require_staging_mount(prefix, device, inode)
        require_staging_mount(PHP_STAGE, device, inode)
        failure = 126
        require_ruby_resources(*resource_limits)
        failure = 97
        release = php_extract(version)
        verify_build_scratch(release, prefix)
        verify_native_build_chain(release)
        bootstrap_gnu_make(resource_limits[0])
        failure = 98
        environment["MAKE"] = GNU_MAKE_PROGRAM
        command(str(release / "configure"), "--prefix=" + prefix,
                "--disable-all", "--enable-cli", "--enable-mbstring", "--with-iconv",
                "--with-openssl", "--with-zlib", "--with-zip", "--enable-phar", "--enable-tokenizer",
                "--enable-session", "--enable-filter", "--with-curl", "--enable-pdo", "--with-pdo-pgsql",
                "--with-pdo-mysql=mysqlnd", "--with-mysqli=mysqlnd", "--with-pgsql", "--with-sqlite3",
                "--with-pdo-sqlite", cwd=release)
        failure = 99
        command(GNU_MAKE_PROGRAM, GNU_MAKE_OVERRIDE, "-j2", cwd=release)
        failure = 100
        PHP_DESTDIR.mkdir(mode=0o700)
        command(GNU_MAKE_PROGRAM, GNU_MAKE_OVERRIDE, "install",
                "INSTALL_ROOT=" + str(PHP_DESTDIR), "DESTDIR=" + str(PHP_DESTDIR), cwd=release)
        require_staging_mount(prefix, device, inode)
        require_staging_mount(PHP_STAGE, device, inode)
        php_stage_output(prefix)
    else:
        failure = 126
        require_ruby_resources(*arguments)
    failure = 103
    php_probe(prefix if PHP_SYSTEM_PROBE else prefix + "/bin/php", version, PHP_SYSTEM_PROBE)
except (OSError, KeyError, ValueError, subprocess.SubprocessError, tarfile.TarError):
    sys.exit(failure)
''')


def php_protected_executable(executable):
    owned_directory(executable.parent)
    info = executable.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o7022
            or not info.st_mode & 0o111):
        fail('integrity', 'PHP requires a protected regular executable')


def php_system_launcher(record, directory):
    files = record.get('system_files')
    if (record.get('ecosystem') != 'PHP' or record.get('origin') != 'SYSTEM'
            or record.get('directory') != str(directory) or not isinstance(files, list)
            or len(files) != 1 or not isinstance(files[0], dict) or files[0].get('name') != 'php'
            or not isinstance(files[0].get('path'), str) or not tracked_digest(files[0].get('sha256'))):
        fail('integrity', 'PHP system marker lacks its pinned executable')
    executable = pathlib.Path(files[0]['path'])
    if not executable.is_absolute() or executable.resolve(strict=True) != executable:
        fail('integrity', 'PHP system marker has an invalid executable path')
    digest = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    version = record.get('version')
    if (not isinstance(version, str) or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version)
            or record.get('source') != 'system:' + str(executable) or record.get('sha256') != digest
            or directory != ROOT / 'versions' / ('php-' + hashlib.sha256(
                ('PHP\n' + version + '\nsystem\n' + digest).encode()).hexdigest())):
        fail('integrity', 'PHP system marker names a different installation')
    owned_directory(directory / 'bin')
    wrapper = directory / 'bin' / 'php'
    if (not wrapper.is_symlink() or wrapper.lstat().st_uid != 0
            or os.readlink(wrapper) != str(executable)):
        fail('integrity', 'PHP system wrapper differs from its pinned executable')
    php_protected_executable(executable)
    return executable


def php_source_recipe_key(identity, expected):
    return hashlib.sha256(('PHP\n' + identity + '\n' + expected + '\n' + PHP_SOURCE_RECIPE +
                           '\n' + GNU_MAKE_SOURCE_SHA256).encode()).hexdigest()


def php_release_url(url, version, historical=False):
    return (isinstance(url, str) and official_url(url, historical=historical) == url
            and urllib.parse.urlsplit(url).path.rsplit('/', 1)[-1] == 'php-' + version + '.tar.gz')


def require_php_source_record(record, directory):
    identity, expected = record.get('version'), record.get('official_digest')
    if (record.get('ecosystem') != 'PHP' or record.get('origin') != 'MANAGED'
            or record.get('directory') != str(directory)
            or not isinstance(identity, str) or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', identity)
            or not tracked_digest(expected) or record.get('official_algorithm') != 'sha256'
            or record.get('sha256') != expected or record.get('recipe') != PHP_SOURCE_RECIPE
            or record.get('dependencies') != {'gnu_make': gnu_make_dependency()}
            or not php_release_url(record.get('source'), identity, historical=True)
            or directory != ROOT / 'versions' / ('php-' + php_source_recipe_key(identity, expected))):
        fail('integrity', 'managed PHP marker differs from the isolated official source recipe')
    marker = directory / '.w2l-toolchain.json'
    if marker.lstat().st_mode & 0o222:
        fail('integrity', 'managed PHP completion marker is not sealed')
    php_protected_executable(directory / 'bin' / 'php')


def php_unit(unit, directory, version, build_mounts=None, probe_mount=None, system=False):
    constants = ('PHP_SYSTEM_PROBE = ' + repr(system) + '\nPHP_REQUIRED_EXTENSIONS = ' +
                 repr(PHP_REQUIRED_EXTENSIONS) + '\n')
    source_unit(unit, directory, version, 'php', constants + PHP_SOURCE_WORKER, build_mounts, probe_mount)


def php_system_probe(executable, version):
    php_protected_executable(executable)

    def probe_unit(unit, path, expected, probe_mount):
        php_unit(unit, path, expected, probe_mount=probe_mount, system=True)

    source_probe(executable, version, 'php', probe_unit)


def php_source_probe(directory, version):
    php_protected_executable(directory / 'bin' / 'php')
    source_probe(directory, version, 'php', php_unit)


def php_record_probe(record, directory, version):
    if record['origin'] == 'SYSTEM':
        php_system_probe(php_system_launcher(record, directory), version)
    else:
        require_php_source_record(record, directory)
        php_source_probe(directory, version)


def php_source_publish(stage, destination):
    ruby_publish(stage, destination, executable='php')
    php_protected_executable(destination / 'bin' / 'php')


_previous_php_installation_key = installation_key
_previous_php_install = install


def install_key_with_php_recipe(ecosystem, identity, expected):
    if ecosystem == 'PHP':
        return php_source_recipe_key(identity, expected)
    return _previous_php_installation_key(ecosystem, identity, expected)


def install_with_php_isolation(ecosystem, identity, url, algorithm, expected, layout):
    if ecosystem == 'PHP':
        if not php_release_url(url, identity):
            fail('metadata', 'PHP official source filename differs from the selected release')
        return source_install(ecosystem, PHP_SOURCE_RECIPE, identity, url, algorithm, expected, layout,
                              php_unit, php_source_publish, php_source_probe)
    return _previous_php_install(ecosystem, identity, url, algorithm, expected, layout)


installation_key = install_key_with_php_recipe
install = install_with_php_isolation
