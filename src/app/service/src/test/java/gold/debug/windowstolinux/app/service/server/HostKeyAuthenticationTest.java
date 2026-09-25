package gold.debug.windowstolinux.app.service.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.SecurityTestFixture;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyObservation;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostKeyAuthenticationTest extends SecurityTestFixture {
    @TempDir
    Path directory;

    private static final ServerProfile PROFILE = new ServerProfile("fixture", "example.test", 22, "root",
            "ssh/fixture/password");

    @Test
    void firstUseDoesNotPersistFailedOrDifferentAuthentication() throws Exception {
        try (var database = DesktopPersistence.open(directory)) {
            var service = new ServerUseCaseFacade(database.servers(), SecurityTestFixture.secrets(database),
                    (endpoint, credential, verifier) -> {
                        throw new AssertionError("No network needed");
                    });
            try (var store = SecurityTestFixture.store(database)) {
                service.save(PROFILE, store, "ssh-password".toCharArray());
            }
            var key = new HostKeyObservation("SHA256:standard-key", "SHA256:encoded-key");
            var verifier = service.hostKeyVerifier(PROFILE, ignored -> true);
            assertEquals(HostKeyDecision.ACCEPT_FIRST_USE, verifier.verify(PROFILE.endpoint(), key));
            assertTrue(database.servers().findServer(PROFILE.id()).isEmpty());
            assertFalse(verifier.authenticated(PROFILE.endpoint(),
                    new HostKeyObservation("SHA256:other-key", "SHA256:other-encoded")));
            assertTrue(database.servers().findServer(PROFILE.id()).isEmpty());
            assertTrue(verifier.authenticated(PROFILE.endpoint(), key));
        }
    }
}
