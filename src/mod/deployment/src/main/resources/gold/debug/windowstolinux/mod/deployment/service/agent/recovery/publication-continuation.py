import hashlib
import json
import os
import re
import selectors
import signal
import subprocess
import sys
import time

FORMAT = "WTL_AGENT_PUBLICATION_CONTINUATION_1"
FRAME = "PUBLICATION_CONTINUATION="
# @WTL_CONTINUATION_BASH@
CHECKS = {"ENVIRONMENT", "ARGUMENTS", "ROOT_IDENTITY", "LOCKS", "JOB", "CANDIDATE", "FINGERPRINTS", "BUILD", "SNAPSHOTS", "MAINTENANCE", "CURRENT", "NATIVE_CONTRACT", "FULL_PARAMETER_VALIDATION", "MANAGED_DATA_LINKS", "UNIT_RENDER_MATCH", "ROLLBACK", "POSTCONDITIONS", "COMPLETE", "INTERNAL", "LIMIT"}

def strict(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError()
        value[key] = item
    return value

def environment():
    count = size = 0
    for key, value in os.environ.items():
        count += 1
        record = len(os.fsencode(key)) + len(os.fsencode(value)) + 2
        size += record
        if count > 512 or record > 8192 or size > 131072:
            return False
        if key in ("BASH_ENV", "ENV", "LD_PRELOAD", "LD_LIBRARY_PATH") or key.startswith("BASH_FUNC_"):
            return False
    return True

def receipt(envelope, status, check, started):
    ok = status == "ROLLED_BACK"
    value = dict(format=FORMAT, sourceVersion=envelope["sourceVersion"], action=envelope["action"], binding=envelope["binding"],
                 status=status, check=check, mutationStarted=started, currentAbsent=True if ok else None,
                 unitAbsent=True if ok else None, releaseAbsent=True if ok else None,
                 maintenanceAbsent=True if ok else None, candidateRetained=True if ok else None)
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if len(encoded.encode()) > 16000:
        raise ValueError()
    print(FRAME + encoded, flush=True)

def execute(args):
    # Drain stdout/stderr while feeding the full fixed body, including early guard rejection.
    process = subprocess.Popen(["/bin/bash", "--noprofile", "--norc", "-p", "-s", "--", *args],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True,
        env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C", "LC_ALL": "C"})
    sent = 0
    body = BASH_SOURCE.encode("utf-8")
    output = bytearray()
    errors = bytearray()
    deadline = time.monotonic() + 165
    try:
        with selectors.DefaultSelector() as selection:
            for pipe, events in ((process.stdin, selectors.EVENT_WRITE), (process.stdout, selectors.EVENT_READ), (process.stderr, selectors.EVENT_READ)):
                os.set_blocking(pipe.fileno(), False)
                selection.register(pipe, events)
            while selection.get_map():
                if time.monotonic() >= deadline:
                    raise TimeoutError()
                for key, _ in selection.select(min(1, max(0, deadline - time.monotonic()))):
                    pipe = key.fileobj
                    if pipe is process.stdin:
                        try:
                            sent += os.write(pipe.fileno(), body[sent:sent + 8192])
                        except BrokenPipeError:
                            selection.unregister(pipe); pipe.close(); continue
                        if sent == len(body):
                            selection.unregister(pipe); pipe.close()
                    else:
                        block = os.read(pipe.fileno(), 4096)
                        if not block:
                            selection.unregister(pipe); pipe.close(); continue
                        target = output if pipe is process.stdout else errors
                        target.extend(block)
                        if len(output) + len(errors) > 16384:
                            raise ValueError()
            process.wait(timeout=max(0.01, deadline - time.monotonic()))
        text = bytes(output)
        started = b"CONTINUATION_MUTATION_STARTED=1\n" in text
        if process.returncode == 0 and not errors and text == b"CONTINUATION_MUTATION_STARTED=1\nROLLED_BACK=1\nCONTINUATION_COMPLETED=1\n":
            return "ROLLED_BACK", "COMPLETE", True
        match = re.fullmatch(rb"CONTINUATION_REFUSED=([A-Z_]+)\n", text)
        if not started and process.returncode == 74 and not errors and match and match[1].decode() in CHECKS:
            return "REFUSED", match[1].decode(), False
        partial = re.fullmatch(rb"CONTINUATION_MUTATION_STARTED=1\n(?:ROLLED_BACK=1\n)?CONTINUATION_REFUSED=([A-Z_]+)\n", text)
        if started and process.returncode == 74 and not errors and partial and partial[1].decode() in CHECKS:
            return "UNKNOWN", partial[1].decode(), True
        return "UNKNOWN", "ROLLBACK" if started else "INTERNAL", True
    except BaseException:
        # Only the group started by this invocation is signalled; no retry or fallback operation.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=5)
        return "UNKNOWN", "LIMIT", True
    finally:
        for pipe in (process.stdin, process.stdout, process.stderr):
            if not pipe.closed:
                pipe.close()

def main():
    envelope = json.loads(sys.argv[1], object_pairs_hook=strict)
    if set(envelope) != {"binding", "action", "sourceVersion"}:
        raise ValueError()
    # The launcher verified the exact SDK sentinels and LD categories before Python.
    # Functions are classified here, after Python startup, before any diagnostic/native IO.
    if len(sys.argv) != 3 or sys.argv[2] != "SDK_DEV_NULL_SENTINELS_REMOVED_CONTINUATION_V1" or not environment():
        receipt(envelope, "REFUSED", "ENVIRONMENT", False); return
    if os.geteuid() != 0:
        receipt(envelope, "REFUSED", "ROOT_IDENTITY", False); return
    binding = envelope["binding"]; original = binding["original"]; current = binding["current"]
    args = [original["task"], envelope["action"].replace("-", ""), original["execution"], original["unit"],
            original["ownerSha256"], original["candidate"], original["releaseSha256"], original["planSha256"],
            current["candidateOwnerSha256"], current["candidateTaskSha256"], current["maintenanceSha256"],
            current["releaseOwnerSha256"], current["parametersSha256"], current["unitSha256"]]
    receipt(envelope, *execute(args))

if __name__ == "__main__":
    try:
        main()
    except BaseException:
        # No raw exception/argument/configuration is printed. Missing safe receipt remains UNKNOWN.
        print("PUBLICATION_CONTINUATION_FAILURE=UNKNOWN", flush=True)
        sys.exit(70)
