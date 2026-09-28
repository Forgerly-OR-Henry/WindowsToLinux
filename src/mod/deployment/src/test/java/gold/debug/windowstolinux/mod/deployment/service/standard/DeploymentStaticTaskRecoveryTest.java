package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.source.DeploymentSourcePreparer;
import org.junit.jupiter.api.Test;

/** Checks only original no-effect preparation evidence and retained uncertainty. / 仅核对原无效果准备依据及保留的不确定性。 */
class DeploymentStaticTaskRecoveryTest extends DeploymentRuntimeFixture {
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    /** Registered recovery releases the original frozen FILE and ends the retained task without rerunning source selection. / 已登记恢复释放原冻结 FILE 并结束保留任务，不重做来源选择。
     * @throws Exception if actual task or source cleanup fails / 真实任务或来源清理失败时 */
    @Test
    void registeredRecoveryClosesOriginalPreparationAndFrozenSource() throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("main.go"), "package main\nfunc main() {}\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var handle = context.tasks().submit(
                    new TaskRequest(DeploymentReferenceFlow.TASK_TYPE, "Recover original preparation", List.of(), true),
                    task -> {
                        calls.incrementAndGet();
                        DeploymentStaticPreparationJournal.begin(context.tasks(), task, new ServerRef("server"));
                        new DeploymentSourcePreparer(context.source())
                                .prepare(context.source().selectDirectory("Source", task), 65536, 100, task);
                        return new OperationResult<Void>(Outcome.UNKNOWN, Optional.empty(),
                                Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_PREPARATION_INTERRUPTED",
                                        "Local preparation interrupted", java.util.Map.of(), Optional.empty())));
                    });
            for (int attempt = 0; attempt < 100 && handle.snapshot().state() != TaskState.UNKNOWN; attempt++)
                Thread.sleep(50);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertEquals(1, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
            var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
            assertEquals(Optional.of(Outcome.FAILED), recovered.outcome());
            assertEquals(1, calls.get());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
        }
    }

    /** Missing and damaged preparation evidence cannot authorize a known result; a cancelled original marker can close. / 缺失和损坏的准备依据不能授权已知结果；原已取消标记可收尾。
     * @throws Exception if actual CLASS/H2 task recovery fails / 真实 CLASS/H2 任务恢复失败时 */
    @Test
    void originalPreparationMarkerControlsLocalRecovery() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var recovery = new DeploymentStaticTaskRecovery(context, new DeploymentReferenceFlow(context));
            for (int scenario = 0; scenario < 3; scenario++) {
                final int selected = scenario;
                var callbackFailure = new AtomicReference<Throwable>();
                var handle = context.tasks().submit(new TaskRequest(DeploymentReferenceFlow.TASK_TYPE,
                        "Recover local preparation", List.of(), true), task -> {
                            try {
                                var previous = new TaskSnapshot(task.id(), OWNER, "1.0.0",
                                        DeploymentReferenceFlow.TASK_TYPE, TaskState.RUNNING, Optional.empty(), "",
                                        Instant.now(), Optional.empty(), List.of());
                                if (selected == 0) {
                                    assertEquals(Outcome.UNKNOWN, recovery.reconcile(previous, task).outcome());
                                } else if (selected == 1) {
                                    var damaged = context.tasks().beginCheckpoint(task,
                                            DeploymentStaticPreparationJournal.KEY, "{}");
                                    assertEquals(Outcome.UNKNOWN, recovery.reconcile(previous, task).outcome());
                                    damaged.close();
                                } else {
                                    var marker = DeploymentStaticPreparationJournal.begin(context.tasks(), task,
                                            new ServerRef("server"));
                                    marker.cancelled();
                                    assertEquals(Outcome.CANCELLED, recovery.reconcile(previous, task).outcome());
                                    assertTrue(context.tasks().resourcesReleased(task));
                                }
                            } catch (Exception | AssertionError failure) {
                                callbackFailure.set(failure);
                            }
                            return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                        });
                assertEquals(Outcome.SUCCEEDED,
                        handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                if (callbackFailure.get() != null)
                    throw new AssertionError("Original local recovery failed", callbackFailure.get());
            }
        }
    }
}
