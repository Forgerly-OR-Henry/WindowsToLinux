"""Managed .NET marker and binding regressions without an SDK, network, or root."""
import base64
import contextlib
import hashlib
import io
import json
import os
import pathlib
import stat
import tarfile
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock


SOURCES = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain'
VERSION = '10.0.300'
URL = 'https://builds.dotnet.microsoft.com/dotnet/Sdk/10.0.300/dotnet-sdk-10.0.300-linux-x64.tar.gz'
FILES = {'dotnet': (b'official muxer', 0o755), 'host/fxr/10.0.5/libhostfxr.so': (b'official hostfxr', 0o644)}


class ManagedDotnetTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = pathlib.Path(work.name) / 'toolchains'
        self.root.mkdir()
        self.files = {}
        self.unsafe = {}
        self.ns = {'__name__': 'controlled_dotnet_test', '__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__': {},
                   '__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__': ['builds.dotnet.microsoft.com']}
        for name in ('10-release-metadata.py', 'archive-boundaries.py', '20-installation-boundaries.py',
                     '30-managed-installation.py', 'tracked-operations.py', '40-binding-protocol.py'):
            exec(compile((SOURCES / name).read_text(encoding='utf-8'), name, 'exec'), self.ns)
        self.real_probe = self.ns['managed_dotnet_probe_output']
        self.ns.update(ROOT=self.root, SHIPPED_CATALOG={'DOTNET': ['10']},
                       probe_output=mock.Mock(side_effect=AssertionError('privileged probe used')),
                       managed_dotnet_probe_output=mock.Mock(side_effect=self.list_sdks),
                       download=mock.Mock(side_effect=self.download))
        self.archive = self.sdk_archive()
        self.sha512 = hashlib.sha512(self.archive).hexdigest()
        self.sha256 = hashlib.sha256(self.archive).hexdigest()
        key = self.ns['installation_key']('DOTNET', VERSION, self.sha512)
        self.destination = self.root / 'versions' / ('dotnet-' + key)
        self.marker = self.destination / '.w2l-toolchain.json'
        self.probe = self.ns['managed_dotnet_probe_output']
        raw_stat, raw_open, raw_fstat, raw_replace = pathlib.Path.stat, os.open, os.fstat, os.replace

        def owned_info(path, info):
            mode = stat.S_IFMT(info.st_mode)
            if path.parent == self.root / 'work' and path.name.startswith('prepare-'):
                mode |= 0o700
            elif stat.S_ISDIR(mode) or path.name == 'dotnet':
                mode |= 0o755
            else:
                mode |= 0o644 if info.st_mode & stat.S_IWUSR else 0o444
            return SimpleNamespace(st_mode=self.unsafe.get(path, mode), st_uid=1234 if 'unpacked' in path.parts else 0,
                                   st_nlink=info.st_nlink,
                                   st_size=info.st_size, st_mtime_ns=info.st_mtime_ns, st_ctime_ns=info.st_ctime_ns,
                                   st_dev=info.st_dev, st_ino=info.st_ino)

        def owned_stat(path, *, follow_symlinks=True):
            return owned_info(path, raw_stat(path, follow_symlinks=follow_symlinks))

        def track_open(path, flags, *args, **kwargs):
            fd = raw_open(path, flags, *args, **kwargs)
            self.files[fd] = pathlib.Path(path)
            return fd

        def owned_fstat(fd):
            return owned_stat(self.files[fd]) if fd in self.files else raw_fstat(fd)

        def linux_replace(source, destination):
            # Linux permits replacing a read-only file in a writable parent directory.
            if destination == self.marker and self.marker.exists():
                self.marker.chmod(0o644)
            return raw_replace(source, destination)

        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(mock.patch.object(pathlib.Path, 'stat', owned_stat))
        stack.enter_context(mock.patch.object(os, 'open', track_open))
        stack.enter_context(mock.patch.object(os, 'fstat', owned_fstat))
        if os.name == 'nt':
            stack.enter_context(mock.patch.object(os, 'replace', linux_replace))
            self.ns['tracked_sync_directory'] = mock.Mock()

    @staticmethod
    def sdk_archive():
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            for directory in ('host', 'host/fxr', 'host/fxr/10.0.5', 'sdk', 'sdk/' + VERSION):
                info = tarfile.TarInfo(directory)
                info.type, info.mode = tarfile.DIRTYPE, 0o755
                archive.addfile(info)
            for name, (payload, mode) in FILES.items():
                info = tarfile.TarInfo(name)
                info.size, info.mode = len(payload), mode
                archive.addfile(info, io.BytesIO(payload))
        return buffer.getvalue()

    def download(self, url, archive, algorithm, digest):
        self.assertEqual((URL, 'sha512', self.sha512), (url, algorithm, digest))
        archive.write_bytes(self.archive)
        return self.sha256

    def list_sdks(self, command, directory):
        self.assertEqual(['/usr/bin/setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', '--no-new-privs',
                          '--inh-caps=-all', '--ambient-caps=-all', '--bounding-set=-all', '--',
                          str(directory / 'dotnet'), '--list-sdks'], command)
        return 0, VERSION + ' [' + str(directory / 'sdk') + ']\n'

    def install(self):
        return self.ns['install']('DOTNET', VERSION, URL, 'sha512', self.sha512, 'flat')

    def legacy_marker(self):
        record = self.install()
        record.pop('dotnet_probe_files')
        self.ns['tracked_atomic_json'](self.marker, record, 0o444)
        self.probe.reset_mock()
        self.ns['download'].reset_mock()
        return record

    def binding(self, origin='MANAGED'):
        fields = ['DOTNET', '-', '-', '-', '-', '-', VERSION, str(self.destination), origin,
                  base64.b64encode(URL.encode()).decode(), self.sha256]
        payload = ('WTL-TOOLS-1\ttest\n' + '\t'.join(fields) + '\n').encode()
        return fields, payload, base64.b64encode(payload).decode()

    def test_archive_pins_entry_hostfxr_and_reuses_the_binding_without_download(self):
        record = self.install()
        self.assertEqual(self.sha256, record['sha256'])
        self.assertEqual({path: hashlib.sha256(data).hexdigest() for path, (data, _) in FILES.items()},
                         record['dotnet_probe_files'])
        self.assertEqual(record, self.install())
        self.ns['download'].assert_called_once()
        _, payload, encoded = self.binding()
        self.assertEqual(payload, self.ns['validated_binding'](encoded))
        self.assertEqual(3, self.probe.call_count)
        self.ns['probe_output'].assert_not_called()

    def test_bind_checks_current_file_bytes_before_running_the_probe(self):
        for relative in FILES:
            with self.subTest(relative=relative):
                if not self.marker.exists():
                    self.install()
                tool = self.destination / relative
                original = tool.read_bytes()
                tool.write_bytes(b'replaced')
                self.probe.reset_mock()
                with self.assertRaisesRegex(self.ns['PreparationFailure'], 'differ from the verified archive'):
                    self.ns['validated_binding'](self.binding()[2])
                self.probe.assert_not_called()
                tool.write_bytes(original)

    def test_bind_rejects_unsafe_entry_modes_and_origin_before_the_probe(self):
        self.install()
        self.probe.reset_mock()
        for mode in (stat.S_IFREG | 0o777, stat.S_IFREG | 0o4755, stat.S_IFLNK | 0o755):
            with self.subTest(mode=mode):
                self.unsafe[self.destination / 'dotnet'] = mode
                with self.assertRaisesRegex(self.ns['PreparationFailure'], 'protected regular files'):
                    self.ns['validated_binding'](self.binding()[2])
        self.unsafe.clear()
        with self.assertRaisesRegex(self.ns['PreparationFailure'], 'wrong origin'):
            self.ns['validated_binding'](self.binding('SYSTEM')[2])
        self.probe.assert_not_called()

    def test_legacy_marker_requires_verified_prepare_and_preserves_archive_binding(self):
        record = self.legacy_marker()
        marker_before = self.marker.read_bytes()
        fields, _, encoded = self.binding()
        with self.assertRaisesRegex(self.ns['PreparationFailure'], 'prepared again'):
            self.ns['validated_binding'](encoded)
        self.assertIsNone(self.ns['existing_installation'](fields))
        self.assertEqual(marker_before, self.marker.read_bytes())
        self.probe.assert_not_called()
        updated = self.install()
        self.ns['download'].assert_called_once()
        self.assertEqual(record, {key: value for key, value in updated.items() if key != 'dotnet_probe_files'})
        self.assertEqual(updated, json.loads(self.marker.read_bytes()))
        self.assertEqual(self.binding()[1], self.ns['validated_binding'](encoded))

    def test_legacy_prepare_cannot_pin_replaced_installed_bytes(self):
        self.legacy_marker()
        marker_before = self.marker.read_bytes()
        (self.destination / 'dotnet').write_bytes(b'existing replacement with an expected version string')
        with self.assertRaisesRegex(self.ns['PreparationFailure'], 'differ from the verified archive'):
            self.install()
        self.ns['download'].assert_called_once()
        self.probe.assert_not_called()
        self.assertEqual(marker_before, self.marker.read_bytes())

    def test_probe_disallows_same_sdk_token_in_another_directory(self):
        self.install()
        self.probe.side_effect = None
        self.probe.return_value = 0, VERSION + ' [/usr/share/dotnet/sdk]\n'
        with self.assertRaisesRegex(self.ns['PreparationFailure'], 'selected exact SDK and directory'):
            self.ns['validated_binding'](self.binding()[2])

    def test_verify_rehashes_without_executing_the_sdk(self):
        self.install()
        self.probe.reset_mock()
        _, payload, _ = self.binding()
        digest = hashlib.sha256(payload).hexdigest()
        target = self.root / 'bindings' / digest
        target.parent.mkdir()
        target.write_bytes(payload)
        target.chmod(0o444)
        self.ns['preparation_lock'] = lambda verifying: contextlib.nullcontext()
        with mock.patch.object(os, 'geteuid', return_value=0, create=True):
            self.ns['main'](['verify', digest])
            (self.destination / 'dotnet').write_bytes(b'replaced')
            with self.assertRaisesRegex(self.ns['PreparationFailure'], 'differ from the verified archive'):
                self.ns['main'](['verify', digest])
        self.probe.assert_not_called()

    def test_probe_launcher_has_clean_environment_and_a_fixed_output_budget(self):
        proc = mock.MagicMock()
        proc.__enter__.return_value = proc
        proc.wait.return_value = 0
        proc.stdout.fileno.return_value = 37
        proc.pid = 123
        selected = mock.MagicMock()
        selected.__enter__.return_value = selected
        selected.select.return_value = [object()]
        command = ['/fake/setpriv', 'fixed-argv']
        with mock.patch.object(self.ns['subprocess'], 'Popen', return_value=proc) as spawn, \
                mock.patch.object(self.ns['selectors'], 'DefaultSelector', return_value=selected), \
                mock.patch.object(os, 'read', side_effect=[b'x' * 8192, b'x']) as read, \
                mock.patch.object(self.ns['signal'], 'SIGKILL', 9, create=True), \
                mock.patch.object(os, 'killpg', create=True) as kill:
            with self.assertRaisesRegex(self.ns['PreparationFailure'], 'output budget'):
                self.real_probe(command, self.destination)
        self.assertEqual([mock.call(37, 8193), mock.call(37, 1)], read.call_args_list)
        self.assertEqual(command, spawn.call_args.args[0])
        options = spawn.call_args.kwargs
        self.assertEqual(self.destination, options['cwd'])
        self.assertEqual({'PATH': '/usr/bin:/bin', 'LC_ALL': 'C', 'HOME': '/nonexistent',
                          'DOTNET_CLI_HOME': '/nonexistent', 'DOTNET_ROOT': str(self.destination),
                          'DOTNET_MULTILEVEL_LOOKUP': '0', 'DOTNET_CLI_TELEMETRY_OPTOUT': '1'}, options['env'])
        self.assertTrue(options['close_fds'])
        self.assertNotIn('pass_fds', options)
        kill.assert_called_once_with(proc.pid, 9)
        proc.wait.assert_called_once_with()


if __name__ == '__main__':
    unittest.main()
