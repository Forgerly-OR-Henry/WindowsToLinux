import configparser
import contextlib
import ctypes
import gzip
import hashlib
import http.server
import io
import json
import os
import pathlib
import sys
import tempfile
import threading
import time
import types
import unittest
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET


def fake_dnf():
    arguments = sys.argv[sys.argv.index('--fake-dnf') + 1:]
    config = configparser.ConfigParser(interpolation=None)
    config.read(arguments[arguments.index('--config') + 1])
    repos = configparser.ConfigParser(interpolation=None)
    repos.read(pathlib.Path(config['main']['reposdir']) / 'task.repo')
    phase = 'query' if 'repoquery' in arguments else 'remove' if 'remove' in arguments else 'install' if '--cacheonly' in arguments else 'download'
    record = {'phase': phase, 'main': dict(config['main']),
              'repos': {name: dict(repos[name]) for name in repos.sections()},
              'arguments': arguments, 'applicationPluginDirectory': os.environ.get('DNF5_PLUGINS_DIR'),
              'proxyEnvironment': [key for key in os.environ if key.upper().endswith('_PROXY')]}
    with pathlib.Path(os.environ['FAKE_DNF_LOG']).open('a') as log:
        log.write(json.dumps(record) + '\n')
    cache = pathlib.Path(config['main']['cachedir']) / 'downloaded.json'
    if phase == 'remove' or phase == 'query' and '--installed' in arguments:
        return 0
    if phase == 'install':
        if not cache.is_file():
            return 47
        # Cacheonly may consult the loopback object cache but must never cause another upstream request.
        for url in json.loads(cache.read_text()):
            urllib.request.urlopen(url).read()
        return int(os.environ.get('FAKE_DNF_INSTALL_STATUS', '0'))
    downloaded, candidates = [], []
    for name in repos.sections():
        if repos[name].get('enabled') != '1':
            continue
        base = repos[name]['baseurl']
        repomd = ET.fromstring(urllib.request.urlopen(base + 'repodata/repomd.xml').read())
        location = repomd.find('./data/location').attrib['href']
        metadata = urllib.request.urlopen(base + location).read()
        if location.endswith('.zst'):
            metadata = metadata[9:]  # Fixture: one raw Zstandard block, after its nine-byte frame header.
        else:
            metadata = gzip.decompress(metadata)
        primary = ET.fromstring(metadata)
        for package in primary.findall('package'):
            candidates.append((base, package))
    requested = arguments[-1]
    selected = [(base, package) for base, package in candidates if requested in (
        package.findtext('name'), package.findtext('name') + '-' + package.find('version').attrib['ver'] + '-' + package.find('version').attrib['rel'])]
    if not selected:
        if phase != 'query':
            print('No match for argument: ' + requested)
            print('Error: Unable to find a match: ' + requested)
            return 1
        return 0
    if phase == 'query':
        version = selected[0][1].find('version').attrib
        print(version['ver'] + '-' + version['rel'])
        return 0
    if os.environ.get('FAKE_DNF_DOWNLOAD_ERROR') == 'permission':
        print('No match for argument: ' + requested)
        print('Error: Permission denied')
        return 1
    for base, package in selected:
            url = base + package.find('location').attrib['href']
            expected = package.find('checksum').text
            good = False
            for retry in range(2):
                try:
                    data = urllib.request.urlopen(url).read()
                    if hashlib.sha256(data).hexdigest() != expected:
                        return 48
                    good = True
                    break
                except urllib.error.HTTPError:
                    pass
            if not good:
                return 49
            downloaded.append(url)
    if os.environ.get('FAKE_DNF_DOWNLOAD_ERROR') == 'post-artifact-no-match':
        print('No match for argument: ' + requested)
        print('Error: Unable to find a match: ' + requested)
        return 1
    cache.write_text(json.dumps(downloaded))
    return 0


if '--fake-dnf' in sys.argv:
    sys.exit(fake_dnf())


