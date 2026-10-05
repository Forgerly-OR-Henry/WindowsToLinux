package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.server.StoredServerIdentity;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformFixedTargetTest {
    @TempDir
    Path root;

    static <T> gold.debug.windowstolinux.appapi.task.result.OperationResult<T> result(
        gold.debug.windowstolinux.appapi.task.run.TaskHandle<T> handle) throws Exception {
        return handle.completion().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    static RemoteTarget target(TaskContext task, PlatformRemoteCapabilitiesTest.Remote remote, Optional<String> fingerprint) {
        return new RemoteTarget(PlatformRemoteCapabilitiesTest.SERVER, task.id(), "127.0.0.1",
            remote.listener.server().getPort(), "alice", fingerprint);
    }

    @Test
    void firstTrustThenPhysicallyFreshPinnedAuthentication() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new PlatformRemoteCapabilitiesTest.Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    String observed;
                    try (var session = capabilities.linux().connect(target(context, remote, Optional.empty()), context)) {
                        observed = session.facts().fingerprint();
                    }
                    try (var session = capabilities.linux().connect(target(context, remote, Optional.of(observed)), context)) {
                        assertEquals(observed, session.facts().fingerprint());
                    }
                    return success(null);
                });
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, result(task).outcome());
                assertEquals(2, remote.authentications.get());
                assertEquals(1, reviews.approved.stream().filter(value -> value.type().equals("platform.ssh.trust")).count());
                assertEquals(0, remote.commands.get());
            }
        }
    }

    @Test
    void changedInitialHostPortOrUserRejectsBeforeAnyAuthentication() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var failure = new AtomicReference<Throwable>();
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    try {
                        var original = target(context, remote, Optional.empty());
                        for (var profile : java.util.List.of(
                            new StoredServerProfile("server", "localhost", original.port(), "alice", "credential", "changed"),
                            new StoredServerProfile("server", "127.0.0.1", original.port() == 65535 ? 65534 : original.port() + 1, "alice", "credential", "changed"),
                            new StoredServerProfile("server", "127.0.0.1", original.port(), "bob", "credential", "changed"))) {
                            assertThrows(java.sql.SQLException.class, () -> runtime.persistence().servers().saveServerProfile(profile));
                            // Corrupt only this isolated fixture to exercise the independent authentication guard.
                            sql(runtime.persistence().database(), "UPDATE server_profile SET host=?,ssh_port=?,username=? WHERE server_profile_id='server'",
                                profile.host(), profile.sshPort(), profile.username());
                            assertEquals("SSH_ORIGINAL_TARGET_CHANGED", assertThrows(ApiException.class,
                                () -> capabilities.linux().connect(original, context)).problem().eventCode());
                        }
                    } catch (Throwable caught) {
                        failure.set(caught);
                    }
                    return success(null);
                });
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, result(task).outcome());
                if (failure.get() != null)
                    throw new AssertionError("Fixed target fixture callback failed", failure.get());
                assertEquals(0, remote.authentications.get());
                assertEquals(0, remote.commands.get());
            }
        }
    }

    @Test
    void changedStoredTrustFailsBeforeAuthenticationAndNeverOffersReplacement() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new PlatformRemoteCapabilitiesTest.Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var failure = new AtomicReference<Throwable>();
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    try {
                        String fingerprint;
                        try (var session = capabilities.linux().connect(target(context, remote, Optional.empty()), context)) {
                            fingerprint = session.facts().fingerprint();
                        }
                        var original = target(context, remote, Optional.of(fingerprint));
                        assertThrows(java.sql.SQLException.class, () -> runtime.persistence().servers().saveServer(new StoredServerIdentity("server", "127.0.0.1", original.port(),
                            "SHA256:" + "A".repeat(43))));
                        sql(runtime.persistence().database(), "UPDATE server_profile SET host_key_sha256=? WHERE server_profile_id='server'", "SHA256:" + "A".repeat(43));
                        assertEquals("SSH_ORIGINAL_TRUST_CHANGED", assertThrows(ApiException.class,
                            () -> capabilities.linux().connect(original, context)).problem().eventCode());
                    } catch (Throwable caught) {
                        failure.set(caught);
                    }
                    return success(null);
                });
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, result(task).outcome());
                if (failure.get() != null)
                    throw new AssertionError("Fixed trust fixture callback failed", failure.get());
                assertEquals(1, remote.authentications.get());
                assertEquals(1, reviews.approved.size());
            }
        }
    }

    @Test
    void changedWireKeyRejectsBeforeSendingPassword() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new PlatformRemoteCapabilitiesTest.Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    var wrong = "SHA256:" + "A".repeat(43);
                    runtime.persistence().servers().saveServer(new StoredServerIdentity("server", "127.0.0.1",
                        remote.listener.server().getPort(), wrong));
                    assertEquals("SSH_HOST_KEY_REJECTED", assertThrows(ApiException.class,
                        () -> capabilities.linux().connect(target(context, remote, Optional.of(wrong)), context)).problem().eventCode());
                    return success(null);
                });
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, result(task).outcome());
                assertEquals(0, remote.authentications.get());
                assertTrue(reviews.approved.isEmpty());
            }
        }
    }

    @Test
    void copiedExpectationCannotAuthorizeForeignOrDifferentTask() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var foreign = runtime.capabilities().remoteCapabilities("foreign.owner", VERSION)) {
                var old = new AtomicReference<RemoteTarget>();
                var first = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    old.set(target(context, remote, Optional.empty()));
                    assertThrows(ApiException.class, () -> foreign.linux().connect(old.get(), context));
                    return success(null);
                });
                result(first);
                var second = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    assertEquals("SSH_ORIGINAL_TASK_CHANGED", assertThrows(ApiException.class,
                        () -> capabilities.linux().connect(old.get(), context)).problem().eventCode());
                    return success(null);
                });
                result(second);
                assertEquals(0, remote.authentications.get());
            }
        }
    }
}
