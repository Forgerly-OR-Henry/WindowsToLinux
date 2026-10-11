package gold.debug.windowstolinux.mod.deployment.linux.database;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentDatabaseDeclarations;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentSqliteSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public transport is controlled; real framing, action identity and SQLite preparation consume explicit layout. */
class DeploymentDatabaseLayoutTest {
    private static final UUID TASK = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final ServerId SERVER = new ServerId("server-layout-523");
    private static final DeploymentRemoteLayout LAYOUT = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data"));
    @TempDir Path local;

    @Test
    void nativeDatabaseDispatchUsesCapturedHelperAndFullExactReviewIdentity() throws Exception {
        var peer = new Peer(false);
        var before = new AtomicInteger();
        byte[] frame = DeploymentNativeDatabaseWire.frame(Map.of("engine", "POSTGRESQL"));
        var result = new DeploymentNativeDatabaseWire(peer.session, peer, SERVER, peer.task, LAYOUT)
            .send("inspect", frame, "e".repeat(64), "POSTGRESQL", before::incrementAndGet);
        assertEquals(TaskResult.SUCCEEDED, result.outcome());
        assertEquals(1, before.get());
        assertEquals(1, peer.dispatches);
        assertArrayEquals(frame, peer.sentInput);
        assertEquals("'/srv/wtl/helper/managed-helper' native-db inspect",
            DeploymentManagedHelperBundle.verifiedBody(peer.sentScript, LAYOUT));
        assertTrue(peer.sentScript.contains(DeploymentManagedHelperBundle.helperSha256(LAYOUT)));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedBody(peer.sentScript));
        assertEquals(Set.of(new ActionTarget(SERVER.id(), "native-database", "POSTGRESQL")),
            peer.intent.semantics().orElseThrow().targets());
    }

    @Test
    void refusedExactDatabaseReviewDoesNotAdvanceOrSend() throws Exception {
        var peer = new Peer(true);
        var before = new AtomicInteger();
        var result = new DeploymentNativeDatabaseWire(peer.session, peer, SERVER, peer.task, LAYOUT)
            .send("status", new byte[0], "e".repeat(64), "original-op", before::incrementAndGet);
        assertEquals(TaskResult.FAILED, result.outcome());
        assertEquals(0, before.get());
        assertEquals(0, peer.dispatches);
        assertEquals("'/srv/wtl/helper/managed-helper' native-db status",
            DeploymentManagedHelperBundle.verifiedBody(peer.approvedScript, LAYOUT));
    }

    @Test
    void sqlitePreparationPublishesCapturedDefaultPhysicalPathWithoutOverridingExplicitAccess() throws Exception {
        Path declaration = local.resolve("windowstolinux-db.properties");
        Files.writeString(declaration, "db=main\ndb.main.engine=SQLITE\ndb.main.path=DEFAULT\ndb.main.pathEnvironment=DB_FILE\n");
        var checked = new DatabaseProjectChecker().inspect(local, "demo");
        var complete = DeploymentDatabaseDeclarations.complete(local, "demo", checked, Map.of(), LAYOUT);
        var captured = DeploymentSqliteSetup.prepare(local, "demo", complete, false, task(), LAYOUT);
        assertEquals("/srv/wtl/data/demo/databases/main/application.db",
            captured.configuration().getFirst().value().canonicalValue());
        assertEquals("/var/opt/windowstolinux/apps/demo/databases/main/application.db",
            DeploymentSqliteSetup.prepare(local, "demo", complete, false, task()).configuration().getFirst().value().canonicalValue());
        Files.writeString(declaration, "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\ndb.main.pathEnvironment=DB_FILE\n");
        var explicit = DeploymentDatabaseDeclarations.complete(local, "demo",
            new DatabaseProjectChecker().inspect(local, "demo"), Map.of(), LAYOUT);
        assertEquals("state/store.db", DeploymentSqliteSetup.prepare(local, "demo", explicit, false, task(), LAYOUT)
            .configuration().getFirst().value().canonicalValue());
    }

    private static TaskContext task() {
        return (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "id" -> TASK;
                case "cancelToken" -> (CancelToken) () -> false;
                default -> throw new AssertionError("Unexpected task method: " + method.getName());
            });
    }

    private static final class Peer implements DeploymentCommandReview {
        private final TaskContext task = task();
        private final RemoteSession session;
        private final boolean reject;
        private ActionIntent intent;
        private ApprovalGrant grant;
        private String approvedScript, sentScript;
        private byte[] sentInput;
        private int dispatches;

        private Peer(boolean reject) {
            this.reject = reject;
            session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                    assertEquals("execute", method.getName());
                    assertFalse(this.reject, "A rejected request cannot reach transport");
                    assertSame(grant, args[1]);
                    var request = (CommandRequest) args[0];
                    assertEquals(RemoteActions.command(SERVER, intent.revision(), request)
                        .withSemantics(intent.semantics().orElseThrow()), intent);
                    sentScript = request.script();
                    sentInput = request.stdin();
                    dispatches++;
                    return success(new CommandResult(Optional.of(0), "CONTROLLED_REPLY=1\n", "", false));
                });
        }

        public OperationResult<ApprovalGrant> approveCommand(ActionIntent intent, CommandRequest request, TaskContext actual) {
            assertSame(task, actual);
            assertEquals(RemoteActions.command(SERVER, intent.revision(), request)
                .withSemantics(intent.semantics().orElseThrow()), intent);
            this.intent = intent;
            approvedScript = request.script();
            if (reject) return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                ProblemCode.ACCESS_DENIED, "CONTROLLED_DENY", "Controlled refusal", Map.of(), Optional.empty())));
            grant = new ApprovalGrant(UUID.randomUUID());
            return success(grant);
        }

        public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) { throw new AssertionError(); }
        public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task) { throw new AssertionError(); }
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
