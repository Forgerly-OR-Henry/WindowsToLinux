package gold.debug.windowstolinux.mod.deployment.source.git;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRepository;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentProjectSourceRecorder;
import org.junit.jupiter.api.Test;

class DeploymentGitPreparerTest extends DeploymentRuntimeFixture {
    private static final Path GIT = locateGit();

    private static Path locateGit() {
        for (String directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(directory).resolve("git.exe");
            if (Files.isRegularFile(candidate))
                return candidate.toAbsolutePath().normalize();
        }
        throw new AssertionError("A real Git executable must be available on PATH");
    }

    private String git(Path directory, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(GIT.toString());
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private Path repository() throws Exception {
        Path repo = Files.createDirectory(root.resolve("repo"));
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "test@example.invalid");
        git(repo, "config", "user.name", "Test");
        git(repo, "config", "core.longpaths", "true");
        Files.writeString(repo.resolve("pom.xml"), "first");
        Files.writeString(repo.resolve("中文+@().txt"), "unicode");
        Files.writeString(repo.resolve(".env"), "excluded");
        Path longFile = repo.resolve("a".repeat(70)).resolve("b".repeat(70)).resolve("c".repeat(70))
                .resolve("source.txt");
        Files.createDirectories(longFile.getParent());
        Files.writeString(longFile, "long path");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "first");
        git(repo, "tag", "v1");
        return repo;
    }

    private void prepare(DeploymentGitPreparer preparer, DeploymentGitRequest request, String expected)
            throws Exception {
        var failure = new AtomicReference<Throwable>();
        var context = activeContext();
        var handle = context.tasks().submit(new TaskRequest("git", "Git", List.of(), true), task -> {
            try {
                try (var material = preparer.prepare(request, 2 * 1024 * 1024, 100, task)) {
                    assertEquals(expected, material.commit());
                    assertTrue(material.source().includedFiles().contains("pom.xml"));
                    assertTrue(
                            material.source().includedFiles().stream().anyMatch(name -> name.endsWith("/source.txt")));
                    assertTrue(material.source().snapshot().directory()
                            .resolve(material.source().includedFiles().stream()
                                    .filter(name -> name.endsWith("/source.txt")).findFirst().orElseThrow())
                            .toString().length() > 260);
                    assertFalse(material.source().includedFiles().contains(".env"));
                    assertFalse(material.source().includedFiles().stream().anyMatch(name -> name.startsWith(".git/")));
                    byte[] archive = Files.readAllBytes(material.archive().path());
                    assertEquals(archive.length, material.archive().byteCount());
                    assertEquals(material.archive().sha256(),
                            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)));
                    try (var compressed = new GZIPInputStream(new java.io.ByteArrayInputStream(archive))) {
                        String tar = new String(compressed.readAllBytes(), StandardCharsets.ISO_8859_1);
                        assertTrue(tar.contains("pom.xml"));
                        assertFalse(tar.contains(".env"));
                        assertFalse(tar.contains(".git/"));
                    }
                }
                return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
            } catch (Throwable problem) {
                failure.set(problem);
                return new OperationResult<Void>(Outcome.FAILED, Optional.empty(), Optional.empty());
            }
        });
        handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
        if (failure.get() != null)
            throw new AssertionError(failure.get());
    }

    private void rejected(DeploymentGitPreparer preparer, DeploymentGitRequest request) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var handle = activeContext().tasks().submit(new TaskRequest("git-rejected", "Git rejected", List.of(), true),
                task -> {
                    try {
                        assertThrows(ApiException.class, () -> preparer.prepare(request, 2 * 1024 * 1024, 100, task));
                        return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                    } catch (Throwable problem) {
                        failure.set(problem);
                        return new OperationResult<Void>(Outcome.FAILED, Optional.empty(), Optional.empty());
                    }
                });
        handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
        if (failure.get() != null)
            throw new AssertionError(failure.get());
    }

    private gold.debug.windowstolinux.appapi.mod.ModContext context;

    private gold.debug.windowstolinux.appapi.mod.ModContext activeContext() {
        return context;
    }

    @Test
    void realPinnedGitMaterialPersistsItsSelectedRefCommitAndSourceDigest() throws Exception {
        Path repo = repository();
        String commit = git(repo, "rev-parse", "HEAD");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            context = runtime.modContexts().get(OWNER).context();
            var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
                    new DeploymentGitReference.Tag("v1"), Set.of(), 2 * 1024 * 1024, true);
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("git-project", "Git project", List.of(), true),
                    task -> {
                        try {
                            UUID id = UUID.randomUUID();
                            var recorder = new DeploymentProjectSourceRecorder(context, GIT);
                            try (var prepared = recorder.prepareGit(task, request, id, "Git 项目", Optional.empty(),
                                    java.time.Instant.parse("2026-09-28T00:00:00Z"), 2 * 1024 * 1024, 100)) {
                                var saved = new DeploymentProjectRepository(context.db()).find(task, id).orElseThrow();
                                assertEquals(prepared.project(), saved);
                                assertEquals("TAG:v1", saved.source().gitRef().orElseThrow());
                                assertEquals(commit, saved.source().gitCommit().orElseThrow());
                                assertEquals(prepared.material().source().snapshot().sha256(), saved.source().sha256());
                                assertNotEquals(prepared.material().archive().sha256(), saved.source().sha256());
                            }
                            return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                        } catch (Throwable problem) {
                            failure.set(problem);
                            return new OperationResult<Void>(Outcome.FAILED, Optional.empty(), Optional.empty());
                        }
                    });
            handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
            if (failure.get() != null)
                throw new AssertionError(failure.get());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    @Test
    void realWorkerPinsDefaultBranchTagAndCommitAndReleasesResources() throws Exception {
        assertTrue(Files.isRegularFile(GIT));
        Path repo = repository();
        String initial = git(repo, "rev-parse", "HEAD");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            context = runtime.modContexts().get(OWNER).context();
            var preparer = new DeploymentGitPreparer(context, GIT);
            var remote = DeploymentGitRemote.parse(repo.toUri().toString());
            for (var ref : List.<DeploymentGitReference>of(new DeploymentGitReference.DefaultBranch(),
                    new DeploymentGitReference.Branch("main"), new DeploymentGitReference.Tag("v1"),
                    new DeploymentGitReference.Commit(initial))) {
                prepare(preparer, new DeploymentGitRequest(remote, ref, Set.of(), 2 * 1024 * 1024, true), initial);
            }
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    @Test
    void movedBranchDoesNotChangeThePreviouslyFrozenBytes() throws Exception {
        Path repo = repository();
        String initial = git(repo, "rev-parse", "HEAD");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            context = runtime.modContexts().get(OWNER).context();
            var preparer = new DeploymentGitPreparer(context, GIT);
            var remote = DeploymentGitRemote.parse(repo.toUri().toString());
            var request = new DeploymentGitRequest(remote, new DeploymentGitReference.Branch("main"), Set.of(),
                    2 * 1024 * 1024, true);
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("git-move", "Git move", List.of(), true), task -> {
                try (var material = preparer.prepare(request, 2 * 1024 * 1024, 100, task)) {
                    assertEquals(initial, material.commit());
                    Files.writeString(repo.resolve("pom.xml"), "second");
                    git(repo, "add", ".");
                    git(repo, "commit", "-m", "second");
                    assertNotEquals(initial, git(repo, "rev-parse", "HEAD"));
                    assertEquals("first",
                            Files.readString(material.source().snapshot().directory().resolve("pom.xml")));
                    return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Throwable problem) {
                    failure.set(problem);
                    return new OperationResult<Void>(Outcome.FAILED, Optional.empty(), Optional.empty());
                }
            });
            handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
            if (failure.get() != null)
                throw new AssertionError(failure.get());
            prepare(preparer, request, git(repo, "rev-parse", "HEAD"));
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    @Test
    void invalidReferencesAndUnsupportedGitTreeFeaturesReleaseTheirOriginalResources() throws Exception {
        Path repo = repository();
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentGitRemote.parse("https://user:secret@example.invalid/repo"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentGitRemote.parse("file://elsewhere/repo"));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentGitReference.Branch("../main"));
        var remote = DeploymentGitRemote.parse(repo.toUri().toString());
        assertThrows(IllegalArgumentException.class, () -> new DeploymentGitRequest(remote,
                new DeploymentGitReference.DefaultBranch(), Set.of(), 1000, false));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentGitRequest(DeploymentGitRemote.parse("https://example.invalid/repo"),
                        new DeploymentGitReference.DefaultBranch(), Set.of(), 1000, false));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentGitRequest(DeploymentGitRemote.parse("ssh://example.invalid/repo"),
                        new DeploymentGitReference.DefaultBranch(), Set.of("example.invalid"), 1000, false));
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            context = runtime.modContexts().get(OWNER).context();
            var preparer = new DeploymentGitPreparer(context, GIT);
            var absentGit = new DeploymentGitPreparer(context, root.resolve("missing-git.exe"));
            assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class,
                            () -> absentGit.prepare(new DeploymentGitRequest(remote,
                                    new DeploymentGitReference.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true),
                                    2 * 1024 * 1024, 100, null))
                            .problem().code());
            rejected(preparer, new DeploymentGitRequest(remote, new DeploymentGitReference.Branch("missing"), Set.of(),
                    2 * 1024 * 1024, true));
            String blob = git(repo, "hash-object", "-w", "pom.xml");
            git(repo, "update-index", "--add", "--cacheinfo", "120000," + blob + ",link");
            git(repo, "commit", "-m", "link");
            rejected(preparer, new DeploymentGitRequest(remote, new DeploymentGitReference.DefaultBranch(), Set.of(),
                    2 * 1024 * 1024, true));
            git(repo, "rm", "--cached", "link");
            git(repo, "update-index", "--add", "--cacheinfo", "160000," + git(repo, "rev-parse", "HEAD") + ",nested");
            git(repo, "commit", "-m", "gitlink");
            rejected(preparer, new DeploymentGitRequest(remote, new DeploymentGitReference.DefaultBranch(), Set.of(),
                    2 * 1024 * 1024, true));
            git(repo, "rm", "--cached", "nested");
            Files.createDirectories(repo.resolve("sub"));
            Files.writeString(repo.resolve("sub/.gitattributes"), "*.bin filter=lfs\n");
            Files.writeString(repo.resolve("sub/blob.bin"), "version https://git-lfs.github.com/spec/v1\n");
            git(repo, "add", ".");
            git(repo, "commit", "-m", "lfs");
            rejected(preparer, new DeploymentGitRequest(remote, new DeploymentGitReference.DefaultBranch(), Set.of(),
                    2 * 1024 * 1024, true));
            git(repo, "mv", "-f", "sub/.gitattributes", "sub/.GitAttributes");
            git(repo, "commit", "-m", "mixed-case lfs attributes");
            rejected(preparer, new DeploymentGitRequest(remote, new DeploymentGitReference.DefaultBranch(), Set.of(),
                    2 * 1024 * 1024, true));
            git(repo, "rm", "sub/.GitAttributes");
            Files.writeString(repo.resolve("sub/.gitmodules"),
                    "[submodule \"fake\"]\n path = fake\n url = https://example.invalid/fake\n");
            git(repo, "add", ".");
            git(repo, "commit", "-m", "nested submodule metadata");
            rejected(preparer, new DeploymentGitRequest(remote, new DeploymentGitReference.DefaultBranch(), Set.of(),
                    2 * 1024 * 1024, true));
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    @Test
    void conflictingOriginalReceiptKeepsOccupancyUntilSameTaskRetriesOriginalCleanup() throws Exception {
        Path repo = repository();
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            context = runtime.modContexts().get(OWNER).context();
            var original = context;
            var receiptPath = new AtomicReference<Path>();
            var receiptBytes = new AtomicReference<byte[]>();
            var requests = new AtomicInteger();
            ProcessService processes = (spec, task) -> {
                Path receipt = Path.of(spec.arguments().get(8)).resolve("git-result.json");
                receiptPath.set(receipt);
                ManagedProcess actual = original.processes().start(spec, task);
                return new ManagedProcess() {
                    private boolean changed;
                    public UUID id() {
                        return actual.id();
                    }

                    public CompletionStage<OperationResult<ProcessMessage>> request(ProcessMessage message,
                            Duration timeout) throws ApiException {
                        requests.incrementAndGet();
                        return actual.request(message, timeout);
                    }

                    public void requestCancel() throws ApiException {
                        actual.requestCancel();
                    }

                    public CompletionStage<Integer> exitCode() {
                        return actual.exitCode();
                    }

                    public void close() throws ApiException {
                        actual.close();
                        if (!changed && Files.isRegularFile(receipt)) {
                            try {
                                receiptBytes.set(Files.readAllBytes(receipt));
                                Files.writeString(receipt, "{}\n");
                                changed = true;
                            } catch (java.io.IOException failure) {
                                throw new AssertionError(failure);
                            }
                        }
                    }
                };
            };
            ModContext intercepted = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                    new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("processes"))
                            return processes;
                        try {
                            return method.invoke(original, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            var preparer = new DeploymentGitPreparer(intercepted, GIT);
            var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
                    new DeploymentGitReference.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
            var failure = new AtomicReference<Throwable>();
            var callbackFinished = new CountDownLatch(1);
            var registration = context.tasks().registerRecovery("git-receipt",
                    (previous, originalTask) -> new OperationResult<Void>(Outcome.FAILED, Optional.empty(),
                            Optional.of(new Problem(ProblemCode.IO_FAILURE, "TEST_RECEIPT_TAMPER",
                                    "Original delivery was withheld", Map.of(), Optional.empty()))));
            var handle = context.tasks().submit(new TaskRequest("git-receipt", "Git receipt", List.of(), true),
                    task -> {
                        try {
                            var unknown = assertThrows(ApiException.class,
                                    () -> preparer.prepare(request, 2 * 1024 * 1024, 100, task));
                            assertEquals(ProblemCode.UNKNOWN, unknown.problem().code());
                            assertNotNull(receiptBytes.get());
                            assertTrue(scalar(runtime,
                                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'") > 0);
                            assertEquals(2, scalar(runtime,
                                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='FILE'"));
                            Files.write(receiptPath.get(), receiptBytes.get());
                            preparer.closeTaskResources(task);
                            return new OperationResult<Void>(Outcome.UNKNOWN, Optional.empty(),
                                    Optional.of(unknown.problem()));
                        } catch (Throwable problem) {
                            failure.set(problem);
                            return new OperationResult<Void>(Outcome.FAILED, Optional.empty(), Optional.empty());
                        } finally {
                            callbackFinished.countDown();
                        }
                    });
            assertTrue(callbackFinished.await(120, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError(failure.get());
            for (int i = 0; i < 100 && handle.snapshot().state() != TaskState.UNKNOWN; i++)
                Thread.sleep(20);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertFalse(handle.completion().toCompletableFuture().isDone());
            assertEquals(1, requests.get());
            runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(Outcome.FAILED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, requests.get());
            registration.close();
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }
}
