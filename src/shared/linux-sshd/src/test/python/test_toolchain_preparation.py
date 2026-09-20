"""Offline tests of official metadata adapters and installation boundaries; no remote support claims. / 官方元数据适配器和安装边界的离线测试，不代表远端支持验证。"""
import sys
from compatibility_resources import read_fragment
sys.dont_write_bytecode = True
import hashlib
import base64
import json
import importlib.util
import io
import os
import pathlib
import tarfile
import tempfile
import time
import unittest
from unittest import mock

SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd/toolchain'
import types
preparation = types.ModuleType('preparation')
script = '\n'.join(read_fragment(path) for path in sorted(SOURCE.glob('*.py')))
exec(compile(script, 'official_toolchains', 'exec'), preparation.__dict__)
DIGEST = 'a' * 64


class OfficialMetadataTest(unittest.TestCase):
    def test_every_ecosystem_lower_middle_upper_metadata(self):
        cases = {'JAVA': ['8.0.202+8', '17.0.2+8', '25.0.1+8'],
                 'NODE': ['16.20.2', '20.1.0', '24.1.0'],
                 'PYTHON': ['3.8.20', '3.11.10', '3.14.0'],
                 'DOTNET': ['8.0.100', '8.0.408', '10.0.100'],
                 'KOTLIN': ['1.8.22', '2.1.0', '2.4.0'],
                 'GO': ['1.18.10', '1.22.5', '1.27.0'],
                 'RUST': ['1.56.1', '1.77.0', '1.98.0'],
                 'PHP': ['8.0.30', '8.3.10', '8.5.0'],
                 'RUBY': ['3.0.7', '3.3.5', '4.0.0']}
        for ecosystem, versions in cases.items():
            for version in versions:
                with self.subTest(ecosystem=ecosystem, version=version):
                    count = 1 if ecosystem in ('JAVA', 'NODE', 'DOTNET') else 2
                    branch = '.'.join(version.split('.')[:count])
                    filename = {'JAVA': 'jdk.tar.gz', 'NODE': 'node-v' + version + '-linux-x64.tar.gz',
                                'PYTHON': 'Python-' + version + '.tgz', 'DOTNET': 'sdk.tar.gz',
                                'KOTLIN': 'kotlin-compiler-' + version + '.zip', 'GO': 'go' + version + '.linux-amd64.tar.gz',
                                'RUST': 'rust-' + version + '-x86_64-unknown-linux-gnu.tar.gz',
                                'PHP': 'php-' + version + '.tar.gz', 'RUBY': 'ruby-' + version + '.tar.gz'}[ecosystem]
                    def json_at(url):
                        if ecosystem == 'JAVA':
                            return [{'version_data': {'semver': version, 'openjdk_version': version}, 'binaries': [{'os': 'linux', 'architecture': 'x64',
                                     'package': {'link': 'https://github.com/adoptium/' + filename, 'checksum': DIGEST}}]}]
                        if ecosystem == 'NODE': return [{'version': 'v' + version}]
                        if ecosystem == 'GO': return [{'version': 'go' + version, 'stable': True,
                                 'files': [{'os': 'linux', 'arch': 'amd64', 'kind': 'archive', 'filename': filename, 'sha256': DIGEST}]}]
                        if ecosystem == 'DOTNET': return {'releases': [{'sdk': {'version': version,
                                 'files': [{'rid': 'linux-x64', 'name': filename, 'url': 'https://builds.dotnet.microsoft.com/' + filename, 'hash': 'b' * 128}]}}]}
                        if ecosystem == 'KOTLIN':
                            return {'tag_name': 'v' + version, 'prerelease': False, 'draft': False,
                                    'assets': [{'name': filename, 'digest': None, 'browser_download_url': 'https://github.com/JetBrains/kotlin/' + filename}],
                                    'body': '| ' + filename + ' | ' + DIGEST + ' |'}
                        if ecosystem == 'PHP': return {version: {'source': [{'filename': filename, 'sha256': DIGEST}]}}
                        raise AssertionError(url)
                    def fetch(url, *args):
                        if ecosystem in ('NODE', 'RUST'): return (DIGEST + '  ' + filename + '\n').encode()
                        if ecosystem == 'PYTHON':
                            if url.endswith('/ftp/python/'): return ('<a href="' + version + '/">release</a>').encode()
                            return ('<tr><td>' + filename + '</td><td>' + DIGEST + '</td></tr>').encode()
                        if ecosystem == 'RUBY':
                            if url.endswith('/downloads/releases/'): return ('<tr><td>' + filename + '</td><td><a href="/en/news/fixture/">more</a></td></tr>').encode()
                            return ('<li>' + filename + '<pre>SHA256: ' + DIGEST + '</pre></li>').encode()
                        raise AssertionError(url)
                    with mock.patch.object(preparation, 'json_at', side_effect=json_at), mock.patch.object(preparation, 'fetch', side_effect=fetch):
                        resolved, url, algorithm, digest, _ = preparation.release(ecosystem, branch, version)
                        self.assertEqual(version, resolved)
                        self.assertEqual(64 if algorithm == 'sha256' else 128, len(digest))
                        self.assertTrue(url.startswith('https://'))

    def test_java_uses_openjdk_identity_instead_of_lts_semver_encoding(self):
        def asset(openjdk, semver):
            return {'version_data': {'openjdk_version': openjdk, 'semver': semver},
                    'binaries': [{'os': 'linux', 'architecture': 'x64', 'package': {
                        'link': 'https://github.com/adoptium/' + openjdk + '.tar.gz', 'checksum': DIGEST}}]}
        assets = [asset('21.0.12+8-LTS', '21.0.12+8.0.LTS'),
                  asset('21.0.12.1+1-LTS', '21.0.12+101.0.LTS')]
        with mock.patch.object(preparation, 'json_at', return_value=assets):
            self.assertEqual('21.0.12.1+1', preparation.release('JAVA', '21', '')[0])
            exact = preparation.release('JAVA', '21', '21.0.12+8')
            self.assertEqual('21.0.12+8', exact[0])
            self.assertIn('21.0.12+8-LTS.tar.gz', exact[1])
            with self.assertRaises(preparation.PreparationFailure):
                preparation.release('JAVA', '21', '21.0.12+9')
        with mock.patch.object(preparation, 'json_at', return_value=[
                asset('1.8.0_492-b09', '8.0.492+9')]):
            self.assertEqual('8.0.492+9', preparation.release('JAVA', '8', '')[0])
        with mock.patch.object(preparation, 'json_at', return_value=[
                asset('21.0.13-ea+1', '21.0.13-ea+1')]):
            with self.assertRaises(preparation.PreparationFailure):
                preparation.release('JAVA', '21', '')

    def test_numeric_order_preview_and_ecosystem_specific_java_alias(self):
        self.assertEqual('1.8.22', preparation.choose(['1.8.2', '1.8.22', '1.8.23-RC'], '1.8', ''))
        self.assertEqual((8, 0, 202), preparation.version_key('1.8.0_202-b08'))
        with self.assertRaises(preparation.PreparationFailure):
            preparation.choose(['3.14.0-rc1'], '3.14', '')
        for url in ['http://nodejs.org/file', 'https://evil.invalid/file', 'https://user@nodejs.org/file', 'https://nodejs.org:444/file']:
            with self.assertRaises(preparation.PreparationFailure): preparation.official_url(url)

    def test_exact_identity_includes_build_number_and_probe_checks_the_same_release(self):
        self.assertEqual('21.0.2+13', preparation.choose(['21.0.2+12', '21.0.2+13'], '21', '21.0.2+13'))
        with self.assertRaises(preparation.PreparationFailure):
            preparation.choose(['21.0.2+12'], '21', '21.0.2+13')
        for reported, expected, valid in [
                ('openjdk version "21.0.2"\nbuild 21.0.2+13-LTS', '21.0.2+13', True),
                ('openjdk version "21.0.2"\nbuild 21.0.2+12-LTS', '21.0.2+13', False),
                ('openjdk version "1.8.0_202"\nbuild 1.8.0_202-b08', '8.0.202+8', True),
                ('openjdk version "1.8.0_202"\nbuild 1.8.0_202-b09', '8.0.202+8', False)]:
            with self.subTest(reported=reported), mock.patch.object(preparation, 'probe_output', return_value=(0, reported)), \
                    mock.patch.object(preparation, 'run'):
                if valid:
                    preparation.probe('JAVA', pathlib.Path('/owned/tool'), expected)
                else:
                    with self.assertRaises(preparation.PreparationFailure):
                        preparation.probe('JAVA', pathlib.Path('/owned/tool'), expected)

    def test_root_admission_uses_only_the_generated_catalog(self):
        with mock.patch.dict(preparation.__dict__, {'SHIPPED_CATALOG': {'JAVA': ['8', '29']}}):
            self.assertTrue(preparation.admitted('JAVA', '29.0.1'))
            self.assertFalse(preparation.admitted('JAVA', '30.0.1'))
            self.assertFalse(preparation.admitted('JAVA', '29.0.1-rc1'))


