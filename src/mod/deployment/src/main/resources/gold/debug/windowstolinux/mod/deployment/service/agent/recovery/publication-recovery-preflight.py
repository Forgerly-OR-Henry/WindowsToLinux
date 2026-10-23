import base64
import fcntl
import hashlib
import json
import os
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
import uuid

FORMAT = "WTL_AGENT_PUBLICATION_RECOVERY_PREFLIGHT_2"
CHECKS = ("ENVIRONMENT", "ROOT_IDENTITY", "LOCK_DIRECTORY", "ADMISSION_LOCK", "RUN_LOCK", "LOCK_ACQUIRED",
          "JOB_ABSENT", "CANDIDATE_OWNER", "CANDIDATE_TASK", "BUILD_IDLE", "BUILD_MAIN_PID", "BUILD_CGROUP_EMPTY",
          "SNAPSHOTS_ABSENT", "MAINTENANCE_OWNER", "SOLE_RELEASE", "CURRENT_RAW_LINK", "RELEASE_OWNER",
          "PARAMETERS_FILE", "DAEMON_DECLARATION", "UNIT_FILE", "UNIT_FRAGMENT", "UNIT_DROPINS_ABSENT",
          "CONTAINER_UNIT_ABSENT", "FULL_PARAMETER_VALIDATION", "UNIT_RENDER_MATCH", "MANAGED_DATA_LINKS", "STABILITY")
REASONS = ("NOT_CHECKED", "MATCH", "MISMATCH", "MISSING", "UNSAFE", "BUSY", "QUERY_FAILED", "LIMIT", "DRIFT", "NOT_IMPLEMENTED")
MAX_READ = 131072
MAX_ENTRIES = 256
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C", "LC_ALL": "C", "SYSTEMD_PAGER": "cat", "SYSTEMD_COLORS": "0"}


class Uncertain(Exception):
    def __init__(self, reason):
        self.reason = reason


def canonical(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Uncertain("UNSAFE")
        result[key] = value
    return result


def identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid, info.st_nlink,
            info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def secure(info, directory=False):
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise Uncertain("UNSAFE")
    if info.st_uid != 0 or info.st_gid != 0 or info.st_mode & 0o022 or not directory and info.st_nlink != 1:
        raise Uncertain("UNSAFE")


class Probe:
    # All descriptors are existing-only, read-only and no-follow. No old helper is executed.
    def __init__(self):
        self.fds = []
        self.names = []

    def keep(self, fd, directory=False):
        try:
            info = os.fstat(fd)
            secure(info, directory)
            self.fds.append((fd, identity(info)))
            return fd
        except BaseException:
            os.close(fd)
            raise

    def directory(self, path, missing=False):
        parts = [] if path == "/" else path.split("/")[1:]
        if not path.startswith("/") or any(x in ("", ".", "..") for x in parts):
            raise Uncertain("UNSAFE")
        fd = self.keep(os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NOATIME), True)
        for name in parts:
            try:
                child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NOATIME, dir_fd=fd)
            except FileNotFoundError:
                if missing:
                    self.names.append((fd, name, None))
                    return None
                raise Uncertain("MISSING") from None
            fd = self.keep(child, True)
        return fd

    def parent(self, path, missing=False):
        parent, name = path.rsplit("/", 1)
        return self.directory(parent or "/", missing), name

    def entry(self, parent, name):
        try:
            value = os.stat(name, dir_fd=parent, follow_symlinks=False)
        except FileNotFoundError:
            value = None
        self.names.append((parent, name, None if value is None else identity(value)))
        return value

    def read(self, path, maximum=MAX_READ):
        parent, name = self.parent(path)
        before = self.entry(parent, name)
        if before is None:
            raise Uncertain("MISSING")
        fd = self.keep(os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK | os.O_NOATIME, dir_fd=parent))
        if identity(os.fstat(fd)) != identity(before) or before.st_size > maximum:
            raise Uncertain("LIMIT" if before.st_size > maximum else "DRIFT")
        chunks, size = [], 0
        while True:
            part = os.read(fd, min(8192, maximum + 1 - size))
            if not part:
                break
            size += len(part)
            if size > maximum:
                raise Uncertain("LIMIT")
            chunks.append(part)
        if identity(os.fstat(fd)) != identity(before):
            raise Uncertain("DRIFT")
        return b"".join(chunks), fd

    def absent(self, path):
        parent, name = self.parent(path, True)
        return parent is None or self.entry(parent, name) is None

    def names_in(self, path):
        fd = self.directory(path)
        names = os.listdir(fd)
        if len(names) > MAX_ENTRIES:
            raise Uncertain("LIMIT")
        return names

    def raw_link(self, path):
        parent, name = self.parent(path)
        info = self.entry(parent, name)
        if info is None:
            raise Uncertain("MISSING")
        if not stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_gid != 0:
            raise Uncertain("UNSAFE")
        value = os.readlink(name, dir_fd=parent)
        if len(value) > 4096:
            raise Uncertain("LIMIT")
        return value

    def stable(self):
        for fd, before in self.fds:
            if identity(os.fstat(fd)) != before:
                return False
        for parent, name, before in self.names:
            try:
                after = identity(os.stat(name, dir_fd=parent, follow_symlinks=False))
            except FileNotFoundError:
                after = None
            if after != before:
                return False
        return True

    def close(self):
        for fd, _ in reversed(self.fds):
            os.close(fd)


