package gold.debug.windowstolinux.mod.deployment.service.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRepository;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;
import gold.debug.windowstolinux.mod.deployment.source.git.*;
import org.junit.jupiter.api.Test;

class DeploymentGitSourceFlowTest extends DeploymentRuntimeFixture {
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
                    new DeploymentGitReference.Branch("main"), Set.of(), 2 * 1024 * 1024, true);
            var flow = new DeploymentGitSourceFlow(context);
            var id = UUID.randomUUID();
            var handle = flow.submit(request, GIT, id, "Git project", Optional.empty(),
                    Instant.parse("2026-09-28T00:00:00Z"), 2 * 1024 * 1024, 100);
            var result = handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            var project = result.value().orElseThrow();
            assertEquals(id, project.id());
            assertEquals(commit, project.source().gitCommit().orElseThrow());
            assertEquals("BRANCH:main", project.source().gitRef().orElseThrow());
            var read = context.tasks().submit(
                    new TaskRequest("git-project-read", "Read saved project", List.of(), false),
                    task -> new OperationResult<>(Outcome.SUCCEEDED,
                            new DeploymentProjectRepository(context.db()).find(task, id), Optional.empty()));
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
                new DeploymentGitReference.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var handle = context.tasks().submit(new TaskRequest(DeploymentGitSourceFlow.TASK_TYPE,
                    "Interrupted original Git project", List.of(), true), task -> {
                        var journal = DeploymentGitSourceJournal.begin(context.tasks(), task, UUID.randomUUID(),
                                "Unwritten Git project", Optional.empty(), Instant.parse("2026-09-28T00:00:00Z"),
                                request, 2 * 1024 * 1024, 100);
                        try (var material = new DeploymentGitPreparer(context, GIT).prepare(request, 2 * 1024 * 1024,
                                100, task, journal)) {
                            assertNotNull(material.commit());
                        }
                        return new OperationResult<Void>(Outcome.UNKNOWN, Optional.empty(),
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
            var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(Outcome.FAILED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    @Test
    void preparedOriginalTaskReopensThroughModRegistrationWithoutCreatingGitWork() throws Exception {
        var repo = repository();
        enable();
        var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
                new DeploymentGitReference.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
        var graph = new AtomicReference<TaskRecoveryGraph>();
        var payload = new AtomicReference<String>();
        UUID prior, interrupted = UUID.randomUUID();
        UUID boot;
        gold.debug.windowstolinux.appapi.db.SchemaIdentity schema;
        try (var runtime = open()) {
            start(runtime, true);
            boot = runtime.persistence().database().bootId();
            var context = runtime.modContexts().get(OWNER).context();
            schema = context.db().identity();
            var probe = context.tasks().submit(
                    new TaskRequest(DeploymentGitSourceFlow.TASK_TYPE, "Capture original graph", List.of(), true),
                    task -> {
                        graph.set(new TaskLedger(runtime.persistence().database()).recoveryGraph(task.id())
                                .orElseThrow());
                        var journal = DeploymentGitSourceJournal.begin(context.tasks(), task, UUID.randomUUID(),
                                "Prepared Git project", Optional.empty(), Instant.parse("2026-09-28T00:00:00Z"),
                                request, 1024, 100);
                        payload.set(context.tasks().findCheckpoint(task, DeploymentGitSourceJournal.KEY).orElseThrow()
                                .snapshot().payload());
                        journal.close();
                        return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                    });
            prior = probe.id();
            assertEquals(Outcome.SUCCEEDED,
                    probe.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
        }
        try (var db = PlatformDatabase.open(root.resolve("data"), PROTECTION)) {
            var ledger = new TaskLedger(db);
            ledger.enqueue(interrupted, OWNER, VERSION,
                    new TaskRequest(DeploymentGitSourceFlow.TASK_TYPE, "Interrupted original Git", List.of(), true),
                    new TaskLedger.InitialClaims() {
                        public List<TaskResource> resolve(java.sql.Connection connection) {
                            return List.of(new TaskResource("MOD", OWNER, OWNER, false),
                                    new TaskResource("SCHEMA", schema.generation().toString(), OWNER, false));
                        }

                        public Optional<TaskRecoveryGraph> recoveryGraph(java.sql.Connection connection) {
                            return Optional.of(graph.get());
                        }
                    });
            assertTrue(ledger.tryStart(interrupted));
            new TaskAuthorCheckpoint(db).begin(interrupted, OWNER, VERSION, DeploymentGitSourceJournal.KEY,
                    payload.get().replace(prior.toString(), interrupted.toString()));
            assertFalse(ledger.complete(interrupted, Outcome.UNKNOWN, "Interrupted before first Git request", false));
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
            assertTrue(report.running().isEmpty());
            assertEquals(List.of("MOD_RECOVERY_READY"), report.problems().stream().map(Problem::eventCode).toList());
            var recovered = runtime.tasks().reconcile(interrupted).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(Outcome.FAILED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
            runtime.releaseModRecovery();
        }
    }

    @Test
    void lostProjectWriteAcknowledgementUsesOnlyExactProposedRowWithoutGitReplay() throws Exception {
        var repo = repository();
        enable();
        var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
                new DeploymentGitReference.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
        var observedAt = Instant.parse("2026-09-28T00:00:00Z");
        var id = UUID.randomUUID();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var handle = context.tasks().submit(new TaskRequest(DeploymentGitSourceFlow.TASK_TYPE,
                    "Lost project write acknowledgement", List.of(), true), task -> {
                        var journal = DeploymentGitSourceJournal.begin(context.tasks(), task, id,
                                "Proposed Git project", Optional.empty(), observedAt, request, 2 * 1024 * 1024, 100);
                        try (var material = new DeploymentGitPreparer(context, GIT).prepare(request, 2 * 1024 * 1024,
                                100, task, journal)) {
                            var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.GIT,
                                    request.remote().location(), Optional.of("DEFAULT:HEAD"),
                                    Optional.of(material.commit()), material.source().snapshot().sha256());
                            var next = new DeploymentProjectRecord(id, "Proposed Git project", source, observedAt,
                                    observedAt);
                            journal.propose(next);
                            journal.writeStarted();
                            new DeploymentProjectRepository(context.db()).create(task, next);
                        }
                        return new OperationResult<Void>(Outcome.UNKNOWN, Optional.empty(),
                                Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_LOST_WRITE_ACK",
                                        "Original project save acknowledgement was withheld", Map.of(),
                                        Optional.empty())));
                    });
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
                Thread.onSpinWait();
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            Files.move(repo, root.resolve("remote-unavailable"));
            var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(Outcome.SUCCEEDED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }

    @Test
    void confirmedProjectRollbackKeepsKnownFailureAndOriginalRow() throws Exception {
        var repo = repository();
        enable();
        var request = new DeploymentGitRequest(DeploymentGitRemote.parse(repo.toUri().toString()),
                new DeploymentGitReference.DefaultBranch(), Set.of(), 2 * 1024 * 1024, true);
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
                        new DeploymentProjectRepository(context.db()).create(task, prior);
                        return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                    });
            assertEquals(Outcome.SUCCEEDED,
                    seed.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            var handle = new DeploymentGitSourceFlow(context).submit(request, GIT, id, "Conflicting Git source",
                    Optional.empty(), observed, 2 * 1024 * 1024, 100);
            var result = handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertEquals(Outcome.FAILED, result.outcome(), result.toString());
            var read = context.tasks().submit(
                    new TaskRequest("project-read", "Read original project", List.of(), false),
                    task -> new OperationResult<>(Outcome.SUCCEEDED,
                            new DeploymentProjectRepository(context.db()).find(task, id), Optional.empty()));
            assertEquals(prior,
                    read.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM','PROCESS')"));
        }
    }
}
