"""Task-private DNF repositories with checksum-bound, request-level source failover."""
import bz2
import configparser
import ctypes
import fnmatch
import gzip
import lzma
import platform
import shutil
import xml.etree.ElementTree as ET


def _dnf_os_release():
    values = {}
    for line in pathlib.Path('/etc/os-release').read_text().splitlines():
        key, separator, value = line.partition('=')
        if separator and re.fullmatch('[A-Z_]+', key):
            parsed = shlex.split(value)
            if len(parsed) == 1:
                values[key] = parsed[0]
    return values


def _dnf_repo_directories():
    return [pathlib.Path('/etc/yum.repos.d'), pathlib.Path('/etc/distro.repos.d'),
            pathlib.Path('/usr/share/dnf5/repos.d')]


def _dnf_override_directories():
    return [pathlib.Path('/usr/share/dnf5/repos.override.d'), pathlib.Path('/etc/dnf/repos.override.d')]


def _dnf_program():
    return ['/usr/bin/dnf']


def _dnf_zstd_library():
    # libzstd is an existing native capability, not a downloaded dependency.
    return ctypes.CDLL('libzstd.so.1')


def _dnf_zstd_blocks(path):
    try:
        library = _dnf_zstd_library()
    except OSError:
        raise MirrorFailure('DNF zstd primary metadata requires installed libzstd') from None

    class Input(ctypes.Structure):
        _fields_ = [('src', ctypes.c_void_p), ('size', ctypes.c_size_t), ('pos', ctypes.c_size_t)]

    class Output(ctypes.Structure):
        _fields_ = [('dst', ctypes.c_void_p), ('size', ctypes.c_size_t), ('pos', ctypes.c_size_t)]

    library.ZSTD_createDStream.restype = ctypes.c_void_p
    library.ZSTD_initDStream.argtypes = [ctypes.c_void_p]
    library.ZSTD_initDStream.restype = ctypes.c_size_t
    library.ZSTD_decompressStream.argtypes = [ctypes.c_void_p, ctypes.POINTER(Output), ctypes.POINTER(Input)]
    library.ZSTD_decompressStream.restype = ctypes.c_size_t
    library.ZSTD_isError.argtypes = [ctypes.c_size_t]
    library.ZSTD_isError.restype = ctypes.c_uint
    library.ZSTD_freeDStream.argtypes = [ctypes.c_void_p]
    stream = library.ZSTD_createDStream()
    if not stream:
        raise MirrorFailure('cannot allocate DNF zstd decoder')
    try:
        remaining = library.ZSTD_initDStream(stream)
        if library.ZSTD_isError(remaining):
            raise MirrorFailure('cannot initialize DNF zstd decoder')
        with path.open('rb') as source:
            while True:
                chunk = source.read(65536)
                if not chunk:
                    break
                raw = ctypes.create_string_buffer(chunk, len(chunk))
                incoming = Input(ctypes.cast(raw, ctypes.c_void_p), len(chunk), 0)
                pending = False
                while incoming.pos < incoming.size or pending:
                    destination = ctypes.create_string_buffer(65536)
                    outgoing = Output(ctypes.cast(destination, ctypes.c_void_p), 65536, 0)
                    previous = incoming.pos
                    remaining = library.ZSTD_decompressStream(stream, ctypes.byref(outgoing), ctypes.byref(incoming))
                    if library.ZSTD_isError(remaining) or incoming.pos < incoming.size and incoming.pos == previous and outgoing.pos == 0:
                        raise MirrorFailure('invalid DNF zstd primary metadata')
                    pending = remaining != 0 and outgoing.pos == outgoing.size
                    if outgoing.pos:
                        yield destination.raw[:outgoing.pos]
        if remaining:
            raise MirrorFailure('truncated DNF zstd primary metadata')
    finally:
        library.ZSTD_freeDStream(stream)


def _dnf_is_five(program):
    return pathlib.Path(program[0]).resolve().name.startswith('dnf5')


def _dnf_expand(value, variables):
    def variable(match):
        name = match.group(1) or match.group(2)
        if name not in variables:
            raise MirrorFailure('unsupported DNF repository variable')
        return variables[name]
    return re.sub(r'\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)', variable, value)


