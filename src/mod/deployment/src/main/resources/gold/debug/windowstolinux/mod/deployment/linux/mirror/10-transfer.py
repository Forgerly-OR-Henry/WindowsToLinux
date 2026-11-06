"""Task-local protocol aware transfers. No source addresses are supplied by this code."""
import argparse
import atexit
import base64
import hashlib
import http.client
import http.server
import json
import os
import pathlib
import re
import shlex
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


class MirrorFailure(Exception):
    pass


def mirror_loopback_peer_uid(local_address, peer_address, table):
    """Use the client's outgoing socket row, never the server's own accepted-socket UID."""
    matches = []
    for line in table.splitlines():
        fields = line.split()
        if len(fields) < 8 or fields[3] != '01':
            continue
        try:
            def address(value):
                host, port = value.split(':')
                if len(host) != 8:
                    raise ValueError('not IPv4')
                return socket.inet_ntoa(struct.pack('=I', int(host, 16))), int(port, 16)
            if address(fields[1]) == tuple(peer_address[:2]) and address(fields[2]) == tuple(local_address[:2]):
                matches.append(int(fields[7]))
        except (ValueError, OSError, struct.error):
            continue
    if len(matches) != 1:
        raise MirrorFailure('loopback client socket owner is absent or ambiguous')
    return matches[0]


def mirror_loopback_client_allowed(local_address, peer_address, credentials_required=False, table=None):
    if not sys.platform.startswith('linux'):
        return os.name == 'nt'  # Windows protocol fixtures do not establish Linux isolation.
    try:
        if table is None:
            with open('/proc/net/tcp', 'r', encoding='ascii') as source:
                table = source.read(8388609)
            if len(table) > 8388608:
                raise MirrorFailure('loopback socket table exceeds limit')
        return mirror_loopback_peer_uid(local_address, peer_address, table) == os.geteuid()
    except (OSError, UnicodeError, MirrorFailure):
        # Anonymous public-source transport grants no new credential access. Authenticated
        # endpoints always fail closed when the kernel cannot prove this task's client UID.
        return not credentials_required


class MirrorLoopbackServer(http.server.ThreadingHTTPServer):
    def __init__(self, address, handler, mirror_policy=None):
        self.credentials_required = bool(mirror_policy and any(
            endpoint.get('secretRef') for endpoints in mirror_policy.points.values() for endpoint in endpoints))
        super().__init__(address, handler)

    def verify_request(self, request, client_address):
        return mirror_loopback_client_allowed(request.getsockname(), client_address, self.credentials_required)


def mirror_log(point, endpoint, reason, attempt=0):
    # Never log URLs, query strings, credentials or server response bodies.
    provider = re.sub(r'[^A-Za-z0-9_.-]', '_', endpoint.get('providerId', 'unknown'))[:96]
    print('MIRROR point=%s source=%s attempt=%s reason=%s' % (point, provider, attempt, reason),
          file=sys.stderr, flush=True)


class MirrorTransferLedger:
    def __init__(self):
        self.lock, self.objects, self.directory = threading.Lock(), {}, None

    def object(self, key):
        with self.lock:
            return self.objects.setdefault(key, {'lock': threading.Lock(), 'attempts': {}, 'exhausted': set(), 'cache': {}})

    def cache_path(self):
        with self.lock:
            if self.directory is None:
                self.directory = tempfile.TemporaryDirectory(prefix='wtl-transfer-cache-')
                atexit.register(self.directory.cleanup)
            descriptor, path = tempfile.mkstemp(dir=self.directory.name)
            os.close(descriptor)
            return pathlib.Path(path)


