package gold.debug.windowstolinux.app.windows.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsBackupArchiveWorkspaceTest {
    @TempDir
    Path temporary;

    @Test
    void publishesOnlyItsSameDirectoryTemporaryWithoutReplacingExistingDestination() throws Exception {
        WindowsBackupArchiveWorkspace workspace = new WindowsBackupArchiveWorkspace();
        Path destination = temporary.resolve("sample.wtl-backup.zip");
        WindowsBackupArchiveAttempt attempt = workspace.createAttempt(destination, 1L);
        Files.writeString(attempt.temporary(), "complete-backup", StandardCharsets.UTF_8);

        Path published = workspace.publish(attempt);

        assertEquals(destination.toAbsolutePath(), published);
        assertEquals("complete-backup", Files.readString(destination, StandardCharsets.UTF_8));
        assertFalse(Files.exists(attempt.temporary()));
        assertThrows(WindowsWorkspaceException.class, () -> workspace.createAttempt(destination, 1L));
        assertEquals("complete-backup", Files.readString(destination, StandardCharsets.UTF_8));
    }

    @Test
    void cleanupIsLimitedToTheExactAttemptAndPreservesAnExternallyReplacedPublication() throws Exception {
        WindowsBackupArchiveWorkspace workspace = new WindowsBackupArchiveWorkspace();
        WindowsBackupArchiveAttempt abandoned = workspace.createAttempt(temporary.resolve("abandoned.zip"), 1L);
        Files.writeString(abandoned.temporary(), "partial", StandardCharsets.UTF_8);
        workspace.discardTemporary(abandoned);
        assertFalse(Files.exists(abandoned.temporary()));
        assertFalse(Files.exists(abandoned.destination()));

        WindowsBackupArchiveAttempt exact = workspace.createAttempt(temporary.resolve("exact.zip"), 1L);
        byte[] exactContent = "verified".getBytes(StandardCharsets.UTF_8);
        Files.write(exact.temporary(), exactContent);
        workspace.publish(exact);
        workspace.discardPublished(exact, digest(exactContent));
        assertFalse(Files.exists(exact.destination()));

        WindowsBackupArchiveAttempt replaced = workspace.createAttempt(temporary.resolve("replaced.zip"), 1L);
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(replaced.temporary(), original);
        workspace.publish(replaced);
        Files.delete(replaced.destination());
        Files.writeString(replaced.destination(), "external", StandardCharsets.UTF_8);

        assertThrows(WindowsWorkspaceException.class, () -> workspace.discardPublished(replaced, digest(original)));
        assertTrue(Files.exists(replaced.destination()));
        assertEquals("external", Files.readString(replaced.destination(), StandardCharsets.UTF_8));
    }

    private static String digest(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
