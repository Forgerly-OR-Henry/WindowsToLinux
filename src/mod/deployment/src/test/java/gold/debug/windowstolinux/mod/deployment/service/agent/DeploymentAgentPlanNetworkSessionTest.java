package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.JsonNode;
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
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises real native plan rejection with controlled tools and no server or provider dispatch. / 通过受控工具验证真实原生计划拒绝，不发送服务器或模型请求。 */
class DeploymentAgentPlanNetworkSessionTest extends DeploymentRuntimeFixture {
    private static final String REVISION = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"process-mapping", "wrong-public-port", "internal-public-port"})
    void rejectedNetworkReplanNeverResolvesResourcesOrChangesSealedDeclaration(String scenario) throws Exception {
        var source = Files.createDirectories(root.resolve("network-source"));
        Files.writeString(source.resolve("index.html"), "source-backed website\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var failed = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("agent-network-local", "Reject invalid network intent before resource resolution", List.of(), false), task -> {
                try {
                    var selection = actual.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1048576, 100, task)) {
                        var browser = new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256());
                        var server = new ServerId("network-only");
                        var journal = new DeploymentAgentActionLog(actual.db());
                        journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), REVISION, "MANUAL_REVIEW", true));
                        var calls = new AtomicInteger();
                        var resolutions = new AtomicInteger();
                        var seals = new AtomicInteger();
                        var sealedPage = new AtomicReference<JsonNode>();
                        var sourceUnavailable = new AtomicBoolean();
                        var unavailable = new ApiException(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_SOURCE_UNAVAILABLE",
                            "Current source inspection is unavailable", Map.of(), Optional.of(task.id())));
                        var requirements = Map.of("goal", "Deploy source-backed website", "publicUrl", "http://site.example:46100");
                        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                            if (method.getName().equals("configurationRevision")) return REVISION;
                            assertEquals("call", method.getName());
                            assertSame(task, args[args.length - 1]);
                            var request = (AiRequest) args[0];
                            assertEquals(AiPurpose.LANGUAGE, request.purpose(), "Network preflight never calls an approval provider");
                            var state = JSON.readTree(request.messages().getLast().text()).path("state");
                            assertEquals(JSON.valueToTree(requirements), state.path("requirements"));
                            int turn = calls.getAndIncrement();
                            if (turn == 5) {
                                sealedPage.set(page(request, 4));
                                assertFalse(sealedPage.get().path("selectedComponentNeedsSeal").asBoolean());
                                assertEquals(1, resolutions.get());
                            }
                            if (turn == 6) {
                                assertTrue(state.path("recentRejections").toString().contains("AGENT_PLAN_NETWORK_CONSTRAINT"));
                                var rejection = request.messages().stream().flatMap(message -> message.toolResult().stream())
                                    .filter(result -> result.callId().equals("network_5")).findFirst().orElseThrow();
                                assertTrue(rejection.error());
                                assertTrue(rejection.text().contains("AGENT_PLAN_NETWORK_CONSTRAINT"));
                                assertEquals(1, resolutions.get(), "Rejected replan cannot resolve resource references");
                                assertEquals(1, state.path("planRevision").asInt());
                                assertEquals("d".repeat(64), state.path("sealed").path("web").asText());
                            }
                            if (turn == 7) {
                                assertEquals(sealedPage.get(), page(request, 6), "Rejected network intent preserves accepted declaration, revision and seal status");
                                sourceUnavailable.set(true);
                            }
                            String tool = switch (turn) {
                                case 0 -> "read";
                                case 1 -> "identify";
                                case 2, 5 -> "plan";
                                case 3 -> "seal";
                                case 4, 6, 7 -> "plan_state";
                                default -> throw new AssertionError("No calls after controlled source inspection failure");
                            };
                            var parameters = switch (tool) {
                                case "read" -> JSON.createObjectNode().put("path", "index.html").put("offset", 0).put("limit", 100);
                                case "identify" -> (ObjectNode) JSON.readTree("""
                                    {"components":[{"component":"web","root":".","technology":"Read source website",
                                     "runtime":"Declared process listener","resources":"No database","uncertainties":"Controlled test does not prove business acceptance","evidence":["o1"]}]}
                                    """);
                                case "plan" -> declaration(turn == 5 ? scenario : "valid");
                                case "seal" -> JSON.createObjectNode().put("component", "web");
                                case "plan_state" -> JSON.createObjectNode().put("component", "web").put("offset", 0).put("limit", 6000);
                                default -> throw new AssertionError(tool);
                            };
                            if (Set.of("plan", "seal").contains(tool)) {
                                var assessment = parameters.putObject("assessment");
                                var basis = assessment.putArray("basis");
                                var pending = state.path("judgement").path("pending");
                                if (pending.isEmpty()) basis.add("o1");
                                else pending.forEach(id -> basis.add(id.asText()));
                                assessment.put("suitability", "Current controlled observations").put("differences", "Typed declaration requires local network validation")
                                    .put("resolution", "RESOLVED").put("decision", "Submit the exact controlled proposal");
                            }
                            return response("network_" + turn, tool, parameters.toString());
                        });
                        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                            if (method.getName().equals("ai")) return ai;
                            try {
                                return method.invoke(actual, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                        var tools = new DeploymentAgentDeliveryPort() {
                            public DeploymentSourceBrowser source(String component) throws ApiException {
                                assertEquals("web", component);
                                if (sourceUnavailable.get()) throw unavailable;
                                return browser;
                            }

                            public DeploymentAgentPlan.Part resolve(DeploymentAgentPlan.Part component) {
                                resolutions.incrementAndGet();
                                return component;
                            }

                            public Map<String, String> inspect() {
                                throw new AssertionError("No server inspection");
                            }

                            public Prepared prepare(String application, DeploymentAgentPlan.Part component) {
                                return new Prepared(new DeploymentRemoteWorkspace(application + "-" + component.id(), "c".repeat(64)), browser.revision());
                            }

                            public CommandFeedback command(TaskContext original, Prepared candidate, String revision, String script, Duration timeout) {
                                throw new AssertionError("No commands");
                            }

                            public String read(TaskContext original, Prepared candidate, String revision, String path, int offset, int limit) {
                                throw new AssertionError("No remote reads");
                            }

                            public String patch(TaskContext original, Prepared candidate, String revision, DeploymentAgentPatch patch) {
                                throw new AssertionError("No patches");
                            }

                            public void validateSeal(TaskContext original, Prepared candidate, String revision) {
                                assertSame(task, original);
                                assertEquals(browser.revision(), revision);
                            }

                            public String seal(TaskContext original, Prepared candidate, String revision) {
                                seals.incrementAndGet();
                                return "d".repeat(64);
                            }

                            public OperationResult<Published> deliver(TaskContext original, DeploymentAgentPlan plan, Map<String, Prepared> candidates,
                                                                      Map<String, String> revisions, Map<String, String> artifacts, int remaining) {
                                throw new AssertionError("No publication");
                            }

                            public void close() {
                            }
                        };
                        var session = new DeploymentAgentSession(context, task, server, requirements, browser, tools, journal, "MANUAL_REVIEW", true, REVISION, () -> REVISION);
                        assertSame(unavailable, assertThrows(ApiException.class, session::run));
                        assertEquals(8, calls.get());
                        assertEquals(1, resolutions.get());
                        assertEquals(1, seals.get());
                        assertEquals(1, journal.events(task).stream().filter(event -> event.type().equals("PLAN")).count());
                        assertTrue(journal.events(task).stream().noneMatch(event -> event.type().equals("ARTIFACT_INVALIDATED") || event.type().startsWith("DELIVERY_")));
                    }
                    actual.file().closeTaskResources(task);
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError failure) {
                    failed.set(failure);
                    throw failure;
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(30, TimeUnit.SECONDS);
            if (failed.get() != null) throw new AssertionError("Controlled network preflight failed", failed.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }

    private static ObjectNode declaration(String scenario) throws Exception {
        var declaration = (ObjectNode) JSON.readTree("""
            {"applicationId":"demo","explanation":"Source-backed website on requested host port",
             "components":[{"id":"web","sourceRoot":".","dependencies":[],"evidence":["o1"],"backend":"PROCESS",
             "mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"",
             "health":{"type":"PROCESS","timeout":30,"stability":3},"ports":[],"resources":[],"configuration":[],
             "databases":[],"secretReferences":[]}],"applicationHealth":{"component":"web","health":{"type":"PROCESS","timeout":30,"stability":3}}}
            """);
        int host = scenario.equals("wrong-public-port") ? 18100 : 46100;
        int target = scenario.equals("process-mapping") ? 18100 : host;
        ((ObjectNode) declaration.path("components").get(0)).putArray("ports").addObject().put("id", "site")
            .put("host", host).put("target", target).put("exposure", scenario.equals("internal-public-port") ? "INTERNAL" : "EXTERNAL");
        return declaration;
    }

    private static JsonNode page(AiRequest request, int turn) throws Exception {
        var result = request.messages().stream().flatMap(message -> message.toolResult().stream())
            .filter(value -> value.callId().equals("network_" + turn)).findFirst().orElseThrow();
        assertFalse(result.error());
        return JSON.readTree(result.text());
    }

    private static OperationResult<AiResponse> response(String call, String tool, String arguments) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall(call, tool, arguments)), Optional.empty())), Optional.empty());
    }
}
