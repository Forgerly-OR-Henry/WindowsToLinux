package gold.debug.windowstolinux.app.windows.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsRestoreWorkspaceTest {
    @TempDir
    Path temporary;

    @Test
    void createsAndDiscardsOnlyOneDigestBoundAttempt() throws Exception {
        WindowsRestoreWorkspace workspace = new WindowsRestoreWorkspace(temporary.resolve("work"));
        WindowsRestoreAttempt attempt = workspace.createAttempt("sample", "a".repeat(64));
        Files.createDirectories(attempt.candidateRoot());
        Files.writeString(attempt.candidateRoot().resolve("member"), "content");

        assertTrue(attempt.candidateRoot().startsWith(temporary.toAbsolutePath()));
        workspace.discardAttempt(attempt);
        assertFalse(Files.exists(attempt.parent()));
    }

    @Test
    void rejectsUnboundIdentityAndForeignCleanup() throws Exception {
        WindowsRestoreWorkspace workspace = new WindowsRestoreWorkspace(temporary.resolve("work"));
        assertThrows(WindowsWorkspaceException.class, () -> workspace.createAttempt("Sample", "a".repeat(64)));
        Path foreignParent = Files.createDirectory(temporary.resolve("foreign"));
        WindowsRestoreAttempt foreign = new WindowsRestoreAttempt(foreignParent,
                foreignParent.resolve("sample-aaaaaaaaaaaaaaaa"), "sample-aaaaaaaaaaaaaaaa");
        assertThrows(WindowsWorkspaceException.class, () -> workspace.discardAttempt(foreign));
        assertTrue(Files.exists(foreignParent));
    }

    @Test
    void preservesAnAttemptParentThatWasExternallyReplaced() throws Exception {
        WindowsRestoreWorkspace workspace = new WindowsRestoreWorkspace(temporary.resolve("work"));
        WindowsRestoreAttempt attempt = workspace.createAttempt("sample", "b".repeat(64));
        Files.delete(attempt.parent());
        Files.writeString(attempt.parent(), "external replacement");

        assertThrows(WindowsWorkspaceException.class, () -> workspace.discardAttempt(attempt));

        assertTrue(Files.isRegularFile(attempt.parent()));
        assertEquals("external replacement", Files.readString(attempt.parent()));
    }
}
