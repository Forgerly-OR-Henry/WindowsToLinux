"""Run the actual toolchain entry point with controlled root ownership and flock boundaries."""
import base64
import contextlib
import hashlib
import io
import json
import os
import pathlib
import stat
import tempfile
import types
import unittest
from unittest import mock


SOURCES = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain'


class ToolchainVerificationBehaviorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name) / 'toolchains'
        self.directory = self.root / 'versions' / ('java-' + 'a' * 64)
        self.directory.mkdir(parents=True)
        self.lock = self.root / '.prepare.lock'
        self.lock.write_text('original lock\n')
        self.lock.chmod(0o444)
        self.record = {'ecosystem': 'JAVA', 'version': '21.0.1', 'directory': str(self.directory),
                       'origin': 'MANAGED', 'source': 'https://example.invalid/toolchain', 'sha256': 'a' * 64}
        self.marker = self.directory / '.w2l-toolchain.json'
        self.marker.write_text(json.dumps(self.record))
        fields = ['JAVA', 'RUNTIME', '21', '-', '-', '-', '21.0.1', str(self.directory),
                  'MANAGED', base64.b64encode(b'https://example.invalid/toolchain').decode(), 'a' * 64]
        payload = ('WTL-TOOLS-1\tTEST\n' + '\t'.join(fields) + '\n').encode()
        self.identity = hashlib.sha256(payload).hexdigest()
        self.binding = self.root / 'bindings' / self.identity
        self.binding.parent.mkdir()
        self.binding.write_bytes(payload)
        self.binding.chmod(0o444)
        self.namespace = {'__name__': 'controlled_toolchain_test'}
        for name in ('10-release-metadata.py', 'archive-boundaries.py', '20-installation-boundaries.py',
                     '30-managed-installation.py', '40-binding-protocol.py'):
            exec(compile((SOURCES / name).read_text(), name, 'exec'), self.namespace)
        self.namespace['ROOT'] = self.root
        self.namespace['owned_directory'] = mock.Mock(side_effect=self.controlled_directory)
        self.fcntl = types.SimpleNamespace(LOCK_SH=1, LOCK_EX=2, LOCK_NB=4, flock=mock.Mock())
        self.lock_uid = 0
        self.lock_permissions = None
        self.marker_uid = 0
        self.marker_permissions = 0o444

    def controlled_directory(self, path):
        # NTFS and non-root CI ownership differ; filesystem reads/writes and digest checks remain real.
        self.assertFalse(path.is_symlink())
        self.assertTrue(path == self.root or self.root in path.parents)

    def snapshot(self):
        return {str(path.relative_to(self.root)): (path.stat().st_mode, path.stat().st_mtime_ns,
                path.read_bytes() if path.is_file() else None)
                for path in [self.root] + sorted(self.root.rglob('*'))}

    def invoke(self, arguments, readonly=True):
        actual_stat = pathlib.Path.stat
        actual_open = io.open

        def controlled_stat(path, *args, **kwargs):
            value = actual_stat(path, *args, **kwargs)
            if self.root == path or self.root in path.parents:
                fields = list(value)
                fields[4] = self.lock_uid if path == self.lock else 0
                if path == self.lock and self.lock_permissions is not None:
                    fields[0] = stat.S_IFREG | self.lock_permissions
                if path == self.marker:
                    fields[4] = self.marker_uid
                    fields[0] = stat.S_IFREG | self.marker_permissions
                return os.stat_result(fields)
            return value

        def readonly_open(file, mode='r', *args, **kwargs):
            self.assertFalse(any(flag in mode for flag in 'wax+'), 'Verification must not open for writing')
            return actual_open(file, mode, *args, **kwargs)

        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.dict('sys.modules', {'fcntl': self.fcntl}))
            stack.enter_context(mock.patch.object(os, 'geteuid', return_value=0, create=True))
            stack.enter_context(mock.patch.object(pathlib.Path, 'stat', controlled_stat))
            if readonly:
                stack.enter_context(mock.patch.object(io, 'open', readonly_open))
                stack.enter_context(mock.patch.object(pathlib.Path, 'mkdir', side_effect=AssertionError('Verification mkdir')))
                stack.enter_context(mock.patch.object(pathlib.Path, 'chmod', side_effect=AssertionError('Verification chmod')))
            self.namespace['main'](arguments)

    def test_existing_binding_verifies_without_filesystem_writes_and_uses_shared_lock(self):
        before = self.snapshot()
        self.invoke(['verify', self.identity])
        self.assertEqual(before, self.snapshot())
        self.fcntl.flock.assert_called_once()
        self.assertEqual(self.fcntl.LOCK_SH | self.fcntl.LOCK_NB, self.fcntl.flock.call_args.args[1])
        for directory in (self.root, self.root / 'versions', self.binding.parent, self.directory):
            self.namespace['owned_directory'].assert_any_call(directory)

    def test_missing_lock_is_not_created(self):
        self.lock.chmod(0o644)
        self.lock.unlink()
        before = self.snapshot()
        with self.assertRaises(self.namespace['PreparationFailure']) as failure:
            self.invoke(['verify', self.identity])
        self.assertEqual('ownership', failure.exception.category)
        self.assertEqual(before, self.snapshot())
        self.fcntl.flock.assert_not_called()

    def test_missing_prepared_root_is_not_created(self):
        self.namespace['ROOT'] = self.root / 'absent'
        with self.assertRaises(self.namespace['PreparationFailure']) as failure:
            self.invoke(['verify', self.identity])
        self.assertEqual('integrity', failure.exception.category)
        self.assertFalse(self.namespace['ROOT'].exists())
        self.fcntl.flock.assert_not_called()

    def test_invalid_verify_arguments_fail_before_any_preparation(self):
        before = self.snapshot()
        for arguments in (['verify'], ['verify', 'invalid'], ['verify', self.identity, 'extra']):
            with self.subTest(arguments=arguments), self.assertRaises(self.namespace['PreparationFailure']) as failure:
                self.invoke(arguments)
            self.assertEqual('request', failure.exception.category)
        self.assertEqual(before, self.snapshot())
        self.fcntl.flock.assert_not_called()

    def test_writable_or_foreign_owned_lock_is_rejected_before_open(self):
        before = self.snapshot()
        for owner, permissions in ((0, 0o666), (1000, 0o444)):
            with self.subTest(owner=owner, permissions=permissions):
                self.lock_uid, self.lock_permissions = owner, permissions
                with self.assertRaises(self.namespace['PreparationFailure']) as failure:
                    self.invoke(['verify', self.identity])
                self.assertEqual('ownership', failure.exception.category)
        self.assertEqual(before, self.snapshot())
        self.fcntl.flock.assert_not_called()

    def test_changed_binding_digest_still_fails_without_writes(self):
        self.binding.chmod(0o644)
        self.binding.write_bytes(b'changed binding')
        self.binding.chmod(0o444)
        before = self.snapshot()
        with self.assertRaises(self.namespace['PreparationFailure']) as failure:
            self.invoke(['verify', self.identity])
        self.assertEqual('integrity', failure.exception.category)
        self.assertEqual(before, self.snapshot())

    def test_changed_pinned_system_tool_still_fails_without_writes(self):
        executable = self.directory / 'java'
        executable.write_bytes(b'changed executable')
        executable.chmod(0o444)
        self.record.update(origin='SYSTEM', system_files=[
            {'path': str(executable), 'sha256': hashlib.sha256(b'original executable').hexdigest()}])
        self.marker.write_text(json.dumps(self.record))
        payload = self.binding.read_bytes().replace(b'\tMANAGED\t', b'\tSYSTEM\t')
        self.identity = hashlib.sha256(payload).hexdigest()
        self.binding = self.binding.parent / self.identity
        self.binding.write_bytes(payload)
        self.binding.chmod(0o444)
        before = self.snapshot()
        with self.assertRaises(self.namespace['PreparationFailure']) as failure:
            self.invoke(['verify', self.identity])
        self.assertEqual('integrity', failure.exception.category)
        self.assertEqual(before, self.snapshot())

    def test_changed_completion_identity_fails_without_writes(self):
        for key in ('ecosystem', 'version', 'directory', 'origin', 'source', 'sha256'):
            with self.subTest(field=key):
                self.marker.write_text(json.dumps(dict(self.record, **{key: 'changed'})))
                before = self.snapshot()
                with self.assertRaises(self.namespace['PreparationFailure']) as failure:
                    self.invoke(['verify', self.identity])
                self.assertEqual('integrity', failure.exception.category)
                self.assertEqual(before, self.snapshot())

    def test_missing_completion_marker_fails_without_writes(self):
        self.marker.unlink()
        before = self.snapshot()
        with self.assertRaises(self.namespace['PreparationFailure']) as failure:
            self.invoke(['verify', self.identity])
        self.assertEqual('integrity', failure.exception.category)
        self.assertEqual(before, self.snapshot())

    def test_writable_or_foreign_owned_completion_marker_fails_without_writes(self):
        before = self.snapshot()
        for owner, permissions in ((0, 0o666), (1000, 0o444)):
            with self.subTest(owner=owner, permissions=permissions):
                self.marker_uid, self.marker_permissions = owner, permissions
                with self.assertRaises(self.namespace['PreparationFailure']) as failure:
                    self.invoke(['verify', self.identity])
                self.assertEqual('ownership', failure.exception.category)
        self.assertEqual(before, self.snapshot())

    def test_bind_keeps_preparation_and_exclusive_write_lock(self):
        self.lock.chmod(0o644)
        self.lock.unlink()
        binder = self.namespace['bind'] = mock.Mock()
        preparation = self.namespace['prepare_shared_directories'] = mock.Mock(
            wraps=self.namespace['prepare_shared_directories'])
        self.invoke(['bind', 'original-payload'], readonly=False)
        preparation.assert_called_once_with()
        binder.assert_called_once_with('original-payload')
        self.assertTrue(self.lock.is_file())
        self.assertEqual(self.fcntl.LOCK_EX | self.fcntl.LOCK_NB, self.fcntl.flock.call_args.args[1])


if __name__ == '__main__':
    unittest.main()
