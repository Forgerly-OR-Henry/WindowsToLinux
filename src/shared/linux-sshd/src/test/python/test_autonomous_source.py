"""Pure file boundary and patch checks; no SSH, model or privileged deployment."""

import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SOURCE = (
    Path(__file__).resolve().parents[2]
    / "main/resources/gold/debug/windowstolinux/shared/linux/sshd/workspace/source-operations.py"
)
SPEC = importlib.util.spec_from_file_location("protected_source", SOURCE)
OPS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OPS)


class AutonomousSourceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "input-source"
        self.source.mkdir()
        (self.source / "main.zig").write_text("old\nline two\n", encoding="utf-8")
        self.revision = OPS.tree_digest(self.source)
        self.proposal = {
            "path": "main.zig",
            "sourceRevision": self.revision,
            "beforeDigest": hashlib.sha256((self.source / "main.zig").read_bytes()).hexdigest(),
            "edits": [{"line": 1, "removed": ["old"], "added": ["new"]}],
        }

    def tearDown(self):
        for item in self.root.rglob("*"):
            if not item.is_symlink():
                item.chmod(0o700)
        self.temporary.cleanup()

    def test_exact_patch_changes_revision_and_preserves_original_snapshot(self):
        original = self.root / "original.zig"
        original.write_bytes((self.source / "main.zig").read_bytes())
        # Windows cannot fsync a directory; only that OS primitive is replaced in this pure-file test.
        with patch.object(OPS, "sync_directory") as sync:
            revised = OPS.apply_patch(self.source, self.proposal, self.revision)
            sync.assert_called_once_with(self.source)
        self.assertNotEqual(self.revision, revised)
        self.assertEqual("new\nline two\n", (self.source / "main.zig").read_text())
        self.assertEqual("old\nline two\n", original.read_text())
        with self.assertRaises(ValueError):
            OPS.apply_patch(self.source, self.proposal, self.revision)

    def test_preimage_conflict_and_line_conflict_make_no_change(self):
        for field, value in [
            ("beforeDigest", "0" * 64),
            ("sourceRevision", "0" * 64),
            ("edits", [{"line": 1, "removed": ["wrong"], "added": ["new"]}]),
        ]:
            proposal = dict(self.proposal, **{field: value})
            with self.assertRaises(ValueError):
                OPS.apply_patch(self.source, proposal, self.revision)
            self.assertEqual(self.revision, OPS.tree_digest(self.source))

    def test_traversal_protected_names_and_bad_schema_are_rejected(self):
        for name in ["../outside", "/absolute", "C:/outside", "sub\\outside", "./file", "a//b"]:
            with self.assertRaises(ValueError):
                OPS.relative(name)
        secret = self.source / ".env"
        secret.write_text("TOKEN=secret")
        revision = OPS.tree_digest(self.source)
        with self.assertRaises(ValueError):
            OPS.apply_patch(self.source, dict(self.proposal, path=".env", sourceRevision=revision), revision)
        for invalid in [None, [], {}, {"edits": None}]:
            with self.assertRaises(ValueError):
                OPS.apply_patch(self.source, invalid, revision)

    def test_links_cannot_escape_source(self):
        outside = self.root / "outside"
        outside.write_text("outside")
        try:
            os.link(outside, self.source / "linked")
        except OSError as error:
            self.skipTest(str(error))
        with self.assertRaises(ValueError):
            OPS.tree_digest(self.source)

    def test_paged_reads_preserve_line_numbers_and_redact_multiline_keys(self):
        file = self.source / "main.zig"
        file.write_text(
            "safe\n-----BEGIN PRIVATE KEY-----\nbody-secret\n-----END PRIVATE KEY-----\npassword=never-send\nlast\n"
        )
        output = OPS.read_text(self.source, "main.zig", 2, 4)
        self.assertIn("3: [key excluded]", output)
        self.assertNotIn("body-secret", output)
        self.assertNotIn("never-send", output)
        self.assertIn("6: last", output)

    def test_archive_digest_and_entry_types_are_verified_before_source_use(self):
        for name, kind in [("../escape", tarfile.REGTYPE), ("link", tarfile.SYMTYPE), ("hard", tarfile.LNKTYPE)]:
            archive = self.root / "source.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                member = tarfile.TarInfo(name)
                member.type = kind
                member.linkname = "outside" if kind != tarfile.REGTYPE else ""
                member.size = 1 if kind == tarfile.REGTYPE else 0
                bundle.addfile(member, io.BytesIO(b"x") if member.size else None)
            destination = self.root / ("extract-" + str(kind[0]))
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            with self.assertRaises(ValueError):
                OPS.extract(archive, destination, digest)
        self.assertFalse((self.root / "escape").exists())


if __name__ == "__main__":
    unittest.main()
