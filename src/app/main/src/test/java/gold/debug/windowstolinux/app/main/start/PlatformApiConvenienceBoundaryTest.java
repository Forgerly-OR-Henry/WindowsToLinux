package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.completed;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformApiConvenienceBoundaryTest {
    @TempDir
    Path root;

    @Test
    void firstTaskPageKeepsOriginalOwnerPaginationAndClosedBindingRules() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var release = new CountDownLatch(1);
            var handles = new ArrayList<TaskHandle<Void>>();
            try {
                for (int index = 0; index < 3; index++)
                    handles.add(tasks.submit(request(), task -> {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        return success(null);
                    }));
                for (var handle : handles) state(handle, TaskState.RUNNING);
                var first = tasks.list(1);
                assertEquals(1, first.items().size());
                assertTrue(first.nextCursor().isPresent());
                var second = tasks.list(first.nextCursor(), 1);
                var third = tasks.list(second.nextCursor(), 1);
                assertTrue(third.nextCursor().isEmpty());
                var visible = new HashSet<>(List.of(first.items().getFirst().id(), second.items().getFirst().id(), third.items().getFirst().id()));
                assertEquals(handles.stream().map(TaskHandle::id).collect(java.util.stream.Collectors.toSet()), visible);
                assertTrue(runtime.tasks().bind("foreign.owner", VERSION, false).list(200).items().isEmpty());
                for (int limit : new int[]{-1, 0, 201, Integer.MAX_VALUE})
                    assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class, () -> tasks.list(limit)).problem().code());
                release.countDown();
                for (var handle : handles)
                    assertEquals(TaskResult.SUCCEEDED,
                        handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
                assertTrue(tasks.list(1).items().isEmpty());
                runtime.tasks().revokeOwnerServices(OWNER, VERSION);
                assertEquals("MOD_TASK_BINDING_CLOSED", assertThrows(ApiException.class, () -> tasks.list(1)).problem().eventCode());
            } finally {
                release.countDown();
                for (var handle : handles) handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void firstServerPagesPreserveAdmissionDiscoveryAndAuthenticTaskIdentity() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            for (var id : List.of("server-a", "server-z"))
                runtime.persistence().servers().saveServerProfile(
                    new StoredServerProfile(id, "host.example", 22, "alice", "credential", id));
            var access = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.empty());
            var foreign = runtime.capabilities().serverAccess("foreign.owner", VERSION, Optional.empty());
            var original = new AtomicReference<TaskContext>();
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request("server", "server-a"), task -> {
                original.set(task);
                var first = access.list(1, task);
                assertEquals("server", first.items().getFirst().ref().id());
                var next = access.list(first.nextCursor(), 1, task);
                assertEquals("server-a", next.items().getFirst().ref().id());
                assertTrue(next.nextCursor().isEmpty());
                var discovered = access.listConfigured(1, task);
                assertEquals("server", discovered.items().getFirst().ref().id());
                var middle = access.listConfigured(discovered.nextCursor(), 1, task);
                var last = access.listConfigured(middle.nextCursor(), 1, task);
                assertEquals("server-z", last.items().getFirst().ref().id());
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class,
                    () -> access.get(new ServerId("server-z"), task)).problem().code());
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class,
                    () -> foreign.list(1, task)).problem().code());
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class,
                    () -> foreign.listConfigured(1, task)).problem().code());
                var forged = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
                    new Class<?>[]{TaskContext.class}, (proxy, method, args) ->
                        method.getName().equals("id") ? task.id() : method.invoke(task, args));
                assertEquals("TASK_CONTEXT", assertThrows(ApiException.class,
                    () -> access.list(1, forged)).problem().eventCode());
                assertEquals("TASK_CONTEXT", assertThrows(ApiException.class,
                    () -> access.listConfigured(1, forged)).problem().eventCode());
                for (int limit : new int[]{-1, 0, 201, Integer.MAX_VALUE}) {
                    assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class,
                        () -> access.list(limit, task)).problem().code());
                    assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class,
                        () -> access.listConfigured(limit, task)).problem().code());
                }
                return success(null);
            }));
            assertThrows(ApiException.class, () -> access.list(1, original.get()));
            assertThrows(ApiException.class, () -> access.listConfigured(1, original.get()));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void progressOverloadsValidateFractionsAndPersistOnlyTheAuthorsActualProgressText() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                task.progress("原始阶段，没有已知比例");
                assertEquals("原始阶段，没有已知比例", progress(runtime, task.id()));
                task.progress("已完成实际检查", 0.25);
                assertEquals("已完成实际检查", progress(runtime, task.id()));
                assertEquals("已完成实际检查", runtime.tasks().requireContext(task).progressText());
                for (double fraction : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.1, 1.1}) {
                    assertEquals("TASK_PROGRESS", assertThrows(ApiException.class,
                        () -> task.progress("不应落库的坏比例", fraction)).problem().eventCode());
                    assertEquals("已完成实际检查", progress(runtime, task.id()));
                }
                assertThrows(ApiException.class, () -> task.progress("x".repeat(4097)));
                assertThrows(ApiException.class, () -> task.progress(null));
                assertEquals("已完成实际检查", progress(runtime, task.id()));
                task.progress("实际零比例", 0);
                assertEquals("实际零比例", progress(runtime, task.id()));
                task.progress("实际完整比例", 1);
                assertEquals("实际完整比例", progress(runtime, task.id()));
                return success(null);
            }));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
        }
    }

    static String progress(PlatformRuntime runtime, UUID id) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
             var query = connection.prepareStatement("SELECT progress_text FROM platform_task WHERE task_id=?")) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }
}
