package gold.debug.windowstolinux.mod.files.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.mod.files.service.FilesRuntimeFixture;
import gold.debug.windowstolinux.app.windows.file.WindowsLinks;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.files.file.model.FilesClipboard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesRemoteIntegrationTest {
    @TempDir
    Path root;

    @Test
    void realSavedServerBrowseCreateRenameDeleteAndBothWayFileTransfersUseActualSftp() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, true)) {
            var servers = fixture.result(fixture.files().servers()).value().orElseThrow();
            assertEquals(List.of(fixture.server()), servers.stream().map(server -> server.ref()).toList());
            var remote = FilePath.remote(fixture.server(), "/");
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().create(remote, "wire-dir", true)).outcome());
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().create(remote, "wire-file.txt", false)).outcome());
            assertEquals(0, Files.size(fixture.remoteFiles().resolve("wire-file.txt")));
            var selected = remoteEntry(fixture, "/", "wire-file.txt");
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().rename(selected, "renamed-wire.txt")).outcome());
            assertFalse(Files.exists(fixture.remoteFiles().resolve("wire-file.txt")));
            assertTrue(Files.exists(fixture.remoteFiles().resolve("renamed-wire.txt")));
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().delete(List.of(remoteEntry(fixture, "/", "renamed-wire.txt")))).outcome());
            assertFalse(Files.exists(fixture.remoteFiles().resolve("renamed-wire.txt")));
            byte[] bytes = new byte[100000];
            new java.util.Random(47).nextBytes(bytes);
            Files.write(fixture.local().resolve("upload.bin"), bytes);
            var upload = FilesLocalIntegrationTest.entry(fixture, fixture.local(), "upload.bin");
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.COPY, List.of(upload)), remote.child("wire-dir"), List.of())).outcome());
            assertArrayEquals(bytes, Files.readAllBytes(fixture.remoteFiles().resolve("wire-dir/upload.bin")));
            assertArrayEquals(bytes, Files.readAllBytes(fixture.local().resolve("upload.bin")));
            Path download = Files.createDirectory(fixture.local().resolve("download"));
            var wire = remoteEntry(fixture, "/wire-dir", "upload.bin");
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(wire)), FilePath.local(download), List.of())).outcome());
            assertArrayEquals(bytes, Files.readAllBytes(download.resolve("upload.bin")));
            assertFalse(Files.exists(fixture.remoteFiles().resolve("wire-dir/upload.bin")));
            var back = FilesLocalIntegrationTest.entry(fixture, download, "upload.bin");
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(back)), remote.child("wire-dir"), List.of())).outcome());
            assertFalse(Files.exists(download.resolve("upload.bin")));
            assertArrayEquals(bytes, Files.readAllBytes(fixture.remoteFiles().resolve("wire-dir/upload.bin")));
            fixture.noResources();
        }
    }

    @Test
    void sameServerActualLinkCopyMoveRenameDeletePreserveTargetThroughExplicitWindowsLinuxWireSeam() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, true)) {
            fixture.linuxLinks();
            Path target = Files.createDirectory(fixture.remoteFiles().resolve("target"));
            Files.writeString(target.resolve("keep.txt"), "wire target survives");
            Files.createDirectory(fixture.remoteFiles().resolve("left"));
            Files.createDirectory(fixture.remoteFiles().resolve("right"));
            WindowsLinks.create(fixture.remoteFiles().resolve("left/link"), target);
            var link = remoteEntry(fixture, "/left", "link");
            assertEquals(FileInfo.Kind.LINK, link.kind());
            var right = FilePath.remote(fixture.server(), "/right");
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.COPY, List.of(link)), right, List.of())).outcome());
            assertTrue(Files.exists(fixture.remoteFiles().resolve("left/link"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("wire target survives", Files.readString(fixture.remoteFiles().resolve("right/link/keep.txt")));
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().rename(remoteEntry(fixture, "/right", "link"), "renamed")).outcome());
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(remoteEntry(fixture, "/right", "renamed"))),
                FilePath.remote(fixture.server(), "/left"), List.of())).outcome());
            assertFalse(Files.exists(fixture.remoteFiles().resolve("right/renamed"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("wire target survives", Files.readString(fixture.remoteFiles().resolve("left/renamed/keep.txt")));
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().delete(List.of(remoteEntry(fixture, "/left", "renamed")))).outcome());
            assertFalse(Files.exists(fixture.remoteFiles().resolve("left/renamed"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("wire target survives", Files.readString(target.resolve("keep.txt")));
            fixture.noResources();
        }
    }

    @Test
    void directCrossHostLinkAndTwoDifferentServersAreRejectedWithoutFileEffects() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, true)) {
            Path target = Files.createDirectory(fixture.local().resolve("target"));
            Files.writeString(target.resolve("keep"), "keep local target");
            WindowsLinks.create(fixture.local().resolve("link"), target);
            var link = FilesLocalIntegrationTest.entry(fixture, fixture.local(), "link");
            var remote = FilePath.remote(fixture.server(), "/");
            var refused = fixture.result(fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(link)), remote, List.of()));
            assertEquals(TaskResult.FAILED, refused.outcome());
            assertEquals("FILES_LINK_CROSS_HOST", refused.problem().orElseThrow().eventCode());
            assertTrue(refused.value().orElseThrow().reports().isEmpty());
            assertTrue(refused.value().orElseThrow().operations().isEmpty());
            assertTrue(Files.exists(fixture.local().resolve("link"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("keep local target", Files.readString(target.resolve("keep")));
            assertFalse(Files.exists(fixture.remoteFiles().resolve("link"), LinkOption.NOFOLLOW_LINKS));
            Files.writeString(fixture.remoteFiles().resolve("remote-source"), "wire content");
            var source = remoteEntry(fixture, "/", "remote-source");
            assertEquals(ProblemCode.INCOMPATIBLE, assertThrows(ApiException.class, () -> fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.COPY, List.of(source)), FilePath.remote(new ServerId("other-server"), "/"), List.of())).problem().code());
            assertEquals("wire content", Files.readString(fixture.remoteFiles().resolve("remote-source")));
            fixture.noResources();
        }
    }

    @Test
    void actualRemoteApprovalCancellationLeavesLocalSourceAndRemoteDirectoryUntouched() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, true)) {
            Files.writeString(fixture.local().resolve("denied.txt"), "source retained");
            var source = FilesLocalIntegrationTest.entry(fixture, fixture.local(), "denied.txt");
            var denied = new AtomicInteger();
            fixture.approval((intent, task) -> {
                if (intent.type().equals("file-move-file")) {
                    denied.incrementAndGet();
                    return false;
                }
                return true;
            });
            var result = fixture.result(fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(source)),
                FilePath.remote(fixture.server(), "/"), List.of()));
            assertEquals(TaskResult.CANCELLED, result.outcome());
            assertEquals(1, denied.get());
            assertEquals("source retained", Files.readString(fixture.local().resolve("denied.txt")));
            assertFalse(Files.exists(fixture.remoteFiles().resolve("denied.txt")));
            assertTrue(result.value().orElseThrow().reports().isEmpty());
            fixture.noResources();
        }
    }

    @Test
    void crossHostDirectoryInternalLinkIsActuallyMappedInsideRemoteCopy() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, true)) {
            fixture.linuxLinks();
            Path tree = Files.createDirectory(fixture.local().resolve("tree"));
            Path nested = Files.createDirectory(tree.resolve("nested"));
            Files.writeString(nested.resolve("value.txt"), "actual internal target");
            WindowsLinks.create(tree.resolve("inside-link"), nested);
            Files.createDirectory(fixture.remoteFiles().resolve("destination"));
            var source = FilesLocalIntegrationTest.entry(fixture, fixture.local(), "tree");
            var result = fixture.result(fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.COPY, List.of(source)),
                FilePath.remote(fixture.server(), "/destination"), List.of()));
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertEquals("actual internal target", Files.readString(fixture.remoteFiles().resolve("destination/tree/nested/value.txt")));
            assertEquals("actual internal target", Files.readString(fixture.remoteFiles().resolve("destination/tree/inside-link/value.txt")));
            assertEquals(FileInfo.Kind.LINK, remoteEntry(fixture, "/destination/tree", "inside-link").kind());
            assertEquals("/destination/tree/nested", remoteEntry(fixture, "/destination/tree", "inside-link").target().orElseThrow());
            assertEquals("actual internal target", Files.readString(tree.resolve("nested/value.txt")));
            fixture.noResources();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void crossHostDirectoryExternalOrBrokenLinkIsRejectedBeforeTargetCreationAndSourceDeletion(boolean broken) throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, true)) {
            fixture.linuxLinks();
            Path tree = Files.createDirectory(fixture.local().resolve("tree"));
            Files.writeString(tree.resolve("keep.txt"), "source remains complete");
            Path external = Files.createDirectory(fixture.local().resolve("external"));
            if (!broken) Files.writeString(external.resolve("keep"), "external target survives");
            WindowsLinks.create(tree.resolve("external-link"), external);
            if (broken) Files.delete(external);
            Files.createDirectory(fixture.remoteFiles().resolve("destination"));
            var source = FilesLocalIntegrationTest.entry(fixture, fixture.local(), "tree");
            var result = fixture.result(fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(source)),
                FilePath.remote(fixture.server(), "/destination"), List.of()));
            assertEquals(TaskResult.FAILED, result.outcome());
            assertTrue(result.value().orElseThrow().reports().isEmpty());
            assertTrue(result.value().orElseThrow().operations().isEmpty());
            assertFalse(Files.exists(fixture.remoteFiles().resolve("destination/tree"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("source remains complete", Files.readString(tree.resolve("keep.txt")));
            assertTrue(Files.exists(tree.resolve("external-link"), LinkOption.NOFOLLOW_LINKS));
            if (!broken) assertEquals("external target survives", Files.readString(external.resolve("keep")));
            else assertFalse(Files.exists(external));
            fixture.noResources();
        }
    }

    static FileInfo remoteEntry(FilesRuntimeFixture fixture, String directory, String name) throws Exception {
        return fixture.result(fixture.files().browse(FilePath.remote(fixture.server(), directory))).value().orElseThrow().entries().stream()
            .filter(value -> value.requested().name().equals(name)).findFirst().orElseThrow();
    }
}
