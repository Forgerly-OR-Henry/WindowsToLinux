package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Retains exact command and patch completion proof across real CLASS/H2 reopen. / 跨真实CLASS/H2重开保留精确命令与补丁完成证明。 */
class DeploymentAgentEffectEvidenceTest extends DeploymentRuntimeFixture {
    @ParameterizedTest
    @ValueSource(strings = {"PREPARE", "COMMAND", "PATCH"})
    void lowerRiskUnsentReceiptRequiresOriginalKindActionAndReasonAfterReopen(String kind) throws Exception {
        enable();
        UUID original;
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var journal = new DeploymentAgentActionLog(context.db());
            var work = context.tasks().submit(new TaskRequest("agent-unsent-proof", "Persist exact known-unsent evidence", List.of(), false), task -> {
                journal.create(task, new DeploymentAgentActionLog.Identity("evidence-only", "a".repeat(64), "b".repeat(64), "AUTOMATIC", "FULL"));
                String other = kind.equals("COMMAND") ? "PATCH" : "COMMAND";
                journal.event(task, kind + "_EXECUTING", "original-binding", "attempt-boundary");
                journal.event(task, other + "_NOT_SENT", "original-binding", "LOWER_RISK_REQUIRED");
                journal.event(task, kind + "_NOT_SENT", "different-binding", "LOWER_RISK_REQUIRED");
                journal.event(task, kind + "_NOT_SENT", "original-binding", "UNCONFIRMED");
                assertFalse(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_NOT_SENT", "original-binding", "LOWER_RISK_REQUIRED");
                assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_EXECUTING", "original-binding", "new-attempt-boundary");
                assertFalse(DeploymentAgentActionLog.effectsSettled(journal.events(task)), "A previous unsent receipt does not settle a later attempt");
                journal.event(task, kind + "_NOT_SENT", "original-binding", "LOWER_RISK_REQUIRED");
                journal.state(task, "SUCCEEDED");
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            original = work.id();
            assertEquals(TaskResult.SUCCEEDED, work.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        }
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var query = new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)).events(original);
            var value = query.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow();
            var events = value.events().stream().filter(event -> !event.type().equals("AUTHORIZATION_POLICY")).toList();
            assertFalse(DeploymentAgentActionLog.effectsSettled(events.subList(0, 4)));
            assertTrue(DeploymentAgentActionLog.effectsSettled(events.subList(0, 5)));
            assertFalse(DeploymentAgentActionLog.effectsSettled(events.subList(0, 6)));
            assertTrue(DeploymentAgentActionLog.effectsSettled(events));
            assertEquals(original, value.entry().id());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMAND", "PATCH"})
    void unrelatedReceiptsCannotReleaseOriginalEffectAfterReopen(String kind) throws Exception {
        enable();
        UUID original;
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var journal = new DeploymentAgentActionLog(context.db());
            var work = context.tasks().submit(new TaskRequest(DeploymentAgentWorkflow.TASK_TYPE,
                "Persist exact effect kinds", List.of(), true), task -> {
                journal.create(task, new DeploymentAgentActionLog.Identity("evidence-only", "a".repeat(64),
                    "b".repeat(64), "MANUAL_REVIEW", "GENERAL"));
                String other = kind.equals("COMMAND") ? "PATCH" : "COMMAND";
                journal.event(task, kind + "_EXECUTING", "shared-binding", "first");
                journal.event(task, other + "_RESULT", "shared-binding", "unrelated-kind");
                journal.event(task, "QUERY_RESULT", "shared-binding", "readonly");
                journal.event(task, kind + "_RESULT", "different-binding", "unrelated-action");
                assertFalse(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_RESULT", "shared-binding", "known-completion");
                assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_EXECUTING", "shared-binding", "second");
                journal.event(task, other + "_RESULT", "shared-binding", "still-unrelated");
                assertFalse(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_RESULT", "shared-binding", "second-known-completion");
                journal.state(task, "SUCCEEDED");
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            original = work.id();
            assertEquals(TaskResult.SUCCEEDED, work.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        }
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var query = new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)).events(original);
            var events = query.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events().stream()
                .filter(event -> !event.type().equals("AUTHORIZATION_POLICY")).toList();
            assertFalse(DeploymentAgentActionLog.effectsSettled(events.subList(0, 4)));
            assertTrue(DeploymentAgentActionLog.effectsSettled(events.subList(0, 5)));
            assertFalse(DeploymentAgentActionLog.effectsSettled(events.subList(0, 7)));
            assertTrue(DeploymentAgentActionLog.effectsSettled(events));
            assertEquals(original, query.completion().toCompletableFuture().get().value().orElseThrow().entry().id());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
