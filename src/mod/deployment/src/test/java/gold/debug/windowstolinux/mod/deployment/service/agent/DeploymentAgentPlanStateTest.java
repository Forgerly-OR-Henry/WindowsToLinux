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
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real native requests, frozen sources and task journal with controlled execution tools. / 真实原生请求、冻结来源与任务账本，执行工具受控。 */
class DeploymentAgentPlanStateTest extends DeploymentRuntimeFixture {
    private static final String REVISION = "a".repeat(64);

    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"false,0", "true,0", "true,4"})
    void nativePlanPagesRetainAcceptedDeclarationAcrossRejectedReplanAndReportPatchStaleness(boolean rejectArtifact, int commandExit) throws Exception {
        var source = Files.createDirectories(root.resolve("plan-source"));
        var changed = Files.createDirectories(root.resolve("plan-changed-source"));
        Files.writeString(source.resolve("index.html"), "original\n");
        Files.writeString(changed.resolve("index.html"), "revised\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var failed = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("agent-plan-state-local", "Accepted declaration is data, not resolved authority", List.of(), false), task -> {
                try {
                    var originalSelection = actual.file().sources().selectDirectory(source, task);
                    var changedSelection = actual.file().sources().selectDirectory(changed, task);
                    try (var original = new DeploymentSourceSetup(actual.file()).prepare(originalSelection, 1048576, 100, task);
                         var derivative = new DeploymentSourceSetup(actual.file()).prepare(changedSelection, 1048576, 100, task)) {
                        var originalBrowser = new DeploymentSourceBrowser(original.snapshot().directory(), original.snapshot().sha256());
                        var derivativeBrowser = new DeploymentSourceBrowser(derivative.snapshot().directory(), derivative.snapshot().sha256());
                        var current = new AtomicReference<>(originalBrowser);
                        var sourceUnavailable = new AtomicBoolean();
                        var unavailable = new ApiException(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_CURRENT_SOURCE_UNAVAILABLE",
                            "Current source inspection is unavailable", Map.of(), Optional.of(task.id())));
                        var server = new ServerId("plan-only");
                        var journal = new DeploymentAgentActionLog(actual.db());
                        journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), REVISION, "MANUAL_REVIEW", true));
                        var calls = new AtomicInteger();
                        var approvals = new AtomicInteger();
                        var seals = new AtomicInteger();
                        var commands = new AtomicInteger();
                        var lastRunExit = new AtomicInteger();
                        var patches = new AtomicInteger();
                        var accepted = new AtomicReference<JsonNode>();
                        var prefix = new AtomicReference<String>();
                        var firstPage = new AtomicReference<JsonNode>();
                        var sealedPage = new AtomicReference<JsonNode>();
                        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                            if (method.getName().equals("configurationRevision")) return REVISION;
                            assertEquals("call", method.getName());
                            assertSame(task, args[args.length - 1]);
                            var request = (AiRequest) args[0];
                            if (request.purpose() == AiPurpose.APPROVAL) {
                                assertEquals(1, approvals.incrementAndGet(), "Only the exact controlled patch is independently reviewed");
                                return response("plan-patch-review", "submit_result", "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":true,\"reason\":\"Controlled exact source patch\"}");
                            }
                            assertEquals(AiPurpose.LANGUAGE, request.purpose());
                            var state = json.readTree(request.messages().getLast().text()).path("state");
                            int actualTurn = calls.getAndIncrement();
                            int turn = !rejectArtifact || actualTurn < 6 ? actualTurn : actualTurn == 6 ? -2 : actualTurn == 7 ? -1
                                : commandExit != 0 && actualTurn == 8 ? -4 : commandExit != 0 && actualTurn == 9 ? -3 : actualTurn - (commandExit == 0 ? 2 : 4);
                            if (turn == -4) {
                                assertTrue(state.path("recentRejections").toString().contains("SEAL_PRECONDITION"));
                                assertEquals(1, seals.get(), "The known failed run cannot send another seal");
                                assertEquals(1, journal.events(task).stream().filter(event -> event.type().equals("SEAL_EXECUTING")).count());
                                assertNotEquals("UNKNOWN", journal.find(task).orElseThrow().state());
                            }
                            if (turn == -2) {
                                assertTrue(state.path("sealed").isEmpty());
                                assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)), "The executed rejection has its own result");
                                assertTrue(journal.events(task).stream().noneMatch(event -> event.type().equals("SEALED_ARTIFACT")));
                                var returned = request.messages().stream().flatMap(message -> message.toolResult().stream())
                                    .filter(result -> result.callId().equals("state_5")).findFirst().orElseThrow();
                                assertTrue(returned.error());
                                assertTrue(returned.text().contains("ARTIFACT_REJECTED") && returned.text().contains("HARD_LINK"));
                            }
                            if (turn == -1) {
                                var outcome = state.path("components").get(0).path("capabilities").path("latestKnownCommand");
                                assertTrue(outcome.path("present").asBoolean());
                                assertEquals(commandExit, outcome.path("exitStatus").asInt());
                                assertEquals(commandExit == 0, outcome.path("succeeded").asBoolean());
                                assertTrue(outcome.path("assessmentCurrent").asBoolean());
                                assertEquals(state.path("judgement").path("pending").get(0).asText(), outcome.path("id").asText());
                                assertTrue(state.path("sealed").isEmpty(), "A known failed command can change output but cannot preserve the old seal");
                            }
                            if (state.path("components").size() == 1) {
                                var layout = state.path("components").get(0).path("executionLayout");
                                assertEquals(".", layout.path("recipeSourceRoot").asText());
                                assertEquals("WTL_OUTPUT", layout.path("commandInitialDirectory").asText());
                                assertTrue(layout.path("recipeResetsOutput").asBoolean());
                                assertTrue(layout.path("commandInvalidatesSeal").asBoolean());
                                assertFalse(layout.path("commandEnvironmentPersists").asBoolean());
                                assertFalse(layout.path("dependencyArtifactsMounted").asBoolean());
                                assertTrue(layout.path("dependenciesNeedingSeal").isEmpty());
                                assertFalse(layout.toString().contains("/var/lib"));
                                var capabilities = state.path("components").get(0).path("capabilities");
                                assertFalse(capabilities.path("localFactsAvailable").asBoolean(), "The default port supplies no inferred recipe or tools");
                                assertTrue(capabilities.path("details").asText().contains("plan_state"));
                                assertFalse(capabilities.has("selectedRecipe"));
                            }
                            if (turn == 4) {
                                var page = page(request, 3);
                                firstPage.set(page);
                                assertStatus(page, 1, 1, true, true, true);
                                assertEquals(state.path("components").get(0).path("executionLayout"), page.path("executionLayout"));
                                assertFalse(page.path("capabilities").path("localFactsAvailable").asBoolean());
                                assertFalse(page.path("capabilities").path("latestKnownCommand").path("present").asBoolean());
                                assertTrue(page.path("hasMore").asBoolean());
                                assertEquals(200, page.path("next").asInt());
                                prefix.set(page.path("declarationPage").asText());
                                assertEquals(3, state.path("evidenceIndex").path("entries").size(), "PLAN_STATE creates no new READ or observation ID");
                            }
                            if (turn == 5) {
                                var page = page(request, 4);
                                assertStatus(page, 1, 1, true, true, true);
                                assertEquals(firstPage.get().path("declarationSha256"), page.path("declarationSha256"));
                                assertEquals(firstPage.get().path("statusSha256"), page.path("statusSha256"), "Character offsets do not change status identity");
                                assertEquals(firstPage.get().path("acceptedSourceRevisions"), page.path("acceptedSourceRevisions"));
                                assertEquals(200, page.path("offset").asInt());
                                assertFalse(page.path("hasMore").asBoolean());
                                assertEquals(accepted.get(), json.readTree(prefix.get() + page.path("declarationPage").asText()));
                                assertEquals(3, state.path("evidenceIndex").path("entries").size());
                            }
                            if (turn == 7) {
                                var page = page(request, 6);
                                assertStatus(page, 1, 1, true, true, false);
                                assertEquals(firstPage.get().path("declarationSha256"), page.path("declarationSha256"));
                                assertNotEquals(firstPage.get().path("statusSha256"), page.path("statusSha256"), "Seal changes current status, not the accepted declaration");
                                sealedPage.set(page);
                                var declaration = json.readTree(page.path("declarationPage").asText());
                                assertEquals(accepted.get(), declaration);
                                assertEquals("app-password", declaration.at("/components/0/secretReferences/0/identifier").asText());
                                assertFalse(declaration.toString().contains("DB_OWNED_PATH"));
                                assertFalse(declaration.toString().contains("db-password"));
                                assertFalse(declaration.has("assessment"));
                            }
                            if (turn == 8) {
                                assertTrue(state.path("recentRejections").toString().contains("Controlled resource resolution rejected"));
                            }
                            if (turn == 9) {
                                assertEquals(sealedPage.get(), page(request, 8), "Failed resource resolution keeps the accepted declaration and sealed state");
                                assertEquals(1, state.path("planRevision").asInt());
                                assertFalse(state.path("sealed").isEmpty());
                            }
                            if (turn == 11) {
                                var page = page(request, 10);
                                assertStatus(page, 2, 2, true, true, true);
                                assertTrue(state.path("sealed").isEmpty(), "Successful replan cannot retain prior artifacts");
                                assertEquals("Second accepted declaration", json.readTree(page.path("declarationPage").asText()).path("explanation").asText());
                                if (rejectArtifact) {
                                    var outcome = page.path("capabilities").path("latestKnownCommand");
                                    assertEquals(0, outcome.path("exitStatus").asInt());
                                    assertTrue(outcome.path("sourceCurrent").asBoolean());
                                    assertFalse(outcome.path("assessmentCurrent").asBoolean(), "Replan preserves original receipts as historical data, without reauthorizing their IDs");
                                }
                            }
                            if (turn == 13) {
                                var page = page(request, 12);
                                assertStatus(page, 2, 3, false, false, true);
                                var declaration = json.readTree(page.path("declarationPage").asText());
                                assertEquals("o1", declaration.at("/components/0/evidence/0").asText());
                                assertEquals(originalBrowser.revision(), page.path("acceptedSourceRevisions").path("api").asText());
                                assertTrue(state.path("sealed").isEmpty());
                                if (rejectArtifact) {
                                    var outcome = page.path("capabilities").path("latestKnownCommand");
                                    assertEquals(0, outcome.path("exitStatus").asInt());
                                    assertFalse(outcome.path("sourceCurrent").asBoolean());
                                    assertFalse(outcome.path("assessmentCurrent").asBoolean());
                                }
                                sourceUnavailable.set(true);
                            }
                            String tool = switch (turn) {
                                case 0 -> "read";
                                case 1 -> "identify";
                                case 2, 7, 9 -> "plan";
                                case 3, 4, 6, 8, 10, 12, 13 -> "plan_state";
                                case -4, -2 -> "command";
                                case -3, -1, 5 -> "seal";
                                case 11 -> "patch";
                                default ->
                                    throw new AssertionError("No action after actual current source inspection failed");
                            };
                            var parameters = switch (tool) {
                                case "read" ->
                                    json.createObjectNode().put("path", "index.html").put("offset", 0).put("limit", 100);
                                case "identify" -> (ObjectNode) json.readTree("""
                                    {"components":[{"component":"api","root":".","technology":"Read actual source","runtime":"Declared managed process",
                                    "resources":"Explicit reference metadata","uncertainties":"Controlled tools prove no deployment","evidence":["o1"]}]}
                                    """);
                                case "plan" -> {
                                    var proposed = declaration(turn == 9 ? "Second accepted declaration" : "First accepted declaration");
                                    if (turn == 7)
                                        ((ObjectNode) proposed.path("components").get(0)).putArray("configuration").addObject().put("key", "REJECT_RESOLVE").put("value", true);
                                    if (turn == 2) accepted.set(proposed.deepCopy());
                                    yield proposed;
                                }
                                case "plan_state" -> json.createObjectNode().put("component", "api")
                                    .put("offset", turn == 4 ? firstPage.get().path("next").asInt() : 0).put("limit", turn == 3 ? 200 : 6000);
                                case "seal" -> json.createObjectNode().put("component", "api");
                                case "command" -> json.createObjectNode().put("component", "api").put("seconds", 60)
                                    .put("script", turn == -4 ? "printf 'independently corrected artifact' > \"$WTL_OUTPUT/run.sh\""
                                        : "printf 'controlled corrected artifact' > \"$WTL_OUTPUT/run.sh\"");
                                case "patch" -> {
                                    var patch = json.createObjectNode().put("component", "api").put("path", "index.html").put("sourceRevision", originalBrowser.revision())
                                        .put("beforeDigest", digest("original\n"));
                                    var edit = patch.putArray("edits").addObject().put("line", 1);
                                    edit.putArray("removed").add("original");
                                    edit.putArray("added").add("revised");
                                    yield patch;
                                }
                                default -> throw new AssertionError(tool);
                            };
                            if (Set.of("plan", "seal", "patch", "command").contains(tool)) {
                                var assessment = parameters.putObject("assessment");
                                var basis = assessment.putArray("basis");
                                var pending = state.path("judgement").path("pending");
                                if (pending.isEmpty()) basis.add("o1");
                                else pending.forEach(id -> basis.add(id.asText()));
                                assessment.put("suitability", "Current original observations").put("differences", "Declarations are not resolved credentials or authority")
                                    .put("resolution", "RESOLVED").put("decision", "One explicitly proposed controlled action");
                            }
                            return response("state_" + turn, tool, parameters.toString());
                        });
                        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                            if (method.getName().equals("ai")) return ai;
                            try {
                                return method.invoke(actual, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                        var port = new DeploymentAgentDeliveryPort() {
                            public DeploymentSourceBrowser source(String component) throws ApiException {
                                assertEquals("api", component);
                                if (sourceUnavailable.get()) throw unavailable;
                                return current.get();
                            }

                            public DeploymentAgentPlan.Part resolve(DeploymentAgentPlan.Part part) {
                                if (part.configuration().stream().anyMatch(entry -> entry.key().equals("REJECT_RESOLVE")))
                                    throw new IllegalArgumentException("Controlled resource resolution rejected");
                                var config = new ArrayList<>(part.configuration());
                                config.add(new DeploymentConfigEntry("DB_OWNED_PATH", DeploymentConfigScopeType.RUNTIME, new DeploymentConfigValue.Text("data/prepared.db")));
                                var refs = new ArrayList<>(part.secretReferences());
                                refs.add(new DeploymentSecretRef("db-password", 3));
                                return new DeploymentAgentPlan.Part(part.id(), part.sourceRoot(), part.dependencies(), part.evidence(), part.runtime(), part.resources(), config, part.databaseIds(), refs);
                            }

                            public Map<String, String> inspect() {
                                throw new AssertionError();
                            }

                            public Prepared prepare(String app, DeploymentAgentPlan.Part part) {
                                return new Prepared(new DeploymentRemoteWorkspace(app + "-" + part.id(), "c".repeat(64)), current.get().revision());
                            }

                            public CommandFeedback command(TaskContext supplied, Prepared candidate, String revision, String script, Duration timeout) {
                                assertTrue(rejectArtifact);
                                assertSame(task, supplied);
                                assertEquals(current.get().revision(), revision);
                                int exit = commands.incrementAndGet() == 1 ? commandExit : 0;
                                lastRunExit.set(exit);
                                return new CommandFeedback(true, exit == 0, exit,
                                    exit == 0 ? "Controlled output corrected" : "Controlled command failed after updating output");
                            }

                            public String read(TaskContext supplied, Prepared candidate, String revision, String path, int offset, int limit) {
                                throw new AssertionError();
                            }

                            public void validatePatch(TaskContext supplied, Prepared candidate, DeploymentAgentPatch patch) {
                                assertSame(task, supplied);
                                assertEquals(originalBrowser.revision(), patch.sourceRevision());
                                assertEquals(digest("original\n"), patch.beforeDigest());
                                assertEquals(List.of("original"), patch.edits().getFirst().removed());
                                assertEquals(List.of("revised"), patch.edits().getFirst().added());
                            }

                            public String patch(TaskContext supplied, Prepared candidate, String revision, DeploymentAgentPatch patch) {
                                patches.incrementAndGet();
                                current.set(derivativeBrowser);
                                return derivativeBrowser.revision();
                            }

                            public void validateSeal(TaskContext supplied, Prepared candidate, String revision) {
                                assertSame(task, supplied);
                                assertEquals(current.get().revision(), revision);
                                if (commands.get() > 0 && lastRunExit.get() != 0)
                                    throw new IllegalArgumentException("SEAL_PRECONDITION: last known command failed; no seal was sent");
                            }

                            public String seal(TaskContext supplied, Prepared candidate, String revision) throws Exception {
                                if (seals.incrementAndGet() == 1 && rejectArtifact)
                                    throw gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentArtifactReceiptTest
                                        .controlledRejection(supplied.id(), candidate.workspace().candidateId(), revision);
                                return "d".repeat(64);
                            }

                            public OperationResult<Published> deliver(TaskContext supplied, DeploymentAgentPlan plan, Map<String, Prepared> candidates,
                                                                      Map<String, String> revisions, Map<String, String> artifacts, int remaining) {
                                throw new AssertionError();
                            }

                            public void close() {
                            }
                        };
                        var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Inspect accepted declaration"), originalBrowser,
                            port, journal, "MANUAL_REVIEW", true, REVISION, () -> REVISION);
                        assertSame(unavailable, assertThrows(ApiException.class, session::run), "Source inspection failure cannot report sourceCurrent=true");
                        assertEquals(rejectArtifact ? commandExit == 0 ? 16 : 18 : 14, calls.get());
                        assertEquals(1, approvals.get());
                        assertEquals(rejectArtifact ? 2 : 1, seals.get());
                        assertEquals(rejectArtifact ? commandExit == 0 ? 1 : 2 : 0, commands.get());
                        assertEquals(rejectArtifact ? 1 : 0, journal.events(task).stream()
                            .filter(event -> event.type().equals("SEAL_RESULT") && event.detail().equals("REJECTED")).count());
                        assertEquals(1, patches.get());
                        assertEquals(2, journal.events(task).stream().filter(event -> event.type().equals("PLAN")).count());
                        assertTrue(journal.events(task).stream().noneMatch(event -> event.type().startsWith("DELIVERY_")));
                        assertEquals("original\n", Files.readString(original.snapshot().directory().resolve("index.html")));
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
            if (failed.get() != null) throw new AssertionError("Controlled plan-state lifecycle failed", failed.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }

    private ObjectNode declaration(String explanation) throws Exception {
        var plan = (ObjectNode) json.readTree("""
            {"applicationId":"demo","components":[{"id":"api","sourceRoot":".","dependencies":[],"evidence":["o1"],
            "backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"",
            "health":{"type":"PROCESS","timeout":30,"stability":3},"ports":[],"resources":[],"configuration":[],
            "databases":[],"secretReferences":[{"identifier":"app-password","revision":2}]}],
            "applicationHealth":{"component":"api","health":{"type":"PROCESS","timeout":30,"stability":3}}}
            """);
        return plan.put("explanation", explanation);
    }

    private JsonNode page(AiRequest request, int turn) throws Exception {
        var result = request.messages().stream().flatMap(message -> message.toolResult().stream())
            .filter(item -> item.callId().equals("state_" + turn)).findFirst().orElseThrow();
        assertFalse(result.error());
        assertTrue(result.text().length() <= 12000);
        return json.readTree(result.text());
    }

    private static void assertStatus(JsonNode page, int accepted, int current, boolean sourceCurrent, boolean readsCurrent, boolean needsSeal) {
        assertEquals(accepted, page.path("acceptedAtPlanRevision").asInt());
        assertEquals(current, page.path("currentPlanRevision").asInt());
        assertEquals(sourceCurrent, page.path("sourceCurrent").asBoolean());
        assertEquals(readsCurrent, page.path("readsCurrent").asBoolean());
        assertEquals(needsSeal, page.path("selectedComponentNeedsSeal").asBoolean());
        assertTrue(page.path("needsSeal").isArray());
        assertEquals(needsSeal ? List.of("api") : List.of(), new ObjectMapper().convertValue(page.path("needsSeal"), List.class));
    }

    private static OperationResult<AiResponse> response(String call, String tool, String arguments) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall(call, tool, arguments)), Optional.empty())), Optional.empty());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
