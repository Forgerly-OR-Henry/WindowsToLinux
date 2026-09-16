"""Run the embedded output controller against inert subprocesses without Linux privileges. / 使用无害子进程运行内置输出控制器，不需要 Linux 特权。"""
import io
import hashlib
import json
import os
import pathlib
import sys
import subprocess
import shutil
import tempfile
import tarfile
import textwrap
import unittest
from unittest import mock

SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd'
fragment = (SOURCE / 'execution/protocol/helper/fragments/workspace/26-build-output.sh').read_text()
MONITOR = fragment.split("<<'WTL_BUILD_OUTPUT'\n", 1)[1].split('\nWTL_BUILD_OUTPUT', 1)[0]


class BuildOutputBudgetTest(unittest.TestCase):
    def execute(self, root, payload, limit=8, on_wait=None):
        read_fd, write_fd = os.pipe()
        os.write(write_fd, payload)
        os.close(write_fd)
        process = mock.Mock(stdout=os.fdopen(read_fd, 'rb'))
        process.wait.return_value = 0
        if on_wait is not None:
            process.wait.side_effect = on_wait
        process.poll.return_value = 0
        captured = io.BytesIO()
        stdout = io.TextIOWrapper(captured, encoding='utf-8', write_through=True)
        try:
            with mock.patch('subprocess.Popen', return_value=process) as popen, \
                    mock.patch.object(os, 'O_NOFOLLOW', getattr(os, 'O_NOFOLLOW', 0), create=True), \
                    mock.patch.object(sys, 'argv', ['monitor', str(root), str(limit), 'fixed-systemd-run']), \
                    mock.patch.object(sys, 'stdout', stdout), self.assertRaises(SystemExit) as stopped:
                exec(compile(MONITOR, 'build-output-controller', 'exec'), {})
            stdout.flush()
            return stopped.exception.code, captured.getvalue(), popen.call_count
        finally:
            stdout.detach()
            process.stdout.close()

    def test_aggregate_budget_survives_retries_and_modified_project_log(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            (root / 'mutable').mkdir()
            status, output, calls = self.execute(root, b'1234')
            self.assertEqual((0, b'1234', 1), (status, output, calls))
            (root / 'mutable/project.log').write_text('')
            status, output, _ = self.execute(root, b'56789')
            self.assertEqual(43, status)
            self.assertTrue(output.startswith(b'5678'))
            self.assertIn(b'BUILD_LIMIT=output', output)
            self.assertEqual('9', (root / '.output-used').read_text())
            status, _, calls = self.execute(root, b'ignored')
            self.assertEqual((43, 0), (status, calls))

    def test_exact_budget_succeeds_but_one_more_byte_fails(self):
        for payload, expected in [(b'12345678', 0), (b'123456789', 43)]:
            with self.subTest(payload=payload), tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                (root / 'mutable').mkdir()
                status, output, _ = self.execute(root, payload)
                self.assertEqual(expected, status)
                self.assertLessEqual(len(output.replace(b'\r\n', b'\n').split(b'\nBUILD_LIMIT=')[0]), 8)

    def test_controller_does_not_hold_volume_writes_during_service_stop(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            (root / 'mutable').mkdir()
            opened = {}
            real_open, real_close = os.open, os.close
            def tracked_open(path, flags, *args):
                fd = real_open(path, flags, *args)
                opened[fd] = (pathlib.Path(path), flags)
                return fd
            def tracked_close(fd):
                opened.pop(fd, None)
                return real_close(fd)
            def service_stopping():
                self.assertFalse(any(path.is_relative_to(root / 'mutable')
                                     and flags & (os.O_WRONLY | os.O_RDWR)
                                     for path, flags in opened.values()),
                                 'service stop must be able to remount the volume read-only')
                return 0
            with mock.patch.object(os, 'open', side_effect=tracked_open), \
                    mock.patch.object(os, 'close', side_effect=tracked_close):
                self.assertEqual(0, self.execute(root, b'ok', on_wait=service_stopping)[0])


class SystemdManagerIsolationTest(unittest.TestCase):
    def test_runtime_uses_standard_selinux_entry_without_changing_reviewed_arguments(self):
        bash = os.environ.get('MANAGED_TEST_BASH') or shutil.which('bash')
        if not bash or not pathlib.Path(bash).is_file():
            self.skipTest('Bash is unavailable')
        foundation = (SOURCE / 'execution/protocol/helper/fragments/00-protocol-foundation.sh').read_text()
        identity = (SOURCE / 'runtime/systemd/helper/61-dynamic-identity.sh').read_text()
        for selinux, kind in [('0', 'javasource'), ('1', 'javasource'), ('1', 'node')]:
            with self.subTest(selinux=selinux, kind=kind):
                setup = r'''
[() {
  if test "$#" = 3 && test "$1" = -e && test "$2" = /sys/fs/selinux/enforce; then
    test "$TEST_SELINUX" = 1; return
  fi
  builtin [ "$@"
}
getent() { return 1; }
parse_deployment_inputs() { deployment_remaining_arguments=("$@"); }
parse_managed_data_bindings() { managed_data_remaining_arguments=("$@"); }
parse_toolchain_binding() { toolchain_remaining_arguments=("$@"); }
configuration_path() { printf /var/lib/windowstolinux/configurations/app/config; }
wrap_database_runtime() { :; }
require_relative_path() { :; }; require_java_main() { :; }; require_bound_version() { :; }
# Git Bash limits ERE repetition counts; these two inert arguments are fixed and already reviewed.
require_safe_argument() { test "$1" = -Xmx128m || test "$1" = checked-argument; }
deployment_secret_identifiers=(); deployment_configuration_digest=fixture
managed_data_application=app; managed_data_component=main; managed_data_bindings=()
runtime_identity_policy=SYSTEMD_DYNAMIC; toolchain_binding=''
toolchain_java=/usr/local/lib/windowstolinux/toolchains/java/bin/java
toolchain_path=/usr/local/lib/windowstolinux/toolchains/node/bin
if test "$TEST_KIND" = javasource; then
  render_deployment_unit app javasource .w2l/java/app.jar acceptance.Main 1 -Xmx128m 1 checked-argument
else render_deployment_unit app node 18 NPM; fi
'''
                result = subprocess.run([bash, '--noprofile', '--norc', '-s'],
                                        input=foundation + '\n' + identity + '\n' + setup,
                                        env=dict(os.environ, TEST_SELINUX=selinux, TEST_KIND=kind),
                                        text=True, capture_output=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                command = next(line for line in result.stdout.splitlines() if line.startswith('ExecStart='))
                if kind == 'javasource':
                    expected = '/usr/local/lib/windowstolinux/toolchains/java/bin/java -Xmx128m -cp /var/lib/windowstolinux/apps/app/current/app.jar acceptance.Main checked-argument'
                    self.assertEqual('ExecStart=' + ('/usr/bin/env ' if selinux == '1' else '') + expected, command)
                else:
                    self.assertEqual(1, command.count('/usr/bin/env'))
                    self.assertIn('npm --prefix /var/lib/windowstolinux/apps/app/current/source start', command)
                self.assertIn('DynamicUser=yes', result.stdout)
                self.assertIn('NoNewPrivileges=yes', result.stdout)

    def test_selinux_hides_manager_directory_and_preserves_other_platform_paths(self):
        bash = os.environ.get('MANAGED_TEST_BASH') or shutil.which('bash')
        if not bash or not pathlib.Path(bash).is_file():
            self.skipTest('Bash is unavailable')
        foundation = (SOURCE / 'execution/protocol/helper/fragments/00-protocol-foundation.sh').read_text()
        identity = (SOURCE / 'runtime/systemd/helper/61-dynamic-identity.sh').read_text()
        for selinux in ['0', '1']:
            with self.subTest(selinux=selinux):
                setup = r'''
[() {
  if test "$#" = 3 && test "$1" = -e && test "$2" = /sys/fs/selinux/enforce; then
    test "$TEST_SELINUX" = 1; return
  fi
  builtin [ "$@"
}
getent() { return 1; }
managed_data_application=app; managed_data_component=main; managed_data_bindings=()
render_dynamic_runtime_identity app
'''
                result = subprocess.run([bash, '--noprofile', '--norc', '-s'],
                                        input=foundation + '\n' + identity + '\n' + setup,
                                        env=dict(os.environ, TEST_SELINUX=selinux), text=True,
                                        capture_output=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                paths = next(line.split('=', 1)[1].split() for line in result.stdout.splitlines()
                             if line.startswith('InaccessiblePaths='))
                if selinux == '1':
                    self.assertIn('TemporaryFileSystem=/run/systemd:ro', result.stdout)
                    self.assertIn('BindReadOnlyPaths=-/run/systemd/dynamic-uid -/run/systemd/userdb', result.stdout)
                    self.assertNotIn('-/run/systemd', paths)
                    self.assertNotIn('-/run/systemd/notify', paths)
                else:
                    self.assertNotIn('TemporaryFileSystem=', result.stdout)
                    self.assertTrue({'-/run/systemd/private', '-/run/systemd/journal',
                                     '-/run/systemd/notify'}.issubset(paths))
                self.assertTrue({'-/run/dbus', '-/run/docker.sock', '-/run/podman', '-/run/user'}.issubset(paths))
                self.assertIn('DynamicUser=yes', result.stdout)
                self.assertIn('NoNewPrivileges=yes', result.stdout)


class ContainerNamespacePreflightTest(unittest.TestCase):
    def test_uses_only_the_loaded_engine_profile_and_keeps_mapping_failures_blocking(self):
        bash = shutil.which('bash') or 'E:/Program/Git/bin/bash.exe'
        if not pathlib.Path(bash).is_file():
            self.skipTest('Bash is unavailable')
        source = (SOURCE / 'execution/protocol/helper/fragments/workspace/21-workspace-volume.sh').read_text()
        cases = [('docker', 'rootlesskit (unconfined)', 'rootlesskit', False),
                 ('podman', 'podman (unconfined)', 'podman', False),
                 ('docker', 'podman (unconfined)', '', False),
                 ('podman', '', '', False),
                 ('podman', 'podman (unconfined)', 'podman', True)]
        for engine, profiles, expected, denied in cases:
            with self.subTest(engine=engine, profiles=profiles, denied=denied):
                setup = r'''
set -eu
reject() { printf 'REJECT=%s\n' "$1"; exit 41; }
command() { if [ "${1:-}" = -v ]; then return 0; fi; builtin command "$@"; }
stat() { printf 'cgroup2fs'; }
[() {
  if test "$#" = 3 && test "$1" = -r && test "$2" = /sys/kernel/security/apparmor/profiles; then
    test -n "$TEST_PROFILES"; return
  fi
  builtin [ "$@"
}
grep() {
  if test "${@: -1}" = /sys/kernel/security/apparmor/profiles; then
    printf '%s\n' "$TEST_PROFILES" | builtin command grep "$1" "$2"
  else return 0; fi
}
systemd-run() {
  case " $* " in
    *' /usr/bin/unshare '*)
      printf 'PROBE_ARGUMENT=%s\n' "$@"
      test "$TEST_DENIED" = 0 ;;
    *) return 0 ;;
  esac
}
preflight_build "$TEST_ENGINE"
'''
                environment = dict(os.environ, TEST_ENGINE=engine, TEST_PROFILES=profiles,
                                   TEST_DENIED='1' if denied else '0')
                result = subprocess.run([bash, '--noprofile', '--norc', '-s'], input=source + '\n' + setup,
                                        env=environment, text=True, capture_output=True, timeout=20)
                self.assertEqual(41 if denied else 0, result.returncode, result.stderr + result.stdout)
                self.assertIn('PROBE_ARGUMENT=--map-root-user', result.stdout)
                if expected:
                    self.assertIn('PROBE_ARGUMENT=--property=AppArmorProfile=' + expected, result.stdout)
                else:
                    self.assertNotIn('AppArmorProfile=', result.stdout)
                if denied:
                    self.assertIn('REJECT=container-user-namespace-unavailable', result.stdout)


class ContainerEngineReadinessTest(unittest.TestCase):
    def test_socket_existence_does_not_start_a_project_before_engine_readiness(self):
        bash = shutil.which('bash') or 'E:/Program/Git/bin/bash.exe'
        if not pathlib.Path(bash).is_file():
            self.skipTest('Bash is unavailable')
        entry = (SOURCE / 'execution/protocol/helper/fragments/workspace/24-build-entry.sh').read_text()
        body = entry.split('wait_engine_ready() {', 1)[1].split('\n}\nwait_engine_ready', 1)[0]
        function = 'wait_engine_ready() {' + body.replace('/usr/bin/timeout', 'engine_probe') + '\n}\n'
        for engine in ('docker', 'podman'):
            for ready_after, alive in [(3, True), (999, True), (999, False)]:
                with self.subTest(engine=engine, ready_after=ready_after, alive=alive), \
                        tempfile.TemporaryDirectory() as temporary:
                    root = pathlib.Path(temporary)
                    (root / 'engine.log').write_text('E' * 9000 + 'FINAL_ENGINE_ERROR')
                    script = r'''
set -eu
mutable="$(pwd)"; socket="$mutable/engine.sock"; daemon=42; attempts=0
engine="$TEST_ENGINE"
trap 'printf "\nATTEMPTS=%s\n" "$attempts"' EXIT
[() { if test "$#" = 3 && test "$1" = -S; then return 0; fi; builtin [ "$@"; }
kill() { test "$TEST_ALIVE" = 1; }
sleep() { :; }
engine_probe() {
  test "$1" = --kill-after=1s && test "$2" = 1s || exit 43
  test "$3" = "/usr/bin/$engine" || exit 44
  attempts=$((attempts + 1))
  test "$attempts" -ge "$TEST_READY_AFTER"
}
'''
                    script += function + '\nwait_engine_ready\nprintf "PROJECT_CALLED\\n"\n'
                    result = subprocess.run([bash, '--noprofile', '--norc', '-s'], cwd=root, input=script,
                                            env=dict(os.environ, TEST_ENGINE=engine, TEST_ALIVE='1' if alive else '0',
                                                     TEST_READY_AFTER=str(ready_after)),
                                            text=True, capture_output=True, timeout=20)
                    succeeded = ready_after == 3
                    self.assertEqual(0 if succeeded else 65, result.returncode, result.stderr + result.stdout)
                    self.assertEqual(succeeded, 'PROJECT_CALLED' in result.stdout)
                    self.assertIn('ATTEMPTS=' + str(3 if succeeded else 20 if alive else 0), result.stdout)
                    if not succeeded:
                        self.assertIn('BUILD_REJECT=container-engine-not-ready', result.stdout)
                        self.assertIn('FINAL_ENGINE_ERROR', result.stdout)
                        self.assertLess(len(result.stdout), 4200)


class ContainerBootstrapPathsTest(unittest.TestCase):
    def test_only_copied_links_in_a_child_namespace_are_replaced(self):
        bash = shutil.which('bash') or 'E:/Program/Git/bin/bash.exe'
        entry = (SOURCE / 'execution/protocol/helper/fragments/workspace/24-build-entry.sh').read_text()
        body = entry.split("--copy-up=/etc --copy-up=/run /bin/bash -ceu '\n", 1)[1].split(
            "\n      ' wtl-docker-child", 1)[0]
        for scenario in ('valid', 'parent', 'nonroot', 'host-run', 'real-directory'):
            with self.subTest(scenario=scenario):
                setup = r'''
set -eu
set -- 'user:[1]' --rootless --data-root=/candidate/engine
XDG_RUNTIME_DIR=/tmp/wtl-engine
id() { if test "$TEST_SCENARIO" = nonroot; then echo 997; else echo 0; fi; }
readlink() { if test "$TEST_SCENARIO" = parent; then echo 'user:[1]'; else echo 'user:[2]'; fi; }
stat() { if test "$TEST_SCENARIO" = host-run; then echo ext4; else echo tmpfs; fi; }
[() {
  if test "$#" = 3 && test "$1" = -L; then test "$TEST_SCENARIO" != real-directory; return; fi
  if test "$#" = 4 && test "$1" = '!' && test "$2" = -e; then return 1; fi
  builtin [ "$@"
}
rm() { printf 'REMOVED=%s\n' "$2"; }
mkdir() { printf 'DIRECTORIES=%s\n' "$*"; }
touch() { :; }
ln() { printf 'LINK=%s\n' "$*"; }
exec() { test "$1" = /usr/bin/dockerd; test "$PATH" = /usr/sbin:/usr/bin:/sbin:/bin; printf 'DAEMON=%s\n' "$*"; }
'''
                result = subprocess.run([bash, '--noprofile', '--norc', '-s'], input=setup + body,
                                        env=dict(os.environ, TEST_SCENARIO=scenario), text=True,
                                        capture_output=True, timeout=20)
                self.assertEqual(0 if scenario == 'valid' else 64, result.returncode, result.stderr + result.stdout)
                if scenario == 'valid':
                    self.assertIn('DAEMON=/usr/bin/dockerd --rootless --data-root=/candidate/engine', result.stdout)
                    self.assertIn('LINK=-s -- /tmp/wtl-engine/docker-run /run/docker', result.stdout)
                    self.assertEqual(3, result.stdout.count('REMOVED='))
                else:
                    self.assertEqual('', result.stdout, 'no path change before child/copy-up checks')


class ContainerExportNormalizationTest(unittest.TestCase):
    def test_legacy_aliases_are_removed_without_extracting_or_weakening_publication_validation(self):
        java = (pathlib.Path(__file__).resolve().parents[2] /
                'main/java/gold/debug/windowstolinux/shared/linux/sshd/build/workload/ContainerBuildRenderer.java').read_text()
        normalizer = textwrap.dedent(java.split("<<'WTL_PODMAN_EXPORT'\n", 1)[1].split(
            '\n                WTL_PODMAN_EXPORT', 1)[0])
        fragment = (SOURCE / 'execution/protocol/helper/fragments/release/12-container-image-input.sh').read_text()
        validator = fragment.split("<<'WTL_IMAGE_INPUT'\n", 1)[1].split('\nWTL_IMAGE_INPUT', 1)[0]
        for scenario in ('valid', 'escape', 'unknown-layer', 'hardlink', 'root'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as temporary:
                archive = pathlib.Path(temporary) / 'image.tar'
                config = json.dumps({'config': {'User': '0' if scenario == 'root' else '65532:65532',
                    'Labels': {'io.windowstolinux.application': 'demo',
                               'io.windowstolinux.candidate': 'demo-0123456789abcdef'}}}).encode()
                config_name = hashlib.sha256(config).hexdigest() + '.json'
                layer = 'a' * 64 + '.tar'
                manifest = json.dumps([{'Config': config_name, 'Layers': [layer],
                    'RepoTags': ['localhost/windowstolinux-candidate:demo-0123456789abcdef']}]).encode()
                with tarfile.open(archive, 'w') as output:
                    for name, data in [(config_name, config), ('manifest.json', manifest), (layer, b'layer bytes')]:
                        item = tarfile.TarInfo(name); item.size = len(data)
                        output.addfile(item, io.BytesIO(data))
                    item = tarfile.TarInfo('b' * 64 + '/layer.tar')
                    item.type = tarfile.LNKTYPE if scenario == 'hardlink' else tarfile.SYMTYPE
                    item.linkname = '/etc/passwd' if scenario == 'escape' else '../' + (
                        'c' * 64 + '.tar' if scenario == 'unknown-layer' else layer)
                    output.addfile(item)
                result = subprocess.run([sys.executable, '-I', '-c', normalizer, str(archive)],
                                        text=True, capture_output=True, timeout=20)
                if scenario in ('escape', 'unknown-layer'):
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('BUILD_REJECT=container-export-link', result.stderr)
                    continue
                self.assertEqual(0, result.returncode, result.stderr)
                validation = subprocess.run([sys.executable, '-I', '-c', validator, str(archive),
                                             'demo', 'demo-0123456789abcdef',
                                             'localhost/windowstolinux-candidate:demo-0123456789abcdef'],
                                            text=True, capture_output=True, timeout=20)
                self.assertEqual(scenario == 'valid', validation.returncode == 0, validation.stderr)
                if scenario == 'valid':
                    with tarfile.open(archive) as output:
                        self.assertTrue(all(member.isfile() for member in output))
                        self.assertEqual(b'layer bytes', output.extractfile(layer).read())


class ContainerMigrationTest(unittest.TestCase):
    def test_stopped_volume_data_is_restored_and_failed_copy_never_marks_migrated(self):
        bash = shutil.which('bash') or 'E:/Program/Git/bin/bash.exe'
        if not pathlib.Path(bash).is_file():
            self.skipTest('Bash is unavailable')
        fragment = (SOURCE / 'execution/protocol/helper/fragments/release/52-container-recovery.sh').read_text()
        for scenario in ('rollback', 'copy-failure', 'stop-failure'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                (root / 'snapshot').mkdir()
                (root / 'volume').mkdir()
                (root / 'volume/original').write_text('old application data')
                (root / '.container-migration-snapshot').write_text('token')
                # Git Bash receives its own canonical path to avoid Windows path conversion ambiguity. / 向 Git Bash 传入其规范路径，避免 Windows 路径转换歧义。
                setup = r"""
set -eu
root="$(pwd)"
app_root() { printf '%s' "$root"; }
snapshot_root() { printf '%s/snapshot' "$root"; }
require_snapshot_token() { test "$1" = token; }
assert_root_owned_regular() { test -f "$1" && test ! -L "$1"; }
assert_root_owned_directory() { test -d "$1" && test ! -L "$1"; }
reject() { printf 'REJECT=%s\n' "$1"; exit 41; }
chown() { :; }
getfacl() { printf 'original-acl\n'; }
setfacl() { printf 'ACL_RESTORED\n'; }
controlled_container_volume_path() { printf '%s/volume' "$root"; }
container_name() { printf 'windowstolinux-demo'; }
container_engine=docker
printf '%s/volume\n' "$root" > "$root/snapshot/volume-fixture.path"
container_volumes=(fixture:/data:0)
"""
                if scenario == 'stop-failure':
                    action = 'docker() { if [ "$1" = inspect ]; then return 0; else return 1; fi; }; stop_container_runtime demo'
                else:
                    action = ('cp() { return 1; }; ' if scenario == 'copy-failure' else '')
                    action += 'stage_container_volume_migration demo owner fixture 10001:10001\n'
                    if scenario == 'rollback':
                        action += r"""
printf 'new application data' > "$root/volume/original"
printf 'new' > "$root/volume/new-file"
restore_container_volume_access demo owner "$root/snapshot"
"""
                result = subprocess.run([bash, '--noprofile', '--norc', '-s'], cwd=root,
                                        input=fragment + '\n' + setup + '\n' + action,
                                        text=True, capture_output=True, timeout=20)
                self.assertEqual(0 if scenario == 'rollback' else 41, result.returncode, result.stderr + result.stdout)
                self.assertEqual('old application data', (root / 'volume/original').read_text())
                self.assertFalse((root / 'volume/new-file').exists())
                if scenario == 'rollback':
                    self.assertIn('ACL_RESTORED', result.stdout)
                else:
                    self.assertFalse((root / 'snapshot/volume-fixture.migrated').exists())


class DynamicStateMappingTest(unittest.TestCase):
    def test_only_exact_private_target_or_its_canonical_relative_link_is_admitted(self):
        bash = shutil.which('bash') or 'E:/Program/Git/bin/bash.exe'
        if not pathlib.Path(bash).is_file():
            self.skipTest('Bash is unavailable')
        fragment = (SOURCE / 'runtime/systemd/helper/61-dynamic-identity.sh').read_text()
        script = fragment + r"""
set -eu
public=/var/lib/windowstolinux/data/example/api
private=/var/lib/private/windowstolinux/data/example/api
readlink() { printf '%s' "$link"; }
for link in "$private" ../../../private/windowstolinux/data/example/api; do
  state_link_matches "$public" "$private" || exit 41
done
for link in /etc ../../../private/windowstolinux/data/other/api ../api/../../../private/windowstolinux/data/example/api; do
  if state_link_matches "$public" "$private"; then exit 42; fi
done
"""
        result = subprocess.run([bash, '--noprofile', '--norc', '-s'], input=script,
                                text=True, capture_output=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)


class CandidateCleanupTest(unittest.TestCase):
    def test_image_cleanup_selects_exact_tag_and_preserves_other_tags_and_failed_records(self):
        bash = shutil.which('bash') or 'E:/Program/Git/bin/bash.exe'
        fragment = (SOURCE / 'execution/protocol/helper/fragments/release/12-container-image-input.sh').read_text()
        for failure in ('none', 'query', 'owner', 'remove'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                marker = pathlib.Path(temporary) / '.image-engine'
                marker.write_text('podman\n')
                script = fragment + r'''
set -eu
candidate_root() { pwd; }
assert_root_owned_regular() { :; }
reject() { echo "$1" >&2; exit 64; }
podman() {
  case "$1 $2" in
    'image ls')
      [ "$failure" != query ] || return 9
      printf '%s\n' localhost/windowstolinux-demo:release
      [ -f removed ] || printf '%s\n' localhost/windowstolinux-candidate:demo-0123456789abcdef
      ;;
    'image inspect')
      [ "$failure" != owner ] || { echo foreign; return; }
      case "$4" in *application*) echo demo ;; *candidate*) echo demo-0123456789abcdef ;; esac
      ;;
    'image rm')
      [ "$3" = localhost/windowstolinux-candidate:demo-0123456789abcdef ] || exit 42
      [ "$failure" != remove ] || return 9
      touch removed
      ;;
    *) exit 43 ;;
  esac
}
'''
                script += 'failure=' + failure + '\ncleanup_candidate_image demo demo-0123456789abcdef\n'
                script += 'cleanup_candidate_image demo demo-0123456789abcdef\n'
                result = subprocess.run([bash, '--noprofile', '--norc', '-s'], cwd=temporary,
                                        input=script, text=True, capture_output=True, timeout=20)
                self.assertEqual(0 if failure == 'none' else 64, result.returncode, result.stderr)
                self.assertEqual(failure != 'none', marker.exists())
                self.assertEqual(failure == 'none', (pathlib.Path(temporary) / 'removed').exists())

    def test_volume_freeze_precedes_identity_release_and_failure_preserves_identity(self):
        bash = shutil.which('bash') or 'E:/Program/Git/bin/bash.exe'
        if not pathlib.Path(bash).is_file():
            self.skipTest('Bash is unavailable')
        fragment = (SOURCE / 'execution/protocol/helper/fragments/workspace/20-candidate-workspace.sh').read_text()
        for failed in (False, True):
            with self.subTest(freeze_failed=failed), tempfile.TemporaryDirectory() as temporary:
                script = fragment + r"""
set -eu
candidate_root() { pwd; }
require_app() { :; }
require_candidate() { :; }
assert_candidate_for_deployer() { :; }
stop_candidate_build() { printf 'stop\n'; }
mountpoint() { return 0; }
cleanup_container_builder() { printf 'identity\n'; }
cleanup_candidate_image() { printf 'image\n'; }
cleanup_restore_candidates() { printf 'restore\n'; }
cleanup_workspace_volume() { printf 'volume\n'; }
rm() { printf 'remove\n'; }
"""
                script += "freeze_candidate_build() { printf 'freeze\\n'; " + ('exit 41;' if failed else ':;') + ' }\n'
                script += 'cleanup_candidate demo demo-0123456789abcdef\n'
                result = subprocess.run([bash, '--noprofile', '--norc', '-s'], cwd=temporary,
                                        input=script, text=True, capture_output=True, timeout=20)
                self.assertEqual(41 if failed else 0, result.returncode, result.stderr)
                steps = result.stdout.splitlines()
                self.assertEqual(['stop', 'freeze'], steps[:2])
                if failed:
                    self.assertNotIn('identity', steps)
                    self.assertNotIn('remove', steps)
                else:
                    self.assertLess(steps.index('freeze'), steps.index('identity'))
                    self.assertLess(steps.index('volume'), steps.index('remove'))


if __name__ == '__main__':
    unittest.main()
