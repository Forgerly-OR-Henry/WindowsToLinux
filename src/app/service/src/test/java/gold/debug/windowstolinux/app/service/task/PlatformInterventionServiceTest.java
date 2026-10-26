package gold.debug.windowstolinux.app.service.task;

import static gold.debug.windowstolinux.app.service.task.PlatformTaskRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.appapi.task.control.*;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.intervention.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformInterventionServiceTest {
    @TempDir
    Path root;

    private static final String OWNER = "fixture.deployment";

    private static final String VERSION = "1.0.0";

    private static final UiInterventionSpec SPEC = new UiInterventionSpec("核验部署", "原任务输入", "继续");

    @Test
    void authenticContextAndExactReleaseAreRequiredAndOptInIsExplicit() throws Exception {
        try (var fixture = new Fixture()) {
            assertSame(fixture.queue, fixture.runtime.interventions());
            var snapshot = fixture.task.snapshot();
            assertTrue(fixture.queue.manages(snapshot));
            var other = new TaskSnapshot(snapshot.id(), OWNER, "2.0.0", snapshot.type(), snapshot.state(),
                snapshot.outcome(), snapshot.progressText(), snapshot.startedAt(), snapshot.endedAt(), snapshot.blockers());
            assertFalse(fixture.queue.manages(other));
            var wrong = fixture.queue.scoped(OWNER, "2.0.0", () -> true);
            assertThrows(ApiException.class, () -> wrong.visible(fixture.task.id(), true));
            assertThrows(ApiException.class, () -> wrong.snapshot(fixture.task.id()));
            assertThrows(ApiException.class, () -> wrong.show(fixture.task.id()));
            assertThrows(ApiException.class, () -> wrong.request(fixture.context.get(), SPEC, fixture.form()));
            var copied = (TaskContext) java.lang.reflect.Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
                new Class<?>[]{TaskContext.class}, (proxy, method, args) -> method.getName().equals("id") ? fixture.task.id() : null);
            assertThrows(ApiException.class, () -> fixture.scoped.request(copied, SPEC, fixture.form()));
            assertThrows(ApiException.class, () -> fixture.queue.request(other, () -> false, SPEC, fixture.form()));
            assertEquals(0, fixture.queue.pending().size());
        }
    }

    @Test
    void originalObservationReturnsTheActualHandleAndRejectsForeignOrExpiredScopes() throws Exception {
        try (var fixture = new Fixture()) {
            var observed = fixture.scoped.observe(fixture.task.id());
            assertSame(fixture.task, observed);
            assertSame(fixture.task.snapshot(), observed.snapshot());
            assertEquals(fixture.task.id(), observed.id());
            var otherOwner = fixture.queue.scoped("fixture.other", VERSION, () -> true);
            var otherVersion = fixture.queue.scoped(OWNER, "2.0.0", () -> true);
            assertThrows(ApiException.class, () -> otherOwner.observe(fixture.task.id()));
            assertThrows(ApiException.class, () -> otherVersion.observe(fixture.task.id()));
            fixture.active.set(false);
            assertThrows(ApiException.class, () -> fixture.scoped.observe(fixture.task.id()));
            fixture.active.set(true);
            fixture.release.countDown();
            assertEquals("original", observed.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).value().orElseThrow());
            assertThrows(ApiException.class, () -> fixture.scoped.observe(fixture.task.id()));
        }
    }

    @Test
    void exactVisibilityAndSuccessfulOriginalNavigationControlOpening() throws Exception {
        try (var fixture = new Fixture()) {
            var request = fixture.request();
            assertFalse(fixture.queue.pending().getFirst().ready());
            assertThrows(ApiException.class, () -> edt(() -> fixture.queue.open(request.id())));
            var navigated = new AtomicInteger();
            var failNavigation = new AtomicBoolean(true);
            try (var control = fixture.tasks.registerController("fixture", new TaskController() {
                @Override
                public TaskControlSnapshot snapshot(UUID task) {
                    return new TaskControlSnapshot(TaskControlSnapshot.State.WAITING_USER, false, false, true, "");
                }

                @Override
                public void request(UUID task, TaskCommand command) throws ApiException {
                    assertEquals(fixture.task.id(), task);
                    if (failNavigation.get())
                        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
                            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "FIXTURE_NAVIGATION",
                            "Original navigation failed", Map.of(), Optional.empty()));
                    navigated.incrementAndGet();
                }
            })) {
                assertThrows(ApiException.class, () -> fixture.management.control(fixture.task.id(), TaskCommand.INTERVENE));
                assertFalse(fixture.queue.pending().getFirst().ready());
                failNavigation.set(false);
                fixture.management.control(fixture.task.id(), TaskCommand.INTERVENE);
                assertEquals(1, navigated.get());
                assertFalse(fixture.queue.pending().getFirst().ready());
                fixture.scoped.visible(fixture.task.id(), true);
                assertTrue(fixture.queue.pending().getFirst().ready());
                var window = edt(() -> fixture.queue.open(request.id()));
                assertTrue(window.active());
                fixture.scoped.visible(fixture.task.id(), false);
                assertFalse(window.active());
                flush();
                assertEquals(1, fixture.cleared.get());
                fixture.scoped.visible(fixture.task.id(), true);
                assertFalse(fixture.queue.pending().getFirst().ready());
                fixture.management.control(fixture.task.id(), TaskCommand.INTERVENE);
                assertTrue(fixture.queue.pending().getFirst().ready());
            }
        }
    }

    @Test
    void taskWideDeferralKeepsOriginalFuturesAndOneInterventionResumesAllQuestions() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.context.get().progress("正在检查", .42);
            fixture.scoped.visible(fixture.task.id(), true);
            var request = fixture.request();
            var other = fixture.request();
            assertEquals(2, fixture.scoped.snapshot(fixture.task.id()).pendingCount());
            assertEquals(Optional.of(.42), fixture.scoped.snapshot(fixture.task.id()).fraction());
            var entry = fixture.management.list().getFirst();
            assertEquals(TaskState.RUNNING, entry.snapshot().state());
            assertEquals(TaskViewSnapshot.Category.WAITING_USER, entry.view().category());
            assertEquals(2, entry.view().pendingInterventions());
            var first = edt(() -> fixture.queue.open(request.id()));
            edt(() -> {
                first.defer();
                return null;
            });
            assertEquals(1, fixture.cleared.get());
            assertArrayEquals(new char[6], fixture.secret.get());
            assertFalse(request.completion().toCompletableFuture().isDone());
            assertTrue(fixture.queue.pending().stream().noneMatch(PlatformInterventionService.Pending::ready));
            fixture.scoped.show(fixture.task.id());
            assertTrue(fixture.queue.pending().stream().allMatch(PlatformInterventionService.Pending::ready));
            var reopened = edt(() -> fixture.queue.open(request.id()));
            assertEquals(2, fixture.created.get());
            edt(() -> {
                reopened.submit();
                return null;
            });
            assertEquals("accepted", request.completion().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(2, fixture.cleared.get());
            assertEquals(1, fixture.queue.count(fixture.task.id()));
            assertTrue(fixture.queue.pending().getFirst().ready());
            assertFalse(other.completion().toCompletableFuture().isDone());
            assertEquals(TaskState.RUNNING, fixture.task.snapshot().state());
            var second = edt(() -> fixture.queue.open(other.id()));
            edt(() -> {
                second.submit();
                return null;
            });
            assertEquals("accepted", other.completion().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(2, fixture.accepted.get());
            assertEquals(0, fixture.management.list().getFirst().view().pendingInterventions());
            assertEquals(TaskViewSnapshot.Category.RUNNING, fixture.management.list().getFirst().view().category());
        }
    }

    @Test
    void deferralBetweenQuestionsKeepsNewQuestionsQueuedUntilExplicitOriginalIntervention() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.scoped.visible(fixture.task.id(), true);
            assertTrue(fixture.queue.taskVisible(fixture.task.id()));
            fixture.queue.deferTask(fixture.task.id());
            var nextQuestion = fixture.request();
            assertFalse(fixture.queue.pending().getFirst().ready());
            assertFalse(nextQuestion.completion().toCompletableFuture().isDone());
            fixture.scoped.visible(fixture.task.id(), false);
            assertFalse(fixture.queue.taskVisible(fixture.task.id()));
            fixture.scoped.show(fixture.task.id());
            assertFalse(fixture.queue.pending().getFirst().ready());
            fixture.scoped.visible(fixture.task.id(), true);
            assertTrue(fixture.queue.pending().getFirst().ready());
            assertTrue(fixture.queue.taskVisible(fixture.task.id()));
        }
    }

    @Test
    void hidingOneOriginalTaskDefersItsWholeQueueWithoutAffectingAnotherTask() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            var releaseOther = new CountDownLatch(1);
            var otherTask = fixture.tasks.submit(new TaskRequest("fixture", "其他原任务", List.of(), true), original -> {
                entered.countDown();
                assertTrue(releaseOther.await(5, TimeUnit.SECONDS));
                return success("other");
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                fixture.scoped.visible(fixture.task.id(), true);
                fixture.scoped.visible(otherTask.id(), true);
                var first = fixture.request();
                fixture.request();
                var other = fixture.queue.request(otherTask.snapshot(), () -> false, SPEC, fixture.form());
                var mounted = edt(() -> fixture.queue.open(first.id()));
                fixture.scoped.visible(fixture.task.id(), false);
                flush();
                assertFalse(mounted.active());
                assertFalse(fixture.queue.taskVisible(fixture.task.id()));
                assertTrue(fixture.queue.pending().stream().filter(pending -> pending.task().id().equals(fixture.task.id()))
                    .noneMatch(PlatformInterventionService.Pending::ready));
                assertTrue(fixture.queue.pending().stream().filter(pending -> pending.requestId().equals(other.id()))
                    .allMatch(PlatformInterventionService.Pending::ready));
                assertTrue(fixture.queue.taskVisible(otherTask.id()));
                other.cancel();
                flush();
            } finally {
                releaseOther.countDown();
                result(otherTask);
            }
        }
    }

    @Test
    void edtDisplayReadsDoNotWaitForAnotherTasksBlockedLedgerAdmission() throws Exception {
        var admissionEntered = new CountDownLatch(1);
        var admissionRelease = new CountDownLatch(1);
        var admissions = new AtomicInteger();
        try (var fixture = new Fixture((connection, owner, version, request) -> {
            if (admissions.incrementAndGet() == 2) {
                admissionEntered.countDown();
                try {
                    if (!admissionRelease.await(5, TimeUnit.SECONDS))
                        throw new java.sql.SQLException("Fixture timeout");
                } catch (InterruptedException interrupted) {
                    throw new java.sql.SQLException(interrupted);
                }
            }
            return claims(connection, owner, version, request);
        })) {
            fixture.scoped.visible(fixture.task.id(), true);
            fixture.request();
            var submitting = CompletableFuture.supplyAsync(() -> {
                try {
                    return fixture.tasks.submit(new TaskRequest("fixture", "阻塞准入", List.of(), true), task -> success("second"));
                } catch (ApiException failure) {
                    throw new CompletionException(failure);
                }
            });
            try {
                assertTrue(admissionEntered.await(3, TimeUnit.SECONDS));
                var observed = new CompletableFuture<Boolean>();
                SwingUtilities.invokeLater(() -> {
                    try {
                        fixture.scoped.visible(fixture.task.id(), true);
                        observed.complete(fixture.queue.pending().size() == 1 && fixture.queue.taskActive(fixture.task.id())
                            && fixture.queue.taskVisible(fixture.task.id()));
                    } catch (Throwable failure) {
                        observed.completeExceptionally(failure);
                    }
                });
                assertTrue(observed.get(2, TimeUnit.SECONDS));
            } finally {
                admissionRelease.countDown();
            }
            assertEquals("second", result(submitting.get(3, TimeUnit.SECONDS)).value().orElseThrow());
        } finally {
            admissionRelease.countDown();
        }
    }

    @Test
    void authorDecisionLeaseSurvivesDeferralAndIsReleasedBeforeAcceptedFutureCompletes() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.scoped.visible(fixture.task.id(), true);
            var request = fixture.request();
            var ledger = new gold.debug.windowstolinux.app.db.task.TaskLedger(fixture.database);
            assertEquals(1, ledger.queries().snapshot(fixture.task.id()).blockers().stream()
                .filter(value -> value.contains("/interaction/host-review-")).count());
            var mounted = edt(() -> fixture.queue.open(request.id()));
            edt(() -> {
                mounted.defer();
                return null;
            });
            assertEquals(1, fixture.cleared.get());
            assertFalse(request.completion().toCompletableFuture().isDone());
            assertEquals(1, ledger.queries().snapshot(fixture.task.id()).blockers().stream()
                .filter(value -> value.contains("/interaction/host-review-")).count());
            var cleanAtCompletion = request.completion().thenApply(value -> fixture.cleared.get() == 2
                && fixture.task.snapshot().blockers().stream().noneMatch(blocker -> blocker.contains("/interaction/host-review-")));
            fixture.scoped.show(fixture.task.id());
            var reopened = edt(() -> fixture.queue.open(request.id()));
            edt(() -> {
                reopened.submit();
                return null;
            });
            assertEquals("accepted", request.completion().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertTrue(cleanAtCompletion.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(TaskState.RUNNING, fixture.task.snapshot().state());
        }
    }

    @Test
    void authorReturnKeepsTaskFinishingUntilTheMountedDecisionIsWithdrawnAndReleased() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.scoped.visible(fixture.task.id(), true);
            var request = fixture.request();
            edt(() -> fixture.queue.open(request.id()));
            var edtEntered = new CountDownLatch(1);
            var edtRelease = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                edtEntered.countDown();
                try {
                    assertTrue(edtRelease.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
            });
            try {
                assertTrue(edtEntered.await(3, TimeUnit.SECONDS));
                fixture.release.countDown();
                state(fixture.task, TaskState.FINISHING);
                assertFalse(fixture.task.completion().toCompletableFuture().isDone());
                assertFalse(request.completion().toCompletableFuture().isDone());
                assertEquals(0, fixture.cleared.get());
            } finally {
                edtRelease.countDown();
            }
            assertEquals("original", result(fixture.task).value().orElseThrow());
            withdrawn(request);
            assertEquals(1, fixture.cleared.get());
            assertEquals(TaskState.ENDED, fixture.task.snapshot().state());
            assertTrue(fixture.task.snapshot().blockers().isEmpty());
            assertTrue(fixture.queue.pending().isEmpty());
        }
    }

    @Test
    void actualRecoveryCallbackRetainsItsExistingPresenterAndCannotEnterTheOrdinaryQueue() throws Exception {
        try (var fixture = new Fixture()) {
            var controller = new TaskController() {
                @Override
                public TaskControlSnapshot snapshot(UUID id) {
                    return new TaskControlSnapshot(TaskControlSnapshot.State.RUNNING, false, false, true, "");
                }

                @Override
                public void request(UUID id, TaskCommand command) {
                }
            };
            var observedRecovery = new AtomicBoolean();
            try (var ordinaryNavigation = fixture.tasks.registerController("fixture", controller);
                 var recoveryNavigation = fixture.tasks.registerController("recoverable", controller);
                 var recovery = fixture.runtime.bind(OWNER, VERSION, true).registerRecovery("recoverable", (previous, context) -> {
                     var actual = fixture.runtime.requireContext(context);
                     assertTrue(fixture.queue.manages(actual));
                     assertFalse(fixture.queue.canPresent(actual));
                     observedRecovery.set(true);
                     return success(null);
                 })) {
                assertTrue(fixture.queue.canPresent(fixture.task.snapshot()));
                var original = fixture.tasks.submit(new TaskRequest("recoverable", "原恢复任务", List.of(), true),
                    context -> PlatformTaskManagementTest.unknown());
                state(original, TaskState.UNKNOWN);
                assertFalse(fixture.queue.canPresent(original.snapshot()));
                assertEquals(TaskState.ENDED, fixture.runtime.reconcile(original.id()).toCompletableFuture().get(3, TimeUnit.SECONDS).state());
                assertTrue(observedRecovery.get());
                assertTrue(fixture.queue.pending().isEmpty());
            }
        }
    }

    @Test
    void releaseOptInDoesNotQueueShortTasksWithoutTheirExactOriginalInterventionController() throws Exception {
        try (var fixture = new Fixture()) {
            assertTrue(fixture.queue.manages(fixture.task.snapshot()));
            assertFalse(fixture.queue.canPresent(fixture.task.snapshot()));
            var allowed = new AtomicBoolean();
            var controller = new TaskController() {
                @Override
                public TaskControlSnapshot snapshot(UUID id) {
                    return new TaskControlSnapshot(TaskControlSnapshot.State.RUNNING, false, false, allowed.get(), "");
                }

                @Override
                public void request(UUID id, TaskCommand command) {
                }
            };
            try (var wrongType = fixture.tasks.registerController("other-type", controller);
                 var wrongVersion = fixture.runtime.bind(OWNER, "2.0.0", false).registerController("fixture", controller)) {
                allowed.set(true);
                assertFalse(fixture.queue.canPresent(fixture.task.snapshot()));
                try (var exact = fixture.tasks.registerController("fixture", controller)) {
                    allowed.set(false);
                    assertFalse(fixture.queue.canPresent(fixture.task.snapshot()));
                    allowed.set(true);
                    assertTrue(fixture.queue.canPresent(fixture.task.snapshot()));
                }
            }
        }
    }

    @Test
    void explicitCancellationAndOriginalTaskCancellationRetireOnlyTheirOwnRequests() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.scoped.visible(fixture.task.id(), true);
            var explicit = fixture.request();
            var original = fixture.request();
            explicit.cancel();
            flush();
            withdrawn(explicit);
            assertTrue(explicit.completion().toCompletableFuture().isCompletedExceptionally());
            assertFalse(original.completion().toCompletableFuture().isDone());
            var mounted = edt(() -> fixture.queue.open(original.id()));
            fixture.queue.cancelTask(fixture.task.id());
            flush();
            withdrawn(original);
            assertEquals(TaskState.CANCEL_REQUESTED, fixture.task.snapshot().state());
            assertTrue(original.completion().toCompletableFuture().isCompletedExceptionally());
            assertEquals(1, fixture.cleared.get());
            assertEquals(0, fixture.queue.count(fixture.task.id()));
            assertFalse(mounted.active());
            assertFalse(fixture.queue.taskActive(fixture.task.id()));
            assertThrows(ApiException.class, () -> edt(() -> {
                mounted.submit();
                return null;
            }));
            assertEquals(0, fixture.accepted.get());
        }
    }

    @Test
    void lockDefersAndClearsMountedSecretsWithoutResolvingOrAutomaticallyReopening() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.scoped.visible(fixture.task.id(), true);
            var request = fixture.request();
            var mounted = edt(() -> fixture.queue.open(request.id()));
            fixture.security.lock();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (fixture.security.privacyState().pending() && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(fixture.security.privacyState().pending());
            assertTrue(fixture.security.privacyState().failure().isEmpty());
            flush();
            assertFalse(mounted.active());
            assertEquals(1, fixture.cleared.get());
            assertArrayEquals(new char[6], fixture.secret.get());
            assertFalse(request.completion().toCompletableFuture().isDone());
            assertFalse(fixture.queue.pending().getFirst().ready());
            fixture.security.unlock("fixture-password".toCharArray());
            fixture.scoped.visible(fixture.task.id(), true);
            assertFalse(fixture.queue.pending().getFirst().ready());
            fixture.scoped.show(fixture.task.id());
            assertTrue(fixture.queue.pending().getFirst().ready());
            assertEquals(TaskState.RUNNING, fixture.task.snapshot().state());
        }
    }

    @Test
    void promptOnlyCancellationIsRecheckedWithoutCancellingTheOriginalTask() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.scoped.visible(fixture.task.id(), true);
            var promptCancelled = new AtomicBoolean();
            var request = fixture.queue.request(fixture.task.snapshot(), promptCancelled::get, SPEC, fixture.form());
            edt(() -> fixture.queue.open(request.id()));
            promptCancelled.set(true);
            fixture.queue.refresh();
            flush();
            assertTrue(request.completion().toCompletableFuture().isCompletedExceptionally());
            assertEquals(1, fixture.cleared.get());
            assertEquals(TaskState.RUNNING, fixture.task.snapshot().state());
            assertTrue(fixture.queue.taskActive(fixture.task.id()));
        }
    }

    @Test
    void hostSecurityLoopRebuildsAfterLockAndAcceptsTheFreshDeferredPromptExactlyOnce() throws Exception {
        try (var fixture = new Fixture(); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.scoped.visible(fixture.task.id(), true);
            var calls = new AtomicInteger();
            var first = new AtomicReference<UiInterventionSession<String>>();
            var prompted = new CountDownLatch(1);
            var result = worker.submit(() -> fixture.security.human(() -> {
                var request = fixture.queue.request(fixture.task.snapshot(), () -> false, SPEC, fixture.form());
                calls.incrementAndGet();
                first.compareAndSet(null, request);
                prompted.countDown();
                try {
                    return request.completion().toCompletableFuture().get();
                } catch (ExecutionException | CancellationException withdrawn) {
                    return null;
                }
            }, () -> false));
            try {
                assertTrue(prompted.await(3, TimeUnit.SECONDS));
                edt(() -> fixture.queue.open(first.get().id()));
                fixture.security.lock();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (fixture.security.privacyState().pending() && System.nanoTime() < deadline) Thread.sleep(5);
                assertFalse(fixture.security.privacyState().pending());
                assertEquals(1, fixture.cleared.get());
                assertTrue(first.get().completion().toCompletableFuture().isCompletedExceptionally());
                assertFalse(result.isDone());
                fixture.security.unlock("fixture-password".toCharArray());
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (calls.get() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(2, calls.get());
                assertEquals(1, fixture.queue.pending().size());
                fixture.scoped.visible(fixture.task.id(), true);
                assertFalse(fixture.queue.pending().getFirst().ready());
                fixture.scoped.show(fixture.task.id());
                var refreshed = fixture.queue.pending().getFirst();
                assertTrue(refreshed.ready());
                assertNotEquals(first.get().id(), refreshed.requestId());
                var mounted = edt(() -> fixture.queue.open(refreshed.requestId()));
                edt(() -> {
                    mounted.submit();
                    return null;
                });
                assertEquals("accepted", result.get(3, TimeUnit.SECONDS));
                assertEquals(1, fixture.accepted.get());
                assertEquals(2, calls.get());
                assertTrue(fixture.queue.pending().isEmpty());
                assertEquals(TaskState.RUNNING, fixture.task.snapshot().state());
            } finally {
                result.cancel(true);
            }
        }
    }

    @Test
    void taskCompletionInvalidatesMountedFormAndPublishesCompletionWithoutAnExtraPrompt() throws Exception {
        try (var fixture = new Fixture()) {
            var observed = new AtomicInteger();
            try (var observation = fixture.queue.observe(observed::incrementAndGet)) {
                fixture.scoped.visible(fixture.task.id(), true);
                var request = fixture.request();
                edt(() -> fixture.queue.open(request.id()));
                fixture.release.countDown();
                result(fixture.task);
                flush();
                withdrawn(request);
                assertEquals(1, fixture.cleared.get());
                assertTrue(request.completion().toCompletableFuture().isCompletedExceptionally());
                assertFalse(fixture.queue.taskActive(fixture.task.id()));
                assertTrue(fixture.queue.pending().isEmpty());
                assertTrue(observed.get() > 0);
            }
        }
    }

    @Test
    void activationInvalidationRejectsAcceptanceAndClearsTheFormBeforeFutureCancellation() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.scoped.visible(fixture.task.id(), true);
            var request = fixture.request();
            var mounted = edt(() -> fixture.queue.open(request.id()));
            var clearedAtCompletion = new AtomicInteger(-1);
            request.completion().whenComplete((value, failure) -> clearedAtCompletion.set(fixture.cleared.get()));
            fixture.active.set(false);
            assertThrows(ApiException.class, () -> edt(() -> {
                mounted.submit();
                return null;
            }));
            withdrawn(request);
            assertEquals(1, clearedAtCompletion.get());
            assertEquals(0, fixture.accepted.get());
            assertEquals(0, fixture.queue.count(fixture.task.id()));
            fixture.queue.revokeOwner(OWNER, VERSION);
            assertFalse(fixture.queue.manages(fixture.task.snapshot()));
            assertThrows(ApiException.class, () -> fixture.scoped.request(fixture.context.get(), SPEC, fixture.form()));
        }
    }

    private final class Fixture implements AutoCloseable {
        final PlatformDatabase database;

        final DesktopSecurityService security;

        final PlatformTaskRuntime runtime;

        final PlatformInterventionService queue;

        final gold.debug.windowstolinux.appapi.task.TaskService tasks;

        final UiInterventionService scoped;

        final PlatformTaskManagement management;

        final CountDownLatch release = new CountDownLatch(1);

        final AtomicReference<TaskContext> context = new AtomicReference<>();

        final AtomicBoolean active = new AtomicBoolean(true);

        final AtomicInteger created = new AtomicInteger(), cleared = new AtomicInteger(), accepted = new AtomicInteger();

        final AtomicReference<char[]> secret = new AtomicReference<>();

        final TaskHandle<String> task;

        Fixture() throws Exception {
            this(PlatformTaskRuntimeTest::claims);
        }

        Fixture(PlatformTaskRuntime.Admission admission) throws Exception {
            database = PlatformDatabase.open(root, PROTECTION);
            security = security(database);
            runtime = new PlatformTaskRuntime(database, admission, security);
            queue = runtime.interventions();
            tasks = runtime.bind(OWNER, VERSION, false);
            scoped = queue.scoped(OWNER, VERSION, active::get);
            management = new PlatformTaskManagement(runtime, security, id -> {
                throw new AssertionError();
            }, () -> {
            });
            var entered = new CountDownLatch(1);
            task = tasks.submit(new TaskRequest("fixture", "原部署任务", List.of(), true), original -> {
                context.set(original);
                entered.countDown();
                if (!release.await(15, TimeUnit.SECONDS))
                    throw new AssertionError("Original callback was not released");
                return success("original");
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        }

        UiInterventionSession<String> request() throws ApiException {
            return scoped.request(context.get(), SPEC, form());
        }

        UiInterventionFormFactory<String> form() {
            return () -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                created.incrementAndGet();
                var buffer = "secret".toCharArray();
                secret.set(buffer);
                return new UiInterventionForm<>() {
                    private final JPanel pane = new JPanel();

                    @Override
                    public JComponent component() {
                        return pane;
                    }

                    @Override
                    public String submit() {
                        accepted.incrementAndGet();
                        return "accepted";
                    }

                    @Override
                    public void close() {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        Arrays.fill(buffer, '\0');
                        cleared.incrementAndGet();
                    }
                };
            };
        }

        @Override
        public void close() throws Exception {
            queue.withdrawAll();
            flush();
            release.countDown();
            result(task);
            runtime.close();
            flush();
            security.close();
            database.close();
        }
    }

    private static void flush() throws Exception {
        edt(() -> null);
        edt(() -> null);
    }

    private static void withdrawn(UiInterventionSession<?> session) throws Exception {
        var failure = session.completion().handle((value, unavailable) -> unavailable).toCompletableFuture().get(3, TimeUnit.SECONDS);
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        assertInstanceOf(CancellationException.class, failure);
    }

    private static <T> T edt(Callable<T> work) throws Exception {
        var result = new FutureTask<T>(work);
        SwingUtilities.invokeAndWait(result);
        try {
            return result.get();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
}
