package gold.debug.windowstolinux.mod.deployment.file.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import gold.debug.windowstolinux.app.db.task.DependencyRecovery;
import gold.debug.windowstolinux.app.db.task.ProcessRecovery;
import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.process.ManagedProcess;
import gold.debug.windowstolinux.appapi.task.process.ProcessMessage;
import gold.debug.windowstolinux.appapi.task.process.ProcessService;
import gold.debug.windowstolinux.appapi.task.process.ProcessSpec;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

class DeploymentGitCancellationTest extends DeploymentRuntimeFixture {
    @Override
    protected boolean gitEnabled() {
        return true;
    }

    private static final Path GIT = gitExecutable();

    private static Path gitExecutable() {
        for (String directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(directory).resolve("git.exe");
            if (Files.isRegularFile(candidate))
                return candidate.toAbsolutePath().normalize();
        }
        throw new AssertionError("A real Git executable must be available on PATH");
    }

    private static ProcessHandle awaitProcess(Predicate<ProcessHandle> matches) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            try (var processes = ProcessHandle.allProcesses()) {
                var found = processes.filter(matches).findFirst();
                if (found.isPresent())
                    return found.orElseThrow();
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Original native process was not observable");
    }

    private static ProcessHandle ownedWorker(PlatformRuntime runtime, UUID task, UUID process) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
             var query = connection.prepareStatement(
                 "SELECT recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind='PROCESS' AND released_at IS NULL")) {
            query.setObject(1, task);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                var checkpoint = ProcessRecovery.decode(ResourceRecovery.decode(rows.getString(1)));
                assertEquals(process, checkpoint.process());
                assertFalse(rows.next());
                var worker = ProcessHandle.of(checkpoint.pid().orElseThrow()).orElseThrow();
                assertEquals(checkpoint.started().orElseThrow(), worker.info().startInstant().orElseThrow());
                return worker;
            }
        }
    }

    @Test
    void productionGitTaskRetainsOriginalJournalUntilNativeCancellationAndConfirmsOriginalResult() throws Exception {
        enable();
        try (var transport = new HeldTlsTransport(); var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var request = new DeploymentGitRequest(
                DeploymentGitRemote.parse("https://127.0.0.1:" + transport.port() + "/source"),
                new DeploymentGitRef.DefaultBranch(), Set.of("127.0.0.1"), 2 * 1024 * 1024, false);
            var handle = GitRuntimeTestBridge.flow(runtime, context).submit(request, GIT, UUID.randomUUID(),
                "Cancelled Git project", Optional.empty(), java.time.Instant.parse("2026-09-28T00:00:00Z"),
                2 * 1024 * 1024, 100);
            try {
                transport.hello().get(30, TimeUnit.SECONDS);
                assertEquals(5, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
                handle.requestCancel();
                long cancellationDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < cancellationDeadline)
                    Thread.sleep(10);
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(),
                    "Original cancellation must be observed before releasing the held transport");
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals(5, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
            } finally {
                transport.release();
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (handle.snapshot().state() != TaskState.UNKNOWN && handle.snapshot().state() != TaskState.ENDED
                && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertTrue(Set.of(TaskState.UNKNOWN, TaskState.ENDED).contains(handle.snapshot().state()));
            while (scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','PROCESS')") != 0
                && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','PROCESS')"));
            var recovered = handle.snapshot();
            if (recovered.state() == TaskState.UNKNOWN) {
                assertFalse(handle.completion().toCompletableFuture().isDone());
                recovered = reconcileReady(runtime, handle);
            }
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(TaskResult.CANCELLED), recovered.outcome());
            assertEquals(TaskResult.CANCELLED,
                handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    @Test
    void cancelledDuringActualLocalGitFetchRetainsResourcesUntilPhysicalExit() throws Exception {
        enable();
        try (var transport = new HeldTlsTransport(); var runtime = open()) {
            start(runtime, true);
            ModContext original = runtime.modContexts().get(OWNER).context();
            var provider = new AtomicReference<ModContext>();
            var request = new DeploymentGitRequest(
                DeploymentGitRemote.parse("https://127.0.0.1:" + transport.port() + "/source"),
                new DeploymentGitRef.DefaultBranch(), Set.of("127.0.0.1"), 2 * 1024 * 1024, false);
            var job = new AtomicReference<UUID>();
            var processId = new AtomicReference<UUID>();
            var taskId = new AtomicReference<UUID>();
            var checkoutPath = new AtomicReference<Path>();
            var controlPath = new AtomicReference<Path>();
            var physicalReceipt = new AtomicReference<JsonNode>();
            var proxyFailure = new AtomicReference<Throwable>();
            var callerObservation = new CountDownLatch(1);
            var requestCount = new AtomicInteger();
            ProcessService observed = new ProcessService() {
                public ManagedProcess start(ProcessSpec spec, TaskContext task) throws ApiException {
                    return started(spec, task, null);
                }

                public ManagedProcess start(ProcessSpec spec, TaskContext task, UUID id) throws ApiException {
                    return started(spec, task, id);
                }

                public void closeTaskResources(TaskContext task) throws ApiException {
                    provider.get().processes().closeTaskResources(task);
                }

                private ManagedProcess started(ProcessSpec spec, TaskContext task, UUID id) throws ApiException {
                    job.set(UUID.fromString(spec.arguments().get(9)));
                    taskId.set(task.id());
                    Path receipt = Path.of(spec.arguments().get(8)).resolve("git-result.json");
                    checkoutPath.set(spec.workingDirectory());
                    controlPath.set(receipt.getParent());
                    ManagedProcess actual = id == null
                        ? provider.get().processes().start(spec, task)
                        : provider.get().processes().start(spec, task, id);
                    processId.set(actual.id());
                    return new ManagedProcess() {
                        public UUID id() {
                            return actual.id();
                        }

                        public CompletionStage<OperationResult<ProcessMessage>> request(ProcessMessage message,
                                                                                        Duration timeout) throws ApiException {
                            requestCount.incrementAndGet();
                            return actual.request(message, timeout).whenComplete((result, failure) -> {
                                if (result != null && result.outcome() == TaskResult.UNKNOWN)
                                    callerObservation.countDown();
                            });
                        }

                        public void requestCancel() throws ApiException {
                            actual.requestCancel();
                        }

                        public CompletionStage<Integer> exitCode() {
                            return actual.exitCode();
                        }

                        public void close() throws ApiException {
                            actual.close();
                            if (physicalReceipt.get() == null && Files.isRegularFile(receipt)) {
                                try {
                                    byte[] bytes = Files.readAllBytes(receipt);
                                    assertTrue(bytes.length <= 1_048_576);
                                    assertEquals('\n', bytes[bytes.length - 1]);
                                    physicalReceipt.set(JsonMapper.builder()
                                        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                        .build().readTree(bytes));
                                } catch (Throwable failure) {
                                    proxyFailure.set(failure);
                                }
                            }
                        }
                    };
                }
            };
            var preparer = GitRuntimeTestBridge.preparation(runtime, original, GIT);
            var authorFailure = new AtomicReference<Throwable>();
            var preparationProblem = new AtomicReference<Problem>();
            var callbackFinished = new CountDownLatch(1);
            var originalObserver = new AtomicReference<DeploymentGitSetup.Observer>();
            var originalCalls = new AtomicReference<Map<DependencyRecovery, List<UUID>>>();
            var registration = original.tasks().registerRecovery("git-native-cancel", (previous, originalTask) -> {
                try {
                    var observer = originalObserver.get();
                    var source = GitRuntimeTestBridge.reconcileOriginal(runtime, original, observer.planned(), originalTask);
                    assertEquals(TaskResult.CANCELLED, source.outcome());
                    var reason = source.problem().orElseThrow();
                    observer.receipt(new DeploymentGitSetup.Receipt(source.outcome(), Optional.empty(),
                        Optional.of(reason.code()), Optional.of(reason.eventCode())));
                    preparer.closeTaskResources(originalTask);
                    GitRuntimeTestBridge.onlyOriginalCallsRemain(runtime, originalTask.id(), originalCalls.get());
                    var receipt = physicalReceipt.get();
                    assertNotNull(receipt);
                    var outcome = TaskResult.valueOf(receipt.path("payload").path("outcome").textValue());
                    assertTrue(outcome == TaskResult.CANCELLED || outcome == TaskResult.FAILED);
                    return new OperationResult<Void>(outcome, Optional.empty(),
                        Optional.of(new Problem(
                            outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
                            "TEST_GIT_RESULT", "Original local Git result was confirmed", Map.of(),
                            Optional.empty())));
                } catch (Throwable problem) {
                    authorFailure.set(problem);
                    throw new AssertionError(problem);
                }
            });
            var handle = original.tasks()
                .submit(new TaskRequest("git-native-cancel", "Native Git cancellation", List.of(), true), task -> {
                    try {
                        GitProviderTestAccess.observeProcesses(runtime, original, task, actual -> {
                            provider.set(actual);
                            return observed;
                        });
                        var observer = gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentGitJournalFixture.begin(original, task, request);
                        originalObserver.set(observer);
                        preparer.prepare(request, 2 * 1024 * 1024, 100, task, observer);
                        throw new AssertionError("The held native Git fetch completed without cancellation");
                    } catch (ApiException failure) {
                        preparationProblem.set(failure.problem());
                        return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                            Optional.of(failure.problem()));
                    } catch (Throwable failure) {
                        authorFailure.set(failure);
                        return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.empty());
                    } finally {
                        callbackFinished.countDown();
                    }
                });
            try {
                transport.hello().get(30, TimeUnit.SECONDS);
                assertNotNull(job.get());
                ProcessHandle worker = ownedWorker(runtime, taskId.get(), processId.get());
                ProcessHandle nativeGit = awaitProcess(
                    p -> p.isAlive() && worker.descendants().anyMatch(child -> child.pid() == p.pid())
                        && p.info().command().map(command -> Path.of(command).getFileName().toString()
                        .toLowerCase(java.util.Locale.ROOT).startsWith("git")).orElse(false));
                assertTrue(nativeGit.isAlive());
                assertTrue(Files.isDirectory(checkoutPath.get()));
                assertTrue(Files.isDirectory(controlPath.get()));
                assertEquals(2, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='FILE'"));
                assertEquals(2, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
                assertEquals(1, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='PROCESS'"));
                handle.requestCancel();
                assertTrue(callerObservation.await(20, TimeUnit.SECONDS));
                assertTrue(nativeGit.isAlive());
                assertTrue(worker.isAlive());
                assertFalse(callbackFinished.await(100, TimeUnit.MILLISECONDS));
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals(5, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
                transport.release();
                assertTrue(callbackFinished.await(30, TimeUnit.SECONDS));
                if (authorFailure.get() != null)
                    throw new AssertionError(authorFailure.get());
                if (proxyFailure.get() != null)
                    throw new AssertionError(proxyFailure.get());
                assertEquals(ProblemCode.UNKNOWN, preparationProblem.get().code());
                var receipt = physicalReceipt.get();
                assertNotNull(receipt);
                assertEquals(1, receipt.path("protocolVersion").intValue());
                assertEquals(processId.get().toString(), receipt.path("processId").textValue());
                assertEquals(taskId.get().toString(), receipt.path("taskId").textValue());
                assertEquals(job.get().toString(), receipt.path("payload").path("value").path("jobId").textValue());
                assertEquals("CANCELLED", receipt.path("payload").path("outcome").textValue());
                assertFalse(nativeGit.isAlive());
                assertFalse(worker.isAlive());
                assertEquals(1, requestCount.get());
                assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','PROCESS')"));
                assertFalse(Files.exists(checkoutPath.get()));
                assertFalse(Files.exists(controlPath.get()));
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                originalCalls.set(GitRuntimeTestBridge.retainedCalls(runtime, handle.snapshot().id()));
                reconcileReady(runtime, handle);
                if (authorFailure.get() != null) throw new AssertionError(authorFailure.get());
                assertEquals(TaskResult.CANCELLED,
                    handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } finally {
                transport.release();
                assertTrue(callbackFinished.await(35, TimeUnit.SECONDS), "Original Git callback did not return");
                if (handle.snapshot().state() == TaskState.UNKNOWN)
                    reconcileReady(runtime, handle);
                registration.close();
            }
        }
    }

    private static final class HeldTlsTransport implements AutoCloseable {
        private final ServerSocket listener;

        private final CompletableFuture<Void> hello = new CompletableFuture<>();

        private final CountDownLatch release = new CountDownLatch(1);

        private final Thread server;

        private HeldTlsTransport() throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server = Thread.ofPlatform().name("git-cancel-loopback-tls").start(() -> {
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(20_000);
                    var input = new DataInputStream(client.getInputStream());
                    if (input.readUnsignedByte() != 22 || input.readUnsignedByte() != 3)
                        throw new AssertionError("Native Git did not send a TLS handshake record");
                    input.readUnsignedByte();
                    int length = input.readUnsignedShort();
                    if (length < 1 || length > 16384 || input.readUnsignedByte() != 1)
                        throw new AssertionError("Native Git did not send a TLS ClientHello");
                    if (input.readNBytes(length - 1).length != length - 1)
                        throw new AssertionError("Native Git TLS ClientHello was incomplete");
                    hello.complete(null);
                    if (!release.await(60, TimeUnit.SECONDS))
                        throw new AssertionError("Held TLS transport was not released");
                } catch (Throwable failure) {
                    hello.completeExceptionally(failure);
                }
            });
        }

        private int port() {
            return listener.getLocalPort();
        }

        private CompletableFuture<Void> hello() {
            return hello;
        }

        private void release() {
            release.countDown();
        }

        @Override
        public void close() throws Exception {
            release();
            listener.close();
            server.join(Duration.ofSeconds(20));
            assertFalse(server.isAlive());
        }
    }
}