class ArchiveAndInstallationTest(unittest.TestCase):
    def test_installation_sealing_repairs_restricted_modes_without_following_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            (root / 'bin').mkdir()
            for name in ('bin/tool', 'library', 'external-link', '.w2l-toolchain.json'):
                (root / name).write_text('fixture')
            modes = {root: 0o700, root / 'bin': 0o700, root / 'bin/tool': 0o700,
                     root / 'library': 0o600, root / '.w2l-toolchain.json': 0o600}
            stat = pathlib.Path.stat
            def restricted_stat(path, *args, **kwargs):
                values = list(stat(path, *args, **kwargs))
                if path in modes:
                    values[0] = (values[0] & 0o170000) | modes[path]
                return os.stat_result(values)
            changed = {}
            with mock.patch.object(preparation, 'owned_directory'), \
                 mock.patch.object(pathlib.Path, 'stat', autospec=True, side_effect=restricted_stat), \
                 mock.patch.object(pathlib.Path, 'is_symlink', autospec=True,
                                   side_effect=lambda path: path == root / 'external-link'), \
                 mock.patch.object(pathlib.Path, 'chmod', autospec=True,
                                   side_effect=lambda path, mode: changed.update({path: mode})):
                preparation.seal_installation(root)
            self.assertEqual({root: 0o755, root / 'bin': 0o755, root / 'bin/tool': 0o755,
                              root / 'library': 0o644, root / '.w2l-toolchain.json': 0o444}, changed)

    def test_shared_roots_are_validated_before_explicit_traversal_permission(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary) / 'toolchains'
            actions = []
            with mock.patch.object(preparation, 'ROOT', root), \
                 mock.patch.object(preparation, 'owned_directory', side_effect=lambda path: actions.append(('validate', path))), \
                 mock.patch.object(pathlib.Path, 'chmod', autospec=True,
                                   side_effect=lambda path, mode: actions.append(('mode', path, mode))):
                preparation.prepare_shared_directories()
                preparation.prepare_shared_directories()
            expected = [('validate', root), ('mode', root, 0o755),
                        ('validate', root / 'versions'), ('mode', root / 'versions', 0o755)]
            self.assertEqual(expected * 2, actions)
            self.assertTrue((root / 'versions').is_dir())
            with mock.patch.object(preparation, 'ROOT', root), \
                 mock.patch.object(preparation, 'owned_directory',
                                   side_effect=preparation.PreparationFailure('ownership', 'untrusted directory')), \
                 mock.patch.object(pathlib.Path, 'chmod') as chmod:
                with self.assertRaises(preparation.PreparationFailure):
                    preparation.prepare_shared_directories()
                chmod.assert_not_called()

    def archive(self, entries):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w:gz') as tar:
            for name, content, link in entries:
                info = tarfile.TarInfo(name)
                info.mode = 0o755
                if link is not None:
                    info.type, info.linkname = tarfile.SYMTYPE, link
                    tar.addfile(info)
                else:
                    value = content.encode()
                    info.size = len(value)
                    tar.addfile(info, io.BytesIO(value))
        return data.getvalue()

    def test_rejects_traversal_link_ancestors_and_cycles_before_extraction(self):
        for entries in [[('../escaped', 'bad', None)], [('root/link', '', '/etc')],
                        [('root/link', '', 'inside'), ('root/link/file', 'bad', None)],
                        [('root/a', '', 'b'), ('root/b', '', 'a')]]:
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                archive = root / 'archive.tar.gz'
                archive.write_bytes(self.archive(entries))
                destination = root / 'output'; destination.mkdir()
                with self.assertRaises(preparation.PreparationFailure): preparation.extract(archive, destination)
                self.assertFalse(any(destination.iterdir()))

    def test_coexistence_reuse_failure_and_cancellation_cleanup(self):
        data = self.archive([('jdk/bin/java', 'fixture-only', None)])
        expected = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            (root / 'versions').mkdir()
            def download(url, archive, *args): archive.write_bytes(data); return expected
            with mock.patch.object(preparation, 'ROOT', root), mock.patch.object(preparation, 'owned_directory'), \
                 mock.patch.object(preparation, 'DEADLINE', time.monotonic() + 60), \
                 mock.patch.object(preparation, 'download', side_effect=download) as downloads, \
                 mock.patch.object(preparation, 'probe') as probe:
                first = preparation.install('JAVA', '8.0.202', 'https://github.com/fixture', 'sha256', expected, 'archive')
                second = preparation.install('JAVA', '11.0.2', 'https://github.com/fixture', 'sha256', expected, 'archive')
                self.assertNotEqual(first['directory'], second['directory'])
                self.assertEqual(first, preparation.install('JAVA', '8.0.202', 'https://github.com/fixture', 'sha256', expected, 'archive'))
                self.assertEqual(2, downloads.call_count)
                for error in [preparation.PreparationFailure('probe', 'fixture failure'), KeyboardInterrupt()]:
                    probe.side_effect = error
                    with self.assertRaises(type(error)):
                        preparation.install('JAVA', '17.0.1', 'https://github.com/fixture', 'sha256', expected, 'archive')
                    self.assertEqual(2, len(list((root / 'versions').iterdir())))
                    self.assertEqual([], list((root / 'work').iterdir()))

    def test_ruby_legacy_tls_uses_private_prefix_and_pinned_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            with mock.patch.object(preparation, 'download') as download, mock.patch.object(preparation, 'run') as run:
                evidence = preparation.ruby_legacy_tls(root / 'ruby', root)
                self.assertEqual('3.0.3', evidence['version'])
                self.assertEqual(evidence['sha256'], download.call_args.args[3])
                self.assertEqual(str(root / 'ruby/bin/ruby'), run.call_args.args[0][0])
                self.assertIn('--local', run.call_args.args[0])
                self.assertNotIn('--global', run.call_args.args[0])

    def test_lower_middle_upper_installation_recipes_record_the_requested_exact_release(self):
        cases = {'JAVA': (['8.0.202', '17.0.2', '25.0.1'], 'archive'),
                 'NODE': (['16.20.2', '20.1.0', '24.1.0'], 'archive'),
                 'PYTHON': (['3.8.20', '3.11.10', '3.14.0'], 'python'),
                 'DOTNET': (['8.0.100', '8.0.408', '10.0.100'], 'flat'),
                 'KOTLIN': (['1.8.22', '2.1.0', '2.4.0'], 'archive'),
                 'GO': (['1.18.10', '1.22.5', '1.27.0'], 'archive'),
                 'RUST': (['1.56.1', '1.77.0', '1.98.0'], 'rust'),
                 'PHP': (['8.0.30', '8.3.10', '8.5.0'], 'php'),
                 'RUBY': (['3.0.7', '3.3.5', '4.0.0'], 'ruby')}
        data = self.archive([('official/fixture', 'simulated archive; no compilation', None)])
        digest = hashlib.sha256(data).hexdigest()
        for ecosystem, (versions, layout) in cases.items():
            for version in versions:
                with self.subTest(ecosystem=ecosystem, version=version), tempfile.TemporaryDirectory() as temporary:
                    root = pathlib.Path(temporary); (root / 'versions').mkdir()
                    def download(url, archive, *args): archive.write_bytes(data); return digest
                    with mock.patch.object(preparation, 'ROOT', root), mock.patch.object(preparation, 'owned_directory'), \
                         mock.patch.object(preparation, 'download', side_effect=download), mock.patch.object(preparation, 'run') as run, \
                         mock.patch.object(preparation, 'source_dependencies'), mock.patch.object(preparation, 'ruby_legacy_tls', return_value={}), \
                         mock.patch.object(preparation, 'probe') as probe:
                        record = preparation.install(ecosystem, version, 'https://github.com/fixture', 'sha256', digest, layout)
                        self.assertEqual((ecosystem, version, digest), (record['ecosystem'], record['version'], record['sha256']))
                        self.assertEqual(record, json.loads((pathlib.Path(record['directory']) / '.w2l-toolchain.json').read_text()))
                        probe.assert_called_once_with(ecosystem, pathlib.Path(record['directory']), version)
                        if ecosystem == 'PHP':
                            self.assertIn('--with-iconv', run.call_args_list[0].args[0])

    def test_php_recipe_does_not_reuse_installations_without_iconv(self):
        legacy = hashlib.sha256(('PHP\n8.3.33\n' + DIGEST).encode()).hexdigest()
        self.assertNotEqual(legacy, preparation.installation_key('PHP', '8.3.33', DIGEST))
        java = hashlib.sha256(('JAVA\n21.0.1\n' + DIGEST).encode()).hexdigest()
        self.assertEqual(java, preparation.installation_key('JAVA', '21.0.1', DIGEST))

    def test_php_probe_requires_runtime_extensions_before_admission(self):
        directory = pathlib.Path('selected-php')
        with mock.patch.object(preparation, 'probe_output', return_value=(0, '8.3.33')), \
             mock.patch.object(preparation, 'run') as run:
            preparation.probe('PHP', directory, '8.3.33')
            self.assertEqual(str(directory / 'bin/php'), run.call_args.args[0][0])
            for extension in ('curl', 'iconv', 'mbstring', 'pdo', 'pdo_sqlite'):
                self.assertIn('"' + extension + '"', run.call_args.args[0][2])
            run.side_effect = preparation.PreparationFailure('probe', 'missing extension')
            with self.assertRaises(preparation.PreparationFailure):
                preparation.probe('PHP', directory, '8.3.33')


