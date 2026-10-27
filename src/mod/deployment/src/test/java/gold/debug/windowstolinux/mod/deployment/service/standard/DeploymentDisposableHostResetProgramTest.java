package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local filesystem and mocked OS boundaries; never invokes real apt or systemctl. */
class DeploymentDisposableHostResetProgramTest {
    @TempDir
    Path temporary;

    @Test
    void hashesRealMetadataWithoutReadingPayloadsAndRefusesPathMountAndRootSubstitution() throws Exception {
        verify("""
            owned = temporary / 'owned'
            outside = temporary / 'outside'
            owned.mkdir()
            outside.mkdir()
            (owned / 'private.env').write_bytes(b'private-canary')
            (outside / 'keep').write_bytes(b'outside-canary')
            with mock.patch.object(pathlib.Path, 'open', side_effect=AssertionError('payload read')):
                first = g['tree_metadata'](owned, set())
            assert first['entries'] == 2 and first['fileBytes'] == 14
            (owned / 'private.env').write_bytes(b'changed-canary-with-different-size')
            assert first['treeSha256'] != g['tree_metadata'](owned, set())['treeSha256']
            rejected('TARGET_CONTAINS_MOUNT', lambda: g['tree_metadata'](owned, {str(owned) + '/nested'}))
            rejected('TARGET_CONTAINS_MOUNT', lambda: g['tree_metadata'](owned, {str(owned)}))
            rejected('UNSAFE_PATH', lambda: g['safe_ancestors'](owned / '..' / 'outside'))
            rejected('UNPLANNED_ROOT', lambda: g['remove_root']({'path': str(outside), 'exists': True}))
            original_metadata = g['metadata']
            link = owned / 'link'
            def ancestor_metadata(path):
                value = original_metadata(path if pathlib.Path(path) != link else owned)
                return dict(value, uid=0, mode=0o755, type='symlink' if pathlib.Path(path) == link else value['type'])
            with mock.patch.dict(g, {'metadata': ancestor_metadata}):
                rejected('UNSAFE_ANCESTOR', lambda: g['safe_ancestors'](link / 'child'))
            # A real symlink is exercised when the host permits creating it; metadata/path negatives above always run.
            try:
                link.symlink_to(outside, target_is_directory=True)
            except OSError:
                pass
            else:
                assert g['tree_metadata'](owned, set())['entries'] == 3
                with mock.patch.dict(g, {'ROOTS': (str(link),), 'safe_ancestors': lambda path: None}):
                    rejected('UNSAFE_TARGET_ROOT', lambda: g['root_inventory'](set()))
            assert (outside / 'keep').read_bytes() == b'outside-canary'
            """);
    }

    @Test
    void aptSimulationAcceptsObservedUbuntuCompilerTripletsAndRejectsCoreAndUnreviewedRemovals() throws Exception {
        verify("""
            compiler_names = ['gcc', 'gcc-x86-64-linux-gnu', 'gcc-13-x86-64-linux-gnu',
                              'g++-x86-64-linux-gnu', 'cpp-x86-64-linux-gnu', 'binutils-x86-64-linux-gnu', 'cmake', 'libssl-dev']
            core_names = ['openssh-server', 'openssh-client', 'python3', 'python3-minimal',
                          'apt', 'dpkg', 'systemd', 'libc6', 'iproute2', 'sudo', 'ca-certificates',
                          'git', 'git-man', 'ubuntu-server', 'ubuntu-server-minimal']
            installed = [dict(name=name, version='1', essential=False) for name in compiler_names + core_names + ['foreign-app']]
            transcript = chr(10).join(('Remv ' if index % 2 else 'Purg ') + name + ' [1]'
                                      for index, name in enumerate(compiler_names))
            assert g['parse_simulation'](transcript, installed, compiler_names) == sorted(compiler_names)
            assert all(g['target_package'](name) for name in compiler_names)
            for name in core_names:
                rejected('APT_CORE_REMOVAL_REFUSED', lambda name=name: g['parse_simulation']('Remv ' + name + ' [1]', installed, []))
                assert g['core_package'](name) and not g['target_package'](name)
            rejected('APT_CORE_REMOVAL_REFUSED', lambda: g['parse_simulation']('Purg ubuntu-server [1]', installed, []))
            rejected('APT_UNREVIEWED_REMOVAL', lambda: g['parse_simulation']('Remv foreign-app [1]', installed, []))
            rejected('APT_UNEXPECTED_INSTALL', lambda: g['parse_simulation']('Inst foreign-app [1]', installed, []))
            rejected('APT_REQUEST_NOT_SIMULATED', lambda: g['parse_simulation']('Purg cmake [1]', installed, ['gcc']))
            essential = [dict(name='gcc', version='1', essential=True)]
            rejected('APT_CORE_REMOVAL_REFUSED', lambda: g['parse_simulation']('Purg gcc [1]', essential, []))
            assert not g['target_package']('gcc-malformed-extra-x86-64-linux-gnu')
            """);
    }

