package gold.debug.windowstolinux.mod.deployment.source;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPInputStream;

import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.appapi.source.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

class DeploymentSourceArchiverTest extends DeploymentRuntimeFixture {
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    Path file(String name, byte[] bytes) throws Exception {
        var path = root.resolve("input").resolve(name);
        Files.createDirectories(path.getParent());
        return Files.write(path, bytes);
    }

    SourceService source(PlatformRuntime runtime) {
        return runtime.modContexts().get(OWNER).context().source();
    }

    DeploymentSourceMaterial material(SourceService source, TaskContext task) throws Exception {
        return new DeploymentSourcePreparer(source).prepare(source.selectDirectory("Source", task), 2 * 1024 * 1024,
                100, task);
    }

    OperationResult<Void> success() {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    void run(PlatformRuntime runtime, TaskWork<Void> work) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var handle = runtime.modContexts().get(OWNER).context().tasks()
                .submit(new TaskRequest("archive", "Archive", List.of(), true), task -> {
                    try {
                        return work.run(task);
                    } catch (Exception | AssertionError error) {
                        failure.set(error);
                        throw error;
                    }
                });
        OperationResult<Void> result;
        try {
            result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            if (failure.get() != null)
                throw new AssertionError("Callback failed before task completion", failure.get());
            throw timeout;
        }
        if (failure.get() != null)
            throw new AssertionError(failure.get());
        assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
    }

    long files(PlatformRuntime runtime) throws Exception {
        return scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL");
    }

