package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ResourceLease;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformServerServiceTest {
    @TempDir
    Path root;

    static final String HOST = PlatformTaskAdmission.PLATFORM_OWNER;

    static final String TYPE = "platform.ssh.check.v1";

    static PlatformServerService setup(PlatformRuntime runtime) throws Exception {
        runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
        return runtime.servers();
    }

    static ServerProfile profile(String id, int port) {
        return new ServerProfile(id, "127.0.0.1", port, "alice", "ssh/" + id, "本机验证 " + id);
    }

    static Optional<String> secret(PlatformRuntime runtime, String key) throws Exception {
        try (var operation = runtime.security().enterOperation(); var store = runtime.security().openCredentials()) {
            var value = store.read(key);
            try {
                return value.map(String::new);
            } finally {
                value.ifPresent(chars -> Arrays.fill(chars, '\0'));
            }
        }
    }

    @Test
    void settingsDoNotConnectAndActualHostVersionChecksAuthenticationWithoutCommands() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            var profile = profile("server", remote.listener.server().getPort());
            char[] password = "fixture-secret".toCharArray();
            service.saveServerProfile(profile, password);
            assertArrayEquals(new char[password.length], password);
            assertEquals(0, remote.authentications.get());
            assertEquals(0, remote.commands.get());
            assertThrows(ApiException.class, () -> service.check(profile));
            assertFalse(runtime.version().contains("${"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
            runtime.started();
            var wrong = runtime.tasks().bind(HOST, "wrong-artifact", false);
            assertThrows(ApiException.class, () -> wrong.submit(request("server"), task -> success(null)));
            var handle = service.check(profile);
            var result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            assertEquals(HOST, handle.snapshot().ownerId());
            assertEquals(runtime.version(), handle.snapshot().ownerVersion());
            assertEquals("", result.value().orElseThrow().operatingSystem());
            assertTrue(result.value().orElseThrow().fingerprint().startsWith("SHA256:"));
            assertEquals(1, remote.authentications.get());
            assertEquals(0, remote.commands.get());
            assertTrue(service.list().getFirst().connected());
            assertTrue(service.list().getFirst().checkedAt().isPresent());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
            assertTrue(service.checks().isEmpty());
            service.saveServerProfile(new ServerProfile(profile.id(), profile.host(), profile.sshPort(),
                profile.username(), profile.credentialKey(), "重命名"), new char[0]);
            assertEquals("fixture-secret", secret(runtime, profile.credentialKey()).orElseThrow());
            assertEquals(1, remote.authentications.get());
            service.delete(profile.id());
            assertTrue(service.list().isEmpty());
            assertTrue(secret(runtime, profile.credentialKey()).isEmpty());
        }
    }

    @Test
    void tasksFreezeEndpointAndDeleteAndFailedProfileWriteRollsBackCredentialChange() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            runtime.started();
            var profile = profile("server", 2222);
            service.saveServerProfile(profile, "original-secret".toCharArray());
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var binding = runtime.tasks().bind(HOST, runtime.version(), false);
            var handle = binding.submit(request("server"), task -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return success(null);
            });
            try {
                assertTrue(entered.await(8, TimeUnit.SECONDS));
                char[] replacement = "replacement-secret".toCharArray();
                assertThrows(SQLException.class, () -> service.saveServerProfile(profile("server", 3333), replacement));
                assertArrayEquals(new char[replacement.length], replacement);
                assertEquals("original-secret", secret(runtime, profile.credentialKey()).orElseThrow());
                assertThrows(SQLException.class, () -> service.delete(profile.id()));
                var queued = binding.submit(request("server"), task -> {
                    fail("Queued callback ran");
                    return success(null);
                });
                assertEquals(TaskState.QUEUED, queued.snapshot().state());
                queued.requestCancel();
                assertEquals(Outcome.CANCELLED,
                    queued.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
            } finally {
                release.countDown();
            }
            assertEquals(Outcome.SUCCEEDED,
                handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
            service.delete(profile.id());
            assertTrue(service.list().isEmpty());
        }
    }

    @Test
    void persistentReferencesBlockDeleteAndSharedCredentialsRemainOwned() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
            var service = runtime.servers();
            var db = runtime.persistence().database();
            var reference = UUID.randomUUID();
            sql(db, "INSERT INTO mod_server_reference(reference_id,server_profile_id,mod_id,schema_version,schema_generation,business_key,state,updated_at) VALUES(?,'server',?,'1',?,'fixture','RESERVED',1)",
                reference, OWNER, schema.generation());
            for (var state : List.of("RESERVED", "ACTIVE", "UNKNOWN")) {
                sql(db, "UPDATE mod_server_reference SET state=? WHERE reference_id=?", state, reference);
                assertThrows(SQLException.class, () -> service.delete("server"));
            }
            sql(db, "DELETE FROM mod_server_reference WHERE reference_id=?", reference);
            sql(db, "INSERT INTO mod_credential_reference(reference_id,credential_id,mod_id,schema_version,schema_generation,business_key,purpose,state,updated_at) VALUES(?,'credential',?,'1',?,'fixture','test','UNKNOWN',1)",
                UUID.randomUUID(), OWNER, schema.generation());
            service.delete("server");
            assertEquals("fixture-secret", secret(runtime, "credential").orElseThrow());
            var one = profile("one", 22);
            service.saveServerProfile(one, "shared-secret".toCharArray());
            runtime.persistence().servers().saveServerProfile(
                new ServerProfile("two", "127.0.0.1", 23, "alice", one.credentialKey(), "共享").stored());
            service.delete("one");
            assertEquals("shared-secret", secret(runtime, one.credentialKey()).orElseThrow());
            service.delete("two");
            assertTrue(secret(runtime, one.credentialKey()).isEmpty());
        }
    }

    @Test
    void cancellationDuringFirstTrustReleasesRealCheckAndDoesNotAuthenticate() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var entered = new CountDownLatch(1);
            runtime.setInteractionPresenter(new PlatformInteractionTest.Prompts() {
                public gold.debug.windowstolinux.appapi.linux.TransferConflictChoice transferConflict(
                    gold.debug.windowstolinux.appapi.linux.TransferConflict conflict,
                    gold.debug.windowstolinux.appapi.server.ServerRef server, TaskSnapshot task,
                    CancelToken cancellation) {
                    return gold.debug.windowstolinux.appapi.linux.TransferConflictChoice.SKIP;
                }

                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                    entered.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (!cancel.requested() && System.nanoTime() < deadline)
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                    assertTrue(cancel.requested());
                    return false;
                }

                public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                                   CancelToken cancel) {
                    return null;
                }
            });
            var service = runtime.servers();
            runtime.started();
            var profile = profile("cancel", remote.listener.server().getPort());
            service.saveServerProfile(profile, "fixture-secret".toCharArray());
            var handle = service.check(profile);
            try {
                assertTrue(entered.await(8, TimeUnit.SECONDS));
                assertThrows(SQLException.class, () -> service.delete(profile.id()));
            } finally {
                service.cancel(handle.id());
            }
            assertEquals(Outcome.CANCELLED,
                handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(0, remote.authentications.get());
            assertEquals(0, remote.commands.get());
            assertTrue(runtime.persistence().servers().findServer(profile.id()).isEmpty());
            service.delete(profile.id());
        }
    }

    @Test
    void earlierHostVersionReconcilesOnlyKnownProcessResourcesWithoutAnyConnectionReplay() throws Exception {
        UUID known, unexpected, foreign, extra;
        try (var db = PlatformDatabase.open(root, PlatformStartupTest.PROTECTION)) {
            var persistence = new PlatformPersistence(db);
            try (var security = new DesktopSecurityService(persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            var ledger = new TaskLedger(db);
            known = old(ledger, HOST, TYPE);
            ledger.retain(known, new TaskResource("CONNECTION", "ssh-" + UUID.randomUUID(), HOST, false),
                new ResourceRecovery("ssh.v1", Map.of("bootId", db.bootId().toString(), "serverId", "fixture")),
                false);
            ledger.retain(known, new TaskResource("CUSTOM", "interaction-" + UUID.randomUUID(), HOST, false));
            unexpected = old(ledger, HOST, TYPE);
            extra = ledger.retain(unexpected, new TaskResource("CUSTOM", "unrecognized-resource", HOST, false));
            foreign = old(ledger, "example.foreign", TYPE);
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            var reviews = new PlatformRemoteCapabilitiesTest.Reviews();
            runtime.setInteractionPresenter(reviews);
            var service = runtime.servers();
            assertEquals(2, service.checks().size());
            var done = service.reconcile(known).toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, done.state());
            assertEquals(Outcome.FAILED, done.outcome().orElseThrow());
            assertEquals("0.0.1-original", done.ownerVersion());
            assertTrue(reviews.approved.isEmpty());
            assertThrows(ApiException.class, () -> service.reconcile(foreign));
            assertEquals(TaskState.UNKNOWN,
                service.reconcile(unexpected).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id='unrecognized-resource' AND released_at IS NULL"));
            new TaskLedger(runtime.persistence().database()).release(unexpected, extra);
            assertEquals(TaskState.ENDED,
                service.reconcile(unexpected).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            try (var recovery = runtime.tasks().bind("example.foreign", "0.0.1-original", true).registerRecovery(TYPE,
                (previous, task) -> success(null))) {
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(foreign).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
        }
    }

    static UUID old(TaskLedger ledger, String owner, String type) throws Exception {
        var id = ledger.enqueue(UUID.randomUUID(), owner, "0.0.1-original",
            new TaskRequest(type, "Previous check", List.of(), true),
            List.of(new TaskResource("MOD", owner, owner, false)));
        assertTrue(ledger.tryStart(id));
        return id;
    }

    @Test
    void currentBootResourcesAreNotDiscardedByInterruptedCheckRecovery() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            runtime.started();
            var lease = new AtomicReference<ResourceLease>();
            var handle = runtime.tasks().bind(HOST, runtime.version(), false)
                .submit(new TaskRequest(TYPE, "Current host check", List.of(), true), task -> {
                    lease.set(runtime.tasks().retain(task,
                        new TaskResource("CONNECTION", "ssh-" + UUID.randomUUID(), HOST, false)));
                    throw new IllegalStateException("Injected after physical open");
                });
            try {
                state(handle, TaskState.UNKNOWN);
                assertEquals(TaskState.FINISHING,
                    service.reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertThrows(SQLException.class, runtime::close);
            } finally {
                if (lease.get() != null)
                    lease.get().close();
            }
            assertEquals(Outcome.FAILED, handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
        }
    }
}
