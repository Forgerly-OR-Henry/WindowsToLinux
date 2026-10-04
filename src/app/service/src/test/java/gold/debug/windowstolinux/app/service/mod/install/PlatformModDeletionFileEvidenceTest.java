package gold.debug.windowstolinux.app.service.mod.install;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModDeletionFileEvidenceTest {
    @TempDir
    Path root;

    @Test
    void originalProgramPrivateDataAndEmergencyFilesRequireActualAbsence() throws Exception {
        var home = Files.createDirectory(root.resolve("home"));
        var data = Files.createDirectory(root.resolve("data"));
        var proof = new PlatformModDeletionFileEvidence(home, data);
        proof.verifyHome(home);
        for (var path : java.util.List.of(home.resolve("mod/example.owner/1.0.0"),
            data.resolve("mod/example.owner"), data.resolve("log/emergency/original.log"),
            data.resolve("log/emergency/original.pending"))) {
            proof.verifyAbsent(path);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "original evidence");
            assertThrows(SQLException.class, () -> proof.verifyAbsent(path));
            assertEquals("original evidence", Files.readString(path));
            Files.delete(path);
            proof.verifyAbsent(path);
        }
    }

    @Test
    void foreignRootsTraversalAndUnregisteredDepthCannotSupplyDeletionProof() throws Exception {
        var home = Files.createDirectory(root.resolve("home"));
        var data = Files.createDirectory(root.resolve("data"));
        var proof = new PlatformModDeletionFileEvidence(home, data);
        var outside = Files.writeString(root.resolve("keep.bin"), "independent");
        assertThrows(SQLException.class, () -> proof.verifyHome(data));
        for (var path : java.util.List.of(outside, home.resolve("mod"),
            home.resolve("mod/example.owner/../../../keep.bin"),
            data.resolve("mod/example.owner/unreviewed"), data.resolve("log/emergency/unreviewed.bin")))
            assertThrows(SQLException.class, () -> proof.verifyAbsent(path));
        assertEquals("independent", Files.readString(outside));
    }

    @Test
    void redirectedAncestorsCannotProveMissingChildrenOrAuthorizeOutsideFiles() throws Exception {
        var home = Files.createDirectory(root.resolve("home"));
        var data = Files.createDirectory(root.resolve("data"));
        var outside = Files.createDirectory(root.resolve("outside"));
        var retained = Files.writeString(outside.resolve("keep.bin"), "independent");
        var programs = Files.createDirectory(home.resolve("mod"));
        var junction = programs.resolve("example.owner");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), outside.toString())
            .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
            var proof = new PlatformModDeletionFileEvidence(home, data);
            assertThrows(SQLException.class, () -> proof.verifyAbsent(junction.resolve("missing")));
            var redirectedHome = new PlatformModDeletionFileEvidence(junction, data);
            assertThrows(SQLException.class, () -> redirectedHome.verifyHome(junction));
            assertEquals("independent", Files.readString(retained));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
            Files.deleteIfExists(junction);
        }
    }
}
