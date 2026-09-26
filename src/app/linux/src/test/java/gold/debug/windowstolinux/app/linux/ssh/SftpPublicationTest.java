package gold.debug.windowstolinux.app.linux.ssh;

import static gold.debug.windowstolinux.app.linux.ssh.SftpTransferTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpPublicationTest {
    @TempDir
    Path root;

    @Test
    void overwriteKeepsOldTargetsThroughoutTransferThenPublishesVerifiedContentOnThreeAndSix() throws Exception {
        for (int version : new int[]{3, 6}) {
            Path directory = Files.createDirectory(root.resolve("v" + version));
            Path oldRemote = directory.resolve("remote/file.bin"), oldLocal = directory.resolve("received.bin");
            byte[] bytes = new byte[90000];
            java.util.Arrays.fill(bytes, (byte) 9);
            var writes = new AtomicInteger();
            var downloading = new AtomicBoolean();
            try (var fixture = new Fixture(directory, version, new SftpEventListener() {
                @Override
                public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                        int start, int length, Throwable failure) throws java.io.IOException {
                    if (failure == null) {
                        assertEquals("old remote", Files.readString(oldRemote));
                        writes.incrementAndGet();
                    }
                }

                @Override
                public void read(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                        int start, int length, int read, Throwable failure) throws java.io.IOException {
                    if (downloading.get() && failure == null && read > 0)
                        assertEquals("old local", Files.readString(oldLocal));
                }
            });
                    var connector = new SshConnector();
                    var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                Files.writeString(oldRemote, "old remote");
                Files.writeString(oldLocal, "old local");
                Path source = Files.write(directory.resolve("input"), bytes);
                var asks = new AtomicInteger();
                TransferConflictResolver overwrite = conflict -> {
                    asks.incrementAndGet();
                    assertEquals(bytes.length, conflict.incomingBytes());
                    return TransferConflictChoice.OVERWRITE;
                };
                var uploaded = connection.transfer(new TransferRequest(source, "/file.bin", bytes.length),
                        digest(bytes), true, Duration.ofSeconds(5), () -> false, overwrite);
                assertEquals(Outcome.SUCCEEDED, uploaded.outcome(), uploaded.toString());
                assertTrue(writes.get() > 1);
                assertArrayEquals(bytes, Files.readAllBytes(oldRemote));
                downloading.set(true);
                var downloaded = connection.transfer(new TransferRequest(oldLocal, "/file.bin", bytes.length),
                        digest(bytes), false, Duration.ofSeconds(5), () -> false, overwrite);
                assertEquals(Outcome.SUCCEEDED, downloaded.outcome(), downloaded.toString());
                assertArrayEquals(bytes, Files.readAllBytes(oldLocal));
                assertEquals(2, asks.get());
                assertEquals(oldLocal, downloaded.value().orElseThrow().localPath());
                assertEquals("/file.bin", uploaded.value().orElseThrow().remoteAbsolutePath());
                try (var entries = Files.walk(directory)) {
                    assertEquals(0,
                            entries.filter(path -> path.getFileName().toString().startsWith(".wtl-transfer-")).count());
                }
            }
        }
    }

    @Test
    void keepBothSkipsOccupiedNamesAndReturnsActualEndpointsWhileTerminateCreatesNothing() throws Exception {
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {
        });
                var connector = new SshConnector();
                var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            var source = Files.writeString(root.resolve("input"), "new");
            var target = root.resolve("file.txt");
            Files.writeString(fixture.remote.resolve("file.txt"), "old");
            Files.createDirectory(fixture.remote.resolve("file (1).txt"));
            var result = connection.transfer(new TransferRequest(source, "/file.txt", 3), digest("new".getBytes()),
                    true, Duration.ofSeconds(5), () -> false, conflict -> TransferConflictChoice.KEEP_BOTH);
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            assertEquals("/file (2).txt", result.value().orElseThrow().remoteAbsolutePath());
            assertEquals("old", Files.readString(fixture.remote.resolve("file.txt")));
            assertEquals("new", Files.readString(fixture.remote.resolve("file (2).txt")));
            Files.writeString(target, "preserved");
            Files.createDirectory(root.resolve("file (1).txt"));
            var received = connection.transfer(new TransferRequest(target, "/file (2).txt", 3),
                    digest("new".getBytes()), false, Duration.ofSeconds(5), () -> false,
                    conflict -> TransferConflictChoice.KEEP_BOTH);
            assertEquals(Outcome.SUCCEEDED, received.outcome(), received.toString());
            assertEquals(root.resolve("file (2).txt"), received.value().orElseThrow().localPath());
            assertEquals("preserved", Files.readString(target));
            var stopped = connection.transfer(new TransferRequest(target, "/file (2).txt", 3), digest("new".getBytes()),
                    false, Duration.ofSeconds(5), () -> false, conflict -> TransferConflictChoice.TERMINATE);
            assertEquals(Outcome.CANCELLED, stopped.outcome());
            try (var entries = Files.walk(root)) {
                assertEquals(0,
                        entries.filter(path -> path.getFileName().toString().startsWith(".wtl-transfer-")).count());
            }
        }
    }

    @Test
    void changedTargetWithPreservedTimestampIsNotReplacedAndVerifiedStagingRemains() throws Exception {
        Path old = root.resolve("remote/file");
        var changed = new AtomicBoolean();
        try (var fixture = new Fixture(root, 6, new SftpEventListener() {
            @Override
            public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                    int start, int length, Throwable failure) throws java.io.IOException {
                if (failure == null && changed.compareAndSet(false, true)) {
                    var modified = Files.getLastModifiedTime(old);
                    Files.writeString(old, "changed");
                    Files.setLastModifiedTime(old, modified);
                }
            }
        });
                var connector = new SshConnector();
                var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            Files.writeString(old, "initial");
            var source = Files.writeString(root.resolve("input"), "new");
            var result = connection.transfer(new TransferRequest(source, "/file", 3), digest("new".getBytes()), true,
                    Duration.ofSeconds(5), () -> false, conflict -> TransferConflictChoice.OVERWRITE);
            assertEquals(Outcome.UNKNOWN, result.outcome(), result.toString());
            assertEquals("changed", Files.readString(old));
            assertEquals("new", Files.readString(temporary(fixture.remote)));
            assertDoesNotThrow(
                    () -> java.util.UUID.fromString(result.problem().orElseThrow().context().get("transferId")));
        }
    }

    @Test
    void nativeNoReplacePublicationCannotClobberAnAppearingTarget() throws Exception {
        Path stage = Files.writeString(root.resolve("stage"), "new");
        Path target = Files.writeString(root.resolve("target"), "old");
        assertThrows(java.io.IOException.class,
                () -> gold.debug.windowstolinux.app.windows.filesystem.WindowsFilePublication.publish(stage, target,
                        false));
        assertEquals("old", Files.readString(target));
        assertEquals("new", Files.readString(stage));
    }
}
