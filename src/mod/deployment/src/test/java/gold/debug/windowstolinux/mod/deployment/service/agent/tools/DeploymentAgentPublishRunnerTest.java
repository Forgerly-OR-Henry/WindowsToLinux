package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.agent.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;
import org.junit.jupiter.api.Test;

/** Actual publication runner and native loop with local JDBC and controlled capabilities, without a provider or SSH. */
class DeploymentAgentPublishRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REVISION = "a".repeat(64);

    private static final String HELPER = "/gold/debug/windowstolinux/mod/deployment/linux/helper/";

    @Test
    void invalidContractCorrectsAndAllOfferedPagesReconstructTheirCompleteOriginalFiles() throws Exception {
        try (var frame = new Frame()) {
            var selected = new ArrayList<String>();
            var actualLines = new ArrayList<String>();
            var file = new AtomicInteger();
            var offset = new AtomicInteger();
            var pages = new AtomicInteger();
            var recovered = new AtomicInteger();
            var firstPage = new java.util.concurrent.atomic.AtomicReference<String>();
            frame.script = (request, turn) -> {
                var state = state(request);
                if (recovered.get() == 1) {
                    assertTrue(lastResult(request).text().startsWith("o2\no2 "));
                    assertTrue(lastResult(request).text().contains(firstPage.get()));
                    assertEquals(pages.get() + 2, state.path("judgement").path("evidenceIndex").path("total").asInt());
                    return calls(turn, "unable", Map.of("reason", "Controlled pagination verification complete"));
                }
                if (turn == 0) {
                    state.path("contractFiles").forEach(value -> selected.add(value.asText()));
                    assertEquals(11, selected.size());
                    var schema = JSON.readTree(tool(request, "contract").inputSchema());
                    assertEquals(500, schema.at("/properties/path/maxLength").asInt());
                    assertEquals(11, schema.at("/properties/path/enum").size());
                    assertEquals(0, schema.at("/properties/offset/minimum").asInt());
                    assertEquals(1000000, schema.at("/properties/offset/maximum").asInt());
                    assertEquals(1, schema.at("/properties/limit/minimum").asInt());
                    assertEquals(100, schema.at("/properties/limit/maximum").asInt());
                    var indexSchema = JSON.readTree(tool(request, "evidence_index").inputSchema());
                    assertEquals(400, indexSchema.at("/properties/offset/maximum").asInt());
                    assertEquals(50, indexSchema.at("/properties/limit/maximum").asInt());
                    return calls(turn, "contract", Map.of("path", "runtime/not-offered.sh", "offset", 0, "limit", 100));
                }
                if (turn == 1) {
                    assertTrue(lastResult(request).error());
                    assertTrue(lastResult(request).text().contains("Unsupported publication contract"));
                } else {
                    var result = lastResult(request);
                    assertFalse(result.error());
                    assertTrue(result.text().length() <= 6000);
                    var page = JSON.readTree(result.text().substring(result.text().indexOf('\n') + 1));
                    if (firstPage.get() == null)
                        firstPage.set(result.text().substring(result.text().indexOf('\n') + 1));
                    assertTrue(page.path("complete").asBoolean());
                    if (file.get() == selected.size()) {
                        assertEquals(page.path("totalLines").asInt(), page.path("offset").asInt());
                        assertEquals(0, page.path("returnedLines").asInt());
                        assertFalse(page.path("hasMore").asBoolean());
                        assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream())
                            .noneMatch(value -> value.text().startsWith("o2\n")), "The first contract body was evicted from the rolling transcript");
                        recovered.set(1);
                        return calls(turn, "observation", Map.of("id", "o2", "offset", 0, "limit", 6000));
                    }
                    assertEquals(selected.get(file.get()), page.path("path").asText());
                    assertEquals(offset.get(), page.path("offset").asInt());
                    page.path("lines").forEach(line -> actualLines.add(line.asText()));
                    assertEquals(page.path("lines").size(), page.path("returnedLines").asInt());
                    assertEquals(offset.get() + page.path("returnedLines").asInt(), page.path("nextLine").asInt());
                    pages.incrementAndGet();
                    if (page.path("hasMore").asBoolean()) offset.set(page.path("nextLine").asInt());
                    else {
                        try (var input = DeploymentAgentPublishRunner.class.getResourceAsStream(HELPER + selected.get(file.get()))) {
                            assertNotNull(input);
                            var expected = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate.render(
                                new String(input.readAllBytes(), StandardCharsets.UTF_8),
                                gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()).lines().toList();
                            assertEquals(expected, actualLines, selected.get(file.get()));
                            assertEquals(expected.size(), page.path("totalLines").asInt());
                        }
                        actualLines.clear();
                        offset.set(0);
                        file.incrementAndGet();
                    }
                }
                return calls(turn, "contract", Map.of("path", selected.get(Math.min(file.get(), selected.size() - 1)),
                    "offset", file.get() == selected.size() ? 1000000 : offset.get(), "limit", 100));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(11, file.get());
            assertTrue(pages.get() > 11, "Long contracts must expose and read their actual tails");
            assertEquals(0, frame.executed);
            assertEquals(0, frame.count("AI_PUBLICATION_ERROR"));
        }
    }

    @Test
    void originalObservationAndDuplicateContractKeepIdsAndNeverRecoverExcludedCredentials() throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> {
                var entries = state(request).path("judgement").path("evidenceIndex").path("entries");
                return switch (turn) {
                    case 0 -> calls(turn, "observation", Map.of("id", "o1", "offset", 0, "limit", 6000));
                    case 1 -> {
                        assertEquals(1, entries.size());
                        assertTrue(lastResult(request).text().startsWith("o1\no1 "));
                        assertTrue(lastResult(request).text().contains("exact sealed graph"));
                        yield calls(turn, "contract", Map.of("path", "input/17-managed-content.sh", "offset", 0, "limit", 100));
                    }
                    case 2 -> calls(turn, List.of(
                        new AiToolCall("recover-" + turn, "observation", JSON.writeValueAsString(Map.of("id", "o2", "offset", 0, "limit", 6000))),
                        new AiToolCall("repeat-" + turn, "contract", JSON.writeValueAsString(Map.of("path", "input/17-managed-content.sh", "offset", 0, "limit", 100)))));
                    case 3 -> {
                        assertEquals(2, entries.size());
                        assertTrue(lastResult(request).text().startsWith("o2\n"));
                        yield calls(turn, "input", Map.of("question", "Controlled nonsecret intent"));
                    }
                    case 4 -> {
                        assertEquals(3, entries.size());
                        assertFalse(lastResult(request).text().contains("synthetic-private-value"));
                        assertTrue(lastResult(request).text().contains("credential content excluded"));
                        yield calls(turn, "observation", Map.of("id", "o3", "offset", 0, "limit", 6000));
                    }
                    default -> {
                        assertEquals(3, entries.size());
                        assertTrue(lastResult(request).text().startsWith("o3\no3 "));
                        assertFalse(lastResult(request).text().contains("synthetic-private-value"));
                        yield calls(turn, "unable", Map.of("reason", "Controlled identity verification complete"));
                    }
                };
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(0, frame.executed);
        }
    }

    @Test
    void unstagedCommandIsRejectedBeforeTransportAndActualStagingAdvancesStateAndBudgets() throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> {
                var state = state(request);
                var budget = state.path("budget");
                assertEquals(turn + 1, budget.path("decisionsStarted").asInt());
                assertEquals(turn, budget.path("observedAttempts").asInt());
                assertEquals(78 - turn, budget.path("sharedRemainingAttempts").asInt());
                if (turn == 0) {
                    assertFalse(state.path("staged").asBoolean());
                    assertFalse(state.path("standardSent").asBoolean());
                    assertFalse(state.path("verified").asBoolean());
                    assertEquals(2, state.path("nextStep").path("routes").size());
                    return calls(turn, "command", assessed(Map.of("script", "printf controlled", "seconds", 1, "reason", "Controlled precondition"), "o1"));
                }
                if (turn == 1) {
                    assertEquals(0, frame.executed);
                    assertTrue(lastResult(request).error());
                    assertTrue(lastResult(request).text().contains("Stage exact publication inputs"));
                    return calls(turn, "stage", assessed(Map.of(), "o1"));
                }
                assertEquals("STAGED", state.path("stage").asText());
                assertTrue(state.path("staged").asBoolean());
                assertFalse(state.path("verified").asBoolean());
                assertEquals(2, frame.executed);
                assertEquals(2, frame.approvals);
                return calls(turn, "unable", Map.of("reason", "Controlled staging verification complete"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(0, frame.releases);
        }
    }

    @Test
    void changedModelRouteStillHardStopsAndRecordsOnlyControlledCause() throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> {
                frame.modelRevision = "b".repeat(64);
                return calls(turn, "contract", Map.of("path", "00-protocol-foundation.sh", "offset", 0, "limit", 100));
            };
            var failure = assertThrows(SecurityException.class, () -> frame.run(78));
            assertEquals("Native model route changed", failure.getMessage());
            assertEquals("MODEL_ROUTE_CHANGED", frame.detail("AI_PUBLICATION_ERROR_CAUSE"));
            assertEquals(0, frame.executed);
        }
    }

    @Test
    void defaultSelectionReviewKeepsCommandRedactionAndRejectedCallsConsumeActualAttempts() throws Exception {
        try (var frame = new Frame()) {
            var review = new DeploymentModelToolLoop.SelectionReview() {
                public boolean required(String name) {
                    return false;
                }

                public void assess(String name, JsonNode summary) {
                }

                public String observed(String name, String output) {
                    return "o1";
                }

                public Map<String, Object> state() {
                    return Map.of();
                }

                public String observation(String id, int offset, int limit) {
                    return "";
                }

                public Map<String, Object> evidenceIndex(int offset, int limit) {
                    return Map.of();
                }
            };
            var loop = new DeploymentModelToolLoop<String>(frame.context, frame.task, REVISION, "Controlled default feedback", 5, review);
            frame.script = (request, turn) -> {
                if (turn == 0)
                    return calls(turn, List.of(new AiToolCall("unknown", "unavailable", "{}"), new AiToolCall("command", "command", "{}")));
                assertEquals(2, loop.budgetState().get("observedAttempts"));
                assertEquals(3, loop.budgetState().get("remainingAttempts"));
                assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).anyMatch(AiToolResult::error));
                assertFalse(lastResult(request).text().contains("synthetic-private-value"));
                assertTrue(lastResult(request).text().contains("credential content excluded"));
                return calls(turn, "end", Map.of());
            };
            var result = loop.run(() -> Map.of("command", new DeploymentModelToolLoop.Tool<>("Controlled command feedback", args ->
                    DeploymentModelToolLoop.Feedback.observed("secret=synthetic-private-value\npublic result")), "end",
                new DeploymentModelToolLoop.Tool<>("Controlled end", args -> DeploymentModelToolLoop.Feedback.ended(success("done")))), Map::of);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
        }
    }

    private static JsonNode state(AiRequest request) throws Exception {
        return JSON.readTree(request.messages().getLast().text()).path("state");
    }

    private static AiToolDefinition tool(AiRequest request, String name) {
        return request.tools().stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
    }

    private static AiToolResult lastResult(AiRequest request) {
        return request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((first, last) -> last).orElseThrow();
    }

    private static Map<String, Object> assessed(Map<String, Object> args, String id) {
        var value = new HashMap<String, Object>(args);
        value.put("assessment", Map.of("basis", List.of(id), "suitability", "Controlled applicability",
            "differences", "No unresolved differences", "resolution", "CONSISTENT", "decision", "Select controlled action"));
        return value;
    }

    private static AiResponse calls(int turn, String name, Map<String, ?> args) throws Exception {
        return calls(turn, List.of(new AiToolCall("call-" + turn, name, JSON.writeValueAsString(args))));
    }

    private static AiResponse calls(int turn, List<AiToolCall> calls) {
        return new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls, Optional.empty());
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    @FunctionalInterface
    private interface Script {
        AiResponse reply(AiRequest request, int turn) throws Exception;
    }

    private static final class Frame implements AutoCloseable {
        private final UUID taskId = UUID.randomUUID();

        private final String jdbc = "jdbc:h2:mem:" + UUID.randomUUID();

        private final Connection retained = DriverManager.getConnection(jdbc);

        private final ServerId server = new ServerId("controlled-publish");

        private final AtomicInteger decisions = new AtomicInteger();

        private final TaskContext task;

        private final ModContext context;

        private final DeploymentAgentActionLog journal;

        private final RemoteSession session;

        private Script script;

        private String modelRevision = REVISION;

        private int executed;

        private int approvals;

        private int releases;

        Frame() throws Exception {
            try (var sql = retained.createStatement()) {
                sql.execute("CREATE TABLE deployment_agent_task(task_id VARCHAR(36) PRIMARY KEY)");
                sql.execute("CREATE TABLE deployment_agent_event(event_id BIGINT GENERATED BY DEFAULT AS IDENTITY,task_id VARCHAR(36),action_id VARCHAR(128),event_type VARCHAR(48),event_detail VARCHAR(4096),created_at BIGINT)");
                sql.execute("INSERT INTO deployment_agent_task VALUES('" + taskId + "')");
            }
            task = proxy(TaskContext.class, (method, args) -> switch (method) {
                case "id" -> taskId;
                case "cancelToken" -> (CancelToken) () -> false;
                case "progress" -> null;
                default -> throw new AssertionError(method);
            });
            var db = proxy(ModDatabase.class, (method, args) -> DriverManager.getConnection(jdbc));
            journal = new DeploymentAgentActionLog(db);
            var ai = proxy(AiService.class, (method, args) -> method.equals("configurationRevision") ? modelRevision
                : success(script.reply((AiRequest) args[0], decisions.getAndIncrement())));
            var interaction = proxy(InteractionService.class, (method, args) -> {
                if (method.equals("approve")) {
                    approvals++;
                    return success(new ApprovalGrant(UUID.randomUUID()));
                }
                if (method.equals("requestText")) return success("secret=synthetic-private-value\npublic intent");
                throw new AssertionError(method);
            });
            var tasks = proxy(TaskService.class, (method, args) -> {
                assertEquals("findCheckpoint", method);
                return Optional.empty();
            });
            var logs = proxy(LogService.class, (method, args) -> method.equals("register") ? new LogTarget(UUID.randomUUID()) : null);
            var ui = proxy(UiService.class, (method, args) -> {
                assertEquals("environment", method);
                return new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false);
            });
            context = proxy(ModContext.class, (method, args) -> switch (method) {
                case "ai" -> ai;
                case "db" -> db;
                case "interaction" -> interaction;
                case "tasks" -> tasks;
                case "logs" -> logs;
                case "ui" -> ui;
                default -> throw new AssertionError(method);
            });
            session = proxy(RemoteSession.class, (method, args) -> {
                assertEquals("execute", method);
                executed++;
                return success(new CommandResult(Optional.of(0), "INPUT_STAGED=1\n", "", false));
            });
        }

        OperationResult<DeploymentPublishResult> run(int budget) throws Exception {
            var unit = DeploymentUnitIdentity.forManaged("controlled", server, "b".repeat(64));
            var health = new DeploymentHealthCheck.Process(10, 1);
            var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true,
                new DeploymentCommand("run.sh", List.of()), "", List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
            var runtime = new DeploymentRuntimeDefinition.ManagedProcess(health, DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            var config = new DeploymentUnitRuntimeConfig(health, Optional.empty(), DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            var graph = new DeploymentApplicationGraph("controlled", "component", health, List.of(new DeploymentApplicationGraph.Part(
                "component", unit, config, List.of(), runtime, List.of(), new DeploymentResourceBindings(List.of(), Optional.of(List.of())))));
            var snapshot = DeploymentConfigSnapshot.create(unit.id(), 1, "v1", Instant.EPOCH, List.of());
            var proposal = new DeploymentPublishResult(graph, List.of(new DeploymentUnitCommit(unit, config,
                new DeploymentCurrentRelease(unit.id(), "c".repeat(64), Instant.EPOCH), snapshot, List.of())));
            var candidate = new DeploymentGraphPublisher.Candidate("controlled-0123456789abcdef", "d".repeat(64), "e".repeat(64), "0".repeat(64), false, Map.of());
            try (var shared = DeploymentToolBudget.open(80)) {
                assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("outer-1", "read", "{}"), new AiToolCall("outer-2", "deliver", "{}"))));
                return DeploymentAgentPublishRunner.run(context, session, server, task, REVISION, proposal, Map.of("component", candidate),
                    journal, DeploymentSendGuard.NONE, budget, () -> releases++);
            }
        }

        int count(String type) throws Exception {
            return journal.events(task).stream().filter(event -> event.type().equals(type)).toList().size();
        }

        String detail(String type) throws Exception {
            return journal.events(task).stream().filter(event -> event.type().equals(type)).findFirst().orElseThrow().detail();
        }

        public void close() throws SQLException {
            retained.close();
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object run(String method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invoke) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> invoke.run(method.getName(), args)));
    }
}
