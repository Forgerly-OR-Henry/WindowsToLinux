"""Execute the real lifecycle fragments with an inert systemctl command boundary."""
import pathlib
from compatibility_resources import read_fragment
import shutil
import subprocess
import tempfile
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd'


class NativeLifecycleTest(unittest.TestCase):
    def run_helper(self, mode, action='stop', legacy=False, twice=False, workload='DAEMON'):
        bash = shutil.which('bash') or 'E:/Program/Git/bin/bash.exe'
        if not pathlib.Path(bash).is_file():
            self.skipTest('Bash is unavailable')
        identity = read_fragment(SOURCE / 'runtime/systemd/helper/61-service-identity.sh')
        lifecycle = (SOURCE / ('runtime/systemd/helper/60-lifecycle.sh' if legacy else
                              'execution/protocol/helper/fragments/runtime/40-typed-runtime.sh')).read_text()
        setup = r'''
set -eu
export PATH=/usr/bin:/bin:$PATH
mode="$1"
application_mode="$2"
CGROUP_ROOT="$PWD/cgroup"
mkdir -p "$CGROUP_ROOT/test"
: > "$CGROUP_ROOT/test/cgroup.procs"
case "$mode" in residue|bad-residue) printf '222\n' > "$CGROUP_ROOT/test/cgroup.procs" ;; esac
printf failed > state
reject() { printf 'REJECT=%s\n' "$1"; exit 64; }
require_app() { :; }
require_digest() { :; }
unit_name() { printf 'windowstolinux-%s.service' "$1"; }
assert_deployment_current_or_empty() { [ "$mode" != foreign ] || reject foreign-owner; previous_present=1; }
assert_current_or_empty() { assert_deployment_current_or_empty "$@"; }
find() { [ "$mode" != groupqueryfail ] || return 1; command find "$@"; }
systemctl() {
  printf '%s\n' "$*" >> calls
  case "$1" in
    stop)
      touch stopped
      case "$mode" in stopfail|bad-setting|bad-residue|bad-pid|bad-active) return 1 ;; timeout) return 124 ;; transition) printf deactivating > state ;; normal) printf inactive > state ;; esac ;;
    reset-failed) [ "$mode" != resetfail ] || return 1; printf inactive > state ;;
    is-active) return 3 ;;
    is-enabled) printf 'disabled\n'; return 1 ;;
    show)
      if [ "$2" = --value ]; then
        case "$4" in
          LoadState) case "$mode" in loadqueryfail) return 1 ;; loadempty) : ;; notfound) printf 'not-found\n' ;; bad-*) printf 'bad-setting\n' ;; *) printf 'loaded\n' ;; esac ;;
          ActiveState) if [ "$mode" = bad-active ]; then printf active; else cat state; fi ;;
          MainPID) if [ "$mode" = pid ] || [ "$mode" = bad-pid ]; then printf 222; else printf 0; fi ;;
          ControlGroup) printf '/test\n' ;;
        esac
      else
        [ "$mode" != queryfail ] || return 1
        if [ "$mode" = afterqueryfail ] && [ -f stopped ]; then return 1; fi
        printf 'ActiveState=%s\nSubState=failed\nResult=exit-code\nExecMainCode=1\nExecMainStatus=129\nMainPID=0\n' "$(cat state)"
      fi ;;
    *) return 1 ;;
  esac
}
'''
        entry = 'lifecycle' if legacy else 'lifecycle_deployment'
        call = f'{entry} demo {action} digest\n'
        if action == 'observe':
            call = 'observe_deployment demo digest\n'
        if action == 'recovery-stop':
            call = 'stop_application_unit demo\n'
        script = setup + identity.replace('/sys/fs/cgroup', '${CGROUP_ROOT}') + '\n' + lifecycle + '\n' + call * (2 if twice else 1)
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run([bash, '-s', '--', mode, workload], input=script, text=True,
                                    cwd=temporary, capture_output=True, timeout=10)
            calls = pathlib.Path(temporary, 'calls')
            log = calls.read_text() if calls.exists() else ''
        return result, log

    def test_recovery_of_bad_unit_requires_independently_verified_stopped_state(self):
        for mode, expected in [('bad-setting', 0), ('bad-pid', 64), ('bad-active', 64), ('bad-residue', 64)]:
            with self.subTest(mode=mode):
                result, calls = self.run_helper(mode, action='recovery-stop')
                self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
                self.assertIn('stop windowstolinux-demo.service', calls)

    def test_one_shot_remains_installed_and_rejects_every_lifecycle_mutation(self):
        result, calls = self.run_helper('normal', action='observe', workload='ON_DEMAND')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('INSTALLED=1', result.stdout)
        self.assertEqual('', calls)
        for action in ('start', 'stop', 'restart', 'enable', 'disable'):
            result, calls = self.run_helper('normal', action=action, workload='ON_DEMAND')
            self.assertEqual(64, result.returncode, result.stderr)
            self.assertIn('application-lifecycle-not-applicable', result.stdout)
            self.assertEqual('', calls)

    def test_checked_stop_clears_only_current_failed_unit_and_is_idempotent(self):
        for legacy in (False, True):
            for mode in ('normal', 'yarn129'):
                with self.subTest(legacy=legacy, mode=mode):
                    result, calls = self.run_helper(mode, legacy=legacy, twice=True)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertEqual(0 if mode == 'normal' else 1, calls.count('reset-failed '))
                    self.assertNotIn('reset-failed\n', calls)
                    if mode != 'normal':
                        self.assertIn('reset-failed windowstolinux-demo.service', calls)
                    self.assertIn('STOP_BEFORE_ExecMainStatus=129', result.stdout)
                    self.assertIn('STOP_AFTER_ExecMainStatus=129', result.stdout)

    def test_stop_failure_timeout_residue_and_ownership_never_reset(self):
        for legacy in (False, True):
            for mode in ('stopfail', 'timeout', 'transition', 'pid', 'residue', 'foreign', 'queryfail', 'loadqueryfail', 'loadempty', 'notfound', 'groupqueryfail', 'afterqueryfail'):
                with self.subTest(legacy=legacy, mode=mode):
                    result, calls = self.run_helper(mode, legacy=legacy)
                    self.assertNotEqual(0, result.returncode, result.stdout)
                    self.assertIn('REJECT=', result.stdout, result.stderr)
                    self.assertNotIn('reset-failed', calls)
                    if mode == 'foreign': self.assertEqual('', calls)

    def test_failed_reset_does_not_report_a_successful_stop(self):
        result, calls = self.run_helper('resetfail')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('REJECT=runtime-reset-failed', result.stdout)
        self.assertIn('STOP_BEFORE_ExecMainStatus=129', result.stdout)
        self.assertEqual(1, calls.count('reset-failed windowstolinux-demo.service'))

    def test_observe_keeps_failures_and_reports_query_failure(self):
        for mode, query in [('yarn129', '1'), ('queryfail', '0')]:
            result, calls = self.run_helper(mode, action='observe')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('QUERY_OK=' + query, result.stdout)
            self.assertNotIn('reset-failed', calls)
            self.assertNotIn('stop ', calls)
            self.assertIn('RUNNING=0', result.stdout)


if __name__ == '__main__':
    unittest.main()
