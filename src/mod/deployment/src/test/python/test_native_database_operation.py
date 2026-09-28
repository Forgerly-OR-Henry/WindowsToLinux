"""Exercise the bundled native DB receipt path without touching a real server. / 离线执行随附原生数据库回执路径。"""

import base64
import hashlib
import io
import json
import pathlib
import stat
import sys
import tempfile
import types
import unittest
import uuid
import contextlib
from unittest.mock import patch
from types import SimpleNamespace

if sys.platform == 'win32':
    sys.modules.setdefault('fcntl', types.SimpleNamespace(LOCK_EX=1, LOCK_NB=2, flock=lambda *args: None))

FRAGMENTS = (
    pathlib.Path(__file__).resolve().parents[2]
    / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/execution/protocol/helper/fragments/database'
)
SOURCE = ''.join((FRAGMENTS / part).read_text(encoding='utf-8') for part in
                 ('10-native-instances.sh', '20-native-targets.sh'))
CODE = SOURCE.split("<<'WTL_NATIVE_DB_PY'\n", 1)[1].split('\nWTL_NATIVE_DB_PY', 1)[0]
DB = {'__name__': 'native_database_operation_test'}
exec(compile(CODE, str(FRAGMENTS), 'exec'), DB)


def request():
    return {'operationId': str(uuid.uuid4()), 'taskId': str(uuid.uuid4()), 'serverId': 'server prod/1',
            'engine': 'POSTGRESQL', 'instanceId': 'postgresql.service', 'fingerprint': 'a' * 64}


def frame(values):
    return ''.join(key + '=' + base64.b64encode(value.encode()).decode() + '\n'
                   for key, value in values.items()).encode('ascii')


def read_windows_fixture(path):
    return json.loads(path.read_text()) if path.exists() else {}


