package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

import org.junit.jupiter.api.Test;

class DeploymentLiveCommandDiagnosticsTest {
    private static final UUID TASK = UUID.fromString("de4f5e16-3a81-471b-9cf8-a851374fe654");

    @Test
    void observesNestedApiTypedFactsWithoutReadingExceptionBodies() {
        var diagnostic = UUID.fromString("edba0127-5150-47a1-b78b-8a58025fb6a7");
        var api = new BodyRejectingApiException(new Problem(ProblemCode.UNKNOWN, "SSH_EXECUTE_UNCONFIRMED", "private-message",
            Map.of("commandPhase", "WAITING_FOR_EXIT", "sessionCloseObserved", "true", "transportFailureType", "org.apache.sshd.common.SshException",
                "stdin", "private-stdin", "peerAddress", "private-host", "transportFailureMessage", "private-message",
                "disconnectCode", "11\ncode=SECRET_INJECTED", "inputStack[6]", "java.lang.Thread#run:42"), Optional.of(diagnostic)));
        var original = new BodyRejectingFailure();
        original.initCause(api);
        var lines = new ArrayList<String>();
        assertSame(original, assertThrows(BodyRejectingFailure.class, () -> DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
            DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.EXECUTE, () -> TASK, () -> { throw original; }, lines::add)));
        String prefix = "STATIC_LIVE_REMOTE_CAUSE phase=execute task=" + TASK + " index=1";
        assertTrue(lines.contains(prefix + " code=UNKNOWN"));
        assertTrue(lines.contains(prefix + " event=SSH_EXECUTE_UNCONFIRMED"));
        assertTrue(lines.contains(prefix + " diagnostic=" + diagnostic));
        assertTrue(lines.contains(prefix + " problem[1].context.commandPhase=WAITING_FOR_EXIT"));
        assertTrue(lines.contains(prefix + " problem[1].context.sessionCloseObserved=true"));
        assertTrue(lines.contains(prefix + " problem[1].context.transportFailureType=org.apache.sshd.common.SshException"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("private-") || line.contains("SECRET_INJECTED")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("disconnectCode") || line.contains("inputStack[6]")));
    }

    @Test
    void excludesNonApiBodiesAndMalformedEventCodes() {
        var lines = new ArrayList<String>();
        var original = new BodyRejectingFailure();
        assertSame(original, assertThrows(BodyRejectingFailure.class, () -> DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
            DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.CONNECT, () -> TASK, () -> { throw original; }, lines::add)));
        assertEquals(3, lines.size());
        assertTrue(lines.stream().noneMatch(line -> line.contains("message=") || line.contains("event=") || line.contains("context.")));
        for (var event : List.of("SSH_PRIVATE\nevent=INJECTED", "SSH private", "SSH_" + "A".repeat(96), "")) {
            lines.clear();
            var api = new BodyRejectingApiException(new Problem(ProblemCode.UNKNOWN, event, "private-message", Map.of(), Optional.empty()));
            assertSame(api, assertThrows(BodyRejectingApiException.class, () -> DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
                DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.CLOSE, () -> TASK, () -> { throw api; }, lines::add)));
            assertTrue(lines.stream().noneMatch(line -> line.contains("event=") || line.contains("diagnostic=")));
        }
    }

    @Test
    void preservesReturnIdentityAndFixedPhaseForEveryBoundary() throws Throwable {
        var returned = new Object();
        for (var phase : DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.values()) {
            var lines = new ArrayList<String>();
            assertSame(returned, DeploymentStaticLiveAcceptanceTest.observedRemoteCall(phase, () -> TASK, () -> returned, lines::add));
            String boundary = "STATIC_LIVE_REMOTE_CALL phase=" + phase.name().toLowerCase(java.util.Locale.ROOT) + " task=" + TASK;
            assertEquals(List.of(boundary + " state=ENTER", boundary + " state=RETURN"), lines);
        }
        assertNull(DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
            DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.CLOSE, () -> TASK, () -> null, ignored -> {}));
    }

    @Test
    void failedDiagnosticSinkPreservesOriginalThrowAndReturn() throws Throwable {
        var original = new BodyRejectingFailure();
        java.util.function.Consumer<String> broken = ignored -> { throw new AssertionError("synthetic sink failure"); };
        assertSame(original, assertThrows(BodyRejectingFailure.class, () -> DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
            DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.CLOSE, () -> TASK, () -> { throw original; }, broken)));
        var returned = new Object();
        assertSame(returned, DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
            DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.CONNECT, () -> TASK, () -> returned, broken));
        assertSame(returned, DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
            DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.CONNECT, () -> { throw new AssertionError("synthetic identity supplier failure"); },
            () -> returned, ignored -> {}));
    }

    @Test
    void boundsCauseDepthAndStopsIdentityCycles() {
        var chain = new BodyRejectingFailure();
        for (int index = 0; index < 6; index++) {
            var outer = new BodyRejectingFailure();
            outer.initCause(chain);
            chain = outer;
        }
        var original = chain;
        var lines = new ArrayList<String>();
        assertSame(original, assertThrows(BodyRejectingFailure.class, () -> DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
            DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.EXECUTE, () -> TASK, () -> { throw original; }, lines::add)));
        assertEquals(4, lines.stream().filter(line -> line.contains(" type=")).count());
        var first = new BodyRejectingFailure();
        var second = new BodyRejectingFailure();
        first.initCause(second);
        second.initCause(first);
        lines.clear();
        assertSame(first, assertThrows(BodyRejectingFailure.class, () -> DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
            DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.CLOSE, () -> TASK, () -> { throw first; }, lines::add)));
        assertEquals(2, lines.stream().filter(line -> line.contains(" type=")).count());
    }

    private static class BodyRejectingFailure extends RuntimeException {
        @Override public String getMessage() { throw new AssertionError("message must not be read"); }
        @Override public String toString() { throw new AssertionError("body must not be read"); }
        @Override public StackTraceElement[] getStackTrace() { throw new AssertionError("stack must not be read"); }
    }

    private static class BodyRejectingApiException extends ApiException {
        BodyRejectingApiException(Problem problem) { super(problem); }
        @Override public String getMessage() { throw new AssertionError("message must not be read"); }
        @Override public String toString() { throw new AssertionError("body must not be read"); }
        @Override public StackTraceElement[] getStackTrace() { throw new AssertionError("stack must not be read"); }
    }

    @Test
    void keepsTypedSshClosureAndReadFactsIncludingNestedProblems() {
        var facts = List.of(
            "context.transportFailureType=org.apache.sshd.common.SshException",
            "problem[0].context.transportFailureType=-",
            "context.disconnectCode=-1",
            "problem[1].context.disconnectCode=11",
            "context.disconnectInitiatorLocal=false",
            "context.sessionCloseObserved=true",
            "context.transportKeyExchangeState=DONE",
            "context.transportReadCallbacks=206",
            "context.transportReadHandlerActive=false",
            "context.transportReadCompletedAgeMillis=48923",
            "context.transportReadHandlerAgeMillis=UNKNOWN",
            "context.transportReadThreadState=WAITING",
            "context.transportReadStack[0]=org.apache.sshd.common.session.helpers.AbstractSession#handleMessage:42",
            "context.inputState=COMPLETED",
            "context.commandPhase=WAITING_FOR_EXIT",
            "context.elapsedMillis=260512",
            "context.timeoutMillis=1796000");
        assertEquals(facts, DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(String.join("\n", facts)));
    }

    @Test
    void keepsTypedFrameAndWindowFactsIncludingZeroAndNestedProblems() {
        var facts = List.of(
            "context.scriptBytes=16777216",
            "problem[0].context.stdinBytes=0",
            "context.frameWritePhase=NOT_STARTED",
            "problem[0].context.frameWritePhase=WRITING_LENGTH",
            "context.frameWritePhase=WRITING_SCRIPT",
            "context.frameWritePhase=WRITING_STDIN",
            "context.frameWritePhase=FLUSHING",
            "context.frameWritePhase=WRITTEN",
            "context.authorPayloadState=NOT_SENT",
            "problem[0].context.authorPayloadState=MAY_HAVE_BEEN_SENT",
            "context.inputSenderCreated=false",
            "problem[0].context.inputSenderCreated=true",
            "context.channelPeerAssigned=false",
            "problem[0].context.channelPeerAssigned=true",
            "context.remoteWindowOpenMaxSize=4294967295",
            "context.remoteWindowOpenSize=0",
            "context.remoteWindowOpenPacketSize=32768",
            "problem[0].context.remoteWindowFailureMaxSize=4294967295",
            "problem[0].context.remoteWindowFailureSize=0",
            "problem[0].context.remoteWindowFailurePacketSize=999999999999");
        assertEquals(facts, DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(String.join("\n", facts)));
    }

    @Test
    void excludesPeerMessagesPathsCredentialsAndMalformedTypedValues() {
        var privateFacts = List.of(
            "context.transportFailureMessage=password=private-value",
            "context.peerAddress=private-host:22",
            "context.transportFailureType=org.apache.sshd.common.SshException: private-message",
            "context.transportFailureType=external.PrivateException",
            "context.disconnectCode=11 private-value",
            "context.disconnectCode=1234567890123",
            "context.disconnectInitiatorLocal=private-value",
            "context.sessionCloseObserved=private-value",
            "context.transportKeyExchangeState=PRIVATE_VALUE",
            "context.transportReadCallbacks=private-value",
            "context.transportReadCallbacks=1234567890123",
            "context.transportReadCompletedAgeMillis=-1",
            "context.transportReadThreadState=private-value",
            "context.transportReadStack[6]=org.apache.sshd.common.Session#read:42",
            "context.transportReadStack[0]=org.apache.sshd.common.Session#read:/private/source.java:42",
            "context.transportReadStack[0]=external.PrivateProvider#read:42",
            "context.script=echo private-value",
            "context.stdin=private-value",
            "context.scriptBytes=-1",
            "context.scriptBytes=1234567890123",
            "context.stdinBytes=",
            "problem[0].context.stdinBytes=1 private-value",
            "context.frameWritePhase=WRITING_PRIVATE_VALUE",
            "problem[0].context.frameWritePhase=WRITTEN private-value",
            "context.authorPayloadState=SENT",
            "problem[0].context.authorPayloadState=MAY_HAVE_BEEN_SENT private-value",
            "context.inputSenderCreated=TRUE",
            "problem[0].context.channelPeerAssigned=1",
            "context.remoteWindowOpenMaxSize=-1",
            "context.remoteWindowOpenSize=+0",
            "context.remoteWindowOpenPacketSize=1.0",
            "problem[0].context.remoteWindowFailureMaxSize=1234567890123",
            "problem[0].context.remoteWindowFailureSize=0 private-value",
            "context.remoteWindowFailurePacketSize=UNKNOWN",
            "context.remoteWindowFailureMessage=private-value",
            "context.remoteWindowOpenPeerAddress=private-host:22",
            "context.remoteWindowOpenPath=/private/source");
        assertTrue(DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(String.join("\n", privateFacts)).isEmpty());
    }
}
