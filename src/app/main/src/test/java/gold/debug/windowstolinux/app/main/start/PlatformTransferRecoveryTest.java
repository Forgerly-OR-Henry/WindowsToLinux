package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.ResourceLease;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static gold.debug.windowstolinux.app.main.start.PlatformRemoteCapabilitiesTest.digest;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.*;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

import gold.debug.windowstolinux.app.linux.sftp.TransferPlan;
import gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector;
import gold.debug.windowstolinux.app.linux.ssh.connect.SshEndpoint;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.app.db.server.StoredServerIdentity;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTransferRecoveryTest {
    @TempDir
    Path root;

    static final ServerRef SERVER = new ServerRef("server");

    static void interruptOnce(PlatformRemoteCapabilitiesTest.Remote remote) {
        var once = new AtomicBoolean(true);
        ((SftpSubsystemFactory) remote.listener.server().getSubsystemFactories().getFirst())
            .addSftpEventListener(new SftpEventListener() {
                public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                    int start, int length, Throwable failure) {
                    if (failure == null && once.compareAndSet(true, false))
                        session.close(true);
                }
            });
    }

    @Test
    void originalTaskDiscardChecksExactApprovalForeignOwnerAndOtherCapabilityConnections() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            interruptOnce(remote);
            var reviews = new PlatformRemoteCapabilitiesTest.Reviews();
            reviews.conflictPolicy = conflict -> TransferConflictChoice.OVERWRITE;
            runtime.setInteractionPresenter(reviews);
            Files.writeString(remote.files.resolve("file"), "old");
            var source = Files.write(root.resolve("source"), new byte[100000]);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var otherInstance = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var foreign = runtime.capabilities().remoteCapabilities("other.owner", VERSION)) {
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var original = new AtomicReference<TaskContext>();
                var task = binding.submit(request("server"), context -> {
                    original.set(context);
                    try (var session = capabilities.linux().connect(SERVER, context)) {
                        var request = new TransferRequest(source, "/file", 100000);
                        var grant = capabilities.interaction().approve(
                            RemoteActions.transfer(SERVER, "original", request, digest(new byte[100000]), true),
                            context).value().orElseThrow();
                        assertEquals(Outcome.UNKNOWN, session.upload(request, grant).outcome());
                        assertEquals(1, capabilities.linux().pendingTransfers(context).size());
                    }
                    return success(null);
                });
                state(task, TaskState.UNKNOWN);
                PlatformServerReferenceTest.callbackReturned(runtime, original.get());
                assertThrows(ApiException.class, () -> capabilities.linux().pendingTransfers(original.get()));
                try (var recovery = binding.registerRecovery("fixture", (previous, context) -> {
                    capabilities.closeTaskResources(context);
                    assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class, () -> foreign.linux().pendingTransfers(context)).problem()
                            .code());
                    var checkpoint = capabilities.linux().pendingTransfers(context).getFirst();
                    try (var session = capabilities.linux().connect(SERVER, context)) {
                        var resumeGrant = capabilities.interaction()
                            .approve(RemoteActions.recoverTransfer(checkpoint, "recovery", true), context).value()
                            .orElseThrow();
                        assertThrows(ApiException.class, () -> session.discardTransfer(checkpoint.id(), resumeGrant));
                        var discardGrant = capabilities.interaction()
                            .approve(RemoteActions.recoverTransfer(checkpoint, "recovery", false), context).value()
                            .orElseThrow();
                        try (var competing = otherInstance.linux().connect(SERVER, context)) {
                            assertEquals(ProblemCode.BUSY,
                                assertThrows(ApiException.class,
                                    () -> session.discardTransfer(checkpoint.id(), discardGrant)).problem()
                                    .code());
                        }
                        assertEquals(ProblemCode.NOT_FOUND,
                            assertThrows(ApiException.class,
                                () -> session.discardTransfer(UUID.randomUUID(), discardGrant)).problem()
                                .code());
                        assertEquals(Outcome.SUCCEEDED,
                            session.discardTransfer(checkpoint.id(), discardGrant).outcome());
                        assertTrue(capabilities.linux().pendingTransfers(context).isEmpty());
                        assertThrows(ApiException.class, () -> session.discardTransfer(checkpoint.id(), discardGrant));
                    }
                    return success(null);
                })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(task.id()).toCompletableFuture()
                        .get(12, TimeUnit.SECONDS).state());
                }
                assertEquals("old", Files.readString(remote.files.resolve("file")));
                try (var files = Files.list(remote.files)) {
                    assertEquals(1, files.count());
                }
                assertEquals(0, remote.commands.get());
            }
        }
    }

    @Test
    void previousBootResumesDurableOriginalPlanAfterReleasingOnlyVanishedSshClients() throws Exception {
        var data = root.resolve("data");
        var source = Files.write(root.resolve("source"), new byte[100000]);
        UUID task;
        var recorded = new AtomicReference<TransferPlan>();
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"))) {
            interruptOnce(remote);
            try (var db = PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
                var persistence = new PlatformPersistence(db);
                try (var security = new DesktopSecurityService(persistence.security())) {
                    security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
                    try (var operation = security.enterOperation(); var credentials = security.openCredentials()) {
                        credentials.save("credential", "fixture-secret".toCharArray());
                    }
                }
                register(db, null);
                persistence.servers().saveServerProfile(new StoredServerProfile("server", "127.0.0.1",
                    remote.listener.server().getPort(), "alice", "credential", "回环恢复"));
                var ledger = new TaskLedger(db);
                task = ledger.enqueue(UUID.randomUUID(), OWNER, VERSION, request("server"),
                    List.of(new TaskResource("MOD", OWNER, OWNER, false),
                        new TaskResource("SERVER", "server", OWNER, true)));
                assertTrue(ledger.tryStart(task));
                var connectionLease = ledger.retain(task,
                    new TaskResource("CONNECTION", "ssh-" + UUID.randomUUID(), OWNER, false),
                    new ResourceRecovery("ssh.v1", Map.of("bootId", db.bootId().toString(), "serverId", "server")),
                    false);
                ledger.releasePreviousSshConnections(task, OWNER, VERSION);
                assertTrue(ledger.queries().soleSshConnection(task, connectionLease),
                    "Current boot must retain the actual client");
                try (var connector = new SshConnector();
                     var connection = connector.connect(
                         new SshEndpoint("server", "127.0.0.1", remote.listener.server().getPort(), "alice"),
                         "fixture-secret".toCharArray(), (endpoint, fingerprint) -> true, Duration.ofSeconds(5),
                         () -> false)) {
                    persistence.servers().saveServer(new StoredServerIdentity("server", "127.0.0.1",
                        remote.listener.server().getPort(), connection.fingerprint()));
                    var result = connection.transfer(new TransferRequest(source, "/file", 100000),
                        digest(new byte[100000]), true, Duration.ofSeconds(5), () -> false, progress -> {
                        }, conflict -> TransferConflictChoice.SKIP, plan -> {
                            recorded.set(plan);
                            var fields = new TreeMap<>(plan.recoveryFields());
                            fields.put("serverId", "server");
                            fields.put("fingerprint", connection.fingerprint());
                            var lease = ledger.retain(task,
                                new TaskResource("FILE", "sftp:" + plan.id(), OWNER, true),
                                new ResourceRecovery("sftp.v1", fields), false);
                            return new ResourceLease() {
                                public UUID id() {
                                    return lease;
                                }

                                public void close() {
                                    fail("Interrupted file must remain retained");
                                }
                            };
                        });
                    assertEquals(Outcome.UNKNOWN, result.outcome());
                }
            }
            try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
                try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                    var binding = runtime.tasks().bind(OWNER, VERSION, true);
                    try (var recovery = binding.registerRecovery("fixture", (previous, context) -> {
                        var checkpoint = capabilities.linux().pendingTransfers(context).getFirst();
                        assertEquals(recorded.get().id(), checkpoint.id());
                        assertEquals(1, capabilities.linux().pendingTransfers(context).size());
                        try (var session = capabilities.linux().connect(SERVER, context)) {
                            var grant = capabilities.interaction()
                                .approve(RemoteActions.recoverTransfer(checkpoint, "recovery", true), context)
                                .value().orElseThrow();
                            var result = session.resumeTransfer(checkpoint.id(), grant);
                            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
                        }
                        assertTrue(capabilities.linux().pendingTransfers(context).isEmpty());
                        return success(null);
                    })) {
                        assertEquals(TaskState.ENDED, runtime.tasks().reconcile(task).toCompletableFuture()
                            .get(12, TimeUnit.SECONDS).state());
                    }
                }
                assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
            assertArrayEquals(new byte[100000], Files.readAllBytes(remote.files.resolve("file")));
            assertEquals(0, remote.commands.get());
        }
    }
}
