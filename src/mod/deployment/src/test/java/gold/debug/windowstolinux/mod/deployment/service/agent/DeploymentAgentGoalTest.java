package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Real task callbacks exercise process-local submit/claim windows; business publication is tested by WorkflowTest. */
class DeploymentAgentGoalTest extends DeploymentAgentContinuationTest {
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @SuppressWarnings("unchecked")
    static TaskHandle<DeploymentAgentDeliveryPort.Published> published(TaskHandle<?> handle) {
        return (TaskHandle<DeploymentAgentDeliveryPort.Published>) handle;
    }

    @SuppressWarnings("unchecked")
    static void pending(DeploymentAgentGoal goal, UUID predecessor, DeploymentAgentGoal.Pending value) throws Exception {
        var field = DeploymentAgentGoal.class.getDeclaredField("pending");
        field.setAccessible(true);
        ((Map<UUID, DeploymentAgentGoal.Pending>) field.get(goal)).put(predecessor, value);
    }

    DeploymentAgentContinuation.Intent withSource(DeploymentAgentContinuation.Intent value, DeploymentAgentContinuationSource source,
                                                  DeploymentAgentGoalBudget.Snapshot budget) {
        return new DeploymentAgentContinuation.Intent(value.root(), value.predecessor(), value.ticket(), value.operation(), value.ordinal(),
            value.application(), value.server(), value.target(), value.configuration(), value.targetProfile(), value.physicalTarget(), value.layout(),
            value.layoutSha256(), value.request(), DeploymentAgentContinuation.Source.capture(source), value.resources(), value.acceptedFailure(),
            value.nativeFailureObservation(), budget);
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-submit", "after-child-registration", "capsule-missing", "cancel-before-submit"})
    void uncertainSubmitOrLostCustodyNeverExecutesSourceAndNeverResubmits(String gap) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("original.txt"), "authorized\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var goalRef = new AtomicReference<DeploymentAgentGoal>();
            var submitted = new AtomicInteger();
            var sourceDispatch = new AtomicInteger();
            var child = new AtomicReference<TaskHandle<Void>>();
            var childStopped = new CountDownLatch(1);
            var goal = new DeploymentAgentGoal(context, new ServerId("server"), REQUEST, value -> {
                submitted.incrementAndGet();
                if (gap.equals("after-child-registration")) {
                    child.set(context.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest("deployment-agent", "Unknown submit acknowledgement", List.of("server"), true), task -> {
                        try (var worker = goalRef.get().claimWorker(task, value).open()) {
                            sourceDispatch.incrementAndGet();
                            return success();
                        } catch (Exception unconfirmed) {
                            return failed();
                        } finally {
                            childStopped.countDown();
                        }
                    }));
                }
                throw new java.io.IOException("Controlled submission acknowledgement loss");
            });
            goalRef.set(goal);
            var privatePath = new AtomicReference<Path>();
            var originalId = new AtomicReference<UUID>();
            var error = new AtomicReference<Throwable>();
            var released = new CountDownLatch(1);
            var prepared = new CountDownLatch(1);
            var original = submit(runtime, task -> {
                originalId.set(task.id());
                try (var worker = goal.rootWorker(task).open()) {
                    var log = new DeploymentAgentActionLog(context.db());
                    var value = failure(runtime, task, true);
                    DeploymentAgentContinuationSource capsule;
                    try (var material = new DeploymentSourceSetup(context.file()).prepare(context.file().sources().selectDirectory(selectedSourceDirectory(), task), 1024, 10, task)) {
                        capsule = DeploymentAgentContinuationSource.capture(material, REQUEST, task);
                    }
                    context.file().closeTaskResources(task);
                    var directory = DeploymentAgentContinuationSource.class.getDeclaredField("directory");
                    directory.setAccessible(true);
                    privatePath.set((Path) directory.get(capsule));
                    var next = new DeploymentAgentGoal.Pending(capsule);
                    next.ticket = log.reserveSuccessor(task, withSource(value, capsule, goal.observation().allowance()));
                    next.ticket = log.readySuccessor(task, goal.observation().allowance());
                    pending(goal, task.id(), next);
                    prepared.countDown();
                    assertTrue(released.await(15, TimeUnit.SECONDS));
                    if (gap.equals("capsule-missing")) capsule.close();
                }
                return failed();
            }, error);
            goal.attach(published(original));
            assertTrue(prepared.await(15, TimeUnit.SECONDS));
            if (gap.equals("cancel-before-submit")) goal.cancel();
            released.countDown();
            var originalResult = original.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            if (error.get() != null) throw new AssertionError("Root gap fixture failed", error.get());
            assertTrue(Set.of(TaskResult.FAILED, TaskResult.CANCELLED).contains(originalResult.outcome()));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!goal.observation().state().equals("BLOCKED") && System.nanoTime() < until) Thread.sleep(10);
            assertEquals("BLOCKED", goal.observation().state());
            assertFalse(goal.completion().toCompletableFuture().isDone());
            if (child.get() != null) {
                assertTrue(childStopped.await(15, TimeUnit.SECONDS));
                assertEquals(TaskResult.FAILED, child.get().completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            }
            assertEquals(0, sourceDispatch.get());
            assertEquals(gap.equals("before-submit") || gap.equals("after-child-registration") ? 1 : 0, submitted.get());
            assertFalse(Files.exists(privatePath.get()));
            run(runtime, task -> {
                var saved = new DeploymentAgentActionLog(context.db()).successor(task, originalId.get()).orElseThrow();
                assertTrue(Set.of("READY", "BLOCKED").contains(saved.phase()));
                if (gap.equals("after-child-registration")) {
                    assertEquals("BLOCKED", saved.phase());
                    assertEquals(child.get().id(), saved.child());
                    assertEquals(1, new DeploymentAgentActionLog(context.db()).events(task, originalId.get()).stream()
                        .filter(event -> event.type().equals("CONTINUATION_CLAIMED") && event.detail().equals(child.get().id().toString())).count());
                }
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM managed_application"));
                return success();
            });
            Thread.sleep(30);
            assertEquals(gap.equals("before-submit") || gap.equals("after-child-registration") ? 1 : 0, submitted.get());
        }
    }

    @Test
    void actualCurrentHandleWatchCancellationAndFinalIdentityKeepOriginalFailed() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var goal = new DeploymentAgentGoal(context, new ServerId("server"), REQUEST, ignored -> {
                throw new AssertionError("No successor intent exists");
            });
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var observed = new ArrayList<UUID>();
            var error = new AtomicReference<Throwable>();
            var subscription = goal.watch(handle -> observed.add(handle.id()));
            var original = submit(runtime, task -> {
                entered.countDown();
                assertTrue(release.await(15, TimeUnit.SECONDS));
                return failed();
            }, error);
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            // Observation wiring attaches authentic handles; no fictional business success is produced.
            goal.attach(published(original));
            assertEquals(original.id(), goal.currentHandle().orElseThrow().id());
            subscription.close();
            release.countDown();
            await(original, error, TaskResult.FAILED);
            assertEquals(original.id(), goal.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).finalTask());
            assertEquals(TaskResult.FAILED, original.snapshot().outcome().orElseThrow());
            assertEquals(List.of(original.id()), observed);
            var childEntered = new CountDownLatch(1);
            var childRelease = new CountDownLatch(1);
            var actualCancel = new AtomicBoolean();
            var cancellable = new DeploymentAgentGoal(context, new ServerId("server"), REQUEST, ignored -> {
                throw new AssertionError();
            });
            var current = submit(runtime, task -> {
                childEntered.countDown();
                assertTrue(childRelease.await(15, TimeUnit.SECONDS));
                actualCancel.set(task.cancelToken().requested());
                return failed();
            }, error);
            assertTrue(childEntered.await(15, TimeUnit.SECONDS));
            cancellable.attach(published(current));
            cancellable.cancel();
            childRelease.countDown();
            current.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(actualCancel.get());
            assertEquals(current.id(), cancellable.currentHandle().orElseThrow().id());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "rebind", "unknown-private-identity"})
    void actualWorkflowRejectsBeforeSourceDispatchClosesOnlyItsPrivateCopyAndRetainsUnknownIdentity(String rejection) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("original.txt"), "authorized\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var actual = runtime.modContexts().get(OWNER).context();
            var modelCalls = new AtomicInteger();
            var sourceCalls = new AtomicInteger();
            var ai = java.lang.reflect.Proxy.newProxyInstance(actual.ai().getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class.getMethod("ai").getReturnType()}, (proxy, method, args) -> {
                    if (method.getName().equals("configurationRevision"))
                        return rejection.equals("authorization") ? "0".repeat(64) : "b".repeat(64);
                    modelCalls.incrementAndGet();
                    throw new AssertionError("Rejected child cannot call a model");
                });
            var file = java.lang.reflect.Proxy.newProxyInstance(actual.file().getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class.getMethod("file").getReturnType()}, (proxy, method, args) -> {
                    if (method.getName().equals("workspaces")) sourceCalls.incrementAndGet();
                    try {
                        return method.invoke(actual.file(), args);
                    } catch (java.lang.reflect.InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
            var context = (gold.debug.windowstolinux.appapi.mod.ModContext) java.lang.reflect.Proxy.newProxyInstance(actual.getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("ai")) return ai;
                    if (method.getName().equals("file")) return file;
                    if (method.getName().equals("linux")) throw new AssertionError("Rejected child cannot connect");
                    try {
                        return method.invoke(actual, args);
                    } catch (java.lang.reflect.InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
            var workflow = new DeploymentAgentWorkflow(context);
            var method = DeploymentAgentWorkflow.class.getDeclaredMethod("submitSuccessor", DeploymentAgentGoal.Pending.class);
            method.setAccessible(true);
            var holder = new AtomicReference<DeploymentAgentGoal.Pending>();
            var copy = new AtomicReference<Path>();
            Path backup = root.resolve("retained-own-private-copy");
            var goal = new DeploymentAgentGoal(context, new ServerId("server"), REQUEST, value -> {
                if (rejection.equals("rebind"))
                    Files.writeString(copy.get().resolve("original.txt"), "changed private bytes\n");
                if (rejection.equals("unknown-private-identity")) {
                    Files.move(copy.get(), backup);
                    Files.createDirectory(copy.get());
                    Files.setAttribute(copy.get(), "creationTime", java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));
                    Files.writeString(copy.get().resolve("foreign.txt"), "foreign replacement must remain\n");
                }
                try {
                    return published((TaskHandle<?>) method.invoke(workflow, value));
                } catch (java.lang.reflect.InvocationTargetException failed) {
                    throw (Exception) failed.getCause();
                }
            });
            var error = new AtomicReference<Throwable>();
            var prepared = new CountDownLatch(1);
            var released = new CountDownLatch(1);
            var original = submit(runtime, task -> {
                try (var worker = goal.rootWorker(task).open()) {
                    var log = new DeploymentAgentActionLog(actual.db());
                    var value = failure(runtime, task, true);
                    DeploymentAgentContinuationSource capsule;
                    try (var material = new DeploymentSourceSetup(actual.file()).prepare(actual.file().sources().selectDirectory(selectedSourceDirectory(), task), 1024, 10, task)) {
                        capsule = DeploymentAgentContinuationSource.capture(material, REQUEST, task);
                    }
                    actual.file().closeTaskResources(task);
                    var directory = DeploymentAgentContinuationSource.class.getDeclaredField("directory");
                    directory.setAccessible(true);
                    copy.set((Path) directory.get(capsule));
                    value = withSource(value, capsule, goal.observation().allowance());
                    var server = actual.servers().get(new ServerId("server"), task);
                    value = new DeploymentAgentContinuation.Intent(value.root(), value.predecessor(), value.ticket(), value.operation(), value.ordinal(),
                        value.application(), value.server(), value.target(), value.configuration(),
                        gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.targetProfile(server),
                        gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.physicalTarget(server), value.layout(), value.layoutSha256(),
                        value.request(), value.source(), value.resources(), value.acceptedFailure(), value.nativeFailureObservation(), value.allowance());
                    var next = new DeploymentAgentGoal.Pending(capsule);
                    next.ticket = log.reserveSuccessor(task, value);
                    next.ticket = log.readySuccessor(task, value.allowance());
                    pending(goal, task.id(), next);
                    holder.set(next);
                    var goals = DeploymentAgentWorkflow.class.getDeclaredField("goals");
                    goals.setAccessible(true);
                    @SuppressWarnings("unchecked") var registered = (Map<UUID, DeploymentAgentGoal>) goals.get(workflow);
                    registered.put(task.id(), goal);
                    prepared.countDown();
                    assertTrue(released.await(15, TimeUnit.SECONDS));
                }
                return failed();
            }, error);
            goal.attach(published(original));
            assertTrue(prepared.await(15, TimeUnit.SECONDS));
            released.countDown();
            await(original, error, TaskResult.FAILED);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (goal.currentHandle().orElseThrow().id().equals(original.id()) && System.nanoTime() < until)
                Thread.sleep(10);
            var child = goal.currentHandle().orElseThrow();
            assertNotEquals(original.id(), child.id());
            assertEquals(TaskResult.FAILED, child.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            assertEquals(0, modelCalls.get());
            assertEquals(0, sourceCalls.get());
            assertEquals("authorized\n", Files.readString(selectedSourceDirectory().resolve("original.txt")));
            run(runtime, task -> {
                var ticket = new DeploymentAgentActionLog(actual.db()).successor(task, original.id()).orElseThrow();
                assertEquals("BLOCKED", ticket.phase());
                assertEquals(child.id(), ticket.child());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM managed_application"));
                return success();
            });
            if (rejection.equals("unknown-private-identity")) {
                assertEquals("BLOCKED", goal.observation().state());
                assertFalse(goal.completion().toCompletableFuture().isDone());
                assertEquals("foreign replacement must remain\n", Files.readString(copy.get().resolve("foreign.txt")));
                assertTrue(Files.exists(backup.resolve("original.txt")));
                // Restore only this test-created identity to release its own retained copy; never retry the child.
                Files.delete(copy.get().resolve("foreign.txt"));
                Files.delete(copy.get());
                Files.move(backup, copy.get());
                goal.releasePrivate(holder.get());
            } else
                assertEquals(TaskResult.FAILED, goal.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).result().outcome());
            assertFalse(Files.exists(copy.get()));
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