class PortableBindingTest(unittest.TestCase):
    def payload(self, directory, origin='SYSTEM', digest=DIGEST):
        b64 = lambda value: base64.b64encode(value.encode()).decode()
        row = ['PYTHON', 'BUILD', b64('3.8'), b64('pyproject.toml'), 'SERIES', '3.8', '3.8.20',
               str(directory), origin, b64('system:/usr/bin/python3.8'), digest]
        return ('WTL-TOOLS-1\told-catalog\n' + '\t'.join(row) + '\n').encode()

    def test_restore_uses_saved_exact_version_and_relocates_system_binding_without_catalog_admission(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            record = {'ecosystem': 'PYTHON', 'version': '3.8.20', 'directory': str(root / 'new-python'),
                      'origin': 'MANAGED', 'source': 'https://www.python.org/fixture', 'sha256': DIGEST}
            with mock.patch.object(preparation, 'ROOT', root), mock.patch.object(preparation, 'release',
                    return_value=('3.8.20', record['source'], 'sha256', DIGEST, 'python')) as release, \
                 mock.patch.object(preparation, 'install', return_value=record), mock.patch.object(preparation, 'bind') as bind, \
                 mock.patch.dict(preparation.__dict__, {'SHIPPED_CATALOG': {}}):
                preparation.restore(base64.b64encode(self.payload(root / 'old-python')).decode())
                release.assert_called_once_with('PYTHON', '3.8', '3.8.20')
                self.assertTrue(bind.call_args.kwargs['restoring'])
                payload = base64.b64decode(bind.call_args.args[0]).decode()
                self.assertIn('old-catalog', payload)
                self.assertIn(str(root / 'new-python') + '\tMANAGED', payload)
                self.assertNotIn(str(root / 'old-python'), payload)

    def test_repeated_restore_reuses_verified_installation_without_network(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            directory = root / ('versions/python-' + DIGEST)
            directory.mkdir(parents=True)
            record = {'ecosystem': 'PYTHON', 'version': '3.8.20', 'directory': str(directory),
                      'origin': 'SYSTEM', 'source': 'system:/usr/bin/python3.8', 'sha256': DIGEST}
            marker = directory / '.w2l-toolchain.json'
            marker.write_text(json.dumps(record)); marker.chmod(0o444)
            with mock.patch.object(preparation, 'ROOT', root), mock.patch.object(preparation, 'owned_directory'), \
                 mock.patch.object(preparation, 'verify_system'), mock.patch.object(preparation, 'probe'), \
                 mock.patch.object(preparation, 'release', side_effect=AssertionError('network must not be used')), \
                 mock.patch.object(preparation, 'bind') as bind:
                payload = self.payload(directory)
                preparation.restore(base64.b64encode(payload).decode())
                self.assertEqual(payload, base64.b64decode(bind.call_args.args[0]))

    def test_repeated_preparation_keeps_the_sealed_binding_file_immutable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            directory = root / ('versions/python-' + DIGEST); directory.mkdir(parents=True)
            record = {'ecosystem': 'PYTHON', 'version': '3.8.20', 'directory': str(directory),
                      'origin': 'SYSTEM', 'source': 'system:/usr/bin/python3.8', 'sha256': DIGEST}
            marker = directory / '.w2l-toolchain.json'; marker.write_text(json.dumps(record)); marker.chmod(0o444)
            payload = self.payload(directory); encoded = base64.b64encode(payload).decode()
            with mock.patch.object(preparation, 'ROOT', root), mock.patch.object(preparation, 'owned_directory'), \
                 mock.patch.object(preparation, 'verify_system'), mock.patch.object(preparation, 'probe'), \
                 mock.patch.dict(preparation.__dict__, {'SHIPPED_CATALOG': {'PYTHON': ['3.8']}}), \
                 mock.patch('builtins.print'):
                preparation.bind(encoded)
                binding = root / 'bindings' / hashlib.sha256(payload).hexdigest()
                before = binding.stat().st_mtime_ns
                preparation.bind(encoded)
                self.assertEqual(payload, binding.read_bytes())
                self.assertEqual(before, binding.stat().st_mtime_ns)

    def test_venv_relocation_updates_only_generated_interpreter_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            selected = root / 'versions/python-fixture'
            (selected / 'bin').mkdir(parents=True)
            (selected / 'bin/python3').write_bytes(b'new-interpreter')
            (root / 'bindings').mkdir()
            payload = self.payload(selected, 'MANAGED')
            identity = hashlib.sha256(payload).hexdigest()
            (root / 'bindings' / identity).write_bytes(payload)
            release = root / 'release'
            venv = release / 'source/.venv'
            (venv / 'bin').mkdir(parents=True)
            (venv / 'bin/python').write_bytes(b'old-interpreter')
            (venv / 'pyvenv.cfg').write_text('home = /old/bin\ninclude-system-site-packages = false\nversion = 3.8.20\n')
            (release / 'source/requirements.lock').write_text('source-lock-unchanged')
            with mock.patch.object(preparation, 'ROOT', root), mock.patch.object(preparation, 'owned_directory'):
                preparation.relocate_venv(str(release), identity)
            self.assertEqual(b'new-interpreter', (venv / 'bin/python').read_bytes())
            self.assertIn('home = ' + str(selected / 'bin'), (venv / 'pyvenv.cfg').read_text())
            self.assertIn('version = 3.8.20', (venv / 'pyvenv.cfg').read_text())
            self.assertEqual('source-lock-unchanged', (release / 'source/requirements.lock').read_text())


class SourceDependencyTest(unittest.TestCase):
    def test_crb_is_enabled_only_for_the_centos_transaction_and_apt_is_preserved(self):
        for installer, release, crb in [
                ('dnf', 'ID="centos"\nVERSION_ID="9"\n', True),
                ('dnf', 'ID=centos\nVERSION_ID=10\n', True),
                ('dnf', 'ID=rocky\nVERSION_ID="9.8"\n', False),
                ('dnf', 'ID=centos\nVERSION_ID=7\n', False),
                ('apt-get', 'ID=ubuntu\nVERSION_ID="24.04"\n', False)]:
            with self.subTest(installer=installer, release=release), \
                 mock.patch.object(pathlib.Path, 'is_file', autospec=True,
                                   side_effect=lambda path: str(path).replace('\\', '/') == '/usr/bin/' + installer), \
                 mock.patch.object(pathlib.Path, 'read_text', return_value=release), \
                 mock.patch.object(preparation, 'run') as run:
                preparation.source_dependencies('python')
                self.assertEqual(1, run.call_count)
                command = run.call_args.args[0]
                self.assertEqual(crb, '--enablerepo=crb' in command)
                if installer == 'dnf':
                    self.assertEqual('/usr/bin/dnf', command[0])
                    self.assertIn('gdbm-devel', command)
                    self.assertNotIn('config-manager', command)
                else:
                    self.assertEqual(['/usr/bin/apt-get', '-o', 'DPkg::Lock::Timeout=120',
                                      'install', '-y', '--no-install-recommends'], command[:6])
                    self.assertIn('libgdbm-dev', command)
                    self.assertEqual('l', run.call_args.kwargs['environment']['NEEDRESTART_MODE'])


if __name__ == '__main__':
    unittest.main()
