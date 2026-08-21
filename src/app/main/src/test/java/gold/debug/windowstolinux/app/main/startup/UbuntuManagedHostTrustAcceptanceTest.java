package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.service.deployment.*;
import gold.debug.windowstolinux.app.service.execution.lifecycle.*;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.source.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live SSH trust test; it never changes the target host or deploys an application.
 *
 * <p>可选实时 SSH 信任测试；它绝不修改目标主机或部署应用。
 */
@EnabledIfSystemProperty(named = "managed.runtime.host-trust", matches = "true")
class UbuntuManagedHostTrustAcceptanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsTheRealFirstUseKeyThenBlocksAMismatchedTrustedKeyWithoutCredentialLeakage() throws Exception {
        String host = System.getProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "ubuntu");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertPresent(host, "managed.ssh.host");
        assertPresent(username, "managed.ssh.user");
        assertPresent(password, "WINDOWSTOLINUX_TEST_SSH_PASSWORD");

        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), new SshdLinuxGateway());
            ServerProfile profile = new ServerProfile("ubuntu-managed-host-trust", host, 22, username,
                    "ssh/ubuntu-managed-host-trust/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-host-trust-master".toCharArray(), password.toCharArray());

            AtomicReference<String> observedFirstUse = new AtomicReference<>();
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-host-trust-master".toCharArray(), fingerprint -> {
                        observedFirstUse.set(fingerprint);
                        return true;
                    });
            assertTrue(capabilities.operatingSystem().contains("Ubuntu 24.04"),
                    () -> "受管部署仅接受 Ubuntu 24.04：" + capabilities.operatingSystem());
            assertEquals("x86_64", capabilities.architecture(), "受管部署仅接受 x86_64 目标机");
            ServerIdentity trusted = service.findTrustedServer(profile.id()).orElseThrow();
            assertEquals(observedFirstUse.get(), trusted.hostKeySha256());
            assertTrue(trusted.hostKeySha256().startsWith("SHA256:"), "real host fingerprint must be persisted in SHA-256 form");

            AtomicBoolean unexpectedPrompt = new AtomicBoolean();
            service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-host-trust-master".toCharArray(), fingerprint -> {
                        unexpectedPrompt.set(true);
                        return false;
                    });
            assertFalse(unexpectedPrompt.get(), "an unchanged trusted key must not ask for first-use approval again");

            String mismatchedFingerprint = "SHA256:" + "x".repeat(43);
            database.servers().saveServer(new ServerIdentity(profile.id(), host, 22, mismatchedFingerprint));
            LinuxOperationException rejection = assertThrows(LinuxOperationException.class,
                    () -> service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                            "managed-host-trust-master".toCharArray(), fingerprint -> true));
            assertEquals("linux.error.hostKeyRejected", rejection.failure().userMessage().key(),
                    "mismatched trust must be reported as a host-key rejection");
            assertFalse(rejection.failure().diagnostic().contains(password), "credential must not appear in rejection evidence");
            assertEquals(mismatchedFingerprint, database.servers().findServer(profile.id()).orElseThrow().hostKeySha256(),
                    "a real observed key must never overwrite a mismatched saved trust record");
        }
    }

    private static void assertPresent(String value, String name) {
        assertTrue(value != null && !value.isBlank(), name + " is required");
    }
}
