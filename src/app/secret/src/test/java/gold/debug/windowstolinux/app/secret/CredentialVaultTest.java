package gold.debug.windowstolinux.app.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.*;
import gold.debug.windowstolinux.app.secret.crypto.PasswordKeyCrypto;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialVaultTest {
    @TempDir
    Path directory;
    private char[] password() {
        return "testpass".toCharArray();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("windowstolinux.db"));
    }

    @Test
    void setupUnlockChangeAndReferenceLifetimePreserveCredentialCiphertext() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var vault = new CredentialVault(db.security());
            assertFalse(vault.configured());
            char[] supplied = password();
            var session = vault.initialize(supplied);
            assertArrayEquals(new char[supplied.length], supplied);
            session.save("ssh/test", "secret value".toCharArray());
            byte[] before;
            try (var stored = db.security().find("ssh/test").orElseThrow()) {
                before = stored.payload();
                assertEquals("AES256_GCM_V1", stored.storageFormat());
            }
            var task = session.fork();
            session.close();
            assertThrows(IllegalStateException.class, () -> session.read("ssh/test"));
            assertArrayEquals("secret value".toCharArray(), task.read("ssh/test").orElseThrow());
            assertThrows(SecretStoreException.class, () -> vault.unlock("incorrect password".toCharArray()));
            vault.changePassword(password(), "new-pass".toCharArray());
            assertThrows(SecretStoreException.class, () -> vault.unlock(password()));
            try (var replacement = vault.unlock("new-pass".toCharArray())) {
                assertArrayEquals("secret value".toCharArray(), replacement.read("ssh/test").orElseThrow());
            }
            try (var stored = db.security().find("ssh/test").orElseThrow()) {
                assertArrayEquals(before, stored.payload());
            }
            task.close();
            assertThrows(IllegalStateException.class, task::fork);
        }
    }

    @Test
    void saltsAreIndependentAndMalformedFormatsFailClosed() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var vault = new CredentialVault(db.security());
            try (var ignored = vault.initialize(password())) {
            }
            var profile = db.security().profile().orElseThrow();
            String[] pieces = profile.passwordVerifier().split("\\$");
            assertFalse(Arrays.equals(Base64.getDecoder().decode(pieces[4]), profile.keyDerivationSalt()));
            assertThrows(Exception.class,
                    () -> PasswordKeyCrypto.unlock(
                            new StoredSecurityProfile(true, profile.passwordVerifier(), profile.wrappedDataKey(),
                                    profile.keyWrapAlgorithm(), profile.keyWrapNonce(),
                                    profile.keyDerivationAlgorithm(), profile.keyDerivationSalt(), "unknown", 1),
                            password()));
            try (var invalid = new StoredCredential("a", "UNKNOWN", new byte[]{1}, null)) {
                assertThrows(Exception.class, () -> PasswordKeyCrypto.decode(invalid, new byte[32], true));
            }
            assertThrows(SecretStoreException.class, () -> vault.changePassword(password(), "1234567".toCharArray()));
            try (var ignored = vault.unlock(password())) {
            }
        }
    }

    @Test
    void ciphertextTamperingAndCredentialSubstitutionFailAuthentication() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var vault = new CredentialVault(db.security());
            try (var session = vault.initialize(password())) {
                session.save("a", "first secret".toCharArray());
                session.save("b", "second secret".toCharArray());
                try (var c = connection(); var s = c.createStatement()) {
                    s.execute("UPDATE credential SET credential_id='swapped' WHERE credential_id='a'");
                    assertThrows(SecretStoreException.class, () -> session.read("swapped"));
                    s.execute("UPDATE credential SET payload=zeroblob(length(payload)) WHERE credential_id='b'");
                    assertThrows(SecretStoreException.class, () -> session.read("b"));
                }
            }
        }
    }

    @Test
    void encryptionConversionIsAtomicAndDoesNotChangeBusinessRevisions() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var vault = new CredentialVault(db.security());
            try (var session = vault.initialize(password())) {
                session.save("a", "first secret".toCharArray());
                session.save("b", "second secret".toCharArray());
                db.applicationSecrets().saveRevision(new StoredApplicationSecretRevision(
                        new SecretReference("business", 1), "a", java.time.Instant.now()));
                assertThrows(SecretStoreException.class, () -> session.save("a", "changed".toCharArray()));
                try (var c = connection(); var s = c.createStatement()) {
                    s.execute(
                            "CREATE TRIGGER fail_second BEFORE UPDATE ON credential WHEN NEW.credential_id='b' BEGIN SELECT RAISE(ABORT,'fixture'); END");
                    assertThrows(SecretStoreException.class, () -> vault.setEncryption(password(), false));
                    assertTrue(vault.encryptionEnabled());
                    for (String id : List.of("a", "b"))
                        try (var stored = db.security().find(id).orElseThrow()) {
                            assertEquals("AES256_GCM_V1", stored.storageFormat());
                        }
                    s.execute("DROP TRIGGER fail_second");
                }
                vault.setEncryption(password(), false);
                assertFalse(vault.encryptionEnabled());
                assertArrayEquals("first secret".toCharArray(), session.read("a").orElseThrow());
                try (var stored = db.security().find("a").orElseThrow()) {
                    assertEquals("PLAIN_V1", stored.storageFormat());
                }
                vault.setEncryption(password(), true);
                assertTrue(vault.encryptionEnabled());
                assertEquals("a", db.applicationSecrets().findRevision(new SecretReference("business", 1)).orElseThrow()
                        .credentialKey());
                assertArrayEquals("first secret".toCharArray(), session.read("a").orElseThrow());
            }
        }
    }

    @Test
    void credentialAndOwnerWritesRollbackTogether() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var vault = new CredentialVault(db.security());
            try (var session = vault.initialize(password())) {
                assertThrows(SQLException.class, () -> session.transaction(() -> {
                    session.save("a", "first secret".toCharArray());
                    db.servers().saveServerProfile(new StoredServerProfile("server", "example.test", 22, "user", "a"));
                    throw new SQLException("simulate failed configuration");
                }));
                assertTrue(db.security().find("a").isEmpty());
                assertTrue(db.servers().findServerProfile("server").isEmpty());
            }
        }
    }
}