    @Test
    void inventoryIsReadOnlyAndPlanAloneSimulatesExactPackageRemoval() throws Exception {
        verify("""
            calls = []
            installed = [dict(name='gcc', version='13', essential=False),
                         dict(name='git', version='1', essential=False),
                         dict(name='git-man', version='1', essential=False),
                         dict(name='openssh-server', version='1', essential=False),
                         dict(name='ubuntu-server', version='1', essential=False),
                         dict(name='ubuntu-server-minimal', version='1', essential=False)]
            def read_command(arguments, **options):
                calls.append(arguments)
                if arguments[0] == '/usr/bin/dpkg-query':
                    return 0, chr(10).join(chr(9).join([value['name'], value['version'], 'installed', '']) for value in installed)
                assert arguments == ['/usr/bin/apt-get', '-s', 'purge', '--', 'gcc'], arguments
                return 0, 'Purg gcc [13]'
            original_bytes = pathlib.Path.read_bytes
            def machine_only(path):
                assert path.as_posix() == '/etc/machine-id', 'Unexpected content read'
                return b'a' * 32
            with mock.patch.object(pathlib.Path, 'read_bytes', machine_only), mock.patch.dict(g, {
                'command': read_command, 'mount_paths': lambda: set(), 'root_inventory': lambda mounts: [],
                'unit_inventory': lambda mounts: ([], [], [], {}), 'account_inventory': lambda: ([], []),
                'subordinate_inventory': lambda: [],
                'cache_inventory': lambda: dict(debFiles=0, partialFiles=0, bytes=0),
                'executable': lambda path: dict(path=path, realpath=path, available=True)}):
                observed = g['inventory']()
                assert calls and all(call[0] == '/usr/bin/dpkg-query' for call in calls)
                assert observed['installedPackages'] == installed
                assert [value['name'] for value in observed['core']['requiredPackages']] == [
                    'git', 'git-man', 'openssh-server', 'ubuntu-server', 'ubuntu-server-minimal']
                assert observed['hostSha256'] == g['digest'](b'a' * 32)
                planned = g['plan']()
                assert planned['purgePackages'] == ['gcc']
                assert planned['packageInventorySha256'] == g['digest'](g['canonical'](installed))
                assert len([call for call in calls if call[0] == '/usr/bin/apt-get']) == 1
            """);
    }

    @Test
    void subordinateIdInventoryOnlySelectsExactEnvironmentAccountsAndRejectsSymlinkFiles() throws Exception {
        verify("""
            mapping = temporary / 'subuid'
            user = 'wtlb-' + 'a' * 16
            mapping.write_text(chr(10).join(['ordinary-user:300000:65536', user + ':100000:65536']))
            original_metadata = g['metadata']
            with mock.patch.dict(g, {'SUBID_FILES': (str(mapping),), 'safe_ancestors': lambda path: None}):
                assert g['subordinate_inventory']() == [
                    dict(file=str(mapping), name=user, start=100000, count=65536)]
                mapping.write_text(user + ':100000:invalid')
                rejected('SUBORDINATE_ID_INVALID', lambda: g['subordinate_inventory']())
                with mock.patch.dict(g, {'metadata': lambda path: dict(original_metadata(path), type='symlink')}):
                    rejected('UNSAFE_SUBORDINATE_ID_FILE', lambda: g['subordinate_inventory']())
            """);
    }

