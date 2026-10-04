package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceSetup;
import gold.debug.windowstolinux.mod.deployment.file.git.*;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import org.junit.jupiter.api.Test;

/** Checks only original no-effect preparation evidence and retained uncertainty. / 仅核对原无效果准备依据及保留的不确定性。 */
class DeploymentStaticTaskRecoveryTest extends DeploymentRuntimeFixture {
    private static final Path GIT = java.util.Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
        .map(path -> Path.of(path).resolve("git.exe")).filter(Files::isRegularFile).findFirst().orElseThrow()
        .toAbsolutePath().normalize();

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
                new TaskRequest(DeploymentRefFlow.TASK_TYPE, "Recover original preparation", List.of(), true),
                task -> {
                    calls.incrementAndGet();
                    DeploymentStaticSetupActionLog.begin(context.tasks(), task, new ServerId("server"));
                    new DeploymentSourceSetup(context.file())
                        .prepare(context.file().sources().selectDirectory("Source", task), 65536, 100, task);
                    return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_PREPARATION_INTERRUPTED",
                            "Local preparation interrupted", java.util.Map.of(), Optional.empty())));
                });
            for (int attempt = 0; attempt < 100 && handle.snapshot().state() != TaskState.UNKNOWN; attempt++)
                Thread.sleep(50);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertEquals(1, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
            var recovered = reconcileReady(runtime, handle, 15);
            assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
            assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
            assertEquals(1, calls.get());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
        }
    }

    /** Registered publication recovery settles the real Git worker before releasing its original frozen source. / 已登记发布恢复先核实真实 Git worker，再释放原冻结来源。
     * @throws Exception if native Git, task or resource observation fails / 原生 Git、任务或资源观察失败时 */
    @Test
    void registeredRecoverySettlesOriginalGitWithoutFetchingAgain() throws Exception {
        var repo = Files.createDirectory(root.resolve("repo"));
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "test@example.invalid");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("go.mod"), "module example.test/recover\n\ngo 1.24\n");
        Files.writeString(repo.resolve("main.go"), "package main\nfunc main() {}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "first");
        var request = new DeploymentGitRequest(new DeploymentGitRemote(repo.toUri()),
            new DeploymentGitRef.DefaultBranch(), Set.of(), 65536, true);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var handle = context.tasks().submit(
                new TaskRequest(DeploymentRefFlow.TASK_TYPE, "Recover original pinned Git", List.of(), true),
                task -> {
                    calls.incrementAndGet();
                    DeploymentStaticSetupActionLog.begin(context.tasks(), task, new ServerId("server"));
                    var pinned = new DeploymentGitSourceFlow(context).prepareForPublication(request, GIT,
                        "reviewed-git-project", 65536, 100, task);
                    assertTrue(Files
                        .isRegularFile(pinned.material().source().snapshot().directory().resolve("main.go")));
                    return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_GIT_PREPARATION_INTERRUPTED",
                            "Git prepared before publication", java.util.Map.of(), Optional.empty())));
                });
            for (int attempt = 0; attempt < 100 && handle.snapshot().state() != TaskState.UNKNOWN; attempt++)
                Thread.sleep(50);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            Files.move(repo, root.resolve("remote-unavailable"));
            var recovered = reconcileReady(runtime, handle);
            assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
            assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
            assertEquals(1, calls.get());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    private static String git(Path repo, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(GIT.toString());
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    /** Missing and damaged preparation evidence cannot authorize a known result; a cancelled original marker can close. / 缺失和损坏的准备依据不能授权已知结果；原已取消标记可收尾。
     * @throws Exception if actual CLASS/H2 task recovery fails / 真实 CLASS/H2 任务恢复失败时 */
    @Test
    void originalPreparationMarkerControlsLocalRecovery() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var recovery = new DeploymentStaticTaskRecovery(context, new DeploymentRefFlow(context));
            for (int scenario = 0; scenario < 3; scenario++) {
                final int selected = scenario;
                var callbackFailure = new AtomicReference<Throwable>();
                var handle = context.tasks().submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                    "Recover local preparation", List.of(), true), task -> {
                    try {
                        var previous = new TaskSnapshot(task.id(), OWNER, "1.0.0",
                            DeploymentRefFlow.TASK_TYPE, TaskState.RUNNING, Optional.empty(), "",
                            Instant.now(), Optional.empty(), List.of());
                        if (selected == 0) {
                            assertEquals(TaskResult.UNKNOWN, recovery.reconcile(previous, task).outcome());
                        } else if (selected == 1) {
                            var damaged = context.tasks().beginCheckpoint(task,
                                DeploymentStaticSetupActionLog.KEY, "{}");
                            assertEquals(TaskResult.UNKNOWN, recovery.reconcile(previous, task).outcome());
                            damaged.close();
                        } else {
                            var marker = DeploymentStaticSetupActionLog.begin(context.tasks(), task,
                                new ServerId("server"));
                            marker.cancelled();
                            assertEquals(TaskResult.CANCELLED, recovery.reconcile(previous, task).outcome());
                            assertTrue(context.tasks().resourcesReleased(task));
                        }
                    } catch (Exception | AssertionError failure) {
                        callbackFailure.set(failure);
                    }
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
                assertEquals(TaskResult.SUCCEEDED,
                    handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                if (callbackFailure.get() != null)
                    throw new AssertionError("Original local recovery failed", callbackFailure.get());
            }
        }
    }
}
