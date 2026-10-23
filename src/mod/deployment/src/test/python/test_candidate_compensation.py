"""Focused compensation state/proof tests; OS ownership and flock are simulated on Windows."""
import copy
import importlib.util
import json
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import patch


SOURCE = pathlib.Path(__file__).resolve().parents[2] / "main/resources/gold/debug/windowstolinux/mod/deployment/linux/cleanup/candidate_compensation.py"
spec = importlib.util.spec_from_file_location("candidate_compensation", SOURCE)
c = importlib.util.module_from_spec(spec)
try:
    import fcntl
except ImportError:
    fcntl = types.SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=lambda *args: None)
with patch.dict(sys.modules, {"fcntl": fcntl}):
    spec.loader.exec_module(c)


def binding():
    return {"formatVersion": 1, "taskId": "c9cc0696-0ae8-4950-b48b-355fdc643f66",
            "operationId": "18b632d4-8bca-4c64-a311-a65f3cb0131f", "applicationId": "wtl-test-app",
            "unitId": "wtl-test-app-backend", "candidateId": "wtl-test-app-backend-" + "a" * 16,
            "planSha256": "b" * 64, "ownerManifestSha256": "c" * 64, "archiveSha256": "a" * 64,
            "helperSha256": "d" * 64, "layout": {"formatVersion": 1, "remote": {
                "helperDirectory": "/usr/local/lib/windowstolinux", "applicationsRoot": "/opt/windowstolinux/apps",
                "stateRoot": "/var/lib/windowstolinux", "configurationRoot": "/etc/opt/windowstolinux/apps",
                "dataRoot": "/var/opt/windowstolinux/apps"}}}


def request(mode="observe"):
    return {"mode": mode, "binding": binding(), "expectedObservationSha256": "1" * 64 if mode == "execute" else "",
            "originalExecutionCreatedAtEpochMillis": 1792727820200}


def state(present=True, sha="1" * 64, protected="2" * 64):
    return {"present": present, "sha": sha, "processSha": "3" * 64, "protected": protected, "systemProcessExclusion": {}}


