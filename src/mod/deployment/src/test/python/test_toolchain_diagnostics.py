"""Original-operation installation diagnostics without network, packages, or server writes."""
import contextlib
import hashlib
import io
import json
import os
import pathlib
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest import mock


SOURCES = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain'
LAYOUT = '{"formatVersion":1,"remote":{"helperDirectory":"/usr/local/lib/windowstolinux","applicationsRoot":"/opt/windowstolinux/apps","stateRoot":"/var/lib/windowstolinux","configurationRoot":"/etc/opt/windowstolinux/apps","dataRoot":"/var/opt/windowstolinux/apps"}}'


class ToolchainDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name) / 'toolchains'
        self.ns = {'__name__': 'controlled_diagnostics'}
        for name in ('10-release-metadata.py', 'archive-boundaries.py', '20-installation-boundaries.py', 'tracked-operations.py'):
            source = (SOURCES / name).read_text().replace('# @compat:centos-repositories@', 'repositories = []')
            exec(compile(source, name, 'exec'), self.ns)
        self.ns['ROOT'] = self.root
        self.ns['tracked_sync_directory'] = mock.Mock()
        self.ns['tracked_layout'] = mock.Mock()
        self.ns['owned_directory'] = mock.Mock(side_effect=self.owned)
        self.ns['prepare_shared_directories'] = lambda: self.root.mkdir(exist_ok=True)
        self.request = {'formatVersion': 1, 'operationId': str(uuid.uuid4()), 'taskId': str(uuid.uuid4()),
                        'serverId': 'fixture-server', 'fingerprintSha256': 'a' * 64, 'planRevision': 'b' * 64,
                        'remoteLayout': LAYOUT, 'arguments': ['prepare', 'PHP', '8.3', '-', '120', '-']}
        self.raw = json.dumps(self.request, separators=(',', ':')).encode()
        self.sha = hashlib.sha256(self.raw).hexdigest()

    def owned(self, path):
        self.assertTrue(path == self.root or self.root in path.parents)
        self.assertFalse(path.is_symlink())

    def controlled_stat(self, original):
        values = list(original)
        values[4] = 0
        values[0] = (values[0] & ~0o777) | (0o700 if stat.S_ISDIR(values[0]) else 0o600)
        return os.stat_result(values)

    def invoke(self, verb, raw=None):
        raw = self.raw if raw is None else raw
        actual = pathlib.Path.lstat
        output = io.StringIO()
        with mock.patch.object(pathlib.Path, 'lstat', lambda path: self.controlled_stat(actual(path))), contextlib.redirect_stdout(output):
            self.ns['installation_diagnostic_main']([verb, self.request['operationId'], hashlib.sha256(raw).hexdigest()], raw)
        return output.getvalue()

    def run_process(self, stdout, stderr, code=37, timeout=False):
        process = mock.Mock(pid=4321, returncode=code)
        process.stdout, process.stderr = io.BytesIO(stdout), io.BytesIO(stderr)
        process.wait.side_effect = [subprocess.TimeoutExpired('apt-get', 1), code] if timeout else [code, code]
        with mock.patch.object(subprocess, 'Popen', return_value=process) as spawn, \
                mock.patch.object(os, 'killpg', create=True) as kill, mock.patch.object(signal, 'SIGKILL', 9, create=True):
            expected = subprocess.TimeoutExpired if timeout else self.ns['PreparationFailure']
            with self.assertRaises(expected) as raised:
                self.ns['run'](['/usr/bin/apt-get', 'install', 'fixed-package'])
        kill.assert_called_once_with(4321, 9)
        self.assertEqual(2, process.wait.call_count)
        self.assertEqual(subprocess.PIPE, spawn.call_args.kwargs['stdout'])
        self.assertEqual(subprocess.PIPE, spawn.call_args.kwargs['stderr'])
        raise raised.exception

    def test_real_exit_stage_redaction_and_independent_tail_bounds_are_persisted(self):
        def preparation(arguments):
            self.assertEqual(self.request['arguments'], arguments)
            self.run_process((('status line ' + 'x' * 150 + '\n') * 220).encode() + b'API_KEY=topsecret\n',
                             b'password: hidden\nhttps://user:pass@example.invalid/a?token=anothersecret\n'
                             + ('\u9519\u8bef\n' * 9000).encode() + b'-----BEGIN PRIVATE KEY-----\nprivatebody\n-----END PRIVATE KEY-----\n')
            self.ns['fail']('installation', 'controlled original failure')
        self.ns['main'] = preparation
        with self.assertRaises(self.ns['PreparationFailure']):
            self.invoke('prepare-diagnosed')
        diagnostic = json.loads(self.invoke('diagnose-only'))
        self.assertEqual('FAILED', diagnostic['preparationStatus'])
        self.assertEqual(37, diagnostic['actualExitCode'])
        self.assertEqual('system-dependencies', diagnostic['stage'])
        self.assertTrue(diagnostic['stdoutTruncated'])
        self.assertTrue(diagnostic['stderrTruncated'])
        for field in ('stdout', 'stderr'):
            self.assertLessEqual(len(diagnostic[field].encode()), 16384)
        self.assertNotIn('topsecret', str(diagnostic))
        self.assertNotIn('privatebody', str(diagnostic))
        self.assertEqual(self.sha, diagnostic['requestSha256'])
        self.assertEqual(self.request['operationId'], diagnostic['operationId'])
        self.assertFalse(diagnostic['timedOut'])
        self.assertIsNone(self.ns['INSTALLATION_DIAGNOSTIC'])
        # Re-reading never calls the preparation implementation or emits the old preparation receipt.
        self.ns['main'] = mock.Mock(side_effect=AssertionError('must not prepare'))
        self.assertEqual(diagnostic, json.loads(self.invoke('diagnose-only')))
        self.ns['main'].assert_not_called()

    def test_timeout_kills_and_reaps_owned_process_before_retaining_actual_exit(self):
        self.ns['main'] = lambda args: self.run_process(b'partial stdout\n', b'actual timeout detail\n', -9, True)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.invoke('prepare-diagnosed')
        diagnostic = json.loads(self.invoke('diagnose-only'))
        self.assertEqual(-9, diagnostic['actualExitCode'])
        self.assertTrue(diagnostic['timedOut'])
        self.assertEqual('timeout', diagnostic['failureCategory'])
        self.assertEqual('partial stdout\n', diagnostic['stdout'])

    def test_real_child_exits_after_timeout_and_after_cancellation(self):
        # Linux exercises the real process-group primitive. Windows adapts only killpg to this new owned child.
        actual_spawn = subprocess.Popen
        for cancelled in (False, True):
            with self.subTest(cancelled=cancelled):
                children = []
                def spawn(*args, **kwargs):
                    child = actual_spawn(*args, **kwargs)
                    children.append(child)
                    return child
                def windows_kill(pid, sig):
                    self.assertEqual(children[0].pid, pid)
                    children[0].kill()
                context = mock.patch.object(os, 'killpg', side_effect=windows_kill, create=True) if os.name == 'nt' else contextlib.nullcontext()
                if cancelled:
                    remaining = mock.Mock(side_effect=lambda: self.ns['fail']('cancelled', 'controlled cancellation'))
                else:
                    self.ns['DEADLINE'] = time.monotonic() + 1.9
                    remaining = self.ns['remaining']
                recorder = mock.Mock()
                with mock.patch.object(subprocess, 'Popen', side_effect=spawn), context, \
                        mock.patch.object(signal, 'SIGKILL', 9, create=True), \
                        mock.patch.dict(self.ns, {'remaining': remaining, 'record_installation_diagnostic': recorder}):
                    expected = self.ns['PreparationFailure'] if cancelled else subprocess.TimeoutExpired
                    with self.assertRaises(expected):
                        self.ns['run']([sys.executable, '-c', 'import sys,time; print("owned child ready",flush=True); time.sleep(30)'])
                self.assertIsNotNone(children[0].poll(), 'The actual owned process must exit before run returns')
                self.assertIsNotNone(children[0].returncode)
                value = recorder.call_args.args[0]
                self.assertEqual(children[0].returncode, value['actualExitCode'])
                self.assertEqual(cancelled, value['cancelled'])
                self.assertEqual(not cancelled, value['timedOut'])

    def test_small_secrets_and_long_lines_are_redacted_before_tail_retention(self):
        tail = self.ns['InstallationOutputTail']()
        tail.drain(io.BytesIO(b'Authorization: Bearer hidden\npassword=othersecret\nhttps://u:p@host.invalid/?api_key=urlsecret\n'
                              b'{"password":"jsonsecret","authorization":"Bearer jsonbearer"}\n--password clisecret\nAuthorization Bearer rawbearer\n'
                              b'token:\ncontinuationsecret\n' + b'x' * 25000 + b'\nvisible\n'))
        result = tail.text()
        for secret in ('hidden', 'othersecret', 'urlsecret', 'continuationsecret', 'u:p@', 'jsonsecret', 'jsonbearer', 'clisecret', 'rawbearer'):
            self.assertNotIn(secret, result)
        self.assertTrue(tail.truncated)
        self.assertIn('visible', result)

    def test_request_or_attachment_identity_mismatch_refuses_without_replay(self):
        self.ns['main'] = mock.Mock()
        self.invoke('prepare-diagnosed')
        with self.assertRaises(FileExistsError):
            self.invoke('prepare-diagnosed')
        self.assertEqual(1, self.ns['main'].call_count)
        changed = dict(self.request, taskId=str(uuid.uuid4()))
        with self.assertRaises(self.ns['PreparationFailure']):
            self.invoke('diagnose-only', json.dumps(changed, separators=(',', ':')).encode())
        path = self.root / 'diagnostics' / self.request['operationId'] / 'diagnostic.json'
        data = json.loads(path.read_text())
        data['requestSha256'] = 'f' * 64
        path.write_text(json.dumps(data))
        with self.assertRaises(self.ns['PreparationFailure']):
            self.invoke('diagnose-only')
        self.assertEqual(1, self.ns['main'].call_count)


if __name__ == '__main__':
    unittest.main()
