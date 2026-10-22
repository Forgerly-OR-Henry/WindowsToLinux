package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentReviewTest extends DeploymentRuntimeFixture {
    private static final String DENIAL_REASON = "Retain the existing candidate database and choose a different output path.\n"
        + "api_key=fixture-private-review-value\n" + "evidence ".repeat(100);

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL"})
    void catastrophicStaticDenialNeverRequestsAiHumanOrRemoteCapabilities(String policy) throws Exception {
        var external = new AtomicInteger();
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    if (java.util.Set.of("ai", "interaction", "linux").contains(method.getName())) {
                        external.incrementAndGet();
                        throw new AssertionError("Static denial must precede all review and remote capability calls");
                    }
                    return method.invoke(actual, arguments);
                });
            var handle = actual.tasks().submit(new TaskRequest("static_denial", "Static command denial", java.util.List.of(), false), task -> {
                var reviewer = new DeploymentAgentReview(context, new ServerId("server"), task, policy, true,
                    "a".repeat(64), () -> "a".repeat(64), new DeploymentAgentActionLog(actual.db()));
                for (String script : java.util.List.of("rm -rf /", "/usr/bin/rm -r -f -- /*",
                    "r'm' --recursive --force /etc", "rm -r -- /usr/bin", "rm -rf /tmp/..", "rm -rf //./",
                    "rm -rf /usr/../var/*", "cd / && rm -rf *", "cd /etc; rm -rf .", "cd /tmp; rm -rf ..",
                    "rm -rf /usr/local", "rm -rf /var/lib/*", "mkfs.ext4 /dev/sda", "dd if=/dev/zero of=/dev/sda",
                    ":(){ :|:& };:", "printf overwrite > /dev/nvme0n1")) {
                    var denied = assertThrows(SecurityException.class, () -> reviewer.command("service", "b".repeat(64), script));
                    assertEquals("DEPLOYMENT_COMMAND_STATIC_DENIED", denied.getMessage());
                }
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(0, external.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL"})
    void validIndependentDenialRequiresDifferentCommandWithoutHumanOverride(String policy) throws Exception {
        var approvals = new AtomicInteger();
        var effects = new AtomicInteger();
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(),
                new Class<?>[]{AiService.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("configurationRevision")) return "a".repeat(64);
                    assertEquals("call", method.getName());
                    var request = (AiRequest) arguments[0];
                    assertEquals(AiPurpose.APPROVAL, request.purpose());
                    assertEquals("a".repeat(64), arguments[1]);
                    assertEquals(1, approvals.incrementAndGet(), "A valid denial must never shop for approval");
                    return response("DENY", DENIAL_REASON);
                });
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("ai"))
                        return ai;
                    if (method.getName().equals("interaction") || method.getName().equals("linux")) {
                        effects.incrementAndGet();
                        throw new AssertionError("Denied action cannot dispatch a tool or ask for human override");
                    }
                    return method.invoke(actual, arguments);
                });
            var handle = actual.tasks().submit(
                new TaskRequest("independent_denial", "Independent denial", java.util.List.of(), false), task -> {
                    var conversation = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApprovalConversation(
                        context, task, new ServerId("server"), "fixture-fingerprint", "b".repeat(64), "a".repeat(64));
                    var reviewer = new DeploymentAgentReview(context, new ServerId("server"), task, policy, true,
                        "a".repeat(64), () -> "a".repeat(64), new DeploymentAgentActionLog(actual.db()), conversation);
                    var denied = assertThrows(DeploymentAgentAlternativeRequiredException.class,
                        () -> reviewer.command("service", "b".repeat(64), "rm -rf -- ./build"));
                    assertEquals("DEPLOYMENT_ALTERNATIVE_REQUIRED", denied.problem().eventCode());
                    assertTrue(denied.problem().message().startsWith("Retain the existing candidate database"));
                    assertTrue(denied.problem().message().contains("[credential content excluded]"));
                    assertFalse(denied.problem().message().contains("fixture-private-review-value"));
                    assertEquals(512, denied.problem().message().length());
                    assertFalse(denied.preparationUnsent(), "Action refusal must not invent an earlier preparation boundary");
                    var refusals = new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(actual)
                        .read(task.id()).records().stream().filter(record -> record.event().eventCode().equals("DEPLOYMENT_AGENT_REVIEW_DENIED")).toList();
                    assertEquals(1, refusals.size());
                    assertTrue(refusals.getFirst().event().message().contains("Retain the existing candidate database"));
                    assertTrue(refusals.getFirst().event().message().matches("type=COMMAND action=[0-9a-f]{64} .*"));
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(1, approvals.get());
            assertEquals(0, effects.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL"})
    void patchDenialRemainsHardFailureWithoutHumanOverride(String policy) throws Exception {
        checkReview(policy, () -> "a".repeat(64), (request, attempt) -> response("DENY", DENIAL_REASON),
            (reviewer, task, journal) -> {
                var patch = new DeploymentAgentPatch("app.py", "b".repeat(64), "c".repeat(64),
                    java.util.List.of(new DeploymentAgentPatch.Edit(1, java.util.List.of("old"), java.util.List.of("new"))));
                var denied = assertThrows(SecurityException.class, () -> reviewer.patch(patch));
                assertEquals("Independent deployment review denied action", denied.getMessage());
                assertTrue(journal.events(task).stream().noneMatch(event -> event.type().endsWith("APPROVED")));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMAND", "PATCH"})
    void validDenialWithChangedRevisionCannotBecomeRetryable(String type) throws Exception {
        var revision = new AtomicReference<>("a".repeat(64));
        checkReview("AUTOMATIC", revision::get, (request, attempt) -> {
            revision.set("d".repeat(64));
            return response("DENY", DENIAL_REASON);
        }, (reviewer, task, journal) -> {
            var denied = assertThrows(SecurityException.class, () -> {
                if (type.equals("COMMAND")) reviewer.command("service", "b".repeat(64), "printf artifact");
                else reviewer.patch(new DeploymentAgentPatch("app.py", "b".repeat(64), "c".repeat(64),
                    java.util.List.of(new DeploymentAgentPatch.Edit(1, java.util.List.of("old"), java.util.List.of("new")))));
            });
            assertEquals("Agent approval configuration changed", denied.getMessage());
            assertTrue(journal.events(task).stream().noneMatch(event -> event.type().endsWith("APPROVED")));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"MALFORMED_DENY", "UNAVAILABLE"})
    void invalidOrUnavailableReviewCannotBecomeRetryable(String failure) throws Exception {
        checkReview("AUTOMATIC", () -> "a".repeat(64), (request, attempt) -> failure.equals("UNAVAILABLE")
                ? new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(
                new gold.debug.windowstolinux.appapi.task.result.Problem(gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE,
                    "CONTROLLED_APPROVAL_UNAVAILABLE", "Independent reviewer unavailable", java.util.Map.of(), Optional.empty())))
                : nativeResponse("{\"decision\":\"DENY\",\"requiresUserConfirmation\":true}"),
            (reviewer, task, journal) -> {
                var rejected = assertThrows(Exception.class,
                    () -> reviewer.command("service", "b".repeat(64), "printf artifact"));
                assertFalse(rejected instanceof DeploymentAgentAlternativeRequiredException);
                assertTrue(failure.equals("UNAVAILABLE")
                    ? rejected instanceof gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException
                    : rejected instanceof java.io.IOException);
                assertTrue(journal.events(task).stream().noneMatch(event -> event.type().endsWith("APPROVED")));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL"})
    void anEarlierApprovalCannotReleaseALaterDeniedExactAction(String policy) throws Exception {
        checkReview(policy, () -> "a".repeat(64), (request, attempt) -> response(attempt == 1 ? "APPROVE" : "DENY", DENIAL_REASON),
            (reviewer, task, journal) -> {
                reviewer.command("service", "b".repeat(64), "printf artifact");
                assertThrows(DeploymentAgentAlternativeRequiredException.class,
                    () -> reviewer.command("service", "b".repeat(64), "printf artifact"));
                assertEquals(1, journal.events(task).stream().filter(event -> event.type().equals("COMMAND_APPROVED")).count());
            });
    }

    private void checkReview(String policy, java.util.function.Supplier<String> revision,
                             java.util.function.BiFunction<AiRequest, Integer, OperationResult<AiResponse>> answer,
                             ReviewAssertions assertions) throws Exception {
        var approvals = new AtomicInteger();
        var effects = new AtomicInteger();
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(),
                new Class<?>[]{AiService.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("configurationRevision")) return revision.get();
                    assertEquals("call", method.getName());
                    var request = (AiRequest) arguments[0];
                    assertEquals(AiPurpose.APPROVAL, request.purpose());
                    assertEquals("a".repeat(64), arguments[1]);
                    return answer.apply(request, approvals.incrementAndGet());
                });
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("ai")) return ai;
                    if (method.getName().equals("interaction") || method.getName().equals("linux")) {
                        effects.incrementAndGet();
                        throw new AssertionError("Review cannot dispatch a tool or ask for human override");
                    }
                    return method.invoke(actual, arguments);
                });
            var handle = actual.tasks().submit(new TaskRequest("review_boundary", "Review boundary", java.util.List.of(), false), task -> {
                var journal = new DeploymentAgentActionLog(actual.db());
                journal.create(task, new DeploymentAgentActionLog.Identity("server", "b".repeat(64), "a".repeat(64), policy, true));
                var reviewer = new DeploymentAgentReview(context, new ServerId("server"), task, policy, true,
                    "a".repeat(64), revision, journal);
                assertions.check(reviewer, task, journal);
                journal.state(task, "SUCCEEDED");
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertTrue(approvals.get() >= 1);
            assertEquals(0, effects.get());
        }
    }

    @FunctionalInterface
    private interface ReviewAssertions {
        void check(DeploymentAgentReview reviewer, gold.debug.windowstolinux.appapi.task.run.TaskContext task,
                   DeploymentAgentActionLog journal) throws Exception;
    }

    private static OperationResult<AiResponse> response(String decision, String reason) {
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
            .put("decision", decision).put("requiresUserConfirmation", true).put("reason", reason);
        return nativeResponse(result.toString());
    }

    private static OperationResult<AiResponse> nativeResponse(String arguments) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "independent", "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            java.util.List.of(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("review", "submit_result", arguments)), Optional.empty())),
            Optional.empty());
    }
}