def _dnf_arguments(arguments):
    command, packages, switches = None, [], []
    enabled, disabled, release = [], [], None
    query = []
    index = 0
    while index < len(arguments):
        item = arguments[index]
        if item in ('--enablerepo', '--disablerepo', '--releasever'):
            index += 1
            if index == len(arguments):
                raise MirrorFailure('DNF option value is absent')
            item += '=' + arguments[index]
        if item.startswith('--enablerepo='):
            enabled.extend(item.partition('=')[2].split(','))
        elif item.startswith('--disablerepo='):
            disabled.extend(item.partition('=')[2].split(','))
        elif item.startswith('--releasever='):
            release = item.partition('=')[2]
            if not re.fullmatch(r'[0-9]{1,3}(?:\.[0-9]{1,3})?', release):
                raise MirrorFailure('invalid DNF release override')
        elif item in ('-y', '--assumeyes', '--best', '--nobest', '--allowerasing', '--noautoremove', '-q'):
            switches.append(item)
        elif item in ('--installed', '--whatrequires', '--latest-limit=1'):
            query.append(item)
        elif item == '--qf':
            index += 1
            if index == len(arguments) or arguments[index] != '%{version}-%{release}':
                raise MirrorFailure('unsupported DNF query format')
            query += ['--qf', arguments[index]]
        elif item in ('install', 'remove', 'repoquery', 'makecache', 'repolist') and command is None:
            command = item
        elif item.startswith('-') or command is None or '://' in item or item.startswith(('/', '.')):
            raise MirrorFailure('unsupported DNF argument under frozen source policy')
        elif not re.fullmatch('[@A-Za-z0-9_][A-Za-z0-9_.+~:^*<>=!/@-]*', item):
            raise MirrorFailure('invalid DNF package specification')
        else:
            packages.append(item)
        index += 1
    if command is None or command in ('install', 'remove') and not packages:
        raise MirrorFailure('DNF operation is absent')
    if query and command != 'repoquery' or '--noautoremove' in switches and command != 'remove':
        raise MirrorFailure('DNF option does not apply to this operation')
    if command == 'repoquery' and (not packages or query not in (
            ['--latest-limit=1', '--qf', '%{version}-%{release}'], ['--installed', '--whatrequires'])):
        raise MirrorFailure('unsupported DNF repository query')
    if any(not re.fullmatch('[A-Za-z0-9_.?*:-]+', value) for value in enabled + disabled):
        raise MirrorFailure('invalid DNF repository selector')
    return command, packages, switches, enabled, disabled, release, query


def _dnf_repositories(variables, requested, disabled, five=False):
    repositories = {}
    for directory in _dnf_repo_directories():
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob('*.repo')):
            parser = configparser.ConfigParser(interpolation=None, strict=True, inline_comment_prefixes=('#', ';'))
            parser.read_string(path.read_text())
            for name in parser.sections():
                if not re.fullmatch('[A-Za-z0-9_.:-]+', name):
                    raise MirrorFailure('ambiguous DNF repository identity')
                # DNF5 uses the first repository definition in directory order.
                if name in repositories:
                    if five:
                        continue
                    raise MirrorFailure('ambiguous DNF repository identity')
                repositories[name] = dict(parser.items(name))
    if five:
        overrides = {}
        for directory in _dnf_override_directories():
            if directory.is_dir():
                for path in directory.glob('*.repo'):
                    overrides[path.name] = path
        for filename, path in sorted(overrides.items()):
            parser = configparser.ConfigParser(interpolation=None, strict=True, inline_comment_prefixes=('#', ';'))
            parser.read_string(path.read_text())
            for pattern in parser.sections():
                for name in repositories:
                    if fnmatch.fnmatchcase(name, pattern):
                        repositories[name].update(dict(parser.items(pattern)))
    for name, values in repositories.items():
        active = values.get('enabled', '1').strip().lower() not in ('0', 'false', 'no')
        if any(fnmatch.fnmatchcase(name, pattern) for pattern in disabled):
            active = False
        if any(fnmatch.fnmatchcase(name, pattern) for pattern in requested):
            active = True
        values['enabled'] = '1' if active else '0'
        if active:
            if values.get('pkg_gpgcheck', values.get('gpgcheck', '1')).strip().lower() not in ('1', 'true', 'yes'):
                raise MirrorFailure('enabled DNF repository does not retain package signature verification')
            keys = []
            for key in shlex.split(_dnf_expand(values.get('gpgkey', ''), variables)):
                parsed = urllib.parse.urlsplit(key)
                if parsed.scheme != 'file' or parsed.netloc not in ('', 'localhost'):
                    raise MirrorFailure('DNF requires an existing local GPG key')
                local = pathlib.Path(urllib.request.url2pathname(parsed.path))
                if not local.is_absolute() or not local.is_file():
                    raise MirrorFailure('DNF local GPG key is absent')
                keys.append(local.as_uri())
            values['gpgkey'] = ' '.join(keys)
    for pattern in requested:
        if not any(fnmatch.fnmatchcase(name, pattern) for name in repositories):
            raise MirrorFailure('requested DNF repository does not exist')
    if not any(values['enabled'] == '1' for values in repositories.values()):
        raise MirrorFailure('no enabled DNF repositories')
    return repositories


