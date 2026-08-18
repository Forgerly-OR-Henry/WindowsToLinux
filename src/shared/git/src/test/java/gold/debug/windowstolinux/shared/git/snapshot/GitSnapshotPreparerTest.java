package gold.debug.windowstolinux.shared.git.snapshot;

import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.git.GitRemote;
import gold.debug.windowstolinux.shared.git.GitSnapshot;
import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.git.GitRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitSnapshotPreparerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pinsABranchToACommitAndCreatesASafeSourceArchive() throws Exception {
        Path repository = createRepository();
        Files.writeString(repository.resolve("service.txt"), "source only", StandardCharsets.UTF_8);
        commit(repository, "add source");

        GitSnapshot snapshot = new GitSnapshotPreparer().prepare(request(repository), temporaryDirectory.resolve("workspace"));

        assertTrue(snapshot.commit().matches("[0-9a-f]{40}"));
        assertTrue(Files.isRegularFile(snapshot.archive().archivePath()));
        assertTrue(snapshot.archive().byteCount() > 0);
        assertTrue(snapshot.archive().excludedEntries().contains(".git/"));
        assertEquals(snapshot.commit(), git(repository, "rev-parse", "HEAD").trim());
    }

    @Test
    void rejectsSubmoduleMetadataBeforeReturningASnapshot() throws Exception {
        Path repository = createRepository();
        Files.writeString(repository.resolve(".gitmodules"), "[submodule \"unsafe\"]\npath = unsafe\nurl = https://example.test/unsafe.git\n");
        commit(repository, "add submodule metadata");

        GitSnapshotException exception = assertThrows(GitSnapshotException.class,
                () -> new GitSnapshotPreparer().prepare(request(repository), temporaryDirectory.resolve("workspace")));

        assertTrue(exception.getMessage().contains("without executing project code"));
    }

    @Test
    void rejectsGitSymbolicLinkModesEvenWhenTheHostCheckoutMaterializesARegularFile() throws Exception {
        Path repository = createRepository();
        String targetBlob = git(repository, "rev-parse", "HEAD:README.md").trim();
        git(repository, "update-index", "--add", "--cacheinfo", "120000," + targetBlob + ",linked.txt");
        git(repository, "commit", "-m", "add symbolic link entry");

        GitSnapshotException exception = assertThrows(GitSnapshotException.class,
                () -> new GitSnapshotPreparer().prepare(request(repository), temporaryDirectory.resolve("workspace")));

        assertTrue(exception.getMessage().contains("without executing project code"));
    }

    @Test
    void rejectsCredentialBearingRemoteUris() {
        assertThrows(IllegalArgumentException.class, () -> GitRemote.parse("https://token@example.test/repository.git"));
    }

    @Test
    void fetchesOnlyTheExactRequestedCommit() throws Exception {
        Path repository = createRepository();
        String firstCommit = git(repository, "rev-parse", "HEAD").trim();
        Files.writeString(repository.resolve("later.txt"), "must not enter the pinned snapshot", StandardCharsets.UTF_8);
        commit(repository, "later commit");
        GitSourceRequest request = new GitSourceRequest(new GitRemote(repository.toUri()),
                new GitReference.Commit(firstCommit), Set.of(), 10 * 1024 * 1024, true);

        GitSnapshot snapshot = new GitSnapshotPreparer().prepare(request, temporaryDirectory.resolve("exact-workspace"));

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
        assertTrue(java.util.Arrays.equals(wrapper, Files.readAllBytes(snapshot.checkoutDirectory().resolve("gradlew"))));
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
