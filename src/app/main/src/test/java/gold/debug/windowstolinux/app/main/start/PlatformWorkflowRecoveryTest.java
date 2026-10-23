package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformRemoteCapabilitiesTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWorkflowDelegationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.CommandReviewMode;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Fresh original recovery authority never reconstructs or reuses the old permission. */
class PlatformWorkflowRecoveryTest {
    @TempDir
    Path root;

    record Original(UUID id, TaskRecoveryGraph graph, TaskContext context, WorkflowGrant workflow,
                    ApprovalGrant commandGrant) {
    }

    Original capture(Path data, Remote remote, CommandReviewMode mode, boolean rememberAuthorization) throws Exception {
        var facts = new AtomicReference<Original>();
        try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setInteractionPresenter(new Reviews());
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    assertThrows(ApiException.class, () -> capabilities.interaction().openReviewedWorkflow(handoff("server"), mode, rememberAuthorization, true, task));
                    var workflow = capabilities.interaction().openReviewedWorkflow(handoff("server"), mode, rememberAuthorization, false, task).value().orElseThrow();
                    try (workflow; var request = command("builtin printf 'old-permit'")) {
                        var grant = capabilities.interaction().approveRequired(action(request, "exact.operation", "/srv/original"), task).value().orElseThrow();
                        facts.set(new Original(task.id(), new TaskLedger(runtime.persistence().database()).queries().recoveryGraph(task.id()).orElseThrow(), task, workflow, grant));
                    }
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                assertEquals(handle.id(), facts.get().id);
            }
        }
        return facts.get();
    }

    void seed(Path data, Original original, boolean corrupt) throws Exception {
        try (var database = PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
            var ledger = new TaskLedger(database);
            ledger.enqueue(original.id, OWNER, VERSION, request("server"), new TaskLedger.InitialClaims() {
                public List<TaskResource> resolve(Connection connection) {
                    var claims = new ArrayList<>(original.graph.claims());
                    claims.add(new TaskResource("SERVER", "server", OWNER, true));
                    return claims;
                }

                public Optional<TaskRecoveryGraph> recoveryGraph(Connection connection) {
                    return Optional.of(original.graph);
                }
            });
            assertTrue(ledger.tryStart(original.id));
            ledger.retain(original.id, new TaskResource("CUSTOM", "workflow-delegation-" + UUID.randomUUID(), OWNER, false),
                new ResourceRecovery("workflow.v1", Map.of("taskId", original.id.toString(), "owner", OWNER,
                    "version", corrupt ? "wrong-release" : VERSION, "bootId", database.bootId().toString())), true);
            assertFalse(ledger.complete(original.id, TaskResult.UNKNOWN, "retained original fixture", false));
        }
    }

    static long workflowLeases(PlatformRuntime runtime) throws Exception {
        return count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'workflow-delegation-%' AND released_at IS NULL");
    }

    static TaskSnapshot reconcile(PlatformRuntime runtime, UUID id) throws Exception {
        CompletionStage<TaskSnapshot> result = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (result == null) {
            try {
                result = runtime.tasks().reconcile(id);
            } catch (ApiException busy) {
                if (busy.problem().code() != ProblemCode.BUSY || System.nanoTime() >= deadline) throw busy;
                Thread.sleep(5);
            }
        }
        return result.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @CsvSource({"MANUAL_REVIEW,false", "AUTOMATIC,false", "FULL_CONTROL,false", "MANUAL_REVIEW,true", "AUTOMATIC,true"})
    void sameOriginalUuidAcrossBootUsesFreshModeAndNeverOldContextGrantOrHandle(CommandReviewMode mode, boolean rememberAuthorization) throws Exception {
        var data = root.resolve("data");
        try (var remote = new Remote(root.resolve("ssh"))) {
            var original = capture(data, remote, mode, rememberAuthorization);
            seed(data, original, false);
            try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                var reviews = new Reviews();
                runtime.setInteractionPresenter(reviews);
                try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                     var recovery = runtime.tasks().bind(OWNER, VERSION, true).registerRecovery("fixture", (previous, task) -> {
                         assertEquals(original.id, task.id());
                         assertNotSame(original.context, task);
                         assertThrows(ApiException.class, () -> capabilities.interaction().openReviewedWorkflow(handoff("server"), mode, rememberAuthorization, true, original.context));
                         assertThrows(ApiException.class, () -> capabilities.interaction().openReviewedWorkflow(handoff("server"), mode, rememberAuthorization, false, task));
                         if (mode != CommandReviewMode.FULL_CONTROL)
                             assertThrows(ApiException.class, () -> capabilities.interaction().openReviewedWorkflow(handoff("server"), mode, rememberAuthorization, false, task));
                         assertThrows(ApiException.class, () -> capabilities.interaction().openReviewedWorkflow(handoff(), mode, rememberAuthorization, true, task));
                         assertThrows(ApiException.class, () -> capabilities.interaction().openReviewedWorkflow(handoff("server"), null, true, true, task));
                         try (var workflow = capabilities.interaction().openReviewedWorkflow(handoff("server"), mode, rememberAuthorization, true, task).value().orElseThrow()) {
                             assertEquals(mode == CommandReviewMode.FULL_CONTROL ? 1 : 0, handoffs(reviews));
                             assertEquals(1, workflowLeases(runtime));
                             assertThrows(ApiException.class, () -> capabilities.interaction().openReviewedWorkflow(handoff("server"), mode, rememberAuthorization, true, task));
                             original.workflow.close();
                             assertEquals(1, workflowLeases(runtime), "Old close-only handle must not release the fresh lease");
                             try (var session = capabilities.linux().connect(SERVER, task); var request = command("builtin printf 'fresh-recovery'")) {
                                 assertThrows(ApiException.class, () -> session.execute(request, original.commandGrant));
                                 assertEquals(0, remote.commands.get());
                                 for (int i = 0; i < 2; i++) {
                                     var grant = capabilities.interaction().approveReviewedAction(action(request, "same.question", "/srv/original"), Optional.of(request), true, "Actual recovery action", task).value().orElseThrow();
                                     assertEquals(TaskResult.SUCCEEDED, session.execute(request, grant).outcome());
                                 }
                                 assertEquals(mode == CommandReviewMode.FULL_CONTROL ? 0 : rememberAuthorization ? 1 : 2, questions(reviews));
                             }
                         }
                         assertEquals(0, workflowLeases(runtime));
                         return success(null);
                     })) {
                    assertEquals(TaskState.ENDED, reconcile(runtime, original.id).state());
                    assertEquals(2, remote.commands.get());
                }
            }
        }
    }

    @Test
    void malformedOldHeaderBlocksFreshScopeBeforePromptOrDispatchUntilOriginalEvidenceIsRestored() throws Exception {
        var data = root.resolve("data");
        try (var remote = new Remote(root.resolve("ssh"))) {
            var original = capture(data, remote, CommandReviewMode.AUTOMATIC, false);
            seed(data, original, true);
            try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                var reviews = new Reviews();
                runtime.setInteractionPresenter(reviews);
                var corrected = new AtomicBoolean();
                try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                     var recovery = runtime.tasks().bind(OWNER, VERSION, true).registerRecovery("fixture", (previous, task) -> {
                         if (!corrected.get()) {
                             var failure = assertThrows(ApiException.class, () -> capabilities.interaction().openReviewedWorkflow(handoff("server"), CommandReviewMode.AUTOMATIC, true, true, task));
                             assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                             assertEquals(0, handoffs(reviews));
                             assertEquals(0, remote.commands.get());
                             assertEquals(1, workflowLeases(runtime));
                             return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(failure.problem()));
                         }
                         try (var workflow = capabilities.interaction().openReviewedWorkflow(handoff("server"), CommandReviewMode.AUTOMATIC, true, true, task).value().orElseThrow()) {
                             assertEquals(0, handoffs(reviews));
                             assertEquals(1, workflowLeases(runtime));
                         }
                         return success(null);
                     })) {
                    assertEquals(TaskState.UNKNOWN, reconcile(runtime, original.id).state());
                    sql(runtime.persistence().database(), "UPDATE platform_task_resource SET recovery_json=REPLACE(recovery_json,'wrong-release','" + VERSION + "') WHERE task_id=? AND resource_id LIKE 'workflow-delegation-%'", original.id);
                    corrected.set(true);
                    assertEquals(TaskState.ENDED, reconcile(runtime, original.id).state());
                    assertEquals(0, remote.commands.get());
                }
            }
        }
    }

    @Test
    void failedSameBootOldCloseMustFinishBeforeFreshScopeAndOldPermitCannotAffectNewLease() throws Exception {
        try (var remote = new Remote(root.resolve("ssh")); var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            var oldScope = new AtomicReference<WorkflowGrant>();
            var oldGrant = new AtomicReference<ApprovalGrant>();
            var corrected = new AtomicBoolean();
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var handle = binding.submit(request("server"), task -> {
                    oldScope.set(capabilities.interaction().openReviewedWorkflow(handoff("server"), CommandReviewMode.FULL_CONTROL, true, false, task).value().orElseThrow());
                    try (var request = command("builtin printf 'same-boot'")) {
                        oldGrant.set(capabilities.interaction().approve(action(request, "same.question", "/srv/original"), task).value().orElseThrow());
                    }
                    sql(runtime.persistence().database(), "ALTER TABLE platform_task_resource ADD CONSTRAINT workflow_recovery_release_fault CHECK(resource_id NOT LIKE 'workflow-delegation-%' OR released_at IS NULL)");
                    var failure = assertThrows(ApiException.class, oldScope.get()::close);
                    assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                    return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(failure.problem()));
                });
                state(handle, TaskState.UNKNOWN);
                try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                    if (!corrected.get()) {
                        var failure = assertThrows(ApiException.class, () -> capabilities.interaction().openReviewedWorkflow(handoff("server"), CommandReviewMode.AUTOMATIC, true, true, task));
                        assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                        assertEquals(1, handoffs(reviews));
                        assertEquals(1, workflowLeases(runtime));
                        return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(failure.problem()));
                    }
                    try (var fresh = capabilities.interaction().openReviewedWorkflow(handoff("server"), CommandReviewMode.AUTOMATIC, true, true, task).value().orElseThrow()) {
                        assertEquals(1, handoffs(reviews));
                        oldScope.get().close();
                        assertEquals(1, workflowLeases(runtime));
                        try (var session = capabilities.linux().connect(SERVER, task); var request = command("builtin printf 'same-boot'")) {
                            assertThrows(ApiException.class, () -> session.execute(request, oldGrant.get()));
                            var grant = capabilities.interaction().approveReviewedAction(action(request, "same.question", "/srv/original"), Optional.of(request), true, "Actual recovery action", task).value().orElseThrow();
                            assertEquals(TaskResult.SUCCEEDED, session.execute(request, grant).outcome());
                        }
                        assertEquals(2, questions(reviews));
                    }
                    return success(null);
                })) {
                    assertEquals(TaskState.UNKNOWN, reconcile(runtime, handle.id()).state());
                    sql(runtime.persistence().database(), "ALTER TABLE platform_task_resource DROP CONSTRAINT workflow_recovery_release_fault");
                    corrected.set(true);
                    assertEquals(TaskState.ENDED, reconcile(runtime, handle.id()).state());
                    assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                    assertEquals(1, remote.commands.get());
                    assertEquals(0, workflowLeases(runtime));
                }
            }
        }
    }
}