class CompensationStateTest(unittest.TestCase):
    def setUp(self):
        self.saved = None
        self.claim = None
        self.claim_writes = []
        self.writes = []
        self.patches = []
        for target, value in (("geteuid", lambda: 0), ("open", lambda *a: 101), ("close", lambda *a: None), ("fsync", lambda *a: None),
                              ("fstat", lambda *a: types.SimpleNamespace(st_dev=1, st_ino=2, st_nlink=1)),
                              ("lstat", lambda *a: types.SimpleNamespace(st_dev=1, st_ino=2, st_nlink=1))):
            p = patch.object(c.os, target, value, create=True)
            p.start()
            self.patches.append(p)
        for name in ("O_NOFOLLOW", "O_DIRECTORY"):
            if not hasattr(c.os, name):
                p = patch.object(c.os, name, 0, create=True)
                p.start()
                self.patches.append(p)
        self.addCleanup(lambda: [p.stop() for p in reversed(self.patches)])
        self.addCleanup(setattr, c, "DEADLINE", None)
        self.read = patch.object(c, "read_receipt", side_effect=lambda b: copy.deepcopy(self.saved)).start()
        self.read_claim = patch.object(c, "read_claim", side_effect=lambda *a: copy.deepcopy(self.claim)).start()
        patch.object(c.os.path, "lexists", side_effect=lambda p: p == c.claim_path(binding()) and self.claim is not None).start()
        self.observe = patch.object(c, "original_state", return_value=state()).start()
        self.secure = patch.object(c, "secure_directory").start()
        self.owned = patch.object(c, "owned").start()
        self.invoke = patch.object(c, "invoke_cleanup").start()
        patch.object(c.fcntl, "flock", lambda *a: None).start()
        self.write = patch.object(c, "write_receipt", side_effect=self.save).start()
        self.addCleanup(patch.stopall)

    def save(self, path, value, first=False):
        if path == c.claim_path(binding()):
            if self.claim is not None:
                raise FileExistsError()
            self.claim = copy.deepcopy(value)
            self.claim_writes.append(self.claim)
            return
        if first and self.saved is not None:
            raise FileExistsError()
        self.saved = copy.deepcopy(value)
        self.writes.append(self.saved["state"])

    def test_observe_has_no_receipt_directory_or_helper_writes(self):
        result = c.operate(request())
        self.assertEqual("OBSERVED", result["status"])
        self.assertTrue(result["quiescent"])
        self.assertEqual([], self.writes)
        self.secure.assert_not_called()
        self.invoke.assert_not_called()

    def test_absence_is_an_observation_and_never_execution_success(self):
        self.observe.return_value = state(False)
        self.assertEqual("ABSENT", c.operate(request())["status"])
        self.assertEqual("REFUSED", c.operate(request("execute"))["status"])
        self.invoke.assert_not_called()

    def test_success_persists_started_before_single_call_and_terminal_receipt(self):
        self.observe.side_effect = [state(), state(), state(False, "4" * 64)]
        self.invoke.side_effect = lambda b: self.assertEqual("STARTED", self.saved["state"])
        result = c.operate(request("execute"))
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertFalse(result["candidatePresent"])
        self.assertEqual(["STARTED", "SUCCEEDED"], self.writes)
        self.assertEqual(1, len(self.claim_writes))
        self.assertRegex(result["receiptSha256"], "^[0-9a-f]{64}$")
        self.invoke.assert_called_once()

    def test_complete_existing_receipt_is_read_only_even_for_execute(self):
        self.saved = {"formatVersion": 2, "binding": binding(), "bindingSha256": c.digest(c.canonical(binding())),
                      "state": "SUCCEEDED", "observationSha256": "1" * 64, "protectedSha256": "2" * 64,
                      "originalExecutionCreatedAtEpochMillis": request()["originalExecutionCreatedAtEpochMillis"]}
        self.claim = {"observationSha256": "1" * 64}
        self.observe.return_value = state(False)
        for mode in ("receipt", "execute"):
            self.assertEqual("SUCCEEDED", c.operate(request(mode))["status"])
        self.invoke.assert_not_called()
        self.assertEqual([], self.writes)

    def test_started_or_unknown_receipt_never_replays_after_lost_response(self):
        for saved in ("STARTED", "UNKNOWN"):
            self.saved = {"state": saved}
            self.observe.return_value = state(False)
            for mode in ("receipt", "execute"):
                self.assertEqual("UNKNOWN", c.operate(request(mode))["status"])
        self.invoke.assert_not_called()
        self.assertEqual([], self.writes)

    def test_preapproval_observation_change_refuses_before_started(self):
        self.observe.return_value = state(sha="5" * 64)
        self.assertEqual("REFUSED", c.operate(request("execute"))["status"])
        self.invoke.assert_not_called()
        self.assertEqual([], self.writes)

    def test_under_lock_change_refuses_before_started(self):
        self.observe.side_effect = [state(), state(sha="5" * 64)]
        result = c.operate(request("execute"))
        self.assertEqual("observation-changed", result["reason"])
        self.invoke.assert_not_called()
        self.assertEqual([], self.writes)

    def test_started_failure_is_unknown_with_no_replay(self):
        self.invoke.side_effect = c.Refusal("new-cleanup-unconfirmed")
        result = c.operate(request("execute"))
        self.assertEqual("UNKNOWN", result["status"])
        self.assertEqual(["STARTED", "UNKNOWN"], self.writes)
        self.observe.return_value = state(False)
        self.observe.side_effect = None
        self.assertEqual("UNKNOWN", c.operate(request("receipt"))["status"])
        self.assertEqual("UNKNOWN", c.operate(request("execute"))["status"])
        self.invoke.assert_called_once()

    def test_postcondition_failure_does_not_synthesize_success_from_absence(self):
        self.observe.side_effect = [state(), state(), state(False, protected="6" * 64)]
        result = c.operate(request("execute"))
        self.assertEqual("UNKNOWN", result["status"])
        self.assertEqual("cleanup-postcondition", result["reason"])
        self.assertEqual("UNKNOWN", self.saved["state"])

    def test_unreadable_process_state_refuses_without_raw_error_or_writes(self):
        self.observe.side_effect = PermissionError("password=must-not-escape")
        result = c.operate(request("execute"))
        self.assertEqual("REFUSED", result["status"])
        self.assertEqual("observation-unavailable", result["reason"])
        self.assertNotIn("password", json.dumps(result))
        self.invoke.assert_not_called()

    def test_receipt_absent_is_query_only(self):
        self.assertEqual("UNKNOWN", c.operate(request("receipt"))["status"])
        self.observe.assert_not_called()
        self.secure.assert_not_called()
        self.invoke.assert_not_called()

    def test_receipt_query_failure_preserves_unknown_without_writing(self):
        self.saved = {"state": "STARTED"}
        self.observe.side_effect = c.Refusal("other-ssh-execution-chain")
        self.assertEqual("UNKNOWN", c.operate(request("receipt"))["status"])
        self.assertEqual([], self.writes)
        self.invoke.assert_not_called()

    def test_claim_before_receipt_crash_is_unknown_and_never_dispatches(self):
        self.claim = {"observationSha256": "1" * 64}
        for mode in ("observe", "execute", "receipt"):
            self.assertEqual("UNKNOWN", c.operate(request(mode))["status"])
        self.assertEqual([], self.writes)
        self.assertEqual([], self.claim_writes)
        self.invoke.assert_not_called()

    def test_claim_persistence_failure_never_calls_helper_or_uses_another_operation(self):
        def uncertain(path, value, first=False):
            self.assertEqual(c.claim_path(binding()), path)
            self.claim = copy.deepcopy(value)
            raise OSError("private provider detail")
        self.write.side_effect = uncertain
        self.assertEqual("UNKNOWN", c.operate(request("execute"))["status"])
        self.assertEqual("UNKNOWN", c.operate(request("execute"))["status"])
        self.invoke.assert_not_called()

    def test_foreign_or_unreadable_claim_refuses_without_writes(self):
        self.claim = {"binding": {"operationId": "another-operation"}}
        with patch.object(c.os.path, "lexists", return_value=True):
            self.read_claim.side_effect = c.Refusal("candidate-claim-conflict")
            for mode in ("observe", "execute", "receipt"):
                result = c.operate(request(mode))
                self.assertEqual("UNKNOWN", result["status"])
                self.assertEqual("candidate-claim-conflict", result["reason"])
        self.invoke.assert_not_called()
        self.assertEqual([], self.writes)


