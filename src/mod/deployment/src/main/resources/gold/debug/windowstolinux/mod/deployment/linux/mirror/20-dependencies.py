"""Ephemeral package repository gateways; never edits a project's manifest or lock."""
import xml.sax.saxutils


class RepositoryGateway:
    def __init__(self, policy, directory):
        self.policy, self.directory = policy, pathlib.Path(directory)
        gateway = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def finish(self):
                super().finish()
                if os.name == 'nt':
                    # Let loopback clients consume the final write before the Windows close.
                    time.sleep(.05)

            def do_GET(self):
                work = tempfile.TemporaryDirectory(prefix='wtl-gateway-')
                try:
                    parts = self.path.split('/', 3)
                    point = parts[1]
                    if parts[2] == 'u':
                        encoded, separator, suffix = parts[3].partition('/')
                        if encoded.endswith('.json'):
                            encoded = encoded[:-5]
                        original = base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)).decode()
                        if separator:
                            original = original.rstrip('/') + '/' + suffix
                    elif parts[2] == 'a':
                        alias, separator, suffix = parts[3].partition('/')
                        original = gateway.aliases[alias] + suffix
                    elif parts[2] == 'root':
                        original = gateway.origin(point).rstrip('/') + '/' + (parts[3] if len(parts) > 3 else '')
                    else:
                        raise MirrorFailure('invalid repository gateway route')
                    downloaded = pathlib.Path(work.name) / 'response'
                    algorithm, expected = gateway.hashes.get(original, (None, None))
                    def validate_metadata(path, response_headers):
                        if point in ('NODE_PACKAGE', 'PYTHON_PACKAGE', 'NUGET', 'COMPOSER_REPOSITORY', 'RUST_INDEX') \
                                and ('json' in response_headers.get('Content-Type', '') or original.endswith('.json')):
                            if path.stat().st_size > 33554432:
                                raise ValueError('metadata size')
                            json.loads(path.read_bytes())
                    metadata = mirror_transfer(policy, point, original, downloaded, algorithm, expected,
                                                limit=2147483648, validator=validate_metadata)
                    data = None
                    content = metadata['contentType']
                    # Repository metadata links are explicit protocol fields. Package bytes, hashes,
                    # signatures, Go sumdb responses and lock files are never rewritten.
                    if point in ('NODE_PACKAGE', 'PYTHON_PACKAGE', 'NUGET', 'COMPOSER_REPOSITORY', 'RUST_INDEX'):
                        if 'json' in content or original.endswith('.json'):
                            if metadata['bytes'] > 33554432:
                                raise MirrorFailure('registry metadata exceeds size limit')
                            data = downloaded.read_bytes()
                            try:
                                parsed = json.loads(data)
                                parsed = gateway.rewrite_json(parsed, point)
                                data = json.dumps(parsed, separators=(',', ':'), ensure_ascii=False).encode()
                            except (ValueError, UnicodeError):
                                if 'json' in content:
                                    raise MirrorFailure('invalid registry JSON')
                        elif point == 'PYTHON_PACKAGE' and ('html' in content or '/simple/' in original):
                            if metadata['bytes'] > 33554432:
                                raise MirrorFailure('registry metadata exceeds size limit')
                            data = downloaded.read_bytes()
                            text = data.decode('utf-8')
                            def link(match):
                                absolute = urllib.parse.urljoin(original, match.group(2))
                                # Fragment hash remains part of the link consumed by pip.
                                value, fragment = urllib.parse.urldefrag(absolute)
                                digests = dict(urllib.parse.parse_qsl(fragment))
                                algorithm = next((item for item in ('sha512', 'sha256') if item in digests), None)
                                return match.group(1) + gateway.link(point, value, algorithm, digests.get(algorithm)) + ('#' + fragment if fragment else '') + match.group(3)
                            data = re.sub(r'''(href\s*=\s*["'])([^"']+)(["'])''', link, text, flags=re.I).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', content or 'application/octet-stream')
                    self.send_header('Content-Length', str(len(data) if data is not None else metadata['bytes']))
                    self.end_headers()
                    if self.command != 'HEAD':
                        if data is not None:
                            self.wfile.write(data)
                        else:
                            import shutil
                            with downloaded.open('rb') as source:
                                shutil.copyfileobj(source, self.wfile, 65536)
                except (MirrorFailure, ValueError, IndexError, KeyError, OSError):
                    self.send_error(502, 'configured mirror request failed')
                finally:
                    work.cleanup()

            def do_HEAD(self):
                # A GET checks the same object; the gateway exposes no extra upstream sources.
                self.do_GET()

        self.server = MirrorLoopbackServer(('127.0.0.1', 0), Handler, mirror_policy=policy)
        self.server.daemon_threads = True
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)
        self.aliases = {}
        self.hashes = {}
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def origin(self, point):
        endpoint = self.policy.endpoints(point)[0]
        routes = endpoint.get('routes', [])
        return routes[0]['origin'] if routes else endpoint['baseUrl']

    def root(self, point):
        self.policy.endpoints(point)
        return self.base + '/' + point + '/root/'

    def link(self, point, value, algorithm=None, expected=None):
        for candidate in [point] + [p for p in self.policy.points if p != point]:
            for endpoint in self.policy.points.get(candidate, []):
                for route in endpoint.get('routes', []):
                    if not route.get('pattern') and value.startswith(route['prefix']):
                        value = route['origin'] + value[len(route['prefix']):]
                    if not route.get('pattern') and value.rstrip('/') == route['prefix'].rstrip('/'):
                        value = route['origin'].rstrip('/')
                    if value.startswith(route['origin']) or value.rstrip('/') == route['origin'].rstrip('/'):
                        if algorithm and expected:
                            self.hashes[value] = algorithm, expected
                        markers = [value.index(marker) for marker in ('{', '%', '$') if marker in value]
                        if markers:
                            offset = min(markers)
                            prefix, suffix = value[:offset], value[offset:]
                            alias = hashlib.sha256(prefix.encode()).hexdigest()
                            self.aliases[alias] = prefix
                            return self.base + '/' + candidate + '/a/' + alias + '/' + suffix
                        encoded = base64.urlsafe_b64encode(value.encode()).decode().rstrip('=')
                        return self.base + '/' + candidate + '/u/' + encoded + ('.json' if value.endswith('.json') else '')
        # Some metadata contains optional search/publish resources. Route those to a local
        # rejection if requested, rather than failing an otherwise usable service index or
        # allowing the client to bypass this task's source policy.
        encoded = base64.urlsafe_b64encode(value.encode()).decode().rstrip('=')
        return self.base + '/' + point + '/u/' + encoded + ('.json' if value.endswith('.json') else '')

    def rewrite_json(self, value, point, key=''):
        # These fields carry URLs in the supported registry protocols, not package identity.
        fields = {'tarball', 'url', 'href', '@id', 'packageBaseAddress', 'registration', 'dl', 'api',
                  'packages', 'metadata-url', 'providers-url', 'p2', 'archives', 'dist', 'download_url'}
        fields.discard('api')
        if isinstance(value, dict):
            result = {k: self.rewrite_json(v, point, k) for k, v in value.items()}
            if point == 'NODE_PACKAGE' and value.get('tarball') and value.get('integrity'):
                for integrity in value['integrity'].split():
                    algorithm, separator, encoded = integrity.partition('-')
                    if separator and algorithm in ('sha256', 'sha512'):
                        try:
                            digest = base64.b64decode(encoded, validate=True).hex()
                            result['tarball'] = self.link(point, value['tarball'], algorithm, digest)
                        except ValueError:
                            raise MirrorFailure('invalid npm integrity metadata')
                        break
            if point == 'PYTHON_PACKAGE' and value.get('url') and isinstance(value.get('hashes'), dict):
                for algorithm in ('sha512', 'sha256'):
                    if value['hashes'].get(algorithm):
                        result['url'] = self.link(point, value['url'], algorithm, value['hashes'][algorithm])
                        break
            return result
        if isinstance(value, list):
            return [self.rewrite_json(item, point, key) for item in value]
        if isinstance(value, str) and value.startswith(('https://', 'http://')) and key in fields:
            return self.link(point, value)
        return value

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


