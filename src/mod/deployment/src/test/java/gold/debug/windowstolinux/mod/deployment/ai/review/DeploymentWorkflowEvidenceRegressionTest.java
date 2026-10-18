package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Workflow evidence cannot become a command grant or survive its original scope. */
class DeploymentWorkflowEvidenceRegressionTest {
    private final UUID id = UUID.randomUUID();

    private final TaskContext task = task(id);

    private final ServerId server = new ServerId("fixture-server");

    @Test
    void preservesTheActualHandoffAndClosesItsOriginalHandleOnce() throws Exception {
        var closed = new AtomicInteger();
        try (var permission = open(() -> closed.incrementAndGet())) {
            var facts = DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), server.id()));
            assertEquals(id.toString(), facts.get("task"));
            assertEquals(List.of(server.id()), facts.get("serverTargets"));
            assertEquals("selected source and deployment prerequisites", facts.get("frozenWorkflowRequest"));
            assertEquals("AUTOMATIC", facts.get("approvalPolicy"));
            assertEquals(true, facts.get("hostHandoffOpened"));
            assertEquals(false, facts.get("recovery"));
            assertFalse(facts.containsKey("commandGrant"));
            permission.close();
            assertThrows(IllegalArgumentException.class,
                () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), server.id())));
        }
        assertEquals(1, closed.get());
    }

    @Test
    void rejectsSubstitutedTaskServerResourceAndForgedHandle() throws Exception {
        try (var permission = open(() -> {
        })) {
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task(id), command(server.id(), server.id())));
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command("other-server", "other-server")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), "other-server")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(() -> {
            }, task, command(server.id(), server.id())));
        }
    }

    @Test
    void failedCleanupRevokesEvidenceButStillRetriesTheOriginalRelease() throws Exception {
        var releases = new AtomicInteger();
        var permission = open(() -> {
            if (releases.incrementAndGet() == 1)
                throw new ApiException(new Problem(ProblemCode.IO_FAILURE, "FIXTURE_RELEASE", "release failed", java.util.Map.of(), Optional.empty()));
        });
        assertThrows(ApiException.class, permission::close);
        assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), server.id())));
        permission.close();
        permission.close();
        assertEquals(2, releases.get());
    }

    @Test
    void assistedScopeKeepsOneHandoffAndRejectsClosedOrDifferentPolicyEvidence() throws Exception {
        var ai = stub(gold.debug.windowstolinux.appapi.ai.AiService.class, (proxy, method, args) -> {
            assertEquals("configurationRevision", method.getName());
            return "model-revision";
        });
        var context = stub(ModContext.class, (proxy, method, args) -> {
            assertEquals("ai", method.getName());
            return ai;
        });
        var scope = new gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope(
            context, task, "model-revision", "AUTOMATIC", true);
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            assertTrue(scope.facts(command(server.id(), server.id()), null, task).containsKey("workflowAuthorization"));
            assertThrows(IllegalStateException.class, () -> scope.workflowOpened(permission));
            var changedPolicy = new gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope(
                context, task, "model-revision", "AUTOMATIC", false);
            changedPolicy.workflowOpened(permission);
            assertThrows(IllegalArgumentException.class, () -> changedPolicy.facts(command(server.id(), server.id()), null, task));
            permission.close();
            assertThrows(IllegalArgumentException.class, () -> scope.verify(command(server.id(), server.id()), null, task));
        }
    }

    private WorkflowGrant open(WorkflowGrant original) throws Exception {
        var interaction = stub(InteractionService.class, (proxy, method, args) -> {
            assertEquals("openReviewedWorkflow", method.getName());
            assertSame(task, args[4]);
            assertEquals(CommandReviewMode.AUTOMATIC, args[1]);
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(original), Optional.empty());
        });
        var context = stub(ModContext.class, (proxy, method, args) -> {
            assertEquals("interaction", method.getName());
            return interaction;
        });
        return DeploymentWorkflowAuthorization.open(context, task, server, "model-revision", "AUTOMATIC", true,
            "selected source and deployment prerequisites");
    }

    private static TaskContext task(UUID id) {
        return stub(TaskContext.class, (proxy, method, args) -> {
            assertEquals("id", method.getName());
            return id;
        });
    }

    private static ActionIntent command(String server, String resourceServer) {
        return new ActionIntent("platform.ssh.execute", List.of(server), "fixture", "business-revision", "exact", "effect")
            .withSemantics(new ActionSemantics("fixture.command", Set.of(new ActionTarget(resourceServer, "directory", "/fixture"))));
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
