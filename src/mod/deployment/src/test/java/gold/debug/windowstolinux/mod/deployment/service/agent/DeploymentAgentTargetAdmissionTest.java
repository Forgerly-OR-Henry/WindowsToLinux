package gold.debug.windowstolinux.mod.deployment.service.agent;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskRequest;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.server.*;

import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Exercises actual serialized CLASS/H2 admission against retained original evidence. / 针对留存原依据验证真实串行CLASS/H2准入。 */
class DeploymentAgentTargetAdmissionTest extends DeploymentRuntimeFixture {
    @Test
    void concurrentAliasesCannotBothCreateAnOriginalAgent() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var journal = new DeploymentAgentJournal(context.db());
            String first = target("profile-first", "SAME.EXAMPLE", 22), second = target("profile-second", "same.example", 22);
            assertEquals(first, second);
            assertNotEquals(first, target("profile-second", "same.example", 23));
            var enter = new CountDownLatch(2);
            var decided = new CountDownLatch(2);
            var accepted = new AtomicInteger();
            var originals = new CopyOnWriteArrayList<UUID>();
            var handles = new ArrayList<TaskHandle<Void>>();
            for (String profile : List.of("profile-first", "profile-second"))
                handles.add(context.tasks().submit(request(), task -> {
                    enter.countDown();
                    assertTrue(enter.await(10, TimeUnit.SECONDS));
                    boolean inserted = false;
                    try {
                        journal.create(task, identity(profile), Optional.of(first));
                        inserted = true;
                        accepted.incrementAndGet();
                        originals.add(task.id());
                    } catch (ApiException denied) {
                        assertEquals("DEPLOYMENT_AGENT_TARGET_BUSY", denied.problem().eventCode());
                        assertNotEquals(task.id().toString(), denied.problem().context().get("originalTask"));
                    } finally {
                        decided.countDown();
                    }
                    assertTrue(decided.await(10, TimeUnit.SECONDS));
                    if (inserted) journal.state(task, "SUCCEEDED");
                    return success();
                }));
            for (var handle : handles)
                assertEquals(Outcome.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            assertEquals(1, accepted.get());
            assertEquals(1, originals.size());
            try (var reader = scope(runtime, true); var connection = reader.openConnection(); var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT COUNT(*) FROM deployment_agent_task")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void wrongTerminalCannotHideAnUnfinishedDispatchAcrossReopen() throws Exception {
        assertRetainedBlock(false, "DEPLOYMENT_AGENT_TARGET_BUSY");
    }

    @Test
    void oldOpaqueTargetCannotBeGuessedAcrossReopen() throws Exception {
        assertRetainedBlock(true, "DEPLOYMENT_AGENT_TARGET_UNCONFIRMED");
    }

    private void assertRetainedBlock(boolean opaque, String expected) throws Exception {
        enable();
        UUID original;
        String endpoint = target("original-profile", "same.example", 22);
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var journal = new DeploymentAgentJournal(context.db());
            var seed = context.tasks().submit(request(), task -> {
                journal.create(task, identity("original-profile"), opaque ? Optional.empty() : Optional.of(endpoint));
                journal.event(task, "COMMAND_EXECUTING", "owned-build", "original-intent");
                journal.event(task, "PATCH_RESULT", "owned-build", "unrelated-receipt");
                journal.state(task, "FAILED");
                return success();
            });
            original = seed.id();
            assertEquals(Outcome.SUCCEEDED, seed.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        }
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var journal = new DeploymentAgentJournal(context.db());
            var attempt = context.tasks().submit(request(), task -> {
                var denied = assertThrows(ApiException.class, () -> journal.create(task, identity("alias-profile"), Optional.of(endpoint)));
                assertEquals(expected, denied.problem().eventCode());
                assertEquals(original.toString(), denied.problem().context().get("originalTask"));
                assertEquals(Optional.of(original), denied.problem().diagnosticId());
                assertTrue(journal.find(task).isEmpty());
                var originalFacts = journal.find(task, original).orElseThrow();
                assertEquals("FAILED", originalFacts.state());
                assertFalse(DeploymentAgentJournal.effectsSettled(journal.events(task, original)));
                return success();
            });
            assertEquals(Outcome.SUCCEEDED, attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    private static TaskRequest request() {
        return new TaskRequest(DeploymentAgentWorkflow.TASK_TYPE, "Agent admission evidence", List.of(), true);
    }

    private static DeploymentAgentJournal.Identity identity(String profile) {
        return new DeploymentAgentJournal.Identity(profile, "a".repeat(64), "b".repeat(64), "MANUAL_REVIEW");
    }

    private static OperationResult<Void> success() {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    private static String target(String profile, String host, int port) {
        return DeploymentAgentWorkflow.physicalTarget(new ServerInfo(new ServerRef(profile), profile, host, port, "different-account",
            Optional.of("SHA256:original-fingerprint"), Optional.of(Instant.EPOCH)));
    }
}
