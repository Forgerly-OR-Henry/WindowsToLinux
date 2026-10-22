import hashlib
import fcntl
import json
import os
import re
import stat
import sys

# @WTL_CONTROLLER_CONSTANTS@


class StoreUnsafe(Exception):
    pass


def store_check(value):
    if not value:
        raise StoreUnsafe()


def store_identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid,
            info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def store_directory(fd):
    info = os.fstat(fd)
    store_check(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
    return fd


def store_walk(path, missing=False):
    store_check(path.startswith("/") and all(part not in ("", ".", "..") for part in path.split("/")[1:]))
    fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        store_directory(fd)
        for part in path.split("/")[1:]:
            try:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
            except FileNotFoundError:
                if missing:
                    return None
                raise StoreUnsafe() from None
            try:
                store_directory(child)
            except BaseException:
                os.close(child)
                raise
            os.close(fd)
            fd = child
        result, fd = fd, None
        return result
    finally:
        if fd is not None:
            os.close(fd)


def store_read(parent, name, maximum, expected=None):
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK, dir_fd=parent)
    try:
        before = os.fstat(fd)
        store_check(stat.S_ISREG(before.st_mode) and before.st_uid == 0 and before.st_gid == 0
                    and stat.S_IMODE(before.st_mode) == 0o444 and before.st_nlink == 1
                    and 0 < before.st_size <= maximum)
        chunks, size = [], 0
        while size <= maximum:
            chunk = os.read(fd, min(65536, maximum + 1 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
        body = b"".join(chunks)
        store_check(len(body) == before.st_size and store_identity(before) == store_identity(os.fstat(fd)))
        if expected is not None:
            store_check(hashlib.sha256(body).hexdigest() == expected)
        return body
    finally:
        os.close(fd)


def store_json(body):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            store_check(key not in result)
            result[key] = value
        return result
    value = json.loads(body.decode("utf-8"), object_pairs_hook=unique,
                       parse_constant=lambda _: store_check(False))
    store_check(json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8") == body)
    return value


def store_expected(installation, install_task):
    return {"format": "WTL_AGENT_CONTROLLER_STORE_1", "contract": CONTRACT_REVISION,
            "version": VERSION_SHA256, "sourceSha256": SOURCE_SHA256, "sourceBytes": SOURCE_BYTES,
            "layoutSha256": LAYOUT_SHA256, "path": STORE_ROOT + "/" + VERSION_SHA256,
            "installOperation": installation, "installTask": install_task}


def store_ready(locked=False):
    parent = store_walk(STORE_ROOT, missing=True)
    if parent is None:
        return None
    lock = None
    try:
        if not locked:
            try:
                lock = os.open(".install.lock", os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK, dir_fd=parent)
            except FileNotFoundError:
                lock = None
            if lock is not None:
                info = os.fstat(lock)
                store_check(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_gid == 0
                            and stat.S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1)
                fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        store_check(not any(name.startswith(".install-" + VERSION_SHA256 + "-") for name in os.listdir(parent)))
        try:
            version = os.open(VERSION_SHA256, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=parent)
        except FileNotFoundError:
            return None
        try:
            store_directory(version)
            store_check(stat.S_IMODE(os.fstat(version).st_mode) == 0o555)
            receipt = store_json(store_read(version, "install-receipt.json", 4096))
            installation = receipt.get("installOperation")
            install_task = receipt.get("installTask")
            store_check(isinstance(installation, str) and re.fullmatch("[a-f0-9]{32}", installation) is not None)
            store_check(isinstance(install_task, str) and re.fullmatch("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}", install_task) is not None)
            store_check(receipt == store_expected(installation, install_task))
            source = store_read(version, "controller.py", 1048576, SOURCE_SHA256)
            store_check(len(source) == SOURCE_BYTES)
            return receipt, source
        finally:
            os.close(version)
    finally:
        if lock is not None:
            os.close(lock)
        os.close(parent)


def store_observation(receipt, task, action):
    value = dict(receipt if receipt is not None else store_expected("-", "-"))
    value.update(status="READY" if receipt is not None else "MISSING", task=task, action=action)
    return value


def store_emit(value):
    print("AGENT_CONTROLLER_STORE=" + json.dumps(value, sort_keys=True, separators=(",", ":")))


def store_main(arguments):
    tracked = len(arguments) == 4 and arguments[0] in ("prepare-tracked", "bind-tracked", "observe-tracked", "cleanup-tracked")
    if tracked:
        verb, task, operation, request_sha = arguments
        store_check(re.fullmatch("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}", task) is not None
                    and re.fullmatch("[a-f0-9]{32}", operation) is not None
                    and re.fullmatch("[a-f0-9]{64}", request_sha) is not None and os.geteuid() == 0)
        raw = sys.stdin.buffer.read(131073)
        store_check(0 < len(raw) <= 131072 and hashlib.sha256(raw).hexdigest() == request_sha)
        ready = store_ready()
        store_check(ready is not None)
        source = ready[1]
        sys.argv = ["<wtl-agent-toolchain>", *arguments]
        exec(compile(source, "<wtl-agent-toolchain>", "exec"),
             {"__name__": "__main__", "PROGRAM_SOURCE": source, "AGENT_TRACKED_REQUEST_RAW": raw,
              "AGENT_TRACKED_STORE_BINDING": {"version": VERSION_SHA256, "sourceSha256": SOURCE_SHA256,
                                             "layoutSha256": LAYOUT_SHA256}})
        return
    store_check(len(arguments) >= 3 and arguments[0] in ("readiness", "invoke"))
    mode, task, action = arguments[:3]
    store_check(re.fullmatch("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}", task) is not None
                and re.fullmatch("[a-f0-9]{32}", action) is not None)
    ready = store_ready()
    if mode == "readiness":
        store_check(len(arguments) == 3)
        store_emit(store_observation(ready[0] if ready is not None else None, task, action))
        return
    store_check(ready is not None and os.geteuid() == 0)
    argv = arguments[3:]
    store_check(len(argv) == 8 and argv[0] == "prepare" or len(argv) == 4 and argv[0] == "bind")
    operation = argv[6] if argv[0] == "prepare" else argv[3]
    store_check(re.fullmatch("[a-f0-9]{32}", operation) is not None and operation == action)
    source = ready[1]
    sys.argv = ["<wtl-agent-toolchain>", *argv]
    exec(compile(source, "<wtl-agent-toolchain>", "exec"),
         {"__name__": "__main__", "PROGRAM_SOURCE": source})


if __name__ == "__main__":
    try:
        store_main(sys.argv[1:])
    except Exception:
        print("TOOLCHAIN_FAILURE=unknown:AGENT_CONTROLLER_STORE_UNCONFIRMED")
        sys.exit(70)
