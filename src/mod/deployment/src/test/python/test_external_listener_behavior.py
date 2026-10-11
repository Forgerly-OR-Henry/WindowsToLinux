"""Run native health ownership/address checks against bounded local OS fixtures."""

import os
import pathlib
import shlex
import subprocess
import sys
import tempfile
import unittest


HEALTH = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/runtime/44-application-health.sh'


def listener(address, pid=101, port=46110):
    return f'LISTEN 0 128 {address}:{port} *:* users:(("private-process",pid={pid},fd=5))'


def invoke(kind='TCP', lines='', expected='0.0.0.0', exposure='EXTERNAL', backend='native', next_lines=None,
           endpoint_port=46110, endpoint_protocol=None, target_port=46110):
    source = HEALTH.read_text(encoding='utf-8')
    assert source.count('"/proc/$pid/cgroup"') == 1
    source = source.replace('"/proc/$pid/cgroup"', '"$fixture/proc/$pid/cgroup"', 1)
    protocol = endpoint_protocol or ('UDP' if kind == 'UDP' else 'TCP')
    with tempfile.TemporaryDirectory(prefix='wtl-listener-') as temporary:
        directory = pathlib.Path(temporary)
        for pid, group in ((101, '/owned'), (202, '/foreign'), (303, '/owned/child')):
            target = directory / 'proc' / str(pid)
            target.mkdir(parents=True)
            (target / 'cgroup').write_text(f'0::{group}\n', encoding='utf-8')
        setup = '\n'.join((
            'set -euo pipefail',
            'fixture="$PWD"',
            'python3() { printf "python\\n" >>"$fixture/python-calls"; MSYS2_ARG_CONV_EXCL="*" '
            + shlex.quote(pathlib.Path(sys.executable).as_posix()) + ' "$@"; }',
            'reject() { printf "REJECT:%s\\n" "$1" >&2; exit 64; }',
            'application_process_identity() { printf identity; }',
            'systemctl() { printf /owned; }',
            'ss() { local calls=0; [ ! -f "$fixture/ss-calls" ] || read -r calls <"$fixture/ss-calls"; '
            'calls=$((calls + 1)); printf "%s\\n" "$calls" >"$fixture/ss-calls"; '
            'if [ "$calls" = 1 ]; then printf "%s\\n" "$first_lines"; else printf "%s\\n" "$later_lines"; fi; }',
            'sleep() { SECONDS=$((SECONDS + 1)); }',
            'curl() { printf probe >>"$fixture/probe-calls"; printf 200; }',
            'application_tcp_response() { printf probe >>"$fixture/probe-calls"; }',
            'application_udp_response() { printf probe >>"$fixture/probe-calls"; }',
            'run_application_native() { printf expected-output; }',
            'assert_container_ports() { printf container >>"$fixture/container-calls"; }',
            'container_name() { printf demo; }',
            'fixture_engine() { printf 303; }',
            'nsenter() { printf "%s\\n" "$first_lines"; }',
            'container_engine=fixture_engine',
            'application_backend=' + shlex.quote(backend),
            'application_health_kind=' + shlex.quote(kind),
            'application_mode=DAEMON',
            'application_health_unit=owned',
            'application_health_port=46110',
            'application_health_url=http://127.0.0.1:46110/healthz',
            'application_health_status=200',
            'application_health_timeout=3',
            'application_health_stability=0',
            'application_health_custom=0',
            'application_health_entry=verify',
            'application_health_args=()',
            'application_health_expected=expected-output',
            'application_endpoints=(' + shlex.quote(f'web|{protocol}|{expected}|{endpoint_port}|{target_port}|{exposure}') + ')',
            'first_lines=' + shlex.quote(lines),
            'later_lines=' + shlex.quote(lines if next_lines is None else next_lines),
            'application_health_loaded demo',
        ))
        command = [os.environ.get('MANAGED_TEST_BASH', 'bash'), '--noprofile', '--norc', '-s']
        environment = dict(os.environ, MSYS2_ARG_CONV_EXCL='*', PYTHONUTF8='1')
        result = subprocess.run(command, input=source + '\n' + setup, text=True, encoding='utf-8',
                                capture_output=True, cwd=directory, env=environment, timeout=15)
        calls = int((directory / 'ss-calls').read_text()) if (directory / 'ss-calls').exists() else 0
        python_calls = (directory / 'python-calls').read_text().count('python') if (directory / 'python-calls').exists() else 0
        return result, calls, python_calls


