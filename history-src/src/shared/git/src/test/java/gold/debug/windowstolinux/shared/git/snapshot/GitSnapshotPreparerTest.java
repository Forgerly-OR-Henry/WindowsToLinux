package gold.debug.windowstolinux.shared.git.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.git.GitRemote;
import gold.debug.windowstolinux.shared.git.GitSnapshot;
import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSnapshotPreparerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pinsABranchToACommitAndCreatesASafeSourceArchive() throws Exception {
        Path repository = createRepository();
        Files.writeString(repository.resolve("service.txt"), "source only", StandardCharsets.UTF_8);
        commit(repository, "add source");

        GitSnapshot snapshot = new GitSnapshotPreparer().prepare(request(repository),
                temporaryDirectory.resolve("workspace"));

        assertTrue(snapshot.commit().matches("[0-9a-f]{40}"));
        assertTrue(Files.isRegularFile(snapshot.archive().archivePath()));
        assertTrue(snapshot.archive().byteCount() > 0);
        assertTrue(snapshot.archive().excludedEntries().contains(".git/"));
        assertEquals(snapshot.commit(), git(repository, "rev-parse", "HEAD").trim());
    }

    @Test
    void usesRemoteDefaultBranchAndKeepsTheResolvedCommitWhenBranchMoves() throws Exception {
        Path repository = createRepository();
        git(repository, "branch", "-M", "production");
        String expected = git(repository, "rev-parse", "HEAD").trim();
        GitSnapshot snapshot = new GitSnapshotPreparer().prepare(new GitSourceRequest(new GitRemote(repository.toUri()),
                new GitReference.DefaultBranch(), Set.of(), 64L * 1024 * 1024, true),
                temporaryDirectory.resolve("workspace"));
        Files.writeString(repository.resolve("after.txt"), "new branch content");
        commit(repository, "move branch");
        assertEquals(expected, snapshot.commit());
        assertTrue(Files.notExists(snapshot.checkoutDirectory().resolve("after.txt")));
    }

    @Test
    void rejectsSubmoduleMetadataBeforeReturningASnapshot() throws Exception {
        Path repository = createRepository();
        Files.writeString(repository.resolve(".gitmodules"),
                "[submodule \"unsafe\"]\npath = unsafe\nurl = https://example.test/unsafe.git\n");
        commit(repository, "add submodule metadata");

        GitSnapshotException exception = assertThrows(GitSnapshotException.class,
                () -> new GitSnapshotPreparer().prepare(request(repository), temporaryDirectory.resolve("workspace")));

        assertEquals(gold.debug.windowstolinux.shared.git.GitSnapshotFailureType.PREPARATION_FAILED,
                exception.failure().definition());
    }

    @Test
    void rejectsGitSymbolicLinkModesEvenWhenTheHostCheckoutMaterializesARegularFile() throws Exception {
        Path repository = createRepository();
        String targetBlob = git(repository, "rev-parse", "HEAD:README.md").trim();
        git(repository, "update-index", "--add", "--cacheinfo", "120000," + targetBlob + ",linked.txt");
        git(repository, "commit", "-m", "add symbolic link entry");

        GitSnapshotException exception = assertThrows(GitSnapshotException.class,
                () -> new GitSnapshotPreparer().prepare(request(repository), temporaryDirectory.resolve("workspace")));

        assertEquals(gold.debug.windowstolinux.shared.git.GitSnapshotFailureType.PREPARATION_FAILED,
                exception.failure().definition());
    }

    @Test
    void rejectsCredentialBearingRemoteUris() {
        assertThrows(IllegalArgumentException.class,
                () -> GitRemote.parse("https://token@example.test/repository.git"));
    }

    @Test
    void classifiesMissingGitAndBoundedCommandTimeout() throws Exception {
        GitCommandExecutor missing = new GitCommandExecutor(Duration.ofSeconds(1));
        GitSnapshotException unavailable = assertThrows(GitSnapshotException.class,
                () -> missing.run(temporaryDirectory, List.of("windowstolinux-missing-git-fixture")));
        assertEquals(gold.debug.windowstolinux.shared.git.GitSnapshotFailureType.TOOL_UNAVAILABLE,
                unavailable.failure().definition());

        GitCommandExecutor bounded = new GitCommandExecutor(Duration.ofMillis(25));
        List<String> slow = System.getProperty("os.name", "").startsWith("Windows")
                ? List.of("cmd", "/c", "ping -n 6 127.0.0.1 >nul")
                : List.of("sh", "-c", "sleep 5");
        GitSnapshotException timeout = assertThrows(GitSnapshotException.class,
                () -> bounded.run(temporaryDirectory, slow));
        assertEquals(gold.debug.windowstolinux.shared.git.GitSnapshotFailureType.TIMEOUT,
                timeout.failure().definition());
    }

    @Test
    void fetchesOnlyTheExactRequestedCommit() throws Exception {
        Path repository = createRepository();
        String firstCommit = git(repository, "rev-parse", "HEAD").trim();
        Files.writeString(repository.resolve("later.txt"), "must not enter the pinned snapshot",
                StandardCharsets.UTF_8);
        commit(repository, "later commit");
        GitSourceRequest request = new GitSourceRequest(new GitRemote(repository.toUri()),
                new GitReference.Commit(firstCommit), Set.of(), 10 * 1024 * 1024, true);

        GitSnapshot snapshot = new GitSnapshotPreparer().prepare(request,
                temporaryDirectory.resolve("exact-workspace"));

        assertEquals(firstCommit, snapshot.commit());
        assertTrue(Files.notExists(snapshot.checkoutDirectory().resolve("later.txt")));
        assertEquals("1", git(snapshot.checkoutDirectory(), "rev-list", "--count", "HEAD").trim());
    }

    @Test
    void preservesRepositoryBlobLineEndingsInsteadOfApplyingTheHostGitDefault() throws Exception {
        Path repository = createRepository();
        byte[] wrapper = "#!/bin/sh\nprintf 'wrapper-ok\\n'\n".getBytes(StandardCharsets.UTF_8);
        Files.write(repository.resolve("gradlew"), wrapper);
        commit(repository, "add wrapper");

        GitSnapshot snapshot = new GitSnapshotPreparer().prepare(request(repository),
                temporaryDirectory.resolve("line-ending-workspace"));

        assertEquals("false", git(snapshot.checkoutDirectory(), "config", "core.autocrlf").trim());
        assertTrue(
                java.util.Arrays.equals(wrapper, Files.readAllBytes(snapshot.checkoutDirectory().resolve("gradlew"))));
    }

    private GitSourceRequest request(Path repository) {
        return new GitSourceRequest(new GitRemote(repository.toUri()), new GitReference.Branch("main"), Set.of(),
                10 * 1024 * 1024, true);
    }

    private Path createRepository() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
        git(repository, "init", "--initial-branch=main");
        git(repository, "config", "user.name", "typed deployment Test");
        git(repository, "config", "user.email", "deployment@example.test");
        Files.writeString(repository.resolve("README.md"), "fixture", StandardCharsets.UTF_8);
        commit(repository, "initial");
        return repository;
    }

    private static void commit(Path repository, String message) throws Exception {
        git(repository, "add", "--all");
        git(repository, "commit", "-m", message);
    }

    private static String git(Path directory, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IOException("test Git fixture command failed: " + new String(output, StandardCharsets.UTF_8));
        }
        return new String(output, StandardCharsets.UTF_8);
    }
}
