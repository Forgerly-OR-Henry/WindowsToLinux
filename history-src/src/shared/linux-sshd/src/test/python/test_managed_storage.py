"""Execute storage boundary parsing from the production helper without root mutations."""

import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd'
FRAGMENT = (ROOT / 'execution/protocol/helper/fragments/input/17-managed-content.sh').read_text(encoding='utf-8')
BASH = 'E:/Program/Git/bin/bash.exe'


class ManagedStorageTest(unittest.TestCase):
    def run_helper(self, body):
        import shutil

        bash = shutil.which('bash') or BASH
        setup = '''set -euo pipefail
PATH=/usr/bin:/bin:$PATH
reject() { printf 'REJECT=%s\\n' "$1"; exit 64; }
require_app() { [[ "$1" =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]] || reject app; }
require_count() { [[ "$1" =~ ^[0-9]+$ ]] && [ "$1" -le 32 ] || reject count; }
require_digest() { [[ "$1" =~ ^[a-f0-9]{64}$ ]] || reject digest; }
app_root() { printf '/opt/windowstolinux/apps/%s' "$1"; }
configurations_root=/etc/opt/windowstolinux/apps
data_root=/var/opt/windowstolinux/apps
current_application=demo
runtime_identity_policy=SYSTEMD_STATIC
'''
        # Physical-parent tests are separate; this harness exercises exact path policy on Windows.
        return subprocess.run(
            [bash, '-s'],
            input=setup + FRAGMENT + '\nassert_storage_parent() { :; }\n' + body,
            text=True,
            capture_output=True,
            timeout=10,
        )

    def test_default_and_relative_paths_match_the_shared_contract(self):
        result = self.run_helper('''managed_storage_directory demo FILE uploads DEFAULT -; printf '\\n'
managed_storage_directory demo DATABASE main CUSTOM database/app.db; printf '\\n'
managed_storage_directory demo CONFIGURATION settings CUSTOM /opt/windowstolinux/apps/demo/settings.json
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            [
                '/var/opt/windowstolinux/apps/demo/files/uploads',
                '/opt/windowstolinux/apps/demo/persistent/databases/main',
                '/opt/windowstolinux/apps/demo/persistent/configuration/settings',
            ],
            result.stdout.splitlines(),
        )

    def test_rejects_traversal_cross_app_prefixes_reserved_paths_and_fake_defaults(self):
        for arguments in [
            'CUSTOM /opt/windowstolinux/apps/demo2/data',
            'CUSTOM /opt/windowstolinux/apps/demo',
            'CUSTOM /opt/windowstolinux/apps/demo/releases/data',
            'CUSTOM ../data',
            'CUSTOM /opt/windowstolinux/apps/demo/a/../data',
            'DEFAULT /tmp/data',
            'UNRESOLVED -',
        ]:
            with self.subTest(arguments=arguments):
                result = self.run_helper('managed_storage_directory demo FILE uploads ' + arguments)
                self.assertEqual(64, result.returncode, result.stdout + result.stderr)

    def test_native_cannot_use_container_access_path_but_container_can(self):
        call = 'parse_managed_data_bindings demo demo 1 main /app/data/app.db rw DATABASE DEFAULT - app.db - - -'
        self.assertEqual(64, self.run_helper(call).returncode)
        self.assertEqual(0, self.run_helper('runtime_identity_policy=CONTAINER_NON_ROOT\n' + call).returncode)

    def test_tampered_binding_fields_and_directory_overlaps_are_rejected(self):
        cases = [
            'parse_managed_data_bindings demo demo 1 files uploads rw FILE DEFAULT - - /etc/passwd - -',
            'parse_managed_data_bindings demo demo 1 main db/app.db rw DATABASE DEFAULT - app.db - ../schema.sql -',
            'parse_managed_data_bindings demo demo 2 one data rw FILE CUSTOM /opt/windowstolinux/apps/demo/state - - - - two other rw FILE CUSTOM /opt/windowstolinux/apps/demo/state/sub - - - -',
        ]
        for call in cases:
            with self.subTest(call=call):
                result = self.run_helper(call)
                self.assertEqual(64, result.returncode, result.stdout + result.stderr)

    def test_sqlite_alias_inside_file_directory_resolves_to_the_managed_parent(self):
        result = self.run_helper('''parse_managed_data_bindings demo backend 2 db data/files.db rw DATABASE DEFAULT - files.db - - - uploads data rw FILE DEFAULT - - - - -
managed_binding_parts "${managed_data_bindings[1]}"
managed_binding_access /opt/windowstolinux/apps/demo/current/source
''')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('/var/opt/windowstolinux/apps/demo/files/uploads/files.db', result.stdout)

    def test_nested_build_publishes_storage_relative_to_application_source(self):
        import tempfile

        release = (ROOT / 'execution/protocol/helper/fragments/release/10-typed-release.sh').read_text(encoding='utf-8')
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary).as_posix()
            result = self.run_helper(
                release
                + f'''
fixture="$(cygpath -u '{directory}' 2>/dev/null || printf '%s' '{directory}')"
mkdir -p "$fixture/candidate/mutable/source/cli/.venv/bin"
printf '#!/bin/sh\\n' > "$fixture/candidate/mutable/source/cli/.venv/bin/python"
chmod +x "$fixture/candidate/mutable/source/cli/.venv/bin/python"
printf metadata > "$fixture/candidate/mutable/source/cli/pyproject.toml"
parse_deployment_inputs() {{ runtime_identity_policy=SYSTEMD_STATIC; application_builddir=cli; application_workdir=; deployment_remaining_arguments=(); }}
parse_managed_data_bindings() {{ managed_data_remaining_arguments=(); }}
parse_toolchain_binding() {{ toolchain_remaining_arguments=(python 3.12 main PYTHON_STDLIB); toolchain_binding=; }}
candidate_root() {{ printf '%s/candidate' "$fixture"; }}
assert_candidate_for_deployer() {{ :; }}
assert_sealed_build() {{ :; }}
assert_root_owned_directory() {{ [ -d "$1" ]; }}
install() {{ mkdir -p "${{@: -1}}"; }}
copy_sealed_source() {{ cp -R -- "$1" "$2"; }}
prepare_managed_data_bindings() {{ [ "$1" = "$fixture/release/source" ] || exit 42; printf '%s' "$1"; }}
application_assert_inputs() {{ :; }}
seal_deployment_tree demo candidate "$fixture/release"
'''
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertTrue(result.stdout.endswith('/release/source'), result.stdout)

    def test_container_volume_declarations_cannot_disagree_with_host_bindings(self):
        container = (ROOT / 'execution/protocol/helper/fragments/release/50-container-release.sh').read_text(
            encoding='utf-8'
        )
        for access, mode, expected in [('/data', 'rw', 0), ('/other', 'rw', 64), ('/data', 'ro', 64)]:
            with self.subTest(access=access, mode=mode):
                result = self.run_helper(
                    container
                    + f'''
runtime_identity_policy=CONTAINER_NON_ROOT
application_endpoints=()
parse_managed_data_bindings demo demo 1 files {access} {mode} FILE DEFAULT - - - - -
parse_container_parameters docker 0 1 windowstolinux-files /data 0
'''
                )
                self.assertEqual(expected, result.returncode, result.stderr + result.stdout)

    def test_file_seed_failure_leaves_no_partially_initialized_target(self):
        import tempfile

        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary).as_posix()
            result = self.run_helper(f'''
fixture="$(cygpath -u '{directory}' 2>/dev/null || printf '%s' '{directory}')"
mkdir -p "$fixture/seed"
mktemp() {{ mkdir "$fixture/staging"; printf '%s' "$fixture/staging"; }}
chown() {{ :; }}
chmod() {{ :; }}
cp() {{ printf partial > "$fixture/staging/partial"; return 9; }}
initialize_managed_file_tree "$fixture/seed" "$fixture/data" fixture
''')
            self.assertEqual(64, result.returncode, result.stderr)
            self.assertFalse((pathlib.Path(temporary) / 'data').exists())
            self.assertFalse((pathlib.Path(temporary) / 'staging').exists())

    def test_image_working_directory_cannot_hide_overlapping_access_paths(self):
        container = (ROOT / 'execution/protocol/helper/fragments/release/18-container-storage.sh').read_text(
            encoding='utf-8'
        )
        for path, expected in [('/app/data/child', 64), ('/app/data', 64), ('/app/other', 0)]:
            with self.subTest(path=path):
                result = self.run_helper(
                    container
                    + f'''
runtime_identity_policy=CONTAINER_NON_ROOT
container_engine=image_fixture
image_fixture() {{ printf /app; }}
parse_managed_data_bindings demo demo 2 one data rw FILE DEFAULT - - - - - two {path} rw FILE DEFAULT - - - - -
container_storage_mounts image
'''
                )
                self.assertEqual(expected, result.returncode, result.stderr + result.stdout)

    def test_sqlite_restore_target_inspection_accepts_absent_database_without_creating_one(self):
        database = (ROOT / 'execution/protocol/helper/fragments/database/65-database-backup.sh').read_text(
            encoding='utf-8'
        )
        database = (
            (ROOT / 'execution/protocol/helper/fragments/database/64-database-client.sh').read_text(encoding='utf-8')
            + '\n'
            + database
        )
        result = self.run_helper(
            database
            + '''
app_root() { printf /no-existing-application; }
data_root=/no-existing-data
sqlite3() { [ "$1" = --version ] || exit 42; printf '3.46.0 fixture\\n'; }
database_inspect demo sqlite main DEFAULT - application.db
'''
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('TOOL_AVAILABLE=1', result.stdout)
        self.assertIn('ONLINE_BACKUP_AVAILABLE=1', result.stdout)

    def test_failed_sqlite_initialization_can_retry_image_seed_without_reseeding_existing_database(self):
        import tempfile

        container = (ROOT / 'execution/protocol/helper/fragments/release/18-container-storage.sh').read_text(
            encoding='utf-8'
        )
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary).as_posix()
            result = self.run_helper(
                container
                + f'''
fixture="$(cygpath -u '{directory}' 2>/dev/null || printf '%s' '{directory}')"
seed_file=-; storage_kind=DATABASE; database_file=app.db
container_storage_needs_image_seed "$fixture" || exit 42
printf existing > "$fixture/app.db"
if container_storage_needs_image_seed "$fixture"; then exit 43; fi
storage_kind=FILE
if container_storage_needs_image_seed "$fixture"; then exit 44; fi
'''
            )
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__':
    unittest.main()
