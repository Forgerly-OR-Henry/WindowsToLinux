"""Exercise task ownership and candidate-query behavior with inert helper collaborators."""

import pathlib
import shutil
import subprocess
import tempfile
import unittest
from compatibility_resources import read_fragment

SOURCE = pathlib.Path(__file__).resolve().parents[2] / "main/resources/gold/debug/windowstolinux/shared/linux/sshd"
FRAGMENT = read_fragment(SOURCE / "execution/protocol/helper/fragments/workspace/20-candidate-workspace.sh")
BASH = shutil.which("bash") or "E:/Program/Git/bin/bash.exe"


class AgentCandidateBoundaryTest(unittest.TestCase):
    def run_case(self, setup, action):
        if not pathlib.Path(BASH).is_file():
            self.skipTest("Bash unavailable")
        with tempfile.TemporaryDirectory() as root:
            script = (
                """set -eu
export PATH=/usr/bin:/bin:$PATH
work_root=work; mkdir work
reject() { printf 'REJECT=%s\\n' "$1"; exit 41; }
require_app() { [[ "$1" =~ ^[a-z][a-z0-9-]*$ ]] || reject app; }
require_candidate() { [ "$2" = "$1-0123456789abcdef" ] || reject candidate; }
candidate_root() { printf 'work/%s' "$1"; }
build_unit_name() { printf 'windowstolinux-build-%s.service' "$1"; }
assert_root_owned_directory() { [ -d "$1" ] && [ ! -L "$1" ] || reject directory; }
assert_root_owned_regular() { [ -f "$1" ] && [ ! -L "$1" ] || reject file; }
assert_candidate_for_deployer() { assert_root_owned_directory "$1"; }
systemctl() { printf 'inactive\\n'; }
"""
                + FRAGMENT
                + "\ncleanup_candidate() { printf 'CLEANED=%s\\n' \"$2\"; }\n"
                + setup
                + "\n"
                + action
            )
            return subprocess.run(
                [BASH, "--noprofile", "--norc", "-s"],
                input=script,
                text=True,
                cwd=root,
                capture_output=True,
                timeout=10,
            )

    def test_absent_candidate_is_a_read_only_observation(self):
        result = self.run_case("", "query_task_candidate app app-0123456789abcdef task")
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn("CANDIDATE_STATE=absent", result.stdout)
        self.assertIn("BUILD_ACTIVE=no", result.stdout)
        self.assertNotIn("CLEANED=", result.stdout)

    def test_foreign_and_unbound_markers_cannot_be_queried_or_cleaned(self):
        for setup in [
            "mkdir work/app-0123456789abcdef",
            "mkdir work/app-0123456789abcdef; echo other > work/app-0123456789abcdef/.agent-task",
        ]:
            for action in ["query_task_candidate", "cleanup_task_candidate"]:
                result = self.run_case(setup, action + " app app-0123456789abcdef task")
                self.assertEqual(41, result.returncode, result.stderr + result.stdout)
                self.assertNotIn("CLEANED=", result.stdout)

    def test_only_owned_candidate_reaches_existing_cleanup(self):
        setup = "mkdir work/app-0123456789abcdef; echo task > work/app-0123456789abcdef/.agent-task"
        result = self.run_case(setup, "cleanup_task_candidate app app-0123456789abcdef task")
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn("CLEANED=app-0123456789abcdef", result.stdout)
        result = self.run_case(setup, "cleanup_task_candidate app app-0123456789abcdef ../escape")
        self.assertEqual(41, result.returncode, result.stderr + result.stdout)


if __name__ == "__main__":
    unittest.main()
