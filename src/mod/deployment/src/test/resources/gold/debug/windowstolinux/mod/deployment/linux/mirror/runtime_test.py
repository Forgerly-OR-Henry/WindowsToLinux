"""Real loopback transfers: no SSH, public mirror, AI endpoint or package installation."""
import collections
import concurrent.futures
import base64
import contextlib
import hashlib
import http.server
import io
import json
import os
import pathlib
import shlex
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.request
import zipfile

RUNTIME = {'__name__': 'wtl_runtime_test'}
exec(compile(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'), sys.argv[1], 'exec'), RUNTIME)
Policy, Failure = RUNTIME['MirrorPolicy'], RUNTIME['MirrorFailure']
transfer = RUNTIME['mirror_transfer']
PAYLOAD = b'locked-archive-content' * 2048
SHA = hashlib.sha256(PAYLOAD).hexdigest()
INTEGRITY = 'sha512-' + base64.b64encode(hashlib.sha512(PAYLOAD).digest()).decode()
COUNTS = collections.Counter()
SEEN_HEADERS = {}
CONFIG = b'{"architecture":"amd64","os":"linux","rootfs":{"type":"layers","diff_ids":[]}}'
MANIFEST = json.dumps({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                       'config': {'digest': 'sha256:' + hashlib.sha256(CONFIG).hexdigest()}, 'layers': []}).encode()


def maven_archive():
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as archive:
        item = zipfile.ZipInfo('apache-maven-3.9.9/bin/mvn')
        item.external_attr = 0o100755 << 16
        archive.writestr(item, b'#!/bin/sh\nexit 0\n')
    return stream.getvalue()


class Handler(http.server.BaseHTTPRequestHandler):
    def finish(self):
        super().finish()
        if os.name == 'nt':
            time.sleep(.05)

    def log_message(self, *args):
        pass

    def do_GET(self):
        COUNTS[self.path] += 1
        SEEN_HEADERS[self.path] = dict(self.headers)
        if self.path.startswith('/missing/'):
            self.send_error(404)
            return
        if self.path.startswith('/redirect/'):
            self.send_response(302)
            self.send_header('Location', self.server.root + '/good/file')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if self.path.startswith('/escape/'):
            self.send_response(302)
            self.send_header('Location', 'http://127.0.0.2:' + str(self.server.server_port) + '/good/file')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if self.path.startswith('/headers/'):
            self.wfile.write(b'HTTP/1.0 200 OK\r\nX-Slow: ')
            self.wfile.flush()
            for _ in range(50):
                try:
                    self.wfile.write(b'x')
                    self.wfile.flush()
                except OSError:
                    break
                time.sleep(.03)
            return
        data, content = PAYLOAD, 'application/octet-stream'
        if self.path.startswith('/corrupt/'):
            data = b'corrupted-object'
        elif self.path.startswith('/invalid-metadata/'):
            data, content = b'<html>mirror service unavailable</html>', 'text/html'
        elif self.path.startswith(('/apt-missing/', '/apt-good/')):
            if self.path.endswith('/index.json'):
                data, content = json.dumps({'versions': ['0-1'] if self.path.startswith('/apt-missing/') else ['1-1']}).encode(), 'application/json'
            elif self.path.startswith('/apt-missing/'):
                self.send_error(404)
                return
        elif self.path.startswith(('/node-bad/', '/node-good/')):
            if self.path.endswith('/index.json'):
                version = 'v20.9.0' if self.path.startswith('/node-bad/') else 'v20.10.0'
                data, content = json.dumps([{'version': version}]).encode(), 'application/json'
            elif self.path.startswith('/node-good/'):
                data = (SHA + '  node-v20.10.0-linux-x64.tar.gz\n').encode()
        elif self.path.startswith('/maven/'):
            data = maven_archive()
            if self.path.endswith('.sha512'):
                data = hashlib.sha512(data).hexdigest().encode()
        elif self.path.startswith('/npm') and self.path.endswith('/package'):
            data = json.dumps({'name': 'package', 'dist': {'tarball': 'https://origin.invalid/archive.tgz',
                                                         'integrity': INTEGRITY}}).encode()
            content = 'application/json'
        elif self.path.startswith('/npm-corrupt/'):
            data = b'corrupted npm archive'
        elif self.path == '/nuget/index.json':
            data, content = b'{"version":"3.0.0","resources":[]}', 'application/json'
        elif self.path.startswith('/oci/v2/') and '/blobs/' in self.path:
            data, content = CONFIG, 'application/octet-stream'
        elif self.path.startswith('/oci/v2/'):
            data, content = MANIFEST, 'application/vnd.oci.image.manifest.v1+json'
        elif self.path.startswith('/wrapper/'):
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w') as archive:
                item = zipfile.ZipInfo('gradle-1/bin/gradle')
                item.external_attr = 0o100755 << 16
                archive.writestr(item, b'#!/bin/sh\nexit 0\n')
            data = stream.getvalue()
        self.send_response(200)
        self.send_header('Content-Type', content)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        if self.path.startswith('/truncated/'):
            self.wfile.write(data[:20])
            self.close_connection = True
        elif self.path.startswith(('/slow/', '/idle/')):
            for _ in range(100):
                try:
                    self.wfile.write(b'x')
                    self.wfile.flush()
                except OSError:
                    break
                time.sleep(.03 if self.path.startswith('/slow/') else .3)
        else:
            self.wfile.write(data)


class PrivilegedRuntimeTests(unittest.TestCase):
    def test_dnf_child_executes_only_the_bytes_matching_the_pinned_runtime(self):
        payload = b'import sys; print(repr(sys.argv))'
        pinned = hashlib.sha256(payload).hexdigest()
        policy = Policy({'formatVersion': 1, 'points': {}})
        with unittest.mock.patch.dict(RUNTIME, {'MIRROR_RUNTIME_SHA256': pinned}):
            command = RUNTIME['mirror_system_command'](policy, 'dnf', ['install', 'demo'])
        self.assertEqual(command[:2], ['/bin/bash', '-pc'])
        self.assertEqual(command[3:], ['wtl-system-package', 'install', 'demo'])
        self.assertIn('wtl_mirror_runtime_sha256=' + pinned, command[2])
        loader = command[2].partition(" -I -c '\n")[2].partition("\n' ")[0]
        self.assertTrue(loader)
        path = '/tmp/wtl-mirrors.fixture/runtime.py'
        expected_argv = [path, 'system-dnf', 'install', 'demo']
        for body, mode, accepted in ((payload, 0o400, True), (payload + b'\n', 0o400, False), (payload, 0o600, False)):
            with self.subTest(mode=mode, accepted=accepted), contextlib.ExitStack() as stack:
                stream = io.BytesIO(body)
                stream.fileno = lambda: 83
                stack.enter_context(unittest.mock.patch.object(os, 'fdopen', return_value=stream))
                descriptor = stack.enter_context(unittest.mock.patch.object(os, 'open', return_value=83))
                stack.enter_context(unittest.mock.patch.object(os, 'O_NOFOLLOW', 1 << 23, create=True))
                stack.enter_context(unittest.mock.patch.object(os, 'O_NONBLOCK', 1 << 24, create=True))
                identity = unittest.mock.Mock(st_mode=stat.S_IFREG | mode, st_uid=0, st_nlink=1)
                stack.enter_context(unittest.mock.patch.object(os, 'fstat', return_value=identity))
                stack.enter_context(unittest.mock.patch.object(sys, 'argv', ['-c', path, pinned] + expected_argv[1:]))
                output, errors = io.StringIO(), io.StringIO()
                stack.enter_context(contextlib.redirect_stdout(output))
                stack.enter_context(contextlib.redirect_stderr(errors))
                if accepted:
                    exec(loader, {})
                    self.assertEqual(output.getvalue().strip(), repr(expected_argv))
                    self.assertEqual(errors.getvalue(), '')
                else:
                    with self.assertRaises(SystemExit) as rejected:
                        exec(loader, {})
                    self.assertEqual(rejected.exception.code, 65)
                    self.assertEqual(output.getvalue(), '')
                    self.assertEqual(errors.getvalue(), 'MIRROR_REJECT=privileged-runtime-integrity\n')
                descriptor.assert_called_once_with(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)


class RuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.server.daemon_threads = True
        cls.server.root = 'http://127.0.0.1:' + str(cls.server.server_port)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ['TEMP'])
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / 'artifact'
        COUNTS.clear()
        SEEN_HEADERS.clear()

    def endpoint(self, name):
        return {'providerId': name, 'protocol': 'HTTP', 'baseUrl': self.server.root + '/' + name + '/',
                'allowedHosts': ['127.0.0.1'], 'official': False, 'custom': True,
                'sourceOrigins': ['https://origin.invalid/'],
                'routes': [{'origin': 'https://origin.invalid/', 'prefix': self.server.root + '/' + name + '/'}]}

    def policy(self, names, **timeouts):
        return Policy({'formatVersion': 1, 'points': {'NODE_PACKAGE': [self.endpoint(name) for name in names]},
                       'timeouts': timeouts})

    def fetch(self, policy):
        return transfer(policy, 'NODE_PACKAGE', 'https://origin.invalid/file', self.path, 'sha256', SHA)

    def test_404_fallback_preserves_order_and_hash(self):
        result = self.fetch(self.policy(['missing', 'good']))
        self.assertEqual(result['source'], 'good')
        self.assertEqual(self.path.read_bytes(), PAYLOAD)
        self.assertEqual(COUNTS['/missing/file'], 1)
        self.assertEqual(COUNTS['/good/file'], 1)

    def test_recursive_validation_of_same_uri_is_rejected_without_waiting_for_its_own_lock(self):
        policy = self.policy(['good'])
        def recursive(path, headers):
            transfer(policy, 'NODE_PACKAGE', 'https://origin.invalid/cycle', self.path)
        with self.assertRaises(Failure):
            transfer(policy, 'NODE_PACKAGE', 'https://origin.invalid/cycle', self.path, validator=recursive)
        self.assertEqual(2, COUNTS['/good/cycle'])

    def apt_fixture(self, manager='apt-get', error='', scheme='https', ipv6=False, authentication=False, arguments=None):
        endpoints = [self.endpoint('apt-missing'), self.endpoint('apt-good')]
        for endpoint in endpoints:
            selected = scheme + '://' + ('[::1]' if ipv6 else 'apt-fixture.invalid') + '/' + endpoint['providerId'] + '/'
            endpoint.update(baseUrl=selected, allowedHosts=['::1' if ipv6 else 'apt-fixture.invalid'], protocol='APT',
                            routes=[{'origin': 'https://origin.invalid/', 'prefix': selected}])
            if authentication:
                endpoint['secretRef'] = 'fixture:1'
        policy = Policy({'formatVersion': 1, 'points': {'APT_DEBIAN': endpoints}})
        directory = pathlib.Path(self.temp.name)
        log = directory / 'native-apt.jsonl'
        policy_path = directory / 'policy.json'
        policy_path.write_text(json.dumps(policy.document))
        if arguments is None:
            arguments = ['-y', 'install', 'demo=1-1'] if manager == 'apt-get' else ['policy', 'demo']
        command = RUNTIME['mirror_system_command'](policy, manager, arguments)
        entry = '\nwtl_' + ('apt' if manager == 'apt-get' else 'apt_candidate') + ' "$@"\n'
        self.assertTrue(command[2].endswith(entry))
        fixture = pathlib.Path(__file__).with_name('apt_native_fixture.py')
        paths = {'wtl_fixture_python': sys.executable, 'wtl_fixture_script': str(fixture),
                 'wtl_fixture_log': str(log), 'TMPDIR': str(directory), 'WTL_MIRROR_POLICY': str(policy_path),
                 'WTL_MIRROR_CREDENTIALS_DIR': str(directory)}
        overrides = '\n'
        for key, value in paths.items():
            overrides += ('export ' + key + '="$(cygpath -u ' + shlex.quote(value) + ')"\n' if os.name == 'nt'
                          else 'export ' + key + '=' + shlex.quote(value) + '\n')
        overrides += 'wtl_fixture_server=' + shlex.quote(self.server.root) + '\nwtl_fixture_error=' + shlex.quote(error) + '\n'
        overrides += '''
wtl_system_point() { printf APT_DEBIAN; }
wtl_fixture_original_copy="$(declare -f wtl_repository_copy)"
eval "${wtl_fixture_original_copy/wtl_repository_copy/wtl_fixture_repository_copy}"
wtl_repository_copy() { printf '# mock native source\\n' >"$4"; }
wtl_bounded_package_request() { local cache="$1" log="$2"; shift 2; "$@" >"$log" 2>&1; }
wtl_apt_configure_command() {
  printf 'deb [signed-by=/usr/share/keyrings/fixture.gpg] https://origin.invalid/ stable main\\n' >"$folder/source.list"
  wtl_fixture_repository_copy "$point" "$provider" "$folder/source.list" "$1/repos/fixture.list" || return $?
  package_command=("$wtl_fixture_python" "$wtl_fixture_script" native "$provider" "$folder" "$cache" "$wtl_fixture_log" "$wtl_fixture_server" "$wtl_fixture_error" "${wtl_auth_options[@]}" "${wtl_apt_strict_options[@]}")
}
wtl_apt_configure_query() {
  query_command=("$wtl_fixture_python" "$wtl_fixture_script" query "$provider" "$folder" "$cache" "$wtl_fixture_log" "$wtl_fixture_server" "$wtl_fixture_error")
}
wtl_apt_configure_download() {
  helper_command=("$wtl_fixture_python" "$wtl_fixture_script" download "$provider" "$folder" "$cache" "$wtl_fixture_log" "$wtl_fixture_server" "$wtl_fixture_error")
}
'''
        script = directory / 'apt-fixture.sh'
        script.write_text(command[2][:-len(entry)] + overrides + entry, encoding='utf-8')
        command = ['E:/Program/Git/bin/bash.exe' if os.name == 'nt' else '/bin/bash', str(script)] + command[4:]
        global_config = directory / 'global-apt.conf'
        global_config.write_text('DPkg::Pre-Install-Pkgs { "global-hook-must-not-run"; }; Acquire::http::Proxy "http://never-contact.invalid/";')
        environment = dict(os.environ, TEMP=self.temp.name, TMP=self.temp.name, TMPDIR=self.temp.name,
                           PYTHONIOENCODING='utf-8', APT_CONFIG=str(global_config))
        process = subprocess.run(command, cwd=self.temp.name, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25)
        records = [json.loads(line) for line in log.read_text().splitlines()] if log.is_file() else []
        return process, records

    def test_apt_missing_version_advances_metadata_then_freezes_objects_and_installs_once(self):
        process, records = self.apt_fixture()
        self.assertEqual(0, process.returncode, process.stderr.decode(errors='replace'))
        self.assertEqual(['apt-missing', 'apt-good'], [item['source'] for item in records if item['phase'] == 'plan'])
        self.assertEqual(1, len([item for item in records if item['phase'] == 'install']))
        self.assertEqual(1, COUNTS['/apt-good/demo.deb'])
        self.assertEqual(2, COUNTS['/apt-missing/demo.deb'])

    @unittest.skipUnless(len(sys.argv) > 3, 'Requires exact production-generated helper APT arguments')
    def test_native_helper_package_flags_reach_plan_and_final_no_download(self):
        batches = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding='utf-8'))
        self.assertEqual(3, len(batches))
        for arguments in batches:
            with self.subTest(arguments=arguments):
                (pathlib.Path(self.temp.name) / 'native-apt.jsonl').unlink(missing_ok=True)
                process, records = self.apt_fixture(arguments=arguments)
                self.assertEqual(0, process.returncode, process.stderr.decode(errors='replace'))
                plans = [record for record in records if record['phase'] == 'plan']
                installs = [record for record in records if record['phase'] == 'install']
                self.assertEqual(2, len(plans))
                self.assertEqual(1, len(installs))
                for record in plans + installs:
                    self.assertEqual(arguments, record['arguments'][-len(arguments):])
                    for flag in ('--no-upgrade', '--no-remove', '--no-install-recommends'):
                        self.assertIn(flag, record['arguments'])
                self.assertIn('--print-uris', plans[-1]['arguments'])
                self.assertIn('--download-only', plans[-1]['arguments'])
                self.assertIn('--no-download', installs[0]['arguments'])

    def test_apt_permission_error_cannot_be_misclassified_as_missing_candidate(self):
        process, records = self.apt_fixture(error='permission')
        self.assertEqual(100, process.returncode, process.stderr.decode(errors='replace'))
        self.assertTrue(records)
        self.assertEqual({'apt-missing'}, {item['source'] for item in records})
        self.assertFalse(any(item['phase'] in ('download', 'install') for item in records))

    def test_apt_install_failure_after_freezing_does_not_re_resolve_or_reinstall(self):
        process, records = self.apt_fixture(error='post-install-no-match')
        self.assertEqual(100, process.returncode, process.stderr.decode(errors='replace'))
        self.assertEqual(2, len([item for item in records if item['phase'] == 'plan']))
        self.assertEqual(1, len([item for item in records if item['phase'] == 'install']))

    def test_apt_native_candidate_query_uses_next_source_without_installing(self):
        process, records = self.apt_fixture(manager='apt-cache')
        self.assertEqual(0, process.returncode, process.stderr.decode(errors='replace'))
        self.assertIn('Candidate: 1-1', process.stdout.decode())
        self.assertEqual(['apt-missing', 'apt-good'], [item['source'] for item in records if item['phase'] == 'query'])
        self.assertFalse(any(item['phase'] in ('plan', 'download', 'install') for item in records))

    def test_http_ipv6_apt_retains_signed_by_and_uses_only_private_configuration(self):
        process, records = self.apt_fixture(scheme='http', ipv6=True)
        self.assertEqual(0, process.returncode, process.stderr.decode(errors='replace'))
        for record in records:
            self.assertTrue(record['aptConfig'].endswith('/apt.conf'))
            self.assertIn('Dir::Etc::main "/dev/null";', record['aptConfigText'])
            self.assertIn('/pkgcache.bin";', record['aptConfigText'])
            self.assertIn('/srcpkgcache.bin";', record['aptConfigText'])
            self.assertEqual([], record['configurationParts'])
            self.assertNotIn('global-hook', record['aptConfigText'])
            self.assertNotIn('trusted', record['aptConfigText'].lower())
            self.assertIn('signed-by=/usr/share/keyrings/fixture.gpg', record['sourceText'])
            self.assertIn('http://[::1]/', record['sourceText'])
        native = next(record for record in records if record['phase'] == 'plan')
        for strict in ('Acquire::http::AllowRedirect=false', 'APT::Get::AllowUnauthenticated=false', 'Acquire::AllowInsecureRepositories=false'):
            self.assertIn(strict, native['arguments'])

    def test_http_authenticated_apt_is_rejected_before_native_or_network_requests(self):
        process, records = self.apt_fixture(scheme='http', authentication=True)
        self.assertEqual(65, process.returncode)
        self.assertIn('system-authentication-requires-https', process.stderr.decode())
        self.assertEqual([], records)
        self.assertEqual(0, COUNTS['/apt-missing/index.json'])

    def test_child_policy_cannot_reuse_another_endpoints_cached_bytes(self):
        policy = self.policy(['good'])
        self.fetch(policy)
        child = Policy({'formatVersion': 1, 'points': {'NODE_PACKAGE': [self.endpoint('other')]}},
                       transfer_ledger=policy.transfer_ledger)
        self.assertEqual('other', self.fetch(child)['source'])
        self.assertEqual(COUNTS['/good/file'], 1)
        self.assertEqual(COUNTS['/other/file'], 1)

    def test_hash_corruption_tries_next_source_same_expected(self):
        self.fetch(self.policy(['corrupt', 'good']))
        self.assertEqual(COUNTS['/corrupt/file'], 1)
        self.assertEqual(self.path.read_bytes(), PAYLOAD)

    def test_truncated_response_has_at_most_two_attempts(self):
        self.fetch(self.policy(['truncated', 'good']))
        self.assertEqual(COUNTS['/truncated/file'], 2)
        self.assertFalse(list(self.path.parent.glob('.wtl-transfer-*')))

    def test_stricter_frozen_attempt_limit_is_respected(self):
        self.fetch(self.policy(['truncated', 'good'], attemptsPerEndpoint=1))
        self.assertEqual(COUNTS['/truncated/file'], 1)
        self.assertEqual(COUNTS['/good/file'], 1)

    def test_native_repeated_and_concurrent_requests_do_not_reset_source_budget(self):
        policy = self.policy(['truncated'])
        with RUNTIME['RepositoryGateway'](policy, self.temp.name) as gateway:
            def failed_request(_):
                try:
                    urllib.request.urlopen(gateway.root('NODE_PACKAGE') + 'file').close()
                except urllib.error.HTTPError as error:
                    return error.code
                return 200
            with concurrent.futures.ThreadPoolExecutor(max_workers=6) as workers:
                self.assertEqual([502] * 6, list(workers.map(failed_request, range(6))))
            self.assertEqual(502, failed_request(0))
        self.assertEqual(COUNTS['/truncated/file'], 2)
        frozen = pathlib.Path(self.temp.name) / 'policy.json'
        raw = json.dumps(policy.document).encode()
        frozen.write_bytes(raw)
        with unittest.mock.patch.dict(os.environ, {'WTL_MIRROR_POLICY': str(frozen),
                'WTL_MIRROR_POLICY_SHA256': hashlib.sha256(raw).hexdigest()}):
            for _ in range(2):
                with self.assertRaises(Failure):
                    transfer(Policy.from_environment(), 'NODE_PACKAGE', 'https://origin.invalid/reloaded', self.path)
        self.assertEqual(COUNTS['/truncated/reloaded'], 2)
        policy = self.policy(['good'])
        with RUNTIME['RepositoryGateway'](policy, self.temp.name) as gateway:
            for _ in range(3):
                with urllib.request.urlopen(gateway.root('NODE_PACKAGE') + 'file') as response:
                    self.assertEqual(PAYLOAD, response.read())
        self.assertEqual(COUNTS['/good/file'], 1)

    def test_linux_loopback_checks_client_uid_not_server_uid(self):
        local, peer = ('127.0.0.1', 35000), ('127.0.0.1', 45000)
        address = '%08X' % RUNTIME['struct'].unpack('=I', RUNTIME['socket'].inet_aton('127.0.0.1'))[0]
        def row(source, target, uid):
            return '0: %s:%04X %s:%04X 01 00000000:00000000 00:00000000 00000000 %d 0 1' % (address, source, address, target, uid)
        table = row(local[1], peer[1], 1000) + '\n' + row(peer[1], local[1], 2000)
        self.assertEqual(RUNTIME['mirror_loopback_peer_uid'](local, peer, table), 2000)
        with unittest.mock.patch.object(RUNTIME['sys'], 'platform', 'linux'), \
             unittest.mock.patch.object(RUNTIME['os'], 'geteuid', return_value=1000, create=True):
            self.assertFalse(RUNTIME['mirror_loopback_client_allowed'](local, peer, True, table))
            self.assertTrue(RUNTIME['mirror_loopback_client_allowed'](local, peer, True, row(peer[1], local[1], 1000)))

    def test_authenticated_loopback_rejects_unavailable_or_ambiguous_kernel_proof(self):
        local, peer = ('127.0.0.1', 35000), ('127.0.0.1', 45000)
        with unittest.mock.patch.object(RUNTIME['sys'], 'platform', 'linux'), \
             unittest.mock.patch.object(RUNTIME['os'], 'geteuid', return_value=1000, create=True), \
             unittest.mock.patch('builtins.open', side_effect=OSError('fixture proc unavailable')):
            self.assertFalse(RUNTIME['mirror_loopback_client_allowed'](local, peer, True))
        with self.assertRaises(Failure):
            RUNTIME['mirror_loopback_peer_uid'](local, peer, 'not a kernel row')

    def test_legacy_bracketed_allowlist_is_compared_without_changing_the_url(self):
        endpoint = self.endpoint('good')
        endpoint['allowedHosts'] = ['[::1]', 'EXAMPLE.INVALID', '[FE80::1%Eth0]']
        for url in ('https://[::1]/file', 'https://example.invalid/file', 'https://[fe80::1%Eth0]/file'):
            self.assertEqual(Policy.validate_url(endpoint, url), url)
        with self.assertRaises(Failure):
            Policy.validate_url(endpoint, 'https://[fe80::1%eth0]/file')

    def test_redirect_allowed_and_outside_host_rejected(self):
        self.fetch(self.policy(['redirect']))
        self.path.unlink()
        self.fetch(self.policy(['escape', 'good']))
        self.assertEqual(COUNTS['/escape/file'], 2)
        self.assertEqual(COUNTS['/good/file'], 2)

    def test_custom_single_source_never_adds_public_fallback(self):
        with self.assertRaises(Failure):
            self.fetch(self.policy(['missing']))
        self.assertEqual(COUNTS, {'/missing/file': 1})
        self.assertFalse(self.path.exists())

    def test_no_progress_and_low_speed_are_bounded(self):
        start = time.monotonic()
        self.fetch(self.policy(['idle', 'good'], noProgressSeconds=.12))
        self.assertLess(time.monotonic() - start, 1.5)
        self.assertEqual(COUNTS['/idle/file'], 2)
        self.path.unlink()
        start = time.monotonic()
        self.fetch(self.policy(['slow', 'good'], noProgressSeconds=.5, lowSpeedSeconds=.12))
        self.assertLess(time.monotonic() - start, 1.5)
        self.assertEqual(COUNTS['/slow/file'], 2)

    def test_slow_headers_cannot_bypass_watchdog(self):
        start = time.monotonic()
        self.fetch(self.policy(['headers', 'good'], noProgressSeconds=.12))
        self.assertLess(time.monotonic() - start, 1.5)

    def test_task_deadline_is_stricter_than_source_retry(self):
        policy = self.policy(['idle', 'good'], connectSeconds=15, readSeconds=90, noProgressSeconds=45,
                             lowSpeedWindowSeconds=90, lowSpeedBytesPerSecond=32768)
        self.assertEqual((15, 45, 90, 32768), (policy.connect, policy.idle, policy.window, policy.minimum))
        policy.deadline = time.monotonic() + .12
        started = time.monotonic()
        with self.assertRaises(Failure):
            self.fetch(policy)
        self.assertLess(time.monotonic() - started, 2)
        self.assertEqual(COUNTS['/idle/file'], 1)
        self.assertEqual(COUNTS['/good/file'], 0)

    def test_dns_resolution_is_inside_connect_budget(self):
        original = RUNTIME['socket'].getaddrinfo
        def slow_dns(*args, **kwargs):
            time.sleep(.5)
            return original(*args, **kwargs)
        start = time.monotonic()
        with unittest.mock.patch.object(RUNTIME['socket'], 'getaddrinfo', slow_dns):
            with self.assertRaises(Failure):
                self.fetch(self.policy(['good'], connectSeconds=.05))
        self.assertLess(time.monotonic() - start, .4)
        self.assertFalse(COUNTS)

    def test_redirect_removes_url_bound_credentials_before_rebinding(self):
        policy = self.policy(['redirect'])
        policy.transport_options = lambda endpoint, url: ({'Authorization': 'Bearer TEST_VALUE', 'X-Api-Key': 'TEST_KEY'}, None) \
            if '/redirect/' in url else ({}, None)
        self.fetch(policy)
        self.assertEqual(SEEN_HEADERS['/redirect/file'].get('Authorization'), 'Bearer TEST_VALUE')
        self.assertNotIn('Authorization', SEEN_HEADERS['/good/file'])
        self.assertFalse(any(name.lower() == 'x-api-key' for name in SEEN_HEADERS['/good/file']))

    def test_gateway_rewrites_npm_metadata_but_preserves_integrity_and_bytes(self):
        policy = self.policy(['npm'])
        with RUNTIME['RepositoryGateway'](policy, self.temp.name) as gateway:
            with urllib.request.urlopen(gateway.root('NODE_PACKAGE') + 'package') as response:
                document = json.load(response)
            self.assertEqual(document['dist']['integrity'], INTEGRITY)
            with urllib.request.urlopen(document['dist']['tarball']) as response:
                self.assertEqual(response.read(), PAYLOAD)

    def test_real_dependency_child_uses_gateway_then_exits(self):
        policy = self.policy(['npm'])
        command = [sys.executable, '-c', 'import os,urllib.request,json; '
                   'd=json.load(urllib.request.urlopen(os.environ["npm_config_registry"]+"package")); '
                   'assert d["dist"]["integrity"]==' + repr(INTEGRITY)]
        self.assertEqual(RUNTIME['mirror_dependency'](policy, 'NODE_PACKAGE', command), 0)

    def test_npm_archive_hash_failure_switches_only_current_object(self):
        policy = self.policy(['npm-corrupt', 'npm-good'])
        with RUNTIME['RepositoryGateway'](policy, self.temp.name) as gateway:
            with urllib.request.urlopen(gateway.root('NODE_PACKAGE') + 'package') as response:
                document = json.load(response)
            with urllib.request.urlopen(document['dist']['tarball']) as response:
                self.assertEqual(response.read(), PAYLOAD)
        self.assertEqual(COUNTS['/npm-corrupt/package'], 1)
        self.assertEqual(COUNTS['/npm-good/package'], 0)
        self.assertEqual(COUNTS['/npm-corrupt/archive.tgz'], 1)
        self.assertEqual(COUNTS['/npm-good/archive.tgz'], 1)

    def test_nuget_keeps_original_validation_configuration(self):
        endpoint = self.endpoint('nuget')
        endpoint['routes'].insert(0, {'origin': 'https://origin.invalid/index.json', 'prefix': self.server.root + '/nuget/index.json'})
        policy = Policy({'formatVersion': 1, 'points': {'NUGET': [endpoint]}})
        config = pathlib.Path(self.temp.name) / 'NuGet.Config'
        original = '<configuration><config><add key="signatureValidationMode" value="require"/></config><trustedSigners/></configuration>'
        config.write_text(original)
        with RUNTIME['RepositoryGateway'](policy, self.temp.name) as gateway:
            with self.assertRaises(Failure):
                RUNTIME['dependency_environment'](policy, gateway, 'NUGET', ['dotnet', 'restore'], self.temp.name)
        self.assertEqual(original, config.read_text())

    def test_fixed_lock_url_is_rejected_without_modification(self):
        lock = pathlib.Path(self.temp.name) / 'yarn.lock'
        content = 'resolved "https://origin.invalid/archive.tgz"\nintegrity sha512-fixed\n'
        lock.write_text(content)
        with self.assertRaises(Failure):
            RUNTIME['reject_unmapped_lock_urls'](self.policy(['good']), 'NODE_PACKAGE', self.temp.name)
        self.assertEqual(lock.read_text(), content)

    def test_wrapper_uses_pinned_distribution_and_does_not_change_properties(self):
        endpoint = self.endpoint('wrapper')
        with urllib.request.urlopen(self.server.root + '/wrapper/gradle.zip') as response:
            digest = hashlib.sha256(response.read()).hexdigest()
        properties = pathlib.Path(self.temp.name) / 'gradle-wrapper.properties'
        content = 'distributionUrl=https\\://origin.invalid/gradle.zip\ndistributionSha256Sum=' + digest + '\n'
        properties.write_text(content)
        policy = Policy({'formatVersion': 1, 'points': {'GRADLE_DISTRIBUTION': [endpoint]}})
        RUNTIME['mirror_wrapper'](policy, 'GRADLE_DISTRIBUTION', properties, str(properties.parent / 'wrapper'))
        self.assertTrue((properties.parent / 'wrapper/gradle-1/bin/gradle').is_file())
        self.assertEqual(properties.read_text(), content)

    def test_metadata_validation_switches_before_freezing_version_or_artifact_identity(self):
        bad, good = self.endpoint('invalid-metadata'), self.endpoint('oci')
        policy = Policy({'formatVersion': 1, 'points': {'OCI_REGISTRY': [bad, good]}})
        document, identity = RUNTIME['oci_manifest'](policy, 'https://origin.invalid/v2/base/manifests/tag')
        self.assertEqual(2, document['schemaVersion'])
        self.assertEqual('sha256:' + hashlib.sha256(MANIFEST).hexdigest(), identity)
        self.assertEqual(2, COUNTS['/invalid-metadata/v2/base/manifests/tag'])
        properties = pathlib.Path(self.temp.name) / 'maven-wrapper.properties'
        original = 'distributionUrl=https\\://origin.invalid/apache-maven-3.9.9-bin.zip\n'
        properties.write_text(original)
        policy = Policy({'formatVersion': 1, 'points': {'MAVEN_DISTRIBUTION': [bad, self.endpoint('maven')]}})
        RUNTIME['mirror_wrapper'](policy, 'MAVEN_DISTRIBUTION', properties, properties.parent / 'maven-wrapper')
        self.assertEqual(original, properties.read_text())
        self.assertEqual(2, COUNTS['/invalid-metadata/apache-maven-3.9.9-bin.zip.sha512'])
        namespace = {'__name__': 'toolchain_metadata_fixture'}
        exec(compile(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'), sys.argv[2], 'exec'), namespace)
        policy = Policy({'formatVersion': 1, 'points': {
            'NODE_METADATA': [self.endpoint('node-bad'), self.endpoint('node-good')],
            'NODE_DISTRIBUTION': [self.endpoint('good')], 'RUBYGEMS': []}})
        namespace['task_mirrors'] = lambda: (RUNTIME, policy)
        with unittest.mock.patch.dict(os.environ, {'WTL_MIRROR_POLICY': 'fixture'}):
            release = namespace['release']('NODE', '20', '20.10.0')
        self.assertEqual('20.10.0', release[0])
        self.assertEqual(SHA, release[3])
        self.assertEqual(2, COUNTS['/node-bad/index.json'])

    def test_oci_arg_multistage_digest_freeze_on_task_copy(self):
        root = pathlib.Path(self.temp.name)
        dockerfile = root / 'Dockerfile'
        content = 'ARG BASE=alpine:3.20\nFROM ${BASE} AS base\nRUN echo local\nFROM base AS final\nCOPY --from=base /x /x\n'
        dockerfile.write_text(content)
        endpoint = self.endpoint('oci')
        policy = Policy({'formatVersion': 1, 'points': {'OCI_REGISTRY': [endpoint]}})
        engine = root / 'engine.py'
        log = root / 'engine.jsonl'
        engine.write_text('import json,sys\nwith open(' + repr(str(log)) + ',"a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\n')
        output = root / 'Dockerfile.mirrors'
        RUNTIME['mirror_oci_prepare'](policy, [sys.executable, str(engine)], dockerfile, output)
        frozen = output.read_text()
        self.assertIn('FROM localhost/wtl-frozen-base:', frozen)
        self.assertIn('FROM base AS final', frozen)
        self.assertEqual(dockerfile.read_text(), content)
        operations = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertEqual([item[0] for item in operations], ['load'])
        self.assertIn(hashlib.sha256(MANIFEST).hexdigest(), frozen)


if __name__ == '__main__':
    selected = ['RuntimeTests.test_native_helper_package_flags_reach_plan_and_final_no_download'] if len(sys.argv) > 3 else []
    unittest.main(argv=[sys.argv[0]] + selected, verbosity=2)
