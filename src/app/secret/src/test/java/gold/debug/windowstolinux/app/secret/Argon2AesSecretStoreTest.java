package gold.debug.windowstolinux.app.secret;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory);
             Argon2AesSecretStore store = new Argon2AesSecretStore(database.encryptedSecrets(),
                     "correct master password".toCharArray())) {
            store.save("ssh/server-one/password", "remote-password".toCharArray());
            assertArrayEquals("remote-password".toCharArray(), store.read("ssh/server-one/password").orElseThrow());
        }
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory);
             Argon2AesSecretStore wrongStore = new Argon2AesSecretStore(database.encryptedSecrets(),
                     "wrong master password".toCharArray())) {
            SecretStoreException failure = assertThrows(SecretStoreException.class,
                    () -> wrongStore.read("ssh/server-one/password"));
            assertEquals("secret.error.decryptFailed", failure.failure().userMessage().key());
            assertFalse(failure.failure().diagnostic().contains("remote-password"));
        }
    }

    @Test
    void credentialManagerCanReadAnAbsentAppCredentialWithoutWriting() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        try (WindowsCredentialManagerSecretStore store = new WindowsCredentialManagerSecretStore()) {
            String key = "verification/" + UUID.randomUUID();
            var result = store.read(key);
            assertTrue(result.isEmpty(), () -> "read an unexpected value of length "
                    + result.map(value -> value.length).orElse(0));
            assertFalse(store.delete(key));
        }
    }

    @Test
    void encryptedStoreDeletesOnlyTheExactKey() throws Exception {
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("delete"));
             Argon2AesSecretStore store = new Argon2AesSecretStore(database.encryptedSecrets(),
                     "correct master password".toCharArray())) {
            store.save("ssh/server-one/password", "first".toCharArray());
            store.save("ssh/server-two/password", "second".toCharArray());

            assertTrue(store.delete("ssh/server-one/password"));
            assertTrue(store.read("ssh/server-one/password").isEmpty());
            assertFalse(store.delete("ssh/server-one/password"));
            assertArrayEquals("second".toCharArray(), store.read("ssh/server-two/password").orElseThrow());
        }
    }

    @Test
    void namespaceDeletionParserReportsSortedDeletedAndResidualTargets() throws Exception {
        String deleted = Base64.getEncoder().encodeToString(
                "WindowsToLinux/ssh/server-one/password".getBytes(StandardCharsets.UTF_8));
        String residual = Base64.getEncoder().encodeToString(
                "WindowsToLinux/ManuallyCreated".getBytes(StandardCharsets.UTF_8));
        var result = WindowsCredentialManagerSecretStore.parseNamespaceDeletion(
                "ignored PowerShell line\nRESIDUAL:" + residual + "\nDELETED:" + deleted);

        assertEquals(java.util.List.of("WindowsToLinux/ssh/server-one/password"), result.deletedTargets());
        assertEquals(java.util.List.of("WindowsToLinux/ManuallyCreated"), result.residualTargets());
        assertThrows(SecretStoreException.class, () ->
                WindowsCredentialManagerSecretStore.parseNamespaceDeletion("EMPTY\nDELETED:" + deleted));
        assertThrows(SecretStoreException.class, () ->
                WindowsCredentialManagerSecretStore.parseNamespaceDeletion("DELETED:" + residual));
    }
}