class ProcessProofTest(unittest.TestCase):
    def fixture(self, name="sshd", args=b"sshd: listener [listener]\0", uid=0, fd="/dev/null"):
        before = {"pid": 200, "state": "S", "parent": 1, "start": 99}
        patches = [patch.object(c, "own_chain", return_value={}),
                   patch.object(c, "sender_closed"), patch.object(c, "process_stat", return_value=before),
                   patch.object(c.os, "stat", return_value=types.SimpleNamespace(st_uid=uid)),
                   patch.object(c.os, "listdir", side_effect=lambda p: ["200"] if p == "/proc" else ["0"]),
                   patch.object(c, "bounded_read", side_effect=lambda p, *a: args if p.endswith("cmdline") else b"0::/\n"),
                   patch.object(c.os, "readlink", side_effect=lambda p: "/usr/bin/" + name if p.endswith("exe") else fd if "/fd/" in p else "/")]
        for p in patches:
            p.start()
        self.addCleanup(patch.stopall)
        c.DEADLINE = None
        return before

    def run_proof(self):
        return c.quiescence(binding(), "/var/lib/windowstolinux/work/" + binding()["candidateId"], ["/dev/loop7"], 0)

    def test_listener_is_not_confused_with_an_executable_old_session(self):
        self.fixture()
        process_sha, exclusion = self.run_proof()
        self.assertRegex(process_sha, "^[0-9a-f]{64}$")
        self.assertEqual({}, exclusion)

    def test_delayed_ssh_session_and_stdin_shell_are_refused_without_candidate_text(self):
        for name, args in (("sshd", b"sshd: root@notty\0"), ("sh", b"sh\0")):
            with self.subTest(name=name):
                self.fixture(name, args)
                with self.assertRaises(c.Refusal):
                    self.run_proof()
                patch.stopall()

    def test_orphan_cleanup_command_is_refused_without_parent_or_candidate_text(self):
        self.fixture("umount", b"umount\0/dev/loop7\0")
        with self.assertRaisesRegex(c.Refusal, "other-root-execution-chain"):
            self.run_proof()

    def test_orphan_transfer_server_cannot_keep_processing_buffered_old_input(self):
        for name in ("sftp-server", "internal-sftp", "ssh", "sftp", "scp", "rsync"):
            with self.subTest(name=name):
                self.fixture(name, (name + "\0").encode())
                with self.assertRaisesRegex(c.Refusal, "other-root-execution-chain"):
                    self.run_proof()
                patch.stopall()

    def test_candidate_loop_fd_from_other_user_is_refused(self):
        self.fixture("worker", b"worker\0", uid=1234, fd="/dev/loop7")
        with self.assertRaisesRegex(c.Refusal, "candidate-descriptor-active"):
            self.run_proof()

    def test_recognized_system_waiter_still_cannot_hold_candidate_device(self):
        self.fixture("python3.12", b"fixed-system-waiter\0", fd="/dev/loop7")
        with patch.object(c, "system_shutdown_waiter", return_value={"pid": 200}):
            with self.assertRaisesRegex(c.Refusal, "candidate-descriptor-active"):
                self.run_proof()

    def test_process_reuse_or_change_is_not_treated_as_quiescent(self):
        before = self.fixture("worker", b"worker\0", uid=1234)
        c.process_stat.side_effect = [before, {**before, "start": 100}]
        with self.assertRaisesRegex(c.Refusal, "process-changed"):
            self.run_proof()

    def test_vanished_parent_is_refused_instead_of_hiding_a_new_child(self):
        self.fixture("worker", b"worker\0", uid=1234)
        c.process_stat.side_effect = FileNotFoundError()
        with self.assertRaisesRegex(c.Refusal, "process-vanished-during-observation"):
            self.run_proof()

    def test_new_child_after_initial_process_list_is_refused(self):
        self.fixture("worker", b"worker\0", uid=1234)
        def listing(path):
            if path != "/proc":
                return ["0"]
            listing.calls += 1
            return ["200"] if listing.calls == 1 else ["200", "201"]
        listing.calls = 0
        c.os.listdir.side_effect = listing
        with self.assertRaisesRegex(c.Refusal, "process-set-changed"):
            self.run_proof()

    def test_other_ssh_transport_is_refused_even_before_a_shell_starts(self):
        chain = {100: {"pid": 100}}
        with patch.object(c, "bounded_read", side_effect=lambda p, *a: b"sshd\n" if p.endswith("comm") else
                          b"header\n0: 00000000:0016 00000000:1111 01 0 0 0 0 0 1000\n1: 00000000:0016 00000000:2222 01 0 0 0 0 0 2000\n"), \
             patch.object(c.os, "listdir", return_value=["0"]), \
             patch.object(c.os, "readlink", return_value="socket:[1000]"):
            with self.assertRaisesRegex(c.Refusal, "other-ssh-transport"):
                c.sender_closed(chain)


