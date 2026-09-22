package gold.debug.windowstolinux.shared.config.secretref;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import org.junit.jupiter.api.Test;

class ResolvedSecretRevisionTest {
    @Test
    void exposesOnlyCopiesAndPublicIntegrityMetadata() {
        char[] source = "temporary-value".toCharArray();
        ResolvedSecretRevision resolved = new ResolvedSecretRevision(new SecretReference("database-password", 2),
                source);
        java.util.Arrays.fill(source, '\0');
        byte[] copy = resolved.copyValue();
        try {
            assertArrayEquals("temporary-value".getBytes(java.nio.charset.StandardCharsets.UTF_8), copy);
            assertFalse(resolved.toString().contains("temporary-value"));
        } finally {
            java.util.Arrays.fill(copy, (byte) 0);
            resolved.close();
        }
        assertArrayEquals(new byte["temporary-value".length()], resolved.copyValue());
    }

    @Test
    void rejectsEnvironmentNameCollisions() {
        SecretRevisionDigest first = new SecretRevisionDigest(new SecretReference("api-token", 1), "a".repeat(64), 1);
        SecretRevisionDigest second = new SecretRevisionDigest(new SecretReference("api.token", 1), "b".repeat(64), 1);
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentInputManifest("c".repeat(64), List.of(first, second)));
    }
}
