"""Verify the frozen workspace independently of the active build. / 独立于活动构建核对冻结工作区。"""

import hashlib
import argparse
import subprocess
from pathlib import Path


def verify(root, revision=None):
    """Check the exact archived file set and bytes. / 检查归档的完整文件集合与字节。"""
    archive = root / "history-src"
    manifest = archive / "ARCHIVE-MANIFEST.sha256"
    expected = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        digest, name = line.split("  ", 1)
        path = archive / name
        if path.is_symlink() or not path.resolve().is_relative_to(archive.resolve()):
            raise ValueError(f"Invalid archive path: {name}")
        expected[name] = digest
    actual = {p.relative_to(archive).as_posix() for p in archive.rglob("*") if p.is_file()}
    if actual != set(expected) | {manifest.name}:
        raise ValueError(f"Archive file set changed: {actual ^ (set(expected) | {manifest.name})}")
    for name, expected_digest in expected.items():
        with (archive / name).open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != expected_digest:
                raise ValueError(f"Archive bytes changed: {name}")
    print(f"Archive verified: {len(expected)} files")
    if revision:
        expected[manifest.name] = hashlib.sha256(manifest.read_bytes()).hexdigest()
        with subprocess.Popen(["git", "cat-file", "--batch"], cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
            for name, expected_digest in expected.items():
                process.stdin.write(f"{revision}:history-src/{name}\n".encode("utf-8"))
                process.stdin.flush()
                header = process.stdout.readline().split()
                if len(header) != 3 or header[1] != b"blob":
                    raise ValueError(f"Archive Git blob missing: {name}")
                data = process.stdout.read(int(header[2]))
                if process.stdout.read(1) != b"\n" or hashlib.sha256(data).hexdigest() != expected_digest:
                    raise ValueError(f"Archive Git blob changed: {name}")
            process.stdin.close()
            if process.wait() != 0:
                raise RuntimeError("Git archive verification failed")
        print(f"Git archive verified at {revision}: {len(expected)} blobs")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--git", metavar="REVISION")
    verify(Path(__file__).resolve().parents[2], parser.parse_args().git)
