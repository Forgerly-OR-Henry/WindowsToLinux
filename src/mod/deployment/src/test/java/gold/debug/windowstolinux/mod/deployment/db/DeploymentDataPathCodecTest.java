package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDataPath;
import org.junit.jupiter.api.Test;

class DeploymentDataPathCodecTest {
    private final DeploymentDataPathCodec codec = new DeploymentDataPathCodec();

    @Test
    void roundTripsCanonicalReviewedPathsAndAnExplicitEmptyInventory() throws Exception {
        List<DeploymentDataPath> paths = List.of(
                new DeploymentDataPath("uploads", DeploymentDataPath.AccessMode.READ_WRITE, "uploads-v2", false),
                new DeploymentDataPath("cache/index", DeploymentDataPath.AccessMode.READ_ONLY, "cache-v1", true));

        assertEquals(List.of(paths.get(1), paths.get(0)), codec.read(DeploymentCodecCompatibility.write(paths)));
        assertEquals(List.of(), codec.read(DeploymentCodecCompatibility.write(List.of())));
    }

    @Test
    void rejectsUnsupportedTruncatedTrailingAndNonCanonicalDocuments() throws Exception {
        byte[] valid = DeploymentCodecCompatibility.write(
                List.of(new DeploymentDataPath("data", DeploymentDataPath.AccessMode.READ_WRITE, "data-v1", true)));
        byte[] unsupported = valid.clone();
        unsupported[4] = 99;
        assertThrows(java.io.IOException.class, () -> codec.read(unsupported));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, 8)));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, valid.length + 1)));

        byte[] invalidAccess = valid.clone();
        invalidAccess[15] = 99;
        assertThrows(java.io.IOException.class, () -> codec.read(invalidAccess));
        assertThrows(java.io.IOException.class, () -> codec.read(new byte[1_048_577]));
    }

    @Test
    void rejectsDuplicateReviewedPathsBeforePersistence() {
        DeploymentDataPath first = new DeploymentDataPath("data", DeploymentDataPath.AccessMode.READ_WRITE, "data-v1",
                true);
        DeploymentDataPath second = new DeploymentDataPath("data", DeploymentDataPath.AccessMode.READ_ONLY, "data-v2",
                true);

        assertThrows(java.io.IOException.class, () -> DeploymentCodecCompatibility.write(List.of(first, second)));
    }
}
