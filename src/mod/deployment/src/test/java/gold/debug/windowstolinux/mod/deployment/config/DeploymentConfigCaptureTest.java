package gold.debug.windowstolinux.mod.deployment.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

class DeploymentConfigCaptureTest extends DeploymentRuntimeFixture {
    @Test
    void originalTaskRetainsCapturedTimingAfterReleaseAndRecovery() throws Exception {
        enable();
        var captured = new DeploymentConfig.Environment(333, 19, 60, 2);
        try (var runtime = open()) {
            start(runtime, true);
            var tasks = runtime.modContexts().get(OWNER).context().tasks();
            var handle = tasks.submit(new TaskRequest("fixture.config.capture", "Configuration", List.of(), true), task -> {
                DeploymentConfigCapture.environment(tasks, task, captured);
                assertTrue(tasks.findCheckpoint(task, "deployment.config.environment.v1").orElseThrow().snapshot().released());
                assertEquals(captured, DeploymentConfigCapture.environment(tasks, task));
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.empty());
            });
            try (var registration = tasks.registerRecovery("fixture.config.capture", (previous, task) -> {
                assertEquals(handle.id(), task.id());
                assertEquals(captured, DeploymentConfigCapture.environment(tasks, task));
                assertNotEquals(DeploymentConfigLoader.current().environment(), captured);
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            })) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (handle.snapshot().state() != gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN) {
                    assertTrue(System.nanoTime() < deadline);
                    Thread.sleep(10);
                }
                assertFalse(handle.completion().toCompletableFuture().isDone(), "UNKNOWN is retained until original recovery ends");
                assertEquals(Optional.of(TaskResult.SUCCEEDED),
                    reconcileReady(runtime, handle).outcome());
                assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
            }
        }
    }

    @Test
    void oldTasksUseHistoricalTimingAndMalformedCapturedEvidenceIsRejected() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var tasks = runtime.modContexts().get(OWNER).context().tasks();
            var handle = tasks.submit(new TaskRequest("fixture.config.invalid", "Configuration", List.of(), true), task -> {
                assertEquals(DeploymentConfig.Environment.original(), DeploymentConfigCapture.environment(tasks, task));
                String payload = "{\"formatVersion\":1,\"task\":\"" + UUID.randomUUID()
                    + "\",\"installSeconds\":900,\"checkSeconds\":30,\"rebootSeconds\":900,\"pollSeconds\":5}";
                try (var ignored = tasks.beginCheckpoint(task, "deployment.config.environment.v1", payload)) {
                    assertThrows(IllegalArgumentException.class, () -> DeploymentConfigCapture.environment(tasks, task));
                }
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        }
    }
}
