package gold.debug.windowstolinux.app.linux.sftp;

import static gold.debug.windowstolinux.app.linux.sftp.SftpTransferTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.linux.ssh.connect.SshCommandExecutionTest;
import gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpActionLogTest {
    @TempDir
    Path root;

    @Test
    void bothDirectionsRequireCheckpointBeforeStagingAndReleaseOnlyAfterPublication() throws Exception {
        for (boolean upload : new boolean[]{true, false}) {
            Path dir = Files.createDirectory(root.resolve(Boolean.toString(upload)));
            var recorded = new AtomicReference<TransferPlan>();
            var released = new AtomicInteger();
            try (var fixture = new Fixture(dir, 3, new SftpEventListener() {
                public void open(ServerSession session, String handle, Handle file) {
                    if (file.getFile().getFileName().toString().startsWith(".wtl-transfer-"))
                        assertNotNull(recorded.get(), "Remote staging must follow durable prepare");
                }
            });
                 var connector = new SshConnector();
                 var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                var local = Files.writeString(dir.resolve("file"), upload ? "new" : "old");
                var remote = Files.writeString(fixture.remote.resolve("file"), upload ? "old" : "new");
                var request = new TransferRequest(local, "/file", 3);
                TransferActionLog reject = plan -> {
                    throw new IOException("fixture persistence failed");
                };
                var denied = connection.transfer(request, digest("new".getBytes()), upload, Duration.ofSeconds(5),
                    () -> false, progress -> {
                    }, conflict -> TransferConflictChoice.OVERWRITE, reject);
                assertEquals(TaskResult.FAILED, denied.outcome(), denied.toString());
                assertEquals("checkpoint", denied.problem().orElseThrow().context().get("phase"));
                assertEquals(upload ? "old" : "new", Files.readString(remote));
                assertEquals(upload ? "new" : "old", Files.readString(local));
                try (var files = Files.walk(dir)) {
                    assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".wtl-transfer-")));
                }
                TransferActionLog journal = plan -> {
                    assertEquals(request, plan.request());
                    assertEquals(upload, plan.upload());
                    assertEquals(digest("new".getBytes()), plan.source().sha256());
                    assertEquals(digest("old".getBytes()), plan.target().sha256());
                    assertEquals(3, plan.source().size());
                    assertEquals("true", plan.recoveryFields().get("overwrite"));
                    if (!upload)
                        assertFalse(Files.exists(Path.of(plan.temporaryPath())));
                    recorded.set(plan);
                    return new ResourceLease() {
                        public UUID id() {
                            return plan.id();
                        }

                        public void close() throws ApiException {
                            try {
                                assertEquals("new", Files.readString(local));
                                assertEquals("new", Files.readString(remote));
                                try (var files = Files.walk(dir)) {
                                    assertFalse(files
                                        .anyMatch(p -> p.getFileName().toString().startsWith(".wtl-transfer-")));
                                }
                                released.incrementAndGet();
                            } catch (IOException failure) {
                                throw new AssertionError(failure);
                            }
                        }
                    };
                };
                var result = connection.transfer(request, digest("new".getBytes()), upload, Duration.ofSeconds(5),
                    () -> false, progress -> {
                    }, conflict -> TransferConflictChoice.OVERWRITE, journal);
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                assertEquals(1, released.get());
                assertTrue(recorded.get().temporaryPath().contains(recorded.get().id().toString()));
            }
        }
    }

    @Test
    void interruptedTransferKeepsFileLeaseAfterActualConnectionClosure() throws Exception {
        var recorded = new AtomicReference<TransferPlan>();
        var released = new AtomicBoolean();
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {
            public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                int start, int length, Throwable failure) {
                if (failure == null)
                    session.close(true);
            }
        }); var connector = new SshConnector()) {
            var source = Files.write(root.resolve("source"), new byte[100000]);
            var connection = SshCommandExecutionTest.connect(connector, fixture.server);
            var result = connection.transfer(new TransferRequest(source, "/file", 100000), digest(new byte[100000]),
                true, Duration.ofSeconds(5), () -> false, progress -> {
                }, conflict -> TransferConflictChoice.SKIP, plan -> {
                    recorded.set(plan);
                    return new ResourceLease() {
                        public UUID id() {
                            return plan.id();
                        }

                        public void close() {
                            released.set(true);
                        }
                    };
                });
            assertEquals(TaskResult.UNKNOWN, result.outcome(), result.toString());
            connection.close();
            assertFalse(released.get());
            assertEquals(recorded.get().id().toString(), result.problem().orElseThrow().context().get("transferId"));
            assertEquals(16384, Files.size(temporary(fixture.remote)));
        }
    }

    @Test
    void publicationReleaseFailureIsUnknownAndRetryDoesNotPublishAgain() throws Exception {
        var releases = new AtomicInteger();
        try (var fixture = new Fixture(root, 6, new SftpEventListener() {
        }); var connector = new SshConnector()) {
            var source = Files.writeString(root.resolve("source"), "new");
            var connection = SshCommandExecutionTest.connect(connector, fixture.server);
            var result = connection.transfer(new TransferRequest(source, "/file", 3), digest("new".getBytes()), true,
                Duration.ofSeconds(5), () -> false, progress -> {
                }, conflict -> TransferConflictChoice.SKIP, plan -> new ResourceLease() {
                    public UUID id() {
                        return plan.id();
                    }

                    public void close() throws ApiException {
                        if (releases.incrementAndGet() == 1)
                            throw new ApiException(new Problem(ProblemCode.IO_FAILURE, "FIXTURE_RELEASE", "fixture",
                                Map.of(), Optional.empty()));
                    }
                });
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("SFTP_RELEASE_PENDING", result.problem().orElseThrow().eventCode());
            assertEquals("new", Files.readString(fixture.remote.resolve("file")));
            Files.writeString(fixture.remote.resolve("file"), "subsequent external change");
            connection.close();
            assertEquals(2, releases.get());
            assertEquals("subsequent external change", Files.readString(fixture.remote.resolve("file")));
        }
    }
}
