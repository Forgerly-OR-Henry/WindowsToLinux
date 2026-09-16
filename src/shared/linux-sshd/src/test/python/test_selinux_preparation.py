"""Exercise the production SELinux script with inert OS commands and a disposable filesystem.
使用无副作用的系统命令及临时文件系统验证生产 SELinux 脚本。
"""
import pathlib
import os
import re
import shutil
import subprocess
import tempfile
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd/distro/dnf/selinux-preparation.sh'


class SelinuxPreparationTest(unittest.TestCase):
    def run_case(self, mode='normal', scenario='complete'):
        bash = os.environ.get('MANAGED_TEST_BASH') or shutil.which('bash')
        if not bash or not pathlib.Path(bash).is_file():
            self.skipTest('Bash is unavailable')
        # One replacement over path prefixes avoids changing nested placeholders twice.
        # 一次性替换路径前缀，避免重复更改嵌套占位符。
        production = SOURCE.read_text(encoding='utf-8')
        production = re.sub(r'/(?:etc|var|proc)(?=/|\s|$)|/\.autorelabel',
                            lambda match: '${TEST_ROOT}' + match.group(0), production)
        setup = r'''
set -eu
export PATH=/usr/bin:/bin:$PATH
mode="$1"; scenario="$2"; TEST_ROOT="$PWD/root"
mkdir -p "$TEST_ROOT/etc/selinux" "$TEST_ROOT/var/lib" "$TEST_ROOT/proc/sys/kernel/random"
printf 'ID=centos\nVERSION_ID=9\n' > "$TEST_ROOT/etc/os-release"
printf 'SELINUX=disabled\nSELINUXTYPE=targeted\n' > "$TEST_ROOT/etc/selinux/config"
printf '11111111-1111-1111-1111-111111111111\n' > "$TEST_ROOT/proc/sys/kernel/random/boot_id"
printf 'root=test\n' > "$TEST_ROOT/proc/cmdline"
printf Disabled > "$TEST_ROOT/security"
id() { if [ "$mode" = nonroot ]; then printf 1000; else printf 0; fi; }
chmod() { :; }
mkdir() { if [ "${1:-}" = -m ]; then shift 2; fi; command mkdir "$@"; }
install() { command mkdir -p "${@: -1}"; }
cp() { command cp -- "${@: -2:1}" "${@: -1}"; }
stat() { case "$2" in %u) printf 0 ;; %a) if [ "$mode" = writable ]; then printf 666; else printf 600; fi ;; *) command stat "$@" ;; esac; }
uname() { printf x86_64; }
rpm() { [ "$mode" != missingpackage ]; }
getenforce() { cat "$TEST_ROOT/security"; }
setenforce() { printf 'setenforce %s\n' "$*" >> calls; [ "$mode" != enforcefail ] || return 1; if [ "$1" = 1 ]; then printf Enforcing > "$TEST_ROOT/security"; else printf Permissive > "$TEST_ROOT/security"; fi; }
fixfiles() { printf 'fixfiles %s\n' "$*" >> calls; [ "$mode" != relabelfail ] || return 1; touch "$TEST_ROOT/.autorelabel"; }
flock() { :; }
ausearch() {
  if [ "$mode" = avcstdin ]; then
    case " $* " in *' --input-logs '*) printf 'avc: denied'; return 0 ;; *) printf '<no matches>\n'; return 1 ;; esac
  fi
  case "$mode" in avc) printf 'avc: denied'; return 0 ;; auditerror) printf 'audit failed'; return 2 ;; *) printf '<no matches>\n'; return 1 ;; esac
}
systemd-run() {
  printf 'systemd-run %s\n' "$*" >> calls
  [ "$mode" != schedulefail ] || return 1
  case "$*" in *--timer-property=RemainAfterElapse=no*) touch "$TEST_ROOT/collect-timer" ;; esac
  case "$1" in *rollback*) touch "$TEST_ROOT/rollback-timer" ;; *) touch "$TEST_ROOT/reboot-timer" ;; esac
}
systemctl() {
  printf 'systemctl %s\n' "$*" >> calls
  case "$1" in
    show)
      case "$2:$4" in
        *:LoadState) if [ "$mode" = collision ]; then printf loaded; else printf not-found; fi ;;
        selinux-autorelabel.service:Result) if [ "$mode" = relabelservicefail ]; then printf failed; else printf success; fi ;;
        *:ActiveState) if [ -f "$TEST_ROOT/rollback-timer" ]; then printf active; else printf inactive; fi ;;
        *) return 1 ;;
      esac ;;
    is-active) case "$3" in *rollback*) test -f "$TEST_ROOT/rollback-timer" ;; *) test -f "$TEST_ROOT/reboot-timer" ;; esac ;;
    stop) [ "$mode" != stopfail ] || return 1; rm -f "$TEST_ROOT/rollback-timer" ;;
    *) return 1 ;;
  esac
}
'''
        call = r'''
approve() {
  selinux_preparation inspect > observation
  local b c p s
  b="$(sed -n 's/^BOOT_ID=//p' observation)"
  c="$(sed -n 's/^CONFIG_SHA256=//p' observation)"
  p="$(sed -n 's/^PREPARATION_STATE=//p' observation)"
  s="$(sed -n 's/^SECURITY_STATE=//p' observation)"
  if [ "$scenario" = stale ]; then c="$(printf b%.0s {1..64})"; fi
  selinux_preparation "$1" "$b" "$c" "$p" "$s"
}
selinux_preparation inspect
if [ "$scenario" = inspect ]; then
  test ! -e "$TEST_ROOT/var/lib/windowstolinux/system-preparation"
  exit
fi
approve prepare
cmp "$TEST_ROOT/var/lib/windowstolinux/system-preparation/selinux/config.before" <(printf 'SELINUX=disabled\nSELINUXTYPE=targeted\n')
if [ "$scenario" = repeat ]; then approve prepare; exit; fi
printf '22222222-2222-2222-2222-222222222222\n' > "$TEST_ROOT/proc/sys/kernel/random/boot_id"
printf Permissive > "$TEST_ROOT/security"
if [ "$mode" != relabelpending ]; then rm "$TEST_ROOT/.autorelabel"; fi
rm "$TEST_ROOT/reboot-timer"
approve enforce
if [ "$scenario" = recover ]; then
  printf Permissive > "$TEST_ROOT/security"
  if [ -f "$TEST_ROOT/collect-timer" ]; then rm "$TEST_ROOT/rollback-timer"; fi
  approve enforce
fi
if [ "$mode" = rollback ]; then printf Permissive > "$TEST_ROOT/security"; fi
approve commit
selinux_preparation inspect
grep -qx 'SELINUX=enforcing' "$TEST_ROOT/etc/selinux/config"
test ! -e "$TEST_ROOT/rollback-timer"
'''
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([bash, '-s', '--', mode, scenario], input=setup + production + '\n' + call,
                                    text=True, cwd=directory, capture_output=True, timeout=15)
            calls = pathlib.Path(directory, 'calls')
            log = calls.read_text() if calls.exists() else ''
            config_path = pathlib.Path(directory, 'root/etc/selinux/config')
            self.assertTrue(config_path.exists(), result.stdout + result.stderr)
            config = config_path.read_text()
        return result, log, config

    def test_inspection_has_no_mutation(self):
        result, log, config = self.run_case(scenario='inspect')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('PREPARATION_STATE=UNPREPARED', result.stdout)
        self.assertEqual('', log)
        self.assertIn('SELINUX=disabled', config)

    def test_real_script_completes_and_cancels_safety_timer(self):
        result, log, config = self.run_case()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('PREPARATION_STATE=COMPLETE', result.stdout)
        self.assertIn('SELINUX=enforcing', config)
        self.assertLess(log.index('--unit=windowstolinux-selinux-rollback'), log.index('setenforce 1'))
        self.assertIn('stop windowstolinux-selinux-rollback.timer', log)

    def test_safety_rollback_can_be_approved_again_and_completed(self):
        result, log, config = self.run_case(scenario='recover')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, log.count('systemd-run --unit=windowstolinux-selinux-rollback'))
        self.assertIn('PREPARATION_STATE=COMPLETE', result.stdout)
        self.assertIn('SELINUX=enforcing', config)

    def test_expired_approval_cannot_change_configuration(self):
        result, log, config = self.run_case(scenario='stale')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('approved-facts-changed', result.stderr)
        self.assertEqual('', log)
        self.assertIn('SELINUX=disabled', config)

    def test_repeat_does_not_schedule_another_reboot(self):
        result, log, _ = self.run_case(scenario='repeat')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, log.count('systemd-run --unit=windowstolinux-selinux-reboot'))

    def test_unsafe_prerequisites_are_rejected_before_configuration_changes(self):
        for mode in ['nonroot', 'writable', 'missingpackage', 'collision']:
            with self.subTest(mode=mode):
                result, log, config = self.run_case(mode)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('SELINUX=disabled', config)
                self.assertNotIn('systemd-run ', log)

    def test_failed_relabel_audit_and_enforcement_never_commit(self):
        for mode in ['relabelfail', 'schedulefail', 'relabelpending', 'relabelservicefail', 'avc', 'auditerror', 'enforcefail', 'rollback', 'stopfail']:
            with self.subTest(mode=mode):
                result, log, config = self.run_case(mode)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertNotIn('SELINUX_PREPARATION_COMPLETE=1', result.stdout)
                self.assertIn('SELINUX=permissive', config)

    def test_piped_input_cannot_hide_denials_from_system_audit_logs(self):
        result, log, config = self.run_case('avcstdin')
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn('setenforce 1', log)
        self.assertIn('SELINUX=permissive', config)


if __name__ == '__main__':
    unittest.main()
