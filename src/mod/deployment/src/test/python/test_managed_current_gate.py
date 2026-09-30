"""Exercise actual helper lifecycle/maintenance gates with controlled OS boundaries. / 用受控系统边界执行真实helper生命周期和维护门禁。"""
import os
import pathlib
import subprocess
import tempfile
import unittest

FRAGMENTS = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/execution/protocol/helper/fragments'


class ManagedCurrentGateTest(unittest.TestCase):
    def invoke(self, function, arguments, drift=True, empty=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            script = root / 'gate.sh'
            sources = ''.join("source '" + (FRAGMENTS / name).as_posix() + "'\n" for name in (
                'runtime/40-typed-runtime.sh', 'runtime/41-application-runtime.sh', 'release/52-container-recovery.sh',
                'release/58-retained-activation.sh'))
            script.write_text(sources + r"""
set -eu
PATH="/usr/bin:/bin:$PATH"
root="$1"
shift
require_app() { :; }
require_digest() { [[ "$1" =~ ^[0-9a-f]{64}$ ]] || reject digest; }
require_snapshot_token() { [[ "$1" =~ ^[0-9a-f]{32}$ ]] || reject token; }
assert_root_owned_regular() { [ -f "$1" ] || reject marker; }
application_lock_directory() { printf '%s' "$root/locks"; }
application_maintenance() { printf 'held' >"$root/locked"; }
app_root() { printf '%s' "$root/app"; }
reject() { printf 'REJECT=%s\n' "$1"; exit 73; }
assert_deployment_current_or_empty() {
    [ -f "$root/locked" ] || reject unlocked-current-check
    previous_present=1
    previous_path="$root/app/releases/$(cat "$root/current")"
    application_mode=DAEMON
}
container_current_release() { assert_deployment_current_or_empty "$@"; }
application_load_current() {
    assert_deployment_current_or_empty "$@"
    application_release="$previous_path"
}
application_assert_inputs() { :; }
stop_requested_application_unit() { printf 'stop\n' >>"$root/effects"; }
stop_container_runtime() { printf 'container-stop\n' >>"$root/effects"; }
systemctl() { printf 'systemctl\n' >>"$root/effects"; }
docker() { printf 'docker\n' >>"$root/effects"; }
container_name() { printf 'controlled-container'; }
container_engine=docker
snapshot_deployment() { printf 'snapshot\n' >>"$root/effects"; printf 'SNAPSHOT=controlled\n'; }
snapshot_container() { printf 'snapshot-container\n' >>"$root/effects"; printf 'SNAPSHOT=controlled\n'; }
""" + function + ' ' + ' '.join(arguments) + '\n')
            (root / 'locks').mkdir()
            (root / 'app').mkdir()
            original = 'a' * 64
            # A successful prior status is deliberately followed by a changed CURRENT.
            (root / 'current').write_text(original)
            self.assertEqual(original, (root / 'current').read_text())
            if drift:
                (root / 'current').write_text('b' * 64)
            if not empty or drift:
                (root / 'app/current').write_text((root / 'current').read_text())
            if arguments[0] == 'end':
                (root / 'locks/maintenance').write_text(arguments[3])
            result = subprocess.run([os.environ.get('MANAGED_TEST_BASH', 'bash'), str(script), root.as_posix()],
                                    text=True, capture_output=True, timeout=20)
            effects = (root / 'effects').read_text() if (root / 'effects').exists() else ''
            marker = (root / 'locks/maintenance').exists()
            return result, effects, marker

    def test_lifecycle_drift_refuses_all_effects_inside_application_lock(self):
        for function in ('lifecycle_deployment', 'lifecycle_container'):
            for action in ('start', 'stop', 'restart'):
                with self.subTest(function=function, action=action):
                    result, effects, _ = self.invoke(function, ['app', action, 'c' * 64, 'a' * 64])
                    self.assertEqual(73, result.returncode, result.stderr)
                    self.assertIn('REJECT=lifecycle-current-drift', result.stdout)
                    self.assertEqual('', effects)

    def test_maintenance_begin_drift_does_not_create_marker(self):
        result, effects, marker = self.invoke('application_maintenance_control',
                ['begin', 'app', 'c' * 64, 'backup-' + 'd' * 32, 'a' * 64])
        self.assertEqual(73, result.returncode, result.stderr)
        self.assertIn('REJECT=application-maintenance-current-drift', result.stdout)
        self.assertFalse(marker)
        self.assertEqual('', effects)

    def test_maintenance_end_removes_only_original_marker_even_after_current_drift(self):
        result, effects, marker = self.invoke('application_maintenance_control',
                ['end', 'app', 'c' * 64, 'backup-' + 'd' * 32, 'a' * 64])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('MAINTENANCE=end', result.stdout)
        self.assertFalse(marker)
        self.assertEqual('', effects)

    def test_exact_current_lifecycle_still_executes_effect(self):
        for function in ('lifecycle_deployment', 'lifecycle_container'):
            with self.subTest(function=function):
                result, effects, _ = self.invoke(function, ['app', 'stop', 'c' * 64, 'a' * 64], False)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('LIFECYCLE=stop', result.stdout)
                self.assertNotEqual('', effects)

    def test_retained_snapshot_drift_creates_no_snapshot(self):
        for kind in ('deployment', 'container'):
            with self.subTest(kind=kind):
                result, effects, _ = self.invoke('snapshot_retained',
                        ['app', 'c' * 64, 'd' * 32, 'a' * 64, kind])
                self.assertEqual(73, result.returncode, result.stderr)
                self.assertIn('REJECT=snapshot-retained-current', result.stdout)
                self.assertEqual('', effects)

    def test_retained_snapshot_exact_current_preserves_receipt(self):
        for kind in ('deployment', 'container'):
            with self.subTest(kind=kind):
                result, effects, _ = self.invoke('snapshot_retained',
                        ['app', 'c' * 64, 'd' * 32, 'a' * 64, kind], False)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('SNAPSHOT=controlled', result.stdout)
                self.assertNotEqual('', effects)

    def test_first_target_maintenance_requires_actual_empty_current(self):
        for drift in (False, True):
            with self.subTest(drift=drift):
                result, effects, marker = self.invoke('application_maintenance_control',
                        ['begin', 'app', 'c' * 64, 'restore-' + 'd' * 32, 'empty'], drift, True)
                self.assertEqual(73 if drift else 0, result.returncode, result.stderr)
                self.assertEqual(not drift, marker)
                self.assertEqual('', effects)
                if drift:
                    self.assertIn('REJECT=application-maintenance-current-drift', result.stdout)
                else:
                    self.assertIn('MAINTENANCE=begin', result.stdout)


if __name__ == '__main__':
    unittest.main()
