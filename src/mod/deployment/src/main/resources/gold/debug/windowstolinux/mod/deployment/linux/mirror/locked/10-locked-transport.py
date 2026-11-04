"""Task-local TLS transport for immutable repository URLs; no machine trust changes."""
import html.parser
import ipaddress
import shutil
import ssl


class LockedRepositoryTransport:
    def __init__(self, policy, point, directory, project=None, openssl=None):
        self.policy, self.point = policy, point
        self.directory = pathlib.Path(directory) / 'locked-transport'
        self.directory.mkdir(mode=0o700)
        self.project = pathlib.Path(project or os.getcwd())
        self.openssl = openssl or '/usr/bin/openssl'
        if not pathlib.Path(self.openssl).is_file():
            raise MirrorFailure('locked HTTPS transport requires the system OpenSSL executable')
        self.contexts, self.lock, self.active = {}, threading.Lock(), set()
        self.allowed_hosts = set()
        for endpoint in policy.endpoints(point):
            self.allowed_hosts.update(MirrorPolicy.normalized_host(host) for host in endpoint.get('allowedHosts', []))
            for url in endpoint.get('sourceOrigins', []) + [r['origin'] for r in endpoint.get('routes', [])]:
                self.allowed_hosts.add(urllib.parse.urlsplit(url).hostname)
        self.composer = self.composer_packages() if point == 'COMPOSER_REPOSITORY' else {}
        self.nuget_hashes = self.nuget_packages() if point == 'NUGET' else {}
        self.ca, self.key = self.directory / 'ca.pem', self.directory / 'ca.key'
        self.run_openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '1',
                         '-subj', '/CN=WindowsToLinux temporary task transport',
                         '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
                         '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
                         '-keyout', str(self.key), '-out', str(self.ca))
        os.chmod(self.key, 0o600)
        # SSL_CERT_FILE is also consumed by native clients for purposes beyond this connection.
        # Retain their public roots while adding only this process's temporary transport CA.
        self.client_ca = self.directory / 'client-ca-bundle.pem'
        self.client_ca.write_bytes(self.ca.read_bytes())
        with self.client_ca.open('ab') as output:
            for certificate in ssl.create_default_context().get_ca_certs(binary_form=True):
                output.write(ssl.DER_cert_to_PEM_cert(certificate).encode('ascii'))
        transport = self

        class QuietHandler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.0'

            def log_message(self, *args):
                pass

            def setup(self):
                self.request.settimeout(min(30, transport.policy.remaining()))
                super().setup()

            def do_CONNECT(self):
                try:
                    parsed = urllib.parse.urlsplit('https://' + self.path)
                    host = parsed.hostname
                    if (not host or parsed.username or parsed.password or parsed.path
                            or parsed.query or parsed.fragment or not parsed.port
                            or host not in transport.allowed_hosts):
                        raise MirrorFailure('CONNECT authority is outside the selected policy')
                    context = transport.tls_context(host)
                    self.send_response(200, 'Connection established')
                    self.end_headers()
                    self.wfile.flush()
                    self.close_connection = True
                    connection = context.wrap_socket(self.connection, server_side=True)
                    with transport.lock:
                        transport.active.add(connection)
                    try:
                        authority = ('[' + host + ']' if ':' in host else host) + (':' + str(parsed.port) if parsed.port != 443 else '')
                        transport.request_handler(authority)(connection, self.client_address, self.server)
                    finally:
                        with transport.lock:
                            transport.active.discard(connection)
                        connection.close()
                except (MirrorFailure, ValueError, OSError):
                    self.close_connection = True

            def do_GET(self):
                self.send_error(405, 'TLS CONNECT is required')

        class Server(MirrorLoopbackServer):
            daemon_threads = True

            def handle_error(self, *args):
                pass  # Never expose request URLs, headers, credentials or remote response bodies.

        self.server = Server(('127.0.0.1', 0), QuietHandler, mirror_policy=policy)
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def run_openssl(self, *arguments):
        environment = dict(os.environ, TEMP=str(self.directory), TMP=str(self.directory), TMPDIR=str(self.directory))
        try:
            subprocess.run([self.openssl, *arguments], cwd=self.directory, env=environment,
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           timeout=min(15, self.policy.remaining()), check=True)
        except (OSError, subprocess.SubprocessError):
            raise MirrorFailure('task TLS certificate creation failed') from None

    def tls_context(self, host):
        with self.lock:
            if host in self.contexts:
                return self.contexts[host]
            try:
                address = str(ipaddress.ip_address(host))
                san = 'IP:' + address
            except ValueError:
                if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?', host):
                    raise MirrorFailure('invalid TLS host')
                san = 'DNS:' + host
            identity = hashlib.sha256(host.encode()).hexdigest()
            key = self.directory / (identity + '.key')
            csr = self.directory / (identity + '.csr')
            certificate = self.directory / (identity + '.pem')
            extensions = self.directory / (identity + '.ext')
            extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n'
                                  'extendedKeyUsage=serverAuth\nsubjectAltName=' + san + '\n', encoding='ascii')
            self.run_openssl('req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256',
                             '-subj', '/CN=task-repository', '-keyout', str(key), '-out', str(csr))
            os.chmod(key, 0o600)
            self.run_openssl('x509', '-req', '-in', str(csr), '-CA', str(self.ca), '-CAkey', str(self.key),
                             '-set_serial', '0x' + os.urandom(16).hex(), '-days', '1', '-sha256',
                             '-extfile', str(extensions), '-out', str(certificate))
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(certificate, key)
            self.contexts[host] = context
            return context

    def request_handler(self, authority):
        transport = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.0'

            def log_message(self, *args):
                pass

            def finish(self):
                super().finish()
                if os.name == 'nt':
                    time.sleep(.05)

            def do_GET(self):
                try:
                    supplied = urllib.parse.urlsplit('https://' + self.headers.get('Host', authority))
                    expected = urllib.parse.urlsplit('https://' + authority)
                    if (not self.path.startswith('/') or self.path.startswith('//') or len(self.path) > 16384
                            or supplied.hostname != expected.hostname or (supplied.port or 443) != (expected.port or 443)
                            or supplied.username or supplied.password or supplied.path or supplied.query or supplied.fragment
                            or self.headers.get('Transfer-Encoding') or self.headers.get('Content-Length', '0') != '0'):
                        raise MirrorFailure('invalid tunneled repository request')
                    original = 'https://' + authority + self.path
                    with tempfile.TemporaryDirectory(prefix='request-', dir=transport.directory) as work:
                        target = pathlib.Path(work) / 'response'
                        # Never forward origin authentication, cookies, proxy credentials or arbitrary headers.
                        headers = {key: self.headers[key] for key in ('Accept',) if self.headers.get(key)}
                        metadata = transport.fetch(original, target, headers)
                        size = metadata['bytes']
                        self.send_response(200)
                        self.send_header('Content-Type', metadata.get('contentType') or 'application/octet-stream')
                        self.send_header('Content-Length', str(size))
                        self.send_header('Connection', 'close')
                        self.end_headers()
                        if self.command != 'HEAD':
                            with target.open('rb') as source:
                                shutil.copyfileobj(source, self.wfile, 65536)
                except (MirrorFailure, ValueError, OSError, KeyError):
                    self.send_error(502, 'selected repository transport failed')
                finally:
                    self.close_connection = True

            def do_HEAD(self):
                self.do_GET()

        return Handler

    @staticmethod
    def canonical_url(endpoint, url):
        # Metadata may return a URL at the selected mirror. Recover the canonical path so the
        # next artifact still follows the full configured priority chain.
        for route in sorted(endpoint.get('routes', []), key=lambda route: len(route['prefix']), reverse=True):
            if not route.get('pattern') and url.startswith(route['prefix']):
                return route['origin'] + url[len(route['prefix']):]
        return url

    def fetch(self, url, target, headers=None):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != 'https' or parsed.username or parsed.password or parsed.fragment:
            raise MirrorFailure('invalid locked repository URL')
        for endpoint in self.policy.endpoints(self.point):
            url = self.canonical_url(endpoint, url)
        if self.point == 'PYTHON_PACKAGE' and parsed.hostname == urllib.parse.urlsplit(self.source_origin()).hostname:
            match = re.fullmatch(r'/pypi/([A-Za-z0-9_.-]+)/([^/]+)/json', parsed.path)
            if match:
                return self.poetry_release(match.group(1), match.group(2), target)
        for endpoint in self.policy.endpoints(self.point):
            policy = MirrorPolicy(dict(self.policy.document, points={self.point: [endpoint]}), self.policy.deadline,
                                  self.policy.transfer_ledger)
            if hasattr(self.policy, 'transport_options'):
                policy.transport_options = self.policy.transport_options
            try:
                expected = None
                package_name = urllib.parse.unquote(urllib.parse.urlsplit(url).path).rsplit('/', 1)[-1].lower()
                nuget_hash = self.nuget_hashes.get(package_name)
                try:
                    policy.maps(endpoint, url)
                except MirrorFailure:
                    endpoint, expected = self.composer_route(policy, endpoint, url)
                    policy.points[self.point] = [endpoint]
                def validate_composer(path, response_headers):
                    if expected:
                        digest = hashlib.sha1()
                        with path.open('rb') as source:
                            for chunk in iter(lambda: source.read(65536), b''):
                                digest.update(chunk)
                        if digest.hexdigest() != expected:
                            raise MirrorFailure('digest-mismatch')
                metadata = mirror_transfer(policy, self.point, url, target,
                                           'sha512' if nuget_hash else None, nuget_hash, headers=headers,
                                           validator=validate_composer if expected else None)
                if (self.point == 'PYTHON_PACKAGE' and 'application/vnd.pypi.simple.v1+json' in (headers or {}).get('Accept', '')
                        and 'json' not in metadata['contentType'] and '/simple/' in urllib.parse.urlsplit(url).path):
                    if metadata['bytes'] > 33554432:
                        raise MirrorFailure('simple index exceeds metadata size limit')
                    entries = locked_simple_entries(pathlib.Path(target).read_bytes(), metadata['contentType'], policy.maps(endpoint, url))
                    value = {'meta': {'api-version': '1.0'}, 'name': urllib.parse.urlsplit(url).path.rstrip('/').rsplit('/', 1)[-1],
                             'files': [{'filename': entry['filename'], 'url': self.canonical_url(endpoint, policy.validate_url(endpoint, entry['url'])),
                                        'hashes': {'sha256': entry['sha256']} if entry['sha256'] else {},
                                        'yanked': entry.get('yanked', False), 'requires-python': entry.get('requires-python')}
                                       for entry in entries]}
                    encoded = json.dumps(value, separators=(',', ':')).encode()
                    pathlib.Path(target).write_bytes(encoded)
                    metadata = dict(metadata, bytes=len(encoded), contentType='application/vnd.pypi.simple.v1+json')
                return metadata
            except MirrorFailure:
                mirror_log(self.point, endpoint, 'locked-route-unavailable')
        raise MirrorFailure('all configured locked repository routes failed')

    def nuget_packages(self):
        result = {}
        for count, path in enumerate(self.project.rglob('packages.lock.json'), 1):
            self.policy.remaining()
            if count > 1024:
                raise MirrorFailure('NuGet project contains too many lock files')
            if path.is_symlink() or not path.is_file() or path.stat().st_size > 33554432:
                raise MirrorFailure('NuGet lock is not a bounded regular project file')
            document = json.loads(path.read_text(encoding='utf-8-sig'))
            for framework in document.get('dependencies', {}).values():
                for name, package in framework.items():
                    if package.get('type', '').lower() == 'project':
                        continue
                    encoded, version = package.get('contentHash'), package.get('resolved')
                    if not encoded or not version:
                        continue
                    try:
                        digest = base64.b64decode(encoded, validate=True)
                    except (ValueError, TypeError):
                        raise MirrorFailure('NuGet lock contains an invalid package checksum') from None
                    if len(digest) != 64 or not re.fullmatch(r'[A-Za-z0-9_.+-]+', name + version):
                        raise MirrorFailure('NuGet lock contains an invalid package identity')
                    key = (name + '.' + version + '.nupkg').lower()
                    if key in result and result[key] != digest.hex():
                        raise MirrorFailure('NuGet project locks disagree on the same package checksum')
                    result[key] = digest.hex()
        return result

    def source_origin(self):
        endpoint = self.policy.endpoints(self.point)[0]
        origins = endpoint.get('sourceOrigins', [])
        if not origins:
            origins = [route['origin'] for route in endpoint.get('routes', [])]
        if not origins:
            raise MirrorFailure('repository canonical origin is absent from the frozen policy')
        return origins[0]

    def composer_packages(self):
        path = self.project / 'composer.lock'
        if not path.is_file():
            return {}
        if path.stat().st_size > 33554432:
            raise MirrorFailure('Composer lock exceeds size limit')
        document = json.loads(path.read_text(encoding='utf-8'))
        result = {}
        for package in document.get('packages', []) + document.get('packages-dev', []):
            dist = package.get('dist', {})
            url = dist.get('url')
            if url:
                result[url] = package
                self.allowed_hosts.add(urllib.parse.urlsplit(url).hostname)
        return result

    def composer_route(self, policy, endpoint, url):
        package = self.composer.get(url)
        if not package:
            raise MirrorFailure('fixed URL has no compatible configured route')
        dist = package['dist']
        expected = dist.get('shasum', '').lower()
        # Empty shasum is common in Composer locks. Matching a commit is not proof that a mirror's
        # repacked archive is byte-identical; do not invent a checksum or weaken the existing lock.
        if not re.fullmatch('[a-f0-9]{40}', expected):
            raise MirrorFailure('Composer mirror relocation requires the original lock checksum')
        name = package.get('name', '')
        if not re.fullmatch(r'[a-z0-9_.-]+/[a-z0-9_.-]+', name):
            raise MirrorFailure('invalid locked Composer package')
        data, _ = mirror_bytes(policy, self.point, urllib.parse.urljoin(self.source_origin(), 'p2/' + name + '.json'))
        document = json.loads(data)
        previous = {}
        for item in document.get('packages', {}).get(name, []):
            if document.get('minified') == 'composer/2.0':
                previous = dict(previous, **item)
                previous = {key: value for key, value in previous.items() if value != '__unset'}
                item = previous
            candidate = item.get('dist', {})
            if (item.get('version') != package.get('version') or candidate.get('reference') != dist.get('reference')
                    or candidate.get('type') != dist.get('type')
                    or candidate.get('shasum', '').lower() not in ('', expected)):
                continue
            selected = policy.validate_url(endpoint, candidate.get('url', ''))
            return dict(endpoint, routes=[{'origin': url, 'prefix': selected}]), expected
        raise MirrorFailure('selected Composer metadata has no checksum-compatible locked distribution')

    def poetry_release(self, name, version, target):
        # Poetry install asks the PyPI JSON API for the files of an already locked package.
        # Supply those exact lock filenames from the selected PEP 503/691 index, never fabricate
        # dependency metadata: requires_dist=None makes Poetry inspect the real distribution.
        lock = self.project / 'poetry.lock'
        if not lock.is_file() or lock.stat().st_size > 33554432:
            raise MirrorFailure('PyPI JSON adaptation requires an unchanged Poetry lock')
        normalized = lambda value: re.sub('[-_.]+', '-', value).lower()
        text = lock.read_text(encoding='utf-8')
        files = {}
        for block in re.split(r'(?m)^\[\[package\]\]\s*$', text)[1:]:
            package_name = re.search(r'(?m)^name\s*=\s*"([^"]+)"\s*$', block)
            package_version = re.search(r'(?m)^version\s*=\s*"([^"]+)"\s*$', block)
            if (package_name and package_version and normalized(package_name.group(1)) == normalized(name)
                    and package_version.group(1) == version):
                files = dict(re.findall(r'\{\s*file\s*=\s*"([^"\\]+)"\s*,\s*hash\s*=\s*"sha256:([a-fA-F0-9]{64})"\s*\}', block))
                break
        if not files:
            raise MirrorFailure('Poetry lock has no supported SHA-256 file entries for the requested release')
        original = urllib.parse.urljoin(self.source_origin(), normalized(name) + '/')
        for endpoint in self.policy.endpoints(self.point):
            try:
                single = MirrorPolicy(dict(self.policy.document, points={self.point: [endpoint]}), self.policy.deadline,
                                      self.policy.transfer_ledger)
                if hasattr(self.policy, 'transport_options'):
                    single.transport_options = self.policy.transport_options
                data, metadata = mirror_bytes(single, self.point, original, headers={'Accept': 'application/vnd.pypi.simple.v1+json, text/html;q=0.9'})
                selected = single.maps(endpoint, original)
                urls = []
                for entry in locked_simple_entries(data, metadata['contentType'], selected):
                    if entry['filename'] not in files:
                        continue
                    expected = files[entry['filename']].lower()
                    if entry['sha256'] and entry['sha256'].lower() != expected:
                        raise MirrorFailure('selected index checksum differs from unchanged Poetry lock')
                    absolute = single.validate_url(endpoint, entry['url'])
                    absolute = self.canonical_url(endpoint, absolute)
                    urls.append({'filename': entry['filename'], 'url': absolute, 'digests': {'sha256': expected},
                                 'packagetype': 'bdist_wheel' if entry['filename'].endswith('.whl') else 'sdist',
                                 'yanked': entry.get('yanked', False)})
                if not urls:
                    raise MirrorFailure('selected simple index has no unchanged Poetry lock files')
                result = {'info': {'name': name, 'version': version, 'summary': '', 'requires_dist': None,
                                   'requires_python': None, 'yanked': False}, 'urls': urls}
                encoded = json.dumps(result, separators=(',', ':')).encode()
                pathlib.Path(target).write_bytes(encoded)
                return {'bytes': len(encoded), 'contentType': 'application/json'}
            except (MirrorFailure, ValueError, KeyError):
                mirror_log(self.point, endpoint, 'locked-pypi-metadata-unavailable')
        raise MirrorFailure('selected sources have no compatible Poetry lock metadata')

    def environment(self):
        environment = dict(os.environ)
        client_timeout = str(max(1, int(self.policy.remaining()) + 1))
        for key in ('http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'all_proxy'):
            environment[key] = self.base
        environment.update(NO_PROXY='', no_proxy='', REQUESTS_CA_BUNDLE=str(self.client_ca), SSL_CERT_FILE=str(self.client_ca),
                           CURL_CA_BUNDLE=str(self.client_ca), COMPOSER_CAFILE=str(self.client_ca), PIP_CERT=str(self.client_ca),
                           NODE_EXTRA_CA_CERTS=str(self.client_ca), npm_config_proxy=self.base,
                           npm_config_https_proxy=self.base, npm_config_noproxy='', npm_config_strict_ssl='true',
                           PIP_RETRIES='0', PIP_TIMEOUT=client_timeout, POETRY_REQUESTS_TIMEOUT=client_timeout, POETRY_HTTP_RETRIES='0',
                           NUGET_ENHANCED_MAX_NETWORK_TRY_COUNT='1',
                           COMPOSER_MAX_PARALLEL_HTTP='1', COMPOSER_PROCESS_TIMEOUT=str(max(1, int(self.policy.remaining()))))
        for key in ('NODE_TLS_REJECT_UNAUTHORIZED', 'PIP_TRUSTED_HOST', 'UV_INSECURE_HOST'):
            environment.pop(key, None)
        return environment

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        with self.lock:
            for connection in list(self.active):
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
        self.server.server_close()
        self.thread.join()
        self.contexts.clear()


def locked_simple_entries(data, content_type, source):
    if 'json' in content_type:
        document = json.loads(data)
        return [{'filename': item['filename'], 'url': urllib.parse.urljoin(source, item['url']),
                 'sha256': item.get('hashes', {}).get('sha256', ''), 'yanked': item.get('yanked', False),
                 'requires-python': item.get('requires-python')}
                for item in document.get('files', [])]

    class Links(html.parser.HTMLParser):
        def __init__(self):
            super().__init__()
            self.entries = []

        def handle_starttag(self, tag, attributes):
            values = dict(attributes)
            if tag.lower() == 'a' and 'href' in values:
                absolute, fragment = urllib.parse.urldefrag(urllib.parse.urljoin(source, values['href']))
                self.entries.append({'filename': urllib.parse.unquote(urllib.parse.urlsplit(absolute).path.rsplit('/', 1)[-1]),
                                     'url': absolute, 'sha256': dict(urllib.parse.parse_qsl(fragment)).get('sha256', ''),
                                     'yanked': 'data-yanked' in values, 'requires-python': values.get('data-requires-python')})

    parser = Links()
    parser.feed(data.decode('utf-8'))
    return parser.entries


def locked_dependency(policy, point, command, directory):
    if point not in ('COMPOSER_REPOSITORY', 'PYTHON_PACKAGE', 'NODE_PACKAGE', 'NUGET'):
        raise MirrorFailure('locked HTTPS transport is unavailable for this package protocol')
    with LockedRepositoryTransport(policy, point, directory) as transport:
        environment = transport.environment()
        # The package manager consumes its original manifest and lock. Only transport and temporary
        # trust differ; do not run install again after a failed request or silently select official.
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
