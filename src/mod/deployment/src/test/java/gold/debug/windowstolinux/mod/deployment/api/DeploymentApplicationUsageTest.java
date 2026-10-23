package gold.debug.windowstolinux.mod.deployment.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentUsageFixture;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Exact original-task delivery uses real owned H2 publication records and current authorized host facts. / 精确原任务交付使用实际所属H2发布记录及当前授权主机事实。 */
class DeploymentApplicationUsageTest extends DeploymentUsageFixture {
    @Test
    void completedOriginalTaskProducesOnlyFixedCommandsAndReviewedIpv6Endpoints() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            var original = seedUsage(runtime, usageGraph("demo", false, true), 1);
            var service = new DeploymentApplicationsService(context(runtime));
            var usage = await(service.usageForCompletedTask(original.snapshot().id())).value().orElseThrow().orElseThrow();
            assertEquals("demo", usage.application().id());
            assertEquals(3, usage.components().size());
            assertEquals(Set.of(DeploymentLifecycleAction.values()), usage.actions());
            var cli = usage.components().stream().filter(value -> value.id().equals("client")).findFirst().orElseThrow();
            assertEquals("sudo /usr/local/lib/windowstolinux/managed-helper app-client demo-client " + "a".repeat(64) + " --", cli.command().orElseThrow());
            var job = usage.components().stream().filter(value -> value.id().equals("job")).findFirst().orElseThrow();
            assertEquals(ApplicationRunState.INSTALLED, job.state());
            assertEquals(ApplicationAutostartState.NOT_APPLICABLE, job.autostart());
            assertTrue(job.command().orElseThrow().contains(" app-run demo-job "));
            var daemon = usage.components().stream().filter(value -> value.id().equals("service")).findFirst().orElseThrow();
            assertTrue(daemon.command().orElseThrow().startsWith("sudo /usr/local/lib/windowstolinux/managed-helper lifecycle-deployment demo-service start "));
            assertEquals(List.of("queue"), daemon.workers());
            assertEquals(List.of("http://[2001:db8::1]:8080", "https://demo.debug.gold/app", "tcp://[2001:db8::1]:9000", "udp://[2001:db8::1]:9001"), daemon.endpoints().stream().map(DeploymentApplicationUsage.Endpoint::value).toList());
            assertEquals(2, daemon.endpoints().stream().filter(value -> value.browser().isPresent()).count());
            assertTrue(daemon.endpoints().stream().filter(value -> Set.of("tcp", "udp").contains(value.protocol())).allMatch(value -> value.browser().isEmpty()));
            for (String hidden : List.of(STARTUP, CLIENT, WORKER, "fixture-secret"))
                assertFalse(usage.toString().contains(hidden));
            var selected = await(service.inventory()).value().orElseThrow().getFirst();
            assertEquals(Optional.of(usage), selected.usage());
            assertEquals(usage, await(service.usage(selected.managed().orElseThrow())).value().orElseThrow());
            assertTrue(await(service.usageForCompletedTask(UUID.randomUUID())).value().orElseThrow().isEmpty());
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TaskResult.class, names = {"FAILED", "CANCELLED", "UNKNOWN"})
    void originalNonSuccessExecutionNeverUsesAnotherSuccessfulApplicationsDelivery(TaskResult outcome) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            seedUsage(runtime, usageGraph("demo", false, true), 1);
            var context = context(runtime);
            var intent = DeploymentRefIntent.server(new ServerId("server"), "usage-negative/demo");
            var executionId = new AtomicReference<UUID>();
            var authorFailure = new AtomicReference<Throwable>();
            var recoveryFailure = new AtomicReference<Throwable>();
            var prepared = new CountDownLatch(1);
            var cancellationObserved = new CountDownLatch(1);
            try (var recovery = context.tasks().registerRecovery("applications-test", (previous, task) -> {
                try {
                    var store = new DeploymentExecutionStore(context.db());
                    var original = store.findForTask(task).orElseThrow();
                    assertEquals(previous.id(), task.id());
                    assertEquals(executionId.get(), original.identity().executionId());
                    assertEquals("PREPARED", original.phase());
                    assertFalse(original.businessCommitted());
                    assertEquals(Optional.of(TaskResult.UNKNOWN), original.outcome());
                    assertEquals(Map.of(intent, Optional.empty()), store.references(original));
                    assertTrue(context.tasks().resourcesReleased(task));
                    var now = Instant.now();
                    var problem = new Problem(ProblemCode.INCOMPATIBLE, "CONTROLLED_ORIGINAL_NO_EFFECTS", "Original failed before effects", Map.of(), Optional.empty());
                    store.recordUncommittedOutcome(task, original.identity().executionId(), TaskResult.FAILED, problem, now);
                    store.finishCleanup(task, original.identity().executionId(), now);
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(problem));
                } catch (Exception | AssertionError failed) {
                    recoveryFailure.set(failed);
                    throw failed;
                }
            })) {
                var original = rootTask(runtime, task -> {
                    try {
                        var store = new DeploymentExecutionStore(context.db());
                        var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), task.id(), Optional.empty(), "demo",
                            new ServerId("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64));
                        executionId.set(identity.executionId());
                        var now = Instant.now();
                        store.begin(task, identity, "usage-negative", List.of(intent), Optional.empty(), now);
                        if (outcome == TaskResult.CANCELLED) {
                            prepared.countDown();
                            assertTrue(cancellationObserved.await(15, TimeUnit.SECONDS));
                            assertTrue(task.cancelToken().requested());
                        }
                        var problem = new Problem(outcome == TaskResult.UNKNOWN ? ProblemCode.UNKNOWN : outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.INCOMPATIBLE,
                            "CONTROLLED_NON_SUCCESS_ORIGINAL", "Original non-success execution", Map.of(), Optional.empty());
                        store.recordUncommittedOutcome(task, identity.executionId(), outcome, problem, now);
                        if (outcome != TaskResult.UNKNOWN) store.finishCleanup(task, identity.executionId(), now);
                        return new OperationResult<Void>(outcome, Optional.empty(), Optional.of(problem));
                    } catch (Exception | AssertionError failed) {
                        authorFailure.set(failed);
                        throw failed;
                    } finally {
                        prepared.countDown();
                    }
                });
                if (outcome == TaskResult.CANCELLED) {
                    try {
                        assertTrue(prepared.await(15, TimeUnit.SECONDS));
                        assertNull(authorFailure.get(), () -> String.valueOf(authorFailure.get()));
                        original.requestCancel();
                    } finally {
                        cancellationObserved.countDown();
                    }
                }
                awaitState(original);
                assertNull(authorFailure.get(), () -> String.valueOf(authorFailure.get()));
                assertEquals(outcome == TaskResult.UNKNOWN ? TaskState.UNKNOWN : TaskState.ENDED, original.snapshot().state(), original.snapshot().toString());
                if (outcome != TaskResult.UNKNOWN)
                    assertEquals(outcome, await(original).outcome());
                var service = new DeploymentApplicationsService(context);
                assertTrue(await(service.usageForCompletedTask(original.id())).value().orElseThrow().isEmpty());
                assertEquals(1, await(service.inventory()).value().orElseThrow().size(), "The other successful publication remains separately available");
                if (outcome == TaskResult.UNKNOWN) {
                    var ended = runtime.tasks().reconcile(original.id()).toCompletableFuture().get(15, TimeUnit.SECONDS);
                    assertNull(recoveryFailure.get(), () -> String.valueOf(recoveryFailure.get()));
                    assertEquals(original.id(), ended.id());
                    assertEquals(TaskState.ENDED, ended.state(), ended.toString());
                    assertEquals(Optional.of(TaskResult.FAILED), ended.outcome());
                    assertTrue(await(service.usageForCompletedTask(original.id())).value().orElseThrow().isEmpty());
                }
                assertEquals(0, peer.effects.get());
                assertNoActiveResources(runtime);
            }
        }
    }

    @Test
    void committedButUnfinishedAndChangedFormalReleaseResultsNeverProduceDelivery() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            var unfinished = seedUsage(runtime, usageGraph("unfinished", true, true), 1, false);
            var service = new DeploymentApplicationsService(context(runtime));
            assertTrue(await(service.usageForCompletedTask(unfinished.id())).value().orElseThrow().isEmpty());
            var original = seedUsage(runtime, usageGraph("demo", true, true), 1);
            var originalBytes = new AtomicReference<byte[]>();
            var captured = rootTask(runtime, task -> {
                try (var connection = context(runtime).db().openConnection(task);
                     var query = connection.prepareStatement("SELECT result_payload FROM deployment_execution WHERE platform_task_id=?")) {
                    query.setObject(1, original.id());
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        originalBytes.set(rows.getBytes(1));
                        assertFalse(rows.next());
                    }
                }
                return success();
            });
            assertEquals(TaskResult.SUCCEEDED, await(captured).outcome());
            for (String fault : List.of("release-uuid", "release-digest", "future-format")) {
                var injected = rootTask(runtime, task -> {
                    var json = (ObjectNode) new ObjectMapper().readTree(originalBytes.get());
                    if (fault.equals("future-format")) json.put("formatVersion", 999);
                    else
                        ((ObjectNode) json.path("applicationRelease")).put(fault.equals("release-uuid") ? "id" : "sha256",
                            fault.equals("release-uuid") ? UUID.randomUUID().toString() : "f".repeat(64));
                    try (var connection = context(runtime).db().openConnection(task);
                         var update = connection.prepareStatement("UPDATE deployment_execution SET result_payload=? WHERE platform_task_id=?")) {
                        update.setBytes(1, new ObjectMapper().writeValueAsBytes(json));
                        update.setObject(2, original.id());
                        assertEquals(1, update.executeUpdate());
                    }
                    return success();
                });
                assertEquals(TaskResult.SUCCEEDED, await(injected).outcome());
                var rejected = service.usageForCompletedTask(original.id());
                rejectedRead(runtime, rejected);
                assertTrue(await(rejected).value().isEmpty(), fault);
            }
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void oldUnreviewedAndCorruptPublicationsNeverCreateNewDelivery() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            var service = new DeploymentApplicationsService(context(runtime));
            var old = seedUsage(runtime, usageGraph("demo", false, true), 1);
            var selected = await(service.inventory()).value().orElseThrow().getFirst().managed().orElseThrow();
            var current = seedUsage(runtime, usageGraph("demo", false, true), 2);
            assertTrue(await(service.usageForCompletedTask(old.snapshot().id())).value().orElseThrow().isEmpty());
            var stale = service.usage(selected);
            rejectedRead(runtime, stale);
            var unreviewed = seedUsage(runtime, usageGraph("legacy", true, false), 1);
            assertTrue(await(service.usageForCompletedTask(unreviewed.snapshot().id())).value().orElseThrow().isEmpty());
            var saved = await(service.inventory()).value().orElseThrow().stream().filter(value -> value.key().equals("managed:legacy")).findFirst().orElseThrow();
            assertTrue(saved.usage().orElseThrow().actions().isEmpty());
            assertTrue(saved.usage().orElseThrow().components().stream().allMatch(value -> value.mode() == DeploymentApplicationUsage.Mode.UNVERIFIED && value.command().isEmpty() && value.endpoints().isEmpty()));
            schemaFault(runtime, "UPDATE deployment_unit SET ownership_manifest_sha256='" + "f".repeat(64) + "' WHERE deployment_unit_id='demo-service'");
            var rejected = service.usageForCompletedTask(current.snapshot().id());
            rejectedRead(runtime, rejected);
            var changed = await(rejected);
            assertTrue(changed.value().isEmpty());
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    private void rejectedRead(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime,
                              gold.debug.windowstolinux.appapi.task.run.TaskHandle<?> handle) throws Exception {
        awaitState(handle);
        assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN, handle.snapshot().state());
        assertFalse(handle.completion().toCompletableFuture().isDone(), "Rejected original evidence cannot create a delivery value");
        var reconciled = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED, reconciled.state());
        assertEquals(Optional.of(gold.debug.windowstolinux.appapi.task.result.TaskResult.FAILED), reconciled.outcome());
    }
}
