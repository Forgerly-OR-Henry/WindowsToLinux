"""Run original-operation fencing in actual Bash with controlled OS locks. / 在真实 Bash 中以受控系统锁验证原操作围栏。"""
import os
import pathlib
import subprocess
import tempfile
import unittest

FRAGMENTS = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper'
OPERATION = 'backup-' + 'a' * 32
TASK = 'b' * 32
REVISION = 'c' * 64


class HandoffOperationFenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).resolve().parents[3] / 'target')
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.script = self.root / 'fence.sh'
        sources = ''.join("source '" + (FRAGMENTS / name).as_posix() + "'\n" for name in (
            'backup/67-managed-backup.sh', 'backup/69-handoff-backup-status.sh'))
        self.script.write_text(sources + r'''
set -eu
PATH="/usr/bin:/bin:$PATH"
base_root="$1/base"; backups_root="$base_root/backups"; deployer=controlled-owner
mode="$2"; shift 2
reject() { printf 'REJECT=%s\n' "$1"; exit 73; }
require_app() { [[ "$1" =~ ^[a-z][a-z0-9-]*$ ]] || reject app; }
require_digest() { [[ "$1" =~ ^[0-9a-f]{64}$ ]] || reject digest; }
require_snapshot_token() { [[ "$1" =~ ^[0-9a-f]{32}$ ]] || reject task; }
assert_root_owned_directory() {
    [ "$1" = /var/lib ] && return
    [ -d "$1" ] && [ ! -L "$1" ] || reject directory
}
assert_root_owned_regular() { [ -f "$1" ] && [ ! -L "$1" ] || reject regular; }
install() { mkdir -p -- "${!#}"; }
chown() { :; }
chmod() { :; }
# Windows has no Linux flock; this boundary models only contention outcomes.
flock() { [ ! -e "$base_root/held" ]; }
prepare_backup_operation() { printf 'UNEXPECTED_PREPARE\n' >"$base_root/effect"; reject prepared; }
case "$mode" in
 producer)
    printf 'READY\n'
    read -r resume
    resume="${resume%$'\r'}"
    [ "$resume" = GO ] || reject resume
    backup_create_artifact "$1" demo api demo-api "$3" "$3" file data 1024
    ;;
 close) handoff_operation_close "$1" demo "$2" "$3" ;;
 status) handoff_operation_close_status "$1" demo "$2" "$3" ;;
 *) reject mode ;;
esac
''')
        self.arguments = [os.environ.get('MANAGED_TEST_BASH', 'bash'), str(self.script), self.root.as_posix()]

    def invoke(self, mode, task=TASK):
        return subprocess.run(self.arguments + [mode, OPERATION, task, REVISION],
                              text=True, capture_output=True, timeout=20)

    def test_writer_paused_before_lock_cannot_create_after_closed_receipt(self):
        producer = subprocess.Popen(self.arguments + ['producer', OPERATION, TASK, REVISION],
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual('READY\n', producer.stdout.readline())
            closed = self.invoke('close')
            self.assertEqual(0, closed.returncode, closed.stderr + closed.stdout)
            self.assertEqual('HANDOFF_OPERATION_CLOSED=1\n', closed.stdout)
            output, error = producer.communicate('GO\n', timeout=20)
            self.assertEqual(73, producer.returncode, output + error)
            self.assertIn('REJECT=handoff-operation-closed', output)
            self.assertFalse((self.root / 'base/effect').exists())
            self.assertFalse((self.root / 'base/backups').exists())
            self.assertEqual('HANDOFF_OPERATION_CLOSED=1\n', self.invoke('status').stdout)
        finally:
            if producer.poll() is None:
                producer.kill()
                producer.communicate(timeout=10)

    def test_missing_status_is_read_only_and_close_owner_cannot_change(self):
        absent = self.invoke('status')
        self.assertEqual(0, absent.returncode, absent.stderr)
        self.assertEqual('HANDOFF_OPERATION_CLOSED=0\n', absent.stdout)
        self.assertFalse((self.root / 'base').exists())
        self.assertEqual(0, self.invoke('close').returncode)
        receipt = self.root / ('base/backup-operation-locks/.closed-' + OPERATION)
        original = receipt.read_bytes()
        self.assertEqual(73, self.invoke('close', 'd' * 32).returncode)
        self.assertEqual(73, self.invoke('status', 'd' * 32).returncode)
        self.assertEqual(original, receipt.read_bytes())

    def test_active_writer_prevents_close_without_creating_a_fence(self):
        (self.root / 'base').mkdir()
        (self.root / 'base/held').write_text('controlled contention')
        result = self.invoke('close')
        self.assertEqual(73, result.returncode, result.stderr + result.stdout)
        self.assertIn('REJECT=handoff-operation-writer-active', result.stdout)
        self.assertFalse((self.root / ('base/backup-operation-locks/.closed-' + OPERATION)).exists())


if __name__ == '__main__':
    unittest.main()
