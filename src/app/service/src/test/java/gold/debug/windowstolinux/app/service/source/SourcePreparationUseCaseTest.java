package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.app.windows.workspace.WindowsSourceWorkspace;
import gold.debug.windowstolinux.shared.analyze.core.ManagedSpringBootAnalysisCoordinator;
import gold.debug.windowstolinux.shared.git.reference.GitReference;
import gold.debug.windowstolinux.shared.git.remote.GitRemote;
import gold.debug.windowstolinux.shared.git.snapshot.GitSourceRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Files.writeString(repository.resolve("package-lock.json"), "{}", StandardCharsets.UTF_8);
        git(repository, "add", ".");
        git(repository, "commit", "-m", "fixture");
        String commit = git(repository, "rev-parse", "HEAD").trim();

        GitSourceRequest request = new GitSourceRequest(new GitRemote(repository.toUri()), new GitReference.Commit(commit),
                Set.of(), 64L * 1024 * 1024, true);
        SourcePreparationUseCase useCase = new SourcePreparationUseCase(new ManagedSpringBootAnalysisCoordinator(),
                new WindowsSourceWorkspace(temporaryDirectory.resolve("workspace")));

        ReviewedSourcePreparation prepared = useCase.prepareReviewedGit(request, DeploymentProjectType.NODE_SERVICE);

        assertEquals(commit, prepared.sourceRevision().orElseThrow().commit().orElseThrow());
        assertEquals(repository.toUri(), prepared.sourceRevision().orElseThrow().remote().orElseThrow());
        assertEquals(prepared.archive().orElseThrow().contentSha256(),
                prepared.sourceRevision().orElseThrow().sourceSha256());
        assertTrue(prepared.assessment().runtimeSuggestion().orElseThrow().value(
                gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion.RuntimeInput.NODE_MAJOR_VERSION).isPresent());
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
