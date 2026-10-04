package gold.debug.windowstolinux.app.windows.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsFilePublishTest {
    @TempDir
    Path root;

    @Test
    void nativePublicationNeverReplacesATargetCreatedAfterAnAbsenceObservation() throws Exception {
        Path staged = root.resolve("staged"), target = root.resolve("target");
        Files.writeString(staged, "new content");
        assertFalse(Files.exists(target));
        Files.writeString(target, "other process", StandardOpenOption.CREATE_NEW);
        assertThrows(FileAlreadyExistsException.class, () -> WindowsFilePublish.publish(staged, target, false));
        assertEquals("other process", Files.readString(target));
        assertEquals("new content", Files.readString(staged));
        WindowsFilePublish.publish(staged, target, true);
        assertEquals("new content", Files.readString(target));
        assertFalse(Files.exists(staged));
    }

    @Test
    void junctionPublicationKeepsBothOriginalLinksWhenTheDestinationIsOccupied() throws Exception {
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path staged = root.resolve("staged"), target = root.resolve("target");
        WindowsLinks.create(staged, left);
        try {
            assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
            WindowsLinks.create(target, right);
            try {
                assertThrows(java.io.IOException.class, () -> WindowsFilePublish.publishLink(staged, target, false));
                assertEquals(left.toString(), WindowsLinks.target(staged).orElseThrow());
                assertEquals(right.toString(), WindowsLinks.target(target).orElseThrow());
            } finally {
                Files.delete(target);
            }
            WindowsFilePublish.publishLink(staged, target, false);
            assertEquals(left.toString(), WindowsLinks.target(target).orElseThrow());
        } finally {
            Files.deleteIfExists(staged);
            Files.deleteIfExists(target);
        }
        assertTrue(Files.isDirectory(left));
        assertTrue(Files.isDirectory(right));
    }
}
