package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAssistedEffectEvidenceRegressionTest {
    @Test
    void fixedUploadCarriesActualWorkflowAndSourceButStillNeedsItsExactHostGrant() throws Exception {
        try (var h = new Harness()) {
            h.reply("APPROVE", "Exact frozen upload", true);
            var result = h.approve();
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertSame(h.grant, result.value().orElseThrow());
            assertEquals(1, h.grants.get());
            var facts = new ObjectMapper().readTree(h.fixture.requests.getFirst().messages().getLast().text());
            assertEquals(h.fixture.task.id().toString(), facts.at("/currentCommandEvidence/workflowAuthorization/task").asText());
            assertEquals("selected source and deployment", facts.at("/currentCommandEvidence/workflowAuthorization/frozenWorkflowRequest").asText());
            assertEquals("a".repeat(64), facts.at("/currentCommandEvidence/sourceSelection/sourceRevision").asText());
            assertEquals(h.intent.exactAction(), facts.path("exactAction").asText());
        }
    }

    @Test
    void fixedEffectDenialKeepsTheReasonAndNeverAsksForAHostGrant() throws Exception {
        try (var h = new Harness()) {
            h.reply("DENY", "Source binding missing\npassword=fixture-credential", false);
            var failure = assertThrows(ApiException.class, h::approve);
            assertEquals("DEPLOYMENT_ASSISTED_REVIEW_DENIED", failure.problem().eventCode());
            assertTrue(failure.problem().context().get("reviewReason").startsWith("Source binding missing"));
            assertEquals(0, h.grants.get());
            var event = h.fixture.events.stream().filter(value -> value.eventCode().equals("DEPLOYMENT_ASSISTED_REVIEW_DENIED")).findFirst().orElseThrow();
            assertTrue(event.message().startsWith("Source binding missing"));
            assertFalse(event.message().contains("fixture-credential"));
            assertTrue(event.outcome().isEmpty());
        }
    }

    @Test
    void closingWorkflowDuringModelReviewPreventsTheFixedEffectGrant() throws Exception {
        try (var h = new Harness()) {
            h.reply("APPROVE", "Exact frozen upload", false);
            h.beforeReview = h.permission::close;
            assertThrows(IllegalArgumentException.class, h::approve);
            assertEquals(0, h.grants.get());
        }
    }

    private static final class Harness implements AutoCloseable {
        final NativeAiFixture fixture = new NativeAiFixture();

        final AtomicInteger grants = new AtomicInteger();

        final ApprovalGrant grant = new ApprovalGrant(UUID.randomUUID());

        final ServerId server = new ServerId("fixture-server");

        final ActionIntent intent = new ActionIntent("platform.transfer.upload", List.of(server.id()), "candidate", "b".repeat(64),
            "Upload the frozen archive to the exact owned candidate", "Transfer source data only")
            .withSemantics(new ActionSemantics("deployment.candidate.upload.v1", Set.of(new ActionTarget(server.id(), "deployment-candidate", "/fixture/candidate/mutable/source.tar.gz"))));

        final DeploymentAssistedScope scope;

        final WorkflowGrant permission;

        final InteractionService host;

        Throwing beforeReview = () -> {
        };

        Harness() throws Exception {
            host = proxy(InteractionService.class, (p, m, a) -> {
                if (m.getName().equals("openReviewedWorkflow"))
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> {
                    }), Optional.empty());
                assertEquals("approveReviewedAction", m.getName());
                assertSame(intent, a[0]);
                assertEquals(Optional.empty(), a[1]);
                assertSame(fixture.task, a[4]);
                grants.incrementAndGet();
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
            });
            var ai = proxy(AiService.class, (p, m, a) -> {
                if (m.getName().equals("call")) beforeReview.run();
                return invoke(fixture.context.ai(), m, a);
            });
            var context = proxy(ModContext.class, (p, m, a) -> switch (m.getName()) {
                case "ai" -> ai;
                case "interaction" -> host;
                default -> invoke(fixture.context, m, a);
            });
            scope = new DeploymentAssistedScope(context, fixture.task, fixture.configurationRevision, "AUTOMATIC", true);
            permission = DeploymentWorkflowAuthorization.open(context, fixture.task, server, fixture.configurationRevision, "AUTOMATIC", true, "selected source and deployment");
            scope.workflowOpened(permission);
            var id = UUID.randomUUID();
            var snapshot = proxy(FileSnapshot.class, (p, m, a) -> switch (m.getName()) {
                case "id" -> id;
                case "sha256" -> "a".repeat(64);
                default -> throw new AssertionError("No source reads expected");
            });
            scope.sourceCaptured(server, new DeploymentSourceMaterial("fixture", snapshot, List.of("Cargo.toml"), List.of()), fixture.task);
        }

        void reply(String decision, String reason, boolean confirmation) throws Exception {
            fixture.nativeReply(new AiToolCall(UUID.randomUUID().toString(), "submit_result", new ObjectMapper().writeValueAsString(
                Map.of("decision", decision, "reason", reason, "requiresUserConfirmation", confirmation))));
        }

        OperationResult<ApprovalGrant> approve() throws ApiException {
            return DeploymentAssistedApproval.approve(scope, host, intent, fixture.task, fixture.configurationRevision, "AUTOMATIC", true);
        }

        public void close() throws ApiException {
            permission.close();
        }
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
