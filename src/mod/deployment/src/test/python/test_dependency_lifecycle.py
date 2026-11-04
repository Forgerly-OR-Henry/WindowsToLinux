"""Actual shared source, pure process/kernel seams only; no real systemd, apt, SSH or Runtime."""
import contextlib
import errno
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
from test_agent_toolchain_isolation import dependencies, OPERATION

TASK = '11111111-2222-3333-4444-555555555555'
FRAME = ['NATIVE_BUILD', OPERATION, '60', 'server-a', TASK]

class DependencyLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.d, _ = dependencies()

    def test_canonical_total_budget_identity_and_no_uncontained_package_entry(self):
        self.assertEqual(60, self.d.dependency_frame(FRAME)['totalSeconds'])
        for field, value in ((2, '0'), (2, '-1'), (2, '044'), (2, '44'), (2, '7201'),
                             (2, '999999999999999999'), (2, '60.0'), (1, 'A' * 32), (3, '../server'), (4, OPERATION)):
            changed = list(FRAME)
            changed[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(self.d.DependencyFailure):
                self.d.dependency_frame(changed)
        with mock.patch.object(self.d.subprocess, 'Popen') as spawn:
            for call in (lambda: self.d.dependency_transaction(['NATIVE_BUILD', OPERATION]),
                         lambda: self.d.dependency_run(['/usr/bin/apt-get', 'update'])):
                with self.assertRaises(self.d.DependencyFailure):
                    call()
            spawn.assert_not_called()

    def test_commands_share_one_deadline_including_leader_exit_with_open_descendant_stdout(self):
        self.d.DEPENDENCY_WORKER_ADMITTED = True
        self.d.DEPENDENCY_DEADLINE_NS = 1000000000
        process = mock.Mock()
        process.stdout = mock.Mock()
        process.poll.return_value = 0  # Leader exited, descendant still owns its output pipe.
        selector = mock.MagicMock()
        selector.get_map.return_value = {1: object()}
        selector.select.return_value = []
        with mock.patch.object(self.d.time, 'monotonic_ns', side_effect=[0, 0, 2000000000]), \
             mock.patch.object(self.d.subprocess, 'Popen', return_value=process), \
             mock.patch.object(self.d.selectors, 'DefaultSelector', return_value=selector), \
             self.assertRaises(self.d.DependencyFailure) as failure:
            self.d.dependency_run(['/usr/bin/apt-get', 'update'])
        self.assertTrue(failure.exception.unknown)
        self.assertEqual('DEPENDENCY_TRANSACTION_TIMEOUT', failure.exception.detail)
        self.assertEqual(1000000000, self.d.DEPENDENCY_DEADLINE_NS)
        process.wait.assert_called_once_with(timeout=1)
        process.stdout.close.assert_called_once()
        with mock.patch.object(self.d.time, 'monotonic_ns', return_value=2000000000), \
             mock.patch.object(self.d.subprocess, 'Popen') as spawn, self.assertRaises(self.d.DependencyFailure):
            self.d.dependency_run(['/usr/bin/apt-get', 'install'])
        spawn.assert_not_called()

    def test_mirror_inputs_reach_the_same_service_with_the_original_task_and_reduced_deadline(self):
        environment = {'WTL_MIRROR_POLICY': '/owned/policy.json', 'WTL_MIRROR_RUNTIME': '/owned/runtime.py',
                       'WTL_MIRROR_POLICY_SHA256': 'a' * 64, 'WTL_MIRROR_BUDGET_SECONDS': '60',
                       'WTL_MIRROR_STARTED_AT': '100', 'UNRELATED_SECRET': 'must-not-cross'}
        policy = types.SimpleNamespace(document={'task': TASK, 'server': 'server-a'}, remaining=lambda: 50)
        output = io.StringIO()
        with self.supervision() as (events, spawn, root, process), \
             mock.patch.dict(self.d.os.environ, environment, clear=True), \
             mock.patch.object(self.d, 'dependency_task_mirrors', return_value=({}, policy)), contextlib.redirect_stdout(output):
            self.d.dependency_supervise(FRAME)
            manifest = json.loads((root / OPERATION / 'manifest.json').read_text())
            mirror = manifest['mirrorEnvironment']
            self.assertEqual(51000000000, manifest['deadlineMonotonicNs'])
            self.assertEqual(21000000000, manifest['workerDeadlineMonotonicNs'])
            self.assertEqual('21.0', mirror['WTL_MIRROR_DEADLINE'])
            self.assertEqual(20, manifest['runtimeSeconds'])
            launch = spawn.call_args.args[0]
            for name, value in mirror.items():
                self.assertIn('--setenv=' + name + '=' + value, launch)
            self.assertFalse(any('UNRELATED_SECRET' in value or 'must-not-cross' in value for value in launch))
            self.assertLess(events.index('ack-after-core-one'), events.index('same-fd-final-pread'))
            self.assertEqual(1, output.getvalue().count('\n'))
        for document in ({'task': 'foreign', 'server': 'server-a'}, {'task': TASK, 'server': 'foreign'}):
            with self.subTest(document=document), mock.patch.dict(self.d.os.environ, environment, clear=True), \
                 mock.patch.object(self.d, 'dependency_task_mirrors', return_value=({}, types.SimpleNamespace(document=document))), \
                 self.assertRaises(self.d.DependencyFailure) as failure:
                self.d.dependency_mirror_environment('server-a', TASK, 60000000000)
            self.assertEqual('DEPENDENCY_MIRROR_ORIGINAL', failure.exception.detail)

    def test_mirrored_transaction_keeps_native_flags_fixed_environment_and_one_worker_deadline(self):
        self.d.DEPENDENCY_WORKER_ADMITTED = True
        self.d.DEPENDENCY_DEADLINE_NS = 10000000000
        process = mock.Mock()
        process.stdout = mock.Mock()
        process.poll.return_value = 0
        process.wait.return_value = 0
        selector = mock.MagicMock()
        selector.__enter__.return_value = selector
        selector.get_map.return_value = {}
        transformed = ['/bin/bash', '-c', 'fixed-mirror-wrapper', 'wtl-system-package', 'install', '-y', '--no-remove']
        adapter = mock.Mock(return_value=transformed)
        environment = {'WTL_MIRROR_POLICY': '/owned/policy.json', 'WTL_MIRROR_RUNTIME': '/owned/runtime.py',
                       'WTL_MIRROR_DEADLINE': '10.0', 'UNRELATED_SECRET': 'must-not-cross'}
        with mock.patch.dict(self.d.os.environ, environment, clear=True), \
             mock.patch.object(self.d.time, 'monotonic_ns', return_value=1000000000), \
             mock.patch.object(self.d, 'dependency_task_mirrors', return_value=({'mirror_system_command': adapter}, object())), \
             mock.patch.object(self.d.selectors, 'DefaultSelector', return_value=selector), \
             mock.patch.object(self.d.subprocess, 'Popen', return_value=process) as spawn:
            self.assertEqual('', self.d.dependency_run(['/usr/bin/apt-get', 'install', '-y', '--no-remove']))
        adapter.assert_called_once_with(mock.ANY, 'apt-get', ['install', '-y', '--no-remove'])
        self.assertEqual(transformed, spawn.call_args.args[0])
        passed = spawn.call_args.kwargs['env']
        self.assertEqual('/usr/sbin:/usr/bin:/sbin:/bin', passed['PATH'])
        self.assertEqual('/owned/policy.json', passed['WTL_MIRROR_POLICY'])
        self.assertEqual('10.0', passed['WTL_MIRROR_DEADLINE'])
        self.assertNotIn('UNRELATED_SECRET', passed)
        self.assertEqual(10000000000, self.d.DEPENDENCY_DEADLINE_NS)

    def test_final_name_is_published_only_after_fsync_and_never_replaces_an_old_receipt(self):
        for collision in (False, True):
            events = []
            def opened(path, flags, *mode):
                events.append(('open', path))
                return 11 if path.endswith('.new') else 12
            def publish(source, target, **options):
                self.assertEqual([('open', str(pathlib.Path('/owned/result.json.new'))), ('owner', 11),
                    ('write', b'complete'), ('fsync', 11), ('close', 11)], events)
                self.assertEqual({'follow_symlinks': False}, options)
                events.append(('link-no-replace', target))
                if collision:
                    raise FileExistsError()
            with mock.patch.object(self.d.os, 'O_NOFOLLOW', 0, create=True), \
                 mock.patch.object(self.d.os, 'O_DIRECTORY', 0, create=True), \
                 mock.patch.object(self.d.os, 'open', side_effect=opened), \
                 mock.patch.object(self.d.os, 'fchown', side_effect=lambda *a: events.append(('owner', a[0])), create=True), \
                 mock.patch.object(self.d.os, 'write', side_effect=lambda fd, data: (events.append(('write', data)), len(data))[1]), \
                 mock.patch.object(self.d.os, 'fsync', side_effect=lambda fd: events.append(('fsync', fd))), \
                 mock.patch.object(self.d.os, 'close', side_effect=lambda fd: events.append(('close', fd))), \
                 mock.patch.object(self.d.os, 'link', side_effect=publish), \
                 mock.patch.object(self.d.pathlib.Path, 'unlink', side_effect=lambda: events.append(('unlink-temp',))):
                if collision:
                    with self.assertRaises(FileExistsError):
                        self.d.dependency_write(pathlib.Path('/owned/result.json'), b'complete')
                else:
                    self.d.dependency_write(pathlib.Path('/owned/result.json'), b'complete')
            self.assertIn(('unlink-temp',), events)
            self.assertFalse(any(event == ('open', str(pathlib.Path('/owned/result.json'))) for event in events))
        with mock.patch.object(self.d, 'dependency_protected', return_value=pathlib.Path('/outside/owned-file')), \
             mock.patch.object(self.d.pathlib.Path, 'is_symlink', return_value=False), \
             mock.patch.object(self.d.os, 'open') as opened, self.assertRaises(self.d.DependencyFailure):
            self.d.dependency_read(pathlib.Path('/owned/result.json'))
        opened.assert_not_called()

    def manager_facts(self, manifest, invocation='b' * 32, main='123', sub='running', group=None):
        return {'LoadState': 'loaded', 'ActiveState': 'active', 'SubState': sub, 'MainPID': main,
            'ExecMainStatus': '0', 'Result': 'success', 'InvocationID': invocation,
            'ControlGroup': '/system.slice/' + manifest['unit'] if group is None else group,
            'Description': manifest['description'], 'Transient': 'yes', 'Type': 'exec', 'ExitType': 'cgroup',
            'User': 'root', 'Group': 'root', 'KillMode': 'control-group', 'SendSIGKILL': 'yes',
            'RuntimeMaxUSec': str(manifest['runtimeSeconds']) + 's', 'TimeoutStopUSec': '5s',
            'RemainAfterExit': 'yes', 'Delegate': 'no', 'ActiveEnterTimestampMonotonic': '1'}

    @contextlib.contextmanager
    def supervision(self, fault=None, total='60'):
        with tempfile.TemporaryDirectory(prefix='wtl-v2-producer-pure-') as temporary:
            root = pathlib.Path(temporary)
            self.d.DEPENDENCY_OPERATIONS_ROOT = str(root)
            self.d.DEPENDENCY_WORKER_SOURCE = 'synthetic fixed worker, never executed'
            events, clock, state = [], [1000000000], {'ack': False, 'stop': False, 'shown': 0}
            anchor = {'groupIdentity': [1, 2], 'eventsIdentity': [1, 3], 'coreEventsFilesystemMagic': 1667723888,
                      'initialPopulated': 1, 'coreEventsBound': True}
            process = mock.Mock()
            process.stdout = mock.Mock()
            process.poll.side_effect = lambda: 0 if state['stop'] else None
            process.returncode, process.wait.return_value = 0, 0
            if fault in ('wait-primary', 'wait-normal'):
                process.wait.side_effect = self.d.subprocess.TimeoutExpired('own-client', 1)
            def communicate(**kwargs):
                self.assertIn('stop', events)
                events.append('wait-launcher')
                return b'', None
            process.communicate.side_effect = communicate
            def show(unit, phase='UNIT_STATUS'):
                state['shown'] += 1
                if state['shown'] == 1:
                    facts = {'LoadState': 'loaded' if fault == 'collision' else 'not-found'}
                elif state['stop']:
                    facts = {'LoadState': 'not-found'}
                else:
                    manifest = json.loads((root / OPERATION / 'manifest.json').read_text())
                    facts = self.manager_facts(manifest, invocation=('c' if fault == 'identity' else 'b') * 32,
                        main='0' if state['ack'] else '123', sub='exited' if state['ack'] else 'running',
                        group='' if state['ack'] else None)
                self.d.DEPENDENCY_LAST_OBSERVATION = {'phase': phase, 'monotonicNs': clock[0], 'nativeExit': 0,
                                                       'fields': dict(facts), 'sameCallNotRetrospectiveQuery': True}
                return facts
            def spawn(arguments, **kwargs):
                events.append('launch')
                self.assertIn('--wait', arguments)
                self.assertIn('--property=ExitType=cgroup', arguments)
                self.assertFalse(any('DynamicUser' in value or 'ProtectSystem' in value for value in arguments))
                manifest = json.loads((root / OPERATION / 'manifest.json').read_text())
                self.assertLessEqual(manifest['runtimeSeconds'] + 30, int(total))
                if fault != 'before-ready':
                    ready = self.d.dependency_ready_record(manifest, 'b' * 32, [True, 2] if fault == 'bool-group' else [1, 2], 123)
                    (root / OPERATION / 'ready.json').write_bytes(self.d.dependency_encode(ready))
                return process
            def write(path, data):
                path.write_bytes(data)
                if path.name == 'manifest.json' and fault == 'budget-before-launch':
                    clock[0] += int(total) * 1000000000
                if path.name == 'cleanup-ownership.json':
                    events.append('cleanup-ownership')
                    if fault == 'before-ready':
                        self.d.dependency_reject('TEST_BEFORE_READY_FAILURE', True)
                if path.name == 'ack.json':
                    events.append('ack-after-core-one')
                    state['ack'] = True
                    manifest = json.loads((root / OPERATION / 'manifest.json').read_text())
                    owned = (root / OPERATION / 'cleanup-ownership.json').read_bytes()
                    envelope = {'formatVersion': 2, 'operation': OPERATION, 'workerSourceSha256': manifest['workerSourceSha256'],
                        'invocationId': 'b' * 32, 'ackSha256': hashlib.sha256(data).hexdigest(),
                        'cleanupOwnershipSha256': hashlib.sha256(owned).hexdigest(),
                        'result': {'operation': OPERATION, 'ecosystem': 'NATIVE_BUILD', 'manager': 'APT', 'packages': {'cmake': '1'}}}
                    (root / OPERATION / 'result.json').write_bytes(self.d.dependency_encode(envelope))
                    events.append('result-only-after-ack')
            def opened(manifest, ready):
                events.append('core-initial-one')
                if fault == 'missing-group':
                    self.d.dependency_reject('DEPENDENCY_CGROUP_UNAVAILABLE', True)
                return 101, 102, anchor
            def read_events(fd):
                self.assertEqual(102, fd)
                events.append('same-fd-final-pread')
                if fault in ('nonempty', 'wait-primary'):
                    return {'kind': 'VALUES', 'populated': 1, 'method': 'PREAD_OFFSET_ZERO'}
                if fault == 'retired':
                    return {'kind': 'ERRNO', 'errno': 19, 'method': 'PREAD_OFFSET_ZERO'}
                return {'kind': 'VALUES', 'populated': 0, 'method': 'PREAD_OFFSET_ZERO'}
            def control(arguments, **kwargs):
                self.assertEqual(['/usr/bin/systemctl', 'stop', '--', self.d.dependency_unit(OPERATION)], arguments)
                events.append('stop')
                state['stop'] = True
                return 0, ''
            selector = mock.MagicMock()
            selector.select.return_value = []
            original_is_file = pathlib.Path.is_file
            with mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True), \
                 mock.patch.object(self.d.time, 'monotonic_ns', side_effect=lambda: clock[0]), \
                 mock.patch.object(self.d, 'dependency_directory', return_value=root), \
                 mock.patch.object(self.d, 'dependency_read', side_effect=lambda p, *args: pathlib.Path(p).read_bytes()), \
                 mock.patch.object(self.d, 'dependency_write', side_effect=write), \
                 mock.patch.object(self.d, 'dependency_tool', side_effect=lambda name: '/usr/bin/' + name), \
                 mock.patch.object(self.d, 'dependency_show', side_effect=show), \
                 mock.patch.object(self.d, 'dependency_open_core_events', side_effect=opened), \
                 mock.patch.object(self.d, 'dependency_events_read', side_effect=read_events), \
                 mock.patch.object(self.d, 'dependency_control', side_effect=control), \
                 mock.patch.object(self.d.os, 'close', side_effect=lambda fd: events.append('close-' + str(fd))), \
                 mock.patch.object(self.d.pathlib.Path, 'is_file', side_effect=lambda p: True if p.as_posix() == '/sys/fs/cgroup/cgroup.controllers' else original_is_file(p), autospec=True), \
                 mock.patch.object(self.d.subprocess, 'Popen', side_effect=spawn) as popen, \
                 mock.patch.object(self.d.selectors, 'DefaultSelector', return_value=selector):
                yield events, popen, root, process

    def test_completion_is_observed_and_empty_checked_before_stop_and_wait(self):
        for fault, proof in ((None, 'KERNEL_POPULATED_ZERO'), ('retired', 'KERNEL_CORE_EVENTS_RETIRED')):
            output = io.StringIO()
            with self.subTest(proof=proof), self.supervision(fault) as (events, spawn, root, process), contextlib.redirect_stdout(output):
                self.d.dependency_supervise(FRAME)
                life = json.loads(output.getvalue().removeprefix('TOOLCHAIN_DEPENDENCIES='))['lifecycle']
                self.assertEqual(27, len(life))
                self.assertEqual(proof, life['proofKind'])
                self.assertEqual(2, life['formatVersion'])
                self.assertNotIn('recursiveCgroupEmptyObserved', life)
                self.assertEqual(None if fault else 0, life['finalPopulated'])
                self.assertEqual(19 if fault else 0, life['readErrno'])
                self.assertLess(events.index('core-initial-one'), events.index('ack-after-core-one'))
                self.assertLess(events.index('same-fd-final-pread'), events.index('stop'))
                self.assertLess(events.index('stop'), events.index('wait-launcher'))
                self.assertTrue((root / OPERATION / 'observations.json').exists())
                spawn.assert_called_once()

    def test_collision_identity_or_missing_nonempty_cgroup_never_emits_success(self):
        for fault in ('collision', 'identity', 'missing-group', 'nonempty', 'bool-group', 'budget-before-launch'):
            output = io.StringIO()
            with self.subTest(fault=fault), self.supervision(fault) as (events, spawn, root, process), \
                 contextlib.redirect_stdout(output), self.assertRaises(self.d.DependencyFailure):
                self.d.dependency_supervise(FRAME)
            self.assertEqual('', output.getvalue())
            if fault in ('collision', 'budget-before-launch'):
                spawn.assert_not_called()

    def test_minimum_total_45_keeps_ack_and_runtime_stop_reserve_in_original_budget(self):
        frame = list(FRAME)
        frame[2] = '45'
        output = io.StringIO()
        with self.supervision(total='45'), contextlib.redirect_stdout(output):
            self.d.dependency_supervise(frame)
        life = json.loads(output.getvalue().removeprefix('TOOLCHAIN_DEPENDENCIES='))['lifecycle']
        self.assertEqual(45, life['totalSeconds'])
        self.assertGreaterEqual(life['runtimeSeconds'], 1)
        self.assertLessEqual(life['runtimeSeconds'] + life['stopSeconds'] + life['reserveSeconds'], 45)

    def test_before_ready_failure_still_cleans_exact_independent_ownership(self):
        output = io.StringIO()
        with self.supervision('before-ready') as (events, spawn, root, process), contextlib.redirect_stdout(output), \
             self.assertRaises(self.d.DependencyFailure) as failure:
            self.d.dependency_supervise(FRAME)
        self.assertEqual('TEST_BEFORE_READY_FAILURE', failure.exception.detail)
        self.assertIn('cleanup-ownership', events)
        self.assertIn('stop', events)
        self.assertNotIn('ack-after-core-one', events)
        self.assertEqual('', output.getvalue())

    def test_final_wait_failure_closes_both_fds_preserves_original_rejection_and_never_publishes_success(self):
        for fault in ('wait-primary', 'wait-normal'):
            output = io.StringIO()
            expected = self.d.DependencyFailure if fault == 'wait-primary' else self.d.subprocess.TimeoutExpired
            with self.subTest(fault=fault), self.supervision(fault) as (events, spawn, root, process), contextlib.redirect_stdout(output):
                with self.assertRaises(expected) as failure:
                    self.d.dependency_supervise(FRAME)
                diagnostics = json.loads((root / OPERATION / 'observations.json').read_text())
                self.assertFalse(diagnostics['clientFinalWaitCompleted'])
                self.assertEqual('TimeoutExpired', diagnostics['clientFinalWaitFailureType'])
                self.assertFalse((root / OPERATION / 'receipt.json').exists())
            if fault == 'wait-primary':
                self.assertEqual('DEPENDENCY_CORE_EVENTS_COMPLETION', failure.exception.detail)
            self.assertEqual('', output.getvalue())
            self.assertIn('close-101', events)
            self.assertIn('close-102', events)
            process.stdout.close.assert_called_once()

    def test_core_events_completion_rejects_wrong_fd_errno_eof_or_populated(self):
        anchor = {'groupIdentity': [1, 2], 'eventsIdentity': [1, 3], 'coreEventsFilesystemMagic': 1667723888,
                  'initialPopulated': 1, 'coreEventsBound': True}
        for actual_anchor, observation in ((None, {}), ({**anchor, 'coreEventsBound': False}, {'kind': 'ERRNO', 'errno': 19, 'method': 'PREAD_OFFSET_ZERO'}),
                ({**anchor, 'coreEventsFilesystemMagic': 0xEF53}, {'kind': 'ERRNO', 'errno': 19, 'method': 'PREAD_OFFSET_ZERO'}),
                (anchor, {'kind': 'ERRNO', 'errno': errno.EIO, 'method': 'PREAD_OFFSET_ZERO'}),
                (anchor, {'kind': 'ERRNO', 'errno': errno.EBADF, 'method': 'PREAD_OFFSET_ZERO'}),
                (anchor, {'kind': 'ERRNO', 'errno': 19, 'method': 'SEEK'}),
                (anchor, {'kind': 'VALUES', 'populated': 1, 'method': 'PREAD_OFFSET_ZERO'}),
                (anchor, {'kind': 'VALUES', 'populated': False, 'method': 'PREAD_OFFSET_ZERO'})):
            with self.subTest(observation=observation), self.assertRaises(self.d.DependencyFailure):
                self.d.dependency_completion_proof(actual_anchor, observation)
        with mock.patch.object(self.d.os, 'pread', return_value=b'', create=True), self.assertRaises(self.d.DependencyFailure):
            self.d.dependency_events_read(42)
        with mock.patch.object(self.d.os, 'pread', return_value=b'populated 0\nfrozen 0\nextra 0\n', create=True), self.assertRaises(self.d.DependencyFailure):
            self.d.dependency_events_read(42)

    def test_core_events_open_requires_original_cgroup2_inode_and_initial_populated_one(self):
        m = {'unit': self.d.dependency_unit(OPERATION), 'description': 'D', 'runtimeSeconds': 30}
        ready = {'invocationId': 'b' * 32, 'mainPid': 123, 'groupIdentity': [1, 2]}
        directory = types.SimpleNamespace(st_mode=stat.S_IFDIR, st_dev=1, st_ino=2)
        regular = types.SimpleNamespace(st_mode=stat.S_IFREG, st_dev=1, st_ino=3)
        for filesystems, first in (([0xEF53], {'kind': 'VALUES', 'populated': 1}),
                                  ([1667723888, 0xEF53], {'kind': 'VALUES', 'populated': 1}),
                                  ([1667723888, 1667723888], {'kind': 'ERRNO', 'errno': 19}),
                                  ([1667723888, 1667723888], {'kind': 'VALUES', 'populated': 0})):
            with self.subTest(filesystems=filesystems, first=first), \
                 mock.patch.object(self.d, 'dependency_show', return_value=self.manager_facts(m)), \
                 mock.patch.object(self.d, 'dependency_group', return_value=([1, 2], ['123'])), \
                 mock.patch.object(self.d, 'dependency_directory'), \
                 mock.patch.object(self.d.os, 'O_DIRECTORY', 0, create=True), \
                 mock.patch.object(self.d.os, 'O_NOFOLLOW', 0, create=True), \
                 mock.patch.object(self.d.os, 'O_CLOEXEC', 0, create=True), \
                 mock.patch.object(self.d.os, 'open', side_effect=[101, 102]), \
                 mock.patch.object(self.d.os, 'fstat', side_effect=[directory, regular]), \
                 mock.patch.object(self.d.os, 'close') as close, \
                 mock.patch.object(self.d, 'dependency_fstype', side_effect=filesystems), \
                 mock.patch.object(self.d, 'dependency_events_read', return_value=first), self.assertRaises(self.d.DependencyFailure):
                self.d.dependency_open_core_events(m, ready)
            close.assert_any_call(101)

    def test_startup_pending_same_call_trace_and_bound_drift_keep_original_deadline(self):
        m = {'unit': self.d.dependency_unit(OPERATION), 'description': 'D', 'runtimeSeconds': 30,
             'originalUnitAbsent': True, 'deadlineMonotonicNs': 100}
        facts = self.manager_facts(m)
        for invocation in ('', '0' * 32):
            self.assertIsNone(self.d.dependency_startup_decision(m, {**facts, 'InvocationID': invocation}, None, 99, False))
            self.assertEqual(100, m['deadlineMonotonicNs'])
        for changed, owner, ready, now in (({**facts, 'InvocationID': ''}, None, True, 99),
                ({**facts, 'InvocationID': ''}, {'invocationId': 'b' * 32}, False, 99),
                ({**facts, 'InvocationID': 'c' * 32}, {'invocationId': 'b' * 32}, False, 99),
                ({**facts, 'InvocationID': 'bad'}, None, False, 99), ({**facts, 'Group': ''}, None, False, 99),
                ({**facts, 'Description': 'foreign'}, None, False, 99), ({**facts, 'InvocationID': ''}, None, False, 100)):
            with self.subTest(changed=changed), self.assertRaises(self.d.DependencyFailure):
                self.d.dependency_startup_decision(m, changed, owner, now, ready)
        self.d.DEPENDENCY_LAST_OBSERVATION = None
        text = '\n'.join(k + '=' + v for k, v in {**facts, 'InvocationID': ''}.items())
        with mock.patch.object(self.d, 'dependency_control', return_value=(0, text)) as control, \
             mock.patch.object(self.d, 'dependency_tool', return_value='/usr/bin/systemctl'), \
             mock.patch.object(self.d.time, 'monotonic_ns', return_value=99):
            observed = self.d.dependency_show(m['unit'], phase='SOURCE_BEFORE_READY')
        self.assertEqual('', observed['InvocationID'])
        snapshot = self.d.dependency_json(self.d.dependency_encode(self.d.DEPENDENCY_LAST_OBSERVATION))
        with mock.patch.object(self.d, 'dependency_show', return_value=observed), \
             mock.patch.object(self.d, 'dependency_retain_ownership') as retain:
            with tempfile.TemporaryDirectory() as temporary:
                observations, startup = {}, []
                self.d.dependency_observe_startup(m, pathlib.Path(temporary), None, startup, observations)
            retain.assert_not_called()
        self.assertEqual(99, snapshot['monotonicNs'])
        self.assertEqual('SOURCE_BEFORE_READY', snapshot['phase'])
        control.assert_called_once()

    def test_worker_rejects_wrong_manager_properties_before_any_package_action(self):
        self.d.__file__ = '/owned/' + OPERATION + '/worker.py'
        manifest = {'unit': self.d.dependency_unit(OPERATION), 'workerDeadlineMonotonicNs': 1000000000,
                    'description': 'D', 'runtimeSeconds': 30, 'mirrorEnvironment': {}}
        with mock.patch.object(self.d, 'dependency_manifest', return_value=manifest), \
             mock.patch.object(self.d, 'dependency_show', return_value={}), \
             mock.patch.object(self.d, 'dependency_transaction') as packages, self.assertRaises(self.d.DependencyFailure):
            self.d.dependency_worker('/owned/' + OPERATION + '/manifest.json')
        packages.assert_not_called()

    def test_worker_never_admits_package_without_ack_and_verifies_actual_ack_hash(self):
        for acknowledged in (False, True):
            with self.subTest(acknowledged=acknowledged), tempfile.TemporaryDirectory(prefix='worker-ack-pure-') as temporary:
                base = pathlib.Path(temporary) / OPERATION
                base.mkdir()
                self.d.__file__ = str(base / 'worker.py')
                m = {**self.d.dependency_frame(FRAME), 'formatVersion': 2, 'runtimeSeconds': 30,
                    'unit': self.d.dependency_unit(OPERATION), 'description': 'D', 'workerSourceSha256': 'f' * 64,
                    'workerDeadlineMonotonicNs': 1000000000, 'mirrorEnvironment': {}}
                self.d.DEPENDENCY_WORKER_ADMITTED = False
                def write(path, data):
                    path.write_bytes(data)
                    if path.name == 'ready.json' and acknowledged:
                        owned = self.d.dependency_encode(self.d.dependency_ownership_record(m, 'b' * 32))
                        (base / 'cleanup-ownership.json').write_bytes(owned)
                        ack = self.d.dependency_ack_record(m, json.loads(data), {'eventsIdentity': [1, 3]}, hashlib.sha256(owned).hexdigest())
                        (base / 'ack.json').write_bytes(self.d.dependency_encode(ack))
                def package(args):
                    self.assertTrue((base / 'ack.json').exists())
                    self.assertTrue(self.d.DEPENDENCY_WORKER_ADMITTED)
                    print('MIRROR fixed-route diagnostic')
                    print('TOOLCHAIN_DEPENDENCIES=' + json.dumps({'operation': OPERATION, 'ecosystem': 'NATIVE_BUILD', 'manager': 'APT', 'packages': {'cmake': '1'}}))
                ticks = [1, self.d.DependencyFailure('DEPENDENCY_TRANSACTION_TIMEOUT', True)] if not acknowledged else None
                original_lstat, original_alias = pathlib.Path.lstat, pathlib.Path.is_symlink
                with mock.patch.object(self.d, 'dependency_manifest', return_value=m), \
                     mock.patch.object(self.d, 'dependency_show', return_value=self.manager_facts(m)), \
                     mock.patch.object(self.d, 'dependency_group', return_value=([1, 2], ['123'])), \
                     mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True), \
                     mock.patch.object(self.d.os, 'getpid', return_value=123), \
                     mock.patch.dict(self.d.os.environ, {'INVOCATION_ID': 'b' * 32}), \
                     mock.patch.object(self.d, 'dependency_read', side_effect=lambda p, *args: pathlib.Path(p).read_bytes()), \
                     mock.patch.object(self.d, 'dependency_write', side_effect=write), \
                     mock.patch.object(self.d, 'dependency_remaining', side_effect=ticks, return_value=1), \
                     mock.patch.object(self.d, 'dependency_transaction', side_effect=package) as packages, \
                     mock.patch.object(self.d.pathlib.Path, 'lstat', side_effect=lambda p: types.SimpleNamespace(st_mode=stat.S_IFREG, st_dev=1, st_ino=3) if p.name == 'cgroup.events' else original_lstat(p), autospec=True), \
                     mock.patch.object(self.d.pathlib.Path, 'is_symlink', side_effect=lambda p: False if p.name == 'cgroup.events' else original_alias(p), autospec=True):
                    if acknowledged:
                        with contextlib.redirect_stdout(io.StringIO()) as diagnostics:
                            self.d.dependency_worker(str(base / 'manifest.json'))
                        self.assertEqual('MIRROR fixed-route diagnostic\n', diagnostics.getvalue())
                        envelope = json.loads((base / 'result.json').read_text())
                        self.assertEqual(hashlib.sha256((base / 'ack.json').read_bytes()).hexdigest(), envelope['ackSha256'])
                        packages.assert_called_once()
                    else:
                        with self.assertRaises(self.d.DependencyFailure):
                            self.d.dependency_worker(str(base / 'manifest.json'))
                        packages.assert_not_called()
                        self.assertFalse(self.d.DEPENDENCY_WORKER_ADMITTED)

    def test_ack_rejects_bool_identity_extra_or_wrong_core_inode(self):
        m = {**self.d.dependency_frame(FRAME), 'unit': self.d.dependency_unit(OPERATION), 'workerSourceSha256': 'f' * 64, 'runtimeSeconds': 30}
        ready = {'invocationId': 'b' * 32, 'groupIdentity': [1, 2]}
        valid = self.d.dependency_ack_record(m, ready, {'eventsIdentity': [1, 3]}, 'e' * 64)
        for mutation in ('BOOL_GROUP', 'BOOL_EVENT', 'BOOL_INITIAL', 'EXTRA', 'SOURCE', 'INODE'):
            ack = json.loads(json.dumps(valid))
            if mutation == 'BOOL_GROUP': ack['groupIdentity'] = [True, 2]
            if mutation == 'BOOL_EVENT': ack['eventsIdentity'] = [True, 3]
            if mutation == 'BOOL_INITIAL': ack['initialPopulated'] = True
            if mutation == 'EXTRA': ack['extra'] = True
            if mutation == 'SOURCE': ack['workerSourceSha256'] = '0' * 64
            if mutation == 'INODE': ack['eventsIdentity'] = [1, 4]
            with self.subTest(mutation=mutation), mock.patch.object(self.d.pathlib.Path, 'lstat', return_value=types.SimpleNamespace(st_mode=stat.S_IFREG, st_dev=1, st_ino=3)), \
                 mock.patch.object(self.d.pathlib.Path, 'is_symlink', return_value=False), self.assertRaises(self.d.DependencyFailure):
                self.d.dependency_validate_ack(m, ready, ack, 'e' * 64)


    def reset_reader(self):
        path = pathlib.Path(__file__).resolve().parents[1] / 'resources/gold/debug/windowstolinux/mod/deployment/service/standard/disposable-host-reset.py'
        namespace = {'__name__': 'pure_reset_ownership_fixture'}
        exec(compile(path.read_text(encoding='utf-8'), '<actual-reset-resource>', 'exec'), namespace)
        return namespace

    @contextlib.contextmanager
    def cleanup_ownership_fixture(self):
        g = self.reset_reader()
        worker = b'controlled fixture source; no code executes'
        source = g['digest'](worker)
        unit = self.d.dependency_unit(OPERATION)
        manifest = {'formatVersion': 2, 'profile': 'NATIVE_BUILD', 'operation': OPERATION, 'unit': unit,
            'workerSourceSha256': source, 'description': 'WTL_DEPENDENCY_2:' + OPERATION + ':' + source,
            'task': TASK, 'server': 'server-a', 'totalSeconds': 60, 'runtimeSeconds': 30,
            'deadlineMonotonicNs': 61000000000, 'workerDeadlineMonotonicNs': 31000000000,
            'exitType': 'cgroup', 'originalUnitAbsent': True, 'mirrorEnvironment': {}}
        ownership = {k: manifest[k] for k in ('formatVersion', 'operation', 'profile', 'unit', 'description',
            'workerSourceSha256', 'server', 'task', 'totalSeconds', 'runtimeSeconds', 'exitType')}
        ownership.update(invocationId='b' * 32, stopSeconds=5, reserveSeconds=25)
        data = {'manifest.json': json.dumps(manifest).encode(),
                'cleanup-ownership.json': json.dumps(ownership).encode(), 'worker.py': worker}
        def metadata(path):
            return {'type': 'file', 'uid': 0, 'gid': 0, 'mode': 0o400, 'size': len(data[path.name])}
        facts = {'LoadState': 'loaded', 'ActiveState': 'activating', 'SubState': 'start', 'MainPID': '123',
                 'ExecMainStatus': '0', 'Result': 'success', 'InvocationID': 'b' * 32,
                 'Description': manifest['description'], 'Transient': 'yes', 'ControlGroup': '/system.slice/' + unit,
                 'Type': 'exec', 'User': 'root', 'Group': 'root', 'KillMode': 'control-group', 'SendSIGKILL': 'yes',
                 'RuntimeMaxUSec': '30s', 'TimeoutStopUSec': '5s', 'RemainAfterExit': 'yes', 'Delegate': 'no',
                 'ExitType': 'cgroup', 'ActiveEnterTimestampMonotonic': '0'}
        commands = []
        def command(args):
            commands.append(args)
            return 0, '\n'.join(k + '=' + v for k, v in facts.items())
        def read(path):
            self.assertEqual('/var/lib/windowstolinux/dependency-operations/' + OPERATION, str(path.parent).replace('\\', '/'))
            self.assertNotEqual('ready.json', path.name)  # Pre-READY failures still have independent ownership.
            return data[path.name]
        with mock.patch.dict(g, {'safe_ancestors': lambda p: None, 'metadata': metadata,
             'command': command}), \
             mock.patch.object(pathlib.Path, 'read_bytes', read):
            yield g, manifest, ownership, data, facts, commands

    def test_cleanup_identifies_only_exact_root_owned_manifest_worker_and_invocation(self):
        unit = self.d.dependency_unit(OPERATION)
        with self.cleanup_ownership_fixture() as (g, manifest, ownership, data, facts, commands):
            binding = g['dependency_unit_binding'](unit)
            self.assertEqual((OPERATION, TASK, 'b' * 32), (binding['operation'], binding['task'], binding['invocationId']))
            self.assertEqual(g['digest'](data['cleanup-ownership.json']), binding['cleanupOwnershipSha256'])
            self.assertNotIn('ready.json', data)
            facts['InvocationID'] = 'c' * 32
            with self.assertRaises(g['ResetError']):
                g['dependency_unit_binding'](unit)
            facts['InvocationID'] = 'b' * 32
            manifest['operation'] = 'c' * 32
            data['manifest.json'] = json.dumps(manifest).encode()
            with self.assertRaises(g['ResetError']):
                g['dependency_unit_binding'](unit)

    def test_cleanup_accepts_only_the_two_known_manifest_schemas_and_rejects_malformed_mirror_inputs(self):
        unit = self.d.dependency_unit(OPERATION)
        for mirrored in (False, True):
            with self.subTest(mirrored=mirrored), self.cleanup_ownership_fixture() as (g, manifest, ownership, data, facts, commands):
                if mirrored:
                    manifest['mirrorEnvironment'] = {'WTL_MIRROR_POLICY': '/owned/policy.json',
                        'WTL_MIRROR_RUNTIME': '/owned/runtime.py', 'WTL_MIRROR_POLICY_SHA256': 'a' * 64,
                        'WTL_MIRROR_DEADLINE': '31.0'}
                else:
                    del manifest['mirrorEnvironment']
                data['manifest.json'] = json.dumps(manifest).encode()
                binding = g['dependency_unit_binding'](unit)
                self.assertEqual(g['digest'](data['manifest.json']), binding['manifestSha256'])
        for mirror in (None, {'UNREVIEWED': 'value'}, {'WTL_MIRROR_POLICY': '/owned/policy.json'},
                       {'WTL_MIRROR_POLICY': '/owned/policy.json', 'WTL_MIRROR_RUNTIME': '/owned/runtime.py',
                        'WTL_MIRROR_POLICY_SHA256': 'a' * 64, 'WTL_MIRROR_DEADLINE': '32.0'}):
            with self.subTest(mirror=mirror), self.cleanup_ownership_fixture() as (g, manifest, ownership, data, facts, commands):
                manifest['mirrorEnvironment'] = mirror
                data['manifest.json'] = json.dumps(manifest).encode()
                with self.assertRaises(g['ResetError']):
                    g['dependency_unit_binding'](unit)
                self.assertEqual([], commands)

    def test_cleanup_rejects_tampered_ownership_budget_types_and_incomplete_static_properties(self):
        unit = self.d.dependency_unit(OPERATION)
        variants = [('manifest', 'formatVersion', True), ('manifest', 'originalUnitAbsent', 1),
                    ('manifest', 'totalSeconds', 60.0), ('manifest', 'runtimeSeconds', True),
                    ('manifest', 'workerDeadlineMonotonicNs', 32000000000),
                    ('ownership', 'formatVersion', True), ('ownership', 'stopSeconds', True),
                    ('ownership', 'reserveSeconds', 25.0), ('ownership', 'task', OPERATION),
                    ('ownership', 'invocationId', '0' * 32), ('ownership', 'extra', True),
                    ('facts', 'ExitType', 'main'), ('facts', 'Group', None),
                    ('facts', 'RuntimeMaxUSec', '31s'), ('facts', 'ControlGroup', '/system.slice/foreign.service')]
        for target, key, value in variants:
            with self.subTest(target=target, key=key), self.cleanup_ownership_fixture() as (g, manifest, ownership, data, facts, commands):
                record = {'manifest': manifest, 'ownership': ownership, 'facts': facts}[target]
                if value is None:
                    del record[key]
                else:
                    record[key] = value
                if target != 'facts':
                    data['manifest.json' if target == 'manifest' else 'cleanup-ownership.json'] = json.dumps(record).encode()
                with self.assertRaises(g['ResetError']):
                    g['dependency_unit_binding'](unit)
                if target != 'facts':
                    self.assertEqual([], commands)
        with self.cleanup_ownership_fixture() as (g, manifest, ownership, data, facts, commands):
            data['cleanup-ownership.json'] = data['cleanup-ownership.json'][:-1] + b',"formatVersion":2}'
            with self.assertRaises(g['ResetError']) as failure:
                g['dependency_unit_binding'](unit)
            self.assertEqual('DEPENDENCY_OWNERSHIP_DUPLICATE', failure.exception.code)
            self.assertEqual([], commands)

    def test_cleanup_reset_rechecks_original_invocation_and_terminal_state(self):
        unit = self.d.dependency_unit(OPERATION)
        with self.cleanup_ownership_fixture() as (g, manifest, ownership, data, facts, commands):
            binding = g['dependency_unit_binding'](unit)
            with self.assertRaises(g['ResetError']):
                g['dependency_unit_binding'](unit, terminal=True)
            facts.update(ActiveState='failed', SubState='failed', MainPID='0', Result='timeout', ControlGroup='')
            self.assertEqual(binding, g['dependency_unit_binding'](unit, terminal=True))
            facts['InvocationID'] = 'c' * 32
            with self.assertRaises(g['ResetError']):
                g['dependency_unit_binding'](unit, terminal=True)

    def test_cleanup_prefix_never_grants_ownership_and_foreign_owner_is_not_queried(self):
        g = self.reset_reader()
        with self.assertRaises(g['ResetError']):
            g['dependency_unit_binding']('windowstolinux-dependency-unowned.service')
        events = []
        with mock.patch.dict(g, {'safe_ancestors': lambda p: None,
             'metadata': lambda p: {'type': 'file', 'uid': 123, 'gid': 0, 'mode': 0o400, 'size': 1},
             'command': lambda args: events.append(args)}), self.assertRaises(g['ResetError']):
            g['dependency_unit_binding'](self.d.dependency_unit(OPERATION))
        self.assertEqual([], events)

    def test_apply_refuses_reset_failed_when_dependency_terminal_binding_drifts(self):
        g = self.reset_reader()
        unit = self.d.dependency_unit(OPERATION)
        binding = {'operation': OPERATION, 'task': TASK, 'server': 'server-a',
                   'manifestSha256': 'a' * 64, 'cleanupOwnershipSha256': 'b' * 64,
                   'workerSourceSha256': 'c' * 64, 'invocationId': 'd' * 32}
        selected = {'units': [unit], 'unitStates': {unit: {'dependencyOwnership': binding}}}
        events = []

        def current_binding(target, terminal=False):
            self.assertEqual(unit, target)
            events.append(('binding', terminal))
            return dict(binding, invocationId='e' * 32) if terminal else dict(binding)

        def command(args, **kwargs):
            events.append(('command', tuple(args)))
            if args == ['/usr/bin/systemctl', 'stop', '--', unit]:
                return 0, ''
            if args == ['/usr/bin/systemctl', 'list-units', '--all', '--no-legend', '--no-pager', '--plain']:
                return 0, unit + ' loaded failed failed\n'
            self.fail('Unexpected mutation after dependency ownership drift')

        with mock.patch.dict(g, {'plan': lambda: selected, 'dependency_unit_binding': current_binding,
                                 'command': command}), \
             mock.patch.object(g['os'], 'geteuid', return_value=0, create=True), \
             mock.patch.object(g['subprocess'], 'Popen') as spawn, \
             mock.patch.object(g['os'], 'kill') as kill, \
             mock.patch.object(g['os'], 'killpg', create=True) as kill_group, \
             self.assertRaises(g['ResetError']) as failure:
            g['apply'](selected)
        self.assertEqual('DEPENDENCY_UNIT_DRIFT', failure.exception.code)
        self.assertEqual([
            ('binding', False),
            ('command', ('/usr/bin/systemctl', 'stop', '--', unit)),
            ('command', ('/usr/bin/systemctl', 'list-units', '--all', '--no-legend', '--no-pager', '--plain')),
            ('binding', True),
        ], events)
        spawn.assert_not_called()
        kill.assert_not_called()
        kill_group.assert_not_called()


if __name__ == '__main__':
    unittest.main()
