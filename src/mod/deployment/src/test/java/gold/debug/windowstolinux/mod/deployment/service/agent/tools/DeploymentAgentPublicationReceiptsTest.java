package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;
import org.junit.jupiter.api.Test;

/** Controlled public-session receipts, without providers or SSH. */
class DeploymentAgentPublicationReceiptsTest {
    private static final ServerId SERVER = new ServerId("receipt-target");

    private static final String REVISION = "a".repeat(64);

    @Test
    void actualFixedChannelFailureRetainsExactComponentAndOriginalGrantWithoutExtraExecution() throws Exception {
        var fixture = fixture();
        var calls = new AtomicInteger();
        var approvals = new AtomicInteger();
        var grant = new ApprovalGrant(UUID.randomUUID());
        var borrowed = session((request, actualGrant) -> {
            assertSame(grant, actualGrant);
            calls.incrementAndGet();
            return success(new CommandResult(Optional.of(64), "private runtime stdout", "launcher: run.sh not found\n", false));
        });
        var receipt = new DeploymentAgentPublicationReceipts(borrowed, SERVER, REVISION, fixture.proposal());
        var interaction = (InteractionService) Proxy.newProxyInstance(InteractionService.class.getClassLoader(),
            new Class<?>[]{InteractionService.class}, (self, method, args) -> {
                assertEquals("approve", method.getName());
                approvals.incrementAndGet();
                return success(grant);
            });
        var task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class},
            (self, method, args) -> {
                throw new AssertionError(method.getName());
            });
        receipt.begin("publish");
        var result = new DeploymentPublishChannel(receipt, interaction, SERVER, task, REVISION)
            .healthCheck(fixture.unit(), fixture.health());
        assertEquals(TaskResult.FAILED, result.outcome());
        assertEquals("DEPLOYMENT_HEALTH_FAILED", result.problem().orElseThrow().eventCode());
        var facts = receipt.observation(result);
        var actual = entries(facts).getFirst();
        assertEquals("component", actual.get("component"));
        assertEquals(64, actual.get("exit"));
        assertEquals(true, actual.get("knownHealthFailure"));
        assertTrue(actual.get("requestSha256").toString().matches("[0-9a-f]{64}"));
        assertTrue(actual.get("stderr").toString().contains("run.sh not found"));
        assertFalse(DeploymentAiJson.facts(facts, 5700).contains("private runtime stdout"));
        assertEquals(1, calls.get());
        assertEquals(1, approvals.get());
        receipt.close();
        assertEquals(1, calls.get(), "Closing the observer cannot close or execute on the borrowed session");
    }

    @Test
    void stdoutExcludedAndStderrRedactedBeforeBoundedExcerpt() throws Exception {
        var fixture = fixture();
        var receipt = observer(success(new CommandResult(Optional.of(64), "stdout-private-value",
            "secret=stderr-private-value\n" + "public diagnostic\n".repeat(200), false)));
        receipt.begin("publish");
        execute(receipt, fixture);
        String facts = DeploymentAiJson.facts(receipt.observation(failed()), 5700);
        assertFalse(facts.contains("stdout-private-value"));
        assertFalse(facts.contains("stderr-private-value"));
        assertTrue(facts.contains("credential content excluded"));
        assertEquals(true, entries(receipt.observation(failed())).getFirst().get("stderrExcerptTruncated"));
    }

    @Test
    void changedScriptInputTimeoutOutputOrHealthCannotBecomeFixedHealthProof() throws Exception {
        var fixture = fixture();
        var calls = new AtomicInteger();
        var receipt = new DeploymentAgentPublicationReceipts(session((request, grant) -> {
            calls.incrementAndGet();
            return success(new CommandResult(Optional.of(64), "", "controlled", false));
        }), SERVER, REVISION, fixture.proposal());
        receipt.begin("publish");
        try (var expected = DeploymentAgentPublicationReceipts.healthRequest(fixture.unit(), fixture.health())) {
            var changed = List.of(
                new CommandRequest("printf application-health\n" + expected.script(), new byte[0], expected.timeout(), 4096),
                new CommandRequest(expected.script(), new byte[]{1}, expected.timeout(), 4096),
                new CommandRequest(expected.script(), new byte[0], Duration.ofSeconds(1), 4096),
                new CommandRequest(expected.script(), new byte[0], expected.timeout(), 2048),
                DeploymentAgentPublicationReceipts.healthRequest(fixture.unit(), new DeploymentHealthCheck.Process(9, 1)));
            for (var request : changed) {
                try (request) {
                    receipt.execute(request, new ApprovalGrant(UUID.randomUUID()));
                }
            }
        }
        assertEquals(5, calls.get());
        assertEquals(0, receipt.observation(failed()).get("capturedReceiptCount"));
        assertTrue(entries(receipt.observation(failed())).isEmpty());
    }

    @Test
    void nextOperationResetsReceiptsAndOnlyDurableWholeGraphEventConfirmsRollback() throws Exception {
        var receipt = observer(success(new CommandResult(Optional.of(64), "", "failed", false)));
        receipt.begin("publish");
        execute(receipt, fixture());
        receipt.journaled("ROLLBACK_RESULT", "component", "RESTORED");
        assertEquals(false, receipt.observation(failed()).get("rollbackConfirmed"));
        receipt.journaled("GRAPH_ROLLBACK_RESULT", "other-application", "RESTORED");
        assertEquals(false, receipt.observation(failed()).get("rollbackConfirmed"));
        receipt.journaled("GRAPH_ROLLBACK_RESULT", "application", "RESTORED");
        assertEquals(true, receipt.observation(failed()).get("rollbackConfirmed"));
        assertEquals(false, receipt.observation(unknown(Optional.empty())).get("rollbackConfirmed"));
        receipt.begin("verify");
        assertTrue(entries(receipt.observation(failed())).isEmpty());
        receipt.journaled("GRAPH_ROLLBACK_RESULT", "application", "RESTORED");
        assertEquals(false, receipt.observation(failed()).get("rollbackConfirmed"));
    }

    @Test
    void unknownTruncatedAndMissingExitCannotClaimKnownHealthFailure() throws Exception {
        for (var result : List.of(
            unknown(Optional.of(new CommandResult(Optional.of(64), "", "uncertain", false))),
            success(new CommandResult(Optional.of(64), "", "partial", true)),
            success(new CommandResult(Optional.empty(), "", "no exit", false)))) {
            var receipt = observer(result);
            receipt.begin("verify");
            execute(receipt, fixture());
            var facts = receipt.observation(unknown(Optional.empty()));
            assertEquals(false, facts.get("rollbackConfirmed"));
            assertEquals(false, entries(facts).getFirst().get("knownHealthFailure"));
        }
    }

    @Test
    void strictPublicationProjectionRejectsAdditionalDuplicateTrailingAndInconsistentFields() throws Exception {
        var receipt = observer(success(new CommandResult(Optional.of(64), "", "diagnostic", false)));
        receipt.begin("publish");
        execute(receipt, fixture());
        String body = DeploymentAiJson.facts(receipt.observation(failed()), 5700);
        var invalid = new ArrayList<String>();
        invalid.add("{\"outcome\":\"FAILED\"," + body.substring(1));
        invalid.add(body + "\n{}");
        invalid.add(body + "suffix");
        for (String fault : List.of("additional-root", "fake-stdout", "known-failure", "truncated-failure", "unknown-failure",
            "phase", "component", "request-sha", "exit", "fractional-exit", "transport", "stderr", "flag", "count", "list-flag", "event", "scope", "outcome", "unknown-rollback")) {
            var root = (ObjectNode) new ObjectMapper().readTree(body);
            var health = (ObjectNode) root.path("healthReceipts").get(0);
            switch (fault) {
                case "additional-root" -> root.put("extra", "untrusted");
                case "fake-stdout" -> health.put("stdout", "private-value");
                case "known-failure" -> health.put("knownHealthFailure", false);
                case "truncated-failure" -> health.put("transportTruncated", true);
                case "unknown-failure" -> health.put("transportOutcome", "UNKNOWN");
                case "phase" -> root.put("phase", "verify");
                case "component" -> health.put("component", "Component/invalid");
                case "request-sha" -> health.put("requestSha256", "not-a-request-digest");
                case "exit" -> health.put("exit", 256);
                case "fractional-exit" -> health.put("exit", 64.5);
                case "transport" -> health.put("transportOutcome", "NOT_OBSERVED");
                case "stderr" -> health.put("stderr", "x".repeat(801));
                case "flag" -> health.put("transportTruncated", "false");
                case "count" -> root.put("excludedReceiptCount", 1);
                case "list-flag" -> root.put("receiptListTruncated", true);
                case "event" -> root.put("event", "untrusted reason: value");
                case "scope" -> root.put("outputScope", "STDOUT_INCLUDED");
                case "outcome" -> root.put("outcome", "UNVERIFIED");
                case "unknown-rollback" -> {
                    root.put("outcome", "UNKNOWN");
                    root.put("rollbackConfirmed", true);
                }
                default -> throw new AssertionError(fault);
            }
            invalid.add(root.toString());
        }
        for (String value : invalid)
            assertThrows(Exception.class, () -> DeploymentAgentPublicationReceipts.safeFeedback("publish", "PUBLICATION_RESULT\n" + value), value);
    }

    @Test
    void strictProjectionPreservesKeywordMetadataAndRedactsOnlyStderr() throws Exception {
        var receipt = observer(success(new CommandResult(Optional.of(64), "", "diagnostic", false)));
        receipt.begin("publish");
        execute(receipt, fixture());
        var root = (ObjectNode) new ObjectMapper().readTree(DeploymentAiJson.facts(receipt.observation(failed()), 5700));
        var health = (ObjectNode) root.path("healthReceipts").get(0);
        health.put("component", "token-worker");
        health.put("stderr", "unknown option --token\npassword=private-value\n");
        String result = DeploymentAgentPublicationReceipts.safeFeedback("publish", "PUBLICATION_RESULT\n" + root).orElseThrow();
        var sanitized = new ObjectMapper().readTree(result.substring(result.indexOf('\n') + 1));
        assertEquals("FAILED", sanitized.path("outcome").asText());
        assertEquals("token-worker", sanitized.path("healthReceipts").get(0).path("component").asText());
        assertEquals(health.path("requestSha256"), sanitized.path("healthReceipts").get(0).path("requestSha256"));
        assertTrue(result.contains("unknown option --token"));
        assertFalse(result.contains("private-value"));
        assertTrue(result.contains("credential content excluded"));
    }

    @Test
    void allThreeExactPrefixesAreCheckedAndUnknownPrefixesNeverGetTypedExemption() throws Exception {
        var receipt = observer(success(new CommandResult(Optional.of(0), "HEALTHY=1\n", "", false)));
        receipt.begin("publish");
        execute(receipt, fixture());
        String publish = DeploymentAiJson.facts(receipt.observation(failed()), 5700);
        assertTrue(DeploymentAgentPublicationReceipts.safeFeedback("publish", "PUBLICATION_RESULT\n" + publish).isPresent());
        receipt.begin("verify");
        execute(receipt, fixture());
        String failedVerify = DeploymentAiJson.facts(receipt.observation(failed()), 5700);
        assertTrue(DeploymentAgentPublicationReceipts.safeFeedback("verify", "PUBLICATION_NOT_VERIFIED\n" + failedVerify).isPresent());
        String verified = DeploymentAiJson.facts(receipt.observation(new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty())), 5700);
        assertTrue(DeploymentAgentPublicationReceipts.safeFeedback("verify", "VERIFIED_CURRENT_GRAPH_AND_APPLICATION_HEALTH\n" + verified).isPresent());
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationReceipts.safeFeedback("verify", "PUBLICATION_NOT_VERIFIED\n" + verified));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationReceipts.safeFeedback("verify", "VERIFIED_CURRENT_GRAPH_AND_APPLICATION_HEALTH\n" + failedVerify));
        assertTrue(DeploymentAgentPublicationReceipts.safeFeedback("publish", "UNKNOWN_PUBLICATION_PREFIX\n" + publish).isEmpty());
        assertTrue(DeploymentAgentPublicationReceipts.safeFeedback("command", "PUBLICATION_RESULT\n" + publish).isEmpty());
    }

    @Test
    void repeatedReceiptsStayCompleteWithinModelFeedbackBudgetEvenWithEscapedDiagnostics() throws Exception {
        var receipt = observer(success(new CommandResult(Optional.of(64), "", "\u0001".repeat(4096), false)));
        receipt.begin("verify");
        for (int i = 0; i < 7; i++) execute(receipt, fixture());
        var facts = receipt.observation(failed());
        assertEquals(7, facts.get("capturedReceiptCount"));
        assertEquals(3, facts.get("excludedReceiptCount"));
        assertEquals(true, facts.get("receiptListTruncated"));
        assertTrue(DeploymentAiJson.facts(facts, 5700).length() <= 5700);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> facts) {
        return (List<Map<String, Object>>) facts.get("healthReceipts");
    }

    private static DeploymentAgentPublicationReceipts observer(OperationResult<CommandResult> result) {
        return new DeploymentAgentPublicationReceipts(session((request, grant) -> result), SERVER, REVISION, fixture().proposal());
    }

    private static void execute(DeploymentAgentPublicationReceipts receipt, Fixture fixture) throws Exception {
        try (var request = DeploymentAgentPublicationReceipts.healthRequest(fixture.unit(), fixture.health())) {
            receipt.execute(request, new ApprovalGrant(UUID.randomUUID()));
        }
    }

    private static OperationResult<Void> failed() {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INCOMPATIBLE,
            "DEPLOYMENT_HEALTH_FAILED", "Controlled failed health receipt", Map.of(), Optional.empty())));
    }

    private static <T> OperationResult<T> unknown(Optional<T> value) {
        return new OperationResult<>(TaskResult.UNKNOWN, value, Optional.of(new Problem(ProblemCode.UNKNOWN,
            "DEPLOYMENT_TEST_UNCONFIRMED", "Controlled unknown receipt", Map.of(), Optional.empty())));
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    @FunctionalInterface
    private interface Execution {
        OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant);
    }

    private static RemoteSession session(Execution execute) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
            (self, method, args) -> {
                assertEquals("execute", method.getName(), "The observer cannot initiate any other capability");
                return execute.execute((CommandRequest) args[0], (ApprovalGrant) args[1]);
            });
    }

    private record Fixture(DeploymentPublishResult proposal, DeploymentUnitIdentity unit,
                           DeploymentHealthCheck health) {
    }

    private static Fixture fixture() {
        var unit = DeploymentUnitIdentity.forManaged("application", SERVER, "b".repeat(64));
        var health = new DeploymentHealthCheck.Process(10, 1);
        var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true, new DeploymentCommand("run.sh", List.of()),
            "", List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
        var runtime = new DeploymentRuntimeDefinition.ManagedProcess(health, DeploymentIdentityMode.SYSTEMD_STATIC, workload);
        var config = new DeploymentUnitRuntimeConfig(health, Optional.empty(), DeploymentIdentityMode.SYSTEMD_STATIC, workload);
        var graph = new DeploymentApplicationGraph("application", "component", health, List.of(new DeploymentApplicationGraph.Part(
            "component", unit, config, List.of(), runtime, List.of(), new DeploymentResourceBindings(List.of(), Optional.of(List.of())))));
        var snapshot = DeploymentConfigSnapshot.create(unit.id(), 1, "v1", Instant.EPOCH, List.of());
        return new Fixture(new DeploymentPublishResult(graph, List.of(new DeploymentUnitCommit(unit, config,
            new DeploymentCurrentRelease(unit.id(), "c".repeat(64), Instant.EPOCH), snapshot, List.of()))), unit, health);
    }
}