class SystemServiceProofTest(unittest.TestCase):
    def setUp(self):
        c.DEADLINE = None
        self.script = "/usr/share/unattended-upgrades/unattended-upgrade-shutdown"
        self.argv = b"/usr/bin/python3\0" + self.script.encode() + b"\0--wait-for-signal\0"
        self.before = {"pid": 886, "state": "S", "parent": 1, "start": 925}
        self.facts = {"FragmentPath": "/usr/lib/systemd/system/unattended-upgrades.service", "DropInPaths": "",
                      "MainPID": "886", "ActiveState": "active", "SubState": "running", "ControlGroup": "/system.slice/unattended-upgrades.service",
                      "InvocationID": "4" * 32, "ExecStart": "{ path=" + self.script + " ; argv[]=" + self.script + " --wait-for-signal ; ignore_errors=no ; pid=886 ; status=0/0 }", "Type": "simple"}
        self.source = b"script"
        self.image = b"old mapped image"
        real_digest = c.digest
        def hashed(value):
            if value == b"script":
                return "8fbf4e278c4ad457e82cc05586631b1a0550dd9d10892a70487ef74c623066e0"
            if value == b"unit":
                return "642331d3861bcc5e41342b9b93ad96b1852447d5b10533b9b2988531cfd2860a"
            return real_digest(value)
        def reading(path, *a):
            return {"/proc/886/comm": b"unattended-upgr\n", "/proc/stat": b"cpu 0\nbtime 1792689091\n",
                    "/proc/886/cgroup": b"0::/system.slice/unattended-upgrades.service\n", self.script: self.source,
                    self.facts["FragmentPath"]: b"unit", "/proc/886/exe": self.image, "/proc/886/cmdline": self.argv}[path]
        self.read = patch.object(c, "bounded_read", side_effect=reading).start()
        patch.object(c, "digest", side_effect=hashed).start()
        patch.object(c.os, "sysconf", return_value=100, create=True).start()
        patch.object(c.os.path, "realpath", side_effect=lambda p: p).start()
        patch.object(c.os, "stat", return_value=types.SimpleNamespace(st_mode=0o100755, st_uid=0, st_nlink=0, st_dev=1, st_ino=2954)).start()
        patch.object(c.os, "readlink", return_value="/usr/bin/python3.12 (deleted)").start()
        self.identity = patch.object(c, "process_stat", return_value=self.before).start()
        patch.object(c, "parents").start()
        self.owned = patch.object(c, "owned").start()
        patch.object(c, "command", side_effect=lambda *a: "\n".join(k + "=" + v for k, v in self.facts.items())).start()
        self.addCleanup(patch.stopall)

    def proof(self):
        return c.system_shutdown_waiter(886, self.before, self.argv, "/usr/bin/python3.12 (deleted)",
                                        1792727820200, "11111111-2222-3333-4444-555555555555")

    def test_exact_prior_system_instance_uses_actual_deleted_image_and_bound_public_role(self):
        result = self.proof()
        self.assertEqual("unattended-upgrade-shutdown-v1", result["role"])
        self.assertEqual(1792689100250, result["startedAtEpochMillis"])
        self.assertEqual(c.digest(self.image), result["executableSha256"])
        self.assertEqual(2954, result["executableInode"])
        self.assertEqual(14, len(result))
        self.assertNotIn("/usr/bin/python3.12", [call.args[0] for call in self.read.call_args_list])

    def test_changed_pid_start_unit_dropin_or_program_never_gets_a_name_exception(self):
        for key, bad in (("MainPID", "887"), ("DropInPaths", "/etc/unit.conf"), ("InvocationID", "bad"),
                         ("ExecStart", "{ path=/bin/sh ; argv[]=/bin/sh ; ignore_errors=no ; pid=886 ; status=0/0 }"),
                         ("ControlGroup", "/other.service")):
            with self.subTest(key=key):
                previous = self.facts[key]
                self.facts[key] = bad
                with self.assertRaises(c.Refusal):
                    self.proof()
                self.facts[key] = previous
        self.source = b"different script"
        with self.assertRaisesRegex(c.Refusal, "system-service-source"):
            self.proof()

    def test_new_or_reused_process_or_unowned_source_is_refused(self):
        with self.assertRaisesRegex(c.Refusal, "system-service-start"):
            c.system_shutdown_waiter(886, self.before, self.argv, "/usr/bin/python3.12 (deleted)", 1792689101000, "boot")
        self.identity.return_value = {**self.before, "start": 926}
        with self.assertRaisesRegex(c.Refusal, "system-service-changed"):
            self.proof()
        self.identity.return_value = self.before
        self.owned.side_effect = c.Refusal("owned-path")
        with self.assertRaisesRegex(c.Refusal, "owned-path"):
            self.proof()