    @Test
    void forgedPlansAndNonRootAdmissionCannotStartAnyMutation() throws Exception {
        verify("""
            current = dict(formatVersion=1, hostSha256='a' * 64, roots=[], units=[], installedUnits=[],
                           unitFiles=[], unitStates={}, accounts=[], groups=[], installedTargets=[],
                           core={}, packageInventorySha256='b' * 64, purgePackages=[])
            mutations = []
            with mock.patch.dict(g, {'plan': lambda: current, 'os': types.SimpleNamespace(geteuid=lambda: 0),
                                     'command': lambda *args, **kwargs: mutations.append(args)}):
                for changed in [dict(current, hostSha256='c' * 64),
                                dict(current, roots=[dict(path=str(temporary), exists=True)]),
                                dict(current, purgePackages=['openssh-server']), dict(current, unexpected=True)]:
                    rejected('PLAN_DRIFT_OR_TAMPERING', lambda changed=changed: g['apply'](changed))
                    encoded = base64.b64encode(g['canonical'](changed)).decode('ascii')
                    output = io.StringIO()
                    with contextlib.redirect_stdout(output):
                        assert g['main'](['apply', encoded]) == 65
                    receipt = json.loads(output.getvalue())
                    assert receipt['ok'] is False and receipt['error'] == 'PLAN_DRIFT_OR_TAMPERING'
                assert not mutations
            with mock.patch.dict(g, {'os': types.SimpleNamespace(geteuid=lambda: 1000),
                                     'plan': lambda: (_ for _ in ()).throw(AssertionError('nonroot read plan'))}):
                rejected('ROOT_REQUIRED', lambda: g['apply'](current))
            for encoded in ['not-base64', base64.b64encode(b'{"same":1,"same":2}').decode('ascii'),
                            base64.b64encode(b'[]').decode('ascii')]:
                rejected('PLAN_ENCODING_INVALID', lambda encoded=encoded: g['decode_plan'](encoded))
            """);
    }

    @Test
    void resetUsesExactUnitsAndPackageBudgetAndNeverContinuesAfterAnAptFailure() throws Exception {
        verify("""
            core = dict(requiredPackages=[dict(name='python3', version='3', essential=False)],
                        python3=dict(available=True), sshClient=dict(available=True), sshDaemon=dict(available=True))
            current = dict(formatVersion=1, hostSha256='a' * 64,
                           roots=[dict(path='/var/lib/windowstolinux', exists=True)],
                           units=['windowstolinux-fixture.service'], installedUnits=['windowstolinux-fixture.service'],
                           unitFiles=[], unitStates={}, accounts=[dict(name='wtlb-' + 'a' * 16, uid=998, gid=998)],
                           groups=[], subordinateIds=[dict(file='/etc/subuid', name='wtlb-' + 'a' * 16, start=100000, count=65536),
                                                      dict(file='/etc/subgid', name='wtlb-' + 'a' * 16, start=200000, count=65536)],
                           installedTargets=[dict(name='gcc')],
                           core=core, packageInventorySha256='b' * 64, purgePackages=['gcc'])
            after = dict(roots=[], units=[], unitFiles=[], accounts=[], groups=[], subordinateIds=[], installedTargets=[],
                         core=core, aptCache=dict(debFiles=0, partialFiles=0, bytes=0))
            for remaining in [None, 600]:
                calls, removed = [], []
                def controlled(arguments, **options):
                    calls.append(arguments)
                    if arguments[0] == '/usr/bin/pgrep':
                        return 1, ''
                    if arguments[1] == 'list-units':
                        return 0, chr(10).join(['windowstolinux-fixture.service loaded failed failed Fixture',
                                              'ssh.service loaded failed failed Core'])
                    return 0, ''
                with mock.patch.dict(sys.modules, {'grp': types.SimpleNamespace()}), mock.patch.dict(g, {
                    'os': types.SimpleNamespace(geteuid=lambda: 0), 'plan': lambda: current,
                    'inventory': lambda: after, 'command': controlled, 'remove_root': lambda value: removed.append(value),
                    'DEADLINE': remaining, 'time': types.SimpleNamespace(monotonic=lambda: 0)}):
                    result = g['apply'](current)
                assert result['summary']['removedRoots'] == 1 and len(removed) == 1
                assert result['summary']['removedAccounts'] == 1
                assert [call for call in calls if call[0] == '/usr/sbin/usermod'] == [
                    ['/usr/sbin/usermod', '--del-subuids', '100000-165535', '--', 'wtlb-' + 'a' * 16],
                    ['/usr/sbin/usermod', '--del-subgids', '200000-265535', '--', 'wtlb-' + 'a' * 16]]
                assert [call for call in calls if 'reset-failed' in call] == [
                    ['/usr/bin/systemctl', 'reset-failed', '--', 'windowstolinux-fixture.service']]
                purge = [call for call in calls if 'purge' in call]
                assert purge == [['/usr/bin/apt-get', '-o', 'DPkg::Lock::Timeout=' + str(1200 if remaining is None else 600),
                                  'purge', '-y', '--', 'gcc']]
                assert all('autoremove' not in call and '--force-yes' not in call for call in calls)
            calls = []
            def apt_failure(arguments, **options):
                calls.append(arguments)
                if arguments[0] == '/usr/bin/pgrep':
                    return 1, ''
                if 'purge' in arguments:
                    raise m['ResetError']('CONTROLLED_APT_FAILURE')
                return 0, ''
            with mock.patch.dict(sys.modules, {'grp': types.SimpleNamespace()}), mock.patch.dict(g, {
                'os': types.SimpleNamespace(geteuid=lambda: 0), 'plan': lambda: current, 'command': apt_failure,
                'remove_root': lambda value: (_ for _ in ()).throw(AssertionError('cleanup after failure')), 'DEADLINE': None}):
                rejected('CONTROLLED_APT_FAILURE', lambda: g['apply'](current))
                assert not any('clean' in call for call in calls)
                assert g['PHASE'] == 'purge-development-packages'
            """);
    }

