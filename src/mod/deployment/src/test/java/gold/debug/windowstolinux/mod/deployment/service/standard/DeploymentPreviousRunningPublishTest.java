package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises real successful publication before health rollback to nonempty running old units. / 先真实成功发布，再健康失败回滚至非空运行旧单元。 */
class DeploymentPreviousRunningPublishTest extends DeploymentPublishAcceptanceFixture {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void knownHealthFailureRestoresEveryOriginalRunningUnitAndFormalCurrent(int count) throws Exception {
        source(count);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var first = new DeploymentPublishStatePeer();
            var initial = submit(original, first, count);
            var initialResult = initial.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, initialResult.outcome(), initialResult.toString());
            assertEquals(TaskState.ENDED, initial.snapshot().state());
            var oldRemote = Map.copyOf(first.builds.current);
            assertEquals(count, oldRemote.size());
            assertEquals(oldRemote.keySet(), first.running);
            var oldFormal = current(runtime, original.db().identity());
            for (int index = 0; index < count; index++) {
                var directory = count == 1
                    ? selectedSourceDirectory()
                    : selectedSourceDirectory().resolve("component" + index);
                Files.writeString(directory.resolve("go.mod"),
                    "module example.test/component" + index + "\n\ngo 1.25\n");
            }
            technicalVersion = "1.25";
            var next = new DeploymentPublishStatePeer();
            next.builds.current.putAll(oldRemote);
            next.running.addAll(first.running);
            next.builds.healthFailuresRemaining = 1;
            var failed = submit(original, next, count);
            var failedResult = failed.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertEquals(TaskResult.FAILED, failedResult.outcome(), failedResult.toString());
            assertEquals(TaskState.ENDED, failed.snapshot().state());
            assertNotEquals(initial.id(), failed.id());
            assertEquals(oldRemote, next.builds.current);
            assertEquals(oldRemote.keySet(), next.running);
            assertEquals(oldFormal, current(runtime, original.db().identity()));
            assertTrue(next.rollbacks > 0);
            assertEquals(0, corrections);
            assertTrue(next.snapshots.isEmpty());
            assertTrue(next.maintenance.isEmpty());
            assertTrue(next.builds.present.isEmpty());
            assertEquals(next.builds.sessionsOpened, next.builds.sessionsClosed);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            try (var scope = new SchemaAccessScope(runtime.persistence().database(), original.db().identity(),
                UUID.randomUUID(), true);
                 var connection = scope.openConnection();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                     "SELECT outcome,business_committed,cleanup_complete FROM deployment_execution ORDER BY created_at")) {
                assertTrue(rows.next());
                assertEquals("SUCCEEDED", rows.getString(1));
                assertTrue(rows.getBoolean(2));
                assertTrue(rows.getBoolean(3));
                assertTrue(rows.next());
                assertEquals("FAILED", rows.getString(1));
                assertFalse(rows.getBoolean(2));
                assertTrue(rows.getBoolean(3));
                assertFalse(rows.next());
            }
        }
    }

    private TaskHandle<Void> submit(ModContext original, DeploymentPublishStatePeer peer, int count)
        throws ApiException {
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                return peer.session(task);
            }

            public RemoteSession connect(RemoteTarget expected, TaskContext task) throws ApiException {
                assertEquals(new ServerId("server"), expected.server());
                assertEquals(task.id(), expected.taskId());
                assertEquals("127.0.0.1", expected.host());
                assertEquals(22, expected.port());
                assertEquals("root", expected.username());
                var server = original.servers().get(expected.server(), task);
                assertEquals(server.ref(), expected.server());
                assertEquals(server.host(), expected.host());
                assertEquals(server.port(), expected.port());
                assertEquals(server.username(), expected.username());
                var session = peer.session(task);
                try {
                    if (expected.fingerprint().isPresent()
                        && !expected.fingerprint().orElseThrow().equals(session.facts().fingerprint()))
                        throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                            "DEPLOYMENT_TEST_WIRE_CHANGED",
                            "The controlled transport rejects a different actual wire before any command",
                            Map.of(), Optional.of(task.id())));
                    return session;
                } catch (ApiException | RuntimeException | Error failure) {
                    try {
                        session.close();
                    } catch (ApiException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }

            public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                return List.of();
            }
        };
        var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
            new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("linux"))
                    return linux;
                try {
                    return method.invoke(original, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        return new DeploymentStandardEntryFixture(bound, new DeploymentRefFlow(bound)).submit(new ServerId("server"),
            new DeploymentStandardInputs.Draft("", 65536, 100, 65536), Optional.empty(), reviewer(count), false,
            "MANUAL_REVIEW");
    }

    private Map<String, List<String>> current(PlatformRuntime runtime, SchemaIdentity identity) throws Exception {
        var values = new TreeMap<String, List<String>>();
        try (var scope = new SchemaAccessScope(runtime.persistence().database(), identity, UUID.randomUUID(), true);
             var connection = scope.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT deployment_unit_id,current_release_sha256,current_release_published_at,runtime_payload FROM deployment_unit")) {
            while (rows.next())
                values.put(rows.getString(1), List.of(rows.getString(2), rows.getString(3),
                    Base64.getEncoder().encodeToString(rows.getBytes(4))));
        }
        return values;
    }
}
