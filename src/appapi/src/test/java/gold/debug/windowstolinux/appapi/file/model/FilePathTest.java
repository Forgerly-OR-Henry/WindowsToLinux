package gold.debug.windowstolinux.appapi.file.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class FilePathTest {
    private final FilePath local = FilePath.local(Path.of("file-path-test-parent"));

    private final FilePath remote = FilePath.remote(new ServerId("test-server"), "/parent");

    @Test
    void ordinaryNamesRemainDirectLocalChildren() {
        for (String name : List.of("normal.txt", "新 文件.txt", ".hidden")) {
            FilePath child = local.child(name);
            assertEquals(local, child.parent());
            assertEquals(local.server(), child.server());
            assertEquals(name, child.name());
        }
    }

    @Test
    void rootsAllowDirectChildren() {
        FilePath localRoot = FilePath.local(Path.of(local.path()).getRoot());
        assertEquals(localRoot, localRoot.child("child").parent());
        FilePath remoteRoot = FilePath.remote(remote.server().orElseThrow(), "/");
        assertEquals(remoteRoot, remoteRoot.child("child").parent());
    }

    @Test
    void invalidNamesAreRejectedOnBothHosts() {
        for (String name : new String[]{null, "", ".", "..", "a/b", "a\\b", "/absolute", "\u0000"}) {
            assertThrows(IllegalArgumentException.class, () -> local.child(name), String.valueOf(name));
            assertThrows(IllegalArgumentException.class, () -> remote.child(name), String.valueOf(name));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDriveQualifiedNamesCannotEscapeOrReuseTheParent() {
        String ownDrive = Path.of(local.path()).getRoot().toString().substring(0, 2);
        String otherDrive = ownDrive.equalsIgnoreCase("C:") ? "D:" : "C:";
        for (String name : List.of(ownDrive, ownDrive + "escape.txt", otherDrive, otherDrive + "escape.txt"))
            assertThrows(IllegalArgumentException.class, () -> local.child(name), name);
    }

    @Test
    void remoteColonNamesPreserveTheirHostAndParent() {
        for (String name : List.of("a:b", "C:escape.txt", ".hidden", "新 文件.txt")) {
            FilePath child = remote.child(name);
            assertEquals(remote, child.parent());
            assertEquals(remote.server(), child.server());
            assertEquals(name, child.name());
        }
    }

    @Test
    void localFactoryStillResolvesRelativePaths() {
        Path relative = Path.of("relative", "..", "child");
        assertEquals(relative.toAbsolutePath().normalize().toString(), FilePath.local(relative).path());
    }
}
