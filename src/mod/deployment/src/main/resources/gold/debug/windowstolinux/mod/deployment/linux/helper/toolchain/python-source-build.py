PYTHON_LEGACY_SOURCE_RECIPE = 'python-dynamic-user-build-v1'
GNU_MAKE_SOURCE_VERSION = '4.4.1'
GNU_MAKE_SOURCE_NAME = 'make-' + GNU_MAKE_SOURCE_VERSION + '.tar.gz'
GNU_MAKE_SOURCE_URL = 'https://ftp.gnu.org/gnu/make/' + GNU_MAKE_SOURCE_NAME
GNU_MAKE_SOURCE_BYTES = 2348200
GNU_MAKE_SOURCE_SHA256 = 'dd16fb1d67bfab79a72f5e8390735c49e3e8e70b4945a15ab1f81ddb78658fb3'
PYTHON_SOURCE_RECIPE = 'python-dynamic-user-build-v2-gnu-make-' + GNU_MAKE_SOURCE_VERSION
PYTHON_GNU_MAKE_FAILURES = {
    210: ('source-gnu-make-archive', 'python3'),
    211: ('source-gnu-make-extract', 'tar'),
    212: ('source-gnu-make-configure', 'configure'),
    213: ('source-gnu-make-posix-spawn', 'python3'),
    214: ('source-gnu-make-compile', 'build.sh'),
    215: ('source-gnu-make-identity', 'make'),
    216: ('source-gnu-make-recipe', 'make'),
}
PYTHON_MAKE_FAILURES = {
    112: 'start-permission-denied',
    113: 'start-path-missing',
    114: 'start-os-error',
    115: 'child-sigkill',
    116: 'child-sigxfsz',
    117: 'child-other-signal',
    118: 'output-storage-exhausted',
    119: 'output-file-too-large',
    120: 'output-resource-unavailable',
    121: 'output-permission-denied',
    122: 'output-compiler-killed',
    123: 'output-linker-error',
    124: 'output-make-prerequisite',
    125: 'output-compiler-error',
    136: 'output-permission-denied',
    137: 'output-operation-not-permitted',
    138: 'output-read-only-filesystem',
    139: 'output-exec-freeze-module-permission-denied',
    140: 'output-exec-bootstrap-python-permission-denied',
    141: 'output-exec-built-python-permission-denied',
    142: 'output-linker-output-permission-denied',
    143: 'output-assembler-output-permission-denied',
    144: 'diagnostic-pipe-error',
    172: 'output-operation-not-permitted-reported-by-python',
    173: 'output-operation-not-permitted-reported-by-archiver',
    174: 'output-operation-not-permitted-reported-by-linker',
    175: 'output-operation-not-permitted-reported-by-compiler',
    176: 'output-operation-not-permitted-reported-by-shell',
    177: 'output-operation-not-permitted-with-failed-checksharedmods-target',
    178: 'output-operation-not-permitted-with-failed-sharedmods-target',
    179: 'output-operation-not-permitted-with-failed-freeze-module-target',
    180: 'output-operation-not-permitted-with-failed-bootstrap-python-target',
    181: 'output-operation-not-permitted-with-failed-python-target',
    182: 'output-operation-not-permitted-with-failed-static-archive-target',
    183: 'output-operation-not-permitted-with-failed-configure-generated-target',
    184: 'output-operation-not-permitted-ambiguous',
    185: 'output-operation-not-permitted-reported-by-cp',
    186: 'output-operation-not-permitted-reported-by-mv',
    187: 'output-operation-not-permitted-reported-by-ln',
    188: 'output-operation-not-permitted-reported-by-mkdir',
    189: 'output-operation-not-permitted-reported-by-rm',
    190: 'output-operation-not-permitted-reported-by-chmod',
    191: 'output-operation-not-permitted-reported-by-install',
    192: 'output-operation-not-permitted-reported-by-make',
}