class MirrorPolicy:
    environment_ledgers, environment_lock = {}, threading.Lock()

    def __init__(self, document, deadline=None, transfer_ledger=None):
        if document.get('formatVersion') != 1 or not isinstance(document.get('points'), dict):
            raise MirrorFailure('invalid frozen mirror policy')
        self.document = document
        self.points = document['points']
        self.transfer_ledger = transfer_ledger or MirrorTransferLedger()
        self.deadline = deadline
        if self.deadline is None and os.environ.get('WTL_MIRROR_DEADLINE'):
            self.deadline = float(os.environ['WTL_MIRROR_DEADLINE'])
        if os.environ.get('WTL_MIRROR_BUDGET_SECONDS') and os.environ.get('WTL_MIRROR_STARTED_AT'):
            remaining = float(os.environ['WTL_MIRROR_BUDGET_SECONDS']) - (time.time() - float(os.environ['WTL_MIRROR_STARTED_AT']))
            task_deadline = time.monotonic() + remaining
            self.deadline = task_deadline if self.deadline is None else min(self.deadline, task_deadline)
        limits = document.get('timeouts', {})
        self.connect = min(16777216.0, max(.01, float(limits.get('connectSeconds', 10))))
        self.idle = min(16777216.0, max(.01, float(limits.get('readSeconds', 60))),
                        max(.01, float(limits.get('noProgressSeconds', 30))))
        self.window = min(16777216.0, max(.01, float(limits.get('lowSpeedWindowSeconds', limits.get('lowSpeedSeconds', 60)))))
        self.minimum = min(16777216, max(1, int(limits.get('lowSpeedBytesPerSecond', limits.get('minimumBytesPerSecond', 65536)))))
        self.attempts = min(2, max(1, int(limits.get('attemptsPerEndpoint', 2))))

    @classmethod
    def from_environment(cls, deadline=None):
        path = os.environ.get('WTL_MIRROR_POLICY')
        if not path:
            return None
        raw = pathlib.Path(path).read_bytes()
        policy_sha = hashlib.sha256(raw).hexdigest()
        if os.environ.get('WTL_MIRROR_POLICY_SHA256', policy_sha) != policy_sha:
            raise MirrorFailure('frozen policy material binding differs from its bytes')
        with cls.environment_lock:
            ledger = cls.environment_ledgers.setdefault(policy_sha, MirrorTransferLedger())
        policy = cls(json.loads(raw), deadline, transfer_ledger=ledger)
        installer = globals().get('install_mirror_credentials')
        return installer(policy, policy_sha) if installer is not None else policy

    def remaining(self):
        value = 3600 if self.deadline is None else self.deadline - time.monotonic()
        if value <= 0:
            raise MirrorFailure('task deadline exceeded')
        return value

    def endpoints(self, point):
        entries = self.points.get(point)
        if not isinstance(entries, list) or not 1 <= len(entries) <= 256:
            raise MirrorFailure('missing or invalid frozen point: ' + point)
        return entries

    def transport_options(self, endpoint, url):
        # Auth/TLS material is supplied out of band by the task's credential adapter.
        # No secret reference in the public frozen JSON is treated as executable input.
        return {}, None

    @staticmethod
    def validate_url(endpoint, url):
        parsed = urllib.parse.urlsplit(url)
        # Loopback HTTP is useful to test the real transfer engine without network access.
        local = parsed.hostname in ('127.0.0.1', '::1', 'localhost')
        if (parsed.scheme != 'https' and not (local and parsed.scheme == 'http')
                or parsed.username or parsed.password or parsed.fragment
                or MirrorPolicy.normalized_host(parsed.hostname) not in {
                    MirrorPolicy.normalized_host(host) for host in endpoint.get('allowedHosts', [])}):
            raise MirrorFailure('destination/redirect outside selected source allowlist')
        return url

    @staticmethod
    def validate_redirect(endpoint, original, url):
        try:
            return MirrorPolicy.validate_url(endpoint, url)
        except MirrorFailure:
            pass
        # Extra hosts are redirect-only edges. They never become initial download hosts,
        # and cannot use another distribution redirect to escape the selected source.
        try:
            source, target = urllib.parse.urlsplit(original), urllib.parse.urlsplit(url)
            rules = endpoint.get('redirectRules', [])
            if (endpoint.get('custom') or not isinstance(rules, list) or len(rules) > 16
                    or any(ord(char) <= 32 or ord(char) == 127 for char in original + url)
                    or '#' in original + url or not source.hostname or not target.hostname
                    or source.scheme != 'https' or target.scheme != 'https'
                    or source.port not in (None, 443) or target.port not in (None, 443)
                    or source.netloc.lower() not in (source.hostname, source.hostname + ':443')
                    or target.netloc.lower() not in (target.hostname, target.hostname + ':443')
                    or source.username is not None or source.password is not None or source.fragment
                    or target.username is not None or target.password is not None or target.fragment):
                raise ValueError('invalid redirect edge')
            allowed = {MirrorPolicy.normalized_host(host) for host in endpoint.get('allowedHosts', [])}
            matched = False
            for rule in rules:
                if not isinstance(rule, dict):
                    raise ValueError('invalid redirect rule')
                start, end, prefix = (rule.get(key) for key in ('fromHost', 'toHost', 'pathPrefix'))
                host_pattern = r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*'
                if (any(not isinstance(host, str) or len(host) > 253
                        or not re.fullmatch(host_pattern, host, re.I | re.ASCII) for host in (start, end))
                        or start.lower() not in allowed
                        or not isinstance(prefix, str) or len(prefix) > 1024 or prefix == '/'
                        or not prefix.endswith('/') or not re.fullmatch(r"[A-Za-z0-9._~!$&'()*+,;=:@/-]+", prefix)
                        or MirrorPolicy.redirect_path(prefix) != prefix):
                    raise ValueError('invalid redirect rule boundary')
                if (source.hostname == start.lower() and target.hostname == end.lower()
                        and target.path.startswith(prefix)
                        and MirrorPolicy.redirect_path(target.path).startswith(prefix)):
                    matched = True
            if matched:
                return url  # Preserve signed query bytes without logging them.
        except (ValueError, UnicodeError):
            pass
        raise MirrorFailure('destination/redirect outside selected source allowlist')

    @staticmethod
    def redirect_path(path):
        decoded = urllib.parse.unquote(path, errors='strict')
        if (not path.startswith('/') or '//' in decoded or '\\' in decoded or '%' in decoded
                or any(ord(char) <= 32 or ord(char) == 127 for char in decoded)
                or decoded.count('/') != path.count('/')
                or any(part in ('.', '..') for part in decoded.split('/'))):
            raise ValueError('redirect path is not canonical')
        return decoded

    @staticmethod
    def normalized_host(value):
        if not isinstance(value, str):
            return ''
        if value.startswith('[') and value.endswith(']'):
            value = value[1:-1]
        address, separator, scope = value.partition('%')
        return address.lower() + (separator + scope if separator else '')

    @staticmethod
    def maps(endpoint, url):
        candidates = sorted(endpoint.get('routes', []), key=lambda item: len(item['origin']), reverse=True)
        for route in candidates:
            if route.get('pattern'):
                match = re.fullmatch(route['pattern'], url)
                if match:
                    return match.expand(route['replacement'])
                continue
            origin = route['origin']
            if url.startswith(origin):
                return route['prefix'] + url[len(origin):]
        # Already-selected metadata URLs may point at an approved artifact/CDN host.
        if endpoint.get('official') or endpoint.get('custom'):
            base = endpoint.get('baseUrl', '').rstrip('/')
            if base and (url == base or url.startswith(base + '/')):
                return url
            for origin in endpoint.get('sourceOrigins', []):
                if endpoint.get('official') and url.startswith(origin):
                    return url
        raise MirrorFailure('URL has no exact compatible route for selected source')


class MirrorRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, endpoint, policy=None):
        self.endpoint, self.policy = endpoint, policy

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # A custom endpoint cannot redirect into the public fallback behind the policy's back.
        MirrorPolicy.validate_redirect(self.endpoint, req.full_url, newurl)
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None:
            for name in list(getattr(req, 'wtl_bound_headers', [])) + ['Authorization', 'Cookie']:
                redirected.remove_header(name.capitalize())
            bound, _ = self.policy.transport_options(self.endpoint, newurl) if self.policy else ({}, None)
            for name, value in bound.items():
                redirected.add_header(name, value)
            redirected.wtl_bound_headers = list(bound)
        return redirected


class MirrorOciAuthentication(urllib.request.BaseHandler):
    def __init__(self, policy, endpoint):
        self.policy, self.endpoint = policy, endpoint

    def http_error_401(self, req, fp, code, message, headers):
        if (req.get_header('Authorization') or '').startswith('Bearer '):
            return None
        challenge = headers.get('WWW-Authenticate', '')
        if not challenge.lower().startswith('bearer '):
            raise MirrorFailure('unsupported OCI registry authentication challenge')
        fields = dict(re.findall(r'([a-z]+)="([^"]*)"', challenge[7:]))
        realm = self.policy.validate_url(self.endpoint, fields.get('realm', ''))
        query = urllib.parse.urlencode({k: fields[k] for k in ('service', 'scope') if k in fields})
        policy = MirrorPolicy(dict(self.policy.document, points={'OCI_REGISTRY': [self.endpoint]}), self.policy.deadline,
                              self.policy.transfer_ledger)
        policy.transport_options = self.policy.transport_options
        payload, _ = mirror_bytes(policy, 'OCI_REGISTRY', realm + ('&' if '?' in realm else '?') + query, limit=1048576)
        token_data = json.loads(payload)
        token = token_data.get('token') or token_data.get('access_token')
        if not isinstance(token, str) or not token or len(token) > 1048576:
            raise MirrorFailure('invalid OCI registry token')
        fp.close()
        req.add_header('Authorization', 'Bearer ' + token)
        return self.parent.open(req, timeout=min(self.policy.connect, self.policy.remaining()))


