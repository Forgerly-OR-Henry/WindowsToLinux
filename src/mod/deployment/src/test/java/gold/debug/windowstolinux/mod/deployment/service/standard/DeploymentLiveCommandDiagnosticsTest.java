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
    void rawReviewShapeExcludesBodiesAndRejectsAmbiguousJson() {
        String shape = DeploymentStaticLiveAcceptanceTest.rawReviewSubmissionShape(
            "{\"decision\":\"DENY\",\"reason\":\"private-value\",\"requiresUserConfirmation\":false}");
        assertTrue(shape.contains("rawDecision=DENY"));
        assertTrue(shape.contains("rawRequiresUserConfirmation=false"));
        assertFalse(shape.contains("private-value"));
        for (var input : List.of(
            "{\"decision\":\"APPROVE\",\"decision\":\"DENY\"}",
            "{\"decision\":\"APPROVE\"} {}",
            "{\"reason\":" + "[".repeat(18) + "0" + "]".repeat(18) + "}")) {
            assertTrue(DeploymentStaticLiveAcceptanceTest.rawReviewSubmissionShape(input).contains("shape=INVALID"));
        }
        var malformed = DeploymentStaticLiveAcceptanceTest.rawReviewSubmissionShape(
            "{\"decision\":\"private-value\",\"reason\":{\"secret\":\"private-value\"},\"requiresUserConfirmation\":\"private-value\"}");
        assertTrue(malformed.contains("rawDecision=OTHER"));
        assertTrue(malformed.contains("rawRequiresUserConfirmation=UNAVAILABLE"));
        assertFalse(malformed.contains("private-value"));
        assertTrue(DeploymentStaticLiveAcceptanceTest.rawReviewSubmissionShape("x".repeat(16385)).contains("shape=TOO_LARGE"));
    }

    @Test
    void hostBoundaryPreservesUnknownResultAndOriginalFailureWithOneCall() throws Throwable {
        var lines = new ArrayList<String>();
        var observer = new DeploymentStaticLiveAcceptanceTest.LiveBoundaryObserver(lines::add);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var problem = new Problem(ProblemCode.UNKNOWN, "HOST_GRANT_UNCONFIRMED", "private-value",
            Map.of("secret", "private-value"), Optional.empty());
        var result = new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(
            gold.debug.windowstolinux.appapi.task.result.TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem));
        assertSame(result, observer.call(DeploymentStaticLiveAcceptanceTest.LiveBoundaryPhase.HOST_GRANT, () -> TASK,
            () -> { calls.incrementAndGet(); return result; }));
        var original = new BodyRejectingApiException(problem);
        assertSame(original, assertThrows(BodyRejectingApiException.class, () -> observer.call(
            DeploymentStaticLiveAcceptanceTest.LiveBoundaryPhase.HOST_GRANT, () -> TASK,
            () -> { calls.incrementAndGet(); throw original; })));
        assertEquals(2, calls.get());
        assertTrue(lines.stream().anyMatch(line -> line.contains("outcome=UNKNOWN") && line.contains("grantPresent=false")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("event=HOST_GRANT_UNCONFIRMED")));
        assertEquals(1, lines.stream().filter(line -> line.contains("firstObserved=true")).count());
        assertTrue(lines.stream().noneMatch(line -> line.contains("private-value")));
    }

    @Test
    void failedLogWriteRetainsEventAttemptAndOriginalThrow() {
        var lines = new ArrayList<String>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var original = new BodyRejectingFailure();
        var event = boundaryEvent();
        var actual = (gold.debug.windowstolinux.appapi.task.log.LogService) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.task.log.LogService.class},
            (proxy, method, args) -> {
                assertEquals("write", method.getName());
                assertSame(event, args[1]);
                assertTrue(lines.stream().anyMatch(line -> line.contains("stage=LOG_EVENT state=ATTEMPT")));
                calls.incrementAndGet();
                throw original;
            });
        var observed = DeploymentStaticLiveAcceptanceTest.observedLogService(() -> actual,
            new DeploymentStaticLiveAcceptanceTest.LiveBoundaryObserver(lines::add));
        assertSame(original, assertThrows(BodyRejectingFailure.class, () -> observed.write(null, event)));
        assertEquals(1, calls.get());
        assertTrue(lines.stream().anyMatch(line -> line.contains("stage=LOG_WRITE state=THROW")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("private-value") || line.contains("state=RETURN")));
    }

    @Test
    void partialLogStorageIsReturnedUnchangedAndNotClaimedSaved() {
        var lines = new ArrayList<String>();
        var saved = new gold.debug.windowstolinux.appapi.task.log.LogWriteResult(UUID.randomUUID(),
            gold.debug.windowstolinux.appapi.task.log.LogStorage.EMERGENCY_FILE, true, Optional.empty());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var actual = (gold.debug.windowstolinux.appapi.task.log.LogService) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.task.log.LogService.class},
            (proxy, method, args) -> { assertEquals("write", method.getName()); calls.incrementAndGet(); return saved; });
        var observed = DeploymentStaticLiveAcceptanceTest.observedLogService(() -> actual,
            new DeploymentStaticLiveAcceptanceTest.LiveBoundaryObserver(lines::add));
        assertSame(saved, observed.write(null, boundaryEvent()));
        assertEquals(1, calls.get());
        assertTrue(lines.stream().anyMatch(line -> line.contains("storage=EMERGENCY_FILE truncated=true")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("private-value") || line.contains("storage=DATABASE")));
    }

    @Test
    void observedSubmitPreservesRequestContextHandleAndNeverQueriesState() throws Exception {
        var lines = new ArrayList<String>();
        var request = new gold.debug.windowstolinux.appapi.task.model.TaskRequest("fixture", "callback", List.of(), false);
        var context = boundaryTask();
        var result = new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(
            gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, Optional.of(new Object()), Optional.empty());
        var handle = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.run.TaskHandle.class},
            (proxy, method, args) -> { throw new AssertionError("No extra handle query: " + method.getName()); });
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var worked = new java.util.concurrent.atomic.AtomicInteger();
        var actual = (gold.debug.windowstolinux.appapi.task.TaskService) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class},
            (proxy, method, args) -> {
                assertEquals("submit", method.getName());
                assertSame(request, args[0]);
                submitted.incrementAndGet();
                assertSame(result, ((gold.debug.windowstolinux.appapi.task.run.TaskWork<?>) args[1]).run(context));
                return handle;
            });
        var observed = DeploymentStaticLiveAcceptanceTest.observedTaskService(() -> actual,
            new DeploymentStaticLiveAcceptanceTest.LiveBoundaryObserver(lines::add));
        assertSame(handle, observed.submit(request, task -> {
            worked.incrementAndGet(); assertSame(context, task); return result;
        }));
        assertEquals(1, submitted.get());
        assertEquals(1, worked.get());
        assertTrue(lines.stream().anyMatch(line -> line.contains("stage=AUTHOR state=ENTER")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("stage=AUTHOR state=RETURN")
            && line.contains("outcome=SUCCEEDED")), () -> "Observed typed rows: " + lines);
    }

    @Test
    void authorExitObservationKeepsUnknownAndNeverClaimsPlatformReturned() throws Exception {
        var lines = new ArrayList<String>();
        var observer = new DeploymentStaticLiveAcceptanceTest.LiveBoundaryObserver(lines::add);
        var result = new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(
            gold.debug.windowstolinux.appapi.task.result.TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(new Problem(ProblemCode.UNKNOWN, "ORIGINAL_UNKNOWN", "private-value", Map.of(), Optional.empty())));
        var observed = DeploymentStaticLiveAcceptanceTest.observedAuthorWork(task -> result, observer);
        assertSame(result, observed.run(boundaryTask()));
        observer.unknownSnapshot(new gold.debug.windowstolinux.appapi.task.model.TaskSnapshot(TASK, "fixture", "1",
            "deployment-publication", gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN, Optional.empty(),
            "private-value", java.time.Instant.now(), Optional.empty(), List.of("private-value")), false);
        assertTrue(lines.stream().anyMatch(line -> line.contains("futureDoneObserved=false")
            && line.contains("authorDelegateExitObserved=true") && line.contains("platformReturned=UNOBSERVED")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("private-value") || line.contains("platformReturned=true")));
    }

    @Test
    void failedBoundarySinkAndIdentityGetterNeverReplaceAuthorOrHostResults() throws Throwable {
        var observer = new DeploymentStaticLiveAcceptanceTest.LiveBoundaryObserver(line -> { throw new AssertionError("sink"); });
        var result = new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(
            gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, Optional.of(new Object()), Optional.empty());
        assertSame(result, observer.call(DeploymentStaticLiveAcceptanceTest.LiveBoundaryPhase.HOST_GRANT,
            () -> { throw new AssertionError("identity"); }, () -> result));
        var original = new BodyRejectingFailure();
        var callback = DeploymentStaticLiveAcceptanceTest.observedAuthorWork(task -> { throw original; }, observer);
        assertSame(original, assertThrows(BodyRejectingFailure.class, () -> callback.run(boundaryTask())));
        var returned = DeploymentStaticLiveAcceptanceTest.observedAuthorWork(task -> result, observer);
        assertSame(result, returned.run(boundaryTask()));
    }

    @Test
    void boundaryMarkerLimitReportsGapWithoutStoppingOriginalCalls() throws Throwable {
        var lines = new ArrayList<String>();
        var observer = new DeploymentStaticLiveAcceptanceTest.LiveBoundaryObserver(lines::add);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        for (int index = 0; index < 4500; index++)
            observer.call(DeploymentStaticLiveAcceptanceTest.LiveBoundaryPhase.HOST_GRANT, () -> TASK,
                () -> { calls.incrementAndGet(); return Boolean.TRUE; });
        assertEquals(4500, calls.get());
        assertEquals(8193, lines.size());
        assertEquals(1, lines.stream().filter(line -> line.contains("COVERAGE_GAP reason=MARKER_LIMIT")).count());
    }

    private static gold.debug.windowstolinux.appapi.task.log.LogEvent boundaryEvent() {
        return new gold.debug.windowstolinux.appapi.task.log.LogEvent(
            gold.debug.windowstolinux.appapi.task.log.LogLevel.ERROR, "DEPLOYMENT_OPERATION_FAILURE", "private-value",
            Optional.of("{\"secret\":\"private-value\"}"), Optional.of(TASK), Optional.empty(), Optional.empty());
    }

    private static gold.debug.windowstolinux.appapi.task.run.TaskContext boundaryTask() {
        return (gold.debug.windowstolinux.appapi.task.run.TaskContext) java.lang.reflect.Proxy.newProxyInstance(
            DeploymentLiveCommandDiagnosticsTest.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.run.TaskContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("id")) return TASK;
                throw new AssertionError("No extra task query: " + method.getName());
            });
    }

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