class NativeDatabaseOperationTest(unittest.TestCase):
    def test_actual_main_decodes_once_and_status_reads_only_original_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            values = request()
            effects = []
            original_fdopen = DB['os'].fdopen
            incoming = [frame(values), frame({key: values[key] for key in ('operationId','taskId','serverId')})]
            def fdopen(fd, *args, **kwargs):
                if fd == 3: return io.BytesIO(incoming.pop(0))
                return original_fdopen(fd, *args, **kwargs)
            def effect(action, selected):
                effects.append(action)
                return {'instance.id': selected['instanceId']}
            with patch.dict(DB, {'state_directory': lambda: directory,
                                 'existing_state_directory': lambda: directory,
                                 'select': lambda selected: {}, 'dispatch': effect,
                                 'read_state': read_windows_fixture, 'operation_read': read_windows_fixture}), \
                    patch.object(DB['os'], 'fdopen', side_effect=fdopen):
                for action in ('start', 'status'):
                    output = io.StringIO()
                    with patch.object(sys, 'argv', ['native-db', action]), contextlib.redirect_stdout(output):
                        DB['main']()
                    decoded = {key: base64.b64decode(value).decode() for key, value in
                               (line.split('=', 1) for line in output.getvalue().splitlines())}
                    self.assertEqual('OK', decoded['status'])
                    self.assertEqual('SUCCEEDED', decoded['receipt.phase'])
                self.assertEqual(['start'], effects)

    def test_start_receipt_is_durable_before_reply_and_duplicate_never_replays(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            values = request()
            payload = frame(values)
            calls = []
            def dispatch(operation, selected):
                calls.append(operation)
                saved = DB['read_state'](DB['operation_path'](directory, values['operationId']))
                self.assertEqual('STARTED', saved['phase'])
                self.assertEqual('MAY_HAVE_RUN', saved['effectBoundary'])
                return {'instance.id': selected['instanceId']}
            with patch.dict(DB, {'select': lambda selected: {}, 'dispatch': dispatch,
                                 'read_state': read_windows_fixture, 'operation_read': read_windows_fixture,
                                 'existing_state_directory': lambda: directory}):
                result = DB['operation_dispatch']('start', values, payload, directory)
                self.assertEqual('SUCCEEDED', result['receipt.phase'])
                self.assertEqual(hashlib.sha256(payload).hexdigest(), result['receipt.requestDigest'])
                self.assertEqual('SUCCEEDED', DB['operation_status'](values)['receipt.phase'])
                with self.assertRaises(DB['Failure']):
                    DB['operation_dispatch']('start', values, payload, directory)
                self.assertEqual(['start'], calls)

    def test_status_does_not_create_missing_state_or_run_database_action(self):
        values = request()
        with patch.dict(DB, {'existing_state_directory': lambda: None,
                             'dispatch': lambda *args: self.fail('status dispatched an action')}):
            self.assertEqual({'receipt.phase': 'MISSING'}, DB['operation_status'](values))

    def test_uncertain_failure_stays_started_and_cross_task_status_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            values = request()
            with patch.dict(DB, {'select': lambda selected: {},
                                 'dispatch': lambda *args: (_ for _ in ()).throw(DB['Uncertain']()),
                                 'read_state': read_windows_fixture, 'operation_read': read_windows_fixture,
                                 'existing_state_directory': lambda: directory}):
                with self.assertRaises(DB['Uncertain']):
                    DB['operation_dispatch']('start', values, frame(values), directory)
                self.assertEqual('STARTED', DB['operation_status'](values)['receipt.phase'])
                self.assertEqual('MAY_HAVE_RUN', DB['operation_status'](values)['receipt.effectBoundary'])
                with self.assertRaises(DB['Failure']):
                    DB['operation_status'](dict(values, taskId=str(uuid.uuid4())))

    def test_read_only_preflight_failure_records_known_no_effect_result(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            values = request()
            with patch.dict(DB, {'select': lambda selected: (_ for _ in ()).throw(DB['Failure']('STATE_CHANGED')),
                                 'dispatch': lambda *args: self.fail('failed preflight dispatched an action'),
                                 'read_state': read_windows_fixture, 'operation_read': read_windows_fixture,
                                 'existing_state_directory': lambda: directory}):
                result = DB['operation_dispatch']('start', values, frame(values), directory)
                self.assertEqual('FAILED', result['receipt.phase'])
                self.assertEqual('NONE', DB['operation_status'](values)['receipt.effectBoundary'])

    def test_known_failure_after_effect_boundary_preserves_manual_review_fact(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            values = request()
            with patch.dict(DB, {'select': lambda selected: {},
                                 'dispatch': lambda *args: (_ for _ in ()).throw(DB['Failure']('MANUAL_RESTORE_REQUIRED')),
                                 'operation_read': read_windows_fixture}):
                result = DB['operation_dispatch']('start', values, frame(values), directory)
                self.assertEqual('FAILED', result['receipt.phase'])
                self.assertEqual('MANUAL_RESTORE_REQUIRED', result['receipt.failureCode'])
                self.assertEqual('MAY_HAVE_RUN', read_windows_fixture(
                    DB['operation_path'](directory, values['operationId']))['effectBoundary'])

    def test_initialize_timeout_keeps_original_target_started(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / 'target.json'
            record = {'phase': 'EMPTY', 'ownershipToken': 'original'}
            saved = []
            calls = iter(('f', '0'))
            def sql_query(*args):
                try: return next(calls)
                except StopIteration: raise DB['Uncertain']()
            values = dict(request(), applicationId='app', database='database', username='user',
                          sql='CREATE TABLE sample(id INT);', sourceSha256='a' * 64,
                          ownershipToken='original', existingApproved='false')
            with patch.dict(DB, {'select': lambda selected: {'engine': 'POSTGRESQL', 'id': 'instance'},
                                 'target_state_path': lambda *args: path,
                                 'read_state': lambda selected: record,
                                 'write_state': lambda selected, value: saved.append(dict(value)),
                                 'sql_query': sql_query}):
                with self.assertRaises(DB['Uncertain']):
                    DB['initialize'](values)
                self.assertEqual('STARTED', saved[-1]['phase'])
                before = DB['operation_target_digest']('initialize', values)
                self.assertNotEqual(before, DB['operation_target_digest']('initialize',
                                    dict(values, existingApproved='true')))
                self.assertNotEqual(before, DB['operation_target_digest']('initialize',
                                    dict(values, sourceSha256='b' * 64)))

    def test_real_status_reader_rejects_oversize_duplicate_and_inconsistent_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / 'operation.json'
            original = DB['os'].fstat
            def trusted_file(fd):
                found = original(fd)
                return SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_size=found.st_size)
            with patch.object(DB['os'], 'fstat', side_effect=trusted_file):
                path.write_bytes(b' ' * 131073)
                with self.assertRaises(DB['Failure']): DB['operation_read'](path)
                path.write_text('{"phase":"STARTED","phase":"SUCCEEDED"}')
                with self.assertRaises(DB['Failure']): DB['operation_read'](path)
                self.assertEqual({}, DB['operation_read'](path.with_name('missing.json')))
                values = request()
                receipt_path = DB['operation_path'](path.parent, values['operationId'])
                receipt = {'formatVersion':1, 'operationId':values['operationId'], 'taskId':values['taskId'],
                           'serverId':values['serverId'], 'action':'start', 'targetDigest':'a'*64,
                           'requestDigest':'b'*64, 'phase':'SUCCEEDED', 'effectBoundary':'NONE', 'result':{}}
                receipt_path.write_text(json.dumps(receipt))
                with patch.dict(DB, {'existing_state_directory': lambda: path.parent}):
                    with self.assertRaises(DB['Failure']): DB['operation_status'](values)


def peer(directory, action):
    """Run the actual embedded main with a bounded local fixture target. / 以有界本机夹具运行实际嵌入 main。"""
    original_fdopen = DB['os'].fdopen
    payload = sys.stdin.buffer.read(3 * 1024 * 1024 + 1)
    def fdopen(fd, *args, **kwargs):
        if fd == 3: return io.BytesIO(payload)
        return original_fdopen(fd, *args, **kwargs)
    path = pathlib.Path(directory)
    path.mkdir(exist_ok=True)
    def dispatch(verb, values):
        if verb != 'start': raise DB['Failure']('STATE_CHANGED')
        if action == 'start-failed': raise DB['Failure']('MANUAL_RESTORE_REQUIRED')
        return {'instance.id': values['instanceId']}
    with patch.dict(DB, {'state_directory': lambda: path,
                         'existing_state_directory': lambda: path,
                         'select': lambda values: {}, 'dispatch': dispatch,
                         'read_state': read_windows_fixture,
                         'operation_read': read_windows_fixture}), \
            patch.object(DB['os'], 'fdopen', side_effect=fdopen), \
            patch.object(sys, 'argv', ['native-db', 'start' if action == 'start-failed' else action]):
        DB['main']()


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == 'peer':
        peer(sys.argv[2], sys.argv[3])
    else:
        unittest.main()