    @Test
    void timedOutCommandsOnlyTerminateTheirNewOwnedProcessGroupAndReportUncertainty() throws Exception {
        verify("""
            for timeouts in [1, 2, 3]:
                groups, launches = [], []
                class Owned:
                    pid = 32123
                    returncode = -15
                    stdout = io.BytesIO()
                    stderr = io.BytesIO()
                    calls = 0
                    def communicate(self, timeout):
                        self.calls += 1
                        if self.calls <= timeouts:
                            raise subprocess.TimeoutExpired('owned', timeout)
                        return b'', b''
                def launch(arguments, **options):
                    launches.append(options)
                    return Owned()
                with mock.patch.object(subprocess, 'Popen', launch), mock.patch.dict(g, {
                    'os': types.SimpleNamespace(name='posix', killpg=lambda pid, sig: groups.append((pid, sig))),
                    'signal': types.SimpleNamespace(SIGTERM=15, SIGKILL=9),
                    'DEADLINE': None}):
                    rejected('OWNED_COMMAND_CLEANUP_UNCONFIRMED' if timeouts == 3 else 'COMMAND_TIMEOUT',
                             lambda: g['command'](['/usr/bin/apt-get', '-s', 'purge', '--', 'gcc'], timeout=20))
                assert launches[0]['start_new_session'] is True
                assert all(pid == 32123 for pid, sig in groups)
                assert len(groups) == (1 if timeouts == 1 else 2)
                assert groups == ([(32123, 15)] if timeouts == 1 else [(32123, 15), (32123, 9)])
                assert launches[0]['env'] == dict(PATH='/usr/sbin:/usr/bin:/sbin:/bin', LC_ALL='C',
                                                DEBIAN_FRONTEND='noninteractive', NEEDRESTART_MODE='l')
            """);
    }

    private void verify(String checks) throws Exception {
        Path source = temporary.resolve("reset.py");
        try (var input = getClass().getResourceAsStream("disposable-host-reset.py")) {
            assertNotNull(input);
            Files.copy(input, source);
        }
        Path python = Path.of(System.getProperty("managed.test.python",
            Files.isRegularFile(Path.of("E:/Program/Python/Python3.12/python.exe"))
                ? "E:/Program/Python/Python3.12/python.exe" : "/usr/bin/python3"));
        assertTrue(Files.isExecutable(python), "A real local Python interpreter is required; this test must not skip");
        String harness = """
            import base64, contextlib, io, json, os, pathlib, runpy, subprocess, sys, types
            from unittest import mock
            m = runpy.run_path(sys.argv[1], run_name='fixture')
            g = m['inventory'].__globals__
            temporary = pathlib.Path(sys.argv[2])
            def rejected(code, action):
                try:
                    action()
                except m['ResetError'] as error:
                    assert error.code == code, (error.code, code)
                else:
                    raise AssertionError('Expected rejection: ' + code)
            """ + checks + "\nprint('BOUNDARY_OK')\n";
        Path output = temporary.resolve("boundary.log");
        var process = new ProcessBuilder(List.of(python.toString(), "-I", "-B", "-c", harness,
            source.toString(), temporary.toString())).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Local reset boundary fixture timed out");
            String result = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), result);
            assertEquals("BOUNDARY_OK", result.strip());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        }
    }
}