def show(unit, properties):
    # Exact read-only systemd verb and properties, no shell, pager, unit file or environment input.
    with subprocess.Popen(["/usr/bin/systemctl", "show", "--no-pager", "--property=" + ",".join(properties), unit],
                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=ENV) as process:
        output = bytearray()
        deadline = time.monotonic() + 4
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0 or not selector.select(remaining):
                        raise Uncertain("QUERY_FAILED")
                    data = os.read(process.stdout.fileno(), 4097 - len(output))
                    if not data:
                        break
                    output.extend(data)
                    if len(output) > 4096:
                        raise Uncertain("LIMIT")
            if process.wait(timeout=max(0.001, deadline - time.monotonic())) != 0:
                raise Uncertain("QUERY_FAILED")
        finally:
            if process.poll() is None:
                process.kill()  # Only the one owned read-only query subprocess, never a managed service.
                process.wait(timeout=1)
    values = {}
    for line in output.decode("utf-8", "strict").splitlines():
        key, separator, value = line.partition("=")
        if not separator or key not in properties or key in values:
            raise Uncertain("QUERY_FAILED")
        values[key] = value
    if set(values) != set(properties):
        raise Uncertain("QUERY_FAILED")
    return values


def cgroup_empty(probe, group):
    if not group:
        return True, 0
    if not re.fullmatch(r"/(?:[A-Za-z0-9_.:@-]+/)*[A-Za-z0-9_.:@-]+", group) or ".." in group:
        raise Uncertain("UNSAFE")
    path = "/sys/fs/cgroup" + group
    if probe.absent(path):
        return True, 0
    pending, count, visited = [(path, 0)], 0, 0
    while pending:
        current, depth = pending.pop()
        visited += 1
        if visited > MAX_ENTRIES or depth > 16:
            raise Uncertain("LIMIT")
        fd = probe.directory(current)
        names = os.listdir(fd)
        if len(names) > MAX_ENTRIES:
            raise Uncertain("LIMIT")
        for name in names:
            info = probe.entry(fd, name)
            if info is None:
                raise Uncertain("DRIFT")
            if stat.S_ISLNK(info.st_mode):
                raise Uncertain("UNSAFE")
            if stat.S_ISDIR(info.st_mode):
                pending.append((current + "/" + name, depth + 1))
            elif name == "cgroup.procs":
                data, _ = probe.read(current + "/" + name, 16384)
                if any(not re.fullmatch(rb"[1-9][0-9]*", x) for x in data.splitlines()):
                    raise Uncertain("UNSAFE")
                count += len(data.splitlines())
    return count == 0, count


