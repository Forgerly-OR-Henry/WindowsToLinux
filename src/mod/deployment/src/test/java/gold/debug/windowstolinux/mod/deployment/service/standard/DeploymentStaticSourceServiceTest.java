package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.file.source.FileSelection;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectStore;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture;
import gold.debug.windowstolinux.mod.deployment.file.git.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;
import org.junit.jupiter.api.Test;

/** Uses real CLASS tasks, source grants, frozen files and complete owned H2 rows. / 使用真实 CLASS 任务、来源授权、冻结文件及完整所属 H2 行。 */
class DeploymentStaticSourceServiceTest extends DeploymentRuntimeFixture {
    private static final Instant OBSERVED = Instant.parse("2026-10-05T00:00:00Z");

    private boolean nativeGitScenario;

    @Override
    protected boolean gitEnabled() {
        return nativeGitScenario;
    }

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Test
    void pickerClosesOriginalGrantAndSaveReauthorizesChangedLocationWithOneFreeze() throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("main.txt"), "before preselection");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var original = runtime.modContexts().get(OWNER).context();
            var selects = new AtomicInteger();
            var captures = new AtomicInteger();
            var oldGrant = new AtomicReference<FileSelection>();
            var frozen = new AtomicReference<String>();
            var file = DeploymentFileServiceFixture.create(FileService.class.getClassLoader(), (proxy, method, arguments) -> {
                Object result;
                try {
                    result = DeploymentFileServiceFixture.invoke(method, original.file(), arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
                if (method.getName().equals("selectDirectory")) {
                    selects.incrementAndGet();
                    oldGrant.compareAndSet(null, (FileSelection) result);
                }
                if (method.getName().equals("snapshot")) {
                    captures.incrementAndGet();
                    frozen.set(((FileSnapshot) result).sha256());
                }
                return result;
            });
            var sources = new DeploymentStaticSourceService(bound(original, file, original.db()));
            var picked = sources.select("Source", Optional.empty());
            var metadata = picked.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow();
            assertEquals(TaskState.ENDED, picked.snapshot().state());
            assertEquals(selectedSourceDirectory().toRealPath().toUri(), metadata.location());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            Files.writeString(selectedSourceDirectory().resolve("main.txt"), "changed before the real save task");
            UUID id = UUID.randomUUID();
            var reviewed = new AtomicReference<UUID>();
            var saved = sources.saveLocal(metadata, new DeploymentStaticSourceService.ProjectEdit(id, "named source",
                Optional.empty(), OBSERVED), 65536, 100, (row, task) -> {
                reviewed.set(task.id());
                assertNotEquals(picked.id(), task.id());
                assertThrows(ApiException.class, () -> original.file().sources().location(oldGrant.get(), task));
                assertEquals(frozen.get(), row.source().sha256());
                Files.writeString(selectedSourceDirectory().resolve("main.txt"), "changed after freezing");
                return true;
            });
            var result = saved.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            var row = result.value().orElseThrow();
            assertEquals(saved.id(), reviewed.get());
            assertEquals(frozen.get(), row.source().sha256());
            assertEquals(2, selects.get());
            assertEquals(1, captures.get());
            var list = sources.projects().completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(List.of(row), list.value().orElseThrow());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void localSaveRevisesExactGitRowAndRejectsStaleCompleteRowWithoutAnotherIdentity() throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("main.txt"), "one frozen revision");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            UUID id = UUID.randomUUID();
            var old = new DeploymentProjectRecord(id, "same name", new DeploymentProjectSource(
                DeploymentProjectSource.KindType.GIT, java.net.URI.create("https://example.invalid/repository.git"),
                Optional.of("BRANCH:main"), Optional.of("a".repeat(40)), "b".repeat(64)), OBSERVED, OBSERVED);
            var seed = context.tasks().submit(new TaskRequest("source-test-seed", "Original row", List.of(), true), task -> {
                new DeploymentProjectStore(context.db()).create(task, old);
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskResult.SUCCEEDED, seed.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            var service = new DeploymentStaticSourceService(context);
            var location = new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentSourceSelectionService.LocalLocation(selectedSourceDirectory().toRealPath().toUri(), "input");
            var edit = new DeploymentStaticSourceService.ProjectEdit(id, "same name", Optional.of(old), OBSERVED.plusMillis(1));
            var saved = service.saveLocal(location, edit, 65536, 100, (row, task) -> true)
                .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, saved.outcome(), saved.toString());
            var next = saved.value().orElseThrow();
            assertEquals(id, next.id());
            assertEquals(old.createdAt(), next.createdAt());
            assertEquals(DeploymentProjectSource.KindType.LOCAL, next.source().kind());
            assertTrue(next.source().gitCommit().isEmpty());
            assertTrue(next.source().gitRef().isEmpty());
            var stale = service.saveLocal(location, new DeploymentStaticSourceService.ProjectEdit(id, "stale edit",
                    Optional.of(old), OBSERVED.plusMillis(2)), 65536, 100, (row, task) -> true)
                .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskResult.FAILED, stale.outcome(), stale.toString());
            assertEquals(List.of(next), service.projects().completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void lostCommitAcknowledgementReconcilesOriginalRowWithoutAnotherFreezeOrWrite() throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("main.txt"), "actual source");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var original = runtime.modContexts().get(OWNER).context();
            var captures = new AtomicInteger();
            var writes = new AtomicInteger();
            var file = DeploymentFileServiceFixture.create(FileService.class.getClassLoader(), (proxy, method, arguments) -> {
                if (method.getName().equals("snapshot")) captures.incrementAndGet();
                try {
                    return DeploymentFileServiceFixture.invoke(method, original.file(), arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            var service = new DeploymentStaticSourceService(bound(original, file, commitFault(original.db(), writes)));
            UUID id = UUID.randomUUID();
            var handle = service.saveLocal(new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentSourceSelectionService.LocalLocation(selectedSourceDirectory().toRealPath().toUri(), "input"),
                new DeploymentStaticSourceService.ProjectEdit(id, "original", Optional.empty(), OBSERVED), 65536, 100, (row, task) -> true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertFalse(handle.completion().toCompletableFuture().isDone());
            assertEquals(1, captures.get());
            assertEquals(1, writes.get());
            Files.delete(selectedSourceDirectory().resolve("main.txt"));
            Files.delete(selectedSourceDirectory());
            var recovered = reconcileReady(runtime, handle);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(TaskResult.SUCCEEDED), recovered.outcome());
            assertEquals(1, captures.get());
            assertEquals(1, writes.get());
            var rows = new DeploymentStaticSourceService(original).projects().completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow();
            assertEquals(1, rows.size());
            assertEquals(id, rows.getFirst().id());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void actualLocalGitAndLocalRevisionsPreserveOneProjectAndRejectTheStaleWholeRow() throws Exception {
        nativeGitScenario = true;
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("main.txt"), "original local revision");
        var executable = Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
            .map(directory -> Path.of(directory).resolve("git.exe")).filter(Files::isRegularFile).findFirst().orElseThrow().toAbsolutePath().normalize();
        var repository = Files.createDirectory(root.resolve("native-project"));
        git(executable, repository, "init", "-b", "main");
        git(executable, repository, "config", "user.email", "test@example.invalid");
        git(executable, repository, "config", "user.name", "Test");
        Files.writeString(repository.resolve("main.txt"), "actual native Git revision");
        git(executable, repository, "add", ".");
        git(executable, repository, "commit", "-m", "Original source");
        String commit = git(executable, repository, "rev-parse", "HEAD");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var service = new DeploymentStaticSourceService(context);
            UUID id = UUID.randomUUID();
            var localLocation = new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentSourceSelectionService.LocalLocation(selectedSourceDirectory().toRealPath().toUri(), "input");
            var first = service.saveLocal(localLocation, new DeploymentStaticSourceService.ProjectEdit(id, "same original project", Optional.empty(), OBSERVED),
                65536, 100, (row, task) -> true).completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow();
            var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repository.toUri().toString()), new DeploymentGitRef.Branch("main"), Set.of(), 65536, true);
            var gitResult = GitRuntimeTestBridge.flow(runtime, context).submit(request, executable, id, first.displayName(), Optional.of(first), OBSERVED.plusMillis(1),
                65536, 100).completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, gitResult.outcome(), gitResult.toString());
            var second = gitResult.value().orElseThrow();
            assertEquals(id, second.id());
            assertEquals(first.createdAt(), second.createdAt());
            assertEquals(commit, second.source().gitCommit().orElseThrow());
            assertEquals("BRANCH:main", second.source().gitRef().orElseThrow());
            Files.writeString(selectedSourceDirectory().resolve("main.txt"), "next original local revision");
            var localResult = service.saveLocal(localLocation, new DeploymentStaticSourceService.ProjectEdit(id, "revised local name", Optional.of(second), OBSERVED.plusMillis(2)),
                65536, 100, (row, task) -> true).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, localResult.outcome(), localResult.toString());
            var third = localResult.value().orElseThrow();
            assertEquals(id, third.id());
            assertEquals(first.createdAt(), third.createdAt());
            assertEquals(DeploymentProjectSource.KindType.LOCAL, third.source().kind());
            assertTrue(third.source().gitCommit().isEmpty());
            assertTrue(third.source().gitRef().isEmpty());
            var stale = GitRuntimeTestBridge.flow(runtime, context).submit(request, executable, id, "stale Git revision", Optional.of(second), OBSERVED.plusMillis(3),
                65536, 100).completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertEquals(TaskResult.FAILED, stale.outcome(), stale.toString());
            assertEquals(List.of(third), service.projects().completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    private static String git(Path executable, Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        var child = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        child.getOutputStream().close();
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        assertEquals(0, child.waitFor(), output);
        return output;
    }

    private static ModContext bound(ModContext original, FileService file, ModDatabase database) {
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("file")) return file;
            if (method.getName().equals("db")) return database;
            try {
                return method.invoke(original, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }

    private static ModDatabase commitFault(ModDatabase original, AtomicInteger writes) {
        return new ModDatabase() {
            public SchemaIdentity identity() throws ApiException {
                return original.identity();
            }

            public Connection openConnection(TaskContext task) throws ApiException, SQLException {
                var connection = original.openConnection(task);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(connection, arguments);
                        if (method.getName().equals("commit")) {
                            writes.incrementAndGet();
                            throw new SQLException("Original commit acknowledgement lost");
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            }
        };
    }
}
