package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformRemoteCapabilitiesTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.AuthorizationMode;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PlatformWorkflowDelegationTest {
    @TempDir
    Path root;

    static ActionIntent handoff(String... servers) {
        return new ActionIntent("deployment.workflow.delegate", List.of(servers), "deployment-instance", "frozen-1",
            "Deploy the exact original source and target under its frozen strategies", "Delegate this complete deployment workflow");
    }

    static ActionIntent action(CommandRequest request, String operation, String target) {
        return RemoteActions.command(SERVER, "original-1", request).withSemantics(new ActionSemantics(operation,
            Set.of(new ActionTarget(SERVER.id(), "deployment-directory", target))));
    }

    static long questions(Reviews reviews) {
        return reviews.approved.stream().filter(intent -> intent.type().equals("platform.ssh.execute")).count();
    }

    static long handoffs(Reviews reviews) {
        return reviews.approved.stream().filter(intent -> intent.type().equals("deployment.workflow.delegate")).count();
    }

    @ParameterizedTest
    @EnumSource(AuthorizationMode.class)
    void questionOnlyScopeDoesNotHandoffOrAutomaticallyDelegateOrdinaryEffects(AuthorizationMode mode) throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    if (mode == AuthorizationMode.FULL) {
                        assertThrows(ApiException.class, () -> capabilities.interaction().reviewedWorkflow(handoff("server"), mode, task));
                        return success(null);
                    }
                    try (var workflow = capabilities.interaction().reviewedWorkflow(handoff("server"), mode, task).value().orElseThrow();
                         var session = capabilities.linux().connect(SERVER, task);
                         var command = command("builtin printf 'question-only'")) {
                        for (int i = 0; i < 2; i++) {
                            var grant = capabilities.interaction().approve(action(command, "same.operation", "/srv/parent"), task).value().orElseThrow();
                            assertEquals(TaskResult.SUCCEEDED, session.execute(command, grant).outcome());
                        }
                        assertEquals(mode == AuthorizationMode.ONCE ? 2 : 1, questions(reviews));
                        assertEquals(0, handoffs(reviews));
                    }
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                assertEquals(mode == AuthorizationMode.FULL ? 0 : 2, remote.commands.get());
                assertEquals(0, handoffs(reviews));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AuthorizationMode.class)
    void actualRootHandoffAndRequiredQuestionsUseOnlyTheirFrozenBusinessMode(AuthorizationMode mode) throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    try (var workflow = capabilities.interaction().delegateWorkflow(handoff("server"), mode, task).value().orElseThrow();
                         var session = capabilities.linux().connect(SERVER, task)) {
                        assertEquals(mode == AuthorizationMode.FULL ? 0 : 1, handoffs(reviews));
                        runtime.persistence().preferences().save("platform_authorization_mode", mode == AuthorizationMode.FULL ? "ONCE" : "FULL");
                        assertThrows(ApiException.class, () -> capabilities.interaction().delegateWorkflow(handoff("server"), AuthorizationMode.FULL, task));
                        try (var command = command("builtin printf 'delegated'")) {
                            var actual = action(command, "delete.same-level", "/srv/owned-parent");
                            for (int i = 0; i < 2; i++) {
                                var grant = capabilities.interaction().approve(actual, task).value().orElseThrow();
                                assertEquals(TaskResult.SUCCEEDED, session.execute(command, grant).outcome());
                            }
                            assertEquals(0, questions(reviews));
                            for (int i = 0; i < 2; i++) {
                                var grant = capabilities.interaction().approveRequired(actual, task).value().orElseThrow();
                                assertEquals(TaskResult.SUCCEEDED, session.execute(command, grant).outcome());
                            }
                            assertEquals(mode == AuthorizationMode.ONCE ? 2 : mode == AuthorizationMode.GENERAL ? 1 : 0, questions(reviews));
                            for (var changed : List.of(action(command, "delete.other-kind", "/srv/owned-parent"),
                                action(command, "delete.same-level", "/srv/other-parent"))) {
                                var grant = capabilities.interaction().approveRequired(changed, task).value().orElseThrow();
                                assertEquals(TaskResult.SUCCEEDED, session.execute(command, grant).outcome());
                            }
                            assertEquals(mode == AuthorizationMode.ONCE ? 4 : mode == AuthorizationMode.GENERAL ? 3 : 0, questions(reviews));
                            var unclassified = RemoteActions.command(SERVER, "original-1", command);
                            var grant = capabilities.interaction().approve(unclassified, task).value().orElseThrow();
                            assertEquals(TaskResult.SUCCEEDED, session.execute(command, grant).outcome());
                            assertEquals(mode == AuthorizationMode.ONCE ? 5 : mode == AuthorizationMode.GENERAL ? 4 : 1, questions(reviews));
                        }
                    }
                    assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'workflow-delegation-%' AND released_at IS NULL"));
                    assertThrows(ApiException.class, () -> capabilities.interaction().delegateWorkflow(handoff("server"), mode, task));
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                assertEquals(7, remote.commands.get());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AuthorizationMode.class)
    void ordinaryRootWithoutHandoffStillRequiresRealHumanApproval(AuthorizationMode mode) throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    try (var session = capabilities.linux().connect(SERVER, task); var command = command("builtin printf 'ordinary'")) {
                        for (int i = 0; i < 2; i++) {
                            var actual = action(command, "same.operation", "/srv/original");
                            var grant = capabilities.interaction().approveRequired(actual, task).value().orElseThrow();
                            assertEquals(TaskResult.SUCCEEDED, session.execute(command, grant).outcome());
                        }
                    }
                    assertEquals(2, questions(reviews));
                    assertEquals(0, handoffs(reviews));
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                assertEquals(2, remote.commands.get());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AuthorizationMode.class)
    void forgedChangedExpiredAndForeignAuthorityCannotDispatch(AuthorizationMode mode) throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            var previousGrant = new AtomicReference<ApprovalGrant>();
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var foreign = runtime.capabilities().remoteCapabilities("foreign.mod", VERSION);
                 var old = runtime.capabilities().remoteCapabilities(OWNER, "old-release")) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    assertThrows(ApiException.class, () -> foreign.interaction().delegateWorkflow(handoff("server"), mode, task));
                    assertThrows(ApiException.class, () -> old.interaction().delegateWorkflow(handoff("server"), mode, task));
                    var workflow = capabilities.interaction().delegateWorkflow(handoff("server"), mode, task).value().orElseThrow();
                    try (var session = capabilities.linux().connect(SERVER, task); var command = command("builtin printf 'original'")) {
                        var grant = capabilities.interaction().approve(action(command, "exact.command", "/srv/app"), task).value().orElseThrow();
                        assertThrows(ApiException.class, () -> session.execute(command, new ApprovalGrant(grant.id())));
                        try (var changed = command("builtin printf 'changed'")) {
                            assertThrows(ApiException.class, () -> session.execute(changed, grant));
                        }
                        previousGrant.set(grant);
                        workflow.close();
                        assertThrows(ApiException.class, () -> session.execute(command, grant));
                        assertThrows(ApiException.class, () -> capabilities.interaction().delegateWorkflow(handoff("server"), mode, task));
                        assertEquals(0, remote.commands.get());
                    } finally {
                        workflow.close();
                    }
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                var other = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    try (var session = capabilities.linux().connect(SERVER, task); var command = command("builtin printf 'original'")) {
                        assertThrows(ApiException.class, () -> session.execute(command, previousGrant.get()));
                    }
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(other).outcome());
                assertEquals(0, remote.commands.get());
            }
        }
    }

    @Test
    void missingExtraAndIncompleteOriginalServerSetsNeverCreateDelegation() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var profile = runtime.persistence().servers().findServerProfile("server").orElseThrow();
            runtime.persistence().servers().saveServerProfile(new StoredServerProfile("second", profile.host(), profile.sshPort(), profile.username(), profile.credentialKey(), "Second"));
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server", "second"), task -> {
                    for (var wrong : List.of(handoff(), handoff("server"), handoff("server", "second", "missing")))
                        assertThrows(ApiException.class, () -> capabilities.interaction().delegateWorkflow(wrong, AuthorizationMode.FULL, task));
                    assertEquals(0, handoffs(reviews));
                    assertEquals(0, remote.commands.get());
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                var empty = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    assertThrows(ApiException.class, () -> capabilities.interaction().delegateWorkflow(handoff(), AuthorizationMode.FULL, task));
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(empty).outcome());
            }
        }
    }

    @Test
    void declinedHandoffLeavesNoWorkflowLeaseOrAuthority() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            reviews.policy = intent -> !intent.type().equals("deployment.workflow.delegate");
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    var refused = capabilities.interaction().delegateWorkflow(handoff("server"), AuthorizationMode.GENERAL, task);
                    assertEquals(TaskResult.CANCELLED, refused.outcome());
                    assertTrue(refused.value().isEmpty());
                    assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'workflow-delegation-%' AND released_at IS NULL"));
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                assertEquals(1, handoffs(reviews));
                assertEquals(0, remote.commands.get());
            }
        }
    }

    @Test
    void failedReleaseRevokesAuthorityRetainsOccupancyAndDoesNotReviveDuringRecovery() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            var retained = new AtomicReference<WorkflowGrant>();
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var handle = binding.submit(request("server"), task -> {
                    retained.set(capabilities.interaction().delegateWorkflow(handoff("server"), AuthorizationMode.FULL, task).value().orElseThrow());
                    assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class, () -> capabilities.interaction().closeWorkflowResources(task)).problem().code());
                    sql(runtime.persistence().database(), "ALTER TABLE platform_task_resource ADD CONSTRAINT workflow_release_fault CHECK(resource_id NOT LIKE 'workflow-delegation-%' OR released_at IS NULL)");
                    var failure = assertThrows(ApiException.class, retained.get()::close);
                    assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                    try (var command = command("builtin printf 'must not run'")) {
                        assertThrows(ApiException.class, () -> capabilities.interaction().approve(action(command, "exact.command", "/srv/app"), task));
                    }
                    return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(failure.problem()));
                });
                state(handle, TaskState.UNKNOWN);
                assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'workflow-delegation-%' AND released_at IS NULL"));
                sql(runtime.persistence().database(), "ALTER TABLE platform_task_resource DROP CONSTRAINT workflow_release_fault");
                try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                    assertThrows(ApiException.class, () -> capabilities.interaction().delegateWorkflow(handoff("server"), AuthorizationMode.FULL, task));
                    capabilities.interaction().closeWorkflowResources(task);
                    retained.get().close();
                    assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'workflow-delegation-%' AND released_at IS NULL"));
                    return success(null);
                })) {
                    java.util.concurrent.CompletionStage<gold.debug.windowstolinux.appapi.task.model.TaskSnapshot> reconciliation = null;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (reconciliation == null) {
                        try {
                            reconciliation = runtime.tasks().reconcile(handle.id());
                        } catch (ApiException busy) {
                            if (busy.problem().code() != ProblemCode.BUSY || System.nanoTime() >= deadline) throw busy;
                            Thread.sleep(5);
                        }
                    }
                    assertEquals(TaskState.ENDED, reconciliation.toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                }
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                assertEquals(0, remote.commands.get());
            }
        }
    }

    @Test
    void previousBootCleanupReleasesOnlyOriginalExpiredOccupancyWithoutRevivingAuthority() throws Exception {
        var data = root.resolve("data");
        var originalGraph = new AtomicReference<gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph>();
        UUID originalId = UUID.randomUUID();
        try (var remote = new Remote(root.resolve("ssh"))) {
            try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
                remote.configure(runtime);
                runtime.setInteractionPresenter(new Reviews());
                try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                    var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                        originalGraph.set(new gold.debug.windowstolinux.app.db.task.TaskLedger(runtime.persistence().database()).queries().recoveryGraph(task.id()).orElseThrow());
                        try (var workflow = capabilities.interaction().delegateWorkflow(handoff("server"), AuthorizationMode.FULL, task).value().orElseThrow()) {
                            return success(null);
                        }
                    });
                    assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                }
            }
            // Reproduce only retained original occupancy for a later actual engine boot; no permission is serialized.
            try (var database = gold.debug.windowstolinux.app.db.connect.PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
                var ledger = new gold.debug.windowstolinux.app.db.task.TaskLedger(database);
                var graph = originalGraph.get();
                ledger.enqueue(originalId, OWNER, VERSION, request("server"), new gold.debug.windowstolinux.app.db.task.TaskLedger.InitialClaims() {
                    public List<gold.debug.windowstolinux.app.db.task.TaskResource> resolve(java.sql.Connection connection) {
                        var claims = new java.util.ArrayList<>(graph.claims());
                        claims.add(new gold.debug.windowstolinux.app.db.task.TaskResource("SERVER", "server", OWNER, true));
                        return claims;
                    }

                    public Optional<gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph> recoveryGraph(java.sql.Connection connection) {
                        return Optional.of(graph);
                    }
                });
                assertTrue(ledger.tryStart(originalId));
                var resource = new gold.debug.windowstolinux.app.db.task.TaskResource("CUSTOM", "workflow-delegation-" + UUID.randomUUID(), OWNER, false);
                ledger.retain(originalId, resource, new gold.debug.windowstolinux.app.db.task.ResourceRecovery("workflow.v1",
                    Map.of("taskId", originalId.toString(), "owner", OWNER, "version", VERSION, "bootId", database.bootId().toString())), true);
                assertFalse(ledger.complete(originalId, TaskResult.UNKNOWN, "fixture", false));
                new gold.debug.windowstolinux.app.db.task.WorkflowRecoveryStore(database).releasePrevious(originalId, OWNER, VERSION);
                assertEquals(1, count(database, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'workflow-delegation-%' AND released_at IS NULL"));
            }
            try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                var expired = new gold.debug.windowstolinux.app.db.task.WorkflowRecoveryStore(runtime.persistence().database());
                assertThrows(java.sql.SQLException.class, () -> expired.releasePrevious(originalId, "foreign.mod", VERSION));
                assertThrows(java.sql.SQLException.class, () -> expired.releasePrevious(originalId, OWNER, "old-release"));
                assertThrows(java.sql.SQLException.class, () -> expired.releasePrevious(UUID.randomUUID(), OWNER, VERSION));
                assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'workflow-delegation-%' AND released_at IS NULL"));
                var reviews = new Reviews();
                runtime.setInteractionPresenter(reviews);
                try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                     var recovery = runtime.tasks().bind(OWNER, VERSION, true).registerRecovery("fixture", (previous, task) -> {
                         assertThrows(ApiException.class, () -> capabilities.interaction().delegateWorkflow(handoff("server"), AuthorizationMode.FULL, task));
                         assertThrows(ApiException.class, () -> capabilities.interaction().reviewedWorkflow(handoff("server"), AuthorizationMode.GENERAL, task));
                         capabilities.closeTaskResources(task);
                         assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'workflow-delegation-%' AND released_at IS NULL"));
                         return success(null);
                     })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(originalId).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                    assertEquals(0, handoffs(reviews));
                    assertEquals(0, remote.commands.get());
                }
            }
        }
    }

    @Test
    void malformedPreviousWorkflowEvidenceRollsBackAllExpiredLeaseRelease() throws Exception {
        var data = root.resolve("data");
        var originalId = UUID.randomUUID();
        try (var database = gold.debug.windowstolinux.app.db.connect.PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
            var ledger = new gold.debug.windowstolinux.app.db.task.TaskLedger(database);
            ledger.enqueue(originalId, OWNER, VERSION, request(), List.of(new gold.debug.windowstolinux.app.db.task.TaskResource("MOD", OWNER, OWNER, false)));
            assertTrue(ledger.tryStart(originalId));
            for (boolean corrupt : List.of(false, true)) {
                var facts = new gold.debug.windowstolinux.app.db.task.ResourceRecovery("workflow.v1", Map.of(
                    "taskId", originalId.toString(), "owner", OWNER, "version", corrupt ? "old-release" : VERSION,
                    "bootId", database.bootId().toString()));
                ledger.retain(originalId, new gold.debug.windowstolinux.app.db.task.TaskResource("CUSTOM",
                    "workflow-delegation-" + UUID.randomUUID(), OWNER, false), facts, true);
            }
            assertFalse(ledger.complete(originalId, TaskResult.UNKNOWN, "fixture", false));
        }
        try (var database = gold.debug.windowstolinux.app.db.connect.PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
            assertThrows(java.sql.SQLException.class, () -> new gold.debug.windowstolinux.app.db.task.WorkflowRecoveryStore(database).releasePrevious(originalId, OWNER, VERSION));
            assertEquals(2, count(database, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'workflow-delegation-%' AND released_at IS NULL"));
        }
    }
}