def reject_unmapped_lock_urls(policy, point, directory):
    """Native source replacement has defined exceptions; arbitrary URL locks must fail closed."""
    names = {
        'NODE_PACKAGE': ('package-lock.json', 'npm-shrinkwrap.json', 'pnpm-lock.yaml', 'yarn.lock'),
        'PYTHON_PACKAGE': ('requirements.lock', 'uv.lock', 'Pipfile.lock', 'poetry.lock'),
        'COMPOSER_REPOSITORY': ('composer.lock',),
        'RUBYGEMS': ('Gemfile.lock',),
    }.get(point, ())
    for name in names:
        path = pathlib.Path(directory) / name
        if not path.is_file():
            continue
        text = path.read_text(encoding='utf-8')
        urls = re.findall(r'https?://[^\s"\'<>\)]+', text)
        if not urls:
            continue
        endpoints = policy.endpoints(point)
        for url in urls:
            # npm's documented registry host replacement retains lock integrity. Other fixed
            # URL forms cannot be rerouted without changing the lock or intercepting TLS.
            if point == 'NODE_PACKAGE' and name in ('package-lock.json', 'npm-shrinkwrap.json'):
                if any(any(url.startswith(route['origin']) for route in ep.get('routes', [])) for ep in endpoints):
                    continue
            if point == 'RUBYGEMS' and name == 'Gemfile.lock' and 'remote: ' + url in text:
                if any(url.rstrip('/') in (endpoint.get('baseUrl', '').rstrip('/'), route['origin'].rstrip('/'), route['prefix'].rstrip('/'))
                       for endpoint in endpoints for route in endpoint.get('routes', [])):
                    continue  # Bundler mirror.<original> is a native, lock-preserving mapping.
            # Metadata registries in lock formats are native source declarations; actual fixed
            # archive URLs would bypass the task gateway and are intentionally rejected.
            raise MirrorFailure('fixed URL in %s cannot honor the selected source policy' % name)


