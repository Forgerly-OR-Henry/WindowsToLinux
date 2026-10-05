package gold.debug.windowstolinux.mod.deployment.file.git;

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

import gold.debug.windowstolinux.app.db.task.DependencyRecovery;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.process.ManagedProcess;
import gold.debug.windowstolinux.appapi.task.process.ProcessMessage;
import gold.debug.windowstolinux.appapi.task.process.ProcessService;
import gold.debug.windowstolinux.appapi.task.process.ProcessSpec;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectStore;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentProjectSourceRecorder;
import org.junit.jupiter.api.Test;

class DeploymentGitSetupTest extends DeploymentRuntimeFixture {
    @Override
    protected boolean gitEnabled() {
        return true;
    }

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

    private void prepare(GitRuntimeTestBridge.Preparation preparer, DeploymentGitRequest request, String expected)
        throws Exception {
        var failure = new AtomicReference<Throwable>();
        var context = activeContext();
        var handle = context.tasks().submit(new TaskRequest("git", "Git", List.of(), true), task -> {
            try {
                try (var material = preparer.prepare(request, 2 * 1024 * 1024, 100, task, gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitJournalFixture.begin(context, task, request))) {
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
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            } catch (Throwable problem) {
                failure.set(problem);
                return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.empty());
            }
        });
        handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
        if (failure.get() != null)
            throw new AssertionError(failure.get());
    }

