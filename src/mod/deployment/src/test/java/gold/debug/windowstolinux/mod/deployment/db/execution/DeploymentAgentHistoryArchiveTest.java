package gold.debug.windowstolinux.mod.deployment.db.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseIdentity;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseOrigin;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentHistoryArchive;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real H2/CLASS transaction fixtures; no model or SSH peer is started. / 真实H2/CLASS事务夹具，不启动模型或SSH替身。 */
class DeploymentAgentHistoryArchiveTest extends DeploymentAgentArchiveFixture {
    @Test
    void platformRecoverySuccessKeepsOriginalCompensatedFailureAndHistoricalCleanupIsIdempotent() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var original = context.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest(
                gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.TASK_TYPE, "Recovered history", List.of("server"), true), task -> {
                var journal = new DeploymentAgentActionLog(context.db());
                journal.create(task, agentIdentity());
                var id = UUID.randomUUID();
                var identity = new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerId("server"),
                    DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "a".repeat(64));
                var store = new DeploymentExecutionStore(context.db());
                store.begin(task, identity, "deployment-agent", List.of(intent()), Optional.empty(), OBSERVED);
                var ref = context.servers().reserve(new ServerId("server"), intent().businessKey(), task);
                store.recordReference(task, id, intent(), ref.id(), OBSERVED.plusSeconds(1));
                store.startEffects(task, id, OBSERVED.plusSeconds(2));
                var reason = new Problem(ProblemCode.IO_FAILURE, "ORIGINAL_DEPLOYMENT_FAILED", "Verified compensation", Map.of(), Optional.empty());
                store.recordUncommittedOutcome(task, id, TaskResult.FAILED, reason, OBSERVED.plusSeconds(3));
                context.servers().resolve(ref.id(), ResourceRefResult.NOT_REFERENCED, task);
                store.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                journal.event(task, "AI_PUBLICATION_CLEANED", "application", "ABSENT");
                journal.state(task, "FAILED");
                return success(); // Platform certifies recovery closure rather than App publication.
            });
            assertEquals(TaskResult.SUCCEEDED, original.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            run(runtime, current -> {
                var archive = new DeploymentAgentHistoryArchive(context);
                var result = archive.archiveRecoveredHistory(current, original.id());
                assertEquals(TaskResult.FAILED, result.outcome());
                assertEquals("ORIGINAL_DEPLOYMENT_FAILED", result.problem().orElseThrow().eventCode());
                assertEquals(result, archive.archiveRecoveredHistory(current, original.id()));
                assertEquals(0, count(runtime, current, "SELECT COUNT(*) FROM managed_application"));
                assertEquals(0, count(runtime, current, "SELECT COUNT(*) FROM deployment_execution"));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void noExecutionRequiresActualRecoverySettlementProof() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentAgentActionLog(context.db());
                journal.create(task, agentIdentity());
                journal.state(task, "FAILED");
                var failed = new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
                    "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED", "Constructed uncompensated failure", Map.of(), Optional.empty())));
                assertThrows(SQLException.class, () -> new DeploymentAgentHistoryArchive(context).settle(task, failed));
                assertTrue(journal.find(task).isPresent());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                return success();
            });
        }
    }

    @Test
    void restartAfterLocalCommitBeforePlatformEndUsesOnlyReceiptAndNoOriginalDispatch() throws Exception {
        enable();
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", System.getProperty("java.class.path"), getClass().getName(), "--settled-crash", root.toString())
            .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
        UUID original = UUID.fromString(Files.readString(root.resolve("settled-original")));
        try (var runtime = open()) {
            var pages = new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry(new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageHost() {
                public void show(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key, gold.debug.windowstolinux.appapi.ui.page.PageSpec spec, javax.swing.JComponent component) {
                    throw new AssertionError("No UI expected");
                }

                public void focus(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key) {
                }

                public void remove(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key) {
                }

                public void environment(gold.debug.windowstolinux.appapi.ui.page.UiEnvironment environment) {
                }
            }, new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.running().isEmpty());
            assertEquals(List.of("MOD_RECOVERY_READY"), report.problems().stream().map(Problem::eventCode).toList());
            var ended = runtime.tasks().reconcile(original).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED, ended.state());
            assertEquals(Optional.of(TaskResult.SUCCEEDED), ended.outcome());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + original + "'"));
        }
    }

    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("--settled-crash")) throw new IllegalArgumentException();
        try {
            var fixture = new DeploymentAgentHistoryArchiveTest();
            fixture.root = Path.of(args[1]);
            var runtime = fixture.open();
            fixture.start(runtime, true);
            fixture.server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var task = context.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest(
                gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.TASK_TYPE, "Settlement crash gap", List.of("server"), true), original -> {
                fixture.preparedSuccess(runtime, original);
                new DeploymentAgentHistoryArchive(context).settle(original, fixture.success());
                Files.writeString(fixture.root.resolve("settled-original"), original.id().toString());
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                    "CONSTRUCTED_AFTER_SETTLEMENT_CRASH", "Platform ending interrupted after Mod commit", Map.of(), Optional.empty())));
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (task.snapshot().state() != gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN
                && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN, task.snapshot().state());
            assertTrue(Files.isRegularFile(fixture.root.resolve("settled-original")));
            assertEquals(task.id().toString(), Files.readString(fixture.root.resolve("settled-original")));
            assertTrue(!task.completion().toCompletableFuture().isDone(), "The platform original remains unfinished at the crash");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    @Test
    void successRemovesWorkingRowsAndKeepsBusinessAndSharedCreatingOriginAcrossReopen() throws Exception {
        enable();
        var tasks = new ArrayList<UUID>();
        var origin = new AtomicReference<UUID>();
        var release = new AtomicReference<DeploymentReleaseIdentity>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            for (int index = 0; index < 2; index++)
                run(runtime, task -> {
                    var id = preparedSuccess(runtime, task);
                    tasks.add(task.id());
                    var context = runtime.modContexts().get(OWNER).context();
                    var store = new DeploymentExecutionStore(context.db());
                    var history = new DeploymentReleaseStore(context.db()).list(task, "application").getFirst();
                    if (origin.get() == null) {
                        origin.set(id);
                        release.set(history.identity());
                    } else {
                        assertEquals(origin.get(), history.executionId());
                        assertEquals(release.get(), history.identity());
                    }
                    var archive = new DeploymentAgentHistoryArchive(context);
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertTrue(store.findForTask(task).isEmpty());
                    assertTrue(new DeploymentAgentActionLog(context.db()).find(task).isEmpty());
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_event"));
                    verify(runtime, task, graph("application"), publications(graph("application"), 1, List.of()));
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(task.id(), task).isPresent());
                    return success();
                });
            assertEquals(3, scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE state='ACTIVE'"));
            valid(runtime);
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                for (UUID original : tasks) {
                    try (var connection = context.db().openConnection(task)) {
                        var receipt = DeploymentOperationReceipts.read(connection, original).orElseThrow();
                        assertEquals(release.get(), receipt.release().orElseThrow());
                        DeploymentOperationReceipts.validateBusiness(connection, receipt);
                        assertEquals(origin.get(), DeploymentReleaseOrigin.read(connection,
                            DeploymentReleaseStore.read(connection, "application", release.get().id()).orElseThrow()).executionId());
                    }
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(original, task).isPresent());
                }
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"receipt", "events", "task", "execution", "before_commit"})
    void anyWriteFailureKeepsOriginalWorkingEvidenceAndRollsBackTheReceipt(String point) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                preparedSuccess(runtime, task);
                var context = runtime.modContexts().get(OWNER).context();
                var original = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
                var events = new DeploymentAgentActionLog(context.db()).events(task);
                var fault = new DeploymentAgentHistoryArchive(withDatabase(context, fault(context.db(), point)));
                assertThrows(Exception.class, () -> fault.settle(task, success()));
                assertEquals(original, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow());
                assertEquals(events, new DeploymentAgentActionLog(context.db()).events(task));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                new DeploymentAgentHistoryArchive(context).settle(task, success());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void lostCommitAcknowledgementReadsReceiptWithoutReconstructingOrRepeatingWork() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                preparedSuccess(runtime, task);
                var context = runtime.modContexts().get(OWNER).context();
                var failure = assertThrows(ApiException.class, () -> new DeploymentAgentHistoryArchive(
                    withDatabase(context, commitFault(context.db()))).settle(task, success()));
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_execution"));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task"));
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(context).retained(task).orElseThrow().outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void preformalCompensationCanSettleFailureWithoutCreatingAnApplication() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentAgentActionLog(context.db());
                journal.create(task, agentIdentity());
                journal.event(task, "AI_RECOVERY_SETTLED", task.id().toString(), "FAILED:COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED");
                journal.state(task, "FAILED");
                var failed = new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                    ProblemCode.IO_FAILURE, "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED", "Verified no remaining effects", Map.of(), Optional.empty())));
                assertEquals(TaskResult.FAILED, new DeploymentAgentHistoryArchive(context).settle(task, failed).outcome());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM managed_application"));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                try (var connection = context.db().openConnection(task)) {
                    var receipt = DeploymentOperationReceipts.read(connection, task.id()).orElseThrow();
                    assertEquals(TaskResult.FAILED, receipt.outcome());
                    assertTrue(receipt.release().isEmpty());
                }
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "FAILED", "PENDING", "BUSY"})
    void unresolvedStateEffectOrExplicitResourceCannotBeRemoved(String state) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                preparedSuccess(runtime, task);
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentAgentActionLog(context.db());
                if (Set.of("UNKNOWN", "FAILED").contains(state)) journal.state(task, state);
                if (state.equals("PENDING")) journal.event(task, "COMMAND_EXECUTING", "pending-command", "original");
                gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint held = state.equals("BUSY")
                    ? context.tasks().beginCheckpoint(task, "fixture.held", "{\"formatVersion\":1}") : null;
                try {
                    if (held == null)
                        assertThrows(SQLException.class, () -> new DeploymentAgentHistoryArchive(context).settle(task, success()));
                    else
                        assertEquals(TaskResult.UNKNOWN, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_execution"));
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task"));
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                } finally {
                    if (held != null) held.close();
                }
                if (state.equals("PENDING")) journal.event(task, "COMMAND_RESULT", "pending-command", "verified");
                journal.state(task, "SUCCEEDED");
                new DeploymentAgentHistoryArchive(context).settle(task, success());
                return success();
            });
        }
    }

    private static ModContext withDatabase(ModContext original, ModDatabase database) {
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> method.getName().equals("db") ? database : invoke(method, original, args));
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    private static ModDatabase fault(ModDatabase original, String point) {
        return new ModDatabase() {
            public gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity identity() throws ApiException {
                return original.identity();
            }

            public Connection openConnection(TaskContext task) throws SQLException, ApiException {
                var connection = original.openConnection(task);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit") && point.equals("before_commit"))
                        throw new SQLException("Pre-commit fixture failure");
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        boolean fail = point.equals("receipt") && sql.startsWith("INSERT INTO deployment_operation_receipt")
                            || point.equals("events") && sql.startsWith("DELETE FROM deployment_agent_event")
                            || point.equals("task") && sql.startsWith("DELETE FROM deployment_agent_task")
                            || point.equals("execution") && sql.startsWith("DELETE FROM deployment_execution");
                        if (fail) throw new SQLException("Cleanup statement fixture failure");
                    }
                    return invoke(method, connection, args);
                });
            }
        };
    }
}
