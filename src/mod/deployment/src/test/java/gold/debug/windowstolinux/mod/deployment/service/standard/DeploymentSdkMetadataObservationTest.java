package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentSdkMetadataObservationTest {
    @Test
    void boundedMetadataObserverRetainsAbsenceAndRejectsUntrustedOrDriftingEvidence() throws Exception {
        var source = Path.of(getClass().getResource("sdk-metadata-observation.py").toURI());
        var process = new ProcessBuilder(System.getProperty("managed.python.executable", "python"), "-I", "-S", "-B", "-", source.toString())
            .redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write("""
                    import hashlib, json, os, pathlib, stat, sys, types
                    from contextlib import ExitStack
                    from unittest.mock import Mock, patch
                    def rejected(action):
                        try: action()
                        except ValueError: return
                        raise AssertionError('Untrusted evidence was accepted')
                    with ExitStack() as stack:
                        for index, flag in enumerate(('O_DIRECTORY', 'O_NOFOLLOW', 'O_CLOEXEC', 'O_NONBLOCK')):
                            stack.enter_context(patch.object(os, flag, 1 << (20 + index), create=True))
                        n = {'__name__': 'synthetic_observation'}
                        exec(compile(pathlib.Path(sys.argv[1]).read_bytes(), '<observer>', 'exec'), n)
                        for ecosystem, version in [('GO', '1.24.13'), ('NODE', '24.22.0')]:
                            digest = 'a' * 64
                            name = ecosystem.lower() + '-' + hashlib.sha256((ecosystem + '\\n' + version + '\\n' + digest).encode()).hexdigest()
                            value = dict(ecosystem=ecosystem, version=version, directory=n['ROOT'] + '/' + name,
                                         origin='MANAGED', source='https://example.invalid/?token=synthetic-private', sha256=digest,
                                         official_algorithm='sha256', official_digest=digest)
                            projected = n['record'](json.dumps(value), name)
                            assert projected['declaredBranchCompatible'] is True and 'source' not in projected
                            assert 'synthetic-private' not in json.dumps(projected)
                            rejected(lambda: n['record'](json.dumps(dict(value, unexpected='untrusted')), name))
                            rejected(lambda: n['record'](json.dumps(dict(value, directory='/foreign/slot')), name))
                        with patch.object(os, 'open', side_effect=FileNotFoundError):
                            assert n['marker'](123) is None
                        info = types.SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_gid=0, st_nlink=1, st_size=64)
                        n['trusted'](info, False)
                        info.st_mode |= 0o020
                        rejected(lambda: n['trusted'](info, False))
                        with patch.dict(n, scan=Mock(side_effect=[([], [], []), (['changed'], [], [])])):
                            rejected(lambda: n['stable_scan'](123))
                    print('SDK_METADATA_SYNTHETIC positive=2 absence=1 untrusted=5 drift=1')
                    """.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Synthetic observer must exit within its local bound");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertEquals("SDK_METADATA_SYNTHETIC positive=2 absence=1 untrusted=5 drift=1", output.strip());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        }
    }
    @Test
    void systemGoAndCapacityObserverBoundsLinksVersionAndFilesystemEvidence() throws Exception {
        var source = Path.of(getClass().getResource("system-go-capacity-observation.py").toURI());
        var process = new ProcessBuilder(System.getProperty("managed.python.executable", "python"), "-I", "-S", "-B", "-", source.toString())
            .redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write("""
                    import json, os, pathlib, posixpath, stat, sys, types
                    from contextlib import ExitStack
                    from unittest.mock import patch
                    def rejected(action):
                        try: action()
                        except ValueError: return
                        raise AssertionError('Untrusted or unbounded metadata was accepted')
                    with ExitStack() as stack:
                        for index, flag in enumerate(('O_DIRECTORY', 'O_NOFOLLOW', 'O_CLOEXEC', 'O_NONBLOCK', 'O_PATH')):
                            stack.enter_context(patch.object(os, flag, 1 << (20 + index), create=True))
                        n = {'__name__': 'synthetic_system_go_observation'}
                        exec(compile(pathlib.Path(sys.argv[1]).read_bytes(), '<observer>', 'exec'), n)
                        class FakeOS:
                            O_RDONLY = os.O_RDONLY
                            O_DIRECTORY, O_NOFOLLOW, O_CLOEXEC, O_NONBLOCK, O_PATH = (getattr(os, key) for key in
                                ('O_DIRECTORY', 'O_NOFOLLOW', 'O_CLOEXEC', 'O_NONBLOCK', 'O_PATH'))
                            def __init__(self):
                                self.nodes, self.handles, self.nextfd, self.reads, self.samples = {}, {}, 10, [], 0
                                for path in ('/', '/usr', '/usr/local', '/usr/local/bin', '/usr/bin', '/usr/local/go', '/usr/local/go/bin'):
                                    self.add(path, stat.S_IFDIR | 0o755)
                                self.add('/bin', stat.S_IFLNK | 0o777, target='usr/bin')
                                self.add('/usr/bin/go', stat.S_IFLNK | 0o777, target='../local/go/bin/go')
                                self.add('/usr/local/go/bin/go', stat.S_IFREG | 0o755, size=123)
                                self.add('/usr/local/go/VERSION', stat.S_IFREG | 0o644, data=b'go1.24.0\\nPRIVATE_OTHER_PAYLOAD\\n')
                            def add(self, path, mode, target=None, data=b'', size=None):
                                info = types.SimpleNamespace(st_dev=1, st_ino=len(self.nodes) + 1, st_mode=mode, st_uid=0, st_gid=0,
                                    st_nlink=1, st_size=len(data) if size is None else size, st_mtime_ns=1, st_ctime_ns=1)
                                self.nodes[path] = (info, target, data)
                            def path(self, name, dir_fd=None):
                                return posixpath.normpath(name if name.startswith('/') else posixpath.join(self.handles[dir_fd][0], name))
                            def stat(self, name, dir_fd=None, follow_symlinks=False):
                                path = self.path(name, dir_fd)
                                if path not in self.nodes: raise FileNotFoundError(path)
                                return self.nodes[path][0]
                            def open(self, name, flags, dir_fd=None):
                                path = self.path(name, dir_fd); self.stat(path)
                                assert not stat.S_ISLNK(self.nodes[path][0].st_mode)
                                if path.endswith('/bin/go'): assert flags & self.O_PATH, 'SDK binary must never be opened for reading'
                                self.nextfd += 1; self.handles[self.nextfd] = [path, 0]
                                return self.nextfd
                            def fstat(self, fd): return self.nodes[self.handles[fd][0]][0]
                            def readlink(self, name, dir_fd=None): return self.nodes[self.path(name, dir_fd)][1]
                            def close(self, fd): del self.handles[fd]
                            def read(self, fd, count):
                                path, offset = self.handles[fd]; assert path.endswith('/VERSION')
                                self.reads.append(path); data = self.nodes[path][2][offset:offset + count]
                                self.handles[fd][1] += len(data); return data
                            def fstatvfs(self, fd):
                                self.samples += 1
                                return types.SimpleNamespace(f_fsid=(1 << 63) + 7, f_frsize=4096, f_bavail=10 + self.samples % 2)
                        fake = FakeOS(); n['os'] = fake
                        observer = n['Observer']()
                        try:
                            absent = observer.go('/usr/local/bin/go'); assert absent['state'] == 'ABSENT_AT_RESOLVED_PATH'
                            linked = observer.go('/bin/go')
                            assert linked['resolvedPath'] == '/usr/local/go/bin/go' and linked['version']['text'] == 'go1.24.0'
                            assert 'PRIVATE_OTHER_PAYLOAD' not in json.dumps(linked) and fake.reads
                            measured = n['capacity'](observer.root)
                            assert measured['fsid'] == '0x8000000000000007'
                            assert measured['freeBytesSamples'] == [45056, 40960] and measured['freeBytesMinimum'] == 40960
                            rejected(lambda: observer.resolve('/' + '/'.join(['a'] * 129)))
                            rejected(lambda: n['version_declaration'](b'go1.24.0;private'))
                            rejected(lambda: n['long_value']((1 << 63)))
                            observer.verify()
                            fake.nodes['/usr/local/go/bin/go'][0].st_ctime_ns = 2
                            rejected(observer.verify)
                        finally: observer.close()
                        fake = FakeOS(); n['os'] = fake
                        observer = n['Observer']()
                        try:
                            observer.resolve('/bin/go')
                            info, target, data = fake.nodes['/usr/bin/go']
                            fake.nodes['/usr/bin/go'] = (info, '/changed', data)
                            rejected(observer.verify)
                        finally: observer.close()
                        for links in (1, 16, 17):
                            fake = FakeOS(); n['os'] = fake
                            for index in range(links):
                                name = '/usr/local/bin/go' if index == 0 else '/usr/local/bin/link' + str(index)
                                target = 'go' if links == 1 else ('link' + str(index + 1) if index < links - 1 else '/usr/local/go/bin/go')
                                fake.add(name, stat.S_IFLNK | 0o777, target=target)
                            observer = n['Observer']()
                            try:
                                if links == 16: assert observer.resolve('/usr/local/bin/go')['path'] == '/usr/local/go/bin/go'
                                else: rejected(lambda: observer.resolve('/usr/local/bin/go'))
                            finally: observer.close()
                        fake = FakeOS(); n['os'] = fake
                        fake.nodes['/usr/local/go/VERSION'][0].st_nlink = 2
                        observer = n['Observer']()
                        try: assert observer.go('/usr/bin/go')['version']['state'] == 'UNCONFIRMED'
                        finally: observer.close()
                        fake = FakeOS(); n['os'] = fake
                        roots = {key: '/usr/local/missing/leaf' for key in n['ROOT_FIELDS']}
                        observed = n['observe'](roots)
                        assert all(row['exists'] is False and row['observedExistingAncestor'] == '/usr/local'
                            and row['requested'] == '/usr/local/missing/leaf' and row['freeBytesMinimum'] == 40960 for row in observed['capacities'])
                        assert len(observed['filesystemGroups']) == 1 and not fake.handles
                        fake = FakeOS(); n['os'] = fake
                        fake.nodes['/usr/bin'][0].st_mode |= 0o022
                        fake.fstatvfs = lambda fd: types.SimpleNamespace(f_fsid=1, f_frsize=1 << 62, f_bavail=4)
                        observed = n['observe'](roots)
                        assert observed['go'][1]['state'] == 'UNCONFIRMED'
                        assert all(row['state'] == 'UNCONFIRMED' and 'freeBytesMinimum' not in row for row in observed['capacities'])
                        assert not fake.handles and observed['sdkExecutions'] == 0
                    print('SYSTEM_GO_CAPACITY_SYNTHETIC_OK')
                    """.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Synthetic observer must exit within its local bound");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertEquals("SYSTEM_GO_CAPACITY_SYNTHETIC_OK", output.strip());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        }
    }
    @Test
    void blockFilesystemObserverBoundsVendorMetadataAndPreservesUnknown() throws Exception {
        var resolver = Path.of(getClass().getResource("system-go-capacity-observation.py").toURI());
        var observer = Path.of(getClass().getResource("block-filesystem-layout-observation.py").toURI());
        var process = new ProcessBuilder(System.getProperty("managed.python.executable", "python"), "-I", "-S", "-B", "-", resolver.toString(), observer.toString())
            .redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write("""
                    import json, os, pathlib, stat, sys, types
                    from contextlib import ExitStack
                    from unittest.mock import Mock, patch
                    def rejected(action):
                        try: action()
                        except (ValueError, KeyError): return
                        raise AssertionError('Invalid vendor metadata was accepted')
                    with ExitStack() as stack:
                        for index, flag in enumerate(('O_DIRECTORY', 'O_NOFOLLOW', 'O_CLOEXEC', 'O_NONBLOCK', 'O_PATH', 'ST_RDONLY')):
                            stack.enter_context(patch.object(os, flag, 1 << (20 + index), create=True))
                        n = {'__name__': 'synthetic_block_observation'}
                        for path in sys.argv[1:]: exec(compile(pathlib.Path(path).read_bytes(), '<public-observer>', 'exec'), n)
                        disk = dict(name='vda', kname='vda', pkname=None, **{'maj:min': '252:0'}, size=1073741824, type='disk', ro=False, serial='PRIVATE_SERIAL')
                        part = dict(name='vda1', kname='vda1', pkname='vda', **{'maj:min': '252:1'}, size=536870912, type='part', ro=False, children=[disk])
                        root = dict(name='vg-root', kname='dm-1', pkname='vda1', **{'maj:min': '253:1'}, size=268435456, type='lvm', ro=False, children=[part])
                        topology = n['block_topology']({'blockdevices': [root]})
                        selected = n['unique_physical_disk'](topology)
                        assert selected['kname'] == 'vda' and 'PRIVATE_SERIAL' not in json.dumps(topology)
                        geometry = {'partitiontable': dict(device='/dev/vda', unit='sectors', sectorsize=512, label='gpt', id='PRIVATE_GUID',
                            partitions=[dict(node='/dev/vda1', start=2048, size=100000, uuid='PRIVATE_UUID', name='PRIVATE_LABEL')])}
                        projected = n['partition_projection'](geometry, selected)
                        assert projected['uncoveredByListedPartitionBytes'] == 1022541824 and 'PRIVATE_' not in json.dumps(projected)
                        mount = n['mount_projection']({'filesystems': [{'target': '/', 'fstype': 'ext4', 'maj:min': '253:1', 'options': 'PRIVATE_OPTIONS'}]}, '/captured/state')
                        assert 'PRIVATE_OPTIONS' not in json.dumps(mount)
                        for data in (b'{"x":1,"x":2}', b'{"x":1} {}', b'{"x":NaN}', b'{"x":1.5}', b' ' * 65537):
                            rejected(lambda data=data: n['strict_json'](data))
                        rejected(lambda: n['integer'](1 << 63))
                        rejected(lambda: n['block_topology']({'blockdevices': [dict(root, kname='dm-1;id')]}))
                        rejected(lambda: n['block_topology']({'blockdevices': [dict(root, children=[root])]}))
                        rejected(lambda: n['partition_projection']({'partitiontable': dict(geometry['partitiontable'], sectorsize=None)}, selected))
                        rejected(lambda: n['partition_projection']({'partitiontable': dict(geometry['partitiontable'], partitions=[dict(node='/dev/vda1', start=(1 << 62), size=2)])}, selected))
                        rejected(lambda: n['unique_physical_disk'](n['block_topology']({'blockdevices': [dict(root, children=[])]})))
                        sample = types.SimpleNamespace(f_fsid=(1 << 63) + 7, f_frsize=4096, f_flag=0, f_blocks=100, f_bfree=30, f_bavail=20)
                        measured = n['filesystem_projection'](sample, types.SimpleNamespace(**dict(vars(sample), f_bavail=19)), 64769)
                        assert measured['availableBytesMinimum'] == 77824 and measured['samples'][0]['totalBytes'] == 409600
                        rejected(lambda: n['filesystem_projection'](sample, types.SimpleNamespace(**dict(vars(sample), f_blocks=1 << 62)), 64769))
                        binding_info = types.SimpleNamespace(st_mode=stat.S_IFBLK | 0o660, st_uid=0, st_gid=6, st_rdev=64769,
                            st_dev=2, st_ino=3, st_nlink=1, st_size=0, st_mtime_ns=1, st_ctime_ns=1)
                        directory = types.SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_dev=64769)
                        fake = types.SimpleNamespace(resolve=lambda path: dict(exists=True, fd=7, info=directory), fds=[], entries=[], verify=Mock(), close=Mock())
                        fake_os = types.SimpleNamespace(O_PATH=1, O_NOFOLLOW=2, O_CLOEXEC=4, ST_RDONLY=os.ST_RDONLY, open=Mock(return_value=9),
                            stat=Mock(return_value=binding_info), fstat=Mock(return_value=binding_info), makedev=lambda major, minor: major * 256 + minor)
                        with patch.dict(n, os=fake_os):
                            bindings = []; n['block_binding'](fake, 'dm-1', '253:1', bindings)
                            assert bindings == [(9, 7, 'dm-1', 64769)]
                        fake_os.fstat = lambda fd: directory
                        fake_os.fstatvfs = lambda fd: sample
                        with patch.dict(n, os=fake_os, Observer=lambda: fake, root_alias=Mock(side_effect=ValueError), utility_json=Mock(side_effect=ValueError)):
                            unknown = n['block_observation']('/captured/state')
                        assert all(unknown[key]['state'] == 'UNKNOWN' and 'byteSize' not in unknown[key] for key in ('mount', 'topology', 'partitionTable'))
                        assert unknown['filesystem']['samples'][0]['availableBytes'] == 81920 and fake.close.call_count == 1
                    print('BLOCK_FILESYSTEM_METADATA_SYNTHETIC_OK')
                    """.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Synthetic protocol must exit within its local bound");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertEquals("BLOCK_FILESYSTEM_METADATA_SYNTHETIC_OK", output.strip());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        }
    }
}