def diagnose(envelope):
    binding = envelope["binding"]["original"]
    remote = envelope["layout"]["remote"]
    app, candidate, release = binding["unit"], binding["candidate"], binding["releaseSha256"]
    root = remote["applicationsRoot"] + "/" + app
    lock = remote["stateRoot"] + "/application-locks/" + app
    workspace = remote["stateRoot"] + "/work/" + candidate
    release_root = root + "/releases/" + release
    unit = "/etc/systemd/system/windowstolinux-" + app + ".service"
    checks = {key: dict(id=key, status="UNKNOWN", reason="NOT_CHECKED", number=None, flag=None, sha256=None) for key in CHECKS}
    for key in ("FULL_PARAMETER_VALIDATION", "UNIT_RENDER_MATCH", "MANAGED_DATA_LINKS"):
        checks[key]["reason"] = "NOT_IMPLEMENTED"
    probe = Probe()

    def record(key, matches, number=None, digest=None):
        checks[key].update(status="PASS" if matches else "FAIL", reason="MATCH" if matches else "MISMATCH",
                           number=number, flag=matches, sha256=digest)

    def attempt(key, operation):
        try:
            operation()
        except (Uncertain, OSError, ValueError, UnicodeError, subprocess.SubprocessError) as error:
            checks[key].update(status="UNKNOWN", reason=error.reason if isinstance(error, Uncertain) else "QUERY_FAILED")

    def text_check(key, path, expected):
        body, _ = probe.read(path)
        record(key, body.rstrip(b"\n") == expected.encode(), digest=hashlib.sha256(body).hexdigest())

    def file_check(key, path):
        body, _ = probe.read(path)
        record(key, True, len(body), hashlib.sha256(body).hexdigest())

    stable = False
    drift = False
    try:
        forbidden = any(key in os.environ for key in ("BASH_ENV", "ENV", "LD_PRELOAD", "LD_LIBRARY_PATH")) or any(key.startswith("BASH_FUNC_") for key in os.environ)
        record("ENVIRONMENT", not forbidden)
        record("ROOT_IDENTITY", os.getuid() == 0 and os.getgid() == 0 and os.environ.get("SUDO_USER", "root") == "root")
        if forbidden or checks["ROOT_IDENTITY"]["status"] != "PASS":
            return checks, False
        fd = probe.directory(lock)
        record("LOCK_DIRECTORY", stat.S_IMODE(os.fstat(fd).st_mode) == 0o700, stat.S_IMODE(os.fstat(fd).st_mode))
        if checks["LOCK_DIRECTORY"]["status"] != "PASS":
            return checks, False
        for key, name in (("ADMISSION_LOCK", "admission"), ("RUN_LOCK", "run")):
            body, lock_fd = probe.read(lock + "/" + name, 4096)
            record(key, stat.S_IMODE(os.fstat(lock_fd).st_mode) == 0o600, stat.S_IMODE(os.fstat(lock_fd).st_mode))
            if checks[key]["status"] != "PASS":
                return checks, False
            try:
                fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                checks["LOCK_ACQUIRED"].update(status="UNKNOWN", reason="BUSY")
                return checks, False
        record("LOCK_ACQUIRED", True)
        attempt("JOB_ABSENT", lambda: record("JOB_ABSENT", probe.absent(lock + "/job")))
        attempt("CANDIDATE_OWNER", lambda: text_check("CANDIDATE_OWNER", workspace + "/.deployer", "root"))
        attempt("CANDIDATE_TASK", lambda: text_check("CANDIDATE_TASK", workspace + "/.agent-task", binding["task"]))
        try:
            build = show("windowstolinux-build-" + candidate + ".service", ("ActiveState", "MainPID", "ControlGroup"))
            record("BUILD_IDLE", build["ActiveState"] in ("inactive", "failed"))
            if not re.fullmatch(r"0|[1-9][0-9]{0,9}", build["MainPID"]):
                raise Uncertain("QUERY_FAILED")
            record("BUILD_MAIN_PID", build["MainPID"] == "0", int(build["MainPID"]))
            cgroup_observation = cgroup_empty(probe, build["ControlGroup"])
            record("BUILD_CGROUP_EMPTY", *cgroup_observation)
        except (Uncertain, OSError, ValueError, subprocess.SubprocessError):
            pass
        attempt("SNAPSHOTS_ABSENT", lambda: record("SNAPSHOTS_ABSENT", not any(name.startswith(app + "-") for name in probe.names_in(remote["stateRoot"] + "/snapshots"))))
        attempt("MAINTENANCE_OWNER", lambda: text_check("MAINTENANCE_OWNER", lock + "/maintenance", "deployment-" + release))
        def releases():
            names = probe.names_in(root + "/releases")
            probe.directory(release_root)
            record("SOLE_RELEASE", names == [release], len(names))
        attempt("SOLE_RELEASE", releases)
        attempt("CURRENT_RAW_LINK", lambda: record("CURRENT_RAW_LINK", probe.raw_link(root + "/current") == release_root))
        attempt("RELEASE_OWNER", lambda: text_check("RELEASE_OWNER", release_root + "/.windowstolinux-owner", binding["ownerSha256"]))
        try:
            body, _ = probe.read(release_root + "/.windowstolinux-deployment-parameters")
            record("PARAMETERS_FILE", True, len(body), hashlib.sha256(body).hexdigest())
            lines = body.decode("utf-8", "strict").splitlines()
            if len(lines) < 3 or lines[:2] != ["identity-v2", "SYSTEMD_STATIC"]:
                raise Uncertain("UNSAFE")
            payload = base64.b64decode(lines[2], validate=True)
            if len(payload) > 65536 or base64.b64encode(payload).decode() != lines[2]:
                raise Uncertain("UNSAFE")
            fields = payload.split(b"\0")
            record("DAEMON_DECLARATION", len(fields) >= 3 and fields[0] in (b"application-v1", b"application-v2") and fields[1] == b"DAEMON")
        except (Uncertain, OSError, ValueError, UnicodeError):
            pass
        attempt("UNIT_FILE", lambda: file_check("UNIT_FILE", unit))
        try:
            service = show("windowstolinux-" + app + ".service", ("FragmentPath", "DropInPaths"))
            record("UNIT_FRAGMENT", service["FragmentPath"] == unit)
            record("UNIT_DROPINS_ABSENT", not service["DropInPaths"])
        except (Uncertain, OSError, ValueError, subprocess.SubprocessError):
            pass
        attempt("CONTAINER_UNIT_ABSENT", lambda: record("CONTAINER_UNIT_ABSENT", probe.absent("/etc/containers/systemd/windowstolinux-" + app + ".container")))
        stable = probe.stable()
        drift = not stable
        try:
            repeated_build = show("windowstolinux-build-" + candidate + ".service", ("ActiveState", "MainPID", "ControlGroup"))
            repeated_service = show("windowstolinux-" + app + ".service", ("FragmentPath", "DropInPaths"))
            drift = drift or repeated_build != build or repeated_service != service or cgroup_empty(probe, build["ControlGroup"]) != cgroup_observation or not probe.stable()
            stable = not drift
        except (UnboundLocalError, Uncertain, OSError, ValueError, subprocess.SubprocessError):
            stable = False
        record("STABILITY", stable)
    except (Uncertain, OSError, ValueError, UnicodeError, subprocess.SubprocessError):
        pass
    finally:
        if not stable:
            for item in checks.values():
                if item["status"] == "PASS" and item["id"] not in ("ENVIRONMENT", "ROOT_IDENTITY"):
                    item.update(status="UNKNOWN", reason="DRIFT" if drift else "NOT_CHECKED")
            checks["STABILITY"].update(status="UNKNOWN", reason="DRIFT" if drift else "NOT_CHECKED")
        probe.close()
    return checks, stable


