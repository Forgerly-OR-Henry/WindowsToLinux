"""Real temporary-file recovery tests with explicitly controlled Linux kernel primitives."""
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

from test_agent_toolchain_isolation import controller, resource, AGENT

TASK = '22222222-2222-2222-2222-222222222222'
OP = '1234567890abcdef1234567890abcdef'
LAYOUT = '{"formatVersion":1,"remote":{"helperDirectory":"/usr/local/lib/windowstolinux","applicationsRoot":"/opt/windowstolinux/apps","stateRoot":"/var/lib/windowstolinux","configurationRoot":"/etc/opt/windowstolinux/apps","dataRoot":"/var/opt/windowstolinux/apps"}}'


class Crash(BaseException):
    pass


class AgentTrackedBehaviorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name) / 'toolchains'
        self.root.mkdir()
        self.c = controller()
        exec(compile(resource(AGENT / 'agent-toolchain-tracked.py'), '<tracked-resource>', 'exec'), self.c.__dict__)
        self.c.ROOT = self.root
        self.c.PROGRAM_SOURCE = b'controlled complete controller bytes'
        source_sha = hashlib.sha256(self.c.PROGRAM_SOURCE).hexdigest()
        self.recipe = dict(formatVersion=1, taskId=TASK, serverId='fixture-server', fingerprintSha256='b' * 64,
            remoteLayout=LAYOUT, phase='PREPARE', operationId=OP, version='c' * 64, sourceSha256=source_sha,
            executedSourceSha256=source_sha, invocationKind='INSTALLED',
            originalArgv=['prepare', 'NODE', '22', '-', '60', '-', OP, 'fixture-release'])
        self.c.AGENT_TRACKED_STORE_BINDING = dict(version='c' * 64, sourceSha256=source_sha,
            layoutSha256=hashlib.sha256(LAYOUT.encode()).hexdigest())
        self.c.owned_directory = lambda path: None
        self.c.agent_tracked_sync = lambda path: None
        self.c.agent_tracked_layout = lambda value: hashlib.sha256(value.encode()).hexdigest()
        self.c.resolution_decode = lambda *args: dict(version='22.0.0', url='https://nodejs.org/fixed',
            algorithm='sha256', digest='a' * 64, layout='archive')
        self.c.agent_tracked_boot = lambda: '33333333-3333-3333-3333-333333333333'
        self.c.agent_tracked_unit = lambda state: ('ABSENT', None)
        self.c.agent_tracked_group = lambda state, unit: ('ABSENT', None)
        self.c.agent_tracked_loop = lambda device: None
        self.c.agent_tracked_mounts = lambda: []
        self.c.agent_tool = lambda name: '/usr/bin/' + name
        original_stat = self.c.agent_tracked_stat
        def owned_stat(path):
            value = original_stat(path)
            if value is None:
                return None
            value['uid'] = value['gid'] = 0
            value['mode'] = 0o700 if value['directory'] else 0o600
            return value
        self.c.agent_tracked_stat = owned_stat
        self.fcntl = types.SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=mock.Mock())
        if os.name == 'nt':
            original_open = self.c.os.open
            # Windows maps a Unix 0444 creation mode to READONLY and cannot atomically replace it.
            # Production ownership, flock, fsync and Unix modes remain explicitly controlled here.
            patcher = mock.patch.object(self.c.os, 'open',
                lambda path, flags, mode=0o777, **kw: original_open(path, flags | os.O_BINARY, 0o600, **kw))
            patcher.start()
            self.addCleanup(patcher.stop)
            for name in ('O_NOFOLLOW', 'O_CLOEXEC', 'O_DIRECTORY'):
                patcher = mock.patch.object(self.c.os, name, 0, create=True)
                patcher.start()
                self.addCleanup(patcher.stop)
            patcher = mock.patch.object(self.c.os, 'fchmod', lambda fd, mode: None, create=True)
            patcher.start()
            self.addCleanup(patcher.stop)

    def raw(self, recipe=None):
        return json.dumps(recipe or self.recipe, ensure_ascii=False, separators=(',', ':')).encode()

    def invoke(self, verb, raw=None):
        raw = self.raw() if raw is None else raw
        self.c.AGENT_TRACKED_REQUEST_RAW = raw
        args = [verb, TASK, OP, hashlib.sha256(raw).hexdigest()]
        stream = io.StringIO()
        # Windows cannot prove root ownership/flock/directory fsync; these primitives are explicitly controlled.
        with mock.patch.dict('sys.modules', fcntl=self.fcntl), mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True), \
             contextlib.redirect_stdout(stream):
            self.c.agent_tracked_main(args)
        lines = stream.getvalue().splitlines()
        self.assertEqual(1, len(lines))
        self.assertTrue(lines[0].startswith('AGENT_TOOLCHAIN_TRACKED='))
        return json.loads(lines[0].split('=', 1)[1])

    def state(self):
        return json.loads((self.root / 'work' / ('agent-' + OP) / 'creation-state.json').read_bytes())

    def save_state(self, state):
        self.c.agent_tracked_replace(self.root / 'work' / ('agent-' + OP) / 'creation-state.json', state)

    def interrupted(self):
        def crash(args):
            raise Crash()
        self.c.agent_prepare = crash
        with self.assertRaises(Crash):
            self.invoke('prepare-tracked')
        return self.root / 'work' / ('agent-' + OP)

    def image(self, base):
        path = base / 'volume.ext4'
        path.write_bytes(b'operation-private-image')
        state = self.state()
        state['steps']['IMAGE_CREATE'] = dict(state='OBSERVED', value=dict(
            path='volume.ext4', identity=self.c.agent_tracked_stat(path)['identity']))
        self.save_state(state)
        return path

    def test_missing_and_legacy_operations_remain_unknown(self):
        for legacy in (False, True):
            if legacy:
                (self.root / 'work' / ('agent-' + OP)).mkdir(parents=True)
            receipt = self.invoke('observe-tracked')
            self.assertEqual('UNKNOWN', receipt['executionStatus'])
            self.assertEqual('UNSETTLED', receipt['effectState'])
            self.assertEqual('MISSING', receipt['proofState'])
            self.assertEqual('LEGACY_PROOF_MISSING' if legacy else 'OPERATION_MISSING', receipt['failureCode'])

    def test_request_identity_and_raw_byte_changes_are_rejected(self):
        self.c.agent_prepare = lambda args: None
        self.invoke('prepare-tracked')
        changed = {**self.recipe, 'fingerprintSha256': 'd' * 64}
        receipt = self.invoke('observe-tracked', self.raw(changed))
        self.assertEqual('CONFLICT', receipt['proofState'])
        self.assertEqual('UNSETTLED', receipt['effectState'])
        self.assertEqual('IDENTITY_MISMATCH', receipt['failureCode'])

    def test_side_effect_intent_is_durable_before_image_allocation(self):
        def create(args):
            self.c.agent_tracked_step('IMAGE_CREATE', {'path': 'volume.ext4'})
            self.assertEqual('INTENT', self.state()['steps']['IMAGE_CREATE']['state'])
            raise Crash()
        self.c.agent_prepare = create
        with self.assertRaises(Crash):
            self.invoke('prepare-tracked')
        receipt = self.invoke('observe-tracked')
        self.assertEqual('UNKNOWN', receipt['executionStatus'])
        self.assertEqual('VERIFIED', receipt['proofState'])

    def test_unknown_cleanup_is_never_reissued(self):
        def create(args):
            self.c.agent_tracked_step('IMAGE_CREATE', {'path': 'volume.ext4'})
            raise self.c.PreparationFailure('installation', 'FIXTURE_FAILED')
        self.c.agent_prepare = create
        self.invoke('prepare-tracked')
        state = self.state()
        state['cleanupStarted'] = True
        self.c.agent_tracked_replace(self.root / 'work' / ('agent-' + OP) / 'creation-state.json', state)
        before = (self.root / 'work' / ('agent-' + OP) / 'creation-state.json').read_bytes()
        receipt = self.invoke('cleanup-tracked')
        self.assertEqual('INTERRUPTED', receipt['failureCode'])
        self.assertEqual(before, (self.root / 'work' / ('agent-' + OP) / 'creation-state.json').read_bytes())

    def test_half_created_image_is_observed_then_cleaned_without_worker_replay(self):
        base = self.interrupted()
        image = self.image(base)
        forbidden = mock.Mock(side_effect=AssertionError('original worker must never run'))
        self.c.agent_prepare = self.c.agent_bind = forbidden
        self.c.agent_os = mock.Mock(return_value=(0, b''))
        before = self.invoke('observe-tracked')
        self.assertEqual('OWNED_PRESENT', before['resources']['image'])
        self.assertEqual('UNSETTLED', before['effectState'])
        result = self.invoke('cleanup-tracked')
        self.assertEqual('UNKNOWN', result['executionStatus'])
        self.assertEqual('SETTLED_NO_EFFECTS', result['effectState'])
        self.assertEqual('VERIFIED', result['cleanupState'])
        self.assertFalse(image.exists())
        self.assertTrue((base / 'controller.py').exists())
        self.assertTrue((base / 'creation.json').exists())
        forbidden.assert_not_called()

    def test_cleanup_ack_loss_is_resolved_by_fresh_observation_only(self):
        base = self.interrupted()
        self.image(base)
        self.c.agent_os = mock.Mock(return_value=(0, b''))
        self.invoke('cleanup-tracked')  # The caller loses this receipt.
        self.c.agent_os = mock.Mock(side_effect=AssertionError('cleanup must not be replayed'))
        observed = self.invoke('observe-tracked')
        self.assertEqual('UNKNOWN', observed['executionStatus'])
        self.assertEqual('SETTLED_NO_EFFECTS', observed['effectState'])
        self.assertEqual('VERIFIED', observed['cleanupState'])
        self.c.agent_os.assert_not_called()

    def test_image_identity_conflict_keeps_unknown_and_never_deletes(self):
        base = self.interrupted()
        image = self.image(base)
        state = self.state()
        state['steps']['IMAGE_CREATE']['value']['identity'][1] += 1
        self.save_state(state)
        self.c.agent_os = mock.Mock(side_effect=AssertionError('conflicting resource must not be touched'))
        observed = self.invoke('cleanup-tracked')
        self.assertEqual('UNSETTLED', observed['effectState'])
        self.assertEqual('RESOURCE_CHANGED', observed['failureCode'])
        self.assertTrue(image.exists())
        self.assertFalse(self.state()['cleanupStarted'])

    def test_original_failed_worker_remains_failed_after_successful_cleanup(self):
        def failed(args):
            raise self.c.PreparationFailure('installation', 'FIXTURE_FAILED')
        self.c.agent_prepare = failed
        first = self.invoke('prepare-tracked')
        self.assertEqual('FAILED', first['executionStatus'])
        self.assertEqual('EXECUTION_FAILED', first['failureCode'])
        cleaned = self.invoke('cleanup-tracked')
        self.assertEqual('FAILED', cleaned['executionStatus'])
        self.assertEqual('SETTLED_NO_EFFECTS', cleaned['effectState'])
        self.assertIsNone(cleaned['failureCode'])

    def test_cleanup_stops_only_verified_original_unit_before_removing_image(self):
        base = self.interrupted()
        image = self.image(base)
        active = [True]
        ready = dict(invocationId='d' * 32)
        self.c.agent_tracked_unit = lambda state: ('OWNED_PRESENT' if active[0] else 'QUIESCENT', ready)
        self.c.agent_tracked_group = lambda state, unit: ('OWNED_PRESENT' if active[0] else 'QUIESCENT', [1, 2])
        actions = []
        self.c.agent_tool = lambda name: '/usr/bin/' + name
        def command(argv, **kw):
            actions.append(argv)
            if argv[1] == 'stop':
                self.assertTrue(image.exists())
                self.assertEqual(self.c.agent_unit(OP), argv[2])
                active[0] = False
            return 0, b''
        self.c.agent_os = command
        result = self.invoke('cleanup-tracked')
        self.assertEqual('SETTLED_NO_EFFECTS', result['effectState'])
        self.assertEqual('/usr/bin/systemctl', actions[0][0])
        self.assertFalse(image.exists())

    def test_parent_exited_but_original_process_group_alive_is_not_quiescent(self):
        self.interrupted()
        state = self.state()
        state['children'] = [dict(pid=123, group=123, start='5', boot=self.c.agent_tracked_boot(), ended=True)]
        self.save_state(state)
        self.c.agent_tracked_group_members = lambda group: [456]
        receipt = self.invoke('observe-tracked')
        self.assertEqual('ACTIVE_CHILD', receipt['failureCode'])
        self.assertEqual('UNSETTLED', receipt['effectState'])

    def test_child_launch_before_pid_record_stays_unknown(self):
        self.interrupted()
        state = self.state()
        state['steps']['CHILD_SPAWN_0'] = dict(state='INTENT', value=dict(boot=self.c.agent_tracked_boot()))
        self.save_state(state)
        receipt = self.invoke('observe-tracked')
        self.assertEqual('ACTIVE_CHILD', receipt['failureCode'])
        self.assertEqual('UNSETTLED', receipt['effectState'])

    def test_bind_commit_before_ack_retains_binding_and_cleans_only_private_temp(self):
        payload = b'WTL-TOOLS-1\tapi\nNODE\tfixture\n'
        self.recipe.update(phase='BIND', originalArgv=['bind', base64.b64encode(payload).decode(), '60', OP])
        target = self.root / 'bindings' / hashlib.sha256(payload).hexdigest()
        original_step = self.c.agent_tracked_step
        def step(name, value=None, observed=False):
            original_step(name, value, observed)
            if name == 'BIND_PUBLISH' and observed:
                raise Crash()
        self.c.agent_tracked_step = step
        self.c.agent_bind = lambda args: self.c.agent_tracked_atomic_binding(target, payload)
        with self.assertRaises(Crash):
            self.invoke('bind-tracked')
        self.c.agent_tracked_step = original_step
        before = self.invoke('observe-tracked')
        self.assertEqual('UNKNOWN', before['executionStatus'])
        self.assertEqual(['IMMUTABLE_BINDING'], before['retainedEffects'])
        self.assertEqual('OWNED_PRESENT', before['resources']['bindingTemporary'])
        self.c.agent_prepare = self.c.agent_bind = mock.Mock(side_effect=AssertionError('no original replay'))
        cleaned = self.invoke('cleanup-tracked')
        self.assertEqual('SETTLED_RETAINED_EFFECTS', cleaned['effectState'])
        self.assertEqual('UNKNOWN', cleaned['executionStatus'])
        self.assertEqual(payload, target.read_bytes())
        self.assertFalse((target.parent.parent / 'work' / ('agent-' + OP) / 'binding.tmp').exists())

    def test_removed_original_successful_binding_cannot_be_a_success(self):
        payload = b'WTL-TOOLS-1\tapi\nNODE\tfixture\n'
        self.recipe.update(phase='BIND', originalArgv=['bind', base64.b64encode(payload).decode(), '60', OP])
        def bind(args):
            target = self.root / 'bindings' / hashlib.sha256(payload).hexdigest()
            self.c.agent_tracked_atomic_binding(target, payload)
            print('TOOLCHAIN_BINDING=' + target.name)
        self.c.agent_bind = bind
        first = self.invoke('bind-tracked')
        self.assertEqual('SUCCEEDED', first['executionStatus'])
        (self.root / 'bindings' / first['bindingSha256']).unlink()
        receipt = self.invoke('observe-tracked')
        self.assertEqual('RESOURCE_CHANGED', receipt['failureCode'])
        self.assertEqual('UNSETTLED', receipt['effectState'])

    def test_locked_original_operation_is_never_observed_as_settled(self):
        self.fcntl.flock.side_effect = BlockingIOError()
        receipt = self.invoke('observe-tracked')
        self.assertEqual('LOCK_BUSY', receipt['failureCode'])
        self.assertEqual('UNSETTLED', receipt['effectState'])

    def test_changed_original_controller_source_is_not_executed_or_cleaned(self):
        base = self.interrupted()
        (base / 'controller.py').write_bytes(b'changed source')
        self.c.agent_os = mock.Mock(side_effect=AssertionError('changed source must never run'))
        receipt = self.invoke('cleanup-tracked')
        self.assertEqual('CONFLICT', receipt['proofState'])
        self.assertEqual('IDENTITY_MISMATCH', receipt['failureCode'])
        self.c.agent_os.assert_not_called()

    def mounted_image(self):
        base = self.interrupted()
        image = self.image(base)
        image.write_bytes(b'\0' * 1128 + bytes.fromhex(OP))
        mutable = base / 'mutable'
        mutable.mkdir()
        state = self.state()
        state['steps']['LOOP_ATTACH'] = dict(state='INTENT', value=dict(device='/dev/loop7', rdev=7, sizeLimit=4096))
        state['steps']['MOUNT'] = dict(state='INTENT', value=dict(directoryIdentity=self.c.agent_tracked_stat(mutable)['identity']))
        self.save_state(state)
        active = dict(loop=True, mount=True)
        loop = dict(device='/dev/loop7', rdev=7, backingIdentity=self.c.agent_tracked_stat(image)['identity'], offset=0, sizeLimit=4096)
        mount = dict(id=88, rdev=7, target=str(mutable), source='/dev/loop7', root='/', filesystem='ext4', options=['nodev', 'nosuid'])
        self.c.agent_tracked_loop = lambda device: loop if active['loop'] else None
        self.c.agent_tracked_mounts = lambda: [mount] if active['mount'] else []
        return base, image, active, loop, mount

    def test_mount_and_loop_attach_before_post_record_are_recovered_by_full_identity(self):
        base, image, active, loop, mount = self.mounted_image()
        observed = self.invoke('observe-tracked')
        self.assertEqual('OWNED_PRESENT', observed['resources']['loop'])
        self.assertEqual('OWNED_PRESENT', observed['resources']['mount'])
        calls = []
        def command(argv, **kw):
            calls.append(argv)
            if argv[0].endswith('/umount'):
                self.assertEqual(str(base / 'mutable'), argv[-1])
                self.assertTrue(active['loop'])
                active['mount'] = False
            elif '--detach' in argv:
                self.assertFalse(active['mount'])
                self.assertEqual('/dev/loop7', argv[-1])
                self.assertTrue(image.exists())
                active['loop'] = False
            return 0, b''
        self.c.agent_os = command
        cleaned = self.invoke('cleanup-tracked')
        self.assertEqual('SETTLED_NO_EFFECTS', cleaned['effectState'])
        self.assertFalse(image.exists())
        self.assertEqual(['umount', 'losetup', 'losetup'], [pathlib.PurePosixPath(call[0]).name for call in calls])

    def test_loop_backing_inode_change_never_detaches_foreign_device(self):
        base, image, active, loop, mount = self.mounted_image()
        loop['backingIdentity'][1] += 1
        self.c.agent_os = mock.Mock(side_effect=AssertionError('foreign loop must not detach'))
        receipt = self.invoke('cleanup-tracked')
        self.assertEqual('RESOURCE_CHANGED', receipt['failureCode'])
        self.assertEqual('UNSETTLED', receipt['effectState'])
        self.assertTrue(image.exists())
        self.c.agent_os.assert_not_called()

    def test_foreign_filesystem_uuid_never_unmounts(self):
        base, image, active, loop, mount = self.mounted_image()
        image.write_bytes(b'\0' * 1128 + bytes.fromhex('f' * 32))
        self.c.agent_os = mock.Mock(side_effect=AssertionError('foreign mount must not be touched'))
        receipt = self.invoke('cleanup-tracked')
        self.assertEqual('RESOURCE_CHANGED', receipt['failureCode'])
        self.assertTrue(active['loop'] and active['mount'])
        self.c.agent_os.assert_not_called()

    def test_empty_placeholder_created_before_identity_record_is_safely_removed(self):
        self.interrupted()
        placeholder = self.root / 'versions' / ('node-' + 'a' * 64)
        state = self.state()
        state['steps']['PLACEHOLDER'] = dict(state='INTENT', value=dict(path=str(placeholder),
            parentIdentity=self.c.agent_tracked_stat(placeholder.parent)['identity']))
        self.save_state(state)
        placeholder.mkdir()
        self.assertEqual('OWNED_PRESENT', self.invoke('observe-tracked')['resources']['placeholder'])
        cleaned = self.invoke('cleanup-tracked')
        self.assertEqual('SETTLED_NO_EFFECTS', cleaned['effectState'])
        self.assertFalse(placeholder.exists())

    def test_promotion_commit_before_post_record_retains_shared_installation(self):
        self.interrupted()
        destination = self.root / 'versions' / ('node-' + 'a' * 64)
        destination.mkdir()
        marker = b'controlled installed marker'
        (destination / '.w2l-toolchain.json').write_bytes(marker)
        state = self.state()
        state['steps']['PLACEHOLDER'] = dict(state='OBSERVED', value=dict(path=str(destination), identity=[1, 2]))
        state['steps']['PROMOTION_PUBLISH'] = dict(state='INTENT', value=dict(
            identity=self.c.agent_tracked_stat(destination)['identity'], markerSha256=hashlib.sha256(marker).hexdigest()))
        self.save_state(state)
        self.c.agent_record = lambda path: dict(agent_program_sha256=self.recipe['executedSourceSha256'])
        self.c.agent_regular = lambda path: path.read_bytes()
        observed = self.invoke('observe-tracked')
        self.assertEqual('RETAINED_SHARED', observed['resources']['placeholder'])
        self.assertEqual(['SHARED_INSTALLATION'], observed['retainedEffects'])
        self.assertEqual('SETTLED_RETAINED_EFFECTS', observed['effectState'])
        self.assertEqual('UNKNOWN', observed['executionStatus'])
        self.assertTrue(destination.exists())


if __name__ == '__main__':
    unittest.main()