class CgroupProofTest(unittest.TestCase):
    def setUp(self):
        c.DEADLINE = None

    def test_missing_or_unreadable_cgroup_never_means_idle(self):
        with patch.object(c.os.path, "isdir", return_value=False):
            with self.assertRaisesRegex(c.Refusal, "cgroup-unavailable"):
                c.cgroup_idle("/group")
        def unreadable(root, **options):
            options["onerror"](PermissionError())
            return iter(())
        with patch.object(c.os.path, "isdir", return_value=True), \
             patch.object(c.os.path, "islink", return_value=False), \
             patch.object(c.os, "walk", side_effect=unreadable):
            with self.assertRaisesRegex(c.Refusal, "cgroup-unavailable"):
                c.cgroup_idle("/group")

    def test_symlink_child_or_missing_members_file_refuses(self):
        with patch.object(c.os.path, "isdir", return_value=True), \
             patch.object(c.os.path, "islink", side_effect=lambda p: p.replace("\\", "/") == "/group/child"), \
             patch.object(c.os, "walk", return_value=iter([("/group", ["child"], ["cgroup.procs"])])):
            with self.assertRaisesRegex(c.Refusal, "cgroup-link"):
                c.cgroup_idle("/group")
        with patch.object(c.os.path, "isdir", return_value=True), \
             patch.object(c.os.path, "islink", return_value=False), \
             patch.object(c.os, "walk", return_value=iter([("/group", [], [])])):
            with self.assertRaisesRegex(c.Refusal, "cgroup-unavailable"):
                c.cgroup_idle("/group")

    def test_every_descendant_members_file_must_be_empty(self):
        for content in (b"", b"200\n"):
            with patch.object(c.os.path, "isdir", return_value=True), \
                 patch.object(c.os.path, "islink", return_value=False), \
                 patch.object(c.os, "walk", return_value=iter([("/group", ["child"], ["cgroup.procs"]),
                                                             ("/group/child", [], ["cgroup.procs"])])), \
                 patch.object(c, "bounded_read", side_effect=[b"", content]):
                if content:
                    with self.assertRaisesRegex(c.Refusal, "build-cgroup-active"):
                        c.cgroup_idle("/group")
                else:
                    c.cgroup_idle("/group")


