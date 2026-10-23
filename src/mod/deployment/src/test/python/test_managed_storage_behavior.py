"""Execute current storage functions in owned temporary directories. / 在专有临时目录执行当前存储函数。"""

import os
import pathlib
import shlex
import subprocess
import sys
import tempfile
import unittest

LINUX = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper'
FRAGMENTS = LINUX


def invoke(directory, body, fragments=(), arguments=(), postcondition='', publication_adapters=False):
    """Keep all filesystem effects inside this exact fixture; OS ownership is an explicit seam."""
    directory = pathlib.Path(directory).resolve(strict=True)
    # Resolve every root used by seed staging, current and rollback before sending the script.
    for relative in ('apps/demo', 'apps/demo/releases', 'data', 'config', 'control', 'work', 'snapshots',
                     'quadlets', 'backups', 'posix-adapters'):
        target = (directory / relative).resolve()
        if not target.is_relative_to(directory):
            raise AssertionError('Fixture root escaped its TemporaryDirectory: ' + str(target))
    sources = '\n'.join((FRAGMENTS / part).read_text(encoding='utf-8')
                        for part in ('00-protocol-foundation.sh',) + tuple(fragments)) + '\n'
    python = shlex.quote(pathlib.Path(sys.executable).as_posix())
    if publication_adapters and os.name == 'nt':
        image_python = '/usr/bin/python3 -I - "$archive" "$app" "$candidate_id" "$image"'
        if sources.count(image_python) != 1:
            raise AssertionError('Expected exactly one current image-validation Python entry')
        sources = sources.replace(image_python, image_python.replace('/usr/bin/python3', 'managed_image_python'), 1)
        adapters = directory / 'posix-adapters'
        adapters.mkdir()
        shim = r'''#!/usr/bin/bash
set -euo pipefail
python3() { MSYS2_ARG_CONV_EXCL="*" PYTHON_ENTRY "$@"; }
original=("$@")
targets=()
command_name="${0##*/}"
if [ "$command_name" = chmod ]; then
    while [ "$#" -gt 0 ] && [[ "$1" = -* ]] && [ "$1" != -- ]; do shift; done
    [ "$#" -gt 0 ] || exit 98
    shift # The actual mode is recorded, but Windows cannot apply Linux mode metadata.
fi
while [ "$#" -gt 0 ]; do
    case "$1" in --|-R|-b|-k) shift ;;
        -*) printf 'Unsupported fixture metadata option: %s\n' "$1" >&2; exit 98 ;;
        *) targets+=("$1"); shift ;;
    esac
done
[ "${#targets[@]}" -gt 0 ] || exit 98
for target in "${targets[@]}"; do
    checked="$(realpath -m -- "$target")"
    checked="$(cygpath -w "$checked")"
    python3 -I -c 'import pathlib,sys; root=pathlib.Path(sys.argv[1]).resolve(strict=True); target=pathlib.Path(sys.argv[2]).resolve(); assert target.is_relative_to(root) and target!=root, (root,target)' "$MANAGED_FIXTURE_ROOT" "$checked"
done
metadata_log="$(cygpath -u "$MANAGED_METADATA_LOG")"
printf '%s\0' "$command_name" "${original[@]}" >>"$metadata_log"
printf '\n' >>"$metadata_log"
'''.replace('PYTHON_ENTRY', python)
        for name in ('setfacl', 'chmod'):
            path = adapters / name
            path.write_text(shim, encoding='utf-8', newline='\n')
            path.chmod(0o700)
    setup = r'''
set -euo pipefail
PATH=/usr/bin:/bin:$PATH
fixture="$PWD"
base_root="$fixture/control"
applications_root="$fixture/apps"
data_root="$fixture/data"
configurations_root="$fixture/config"
secrets_root="$fixture/config"
work_root="$fixture/work"
snapshots_root="$fixture/snapshots"
backups_root="$fixture/backups"
mkdir -p "$base_root" "$applications_root" "$work_root" "$snapshots_root"
current_application=demo
application_workdir=
application_builddir=
runtime_identity_policy=CONTAINER_NON_ROOT
managed_data_bindings=()
deployment_secret_identifiers=()
deployment_secret_names=()
deployment_secret_revisions=()
deployment_secret_digests=()
application_input_sources=()
application_input_targets=()
application_endpoints=()
check_path() {
    local checked
    # MSYS sysfile links must be resolved by the same filesystem consumer before native Python.
    checked="$(realpath -m -- "$1")"
    if command -v cygpath >/dev/null 2>&1; then checked="$(cygpath -w "$checked")"; fi
    python3 -I -c 'import pathlib,sys; root=pathlib.Path(sys.argv[1]).resolve(strict=True); target=pathlib.Path(sys.argv[2]).resolve(); assert target.is_relative_to(root) and target!=root, (root,target)' "$MANAGED_FIXTURE_ROOT" "$checked"
}
rm() {
    local value
    for value in "$@"; do case "$value" in -*) ;; *) check_path "$value" ;; esac; done
    command rm "$@"
}
# POSIX ownership/mount discovery are OS seams, never substituted business decisions.
chown() { :; }
findmnt() { :; }
assert_storage_parent() { check_path "$1"; }
assert_root_owned_regular() { check_path "$1"; [ -f "$1" ] && [ ! -L "$1" ] || reject fixture-regular; }
assert_root_owned_directory() { check_path "$1"; [ -d "$1" ] && [ ! -L "$1" ] || reject fixture-directory; }
prepare_service_identity() { :; }
service_identity_name() { printf fixture; }
install() {
    local -a original=("$@") forwarded=() directories=()
    local directory_mode=0
    while [ "$#" -gt 0 ]; do
        case "$1" in -o|-g) shift 2 ;;
        -d) directory_mode=1; forwarded+=("$1"); shift ;;
        *)
            if [ "$1" = /etc/containers/systemd ]; then forwarded+=("$fixture/quadlets"); else forwarded+=("$1"); fi
            shift ;;
        esac
    done
    if [ "$MANAGED_FIXTURE_WINDOWS" = 1 ] && [ "$directory_mode" = 1 ]; then
        printf '%s\0' install "${original[@]}" >>"$fixture/posix-metadata"
        printf '\n' >>"$fixture/posix-metadata"
        set -- "${forwarded[@]}"
        while [ "$#" -gt 0 ]; do
            case "$1" in -d|--) shift ;; -m) shift 2 ;;
                *) check_path "$1"; directories+=("$1"); shift ;; esac
        done
        # Windows cannot apply Linux directory modes; creation and every path remain real.
        command mkdir -p -- "${directories[@]}"
        return
    fi
    local last="${forwarded[${#forwarded[@]}-1]}"
    check_path "$last"
    command install "${forwarded[@]}"
}
mktemp() {
    local -a forwarded=()
    local value
    for value in "$@"; do
        case "$value" in /etc/containers/systemd/*) value="$fixture/quadlets/${value##*/}" ;; esac
        forwarded+=("$value")
    done
    command mktemp "${forwarded[@]}"
}
'''
    if publication_adapters and os.name == 'nt':
        setup += r'''
PATH="$fixture/posix-adapters:$PATH"
managed_image_python() {
    [ "$#" -eq 6 ] && [ "$1" = -I ] && [ "$2" = - ] || reject fixture-image-python-arguments
    check_path "$3"
    local archive="$(cygpath -w "$3")"
    python3 -I - "$archive" "$4" "$5" "$6"
}
'''
    setup = 'python3() { MSYS2_ARG_CONV_EXCL="*" ' + python + ' "$@"; }\n' + setup
    environment = dict(os.environ, MANAGED_FIXTURE_ROOT=str(directory), MSYS2_ARG_CONV_EXCL='*',
                       MANAGED_FIXTURE_WINDOWS='1' if os.name == 'nt' else '0',
                       MANAGED_METADATA_LOG=str(directory / 'posix-metadata'))
    msys = [option for option in environment.get('MSYS', '').split() if not option.startswith('winsymlinks:')]
    environment['MSYS'] = ' '.join(msys + ['winsymlinks:sysfile'])
    if postcondition:
        # A plain subshell retains errexit; the outer Bash checks identity even after reject exits.
        body = 'set +e\n(\nset -e\n' + body + '\n)\nfixture_status=$?\nset -e\n' + postcondition + '\nexit "$fixture_status"\n'
    return subprocess.run([os.environ.get('MANAGED_TEST_BASH', 'E:/Program/Git/bin/bash.exe'), '-s', '--', *arguments],
                          input=sources + setup + body, cwd=directory, env=environment,
                          text=True, encoding='utf-8', capture_output=True, timeout=20)


