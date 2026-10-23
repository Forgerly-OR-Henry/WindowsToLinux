package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.model.ModIdentity;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;

/** Original-worker queue, compare-and-set evidence and restart boundaries; no network or deployment. */
class DeploymentApprovalPoliciesTest {
    @Test
    void anAlreadyOpenHumanQuestionFinishesUnderTheOldPolicyBeforeAQueuedChangeIsApplied() throws Exception {
        var fixture = new Fixture();
        var opened = new java.util.concurrent.CountDownLatch(1);
        var queued = new java.util.concurrent.CountDownLatch(1);
        var uiFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var controller = fixture.open(false)) {
            fixture.manualQuestion = () -> {
                opened.countDown();
                try {
                    assertTrue(queued.await(3, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
                assertEquals("MANUAL_REVIEW", controller.confirmed().mode());
                assertEquals(0, fixture.calls.get());
            };
            var ui = new Thread(() -> {
                try {
                    assertTrue(opened.await(3, java.util.concurrent.TimeUnit.SECONDS));
                    DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "AUTOMATIC", 0);
                } catch (Throwable failure) {
                    uiFailure.set(failure);
                } finally {
                    queued.countDown();
                }
            });
            ui.start();
            var intent = new ActionIntent("fixture.original.question", List.of("server"), "original", "1", "original", "Original question");
            assertEquals(TaskResult.SUCCEEDED, fixture.context.interaction().approve(intent, fixture.task).outcome());
            ui.join(3000);
            assertFalse(ui.isAlive());
            assertNull(uiFailure.get());
            assertEquals(0, fixture.calls.get());
            assertEquals("AUTOMATIC", DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void queuedUiChangeDoesNotCallHostUntilTheNextWorkerBoundaryAndPreservesRemember() throws Exception {
        var fixture = new Fixture();
        try (var controller = fixture.open(false)) {
            DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "AUTOMATIC", 0);
            assertEquals(0, fixture.calls.get());
            assertEquals("MANUAL_REVIEW", controller.state().mode());
            assertEquals(Optional.of("AUTOMATIC"), controller.state().pendingMode());
            assertEquals("AUTOMATIC", DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW"));
            assertEquals(1, fixture.calls.get());
            assertEquals(1, controller.state().revision());
            assertTrue(controller.state().pendingMode().isEmpty());
            assertFalse(fixture.checkpoint.snapshot().payload().contains("\"rememberAuthorization\":true"));
            assertEquals(Optional.of("DEPLOYMENT_APPROVAL_PREFERENCE_SAVE_FAILED"), controller.state().problem(),
                "An unavailable preference database does not undo the applied task policy");
            assertTrue(controller.state().editable());
            assertThrows(ApiException.class, () -> DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "FULL_CONTROL", 0));
        }
    }

    @Test
    void deniedFullControlKeepsTheConfirmedModeAndItsRevision() throws Exception {
        var fixture = new Fixture();
        fixture.answer = ignored -> new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.of(problem(ProblemCode.CANCELLED)));
        try (var controller = fixture.open(false)) {
            DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "FULL_CONTROL", 0);
            assertEquals("MANUAL_REVIEW", DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW"));
            assertEquals(0, controller.confirmed().revision());
            assertTrue(controller.state().pendingMode().isEmpty());
            assertTrue(controller.state().editable());
            assertEquals("MANUAL_REVIEW", DeploymentApprovalPolicies.original(fixture.context, fixture.task, "AUTOMATIC").mode());
            fixture.answer = mode -> success(new WorkflowPolicyState(mode, 1));
            DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "AUTOMATIC", 0);
            assertEquals("AUTOMATIC", DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW"));
            assertEquals(1, controller.confirmed().revision());
        }
    }

    @Test
    void unknownChangeBlocksFurtherActionsAndRestartNeverReplaysIt() throws Exception {
        var fixture = new Fixture();
        fixture.answer = ignored -> new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem(ProblemCode.UNKNOWN)));
        try (var controller = fixture.open(false)) {
            DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "AUTOMATIC", 0);
            assertEquals(ProblemCode.UNKNOWN, assertThrows(ApiException.class,
                () -> DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW")).problem().code());
            assertFalse(controller.state().editable());
            assertEquals("MANUAL_REVIEW", controller.state().mode());
            assertThrows(ApiException.class, () -> DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW"));
            assertEquals(1, fixture.calls.get());
        }
        assertFalse(fixture.checkpoint.snapshot().released());
        var restarted = fixture.restart();
        assertEquals(ProblemCode.UNKNOWN, assertThrows(ApiException.class,
            () -> DeploymentApprovalPolicies.original(restarted.context, restarted.task, "FULL_CONTROL")).problem().code());
        assertEquals(0, restarted.calls.get());
    }

    @Test
    void confirmedRestartReadsReleasedEvidenceInsteadOfTheNewPreferenceAndDoesNotCreateAnotherCheckpoint() throws Exception {
        var fixture = new Fixture();
        try (var controller = fixture.open(false)) {
            DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "AUTOMATIC", 0);
            DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW");
        }
        assertTrue(fixture.checkpoint.snapshot().released());
        var restarted = fixture.restart();
        var state = DeploymentApprovalPolicies.original(restarted.context, restarted.task, "FULL_CONTROL");
        assertEquals(new DeploymentApprovalPolicies.Confirmed("AUTOMATIC", 1), state);
        try (var controller = restarted.open(true)) {
            assertEquals(state, controller.confirmed());
            assertFalse(controller.state().editable());
            assertThrows(ApiException.class, () -> DeploymentApprovalPolicies.request(restarted.context, restarted.task.id(), "FULL_CONTROL", 1));
        }
        assertEquals(0, restarted.begins.get());
        assertEquals(0, restarted.calls.get());
    }

    @Test
    void legacyRecoveryDoesNotCreateMissingEvidenceOrOfferLivePolicyEdits() throws Exception {
        var fixture = new Fixture();
        try (var controller = fixture.open(true)) {
            assertFalse(controller.state().editable());
            assertEquals(0, fixture.begins.get());
        }
        assertNull(fixture.checkpoint);
    }

    @Test
    void hostRevisionRestartsAtZeroWhileDurableRevisionContinuesMonotonicallyForTheNextWorker() throws Exception {
        var fixture = new Fixture();
        var inherited = new DeploymentApprovalPolicies.Confirmed("AUTOMATIC", 7);
        fixture.answer = mode -> success(new WorkflowPolicyState(mode, 1));
        try (var controller = DeploymentApprovalPolicies.open(fixture.context, fixture.task, fixture.permission, inherited, false, false)) {
            DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "MANUAL_REVIEW", 7);
            assertEquals("MANUAL_REVIEW", DeploymentApprovalPolicies.boundary(fixture.task, "AUTOMATIC"));
            assertEquals(8, controller.confirmed().revision());
            assertEquals(List.of(0L), fixture.hostRevisions);
        }
    }

