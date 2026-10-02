package gold.debug.windowstolinux.mod.deployment.file;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskWork;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.file.FileReadRequest;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.FileSnapshot;

import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentSourcePreparerTest extends DeploymentRuntimeFixture {
    static final String DECLARATION = "windowstolinux-application.properties";

    Path file(String path, String content) throws Exception {
        Path destination = root.resolve("input").resolve(path);
        Files.createDirectories(destination.getParent());
        return Files.writeString(destination, content);
    }

    void run(PlatformRuntime runtime, TaskWork<Void> work) throws Exception {
        var error = new AtomicReference<Throwable>();
        var task = runtime.modContexts().get(OWNER).context().tasks()
            .submit(new TaskRequest("source_preparation", "Source preparation", List.of(), true), context -> {
                try {
                    return work.run(context);
                } catch (Exception | AssertionError failure) {
                    error.set(failure);
                    throw failure;
                }
            });
        var result = task.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        if (error.get() != null)
            throw new AssertionError("Source callback failed", error.get());
        assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
    }

    OperationResult<Void> success() {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    void noSnapshots(PlatformRuntime runtime) throws Exception {
        assertEquals(0, scalar(runtime,
            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
    }

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    FileService file(PlatformRuntime runtime) {
        return runtime.modContexts().get(OWNER).context().file();
    }

    @Test
    void originalDeploymentExclusionsAndExplicitSampleLogProduceOneOwnedFrozenView() throws Exception {
        file("src/中文.java", "original");
        file("pom.xml", "project");
        file("fixtures/sample.log", "fixture");
        file(".env", "not-read");
        file("secret.KEY", "not-read");
        file(".env.example", "not-read");
        file("other.LOG", "not-read");
        file(".git/config", "not-read");
        file("target/output", "not-read");
        file("node_modules/library", "not-read");
        file("logs/service.log", "not-read");
        file(DECLARATION, "source.include=fixtures/sample.log\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var original = file(runtime);
            var reads = new ArrayList<String>();
            var directories = new ArrayList<String>();
            var observing = (FileService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FileService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("read"))
                        reads.add(((FileReadRequest) args[1]).relativePath());
                    if (method.getName().equals("list"))
                        directories.add((String) args[1]);
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            run(runtime, task -> {
                var selection = observing.selectDirectory("源码", task);
                var material = new DeploymentSourcePreparer(observing).prepare(selection, 4096, 100, task);
                Path frozen = material.snapshot().directory();
                String digest = material.snapshot().sha256();
                try (material) {
                    assertEquals(List.of("fixtures/sample.log", "pom.xml", "src/中文.java", DECLARATION),
                        material.includedFiles());
                    assertEquals(List.of(".env", ".env.example", ".git/", "logs/", "node_modules/", "other.LOG",
                        "secret.KEY", "target/"), material.excludedEntries());
                    assertEquals(List.of(DECLARATION), reads);
                    assertEquals(List.of("", "fixtures", "src"), directories);
                    assertEquals("input", material.displayName());
                    assertFalse(Files.exists(frozen.resolve(".env")));
                    file("src/中文.java", "changed original");
                    assertEquals("original", Files.readString(frozen.resolve("src/中文.java")));
                    assertEquals(digest, material.snapshot().sha256());
                    assertEquals(1, scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                }
                assertFalse(Files.exists(frozen));
                assertThrows(IllegalStateException.class, material.snapshot()::directory);
                noSnapshots(runtime);
                return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {".env", "secret.pem", "target/resource", "logs/fixture.log", "missing.log", "file,file",
        "../outside", "directory", "a/./b"})
    void explicitResourcesCannotOverrideSecretDirectoryOrOrdinaryFileBoundaries(String include) throws Exception {
        file(".env", "secret-fixture");
        file("secret.pem", "fixture");
        file("target/resource", "fixture");
        file("logs/fixture.log", "fixture");
        file("file", "fixture");
        Files.createDirectories(root.resolve("input/directory"));
        file(DECLARATION, "source.include=" + include + "\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var source = file(runtime);
            run(runtime, task -> {
                var selection = source.selectDirectory("Source", task);
                assertThrows(ApiException.class,
                    () -> new DeploymentSourcePreparer(source).prepare(selection, 4096, 100, task));
                noSnapshots(runtime);
                assertEquals("secret-fixture", Files.readString(root.resolve("input/.env")));
                return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes", "entries", "declaration-size"})
    void contentDiscoveryAndDeclarationBudgetsFailBeforeAnySnapshotIsRetained(String budget) throws Exception {
        file("file", "content");
        file("nested/child", "content");
        if (budget.equals("declaration-size"))
            file(DECLARATION, "x".repeat(65_537));
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var source = file(runtime);
            run(runtime, task -> {
                var selection = source.selectDirectory("Source", task);
                assertThrows(ApiException.class, () -> new DeploymentSourcePreparer(source).prepare(selection,
                    budget.equals("bytes") ? 1 : 4096, budget.equals("entries") ? 2 : 100, task));
                noSnapshots(runtime);
                return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declarationChangesBeforeCaptureRejectMaterialAndPreserveAnyUnconfirmedCleanup(boolean cleanupFails)
        throws Exception {
        file("sample.log", "fixture");
        file(DECLARATION, "source.include=sample.log\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var source = file(runtime);
            var held = new AtomicReference<FileSnapshot>();
            var changed = (FileService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FileService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("snapshot"))
                        file(DECLARATION, "source.include=\n");
                    Object result;
                    try {
                        result = method.invoke(source, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (result instanceof FileSnapshot snapshot) {
                        held.set(snapshot);
                        if (cleanupFails)
                            return new FileSnapshot() {
                                public UUID id() {
                                    return snapshot.id();
                                }

                                public String sha256() {
                                    return snapshot.sha256();
                                }

                                public Path directory() {
                                    return snapshot.directory();
                                }

                                public void close() throws ApiException {
                                    throw new ApiException(
                                        new Problem(ProblemCode.UNKNOWN, "FIXTURE_CLEANUP_UNKNOWN",
                                            "Cleanup unavailable", Map.of(), Optional.empty()));
                                }
                            };
                    }
                    return result;
                });
            run(runtime, task -> {
                var selection = changed.selectDirectory("Source", task);
                var failure = assertThrows(ApiException.class,
                    () -> new DeploymentSourcePreparer(changed).prepare(selection, 4096, 100, task));
                assertEquals(cleanupFails ? ProblemCode.UNKNOWN : ProblemCode.IO_FAILURE, failure.problem().code());
                if (cleanupFails) {
                    assertEquals(1, scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                    held.get().close();
                }
                noSnapshots(runtime);
                return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAfterPhysicalCaptureClosesTheOriginalSnapshotBeforeTaskCompletion(boolean declaration)
        throws Exception {
        file("file", "fixture");
        if (declaration)
            file(DECLARATION, "source.include=\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var source = file(runtime);
            var captured = new CountDownLatch(1);
            var proceed = new CountDownLatch(1);
            var held = new AtomicReference<FileSnapshot>();
            var error = new AtomicReference<Throwable>();
            var delayed = (FileService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FileService.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(source, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (result instanceof FileSnapshot snapshot) {
                        held.set(snapshot);
                        captured.countDown();
                        assertTrue(proceed.await(8, TimeUnit.SECONDS));
                    }
                    return result;
                });
            var handle = runtime.modContexts().get(OWNER).context().tasks()
                .submit(new TaskRequest("source_preparation", "Source cancellation", List.of(), true), task -> {
                    try {
                        var selection = delayed.selectDirectory("Source", task);
                        var failure = assertThrows(ApiException.class,
                            () -> new DeploymentSourcePreparer(delayed).prepare(selection, 4096, 100, task));
                        assertEquals(ProblemCode.CANCELLED, failure.problem().code());
                        assertThrows(IllegalStateException.class, held.get()::directory);
                        noSnapshots(runtime);
                        return new OperationResult<Void>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                    } catch (Exception | AssertionError failure) {
                        error.set(failure);
                        throw failure;
                    }
                });
            try {
                assertTrue(captured.await(8, TimeUnit.SECONDS));
                handle.requestCancel();
            } finally {
                proceed.countDown();
            }
            var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            if (error.get() != null)
                throw new AssertionError("Cancellation callback failed", error.get());
            assertEquals(Outcome.CANCELLED, result.outcome());
            noSnapshots(runtime);
        }
    }

    @Test
    void aJunctionCannotBeHiddenBehindAnExcludedDirectoryName() throws Exception {
        file("file", "fixture");
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.writeString(outside.resolve("untouched"), "outside");
        Path link = root.resolve("input/.git");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString())
            .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
            enable();
            try (var runtime = open()) {
                start(runtime, true);
                var source = file(runtime);
                run(runtime, task -> {
                    var selected = source.selectDirectory("Source", task);
                    assertThrows(ApiException.class,
                        () -> new DeploymentSourcePreparer(source).prepare(selected, 4096, 100, task));
                    noSnapshots(runtime);
                    return success();
                });
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(3, TimeUnit.SECONDS));
            }
            Files.deleteIfExists(link);
        }
        assertEquals("outside", Files.readString(outside.resolve("untouched")));
    }

    @Test
    void emptySourceIsAnExplicitEmptySnapshotAndInvalidBudgetsDoNotAccessTheCapability() throws Exception {
        Files.createDirectories(root.resolve("input"));
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var source = file(runtime);
            run(runtime, task -> {
                var selected = source.selectDirectory("Source", task);
                var preparer = new DeploymentSourcePreparer(source);
                assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class, () -> preparer.prepare(selected, -1, 1, task)).problem()
                        .code());
                assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class,
                        () -> preparer.prepare(selected, 2L * 1024 * 1024 * 1024 + 1, 1, task)).problem()
                        .code());
                assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class, () -> preparer.prepare(selected, 0, 100001, task)).problem()
                        .code());
                try (var material = preparer.prepare(selected, 0, 1, task)) {
                    assertTrue(material.includedFiles().isEmpty());
                    assertTrue(material.excludedEntries().isEmpty());
                    try (var files = Files.list(material.snapshot().directory())) {
                        assertEquals(0, files.count());
                    }
                }
                noSnapshots(runtime);
                return success();
            });
        }
    }
}
