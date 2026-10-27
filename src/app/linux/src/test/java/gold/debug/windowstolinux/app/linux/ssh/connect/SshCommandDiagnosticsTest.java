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
            Thread.sleep(80);
            var result = connection.execute(new CommandRequest("private author script", new byte[0],
                Duration.ofSeconds(3), 100), () -> false);
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("11", result.problem().orElseThrow().context().get("disconnectCode"));
            assertEquals("true", result.problem().orElseThrow().context().get("disconnectObserved"));
            assertEquals("false", result.problem().orElseThrow().context().get("disconnectInitiatorLocal"));
            assertEquals("true", result.problem().orElseThrow().context().get("channelPeerAssigned"));
            assertTrue(Long.parseLong(result.problem().orElseThrow().context().get("transportReadCallbacks")) > 0);
            var facts = result.problem().orElseThrow().context();
            long baseline = Long.parseLong(facts.get("transportReadCallbacksAtCommandStart"));
            assertTrue(baseline > 0);
            assertTrue(Long.parseLong(facts.get("transportReadCallbacksDuringCommand")) > 0);
            assertTrue(Long.parseLong(facts.get("transportSessionAgeMillis"))
                >= Long.parseLong(facts.get("elapsedMillis")) + 40);
            assertFalse(result.toString().contains("private"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void rawPeerTransportCloseHasUnknownInitiatorAndIsNeverReplayed() throws Exception {
        try (var fixture = new SshCommandExecutionTest.WireFixture(directory, command -> {
            command.input.readAllBytes();
            command.channel.getSession().getIoSession().close(true);
        }); var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            var result = connection.execute(new CommandRequest("private author script", new byte[0],
                Duration.ofSeconds(3), 100), () -> false);
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            var facts = result.problem().orElseThrow().context();
            assertEquals("false", facts.get("disconnectObserved"));
            assertEquals("-1", facts.get("disconnectCode"));
            assertEquals("UNKNOWN", facts.get("disconnectInitiatorLocal"));
            assertEquals("false", facts.get("exitObserved"));
            assertFalse(result.toString().contains("private"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void firstFailureRetainsOnlyBoundedWhitelistedTypeCauseAndFrames() {
        var diagnostic = new SshCommandDiagnostics();
        var failure = new java.net.SocketException("private address and credential");
        failure.initCause(new java.io.EOFException("private payload"));
        var frames = new StackTraceElement[10];
        frames[0] = new StackTraceElement("private.secret.Class", "read", "private", 1);
        frames[1] = new StackTraceElement("java.net.Socket", "private method", "private", 2);
        for (int index = 2; index < frames.length; index++)
            frames[index] = new StackTraceElement("java.net.Socket", "read", "private filename", index);
        failure.setStackTrace(frames);
        diagnostic.failure(failure);
        diagnostic.failure(new IOException("late private generic failure"));
        var facts = new LinkedHashMap<String, String>();
        diagnostic.appendTo(facts);
        assertEquals("java.net.SocketException", facts.get("transportFailureType"));
        assertEquals("java.io.EOFException", facts.get("transportFailureCauseType"));
        assertEquals(6, facts.keySet().stream().filter(key -> key.startsWith("transportFailureStack[")).count());
        assertEquals("java.net.Socket#read:2", facts.get("transportFailureStack[0]"));
        assertFalse(facts.toString().contains("private"));
        assertEquals("false", facts.get("disconnectObserved"));
        assertEquals("UNKNOWN", facts.get("disconnectInitiatorLocal"));
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
        assertEquals("true", facts.get("disconnectObserved"));
        assertEquals("false", facts.get("disconnectInitiatorLocal"));
        assertEquals("true", facts.get("sessionCloseObserved"));
        assertFalse(facts.toString().contains("private"));
    }
}
