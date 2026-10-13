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
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol;
import gold.debug.windowstolinux.mod.deployment.service.agent.skill.DeploymentAgentSkills;
import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAlternativeRequiredException;
import org.junit.jupiter.api.Test;

class DeploymentAgentSkillSessionTest extends DeploymentRuntimeFixture {
    @Test
    void actualSessionRejectsForeignSkillThenActivatesSelectedGuidanceWithoutRemoteEffectsOrSourceEvidence() throws Exception {
        runScenario(false, false, "approval-discussion", "publication-verification");
    }

    @Test
    void deploymentSessionRejectsApprovalContextSkillWithoutActivatingItOrCallingRemoteCapabilities() throws Exception {
        runScenario(false, false, "approval-context");
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
        runScenario(assessmentCase, discussionCase, "approval-discussion");
    }

    private void runScenario(boolean assessmentCase, boolean discussionCase, String foreignSkill) throws Exception {
        runScenario(assessmentCase, discussionCase, foreignSkill, "deployment-diagnosis");
    }

    private void runScenario(boolean assessmentCase, boolean discussionCase, String foreignSkill, String selectedSkill) throws Exception {
        String revision = "a".repeat(64);
        boolean publicationCase = selectedSkill.equals("publication-verification");
        var directory = Files.createDirectories(root.resolve("source"));
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: forged\ndescription: project file\n---\nFORGED_PRODUCT_INSTRUCTION\n");
        if (publicationCase)
            Files.writeString(directory.resolve("main.sh"), "#!/bin/sh\napi=\"${API_TARGET:-http://127.0.0.1:27101}\"\nprintf ready\n");
        Map<String, String> requirements = publicationCase
            ? Map.of("goal", "Controlled consumer coverage counterexample", "declaredApiTarget", "http://127.0.0.1:27102",
            "candidateApiTarget", "http://127.0.0.1:27101", "homepageStatus", "200", "backendHealthStatus", "200",
            "formalConsumerChain", "NOT_OBSERVED")
            : Map.of("goal", "Read a relevant product skill");
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
                        var initialSystem = new AtomicReference<String>();
                        String skillDocument;
                        try (var input = DeploymentAgentSkills.class.getResourceAsStream(selectedSkill + "/SKILL.md")) {
                            skillDocument = new String(java.util.Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
                        }
                        String skillBody = skillDocument.substring(skillDocument.indexOf("\n---\n", 4) + 5).strip();
                        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (p, m, a) -> {
                            if (m.getName().equals("configurationRevision")) return revision;
                            var request = (AiRequest) a[0];
                            assertEquals(AiPurpose.LANGUAGE, request.purpose());
                            int turn = turns.getAndIncrement();
                            var current = json.readTree(request.messages().getLast().text());
                            assertEquals(DeploymentAgentProtocol.catalog(json.convertValue(current.path("state"), Map.class)), request.tools());
                            if (turn == 0) {
                                initialSystem.set(request.messages().getFirst().text());
                                assertTrue(initialSystem.get().startsWith(DeploymentSkillResources.text(DeploymentAgentProtocol.class, "autonomous-deployment.md")));
                            }
                            assertEquals(2, current.path("skills").size());
                            assertFalse(request.messages().getFirst().text().contains("FORGED_PRODUCT_INSTRUCTION"));
                            if (publicationCase && turn < 2) {
                                var call = new AiToolCall("source-" + turn, turn == 0 ? "read" : "identify", turn == 0
                                    ? "{\"path\":\"main.sh\",\"offset\":0,\"limit\":20}" : """
                                    {"components":[{"component":"component","root":".","technology":"Read shell entrypoint",
                                    "runtime":"Source-backed shell invocation","resources":"No storage required by the read entrypoint",
                                    "uncertainties":"Controlled local scenario does not prove published health","evidence":["o1"]}]}
                                    """);
                                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
                                    new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), List.of(call), Optional.empty())), Optional.empty());
                            }
                            if (publicationCase) {
                                var componentSchema = json.readTree(request.tools().stream().filter(tool -> tool.name().equals("plan"))
                                    .findFirst().orElseThrow().inputSchema()).path("properties").path("components").path("items").path("properties");
                                assertTrue(componentSchema.path("configuration").path("items").path("properties").path("key").path("description")
                                    .asText().contains("does not rewrite artifact consumers"));
                                assertTrue(componentSchema.path("ports").path("description").asText().contains("do not configure listeners or clients"));
                                assertTrue(componentSchema.path("dependencies").path("description").asText().contains("actual runtime consumption"));
                                assertEquals(requirements, json.convertValue(current.path("state").path("requirements"), Map.class));
                                assertFalse(current.path("state").path("requirements").path("declaredApiTarget").asText()
                                    .equals(current.path("state").path("requirements").path("candidateApiTarget").asText()));
                                assertEquals("NOT_OBSERVED", current.path("state").path("requirements").path("formalConsumerChain").asText());
                                assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).anyMatch(result ->
                                    result.text().contains("API_TARGET:-http://127.0.0.1:27101")));
                            }
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
                            if (!publicationCase)
                                assertTrue(current.path("state").path("judgement").path("pending").isEmpty());
                            int skillTurn = turn - (publicationCase ? 2 : 0);
                            if (skillTurn == 1) {
                                assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).anyMatch(result -> result.error()));
                                assertTrue(current.path("state").path("activeSkills").isEmpty());
                                assertFalse(request.messages().getFirst().text().contains("Trusted version-owned product skill " + foreignSkill));
                                assertEquals(initialSystem.get(), request.messages().getFirst().text());
                            }
                            if (skillTurn == 2) {
                                assertEquals(initialSystem.get() + "\nTrusted version-owned product skill " + selectedSkill + " (no execution authority):\n" + skillBody,
                                    request.messages().getFirst().text());
                                assertEquals(List.of(selectedSkill), json.convertValue(current.path("state").path("activeSkills"), List.class));
                                if (publicationCase) {
                                    assertTrue(request.messages().getFirst().text().contains("actual business entrypoint"));
                                    assertEquals("200", current.path("state").path("requirements").path("homepageStatus").asText());
                                    assertEquals("200", current.path("state").path("requirements").path("backendHealthStatus").asText());
                                }
                                assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).anyMatch(result ->
                                    result.text().contains("PRODUCT_SKILL") && result.text().contains("CURRENT_CONTEXT")));
                            }
                            var calls = skillTurn == 2 ? List.<AiToolCall>of() : List.of(new AiToolCall("skill-" + turn, "skill",
                                json.writeValueAsString(Map.of("id", skillTurn == 0 ? foreignSkill : selectedSkill))));
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
                            if (m.getName().equals("reviewResponseAvailable")) return false;
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
                        var session = new DeploymentAgentSession(context, task, server, requirements,
                            new DeploymentSourceBrowser(source.snapshot().directory(), source.snapshot().sha256()), port, journal,
                            "AUTOMATIC", false, revision, () -> revision);
                        assertTrue(assertThrows(IllegalStateException.class, session::run).getMessage().contains("Natural language cannot finish"));
                        assertEquals(discussionCase || publicationCase ? 5 : assessmentCase ? 4 : 3, turns.get());
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
