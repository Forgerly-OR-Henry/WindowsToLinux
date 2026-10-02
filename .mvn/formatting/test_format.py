"""Regression checks for scope and read-only formatting. / 范围和只读检查的回归验证。"""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("formatter", Path(__file__).with_name("format.py"))
formatter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(formatter)


class FormattingContractTest(unittest.TestCase):
    def test_root_examples_are_excluded_but_module_tests_remain(self):
        for name in (
            "src/app/main/src/main/java/Main.java",
            "src/app/main/src/test/java/MainTest.java",
            "src/mod/deployment/src/test/java/DeploymentTest.java",
            "src/app/main/src/test/browser/verify-business.mjs",
            "pom.xml",
        ):
            self.assertTrue(formatter.in_scope(name), name)
        for name in (
            "test/java/Main.java",
            "test/multi-language/demo/build.gradle",
            "src/mod/deployment/src/main/resources/helper.sh",
            "src/app/main/src/test/python/verify.py",
            "src/app/main/target/generated-sources/Main.java",
            "src/app/main/src/test/browser/package-lock.json",
            "history-src/src/main/java/Main.java",
            "src/mod/deployment/src/test/resources/original-codec-goldens.properties",
        ):
            self.assertFalse(formatter.in_scope(name), name)

    def test_failed_engine_cannot_hide_source_mutation(self):
        formatter.CACHE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=formatter.CACHE) as directory:
            source = Path(directory) / "Probe.java"
            source.write_bytes(b"class Probe {}\n")

            def broken_engine(paths):
                source.write_bytes(b"changed\n")
                raise RuntimeError("engine failed")

            with patch.object(formatter, "idea", broken_engine):
                with self.assertRaisesRegex(RuntimeError, "Sources changed during check"):
                    formatter.format_paths([source], False)

    def test_check_reports_line_endings_without_rewriting(self):
        formatter.CACHE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=formatter.CACHE) as directory:
            source = Path(directory) / "Probe.java"
            source.write_bytes(b"class Probe {}\r\n")
            with patch.object(formatter, "idea"):
                with self.assertRaisesRegex(RuntimeError, "Files need formatting"):
                    formatter.format_paths([source], False)
            self.assertEqual(b"class Probe {}\r\n", source.read_bytes())

    def test_check_formats_only_copies_and_cleans_them(self):
        formatter.CACHE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=formatter.CACHE) as directory:
            source = Path(directory) / "Probe.java"
            source.write_bytes(b"class Probe{}\n")
            staged = []

            def engine(paths):
                self.assertNotEqual(source, paths[0])
                self.assertEqual(source.read_bytes(), paths[0].read_bytes())
                paths[0].write_bytes(b"class Probe {}\n")
                staged.extend(paths)

            with patch.object(formatter, "idea", engine):
                with self.assertRaisesRegex(RuntimeError, "Files need formatting"):
                    formatter.format_paths([source], False)
            self.assertEqual(b"class Probe{}\n", source.read_bytes())
            self.assertFalse(staged[0].exists())

    def test_write_preserves_concurrent_edit(self):
        formatter.CACHE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=formatter.CACHE) as directory:
            source = Path(directory) / "Probe.java"
            source.write_bytes(b"class Probe{}\n")

            def engine(paths):
                paths[0].write_bytes(b"class Probe {}\n")
                source.write_bytes(b"class Probe { int newField; }\n")

            with patch.object(formatter, "idea", engine):
                with self.assertRaisesRegex(RuntimeError, "Sources changed during formatting"):
                    formatter.format_paths([source], True)
            self.assertEqual(b"class Probe { int newField; }\n", source.read_bytes())


if __name__ == "__main__":
    unittest.main()
