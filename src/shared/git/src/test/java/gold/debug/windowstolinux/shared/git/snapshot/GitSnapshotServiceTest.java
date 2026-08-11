package gold.debug.windowstolinux.shared.git.snapshot;

import gold.debug.windowstolinux.shared.git.reference.GitReference;
import gold.debug.windowstolinux.shared.git.remote.GitRemote;
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

class GitSnapshotServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pinsABranchToACommitAndCreatesASafeSourceArchive() throws Exception {
        Path repository = createRepository();
        Files.writeString(repository.resolve("service.txt"), "source only", StandardCharsets.UTF_8);
        commit(repository, "add source");

        GitSnapshot snapshot = new GitSnapshotService().prepare(request(repository), temporaryDirectory.resolve("workspace"));

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
                () -> new GitSnapshotService().prepare(request(repository), temporaryDirectory.resolve("workspace")));

        assertTrue(exception.getMessage().contains("without executing project code"));
    }

    @Test
    void rejectsCredentialBearingRemoteUris() {
        assertThrows(IllegalArgumentException.class, () -> GitRemote.parse("https://token@example.test/repository.git"));
    }

    private GitSourceRequest request(Path repository) {
        return new GitSourceRequest(new GitRemote(repository.toUri()), new GitReference.Branch("main"), Set.of(),
                10 * 1024 * 1024, true);
    }

    private Path createRepository() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
        git(repository, "init", "--initial-branch=main");
        git(repository, "config", "user.name", "Phase Two Test");
        git(repository, "config", "user.email", "phase2@example.test");
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
