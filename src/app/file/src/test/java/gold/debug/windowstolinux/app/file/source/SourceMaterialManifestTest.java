package gold.debug.windowstolinux.app.file.source;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceMaterialManifestTest {
    @TempDir
    Path root;

    SourceWorkDirectory work() throws Exception {
        var work = new SourceWorkDirectory(root.resolve("work"), UUID.randomUUID(), UUID.randomUUID(), "example.producer");
        work.prepare();
        return work;
    }

    @Test
    void persistentCanonicalFramingMatchesSnapshotAndSurvivesPhysicalRecovery() throws Exception {
        UUID task = UUID.randomUUID(), id = UUID.randomUUID();
        var work = new SourceWorkDirectory(root.resolve("work"), task, id, "example.producer");
        work.prepare();
        Files.createDirectory(work.directory().resolve("src"));
        Files.writeString(work.directory().resolve("src/中文.txt"), "original source");
        Files.writeString(work.directory().resolve("a.txt"), "other");
        var original = SourceMaterialManifest.capture(work, List.of("src/中文.txt", "a.txt"), () -> false);
        assertEquals(List.of("a.txt", "src/中文.txt"), new ArrayList<>(original.entries().keySet()));
        assertEquals(SourceMaterialManifest.digest(original.entries()), original.sha256());
        assertTrue(Files.exists(work.metadata("manifest.bin")));
        assertFalse(Files.exists(work.directory().resolve("manifest.bin")));
        var recovered = new SourceWorkDirectory(root.resolve("work"), task, id, "example.producer");
        recovered.recover();
        assertEquals(work.identity(), recovered.identity());
        assertEquals(original, SourceMaterialManifest.read(recovered, original.sha256(), original.byteCount(), 2));
        SourceMaterialManifest.verify(recovered, original, () -> false);
        recovered.close();
    }

    @Test
    void rawTreeAboveTwoGiBRemainsAdmissibleWhileFilteredSnapshotRetainsOriginalBudget() throws Exception {
        try (var work = work()) {
            Files.writeString(work.directory().resolve("source.txt"), "small source");
            long huge = FrozenSourceWork.MAX_BYTES + 1;
            try (var channel = FileChannel.open(work.directory().resolve("excluded.bin"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
                channel.position(huge - 1);
                channel.write(ByteBuffer.wrap(new byte[]{1}));
            }
            assertEquals(huge, Files.size(work.directory().resolve("excluded.bin")));
            var manifest = SourceMaterialManifest.capture(work, List.of("source.txt", "excluded.bin"), () -> false);
            assertTrue(manifest.byteCount() > FrozenSourceWork.MAX_BYTES);
            assertThrows(IllegalArgumentException.class, () -> FrozenSourceWork.validate(
                new gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest(List.of("excluded.bin"), huge)));
            try (var snapshot = new FrozenSourceWork(root.resolve("filtered"), UUID.randomUUID(), UUID.randomUUID(), "example.consumer")) {
                snapshot.prepare();
                snapshot.capture(new SelectedDirectory(work.directory()),
                    new gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest(List.of("source.txt"), 100), () -> false);
                assertEquals("small source", Files.readString(snapshot.directory().resolve("source.txt")));
                assertFalse(Files.exists(snapshot.directory().resolve("excluded.bin")));
            }
        }
    }

    @Test
    void duplicatePortableAliasesAndCancellationCannotSealAFalseSuccess() throws Exception {
        try (var work = work()) {
            Files.writeString(work.directory().resolve("file.txt"), "original");
            assertThrows(IOException.class, () -> SourceMaterialManifest.capture(work, List.of("file.txt", "file.txt"), () -> false));
            assertThrows(IllegalArgumentException.class, () -> SourceMaterialManifest.capture(work, List.of("../file.txt"), () -> false));
            assertThrows(java.util.concurrent.CancellationException.class,
                () -> SourceMaterialManifest.capture(work, List.of("file.txt"), () -> true));
            assertFalse(Files.exists(work.metadata("manifest.bin")));
        }
    }

    @Test
    void changedFileAndDamagedPersistentDigestNeverAuthorizeOriginalReads() throws Exception {
        try (var work = work()) {
            Path file = work.directory().resolve("file.txt");
            Files.writeString(file, "original");
            var original = SourceMaterialManifest.capture(work, List.of("file.txt"), () -> false);
            Files.setAttribute(file, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
            Files.writeString(file, "changed!");
            assertThrows(IOException.class, () -> SourceMaterialManifest.verify(work, original, () -> false));
            Files.writeString(file, "original");
            assertThrows(IOException.class, () -> SourceMaterialManifest.read(work, "0".repeat(64), original.byteCount(), 1));
            assertThrows(IOException.class, () -> SourceMaterialManifest.read(work, original.sha256(), original.byteCount() + 1, 1));
            assertThrows(IOException.class, () -> SourceMaterialManifest.read(work, original.sha256(), original.byteCount(), 0));
            Files.write(work.metadata("manifest.bin"), new byte[]{1}, StandardOpenOption.APPEND);
            assertThrows(IOException.class, () -> SourceMaterialManifest.read(work, original.sha256(), original.byteCount(), 1));
        }
    }

    @Test
    void physicalHardlinkDuplicatesAndOversizedManifestAreRejectedWithoutFollowingAliases() throws Exception {
        try (var work = work()) {
            Path file = work.directory().resolve("file.txt");
            Files.writeString(file, "original");
            Files.createLink(work.directory().resolve("duplicate.txt"), file);
            assertThrows(IOException.class, () -> SourceMaterialManifest.capture(work, List.of("file.txt", "duplicate.txt"), () -> false));
            assertFalse(Files.exists(work.metadata("manifest.bin")));
            assertThrows(IOException.class, () -> SourceMaterialManifest.validate(Collections.nCopies(SourceMaterialManifest.MAX_FILES + 1, "file.txt")));
            try (var channel = FileChannel.open(work.metadata("manifest.bin"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
                channel.position(SourceMaterialManifest.MAX_MANIFEST_BYTES);
                channel.write(ByteBuffer.wrap(new byte[]{1}));
            }
            assertThrows(IOException.class, () -> SourceMaterialManifest.read(work, "0".repeat(64), 0, 0));
        }
    }
}
