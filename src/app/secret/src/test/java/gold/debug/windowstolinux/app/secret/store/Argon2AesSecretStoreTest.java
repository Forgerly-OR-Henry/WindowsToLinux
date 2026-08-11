package gold.debug.windowstolinux.app.secret.store;

import gold.debug.windowstolinux.app.secret.api.SecretStoreException;
import gold.debug.windowstolinux.app.secret.windows.WindowsCredentialManagerSecretStore;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Argon2AesSecretStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void encryptsWithArgon2idAndRejectsTheWrongMasterPassword() throws Exception {
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory);
             Argon2AesSecretStore store = new Argon2AesSecretStore(database, "correct master password".toCharArray())) {
            store.save("ssh/server-one/password", "remote-password".toCharArray());
            assertArrayEquals("remote-password".toCharArray(), store.read("ssh/server-one/password").orElseThrow());
        }
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory);
             Argon2AesSecretStore wrongStore = new Argon2AesSecretStore(database, "wrong master password".toCharArray())) {
            SecretStoreException failure = assertThrows(SecretStoreException.class,
                    () -> wrongStore.read("ssh/server-one/password"));
            assertEquals("secret.decryptFailed", failure.userMessage().key());
            assertFalse(failure.diagnostic().contains("remote-password"));
        }
    }

    @Test
    void credentialManagerCanReadAnAbsentAppCredentialWithoutWriting() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        try (WindowsCredentialManagerSecretStore store = new WindowsCredentialManagerSecretStore()) {
            var result = store.read("verification/" + UUID.randomUUID());
            assertTrue(result.isEmpty(), () -> "read an unexpected value of length "
                    + result.map(value -> value.length).orElse(0));
        }
    }
}