class ExternalListenerBehaviorTest(unittest.TestCase):
    def test_all_native_network_health_kinds_reject_loopback_by_ip_semantics(self):
        for kind in ('TCP', 'HTTP', 'UDP'):
            for address in ('127.0.0.1', '127.23.45.67', '[::1]', '[0:0:0:0:0:0:0:1]',
                            '[::ffff:127.0.0.1]', '[::ffff:7f01:2345]'):
                with self.subTest(kind=kind, address=address):
                    result, calls, _ = invoke(kind, listener(address))
                    self.assertEqual(64, result.returncode, result.stderr)
                    self.assertGreater(calls, 1)
                    self.assertEqual(1, result.stderr.count('APPLICATION_LISTENER_ADDRESS'))
                    self.assertIn('expected=0.0.0.0 observed=', result.stderr)
                    self.assertIn('endpoint=web transport=' + ('udp' if kind == 'UDP' else 'tcp') + ' port=46110', result.stderr)
                    self.assertIn('REJECT:application-health-timeout', result.stderr)
                    self.assertNotIn('HEALTHY=1', result.stdout)
                    self.assertNotIn('private-process', result.stderr)
                    self.assertNotIn('pid=', result.stderr)

    def test_wildcards_and_nonloopback_addresses_need_no_string_equality(self):
        for kind in ('TCP', 'HTTP', 'UDP'):
            for address in ('0.0.0.0', '*', '[::]', '198.51.100.9', '[2001:db8::9]', '[::ffff:192.0.2.9]'):
                with self.subTest(kind=kind, address=address):
                    result, _, _ = invoke(kind, listener(address))
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual('HEALTHY=1\n', result.stdout)
                    self.assertEqual('', result.stderr)

    def test_address_and_cgroup_must_pass_on_the_same_listener_line(self):
        for kind in ('TCP', 'HTTP', 'UDP'):
            with self.subTest(kind=kind, owner='loopback'):
                result, _, _ = invoke(kind, listener('127.0.0.1') + '\n' + listener('0.0.0.0', pid=202))
                self.assertEqual(64, result.returncode, result.stderr)
                self.assertIn('observed=127.0.0.1\n', result.stderr)
                self.assertNotIn('observed=127.0.0.1,0.0.0.0', result.stderr)
            with self.subTest(kind=kind, owner='wildcard'):
                result, _, _ = invoke(kind, listener('127.0.0.1', pid=202) + '\n' + listener('0.0.0.0'))
                self.assertEqual(0, result.returncode, result.stderr)

    def test_internal_and_effective_restore_loopback_declarations_keep_original_behavior(self):
        for exposure, expected in (('INTERNAL', '0.0.0.0'), ('EXTERNAL', '127.0.0.1'),
                                   ('EXTERNAL', '::1'), ('EXTERNAL', '::ffff:127.0.0.1')):
            with self.subTest(exposure=exposure, expected=expected):
                result, _, _ = invoke(lines=listener('127.0.0.1'), exposure=exposure, expected=expected)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('', result.stderr)

    def test_guard_is_limited_to_the_matching_transport_and_health_port(self):
        for overrides in ({'endpoint_port': 46111}, {'endpoint_protocol': 'UDP'}, {'exposure': 'INTERNAL'}):
            with self.subTest(overrides=overrides):
                result, _, python_calls = invoke(lines=listener('127.0.0.1'), **overrides)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(0, python_calls)

    def test_later_valid_listener_can_satisfy_the_original_startup_wait(self):
        result, calls, python_calls = invoke(lines=listener('127.0.0.1'), next_lines=listener('0.0.0.0'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, calls)
        self.assertEqual(2, python_calls)
        self.assertEqual('', result.stderr)

    def test_owned_cgroup_child_listener_can_satisfy_external_health(self):
        for kind in ('TCP', 'HTTP', 'UDP'):
            with self.subTest(kind=kind):
                result, _, _ = invoke(kind, listener('0.0.0.0', pid=303))
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('HEALTHY=1\n', result.stdout)

    def test_container_health_keeps_host_to_target_port_mapping(self):
        result, _, python_calls = invoke(lines=listener('127.0.0.1', port=18110), backend='container', target_port=18110)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, python_calls)
        result, _, python_calls = invoke(lines=listener('127.0.0.1', port=46110), backend='container', target_port=18110)
        self.assertEqual(64, result.returncode, result.stderr)
        self.assertEqual(0, python_calls)
        self.assertNotIn('APPLICATION_LISTENER_ADDRESS', result.stderr)

    def test_container_process_and_command_paths_are_unchanged(self):
        for kind, backend in (('TCP', 'container'), ('PROCESS', 'native'), ('COMMAND', 'native')):
            with self.subTest(kind=kind, backend=backend):
                result, calls, python_calls = invoke(kind, listener('127.0.0.1'), backend=backend)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('HEALTHY=1\n', result.stdout)
                self.assertEqual(0, calls)
                self.assertEqual(0, python_calls)

    def test_timeout_address_diagnostic_is_bounded_and_does_not_echo_raw_rows(self):
        rows = '\n'.join(listener(f'127.0.0.{index}') for index in range(1, 31))
        result, _, _ = invoke(lines=rows)
        self.assertEqual(64, result.returncode, result.stderr)
        diagnostic = result.stderr.splitlines()[0]
        self.assertLess(len(diagnostic), 1200)
        self.assertEqual(1, result.stderr.count('APPLICATION_LISTENER_ADDRESS'))
        self.assertNotIn('127.0.0.17', diagnostic)
        self.assertNotIn('private-process', result.stderr)
        self.assertNotIn('pid=', result.stderr)


if __name__ == '__main__':
    unittest.main()
