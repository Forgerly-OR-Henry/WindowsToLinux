package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Migrated original server trust and profile contracts against real H2 and SSH authentication. / 在真实H2与SSH认证上迁移原服务器信任及配置合同。 */
class PlatformServerContractsTest {
    @TempDir Path root;

    @Test void firstUseDoesNotPersistFailedOrDifferentAuthentication() throws Exception {
        var accepted = new AtomicBoolean(); var authenticated = new AtomicInteger(); var commands = new AtomicInteger();
        try (var runtime = PlatformRuntime.open(root.resolve("database"), PlatformStartupTest.PROTECTION)) {
            var service = PlatformServerServiceTest.setup(runtime); runtime.started();
            int port; gold.debug.windowstolinux.app.db.entity.StoredServerIdentity original;
            Path first = Files.createDirectories(root.resolve("first-host"));
            try (var ssh = new LoopbackSshServer(first, server -> {
                server.setPasswordAuthenticator((user, password, session) -> {
                    authenticated.incrementAndGet(); return accepted.get() && user.equals("alice") && password.equals("fixture-secret");
                });
                server.setCommandFactory((channel, command) -> { commands.incrementAndGet(); throw new AssertionError("Authentication-only check cannot send commands"); });
            })) {
                port = ssh.server().getPort();
                var profile = PlatformServerServiceTest.profile("server", port);
                service.saveServerProfile(profile, "fixture-secret".toCharArray());
                var failed = service.check(profile); var result = failed.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(Outcome.FAILED, result.outcome()); assertEquals(TaskState.ENDED, failed.snapshot().state());
                assertEquals("SSH_AUTHENTICATION_FAILED", result.problem().orElseThrow().eventCode());
                assertTrue(authenticated.get() > 0); assertTrue(runtime.persistence().servers().findServer("server").isEmpty(), "Accepted first-use review alone cannot persist unconfirmed authentication");
                accepted.set(true); var verified = service.check(profile);
                assertEquals(Outcome.SUCCEEDED, verified.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
                assertEquals(TaskState.ENDED, verified.snapshot().state());
                original = runtime.persistence().servers().findServer("server").orElseThrow();
                assertTrue(original.hostKeySha256().startsWith("SHA256:"));
            }
            int authenticatedBefore = authenticated.get();
            Path replacement = Files.createDirectories(root.resolve("different-host-key"));
            try (var ssh = new LoopbackSshServer(replacement, server -> {
                server.setPort(port);
                server.setPasswordAuthenticator((user, password, session) -> { authenticated.incrementAndGet(); return true; });
                server.setCommandFactory((channel, command) -> { commands.incrementAndGet(); throw new AssertionError(); });
            })) {
                assertEquals(port, ssh.server().getPort());
                var changed = service.check(PlatformServerServiceTest.profile("server", port));
                var rejected = changed.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(Outcome.FAILED, rejected.outcome()); assertEquals(TaskState.ENDED, changed.snapshot().state());
                assertEquals("SSH_HOST_KEY_REJECTED", rejected.problem().orElseThrow().eventCode());
                assertEquals(authenticatedBefore, authenticated.get(), "A different physical host key cannot reach authentication or overwrite approved trust");
                assertEquals(Optional.of(original), runtime.persistence().servers().findServer("server"));
            }
            assertEquals(0, commands.get()); assertTrue(service.checks().isEmpty());
            assertEquals(0, PlatformTaskAdmissionIntegrationTest.count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test void renamingRetainsTheCredentialAndChangingItsReferenceRequiresANewPassword() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("database"), PlatformStartupTest.PROTECTION)) {
            var service = PlatformServerServiceTest.setup(runtime); runtime.started();
            var first = new ServerProfile("first", "example.test", 22, "tester", "custom/key");
            char[] password = "fixture-password".toCharArray(); service.saveServerProfile(first, password);
            assertArrayEquals(new char[password.length], password);
            byte[] before;
            try (var row = runtime.persistence().security().find(first.credentialKey()).orElseThrow()) { before = row.payload(); }
            var renamed = new ServerProfile(first.id(), first.host(), first.sshPort(), first.username(), first.credentialKey(), "Production");
            service.saveServerProfile(renamed, new char[0]);
            try (var row = runtime.persistence().security().find(first.credentialKey()).orElseThrow()) { assertArrayEquals(before, row.payload()); }
            assertEquals(renamed, service.list().getFirst().profile());
            assertThrows(IllegalArgumentException.class, () -> service.saveServerProfile(new ServerProfile(first.id(), first.host(), 22, first.username(), "new/key", "Production"), new char[0]));
            assertEquals(renamed, service.list().getFirst().profile());
            assertTrue(runtime.persistence().security().find("new/key").isEmpty());
            assertEquals(0, PlatformTaskAdmissionIntegrationTest.count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"), "Configuration save, rename and rejection never connect or create a check task");
        }
    }
}
