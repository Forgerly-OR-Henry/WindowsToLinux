package gold.debug.windowstolinux.app.linux.ssh.connect;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshCommandDiagnosticsTest {
    @TempDir
    Path directory;

    @Test
    void peerDisconnectAfterInputRemainsUnknownAndIsNeverReplayed() throws Exception {
        try (var fixture = new SshCommandExecutionTest.WireFixture(directory, command -> {
            command.input.readAllBytes();
            command.channel.getSession().disconnect(11, "private peer message");
        }); var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            var result = connection.execute(new CommandRequest("private author script", new byte[0],
                Duration.ofSeconds(3), 100), () -> false);
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("11", result.problem().orElseThrow().context().get("disconnectCode"));
            assertEquals("false", result.problem().orElseThrow().context().get("disconnectInitiatorLocal"));
            assertEquals("true", result.problem().orElseThrow().context().get("channelPeerAssigned"));
            assertTrue(Long.parseLong(result.problem().orElseThrow().context().get("transportReadCallbacks")) > 0);
            assertFalse(result.toString().contains("private"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void retainsTypedDisconnectWithoutExceptionOrPeerText() {
        var diagnostic = new SshCommandDiagnostics();
        diagnostic.sessionException(null, new IOException("private credential and author script"));
        diagnostic.sessionDisconnect(null, 11, "private remote message", "private language", false);
        diagnostic.sessionClosed(null);
        var facts = new LinkedHashMap<String, String>();
        diagnostic.appendTo(facts);
        assertEquals("java.io.IOException", facts.get("transportFailureType"));
        assertEquals("11", facts.get("disconnectCode"));
        assertEquals("false", facts.get("disconnectInitiatorLocal"));
        assertEquals("true", facts.get("sessionCloseObserved"));
        assertFalse(facts.toString().contains("private"));
    }
}
