package gold.debug.windowstolinux.app.linux.sftp;

import gold.debug.windowstolinux.app.linux.ssh.connect.SshCommandExecutionTest;

import gold.debug.windowstolinux.appapi.task.Outcome;

import gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector;

import static gold.debug.windowstolinux.app.linux.sftp.SftpTransferTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.*;

import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpProgressTest {
    @TempDir
    Path root;

    static void delay(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    @Test
    void ongoingCopyAndVerificationCanEachExceedIdleThresholdInBothDirections() throws Exception {
        var observations = new ArrayList<TransferProgress>();
        var idle = Duration.ofMillis(700);
        byte[] bytes = new byte[1024 * 1024];
        Arrays.fill(bytes, (byte) 7);
        try (var fixture = new Fixture(root, 6, new SftpEventListener() {
            public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                int start, int length, Throwable failure) throws IOException {
                if (failure == null)
                    delay(60);
            }

            public void read(ServerSession session, String handle, FileHandle file, long offset, byte[] data, int start,
                             int length, int read, Throwable failure) throws IOException {
                if (failure == null && read > 0)
                    delay(60);
            }
        });
             var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            for (boolean upload : new boolean[]{true, false}) {
                observations.clear();
                Path local = root.resolve(upload ? "source" : "received");
                if (upload)
                    Files.write(local, bytes);
                long start = System.nanoTime();
                var result = connection.transfer(new TransferRequest(local, "/file", bytes.length), digest(bytes),
                    upload, idle, () -> false, observations::add, conflict -> TransferConflictChoice.SKIP,
                    journal());
                assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
                assertTrue(System.nanoTime() - start > idle.toNanos() * 3);
                assertArrayEquals(bytes, Files.readAllBytes(upload ? fixture.remote.resolve("file") : local));
                assertTrue(observations.stream().anyMatch(p -> p.stage() == TransferProgress.Stage.TRANSFERRING
                    && p.completedBytes() > 0 && p.completedBytes() < bytes.length));
                var verification = upload
                    ? TransferProgress.Stage.VERIFYING_STAGING
                    : TransferProgress.Stage.VERIFYING_SOURCE;
                assertTrue(observations.stream().anyMatch(
                    p -> p.stage() == verification && p.completedBytes() > 0 && p.completedBytes() < bytes.length));
                assertTrue(observations.stream()
                    .allMatch(p -> p.totalBytes() < 0 || p.completedBytes() <= p.totalBytes()));
                assertEquals(1, observations.stream().map(TransferProgress::transferId).distinct().count());
            }
        }
    }

    @Test
    void humanWaitIsExcludedButStageChangesAndObservationsDoNotRefreshTheClock() throws Exception {
        var progress = new SftpProgress(UUID.randomUUID());
        long interval = Duration.ofMillis(100).toNanos();
        progress.begin(TransferProgress.Stage.TRANSFERRING, 100, 0);
        progress.advance(1);
        progress.human(true);
        Thread.sleep(160);
        assertEquals(interval, progress.remaining(interval));
        progress.human(false);
        assertTrue(progress.remaining(interval) > 0);
        Thread.sleep(140);
        progress.begin(TransferProgress.Stage.PUBLISHING, -1, 0);
        progress.snapshot();
        assertTrue(progress.remaining(interval) < 0);
        progress.begin(TransferProgress.Stage.VERIFYING_RESULT, 100, 0);
        progress.advance(1);
        assertTrue(progress.remaining(interval) > 0);
    }

    @Test
    void conflictWaitLongerThanIdleIntervalStillAllowsOriginalUploadToFinish() throws Exception {
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {
        });
             var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            Files.writeString(fixture.remote.resolve("file"), "old");
            var local = Files.writeString(root.resolve("source"), "new");
            var result = connection.transfer(new TransferRequest(local, "/file", 3), digest("new".getBytes()), true,
                Duration.ofMillis(500), () -> false, progress -> {
                }, conflict -> {
                    Thread.sleep(1100);
                    return TransferConflictChoice.OVERWRITE;
                }, journal());
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            assertEquals("new", Files.readString(fixture.remote.resolve("file")));
        }
    }
}
