package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics;
import org.junit.jupiter.api.Test;

class DeploymentActiveDiagnosticsProjectionTest {
    private static final UUID TASK = UUID.fromString("de4f5e16-3a81-471b-9cf8-a851374fe654");

    @Test
    void activeDiagnosticProjectionRetainsSanitizedDenialWithoutReopeningRuntime() throws Exception {
        for (String code : List.of("DEPLOYMENT_COMMAND_REVIEW_DENIED", "DEPLOYMENT_ASSISTED_REVIEW_DENIED")) {
            var lines = new ArrayList<String>();
            var writes = new AtomicInteger();
            var saved = new ArrayList<LogEvent>();
            var context = context(logs(lines::add, writes, saved, null));
            String reason = "Ownership evidence is missing\npassword=private-password\nBearer private-bearer\n"
                + "-----BEGIN PRIVATE KEY-----\nprivate-key\n-----END PRIVATE KEY-----\n"
                + "Remaining installation effects require review.\u2028" + "x".repeat(5000) + "private-tail";
            if (code.equals("DEPLOYMENT_COMMAND_REVIEW_DENIED"))
                DeploymentDiagnostics.commandReviewDenied(context, task(), reason);
            else DeploymentDiagnostics.assistedReviewDenied(context, task(), reason);
            assertEquals(1, writes.get());
            var event = saved.getFirst();
            assertEquals(code, event.eventCode());
            assertTrue(event.message().length() <= 4096);
            String projection = lines.stream().filter(line -> line.contains("reviewReasonJson=")).findFirst().orElseThrow();
            assertTrue(projection.contains("stage=LOG_EVENT state=ATTEMPT"));
            assertTrue(projection.contains("event=" + code + " declaredOutcome=NONE"));
            assertTrue(projection.contains("reasonUnavailable=false"));
            String retained = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                projection.substring(projection.indexOf("reviewReasonJson=") + "reviewReasonJson=".length())).textValue();
            assertEquals(event.message(), retained);
            assertTrue(retained.contains("Ownership evidence is missing"));
            assertTrue(retained.contains("Remaining installation effects require review."));
            assertTrue(retained.length() <= 4096);
            assertFalse(projection.contains("\u2028"));
            assertTrue(lines.stream().noneMatch(line -> line.contains("private-") || line.contains("\n") || line.contains("\r")));
            assertTrue(lines.stream().anyMatch(line -> line.contains("storage=DATABASE truncated=false hasProblem=false")));
            for (var sample : Map.of(
                "Bearer [credential content excluded]", "Bearer [credential content excluded]",
                "Bearer [credential content excluded] public explanation", "Bearer [credential content excluded] public explanation",
                "Bearer [credential content excluded], public explanation", "Bearer [credential content excluded], public explanation",
                "Bearer [credential content excluded]; public explanation", "Bearer [credential content excluded]; public explanation",
                "Bearer [credential content excluded]private-suffix", "Bearer [credential content excluded]",
                "Bearer [credential content excluded]private-suffix public explanation", "Bearer [credential content excluded] public explanation",
                "Bearer [credential content excluded]private-suffix, public explanation", "Bearer [credential content excluded], public explanation",
                "Bearer [credential content excluded]private-suffix; public explanation", "Bearer [credential content excluded]; public explanation").entrySet()) {
                lines.clear();
                saved.clear();
                int priorWrites = writes.get();
                if (code.equals("DEPLOYMENT_COMMAND_REVIEW_DENIED"))
                    DeploymentDiagnostics.commandReviewDenied(context, task(), sample.getKey());
                else DeploymentDiagnostics.assistedReviewDenied(context, task(), sample.getKey());
                assertEquals(priorWrites + 1, writes.get());
                assertEquals(sample.getValue(), saved.getFirst().message());
                String projected = lines.stream().filter(line -> line.contains("reviewReasonJson=")).findFirst().orElseThrow();
                assertEquals(sample.getValue(), new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    projected.substring(projected.indexOf("reviewReasonJson=") + "reviewReasonJson=".length())).textValue());
                assertTrue(lines.stream().noneMatch(line -> line.contains("private-suffix")));
            }
        }
    }

    @Test
    void activeOperationMetadataPreservesClassCodeAndNeverReadsPayload() {
        var lines = new ArrayList<String>();
        var writes = new AtomicInteger();
        var context = context(logs(lines::add, writes, new ArrayList<>(), null));
        var failure = new BodyRejectingApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED",
            "private-message", Map.of("command", "private-command", "stdin", "private-stdin", "reviewReason", "private-reason"), Optional.empty()));
        failure.initCause(new java.io.IOException("private-cause"));
        DeploymentDiagnostics.operationFailureMetadata(context, task(), failure);
        assertEquals(1, writes.get());
        String projection = lines.stream().filter(line -> line.contains("diagnosticMetadata=AVAILABLE")).findFirst().orElseThrow();
        assertTrue(projection.contains("exceptionType=" + failure.getClass().getName()));
        assertTrue(projection.contains("causeType=java.io.IOException"));
        assertTrue(projection.contains("problemCode=ACCESS_DENIED problemEvent=DEPLOYMENT_COMMAND_REVIEW_DENIED"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("private-") || line.contains("reviewReasonJson=")));
        DeploymentDiagnostics.operationFailureMetadata(context, task(), new BodyRejectingApiException(new Problem(
            ProblemCode.INVALID_INPUT, "DEPLOYMENT_BAD\nprivate-event", "private-message", Map.of("stdin", "private-stdin"), Optional.empty())));
        assertEquals(2, writes.get());
        assertTrue(lines.stream().anyMatch(line -> line.contains("diagnosticMetadata=AVAILABLE") && line.contains("problemCode=INVALID_INPUT")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("private-") || line.contains("problemEvent=DEPLOYMENT_BAD")));
    }

    @Test
    void activeOperationMetadataProjectsOnlyFiniteSourceReviewDiagnostics() {
        var lines = new ArrayList<String>();
        var writes = new AtomicInteger();
        var context = context(logs(lines::add, writes, new ArrayList<>(), null));
        var details = Map.of("reviewStage", "EVIDENCE_READ", "reviewCode", "EVIDENCE_NO_PROGRESS",
            "reviewPageCount", "3", "reviewNewCharacters", "0", "reviewRepeatedBatches", "2", "stdin", "private-stdin");
        var problem = new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_COMMAND_REVIEW_INVALID",
            "private-message", details, Optional.empty());
        DeploymentDiagnostics.operationFailureMetadata(context, task(), new BodyRejectingApiException(problem));
        assertEquals(1, writes.get());
        assertTrue(lines.stream().anyMatch(line -> line.contains("reviewStage=EVIDENCE_READ reviewCode=EVIDENCE_NO_PROGRESS"
            + " reviewPageCount=3 reviewNewCharacters=0 reviewRepeatedBatches=2")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("private-")));
        String injected = "exceptionType=java.io.IOException problemCode=INVALID_INPUT problemEvent=DEPLOYMENT_COMMAND_REVIEW_INVALID"
            + " reviewStage=EVIDENCE_READ reviewCode=EVIDENCE_NO_PROGRESS reviewPageCount=9999999";
        assertEquals("diagnosticMetadata=UNAVAILABLE", assertDoesNotThrow(() ->
            DeploymentStaticLiveAcceptanceTest.liveDiagnosticProjection(event("DEPLOYMENT_OPERATION_FAILURE", injected, Optional.empty()))));
    }

    @Test
    void activeDiagnosticProjectionRejectsUnexpectedSchemasAndFreeformBodies() throws Exception {
        var lines = new ArrayList<String>();
        var writes = new AtomicInteger();
        var observed = logs(lines::add, writes, new ArrayList<>(), null);
        for (String message : List.of("private-body", "exceptionType=external.PrivateFailure",
            "exceptionType=java.lang.IllegalStateException command=private-command",
            "exceptionType=java.lang.IllegalStateException problemCode=PRIVATE_CODE",
            "exceptionType=java.lang.IllegalStateException problemCode=UNKNOWN problemEvent=PRIVATE_EVENT",
            "exceptionType=java.lang.IllegalStateException causeType=private-cause",
            "exceptionType=java.lang.IllegalStateException causeType=java.io.IOException causeType=private-cause",
            "exceptionType=java.lang.IllegalStateException\nprivate-tail", "x".repeat(1025))) {
            lines.clear();
            observed.write(null, event("DEPLOYMENT_OPERATION_FAILURE", message, Optional.empty()));
            assertTrue(lines.stream().anyMatch(line -> line.contains("diagnosticMetadata=UNAVAILABLE")));
            assertTrue(lines.stream().noneMatch(line -> line.contains("private-") || line.contains("exceptionType=") || line.contains("PRIVATE_CODE")));
        }
        for (var event : List.of(
            event("DEPLOYMENT_OPERATION_FAILURE", "exceptionType=java.lang.IllegalStateException", Optional.of("private-details")),
            new LogEvent(LogLevel.WARN, "DEPLOYMENT_OPERATION_FAILURE", "exceptionType=java.lang.IllegalStateException", Optional.empty(), Optional.of(TASK), Optional.of(TASK), Optional.empty()),
            new LogEvent(LogLevel.ERROR, "DEPLOYMENT_OPERATION_FAILURE", "exceptionType=java.lang.IllegalStateException", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            new LogEvent(LogLevel.ERROR, "DEPLOYMENT_OPERATION_FAILURE", "exceptionType=java.lang.IllegalStateException", Optional.empty(), Optional.of(TASK), Optional.of(UUID.randomUUID()), Optional.empty()),
            new LogEvent(LogLevel.ERROR, "DEPLOYMENT_OPERATION_FAILURE", "exceptionType=java.lang.IllegalStateException", Optional.empty(), Optional.of(TASK), Optional.of(TASK), Optional.of(TaskResult.FAILED)))) {
            lines.clear();
            observed.write(null, event);
            assertTrue(lines.stream().anyMatch(line -> line.contains("diagnosticMetadata=UNAVAILABLE")));
            assertTrue(lines.stream().noneMatch(line -> line.contains("private-details") || line.contains("exceptionType=")));
        }
        for (var event : List.of(
            event("DEPLOYMENT_COMMAND_REVIEW_DENIED", "private-body", Optional.of("private-details")),
            event("DEPLOYMENT_COMMAND_REVIEW_DENIED", " ", Optional.empty()),
            event("DEPLOYMENT_COMMAND_REVIEW_DENIED", "x".repeat(4097), Optional.empty()),
            new LogEvent(LogLevel.INFO, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "private-body", Optional.empty(), Optional.of(TASK), Optional.of(TASK), Optional.empty()),
            new LogEvent(LogLevel.WARN, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "private-body", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            new LogEvent(LogLevel.WARN, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "private-body", Optional.empty(), Optional.of(TASK), Optional.empty(), Optional.empty()),
            new LogEvent(LogLevel.WARN, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "private-body", Optional.empty(), Optional.of(TASK), Optional.of(UUID.randomUUID()), Optional.empty()),
            new LogEvent(LogLevel.WARN, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "private-body", Optional.empty(), Optional.of(TASK), Optional.of(TASK), Optional.of(TaskResult.FAILED)))) {
            lines.clear();
            observed.write(null, event);
            assertTrue(lines.stream().anyMatch(line -> line.contains("reasonUnavailable=true")));
            assertTrue(lines.stream().noneMatch(line -> line.contains("private-") || line.contains("reviewReasonJson=")));
        }
        lines.clear();
        observed.write(null, event("DEPLOYMENT_OTHER_EVENT", "private-body", Optional.of("private-details")));
        assertTrue(lines.isEmpty(), "An unrelated event must not enter the diagnostic projection");
    }

    @Test
    void diagnosticProjectionOrLogWriteFailureCannotReplaceOriginalUnknown() throws Exception {
        var unknown = new OperationResult<Object>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(
            ProblemCode.UNKNOWN, "DEPLOYMENT_GRAPH_REMOTE_UNCONFIRMED", "private-result", Map.of(), Optional.empty())));
        var failures = new ArrayList<Throwable>();
        failures.add(null);
        failures.add(new ApiException(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_LOG_UNAVAILABLE", "private-log", Map.of(), Optional.empty())));
        failures.add(new IllegalStateException("private-log"));
        failures.add(new AssertionError("private-log"));
        failures.add(new LinkageError("private-log"));
        for (Throwable logFailure : failures) {
            var lines = new ArrayList<String>();
            var observer = new DeploymentStaticLiveAcceptanceTest.LiveBoundaryObserver(line -> {
                if (logFailure == null && line.contains("diagnosticMetadata=AVAILABLE")) throw new AssertionError("private-sink");
                lines.add(line);
            });
            var writes = new AtomicInteger();
            var actual = delegate(writes, new ArrayList<>(), logFailure);
            var context = context(DeploymentStaticLiveAcceptanceTest.observedLogService(() -> actual, observer));
            var calls = new AtomicInteger();
            var original = new BodyRejectingApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED",
                "private-original", Map.of("stdin", "private-stdin"), Optional.empty()));
            var author = DeploymentStaticLiveAcceptanceTest.observedAuthorWork(task -> {
                calls.incrementAndGet();
                DeploymentDiagnostics.operationFailureMetadata(context, task, original);
                return unknown;
            }, observer);
            assertSame(unknown, author.run(task()));
            assertEquals(1, calls.get());
            assertEquals(1, writes.get());
            assertEquals("DEPLOYMENT_GRAPH_REMOTE_UNCONFIRMED", unknown.problem().orElseThrow().eventCode());
            assertEquals(TaskResult.UNKNOWN, unknown.outcome());
            assertTrue(lines.stream().anyMatch(line -> line.contains("stage=AUTHOR state=RETURN") && line.contains("outcome=UNKNOWN")));
            if (logFailure == null) assertTrue(lines.stream().anyMatch(line -> line.contains("observerGaps=1")));
            else assertTrue(lines.stream().anyMatch(line -> line.contains("stage=LOG_WRITE state=THROW")));
            assertTrue(lines.stream().noneMatch(line -> line.contains("private-")));
        }
    }

    private static LogEvent event(String code, String message, Optional<String> details) {
        return new LogEvent(code.equals("DEPLOYMENT_OPERATION_FAILURE") ? LogLevel.ERROR : LogLevel.WARN,
            code, message, details, Optional.of(TASK), Optional.of(TASK), Optional.empty());
    }

    private static LogService logs(Consumer<String> sink, AtomicInteger writes, List<LogEvent> saved, Throwable failure) {
        var actual = delegate(writes, saved, failure);
        return DeploymentStaticLiveAcceptanceTest.observedLogService(() -> actual,
            new DeploymentStaticLiveAcceptanceTest.LiveBoundaryObserver(sink));
    }

    private static LogService delegate(AtomicInteger writes, List<LogEvent> saved, Throwable failure) {
        return (LogService) Proxy.newProxyInstance(DeploymentActiveDiagnosticsProjectionTest.class.getClassLoader(), new Class<?>[]{LogService.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "register" -> new LogTarget(UUID.randomUUID());
                case "write" -> {
                    writes.incrementAndGet();
                    saved.add((LogEvent) args[1]);
                    if (failure != null) throw failure;
                    yield new LogWriteResult(UUID.randomUUID(), LogStorage.DATABASE, false, Optional.empty());
                }
                default -> throw new AssertionError("No read, reopen, recovery or replay capability: " + method.getName());
            });
    }

    private static ModContext context(LogService logs) {
        return (ModContext) Proxy.newProxyInstance(DeploymentActiveDiagnosticsProjectionTest.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                assertEquals("logs", method.getName(), "Diagnostic projection may only use the original LogService");
                return logs;
            });
    }

    private static TaskContext task() {
        return (TaskContext) Proxy.newProxyInstance(DeploymentActiveDiagnosticsProjectionTest.class.getClassLoader(), new Class<?>[]{TaskContext.class},
            (proxy, method, args) -> {
                assertEquals("id", method.getName(), "No extra task query");
                return TASK;
            });
    }

    private static final class BodyRejectingApiException extends ApiException {
        BodyRejectingApiException(Problem problem) { super(problem); }

        @Override
        public String getMessage() {
            throw new AssertionError("Arbitrary exception messages must not be read");
        }
    }
}
