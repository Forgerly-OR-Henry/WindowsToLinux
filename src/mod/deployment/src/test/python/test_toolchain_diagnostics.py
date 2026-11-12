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
import types
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
        self.ns = {'__name__': 'controlled_diagnostics', '__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__': {},
                   '__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__': ['example.invalid']}
        for name in ('10-release-metadata.py', 'archive-boundaries.py', '20-installation-boundaries.py',
                     '30-managed-installation.py', 'tracked-operations.py', '40-binding-protocol.py'):
            source = (SOURCES / name).read_text().replace('# @compat:centos-repositories@', 'repositories = []')
            exec(compile(source, name, 'exec'), self.ns)
        self.ns['ROOT'] = self.root
        self.ns['tracked_sync_directory'] = mock.Mock()
        self.ns['tracked_layout'] = mock.Mock()
        self.ns['owned_directory'] = mock.Mock(side_effect=self.owned)
        self.fcntl = types.SimpleNamespace(LOCK_SH=1, LOCK_EX=2, LOCK_NB=4, flock=mock.Mock())
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
        actual_lstat, actual_fstat = pathlib.Path.lstat, os.fstat
        output = io.StringIO()
        with mock.patch.object(pathlib.Path, 'lstat', lambda path: self.controlled_stat(actual_lstat(path))), \
                mock.patch.object(os, 'fstat', lambda fd: self.controlled_stat(actual_fstat(fd))), \
                mock.patch.dict(sys.modules, {'fcntl': self.fcntl}), contextlib.redirect_stdout(output):
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
        self.assertEqual((self.fcntl.flock.call_args.args[0],), spawn.call_args.kwargs['pass_fds'])
        self.assertEqual(subprocess.PIPE, spawn.call_args.kwargs['stderr'])
        raise raised.exception

    def test_real_exit_stage_redaction_and_independent_tail_bounds_are_persisted(self):
        def preparation(arguments):
            self.assertEqual(self.request['arguments'], arguments)
            self.run_process((('status line ' + 'x' * 150 + '\n') * 220).encode() + b'API_KEY=topsecret\n',
                             b'password: hidden\nhttps://user:pass@example.invalid/a?token=anothersecret\n'
                             + ('\u9519\u8bef\n' * 9000).encode() + b'-----BEGIN PRIVATE KEY-----\nprivatebody\n-----END PRIVATE KEY-----\n')
            self.ns['fail']('installation', 'controlled original failure')
        self.ns['prepare'] = preparation
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
        self.ns['prepare'] = mock.Mock(side_effect=AssertionError('must not prepare'))
        self.assertEqual(diagnostic, json.loads(self.invoke('diagnose-only')))
        self.ns['prepare'].assert_not_called()
        self.fcntl.flock.assert_called_once()

    def test_timeout_kills_and_reaps_owned_process_before_retaining_actual_exit(self):
        self.ns['prepare'] = lambda args: self.run_process(b'partial stdout\n', b'actual timeout detail\n', -9, True)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.invoke('prepare-diagnosed')
        diagnostic = json.loads(self.invoke('diagnose-only'))
        self.assertEqual(-9, diagnostic['actualExitCode'])
        self.assertTrue(diagnostic['timedOut'])
        self.assertEqual('timeout', diagnostic['failureCategory'])
        self.assertEqual('partial stdout\n', diagnostic['stdout'])

    def test_apt_wait_and_installation_keep_one_deadline_after_command_construction(self):
        clock = [100]
        self.ns['DEADLINE'] = 700
        process = mock.Mock(pid=4321, returncode=0)
        process.stdout, process.stderr = io.BytesIO(b'lock released\n'), io.BytesIO()
        process.wait.return_value = 0

        def start(command, **kwargs):
            self.assertIn('DPkg::Lock::Timeout=600', command)
            clock[0] += 20
            return process

        with mock.patch.object(time, 'monotonic', side_effect=lambda: clock[0]), \
                mock.patch.object(pathlib.Path, 'is_file', lambda path: path.as_posix() == '/usr/bin/apt-get'), \
                mock.patch.object(subprocess, 'Popen', side_effect=start) as spawn, \
                mock.patch.object(os, 'killpg', create=True) as kill, \
                mock.patch.object(signal, 'SIGKILL', 9, create=True):
            self.ns['source_dependencies']('python')
        spawn.assert_called_once()
        self.assertEqual('/usr/bin/apt-get', spawn.call_args.args[0][0])
        self.assertEqual([mock.call(timeout=580), mock.call()], process.wait.call_args_list)
        self.assertEqual(700, self.ns['DEADLINE'])
        kill.assert_called_once_with(process.pid, 9)
        self.assertTrue(process.stdout.closed)
        self.assertTrue(process.stderr.closed)

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
        self.ns['prepare'] = mock.Mock()
        self.invoke('prepare-diagnosed')
        with self.assertRaises(FileExistsError):
            self.invoke('prepare-diagnosed')
        self.assertEqual(1, self.ns['prepare'].call_count)
        changed = dict(self.request, taskId=str(uuid.uuid4()))
        with self.assertRaises(self.ns['PreparationFailure']):
            self.invoke('diagnose-only', json.dumps(changed, separators=(',', ':')).encode())
        path = self.root / 'diagnostics' / self.request['operationId'] / 'diagnostic.json'
        data = json.loads(path.read_text())
        data['requestSha256'] = 'f' * 64
        path.write_text(json.dumps(data))
        with self.assertRaises(self.ns['PreparationFailure']):
            self.invoke('diagnose-only')
        self.assertEqual(1, self.ns['prepare'].call_count)

    def test_installation_waits_for_exclusive_lock_and_preserves_normal_receipt(self):
        self.ns['SHIPPED_CATALOG'] = {'PHP': ['8.3']}
        url = 'https://example.invalid/php.tar.gz'
        self.ns['release'] = mock.Mock(return_value=('8.3.35', url, 'sha256', 'a' * 64, 'php'))
        self.ns['reuse_system'] = mock.Mock(return_value=None)
        record = {'ecosystem': 'PHP', 'version': '8.3.35', 'directory': '/fixture', 'origin': 'MANAGED',
                  'source': url, 'sha256': 'a' * 64}

        def install(*arguments):
            self.assertEqual(('PHP', '8.3.35', url, 'sha256', 'a' * 64, 'php'), arguments)
            self.assertEqual(self.fcntl.flock.call_args.args[0], self.ns['PREPARE_LOCK'])
            self.assertTrue((self.root / 'versions').is_dir())
            return record

        def retry(seconds):
            self.assertEqual(0.2, seconds)
            self.assertFalse((self.root / 'versions').exists())
            self.ns['release'].assert_not_called()

        self.ns['install'] = mock.Mock(side_effect=install)
        self.fcntl.flock.side_effect = [BlockingIOError(), None]
        with mock.patch.object(time, 'sleep', side_effect=retry) as sleep:
            output = self.invoke('prepare-diagnosed')
        sleep.assert_called_once()
        fd = self.fcntl.flock.call_args.args[0]
        flags = self.fcntl.LOCK_EX | self.fcntl.LOCK_NB
        self.assertEqual([mock.call(fd, flags)] * 2, self.fcntl.flock.call_args_list)
        self.assertIsNone(self.ns['PREPARE_LOCK'])
        self.assertEqual('TOOLCHAIN=' + '|'.join(record.values()) + '\n', output)
        self.assertEqual('SUCCEEDED', json.loads(self.invoke('diagnose-only'))['preparationStatus'])

    def test_invalid_lock_records_failure_without_execution_or_replay(self):
        lock = self.root / '.prepare.lock'
        lock.mkdir(parents=True)
        self.ns['prepare'] = mock.Mock()
        with self.assertRaises(self.ns['PreparationFailure']) as failure:
            self.invoke('prepare-diagnosed')
        self.assertEqual('ownership', failure.exception.category)
        self.assertEqual('FAILED', json.loads(self.invoke('diagnose-only'))['preparationStatus'])
        with self.assertRaises(FileExistsError):
            self.invoke('prepare-diagnosed')
        self.ns['prepare'].assert_not_called()
        self.fcntl.flock.assert_not_called()
        self.assertTrue(lock.is_dir())

    def test_diagnosed_installation_retains_existing_unmarked_destination(self):
        digest = 'a' * 64
        key = self.ns['installation_key']('PHP', '8.3.35', digest)
        destination = self.root / 'versions' / ('php-' + key)
        destination.mkdir(parents=True)
        retained = destination / 'retained'
        retained.write_bytes(b'original destination content')
        self.ns['SHIPPED_CATALOG'] = {'PHP': ['8.3']}
        self.ns['release'] = mock.Mock(return_value=('8.3.35', 'https://example.invalid/php.tar.gz', 'sha256', digest, 'php'))
        self.ns['discover_system'] = mock.Mock(return_value=None)
        self.ns['download'] = mock.Mock()
        with self.assertRaises(self.ns['PreparationFailure']) as failure:
            self.invoke('prepare-diagnosed')
        self.assertEqual('ownership', failure.exception.category)
        diagnostic = json.loads(self.invoke('diagnose-only'))
        self.assertEqual('FAILED', diagnostic['preparationStatus'])
        self.assertEqual('ownership', diagnostic['failureCategory'])
        self.assertIsNone(diagnostic['actualExitCode'])
        self.assertEqual(b'original destination content', retained.read_bytes())
        self.assertEqual([retained], list(destination.iterdir()))
        self.ns['download'].assert_not_called()


if __name__ == '__main__':
    unittest.main()
