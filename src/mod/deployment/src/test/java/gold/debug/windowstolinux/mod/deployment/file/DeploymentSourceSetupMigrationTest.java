package gold.debug.windowstolinux.mod.deployment.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRef;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRemote;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;
import gold.debug.windowstolinux.mod.deployment.file.git.GitRuntimeTestBridge;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeReport;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.part.PartIsolationSpec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.PartAnalysisRequest;
import org.junit.jupiter.api.Test;

/** Retains source-preparation contracts on authentic Mod tasks and separately identified source/archive bytes. / 在真实Mod任务及分别标识的源码/归档字节上保留来源准备合同。 */
class DeploymentSourceSetupMigrationTest extends DeploymentRuntimeFixture {
    private static final Path GIT = Path.of("E:/Program/Git/cmd/git.exe");

    @Override
    protected boolean gitEnabled() {
        return true;
    }

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Test
    void bindsThePinnedGitCommitRemoteAndArchiveDigestBeforeDeploymentPlanning() throws Exception {
        var repository = Files.createDirectories(root.resolve("repository"));
        node(repository, "git-demo");
        git(repository, "init");
        git(repository, "config", "user.email", "test@example.invalid");
        git(repository, "config", "user.name", "Test");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "source fixture");
        String commit = git(repository, "rev-parse", "HEAD").trim();
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                var remote = DeploymentGitRemote.parse(repository.toUri().toString());
                var request = new DeploymentGitRequest(remote, new DeploymentGitRef.Commit(commit), Set.of(),
                    2 * 1024 * 1024, true);
                try (var prepared = GitRuntimeTestBridge.preparation(runtime, context, GIT).prepare(request, 2 * 1024 * 1024, 100, task, gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentGitJournalFixture.begin(context, task, request))) {
                    assertEquals(commit, prepared.commit());
                    assertEquals(repository.toUri(), prepared.remote().location());
                    byte[] archive = Files.readAllBytes(prepared.archive().path());
                    assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)),
                        prepared.archive().sha256());
                    assertEquals(prepared.source().snapshot().sha256(), prepared.archive().sourceManifestSha256());
                    assertEquals(prepared.source().snapshot().id(), prepared.archive().originSnapshotId());
                    assertNotEquals(prepared.source().snapshot().sha256(), prepared.archive().sha256());
                    var analyzed = new DeploymentStandardAnalyzer().analyze(prepared.source(), "git-demo",
                        DeploymentProjectKind.NODE_SERVICE, Optional.empty(), false, task);
                    assertEquals(TaskResult.SUCCEEDED, analyzed.outcome());
                    assertEquals(prepared.source().snapshot().sha256(), analyzed.value().orElseThrow().snapshotSha256());
                    assertEquals(Optional.of("22"), analyzed.value().orElseThrow().assessment().runtimeSuggestion()
                        .orElseThrow().value(DeploymentRuntimeReport.RuntimeInputType.NODE_MAJOR_VERSION));
                }
                return success();
            });
        }
    }

    @Test
    void recognitionPreviewNeverCreatesAnArchiveOrSourceRevision() throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("install.sh"), "this must never execute");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                try (var material = new DeploymentSourceSetup(context.file()).prepare(
                    context.file().sources().selectDirectory("Preview", task), 65536, 100, task)) {
                    var result = new DeploymentStandardAnalyzer().analyze(material, "preview",
                        DeploymentProjectKind.RECOGNITION_PREVIEW, Optional.empty(), false, task);
                    assertEquals(TaskResult.SUCCEEDED, result.outcome());
                    assertEquals(DeploymentAdmissionStatus.RECOGNITION_PREVIEW,
                        result.value().orElseThrow().assessment().admission());
                    assertEquals(1, files(runtime), "Only the source snapshot exists; no archive was retained");
                    try (var connection = context.db().openConnection(task); var query = connection.createStatement();
                         var rows = query.executeQuery("SELECT COUNT(*) FROM deployment_project")) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1), "Preview cannot save a source revision or project");
                    }
                }
                assertEquals(0, files(runtime));
                return success();
            });
        }
    }

    @Test
    void preparesIndependentArchivesOnlyAfterTheWholeComponentGraphIsAdmitted() throws Exception {
        node(Files.createDirectories(selectedSourceDirectory().resolve("api")), "api");
        node(Files.createDirectories(selectedSourceDirectory().resolve("web")), "web");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                try (var material = new DeploymentSourceSetup(context.file()).prepare(
                    context.file().sources().selectDirectory("Graph", task), 65536, 100, task)) {
                    var analyzer = new DeploymentStandardAnalyzer();
                    var rejected = analyzer.analyzeComponents(material, "application", "conflict",
                        List.of(component("api", 18081, Set.of()), component("web", 18081, Set.of("api"))),
                        Map.of(), false, task);
                    assertEquals(TaskResult.SUCCEEDED, rejected.outcome());
                    assertEquals(DeploymentAdmissionStatus.REJECTED, rejected.value().orElseThrow().assessment().admission());
                    assertEquals(1, files(runtime), "Whole-graph rejection cannot retain a component archive");
                    var accepted = analyzer.analyzeComponents(material, "application", "shop",
                        List.of(component("api", 18081, Set.of()), component("web", 18082, Set.of("api"))),
                        Map.of(), false, task);
                    assertEquals(TaskResult.SUCCEEDED, accepted.outcome());
                    assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING,
                        accepted.value().orElseThrow().assessment().admission());
                    assertEquals(List.of("api", "web"), accepted.value().orElseThrow().assessment().components().stream()
                        .map(value -> value.componentId()).toList());
                    try (var api = new DeploymentSourceArchiver(context.file()).prepare(material, "api", 65536, task);
                         var web = new DeploymentSourceArchiver(context.file()).prepare(material, "web", 65536, task)) {
                        assertNotEquals(api.path(), web.path());
                        assertEquals(material.snapshot().id(), api.originSnapshotId());
                        assertEquals(material.snapshot().id(), web.originSnapshotId());
                        for (var archive : List.of(api, web)) {
                            assertTrue(Files.isRegularFile(archive.path()));
                            assertEquals(material.snapshot().sha256(), archive.sourceManifestSha256());
                            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(archive.path()))), archive.sha256());
                        }
                        assertEquals(3, files(runtime));
                    }
                    assertEquals(1, files(runtime));
                }
                assertEquals(0, files(runtime));
                return success();
            });
        }
    }

    private static PartAnalysisRequest component(String id, int port, Set<String> dependencies) {
        return new PartAnalysisRequest(id, id, DeploymentProjectKind.NODE_SERVICE,
            Optional.of(new DeploymentRuntimeDefinition.NodeService(22, new DeploymentHealthCheck.Tcp(port, 20, 1))),
            List.of(id + "/dist"), Set.of(port), List.of("PORT"), List.of(), List.of(), dependencies, true,
            PartIsolationSpec.managed());
    }

    private static void node(Path directory, String name) throws Exception {
        Files.writeString(directory.resolve("package.json"),
            "{\"name\":\"" + name + "\",\"engines\":{\"node\":\"22\"},\"scripts\":{\"build\":\"build\",\"start\":\"start\"}}");
        Files.writeString(directory.resolve("package-lock.json"),
            "{\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"lockfileVersion\":3,\"requires\":true,\"packages\":{\"\":{\"name\":\"" + name + "\",\"version\":\"1.0.0\"}}}");
    }

    private String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(GIT.toString());
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), result);
            return result;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private static OperationResult<Void> success() {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    private long files(PlatformRuntime runtime) throws Exception {
        return scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL");
    }

    private void run(PlatformRuntime runtime, TaskWork<Void> work) throws Exception {
        var observed = new AtomicReference<Throwable>();
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(new TaskRequest("source-preparation-migration", "Source preparation", List.of(), true), task -> {
            try {
                return work.run(task);
            } catch (Exception | AssertionError failure) {
                observed.set(failure);
                throw failure;
            }
        });
        try {
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS).outcome());
        } catch (Exception | AssertionError failure) {
            if (observed.get() != null)
                throw new AssertionError("Original source callback failed: " + handle.snapshot(), observed.get());
            throw failure;
        }
        assertEquals(0, files(runtime));
    }
}