class DnfRepositoryGateway:
    def __init__(self, policy, point, repositories, variables, directory, metadata_start=0):
        self.policy, self.point = policy, point
        self.directory = pathlib.Path(directory)
        self.downloads = self.directory / 'objects'
        self.downloads.mkdir()
        self.expected, self.files, self.failures, self.locks = {}, {}, set(), {}
        self.primary, self.repo_policies, self.origins = {}, {}, {}
        self.metadata_start, self.metadata_indices = metadata_start, {}
        self.package_objects, self.artifacts_requested = set(), False
        self.guard = threading.RLock()
        self.online = True
        for name, values in repositories.items():
            if values['enabled'] != '1':
                continue
            endpoints = []
            original = None
            for ordinal, endpoint in enumerate(policy.endpoints(point)):
                template = endpoint.get('repoTemplates', {}).get(name)
                if not template:
                    continue
                base = _dnf_expand(template, variables)
                policy.validate_url(endpoint, base)
                original = original or base.rstrip('/') + '/'
                selected = dict(endpoint)
                selected['routes'] = [{'origin': original, 'prefix': base.rstrip('/') + '/'}]
                selected['sourceOrigins'] = [selected['routes'][0]['origin']]
                selected['_metadataOrdinal'] = ordinal
                endpoints.append(selected)
            if not endpoints:
                raise MirrorFailure('enabled DNF repository has no selected template: ' + name)
            document = dict(policy.document, points={point: endpoints})
            transport = MirrorPolicy(document, deadline=policy.deadline, transfer_ledger=policy.transfer_ledger)
            transport.transport_options = policy.transport_options
            self.repo_policies[name], self.origins[name] = transport, original
        gateway = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def finish(self):
                super().finish()
                if os.name == 'nt':
                    time.sleep(.05)

            def do_GET(self):
                try:
                    parts = urllib.parse.urlsplit(self.path)
                    if parts.query or parts.fragment:
                        raise MirrorFailure('DNF gateway accepts only repository object paths')
                    name, separator, relative = parts.path.lstrip('/').partition('/')
                    if not separator:
                        raise MirrorFailure('DNF repository object is absent')
                    output = gateway.obtain(name, relative)
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/octet-stream')
                    self.send_header('Content-Length', str(output.stat().st_size))
                    self.end_headers()
                    if self.command != 'HEAD':
                        with output.open('rb') as source:
                            shutil.copyfileobj(source, self.wfile, 65536)
                except (MirrorFailure, ValueError, OSError, ET.ParseError, EOFError):
                    self.send_error(502, 'configured DNF object failed')

            def do_HEAD(self):
                self.do_GET()

        self.server = MirrorLoopbackServer(('127.0.0.1', 0), Handler, mirror_policy=policy)
        self.server.daemon_threads = False
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def root(self, name):
        return self.base + '/' + name + '/'

    @staticmethod
    def relative(value):
        parsed = urllib.parse.urlsplit(value)
        decoded = urllib.parse.unquote(parsed.path)
        if parsed.scheme or parsed.netloc or parsed.query or parsed.fragment or decoded.startswith('/') \
                or '\\' in decoded or any(part in ('', '.', '..') for part in decoded.split('/')):
            raise MirrorFailure('DNF metadata contains a non-relative package location')
        return value

    def obtain(self, name, relative):
        relative = self.relative(relative)
        key = (name, relative)
        with self.guard:
            if name not in self.repo_policies:
                raise MirrorFailure('DNF requested an inactive repository')
            lock = self.locks.setdefault(key, threading.Lock())
        with lock:
            if key in self.files:
                return self.files[key]
            if key in self.failures or not self.online:
                raise MirrorFailure('DNF object request budget already exhausted')
            digest = self.expected.get(key)
            if key in self.package_objects:
                self.artifacts_requested = True
            bootstrap = relative in ('repodata/repomd.xml', 'repodata/repomd.xml.asc')
            if not bootstrap and digest is None:
                raise MirrorFailure('DNF object is not bound to repository checksum metadata')
            output = self.downloads / hashlib.sha256((name + '\n' + relative).encode()).hexdigest()
            try:
                if relative == 'repodata/repomd.xml':
                    accepted = False
                    endpoints = self.repo_policies[name].endpoints(self.point)
                    for endpoint in endpoints:
                        ordinal = endpoint['_metadataOrdinal']
                        if ordinal < self.metadata_start:
                            continue
                        selected = self.policy.maps(endpoint, self.origins[name] + relative)
                        metadata_root = selected[:-len(relative)]
                        private = dict(endpoint, routes=[{'origin': metadata_root, 'prefix': metadata_root}], sourceOrigins=[metadata_root])
                        document = dict(self.policy.document, points={self.point: [private]})
                        candidate = MirrorPolicy(document, deadline=self.policy.deadline, transfer_ledger=self.policy.transfer_ledger)
                        candidate.transport_options = self.policy.transport_options
                        try:
                            mirror_transfer(candidate, self.point, selected, output, limit=4194304)
                            self.read_repomd(name, output)
                            self.metadata_indices[name] = ordinal
                            self.origins[name] = metadata_root
                            for item in endpoints:
                                item['routes'] = [{'origin': metadata_root, 'prefix': item['routes'][0]['prefix']}]
                                item['sourceOrigins'] = [metadata_root]
                            accepted = True
                            break
                        except (MirrorFailure, OSError, ValueError, ET.ParseError):
                            output.unlink(missing_ok=True)
                    if not accepted:
                        raise MirrorFailure('selected DNF sources did not provide valid repomd metadata')
                else:
                    transport = self.repo_policies[name]
                    if key not in self.package_objects:
                        entries = transport.endpoints(self.point)
                        entries = ([entry for entry in entries if entry['_metadataOrdinal'] == self.metadata_indices[name]]
                                   if relative == 'repodata/repomd.xml.asc' else
                                   [entry for entry in entries if entry['_metadataOrdinal'] >= self.metadata_start])
                        document = dict(self.policy.document, points={self.point: entries})
                        transport = MirrorPolicy(document, self.policy.deadline, self.policy.transfer_ledger)
                        transport.transport_options = self.policy.transport_options
                    mirror_transfer(transport, self.point, self.origins[name] + relative, output,
                                    digest[0] if digest else None, digest[1] if digest else None)
                    if key in self.primary:
                        self.read_primary(name, output, relative)
                self.files[key] = output
                return output
            except (MirrorFailure, OSError, ValueError, ET.ParseError, EOFError):
                self.failures.add(key)
                output.unlink(missing_ok=True)
                raise

    def register(self, name, location, algorithm, expected, primary=False):
        location = self.relative(location)
        expected = (expected or '').strip()
        if algorithm not in ('sha256', 'sha512') or not re.fullmatch(
                '[a-fA-F0-9]{%d}' % (64 if algorithm == 'sha256' else 128), expected or ''):
            raise MirrorFailure('DNF object lacks a supported repository checksum')
        key, digest = (name, location), (algorithm, expected.lower())
        with self.guard:
            if key in self.expected and self.expected[key] != digest:
                raise MirrorFailure('DNF repository changed an already frozen object identity')
            self.expected[key] = digest
            if primary:
                self.primary[key] = True

    def read_repomd(self, name, output):
        if output.stat().st_size > 4194304:
            raise MirrorFailure('DNF repomd size limit')
        raw = output.read_bytes()
        if b'<!DOCTYPE' in raw or b'<!ENTITY' in raw:
            raise MirrorFailure('DNF metadata entity declarations are forbidden')
        root = ET.fromstring(raw)
        if root.tag.rsplit('}', 1)[-1] != 'repomd':
            raise MirrorFailure('DNF repomd document type is invalid')
        count = 0
        entries = []
        for data in root:
            if data.tag.rsplit('}', 1)[-1] != 'data':
                continue
            count += 1
            if count > 128:
                raise MirrorFailure('DNF repomd object count limit')
            values = {child.tag.rsplit('}', 1)[-1]: child for child in data}
            checksum, location = values.get('checksum'), values.get('location')
            if checksum is None or location is None:
                raise MirrorFailure('DNF metadata checksum/location absent')
            href = self.relative(location.attrib.get('href', ''))
            algorithm, expected = checksum.attrib.get('type'), (checksum.text or '').strip()
            if algorithm not in ('sha256', 'sha512') or not re.fullmatch(
                    '[a-fA-F0-9]{%d}' % (64 if algorithm == 'sha256' else 128), expected):
                raise MirrorFailure('DNF metadata lacks a supported checksum')
            entries.append((href, algorithm, expected, data.attrib.get('type') == 'primary'))
        if not any(entry[3] for entry in entries):
            raise MirrorFailure('DNF repomd has no primary package metadata')
        for href, algorithm, expected, primary in entries:
            self.register(name, href, algorithm, expected, primary)

    def read_primary(self, name, output, relative):
        opener = gzip.open if relative.endswith('.gz') else lzma.open if relative.endswith('.xz') \
            else bz2.open if relative.endswith('.bz2') else open
        if relative.endswith('.zck'):
            raise MirrorFailure('DNF zchunk primary is disabled; ordinary primary metadata is required')
        expanded = output.with_suffix('.xml')
        try:
            total = 0
            previous = b''
            def ordinary_blocks():
                with opener(output, 'rb') as source:
                    while True:
                        block = source.read(65536)
                        if not block:
                            break
                        yield block
            blocks = _dnf_zstd_blocks(output) if relative.endswith('.zst') else ordinary_blocks()
            with expanded.open('wb') as target:
                for block in blocks:
                    self.policy.remaining()
                    total += len(block)
                    declarations = previous + block
                    if total > 536870912 or b'<!DOCTYPE' in declarations or b'<!ENTITY' in declarations:
                        raise MirrorFailure('DNF primary expansion limit or entity declaration')
                    target.write(block)
                    previous = block[-16:]
            count = 0
            for event, element in ET.iterparse(expanded, events=('end',)):
                if element.tag.rsplit('}', 1)[-1] != 'package':
                    continue
                count += 1
                if count > 2_000_000:
                    raise MirrorFailure('DNF primary package count limit')
                fields = {child.tag.rsplit('}', 1)[-1]: child for child in element}
                checksum, location = fields.get('checksum'), fields.get('location')
                if checksum is None or location is None:
                    raise MirrorFailure('DNF primary package identity absent')
                self.register(name, location.attrib.get('href', ''), checksum.attrib.get('type'), checksum.text)
                self.package_objects.add((name, location.attrib.get('href', '')))
                element.clear()
        finally:
            expanded.unlink(missing_ok=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


def _dnf_config(directory, repositories, gateway):
    repos = directory / 'repos'
    repos.mkdir()
    parser = configparser.ConfigParser(interpolation=None)
    retained = ('priority', 'cost', 'exclude', 'excludepkgs', 'includepkgs', 'module_hotfixes', 'module_platform_id')
    for name, values in repositories.items():
        if values['enabled'] != '1':
            parser[name] = {'enabled': '0'}
            continue
        section = {'name': name, 'enabled': '1', 'baseurl': gateway.root(name), 'gpgcheck': '1',
                   'repo_gpgcheck': values.get('repo_gpgcheck', '0'), 'gpgkey': values.get('gpgkey', ''),
                   'skip_if_unavailable': '0', 'sslverify': '1', 'proxy': '_none_', 'metadata_expire': '-1'}
        for key in retained:
            if key in values:
                section[key] = values[key]
        parser[name] = section
    with (repos / 'task.repo').open('w') as output:
        parser.write(output)
    for name in ('cache', 'persist', 'logs', 'vars', 'plugins'):
        (directory / name).mkdir()
    config = configparser.ConfigParser(interpolation=None)
    config['main'] = {'reposdir': str(repos), 'cachedir': str(directory / 'cache'),
                      'persistdir': str(directory / 'persist'), 'logdir': str(directory / 'logs'),
                      'system_cachedir': str(directory / 'cache'), 'varsdir': str(directory / 'vars'),
                      'pluginconfpath': str(directory / 'plugins'), 'plugin_conf_dir': str(directory / 'plugins'),
                      'plugins': '0', 'proxy': '_none_', 'keepcache': '1', 'retries': '1',
                      'timeout': str(max(1, int(gateway.policy.remaining()) + 1)),
                      'minrate': str(gateway.policy.minimum), 'max_parallel_downloads': '1',
                      'gpgcheck': '1', 'sslverify': '1', 'zchunk': '0', 'deltarpm': '0',
                      'skip_if_unavailable': '0', 'fastestmirror': '0', 'countme': '0', 'color': 'never'}
    path = directory / 'dnf.conf'
    with path.open('w') as output:
        config.write(output)
    return path


def _dnf_command_pins(config, five):
    # CLI priority prevents DNF5's global repo override drop-ins from replacing the loopback gateway.
    options = configparser.ConfigParser(interpolation=None)
    options.read(config)
    result = []
    for key, value in options['main'].items():
        if not five and key in ('system_cachedir', 'plugin_conf_dir'):
            continue
        result.append('--setopt=' + key + '=' + value)
    repositories = configparser.ConfigParser(interpolation=None)
    repositories.read(pathlib.Path(options['main']['reposdir']) / 'task.repo')
    for name in repositories.sections():
        settings = {'baseurl': '', 'metalink': '', 'mirrorlist': '', 'username': '', 'password': '',
                    'proxy_username': '', 'proxy_password': '', 'sslclientcert': '', 'sslclientkey': '',
                    'gpgkey': '', 'proxy': '_none_', 'sslverify': '1', 'gpgcheck': '1'}
        settings.update(dict(repositories[name]))
        for key, value in settings.items():
            result.append('--setopt=' + name + '.' + key + '=' + value)
    return result


def _dnf_run(policy, command, directory, phase, query=False):
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().endswith('_PROXY') and not key.startswith('DNF_VAR_')
                   and key not in ('PYTHONPATH', 'PYTHONHOME', 'LD_PRELOAD', 'LD_LIBRARY_PATH')}
    environment.update(TEMP=str(directory), TMP=str(directory), TMPDIR=str(directory),
                       LC_ALL='C', LANGUAGE='C', TERM='dumb', NO_COLOR='1',
                       DNF5_PLUGINS_DIR='', LIBDNF_PLUGINS_CONFIG_DIR=str(directory / 'plugins'),
                       DNF5_FORCE_INTERACTIVE='0',
                       PATH='/usr/sbin:/usr/bin:/sbin:/bin' if os.name != 'nt' else os.environ.get('PATH', ''))
    log = directory / (phase + '.log')
    with log.open('wb') as output, (directory / (phase + '.out')).open('wb') as query_output:
        policy.remaining()
        process = subprocess.Popen(command, cwd=directory, env=environment, stdout=query_output if query else output,
                                   stderr=output if query else subprocess.STDOUT,
                                   start_new_session=os.name != 'nt')
        try:
            return process.wait(timeout=policy.remaining())
        except (subprocess.TimeoutExpired, MirrorFailure):
            if os.name != 'nt':
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
            process.wait()
            raise MirrorFailure('DNF operation exceeded original task deadline')
        finally:
            if process.poll() is None:
                if os.name != 'nt':
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                else:
                    process.kill()
                process.wait()
            if log.stat().st_size <= 2097152:
                sys.stderr.write(log.read_text(errors='replace'))


