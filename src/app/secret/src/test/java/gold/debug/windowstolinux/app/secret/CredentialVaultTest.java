package gold.debug.windowstolinux.app.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.PlatformPersistence;
import gold.debug.windowstolinux.app.db.security.StoredCredential;
import gold.debug.windowstolinux.app.db.security.StoredSecurityProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.secret.crypto.PasswordKeyCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialVaultTest {
    @TempDir
    Path directory;

    private char[] password() {
        return "testpass".toCharArray();
    }

    private PlatformPersistence open() throws Exception {
        return new PlatformPersistence(PlatformDatabase.open(directory, CredentialVaultH2Test.TEST_ONLY_PROTECTION));
    }

    @Test
    void setupUnlockChangeAndReferenceLifetimePreserveCredentialCiphertext() throws Exception {
        try (var db = open()) {
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
        try (var db = open()) {
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
        try (var db = open()) {
            var vault = new CredentialVault(db.security());
            try (var session = vault.initialize(password())) {
                session.save("a", "first secret".toCharArray());
                session.save("b", "second secret".toCharArray());
                try (var c = db.database().openConnection(); var s = c.createStatement()) {
                    s.execute("UPDATE credential SET credential_id='swapped' WHERE credential_id='a'");
                    assertThrows(SecretStoreException.class, () -> session.read("swapped"));
                    try (var stored = db.security().find("b").orElseThrow();
                         var update = c
                             .prepareStatement("UPDATE credential SET payload=? WHERE credential_id='b'")) {
                        update.setBytes(1, new byte[stored.payload().length]);
                        update.executeUpdate();
                    }
                    assertThrows(SecretStoreException.class, () -> session.read("b"));
                }
            }
        }
    }

    @Test
    void encryptionConversionIsAtomicAndDoesNotChangeBusinessRevisions() throws Exception {
        try (var db = open()) {
            var vault = new CredentialVault(db.security());
            try (var session = vault.initialize(password())) {
                session.save("a", "first secret".toCharArray());
                session.save("b", "second secret".toCharArray());
                try (var c = db.database().openConnection(); var s = c.createStatement()) {
                    s.execute("INSERT INTO mod_registry(mod_id,updated_at) VALUES('fixture.mod',0)");
                    s.execute(
                        "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at,validated_at) VALUES('fixture.mod','1',RANDOM_UUID(),'fixture_schema','fixture_user','READY',0,0)");
                    s.execute(
                        "INSERT INTO mod_credential_reference SELECT RANDOM_UUID(),'a',mod_id,schema_version,generation,'business','fixture','ACTIVE',0 FROM mod_schema WHERE mod_id='fixture.mod'");
                }
                assertThrows(SecretStoreException.class, () -> session.save("a", "changed".toCharArray()));
                try (var c = db.database().openConnection(); var s = c.createStatement()) {
                    s.execute(
                        "ALTER TABLE credential ADD CONSTRAINT fail_second CHECK(credential_id<>'b' OR storage_format<>'PLAIN_V1')");
                    assertThrows(SecretStoreException.class, () -> vault.setEncryption(password(), false));
                    assertTrue(vault.encryptionEnabled());
                    for (String id : List.of("a", "b"))
                        try (var stored = db.security().find(id).orElseThrow()) {
                            assertEquals("AES256_GCM_V1", stored.storageFormat());
                        }
                    s.execute("ALTER TABLE credential DROP CONSTRAINT fail_second");
                }
                vault.setEncryption(password(), false);
                assertFalse(vault.encryptionEnabled());
                assertArrayEquals("first secret".toCharArray(), session.read("a").orElseThrow());
                try (var stored = db.security().find("a").orElseThrow()) {
                    assertEquals("PLAIN_V1", stored.storageFormat());
                }
                vault.setEncryption(password(), true);
                assertTrue(vault.encryptionEnabled());
                try (var c = db.database().openConnection();
                     var s = c.createStatement();
                     var rows = s.executeQuery(
                         "SELECT credential_id,state FROM mod_credential_reference WHERE business_key='business'")) {
                    assertTrue(rows.next());
                    assertEquals("a", rows.getString(1));
                    assertEquals("ACTIVE", rows.getString(2));
                    assertFalse(rows.next());
                }
                assertArrayEquals("first secret".toCharArray(), session.read("a").orElseThrow());
            }
        }
    }

    @Test
    void credentialAndOwnerWritesRollbackTogether() throws Exception {
        try (var db = open()) {
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
