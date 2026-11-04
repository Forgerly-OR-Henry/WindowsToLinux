import contextlib
import base64
import hashlib
import io
import http.client
import http.server
import json
import os
import pathlib
import ssl
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import zipfile


runtime = {'__name__': 'mirror_test'}
exec(compile(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'), sys.argv[1], 'exec'), runtime)
Policy, Transport, Failure = (runtime[name] for name in ('MirrorPolicy', 'LockedRepositoryTransport', 'MirrorFailure'))
OPENSSL = sys.argv[2]
sys.argv = sys.argv[:1] + sys.argv[3:]


class Repository:
    def __init__(self, context, responses):
        self.requests = []
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                owner.requests.append((self.path, dict(self.headers)))
                status, content, body = responses(self.path)
                self.send_response(status)
                self.send_header('Content-Type', content)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

            def finish(self):
                super().finish()
                if os.name == 'nt':
                    time.sleep(.05)

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.base = 'https://127.0.0.1:' + str(self.server.server_port) + '/'

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


def endpoint(name, base, origin='https://locked.example/'):
    return {'providerId': name, 'baseUrl': base, 'official': False, 'custom': False,
            'allowedHosts': ['127.0.0.1'], 'sourceOrigins': [origin],
            'routes': [{'origin': origin, 'prefix': base}]}


def request(transport, path, host='locked.example', headers=None, trust=True):
    context = ssl.create_default_context(cafile=str(transport.ca)) if trust else ssl.create_default_context()
    client = http.client.HTTPSConnection('127.0.0.1', transport.server.server_port, context=context, timeout=10)
    client.set_tunnel(host, 443)
    try:
        client.request('GET', path, headers=headers or {})
        response = client.getresponse()
        return response.status, response.read()
    finally:
        client.close()


class LockedTransportTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory(prefix='locked-proof-')
        self.directory = pathlib.Path(self.work.name)
        self.project = self.directory / 'project'
        self.project.mkdir()
        policy = Policy({'formatVersion': 1, 'points': {'NODE_PACKAGE': [endpoint('certificate', 'https://127.0.0.1/')]}})
        self.certificates = Transport(policy, 'NODE_PACKAGE', self.directory, self.project, OPENSSL)
        self.upstream_context = self.certificates.tls_context('127.0.0.1')
        self.upstream_trust = ssl.create_default_context(cafile=str(self.certificates.ca))
        self.certificates.server.server_close()

    def tearDown(self):
        if os.name == 'nt':
            root = pathlib.Path('\\\\?\\' + str(self.directory))
            for path in root.rglob('*'):
                if path.is_file():
                    os.chmod(path, 0o600)
            shutil.rmtree(root)
        self.work.cleanup()

    def policy(self, point, endpoints):
        value = Policy({'formatVersion': 1, 'points': {point: endpoints},
                        'timeouts': {'connectSeconds': 1, 'noProgressSeconds': 1}})
        value.transport_options = lambda endpoint, url: ({}, self.upstream_trust)
        return value

    @contextlib.contextmanager
    def transport(self, policy, point):
        directory = self.directory / ('client-' + os.urandom(8).hex())
        directory.mkdir()
        with Transport(policy, point, directory, self.project, OPENSSL) as value:
            yield value

    def test_fixed_https_url_uses_two_selected_tls_mirrors_in_order_without_lock_edits(self):
        payload = b'unchanged locked package bytes'
        lock = self.project / 'yarn.lock'
        lock.write_text('resolved "https://locked.example/package.tgz"\nintegrity sha512-' + hashlib.sha512(payload).hexdigest())
        before = lock.read_bytes()
        with Repository(self.upstream_context, lambda path: (503, 'text/plain', b'failed')) as first, \
             Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', payload)) as second:
            policy = self.policy('NODE_PACKAGE', [endpoint('first', first.base), endpoint('second', second.base)])
            with self.transport(policy, 'NODE_PACKAGE') as transport:
                status, received = request(transport, '/package.tgz', headers={'Authorization': 'Bearer origin-only', 'Cookie': 'private=yes'})
                self.assertEqual((200, payload), (status, received))
                self.assertEqual((200, payload), request(transport, '/package.tgz'))
                self.assertEqual(2, len(first.requests))
                self.assertEqual(1, len(second.requests))
                self.assertNotIn('Authorization', second.requests[0][1])
                self.assertNotIn('Cookie', second.requests[0][1])
                self.assertEqual(before, lock.read_bytes())
                self.assertEqual('', transport.environment()['NO_PROXY'])
                self.assertNotIn('NODE_TLS_REJECT_UNAUTHORIZED', transport.environment())

    def test_private_ca_is_required_and_upstream_tls_remains_verified(self):
        with Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', b'ok')) as repository:
            policy = self.policy('NODE_PACKAGE', [endpoint('source', repository.base)])
            with self.transport(policy, 'NODE_PACKAGE') as transport:
                with self.assertRaises(ssl.SSLCertVerificationError):
                    request(transport, '/package.tgz', trust=False)
                self.assertEqual(0, len(repository.requests))
            policy.transport_options = lambda endpoint, url: ({}, ssl.create_default_context())
            with self.transport(policy, 'NODE_PACKAGE') as transport:
                self.assertEqual(502, request(transport, '/package.tgz')[0])
                self.assertEqual(0, len(repository.requests))

    def test_composer_metadata_relocation_checks_original_lock_sha1(self):
        payload = b'composer dist exact bytes'
        url = 'https://api.github.com/repos/vendor/package/zipball/frozen'
        package = {'name': 'vendor/package', 'version': '1.2.3', 'dist': {'url': url, 'type': 'zip',
                   'reference': 'frozen', 'shasum': hashlib.sha1(payload).hexdigest()}}
        lock = self.project / 'composer.lock'
        lock.write_text(json.dumps({'packages': [package]}))
        original = lock.read_bytes()
        def respond(path):
            if path == '/p2/vendor/package.json':
                changed = dict(package, dist=dict(package['dist'], url=repository.base + 'dist/package.zip'))
                return 200, 'application/json', json.dumps({'packages': {'vendor/package': [changed]}}).encode()
            return 200, 'application/zip', payload
        with Repository(self.upstream_context, respond) as repository:
            selected = endpoint('mirror', repository.base, 'https://repo.packagist.org/')
            policy = self.policy('COMPOSER_REPOSITORY', [selected])
            with self.transport(policy, 'COMPOSER_REPOSITORY') as transport:
                self.assertEqual((200, payload), request(transport, '/repos/vendor/package/zipball/frozen', 'api.github.com'))
            self.assertEqual(original, lock.read_bytes())
            package['dist']['shasum'] = '0' * 40
            lock.write_text(json.dumps({'packages': [package]}))
            with self.transport(policy, 'COMPOSER_REPOSITORY') as transport:
                self.assertEqual(502, request(transport, '/repos/vendor/package/zipball/frozen', 'api.github.com')[0])
            package['dist']['shasum'] = ''
            lock.write_text(json.dumps({'packages': [package]}))
            count = len(repository.requests)
            with self.transport(policy, 'COMPOSER_REPOSITORY') as transport:
                self.assertEqual(502, request(transport, '/repos/vendor/package/zipball/frozen', 'api.github.com')[0])
            self.assertEqual(count, len(repository.requests))

    def test_poetry_default_json_uses_selected_simple_files_and_original_hash(self):
        payload = b'locked wheel'
        digest = hashlib.sha256(payload).hexdigest()
        lock = self.project / 'poetry.lock'
        lock.write_text('[[package]]\nname = "example"\nversion = "1.0"\nfiles = [\n'
                        '{file = "example-1.0-py3-none-any.whl", hash = "sha256:' + digest + '"},\n]\n')
        original = lock.read_bytes()
        def respond(path):
            if path == '/simple/example/':
                html = '<a href="../../packages/example-1.0-py3-none-any.whl#sha256=' + digest + '">wheel</a>'
                return 200, 'text/html', html.encode()
            return 200, 'application/octet-stream', payload
        with Repository(self.upstream_context, respond) as repository:
            selected = endpoint('mirror', repository.base + 'simple/', 'https://pypi.org/simple/')
            selected['routes'].append({'origin': 'https://files.pythonhosted.org/packages/', 'prefix': repository.base + 'packages/'})
            selected['sourceOrigins'].append('https://files.pythonhosted.org/packages/')
            policy = self.policy('PYTHON_PACKAGE', [selected])
            with self.transport(policy, 'PYTHON_PACKAGE') as transport:
                status, simple = request(transport, '/simple/example/', 'pypi.org',
                                         headers={'Accept': 'application/vnd.pypi.simple.v1+json'})
                self.assertEqual(200, status)
                self.assertEqual(digest, json.loads(simple)['files'][0]['hashes']['sha256'])
                status, data = request(transport, '/pypi/example/1.0/json', 'pypi.org')
                self.assertEqual(200, status)
                document = json.loads(data)
                self.assertEqual(digest, document['urls'][0]['digests']['sha256'])
                self.assertIsNone(document['info']['requires_dist'])
                self.assertEqual((200, payload), request(transport, '/packages/example-1.0-py3-none-any.whl', 'files.pythonhosted.org'))
                self.assertEqual(original, lock.read_bytes())

    def test_bound_credentials_authenticate_only_exact_source_scope_and_custom_ca(self):
        with Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', b'private artifact')) as repository:
            selected = endpoint('private', repository.base + 'scope/')
            selected['secretRef'] = 'private-repository:1'
            policy = self.policy('NODE_PACKAGE', [selected])
            task, server = '18c7da34-2955-46fc-80a5-efcb3befc2d4', 'saved-server'
            policy.document.update(task=task, server=server)
            digest = hashlib.sha256(json.dumps(policy.document).encode()).hexdigest()
            directory = self.directory / task / hashlib.sha256(server.encode()).hexdigest() / digest
            if os.name == 'nt':
                directory = pathlib.Path('\\\\?\\' + str(directory))
            directory.mkdir(parents=True, mode=0o700)
            material = directory / hashlib.sha256(selected['secretRef'].encode()).hexdigest()
            material.write_bytes(('WTL-MIRROR-1\nBearer fixture-only\n' + self.certificates.ca.read_text()).encode())
            os.chmod(material, 0o400)
            adapter = runtime['MirrorCredentialMaterial'](policy, directory, digest)
            policy.transport_options = adapter.options
            with self.transport(policy, 'NODE_PACKAGE') as transport:
                self.assertEqual((200, b'private artifact'), request(transport, '/package.tgz'))
            self.assertEqual('Bearer fixture-only', repository.requests[0][1].get('Authorization'))
            for url in (repository.base + 'other/file', repository.base + 'scope/../other',
                        repository.base + 'scope/%2e%2e/other', repository.base + 'scope/%2Fother',
                        repository.base + 'scope/%252e%252e/other', repository.base + 'scope/%252Fother',
                        repository.base.replace('127.0.0.1', 'localhost') + 'scope/file'):
                self.assertEqual(({}, None), adapter.options(selected, url))
            headers, context = adapter.options(selected, repository.base + 'scope/file%20name')
            self.assertEqual('Bearer fixture-only', headers.get('Authorization'))
            self.assertIsNotNone(context)
            with self.assertRaises(Failure):
                runtime['MirrorCredentialMaterial'](policy, directory, '0' * 64)

    def test_real_pip_installs_unchanged_hashed_direct_url_through_task_tls(self):
        wheel = io.BytesIO()
        with zipfile.ZipFile(wheel, 'w') as archive:
            archive.writestr('example/__init__.py', 'VALUE = "original-wheel"\n')
            archive.writestr('example-1.0.dist-info/METADATA', 'Metadata-Version: 2.1\nName: example\nVersion: 1.0\n')
            archive.writestr('example-1.0.dist-info/WHEEL', 'Wheel-Version: 1.0\nGenerator: local-proof\nRoot-Is-Purelib: true\nTag: py3-none-any\n')
            archive.writestr('example-1.0.dist-info/RECORD', '')
        payload = wheel.getvalue()
        lock = self.project / 'requirements.lock'
        lock.write_bytes(('https://locked.example/example-1.0-py3-none-any.whl#sha256=' + hashlib.sha256(payload).hexdigest() + '\n').encode())
        original = lock.read_bytes()
        with Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', payload)) as repository:
            policy = self.policy('PYTHON_PACKAGE', [endpoint('mirror', repository.base)])
            with self.transport(policy, 'PYTHON_PACKAGE') as transport:
                environment = transport.environment()
                for key in ('TEMP', 'TMP', 'TMPDIR'):
                    environment[key] = str(self.directory)
                installed = self.project / 'installed'
                process = subprocess.run([sys.executable, '-I', '-m', 'pip', '--isolated', '--disable-pip-version-check',
                    'install', '--no-input', '--no-index', '--no-deps', '--no-cache-dir', '--no-compile',
                    '--target', str(installed), '--requirement', str(lock)], cwd=self.project, env=environment,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
                self.assertEqual(0, process.returncode, process.stdout.decode(errors='replace'))
                self.assertTrue((installed / 'example' / '__init__.py').is_file())
                self.assertEqual(original, lock.read_bytes())
                self.assertEqual(1, len(repository.requests))

    def test_nuget_tls_preserves_config_and_switches_only_corrupt_locked_package(self):
        payload = b'nuget original signed package bytes'
        lock = self.project / 'packages.lock.json'
        lock.write_text(json.dumps({'version': 1, 'dependencies': {'net8.0': {'Example': {
            'type': 'Direct', 'resolved': '1.0.0',
            'contentHash': base64.b64encode(hashlib.sha512(payload).digest()).decode()}}}}))
        config = self.project / 'NuGet.Config'
        config.write_text('<configuration><config><add key="signatureValidationMode" value="require"/></config>'
                          '<trustedSigners/><packageSourceMapping/><auditSources/></configuration>')
        originals = lock.read_bytes(), config.read_bytes()
        with Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', b'corrupt')) as first, \
             Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', payload)) as second:
            policy = self.policy('NUGET', [endpoint('first', first.base), endpoint('second', second.base)])
            with self.transport(policy, 'NUGET') as transport:
                self.assertEqual((200, payload), request(transport, '/flat/example/1.0.0/example.1.0.0.nupkg'))
                self.assertEqual((200, payload), request(transport, '/flat/example/1.0.0/example.1.0.0.nupkg'))
                self.assertEqual('1', transport.environment()['NUGET_ENHANCED_MAX_NETWORK_TRY_COUNT'])
            self.assertEqual(1, len(first.requests))
            self.assertEqual(1, len(second.requests))
        self.assertEqual(originals, (lock.read_bytes(), config.read_bytes()))


if __name__ == '__main__':
    unittest.main(verbosity=2)
