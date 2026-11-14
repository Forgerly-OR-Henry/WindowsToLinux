PYTHON_SOURCE_RECIPE = 'python-dynamic-user-build-v1'
PYTHON_SOURCE_WORKER = SOURCE_WORKER + r'''
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
        command("/usr/bin/make", "-j2", cwd=release)
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