def _dnf_missing_candidate(directory, packages):
    log = directory / 'download.log'
    if not log.is_file() or log.stat().st_size > 2097152:
        return False
    text = log.read_text(errors='replace')
    found = False
    for line in text.splitlines():
        match = re.fullmatch(r'\s*No match for argument: (.+?)\s*', line)
        if match:
            if match.group(1) not in packages:
                return False
            found = True
        elif re.search(r'(?i)permission denied|operation not permitted|gpg|signature|certificate|failed to download|cannot download|checksum|error:', line):
            if not re.fullmatch(r'\s*Error: Unable to find a match: .+', line):
                return False
    return found


def mirror_dnf(policy, arguments):
    command, packages, switches, enabled, disabled, release, query_options = _dnf_arguments(list(arguments))
    system = _dnf_os_release()
    point = {'fedora': 'DNF_FEDORA', 'rocky': 'DNF_ROCKY', 'almalinux': 'DNF_ALMA',
             'centos': 'DNF_CENTOS_STREAM', 'ol': 'DNF_ORACLE'}.get(system.get('ID'))
    if point is None:
        raise MirrorFailure('DNF distribution is not supported by the frozen catalog')
    release = release or system.get('VERSION_ID', '').split('.')[0]
    if not re.fullmatch(r'[0-9]{1,3}(?:\.[0-9]{1,3})?', release):
        raise MirrorFailure('DNF release identity is unavailable')
    architecture = platform.machine().lower()
    architecture = {'amd64': 'x86_64', 'arm64': 'aarch64'}.get(architecture, architecture)
    if not re.fullmatch('[A-Za-z0-9_]+', architecture):
        raise MirrorFailure('DNF architecture identity is unavailable')
    variables = {'releasever': release, 'basearch': architecture, 'arch': architecture}
    program = _dnf_program()
    five = _dnf_is_five(program)
    local_only = command == 'remove' or command == 'repoquery' and '--installed' in query_options
    repositories = {} if local_only else _dnf_repositories(variables, enabled, disabled, five)
    with tempfile.TemporaryDirectory(prefix='wtl-dnf-') as temporary:
        root, metadata_start = pathlib.Path(temporary), 0
        os.chmod(root, 0o700)
        while True:
            directory = root / ('metadata-' + str(metadata_start))
            directory.mkdir(mode=0o700)
            with DnfRepositoryGateway(policy, point, repositories, variables, directory, metadata_start) as gateway:
                config = _dnf_config(directory, repositories, gateway)
                plugin = '--no-plugins' if five else '--noplugins'
                base = program + ['--config', str(config), plugin, '--releasever=' + release] \
                    + _dnf_command_pins(config, five) + switches
                missing = False
                if command == 'install':
                    status = _dnf_run(policy, base + ['install', '--downloadonly'] + packages, directory, 'download')
                    if not status:
                        gateway.online = False
                        return _dnf_run(policy, base + ['--cacheonly', 'install'] + packages, directory, 'install')
                    missing = _dnf_missing_candidate(directory, packages)
                elif command == 'repoquery':
                    installed = '--installed' in query_options
                    if installed:
                        gateway.online = False
                    status = _dnf_run(policy, base + (['--disablerepo=*', '--cacheonly'] if installed else [])
                                      + ['repoquery'] + query_options + packages, directory, 'query', query=True)
                    output = directory / 'query.out'
                    if output.stat().st_size > 1048576:
                        raise MirrorFailure('DNF query result exceeds limit')
                    text = output.read_text(errors='strict')
                    if status or any(not re.fullmatch(r'[A-Za-z0-9_.+:~%*-]+', line) for line in text.splitlines()):
                        return status or 65
                    if text.strip() or installed:
                        sys.stdout.write(text)
                        return 0
                    missing = True
                    status = 65
                else:
                    if command == 'remove':
                        gateway.online = False
                        base += ['--disablerepo=*', '--cacheonly']
                    return _dnf_run(policy, base + [command] + packages, directory, command)
                if not missing or gateway.artifacts_requested or gateway.failures or not gateway.metadata_indices:
                    return status
                next_metadata = min(gateway.metadata_indices.values()) + 1
                if next_metadata <= metadata_start or next_metadata >= len(policy.endpoints(point)):
                    return status
                for ordinal in sorted(set(gateway.metadata_indices.values())):
                    mirror_log(point, policy.endpoints(point)[ordinal], 'metadata-missing-candidate')
                metadata_start = next_metadata
