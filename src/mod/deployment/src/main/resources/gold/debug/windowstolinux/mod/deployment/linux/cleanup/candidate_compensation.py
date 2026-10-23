"""One explicit compensation for an unpublished, original native candidate."""
import base64
import fcntl
import hashlib
import json
import os
import pathlib
import re
import selectors
import signal
import stat
import subprocess
import time
import uuid


class Refusal(Exception):
    pass


DEADLINE = None


def remaining():
    if DEADLINE is None:
        return 100.0
    value = DEADLINE - time.monotonic()
    require(value > 0, "request-deadline")
    return value


def require(value, reason):
    if not value:
        raise Refusal(reason)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def exact(value, keys):
    require(type(value) is dict and set(value) == set(keys), "request-fields")


def validate(request):
    exact(request, ("mode", "binding", "expectedObservationSha256", "originalExecutionCreatedAtEpochMillis"))
    require(type(request["originalExecutionCreatedAtEpochMillis"]) is int
            and 0 < request["originalExecutionCreatedAtEpochMillis"] <= 9223372036854775807, "original-execution-time")
    require(request["mode"] in ("observe", "execute", "receipt"), "request-mode")
    b = request["binding"]
    exact(b, ("formatVersion", "taskId", "operationId", "applicationId", "unitId", "candidateId",
              "planSha256", "ownerManifestSha256", "archiveSha256", "layout", "helperSha256"))
    require(type(b["formatVersion"]) is int and b["formatVersion"] == 1, "binding-version")
    for name in ("taskId", "operationId"):
        require(type(b[name]) is str and str(uuid.UUID(b[name])) == b[name], "binding-uuid")
    for name in ("planSha256", "ownerManifestSha256", "archiveSha256", "helperSha256"):
        require(type(b[name]) is str and re.fullmatch("[0-9a-f]{64}", b[name]), "binding-digest")
    for name in ("applicationId", "unitId"):
        require(type(b[name]) is str and re.fullmatch("[a-z0-9][a-z0-9-]{0,62}", b[name]), "binding-unit")
    require(b["unitId"].startswith(b["applicationId"] + "-"), "binding-component")
    require(b["candidateId"] == b["unitId"] + "-" + b["archiveSha256"][:16], "binding-candidate")
    layout = b["layout"]
    exact(layout, ("formatVersion", "remote"))
    require(type(layout["formatVersion"]) is int and layout["formatVersion"] == 1, "layout-version")
    roots = layout["remote"]
    exact(roots, ("helperDirectory", "applicationsRoot", "stateRoot", "configurationRoot", "dataRoot"))
    for value in roots.values():
        require(type(value) is str and value.startswith("/") and value != "/"
                and str(pathlib.PurePosixPath(value)) == value
                and all(part not in (".", "..") for part in value.split("/")[1:])
                and not any(ord(c) < 32 for c in value), "layout-path")
    expected = request["expectedObservationSha256"]
    require(type(expected) is str and (not expected or re.fullmatch("[0-9a-f]{64}", expected)), "observation-digest")
    require((request["mode"] == "execute") == bool(expected), "observation-required")
    return b


def bounded_read(path, maximum=65536):
    remaining()
    with open(path, "rb", buffering=0) as source:
        value = source.read(maximum + 1)
    require(len(value) <= maximum, "observation-bound")
    return value


def owned(path, directory=False):
    info = os.lstat(path)
    require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0
            and (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))
            and not (info.st_mode & 0o022), "owned-path")
    if not directory:
        require(info.st_nlink == 1, "owned-link-count")
    return {"device": info.st_dev, "inode": info.st_ino, "mode": stat.S_IMODE(info.st_mode),
            "uid": info.st_uid, "gid": info.st_gid, "size": 0 if directory else info.st_size}


def parents(path):
    current = pathlib.Path(path)
    for parent in reversed(current.parents):
        owned(parent, True)


def existing_parents(path):
    for parent in reversed(pathlib.Path(path).parents):
        if not os.path.lexists(parent):
            break
        owned(parent, True)


