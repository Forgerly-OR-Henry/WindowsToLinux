package gold.debug.windowstolinux.app.service.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerProfileEditingTest {
    @TempDir
    Path directory;

    @Test
    void renamingRetainsTheCredentialAndStorageChangesRequireANewPassword() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        SecretStore store = new SecretStore() {
            public void save(String key, char[] value) {
                writes.incrementAndGet();
            }

            public Optional<char[]> read(String key) {
                throw new AssertionError("rename does not read secrets");
            }

            public boolean delete(String key) {
                throw new AssertionError("rename does not delete secrets");
            }

            public void close() {
            }
        };
        try (var db = DesktopPersistence.open(directory)) {
            var service = new ServerUseCaseFacade(db.servers(), new DesktopSecretStoreService(db.encryptedSecrets()),
                    (endpoint, credential, verifier) -> {
                        throw new AssertionError("save does not connect");
                    });
            var first = new ServerProfile("first", "example.test", 22, "tester", "custom/key",
                    CredentialStorageMode.MASTER_PASSWORD);
            char[] password = "fixture-password".toCharArray();
            service.save(first, store, password);
            assertArrayEquals(new char[password.length], password);
            var renamed = new ServerProfile(first.id(), first.host(), first.sshPort(), first.username(),
                    first.credentialKey(), first.credentialMode(), "Production");
            service.save(renamed, store, new char[0]);
            assertEquals(1, writes.get());
            assertEquals(renamed, service.find("first").orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> service.save(new ServerProfile(first.id(), first.host(),
                    22, first.username(), "new/key", first.credentialMode(), "Production"), store, new char[0]));
        }
    }
}
