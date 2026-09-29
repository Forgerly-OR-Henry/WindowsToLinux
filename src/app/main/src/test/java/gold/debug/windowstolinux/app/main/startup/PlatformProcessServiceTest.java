package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.mod.process.ProcessProtocol.Kind;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.*;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.mod.process.*;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformProcessServiceTest {
    @TempDir
    Path root;

    static final ProcessMessage MESSAGE = new ProcessMessage("fixture.work", "{}");

    ProcessSpec spec(String mode) {
        return new ProcessSpec(Path.of(System.getProperty("java.home"), "bin", "java.exe"), List.of("-cp",
                System.getProperty("java.class.path"), Child.class.getName(), mode, root.toString(), "literal ; & 中文"),
                Map.of("WTL_FIXTURE", "explicit"), root, Duration.ofSeconds(3));
    }

    static void returned(PlatformRuntime runtime, TaskContext task) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            try {
                runtime.tasks().requireCleanup(task, OWNER, VERSION);
                Thread.sleep(5);
            } catch (ApiException ended) {
                return;
            }
        }
        fail("Original callback did not return");
    }

    static OperationResult<Void> reconciledFailure() {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
                "FIXTURE_RECONCILED", "No fixture business effect was performed", Map.of(), Optional.empty())));
    }

    @Test
    void realPublicCapabilityBindsTaskRecordsPidAndClosesBeforeTaskEnd() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var processes = runtime.processes(OWNER, VERSION);
            var foreign = runtime.processes("example.foreign", VERSION);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var handle = binding.submit(request(), task -> {
                assertThrows(ApiException.class, () -> foreign.start(spec("normal"), task));
                try (var child = processes.start(spec("normal"), task)) {
                    var facts = new TaskLedger(runtime.persistence().database()).queries().recoveries(task.id(), "PROCESS",
                            "process-v1:");
                    assertEquals(1, facts.size());
                    var checkpoint = ProcessRecovery.decode(facts.values().iterator().next());
                    assertEquals(child.id(), checkpoint.process());
                    assertEquals(runtime.persistence().database().bootId(), checkpoint.boot());
                    assertTrue(ProcessHandle.of(checkpoint.pid().orElseThrow()).orElseThrow().isAlive());
                    assertEquals(checkpoint.started(),
                            ProcessHandle.of(checkpoint.pid().orElseThrow()).orElseThrow().info().startInstant());
                    var result = child.request(MESSAGE, Duration.ofSeconds(3)).toCompletableFuture().get(4,
                            TimeUnit.SECONDS);
                    assertEquals(Outcome.SUCCEEDED, result.outcome());
                    var output = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(result.value().orElseThrow().jsonObject());
                    assertEquals(checkpoint.pid().getAsLong(), output.path("pid").asLong());
                    assertEquals("literal ; & 中文", output.path("argument").asText());
                    assertEquals("explicit", output.path("environment").asText());
                    assertTrue(output.path("inheritedJavaHome").isNull());
                }
                return success(null);
            });
            assertEquals(Outcome.SUCCEEDED,
                    handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='PROCESS' AND released_at IS NULL"));
            var diagnostics = new gold.debug.windowstolinux.app.db.log.LogRepository(runtime.persistence().database());
            var target = diagnostics.targets().stream().filter(t -> t.owner().equals(OWNER)).findFirst().orElseThrow();
            var records = diagnostics.read(target);
            assertTrue(records.stream().anyMatch(r -> r.eventCode().equals("PROCESS_STDERR")));
            assertTrue(
                    records.stream().filter(r -> r.eventCode().equals("PROCESS_STDERR")).anyMatch(r -> r.truncated()));
            assertTrue(
                    records.stream().noneMatch(r -> Objects.toString(r.details(), "").contains("fixture-secret-raw")));
        }
    }

    @Test
    void returnedCallbackCannotEndWhileActualChildAndClaimRemain() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var processes = runtime.processes(OWNER, VERSION);
            var child = new AtomicReference<ManagedProcess>();
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var handle = binding.submit(request(), task -> {
                child.set(processes.start(spec("normal"), task));
                return success(null);
            });
            state(handle, TaskState.FINISHING);
            assertFalse(handle.completion().toCompletableFuture().isDone());
            assertTrue(handle.snapshot().blockers().stream().anyMatch(s -> s.contains("PROCESS")));
            assertThrows(ApiException.class, () -> child.get().request(MESSAGE, Duration.ofSeconds(1)));
            child.get().close();
            state(handle, TaskState.ENDED);
            assertEquals(Outcome.SUCCEEDED, handle.completion().toCompletableFuture().get().outcome());
        }
    }

    @Test
    void originalTaskCancellationCannotBeOverwrittenByAuthorSuccessOrReplay() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var processes = runtime.processes(OWNER, VERSION);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var requested = new CountDownLatch(1);
            var context = new AtomicReference<TaskContext>();
            var handle = binding.submit(request(), task -> {
                context.set(task);
                try (var child = processes.start(spec("hold"), task)) {
                    var response = child.request(MESSAGE, Duration.ofSeconds(30));
                    requested.countDown();
                    assertEquals(Outcome.UNKNOWN, response.toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
                }
                return success(null);
            });
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            handle.requestCancel();
            state(handle, TaskState.UNKNOWN);
            returned(runtime, context.get());
            try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                processes.closeTaskResources(task);
                return reconciledFailure();
            })) {
                assertEquals(TaskState.ENDED,
                        runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            var events = Files.readAllLines(root.resolve("events"));
            assertEquals(1, events.stream().filter("REQUEST"::equals).count());
            assertEquals(1, events.stream().filter("CANCEL"::equals).count());
        }
    }

    @Test
    void checkpointFailurePreventsHelloAndReleaseFailureKeepsOriginalClaim() throws Exception {
        for (boolean checkpointFailure : List.of(true, false)) {
            Files.deleteIfExists(root.resolve("events"));
            try (var runtime = PlatformRuntime.open(root.resolve("data-" + checkpointFailure),
                    PlatformStartupTest.PROTECTION)) {
                initialize(runtime, false);
                var db = runtime.persistence().database();
                var processes = runtime.processes(OWNER, VERSION);
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var context = new AtomicReference<TaskContext>();
                sql(db, checkpointFailure
                        ? "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_process CHECK(resource_kind<>'PROCESS' OR recovery_json NOT LIKE '%STARTED%')"
                        : "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_process CHECK(resource_kind<>'PROCESS' OR released_at IS NULL)");
                var handle = binding.submit(request(), task -> {
                    context.set(task);
                    try (var child = processes.start(spec("normal"), task)) {
                        child.request(MESSAGE, Duration.ofSeconds(3)).toCompletableFuture().get(4, TimeUnit.SECONDS);
                    }
                    return success(null);
                });
                state(handle, TaskState.UNKNOWN);
                returned(runtime, context.get());
                var events = Files.readAllLines(root.resolve("events"));
                if (checkpointFailure)
                    assertFalse(events.contains("HELLO"));
                else
                    assertEquals(1, count(db,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='PROCESS' AND released_at IS NULL"));
                sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_process");
                try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                    processes.closeTaskResources(task);
                    return reconciledFailure();
                })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture()
                            .get(8, TimeUnit.SECONDS).state());
                }
                assertEquals(events, Files.readAllLines(root.resolve("events")),
                        "Recovery must not restart or replay the child");
            }
        }
    }

    @Test
    void previousBootLiveChildKeepsItsOriginalClaimUntilObservedExit() throws Exception {
        UUID task;
        var data = root.resolve("data");
        var command = new ArrayList<String>();
        var spec = spec("orphan");
        command.add(spec.executable().toString());
        command.addAll(spec.arguments());
        var child = new ProcessBuilder(command).start();
        try {
            try (var db = PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
                var persistence = new PlatformPersistence(db);
                try (var security = new DesktopSecurityService(persistence.security())) {
                    security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
                }
                register(db, null);
                var ledger = new TaskLedger(db);
                task = UUID.randomUUID();
                ledger.enqueue(task, OWNER, VERSION, request(), List.of(new TaskResource("MOD", OWNER, OWNER, false)));
                assertTrue(ledger.tryStart(task));
                var observed = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.of(child.pid()),
                        child.info().startInstant());
                ledger.retain(task, new TaskResource("PROCESS", "process-v1:" + observed.process(), OWNER, false),
                        observed.encode(), false);
            }
            try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                var processes = runtime.processes(OWNER, VERSION);
                var binding = runtime.tasks().bind(OWNER, VERSION, true);
                try (var recovery = binding.registerRecovery("fixture", (previous, context) -> {
                    processes.closeTaskResources(context);
                    return reconciledFailure();
                })) {
                    assertEquals(TaskState.UNKNOWN,
                            runtime.tasks().reconcile(task).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                    assertTrue(child.isAlive());
                    assertEquals(1, count(runtime.persistence().database(),
                            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='PROCESS' AND released_at IS NULL"));
                    Files.writeString(root.resolve("orphan-release"), "exit");
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS));
                    assertEquals(TaskState.ENDED,
                            runtime.tasks().reconcile(task).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                }
            }
        } finally {
            Files.writeString(root.resolve("orphan-release"), "fixture cleanup");
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            child.getInputStream().close();
            child.getOutputStream().close();
            child.getErrorStream().close();
        }
    }

    @Test
    void previousBootPidReuseNeverKillsCurrentProcessAndMissingCheckpointStaysBlocked() throws Exception {
        UUID task, missingTask, missingLease;
        var data = root.resolve("data");
        var current = ProcessHandle.current();
        try (var db = PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
            var persistence = new PlatformPersistence(db);
            try (var security = new DesktopSecurityService(persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            register(db, null);
            var ledger = new TaskLedger(db);
            task = UUID.randomUUID();
            missingTask = UUID.randomUUID();
            for (var id : List.of(task, missingTask)) {
                ledger.enqueue(id, OWNER, VERSION, request(), List.of(new TaskResource("MOD", OWNER, OWNER, false)));
                assertTrue(ledger.tryStart(id));
            }
            var original = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.of(current.pid()),
                    Optional.of(current.info().startInstant().orElseThrow().minusSeconds(1)));
            ledger.retain(task, new TaskResource("PROCESS", "process-v1:" + original.process(), OWNER, false),
                    original.encode(), false);
            var missing = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.empty(), Optional.empty());
            missingLease = ledger.retain(missingTask,
                    new TaskResource("PROCESS", "process-v1:" + missing.process(), OWNER, false), missing.encode(),
                    false);
        }
        try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            var processes = runtime.processes(OWNER, VERSION);
            var binding = runtime.tasks().bind(OWNER, VERSION, true);
            try (var recovery = binding.registerRecovery("fixture", (previous, context) -> {
                processes.closeTaskResources(context);
                return reconciledFailure();
            })) {
                assertEquals(TaskState.ENDED,
                        runtime.tasks().reconcile(task).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                assertTrue(current.isAlive(), "A reused PID must not be signalled or killed");
                assertEquals(TaskState.UNKNOWN,
                        runtime.tasks().reconcile(missingTask).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                // This fixture created no physical process; only test cleanup can establish that fact.
                new TaskLedger(runtime.persistence().database()).release(missingTask, missingLease);
                assertEquals(TaskState.ENDED,
                        runtime.tasks().reconcile(missingTask).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
        }
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    Thread.sleep(15000);
                    System.exit(92);
                } catch (InterruptedException ignored) {
                }
            });
            var mode = args[0];
            var root = Path.of(args[1]);
            if (mode.equals("orphan")) {
                while (!Files.exists(root.resolve("orphan-release")))
                    Thread.sleep(10);
                return;
            }
            while (true) {
                var next = ProcessProtocol.read(System.in);
                if (next.isEmpty())
                    return;
                var frame = next.get();
                Files.writeString(root.resolve("events"), frame.kind() + "\n", StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                if (frame.kind() == Kind.HELLO)
                    send(frame, Kind.READY, "{}");
                else if (frame.kind() == Kind.STOP) {
                    send(frame, Kind.STOPPED, "{}");
                    return;
                } else if (frame.kind() == Kind.REQUEST && !mode.equals("hold")) {
                    System.err.println("password=fixture-secret-raw diagnostic " + "x".repeat(65536));
                    System.err.flush();
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    var payload = json.createObjectNode().put("outcome", "SUCCEEDED").putNull("problem");
                    payload.putObject("value").put("pid", ProcessHandle.current().pid()).put("argument", args[2])
                            .put("environment", System.getenv("WTL_FIXTURE"))
                            .put("inheritedJavaHome", System.getenv("JAVA_HOME"));
                    send(frame, Kind.RESULT, payload.toString());
                }
            }
        }

        static void send(ProcessProtocol.Frame original, Kind kind, String payload) throws Exception {
            System.out.write(ProcessProtocol.encode(new ProcessProtocol.Frame(original.process(), original.task(),
                    original.request(), kind, original.type(), payload)));
            System.out.flush();
        }
    }
}
