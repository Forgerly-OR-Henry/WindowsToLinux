"""Exercise the formal Ruby recipe without starting a service or compiling Ruby."""
import os
import pathlib
import shutil
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
        stage, source, _, _ = mounts
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
        self.assertTrue((operation_dir / 'work/stage').is_dir())
        self.assertTrue((self.destination() / '.w2l-operation-owner').is_file())
        self.assertFalse((self.destination() / '.w2l-toolchain.json').exists())
        self.ns['ruby_publish'].assert_not_called()
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
                      (stage, self.root / 'ruby.tar.gz', self.root / 'openssl-3.0.3.gem', self.root / 'build.log'))
        argv = raw['run'].call_args.args[0]
        self.assertIn('--wait', argv)
        self.assertIn('--property=DynamicUser=yes', argv)
        self.assertIn('--property=NoNewPrivileges=yes', argv)
        self.assertIn('--property=PrivateNetwork=yes', argv)
        self.assertIn('--property=ProtectSystem=strict', argv)
        self.assertIn('--property=RestrictAddressFamilies=AF_INET AF_INET6 AF_NETLINK', argv)
        self.assertTrue(any(item.startswith('--property=BindReadOnlyPaths=') and ':/tmp/openssl-3.0.3.gem' in item for item in argv))
        worker_args = argv[argv.index('--') + 1:]
        self.assertEqual(['/usr/bin/python3', '-I', '-S', '-B', '-c'], worker_args[:5])
        compile(worker_args[5], '<Ruby sandbox worker>', 'exec')
        self.assertEqual(['build', 'net:[11]', 'mnt:[12]', '/usr/local/lib/windowstolinux/toolchains/versions/ruby-selected',
                          VERSION, str(stage.stat().st_dev), str(stage.stat().st_ino), 'legacy'], worker_args[6:])
        raw['ruby_require_settled'].assert_called_once_with(command)

    def test_worker_refuses_root_or_shared_namespace_before_starting_any_external_program(self):
        worker = compile(self.ns['RUBY_WORKER'], '<Ruby sandbox worker>', 'exec')
        arguments = ['', 'probe', 'net:[1]', 'mnt:[2]', '/protected/ruby', VERSION]
        with mock.patch.object(self.ns['sys'], 'argv', arguments), \
                mock.patch.object(self.ns['subprocess'], 'run') as run:
            for uid, links, reason in ((0, [], 'unprivileged dynamic identity'),
                                       (61200, ['net:[1]'], 'private network and mount namespaces'),
                                       (61200, ['net:[3]', 'mnt:[2]'], 'private network and mount namespaces')):
                with self.subTest(uid=uid, links=links), \
                        mock.patch.object(os, 'geteuid', return_value=uid, create=True), \
                        mock.patch.object(os, 'getegid', return_value=uid, create=True), \
                        mock.patch.object(os, 'readlink', side_effect=links), \
                        self.assertRaisesRegex(SystemExit, reason):
                    exec(worker, {})
        run.assert_not_called()

    @staticmethod
    def unallocated(name):
        raise KeyError(name)
