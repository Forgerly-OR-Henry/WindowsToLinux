package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.io.InputStreamReader;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.cleanup.DeploymentCandidateCompensationProgram;
import gold.debug.windowstolinux.mod.deployment.linux.cleanup.DeploymentCandidateCompensationProgram.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentCandidateCompensation;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishActionLog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real fixed protocol, assisted scope and independent model boundary; no remote capability exists. */
class DeploymentAssistedCompensationReviewContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHILD = "deployment-publication-unit-00";
    private static final String PLAN = "b".repeat(64), ROOT = "c".repeat(64), ARCHIVE = "a".repeat(64), OWNER = "d".repeat(64);

    @ParameterizedTest
    @EnumSource(Mode.class)
    void allModesReachIndependentReviewWithCompleteInputAndOriginalBindingAndDenialIsFinal(Mode mode) throws Exception {
        try (var h = new Harness(mode); var command = DeploymentCandidateCompensationProgram.command(h.request)) {
            var intent = DeploymentCandidateCompensationProgram.intent(h.server, h.request, command);
            assertEquals("exec /usr/bin/python3 -I -", command.script());
            assertDoesNotThrow(() -> DeploymentCommandStaticGate.requireFixedAllowed(command.script()));
            assertTrue(DeploymentFixedCommandProtocol.registeredOperation(mode.operation()));
            var before = h.snapshots();
            var protocol = JSON.valueToTree(new DeploymentFixedCommandProtocol(h.scope)
                .inspect(intent, command, h.layout, h.fixture.task));
            assertEquals(mode.wire(), protocol.at("/candidateCompensation/mode").asText());
            h.reply("DENY");
            var failure = assertThrows(ApiException.class, () -> h.approve(intent, command));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", failure.problem().eventCode());
            assertEquals(1, h.fixture.requests.size());
            var reviewed = JSON.readTree(h.fixture.requests.getFirst().messages().getLast().text());
            var facts = reviewed.at("/currentCommandEvidence/originalCandidateCompensation");
            assertEquals(h.binding.json(), facts.path("binding"));
            assertEquals(h.binding.sha256(), facts.path("bindingSha256").asText());
            assertEquals(JSON.readTree(h.author.get(CHILD).snapshot.payload()), facts.path("originalChild"));
            assertEquals(JSON.readTree(h.author.get(DeploymentStandardGraphPublishActionLog.KEY).snapshot.payload()), facts.path("originalGraph"));
            assertEquals(DeploymentModelCommandIdentity.digest(new String(command.stdin(), StandardCharsets.UTF_8)),
                facts.path("completeStdinSha256").asText());
            assertEquals(DeploymentCandidateCompensationProgram.bodySha256(), facts.path("guardSha256").asText());
            assertEquals(1000, facts.path("originalExecutionCreatedAtEpochMillis").longValue());
            if (mode == Mode.EXECUTE) assertEquals("OBSERVED", facts.path("observerResponse").path("status").asText());
            assertTrue(containsText(reviewed, new String(command.stdin(), StandardCharsets.UTF_8)),
                "Independent model must receive the complete executable stdin, not only its digest");
            assertTrue(containsText(reviewed, command.script()), "Independent model must receive the exact launcher");
            assertEquals(before, h.snapshots());
            assertEquals(0, h.hostCalls);
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void genericApprovalCannotBypassCompleteCommandReview(Mode mode) throws Exception {
        try (var h = new Harness(mode); var command = DeploymentCandidateCompensationProgram.command(h.request)) {
            var intent = DeploymentCandidateCompensationProgram.intent(h.server, h.request, command);
            var failure = assertThrows(ApiException.class, () -> h.scope.interaction().approve(intent, h.fixture.task));
            assertEquals("DEPLOYMENT_ASSISTED_COMPENSATION_COMMAND_REQUIRED", failure.problem().eventCode());
            assertTrue(h.fixture.requests.isEmpty());
            assertEquals(0, h.hostCalls);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"STDIN", "LAYOUT", "GRAPH", "CHILD", "RECOVERY", "TASK_TARGET", "TASK_OBJECT", "CONFIRMATION", "STAGE", "GUARD", "EPOCH", "OBSERVER"})
    void changedOriginalEvidenceIsRejectedBeforeModelOrHost(String mutation) throws Exception {
        try (var h = new Harness(Mode.EXECUTE); var original = DeploymentCandidateCompensationProgram.command(h.request)) {
            h.mutate(mutation);
            try (var command = new CommandRequest(original.script(),
                (new String(original.stdin(), StandardCharsets.UTF_8) + (mutation.equals("STDIN") ? "\n" : ""))
                    .getBytes(StandardCharsets.UTF_8), original.timeout(), original.outputLimitBytes())) {
                var intent = DeploymentCandidateCompensationProgram.intent(h.server, h.request, command);
                if (mutation.equals("TASK_TARGET")) {
                    var targets = new HashSet<>(intent.semantics().orElseThrow().targets());
                    targets.removeIf(value -> value.kind().equals("task"));
                    targets.add(new ActionTarget(h.server.id(), "task", UUID.randomUUID().toString()));
                    intent = intent.withSemantics(new ActionSemantics(h.request.mode().operation(), targets));
                }
                var actualIntent = intent;
                if (mutation.equals("TASK_OBJECT")) {
                    TaskContext other = proxy(TaskContext.class, (p, m, a) -> h.fixture.task.id());
                    assertThrows(IllegalArgumentException.class,
                        () -> ((DeploymentCommandReview) h.scope.interaction()).approveCommand(actualIntent, command, other));
                } else {
                    var failure = assertThrows(ApiException.class, () -> h.approve(actualIntent, command));
                    assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", failure.problem().eventCode());
                }
            }
            assertTrue(h.fixture.requests.isEmpty());
            assertEquals(0, h.hostCalls);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GRAPH", "CHILD", "LAYOUT", "RECOVERY"})
    void modelApprovalCannotGrantAuthorityAfterOriginalEvidenceChangesDuringReview(String mutation) throws Exception {
        try (var h = new Harness(Mode.EXECUTE); var command = DeploymentCandidateCompensationProgram.command(h.request)) {
            h.duringReview = mutation;
            h.reply("APPROVE");
            var intent = DeploymentCandidateCompensationProgram.intent(h.server, h.request, command);
            var failure = assertThrows(ApiException.class, () -> h.approve(intent, command));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", failure.problem().eventCode());
            assertEquals(1, h.fixture.requests.size());
            assertEquals(0, h.hostCalls);
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void validIndependentApprovalObtainsOnlyTheExactOriginalHostGrant(Mode mode) throws Exception {
        try (var h = new Harness(mode); var command = DeploymentCandidateCompensationProgram.command(h.request)) {
            var intent = DeploymentCandidateCompensationProgram.intent(h.server, h.request, command);
            h.expectedIntent = intent;
            h.expectedCommand = command;
            h.reply("APPROVE");
            assertEquals(Optional.of(h.grant), h.approve(intent, command).value());
            assertEquals(1, h.fixture.requests.size());
            assertEquals(1, h.hostCalls);
        }
    }

    @Test
    void soleSuccessorCanIndependentlyReviewOnlyTheOldOperationsReadOnlyReceiptBeforePreparation() throws Exception {
        try (var h = new Harness(Mode.RECEIPT)) {
            h.successorReceipt();
            try (var command = DeploymentCandidateCompensationProgram.command(h.request)) {
                var intent = DeploymentCandidateCompensationProgram.intent(h.server, h.request, command);
                assertTrue(intent.semantics().orElseThrow().targets().stream().noneMatch(target -> target.kind().equals("filesystem-write")));
                h.expectedIntent = intent; h.expectedCommand = command; h.reply("APPROVE");
                assertEquals(Optional.of(h.grant), h.approve(intent, command).value());
                var reviewed = JSON.readTree(h.fixture.requests.getFirst().messages().getLast().text());
                var facts = reviewed.at("/currentCommandEvidence/originalCandidateCompensation");
                assertTrue(facts.path("verifiesPredecessorReceiptOnly").booleanValue());
                assertEquals("VERIFY_PREDECESSOR", facts.path("stage").asText());
                assertEquals(h.binding.operationId().toString(), facts.path("binding").path("operationId").asText());
                assertEquals("EXTERNAL_EXECUTION_SOURCE_ATTESTATION_REQUIRED", facts.path("predecessor").path("kind").asText());
                assertEquals("NOT_ATTESTED", facts.path("legacyExecutionSource").asText());
                assertEquals(1, h.hostCalls);
            }
        }
    }

    @Test
    void predecessorByteChangeDuringIndependentReviewCannotObtainTheHostGrant() throws Exception {
        try (var h = new Harness(Mode.RECEIPT)) {
            h.successorReceipt(); h.duringReview = "PREDECESSOR_BYTES"; h.reply("APPROVE");
            try (var command = DeploymentCandidateCompensationProgram.command(h.request)) {
                var intent = DeploymentCandidateCompensationProgram.intent(h.server, h.request, command);
                var failure = assertThrows(ApiException.class, () -> h.approve(intent, command));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", failure.problem().eventCode());
                assertEquals(1, h.fixture.requests.size());
                assertEquals(0, h.hostCalls);
            }
        }
    }

    private static final class Harness implements AutoCloseable {
        final NativeAiFixture fixture = new NativeAiFixture();
        final ServerId server = new ServerId("compensation-review-server");
        final UUID generation = UUID.randomUUID(), operation = UUID.randomUUID();
        final String jdbc = "jdbc:h2:mem:compensation-review-" + generation;
        final Connection retained = DriverManager.getConnection(jdbc);
        final Map<String, Checkpoint> author = new HashMap<>(), recovery = new HashMap<>();
        final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
        final Binding binding = new Binding(fixture.task.id(), operation, "fixture-app", "fixture-app-backend",
            "fixture-app-backend-" + ARCHIVE.substring(0, 16), PLAN, OWNER, ARCHIVE, layout,
            DeploymentManagedHelperBundle.helperSha256(layout));
        final Request request;
        final ApprovalGrant grant = new ApprovalGrant(UUID.randomUUID());
        int hostCalls;
        String duringReview = "";
        ActionIntent expectedIntent;
        CommandRequest expectedCommand;
        final TaskService tasks = proxy(TaskService.class, (p, m, a) -> {
            assertSame(fixture.task, a[0]);
            String key = (String) a[1];
            return switch (m.getName()) {
                case "findCheckpoint" -> Optional.ofNullable(author.get(key));
                case "findRecoveryCheckpoint" -> Optional.ofNullable(recovery.get(key));
                case "beginCheckpoint" -> {
                    assertEquals("deployment.config.remote-layout.v1", key);
                    var value = new Checkpoint((String) a[2], true);
                    assertNull(author.putIfAbsent(key, value));
                    yield value;
                }
                default -> throw new AssertionError("Unexpected task capability: " + m.getName());
            };
        });
        final ModDatabase database = proxy(ModDatabase.class, (p, m, a) -> switch (m.getName()) {
            case "identity" -> new SchemaIdentity(DeploymentSchemaIdentity.OWNER, DeploymentSchemaIdentity.VERSION, generation);
            case "openConnection" -> { assertSame(fixture.task, a[0]); yield DriverManager.getConnection(jdbc); }
            default -> throw new AssertionError(m.getName());
        });
        final AiService ai = proxy(AiService.class, (p, m, a) -> {
            if (m.getName().equals("call") && !duringReview.isEmpty()) mutate(duringReview);
            return invoke(fixture.context.ai(), m, a);
        });
        final InteractionService host = proxy(InteractionService.class, (p, m, a) -> {
            hostCalls++;
            assertEquals("approveReviewedAction", m.getName());
            assertSame(expectedIntent, a[0]);
            assertEquals(Optional.of(expectedCommand), a[1]);
            assertSame(fixture.task, a[4]);
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
        });
        final UiService ui = proxy(UiService.class, (p, m, a) -> {
            assertEquals("environment", m.getName());
            return new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false);
        });
        final ModContext context = proxy(ModContext.class, (p, m, a) -> switch (m.getName()) {
            case "tasks" -> tasks;
            case "db" -> database;
            case "ai" -> ai;
            case "interaction" -> host;
            case "ui" -> ui;
            default -> invoke(fixture.context, m, a);
        });
        final DeploymentAssistedScope scope = new DeploymentAssistedScope(context, fixture.task,
            fixture.configurationRevision, "AUTOMATIC", true);

        Harness(Mode mode) throws Exception {
            try (var source = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                "/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), StandardCharsets.UTF_8)) {
                org.h2.tools.RunScript.execute(retained, source);
            }
            var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), fixture.task.id(), Optional.empty(),
                "fixture-app", server, DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED, ROOT);
            var store = new DeploymentExecutionStore(database);
            var serverReference = DeploymentRefIntent.server(server, "compensation-review:" + identity.executionId());
            var originalCreatedAt = Instant.ofEpochMilli(1000);
            store.begin(fixture.task, identity, "deployment-publication", List.of(serverReference), Optional.empty(), originalCreatedAt);
            store.recordReference(fixture.task, identity.executionId(), serverReference, UUID.randomUUID(), originalCreatedAt);
            store.startEffects(fixture.task, identity.executionId(), originalCreatedAt);
            store.recordUncommittedOutcome(fixture.task, identity.executionId(), TaskResult.FAILED,
                new Problem(ProblemCode.IO_FAILURE, "SFTP_NOT_VERIFIED", "Original fixture failure", Map.of(), Optional.empty()), originalCreatedAt);
            DeploymentConfigCapture.remoteLayout(tasks, fixture.task, layout);
            var root = JSON.createObjectNode().put("version", 1).put("task", fixture.task.id().toString())
                .put("server", server.id()).put("application", "fixture-app").put("planSha256", ROOT)
                .put("phase", "ALL_PREPARED").put("verifiedOutcome", "FAILED")
                .put("failureCode", "IO_FAILURE").put("failureEvent", "SFTP_NOT_VERIFIED");
            var components = root.putArray("components");
            components.addObject().put("componentId", "backend").put("unitId", binding.unitId()).put("key", CHILD).put("planSha256", PLAN);
            components.addObject().put("componentId", "web").put("unitId", "fixture-app-web")
                .put("key", "deployment-publication-unit-01").put("planSha256", "f".repeat(64));
            var child = JSON.createObjectNode().put("version", 3).put("task", fixture.task.id().toString())
                .put("server", server.id()).put("application", binding.unitId()).put("ownerManifestSha256", OWNER)
                .put("releaseRoot", layout.applicationRoot(binding.unitId())).put("archiveSha256", ARCHIVE)
                .put("candidate", binding.candidateId()).put("planSha256", PLAN).put("backend", "native").put("engine", "none")
                .put("phase", "CANDIDATE_CLEANUP_MAY_HAVE_RUN").put("buildPlan", PLAN)
                .put("buildScript", "1".repeat(64)).put("buildTools", "2".repeat(64));
            author.put(DeploymentStandardGraphPublishActionLog.KEY, new Checkpoint(root.toString()));
            author.put(CHILD, new Checkpoint(child.toString()));
            String observation = mode == Mode.EXECUTE ? "e".repeat(64) : "";
            request = new Request(mode, binding, observation, originalCreatedAt.toEpochMilli());
            var observer = JSON.createObjectNode().put("formatVersion", 2).put("mode", "observe").put("taskId", binding.taskId().toString())
                .put("operationId", binding.operationId().toString()).put("bindingSha256", binding.sha256())
                .put("status", "OBSERVED").put("reason", "fixture-result").put("candidatePresent", true).put("quiescent", true)
                .put("observationSha256", "e".repeat(64)).put("receiptSha256", "");
            observer.putObject("systemProcessExclusion");
            String observerResponse = DeploymentCandidateCompensationProgram.canonical(observer);
            var journal = JSON.createObjectNode().put("formatVersion", 2).put("childKey", CHILD)
                .put("stage", mode == Mode.OBSERVE ? "PREPARED" : mode == Mode.EXECUTE ? "CONFIRMED" : "DISPATCHED")
                .put("observationSha256", observation).put("questionSha256", mode == Mode.EXECUTE
                    ? DeploymentModelCommandIdentity.digest(DeploymentCandidateCompensation.prompt(binding, observation, observerResponse, "{}")) : "")
                .put("response", mode == Mode.EXECUTE ? observerResponse : "").put("event", "")
                .put("guardSha256", DeploymentCandidateCompensationProgram.bodySha256())
                .put("originalExecutionCreatedAtEpochMillis", originalCreatedAt.toEpochMilli()).put("predecessorResponse", "");
            journal.putObject("predecessor");
            journal.set("binding", binding.json()); journal.set("originalGraph", root); journal.set("originalChild", child);
            recovery.put(DeploymentCandidateCompensation.key(binding.candidateId()), new Checkpoint(journal.toString()));
        }

        void reply(String decision) {
            fixture.nativeReply(new AiToolCall("compensation-verdict", "submit_result", "{\"decision\":\"" + decision
                + "\",\"reason\":\"Original compensation review\",\"requiresUserConfirmation\":false}"));
        }

        void successorReceipt() throws Exception {
            String key = DeploymentCandidateCompensation.key(binding.candidateId());
            var base = (ObjectNode)JSON.readTree(recovery.get(key).snapshot.payload());
            var response = JSON.createObjectNode().put("formatVersion", 1).put("mode", "observe").put("taskId", binding.taskId().toString())
                .put("operationId", binding.operationId().toString()).put("bindingSha256", binding.sha256()).put("status", "REFUSED")
                .put("reason", "other-root-execution-chain").put("candidatePresent", false).put("quiescent", false)
                .put("observationSha256", "").put("receiptSha256", "");
            var legacy = JSON.createObjectNode().put("formatVersion", 1).put("childKey", CHILD).put("stage", "REFUSED")
                .put("observationSha256", "").put("questionSha256", "").put("response", DeploymentCandidateCompensationProgram.canonical(response)).put("event", "");
            legacy.set("binding", binding.json()); legacy.set("originalGraph", base.path("originalGraph")); legacy.set("originalChild", base.path("originalChild"));
            var tombstone = new Checkpoint(legacy.toString()); tombstone.snapshot = new TaskCheckpoint.Snapshot(2, legacy.toString(), true);
            recovery.put(key, tombstone);
            var successorBinding = binding.json().put("operationId", UUID.nameUUIDFromBytes(
                ("candidate-compensation-observation-v1:" + binding.sha256()).getBytes(StandardCharsets.UTF_8)).toString());
            base.set("binding", successorBinding); base.put("stage", "VERIFY_PREDECESSOR");
            var reference = base.putObject("predecessor").put("key", key).put("operationId", binding.operationId().toString())
                .put("revision", 2).put("payloadSha256", DeploymentModelCommandIdentity.digest(tombstone.snapshot.payload()))
                .put("helperSha256", binding.helperSha256()).put("kind", "EXTERNAL_EXECUTION_SOURCE_ATTESTATION_REQUIRED")
                .put("legacyStateMachineRevision", 1);
            reference.putObject("expectedLegacyWriterSources")
                .put("compensationSha256", "71f4cea99b53dd037a56279d5b0a746a58bd88cbf9baae6a75d1c72aa98a447c")
                .put("programSha256", "2aaccc2efecb6a6fb019129ec774363dae92ae9e181f6442cf160407998359e9")
                .put("pythonSha256", "27bccfdf873b82b9fc587c8f674952efc49cfedf88a3109304dddc9916b2b6f1");
            recovery.put(DeploymentCandidateCompensation.successorKey(binding.candidateId()), new Checkpoint(base.toString()));
        }

        OperationResult<ApprovalGrant> approve(ActionIntent intent, CommandRequest command) throws ApiException {
            return ((DeploymentCommandReview) scope.interaction()).approveCommand(intent, command, fixture.task);
        }

        void mutate(String mutation) throws Exception {
            if (mutation.equals("PREDECESSOR_BYTES")) {
                var original = recovery.get(DeploymentCandidateCompensation.key(binding.candidateId()));
                original.snapshot = new TaskCheckpoint.Snapshot(2, original.snapshot.payload() + " ", true);
                return;
            }
            if (mutation.equals("LAYOUT")) {
                author.remove("deployment.config.remote-layout.v1");
                DeploymentConfigCapture.remoteLayout(tasks, fixture.task, new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
                    "/opt/changed/bin", "/srv/changed/apps", "/var/lib/changed", "/etc/changed", "/srv/changed/data")));
                return;
            }
            Checkpoint checkpoint = switch (mutation) {
                case "GRAPH" -> author.get(DeploymentStandardGraphPublishActionLog.KEY);
                case "CHILD" -> author.get(CHILD);
                default -> recovery.values().iterator().next();
            };
            var node = (ObjectNode) JSON.readTree(checkpoint.snapshot.payload());
            switch (mutation) {
                case "GRAPH" -> node.put("failureEvent", "OTHER_ORIGINAL_FAILURE");
                case "CHILD" -> node.put("archiveSha256", "9".repeat(64));
                case "RECOVERY" -> node.withObject("originalChild").put("archiveSha256", "9".repeat(64));
                case "CONFIRMATION" -> node.put("questionSha256", "0".repeat(64));
                case "STAGE" -> node.put("stage", "DISPATCHED");
                case "GUARD" -> node.put("guardSha256", "0".repeat(64));
                case "EPOCH" -> node.put("originalExecutionCreatedAtEpochMillis", 1001);
                case "OBSERVER" -> node.put("response", node.path("response").asText().replace("OBSERVED", "REFUSED"));
                default -> { return; }
            }
            checkpoint.snapshot = new TaskCheckpoint.Snapshot(checkpoint.snapshot.revision() + 1, node.toString(), false);
        }

        Map<String, TaskCheckpoint.Snapshot> snapshots() {
            var result = new HashMap<String, TaskCheckpoint.Snapshot>();
            author.forEach((key, value) -> result.put(key, value.snapshot));
            recovery.forEach((key, value) -> result.put(key, value.snapshot));
            return Map.copyOf(result);
        }

        public void close() throws SQLException { retained.close(); }
    }

    private static final class Checkpoint implements TaskCheckpoint {
        Snapshot snapshot;
        final boolean closeAllowed;
        Checkpoint(String payload) { this(payload, false); }
        Checkpoint(String payload, boolean closeAllowed) {
            snapshot = new Snapshot(0, payload, false);
            this.closeAllowed = closeAllowed;
        }
        public Snapshot snapshot() { return snapshot; }
        public Snapshot advance(Snapshot expected, String next) { throw new AssertionError("Review cannot mutate checkpoints"); }
        public void close() {
            if (!closeAllowed) throw new AssertionError("Review cannot release original or recovery checkpoints");
            snapshot = new Snapshot(snapshot.revision() + 1, snapshot.payload(), true);
        }
    }

    private static boolean containsText(JsonNode node, String expected) {
        if (node.isTextual()) return node.asText().equals(expected);
        for (var child : node) if (containsText(child, expected)) return true;
        return false;
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
