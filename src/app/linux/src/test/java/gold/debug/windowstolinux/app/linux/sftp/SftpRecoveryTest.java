package gold.debug.windowstolinux.app.linux.sftp;

import gold.debug.windowstolinux.app.linux.ssh.connect.SshCommandExecutionTest;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ResourceLease;

import gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector;

import static gold.debug.windowstolinux.app.linux.sftp.SftpTransferTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.appapi.linux.*;

import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpRecoveryTest {
    @TempDir
    Path root;

    static final class InterruptedTransfer implements AutoCloseable {
        final byte[] bytes = new byte[100000];

        final boolean upload;

        final Fixture fixture;

        final Path local;

        final Path source;

        final Path target;

        final AtomicReference<TransferPlan> plan = new AtomicReference<>();

        final AtomicBoolean interrupt = new AtomicBoolean(true);

        final AtomicBoolean resuming = new AtomicBoolean();

        final AtomicLong firstWrite = new AtomicLong(-1);

        final AtomicLong minimumStaging = new AtomicLong(Long.MAX_VALUE);

        final AtomicInteger releases = new AtomicInteger();

        InterruptedTransfer(Path root, boolean upload, int version) throws Exception {
            Files.createDirectories(root);
            this.upload = upload;
            local = root.resolve("local.bin");
            new Random(74).nextBytes(bytes);
            fixture = new Fixture(root, version, new SftpEventListener() {
                public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                    int start, int length, Throwable failure) {
                    if (upload && failure == null) {
                        if (resuming.get())
                            firstWrite.compareAndSet(-1, offset);
                        if (interrupt.compareAndSet(true, false))
                            session.close(true);
                    }
                }

                public void read(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                 int start, int length, int read, Throwable failure) throws IOException {
                    var original = plan.get();
                    if (!upload && original != null && failure == null) {
                        var temp = Path.of(original.temporaryPath());
                        if (Files.exists(temp)) {
                            long size = Files.size(temp);
                            if (resuming.get())
                                minimumStaging.accumulateAndGet(size, Math::min);
                            if (size > 0 && interrupt.compareAndSet(true, false))
                                session.close(true);
                        }
                    }
                }
            });
            var remote = fixture.remote.resolve("file.bin");
            source = upload ? local : remote;
            target = upload ? remote : local;
            Files.write(source, bytes);
            Files.writeString(target, "old");
        }

        TransferJournal journal() {
            return original -> {
                plan.compareAndSet(null, original);
                assertEquals(plan.get(), original);
                return new ResourceLease() {
                    public UUID id() {
                        return original.id();
                    }

                    public void close() {
                        releases.incrementAndGet();
                    }
                };
            };
        }

        void start() throws Exception {
            try (var connector = new SshConnector();
                 var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                var result = connection.transfer(new TransferRequest(local, "/file.bin", bytes.length), digest(bytes),
                    upload, Duration.ofSeconds(5), () -> false, progress -> {
                    }, conflict -> TransferConflictChoice.OVERWRITE, journal());
                assertEquals(Outcome.UNKNOWN, result.outcome(), result.toString());
            }
            assertEquals(0, releases.get());
            assertEquals("old", Files.readString(target));
            assertTrue(Files.size(staging()) > 0 && Files.size(staging()) < bytes.length);
        }

        Path staging() {
            return upload
                ? fixture.remote.resolve(Path.of(plan.get().temporaryPath()).getFileName())
                : Path.of(plan.get().temporaryPath());
        }

        OperationResult<TransferResult> recover(boolean discard) throws Exception {
            resuming.set(true);
            try (var connector = new SshConnector();
                 var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                return connection.recoverTransfer(plan.get(), discard, Duration.ofSeconds(5), () -> false, progress -> {
                }, journal());
            }
        }

        public void close() throws Exception {
            fixture.close();
        }
    }

    @Test
    void resumesVerifiedPrefixesWithoutTruncationInBothDirectionsOnThreeAndSix() throws Exception {
        for (boolean upload : new boolean[]{true, false})
            for (int version : new int[]{3, 6}) {
                try (var interrupted = new InterruptedTransfer(root.resolve(upload + "-" + version), upload, version)) {
                    interrupted.start();
                    long extent = Files.size(interrupted.staging());
                    var result = interrupted.recover(false);
                    assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
                    assertArrayEquals(interrupted.bytes, Files.readAllBytes(interrupted.target));
                    assertFalse(Files.exists(interrupted.staging()));
                    assertEquals(1, interrupted.releases.get());
                    if (upload)
                        assertEquals(extent, interrupted.firstWrite.get());
                    else
                        assertTrue(interrupted.minimumStaging.get() >= extent, "Recovery must not truncate the prefix");
                }
            }
    }

    @Test
    void changedSourcePrefixOrOriginalTargetCannotBeResumedOrSilentlyOverwritten() throws Exception {
        for (String change : List.of("source", "prefix", "target", "sourceIdentity")) {
            try (var interrupted = new InterruptedTransfer(root.resolve(change), true, 3)) {
                interrupted.start();
                Path changed = change.equals("prefix")
                    ? interrupted.staging()
                    : change.equals("target") ? interrupted.target : interrupted.source;
                var time = Files.getLastModifiedTime(changed);
                var bytes = Files.readAllBytes(changed);
                if (change.equals("sourceIdentity")) {
                    Files.move(changed, changed.resolveSibling("retained-original"));
                    Files.write(changed, bytes);
                } else {
                    bytes[0] ^= 1;
                    Files.write(changed, bytes);
                }
                Files.setLastModifiedTime(changed, time);
                byte[] stageBefore = Files.readAllBytes(interrupted.staging());
                byte[] targetBefore = Files.readAllBytes(interrupted.target);
                var result = interrupted.recover(false);
                assertEquals(Outcome.UNKNOWN, result.outcome(), change + ": " + result);
                assertEquals(0, interrupted.releases.get());
                assertEquals(-1, interrupted.firstWrite.get());
                assertArrayEquals(stageBefore, Files.readAllBytes(interrupted.staging()));
                assertArrayEquals(targetBefore, Files.readAllBytes(interrupted.target));
            }
        }
    }

    @Test
    void discardRequiresVerifiedPrefixAndLeavesFinalTargetUntouched() throws Exception {
        for (boolean upload : new boolean[]{true, false}) {
            try (var interrupted = new InterruptedTransfer(root.resolve(Boolean.toString(upload)), upload, 6)) {
                interrupted.start();
                byte[] original = Files.readAllBytes(interrupted.staging());
                byte[] corrupt = original.clone();
                corrupt[0] ^= 1;
                Files.write(interrupted.staging(), corrupt);
                assertEquals(Outcome.UNKNOWN, interrupted.recover(true).outcome());
                assertTrue(Files.exists(interrupted.staging()));
                assertEquals(0, interrupted.releases.get());
                Files.write(interrupted.staging(), original);
                assertEquals(Outcome.SUCCEEDED, interrupted.recover(true).outcome());
                assertFalse(Files.exists(interrupted.staging()));
                assertEquals("old", Files.readString(interrupted.target));
                assertEquals(1, interrupted.releases.get());
            }
        }
    }

    @Test
    void lostPublicationAcknowledgementIsReconciledWithoutReplayingOrNeedingTheSource() throws Exception {
        var plan = new AtomicReference<TransferPlan>();
        var publications = new AtomicInteger();
        var releases = new AtomicInteger();
        try (var fixture = new Fixture(root, 3, new SftpEventListener() {
            public void moved(ServerSession session, Path source, Path target, Collection<CopyOption> options,
                              Throwable failure) {
                if (failure == null) {
                    publications.incrementAndGet();
                    session.close(true);
                }
            }
        })) {
            var source = Files.writeString(root.resolve("source"), "new");
            TransferJournal journal = facts -> {
                plan.set(facts);
                return new ResourceLease() {
                    public UUID id() {
                        return facts.id();
                    }

                    public void close() {
                        releases.incrementAndGet();
                    }
                };
            };
            try (var connector = new SshConnector();
                 var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                assertEquals(Outcome.UNKNOWN, connection.transfer(new TransferRequest(source, "/file", 3),
                    digest("new".getBytes()), true, Duration.ofSeconds(5), () -> false, progress -> {
                    }, conflict -> TransferConflictChoice.SKIP, journal).outcome());
            }
            assertEquals(0, releases.get());
            Files.delete(source);
            try (var connector = new SshConnector();
                 var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                var recovered = connection.recoverTransfer(plan.get(), false, Duration.ofSeconds(5), () -> false,
                    progress -> {
                    }, journal);
                assertEquals(Outcome.SUCCEEDED, recovered.outcome(), recovered.toString());
            }
            assertEquals(1, publications.get());
            assertEquals(1, releases.get());
            assertEquals("new", Files.readString(fixture.remote.resolve("file")));
        }
    }

    @Test
    void strictPlanDecoderRejectsChangedScopeUnknownFactsAndMalformedBooleans() throws Exception {
        try (var interrupted = new InterruptedTransfer(root, true, 3)) {
            interrupted.start();
            var original = interrupted.plan.get();
            assertEquals(original, TransferPlan.fromRecoveryFields(original.recoveryFields()));
            for (var change : Map.of("upload", "yes", "selectedRemote", "/other/file", "temporaryPath", "/foreign",
                "unexpected", "x", "maxBytes", "000100000").entrySet()) {
                var fields = new HashMap<>(original.recoveryFields());
                fields.put(change.getKey(), change.getValue());
                assertThrows(IllegalArgumentException.class, () -> TransferPlan.fromRecoveryFields(fields));
            }
        }
    }

    @Test
    void stagingChangedAfterHandleVerificationCannotReplaceTheOldTarget() throws Exception {
        for (boolean upload : new boolean[]{true, false}) {
            var directory = Files.createDirectory(root.resolve(Boolean.toString(upload)));
            var plan = new AtomicReference<TransferPlan>();
            var changed = new AtomicBoolean();
            try (var fixture = new Fixture(directory, 3, new SftpEventListener() {
                public void closed(ServerSession session, String handle, Handle file, Throwable failure)
                    throws IOException {
                    if (failure != null || plan.get() == null)
                        return;
                    boolean stagingHandle = file.getFile().getFileName().toString().startsWith(".wtl-transfer-");
                    if ((upload ? stagingHandle : file.getFile().getFileName().toString().equals("file"))
                        && changed.compareAndSet(false, true)) {
                        var staged = upload
                            ? directory.resolve("remote").resolve(Path.of(plan.get().temporaryPath()).getFileName())
                            : Path.of(plan.get().temporaryPath());
                        Files.writeString(staged, "bad");
                    }
                }
            });
                 var connector = new SshConnector();
                 var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                var local = Files.writeString(directory.resolve("file"), upload ? "new" : "old");
                var remote = Files.writeString(fixture.remote.resolve("file"), upload ? "old" : "new");
                var result = connection.transfer(new TransferRequest(local, "/file", 3), digest("new".getBytes()),
                    upload, Duration.ofSeconds(5), () -> false, progress -> {
                    }, conflict -> TransferConflictChoice.OVERWRITE, original -> {
                        plan.set(original);
                        return journal().prepare(original);
                    });
                assertTrue(changed.get());
                assertEquals(Outcome.UNKNOWN, result.outcome(), result.toString());
                assertEquals("old", Files.readString(upload ? remote : local));
            }
        }
    }

    @Test
    void discardRechecksNamedPrefixAfterClosingVerifiedHandles() throws Exception {
        for (boolean upload : new boolean[]{true, false}) {
            try (var interrupted = new InterruptedTransfer(root.resolve(Boolean.toString(upload)), upload, 3)) {
                interrupted.start();
                var changed = new AtomicBoolean();
                ((SftpSubsystemFactory) interrupted.fixture.server.server().getSubsystemFactories().getFirst())
                    .addSftpEventListener(new SftpEventListener() {
                        public void closed(ServerSession session, String handle, Handle file, Throwable failure)
                            throws IOException {
                            boolean stagingHandle = file.getFile().getFileName().toString()
                                .startsWith(".wtl-transfer-");
                            if (failure == null
                                && (upload
                                ? stagingHandle
                                : file.getFile().getFileName().toString().equals("file.bin"))
                                && changed.compareAndSet(false, true)) {
                                var bytes = Files.readAllBytes(interrupted.staging());
                                bytes[0] ^= 1;
                                Files.write(interrupted.staging(), bytes);
                            }
                        }
                    });
                var result = interrupted.recover(true);
                assertTrue(changed.get());
                assertEquals(Outcome.UNKNOWN, result.outcome(), result.toString());
                assertTrue(Files.exists(interrupted.staging()));
                assertEquals(0, interrupted.releases.get());
                assertEquals("old", Files.readString(interrupted.target));
            }
        }
    }
}