    private void rejected(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime,
                          GitRuntimeTestBridge.Preparation preparer, DeploymentGitRequest request) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var observer = new AtomicReference<DeploymentGitSetup.Observer>();
        var originalCalls = new AtomicReference<Map<DependencyRecovery, List<UUID>>>();
        var finished = new java.util.concurrent.CountDownLatch(1);
        var registration = activeContext().tasks().registerRecovery("git-rejected", (previous, task) -> {
            try {
                var source = GitRuntimeTestBridge.reconcileOriginal(runtime, context, observer.get().planned(), task);
                assertEquals(TaskResult.FAILED, source.outcome());
                var reason = source.problem().orElseThrow();
                observer.get().receipt(new DeploymentGitSetup.Receipt(source.outcome(), Optional.empty(),
                    Optional.of(reason.code()), Optional.of(reason.eventCode())));
                preparer.closeTaskResources(task);
                GitRuntimeTestBridge.onlyOriginalCallsRemain(runtime, task.id(), originalCalls.get());
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            } catch (Throwable problem) {
                failure.set(problem);
                throw new AssertionError(problem);
            }
        });
        var handle = activeContext().tasks().submit(new TaskRequest("git-rejected", "Git rejected", List.of(), true),
            task -> {
                try {
                    observer.set(gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitJournalFixture.begin(context, task, request));
                    var rejected = assertThrows(ApiException.class,
                        () -> preparer.prepare(request, 2 * 1024 * 1024, 100, task, observer.get()));
                    if (rejected.problem().code() == ProblemCode.UNKNOWN)
                        return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(rejected.problem()));
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Throwable problem) {
                    failure.set(problem);
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.empty());
                } finally {
                    finished.countDown();
                }
            });
        try {
            assertTrue(finished.await(120, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
            for (int i = 0; i < 100 && handle.snapshot().state() != TaskState.UNKNOWN
                && handle.snapshot().state() != TaskState.ENDED; i++)
                Thread.sleep(20);
            if (handle.snapshot().state() == TaskState.UNKNOWN) {
                originalCalls.set(GitRuntimeTestBridge.retainedCalls(runtime, handle.snapshot().id()));
                reconcileReady(runtime, handle);
                if (failure.get() != null) throw new AssertionError(failure.get());
            }
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            registration.close();
        }
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
                new DeploymentGitRef.Tag("v1"), Set.of(), 2 * 1024 * 1024, true);
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("git-project", "Git project", List.of(), true),
                task -> {
                    try {
                        var flow = GitRuntimeTestBridge.flow(runtime, context);
                        try (var prepared = flow.prepareForPublication(request, GIT, "Git 项目", 2 * 1024 * 1024, 100, task);
                             var material = prepared.material()) {
                            var project = prepared.save();
                            var saved = new DeploymentProjectStore(context.db()).find(task, project.id()).orElseThrow();
                            assertEquals(project, saved);
                            assertEquals("TAG:v1", saved.source().gitRef().orElseThrow());
                            assertEquals(commit, saved.source().gitCommit().orElseThrow());
                            assertEquals(prepared.material().source().snapshot().sha256(), saved.source().sha256());
                            assertNotEquals(prepared.material().archive().sha256(), saved.source().sha256());
                        }
                        return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    } catch (Throwable problem) {
                        failure.set(problem);
                        return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.empty());
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
            var preparer = GitRuntimeTestBridge.preparation(runtime, context, GIT);
            var remote = DeploymentGitRemote.parse(repo.toUri().toString());
            for (var ref : List.<DeploymentGitRef>of(new DeploymentGitRef.DefaultBranch(),
                new DeploymentGitRef.Branch("main"), new DeploymentGitRef.Tag("v1"),
                new DeploymentGitRef.Commit(initial))) {
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
            var preparer = GitRuntimeTestBridge.preparation(runtime, context, GIT);
            var remote = DeploymentGitRemote.parse(repo.toUri().toString());
            var request = new DeploymentGitRequest(remote, new DeploymentGitRef.Branch("main"), Set.of(),
                2 * 1024 * 1024, true);
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("git-move", "Git move", List.of(), true), task -> {
                try (var material = preparer.prepare(request, 2 * 1024 * 1024, 100, task, gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitJournalFixture.begin(context, task, request))) {
                    assertEquals(initial, material.commit());
                    Files.writeString(repo.resolve("pom.xml"), "second");
                    git(repo, "add", ".");
                    git(repo, "commit", "-m", "second");
                    assertNotEquals(initial, git(repo, "rev-parse", "HEAD"));
                    assertEquals("first",
                        Files.readString(material.source().snapshot().directory().resolve("pom.xml")));
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Throwable problem) {
                    failure.set(problem);
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.empty());
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
    void actualIsolatedProviderTransfersLargeTreeWithoutRaisingServiceValueBudget() throws Exception {
        Path repo = repository();
        Path large = repo.resolve("large-source.dat");
        var random = new Random(7319);
        var chunk = new byte[1024 * 1024];
        try (var output = Files.newOutputStream(large)) {
            for (int i = 0; i < 24; i++) {
                random.nextBytes(chunk);
                output.write(chunk);
            }
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(large)));
        git(repo, "add", ".");
        git(repo, "commit", "-m", "large source");
        String commit = git(repo, "rev-parse", "HEAD");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            context = runtime.modContexts().get(OWNER).context();
            var preparer = GitRuntimeTestBridge.preparation(runtime, context, GIT);
            var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
                new DeploymentGitRef.Commit(commit), Set.of(), 32L * 1024 * 1024, true);
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("git-large-source", "Large Git source", List.of(), true), task -> {
                try (var material = preparer.prepare(request, 32L * 1024 * 1024, 100, task,
                    gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitJournalFixture
                        .begin(context, task, request, 32L * 1024 * 1024, 100))) {
                    assertEquals(commit, material.commit());
                    assertEquals(24L * 1024 * 1024,
                        Files.size(material.source().snapshot().directory().resolve("large-source.dat")));
                    assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                        Files.readAllBytes(material.source().snapshot().directory().resolve("large-source.dat")))));
                    assertTrue(material.archive().byteCount() > 16L * 1024 * 1024);
                    assertTrue(material.source().includedFiles().contains("large-source.dat"));
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Throwable problem) {
                    failure.set(problem);
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.empty());
                }
            });
            var result = handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
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
        assertThrows(IllegalArgumentException.class, () -> new DeploymentGitRef.Branch("../main"));
        var remote = DeploymentGitRemote.parse(repo.toUri().toString());
        assertThrows(IllegalArgumentException.class, () -> new DeploymentGitRequest(remote,
            new DeploymentGitRef.DefaultBranch(), Set.of(), 1000, false));
        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentGitRequest(DeploymentGitRemote.parse("https://example.invalid/repo"),
                new DeploymentGitRef.DefaultBranch(), Set.of(), 1000, false));
        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentGitRequest(DeploymentGitRemote.parse("ssh://example.invalid/repo"),
                new DeploymentGitRef.DefaultBranch(), Set.of("example.invalid"), 1000, false));
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            context = runtime.modContexts().get(OWNER).context();
            var preparer = GitRuntimeTestBridge.preparation(runtime, context, GIT);
            var absentGit = GitRuntimeTestBridge.preparation(runtime, context, root.resolve("missing-git.exe"));
            assertEquals(ProblemCode.INVALID_INPUT,
                assertThrows(ApiException.class,
                    () -> absentGit.prepare(new DeploymentGitRequest(remote,
                            new DeploymentGitRef.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true),
                        2 * 1024 * 1024, 100, null, null))
                    .problem().code());
            rejected(runtime, preparer, new DeploymentGitRequest(remote, new DeploymentGitRef.Branch("missing"), Set.of(),
                2 * 1024 * 1024, true));
            String blob = git(repo, "hash-object", "-w", "pom.xml");
            git(repo, "update-index", "--add", "--cacheinfo", "120000," + blob + ",link");
            git(repo, "commit", "-m", "link");
            rejected(runtime, preparer, new DeploymentGitRequest(remote, new DeploymentGitRef.DefaultBranch(), Set.of(),
                2 * 1024 * 1024, true));
            git(repo, "rm", "--cached", "link");
            git(repo, "update-index", "--add", "--cacheinfo", "160000," + git(repo, "rev-parse", "HEAD") + ",nested");
            git(repo, "commit", "-m", "gitlink");
            rejected(runtime, preparer, new DeploymentGitRequest(remote, new DeploymentGitRef.DefaultBranch(), Set.of(),
                2 * 1024 * 1024, true));
            git(repo, "rm", "--cached", "nested");
            Files.createDirectories(repo.resolve("sub"));
            Files.writeString(repo.resolve("sub/.gitattributes"), "*.bin filter=lfs\n");
            Files.writeString(repo.resolve("sub/blob.bin"), "version https://git-lfs.github.com/spec/v1\n");
            git(repo, "add", ".");
            git(repo, "commit", "-m", "lfs");
            rejected(runtime, preparer, new DeploymentGitRequest(remote, new DeploymentGitRef.DefaultBranch(), Set.of(),
                2 * 1024 * 1024, true));
            git(repo, "mv", "-f", "sub/.gitattributes", "sub/.GitAttributes");
            git(repo, "commit", "-m", "mixed-case lfs attributes");
            rejected(runtime, preparer, new DeploymentGitRequest(remote, new DeploymentGitRef.DefaultBranch(), Set.of(),
                2 * 1024 * 1024, true));
            git(repo, "rm", "sub/.GitAttributes");
            Files.writeString(repo.resolve("sub/.gitmodules"),
                "[submodule \"fake\"]\n path = fake\n url = https://example.invalid/fake\n");
            git(repo, "add", ".");
            git(repo, "commit", "-m", "nested submodule metadata");
            rejected(runtime, preparer, new DeploymentGitRequest(remote, new DeploymentGitRef.DefaultBranch(), Set.of(),
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
            var provider = new AtomicReference<ModContext>();
            var receiptPath = new AtomicReference<Path>();
            var receiptBytes = new AtomicReference<byte[]>();
            var requests = new AtomicInteger();
            ProcessService processes = new ProcessService() {
                public ManagedProcess start(ProcessSpec spec, TaskContext task) throws ApiException {
                    return started(spec, task, null);
                }

                public ManagedProcess start(ProcessSpec spec, TaskContext task, UUID id) throws ApiException {
                    return started(spec, task, id);
                }

                public void closeTaskResources(TaskContext task) throws ApiException {
                    provider.get().processes().closeTaskResources(task);
                }

                private ManagedProcess started(ProcessSpec spec, TaskContext task, UUID id) throws ApiException {
                    Path receipt = Path.of(spec.arguments().get(8)).resolve("git-result.json");
                    receiptPath.set(receipt);
                    ManagedProcess actual = id == null
                        ? provider.get().processes().start(spec, task)
                        : provider.get().processes().start(spec, task, id);
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
                }
            };
            var preparer = GitRuntimeTestBridge.preparation(runtime, original, GIT);
            var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
                new DeploymentGitRef.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
            var failure = new AtomicReference<Throwable>();
            var callbackFinished = new CountDownLatch(1);
            var originalObserver = new AtomicReference<DeploymentGitSetup.Observer>();
            var originalCalls = new AtomicReference<Map<DependencyRecovery, List<UUID>>>();
            var registration = context.tasks().registerRecovery("git-receipt", (previous, originalTask) -> {
                try {
                    var observer = originalObserver.get();
                    var source = GitRuntimeTestBridge.reconcileOriginal(runtime, original, observer.planned(), originalTask);
                    assertEquals(TaskResult.FAILED, source.outcome(), source.toString());
                    var reason = source.problem().orElseThrow();
                    observer.receipt(new DeploymentGitSetup.Receipt(source.outcome(), Optional.empty(),
                        Optional.of(reason.code()), Optional.of(reason.eventCode())));
                    preparer.closeTaskResources(originalTask);
                    GitRuntimeTestBridge.onlyOriginalCallsRemain(runtime, originalTask.id(), originalCalls.get());
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.IO_FAILURE, "TEST_RECEIPT_TAMPER",
                            "Original delivery was withheld", Map.of(), Optional.empty())));
                } catch (Throwable problem) {
                    failure.set(problem);
                    throw new AssertionError(problem);
                }
            });
            var handle = context.tasks().submit(new TaskRequest("git-receipt", "Git receipt", List.of(), true),
                task -> {
                    try {
                        GitProviderTestAccess.observeProcesses(runtime, original, task, actual -> {
                            provider.set(actual);
                            return processes;
                        });
                        var observer = gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitJournalFixture
                            .begin(context, task, request);
                        originalObserver.set(observer);
                        var unknown = assertThrows(ApiException.class,
                            () -> preparer.prepare(request, 2 * 1024 * 1024, 100, task, observer));
                        assertEquals(ProblemCode.UNKNOWN, unknown.problem().code());
                        assertNotNull(receiptBytes.get());
                        assertTrue(scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'") > 0);
                        assertEquals(2, scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='FILE'"));
                        return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                            Optional.of(unknown.problem()));
                    } catch (Throwable problem) {
                        failure.set(problem);
                        return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.empty());
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
            Files.write(receiptPath.get(), receiptBytes.get());
            originalCalls.set(GitRuntimeTestBridge.retainedCalls(runtime, handle.snapshot().id()));
            reconcileReady(runtime, handle);
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(TaskResult.FAILED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, requests.get());
            registration.close();
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