    @Test
    void policyConfirmationPersistenceFailurePreservesApplyingAndDoesNotRepeatTheHostUpdate() throws Exception {
        var fixture = new Fixture();
        try (var controller = fixture.open(false)) {
            fixture.checkpoint.failConfirmation = true;
            DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "AUTOMATIC", 0);
            assertThrows(ApiException.class, () -> DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW"));
            assertEquals(1, fixture.calls.get());
            assertEquals("MANUAL_REVIEW", controller.confirmed().mode());
            assertTrue(fixture.checkpoint.snapshot().payload().contains("\"phase\":\"APPLYING\""));
            assertThrows(ApiException.class, () -> DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void pendingRequestsAreIsolatedByOwnerTaskAndWorker() throws Exception {
        var fixture = new Fixture();
        var other = new Fixture();
        try (var controller = fixture.open(false); var separate = other.open(false)) {
            assertThrows(ApiException.class, () -> DeploymentApprovalPolicies.request(other.context, fixture.task.id(), "FULL_CONTROL", 0));
            DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "AUTOMATIC", 0);
            assertThrows(ApiException.class, () -> DeploymentApprovalPolicies.request(fixture.context, fixture.task.id(), "FULL_CONTROL", 0));
            assertTrue(separate.state().pendingMode().isEmpty());
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread = new Thread(() -> {
                try {
                    DeploymentApprovalPolicies.boundary(fixture.task, "MANUAL_REVIEW");
                } catch (Throwable rejected) {
                    failure.set(rejected);
                }
            });
            thread.start();
            thread.join(3000);
            assertFalse(thread.isAlive());
            assertInstanceOf(ApiException.class, failure.get());
            assertEquals(0, fixture.calls.get());
            assertEquals("CONFIRMED", new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.checkpoint.snapshot().payload()).path("phase").asText());
        }
    }

    static final class Fixture {
        final UUID id;

        final AtomicInteger begins = new AtomicInteger();

        final AtomicInteger calls = new AtomicInteger();

        final List<Long> hostRevisions = new ArrayList<>();

        final WorkflowGrant permission = () -> {
        };

        final TaskContext task;

        final ModContext context;

        MemoryCheckpoint checkpoint;

        Runnable manualQuestion = () -> {
            throw new AssertionError("No human question expected");
        };

        Function<CommandReviewMode, OperationResult<WorkflowPolicyState>> answer = mode -> success(new WorkflowPolicyState(mode, calls.get()));

        Fixture() {
            this(UUID.randomUUID(), null);
        }

        Fixture(UUID id, MemoryCheckpoint checkpoint) {
            this.id = id;
            this.checkpoint = checkpoint;
            task = proxy(TaskContext.class, (p, method, args) -> switch (method.getName()) {
                case "id" -> id;
                case "progress" -> null;
                default -> throw new AssertionError("No task side effect expected: " + method.getName());
            });
            var host = proxy(InteractionService.class, (p, method, args) -> {
                if (method.getName().equals("approve")) {
                    assertSame(task, args[1]);
                    manualQuestion.run();
                    return success(new ApprovalGrant(UUID.randomUUID()));
                }
                assertEquals("updateReviewedWorkflowPolicy", method.getName());
                assertSame(permission, args[0]);
                assertSame(task, args[3]);
                calls.incrementAndGet();
                hostRevisions.add((Long) args[1]);
                return answer.apply((CommandReviewMode) args[2]);
            });
            var tasks = proxy(TaskService.class, (p, method, args) -> {
                assertSame(task, args[0]);
                assertEquals(DeploymentApprovalPolicies.KEY, args[1]);
                if (method.getName().equals("findCheckpoint")) return Optional.ofNullable(this.checkpoint);
                assertEquals("beginCheckpoint", method.getName());
                begins.incrementAndGet();
                assertNull(this.checkpoint);
                this.checkpoint = new MemoryCheckpoint((String) args[2]);
                return this.checkpoint;
            });
            context = proxy(ModContext.class, (p, method, args) -> switch (method.getName()) {
                case "identity" -> new ModIdentity("deployment", "1.0.0", Optional.of("1"));
                case "tasks" -> tasks;
                case "interaction" -> host;
                case "db" -> throw new IllegalStateException("Preference database intentionally unavailable");
                default -> throw new AssertionError("No network or other capability expected: " + method.getName());
            });
        }

        Fixture restart() {
            return new Fixture(id, checkpoint);
        }

        DeploymentApprovalPolicies.Controller open(boolean recovery) throws ApiException {
            return DeploymentApprovalPolicies.open(context, task, permission,
                DeploymentApprovalPolicies.original(context, task, "MANUAL_REVIEW"), false, recovery);
        }
    }

    static final class MemoryCheckpoint implements TaskCheckpoint {
        private Snapshot state;

        boolean failConfirmation;

        MemoryCheckpoint(String payload) {
            state = new Snapshot(0, payload, false);
        }

        public synchronized Snapshot snapshot() {
            return state;
        }

        public synchronized Snapshot advance(Snapshot expected, String payload) throws ApiException {
            assertEquals(expected, state);
            assertFalse(state.released());
            if (failConfirmation && payload.contains("\"phase\":\"CONFIRMED\""))
                throw new ApiException(problem(ProblemCode.UNKNOWN));
            state = new Snapshot(state.revision() + 1, payload, false);
            return state;
        }

        public synchronized void close() {
            state = new Snapshot(state.revision() + 1, state.payload(), true);
        }
    }

    static Problem problem(ProblemCode code) {
        return new Problem(code, "FIXTURE_POLICY_OUTCOME", "Policy outcome", Map.of(), Optional.empty());
    }

    static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
