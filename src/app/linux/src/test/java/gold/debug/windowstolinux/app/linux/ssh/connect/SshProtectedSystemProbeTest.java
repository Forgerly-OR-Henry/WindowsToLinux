package gold.debug.windowstolinux.app.linux.ssh.connect;

import static gold.debug.windowstolinux.appapi.linux.command.SshCommandFrameProtocol.PROTECTED_SYSTEM_PROBE_HEADER;
import static gold.debug.windowstolinux.appapi.linux.command.SshCommandFrameProtocol.PRIVILEGE_CHECKED_FRAME_LAUNCHER;
import static gold.debug.windowstolinux.appapi.linux.command.SshCommandFrameProtocol.PRIVILEGE_CHECKED_SYSTEM_PROBE_HEADER;
import static org.junit.jupiter.api.Assertions.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.common.SftpHelper;
import org.apache.sshd.sftp.server.SftpSubsystem;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Validates ordering against an offline, read-only SSH and SFTP fixture. */
class SshProtectedSystemProbeTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"split", "usr/bin", "/usr/bin"})
    void verifiesFixedLaunchPathsInTheSameSessionBeforeExec(String binTarget) throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var metadataSession = new AtomicReference<ServerSession>();
        var execSession = new AtomicReference<ServerSession>();
        var commands = new CopyOnWriteArrayList<SshCommandExecutionTest.WireCommand>();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        try (var server = new LinuxLoopbackSshServer(root, ssh -> {
            ssh.setSubsystemFactories(List.of(sftp(binTarget, "safe", events, metadataSession)));
            ssh.setCommandFactory((channel, command) -> {
                execSession.set(channel.getSession());
                events.add("exec");
                return acknowledgeFrame(commands, failures);
            });
        }); var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, server)) {
            try (var request = SshCommandExecutionTest.request(PROTECTED_SYSTEM_PROBE_HEADER + ":\n", 4096)) {
                var result = connection.execute(request, () -> false);
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                assertEquals("frame received", result.value().orElseThrow().stdout());
            }
            assertSame(metadataSession.get(), execSession.get());
            var expected = new ArrayList<>(List.of("/", "/usr", "/usr/bin", "/usr/bin/env", "/usr/bin/stat",
                "/usr/bin/readlink", "/bin"));
            if (binTarget.equals("split")) expected.add("/bin/bash");
            else expected.addAll(List.of("readlink:/bin", "/usr/bin/bash"));
            expected.add("exec");
            assertEquals(expected, events);
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(commands.stream().noneMatch(command -> command.worker.isAlive()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"safe", "setuid-env", "setgid-bash", "setuid-reader", "setgid-runner", "absent-getcap", "absent-setpriv"})
    void privilegeCheckedFrameRequiresSetIdFreeLaunchToolsBeforeDispatch(String scenario) throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var metadataSession = new AtomicReference<ServerSession>();
        var commands = new CopyOnWriteArrayList<SshCommandExecutionTest.WireCommand>();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        boolean safe = scenario.equals("safe");
        try (var server = new LinuxLoopbackSshServer(root, ssh -> {
            ssh.setSubsystemFactories(List.of(sftp("usr/bin", scenario, events, metadataSession)));
            ssh.setCommandFactory((channel, command) -> {
                events.add("exec");
                assertTrue(safe, "Rejected launch metadata reached exec");
                assertSame(metadataSession.get(), channel.getSession());
                assertTrue(command.startsWith(PRIVILEGE_CHECKED_FRAME_LAUNCHER));
                return acknowledgeFrame(commands, failures, PRIVILEGE_CHECKED_SYSTEM_PROBE_HEADER);
            });
        }); var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, server);
             var request = SshCommandExecutionTest.request(PRIVILEGE_CHECKED_SYSTEM_PROBE_HEADER + ":\n", 4096)) {
            if (safe) {
                assertEquals(TaskResult.SUCCEEDED, connection.execute(request, () -> false).outcome());
                assertEquals(List.of("/", "/usr", "/usr/bin", "/usr/bin/env", "/usr/bin/stat", "/usr/bin/readlink",
                    "/bin", "readlink:/bin", "/usr/bin/bash", "/usr/bin/setpriv", "/usr/sbin", "/usr/sbin/getcap", "exec"), events);
            } else {
                var denied = assertThrows(ApiException.class, () -> connection.execute(request, () -> false));
                assertEquals("SSH_PROTECTED_SYSTEM_PROBE_UNCONFIRMED", denied.problem().eventCode());
                assertFalse(events.contains("exec"));
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(commands.stream().noneMatch(command -> command.worker.isAlive()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"clean", "capability", "diagnostic", "reader-failure"})
    void privilegeCheckedLauncherStopsBeforeStartingFrameIfCapabilitiesAreUnconfirmed(String scenario) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(Path.of(bash)));
        String launcher = PRIVILEGE_CHECKED_FRAME_LAUNCHER
            .replace("LC_ALL=C /usr/sbin/getcap ", "LC_ALL=C fixture_getcap ")
            .replace("exec /usr/bin/setpriv ", "fixture_setpriv ");
        String script = """
            export LD_LIBRARY_PATH= GCONV_PATH=/nonexistent
            fixture_getcap() {
              [ -z "${LD_LIBRARY_PATH+x}" ] && [ -z "${GCONV_PATH+x}" ] || return 91
              [ "$*" = '/usr/sbin/getcap /usr/bin/setpriv /usr/bin/env /bin/bash /usr/bin/stat /usr/bin/readlink' ] || return 92
              case "$scenario" in
                capability) printf 'recorded capability' ;;
                diagnostic) printf 'xattr could not be read' >&2 ;;
                reader-failure) return 2 ;;
              esac
            }
            fixture_setpriv() {
              [ "$*" = '--no-new-privs -- /usr/bin/env BASH_ENV=/dev/null ENV=/dev/null LC_ALL=C /bin/bash --noprofile --norc -p -c FRAME' ] || return 93
              printf 'FRAME_STARTED\\n'
            }
            """ + "scenario=" + scenario + "\n" + launcher + " FRAME\n";
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-p", "-s")
            .directory(root.toFile()).redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(scenario.equals("clean") ? 0 : 125, process.exitValue(), output);
            assertEquals(scenario.equals("clean") ? "FRAME_STARTED\n" : "", output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-sftp", "unowned", "writable-file", "writable-parent", "writable-root",
        "missing-mode", "missing-owner", "absent-readlink", "tool-symlink", "unowned-bin-link", "alternate-bin"})
    void unsafeOrUnobservableMetadataDeniesBeforeExec(String scenario) throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        try (var server = new LinuxLoopbackSshServer(root, ssh -> {
            if (!scenario.equals("no-sftp")) {
                String binTarget = scenario.equals("alternate-bin") ? "/opt/bin" : "usr/bin";
                ssh.setSubsystemFactories(List.of(sftp(binTarget, scenario, events, new AtomicReference<>())));
            }
            ssh.setCommandFactory((channel, command) -> {
                events.add("exec");
                throw new AssertionError("Rejected preflight reached exec");
            });
        }); var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, server)) {
            try (var request = SshCommandExecutionTest.request(PROTECTED_SYSTEM_PROBE_HEADER + ":\n", 4096)) {
                var denied = assertThrows(ApiException.class, () -> connection.execute(request, () -> false));
                assertEquals("SSH_PROTECTED_SYSTEM_PROBE_UNCONFIRMED", denied.problem().eventCode(), scenario);
                assertFalse(events.contains("exec"));
                if (scenario.equals("unowned-bin-link")) assertFalse(events.contains("readlink:/bin"));
                if (scenario.equals("writable-parent")) assertFalse(events.contains("/usr/bin"));
                if (scenario.equals("writable-root")) assertFalse(events.contains("/usr"));
            }
        }
    }

    @Test
    void originalExecNeedsNoSftpAndRemainsUsableAfterDeniedPreflight() throws Exception {
        try (var fixture = new SshCommandExecutionTest.WireFixture(root, command -> {
            command.input.readAllBytes();
            command.exit.onExit(0);
        }); var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, fixture.server);
             var guarded = SshCommandExecutionTest.request(PROTECTED_SYSTEM_PROBE_HEADER + ":\n", 4096);
             var plain = SshCommandExecutionTest.request(":\n", 4096)) {
            assertThrows(ApiException.class, () -> connection.execute(guarded, () -> false));
            assertEquals(0, fixture.calls.get());
            assertEquals(TaskResult.SUCCEEDED, connection.execute(plain, () -> false).outcome());
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void originalCommandDeadlineIncludesStalledSftpMetadata() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        try (var server = new LinuxLoopbackSshServer(root, ssh -> {
            ssh.setSubsystemFactories(List.of(sftp("usr/bin", "slow", events, new AtomicReference<>())));
            ssh.setCommandFactory((channel, command) -> {
                events.add("exec");
                throw new AssertionError("Expired preflight reached exec");
            });
        }); var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, server);
             var request = new CommandRequest(PROTECTED_SYSTEM_PROBE_HEADER + ":\n", new byte[0], Duration.ofSeconds(1), 4096)) {
            var timeout = assertTimeout(Duration.ofSeconds(3), () -> assertThrows(ApiException.class,
                () -> connection.execute(request, () -> false)));
            assertEquals(ProblemCode.TIMEOUT, timeout.problem().code());
            assertEquals(List.of("/"), events);
        }
    }

    private SftpSubsystemFactory sftp(String binTarget, String scenario, List<String> events,
                                     AtomicReference<ServerSession> metadataSession) {
        return new SftpSubsystemFactory() {
            @Override
            public Command createSubsystem(ChannelSession channel) {
                return new SftpSubsystem(channel, this) {
                    @Override
                    protected Map<String, Object> doLStat(int id, String path, int flags) throws IOException {
                        metadataSession.set(channel.getSession());
                        events.add(path);
                        if (scenario.equals("slow")) {
                            try {
                                Thread.sleep(Duration.ofSeconds(4));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Fixture metadata request cancelled");
                            }
                        }
                        return attributes(path, binTarget, scenario);
                    }

                    @Override
                    protected void writeAttrs(Buffer buffer, Map<String, ?> attributes) {
                        if (attributes.get("fixtureSetId") instanceof Integer specialMode) {
                            // Send the exact bits: PosixFilePermission cannot represent set-ID.
                            var mode = new Attributes().owner(0, 0);
                            mode.setPermissions(0100755 | specialMode);
                            SftpHelper.writeAttributes(buffer, mode, getVersion());
                        } else {
                            super.writeAttrs(buffer, attributes);
                        }
                    }

                    @Override
                    protected SimpleImmutableEntry<Path, String> doReadLink(int id, String path) {
                        events.add("readlink:" + path);
                        assertEquals("/bin", path);
                        return new SimpleImmutableEntry<>(root.resolve("bin"), binTarget);
                    }
                };
            }
        };
    }

    private static Map<String, Object> attributes(String path, String binTarget, String scenario) throws FileNotFoundException {
        boolean link = path.equals("/bin") && !binTarget.equals("split")
            || path.equals("/usr/bin/stat") && scenario.equals("tool-symlink");
        boolean directory = List.of("/", "/usr", "/usr/bin", "/bin", "/usr/sbin").contains(path) && !link;
        if (!directory && !link && !List.of("/usr/bin/env", "/usr/bin/stat", "/usr/bin/readlink",
            "/usr/bin/bash", "/bin/bash", "/usr/bin/setpriv", "/usr/sbin/getcap").contains(path)
            || scenario.equals("absent-readlink") && path.equals("/usr/bin/readlink")
            || scenario.equals("absent-getcap") && path.equals("/usr/sbin/getcap")
            || scenario.equals("absent-setpriv") && path.equals("/usr/bin/setpriv"))
            throw new FileNotFoundException("No fixture metadata");
        boolean unowned = scenario.equals("unowned") && path.equals("/usr/bin/stat")
            || scenario.equals("unowned-bin-link") && path.equals("/bin");
        boolean writable = scenario.equals("writable-file") && path.equals("/usr/bin/readlink")
            || scenario.equals("writable-parent") && path.equals("/usr")
            || scenario.equals("writable-root") && path.equals("/");
        var attributes = new HashMap<String, Object>();
        attributes.put(IoUtils.REGFILE_VIEW_ATTR, !directory && !link);
        attributes.put(IoUtils.DIRECTORY_VIEW_ATTR, directory);
        attributes.put(IoUtils.SYMLINK_VIEW_ATTR, link);
        attributes.put(IoUtils.GROUPID_VIEW_ATTR, 0);
        if (!(scenario.equals("missing-owner") && path.equals("/usr/bin/stat")))
            attributes.put(IoUtils.USERID_VIEW_ATTR, unowned ? 1000 : 0);
        if (!(scenario.equals("missing-mode") && path.equals("/usr/bin/stat")))
            attributes.put(IoUtils.PERMISSIONS_VIEW_ATTR, PosixFilePermissions.fromString(
                link ? "rwxrwxrwx" : writable ? "rwxrwxr-x" : "rwxr-xr-x"));
        switch (scenario + ":" + path) {
            case "setuid-env:/usr/bin/env", "setuid-reader:/usr/sbin/getcap" -> attributes.put("fixtureSetId", 04000);
            case "setgid-bash:/usr/bin/bash", "setgid-runner:/usr/bin/setpriv" -> attributes.put("fixtureSetId", 02000);
            default -> {}
        }
        return attributes;
    }

    private static Command acknowledgeFrame(List<SshCommandExecutionTest.WireCommand> commands,
                                            ConcurrentLinkedQueue<Throwable> failures) {
        return acknowledgeFrame(commands, failures, PROTECTED_SYSTEM_PROBE_HEADER);
    }

    private static Command acknowledgeFrame(List<SshCommandExecutionTest.WireCommand> commands,
                                            ConcurrentLinkedQueue<Throwable> failures, String header) {
        var wire = new SshCommandExecutionTest.WireCommand(command -> {
            assertTrue(new String(command.input.readAllBytes(), StandardCharsets.UTF_8).contains(header));
            command.output.write("frame received".getBytes(StandardCharsets.UTF_8));
            command.output.flush();
            command.exit.onExit(0);
        }, failures);
        commands.add(wire);
        return wire;
    }
}