def dependency_environment(policy, gateway, point, command, directory):
    environment = dict(os.environ)
    root = pathlib.Path(directory)
    escaped = xml.sax.saxutils.escape
    client_timeout = max(1, int(policy.remaining()) + 1)
    client_milliseconds = str(client_timeout * 1000)
    reject_unmapped_lock_urls(policy, point, pathlib.Path.cwd())
    if point == 'NODE_PACKAGE':
        environment.update(npm_config_registry=gateway.root(point), npm_config_fetch_retries='0',
                           npm_config_fetch_timeout=client_milliseconds, npm_config_replace_registry_host='always',
                           npm_config_audit='false', npm_config_fund='false',
                           YARN_NPM_REGISTRY_SERVER=gateway.root(point),
                           YARN_REGISTRY=gateway.root(point))
        command = [item for item in command if not item.startswith('--registry=')]
        npmrc = pathlib.Path('.npmrc')
        if npmrc.is_file():
            for line in npmrc.read_text(encoding='utf-8').splitlines():
                match = re.fullmatch(r'\s*((?:@[^\s:]+:)?registry)\s*=\s*(https?://\S+)\s*', line)
                if not match:
                    continue
                declared = match.group(2).rstrip('/')
                compatible = any(declared in (endpoint.get('baseUrl', '').rstrip('/'), route['origin'].rstrip('/'), route['prefix'].rstrip('/'))
                                 for endpoint in policy.endpoints(point) for route in endpoint.get('routes', []))
                if not compatible:
                    raise MirrorFailure('project npm registry has no exact task source route')
                command.append('--' + match.group(1) + '=' + gateway.link(point, match.group(2)).rstrip('/') + '/')
    elif point == 'PYTHON_PACKAGE':
        endpoint = gateway.root(point)
        environment.update(PIP_INDEX_URL=endpoint, PIP_EXTRA_INDEX_URL='', PIP_CONFIG_FILE=os.devnull,
                           PIP_TRUSTED_HOST='127.0.0.1', PIP_RETRIES='0', PIP_TIMEOUT=str(client_timeout),
                           UV_DEFAULT_INDEX=endpoint, UV_INDEX_URL=endpoint,
                           UV_INSECURE_HOST='127.0.0.1', UV_HTTP_RETRIES='0', UV_HTTP_TIMEOUT=str(client_timeout),
                           PIPENV_PYPI_MIRROR=endpoint, PIPENV_MAX_RETRIES='0')
        command = [item for item in command if not item.startswith('--index-url=') and item != '--isolated']
        if pathlib.Path(command[0]).name == 'poetry':
            # Poetry project source declarations cannot be replaced by an environment variable.
            raise MirrorFailure('Poetry source replacement cannot preserve project inputs; use a compatible locked manager')
    elif point == 'GO_MODULE':
        environment['GOPROXY'] = gateway.root(point)
        environment['GONOPROXY'] = 'none'
        environment['GOPRIVATE'] = ''
        checksum = policy.endpoints('GO_CHECKSUM')[0]
        # Keep the configured sumdb verifier identity/key; only its transport URL changes.
        identity = checksum.get('verifier', '') or urllib.parse.urlsplit(gateway.origin('GO_CHECKSUM')).hostname
        if not identity:
            raise MirrorFailure('Go checksum verifier identity is absent')
        environment['GOSUMDB'] = identity + ' ' + gateway.root('GO_CHECKSUM')
        environment['GONOSUMDB'] = 'none'
    elif point == 'MAVEN_REPOSITORY':
        settings = root / 'settings.xml'
        settings.write_text('<settings><mirrors><mirror><id>wtl-task</id><mirrorOf>*</mirrorOf><url>'
                            + escaped(gateway.root(point)) + '</url></mirror></mirrors></settings>')
        command = command[:1] + ['--settings', str(settings), '--global-settings', str(settings),
                                '-Dmaven.wagon.http.retryHandler.count=0', '-Dmaven.wagon.rto=' + client_milliseconds,
                                '-Daether.connector.requestTimeout=' + client_milliseconds] + command[1:]
    elif point == 'GRADLE_PLUGIN':
        init = root / 'mirrors.gradle'
        plugin_repository = json.dumps(gateway.root(point))
        dependency_repository = json.dumps(gateway.root('MAVEN_REPOSITORY'))
        origins = sorted({route['origin'].rstrip('/') for candidate in ('MAVEN_REPOSITORY', point)
                          for endpoint in policy.endpoints(candidate) for route in endpoint.get('routes', [])})
        init.write_text('''def wtlOrigins = %s
def wtlRepos = { repos, selected ->
  repos.clear()
  repos.maven { url = uri(selected); allowInsecureProtocol = true }
  repos.whenObjectAdded { r ->
    if (!r.hasProperty('url')) throw new GradleException('unsupported task repository protocol')
    def original = r.url.toString().replaceAll('/+$', '')
    if (original != selected.replaceAll('/+$', '') && !wtlOrigins.contains(original)) throw new GradleException('repository has no exact task mirror route')
    r.url = uri(selected)
    r.allowInsecureProtocol = true
  }
}
beforeSettings { s -> wtlRepos(s.pluginManagement.repositories, %s) }
settingsEvaluated { s ->
  wtlRepos(s.pluginManagement.repositories, %s)
  if (s.hasProperty('dependencyResolutionManagement')) wtlRepos(s.dependencyResolutionManagement.repositories, %s)
}
allprojects { p ->
  wtlRepos(p.repositories, %s)
  wtlRepos(p.buildscript.repositories, %s)
}
''' % (json.dumps(origins), plugin_repository, plugin_repository, dependency_repository,
       dependency_repository, dependency_repository))
        command += ['--init-script', str(init), '-Dorg.gradle.internal.http.connectionTimeout=' + str(max(1, int(policy.connect * 1000))),
                    '-Dorg.gradle.internal.http.socketTimeout=' + client_milliseconds]
    elif point == 'RUST_CRATE':
        cargo = pathlib.Path(environment.get('CARGO_HOME', str(pathlib.Path.cwd() / '.w2l/mirror-cargo')))
        cargo.mkdir(parents=True, exist_ok=True)
        (cargo / 'config.toml').write_text('[source.crates-io]\nreplace-with="wtl-task"\n'
            '[source.wtl-task]\nregistry=' + json.dumps('sparse+' + gateway.root('RUST_INDEX'))
            + '\n[net]\nretry=0\n[http]\ntimeout=' + str(client_timeout) + '\nlow-speed-limit=' + str(policy.minimum) + '\n')
        environment['CARGO_HOME'] = str(cargo)
        command += ['--config', 'source.crates-io.replace-with="wtl-task"', '--config',
                    'source.wtl-task.registry=' + json.dumps('sparse+' + gateway.root('RUST_INDEX'))]
        lock = pathlib.Path('Cargo.lock').read_text()
        if re.search(r'source\s*=\s*"git\+', lock):
            raise MirrorFailure('Cargo git dependency cannot honor registry-only policy')
    elif point == 'NUGET':
        # Keep the effective NuGet config hierarchy, source mapping, trusted signers, audit
        # sources and original HTTPS service-index URLs. Only the transport may be changed.
        raise MirrorFailure('NuGet requires transport routing with its original validation configuration')
    elif point == 'COMPOSER_REPOSITORY':
        # COMPOSER_REPO_PACKAGIST disables default Packagist and supplies a task-only repository.
        environment['COMPOSER_REPO_PACKAGIST'] = gateway.root(point)
        environment['COMPOSER_MAX_PARALLEL_HTTP'] = '1'
        environment['COMPOSER_IPRESOLVE'] = '4'
        raise MirrorFailure('Composer fixed dist URLs cannot be safely redirected without changing lock input')
    elif point == 'RUBYGEMS':
        bundle = root / 'bundle'
        bundle.mkdir()
        lines = ['BUNDLE_RETRY: "0"', 'BUNDLE_TIMEOUT: ' + json.dumps(str(client_timeout)),
                 'BUNDLE_FROZEN: "true"', 'BUNDLE_DEPLOYMENT: "true"', 'BUNDLE_PATH: "vendor/bundle"',
                 'BUNDLE_DISABLE_SHARED_GEMS: "true"']
        for endpoint in policy.endpoints(point):
            for route in endpoint.get('routes', []):
                for original in {route['origin'], route['prefix'], endpoint['baseUrl']}:
                    key = 'BUNDLE_MIRROR__' + original.upper().replace('.', '__').replace('-', '___')
                    lines.append(json.dumps(key) + ': ' + json.dumps(gateway.root(point)))
                    lines.append(json.dumps(key.rstrip('/') + '__FALLBACK_TIMEOUT') + ': "0"')
        (bundle / 'config').write_text('\n'.join(lines) + '\n')
        environment['BUNDLE_APP_CONFIG'] = str(bundle)
        command = [item for item in command if not item.startswith('--source=')]
        if '--source' in command:
            index = command.index('--source')
            del command[index:index + 2]
        if pathlib.Path(command[0]).name == 'gem':
            command += ['--clear-sources', '--source', gateway.root(point)]
    else:
        raise MirrorFailure('unsupported dependency protocol: ' + point)
    return environment, command


