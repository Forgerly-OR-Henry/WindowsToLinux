package gold.debug.windowstolinux.app.linux.sftp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.linux.ssh.connect.LinuxLoopbackSshServer;
import gold.debug.windowstolinux.app.linux.ssh.connect.SshCommandExecutionTest;
import gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.channel.*;
import org.apache.sshd.common.session.helpers.AbstractConnectionService;
import org.apache.sshd.common.util.threads.*;
import org.apache.sshd.server.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpTransferTest {
    @TempDir
    Path root;

    @Test
    void verifiedWorkerTailClosesGracefullyBeforeItsFuturePublishesTheResult() throws Exception {
        var verified = new AtomicReference<OperationResult<TransferResult>>();
        var verifiedTail = new CountDownLatch(1);
        var publishResult = new CountDownLatch(1);
        var closing = new AtomicBoolean();
        var closeProbe = new AtomicBoolean();
        var doneAtClose = new AtomicReference<Boolean>();
        var originalWorker = new AtomicReference<Thread>();
        var receivedClose = new CountDownLatch(1);
        var unregistered = new CountDownLatch(1);
        var serverSession = new AtomicReference<ServerSession>();
        var releases = new AtomicInteger();
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {});
             var client = SshClient.setUpDefaultClient()) {
            fixture.server.server().addChannelListener(new ChannelListener() {
                public void channelInitialized(Channel channel) {
                    serverSession.set((ServerSession) channel.getSession());
                }
                public void channelStateChanged(Channel channel, String hint) {
                    if (hint.equals("SSH_MSG_CHANNEL_CLOSE")) receivedClose.countDown();
                    if (hint.equals("unregistered")) unregistered.countDown();
                }
            });
            client.setServerKeyVerifier((session, address, key) -> true);
            client.start();
            try (var session = client.connect("synthetic-tail-user", "127.0.0.1", fixture.server.server().getPort())
                .verify(Duration.ofSeconds(5)).getSession()) {
                session.addPasswordIdentity("synthetic-tail-password");
                session.auth().verify(Duration.ofSeconds(5));
                byte[] content = "verified-before-future-publication".getBytes(StandardCharsets.UTF_8);
                Path source = Files.write(root.resolve("source"), content);
                var transfer = new SftpTransfer(session, new TransferRequest(source, "/uploaded", content.length),
                    digest(content), true, Duration.ofSeconds(5), () -> false,
                    conflict -> TransferConflictChoice.SKIP, plan -> new ResourceLease() {
                        public UUID id() { return plan.id(); }
                        public void close() { releases.incrementAndGet(); }
                    }, progress -> {}, null, false);
                try {
                    long started = System.nanoTime();
                    var observed = transfer.run(callable -> new FutureTask<>(callable) {
                        protected void set(OperationResult<TransferResult> result) {
                            originalWorker.set(Thread.currentThread());
                            verified.set(result);
                            verifiedTail.countDown();
                            try {
                                if (!publishResult.await(15, TimeUnit.SECONDS))
                                    throw new AssertionError("Verified tail was not released");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                            super.set(result);
                        }
                        public boolean isDone() {
                            boolean done = super.isDone();
                            if (closing.get() && closeProbe.compareAndSet(false, true)) {
                                doneAtClose.set(done);
                                publishResult.countDown();
                            }
                            return done;
                        }
                    });
                    assertEquals(0, verifiedTail.getCount(), "Real transfer must reach its verified return tail");
                    assertEquals(TaskResult.SUCCEEDED, verified.get().outcome(), verified.get().toString());
                    assertEquals(TaskResult.UNKNOWN, observed.outcome(), observed.toString());
                    assertEquals("SFTP_STALLED", observed.problem().orElseThrow().eventCode());
                    assertArrayEquals(content, Files.readAllBytes(fixture.remote.resolve("uploaded")));
                    assertEquals(0, releases.get());
                    closing.set(true);
                    boolean closed = transfer.close();
                    System.out.println("verified-tail: monitor=" + observed.problem().orElseThrow().eventCode()
                        + ", verified=" + verified.get().outcome() + ", resultIdentity="
                        + Integer.toHexString(System.identityHashCode(verified.get())) + ", closeIsDone=" + doneAtClose.get()
                        + ", close=" + closed + ", releases=" + releases.get() + ", receivedClose="
                        + (receivedClose.getCount() == 0) + ", elapsedNanos=" + (System.nanoTime() - started));
                    assertEquals(Boolean.FALSE, doneAtClose.get(), "Close must observe the real unpublished FutureTask state");
                    assertTrue(closed, "Verified worker tail must retain graceful peer-confirmed closure");
                    assertSame(verified.get(), transfer.afterClose(observed));
                    assertEquals(1, releases.get());
                    assertTrue(receivedClose.await(5, TimeUnit.SECONDS), "Server must receive a real CHANNEL_CLOSE");
                    assertTrue(unregistered.await(5, TimeUnit.SECONDS));
                    assertEquals(0, serverSession.get().getService(AbstractConnectionService.class).getChannels().size());
                    assertFalse(session.isClosed());
                    assertTrue(transfer.close());
                    assertEquals(1, releases.get(), "Repeated cleanup must not release the lease twice");
                } finally {
                    publishResult.countDown();
                    assertTrue(session.close(true).awaitUninterruptibly(Duration.ofSeconds(5)));
                    assertTrue(transfer.close(), "Session closure must finish retained transfer cleanup");
                    if (originalWorker.get() != null)
                        assertTrue(originalWorker.get().join(Duration.ofSeconds(5)), "Original transfer worker leaked");
                }
            }
        }
    }

    @Test
    void successfulTransfersReleaseServerChannelsBeforeReusingTheSameSession() throws Exception {
        var channels = new ConcurrentHashMap<Channel, CountDownLatch>();
        var receivedClose = ConcurrentHashMap.<Channel>newKeySet();
        var serverSession = new AtomicReference<ServerSession>();
        var commands = new CopyOnWriteArrayList<BarrierCommand>();
        var observedCounts = new ArrayList<Integer>();
        var unregistered = new ArrayList<Boolean>();
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {});
             var connector = new SshConnector()) {
            fixture.server.server().addChannelListener(new ChannelListener() {
                public void channelInitialized(Channel channel) {
                    channels.put(channel, new CountDownLatch(1));
                    serverSession.set((ServerSession) channel.getSession());
                }
                public void channelStateChanged(Channel channel, String hint) {
                    if (hint.equals("SSH_MSG_CHANNEL_CLOSE")) receivedClose.add(channel);
                    if (hint.equals("unregistered")) channels.get(channel).countDown();
                }
            });
            fixture.server.server().setCommandFactory((channel, request) -> {
                var command = new BarrierCommand(fixture.failures);
                commands.add(command);
                return command;
            });
            try (var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                for (int ordinal = 1; ordinal <= 2; ordinal++) {
                    byte[] content = ("synthetic-transfer-" + ordinal + "\n").getBytes(StandardCharsets.UTF_8);
                    Path source = Files.write(root.resolve("source-" + ordinal), content);
                    var result = transfer(connection, source, "/uploaded-" + ordinal, content.length,
                        digest(content), true);
                    assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                    assertArrayEquals(content, Files.readAllBytes(fixture.remote.resolve("uploaded-" + ordinal)));
                    assertEquals(ordinal, fixture.sftpChannels.size());
                    try (var request = new CommandRequest("synthetic-barrier", new byte[0], Duration.ofSeconds(5), 1024)) {
                        var barrier = connection.execute(request, () -> false);
                        assertEquals(TaskResult.SUCCEEDED, barrier.outcome(), barrier.toString());
                        assertEquals("protocol-ack\n", barrier.value().orElseThrow().stdout());
                        assertEquals(0, barrier.value().orElseThrow().exitCode().orElseThrow());
                    }
                    assertTrue(commands.getLast().inputCompleted.await(5, TimeUnit.SECONDS));
                    assertTrue(channels.get(commands.getLast().channel).await(5, TimeUnit.SECONDS));
                    unregistered.add(channels.get(fixture.sftpChannels.getLast()).await(5, TimeUnit.SECONDS));
                    observedCounts.add(serverSession.get().getService(AbstractConnectionService.class).getChannels().size());
                    assertTrue(connection.connected());
                }
                assertEquals(List.of(0, 0), observedCounts, "Server channels retained after real exec barriers");
                assertEquals(List.of(true, true), unregistered);
                assertTrue(receivedClose.containsAll(fixture.sftpChannels), "Server must receive both CHANNEL_CLOSE messages");
                assertTrue(channels.keySet().stream().allMatch(channel -> channel.getSession() == serverSession.get()),
                    "Both transfers and exec barriers must use the original server session");
            }
        } finally {
            for (var command : commands)
                if (command.worker != null) assertTrue(command.worker.join(Duration.ofSeconds(5)));
        }
    }

    @Test
    void missingPeerCloseAcknowledgementRetainsOwnershipUntilTheSessionCloses() throws Exception {
        var receivedClose = new CountDownLatch(1);
        var unregistered = new CountDownLatch(1);
        var releases = new AtomicInteger();
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {});
             var connector = new SshConnector()) {
            fixture.server.server().setChannelFactories(List.of(new ChannelFactory() {
                public String getName() { return "session"; }
                public Channel createChannel(org.apache.sshd.common.session.Session session) {
                    return new ChannelSession() {
                        final org.apache.sshd.common.Closeable withheld = new org.apache.sshd.common.util.closeable.AbstractCloseable("withheld-peer-close") {
                            protected org.apache.sshd.common.future.CloseFuture doCloseGracefully() {
                                return new org.apache.sshd.common.future.DefaultCloseFuture("no-peer-ack", null);
                            }
                        };
                        public void handleClose() throws IOException {
                            receivedClose.countDown();
                            super.handleClose();
                        }
                        protected org.apache.sshd.common.Closeable getInnerCloseable() {
                            return builder().sequential(withheld, super.getInnerCloseable()).build();
                        }
                    };
                }
            }));
            fixture.server.server().addChannelListener(new ChannelListener() {
                public void channelStateChanged(Channel channel, String hint) {
                    if (hint.equals("unregistered")) unregistered.countDown();
                }
            });
            try (var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                byte[] content = "verified-before-close".getBytes(StandardCharsets.UTF_8);
                Path source = Files.write(root.resolve("source"), content);
                long started = System.nanoTime();
                var result = connection.transfer(new TransferRequest(source, "/uploaded", content.length),
                    digest(content), true, Duration.ofSeconds(5), () -> false, progress -> {},
                    conflict -> TransferConflictChoice.SKIP, plan -> new ResourceLease() {
                        public UUID id() { return plan.id(); }
                        public void close() { releases.incrementAndGet(); }
                    });
                long elapsed = System.nanoTime() - started;
                assertTrue(elapsed < Duration.ofSeconds(6).toNanos(), "Five-second close wait exceeded scheduling allowance");
                assertEquals(0, receivedClose.getCount(), "Server must receive the real close request");
                assertEquals(TaskResult.UNKNOWN, result.outcome(), result.toString());
                assertEquals("SFTP_RELEASE_PENDING", result.problem().orElseThrow().eventCode());
                assertArrayEquals(content, Files.readAllBytes(fixture.remote.resolve("uploaded")));
                assertEquals(0, releases.get(), "Verified publication does not release ownership before channel confirmation");
                assertEquals(1, unregistered.getCount());
                assertTrue(connection.connected());
                assertEquals("SSH_CONNECTION_UNCERTAIN", assertThrows(ApiException.class,
                    () -> transfer(connection, source, "/forbidden-reuse", content.length, digest(content), true))
                    .problem().eventCode());
                connection.close();
                assertEquals(1, releases.get());
                assertTrue(unregistered.await(5, TimeUnit.SECONDS));
                assertEquals(0, connector.retainedConnections());
            }
        }
    }

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
                assertEquals(TaskResult.SUCCEEDED, upload.outcome(), upload.toString());
                assertEquals(bytes.length, upload.value().orElseThrow().transferredBytes());
                assertEquals(digest(bytes), upload.value().orElseThrow().sha256());
                assertArrayEquals(bytes, Files.readAllBytes(fixture.remote.resolve("nested/二进制 file.bin")));
                Path received = directory.resolve("received.bin");
                var download = transfer(connection, received, "/nested/二进制 file.bin", bytes.length, digest(bytes),
                    false);
                assertEquals(TaskResult.SUCCEEDED, download.outcome(), download.toString());
                assertArrayEquals(bytes, Files.readAllBytes(received));
                var skippedDownload = transfer(connection, received, "/nested/二进制 file.bin", bytes.length,
                    digest(bytes), false);
                assertEquals(TaskResult.FAILED, skippedDownload.outcome());
                assertEquals("SFTP_CONFLICT_SKIPPED", skippedDownload.problem().orElseThrow().eventCode());
                var skippedUpload = transfer(connection, source, "/nested/二进制 file.bin", bytes.length,
                    digest(bytes), true);
                assertEquals(TaskResult.FAILED, skippedUpload.outcome());
                assertEquals("SFTP_CONFLICT_SKIPPED", skippedUpload.problem().orElseThrow().eventCode());
                assertArrayEquals(bytes, Files.readAllBytes(received));
                Path empty = Files.write(directory.resolve("empty"), new byte[0]);
                assertEquals(TaskResult.SUCCEEDED,
                    transfer(connection, empty, "/empty", 0, digest(new byte[0]), true).outcome());
                assertEquals(TaskResult.SUCCEEDED,
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
            assertEquals(TaskResult.FAILED, transfer(connection, source, "/never", 2, digest(bytes), true).outcome());
            assertEquals(TaskResult.FAILED, transfer(connection, source, "/never", 3, "0".repeat(64), true).outcome());
            assertEquals(TaskResult.FAILED, transfer(connection, root, "/never", 3, digest(bytes), true).outcome());
            Files.createDirectories(fixture.remote.resolve("directory"));
            assertEquals(TaskResult.FAILED,
                transfer(connection, root.resolve("never"), "/directory", 3, digest(bytes), false).outcome());
            Files.write(fixture.remote.resolve("file"), bytes);
            assertEquals(TaskResult.FAILED,
                transfer(connection, root.resolve("never"), "/file", 2, digest(bytes), false).outcome());
            assertEquals(TaskResult.FAILED,
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
            assertEquals(TaskResult.UNKNOWN, result.outcome());
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
            assertEquals(TaskResult.CANCELLED, connection
                .transfer(request, digest(bytes), true, Duration.ofSeconds(5), cancelled::get, progress -> {
                }, conflict -> TransferConflictChoice.SKIP, journal()).outcome());
            assertFalse(Files.exists(fixture.remote.resolve("partial")));
            cancelled.set(false);
            assertEquals(TaskResult.UNKNOWN, connection
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
                    assertEquals(TaskResult.UNKNOWN, result.outcome(), result.toString());
                    if (timeout) {
                        assertEquals("SFTP_RELEASE_PENDING", result.problem().orElseThrow().eventCode());
                        assertEquals(1, connector.retainedConnections());
                        assertEquals("SSH_CONNECTION_UNCERTAIN", assertThrows(ApiException.class,
                            () -> transfer(connection, source, "/forbidden-reuse", bytes.length, digest(bytes), true))
                            .problem().eventCode());
                    }
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
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertTrue(result.value().isEmpty());
            assertFalse(Files.exists(fixture.remote.resolve("changed")));
            assertArrayEquals(new byte[]{9, 2, 3}, Files.readAllBytes(temporary(fixture.remote)));
        }
    }

    static TransferActionLog journal() {
        return plan -> new ResourceLease() {
            public java.util.UUID id() {
                return plan.id();
            }

            public void close() {
            }
        };
    }

    static OperationResult<gold.debug.windowstolinux.appapi.linux.transfer.TransferResult> transfer(
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
                assertEquals(TaskResult.FAILED,
                    transfer(connection, localLink.resolve("input"), "/never", 3, digest(bytes), true).outcome());
                assertEquals(TaskResult.FAILED,
                    transfer(connection, source, "/remote-link/never", 3, digest(bytes), true).outcome());
                Files.write(fixture.remote.resolve("source"), bytes);
                assertEquals(TaskResult.FAILED,
                    transfer(connection, localLink.resolve("never"), "/source", 3, digest(bytes), false).outcome());
                assertEquals(TaskResult.FAILED,
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
            assertEquals(TaskResult.UNKNOWN, result.outcome());
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

    static final class BarrierCommand implements Command {
        final Queue<Throwable> failures;
        final CountDownLatch inputCompleted = new CountDownLatch(1);
        InputStream input;
        OutputStream output;
        ExitCallback exit;
        ChannelSession channel;
        Thread worker;
        BarrierCommand(Queue<Throwable> failures) { this.failures = failures; }
        public void setInputStream(InputStream value) { input = value; }
        public void setOutputStream(OutputStream value) { output = value; }
        public void setErrorStream(OutputStream value) {}
        public void setExitCallback(ExitCallback value) { exit = value; }
        public void start(ChannelSession channel, Environment environment) {
            this.channel = channel;
            worker = Thread.ofVirtual().name("fixture-sftp-release-barrier").start(() -> {
                try {
                    assertTrue(input.readAllBytes().length > 0);
                    inputCompleted.countDown();
                    output.write("protocol-ack\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    exit.onExit(0);
                } catch (Throwable failure) {
                    failures.add(failure);
                    exit.onExit(1);
                }
            });
        }
        public void destroy(ChannelSession channel) throws Exception {
            if (input != null) input.close();
            if (worker != null && worker != Thread.currentThread())
                assertTrue(worker.join(Duration.ofSeconds(5)));
        }
    }

    static final class Fixture implements AutoCloseable {
        final Path remote;

        final LinuxLoopbackSshServer server;

        final List<CloseableExecutorService> executors = new CopyOnWriteArrayList<>();

        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        final List<ChannelSession> sftpChannels = new CopyOnWriteArrayList<>();

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
                    sftpChannels.add(channel);
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
