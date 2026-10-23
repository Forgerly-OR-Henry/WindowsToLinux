# @compat:toolchain-archives@

import threading


class InstallationOutputTail:
    """Keep only redacted diagnostic bytes; pipes are drained while the child runs."""
    LIMIT = 16384

    def __init__(self):
        self.value = bytearray()
        self.truncated = False

    def drain(self, source):
        private = False
        hidden_continuation = False
        oversized = False
        for raw in iter(lambda: source.readline(8193), b''):
            complete = raw.endswith(b'\n')
            text = raw.decode('utf-8', errors='replace')
            if '-----BEGIN ' in text:
                private = True
            hidden = private or hidden_continuation or oversized or len(raw) > 8192
            if oversized or len(raw) > 8192:
                self.truncated = True
            if '-----END ' in text:
                private = False
            if hidden:
                safe = '[redacted diagnostic line]\n' if not oversized else ''
            else:
                text = re.sub(r'''(?i)(["']?(?:password|secret|token|api[_-]?key|authorization)["']?\s*[:=]\s*)[^\r\n]+''', r'\1[redacted]', text)
                text = re.sub(r'(?i)(--(?:password|secret|token|api[_-]?key|authorization)(?:\s+|=))[^\r\n]+', r'\1[redacted]', text)
                text = re.sub(r'(?i)(authorization\s+(?:bearer|basic)\s+)[^\r\n]+', r'\1[redacted]', text)
                text = re.sub(r'(?i)(https?://)[^\s/@]+:[^\s/@]+@', r'\1[redacted]@', text)
                text = re.sub(r'(?i)([?&](?:password|secret|token|api[_-]?key)=)[^&\s]+', r'\1[redacted]', text)
                safe = ''.join(c if c in '\t\n\r' or ord(c) >= 32 and ord(c) != 127 else '?' for c in text)
            hidden_continuation = bool(re.search(r'''(?i)["']?(?:password|secret|token|api[_-]?key|authorization)["']?\s*[:=]?\s*$''', text.rstrip())) or (hidden_continuation and not text.strip())
            oversized = not complete and (oversized or len(raw) > 8192)
            if complete:
                oversized = False
            encoded = safe.encode('utf-8')
            self.value.extend(encoded)
            if len(self.value) > self.LIMIT:
                del self.value[:-self.LIMIT]
                # Never expose an incomplete UTF-8 prefix after truncation.
                self.value[:] = self.value.decode('utf-8', errors='ignore').encode('utf-8')
                self.truncated = True

    def text(self):
        return self.value.decode('utf-8')


def installation_stage(command):
    executable = pathlib.Path(command[0]).name
    if executable in ('apt-get', 'dnf'):
        return 'system-dependencies'
    if executable == 'configure':
        return 'source-configure'
    if executable == 'make':
        return 'source-install' if 'install' in command[1:] else 'source-build'
    return 'installation-command'


def run(command, cwd=None, environment=None):
    stdout, stderr = InstallationOutputTail(), InstallationOutputTail()
    process = subprocess.Popen(
        command,
        cwd=cwd,
        env=environment,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
        **tracked_process_options(),
    )
    readers = [threading.Thread(target=tail.drain, args=(stream,))
               for tail, stream in ((stdout, process.stdout), (stderr, process.stderr))]
    for reader in readers:
        reader.start()
    failure = None
    try:
        tracked_process_started(process)
        code = process.wait(timeout=remaining())
    except BaseException as error:
        failure = error
        raise
    finally:
        # Kill the owned process group on cancellation, timeout, and leaked descendants after normal completion.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        for reader in readers:
            reader.join()
        process.stdout.close()
        process.stderr.close()
        tracked_process_finished(process)
        recorder = globals().get('record_installation_diagnostic')
        if recorder is not None:
            recorder({'stage': installation_stage(command), 'executable': pathlib.Path(command[0]).name,
                      'actualExitCode': process.returncode,
                      'timedOut': isinstance(failure, subprocess.TimeoutExpired) or isinstance(failure, PreparationFailure) and failure.category == 'timeout',
                      'cancelled': isinstance(failure, PreparationFailure) and failure.category == 'cancelled',
                      'stdout': stdout.text(), 'stderr': stderr.text(),
                      'stdoutTruncated': stdout.truncated, 'stderrTruncated': stderr.truncated})
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