def environment_snapshot():
    # The fixed Bash boundary already verified/removed only the two SDK sentinels.
    # These declaration fields describe that checked boundary; remaining categories are measured here.
    result = dict(bashEnv="DEV_NULL", env="DEV_NULL", ldPreload="PRESENT" if "LD_PRELOAD" in os.environ else "ABSENT",
                  ldLibraryPath="PRESENT" if "LD_LIBRARY_PATH" in os.environ else "ABSENT",
                  exportedFunctions="UNKNOWN", snapshot="UNAVAILABLE", sanitizedSentinels="BASH_ENV" not in os.environ and "ENV" not in os.environ,
                  pythonStarted=True)
    found = False
    count = size = 0
    try:
        for key, value in os.environ.items():
            count += 1
            if key.startswith("BASH_FUNC_"):
                found = True
            if len(key) > 8192 or len(value) > 8192:
                result.update(exportedFunctions="PRESENT" if found else "UNKNOWN", snapshot="LIMIT")
                return result
            record_size = len(key.encode("utf-8", "strict")) + len(value.encode("utf-8", "strict")) + 2
            size += record_size
            if record_size > 8192 or count > 512 or size > 131072:
                result.update(exportedFunctions="PRESENT" if found else "UNKNOWN", snapshot="LIMIT")
                return result
        result.update(exportedFunctions="PRESENT" if found else "ABSENT", snapshot="COMPLETE")
    except (UnicodeError, RuntimeError, TypeError):
        result.update(exportedFunctions="PRESENT" if found else "UNKNOWN", snapshot="UNAVAILABLE")
    return result


