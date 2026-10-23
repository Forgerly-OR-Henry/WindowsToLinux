import json
import os
import re
import selectors
import signal
import subprocess
import sys
import time

FORMAT = "WTL_AGENT_PUBLICATION_CONTRACT_DIAGNOSTIC_1"
FRAME = "PUBLICATION_CONTRACT_DIAGNOSTIC="
# @WTL_CONTRACT_DIAGNOSTIC_BASH@
CHECKS = ("FULL_PARAMETER_VALIDATION", "MANAGED_DATA_LINKS", "UNIT_RENDER_MATCH")
PHASES = ("ARGUMENTS", "ROOT_IDENTITY", "LOCKS", "CANDIDATE", "FINGERPRINTS", "BUILD", "SNAPSHOTS",
          "CURRENT", "MAINTENANCE", *CHECKS, "STABILITY", "COMPLETE")


def strict(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError()
        result[key] = value
    return result


def environment():
    size = count = 0
    # Complete bounded enumeration precedes all diagnostic IO. Python has already started.
    for key, value in os.environ.items():
        count += 1
        length = len(os.fsencode(key)) + len(os.fsencode(value)) + 2
        size += length
        if count > 512 or length > 8192 or size > 131072:
            return False
        if key in ("BASH_ENV", "ENV", "LD_PRELOAD", "LD_LIBRARY_PATH") or key.startswith("BASH_FUNC_"):
            return False
    return True


def empty_checks():
    return [dict(id=key, status="UNKNOWN", reason="NOT_CHECKED", exitCode=None) for key in CHECKS]


def unknown(reason="UNCONFIRMED"):
    return "UNKNOWN", "CONTROL", reason, None, empty_checks()


def controls(body, exit_code, output=b""):
    # All control tokens are fixed enums/numbers; no native reject argument is accepted.
    if output or len(body) > 8192 or not body.endswith(b"\n"):
        return unknown()
    try:
        lines = body.decode("ascii").splitlines()
        if not 2 <= len(lines) <= 128:
            return unknown()
        root = None
        phase_index = 0
        passed = []
        rejected = False
        errored = False
        terminal = False
        failed_phase = None
        for index, line in enumerate(lines):
            fields = line.split("\t")
            if len(fields) != 5 or fields[0] != "WTL_CONTRACT_CONTROL_1":
                return unknown()
            kind, phase, code, pid = fields[1:]
            if phase not in PHASES or not re.fullmatch(r"0|[1-9][0-9]{0,2}", code) or int(code) > 255 or not re.fullmatch(r"[1-9][0-9]{0,9}", pid):
                return unknown()
            code = int(code)
            if index == 0:
                if (kind, phase, code) != ("BEGIN", "ARGUMENTS", 0):
                    return unknown()
                root = pid
                continue
            if terminal or phase != PHASES[phase_index] and kind != "PHASE":
                return unknown()
            if kind == "PHASE":
                if rejected or errored or pid != root or code or phase_index + 1 >= len(PHASES) or phase != PHASES[phase_index + 1]:
                    return unknown()
                # The next contract is entered only after the preceding one passed.
                if PHASES[phase_index] in CHECKS and PHASES[phase_index] not in passed:
                    return unknown()
                phase_index += 1
            elif kind == "PASS":
                if pid != root or code or rejected or errored or phase not in CHECKS or phase in passed or phase != CHECKS[len(passed)]:
                    return unknown()
                passed.append(phase)
            elif kind in ("REJECT", "ERROR"):
                if not code or phase in passed:
                    return unknown()
                if failed_phase is not None and failed_phase != phase:
                    return unknown()
                failed_phase = phase
                if kind == "REJECT":
                    if code != 64 or rejected:
                        return unknown()
                    rejected = True
                else:
                    errored = True
            elif kind == "EXIT":
                if pid != root or index != len(lines) - 1 or code != exit_code:
                    return unknown()
                terminal = True
            else:
                return unknown()
        if not terminal:
            return unknown()
        checks = empty_checks()
        for i, key in enumerate(CHECKS):
            if key in passed:
                checks[i] = dict(id=key, status="PASS", reason="MATCH", exitCode=0)
        if exit_code == 0 and not rejected and not errored and PHASES[phase_index] == "COMPLETE" and tuple(passed) == CHECKS:
            return "OBSERVED", "COMPLETE", "MATCH", 0, checks
        if rejected and exit_code == 64 and failed_phase == PHASES[phase_index]:
            if failed_phase in CHECKS:
                i = CHECKS.index(failed_phase)
                checks[i] = dict(id=failed_phase, status="FAIL", reason="REJECTED", exitCode=64)
            return "REFUSED", failed_phase, "REJECTED", 64, checks
        # A plain native failure may be caused by transport, query, dependency or drift.
        # It is not upgraded into an explicit contract rejection.
        if exit_code != 0 and errored:
            if failed_phase in CHECKS:
                i = CHECKS.index(failed_phase)
                checks[i] = dict(id=failed_phase, status="UNKNOWN", reason="UNCONFIRMED", exitCode=exit_code)
            return "UNKNOWN", PHASES[phase_index], "UNCONFIRMED", exit_code, checks
        return unknown()
    except (UnicodeError, ValueError, IndexError):
        return unknown()


def execute(args):
    read_fd, write_fd = os.pipe()
    process = None
    pipes = []
    try:
        # A dedicated inherited pipe is independent of stdout, redirects and substitutions.
        process = subprocess.Popen(["/bin/bash", "--noprofile", "--norc", "-p", "-s", "--", *args, str(write_fd)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, pass_fds=(write_fd,),
            start_new_session=True, env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C", "LC_ALL": "C"})
        os.close(write_fd)
        write_fd = None
        control = os.fdopen(read_fd, "rb", buffering=0)
        read_fd = None
        pipes = [process.stdin, process.stdout, process.stderr, control]
        body = BASH_SOURCE.encode("utf-8")
        sent = 0
        buffers = {process.stdout: bytearray(), process.stderr: bytearray(), control: bytearray()}
        deadline = time.monotonic() + 75
        with selectors.DefaultSelector() as selection:
            for pipe in pipes:
                os.set_blocking(pipe.fileno(), False)
                selection.register(pipe, selectors.EVENT_WRITE if pipe is process.stdin else selectors.EVENT_READ)
            while selection.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError()
                for key, _ in selection.select(min(1, remaining)):
                    pipe = key.fileobj
                    if pipe is process.stdin:
                        try:
                            sent += os.write(pipe.fileno(), body[sent:sent + 8192])
                        except BrokenPipeError:
                            selection.unregister(pipe)
                            pipe.close()
                            continue
                        if sent == len(body):
                            selection.unregister(pipe)
                            pipe.close()
                    else:
                        block = os.read(pipe.fileno(), 4096)
                        if not block:
                            selection.unregister(pipe)
                            pipe.close()
                            continue
                        buffers[pipe].extend(block)
                        if len(buffers[control]) > 8192 or sum(map(len, buffers.values())) > 32768:
                            raise ValueError()
            process.wait(timeout=max(0.01, deadline - time.monotonic()))
        # Native stderr is bounded and discarded, never interpreted or copied into the safe receipt.
        return controls(bytes(buffers[control]), process.returncode, bytes(buffers[process.stdout]))
    except BaseException:
        if process is not None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
        return unknown("LIMIT")
    finally:
        for pipe in pipes:
            if not pipe.closed:
                pipe.close()
        for fd in (read_fd, write_fd):
            if fd is not None:
                os.close(fd)


def emit(envelope, result):
    status, phase, reason, exit_code, checks = result
    value = dict(format=FORMAT, sourceVersion=envelope["sourceVersion"], action=envelope["action"],
                 binding=envelope["binding"], status=status, phase=phase, reason=reason, exitCode=exit_code, checks=checks)
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if len(encoded.encode("utf-8")) + len(FRAME) + 1 > 16384:
        raise ValueError()
    print(FRAME + encoded, flush=True)


def main():
    envelope = json.loads(sys.argv[1], object_pairs_hook=strict)
    if set(envelope) != {"binding", "action", "sourceVersion"}:
        raise ValueError()
    if len(sys.argv) != 3 or sys.argv[2] != "SDK_DEV_NULL_SENTINELS_REMOVED_CONTRACT_DIAGNOSTIC_V1" or not environment():
        emit(envelope, ("REFUSED", "ENVIRONMENT", "REJECTED", None, empty_checks()))
        return
    if os.geteuid() != 0:
        emit(envelope, ("REFUSED", "ROOT_IDENTITY", "REJECTED", None, empty_checks()))
        return
    binding = envelope["binding"]["continuation"]
    original, current = binding["original"], binding["current"]
    args = [original["task"], envelope["action"].replace("-", ""), original["execution"], original["unit"],
            original["ownerSha256"], original["candidate"], original["releaseSha256"], original["planSha256"],
            current["candidateOwnerSha256"], current["candidateTaskSha256"], current["maintenanceSha256"],
            current["releaseOwnerSha256"], current["parametersSha256"], current["unitSha256"]]
    emit(envelope, execute(args))


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        print("PUBLICATION_CONTRACT_DIAGNOSTIC_FAILURE=UNKNOWN", flush=True)
        sys.exit(70)
