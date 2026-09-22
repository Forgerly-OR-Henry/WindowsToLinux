"""Protected task-source operations; no project-defined code runs as root."""

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tarfile
import tempfile

MAX_FILE = 262144
MAX_MEMBERS = 20000


def relative(name):
    if not isinstance(name, str) or not name or len(name) > 500 or "\\" in name or ":" in name:
        raise ValueError("source path")
    parts = name.split("/")
    if any(part in ("", ".", "..") or any(ord(c) < 32 for c in part) for part in parts):
        raise ValueError("source traversal")
    return PurePosixPath(name)


def safe_member(root, name):
    path = root
    for part in relative(name).parts:
        path = path / part
        if path.is_symlink():
            raise ValueError("source symlink")
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise ValueError("source not a private regular file")
    return path


def tree_digest(root):
    digest = hashlib.sha256()
    members = sorted(root.rglob("*"), key=lambda p: p.relative_to(root).as_posix())
    if len(members) > MAX_MEMBERS * 2:
        raise ValueError("source member limit")
    count = 0
    for path in members:
        info = path.lstat()
        if stat.S_ISDIR(info.st_mode):
            continue
        name = path.relative_to(root).as_posix()
        safe_member(root, name)
        count += 1
        if count > MAX_MEMBERS:
            raise ValueError("source file limit")
        encoded = name.encode("utf-8")
        digest.update(str(len(encoded)).encode() + b":" + encoded + b":" + str(info.st_size).encode() + b":")
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(65536), b""):
                digest.update(chunk)
    if count == 0:
        raise ValueError("empty source or artifact")
    return digest.hexdigest()


def extract(archive, root, expected):
    archive_hash = hashlib.sha256()
    with archive.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            archive_hash.update(chunk)
    actual = archive_hash.hexdigest()
    if actual != expected or root.exists() or root.is_symlink():
        raise ValueError("archive identity or existing source")
    root.mkdir(mode=0o700)
    seen = set()
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle:
            if not member.isfile() or member.name in seen or len(seen) >= MAX_MEMBERS:
                raise ValueError("archive member type or duplicate")
            name = relative(member.name)
            seen.add(member.name)
            destination = root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            with bundle.extractfile(member) as source, destination.open("xb") as output:
                copied = 0
                for chunk in iter(lambda: source.read(65536), b""):
                    copied += len(chunk)
                    if copied > member.size:
                        raise ValueError("archive size mismatch")
                    output.write(chunk)
                if copied != member.size:
                    raise ValueError("archive truncated")
            destination.chmod(0o444)
    for path in sorted(root.rglob("*"), reverse=True):
        if path.is_dir():
            path.chmod(0o555)
    root.chmod(0o555)
    return tree_digest(root)


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate patch field")
        result[key] = value
    return result


