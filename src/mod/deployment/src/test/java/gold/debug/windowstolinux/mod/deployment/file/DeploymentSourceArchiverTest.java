package gold.debug.windowstolinux.mod.deployment.file;

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

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.source.FileSelection;
import gold.debug.windowstolinux.appapi.file.work.FileWorkspace;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.ProjectPartFinder;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSourceSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStandardRuntimeResolver;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

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

    FileService file(PlatformRuntime runtime) {
        return runtime.modContexts().get(OWNER).context().file();
    }

    DeploymentSourceMaterial material(FileService source, TaskContext task) throws Exception {
        return new DeploymentSourceSetup(source).prepare(source.sources().selectDirectory("Source", task), 2 * 1024 * 1024,
            100, task);
    }

    OperationResult<Void> success() {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
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
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
    }

    long files(PlatformRuntime runtime) throws Exception {
        return scalar(runtime,
            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL");
    }

    FileService intercept(FileService original, InvocationHandler handler) {
        return gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.create(FileService.class.getClassLoader(), (proxy, method, arguments) -> {
            try {
                return handler.invoke(DeploymentFileServiceFixture.view(method, original), method, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }

    @Test
    void realTaskBoardRuntimeInputsPreserveBothComponentArchivesAndReleaseFileResources() throws Exception {
        fixedFixtureContract("task-board");
    }

    @ParameterizedTest(name = "fixed local fixture: {0}")
    @ValueSource(strings = {"task-board", "file-transfer", "asset-lending", "csv-inspector", "survey-scoring",
        "log-analyzer", "directory-diff", "binary-inspector"})
    void fixedEightRuntimeInputsPreserveGraphHealthResourcesAndComponentArchives(String project) throws Exception {
        fixedFixtureContract(project);
    }

    private void fixedFixtureContract(String project) throws Exception {
        Path fixture = workspace().resolve("test/multi-language/success-" + project);
        var original = new TreeMap<Path, String>();
        Files.walkFileTree(fixture, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (Set.of("node_modules", "target", ".venv", "__pycache__", ".git", ".w2l", "dist", "build", "bin", "obj")
                    .contains(path.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(selectedSourceDirectory().resolve(fixture.relativize(path)));
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                assertFalse(Files.isSymbolicLink(path));
                try { original.put(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))); }
                catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                Files.copy(path, selectedSourceDirectory().resolve(fixture.relativize(path)));
                return FileVisitResult.CONTINUE;
            }
        });
        try {
            var liveType = Class.forName("gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticLiveAcceptanceTest");
            var constructor = liveType.getDeclaredConstructor();
            constructor.setAccessible(true);
            var runtimeInputs = liveType.getDeclaredMethod("runtimeInputs", String.class, Path.class, String.class, int.class);
            runtimeInputs.setAccessible(true);
            var live = constructor.newInstance();
            @SuppressWarnings("unchecked") var inputs = (Map<String, Map<String, String>>) runtimeInputs.invoke(live, project, selectedSourceDirectory(), "host.invalid", 46100);
            var reviewerMethod = liveType.getDeclaredMethod("reviewer", String.class, String.class, Map.class);
            reviewerMethod.setAccessible(true);
            var reviewer = (DeploymentStandardInputs.Reviewer) reviewerMethod.invoke(live, project, "local-contract", inputs);
            boolean cli = List.of("log-analyzer", "directory-diff", "binary-inspector").contains(project);
            Set<String> roots = cli ? Set.of("app") : project.equals("survey-scoring") ? Set.of("backend", "frontend", "scorer")
                : project.equals("csv-inspector") ? Set.of("analyzer", "web") : project.equals("task-board") ? Set.of("backend", "frontend") : Set.of("backend", "web");
            Map<String, String> expectedKinds = switch (project) {
                case "task-board" -> Map.of("backend", "SPRING_BOOT", "frontend", "STATIC_SITE");
                case "file-transfer" -> Map.of("backend", "GO_SERVICE", "web", "NODE_SERVICE");
                case "asset-lending" -> Map.of("backend", "DOTNET_SERVICE", "web", "NODE_SERVICE");
                case "csv-inspector" -> Map.of("analyzer", "PYTHON_SERVICE", "web", "PHP_SERVICE");
                case "survey-scoring" -> Map.of("backend", "KOTLIN_SERVICE", "frontend", "STATIC_SITE", "scorer", "RUBY_SERVICE");
                default -> Map.of("app", project.equals("directory-diff") ? "PYTHON_SERVICE" : "RUST_SERVICE");
            };
            assertEquals(roots, inputs.keySet());
            if (inputs.containsKey("frontend")) assertTrue(Files.readString(selectedSourceDirectory().resolve("frontend/public/runtime-config.json")).contains("http://host.invalid:46101"));
            enable();
            try (var runtime = open()) {
                start(runtime, true);
                run(runtime, task -> {
                    var source = file(runtime);
                    try (var material = new DeploymentSourceSetup(source).prepare(source.sources().selectDirectory("Source", task),
                        128L * 1024 * 1024, 10000, task)) {
                        var discovered = new ProjectPartFinder().discover(material.snapshot().directory());
                        assertEquals(roots, discovered.stream().map(part -> part.relativeRoot().toString().isEmpty() ? "app"
                            : part.relativeRoot().toString().replace('\\', '/')).collect(java.util.stream.Collectors.toSet()));
                        var selection = reviewer.select(new ServerId("local-fixture"), material.snapshot().sha256(), discovered, task).value().orElseThrow();
                        assertEquals(cli ? Optional.empty() : Optional.of(project.equals("task-board") || project.equals("survey-scoring") ? "frontend" : "web"), selection.healthComponent());
                        var resolver = new DeploymentStandardRuntimeResolver();
                        var prompts = new ArrayList<DeploymentStandardInputs.Prompt>();
                        for (var part : discovered) {
                            var choice = selection.choices().get(part.id());
                            assertEquals(expectedKinds.get(part.id()), choice.type().name());
                            Set<String> dependencies = part.id().equals("frontend") ? Set.of("backend") : part.id().equals("web")
                                ? Set.of(project.equals("csv-inspector") ? "analyzer" : "backend") : part.id().equals("backend") && project.equals("survey-scoring") ? Set.of("scorer") : Set.of();
                            assertEquals(dependencies, choice.dependencies());
                            var analyzed = new DeploymentStandardAnalyzer().analyzeComponent(material, part.relativeRoot(), part.id(), choice.type(), Optional.empty(), true, task);
                            assertEquals(TaskResult.SUCCEEDED, analyzed.outcome(), analyzed.toString());
                            var report = analyzed.value().orElseThrow().assessment();
                            var facts = report.facts().orElseThrow();
                            prompts.add(new DeploymentStandardInputs.Prompt(part.id(), "local-contract", choice.type(),
                                resolver.defaults(choice.type(), report.runtimeSuggestion().orElse(null), facts.sourceRoot()), List.of()));
                        }
                        var answers = reviewer.review(new ServerId("local-fixture"), material.snapshot().sha256(), prompts, task).value().orElseThrow();
                        for (var prompt : prompts) {
                            var answer = answers.get(prompt.componentId());
                            var runtimeContract = resolver.runtime(prompt.projectType(), answer.values());
                            var workload = runtimeContract.workload();
                            String role = prompt.componentId();
                            assertEquals(cli ? DeploymentWorkload.ExecutionMode.ON_DEMAND : DeploymentWorkload.ExecutionMode.DAEMON, workload.mode());
                            if (cli) {
                                assertInstanceOf(DeploymentHealthCheck.Command.class, runtimeContract.healthCheck());
                                String expected = project.equals("log-analyzer") ? "\"matched\": 6" : project.equals("directory-diff") ? "\"status\": \"complete\"" : "\"records\": 5";
                                assertEquals(expected, workload.expectedOutput());
                                assertEquals(project.equals("directory-diff") ? List.of("--format", "json", "snapshot", "--root", "samples/baseline", "--name", "live-baseline")
                                    : List.of("--input", project.equals("log-analyzer") ? "samples/events.log" : "samples/normal.bin", "--format", "json"), workload.verification().orElseThrow().arguments());
                                assertEquals(1, workload.companions().size());
                                assertEquals("native", workload.companions().getFirst().sourcePath());
                                assertEquals("NATIVE_HELPER", workload.companions().getFirst().environment());
                                assertEquals(".w2l/bin/" + (project.equals("log-analyzer") ? "log-worker" : project.equals("directory-diff") ? "scan-worker" : "binary-worker"), workload.companions().getFirst().artifactPath());
                            } else if (role.equals("scorer")) {
                                assertInstanceOf(DeploymentHealthCheck.Process.class, runtimeContract.healthCheck());
                                assertEquals(46102, workload.endpoints().getFirst().hostPort());
                            }
                            else {
                                var http = assertInstanceOf(DeploymentHealthCheck.Http.class, runtimeContract.healthCheck());
                                int expectedPort = role.equals("frontend") || role.equals("web") ? 46100 : 46101;
                                assertEquals(expectedPort, http.endpoint().getPort());
                            }
                            boolean resource = project.equals("file-transfer") && role.equals("backend") || project.equals("csv-inspector") && role.equals("web") || project.equals("directory-diff");
                            assertEquals(resource ? 1 : 0, answer.files().size());
                            if (resource) {
                                var binding = answer.files().getFirst();
                                assertEquals(project.equals("file-transfer") ? "uploads" : "content", binding.bindingId());
                                assertEquals(project.equals("csv-inspector") ? "seed" : "", binding.seedFile());
                                assertEquals("data", binding.dataPath().path());
                                assertEquals("READ_WRITE", binding.dataPath().access().name());
                            }
                            if (project.equals("csv-inspector") && role.equals("web")) {
                                assertEquals(1, workload.workers().size());
                                assertEquals("worker.php", workload.workers().getFirst().command().entrypoint());
                            }
                        }
                        String sourceSha = material.snapshot().sha256();
                        var owned = new ArrayList<DeploymentSourceArchive>();
                        try {
                            var frozen = DeploymentStandardSourceSetup.archiveParts(material, source, discovered,
                                64L * 1024 * 1024, owned, task);
                            assertEquals(roots.size(), frozen.archives().size());
                            for (var entry : frozen.archives().entrySet()) {
                                var archive = entry.getValue();
                                assertEquals(cli ? "" : entry.getKey(), archive.relativeRoot());
                                assertEquals(material.snapshot().id(), archive.originSnapshotId());
                                assertEquals(sourceSha, archive.sourceManifestSha256());
                                assertTrue(Files.isRegularFile(archive.path()));
                                assertEquals(archive.sha256(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(Files.readAllBytes(archive.path()))));
                                var names = new HashSet<String>();
                                try (var tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive.path())))) {
                                    for (var item = tar.getNextEntry(); item != null; item = tar.getNextEntry()) if (!item.isDirectory()) {
                                        String name = item.getName().replaceFirst("^\\./", ""); names.add(name);
                                        assertArrayEquals(Files.readAllBytes(material.snapshot().directory().resolve(archive.relativeRoot()).resolve(name)), tar.readAllBytes(), name);
                                    }
                                }
                                if (cli) {
                                    assertTrue(names.stream().anyMatch(name -> name.startsWith("native/")), names.toString());
                                    assertTrue(names.contains(project.equals("log-analyzer") ? "samples/events.log" : project.equals("binary-inspector") ? "samples/normal.bin" : "samples/baseline/README.md")
                                        || project.equals("directory-diff") && names.stream().anyMatch(name -> name.startsWith("samples/baseline/")), names.toString());
                                }
                                if (project.equals("csv-inspector") && entry.getKey().equals("web")) {
                                    assertTrue(names.contains("worker.php")); assertTrue(names.contains("seed/datasets/demo.csv"));
                                }
                            }
                            assertEquals(sourceSha, material.snapshot().sha256());
                            return success();
                        } finally {
                            for (var archive : owned) archive.close();
                        }
                    }
                });
                assertEquals(0, files(runtime));
            }
        } finally {
            for (var entry : original.entrySet())
                assertEquals(entry.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(entry.getKey()))), "Original fixed fixture must remain unchanged");
        }
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
                var source = file(runtime);
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
                var source = file(runtime);
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
                var source = file(runtime);
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
                var source = file(runtime);
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
                var source = file(runtime);
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
                var original = file(runtime);
                try (var material = material(original, task)) {
                    var staged = new AtomicReference<FileWorkspace>();
                    var source = intercept(original, (target, method, args) -> {
                        if (method.getName().equals("snapshot"))
                            Files.writeString(staged.get().directory().resolve("source.tar.gz"), "tampered");
                        Object result = method.invoke(target, args);
                        if (method.getName().equals("workspace"))
                            staged.set((FileWorkspace) result);
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
                var original = file(runtime);
                var held = new AtomicReference<FileWorkspace>();
                try (var material = material(original, task)) {
                    var source = intercept(original, (target, method, args) -> {
                        Object result = method.invoke(target, args);
                        if (!method.getName().equals("workspace"))
                            return result;
                        var work = (FileWorkspace) result;
                        held.set(work);
                        return new FileWorkspace() {
                            public UUID id() {
                                return work.id();
                            }

                            public Path directory() throws ApiException {
                                return work.directory();
                            }

                            public FileSelection selection() throws ApiException {
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
                        var original = file(runtime);
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
                        return new OperationResult<Void>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
                    } catch (Exception | AssertionError error) {
                        failure.set(error);
                        throw error;
                    }
                }));
            assigned.countDown();
            var result = handle.get().completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            if (failure.get() != null)
                throw new AssertionError(failure.get());
            assertEquals(TaskResult.CANCELLED, result.outcome());
        }
    }
}
