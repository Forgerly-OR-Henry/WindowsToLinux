package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.mod.run.ModRuntimeSession;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.mod.lifecycle.StopReason;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies normal CLASS shutdown after a fully committed running publication. / 验证完整提交且运行的发布之后正常CLASS停用。 */
class DeploymentPublishedApplicationStopTest extends DeploymentPublishAcceptanceFixture {
    /** Keeps single and whole-graph applications running when their author Mod stops normally. / 作者Mod正常停止时保持单应用与整图应用运行。
     * @param count actual discovered component count / 实际发现组件数
     * @throws Exception if actual publication, lifecycle or retained data cannot be verified / 实际发布、生命周期或留存数据无法核实时 */
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void normalClassStopPreservesPublishedRunningApplicationAndFormalData(int count) throws Exception {
        source(count);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var identity = original.db().identity();
            var peer = new DeploymentAssistedBuildPeer(false);
            peer.publicationAttempts = true;
            var running = new HashSet<String>();
            var bound = bind(original, peer, running);
            var workflow = new DeploymentStandardEntryFixture(bound, new DeploymentRefFlow(bound));
            var handle = workflow.submit(new ServerId("server"),
                new DeploymentStandardInputs.Draft("", 65536, 100, 65536), Optional.empty(), reviewer(count), false,
                "MANUAL_REVIEW");
            var publication = handle.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, publication.outcome(), () -> publication + "\nCommands=" + peer.commands);
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(count, running.size());
            assertEquals(running, peer.current.keySet());
            assertTrue(peer.present.isEmpty());
            assertEquals(peer.sessionsOpened, peer.sessionsClosed);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            var before = business(runtime, identity, count);
            var remote = Map.copyOf(peer.current);
            var commands = List.copyOf(peer.commands);
            var session = runtime.modSessions().get(OWNER);
            assertEquals(ModRuntimeSession.SessionState.RUNNING, session.state());
            session.stop(StopReason.APPLICATION_EXIT);
            assertEquals(ModRuntimeSession.SessionState.STOPPED, session.state());
            assertTrue(session.resourcesClosed(), "The actual context and CLASS loader must close successfully");
            assertThrows(ApiException.class,
                () -> original.tasks().submit(new TaskRequest("after_stop", "After stop", List.of(), true),
                    task -> success("unexpected")));
            assertEquals(before, business(runtime, identity, count));
            assertEquals(commands, peer.commands,
                "Normal Mod.stop must send no application stop, rollback or other remote operation");
            assertEquals(remote, peer.current);
            assertEquals(remote.keySet(), running);
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            runtime.close();
            assertEquals(commands, peer.commands);
            assertEquals(remote, peer.current);
            assertEquals(remote.keySet(), running);
            assertTrue(runtime.modContexts().isEmpty());
            assertTrue(runtime.modSessions().isEmpty());
        }
    }

    /** Uses the real original host capabilities and a stateful controlled remote running process. / 使用真实原宿主能力与有状态的受控远端运行进程。
     * @param original authentic CLASS context / 真实CLASS上下文
     * @param peer existing strict build receipt peer / 既有严格构建回执端
     * @param running actual remote running units / 实际远端运行单元
     * @return context with only its remote boundary controlled / 仅远端边界受控的上下文 */
    protected static ModContext bind(ModContext original, DeploymentAssistedBuildPeer peer, Set<String> running) {
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext task) throws ApiException {
                var saved = original.servers().get(expected.server(), task);
                assertEquals(task.id(), expected.taskId());
                assertEquals(saved.ref(), expected.server());
                assertEquals(saved.host(), expected.host());
                assertEquals(saved.port(), expected.port());
                assertEquals(saved.username(), expected.username());
                var session = connect(expected.server(), task);
                try {
                    if (expected.fingerprint().isPresent() && !expected.fingerprint().orElseThrow().equals(session.facts().fingerprint()))
                        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
                            gold.debug.windowstolinux.appapi.task.result.ProblemCode.ACCESS_DENIED, "DEPLOYMENT_TEST_WIRE_CHANGED",
                            "Controlled wire differs from the original frozen target", Map.of(), Optional.of(task.id())));
                    return session;
                } catch (ApiException failure) {
                    try {
                        session.close();
                    } catch (ApiException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }

            public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                var session = peer.session(task);
                return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                    new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                        String script = method.getName().equals("execute")
                            ? ((CommandRequest) args[0]).script()
                            : "";
                        var arguments = quoted(script);
                        if (arguments.size() > 2 && arguments.get(1).equals("observe-deployment"))
                            assertTrue(running.contains(arguments.get(2)),
                                "Remote observation must refer to a physically running published unit");
                        Object result;
                        try {
                            result = method.invoke(session, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                        if (arguments.size() > 2 && arguments.get(1).equals("publish-deployment")) {
                            assertEquals(TaskResult.SUCCEEDED, ((OperationResult<?>) result).outcome());
                            assertTrue(running.add(arguments.get(2)));
                        }
                        return result;
                    });
            }

            public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                return List.of();
            }
        };
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("linux"))
                    return linux;
                try {
                    return method.invoke(original, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    /** Reads complete persisted rows through a separate read-only scope, also after author capability closure. / 经独立只读作用域读取完整持久行，亦可在作者能力关闭后读取。
     * @param runtime actual host / 实际宿主
     * @param identity exact original Schema / 精确原Schema
     * @param count complete expected unit count / 完整预期单元数
     * @return canonical original business row bytes / 规范原业务行字节
     * @throws Exception if retained data differs or is unavailable / 留存数据不符或不可用时 */
    protected static Map<String, List<List<String>>> business(PlatformRuntime runtime, SchemaIdentity identity, int count)
        throws Exception {
        var result = new LinkedHashMap<String, List<List<String>>>();
        try (var access = new SchemaAccessScope(runtime.persistence().database(), identity, UUID.randomUUID(), true);
             var connection = access.openConnection()) {
            for (String table : List.of("managed_application", "managed_application_component", "deployment_unit",
                "deployment_release", "deployment_execution", "deployment_unit_configuration_snapshot",
                "deployment_unit_release_binding")) {
                var rows = new ArrayList<List<String>>();
                try (var statement = connection.createStatement();
                     var query = statement.executeQuery("SELECT * FROM " + table)) {
                    var metadata = query.getMetaData();
                    while (query.next()) {
                        var row = new ArrayList<String>();
                        for (int column = 1; column <= metadata.getColumnCount(); column++) {
                            Object value = query.getObject(column);
                            row.add(value instanceof Blob blob
                                ? Base64.getEncoder()
                                .encodeToString(blob.getBytes(1, Math.toIntExact(blob.length())))
                                : String.valueOf(value));
                        }
                        rows.add(List.copyOf(row));
                    }
                }
                rows.sort(Comparator.comparing(Object::toString));
                result.put(table, List.copyOf(rows));
            }
            assertEquals(1, result.get("managed_application").size());
            assertEquals(count, result.get("deployment_unit").size());
            try (var statement = connection.createStatement();
                 var query = statement
                     .executeQuery("SELECT current_release_sha256,runtime_payload FROM deployment_unit")) {
                int units = 0;
                while (query.next()) {
                    assertTrue(query.getString(1).matches("[0-9a-f]{64}"));
                    assertNotNull(query.getBytes(2));
                    units++;
                }
                assertEquals(count, units);
            }
            try (var statement = connection.createStatement();
                 var query = statement.executeQuery(
                     "SELECT phase,outcome,business_committed,cleanup_complete FROM deployment_execution")) {
                assertTrue(query.next());
                assertEquals("FINISHED", query.getString(1));
                assertEquals("SUCCEEDED", query.getString(2));
                assertTrue(query.getBoolean(3));
                assertTrue(query.getBoolean(4));
                assertFalse(query.next());
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> quoted(String script) {
        if (script.contains(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PATH))
            script = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedBody(script);
        var values = new ArrayList<String>();
        var matcher = Pattern.compile("'([^']*)'").matcher(script);
        while (matcher.find())
            values.add(matcher.group(1));
        return values;
    }

}
