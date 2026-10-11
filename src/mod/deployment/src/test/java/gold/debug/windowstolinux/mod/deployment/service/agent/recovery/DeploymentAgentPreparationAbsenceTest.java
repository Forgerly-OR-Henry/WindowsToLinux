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
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
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
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real isolated H2 transactions and native fake-model messages; no API transport or SSH. */
class DeploymentAgentPreparationAbsenceTest {
    private static final String REVISION = "a".repeat(64);

    private static final String SOURCE = "b".repeat(64);

    private static final String ARCHIVE = "c".repeat(64);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exactAbsentObservationSettlesFailedOriginalArchivesAndDoesNotDispatchAgain() throws Exception {
        try (var frame = new Frame()) {
            var business = frame.business();
            var original = frame.journal.events(frame.task);
            frame.approve();
            var result = frame.recover();
            assertEquals(TaskResult.FAILED, result.outcome(), "Recovered absence is compensation, not a completed application");
            assertEquals(1, frame.sent);
            assertEquals(1, frame.grants);
            assertEquals(1, frame.filesClosed);
            var settled = frame.journal.events(frame.task);
            assertEquals(original, settled.subList(0, original.size()));
            assertEquals(1, settled.stream().filter(event -> event.type().equals(DeploymentAgentPreparationAbsence.EVENT)).count());
            assertTrue(settled.stream().noneMatch(event -> event.type().equals("PREPARE_NOT_SENT") || event.type().equals("PREPARE_RESULT")));
            assertTrue(DeploymentAgentActionLog.effectsSettled(settled));
            var archive = new DeploymentAgentHistoryArchive(frame.context);
            assertEquals(TaskResult.FAILED, archive.settle(frame.task, result).outcome());
            assertTrue(frame.journal.find(frame.task).isEmpty());
            assertTrue(frame.journal.events(frame.task).isEmpty());
            assertEquals(TaskResult.FAILED, archive.retained(frame.task).orElseThrow().outcome());
            assertEquals(TaskResult.FAILED, archive.settle(frame.task, result).outcome());
            assertEquals(1, frame.sent, "Platform-end crash gap must read the settlement receipt without remote work");
            assertEquals(business, frame.business(), "Existing formal/config/secret records stay byte-exact");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"READINESS", "SECOND_PREPARE", "OTHER_EFFECT", "NATIVE_DISPATCHED", "MISSING_HEADER", "WRONG_ACTION", "WRONG_LAYOUT", "LATER_MODEL_ACTIVITY"})
    void incompleteOrProgressedOriginalNeverObservesOrClosesFiles(String change) throws Exception {
        try (var frame = new Frame()) {
            switch (change) {
                case "READINESS" ->
                    frame.journal.event(frame.task, "HELPER_READINESS_OBSERVED", "d".repeat(64), "MISSING:HELPER_MISSING");
                case "SECOND_PREPARE" -> frame.journal.event(frame.task, "PREPARE_EXECUTING", "e".repeat(64), "web");
                case "OTHER_EFFECT" -> frame.journal.event(frame.task, "COMMAND_EXECUTING", "e".repeat(64), "30");
                case "NATIVE_DISPATCHED" -> frame.nativeDispatched();
                case "MISSING_HEADER" -> frame.sql("DELETE FROM deployment_agent_event WHERE event_type='PLAN'");
                case "WRONG_ACTION" ->
                    frame.sql("UPDATE deployment_agent_event SET action_id='" + "e".repeat(64) + "' WHERE event_type='PREPARE_EXECUTING'");
                case "WRONG_LAYOUT" ->
                    frame.sql("UPDATE deployment_agent_event SET event_detail='{}' WHERE event_type='REMOTE_LAYOUT'");
                case "LATER_MODEL_ACTIVITY" ->
                    frame.journal.event(frame.task, "AGENT_OBSERVATION", "READ", "Unexpected later activity");
                default -> throw new AssertionError(change);
            }
            var before = frame.journal.events(frame.task);
            if (change.equals("WRONG_LAYOUT")) assertThrows(Exception.class, frame::recover);
            else assertEquals(TaskResult.UNKNOWN, frame.recover().outcome());
            assertEquals(0, frame.sent);
            assertEquals(0, frame.connected);
            assertEquals(0, frame.filesClosed);
            assertEquals(before, frame.journal.events(frame.task));
            if (change.equals("NATIVE_DISPATCHED")) {
                var nativeReceipt = DeploymentNativeDatabaseActionLog.find(frame.context.tasks(), frame.task).orElseThrow();
                assertEquals("DISPATCHED", nativeReceipt.facts().phase());
                assertFalse(frame.checkpoints.get(DeploymentNativeDatabaseActionLog.KEY).snapshot().released());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATH", "BUILD", "MOUNTS", "LOOPS", "PERMISSION", "DANGLING_LINK", "FAILED_OBSERVATION", "TRUNCATED"})
    void anythingExceptCompleteAbsentMetadataPreservesUnknown(String obstacle) throws Exception {
        try (var frame = new Frame()) {
            frame.obstacle = obstacle;
            frame.approve();
            var before = frame.journal.events(frame.task);
            assertEquals(TaskResult.UNKNOWN, frame.recover().outcome());
            assertEquals(1, frame.sent);
            assertEquals(0, frame.filesClosed);
            assertEquals(before, frame.journal.events(frame.task));
            assertFalse(DeploymentAgentActionLog.effectsSettled(before));
        }
    }

    @Test
    void ledgerChangeAfterObservationDoesNotBackfillOrRelease() throws Exception {
        try (var frame = new Frame()) {
            frame.approve();
            frame.afterSend = () -> frame.journal.state(frame.task, "UNKNOWN");
            assertEquals(TaskResult.UNKNOWN, frame.recover().outcome());
            assertEquals(1, frame.sent);
            assertEquals(0, frame.filesClosed);
            assertTrue(frame.journal.events(frame.task).stream().noneMatch(event -> event.type().equals(DeploymentAgentPreparationAbsence.EVENT)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROFILE", "FINGERPRINT"})
    void changedActualTargetCannotProduceAnAbsenceReceipt(String change) throws Exception {
        try (var frame = new Frame()) {
            if (change.equals("PROFILE"))
                frame.observedProfile = new ServerInfo(frame.server, "Changed", "another.invalid", 22, "root", Optional.empty(), Optional.empty());
            else frame.observedFingerprint = "another-fingerprint";
            var before = frame.journal.events(frame.task);
            assertEquals(TaskResult.UNKNOWN, frame.recover().outcome());
            assertEquals(0, frame.sent);
            assertEquals(0, frame.grants);
            assertEquals(0, frame.filesClosed);
            assertEquals(before, frame.journal.events(frame.task));
        }
    }

    @Test
    void independentRefusalDoesNotDispatchOrReleaseTheOriginal() throws Exception {
        try (var frame = new Frame()) {
            frame.nativeAi.nativeReply(new AiToolCall(UUID.randomUUID().toString(), "submit_result",
                "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Observation is not authorized\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[],\"discussionStatus\":\"CLOSED\"}"));
            var before = frame.journal.events(frame.task);
            assertEquals(TaskResult.UNKNOWN, frame.recover().outcome());
            assertEquals(0, frame.sent);
            assertEquals(0, frame.grants);
            assertEquals(0, frame.filesClosed);
            assertEquals(before, frame.journal.events(frame.task));
        }
    }

    @Test
    void newFormalExecutionBlocksTheTransactionalAbsenceCommit() throws Exception {
        try (var frame = new Frame()) {
            var captured = frame.capture();
            frame.sql("INSERT INTO deployment_execution(execution_id,platform_task_id,server_profile_id,mode,reviewed_revision,request_payload,recovery_payload,phase,created_at,updated_at) VALUES('"
                + UUID.randomUUID() + "','" + frame.id + "','original-server','AGENT','" + REVISION + "',X'7b7d',X'7b7d','EXECUTING',1,1)");
            var before = frame.journal.events(frame.task);
            assertThrows(SQLException.class, () -> frame.journal.preparationAbsent(frame.task, captured, frame.proof(captured)));
            assertEquals(before, frame.journal.events(frame.task));
            assertEquals(0, frame.filesClosed);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "COMMIT_BEFORE", "COMMIT_ACK_LOST"})
    void transactionFailureRollsBackOrStrictReceiptProvesTheLostCommit(String fault) throws Exception {
        try (var frame = new Frame()) {
            var captured = frame.capture();
            var proof = frame.proof(captured);
            var before = frame.journal.events(frame.task);
            frame.fault = fault;
            assertThrows(SQLException.class, () -> frame.journal.preparationAbsent(frame.task, captured, proof));
            if (!fault.equals("COMMIT_ACK_LOST")) {
                assertEquals(before, frame.journal.events(frame.task));
                assertFalse(DeploymentAgentActionLog.effectsSettled(frame.journal.events(frame.task)));
            } else {
                var committed = frame.journal.events(frame.task);
                assertTrue(DeploymentAgentActionLog.effectsSettled(committed));
                frame.journal.preparationAbsent(frame.task, captured, proof);
                assertEquals(committed, frame.journal.events(frame.task), "The exact confirmed receipt is idempotent");
                assertEquals(TaskResult.FAILED, frame.recover().outcome());
                assertEquals(0, frame.sent, "Lost commit confirmation cannot cause another probe or preparation");
            }
        }
    }

    @Test
    void confirmedAbsenceReceiptDoesNotHideLaterLedgerProgress() throws Exception {
        try (var frame = new Frame()) {
            var captured = frame.capture();
            var proof = frame.proof(captured);
            frame.journal.preparationAbsent(frame.task, captured, proof);
            var committed = frame.journal.events(frame.task);
            frame.journal.preparationAbsent(frame.task, captured, proof);
            assertEquals(committed, frame.journal.events(frame.task));
            frame.journal.event(frame.task, "STATE", "", "UNKNOWN");
            var changed = frame.journal.events(frame.task);
            assertThrows(SQLException.class, () -> frame.journal.preparationAbsent(frame.task, captured, proof));
            assertEquals(changed, frame.journal.events(frame.task), "The old receipt cannot conceal later evidence or mutate the ledger");
            assertEquals(0, frame.sent);
            assertEquals(0, frame.filesClosed);
        }
    }

    @Test
    void foreignTaskAndForgedOrIncompleteProofDoNotSettlePreparation() throws Exception {
        try (var frame = new Frame()) {
            var captured = frame.capture();
            TaskContext foreign = proxy(TaskContext.class, (name, args) -> UUID.randomUUID());
            assertThrows(SQLException.class, () -> frame.journal.preparationAbsent(foreign, captured, frame.proof(captured)));
            String wrong = frame.proof(captured).replace("\"resultSha256\":\"", "\"resultSha256\":\"0");
            assertThrows(SQLException.class, () -> frame.journal.preparationAbsent(frame.task, captured, wrong));
            var before = frame.journal.events(frame.task);
            frame.journal.event(frame.task, DeploymentAgentPreparationAbsence.EVENT, captured.preparation().action(), "ABSENT");
            assertFalse(DeploymentAgentActionLog.effectsSettled(frame.journal.events(frame.task)));
            assertTrue(DeploymentAgentRecovery.originalWorkspaces(frame.task.id(), captured.snapshot().identity(), frame.journal.events(frame.task)).isEmpty());
            assertEquals(before.size() + 1, frame.journal.events(frame.task).size());
        }
    }

    private static final class Frame implements AutoCloseable {
        final NativeAiFixture nativeAi = new NativeAiFixture();

        final UUID id = nativeAi.task.id();

        final String jdbc = "jdbc:h2:mem:preparation-absence-" + id;

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

        int connected;

        int sent;

        int grants;

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
                    assertTrue(body.contains("WTL_AGENT_PREPARATION_ABSENCE_1"));
                    assertFalse(body.contains("managed-helper") || body.contains("mkdir ") || body.contains("rm ") || body.contains("losetup --detach"));
                    var captured = capture();
                    String output = DeploymentAgentPreparationProbe.absentOutput(captured);
                    if (List.of("PATH", "BUILD", "MOUNTS", "LOOPS").contains(obstacle))
                        output = output.replace(obstacle + "=ABSENT", obstacle + "=PRESENT");
                    if (List.of("PERMISSION", "DANGLING_LINK").contains(obstacle))
                        output = output.replace("STATUS=ABSENT", "STATUS=UNKNOWN");
                    var result = new CommandResult(Optional.of(obstacle.equals("FAILED_OBSERVATION") ? 1 : 0), output, "", obstacle.equals("TRUNCATED"));
                    afterSend.run();
                    yield success(result);
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
                if (name.equals("call")) return args.length == 3
                    ? nativeAi.context.ai().call((gold.debug.windowstolinux.appapi.ai.model.AiRequest) args[0], (String) args[1], task)
                    : nativeAi.context.ai().call((gold.debug.windowstolinux.appapi.ai.model.AiRequest) args[0], task);
                throw new AssertionError(name);
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
                case "logs" -> nativeAi.context.logs();
                default -> throw new AssertionError(name);
            });
            sql("INSERT INTO deployment_unit(deployment_unit_id,server_profile_id,systemd_unit_name,release_root_path,ownership_manifest_sha256,runtime_payload) VALUES('shared-unit','another-server','shared.service','/opt/shared','" + "f".repeat(64) + "',X'010203')");
            sql("INSERT INTO secret_revision(secret_id,secret_revision,credential_id,purpose,created_at) VALUES('persistent-secret',1,'retained-credential','deployment-runtime',1)");
            sql("INSERT INTO deployment_unit_configuration_snapshot(deployment_unit_id,configuration_revision,configuration_sha256,schema_version,created_at) VALUES('shared-unit',1,'" + "f".repeat(64) + "','1',1)");
            sql("INSERT INTO deployment_unit_configuration_entry VALUES('shared-unit',1,'persistent-value','RUNTIME','TEXT','KEEP')");
        }

        Connection connection() throws Exception {
            var connection = DriverManager.getConnection(jdbc);
            return proxy(Connection.class, (name, args) -> {
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

        DeploymentAgentPreparationAbsence.Captured capture() throws Exception {
            return DeploymentAgentPreparationAbsence.capture(id, journal.find(task).orElseThrow(), layout, journal.events(task)).orElseThrow();
        }

        String proof(DeploymentAgentPreparationAbsence.Captured captured) {
            return DeploymentAgentPreparationAbsence.proof(captured, DeploymentAgentPreparationProbe.requestDigest(captured),
                DeploymentAgentPreparationAbsence.hash(DeploymentAgentPreparationProbe.absentOutput(captured)));
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