runtime = types.ModuleType('dnf_runtime')
exec(compile(pathlib.Path(sys.argv[1]).read_text(), sys.argv[1], 'exec'), runtime.__dict__)
original_five = runtime._dnf_is_five
original_zstd = runtime._dnf_zstd_library
sys.argv = [sys.argv[0]] + sys.argv[2:]


class Repository:
    def __init__(self, bad_package=False, bad_metadata=False, compression='gz', missing_version=False, missing_package=False):
        self.requests = []
        self.request_times = []
        package = b'unchanged selected package identity\n'
        self.package = package
        checksum = hashlib.sha256(package).hexdigest()
        primary = ('<metadata><package><name>demo</name><version ver="' + ('0' if missing_version else '1') + '" rel="1"/><checksum type="sha256">' + checksum
                   + '</checksum><location href="Packages/demo.rpm"/></package></metadata>').encode()
        if missing_package:
            primary = b'<metadata/>'
        compressed = gzip.compress(primary, mtime=0)
        if compression == 'zst':
            assert len(primary) < 256
            compressed = b'\x28\xb5\x2f\xfd\x20' + bytes([len(primary)]) \
                + ((len(primary) << 3) | 1).to_bytes(3, 'little') + primary
        location = 'repodata/primary.xml.' + compression
        metadata_hash = hashlib.sha256(compressed).hexdigest()
        repomd = ('<repomd><data type="primary"><checksum type="sha256">' + metadata_hash
                  + '</checksum><location href="' + location + '"/></data></repomd>').encode()
        self.files = {'repodata/repomd.xml': repomd,
                      location: b'wrong metadata' if bad_metadata else compressed,
                      'Packages/demo.rpm': b'different identity' if bad_package else package}
        if missing_version:
            self.files.pop('Packages/demo.rpm')
        repository = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def finish(self):
                super().finish()
                if os.name == 'nt':
                    time.sleep(.05)

            def do_GET(self):
                repository.requests.append(self.path)
                repository.request_times.append((self.path, time.monotonic()))
                value = repository.files.get(self.path.split('/repo/', 1)[-1])
                if value is None:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header('Content-Type', 'application/octet-stream')
                self.send_header('Content-Length', str(len(value)))
                self.end_headers()
                self.wfile.write(value)

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.base = 'http://127.0.0.1:' + str(self.server.server_port) + '/repo/'

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class DnfTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='dnf-proof-')
        self.directory = pathlib.Path(self.temporary.name)
        self.repositories = self.directory / 'global-repos'
        self.repositories.mkdir()
        self.key = self.directory / 'existing-system-key'
        self.key.write_text('existing local trust key bytes\n')
        self.log = self.directory / 'native.jsonl'
        self.previous = {key: os.environ.get(key) for key in ('FAKE_DNF_LOG', 'FAKE_DNF_INSTALL_STATUS', 'FAKE_DNF_DOWNLOAD_ERROR', 'HTTP_PROXY')}
        os.environ['FAKE_DNF_LOG'] = str(self.log)
        os.environ['HTTP_PROXY'] = 'http://must-never-be-used.invalid/'
        os.environ.pop('FAKE_DNF_INSTALL_STATUS', None)
        os.environ.pop('FAKE_DNF_DOWNLOAD_ERROR', None)
        runtime._dnf_os_release = lambda: {'ID': 'rocky', 'VERSION_ID': '9.7'}
        runtime._dnf_repo_directories = lambda: [self.repositories]
        runtime._dnf_program = lambda: [sys.executable, str(pathlib.Path(__file__).resolve()), '--fake-dnf']
        runtime._dnf_is_five = original_five
        runtime._dnf_zstd_library = original_zstd
        runtime._dnf_override_directories = lambda: []
        self.write_repos()

    def tearDown(self):
        for key, value in self.previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        self.temporary.cleanup()

    def write_repos(self, additional=''):
        self.original = ('[baseos]\nname=Original BaseOS\nenabled=1\ngpgcheck=1\nrepo_gpgcheck=1\n'
                         'gpgkey=' + self.key.as_uri() + '\nmetalink=https://unselected.invalid/mirrors\n'
                         '[crb]\nenabled=0\ngpgcheck=1\ngpgkey=' + self.key.as_uri() + '\n'
                         '[disabled_vendor]\nenabled=0\nbaseurl=https://never-contact.invalid/\n' + additional)
        (self.repositories / 'system.repo').write_text(self.original)

    def policy(self, sources):
        entries = []
        for number, repository in enumerate(sources):
            entries.append({'providerId': 'selected-' + str(number), 'baseUrl': repository.base,
                            'allowedHosts': ['127.0.0.1'], 'official': False, 'custom': True,
                            'repoTemplates': {'baseos': repository.base, 'crb': repository.base},
                            'routes': [{'origin': repository.base, 'prefix': repository.base}]})
        return runtime.MirrorPolicy({'formatVersion': 1, 'points': {'DNF_ROCKY': entries}}, time.monotonic() + 20)

    def records(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_request_level_failover_pins_metadata_and_install_executes_once(self):
        with Repository(bad_package=True) as first, Repository() as second:
            policy = self.policy([first, second])
            os.environ['FAKE_DNF_INSTALL_STATUS'] = '42'
            status = runtime.mirror_dnf(policy, ['-y', 'install', 'demo'])
            self.assertEqual(42, status)
            records = self.records()
            self.assertEqual(['download', 'install'], [r['phase'] for r in records])
            self.assertEqual(1, first.requests.count('/repo/Packages/demo.rpm'))
            self.assertEqual(1, second.requests.count('/repo/Packages/demo.rpm'))
            self.assertEqual(1, first.requests.count('/repo/repodata/repomd.xml'))
            self.assertFalse(any('repomd' in path for path in second.requests))
            for record in records:
                self.assertEqual([], record['proxyEnvironment'])
                self.assertEqual('1', record['main']['retries'])
                self.assertEqual('0', record['main']['plugins'])
                self.assertEqual('_none_', record['main']['proxy'])
                self.assertEqual('1', record['repos']['baseos']['gpgcheck'])
                self.assertEqual('1', record['repos']['baseos']['repo_gpgcheck'])
                self.assertEqual(self.key.as_uri(), record['repos']['baseos']['gpgkey'])
                self.assertEqual('0', record['repos']['crb']['enabled'])
                self.assertEqual('0', record['repos']['disabled_vendor']['enabled'])
                self.assertTrue(record['repos']['baseos']['baseurl'].startswith('http://127.0.0.1:'))
                self.assertNotIn('metalink', record['repos']['baseos'])
            self.assertEqual(self.original, (self.repositories / 'system.repo').read_text())
            self.assertEqual('existing local trust key bytes\n', self.key.read_text())

    def test_single_fixed_source_never_contacts_external_or_retries_exhausted_object(self):
        with Repository(bad_package=True) as first, Repository() as unselected:
            self.assertEqual(49, runtime.mirror_dnf(self.policy([first]), ['-y', 'install', 'demo']))
            self.assertEqual(['download'], [r['phase'] for r in self.records()])
            self.assertEqual(1, first.requests.count('/repo/Packages/demo.rpm'))
            self.assertEqual([], unselected.requests)

    def test_enabled_crb_requires_explicit_template_and_disabled_vendor_stays_disabled(self):
        with Repository() as repository:
            self.assertEqual(0, runtime.mirror_dnf(self.policy([repository]), ['--enablerepo=crb', '-y', 'install', 'demo']))
            self.assertEqual('1', self.records()[0]['repos']['crb']['enabled'])
            self.assertEqual('0', self.records()[0]['repos']['disabled_vendor']['enabled'])
            self.write_repos('[unmapped]\nenabled=1\ngpgcheck=1\n')
            with self.assertRaisesRegex(runtime.MirrorFailure, 'no selected template'):
                runtime.mirror_dnf(self.policy([repository]), ['-y', 'install', 'demo'])

    def test_metadata_checksum_failure_changes_source_without_changing_identity(self):
        with Repository(bad_metadata=True) as first, Repository() as second:
            self.assertEqual(0, runtime.mirror_dnf(self.policy([first, second]), ['install', '-y', 'demo']))
            self.assertEqual(1, first.requests.count('/repo/repodata/primary.xml.gz'))
            self.assertEqual(1, second.requests.count('/repo/repodata/primary.xml.gz'))
            self.assertEqual(1, len([r for r in self.records() if r['phase'] == 'install']))

    def test_valid_metadata_missing_exact_version_advances_only_before_any_rpm_request(self):
        with Repository(missing_version=True) as first, Repository() as second:
            self.assertEqual(0, runtime.mirror_dnf(self.policy([first, second]), ['-y', 'install', 'demo-1-1']))
            selected_primary = next(when for path, when in second.request_times if path == '/repo/repodata/primary.xml.gz')
            self.assertTrue(all(when >= selected_primary for path, when in first.request_times if path == '/repo/Packages/demo.rpm'))
            self.assertEqual(1, second.requests.count('/repo/Packages/demo.rpm'))
            self.assertEqual(['download', 'download', 'install'], [item['phase'] for item in self.records()])

    def test_native_permission_failure_does_not_advance_metadata_even_with_no_match_text(self):
        os.environ['FAKE_DNF_DOWNLOAD_ERROR'] = 'permission'
        with Repository() as first, Repository() as second:
            self.assertEqual(1, runtime.mirror_dnf(self.policy([first, second]), ['install', 'demo']))
            self.assertEqual([], second.requests)
            self.assertEqual(['download'], [item['phase'] for item in self.records()])

    def test_no_match_after_rpm_identity_is_requested_cannot_change_metadata(self):
        os.environ['FAKE_DNF_DOWNLOAD_ERROR'] = 'post-artifact-no-match'
        with Repository() as first, Repository() as second:
            self.assertEqual(1, runtime.mirror_dnf(self.policy([first, second]), ['install', 'demo']))
            self.assertIn('/repo/Packages/demo.rpm', first.requests)
            self.assertEqual([], second.requests)
            self.assertEqual(['download'], [item['phase'] for item in self.records()])

    def test_available_candidate_query_advances_empty_metadata_and_retains_formatted_stdout(self):
        with Repository(missing_package=True) as first, Repository() as second:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, runtime.mirror_dnf(self.policy([first, second]),
                    ['-q', 'repoquery', '--latest-limit=1', '--qf', '%{version}-%{release}', 'demo']))
            self.assertEqual('1-1\n', output.getvalue())
            self.assertEqual(['query', 'query'], [item['phase'] for item in self.records()])
            self.assertFalse(any('Packages/' in path for path in first.requests + second.requests))

    def test_installed_dependency_query_and_removal_have_no_remote_repositories(self):
        with Repository() as unselected:
            policy = self.policy([unselected])
            self.assertEqual(0, runtime.mirror_dnf(policy, ['-q', 'repoquery', '--installed', '--whatrequires', 'demo']))
            self.assertEqual(0, runtime.mirror_dnf(policy, ['-y', '--noautoremove', 'remove', 'demo']))
            self.assertEqual([], unselected.requests)
            for record in self.records():
                self.assertEqual({}, record['repos'])
                self.assertIn('--disablerepo=*', record['arguments'])
                self.assertIn('--cacheonly', record['arguments'])

    def test_fixed_single_source_missing_version_never_installs_or_adds_another_source(self):
        with Repository(missing_version=True) as only:
            self.assertEqual(1, runtime.mirror_dnf(self.policy([only]), ['install', 'demo-1-1']))
            self.assertFalse(any('Packages/' in path for path in only.requests))
            self.assertEqual(['download'], [item['phase'] for item in self.records()])

    def test_remote_key_and_trust_disabling_arguments_are_rejected(self):
        with Repository() as repository:
            self.write_repos()
            path = self.repositories / 'system.repo'
            path.write_text(path.read_text().replace(self.key.as_uri(), 'https://unknown.invalid/key'))
            with self.assertRaisesRegex(runtime.MirrorFailure, 'local GPG key'):
                runtime.mirror_dnf(self.policy([repository]), ['-y', 'install', 'demo'])
            with self.assertRaises(runtime.MirrorFailure):
                runtime.mirror_dnf(self.policy([repository]), ['--nogpgcheck', 'install', 'demo'])
            self.assertFalse(self.log.exists())
            self.assertEqual([], repository.requests)

    def test_expired_original_deadline_does_not_start_native_process(self):
        with Repository() as repository:
            policy = self.policy([repository])
            policy.deadline = time.monotonic() - 1
            with self.assertRaisesRegex(runtime.MirrorFailure, 'deadline'):
                runtime.mirror_dnf(policy, ['-y', 'install', 'demo'])
            self.assertFalse(self.log.exists())
            self.assertEqual([], repository.requests)

    def test_dnf5_preserves_effective_dropin_enablement_and_pins_gateway_above_overrides(self):
        override = self.directory / 'global-overrides'
        override.mkdir()
        (override / '99-user.repo').write_text('[baseos]\nenabled=0\n[crb]\nenabled=1\n')
        runtime._dnf_is_five = lambda program: True
        runtime._dnf_override_directories = lambda: [override]
        with Repository() as repository:
            self.assertEqual(0, runtime.mirror_dnf(self.policy([repository]), ['-y', 'install', 'demo']))
            record = self.records()[0]
            self.assertEqual('0', record['repos']['baseos']['enabled'])
            self.assertEqual('1', record['repos']['crb']['enabled'])
            self.assertEqual(record['main']['cachedir'], record['main']['system_cachedir'])
            self.assertEqual('', record['applicationPluginDirectory'])
            self.assertIn('--no-plugins', record['arguments'])
            self.assertIn('--setopt=crb.metalink=', record['arguments'])
            self.assertIn('--setopt=crb.mirrorlist=', record['arguments'])
            self.assertIn('--setopt=crb.password=', record['arguments'])

    def test_native_zstd_stream_decodes_checksum_bound_primary(self):
        if os.name == 'nt':
            library = pathlib.Path('E:/Program/Git/mingw64/bin/libzstd.dll')
            if not library.is_file():
                self.skipTest('native zstd test capability absent')
            runtime._dnf_zstd_library = lambda: ctypes.CDLL(str(library))
        else:
            try:
                original_zstd()
            except OSError:
                self.skipTest('installed libzstd capability absent')
        with Repository(compression='zst') as repository:
            self.assertEqual(0, runtime.mirror_dnf(self.policy([repository]), ['-y', 'install', 'demo']))
            self.assertEqual(['download', 'install'], [r['phase'] for r in self.records()])
        payload = b'large native streaming frame\n' * 10000
        frame = b'\x28\xb5\x2f\xfd\xa0' + len(payload).to_bytes(4, 'little')
        for offset in range(0, len(payload), 100000):
            block = payload[offset:offset + 100000]
            last = offset + len(block) == len(payload)
            frame += ((len(block) << 3) | int(last)).to_bytes(3, 'little') + block
        archive = self.directory / 'large.zst'
        archive.write_bytes(frame)
        self.assertEqual(payload, b''.join(runtime._dnf_zstd_blocks(archive)))

    def test_invalid_repomd_response_can_advance_before_metadata_identity_is_frozen(self):
        with Repository() as first, Repository() as second:
            first.files['repodata/repomd.xml'] = b'<html>not repository metadata</html>'
            self.assertEqual(0, runtime.mirror_dnf(self.policy([first, second]), ['-y', 'install', 'demo']))
            self.assertEqual(1, first.requests.count('/repo/repodata/repomd.xml'))
            self.assertEqual(1, second.requests.count('/repo/repodata/repomd.xml'))


unittest.main(verbosity=2)
