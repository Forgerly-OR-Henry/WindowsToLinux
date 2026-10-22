# @compat:toolchain-archives@


def run(command, cwd=None, environment=None):
    process = subprocess.Popen(
        command,
        cwd=cwd,
        env=environment,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
        **tracked_process_options(),
    )
    tracked_process_started(process)
    try:
        code = process.wait(timeout=remaining())
    finally:
        # Kill the owned process group on cancellation, timeout, and leaked descendants after normal completion.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        tracked_process_finished(process)
    if code:
        fail('installation', 'official toolchain installation command failed: ' + pathlib.Path(command[0]).name)


def probe_output(command):
    with tempfile.TemporaryFile() as output:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=output,
            stderr=subprocess.STDOUT,
            env=os.environ.copy(),
            start_new_session=True,
            **tracked_process_options(),
        )
        tracked_process_started(process)
        try:
            code = process.wait(timeout=min(60, remaining()))
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            tracked_process_finished(process)
        output.seek(0)
        return code, output.read(8192).decode('utf-8', errors='replace')


DEADLINE = None


def tracked_process_options():
    descriptor = globals().get('TRACKED_PREPARE_LOCK')
    return {} if descriptor is None else {'pass_fds': (descriptor,)}


def tracked_process_started(process):
    tracked = globals().get('TRACKED_OPERATION')
    if tracked is not None:
        try:
            tracked.process_started(process.pid)
        except BaseException:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise


def tracked_process_finished(process):
    tracked = globals().get('TRACKED_OPERATION')
    if tracked is not None:
        tracked.process_finished(process.pid)


def remaining():
    seconds = int(DEADLINE - time.monotonic()) if DEADLINE is not None else 3600
    if seconds < 1:
        fail('timeout', 'toolchain preparation exceeded its time budget')
    return seconds


def source_dependencies(layout):
    if layout not in ('python', 'php', 'ruby'):
        return
    common_apt = ['build-essential', 'pkg-config', 'libssl-dev', 'zlib1g-dev', 'libffi-dev']
    common_dnf = ['gcc', 'gcc-c++', 'make', 'pkgconf-pkg-config', 'openssl-devel', 'zlib-devel', 'libffi-devel']
    apt = {
        'python': ['libbz2-dev', 'libreadline-dev', 'libsqlite3-dev', 'liblzma-dev', 'libncurses-dev', 'libgdbm-dev'],
        'php': ['libonig-dev', 'libcurl4-openssl-dev', 'libxml2-dev', 'libsqlite3-dev', 'libpq-dev', 'libzip-dev'],
        'ruby': ['libyaml-dev', 'libreadline-dev', 'libgdbm-dev'],
    }[layout]
    dnf = {
        'python': ['bzip2-devel', 'readline-devel', 'sqlite-devel', 'xz-devel', 'ncurses-devel', 'gdbm-devel'],
        'php': ['oniguruma-devel', 'libcurl-devel', 'libxml2-devel', 'sqlite-devel', 'libpq-devel', 'libzip-devel'],
        'ruby': ['libyaml-devel', 'readline-devel', 'gdbm-devel'],
    }[layout]
    if pathlib.Path('/usr/bin/apt-get').is_file():
        if globals().get('TRACKED_OPERATION') is not None:
            TRACKED_OPERATION.dependencies_started()
        environment = dict(os.environ, DEBIAN_FRONTEND='noninteractive', NEEDRESTART_MODE='l')
        run(
            ['/usr/bin/apt-get', '-o', 'DPkg::Lock::Timeout=120', 'install', '-y', '--no-install-recommends']
            + common_apt
            + apt,
            environment=environment,
        )
    elif pathlib.Path('/usr/bin/dnf').is_file():
        if globals().get('TRACKED_OPERATION') is not None:
            TRACKED_OPERATION.dependencies_started()
        # @compat:centos-repositories@
        run(['/usr/bin/dnf', '-y'] + repositories + ['install'] + common_dnf + dnf)
    else:
        fail('platform', 'the platform has no supported dependency installer')
