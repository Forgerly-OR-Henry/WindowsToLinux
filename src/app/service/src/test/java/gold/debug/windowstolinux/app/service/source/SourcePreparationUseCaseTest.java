package gold.debug.windowstolinux.app.service.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.app.windows.workspace.WindowsSourcePreparer;
import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.git.GitRemote;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.shared.standard.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the common local and pinned-Git reviewed source boundary. / 测试共用的本地和固定 Git 经审阅源码边界。 */
class SourcePreparationUseCaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bindsThePinnedGitCommitRemoteAndArchiveDigestBeforeDeploymentPlanning() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
        git(repository, "init");
        git(repository, "config", "user.email", "test@example.invalid");
        git(repository, "config", "user.name", "Test User");
        Files.writeString(repository.resolve("package.json"), """
                {"name":"git-demo","engines":{"node":"22"},"scripts":{"build":"build","start":"start"}}
                """, StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("package-lock.json"), npmLock("git-demo"), StandardCharsets.UTF_8);
        git(repository, "add", ".");
        git(repository, "commit", "-m", "fixture");
        String commit = git(repository, "rev-parse", "HEAD").trim();

        GitSourceRequest request = new GitSourceRequest(new GitRemote(repository.toUri()),
                new GitReference.Commit(commit), Set.of(), 64L * 1024 * 1024, true);
        SourcePreparationUseCase useCase = new SourcePreparationUseCase(new DeploymentAnalysisCoordinator(),
                new WindowsSourcePreparer(temporaryDirectory.resolve("workspace")));

        ReviewedSourcePreparation prepared = useCase.prepareGit(request, DeploymentProjectType.NODE_SERVICE);

        assertEquals(commit, prepared.sourceRevision().orElseThrow().commit().orElseThrow());
        assertEquals(repository.toUri(), prepared.sourceRevision().orElseThrow().remote().orElseThrow());
        assertEquals(prepared.archive().orElseThrow().contentSha256(),
                prepared.sourceRevision().orElseThrow().sourceSha256());
        assertTrue(prepared.assessment().runtimeSuggestion().orElseThrow().value(
                gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment.RuntimeInputType.NODE_MAJOR_VERSION)
                .isPresent());
    }

    @Test
    void recognitionPreviewNeverCreatesAnArchiveOrSourceRevision() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("preview"));
        Files.writeString(project.resolve("install.sh"), "this must never execute");
        SourcePreparationUseCase useCase = new SourcePreparationUseCase(new DeploymentAnalysisCoordinator(),
                new WindowsSourcePreparer(temporaryDirectory.resolve("workspace")));

        ReviewedSourcePreparation prepared = useCase.prepare(project, DeploymentProjectType.RECOGNITION_PREVIEW);

        assertEquals(gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus.RECOGNITION_PREVIEW,
                prepared.assessment().admission());
        assertTrue(prepared.archive().isEmpty());
        assertTrue(prepared.sourceRevision().isEmpty());
        assertTrue(Files.notExists(temporaryDirectory.resolve("workspace/archives")));
    }

    @Test
    void preparesIndependentArchivesOnlyAfterTheWholeComponentGraphIsAdmitted() throws Exception {
        Path application = Files.createDirectories(temporaryDirectory.resolve("application"));
        node(Files.createDirectories(application.resolve("api")), "api");
        node(Files.createDirectories(application.resolve("web")), "web");
        Path workspace = temporaryDirectory.resolve("workspace");
        SourcePreparationUseCase useCase = new SourcePreparationUseCase(new DeploymentAnalysisCoordinator(),
                new WindowsSourcePreparer(workspace));

        PreparedMultiComponentSource prepared = useCase.prepareMultiComponent(application, "shop",
                List.of(component("api", "api", 18081, Set.of()), component("web", "web", 18082, Set.of("api"))));

        assertEquals(gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus.READY_FOR_PLANNING,
                prepared.assessment().admission());
        assertEquals(List.of("api", "web"), prepared.components().keySet().stream().toList());
        assertTrue(prepared.components().values().stream()
                .allMatch(value -> value.archive().localArchive().startsWith(workspace.toAbsolutePath())
                        && Files.isRegularFile(value.archive().localArchive())
                        && value.archive().contentSha256().equals(value.sourceRevision().sourceSha256())));
        assertEquals(2, prepared.components().values().stream().map(value -> value.archive().localArchive()).distinct()
                .count());

        PreparedMultiComponentSource rejected = useCase.prepareMultiComponent(application, "conflict",
                List.of(component("api", "api", 18081, Set.of()), component("web", "web", 18081, Set.of("api"))));
        assertEquals(gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus.REJECTED,
                rejected.assessment().admission());
        assertTrue(rejected.components().isEmpty());
    }

    private static ComponentAnalysisRequest component(String id, String root, int port, Set<String> dependencies) {
        return new ComponentAnalysisRequest(id, root, DeploymentProjectType.NODE_SERVICE,
                Optional.of(new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(port, 20, 1))),
                List.of(root + "/dist"), Set.of(port), List.of("PORT"), List.of(), List.of(), dependencies, true,
                ComponentIsolationSpecification.managed());
    }

    private static void node(Path directory, String name) throws IOException {
        Files.writeString(directory.resolve("package.json"), """
                {"name":"%s","engines":{"node":"22"},"scripts":{"build":"build","start":"start"}}
                """.formatted(name), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("package-lock.json"), npmLock(name), StandardCharsets.UTF_8);
    }

    private static String npmLock(String name) {
        return """
                {"name":"%s","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"%s","version":"1.0.0"}}}
                """.formatted(name, name);
    }

    private static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IOException("fixture Git command failed: " + new String(output, StandardCharsets.UTF_8));
        }
        return new String(output, StandardCharsets.UTF_8);
    }
}
