PYTHON_SOURCE_RECIPE = 'python-dynamic-user-build-v1'
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
}
PYTHON_SOURCE_WORKER = SOURCE_WORKER + r'''
import re
import signal

# Child output is untrusted. Retain only the highest-priority matching fixed hint in the unit.
MAKE_OUTPUT_RULES = (
    (118, rb"(?i)(?:^|: )(?:no space left on device|disk quota exceeded)\.?$"),
    (119, rb"(?i)(?:^|: )(?:file too large|file size limit exceeded)(?: \(core dumped\))?\.?$"),
    (120, rb"(?i)(?:^|: )resource temporarily unavailable\.?$"),
    (121, rb"(?i)(?:^|: )permission denied\.?$"),
    (122, rb"(?i)^(?:[^:]+: )?fatal error: killed signal terminated program cc1(?:plus)?(?:\s.*)?$"),
    (123, rb"(?i)(?:^collect2: error: ld returned [0-9]+ exit status$|: undefined reference to )"),
    (124, rb"^make(?:\[[0-9]+\])?: \*\*\* No rule to make target "),
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
)
MAKE_FAILED_TARGET = rb"^make(?:\[[0-9]+\])?: \*\*\* \[(?:[^\[\]]+:[0-9]+: )?([^\[\]]+)\] Error [0-9]+$"
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
        process = subprocess.Popen(("/usr/bin/make", "-j2"), cwd=release, env=environment,
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
        device, inode, _, *resource_limits = arguments
        require_staging_mount(prefix, device, inode)
        failure = 126
        require_ruby_resources(*resource_limits)
        failure = 97
        release = extract_release("Python-" + version)
        verify_build_scratch(release, prefix)
        verify_native_build_chain(release)
        failure = 98
        command(str(release / "configure"), "--prefix=" + prefix, "--with-ensurepip=install", cwd=release)
        failure = 99
        make_failure = make_python(release)
        if make_failure:
            sys.exit(make_failure)
        failure = 100
        command("/usr/bin/make", "install", cwd=release)
        failure = 102
        command(*python_flags, "import sys; sys.exit(sys.base_prefix != sys.argv[1])", prefix)

    else:
        failure = 126
        require_ruby_resources(*arguments)
    failure = 103
    command(*python_flags, "import ssl, sqlite3, bz2, lzma, venv, zlib, sys; "
            "sys.exit('.'.join(map(str, sys.version_info[:3])) != sys.argv[1])", version)
except (OSError, KeyError, ValueError, subprocess.CalledProcessError):
    sys.exit(failure)
'''


def python_source_unit(unit, directory, version, build_mounts=None):
    source_unit(unit, directory, version, 'python', PYTHON_SOURCE_WORKER, build_mounts)


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
