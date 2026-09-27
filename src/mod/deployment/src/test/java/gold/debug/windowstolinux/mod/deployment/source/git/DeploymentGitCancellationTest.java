package gold.debug.windowstolinux.mod.deployment.source.git;

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

import gold.debug.windowstolinux.app.db.task.ProcessRecovery;
import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import org.junit.jupiter.api.Test;

class DeploymentGitCancellationTest extends DeploymentRuntimeFixture {
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
    void productionGitTaskRetainsOriginalJournalUntilNativeCancellationAndExplicitRecovery() throws Exception {
        enable();
        try (var transport = new HeldTlsTransport(); var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var request = new DeploymentGitRequest(
                    DeploymentGitRemote.parse("https://127.0.0.1:" + transport.port() + "/source"),
                    new DeploymentGitReference.DefaultBranch(), Set.of("127.0.0.1"), 2 * 1024 * 1024, false);
            var handle = new DeploymentGitSourceFlow(context).submit(request, GIT, UUID.randomUUID(),
                    "Cancelled Git project", Optional.empty(), java.time.Instant.parse("2026-09-28T00:00:00Z"),
                    2 * 1024 * 1024, 100);
            try {
                transport.hello().get(30, TimeUnit.SECONDS);
                assertEquals(4, scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
                handle.requestCancel();
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals(4, scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
            } finally {
                transport.release();
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
                Thread.onSpinWait();
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            while (scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')") != 0
                    && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
            var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(Outcome.CANCELLED), recovered.outcome());
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
            var request = new DeploymentGitRequest(
                    DeploymentGitRemote.parse("https://127.0.0.1:" + transport.port() + "/source"),
                    new DeploymentGitReference.DefaultBranch(), Set.of("127.0.0.1"), 2 * 1024 * 1024, false);
            var job = new AtomicReference<UUID>();
            var processId = new AtomicReference<UUID>();
            var taskId = new AtomicReference<UUID>();
            var checkoutPath = new AtomicReference<Path>();
            var controlPath = new AtomicReference<Path>();
            var physicalReceipt = new AtomicReference<DeploymentGitProtocol.Frame>();
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
                    original.processes().closeTaskResources(task);
                }

                private ManagedProcess started(ProcessSpec spec, TaskContext task, UUID id) throws ApiException {
                    job.set(UUID.fromString(spec.arguments().get(9)));
                    taskId.set(task.id());
                    Path receipt = Path.of(spec.arguments().get(8)).resolve("git-result.json");
                    checkoutPath.set(spec.workingDirectory());
                    controlPath.set(receipt.getParent());
                    ManagedProcess actual = id == null
                            ? original.processes().start(spec, task)
                            : original.processes().start(spec, task, id);
                    processId.set(actual.id());
                    return new ManagedProcess() {
                        public UUID id() {
                            return actual.id();
                        }

                        public CompletionStage<OperationResult<ProcessMessage>> request(ProcessMessage message,
                                Duration timeout) throws ApiException {
                            requestCount.incrementAndGet();
                            return actual.request(message, timeout).whenComplete((result, failure) -> {
                                if (result != null && result.outcome() == Outcome.UNKNOWN)
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
                                try (var input = Files.newInputStream(receipt)) {
                                    physicalReceipt.set(DeploymentGitProtocol.read(input).orElseThrow());
                                    if (input.read() != -1)
                                        throw new AssertionError("Original physical receipt has trailing bytes");
                                } catch (Throwable failure) {
                                    proxyFailure.set(failure);
                                }
                            }
                        }
                    };
                }
            };
            ModContext intercepted = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                    new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("processes"))
                            return observed;
                        try {
                            return method.invoke(original, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            var preparer = new DeploymentGitPreparer(intercepted, GIT);
            var authorFailure = new AtomicReference<Throwable>();
            var preparationProblem = new AtomicReference<Problem>();
            var callbackFinished = new CountDownLatch(1);
            var registration = original.tasks().registerRecovery("git-native-cancel", (previous, originalTask) -> {
                assertTrue(original.tasks().resourcesReleased(originalTask));
                var receipt = physicalReceipt.get();
                assertNotNull(receipt);
                var outcome = DeploymentGitProtocol.result(receipt).outcome();
                assertTrue(outcome == Outcome.CANCELLED || outcome == Outcome.FAILED);
                return new OperationResult<Void>(outcome, Optional.empty(),
                        Optional.of(new Problem(
                                outcome == Outcome.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
                                "TEST_GIT_RESULT", "Original local Git result was confirmed", Map.of(),
                                Optional.empty())));
            });
            var handle = original.tasks()
                    .submit(new TaskRequest("git-native-cancel", "Native Git cancellation", List.of(), true), task -> {
                        try {
                            preparer.prepare(request, 2 * 1024 * 1024, 100, task);
                            throw new AssertionError("The held native Git fetch completed without cancellation");
                        } catch (ApiException failure) {
                            preparationProblem.set(failure.problem());
                            return new OperationResult<Void>(Outcome.UNKNOWN, Optional.empty(),
                                    Optional.of(failure.problem()));
                        } catch (Throwable failure) {
                            authorFailure.set(failure);
                            return new OperationResult<Void>(Outcome.FAILED, Optional.empty(), Optional.empty());
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
                assertEquals(1, scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
                assertEquals(1, scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='PROCESS'"));
                handle.requestCancel();
                assertTrue(callerObservation.await(20, TimeUnit.SECONDS));
                assertTrue(nativeGit.isAlive());
                assertTrue(worker.isAlive());
                assertFalse(callbackFinished.await(100, TimeUnit.MILLISECONDS));
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals(4, scalar(runtime,
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
                assertEquals(processId.get(), receipt.process());
                assertEquals(taskId.get(), receipt.task());
                assertEquals(job.get(),
                        DeploymentGitProtocol.uuid(
                                DeploymentGitProtocol.object(
                                        DeploymentGitProtocol.result(receipt).value().orElseThrow().jsonObject()),
                                "jobId"));
                assertEquals(Outcome.CANCELLED, DeploymentGitProtocol.result(receipt).outcome());
                assertFalse(nativeGit.isAlive());
                assertFalse(worker.isAlive());
                assertEquals(1, requestCount.get());
                assertEquals(0, scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
                assertFalse(Files.exists(checkoutPath.get()));
                assertFalse(Files.exists(controlPath.get()));
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(Outcome.CANCELLED,
                        handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            } finally {
                transport.release();
                assertTrue(callbackFinished.await(35, TimeUnit.SECONDS), "Original Git callback did not return");
                if (handle.snapshot().state() == TaskState.UNKNOWN)
                    runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
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