class BindingTest(unittest.TestCase):
    def test_invalid_task_candidate_layout_and_expected_digest_refuse(self):
        variants = []
        for field, value in (("taskId", "foreign"), ("candidateId", "foreign"), ("formatVersion", True)):
            value_request = request()
            value_request["binding"][field] = value
            variants.append(value_request)
        value_request = request()
        value_request["binding"]["layout"]["remote"]["stateRoot"] = "/var/../tmp"
        variants.append(value_request)
        value_request = request("execute")
        value_request["expectedObservationSha256"] = ""
        variants.append(value_request)
        for value in variants:
            with self.subTest(value=value), self.assertRaises((c.Refusal, ValueError)):
                c.validate(value)

    def test_binding_canonical_digest_is_order_independent(self):
        b = binding()
        self.assertEqual(c.digest(c.canonical(b)), c.digest(c.canonical(dict(reversed(list(b.items()))))))


class ClaimProofTest(unittest.TestCase):
    def value(self):
        return {"formatVersion": 1, "binding": binding(), "bindingSha256": c.digest(c.canonical(binding())),
                "state": "CLAIMED", "originalExecutionCreatedAtEpochMillis": request()["originalExecutionCreatedAtEpochMillis"],
                "observationSha256": "1" * 64}

    def test_claim_is_canonical_and_bound_to_one_original_operation_and_execution(self):
        value = self.value()
        with patch.object(c, "existing_parents"), patch.object(c, "parents"), patch.object(c, "owned"), \
             patch.object(c.os.path, "lexists", return_value=True), \
             patch.object(c, "bounded_read", return_value=c.canonical(value) + b"\n") as source:
            self.assertEqual(value, c.read_claim(binding(), request()["originalExecutionCreatedAtEpochMillis"]))
            for mutation in ("OPERATION", "TASK", "EPOCH", "STATE", "EXTRA", "NONCANONICAL", "PARTIAL"):
                changed = copy.deepcopy(value)
                if mutation == "OPERATION":
                    changed["binding"]["operationId"] = "00000000-0000-0000-0000-000000000000"
                elif mutation == "TASK":
                    changed["binding"]["taskId"] = "00000000-0000-0000-0000-000000000000"
                elif mutation == "EPOCH":
                    changed["originalExecutionCreatedAtEpochMillis"] += 1
                elif mutation == "STATE":
                    changed["state"] = "RELEASED"
                elif mutation == "EXTRA":
                    changed["allowRetry"] = True
                raw = c.canonical(changed) + b"\n"
                if mutation == "NONCANONICAL":
                    raw += b" "
                if mutation == "PARTIAL":
                    raw = raw[:10]
                source.return_value = raw
                with self.subTest(mutation=mutation), self.assertRaises((c.Refusal, ValueError)):
                    c.read_claim(binding(), request()["originalExecutionCreatedAtEpochMillis"])

    def test_exclusive_claim_is_synced_and_cannot_be_replaced_after_uncertain_write(self):
        c.DEADLINE = None
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(c.os, "O_NOFOLLOW", getattr(c.os, "O_NOFOLLOW", 0), create=True), \
             patch.object(c, "sync_directory") as synced:
            path = str(pathlib.Path(directory) / "candidate.json")
            c.write_receipt(path, self.value(), True)
            synced.assert_called_once_with(directory)
            original = pathlib.Path(path).read_bytes()
            with self.assertRaises(FileExistsError):
                c.write_receipt(path, {"another": "operation"}, True)
            self.assertEqual(original, pathlib.Path(path).read_bytes())
            partial = str(pathlib.Path(directory) / "uncertain.json")
            synced.reset_mock()
            with patch.object(c.os, "fsync", side_effect=OSError("sync failed")):
                with self.assertRaises(OSError):
                    c.write_receipt(partial, self.value(), True)
            synced.assert_not_called()
            with self.assertRaises(FileExistsError):
                c.write_receipt(partial, self.value(), True)