def official_native_environment(policy, point, reason):
    entries = policy.endpoints(point)
    if len(entries) != 1 or not entries[0].get('official') or entries[0].get('custom') or entries[0].get('explicit'):
        return None
    names = {'NODE_PACKAGE': ('package-lock.json', 'npm-shrinkwrap.json', 'pnpm-lock.yaml', 'yarn.lock'),
             'PYTHON_PACKAGE': ('requirements.lock', 'uv.lock', 'Pipfile.lock', 'poetry.lock'),
             'COMPOSER_REPOSITORY': ('composer.lock',), 'RUBYGEMS': ('Gemfile.lock',)}.get(point, ())
    urls = []
    for name in names:
        path = pathlib.Path(name)
        if path.is_file():
            urls.extend(re.findall(r'https?://[^\s"\'<>\)]+', path.read_text(encoding='utf-8')))
    for endpoint in entries:
        if not endpoint.get('official') or endpoint.get('custom'):
            continue
        try:
            for url in urls:
                policy.validate_url(endpoint, policy.maps(endpoint, url))
        except MirrorFailure:
            continue
        mirror_log(point, endpoint, 'native-official-compatible-' + reason)
        environment = dict(os.environ)
        environment.update(POETRY_REQUESTS_TIMEOUT='30', POETRY_HTTP_RETRIES='0',
                           COMPOSER_PROCESS_TIMEOUT=str(max(1, int(policy.remaining()))))
        return environment
    return None


def mirror_dependency(policy, point, command):
    if not command:
        raise MirrorFailure('dependency command is absent')
    with tempfile.TemporaryDirectory(prefix='wtl-repository-') as directory:
        with RepositoryGateway(policy, directory) as gateway:
            try:
                environment, command = dependency_environment(policy, gateway, point, command, directory)
            except MirrorFailure as error:
                if 'locked_dependency' in globals():
                    return locked_dependency(policy, point, command, directory)
                environment = official_native_environment(policy, point, 'fixed-input-routing')
                if environment is None:
                    raise error
            process = subprocess.Popen(command, cwd=os.getcwd(), env=environment, start_new_session=os.name != 'nt')
            try:
                return process.wait(timeout=policy.remaining())
            finally:
                if os.name != 'nt':
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                elif process.poll() is None:
                    process.kill()
                process.wait()
