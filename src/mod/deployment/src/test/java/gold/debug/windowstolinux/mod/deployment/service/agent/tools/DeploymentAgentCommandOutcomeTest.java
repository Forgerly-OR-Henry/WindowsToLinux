package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.EnumSource;

/** Actual Agent channel and host grants with a controlled peer; an exit establishes no artifact or deployment. */
class DeploymentAgentCommandOutcomeTest extends DeploymentRuntimeFixture {
    enum Boundary {
        KNOWN_NONZERO, MAX_NONZERO, ZERO_SUCCESS, SUCCESS_NONZERO, UNKNOWN_WITH_EXIT, CANCELLED_WITH_EXIT,
        FOREIGN_EVENT, FOREIGN_CODE, NO_VALUE, NO_EXIT, TRUNCATED, ZERO_FAILED, NEGATIVE_EXIT, ABOVE_SHELL_EXIT,
        HELPER_REJECT_STDOUT, HELPER_REJECT_STDERR, REVIEW_DENIED, EXECUTE_FAILURE
    }

    private static final String REVISION = "a".repeat(64);

    private static final String SOURCE = "b".repeat(64);

    private static final String SCRIPT = "printf 'controlled failed attempt'";

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return (PlatformInteractionService.Prompts) Proxy.newProxyInstance(PlatformInteractionService.Prompts.class.getClassLoader(),
            new Class<?>[]{PlatformInteractionService.Prompts.class}, (proxy, method, args) -> {
                assertEquals("approve", method.getName());
                return true;
            });
    }

    @Test
    void latestActualRunSurvivesUnsentReviewAndRequiresSuccessAfterEachMutation() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("run-receipt-server");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture")), "fixture".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var failed = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = context.tasks().submit(new TaskRequest("agent-run-receipt-local", "Current output seal precondition", List.of(server.id()), true), task -> {
                try {
                    var next = new AtomicReference<>(success("Known complete command\n"));
                    var deny = new AtomicBoolean();
                    var sends = new AtomicInteger();
                    var reviews = new AtomicInteger();
                    var peer = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                        assertEquals("execute", method.getName(), "No real SSH or opportunistic state query");
                        sends.incrementAndGet();
                        var request = (CommandRequest) args[0];
                        if (request.script().contains("'agent-open'"))
                            return success("SOURCE_REVISION=" + SOURCE + "\n");
                        if (request.script().contains("'agent-patch'"))
                            return success("SOURCE_REVISION=" + "e".repeat(64) + "\n");
                        assertTrue(request.script().contains("'agent-run'"), "Local seal preconditions must never dispatch seal");
                        return next.get();
                    });
                    var review = (DeploymentCommandReview) Proxy.newProxyInstance(DeploymentCommandReview.class.getClassLoader(), new Class<?>[]{DeploymentCommandReview.class}, (proxy, method, args) -> {
                        assertEquals("approveCommand", method.getName());
                        assertSame(task, args[2]);
                        var intent = (ActionIntent) args[0];
                        var request = (CommandRequest) args[1];
                        assertEquals(RemoteActions.command(server, REVISION, request).exactAction(), intent.exactAction());
                        reviews.incrementAndGet();
                        if (deny.get())
                            throw new ApiException(problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED"));
                        return context.interaction().approve(intent, task);
                    });
                    var channel = new DeploymentAgentProjectChannel(peer, review, server, task, REVISION, () -> REVISION);
                    var workspace = new DeploymentRemoteWorkspace("demo-api", "c".repeat(64));
                    var candidate = new DeploymentAgentDeliveryPort.Prepared(workspace, channel.open(workspace, "ordinary"));
                    assertThrows(IllegalArgumentException.class, () -> channel.seal(candidate, SOURCE));
                    assertEquals(1, reviews.get());
                    assertEquals(1, sends.get());
                    assertTrue(channel.command(candidate, SOURCE, SCRIPT, Duration.ofSeconds(60)).succeeded());
                    var first = channel.latestRun(candidate).orElseThrow();
                    assertDoesNotThrow(() -> channel.requireSealable(candidate, SOURCE));
                    assertThrows(IllegalArgumentException.class, () -> channel.requireSealable(candidate, "f".repeat(64)));
                    next.set(new OperationResult<>(TaskResult.FAILED,
                        Optional.of(new CommandResult(Optional.of(2), "passing case\nexpected error case\n", "", false)),
                        Optional.of(problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO"))));
                    assertEquals(2, channel.command(candidate, SOURCE, SCRIPT + "\nexit 2", Duration.ofSeconds(60)).exitStatus());
                    var failedRun = channel.latestRun(candidate).orElseThrow();
                    assertTrue(failedRun.outputGeneration() > first.outputGeneration());
                    assertFalse(channel.outputCurrent(first));
                    assertTrue(channel.outputCurrent(failedRun), "Current metadata is not successful execution");
                    int before = sends.get();
                    assertThrows(IllegalArgumentException.class, () -> channel.seal(candidate, SOURCE));
                    deny.set(true);
                    assertThrows(DeploymentAgentAlternativeRequiredException.class,
                        () -> channel.command(candidate, SOURCE, "printf 'denied correction'", Duration.ofSeconds(60)));
                    assertEquals(before, sends.get());
                    assertEquals(failedRun, channel.latestRun(candidate).orElseThrow(), "NOT_SENT must retain the last known failure");
                    assertTrue(channel.outputCurrent(failedRun));
                    assertThrows(IllegalArgumentException.class, () -> channel.requireSealable(candidate, SOURCE));
                    deny.set(false);
                    next.set(success("Independently reviewed correction\n"));
                    assertTrue(channel.command(candidate, SOURCE, "printf 'fresh correction'", Duration.ofSeconds(60)).succeeded());
                    var corrected = channel.latestRun(candidate).orElseThrow();
                    assertDoesNotThrow(() -> channel.requireSealable(candidate, SOURCE));
                    var patch = new DeploymentAgentPatch("source.txt", "d".repeat(64), SOURCE,
                        List.of(new DeploymentAgentPatch.Edit(1, List.of("old"), List.of("new"))));
                    String patched = channel.patch(candidate, patch);
                    assertEquals("e".repeat(64), patched);
                    assertFalse(channel.outputCurrent(corrected));
                    assertThrows(IllegalArgumentException.class, () -> channel.requireSealable(candidate, SOURCE));
                    assertThrows(IllegalArgumentException.class, () -> channel.requireSealable(candidate, patched));
                    assertTrue(channel.command(candidate, patched, SCRIPT, Duration.ofSeconds(60)).succeeded());
                    assertDoesNotThrow(() -> channel.requireSealable(candidate, patched));
                    var beforeUnknown = channel.latestRun(candidate).orElseThrow();
                    next.set(outcome(Boundary.UNKNOWN_WITH_EXIT));
                    assertFalse(channel.command(candidate, patched, "printf 'uncertain command'", Duration.ofSeconds(60)).known());
                    assertEquals(beforeUnknown, channel.latestRun(candidate).orElseThrow(), "UNKNOWN never invents a new known receipt");
                    assertFalse(channel.outputCurrent(beforeUnknown));
                    assertThrows(IllegalArgumentException.class, () -> channel.requireSealable(candidate, patched));
                    assertEquals(sends.get() + 1, reviews.get(), "Only the denied request was reviewed without dispatch");
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError error) {
                    failed.set(error);
                    throw error;
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(40, TimeUnit.SECONDS);
            if (failed.get() != null) throw new AssertionError("Current run receipt contract", failed.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void actualSealRejectionDoesNotConsumeCommandOutputOrSkipFreshReview() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("seal-outcome-server");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture")), "fixture".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var failed = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = context.tasks().submit(new TaskRequest("agent-seal-outcome-local", "Bound artifact rejection", List.of(server.id()), true), task -> {
                try {
                    var seals = new AtomicInteger();
                    var commands = new AtomicInteger();
                    var reviews = new AtomicInteger();
                    var workspace = new DeploymentRemoteWorkspace("demo-api", "c".repeat(64));
                    String candidateId = workspace.candidateId();
                    String frame = "ARTIFACT_REJECT={\"task\":\"" + task.id() + "\",\"candidate\":\"" + candidateId
                        + "\",\"sourceRevision\":\"" + SOURCE + "\",\"code\":\"HARD_LINK\",\"path\":\"app\"}\n";
                    var refusal = new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.of(65), frame, "", false)),
                        Optional.of(new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO", "Controlled frozen artifact rejection",
                            Map.of("inputState", "COMPLETED", "channelClosed", "true", "exitObserved", "true"), Optional.empty())));
                    var peer = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                        assertEquals("execute", method.getName(), "No real SSH or unscheduled query");
                        var request = (CommandRequest) args[0];
                        if (request.script().contains("'agent-open'"))
                            return success("SOURCE_REVISION=" + SOURCE + "\n");
                        if (request.script().contains("'agent-seal'"))
                            return seals.incrementAndGet() == 1 ? refusal : success("DIGEST=" + "d".repeat(64) + "\n");
                        assertTrue(request.script().contains("'agent-run'"));
                        assertEquals(SCRIPT, new String(request.stdin(), StandardCharsets.UTF_8));
                        return commands.incrementAndGet() == 2 ? refusal : success("Controlled corrected output\n");
                    });
                    var review = (DeploymentCommandReview) Proxy.newProxyInstance(DeploymentCommandReview.class.getClassLoader(), new Class<?>[]{DeploymentCommandReview.class}, (proxy, method, args) -> {
                        assertEquals("approveCommand", method.getName());
                        var intent = (ActionIntent) args[0];
                        var request = (CommandRequest) args[1];
                        assertSame(task, args[2]);
                        assertEquals(RemoteActions.command(server, REVISION, request).exactAction(), intent.exactAction());
                        reviews.incrementAndGet();
                        return context.interaction().approve(intent, task);
                    });
                    var channel = new DeploymentAgentProjectChannel(peer, review, server, task, REVISION, () -> REVISION);
                    var candidate = new DeploymentAgentDeliveryPort.Prepared(workspace, channel.open(workspace, "ordinary"));
                    assertTrue(channel.command(candidate, SOURCE, SCRIPT, Duration.ofSeconds(60)).succeeded());
                    var error = assertThrows(DeploymentAgentArtifactRejectedException.class, () -> channel.seal(candidate, SOURCE));
                    assertTrue(error.matches(task.id(), candidateId, SOURCE));
                    var fakeMarker = channel.command(candidate, SOURCE, SCRIPT, Duration.ofSeconds(60));
                    assertTrue(fakeMarker.known());
                    assertFalse(fakeMarker.succeeded(), "A project command's identical marker is ordinary failed output, never a seal result");
                    assertThrows(IllegalArgumentException.class, () -> channel.seal(candidate, SOURCE));
                    assertTrue(channel.command(candidate, SOURCE, SCRIPT, Duration.ofSeconds(60)).succeeded());
                    assertEquals("d".repeat(64), channel.seal(candidate, SOURCE));
                    assertEquals(2, seals.get());
                    assertEquals(3, commands.get());
                    assertEquals(6, reviews.get(), "Open, three commands and two dispatched seals each have their own actual host review");
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError error) {
                    failed.set(error);
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(problem(ProblemCode.UNKNOWN, "CONTROLLED_ASSERTION")));
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(40, TimeUnit.SECONDS);
            assertNull(failed.get(), () -> String.valueOf(failed.get()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (handle.snapshot().state() != gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(TaskResult.SUCCEEDED, handle.snapshot().outcome().orElseThrow());
        }
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void actualCommandFeedbackRequiresExactCompleteSshExitAndPreservesEveryStop(Boundary boundary) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("command-outcome-server");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture")), "fixture".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var failed = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = context.tasks().submit(new TaskRequest("agent-command-outcome-local", "Exact observed Agent command exit", List.of(server.id()), true), task -> {
                try {
                    var commandSends = new AtomicInteger();
                    var commandReviews = new AtomicInteger();
                    var denial = new ApiException(problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED"));
                    var executionFailure = new ApiException(problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_TRANSPORT"));
                    var returned = outcome(boundary);
                    var peer = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
                        (proxy, method, args) -> {
                            assertEquals("execute", method.getName(), "No real SSH, candidate query or artifact operation");
                            var request = (CommandRequest) args[0];
                            if (request.script().contains("'agent-open'"))
                                return success("SOURCE_REVISION=" + SOURCE + "\n");
                            assertTrue(request.script().contains("'agent-run'"));
                            assertEquals(SCRIPT, new String(request.stdin(), StandardCharsets.UTF_8));
                            commandSends.incrementAndGet();
                            if (boundary == Boundary.EXECUTE_FAILURE) throw executionFailure;
                            return returned;
                        });
                    var review = (DeploymentCommandReview) Proxy.newProxyInstance(DeploymentCommandReview.class.getClassLoader(),
                        new Class<?>[]{DeploymentCommandReview.class}, (proxy, method, args) -> {
                            assertEquals("approveCommand", method.getName());
                            var intent = (ActionIntent) args[0];
                            var request = (CommandRequest) args[1];
                            assertSame(task, args[2]);
                            assertEquals(RemoteActions.command(server, REVISION, request).exactAction(), intent.exactAction());
                            if (request.script().contains("'agent-run'")) {
                                commandReviews.incrementAndGet();
                                if (boundary == Boundary.REVIEW_DENIED) throw denial;
                            }
                            return context.interaction().approve(intent, (TaskContext) args[2]);
                        });
                    var channel = new DeploymentAgentProjectChannel(peer, review, server, task, REVISION, () -> REVISION);
                    var workspace = new DeploymentRemoteWorkspace("demo-api", "c".repeat(64));
                    var candidate = new DeploymentAgentDeliveryPort.Prepared(workspace, channel.open(workspace, "ordinary"));
                    if (Set.of(Boundary.KNOWN_NONZERO, Boundary.MAX_NONZERO, Boundary.ZERO_SUCCESS, Boundary.UNKNOWN_WITH_EXIT).contains(boundary)) {
                        var feedback = channel.command(candidate, SOURCE, SCRIPT, Duration.ofSeconds(60));
                        if (boundary == Boundary.UNKNOWN_WITH_EXIT) {
                            assertFalse(feedback.known(), "UNKNOWN with an observed exit cannot become known command completion");
                            assertFalse(feedback.succeeded());
                            assertEquals(-1, feedback.exitStatus());
                        } else {
                            var observed = returned.value().orElseThrow();
                            assertTrue(feedback.known());
                            assertEquals(boundary == Boundary.ZERO_SUCCESS, feedback.succeeded());
                            assertEquals(observed.exitCode().orElseThrow().intValue(), feedback.exitStatus());
                            assertEquals(observed.stdout() + "\n" + observed.stderr(), feedback.evidenceOutput());
                        }
                    } else if (boundary == Boundary.REVIEW_DENIED) {
                        var refusal = assertThrows(DeploymentAgentAlternativeRequiredException.class,
                            () -> channel.command(candidate, SOURCE, SCRIPT, Duration.ofSeconds(60)));
                        assertSame(denial, refusal.getCause());
                    } else if (boundary == Boundary.EXECUTE_FAILURE) {
                        var error = assertThrows(ApiException.class, () -> channel.command(candidate, SOURCE, SCRIPT, Duration.ofSeconds(60)));
                        assertSame(executionFailure, error, "An execution exception cannot become an unsent review proof");
                    } else if (boundary == Boundary.SUCCESS_NONZERO) {
                        assertThrows(IllegalStateException.class, () -> channel.command(candidate, SOURCE, SCRIPT, Duration.ofSeconds(60)));
                    } else {
                        var error = assertThrows(DeploymentAiJson.CapabilityOutcomeException.class,
                            () -> channel.command(candidate, SOURCE, SCRIPT, Duration.ofSeconds(60)));
                        assertEquals(returned.outcome(), error.outcome());
                        assertEquals(returned.problem(), error.problem());
                    }
                    if (boundary == Boundary.ZERO_SUCCESS) {
                        assertDoesNotThrow(() -> channel.requireSealable(candidate, SOURCE));
                        assertEquals(0, channel.latestRun(candidate).orElseThrow().exitStatus());
                    } else {
                        assertThrows(IllegalArgumentException.class, () -> channel.requireSealable(candidate, SOURCE));
                        assertEquals(Set.of(Boundary.KNOWN_NONZERO, Boundary.MAX_NONZERO).contains(boundary), channel.latestRun(candidate).isPresent());
                    }
                    assertEquals(1, commandReviews.get(), "One actual complete request is reviewed without a retry");
                    assertEquals(boundary == Boundary.REVIEW_DENIED ? 0 : 1, commandSends.get());
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError failure) {
                    failed.set(failure);
                    throw failure;
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(30, TimeUnit.SECONDS);
            if (failed.get() != null)
                throw new AssertionError("Controlled Agent command outcome failed: " + boundary, failed.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }

    private static OperationResult<CommandResult> outcome(Boundary boundary) {
        TaskResult result = switch (boundary) {
            case ZERO_SUCCESS, SUCCESS_NONZERO -> TaskResult.SUCCEEDED;
            case UNKNOWN_WITH_EXIT -> TaskResult.UNKNOWN;
            case CANCELLED_WITH_EXIT -> TaskResult.CANCELLED;
            default -> TaskResult.FAILED;
        };
        int exit = switch (boundary) {
            case ZERO_SUCCESS, ZERO_FAILED -> 0;
            case MAX_NONZERO -> 255;
            case NEGATIVE_EXIT -> -1;
            case ABOVE_SHELL_EXIT -> 256;
            case HELPER_REJECT_STDOUT, HELPER_REJECT_STDERR -> 64;
            default -> 1;
        };
        String stdout = boundary == Boundary.HELPER_REJECT_STDOUT ? "MANAGED_HELPER_REJECT=workspace-freeze-failed\n"
            : "BUILD_UID=63416\nProject log mentions MANAGED_HELPER_REJECT= as data\n";
        String stderr = boundary == Boundary.HELPER_REJECT_STDERR ? "MANAGED_HELPER_REJECT=build-entry-identity\n"
            : "CMake Error: controlled nonzero output\n";
        var value = boundary == Boundary.NO_VALUE ? Optional.<CommandResult>empty()
            : Optional.of(new CommandResult(boundary == Boundary.NO_EXIT ? Optional.empty() : Optional.of(exit), stdout, stderr, boundary == Boundary.TRUNCATED));
        ProblemCode code = switch (boundary) {
            case FOREIGN_CODE -> ProblemCode.ACCESS_DENIED;
            case UNKNOWN_WITH_EXIT -> ProblemCode.UNKNOWN;
            case CANCELLED_WITH_EXIT -> ProblemCode.CANCELLED;
            default -> ProblemCode.IO_FAILURE;
        };
        String event = switch (boundary) {
            case FOREIGN_EVENT -> "SSH_COMMAND_NOT_SENT";
            case UNKNOWN_WITH_EXIT -> "SSH_COMMAND_EXIT_UNKNOWN";
            case CANCELLED_WITH_EXIT -> "SSH_COMMAND_CANCELLED";
            default -> "SSH_COMMAND_NONZERO";
        };
        var diagnostic = result == TaskResult.SUCCEEDED ? Optional.<Problem>empty() : Optional.of(problem(code, event));
        return new OperationResult<>(result, value, diagnostic);
    }

    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Controlled exact command outcome", Map.of(), Optional.empty());
    }

    private static OperationResult<CommandResult> success(String output) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), output, "", false)), Optional.empty());
    }
}
