package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformRemoteCapabilitiesTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.service.linux.PlatformLinuxService;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTransferStallTest {
    @TempDir
    Path root;

    static void stallWrites(Remote remote, int count, List<Long> offsets) {
        var remaining = new AtomicInteger(count);
        ((SftpSubsystemFactory) remote.listener.server().getSubsystemFactories().getFirst())
                .addSftpEventListener(new SftpEventListener() {
                    public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                            int start, int length, Throwable failure) throws IOException {
                        if (failure != null)
                            return;
                        offsets.add(offset);
                        if (remaining.getAndDecrement() <= 0)
                            return;
                        try {
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                            while (session.isOpen() && System.nanoTime() < deadline)
                                Thread.sleep(10);
                            assertFalse(session.isOpen(), "Stalled attempt must close before another client can write");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
    }

    @Test
    void twoExplicitRetriesCloseOldClientsAndReuseOriginalFileLeaseAndVerifiedOffsets() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var offsets = new CopyOnWriteArrayList<Long>();
            stallWrites(remote, 2, offsets);
            var reviews = new Reviews();
            var prompts = new AtomicInteger();
            var conflicts = new AtomicInteger();
            var identity = new AtomicReference<UUID>();
            var local = Files.write(root.resolve("source"), new byte[100000]);
            Files.writeString(remote.files.resolve("file"), "old");
            reviews.conflictPolicy = conflict -> {
                conflicts.incrementAndGet();
                return TransferConflictChoice.OVERWRITE;
            };
            reviews.stallPolicy = stall -> {
                assertEquals(TransferProgress.Stage.TRANSFERRING, stall.progress().stage());
                assertEquals(remote.authentications.get(), prompts.incrementAndGet());
                if (identity.get() == null)
                    identity.set(stall.progress().transferId());
                assertEquals(identity.get(), stall.progress().transferId());
                assertEquals(1L, (long) assertDoesNotThrow(() -> count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL")));
                return TransferStallChoice.RETRY;
            };
            try (var interaction = new PlatformInteractionService(runtime.tasks(), runtime.security(), OWNER, VERSION,
                    reviews);
                    var linux = new PlatformLinuxService(runtime.tasks(), runtime.security(),
                            runtime.persistence().servers(), interaction, OWNER, VERSION, Duration.ofSeconds(5),
                            Duration.ofMillis(650))) {
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    try (var session = linux.connect(SERVER, context)) {
                        var request = new TransferRequest(local, "/file", 100000);
                        var grant = interaction.approve(
                                RemoteActions.transfer(SERVER, "original", request, digest(new byte[100000]), true),
                                context).value().orElseThrow();
                        var result = session.upload(request, grant);
                        assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
                        assertTrue(linux.pendingTransfers(context).isEmpty());
                        return result;
                    }
                });
                assertEquals(Outcome.SUCCEEDED,
                        task.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                assertEquals(2, prompts.get());
                assertEquals(3, remote.authentications.get());
                assertEquals(1, conflicts.get());
                assertEquals(1, reviews.approved.stream().filter(i -> i.type().equals("platform.sftp.upload")).count());
                assertEquals(List.of(0L, 16384L, 32768L), offsets.subList(0, 3));
                assertArrayEquals(new byte[100000], Files.readAllBytes(remote.files.resolve("file")));
                assertEquals(1, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE'"));
                assertEquals(0, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertEquals(0, remote.commands.get());
            }
        }
    }

    @Test
    void terminationRetainsOriginalTaskAndFileUntilExplicitVerifiedDiscard() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            stallWrites(remote, 1, new CopyOnWriteArrayList<>());
            var reviews = new Reviews();
            var prompts = new AtomicInteger();
            reviews.stallPolicy = stall -> {
                prompts.incrementAndGet();
                return TransferStallChoice.TERMINATE;
            };
            try (var interaction = new PlatformInteractionService(runtime.tasks(), runtime.security(), OWNER, VERSION,
                    reviews);
                    var linux = new PlatformLinuxService(runtime.tasks(), runtime.security(),
                            runtime.persistence().servers(), interaction, OWNER, VERSION, Duration.ofSeconds(5),
                            Duration.ofMillis(650))) {
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var original = new AtomicReference<TaskContext>();
                var local = Files.write(root.resolve("source"), new byte[100000]);
                var task = binding.submit(request("server"), context -> {
                    original.set(context);
                    try (var session = linux.connect(SERVER, context)) {
                        var request = new TransferRequest(local, "/file", 100000);
                        var grant = interaction.approve(
                                RemoteActions.transfer(SERVER, "original", request, digest(new byte[100000]), true),
                                context).value().orElseThrow();
                        var result = session.upload(request, grant);
                        assertEquals(Outcome.UNKNOWN, result.outcome());
                        assertEquals("SFTP_STALLED", result.problem().orElseThrow().eventCode());
                        return result;
                    }
                });
                state(task, TaskState.UNKNOWN);
                PlatformServerReferenceTest.callbackReturned(runtime, original.get());
                assertEquals(1, prompts.get());
                assertEquals(1, remote.authentications.get());
                assertEquals(1, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                assertFalse(Files.exists(remote.files.resolve("file")));
                try (var recovery = binding.registerRecovery("fixture", (previous, context) -> {
                    linux.closeTaskResources(context);
                    var checkpoint = linux.pendingTransfers(context).getFirst();
                    try (var session = linux.connect(SERVER, context)) {
                        var grant = interaction
                                .approve(RemoteActions.recoverTransfer(checkpoint, "discard", false), context).value()
                                .orElseThrow();
                        return session.discardTransfer(checkpoint.id(), grant);
                    }
                })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(task.id()).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).state());
                }
                try (var paths = Files.list(remote.files)) {
                    assertEquals(0, paths.count());
                }
                assertEquals(0, remote.commands.get());
            }
        }
    }
}
