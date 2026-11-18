"""Execute the extracted production preflight with controlled stdlib boundaries only."""
import ast
import errno
import json
import pathlib
import sys
import types

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
tree = ast.parse(source)
calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)]
exec_call = next(node for node in calls if ast.unparse(node.func) == "os.execve")
assert ast.literal_eval(exec_call.args[0]) == "/usr/bin/true"
assert ast.literal_eval(exec_call.args[1]) == ("/usr/bin/true",)
assert ast.literal_eval(exec_call.args[2]) == {}
pair_call = next(node for node in calls if ast.unparse(node.func) == "socket.socketpair")
assert [ast.unparse(arg) for arg in pair_call.args] == [
    "socket.AF_UNIX", "socket.SOCK_SEQPACKET | socket.SOCK_CLOEXEC", "0"]
code = compile(tree, "production-offline-preflight", "exec")


class ChildExit(BaseException):
    pass


def check(scenario):
    endpoints = []
    writes = []
    reaped = []
    waits = []
    creation_families = []

    class Endpoint:
        def __init__(self, fd):
            self.fd = fd
            self.closed = False
            self.queue = []

        def __enter__(self):
            return self

        def __exit__(self, *unused):
            self.close()

        def close(self):
            self.closed = True

        def fileno(self):
            assert not self.closed
            return self.fd

        def recv(self, size):
            if scenario == "recvfrom-denied":
                raise OSError(errno.EPERM, "controlled recv denial")
            if self.queue:
                return self.queue.pop(0)
            assert reaped and endpoints[1].closed
            return b"X" if scenario == "missing-eof" else b""

        def send(self, data):
            assert data == b"WTLC"
            if scenario == "send-allowed":
                return len(data)
            raise OSError(errno.EPERM, "controlled send denial")

    def socket(family):
        creation_families.append(family)
        if scenario == "socket-allowed":
            return Endpoint(90)
        raise OSError(errno.EPERM, "controlled socket denial")

    def socketpair(family, kind, protocol):
        assert (family, kind, protocol) == (1, 5 | 0x80000, 0)
        if scenario == "pair-denied":
            raise OSError(errno.EPERM, "controlled pair denial")
        endpoints.extend((Endpoint(31), Endpoint(32)))
        return tuple(endpoints)

    def write(fd, data):
        assert data == b"WTLC" and fd in (31, 32)
        writes.append(fd)
        endpoints[1 if fd == 31 else 0].queue.append(data)
        return len(data)

    def fork():
        if scenario == "fork-failed":
            raise OSError(errno.EAGAIN, "controlled fork failure")
        return 0 if scenario == "exec-failed-child" else 4242

    def waitpid(pid, flags):
        assert pid == 4242 and flags == 0 and endpoints[1].closed
        waits.append(pid)
        if scenario == "wait-interrupted" and len(waits) == 1:
            raise OSError(errno.EINTR, "controlled wait interruption")
        assert not reaped
        reaped.append(pid)
        return pid, 127 << 8 if scenario == "child-nonzero" else 0

    def execve(path, args, environment):
        assert (path, args, environment) == ("/usr/bin/true", ("/usr/bin/true",), {})
        raise OSError(errno.ENOENT, "controlled child exec failure")

    def child_exit(status):
        assert status == 127
        raise ChildExit()

    fake_os = types.SimpleNamespace(
        geteuid=lambda: 0 if scenario == "root" else 61000,
        readlink=lambda path: "net:[1]" if scenario == "host-netns" else "net:[2]",
        get_inheritable=lambda fd: scenario == "missing-cloexec",
        write=write, fork=fork, waitpid=waitpid, execve=execve, _exit=child_exit,
        WIFEXITED=lambda status: status & 127 == 0, WEXITSTATUS=lambda status: status >> 8)
    fake_socket = types.SimpleNamespace(AF_INET=2, AF_INET6=10, AF_UNIX=1,
        SOCK_SEQPACKET=5, SOCK_CLOEXEC=0x80000, socket=socket, socketpair=socketpair)
    previous = sys.modules["os"], sys.modules.get("socket"), sys.argv
    sys.modules["os"] = fake_os
    sys.modules["socket"] = fake_socket
    sys.argv = ["production-preflight", "net:[1]"]
    rejected = False
    try:
        try:
            exec(code, {"__name__": "controlled_production_preflight"})
        except (SystemExit, OSError, ChildExit):
            rejected = True
    finally:
        sys.modules["os"], old_socket, sys.argv = previous
        if old_socket is None:
            del sys.modules["socket"]
        else:
            sys.modules["socket"] = old_socket
    assert rejected == (scenario != "success"), scenario
    assert all(endpoint.closed for endpoint in endpoints), scenario
    if scenario == "success":
        assert creation_families == [2, 10, 1] and writes == [31, 32] and reaped == [4242]
    if scenario in ("child-nonzero", "missing-eof", "wait-interrupted"):
        assert reaped == [4242], scenario
    return dict(scenario=scenario, rejected=rejected, ownedEndpointsClosed=True,
                reaped=len(reaped), waitAttempts=len(waits))


scenarios = ("success", "pair-denied", "recvfrom-denied", "send-allowed", "fork-failed",
             "child-nonzero", "missing-eof", "missing-cloexec", "root", "host-netns",
             "socket-allowed", "wait-interrupted", "exec-failed-child")
print(json.dumps(dict(schema="OFFLINE_PRODUCTION_PREFLIGHT_CONTROLLED_TEST_V1",
    cases=[check(scenario) for scenario in scenarios], actualProductionSource=True,
    realSocketsForkExecOrHost=False), separators=(",", ":")))
