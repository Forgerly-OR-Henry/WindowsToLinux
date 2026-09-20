"""Run the production daemon supervisor against real child processes."""
import base64
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

RESOURCE = Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/shared/linux/sshd/execution/protocol/helper/fragments/runtime/41-application-runtime.sh'
PROGRAM = RESOURCE.read_text(encoding='utf-8').split("<<'WTL_WORKER_SUPERVISOR'\n", 1)[1].split('\nWTL_WORKER_SUPERVISOR\n', 1)[0]

class ApplicationWorkersTest(unittest.TestCase):
    def run_case(self, primary, terminate=False):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'heartbeat'
            worker = [sys.executable, '-u', '-c',
                      'import pathlib,sys,time; p=pathlib.Path(sys.argv[1]); '
                      '[(p.write_text(str(i)),time.sleep(.05)) for i in range(200)]', str(path)]
            commands = [worker, primary]
            encoded = base64.b64encode(json.dumps(commands).encode()).decode()
            process = subprocess.Popen([sys.executable, '-I', '-c', PROGRAM, 'program', encoded],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                if terminate:
                    deadline = time.monotonic() + 5
                    while not path.exists() and time.monotonic() < deadline: time.sleep(.02)
                    self.assertTrue(path.exists())
                    process.send_signal(signal.SIGTERM)
                output, error = process.communicate(timeout=8)
                self.assertNotEqual(0, process.returncode, output + error)
                previous = path.read_text() if path.exists() else None
                time.sleep(.2)
                self.assertEqual(previous, path.read_text() if path.exists() else None, 'worker survived supervisor exit')
            finally:
                if process.poll() is None: process.kill(); process.wait(timeout=5)

    def test_normal_child_exit_stops_the_remaining_worker(self):
        self.run_case([sys.executable, '-c', 'import time; time.sleep(.4)'])

    def test_startup_failure_stops_already_started_children(self):
        self.run_case(['__windowstolinux_missing_worker_executable__'])

    @unittest.skipIf(os.name == 'nt', 'Windows termination does not deliver POSIX SIGTERM')
    def test_sigterm_stops_children(self):
        self.run_case([sys.executable, '-c', 'import time; time.sleep(10)'], terminate=True)

if __name__ == '__main__': unittest.main()
