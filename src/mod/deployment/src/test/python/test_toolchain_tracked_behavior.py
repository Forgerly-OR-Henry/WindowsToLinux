"""Exercise tracked helper effects and crash recovery in a temporary filesystem, without network or packages."""
import base64
import contextlib
import hashlib
import io
import json
import os
import pathlib
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import types
import unittest
import uuid
from unittest import mock


SOURCES = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain'
LAYOUT = '{"formatVersion":1,"remote":{"helperDirectory":"/usr/local/lib/windowstolinux","applicationsRoot":"/opt/windowstolinux/apps","stateRoot":"/var/lib/windowstolinux","configurationRoot":"/etc/opt/windowstolinux/apps","dataRoot":"/var/opt/windowstolinux/apps"}}'


class Crash(BaseException):
    pass


class TrackedToolchainBehaviorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name) / 'toolchains'
        self.root.mkdir()
        self.ns = {'__name__': 'controlled_tracked_test'}
        for name in ('10-release-metadata.py', 'archive-boundaries.py', '20-installation-boundaries.py',
                     '30-managed-installation.py', 'tracked-operations.py', '40-binding-protocol.py'):
            source = (SOURCES / name).read_text().replace('# @compat:centos-repositories@', 'repositories = []')
            exec(compile(source, name, 'exec'), self.ns)
        self.ns['ROOT'] = self.root
        self.ns['SHIPPED_CATALOG'] = {'NODE': ['22'], 'JAVA': ['21']}
        self.ns['owned_directory'] = mock.Mock(side_effect=self.owned)
        self.ns['tracked_sync_directory'] = mock.Mock()
        self.ns['probe'] = mock.Mock()
        self.ns['release'] = mock.Mock(return_value=('22.0.0', 'https://nodejs.org/dist/v22.0.0/node.tar.xz', 'sha256', 'a' * 64, 'archive'))
        self.ns['discover_system'] = mock.Mock(return_value=None)
        self.ns['download'] = mock.Mock(return_value='a' * 64)
        self.ns['extract'] = mock.Mock(side_effect=self.extract)
        self.fcntl = types.SimpleNamespace(LOCK_SH=1, LOCK_EX=2, LOCK_NB=4, flock=mock.Mock())
        self.request = self.prepare_request()
        self.real_layout = self.ns['tracked_layout']
        self.ns['tracked_layout'] = self.layout

    def owned(self, path):
        self.assertFalse(path.is_symlink())
        self.assertTrue(path == self.root or self.root in path.parents)

    def layout(self, encoded):
        original = self.ns['ROOT']
        self.ns['ROOT'] = pathlib.PurePosixPath('/usr/local/lib/windowstolinux/toolchains')
        try:
            self.real_layout(encoded)
        finally:
            self.ns['ROOT'] = original

    def extract(self, archive, unpacked):
        directory = unpacked / 'node-22.0.0'
        (directory / 'bin').mkdir(parents=True)
        (directory / 'bin' / 'node').write_text('fixed fake executable')

    def prepare_request(self):
        return {'formatVersion': 1, 'operationId': str(uuid.uuid4()), 'taskId': '22222222-2222-2222-2222-222222222222',
                'executionId': '33333333-3333-3333-3333-333333333333', 'unitId': 'sample-api', 'recoveryAttempt': 1,
                'originalIntentSha256': 'a' * 64, 'serverId': 'fixture-server', 'fingerprintSha256': 'b' * 64,
                'remoteLayout': LAYOUT, 'kind': 'PREPARE',
                'parameters': {'ecosystem': 'NODE', 'branch': '22', 'exact': '-', 'seconds': 120, 'javaHome': '-'}}

    def raw(self, request=None):
        return json.dumps(request or self.request, separators=(',', ':')).encode()

    def state_path(self, request=None):
        return self.root / 'operations' / (request or self.request)['operationId'] / 'state.json'

    def state(self, request=None):
        return json.loads(self.state_path(request).read_text())

    def controlled_stat(self, value):
        fields = list(value)
        fields[4] = 0
        fields[0] = (fields[0] & ~0o777) | (0o700 if stat.S_ISDIR(fields[0]) else 0o444)
        return os.stat_result(fields)

    def invoke(self, verb, request=None, raw=None):
        request = request or self.request
        raw = self.raw(request) if raw is None else raw
        args = [verb, request['operationId'], hashlib.sha256(raw).hexdigest()]
        actual_lstat = pathlib.Path.lstat
        actual_stat = pathlib.Path.stat
        actual_fstat = os.fstat
        actual_rmtree = shutil.rmtree

        def controlled_link(source, target):
            # Windows sandbox prohibits hard links. Linux runs the actual atomic no-clobber primitive.
            self.assertFalse(pathlib.Path(target).exists())
            with pathlib.Path(target).open('xb') as output:
                output.write(pathlib.Path(source).read_bytes())
            pathlib.Path(target).chmod(0o444)

        def controlled_unlink(path, *args, **kwargs):
            if path.exists():
                path.chmod(0o600)
            return actual_unlink(path, *args, **kwargs)

        def controlled_rmtree(path, *args, **kwargs):
            resolved = pathlib.Path(path).resolve()
            self.assertTrue(resolved == self.root.resolve() or self.root.resolve() in resolved.parents)

            def repair(function, failed_path, error):
                os.chmod(failed_path, 0o700)
                function(failed_path)

            return actual_rmtree(path, onexc=repair)

        actual_unlink = pathlib.Path.unlink
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.dict('sys.modules', {'fcntl': self.fcntl}))
            stack.enter_context(mock.patch.object(pathlib.Path, 'lstat', lambda path: self.controlled_stat(actual_lstat(path))))
            stack.enter_context(mock.patch.object(pathlib.Path, 'stat', lambda path, *a, **k: self.controlled_stat(actual_stat(path, *a, **k))))
            stack.enter_context(mock.patch.object(os, 'fstat', lambda fd: self.controlled_stat(actual_fstat(fd))))
            if os.name == 'nt':
                stack.enter_context(mock.patch.object(os, 'link', controlled_link))
                stack.enter_context(mock.patch.object(pathlib.Path, 'unlink', controlled_unlink))
                stack.enter_context(mock.patch.object(shutil, 'rmtree', controlled_rmtree))
            output = io.StringIO()
            stack.enter_context(contextlib.redirect_stdout(output))
            self.ns['tracked_main'](args, raw)
        lines = output.getvalue().splitlines()
        self.assertEqual(1, len(lines), 'Tracked protocol emits exactly one JSON receipt')
        self.assertLessEqual(len(lines[0].encode()), 32768)
        return json.loads(lines[0])

    def binding_request(self, selection):
        request = self.prepare_request()
        request['kind'] = 'BIND'
        payload = ('WTL-TOOLS-1\tTEST\nNODE\tBUILD\t22\t-\t-\t-\t' + selection['version'] + '\t'
                   + selection['directory'] + '\t' + selection['origin'] + '\t'
                   + base64.b64encode(selection['source'].encode()).decode() + '\t' + selection['sha256'] + '\n').encode()
        request['parameters'] = {'bindingBase64': base64.b64encode(payload).decode()}
        return request, payload

    def crash_before_terminal(self):
        original = self.ns['tracked_atomic_json']

        def write(path, value, mode=0o600):
            if path.name == 'state.json' and value.get('phase') == 'COMPLETED':
                raise Crash('receipt crash')
            return original(path, value, mode)

        return mock.patch.dict(self.ns, {'tracked_atomic_json': write})

    def test_golden_request_bytes_and_strict_schema(self):
        request = self.prepare_request()
        request['operationId'] = '11111111-1111-1111-1111-111111111111'
        raw = self.raw(request)
        self.assertEqual('5c8a3904fa80a54f92bfa08c20ed25d2f2b85adf75e24648126216bf48d35897', hashlib.sha256(raw).hexdigest())
        args = ['prepare-tracked', request['operationId'], hashlib.sha256(raw).hexdigest()]
        self.assertEqual(request, self.ns['tracked_request'](args, raw))
        for field, value in (('recoveryAttempt', True), ('operationId', request['operationId'].upper()),
                             ('fingerprintSha256', 'invalid'), ('remoteLayout', LAYOUT + '\n'), ('unexpected', 'x')):
            with self.subTest(field=field):
                invalid = dict(request, **{field: value})
                if invalid == request:
                    continue
                data = self.raw(invalid)
                with self.assertRaises((self.ns['PreparationFailure'], ValueError)):
                    self.ns['tracked_request'](['prepare-tracked', request['operationId'], hashlib.sha256(data).hexdigest()], data)
        duplicate = raw[:-1] + b',"formatVersion":1}'
        with self.assertRaises(self.ns['PreparationFailure']):
            self.ns['tracked_request'](['prepare-tracked', request['operationId'], hashlib.sha256(duplicate).hexdigest()], duplicate)

    def test_selected_release_is_durable_before_first_install_and_observe_never_reselects(self):
        def stop_before_install(*args):
            selected = self.state()['selected']
            self.assertEqual('22.0.0', selected['version'])
            self.assertEqual('https://nodejs.org/dist/v22.0.0/node.tar.xz', selected['source'])
            self.assertEqual('SELECTED', self.state()['phase'])
            raise Crash('before installation')

        with mock.patch.dict(self.ns, {'install': stop_before_install}), self.assertRaises(Crash):
            self.invoke('prepare-tracked')
        before = self.ns['release'].call_count
        result = self.invoke('observe-tracked')
        self.assertEqual('UNKNOWN', result['executionStatus'])
        self.assertEqual('UNSETTLED', result['effectState'])
        self.assertEqual(before, self.ns['release'].call_count)
        result = self.invoke('cleanup-tracked')
        self.assertEqual('SETTLED_NO_EFFECTS', result['effectState'])
        self.assertEqual('VERIFIED', result['cleanupState'])
        self.assertEqual('UNKNOWN', result['executionStatus'])

    def test_actual_installation_marker_recovers_without_terminal_receipt_or_another_install(self):
        with self.crash_before_terminal(), self.assertRaises(Crash):
            self.invoke('prepare-tracked')
        result = self.invoke('observe-tracked')
        self.assertEqual('UNKNOWN', result['executionStatus'])
        self.assertEqual('PREPARED_VERIFIED', result['effectState'])
        self.assertEqual('22.0.0', result['selection']['version'])
        self.assertEqual(1, self.ns['download'].call_count)
        self.assertEqual(1, self.ns['release'].call_count)
        repeated = self.invoke('prepare-tracked')
        self.assertEqual('UNKNOWN', repeated['executionStatus'])
        self.assertEqual(1, self.ns['download'].call_count)

    def test_atomic_bind_before_terminal_receipt_recovers_complete_original_binding(self):
        prepared = self.invoke('prepare-tracked')
        request, payload = self.binding_request(prepared['selection'])
        with self.crash_before_terminal(), self.assertRaises(Crash):
            self.invoke('bind-tracked', request)
        identity = hashlib.sha256(payload).hexdigest()
        self.assertEqual(payload, (self.root / 'bindings' / identity).read_bytes())
        result = self.invoke('observe-tracked', request)
        self.assertEqual('UNKNOWN', result['executionStatus'])
        self.assertEqual('BINDING_VERIFIED', result['effectState'])
        self.assertEqual(identity, result['bindingSha256'])
        self.assertIsNone(result['selection'])
        self.assertEqual(['IMMUTABLE_BINDING'], result['retainedEffects'])
        self.assertEqual(1, self.ns['download'].call_count)

    def test_atomic_link_crash_leaves_full_binding_and_only_original_temp_is_cleaned(self):
        prepared = self.invoke('prepare-tracked')
        request, payload = self.binding_request(prepared['selection'])

        def stop(path):
            if path == self.root / 'bindings' and (path / hashlib.sha256(payload).hexdigest()).exists():
                raise Crash('after atomic link')

        with mock.patch.dict(self.ns, {'tracked_sync_directory': stop}), self.assertRaises(Crash):
            self.invoke('bind-tracked', request)
        result = self.invoke('observe-tracked', request)
        self.assertEqual('BINDING_VERIFIED', result['effectState'])
        self.assertEqual('REQUIRED', result['cleanupState'])
        result = self.invoke('cleanup-tracked', request)
        self.assertEqual('VERIFIED', result['cleanupState'])
        self.assertEqual(payload, (self.root / 'bindings' / hashlib.sha256(payload).hexdigest()).read_bytes())
        self.assertFalse((self.root / 'bindings' / ('.tracked-' + request['operationId'])).exists())

    def test_shared_complete_installation_binding_and_system_effects_are_retained(self):
        first = self.invoke('prepare-tracked')
        second_request = self.prepare_request()
        second = self.invoke('prepare-tracked', second_request)
        self.assertEqual(first['selection'], second['selection'])
        bind_request, payload = self.binding_request(first['selection'])
        self.invoke('bind-tracked', bind_request)
        marker = pathlib.Path(first['selection']['directory']) / '.w2l-toolchain.json'
        before = marker.read_bytes()
        for request in (self.request, second_request, bind_request):
            result = self.invoke('cleanup-tracked', request)
            self.assertEqual('VERIFIED', result['cleanupState'])
        self.assertEqual(before, marker.read_bytes())
        self.assertEqual(payload, (self.root / 'bindings' / hashlib.sha256(payload).hexdigest()).read_bytes())
        self.assertEqual(1, self.ns['download'].call_count)

    def test_failed_installation_is_not_settled_until_owned_partial_directory_is_removed(self):
        def failure(ecosystem, identity, url, algorithm, digest, layout):
            tracked = self.ns['TRACKED_OPERATION']
            tracked.create_destination(pathlib.Path(tracked.state['selected']['directory']))
            tracked.dependencies_started()
            self.ns['fail']('installation', 'controlled package/build failure')

        with mock.patch.dict(self.ns, {'install': failure}):
            result = self.invoke('prepare-tracked')
        self.assertEqual('FAILED', result['executionStatus'])
        self.assertEqual('UNSETTLED', result['effectState'])
        self.assertEqual('REQUIRED', result['cleanupState'])
        result = self.invoke('cleanup-tracked')
        self.assertEqual('FAILED', result['executionStatus'])
        self.assertEqual('SETTLED_RETAINED_EFFECTS', result['effectState'])
        self.assertEqual(['SYSTEM_DEPENDENCIES'], result['retainedEffects'])
        self.assertEqual('VERIFIED', result['cleanupState'])

    def test_request_identity_collision_is_rejected_before_replaying_or_deleting_assets(self):
        result = self.invoke('prepare-tracked')
        marker = pathlib.Path(result['selection']['directory']) / '.w2l-toolchain.json'
        before = marker.read_bytes()
        for field in ('taskId', 'executionId', 'serverId', 'fingerprintSha256', 'originalIntentSha256'):
            request = dict(self.request)
            request[field] = str(uuid.uuid4()) if field.endswith('Id') and field != 'serverId' else 'c' * 64
            with self.subTest(field=field), self.assertRaises(self.ns['PreparationFailure']):
                self.invoke('cleanup-tracked', request)
            self.assertEqual(before, marker.read_bytes())
        self.assertEqual(1, self.ns['download'].call_count)
        reordered = json.dumps(self.request, sort_keys=True, separators=(',', ':')).encode()
        with self.assertRaises(self.ns['PreparationFailure']):
            self.invoke('observe-tracked', raw=reordered)

    def test_crash_before_destination_owner_marker_keeps_unproven_residue_unknown(self):
        original = self.ns['tracked_atomic_json']

        def crash(path, value, mode=0o600):
            if path.name == '.w2l-operation-owner':
                raise Crash('before durable ownership')
            return original(path, value, mode)

        with mock.patch.dict(self.ns, {'tracked_atomic_json': crash}), self.assertRaises(Crash):
            self.invoke('prepare-tracked')
        directory = pathlib.Path(self.state()['selected']['directory'])
        self.assertTrue(directory.exists())
        result = self.invoke('cleanup-tracked')
        self.assertEqual('UNKNOWN', result['executionStatus'])
        self.assertEqual('UNSETTLED', result['effectState'])
        self.assertEqual('REQUIRED', result['cleanupState'])
        self.assertTrue(directory.exists())

    def test_changed_completion_marker_is_not_current_effect_proof_or_cleanup_authority(self):
        prepared = self.invoke('prepare-tracked')
        marker = pathlib.Path(prepared['selection']['directory']) / '.w2l-toolchain.json'
        changed = json.loads(marker.read_text())
        changed['version'] = '22.0.1'
        marker.chmod(0o600)
        marker.write_text(json.dumps(changed))
        marker.chmod(0o444)
        before = marker.read_bytes()
        for verb in ('observe-tracked', 'cleanup-tracked'):
            result = self.invoke(verb)
            self.assertEqual('SUCCEEDED', result['executionStatus'], 'The old execution record is retained separately')
            self.assertEqual('UNSETTLED', result['effectState'])
            self.assertEqual('integrity', result['failureCategory'])
            self.assertEqual(before, marker.read_bytes())

    def test_conflicting_existing_binding_is_never_replaced_or_deleted(self):
        prepared = self.invoke('prepare-tracked')
        request, payload = self.binding_request(prepared['selection'])
        identity = hashlib.sha256(payload).hexdigest()
        target = self.root / 'bindings' / identity
        target.parent.mkdir()
        target.write_bytes(b'conflicting retained bytes')
        target.chmod(0o444)
        for verb in ('bind-tracked', 'observe-tracked', 'cleanup-tracked'):
            result = self.invoke(verb, request)
            self.assertEqual('UNSETTLED', result['effectState'])
            self.assertEqual(b'conflicting retained bytes', target.read_bytes())

    def test_missing_operation_and_busy_lock_do_not_create_a_recovery_operation(self):
        (self.root / '.prepare.lock').write_text('')
        (self.root / '.prepare.lock').chmod(0o600)
        missing = self.invoke('observe-tracked')
        self.assertEqual('operation_missing', missing['failureCategory'])
        self.assertEqual('UNSETTLED', missing['effectState'])
        self.assertFalse((self.root / 'operations').exists())
        self.fcntl.flock.side_effect = BlockingIOError()
        busy = self.invoke('cleanup-tracked')
        self.assertEqual('lock_busy', busy['failureCategory'])
        self.assertEqual('UNKNOWN', busy['executionStatus'])

    def test_subprocesses_inherit_the_original_preparation_lock(self):
        process = mock.Mock(pid=321)
        process.stdout, process.stderr = io.BytesIO(), io.BytesIO()
        process.returncode = 0
        process.wait.return_value = 0
        recorder = mock.Mock()
        with mock.patch.dict(self.ns, {'TRACKED_PREPARE_LOCK': 17, 'TRACKED_OPERATION': recorder, 'DEADLINE': None}), \
                mock.patch.object(subprocess, 'Popen', return_value=process) as spawn, \
                mock.patch.object(os, 'killpg', create=True), mock.patch.object(signal, 'SIGKILL', 9, create=True):
            self.ns['run'](['fixed-install-command'])
        self.assertEqual((17,), spawn.call_args.kwargs['pass_fds'])
        recorder.process_started.assert_called_once_with(321)
        recorder.process_finished.assert_called_once_with(321)

    def test_symlink_and_foreign_partial_owner_cannot_be_cleaned(self):
        def stop(*args):
            tracked = self.ns['TRACKED_OPERATION']
            directory = pathlib.Path(tracked.state['selected']['directory'])
            tracked.create_destination(directory)
            owner = directory / '.w2l-operation-owner'
            owner.chmod(0o600)
            owner.write_text('{"operationId":"foreign","requestSha256":"' + 'c' * 64 + '"}')
            raise Crash('foreign residue')

        with mock.patch.dict(self.ns, {'install': stop}), self.assertRaises(Crash):
            self.invoke('prepare-tracked')
        result = self.invoke('cleanup-tracked')
        self.assertEqual('UNSETTLED', result['effectState'])
        self.assertEqual('ownership', result['failureCategory'])
        self.assertTrue(pathlib.Path(self.state()['selected']['directory']).exists())


