package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real tool loop and durable original evidence, with isolated H2 and controlled host replies. */
class DeploymentAgentFailurePublicationTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static final String REVISION = "a".repeat(64);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshModelResponseAcceptsOnlyActualNativeFailureAndRetainsKnownPriorCli(boolean priorCli) throws Exception {
        try (var frame = new Frame()) {
            if (priorCli) frame.command("ENDED");
            frame.script = (request, turn) -> {
                if (turn == 0) return call(request, turn, "publish", Map.of());
                var receipt = lastResult(request);
                assertFalse(receipt.error());
                var observation = JSON.readTree(receipt.text().substring(receipt.text().indexOf('{')));
                assertEquals("FAILED", observation.path("outcome").asText());
                assertTrue(observation.path("rollbackConfirmed").asBoolean());
                assertEquals(64, observation.at("/healthReceipts/0/exit").asInt());
                assertEquals("RESTORED", frame.journal.events(frame.task).stream()
                    .filter(event -> event.type().equals("GRAPH_ROLLBACK_RESULT")).findFirst().orElseThrow().detail());
                return call(request, turn, "finish_failure", Map.of("reason", "Accept the actual original native failure"));
            };
            var result = frame.run(20);
            assertEquals(TaskResult.FAILED, result.outcome());
            assertEquals("DEPLOYMENT_HEALTH_FAILED", result.problem().orElseThrow().eventCode());
            assertNotNull(frame.accepted.get());
            assertEquals(1, frame.count(DeploymentAgentFailedPublication.EVENT));
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
            assertEquals(6, frame.executed);
            assertEquals(0, frame.resourceReleases, "Cleanup is a later Session boundary, after DELIVERY_RESULT");
            assertFalse(DeploymentAgentActionLog.effectsSettled(frame.journal.events(frame.task)));
            if (priorCli) assertTrue(frame.commandSnapshot.get().released());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-assessment", "missing-current-basis", "later-stage", "later-command", "unsettled-effect", "foreign-rollback", "older-rollback"})
    void unsafeAcceptanceRemainsUnknownWithoutClaimingFinish(String mode) throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> {
                if (turn == 0) return call(request, turn, "publish", Map.of());
                if (turn == 1) {
                    if (mode.equals("later-stage")) return call(request, turn, "stage", Map.of());
                    if (mode.equals("later-command")) frame.command("ENDED");
                    if (mode.equals("unsettled-effect"))
                        frame.journal.event(frame.task, "CAPABILITY_EXECUTING", "different-effect", "1");
                    if (mode.equals("foreign-rollback"))
                        frame.sql("UPDATE deployment_agent_event SET action_id='foreign' WHERE event_type='GRAPH_ROLLBACK_RESULT'");
                    if (mode.equals("older-rollback"))
                        frame.journal.event(frame.task, "GRAPH_ROLLBACK_RESULT", "controlled", "RESTORED");
                    if (mode.equals("missing-assessment"))
                        return calls(turn, "finish_failure", Map.of("reason", "False success text cannot establish failure"));
                    if (mode.equals("missing-current-basis"))
                        return calls(turn, "finish_failure", Map.of("reason", "Prior observation is insufficient", "assessment", assessment(List.of("o1"))));
                }
                if (turn == 1 || turn == 2 && mode.equals("later-stage"))
                    return call(request, turn, "finish_failure", Map.of("reason", "Accept only if this current boundary is safe"));
                assertTrue(lastResult(request).error());
                return calls(turn, "unable", Map.of("reason", "Retain unconfirmed attempt"));
            };
            if (Set.of("later-command", "unsettled-effect", "foreign-rollback", "older-rollback").contains(mode))
                assertThrows(IllegalStateException.class, () -> frame.run(20));
            else assertEquals(TaskResult.UNKNOWN, frame.run(20).outcome());
            assertNull(frame.accepted.get());
            assertEquals(0, frame.count(DeploymentAgentFailedPublication.EVENT));
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
            assertEquals(0, frame.resourceReleases);
        }
    }

    @Test
    void falseReasonBeforePublicationGrantsNoFailureEligibility() throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> turn == 0
                ? call(request, turn, "finish_failure", Map.of("reason", "FAILED RESTORED SUCCEEDED all resources cleaned"))
                : calls(turn, "unable", Map.of("reason", "No actual native failure"));
            assertEquals(TaskResult.UNKNOWN, frame.run(10).outcome());
            assertEquals(0, frame.executed);
            assertEquals(0, frame.count(DeploymentAgentFailedPublication.EVENT));
        }
    }

    @Test
    void unknownRollbackEndsBeforeModelCanAcceptFailure() throws Exception {
        try (var frame = new Frame()) {
            frame.rollbackUnknown = true;
            frame.script = (request, turn) -> call(request, turn, "publish", Map.of());
            assertEquals(TaskResult.UNKNOWN, frame.run(10).outcome());
            assertEquals(1, frame.decisions);
            assertEquals(0, frame.count(DeploymentAgentFailedPublication.EVENT));
            assertNull(frame.accepted.get());
        }
    }

    @Test
    void exhaustedExistingBudgetCannotAcceptOrResetFailure() throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> call(request, turn, "publish", Map.of());
            assertThrows(IllegalStateException.class, () -> frame.run(1));
            assertEquals(1, frame.decisions);
            assertEquals(0, frame.count(DeploymentAgentFailedPublication.EVENT));
            assertEquals(0, frame.resourceReleases);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHED", "INTENT"})
    void unsupportedPriorCommandPreventsFailureAcceptance(String phase) throws Exception {
        try (var frame = new Frame()) {
            frame.command(phase);
            frame.script = (request, turn) -> call(request, turn, turn == 0 ? "publish" : "finish_failure",
                turn == 0 ? Map.of() : Map.of("reason", "Unsupported command cannot establish failure eligibility"));
            try {
                assertEquals(TaskResult.UNKNOWN, frame.run(10).outcome());
            } catch (IllegalStateException unavailable) {
                assertEquals(phase.equals("DISPATCHED") ? "Model command remains unknown"
                    : "Failure settlement supports only unchanged known ENDED host commands", unavailable.getMessage());
            }
            assertEquals(6, frame.executed);
            assertEquals(1, frame.count("GRAPH_PROPOSAL"));
            assertNull(frame.accepted.get());
        }
    }

    @Test
    void knownUnsentPriorIntentDoesNotBlockIndependentSuccessfulNativePublication() throws Exception {
        try (var frame = new Frame()) {
            frame.command("INTENT");
            frame.healthyNative = true;
            frame.script = (request, turn) -> {
                if (turn == 0) return call(request, turn, "publish", Map.of());
                var receipt = lastResult(request);
                assertFalse(receipt.error());
                var observation = JSON.readTree(receipt.text().substring(receipt.text().indexOf('{')));
                assertEquals("SUCCEEDED", observation.path("outcome").asText());
                assertFalse(observation.path("rollbackConfirmed").asBoolean());
                return calls(turn, "unable", Map.of("reason", "Controlled publication reachability check only; independent finish is still required"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(10).outcome());
            assertEquals(1, frame.count("GRAPH_PROPOSAL"));
            assertEquals(0, frame.count("GRAPH_ROLLBACK_RESULT"));
            assertEquals(0, frame.count(DeploymentAgentFailedPublication.EVENT));
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
            assertTrue(frame.commandSnapshot.get().released());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"database-missing", "secret-missing", "database-query-unknown", "secret-query-unknown", "database-foreign-server", "database-foreign-manifest"})
    void indexedResourceAbsenceOrUnknownQueryCannotBeAccepted(String mode) throws Exception {
        try (var frame = new Frame()) {
            String key = mode.startsWith("database") ? "deployment-database-plan." + "1".repeat(32)
                : "deployment-secret-revision." + "1".repeat(32);
            frame.journal.event(frame.task, mode.startsWith("database") ? "AGENT_DATABASE_INDEX" : "AGENT_SECRET_INDEX", key,
                mode.startsWith("database") ? "e".repeat(64) + ":" + "6".repeat(64) : "6".repeat(64) + ":deployment-runtime");
            if (mode.endsWith("query-unknown")) frame.unknownCheckpoint = key;
            if (mode.startsWith("database-foreign")) {
                var facts = JSON.createObjectNode().put("formatVersion", 1).put("taskId", frame.taskId.toString())
                    .put("serverId", mode.endsWith("server") ? "foreign" : frame.server.id())
                    .put("manifestSha256", mode.endsWith("manifest") ? "5".repeat(64) : "e".repeat(64));
                facts.putArray("keys").add("1".repeat(32));
                frame.extraCheckpoints.put(key, proxy(TaskCheckpoint.class, (name, args) -> {
                    assertEquals("snapshot", name);
                    return new TaskCheckpoint.Snapshot(1, facts.toString(), false);
                }));
            }
            frame.script = (request, turn) -> call(request, turn, turn == 0 ? "publish" : "finish_failure",
                turn == 0 ? Map.of() : Map.of("reason", "Original indexed work must still be retained"));
            var stopped = assertThrows(Exception.class, () -> frame.run(10));
            if (mode.endsWith("query-unknown"))
                assertEquals("CONTROLLED_CHECKPOINT_READ_UNKNOWN", assertInstanceOf(ApiException.class, stopped).problem().eventCode());
            else assertInstanceOf(IllegalStateException.class, stopped);
            assertNull(frame.accepted.get());
            assertEquals(0, frame.count(DeploymentAgentFailedPublication.EVENT));
            assertEquals(0, frame.resourceReleases);
            assertEquals(6, frame.executed);
        }
    }

    static Map<String, Object> assessment(List<String> basis) {
        return Map.of("basis", basis, "suitability", "Actual controlled evidence", "differences", "No unresolved current differences",
            "resolution", "CONSISTENT", "decision", "Select this current action");
    }

    static AiResponse call(AiRequest request, int turn, String name, Map<String, ?> args) throws Exception {
        var basis = new ArrayList<String>();
        JSON.readTree(request.messages().getLast().text()).at("/state/judgement/pending").forEach(value -> basis.add(value.asText()));
        var values = new HashMap<String, Object>(args);
        values.put("assessment", assessment(basis));
        return calls(turn, name, values);
    }

    static AiResponse calls(int turn, String name, Map<String, ?> args) throws Exception {
        return new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall("call-" + turn, name, JSON.writeValueAsString(args))), Optional.empty());
    }

    static AiToolResult lastResult(AiRequest request) {
        return request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((first, last) -> last).orElseThrow();
    }

    @FunctionalInterface
    interface Script {
        AiResponse reply(AiRequest request, int turn) throws Exception;
    }

    static final class Frame implements AutoCloseable {
        final UUID taskId = UUID.randomUUID();

        final String jdbc = "jdbc:h2:mem:" + UUID.randomUUID();

        final Connection retained = DriverManager.getConnection(jdbc);

        final ServerId server = new ServerId("controlled-publish");

        final TaskContext task;

        final ModContext context;

        final RemoteSession session;

        final DeploymentAgentActionLog journal;

        final DeploymentExecutionStore executions;

        final DeploymentExecutionIdentity execution;

        final DeploymentPublishResult proposal;

        final Map<String, DeploymentGraphPublisher.Candidate> candidates;

        final AtomicReference<DeploymentAgentFailedPublication> accepted = new AtomicReference<>();

        final AtomicReference<TaskCheckpoint.Snapshot> commandSnapshot = new AtomicReference<>();

        final Map<String, TaskCheckpoint> extraCheckpoints = new HashMap<>();

        TaskCheckpoint commandCheckpoint;

        Script script;

        int decisions, executed, resourceReleases;

        int nativeDatabaseStatusQueries, secretReferenceQueries, indexedCloses;

        boolean rollbackUnknown;

        boolean nativeDatabaseRecovery;

        boolean healthyNative;

        String unknownCheckpoint;

        Frame() throws Exception {
            try (var source = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                "/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), StandardCharsets.UTF_8)) {
                org.h2.tools.RunScript.execute(retained, source);
            }
            task = proxy(TaskContext.class, (name, args) -> switch (name) {
                case "id" -> taskId;
                case "cancelToken" -> (CancelToken) () -> false;
                case "progress" -> null;
                default -> throw new AssertionError(name);
            });
            var db = proxy(ModDatabase.class, (name, args) -> name.equals("identity")
                ? new SchemaIdentity(DeploymentSchemaIdentity.OWNER, DeploymentSchemaIdentity.VERSION, taskId) : DriverManager.getConnection(jdbc));
            journal = new DeploymentAgentActionLog(db);
            executions = new DeploymentExecutionStore(db);
            var ai = proxy(AiService.class, (name, args) -> name.equals("configurationRevision") ? REVISION
                : success(script.reply((AiRequest) args[0], decisions++)));
            var interaction = proxy(InteractionService.class, (name, args) -> {
                assertEquals("approve", name);
                return success(new ApprovalGrant(UUID.randomUUID()));
            });
            var tasks = proxy(TaskService.class, (name, args) -> {
                assertEquals("findCheckpoint", name);
                if (Objects.equals(unknownCheckpoint, args[1]))
                    throw new ApiException(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_CHECKPOINT_READ_UNKNOWN", "Controlled original indexed resource read is unknown", Map.of(), Optional.of(taskId)));
                return DeploymentModelCommandRunner.KEY.equals(args[1]) ? Optional.ofNullable(commandCheckpoint)
                    : Optional.ofNullable(extraCheckpoints.get(args[1]));
            });
            var logs = proxy(LogService.class, (name, args) -> name.equals("register") ? new LogTarget(UUID.randomUUID()) : null);
            var ui = proxy(UiService.class, (name, args) -> new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            context = proxy(ModContext.class, (name, args) -> switch (name) {
                case "db" -> db;
                case "ai" -> ai;
                case "interaction" -> interaction;
                case "tasks" -> tasks;
                case "logs" -> logs;
                case "ui" -> ui;
                case "secrets" ->
                    proxy(gold.debug.windowstolinux.appapi.secret.SecretService.class, (operation, values) -> {
                        assertEquals("findReference", operation);
                        secretReferenceQueries++;
                        throw new ApiException(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_SECRET_REFERENCE_UNKNOWN", "Original secret reference query is unknown", Map.of(), Optional.of(taskId)));
                    });
                default -> throw new AssertionError(name);
            });
            session = proxy(RemoteSession.class, (name, args) -> {
                if (name.equals("facts")) return new RemoteFacts("Linux", "amd64", "SHA256:controlled");
                assertEquals("execute", name);
                executed++;
                var request = (CommandRequest) args[0];
                if (nativeDatabaseRecovery) {
                    if (request.script().contains("native-db status")) {
                        nativeDatabaseStatusQueries++;
                        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                            "CONTROLLED_NATIVE_DATABASE_STATUS_UNKNOWN", "Original native database status query is unknown", Map.of(), Optional.of(taskId))));
                    }
                    assertTrue(request.script().contains("' probe\n"));
                    return success(new CommandResult(Optional.of(0), "HELPER=1\nPROTOCOL="
                        + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PROTOCOL_VERSION
                        + "\nSHA256=" + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n", "", false));
                }
                String output;
                if (request.script().contains("'application-health'")) {
                    if (healthyNative) return success(new CommandResult(Optional.of(0), "HEALTHY=1\n", "", false));
                    return success(new CommandResult(Optional.of(64), "", "Controlled native health failure\n", false));
                }
                if (request.script().contains("'rollback-deployment-first'")) {
                    if (rollbackUnknown)
                        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                            "CONTROLLED_ROLLBACK_UNKNOWN", "Original rollback reply is unknown", Map.of(), Optional.of(taskId))));
                    output = "ROLLED_BACK=1\n";
                } else if (request.script().contains("'snapshot-deployment'")) output = "PREVIOUS=0\n";
                else if (request.script().contains("'publish-deployment'")) output = "PUBLISHED=1\n";
                else output = "INPUT_STAGED=1\n";
                return success(new CommandResult(Optional.of(0), output, "", false));
            });
            var unit = DeploymentUnitIdentity.forManaged("controlled", server, "b".repeat(64));
            var health = new DeploymentHealthCheck.Process(10, 1);
            var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true,
                new DeploymentCommand("run.sh", List.of()), "", List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
            var runtime = new DeploymentRuntimeDefinition.ManagedProcess(health, DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            var config = new DeploymentUnitRuntimeConfig(health, Optional.empty(), DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            var graph = new DeploymentApplicationGraph("controlled", "component", health, List.of(new DeploymentApplicationGraph.Part(
                "component", unit, config, List.of(), runtime, List.of(), new DeploymentResourceBindings(List.of(), Optional.of(List.of())))));
            proposal = new DeploymentPublishResult(graph, List.of(new DeploymentUnitCommit(unit, config,
                new DeploymentCurrentRelease(unit.id(), "c".repeat(64), Instant.EPOCH), DeploymentConfigSnapshot.create(unit.id(), 1, "v1", Instant.EPOCH, List.of()), List.of())));
            candidates = Map.of("component", new DeploymentGraphPublisher.Candidate("controlled-0123456789abcdef", "d".repeat(64), "e".repeat(64), "0".repeat(64), false, Map.of()));
            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), DeploymentAgentInputs.targetDigest(server, session), REVISION, "MANUAL_REVIEW", true));
            journal.event(task, "SOURCE", "f".repeat(64), "e".repeat(64));
            journal.event(task, "TARGET_PROFILE", "", "b".repeat(64));
            journal.event(task, "DELIVERY_EXECUTING", "9".repeat(64), "1");
            execution = new DeploymentExecutionIdentity(UUID.randomUUID(), taskId, Optional.empty(), "controlled", server,
                DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "8".repeat(64));
            var references = gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit.references(server, proposal, Map.of());
            executions.begin(task, execution, "deployment-agent", references, Optional.empty(), Instant.EPOCH);
            for (var reference : references)
                executions.recordReference(task, execution.executionId(), reference, UUID.randomUUID(), Instant.EPOCH);
            executions.startEffects(task, execution.executionId(), Instant.EPOCH);
            executions.recordPublicationTemplate(task, execution.executionId(), new DeploymentPublishProposal(graph, proposal.units()), Instant.EPOCH);
        }

        void command(String phase) throws Exception {
            var node = JSON.createObjectNode().put("version", 1).put("task", taskId.toString()).put("server", server.id())
                .put("fingerprint", session.facts().fingerprint()).put("modelRevision", REVISION);
            var item = node.putArray("commands").addObject().put("operation", UUID.randomUUID().toString()).put("body", "7".repeat(64)).put("seconds", 1).put("phase", phase);
            if (phase.equals("ENDED")) item.put("exit", 0);
            long next = commandSnapshot.get() == null ? 1 : commandSnapshot.get().revision() + 1;
            commandSnapshot.set(new TaskCheckpoint.Snapshot(next, node.toString(), false));
            commandCheckpoint = proxy(TaskCheckpoint.class, (name, args) -> {
                if (name.equals("snapshot")) return commandSnapshot.get();
                assertEquals("close", name);
                var before = commandSnapshot.get();
                commandSnapshot.set(new TaskCheckpoint.Snapshot(before.revision(), before.payload(), true));
                return null;
            });
        }

        OperationResult<DeploymentPublishResult> run(int budget) throws Exception {
            try (var allowance = DeploymentToolBudget.open(budget)) {
                return DeploymentAgentPublishRunner.run(context, session, server, task, REVISION, proposal, candidates,
                    journal, DeploymentSendGuard.NONE, budget, () -> resourceReleases++, accepted::set);
            }
        }

        void sql(String sql) throws SQLException {
            try (var statement = retained.createStatement()) {
                statement.executeUpdate(sql);
            }
        }

        void retainIndexed(String key, String payload) {
            extraCheckpoints.put(key, proxy(TaskCheckpoint.class, (name, args) -> {
                if (name.equals("snapshot")) return new TaskCheckpoint.Snapshot(1, payload, false);
                assertEquals("close", name);
                indexedCloses++;
                throw new AssertionError("Unknown indexed work must remain retained");
            }));
        }

        long count(String type) throws Exception {
            return journal.events(task).stream().filter(event -> event.type().equals(type)).count();
        }

        public void close() throws SQLException {
            retained.close();
        }
    }

    static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    @FunctionalInterface
    interface Invocation {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> invocation.invoke(method.getName(), args)));
    }
}
