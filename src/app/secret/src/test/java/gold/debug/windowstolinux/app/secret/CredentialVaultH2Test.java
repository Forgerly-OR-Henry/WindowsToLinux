package gold.debug.windowstolinux.app.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.persistence.repository.SecurityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialVaultH2Test {
    @TempDir
    Path directory;

    static final DatabaseProtection TEST_ONLY_PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] clear) {
            return clear.clone();
        }

        public byte[] unprotect(byte[] encrypted) {
            return encrypted.clone();
        }
    };
    char[] password() {
        return "testpass".toCharArray();
    }

    SecurityRepository repository(PlatformDatabase database) {
        return new SecurityRepository(new RepositoryConnections(database::openConnection));
    }

    @Test
    void publicProblemSurvivesTheTransactionBridgeWithoutLeakingCauseMessages() throws Exception {
        try (var database = PlatformDatabase.open(directory, TEST_ONLY_PROTECTION)) {
            var repository = repository(database);
            var vault = new CredentialVault(repository);
            try (var session = vault.initialize(password())) {
                var original = SecretStoreException.create(SecretStoreFailureType.ENCRYPT_FAILED,
                        "Credential persistence failed", new SQLException("password=private-fixture"));
                var returned = assertThrows(SecretStoreException.class, () -> session.transaction(() -> {
                    session.save("rollback", "private-value".toCharArray());
                    throw original;
                }));
                assertSame(original, returned);
                assertSame(original.problem(), returned.problem());
                assertTrue(repository.find("rollback").isEmpty());
                assertFalse(returned.problem().toString().contains("private-fixture"));
                assertFalse(returned.problem().toString().contains("private-value"));
                assertFalse(returned.getMessage().contains("private-fixture"));
                assertTrue(returned.problem().diagnosticId().isPresent());
            }
        }
    }

    @Test
    void existingVaultProtectsH2CredentialsAcrossPasswordChangesAndDatabaseReopen() throws Exception {
        byte[] before;
        try (var database = PlatformDatabase.open(directory, TEST_ONLY_PROTECTION)) {
            var repository = repository(database);
            var vault = new CredentialVault(repository);
            assertFalse(vault.configured());
            var supplied = password();
            var session = vault.initialize(supplied);
            assertArrayEquals(new char[supplied.length], supplied);
            session.save("ssh/test", "secret value".toCharArray());
            try (var stored = repository.find("ssh/test").orElseThrow()) {
                before = stored.payload();
                assertEquals("AES256_GCM_V1", stored.storageFormat());
            }
            var task = session.fork();
            session.close();
            assertThrows(IllegalStateException.class, () -> session.read("ssh/test"));
            assertArrayEquals("secret value".toCharArray(), task.read("ssh/test").orElseThrow());
            assertThrows(SecretStoreException.class, () -> vault.unlock("bad-password".toCharArray()));
            vault.changePassword(password(), "new-pass".toCharArray());
            task.close();
            assertThrows(IllegalStateException.class, task::fork);
            assertEquals(0, database.openConnections());
        }
        try (var database = PlatformDatabase.open(directory, TEST_ONLY_PROTECTION)) {
            var repository = repository(database);
            var vault = new CredentialVault(repository);
            assertTrue(vault.configured());
            assertThrows(SecretStoreException.class, () -> vault.unlock(password()));
            try (var session = vault.unlock("new-pass".toCharArray());
                    var stored = repository.find("ssh/test").orElseThrow()) {
                assertArrayEquals(before, stored.payload());
                assertArrayEquals("secret value".toCharArray(), session.read("ssh/test").orElseThrow());
            }
        }
    }

    @Test
    void conversionRollsBackEveryCredentialAndRetainedReferencesRemainProtected() throws Exception {
        try (var database = PlatformDatabase.open(directory, TEST_ONLY_PROTECTION)) {
            var repository = repository(database);
            var vault = new CredentialVault(repository);
            try (var session = vault.initialize(password())) {
                session.save("a", "first secret".toCharArray());
                session.save("b", "second secret".toCharArray());
                try (var c = database.openConnection(); var s = c.createStatement()) {
                    s.execute(
                            "ALTER TABLE credential ADD CONSTRAINT test_reject_plain CHECK(credential_id<>'b' OR storage_format<>'PLAIN_V1')");
                    assertThrows(SecretStoreException.class, () -> vault.setEncryption(password(), false));
                    assertTrue(vault.encryptionEnabled());
                    for (var id : java.util.List.of("a", "b"))
                        try (var value = repository.find(id).orElseThrow()) {
                            assertEquals("AES256_GCM_V1", value.storageFormat());
                        }
                    s.execute("ALTER TABLE credential DROP CONSTRAINT test_reject_plain");
                    var generation = UUID.randomUUID();
                    s.execute("INSERT INTO mod_registry(mod_id,updated_at) VALUES('fixture.mod',0)");
                    try (var p = c.prepareStatement(
                            "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at,validated_at) VALUES('fixture.mod','1',?,'fixture_schema','fixture_user','READY',0,0)")) {
                        p.setObject(1, generation);
                        p.executeUpdate();
                    }
                    try (var p = c.prepareStatement(
                            "INSERT INTO mod_credential_reference VALUES(?,'a','fixture.mod','1',?,'record','fixture','UNKNOWN',0)")) {
                        p.setObject(1, UUID.randomUUID());
                        p.setObject(2, generation);
                        p.executeUpdate();
                    }
                    assertThrows(SecretStoreException.class, () -> session.save("a", "new secret".toCharArray()));
                    assertThrows(SQLException.class, () -> repository.delete("a"));
                }
                vault.setEncryption(password(), false);
                assertFalse(vault.encryptionEnabled());
                assertArrayEquals("first secret".toCharArray(), session.read("a").orElseThrow());
                vault.setEncryption(password(), true);
                assertTrue(vault.encryptionEnabled());
                assertArrayEquals("second secret".toCharArray(), session.read("b").orElseThrow());
            }
            assertEquals(0, database.openConnections());
        }
    }

    @Test
    void tamperingWithH2PayloadOrCredentialIdentityFailsAuthentication() throws Exception {
        try (var database = PlatformDatabase.open(directory, TEST_ONLY_PROTECTION)) {
            var repository = repository(database);
            var vault = new CredentialVault(repository);
            try (var session = vault.initialize(password())) {
                session.save("a", "first secret".toCharArray());
                session.save("b", "second secret".toCharArray());
                try (var c = database.openConnection(); var s = c.createStatement()) {
                    s.executeUpdate("UPDATE credential SET credential_id='swapped' WHERE credential_id='a'");
                    assertThrows(SecretStoreException.class, () -> session.read("swapped"));
                    try (var stored = repository.find("b").orElseThrow();
                            var p = c.prepareStatement("UPDATE credential SET payload=? WHERE credential_id='b'")) {
                        var bytes = stored.payload();
                        Arrays.fill(bytes, (byte) 0);
                        p.setBytes(1, bytes);
                        p.executeUpdate();
                    }
                    assertThrows(SecretStoreException.class, () -> session.read("b"));
                }
            }
        }
    }
}
