package gold.debug.windowstolinux.mod.deployment.service.standard;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises real successful publication before health rollback to nonempty running old units. / 先真实成功发布，再健康失败回滚至非空运行旧单元。 */
class DeploymentPreviousRunningPublicationTest extends DeploymentPublicationAcceptanceFixture {
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
            var first = new DeploymentPublicationStatePeer();
            var initial = submit(original, first, count);
            assertEquals(Outcome.SUCCEEDED,
                initial.completion().toCompletableFuture().get(60, TimeUnit.SECONDS).outcome());
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
            var next = new DeploymentPublicationStatePeer();
            next.builds.current.putAll(oldRemote);
            next.running.addAll(first.running);
            next.builds.healthFailuresRemaining = 1;
            var failed = submit(original, next, count);
            assertEquals(Outcome.FAILED, failed.completion().toCompletableFuture().get(60, TimeUnit.SECONDS).outcome());
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

    private TaskHandle<Void> submit(ModContext original, DeploymentPublicationStatePeer peer, int count)
        throws ApiException {
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
                return peer.session(task);
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
        return new DeploymentStaticWorkflow(bound, new DeploymentReferenceFlow(bound)).submit(new ServerRef("server"),
            new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536), Optional.empty(), reviewer(count), false,
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
