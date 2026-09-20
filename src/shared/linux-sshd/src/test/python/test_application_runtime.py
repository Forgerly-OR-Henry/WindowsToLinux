"""Exercise production APP protocol and UDP responses without root or service mutation."""
import base64
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2] / "main/resources/gold/debug/windowstolinux/shared/linux/sshd/execution/protocol/helper/fragments"
BASH = shutil.which("bash") or "E:/Program/Git/bin/bash.exe"
SETUP = """set -euo pipefail
PATH=/usr/bin:/bin:$PATH
export MSYS2_ARG_CONV_EXCL='*'
reject() { printf 'REJECT=%s\n' "$1"; exit 64; }
require_app() { [[ "$1" =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]] || reject app; }
"""

def execute(fragment, body):
    python = sys.executable.replace("\\", "/").replace("'", "'\"'\"'")
    return subprocess.run([BASH, "-s"], input=SETUP + f"python3() {{ '{python}' \"$@\"; }}\n" + (ROOT / fragment).read_text() + "\n" + body,
                          text=True, capture_output=True, timeout=12, encoding="utf-8", errors="replace")

def payload(mode="ON_DEMAND", endpoints=(), inputs=(), companions=(), verify=True, workers=()):
    fields = ["application-v2" if workers else "application-v1", mode, "1", "", "", "main.py", "0", "1" if verify else "0"]
    if verify: fields += ["main.py", "2", "--self-test", "中文 空格 $HOME %i"]
    fields += ["ok", "0", str(len(endpoints))]
    for endpoint in endpoints: fields += list(endpoint)
    fields += [str(len(inputs))]
    for value in inputs: fields += list(value)
    fields += [str(len(companions))]
    for value in companions: fields += list(value)
    if workers:
        fields += [str(len(workers))]
        for identifier, entry, arguments in workers: fields += [identifier, entry, str(len(arguments)), *arguments]
    fields += ["PROCESS", "10", "5"]
    return base64.b64encode(("\0".join(fields) + "\0").encode()).decode()