def mirror_transfer(policy, point, original, target, algorithm=None, expected=None, limit=2147483648, headers=None, oci_auth=False, validator=None):
    """Serialize an object and retain exhausted sources across native client retries."""
    if bool(algorithm) != bool(expected):
        raise MirrorFailure('digest algorithm and expected value must be supplied together')
    if algorithm not in (None, 'sha256', 'sha512'):
        raise MirrorFailure('unsupported digest algorithm')
    if expected and not re.fullmatch('[a-fA-F0-9]{%d}' % (hashlib.new(algorithm).digest_size * 2), expected):
        raise MirrorFailure('invalid expected digest')
    record = policy.transfer_ledger.object((point, original))
    if record.get('owner') == threading.get_ident():
        raise MirrorFailure('cyclic validation request for the same repository object')
    if not record['lock'].acquire(timeout=policy.remaining()):
        raise MirrorFailure('task deadline while waiting for an in-flight object')
    record['owner'] = threading.get_ident()
    try:
        if expected:
            identity = algorithm, expected.lower()
            if record.get('expected') and record['expected'] != identity:
                raise MirrorFailure('resolved object checksum changed during the same operation')
            record['expected'] = identity
        elif record.get('expected'):
            algorithm, expected = record['expected']
        representation = (headers or {}).get('Accept', '')
        cache_key, cached = None, None
        for endpoint in policy.endpoints(point):
            candidate = hashlib.sha256(json.dumps(endpoint, sort_keys=True, separators=(',', ':')).encode()).hexdigest(), representation
            if endpoint['providerId'] not in record['exhausted'] and candidate in record['cache']:
                cache_key, cached = candidate, record['cache'][candidate]
                break
        if cached:
            path, metadata = cached
            if metadata['bytes'] > limit:
                raise MirrorFailure('cached object exceeds request size limit')
            try:
                if expected:
                    digest = hashlib.new(algorithm)
                    with path.open('rb') as source:
                        for chunk in iter(lambda: source.read(65536), b''):
                            digest.update(chunk)
                    if digest.hexdigest() != expected.lower():
                        raise MirrorFailure('cached object does not match the resolved checksum')
                if validator:
                    validator(path, {'Content-Type': metadata['contentType']})
            except (MirrorFailure, ValueError, UnicodeError):
                record['exhausted'].add(metadata['source'])
                record['cache'].pop(cache_key)
                path.unlink(missing_ok=True)
            else:
                target = pathlib.Path(target)
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.is_symlink():
                    raise MirrorFailure('transfer output must not be a symbolic link')
                descriptor, temporary = tempfile.mkstemp(prefix='.wtl-cache-', dir=target.parent)
                os.close(descriptor)
                try:
                    shutil.copyfile(path, temporary)
                    os.replace(temporary, target)
                finally:
                    pathlib.Path(temporary).unlink(missing_ok=True)
                return dict(metadata)
        metadata = _mirror_transfer(policy, point, original, target, algorithm, expected, limit, headers, oci_auth, validator, record)
        cached_path = policy.transfer_ledger.cache_path()
        shutil.copyfile(target, cached_path)
        record['cache'][(record['producer'], representation)] = cached_path, dict(metadata)
        return metadata
    finally:
        record.pop('owner', None)
        record['lock'].release()


