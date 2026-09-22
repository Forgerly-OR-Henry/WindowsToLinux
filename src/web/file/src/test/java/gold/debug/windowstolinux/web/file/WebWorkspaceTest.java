package gold.debug.windowstolinux.web.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

import gold.debug.windowstolinux.web.file.quota.UploadQuota;
import gold.debug.windowstolinux.web.file.upload.SourceArchiveUpload;
import gold.debug.windowstolinux.web.file.workspace.*;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebWorkspaceTest {
    @TempDir
    Path root;
    @Test
    void sourceOwnershipFinalizationAndDuplicateWritesAreEnforced() throws Exception {
        var workspace = workspace();
        var address = address();
        workspace.create(address);
        workspace.upload(address, "nested/index.html", stream("hello"));
        assertThrows(IOException.class, () -> workspace.upload(address, "nested/index.html", stream("overwrite")));
        assertEquals("hello", Files.readString(workspace.source(address).resolve("nested/index.html")));
        workspace.complete(address);
        assertThrows(IOException.class, () -> workspace.upload(address, "extra", stream("late")));
        assertThrows(IOException.class, () -> workspace.source(new WorkspaceAddress("other", address.resourceId())));
        workspace.discard(address);
        assertFalse(Files.exists(root.resolve("files/internal/" + address.resourceId())));
    }

    @Test
    void traversalsWindowsDevicesAndAbsolutePathsAreRejected() throws Exception {
        var workspace = workspace();
        var address = address();
        workspace.create(address);
        for (String name : new String[]{"../escape", "/root", "C:/evil", "a\\evil", "a/../../evil", "NUL", "a/CON.txt",
                "a.", "a//b", "a/./b", "a:stream"})
            assertThrows(IOException.class, () -> workspace.upload(address, name, stream("bad")), name);
    }

    @Test
    void streamQuotasLeaveNoPartialFile() throws Exception {
        var workspace = workspace();
        var address = address();
        workspace.create(address);
        assertThrows(IOException.class, () -> workspace.upload(address, "large", stream("x".repeat(101))));
        assertFalse(Files.exists(workspace.source(address).resolve("large")));
        workspace.upload(address, "valid", stream("ok"));
    }

    @Test
    void archiveExtractionRejectsTraversalSymlinksDuplicatesAndBombs() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            var workspace = workspace();
            var address = address();
            workspace.create(address);
            byte[] archive = zip(variant);
            assertThrows(IOException.class, () -> new SourceArchiveUpload(workspace).extract(address, "zip",
                    new ByteArrayInputStream(archive)));
            assertFalse(Files.exists(root.resolve("escape")));
            assertFalse(Files.exists(workspace.directory(address).resolve("upload.archive")));
            workspace.discard(address);
        }
    }

    @Test
    void boundedZipIsExtractedWithoutExecutingMembers() throws Exception {
        var workspace = workspace();
        var address = address();
        workspace.create(address);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(bytes)) {
            zip.putArchiveEntry(new ZipArchiveEntry("run.sh"));
            zip.write("exit 99".getBytes());
            zip.closeArchiveEntry();
        }
        new SourceArchiveUpload(workspace).extract(address, "zip", new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("exit 99", Files.readString(workspace.source(address).resolve("run.sh")));
    }

    @Test
    void ownedCleanupDiscoveryPreservesUnmarkedDirectoriesAndChecksCapacity() throws Exception {
        var workspace = workspace();
        var address = address();
        workspace.create(address);
        Path foreign = workspace.directory(address).getParent().resolve(UUID.randomUUID().toString());
        Files.createDirectory(foreign);
        assertEquals(java.util.List.of(address), workspace.owned("internal"));
        assertThrows(IOException.class, () -> workspace.checkCapacity(address, 5001));
        workspace.discard(address);
        assertTrue(Files.isDirectory(foreign));
        assertTrue(workspace.owned("internal").isEmpty());
    }

    private byte[] zip(int kind) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(bytes)) {
            var entry = new ZipArchiveEntry(kind == 0 ? "../escape" : "entry");
            if (kind == 1)
                entry.setUnixMode(0120777);
            zip.putArchiveEntry(entry);
            zip.write("x".repeat(kind == 3 ? 101 : 5).getBytes());
            zip.closeArchiveEntry();
            if (kind == 2) {
                zip.putArchiveEntry(new ZipArchiveEntry("entry"));
                zip.write("again".getBytes());
                zip.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private WebWorkspace workspace() throws Exception {
        return new WebWorkspace(root.resolve("files"), new UploadQuota(100, 5000, 50000, 10, 100), 64L << 20);
    }

    private static WorkspaceAddress address() {
        return new WorkspaceAddress("internal", UUID.randomUUID().toString());
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
