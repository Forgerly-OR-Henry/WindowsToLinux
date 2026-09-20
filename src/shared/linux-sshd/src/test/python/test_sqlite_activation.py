"""Run production commit/recovery shell with SQLite Backup API and temporary files.

Account/permission commands are stubbed on Windows; no service or container is started.
The sqlite3 command shim uses the real Python SQLite engine, not fabricated database bytes.
"""
import os
from contextlib import closing
import pathlib
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd'
FRAGMENTS = ROOT / 'execution/protocol/helper/fragments/database'
CODE = (FRAGMENTS / '64-database-client.sh').read_text(encoding='utf-8') + '\n' + (FRAGMENTS / '65-database-backup.sh').read_text(encoding='utf-8') + '\n' + (FRAGMENTS / '66-database-activation.sh').read_text(encoding='utf-8')
STORAGE = (ROOT / 'execution/protocol/helper/fragments/input/17-managed-content.sh').read_text(encoding='utf-8')
CLI = r'''
import sqlite3, sys, re, pathlib
def path(value):
    root=pathlib.Path(__file__).resolve().parent
    prefix='/tmp/'+root.name
    if value.startswith(prefix+'/'): return str(root/value[len(prefix)+1:])
    if sys.platform == 'win32' and re.match(r'^/[a-zA-Z]/',value):
        return value[1]+':'+value[2:]
    return value
args=[value for value in sys.argv[1:] if value not in ('--','-readonly')]
source=sqlite3.connect(path(args[0]))
try:
    if len(args) == 1:
        script='\n'.join(line for line in sys.stdin.read().splitlines() if line != '.bail on')
        source.executescript(script)
    elif args[1].startswith('.backup '):
        target=sqlite3.connect(path(args[1][8:].strip("'")))
        try: source.backup(target)
        finally: target.close()
    elif ';' in args[1].strip().rstrip(';'):
        source.executescript(args[1])
    else:
        for row in source.execute(args[1]): print('|'.join(map(str,row)))
finally: source.close()
'''
SETUP = r'''
set -euo pipefail
root="$(pwd)"
reject() { echo "REJECT=$1" >&2; exit 64; }
assert_storage_parent() { [ ! -L "$1" ]; }
assert_root_owned_directory() { [ -d "$1" ] && [ ! -L "$1" ]; }
assert_root_owned_regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
record_storage_binding() { :; }
prepare_service_identity() { :; }
service_identity_name() { printf 'fixture'; }
chown() { :; }
chmod() { :; }
sync() { :; }
install() {
  local -a args=()
  while [ "$#" -gt 0 ]; do
    case "$1" in -o|-g|-m) shift 2 ;; -d|--) shift ;; *) args+=("$1"); shift ;; esac
  done
  mkdir -p -- "${args[@]}"
}
sqlite3() { "$WTL_SQLITE_PYTHON" "$root/sqlite_cli.py" "$@"; }
systemd-run() { "$WTL_SQLITE_PYTHON" "$root/sqlite_cli.py" "${@: -1}"; }
database_discard_candidate() { rm -f -- "$root/input.db"; }
database_activation_credential_app=demo
database_activation_app=demo
database_activation_candidate=demo-0123456789abcdef
database_activation_root="$root/candidate"
database_sqlite_binding=main
database_sqlite_location_type=DEFAULT
database_sqlite_location_path=-
database_sqlite_file=app.db
database_sqlite_directory="$root/live"
database_sqlite_target="$root/live/app.db"
database_activation_sqlite_candidate="$root/input.db"
database_activation_sqlite_journal="$root/live/.restore-test"
database_activation_state="$root/state"
'''


class SqliteActivationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        (self.root / 'live').mkdir()
        (self.root / 'candidate').mkdir()
        (self.root / 'sqlite_cli.py').write_text(CLI, encoding='utf-8')
        self.bash = shutil.which('bash') or 'E:/Program/Git/bin/bash.exe'

    def shell(self, action):
        env = dict(os.environ, WTL_SQLITE_PYTHON=sys.executable)
        return subprocess.run([self.bash, '-s'], cwd=self.root, env=env,
                              input=CODE+'\n'+STORAGE+'\n'+SETUP+'\n'+action, text=True,
                              capture_output=True, timeout=30)

    def databases(self):
        original = sqlite3.connect(self.root / 'original.db')
        original.execute('pragma journal_mode=wal')
        original.execute('pragma wal_autocheckpoint=0')
        original.execute('create table records(value text)')
        original.execute("insert into records values('original-wal-data')")
        original.commit()
        for suffix in ('', '-wal', '-shm'):
            shutil.copyfile(str(self.root/'original.db')+suffix, str(self.root/'live/app.db')+suffix)
        original.close()
        with closing(sqlite3.connect(self.root / 'input.db')) as candidate:
            candidate.execute('create table records(value text)')
            candidate.execute("insert into records values('candidate-data')")
            candidate.commit()

    def test_commit_and_recovery_preserve_database_with_uncheckpointed_wal(self):
        self.databases()
        result = self.shell('database_commit_sqlite')
        self.assertEqual(0,result.returncode,result.stderr)
        with closing(sqlite3.connect(self.root/'live/app.db')) as active:
            self.assertEqual('candidate-data',active.execute('select value from records').fetchone()[0])
        result = self.shell('mapfile -t activation < "$database_activation_state"; database_recover_sqlite "${activation[1]}"')
        self.assertEqual(0,result.returncode,result.stderr)
        with closing(sqlite3.connect(self.root/'live/app.db')) as active:
            self.assertEqual('original-wal-data',active.execute('select value from records').fetchone()[0])
        self.assertFalse((self.root/'input.db').exists())

    def test_every_file_move_interruption_can_restore_the_original_wal_set(self):
        for boundary in range(1,5):
            with self.subTest(boundary=boundary):
                # Each subcase owns an independent temporary tree.
                case=SqliteActivationTest(); case.setUp()
                try:
                    case.databases()
                    action = f'''
moves=0
mv() {{ moves=$((moves+1)); [ "$moves" != {boundary} ] || return 42; command mv "$@"; }}
database_commit_sqlite
'''
                    result=case.shell(action)
                    self.assertEqual(42,result.returncode,result.stderr)
                    result=case.shell('mapfile -t activation < "$database_activation_state"; database_recover_sqlite "${activation[1]}"')
                    self.assertEqual(0,result.returncode,result.stderr)
                    with closing(sqlite3.connect(case.root/'live/app.db')) as active:
                        self.assertEqual('original-wal-data',active.execute('select value from records').fetchone()[0])
                finally: case.doCleanups()

    def test_corrupt_candidate_never_replaces_formal_database(self):
        self.databases()
        (self.root/'input.db').write_bytes(b'not a database')
        result=self.shell('database_commit_sqlite')
        self.assertNotEqual(0,result.returncode)
        self.assertFalse((self.root/'state').exists())
        with closing(sqlite3.connect(self.root/'live/app.db')) as active:
            self.assertEqual('original-wal-data',active.execute('select value from records').fetchone()[0])

    def test_seed_and_sql_initialize_once_and_redeployment_preserves_business_data(self):
        with closing(sqlite3.connect(self.root/'seed.db')) as seed:
            seed.execute('create table records(value text)')
            seed.execute("insert into records values('seed-data')")
            seed.commit()
        (self.root/'init.sql').write_text("insert into records values('initialized');",encoding='utf-8')
        action='''seed_file=seed.db
initialization_files=init.sql
initialize_managed_sqlite "$root" "$root/live" "$root/live/app.db" fixture
'''
        for deployment in range(2):
            result=self.shell(action)
            self.assertEqual(0,result.returncode,result.stderr)
            with closing(sqlite3.connect(self.root/'live/app.db')) as active:
                expected=[('seed-data',),('initialized',)] + ([('business-data',)] if deployment else [])
                self.assertEqual(expected,active.execute('select value from records').fetchall())
                if not deployment:
                    active.execute("insert into records values('business-data')")
                    active.commit()
        self.assertEqual([],list((self.root/'live').glob('.initialize.*')))

    def test_initialization_failure_does_not_publish_partial_database(self):
        (self.root/'init.sql').write_text('create table partial(value text); INVALID SQL;',encoding='utf-8')
        result=self.shell('''seed_file=-
initialization_files=init.sql
initialize_managed_sqlite "$root" "$root/live" "$root/live/app.db" fixture
''')
        self.assertEqual(64,result.returncode,result.stderr)
        self.assertFalse((self.root/'live/app.db').exists())
        self.assertEqual([],list((self.root/'live').glob('.initialize.*')))


if __name__ == '__main__':
    unittest.main()
