package gold.debug.windowstolinux.app.linux.sftp;

import gold.debug.windowstolinux.app.linux.ssh.connect.LinuxLoopbackSshServer;
import gold.debug.windowstolinux.app.linux.ssh.connect.SshCommandExecutionTest;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.ResourceLease;

import gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.appapi.linux.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.TransferRequest;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.util.threads.*;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpTransferTest {
    @TempDir
    Path root;

    @Test
    void roundTripsBinaryFilesAndEmptyFilesOnSftpThreeAndSixWithoutOverwriting() throws Exception {
        for (int version : new int[]{3, 6}) {
            Path directory = Files.createDirectories(root.resolve("v" + version));
            try (var fixture = new Fixture(directory, version, new SftpEventListener() {
            });
                 var connector = new SshConnector();
                 var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                byte[] bytes = new byte[150007];
                for (int i = 0; i < bytes.length; i++)
                    bytes[i] = (byte) i;
                Path source = Files.write(directory.resolve("原始 输入.bin"), bytes);
                Files.createDirectories(fixture.remote.resolve("nested"));
                var upload = transfer(connection, source, "/nested/二进制 file.bin", bytes.length, digest(bytes), true);
                assertEquals(Outcome.SUCCEEDED, upload.outcome(), upload.toString());
                assertEquals(bytes.length, upload.value().orElseThrow().transferredBytes());
                assertEquals(digest(bytes), upload.value().orElseThrow().sha256());
                assertArrayEquals(bytes, Files.readAllBytes(fixture.remote.resolve("nested/二进制 file.bin")));
                Path received = directory.resolve("received.bin");
                var download = transfer(connection, received, "/nested/二进制 file.bin", bytes.length, digest(bytes),
                    false);
                assertEquals(Outcome.SUCCEEDED, download.outcome(), download.toString());
                assertArrayEquals(bytes, Files.readAllBytes(received));
                assertEquals(Outcome.CANCELLED,
                    transfer(connection, received, "/nested/二进制 file.bin", bytes.length, digest(bytes), false)
                        .outcome());
                assertEquals(Outcome.CANCELLED,
                    transfer(connection, source, "/nested/二进制 file.bin", bytes.length, digest(bytes), true)
                        .outcome());
                assertArrayEquals(bytes, Files.readAllBytes(received));
                Path empty = Files.write(directory.resolve("empty"), new byte[0]);
                assertEquals(Outcome.SUCCEEDED,
                    transfer(connection, empty, "/empty", 0, digest(new byte[0]), true).outcome());
                assertEquals(Outcome.SUCCEEDED,
                    transfer(connection, directory.resolve("empty-copy"), "/empty", 0, digest(new byte[0]), false)
                        .outcome());
                Files.delete(source);
                Files.delete(received);
            }
        }
    }

    @Test
    void rejectsInvalidPathsBudgetsDigestsDirectoriesAndChangedSourcesBeforeCreatingDestinations() throws Exception {
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {
        });
             var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            byte[] bytes = {1, 2, 3};
            Path source = Files.write(root.resolve("input"), bytes);
            for (String path : List.of("relative", "/", "/a/../b", "/a//b", "/a/./b", "/a\\b", "/a\nb", "/\uD800"))
                assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class,
                    () -> transfer(connection, source, path, 3, digest(bytes), true)).problem().code());
            assertEquals(ProblemCode.INVALID_INPUT,
                assertThrows(ApiException.class,
                    () -> transfer(connection, Path.of("relative"), "/never", 3, digest(bytes), true)).problem()
                    .code());
            assertEquals(ProblemCode.INVALID_INPUT,
                assertThrows(ApiException.class, () -> transfer(connection, source, "/never", 3, "bad", true))
                    .problem().code());
            assertEquals(Outcome.FAILED, transfer(connection, source, "/never", 2, digest(bytes), true).outcome());
            assertEquals(Outcome.FAILED, transfer(connection, source, "/never", 3, "0".repeat(64), true).outcome());
            assertEquals(Outcome.FAILED, transfer(connection, root, "/never", 3, digest(bytes), true).outcome());
            Files.createDirectories(fixture.remote.resolve("directory"));
            assertEquals(Outcome.FAILED,
                transfer(connection, root.resolve("never"), "/directory", 3, digest(bytes), false).outcome());
            Files.write(fixture.remote.resolve("file"), bytes);
            assertEquals(Outcome.FAILED,
                transfer(connection, root.resolve("never"), "/file", 2, digest(bytes), false).outcome());
            assertEquals(Outcome.FAILED,
                transfer(connection, root.resolve("never"), "/file", 3, "0".repeat(64), false).outcome());
            assertFalse(Files.exists(root.resolve("never")));
            assertFalse(Files.exists(fixture.remote.resolve("never")));
        }
    }

    @Test
    void rejectsCorruptedAcknowledgedUploadAndDoesNotDeleteOrReplayIt() throws Exception {
        var writes = new AtomicInteger();
        try (var fixture = new Fixture(root, 6, new SftpEventListener() {
            @Override
            public void writing(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                int start, int length) {
                writes.incrementAndGet();
                data[start] ^= 1;
            }
        });
             var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            byte[] bytes = {1, 2, 3};
            Path source = Files.write(root.resolve("input"), bytes);
            var result = transfer(connection, source, "/corrupt", 3, digest(bytes), true);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals(1, writes.get());
            assertFalse(Files.exists(fixture.remote.resolve("corrupt")));
            assertArrayEquals(new byte[]{0, 2, 3}, Files.readAllBytes(temporary(fixture.remote)));
            assertThrows(ApiException.class, () -> transfer(connection, source, "/retry", 3, digest(bytes), true));
            assertEquals(1, writes.get());
        }
    }

    @Test
    void cancellationBeforeCreationIsKnownAndAfterFirstWriteRetainsPartialFile() throws Exception {
        var cancelled = new AtomicBoolean(true);
        var writes = new AtomicInteger();
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {
            @Override
            public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                int start, int length, Throwable failure) {
                if (failure == null) {
                    writes.incrementAndGet();
                    cancelled.set(true);
                }
            }
        });
             var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            byte[] bytes = new byte[200000];
            Path source = Files.write(root.resolve("input"), bytes);
            var request = new TransferRequest(source, "/partial", bytes.length);
            assertEquals(Outcome.CANCELLED, connection
                .transfer(request, digest(bytes), true, Duration.ofSeconds(5), cancelled::get, progress -> {
                }, conflict -> TransferConflictChoice.SKIP, journal()).outcome());
            assertFalse(Files.exists(fixture.remote.resolve("partial")));
            cancelled.set(false);
            assertEquals(Outcome.UNKNOWN, connection
                .transfer(request, digest(bytes), true, Duration.ofSeconds(5), cancelled::get, progress -> {
                }, conflict -> TransferConflictChoice.SKIP, journal()).outcome());
            assertEquals(1, writes.get());
            assertFalse(Files.exists(fixture.remote.resolve("partial")));
            assertEquals(16384, Files.size(temporary(fixture.remote)));
            assertEquals(1, connector.retainedConnections());
        }
    }

    @Test
    void deadlineAndDisconnectNeverClaimThatAStartedUploadRolledBack() throws Exception {
        for (boolean timeout : new boolean[]{true, false}) {
            Path directory = Files.createDirectories(root.resolve(Boolean.toString(timeout)));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var fixture = new Fixture(directory, 3, new SftpEventListener() {
                @Override
                public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                    int start, int length, Throwable failure) throws IOException {
                    if (failure != null)
                        return;
                    entered.countDown();
                    if (!timeout) {
                        session.close(true);
                        return;
                    }
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new IOException("Fixture release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }
            });
                 var connector = new SshConnector();
                 var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                byte[] bytes = new byte[100000];
                Path source = Files.write(directory.resolve("input"), bytes);
                try {
                    var result = connection.transfer(new TransferRequest(source, "/partial", bytes.length),
                        digest(bytes), true, timeout ? Duration.ofMillis(400) : Duration.ofSeconds(5), () -> false,
                        progress -> {
                        }, conflict -> TransferConflictChoice.SKIP, journal());
                    assertEquals(0, entered.getCount());
                    assertEquals(Outcome.UNKNOWN, result.outcome(), result.toString());
                    if (timeout)
                        assertEquals("SFTP_STALLED", result.problem().orElseThrow().eventCode());
                    assertFalse(Files.exists(fixture.remote.resolve("partial")));
                    assertEquals(16384, Files.size(temporary(fixture.remote)));
                    assertTrue(temporary(fixture.remote).getFileName().toString()
                        .contains(result.problem().orElseThrow().context().get("transferId")));
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void sourceMutationAfterReviewLeavesUnknownAndNeverReturnsVerifiedContent() throws Exception {
        byte[] bytes = {1, 2, 3};
        Path source = Files.write(root.resolve("input"), bytes);
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {
            @Override
            public void open(ServerSession session, String handle, Handle file) throws IOException {
                Files.write(source, new byte[]{9, 2, 3});
            }
        });
             var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            var result = transfer(connection, source, "/changed", 3, digest(bytes), true);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertTrue(result.value().isEmpty());
            assertFalse(Files.exists(fixture.remote.resolve("changed")));
            assertArrayEquals(new byte[]{9, 2, 3}, Files.readAllBytes(temporary(fixture.remote)));
        }
    }

    static TransferJournal journal() {
        return plan -> new ResourceLease() {
            public java.util.UUID id() {
                return plan.id();
            }

            public void close() {
            }
        };
    }

    static OperationResult<gold.debug.windowstolinux.appapi.linux.TransferResult> transfer(
        SshConnector.Connection connection, Path local, String remote, long limit, String digest, boolean upload)
        throws ApiException {
        return connection.transfer(new TransferRequest(local, remote, limit), digest, upload, Duration.ofSeconds(5),
            () -> false, progress -> {
            }, conflict -> TransferConflictChoice.SKIP, journal());
    }

    @Test
    void rejectsLocalAndRemoteLinkedParentsWithoutWritingThroughThem() throws Exception {
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path localLink = root.resolve("local-link");
        byte[] bytes = {1, 2, 3};
        Files.write(outside.resolve("input"), bytes);
        Path source = Files.write(root.resolve("input"), bytes);
        directoryLink(localLink, outside);
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {
        }, new SftpFileSystemAccessor() {
            @Override
            public NavigableMap<String, Object> resolveReportedFileAttributes(SftpSubsystemProxy subsystem, Path file,
                                                                              int flags, NavigableMap<String, Object> attributes, LinkOption... options) throws IOException {
                // The Windows test server reports junctions as directories; model Linux symlink wire attributes explicitly.
                if (file.getFileName() != null && file.getFileName().toString().equals("remote-link")) {
                    attributes.put("isSymbolicLink", true);
                    attributes.put("isDirectory", false);
                    attributes.put("isRegularFile", false);
                }
                return attributes;
            }
        });
             var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            Path remoteLink = fixture.remote.resolve("remote-link");
            directoryLink(remoteLink, outside);
            try {
                assertEquals(Outcome.FAILED,
                    transfer(connection, localLink.resolve("input"), "/never", 3, digest(bytes), true).outcome());
                assertEquals(Outcome.FAILED,
                    transfer(connection, source, "/remote-link/never", 3, digest(bytes), true).outcome());
                Files.write(fixture.remote.resolve("source"), bytes);
                assertEquals(Outcome.FAILED,
                    transfer(connection, localLink.resolve("never"), "/source", 3, digest(bytes), false).outcome());
                assertEquals(Outcome.FAILED,
                    transfer(connection, root.resolve("never"), "/remote-link/input", 3, digest(bytes), false)
                        .outcome());
                assertFalse(Files.exists(outside.resolve("never")));
                assertFalse(Files.exists(fixture.remote.resolve("never")));
                assertArrayEquals(bytes, Files.readAllBytes(outside.resolve("input")));
            } finally {
                Files.delete(remoteLink);
            }
        } finally {
            Files.delete(localLink);
        }
    }

    @Test
    void detectsRemoteContentChangingBetweenReviewAndDownloadWithoutDeletingEvidence() throws Exception {
        var firstReads = new AtomicInteger();
        byte[] bytes = {1, 2, 3};
        try (var fixture = new Fixture(root, 6, new SftpEventListener() {
            @Override
            public void read(ServerSession session, String handle, FileHandle file, long offset, byte[] data, int start,
                             int length, int read, Throwable failure) {
                if (failure == null && offset == 0 && read > 0 && firstReads.incrementAndGet() == 2)
                    data[start] ^= 1;
            }
        });
             var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            Files.write(fixture.remote.resolve("changing"), bytes);
            Path received = root.resolve("received");
            var result = transfer(connection, received, "/changing", bytes.length, digest(bytes), false);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertTrue(result.value().isEmpty());
            assertFalse(Files.exists(received));
            assertArrayEquals(new byte[]{0, 2, 3}, Files.readAllBytes(temporary(root)));
            Files.delete(temporary(root));
        }
    }

    static void directoryLink(Path link, Path target) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Files.createSymbolicLink(link, target);
            return;
        }
        var builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path $env:TRANSFER_TEST_LINK -Target $env:TRANSFER_TEST_TARGET | Out-Null");
        builder.environment().put("TRANSFER_TEST_LINK", link.toString());
        builder.environment().put("TRANSFER_TEST_TARGET", target.toString());
        var process = builder.redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Junction fixture creation timed out");
            assertEquals(0, process.exitValue(),
                new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static Path temporary(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            var matches = files.filter(file -> file.getFileName().toString().startsWith(".wtl-transfer-")).toList();
            assertEquals(1, matches.size(), matches.toString());
            return matches.getFirst();
        }
    }

    static final class Fixture implements AutoCloseable {
        final Path remote;

        final LinuxLoopbackSshServer server;

        final List<CloseableExecutorService> executors = new CopyOnWriteArrayList<>();

        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        Fixture(Path directory, int version, SftpEventListener listener) throws Exception {
            this(directory, version, listener, SftpFileSystemAccessor.DEFAULT);
        }

        Fixture(Path directory, int version, SftpEventListener listener, SftpFileSystemAccessor accessor)
            throws Exception {
            remote = Files.createDirectories(directory.resolve("remote"));
            var subsystem = new SftpSubsystemFactory() {
                @Override
                public org.apache.sshd.server.command.Command createSubsystem(
                    org.apache.sshd.server.channel.ChannelSession channel) throws IOException {
                    try {
                        var command = new SftpSubsystem(channel, this) {
                            @Override
                            protected void doProcess(org.apache.sshd.common.util.buffer.Buffer buffer, int length,
                                                     int type, int id) throws IOException {
                                try {
                                    super.doProcess(buffer, length, type, id);
                                } catch (IOException | RuntimeException | Error failure) {
                                    if (!(failure instanceof java.io.EOFException
                                        && (channel.isClosing() || channel.getSession().isClosing())))
                                        failures.add(failure);
                                    throw failure;
                                }
                            }
                        };
                        getRegisteredListeners().forEach(command::addSftpEventListener);
                        return command;
                    } catch (RuntimeException | Error failure) {
                        failures.add(failure);
                        throw failure;
                    }
                }
            };
            subsystem.setExecutorServiceProvider(() -> {
                var executor = ThreadUtils.newSingleThreadExecutor("fixture-sftp-" + UUID.randomUUID());
                executors.add(executor);
                return executor;
            });
            subsystem.addSftpEventListener(listener);
            subsystem.setFileSystemAccessor(accessor);
            server = new LinuxLoopbackSshServer(directory, ssh -> {
                ssh.setFileSystemFactory(new VirtualFileSystemFactory(remote));
                ssh.setSubsystemFactories(List.of(subsystem));
                SftpModuleProperties.SFTP_VERSION.set(ssh, version);
            });
        }

        public void close() throws Exception {
            server.close();
            for (var executor : executors)
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "SFTP fixture executor leaked");
            if (!failures.isEmpty())
                throw new AssertionError("SFTP fixture failed", failures.peek());
        }
    }
}