class ApplicationRuntimeTest(unittest.TestCase):
    def test_native_worker_renderer_passes_argv_without_shell_expansion(self):
        import shlex
        encoded = payload('DAEMON', workers=(('queue', 'worker.php', ['中文 参数', '$HOME %i']),))
        runtime = (ROOT / 'runtime/41-application-runtime.sh').read_text(encoding='utf-8')
        result = execute('input/16-application-input.sh', runtime + f'''
parse_application_payload '{encoded}'
toolchain_php=/opt/php/bin/php
application_primary_exec=(/opt/php/bin/php main.py)
command=unused
render_application_command php /opt/apps/demo
printf '%s' "$command"
''')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        argv = shlex.split(result.stdout)
        commands = json.loads(base64.b64decode(argv[-1]))
        self.assertEqual(['/opt/php/bin/php', '/opt/apps/demo/current/source/worker.php', '中文 参数', '$HOME %i'], commands[1])
        self.assertEqual(['/opt/php/bin/php', '/opt/apps/demo/current/source/main.py'], commands[0])

    def test_workers_preserve_literal_arguments_and_require_unique_daemon_entries(self):
        worker = ('queue', 'worker.php', ['中文 参数', '$HOME %i'])
        result = self.parse(payload('DAEMON', workers=(worker,)), 'printf "%s\\n" "${application_worker_arguments[@]}"')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(worker[2], result.stdout.splitlines())
        for value in (payload(workers=(worker,)), payload('DAEMON', workers=(worker,worker)),
                      payload('DAEMON', workers=(('queue','../escape.php',[]),))):
            self.assertEqual(64, self.parse(value).returncode)

    def test_unit_working_directory_is_a_literal_path_not_a_quoted_command_argument(self):
        foundation = (ROOT / '00-protocol-foundation.sh').read_text(encoding='utf-8')
        result = execute('runtime/41-application-runtime.sh', foundation + '''
parse_deployment_inputs() { deployment_remaining_arguments=(); deployment_secret_identifiers=(); deployment_secret_names=(); }
parse_managed_data_bindings() { managed_data_remaining_arguments=(); }
parse_toolchain_binding() { toolchain_remaining_arguments=(springboot MAVEN); }
configuration_path() { printf /etc/opt/windowstolinux/apps/demo/config.env; }
wrap_database_runtime() { :; }
render_service_runtime_identity() { printf 'User=fixture\\n'; }
deployment_configuration_digest=unused; toolchain_binding=; toolchain_java=/usr/bin/java
application_builddir=; application_entry=; application_args=(); application_mode=DAEMON
application_input_sources=(); application_companion_ids=(); application_workdir='中文 workspace'
runtime_identity_policy=SYSTEMD_STATIC
render_deployment_unit demo
''')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('WorkingDirectory=/opt/windowstolinux/apps/demo/current/source/中文 workspace\n', result.stdout)
        self.assertIn('ExecStart="/usr/bin/java" "-jar"', result.stdout)

    def test_collected_native_jobs_are_clean_but_running_or_unknown_units_are_not(self):
        for state, expected in (("not-found", 0), ("loaded", 0), ("error", 64)):
            result = execute("runtime/42-application-job.sh", f'''
systemctl() {{ case "$*" in *LoadState*) printf '%s\\n' '{state}';; *MainPID*) echo 0;; *ActiveState*) echo inactive;; *) return 1;; esac; }}
application_assert_native_job_stopped windowstolinux-job-fixture.service
''')
            self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        result = execute("runtime/42-application-job.sh", '''
systemctl() { case "$*" in *LoadState*) echo loaded;; *MainPID*) echo 42;; *) return 1;; esac; }
application_assert_native_job_stopped windowstolinux-job-fixture.service
''')
        self.assertEqual(64, result.returncode)

    def parse(self, encoded, suffix=""):
        return execute("input/16-application-input.sh", f"parse_application_payload '{encoded}'\n" + suffix)

    def test_literal_arguments_and_default_process_validation(self):
        result = self.parse(payload(), 'printf "%s\n" "${application_verify_args[1]}" "$application_health_stability"')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["中文 空格 $HOME %i", "5"], result.stdout.splitlines())

    def test_unicode_read_only_paths_remain_literal_and_escape_paths_fail(self):
        result = self.parse(payload(inputs=(("source", "/srv/中文 输入", "/inputs/资料"),)), 'printf "%s\\n" "${application_input_sources[0]}"')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('/srv/中文 输入', result.stdout.strip())
        for path in ('/srv/../secret', '/srv/data,readonly=false', '/srv/$HOME', '/srv//data'):
            self.assertEqual(64, self.parse(payload(inputs=(("source", path, "/inputs/data"),))).returncode)

    def test_tampered_or_unreviewed_payload_fails_before_any_action(self):
        for data in [payload()[:-4], payload(verify=False), base64.b64encode(b"application-v0\0").decode(),
                     payload(inputs=(("same", "/srv/a", "/inputs/a"), ("same", "/srv/b", "/inputs/b"))),
                     payload(companions=(("helper", "native", "CMAKE_SERVICE", ".w2l/bin/helper", "LD_PRELOAD"),))]:
            with self.subTest(data=data[:40]): self.assertEqual(64, self.parse(data).returncode)

    def test_health_request_is_framed_and_cannot_replace_an_installation_entry(self):
        valid = base64.b64encode("\0".join(["health-v1", "TCP", "10", "9000", "2", ""]).encode()).decode()
        result = self.parse(payload("DAEMON"), f"parse_application_health_payload '{valid}'\nprintf '%s\\n' \"$application_health_port\"")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("9000", result.stdout.strip())
        self.assertEqual(64, self.parse(payload(), f"parse_application_health_payload '{valid}'").returncode)
        self.assertEqual(64, self.parse(payload("DAEMON"), f"parse_application_health_payload '{valid[:-4]}'").returncode)

    def test_transport_namespace_and_one_shot_port_rejection(self):
        tcp = ("tcp", "TCP", "0.0.0.0", "9000", "9001", "EXTERNAL", "")
        udp = ("udp", "UDP", "0.0.0.0", "9000", "9002", "EXTERNAL", "")
        self.assertEqual(0, self.parse(payload("DAEMON", (tcp, udp))).returncode)
        self.assertEqual(64, self.parse(payload("DAEMON", (tcp, tcp))).returncode)
        self.assertEqual(64, self.parse(payload("ON_DEMAND", (udp,))).returncode)

    def test_container_mapping_checks_protocol_address_host_and_target(self):
        actual = {"9001/tcp":[{"HostIp":"0.0.0.0", "HostPort":"9000"}], "9002/udp":[{"HostIp":"0.0.0.0", "HostPort":"9000"}]}
        setup = "container_engine=fake\nfake() { printf '%s' '" + json.dumps(actual) + "'; }\n"
        for ports, success in [("0.0.0.0:9000:9001/tcp 0.0.0.0:9000:9002/udp", True), ("0.0.0.0:9000:9001/tcp", False), ("127.0.0.1:9000:9001/tcp 0.0.0.0:9000:9002/udp", False)]:
            result = execute("runtime/45-application-container-contract.sh", setup + f"container_ports=({ports})\nassert_container_ports demo")
            self.assertEqual(success, result.returncode == 0, result.stdout + result.stderr)

    def test_udp_health_requires_the_expected_reply_not_just_successful_send(self):
        for reply, expected in [(b"pong", 0), (b"wrong", 1), (None, 1)]:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
                server.bind(("127.0.0.1", 0)); server.settimeout(6)
                def respond():
                    try:
                        data, address = server.recvfrom(100)
                        if data == b"ping" and reply is not None: server.sendto(reply, address)
                    except OSError: pass
                thread = threading.Thread(target=respond); thread.start()
                result = execute("runtime/44-application-health.sh", f"application_endpoints=()\napplication_health_port={server.getsockname()[1]}\napplication_health_request=70696e67\napplication_health_response=706f6e67\napplication_udp_response")
                thread.join(7)
                self.assertFalse(thread.is_alive())
                self.assertEqual(expected, result.returncode, result.stdout + result.stderr)

    def test_container_primary_command_preserves_image_cmd_and_literal_arguments(self):
        for config, expected in [({'Entrypoint': None, 'Cmd': ['python', 'main.py']}, ['python', 'main.py']),
                                 ({'Entrypoint': ['/launcher'], 'Cmd': ['worker']}, ['/launcher', 'worker'])]:
            setup = "container_engine=fake\nfake() { printf '%s' '" + json.dumps(config) + "'; }\n"
            result = execute('runtime/45-application-container-contract.sh', setup +
                             "application_container_argv image '' '中文 空格' '$HOME' '--help'\nprintf '%s\\n' \"${application_exec[@]}\"\n")
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(expected + ['中文 空格', '$HOME', '--help'], result.stdout.splitlines())

    def test_tcp_uses_reviewed_binding_address(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.bind(("127.0.0.2", 0)); server.listen(1); server.settimeout(3)
            port = server.getsockname()[1]
            result = execute("runtime/44-application-health.sh",
                             f"application_endpoints=('service|TCP|127.0.0.2|{port}|{port}|EXTERNAL')\napplication_health_port={port}\napplication_tcp_response")
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            connection, _ = server.accept(); connection.close()

    def test_native_primary_argv_does_not_split_a_build_directory_with_spaces(self):
        result = execute('runtime/41-application-runtime.sh', """
application_relative() { :; }
application_primary_exec=('/opt/app/releases/demo/source/main unit/.venv/bin/python' '-m' 'worker')
application_argv python /opt/app/releases/demo '' '中文 空格' '$HOME'
printf '%s\\n' "${application_exec[@]}"
""")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(['/opt/app/releases/demo/source/main unit/.venv/bin/python', '-m', 'worker', '中文 空格', '$HOME'], result.stdout.splitlines())
        result = execute('runtime/41-application-runtime.sh', '''
command=unused; application_entry='主目录/main.py'; application_args=(); application_builddir='main unit'
render_application_command python /opt/app
printf '%s' "$command"
''')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('"/opt/app/current/source/main unit/.venv/bin/python" "/opt/app/current/source/主目录/main.py"', result.stdout)

if __name__ == "__main__": unittest.main()
