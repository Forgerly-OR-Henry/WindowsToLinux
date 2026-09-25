package gold.debug.windowstolinux.app.service.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.service.SecurityTestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerProfileEditingTest extends SecurityTestFixture {
    @TempDir
    Path directory;

    @Test
    void renamingRetainsTheCredentialAndChangingItsReferenceRequiresANewPassword() throws Exception {
        try (var db = DesktopPersistence.open(directory); var store = SecurityTestFixture.store(db)) {
            var service = new ServerUseCaseFacade(db.servers(), SecurityTestFixture.secrets(db),
                    (endpoint, credential, verifier) -> {
                        throw new AssertionError("save does not connect");
                    });
            var first = new ServerProfile("first", "example.test", 22, "tester", "custom/key");
            char[] password = "fixture-password".toCharArray();
            service.save(first, store, password);
            assertArrayEquals(new char[password.length], password);
            byte[] before;
            try (var row = db.security().find(first.credentialKey()).orElseThrow()) {
                before = row.payload();
            }
            var renamed = new ServerProfile(first.id(), first.host(), first.sshPort(), first.username(),
                    first.credentialKey(), "Production");
            service.save(renamed, store, new char[0]);
            try (var row = db.security().find(first.credentialKey()).orElseThrow()) {
                assertArrayEquals(before, row.payload());
            }
            assertEquals(renamed, service.find("first").orElseThrow());
            assertThrows(IllegalArgumentException.class,
                    () -> service.save(
                            new ServerProfile(first.id(), first.host(), 22, first.username(), "new/key", "Production"),
                            store, new char[0]));
        }
    }
}
