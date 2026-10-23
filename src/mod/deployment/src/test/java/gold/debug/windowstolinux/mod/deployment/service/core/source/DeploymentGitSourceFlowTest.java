package gold.debug.windowstolinux.mod.deployment.service.core.source;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.TaskAuthorCheckpoint;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectStore;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRef;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRemote;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitSetup;
import gold.debug.windowstolinux.mod.deployment.file.git.GitRuntimeTestBridge;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectSource;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSetupActionLog;
import org.junit.jupiter.api.Test;

class DeploymentGitSourceFlowTest extends DeploymentRuntimeFixture {
    @Override
    protected boolean gitEnabled() {
        return true;
    }

    private static final Path GIT = locateGit();

    private static Path locateGit() {
        for (String directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            Path path = Path.of(directory).resolve("git.exe");
            if (Files.isRegularFile(path))
                return path.toAbsolutePath().normalize();
        }
        throw new AssertionError("Local native Git is required");
    }

    private String git(Path directory, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(GIT.toString());
        command.addAll(List.of(args));
        var child = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        child.getOutputStream().close();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, child.waitFor(), output);
        return output;
    }

    private Path repository() throws Exception {
        var path = Files.createDirectory(root.resolve("git-repository"));
        git(path, "init", "-b", "main");
        git(path, "config", "user.email", "test@example.invalid");
        git(path, "config", "user.name", "Test");
        Files.writeString(path.resolve("pom.xml"), "original");
        git(path, "add", ".");
        git(path, "commit", "-m", "original");
        return path;
    }

    @Test
    void realClassTaskSavesExactGitProjectAndReleasesOriginalResources() throws Exception {
        var repo = repository();
        var commit = git(repo, "rev-parse", "HEAD");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
                new DeploymentGitRef.Branch("main"), Set.of(), 2 * 1024 * 1024, true);
            var flow = GitRuntimeTestBridge.flow(runtime, context);
            var id = UUID.randomUUID();
            var handle = flow.submit(request, GIT, id, "Git project", Optional.empty(),
                Instant.parse("2026-09-28T00:00:00Z"), 2 * 1024 * 1024, 100);
            var result = handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            var project = result.value().orElseThrow();
            assertEquals(id, project.id());
            assertEquals(commit, project.source().gitCommit().orElseThrow());
            assertEquals("BRANCH:main", project.source().gitRef().orElseThrow());
            var read = context.tasks().submit(
                new TaskRequest("git-project-read", "Read saved project", List.of(), false),
                task -> new OperationResult<>(TaskResult.SUCCEEDED,
                    new DeploymentProjectStore(context.db()).find(task, id), Optional.empty()));
            assertEquals(project,
                read.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    @Test
    void actualOriginalReceiptReconcilesWithoutGitReplayOrProjectSave() throws Exception {
        var repo = repository();
        enable();
        var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
            new DeploymentGitRef.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var handle = context.tasks().submit(new TaskRequest(DeploymentGitSourceFlow.TASK_TYPE,
                "Interrupted original Git project", List.of(), true), task -> {
                var journal = DeploymentGitSourceActionLog.begin(context.tasks(), task, UUID.randomUUID(),
                    "Unwritten Git project", Optional.empty(), Instant.parse("2026-09-28T00:00:00Z"),
                    request, 2 * 1024 * 1024, 100);
                try (var material = GitRuntimeTestBridge.preparation(runtime, context, GIT).prepare(request, 2 * 1024 * 1024,
                    100, task, journal)) {
                    assertNotNull(material.commit());
                }
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_INTERRUPTED_BEFORE_SAVE",
                        "Original callback was interrupted before project save", Map.of(),
                        Optional.empty())));
            });
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
                Thread.onSpinWait();
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertEquals(1, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
            Files.move(repo, root.resolve("remote-unavailable"));
            var recovered = reconcileReady(runtime, handle);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    /** Static recovery cannot infer Git exit from its local marker when the original request has no receipt. / 原请求缺回执时，静态恢复不能据本机标记推断 Git 已退出。
     * @throws Exception if actual task or checkpoint setup fails / 真实任务或检查点设置失败时 */
    @Test
    void staticPublicationMissingGitReceiptRetainsUnknownWithoutReplay() throws Exception {
        runUnknownProbe("--missing-static-receipt", "STATIC_GIT_RECEIPT_MISSING_VERIFIED");
    }

    /** A started project write without an exact row remains UNKNOWN and retains its original checkpoints. / 已开始的项目写入缺精确行时保持 UNKNOWN 并保留原检查点。
     * @throws Exception if the isolated original task cannot finish / 隔离原任务无法完成时 */
    @Test
    void staticPublicationUnknownProjectWriteRetainsOriginalFacts() throws Exception {
        runUnknownProbe("--unknown-static-save", "STATIC_GIT_SAVE_UNKNOWN_VERIFIED");
    }

    private void runUnknownProbe(String scenario, String marker) throws Exception {
        var javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var child = new ProcessBuilder(javaBinary.toString(), "-cp", System.getProperty("java.class.path"),
            getClass().getName(), scenario, root.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Original UNKNOWN child timed out");
            var output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains(marker), output);
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(2, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 2)
            throw new IllegalArgumentException("Isolated Git probe only");
        try {
            var probe = new DeploymentGitSourceFlowTest();
            probe.root = Path.of(args[1]);
            switch (args[0]) {
                case "--missing-static-receipt" -> {
                    probe.verifyMissingStaticReceipt();
                    System.out.println("STATIC_GIT_RECEIPT_MISSING_VERIFIED");
                }
                case "--unknown-static-save" -> {
                    probe.verifyUnknownStaticSave();
                    System.out.println("STATIC_GIT_SAVE_UNKNOWN_VERIFIED");
                }
                default -> throw new IllegalArgumentException("Isolated Git probe only");
            }
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private void verifyMissingStaticReceipt() throws Exception {
        var repo = repository();
        enable();
        var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
            new DeploymentGitRef.DefaultBranch(), Set.of(), 65536, true);
        var runtime = open();
        {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var handle = context.tasks().submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                "Original static Git without receipt", List.of(), true), task -> {
                gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSetupActionLog
                    .begin(context.tasks(), task,
                        new gold.debug.windowstolinux.appapi.server.model.ServerId("server"));
                var journal = DeploymentGitSourceActionLog.begin(context.tasks(), task, UUID.randomUUID(),
                    "reviewed-project", Optional.empty(), Instant.parse("2026-09-28T00:00:00Z"), request,
                    65536, 100);
                journal.beforeRequest();
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_GIT_RECEIPT_MISSING",
                        "Original receipt unavailable", Map.of(), Optional.empty())));
            });
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
                Thread.onSpinWait();
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            Files.move(repo, root.resolve("remote-unavailable"));
            var recovered = reconcileReady(runtime, handle);
            assertEquals(TaskState.UNKNOWN, recovered.state());
            assertEquals(2, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
        }
    }

    private void verifyUnknownStaticSave() throws Exception {
        var repo = repository();
        enable();
        var request = new DeploymentGitRequest(new DeploymentGitRemote(repo.toUri()),
            new DeploymentGitRef.DefaultBranch(), Set.of(), 65536, true);
        var runtime = open();
        start(runtime, true);
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(
            new TaskRequest(DeploymentRefFlow.TASK_TYPE, "Original static Git save unknown", List.of(), true),
            task -> {
                DeploymentStandardSetupActionLog.begin(context.tasks(), task,
                    new gold.debug.windowstolinux.appapi.server.model.ServerId("server"));
                var pinned = GitRuntimeTestBridge.flow(runtime, context).prepareForPublication(request, GIT,
                    "reviewed-project", 65536, 100, task);
                var journal = DeploymentGitSourceActionLog.find(context.tasks(), task).orElseThrow();
                var facts = journal.facts();
                var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.GIT,
                    request.remote().location(), Optional.of("DEFAULT:HEAD"),
                    Optional.of(pinned.material().commit()), pinned.material().source().snapshot().sha256());
                journal.propose(new DeploymentProjectRecord(facts.project(), facts.displayName(), source,
                    facts.observedAt(), facts.observedAt()));
                journal.writeStarted();
                assertEquals(ProblemCode.UNKNOWN, assertThrows(ApiException.class, pinned::close).problem().code());
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_GIT_SAVE_UNKNOWN",
                        "Original save acknowledgement unavailable", Map.of(), Optional.empty())));
            });
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
            Thread.onSpinWait();
        assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
        Files.move(repo, root.resolve("remote-unavailable"));
        assertEquals(TaskState.UNKNOWN,
            reconcileReady(runtime, handle).state());
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_project")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
        assertEquals(2, scalar(runtime,
            "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
    }

    /** A late verified cancellation remains cancellation after both original preparation checkpoints settle. / 晚到的已核实取消在两个原准备检查点收尾后仍为取消。
     * @throws Exception if original CLASS task recovery cannot settle / 原 CLASS 任务恢复无法结清时 */
    @Test
    void staticRecoveryPreservesLateGitCancellation() throws Exception {
        enable();
        var request = new DeploymentGitRequest(new DeploymentGitRemote(root.resolve("unused-repo").toUri()),
            new DeploymentGitRef.DefaultBranch(), Set.of(), 65536, true);
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var lateReceipt = new AtomicReference<String>();
            var tasks = context.tasks();
            var handle = tasks.submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                "Late original Git cancellation", List.of(), true), task -> {
                DeploymentStandardSetupActionLog.begin(tasks, task,
                    new gold.debug.windowstolinux.appapi.server.model.ServerId("server"));
                var journal = DeploymentGitSourceActionLog.begin(tasks, task, UUID.randomUUID(),
                    "reviewed-project", Optional.empty(), Instant.parse("2026-09-28T00:00:00Z"), request,
                    65536, 100);
                journal.beforeRequest();
                var checkpoint = tasks.findCheckpoint(task, DeploymentGitSourceActionLog.KEY).orElseThrow();
                var requested = checkpoint.snapshot().payload();
                journal.receipt(new DeploymentGitSetup.Receipt(TaskResult.CANCELLED, Optional.empty(),
                    Optional.of(ProblemCode.CANCELLED), Optional.of("GIT_CANCELLED")));
                lateReceipt.set(checkpoint.snapshot().payload());
                checkpoint.advance(checkpoint.snapshot(), requested);
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(
                    new Problem(ProblemCode.UNKNOWN, "TEST_GIT_RECEIPT_MISSING", "Waiting for the original receipt",
                        Map.of(), Optional.empty())));
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < until)
                Thread.sleep(20);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
            var author = new TaskAuthorCheckpoint(runtime.persistence().database());
            var retained = author.find(handle.id(), OWNER, VERSION, DeploymentGitSourceActionLog.KEY).orElseThrow();
            author.advance(handle.id(), OWNER, VERSION, DeploymentGitSourceActionLog.KEY,
                retained.lease(), retained.snapshot(), lateReceipt.get());
            var result = reconcileReady(runtime, handle);
            assertEquals(TaskState.ENDED, result.state());
            assertEquals(Optional.of(TaskResult.CANCELLED), result.outcome());
        }
    }

    @Test
    void preparedOriginalTaskReopensThroughModRegistrationWithoutCreatingGitWork() throws Exception {
        var repo = repository();
        enable();
        var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
            new DeploymentGitRef.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
        var graph = new AtomicReference<TaskRecoveryGraph>();
        var payload = new AtomicReference<String>();
        UUID prior, interrupted = UUID.randomUUID();
        UUID boot;
        gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity schema;
        try (var runtime = open()) {
            start(runtime, true);
            boot = runtime.persistence().database().bootId();
            var context = runtime.modContexts().get(OWNER).context();
            schema = context.db().identity();
            var probe = context.tasks().submit(
                new TaskRequest(DeploymentGitSourceFlow.TASK_TYPE, "Capture original graph", List.of(), true),
                task -> {
                    graph.set(new TaskLedger(runtime.persistence().database()).queries().recoveryGraph(task.id())
                        .orElseThrow());
                    var journal = DeploymentGitSourceActionLog.begin(context.tasks(), task, UUID.randomUUID(),
                        "Prepared Git project", Optional.empty(), Instant.parse("2026-09-28T00:00:00Z"),
                        request, 1024, 100);
                    payload.set(context.tasks().findCheckpoint(task, DeploymentGitSourceActionLog.KEY).orElseThrow()
                        .snapshot().payload());
                    journal.close();
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
            prior = probe.id();
            assertEquals(TaskResult.SUCCEEDED,
                probe.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
        }
        try (var db = PlatformDatabase.open(root.resolve("data"), PROTECTION)) {
            var ledger = new TaskLedger(db);
            ledger.enqueue(interrupted, OWNER, VERSION,
                new TaskRequest(DeploymentGitSourceFlow.TASK_TYPE, "Interrupted original Git", List.of(), true),
                new TaskLedger.InitialClaims() {
                    public List<TaskResource> resolve(java.sql.Connection connection) {
                        return graph.get().claims();
                    }

                    public Optional<TaskRecoveryGraph> recoveryGraph(java.sql.Connection connection) {
                        return Optional.of(graph.get());
                    }
                });
            assertTrue(ledger.tryStart(interrupted));
            new TaskAuthorCheckpoint(db).begin(interrupted, OWNER, VERSION, DeploymentGitSourceActionLog.KEY,
                payload.get().replace(prior.toString(), interrupted.toString()));
            assertFalse(ledger.complete(interrupted, TaskResult.UNKNOWN, "Interrupted before first Git request", false));
            try (var connection = db.openConnection();
                 var update = connection.prepareStatement(
                     "UPDATE mod_registry SET last_boot_id=?,last_runtime_state='UNKNOWN',actual_version=? WHERE mod_id=?")) {
                update.setObject(1, boot);
                update.setString(2, VERSION);
                update.setString(3, OWNER);
                assertEquals(1, update.executeUpdate());
            }
        }
        Files.move(repo, root.resolve("remote-unavailable"));
        try (var runtime = open()) {
            var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
                public void show(ModPageRegistry.PageKey key, PageSpec spec, javax.swing.JComponent component) {
                    throw new AssertionError("Recovery must not mount a page");
                }

                public void focus(ModPageRegistry.PageKey key) {
                }

                public void remove(ModPageRegistry.PageKey key) {
                }

                public void environment(UiEnvironment environment) {
                }
            }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertEquals(Set.of("gold.debug.windowstolinux.mod.git"), report.running(),
                "Unused optional Git starts normally while only the original root graph recovers");
            assertEquals(List.of("MOD_RECOVERY_READY"), report.problems().stream().map(Problem::eventCode).toList());
            var recovered = runtime.tasks().reconcile(interrupted).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
            runtime.modRecovery().release();
        }
    }

    @Test
    void lostProjectWriteAcknowledgementUsesOnlyExactProposedRowWithoutGitReplay() throws Exception {
        var repo = repository();
        enable();
        var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
            new DeploymentGitRef.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
        var observedAt = Instant.parse("2026-09-28T00:00:00Z");
        var id = UUID.randomUUID();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var handle = context.tasks().submit(new TaskRequest(DeploymentGitSourceFlow.TASK_TYPE,
                "Lost project write acknowledgement", List.of(), true), task -> {
                var journal = DeploymentGitSourceActionLog.begin(context.tasks(), task, id,
                    "Proposed Git project", Optional.empty(), observedAt, request, 2 * 1024 * 1024, 100);
                try (var material = GitRuntimeTestBridge.preparation(runtime, context, GIT).prepare(request, 2 * 1024 * 1024,
                    100, task, journal)) {
                    var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.GIT,
                        request.remote().location(), Optional.of("DEFAULT:HEAD"),
                        Optional.of(material.commit()), material.source().snapshot().sha256());
                    var next = new DeploymentProjectRecord(id, "Proposed Git project", source, observedAt,
                        observedAt);
                    journal.propose(next);
                    journal.writeStarted();
                    new DeploymentProjectStore(context.db()).create(task, next);
                }
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_LOST_WRITE_ACK",
                        "Original project save acknowledgement was withheld", Map.of(),
                        Optional.empty())));
            });
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
                Thread.onSpinWait();
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            Files.move(repo, root.resolve("remote-unavailable"));
            var recovered = reconcileReady(runtime, handle);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(TaskResult.SUCCEEDED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    @Test
    void confirmedProjectRollbackKeepsKnownFailureAndOriginalRow() throws Exception {
        var repo = repository();
        enable();
        var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
            new DeploymentGitRef.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
        var observed = Instant.parse("2026-09-28T00:00:00Z");
        var id = UUID.randomUUID();
        var prior = new DeploymentProjectRecord(id, "Existing local source",
            new DeploymentProjectSource(DeploymentProjectSource.KindType.LOCAL, repo.toUri(), Optional.empty(),
                Optional.empty(), "a".repeat(64)),
            observed, observed);
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var seed = context.tasks().submit(new TaskRequest("project-seed", "Seed project", List.of(), false),
                task -> {
                    new DeploymentProjectStore(context.db()).create(task, prior);
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
            assertEquals(TaskResult.SUCCEEDED,
                seed.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            var handle = GitRuntimeTestBridge.flow(runtime, context).submit(request, GIT, id, "Conflicting Git source",
                Optional.empty(), observed, 2 * 1024 * 1024, 100);
            var result = handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertEquals(TaskResult.FAILED, result.outcome(), result.toString());
            var read = context.tasks().submit(
                new TaskRequest("project-read", "Read original project", List.of(), false),
                task -> new OperationResult<>(TaskResult.SUCCEEDED,
                    new DeploymentProjectStore(context.db()).find(task, id), Optional.empty()));
            assertEquals(prior,
                read.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }
}
