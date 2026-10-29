"""Actual shared source, pure manager/process seams only; no systemd, apt, SSH or kernel proof."""
import contextlib
import io
import json
import os
import pathlib
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
        spawn.assert_not_called()  # No fresh 1800-second allowance for the next command.

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

    @contextlib.contextmanager
    def supervision(self, fault=None):
        with tempfile.TemporaryDirectory(prefix='wtl-dependency-pure-') as temporary:
            root = pathlib.Path(temporary)
            self.d.DEPENDENCY_OPERATIONS_ROOT = str(root)
            self.d.DEPENDENCY_WORKER_SOURCE = 'fixed synthetic worker; never executed'
            events, observations = [], []
            clock = [1000000000]
            process = mock.Mock()
            process.stdout = mock.Mock()
            process.poll.return_value = None
            process.returncode = 0
            def communicate(**kw):
                self.assertIn('stop', events)
                events.append('wait-launcher')
                return b'', None
            process.communicate.side_effect = communicate
            def group(manifest, empty=False):
                self.assertTrue(empty)
                events.append('real-empty-check-seam')
                if fault == 'nonempty':
                    self.d.dependency_reject('DEPENDENCY_CGROUP_NOT_EMPTY', True)
                if fault == 'missing-group':
                    self.d.dependency_reject('DEPENDENCY_CGROUP_UNAVAILABLE', True)
                return [1, 2], []
            def show(unit):
                if not observations:
                    observations.append('initial')
                    return {'LoadState': 'loaded' if fault == 'collision' else 'not-found'}
                if 'stop' in events:
                    return {'LoadState': 'not-found'}
                manifest = json.loads((root / OPERATION / 'manifest.json').read_text())
                return {'LoadState': 'loaded', 'ActiveState': 'active', 'SubState': 'exited', 'MainPID': '0',
                    'ExecMainStatus': '0', 'Result': 'success', 'InvocationID': ('c' if fault == 'identity' else 'b') * 32,
                    'ControlGroup': '/system.slice/' + unit, 'Description': manifest['description'], 'Transient': 'yes',
                    'Type': 'exec', 'User': 'root', 'Group': 'root', 'KillMode': 'control-group', 'SendSIGKILL': 'yes',
                    'RuntimeMaxUSec': str(manifest['runtimeSeconds']) + 's', 'TimeoutStopUSec': '5s',
                    'RemainAfterExit': 'yes', 'Delegate': 'no'}
            def spawn(arguments, **kw):
                events.append('launch')
                self.assertIn('--wait', arguments)
                for prop in ('Type=exec', 'RemainAfterExit=yes', 'KillMode=control-group', 'SendSIGKILL=yes'):
                    self.assertIn('--property=' + prop, arguments)
                self.assertFalse(any('DynamicUser' in arg or 'ProtectSystem' in arg for arg in arguments))
                manifest = json.loads((root / OPERATION / 'manifest.json').read_text())
                self.assertLessEqual(manifest['runtimeSeconds'] + 5 + 25, 60)
                ready = {'operation': OPERATION, 'workerSourceSha256': manifest['workerSourceSha256'],
                         'invocationId': 'b' * 32, 'groupIdentity': [1, 2]}
                if fault == 'bool-group':
                    ready['groupIdentity'] = [True, 2]
                (root / OPERATION / 'ready.json').write_text(json.dumps(ready))
                (root / OPERATION / 'result.json').write_text(json.dumps({'operation': OPERATION,
                    'ecosystem': 'NATIVE_BUILD', 'manager': 'APT', 'packages': {'cmake': '1'}}))
                return process
            def control(arguments, **kw):
                self.assertEqual(['/usr/bin/systemctl', 'stop', self.d.dependency_unit(OPERATION)], arguments)
                events.append('stop')
                process.poll.return_value = 0
                return 0, ''
            def write(path, data):
                path.write_bytes(data)
                if fault == 'budget-before-launch' and path.name == 'manifest.json':
                    clock[0] += 40000000000
            selector = mock.MagicMock()
            selector.select.return_value = []
            original_is_file = pathlib.Path.is_file
            def is_file(path):
                return True if path.as_posix() == '/sys/fs/cgroup/cgroup.controllers' else original_is_file(path)
            with mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True), \
                 mock.patch.object(self.d.os, 'O_NOFOLLOW', 0, create=True), \
                 mock.patch.object(self.d.time, 'monotonic_ns', side_effect=lambda: clock[0]), \
                 mock.patch.object(self.d, 'dependency_directory', return_value=root), \
                 mock.patch.object(self.d, 'dependency_protected', side_effect=lambda p: pathlib.Path(p)), \
                 mock.patch.object(self.d, 'dependency_write', side_effect=write), \
                 mock.patch.object(self.d, 'dependency_tool', side_effect=lambda name: '/usr/bin/' + name), \
                 mock.patch.object(self.d, 'dependency_show', side_effect=show), \
                 mock.patch.object(self.d, 'dependency_group', side_effect=group), \
                 mock.patch.object(self.d, 'dependency_control', side_effect=control), \
                 mock.patch.object(self.d.pathlib.Path, 'is_file', is_file), \
                 mock.patch.object(self.d.subprocess, 'Popen', side_effect=spawn) as popen, \
                 mock.patch.object(self.d.selectors, 'DefaultSelector', return_value=selector):
                yield events, popen

    def test_completion_is_observed_and_empty_checked_before_stop_and_wait(self):
        output = io.StringIO()
        with self.supervision() as (events, spawn), contextlib.redirect_stdout(output):
            self.d.dependency_supervise(FRAME)
        self.assertEqual(['launch', 'real-empty-check-seam', 'stop', 'wait-launcher'], events)
        spawn.assert_called_once()
        receipt = json.loads(output.getvalue().removeprefix('TOOLCHAIN_DEPENDENCIES='))
        self.assertTrue(receipt['lifecycle']['recursiveCgroupEmptyObserved'])
        self.assertTrue(receipt['lifecycle']['loadedCompletionObserved'])
        self.assertTrue(receipt['lifecycle']['stopCompleted'])

    def test_collision_identity_or_missing_nonempty_cgroup_never_emits_success(self):
        for fault in ('collision', 'identity', 'missing-group', 'nonempty', 'bool-group', 'budget-before-launch'):
            output = io.StringIO()
            with self.subTest(fault=fault), self.supervision(fault) as (events, spawn), \
                 contextlib.redirect_stdout(output), self.assertRaises(self.d.DependencyFailure):
                self.d.dependency_supervise(FRAME)
            self.assertEqual('', output.getvalue())
            self.assertNotIn('wait-launcher', events)
            if fault in ('collision', 'budget-before-launch'):
                spawn.assert_not_called()

    def test_worker_rejects_wrong_manager_properties_before_any_package_action(self):
        self.d.__file__ = '/owned/' + OPERATION + '/worker.py'
        manifest = {'unit': self.d.dependency_unit(OPERATION), 'workerDeadlineMonotonicNs': 1000000000,
                    'description': 'exact-owned-description', 'runtimeSeconds': 30}
        with mock.patch.object(self.d, 'dependency_manifest', return_value=manifest), \
             mock.patch.object(self.d, 'dependency_show', return_value={}), \
             mock.patch.object(self.d, 'dependency_transaction') as packages, self.assertRaises(self.d.DependencyFailure):
            self.d.dependency_worker('/owned/' + OPERATION + '/manifest.json')
        packages.assert_not_called()

    def reset_reader(self):
        path = pathlib.Path(__file__).resolve().parents[1] / 'resources/gold/debug/windowstolinux/mod/deployment/service/standard/disposable-host-reset.py'
        namespace = {'__name__': 'pure_reset_ownership_fixture'}
        exec(compile(path.read_text(encoding='utf-8'), '<actual-reset-resource>', 'exec'), namespace)
        return namespace

    def test_cleanup_identifies_only_exact_root_owned_manifest_worker_and_invocation(self):
        g = self.reset_reader()
        worker = b'controlled fixture source; no code executes'
        source = g['digest'](worker)
        unit = self.d.dependency_unit(OPERATION)
        manifest = {'formatVersion': 1, 'operation': OPERATION, 'unit': unit, 'workerSourceSha256': source,
            'description': 'WTL_DEPENDENCY_1:' + OPERATION + ':' + source, 'task': TASK, 'server': 'server-a'}
        ready = {'operation': OPERATION, 'workerSourceSha256': source, 'invocationId': 'b' * 32}
        data = {'manifest.json': json.dumps(manifest).encode(), 'ready.json': json.dumps(ready).encode(), 'worker.py': worker}
        def metadata(path):
            return {'type': 'file', 'uid': 0, 'gid': 0, 'mode': 0o400, 'size': len(data[path.name])}
        facts = {'LoadState': 'loaded', 'InvocationID': 'b' * 32, 'Description': manifest['description'],
                 'Transient': 'yes', 'ControlGroup': '/system.slice/' + unit}
        def read(path):
            self.assertEqual('/var/lib/windowstolinux/dependency-operations/' + OPERATION, str(path.parent).replace('\\', '/'))
            return data[path.name]
        with mock.patch.dict(g, {'safe_ancestors': lambda p: None, 'metadata': metadata,
             'command': lambda args: (0, '\n'.join(k + '=' + v for k, v in facts.items()))}), \
             mock.patch.object(pathlib.Path, 'read_bytes', read):
            binding = g['dependency_unit_binding'](unit)
            self.assertEqual((OPERATION, TASK, 'b' * 32), (binding['operation'], binding['task'], binding['invocationId']))
            facts['InvocationID'] = 'c' * 32
            with self.assertRaises(g['ResetError']):
                g['dependency_unit_binding'](unit)
            facts['InvocationID'] = 'b' * 32
            manifest['operation'] = 'c' * 32
            data['manifest.json'] = json.dumps(manifest).encode()
            with self.assertRaises(g['ResetError']):
                g['dependency_unit_binding'](unit)

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


if __name__ == '__main__':
    unittest.main()
