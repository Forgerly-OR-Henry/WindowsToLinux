package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.db.DatabaseProjectChecker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Real source, native Agent protocol and journal; controlled tools establish no real deployment or authorization. */
class DeploymentAgentDatabaseDeclarationTest extends DeploymentRuntimeFixture {
    private static final String REVISION = "a".repeat(64);

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void duplicateResolvedSqliteBindingReturnsToMainLoopBeforeAnyPreparationAndCorrectedPlanRetainsDatabase() throws Exception {
        var source = Files.createDirectories(root.resolve("duplicate-data-source"));
        Files.writeString(source.resolve("index.html"), "Controlled source-backed persistent service\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var failed = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("agent-managed-data-local", "Reject ambiguous resources before publication", List.of(), false), task -> {
                try {
                    var selection = actual.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1048576, 100, task)) {
                        var browser = new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256());
                        var server = new ServerId("managed-data-only");
                        var journal = new DeploymentAgentActionLog(actual.db());
                        journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), REVISION, "MANUAL_REVIEW", true));
                        var calls = new AtomicInteger();
                        var preparations = new AtomicInteger();
                        var deliveries = new AtomicInteger();
                        var database = new gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseBinding("state",
                            new gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection.Sqlite("state.db",
                                gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation.defaults(), "data/state.db"));
                        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                            if (method.getName().equals("configurationRevision")) return REVISION;
                            assertEquals("call", method.getName());
                            assertSame(task, args[args.length - 1]);
                            var request = (AiRequest) args[0];
                            assertEquals(AiPurpose.LANGUAGE, request.purpose());
                            var state = json.readTree(request.messages().getLast().text()).path("state");
                            int turn = calls.getAndIncrement();
                            if (turn == 3) {
                                assertTrue(state.path("recentRejections").toString().contains("duplicates binding ID state"));
                                var refusal = request.messages().stream().flatMap(message -> message.toolResult().stream())
                                    .filter(result -> result.callId().equals("data_2")).findFirst().orElseThrow();
                                assertTrue(refusal.error());
                                assertTrue(refusal.text().contains("AGENT_PLAN_MANAGED_DATA_CONSTRAINT"));
                                assertEquals(0, preparations.get());
                                assertEquals(0, deliveries.get());
                                assertEquals(0, state.path("planRevision").asInt());
                                assertTrue(journal.events(task).stream().noneMatch(event -> event.type().equals("PLAN")
                                    || event.type().startsWith("PREPARE_") || event.type().startsWith("DELIVERY_")));
                                assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                            }
                            String tool = switch (turn) {
                                case 0 -> "read";
                                case 1 -> "identify";
                                case 2, 3 -> "plan";
                                case 4 -> "command";
                                case 5 -> "seal";
                                case 6 -> "deliver";
                                default -> throw new AssertionError("No calls after corrected controlled delivery");
                            };
                            var parameters = (ObjectNode) json.readTree(switch (tool) {
                                case "read" -> "{\"path\":\"index.html\",\"offset\":0,\"limit\":100}";
                                case "identify" -> """
                                    {"components":[{"component":"api","root":".","technology":"Read source",
                                    "runtime":"Declared process","resources":"Verified SQLite state","uncertainties":"Controlled test does not certify business behavior","evidence":["o1"]}]}
                                    """;
                                case "plan" -> plan(false);
                                case "command" -> "{\"component\":\"api\",\"script\":\"printf result\",\"seconds\":10}";
                                case "seal" -> "{\"component\":\"api\"}";
                                case "deliver" -> "{}";
                                default -> throw new AssertionError(tool);
                            });
                            if (tool.equals("plan")) {
                                var part = (ObjectNode) parameters.path("components").get(0);
                                part.putArray("databases").add("state");
                                part.putObject("workspace").put("mode", "EXPLICIT").put("minimumBuildBytes", 0).put("preferredBuildBytes", 0).put("explicitBytes", 134217728);
                                part.putArray("resources").addObject().put("id", turn == 2 ? "state" : "uploads")
                                    .put("path", "uploads").put("access", "READ_WRITE").put("schema", "files").put("reversible", true)
                                    .put("type", "FILE").put("seedFile", "").put("digest", "");
                            }
                            if (!Set.of("read", "identify").contains(tool)) {
                                var assessment = parameters.putObject("assessment");
                                var basis = assessment.putArray("basis");
                                var pending = state.path("judgement").path("pending");
                                if (pending.isEmpty()) basis.add("o1");
                                else pending.forEach(id -> basis.add(id.asText()));
                                assessment.put("suitability", "Current controlled evidence").put("differences", "Keep verified database identity while correcting redundant file identity")
                                    .put("resolution", "RESOLVED").put("decision", "Submit current declaration");
                            }
                            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
                                new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                                List.of(new AiToolCall("data_" + turn, tool, parameters.toString())), Optional.empty())), Optional.empty());
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
                            public DeploymentSourceBrowser source(String component) {
                                return browser;
                            }

                            public DeploymentAgentPlan.Part resolve(DeploymentAgentPlan.Part part) {
                                assertEquals(Optional.of(List.of("state")), part.databaseIds());
                                return new DeploymentAgentPlan.Part(part.id(), part.sourceRoot(), part.dependencies(), part.evidence(), part.runtime(),
                                    new gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings(part.resources().fileBindings(), Optional.of(List.of(database))),
                                    part.configuration(), part.databaseIds(), part.secretReferences(), part.workspaceRequest(), part.workspaceSelection());
                            }

                            public List<DeploymentAgentPlan.Part> selectWorkspaces(DeploymentAgentPlan plan, Map<String, Prepared> existing) {
                                var part = plan.components().getFirst();
                                var quota = gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.select(
                                    List.of(new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input(part.id(), 0, 0, part.workspaceRequest().orElseThrow())),
                                    4294967296L, new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Policy(4294967296L, 1073741824L),
                                    gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Cost.AGENT).get(part.id());
                                return List.of(part.withWorkspace(quota));
                            }

                            public Map<String, String> inspect() {
                                throw new AssertionError("No host inspection");
                            }

                            public Prepared prepare(String application, DeploymentAgentPlan.Part part) {
                                preparations.incrementAndGet();
                                return new Prepared(new DeploymentRemoteWorkspace(application + "-" + part.id(), "c".repeat(64)), browser.revision(), part.workspaceSelection().orElseThrow());
                            }

                            public CommandFeedback command(TaskContext original, Prepared candidate, String revision, String script, Duration timeout) {
                                return new CommandFeedback(true, true, 0, "Controlled command completed");
                            }

                            public String read(TaskContext original, Prepared candidate, String revision, String path, int offset, int limit) {
                                throw new AssertionError();
                            }

                            public String patch(TaskContext original, Prepared candidate, String revision, DeploymentAgentPatch patch) {
                                throw new AssertionError();
                            }

                            public void validateSeal(TaskContext original, Prepared candidate, String revision) {
                                assertSame(task, original);
                            }

                            public String seal(TaskContext original, Prepared candidate, String revision) {
                                return "d".repeat(64);
                            }

                            public OperationResult<Published> deliver(TaskContext original, DeploymentAgentPlan plan, Map<String, Prepared> candidates, Map<String, String> revisions, Map<String, String> artifacts, int remaining) {
                                assertSame(task, original);
                                var part = plan.components().getFirst();
                                assertEquals(List.of(database), part.resources().databaseBindings().orElseThrow());
                                assertEquals("uploads", part.resources().fileBindings().getFirst().bindingId());
                                assertEquals(Optional.of(List.of("state")), part.databaseIds());
                                deliveries.incrementAndGet();
                                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new Published(Set.of("api"), "Controlled publication receipt")), Optional.empty());
                            }

                            public void close() {
                            }
                        };
                        var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Retain verified resources"), browser, port, journal, "MANUAL_REVIEW", true, REVISION, () -> REVISION);
                        assertEquals(TaskResult.SUCCEEDED, session.run().outcome());
                        assertEquals(7, calls.get());
                        assertEquals(1, preparations.get());
                        assertEquals(1, deliveries.get());
                        assertEquals(1, journal.events(task).stream().filter(event -> event.type().equals("PLAN")).count());
                        assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
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
            if (failed.get() != null)
                throw new AssertionError("Controlled merged data correction failed", failed.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownDatabaseRejectsBeforeDeliveryIntentAndExplicitReplanCanResealOrRetainRealUnknown(boolean publicationFailure) throws Exception {
        declarationScenario(publicationFailure, false);
    }

    @Test
    void sourceInspectionBeforeCandidateHasZeroPreparationAndNoRemoteReceipt() throws Exception {
        declarationScenario(false, true);
    }

    private void declarationScenario(boolean publicationFailure, boolean sourceFirst) throws Exception {
        var source = Files.createDirectories(root.resolve("source"));
        Files.writeString(source.resolve("index.html"), "<html>read actual source without database declarations</html>\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var checked = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("agent-database-declaration-local", "Known resource state before publication", List.of(), false), task -> {
                try {
                    var selected = actual.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(actual.file()).prepare(selected, 1048576, 100, task)) {
                        var browser = new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256());
                        var server = new ServerId("declaration-only");
                        var journal = new DeploymentAgentActionLog(actual.db());
                        journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), REVISION, "MANUAL_REVIEW", true));
                        var calls = new AtomicInteger();
                        var preparations = new AtomicInteger();
                        var inspections = new AtomicInteger();
                        var commands = new AtomicInteger();
                        var seals = new AtomicInteger();
                        var deliveries = new AtomicInteger();
                        var unknown = new DeploymentAiJson.CapabilityOutcomeException(TaskResult.UNKNOWN,
                            Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_PUBLICATION_UNCONFIRMED", "Actual delivery remains unconfirmed", Map.of(), Optional.empty())));
                        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                            if (method.getName().equals("configurationRevision")) return REVISION;
                            assertEquals("call", method.getName());
                            assertSame(task, args[args.length - 1]);
                            var request = (AiRequest) args[0];
                            assertEquals(AiPurpose.LANGUAGE, request.purpose());
                            var state = json.readTree(request.messages().getLast().text()).path("state");
                            int turn = calls.getAndIncrement();
                            if (sourceFirst && turn == 4) {
                                assertEquals(0, preparations.get());
                                assertEquals(1, inspections.get());
                                assertTrue(journal.events(task).stream().noneMatch(event -> event.type().startsWith("PREPARE_") || event.type().equals("CANDIDATE")));
                                assertTrue(journal.events(task).stream().anyMatch(event -> event.type().equals("AGENT_OBSERVATION") && event.action().equals("TOOL_database_inspect")));
                                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "stop",
                                    new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), List.of(), Optional.empty())), Optional.empty());
                            }
                            if (turn == 3) {
                                assertEquals("PLANNED", state.path("stage").asText());
                                assertTrue(state.path("nextStep").path("condition").asText().contains("Before building or sealing when possible"));
                            }
                            if (turn == 5) {
                                assertEquals("SEALED", state.path("stage").asText());
                                assertEquals("UNKNOWN", state.path("components").get(0).path("resourceState").asText());
                                assertEquals("plan", state.path("nextStep").path("suggestedTool").asText());
                                assertTrue(state.path("nextStep").path("condition").asText().contains("seal current output again"));
                            }
                            if (turn == 6) {
                                assertTrue(state.path("recentRejections").toString().contains("Database requirements remain unknown for component api"));
                                assertTrue(journal.events(task).stream().noneMatch(event -> event.type().startsWith("DELIVERY_")), "Deterministic rejection creates no delivery intent or receipt");
                                assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                                assertEquals(0, deliveries.get());
                            }
                            if (turn == 8) {
                                assertEquals("PREPARED", state.path("stage").asText());
                                assertTrue(state.path("sealed").isEmpty(), "Resource-only replan keeps the candidate but invalidates prior artifact claims");
                                assertEquals("DECLARED", state.path("components").get(0).path("resourceState").asText());
                            }
                            if (turn == 9)
                                assertEquals("deliver", state.path("nextStep").path("suggestedTool").asText());
                            String tool = switch (turn) {
                                case 0 -> "read";
                                case 1 -> "identify";
                                case 2, 7 -> "plan";
                                case 3 -> sourceFirst ? "database_inspect" : "command";
                                case 4, 8 -> "seal";
                                case 5, 9 -> "deliver";
                                case 6 -> "database_inspect";
                                default -> throw new AssertionError("No retry beyond the exact resource correction");
                            };
                            ObjectNode parameters = (ObjectNode) json.readTree(switch (tool) {
                                case "read" -> "{\"path\":\"index.html\",\"offset\":0,\"limit\":100}";
                                case "identify" ->
                                    "{\"components\":[{\"component\":\"api\",\"root\":\".\",\"technology\":\"Read actual HTML\",\"runtime\":\"Reviewed intended artifact\",\"resources\":\"Database state awaits confirmation\",\"uncertainties\":\"No build or storage permission inferred\",\"evidence\":[\"o1\"]}]}";
                                case "plan" -> plan(turn == 2);
                                case "command" -> "{\"component\":\"api\",\"script\":\"printf result\",\"seconds\":10}";
                                case "seal" -> "{\"component\":\"api\"}";
                                case "database_inspect" -> "{\"component\":\"api\",\"scope\":\"SOURCE\"}";
                                case "deliver" -> "{}";
                                default -> throw new AssertionError(tool);
                            });
                            if (!Set.of("read", "identify").contains(tool)) {
                                var assessment = parameters.putObject("assessment");
                                var basis = assessment.putArray("basis");
                                var pending = state.path("judgement").path("pending");
                                if (pending.isEmpty()) basis.add("o1");
                                else pending.forEach(id -> basis.add(id.asText()));
                                assessment.put("suitability", "Evaluate all current pending evidence").put("differences", "Unknown and explicit empty database declarations differ")
                                    .put("resolution", "RESOLVED").put("decision", "Perform one exact source-backed step");
                            }
                            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
                                new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                                List.of(new AiToolCall("declaration_" + turn, tool, parameters.toString())), Optional.empty())), Optional.empty());
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
                            public DeploymentSourceBrowser source(String component) {
                                assertEquals("api", component);
                                return browser;
                            }

                            public Map<String, String> inspect() {
                                throw new AssertionError("No host operation");
                            }

                            public Prepared prepare(String app, DeploymentAgentPlan.Part part) {
                                preparations.incrementAndGet();
                                return new Prepared(new DeploymentRemoteWorkspace(app + "-" + part.id(), "c".repeat(64)), browser.revision());
                            }

                            public String capability(DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part, Prepared candidate, String revision, String binding, String applicationId) throws Exception {
                                assertEquals("database_inspect", request.name());
                                assertEquals("SOURCE", request.arguments().path("scope").asText());
                                if (sourceFirst)
                                    assertNull(candidate, "SOURCE must not synthesize or prepare a candidate");
                                assertTrue(part.databaseIds().isEmpty(), "Inspection does not silently mutate the old plan");
                                assertEquals(sourceFirst ? null : browser.revision(), revision);
                                var report = new DatabaseProjectChecker().inspect(material.snapshot().directory(), part.id());
                                assertTrue(report.databases().isEmpty());
                                assertFalse(report.unknownDatabase());
                                inspections.incrementAndGet();
                                return json.writeValueAsString(Map.ofEntries(Map.entry("sourceRevision", browser.revision()), Map.entry("component", part.id()),
                                    Map.entry("declarationRoot", part.sourceRoot()), Map.entry("scope", "SOURCE"), Map.entry("serverInventory", "NOT_OBSERVED"),
                                    Map.entry("sourceCoverage", "EXPLICIT_MANIFEST_AND_SUPPORTED_CONVENTIONS_ONLY"), Map.entry("status", "SOURCE_INSPECTED"),
                                    Map.entry("databases", report.databases()), Map.entry("candidateManifests", List.of()), Map.entry("questions", List.of()),
                                    Map.entry("schemaReviewRequired", report.schemaReviewRequired()), Map.entry("endpointConfirmationRequired", false)));
                            }

                            public CommandFeedback command(TaskContext original, Prepared candidate, String revision, String script, Duration timeout) {
                                commands.incrementAndGet();
                                return new CommandFeedback(true, true, 0, "Controlled actual tool exit");
                            }

                            public String read(TaskContext original, Prepared candidate, String revision, String path, int offset, int limit) {
                                throw new AssertionError();
                            }

                            public String patch(TaskContext original, Prepared candidate, String revision, DeploymentAgentPatch patch) {
                                throw new AssertionError();
                            }

                            public void validateSeal(TaskContext original, Prepared candidate, String revision) {
                                assertSame(task, original);
                            }

                            public String seal(TaskContext original, Prepared candidate, String revision) {
                                seals.incrementAndGet();
                                return "d".repeat(64);
                            }

                            public OperationResult<Published> deliver(TaskContext original, DeploymentAgentPlan plan, Map<String, Prepared> candidates, Map<String, String> revisions, Map<String, String> artifacts, int remaining) throws Exception {
                                assertSame(task, original);
                                plan.requireKnownDatabases();
                                assertEquals(Optional.of(List.of()), plan.components().getFirst().databaseIds());
                                assertEquals(1, inspections.get());
                                assertEquals(2, seals.get(), "Current plan needs a new explicit seal after replan");
                                assertEquals(1, journal.events(task).stream().filter(event -> event.type().equals("DELIVERY_EXECUTING")).count());
                                assertTrue(remaining < 80 && remaining > 0);
                                deliveries.incrementAndGet();
                                if (publicationFailure) throw unknown;
                                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new Published(Set.of("api"), "Controlled publication result")), Optional.empty());
                            }

                            public void close() {
                            }
                        };
                        var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Confirm resources before publication"), browser, port, journal, "MANUAL_REVIEW", true, REVISION, () -> REVISION);
                        if (sourceFirst) {
                            var stopped = assertThrows(IllegalStateException.class, session::run);
                            assertEquals("Natural language cannot finish deployment; native finish gates are required", stopped.getMessage());
                            assertEquals(5, calls.get());
                            assertEquals(0, preparations.get());
                            assertEquals(0, commands.get());
                            assertEquals(0, seals.get());
                            assertEquals(0, deliveries.get());
                            assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                            journal.state(task, "FAILED");
                        } else if (publicationFailure) {
                            assertSame(unknown, assertThrows(DeploymentAiJson.CapabilityOutcomeException.class, session::run));
                            assertEquals("UNKNOWN", journal.find(task).orElseThrow().state());
                            assertFalse(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                            assertTrue(journal.events(task).stream().noneMatch(event -> event.type().equals("DELIVERY_RESULT")));
                        } else {
                            assertEquals(TaskResult.SUCCEEDED, session.run().outcome());
                            assertEquals("SUCCEEDED", journal.find(task).orElseThrow().state());
                            assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                        }
                        if (!sourceFirst) {
                            assertEquals(10, calls.get());
                            assertEquals(1, preparations.get());
                            assertEquals(1, commands.get());
                            assertEquals(1, deliveries.get());
                        }
                    }
                    actual.file().closeTaskResources(task);
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError failure) {
                    checked.set(failure);
                    throw failure;
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(30, TimeUnit.SECONDS);
            if (checked.get() != null)
                throw new AssertionError("Controlled database declaration correction failed", checked.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }

    private static String plan(boolean unknown) {
        return """
            {"applicationId":"demo","explanation":"Model explicitly declares current resource evidence","components":[{"id":"api","sourceRoot":".","dependencies":[],"evidence":["o1"],
            "backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10},
            "ports":[],"resources":[],"configuration":[],"databases":%s,"secretReferences":[]}],"applicationHealth":{"component":"api","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10}}}
            """.formatted(unknown ? "null" : "[]");
    }
}
