package gold.debug.windowstolinux.mod.deployment.service.agent;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskRequest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.TimeUnit;


import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Reads original ended evidence through authentic new CLASS/H2 query callbacks. / 经真实新CLASS/H2查询回调读取原结束依据。 */
class DeploymentAgentHistoryTest extends DeploymentRuntimeFixture {
    @Test
    void endedEventsRemainBoundToSelectedUuidAndCannotBorrowOldContext() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var journal = new DeploymentAgentJournal(context.db());
            var original = context.tasks().submit(new TaskRequest(DeploymentAgentWorkflow.TASK_TYPE,
                "Original evidence owner", List.of(), true), task -> {
                journal.create(task, new DeploymentAgentJournal.Identity("evidence-only", "a".repeat(64),
                    "b".repeat(64), "MANUAL_REVIEW"));
                journal.event(task, "COMMAND_EXECUTING", "original-binding", "10");
                journal.event(task, "COMMAND_RESULT", "original-binding", "1");
                journal.state(task, "FAILED");
                return new OperationResult<Void>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(
                    ProblemCode.IO_FAILURE, "CONTROLLED_KNOWN_FAILURE", "Known closed test evidence",
                    Map.of(), Optional.empty())));
            });
            assertEquals(Outcome.FAILED, original.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
            var page = new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context));
            assertTrue(page.tasks().isEmpty());
            var history = page.history();
            var entry = history.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().getFirst();
            assertNotEquals(original.id(), history.id());
            assertEquals(original.id(), entry.id());
            assertEquals("FAILED", entry.state());
            var query = page.events(entry.id());
            var detail = query.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow();
            assertNotEquals(entry.id(), query.id());
            assertEquals(entry, detail.entry());
            assertEquals("b".repeat(64), detail.snapshot().identity().configurationDigest());
            assertEquals(List.of("COMMAND_EXECUTING", "COMMAND_RESULT", "STATE"),
                detail.events().stream().map(DeploymentAgentJournal.Event::type).toList());
            assertEquals("original-binding", detail.events().getFirst().action());
            var rejected = page.requestInspection(entry.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(Outcome.FAILED, rejected.outcome());
            assertEquals("DEPLOYMENT_AGENT_INSPECT_NOT_RETAINED", rejected.problem().orElseThrow().eventCode());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
