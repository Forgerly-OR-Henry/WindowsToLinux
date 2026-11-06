package gold.debug.windowstolinux.mod.deployment.service.environment;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;

class DeploymentEnvironmentHelperProbeTest {
    @Test
    void transportFailureAndUncertaintyNeverReviewOrSendAnInstaller() throws Exception {
        var notSent = new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT", "Not sent",
            Map.of("commandPhase", "CHANNEL_OPENING", "authorPayloadState", "NOT_SENT"), Optional.of(UUID.randomUUID()));
        for (var outcome : List.of(TaskResult.FAILED, TaskResult.UNKNOWN, TaskResult.CANCELLED)) {
            var probe = new OperationResult<>(outcome,
                Optional.of(new CommandResult(Optional.empty(), "", "", false)), Optional.of(notSent));
            var fixture = new Fixture(probe);
            var pending = assertThrows(DeploymentEnvironmentPendingException.class, fixture::prepare);
            assertEquals(TaskResult.UNKNOWN, pending.result().outcome(), "The existing environment operation remains retained");
            assertSame(notSent, pending.problem());
            assertSame(notSent, pending.result().problem().orElseThrow());
            assertEquals(0, fixture.installReviews.get());
            assertEquals(0, fixture.installs.get());
            assertEquals(1, fixture.probes.get());
            assertEquals(1, fixture.closes.get());
            assertEquals(fixture.state, fixture.journal.read());
            assertEquals(fixture.before, fixture.checkpoint.snapshot());
            assertFalse(fixture.checkpoint.snapshot().released());
        }
    }

    @Test
    void verifiedGuardRejectionStillSendsOneTrackedInstaller() throws Exception {
        var probe = new OperationResult<>(TaskResult.FAILED,
            Optional.of(new CommandResult(Optional.of(64), "", "", false)),
            Optional.of(new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO", "Observed guard rejection", Map.of(), Optional.empty())));
        var fixture = new Fixture(probe);
        fixture.prepare();
        assertEquals(1, fixture.installReviews.get());
        assertEquals(1, fixture.installs.get());
        assertEquals(1, fixture.probes.get());
        assertEquals(1, fixture.closes.get());
        assertEquals("HELPER_DISPATCHED", fixture.journal.read().stage());
    }

    private static final class Fixture {
        final AtomicInteger installReviews = new AtomicInteger(), installs = new AtomicInteger(),
            probes = new AtomicInteger(), closes = new AtomicInteger();
        final TaskContext task;
        final ModContext context;
        final DeploymentEnvironmentJournal journal;
        final DeploymentEnvironmentState state;
        final TaskCheckpoint checkpoint;
        final TaskCheckpoint.Snapshot before;
        final DeploymentEnvironmentCommandReview review;

        Fixture(OperationResult<CommandResult> probe) throws Exception {
            UUID taskId = UUID.fromString("06d502c9-d7c9-4e0e-967d-3f76a8206b7c");
            task = proxy(TaskContext.class, (method, args) -> switch (method) {
                case "id" -> taskId;
                case "cancelToken" -> (CancelToken) () -> false;
                default -> throw new AssertionError(method);
            });
            var target = new RemoteTarget(new ServerId("synthetic-environment"), taskId, "127.0.0.1", 22, "root",
                Optional.of("SHA256:" + "A".repeat(43)));
            var retained = new java.util.concurrent.atomic.AtomicReference<TaskCheckpoint>();
            TaskService tasks = proxy(TaskService.class, (method, args) -> {
                if (method.equals("findCheckpoint")) return Optional.empty();
                if (method.equals("beginCheckpoint")) {
                    TaskCheckpoint created = new TaskCheckpoint() {
                    Snapshot current = new Snapshot(0, (String) args[2], false);
                    public Snapshot snapshot() { return current; }
                    public Snapshot advance(Snapshot expected, String next) {
                        assertEquals(current, expected);
                        return current = new Snapshot(current.revision() + 1, next, false);
                    }
                    public void close() { throw new AssertionError("No checkpoint release expected"); }
                    };
                    retained.set(created);
                    return created;
                }
                throw new AssertionError(method);
            });
            journal = DeploymentEnvironmentJournal.begin(tasks, task, target);
            checkpoint = retained.get();
            var initial = journal.read();
            var observed = new DeploymentEnvironmentObservation("ubuntu-24.04", UUID.randomUUID(), "none", "apparmor:Y",
                "none", "NOT_APPLICABLE", "SUCCEEDED", "a".repeat(64), "none", "none", "none");
            state = initial.next("PACKAGES_INSTALLED", "a".repeat(64), observed);
            journal.advance(initial, state);
            before = checkpoint.snapshot();
            RemoteSession session = proxy(RemoteSession.class, (method, args) -> {
                if (method.equals("close")) { closes.incrementAndGet(); return null; }
                if (method.equals("execute")) {
                    var request = (CommandRequest) args[0];
                    if (request.stdin().length == 0) { probes.incrementAndGet(); return probe; }
                    installs.incrementAndGet();
                    assertEquals("HELPER_DISPATCHED", journal.read().stage());
                    return new OperationResult<>(TaskResult.SUCCEEDED,
                        Optional.of(new CommandResult(Optional.of(0), "", "", false)), Optional.empty());
                }
                throw new AssertionError(method);
            });
            LinuxService linux = proxy(LinuxService.class, (method, args) -> {
                assertEquals("connect", method);
                assertEquals(target, args[0]);
                assertSame(task, args[1]);
                return session;
            });
            InteractionService unused = proxy(InteractionService.class, (method, args) -> { throw new AssertionError(method); });
            context = proxy(ModContext.class, (method, args) -> switch (method) {
                case "linux" -> linux;
                case "interaction" -> unused;
                default -> throw new AssertionError(method);
            });
            review = (originalTarget, revision, request, originalTask) -> {
                assertEquals(target, originalTarget);
                assertSame(task, originalTask);
                if (request.stdin().length != 0) installReviews.incrementAndGet();
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())), Optional.empty());
            };
        }

        void prepare() throws Exception { new DeploymentEnvironmentHelper(context, review, task, journal).prepare(state); }
    }

    private interface Call { Object invoke(String method, Object[] args) throws Exception; }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> call.invoke(method.getName(), args)));
    }
}