STORAGE = ('input/17-managed-content.sh', 'release/18-container-storage.sh')


class ManagedStorageBehaviorTest(unittest.TestCase):
    def test_file_seed_is_committed_once_and_existing_data_survives_later_source_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            result = invoke(root, r'''
mkdir -p "$fixture/source/seed"
printf original >"$fixture/source/seed/value"
parse_managed_data_bindings demo demo 1 files /data rw FILE DEFAULT - - seed - -
prepare_managed_data_bindings "$fixture/source"
target="$data_root/demo/files/files"
printf user-data >"$target/value"
printf changed-seed >"$fixture/source/seed/value"
prepare_managed_data_bindings "$fixture/source"
''', STORAGE)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(b'user-data', (root / 'data/demo/files/files/value').read_bytes())
            self.assertFalse(list(root.rglob('.initialize-files.*')))
            self.assertEqual('FILE:DEFAULT:-\n', (root / 'control/storage/demo/files').read_text())
            if os.name == 'nt':
                metadata = [[value.decode() for value in row.split(b'\0')[:-1]]
                            for row in (root / 'posix-metadata').read_bytes().splitlines()]
                storage_directory = next(row for row in metadata if row[-1].endswith('/control/storage/demo'))
                self.assertEqual(['install', '-d', '-o', 'root', '-g', 'root', '-m', '700', '--'],
                                 storage_directory[:-1])

    def test_failed_file_seed_removes_real_partial_staging_without_creating_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            result = invoke(root, r'''
mkdir -p "$fixture/source/seed"
printf original >"$fixture/source/seed/value"
cp() { printf partial >"${@: -1}/partial"; return 9; }
parse_managed_data_bindings demo demo 1 files /data rw FILE DEFAULT - - seed - -
prepare_managed_data_bindings "$fixture/source"
''', STORAGE)
            self.assertEqual(64, result.returncode, result.stdout + result.stderr)
            self.assertIn('managed-storage-seed-copy', result.stderr)
            self.assertFalse((root / 'data/demo/files/files').exists())
            self.assertFalse(list(root.rglob('.initialize-files.*')))

    def test_sqlite_first_initialization_and_failure_cleanup_use_real_staging(self):
        for failing in (False, True):
            with self.subTest(failing=failing), tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                result = invoke(root, r'''
mkdir -p "$fixture/source" "$fixture/database"
printf 'CREATE TABLE sample(id INT);' >"$fixture/source/schema.sql"
seed_file=-
initialization_files=schema.sql
sqlite3() {
    case "$*" in *integrity_check*) printf 'ok\n' ;; *) printf 'checkpoint\n' >>"$fixture/sqlite-calls" ;; esac
}
systemd-run() {
    local target="${@: -1}"
    printf 'initialize\n' >>"$fixture/sqlite-calls"
    printf initialized >"$target"
    [ "$failure" = false ]
}
failure="$1"
initialize_managed_sqlite "$fixture/source" "$fixture/database" "$fixture/database/app.db" fixture
printf user-data >"$fixture/database/app.db"
initialize_managed_sqlite "$fixture/source" "$fixture/database" "$fixture/database/app.db" fixture
''', STORAGE, ('true' if failing else 'false',))
                self.assertEqual(64 if failing else 0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(1, (root / 'sqlite-calls').read_text().splitlines().count('initialize'))
                self.assertFalse(list(root.rglob('.initialize.*')))
                if failing:
                    self.assertIn('database-initialization-failed', result.stderr)
                    self.assertFalse((root / 'database/app.db').exists())
                else:
                    self.assertEqual(b'user-data', (root / 'database/app.db').read_bytes())

    def test_configuration_binding_is_read_only_and_existing_revision_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            result = invoke(root, r'''
mkdir -p "$fixture/source"
printf approved >"$fixture/source/settings"
digest="$(sha256sum "$fixture/source/settings" | awk '{print $1}')"
parse_managed_data_bindings demo demo 1 settings /settings ro CONFIGURATION DEFAULT - - settings - "$digest"
prepare_managed_data_bindings "$fixture/source"
printf changed >"$fixture/source/settings"
prepare_managed_data_bindings "$fixture/source"
''', STORAGE)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            values = list((root / 'config').rglob('value'))
            self.assertEqual(1, len(values))
            self.assertEqual(b'approved', values[0].read_bytes())
        with tempfile.TemporaryDirectory() as temporary:
            result = invoke(temporary, 'parse_managed_data_bindings demo demo 1 settings /settings rw CONFIGURATION DEFAULT - - settings - ' + 'a' * 64, STORAGE)
            self.assertEqual(64, result.returncode, result.stdout + result.stderr)
            self.assertIn('managed-configuration-input', result.stderr)

    def test_actual_container_inspection_rejects_writable_or_missing_declared_mounts(self):
        for engine in ('docker', 'podman'):
            for scenario, expected in (('exact', 0), ('root-writable', 64), ('configuration-writable', 64),
                                       ('extra', 64), ('input-missing', 64), ('binding-missing', 64)):
                with self.subTest(engine=engine, scenario=scenario), tempfile.TemporaryDirectory() as temporary:
                    result = invoke(temporary, r'''
container_engine="$1"
scenario="$2"
parse_managed_data_bindings demo demo 2 files /data rw FILE DEFAULT - - - - - settings /settings ro CONFIGURATION DEFAULT - - settings - aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
application_input_sources=("$fixture/input")
application_input_targets=(/input)
deployment_secret_names=(TEST_SECRET)
engine_fixture() {
    case "$*" in
        *ReadonlyRootfs*) if [ "$scenario" = root-writable ]; then printf false; else printf true; fi ;;
        *'.Type .Source .Destination .RW'*)
            [ "$scenario" = binding-missing ] || printf 'bind:%s:/data:true\n' "$data_root/demo/files/files"
            local readonly=false
            [ "$scenario" != configuration-writable ] || readonly=true
            printf 'bind:%s:/settings:%s\n' "$configurations_root/demo/files/settings/revisions/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/value" "$readonly"
            [ "$scenario" = input-missing ] || printf 'bind:%s:/input:false\n' "$fixture/input"
            printf 'bind:%s:/run/secrets/TEST_SECRET:false\n' "$configurations_root/demo/.container-credentials/release/TEST_SECRET"
            if [ "$scenario" = extra ]; then printf 'bind:/unapproved:/extra:true\n'; fi
            ;;
        *'.Source .Destination .RW'*)
            [ "$scenario" = binding-missing ] || printf '%s:/data:true\n' "$data_root/demo/files/files"
            printf '%s:/settings:false\n' "$configurations_root/demo/files/settings/revisions/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/value"
            ;;
        *) reject unexpected-inspection ;;
    esac
}
docker() { engine_fixture "$@"; }
podman() { engine_fixture "$@"; }
assert_container_bind_mounts fixture:release controlled-container
''', STORAGE, (engine, scenario))
                    self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
                    if expected:
                        self.assertIn('MANAGED_HELPER_REJECT=', result.stderr)


if __name__ == '__main__':
    unittest.main()
