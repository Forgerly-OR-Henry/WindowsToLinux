package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.task.log.LogEvent;
import gold.debug.windowstolinux.appapi.task.log.LogService;
import gold.debug.windowstolinux.appapi.task.log.LogTarget;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentHistoryArchive;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationAbsence;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationCompensation;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Isolated original-task transactions and fake independent approval; no runtime transport. */
class DeploymentAgentPreparationCompensationTest {
    private static final String REVISION = "a".repeat(64);

    private static final String SOURCE = "b".repeat(64);

    private static final String ARCHIVE = "c".repeat(64);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void uploadedOriginalClosesOnlyItsTemporaryEffectsAndArchivesFailed() throws Exception {
        try (var f = new Frame()) {
            var before = f.journal.events(f.task);
            var business = f.business();
            assertTrue(DeploymentAgentPreparationAbsence.capture(f.id, f.journal.find(f.task).orElseThrow(), f.layout, before).isEmpty());
            var result = f.recover();
            assertEquals(TaskResult.FAILED, result.outcome(), f.nativeAi.events.toString());
            assertEquals(1, f.deletes);
            assertEquals(4, f.sent);
            var events = f.journal.events(f.task);
            assertEquals(before, events.subList(0, before.size()));
            var proof = events.stream().filter(e -> e.type().equals(DeploymentAgentPreparationCompensation.EVENT)).findFirst().orElseThrow();
            assertEquals("SUCCEEDED", JSON.readTree(proof.detail()).path("cleanup").asText());
            assertTrue(DeploymentAgentActionLog.effectsSettled(events));
            assertEquals(TaskResult.FAILED, f.recover().outcome());
            assertEquals(events, f.journal.events(f.task), "Restart after final state must not append duplicate settlement or delete again");
            assertEquals(1, f.deletes);
            assertTrue(events.stream().noneMatch(e -> e.type().equals("PREPARE_NOT_SENT") || e.type().equals("PREPARE_RESULT")));
            var archive = new DeploymentAgentHistoryArchive(f.context);
            assertEquals(TaskResult.FAILED, archive.settle(f.task, result).outcome());
            assertEquals(TaskResult.FAILED, archive.settle(f.task, result).outcome());
            assertEquals(1, f.deletes);
            assertEquals(business, f.business());
        }
    }

