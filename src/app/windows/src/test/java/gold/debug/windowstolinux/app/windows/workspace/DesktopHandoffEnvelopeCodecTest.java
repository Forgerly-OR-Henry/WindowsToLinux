package gold.debug.windowstolinux.app.windows.workspace;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopHandoffEnvelopeCodecTest {
    private final DesktopHandoffEnvelopeCodec codec = new DesktopHandoffEnvelopeCodec();

    @Test
    void authenticatesPurposeAndPayloadWithoutRetainingAKey() throws Exception {
        byte[] payload = "bounded-update-handoff".getBytes(StandardCharsets.UTF_8);
        byte[] document = codec.write(DesktopHandoffEnvelopeCodec.PurposeType.UPDATE, payload, key(1));

        assertArrayEquals(payload, codec.read(DesktopHandoffEnvelopeCodec.PurposeType.UPDATE, document, key(1)));
        assertFailure(() -> codec.read(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL, document, key(1)));
    }

    @Test
    void rejectsTamperingWrongKeysTruncationAndWeakKeysUniformly() throws Exception {
        byte[] document = codec.write(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL,
                "bounded-uninstall-handoff".getBytes(StandardCharsets.UTF_8), key(2));
        byte[] tampered = document.clone();
        tampered[15] ^= 1;

        assertFailure(() -> codec.read(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL, tampered, key(2)));
        assertFailure(() -> codec.read(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL, document, key(3)));
        assertFailure(() -> codec.read(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL,
                Arrays.copyOf(document, 31), key(2)));
        assertFailure(() -> codec.write(DesktopHandoffEnvelopeCodec.PurposeType.UPDATE,
                new byte[]{1}, new SecretKeySpec(new byte[16], "HmacSHA256")));
        assertFailure(() -> codec.read(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL, document, null));
        assertFailure(() -> codec.read(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL, null, key(2)));
    }

    private static SecretKey key(int marker) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) marker);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private static void assertFailure(ThrowingOperation operation) {
        WindowsWorkspaceException failure = assertThrows(WindowsWorkspaceException.class, operation::run);
        assertEquals("windows.workspace.handoff-invalid", failure.failure().code());
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