def secure_directory(path, create=False):
    parents(path)
    if create:
        try:
            os.mkdir(path, 0o700)
        except FileExistsError:
            pass
    result = owned(path, True)
    if create:
        sync_directory(os.path.dirname(path))
    return result


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def command(arguments, maximum=65536, seconds=10):
    result = subprocess.run(arguments, stdin=subprocess.DEVNULL, capture_output=True, timeout=min(seconds, remaining()),
                            env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C"})
    require(result.returncode == 0 and len(result.stdout) <= maximum and len(result.stderr) <= maximum,
            "observation-command")
    return result.stdout.decode("utf-8", "strict")


def process_stat(pid):
    raw = bounded_read("/proc/" + str(pid) + "/stat", 8192).decode("ascii")
    fields = raw[raw.rindex(")") + 2:].split()
    require(len(fields) > 19, "process-stat")
    return {"pid": pid, "state": fields[0], "parent": int(fields[1]), "group": int(fields[2]),
            "session": int(fields[3]), "start": int(fields[19])}


def own_chain():
    chain, pid = {}, os.getpid()
    while pid:
        require(pid not in chain and len(chain) < 64, "observer-chain")
        current = process_stat(pid)
        chain[pid] = current
        pid = current["parent"]
    return chain


def sender_closed(chain):
    sockets, ssh_ancestors = set(), 0
    for pid in chain:
        base = "/proc/" + str(pid)
        if "sshd" not in bounded_read(base + "/comm", 256).decode("ascii"):
            continue
        ssh_ancestors += 1
        for fd in os.listdir(base + "/fd"):
            try:
                match = re.fullmatch(r"socket:\[([0-9]+)\]", os.readlink(base + "/fd/" + fd))
            except FileNotFoundError:
                continue
            if match:
                sockets.add(match[1])
    require(ssh_ancestors and sockets, "observer-ssh-identity")
    rows = []
    for table in ("/proc/net/tcp", "/proc/net/tcp6"):
        for line in bounded_read(table, 4194304).decode("ascii").splitlines()[1:]:
            fields = line.split()
            require(len(fields) >= 10, "ssh-socket-observation")
            rows.append((fields[1].rsplit(":", 1)[1], fields[3], fields[9]))
    ports = {port for port, state, inode in rows if inode in sockets and state == "01"}
    require(len(ports) == 1, "observer-ssh-socket")
    for port, state, inode in rows:
        if port in ports:
            # LISTEN and kernel TIME_WAIT/CLOSE cannot accept an old exec request.
            require(state in ("0A", "06", "07") or inode in sockets, "other-ssh-transport")


def under(value, root):
    value = value.removesuffix(" (deleted)")
    return value == root or value.startswith(root + "/")


def system_shutdown_waiter(pid, before, cmdline, executable, created_at, boot):
    script = "/usr/share/unattended-upgrades/unattended-upgrade-shutdown"
    unit = "unattended-upgrades.service"
    require(before["parent"] == 1 and created_at > 0
            and re.fullmatch(r"/usr/bin/python3[.][0-9]+(?: \(deleted\))?", executable)
            and cmdline == b"/usr/bin/python3\0" + script.encode() + b"\0--wait-for-signal\0",
            "other-root-execution-chain")
    require(bounded_read("/proc/" + str(pid) + "/comm", 256) == b"unattended-upgr\n", "system-service-identity")
    ticks = os.sysconf("SC_CLK_TCK")
    boot_time = re.findall(rb"(?m)^btime ([0-9]+)$", bounded_read("/proc/stat", 1048576))
    require(type(ticks) is int and ticks > 0 and len(boot_time) == 1, "system-service-start")
    started = int(boot_time[0]) * 1000 + before["start"] * 1000 // ticks
    require(started + 60000 < created_at, "system-service-start")
    properties = ("FragmentPath", "DropInPaths", "MainPID", "ActiveState", "SubState", "ControlGroup", "InvocationID", "ExecStart", "Type")
    output = command(["systemctl", "show", unit, "--no-pager", "--property=" + ",".join(properties)])
    facts = dict(line.split("=", 1) for line in output.splitlines() if "=" in line)
    require(set(facts) == set(properties) and facts["FragmentPath"] in
            ("/usr/lib/systemd/system/" + unit, "/lib/systemd/system/" + unit)
            and not facts["DropInPaths"] and facts["MainPID"] == str(pid)
            and facts["ActiveState"] == "active" and facts["SubState"] == "running" and facts["Type"] == "simple"
            and facts["ControlGroup"] == "/system.slice/" + unit
            and re.fullmatch("[0-9a-f]{32}", facts["InvocationID"]), "system-service-unit")
    launched = re.fullmatch(r"\{ path=([^ ;]+) ; argv\[\]=(.*?) ; ignore_errors=(?:yes|no) ; .* \}", facts["ExecStart"])
    require(launched and launched[1] == script and launched[2] == script + " --wait-for-signal", "system-service-launcher")
    group = bounded_read("/proc/" + str(pid) + "/cgroup", 16384).decode("utf-8", "strict")
    require(group == "0::" + facts["ControlGroup"] + "\n", "system-service-cgroup")
    fragment = os.path.realpath(facts["FragmentPath"])
    for path in (script, fragment):
        parents(path)
        owned(path)
    script_sha = digest(bounded_read(script, 65536))
    unit_sha = digest(bounded_read(fragment, 65536))
    # This reviewed package role handles OS upgrade coordination, not task payloads
    # or arbitrary cleanup jobs. This exception removes only the name guard.
    require(script_sha == "8fbf4e278c4ad457e82cc05586631b1a0550dd9d10892a70487ef74c623066e0"
            and unit_sha == "642331d3861bcc5e41342b9b93ad96b1852447d5b10533b9b2988531cfd2860a", "system-service-source")
    mapped = os.stat("/proc/" + str(pid) + "/exe")
    require(stat.S_ISREG(mapped.st_mode) and mapped.st_uid == 0 and not mapped.st_mode & 0o022
            and mapped.st_nlink in (0, 1), "system-service-image")
    image_sha = digest(bounded_read("/proc/" + str(pid) + "/exe", 33554432))
    require(process_stat(pid) == before and os.readlink("/proc/" + str(pid) + "/exe") == executable
            and bounded_read("/proc/" + str(pid) + "/cmdline", 131072) == cmdline, "system-service-changed")
    return {"role": "unattended-upgrade-shutdown-v1", "pid": pid, "parentPid": 1, "startTicks": before["start"],
            "bootIdSha256": digest(boot.encode()), "executableSha256": image_sha,
            "executableDevice": mapped.st_dev, "executableInode": mapped.st_ino,
            "scriptSha256": script_sha, "unitSha256": unit_sha, "argvSha256": digest(cmdline),
            "unitInvocationId": facts["InvocationID"], "controlGroupSha256": digest(facts["ControlGroup"].encode()),
            "startedAtEpochMillis": started}


def quiescence(b, candidate, devices, deployer, created_at=0, boot=""):
    # The old sender is untracked: reject every other possible login/interpreter chain,
    # rather than inferring quiescence from candidate substring matches alone.
    chain = own_chain()
    sender_closed(chain)
    blocked = {"bash", "sh", "dash", "zsh", "ksh", "sudo", "su", "python", "python3", "perl", "ruby",
               "rm", "rmdir", "umount", "mount", "losetup", "systemctl", "systemd-run", "setpriv", "fuser",
               "sftp-server", "internal-sftp", "ssh", "sftp", "scp", "rsync"}
    evidence, identities, exclusion, exclusion_source = [], {}, {}, None
    pids = sorted(int(item) for item in os.listdir("/proc") if item.isdecimal())
    require(len(pids) <= 32768, "process-count")
    for pid in pids:
        remaining()
        try:
            before = process_stat(pid)
            identities[pid] = before
            if pid in chain:
                require(before == chain[pid], "observer-chain-changed")
                continue
            if before["state"] == "Z":
                continue
            base = "/proc/" + str(pid)
            uid = os.stat(base).st_uid
            cmdline = bounded_read(base + "/cmdline", 131072)
            try:
                executable = os.readlink(base + "/exe")
            except FileNotFoundError:
                # Kernel tasks have no address space, argv or descriptors. A racing
                # user execution is refused, never classified from an empty argv alone.
                require(not cmdline and not bounded_read(base + "/maps", 1048576)
                        and not os.listdir(base + "/fd"), "process-identity-unavailable")
                require(process_stat(pid) == before, "process-changed")
                continue
            name = os.path.basename(executable.removesuffix(" (deleted)"))
            if name.startswith("sshd"):
                # Only the root listener (not a session, privsep child or delayed
                # stdin shell) may be outside this observation's exact ancestor chain.
                require(uid == 0 and before["parent"] == 1
                        and b"[listener]" in cmdline and b"root@" not in cmdline,
                        "other-ssh-execution-chain")
            require(deployer == 0 or uid != deployer, "deployer-process-active")
            if uid == 0 and (name in blocked or re.fullmatch(r"python[0-9.]*", name)):
                require(not exclusion, "other-root-execution-chain")
                exclusion = system_shutdown_waiter(pid, before, cmdline, executable, created_at, boot)
                exclusion_source = (pid, before, cmdline, executable)
            text = cmdline.decode("utf-8", "replace")
            require(not any(token in text for token in (candidate, b["candidateId"], b["unitId"], b["taskId"])),
                    "original-execution-active")
            group = bounded_read(base + "/cgroup", 16384).decode("utf-8", "strict")
            require(b["candidateId"] not in group and b["unitId"] not in group, "original-cgroup-active")
            for link in ("cwd", "root"):
                value = os.readlink(base + "/" + link)
                require(not under(value, candidate), "candidate-process-active")
            descriptors = os.listdir(base + "/fd")
            require(len(descriptors) <= 65536, "descriptor-count")
            for descriptor in descriptors:
                remaining()
                try:
                    value = os.readlink(base + "/fd/" + descriptor)
                except FileNotFoundError:
                    require(not os.path.exists(base + "/fd/" + descriptor), "descriptor-unavailable")
                    continue
                require(not under(value, candidate) and value.removesuffix(" (deleted)") not in devices,
                        "candidate-descriptor-active")
            require(process_stat(pid) == before, "process-changed")
            evidence.append((pid, before["start"], uid, digest(cmdline)))
        except FileNotFoundError:
            raise Refusal("process-vanished-during-observation")
    require(pids == sorted(int(item) for item in os.listdir("/proc") if item.isdecimal()), "process-set-changed")
    for pid, before in identities.items():
        after = process_stat(pid)
        require((before["start"], before["parent"]) == (after["start"], after["parent"]), "process-changed")
    sender_closed(chain)
    if exclusion_source is not None:
        require(system_shutdown_waiter(*exclusion_source, created_at, boot) == exclusion, "system-service-changed")
    return digest(canonical(evidence)), exclusion


def mount_rows():
    result = []
    for line in bounded_read("/proc/self/mountinfo", 1048576).decode("utf-8", "strict").splitlines():
        fields = line.split()
        split = fields.index("-")
        decode = lambda s: re.sub(r"\\([0-7]{3})", lambda m: chr(int(m[1], 8)), s)
        result.append({"target": decode(fields[4]), "source": decode(fields[split + 2]), "type": fields[split + 1]})
    return result


def cgroup_idle(root):
    require(os.path.isdir(root) and not os.path.islink(root), "cgroup-unavailable")
    def unavailable(_):
        raise Refusal("cgroup-unavailable")
    for directory, dirs, files in os.walk(root, followlinks=False, onerror=unavailable):
        remaining()
        require(not os.path.islink(directory)
                and not any(os.path.islink(os.path.join(directory, child)) for child in dirs), "cgroup-link")
        require("cgroup.procs" in files, "cgroup-unavailable")
        require(not bounded_read(directory + "/cgroup.procs", 262144).strip(), "build-cgroup-active")


def original_state(b, created_at):
    r = b["layout"]["remote"]
    candidate = r["stateRoot"] + "/work/" + b["candidateId"]
    helper = r["helperDirectory"] + "/managed-helper"
    parents(helper)
    helper_stat = owned(helper)
    require(digest(bounded_read(helper, 2097152)) == b["helperSha256"], "helper-changed")
    boot = bounded_read("/proc/sys/kernel/random/boot_id", 128).decode("ascii").strip()
    require(str(uuid.UUID(boot)) == boot, "boot-identity")
    protected = (r["applicationsRoot"] + "/" + b["unitId"],
                 r["stateRoot"] + "/snapshots/" + b["unitId"] + "-" + b["taskId"],
                 r["stateRoot"] + "/application-locks/" + b["unitId"] + "/maintenance",
                 "/etc/systemd/system/windowstolinux-" + b["unitId"] + ".service")
    for path in protected:
        existing_parents(path)
        require(not os.path.lexists(path), "protected-state-present")
    unit = "windowstolinux-" + b["unitId"] + ".service"
    build = "windowstolinux-build-" + b["candidateId"] + ".service"
    unit_facts = {}
    for selected in (unit, build):
        output = command(["systemctl", "show", selected, "--no-pager",
                          "--property=LoadState,ActiveState,ControlGroup,MainPID"])
        facts = dict(line.split("=", 1) for line in output.splitlines() if "=" in line)
        require(set(facts) == {"LoadState", "ActiveState", "ControlGroup", "MainPID"}, "unit-observation")
        require(facts["ActiveState"] in ("inactive", "failed") and facts["MainPID"] == "0", "unit-active")
        if selected == unit:
            require(facts["LoadState"] == "not-found", "published-unit-present")
        group = facts["ControlGroup"]
        require(not group or group.startswith("/") and ".." not in group.split("/"), "cgroup-path")
        if group:
            cgroup_idle("/sys/fs/cgroup" + group)
        unit_facts[selected] = facts
    mounts = mount_rows()
    related = [m for m in mounts if under(m["target"], candidate)]
    parents(candidate)
    present = os.path.lexists(candidate)
    devices, identity, deployer = [], None, -1
    if present:
        identity = owned(candidate, True)
        require(bounded_read(candidate + "/.agent-task", 128) == (b["taskId"] + "\n").encode(), "candidate-task")
        owned(candidate + "/.agent-task")
        deployer_name = bounded_read(candidate + "/.deployer", 128).decode("ascii").strip()
        owned(candidate + "/.deployer")
        require(deployer_name == "root", "candidate-deployer")
        deployer = 0
        for name in (".builder", ".builder-intent", ".image-engine", ".restore", "mutable/restore-activation"):
            require(not os.path.lexists(candidate + "/" + name), "unsupported-candidate-residue")
        for name in (".workspace.ext4", ".workspace-device", ".workspace-limit", ".purpose"):
            owned(candidate + "/" + name)
        require(bounded_read(candidate + "/.purpose", 32) == b"build\n", "workspace-purpose")
        device = bounded_read(candidate + "/.workspace-device", 128).decode("ascii").strip()
        require(re.fullmatch("/dev/loop[0-9]+", device), "workspace-device")
        image = candidate + "/.workspace.ext4"
        require(command(["losetup", "--noheadings", "--raw", "--output", "BACK-FILE", device]).strip() == image,
                "workspace-loop-owner")
        require(related == [{"target": candidate + "/mutable", "source": device, "type": "ext4"}], "workspace-mount-owner")
        require([m for m in mounts if m["source"] == device] == related, "workspace-other-mount")
        devices.append(device)
        identity["image"] = owned(image)
        identity["deployer"] = deployer_name
        identity["device"] = device
    else:
        require(not related, "absent-candidate-mounted")
    # A lazy-detached loop can outlive the candidate path. Observe all loop backing
    # files as well as the tracked device before and after the compensation.
    loops = []
    for entry in pathlib.Path("/sys/block").glob("loop*"):
        remaining()
        backing = entry / "loop/backing_file"
        try:
            value = bounded_read(backing, 8192).decode("utf-8", "strict").strip()
        except FileNotFoundError:
            continue
        if under("/" + value.lstrip("/"), candidate):
            loops.append("/dev/" + entry.name)
    require(sorted(loops) == sorted(devices), "workspace-loop-residue")
    process_sha, exclusion = quiescence(b, candidate, devices, deployer, created_at, boot)
    stable = {"bootId": boot, "helper": helper_stat, "candidate": identity,
              "mounts": related, "loops": sorted(loops), "units": unit_facts,
              "protectedAbsent": list(protected), "systemProcessExclusion": exclusion}
    return {"present": present, "sha": digest(canonical(stable)), "processSha": process_sha,
            "protected": digest(canonical({"boot": boot, "helper": helper_stat,
                                           "unit": unit_facts[unit], "absent": list(protected),
                                           "systemProcessExclusion": exclusion})),
            "systemProcessExclusion": exclusion}


def receipt_paths(b):
    root = b["layout"]["remote"]["stateRoot"]
    directory = root + "/candidate-compensations/" + b["taskId"] + "/" + b["operationId"]
    return directory, directory + "/receipt.json", root + "/candidate-compensation-locks/" + b["candidateId"] + ".lock"


def claim_path(b):
    return (b["layout"]["remote"]["stateRoot"] + "/candidate-compensation-claims/"
            + b["taskId"] + "/" + b["candidateId"] + ".json")


def read_claim(b, created_at):
    path = claim_path(b)
    existing_parents(path)
    if not os.path.lexists(path):
        return None
    parents(path)
    owned(path)
    raw = bounded_read(path, 16384)
    value = json.loads(raw)
    exact(value, ("formatVersion", "binding", "bindingSha256", "originalExecutionCreatedAtEpochMillis", "observationSha256", "state"))
    require(raw == canonical(value) + b"\n" and type(value["formatVersion"]) is int and value["formatVersion"] == 1
            and value["state"] == "CLAIMED" and canonical(value["binding"]) == canonical(b)
            and value["bindingSha256"] == digest(canonical(b))
            and type(value["originalExecutionCreatedAtEpochMillis"]) is int
            and value["originalExecutionCreatedAtEpochMillis"] == created_at
            and type(value["observationSha256"]) is str
            and re.fullmatch("[0-9a-f]{64}", value["observationSha256"]), "candidate-claim-conflict")
    return value


def read_receipt(b):
    directory, path, _ = receipt_paths(b)
    existing_parents(path)
    if not os.path.lexists(path):
        if os.path.lexists(directory):
            secure_directory(directory)
        return None
    secure_directory(directory)
    owned(path)
    raw = bounded_read(path, 16384)
    value = json.loads(raw)
    exact(value, ("formatVersion", "binding", "bindingSha256", "state", "observationSha256", "protectedSha256", "originalExecutionCreatedAtEpochMillis"))
    require(raw == canonical(value) + b"\n" and type(value["formatVersion"]) is int and value["formatVersion"] == 2
            and canonical(value["binding"]) == canonical(b)
            and value["bindingSha256"] == digest(canonical(b))
            and type(value["originalExecutionCreatedAtEpochMillis"]) is int
            and value["originalExecutionCreatedAtEpochMillis"] > 0
            and value["state"] in ("STARTED", "SUCCEEDED", "UNKNOWN")
            and type(value["observationSha256"]) is str and type(value["protectedSha256"]) is str
            and re.fullmatch("[0-9a-f]{64}", value["observationSha256"])
            and re.fullmatch("[0-9a-f]{64}", value["protectedSha256"]), "receipt-binding")
    return value


def write_receipt(path, value, first=False):
    data = canonical(value) + b"\n"
    if first:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as destination:
            destination.write(data)
            destination.flush()
            os.fsync(destination.fileno())
    else:
        temporary = path + ".next"
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as destination:
            destination.write(data)
            destination.flush()
            os.fsync(destination.fileno())
        os.replace(temporary, path)
    sync_directory(os.path.dirname(path))


def group_members(child, identity):
    # Keep the direct child unreaped until the group is verified. Its occupied
    # PID prevents the original session/group ID being recycled into unrelated work.
    leader = process_stat(child.pid)
    require(leader["start"] == identity["start"] and leader["group"] == child.pid
            and leader["session"] == child.pid, "owned-process-identity")
    members = []
    for item in os.listdir("/proc"):
        if not item.isdecimal():
            continue
        try:
            value = process_stat(int(item))
        except FileNotFoundError:
            continue
        if value["group"] == child.pid and value["session"] == child.pid and value["state"] != "Z":
            members.append((value["pid"], value["start"]))
    return members


def stop_owned_group(child, identity):
    for sig, grace in ((signal.SIGTERM, 1.0), (signal.SIGKILL, 2.0)):
        members = group_members(child, identity)
        if not members:
            return
        os.killpg(child.pid, sig)
        expires = time.monotonic() + grace
        while time.monotonic() < expires:
            if not group_members(child, identity):
                return
            time.sleep(0.025)
    require(not group_members(child, identity), "owned-process-group-pending")


def invoke_cleanup(b):
    global DEADLINE
    helper = b["layout"]["remote"]["helperDirectory"] + "/managed-helper"
    expires = time.monotonic() + min(60, max(1, remaining() - 25))
    child = subprocess.Popen([helper, "candidate-cleanup-task", b["unitId"], b["candidateId"], b["taskId"]],
                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             start_new_session=True, env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C"})
    try:
        identity = process_stat(child.pid)
        require(identity["group"] == child.pid and identity["session"] == child.pid, "owned-process-identity")
        output = {child.stdout.fileno(): bytearray(), child.stderr.fileno(): bytearray()}
        with selectors.DefaultSelector() as selector:
            for stream in (child.stdout, child.stderr):
                os.set_blocking(stream.fileno(), False)
                selector.register(stream, selectors.EVENT_READ)
            while selector.get_map():
                remaining()
                require(time.monotonic() < expires, "new-cleanup-timeout")
                for key, _ in selector.select(min(0.25, max(0.001, expires - time.monotonic()))):
                    block = os.read(key.fd, 4096)
                    if not block:
                        selector.unregister(key.fileobj)
                        continue
                    output[key.fd].extend(block)
                    require(len(output[key.fd]) <= 8192, "new-cleanup-output-bound")
        require(not group_members(child, identity), "owned-process-group-pending")
        child.wait(timeout=min(2, remaining()))
        stdout, stderr = bytes(output[child.stdout.fileno()]), bytes(output[child.stderr.fileno()])
    except BaseException:
        previous_deadline = DEADLINE
        DEADLINE = time.monotonic() + 4
        try:
            if "identity" not in locals():
                identity = process_stat(child.pid)
            stop_owned_group(child, identity)
        finally:
            try:
                child.wait(timeout=2)
            finally:
                DEADLINE = previous_deadline
        raise
    finally:
        child.stdout.close()
        child.stderr.close()
    require(child.returncode == 0 and stdout == b"CANDIDATE_CLEANED=1\n" and len(stderr) <= 8192,
            "new-cleanup-unconfirmed")


def operate(request):
    global DEADLINE
    b = validate(request)
    mode = request["mode"]
    created_at = request["originalExecutionCreatedAtEpochMillis"]
    DEADLINE = time.monotonic() + (100 if mode == "execute" else 35)
    result = {"formatVersion": 2, "mode": mode, "taskId": b["taskId"], "operationId": b["operationId"],
              "bindingSha256": digest(canonical(b)), "status": "REFUSED", "reason": "not-observed",
              "candidatePresent": False, "quiescent": False, "observationSha256": "", "receiptSha256": "",
              "systemProcessExclusion": {}}
    started = False
    prior_recorded = False
    receipt = None
    try:
        require(os.geteuid() == 0, "root-required")
        # The permanent candidate claim is distinct from the transient lock and
        # from an operation receipt. Any occupied or damaged claim is query-only.
        prior_recorded = os.path.lexists(claim_path(b))
        claim = read_claim(b, created_at)
        prior_recorded = prior_recorded or claim is not None
        receipt = read_receipt(b)
        prior_recorded = prior_recorded or receipt is not None
        require(claim is None or receipt is not None, "candidate-claim-without-receipt")
        if mode == "receipt" and receipt is None:
            result.update(status="UNKNOWN", reason="receipt-absent")
            return result
        before = original_state(b, created_at)
        result.update(candidatePresent=before["present"], quiescent=True, observationSha256=before["sha"],
                      systemProcessExclusion=before["systemProcessExclusion"])
        if receipt is not None:
            require(claim is not None and receipt["originalExecutionCreatedAtEpochMillis"] == created_at
                    and receipt["observationSha256"] == claim["observationSha256"], "receipt-claim-conflict")
            result.update(receiptSha256=digest(canonical(receipt) + b"\n"))
            if receipt["state"] == "SUCCEEDED" and not before["present"] and receipt["protectedSha256"] == before["protected"]:
                result.update(status="SUCCEEDED", reason="terminal-receipt-verified")
            else:
                result.update(status="UNKNOWN", reason="original-new-action-unconfirmed")
            return result
        if mode == "observe":
            result.update(status="OBSERVED" if before["present"] else "ABSENT", reason="original-state-observed")
            return result
        require(before["present"] and before["sha"] == request["expectedObservationSha256"], "observation-changed")
        directory, path, lock_path = receipt_paths(b)
        roots = b["layout"]["remote"]["stateRoot"]
        for location in (roots + "/candidate-compensations", roots + "/candidate-compensations/" + b["taskId"],
                         directory, roots + "/candidate-compensation-locks", roots + "/candidate-compensation-claims",
                         roots + "/candidate-compensation-claims/" + b["taskId"]):
            secure_directory(location, True)
        lock_fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            owned(lock_path)
            fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            locked = os.fstat(lock_fd)
            current_lock = os.lstat(lock_path)
            require((locked.st_dev, locked.st_ino) == (current_lock.st_dev, current_lock.st_ino)
                    and locked.st_nlink == 1, "lock-identity-changed")
            os.fsync(lock_fd)
            sync_directory(os.path.dirname(lock_path))
            require(read_receipt(b) is None, "operation-already-recorded")
            require(read_claim(b, created_at) is None, "candidate-already-claimed")
            current = original_state(b, created_at)
            require(current["present"] and current["sha"] == before["sha"], "observation-changed")
            claim = {"formatVersion": 1, "binding": b, "bindingSha256": result["bindingSha256"], "state": "CLAIMED",
                     "originalExecutionCreatedAtEpochMillis": created_at, "observationSha256": current["sha"]}
            started = True
            write_receipt(claim_path(b), claim, True)
            receipt = {"formatVersion": 2, "binding": b, "bindingSha256": result["bindingSha256"], "state": "STARTED",
                       "observationSha256": current["sha"], "protectedSha256": current["protected"],
                       "originalExecutionCreatedAtEpochMillis": created_at}
            write_receipt(path, receipt, True)
            invoke_cleanup(b)
            after = original_state(b, created_at)
            require(not after["present"] and after["protected"] == current["protected"], "cleanup-postcondition")
            receipt["state"] = "SUCCEEDED"
            write_receipt(path, receipt)
            result.update(status="SUCCEEDED", reason="new-compensation-verified", candidatePresent=False,
                          observationSha256=after["sha"], receiptSha256=digest(canonical(receipt) + b"\n"))
        finally:
            os.close(lock_fd)
        return result
    except (Refusal, OSError, ValueError, KeyError, subprocess.SubprocessError):
        # Never put command output, argv, process data, paths or exception text in
        # the public result. A STARTED operation is query-only forever.
        import sys
        failure = sys.exc_info()[1]
        reason = str(failure) if isinstance(failure, Refusal) else "observation-unavailable"
        result.update(status="UNKNOWN" if started or prior_recorded else "REFUSED", reason=reason, quiescent=False)
        if started and receipt is not None:
            receipt["state"] = "UNKNOWN"
            try:
                write_receipt(receipt_paths(b)[1], receipt)
                result["receiptSha256"] = digest(canonical(receipt) + b"\n")
            except (OSError, Refusal):
                pass
        return result


def main(request):
    def cancelled(number, frame):
        raise Refusal("request-cancelled")
    previous = {number: signal.signal(number, cancelled) for number in (signal.SIGTERM, signal.SIGHUP)}
    try:
        result = operate(request)
        encoded = canonical(result)
        require(len(encoded) <= 8192, "result-bound")
        print(encoded.decode("utf-8"), flush=True)
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)