def _mirror_transfer(policy, point, original, target, algorithm=None, expected=None, limit=2147483648, headers=None,
                     oci_auth=False, validator=None, record=None):
    """Commit output only after a complete transfer and the caller's unchanged digest check."""
    target = pathlib.Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_symlink():
        raise MirrorFailure('transfer output must not be a symbolic link')
    failures = []
    for endpoint in policy.endpoints(point):
        provider = endpoint['providerId']
        if provider in record['exhausted']:
            failures.append('source-previously-exhausted')
            continue
        try:
            selected = policy.validate_url(endpoint, policy.maps(endpoint, original))
        except MirrorFailure as error:
            mirror_log(point, endpoint, 'incompatible-route')
            failures.append(str(error))
            continue
        while record['attempts'].get(provider, 0) < policy.attempts:
            policy.remaining()
            attempt = record['attempts'].get(provider, 0) + 1
            record['attempts'][provider] = attempt
            descriptor, temporary = tempfile.mkstemp(prefix='.wtl-transfer-', dir=target.parent)
            progress = {'time': time.monotonic(), 'bytes': 0, 'start': time.monotonic(), 'failure': None,
                        'connected': False, 'connectStart': time.monotonic()}
            active = {'socket': None}
            done = threading.Event()
            response = None
            watchdog = None
            try:
                def watch():
                    while not done.wait(min(.1, policy.idle / 4)):
                        now = time.monotonic()
                        reason = None
                        if policy.deadline is not None and now >= policy.deadline:
                            reason = 'task-deadline'
                        elif not progress['connected'] and now - progress['connectStart'] >= policy.connect:
                            reason = 'connect-timeout'
                        elif now - progress['time'] >= policy.idle:
                            reason = 'no-progress'
                        elif now - progress['start'] >= policy.window:
                            if progress['bytes'] / (now - progress['start']) < policy.minimum:
                                reason = 'low-speed'
                            progress['start'], progress['bytes'] = now, 0
                        if reason:
                            progress['failure'] = reason
                            connection = active['socket']
                            if connection is not None:
                                try:
                                    connection.shutdown(socket.SHUT_RDWR)
                                except OSError:
                                    pass
                            return

                def bounded_connection(base):
                    class Connection(base):
                        def __init__(self, *args, **kwargs):
                            super().__init__(*args, **kwargs)
                            self._create_connection = self.create_connection

                        def create_connection(self, address, timeout=None, source_address=None):
                            resolved, resolution = threading.Event(), {}
                            def resolve():
                                try:
                                    resolution['addresses'] = socket.getaddrinfo(address[0], address[1], 0, socket.SOCK_STREAM)
                                except OSError as error:
                                    resolution['error'] = error
                                finally:
                                    resolved.set()
                            threading.Thread(target=resolve, daemon=True).start()
                            until = min(progress['connectStart'] + policy.connect, time.monotonic() + policy.remaining())
                            if not resolved.wait(max(.001, until - time.monotonic())):
                                raise MirrorFailure('connect-timeout')
                            if 'error' in resolution:
                                raise resolution['error']
                            failure = None
                            for family, kind, protocol, _, selected_address in resolution['addresses']:
                                connection = socket.socket(family, kind, protocol)
                                active['socket'] = connection
                                try:
                                    remaining = until - time.monotonic()
                                    if remaining <= 0:
                                        raise MirrorFailure('connect-timeout')
                                    connection.settimeout(remaining)
                                    if source_address:
                                        connection.bind(source_address)
                                    connection.connect(selected_address)
                                    return connection
                                except (OSError, MirrorFailure) as error:
                                    failure = error
                                    connection.close()
                            if failure:
                                raise failure
                            raise MirrorFailure('DNS returned no usable address')

                        def connect(self):
                            progress['connected'], progress['connectStart'] = False, time.monotonic()
                            if isinstance(self, http.client.HTTPSConnection):
                                http.client.HTTPConnection.connect(self)
                                self.sock = self._context.wrap_socket(self.sock, server_hostname=self.host,
                                                                      do_handshake_on_connect=False)
                                active['socket'] = self.sock
                                self.sock.settimeout(min(policy.connect, policy.remaining()))
                                self.sock.do_handshake()
                            else:
                                super().connect()
                            active['socket'] = self.sock
                            progress['connected'], progress['time'] = True, time.monotonic()
                            self.sock.settimeout(min(policy.idle, policy.remaining()))
                    return Connection

                class Http(urllib.request.HTTPHandler):
                    def http_open(self, req):
                        return self.do_open(bounded_connection(http.client.HTTPConnection), req)

                class Https(urllib.request.HTTPSHandler):
                    def https_open(self, req):
                        _, context = policy.transport_options(endpoint, req.full_url)
                        return self.do_open(bounded_connection(http.client.HTTPSConnection), req,
                                            context=context or self._context)

                watchdog = threading.Thread(target=watch, daemon=True)
                watchdog.start()
                request_headers = {'User-Agent': 'WindowsToLinux-mirrors/1', 'Accept-Encoding': 'identity'}
                request_headers.update(headers or {})
                bound_headers, _ = policy.transport_options(endpoint, selected)
                request_headers.update(bound_headers)
                request = urllib.request.Request(selected, headers=request_headers)
                request.wtl_bound_headers = list(bound_headers)
                handlers = [urllib.request.ProxyHandler({}), Http(), Https(), MirrorRedirect(endpoint, policy)]
                if oci_auth:
                    handlers.append(MirrorOciAuthentication(policy, endpoint))
                opener = urllib.request.build_opener(*handlers)
                response = opener.open(request, timeout=min(policy.connect, policy.remaining()))
                content_length = response.headers.get('Content-Length')
                if content_length and int(content_length) > limit:
                    raise MirrorFailure('response-size-limit')
                digest = hashlib.new(algorithm) if algorithm else None
                sha256, total = hashlib.sha256(), 0
                with os.fdopen(descriptor, 'wb') as output:
                    descriptor = None
                    while True:
                        block = response.read1(65536)
                        if progress['failure']:
                            raise MirrorFailure(progress['failure'])
                        if not block:
                            break
                        policy.remaining()
                        total += len(block)
                        if total > limit:
                            raise MirrorFailure('response-size-limit')
                        progress['bytes'] += len(block)
                        progress['time'] = time.monotonic()
                        if digest:
                            digest.update(block)
                        sha256.update(block)
                        output.write(block)
                    if content_length and total != int(content_length):
                        raise MirrorFailure('truncated-response')
                    if digest and digest.hexdigest().lower() != expected.lower():
                        raise MirrorFailure('digest-mismatch')
                if validator is not None:
                    try:
                        validator(pathlib.Path(temporary), response.headers)
                    except (ValueError, UnicodeError):
                        raise MirrorFailure('invalid-protocol-metadata') from None
                os.replace(temporary, target)
                mirror_log(point, endpoint, 'complete', attempt)
                record['producer'] = hashlib.sha256(json.dumps(endpoint, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
                return {'sha256': sha256.hexdigest(), 'source': endpoint['providerId'],
                        'contentType': response.headers.get('Content-Type', ''), 'bytes': total}
            except (OSError, ValueError, MirrorFailure, urllib.error.URLError, http.client.HTTPException) as error:
                reason = progress['failure'] or ('http-' + str(error.code) if isinstance(error, urllib.error.HTTPError)
                                                 else str(error) if isinstance(error, MirrorFailure)
                                                 else type(error).__name__)
                mirror_log(point, endpoint, reason, attempt)
                failures.append(reason)
                # A deterministic 4xx or corrupted object does not merit another identical request.
                if isinstance(error, urllib.error.HTTPError) and 400 <= error.code < 500 or reason == 'digest-mismatch':
                    record['exhausted'].add(provider)
                    break
            finally:
                done.set()
                if response is not None:
                    response.close()
                if watchdog is not None:
                    watchdog.join(timeout=1)
                if descriptor is not None:
                    os.close(descriptor)
                if os.path.exists(temporary):
                    os.unlink(temporary)
        record['exhausted'].add(provider)
    raise MirrorFailure('selected sources exhausted: ' + ','.join(failures[-6:]))


def mirror_bytes(policy, point, url, limit=33554432, headers=None, oci_auth=False, expected=None, validator=None):
    with tempfile.TemporaryDirectory(prefix='wtl-metadata-') as work:
        output = pathlib.Path(work) / 'response'
        result = mirror_transfer(policy, point, url, output, 'sha256' if expected else None, expected,
                                 limit=limit, headers=headers, oci_auth=oci_auth, validator=validator)
        return output.read_bytes(), result
