package gold.debug.windowstolinux.app.linux.ssh.connect;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Executes the actual SSH frame with inherited startup controls injected before its first Bash. */
class SshCommandFrameEnvironmentTest {
    @TempDir
    Path root;

    @Test
    void nativeFrameClearsLoaderSettingsAndKeepsOrdinaryEnvironmentAndOriginalInput() throws Exception {
        Path bash = Path.of(System.getProperty("managed.test.bash", "/bin/bash"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(bash), "Set managed.test.bash to validate the native protocol");
        try (var server = new LinuxLoopbackSshServer(root, ssh -> ssh.setCommandFactory((channel, command) -> {
            assertEquals(SshCommandFrame.COMMAND, command);
            Path entry = root.resolve("ordinary-frame.sh");
            Files.writeString(entry, "export LD_LIBRARY_PATH= GCONV_PATH=/nonexistent WTL_FRAME_ENV=retained\n" + command,
                StandardCharsets.UTF_8);
            return new ProcessShellFactory("fixture", List.of(bash.toString(), "--noprofile", "--norc", "-p", entry.toString())) {
                @Override
                protected List<String> resolveEffectiveCommand(ChannelSession c, String ignored, List<String> arguments) {
                    return arguments;
                }
            }.createShell(channel);
        })); var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, server)) {
            byte[] input = {0, 1, 10, 13, (byte) 255};
            String script = """
                [ ! -v LD_LIBRARY_PATH ] && [ ! -v GCONV_PATH ] || exit 18
                [ "$BASH_ENV" = /dev/null ] && [ "$ENV" = /dev/null ] || exit 19
                builtin printf '%s\\n' "$WTL_FRAME_ENV"
                /usr/bin/sha256sum
                """;
            var result = connection.execute(new CommandRequest(script, input, Duration.ofSeconds(10), 4096), () -> false);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            var observed = result.value().orElseThrow();
            assertEquals(0, observed.exitCode().orElseThrow());
            assertEquals("", observed.stderr());
            var lines = observed.stdout().lines().toList();
            assertEquals(2, lines.size(), observed.stdout());
            assertEquals("retained", lines.getFirst());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)), lines.getLast().split(" ")[0]);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FUNCTION", "OPTIONS"})
    void nativeFrameIgnoresInheritedFunctionsAndOptionsWithoutChangingBinaryInput(String attack) throws Exception {
        Path bash = Path.of(System.getProperty("managed.test.bash", "/bin/bash"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(bash), "Set managed.test.bash to validate the native protocol");
        Path hook = root.resolve("imported-function.marker");
        var environment = new ArrayList<>(List.of("WTL_FRAME_ENV=retained"));
        if (attack.equals("FUNCTION"))
            environment.add("BASH_FUNC_read%%=() { builtin printf FRAME_IMPORT_HOOK > '"
                + hook.toAbsolutePath().toString().replace('\\', '/').replace("'", "'\"'\"'") + "'; return 111; }");
        else
            environment.addAll(List.of("SHELLOPTS=xtrace", "BASHOPTS=extdebug", "CDPATH=/", "GLOBIGNORE=*"));
        String injected = environment.stream().map(value -> "'" + value.replace("'", "'\"'\"'") + "'")
            .collect(java.util.stream.Collectors.joining(" "));
        var originalStartupControl = new java.util.concurrent.atomic.AtomicBoolean();
        try (var server = new LinuxLoopbackSshServer(root, ssh -> ssh.setCommandFactory((channel, command) -> {
            assertEquals(SshCommandFrame.COMMAND, command);
            String envLauncher = "exec /usr/bin/env ";
            assertTrue(command.startsWith("unset "));
            assertTrue(command.indexOf("LD_LIBRARY_PATH") < command.indexOf(envLauncher));
            Path entry = root.resolve("inherited-frame.sh");
            String executed = originalStartupControl.get() ? command.replace("--noprofile --norc -p", "--noprofile --norc")
                .replace("IFS= builtin read", "IFS= read") : command;
            int launch = executed.indexOf(envLauncher) + envLauncher.length();
            Files.writeString(entry, executed.substring(0, launch) + injected + " " + executed.substring(launch), StandardCharsets.UTF_8);
            return new ProcessShellFactory("fixture", List.of(bash.toString(), "--noprofile", "--norc", entry.toString())) {
                @Override
                protected List<String> resolveEffectiveCommand(ChannelSession c, String ignored, List<String> arguments) {
                    return arguments;
                }
            }.createShell(channel);
        })); var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, server)) {
            byte[] input = new byte[4096];
            for (int i = 0; i < input.length; i++) input[i] = (byte) i;
            String script = "# FRAME_SCRIPT_PRIVATE 中文\n"
                + "if [[ -o xtrace ]] || shopt -q extdebug; then exit 17; fi\n"
                + "builtin printf '%s\\n' \"$WTL_FRAME_ENV\"\n/usr/bin/sha256sum\n";
            var result = connection.execute(new CommandRequest(script, input, Duration.ofSeconds(10), 4096), () -> false);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            var observed = result.value().orElseThrow();
            assertEquals(0, observed.exitCode().orElseThrow());
            assertFalse(observed.truncated());
            assertEquals("", observed.stderr(), "Imported controls cannot execute a hook or trace the framed script");
            assertEquals("retained", observed.stdout().lines().findFirst().orElseThrow());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)),
                observed.stdout().lines().skip(1).findFirst().orElseThrow().split(" ")[0]);
            assertFalse(Files.exists(hook));
            // Only the local controlled peer reproduces the original startup to prove injection reached the native boundary.
            originalStartupControl.set(true);
            var original = connection.execute(new CommandRequest(script, input, Duration.ofSeconds(10), 4096), () -> false);
            assertEquals(TaskResult.FAILED, original.outcome(), "The same injected controls must break the original frame startup");
            if (attack.equals("FUNCTION")) assertEquals("FRAME_IMPORT_HOOK", Files.readString(hook));
            else
                assertTrue(original.value().orElseThrow().stderr().contains("FRAME_SCRIPT_PRIVATE"), original.toString());
        }
    }
}
