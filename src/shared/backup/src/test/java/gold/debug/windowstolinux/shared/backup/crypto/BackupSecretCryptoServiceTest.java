package gold.debug.windowstolinux.shared.backup.crypto;

import gold.debug.windowstolinux.shared.backup.format.BackupSecretEnvelope;
import gold.debug.windowstolinux.shared.backup.format.BackupSecretEnvelopeCodec;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupSecretCryptoServiceTest {
    private static final char[] PASSWORD = "independent backup password".toCharArray();

    @Test
    void encryptsAndAuthenticatesWithFreshArgon2idParameters() throws Exception {
        BackupSecretCryptoService service = new BackupSecretCryptoService();
        BackupSecretEnvelopeCodec codec = new BackupSecretEnvelopeCodec();
        byte[] plaintext = "{\"db.password\":\"private-value\"}".getBytes(StandardCharsets.UTF_8);

        byte[] firstDocument = service.encrypt(PASSWORD, plaintext);
        byte[] secondDocument = service.encrypt(PASSWORD, plaintext);
        BackupSecretEnvelope first = codec.read(firstDocument);
        BackupSecretEnvelope second = codec.read(secondDocument);

        assertArrayEquals(plaintext, service.decrypt(PASSWORD, firstDocument));
        assertEquals("Argon2id", first.keyDerivation());
        assertEquals("AES-256-GCM", first.cipher());
        assertEquals(64 * 1024, first.memoryKiB());
        assertNotEquals(java.util.HexFormat.of().formatHex(first.salt()),
                java.util.HexFormat.of().formatHex(second.salt()));
        assertNotEquals(java.util.HexFormat.of().formatHex(first.nonce()),
                java.util.HexFormat.of().formatHex(second.nonce()));
        assertFalse(new String(firstDocument, StandardCharsets.UTF_8).contains("private-value"));
    }

    @Test
    void wrongPasswordAndTamperingExposeTheSameFailureAndNoPlaintext() throws Exception {
        BackupSecretCryptoService service = new BackupSecretCryptoService();
        BackupSecretEnvelopeCodec codec = new BackupSecretEnvelopeCodec();
        String plaintextMarker = "highly-private-value-4197";
        byte[] document = service.encrypt(PASSWORD, plaintextMarker.getBytes(StandardCharsets.UTF_8));
        BackupSecretEnvelope envelope = codec.read(document);
        byte[] tamperedCiphertext = envelope.ciphertext();
        tamperedCiphertext[0] ^= 1;
        byte[] tampered = codec.write(new BackupSecretEnvelope(
                envelope.format(), envelope.keyDerivation(), envelope.memoryKiB(), envelope.iterations(),
                envelope.parallelism(), envelope.salt(), envelope.cipher(), envelope.nonce(), tamperedCiphertext));

        BackupSecretException wrongPassword = assertThrows(BackupSecretException.class,
                () -> service.decrypt("a different backup password".toCharArray(), document));
        BackupSecretException changedContent = assertThrows(BackupSecretException.class,
                () -> service.decrypt(PASSWORD, tampered));

        assertEquals(BackupSecretFailureType.DECRYPT_FAILED.code(), wrongPassword.failure().code());
        assertEquals(wrongPassword.failure().userMessage().key(), changedContent.failure().userMessage().key());
        assertFalse(wrongPassword.failure().diagnostic().contains(plaintextMarker));
        assertFalse(changedContent.failure().diagnostic().contains(plaintextMarker));
    }

    @Test
    void serviceHasNoPasswordOrDerivedKeyField() {
        boolean hasSensitiveInstanceArray = Arrays.stream(BackupSecretCryptoService.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .anyMatch(field -> field.getType() == byte[].class || field.getType() == char[].class);

        assertFalse(hasSensitiveInstanceArray);
    }

    @Test
    void rejectsShortBackupPasswordBeforeEncryption() {
        BackupSecretCryptoService service = new BackupSecretCryptoService();

        BackupSecretException failure = assertThrows(BackupSecretException.class,
                () -> service.encrypt("short".toCharArray(), new byte[]{1}));

        assertEquals(BackupSecretFailureType.PASSWORD_INVALID.code(), failure.failure().code());
    }

    @Test
    void encryptsAndDecodesAnExactCanonicalRevisionSet() throws Exception {
        BackupSecretCryptoService service = new BackupSecretCryptoService();
        try (ResolvedSecretRevision second = new ResolvedSecretRevision(
                new SecretReference("database-password", 2), "数据库-secret".toCharArray());
             ResolvedSecretRevision first = new ResolvedSecretRevision(
                     new SecretReference("api-token", 1), "token-value".toCharArray())) {
            byte[] encrypted = service.encryptRevisions(PASSWORD, java.util.List.of(second, first));
            try (BackupSecretDocument restored = service.decryptRevisions(PASSWORD, encrypted)) {
                assertEquals(java.util.List.of(first.reference(), second.reference()),
                        restored.revisions().stream().map(ResolvedSecretRevision::reference).toList());
                assertEquals(first.digest(), restored.revisions().get(0).digest());
                assertEquals(second.digest(), restored.revisions().get(1).digest());
                byte[] restoredValue = restored.revisions().get(1).copyValue();
                try {
                    assertArrayEquals("数据库-secret".getBytes(StandardCharsets.UTF_8), restoredValue);
                } finally {
                    Arrays.fill(restoredValue, (byte) 0);
                }
            }
        }
    }

    @Test
    void authenticatedMalformedPayloadReturnsNoRevisionDocument() throws Exception {
        BackupSecretCryptoService service = new BackupSecretCryptoService();
        String secretMarker = "authenticated-but-malformed-secret";
        byte[] envelope = service.encrypt(PASSWORD, secretMarker.getBytes(StandardCharsets.UTF_8));

        BackupSecretException failure = assertThrows(BackupSecretException.class,
                () -> service.decryptRevisions(PASSWORD, envelope));

        assertEquals(BackupSecretFailureType.PAYLOAD_INVALID.code(), failure.failure().code());
        assertFalse(failure.failure().diagnostic().contains(secretMarker));
    }

    @Test
    void duplicateRevisionIsRejectedBeforeEncryption() {
        BackupSecretCryptoService service = new BackupSecretCryptoService();
        SecretReference reference = new SecretReference("duplicate", 1);
        try (ResolvedSecretRevision first = new ResolvedSecretRevision(reference, "first-value".toCharArray());
             ResolvedSecretRevision second = new ResolvedSecretRevision(reference, "second-value".toCharArray())) {
            BackupSecretException failure = assertThrows(BackupSecretException.class,
                    () -> service.encryptRevisions(PASSWORD, java.util.List.of(first, second)));

            assertEquals(BackupSecretFailureType.ENCRYPT_FAILED.code(), failure.failure().code());
            assertTrue(failure.failure().diagnostic().contains("encoding failed"));
        }
    }

    @Test
    void preservesDifferentRevisionsOfTheSameIdentifier() throws Exception {
        BackupSecretCryptoService service = new BackupSecretCryptoService();
        try (ResolvedSecretRevision first = new ResolvedSecretRevision(
                new SecretReference("rotated-token", 1), "old-value".toCharArray());
             ResolvedSecretRevision second = new ResolvedSecretRevision(
                     new SecretReference("rotated-token", 2), "new-value".toCharArray())) {
            byte[] encrypted = service.encryptRevisions(PASSWORD, java.util.List.of(second, first));

            try (BackupSecretDocument restored = service.decryptRevisions(PASSWORD, encrypted)) {
                assertEquals(java.util.List.of(first.reference(), second.reference()),
                        restored.revisions().stream().map(ResolvedSecretRevision::reference).toList());
            }
        }
    }
}