PYTHON_GNU_MAKE_BOOTSTRAP = r'''
import hashlib
import io
import stat
import tarfile

GNU_MAKE_ARCHIVE = "/tmp/wtl-gnu-make-source"
GNU_MAKE_BUILD = pathlib.Path("/tmp/wtl-gnu-make-build")
GNU_MAKE_RELEASE = "make-" + GNU_MAKE_SOURCE_VERSION
GNU_MAKE_PROGRAM = str(GNU_MAKE_BUILD / GNU_MAKE_RELEASE / "make")
GNU_MAKE_OVERRIDE = "MAKE=" + GNU_MAKE_PROGRAM

def read_gnu_make_archive():
    descriptor = os.open(GNU_MAKE_ARCHIVE, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1
                or info.st_size != GNU_MAKE_SOURCE_BYTES or info.st_mode & 0o222):
            sys.exit(210)
        content = source.read(GNU_MAKE_SOURCE_BYTES + 1)
    if len(content) != GNU_MAKE_SOURCE_BYTES or hashlib.sha256(content).hexdigest() != GNU_MAKE_SOURCE_SHA256:
        sys.exit(210)
    names, expanded, directories = set(), 0, 0
    with tarfile.open(fileobj=io.BytesIO(content), mode="r:gz") as archive:
        for member in archive:
            parts = member.name.split("/")
            if (len(names) >= 416 or parts[0] != GNU_MAKE_RELEASE or any(p in ("", ".", "..") for p in parts)
                    or "\\" in member.name or member.name in names or member.mode & 0o7000
                    or member.type not in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE)):
                sys.exit(210)
            names.add(member.name)
            expanded += member.size
            directories += member.isdir()
            if expanded > 8_780_526:
                sys.exit(210)
    required = {GNU_MAKE_RELEASE + path for path in ("", "/configure", "/build.sh", "/src/job.c")}
    if len(names) != 416 or directories != 21 or expanded != 8_780_526 or not required <= names:
        sys.exit(210)
    return content

def gnu_make_binary_identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid, info.st_nlink,
            info.st_size, info.st_mtime_ns, info.st_ctime_ns)

def gnu_make_binary_digest():
    descriptor = os.open(GNU_MAKE_PROGRAM, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1
                or not info.st_mode & stat.S_IXUSR or info.st_mode & 0o7022
                or not 4 < info.st_size <= 8 * 1024 * 1024):
            sys.exit(failure)
        binary = stream.read(info.st_size + 1)
        if (gnu_make_binary_identity(info) != gnu_make_binary_identity(os.fstat(descriptor))
                or gnu_make_binary_identity(info) != gnu_make_binary_identity(os.lstat(GNU_MAKE_PROGRAM))
                or len(binary) != info.st_size or not binary.startswith(b"\x7fELF")):
            sys.exit(failure)
    return hashlib.sha256(binary).hexdigest()

def bootstrap_gnu_make(unit):
    global failure
    failure = 210
    content = read_gnu_make_archive()
    failure = 211
    GNU_MAKE_BUILD.mkdir(mode=0o700)
    subprocess.run(("/usr/bin/tar", "--extract", "--gzip", "--no-same-owner", "--no-same-permissions",
                    "--file", "-", "--directory", str(GNU_MAKE_BUILD)),
                   input=content, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   env=environment, check=True, timeout=20)
    release = GNU_MAKE_BUILD / GNU_MAKE_RELEASE
    if list(GNU_MAKE_BUILD.iterdir()) != [release] or release.is_symlink() or not release.is_dir():
        sys.exit(failure)
    build_environment = dict(environment, CC="/usr/bin/cc", AR="/usr/bin/ar", RANLIB="/usr/bin/ranlib",
                             CFLAGS="-O2 -g0", CONFIG_SITE="/dev/null", MAKE="/usr/bin/false",
                             SOURCE_DATE_EPOCH="1677369600")
    failure = 212
    subprocess.run(("/bin/sh", "./configure", "--enable-posix-spawn", "--disable-nls", "--without-guile",
                    "--disable-load", "--disable-dependency-tracking", "--prefix=/tmp/wtl-make-unused-prefix"),
                   cwd=release, env=build_environment, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, check=True, timeout=60)
    failure = 213
    with (release / "src/config.h").open("rb") as settings:
        config = settings.read(65537)
    if len(config) > 65536 or config.splitlines().count(b"#define USE_POSIX_SPAWN 1") != 1:
        sys.exit(failure)
    failure = 214
    subprocess.run(("/bin/sh", "./build.sh"), cwd=release, env=build_environment,
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   check=True, timeout=75)
    failure = 215
    digest = gnu_make_binary_digest()
    with tempfile.TemporaryDirectory(prefix=".wtl-gnu-make-check-", dir="/tmp") as directory:
        with tempfile.TemporaryFile(dir=directory) as output:
            subprocess.run((GNU_MAKE_PROGRAM, "--version"), cwd=directory, env=environment,
                           stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=5)
            output.seek(0)
            version = output.read(2049)
        if (len(version) > 2048
                or version.partition(b"\n")[:2] != (b"GNU Make " + GNU_MAKE_SOURCE_VERSION.encode("ascii"), b"\n")):
            sys.exit(failure)
        failure = 216
        with (pathlib.Path(directory) / "probe.mk").open("xb") as makefile:
            makefile.write(b".PHONY: probe\nprobe:\n\t@/usr/bin/true\n")
        with tempfile.TemporaryFile(dir=directory) as output:
            subprocess.run((GNU_MAKE_PROGRAM, "--no-print-directory", "-rR", "-j2", "-f", "probe.mk", "probe"),
                           cwd=directory, env=environment, stdin=subprocess.DEVNULL,
                           stdout=output, stderr=subprocess.STDOUT, check=True, timeout=5)
            output.seek(0)
            if output.read(1):
                sys.exit(failure)
    if not only_source_worker_in_cgroup(unit) or gnu_make_binary_digest() != digest:
        sys.exit(failure)
    environment.update(MAKE=GNU_MAKE_PROGRAM, PATH=str(release) + ":" + environment["PATH"])
'''

