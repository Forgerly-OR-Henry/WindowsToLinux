package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeploymentAgentRuntimeValidationSessionTest extends DeploymentRuntimeFixture {
    enum Scenario {WRONG_REPLAN, COMMAND, BUILD, REPLAN, PATCH, SOURCE, DENY, UNKNOWN, NOT_APPLICABLE, LONG_LITERAL, SEAL_PREFLIGHT, FAILED_RUN_REPLAN}

    private static final String REVISION = "a".repeat(64);

    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(value = Scenario.class, names = "DENY", mode = EnumSource.Mode.EXCLUDE)
    void currentExactInvocationControlsSealAndPreservesOriginalStopContracts(Scenario scenario) throws Exception {
        runScenario(scenario);
    }

    @Test
    void deniedExactInvocationCannotBeReapprovedOrSeal() throws Exception {
        runScenario(Scenario.DENY);
    }

    private void runScenario(Scenario scenario) throws Exception {
        var directory = Files.createDirectories(root.resolve("source"));
        Files.writeString(directory.resolve("source.txt"), "original source facts\n");
        var revisedDirectory = Files.createDirectories(root.resolve("revised"));
        Files.writeString(revisedDirectory.resolve("source.txt"), "revised source facts\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("agent-runtime-validation-local", "Exact candidate invocation", List.of(), false), task -> {
                try {
                    var setup = new DeploymentSourceSetup(actual.file());
                    try (var material = setup.prepare(actual.file().sources().selectDirectory(directory, task), 1048576, 100, task);
                         var revised = setup.prepare(actual.file().sources().selectDirectory(revisedDirectory, task), 1048576, 100, task)) {
                        var originalBrowser = new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256());
                        var revisedBrowser = new DeploymentSourceBrowser(revised.snapshot().directory(), revised.snapshot().sha256());
                        var current = new AtomicReference<>(originalBrowser);
                        var journal = new DeploymentAgentActionLog(actual.db());
                        var server = new ServerId("runtime-validation-only");
                        journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), REVISION, "MANUAL_REVIEW", true));
                        var calls = new AtomicInteger();
                        var commands = new AtomicInteger();
                        var seals = new AtomicInteger();
                        var deliveries = new AtomicInteger();
                        var lastRun = new AtomicReference<DeploymentAgentDeliveryPort.CommandFeedback>();
                        var tail = switch (scenario) {
                            case WRONG_REPLAN ->
                                List.of("command", "seal", "validate_runtime", "seal", "plan", "validate_runtime", "seal", "deliver");
                            case COMMAND -> List.of("validate_runtime", "command", "seal");
                            case BUILD -> List.of("validate_runtime", "build", "seal");
                            case REPLAN -> List.of("validate_runtime", "plan", "seal");
                            case PATCH -> List.of("validate_runtime", "patch", "seal");
                            case SOURCE -> List.of("validate_runtime", "plan_state", "seal");
                            case DENY -> List.of("validate_runtime", "seal", "validate_runtime");
                            case UNKNOWN -> List.of("validate_runtime");
                            case NOT_APPLICABLE, LONG_LITERAL ->
                                List.of("validate_runtime", "command", "seal", "deliver");
                            case SEAL_PREFLIGHT -> List.of("validate_runtime", "seal");
                            case FAILED_RUN_REPLAN -> List.of("command", "plan", "seal");
                        };
                        var scriptCalls = new ArrayList<String>();
                        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                            if (method.getName().equals("configurationRevision")) return REVISION;
                            assertEquals("call", method.getName());
                            var request = (AiRequest) args[0];
                            if (request.purpose() == AiPurpose.APPROVAL) {
                                assertEquals(Scenario.PATCH, scenario);
                                return reply(List.of(new AiToolCall("runtime-patch-review", "submit_result",
                                    "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":true,\"reason\":\"Controlled exact source patch\"}")));
                            }
                            assertEquals(AiPurpose.LANGUAGE, request.purpose());
                            var state = json.readTree(request.messages().getLast().text()).path("state");
                            int turn = calls.getAndIncrement();
                            if (turn >= 3 + tail.size()) {
                                assertEquals(0, seals.get());
                                assertEquals(0, deliveries.get());
                                assertEquals(scenario == Scenario.SEAL_PREFLIGHT,
                                    state.path("components").get(0).path("capabilities").path("runtimeValidation").path("current").asBoolean());
                                if (scenario == Scenario.SEAL_PREFLIGHT || scenario == Scenario.FAILED_RUN_REPLAN) {
                                    assertTrue(state.path("recentRejections").toString().contains("SEAL_PRECONDITION"));
                                    assertTrue(journal.events(task).stream().noneMatch(event -> event.type().equals("SEAL_EXECUTING")));
                                    assertNotEquals("UNKNOWN", journal.find(task).orElseThrow().state());
                                }
                                assertFalse(state.path("recentRejections").isEmpty());
                                return reply(List.of());
                            }
                            String tool = turn < 3 ? List.of("read", "identify", "plan").get(turn) : tail.get(turn - 3);
                            if (tool.equals("seal") && scenario == Scenario.WRONG_REPLAN && turn == 9) {
                                var validation = state.path("components").get(0).path("capabilities").path("runtimeValidation");
                                assertTrue(validation.path("current").asBoolean());
                                assertEquals("NOT_VERIFIED", validation.path("publishedHealth").asText());
                                assertTrue(state.path("judgement").path("pending").toString().contains(validation.path("observation").asText()),
                                    "The exact original validation observation must require the next critical assessment");
                            }
                            if (tool.equals("plan_state")) current.set(revisedBrowser);
                            ObjectNode parameters = switch (tool) {
                                case "read" ->
                                    json.createObjectNode().put("path", "source.txt").put("offset", 0).put("limit", 100);
                                case "identify" -> (ObjectNode) json.readTree("""
                                    {"components":[{"component":"cli","root":".","technology":"Actual source READ","runtime":"Explicit literal verification","resources":"Explicit empty declaration","uncertainties":"Runtime remains to be tested","evidence":["o1"]}]}
                                    """);
                                case "plan" -> {
                                    var declaration = DeploymentAgentRuntimeValidationTest.declaration("tool",
                                        scenario == Scenario.LONG_LITERAL ? List.of("'".repeat(4000), "'".repeat(4000))
                                            : List.of("--type", scenario == Scenario.WRONG_REPLAN && turn == 2 ? "text" : "event"), "", "records=5");
                                    if (scenario == Scenario.NOT_APPLICABLE || scenario == Scenario.FAILED_RUN_REPLAN) {
                                        var component = (ObjectNode) declaration.path("components").get(0);
                                        component.put("mode", "DAEMON").putObject("health").put("type", "PROCESS").put("timeout", 30).put("stability", 1);
                                        ((ObjectNode) declaration.path("applicationHealth")).set("health", component.path("health").deepCopy());
                                    }
                                    yield declaration;
                                }
                                case "command" ->
                                    json.createObjectNode().put("component", "cli").put("script", "printf records=5").put("seconds", 30);
                                case "build" -> json.createObjectNode().put("component", "cli").put("seconds", 60);
                                case "patch" -> {
                                    var patch = json.createObjectNode().put("component", "cli").put("path", "source.txt")
                                        .put("beforeDigest", "c".repeat(64)).put("sourceRevision", originalBrowser.revision());
                                    var edit = patch.putArray("edits").addObject().put("line", 1);
                                    edit.putArray("removed").add("original source facts");
                                    edit.putArray("added").add("revised source facts");
                                    yield patch;
                                }
                                case "plan_state" ->
                                    json.createObjectNode().put("component", "cli").put("offset", 0).put("limit", 6000);
                                case "seal", "validate_runtime" -> json.createObjectNode().put("component", "cli");
                                case "deliver" -> json.createObjectNode();
                                default -> throw new AssertionError(tool);
                            };
                            if (tool.equals("seal") && (scenario == Scenario.NOT_APPLICABLE || scenario == Scenario.LONG_LITERAL)) {
                                var validation = state.path("components").get(0).path("capabilities").path("runtimeValidation");
                                assertFalse(validation.path("current").asBoolean());
                                assertTrue(validation.path("observation").asText().isEmpty());
                            }
                            if (DeploymentAgentProtocol.critical(tool)) {
                                var assessment = parameters.putObject("assessment");
                                var basis = assessment.putArray("basis");
                                var pending = state.path("judgement").path("pending");
                                if (pending.isEmpty()) basis.add("o1");
                                else pending.forEach(id -> basis.add(id.asText()));
                                assessment.put("suitability", "Assess current original observations").put("differences", "Candidate invocation is not published health")
                                    .put("resolution", "RESOLVED").put("decision", "One fresh exact action");
                            }
                            return reply(List.of(new AiToolCall("runtime_" + turn, tool, parameters.toString())));
                        });
                        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                            if (method.getName().equals("ai")) return ai;
                            try {
                                return method.invoke(actual, args);
                            } catch (InvocationTargetException failed) {
                                throw failed.getCause();
                            }
                        });
                        var port = new DeploymentAgentDeliveryPort() {
                            public DeploymentSourceBrowser source(String component) {
                                return current.get();
                            }

                            public Map<String, String> inspect() {
                                throw new AssertionError();
                            }

                            public Prepared prepare(String app, DeploymentAgentPlan.Part part) {
                                return new Prepared(new DeploymentRemoteWorkspace(app + "-" + part.id(), "c".repeat(64)), current.get().revision());
                            }

                            public void validateCapability(DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part) {
                                assertEquals("build", request.name());
                            }

                            public String capability(DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part, Prepared candidate, String revision, String binding, String app) {
                                assertEquals("build", request.name());
                                lastRun.set(new CommandFeedback(true, true, 0, "Controlled complete build result"));
                                return "Actual controlled build changed candidate output";
                            }

                            public CommandFeedback command(TaskContext original, Prepared candidate, String revision, String script, Duration timeout) throws Exception {
                                assertSame(task, original);
                                commands.incrementAndGet();
                                scriptCalls.add(script);
                                if (scenario == Scenario.FAILED_RUN_REPLAN) {
                                    var result = new CommandFeedback(true, false, 2, "One passing case followed by a documented nonzero error case");
                                    lastRun.set(result);
                                    return result;
                                }
                                if (scenario == Scenario.DENY)
                                    throw new DeploymentAlternativeRequiredException(task, "Controlled valid DENY; zero transport");
                                if (scenario == Scenario.UNKNOWN)
                                    return new CommandFeedback(false, false, -1, "Unknown actual result");
                                CommandFeedback result;
                                if (script.equals("printf records=5"))
                                    result = new CommandFeedback(true, true, 0, "records=5");
                                else {
                                    assertEquals(Duration.ofSeconds(30), timeout);
                                    if (script.contains("'--type' 'text'"))
                                        result = new CommandFeedback(true, false, 2, "invalid selected type");
                                    else {
                                        assertTrue(script.contains("'--type' 'event'"));
                                        assertTrue(script.contains("case \"$agent_runtime_observed\" in *'records=5'*)"));
                                        result = new CommandFeedback(true, true, 0, "records=5");
                                    }
                                }
                                lastRun.set(result);
                                return result;
                            }

                            public String read(TaskContext original, Prepared candidate, String revision, String path, int offset, int limit) {
                                throw new AssertionError();
                            }

                            public void validatePatch(TaskContext original, Prepared candidate, DeploymentAgentPatch patch) {
                            }

                            public String patch(TaskContext original, Prepared candidate, String revision, DeploymentAgentPatch patch) {
                                current.set(revisedBrowser);
                                return revisedBrowser.revision();
                            }

                            public void validateSeal(TaskContext original, Prepared candidate, String revision) {
                                assertSame(task, original);
                                if (scenario == Scenario.FAILED_RUN_REPLAN) {
                                    assertEquals(2, lastRun.get().exitStatus(), "Replan cannot erase the latest actual failed run");
                                    throw new IllegalArgumentException("SEAL_PRECONDITION: lastKnownExit=2; no seal was sent");
                                }
                                if (scenario == Scenario.SEAL_PREFLIGHT)
                                    throw new IllegalArgumentException("SEAL_PRECONDITION: current successful-run evidence unavailable; no seal was sent");
                                assertNotNull(lastRun.get());
                                assertTrue(lastRun.get().known() && lastRun.get().succeeded() && lastRun.get().exitStatus() == 0);
                            }

                            public String seal(TaskContext original, Prepared candidate, String revision) {
                                seals.incrementAndGet();
                                return "d".repeat(64);
                            }

                            public OperationResult<Published> deliver(TaskContext original, DeploymentAgentPlan plan, Map<String, Prepared> candidates, Map<String, String> revisions, Map<String, String> artifacts, int remaining) {
                                deliveries.incrementAndGet();
                                assertEquals(1, seals.get());
                                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new Published(Set.of("cli"), "Controlled publication health")), Optional.empty());
                            }

                            public void close() {
                            }
                        };
                        var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Verify exact candidate runtime"), originalBrowser,
                            port, journal, "MANUAL_REVIEW", true, REVISION, () -> REVISION);
                        if (scenario == Scenario.WRONG_REPLAN || scenario == Scenario.NOT_APPLICABLE || scenario == Scenario.LONG_LITERAL) {
                            assertEquals(TaskResult.SUCCEEDED, session.run().outcome());
                            assertEquals(1, deliveries.get());
                            assertEquals(scenario == Scenario.WRONG_REPLAN ? 3 : 1, commands.get());
                        } else {
                            var stopped = assertThrows(IllegalStateException.class, session::run);
                            assertEquals(scenario == Scenario.UNKNOWN ? "Unknown agent command must not be replayed"
                                : scenario == Scenario.DENY ? "Repeated rejected validate_runtime parameters without new validated progress"
                                : "Natural language cannot finish deployment; native finish gates are required", stopped.getMessage());
                            assertEquals(0, seals.get());
                            assertEquals(0, deliveries.get());
                            assertEquals(scenario == Scenario.COMMAND ? 2 : 1, commands.get());
                            assertEquals(scenario != Scenario.UNKNOWN, DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                            if (scenario == Scenario.UNKNOWN)
                                assertEquals("UNKNOWN", journal.find(task).orElseThrow().state());
                        }
                        assertEquals(scenario == Scenario.WRONG_REPLAN ? 2 : scenario == Scenario.NOT_APPLICABLE || scenario == Scenario.LONG_LITERAL ? 1 : scenario == Scenario.UNKNOWN || scenario == Scenario.DENY || scenario == Scenario.FAILED_RUN_REPLAN ? 0 : 1,
                            journal.events(task).stream().filter(event -> event.type().equals("AGENT_OBSERVATION") && event.action().equals("TOOL_validate_runtime")).count());
                        if (scenario == Scenario.DENY) {
                            assertEquals(1, scriptCalls.size(), "Same denied generated bytes cannot seek a second review or dispatch");
                            assertEquals(1, journal.events(task).stream().filter(event -> event.type().equals("COMMAND_NOT_SENT")).count());
                        }
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
            if (failure.get() != null)
                throw new AssertionError("Exact runtime candidate scenario failed: " + scenario, failure.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }

    private static OperationResult<AiResponse> reply(List<AiToolCall> calls) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", calls.isEmpty() ? "stop" : "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls, Optional.empty())), Optional.empty());
    }
}
