"""Offline behavior checks for the bundled native DB helper; never contacts a server. / 对内置原生数据库 helper 进行离线行为检查，不连接服务器。"""
import json
import os
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

if sys.platform == 'win32':
    sys.modules.setdefault('fcntl', types.SimpleNamespace(LOCK_EX=1, LOCK_NB=2, flock=lambda *args: None))

RESOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd/execution/protocol/helper/fragments/database'
CODE = ''.join((RESOURCE / name).read_text(encoding='utf-8') for name in ('10-native-instances.sh', '20-native-targets.sh')).split("<<'WTL_NATIVE_DB_PY'\n", 1)[1].split('\nWTL_NATIVE_DB_PY', 1)[0]
DB = {'__name__': 'native_database_offline_test'}
exec(compile(CODE, str(RESOURCE), 'exec'), DB)


class NativeDatabaseTest(unittest.TestCase):
    def test_unknown_installed_version_is_a_conflict_not_an_absent_database(self):
        with patch.dict(DB, {'system': lambda: 'dnf', 'packages': lambda manager: {'postgresql-server': 'custom'},
                             'run': lambda *args, **kwargs: types.SimpleNamespace(stdout='', returncode=0),
                             'unit_properties': lambda unit: {'Id': unit, 'LoadState': 'loaded'},
                             'package_candidate': lambda *args: None}), patch('shutil.which', return_value='/usr/bin/postgres'):
            inventory = DB['inspect']('POSTGRESQL')
        self.assertIn('version_unknown', inventory['conflicts'])
        self.assertIn('instance_unknown', inventory['conflicts'])
        self.assertEqual([], inventory['instances'])

    def test_unparseable_repository_candidate_is_not_used_for_installation(self):
        with patch.dict(DB, {'run': lambda *args, **kwargs: types.SimpleNamespace(stdout='  Candidate: custom\n', returncode=0)}):
            self.assertIsNone(DB['package_candidate']('apt', 'POSTGRESQL'))

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = pathlib.Path(self.temporary.name)
        self.item = {'engine': 'POSTGRESQL', 'id': '16/main', 'version': '16.1', 'port': '5432',
                     'service': 'postgresql@16-main.service', 'dataDirectory': '/var/lib/postgresql/16/main',
                     'fingerprint': 'a' * 64, 'running': 'true', '_package': 'postgresql-16'}
        self.request = {'engine': 'POSTGRESQL', 'instanceId': '16/main', 'fingerprint': 'a' * 64,
                        'applicationId': 'demo', 'database': 'demo', 'username': 'demo',
                        'applicationPassword': 'test-only-password', 'ownershipToken': 'b' * 64,
                        'sourceSha256': 'c' * 64, 'sql': 'CREATE TABLE sample (id integer);', 'existingApproved': 'false'}
        seams = {'state_directory': lambda: self.directory}
        if sys.platform == 'win32':
            # Windows cannot represent the helper's POSIX root/0600 ownership contract. / Windows 无法表达 helper 的 POSIX root 属主与 0600 权限契约。
            # These tests exercise state transitions; production ownership checks remain unchanged. / 这些测试覆盖状态转换，生产属主校验保持不变。
            seams['read_state'] = lambda path: json.loads(path.read_text()) if path.exists() else {}
        self.fixture = patch.dict(DB, seams)
        self.fixture.start(); self.addCleanup(self.fixture.stop)

    def journal(self, phase='EMPTY'):
        path = DB['target_state_path'](self.item, 'demo', 'demo')
        DB['write_state'](path, {'phase': phase, 'instanceId': self.item['id'], 'applicationId': 'demo',
                                'database': 'demo', 'username': 'demo', 'ownershipToken': 'b' * 64})
        return path

    def test_existing_database_cannot_install_without_exact_replacement_approval(self):
        candidate = {'package': 'postgresql', 'packageVersion': '17.1', 'engineVersion': '17.1'}
        mutations = []
        with patch.dict(DB, {'inspect': lambda *args: {'instances': [self.item], 'candidate': candidate, 'conflicts': []},
                             'select': lambda request: self.item, 'system': lambda: 'apt',
                             'run': lambda *args, **kwargs: mutations.append(args)}):
            with self.assertRaises(DB['Failure']) as failure:
                DB['install'](dict(self.request, **candidate))
        self.assertEqual('STATE_CHANGED', failure.exception.code)
        self.assertEqual([], mutations)

    def test_changed_instance_invalidates_selection(self):
        inventory = {'instances': [dict(self.item, fingerprint='d' * 64)], 'conflicts': []}
        with patch.dict(DB, {'inspect': lambda *args: inventory}):
            with self.assertRaises(DB['Failure']) as failure: DB['select'](self.request)
        self.assertEqual('STATE_CHANGED', failure.exception.code)

    def test_stopped_instance_is_started_without_installing_another_server(self):
        actions = []
        with patch.dict(DB, {'select': lambda request: dict(self.item, running='false'),
                             'system': lambda: 'apt', 'inspect': lambda *args: {'instances': [self.item]},
                             'run': lambda args, **kwargs: actions.append(args)}):
            result = DB['start'](self.request)
        self.assertEqual('true', result['running'])
        self.assertEqual([['systemctl', 'start', self.item['service']]], actions)

    def test_installed_replacement_does_not_mean_old_data_has_been_restored(self):
        DB['write_state'](DB['replacement_path']('POSTGRESQL'), {'phase': 'WAITING_FOR_RESTORE'})
        with patch.dict(DB, {'select': lambda request: self.item}):
            with self.assertRaises(DB['Failure']) as failure: DB['start'](self.request)
        self.assertEqual('MANUAL_RESTORE_REQUIRED', failure.exception.code)

    def test_owned_empty_database_initializes_once_and_replay_only_checks_connection(self):
        self.journal(); executed = []

        def query(item, sql, *args):
            if 'rolsuper' in sql: return 'f'
            if 'count(*)' in sql: return '0'
            executed.append(sql); return '1'

        with patch.dict(DB, {'select': lambda request: self.item, 'sql_query': query}):
            first = DB['initialize'](self.request)
            second = DB['initialize'](self.request)
        self.assertEqual('COMPLETE', first['initialization'])
        self.assertEqual('COMPLETE', second['initialization'])
        self.assertEqual(1, sum('CREATE TABLE' in sql for sql in executed))
        self.assertTrue(executed[0].startswith('BEGIN;'))

    def test_failed_initialization_is_not_reexecuted_on_retry(self):
        journal = self.journal(); executed = []

        def query(item, sql, *args):
            if 'rolsuper' in sql: return 'f'
            if 'count(*)' in sql: return '0'
            executed.append(sql); raise DB['Failure']('INITIALIZATION_FAILED')

        with patch.dict(DB, {'select': lambda request: self.item, 'sql_query': query}):
            with self.assertRaises(DB['Failure']): DB['initialize'](self.request)
            with self.assertRaises(DB['Failure']) as retry: DB['initialize'](self.request)
        self.assertEqual('FAILED', DB['read_state'](journal)['phase'])
        self.assertEqual('MANUAL_RESTORE_REQUIRED', retry.exception.code)
        self.assertEqual(1, len(executed))

    def test_existing_unowned_database_never_enters_new_database_initialization(self):
        with patch.dict(DB, {'select': lambda request: self.item}):
            with self.assertRaises(DB['Failure']) as failure: DB['initialize'](self.request)
        self.assertEqual('STATE_CHANGED', failure.exception.code)

    def test_even_an_empty_owned_database_is_rechecked_before_initialization(self):
        self.journal()
        with patch.dict(DB, {'select': lambda request: self.item,
                             'sql_query': lambda item, sql, *args: 'f' if 'rolsuper' in sql else '2'}):
            with self.assertRaises(DB['Failure']) as failure: DB['initialize'](self.request)
        self.assertEqual('STATE_CHANGED', failure.exception.code)

    def test_initializer_refuses_client_commands_and_privileged_sql(self):
        for sql in [r'\! touch /tmp/unsafe', 'CREATE DATABASE other;', "COPY x FROM PROGRAM 'id';",
                    'GRANT ALL ON *.* TO demo;', 'USE other; DELETE FROM secrets;',
                    "CREATE FUNCTION dangerous() RETURNS void AS 'anything';"]:
            with self.subTest(sql=sql), self.assertRaises(DB['Failure']): DB['restricted_sql'](sql)

    def test_runtime_launcher_preserves_literal_control_character_checks(self):
        compile(DB['RUNTIME_LAUNCHER'], 'runtime-db-env', 'exec')
        self.assertIn("'\\x00'", DB['RUNTIME_LAUNCHER'])

    def test_launcher_maps_password_and_password_file_without_putting_values_in_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = pathlib.Path(folder)
            password_file = directory / 'WINDOWSTOLINUX_SECRET_DB_123456ABCDEF_ENV_DB_PASSWORD_FILE_FILE'
            password_file.write_text('file-secret', encoding='utf-8')
            (directory / 'WINDOWSTOLINUX_SECRET_DB_123456ABCDEF_ENV_DB_PASSWORD_FILE').write_text('env-secret', encoding='utf-8')
            with patch.dict(os.environ, {'CREDENTIALS_DIRECTORY': folder}, clear=True), \
                    patch('sys.argv', ['runtime-db-env', '/usr/bin/java', '-jar', 'app.jar']), patch('os.execv') as execute:
                exec(DB['RUNTIME_LAUNCHER'], {})
                self.assertEqual('env-secret', os.environ['DB_PASSWORD'])
                self.assertEqual(str(password_file), os.environ['DB_PASSWORD_FILE'])
                execute.assert_called_once_with('/usr/bin/java', ['/usr/bin/java', '-jar', 'app.jar'])


if __name__ == '__main__': unittest.main()