def main():
    # The builder reconstructs this exact source and the single canonical argument before review/send.
    if len(sys.argv) != 3 or sys.argv[2] != "SDK_DEV_NULL_SENTINELS_REMOVED_V2" or len(sys.argv[1].encode()) > 16384:
        raise Uncertain("UNSAFE")
    envelope = json.loads(sys.argv[1], object_pairs_hook=strict_object)
    if set(envelope) != {"binding", "action", "layout", "sourceVersion"}:
        raise Uncertain("UNSAFE")
    if canonical(envelope) != sys.argv[1]:
        raise Uncertain("UNSAFE")
    wrapped = envelope["binding"]
    if set(wrapped) != {"original", "prior"}:
        raise Uncertain("UNSAFE")
    binding = wrapped["original"]
    prior = wrapped["prior"]
    if set(prior) != {"action", "requestEvent", "endEvent", "requestSha256", "receiptSha256", "pendingResultEvent", "pendingResultSha256"}:
        raise Uncertain("UNSAFE")
    if not isinstance(prior["action"], str) or str(uuid.UUID(prior["action"])) != prior["action"]:
        raise Uncertain("UNSAFE")
    for key in ("requestEvent", "endEvent", "pendingResultEvent"):
        if type(prior[key]) is not int or not 0 < prior[key] < 9223372036854775808:
            raise Uncertain("UNSAFE")
    for key in ("requestSha256", "receiptSha256", "pendingResultSha256"):
        if not isinstance(prior[key], str) or not re.fullmatch(r"[0-9a-f]{64}", prior[key]):
            raise Uncertain("UNSAFE")
    if not binding["pendingIntentEvent"] < prior["pendingResultEvent"] < prior["requestEvent"] < prior["endEvent"] or prior["action"] == binding["pendingAction"]:
        raise Uncertain("UNSAFE")
    if set(binding) != {"server", "task", "execution", "pendingAction", "pendingIntentEvent", "pendingIntentSha256", "planSha256", "unit", "ownerSha256", "candidate", "releaseSha256", "layoutSha256"}:
        raise Uncertain("UNSAFE")
    if not isinstance(binding["server"], str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", binding["server"]):
        raise Uncertain("UNSAFE")
    for key in ("task", "execution", "pendingAction"):
        if not isinstance(binding[key], str) or str(uuid.UUID(binding[key])) != binding[key]:
            raise Uncertain("UNSAFE")
    for key in ("pendingIntentSha256", "planSha256", "ownerSha256", "releaseSha256", "layoutSha256"):
        if not isinstance(binding[key], str) or not re.fullmatch(r"[0-9a-f]{64}", binding[key]):
            raise Uncertain("UNSAFE")
    if type(binding["pendingIntentEvent"]) is not int or not 0 < binding["pendingIntentEvent"] < 9223372036854775808:
        raise Uncertain("UNSAFE")
    if not isinstance(binding["unit"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", binding["unit"]):
        raise Uncertain("UNSAFE")
    if not isinstance(binding["candidate"], str) or not re.fullmatch(re.escape(binding["unit"]) + r"-[0-9a-f]{16}", binding["candidate"]):
        raise Uncertain("UNSAFE")
    layout = envelope["layout"]
    if set(layout) != {"formatVersion", "remote"} or type(layout["formatVersion"]) is not int or layout["formatVersion"] != 1:
        raise Uncertain("UNSAFE")
    if set(layout["remote"]) != {"helperDirectory", "applicationsRoot", "stateRoot", "configurationRoot", "dataRoot"}:
        raise Uncertain("UNSAFE")
    for value in layout["remote"].values():
        if not isinstance(value, str) or not re.fullmatch(r"/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+", value) or ".." in value:
            raise Uncertain("UNSAFE")
    if hashlib.sha256(canonical(layout).encode()).hexdigest() != binding["layoutSha256"] or not re.fullmatch(r"[0-9a-f]{64}", envelope["sourceVersion"]):
        raise Uncertain("UNSAFE")
    if not isinstance(envelope["action"], str) or str(uuid.UUID(envelope["action"])) != envelope["action"]:
        raise Uncertain("UNSAFE")
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(Uncertain("LIMIT")))
    signal.alarm(20)
    environment = environment_snapshot()
    clean = (environment["ldPreload"] == environment["ldLibraryPath"] == environment["exportedFunctions"] == "ABSENT"
             and environment["snapshot"] == "COMPLETE" and environment["sanitizedSentinels"])
    if clean:
        checks, stable = diagnose(envelope)
        stage = "GUARD_CHECKS"
    else:
        checks = {key: dict(id=key, status="UNKNOWN", reason="NOT_IMPLEMENTED" if key in
                  ("FULL_PARAMETER_VALIDATION", "UNIT_RENDER_MATCH", "MANAGED_DATA_LINKS") else "NOT_CHECKED",
                  number=None, flag=None, sha256=None) for key in CHECKS}
        checks["ENVIRONMENT"].update(status="FAIL", reason="MISMATCH", flag=False)
        stable = False
        stage = "PYTHON_ENVIRONMENT_REJECTED"
    value = dict(format=FORMAT, sourceVersion=envelope["sourceVersion"], action=envelope["action"],
                 binding=envelope["binding"], stage=stage, environment=environment,
                 checks=[checks[key] for key in CHECKS], stable=stable)
    output = "PUBLICATION_RECOVERY_PREFLIGHT=" + canonical(value) + "\n"
    if len(output.encode()) > 16384:
        raise Uncertain("LIMIT")
    sys.stdout.write(output)


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        # No raw exception, filesystem content, command output or secret body is emitted.
        sys.stderr.write("PUBLICATION_RECOVERY_PREFLIGHT=UNKNOWN\n")
        raise SystemExit(70)
