"""Verify bounded read-only systemd queries against actual subprocess and timeout boundaries."""
import os
import pathlib
import subprocess
import tempfile
import time
import unittest

FOUNDATION = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/00-protocol-foundation.sh'


class SystemdObservationTest(unittest.TestCase):
    def invoke(self, command, seconds=20):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            (root / 'bin').mkdir()
            shim = root / 'bin/systemctl'
            shim.write_text('#!/usr/bin/bash\nset -eu\n'
                            'count=0\n[ ! -f "$MANAGED_QUERY_ROOT/count" ] || count="$(cat "$MANAGED_QUERY_ROOT/count")"\n'
                            'printf "%s" "$((count + 1))" >"$MANAGED_QUERY_ROOT/count"\n' + command + '\n')
            shim.chmod(0o700)
            script = FOUNDATION.read_text(encoding='utf-8') + r'''
if command -v cygpath >/dev/null 2>&1; then MANAGED_QUERY_ROOT="$(cygpath -u "$MANAGED_QUERY_ROOT")"; export MANAGED_QUERY_ROOT; fi
PATH="$MANAGED_QUERY_ROOT/bin:$PATH"
sleep() { printf '%s\n' "$1" >>"$MANAGED_QUERY_ROOT/delays"; }
systemd_query show --value --property FragmentPath demo.service || exit 75
printf effect >"$MANAGED_QUERY_ROOT/effect"
'''
            environment = dict(os.environ, WTL_SYSTEMD_QUERY_SECONDS=str(seconds),
                               MANAGED_QUERY_ROOT=root.as_posix(), MSYS2_ARG_CONV_EXCL='*')
            start = time.monotonic()
            result = subprocess.run([os.environ.get('MANAGED_TEST_BASH', 'E:/Program/Git/bin/bash.exe'), '-s'],
                                    input=script, text=True, capture_output=True, env=environment, timeout=10)
            elapsed = time.monotonic() - start
            return result, (root / 'count').read_text(), (root / 'delays').read_text() if (root / 'delays').exists() else '', (root / 'effect').exists(), elapsed

    def test_nonzero_queries_are_tried_three_times_with_one_and_two_second_delays(self):
        result, count, delays, effect, _ = self.invoke('printf "bus unavailable\\n" >&2; exit 1')
        self.assertEqual(75, result.returncode, result.stderr)
        self.assertEqual('3', count)
        self.assertEqual('1\n2\n', delays)
        self.assertFalse(effect)
        self.assertEqual(1, result.stderr.count('MANAGED_HELPER_OBSERVATION_UNAVAILABLE=systemd-query'))

    def test_hanging_query_ends_at_original_request_budget_without_retry_or_effect(self):
        result, count, delays, effect, elapsed = self.invoke('sleep 5; printf /foreign/unit', 1)
        self.assertEqual(75, result.returncode, result.stderr)
        self.assertEqual('1', count)
        self.assertEqual('', delays)
        self.assertFalse(effect)
        self.assertLess(elapsed, 3)


if __name__ == '__main__':
    unittest.main()