class OwnedProcessTest(unittest.TestCase):
    def setUp(self):
        if not hasattr(c.signal, "SIGKILL"):
            patcher = patch.object(c.signal, "SIGKILL", 9, create=True)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_deadline_after_spawn_terminates_and_reaps_only_owned_group(self):
        child = unittest.mock.Mock(pid=321, returncode=0)
        child.stdout.fileno.return_value = 10
        child.stderr.fileno.return_value = 11
        child.communicate.return_value = (b"", b"")
        selector = unittest.mock.MagicMock()
        selector.__enter__.return_value = selector
        selector.get_map.return_value = {10: object()}
        with patch.object(c, "remaining", side_effect=[100, c.Refusal("request-deadline")]), \
             patch.object(c.subprocess, "Popen", return_value=child) as spawn, \
             patch.object(c.selectors, "DefaultSelector", return_value=selector), \
             patch.object(c.os, "set_blocking"), \
             patch.object(c, "process_stat", return_value={"start": 1, "group": 321, "session": 321}), \
             patch.object(c, "stop_owned_group") as terminate:
            with self.assertRaisesRegex(c.Refusal, "request-deadline"):
                c.invoke_cleanup(binding())
            self.assertTrue(spawn.call_args.kwargs["start_new_session"])
            terminate.assert_called_once()
            self.assertIs(child, terminate.call_args.args[0])
            child.wait.assert_called_once_with(timeout=2)
            child.stdout.close.assert_called_once()
            child.stderr.close.assert_called_once()

    def test_exited_leader_with_pipe_free_descendant_requires_group_kill(self):
        child = unittest.mock.Mock(pid=321)
        member = [(333, 3)]
        with patch.object(c, "group_members", side_effect=[member, member, []]), \
             patch.object(c.time, "monotonic", side_effect=[0, 2, 2, 5]), \
             patch.object(c.os, "killpg", create=True) as terminate:
            c.stop_owned_group(child, {"start": 1})
            self.assertEqual([unittest.mock.call(321, c.signal.SIGTERM), unittest.mock.call(321, c.signal.SIGKILL)],
                             terminate.call_args_list)

    def test_reused_leader_identity_cannot_authorize_a_signal(self):
        child = unittest.mock.Mock(pid=321)
        with patch.object(c, "process_stat", return_value={"start": 99, "group": 321, "session": 321}), \
             patch.object(c.os, "killpg", create=True) as terminate:
            with self.assertRaisesRegex(c.Refusal, "owned-process-identity"):
                c.stop_owned_group(child, {"start": 1})
            terminate.assert_not_called()


class DurabilityTest(unittest.TestCase):
    def test_new_directory_entry_is_synced_before_it_is_accepted(self):
        with patch.object(c, "parents"), patch.object(c, "owned", return_value={}), \
             patch.object(c.os, "mkdir") as create, patch.object(c, "sync_directory") as sync:
            c.secure_directory("/state/task/op", True)
            create.assert_called_once_with("/state/task/op", 0o700)
            sync.assert_called_once_with("/state/task")

    def test_directory_sync_failure_propagates_before_any_receipt(self):
        with patch.object(c, "parents"), patch.object(c, "owned", return_value={}), \
             patch.object(c.os, "mkdir"), patch.object(c, "sync_directory", side_effect=OSError()), \
             patch.object(c, "write_receipt") as write, patch.object(c, "invoke_cleanup") as invoke:
            with self.assertRaises(OSError):
                c.secure_directory("/state/task/op", True)
            write.assert_not_called()
            invoke.assert_not_called()


if __name__ == "__main__":
    unittest.main()
