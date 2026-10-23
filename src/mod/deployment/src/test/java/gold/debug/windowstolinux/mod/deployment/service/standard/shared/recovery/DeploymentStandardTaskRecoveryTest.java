package gold.debug.windowstolinux.mod.deployment.service.standard.shared.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceSetup;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRef;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRemote;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStandardAssembly;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSetupActionLog;
import org.junit.jupiter.api.Test;

/** Checks only original no-effect preparation evidence and retained uncertainty. / 仅核对原无效果准备依据及保留的不确定性。 */
class DeploymentStandardTaskRecoveryTest extends DeploymentRuntimeFixture {
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
                    DeploymentStandardSetupActionLog.begin(context.tasks(), task, new ServerId("server"));
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
                    DeploymentStandardSetupActionLog.begin(context.tasks(), task, new ServerId("server"));
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
            var actual = runtime.modContexts().get(OWNER).context();
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("tasks")) return tasks;
                    try {
                        return method.invoke(actual, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var recovery = new DeploymentStandardTaskRecovery(context, new DeploymentRefFlow(context), new DeploymentStandardAssembly());
            try (var registration = tasks.registerRecovery(DeploymentRefFlow.TASK_TYPE, recovery)) {
                for (int scenario = 0; scenario < 3; scenario++) {
                    var callbackFailure = new AtomicReference<Throwable>();
                    var handle = context.tasks().submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                        "Recover local preparation", List.of(), true), task -> {
                        try {
                            var marker = DeploymentStandardSetupActionLog.begin(context.tasks(), task,
                                new ServerId("server"));
                            marker.cancelled();
                        } catch (Exception | AssertionError failure) {
                            callbackFailure.set(failure);
                        }
                        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(
                            ProblemCode.UNKNOWN, "TEST_ORIGINAL_MARKER_INTERRUPTED", "Original cancelled preparation awaits recovery",
                            java.util.Map.of(), Optional.empty())));
                    });
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < until)
                        Thread.sleep(20);
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                    if (callbackFailure.get() != null)
                        throw new AssertionError("Original local recovery failed", callbackFailure.get());
                    String key = OWNER + "/checkpoint/" + DeploymentStandardSetupActionLog.KEY;
                    UUID lease;
                    String payload;
                    try (var connection = runtime.persistence().database().openConnection();
                         var query = connection.prepareStatement("SELECT lease_id,recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind='CUSTOM' AND resource_id=? AND released_at IS NULL")) {
                        query.setObject(1, handle.id());
                        query.setString(2, key);
                        try (var rows = query.executeQuery()) {
                            assertTrue(rows.next());
                            lease = rows.getObject(1, UUID.class);
                            payload = rows.getString(2);
                            assertFalse(rows.next());
                        }
                    }
                    if (scenario < 2) {
                        String hiddenKey = scenario == 0 ? key + ".fixture-hidden" : key;
                        String damagedPayload = payload;
                        if (scenario == 1) {
                            var node = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
                            ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("fields")).put("payload", "{}");
                            damagedPayload = node.toString();
                        }
                        alterOriginalMarker(runtime, handle.id(), lease, key, payload, hiddenKey, damagedPayload);
                        try {
                            var unknown = reconcileReady(runtime, handle);
                            assertEquals(handle.id(), unknown.id());
                            assertEquals(TaskState.UNKNOWN, unknown.state(), unknown.toString());
                            assertTrue(unknown.outcome().isEmpty());
                            assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE lease_id='" + lease + "' AND released_at IS NULL"));
                        } finally {
                            alterOriginalMarker(runtime, handle.id(), lease, hiddenKey, damagedPayload, key, payload);
                        }
                    }
                    var cancelled = reconcileReady(runtime, handle);
                    assertEquals(handle.id(), cancelled.id());
                    assertEquals(TaskState.ENDED, cancelled.state(), cancelled.toString());
                    assertEquals(Optional.of(TaskResult.CANCELLED), cancelled.outcome());
                    assertEquals(TaskResult.CANCELLED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                    assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id() + "' AND released_at IS NULL"));
                }
            }
        }
    }

    private static void alterOriginalMarker(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime,
                                            UUID task, UUID lease, String oldKey, String oldPayload,
                                            String key, String payload) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
             var update = connection.prepareStatement("UPDATE platform_task_resource SET resource_id=?,recovery_json=? WHERE lease_id=? AND task_id=? AND resource_id=? AND recovery_json=? AND released_at IS NULL")) {
            update.setString(1, key);
            update.setString(2, payload);
            update.setObject(3, lease);
            update.setObject(4, task);
            update.setString(5, oldKey);
            update.setString(6, oldPayload);
            assertEquals(1, update.executeUpdate(), "Only the previously captured original checkpoint may be altered or restored");
        }
    }
}