@unittest.skipUnless(sys.platform == 'linux', 'Actual flock inheritance and FD3 transport require Linux')
class TrackedToolchainProcessBoundaryTest(unittest.TestCase):
    def test_inherited_lock_survives_killed_coordinator(self):
        import fcntl
        import signal
        import time
        with tempfile.TemporaryDirectory() as temporary:
            lock = pathlib.Path(temporary) / 'lock'
            ready = pathlib.Path(temporary) / 'ready'
            child = None
            source = ("import fcntl,os,pathlib,subprocess,sys,time\n"
                      "fd=os.open(sys.argv[1],os.O_RDWR|os.O_CREAT,0o600)\n"
                      "fcntl.flock(fd,fcntl.LOCK_EX)\n"
                      "ns={'__name__':'controlled_actual_process'}\n"
                      "for name in ('10-release-metadata.py','20-installation-boundaries.py'):\n"
                      " exec(compile((pathlib.Path(sys.argv[3])/name).read_text(),name,'exec'),ns)\n"
                      "class Journal:\n"
                      " def process_started(self,pid): pathlib.Path(sys.argv[2]).write_text(str(pid))\n"
                      " def process_finished(self,pid): pass\n"
                      "ns['TRACKED_PREPARE_LOCK']=fd\n"
                      "ns['TRACKED_OPERATION']=Journal()\n"
                      "ns['run']([sys.executable,'-c','import time; time.sleep(30)'])\n")
            coordinator = subprocess.Popen([sys.executable, '-c', source, str(lock), str(ready), str(SOURCES)])
            try:
                deadline = time.monotonic() + 5
                while not ready.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue(ready.exists())
                child = int(ready.read_text())
                coordinator.kill()
                coordinator.wait(timeout=5)
                with lock.open('r') as stream:
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                os.killpg(child, signal.SIGKILL)
                child = None
                deadline = time.monotonic() + 5
                with lock.open('r') as stream:
                    while True:
                        try:
                            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                            break
                        except BlockingIOError:
                            if time.monotonic() >= deadline:
                                raise
                            time.sleep(.01)
            finally:
                if child is not None:
                    os.killpg(child, signal.SIGKILL)
                if coordinator.poll() is None:
                    coordinator.kill()
                coordinator.wait(timeout=5)

    def test_actual_heredoc_preserves_request_bytes_on_descriptor_three(self):
        bundle_source = pathlib.Path(__file__).resolve().parents[2] / 'main/java/gold/debug/windowstolinux/mod/deployment/linux/helper/DeploymentManagedHelperBundle.java'
        # Also bind the exercised redirection to the current packaged wrapper, not a separate fixture convention.
        self.assertIn('3<&0', bundle_source.read_text())
        payload = b'{"fixed":"original request bytes"}'
        shell = "python3 -I - 3<&0 <<'SOURCE'\nimport os,hashlib\nprint(hashlib.sha256(os.read(3,1024)).hexdigest())\nSOURCE\n"
        result = subprocess.run(['bash', '-c', shell], input=payload, capture_output=True, check=True, timeout=10)
        self.assertEqual(hashlib.sha256(payload).hexdigest(), result.stdout.decode().strip())


@unittest.skipUnless(os.environ.get('WTL_HELPER_ACTUAL_LINK_TEST') == '1', 'Explicitly requested real OS hard-link capability check')
class TrackedToolchainAtomicLinkPrimitiveTest(unittest.TestCase):
    def test_actual_os_link_publishes_complete_bytes_without_clobbering_existing_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            source, target, other = root / 'source', root / 'binding', root / 'other'
            source.write_bytes(b'complete immutable binding')
            other.write_bytes(b'conflicting bytes')
            os.link(source, target)
            self.assertEqual(source.read_bytes(), target.read_bytes())
            with self.assertRaises(FileExistsError):
                os.link(other, target)
            self.assertEqual(b'complete immutable binding', target.read_bytes())


if __name__ == '__main__':
    unittest.main()