PYTHON_SOURCE_WORKER = SOURCE_WORKER + (
    f"\nGNU_MAKE_SOURCE_VERSION = {GNU_MAKE_SOURCE_VERSION!r}\n"
    f"GNU_MAKE_SOURCE_BYTES = {GNU_MAKE_SOURCE_BYTES}\nGNU_MAKE_SOURCE_SHA256 = {GNU_MAKE_SOURCE_SHA256!r}\n"
) + PYTHON_GNU_MAKE_BOOTSTRAP + r'''
import re
import signal

MAKE_REPORTER = rb"(?:(?:/(?:usr/)?bin/)?make|" + re.escape(GNU_MAKE_PROGRAM.encode("ascii")) + rb")(?:\[[0-9]+\])?"
# Child output is untrusted. Retain only the highest-priority matching fixed hint in the unit.
MAKE_OUTPUT_RULES = (
    (118, rb"(?i)(?:^|: )(?:no space left on device|disk quota exceeded)\.?$"),
    (119, rb"(?i)(?:^|: )(?:file too large|file size limit exceeded)(?: \(core dumped\))?\.?$"),
    (120, rb"(?i)(?:^|: )resource temporarily unavailable\.?$"),
    (121, rb"(?i)(?:^|: )permission denied\.?$"),
    (122, rb"(?i)^(?:[^:]+: )?fatal error: killed signal terminated program cc1(?:plus)?(?:\s.*)?$"),
    (123, rb"(?i)(?:^collect2: error: ld returned [0-9]+ exit status$|: undefined reference to )"),
    (124, rb"^" + MAKE_REPORTER + rb": \*\*\* No rule to make target "),
    (125, rb"^.+:[0-9]+(?::[0-9]+)?: (?:fatal )?error: "),
)
MAKE_PERMISSION_RULES = (
    (139, rb"^(?:/(?:usr/)?bin/)?(?:ba)?sh: (?:[0-9]+: )?\./Programs/_freeze_module: Permission denied$"),
    (140, rb"^(?:/(?:usr/)?bin/)?(?:ba)?sh: (?:[0-9]+: )?\./_bootstrap_python: Permission denied$"),
    (141, rb"^(?:/(?:usr/)?bin/)?(?:ba)?sh: (?:[0-9]+: )?\./python: Permission denied$"),
    (142, rb"^(?:[^: ]*/)?ld: cannot open output file .+: Permission denied$"),
    (143, rb"^(?:[^: ]*/)?as: (?:Fatal error: )?can't create .+: Permission denied$"),
)
MAKE_EPERM_REPORTERS = (
    (172, rb"^PermissionError: \[Errno 1\] "),
    (173, rb"^(?:[^: ]*/)?(?:(?:[a-z0-9_]+-)*ar|ranlib): "),
    (174, rb"^(?:[^: ]*/)?(?:(?:[a-z0-9_]+-)*ld(?:\.bfd)?|collect2): "),
    (175, rb"^(?:[^: ]*/)?(?:gcc(?:-[0-9]+)?|cc|cc1|as): "),
    (176, rb"^(?:/(?:usr/)?bin/)?(?:ba)?sh: "),
    (185, rb"^(?:/(?:usr/)?bin/)?cp: "),
    (186, rb"^(?:/(?:usr/)?bin/)?mv: "),
    (187, rb"^(?:/(?:usr/)?bin/)?ln: "),
    (188, rb"^(?:/(?:usr/)?bin/)?mkdir: "),
    (189, rb"^(?:/(?:usr/)?bin/)?rm: "),
    (190, rb"^(?:/(?:usr/)?bin/)?chmod: "),
    (191, rb"^(?:/(?:usr/)?bin/)?install: "),
    (192, rb"^" + MAKE_REPORTER + rb": "),
)
MAKE_FAILED_TARGET = rb"^" + MAKE_REPORTER + rb": \*\*\* \[(?:[^\[\]]+:[0-9]+: )?([^\[\]]+)\] Error [0-9]+$"
MAKE_TARGETS = {
    b"checksharedmods": 177, b"sharedmods": 178, b"Programs/_freeze_module": 179,
    b"_bootstrap_python": 180, b"python": 181, b"Makefile": 183, b"pyconfig.h": 183, b"config.status": 183,
    b"pybuilddir.txt": 183,
}

def make_failed_target(line):
    match = re.match(MAKE_FAILED_TARGET, line)
    if not match:
        return None
    target = match[1]
    if re.fullmatch(rb"(?:libpython3\.[0-9]+[a-z]*|Modules/(?:_decimal/libmpdec/libmpdec|expat/libexpat|_hacl/libHacl_Hash_SHA2))\.a", target):
        return 182
    return MAKE_TARGETS.get(target, 0)

def make_output_hint(stream):
    hint, permissions, eperm_reporters, failed_targets = 126, set(), set(), set()
    for line in diagnostic_output_lines(stream):
        for code, pattern in MAKE_OUTPUT_RULES:
            if code < hint and re.search(pattern, line):
                hint = code
        for code, pattern in MAKE_PERMISSION_RULES:
            if re.search(pattern, line):
                permissions.add(code)
        extra = {b"operation not permitted": 137, b"read-only file system": 138}.get(permission_text(line))
        if re.match(rb"^PermissionError: \[Errno 1\] Operation not permitted(?::|$)", line):
            extra = 137
        if extra:
            permissions.add(extra)
        if extra == 137:
            eperm_reporters.add(next((code for code, pattern in MAKE_EPERM_REPORTERS if re.match(pattern, line)), 0))
        if hint == 121 and permission_text(line) == b"permission denied" and not any(
                re.match(pattern, line) for _, pattern in MAKE_PERMISSION_RULES):
            permissions.add(136)
        target = make_failed_target(line)
        if target is not None:
            failed_targets.add(target)
    if hint >= 121 and permissions:
        if len(permissions) != 1:
            return 184 if 137 in permissions else 121
        if 137 not in permissions:
            return next(iter(permissions))
        if len(eperm_reporters) > 1 or len(failed_targets) > 1:
            return 184
        # The reporter is observed on the EPERM line. A separately observed failed
        # target is context only, and is used only when no reporter was identified.
        return next(iter(eperm_reporters), 0) or next(iter(failed_targets), 0) or 137
    return 136 if hint == 121 else 99 if hint == 126 else hint

def make_python(release):
    try:
        process = subprocess.Popen((GNU_MAKE_PROGRAM, GNU_MAKE_OVERRIDE, "-j2"), cwd=release, env=environment,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except OSError as error:
        return {errno.EACCES: 112, errno.EPERM: 112, errno.ENOENT: 113}.get(error.errno, 114)
    try:
        with process:
            hint = make_output_hint(process.stdout)
            code = process.wait()
    except OSError:
        return 144
    if code < 0:
        return {signal.SIGKILL: 115, signal.SIGXFSZ: 116}.get(-code, 117)
    return hint if code else 0

failure = 90
try:
    mode, network, mount, prefix, version, *arguments = sys.argv[1:]
    require_isolated(network, mount)
    python = prefix + "/bin/python3"
    python_flags = (python, "-I", "-S", "-B", "-c")
    if mode == "build":
        failure = 95
        device, inode, _, *resource_limits, _unit_deadline, _clock_namespace = arguments
        require_staging_mount(prefix, device, inode)
        failure = 126
        require_ruby_resources(*resource_limits)
        failure = 97
        release = extract_release("Python-" + version)
        verify_build_scratch(release, prefix)
        verify_native_build_chain(release)
        bootstrap_gnu_make(resource_limits[0])
        failure = 98
        command(str(release / "configure"), "--prefix=" + prefix, "--with-ensurepip=install", cwd=release)
        failure = 99
        make_failure = make_python(release)
        if make_failure:
            sys.exit(make_failure)
        failure = 100
        command(GNU_MAKE_PROGRAM, GNU_MAKE_OVERRIDE, "install", cwd=release)
        failure = 102
        command(*python_flags, "import sys; sys.exit(sys.base_prefix != sys.argv[1])", prefix)

    else:
        failure = 126
        require_ruby_resources(*arguments)
    failure = 103
    command(*python_flags, "import ssl, sqlite3, bz2, lzma, venv, zlib, sys; "
            "sys.exit('.'.join(map(str, sys.version_info[:3])) != sys.argv[1])", version)
except (OSError, KeyError, ValueError, subprocess.SubprocessError, tarfile.TarError):
    sys.exit(failure)
'''


