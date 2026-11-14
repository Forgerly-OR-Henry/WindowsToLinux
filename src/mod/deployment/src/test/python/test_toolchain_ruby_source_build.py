"""Exercise the formal Ruby recipe without starting a service or compiling Ruby."""
import ast
import errno
import hashlib
import io
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import types
import unittest
from unittest import mock


SOURCES = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain'
VERSION = '3.3.13'
DIGEST = 'a' * 64
URL = 'https://example.invalid/ruby.tar.gz'
OPERATION_ID = '2fc3c77f-bfc6-4340-980b-7665c62c9ab2'


class RubySourceBuildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name) / 'toolchains'
        (self.root / 'versions').mkdir(parents=True)
        self.ns = {'__name__': 'controlled_ruby_test', '__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__': {},
                   '__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__': ['example.invalid']}
        for name in ('10-release-metadata.py', 'archive-boundaries.py', '20-installation-boundaries.py',
                     '30-managed-installation.py', 'tracked-operations.py', 'ruby-source-build.py'):
            exec(compile((SOURCES / name).read_text(encoding='utf-8'), name, 'exec'), self.ns)
        self.ns.update(ROOT=self.root, owned_directory=mock.Mock(), tracked_sync_directory=mock.Mock(),
                       source_dependencies=mock.Mock(), ruby_require_settled=mock.Mock(), ruby_probe=mock.Mock(),
                       ruby_unit=mock.Mock(side_effect=self.stage_installation),
                       ruby_create_scratch=mock.Mock(side_effect=lambda path, unit: path.mkdir()),
                       ruby_remove_scratch=mock.Mock(),
                       ruby_publish=mock.Mock(side_effect=self.publish_installation))
        self.ns['download'] = mock.Mock(side_effect=self.download)
        self.ns['source_url'] = mock.Mock(return_value='https://example.invalid/openssl-3.0.3.gem')
        if os.name == 'nt':
            # Windows read-only DOS attributes cannot model POSIX modes and prevent fixture removal.
            chmod = pathlib.Path.chmod
            patcher = mock.patch.object(pathlib.Path, 'chmod', lambda path, mode: chmod(path, mode | 0o200))
            patcher.start()
            self.addCleanup(patcher.stop)

    def stage_installation(self, unit, destination, version, mounts):
        stage, source, _ = mounts
        self.assertTrue(source.is_file())
        self.assertEqual(destination, self.destination(version))
        self.assertFalse((destination / '.w2l-toolchain.json').exists())
        (stage / 'ruby').write_text('fixed staged output')

    def publish_installation(self, stage, destination):
        self.assertFalse((destination / '.w2l-toolchain.json').exists())
        self.assertEqual('fixed staged output', (stage / 'ruby').read_text())
        (destination / 'bin').mkdir()
        shutil.copyfile(stage / 'ruby', destination / 'bin/ruby')

    @staticmethod
    def download(url, target, algorithm, digest):
        target.write_bytes(b'controlled archive')
        return digest

    def destination(self, version=VERSION):
        return self.root / 'versions' / ('ruby-' + self.ns['installation_key']('RUBY', version, DIGEST))

    def install(self, version=VERSION):
        return self.ns['install']('RUBY', version, URL, 'sha256', DIGEST, 'ruby')

    def test_old_ruby_recipe_is_not_reused_and_other_installation_keys_are_preserved(self):
        for version in ('3.0.7', VERSION):
            self.assertNotEqual(self.ns['_previous_installation_key']('RUBY', version, DIGEST),
                                self.ns['installation_key']('RUBY', version, DIGEST))
        for ecosystem, version in (('PYTHON', '3.12.12'), ('PHP', '8.3.35'), ('RUST', '1.80.0')):
            self.assertEqual(self.ns['_previous_installation_key'](ecosystem, version, DIGEST),
                             self.ns['installation_key'](ecosystem, version, DIGEST))
        old_key = self.ns['_previous_installation_key']('RUBY', VERSION, DIGEST)
        old = self.root / 'versions' / ('ruby-' + old_key)
        old.mkdir()
        (old / '.w2l-toolchain.json').write_text('previous recipe marker')
        record = self.install()
        self.assertEqual(self.ns['RUBY_RECIPE'], record['recipe'])
        self.assertEqual(str(self.destination()), record['directory'])
        self.assertEqual('previous recipe marker', (old / '.w2l-toolchain.json').read_text())
        self.ns['source_dependencies'].assert_called_once_with('ruby')
        self.ns['ruby_publish'].assert_called_once()
        self.ns['ruby_probe'].assert_called_once_with(self.destination(), VERSION)
        self.assertEqual([], list((self.root / 'work').iterdir()))

    def test_ruby_30_passes_only_checksum_verified_legacy_gem_to_the_same_unit(self):
        record = self.install('3.0.7')
        arguments = self.ns['ruby_unit'].call_args.args
        self.assertEqual('3.0.7', arguments[2])
        self.assertEqual('openssl-3.0.3.gem', arguments[3][2].name)
        self.assertEqual(self.ns['RUBY_OPENSSL_DIGEST'], record['dependencies']['openssl']['sha256'])
        self.assertEqual(record['dependencies']['openssl']['sha256'], self.ns['download'].call_args.args[-1])
        self.assertEqual('sha256', self.ns['download'].call_args.args[-2])

    def test_failed_unit_without_proven_quiescence_leaves_tracked_work_unpublished(self):
        selected = {'directory': str(self.destination())}
        operation_dir = self.root / 'operation'
        operation_dir.mkdir()
        operation = self.ns['TrackedToolchainOperation'](
            {'operationId': OPERATION_ID, 'kind': 'PREPARE', 'parameters': {'ecosystem': 'RUBY'}},
            b'', DIGEST, operation_dir,
            {'ownedDestination': True, 'selected': selected, 'processes': []},
        )
        self.ns['TRACKED_OPERATION'] = operation
        failure = self.ns['PreparationFailure']('interrupted', 'unit may still be active')
        self.ns['ruby_unit'].side_effect = failure
        self.ns['ruby_require_settled'].side_effect = failure
        with self.assertRaises(self.ns['PreparationFailure']):
            self.install()
        self.assertTrue((operation_dir / 'work/scratch/stage').is_dir())
        self.assertTrue((self.destination() / '.w2l-operation-owner').is_file())
        self.assertFalse((self.destination() / '.w2l-toolchain.json').exists())
        self.ns['ruby_publish'].assert_not_called()
        self.ns['ruby_remove_scratch'].assert_not_called()
        with self.assertRaises(self.ns['PreparationFailure']):
            self.ns['tracked_require_quiescent'](operation)
        self.ns['ruby_require_settled'].assert_called_with(self.ns['ruby_unit_name'](OPERATION_ID, 'build'))

    def test_failed_public_probe_prevents_installation_marker(self):
        self.ns['ruby_probe'].side_effect = self.ns['PreparationFailure']('probe', 'controlled failure')
        with self.assertRaises(self.ns['PreparationFailure']):
            self.install()
        self.assertFalse(self.destination().exists())
        self.assertEqual([], list((self.root / 'work').iterdir()))

    def test_external_or_cyclic_staging_links_are_rejected(self):
        resolve = self.ns['ruby_link_target']
        self.assertEqual(('lib', 'real'), resolve(('bin', 'link'), {('bin', 'link'): '../lib/real'}))
        for path, links in (
            (('bin', 'a'), {('bin', 'a'): '../../outside'}),
            (('bin', 'a'), {('bin', 'a'): 'b', ('bin', 'b'): 'a'}),
            (('bin', 'a'), {('bin', 'a'): '../redirect/../../outside', ('redirect',): 'lib'}),
        ):
            with self.subTest(path=path, links=links), self.assertRaises(self.ns['PreparationFailure']):
                resolve(path, links)

    @unittest.skipUnless(os.name == 'posix' and hasattr(os, 'O_NOFOLLOW'), 'requires POSIX no-follow descriptors')
    def test_promotion_normalizes_permissions_and_rejects_nonregular_or_linked_files(self):
        publish = self.raw_function('ruby_publish')
        raw = publish.__globals__
        raw['RUBY_DYNAMIC_MIN'] = raw['RUBY_DYNAMIC_MAX'] = os.geteuid()
        stage = self.root / 'stage'
        (stage / 'bin').mkdir(parents=True)
        binary = stage / 'bin/ruby'
        binary.write_bytes(b'controlled executable')
        binary.chmod(0o710)
        (stage / 'bin/ruby-alias').symlink_to('ruby')
        destination = self.root / 'destination'
        destination.mkdir(mode=0o700)
        publish(stage, destination)
        self.assertEqual(0o755, destination.stat().st_mode & 0o777)
        self.assertEqual(0o755, (destination / 'bin/ruby').stat().st_mode & 0o777)
        self.assertEqual(b'controlled executable', (destination / 'bin/ruby-alias').read_bytes())
        for name, create in (('hardlink', lambda path: os.link(binary, path)),
                             ('absolute', lambda path: path.symlink_to('/etc/passwd')),
                             ('fifo', os.mkfifo)):
            with self.subTest(name=name):
                malicious = stage / name
                create(malicious)
                target = self.root / name
                target.mkdir()
                with self.assertRaises(self.ns['PreparationFailure']):
                    publish(stage, target)
                malicious.unlink()

    def test_inactive_unit_requires_zero_pids_and_confirmed_manager_response(self):
        raw = (b'LoadState=not-found\nActiveState=inactive\nMainPID=0\nControlPID=0\nControlGroup=\n')
        unit = self.ns['ruby_unit_name'](OPERATION_ID, 'build')
        settled = self.raw_function('ruby_require_settled')
        with mock.patch.object(self.ns['subprocess'], 'run') as run:
            run.return_value = types.SimpleNamespace(returncode=0, stdout=b'LoadState=not-found\nActiveState=inactive\n')
            settled(unit)
            run.return_value = types.SimpleNamespace(returncode=0, stdout=raw.replace(b'not-found', b'loaded'))
            settled(unit)
            for status, response in ((2, raw), (0, raw.replace(b'MainPID=0', b'MainPID=43')),
                                     (0, raw + b'Job=42\n'),
                                     (0, raw.replace(b'ActiveState=inactive', b'ActiveState=deactivating'))):
                with self.subTest(status=status, response=response), self.assertRaises(self.ns['PreparationFailure']):
                    run.return_value = types.SimpleNamespace(returncode=status, stdout=response)
                    settled(unit)

    def raw_function(self, name):
        # Use the exact packaged fragment while replacing only operating-system dependencies.
        namespace = dict(self.ns)
        resource = SOURCES / 'ruby-source-build.py'
        exec(compile(resource.read_text(encoding='utf-8'), str(resource), 'exec'), namespace)
        return namespace[name]

    def test_exact_prefix_and_isolation_are_bound_before_any_worker_code(self):
        ruby_unit = self.raw_function('ruby_unit')
        raw = ruby_unit.__globals__
        raw['run'] = mock.Mock()
        raw['ruby_require_settled'] = mock.Mock()
        raw['ruby_mount_path'] = str
        stage = self.root / 'stage'
        stage.mkdir()
        command = self.ns['ruby_unit_name'](OPERATION_ID, 'build')
        with mock.patch.dict('sys.modules', {'grp': types.SimpleNamespace(getgrnam=self.unallocated),
                                            'pwd': types.SimpleNamespace(getpwnam=self.unallocated)}), \
                mock.patch.object(os, 'readlink', side_effect=('net:[11]', 'mnt:[12]')):
            ruby_unit(command, '/usr/local/lib/windowstolinux/toolchains/versions/ruby-selected', VERSION,
                      (stage, self.root / 'ruby.tar.gz', self.root / 'openssl-3.0.3.gem'))
        argv = raw['run'].call_args.args[0]
        self.assertIn('--wait', argv)
        self.assertIn('--collect', argv)
        self.assertNotIn('--quiet', argv)
        self.assertIn('--property=DynamicUser=yes', argv)
        self.assertIn('--property=NoNewPrivileges=yes', argv)
        self.assertIn('--property=PrivateNetwork=yes', argv)
        self.assertIn('--property=ProtectSystem=strict', argv)
        self.assertIn('--property=MemoryMax=3221225472', argv)
        self.assertIn('--property=MemorySwapMax=0', argv)
        self.assertTrue(any(item.startswith('--property=BindPaths=') and item.endswith(':/tmp') for item in argv))
        self.assertTrue(any(item.startswith('--property=BindReadOnlyPaths=')
                            and ':/var/tmp ' in item and item.endswith(':/dev/shm') for item in argv))
        self.assertIn('--property=StandardOutput=null', argv)
        self.assertIn('--property=StandardError=null', argv)
        self.assertIn('--property=RestrictAddressFamilies=AF_INET AF_INET6 AF_NETLINK', argv)
        self.assertTrue(any(item.startswith('--property=BindReadOnlyPaths=') and ':/tmp/openssl-3.0.3.gem' in item for item in argv))
        worker_args = argv[argv.index('--') + 1:]
        self.assertEqual(['/usr/bin/python3', '-I', '-S', '-B', '-c'], worker_args[:5])
        compile(worker_args[5], '<Ruby sandbox worker>', 'exec')
        scratch = stage.parent.stat()
        self.assertEqual(['build', 'net:[11]', 'mnt:[12]', '/usr/local/lib/windowstolinux/toolchains/versions/ruby-selected',
                          VERSION, str(stage.stat().st_dev), str(stage.stat().st_ino), 'legacy', command,
                          str(scratch.st_dev), str(scratch.st_ino), '1610612736', '96000', '3221225472'], worker_args[6:])
        raw['ruby_require_settled'].assert_called_once_with(command)

    def test_owned_scratch_refuses_foreign_or_nested_mount_before_unmounting(self):
        check = self.raw_function('ruby_scratch_mount')
        raw = check.__globals__
        path = pathlib.PurePosixPath('/usr/local/lib/windowstolinux/toolchains/work/owned/scratch')
        scratch = mock.Mock()
        scratch.__str__ = mock.Mock(return_value=str(path))
        scratch.parent = path.parent
        scratch.is_symlink.return_value = False
        scratch.stat.return_value = types.SimpleNamespace(st_uid=0, st_mode=0o41777)
        unit = self.ns['ruby_unit_name'](OPERATION_ID, 'build')
        shape = types.SimpleNamespace(f_blocks=393216, f_frsize=4096, f_files=96000)
        row = f'32 20 0:15 / {path} rw,nosuid,nodev - tmpfs {unit} rw\n'
        raw['owned_directory'] = mock.Mock()
        with mock.patch.object(raw['os'], 'statvfs', return_value=shape, create=True):
            for mountinfo, expected in ((row, True), ('', False)):
                with self.subTest(mountinfo=mountinfo), mock.patch('builtins.open', mock.mock_open(read_data=mountinfo)):
                    self.assertEqual(expected, check(scratch, unit))
            for mountinfo in (row.replace(unit, 'foreign'), row + row.replace('scratch ', 'scratch/extra ')):
                with self.subTest(mountinfo=mountinfo), mock.patch('builtins.open', mock.mock_open(read_data=mountinfo)):
                    with self.assertRaises(raw['PreparationFailure']):
                        check(scratch, unit)

    def test_worker_requires_effective_scratch_and_cgroup_bounds_before_build(self):
        worker = ast.parse(self.ns['RUBY_WORKER'])
        worker.body = [definition for definition in worker.body if isinstance(definition, (ast.Import, ast.FunctionDef, ast.Assign))
                       and not (isinstance(definition, ast.Assign) and definition.targets[0].id == 'failure')]
        scope = {}
        exec(compile(worker, '<Ruby resource guard>', 'exec'), scope)
        unit = self.ns['ruby_unit_name'](OPERATION_ID, 'build')
        paths = {'/proc/self/cgroup': '0::/system.slice/' + unit + '\n',
                 'memory.max': '3221225472\n', 'memory.swap.max': '0\n'}
        scratch = types.SimpleNamespace(st_dev=2, st_ino=3)
        validate = scope['require_ruby_resources']
        for reason in ('valid', 'oversize', 'inodes', 'swap', 'alternate-tmp'):
            with self.subTest(reason=reason):
                values = {**paths, 'memory.swap.max': 'max\n' if reason == 'swap' else '0\n'}
                filesystem = types.SimpleNamespace(f_blocks=393217 if reason == 'oversize' else 393216,
                                                   f_frsize=4096, f_files=96001 if reason == 'inodes' else 96000,
                                                   f_flag=0 if reason == 'alternate-tmp' else 1)
                with mock.patch.object(pathlib.Path, 'read_text', autospec=True) as read, \
                        mock.patch.object(os, 'stat', return_value=scratch), \
                        mock.patch.object(os, 'ST_RDONLY', 1, create=True), \
                        mock.patch.object(os, 'statvfs', return_value=filesystem, create=True):
                    read.side_effect = lambda path, **_: values.get(path.name, values['/proc/self/cgroup'])
                    args = (unit, '2', '3', '1610612736', '96000', '3221225472')
                    if reason == 'valid':
                        validate(*args)
                    else:
                        with self.assertRaises(SystemExit) as caught:
                            validate(*args)
                        self.assertEqual(126, caught.exception.code)

    def test_probe_requires_capped_readonly_private_tmpfs_and_cgroup(self):
        source = ast.parse(self.ns['SOURCE_WORKER'])
        scope = {}
        exec(compile(source, '<source resource guard>', 'exec'), scope)
        unit = self.ns['ruby_unit_name'](OPERATION_ID, 'probe')
        mount = '32 20 0:9 / /tmp ro,nosuid,nodev,noexec - tmpfs tmpfs rw\n'
        paths = {'cgroup': '0::/system.slice/' + unit + '\n',
                 'memory.max': '268435456\n', 'memory.swap.max': '0\n', 'mountinfo': mount}
        stat = types.SimpleNamespace(st_dev=9, st_ino=2)
        args = (unit, '-', '-', '16777216', '256', '268435456')
        for case, mountinfo, flags in (('valid', mount, 1), ('foreign-device', mount.replace('0:9', '0:8'), 1),
                                       ('other-fs', mount.replace('- tmpfs', '- ext4'), 1), ('writable', mount, 0)):
            with self.subTest(case=case), \
                    mock.patch.object(pathlib.Path, 'read_text', autospec=True) as read, \
                    mock.patch.object(os, 'stat', return_value=stat), \
                    mock.patch.object(os, 'major', return_value=0, create=True), \
                    mock.patch.object(os, 'minor', return_value=9, create=True), \
                    mock.patch.object(os, 'ST_RDONLY', 1, create=True), \
                    mock.patch.object(os, 'statvfs', create=True,
                                      return_value=types.SimpleNamespace(f_blocks=4096, f_frsize=4096,
                                                                         f_files=256, f_flag=flags)):
                read.side_effect = lambda path, **_: mountinfo if path.name == 'mountinfo' else paths[path.name]
                if case == 'valid':
                    scope['require_ruby_resources'](*args)
                else:
                    with self.assertRaises(SystemExit) as caught:
                        scope['require_ruby_resources'](*args)
                    self.assertEqual(126, caught.exception.code)

    def test_fixed_scratch_check_writes_and_links_in_source_and_staging_then_cleans(self):
        scope = {}
        exec(compile(self.ns['SOURCE_WORKER'], '<source scratch check>', 'exec'), scope)
        release, stage = self.root / 'source-fixture', self.root / 'stage-fixture'
        release.mkdir()
        stage.mkdir()

        def run(alias, cwd):
            script = pathlib.Path(alias)
            self.assertEqual(release, cwd)
            self.assertEqual(b'#!/bin/sh\nexit 0\n', script.read_bytes())
            self.assertTrue(os.path.samestat(script.stat(), script.with_name('check').stat()))

        scope['command'] = mock.Mock(side_effect=run)
        if os.name == 'nt':
            def fixture_link(source, alias):
                self.assertEqual(source.parent, alias.parent)
                self.assertIn(source.parent.parent, (release, stage))
                shutil.copyfile(source, alias)

            with mock.patch.object(os, 'link', side_effect=fixture_link) as link, \
                    mock.patch.object(os.path, 'samestat', return_value=True):
                scope['verify_build_scratch'](release, stage)
                self.assertEqual(2, link.call_count)
        else:
            scope['verify_build_scratch'](release, stage)
        scope['command'].assert_called_once()
        self.assertEqual([], list(release.iterdir()))
        self.assertEqual([], list(stage.iterdir()))

    def test_fixed_native_chain_runs_only_its_own_probe_and_cleans_on_each_outcome(self):
        scope = {}
        exec(compile(self.ns['SOURCE_WORKER'], '<native build chain>', 'exec'), scope)
        sequence = (('/usr/bin/cc', '-c', 'probe.c', '-o', 'probe.o'),
                    ('/usr/bin/ar', 'crs', 'probe.a', 'probe.o'),
                    ('/usr/bin/cc', 'probe.a', '-o', 'probe'),
                    ('./probe',))
        cases = ((None, None), (PermissionError(errno.EPERM, 'private'), 145),
                 (FileNotFoundError(errno.ENOENT, 'private'), 146),
                 (OSError(errno.EROFS, 'private'), 147),
                 (subprocess.CalledProcessError(2, 'private'), 148),
                 (subprocess.CalledProcessError(-9, 'private'), 149))
        for error, expected in cases:
            for index in range(4) if error else (3,):
                with self.subTest(expected=expected, index=index):
                    calls = []

                    def run(argv, *, cwd, env, stdin, stdout, stderr, check):
                        calls.append(argv)
                        self.assertEqual(self.root, pathlib.Path(cwd).parent)
                        self.assertEqual(b'int main(void) { return 0; }\n', (pathlib.Path(cwd) / 'probe.c').read_bytes())
                        self.assertEqual((subprocess.DEVNULL,) * 3, (stdin, stdout, stderr))
                        self.assertEqual('/usr/bin:/bin', env['PATH'])
                        self.assertTrue(check)
                        if error and len(calls) == index + 1:
                            raise error

                    before = list(self.root.iterdir())
                    with mock.patch.object(subprocess, 'run', side_effect=run):
                        if error:
                            with self.assertRaises(SystemExit) as observed:
                                scope['verify_native_build_chain'](self.root)
                            self.assertEqual(expected + 5 * index, observed.exception.code)
                        else:
                            scope['verify_native_build_chain'](self.root)
                    self.assertEqual(sequence[:index + 1], tuple(calls))
                    self.assertEqual(before, list(self.root.iterdir()))

    def test_native_diagnostic_requires_manager_agreement_and_uses_fixed_fields(self):
        diagnose = self.ns['ruby_diagnostic']
        for index, (phase, child) in enumerate(self.ns['SOURCE_BUILD_PROBE_STAGES']):
            for offset, failure in enumerate(self.ns['SOURCE_BUILD_PROBE_FAILURES']):
                code = 145 + index * 5 + offset
                for ecosystem in ('ruby', 'python'):
                    with self.subTest(code=code, ecosystem=ecosystem):
                        base = self.launcher_observation(code)
                        result = diagnose(base, 'build', ecosystem)
                        self.assertEqual(ecosystem + '-build-native-check-' + phase, result['stage'])
                        self.assertIn('native-check-failure=' + failure + '\n', result['stdout'])
                        self.assertIn('worker-executable=' + child + '\n', result['stdout'])
                        self.assertNotIn('sample-private-value', str(result))
                        for other, role in ((dict(base, actualExitCode=95), 'build'), (base, 'probe')):
                            unconfirmed = diagnose(other, role, ecosystem)
                            self.assertNotIn('native-check-failure=', unconfirmed['stdout'])

    def test_worker_refuses_root_or_shared_namespace_before_starting_any_external_program(self):
        worker = compile(self.ns['RUBY_WORKER'], '<Ruby sandbox worker>', 'exec')
        arguments = ['', 'probe', 'net:[1]', 'mnt:[2]', '/protected/ruby', VERSION]
        with mock.patch.object(self.ns['sys'], 'argv', arguments), \
                mock.patch.object(self.ns['subprocess'], 'run') as run:
            for uid, links, reason in ((0, [], 90), (61200, ['net:[1]'], 91),
                                       (61200, ['net:[3]', 'mnt:[2]'], 91)):
                with self.subTest(uid=uid, links=links), \
                        mock.patch.object(os, 'geteuid', return_value=uid, create=True), \
                        mock.patch.object(os, 'getegid', return_value=uid, create=True), \
                        mock.patch.object(os, 'readlink', side_effect=links), \
                        self.assertRaises(SystemExit) as error:
                    exec(worker, {})
                self.assertEqual(reason, error.exception.code)
        run.assert_not_called()

    def test_confirmed_manager_exit_identifies_fixed_worker_stage_without_raw_output(self):
        raw = self.ns['ruby_diagnostic']
        evidence = self.launcher_observation(98)
        observed = raw(evidence, 'build')
        self.assertEqual('ruby-build-source-configure', observed['stage'])
        self.assertEqual('systemd-run', observed['executable'])
        self.assertEqual(98, observed['actualExitCode'])
        self.assertEqual('', observed['stderr'])
        self.assertIn('worker-stage=source-configure\nworker-executable=configure\n', observed['stdout'])
        self.assertNotIn('sample-private-value', str(observed))
        self.assertTrue(observed['stderrTruncated'])
        self.assertEqual('ruby-probe-runtime-verification', raw(self.launcher_observation(103), 'probe')['stage'])

    def test_configure_distinguishes_start_errors_and_known_autoconf_failures(self):
        # Load only the worker declarations so the fixture never enters a sandbox or runs configure.
        source = ast.parse(self.ns['RUBY_WORKER'], '<Ruby sandbox worker>')
        source.body = source.body[:next(i for i, node in enumerate(source.body) if isinstance(node, ast.Try))]
        worker = {}
        exec(compile(source, '<Ruby sandbox worker>', 'exec'), worker)
        cases = (
            (OSError(errno.EACCES, 'private path'), b'', 104, 'start-permission-denied'),
            (OSError(errno.ENOENT, 'private path'), b'', 105, 'start-path-or-interpreter-missing'),
            (OSError(errno.ENOEXEC, 'private path'), b'', 106, 'start-executable-format'),
            (OSError(errno.EROFS, 'private path'), b'', 107, 'start-os-error'),
            (subprocess.CalledProcessError(1, 'private command'),
             b'configure: error: no acceptable C compiler found in $PATH', 108, 'c-compiler-missing'),
            (subprocess.CalledProcessError(77, 'private command'),
             b'configure: error: C compiler cannot create executables', 109, 'c-compiler-cannot-link'),
            (subprocess.CalledProcessError(77, 'private command'),
             b'configure: error: cannot run C compiled programs.', 110, 'compiled-program-cannot-run'),
            (subprocess.CalledProcessError(-9, 'private command'), b'', 111, 'configure-signalled'),
            (subprocess.CalledProcessError(1, 'private command'), b'private path: Permission denied',
             130, 'output-permission-denied'),
            (subprocess.CalledProcessError(1, 'private command'), b'private path: Operation not permitted',
             131, 'output-operation-not-permitted'),
            (subprocess.CalledProcessError(1, 'private command'), b'private path: Read-only file system',
             132, 'output-read-only-filesystem'),
            (subprocess.CalledProcessError(1, 'private command'), b'private path: No space left on device',
             133, 'output-storage-exhausted'),
            (subprocess.CalledProcessError(1, 'private command'), b'private payload', 134, 'configure-nonzero'),
            (subprocess.CalledProcessError(1, 'private command'),
             b'./configure: line 921: ./conftest: Operation not permitted',
             165, 'output-operation-not-permitted-conftest'),
            (subprocess.CalledProcessError(1, 'private command'),
             b'config.status: private-value: Operation not permitted',
             166, 'output-operation-not-permitted-config-status'),
            (subprocess.CalledProcessError(1, 'private command'),
             b'cp: private-value: Operation not permitted\nmv: private-value: Operation not permitted',
             171, 'output-permission-hints-ambiguous'),
        )
        for error, message, status, reason in cases:
            with self.subTest(status=status):
                with mock.patch.object(subprocess, 'Popen') as start, self.assertRaises(SystemExit) as observed:
                    if isinstance(error, OSError):
                        start.side_effect = error
                    else:
                        start.return_value.stdout = io.BytesIO(message + b'\n' + b'private payload\n' * 2000)
                        start.return_value.wait.return_value = error.returncode
                    worker['configure'](self.root, '/protected/ruby', [])
                self.assertEqual(subprocess.STDOUT, start.call_args.kwargs['stderr'])
                self.assertEqual(status, observed.exception.code)
                diagnostic = self.ns['ruby_diagnostic'](self.launcher_observation(status), 'build')
                self.assertEqual('ruby-build-source-configure', diagnostic['stage'])
                self.assertIn('configure-failure=' + reason + '\n', diagnostic['stdout'])
                self.assertNotIn('private', str(diagnostic))
        process = mock.MagicMock()
        process.stdout.readline.side_effect = OSError(errno.EIO, 'private pipe detail')
        with mock.patch.object(subprocess, 'Popen', return_value=process), self.assertRaises(SystemExit) as observed:
            worker['configure'](self.root, '/protected/ruby', [])
        self.assertEqual(135, observed.exception.code)

    def test_missing_ambiguous_or_mismatching_manager_evidence_stays_at_launcher_stage(self):
        convert = self.ns['ruby_diagnostic']
        for stdout, exit_code in (
            ('', 98),
            ('Finished with result: exit-code\nFinished with result: exit-code\n'
             'Main processes terminated with: code=exited/status=98\n', 98),
            ('Finished with result: exit-code\nMain processes terminated with: code=exited/status=1\n', 98),
            ('Finished with result: timeout\nMain processes terminated with: code=killed/status=TERM\n', 1),
            ('Finished with result: exit-code\nMain processes terminated with: code=exited/status=226\n', 226),
        ):
            with self.subTest(stdout=stdout, exit_code=exit_code):
                observation = dict(self.launcher_observation(exit_code), stdout=stdout, stderr='')
                diagnostic = convert(observation, 'build')
                self.assertEqual('ruby-build-launcher', diagnostic['stage'])
                self.assertNotIn('worker-stage=', diagnostic['stdout'])
                self.assertEqual(exit_code, diagnostic['actualExitCode'])
        cancelled = dict(self.launcher_observation(98), cancelled=True)
        self.assertEqual('ruby-build-launcher', convert(cancelled, 'build')['stage'])

    def test_worker_reports_probe_child_failure_without_echoing_arguments(self):
        source = ast.parse(self.ns['RUBY_WORKER'])
        index = next(i for i, node in enumerate(source.body) if isinstance(node, ast.Try)) - 1
        declarations = ast.Module(body=source.body[:index], type_ignores=[])
        state = {}
        exec(compile(declarations, '<Ruby sandbox worker declarations>', 'exec'), state)
        state['require_ruby_resources'] = mock.Mock()
        source.body = source.body[index:]
        worker = compile(source, '<Ruby sandbox worker>', 'exec')
        arguments = ['', 'probe', 'net:[1]', 'mnt:[2]', '/protected/ruby', VERSION,
                     'wtl-ruby-probe-fixture.service', '-', '-', '16777216', '256', '268435456']
        status = 'NoNewPrivs: 1\nSeccomp: 2\nCapEff: 0\nCapPrm: 0\nCapBnd: 0\n'
        child_failure = subprocess.CalledProcessError(2, ['sample-private-value'])
        with mock.patch.object(self.ns['sys'], 'argv', arguments), \
                mock.patch.object(os, 'geteuid', return_value=61200, create=True), \
                mock.patch.object(os, 'getegid', return_value=61200, create=True), \
                mock.patch.object(os, 'readlink', side_effect=['net:[3]', 'mnt:[4]']), \
                mock.patch.object(os, 'ST_RDONLY', 1, create=True), \
                mock.patch.object(os, 'statvfs', return_value=types.SimpleNamespace(f_flag=1), create=True), \
                mock.patch('builtins.open', mock.mock_open(read_data=status)), \
                mock.patch('socket.AF_UNIX', 1, create=True), \
                mock.patch('socket.socket', side_effect=PermissionError), \
                mock.patch.object(subprocess, 'run', side_effect=child_failure) as run, \
                self.assertRaises(SystemExit) as observed:
            exec(worker, state)
        self.assertEqual(103, observed.exception.code)
        self.assertNotIn('sample-private-value', str(observed.exception))
        self.assertEqual('/protected/ruby/bin/ruby', run.call_args.args[0][0])
        state['require_ruby_resources'].assert_called_once_with(*arguments[-6:])

    def test_protected_diagnose_only_keeps_ruby_evidence_and_restores_previous_recorder(self):
        namespace = self.ns
        layout = {'fixture': True}
        request = {'formatVersion': 1, 'operationId': OPERATION_ID,
                   'taskId': 'f68b21c0-57dc-4d5c-ae0d-5244c8c96f37', 'serverId': 'test-server',
                   'fingerprintSha256': DIGEST, 'planRevision': DIGEST, 'remoteLayout': layout,
                   'arguments': ['prepare', 'RUBY', '3.3', VERSION, '120', '-']}
        raw = json.dumps(request, sort_keys=True, separators=(',', ':')).encode('utf-8')
        request_digest = hashlib.sha256(raw).hexdigest()
        recorder = namespace['record_installation_diagnostic']
        failure = namespace['PreparationFailure']('installation', 'original launcher failure')

        def failed_launcher(command):
            namespace['record_installation_diagnostic'](self.launcher_observation(99))
            raise failure

        def prepare(arguments):
            self.assertEqual(request['arguments'], arguments)
            namespace['ruby_run'](['/usr/bin/env', 'systemd-run'], 'build')

        namespace.update(prepare_root=mock.Mock(), preparation_lock=lambda verifying: namespace['contextlib'].nullcontext(),
                         tracked_layout=mock.Mock(), prepare=prepare, run=failed_launcher,
                         tracked_regular=lambda path, limit: path.read_bytes())
        entry = namespace['installation_diagnostic_main']
        with self.assertRaises(namespace['PreparationFailure']) as observed:
            entry(['prepare-diagnosed', OPERATION_ID, request_digest], raw)
        self.assertIs(failure, observed.exception)
        self.assertIs(recorder, namespace['record_installation_diagnostic'])
        with mock.patch.object(namespace['sys'], 'stdout', new_callable=io.StringIO) as stdout:
            entry(['diagnose-only', OPERATION_ID, request_digest], raw)
        diagnostic = json.loads(stdout.getvalue())
        self.assertEqual(request_digest, diagnostic['requestSha256'])
        self.assertEqual('FAILED', diagnostic['preparationStatus'])
        self.assertEqual('installation', diagnostic['failureCategory'])
        self.assertEqual('ruby-build-source-build', diagnostic['stage'])
        self.assertIn('worker-executable=make\n', diagnostic['stdout'])
        self.assertNotIn('sample-private-value', stdout.getvalue())

    @staticmethod
    def launcher_observation(code):
        return {'stage': 'installation-command', 'executable': 'env', 'actualExitCode': code,
                'timedOut': False, 'cancelled': False, 'stdoutTruncated': False, 'stderrTruncated': True,
                'stdout': f'Finished with result: exit-code\nMain processes terminated with: code=exited/status={code}\n',
                'stderr': 'unstructured diagnostic sample-private-value\n'}

    @staticmethod
    def unallocated(name):
        raise KeyError(name)