    @Test
    void committedSettlementSurvivesFinalStateCommitFailureWithoutAnotherDeleteOrEvent() throws Exception {
        try (var f = new Frame()) {
            f.fault = "FINAL_STATE_COMMIT";
            assertThrows(SQLException.class, f::recover);
            var interrupted = f.journal.events(f.task);
            assertEquals("UNKNOWN", f.journal.find(f.task).orElseThrow().state());
            assertEquals(1, interrupted.stream().filter(e -> e.type().equals("AI_RECOVERY_SETTLED")).count());
            assertTrue(DeploymentAgentActionLog.preparationsSettled(interrupted));
            assertEquals(1, f.deletes);
            assertEquals(4, f.sent);
            var result = f.recover();
            assertEquals(TaskResult.FAILED, result.outcome());
            var events = f.journal.events(f.task);
            assertEquals(1, events.stream().filter(e -> e.type().equals("AI_RECOVERY_SETTLED")).count());
            assertEquals(interrupted, events.subList(0, interrupted.size()));
            assertTrue(DeploymentAgentActionLog.preparationsSettled(events));
            assertEquals(1, f.deletes);
            assertEquals(4, f.sent);
            assertEquals(TaskResult.FAILED, new DeploymentAgentHistoryArchive(f.context).settle(f.task, result).outcome());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LATE_SOURCE", "LATE_PLAN", "SECOND_PREPARE", "WRONG_ACTION", "SIDE_EFFECT", "PUBLICATION", "NATIVE", "LATE_OBSERVATION", "DUPLICATE_PLAN_APP"})
    void changedOrBranchedOriginalCannotAcquireCandidate(String change) throws Exception {
        try (var f = new Frame()) {
            switch (change) {
                case "LATE_SOURCE" -> f.journal.event(f.task, "SOURCE", ARCHIVE, SOURCE);
                case "LATE_PLAN" -> f.journal.event(f.task, "PLAN", "", "e".repeat(64));
                case "DUPLICATE_PLAN_APP" -> f.journal.event(f.task, "PLAN_APPLICATION", "", "other");
                case "SECOND_PREPARE" -> f.journal.event(f.task, "PREPARE_EXECUTING", "e".repeat(64), "web");
                case "WRONG_ACTION" ->
                    f.sql("UPDATE deployment_agent_event SET action_id='" + "e".repeat(64) + "' WHERE event_type='PREPARE_EXECUTING'");
                case "SIDE_EFFECT" -> f.journal.event(f.task, "CAPABILITY_EXECUTING", "x", "secret");
                case "PUBLICATION" -> f.journal.event(f.task, "AI_PUBLICATION", "demo", "START");
                case "NATIVE" -> f.nativeDispatched();
                case "LATE_OBSERVATION" -> f.journal.event(f.task, "AGENT_OBSERVATION", "x", "later model activity");
                default -> throw new AssertionError(change);
            }
            var before = f.journal.events(f.task);
            assertEquals(TaskResult.UNKNOWN, f.recover().outcome());
            assertEquals(0, f.sent);
            assertEquals(0, f.filesClosed);
            assertEquals(before, f.journal.events(f.task));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACK_ABSENT", "ACK_PRESENT", "PRE_UNKNOWN", "ABSENT_LOOP", "DENY", "INITIAL_ABSENT"})
    void receiptAndAbsenceStayDistinctAndDeniedCleanupNeverSends(String condition) throws Exception {
        try (var f = new Frame()) {
            f.obstacle = condition;
            if (condition.equals("DENY")) f.denyCleanup = true;
            if (condition.equals("INITIAL_ABSENT") || condition.equals("ABSENT_LOOP")) f.present = false;
            var result = f.recover();
            boolean closed = condition.equals("ACK_ABSENT") || condition.equals("INITIAL_ABSENT");
            assertEquals(closed ? TaskResult.FAILED : TaskResult.UNKNOWN, result.outcome());
            assertEquals(condition.startsWith("ACK_") ? 1 : 0, f.deletes);
            if (closed) {
                var proof = f.journal.events(f.task).stream().filter(e -> e.type().equals(DeploymentAgentPreparationCompensation.EVENT)).findFirst().orElseThrow();
                assertEquals(condition.equals("ACK_ABSENT") ? "UNCONFIRMED" : "NOT_REQUIRED", JSON.readTree(proof.detail()).path("cleanup").asText());
            } else {
                assertEquals(0, f.filesClosed);
                assertFalse(DeploymentAgentActionLog.preparationsSettled(f.journal.events(f.task)));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "COMMIT_FAILED", "COMMIT_ACK_LOST"})
    void transactionFailureOrLostAcknowledgementRequiresObservationOrValidReceipt(String fault) throws Exception {
        try (var f = new Frame()) {
            var captured = f.capture();
            String proof = f.proof(captured);
            f.fault = fault;
            assertThrows(SQLException.class, () -> f.journal.preparationCompensated(f.task, captured, proof));
            boolean committed = fault.equals("COMMIT_ACK_LOST");
            assertEquals(committed, DeploymentAgentActionLog.preparationsSettled(f.journal.events(f.task)));
            if (committed) {
                f.journal.preparationCompensated(f.task, captured, proof);
                assertEquals(TaskResult.FAILED, f.recover().outcome());
                assertEquals(0, f.sent);
            } else {
                assertEquals(captured.events(), f.journal.events(f.task));
                f.present = false;
                assertEquals(TaskResult.FAILED, f.recover().outcome());
                assertEquals(0, f.deletes);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "STATE", "FORMAL", "BUSINESS", "FORGED", "FOREIGN"})
    void transactionalRecheckRejectsChangesAndForgedProofs(String change) throws Exception {
        try (var f = new Frame()) {
            var captured = f.capture();
            String proof = f.proof(captured);
            switch (change) {
                case "SOURCE" -> f.journal.event(f.task, "SOURCE", ARCHIVE, SOURCE);
                case "STATE" -> f.journal.state(f.task, "CANCELLED");
                case "FORMAL" ->
                    f.sql("INSERT INTO deployment_execution(execution_id,platform_task_id,server_profile_id,mode,reviewed_revision,request_payload,recovery_payload,phase,created_at,updated_at) VALUES('"
                        + UUID.randomUUID() + "','" + f.id + "','original-server','AGENT','" + REVISION + "',X'7b7d',X'7b7d','EXECUTING',1,1)");
                case "BUSINESS" ->
                    f.sql("INSERT INTO managed_application(application_id,server_profile_id,updated_at) VALUES('demo','original-server',1)");
                case "FORGED" -> proof = proof.replace("\"afterResultSha256\":\"", "\"afterResultSha256\":\"0");
                case "FOREIGN" -> proof = proof.replace(f.id.toString(), UUID.randomUUID().toString());
                default -> throw new AssertionError(change);
            }
            String selected = proof;
            assertThrows(SQLException.class, () -> f.journal.preparationCompensated(f.task, captured, selected));
            assertFalse(DeploymentAgentActionLog.preparationsSettled(f.journal.events(f.task)));
            assertEquals(0, f.deletes);
        }
    }

    @Test
    void duplicateConcurrentAppendsAreOnceAndLaterProgressCannotHideBehindProof() throws Exception {
        try (var f = new Frame()) {
            var captured = f.capture();
            String proof = f.proof(captured);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var a = executor.submit(() -> {
                    f.journal.preparationCompensated(f.task, captured, proof);
                    return true;
                });
                var b = executor.submit(() -> {
                    f.journal.preparationCompensated(f.task, captured, proof);
                    return true;
                });
                assertTrue(a.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(b.get(10, java.util.concurrent.TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(1, f.journal.events(f.task).stream().filter(e -> e.type().equals(DeploymentAgentPreparationCompensation.EVENT)).count());
            f.journal.event(f.task, DeploymentAgentPreparationCompensation.EVENT, captured.preparation().action(), proof);
            assertFalse(DeploymentAgentActionLog.preparationsSettled(f.journal.events(f.task)));
            assertTrue(DeploymentAgentRecovery.originalWorkspaces(f.id, captured.snapshot().identity(), f.journal.events(f.task)).isEmpty());
        }
    }

    @Test
    void bindingChangeAfterPreObservationCannotDispatchCleanup() throws Exception {
        try (var f = new Frame()) {
            f.afterSend = () -> f.journal.event(f.task, "SOURCE", ARCHIVE, SOURCE);
            assertEquals(TaskResult.UNKNOWN, f.recover().outcome());
            assertEquals(0, f.deletes);
            assertEquals(0, f.filesClosed);
        }
    }

    @Test
    void confirmedProofCannotConcealLaterIdentityOrPublication() throws Exception {
        try (var f = new Frame()) {
            var captured = f.capture();
            f.journal.preparationCompensated(f.task, captured, f.proof(captured));
            f.journal.event(f.task, "SOURCE", ARCHIVE, SOURCE);
            assertFalse(DeploymentAgentActionLog.preparationsSettled(f.journal.events(f.task)));
            assertEquals(TaskResult.UNKNOWN, f.recover().outcome());
            assertEquals(0, f.sent);
            assertEquals(0, f.filesClosed);
        }
    }

    @Test
    void oldAbsenceStillNeedsItsOwnCompleteObservationAndRejectsReadiness() throws Exception {
        try (var f = new Frame()) {
            f.sql("DELETE FROM deployment_agent_event WHERE event_type='HELPER_READINESS_OBSERVED'");
            f.present = false;
            assertEquals(TaskResult.FAILED, f.recover().outcome(), f.nativeAi.events.toString());
            assertEquals(0, f.deletes);
            assertEquals(1, f.sent);
            assertTrue(f.journal.events(f.task).stream().anyMatch(e -> e.type().equals(DeploymentAgentPreparationAbsence.EVENT)));
        }
    }

    @Test
    void original09TypedIdentityBindsFullArchiveDistinctFromSnapshotAndPolicy() throws Exception {
        UUID task = UUID.fromString("191bab33-4c43-400a-816d-f4ba78f4b785");
        String archive = "c99039223d8e52cf3c78f413eef7e50aea3866d6969e5b6107a7bb26d8858a47";
        String source = "188ceae2557c7b758c2f4686d1e3653ca8c9df5d38801853ccab149987e9a6c0";
        var identity = new DeploymentAgentActionLog.Identity("static-live", "c46170de167e15e59701c3ce53313c75eedb78bd60ce67393cf5677d6fcf7eaf",
            "e7c1cf418b145d3d85f6718db9e28783f1cb238566ab62f607357f0bf26f13b7", "MANUAL_REVIEW", false);
        var layout = DeploymentRemoteLayout.original();
        var header = JSON.createObjectNode().put("formatVersion", 1).put("task", task.toString()).put("server", identity.server());
        header.set("layout", JSON.readTree(layout.canonical()));
        header.put("layoutSha256", layout.digest());
        var events = List.of(new DeploymentAgentActionLog.Event(1, "REMEMBER_AUTHORIZATION", "rememberAuthorization.v1", "false"),
            new DeploymentAgentActionLog.Event(2, "REMOTE_TARGET", "", "f".repeat(64)), new DeploymentAgentActionLog.Event(3, "REMOTE_LAYOUT", task.toString(), header.toString()),
            new DeploymentAgentActionLog.Event(4, "SOURCE", archive, source), new DeploymentAgentActionLog.Event(5, "TARGET_PROFILE", "", "e".repeat(64)),
            new DeploymentAgentActionLog.Event(98, "PLAN_APPLICATION", "", "wtl-agent-task-board-3q8om79rww"), new DeploymentAgentActionLog.Event(99, "PLAN", "", "e".repeat(64)),
            new DeploymentAgentActionLog.Event(118, "PREPARE_EXECUTING", "81921ee8aef135d428d133826bebdcb4f31a62c2dedf132e86b62b886167f8ac", "backend"),
            new DeploymentAgentActionLog.Event(119, "HELPER_READINESS_OBSERVED", "f".repeat(64), "MISSING:HELPER_MISSING"),
            new DeploymentAgentActionLog.Event(120, "HELPER_READINESS_OBSERVED", "f".repeat(64), "READY:CURRENT_TASK_READY"), new DeploymentAgentActionLog.Event(121, "STATE", "", "UNKNOWN"));
        var snapshot = new DeploymentAgentActionLog.Snapshot(identity, "UNKNOWN", 4);
        var original = DeploymentAgentPreparationCompensation.capture(task, snapshot, layout, events).orElseThrow();
        assertEquals("wtl-agent-task-board-3q8om79rww-backend-c99039223d8e52cf", original.workspace().candidateId());
        assertEquals(source, original.source());
        var changed = new ArrayList<>(events);
        changed.set(3, new DeploymentAgentActionLog.Event(4, "SOURCE", archive.substring(0, 16) + "0".repeat(48), source));
        var differentArchive = DeploymentAgentPreparationCompensation.capture(task, snapshot, layout, changed).orElseThrow();
        assertEquals(original.workspace().candidateId(), differentArchive.workspace().candidateId());
        assertFalse(DeploymentAgentPartialPreparationProbe.present(differentArchive, new CommandResult(Optional.of(0),
            DeploymentAgentPartialPreparationProbe.presentOutput(original), "", false)));
        assertFalse(DeploymentAgentPartialPreparationProbe.requestDigest(original, false).equals(DeploymentAgentPartialPreparationProbe.requestDigest(differentArchive, false)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VALID_REVIEW", "INVALID_REBOUND", "VALID_INSPECT", "FOREIGN_INSPECT"})
    void recoveryMetadataIsExplicitlyBoundRatherThanAnArbitrarySuffix(String condition) throws Exception {
        try (var f = new Frame()) {
            boolean valid = condition.startsWith("VALID_");
            if (condition.endsWith("REVIEW") || condition.equals("INVALID_REBOUND")) {
                f.journal.event(f.task, "RECOVERY_REVIEW_BOUND", REVISION, "f".repeat(64));
                if (!valid)
                    f.journal.event(f.task, "RECOVERY_REVIEW_REBOUND", REVISION, "previousEventId=0\npreviousReviewRevision=" + "f".repeat(64) + "\nreviewRevision=" + "e".repeat(64));
            } else {
                f.journal.event(f.task, "INSPECT_REQUESTED", valid ? f.id.toString() : UUID.randomUUID().toString(), UUID.randomUUID().toString());
                long requested = f.journal.events(f.task).getLast().id();
                f.journal.event(f.task, "INSPECT_RESULT", Long.toString(requested), "{" + f.captureCandidate() + "=PRESENT}");
            }
            assertEquals(valid, DeploymentAgentPreparationCompensation.capture(f.id, f.journal.find(f.task).orElseThrow(), f.layout, f.journal.events(f.task)).isPresent());
        }
    }

    private static final class Frame implements AutoCloseable {
        final NativeAiFixture nativeAi = new NativeAiFixture();

        final UUID id = nativeAi.task.id();

        final String jdbc = "jdbc:h2:mem:preparation-compensation-" + id;

        final Connection retained = DriverManager.getConnection(jdbc);

        final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();

        final ServerId server = new ServerId("original-server");

        final ServerInfo profile = new ServerInfo(server, "Original", "original.invalid", 22, "root", Optional.of("controlled-fingerprint"), Optional.empty());

        final RemoteFacts remoteFacts = new RemoteFacts("linux", "amd64", "controlled-fingerprint");

        ServerInfo observedProfile = profile;

        String observedFingerprint = remoteFacts.fingerprint();

        final TaskContext task = nativeAi.task;

        final ModDatabase db;

        final DeploymentAgentActionLog journal;

        final ModContext context;

        final Map<String, TaskCheckpoint> checkpoints = new HashMap<>();

        int diagnosticAttempts;

        CommandResult observedCommand;

        int connected;

        int sent;

        int grants;

        int deletes;

        boolean present = true;

        boolean denyCleanup;

        final Map<String, Integer> modelCursor = new HashMap<>();

        int filesClosed;

        String obstacle = "";

        String fault = "";

        Hook afterSend = () -> {
        };

        Frame() throws Exception {
            nativeAi.configurationRevision = REVISION;
            try (var schema = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                "/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), StandardCharsets.UTF_8)) {
                org.h2.tools.RunScript.execute(retained, schema);
            }
            db = proxy(ModDatabase.class, (name, args) -> name.equals("identity")
                ? new SchemaIdentity(DeploymentSchemaIdentity.OWNER, DeploymentSchemaIdentity.VERSION, id) : connection());
            journal = new DeploymentAgentActionLog(db);
            String target = DeploymentAgentInputs.hash(server.id() + ":" + remoteFacts.fingerprint());
            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), target, REVISION, "FULL_CONTROL", true),
                Optional.of(DeploymentAgentInputs.physicalTarget(profile)));
            DeploymentAgentLayouts.record(journal, task, server, layout);
            journal.event(task, "SOURCE", ARCHIVE, SOURCE);
            journal.event(task, "TARGET_PROFILE", "", DeploymentAgentInputs.targetProfile(profile));
            journal.event(task, "PLAN_APPLICATION", "", "demo");
            journal.event(task, "PLAN", "", "e".repeat(64));
            journal.event(task, "PREPARE_EXECUTING", DeploymentAgentInputs.hash(task.id() + ":api:" + SOURCE), "api");
            journal.event(task, "HELPER_READINESS_OBSERVED", "d".repeat(64), "MISSING:HELPER_MISSING");
            journal.event(task, "HELPER_READINESS_OBSERVED", "e".repeat(64), "READY:CURRENT_TASK_READY");
            journal.state(task, "UNKNOWN");
            var capturedLayout = JSON.createObjectNode().put("formatVersion", 1).put("task", id.toString());
            capturedLayout.set("layout", JSON.readTree(layout.canonical()));
            capturedLayout.put("layoutSha256", layout.digest());
            TaskCheckpoint checkpoint = proxy(TaskCheckpoint.class, (name, args) -> new TaskCheckpoint.Snapshot(0, capturedLayout.toString(), true));
            checkpoints.put("deployment.config.remote-layout.v1", checkpoint);
            var tasks = proxy(TaskService.class, (name, args) -> switch (name) {
                case "findCheckpoint" -> Optional.ofNullable(checkpoints.get((String) args[1]));
                case "beginCheckpoint" -> {
                    var created = new MemoryCheckpoint((String) args[2]);
                    assertTrue(checkpoints.putIfAbsent((String) args[1], created) == null);
                    yield created;
                }
                case "resourcesReleased" -> filesClosed > 0;
                default -> throw new AssertionError(name);
            });
            var host = proxy(InteractionService.class, (name, args) -> switch (name) {
                case "openReviewedWorkflow" -> success((WorkflowGrant) () -> {
                });
                case "approveReviewedAction" -> {
                    grants++;
                    yield success(new ApprovalGrant(UUID.randomUUID()));
                }
                default -> throw new AssertionError(name);
            });
            var session = proxy(RemoteSession.class, (name, args) -> switch (name) {
                case "facts" -> new RemoteFacts("linux", "amd64", observedFingerprint);
                case "close" -> null;
                case "execute" -> {
                    sent++;
                    CommandRequest request = (CommandRequest) args[0];
                    String body = new String(request.stdin(), StandardCharsets.UTF_8);
                    if (body.isEmpty() && request.script().contains("' probe"))
                        yield success(new CommandResult(Optional.of(0), "HELPER=1\nPROTOCOL=" + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PROTOCOL_VERSION
                            + "\nSHA256=" + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.helperSha256(layout) + "\n", "", false));
                    if (request.script().contains("'candidate-cleanup-task'")) {
                        deletes++;
                        if (!obstacle.equals("ACK_PRESENT")) present = false;
                        if (obstacle.startsWith("ACK_")) throw new ApiException(processProblem(ProblemCode.IO_FAILURE));
                        yield success(new CommandResult(Optional.of(0), "CANDIDATE_CLEANED=1\n", "", false));
                    }
                    assertTrue(body.contains("WTL_AGENT_PREPARATION_ABSENCE_1"));
                    boolean pre = body.contains("WTL_AGENT_UPLOADED_PREPARATION_1");
                    var captured = DeploymentAgentPreparationCompensation.capture(id, journal.find(task).orElseThrow(), layout, journal.events(task));
                    String output = captured.isPresent() ? present && pre ? DeploymentAgentPartialPreparationProbe.presentOutput(captured.orElseThrow())
                        : DeploymentAgentPartialPreparationProbe.absentOutput(captured.orElseThrow())
                        : DeploymentAgentPreparationProbe.absentOutput(DeploymentAgentPreparationAbsence.capture(id, journal.find(task).orElseThrow(), layout, journal.events(task)).orElseThrow());
                    if (obstacle.equals("PRE_UNKNOWN") && pre || present && !pre || obstacle.equals("ABSENT_LOOP"))
                        output = "UNKNOWN\n";
                    afterSend.run();
                    yield success(new CommandResult(Optional.of(0), output, "", false));
                }
                default -> throw new AssertionError("Recovery cannot invoke " + name);
            });
            var linux = proxy(LinuxService.class, (name, args) -> {
                assertEquals("connect", name);
                connected++;
                return session;
            });
            var files = proxy(FileService.class, (name, args) -> {
                assertEquals("closeTaskResources", name);
                filesClosed++;
                return null;
            });
            var servers = proxy(ServerService.class, (name, args) -> {
                assertEquals("get", name);
                return observedProfile;
            });
            var ui = proxy(UiService.class, (name, args) -> new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            var ai = proxy(AiService.class, (name, args) -> {
                if (name.equals("configurationRevision")) return REVISION;
                if (name.equals("call")) {
                    var request = (gold.debug.windowstolinux.appapi.ai.model.AiRequest) args[0];
                    var input = JSON.readTree(request.messages().getLast().text());
                    String binding = input.path("reviewBinding").asText();
                    int cursor = modelCursor.getOrDefault(binding, 0);
                    var calls = new ArrayList<AiToolCall>();
                    int index = 0;
                    for (String part : List.of("script", "stdin")) {
                        var descriptor = input.path("executableInput").path(part);
                        int total = descriptor.path("total").asInt();
                        if (total <= 512) continue;
                        for (int offset = 0; offset < total; offset += 2500) {
                            if (index++ == cursor)
                                calls.add(new AiToolCall(UUID.randomUUID().toString(), "request", JSON.createObjectNode()
                                    .put("part", part).put("offset", offset).put("limit", 2500).toString()));
                        }
                    }
                    if (calls.isEmpty()) {
                        String decision = denyCleanup && deletes == 0 && sent == 2 ? "DENY" : "APPROVE";
                        calls.add(new AiToolCall(UUID.randomUUID().toString(), "submit_result", "{\"decision\":\"" + decision
                            + "\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled complete fixed request\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}"));
                    }
                    modelCursor.put(binding, cursor + 1);
                    nativeAi.nativeReply(calls.toArray(AiToolCall[]::new));
                    return nativeAi.context.ai().call(request, task);
                }
                throw new AssertionError(name);
            });
            var logs = proxy(LogService.class, (name, args) -> switch (name) {
                case "register" -> nativeAi.context.logs().register((LogCategory) args[0]);
                case "write" -> {
                    var event = (LogEvent) args[1];
                    if (event.eventCode().equals("DEPLOYMENT_AGENT_OBSERVATION") && event.message().startsWith("preparation.")) {
                        diagnosticAttempts++;
                        if (obstacle.equals("LOGGER_FAILURE"))
                            throw new IllegalStateException("password=FAKE_PASSWORD_MARKER");
                    }
                    yield nativeAi.context.logs().write((LogTarget) args[0], event);
                }
                default -> throw new AssertionError(name);
            });
            context = proxy(ModContext.class, (name, args) -> switch (name) {
                case "db" -> db;
                case "tasks" -> tasks;
                case "interaction" -> host;
                case "linux" -> linux;
                case "servers" -> servers;
                case "file" -> files;
                case "ui" -> ui;
                case "ai" -> ai;
                case "logs" -> logs;
                default -> throw new AssertionError(name);
            });
            sql("INSERT INTO deployment_unit(deployment_unit_id,server_profile_id,systemd_unit_name,release_root_path,ownership_manifest_sha256,runtime_payload) VALUES('shared-unit','another-server','shared.service','/opt/shared','" + "f".repeat(64) + "',X'010203')");
            sql("INSERT INTO secret_revision(secret_id,secret_revision,credential_id,purpose,created_at) VALUES('persistent-secret',1,'retained-credential','deployment-runtime',1)");
            sql("INSERT INTO deployment_unit_configuration_snapshot(deployment_unit_id,configuration_revision,configuration_sha256,schema_version,created_at) VALUES('shared-unit',1,'" + "f".repeat(64) + "','1',1)");
            sql("INSERT INTO deployment_unit_configuration_entry VALUES('shared-unit',1,'persistent-value','RUNTIME','TEXT','KEEP')");
        }

        Problem processProblem(ProblemCode code) {
            return new Problem(code, "FAKE_PASSWORD_MARKER", "password=FAKE_PASSWORD_MARKER",
                Map.of("source", "FAKE_SOURCE_MARKER"), Optional.of(id));
        }

        Connection connection() throws Exception {
            var connection = DriverManager.getConnection(jdbc);
            var stateWrite = new boolean[1];
            return proxy(Connection.class, (name, args) -> {
                if (name.equals("prepareStatement") && ((String) args[0]).startsWith("UPDATE deployment_agent_task SET task_state="))
                    stateWrite[0] = true;
                if (name.equals("commit") && stateWrite[0] && fault.equals("FINAL_STATE_COMMIT")) {
                    fault = "";
                    throw new SQLException("Controlled final state commit failure after committed settlement");
                }
                if (name.equals("prepareStatement") && fault.equals("INSERT") && ((String) args[0]).startsWith("INSERT INTO deployment_agent_event")) {
                    fault = "";
                    throw new SQLException("Controlled append failure");
                }
                if (name.equals("commit") && fault.startsWith("COMMIT_")) {
                    String selected = fault;
                    fault = "";
                    if (selected.equals("COMMIT_ACK_LOST")) connection.commit();
                    throw new SQLException("Controlled commit confirmation failure");
                }
                try {
                    for (var method : Connection.class.getMethods())
                        if (method.getName().equals(name) && method.getParameterCount() == (args == null ? 0 : args.length)) {
                            try {
                                return method.invoke(connection, args);
                            } catch (IllegalArgumentException overload) {
                            }
                        }
                    throw new AssertionError(name);
                } catch (InvocationTargetException failed) {
                    throw failed.getCause();
                }
            });
        }

        void approve() {
            nativeAi.nativeReply(new AiToolCall(UUID.randomUUID().toString(), "submit_result",
                "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Exact metadata observation only\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}"));
        }

        void nativeDispatched() throws Exception {
            var receipt = DeploymentNativeDatabaseActionLog.begin(context.tasks(), task, server, UUID.randomUUID(), "prepare", "d".repeat(64), "e".repeat(64), "original-database");
            receipt.dispatched();
            journal.event(task, "AGENT_DATABASE_INDEX", "native-dispatched", "d".repeat(64) + ":" + "e".repeat(64));
        }

        DeploymentAgentPreparationCompensation.Captured capture() throws Exception {
            return DeploymentAgentPreparationCompensation.capture(id, journal.find(task).orElseThrow(), layout, journal.events(task)).orElseThrow();
        }

        String captureCandidate() {
            return new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace("demo-api", ARCHIVE, layout).candidateId();
        }

        String proof(DeploymentAgentPreparationCompensation.Captured captured) {
            return DeploymentAgentPreparationCompensation.proof(captured, false, DeploymentAgentPreparationCompensation.Cleanup.NOT_REQUIRED, "");
        }

        OperationResult<Void> recover() throws Exception {
            return new DeploymentAgentRecovery(context, journal).reconcile(task, journal.find(task).orElseThrow());
        }

        void sql(String statement) throws Exception {
            try (var query = retained.createStatement()) {
                query.executeUpdate(statement);
            }
        }

        List<String> business() throws Exception {
            var result = new ArrayList<String>();
            for (String table : List.of("deployment_unit", "managed_application", "managed_application_component", "managed_application_dependency", "deployment_release",
                "deployment_unit_configuration_snapshot", "deployment_unit_configuration_entry", "deployment_unit_release_binding", "secret_revision", "deployment_unit_release_secret_reference")) {
                try (var query = retained.createStatement(); var rows = query.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
                    while (rows.next()) {
                        var values = new ArrayList<Object>();
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                            Object value = rows.getObject(column);
                            values.add(value instanceof java.sql.Blob blob ? HexFormat.of().formatHex(blob.getBytes(1, Math.toIntExact(blob.length()))) : value);
                        }
                        result.add(table + ":" + values);
                    }
                }
            }
            return result;
        }

        public void close() throws Exception {
            retained.close();
        }
    }

    @FunctionalInterface
    private interface Hook {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String name, Object[] args) throws Throwable;
    }

    private static final class MemoryCheckpoint implements TaskCheckpoint {
        private Snapshot value;

        MemoryCheckpoint(String payload) {
            value = new Snapshot(0, payload, false);
        }

        public Snapshot snapshot() {
            return value;
        }

        public Snapshot advance(Snapshot expected, String payload) {
            assertEquals(value, expected);
            return value = new Snapshot(value.revision() + 1, payload, false);
        }

        public void close() {
            value = new Snapshot(value.revision() + 1, value.payload(), true);
        }
    }

    private static <T> T proxy(Class<T> type, Invocation callback) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> callback.call(method.getName(), args)));
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
