package gold.debug.windowstolinux.mod.deployment.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.*;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSecretRepository;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;

class DeploymentSecretProvisionFlowTest extends DeploymentRuntimeFixture {
    private static final ServerRef SERVER = new ServerRef("server");

    private static final String PURPOSE = "deployment-password";

    private static final Instant CREATED = Instant.parse("2026-09-28T00:00:00Z");

    private static final DeploymentSecretReference REVISION = new DeploymentSecretReference("database-password", 1);

    private static TaskSnapshot original(TaskContext task) {
        return new TaskSnapshot(task.id(), OWNER, VERSION, DeploymentSecretProvisionFlow.TASK_TYPE, TaskState.RUNNING,
                Optional.empty(), "Secret revision recovery", Instant.now(), Optional.empty(), List.of());
    }

    private static OperationResult<Void> fixtureFailure() {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.IO_FAILURE, "TEST_INTERRUPTED_SECRET",
                        "Test released only its own constructed facts", Map.of(), Optional.empty())));
    }

    private void server(PlatformRuntime runtime) throws Exception {
        runtime.servers().saveServerProfile(
                ServerProfile.fromStored(
                        new StoredServerProfile(SERVER.id(), "127.0.0.1", 22, "tester", "fixture-password")),
                "fixture-secret".toCharArray());
    }

    private static ModContext withSecrets(ModContext original, SecretService secrets) {
        return (ModContext) Proxy.newProxyInstance(DeploymentSecretProvisionFlowTest.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("secrets"))
                        return secrets;
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static ModContext withTasks(ModContext original, TaskService tasks) {
        return (ModContext) Proxy.newProxyInstance(DeploymentSecretProvisionFlowTest.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("tasks"))
                        return tasks;
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static SecretService intercept(MethodCall handler) {
        return (SecretService) Proxy.newProxyInstance(DeploymentSecretProvisionFlowTest.class.getClassLoader(),
                new Class<?>[]{SecretService.class}, (proxy, method, args) -> handler.invoke(method, args));
    }

    private interface MethodCall {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    @Test
    void realClassTaskCreatesOneCredentialAndExactRevisionThenRejectsExistingRevisionBeforeInput() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var copied = new AtomicReference<byte[]>();
            var creates = new AtomicInteger();
            var secrets = intercept((method, args) -> {
                if (method.getName().equals("create")) {
                    creates.incrementAndGet();
                    copied.set((byte[]) args[1]);
                }
                try {
                    return method.invoke(context.secrets(), args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            var flow = new DeploymentSecretProvisionFlow(withSecrets(context, secrets));
            byte[] callerOwned = "private-fixture".getBytes(StandardCharsets.UTF_8);
            var first = flow.submit(SERVER, REVISION, PURPOSE, CREATED, task -> callerOwned);
            var result = first.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            var row = result.value().orElseThrow();
            assertEquals(REVISION, row.reference());
            assertEquals(CREATED, row.createdAt());
            assertEquals(Optional.of(PURPOSE), row.purpose());
            assertArrayEquals("private-fixture".getBytes(StandardCharsets.UTF_8), callerOwned);
            assertNotSame(callerOwned, copied.get());
            assertTrue(Arrays.equals(new byte[copied.get().length], copied.get()));
            assertEquals(1, creates.get());
            var read = context.tasks().submit(
                    new TaskRequest("secret-provision-read", "Read original revision", List.of(SERVER.id()), false),
                    task -> {
                        var stored = new DeploymentSecretRepository(context.db()).findRevision(task, REVISION);
                        var secret = new SecretRef(row.credentialId(), PURPOSE);
                        var intent = gold.debug.windowstolinux.mod.deployment.db.DeploymentReferenceKey
                                .secretRevision(SERVER, secret, REVISION);
                        var reference = context.secrets().findReference(secret, intent.businessKey(), task);
                        boolean matched = context.secrets().use(secret, PURPOSE, task,
                                bytes -> Arrays.equals(callerOwned, bytes));
                        assertEquals(Optional.of(row), stored);
                        assertEquals(ReferenceState.ACTIVE, reference.orElseThrow().state());
                        assertTrue(matched);
                        return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                    });
            assertEquals(Outcome.SUCCEEDED,
                    read.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            var calls = new AtomicInteger();
            var duplicate = flow.submitOwned(SERVER, REVISION, PURPOSE, CREATED.plusSeconds(1), task -> {
                calls.incrementAndGet();
                return callerOwned;
            });
            assertEquals(Outcome.FAILED,
                    duplicate.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(0, calls.get());
            assertEquals(1, creates.get());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
            Arrays.fill(callerOwned, (byte) 0);
        }
    }

    @Test
    void missingCreatedIdAndAbsentWriteAcknowledgementRemainUnknownWithoutReplay() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var calls = new AtomicInteger();
            var first = context.tasks().submit(new TaskRequest(DeploymentSecretProvisionFlow.TASK_TYPE,
                    "Lost created ID", List.of(SERVER.id()), true), task -> {
                        var journal = DeploymentSecretProvisionJournal.begin(context.tasks(), task, SERVER, REVISION,
                                PURPOSE, CREATED);
                        journal.createStarted();
                        var created = context.secrets().create(PURPOSE, new byte[]{1, 2, 3}, task);
                        calls.incrementAndGet();
                        assertNotNull(created.id());
                        assertEquals(Outcome.UNKNOWN,
                                new DeploymentSecretProvisionFlow(context).reconcile(original(task), task).outcome());
                        assertEquals(DeploymentSecretProvisionJournal.PhaseType.CREATE_STARTED,
                                journal.refresh().phase());
                        assertTrue(journal.facts().credentialId().isEmpty());
                        journal.close();
                        return fixtureFailure();
                    });
            assertEquals(Outcome.FAILED, first.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, calls.get());
            var second = context.tasks().submit(new TaskRequest(DeploymentSecretProvisionFlow.TASK_TYPE,
                    "Unknown business commit", List.of(SERVER.id()), true), task -> {
                        var journal = DeploymentSecretProvisionJournal.begin(context.tasks(), task, SERVER,
                                new DeploymentSecretReference("unwritten-password", 1), PURPOSE, CREATED);
                        journal.createStarted();
                        var secret = context.secrets().create(PURPOSE, new byte[]{4, 5, 6}, task);
                        journal.created(secret);
                        journal.reserveStarted();
                        var intent = journal.intent();
                        var reference = context.secrets().reserve(secret, intent.businessKey(), task);
                        journal.reserved(reference.id());
                        journal.writeStarted();
                        var flow = new DeploymentSecretProvisionFlow(context);
                        assertEquals(Outcome.UNKNOWN, flow.reconcile(original(task), task).outcome());
                        assertEquals(DeploymentSecretProvisionJournal.PhaseType.WRITE_STARTED,
                                journal.refresh().phase());
                        journal.saved();
                        assertEquals(Outcome.UNKNOWN, flow.reconcile(original(task), task).outcome());
                        assertEquals(DeploymentSecretProvisionJournal.PhaseType.SAVED, journal.refresh().phase());
                        context.secrets().resolve(reference.id(), ReferenceResolution.NOT_REFERENCED, task);
                        journal.close();
                        return fixtureFailure();
                    });
            assertEquals(Outcome.FAILED, second.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
        }
    }

    @Test
    void lostResolutionAcknowledgementReconcilesOriginalIdWithoutCreatingAgain() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var creates = new AtomicInteger();
            var inputs = new AtomicInteger();
            var resolutions = new AtomicInteger();
            var secrets = intercept((method, args) -> {
                try {
                    if (method.getName().equals("create"))
                        creates.incrementAndGet();
                    Object answer = method.invoke(context.secrets(), args);
                    if (method.getName().equals("resolve") && resolutions.incrementAndGet() >= 1)
                        throw new ApiException(new Problem(ProblemCode.UNKNOWN, "TEST_SECRET_RESOLUTION_ACK",
                                "Original resolution acknowledgement was lost", Map.of(), Optional.empty()));
                    return answer;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            var flow = new DeploymentSecretProvisionFlow(withSecrets(context, secrets));
            var handle = flow.submit(SERVER, REVISION, PURPOSE, CREATED, task -> {
                inputs.incrementAndGet();
                return new byte[]{9, 8, 7};
            });
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
                Thread.onSpinWait();
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertEquals(1, creates.get());
            assertEquals(1, inputs.get());
            assertTrue(resolutions.get() >= 1);
            assertEquals(1, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
            var reconciled = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, reconciled.state(), reconciled.toString());
            assertEquals(Optional.of(Outcome.SUCCEEDED), reconciled.outcome());
            assertEquals(1, creates.get());
            assertEquals(1, inputs.get());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
        }
    }

    @Test
    void lostCreateReturnKeepsOriginalUnknownWithoutCreatingAgain() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var creates = new AtomicInteger();
            var inputs = new AtomicInteger();
            var owned = new AtomicReference<byte[]>();
            var observed = new AtomicReference<OperationResult<DeploymentSecretRevision>>();
            var secrets = intercept((method, args) -> {
                try {
                    Object answer = method.invoke(context.secrets(), args);
                    if (method.getName().equals("create")) {
                        creates.incrementAndGet();
                        assertNotNull(((SecretRef) answer).id());
                        throw new ApiException(new Problem(ProblemCode.UNKNOWN, "TEST_SECRET_CREATE_ACK",
                                "Created ID response was lost", Map.of(), Optional.empty()));
                    }
                    return answer;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            var tasks = (TaskService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TaskService.class}, (proxy, method, args) -> {
                        try {
                            if (method.getName().equals("submit")) {
                                @SuppressWarnings("unchecked")
                                TaskWork<DeploymentSecretRevision> work = (TaskWork<DeploymentSecretRevision>) args[1];
                                Object[] submitted = args.clone();
                                submitted[1] = (TaskWork<DeploymentSecretRevision>) task -> {
                                    var actual = work.run(task);
                                    observed.set(actual);
                                    assertEquals(Outcome.UNKNOWN, actual.outcome());
                                    var checkpoint = context.tasks()
                                            .findCheckpoint(task, DeploymentSecretProvisionJournal.KEY).orElseThrow();
                                    assertTrue(
                                            checkpoint.snapshot().payload().contains("\"phase\":\"CREATE_STARTED\""));
                                    assertTrue(checkpoint.snapshot().payload().contains("\"credentialId\":null"));
                                    assertEquals(Outcome.UNKNOWN, new DeploymentSecretProvisionFlow(context)
                                            .reconcile(original(task), task).outcome());
                                    assertEquals(1, creates.get());
                                    checkpoint.close(); // Test-only release after checking the durable UNKNOWN.
                                    return new OperationResult<DeploymentSecretRevision>(Outcome.FAILED,
                                            Optional.empty(), fixtureFailure().problem());
                                };
                                return method.invoke(context.tasks(), submitted);
                            }
                            return method.invoke(context.tasks(), args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            var flow = new DeploymentSecretProvisionFlow(withTasks(withSecrets(context, secrets), tasks));
            var handle = flow.submitOwned(SERVER, REVISION, PURPOSE, CREATED, task -> {
                inputs.incrementAndGet();
                byte[] value = new byte[]{11, 12, 13};
                owned.set(value);
                return value;
            });
            assertEquals(Outcome.FAILED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(Outcome.UNKNOWN, observed.get().outcome());
            assertEquals(1, creates.get());
            assertEquals(1, inputs.get());
            assertArrayEquals(new byte[3], owned.get(), "UNKNOWN must not retain page-owned plaintext");
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
        }
    }

    @Test
    void plannedOriginalTaskReopensThroughProductionModRecoveryWithoutCreatingSecret() throws Exception {
        enable();
        var graph = new AtomicReference<TaskRecoveryGraph>();
        var payload = new AtomicReference<String>();
        UUID prior;
        UUID interrupted = UUID.randomUUID();
        UUID boot;
        gold.debug.windowstolinux.appapi.db.SchemaIdentity schema;
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            boot = runtime.persistence().database().bootId();
            var context = runtime.modContexts().get(OWNER).context();
            schema = context.db().identity();
            var probe = context.tasks().submit(new TaskRequest(DeploymentSecretProvisionFlow.TASK_TYPE,
                    "Capture original secret task graph", List.of(SERVER.id()), true), task -> {
                        graph.set(new TaskLedger(runtime.persistence().database()).queries().recoveryGraph(task.id())
                                .orElseThrow());
                        var journal = DeploymentSecretProvisionJournal.begin(context.tasks(), task, SERVER, REVISION,
                                PURPOSE, CREATED);
                        payload.set(context.tasks().findCheckpoint(task, DeploymentSecretProvisionJournal.KEY)
                                .orElseThrow().snapshot().payload());
                        journal.close();
                        return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                    });
            prior = probe.id();
            assertEquals(Outcome.SUCCEEDED,
                    probe.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
        }
        try (var db = PlatformDatabase.open(root.resolve("data"), PROTECTION)) {
            var ledger = new TaskLedger(db);
            ledger.enqueue(
                    interrupted, OWNER, VERSION, new TaskRequest(DeploymentSecretProvisionFlow.TASK_TYPE,
                            "Interrupted original secret task", List.of(SERVER.id()), true),
                    new TaskLedger.InitialClaims() {
                        public List<TaskResource> resolve(java.sql.Connection connection) {
                            return List.of(new TaskResource("MOD", OWNER, OWNER, false),
                                    new TaskResource("SCHEMA", schema.generation().toString(), OWNER, false),
                                    new TaskResource("SERVER", SERVER.id(), OWNER, true));
                        }

                        public Optional<TaskRecoveryGraph> recoveryGraph(java.sql.Connection connection) {
                            return Optional.of(graph.get());
                        }
                    });
            assertTrue(ledger.tryStart(interrupted));
            new TaskAuthorCheckpoint(db).begin(interrupted, OWNER, VERSION, DeploymentSecretProvisionJournal.KEY,
                    payload.get().replace(prior.toString(), interrupted.toString()));
            assertFalse(ledger.complete(interrupted, Outcome.UNKNOWN, "Interrupted before secret create", false));
            try (var connection = db.openConnection();
                    var update = connection.prepareStatement("UPDATE mod_registry SET last_boot_id=?,"
                            + "last_runtime_state='UNKNOWN',actual_version=? WHERE mod_id=?")) {
                update.setObject(1, boot);
                update.setString(2, VERSION);
                update.setString(3, OWNER);
                assertEquals(1, update.executeUpdate());
            }
        }
        try (var runtime = open()) {
            var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
                public void show(ModPageRegistry.PageKey key, PageSpec spec, javax.swing.JComponent component) {
                    throw new AssertionError("Recovery must not mount a page");
                }

                public void focus(ModPageRegistry.PageKey key) {
                }

                public void remove(ModPageRegistry.PageKey key) {
                }

                public void environment(UiEnvironment environment) {
                }
            }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.running().isEmpty());
            assertEquals(List.of("MOD_RECOVERY_READY"), report.problems().stream().map(Problem::eventCode).toList());
            var recovered = runtime.tasks().reconcile(interrupted).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(Outcome.FAILED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
            runtime.releaseModRecovery();
        }
    }

    @Test
    void cancellationWhileObtainingValueNeverStartsSecretCreation() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var creates = new AtomicInteger();
            var owned = new AtomicReference<byte[]>();
            var secrets = intercept((method, args) -> {
                try {
                    if (method.getName().equals("create"))
                        creates.incrementAndGet();
                    return method.invoke(context.secrets(), args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            var flow = new DeploymentSecretProvisionFlow(withSecrets(context, secrets));
            var handle = flow.submitOwned(SERVER, REVISION, PURPOSE, CREATED, task -> {
                entered.countDown();
                assertTrue(release.await(20, TimeUnit.SECONDS));
                byte[] value = new byte[]{14, 15, 16};
                owned.set(value);
                return value;
            });
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS));
                handle.requestCancel();
            } finally {
                release.countDown();
            }
            var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(Outcome.CANCELLED, result.outcome(), result.toString());
            assertEquals(0, creates.get());
            assertArrayEquals(new byte[3], owned.get(), "Cancelled owned input must be cleared");
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
        }
    }
}
