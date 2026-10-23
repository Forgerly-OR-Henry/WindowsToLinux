package gold.debug.windowstolinux.mod.deployment.linux.build;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.db.security.SecurityStore;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentToolchainDiagnosticsTaskServiceTest {
    private static final String OWNER = "gold.debug.windowstolinux.mod.deployment";
    private static final String VERSION = "1.0.0";
    private static final String TYPE = "diagnostic-fixture";
    private static final UUID OPERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_OPERATION = UUID.fromString("11111111-1111-1111-1111-111111111112");
    private static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        @Override public byte[] protect(byte[] input) {
            var copy = input.clone();
            for (int index = 0; index < copy.length; index++) copy[index] ^= 0x39;
            return copy;
        }
        @Override public byte[] unprotect(byte[] input) { return protect(input); }
    };

    @TempDir Path root;

    @Test
    void realTaskServiceRetainsExactOriginalRequestAcrossReopenAndRecovery() throws Exception {
        var output = root.resolve("original-process.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-Djdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir"), "-classpath",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            OriginalProcess.class.getName(), root.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Original process did not retain its checkpoint in time");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(8, TimeUnit.SECONDS), "Original process did not exit");
            }
        }
        var original = DeploymentToolchainDiagnostics.decode(Files.readAllBytes(root.resolve("original-request.json")));
        var taskId = UUID.fromString(original.taskId());
        var failure = new AtomicReference<Throwable>();
        try (var database = PlatformDatabase.open(root.resolve("database"), PROTECTION);
             var security = security(database);
             var runtime = runtime(database, security)) {
            assertEquals(TaskState.UNKNOWN, runtime.snapshots().stream().filter(task -> task.id().equals(taskId))
                .findFirst().orElseThrow().state());
            var recovery = runtime.bind(OWNER, VERSION, true);
            var recovered = new AtomicInteger();
            try (var registration = recovery.registerRecovery(TYPE, (previous, task) -> {
                try {
                    recovered.incrementAndGet();
                    assertEquals(taskId, previous.id());
                    assertEquals(taskId, task.id());
                    assertRetained(recovery, task, original);
                    assertTrue(recovery.findCheckpoint(task, DeploymentToolchainDiagnostics.checkpointKey(OPERATION))
                        .orElseThrow().snapshot().released());
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), interrupted().problem());
                } catch (Exception | AssertionError unexpected) {
                    failure.set(unexpected);
                    throw unexpected;
                }
            })) {
                var result = runtime.reconcile(taskId).toCompletableFuture().get(8, TimeUnit.SECONDS);
                assertNoFailure(failure);
                assertEquals(taskId, result.id());
                assertEquals(TaskState.ENDED, result.state(), result.toString());
                assertEquals(Optional.of(TaskResult.FAILED), result.outcome());
                assertTrue(result.blockers().isEmpty(), result.toString());
            }
            assertEquals(1, recovered.get());
        }
        assertEquals("1", Files.readString(root.resolve("original-callback-count.txt")),
            "Recovery reads retained bytes without replaying the original preparation");
    }

    /** Exits after retaining UNKNOWN, simulating restart without weakening cooperative shutdown. */
    public static final class OriginalProcess {
        public static void main(String[] arguments) {
            try {
                runOriginal(Path.of(arguments[0]));
                System.exit(0);
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
        }
    }

    private static void runOriginal(Path root) throws Exception {
        var calls = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var original = new AtomicReference<DeploymentToolchainDiagnostics.Request>();
        var database = PlatformDatabase.open(root.resolve("database"), PROTECTION);
        var security = security(database);
        var runtime = runtime(database, security);
        var tasks = runtime.bind(OWNER, VERSION, false);
        var handle = tasks.submit(new TaskRequest(TYPE, "Retain original diagnostic", List.of(), true), task -> {
            try {
                calls.incrementAndGet();
                var request = request(task.id());
                original.set(request);
                String oldKey = "deployment-toolchain-diagnostic-" + request.operationId();
                assertEquals(68, oldKey.length());
                assertEquals("TASK_CHECKPOINT_INPUT", assertThrows(ApiException.class,
                    () -> tasks.beginCheckpoint(task, oldKey, new String(request.bytes(), StandardCharsets.UTF_8)))
                    .problem().eventCode());
                assertEquals(64, DeploymentToolchainDiagnostics.checkpointKey(request.operationId()).length());
                var scope = new DeploymentAssistedScope(context(tasks), task, "b".repeat(64), "MANUAL_REVIEW");
                scope.preparationObserver().diagnosticRequested(request, task);
                assertRetained(tasks, task, request);
                assertTrue(tasks.findCheckpoint(task, DeploymentToolchainDiagnostics.checkpointKey(OPERATION))
                    .orElseThrow().snapshot().released());
                return interrupted();
            } catch (Exception | AssertionError unexpected) {
                failure.set(unexpected);
                throw unexpected;
            }
        });
        awaitUnknown(handle);
        assertNoFailure(failure);
        var taskId = handle.id();
        assertEquals(taskId.toString(), original.get().taskId());

        var foreign = tasks.submit(new TaskRequest(TYPE, "Different original task", List.of(), true), task -> {
            try {
                assertNotEquals(taskId, task.id());
                assertTrue(DeploymentToolchainDiagnostics.retained(tasks, task, OPERATION).isEmpty());
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            } catch (Exception | AssertionError unexpected) {
                failure.set(unexpected);
                throw unexpected;
            }
        });
        assertEquals(TaskResult.SUCCEEDED, foreign.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
        assertNoFailure(failure);
        assertEquals(1, calls.get());
        Files.write(root.resolve("original-request.json"), original.get().bytes());
        Files.writeString(root.resolve("original-callback-count.txt"), Integer.toString(calls.get()));
    }

    private static void assertRetained(TaskService tasks, TaskContext task,
                                       DeploymentToolchainDiagnostics.Request expected) throws Exception {
        var retained = DeploymentToolchainDiagnostics.retained(tasks, task, expected.operationId()).orElseThrow();
        assertEquals(expected.operationId(), retained.operationId());
        assertEquals(task.id().toString(), retained.taskId());
        assertArrayEquals(expected.bytes(), retained.bytes());
        assertNotEquals(DeploymentToolchainDiagnostics.checkpointKey(OPERATION),
            DeploymentToolchainDiagnostics.checkpointKey(OTHER_OPERATION));
        assertTrue(DeploymentToolchainDiagnostics.retained(tasks, task, OTHER_OPERATION).isEmpty());
    }

    private static DeploymentToolchainDiagnostics.Request request(UUID task) {
        return new DeploymentToolchainDiagnostics.Request(OPERATION, task.toString(), "fixture-server", "a".repeat(64),
            "b".repeat(64), DeploymentRemoteLayout.original(), List.of("prepare", "PHP", "8.3", "-", "120", "-"));
    }

    private static ModContext context(TaskService tasks) {
        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("configurationRevision")) return "b".repeat(64);
                throw new AssertionError(method.getName());
            });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "tasks" -> tasks;
                case "ai" -> ai;
                default -> throw new AssertionError(method.getName());
            });
    }

    private static PlatformTaskRuntime runtime(PlatformDatabase database, DesktopSecurityService security) throws Exception {
        return new PlatformTaskRuntime(database, (connection, owner, version, request) ->
            List.of(new TaskResource("MOD", owner, owner, false)), security);
    }

    private static DesktopSecurityService security(PlatformDatabase database) throws Exception {
        var security = new DesktopSecurityService(new SecurityStore(new StoreConnections(database::openConnection)));
        if (security.securityState().configured()) security.unlock("fixture-password".toCharArray());
        else security.setupAndUnlock("fixture-password".toCharArray(), "fixture-password".toCharArray());
        return security;
    }

    private static OperationResult<Void> interrupted() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            "TEST_DIAGNOSTIC_INTERRUPTED", "Original diagnostic retained before simulated interruption", Map.of(), Optional.empty())));
    }

    private static void awaitUnknown(TaskHandle<?> handle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), handle.snapshot().toString());
    }

    private static void assertNoFailure(AtomicReference<Throwable> failure) {
        if (failure.get() != null) throw new AssertionError("Original diagnostic checkpoint contract failed", failure.get());
    }
}