def apply_patch(root, patch, expected_revision):
    if not isinstance(patch, dict) or set(patch) != {"path", "beforeDigest", "sourceRevision", "edits"}:
        raise ValueError("patch schema")
    if patch["sourceRevision"] != expected_revision or tree_digest(root) != expected_revision:
        raise ValueError("source revision conflict")
    path = safe_member(root, patch["path"])
    for part in path.relative_to(root).parts:
        lower = part.lower()
        if (
            lower in {".git", ".ssh", ".env", ".npmrc", ".pypirc", ".w2l"}
            or lower.startswith(".env.")
            or lower.endswith((".pem", ".key", ".p12", ".pfx"))
        ):
            raise ValueError("protected source path")
    if path.stat().st_size > MAX_FILE:
        raise ValueError("patch file limit")
    original = path.read_bytes()
    if hashlib.sha256(original).hexdigest() != patch["beforeDigest"]:
        raise ValueError("patch digest conflict")
    text = original.decode("utf-8", errors="strict")
    if "\0" in text:
        raise ValueError("binary patch")
    lines = text.splitlines()
    edits = patch["edits"]
    if not isinstance(edits, list) or not 1 <= len(edits) <= 64:
        raise ValueError("patch edit limit")
    result = []
    consumed = 0
    previous = -1
    for edit in edits:
        if not isinstance(edit, dict) or set(edit) != {"line", "removed", "added"} or type(edit["line"]) is not int:
            raise ValueError("line edit schema")
        start = edit["line"] - 1
        removed, added = edit["removed"], edit["added"]
        if not isinstance(removed, list) or not isinstance(added, list) or not 1 <= len(removed) + len(added) <= 200:
            raise ValueError("line edit bounds")
        if any(
            not isinstance(line, str) or len(line) > 4000 or any(c in line for c in "\n\r\0")
            for line in removed + added
        ):
            raise ValueError("invalid patch line")
        if (
            start < consumed
            or start <= previous
            or start > len(lines)
            or lines[start : start + len(removed)] != removed
        ):
            raise ValueError("patch line conflict")
        result.extend(lines[consumed:start])
        result.extend(added)
        consumed = start + len(removed)
        previous = start
    result.extend(lines[consumed:])
    separator = "\r\n" if "\r\n" in text else "\n"
    updated = (separator.join(result) + (separator if text.endswith("\n") else "")).encode("utf-8")
    if updated == original or len(updated) > MAX_FILE:
        raise ValueError("empty or oversized patch")
    descriptor, temporary = tempfile.mkstemp(prefix=".wtl-patch-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(updated)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, stat.S_IMODE(path.stat().st_mode))
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return tree_digest(root)


def sync_directory(path):
    directory = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def read_text(root, name, offset, limit):
    path = safe_member(root, name)
    if path.stat().st_size > MAX_FILE or not 0 <= offset <= 1000000 or not 1 <= limit <= 100:
        raise ValueError("source read bounds")
    value = path.read_bytes()
    text = value.decode("utf-8", errors="strict")
    if "\0" in text:
        raise ValueError("binary source")
    lines = []
    redacted = []
    private_block = False
    for line in text.splitlines():
        if re.search(r"-----BEGIN .*PRIVATE KEY-----", line):
            private_block = True
        redacted.append("[key excluded]" if private_block else line)
        if re.search(r"-----END .*PRIVATE KEY-----", line):
            private_block = False
    for i, line in enumerate(redacted[offset : offset + limit], offset + 1):
        if re.search(r"(?i)(password|passwd|secret|token|api[_-]?key|private[_-]?key).*[:=]", line):
            line = "[credential content excluded]"
        line = re.sub(r"(https?://)[^\s/@:]+:[^\s/@]+@", r"\1[credentials excluded]@", line)
        lines.append(str(i) + ": " + line[:4000])
    return "FILE_SHA256=" + hashlib.sha256(value).hexdigest() + "\n" + "\n".join(lines)[:12000]


def main():
    if os.geteuid() != 0:
        raise ValueError("source controller requires root")
    mode, directory, expected, *args = sys.argv[1:]
    root = Path(directory)
    if not re.fullmatch(
        r"/var/lib/windowstolinux/work/[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}/mutable/(input-source|source)", directory
    ):
        raise ValueError("task source root")
    for parent in (root, *root.parents):
        if parent.is_symlink():
            raise ValueError("task parent symlink")
    if mode == "open":
        print("SOURCE_REVISION=" + extract(root.parent / "source.tar.gz", root, expected))
    elif mode == "digest":
        print("DIGEST=" + tree_digest(root))
    else:
        if tree_digest(root) != expected:
            raise ValueError("source revision conflict")
        if mode == "read":
            print(read_text(root, args[0], int(args[1]), int(args[2])))
        elif mode == "patch":
            raw = sys.stdin.buffer.read(65537)
            if len(raw) > 65536:
                raise ValueError("patch payload limit")
            patch = json.loads(raw.decode("utf-8"), object_pairs_hook=no_duplicates)
            print("SOURCE_REVISION=" + apply_patch(root, patch, expected))
        else:
            raise ValueError("source operation")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, UnicodeError, tarfile.TarError):
        print("SOURCE_REJECT=invalid-or-conflicting-source", file=sys.stderr)
        sys.exit(64)
