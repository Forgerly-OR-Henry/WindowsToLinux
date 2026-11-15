"""Exercise guarded CPython preparation without network, a compiler, root or systemd."""
import ast
import errno
import hashlib
import io
import json
import os
import pathlib
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import types
import unittest
from unittest import mock


FRAGMENTS = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain'
VERSION = '3.12.12'
DIGEST = 'a' * 64
URL = 'https://www.python.org/ftp/python/3.12.12/Python-3.12.12.tgz'
MAKE_URL = 'https://ftp.gnu.org/gnu/make/make-4.4.1.tar.gz'
OPERATION = '2fc3c77f-bfc6-4340-980b-7665c62c9ab2'


class PythonSourceBuildTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = pathlib.Path(work.name) / 'toolchains'
        (self.root / 'versions').mkdir(parents=True)
        self.ns = {'__name__': 'fixture_cpython_source',
                   '__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__': {'GNU_MAKE_SOURCE': ['https://ftp.gnu.org/gnu/make/']},
                   '__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__': []}
        for name in ('10-release-metadata.py', 'archive-boundaries.py', '20-installation-boundaries.py',
                     '30-managed-installation.py', 'tracked-operations.py', 'ruby-source-build.py', 'python-source-build.py'):
            exec(compile((FRAGMENTS / name).read_bytes(), name, 'exec'), self.ns)
        self.unit = mock.Mock()
        self.seal = mock.Mock()
        self.ns.update(ROOT=self.root, owned_directory=mock.Mock(), tracked_sync_directory=mock.Mock(),
                       tracked_regular=lambda p, limit: p.read_bytes(), source_dependencies=mock.Mock(),
                       ruby_require_settled=mock.Mock(), ruby_create_scratch=mock.Mock(side_effect=lambda p, _: p.mkdir()),
                       ruby_remove_scratch=mock.Mock(), python_source_unit=self.unit, python_source_publish=self.seal)
        self.ns['download'] = mock.Mock(side_effect=self.download)
        if os.name == 'nt':
            chmod = pathlib.Path.chmod
            patcher = mock.patch.object(pathlib.Path, 'chmod', lambda path, mode: chmod(path, mode | 0o200))
            patcher.start()
            self.addCleanup(patcher.stop)
        key = self.ns['installation_key']('PYTHON', VERSION, DIGEST)
        self.destination = self.root / 'versions' / ('python-' + key)

    def download(self, url, target, algorithm, digest, *, point=None, maximum=None):
        if point is not None:
            self.assertEqual(('GNU_MAKE_SOURCE', MAKE_URL, 'sha256', self.ns['GNU_MAKE_SOURCE_SHA256'],
                              self.ns['GNU_MAKE_SOURCE_BYTES']), (point, url, algorithm, digest, maximum))
            with target.open('xb') as archive:
                archive.truncate(maximum)
        else:
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
                stage, archive, make_archive = mounts
                self.assertTrue(unit_id.startswith('wtl-python-build-'))
                self.assertTrue(stage.is_dir())
                self.assertEqual('scratch', stage.parent.name)
                self.assertEqual(b'fixture archive', archive.read_bytes())
                self.assertEqual((archive.parent, 'gnu-make-source.tar.gz', self.ns['GNU_MAKE_SOURCE_BYTES']),
                                 (make_archive.parent, make_archive.name, make_archive.stat().st_size))
                self.seal.assert_not_called()
            else:
                self.assertTrue(unit_id.startswith('wtl-python-probe-'))
                self.seal.assert_called_once()

        self.unit.side_effect = unit
        self.ns['run'] = mock.Mock(side_effect=AssertionError('legacy privileged execution'))
        record = self.install()
        self.assertEqual(record, json.loads(marker.read_text()))
        self.assertEqual('python-dynamic-user-build-v2-gnu-make-4.4.1', record['recipe'])
        self.assertEqual({'gnu_make': {'version': '4.4.1', 'source': MAKE_URL,
                                      'sha256': self.ns['GNU_MAKE_SOURCE_SHA256']}}, record['dependencies'])
        self.assertEqual(self.destination, pathlib.Path(record['directory']))
        self.assertEqual(DIGEST, record['sha256'])
        self.assertEqual(2, self.unit.call_count)
        self.ns['source_dependencies'].assert_called_once_with('python')
        self.ns['ruby_create_scratch'].assert_called_once()
        self.ns['ruby_remove_scratch'].assert_called_once_with(*self.ns['ruby_create_scratch'].call_args.args)
        self.ns['run'].assert_not_called()
        self.assertEqual([], list((self.root / 'work').iterdir()))

    def test_legacy_protected_marker_keeps_original_identity_and_selected_probe(self):
        self.destination.mkdir()
        marker = self.destination / '.w2l-toolchain.json'
        record = {'ecosystem': 'PYTHON', 'origin': 'MANAGED', 'version': VERSION, 'directory': str(self.destination),
                  'source': URL, 'sha256': DIGEST, 'official_algorithm': 'sha256', 'official_digest': DIGEST,
                  'dependencies': {}}
        self.ns['saved_installation_marker'] = mock.Mock(return_value=marker)
        for historical in (record, dict(record, recipe='python-dynamic-user-build-v1')):
            with self.subTest(recipe=historical.get('recipe')):
                marker.write_text(json.dumps(historical), encoding='utf-8')
                self.assertEqual(historical, self.install())
                self.assertEqual((self.destination, VERSION), self.unit.call_args.args[1:])
        self.ns['download'].assert_not_called()

    def test_new_marker_requires_the_exact_gnu_make_dependency(self):
        record = self.install()
        marker = self.destination / '.w2l-toolchain.json'
        self.ns['saved_installation_marker'] = mock.Mock(return_value=marker)
        self.ns['source_url'] = mock.Mock(side_effect=AssertionError('GNU Make route is unavailable'))
        self.unit.reset_mock()
        self.ns['download'].reset_mock()
        self.assertEqual(record, self.install())
        self.unit.assert_called_once()
        self.unit.reset_mock()
        dependency = dict(record['dependencies']['gnu_make'], sha256=DIGEST)
        marker.chmod(0o644)
        marker.write_text(json.dumps(dict(record, dependencies={'gnu_make': dependency})), encoding='utf-8')
        with self.assertRaises(self.ns['PreparationFailure']):
            self.install()
        self.unit.assert_not_called()
        self.ns['download'].assert_not_called()
        self.ns['source_url'].assert_not_called()

    def test_short_gnu_make_archive_fails_before_preparation_or_publication(self):
        def download(url, target, algorithm, digest, **options):
            if not options:
                return self.download(url, target, algorithm, digest)
            target.write_bytes(b'short')
            return digest

        self.ns['download'].side_effect = download
        with self.assertRaises(self.ns['PreparationFailure']) as error:
            self.install()
        self.assertEqual('integrity', error.exception.category)
        self.ns['source_dependencies'].assert_not_called()
        self.unit.assert_not_called()
        self.seal.assert_not_called()
        self.assertEqual([], list((self.root / 'work').iterdir()))

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

    def test_python_probe_launcher_has_its_own_lower_limits_without_writable_binds(self):
        ns = self.ns
        unit = ns['source_unit_name'](OPERATION, 'probe', 'python')
        unallocated = mock.Mock(side_effect=KeyError)
        ns.update(ruby_run=mock.Mock())
        with mock.patch.dict('sys.modules', {'grp': types.SimpleNamespace(getgrnam=unallocated),
                                            'pwd': types.SimpleNamespace(getpwnam=unallocated)}), \
                mock.patch.object(os, 'readlink', side_effect=('net:[1]', 'mnt:[2]')):
            ns['source_unit'](unit, '/usr', VERSION, 'python', ns['PYTHON_SOURCE_WORKER'], None)
        command, role, ecosystem = ns['ruby_run'].call_args.args
        self.assertEqual(('probe', 'python'), (role, ecosystem))
        self.assertIn('--property=MemoryMax=268435456', command)
        self.assertIn('--property=MemorySwapMax=0', command)
        self.assertIn('--property=PrivateTmp=no', command)
        self.assertFalse(any(arg.startswith('--property=BindPaths=') for arg in command))
        mounted = next(arg for arg in command if arg.startswith('--property=TemporaryFileSystem=/tmp:'))
        for path in ('/tmp', '/var/tmp', '/dev/shm'):
            self.assertIn(path + ':ro,nosuid,nodev,noexec,size=16777216,nr_inodes=256', mounted)
        self.assertEqual([unit, '-', '-', '16777216', '256', '268435456'], command[-6:])

    def test_python_unit_binds_the_exact_staging_prefix_and_selects_its_own_worker(self):
        ns = self.ns
        stage = self.root / 'stage'
        stage.mkdir()
        archive = self.root / 'archive'
        make_archive = self.root / 'gnu-make-source.tar.gz'
        prefix = '/usr/local/lib/windowstolinux/toolchains/versions/python-fixture'
        namespace_unallocated = mock.Mock(side_effect=KeyError)
        ns.update(ruby_mount_path=str, ruby_run=mock.Mock(), remaining=mock.Mock(return_value=80))
        with mock.patch.dict('sys.modules', {'grp': types.SimpleNamespace(getgrnam=namespace_unallocated),
                                            'pwd': types.SimpleNamespace(getpwnam=namespace_unallocated)}), \
                mock.patch.object(os, 'readlink', side_effect=('net:[1]', 'mnt:[2]', 'time:[3]')), \
                mock.patch.object(time, 'monotonic_ns', return_value=1_000_000_000):
            ns['source_unit']('wtl-python-build-01234567890123456789012345678901.service',
                              prefix, VERSION, 'python', ns['PYTHON_SOURCE_WORKER'], (stage, archive, make_archive))
        init, role, ecosystem = ns['ruby_run'].call_args.args
        self.assertEqual(('build', 'python'), (role, ecosystem))
        self.assertIn('--property=DynamicUser=yes', init)
        self.assertIn('--property=PrivateNetwork=yes', init)
        self.assertIn('--property=MemoryMax=3221225472', init)
        self.assertIn('--property=MemorySwapMax=0', init)
        self.assertIn('--property=BindReadOnlyPaths=' + str(archive) + ':/tmp/wtl-toolchain-release '
                      + str(make_archive) + ':/tmp/wtl-gnu-make-source '
                      + str(stage.parent / 'readonly') + ':/var/tmp ' + str(stage.parent / 'readonly') + ':/dev/shm', init)
        self.assertIn('--property=BindPaths=' + str(stage) + ':' + prefix + ' ' + str(stage.parent) + ':/tmp', init)
        self.assertIn('--property=ReadWritePaths=' + prefix + ' /tmp', init)
        self.assertIn('--property=SystemCallFilter=~@mount @privileged @obsolete '
                      'io_uring_setup io_uring_enter io_uring_register', init)
        self.assertIn('--property=RuntimeMaxSec=80s', init)
        worker = init[init.index('--') + 1:]
        self.assertEqual(['/usr/bin/python3', '-I', '-S', '-B', '-c'], worker[:5])
        self.assertEqual(ns['PYTHON_SOURCE_WORKER'], worker[5])
        scratch = stage.parent.stat()
        self.assertEqual(['build', 'net:[1]', 'mnt:[2]', prefix, VERSION, str(stage.stat().st_dev), str(stage.stat().st_ino),
                          'standard', 'wtl-python-build-01234567890123456789012345678901.service',
                          str(scratch.st_dev), str(scratch.st_ino), '1610612736', '96000', '3221225472',
                          '81000000000', 'time:[3]'], worker[6:])

    def test_worker_validates_isolation_before_configure_and_runs_fixed_commands(self):
        tree = ast.parse(self.ns['PYTHON_SOURCE_WORKER'])
        definitions = self.make_worker_functions()
        pinned_make = definitions['GNU_MAKE_PROGRAM']
        bootstrap = mock.Mock(side_effect=lambda unit: definitions['environment'].update(
            MAKE=pinned_make, PATH=pinned_make.rsplit('/', 1)[0] + ':/usr/bin:/bin'))
        definitions.update(require_ruby_resources=mock.Mock(), verify_build_scratch=mock.Mock(),
                           verify_native_build_chain=mock.Mock(), bootstrap_gnu_make=bootstrap)
        tree.body = tree.body[next(i for i, node in enumerate(tree.body) if isinstance(node, ast.Try)) - 1:]
        worker = compile(tree, '<CPython source worker>', 'exec')
        argv = ['', 'build', 'net:[1]', 'mnt:[2]', '/protected/python', VERSION, '42', '7', 'standard',
                'wtl-python-build-test.service', '5', '6', '1610612736', '96000', '3221225472',
                '81000000000', 'time:[3]']
        with mock.patch.object(self.ns['sys'], 'argv', argv), \
                mock.patch.object(os, 'geteuid', return_value=0, create=True), \
                mock.patch.object(os, 'getegid', return_value=0, create=True), \
                mock.patch.object(subprocess, 'run') as run, self.assertRaises(SystemExit) as error:
            exec(worker, definitions)
        self.assertEqual(90, error.exception.code)
        run.assert_not_called()
        bootstrap.assert_not_called()

        status = 'NoNewPrivs: 1\nSeccomp: 2\nCapEff: 0\nCapPrm: 0\nCapBnd: 0\n'
        source = pathlib.Path('/tmp/wtl-toolchain-source')
        release = source / ('Python-' + VERSION)
        process = mock.MagicMock(stdout=io.BytesIO(), wait=mock.Mock(return_value=0))
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
                mock.patch.object(subprocess, 'Popen', return_value=process) as popen, \
                mock.patch.object(subprocess, 'run') as run:
            exec(worker, definitions)
        definitions['require_ruby_resources'].assert_called_once_with(*argv[-8:-2])
        definitions['verify_build_scratch'].assert_called_once_with(release, '/protected/python')
        definitions['verify_native_build_chain'].assert_called_once_with(release)
        calls = [entry.args[0] for entry in run.call_args_list]
        self.assertEqual(['/usr/bin/tar', str(release / 'configure'), pinned_make,
                          '/protected/python/bin/python3', '/protected/python/bin/python3'], [entry[0] for entry in calls])
        self.assertEqual(('--prefix=/protected/python', '--with-ensurepip=install'), calls[1][1:])
        self.assertEqual((pinned_make, 'MAKE=' + pinned_make, '-j2'), popen.call_args.args[0])
        self.assertEqual((pinned_make, 'MAKE=' + pinned_make, 'install'), calls[2])
        for child in [run.call_args_list[1], popen.call_args, run.call_args_list[2]]:
            self.assertEqual(pinned_make, child.kwargs['env']['MAKE'])
            self.assertTrue(child.kwargs['env']['PATH'].startswith(pinned_make.rsplit('/', 1)[0] + ':'))
        bootstrap.assert_called_once_with(argv[9])
        self.assertEqual(release, popen.call_args.kwargs['cwd'])
        self.assertEqual(('-I', '-S', '-B', '-c'), calls[3][1:5])
        self.assertIn('sys.base_prefix', calls[3][5])
        self.assertIn('import ssl, sqlite3, bz2, lzma, venv, zlib', calls[4][5])
        self.assertEqual(VERSION, calls[4][-1])

    def make_worker_functions(self):
        tree = ast.parse(self.ns['PYTHON_SOURCE_WORKER'])
        tree.body = tree.body[:next(i for i, node in enumerate(tree.body) if isinstance(node, ast.Try))]
        worker = {}
        exec(compile(tree, '<CPython make fixture>', 'exec'), worker)
        return worker

    def test_failed_build_keeps_the_observed_status_and_never_installs(self):
        tree = ast.parse(self.ns['PYTHON_SOURCE_WORKER'])
        tree.body = tree.body[next(i for i, node in enumerate(tree.body) if isinstance(node, ast.Try)) - 1:]
        worker = compile(tree, '<CPython source worker>', 'exec')
        argv = ['', 'build', 'net:[1]', 'mnt:[2]', '/protected/python', VERSION, '42', '7', 'standard',
                'wtl-python-build-test.service', '5', '6', '1610612736', '96000', '3221225472',
                '81000000000', 'time:[3]']
        release = self.root / 'release'
        for make_status in (192, 137, 184, 115):
            inputs = self.make_worker_functions()
            inputs.update(require_isolated=mock.Mock(), require_staging_mount=mock.Mock(),
                          require_ruby_resources=mock.Mock(), extract_release=mock.Mock(return_value=release),
                          verify_build_scratch=mock.Mock(), verify_native_build_chain=mock.Mock(),
                          command=mock.Mock(), make_python=mock.Mock(return_value=make_status), bootstrap_gnu_make=mock.Mock())
            with self.subTest(status=make_status), mock.patch.object(self.ns['sys'], 'argv', argv), \
                    self.assertRaises(SystemExit) as error:
                exec(worker, inputs)
            self.assertEqual(make_status, error.exception.code)
            inputs['command'].assert_called_once_with(str(release / 'configure'), '--prefix=/protected/python',
                                                     '--with-ensurepip=install', cwd=release)
            inputs['bootstrap_gnu_make'].assert_called_once_with(argv[9])

    def test_make_archive_requires_exact_bytes_and_regular_members_under_the_single_root(self):
        worker = self.make_worker_functions()
        name = 'make-4.4.1'
        content = io.BytesIO()
        with tarfile.open(fileobj=content, mode='w:gz') as archive:
            for directory in [name, name + '/src'] + [name + '/dir' + str(i) for i in range(19)]:
                entry = tarfile.TarInfo(directory)
                entry.type = tarfile.DIRTYPE
                archive.addfile(entry)
            files = ['configure', 'build.sh', 'src/job.c'] + ['stub' + str(i) for i in range(392)]
            for filename in files:
                entry = tarfile.TarInfo(name + '/' + filename)
                data = b'x' * 8_780_526 if filename == 'configure' else b''
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        valid = content.getvalue()

        invalid_archives = []
        for filename, kind in ((name + '/../outside', tarfile.REGTYPE), (name + '/configure', tarfile.SYMTYPE)):
            content = io.BytesIO()
            with tarfile.open(fileobj=content, mode='w:gz') as archive:
                entry = tarfile.TarInfo(filename)
                entry.type = kind
                archive.addfile(entry)
            invalid_archives.append(content.getvalue())
        for data, digest, accepted in [(valid, hashlib.sha256(valid).hexdigest(), True),
                                       (valid, DIGEST, False)] + [
                                           (data, hashlib.sha256(data).hexdigest(), False) for data in invalid_archives
                                       ]:
            info = types.SimpleNamespace(st_mode=0o100444, st_uid=0, st_nlink=1, st_size=len(data))
            os_fixture = types.SimpleNamespace(O_RDONLY=0, O_NOFOLLOW=1, O_NONBLOCK=2,
                                               open=mock.Mock(return_value=42), fstat=lambda fd: info,
                                               fdopen=lambda fd, mode: io.BytesIO(data))
            with self.subTest(accepted=accepted, size=len(data)), mock.patch.dict(
                    worker, os=os_fixture, GNU_MAKE_SOURCE_BYTES=len(data), GNU_MAKE_SOURCE_SHA256=digest):
                if accepted:
                    self.assertEqual(data, worker['read_gnu_make_archive']())
                else:
                    with self.assertRaises(SystemExit) as error:
                        worker['read_gnu_make_archive']()
                    self.assertEqual(210, error.exception.code)
            os_fixture.open.assert_called_once_with('/tmp/wtl-gnu-make-source', 3)

    def test_gnu_make_bootstrap_has_no_system_make_and_requires_identity_and_recipe(self):
        for feature, version, recipe_output, cgroup, expected in (
            (b'#define USE_POSIX_SPAWN 1\n', b'GNU Make 4.4.1\n', b'', True, None),
            (b'#undef USE_POSIX_SPAWN\n', b'GNU Make 4.4.1\n', b'', True, 213),
            (b'#define USE_POSIX_SPAWN 1\n', b'GNU Make 4.3\n', b'', True, 215),
            (b'#define USE_POSIX_SPAWN 1\n', b'GNU Make 4.4.1\n', b'private output', True, 216),
            (b'#define USE_POSIX_SPAWN 1\n', b'GNU Make 4.4.1\n', b'', False, 216),
        ):
            with self.subTest(expected=expected, absent_cgroup=not cgroup), tempfile.TemporaryDirectory(dir=self.root) as tmp:
                directory = pathlib.Path(tmp)
                worker = self.make_worker_functions()
                build_root = directory / 'make-build'
                release = build_root / 'make-4.4.1'
                candidate = str(release / 'make')
                real_temporary = tempfile.TemporaryDirectory
                assert_called = []

                def run(argv, **kwargs):
                    assert_called.append(argv)
                    self.assertTrue(kwargs['check'])
                    self.assertLessEqual(kwargs['timeout'], 75)
                    if argv[0] == '/usr/bin/tar':
                        self.assertEqual(b'archive', kwargs['input'])
                        (release / 'src').mkdir(parents=True)
                    elif argv[1] == './configure':
                        self.assertEqual(('--enable-posix-spawn', '--disable-nls', '--without-guile', '--disable-load',
                                          '--disable-dependency-tracking', '--prefix=/tmp/wtl-make-unused-prefix'), argv[2:])
                        self.assertEqual('/usr/bin/false', kwargs['env']['MAKE'])
                        (release / 'src/config.h').write_bytes(feature)
                    elif argv[1] == './build.sh':
                        self.assertEqual('/usr/bin/false', kwargs['env']['MAKE'])
                    elif argv == (candidate, '--version'):
                        kwargs['stdout'].write(version)
                    else:
                        self.assertEqual((candidate, '--no-print-directory', '-rR', '-j2', '-f', 'probe.mk', 'probe'), argv)
                        self.assertEqual(b'.PHONY: probe\nprobe:\n\t@/usr/bin/true\n',
                                         (pathlib.Path(kwargs['cwd']) / 'probe.mk').read_bytes())
                        kwargs['stdout'].write(recipe_output)

                with mock.patch.dict(worker, GNU_MAKE_BUILD=build_root, GNU_MAKE_PROGRAM=candidate,
                                     read_gnu_make_archive=lambda: b'archive',
                                     gnu_make_binary_digest=mock.Mock(return_value=DIGEST),
                                     only_source_worker_in_cgroup=lambda unit: cgroup), \
                        mock.patch.object(subprocess, 'run', side_effect=run), \
                        mock.patch.object(tempfile, 'TemporaryDirectory',
                                          side_effect=lambda **kwargs: real_temporary(dir=directory)):
                    if expected is None:
                        worker['bootstrap_gnu_make']('python.service')
                        self.assertEqual(candidate, worker['environment']['MAKE'])
                        self.assertEqual(str(release) + ':/usr/bin:/bin', worker['environment']['PATH'])
                        self.assertEqual(5, len(assert_called))
                    else:
                        with self.assertRaises(SystemExit) as error:
                            worker['bootstrap_gnu_make']('python.service')
                        self.assertEqual(expected, error.exception.code)
                        self.assertNotIn('MAKE', worker['environment'])

    def test_make_hints_are_fixed_and_require_complete_bounded_lines(self):
        classify = self.make_worker_functions()['make_output_hint']
        for line, status in (
            (b'as: warning: private prefix: No space left on device\n', 118),
            (b'make: File size limit exceeded (core dumped)\n', 119),
            (b'sh: fork: Resource temporarily unavailable\n', 120),
            (b'cc: /tmp/private: Permission denied\n', 136),
            (b'shell: Operation not permitted\n', 137),
            (b'cc: /tmp/private: Read-only file system\n', 138),
            (b'/bin/sh: 1: ./Programs/_freeze_module: Permission denied\n', 139),
            (b'/bin/sh: 1: ./_bootstrap_python: Permission denied\n', 140),
            (b'/bin/sh: 1: ./python: Permission denied\n', 141),
            (b'/usr/bin/ld: cannot open output file private-value: Permission denied\n', 142),
            (b"as: Fatal error: can't create private-value: Permission denied\n", 143),
            (b"PermissionError: [Errno 1] Operation not permitted: 'private-value'\n", 172),
            (b"/usr/bin/x86_64-linux-gnu-ar: private-value: Operation not permitted\n", 173),
            (b"/usr/bin/ld: private-value: Operation not permitted\n", 174),
            (b"gcc: private-value: Operation not permitted\n", 175),
            (b"/bin/sh: private-value: Operation not permitted\n", 176),
            (b"/usr/bin/cp: cannot create regular file 'private-value': Operation not permitted\n", 185),
            (b"/bin/mv: cannot move 'private-source' to 'private-value': Operation not permitted\n", 186),
            (b"ln: failed to create symbolic link 'private-value': Operation not permitted\n", 187),
            (b"mkdir: cannot create directory 'private-value': Operation not permitted\n", 188),
            (b"rm: cannot remove 'private-value': Operation not permitted\n", 189),
            (b"chmod: changing permissions of 'private-value': Operation not permitted\n", 190),
            (b"install: cannot create regular file 'private-value': Operation not permitted\n", 191),
            (b"make[2]: stat: private-value: Operation not permitted\n", 192),
            (b"/tmp/private/cp: private-value: Operation not permitted\n", 137),
            (b"cp: private-value: Operation not permitted", 99),
            (b"x" * 4097 + b"cp: private-value: Operation not permitted\n", 99),
            (b'gcc: fatal error: Killed signal terminated program cc1\n', 122),
            (b'collect2: error: ld returned 1 exit status\n', 123),
            (b"make: *** No rule to make target 'private path'.  Stop.\n", 124),
            (b'/tmp/private/compile.c:10:5: fatal error: header is missing\n', 125),
            (b"make: warning: Clock skew detected. Your build may be incomplete.\n", 99),
            (b'x' * 4097 + b'collect2: error: ld returned 1 exit status\n', 99),
        ):
            with self.subTest(status=status):
                self.assertEqual(status, classify(io.BytesIO(line)))
        self.assertEqual(118, classify(io.BytesIO(b'cc: compiler.c:10: error: private value\n'
                                                  b'/tmp/private: No space left on device\n')))
        self.assertEqual(121, classify(io.BytesIO(b'/bin/sh: 1: ./python: Permission denied\n'
                                                  b'cc: /tmp/private: Read-only file system\n')))
        self.assertEqual(177, classify(io.BytesIO(b'unknown: Operation not permitted\n'
                                                  b'make: *** [Makefile:922: checksharedmods] Error 1\n')))
        self.assertEqual(182, classify(io.BytesIO(b'unknown: Operation not permitted\n'
                                                  b'make: *** [Makefile:882: Modules/expat/libexpat.a] Error 1\n')))
        self.assertEqual(137, classify(io.BytesIO(b'unknown: Operation not permitted\n'
                                                  b'make: *** [Makefile:922: private-target] Error 1\n')))
        self.assertEqual(184, classify(io.BytesIO(b'ar: private: Operation not permitted\n'
                                                  b'make: *** [Makefile:922: checksharedmods] Error 1\n'
                                                  b'make: *** [Makefile:760: libpython3.12.a] Error 1\n')))
        self.assertEqual(184, classify(io.BytesIO(b'ar: private: Operation not permitted\n'
                                                  b'as: private: Permission denied\n')))
        self.assertEqual(184, classify(io.BytesIO(b'ln: private: Operation not permitted\n'
                                                  b'make: private: Operation not permitted\n')))

    def test_make_launch_errors_and_child_signals_have_precedence_over_child_output(self):
        make = self.make_worker_functions()['make_python']
        for errno_value, status in ((errno.EPERM, 112), (errno.ENOENT, 113), (errno.EIO, 114)):
            with self.subTest(status=status), mock.patch.object(subprocess, 'Popen', side_effect=OSError(errno_value, 'secret')):
                self.assertEqual(status, make(self.root))
        with mock.patch.object(signal, 'SIGKILL', 9, create=True), mock.patch.object(signal, 'SIGXFSZ', 25, create=True):
            for returncode, status in ((-signal.SIGKILL, 115), (-signal.SIGXFSZ, 116), (-signal.SIGTERM, 117), (0, 0)):
                process = mock.MagicMock(stdout=io.BytesIO(b'/tmp/private: No space left on device\n'),
                                         wait=mock.Mock(return_value=returncode))
                with self.subTest(status=status), mock.patch.object(subprocess, 'Popen', return_value=process):
                    self.assertEqual(status, make(self.root))
                    process.wait.assert_called_once()

    def test_make_drains_a_real_owned_process_before_reporting_the_error_hint(self):
        worker = self.make_worker_functions()
        make, pinned = worker['make_python'], worker['GNU_MAKE_PROGRAM']
        real_popen = subprocess.Popen
        children = []

        def start(argv, **kwargs):
            self.assertEqual((pinned, 'MAKE=' + pinned, '-j2'), argv)
            child = real_popen([sys.executable, '-I', '-S', '-B', '-c',
                                "import sys; sys.stdout.buffer.write(b'x' * 200000 + b'\\n'); "
                                "sys.stderr.write('collect2: error: ld returned 1 exit status\\n'); sys.exit(2)"],
                               cwd=kwargs['cwd'], stdin=kwargs['stdin'], stdout=kwargs['stdout'], stderr=kwargs['stderr'])
            children.append(child)
            return child

        try:
            with mock.patch.object(subprocess, 'Popen', side_effect=start):
                self.assertEqual(123, make(self.root))
            self.assertEqual([2], [child.poll() for child in children])
        finally:
            for child in children:
                if child.poll() is None:
                    child.kill()
                    child.wait(timeout=5)

    def test_receipt_accepts_make_hint_only_with_matching_manager_exit(self):
        diagnose = self.ns['ruby_diagnostic']
        for code, reason in self.ns['PYTHON_MAKE_FAILURES'].items():
            observation = {'actualExitCode': code, 'timedOut': False, 'cancelled': False,
                           'stdout': 'Finished with result: exit-code\n'
                                     f'Main processes terminated with: code=exited/status={code}\n',
                           'stderr': 'private fixture output'}
            with self.subTest(code=code):
                result = diagnose(observation, 'build', 'python')
                self.assertEqual('python-build-source-build', result['stage'])
                self.assertEqual(code, result['actualExitCode'])
                self.assertIn('worker-executable=make\n', result['stdout'])
                self.assertIn('make-failure=' + reason + '\n', result['stdout'])
                self.assertEqual('', result['stderr'])
                self.assertNotIn('private fixture output', str(result))
                observations = (
                    (dict(observation, actualExitCode=99), 'build', 'python'),
                    (dict(observation, cancelled=True), 'build', 'python'),
                    (dict(observation, timedOut=True), 'build', 'python'),
                    (observation, 'probe', 'python'),
                    (observation, 'build', 'ruby'),
                )
                for other, role, ecosystem in observations:
                    result = diagnose(other, role, ecosystem)
                    self.assertEqual(ecosystem + '-' + role + '-launcher', result['stage'])
                    self.assertNotIn('make-failure=', result['stdout'])

    def test_gnu_make_bootstrap_receipt_requires_matching_python_build_manager_exit(self):
        diagnose = self.ns['ruby_diagnostic']
        for code, (stage, executable) in self.ns['PYTHON_GNU_MAKE_FAILURES'].items():
            observation = {'actualExitCode': code, 'timedOut': False, 'cancelled': False,
                           'stdout': 'Finished with result: exit-code\n'
                                     f'Main processes terminated with: code=exited/status={code}\n',
                           'stderr': 'private fixture output'}
            with self.subTest(code=code):
                result = diagnose(observation, 'build', 'python')
                self.assertEqual('python-build-' + stage, result['stage'])
                self.assertIn('worker-executable=' + executable + '\n', result['stdout'])
                self.assertNotIn('private fixture output', str(result))
                for other, role, ecosystem in ((dict(observation, actualExitCode=99), 'build', 'python'),
                                               (observation, 'probe', 'python'), (observation, 'build', 'ruby')):
                    result = diagnose(other, role, ecosystem)
                    self.assertEqual(ecosystem + '-' + role + '-launcher', result['stage'])
                    self.assertNotIn('worker-executable=', result['stdout'])

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

    def test_python_tracked_cleanup_retains_work_if_mount_ownership_is_unproven(self):
        directory = self.root / 'operation'
        work = directory / 'work'
        scratch = work / 'scratch'
        scratch.mkdir(parents=True)
        state = {'processes': [], 'selected': None}
        operation = types.SimpleNamespace(directory=directory, state=state, save=mock.Mock(),
                                          request={'kind': 'PREPARE', 'operationId': OPERATION, 'parameters': {'ecosystem': 'PYTHON'}})
        self.ns['ruby_remove_scratch'].side_effect = self.ns['PreparationFailure']('ownership', 'foreign scratch')
        with self.assertRaises(self.ns['PreparationFailure']):
            self.ns['tracked_cleanup'](operation)
        self.assertTrue(work.is_dir())
        self.ns['ruby_remove_scratch'].assert_called_once_with(scratch, self.ns['source_unit_name'](OPERATION, 'build', 'python'))
        operation.save.assert_not_called()

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
