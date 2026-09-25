package gold.debug.windowstolinux.shared.backup.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class BackupSecretEnvelopeCodecTest {
    @Test
    void roundTripsOnlyPublicParametersAndCiphertext() throws Exception {
        BackupSecretEnvelope envelope = new BackupSecretEnvelope(BackupSecretEnvelope.CURRENT_FORMAT,
                BackupSecretEnvelope.CURRENT_KEY_DERIVATION, 64 * 1024, 3, 1, new byte[16],
                BackupSecretEnvelope.CURRENT_CIPHER, new byte[12], new byte[32]);
        BackupSecretEnvelopeCodec codec = new BackupSecretEnvelopeCodec();

        BackupSecretEnvelope restored = codec.read(codec.write(envelope));

        assertEquals(envelope.format(), restored.format());
        assertEquals(envelope.memoryKiB(), restored.memoryKiB());
        assertArrayEquals(envelope.salt(), restored.salt());
        assertArrayEquals(envelope.ciphertext(), restored.ciphertext());
    }

    @Test
    void rejectsUnknownFieldsAndUnsafeDerivationBounds() throws Exception {
        BackupSecretEnvelopeCodec codec = new BackupSecretEnvelopeCodec();
        BackupSecretEnvelope envelope = new BackupSecretEnvelope(BackupSecretEnvelope.CURRENT_FORMAT,
                BackupSecretEnvelope.CURRENT_KEY_DERIVATION, 64 * 1024, 3, 1, new byte[16],
                BackupSecretEnvelope.CURRENT_CIPHER, new byte[12], new byte[16]);
        String json = new String(codec.write(envelope), StandardCharsets.UTF_8).replaceFirst("\\{",
                "{\\\"unknown\\\":true,");

        assertThrows(Exception.class, () -> codec.read(json.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> new BackupSecretEnvelope(BackupSecretEnvelope.CURRENT_FORMAT,
                        BackupSecretEnvelope.CURRENT_KEY_DERIVATION, 1024, 3, 1, new byte[16],
                        BackupSecretEnvelope.CURRENT_CIPHER, new byte[12], new byte[16]));
    }
}
