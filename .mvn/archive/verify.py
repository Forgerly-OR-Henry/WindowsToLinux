"""Verify the frozen workspace independently of the active build. / 独立于活动构建核对冻结工作区。"""

import hashlib
from pathlib import Path


def verify(root):
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


if __name__ == "__main__":
    verify(Path(__file__).resolve().parents[2])
