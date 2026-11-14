"""Exercise guarded CPython preparation without network, a compiler, root or systemd."""
import json
import os
import pathlib
import subprocess
import tempfile
import types
import unittest
from unittest import mock


FRAGMENTS = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain'
VERSION = '3.12.12'
DIGEST = 'a' * 64
URL = 'https://www.python.org/ftp/python/3.12.12/Python-3.12.12.tgz'
OPERATION = '2fc3c77f-bfc6-4340-980b-7665c62c9ab2'


class PythonSourceBuildTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = pathlib.Path(work.name) / 'toolchains'
        (self.root / 'versions').mkdir(parents=True)
        self.ns = {'__name__': 'fixture_cpython_source',
                   '__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__': {}, '__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__': []}
        for name in ('10-release-metadata.py', 'archive-boundaries.py', '20-installation-boundaries.py',
                     '30-managed-installation.py', 'tracked-operations.py', 'ruby-source-build.py', 'python-source-build.py'):
            exec(compile((FRAGMENTS / name).read_bytes(), name, 'exec'), self.ns)
        self.unit = mock.Mock()
        self.seal = mock.Mock()
        self.ns.update(ROOT=self.root, owned_directory=mock.Mock(), tracked_sync_directory=mock.Mock(),
                       tracked_regular=lambda p, limit: p.read_bytes(), source_dependencies=mock.Mock(),
                       ruby_require_settled=mock.Mock(), python_source_unit=self.unit, python_source_publish=self.seal)
        self.ns['download'] = mock.Mock(side_effect=self.download)
        if os.name == 'nt':
            chmod = pathlib.Path.chmod
            patcher = mock.patch.object(pathlib.Path, 'chmod', lambda path, mode: chmod(path, mode | 0o200))
            patcher.start()
            self.addCleanup(patcher.stop)
        key = self.ns['installation_key']('PYTHON', VERSION, DIGEST)
        self.destination = self.root / 'versions' / ('python-' + key)

    @staticmethod
    def download(url, target, algorithm, digest):
        target.write_bytes(b'fixture archive')
        return digest

    def install(self):
        return self.ns['install']('PYTHON', VERSION, URL, 'sha256', DIGEST, 'python')

    def test_known_archive_is_built_and_probed_only_in_source_unit_then_marker_is_written(self):
        marker = self.destination / '.w2l-toolchain.json'

        def unit(unit_id, destination, version, mounts=None):
            self.assertFalse(marker.exists())
            self.assertEqual((self.destination, VERSION), (destination, version))
            if mounts is not None:
                stage, archive, gem = mounts
                self.assertTrue(unit_id.startswith('wtl-python-build-'))
                self.assertTrue(stage.is_dir())
                self.assertIsNone(gem)
                self.assertEqual(b'fixture archive', archive.read_bytes())
                self.seal.assert_not_called()
            else:
                self.assertTrue(unit_id.startswith('wtl-python-probe-'))
                self.seal.assert_called_once()

        self.unit.side_effect = unit
        self.ns['run'] = mock.Mock(side_effect=AssertionError('legacy privileged execution'))
        record = self.install()
        self.assertEqual(record, json.loads(marker.read_text()))
        self.assertEqual('python-dynamic-user-build-v1', record['recipe'])
        self.assertEqual(self.destination, pathlib.Path(record['directory']))
        self.assertEqual(DIGEST, record['sha256'])
        self.assertEqual(2, self.unit.call_count)
        self.ns['source_dependencies'].assert_called_once_with('python')
        self.ns['run'].assert_not_called()
        self.assertEqual([], list((self.root / 'work').iterdir()))

    def test_legacy_protected_marker_keeps_original_identity_and_selected_probe(self):
        self.destination.mkdir()
        marker = self.destination / '.w2l-toolchain.json'
        record = {'ecosystem': 'PYTHON', 'origin': 'MANAGED', 'version': VERSION, 'directory': str(self.destination),
                  'source': URL, 'sha256': DIGEST, 'official_algorithm': 'sha256', 'official_digest': DIGEST,
                  'dependencies': {}}
        marker.write_text(json.dumps(record), encoding='utf-8')
        self.ns['saved_installation_marker'] = mock.Mock(return_value=marker)
        self.assertEqual(record, self.install())
        self.assertEqual((self.destination, VERSION), self.unit.call_args.args[1:])
        self.ns['download'].assert_not_called()

    def test_failed_unit_never_promotes_or_seals_a_managed_installation(self):
        self.unit.side_effect = self.ns['PreparationFailure']('installation', 'fixture failure')
        with self.assertRaises(self.ns['PreparationFailure']):
            self.install()
        self.ns['ruby_require_settled'].assert_called_once()
        self.seal.assert_not_called()
        self.assertFalse(self.destination.exists())
        self.assertEqual([], list((self.root / 'work').iterdir()))

    def test_probe_uses_systemd_for_system_and_existing_managed_python(self):
        for directory in (pathlib.Path('/usr'), self.destination):
            self.ns['probe']('PYTHON', directory, VERSION)
            self.assertEqual((directory, VERSION), self.unit.call_args.args[1:])

    def test_python_unit_binds_the_exact_staging_prefix_and_selects_its_own_worker(self):
        ns = self.ns
        stage = self.root / 'stage'
        stage.mkdir()
        archive = self.root / 'archive'
        prefix = '/usr/local/lib/windowstolinux/toolchains/versions/python-fixture'
        namespace_unallocated = mock.Mock(side_effect=KeyError)
        ns.update(ruby_mount_path=str, ruby_run=mock.Mock())
        with mock.patch.dict('sys.modules', {'grp': types.SimpleNamespace(getgrnam=namespace_unallocated),
                                            'pwd': types.SimpleNamespace(getpwnam=namespace_unallocated)}), \
                mock.patch.object(os, 'readlink', side_effect=('net:[1]', 'mnt:[2]')):
            ns['source_unit']('wtl-python-build-01234567890123456789012345678901.service',
                              prefix, VERSION, 'python', ns['PYTHON_SOURCE_WORKER'], (stage, archive, None))
        init, role, ecosystem = ns['ruby_run'].call_args.args
        self.assertEqual(('build', 'python'), (role, ecosystem))
        self.assertIn('--property=DynamicUser=yes', init)
        self.assertIn('--property=PrivateNetwork=yes', init)
        self.assertIn('--property=BindReadOnlyPaths=' + str(archive) + ':/tmp/wtl-toolchain-release', init)
        self.assertIn('--property=BindPaths=' + str(stage) + ':' + prefix, init)
        self.assertIn('--property=ReadWritePaths=' + prefix, init)
        worker = init[init.index('--') + 1:]
        self.assertEqual(['/usr/bin/python3', '-I', '-S', '-B', '-c'], worker[:5])
        self.assertEqual(ns['PYTHON_SOURCE_WORKER'], worker[5])
        self.assertEqual(['build', 'net:[1]', 'mnt:[2]', prefix, VERSION, str(stage.stat().st_dev), str(stage.stat().st_ino),
                          'standard'], worker[6:])

    def test_worker_validates_isolation_before_configure_and_runs_fixed_commands(self):
        worker = compile(self.ns['PYTHON_SOURCE_WORKER'], '<CPython source worker>', 'exec')
        argv = ['', 'build', 'net:[1]', 'mnt:[2]', '/protected/python', VERSION, '42', '7', 'standard']
        with mock.patch.object(self.ns['sys'], 'argv', argv), \
                mock.patch.object(os, 'geteuid', return_value=0, create=True), \
                mock.patch.object(os, 'getegid', return_value=0, create=True), \
                mock.patch.object(subprocess, 'run') as run, self.assertRaises(SystemExit) as error:
            exec(worker, {})
        self.assertEqual(90, error.exception.code)
        run.assert_not_called()

        status = 'NoNewPrivs: 1\nSeccomp: 2\nCapEff: 0\nCapPrm: 0\nCapBnd: 0\n'
        source = pathlib.Path('/tmp/wtl-toolchain-source')
        release = source / ('Python-' + VERSION)
        with mock.patch.object(self.ns['sys'], 'argv', argv), \
                mock.patch.object(os, 'geteuid', return_value=61200, create=True), \
                mock.patch.object(os, 'getegid', return_value=61200, create=True), \
                mock.patch.object(os, 'readlink', side_effect=['net:[3]', 'mnt:[4]']), \
                mock.patch.object(os, 'ST_RDONLY', 1, create=True), \
                mock.patch.object(os, 'statvfs', return_value=types.SimpleNamespace(f_flag=1), create=True), \
                mock.patch('builtins.open', mock.mock_open(read_data=status)), \
                mock.patch('socket.AF_UNIX', 1, create=True), \
                mock.patch('socket.socket', side_effect=PermissionError), \
                mock.patch.object(os, 'stat', return_value=types.SimpleNamespace(st_dev=42, st_ino=7)), \
                mock.patch.object(pathlib.Path, 'mkdir'), mock.patch.object(pathlib.Path, 'iterdir', return_value=[release]), \
                mock.patch.object(pathlib.Path, 'is_symlink', return_value=False), \
                mock.patch.object(pathlib.Path, 'is_dir', return_value=True), \
                mock.patch.object(subprocess, 'run') as run:
            exec(worker, {})
        calls = [entry.args[0] for entry in run.call_args_list]
        self.assertEqual(['/usr/bin/tar', str(release / 'configure'), '/usr/bin/make', '/usr/bin/make',
                          '/protected/python/bin/python3', '/protected/python/bin/python3'], [entry[0] for entry in calls])
        self.assertEqual(('--prefix=/protected/python', '--with-ensurepip=install'), calls[1][1:])
        self.assertEqual(('-I', '-S', '-B', '-c'), calls[4][1:5])
        self.assertIn('sys.base_prefix', calls[4][5])
        self.assertIn('import ssl, sqlite3, bz2, lzma, venv, zlib', calls[5][5])
        self.assertEqual(VERSION, calls[5][-1])

    def test_tracked_cleanup_checks_python_build_and_probe_units_before_mutation(self):
        directory = self.root / 'operation'
        (directory / 'work' / 'stage').mkdir(parents=True)
        probe_id = 'be4c271c-99de-42ed-a95c-bbd07794e674'
        (directory / 'python-probe').write_text(probe_id)
        operation = types.SimpleNamespace(directory=directory, state={'processes': []},
                                          request={'kind': 'PREPARE', 'operationId': OPERATION, 'parameters': {'ecosystem': 'PYTHON'}})
        self.ns['tracked_require_quiescent'](operation)
        self.assertEqual([mock.call('wtl-python-build-' + OPERATION.replace('-', '') + '.service'),
                          mock.call('wtl-python-probe-' + probe_id.replace('-', '') + '.service')],
                         self.ns['ruby_require_settled'].call_args_list)

    @unittest.skipUnless(os.name == 'posix' and hasattr(os, 'O_NOFOLLOW'), 'requires POSIX no-follow descriptors')
    def test_promoted_python_alias_resolves_only_to_private_copied_executable(self):
        self.ns['RUBY_DYNAMIC_MIN'] = self.ns['RUBY_DYNAMIC_MAX'] = os.geteuid()
        stage = self.root / 'stage'
        (stage / 'bin').mkdir(parents=True)
        binary = stage / 'bin' / 'python3.12'
        binary.write_text('fixture')
        binary.chmod(0o711)
        alias = stage / 'bin' / 'python3'
        alias.symlink_to(binary.name)
        self.destination.mkdir()
        self.ns['ruby_publish'](stage, self.destination, executable='python3')
        self.assertEqual('fixture', (self.destination / 'bin' / 'python3').read_text())
        self.assertEqual(0o755, (self.destination / 'bin' / 'python3.12').stat().st_mode & 0o777)
        alias.unlink()
        alias.symlink_to('/usr/bin/python3')
        other = self.root / 'other'
        other.mkdir()
        with self.assertRaises(self.ns['PreparationFailure']):
            self.ns['ruby_publish'](stage, other, executable='python3')
