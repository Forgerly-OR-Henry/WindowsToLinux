package gold.debug.windowstolinux.app.service.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyObservation;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthenticatedHostKeyMigrationTest {
    @TempDir
    Path directory;

    private static final ServerProfile PROFILE = new ServerProfile("fixture", "example.test", 22, "root",
            "ssh/fixture/password", CredentialStorageMode.MASTER_PASSWORD);

    @Test
    void migratesTheSameKeyOnlyAfterAuthenticationAndRejectsChangedKeys() throws Exception {
        try (var database = DesktopPersistence.open(directory)) {
            var prior = new ServerIdentity(PROFILE.id(), PROFILE.host(), 22, "SHA256:historical-key");
            database.servers().saveServer(prior);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("windowstolinux.db"));
                    var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE server SET host_key_format='LEGACY_X509'");
            }
            var service = new ServerUseCaseFacade(database.servers(),
                    new DesktopSecretStoreService(database.encryptedSecrets()), (endpoint, credential, verifier) -> {
                        throw new AssertionError("No network needed");
                    });
            var observation = new HostKeyObservation("SHA256:standard-same-key", prior.hostKeySha256());
            var verifier = service.hostKeyVerifier(PROFILE, ignored -> {
                throw new AssertionError("Existing trust must not prompt");
            });
            assertEquals(HostKeyDecision.ACCEPT_EXISTING, verifier.verify(PROFILE.endpoint(), observation));
            assertEquals(prior, database.servers().findServer(PROFILE.id()).orElseThrow());
            assertTrue(database.servers().hasLegacyHostKey(prior));
            assertTrue(verifier.authenticated(PROFILE.endpoint(), observation));
            var migrated = database.servers().findServer(PROFILE.id()).orElseThrow();
            assertEquals(observation.sshSha256(), migrated.hostKeySha256());
            assertFalse(database.servers().hasLegacyHostKey(migrated));
            assertEquals(HostKeyDecision.REJECT, service.hostKeyVerifier(PROFILE, ignored -> true)
                    .verify(PROFILE.endpoint(), new HostKeyObservation("SHA256:changed-key", prior.hostKeySha256())));
            assertThrows(java.sql.SQLException.class, () -> database.servers().saveServer(prior));
        }
    }

    @Test
    void firstUseDoesNotPersistFailedOrDifferentAuthentication() throws Exception {
        try (var database = DesktopPersistence.open(directory)) {
            var service = new ServerUseCaseFacade(database.servers(),
                    new DesktopSecretStoreService(database.encryptedSecrets()), (endpoint, credential, verifier) -> {
                        throw new AssertionError("No network needed");
                    });
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
