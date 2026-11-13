"""Exercise preparation recipes without package, network, or compiler execution."""
import hashlib
import pathlib
import tempfile
import unittest
from unittest import mock


SOURCES = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain'


class ToolchainPreparationBehaviorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name) / 'toolchains'
        self.root.mkdir()
        self.namespace = {'__name__': 'controlled_preparation_test', '__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__': {},
                          '__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__': ['example.invalid']}
        for name in ('10-release-metadata.py', 'archive-boundaries.py', '20-installation-boundaries.py', '30-managed-installation.py'):
            source = (SOURCES / name).read_text()
            # The bundle injects distribution repository policy here; these tests exercise package selection only.
            source = source.replace('# @compat:centos-repositories@', 'repositories = []')
            exec(compile(source, name, 'exec'), self.namespace)
        self.namespace['ROOT'] = self.root
        self.namespace['owned_directory'] = mock.Mock(side_effect=self.controlled_directory)
        self.commands = []
        self.namespace['run'] = mock.Mock(side_effect=lambda command, *args, **kwargs: self.commands.append(command))
        self.namespace['probe_output'] = mock.Mock(return_value=(0, '8.3.35'))
        self.namespace['download'] = mock.Mock(return_value='a' * 64)
        self.namespace['extract'] = mock.Mock(side_effect=self.controlled_extract)
        self.namespace['seal_installation'] = mock.Mock()

    def controlled_directory(self, path):
        self.assertFalse(path.is_symlink())
        self.assertTrue(path == self.root or self.root in path.parents)

    def controlled_extract(self, archive, unpacked):
        (unpacked / 'php-8.3.35').mkdir()

    def install(self):
        return self.namespace['install']('PHP', '8.3.35', 'https://example.invalid/php.tar.gz', 'sha256', 'a' * 64, 'php')

    def destination(self):
        key = self.namespace['installation_key']('PHP', '8.3.35', 'a' * 64)
        return self.root / 'versions' / ('php-' + key)

    def test_php_dependencies_are_selected_for_both_supported_package_installers(self):
        for installer, dependency in (('apt-get', 'libzip-dev'), ('dnf', 'libzip-devel')):
            with self.subTest(installer=installer):
                self.commands.clear()
                with mock.patch.object(pathlib.Path, 'is_file', lambda path: path.as_posix() == '/usr/bin/' + installer):
                    self.namespace['source_dependencies']('php')
                self.assertEqual(1, len(self.commands))
                self.assertEqual('/usr/bin/' + installer, self.commands[0][0])
                self.assertIn(dependency, self.commands[0])
                self.assertIn('install', self.commands[0])

    def test_apt_lock_wait_uses_the_current_preparation_deadline(self):
        for layout in ('python', 'php', 'ruby'):
            for seconds in (630.75, 35.75):
                with self.subTest(layout=layout, seconds=seconds):
                    self.commands.clear()
                    self.namespace['DEADLINE'] = 100 + seconds
                    with mock.patch.object(self.namespace['time'], 'monotonic', return_value=100), \
                            mock.patch.object(pathlib.Path, 'is_file', lambda path: path.as_posix() == '/usr/bin/apt-get'):
                        self.namespace['source_dependencies'](layout)
                    self.assertEqual(1, len(self.commands))
                    self.assertIn('DPkg::Lock::Timeout=' + str(int(seconds)), self.commands[0])
                    self.assertEqual(100 + seconds, self.namespace['DEADLINE'])

    def test_expired_deadline_does_not_start_a_dependency_installer(self):
        self.namespace['DEADLINE'] = 100
        with mock.patch.object(self.namespace['time'], 'monotonic', return_value=100), \
                mock.patch.object(pathlib.Path, 'is_file', lambda path: path.as_posix() == '/usr/bin/apt-get'), \
                mock.patch.object(self.namespace['subprocess'], 'Popen') as spawn:
            with self.assertRaises(self.namespace['PreparationFailure']) as raised:
                self.namespace['source_dependencies']('python')
        self.assertEqual('timeout', raised.exception.category)
        self.namespace['run'].assert_not_called()
        spawn.assert_not_called()

    def test_install_does_not_reuse_the_existing_php_installation_without_zip(self):
        old_key = hashlib.sha256(('PHP\n8.3.35\n' + 'a' * 64 + '\nphp-cli-iconv-v1').encode()).hexdigest()
        old_directory = self.root / 'versions' / ('php-' + old_key)
        old_directory.mkdir(parents=True)
        old_marker = old_directory / '.w2l-toolchain.json'
        old_marker.write_text('retained installation without zip')
        self.namespace['source_dependencies'] = mock.Mock()
        record = self.install()
        directory = pathlib.Path(record['directory'])
        self.assertNotEqual(old_directory, directory)
        self.assertEqual('retained installation without zip', old_marker.read_text())
        self.namespace['download'].assert_called_once()
        self.assertIn('--with-zip', self.commands[0])
        self.assertTrue((directory / '.w2l-toolchain.json').is_file())
        self.namespace['seal_installation'].assert_called_once_with(directory)

    def test_php_module_validation_runs_before_the_completion_marker_is_written(self):
        self.namespace['source_dependencies'] = mock.Mock()

        def command(command, *args, **kwargs):
            self.commands.append(command)
            if command[1:2] == ['-r']:
                directory = pathlib.Path(command[0]).parents[1]
                self.assertFalse((directory / '.w2l-toolchain.json').exists())
                self.assertIn('"zip"', command[2])
                self.assertIn('extension_loaded', command[2])

        self.namespace['run'].side_effect = command
        self.install()
        self.assertEqual(['-r'], self.commands[-1][1:2])
        self.assertEqual(4, len(self.commands))

    def test_python_version_and_module_checks_use_isolated_interpreters(self):
        executable = str(self.root / 'bin/python3')
        self.namespace['probe_output'].return_value = (0, 'Python 3.12.10')
        self.namespace['probe']('PYTHON', self.root, '3.12.10')
        self.namespace['probe_output'].assert_called_once_with([executable, '-I', '-S', '-B', '--version'])
        self.namespace['run'].assert_called_once_with(
            [executable, '-I', '-S', '-B', '-c', 'import ssl, sqlite3, bz2, lzma, venv, zlib'])

    def test_failed_module_validation_removes_the_unadmitted_installation(self):
        self.namespace['source_dependencies'] = mock.Mock()

        def command(command, *args, **kwargs):
            if command[1:2] == ['-r']:
                self.namespace['fail']('installation', 'controlled missing PHP extension')

        self.namespace['run'].side_effect = command
        with self.assertRaises(self.namespace['PreparationFailure']):
            self.install()
        self.assertEqual([], list((self.root / 'versions').iterdir()))
        self.namespace['seal_installation'].assert_not_called()

    def test_existing_unmarked_managed_and_system_destinations_are_preserved(self):
        destination = self.destination()
        destination.mkdir(parents=True)
        retained = destination / 'retained.txt'
        retained.write_text('keep original content')
        system = {'ecosystem': 'PHP', 'directory': str(destination), 'system_files': [], '_home': str(self.root)}
        for install in (self.install, lambda: self.namespace['materialize_system'](system)):
            with self.subTest(install=install), self.assertRaises(self.namespace['PreparationFailure']) as failure:
                install()
            self.assertEqual('ownership', failure.exception.category)
            self.assertEqual('keep original content', retained.read_text())
            self.assertEqual([retained], list(destination.iterdir()))
        self.namespace['download'].assert_not_called()
        self.namespace['run'].assert_not_called()

    def test_destination_created_while_downloading_is_preserved(self):
        destination = self.destination()
        retained = destination / 'retained.txt'
        def download(*args):
            destination.mkdir(parents=True)
            retained.write_text('keep concurrent content')
            return 'a' * 64
        self.namespace['download'].side_effect = download
        with self.assertRaises(FileExistsError):
            self.install()
        self.assertEqual('keep concurrent content', retained.read_text())
        self.assertEqual([retained], list(destination.iterdir()))
        self.namespace['run'].assert_not_called()


if __name__ == '__main__':
    unittest.main()