def python_make_dependency():
    return {'version': GNU_MAKE_SOURCE_VERSION, 'source': GNU_MAKE_SOURCE_URL, 'sha256': GNU_MAKE_SOURCE_SHA256}


def prepare_python_make_source(work):
    dependency = python_make_dependency()
    archive = work / 'gnu-make-source.tar.gz'
    url = source_url('GNU_MAKE_SOURCE', GNU_MAKE_SOURCE_NAME)
    digest = download(url, archive, 'sha256', dependency['sha256'],
                      point='GNU_MAKE_SOURCE', maximum=GNU_MAKE_SOURCE_BYTES)
    info = archive.lstat()
    if digest != dependency['sha256'] or not stat.S_ISREG(info.st_mode) or info.st_size != GNU_MAKE_SOURCE_BYTES:
        fail('integrity', 'GNU Make source archive identity differs')
    archive.chmod(0o444)
    return archive, dependency


def python_source_marker_recipe_matches(record):
    recipe = record.get('recipe', PYTHON_LEGACY_SOURCE_RECIPE)
    if recipe == PYTHON_LEGACY_SOURCE_RECIPE:
        return True
    dependencies = record.get('dependencies')
    return (recipe == PYTHON_SOURCE_RECIPE and isinstance(dependencies, dict)
            and dependencies.get('gnu_make') == python_make_dependency())


def python_source_unit(unit, directory, version, build_mounts=None, probe_mount=None):
    source_unit(unit, directory, version, 'python', PYTHON_SOURCE_WORKER, build_mounts, probe_mount)


def python_source_probe(directory, version):
    source_probe(directory, version, 'python', python_source_unit)


def python_source_publish(stage, destination):
    # CPython installs an in-prefix symlink from bin/python3 to its versioned interpreter.
    ruby_publish(stage, destination, executable='python3')


_previous_python_install = install
_previous_python_probe = probe


def install_with_python_isolation(ecosystem, identity, url, algorithm, expected, layout):
    if ecosystem == 'PYTHON':
        return source_install(ecosystem, PYTHON_SOURCE_RECIPE, identity, url, algorithm, expected, layout,
                              python_source_unit, python_source_publish, python_source_probe)
    return _previous_python_install(ecosystem, identity, url, algorithm, expected, layout)


def probe_with_python_isolation(ecosystem, directory, expected):
    if ecosystem == 'PYTHON':
        return python_source_probe(directory, expected)
    return _previous_python_probe(ecosystem, directory, expected)


install = install_with_python_isolation
probe = probe_with_python_isolation
