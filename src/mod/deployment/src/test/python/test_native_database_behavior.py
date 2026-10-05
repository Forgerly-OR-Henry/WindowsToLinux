"""Execute current native DB decisions with inert package/client boundaries. / 执行当前原生数据库决策，系统命令受控。"""

import contextlib
import pathlib
import tempfile
import types
import unittest
import uuid
from unittest.mock import patch

from test_native_database_operation import DB, read_windows_fixture


class NativeDatabaseBehaviorTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = pathlib.Path(temporary.name)
        # This seam represents POSIX root ownership on Windows; actual journal writes remain real.
        fixture = patch.dict(DB, {'state_directory': lambda: self.directory,
                                 'read_state': read_windows_fixture})
        fixture.start()
        self.addCleanup(fixture.stop)

    def item(self, engine):
        return {'engine': engine, 'id': engine.lower() + '.service', 'version': '16.1',
                'port': '6379' if engine == 'REDIS' else '5432' if engine == 'POSTGRESQL' else '3306',
                'service': engine.lower() + '.service', 'dataDirectory': str(self.directory / 'data'),
                'fingerprint': 'a' * 64, 'running': 'true', '_package': engine.lower() + '-server'}

    def test_unknown_version_and_changed_instance_never_become_an_installable_absence(self):
        for engine in ('POSTGRESQL', 'MYSQL', 'MARIADB', 'REDIS'):
            with self.subTest(engine=engine):
                package = DB['PACKAGES']['dnf'][engine]
                with patch.dict(DB, {'system': lambda: 'dnf', 'packages': lambda manager: {package: 'custom'},
                                     'run': lambda *args, **kwargs: types.SimpleNamespace(stdout='', returncode=0),
                                     'unit_properties': lambda unit: {'Id': unit, 'LoadState': 'loaded'},
                                     'package_candidate': lambda *args: None}), \
                        patch('shutil.which', return_value=None):
                    inventory = DB['inspect'](engine)
                self.assertIn('version_unknown', inventory['conflicts'])
                self.assertIn('instance_unknown', inventory['conflicts'])
                self.assertEqual([], inventory['instances'])
                item = self.item(engine)
                with patch.dict(DB, {'inspect': lambda *args: {'instances': [item], 'conflicts': []}}):
                    with self.assertRaises(DB['Failure']) as changed:
                        DB['select']({'engine': engine, 'instanceId': item['id'], 'fingerprint': 'b' * 64})
                self.assertEqual('STATE_CHANGED', changed.exception.code)
        for engine, foreign, conflict in (('MYSQL', 'mariadb-server', 'mariadb_installed'),
                                          ('MARIADB', 'mysql-server', 'mysql_installed')):
            with self.subTest(engine=engine, foreign=foreign), \
                    patch.dict(DB, {'system': lambda: 'dnf', 'packages': lambda manager: {foreign: '16.1'},
                                    'package_candidate': lambda *args: None}), \
                    patch('shutil.which', return_value=None), patch.object(pathlib.Path, 'exists', return_value=False):
                self.assertIn(conflict, DB['inspect'](engine)['conflicts'])

    def test_each_engine_uses_its_actual_client_without_password_in_argv(self):
        for engine, client in (('POSTGRESQL', 'psql'), ('MYSQL', 'mysql'),
                               ('MARIADB', 'mariadb'), ('REDIS', 'redis-cli')):
            with self.subTest(engine=engine):
                calls = []
                defaults = []

                def run(args, **kwargs):
                    calls.append((args, kwargs))
                    self.assertNotIn('fixture-secret', '\0'.join(args))
                    if client in ('mysql', 'mariadb'):
                        option = next(value for value in args if value.startswith('--defaults-extra-file='))
                        path = pathlib.Path(option.split('=', 1)[1])
                        self.assertIn('fixture-secret', path.read_text())
                        defaults.append(path)
                    return types.SimpleNamespace(stdout='1\n', returncode=0)

                with patch.dict(DB, {'run': run}):
                    if engine == 'REDIS':
                        self.assertEqual('1', DB['redis_query'](self.item(engine), ['PING'], 'fixture-secret', 'demo'))
                    else:
                        self.assertEqual('1', DB['sql_query'](self.item(engine), 'SELECT 1;', 'fixture-secret',
                                                             'demo', 'demo', False))
                args, options = calls[0]
                self.assertEqual(client, args[0])
                if engine == 'POSTGRESQL':
                    self.assertEqual('fixture-secret', options['env']['PGPASSWORD'])
                    self.assertIn('ON_ERROR_STOP=1', args)
                elif engine == 'REDIS':
                    self.assertEqual('fixture-secret', options['env']['REDISCLI_AUTH'])
                    self.assertEqual('PING', args[-1])
                else:
                    self.assertIn('--local-infile=0', args)
                    self.assertIn('--database=demo', args)
                    self.assertTrue(all(not path.exists() for path in defaults))

    def test_exact_install_and_replacement_journal_precede_any_package_mutation(self):
        for engine in ('POSTGRESQL', 'MYSQL', 'MARIADB', 'REDIS'):
            for manager in ('apt', 'dnf'):
                for replacing in (False, True):
                    with self.subTest(engine=engine, manager=manager, replacing=replacing):
                        item = self.item(engine)
                        data = pathlib.Path(item['dataDirectory'])
                        data.mkdir(exist_ok=True)
                        (data / 'retained').write_bytes(b'original-database-data')
                        candidate = {'package': DB['PACKAGES'][manager][engine],
                                     'packageVersion': '17.2-1', 'engineVersion': '17.2'}
                        inventory = {'instances': [item] if replacing else [], 'candidate': candidate, 'conflicts': []}
                        values = dict(candidate, engine=engine, instanceId=item['id'], fingerprint=item['fingerprint'],
                                      replacementApproved='true', approvedServer='server prod/1',
                                      serverId='server prod/1', operation=str(uuid.uuid4()))
                        journal = DB['replacement_path'](engine)
                        if journal.exists():
                            journal.unlink()
                        calls = []

                        def run(args, **kwargs):
                            if replacing:
                                saved = read_windows_fixture(journal)
                                self.assertEqual('WAITING_FOR_RESTORE', saved['phase'])
                                self.assertEqual(item, saved['previous'])
                                self.assertEqual(candidate, saved['target'])
                                self.assertEqual(values['operation'], saved['operation'])
                            calls.append(args)
                            preview = 'Remv ' + item['_package'] + '\n' if args[:3] == ['apt-get', '-s', 'remove'] else ''
                            return types.SimpleNamespace(stdout=preview, returncode=0)

                        with patch.dict(DB, {'inspect': lambda *args: inventory, 'system': lambda: manager,
                                             'run': run, 'prevent_package_start': lambda *args: contextlib.nullcontext()}):
                            self.assertEqual(inventory, DB['install'](values))
                        installation = (['apt-get', '-y', '--no-remove', 'install', candidate['package'] + '=17.2-1']
                                        if manager == 'apt' else ['dnf', '-y', 'install', candidate['package'] + '-17.2-1'])
                        self.assertEqual(installation, calls[-1])
                        if replacing:
                            self.assertEqual(['systemctl', 'stop', item['service']], calls[0])
                            removal = (['apt-get', '-y', 'remove', item['_package']] if manager == 'apt'
                                       else ['dnf', '-y', '--noautoremove', 'remove', item['_package']])
                            self.assertIn(removal, calls)
                        else:
                            self.assertEqual([installation], calls)
                        self.assertFalse(any('purge' in command or item['dataDirectory'] in command for command in calls))
                        self.assertEqual(b'original-database-data', (data / 'retained').read_bytes())

    def test_replacement_target_or_approval_change_rejects_before_effect(self):
        item = self.item('POSTGRESQL')
        candidate = {'package': 'postgresql', 'packageVersion': '17.2', 'engineVersion': '17.2'}
        values = dict(candidate, engine='POSTGRESQL', instanceId=item['id'], fingerprint=item['fingerprint'],
                      replacementApproved='true', approvedServer='server prod/1', serverId='server prod/1',
                      operation=str(uuid.uuid4()))
        for changed in ({'replacementApproved': 'false'}, {'fingerprint': 'b' * 64},
                        {'approvedServer': 'another-server'}, {'packageVersion': '17.3'}):
            with self.subTest(changed=changed), patch.dict(DB, {
                    'inspect': lambda *args: {'instances': [item], 'candidate': candidate, 'conflicts': []},
                    'system': lambda: 'apt', 'run': lambda *args, **kwargs: self.fail('refused installation mutated OS')}):
                with self.assertRaises(DB['Failure']) as failure:
                    DB['install'](dict(values, **changed))
                self.assertEqual('STATE_CHANGED', failure.exception.code)
                self.assertFalse(DB['replacement_path']('POSTGRESQL').exists())

    def test_owned_initialization_runs_once_and_known_failure_requires_manual_restore(self):
        for engine in ('POSTGRESQL', 'MYSQL', 'MARIADB'):
            for failing in (False, True):
                with self.subTest(engine=engine, failing=failing):
                    item = self.item(engine)
                    values = dict(engine=engine, instanceId=item['id'], fingerprint=item['fingerprint'],
                                  applicationId='demo', database='demo', username='demo', ownershipToken='b' * 64,
                                  sourceSha256='c' * 64, sql='CREATE TABLE sample(id INT);', existingApproved='false')
                    journal = DB['target_state_path'](item, 'demo', 'demo')
                    DB['write_state'](journal, {'phase': 'EMPTY', 'ownershipToken': values['ownershipToken']})
                    executed = []

                    def query(selected, sql, *args):
                        if 'rolsuper' in sql:
                            return 'f'
                        if 'SHOW GRANTS' in sql:
                            return 'GRANT SELECT ON `demo`.* TO demo'
                        if 'count(*)' in sql:
                            return '0'
                        if 'CREATE TABLE' in sql:
                            self.assertEqual('STARTED', read_windows_fixture(journal)['phase'])
                            executed.append(sql)
                            if failing:
                                raise DB['Failure']('INITIALIZATION_FAILED')
                        return '1'

                    with patch.dict(DB, {'select': lambda *args: item, 'sql_query': query}):
                        if failing:
                            with self.assertRaises(DB['Failure']):
                                DB['initialize'](values)
                            with self.assertRaises(DB['Failure']) as retry:
                                DB['initialize'](values)
                            self.assertEqual('MANUAL_RESTORE_REQUIRED', retry.exception.code)
                            self.assertEqual('FAILED', read_windows_fixture(journal)['phase'])
                        else:
                            self.assertEqual('COMPLETE', DB['initialize'](values)['initialization'])
                            self.assertEqual('COMPLETE', DB['initialize'](values)['initialization'])
                    self.assertEqual(1, len(executed))
                    if engine == 'POSTGRESQL':
                        self.assertTrue(executed[0].startswith('BEGIN;\n'))

    def test_redis_refuses_sql_before_client_or_journal_effect(self):
        item = self.item('REDIS')
        values = dict(engine='REDIS', instanceId=item['id'], fingerprint=item['fingerprint'], applicationId='demo',
                      database='demo', username='demo', sourceSha256='c' * 64, sql='CREATE TABLE sample(id INT);')
        with patch.dict(DB, {'select': lambda *args: item,
                             'sql_query': lambda *args: self.fail('Redis dispatched SQL'),
                             'redis_query': lambda *args: self.fail('Redis performed a write'),
                             'run': lambda *args, **kwargs: self.fail('Redis started a client')}):
            with self.assertRaises(DB['Failure']) as failure:
                DB['initialize'](values)
        self.assertEqual('INITIALIZATION_FAILED', failure.exception.code)
        self.assertEqual([], list(self.directory.iterdir()))


if __name__ == '__main__':
    unittest.main()
