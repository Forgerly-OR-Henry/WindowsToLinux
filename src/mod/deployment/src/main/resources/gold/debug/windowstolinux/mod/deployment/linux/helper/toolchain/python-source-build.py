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
}
PYTHON_SOURCE_WORKER = SOURCE_WORKER + r'''
import errno
import re
import signal

# Child output is untrusted. Retain only the highest-priority matching fixed hint in the unit.
MAKE_OUTPUT_RULES = (
    (118, rb"(?i)(?:^|: )(?:no space left on device|disk quota exceeded)\.?$"),
    (119, rb"(?i)(?:^|: )(?:file too large|file size limit exceeded)(?: \(core dumped\))?\.?$"),
    (120, rb"(?i)(?:^|: )resource temporarily unavailable\.?$"),
    (121, rb"(?i)(?:^|: )(?:permission denied|operation not permitted|read-only file system)\.?$"),
    (122, rb"(?i)^(?:[^:]+: )?fatal error: killed signal terminated program cc1(?:plus)?(?:\s.*)?$"),
    (123, rb"(?i)(?:^collect2: error: ld returned [0-9]+ exit status$|: undefined reference to )"),
    (124, rb"^make(?:\[[0-9]+\])?: \*\*\* No rule to make target "),
    (125, rb"^.+:[0-9]+(?::[0-9]+)?: (?:fatal )?error: "),
)

def make_output_hint(stream):
    hint, continued = 126, False
    for raw in iter(lambda: stream.readline(4097), b""):
        if not continued and len(raw) <= 4096:
            line = raw.rstrip(b"\r\n")
            for code, pattern in MAKE_OUTPUT_RULES:
                if code < hint and re.search(pattern, line):
                    hint = code
        continued = not raw.endswith(b"\n")
    return 99 if hint == 126 else hint

def make_python(release):
    try:
        process = subprocess.Popen(("/usr/bin/make", "-j2"), cwd=release, env=environment,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except OSError as error:
        return {errno.EACCES: 112, errno.EPERM: 112, errno.ENOENT: 113}.get(error.errno, 114)
    with process:
        hint = make_output_hint(process.stdout)
        code = process.wait()
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
        device, inode, _ = arguments
        require_staging_mount(prefix, device, inode)
        failure = 97
        release = extract_release("Python-" + version)
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
