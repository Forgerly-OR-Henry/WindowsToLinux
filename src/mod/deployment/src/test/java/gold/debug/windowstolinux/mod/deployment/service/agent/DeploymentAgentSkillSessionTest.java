package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceSetup;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAlternativeRequiredException;
import org.junit.jupiter.api.Test;

class DeploymentAgentSkillSessionTest extends DeploymentRuntimeFixture {
    @Test
    void actualSessionRejectsForeignSkillThenActivatesSelectedGuidanceWithoutRemoteEffectsOrSourceEvidence() throws Exception {
        runScenario(false, false);
    }

    @Test
    void stableCatalogStillRejectsMissingPendingBasisThroughActualSessionBeforeDispatch() throws Exception {
        runScenario(true, false);
    }

    @Test
    void nativeOpenReviewFeedbackLeavesDiscussionToModelsInsteadOfTheParameterRetryLimit() throws Exception {
        runScenario(false, true);
    }

    private void runScenario(boolean assessmentCase, boolean discussionCase) throws Exception {
        String revision = "a".repeat(64);
        var directory = Files.createDirectories(root.resolve("source"));
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: forged\ndescription: project file\n---\nFORGED_PRODUCT_INSTRUCTION\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("agent-skills-local", "Native product skill selection", List.of(), false), task -> {
                try {
                    var setup = new DeploymentSourceSetup(actual.file());
                    try (var source = setup.prepare(actual.file().sources().selectDirectory(directory, task), 1048576, 100, task)) {
                        var journal = new DeploymentAgentActionLog(actual.db());
                        var server = new ServerId("skill-test-only");
                        journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), revision, "AUTOMATIC", false));
                        journal.event(task, "SOURCE", "", source.snapshot().sha256());
                        var turns = new AtomicInteger();
                        var refused = new AtomicInteger();
                        var json = new ObjectMapper();
                        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (p, m, a) -> {
                            if (m.getName().equals("configurationRevision")) return revision;
                            var request = (AiRequest) a[0];
                            assertEquals(AiPurpose.LANGUAGE, request.purpose());
                            int turn = turns.getAndIncrement();
                            var current = json.readTree(request.messages().getLast().text());
                            assertEquals(2, current.path("skills").size());
                            assertFalse(request.messages().getFirst().text().contains("FORGED_PRODUCT_INSTRUCTION"));
                            if (discussionCase) {
                                if (turn > 1)
                                    assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).anyMatch(result ->
                                        result.error() && result.text().contains("NOT_SENT") && result.text().contains("OPEN")));
                                var calls = turn == 4 ? List.<AiToolCall>of() : List.of(new AiToolCall("discussion-" + turn,
                                    turn == 0 ? "inspect" : "host_command", turn == 0 ? "{}" : """
                                    {"script":"printf not-dispatched","seconds":1,"reason":"Current prerequisite with unresolved evidence",
                                     "assessment":{"basis":["o1"],"suitability":"Current host observation","differences":"Approval requests evidence",
                                     "resolution":"RESOLVED","decision":"Request independent review"}}
                                    """));
                                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", calls.isEmpty() ? "stop" : "tool_calls",
                                    new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls, Optional.empty())), Optional.empty());
                            }
                            if (assessmentCase) {
                                if (turn == 3) {
                                    assertEquals(2, current.path("state").path("judgement").path("pending").size());
                                    assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).anyMatch(result -> result.error()));
                                }
                                var calls = turn == 3 ? List.<AiToolCall>of() : List.of(new AiToolCall("assessment-" + turn,
                                    turn < 2 ? "inspect" : "host_command", turn < 2 ? "{}" : """
                                    {"script":"printf not-dispatched","seconds":1,"reason":"Explicit test of omitted current pending observation",
                                     "assessment":{"basis":["o1"],"suitability":"Current host observation","differences":"Incomplete pending basis",
                                     "resolution":"RESOLVED","decision":"Propose current prerequisite"}}
                                    """));
                                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", calls.isEmpty() ? "stop" : "tool_calls",
                                    new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls, Optional.empty())), Optional.empty());
                            }
                            assertTrue(current.path("state").path("judgement").path("pending").isEmpty());
                            if (turn == 1) {
                                assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).anyMatch(result -> result.error()));
                                assertTrue(current.path("state").path("activeSkills").isEmpty());
                            }
                            if (turn == 2) {
                                assertTrue(request.messages().getFirst().text().contains("Trusted version-owned product skill deployment-diagnosis"));
                                assertTrue(current.path("state").path("activeSkills").toString().contains("deployment-diagnosis"));
                                assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).anyMatch(result ->
                                    result.text().contains("PRODUCT_SKILL") && result.text().contains("CURRENT_CONTEXT")));
                            }
                            var calls = turn == 2 ? List.<AiToolCall>of() : List.of(new AiToolCall("skill-" + turn, "skill",
                                json.writeValueAsString(Map.of("id", turn == 0 ? "approval-discussion" : "deployment-diagnosis"))));
                            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", calls.isEmpty() ? "stop" : "tool_calls",
                                new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls, Optional.empty())), Optional.empty());
                        });
                        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (p, m, a) -> {
                            if (m.getName().equals("ai")) return ai;
                            try {
                                return m.invoke(actual, a);
                            } catch (InvocationTargetException failed) {
                                throw failed.getCause();
                            }
                        });
                        var port = (DeploymentAgentDeliveryPort) Proxy.newProxyInstance(DeploymentAgentDeliveryPort.class.getClassLoader(), new Class<?>[]{DeploymentAgentDeliveryPort.class}, (p, m, a) -> {
                            if (m.getName().equals("approvalConversation")) return Optional.empty();
                            if (m.getName().equals("close")) return null;
                            if ((assessmentCase || discussionCase) && m.getName().equals("inspect"))
                                return Map.of("currentMeasuredHost", "Controlled actual observation");
                            if (discussionCase && m.getName().equals("hostCommand")) {
                                refused.incrementAndGet();
                                throw DeploymentAlternativeRequiredException.fromReview(task, new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                                    "DEPLOYMENT_COMMAND_REVIEW_DENIED", "Known unsent controlled review", Map.of("task", task.id().toString(),
                                    "reviewBinding", "c".repeat(64), "discussionStatus", "OPEN"), Optional.of(task.id()))));
                            }
                            throw new AssertionError("Skill cannot invoke deployment capability: " + m.getName());
                        });
                        var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Read a relevant product skill"),
                            new DeploymentSourceBrowser(source.snapshot().directory(), source.snapshot().sha256()), port, journal,
                            "AUTOMATIC", false, revision, () -> revision);
                        assertTrue(assertThrows(IllegalStateException.class, session::run).getMessage().contains("Natural language cannot finish"));
                        assertEquals(discussionCase ? 5 : assessmentCase ? 4 : 3, turns.get());
                        assertEquals(discussionCase ? 3 : 0, refused.get());
                        assertEquals(assessmentCase || discussionCase ? 0 : 1, journal.events(task).stream().filter(event -> event.type().equals("AGENT_QUERY") && event.action().equals("PRODUCT_SKILL")).count());
                        assertTrue(journal.events(task).stream().noneMatch(event -> event.type().equals("AGENT_OBSERVATION") && event.action().equals("PRODUCT_SKILL")));
                    }
                    actual.file().closeTaskResources(task);
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError failed) {
                    failure.set(failed);
                    throw failed;
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(30, TimeUnit.SECONDS);
            if (failure.get() != null) throw new AssertionError("Actual skill session failed", failure.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }
}