    SourceService intercept(SourceService original, InvocationHandler handler) {
        return (SourceService) Proxy.newProxyInstance(SourceService.class.getClassLoader(),
                new Class<?>[]{SourceService.class}, (proxy, method, arguments) -> {
                    try {
                        return handler.invoke(original, method, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @Test
    void componentArchiveUsesOriginalSnapshotButBuildsFromItsOwnTarRoot() throws Exception {
        file("api/go.mod", "module example/api\n".getBytes());
        file("web/package.json", "{\"name\":\"web\"}".getBytes());
        file("中文 服务..v2/main.txt", "nested content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var source = source(runtime);
                try (var material = material(source, task);
                        var api = new DeploymentSourceArchiver(source).prepare(material, "api", 100_000, task);
                        var web = new DeploymentSourceArchiver(source).prepare(material, "web", 100_000, task);
                        var named = new DeploymentSourceArchiver(source).prepare(material, "中文 服务..v2", 100_000,
                                task)) {
                    assertEquals(material.snapshot().id(), api.originSnapshotId());
                    assertEquals(material.snapshot().sha256(), web.sourceManifestSha256());
                    assertEquals("api", api.relativeRoot());
                    assertEquals("web", web.relativeRoot());
                    assertEquals("中文 服务..v2", named.relativeRoot());
                    assertTrue(DeploymentSourceArchive.validRelativeRoot("中文 服务..v2"));
                    assertFalse(DeploymentSourceArchive.validRelativeRoot("../api"));
                    assertFalse(DeploymentSourceArchive.validRelativeRoot("api/../web"));
                    assertFalse(DeploymentSourceArchive.validRelativeRoot("/api"));
                    try (var gzip = new GZIPInputStream(Files.newInputStream(api.path()))) {
                        byte[] tar = gzip.readAllBytes();
                        assertEquals("go.mod", new String(tar, 0, 6, java.nio.charset.StandardCharsets.US_ASCII));
                        assertFalse(
                                new String(tar, java.nio.charset.StandardCharsets.UTF_8).contains("web/package.json"));
                    }
                    var buildSource = root.resolve("expanded-api");
                    Files.createDirectories(buildSource);
                    var extraction = new ProcessBuilder("tar", "-xzf", api.path().toString(), "-C",
                            buildSource.toString()).redirectErrorStream(true).start();
                    try {
                        assertTrue(extraction.waitFor(10, TimeUnit.SECONDS), "Original tar extraction timed out");
                        assertEquals(0, extraction.exitValue(), new String(extraction.getInputStream().readAllBytes()));
                    } finally {
                        if (extraction.isAlive()) {
                            extraction.destroyForcibly();
                            assertTrue(extraction.waitFor(5, TimeUnit.SECONDS));
                        }
                    }
                    assertTrue(Files.isRegularFile(buildSource.resolve("go.mod")));
                    assertFalse(Files.exists(buildSource.resolve("api")));
                    try (var gzip = new GZIPInputStream(Files.newInputStream(web.path()))) {
                        byte[] tar = gzip.readAllBytes();
                        assertEquals("package.json",
                                new String(tar, 0, 12, java.nio.charset.StandardCharsets.US_ASCII));
                    }
                    try (var gzip = new GZIPInputStream(Files.newInputStream(named.path()))) {
                        assertEquals("main.txt",
                                new String(gzip.readAllBytes(), 0, 8, java.nio.charset.StandardCharsets.US_ASCII));
                    }
                }
                return success();
            });
        }
    }

    @Test
    void compressedBytesMatchTheOriginalFormatAndOwnAnIndependentFrozenLifetime() throws Exception {
        file("pom.xml", "project".getBytes());
        file("asset+@().txt", "ordinary name".getBytes());
        file("src/中文.java", "source".getBytes());
        file("empty", new byte[0]);
        file("fixtures/sample.log", "sample".getBytes());
        file(".env", "secret".getBytes());
        file("target/output", "excluded".getBytes());
        file("windowstolinux-application.properties", "source.include=fixtures/sample.log\n".getBytes());
        String longName = "d".repeat(80) + "/" + "n".repeat(40);
        file(longName, new byte[]{0, 1, 2, 3});
        byte[] expected;
        try (var fixture = getClass().getResourceAsStream("/original-source-original.tar.gz")) {
            assertNotNull(fixture, "The independently frozen original archive must be packaged");
            expected = fixture.readAllBytes();
        }
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var source = source(runtime);
                try (var material = material(source, task)) {
                    var archiver = new DeploymentSourceArchiver(source);
                    byte[] first;
                    try (var archive = archiver.prepare(material, 100000, task)) {
                        first = Files.readAllBytes(archive.path());
                        assertArrayEquals(expected, first);
                        assertEquals("c4506d54e1c4832d72d761ca8723e2bff0572715b6396698fcf9d3473874e50d",
                                archive.sha256());
                        assertEquals(333, archive.byteCount());
                        assertEquals(71, archive.uncompressedByteCount());
                        assertEquals(material.snapshot().id(), archive.originSnapshotId());
                        assertEquals(material.snapshot().sha256(), archive.sourceManifestSha256());
                        assertNotEquals(archive.snapshot().sha256(), archive.sha256());
                        assertTrue(Files.readAttributes(archive.path(), DosFileAttributes.class).isReadOnly());
                        assertEquals(2, files(runtime));
                        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(first))) {
                            byte[] tar = gzip.readAllBytes();
                            assertEquals(0, tar.length % 512);
                            assertArrayEquals(new byte[1024], Arrays.copyOfRange(tar, tar.length - 1024, tar.length));
                        }
                    }
                    assertEquals(1, files(runtime));
                    try (var second = archiver.prepare(material, first.length, task)) {
                        assertArrayEquals(first, Files.readAllBytes(second.path()));
                    }
                    assertEquals("project", Files.readString(material.snapshot().directory().resolve("pom.xml")));
                }
                assertEquals(0, files(runtime));
                return success();
            });
        }
    }

    @Test
    void emptySourcesAreValidAndBudgetsCountActualCompressedHeadersAndTrailers() throws Exception {
        Files.createDirectory(root.resolve("input"));
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var source = source(runtime);
                try (var material = material(source, task)) {
                    var archiver = new DeploymentSourceArchiver(source);
                    long size;
                    try (var archive = archiver.prepare(material, 1024, task)) {
                        assertEquals(0, archive.uncompressedByteCount());
                        size = archive.byteCount();
                        try (var gzip = new GZIPInputStream(Files.newInputStream(archive.path()))) {
                            assertArrayEquals(new byte[1024], gzip.readAllBytes());
                        }
                    }
                    assertEquals(ProblemCode.IO_FAILURE,
                            assertThrows(ApiException.class, () -> archiver.prepare(material, size - 1, task)).problem()
                                    .code());
                    for (long limit : new long[]{0, -1, 2L * 1024 * 1024 * 1024 + 1})
                        assertEquals(ProblemCode.INVALID_INPUT,
                                assertThrows(ApiException.class, () -> archiver.prepare(material, limit, task))
                                        .problem().code());
                    assertEquals(1, files(runtime));
                }
                return success();
            });
        }
    }

    @Test
    void unrepresentableUstarNamesFailAndLeaveOnlyTheOriginalMaterial() throws Exception {
        file("中".repeat(34), "value".getBytes());
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var source = source(runtime);
                try (var material = material(source, task)) {
                    assertEquals(ProblemCode.IO_FAILURE,
                            assertThrows(ApiException.class,
                                    () -> new DeploymentSourceArchiver(source).prepare(material, 10000, task)).problem()
                                    .code());
                    assertEquals(1, files(runtime));
                    assertEquals(1, material.includedFiles().size());
                }
                return success();
            });
        }
    }

    @Test
    void changedFrozenSourceIsRejectedWithoutPublishingAnArchive() throws Exception {
        file("source", "original".getBytes());
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var source = source(runtime);
                try (var material = material(source, task)) {
                    Path changed = material.snapshot().directory().resolve("source");
                    Files.setAttribute(changed, "dos:readonly", false);
                    Files.writeString(changed, "modified");
                    assertEquals(ProblemCode.IO_FAILURE,
                            assertThrows(ApiException.class,
                                    () -> new DeploymentSourceArchiver(source).prepare(material, 10000, task)).problem()
                                    .code());
                    assertEquals(1, files(runtime));
                }
                return success();
            });
        }
    }

    @Test
    void compressedFileChangedBeforeFreezeCannotBePublishedWithTheOldDigest() throws Exception {
        file("source", "original".getBytes());
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var original = source(runtime);
                try (var material = material(original, task)) {
                    var staged = new AtomicReference<SourceWorkspace>();
                    var source = intercept(original, (target, method, args) -> {
                        if (method.getName().equals("snapshot"))
                            Files.writeString(staged.get().directory().resolve("source.tar.gz"), "tampered");
                        Object result = method.invoke(target, args);
                        if (method.getName().equals("workspace"))
                            staged.set((SourceWorkspace) result);
                        return result;
                    });
                    assertEquals(ProblemCode.IO_FAILURE,
                            assertThrows(ApiException.class,
                                    () -> new DeploymentSourceArchiver(source).prepare(material, 10000, task)).problem()
                                    .code());
                    assertEquals(1, files(runtime));
                    assertThrows(ApiException.class, staged.get()::directory);
                }
                return success();
            });
        }
    }

    @Test
    void uncertainWorkspaceCleanupIsReportedAndKeepsItsOriginalHandleForRetry() throws Exception {
        file("source", "original".getBytes());
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var original = source(runtime);
                var held = new AtomicReference<SourceWorkspace>();
                try (var material = material(original, task)) {
                    var source = intercept(original, (target, method, args) -> {
                        Object result = method.invoke(target, args);
                        if (!method.getName().equals("workspace"))
                            return result;
                        var work = (SourceWorkspace) result;
                        held.set(work);
                        return new SourceWorkspace() {
                            public UUID id() {
                                return work.id();
                            }

                            public Path directory() throws ApiException {
                                return work.directory();
                            }

                            public SourceSelection selection() throws ApiException {
                                return work.selection();
                            }

                            public void close() throws ApiException {
                                throw new ApiException(new Problem(ProblemCode.UNKNOWN, "FIXTURE_CLEANUP",
                                        "Cleanup unconfirmed", Map.of(), Optional.empty()));
                            }
                        };
                    });
                    assertEquals(ProblemCode.UNKNOWN,
                            assertThrows(ApiException.class,
                                    () -> new DeploymentSourceArchiver(source).prepare(material, 10000, task)).problem()
                                    .code());
                    assertEquals(2, files(runtime));
                    held.get().close();
                    assertEquals(1, files(runtime));
                } finally {
                    if (held.get() != null)
                        held.get().close();
                }
                return success();
            });
        }
    }

    @Test
    void cancellationAfterArchiveFreezeReleasesBothNewResourcesBeforeReturningCancelled() throws Exception {
        file("source", "original".getBytes());
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var assigned = new CountDownLatch(1);
            var handle = new AtomicReference<TaskHandle<Void>>();
            var failure = new AtomicReference<Throwable>();
            handle.set(runtime.modContexts().get(OWNER).context().tasks()
                    .submit(new TaskRequest("archive", "Archive", List.of(), true), task -> {
                        try {
                            assertTrue(assigned.await(8, TimeUnit.SECONDS));
                            var original = source(runtime);
                            try (var material = material(original, task)) {
                                var source = intercept(original, (target, method, args) -> {
                                    Object result = method.invoke(target, args);
                                    if (method.getName().equals("snapshot"))
                                        handle.get().requestCancel();
                                    return result;
                                });
                                assertEquals(ProblemCode.CANCELLED, assertThrows(ApiException.class,
                                        () -> new DeploymentSourceArchiver(source).prepare(material, 10000, task))
                                        .problem().code());
                                assertEquals(1, files(runtime));
                            }
                            assertEquals(0, files(runtime));
                            return new OperationResult<Void>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                        } catch (Exception | AssertionError error) {
                            failure.set(error);
                            throw error;
                        }
                    }));
            assigned.countDown();
            var result = handle.get().completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            if (failure.get() != null)
                throw new AssertionError(failure.get());
            assertEquals(Outcome.CANCELLED, result.outcome());
        }
    }
}
