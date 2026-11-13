"""Exercise fixed Rust archive admission without invoking any vendor executable."""
import contextlib
import hashlib
import json
import os
import pathlib
import re
import shutil
import stat
import tempfile
import unittest
from unittest import mock


FRAGMENTS = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain'


class PreparationFailure(Exception):
    def __init__(self, category, detail):
        self.category, self.detail = category, detail
        super().__init__(detail)


class RustDistributionTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = pathlib.Path(tmp.name)
        self.versions = self.root / 'versions'
        self.versions.mkdir()
        self.dist = self.root / 'fixture'
        self.dist.mkdir()
        self.components = ('rustc', 'rust-std-x86_64-unknown-linux-gnu', 'cargo')
        (self.dist / 'rust-installer-version').write_bytes(b'3\n')
        (self.dist / 'components').write_bytes(('\n'.join(self.components) + '\nrust-docs\n').encode())
        (self.dist / 'install.sh').write_bytes(b'fixture-data-never-executed\n')
        for component, directives in (
                ('rustc', ('file:bin/rustc', 'file:bin/rustdoc')),
                (self.components[1], ('dir:lib/rustlib/x86_64-unknown-linux-gnu/lib',)),
                ('cargo', ('file:bin/cargo',))):
            base = self.dist / component
            base.mkdir()
            (base / 'manifest.in').write_bytes(('\n'.join(directives) + '\n').encode())
            for directive in directives:
                kind, _, name = directive.partition(':')
                target = base / name
                target.parent.mkdir(parents=True, exist_ok=True)
                if kind == 'file':
                    target.write_bytes(b'fixed fixture')
                else:
                    target.mkdir()
                    (target / 'libstd-fixture.rlib').write_bytes(b'fixed fixture')
        self.version = '1.95.0'
        self.digest = 'a' * 64
        self.url = 'https://static.rust-lang.org/dist/fixture'
        self.key = hashlib.sha256(('RUST\n' + self.version + '\n' + self.digest).encode()).hexdigest()
        self.probes = []
        self.published = []
        self.ns = dict(contextlib=contextlib, pathlib=pathlib, json=json, os=os, re=re, shutil=shutil,
                       tempfile=tempfile, stat=stat, ROOT=self.root, installation_key=mock.Mock(return_value=self.key),
                       toolchain_point=lambda _: 'RUST_TOOLCHAIN', saved_installation_marker=self.marker,
                       owned_directory=mock.Mock(), download=mock.Mock(return_value=self.digest), extract=self.extract,
                       create_installation_directory=self.create, tracked_regular=lambda path, _: path.read_bytes(),
                       tracked_atomic_json=self.record, probe=self.probe, probe_output=mock.Mock(),
                       release=mock.Mock(), PreparationFailure=PreparationFailure, record_installation_diagnostic=mock.Mock(),
                       install=mock.Mock(side_effect=AssertionError('vendor install invoked')),
                       fail=self.fail_with_category)
        exec(compile((FRAGMENTS / 'rust-distribution.py').read_bytes(), 'rust-distribution.py', 'exec'), self.ns)

    @staticmethod
    def fail_with_category(category, message):
        raise ValueError(category + ': ' + message)

    def marker(self, destination):
        marker = destination / '.w2l-toolchain.json'
        return marker if marker.exists() else None

    @staticmethod
    @contextlib.contextmanager
    def create(destination, tracked):
        assert tracked is None
        destination.mkdir()
        yield

    def extract(self, archive, destination):
        root = destination / 'rust-1.95.0'
        shutil.copytree(self.dist, root)
        for binary in (root / 'rustc/bin/rustc', root / 'rustc/bin/rustdoc', root / 'cargo/bin/cargo'):
            if binary.exists():
                binary.chmod(0o755)

    def probe(self, ecosystem, directory, expected):
        self.probes.append((ecosystem, directory, expected))

    def record(self, marker, value, mode):
        self.published.append((marker, mode))
        marker.write_text(json.dumps(value), encoding='utf-8')

    def test_rust_transfer_failures_record_only_fixed_phase_and_route_reasons(self):
        release = self.ns['_previous_rust_distribution_release']
        recorder = self.ns['record_installation_diagnostic']
        failure = PreparationFailure('network', 'selected sources exhausted: http-503,private token,no-progress')
        release.side_effect = failure
        for exact, expected_stage in (('', 'rust-release-metadata'), (self.version, 'rust-release-checksum')):
            with self.subTest(exact=exact), self.assertRaises(PreparationFailure) as observed:
                self.ns['release']('RUST', '1.95', exact)
            self.assertIs(failure, observed.exception)
            diagnostic = recorder.call_args.args[0]
            self.assertEqual(expected_stage, diagnostic['stage'])
            self.assertEqual('mirror-point=RUST_METADATA\ntransfer-reasons=http-503,unclassified,no-progress\n',
                             diagnostic['stderr'])
            self.assertIsNone(diagnostic['actualExitCode'])
            self.assertFalse(diagnostic['timedOut'])
        recorder.reset_mock()
        with self.assertRaises(PreparationFailure):
            self.ns['release']('RUBY', '3.3', '')
        recorder.assert_not_called()
        self.ns['download'].side_effect = PreparationFailure('network', 'task deadline exceeded')
        with self.assertRaises(PreparationFailure):
            self.ns['install']('RUST', self.version, self.url, 'sha256', self.digest, 'rust')
        diagnostic = recorder.call_args.args[0]
        self.assertEqual('rust-archive-download', diagnostic['stage'])
        self.assertEqual('mirror-point=RUST_TOOLCHAIN\ntransfer-reasons=task-deadline\n', diagnostic['stderr'])
        self.assertTrue(diagnostic['timedOut'])
        self.assertFalse(self.published)
        self.assertFalse((self.versions / ('rust-' + self.key)).exists())
        self.assertEqual([], list((self.root / 'work').iterdir()))

    def test_installs_selected_regular_components_without_executing_installer_or_changing_binding_path(self):
        previous_mask = os.umask(0o077)
        self.addCleanup(os.umask, previous_mask)
        if os.name == 'nt':
            plan = self.ns['rust_component_plan']

            def with_posix_fixture_modes(source):
                entries, directories = plan(source)
                for binary in ('rustc', 'cargo', 'rustdoc'):
                    path, _ = entries[('bin', binary)]
                    entries[('bin', binary)] = (path, 0o755)
                return entries, directories

            self.ns['rust_component_plan'] = with_posix_fixture_modes
        def version_probe(argv):
            if os.name != 'nt':
                executable = pathlib.Path(argv[-2])
                for directory in (executable.parent, executable.parent.parent):
                    self.assertEqual(0o755, stat.S_IMODE(directory.stat().st_mode))
            return 0, 'rustc 1.95.0 (fixture)\n'

        self.ns['probe_output'].side_effect = version_probe
        record = self.ns['install']('RUST', self.version, self.url, 'sha256', self.digest, 'rust')
        destination = self.versions / ('rust-' + self.key)
        self.assertEqual(str(destination), record['directory'])
        self.assertTrue((destination / 'bin/cargo').is_file())
        self.assertTrue((destination / 'lib/rustlib/x86_64-unknown-linux-gnu/lib/libstd-fixture.rlib').is_file())
        self.assertFalse((destination / 'install.sh').exists())
        self.assertEqual([(destination / '.w2l-toolchain.json', 0o444)], self.published)
        argv = self.ns['probe_output'].call_args.args[0]
        self.assertEqual(['/usr/bin/setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', '--no-new-privs'],
                         argv[5:10])
        self.assertEqual(record, self.ns['install']('RUST', self.version, self.url, 'sha256', self.digest, 'rust'))
        self.ns['download'].assert_called_once()

    def test_compiler_and_standard_library_are_required_and_bulk_paths_are_bounded(self):
        entries, directories = self.ns['rust_component_plan'](self.dist)
        self.assertIn(('bin', 'rustdoc'), entries)
        self.assertIn(('lib', 'rustlib', 'x86_64-unknown-linux-gnu', 'lib'), directories)
        (self.dist / 'components').write_bytes(b'rustc\ncargo\n')
        with self.assertRaisesRegex(ValueError, 'missing its required components'):
            self.ns['rust_component_plan'](self.dist)

    def test_manifest_conflicts_and_nonliteral_paths_are_rejected(self):
        manifest = self.dist / 'cargo/manifest.in'
        for directive in (b'file:bin/../outside\n', b'file:/bin/cargo\n',
                          b'file:bin/cargo\nfile:bin/cargo\n', b'file:bin\\cargo\n',
                          b'file:bin/cargo\r\n', b'exec:bin/cargo\n'):
            with self.subTest(directive=directive):
                manifest.write_bytes(directive)
                with self.assertRaises(ValueError):
                    self.ns['rust_component_plan'](self.dist)

    def test_managed_probe_uses_fixed_non_root_identity_and_fails_on_wrong_release(self):
        self.ns['probe_output'].return_value = (0, 'rustc 1.95.1 (fixture)\n')
        with self.assertRaisesRegex(ValueError, 'exact release'):
            self.ns['probe']('RUST', self.versions / 'fixture', self.version)
        argv = self.ns['probe_output'].call_args.args[0]
        self.assertEqual(['/usr/bin/setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', '--no-new-privs'],
                         argv[5:10])
        self.assertEqual(str(self.versions / 'fixture/bin/rustc'), argv[-2])
        self.ns['probe']('RUST', pathlib.Path('/usr'), self.version)
        self.assertEqual([('RUST', pathlib.Path('/usr'), self.version)], self.probes)
