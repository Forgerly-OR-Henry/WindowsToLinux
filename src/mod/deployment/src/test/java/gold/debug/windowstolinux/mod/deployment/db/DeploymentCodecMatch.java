package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Checks independently frozen original bytes without a legacy runtime dependency. / 不依赖旧运行时地核对独立冻结的原字节。 */
final class DeploymentCodecMatch {
    private static final Properties ORIGINAL = originals();

    private DeploymentCodecMatch() {
    }

    static byte[] write(DeploymentRuntimeDefinition runtime) throws IOException {
        return original("runtime", new DeploymentRuntimeCodec().write(runtime));
    }

    static byte[] write(DeploymentHealthCheck health) throws IOException {
        return original("health", new DeploymentHealthCodec().write(health));
    }

    static byte[] write(DeploymentUnitRuntimeConfig runtime) throws IOException {
        return original("unit", new DeploymentUnitRuntimeCodec().write(runtime));
    }

    static byte[] write(DeploymentResourceBindings bindings) throws IOException {
        return original("resources", new DeploymentResourceCodec().write(bindings));
    }

    static byte[] write(List<DeploymentStoragePath> paths) throws IOException {
        return original("paths", new DeploymentStoragePathCodec().write(paths));
    }

    private static byte[] original(String kind, byte[] encoded) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
            String frozen = ORIGINAL.getProperty(kind + "-" + digest);
            assertNotNull(frozen, "No independently frozen original sample matches " + kind + "/" + digest);
            byte[] expected = Base64.getDecoder().decode(frozen);
            assertArrayEquals(expected, encoded);
            return expected;
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError("SHA-256 is required to identify original samples", failure);
        }
    }

    private static Properties originals() {
        var values = new Properties();
        try (var input = DeploymentCodecMatch.class.getResourceAsStream("/original-codec-goldens.properties")) {
            assertNotNull(input, "Original format fixtures must be packaged");
            values.load(input);
            return values;
        } catch (IOException failure) {
            throw new AssertionError("Original format fixtures cannot be read", failure);
        }
    }
}
