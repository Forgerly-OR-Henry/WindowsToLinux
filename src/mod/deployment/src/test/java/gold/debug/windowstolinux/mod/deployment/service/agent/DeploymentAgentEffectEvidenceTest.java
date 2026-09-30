package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Retains exact command and patch completion proof across real CLASS/H2 reopen. / 跨真实CLASS/H2重开保留精确命令与补丁完成证明。 */
class DeploymentAgentEffectEvidenceTest extends DeploymentRuntimeFixture {
    @ParameterizedTest @ValueSource(strings = {"COMMAND", "PATCH"})
    void unrelatedReceiptsCannotReleaseOriginalEffectAfterReopen(String kind) throws Exception {
        enable(); UUID original;
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var journal = new DeploymentAgentJournal(context.db());
            var work = context.tasks().submit(new TaskRequest(DeploymentAgentWorkflow.TASK_TYPE,
                    "Persist exact effect kinds", List.of(), true), task -> {
                journal.create(task, new DeploymentAgentJournal.Identity("evidence-only", "a".repeat(64),
                        "b".repeat(64), "MANUAL_REVIEW"));
                String other = kind.equals("COMMAND") ? "PATCH" : "COMMAND";
                journal.event(task, kind + "_EXECUTING", "shared-binding", "first");
                journal.event(task, other + "_RESULT", "shared-binding", "unrelated-kind");
                journal.event(task, "QUERY_RESULT", "shared-binding", "readonly");
                journal.event(task, kind + "_RESULT", "different-binding", "unrelated-action");
                assertFalse(DeploymentAgentJournal.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_RESULT", "shared-binding", "known-completion");
                assertTrue(DeploymentAgentJournal.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_EXECUTING", "shared-binding", "second");
                journal.event(task, other + "_RESULT", "shared-binding", "still-unrelated");
                assertFalse(DeploymentAgentJournal.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_RESULT", "shared-binding", "second-known-completion");
                journal.state(task, "SUCCEEDED");
                return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            original = work.id();
            assertEquals(Outcome.SUCCEEDED, work.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        }
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var query = new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)).events(original);
            var events = query.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
            assertFalse(DeploymentAgentJournal.effectsSettled(events.subList(0, 4)));
            assertTrue(DeploymentAgentJournal.effectsSettled(events.subList(0, 5)));
            assertFalse(DeploymentAgentJournal.effectsSettled(events.subList(0, 7)));
            assertTrue(DeploymentAgentJournal.effectsSettled(events));
            assertEquals(original, query.completion().toCompletableFuture().get().value().orElseThrow().entry().id());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
