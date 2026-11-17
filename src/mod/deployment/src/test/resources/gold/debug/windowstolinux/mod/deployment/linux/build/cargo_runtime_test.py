import hashlib
import http.server
import io
import json
import os
import pathlib
import subprocess
import sys
import tarfile
import threading
import time
import unittest
import uuid


runtime, registry, worker, scratch = [pathlib.Path(value) for value in sys.argv[1:]]
if os.name == 'nt':
    scratch = pathlib.Path('\\\\?\\' + str(scratch.resolve()))
sys.argv = sys.argv[:1]
source = runtime.read_text(encoding='utf-8')
scope = {'__name__': 'cargo_runtime_fixture'}
exec(compile(source, str(runtime), 'exec'), scope)
exec(compile(registry.read_text(encoding='utf-8'), str(registry), 'exec'), scope)
# Windows exercises parsing and transport, not Linux descriptor isolation.
if os.name == 'nt':
    os.O_NOFOLLOW = getattr(os, 'O_NOFOLLOW', 0)
    os.O_NONBLOCK = getattr(os, 'O_NONBLOCK', 0)
worker_file = scratch / 'actual-worker.py'
worker_file.write_text(('import os\n'
    'if os.name == "nt":\n'
    '    os.O_NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)\n'
    '    os.O_NONBLOCK = getattr(os, "O_NONBLOCK", 0)\n')
    + registry.read_text(encoding='utf-8') + '\n' + worker.read_text(encoding='utf-8'), encoding='utf-8')


class CargoRuntimeTests(unittest.TestCase):
    def candidate(self, lock, policy, supplied_runtime=source):
        candidate = scratch / str(uuid.uuid4())
        (candidate / 'mutable' / 'home').mkdir(parents=True)
        task = str(uuid.uuid4())
        (candidate / '.agent-task').write_text(task + '\n', encoding='ascii')
        archive = candidate / 'mutable' / 'source.tar.gz'
        with tarfile.open(archive, 'w:gz') as output:
            member = tarfile.TarInfo('cli/Cargo.lock')
            member.size = len(lock)
            output.addfile(member, io.BytesIO(lock))
        if policy is not None:
            policy = dict(policy, task=task, server='fixture-server', formatVersion=1)
        value = {'task': task, 'archiveSha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
                 'lockMember': 'cli/Cargo.lock', 'runtime': supplied_runtime,
                 'policy': '' if policy is None else json.dumps(policy)}
        (candidate / '.build-prefetch').write_text(json.dumps(value) + '\n', encoding='utf-8')
        return candidate

    def execute(self, candidate):
        return subprocess.run([sys.executable, '-I', '-S', '-B', str(worker_file), str(candidate),
            hashlib.sha256(source.encode()).hexdigest(), '10'], cwd=scratch, capture_output=True,
            text=True, timeout=15)

    def test_actual_worker_downloads_only_locked_bytes_and_rejects_wrong_index(self):
        crate = b'locked-crate-fixture'
        digest = hashlib.sha256(crate).hexdigest()
        lock = ('version = 3\n[[package]]\nname = "xx"\nversion = "1.0.0"\n'
                'source = "registry+https://github.com/rust-lang/crates.io-index"\n'
                'checksum = "' + digest + '"\n').encode()
        requests = []
        index = {'name': 'xx', 'vers': '1.0.0', 'cksum': digest}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                body = (json.dumps(index) + '\n').encode() if self.path == '/index/2/xx' else crate
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

            def finish(self):
                super().finish()
                if os.name == 'nt':
                    time.sleep(.05)

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            base = 'http://127.0.0.1:' + str(server.server_port)
            def endpoint(origin, prefix):
                return [{'providerId': 'fixture', 'baseUrl': base + prefix, 'allowedHosts': ['127.0.0.1'],
                         'routes': [{'origin': origin, 'prefix': base + prefix}]}]
            policy = {'points': {'RUST_INDEX': endpoint('https://index.crates.io/', '/index/'),
                                 'RUST_CRATE': endpoint('https://static.crates.io/crates/', '/crates/')}}
            candidate = self.candidate(lock, policy)
            result = self.execute(candidate)
            self.assertEqual(0, result.returncode, result.stderr)
            local = candidate / 'mutable/home/wtl-cargo-registry'
            self.assertEqual(crate, (local / 'xx-1.0.0.crate').read_bytes())
            self.assertEqual(digest, json.loads((local / 'index/2/xx').read_text())['cksum'])
            self.assertEqual(['/index/2/xx', '/crates/xx/xx-1.0.0.crate'], requests)
            index['cksum'] = '0' * 64
            failed = self.execute(self.candidate(lock, policy))
            self.assertEqual(65, failed.returncode, failed.stderr)
            self.assertIn('MIRROR_REJECT=', failed.stderr)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(5)
            self.assertFalse(thread.is_alive())

    def test_no_policy_executes_the_pinned_runtime_and_rejects_changed_bytes(self):
        lock = b'version = 3\n[[package]]\nname = "workspace"\nversion = "1.0.0"\n'
        candidate = self.candidate(lock, None)
        result = self.execute(candidate)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((candidate / 'mutable/home/wtl-cargo-registry/index').is_dir())
        rejected = self.execute(self.candidate(lock, None, source + '\nraise AssertionError()\n'))
        self.assertEqual(65, rejected.returncode, rejected.stderr)
        self.assertIn('invalid-cargo-prefetch-data', rejected.stderr)

    def test_credentials_check_binding_scope_and_tls_with_complete_runtime(self):
        Policy, Failure = scope['MirrorPolicy'], scope['MirrorFailure']
        install = scope['install_mirror_credentials']
        anonymous = Policy({'formatVersion': 1, 'points': {}})
        self.assertIs(anonymous, install(anonymous, 'a' * 64))
        endpoint = {'secretRef': 'repository:fixture', 'baseUrl': 'https://fixture.example/allowed/'}
        document = {'formatVersion': 1, 'task': str(uuid.uuid4()), 'server': 'fixture-server',
                    'points': {'RUST_CRATE': [endpoint]}}
        digest = hashlib.sha256(json.dumps(document).encode()).hexdigest()
        with unittest.mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(Failure):
                install(Policy(document), digest)
        directory = scratch / document['task']
        for part in (hashlib.sha256(document['server'].encode()).hexdigest(), digest):
            directory.mkdir(mode=0o700, exist_ok=True)
            directory = directory / part
        directory.mkdir(mode=0o700)
        material = directory / hashlib.sha256(endpoint['secretRef'].encode()).hexdigest()
        material.write_bytes(b'WTL-MIRROR-1\nBearer fixture-token\n')
        material.chmod(0o600)
        with unittest.mock.patch.dict(os.environ, {'WTL_MIRROR_CREDENTIALS_DIR': str(directory)}):
            policy = install(Policy(document), digest)
            headers, context = policy.transport_options(endpoint, endpoint['baseUrl'] + 'crate')
            self.assertEqual({'Authorization': 'Bearer fixture-token'}, headers)
            self.assertIsNone(context)
            self.assertEqual({}, policy.transport_options(endpoint, 'https://elsewhere.example/crate')[0])
            material.write_bytes(b'WTL-MIRROR-1\nBearer fixture-token\ninvalid-ca')
            policy.credential_material.cache.clear()
            with self.assertRaisesRegex(Failure, 'CA material invalid'):
                policy.transport_options(endpoint, endpoint['baseUrl'] + 'crate')
            with self.assertRaises(Failure):
                install(Policy(document), 'b' * 64)


if __name__ == '__main__':
    import unittest.mock
    unittest.main()
