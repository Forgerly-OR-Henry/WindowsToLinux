package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.persistence.repository.ServerReferenceRepository;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.server.ReferenceState;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;

class DeploymentReferenceFlowTest extends DeploymentExecutionFixture {
    private TaskSnapshot original(TaskContext task) {
        return new TaskSnapshot(task.id(), OWNER, VERSION, DeploymentReferenceFlow.TASK_TYPE, TaskState.RUNNING,
                Optional.empty(), "Publication recovery", Instant.now(), Optional.empty(), List.of());
    }

    private void runOriginal(PlatformRuntime runtime, TaskWork<Void> work) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var observed = new AtomicReference<Throwable>();
        var handle = context.tasks().submit(
                new TaskRequest(DeploymentReferenceFlow.TASK_TYPE, "Publication recovery", List.of("server"), true),
                task -> {
                    try {
                        return work.run(task);
                    } catch (Exception | AssertionError failure) {
                        observed.set(failure);
                        throw failure;
                    }
                });
        try {
            var result = handle.completion().toCompletableFuture().get(12, TimeUnit.SECONDS);
            if (observed.get() != null)
                throw new AssertionError("Recovery fixture failed", observed.get());
            assertEquals(Outcome.SUCCEEDED, result.outcome());
        } catch (java.util.concurrent.TimeoutException unfinished) {
            throw new AssertionError("Recovery task stuck: " + handle.snapshot(),
                    observed.get() == null ? unfinished : observed.get());
        }
    }

    @Test
    void submittedPublicationPersistsOriginalReferencesAndFinishesAfterRealPlatformResolution() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var graph = graph("application");
            var records = publications(graph, 1, List.of());
            var intents = new ArrayList<DeploymentReferenceIntent>();
            intents.add(DeploymentReferenceKey.application(new ServerRef("server"), "application"));
            graph.components().forEach(component -> intents
                    .add(DeploymentReferenceKey.unit(new ServerRef("server"), component.unit().id())));
            var plan = new DeploymentPublicationPlan(UUID.randomUUID(), Optional.empty(), "application",
                    new ServerRef("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64),
                    intents, OBSERVED);
            var calls = new AtomicInteger();
            var handle = new DeploymentReferenceFlow(context).submit(plan, task -> {
                calls.incrementAndGet();
                return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new DeploymentPublication(graph, records)),
                        Optional.empty());
            });
            OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException unfinished) {
                String durable;
                try (var access = scope(runtime, true);
                        var connection = access.openConnection();
                        var query = connection.createStatement();
                        var rows = query.executeQuery(
                                "SELECT phase,outcome,business_committed,cleanup_complete FROM deployment_execution")) {
                    durable = rows.next()
                            ? rows.getString(1) + "/" + rows.getString(2) + "/" + rows.getBoolean(3) + "/"
                                    + rows.getBoolean(4)
                            : "absent";
                }
                throw new AssertionError("Publication stuck: " + handle.snapshot() + ", author calls=" + calls.get()
                        + ", durable=" + durable, unfinished);
            }
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            assertEquals(1, calls.get());
            try (var scope = scope(runtime, true);
                    var connection = scope.openConnection();
                    var query = connection.createStatement();
                    var rows = query.executeQuery(
                            "SELECT COUNT(*) FROM deployment_execution WHERE business_committed=TRUE AND cleanup_complete=TRUE")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
            }
            assertEquals(intents.size(), scalar(runtime,
                    "SELECT COUNT(*) FROM mod_server_reference WHERE business_key LIKE 'deployment:v1:%' AND state='ACTIVE'"));
            valid(runtime);
        }
    }

    @Test
    void preparedRecoveryFindsLostOriginalUuidAndRequiresActualFileRelease() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            runOriginal(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentExecutionRepository(context.db());
                var identity = identity(task, UUID.randomUUID());
                var intent = DeploymentReferenceKey.application(new ServerRef("server"), "application");
                journal.begin(task, identity, DeploymentReferenceFlow.TASK_TYPE, List.of(intent), Optional.empty(),
                        OBSERVED);
                var original = context.servers().reserve(new ServerRef("server"), intent.businessKey(), task);
                var workspace = context.source().workspace(task);
                var flow = new DeploymentReferenceFlow(context);
                assertEquals(Outcome.UNKNOWN, flow.reconcile(original(task), task).outcome());
                assertTrue(context.servers().findReference(new ServerRef("server"), intent.businessKey(), task)
                        .isPresent());
                workspace.close();
                assertEquals(Outcome.FAILED, flow.reconcile(original(task), task).outcome());
                assertEquals(Optional.of(original.id()),
                        journal.references(journal.find(task, identity.executionId()).orElseThrow()).get(intent));
                assertTrue(
                        context.servers().findReference(new ServerRef("server"), intent.businessKey(), task).isEmpty());
                assertTrue(journal.find(task, identity.executionId()).orElseThrow().cleanupComplete());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void registeredRecoveryFinishesUnstartedOriginalTaskWithoutRunningAuthorAgain() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var calls = new AtomicInteger();
            var intent = DeploymentReferenceKey.application(new ServerRef("server"), "application");
            var handle = context.tasks().submit(new TaskRequest(DeploymentReferenceFlow.TASK_TYPE,
                    "Recover original publication", List.of("server"), true), task -> {
                        calls.incrementAndGet();
                        new DeploymentExecutionRepository(context.db()).begin(task, identity(task, UUID.randomUUID()),
                                DeploymentReferenceFlow.TASK_TYPE, List.of(intent), Optional.empty(), OBSERVED);
                        return new OperationResult<Void>(Outcome.UNKNOWN, Optional.empty(),
                                Optional.of(problem(Outcome.UNKNOWN, "ORIGINAL_EFFECT_UNCERTAIN")));
                    });
            for (int attempt = 0; attempt < 100 && handle.snapshot().state() != TaskState.UNKNOWN; attempt++)
                Thread.sleep(50);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
            assertEquals(Optional.of(Outcome.FAILED), recovered.outcome());
            assertEquals(1, calls.get());
            valid(runtime);
        }
    }

    @Test
    void parallelServersKeepDistinctOriginalSecretReferencesWhenOnePublicationFails() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            runtime.servers().saveServerProfile(
                    ServerProfile.fromStored(
                            new StoredServerProfile("server2", "127.0.0.2", 22, "tester", "fixture-password")),
                    "fixture-secret-2".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var secretHolder = new AtomicReference<SecretRef>();
            var revision = new DeploymentSecretReference("shared", 1);
            run(runtime, task -> {
                var secret = context.secrets().create("deployment.configuration", new byte[]{7}, task);
                secretHolder.set(secret);
                return success();
            });
            var secret = secretHolder.get();
            var first = DeploymentReferenceKey.secretRevision(new ServerRef("server"), secret, revision);
            var second = DeploymentReferenceKey.secretRevision(new ServerRef("server2"), secret, revision);
            assertNotEquals(first.businessKey(), second.businessKey());
            var graph = new DeploymentApplicationGraph("otherapp", "worker", new DeploymentHealthCheck.Process(20, 2),
                    List.of(component("worker", "server2", List.of())));
            var firstPlan = new DeploymentPublicationPlan(UUID.randomUUID(), Optional.empty(), "application",
                    new ServerRef("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64),
                    List.of(DeploymentReferenceKey.application(new ServerRef("server"), "application"), first),
                    OBSERVED);
            var secondPlan = new DeploymentPublicationPlan(UUID.randomUUID(), Optional.empty(), "otherapp",
                    new ServerRef("server2"), DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC,
                    "a".repeat(64), List.of(DeploymentReferenceKey.application(new ServerRef("server2"), "otherapp"),
                            DeploymentReferenceKey.unit(new ServerRef("server2"), "worker"), second),
                    OBSERVED);
            var ready = new CountDownLatch(2);
            var releaseRequested = new CountDownLatch(1);
            var revisionSaved = new CountDownLatch(1);
            SecretService intercepted = (SecretService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SecretService.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("resolve") && arguments[1] == ReferenceResolution.NOT_REFERENCED) {
                            releaseRequested.countDown();
                            if (!revisionSaved.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Second server did not establish its revision");
                        }
                        try {
                            return method.invoke(context.secrets(), arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            ModContext firstContext = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("secrets"))
                            return intercepted;
                        try {
                            return method.invoke(context, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            var failed = new DeploymentReferenceFlow(firstContext).submit(firstPlan, task -> {
                ready.countDown();
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                return new OperationResult<DeploymentPublication>(Outcome.FAILED, Optional.empty(),
                        Optional.of(problem(Outcome.FAILED, "FIRST_SERVER_FAILED")));
            });
            var published = new DeploymentReferenceFlow(context).submit(secondPlan, task -> {
                ready.countDown();
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                assertTrue(releaseRequested.await(10, TimeUnit.SECONDS));
                new DeploymentSecretRepository(context.db()).saveRevision(task,
                        new DeploymentSecretRevision(revision, secret.id(), OBSERVED));
                revisionSaved.countDown();
                return new OperationResult<>(Outcome.SUCCEEDED,
                        Optional.of(new DeploymentPublication(graph, publications(graph, 1, List.of(revision)))),
                        Optional.empty());
            });
            assertEquals(Outcome.FAILED, failed.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(Outcome.SUCCEEDED,
                    published.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, scalar(runtime,
                    "SELECT COUNT(*) FROM mod_credential_reference WHERE business_key LIKE 'deployment:v1:secret-revision:%'"));
            assertEquals(1, scalar(runtime,
                    "SELECT COUNT(*) FROM mod_credential_reference WHERE business_key LIKE 'deployment:v1:secret-revision:%' AND state='ACTIVE'"));
            valid(runtime);
        }
    }

    @Test
    void lostResolutionAcknowledgementRecoversKnownFailureByOriginalUuid() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var original = runtime.modContexts().get(OWNER).context();
            var injected = new java.util.concurrent.atomic.AtomicBoolean();
            ServerService servers = (ServerService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ServerService.class}, (proxy, method, arguments) -> {
                        try {
                            var result = method.invoke(original.servers(), arguments);
                            if (method.getName().equals("resolve") && arguments[1] == ReferenceResolution.NOT_REFERENCED
                                    && injected.compareAndSet(false, true))
                                throw new ApiException(new Problem(ProblemCode.UNKNOWN, "RESOLUTION_ACK_LOST",
                                        "Resolution acknowledgement lost", Map.of(), Optional.empty()));
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            ModContext wrapped = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("servers"))
                            return servers;
                        try {
                            return method.invoke(original, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            var plan = new DeploymentPublicationPlan(UUID.randomUUID(), Optional.empty(), "application",
                    new ServerRef("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64),
                    List.of(DeploymentReferenceKey.application(new ServerRef("server"), "application")), OBSERVED);
            var handle = new DeploymentReferenceFlow(wrapped).submit(plan, task -> new OperationResult<>(Outcome.FAILED,
                    Optional.empty(), Optional.of(problem(Outcome.FAILED, "AUTHOR_CONFIRMED_FAILURE"))));
            for (int attempt = 0; attempt < 100 && handle.snapshot().state() != TaskState.UNKNOWN; attempt++)
                Thread.sleep(50);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertTrue(injected.get());
            var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
            assertEquals(Optional.of(Outcome.FAILED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment:v1:application:application'"));
            valid(runtime);
        }
    }

    @Test
    void directKnownFailurePreservesItsProblemWhileDurableResultStoresOnlySafeCodes() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var flow = new DeploymentReferenceFlow(context);
            var reference = DeploymentReferenceKey.application(new ServerRef("server"), "application");
            var plan = new DeploymentPublicationPlan(UUID.randomUUID(), Optional.empty(), "application",
                    new ServerRef("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64),
                    List.of(reference), OBSERVED);
            var original = new Problem(ProblemCode.UNAVAILABLE, "deployment.author-failed", "Memory-only marker",
                    Map.of("private", "secret-value"), Optional.empty());
            var failed = flow
                    .submit(plan,
                            task -> new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(original)))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(Outcome.FAILED, failed.outcome());
            assertEquals(Optional.of(original), failed.problem());
            var cancelledPlan = new DeploymentPublicationPlan(UUID.randomUUID(), Optional.empty(), "application",
                    new ServerRef("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64),
                    List.of(reference), OBSERVED);
            var cancelled = flow
                    .submit(cancelledPlan, task -> new OperationResult<DeploymentPublication>(Outcome.CANCELLED,
                            Optional.empty(), Optional.empty()))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(Outcome.CANCELLED, cancelled.outcome());
            assertEquals(ProblemCode.CANCELLED, cancelled.problem().orElseThrow().code());
            try (var access = scope(runtime, true);
                    var connection = access.openConnection();
                    var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT result_payload FROM deployment_execution")) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    var json = rows.getString(1);
                    assertFalse(json.contains("Memory-only marker"));
                    assertFalse(json.contains("secret-value"));
                    assertTrue(json.contains("problemCode"));
                    assertTrue(json.contains("eventCode"));
                }
                assertEquals(2, count);
            }
            valid(runtime);
        }
    }

    @Test
    void retainedPreparedTaskFindsOriginalUuidAfterPlatformReopenWithoutReservingAgain() throws Exception {
        enable();
        UUID taskId = UUID.randomUUID();
        var intent = DeploymentReferenceKey.application(new ServerRef("server"), "application");
        gold.debug.windowstolinux.appapi.db.SchemaIdentity schema;
        TaskRecoveryGraph graph;
        UUID oldBoot;
        UUID originalReference;
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            schema = runtime.modContexts().get(OWNER).context().db().identity();
            oldBoot = runtime.persistence().database().bootId();
            var captured = new AtomicReference<TaskRecoveryGraph>();
            var probe = runtime.modContexts().get(OWNER).context().tasks().submit(
                    new TaskRequest(DeploymentReferenceFlow.TASK_TYPE, "Graph capture", List.of("server"), true),
                    task -> {
                        captured.set(new TaskLedger(runtime.persistence().database()).recoveryGraph(task.id())
                                .orElseThrow());
                        return success();
                    });
            assertEquals(Outcome.SUCCEEDED,
                    probe.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            graph = captured.get();
            var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), taskId, Optional.empty(), "application",
                    new ServerRef("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC,
                    "a".repeat(64));
            try (var access = scope(runtime, false);
                    var connection = access.openConnection();
                    var insert = connection.prepareStatement(
                            "INSERT INTO deployment_execution(execution_id,platform_task_id,project_id,application_id,server_profile_id,mode,phase,reviewed_revision,request_payload,recovery_payload,created_at,updated_at) VALUES(?,?,?,?,?,?,'PREPARED',?,?,?,?,?)")) {
                insert.setObject(1, identity.executionId());
                insert.setObject(2, taskId);
                insert.setObject(3, null);
                insert.setString(4, identity.applicationId());
                insert.setString(5, identity.server().id());
                insert.setString(6, identity.mode().name());
                insert.setString(7, identity.reviewedRevision());
                insert.setBytes(8,
                        DeploymentExecutionCodec
                                .request(identity, DeploymentReferenceFlow.TASK_TYPE, List.of(intent), Optional.empty())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                insert.setBytes(9, DeploymentExecutionCodec.recovery(identity, "PREPARED", Map.of())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                insert.setLong(10, OBSERVED.toEpochMilli());
                insert.setLong(11, OBSERVED.toEpochMilli());
                insert.executeUpdate();
            }
        }
        try (var db = PlatformDatabase.open(root.resolve("data"), PROTECTION)) {
            var ledger = new TaskLedger(db);
            ledger.enqueue(taskId, OWNER, VERSION,
                    new TaskRequest(DeploymentReferenceFlow.TASK_TYPE, "Original publication", List.of("server"), true),
                    new TaskLedger.InitialClaims() {
                        public List<TaskResource> resolve(java.sql.Connection connection) {
                            return List.of(new TaskResource("MOD", OWNER, OWNER, false),
                                    new TaskResource("SCHEMA", schema.generation().toString(), OWNER, false),
                                    new TaskResource("SERVER", "server", OWNER, true));
                        }

                        public Optional<TaskRecoveryGraph> recoveryGraph(java.sql.Connection connection) {
                            return Optional.of(graph);
                        }
                    });
            assertTrue(ledger.tryStart(taskId));
            assertFalse(ledger.complete(taskId, Outcome.UNKNOWN, "Original interrupted boundary", false));
            originalReference = new ServerReferenceRepository(new RepositoryConnections(db::openConnection))
                    .reserve(schema, "server", intent.businessKey()).id();
            try (var connection = db.openConnection();
                    var update = connection.prepareStatement(
                            "UPDATE mod_registry SET last_boot_id=?,last_runtime_state='UNKNOWN',actual_version=? WHERE mod_id=?")) {
                update.setObject(1, oldBoot);
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
            var recovered = runtime.tasks().reconcile(taskId).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
            assertEquals(Optional.of(Outcome.FAILED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment:v1:application:application'"));
            try (var access = new gold.debug.windowstolinux.app.db.schema.SchemaAccessScope(
                    runtime.persistence().database(), schema, UUID.randomUUID(), true)) {
                assertTrue(new DeploymentSchema().validate(access).valid());
                try (var connection = access.openConnection();
                        var query = connection.prepareStatement(
                                "SELECT recovery_payload,cleanup_complete FROM deployment_execution WHERE platform_task_id=?")) {
                    query.setObject(1, taskId);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertTrue(new String(rows.getBytes(1), java.nio.charset.StandardCharsets.UTF_8)
                                .contains(originalReference.toString()));
                        assertTrue(rows.getBoolean(2));
                    }
                }
            }
            runtime.releaseModRecovery();
        }
    }

    @Test
    void executingUnknownRecoveryProtectsOriginalUuidWithoutStartingEffectsAgain() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            runOriginal(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentExecutionRepository(context.db());
                var identity = identity(task, UUID.randomUUID());
                var intent = DeploymentReferenceKey.application(new ServerRef("server"), "application");
                journal.begin(task, identity, DeploymentReferenceFlow.TASK_TYPE, List.of(intent), Optional.empty(),
                        OBSERVED);
                var reference = context.servers().reserve(new ServerRef("server"), intent.businessKey(), task);
                journal.recordReference(task, identity.executionId(), intent, reference.id(), OBSERVED.plusSeconds(1));
                journal.startEffects(task, identity.executionId(), OBSERVED.plusSeconds(2));
                journal.recordUncommittedOutcome(task, identity.executionId(), Outcome.UNKNOWN,
                        problem(Outcome.UNKNOWN, "AUTHOR_EFFECT_UNCERTAIN"), OBSERVED.plusSeconds(3));
                var unresolved = new DeploymentReferenceFlow(context).reconcile(original(task), task);
                assertEquals(Outcome.UNKNOWN, unresolved.outcome());
                assertEquals("AUTHOR_EFFECT_UNCERTAIN", unresolved.problem().orElseThrow().eventCode());
                assertEquals(ReferenceState.UNKNOWN, context.servers()
                        .findReference(new ServerRef("server"), intent.businessKey(), task).orElseThrow().state());
                assertThrows(java.sql.SQLException.class,
                        () -> journal.startEffects(task, identity.executionId(), OBSERVED.plusSeconds(4)));
                // This fixture alone knows no remote effect occurred and explicitly supplies a later known result. /
                // 仅本夹具知道没有远端副作用，并明确提供后续已知结果。
                journal.recordUncommittedOutcome(task, identity.executionId(), Outcome.FAILED,
                        problem(Outcome.FAILED, "MANUALLY_PROVEN_ABSENT"), Instant.now());
                context.servers().resolve(reference.id(), ReferenceResolution.NOT_REFERENCED, task);
                journal.finishCleanup(task, identity.executionId(), Instant.now());
                return success();
            });
            valid(runtime);
        }
    }
}
